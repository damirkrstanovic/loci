(ns loci.memory
  "Layer 3: the recall half of record/recall — a Clojure-native AI-memory
   engine behind the loci.mold/Recall protocol.

   A fact is {:id :fact :entities :source :ts :strength}, and once embedded it
   also carries {:vec :model :dim}. remember reinforces near-duplicates instead
   of duplicating; recall fuses keyword, entity, recency and strength signals.
   This is the agent's domain: revisable and decaying — deliberately NOT the
   substrate, and never touched by undo.

   Persistence is an append-only EDN-lines file; reinforcement appends the
   updated fact under the same :id and load is last-wins by :id. **Embedding is
   the same append**: the fact's own line, written again with :vec/:model/:dim
   added. That is why vectors need no new store, no schema and no migration.

   Embedding happens *after* the write and never during it. `remember` does not
   call the embedder at all, so a fact is recorded whether or not an embedder
   exists; `embed-pending!` sweeps afterwards and is resumable, because a fact
   that failed simply still has no :vec. Nothing here starts on require —
   `start-embed-worker!` is explicit and `stop-embed-worker!` undoes it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [loci.embed :as embed]
            [loci.mold :as mold]
            [loci.substrate :as sub])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

;; a trailing "." on a token is sentence punctuation, not part of the word —
;; the middle-dot form (e.g. "4.2", "22%") stays intact since [.%-] must be
;; followed by another alnum to extend the match; only a dangling separator
;; at the very end (a sentence-final period) is excluded.
(defn- tokens [s] (set (re-seq #"[a-z0-9]+(?:[.%-][a-z0-9]+)*%?" (str/lower-case (str s)))))

(defn- jaccard [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (/ (count (set/intersection a b)) (double (count (set/union a b))))))

(defn- decay
  "1.0 now, halved after ~30 days — recency as a multiplier, not a cutoff."
  [ts now]
  (/ 1.0 (+ 1.0 (/ (max 0 (- now ts)) (* 30.0 86400000.0)))))

(defn- score [qt now f]
  (let [kw  (jaccard qt (tokens (:fact f)))
        es  (set (map str/lower-case (:entities f)))
        ent (if (empty? es) 0.0
                (/ (count (set/intersection qt es)) (double (count es))))]
    (* (+ (* 0.6 kw) (* 0.4 ent))
       (decay (:ts f) now)
       (+ 1.0 (* 0.25 (dec (:strength f 1)))))))

(defn- append-line! [file rec]
  (io/make-parents (io/file file))
  (spit file (str (pr-str rec) "\n") :append true))

(defn- load-facts [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(def ^:private id-pattern #"mem-(\d+)")

(defn- next-id
  "One past the largest id ever issued — not (inc (count …)).

   Counting is collision-free only while nothing is ever deleted. Delete mem-2
   of three facts and the count is 2, so the next remember mints mem-3 and
   overwrites a *different, still-live* fact: same id, last line wins, the
   original text gone with no error anywhere. The semantic merge in the design's
   §4 deletes the absorbed fact, so this stops being hypothetical there.

   An id that is not mem-<n> contributes nothing to the maximum rather than
   throwing; there are none today, and a hand-edited log should not brick
   remembering."
  [facts]
  (str "mem-"
       (inc (reduce (fn [hi id]
                      (if-let [[_ n] (re-matches id-pattern (str id))]
                        (max hi (parse-long n))
                        hi))
                    0 (keys facts)))))

;; ----------------------------------------------------------------------------
;; embedding — after the write, never during it
;; ----------------------------------------------------------------------------

(defn- awaiting?
  "True when `f` has no vector usable by `model`: none at all, or one stamped
   with a different model.

   A vector from another model is worse than none. Two embedding spaces are not
   comparable, and cosine between them still returns a number that sorts — so a
   stale vector produces confident nonsense, while no vector produces a fact
   that competes lexically and is queued for re-embedding."
  [f model]
  (or (empty? (:vec f)) (not= model (:model f))))

(defonce ^{:private true
           :doc "messages already reported by warn-once!, so a timer does not repeat them"}
  !warned (atom #{}))

(defn- warn-once!
  "Print `msg` to stderr the first time it is seen and never again.

   A backfill runs on a timer, so an embedder answering with a wrong dimension
   would otherwise write the same line every interval for as long as loci is up.
   The set is keyed on the message, so a *different* failure still gets its own
   line — including a different dimension, which the client puts in the text."
  [msg]
  (when-not (contains? @!warned msg)
    (swap! !warned conj msg)
    (binding [*out* *err*] (println "loci.memory:" msg))))

(defn all-facts
  "Every remembered fact, newest first — the browsable memory pane.

   A thin function over the protocol method so existing callers read unchanged."
  [m]
  (mold/all-facts m))

(defn pending-facts
  "The facts awaiting an embedding for the configured model — what the memory
   pane reports as \"N awaiting embedding\" rather than pretending they are
   searchable by meaning."
  ([m] (pending-facts m (embed/embed-model)))
  ([m model] (filterv #(awaiting? % model) (mold/all-facts m))))

(def ^:private batch-size
  "Texts per request. One request would carry loci's whole memory today; this
   only keeps a first backfill of a much larger memory from being a single
   enormous body, and costs nothing at this scale."
  32)

(defn- store-vector!
  "Persist one fact's embedding: the same :id appended again with :vec, :model
   and :dim added. The log is last-wins by id, so this is exactly what
   reinforcement already does — no new store, no migration.

   The update is applied to the fact as it stands *now*, not to the snapshot the
   pass started from, so a reinforcement that landed while the embedder was
   thinking is not rolled back. If the text changed under us — or the fact is
   gone — nothing is written and nothing is appended: the vector describes text
   that is no longer stored, and pairing it with the new text is the silent
   mismatch this whole path exists to avoid. The fact simply stays pending and
   the next pass embeds what it now says."
  [{:keys [!facts file]} fact vector model dim]
  (let [id   (:id fact)
        text (:fact fact)
        [_ after] (swap-vals! !facts
                              (fn [fs]
                                (if (= text (:fact (get fs id)))
                                  (update fs id assoc :vec vector :model model :dim dim)
                                  fs)))
        rec  (get after id)]
    (when (and rec (= text (:fact rec)))
      (append-line! file rec)
      true)))

(defn embed-pending!
  "Embed every fact awaiting one and append each result. Returns

     {:embedded n :pending n :model \"…\"}   with :error added if a batch failed
     {:off true}                            no embedding endpoint configured

   and never throws.

   A plain function: the server calls it, `start-embed-worker!` calls it on a
   timer, a test calls it directly. Requiring this namespace calls it never.

   **Resumable by construction.** Nothing marks a fact as tried — a failed
   embedding leaves the fact with no :vec, which is the same state it was in
   before, so the next pass picks it up again. A worker killed mid-sweep loses
   only the batch in flight.

   It stops at the first failing batch rather than working through the rest. The
   overwhelmingly likely cause is that the embedder is down, and twenty more
   30-second timeouts discover that twenty more times.

   Two passes running at once do the same work twice and append the same :id
   twice. Last-wins by :id makes that harmless — it costs a duplicate line, not
   a wrong fact — which is why no lock is taken."
  [m]
  (if-not (embed/embedding-configured?)
    {:off true}
    (let [model (embed/embed-model)]
      (loop [batches (partition-all batch-size (pending-facts m model))
             done    0]
        (if-let [batch (seq (first batches))]
          (let [r (embed/embed-texts (mapv :fact batch))]
            (if (:error r)
              (do (warn-once! (:error r))
                  {:embedded done
                   :pending  (count (pending-facts m model))
                   :model    model
                   :error    (:error r)})
              ;; :embedded counts what was actually stored, not what was asked
              ;; for — store-vector! declines a fact whose text moved under it.
              (let [stored (reduce (fn [n [f v]]
                                     ;; :model here is the *configured* name;
                                     ;; the server's own (llama.cpp answers with
                                     ;; a file path) would never match again and
                                     ;; the fact would be re-embedded forever.
                                     (if (store-vector! m f v (:model r) (:dim r))
                                       (inc n) n))
                                   0 (map vector batch (:vectors r)))]
                (recur (next batches) (+ done stored)))))
          {:embedded done
           :pending  (count (pending-facts m model))
           :model    model})))))

(defn start-embed-worker!
  "Start one background thread that runs `embed-pending!` on `m` every
   `:interval-ms` (default 15 s) until stopped. Returns a handle to pass to
   `stop-embed-worker!`.

   Explicit on purpose. Requiring this namespace starts nothing: a namespace
   that spawns on load leaves a thread behind in every process that merely read
   it, tests included.

   It polls rather than being woken by `remember`, so `remember` holds no
   reference to the worker at all — there is nothing there for a later edit to
   make it wait on. A pass with nothing pending makes no HTTP request, so the
   idle cost is a scan of the facts.

   The thread is a daemon: a worker parked in a 30-second request must not be
   what keeps the JVM alive."
  ([m] (start-embed-worker! m {}))
  ([m {:keys [interval-ms] :or {interval-ms 15000}}]
   (let [wake (ArrayBlockingQueue. 1)
         run? (atom true)
         t    (Thread. ^Runnable
                       (fn []
                         (while @run?
                           (try (embed-pending! m)
                                (catch Throwable e
                                  (warn-once! (str "the embedding worker threw: " e))))
                           (when @run?
                             (try (.poll wake interval-ms TimeUnit/MILLISECONDS)
                                  (catch InterruptedException _ nil)))))
                       "loci-embed-worker")]
     (.setDaemon t true)
     (.start t)
     {:thread t :wake wake :run? run?})))

(defn stop-embed-worker!
  "Stop a worker started by `start-embed-worker!`. Returns true once its thread
   has died.

   The wake queue is what makes that quick: the thread spends its life parked in
   a poll whose timeout is the interval, and an offer returns it immediately
   instead of after the rest of the interval. A request already in flight is not
   interrupted — it can legitimately be a 30-second timeout — so the join is
   bounded and a false return means \"still finishing a request\", not \"stuck\"."
  [{:keys [^Thread thread ^ArrayBlockingQueue wake run?]}]
  (reset! run? false)
  (.offer wake :stop)
  (.join thread 2000)
  (not (.isAlive thread)))

;; ----------------------------------------------------------------------------

(defrecord FileMemory [!facts file]
  mold/Recall
  ;; NOTHING in here touches the embedder. That is the design, not an omission:
  ;; a fact is recorded whether or not an embedder exists, at the speed of one
  ;; file append, and `embed-pending!` catches up afterwards.
  (remember [_ fact opts]
    (let [ft  (tokens fact)
          dup (some (fn [f] (when (>= (jaccard ft (tokens (:fact f))) 0.6) f))
                    (vals @!facts))
          rec (if dup
                (cond-> (-> dup
                            (assoc :fact fact :ts (System/currentTimeMillis))
                            (assoc :source (or (:source opts) (:source dup)))
                            (update :strength (fnil inc 1))
                            (update :entities #(vec (distinct (concat % (:entities opts))))))
                  ;; Reinforcement rewrites :fact, and a vector describes the
                  ;; exact text it was made from. Identical wording (the common
                  ;; case — auto-distill re-derives the same sentence) keeps its
                  ;; vector; different wording drops it, so the next pass
                  ;; re-embeds rather than leaving a vector pointing at a
                  ;; sentence that is no longer stored.
                  (not= fact (:fact dup)) (dissoc :vec :model :dim))
                {:id (next-id @!facts)
                 :fact fact :entities (vec (:entities opts))
                 :source (:source opts) :ts (System/currentTimeMillis) :strength 1})]
      (append-line! file rec)
      (swap! !facts assoc (:id rec) rec)
      :ok))
  (recall [_ query opts]
    (let [qt (tokens query) now (System/currentTimeMillis) k (or (:k opts) 6)]
      (->> (vals @!facts)
           (map #(assoc % :score (score qt now %)))
           (filter #(pos? (:score %)))
           (sort-by :score >)
           (take k)
           vec)))
  (all-facts [_] (->> (vals @!facts) (sort-by :ts >) vec)))

(defn file-memory
  ([] (file-memory (str (sub/data-dir) "/memory.edn")))
  ([path] (->FileMemory (atom (load-facts path)) path)))

(defonce ^{:doc "the server's memory singleton (data-dir resolved at first use)"}
  memory (delay (file-memory)))
