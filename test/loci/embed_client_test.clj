(ns loci.embed-client-test
  "The embedding/reranking client, against a stub HTTP server started inside the
   test. Nothing here reaches a real embedder: the suite has to pass on a laptop
   with no model server and in CI with no network, because the whole point of
   this client is that loci works when the embedder is absent.

   Two seams make that possible. `embed/env` and `embed/from-file` are redefined
   so the resolvers see exactly the environment a test states — otherwise a
   LOCI_EMBED_ENDPOINT exported in the shell, or a .embed-endpoint left in the
   checkout, would silently aim these tests at somebody's actual box. And the
   stub binds port 0 and is asked which port it got, rather than picking a
   number: picking one and binding it later is a race, which is why
   test/browser/harness.mjs does the same for its JVM."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.embed :as embed]
            [org.httpkit.server :as server])
  (:import [java.net ServerSocket]))

;; Not a real token, and nothing here reads loci.env.
(def ^:private a-key "test-token-not-a-real-one")

(defn- json-body [status data]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str data)})

(defn- with-stub
  "Start `handler` on an ephemeral loopback port, call `(f port seen)`, stop the
   server. `seen` is an atom holding every request the stub received, so a test
   can assert on what actually went over the wire rather than on what the client
   was asked to send."
  [handler f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                ;; The body is a stream that is gone once the handler returns,
                ;; so it is slurped here, before anything else touches req.
                (swap! seen conj (assoc (select-keys req [:uri :request-method :headers])
                                        :body (some-> (:body req) slurp)))
                (handler req))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (server/server-port srv) seen)
         (finally (server/server-stop! srv {:timeout 100})))))

(defn- with-env
  "Run `f` with the resolvers seeing exactly `env` and no working-directory files."
  [env f]
  (with-redefs [embed/env #(get env %) embed/from-file (constantly nil)]
    (f)))

(defn- embedding-at [port]
  {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" port "/v1/embeddings")})

(defn- rerank-at [port]
  {"LOCI_RERANK_ENDPOINT" (str "http://127.0.0.1:" port "/v1/rerank")})

(defn- a-closed-port
  "A port with nothing listening on it: bind one, ask which it was, close it.
   Hard-coding a number instead would give a test that fails on whichever
   machine happens to be running something there."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- authorization
  "The Authorization header names present, under any casing. http-kit
   lower-cases what it received, but a test asserting on one exact spelling
   would pass while the header was going out under another."
  [headers]
  (filter #(= "authorization" (str/lower-case %)) (keys headers)))

(defn- close? [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1e-9))

;; ---- not configured is off, and off makes no request ----

(deftest embed-texts-with-no-endpoint-is-off-and-sends-nothing
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0]}]}))
    (fn [_port seen]
      (is (= {:off true} (with-env {} #(embed/embed-texts ["anything"])))
          "unconfigured is not an error — recall is lexical, exactly as it is today")
      (is (= [] @seen) "and no request goes anywhere, least of all to a baked-in default"))))

(deftest rerank-with-no-endpoint-is-off-and-sends-nothing
  (with-stub (fn [_] (json-body 200 {:results []}))
    (fn [_port seen]
      (is (= {:off true} (with-env {} #(embed/rerank "q" ["a" "b"])))
          "the caller proceeds unranked rather than failing")
      (is (= [] @seen)))))

(deftest embed-and-rerank-are-off-independently
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0]}]}))
    (fn [port _seen]
      (is (= {:off true} (with-env (embedding-at port) #(embed/rerank "q" ["a"])))
          "an embedder without a reranker means rerank is skipped, not guessed at the embed URL"))))

;; ---- input order: the bug that pairs a fact with another fact's meaning ----

(deftest vectors-come-back-in-input-order-however-the-server-shuffles-them
  (with-stub
    (fn [_] (json-body 200 {:model "embed-qwen3-0.6b"
                            ;; deliberately not in input order, which the API
                            ;; permits and which is why every entry carries :index
                            :data [{:index 2 :embedding [3.0 3.1 3.2]}
                                   {:index 0 :embedding [1.0 1.1 1.2]}
                                   {:index 1 :embedding [2.0 2.1 2.2]}]}))
    (fn [port seen]
      (let [r (with-env (embedding-at port) #(embed/embed-texts ["first" "second" "third"]))]
        (is (nil? (:error r)))
        (is (= [[1.0 1.1 1.2] [2.0 2.1 2.2] [3.0 3.1 3.2]] (:vectors r))
            "placed by :index. Pairing by array position neither throws nor looks wrong — it attaches one fact's meaning to another and stays that way")
        (is (= 3 (:dim r))))
      (let [sent (json/read-str (:body (first @seen)) :key-fn keyword)]
        (is (= ["first" "second" "third"] (:input sent)) "the texts go out in the caller's order")
        (is (= "embed-qwen3-0.6b" (:model sent)))))))

(deftest an-index-set-that-is-not-exactly-the-inputs-is-an-error-not-a-guess
  (let [bad (fn [data n]
              (with-stub (fn [_] (json-body 200 {:model "m" :data data}))
                (fn [port _]
                  (with-env (embedding-at port)
                    #(embed/embed-texts (vec (repeat n "text")))))))]
    (is (string? (:error (bad [{:embedding [1.0]} {:embedding [2.0]}] 2)))
        "no :index at all: falling back to array order here is exactly the silent mispairing")
    (is (string? (:error (bad [{:index 0 :embedding [1.0]} {:index 0 :embedding [2.0]}] 2)))
        "a duplicated index leaves one input with no vector")
    (is (string? (:error (bad [{:index 0 :embedding [1.0]}] 2)))
        "fewer vectors than texts")
    (is (string? (:error (bad [{:index 0 :embedding [1.0]} {:index 7 :embedding [2.0]}] 2)))
        "an index outside the batch")))

;; ---- what a good response reports ----

(deftest dim-is-the-length-of-the-vectors-actually-returned
  (let [v (vec (repeat 1024 0.03125))]
    (with-stub (fn [_] (json-body 200 {:model "embed-qwen3-0.6b" :data [{:index 0 :embedding v}]}))
      (fn [port _]
        (let [r (with-env (embedding-at port) #(embed/embed-texts ["one"]))]
          (is (= 1024 (:dim r)) "1024 is what embed-qwen3-0.6b returned on 2026-08-07, but it is read off the response, not assumed")
          (is (= [v] (:vectors r))))))))

(deftest the-model-reported-is-the-one-configured-not-the-one-the-server-names
  (with-stub
    (fn [_] (json-body 200 {:model "/models/qwen3-embedding-0.6b-q8_0.gguf"
                            :data [{:index 0 :embedding [1.0 2.0]}]}))
    (fn [port _]
      (let [r (with-env (embedding-at port) #(embed/embed-texts ["one"]))]
        (is (= "embed-qwen3-0.6b" (:model r))
            "a fact stamped with the server's own name for the model would never match the configured name again, and would be re-embedded on every backfill pass forever")))))

;; ---- the empty bearer ----

(deftest with-no-key-the-request-carries-no-authorization-header-at-all
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0]}]}))
    (fn [port seen]
      (with-env (embedding-at port) #(embed/embed-texts ["one"]))
      (is (= 1 (count @seen)))
      (is (= [] (authorization (:headers (first @seen))))
          "an empty `Bearer ` is worse than no header: a llama.cpp started without --api-key rejects it, and the 401 that comes back reads like a wrong key"))))

(deftest with-a-key-the-request-carries-exactly-one-bearer
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0]}]}))
    (fn [port seen]
      (with-env (assoc (embedding-at port) "LOCI_EMBED_API_KEY" a-key)
        #(embed/embed-texts ["one"]))
      (let [headers (:headers (first @seen))
            names   (authorization headers)]
        (is (= 1 (count names)))
        (is (= (str "Bearer " a-key) (get headers (first names))))))))

(deftest rerank-with-no-key-also-carries-no-authorization-header
  (with-stub (fn [_] (json-body 200 {:results [{:index 0 :relevance_score 0.1}]}))
    (fn [port seen]
      (with-env (rerank-at port) #(embed/rerank "q" ["a"]))
      (is (= [] (authorization (:headers (first @seen))))))))

;; ---- every failure is a value, never a throw ----

(deftest a-non-200-is-an-error-not-a-throw
  (with-stub (fn [_] {:status 500 :body "upstream on fire"})
    (fn [port _]
      (let [r (with-env (embedding-at port) #(embed/embed-texts ["one"]))]
        (is (string? (:error r)))
        (is (re-find #"500" (:error r)) "the status is in the message — a bare \"failed\" is unactionable")
        (is (nil? (:vectors r)))))))

(deftest a-refused-connection-is-an-error-not-a-throw
  (let [r (with-env (embedding-at (a-closed-port)) #(embed/embed-texts ["one"]))]
    (is (string? (:error r)))
    (is (nil? (:vectors r)))))

(deftest a-body-that-is-not-json-is-an-error-not-a-throw
  (with-stub (fn [_] {:status 200
                      :headers {"Content-Type" "application/json"}
                      :body "<html>proxy says no</html>"})
    (fn [port _]
      (let [r (with-env (embedding-at port) #(embed/embed-texts ["one"]))]
        (is (string? (:error r)))
        (is (nil? (:vectors r)))))))

(deftest a-json-body-with-no-data-array-is-an-error
  (with-stub (fn [_] (json-body 200 {:model "m" :object "list"}))
    (fn [port _]
      (is (string? (:error (with-env (embedding-at port) #(embed/embed-texts ["one"])))))))
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0}]}))
    (fn [port _]
      (is (string? (:error (with-env (embedding-at port) #(embed/embed-texts ["one"]))))
          "an entry with no :embedding is not a zero-dimensional vector"))))

(deftest a-dimension-that-disagrees-inside-one-response-is-an-error
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0 2.0 3.0]}
                                                       {:index 1 :embedding [1.0 2.0]}]}))
    (fn [port _]
      (let [r (with-env (embedding-at port) #(embed/embed-texts ["a" "b"]))]
        (is (string? (:error r)))
        (is (re-find #"(?i)dimension" (:error r)) "the message names what was seen, per the spec's failure table")
        (is (nil? (:vectors r)) "a ragged batch is not partially usable — one of these is not what it claims")))))

(deftest embedding-nothing-asks-for-nothing
  (with-stub (fn [_] (json-body 200 {:model "m" :data [{:index 0 :embedding [1.0]}]}))
    (fn [port seen]
      (let [r (with-env (embedding-at port) #(embed/embed-texts []))]
        (is (= [] (:vectors r)))
        (is (nil? (:error r)))
        (is (= [] @seen) "no round trip for a batch with nothing in it")))))

;; ---- rerank ----

(deftest rerank-sorts-best-first-and-a-negative-score-is-ordinary
  (with-stub (fn [_] (json-body 200 {:results [{:index 1 :relevance_score -11.04}
                                               {:index 0 :relevance_score 0.687}
                                               {:index 2 :relevance_score -3.0}]}))
    (fn [port seen]
      (let [r (with-env (rerank-at port) #(embed/rerank "which world is slowest" ["a" "b" "c"]))]
        (is (= [{:index 0 :score 0.687} {:index 2 :score -3.0} {:index 1 :score -11.04}] r)
            "raw logits, not probabilities: measured 0.687 down to -11.04 on 2026-08-07, so a negative score is low relevance and not a failure"))
      (let [sent (json/read-str (:body (first @seen)) :key-fn keyword)]
        (is (= "which world is slowest" (:query sent)))
        (is (= ["a" "b" "c"] (:documents sent)))
        (is (= "rerank-bge-m3" (:model sent)))))))

(deftest a-reranker-that-fails-is-an-error-not-a-throw
  (with-stub (fn [_] {:status 502 :body "bad gateway"})
    (fn [port _]
      (let [r (with-env (rerank-at port) #(embed/rerank "q" ["a"]))]
        (is (map? r))
        (is (string? (:error r))))))
  (with-stub (fn [_] (json-body 200 {:no "results"}))
    (fn [port _]
      (is (string? (:error (with-env (rerank-at port) #(embed/rerank "q" ["a"])))))))
  (with-stub (fn [_] (json-body 200 {:results [{:index 9 :relevance_score 0.5}]}))
    (fn [port _]
      (is (string? (:error (with-env (rerank-at port) #(embed/rerank "q" ["a"]))))
          "an index past the documents sent would blow up whoever indexes with it"))))

(deftest reranking-nothing-asks-for-nothing
  (with-stub (fn [_] (json-body 200 {:results [{:index 0 :relevance_score 0.1}]}))
    (fn [port seen]
      (is (= [] (with-env (rerank-at port) #(embed/rerank "q" []))))
      (is (= [] @seen)))))

;; ---- cosine ----

(deftest cosine-is-one-for-identical-zero-for-orthogonal-minus-one-for-opposite
  (is (close? 1.0 (embed/cosine [1.0 2.0 3.0] [1.0 2.0 3.0])))
  (is (close? 0.0 (embed/cosine [1.0 0.0] [0.0 1.0])))
  (is (close? -1.0 (embed/cosine [1.0 2.0 3.0] [-1.0 -2.0 -3.0]))))

(deftest cosine-divides-by-the-norms-so-a-dot-product-will-not-do
  ;; [3 4]·[4 3] = 24, |a| = |b| = 5, so cosine is 24/25 = 0.96. A bare dot
  ;; product answers 24.0. embed-qwen3-0.6b returns L2-normalised vectors
  ;; (measured norm 1.0 on 2026-08-07), so any test built from its output passes
  ;; either way — which is why this pair is hand-made and not normalised.
  (is (close? 0.96 (embed/cosine [3.0 4.0] [4.0 3.0])))
  (is (close? 0.96 (embed/cosine [3 4] [4 3])) "integers too — a hand-written vector is not always doubles")
  (is (close? 1.0 (embed/cosine [2.0 0.0] [7.0 0.0]))
      "same direction, very different magnitude: cosine ignores length, a dot product does not")
  (is (close? 0.5 (embed/cosine [1.0 0.0] [1.0 (Math/sqrt 3.0)]))
      "dot 1 over |b| = 2 — hand-computed, 60 degrees apart"))

(deftest cosine-of-a-zero-vector-is-zero-not-a-division-by-zero
  (is (= 0.0 (embed/cosine [0.0 0.0 0.0] [1.0 2.0 3.0])))
  (is (= 0.0 (embed/cosine [1.0 2.0 3.0] [0.0 0.0 0.0])))
  (is (= 0.0 (embed/cosine [0.0] [0.0])))
  (is (= 0.0 (embed/cosine [] []))))

(deftest cosine-of-different-length-vectors-is-zero
  (is (= 0.0 (embed/cosine [1.0 2.0] [1.0 2.0 3.0]))
      "not a dot product over the common prefix, which would be a number that sorts")
  (is (= 0.0 (embed/cosine [1.0 2.0 3.0] [1.0 2.0]))))
