(ns loci.tablestore
  "The seam between an object that IS a table and wherever its rows actually
   live. The log stores a handle; this is what turns one back into rows.

   `InlineTable` keeps rows in the handle itself — which is what every loci
   table was before 2026-08-16 — and stays as the parity reference and as the
   store a test uses when it has nothing to say about DuckLake, the same role
   `substrate/PersistentStore` plays next to `dlv/DatalevinStore`."
  (:require [clojure.string :as str]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]))

(defprotocol TableStore
  (put-rows! [this id rows]
    "Land `rows` and return the handle to record in the event. Never overwrites:
     every call creates new storage, which is what lets two events never contend
     for one table.")
  (rows [this handle opts]
    "A page of rows for `handle`. `opts` may carry :offset and :limit.")
  (row-count [this handle] "How many rows `handle` names, without fetching them.")
  (reclaimed-at [this handle]
    "When this handle's rows were reclaimed, or nil if they were not.

     On the protocol because only the store can answer it, and `hydrate` needs
     the answer to tell a deliberate reclamation apart from a failing disk. An
     `InlineTable` carries its rows in the handle, so nothing can reclaim them
     and the answer is always nil.")
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
   from being wrong.

   `:source` is the passthrough handle, and it is the odd one: it carries no
   rows and names no storage, because it IS the recipe — a location, a format
   and the columns observed when it was connected. Reading it runs the reader
   function against the file every time."
  [v]
  (and (map? v) (contains? #{:inline :lake :source} (:store v))))

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

(defn- name-collisions
  "The source names `col-kw` would collapse onto one column, as
   `{normalized [source …]}` — empty whenever the normalization is injective,
   which it is for all 31 tables in the corpus (measured 2026-08-16).

   Takes NAMES rather than rows, so a CSV header and a vector of row maps run
   the same check: `loci.source/describe` calls `check-names!` on the header
   before anything is created, and `normalize-rows` calls it on every key any
   row has.

   Across the whole table rather than per row, deliberately. A per-row check
   passes a ragged table whose row 1 says `\"a b\"` and whose row 2 says `:a_b`:
   no single row loses a value there, and yet the table still ends with one
   column fed by two different names. `:cols` is a table-level fact, so the
   invariant that produces it is one too — and the table-level check is a strict
   superset, since a row is a table of one.

   Private: `check-names!` is the only caller and the only thing anyone should
   need. A reader that wants to LIST collisions rather than refuse them can have
   this made public on the day it exists."
  [names]
  (into (sorted-map)
        (keep (fn [[k ks]] (when (next ks) [k (vec (sort-by pr-str ks))])))
        (group-by sub/col-kw (set names))))

(defn check-names!
  "Throw if two of `names` would become one column. Returns nil otherwise.

   Refusing rather than merging is the register this codebase already keeps:
   `put-rows!` throws on a table with no rows, and `safe-event` throws rather
   than write a line it could not read back. `{\"a b\" 1, :a_b 2}` loses a whole
   column — both keys become `:a_b`, one value wins, and nothing downstream can
   tell, because `:cols` and the rows still agree with each other perfectly."
  [names]
  (let [cs (name-collisions names)]
    (when (seq cs)
      (let [[k ks] (first cs)]
        (throw (ex-info (str "two column names would become one column: "
                             (str/join " and " (map pr-str ks))
                             " both normalize to " k " — refusing to merge them")
                        {:column k :from ks :collisions cs}))))))

(defn- nested-value
  "The first `[column value]` whose value is a vector, map or set — or nil.

   Over the whole table for `collisions`' reason: a ragged table whose column is
   a string in row 1 and a vector in row 2 is still a table with a nested
   column, and one row cannot see it."
  [rows]
  (first (for [r rows, [k v] r :when (coll? v)] [k v])))

(defn normalize-rows
  "Every row keyed by `cols-of`'s keywords, so the rows and the column list
   cannot disagree — **or a throw**, when two column names normalize to one, or
   when a column holds a vector or a map.

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
   than write a line it could not read back.

   **A nested column is refused for the same reason, and refused rather than
   supported.** `read_json_auto` infers a column whose rows all share one shape
   as DuckDB `STRUCT` or `LIST` — never `JSON` — so JDBC returns
   `org.duckdb.DuckDBStruct` / `DuckDBArray`, `lake/unwrap` never sees them, and
   the object cannot be serialized at all:

     Don't know how to write JSON of class org.duckdb.DuckDBArray
     Don't know how to write JSON of class org.duckdb.DuckDBStruct

   Reachable from the agent's own path — `save_table` with
   `[{:name \"a\" :tags [\"x\" \"y\"]}]` lands fine and the next `query_table`
   throws into the tool loop — and a regression from moving rows to the lake,
   since the same rows through `InlineTable` serialize.

   Supporting them properly means `to_json` in the SELECT plus a type probe per
   read, which is real machinery for a shape nothing asks for: a loci table
   column is a scalar everywhere it is consumed — `content.clj`'s viewer
   predicates, `fnlib`'s `numeric-cols`/`cat-cols` — and `save_table`'s own tool
   description says \"each row is an object of column->value\". **0 of the 31
   live tables have a nested column** (measured 2026-08-16). A refusal at write
   time, naming the column, is something the agent can act on; a 500 at read
   time is not.

   Here rather than in `Lake` so **both stores refuse**. An inline/lake
   divergence is the failure this move has spent four tasks closing, and a check
   that lived in one store would be one more."
  [rows]
  (check-names! (into #{} (mapcat keys) rows))
  (when-let [[k v] (nested-value rows)]
    (throw (ex-info (str "column " k " holds a " (.getSimpleName (class v))
                         " — a loci table column holds scalars, not a vector or a map."
                         " Join it into a string, or split it into columns.")
                    {:column k :type (.getName (class v)) :value v})))
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
  (reclaimed-at [_ _] nil)
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

(defn table-value
  "Rows, landed in `ts`, as a table object's `:value` — a handle, or `[]` for no
   rows.

   **The empty case is not a handle** because `Lake/put-rows!` refuses an empty
   table and two writers can honestly produce one: `query_table :save_as` over a
   filter that matched nothing, and `import-csv!` on a header-only file. Both
   committed `:value []` before tables moved and the shell drew an empty table;
   letting the throw through would turn \"nothing matched\" into an error string,
   and on the `save_as` path into an uncaught exception inside the agent's tool
   loop. `[]` is not a handle, so `hydrate` passes it through and everything
   downstream sees what it saw before — and `[]` in the log is not rows in the
   log, so the guard costs the move nothing.

   The other five writers cannot reach the empty case, measured 2026-08-16, and
   this is worth knowing before anyone \"simplifies\" one of them: `save-table!`
   returns `{:error \"no rows to save\"}` first; `compute-clj!`, the ƒ apply and
   `rerun!` are all fed by `run-clj-rows`/`fnlib/run-fn`, which already answer
   *the function did not return rows* / *no rows came out — nothing to keep* for
   an empty result; and `corpus/mint-hub` runs after `material-complaint` has
   refused any file with no rows in it.

   Separate from `table-object` because `rerun!` replaces an existing object's
   `:value` in place with `{:op :assoc :path [:value]}` and has no object to
   construct — and rows written through that path are still rows in the log.
   That is exactly how it went unnoticed: it builds no `:kind :table` literal,
   so the grep that found the six writers did not find the seventh, and a test
   reading through `hydrate` cannot see the difference because `hydrate` passes
   a literal vector through untouched."
  [ts id rows]
  (if (seq rows) (put-rows! ts id rows) []))

(defn table-object
  "Build a `:kind :table` object, landing its rows in `ts`, so that the log holds
   a handle and not the rows.

   One function rather than a map literal per writer, so that a site added later
   gets lake storage by using the obvious constructor rather than by remembering
   to. `table-value` is the half of it a site that only replaces an existing
   object's `:value` can use — `server/rerun!` is the one.

   **Eight writers, and the grep found six.** `rerun!` assocs over `:value` and
   constructs no object; `content/seed!` builds its objects through a helper that
   takes `kind` as a parameter. Both spell no `:kind :table` literal, both were
   found by measuring what the log actually held after a write rather than by
   searching for the shape, and between them they were putting a 1,599-character
   event on every refresh and 230,300 characters on every fresh boot. So:
   `grep :kind :table` coming back clean is not the invariant. The invariant is
   that a `:table` object's `:value` is a handle, and the only tests that can see
   it are the ones reading `(:value (sub/object …))` raw — anything going through
   `hydrate` passes a literal vector along and reports success."
  [ts id title rows & [extra]]
  (merge {:id id :kind :table :title title :value (table-value ts id rows)}
         extra))

(defn table-shape
  "`{:cols [...] :rows n}` for a table object, or nil if it is not one.

   Reads the handle and fetches nothing. A handle recorded `:cols` and `:rows`
   when the rows were written, which is everything a catalog, a table list or a
   column menu needs — and DuckDB's JDBC driver is slow row-wise, so fetching
   them to count them is the difference between a 10 ms chat turn and a 282 ms
   one. Measured 2026-08-17 over the migrated store (31 tables, 3,957 rows):
   `agent-ctx` 282 ms → 10 ms and `list_tables` 271 ms → 0.05 ms, against 42 ms
   to walk 1,000×8 cells with `.getObject` and build nothing — so there is
   nothing to optimise in our own row-building, and nothing in the versioned
   read either
   (`AT (VERSION => n)` and a bare `SELECT *` are both 43 ms, `count(*)` 3 ms).
   The only fix available is not to ask for rows.

   Falls back to counting a legacy inline vector, so a store that has not been
   migrated answers the same question at the same cost it always did.

   Takes the store **or a delay of one**, and forces it only for a handle —
   `hydrate`'s rule, for `hydrate`'s reason: most callers run on workspaces
   holding no lake-backed table, and answering them must not open the lake."
  [ts o]
  (let [v (:value o)]
    (cond
      (handle? v) {:cols (vec (:cols v)) :rows (row-count (force ts) v)}
      (and (sequential? v) (seq v) (every? map? v))
      {:cols (vec (keys (first v))) :rows (count v)}
      :else nil)))

(defn reclaimed-str
  "A reclamation instant as text, in the ONE spelling everything downstream
   uses: ISO-8601 in UTC, `2026-08-17T11:40:29.190Z`.

   Exists because two readers on the SAME agent surface were spelling one
   instant two ways — `list_tables` shipped `\"2026-08-17T11:40:29.190Z\"`
   (`clojure.data.json` renders a `Date` as ISO-UTC) while `query_table`'s error
   string interpolated it and got `Date/toString`'s
   `\"Mon Aug 17 13:40:29 CEST 2026\"`. Same instant, same tool loop, one message
   apart, and nothing tells a model they are the same event. `recompute`'s
   `:why` had the local form too.

   ISO-UTC rather than the local form because it is the one the JSON boundary
   already produces and cannot be talked out of: choosing the other spelling
   would mean overriding `data.json` at every writer instead of formatting at
   the three that build strings.

   `(str (.toInstant d))` and not a `SimpleDateFormat`, because that is exactly
   what `data.json` emits — verified byte for byte, 2026-08-17 — so the two
   paths cannot drift."
  [^java.util.Date d]
  (when d (str (.toInstant d))))

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
   which is the trap `mold-payload`'s own docstring describes for `mem/memory`.

   **A table whose rows were reclaimed degrades instead of throwing**, and that
   is a second shape a caller has to expect: `:value []`, `:table/total` and
   `:table/cols` from the handle, and `:table/reclaimed` carrying the instant.
   `[]` rather than the handle because every downstream shape guard —
   `(and (sequential? v) (seq v) (every? map? v))`, repeated in a dozen readers
   — then behaves as it does for an empty table, a state they all already
   handle. **A reader that acts on the rows must check `:table/reclaimed`
   first**, because `[]` and \"no rows\" are indistinguishable otherwise:
   `server/recompute` refuses on the marker for exactly this reason, having
   otherwise been ready to recompute a derived table from zero rows and commit
   the empty result over good data.

   Only a store that says the rows were reclaimed may be swallowed. Any other
   failure is rethrown untouched, so a broken disk stays a broken disk — and
   asking the store is itself wrapped, because a catalog too damaged to answer
   must not replace the error the caller actually needs to see.

   **A passthrough table adds three more shapes on the same rule.** A healthy one
   carries `:table/passthrough true` beside its rows, so a reader can tell that
   its `:table/total` is the count recorded at connect rather than a fact about
   the file right now. A failing one degrades exactly as a reclaimed one does —
   `:value []`, the count and cols off the handle — and carries a marker saying
   which repair it needs: `:table/unavailable` when there is no readable file
   there, or `:table/source-drift` when there is one and it no longer matches
   the columns recorded at connect. Find the file, versus reconnect it.

   **The two markers carry messages from different places, deliberately.**
   `:table/unavailable` carries loci's own phrase, because loci looked at the
   filesystem to choose this branch and knows the real answer — where DuckDB's
   driver, for an unreadable file, reports either a four-line dump or a bare
   `NullPointerException`. `:table/source-drift` carries DuckDB's, because there
   the message is the actionable part: it names the columns the file has now."
  [ts o]
  (if (and o (handle? (:value o)))
    (let [ts (force ts)
          h  (:value o)
          pt (= :source (:store h))]
      (try
        (cond-> (assoc o :value       (rows ts h {:limit mold-cap})
                         :table/total (row-count ts h))
          pt (assoc :table/passthrough true))
        (catch Exception e
          ;; `:value []` rather than the handle, so every downstream shape
          ;; guard — `(and (sequential? v) (seq v) (every? map? v))`, repeated
          ;; in a dozen readers — behaves as it does for an empty table, which
          ;; is a state they all already handle. The cols and the true row
          ;; count survive because they live on the handle, not in the rows.
          (let [gone (assoc o :value [] :table/total (:rows h) :table/cols (:cols h))]
            (if pt
              (if-let [why (ssp/unreadable-because (:uri h))]
                (assoc gone :table/passthrough true :table/unavailable why)
                (assoc gone :table/passthrough true
                            :table/source-drift (ssp/duckdb-said e)))
              (if-let [at (try (reclaimed-at ts h) (catch Exception _ nil))]
                (assoc gone :table/reclaimed at)
                (throw e)))))))
    o))
