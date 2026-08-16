(ns loci.tablestore
  "The seam between an object that IS a table and wherever its rows actually
   live. The log stores a handle; this is what turns one back into rows.

   `InlineTable` keeps rows in the handle itself — which is what every loci
   table was before 2026-08-16 — and stays as the parity reference and as the
   store a test uses when it has nothing to say about DuckLake, the same role
   `substrate/PersistentStore` plays next to `dlv/DatalevinStore`."
  (:require [clojure.string :as str]
            [loci.substrate :as sub]))

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

     Nulls dropped, each value clipped to `sample-max-chars`, then **distinct,
     then ordered by that clipped string**. Clipped before deduplicating rather
     than after, so two values sharing a 40-character prefix contribute one
     sample and not two identical-looking ones.

     Ordered rather than first-seen because the lake has no row order to appeal
     to without an `ORDER BY`, and a synopsis whose values vary between runs
     would move its hash on every sweep and re-embed forever. Ordered *by
     string form* because a column mixing a number and a string has no natural
     order, and the string is what reaches the synopsis anyway.

     **The two implementations agree on the ordering but not always on the
     string**, and the example that motivates ordering-by-string is itself the
     exception: a column holding both `50` and `\"~13%\"` is widened to JSON by
     `read_json_auto`, so the lake renders it `\"~13%\"` — quotes included — and
     sorts it first, where inline renders `~13%` and sorts it last. See
     `the-two-stores-disagree-on-a-mixed-type-column` in `loci.lake-test`, which
     records the disagreement, and `loci.chunks/all-chunks`, which lists every
     class of it measured over the live corpus.

     This is what a table's chunk is built from. It exists so that describing a
     10M-row table costs nine values rather than a page of rows, and so that the
     description covers the whole column rather than its first `mold-cap` rows."))

(def sample-max-chars
  "How long one sample value may be, in **Unicode code points**.

   Code points rather than Java chars because that is the unit DuckDB's
   `length` and `substr` count, and the two stores have to clip identically or
   they produce different text for the same table. Measured 2026-08-16: they
   agree exactly for ASCII, Cyrillic and CJK, and part company only above the
   BMP — 45 `🙂` is 90 chars to Java and 45 to DuckDB, so a `subs`-based clip
   would keep 20 of them and DuckDB 40."
  40)

(defn clip-sample
  "One sample value, clipped to `sample-max-chars` code points with an ellipsis.

   The reference for what the lake does in SQL; keep the two in step."
  [s]
  (let [n (.codePointCount ^String s 0 (.length ^String s))]
    (if (> n sample-max-chars)
      (str (subs s 0 (.offsetByCodePoints ^String s 0 sample-max-chars)) "…")
      s)))

(defn handle?
  "True for a handle this namespace produced. The `:store` key is explicit
   rather than inferred from shape, because a table's rows are a vector of maps
   and a handle is a map — close enough that a shape test would be one refactor
   from being wrong."
  [v]
  (and (map? v) (contains? #{:inline :lake} (:store v))))

(defn cols-of
  "The column order to preserve: every key any row has, in the order first seen.

   First-seen order, not sorted, because the order the rows were written in is
   the order the table is read in, and a table that renders with its columns
   rearranged reads as a different table. Every row rather than the first,
   because a key that appears only in row 2 is still a column, and the lake
   would otherwise drop it silently while `InlineTable` kept it.

   Each key goes through `sub/col-kw`, which leaves a keyword that already reads
   back cleanly alone — `:tenure-band` stays `:tenure-band` — and normalizes one
   that does not. That matters here and nowhere else in the codebase, because
   `:cols` is the only place a column keyword travels through the log as a
   *value*: `normalize-keys` fixes map keys, and `safe-event`'s guard compares
   printed strings, so `[:foo bar]` passes both and re-prints identically while
   going in as two elements and reading back as three. Why that `col-kw`
   survives even though `put-rows!` hands this function rows `normalize-rows`
   has already fixed: see
   `cols-of-normalises-on-its-own` in `loci.tablestore-test`."
  [rows]
  (into [] (distinct) (map sub/col-kw (mapcat keys rows))))

(defn- collisions
  "The source keys `col-kw` would collapse onto one column, as
   `{normalized [source …]}` — empty whenever the normalization is injective,
   which it is for all 31 tables in the corpus (measured 2026-08-16).

   Across the whole table rather than per row, deliberately. A per-row check
   passes a ragged table whose row 1 says `\"a b\"` and whose row 2 says `:a_b`:
   no single row loses a value there, and yet the table still ends with one
   column fed by two different names. `:cols` is a table-level fact, so the
   invariant that produces it is one too — and the table-level check is a strict
   superset, since a row is a table of one.

   One extra pass over every key, on top of the one `cols-of` already makes."
  [rows]
  (into (sorted-map)
        (keep (fn [[k ks]] (when (next ks) [k (vec (sort-by pr-str ks))])))
        (group-by sub/col-kw (into #{} (mapcat keys) rows))))

(defn normalize-rows
  "Every row keyed by `cols-of`'s keywords, so the rows and the column list
   cannot disagree — **or a throw**, when two column names normalize to one.

   Public because `Lake` implements `put-rows!` from its own namespace and has
   to normalize before `load-rows!` writes the JSON. Measured with the
   normalization dropped: the JSON keeps the key `foo bar`, the CREATE names the
   column `\"foo_bar\"`, and DuckDB refuses the write outright — *Binder Error:
   Referenced column \"foo_bar\" not found in FROM clause! Candidate bindings:
   \"foo bar\"*.

   The throw is the same failure `col-kw` prevents at the `:cols` level, one
   layer down, and the two differ in what is lost. There, an unnormalized column
   keyword corrupts its own *value* on the way back out of the log — two columns
   in, three out. Here, `{\"a b\" 1, :a_b 2}` loses a whole column:
   both keys become `:a_b`, one value wins, and nothing downstream can tell,
   because `:cols` and the rows still agree with each other perfectly. Reachable
   today through `save_table`, where a model names its own columns.

   Refusing rather than merging is the register this codebase already keeps:
   `put-rows!` throws on a table with no rows, and `safe-event` throws rather
   than write a line it could not read back."
  [rows]
  (let [cs (collisions rows)]
    (when (seq cs)
      (let [[k ks] (first cs)]
        (throw (ex-info (str "two column names would become one column: "
                             (str/join " and " (map pr-str ks))
                             " both normalize to " k " — refusing to merge them")
                        {:column k :from ks :collisions cs})))))
  (mapv (fn [r] (reduce-kv (fn [m k v] (assoc m (sub/col-kw k) v)) {} r)) rows))

(defrecord InlineTable []
  TableStore
  (put-rows! [_ _ rs]
    (let [rs (normalize-rows (vec rs))]
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
                 [c (->> rs (keep #(get % c)) (map str) (map clip-sample)
                         distinct sort (take per-col) vec)])))))

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
