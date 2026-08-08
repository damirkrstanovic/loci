(ns loci.chunks-test
  "Chunks: what the substrate says should be embedded, and the sidecar that
   holds the vectors.

   Two halves, and the split is the point. Chunking is a **pure function of one
   object** — no store, no file, no embedder — so most of this file constructs a
   map and reads what comes back. The store half runs against a **temp path**
   handed in explicitly; nothing here derefs `loci.chunks/chunks`, which resolves
   to the user's real data directory.

   Nothing reaches a real embedder either. The stub binds port 0 and is asked
   which port it got, and `loci.embed`'s env/file seams are redefined so a
   LOCI_EMBED_ENDPOINT exported in the shell cannot aim these tests at somebody's
   actual box — the same arrangement as `loci.memory-embed-test`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.chunks :as ch]
            [loci.embed :as embed]
            [loci.substrate :as sub]
            [org.httpkit.server :as server])
  (:import [java.net ServerSocket]))

;; ---------------------------------------------------------------------------
;; temp locations that do not outlive the test
;; ---------------------------------------------------------------------------

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-tmpfile
  "A fresh chunks.edn path under a fresh directory, always removed."
  [[sym] & body]
  `(let [root# (str (System/getProperty "java.io.tmpdir") "/loci-chunks-test-" (System/nanoTime))
         ~sym  (str root# "/chunks.edn")]
     (try ~@body (finally (rm-rf root#)))))

;; ---------------------------------------------------------------------------
;; the embedder stub
;; ---------------------------------------------------------------------------

(defn- json-body [status data]
  {:status status :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- vec-for
  "A deterministic 1024-float vector for a text. The numbers mean nothing — no
   test here asserts anything about similarity, only about what is stored,
   persisted and re-requested."
  [text]
  (let [h (/ (double (mod (Math/abs (hash text)) 97)) 100.0)]
    (vec (repeat 1024 h))))

(defn- embed-handler [req]
  (let [input (:input (json/read-str (:body req) :key-fn keyword))]
    (json-body 200 {:model "/models/qwen3-embedding-0.6b-q8_0.gguf"
                    :data  (vec (map-indexed (fn [i t] {:index i :embedding (vec-for t)}) input))})))

(defn- with-stub
  "Start `handler` on an ephemeral loopback port, call `(f port seen)`, stop the
   server. `seen` holds every request received, so a test can assert on what
   actually went over the wire — including that nothing did."
  [handler f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                (let [r (assoc (select-keys req [:uri :request-method])
                               :body (some-> (:body req) slurp))]
                  (swap! seen conj r)
                  (handler r)))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (server/server-port srv) seen)
         (finally (server/server-stop! srv {:timeout 100})))))

(defn- with-env
  "Run `f` with loci.embed's resolvers seeing exactly `env` and no files."
  [env f]
  (with-redefs [embed/env #(get env %) embed/from-file (constantly nil)]
    (f)))

(defn- embedding-at [port]
  {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" port "/v1/embeddings")})

(defn- a-closed-port
  "A port with nothing listening: bind one, ask which it was, close it."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- texts-asked-for [seen]
  (vec (mapcat #(:input (json/read-str (:body %) :key-fn keyword)) @seen)))

;; ---------------------------------------------------------------------------
;; fixtures — the shapes the real substrate actually holds
;; ---------------------------------------------------------------------------

(def ^:private churn-doc
  (str "# Retention — Q3\nChurn rose to 4.2% in Q3, concentrated in accounts under twelve months old.\n"
       "## Pricing\nSupport volume for these accounts tripled after the March pricing change.\n"
       "## Recovery\nOutreach within 7 days of a downgrade historically recovered ~22%."))

(defn- doc-obj [id title text] {:id id :kind :doc :title title :value text})

(defn- table-obj [id title rows] {:id id :kind :table :title title :value rows})

(defn- big-rows
  "A table of `n` rows in the shape the substrate really holds — a vector of
   maps with keyword columns."
  [n]
  (vec (for [i (range n)]
         (array-map :month (format "2026-%02d" (inc (mod i 12)))
                    :account (str "A-" (+ 100 i))
                    :region (nth ["NA" "EMEA" "APAC" "LATAM"] (mod i 4))
                    :revenue (* 1000 (inc i))
                    :churn (double (/ (mod i 97) 10))))))

(def ^:private notebook
  {:id "space:retention" :kind :space :title "Retention, Q3"
   :value {:intent "Find why Q3 churn rose, then draft the memo."
           :cells [{:text "Support volume tripled after the March pricing change."}
                   {:ref "tbl:revenue" :view "table/bar"}
                   {:text "The recovery window is seven days; after that the account is gone."}]}})

(def ^:private report
  {:id "report:retention" :kind :report :title "Retention memo — Q3"
   :value [{:block :heading :text "Retention memo — Q3"}
           {:block :text :text "Churn rose to 4.2% in Q3, concentrated in accounts under twelve months old."}
           {:block :table :title "Churn by month & tenure" :rows (big-rows 12)}
           {:block :chart :spec {:chart "line" :x "month" :y "churn" :rows (big-rows 12)}}
           {:block :heading :text "Recovery"}
           {:block :text :text "Outreach within 7 days of a downgrade historically recovered ~22%."}]})

(defn- corpus
  "A realistic little substrate: notebooks, docs, a report, tables, a metric,
   and the machinery that must not be embedded."
  []
  [notebook
   report
   (doc-obj "doc:churn" "Retention notes — Q3" churn-doc)
   (doc-obj "doc:flat" "No headings at all"
            "One paragraph, no markdown structure whatsoever, but it is still text somebody wrote.")
   (table-obj "tbl:revenue" "Q3 revenue & churn" (big-rows 24))
   {:id "metric:mrr" :kind :metric :title "MRR (24 months)" :value (big-rows 24)}
   {:id "view:1" :kind :viewspec :title "view: by region"
    :value {:label "by region" :target "tbl:revenue" :spec {}}}
   {:id "app:1" :kind :applet :title "app: churn" :value {:label "churn" :code "…" :target "tbl:revenue"}}
   {:id "fn:1" :kind :fn :title "fn: normalize" :value {:label "normalize" :source "tbl:revenue"}}
   {:id "tag-palette" :kind :palette :title "tag colours" :value {"churn" "#2f6f5b"}}])

(defn- store-with
  "A fresh in-memory substrate holding `objs`, committed in the given order."
  [objs]
  (let [st (sub/fresh-store)]
    (doseq [o objs] (sub/commit! st {:op :put :id (:id o) :value o}))
    st))

(defn- id+hash [chunks] (mapv (juxt :id :hash) chunks))

(defn- for-object [chunks id] (filterv #(= id (:object %)) chunks))

;; ===========================================================================
;; chunking — a pure function of one object
;; ===========================================================================

(deftest a-notebook-gives-one-chunk-for-its-title-and-intent
  (let [cs (ch/chunks-of notebook)
        c  (first cs)]
    (is (= "space:retention#0" (:id c)))
    (is (= "space:retention" (:object c)))
    (is (= :space (:kind c)))
    (is (str/includes? (:text c) "Retention, Q3") "the title")
    (is (str/includes? (:text c) "Find why Q3 churn rose") "and the intent")
    (is (not (str/includes? (:text c) "Support volume tripled"))
        "a prose cell is its own chunk, not folded into the notebook's")))

(deftest prose-cells-get-one-chunk-each-and-ref-cells-get-none
  (let [cs (ch/chunks-of notebook)]
    (is (= 3 (count cs)) "title+intent, and one per prose cell — the ref cell is not text")
    (is (= ["space:retention#0" "space:retention#1" "space:retention#2"] (mapv :id cs)))
    (is (= "Support volume tripled after the March pricing change." (:text (nth cs 1))))
    (is (= "The recovery window is seven days; after that the account is gone." (:text (nth cs 2))))
    (is (every? #(= "space:retention" (:object %)) cs)
        "every chunk points at the notebook — a hit opens the notebook, not the fragment")))

(deftest a-doc-body-splits-on-markdown-headings
  (let [cs (ch/chunks-of (doc-obj "doc:churn" "Retention notes — Q3" churn-doc))]
    (is (= 3 (count cs)) "one section per heading")
    (is (= ["doc:churn#0" "doc:churn#1" "doc:churn#2"] (mapv :id cs)))
    (is (str/starts-with? (:text (nth cs 0)) "# Retention — Q3"))
    (is (str/starts-with? (:text (nth cs 1)) "## Pricing"))
    (is (str/starts-with? (:text (nth cs 2)) "## Recovery"))
    (is (str/includes? (:text (nth cs 2)) "recovered ~22%")
        "a section carries its heading AND its body")))

(deftest a-body-with-no-headings-still-yields-a-chunk
  (let [cs (ch/chunks-of (doc-obj "doc:flat" "Flat" "Just one paragraph, written by a person."))]
    (is (= 1 (count cs)))
    (is (= "Just one paragraph, written by a person." (:text (first cs))))))

(deftest an-empty-doc-yields-no-chunks
  (is (= [] (ch/chunks-of (doc-obj "doc:empty" "Empty" ""))))
  (is (= [] (ch/chunks-of (doc-obj "doc:blank" "Blank" "   \n\n  ")))))

(deftest a-section-longer-than-the-limit-splits-again-without-cutting-a-heading
  (let [para (str (str/join " " (repeat 400 "churn")) ".")     ; ~2.4k chars
        body (str "# Long one\n\n" para "\n\n" para "\n\n" para)
        cs   (ch/chunks-of (doc-obj "doc:long" "Long" body))]
    (is (< 1 (count cs)) "a section over the limit is cut further")
    (is (every? #(<= (count (:text %)) ch/max-chars) cs)
        (str "every chunk within " ch/max-chars " chars: "
             (pr-str (mapv #(count (:text %)) cs))))
    ;; The heading is a whole line and lines are only ever cut whole, so it
    ;; cannot be halved; each piece keeps it, so no piece is a bare heading.
    (is (every? #(str/starts-with? (:text %) "# Long one") cs)
        "every piece of a split section still says which section it belongs to")
    (is (not-any? #(re-find #"# Lon$|^g one" (:text %)) cs) "and none of them cut the heading itself")))

(deftest a-word-longer-than-the-limit-is-still-chunked-rather-than-dropped
  (let [cs (ch/chunks-of (doc-obj "doc:blob" "Blob" (str/join (repeat 6000 "x"))))]
    (is (= 3 (count cs)))
    (is (every? #(<= (count (:text %)) ch/max-chars) cs))
    (is (= 6000 (reduce + (map #(count (:text %)) cs))) "nothing lost at the cut")))

;; ---- tables: ONE synopsis, bounded, never one vector per row ----

(deftest a-table-gets-one-bounded-synopsis-and-not-its-rows
  (let [rows (big-rows 2584)
        cs   (ch/chunks-of (table-obj "tbl:orders" "E-commerce orders" rows))
        t    (:text (first cs))]
    (is (= 1 (count cs))
        (str "one synopsis for a 2584-row table, got " (count cs)
             " — row vectors are the failure this rule exists to prevent"))
    (is (str/includes? t "E-commerce orders") "the title")
    (is (every? #(str/includes? t (name %)) [:month :account :region :revenue :churn])
        "and every column name")
    (is (<= (count t) (inc ch/synopsis-max-chars))
        (str "the synopsis is bounded — " (count t) " chars for 2584 rows"))
    ;; The bound above is the real guard, but state the failure directly too:
    ;; a synopsis that grew with the table would be tens of thousands of chars.
    (is (< (count t) (/ (count (pr-str rows)) 100.0))
        "the synopsis does not scale with the number of rows")
    (is (not (str/includes? t "A-2000")) "no row from deep in the table is in it")))

(deftest a-metric-is-a-table-for-this-purpose
  (let [cs (ch/chunks-of {:id "metric:mrr" :kind :metric :title "MRR (24 months)" :value (big-rows 24)})]
    (is (= 1 (count cs)))
    (is (= "metric:mrr#0" (:id (first cs))))
    (is (str/includes? (:text (first cs)) "MRR (24 months)"))))

(deftest a-table-with-no-rows-falls-back-to-its-title
  (let [cs (ch/chunks-of (table-obj "tbl:empty" "Nothing here yet" []))]
    (is (= 1 (count cs)))
    (is (= "Nothing here yet" (:text (first cs))))))

;; ---- reports ----

(deftest a-report-is-chunked-by-its-headings-and-carries-no-rows
  (let [cs (ch/chunks-of report)]
    (is (= 2 (count cs)) "two headings, two sections")
    (is (str/includes? (:text (first cs)) "Churn rose to 4.2%") "its prose")
    (is (str/includes? (:text (first cs)) "Churn by month & tenure") "and its data blocks by title")
    (is (str/includes? (:text (last cs)) "recovered ~22%"))
    (is (not-any? #(str/includes? (:text %) "A-105") cs)
        "a report's table rows are not in its chunks — the table object has the synopsis")))

;; ---- machinery is not embedded ----

(deftest viewspecs-applets-functions-and-the-palette-get-no-chunks
  (doseq [o (filter #(#{:viewspec :applet :fn :palette} (:kind %)) (corpus))]
    (is (= [] (ch/chunks-of o)) (str (:kind o) " is machinery, not something anyone wrote")))
  (is (= [] (ch/chunks-of {:id "flow:1" :kind :flow :title "Flow — x" :value {:steps []}}))
      "a flow is a live checklist; the spec's table does not list it"))

;; ---- determinism and stability ----

(deftest chunking-the-same-object-twice-gives-identical-ids-and-hashes
  (doseq [o (corpus)]
    (is (= (ch/chunks-of o) (ch/chunks-of o)) (str "not deterministic: " (:id o)))))

(deftest chunk-ids-do-not-depend-on-what-else-is-in-the-store
  ;; Ids are the object's id plus the chunk's index *within that object*, so a
  ;; chunk carries no trace of the enumeration that found it. A global counter
  ;; over the objects — the obvious wrong implementation — fails here twice: the
  ;; ids change when the corpus does, and they change when the order does.
  (let [alone (ch/all-chunks (store-with [(doc-obj "doc:churn" "Retention notes — Q3" churn-doc)]))
        among (ch/all-chunks (store-with (corpus)))
        rev   (ch/all-chunks (store-with (reverse (corpus))))]
    (is (= (id+hash alone) (id+hash (for-object among "doc:churn")))
        "the same object chunked alone and among nine others")
    (is (= (id+hash among) (id+hash rev))
        "and committing the objects in the opposite order changes nothing")
    (is (= (dedupe (mapv :object among)) (sort (distinct (mapv :object among))))
        "the enumeration itself is ordered by object id, so a sweep batches the same work the same way twice")))

(deftest editing-one-doc-changes-only-that-docs-chunk-hashes
  (let [before (ch/all-chunks (store-with (corpus)))
        edited (map (fn [o] (if (= "doc:churn" (:id o))
                              (assoc o :value (str/replace churn-doc "4.2%" "5.9%"))
                              o))
                    (corpus))
        after  (ch/all-chunks (store-with edited))
        moved  (fn [cs] (into {} (map (juxt :id :hash)) cs))]
    (is (= (set (map :id before)) (set (map :id after))) "the same chunks exist")
    (let [changed (->> (map :id before)
                       (remove #(= (get (moved before) %) (get (moved after) %)))
                       set)]
      (is (= #{"doc:churn#0"} changed)
          (str "exactly the edited section's hash moved, got " (pr-str changed))))))

;; ===========================================================================
;; the store — every path explicit, never the real data dir
;; ===========================================================================

(deftest a-sweep-embeds-every-chunk-and-a-second-sweep-embeds-nothing
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port seen]
        (let [st (store-with (corpus))
              cs (ch/file-chunks path)]
          (with-env (embedding-at port)
            (fn []
              (let [want (count (ch/all-chunks st))
                    r    (ch/sweep! st cs)]
                (is (pos? want))
                (is (= want (:embedded r)) "every chunk the substrate implies")
                (is (= 0 (:pending r)))
                (is (= want (count (ch/embedded cs)))))
              (let [asked (count @seen)
                    r     (ch/sweep! st cs)]
                (is (= 0 (:embedded r)) "already embedded for this model")
                (is (= 0 (:pending r)))
                (is (= asked (count @seen))
                    "and no second request — otherwise every pass re-embeds everything forever")))))))))

(deftest a-vector-survives-a-reload-because-it-is-one-more-append
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port _]
        (let [st (store-with (corpus))]
          (with-env (embedding-at port) #(ch/sweep! st (ch/file-chunks path)))
          (let [reloaded (ch/file-chunks path)
                ;; through with-env like everything else: a LOCI_EMBED_MODEL
                ;; exported in the shell must not decide what this test sees
                stored   (with-env (embedding-at port) #(ch/embedded reloaded))]
            (is (= (count (ch/all-chunks st)) (count stored)))
            (is (every? #(= 1024 (count (:vec %))) stored))
            (is (every? #(= "embed-qwen3-0.6b" (:model %)) stored)
                "the configured model name, not the path the server reported")
            (is (every? #(= 1024 (:dim %)) stored))
            (is (= [] (with-env (embedding-at port) #(ch/pending st reloaded)))
                "nothing awaiting after a reload")))))))

(deftest editing-a-doc-makes-exactly-its-chunks-pending-again
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port seen]
        (let [objs (corpus)
              st   (store-with objs)
              cs   (ch/file-chunks path)]
          (with-env (embedding-at port)
            (fn []
              (ch/sweep! st cs)
              (is (= [] (ch/pending st cs)))
              (reset! seen [])
              ;; the same edit as the pure test above, but through the substrate
              (sub/commit! st {:op :assoc :id "doc:churn" :path [:value]
                               :value (str/replace churn-doc "4.2%" "5.9%")})
              (is (= ["doc:churn#0"] (mapv :id (ch/pending st cs)))
                  "the edited section, and nothing else in the corpus")
              (let [r (ch/sweep! st cs)]
                (is (= 1 (:embedded r)))
                (is (= 0 (:pending r))))
              (is (= 1 (count (texts-asked-for seen)))
                  "one text over the wire — staleness is by content hash, not a blanket refresh")
              (is (str/includes? (first (texts-asked-for seen)) "5.9%")))))))))

(deftest changing-the-model-makes-every-chunk-pending
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port _]
        (let [st (store-with (corpus))
              cs (ch/file-chunks path)
              at (embedding-at port)
              n  (count (ch/all-chunks st))]
          (with-env (assoc at "LOCI_EMBED_MODEL" "model-a")
            (fn []
              (is (= n (:embedded (ch/sweep! st cs))))
              (is (= [] (ch/pending st cs)))))
          (with-env (assoc at "LOCI_EMBED_MODEL" "model-b")
            (fn []
              (is (= n (count (ch/pending st cs)))
                  "a vector from another model is worse than none — cosine across two spaces still sorts")
              (is (= n (:embedded (ch/sweep! st cs))))
              (is (= n (count (ch/embedded cs "model-b"))))
              (is (= 0 (count (ch/embedded cs "model-a")))
                  "and the old model's vectors are not offered to the new one"))))))))

(deftest deleting-the-chunks-file-and-sweeping-restores-byte-equivalent-ids-and-hashes
  ;; This is the test that keeps "derived, never truth" honest. If it ever fails,
  ;; the chunk store has become a place where something is *kept* rather than
  ;; recomputed, and deleting it is a loss instead of a rebuild.
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port _]
        (let [st (store-with (corpus))]
          (with-env (embedding-at port)
            (fn []
              (ch/sweep! st (ch/file-chunks path))
              (let [before (sort-by :id (ch/embedded (ch/file-chunks path)))]
                (is (.delete (io/file path)))
                (is (not (.exists (io/file path))))
                (is (= [] (ch/embedded (ch/file-chunks path))) "gone")
                (let [cs (ch/file-chunks path)]
                  (ch/sweep! st cs)
                  (let [after (sort-by :id (ch/embedded cs))]
                    (is (= (mapv (juxt :id :hash) before) (mapv (juxt :id :hash) after))
                        "byte-equivalent ids and hashes, rebuilt from the substrate alone")
                    (is (= (mapv :text before) (mapv :text after)))
                    (is (= (mapv :vec before) (mapv :vec after))))))))))))
  ;; and no substrate event was ever written for any of it
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port _]
        (let [st (store-with (corpus))
              n  (count (sub/history st))]
          (with-env (embedding-at port) #(ch/sweep! st (ch/file-chunks path)))
          (is (= n (count (sub/history st)))
              "chunks are derived: committing them would make derived data undoable and drag it into time travel"))))))

(deftest a-chunk-whose-object-is-gone-stops-being-searchable
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [port _]
        (let [st (store-with (corpus))
              cs (ch/file-chunks path)]
          (with-env (embedding-at port)
            (fn []
              (ch/sweep! st cs)
              (is (some #(= "doc:churn#0" (:id %)) (ch/embedded cs)))
              (sub/commit! st {:op :delete :id "doc:churn"})
              (let [r (ch/sweep! st cs)]
                (is (= 3 (:retired r)) "the deleted doc's three chunks"))
              (is (not-any? #(str/starts-with? (:id %) "doc:churn") (ch/embedded cs))
                  "a stale vector must not answer for text that is no longer stored")
              (is (not-any? #(str/starts-with? (:id %) "doc:churn")
                            (ch/embedded (ch/file-chunks path)))
                  "and the retirement survives a reload"))))))))

;; ---- degradation: unconfigured, and unreachable ----

(deftest with-no-embedder-configured-a-sweep-is-off-and-asks-for-nothing
  (with-tmpfile [path]
    (with-stub embed-handler
      (fn [_port seen]
        (let [st (store-with (corpus))
              cs (ch/file-chunks path)]
          (is (:off (with-env {} #(ch/sweep! st cs)))
              "unconfigured is not an error — LEAP is exactly what it is today")
          (is (= [] @seen) "and nothing goes to a baked-in default")
          (is (= (count (ch/all-chunks st)) (count (with-env {} #(ch/pending st cs))))
              "still counted as awaiting rather than pretended embedded")
          (is (= [] (ch/embedded cs)))
          (is (not (.exists (io/file path))) "and no file was written"))))))

(deftest an-unreachable-embedder-leaves-chunks-unembedded-and-the-next-sweep-picks-them-up
  (with-tmpfile [path]
    (let [dead (a-closed-port)
          st   (store-with (corpus))
          cs   (ch/file-chunks path)
          n    (count (ch/all-chunks st))]
      (with-env (embedding-at dead)
        (fn []
          ;; the connection failure is expected; captured so a passing suite does
          ;; not print a line that reads like a failure
          (let [r (binding [*err* (java.io.StringWriter.)] (ch/sweep! st cs))]
            (is (string? (:error r)) "the failure is a value, not a throw")
            (is (= 0 (:embedded r)))
            (is (= n (:pending r))))
          (is (= [] (ch/embedded cs)) "nothing half-written")))
      ;; Nothing marked a chunk as tried — which is the whole of resumability.
      ;; A failed sweep leaves a chunk with no vector, the state it was already
      ;; in, so the next pass picks it up again.
      (with-stub embed-handler
        (fn [port _]
          (with-env (embedding-at port)
            (fn []
              (let [r (ch/sweep! st cs)]
                (is (= n (:embedded r)))
                (is (= 0 (:pending r))))
              (is (= n (count (ch/embedded cs)))))))))))

(deftest loading-this-namespace-writes-nothing-and-resolves-no-data-dir
  (is (var? #'ch/chunks) "the singleton exists")
  (is (delay? @#'ch/chunks) "and is a delay — requiring loci.chunks must not touch the user's data dir")
  (is (not (realized? @#'ch/chunks))
      "nothing in this suite has forced it; every test passes an explicit temp path"))
