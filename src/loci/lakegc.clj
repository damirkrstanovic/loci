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
  (:require [loci.lake :as lake]
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
