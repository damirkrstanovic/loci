(ns loci.memory-merge-test
  "Semantic merge: two facts that say the same thing become one, in the
   background, at a threshold that belongs to one embedding model.

   **Nothing here reaches an embedder except the one stub in the worker test,
   which binds an ephemeral loopback port.** The vectors in the fixtures are
   *constructed* rather than measured — every fact's vector is a unit vector in
   a plane, so the cosine between two of them is a number chosen in advance and
   the assertions are arithmetic rather than empirical. They are not evidence
   about any model; the real thresholds came from a real corpus (see
   `loci.calibrate` and the spec's §5.1.1).

   Every memory here is a fresh file under java.io.tmpdir. Nothing derefs
   `loci.memory/memory`, which resolves to the user's real memory.edn."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.embed :as embed]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [org.httpkit.server :as server]))

;; ---------------------------------------------------------------------------
;; the fixture: vectors whose cosines are chosen, not measured
;; ---------------------------------------------------------------------------

(def ^:private dim 8)

(defn- unit
  "A unit vector of `dim` carrying `weights` on the named axes, zero elsewhere."
  [weights]
  (mapv #(double (get weights % 0.0)) (range dim)))

(def ^:private v-base
  "The axis every deliberately-similar fixture vector is measured against."
  (unit {0 1.0}))

(defn- v-at
  "A unit vector whose cosine with `v-base` is exactly `c`."
  [c]
  (unit {0 c, 1 (Math/sqrt (- 1.0 (* (double c) (double c))))}))

(def ^:private v-orthogonal
  "Cosine 0.0 with `v-base` and with every `v-at` — a fact about something else."
  (unit {2 1.0}))

(defn- rec
  "One stored fact, as the log holds it. `:ts` is given rather than taken from
   the clock, because which fact is older is the thing several of these tests
   are about."
  [{:keys [id text ts strength entities source vec model]}]
  {:id       id
   :fact     text
   :entities (or entities [])
   :source   source
   :ts       ts
   :strength (or strength 1)
   :vec      vec
   :model    (or model "embed-qwen3-0.6b")
   :dim      (count vec)})

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-merge-test-" (System/nanoTime) "/memory.edn"))

(defn- seed!
  "Write `recs` to a fresh memory file in the format the append-only log already
   uses — one EDN map per line — and open it. Returns `[path memory]`.

   Written as a file rather than poked into the atom so the persistence tests
   have something to reload, and so nothing here can accidentally be pointed at
   a memory that is not a throwaway."
  [recs]
  (let [path (tmpfile)]
    (io/make-parents (io/file path))
    (spit path (str (str/join "\n" (map pr-str recs)) "\n"))
    [path (mem/file-memory path)]))

(defn- with-env
  "Run `f` with loci.embed's resolvers seeing exactly `env` and no files, so a
   LOCI_EMBED_MODEL exported in the shell cannot change what these tests mean."
  [env f]
  (with-redefs [embed/env #(get env %) embed/from-file (constantly nil)]
    (f)))

(defn- model-is [m] {"LOCI_EMBED_MODEL" m})

(defn- by-id [m id] (first (filter #(= id (:id %)) (mem/all-facts m))))

(defn- lines-in [path] (count (str/split-lines (str/trim (slurp path)))))

(defn- quiet
  "Run `f` with stderr captured. Merge warns once when it refuses, and a passing
   suite should not print a line that reads like a failure."
  [f]
  (binding [*err* (java.io.StringWriter.)] (f)))

;; ---------------------------------------------------------------------------
;; the table
;; ---------------------------------------------------------------------------

(deftest the-threshold-table-is-per-model
  (is (= 0.85 (mem/merge-threshold "embed-qwen3-0.6b"))
      "measured 2026-08-07 on the real corpus")
  (is (= 0.88 (mem/merge-threshold "embed-qwen3-4b"))
      "the same corpus, a different model, a different number — the tail moved")
  (is (nil? (mem/merge-threshold "embed-bge-m3"))
      "uncalibrated: no threshold, not the nearest one")
  (is (nil? (mem/merge-threshold "something-nobody-has-measured"))))

(deftest an-uncalibrated-model-merges-nothing-and-says-so
  (let [[path m] (seed! [(rec {:id "mem-1" :text "Kepler's Third Law relates period and axis."
                               :ts 1000 :vec v-base :model "embed-bge-m3"})
                         (rec {:id "mem-2" :text "The orbital period grows with distance."
                               :ts 2000 :vec (v-at 0.95) :model "embed-bge-m3"})])
        before   (mem/all-facts m)
        before-n (lines-in path)
        r        (quiet #(with-env (model-is "embed-bge-m3") (fn [] (mem/merge-similar! m))))]
    (is (= 0 (:merged r)) "0.95 is above both calibrated thresholds and is still not merged")
    (is (string? (:refused r)) "and it says why rather than returning a bare zero")
    (is (str/includes? (:refused r) "embed-bge-m3") "naming the model it has no number for")
    (is (nil? (:threshold r)) "there is no threshold to report")
    ;; the whole point: an unmerged corpus is redundant, a wrongly merged one has
    ;; lost something, so refusing must leave the corpus exactly as it was
    (is (= before (mem/all-facts m)) "the corpus is untouched, fact for fact")
    (is (= before-n (lines-in path)) "and nothing was appended to the file")
    (is (every? #(nil? (:merged-from %)) (mem/all-facts m)))))

(deftest the-refusal-is-reported-once-not-once-per-pass
  (let [[_ m] (seed! [(rec {:id "mem-1" :text "a" :ts 1000 :vec v-base :model "embed-bge-m3"})
                      (rec {:id "mem-2" :text "b" :ts 2000 :vec (v-at 0.95) :model "embed-bge-m3"})])
        err   (java.io.StringWriter.)]
    (reset! @#'mem/!warned #{})
    (binding [*err* err]
      (with-env (model-is "embed-bge-m3")
        (fn [] (dotimes [_ 3] (mem/merge-similar! m)))))
    (is (= 1 (count (re-seq #"loci\.memory:" (str err))))
        "the worker runs this every 15 s — one line, not four an hour")
    (is (str/includes? (str err) "embed-bge-m3"))))

;; ---------------------------------------------------------------------------
;; a merge
;; ---------------------------------------------------------------------------

(defn- two-near-facts
  "Two facts scoring `c` against each other: mem-1 older, mem-2 newer."
  [c]
  (seed! [(rec {:id "mem-1" :text "TSMC produces over 90% of sub-7nm chips, all in Taiwan."
                :ts 1000 :strength 3 :entities ["tsmc" "taiwan"] :source {:obj "obj-1"}
                :vec v-base})
          (rec {:id "mem-2" :text "More than 90% of advanced chips are made by TSMC in Taiwan."
                :ts 2000 :strength 2 :entities ["taiwan" "chips"] :source {:obj "obj-9"}
                :vec (v-at c)})]))

(defn- merge-once! [m]
  (quiet #(with-env (model-is "embed-qwen3-0.6b") (fn [] (mem/merge-similar! m)))))

(deftest two-facts-above-the-threshold-become-one-and-the-older-survives
  (let [[_ m] (two-near-facts 0.93)
        r     (merge-once! m)
        facts (mem/all-facts m)
        s     (first facts)]
    (is (= 1 (:merged r)))
    (is (= 0.85 (:threshold r)) "the number that belongs to this model")
    (is (= 1 (count facts)) "two rewordings of one fact are one fact")
    (is (= "mem-1" (:id s)) "the older survives — it is the one the provenance was recorded against")
    (is (= "TSMC produces over 90% of sub-7nm chips, all in Taiwan." (:fact s))
        "and it keeps its own wording, so its vector still describes its text")
    (is (= {:obj "obj-1"} (:source s)) "the older fact's source, not the newer's")
    (is (= 5 (:strength s)) "strength sums — this is loci's only \"it keeps coming up\" signal")
    (is (= 2000 (:ts s)) ":ts takes the newer, because the fact was just seen again")
    (is (= ["tsmc" "taiwan" "chips"] (:entities s)) "entities union, survivor's first")
    (is (= 8 (count (:vec s))) "the survivor's vector is untouched — its text did not change")))

(deftest the-absorbed-fact-is-gone-from-all-facts-and-recoverable-from-merged-from
  (let [[_ m] (two-near-facts 0.93)
        _     (merge-once! m)
        s     (first (mem/all-facts m))
        gone  (first (:merged-from s))]
    (is (nil? (by-id m "mem-2")) "the absorbed fact is no longer a fact")
    (is (= 1 (count (:merged-from s))))
    (is (= "mem-2" (:id gone)) "its id is on the survivor")
    (is (= "More than 90% of advanced chips are made by TSMC in Taiwan." (:fact gone))
        "and its text — an id alone would make the merge auditable but not reversible")
    (is (= 2 (:strength gone)) "with enough of the record to put it back")
    (is (= {:obj "obj-9"} (:source gone)))
    (is (nil? (:vec gone)) "minus the vector, which is 1024 floats and re-derivable from the text")))

(deftest two-facts-below-the-threshold-are-left-alone
  (let [[_ m] (two-near-facts 0.84)
        r     (merge-once! m)]
    (is (= 0 (:merged r)) "0.84 is under 0.85 — the band just below the cut is where the misses are")
    (is (= 2 (count (mem/all-facts m))))
    (is (every? #(nil? (:merged-from %)) (mem/all-facts m)))
    (is (= [3 2] (mapv :strength (sort-by :id (mem/all-facts m)))) "nothing reinforced either")))

(deftest facts-embedded-under-different-models-are-never-compared
  (let [[_ m] (seed! [(rec {:id "mem-1" :text "Neptune takes 165 years to orbit the Sun."
                            :ts 1000 :vec v-base :model "embed-qwen3-0.6b"})
                      ;; the same vector, so cosine is 1.0 — only the model stamp
                      ;; stands between these two and a merge
                      (rec {:id "mem-2" :text "Neptune's orbit takes about 165 years."
                            :ts 2000 :vec v-base :model "embed-qwen3-4b"})])
        r     (merge-once! m)]
    (is (= 0 (:merged r))
        "cosine 1.0 across two embedding spaces is not a similarity, it is a coincidence of axes")
    (is (= 2 (count (mem/all-facts m))))
    (is (= 1 (:considered r)) "only the facts stamped with the configured model were in the pass")))

(deftest a-fact-with-no-vector-yet-is-not-merged
  (let [[_ m] (seed! [(rec {:id "mem-1" :text "Mercury is the fastest planet." :ts 1000 :vec v-base})
                      {:id "mem-2" :fact "Mercury orbits fastest of the planets."
                       :entities [] :ts 2000 :strength 1}])
        r     (merge-once! m)]
    (is (= 0 (:merged r)) "nothing to compare — it stays pending and is merged on a later pass")
    (is (= 2 (count (mem/all-facts m))))))

(deftest merging-twice-changes-nothing-the-second-time
  (let [[path m] (two-near-facts 0.93)
        _        (merge-once! m)
        after    (mem/all-facts m)
        n        (lines-in path)
        r        (merge-once! m)]
    (is (= 0 (:merged r)) "idempotent — the worker runs this every 15 s forever")
    (is (= after (mem/all-facts m)) "not one field moved")
    (is (= n (lines-in path)) "and not one line was appended")))

(deftest a-merge-survives-a-reload-because-it-is-two-more-appends
  (let [[path m] (two-near-facts 0.93)
        before-n (lines-in path)
        _        (merge-once! m)]
    (is (= (+ 2 before-n) (lines-in path))
        "the survivor again, and the absorbed id again as a tombstone — appends, not a rewrite")
    (let [reloaded (mem/file-memory path)
          s        (first (mem/all-facts reloaded))]
      (is (= 1 (count (mem/all-facts reloaded))) "the absorbed fact does not come back on reload")
      (is (= "mem-1" (:id s)))
      (is (= 5 (:strength s)))
      (is (= 2000 (:ts s)))
      (is (= ["mem-2"] (mapv :id (:merged-from s))) "the audit record is in the file, not only in RAM")
      (is (= "More than 90% of advanced chips are made by TSMC in Taiwan."
             (:fact (first (:merged-from s))))))))

(deftest an-absorbed-id-is-never-reissued
  ;; next-id is one past the highest id ever issued, and a merge deletes a fact
  ;; — which is exactly the case its docstring warns about. If the tombstone
  ;; stopped counting, the next remember would mint mem-2 again and overwrite a
  ;; live record with a different fact under the same id.
  (let [[path m] (two-near-facts 0.93)]
    (merge-once! m)
    (mold/remember m "Directed self-assembly patterns wafers below the lithographic limit." {})
    (is (= #{"mem-1" "mem-3"} (set (map :id (mem/all-facts m)))))
    (is (= #{"mem-1" "mem-3"} (set (map :id (mem/all-facts (mem/file-memory path)))))
        "and the same after a reload")))

(deftest remembering-the-absorbed-text-again-records-it-and-does-not-revive-the-tombstone
  ;; remember's jaccard dedup scans the stored facts, and a tombstone still
  ;; carries the absorbed text — so a scan that did not skip tombstones would
  ;; find a perfect match and "reinforce" it. The updated record would still
  ;; carry :merged-into, so the newly remembered fact would land nowhere
  ;; visible: remembered, acknowledged with :ok, and absent from all-facts.
  (let [[_ m]  (two-near-facts 0.93)
        absorbed "More than 90% of advanced chips are made by TSMC in Taiwan."]
    (merge-once! m)
    (mold/remember m absorbed {})
    (is (= #{"mem-1" "mem-3"} (set (map :id (mem/all-facts m))))
        "the text is a live fact again, under a new id")
    (is (= absorbed (:fact (by-id m "mem-3"))))
    (is (nil? (by-id m "mem-2")) "and mem-2 stays absorbed")))

(deftest a-merged-away-fact-is-not-recalled-and-is-not-pending
  (let [[_ m] (two-near-facts 0.93)]
    (merge-once! m)
    (with-env (model-is "embed-qwen3-0.6b")
      (fn []
        (is (= ["mem-1"] (mapv :id (mold/recall m "TSMC Taiwan chips" {:k 6})))
            "recall answers from the survivor only")
        (is (= [] (mem/pending-facts m))
            "and the tombstone is not queued for an embedding of a fact that is gone")))))

;; ---------------------------------------------------------------------------
;; where it runs: the worker, after a pass — never on the write path
;; ---------------------------------------------------------------------------

(defn- json-body [status data]
  {:status status :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- with-stub
  "Start `handler` on an ephemeral loopback port, call `(f port seen)`, stop."
  [handler f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                (let [r (assoc (select-keys req [:uri :request-method]) :body (some-> (:body req) slurp))]
                  (swap! seen conj r)
                  (handler r)))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (server/server-port srv) seen)
         (finally (server/server-stop! srv {:timeout 100})))))

(defn- near-vectors-handler
  "An embeddings stub that answers with vectors 0.93 apart — above the 0.6b's
   calibrated 0.85, whatever the texts actually say."
  [req]
  (let [input (:input (json/read-str (:body req) :key-fn keyword))]
    (json-body 200 {:model "/models/qwen3-embedding-0.6b-q8_0.gguf"
                    :data  (vec (map-indexed (fn [i _] {:index i :embedding (if (zero? i) v-base (v-at 0.93))})
                                             input))})))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline) (Thread/sleep 10) (recur))))))

(deftest remember-does-not-merge
  ;; The write path is one file append and stays one file append. Merge is the
  ;; worker's job precisely because comparing a new fact against every embedded
  ;; one is O(n) cosines that a person clicking must never wait for.
  (let [[path m] (two-near-facts 0.93)
        before-n (lines-in path)
        t0       (System/nanoTime)
        _        (mold/remember m "Neptune takes 165 years to orbit the Sun." {})
        ms       (/ (- (System/nanoTime) t0) 1e6)]
    (is (= 3 (count (mem/all-facts m))) "the two near-duplicates are still two facts")
    (is (every? #(nil? (:merged-from %)) (mem/all-facts m)))
    (is (= (inc before-n) (lines-in path)) "one append, exactly as before")
    (is (< ms 100) (str "remember took " ms " ms"))))

(deftest the-worker-merges-after-a-successful-embed-pass
  (with-stub near-vectors-handler
    (fn [port _]
      (let [m (mem/file-memory (tmpfile))]
        (with-env {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" port "/v1/embeddings")}
          (fn []
            (mold/remember m "TSMC concentrates advanced nodes in Taiwan." {:entities ["tsmc"]})
            (Thread/sleep 5)
            (mold/remember m "Leading-edge fabrication is concentrated on one island." {:entities ["taiwan"]})
            (is (= 2 (count (mem/all-facts m))) "two facts, unmerged, until something embeds them")
            (let [w (mem/start-embed-worker! m {:interval-ms 25})]
              (try
                (is (wait-for #(= 1 (count (mem/all-facts m))) 3000)
                    "the worker embedded them and then merged them, with nothing on the write path waiting")
                (finally (mem/stop-embed-worker! w)))
              (let [s (first (mem/all-facts m))]
                (is (= "mem-1" (:id s)) "the older survived")
                (is (= 2 (:strength s)))
                (is (= ["mem-2"] (mapv :id (:merged-from s))))))))))))

(deftest a-failed-embed-pass-does-not-merge
  ;; Merge runs after a *successful* pass. A pass that failed leaves facts
  ;; unembedded, and a merge over half a corpus would merge on evidence that is
  ;; missing for the rest of it.
  (with-stub (fn [_] {:status 500 :body "model still loading"})
    (fn [port seen]
      (let [[_ m]    (two-near-facts 0.93)
            endpoint (str "http://127.0.0.1:" port "/v1/embeddings")]
        ;; warn-once! prints on the worker's own thread, where a `binding` of
        ;; *err* made here cannot reach it. Its message is deterministic given
        ;; the port, so it is marked as already reported: a passing suite should
        ;; not print a line that reads like a failure. If the message ever
        ;; changes the line comes back — it cannot make this test pass wrongly.
        (swap! @#'mem/!warned conj (str endpoint " returned HTTP 500"))
        (with-env {"LOCI_EMBED_ENDPOINT" endpoint}
          (fn []
            ;; one fact with no vector, so the pass has something to fail on
            (mold/remember m "Neptune takes 165 years to orbit the Sun." {})
            (let [w (mem/start-embed-worker! m {:interval-ms 25})]
              (try (wait-for #(<= 2 (count @seen)) 3000)
                   (finally (mem/stop-embed-worker! w))))))
        (is (= 3 (count (mem/all-facts m)))
            "the two near-duplicates were not merged while the embedder was down")
        (is (every? #(nil? (:merged-from %)) (mem/all-facts m)))))))
