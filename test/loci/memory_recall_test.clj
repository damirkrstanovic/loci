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

;; ---- subject: the union, and what a name is worth ----

(def ^:private mendev  "Areelu Vorlesh masterminds a crusade against Mendev.")
(def ^:private euv     "EUV scanners ship from Veldhoven to Hsinchu and to Chandler.")
(def ^:private rapidus "Rapidus targets two-nanometre production by 2027.")

(defn- ubiquity-memory
  "Seven facts shaped like the measured corpus: two that name `asml` (2 live
   facts) and six that name `taiwan` and `united states` (6 each).

   `euv` and `rapidus` both match the query token `asml` on the entity half and
   NEITHER of them contains the word — so the keyword half is 0 for both and the
   only thing that can order them is what their *other* entities are worth.
   `euv`'s other two names are everywhere in this corpus; `rapidus`'s other name
   is nowhere else. That is the whole experiment.

   The five fillers score 0 and never appear: no shared word, no shared name."
  []
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m euv {:entities ["asml" "taiwan" "united states"]
                          :source   {:obj "find:euv-1" :space "space:euv"}})
    (mold/remember m rapidus {:entities ["asml" "rapidus"]
                              :source   {:obj "find:rap-1" :space "space:rapidus"}})
    (doseq [t ["Export controls tightened again in October."
               "A defence pact was signed last spring."
               "Tourism recovered to pre-pandemic levels."
               "Undersea cables were repaired within a week."
               "Currency reserves grew by nine percent."]]
      (mold/remember m t {:entities ["taiwan" "united states"]
                          :source   {:space "space:filler"}}))
    m))

(deftest a-fact-that-shares-a-subject-is-reachable-without-sharing-a-lineage
  (let [m (remember-all! (mem/file-memory (tmpfile)))
        q "inflation orbit taiwan"
        r (set (facts-of (mold/recall m q {:k 5 :filter {:sources  #{"space:sky"}
                                                         :entities #{"tsmc"}}})))]
    (is (contains? r taiwan)
        "the semis fact is in no lineage of space:sky and answers anyway — it carries tsmc")
    (is (not (contains? r serbia))
        "and the fact that shares neither the lineage nor the subject stays out")
    (is (contains? r neptune)
        "the union does not REPLACE lineage: space:sky's own fact carries no tsmc and is still here")
    (is (= #{taiwan neptune} r)
        "and those two are the whole answer — the union widens the scope, it does not remove it")

    (testing "an empty :sources with no :entities is still an empty result"
      (is (= [] (mold/recall m q {:k 5 :filter {:sources #{}}}))
          "the scope owns nothing, and the whole memory is not the answer to that"))

    (testing "an empty :sources beside an :entities set is that entities set"
      (is (= [taiwan] (facts-of (mold/recall m q {:k 5 :filter {:sources  #{}
                                                                :entities #{"tsmc"}}})))
          "a caller whose lineage is empty can still ask about a subject"))

    (testing ":entities with no :sources key at all is a scope in its own right"
      (is (= [taiwan] (facts-of (mold/recall m q {:k 5 :filter {:entities #{"tsmc"}}})))
          "the subject alone narrows — this is the shape an entity page asks in, and it is not no-filter"))

    (testing "the caller's names are normalized, not taken on trust"
      (is (= [taiwan] (facts-of (mold/recall m q {:k 5 :filter {:entities #{"TSMC"}}})))
          (str "`GET /api/entity?name=TSMC` must not answer with an empty page. An "
               "un-normalized name would simply never match and the scope would look "
               "like a corpus with no such facts in it")))

    (testing ":subjects scores, :filter {:entities} scopes — and the difference is silent"
      (is (= [] (mold/recall m "tsmc" {:k 5 :subjects #{"tsmc"}
                                       :filter   {:sources #{"space:sky"}}}))
          (str "the semis fact carries tsmc and is named as a subject, and it still does not "
               "answer: a subject raises a fact that already passed the filter, it never "
               "lets one past it"))
      (is (= [taiwan] (facts-of (mold/recall m "tsmc" {:k 5 :filter {:sources  #{"space:sky"}
                                                                     :entities #{"tsmc"}}})))
          "the same name in :filter is what makes it eligible — a caller reaching out of its lineage needs both"))))

(deftest a-multi-word-subject-scores-where-a-query-token-never-could
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m mendev {:entities ["wrath of the righteous"]
                             :source   {:obj "doc:wotr" :space "space:games"}})
    (is (= [] (facts-of (mold/recall m "what about the ending" {:k 5})))
        "not one word of the query is in the fact, and `wrath of the righteous` is not a token")
    (let [r (mold/recall m "what about the ending" {:k 5 :subjects #{"wrath of the righteous"}})]
      (is (= [mendev] (facts-of r))
          "named as a subject it scores — this is the half of the scorer that finds a fact by what it is about")
      ;; `(:score … 0)` rather than a bare lookup: when this regresses the hit is
      ;; absent, and a missing fact should read as a score of zero rather than
      ;; throwing an NPE over the assertion that was about to say so.
      (is (pos? (:score (first r) 0))))
    (is (= [mendev] (facts-of (mold/recall m "what about the ending"
                                           {:k 5 :subjects #{"  Wrath of the   Righteous "}})))
        "and the subject set is normalized on the way in, so a caller's casing cannot make it miss")))

(deftest a-blank-subject-is-not-a-subject-and-matches-nothing
  ;; Task 3 builds the subject set as "entities detected in the prompt ∪ entities
  ;; of facts already in this lineage". `ent/detect` cannot emit a blank, but the
  ;; lineage half is raw `:entities` off facts and those come from a model — so a
  ;; blank subject is a thing that will be asked for. It must match nothing: a
  ;; blank that matched would match every fact the distiller left junk on.
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m neptune {:entities ["neptune" nil ""]
                              :source   {:obj "doc:neptune" :space "space:sky"}})
    (doseq [s [#{nil} #{""} #{"   "} #{nil "" "  "}]]
      (is (= [] (mold/recall m "unrelated words entirely" {:k 5 :subjects s}))
          (str "a blank subject names nothing, so it raises nothing: " (pr-str s))))
    (is (= [neptune] (facts-of (mold/recall m "unrelated words entirely"
                                            {:k 5 :subjects #{"neptune" nil ""}})))
        "and a real name beside the blanks still works — the blanks are dropped, not the set")))

(deftest a-fact-s-own-names-are-normalized-the-way-every-other-entity-name-is
  ;; The distiller writes `:entities` and nothing ever rewrites them, so a padded
  ;; or oddly-cased name is what is actually on disk. Both new paths look a fact's
  ;; names up against normalized ones — the filter's set, and the df map's keys —
  ;; and a name keyed one way and compared the other misses in silence: the fact
  ;; simply never scores, and a weight of 1.0 quietly calls it the rarest name
  ;; there is.
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Veldhoven ships scanners." {:entities ["  ASML  "]
                                                  :source   {:space "space:euv"}})
    (is (= ["Veldhoven ships scanners."] (facts-of (mold/recall m "asml" {:k 5})))
        "the scorer matches the query token against the normalized name, not the stored one")
    (is (= ["Veldhoven ships scanners."]
           (facts-of (mold/recall m "asml" {:k 5 :filter {:sources #{} :entities #{"asml"}}})))
        "and so does the scope filter — otherwise a subject union silently skips its own facts")))

(deftest a-blank-entity-name-is-not-the-rarest-name-there-is
  ;; `ent/index` drops blanks, so `df-map` never keys one — and an unfiltered
  ;; blank would therefore take the `(get dfm e 1)` default and be weighed 1.0,
  ;; a LARGER weight than any real name. The distiller writes `:entities`
  ;; straight off a model's JSON, so `""` and `nil` are values that arrive here.
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m taiwan {:entities ["tsmc" "" nil "   "]
                             :source   {:obj "find:semis-1" :space "space:semis"}})
    (let [r (mold/recall m "tsmc" {:k 5})]
      (is (= [taiwan] (facts-of r)))
      (is (< (Math/abs (- 0.5 (:score (first r)))) 1e-4)
          (str "0.6 × 1/6 for the word plus the full 0.4 for the one name this fact "
               "actually has — exactly what `[\"tsmc\"]` alone scores. Left in, the three "
               "blanks normalize to one \"\" that halves the entity half and makes this 0.3, "
               "and at the corpus's real df for tsmc it would be 0.09 against 1.0: "
               (:score (first r)))))))

(deftest a-name-everything-carries-is-worth-less-than-a-name-two-facts-carry
  (let [m (ubiquity-memory)]
    (is (= 7 (count (mold/all-facts m)))
        "seven distinct facts — nothing was reinforced into anything else")
    (is (= [euv rapidus] (facts-of (mold/recall m "asml" {:k 10})))
        (str "both match `asml` on the entity half and neither on the words. `euv` also names "
             "taiwan and united states, 6 facts each; `rapidus` also names rapidus, 1 fact. "
             "Counting names equally makes it 1-of-3 against 1-of-2 and puts rapidus FIRST; "
             "weighting each name by 1/df makes it 0.60 against 0.33. A rare name a fact does "
             "not match is evidence it is about something else; a ubiquitous one is not."))))

(deftest document-frequency-is-global-and-not-the-scope-s
  (let [m (ubiquity-memory)]
    (is (= [euv rapidus]
           (facts-of (mold/recall m "asml" {:k 10 :filter {:sources #{"space:euv" "space:rapidus"}}})))
        (str "the same two facts, now alone inside a scope. Counted over the CANDIDATES, "
             "taiwan and united states occur once each in here and look as rare as rapidus — "
             "the ordering inverts and `united states` is an anchor again inside the one "
             "notebook that talks about it. df is counted over every live fact."))))

(deftest an-ordinary-query-scores-exactly-what-it-scored-before
  (let [m (remember-all! (mem/file-memory (tmpfile)))]
    (let [r (mold/recall m "inflation orbit taiwan" {:k 5})]
      (is (= [taiwan neptune serbia] (facts-of r))
          "one word of the query in each fact, and the ranking is the jaccard of each against it")
      (is (< (Math/abs (- 0.075 (:score (first r)))) 1e-4)
          (str "0.6 × jaccard(3 query tokens, 6 fact tokens sharing one) = 0.6 × 1/8, "
               "entity half 0 — the scorer's own arithmetic: " (:score (first r)))))
    (let [r (mold/recall m "tsmc" {:k 5})]
      (is (= [taiwan] (facts-of r)))
      (is (< (Math/abs (- 0.5 (:score (first r)))) 1e-4)
          (str "0.6 × 1/6 for the word plus 0.4 × 1 for the only entity there is, and with every "
               "name equally frequent the weighted entity half is the plain fraction it replaced: "
               (:score (first r)))))))

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

;; ---- ties: the lexical path's ordering, which used to be a hash order ----

(defn- tied-memory
  "`n` facts that score EXACTLY equal against the query `\"qq\"`, in ids
   `mem-1 … mem-n`.

   Equal to the last bit, not merely close, and every term is arranged for it:

   - one entity each, the same one, so the inverse-frequency entity half is
     `w/w` = 1.0 for all of them whatever `n` is;
   - texts sharing no token with each other or with the query, so the keyword
     half is 0 for all of them — and so `remember`'s 0.6-jaccard reinforcement
     never fires and there really are `n` facts;
   - one stamped `:ts`, because `weight` multiplies by `decay(ts, now)` and two
     facts written a millisecond apart do NOT tie. A distiller pass writing a
     dozen facts inside one millisecond is the live shape of this; the stamp
     only makes it reproducible.

   What was left was the sort. `sort-by` is stable, so with no tiebreaker these
   came back in the order of `(vals @!facts)` — the fact map's hash order,
   measured as `qq10 qq4 qq3 qq9 qq5 qq8 qq0 qq11 qq7 qq1 qq6 qq2`. Deterministic
   and meaningless."
  [n]
  (let [m (mem/file-memory (tmpfile))]
    (doseq [i (range n)]
      (mold/remember m (str "qq" i " rr" i " ss" i " tt" i)
                     {:entities ["qq"] :source {:obj "doc:q" :space "space:q"}}))
    (swap! (:!facts m) update-vals #(assoc % :ts 1700000000000))
    m))

(deftest facts-that-tie-exactly-come-back-by-id-and-not-in-a-hash-order
  (let [hits (mold/recall (tied-memory 12) "qq" {:k 20})]
    (is (= 12 (count hits)) "no fact was reinforced away — there are twelve to order")
    (is (= 1 (count (distinct (map :score hits))))
        "they tie EXACTLY: one distinct score, so nothing below is really testing the scorer")
    ;; `:id` sorts lexicographically and these ids are `mem-<n>`, so `mem-10`
    ;; precedes `mem-2`. That is NOT insertion order and this assertion is
    ;; written out in full rather than generated, so nobody can read it as one.
    ;; It matches `(sort-by (juxt (comp - :score) :id))` on the fused path
    ;; character for character, and one arbitrary-but-stated rule on both paths
    ;; beats two different ones.
    (is (= ["mem-1" "mem-10" "mem-11" "mem-12" "mem-2" "mem-3"
            "mem-4" "mem-5" "mem-6" "mem-7" "mem-8" "mem-9"]
           (mapv :id hits)))))

(deftest a-tiebreaker-never-outranks-a-score
  ;; The other half of the change, and the one that would go wrong quietly:
  ;; `(juxt (comp - :score) :id)` must still sort by score FIRST. `mem-2` here
  ;; says the query word and `mem-1` does not, so id order and score order are
  ;; opposites and only one of them can be the answer.
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "zz1 yy1 xx1 ww1"      {:entities ["qq"]})
    (mold/remember m "qq is said out loud"  {:entities ["qq"]})
    (let [hits (mold/recall m "qq" {:k 20})]
      (is (= ["mem-2" "mem-1"] (mapv :id hits))
          "score first, id only where the scores are equal")
      (is (apply > (map :score hits))))))
