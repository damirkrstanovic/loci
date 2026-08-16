(ns loci.tablestore
  "The seam between an object that IS a table and wherever its rows actually
   live. The log stores a handle; this is what turns one back into rows.

   `InlineTable` keeps rows in the handle itself — which is what every loci
   table was before 2026-08-16 — and stays as the parity reference and as the
   store a test uses when it has nothing to say about DuckLake, the same role
   `substrate/PersistentStore` plays next to `dlv/DatalevinStore`.")

(defprotocol TableStore
  (put-rows! [this id rows]
    "Land `rows` and return the handle to record in the event. Never overwrites:
     every call creates new storage, which is what lets two events never contend
     for one table.")
  (rows [this handle opts]
    "A page of rows for `handle`. `opts` may carry :offset and :limit.")
  (row-count [this handle] "How many rows `handle` names, without fetching them.")
  (column-samples [this handle per-col max-cols]
    "Up to `per-col` sample values for each of the first `max-cols` columns, as
     `{column [\"…\" …]}` with the values already stringified.

     Distinct, ordered by string form, nulls dropped. Ordered rather than
     first-seen because the lake has no row order to appeal to without an
     `ORDER BY`, and a synopsis whose values vary between runs would move its
     hash on every sweep and re-embed forever. Ordered *by string form* because
     a column holding both `50` and `\"~13%\"` has no natural order, and the
     string is what reaches the synopsis anyway.

     This is what a table's chunk is built from. It exists so that describing a
     10M-row table costs nine values rather than a page of rows, and so that the
     description covers the whole column rather than its first `mold-cap` rows."))

(defn handle?
  "True for a handle this namespace produced. The `:store` key is explicit
   rather than inferred from shape, because a table's rows are a vector of maps
   and a handle is a map — close enough that a shape test would be one refactor
   from being wrong."
  [v]
  (and (map? v) (contains? #{:inline :lake} (:store v))))

(defn cols-of
  "The column order to preserve. Taken from the first row, which is what
   `save-table!` and `import-csv!` already treat as authoritative."
  [rows]
  (vec (keys (first rows))))

(defrecord InlineTable []
  TableStore
  (put-rows! [_ _ rs]
    (let [rs (vec rs)]
      {:store :inline :rows (count rs) :cols (cols-of rs) :data rs}))
  (rows [_ h {:keys [offset limit]}]
    (cond->> (:data h)
      offset (drop offset)
      limit  (take limit)
      true   vec))
  (row-count [_ h] (:rows h))
  (column-samples [_ h per-col max-cols]
    (let [rs (:data h)]
      (into {} (for [c (take max-cols (:cols h))]
                 [c (->> rs (keep #(get % c)) (map str) distinct sort (take per-col) vec)])))))

(defn inline-table [] (->InlineTable))

(def mold-cap
  "How many rows a molded table carries. The point of DuckLake is tables of
   millions of rows, and a payload that materialized all of them would move the
   problem from the substrate to the browser. The largest table in the substrate
   today is 1,000 rows, so this changes nothing about what currently renders."
  2000)

(defn hydrate
  "An object whose `:value` is a table handle → the same object with rows in
   its place, capped, plus `:table/total`.

   Everything downstream — `mold/mold`, `alternatives`, `viewspec/interpret`,
   `content.clj`'s predicates — receives rows exactly as it did before tables
   moved, which is the point: one call here instead of a handle branch in each
   of them. Anything that is not a handle passes through untouched, so a legacy
   inline table and a doc both survive.

   Takes the store **or a delay of one**, and forces it only when there is
   actually a handle to resolve. Fifteen call sites reach this function, most of
   them on paths — `list_tables`, the ƒ palette, the chunk sweep — that run on
   workspaces holding no lake-backed table at all. Forcing eagerly would open
   `<data-dir>/lake` to answer questions about rows that never left the log,
   which is the trap `mold-payload`'s own docstring describes for `mem/memory`."
  [ts o]
  (if (and o (handle? (:value o)))
    (let [ts (force ts)
          h  (:value o)]
      (assoc o :value       (rows ts h {:limit mold-cap})
               :table/total (row-count ts h)))
    o))
