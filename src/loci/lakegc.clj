(ns loci.lakegc
  "What each lake table costs, and reclaiming the ones nothing needs.

   Writes never overwrite — every `put-rows!` mints a new lake table — so a
   table refreshed ten times leaves ten lake tables behind. This namespace is
   what tells you that and lets you act on it, one object at a time.

   It issues no SQL of its own; `loci.lake` owns the connection and every query.

   Measured 2026-08-17: DuckLake's own `cleanup_old_files` cannot do this. It
   will not touch a file until every snapshot below its `end_snapshot` is
   expired, and a DuckLake snapshot is catalog-wide, so freeing one object's old
   version always means expiring the snapshots other objects' live handles read
   at — measured, that broke three unrelated handles. `DROP TABLE` plus deleting
   that table's own Parquet frees the same bytes, expires nothing, and leaves
   every other handle reading."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.lake :as lake]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(def default-window
  "How much history a reclaim keeps: whichever of these keeps more.

   A version count alone would drop a busy afternoon's work; an age alone puts
   no ceiling on a table rewritten forty times in that afternoon. Taking the
   larger keeps recent work under either reading, and still bounds a table once
   it goes quiet."
  {:versions 3 :days 30})

;; The `:tx`-aware handle walk moved to `loci.tablestore` on 2026-08-22 — `tbl/event-handles`
;; and `tbl/handles-in-history` — because the files GC needs the same walk, and a second copy
;; is a second chance to miss a sub-event and reclaim rows a live object is still using.

(defn classify
  "`{lake-table {:class … :object … :ts …}}` for every table in the lake.

   Four classes. **current** — named by a handle on a live object; never
   touched. **in-window** — superseded but inside `window`; kept, so undo and
   `as-of` stay exact there. **reclaimable** — superseded and outside it.
   **orphaned** — named by no handle anywhere in the log, which `undo!` produces
   (it deletes the event) and so does a write whose caller died between the lake
   commit and `sub/commit!`. Orphans ignore the window because nothing can ever
   reference them again."
  [st lk {:keys [versions days] :as _window}]
  (let [live    (set (keep #(:table (:value %)) (vals (sub/objects st))))
        by-obj  (tbl/handles-in-history st)
        cutoff  (- (System/currentTimeMillis) (* days 24 60 60 1000))
        seen    (into {} (for [[o hs] by-obj
                               [i {:keys [handle ts]}] (map-indexed vector hs)]
                           [(:table handle) {:object o :ts ts :age-rank i}]))]
    (into {}
          (for [{:keys [table_name]} (lake/live-tables lk)
                :let [m (get seen table_name)]]
            [table_name
             (cond
               (nil? m)                    {:class :orphaned :object nil :ts nil}
               (live table_name)           (assoc m :class :current)
               (or (< (:age-rank m) versions)
                   (>= (or (:ts m) 0) cutoff)) (assoc m :class :in-window)
               :else                       (assoc m :class :reclaimable))]))))

(defn report
  "What every object's tables cost, and what a reclaim would free.

   Walks the log once and asks the catalog for sizes in one query. **Fetches no
   rows** — a storage panel that costs a row read per table would be slower than
   the thing it is reporting on, and DuckDB's JDBC driver is slow row-wise
   (42 ms to walk 1,000×8 cells, measured 2026-08-17).

   `:lake` reports `:files` and `:catalog` separately because `catalog.duckdb`
   never shrinks and was 98% of the lake at fixture scale — a panel showing only
   reclaimable bytes would make a correct reclaim look like it did nothing."
  [st lk window]
  (let [cls   (classify st lk window)
        sizes (into {} (map (juxt :table_name identity)) (lake/live-tables lk))
        bytes #(or (:file_size_bytes (sizes %)) 0)
        objs  (sub/objects st)
        rows  (for [[object ts] (group-by :object (map #(assoc (val %) :table (key %)) cls))
                    :when object
                    ;; No `:orphaned` here, and no per-row orphan column: an
                    ;; orphan is a table no handle names, so `classify` gives it
                    ;; `:object nil` and it can never land in an object's group
                    ;; at all. The `:orphaned` key this row used to carry was 0
                    ;; in every row it was ever computed for. Orphans are
                    ;; reported once, at the top level, which is the only place
                    ;; that can be true.
                    :let [by-class (group-by :class ts)
                          recl (:reclaimable by-class)]]
                {:id      object
                 :title   (:title (get objs object))
                 :versions (count ts)
                 :current  (reduce + 0 (map #(bytes (:table %)) (:current by-class)))
                 ;; The current version counts as kept, so that the three
                 ;; version columns add up: `:versions` = `:in-window` +
                 ;; `:reclaimable-versions`. Counting only the `:in-window`
                 ;; CLASS leaves the current one in no column at all, and a
                 ;; panel reading "5 versions, 2 in window, 2 reclaimable"
                 ;; invites the reader to hunt for the fifth. It is also what
                 ;; `{:versions 3}` says on its face: three versions are kept,
                 ;; and one of the three is the one being read right now.
                 :in-window (+ (count (:current by-class))
                               (count (:in-window by-class)))
                 :reclaimable (reduce + 0 (map #(bytes (:table %)) recl))
                 :reclaimable-versions (count recl)
                 :inlined? (every? #(zero? (or (:file_count (sizes (:table %))) 0))
                                   (:current by-class))})
        orph  (filter #(nil? (:object (val %))) cls)]
    {:window window
     :lake   {:files   (reduce + 0 (map :file_size_bytes (vals sizes)))
              :catalog (.length (java.io.File. (str (:dir lk) "/catalog.duckdb")))}
     :orphaned {:tables (count orph)
                :bytes  (reduce + 0 (map #(bytes (key %)) orph))}
     :tables (vec (sort-by (comp - :reclaimable) rows))}))

(defn reclaim!
  "Reclaim one object's out-of-window and orphaned versions. Irreversible.

   `DROP TABLE`, then delete that table's own Parquet files. Never
   `ducklake_expire_snapshots` — expiry is catalog-wide, and freeing one
   object's history that way expires the snapshots every other object's handles
   read at (measured: three unrelated handles broke). A drop *mints* a snapshot
   instead of removing one, which is why it is safe.

   There is no dry-run flag because `report` is the dry run: it names exactly
   the versions this will delete and the bytes it will free, and this asserts
   the same numbers back.

   **`object` is nil for the orphans.** `classify` cannot attribute an orphan —
   an orphan is precisely a table no handle names, so there is no object to name
   it — and `nil` is therefore how you ask for them, not a missing argument.

   **It refuses rather than filters.** Before anything is dropped, the set it is
   about to delete is checked against the tables live objects are reading, and
   any overlap aborts the whole call. That is not defence in depth against
   nothing: `classify`'s `cond` tests `(nil? m)` before `(live table_name)` on
   purpose, so a gap in `tbl/event-handles`' coverage — a new event shape that can
   carry a handle — surfaces as a live table classified `:orphaned`, and orphans
   ignore the window. Deleting the rest and skipping the overlap would free the
   bytes and leave the gap unlooked-at; the only useful outcome of the
   classifier and the live set disagreeing is that someone reads this.

   **A restored table is reclaimed whole.** `lake/table-files` finds every
   `table_id` that ever bore the name, so the Parquet a `restore-to!` left
   tombstoned in the same directory goes too — without that, the table stayed
   readable at its pre-restore version after this claimed to have reclaimed it."
  [st lk object window]
  (let [cls    (classify st lk window)
        mine   (into {} (filter #(= object (:object (val %)))) cls)
        doomed (into #{} (comp (filter #(#{:reclaimable :orphaned} (:class (val %))))
                               (map key))
                     mine)
        live   (set (keep #(:table (:value %)) (vals (sub/objects st))))
        alive  (vec (filter live doomed))]
    (cond
      (empty? mine)
      {:error :unknown-object :object object :versions 0 :freed 0
       :why (str "no table in this lake is named by a handle for "
                 (pr-str object) " — nothing to reclaim, and a typo must not "
                 "read as a completed sweep")}

      (seq alive)
      {:error :live-table :object object :versions 0 :freed 0 :tables alive
       :why (str "refusing the whole reclaim: " (str/join ", " alive)
                 " " (if (= 1 (count alive)) "is" "are")
                 " still read by a live object, so the classifier and the live "
                 "set disagree. That means event-handles has a gap; deleting "
                 "the other versions would hide it.")}

      :else
      ;; List every file BEFORE any drop, and hold the paths across both loops.
      ;; `table-files` reads the metadata rows, which survive a tombstone, so
      ;; this order is no longer load-bearing the way it was against
      ;; `ducklake_list_files` — that one THROWS for a dropped table. Keep it
      ;; anyway: nothing is gained by asking the catalog a question after
      ;; mutating it.
      (let [files (into [] (mapcat #(lake/table-files lk %)) doomed)]
        (run! #(lake/drop-table! lk %) doomed)
        (run! #(io/delete-file (:data_file %) true) files)
        {:object   object
         :tables   (vec doomed)
         :versions (count doomed)
         :files    (count files)
         :freed    (reduce + 0 (map #(or (:data_file_size_bytes %) 0) files))}))))
