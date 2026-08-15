(ns loci.agent-test
  "`distill-facts`'s system prompt, pinned — and what its answer becomes.

   `agent/chat` is an ordinary `defn` (not a protocol method — protocol
   callsites are compiled to a cached implementation and `with-redefs` on the
   var never gets seen there, which is why an earlier task in this plan had
   to stub `mold/recall` a different way). A plain var, `with-redefs` reaches
   it cleanly, so this is the one test in the plan that can use the simple
   form. Where a protocol IS in the way — `mold/remember`, below — the
   receiver changes instead of the method: `remember-spy` is a `reify`.

   No network, no key, no store: `chat` never runs for real, `mem/memory` is
   rebound to a delay over a spy so the real `data/memory.edn` delay is never
   forced, so there is nothing here for LOCI_LLM_API_KEY or `data/` to do."
  (:require [clojure.test :refer [deftest is testing]]
            [loci.agent :as agent]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.server :as srv]))

(deftest distill-facts-tells-the-model-to-name-the-subject
  (testing "the system prompt requires each fact to name its own subject,
            and the parsed return shape is unchanged

            This is a WEAK test by construction: it proves the instruction is
            present in the prompt sent to the model, nothing more. It cannot
            prove the distiller actually complies — that would take a real
            call and a judgment about the sentence it returned. Green here
            means \"we asked\", not \"facts now name their subject\"."
    (let [captured (atom nil)
          canned   (str "{\"facts\":[{\"fact\":\"The Aeon path in Wrath of the "
                        "Righteous locks out the Lich ending\","
                        "\"entities\":[\"wrath of the righteous\"]}]}")]
      (with-redefs [agent/chat (fn [messages & _opts]
                                 (reset! captured messages)
                                 canned)]
        (let [result (agent/distill-facts "a CRPG notebook" "some flow output")
              sys     (:content (first @captured))]
          ;; the requirement, not the wording: a fact must be told to name
          ;; ITS OWN subject (not merely "be clear" or "be specific") because
          ;; it is read standalone later. Two independent substrings rather
          ;; than the literal sentence, so a future rewording that keeps both
          ;; halves of the requirement still passes, and a version that drops
          ;; either half — the instruction, or the reason it exists — fails.
          (is (re-find #"(?i)own subject" sys)
              "must instruct the model to name the fact's own subject")
          (is (re-find #"(?i)on its own" sys)
              "must say why: the fact is read later with no context around it")
          ;; the shape distill! (server.clj) destructures is unchanged
          (is (= [{:fact "The Aeon path in Wrath of the Righteous locks out the Lich ending"
                   :entities ["wrath of the righteous"]}]
                 result)))))))

;; ============================================================================
;; …and whether the fact will still be true next year
;; ============================================================================

(def ^:private aeon-fact
  "The Aeon path in Wrath of the Righteous locks out the Lich ending")

(defn- canned
  "One fact as the model would return it. `durable` nil spells the field out of
   the JSON entirely — an older model, or one that declined the question, and a
   state that has to stay distinct from `false` the whole way down."
  [durable]
  (str "{\"facts\":[{\"fact\":\"" aeon-fact "\","
       "\"entities\":[\"wrath of the righteous\"]"
       (when (some? durable) (str ",\"durable\":" durable))
       "}]}"))

(defn- distilled
  "`[parsed-facts system-prompt]` for a canned model reply."
  [body]
  (let [captured (atom nil)]
    (with-redefs [agent/chat (fn [messages & _opts] (reset! captured messages) body)]
      [(agent/distill-facts "a CRPG notebook" "some flow output")
       (:content (first @captured))])))

(deftest distill-facts-asks-whether-the-fact-will-go-out-of-date
  (testing "the system prompt requires a per-fact durability judgement, and
            teaches the axis rather than only naming it

            WEAK for the same reason as the test above: it pins the
            instruction SENT, not the model's compliance. Green here means
            \"we asked\", never \"facts are classified correctly\" — that is a
            measurement over the real corpus, and Task 6 of the plan takes it."
    (let [[_ sys] (distilled (canned true))]
      ;; The requirement in two independent halves, as above: the field, and
      ;; what the field MEANS. A reword that keeps both still passes; dropping
      ;; either — asking for a field nobody defined, or defining an axis the
      ;; JSON has no room for — fails.
      (is (re-find #"(?i)\"durable\"" sys)
          "must ask for a \"durable\" field in the JSON shape it specifies")
      (is (re-find #"(?i)out of date" sys)
          (str "must say what the axis IS — a claim that goes out of date "
               "against one that does not — because \"durable\" is no more "
               "self-explanatory to a model than it was to us")))))

(deftest a-durability-judgement-reaches-the-parsed-fact
  (testing "true and false both arrive, as themselves"
    (let [[[f] _] (distilled (canned true))]
      (is (= true (:durable f)) "\"durable\":true parses to :durable true"))
    (let [[[f] _] (distilled (canned false))]
      (is (= false (:durable f))
          "false is an ANSWER — the model judged and said no — and must not be flattened into the same state as silence"))))

(deftest a-model-that-judges-nothing-still-distills
  (testing "a body with no \"durable\" field parses, and stays unjudged"
    (let [[[f] _] (distilled (canned nil))]
      (is (= aeon-fact (:fact f))
          "an older model, or a refusal to answer, must not break distillation")
      (is (not (contains? f :durable))
          (str "absent must stay absent: `remember` keys on `contains?`, so "
               "coercing silence to false here would record, forever, a "
               "judgement nobody made")))))

;; ---- and what distill! does with it -----------------------------------------

(defn- remember-spy
  "A `mold/Recall` that records what `remember` was called with.

   A `reify`, not `with-redefs` on `mold/remember`: `remember` is a protocol
   method and the compiler caches the callsite against the implementing class,
   so redefining the var is silently ignored and the test passes green while
   observing nothing. The RECEIVER is what has to change, and `mem/memory` is
   an ordinary var holding a delay, so rebinding that is seen."
  [seen]
  (reify mold/Recall
    (remember  [_ fact opts] (swap! seen conj [fact opts]) :ok)
    (recall    [_ _ _] [])
    (all-facts [_] [])))

(defn- distill-opts
  "The `[fact opts]` pairs `distill!` hands `remember`, for a canned reply."
  [body]
  (let [seen (atom [])]
    (with-redefs [agent/chat (fn [& _] body)
                  mem/memory (delay (remember-spy seen))]
      ;; Deref INSIDE the with-redefs. `distill!` returns a future and
      ;; `with-redefs` restores the root bindings the instant its body exits,
      ;; so a future still running would find the real memory singleton.
      @(#'srv/distill! "a CRPG notebook" "some flow output" "doc:1" "space:1"))
    @seen))

(deftest distill-hands-the-judgement-to-remember-renamed
  ;; `distill!` swallows every exception, so a spy that was never reached
  ;; leaves `calls` empty and every `contains?` assertion below would pass
  ;; vacuously. Each block counts the calls first.
  (testing "durable true arrives as :durable? true, beside the existing opts"
    (let [calls (distill-opts (canned true))
          [[fact opts]] calls]
      (is (= 1 (count calls)) "one fact in, one remember out")
      (is (= aeon-fact fact))
      (is (= true (:durable? opts))
          "RENAMED on the way through: the model answers \"durable\", `remember` keys on :durable?")
      (is (= ["wrath of the righteous"] (:entities opts))
          "and the opts it already carried are untouched")
      (is (= {:obj "doc:1" :space "space:1"} (:source opts)))))
  (testing "durable false arrives as false, not as silence"
    (let [calls (distill-opts (canned false))
          [[_ opts]] calls]
      (is (= 1 (count calls)))
      (is (contains? opts :durable?) "the model answered, so `remember` must hear an answer")
      (is (= false (:durable? opts)))))
  (testing "no judgement at all reaches remember as no key at all"
    (let [calls (distill-opts (canned nil))
          [[fact opts]] calls]
      (is (= 1 (count calls)) "the fact is still recorded — an unjudged fact is a normal fact")
      (is (= aeon-fact fact))
      (is (not (contains? opts :durable?))
          (str "a `{:keys [durable]}` destructure would put :durable? nil here, "
               "and `remember` keys on `contains?` — so every unjudged fact "
               "would be written :durable-by \"agent\", judged by nobody and "
               "un-rejudgeable except by hand"))))
  (testing "an answer that is not a boolean is not an answer"
    ;; Beyond what the plan asked for, and for the reason the clause above
    ;; exists. A model answering the STRING "false" — JSON mode does not make
    ;; that impossible — is truthy in Clojure, so it would reach `weight` as
    ;; durable: decay skipped on the one fact the model just said ages, stamped
    ;; `:durable-by "agent"`, and undoable only by hand in the pane. A
    ;; malformed answer is worse than a missing one, so it is treated as the
    ;; missing one.
    (let [calls (distill-opts (canned "\"false\""))
          [[fact opts]] calls]
      (is (= 1 (count calls)) "the fact is still recorded")
      (is (= aeon-fact fact))
      (is (not (contains? opts :durable?))
          "a string, a number, a null — none of them is a judgement, and passing one through would be a judgement nobody made in the WRONG direction"))))

(deftest llm-timeout-is-configurable-and-long-enough-for-a-research-step
  ;; 60000 was the hardcoded value and it is what made agent flows unusable.
  ;; Measured 2026-08-15: a bare research! on a narrow prompt finished in 31 s
  ;; and passed; the same verb inside a flow — same model, plus the notebook
  ;; context and a broader prompt — passed 60 s and died with
  ;; "TimeoutException: idle timeout", at step 1 of 5.
  ;;
  ;; It is http-kit's IDLE timeout, and a non-streaming completion arrives all
  ;; at once, so it is really a deadline for the whole response rather than a
  ;; budget for a slow trickle.
  (let [f #'agent/timeout-ms]
    (is (= 180000 (f)) "the default must clear a research step with several tool rounds")
    (with-redefs [loci.config/env {"LOCI_LLM_TIMEOUT_MS" "45000"}]
      (is (= 45000 (f)) "the knob is read"))
    ;; a misconfigured value must not become a zero-length timeout
    (with-redefs [loci.config/env {"LOCI_LLM_TIMEOUT_MS" "abc"}]
      (is (= 180000 (f)) "garbage falls back rather than breaking every call"))
    (with-redefs [loci.config/env {"LOCI_LLM_TIMEOUT_MS" ""}]
      (is (= 180000 (f)) "blank is unset, as everywhere else in loci.config"))))
