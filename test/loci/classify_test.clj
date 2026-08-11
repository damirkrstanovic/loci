(ns loci.classify-test
  "The one-time classification pass, against synthetic memories.

   Nothing here opens `data/`. Every memory is a fresh `mem/file-memory` under
   `java.io.tmpdir`, removed in a `finally`; nothing derefs `loci.memory/memory`;
   no test needs an endpoint, a key or a network.

   `agent/chat` is a plain `defn`, so `with-redefs` on it is seen. That is not
   true of `mold/recall` and `mold/remember`, which are protocol methods whose
   callsites the compiler caches against the implementing class — redefining
   those vars is silently ignored and the test passes green while observing
   nothing. It has cost this codebase a round already.

   The stub reads the ids out of the USER message it was sent, so it also pins
   that the pass asks about what it says it asks about: a pass that sent the
   model nothing would get nothing back and refuse, loudly, rather than pass."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loci.agent :as agent]
            [loci.classify :as classify]
            [loci.memory :as mem]
            [loci.mold :as mold]))

;; ---------------------------------------------------------------------------
;; the fixture
;; ---------------------------------------------------------------------------

(def corpus
  "Three facts with the `:ts` and `:strength` shape of a real log. The
   timestamps are literals on purpose: `remember` would replace every one of
   them with `System/currentTimeMillis`, and an assertion against a value the
   fixture itself invented at run time could not notice.

   The sentences are the spec's known-good answers — Kepler and Mercury durable,
   lithography capacity not — so a reader of these tests is reading the same
   examples Task 6 will check the real classifier against."
  [{:id "mem-1" :fact "Kepler's Third Law states that T² ∝ a³."
    :entities ["kepler's third law"] :source {:obj "find:dd-18-1" :space "space:dd-18"}
    :ts 1784830205451 :strength 3}
   {:id "mem-2" :fact "Lithography capacity is booked through 2027."
    :entities ["asml"] :source {:obj "find:dd-15-1" :space "space:dd-15"}
    :ts 1784830205452 :strength 1}
   {:id "mem-3" :fact "Mercury's orbital period is 88 Earth days."
    :entities ["mercury"] :source {:obj "find:dd-18-1" :space "space:dd-18"}
    :ts 1784830205453 :strength 2}])

(def answers
  "What the stubbed classifier says about each of the three."
  {"mem-1" true "mem-2" false "mem-3" true})

(defn- judging
  "A stubbed `agent/chat` that judges by lookup, plus what it saw.

     {:chat  fn     — install with (with-redefs [agent/chat …])
      :asked atom   — every fact id the model was shown
      :calls atom   — how many requests were made
      :sys   atom   — the last system prompt sent}

   It answers out of the ids in the request rather than from a canned string, so
   a fact the pass never asks about is a fact the model never judges — which is
   how the `\"you\"` test asserts a skip happened *before* the call and not after
   the answer."
  [judgements]
  (let [asked (atom #{}) calls (atom 0) sys (atom nil)]
    {:asked asked :calls calls :sys sys
     :chat (fn [messages & _]
             (swap! calls inc)
             (reset! sys (:content (first messages)))
             (let [batch (:facts (json/read-str (:content (second messages)) :key-fn keyword))]
               (swap! asked into (map :id batch))
               (json/write-str
                {:judgements (vec (keep (fn [{:keys [id]}]
                                          (when-let [e (find judgements id)]
                                            {:id id :durable (val e)}))
                                        batch))})))}))

(defn- with-memory*
  [facts f]
  (let [path (str (System/getProperty "java.io.tmpdir") "/loci-classify-" (System/nanoTime) ".edn")]
    (try
      (spit path (apply str (map #(str (pr-str %) "\n") facts)))
      (f (mem/file-memory path) path)
      (finally (.delete (io/file path))))))

(defmacro with-memory
  "A FileMemory over a temp log seeded with `facts`, always removed."
  [[m path facts] & body]
  `(with-memory* ~facts (fn [~m ~path] ~@body)))

(defn- lines [path] (vec (remove str/blank? (str/split-lines (slurp path)))))
(defn- fact-line-count [path] (count (lines path)))

(defn- reloaded
  "id → record, read back off disk rather than out of the atom — the file is
   what survives a restart, and an implementation that swapped without appending
   would otherwise look correct."
  [path]
  (into {} (map (juxt :id identity)) (mold/all-facts (mem/file-memory path))))

;; ===========================================================================
;; the plan
;; ===========================================================================

(deftest a-dry-run-appends-nothing-and-says-what-it-would-do
  (with-memory [m path corpus]
    (let [c    (judging answers)
          text (slurp path)
          r    (with-redefs [agent/chat (:chat c)] (classify/classify! m))]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (:dry-run? r) "dry-run? must be the DEFAULT, not something a caller opts into")
      (is (= text (slurp path)) "a dry run appended to the log")
      (is (= (set corpus) (set (mold/all-facts m)))
          "a dry run changed the facts in RAM — an append is not the only way to write")

      (testing "the plan names every fact and its judgement"
        (is (= [{:id "mem-1" :fact "Kepler's Third Law states that T² ∝ a³." :durable? true}
                {:id "mem-2" :fact "Lithography capacity is booked through 2027." :durable? false}
                {:id "mem-3" :fact "Mercury's orbital period is 88 Earth days." :durable? true}]
               (get-in r [:plan :classify]))
            "the fact's text is in the plan because the text is what was judged")
        (is (= 3 (get-in r [:plan :appends])))
        (is (= 3 (get-in r [:plan :facts])))
        (is (= 2 (get-in r [:plan :durable]))
            "a classifier answering \"durable\" to everything must be visible in one number")
        (is (empty? (get-in r [:plan :already])))
        (is (empty? (get-in r [:plan :yours])))
        (is (empty? (get-in r [:plan :unjudged]))))

      (testing "the explicit dry run is the same answer as the default"
        (let [b (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? true}))]
          (is (= (:plan r) (:plan b))))))))

(deftest the-classifier-is-asked-the-durability-question
  (testing "WEAK BY CONSTRUCTION, and this is the disclosure: what follows pins
            the instruction SENT, not the model's compliance. Green here means
            \"we asked\", never \"the corpus is classified correctly\" — that is a
            measurement over real facts, and Task 6 of the plan takes it, on a
            copy."
    (with-memory [m path corpus]
      (let [c (judging answers)]
        (with-redefs [agent/chat (:chat c)] (classify/classify! m))
        (let [sys (str @(:sys c))]
          (is (re-find #"(?i)\"durable\"" sys)
              "must ask for a \"durable\" field in the JSON shape it specifies")
          (is (re-find #"(?i)out of date" sys)
              (str "must say what the axis IS, because \"durable\" is no more "
                   "self-explanatory to a model than it was to us"))
          (is (str/includes? sys "T² ∝ a³")
              "the axis is taught BY EXAMPLE — one side of it, at least")
          (is (str/includes? sys "2027")
              "…and the other side, or the model learns half a distinction")
          (is (re-find #"(?i)\"id\"" sys)
              (str "every judgement must name the id it is about: a positional "
                   "answer misaligns silently the moment the model drops one")))))))

;; ===========================================================================
;; the write
;; ===========================================================================

(deftest a-live-run-appends-one-line-per-fact-stamped-agent
  (with-memory [m path corpus]
    (let [c     (judging answers)
          n     (fact-line-count path)
          r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= 3 (:appended r)))
      (is (empty? (:declined r)))
      (is (= 3 (- (fact-line-count path) n)) "exactly one line per fact, appended")
      (is (= true (:durable? (after "mem-1"))))
      (is (= false (:durable? (after "mem-2")))
          "false is an ANSWER — the classifier judged and said no — and is written as one")
      (is (= true (:durable? (after "mem-3"))))
      (doseq [id ["mem-1" "mem-2" "mem-3"]]
        (is (= "agent" (:durable-by (after id)))
            (str id " must record WHO judged it, or a later reinforcement cannot "
                 "tell an agent's call from yours"))))))

(deftest the-pass-does-not-touch-ts-or-strength
  ;; The `remember` trap, asserted directly. `remember` would find each fact as
  ;; its own jaccard-1.0 duplicate, bump `:strength` and re-stamp `:ts` — handing
  ;; every fact the classifier calls NOT durable a fresh 1.0 decay multiplier in
  ;; the same breath as recording that it ages. That is the opposite of the
  ;; change this pass exists to make, and it is invisible in every other
  ;; assertion in this file.
  (with-memory [m path corpus]
    (let [c     (judging answers)
          r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= 3 (:appended r))
          "nothing below proves anything if the pass wrote nothing — the guard, first")
      (is (= #{"mem-1" "mem-2" "mem-3"} (set (keys after)))
          "a fourth id means the pass MINTED a fact instead of judging one")
      (doseq [f corpus]
        (let [a (after (:id f))]
          (is (= (:ts f) (:ts a))
              (str (:id f) "'s :ts moved — :ts is a multiplier in weight, so re-stamping "
                   "it hands a fact that ages a fresh 1.0"))
          (is (= (:strength f) (:strength a))
              (str (:id f) "'s :strength moved — this pass judges, it does not reinforce"))
          (is (= (assoc f :durable? (answers (:id f)) :durable-by "agent") a)
              (str (:id f) " is not the record that was there with two keys added — "
                   "everything else about a fact is left exactly alone")))))))

(deftest a-second-run-is-a-no-op
  (with-memory [m path corpus]
    (let [c (judging answers)]
      (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
      (let [n     (fact-line-count path)
            calls @(:calls c)
            r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))]
        (is (:ok? r) (str "refused: " (:error r)))
        (is (= 0 (:appended r)))
        (is (= n (fact-line-count path)) "an already-judged fact was appended again")
        (is (= ["mem-1" "mem-2" "mem-3"] (get-in r [:plan :already])))
        (is (empty? (get-in r [:plan :classify])))
        (is (= calls @(:calls c))
            (str "a second run asked the model again — an idempotent pass that "
                 "still spends every call is only half idempotent"))))))

(deftest a-judgement-of-yours-is-never-re-judged
  (with-memory [m path (mapv #(cond-> % (= "mem-2" (:id %))
                                      (assoc :durable? true :durable-by "you"))
                             corpus)]
    ;; the classifier would say `false` for mem-2, and does not get the chance
    (let [c     (judging answers)
          n     (fact-line-count path)
          r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= 2 (:appended r)))
      (is (= ["mem-2"] (get-in r [:plan :yours])))
      (is (= [] (get-in r [:plan :already]))
          "yours is reported once, under :yours — the four lists have to add up")
      (is (= 3 (get-in r [:plan :facts]))
          ":facts is the corpus, not the queue — Task 6 reports \"n of 80\" out of it")
      (is (= #{"mem-1" "mem-2" "mem-3"}
             (set (concat (map :id (get-in r [:plan :classify]))
                          (get-in r [:plan :already])
                          (get-in r [:plan :yours])
                          (get-in r [:plan :unjudged]))))
          "a fact in none of the four lists is a fact the plan dropped silently")
      (is (= 2 (- (fact-line-count path) n)))
      (is (= true (:durable? (after "mem-2")))
          "the classifier said false and you said true; yours stands")
      (is (= "you" (:durable-by (after "mem-2")))
          "…and stays yours, so the next agent pass cannot revise it either")
      (is (= #{"mem-1" "mem-3"} @(:asked c))
          (str "mem-2 was put in front of the model at all — the skip belongs "
               "before the call, not after the answer"))))

  (testing "…including a judgement of yours the pass could not have written itself
            — a hand-edited log is a state this codebase already plans for
            (`next-id`: \"a hand-edited log should not brick remembering\"), and it
            is the case where the `\"you\"` guard is the ONLY thing standing between
            your call and an agent's, since a malformed value is not `judged?`"
    (with-memory [m path (mapv #(cond-> % (= "mem-2" (:id %))
                                        (assoc :durable? "true" :durable-by "you"))
                               corpus)]
      (let [c     (judging answers)
            n     (fact-line-count path)
            r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
            after (reloaded path)]
        (is (:ok? r) (str "refused: " (:error r)))
        (is (= 2 (:appended r)))
        (is (= ["mem-2"] (get-in r [:plan :yours])))
        (is (= 2 (- (fact-line-count path) n)))
        (is (= "you" (:durable-by (after "mem-2")))
            "your record came back stamped as an agent's — repairing your call is not this pass's business")
        (is (= "true" (:durable? (after "mem-2")))
            "…with the classifier's answer in place of yours")
        (is (not (contains? @(:asked c) "mem-2")))))))

(deftest a-fact-whose-text-moved-between-plan-and-write-is-declined
  ;; A watch rewrites mem-2's sentence once, after the FIRST fact's swap lands —
  ;; so the change is in the atom by the time mem-2's turn comes, and was not
  ;; there when the plan was made. Two failures this catches, and they are
  ;; different: appending the PLANNED record rolls the edit back under the same
  ;; id with the log reporting success, and appending the CURRENT record files a
  ;; judgement about a sentence the model was never shown.
  (with-memory [m path corpus]
    (add-watch (:!facts m) ::edit
               (fn [k a _ _]
                 (remove-watch a k)
                 (swap! a assoc-in ["mem-2" :fact]
                        "Lithography capacity is booked through 2029.")))
    (let [c     (judging answers)
          n     (fact-line-count path)
          r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= ["mem-2"] (:declined r)) "the report has to SAY which fact it declined")
      (is (= 2 (:appended r)))
      (is (= 2 (- (fact-line-count path) n)))
      (is (not (contains? (after "mem-2") :durable?))
          "mem-2 was judged anyway, against text that is no longer what it says")
      (is (= "Lithography capacity is booked through 2027." (:fact (after "mem-2")))
          (str "the pass appended mem-2's new text with a judgement of the old "
               "sentence attached to it — the mismatch store-vector! declines for"))
      (testing "guarding the guard: the run really ran"
        (is (= true (:durable? (after "mem-1"))))
        (is (= true (:durable? (after "mem-3"))))))))

;; ===========================================================================
;; refusals
;; ===========================================================================

(deftest an-answer-that-is-not-a-boolean-leaves-the-fact-unjudged
  ;; Ratified in Task 3 and matched here on purpose: a model answering the STRING
  ;; "false" is truthy in Clojure, so `weight` would skip decay on the very fact
  ;; the classifier said ages — stamped `:durable-by "agent"` and undoable only
  ;; by hand in the pane. A malformed answer leaves the fact unjudged, exactly as
  ;; no answer does. If the two writers disagreed about that, one of them would
  ;; be silently wrong for as long as nobody looked.
  (with-memory [m path corpus]
    (let [c     (judging (assoc answers "mem-2" "false"))
          n     (fact-line-count path)
          r     (with-redefs [agent/chat (:chat c)] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= 2 (:appended r)) "the other two are judged — one bad answer is not a bad pass")
      (is (= ["mem-2"] (get-in r [:plan :unjudged])))
      (is (= 2 (- (fact-line-count path) n)))
      (is (not (contains? (after "mem-2") :durable?))
          "the string \"false\" reached the record, where it is TRUE")
      (is (not (contains? (after "mem-2") :durable-by))
          "…stamped as an agent's judgement, which makes it survive the next agent pass"))))

(deftest judgements-land-on-the-fact-they-name
  ;; Beyond what the plan asked for, and the failure it guards is the quietest
  ;; one available: a pass that zipped the answers onto the batch by POSITION
  ;; would, the moment a model reorders or drops one, file every judgement after
  ;; it against a different sentence — all of them stamped `:durable-by "agent"`,
  ;; all of them plausible, and nothing anywhere reporting it. The only assertion
  ;; that can tell the two implementations apart is an answer that does not come
  ;; back in the order it was sent.
  (with-memory [m path corpus]
    (let [chat  (fn [messages & _]
                  (let [batch (:facts (json/read-str (:content (second messages)) :key-fn keyword))]
                    (json/write-str
                     {:judgements (->> batch
                                       (remove #(= "mem-1" (:id %)))
                                       reverse
                                       (mapv (fn [{:keys [id]}] {:id id :durable (answers id)})))})))
          r     (with-redefs [agent/chat chat] (classify/classify! m {:dry-run? false}))
          after (reloaded path)]
      (is (:ok? r) (str "refused: " (:error r)))
      (is (= 2 (:appended r)))
      (is (= false (:durable? (after "mem-2")))
          "a positional match would put mem-3's answer here")
      (is (= true (:durable? (after "mem-3"))))
      (is (not (contains? (after "mem-1") :durable?))
          "the model dropped mem-1, so mem-1 stays unjudged — it does not inherit a neighbour's answer")
      (is (= ["mem-1"] (get-in r [:plan :unjudged]))))))

(deftest an-answer-that-cannot-be-read-refuses-the-whole-pass
  (testing "a body that is not JSON at all"
    (with-memory [m path corpus]
      (let [text (slurp path)
            r    (with-redefs [agent/chat (fn [& _] "I'm sorry — I can't judge these.")]
                   (classify/classify! m {:dry-run? false}))]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "could not be read"))
        (is (= text (slurp path)) "a refusal that wrote anything is not a refusal")
        (is (= (set corpus) (set (mold/all-facts m))))
        (is (nil? (:appended r))))))

  (testing "a body that reads and judges nothing"
    (with-memory [m path corpus]
      (let [text (slurp path)
            r    (with-redefs [agent/chat (fn [& _] "{\"judgements\":[]}")]
                   (classify/classify! m {:dry-run? false}))]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "none")
            (str "a pass that judged none of what it asked about is a pass that "
                 "did not run, and reporting it as a success reads as a corpus "
                 "that is already judged"))
        (is (= text (slurp path))))))

  (testing "a batch that fails after two have succeeded loses both"
    (with-memory [m path corpus]
      (let [c    (judging answers)
            text (slurp path)
            n    (atom 0)
            r    (with-redefs [agent/chat (fn [& args]
                                            (if (< (swap! n inc) 3)
                                              (apply (:chat c) args)
                                              "no."))]
                   (classify/classify! m {:dry-run? false :batch-size 1}))]
        (is (false? (:ok? r)))
        (is (= 3 @n) "the pass never reached the third batch, so this proves nothing")
        (is (= text (slurp path))
            (str "two batches were written before the third failed — a half-judged "
                 "memory nobody can characterise the state of"))
        (is (= (set corpus) (set (mold/all-facts m))))))))

(deftest it-refuses-a-memory-it-cannot-append-to
  (let [m (reify mold/Recall
            (remember  [_ _ _] :ok)
            (recall    [_ _ _] [])
            (all-facts [_] corpus))
        ;; the stub is the assertion that it refuses BEFORE spending a model
        ;; call — and it also keeps this test off the network on a machine that
        ;; has an endpoint configured, which every test here has to be
        r (with-redefs [agent/chat (fn [& _] (throw (ex-info "the classifier must not be asked" {})))]
            (classify/classify! m {:dry-run? false}))]
    (is (false? (:ok? r)))
    (is (str/includes? (:error r) "FileMemory")
        "it appends to the fact log directly, so a memory with no log is a refusal"))
  (testing "including no memory at all — a refusal that throws is not a refusal"
    (let [r (classify/classify! nil {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (str/includes? (:error r) "FileMemory")))))
