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
  (row-count [this handle] "How many rows `handle` names, without fetching them."))

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
  (row-count [_ h] (:rows h)))

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
