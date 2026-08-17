(ns loci.chunks
  "The substrate's own text, cut into chunks and given vectors — so LEAP can
   find what **you wrote**, not only what the agent distilled into a memory fact.

   Two halves, and the seam between them is the design:

   · `chunks-of` is a **pure function of one object**. No store, no file, no
     embedder, no clock. Give it the map the substrate holds and it returns the
     chunks that object implies, with stable ids and a hash of each chunk's
     text. That is what makes everything below rebuildable.

   · the store is an append-only EDN-lines sidecar, last-wins by `:id` — the
     same mechanism `loci.memory` uses for facts, for the same reasons: no new
     store, no schema, no migration, and a corrupt or stale file is a rebuild
     rather than a loss.

   **Chunks are derived and are NEVER substrate events.** Nothing here calls
   `sub/commit!`, and nothing here may start to. Putting a chunk in the log
   would make derived data undoable (an undo would have to know embeddings
   exist), drag it into time travel (`as-of` would replay vectors), and let a
   stale vector answer a question about the past. The corollary is a test:
   delete `chunks.edn`, sweep, and the ids and hashes come back byte-equivalent.

   **Staleness is by content hash, not by a timestamp or a flag.** `:hash`
   covers the chunk *text*, so editing a document changes its chunks' hashes and
   nothing else's, and there is no separate invalidation protocol to keep in
   step. A model change invalidates everything, because a vector from another
   model is worse than none — cosine across two embedding spaces still returns a
   number that sorts.

   **A table gets ONE synopsis**: its title, its column names and a few sample
   values, clipped to `synopsis-max-chars`. 2,584 row-vectors would be 2,584
   useless vectors; nobody searches for a row, they search for *which table holds
   the data*. The synopsis is bounded by construction, not by hoping tables stay
   small.

   Embedding happens after the write and never during it, exactly as in
   `loci.memory`: `sweep!` is a plain synchronous function that a server, a
   worker or a test can call, it is resumable because a chunk that failed simply
   has no stored vector, and requiring this namespace starts nothing.

   Titles are deliberately *not* prefixed to doc and report chunks. LEAP already
   matches titles by substring on the keystroke path; these vectors exist to
   reach the text underneath, which is what substring search cannot do."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.dlv :as dlv]
            [loci.embed :as embed]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

;; ---------------------------------------------------------------------------
;; sizes — the two bounds this namespace makes claims about
;; ---------------------------------------------------------------------------

(def max-chars
  "The longest a chunk of prose may be, in characters: ~600 tokens at the ~4
   chars/token that English runs to. Public because it is a claim tests check
   rather than an internal detail.

   The live corpus's longest document body is 5,078 chars, so this splits the
   two or three documents that need it and leaves the median 2,027-char body
   whole."
  2400)

(def synopsis-max-chars
  "The longest a table's synopsis may be. This is the bound that stops a
   1,000-row table from producing a vector that is mostly rows: the synopsis is
   clipped here whatever the table does, so its size is a property of this
   namespace and not of the data."
  600)

(def ^:private synopsis-cols
  "How many columns a synopsis names. A table with more than this is a table
   nobody is going to find by its 25th column name."
  24)

(def ^:private synopsis-samples
  "Sample values per column. Enough that \"Belgrade\" finds the roster; few
   enough that the samples are a hint, not the data.

   Not taken from the front of the table — `column-samples` returns the whole
   column's distinct values ordered by their string form, and these are the
   first three of those. Appending rows still does not churn the hash, but for
   a stronger reason than row order gave: a new row moves this prefix only if
   its value sorts *into* it, so appending 10,000 rows to a table whose
   smallest three values are already present changes nothing at all."
  3)

;; ---------------------------------------------------------------------------
;; hashing
;; ---------------------------------------------------------------------------

(defn- sha256
  "A hex sha256 of `s`, prefixed with the algorithm so a later change of
   algorithm is visible in the file rather than a silent mismatch."
  [s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest d (.getBytes ^String s "UTF-8"))
         (map #(format "%02x" %))
         str/join
         (str "sha256:"))))

;; ---------------------------------------------------------------------------
;; splitting prose
;; ---------------------------------------------------------------------------

(defn- heading? [line] (boolean (re-find #"^#{1,6}\s" (str line))))

(defn- sections
  "A markdown body split at headings: each section is its heading line plus
   everything up to the next heading. Text before the first heading is its own
   section. Blank sections are dropped, so a body that is only whitespace
   produces nothing at all."
  [body]
  (->> (str/split-lines (str body))
       (reduce (fn [acc line]
                 (if (and (heading? line) (seq (peek acc)))
                   (conj acc [line])
                   (conj (pop acc) (conj (peek acc) line))))
               [[]])
       (map #(str/trim (str/join "\n" %)))
       (remove str/blank?)
       vec))

(def ^:private boundaries
  "Where a too-long piece may be cut, coarsest first: blank lines, then lines,
   then spaces. Cutting at the coarsest boundary that fits is what keeps a
   paragraph — and above all a heading, which is a whole line — intact."
  [[#"\n{2,}" "\n\n"] [#"\n" "\n"] [#" +" " "]])

(defn- pack
  "Greedily join `parts` with `sep` into as few pieces of at most `limit` as
   possible. A single part longer than `limit` comes out alone and oversize; the
   next boundary deals with it."
  [parts sep limit]
  (reduce (fn [acc p]
            (let [cur (peek acc)]
              (if (and cur (<= (+ (count cur) (count sep) (count p)) limit))
                (conj (pop acc) (str cur sep p))
                (conj acc p))))
          [] parts))

(defn- fit
  "`s` as one or more pieces, none longer than `limit`.

   Total by construction: when every structural boundary has been tried and a
   single token is still too long, it is cut at `limit`. Nothing is dropped —
   the pieces concatenate back to the input's content."
  [s limit]
  (loop [bs boundaries, pieces [s]]
    (if (every? #(<= (count %) limit) pieces)
      pieces
      (if-let [[re sep] (first bs)]
        (recur (rest bs)
               (vec (mapcat #(if (<= (count %) limit) [%] (pack (str/split % re) sep limit))
                            pieces)))
        (vec (mapcat #(if (<= (count %) limit) [%] (map str/join (partition-all limit %)))
                     pieces))))))

(defn- split-section
  "One heading-delimited section as chunks of at most `max-chars`.

   A section that fits comes back untouched. One that does not is cut at the
   coarsest boundary that works, and **every piece keeps the section's heading
   line**, so a heading is never halved and no piece is a bare heading with its
   body somewhere else."
  [section]
  (if (<= (count section) max-chars)
    [section]
    (let [lines (str/split-lines section)
          head  (when (heading? (first lines)) (first lines))
          body  (if head (str/trim (str/join "\n" (rest lines))) section)]
      (if (or (nil? head) (str/blank? body))
        (fit section max-chars)
        (mapv #(str head "\n" %)
              ;; room for the heading that will be put back on the front
              (fit body (max 200 (- max-chars (count head) 1))))))))

(defn- prose-chunks [body] (vec (mapcat split-section (sections body))))

;; ---------------------------------------------------------------------------
;; tables — one synopsis, bounded
;; ---------------------------------------------------------------------------

(defn- rows? [v] (and (sequential? v) (seq v) (every? map? v)))

(def ^:private inline-store
  "The reference `TableStore`. Lifted to a def rather than constructed per call
   so that `chunks-of` staying **pure** is obvious: `InlineTable` is a record
   with no fields, so this is a value, not a store to be reached."
  (tbl/inline-table))

(defn- col-name [c] (if (keyword? c) (name c) (str c)))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- synopsis
  "One bounded description of a table: its title, its column names, and a few
   sample values per column.

   Not its rows. The whole point is that 2,584 rows produce one vector, and the
   clip at the end makes that a guarantee rather than an expectation — a caller
   can assert on the length and the assertion holds for any table.

   Takes samples rather than rows so that a lake-backed table can be described
   without fetching any: the store answers `column-samples` from SQL, and this
   function never learns which store it was. The row *count* is deliberately
   absent: it would move the hash on every appended row and buy nothing anyone
   searches for."
  [title cols samples]
  ;; No per-value clip here: `column-samples` already clipped to
  ;; `tbl/sample-max-chars`, and re-clipping would cut an astral value a second
  ;; time — the store counts code points, `clip` counts Java chars.
  (let [line (fn [c] (str (col-name c) ": " (str/join ", " (get samples c))))]
    (clip (str/join "\n" (concat (when-not (str/blank? (str title)) [(str title)])
                                 [(str "columns: " (str/join ", " (map col-name cols)))]
                                 (map line cols)))
          synopsis-max-chars)))

;; ---------------------------------------------------------------------------
;; reports
;; ---------------------------------------------------------------------------

(defn- report-body
  "A report's prose as markdown: its headings and text blocks, with each data
   block reduced to what it is *about*.

   The rows are not here. A report's table is the same one-vector-per-table
   problem, and the table object it was drawn from already carries the synopsis;
   copying the rows in would embed the same data twice and blow past every
   bound in this namespace."
  [blocks]
  (->> blocks
       (keep (fn [b]
               (let [t (str (:text b))]
                 (case (:block b)
                   :heading (when-not (str/blank? t) (str "## " t))
                   :text    (when-not (str/blank? t) t)
                   :table   (when-not (str/blank? (str (:title b))) (str "Table: " (:title b)))
                   ;; map? guarded: a chart's :spec comes from an agent, and
                   ;; destructuring a string here would throw inside a sweep and
                   ;; take every other chunk down with it
                   :chart   (let [s (:spec b)]
                              (when (map? s)
                                (let [{:keys [x y]} s]
                                  (when (or x y) (str "Chart: " y " by " x)))))
                   nil))))
       (str/join "\n\n")))

;; ---------------------------------------------------------------------------
;; chunking — a pure function of one object
;; ---------------------------------------------------------------------------

(defn- texts-of
  "The texts one object contributes, in order — or nothing.

   Dispatch is an explicit whitelist of kinds, not a default that embeds
   whatever turns up. `:viewspec`, `:applet`, `:fn` and `:palette` are
   machinery, which LEAP already hides from its object list, and `:flow` is a
   live checklist rather than something somebody wrote. A kind added later gets
   no chunks until it is named here, which is the safe direction to fail: an
   unembedded object is found lexically, an embedded viewspec is noise in every
   result."
  ;; `:table/synopsis-cols` is read off `o` rather than destructured, because the
  ;; obvious local would shadow the `synopsis-cols` DEF this function uses two
  ;; branches down.
  [{:keys [id kind title value] :table/keys [samples] :as o}]
  (case kind
    :space  (let [head (str/trim (str title "\n" (str (:intent value))))]
              (into (if (str/blank? head) [] [head])
                    (comp (keep :text) (map str/trim) (remove str/blank?))
                    (nb/cells-of {:value value})))
    ;; a doc's value is its text. Anything else is a bug elsewhere, and
    ;; embedding its printed EDN would put noise in every result rather than
    ;; leaving the object to be found the way it is found today, by title.
    :doc    (if (string? value) (prose-chunks value) [])
    :report (prose-chunks (report-body value))
    (:table :metric)
    (cond
      samples [(synopsis title (:table/synopsis-cols o) samples)]

      (rows? value)
      (let [h (tbl/put-rows! inline-store id value)]
        [(synopsis title (vec (take synopsis-cols (:cols h)))
                   (tbl/column-samples inline-store h synopsis-samples synopsis-cols))])

      (not (str/blank? (str title))) [(str title)]
      :else [])
    []))

(defn chunks-of
  "Every chunk `obj` implies: `[{:id :object :kind :text :hash} …]`, in order.

   Pure. The id is the object's id plus the chunk's index **within that
   object** — `\"doc:findings-4#2\"` — so a chunk carries no trace of the
   enumeration that found it, and chunking one object alone gives the same ids
   as chunking it among a thousand others.

   `:object` is what a hit points at, so a result opens the document rather than
   the fragment."
  [obj]
  (let [id (:id obj)]
    (vec (map-indexed (fn [i t] {:id     (str id "#" i)
                                 :object id
                                 :kind   (:kind obj)
                                 :text   t
                                 :hash   (sha256 t)})
                      (texts-of obj)))))

(defn chunks-in
  "The chunks a collection of objects implies, ordered by object id.

   Ordered, so a sweep does the same work in the same batches twice; the ids
   themselves do not depend on it (see `chunks-of`), and neither does anything
   this namespace stores."
  [objs]
  (vec (mapcat chunks-of (sort-by #(str (:id %)) objs))))

(defn- with-samples
  "A handle-backed table, described. Attaches what `texts-of` needs and nothing
   else — the rows are never fetched.

   A table whose rows are gone loses its samples and nothing else: `texts-of`
   falls through to the title, which is the same chunk an untitled-value table
   has always produced. The sweep is whole-substrate and runs on a timer, so one
   unreadable table must never cost more than its own chunk — before this, a
   single deleted Parquet stopped semantic-recall indexing for **every** object
   in the substrate, every 15 seconds, until somebody noticed.

   Only a store that says the rows were reclaimed may be swallowed, and asking
   it is itself wrapped — the same shape as `tbl/hydrate`, for the same two
   reasons. A failing disk must stay a failing disk rather than becoming a
   substrate of bare titles nothing raised about, and a catalog too damaged to
   answer must not replace the error the caller actually needs to see.

   **The catch fires only on a cold `lake/sample-cache`, and in the server that
   means after a restart.** Samples are memoized per `[table snapshot]`, so a
   table the 15-second sweep has already described keeps producing its full
   synopsis from the memo after its Parquet is deleted — measured 2026-08-17:
   two identically injured tables, the sampled one answered `{:a [\"0\" \"1\"
   \"10\"]}` and the unsampled one threw `IO Error: Cannot open file`. So
   reclaiming costs nothing at all until the process next starts cold, and then
   exactly one re-embedding for that one chunk: the text goes from synopsis to
   title, so its `:hash` moves, and `sweep!` reports `{:embedded 1 :retired 0}`
   and asks the model for `[\"Gone\"]`. Nothing is retired — the chunk id is the
   object's id plus its index, which does not move."
  [o]
  (if (tbl/handle? (:value o))
    (let [h (:value o)]
      (try
        ;; `:table/synopsis-cols` and NOT `:table/cols`, which `tbl/hydrate`
        ;; already owns and fills with the table's FULL column list. This one is
        ;; truncated to `synopsis-cols` — two different meanings under one key,
        ;; kept apart today only by `texts-of` gating on `:table/samples`, which
        ;; is one refactor from being wrong.
        (assoc o :table/synopsis-cols (vec (take synopsis-cols (:cols h)))
                 :table/samples       (tbl/column-samples (force lake/lake) h
                                                          synopsis-samples synopsis-cols))
        (catch Exception e
          ;; `o` unchanged on purpose: with no `:table/samples`, `texts-of`'s
          ;; `(:table :metric)` branch falls to the title, which is already how
          ;; it handles a table it cannot describe.
          (if (try (tbl/reclaimed-at (force lake/lake) h) (catch Exception _ nil))
            o
            (throw e)))))
    o))

(defn all-chunks
  "Every chunk the substrate currently implies. Reads the store; writes nothing
   to it, ever.

   A table's synopsis comes from `column-samples`, which the lake answers in SQL
   and memoizes per handle, so this costs no row reads in steady state and stays
   bounded whatever a table's size. `chunks-of` stays pure — it never learns
   that a lake exists.

   Byte-identical to the inline path only where the two agree on how a value
   *prints*. Measured over the 31 live row-bearing objects, 2026-08-16: **27 of
   31**, and re-measured unchanged on 2026-08-17 after `lake/unwrap` landed —
   the JSON columns it fixed are read through `q-as`, and these samples come
   from `q` over a `CAST(… AS VARCHAR)` that happens before any value reaches
   JDBC.

   *Objects*, not tables: 29 tables and the two `:metric`s, which carry rows the
   same way and which a `:kind :table` filter silently drops. That word cost a
   round — it made the count look wrong, and the two it hid are exactly the two
   `content/seed!` was failing to convert. Never because the ordering differs — DuckDB's VARCHAR collation and
   Clojure's `sort` were checked against each other over 27 strings spanning
   punctuation, digits, case, accents and CJK and agreed exactly. Always
   because `CAST(… AS VARCHAR)` and Clojure's `str` render the same value
   differently, and **a value that renders differently also sorts differently,
   so the whole sample list can change and not just the one value in it.**

   Three of the four are the lake's own type inference, and predate this
   function: reading the rows back disagreed with inline in exactly the same
   way, and sampling in row order simply never reached the offending value.

   · A column mixing `Long` and `Double` is one `DOUBLE` to `read_json_auto`,
     so `100` renders `100.0` — `tbl:csv-19-7071`'s `urban_pct`.
   · A column mixing a string with numbers is widened to JSON, and a JSON value
     keeps its quotes, so an empty string renders as `\"\"` — two characters,
     sorting below every digit. Five columns: `density_per_km2`
     (also `tbl:csv-19-7071`, which is why it is under both bullets),
     `tbl:csv-18-1542`'s `inflation_pct`, `growth_pct` and `unemployment_pct`.
     In `tbl:extract-20-8413`'s `approx_low_income_share` the quote also
     reorders: `10, 50, ~13% (1 of 8 countries)` against
     `\"~13% (1 of 8 countries)\", 10, 50`.

   The fourth is this function's own, and is the price of asking SQL rather
   than Clojure to render. `tbl:corpus-2`'s `production_tonnes` samples
   `1.24E7, 1.4E7, 100.0` inline and `100.0, 1000.0, 10000.0` from the lake —
   **three different values**, because the rendering decides the sort. Both
   engines use scientific notation and neither can be made to imitate the
   other: Clojure switches at 1e7 and 1e-3 and writes `1.0E7`, DuckDB switches
   at 1e16 and 1e-5 and writes `1e+16` / `1e-05`, so there is no range in which
   they agree on such a value.

   Not repaired by stringifying in Clojure instead: the values are *ordered* by
   the rendering, so printing one rendering and sorting by another is a worse
   contract than either. All four are re-embeddings, not losses — one extra
   model round trip apiece at migration, once."
  [st]
  (chunks-in (map with-samples (vals (sub/objects st)))))

;; ---------------------------------------------------------------------------
;; the sidecar — append-only EDN lines, last-wins by :id
;; ---------------------------------------------------------------------------

(defn- append-line!
  "One record into the store's backing log, last-wins by `:id`.

   Takes the STORE, not a path. It used to take a file and `spit` to it; the
   argument changed when chunks moved into the substrate's own environment, so
   that both write sites — `retire!` and `store-vector!` — move together rather
   than each learning where chunks live.

   The `:file` key is gone rather than kept beside `:store`, deliberately.
   Clojure binds a missing destructured key to nil in silence, so a write site
   that had not been updated would go on appending to an EDN file nothing reads
   — which is exactly what `loci.memory` had to be rescued from on 2026-08-15,
   where three out-of-namespace callers were still reaching for `:file`."
  [{:keys [store file]} rec]
  (if store
    (dlv/put-chunk! store rec)
    (do (io/make-parents (io/file file))
        (spit file (str (pr-str rec) "\n") :append true))))

(defn- load-chunks [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defn file-chunks
  "A chunk store over an EDN-lines `path`.

   Kept for the tests, which pass a path and want a store they can delete and
   reload without a Datalevin environment. The server uses `store-chunks`."
  ([] (file-chunks (str (sub/data-dir) "/chunks.edn")))
  ([path] {:!chunks (atom (load-chunks path)) :file path}))

(defn store-chunks
  "A chunk store over the substrate's own `chunks` dbi.

   Chunks are derived — `chunks-of` is a pure function of one object, and the
   corollary the docstring at the top of this namespace states is that the whole
   store can be deleted and re-swept byte-equivalently. So this could have been a
   rebuild rather than a migration. It is a migration because the *vectors* are
   not free: re-deriving 94 chunks costs nothing, re-embedding them costs a round
   trip each to a model server that may not be running."
  [st]
  {:!chunks (atom (dlv/all-chunks st)) :store st})

(defn migrate-from-file!
  "Copy `data/chunks.edn` into `st`'s `chunks` dbi. Returns `{:migrated n :skipped n}`.

   **No dry run, no refusals, and that is proportionate.** `loci.factmigrate`
   rehearses because a fact is written by an agent and cannot be recomputed;
   a chunk is a pure function of an object plus an embedder call, so the worst
   case here is deleting the dbi and sweeping again. Writes are last-wins by
   `:id` and the ids are content-derived, so running this twice is a no-op.

   Retired records migrate like any other. Dropping them would let a retired id
   read as absent on the next sweep and be embedded all over again.

   The source file is never written or deleted; it stays as the rollback until
   somebody decides otherwise."
  ([st] (migrate-from-file! st (str (sub/data-dir) "/chunks.edn")))
  ([st path]
   (let [src (load-chunks path)
         dst (dlv/all-chunks st)
         todo (remove (fn [[id rec]] (= rec (get dst id))) src)]
     (doseq [[_ rec] todo] (dlv/put-chunk! st rec))
     {:migrated (count todo)
      :skipped  (- (count src) (count todo))
      :total    (count (dlv/all-chunks st))})))

(defonce ^{:doc "The server's chunk store — the `chunks` dbi of the process's one store.

   Was `(delay (file-chunks))` over `data/chunks.edn` until 2026-08-15. A delay
   behind an ordinary var because that is the seam the suite tests directly:
   `chunks-test` asserts this var holds an unrealized delay, so that requiring
   this namespace never touches the user's data directory."}
  chunks (delay (store-chunks @dlv/store)))

(defn- retired?
  "True for a record retired because the substrate no longer implies its chunk.

   The file is append-only, so a chunk cannot be removed from it; retiring
   appends a final record for the id carrying `:retired`. It stays in the map so
   nothing reads the old vector, and a chunk that comes back later simply
   appends a full record again — last-wins by :id."
  [rec]
  (true? (:retired rec)))

(defn stored
  "The live records in the store, by id: everything not retired."
  [{:keys [!chunks]}]
  (into {} (remove (comp retired? val)) @!chunks))

(defn- awaiting?
  "True when the substrate's `want` has no stored vector usable for it: none at
   all, one stamped with a different model, or one whose hash says it describes
   text that has since been edited."
  [have want model]
  (let [s (get have (:id want))]
    (or (nil? s)
        (empty? (:vec s))
        (not= model (:model s))
        (not= (:hash want) (:hash s)))))

(defn embedded
  "The stored chunks carrying a vector for `model`, ready to be scored — what a
   semantic search runs over. Records, so each one has its `:object`, `:text`
   and `:vec`."
  ([cs] (embedded cs (embed/embed-model)))
  ([cs model]
   (->> (vals (stored cs))
        (filterv #(and (seq (:vec %)) (= model (:model %)))))))

(defn pending
  "The chunks the substrate implies that have no usable vector — what the shell
   reports as \"N awaiting embedding\" rather than pretending they are
   searchable by meaning.

   Computed against the substrate rather than against the file, because the
   substrate is the truth and the file is a cache of vectors for it. That is
   what makes a deleted `chunks.edn` a rebuild: everything is simply pending."
  ([st cs] (pending st cs (embed/embed-model)))
  ([st cs model]
   (let [have (stored cs)]
     (filterv #(awaiting? have % model) (all-chunks st)))))

(defn- retire!
  "Append a tombstone for every stored chunk the substrate no longer implies,
   and return how many. A deleted document's vectors must stop answering; they
   describe text that is no longer stored, and a hit on one would open an object
   that does not contain it."
  [{:keys [!chunks] :as cs} wanted-ids]
  (let [gone (remove wanted-ids (keys (stored cs)))]
    (doseq [id gone]
      (let [rec {:id id :retired true}]
        (swap! !chunks assoc id rec)
        (append-line! cs rec)))
    (count gone)))

(def ^:private batch-size
  "Texts per request. The live corpus is ~94 chunks, so this is three requests
   rather than one enormous body; it costs nothing at this scale and keeps a
   first backfill of a much larger substrate from being a single huge POST."
  32)

(defn- store-vector!
  "Persist one chunk: the record with `:vec`, `:model` and `:dim`, appended
   under its id. Returns true when it was written.

   `fresh` is the hash the substrate gives this chunk id **now**, re-derived
   after the embedder answered. If the text moved while the embedder was
   thinking — or the chunk is gone — nothing is written: the vector describes
   text that is no longer stored, and pairing it with the new text is the silent
   mismatch this whole path exists to avoid. The chunk simply stays pending and
   the next sweep embeds what it now says. Same guard as
   `loci.memory/store-vector!`, for the same reason."
  [{:keys [!chunks] :as cs} fresh chunk vector model dim]
  (when (= (:hash chunk) (get fresh (:id chunk)))
    (let [rec (assoc chunk :vec vector :model model :dim dim)]
      (swap! !chunks assoc (:id rec) rec)
      (append-line! cs rec)
      true)))

(defonce ^{:private true
           :doc "messages already reported by warn-once!, so a timer does not repeat them"}
  !warned (atom #{}))

(defn- warn-once!
  "Print `msg` to stderr the first time it is seen and never again — a sweep
   runs on a timer, and an embedder that is down would otherwise write the same
   line every interval for as long as loci is up."
  [msg]
  (when-not (contains? @!warned msg)
    (swap! !warned conj msg)
    (binding [*out* *err*] (println "loci.chunks:" msg))))

(defn sweep!
  "Bring the chunk store into line with the substrate. Returns

     {:embedded n :pending n :retired n :model \"…\"}   with :error if a batch failed
     {:off true :retired n}                            no embedding endpoint configured

   and never throws.

   A plain function: the server calls it, a worker calls it on a timer, a test
   calls it directly. Requiring this namespace calls it never, and **it commits
   no substrate event** — see the namespace docstring for why that is not an
   omission.

   Retiring happens before the embedder is consulted, because a vector for text
   that has been deleted is wrong whether or not an embedder is reachable.

   **Resumable by construction.** Nothing marks a chunk as tried — a chunk that
   failed simply has no stored vector, the state it was already in, so the next
   sweep picks it up again. A sweep killed mid-pass loses only the batch in
   flight.

   It stops at the first failing batch rather than working through the rest. The
   overwhelmingly likely cause is that the embedder is down, and twenty more
   30-second timeouts discover that twenty more times.

   Two sweeps at once do the same work twice and append the same :id twice.
   Last-wins by :id makes that harmless — a duplicate line, not a wrong chunk —
   which is why no lock is taken."
  [st cs]
  (let [want    (all-chunks st)
        retired (retire! cs (set (map :id want)))]
    (if-not (embed/embedding-configured?)
      {:off true :retired retired}
      (let [model (embed/embed-model)
            ;; one `stored` snapshot per question asked, not one per chunk —
            ;; it rebuilds a map over the whole store every call
            left  (fn [] (let [have (stored cs)]
                           (count (filterv #(awaiting? have % model) want))))
            todo  (let [have (stored cs)]
                    (filterv #(awaiting? have % model) want))]
        (loop [batches (partition-all batch-size todo)
               done    0]
          (if-let [batch (seq (first batches))]
            (let [r (embed/embed-texts (mapv :text batch))]
              (if (:error r)
                (do (warn-once! (:error r))
                    {:embedded done :pending (left) :retired retired
                     :model model :error (:error r)})
                ;; ONE re-derivation per batch, not one per chunk: `sub/objects`
                ;; re-folds the whole log to answer, and the window this guard
                ;; covers is a single HTTP round trip.
                (let [fresh (into {} (map (juxt :id :hash)) (all-chunks st))
                      wrote (reduce (fn [n [c v]]
                                      ;; :model is the *configured* name, not the
                                      ;; one the response reports — llama.cpp
                                      ;; answers with a file path, and a chunk
                                      ;; stamped with that would never match
                                      ;; again and be re-embedded forever.
                                      (if (store-vector! cs fresh c v (:model r) (:dim r))
                                        (inc n) n))
                                    0 (map vector batch (:vectors r)))]
                  (recur (next batches) (+ done wrote)))))
            {:embedded done :pending (left) :retired retired :model model}))))))
