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
    ;; Does not carry independent signal today: `mint-id!` is already atomic,
    ;; so distinct-count == fact-count in every unlocked run too — this
    ;; assertion has never once caught anything the one above didn't. Kept
    ;; as a tripwire against id-level regressions (a future change that
    ;; makes minting collision-prone again), not because it is doing work
    ;; here.
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
    ;; write `[[:put "events" 999 rec :long]]` per the plan's original
    ;; recipe: 0 failures — but NOT because the write is inert. It IS read
    ;; back — by `read-log`, on the next boot, which throws:
    ;;   "substrate event keys are not contiguous — expected 2, found 999"
    ;; It is invisible to a LIVE process only: `undo!`/`history` read the
    ;; in-RAM `!db`, never a live rescan of `events`, so nothing here notices
    ;; until the store is reopened — at which point it bricks the store
    ;; rather than merely failing a test. See `undo-does-not-revoke-a-fact-
    ;; via-a-contiguous-phantom-event` below for the sabotage that DOES land
    ;; a durable event a reload will actually accept.
    ;;
    ;; A fresh memory over the SAME store, built after the undo, reads
    ;; `facts` back off disk and is what would actually notice a fact `undo!`
    ;; had deleted.
    (is (= 1 (count (mem/all-facts (mem/datalevin-memory st))))
        "a fact reloaded from durable storage after undo must still be there")))

(deftest undo-does-not-revoke-a-fact-via-a-contiguous-phantom-event
  ;; The orphan-key sabotage above bricks the store (a contiguity error on
  ;; reload) rather than corrupting undo — it's not what "somebody routed
  ;; remember through commit!" would actually produce. A real routing bug
  ;; lands the fact at the NEXT CONTIGUOUS durable event key, which `read-log`
  ;; accepts as a real event. That is durable-events-side corruption, and the
  ;; live `st`'s `sub/history` cannot see it either — for the same reason
  ;; `!facts` above could not see a durable `facts` deletion: `history` reads
  ;; the in-RAM `!db`, not a rescan of `events`. `dlv/reload!` (standing in
  ;; for the next server restart, or a background reindex) is what actually
  ;; re-derives RAM from what's on disk.
  (let [st (dlv/datalevin-store (tmpdir))
        m  (mem/datalevin-memory st)]
    (sub/commit! st {:op :put :id "doc:1" :value {:id "doc:1" :kind :doc :title "T" :value "x"}})
    (mold/remember m "The document says something worth keeping."
                   {:entities ["t"] :source {:obj "doc:1"}})
    (dlv/reload! st)
    (sub/undo! st)
    (is (nil? (sub/object st "doc:1"))
        "one undo, after a reload, must revert the user's edit — not a fact
         that had no business being an event in the first place")))

(deftest as-of-does-not-time-travel-the-memory
  ;; This is a tripwire, not a live guard, and cannot be made into one: `sub/
  ;; as-of` is a pure read (`(sub/materialize (take n (:log @!db)))`) that
  ;; mutates nothing, in `st` or anywhere else, so nothing here — reusing the
  ;; same live `m` and comparing `mold/recall` before and after — could fail
  ;; today no matter what `n` is passed. Its job is to catch the day `as-of`
  ;; stops being pure — a future version that resets `!db` to the scrubbed
  ;; state, say, rather than just returning it — which is exactly the kind
  ;; of change this test would need mutation-based sabotage to prove it
  ;; bites, and there is no such mutation to make in `as-of` today.
  (let [st (dlv/datalevin-store (tmpdir))
        m  (mem/datalevin-memory st)]
    (sub/commit! st {:op :put :id "doc:1" :value {:id "doc:1" :kind :doc :title "T" :value "x"}})
    (mold/remember m "Cobalt blue reached Delft through the Dutch trade." {:entities ["cobalt"]})
    (let [before (mold/recall m "cobalt" {:k 5})]
      (sub/as-of st 0)
      (is (= (mapv :id before) (mapv :id (mold/recall m "cobalt" {:k 5})))
          "recall answers the same regardless of where the substrate is scrubbed to"))))

(def ^:private corpus
  [["TSMC produces most leading-edge logic chips."        ["tsmc" "taiwan"]]
   ["Serbia's inflation spiked to about 12% in 2022."     ["serbia"]]
   ["Cobalt blue reached Delft through the Dutch trade."  ["cobalt" "delft"]]
   ["Japan banned leaded petrol in road vehicles in 1986." ["japan" "leaded petrol"]]
   ["The Casimir effect demonstrates vacuum energy."      ["casimir effect"]]])

(deftest recall-is-identical-across-both-backends
  (let [fm (mem/file-memory (str (System/getProperty "java.io.tmpdir")
                                 "/loci-par-" (System/nanoTime) "/memory.edn"))
        dm (mem/datalevin-memory (dlv/datalevin-store (tmpdir)))]
    (doseq [[f es] corpus]
      (mold/remember fm f {:entities es})
      (mold/remember dm f {:entities es}))
    (doseq [q ["taiwan chips" "cobalt" "petrol ban" "vacuum" "inflation"
               ;; the five single-keyword queries above each return exactly
               ;; ONE hit — comparing two singleton lists cannot detect a
               ;; reversed or otherwise reordered ranking. Reviewer proved
               ;; this: reversing the DbiLog backend's ranking outright still
               ;; passed 0 failures against the query list above. This query
               ;; matches all five facts (one keyword per fact) so there is
               ;; an actual order to compare.
               "chips inflation cobalt petrol vacuum"]]
      (let [rfm (mold/recall fm q {:k 5})
            rdm (mold/recall dm q {:k 5})]
        ;; Compared only where the scores actually DIFFER. `(= (mapv :fact rfm)
        ;; (mapv :fact rdm))` looks like the obvious assertion and is a coin
        ;; flip: on the multi-hit query two of these facts score within 2e-11
        ;; of each other, so which one sorts first is decided by the decay
        ;; clock ticking between `fm`'s remember and `dm`'s, not by anything
        ;; either backend did. Measured 2026-08-15: the naive comparison failed
        ;; 3 runs in 12, the version below 0 in 12, and both catch a wholesale
        ;; reversal of one backend's ranking.
        ;;
        ;; Dropping the tied facts rather than sorting them by some tiebreak is
        ;; deliberate: re-sorting the results here would also undo a reversed
        ;; ranking, which is the one regression this assertion exists to find.
        (let [tied (fn [rs] (set (for [a rs b rs
                                       :when (and (not= (:fact a) (:fact b))
                                                  (< (Math/abs (- (double (:score a))
                                                                  (double (:score b))))
                                                     1e-6))]
                                   (:fact a))))
              amb  (into (tied rfm) (tied rdm))
              rank (fn [rs] (mapv :fact (remove #(amb (:fact %)) rs)))]
          (is (= (rank rfm) (rank rdm))
              (str "ranking differs for query: " q)))
        ;; :fact order alone lets a backend-dependent SCORE divergence that
        ;; happens to preserve order stay invisible. 1e-6 rather than exact
        ;; `=`: fm and dm are remembered a few instructions apart and
        ;; recalled by two separate calls, so `now` (decay's clock) differs
        ;; by a few milliseconds between them — measured diffs ~1e-10, so
        ;; 1e-6 has six orders of magnitude of margin against timing noise
        ;; while still catching a real scoring divergence, which would not
        ;; plausibly be this small.
        (is (every? #(< (Math/abs (double %)) 1e-6) (map - (mapv :score rfm) (mapv :score rdm)))
            (str "scores differ beyond floating-point/timing noise for query: " q))))))
