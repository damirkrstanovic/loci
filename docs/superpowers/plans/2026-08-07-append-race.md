# The Concurrent Cell-Append Race

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Two cells appended to the same notebook at the same time must both survive.

**Architecture:** A new substrate op, `:append`, whose effect is computed when the event is
*applied* rather than when it is *built* — so the read-modify-write happens inside the commit
lock instead of racing outside it.

---

## The defect, measured

`notebook.clj:51`:

```clojure
(defn append-cell-event [st space-id cell]
  (set-cells-event space-id (conj (cells-of (sub/object st space-id)) cell)))
```

It reads the whole cell vector and emits `{:op :assoc :path [:value :cells] :value <whole new vector>}`.
Two appends that read the same base both write their own idea of the whole vector; the last
one wins and the other's cell is gone.

**Measured on a fresh store, 24 concurrent appends: 6 cells present, 18 lost.**

This is the third instance of this exact class on loci — concurrent `commit!`s destroying 208
of 900 events, then the tag-colour registry losing 48 of 64 inks. The registry was fixed by
writing per key. A vector has no key, so the fix has to be different.

Reachable today: a research job and a flow step landing cells in the same notebook; two
`suggest` runs with destination `"here"`; any two agent jobs on one notebook. There are
**eight** `append-cell-event` call sites in `server.clj` and one in `tools.clj`.

---

### Task 1: An `:append` op, and the legacy trap it must survive

**Files:**
- Modify: `src/loci/substrate.clj` (`apply-event` methods, ~`:100-105`), `src/loci/notebook.clj:51-54`
- Test: `test/loci/substrate_test.clj`, `test/loci/notebook_test.clj`

- [ ] **Step 1: Write the failing tests**

The regression test, which must fail on today's code:

```clojure
(deftest concurrent-appends-do-not-lose-cells
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:t"
                     :value {:id "space:t" :kind :space :title "T" :value {:intent "i" :cells []}}})
    (let [n 24
          latch (java.util.concurrent.CountDownLatch. 1)
          ths (mapv (fn [i] (Thread. (fn [] (.await latch)
                                       (sub/commit! st (nb/append-cell-event st "space:t" {:ref (str "obj-" i)})))))
                    (range n))]
      (doseq [t ths] (.start t))
      (.countDown latch)
      (doseq [t ths] (.join t))
      (let [refs (mapv :ref (nb/cells-of (sub/object st "space:t")))]
        (is (= n (count refs)) "every concurrently appended cell survives")
        (is (= n (count (set refs))) "and none is duplicated")))))
```

**The legacy trap — this test matters as much as the race one.** `cells-of`
(`notebook.clj:15`) falls back to `:members` when a space has no `:cells`. So appending
straight to `[:value :cells]` on a legacy notebook would create a one-element `:cells` and
silently orphan every member. The browser harness already carries a comment about being
bitten by exactly this.

```clojure
(deftest appending-to-a-legacy-notebook-keeps-its-members
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:old"
                     :value {:id "space:old" :kind :space :title "Old"
                             :value {:intent "i" :members ["a" "b" "c"]}}})   ; no :cells
    (sub/commit! st (nb/append-cell-event st "space:old" {:ref "d"}))
    (is (= ["a" "b" "c" "d"] (mapv :ref (nb/cells-of (sub/object st "space:old")))))))

(deftest concurrent-appends-to-a-legacy-notebook-keep-everything
  ;; the nastiest case: two writers both see :cells absent, so both carry a seed
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:old"
                     :value {:id "space:old" :kind :space :title "Old"
                             :value {:intent "i" :members ["a" "b"]}}})
    (let [latch (java.util.concurrent.CountDownLatch. 1)
          ths (mapv (fn [i] (Thread. (fn [] (.await latch)
                                       (sub/commit! st (nb/append-cell-event st "space:old" {:ref (str "n" i)})))))
                    (range 8))]
      (doseq [t ths] (.start t)) (.countDown latch) (doseq [t ths] (.join t))
      (let [refs (mapv :ref (nb/cells-of (sub/object st "space:old")))]
        (is (= ["a" "b"] (take 2 refs)) "the members are still there, still first")
        (is (= 10 (count refs)) "and all eight new cells landed")))))
```

Plus these, which guard the substrate contract:

- **undo** of an append removes exactly that cell and nothing else
- **`as-of`** at event *n* materializes the same cells a full fold does — replay must be
  deterministic, which is the whole reason the effect may depend only on the event and the
  prior state, never on wall-clock or ordering outside the log
- the new event **survives `safe-event`'s pr-str/read round-trip** (`substrate.clj`) and
  nippy serialization on the Datalevin path — write one, reload the store, read it back

- [ ] **Step 2: Run them, watch them fail.** `clojure -M:test 2>&1 | tail -30`. The race test
      should report roughly 6 of 24 surviving. Report the actual number.

- [ ] **Step 3: Implement**

In `src/loci/substrate.clj`, beside the other `apply-event` methods:

```clojure
;; Appending is computed HERE, at apply time, not when the event was built — so
;; the read-modify-write happens inside commit!'s lock. The old form read the
;; whole cell vector and wrote a whole new one, and two concurrent appends each
;; wrote their own idea of the whole: measured, 24 concurrent appends left 6.
;;
;; :seed is used only when the path is absent, which is how a legacy notebook
;; (:members, no :cells) gets normalized without a separate racing write. Two
;; writers that both saw it absent carry the same seed, so whichever lands first
;; seeds it and the second simply appends.
(defmethod apply-event :append [st {:keys [id path value seed]}]
  (update-in st (into [:objects id] path)
             (fn [cur] (conj (vec (or cur seed)) value))))
```

In `src/loci/notebook.clj`:

```clojure
(defn append-cell-event
  "Event appending one cell. The append is applied inside commit!'s lock, so
   concurrent appends cannot overwrite each other. :seed carries the normalized
   legacy :members, used only if :cells does not exist yet."
  [st space-id cell]
  (let [o (sub/object st space-id)]
    (cond-> {:op :append :id space-id :path [:value :cells] :value cell}
      (nil? (get-in o [:value :cells])) (assoc :seed (cells-of o)))))
```

- [ ] **Step 4: Run the tests.** Green.

- [ ] **Step 5: Check every consumer of the log understands the new op.** Grep for `:op` and
      for `case`/`cond` over ops across `src/`. `dlv/touched-ids` reads `(:id ev)` generically
      and should need nothing, but **verify rather than assume** — and check `loci.migrate`'s
      parity verification, the time scrubber's counts, and anything in `dlv.clj` that
      inspects events. Report what you found.

- [ ] **Step 6: Prove the fix bites.** Revert `append-cell-event` to its old body, run the
      two race tests, confirm both fail with real numbers. Restore. Report the output.

- [ ] **Step 7: Prove it on the Datalevin path too.** The tests above use `fresh-store`. Run
      the same concurrent append against a `DatalevinStore` on a **throwaway** directory,
      reload it from disk, and assert all 24 cells are there. Nippy serializes the event, so
      this is the check that the new op survives a real round trip.

- [ ] **Step 8: Both suites once, and the substrate check**

```bash
clojure -M:test 2>&1 | tail -3        # expect 179+ tests, 0 failures
npm run test:browser 2>&1 | tail -6   # expect 37 pass
md5sum data/substrate.edn             # e7a6eecdff7bb44ad6b959bb1a7fb7c6
```

- [ ] **Step 9: Commit**

```bash
git add src/loci/substrate.clj src/loci/notebook.clj test/
git commit -m "fix: appending a cell is computed under the lock — 24 concurrent appends left 6"
```

## Out of scope, deliberately

- **`notebook-op!`'s edit/move/delete path** (`server.clj:417`) is the same whole-vector
  rewrite and races the same way. It is left alone because reorder and delete have no
  append-like commutative form — two concurrent reorders genuinely conflict, and picking a
  winner is a design question, not a bug fix. It is also far less exposed: those come from a
  person clicking, not from agent jobs running in parallel. **Say so in a comment at that
  call site** so the next reader knows it was considered, not missed.
- Datalevin phase 2 (projection + FTS) and phase 3 (embeddings) — next, separately.
