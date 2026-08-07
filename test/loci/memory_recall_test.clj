(ns loci.memory-recall-test
  "Hybrid recall: the lexical scorer, cosine, and the reciprocal-rank fusion of
   the two — with provenance on every hit and a scope that does not leak.

   Nothing here reaches a real embedder, and nothing here needs one to be
   *plausible*: the stub returns vectors this file chose, so \"found by meaning\"
   is an assertion about fusion rather than a hope about a model. The vectors
   are five-dimensional and axis-aligned, which makes every cosine in this file
   either 1.0 or 0.0 and every expected ranking arithmetic rather than opinion.

   Every memory is a fresh file under java.io.tmpdir. Nothing derefs
   `loci.memory/memory`, which resolves to the user's real memory.edn."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [loci.embed :as embed]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [org.httpkit.server :as server])
  (:import [java.net ServerSocket]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-recall-test-" (System/nanoTime) "/memory.edn"))

(defn- a-closed-port
  "A port with nothing listening: bind one, ask which it was, close it."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- with-env
  "Run `f` with loci.embed's resolvers seeing exactly `env` and no files — a
   LOCI_EMBED_ENDPOINT exported in the shell cannot aim these tests at anyone's
   actual box."
  [env f]
  (with-redefs [embed/env #(get env %) embed/from-file (constantly nil)]
    (f)))

(defn- embedding-at [port]
  {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" port "/v1/embeddings")})

(defn- reranking-at [port]
  {"LOCI_RERANK_ENDPOINT" (str "http://127.0.0.1:" port "/v1/rerank")})

;; ---- the little vector space ----

(def ^:private dims 5)

(defn- v
  "A unit vector along the named axes. (v 0) and (v 1) are orthogonal, so their
   cosine is exactly 0 and 'not similar' in this file means not similar at all."
  [& axes]
  (let [on (set axes)
        n  (Math/sqrt (count on))]
    (mapv #(if (on %) (/ 1.0 n) 0.0) (range dims))))

(def ^:private taiwan  "TSMC concentrates advanced nodes in Taiwan.")
(def ^:private neptune "Neptune takes 165 years to orbit the Sun.")
(def ^:private serbia  "Serbia's inflation spiked to about 12% in 2022.")

(def ^:private space
  "text → vector, for facts and for queries alike. Anything absent gets the last
   axis, which is orthogonal to everything named here — so a text this file
   forgot to place shows up as no semantic hit rather than as a coincidence."
  {taiwan       (v 0)
   neptune      (v 1)
   serbia       (v 2)
   ;; queries
   "lithography"      (v 0)     ; taiwan's meaning, none of taiwan's words
   "inflation"        (v 0)     ; serbia's words, taiwan's meaning
   "taiwan"           (v 0)     ; both
   "everything"       (v 0 1 2)}) ; equally close to all three

(defn- vec-for [text] (get space text (v (dec dims))))

;; ---- the stubs ----

(defn- json-body [data]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- stub-handler
  "One handler for both endpoints. The reranker returns the documents in exactly
   reverse order — an opinion no other signal in this file shares, so a test can
   tell whether the reranker's order was used."
  [req]
  (let [body (json/read-str (:body req) :key-fn keyword)]
    (if (re-find #"rerank" (:uri req))
      (json-body {:results (vec (map-indexed
                                 (fn [rank i] {:index i :relevance_score (- 1.0 rank)})
                                 (reverse (range (count (:documents body))))))})
      (json-body {:model "/models/whatever.gguf"
                  :data  (vec (map-indexed (fn [i t] {:index i :embedding (vec-for t)})
                                           (:input body)))}))))

(defn- with-stub
  "Start the stub on an ephemeral loopback port, call `(f port seen)`, stop it.
   `seen` holds every request received, so a test can assert on what went over
   the wire — including that nothing did."
  [f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                (let [r (assoc (select-keys req [:uri]) :body (some-> (:body req) slurp))]
                  (swap! seen conj r)
                  (stub-handler r)))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (server/server-port srv) seen)
         (finally (server/server-stop! srv {:timeout 100})))))

;; ---- fixtures ----

(defn- remember-all!
  "Three facts, each in its own notebook, in a fixed id order (mem-1 taiwan,
   mem-2 neptune, mem-3 serbia)."
  [m]
  (mold/remember m taiwan  {:entities ["tsmc"]   :source {:obj "find:semis-1" :space "space:semis"}})
  (mold/remember m neptune {:entities ["neptune"] :source {:obj "doc:neptune"  :space "space:sky"}})
  (mold/remember m serbia  {:entities ["serbia"]  :source {:obj "doc:serbia"   :space "space:econ"}})
  m)

(defn- facts-of [hits] (mapv :fact hits))
(defn- hit [hits text] (first (filter #(= text (:fact %)) hits)))

;; ---- the keystroke path: lexical only, and no network at all ----

(deftest lexical-only-is-what-it-always-was-and-asks-the-embedder-nothing
  (with-stub
    (fn [port seen]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "Neptune orbits the Sun" {})
            (mold/remember m serbia {:entities ["serbia"]})
            (mem/embed-pending! m)                       ; both facts have vectors
            (reset! seen [])
            (let [r (mold/recall m "neptune" {:k 5})]
              (is (= ["Neptune orbits the Sun"] (facts-of r)))
              ;; 0.6 × jaccard(#{neptune}, #{neptune orbits the sun}) = 0.6 × 0.25,
              ;; times a decay of ~1.0 on a fact remembered a millisecond ago and a
              ;; strength bonus of 1.0. The scorer's own constants, unchanged — a
              ;; fused score would be around 1/61 instead.
              (is (< (Math/abs (- 0.15 (:score (first r)))) 1e-4)
                  (str "the lexical score, not a fused one: " (:score (first r))))
              (is (= [:lexical] (:via (first r))))
              (is (= :skipped (:semantic (meta r)))))
            ;; This is the assertion the whole default is for. /api/leap calls
            ;; recall on every keystroke and embedding a query is a 20–50 ms round
            ;; trip; the facts here ARE embedded and the endpoint IS configured,
            ;; so nothing but the default is keeping this at zero.
            (is (= [] @seen)
                "not one request — the keystroke path must not wait on a model server")))))))

;; ---- meaning ----

(deftest a-fact-sharing-no-word-with-the-query-is-found-by-meaning
  (with-stub
    (fn [port _]
      (let [m (remember-all! (mem/file-memory (tmpfile)))]
        (with-env (embedding-at port)
          (fn []
            (is (= 3 (:embedded (mem/embed-pending! m))))
            (is (= [] (mold/recall m "lithography" {:k 5}))
                "lexically the word is in none of them")
            (let [r (mold/recall m "lithography" {:k 5 :semantic? true})]
              (is (= [taiwan] (facts-of r)) "and by meaning it is the fab fact")
              (is (= [:semantic] (:via (first r))) "which says so")
              (is (= :ok (:semantic (meta r)))))))))))

(deftest fusion-lets-neither-retriever-starve-the-other
  (with-stub
    (fn [port _]
      (let [m (remember-all! (mem/file-memory (tmpfile)))]
        (with-env (embedding-at port)
          (fn []
            (mem/embed-pending! m)
            ;; "inflation" is a word in the Serbia fact and, in this little
            ;; vector space, the meaning of the Taiwan one.
            (is (= [serbia] (facts-of (mold/recall m "inflation" {:k 5})))
                "lexical alone finds one of them")
            (let [r (mold/recall m "inflation" {:k 5 :semantic? true})]
              (is (= #{serbia taiwan} (set (facts-of r))))
              (is (= [:lexical] (:via (hit r serbia)))
                  "the fact only the words found is still here — RRF sums, it does not choose")
              (is (= [:semantic] (:via (hit r taiwan)))))))))))

(deftest a-fact-carrying-both-signals-says-so
  (with-stub
    (fn [port _]
      (let [m (remember-all! (mem/file-memory (tmpfile)))]
        (with-env (embedding-at port)
          (fn []
            (mem/embed-pending! m)
            (let [r (mold/recall m "taiwan" {:k 5 :semantic? true})]
              (is (= [:lexical :semantic] (:via (hit r taiwan)))
                  "both retrievers ranked it, and :via is in a fixed order so it is deterministic"))))))))

(deftest a-fact-with-no-vector-still-competes-lexically
  (with-stub
    (fn [port _]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m taiwan {:entities ["tsmc"]})
            (mem/embed-pending! m)
            ;; remembered after the pass: it has no vector and will not until the
            ;; next one, which is the ordinary state of a just-distilled fact
            (mold/remember m serbia {:entities ["serbia"]})
            (is (= 1 (count (mem/pending-facts m))))
            (let [r (mold/recall m "inflation" {:k 5 :semantic? true})]
              (is (= #{serbia taiwan} (set (facts-of r))))
              (is (= [:lexical] (:via (hit r serbia)))
                  "an unembedded fact is not excluded from recall, only from the semantic half"))))))))

;; ---- the reranker ----

(deftest the-reranker-owns-the-order-of-the-head-and-leaves-its-score-behind
  (with-stub
    (fn [port _]
      (let [m (remember-all! (mem/file-memory (tmpfile)))]
        (with-env (merge (embedding-at port) (reranking-at port))
          (fn []
            (mem/embed-pending! m)
            (let [plain    (mold/recall m "everything" {:k 5 :semantic? true})
                  reranked (mold/recall m "everything" {:k 5 :semantic? true :rerank? true})]
              (is (= 3 (count plain)))
              (is (= (reverse (facts-of plain)) (facts-of reranked))
                  "the stub reverses, and the reversal survives to the answer")
              (is (every? number? (map :rerank reranked))
                  "each hit carries the score that reordered it")
              (is (= :ok (:rerank (meta reranked))))
              (is (every? nil? (map :rerank plain))
                  "and rerank is opt-in — it is a second network call")))))))

  (testing "with no reranker configured the fused order stands and nothing is claimed"
    (with-stub
      (fn [port _]
        (let [m (remember-all! (mem/file-memory (tmpfile)))]
          (with-env (embedding-at port)
            (fn []
              (mem/embed-pending! m)
              (let [r (mold/recall m "everything" {:k 5 :semantic? true :rerank? true})]
                (is (= :off (:rerank (meta r))))
                (is (every? nil? (map :rerank r)))
                (is (nil? (:degraded (meta r))) "unconfigured is not degraded")))))))))

;; ---- a vector from another model is not a vector ----

(deftest a-fact-embedded-under-another-model-leaves-the-semantic-half-and-is-counted
  (with-stub
    (fn [port seen]
      (let [m  (remember-all! (mem/file-memory (tmpfile)))
            at (embedding-at port)]
        (with-env (assoc at "LOCI_EMBED_MODEL" "model-a")
          (fn []
            (mem/embed-pending! m)
            (is (= [taiwan] (facts-of (mold/recall m "lithography" {:k 5 :semantic? true})))
                "under the model they were embedded with, meaning finds it")))
        (with-env (assoc at "LOCI_EMBED_MODEL" "model-b")
          (fn []
            (reset! seen [])
            (is (= [] (mold/recall m "lithography" {:k 5 :semantic? true}))
                "under a different model those vectors describe another space and are not used")
            (is (= 3 (count (mem/pending-facts m)))
                "and they are awaiting embedding, which the memory pane reports — not silently dropped")
            (is (= [] @seen)
                "with nothing eligible to compare against, the query is not embedded either")))))))

;; ---- scope ----

(deftest a-scoped-recall-sees-only-its-own-sources-and-never-falls-back
  (with-stub
    (fn [port _]
      (let [m (remember-all! (mem/file-memory (tmpfile)))
            q "inflation orbit taiwan"]
        (with-env (embedding-at port)
          (fn []
            (mem/embed-pending! m)
            (is (= #{taiwan neptune serbia} (set (facts-of (mold/recall m q {:k 5}))))
                "unscoped, all three answer")
            (is (= [taiwan] (facts-of (mold/recall m q {:k 5 :filter {:sources #{"space:semis"}}})))
                "the notebook id scopes")
            (is (= [taiwan] (facts-of (mold/recall m q {:k 5 :filter {:sources #{"find:semis-1"}}})))
                "and so does the object id — a fact belongs to both")
            (is (= #{taiwan neptune}
                   (set (facts-of (mold/recall m q {:k 5 :filter {:sources #{"space:semis" "space:sky"}}}))))
                "a set, not one id — the caller walks the links and hands over what it found")

            ;; The two that matter. An empty result is an answer.
            (is (= [] (mold/recall m q {:k 5 :filter {:sources #{}}}))
                "no eligible source is an empty recall, NOT the whole memory")
            (is (= [] (mold/recall m q {:k 5 :filter {:sources #{"space:nothing-here"}}}))
                "and neither is a scope that owns nothing")

            ;; The semantic half is inside the scope too. Without this a scoped
            ;; recall would leak exactly the facts it was scoped to exclude —
            ;; and leak them by meaning, where the caller cannot see why.
            (is (= [taiwan] (facts-of (mold/recall m "lithography" {:k 5 :semantic? true})))
                "unscoped, meaning reaches the Taiwan fact")
            (is (= [] (mold/recall m "lithography" {:k 5 :semantic? true
                                                    :filter {:sources #{"space:sky"}}}))
                "scoped elsewhere, it does not — the filter is applied before either retriever")))))))

;; ---- degradation ----

(deftest an-unreachable-embedder-degrades-recall-to-words-and-says-so
  (let [m (remember-all! (mem/file-memory (tmpfile)))]
    (with-env {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" (a-closed-port) "/v1/embeddings")}
      (fn []
        ;; embed-pending! cannot reach it either, so nothing has a vector; give
        ;; them one from a stub first so the failure under test is the *query's*
        ;; embedding, not an absence of anything to compare it to
        (is (= 3 (count (mem/pending-facts m))))))
    (with-stub
      (fn [port _]
        (with-env (embedding-at port) (fn [] (mem/embed-pending! m)))))
    (with-env {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" (a-closed-port) "/v1/embeddings")}
      (fn []
        (let [r (mold/recall m "inflation" {:k 5 :semantic? true})]
          (is (= [serbia] (facts-of r)) "the words still work")
          (is (= [:lexical] (:via (first r))))
          (is (= :error (:semantic (meta r))))
          (is (string? (:degraded (meta r)))
              "and it says so rather than erroring or pretending it searched by meaning")))))

  (testing "no embedder configured at all is off, not degraded"
    (let [m (remember-all! (mem/file-memory (tmpfile)))]
      (with-env {}
        (fn []
          (let [r (mold/recall m "inflation" {:k 5 :semantic? true})]
            (is (= [serbia] (facts-of r)))
            (is (= :off (:semantic (meta r))))
            (is (nil? (:degraded (meta r)))
                "recall is exactly what it was before any of this existed")))))))
