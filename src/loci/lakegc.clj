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

(defn- event-handles
  "Every `{:table … :object … :ts …}` one event names, **including inside a `:tx`**.

   A `:tx` is several sub-events committed as one undoable step, and
   `tools/save-table!` commits one whenever it also appends a notebook cell. A
   classifier that walked only top-level events would find no handle for those
   tables, call them orphaned, and reclaim rows a live object is still using —
   the worst outcome this namespace can produce. Sub-events carry no `:ts` of
   their own, so they inherit the transaction's.

   Two shapes carry a handle: a `:put` whose value is the whole object, and an
   `:assoc` at `[:value]` whose value is the handle itself (that is how
   `rerun!` refreshes a table)."
  [ev]
  (case (:op ev)
    :tx (mapcat #(event-handles (assoc % :ts (:ts ev))) (:events ev))
    (let [v (:value ev)
          h (cond (tbl/handle? v)          v
                  (tbl/handle? (:value v)) (:value v))]
      (when h [{:table (:table h) :object (:id ev) :ts (:ts ev)}]))))

(defn- handles-in-history
  "Every `{:table … :object … :ts …}` the log has ever named, newest first per
   object.

   From `sub/history` rather than from current state, because `as-of` and undo
   can reach any of them — that is exactly what the window protects."
  [st]
  (->> (sub/history st)
       (mapcat event-handles)
       (group-by :object)
       (into {} (map (fn [[o hs]] [o (vec (reverse hs))])))))

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
        by-obj  (handles-in-history st)
        cutoff  (- (System/currentTimeMillis) (* days 24 60 60 1000))
        seen    (into {} (for [[o hs] by-obj
                               [i {:keys [table ts]}] (map-indexed vector hs)]
                           [table {:object o :ts ts :age-rank i}]))]
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
                    :let [by-class (group-by :class ts)
                          recl (concat (:reclaimable by-class) (:orphaned by-class))]]
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
                 :orphaned (reduce + 0 (map #(bytes (:table %)) (:orphaned by-class)))
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
   purpose, so a gap in `event-handles`' coverage — a new event shape that can
   carry a handle — surfaces as a live table classified `:orphaned`, and orphans
   ignore the window. Deleting the rest and skipping the overlap would free the
   bytes and leave the gap unlooked-at; the only useful outcome of the
   classifier and the live set disagreeing is that someone reads this.

   **Incomplete for a table that was `restore-to!`d** — see `lake/table-files`,
   which names the live `table_id`'s Parquet and not the tombstoned sibling's,
   so such a table stays readable at its pre-restore version after this claims
   to have reclaimed it. Latent: nothing outside `loci.lake-test` calls
   `restore-to!` yet."
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
      ;; List every file BEFORE any drop. `ducklake_list_files` resolves through
      ;; the live catalog and THROWS for a dropped table rather than reporting
      ;; no files — measured 2026-08-17 — so a listing interleaved with the
      ;; drops would leave Parquet behind for every table after the first.
      (let [files (into [] (mapcat #(lake/table-files lk %)) doomed)]
        (run! #(lake/drop-table! lk %) doomed)
        (run! #(io/delete-file (:data_file %) true) files)
        {:object   object
         :tables   (vec doomed)
         :versions (count doomed)
         :files    (count files)
         :freed    (reduce + 0 (map #(or (:data_file_size_bytes %) 0) files))}))))
