(ns loci.tablestore
  "The seam between an object that IS a table and wherever its rows actually
   live. The log stores a handle; this is what turns one back into rows.

   `InlineTable` keeps rows in the handle itself — which is what every loci
   table was before 2026-08-16 — and stays as the parity reference and as the
   store a test uses when it has nothing to say about DuckLake, the same role
   `substrate/PersistentStore` plays next to `dlv/DatalevinStore`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]))

(defprotocol TableStore
  (put-rows! [this id rows]
    "Land `rows` and return the handle to record in the event. Never overwrites:
     every call creates new storage, which is what lets two events never contend
     for one table.")
  (rows [this handle opts]
    "A page of rows for `handle`. `opts` may carry :offset and :limit.")
  (page [this handle window]
    "One page of rows for `handle`, plus the cursor that starts the next page.

     `window` may carry `:limit`, `:cursor` (opaque, nil for page 1), `:sort`
     (`[col dir]`) and `:cols` (the columns to project, nil for all).

     => `{:rows [...] :next <cursor-or-nil>}`

     **Separate from `rows` rather than an option on it.** `rows` has twelve
     callers — agent digests, `tools.clj`, `describe` — that want a *sample* for
     a summary, not a page, and `mold-cap` is their default. Teaching `rows` to
     page would push a paging concept into every one of them.

     `:next` is produced by asking storage for `limit + 1` rows: if the extra one
     comes back there is another page, and the cursor is built from the last row
     actually returned. So the last page is known without a count.")
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
   rows and names no storage, because it IS the recipe — a location, a format,
   and the columns observed when it was connected under BOTH names: `:raw`, the
   file's own header, and `:cols`, loci's. Reading it runs the reader function
   against the file every time, projecting the first onto the second."
  [v]
  (and (map? v) (contains? #{:inline :lake :source} (:store v))))

(defn event-handles
  "Every `{:handle :source :object :ts}` one event names, **including inside a `:tx`**.

   Lifted out of `loci.lakegc` on 2026-08-22 when a second GC needed the same
   walk. Its reasoning is why it is one function and not two: a `:tx` is several
   sub-events committed as one undoable step, and `tools/save-table!` commits one
   whenever it also appends a notebook cell. A classifier that walked only
   top-level events would find no handle for those tables, call them orphaned,
   and reclaim rows a live object is still using — the worst outcome either GC
   can produce. Sub-events carry no `:ts` of their own, so they inherit the
   transaction's.

   Two shapes carry a handle, and an event yields an entry only if its `:path`
   is one of theirs: a `:put`, which names the whole object and carries no
   `:path` at all, and an `:assoc` at exactly `[:value]`, whose value is the
   handle itself (that is how `rerun!` and `source/refresh!` replace a table).
   A handle sitting at any deeper path — say an `:assoc` at `[:value :preview]`
   — is skipped, because an entry built from it would claim the OBJECT's value
   is that handle. That is a **wrong** entry, not a missing one.

   The gate is what makes the sentence above true rather than aspirational, and
   it had to choose a side, because the two GCs want opposite defaults.
   `lakegc/classify` folds these entries into one `{table {:object :ts
   :age-rank}}`, last write wins per table name, so a spurious entry can
   *replace* the true owner's; landing with an older `:ts` or a higher age-rank
   it flips a version `undo!` can still reach from `:in-window` to
   `:reclaimable`. `reclaim!`'s live-set guard does not catch that — that guard
   catches *under*-coverage, a live table classified `:orphaned`, and this is
   over-coverage. Over-coverage is harmless to a files GC, which would merely
   keep a file alive one round too long; under-coverage is unsafe for both. So
   the walk yields only the shapes it documents, and skips what it does not
   understand. Nothing in `src/` commits a handle at any other path today —
   checked 2026-08-22 across `source`, `tablemigrate`, `tools`, `server` and
   `content`, every one a `:put` or an `:assoc` at `[:value]` — so this closes
   the shape, not a live defect.

   **`:source` is carried beside `:handle` and is not redundant.** A
   materialised source's handle is `{:store :lake :table …}` and names no
   location, so a refresh — an `:assoc` at `[:value]` — drops the uri
   entirely. Only the `:put` of the whole object has it. A caller walking
   handles alone would find a live materialised table's file unreferenced.

   The log is not as silent as that last sentence reads. `source/refresh!`
   commits a `:tx` of TWO sub-events, and the second is an `:assoc` at
   `[:source]` carrying the freshly rebuilt source map — but no entry comes back
   for it, because a source map names no `:store`, so it matches neither
   `handle?` test, and it sits at a path the gate skips anyway. Only *these
   entries* stop at the `:put`; the log does carry a later uri. Harmless while
   `source-of` rebuilds the uri from the object's own `:source` and only `:at`
   moves, so it cannot change across a refresh. The day re-pointing a connected
   source at a new uri lands, a caller that learned \"fold back to the `:put`\"
   reads the old file's name and reclaims the new one — read `[:source]` off the
   object then, or teach this walk to carry it."
  [ev]
  (case (:op ev)
    :tx (mapcat #(event-handles (assoc % :ts (:ts ev))) (:events ev))
    (let [v (:value ev)
          ;; `contains?`, never `(#{nil [:value]} (:path ev))` — a set called as
          ;; a function answers with the element it found, and the element here
          ;; IS `nil`, so the set form is false for exactly the `:put` case it
          ;; has to admit. Measured 2026-08-22: it silently emptied every entry
          ;; in this namespace's tests and `lakegc`'s.
          h (when (contains? #{nil [:value]} (:path ev))
              (cond (handle? v)          v
                    (handle? (:value v)) (:value v)))]
      (when h [{:handle h :source (:source v) :object (:id ev) :ts (:ts ev)}]))))

(defn handles-in-history
  "Every `{:handle :source :object :ts}` the log has ever named, newest first
   per object, as `{object-id [entry …]}`.

   From `sub/history` rather than from current state, because `as-of` and undo
   can reach any of them — that is exactly what a GC window protects."
  [st]
  (update-vals (->> (sub/history st)
                    (mapcat event-handles)
                    (group-by :object))
               #(vec (reverse %))))

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

(def default-limit
  "Rows per page when a window does not say. 100 rather than 60 — the old
   client-side cap — because a page is now the whole of what the browser holds."
  100)

(def max-limit
  "The ceiling a window may ask for. A design decision and not a measured one:
   it is how many rows a person can look at, and 200 of a 36-column table is
   already 7,200 cells."
  200)

(defn page-limit
  "A window's `:limit`, clamped to 1..`max-limit` with `default-limit` for
   absent. Takes a string too, because a window arrives in a query string."
  [n]
  (let [n (if (string? n) (parse-long n) n)]
    (long (max 1 (min max-limit (or n default-limit))))))

(defn encode-cursor
  "A store's internal page key, as one opaque URL-safe string.

   **Opaque is the contract; base64-of-EDN is only today's encoding.** The key's
   shape differs per handle and per sort — a rowid here, an offset there — and
   the shell has one pager because it never looks. `clojure.edn/read-string`
   and not `read-string`: a cursor arrives from a query string, so it is hostile
   input, and `edn` evaluates nothing."
  [m]
  (.encodeToString (java.util.Base64/getUrlEncoder)
                   (.getBytes (pr-str m) "UTF-8")))

(defn decode-cursor
  "The map `encode-cursor` made, or **nil** for nil, non-string, blank, or
   anything malformed.

   nil means page 1, so a bookmarked cursor that no longer parses reopens the
   table at the top rather than throwing. A 500 on a URL somebody saved is the
   worse failure: the table looks broken when only the position was. The
   whole body runs inside the `try` — `str/blank?` is type-hinted and throws
   on a non-string, so checking it outside would defeat the point.

   Validates only that the decoded value IS a map — not what its fields hold.
   This function does not know a store's cursor shape, so a wrong-typed field
   (`{:i \"nope\"}`) passes here; `cursor-int` below is where a store that
   DOES know the shape catches it."
  [s]
  (try
    (when-not (str/blank? s)
      (let [m (edn/read-string (String. (.decode (java.util.Base64/getUrlDecoder) ^String s)
                                        "UTF-8"))]
        (when (map? m) m)))
    (catch Exception _ nil)))

(defn cursor-int
  "The integer at `k` in a decoded cursor, or `default` for anything else —
   missing, the wrong type, or below `default`. The 2-arity defaults to 0.

   Split from `decode-cursor` on purpose: that function validates only that
   the whole cursor is a map, because it does not know a store's cursor
   shape. A per-field check belongs to the store that DOES.

   **`default` is not always 0, and passing it explicitly is the point.**
   `InlineTable`'s `:i` is an index — page 1 starts at row 0, so the 2-arity
   is right for it, and a future `Lake`'s `:o` (an OFFSET, Task 2) is the same
   shape. `Lake`'s `:k` is a different thing: a bound on a 0-based `rowid`,
   read back as `WHERE rowid > k`, so ITS start-of-table is `-1`, not `0` —
   `rowid > 0` skips row 0 of every table. Call `(cursor-int cursor :k -1)`
   for it; the 2-arity would silently reintroduce that off-by-one, and it is
   also the reason the floor below is `default` and not a hardcoded `0` — a
   `:k` of `-1` is the valid, empty-page-so-far case, not a malformed one."
  ([cursor k] (cursor-int cursor k 0))
  ([cursor k default]
   (let [v (get cursor k)]
     (if (and (integer? v) (>= v default)) v default))))

(defn project-cols
  "The columns a window asks for, in the **handle's** order, or all of them.

   Handle order and not the caller's, because `lake/q-as` names a result set
   positionally: a reordered projection would label every value with its
   neighbour's name. Unknown names are dropped, and dropping all of them means
   all columns — a table with no columns is not something the shell can draw."
  [h cols]
  (if-let [want (some->> (seq cols) (map name) set)]
    (let [kept (filterv #(contains? want (name %)) (:cols h))]
      (if (seq kept) kept (:cols h)))
    (:cols h)))

(defn sort-spec
  "A window's `:sort`, validated against the handle's own columns, or nil.

   **Validation is not politeness.** `:sort` arrives in a query string and
   reaches an `ORDER BY`. `lake/ident` quotes it, so a name cannot break out of
   the identifier — but an unknown one reaches DuckDB as a binder error in the
   middle of a render, and a table that throws because a stale bookmark named a
   dropped column is worse than one that ignores the sort."
  [h [col dir]]
  (when (some? col)
    (let [known (set (map name (:cols h)))]
      (when (contains? known (name col))
        [(name col) (if (= "desc" (str dir)) "desc" "asc")]))))

(defn sort-rows
  "`rows` ordered by a validated `sort-spec`, **nulls last in both directions**.

   Nulls last matches the lake's `ORDER BY … NULLS LAST`. `InlineTable` is the
   parity reference for `Lake`, so a difference here would be a difference every
   paging test has to encode twice."
  [[col dir] rows]
  (let [k    (sub/col-kw col)
        desc (= "desc" dir)]
    (vec (sort-by #(get % k)
                  (fn [a b]
                    (cond (and (nil? a) (nil? b)) 0
                          (nil? a)  1
                          (nil? b) -1
                          desc     (compare b a)
                          :else    (compare a b)))
                  rows))))

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
  (page [_ h window]
    (let [lim  (page-limit (:limit window))
          i    (cursor-int (decode-cursor (:cursor window)) :i)
          srt  (sort-spec h (:sort window))
          proj (project-cols h (:cols window))
          got  (->> (cond->> (:data h) srt (sort-rows srt))
                    (drop i) (take (inc lim)) vec)
          more (> (count got) lim)]
      {:rows (mapv #(select-keys % proj) (take lim got))
       :next (when more (encode-cursor {:i (+ i lim)}))}))
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
   table and a writer can honestly produce one: `query_table :save_as` over a
   filter that matched nothing. `import-csv!` on a header-only file was the
   second such writer and is not one any longer — it lands through
   `source/connect!`, which materialises a real, empty lake table out of
   `read_csv` rather than handing rows here; see
   `a-header-only-csv-lands-an-empty-lake-table-not-a-literal` in
   `loci.server-test`. Both committed `:value []` before tables moved and the
   shell drew an empty table; letting the throw through would turn
   \"nothing matched\" into an error string, and on the `save_as` path into an
   uncaught exception inside the agent's tool loop. `[]` is not a handle, so
   `hydrate` passes it through and everything downstream sees what it saw
   before — and `[]` in the log is not rows in the log, so the guard costs the
   move nothing.

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

(defn unusable
  "Why a hydrated table's rows must not be acted on, as one sentence — or nil
   when they may be.

   One function rather than a marker check per reader, because there are now
   four markers and four readers, and the failure this prevents is arithmetic:
   sixteen places to be right, and `recompute` has already been wrong once by
   being ready to rebuild a derived table from zero rows and commit the empty
   result over good data.

   `:table/passthrough` is deliberately NOT here. It is a badge, not a refusal —
   a live table's rows are as real as any other's."
  [o]
  (cond
    (:table/reclaimed o)    (str "its rows were reclaimed on " (reclaimed-str (:table/reclaimed o)))
    (:table/unavailable o)  (str "its source is unreachable — " (:table/unavailable o))
    (:table/source-drift o) (str "its source's columns changed — " (:table/source-drift o)
                                 " — reconnect or refresh it")
    :else nil))

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
   handle. **A reader that acts on the rows must call `unusable` first**,
   because `[]` and \"no rows\" are indistinguishable otherwise:
   `server/recompute` refuses on its answer for exactly this reason, having
   otherwise been ready to recompute a derived table from zero rows and commit
   the empty result over good data. `unusable` and not `:table/reclaimed` — that
   was the whole check when reclamation was the only degradation; the two
   passthrough markers below arrived afterwards, and a reader still testing the
   one key acts on `[]` for both of them.

   For a `:lake` handle, only a store that says the rows were reclaimed may be
   swallowed. Any other failure is rethrown untouched, so a broken disk stays a
   broken disk — and asking the store is itself wrapped, because a catalog too
   damaged to answer must not replace the error the caller actually needs to see.

   **A passthrough table adds three more shapes, and it is NOT the same rule.**
   A healthy one carries `:table/passthrough true` beside its rows, so a reader
   can tell that its `:table/total` is the count recorded at connect rather than
   a fact about the file right now. A failing one degrades exactly as a reclaimed
   one does — `:value []`, the count and cols off the handle — and carries a
   marker saying which repair it needs: `:table/unavailable` when there is no
   readable file there, or `:table/source-drift` when there is one and it no
   longer matches the columns recorded at connect. Find the file, versus
   reconnect it.

   **Nothing on the `:source` branch rethrows, and that is a real cost rather
   than an oversight.** There is no record of a deliberate loss to consult — a
   source's file can vanish or change its header with no event committed, and
   `reclaimed-at` answers nil for such a handle by design — so the only two
   things this branch can ask are whether loci itself can read the file and, if
   it can, what DuckDB said. A fault that is neither therefore lands in the
   `else` and is reported as drift. Measured 2026-08-20 against a closed lake
   connection, with the file present and correct: `:table/source-drift` reading
   \"it was connected as a, b — Connection was closed\", which sends the operator
   to reconnect a file that is fine, where the same closed connection on a
   `:lake` handle threw `java.sql.SQLException: Connection was closed`. Accepted
   because the alternative is worse: separating the two by DuckDB's message is
   what `loci.sourcespec`'s docstring calls the fragile alternative, that driver
   already answers real source failures with an unrelated `NullPointerException`,
   and a rethrow here would put every passthrough table in the substrate back
   behind one unreadable file. `loci.chunks/with-samples` makes the same trade on
   the same handle, deliberately — see its docstring for the predicate and why
   nothing narrower was reachable.

   Not everything is swallowed even so: this catches `Exception`, so an `Error` —
   `OutOfMemoryError`, `StackOverflowError` — passes through both branches
   untouched.

   **`:table/passthrough` says how a table is stored, never whether it is well.**
   It is `true` on all three shapes, the two failures included, so a reader must
   not take it for a liveness signal — ask `unusable`, which is the one answer to
   that question and the thing readers should call.

   **The two markers carry messages from different places, deliberately.**
   `:table/unavailable` carries loci's own phrase, because loci looked at the
   filesystem to choose this branch and knows the real answer — where DuckDB's
   driver, for an unreadable file, reports either a four-line dump or a bare
   `NullPointerException`. `:table/source-drift` carries DuckDB's, because there
   the message is the actionable part: it names the columns the file has now.

   **A REMOTE unavailable carries both**, because the sentence above stops being
   true: there is no filesystem for loci to have looked at, so its phrase names
   the location and nothing else, and DuckDB's is the only account of what went
   wrong that exists. Suppressing it there would mean drawing a block headed
   \"source unreachable\" that cannot say whether the credentials expired or the
   key was never written."
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
                (assoc gone :table/passthrough true
                            :table/unavailable
                            ;; loci's own phrase is the WHOLE message for a
                            ;; local uri — it looked at the filesystem and knows
                            ;; the answer, so DuckDB's line would only repeat it
                            ;; in a worse voice. That argument does not carry to
                            ;; a remote one, where loci looked at nothing: its
                            ;; phrase can name the location and no more, and the
                            ;; 403, the expired credential or the key that was
                            ;; never there is in `e` and nowhere else. Suppress
                            ;; it there and the reader is told a source is
                            ;; unreachable with no way to learn why.
                            ;;
                            ;; `remote?` and not a match on the phrase's
                            ;; English: reading a state back out of prose is
                            ;; what this file refuses to do everywhere else, and
                            ;; index.html's own comment forbids it in the shell.
                            (cond-> why
                              (ssp/remote? (:uri h)) (str " — " (ssp/duckdb-said e))))
                (assoc gone :table/passthrough true
                            :table/source-drift
                            ;; The columns it was connected as come first,
                            ;; because they are true whatever the driver says.
                            ;; DuckDB's own text is usually the better half — it
                            ;; names what the file holds NOW — but it is not
                            ;; reliable: on a long-lived connection its JDBC
                            ;; driver sometimes reports an unrelated
                            ;; `NullPointerException` instead of the binder
                            ;; error, observed twice on 2026-08-19. So the half
                            ;; loci can always vouch for leads.
                            (str "it was connected as "
                                 (str/join ", " (map name (:cols h)))
                                 " — " (ssp/duckdb-said e))))
              (if-let [at (try (reclaimed-at ts h) (catch Exception _ nil))]
                (assoc gone :table/reclaimed at)
                (throw e)))))))
    o))

(defn hydrate-page
  "`hydrate`, but for one page of a table rather than a `mold-cap` sample.

   Returns the hydrated object with `:value` set to the page's rows,
   `:table/total`, `:table/cols` set from the handle's FULL column list, and
   `:page/next` set to the cursor for the next page, or nil on the last one.

   **`:table/cols` is the handle's own list, not the page's.** A window may
   narrow `:cols`, so the rows in `:value` can carry fewer keys than the table
   has — and reading the column list back out of those rows would make hiding a
   column one-way, since an unhidden one would have vanished from what a reader
   could ever ask for again.

   **Beside `hydrate` rather than a flag on it.** `hydrate` is reached from
   thirteen places and twelve of them want the sample — its failure handling,
   its `:table/unavailable` and `:table/source-drift` classification and its
   `:table/passthrough` marker are all shared, so this delegates for a non-handle
   value and for the failure paths and differs only in which read it issues."
  [ts o window]
  (if-not (and o (handle? (:value o)))
    (hydrate ts o)
    (let [ts (force ts)
          h  (:value o)]
      (try
        (let [{:keys [rows next]} (page ts h window)]
          (cond-> (assoc o :value rows :table/total (row-count ts h)
                           :table/cols (:cols h) :page/next next)
            (= :source (:store h)) (assoc :table/passthrough true)))
        (catch Exception _
          ;; one classifier, not two: a page that throws is the same broken
          ;; table a sample would have been, and `hydrate` already knows how to
          ;; say which way it is broken.
          (hydrate ts o))))))
