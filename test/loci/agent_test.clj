(ns loci.agent-test
  "`distill-facts`'s system prompt, pinned.

   `agent/chat` is an ordinary `defn` (not a protocol method — protocol
   callsites are compiled to a cached implementation and `with-redefs` on the
   var never gets seen there, which is why an earlier task in this plan had
   to stub `mold/recall` a different way). A plain var, `with-redefs` reaches
   it cleanly, so this is the one test in the plan that can use the simple
   form.

   No network, no key, no store: `chat` never runs for real, so there is
   nothing here for LOCI_LLM_API_KEY or `data/` to do."
  (:require [clojure.test :refer [deftest is testing]]
            [loci.agent :as agent]))

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
