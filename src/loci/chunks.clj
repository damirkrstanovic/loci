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
            [loci.embed :as embed]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

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
   enough that the samples are a hint, not the data. Taken from the front of the
   table, so appending rows does not churn the hash."
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

(defn- col-name [c] (if (keyword? c) (name c) (str c)))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- synopsis
  "One bounded description of a table: its title, its column names, and a few
   sample values per column.

   Not its rows. The whole point is that 2,584 rows produce one vector, and the
   clip at the end makes that a guarantee rather than an expectation — a caller
   can assert on the length and the assertion holds for any table.

   Columns come from the first rows in order of first appearance, so the answer
   does not depend on how a map happens to iterate. The row *count* is
   deliberately absent: it would move the hash on every appended row and buy
   nothing anyone searches for."
  [title rows]
  (let [cols (->> (take 50 rows) (mapcat keys) distinct (take synopsis-cols) vec)
        line (fn [c]
               (let [vs (->> rows (keep #(get % c)) (map #(clip (str %) 40))
                             distinct (take synopsis-samples))]
                 (str (col-name c) ": " (str/join ", " vs))))]
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
  [{:keys [kind title value]}]
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
    (:table :metric) (cond
                       (rows? value)                 [(synopsis title value)]
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

(defn all-chunks
  "Every chunk the substrate currently implies. Reads the store; writes nothing
   to it, ever."
  [st]
  (chunks-in (vals (sub/objects st))))

;; ---------------------------------------------------------------------------
;; the sidecar — append-only EDN lines, last-wins by :id
;; ---------------------------------------------------------------------------

(defn- append-line! [file rec]
  (io/make-parents (io/file file))
  (spit file (str (pr-str rec) "\n") :append true))

(defn- load-chunks [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defn file-chunks
  "A chunk store over `path`. The no-argument form resolves the user's real data
   directory and is for the server; **every test passes a path.**"
  ([] (file-chunks (str (sub/data-dir) "/chunks.edn")))
  ([path] {:!chunks (atom (load-chunks path)) :file path}))

(defonce ^{:doc "the server's chunk store singleton (data-dir resolved at first use)"}
  chunks (delay (file-chunks)))

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
  [{:keys [!chunks file] :as cs} wanted-ids]
  (let [gone (remove wanted-ids (keys (stored cs)))]
    (doseq [id gone]
      (let [rec {:id id :retired true}]
        (swap! !chunks assoc id rec)
        (append-line! file rec)))
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
  [{:keys [!chunks file]} fresh chunk vector model dim]
  (when (= (:hash chunk) (get fresh (:id chunk)))
    (let [rec (assoc chunk :vec vector :model model :dim dim)]
      (swap! !chunks assoc (:id rec) rec)
      (append-line! file rec)
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
