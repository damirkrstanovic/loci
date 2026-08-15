(ns loci.memory-dlv-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.substrate :as sub]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-mdlv-" (System/nanoTime) "/substrate"))

(deftest concurrent-remember-loses-no-fact
  (let [m  (mem/datalevin-memory (dlv/datalevin-store (tmpdir)))
        ;; 20 topics, pairwise jaccard 0.3333 — only "governs"/"within"/
        ;; "entirely" are ever shared, every other token carries the topic's
        ;; own index — so topics never bleed into each other. Each topic is
        ;; remembered 10 times CONCURRENTLY with THE EXACT SAME TEXT (jaccard
        ;; 1.0 with itself): correct, fully-serialized behaviour is 9 of
        ;; every 10 reinforcing the first, so exactly 20 facts should survive.
        ;;
        ;; This is not the corpus the plan originally specified. That one was
        ;; "Fact number N concerns subject N exclusively." for N in 0..199,
        ;; called out as "deliberately unrelated" — measured, it is not:
        ;; jaccard 0.7142857 between EVERY pair, since only the digit token
        ;; differs and "fact"/"number"/"concerns"/"subject"/"exclusively" are
        ;; shared by all 200. Correct behaviour collapses all 200 into ONE
        ;; reinforced fact, so `(= 200 (count (all-facts m)))` could not pass
        ;; under any lock — confirmed: with the lock in place it settled on 1.
        ;;
        ;; Fixing only the jaccard (200 genuinely distinct topics, each
        ;; remembered once) does not work either, though for the opposite
        ;; reason: measured with real concurrent threads, 200 of 200 survived
        ;; even with NO lock at all. `DbiLog.mint-id!` and `DbiLog.append!`
        ;; (Task 3) are already individually atomic — every id they hand out
        ;; is already unique — so a corpus with no possible duplicate can
        ;; never exercise the race `lock` exists for: the duplicate SCAN
        ;; racing the insert-or-reinforce DECISION, not the mint. That race
        ;; needs something the scan could plausibly call a duplicate.
        ;;
        ;; Repeated identical text is exactly that, and reliably racy:
        ;; measured unlocked (pmap, 6 cores) over three runs, 122, 106 and 103
        ;; facts survived where 20 should have — the duplicate scan and the
        ;; swap that follows it are not one step, so threads racing on the
        ;; same text repeatedly all see "no duplicate yet" and all insert.
        topics (mapv #(str "Quiddity" % " governs phenomenon" % " within regime" % " entirely.")
                      (range 20))
        fs     (mapcat #(repeat 10 %) topics)]
    (doall (pmap #(mold/remember m % {:entities ["x"]}) fs))
    (is (= (count topics) (count (mem/all-facts m)))
        "10 concurrent remembers of the same text must reinforce one fact each, not insert extras")
    (is (= (count topics) (count (set (map :id (mem/all-facts m)))))
        "every surviving fact still has its own id")))

(deftest undo-does-not-revoke-a-fact
  (let [st (dlv/datalevin-store (tmpdir))
        m  (mem/datalevin-memory st)]
    (sub/commit! st {:op :put :id "doc:1" :value {:id "doc:1" :kind :doc :title "T" :value "x"}})
    (mold/remember m "The document says something worth keeping."
                   {:entities ["t"] :source {:obj "doc:1"}})
    (is (= 1 (count (mem/all-facts m))))
    (sub/undo! st)
    (is (nil? (sub/object st "doc:1")) "the document is gone")
    (is (= 1 (count (mem/all-facts m)))
        "undoing a notebook edit must not un-learn a fact")
    ;; The check above alone does NOT bite. `m`'s facts live in `!facts`, an
    ;; in-RAM cache loaded once at `datalevin-memory` construction and
    ;; mutated only by `remember`'s own writes — `undo!` cannot touch it no
    ;; matter what `undo-tx` does to the `facts` dbi, so a regression that
    ;; makes undo delete from `facts` would sail past `(count (all-facts m))`
    ;; on the SAME `m`. Confirmed by sabotaging `DbiLog.append!` to also
    ;; write `[[:put "events" 999 rec :long]]` per the original recipe: 0
    ;; failures — an orphan, non-contiguous "events" key is never read back
    ;; by anything either, so it is doubly inert.
    ;;
    ;; A fresh memory over the SAME store, built after the undo, reads
    ;; `facts` back off disk and is what would actually notice a fact `undo!`
    ;; had deleted.
    (is (= 1 (count (mem/all-facts (mem/datalevin-memory st))))
        "a fact reloaded from durable storage after undo must still be there")))

(deftest as-of-does-not-time-travel-the-memory
  (let [st (dlv/datalevin-store (tmpdir))
        m  (mem/datalevin-memory st)]
    (sub/commit! st {:op :put :id "doc:1" :value {:id "doc:1" :kind :doc :title "T" :value "x"}})
    (mold/remember m "Cobalt blue reached Delft through the Dutch trade." {:entities ["cobalt"]})
    (let [before (mold/recall m "cobalt" {:k 5})]
      (sub/as-of st 0)
      (is (= (mapv :id before) (mapv :id (mold/recall m "cobalt" {:k 5})))
          "recall answers the same regardless of where the substrate is scrubbed to"))))
