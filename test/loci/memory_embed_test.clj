(ns loci.memory-embed-test
  "Facts that carry vectors — embedded after the write and never during it.

   Nothing here reaches a real embedder. The suite has to pass on a laptop with
   no model server and in CI with no network, because the point of this whole
   path is that loci records and recalls when the embedder is absent. The stub
   binds port 0 and is asked which port it got (picking a number and binding it
   later is a race), and `loci.embed`'s env/file seams are redefined so a
   LOCI_EMBED_ENDPOINT exported in the shell cannot aim these tests at
   somebody's actual box.

   Every memory here is a fresh file under java.io.tmpdir. Nothing derefs
   `loci.memory/memory`, which resolves to the user's real memory.edn."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.embed :as embed]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [org.httpkit.server :as server])
  (:import [java.net ServerSocket]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-embed-test-" (System/nanoTime) "/memory.edn"))

(defn- json-body [status data]
  {:status status :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- with-stub
  "Start `handler` on an ephemeral loopback port, call `(f port seen)`, stop the
   server. `seen` holds every request received, so a test can assert on what
   actually went over the wire — including that nothing did.

   The request body is a stream that is gone once the handler returns, so it is
   slurped here and handed to `handler` as a string under :body."
  [handler f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                (let [r (assoc (select-keys req [:uri :request-method :headers])
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

(defn- vec-for
  "A deterministic 1024-float vector for a text. The numbers mean nothing — no
   test here asserts anything about similarity, only about what is stored,
   persisted and re-requested."
  [text]
  (let [h (/ (double (mod (Math/abs (hash text)) 97)) 100.0)]
    (vec (repeat 1024 h))))

(defn- embed-handler
  "An OpenAI-shaped embeddings stub. It names a *different* model in the
   response than the one asked for, the way llama.cpp answers with the loaded
   file's path — a fact stamped with that would never match the configured name
   again and would be re-embedded on every pass forever."
  [req]
  (let [input (:input (json/read-str (:body req) :key-fn keyword))]
    (json-body 200 {:model "/models/qwen3-embedding-0.6b-q8_0.gguf"
                    :data  (vec (map-indexed (fn [i t] {:index i :embedding (vec-for t)}) input))})))

(defn- wait-for
  "Poll `pred` until truthy or `ms` elapses. Returns what it saw."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(defn- lines-in [path] (count (str/split-lines (str/trim (slurp path)))))

(defn- fact-by-id [m id] (first (filter #(= id (:id %)) (mem/all-facts m))))

;; ---- remember does not wait for anything ----

(deftest remember-records-the-fact-with-the-embedder-at-a-dead-port
  (let [m        (mem/file-memory (tmpfile))
        endpoint {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" (a-closed-port) "/v1/embeddings")}
        t0       (System/nanoTime)
        r        (with-env endpoint
                   #(mold/remember m "TSMC concentrates advanced nodes in Taiwan." {:entities ["tsmc"]}))
        ms       (/ (- (System/nanoTime) t0) 1e6)]
    (is (= :ok r) "a fact is recorded whether or not an embedder exists")
    (is (= 1 (count (mem/all-facts m))))
    (is (nil? (:vec (first (mem/all-facts m)))) "recorded, not embedded")
    ;; Deliberately loose: this is not a performance claim, it is "no network
    ;; round trip happened on this path". A file append is sub-millisecond, so
    ;; 100 ms leaves room for a cold JIT and a loaded machine while leaving none
    ;; for a request.
    (is (< ms 100) (str "remember took " ms " ms"))))

(deftest remember-does-not-wait-for-a-slow-embedder
  ;; The dead-port case above would pass even if remember *did* await its
  ;; embedding — connection refused on loopback returns in about a millisecond.
  ;; This is the one that bites: the stub takes 750 ms to answer, so a remember
  ;; that waited for it could not possibly return inside the bound.
  (with-stub (fn [req] (Thread/sleep 750) (embed-handler req))
    (fn [port seen]
      (let [m  (mem/file-memory (tmpfile))
            t0 (System/nanoTime)
            r  (with-env (embedding-at port)
                 #(mold/remember m "Neptune takes 165 years to orbit the Sun." {:entities ["neptune"]}))
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (is (= :ok r))
        (is (< ms 200) (str "remember took " ms " ms against an embedder that answers in 750"))
        (is (= [] @seen) "and no request was made at all — the write path does not know the embedder exists")))))

;; ---- what a pass stores ----

(deftest a-pass-gives-each-fact-a-vector-with-the-model-and-dimension
  (with-stub embed-handler
    (fn [port seen]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "TSMC concentrates advanced nodes in Taiwan." {:entities ["tsmc"]})
            (mold/remember m "Neptune takes 165 years to orbit the Sun." {:entities ["neptune"]})
            (let [r (mem/embed-pending! m)]
              (is (= 2 (:embedded r)))
              (is (= 0 (:pending r)) "nothing awaiting once the pass succeeded"))
            (is (= 1 (count @seen)) "both facts in one batch")
            (doseq [f (mem/all-facts m)]
              (is (= 1024 (count (:vec f))))
              (is (every? double? (:vec f)))
              (is (= 1024 (:dim f)))
              (is (= "embed-qwen3-0.6b" (:model f))
                  "the configured model name, not the path the server reported — a fact stamped with the server's own string would never match the configured name again")))))))

  (with-stub embed-handler
    (fn [port seen]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (is (= 0 (:embedded (mem/embed-pending! m))))
            (is (= [] @seen) "an empty memory asks the embedder nothing — this runs on a timer")))))))

(deftest with-no-embedder-configured-a-pass-is-off-and-asks-for-nothing
  (with-stub embed-handler
    (fn [_port seen]
      (let [m (mem/file-memory (tmpfile))]
        (mold/remember m "Kepler's Third Law relates period and semi-major axis." {})
        (is (= {:off true} (with-env {} #(mem/embed-pending! m)))
            "unconfigured is not an error — recall is lexical, exactly as it is today")
        (is (= [] @seen) "and nothing goes to a baked-in default")
        (is (= 1 (count (mem/pending-facts m))) "still counted as awaiting rather than pretended embedded")))))

;; ---- resumability ----

(deftest a-failed-embedding-leaves-the-fact-pending-for-the-next-pass
  (let [fail? (atom true)]
    (with-stub (fn [req] (if @fail? {:status 500 :body "model still loading"} (embed-handler req)))
      (fn [port _]
        (let [m (mem/file-memory (tmpfile))]
          (with-env (embedding-at port)
            (fn []
              (mold/remember m "Low-income countries cluster near the equator." {:entities ["equator"]})
              ;; the 500 is expected here; captured so a passing suite does not
              ;; print a line that reads like a failure
              (let [r (binding [*err* (java.io.StringWriter.)] (mem/embed-pending! m))]
                (is (string? (:error r)) "the failure is a value, not a throw")
                (is (= 0 (:embedded r)))
                (is (= 1 (:pending r))))
              (let [f (first (mem/all-facts m))]
                (is (nil? (:vec f)) "nothing half-written")
                (is (nil? (:model f)))
                (is (nil? (:dim f))))
              ;; Nothing marked the fact as tried — which is the whole of
              ;; resumability. A worker killed mid-sweep loses only its batch.
              (reset! fail? false)
              (let [r (mem/embed-pending! m)]
                (is (= 1 (:embedded r)))
                (is (= 0 (:pending r))))
              (is (= 1024 (count (:vec (first (mem/all-facts m)))))))))))))

(deftest a-response-with-mixed-dimensions-embeds-nothing-and-logs-once
  (with-stub (fn [_] (json-body 200 {:model "m"
                                     :data  [{:index 0 :embedding (vec (repeat 1024 0.1))}
                                             {:index 1 :embedding (vec (repeat 768 0.1))}]}))
    (fn [port _]
      (let [m   (mem/file-memory (tmpfile))
            err (java.io.StringWriter.)]
        (reset! @#'mem/!warned #{})
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "Mercury is the fastest planet." {})
            (mold/remember m "Serbia's inflation spiked to about 12% in 2022." {})
            (binding [*err* err]
              (let [r (mem/embed-pending! m)]
                (is (re-find #"(?i)dimension" (:error r)) "the dimension seen is in the message")
                (is (= 0 (:embedded r)))
                (is (= 2 (:pending r))))
              (mem/embed-pending! m))
            (is (every? #(nil? (:vec %)) (mem/all-facts m))
                "a ragged batch is not partially usable — one of those vectors is not what it claims")
            (is (= 1 (count (re-seq #"loci\.memory:" (str err))))
                "logged once, not once per pass — a backfill on a timer would otherwise repeat it forever")))))))

;; ---- selection: only what is pending, and only for the current model ----

(deftest a-second-pass-under-the-same-model-asks-for-nothing
  (with-stub embed-handler
    (fn [port seen]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (assoc (embedding-at port) "LOCI_EMBED_MODEL" "model-a")
          (fn []
            (mold/remember m "TSMC concentrates advanced nodes in Taiwan." {})
            (is (= 1 (:embedded (mem/embed-pending! m))))
            (is (= 1 (count @seen)))
            (is (= [] (mem/pending-facts m)) "an embedded fact is not awaiting anything")
            (let [r (mem/embed-pending! m)]
              (is (= 0 (:embedded r)) "already embedded for this model")
              (is (= 0 (:pending r))))
            (is (= 1 (count @seen))
                "no second request — otherwise every pass re-embeds the whole memory forever")))))))

(deftest changing-the-model-makes-every-fact-pending-again
  (with-stub embed-handler
    (fn [port seen]
      (let [m (mem/file-memory (tmpfile))
            at (embedding-at port)]
        (with-env (assoc at "LOCI_EMBED_MODEL" "model-a")
          (fn []
            (mold/remember m "TSMC concentrates advanced nodes in Taiwan." {})
            (mem/embed-pending! m)
            (is (= "model-a" (:model (first (mem/all-facts m)))))))
        (with-env (assoc at "LOCI_EMBED_MODEL" "model-b")
          (fn []
            (is (= 1 (count (mem/pending-facts m)))
                "a vector from another model is worse than none — cosine across two spaces still returns a number that sorts")
            (is (= 1 (:embedded (mem/embed-pending! m))))
            (is (= 2 (count @seen)))
            (is (= "model-b" (:model (first (mem/all-facts m)))))))))))

;; ---- persistence: one append of the same id ----

(deftest the-vector-survives-a-reload-because-it-is-one-more-append
  (with-stub embed-handler
    (fn [port _]
      (let [path (tmpfile)
            m    (mem/file-memory path)]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "TSMC concentrates advanced nodes in Taiwan." {:entities ["tsmc"]})
            (is (= 1 (:embedded (mem/embed-pending! m))))))
        (is (= 2 (lines-in path)) "the fact, then the fact again with its vector — an append, not a rewrite")
        (let [reloaded (mem/file-memory path)
              f        (first (mem/all-facts reloaded))]
          (is (= 1 (count (mem/all-facts reloaded))) "last-wins by :id — no new store and no migration")
          (is (= 1024 (count (:vec f))) "the vector is still there after a reload")
          (is (= "embed-qwen3-0.6b" (:model f)))
          (is (= 1024 (:dim f)))
          (is (= ["tsmc"] (:entities f)) "and the rest of the fact came with it — the append carries the whole record")
          (is (= 1 (:strength f))))))))

(deftest reinforcing-with-new-wording-drops-the-vector-it-no-longer-describes
  (with-stub embed-handler
    (fn [port _]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "TSMC produces most leading-edge logic chips." {:entities ["tsmc"]})
            (mem/embed-pending! m)
            ;; the identical sentence again: the vector still describes it
            (mold/remember m "TSMC produces most leading-edge logic chips." {:entities ["taiwan"]})
            (is (= 2 (:strength (first (mem/all-facts m)))))
            (is (= 1024 (count (:vec (first (mem/all-facts m)))))
                "same text, same meaning — no reason to re-embed")
            (is (= [] (mem/pending-facts m)))
            ;; reworded: the stored vector is now for a sentence that is gone
            (mold/remember m "TSMC produces most of the leading-edge logic chips today." {})
            (is (nil? (:vec (first (mem/all-facts m)))))
            (is (= 1 (count (mem/pending-facts m))) "queued rather than left pointing at text no longer stored")
            (is (= 1 (:embedded (mem/embed-pending! m))))))))))

;; ---- all-facts on the protocol ----

(deftest all-facts-is-a-protocol-function-and-the-old-name-still-works
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Mercury is the fastest planet." {})
    (Thread/sleep 5)
    (mold/remember m "Serbia's inflation spiked in 2022." {})
    (is (= (mold/all-facts m) (mem/all-facts m))
        "server.clj and demo.clj call the function; the protocol method is what a disk-backed store would implement")
    (is (= ["Serbia's inflation spiked in 2022." "Mercury is the fastest planet."]
           (mapv :fact (mold/all-facts m)))
        "newest first"))
  (let [n (mold/naive-recall)]
    (mold/remember n "a" {})
    (mold/remember n "b" {})
    (is (= ["b" "a"] (mapv :fact (mold/all-facts n)))
        "every implementation answers it, or the call is an AbstractMethodError the day someone uses the other one")))

;; ---- ids ----

(deftest ids-come-from-the-highest-ever-issued-not-from-the-count
  (let [m  (mem/file-memory (tmpfile))
        at (System/currentTimeMillis)
        f  (fn [id text] [id {:id id :fact text :entities [] :ts at :strength 1}])]
    (reset! (:!facts m) (into {} [(f "mem-1" "TSMC concentrates advanced nodes in Taiwan")
                                  (f "mem-2" "Neptune takes 165 years to orbit the Sun")
                                  (f "mem-3" "Serbia's inflation spiked to about 12% in 2022")]))
    ;; the semantic merge in the design's §4 deletes the absorbed fact
    (swap! (:!facts m) dissoc "mem-2")
    (mold/remember m "Directed self-assembly patterns wafers below the lithographic limit" {})
    (is (= #{"mem-1" "mem-3" "mem-4"} (set (map :id (mem/all-facts m))))
        "one past the highest id ever issued — counting would mint mem-3 here")
    (is (= "Serbia's inflation spiked to about 12% in 2022" (:fact (fact-by-id m "mem-3")))
        "and the live fact holding that id was not overwritten by a different one")))

;; ---- the worker ----

(defn- embed-worker-threads []
  (filter #(= "loci-embed-worker" (.getName ^Thread %)) (keys (Thread/getAllStackTraces))))

(deftest loading-this-namespace-starts-no-worker
  (is (= [] (embed-worker-threads))
      "a namespace that spawns on require leaves a thread behind in every process that merely loaded it, this test run included"))

(deftest the-worker-is-started-explicitly-and-stops-when-told
  (with-stub embed-handler
    (fn [port _]
      (let [m (mem/file-memory (tmpfile))]
        (with-env (embedding-at port)
          (fn []
            (mold/remember m "Low-income countries cluster near the equator." {})
            (let [w (mem/start-embed-worker! m {:interval-ms 25})]
              (try
                (is (wait-for #(seq (:vec (first (mem/all-facts m)))) 3000)
                    "the worker picked the fact up, with nothing on the write path having waited for it")
                (finally
                  (is (true? (mem/stop-embed-worker! w)) "and it stops when told")))
              (is (not (.isAlive ^Thread (:thread w))))
              (is (= [] (embed-worker-threads))))))))))
