# One Substrate, Phase 1: memory facts into Datalevin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `loci.memory`'s fact storage out of `data/memory.edn` and into a `facts` dbi in the existing Datalevin environment, with atomic id minting, without changing recall behaviour.

**Architecture:** Introduce a `FactLog` protocol behind `append-line!` — the one function `memory.clj` documents as "the only way anything in this namespace writes a fact". Two implementations: `FileLog` (today's `spit :append true`) and `DbiLog` (Datalevin). Id minting moves into the protocol, so it can be a monotonic counter in `meta` rather than a max over an in-RAM map. `undo!` and `as-of` read the `events` dbi and are not touched.

**Tech Stack:** Clojure 1.12, Datalevin 1.0.0 KV API (`d/open-dbi`, `d/transact-kv`, `d/get-value`, `d/get-range`), `clojure.test` via `clojure -M:test`.

---

## Background the engineer needs

Read these before starting:

- `docs/superpowers/specs/2026-08-15-one-substrate-design.md` — the spec this implements.
- `src/loci/dlv.clj:51-61` (`open-env!`), `:91-104` (`commit-tx`/`undo-tx`), `:106-142` (`DatalevinStore`). The `locking lock` comment at `:108-118` is the pattern Task 4 copies.
- `src/loci/memory.clj:154-182` (`append-line!`, `load-facts`), `:184-201` (`next-id`), `:1064-1114` (`FileMemory.remember`), `:1217-1222` (`file-memory`, the `memory` singleton).

**Two facts that shape everything:**

1. `append-line!` is public and has **eight call sites across four namespaces** — verified by grep on 2026-08-15, not estimated:

   | namespace | lines |
   |---|---|
   | `memory.clj` | 296 (`store-vector!`), 557–558 (`merge!`), 793–794 (`unmerge!`), 1112 (`remember`) |
   | `classify.clj` | 255 |
   | `repair.clj` | 438 |
   | `server.clj` | 2576 |

   Six sites destructure `{:keys [!facts file]}` off the record to get the path: `memory.clj` 286/527/744, `classify.clj` 300, `repair.clj` 464, `server.clj` 2554. `server.clj:2529` carries a comment acknowledging that it "reaches for `{:keys [!facts file]}` from outside the namespace".

   **This is the task's real hazard.** Clojure destructuring binds a missing key to `nil` silently, so if `FileMemory` keeps a `file` field beside a new `log` field, all three out-of-namespace sites keep writing to an EDN file that nothing reads after Task 3 — and `server.clj:2576` is the user's own hand-made `durable-by "you"` judgement, the one value the codebase works hardest to protect. Changing `append-line!`'s *implementation* moves every path at once only if the old path is made impossible to reach.
2. `FileMemory.remember` currently takes **no lock**. `next-id` reads the atom, then `append-line!` writes, then `swap!` updates. Two threads interleave and mint the same id. This is a live bug and Task 4 fixes it.

**Never write to `data/`.** Every test and every manual run uses a throwaway directory. See `test/loci/memory_test.clj:6-7` for the `tmpfile` convention.

---

## File structure

| File | Responsibility |
|---|---|
| `src/loci/factlog.clj` | **new.** The `FactLog` protocol and `FileLog`. No Datalevin dependency, so `memory.clj` does not gain one. |
| `src/loci/dlv.clj` | **modify.** Open the `facts` dbi; expose `->DbiLog` and the `meta` counter. |
| `src/loci/memory.clj` | **modify.** `append-line!` and `next-id` delegate to a `FactLog`; add a lock. |
| `src/loci/factmigrate.clj` | **new.** `migrate-facts!`, dry-run by default, shaped like `loci.corpus`. |
| `test/loci/factlog_test.clj` | **new.** Protocol conformance, run against both implementations. |
| `test/loci/factmigrate_test.clj` | **new.** Migration, idempotence, refusals. |
| `test/loci/memory_dlv_test.clj` | **new.** Concurrency, undo/as-of isolation, recall parity. |

`DbiLog` lives in `dlv.clj` rather than `factlog.clj` so that `memory.clj` can require `factlog` without pulling in Datalevin — the same reason `memory.clj` does not require `dlv` today.

---

### Task 1: The `FactLog` protocol and `FileLog`

**Files:**
- Create: `src/loci/factlog.clj`
- Test: `test/loci/factlog_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns loci.factlog-test
  (:require [clojure.test :refer [deftest is]]
            [loci.factlog :as fl]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-fl-" (System/nanoTime) "/memory.edn"))

(deftest file-log-round-trips
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1" :fact "a" :ts 1})
    (fl/append! l {:id "mem-2" :fact "b" :ts 2})
    (is (= {"mem-1" {:id "mem-1" :fact "a" :ts 1}
            "mem-2" {:id "mem-2" :fact "b" :ts 2}}
           (fl/load-all l)))))

(deftest file-log-is-last-wins-by-id
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1" :fact "first"})
    (fl/append! l {:id "mem-1" :fact "second"})
    (is (= "second" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest file-log-mints-past-the-highest-id-ever-seen
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1"})
    (fl/append! l {:id "mem-7"})
    ;; not (inc (count …)) — mem-2 would collide with a live fact after a delete
    (is (= "mem-8" (fl/mint-id! l)))
    (is (= "mem-9" (fl/mint-id! l)))))

(deftest file-log-ignores-ids-of-another-shape
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "hand-edited"})
    (is (= "mem-1" (fl/mint-id! l)))))
```

- [ ] **Step 2: Run it and watch it fail**

Run: `clojure -M:test -n loci.factlog-test`
Expected: FAIL — `No such namespace: loci.factlog`

- [ ] **Step 3: Write the implementation**

```clojure
(ns loci.factlog
  "Where a fact is durably written, behind one protocol.

   `loci.memory` documents `append-line!` as the only way anything writes a
   fact. This makes that literal: every write path in that namespace goes
   through `append!`, so moving facts from a file into a database is a change
   of implementation and not a rewrite of the recall engine.

   **`mint-id!` is on the protocol and that is the point.** A file log can only
   answer 'what is the next id' by scanning what it has; a database can keep a
   counter and increment it inside the write transaction. Leaving minting in
   `loci.memory`, over an in-RAM map, is what lets two writers mint the same id
   and destroy one fact with no error anywhere."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defprotocol FactLog
  (append!  [this rec] "Persist one record. Last-wins by :id. Returns :ok.")
  (load-all [this]     "id → record, last-wins, tombstones included.")
  (mint-id! [this]     "A fact id no live or tombstoned record holds."))

(def ^:private id-pattern #"mem-(\d+)")

(defn id-ordinal
  "The <n> of a mem-<n> id, or nil for an id in any other shape.

   Public because both implementations need it and `loci.memory` sorts by it."
  [id]
  (when-let [[_ n] (re-matches id-pattern (str id))] (parse-long n)))

(defn- read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defrecord FileLog [file lock]
  FactLog
  (append! [_ rec]
    (locking lock
      (io/make-parents (io/file file))
      (spit file (str (pr-str rec) "\n") :append true)
      :ok))
  (load-all [_] (read-file file))
  ;; Scans on every call, which is what a flat file can do. `DbiLog` keeps a
  ;; counter instead. Both are inside the lock, so the read and the write that
  ;; depends on it cannot interleave.
  (mint-id! [_]
    (locking lock
      (str "mem-" (inc (reduce (fn [hi id] (max hi (or (id-ordinal id) 0)))
                               0 (keys (read-file file))))))))

(defn file-log [path] (->FileLog path (Object.)))
```

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n loci.factlog-test`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/loci/factlog.clj test/loci/factlog_test.clj
git commit -m "feat: a fact log behind one protocol, so storage can change without recall"
```

---

### Task 2: Route `loci.memory`'s writes through the protocol

No behaviour change. Every existing test must still pass — that is how you know the seam is right.

**Files:**
- Modify: `src/loci/memory.clj:154-201` (`append-line!`, `load-facts`, `next-id`, `id-ordinal`), `:1064` (`FileMemory`), `:1217-1222` (`file-memory`)
- Modify: `src/loci/classify.clj:255,300`
- Modify: `src/loci/repair.clj:438,464`
- Modify: `src/loci/server.clj:2529,2554,2576`

- [ ] **Step 1: Run the existing suite and record the baseline**

Run: `clojure -M:test`
Expected: PASS, 541 tests / 3582 assertions / 0 failures as of commit `92f2b6b`. **Write the numbers down** — Task 2 must end with the same ones.

- [ ] **Step 2: Replace the three private helpers with delegation**

In `src/loci/memory.clj`, add `[loci.factlog :as fl]` to the `:require`, then replace `append-line!` (`:154-166`), `load-facts` (`:168-176`), `id-pattern`/`id-ordinal` (`:178-182`) and `next-id` (`:184-201`) with:

```clojure
(defn append-line!
  "One record, appended to `log`. The only way anything in this namespace writes
   a fact.

   **Takes a FactLog, not a path.** It used to take a file and `spit` to it;
   the argument changed so that a fact can land in the substrate's database
   instead, and every caller moves with it rather than each learning where
   facts live.

   Still public for `loci.repair`, `loci.classify` and the server's durability
   toggle, all three of which append from outside this namespace and must
   append the way everything else does rather than go through `remember` and
   re-stamp `:ts`.

   **The assertion is not defensive programming.** Six call sites reach a
   memory's storage by destructuring `{:keys [!facts file]}`, and Clojure binds
   a missing key to nil without complaint. Once `:file` is gone, a site that
   was not updated would pass nil here and — with a bare `fl/append!` — fail
   somewhere further in with a message about a protocol. Asserting at the door
   names the caller's mistake instead."
  [log rec]
  (assert (satisfies? fl/FactLog log)
          (str "append-line! takes a FactLog, not " (pr-str log)
               " — a caller is still reaching for the old :file field"))
  (fl/append! log rec))

(def ^:private id-ordinal fl/id-ordinal)
```

`id-ordinal` keeps an alias because `memory.clj` uses it outside the log — `grep -n "id-ordinal" src/loci/memory.clj` finds it in the fact ordering at `:445`.

Delete `load-facts` and `next-id` entirely. `load-facts` took a path and had exactly one caller (`file-memory`, rewritten in Step 5); `mint-id!` replaces `next-id` and is wired in at Step 4.

- [ ] **Step 3: Rename the field, and make the old one impossible to reach**

Replace `(defrecord FileMemory [!facts file]` at `:1064` with `(defrecord FileMemory [!facts log]`.

**Remove `file` entirely — do not keep it alongside `log` "for compatibility".** Keeping it is the failure mode this step exists to prevent: the three out-of-namespace call sites would go on writing to an EDN file that nothing reads once Task 3 lands, and one of them (`server.clj:2576`) persists the user's own hand-made durability judgement.

Then update the in-namespace destructuring sites and their appends:

```bash
grep -n "!facts file\|append-line! file" src/loci/memory.clj
```

Expect six: destructuring at `:286` (`store-vector!`), `:527` (`merge!`), `:744` (`unmerge!`); appends at `:296`, `:557-558`, `:793-794`, `:1112`. Change each `{:keys [!facts file]}` to `{:keys [!facts log]}` and each `(append-line! file …)` to `(append-line! log …)`.

- [ ] **Step 4: Keep `next-id`'s call site compiling**

At `:1102`, `remember` currently reads `{:id (next-id @!facts) …}`. Change it to `{:id (fl/mint-id! log) …}`. Task 4 makes this atomic; for now it must merely compile and behave as before.

- [ ] **Step 5: Update the constructor**

Replace `file-memory` (`:1217-1219`) with:

```clojure
(defn file-memory
  "A memory backed by an EDN-lines file. Kept for tests and `loci.calibrate`,
   which requires an explicit path and reads only."
  ([] (file-memory (str (sub/data-dir) "/memory.edn")))
  ([path] (let [log (fl/file-log path)]
            (->FileMemory (atom (fl/load-all log)) log))))
```

**One log, bound once.** `(->FileMemory (atom (fl/load-all (fl/file-log path))) (fl/file-log path))` would build two `FileLog` records holding two different `(Object.)` monitors, so `mint-id!` and `append!` would serialise against different locks and the Task 4 fix would silently not apply.

- [ ] **Step 6: Fix all three out-of-namespace call sites**

These are the ones a rename alone would leave silently broken. Do all three:

```bash
grep -n "!facts file\|append-line!" src/loci/classify.clj src/loci/repair.clj src/loci/server.clj
```

| file | destructure | append |
|---|---|---|
| `src/loci/classify.clj` | `:300` | `:255` |
| `src/loci/repair.clj` | `:464` | `:438` |
| `src/loci/server.clj` | `:2554` | `:2576` |

In each, change `{:keys [!facts file]}` to `{:keys [!facts log]}` and `(mem/append-line! file …)` to `(mem/append-line! log …)`.

`src/loci/server.clj:2529` has a comment describing this reach-through by its old field name; update the prose so it does not send the next reader looking for `:file`.

- [ ] **Step 6b: Prove no site was missed**

```bash
grep -rn ":keys \[!facts file\]\|append-line! file" src/
```

Expected: **no output.** Any hit is a site that will write to a dead file after Task 3.

Then confirm the assertion fires rather than corrupting anything:

```bash
clojure -M -e "(require '[loci.memory :as mem]) (try (mem/append-line! \"some/path.edn\" {:id \"mem-1\"}) (catch Throwable t (println \"REFUSED:\" (.getMessage t)))) (System/exit 0)"
```

Expected: `REFUSED: … takes a FactLog, not "some/path.edn" …`. If it silently succeeds, the assertion is not wired up.

- [ ] **Step 7: Run the full suite**

Run: `clojure -M:test`
Expected: PASS, **with the same test and assertion counts as Step 1.** A different count means you changed behaviour, not just storage. Stop and find out why.

- [ ] **Step 8: Commit**

```bash
git add src/loci/memory.clj src/loci/repair.clj
git commit -m "refactor: memory writes go through the fact log, not a file path"
```

---

### Task 3: `DbiLog` — the Datalevin implementation

**Files:**
- Modify: `src/loci/dlv.clj:51-61` (`open-env!`), and append `DbiLog` at the end
- Test: `test/loci/factlog_test.clj` (extend)

- [ ] **Step 1: Write the failing test**

Append to `test/loci/factlog_test.clj`, and add `[loci.dlv :as dlv]` to its `:require`:

```clojure
(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-dbi-" (System/nanoTime) "/substrate"))

(deftest dbi-log-round-trips
  (let [st (dlv/datalevin-store (tmpdir))
        l  (dlv/fact-log st)]
    (fl/append! l {:id "mem-1" :fact "a" :ts 1})
    (fl/append! l {:id "mem-2" :fact "b" :ts 2})
    (is (= #{"mem-1" "mem-2"} (set (keys (fl/load-all l)))))
    (is (= "a" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest dbi-log-is-last-wins-by-id
  (let [l (dlv/fact-log (dlv/datalevin-store (tmpdir)))]
    (fl/append! l {:id "mem-1" :fact "first"})
    (fl/append! l {:id "mem-1" :fact "second"})
    (is (= 1 (count (fl/load-all l))))
    (is (= "second" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest dbi-log-counter-survives-a-reopen
  (let [dir (tmpdir)]
    (let [l (dlv/fact-log (dlv/datalevin-store dir))]
      (is (= "mem-1" (fl/mint-id! l)))
      (is (= "mem-2" (fl/mint-id! l))))
    ;; a counter that lived only in RAM would restart at mem-1 and destroy mem-2
    (let [l (dlv/fact-log (dlv/datalevin-store dir))]
      (is (= "mem-3" (fl/mint-id! l))))))

(deftest dbi-log-counter-starts-past-migrated-facts
  (let [dir (tmpdir)
        l   (dlv/fact-log (dlv/datalevin-store dir))]
    ;; a bulk write (the migration) puts records in without minting
    (fl/append! l {:id "mem-40" :fact "migrated"})
    (is (= "mem-41" (fl/mint-id! l)))))
```

- [ ] **Step 2: Run it and watch it fail**

Run: `clojure -M:test -n loci.factlog-test`
Expected: FAIL — `No such var: dlv/fact-log`

- [ ] **Step 3: Open the dbi**

In `src/loci/dlv.clj`, add one line to `open-env!` (`:56-61`):

```clojure
(let [kv (d/open-kv dir)]
  (d/open-dbi kv "events")
  (d/open-dbi kv "counts")
  (d/open-list-dbi kv "touched")
  (d/open-dbi kv "meta")
  (d/open-dbi kv "facts")
  kv)
```

- [ ] **Step 4: Implement `DbiLog`**

Append to `src/loci/dlv.clj`:

```clojure
(def ^:private next-id-key :facts/next-id)

(defrecord DbiLog [kv lock]
  fl/FactLog
  ;; `facts` is a parallel truth and is NEVER an event: nothing here writes to
  ;; `events`, so `undo!` cannot revoke a fact and `as-of` cannot time-travel
  ;; one. That is the whole record/recall separation, and after this change it
  ;; is enforced by which dbi a write names rather than by which file it opens.
  ;;
  ;; **The fact and the counter move in ONE transaction.** A bulk writer — the
  ;; migration — appends records without minting, so if the counter did not
  ;; advance here it would still read 0 after 248 facts landed, and the next
  ;; mint would hand out mem-1 onto a live record. Splitting them into two
  ;; transactions would let a crash land the fact and lose the bump, which is
  ;; the same failure one restart later. `commit-tx` groups events, counts and
  ;; touches for exactly this reason.
  (append! [_ rec]
    (locking lock
      (let [n (max (or (d/get-value kv "meta" next-id-key :keyword) 0)
                   (or (fl/id-ordinal (:id rec)) 0))]
        (d/transact-kv kv [[:put "facts" (:id rec) rec :string]
                           [:put "meta" next-id-key n :keyword]]))
      :ok))
  (load-all [_]
    (into {} (d/get-range kv "facts" [:all] :string)))
  ;; O(1), and durable. Reading the counter is one key lookup, not a scan of
  ;; every fact — `FileLog` was measured at 642 ms/call doing the scan version
  ;; against an 8.5 MB log, and there is no reason to reintroduce it here where
  ;; a counter is native. `append!` keeps the counter current, so this never
  ;; needs to consult `facts` at all.
  ;;
  ;; Durable is the half `FileLog` cannot match: its high-water atom dies with
  ;; the process, while this survives a restart.
  (mint-id! [_]
    (locking lock
      (let [n (inc (or (d/get-value kv "meta" next-id-key :keyword) 0))]
        (d/transact-kv kv [[:put "meta" next-id-key n :keyword]])
        (str "mem-" n)))))

(defn fact-log
  "The `FactLog` over a `DatalevinStore`'s environment.

   Takes the store rather than a directory so that facts land in the SAME
   environment as the events — which is the point of the change — and so that
   nothing can open a second env against the same directory by accident.

   **Seeds the counter if facts exist without one.** That state is reachable:
   a store written by an older path, or one hand-loaded, holds `mem-1…mem-n`
   and no `:facts/next-id`. Minting from a zero counter would then reissue
   `mem-1` onto a live fact and last-wins would destroy it — the failure the
   deleted `next-id` docstring called 'no longer hypothetical'. One scan, once,
   only when the key is absent."
  [st]
  (let [kv (:kv st)]
    (when (nil? (d/get-value kv "meta" next-id-key :keyword))
      (let [highest (reduce (fn [hi [id _]] (max hi (or (fl/id-ordinal id) 0)))
                            0 (d/get-range kv "facts" [:all] :string))]
        (when (pos? highest)
          (d/transact-kv kv [[:put "meta" next-id-key highest :keyword]]))))
    (->DbiLog kv (Object.))))
```

Add `[loci.factlog :as fl]` to `dlv.clj`'s `:require`.

- [ ] **Step 5: Run the tests**

Run: `clojure -M:test -n loci.factlog-test`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add src/loci/dlv.clj test/loci/factlog_test.clj
git commit -m "feat: facts get a dbi in the substrate's own environment"
```

---

### Task 4: Make minting atomic, and prove it

**Files:**
- Modify: `src/loci/memory.clj:1064-1114` (`FileMemory.remember`)
- Test: `test/loci/memory_dlv_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns loci.memory-dlv-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.memory :as mem]
            [loci.mold :as mold]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-mdlv-" (System/nanoTime) "/substrate"))

(deftest concurrent-remember-loses-no-fact
  (let [m  (mem/datalevin-memory (dlv/datalevin-store (tmpdir)))
        n  200
        ;; deliberately unrelated sentences: anything above jaccard 0.6 would
        ;; reinforce rather than insert, and the count would drop for a reason
        ;; that is not a lost write
        fs (map #(str "Fact number " % " concerns subject " % " exclusively.") (range n))]
    (doall (pmap #(mold/remember m % {:entities ["x"]}) fs))
    (is (= n (count (mem/all-facts m))))
    (is (= n (count (set (map :id (mem/all-facts m)))))
        "every fact got its own id")))
```

- [ ] **Step 2: Run it and watch it fail**

Run: `clojure -M:test -n loci.memory-dlv-test`
Expected: FAIL — `No such var: mem/datalevin-memory`.

Once Step 3 adds the constructor but before Step 4 adds the lock, it must fail *again*, with a count below 200. **Do not skip that intermediate run — it is the proof the test bites.**

- [ ] **Step 3: Add the constructor**

In `src/loci/memory.clj`, after `file-memory`:

```clojure
(defn datalevin-memory
  "A memory whose facts live in the substrate's own environment.

   Takes the store, so that `undo!`, `as-of` and `recall` are all reading one
   LMDB environment and a backup of `data/substrate/` is a backup of the facts
   too — which it was not while they lived in `data/memory.edn`."
  [st]
  (let [log (dlv/fact-log st)]
    (->FileMemory (atom (fl/load-all log)) log)))
```

Add `[loci.dlv :as dlv]` to `memory.clj`'s `:require`.

> **Circular dependency check:** `dlv.clj` now requires `factlog`, and `memory.clj` requires both. `dlv.clj` must NOT require `memory.clj`. Run `grep -n "loci.memory" src/loci/dlv.clj` — expect no output. If there is any, stop and report it rather than working around it.

- [ ] **Step 4: Run the test and confirm it FAILS on the count**

Run: `clojure -M:test -n loci.memory-dlv-test`
Expected: FAIL, with fewer than 200 facts — `remember` still mints and appends as two unsynchronised steps. Record the number you saw.

- [ ] **Step 5: Add the lock**

Add a `lock` field: `(defrecord FileMemory [!facts log lock]`, and update both constructors to pass `(Object.)`.

Then wrap the whole body of `remember` (`:1069-1114`) in `(locking lock …)`, so that the duplicate scan, the mint, the append and the `swap!` are one critical section:

```clojure
(remember [_ fact opts]
  (locking lock
    (let [ft  (tokens fact)
          dup (some (fn [f] (when (>= (jaccard ft (tokens (:fact f))) 0.6) f))
                    (live (vals @!facts)))
          rec (if dup
                ;; … unchanged …
                )]
      (append-line! log rec)
      (swap! !facts assoc (:id rec) rec)
      :ok)))
```

The duplicate scan must be inside the lock, not just the mint. Two threads that both see "no duplicate" and both insert produce two facts where reinforcement was correct — a different lost write, with the same cause.

- [ ] **Step 6: Run the test**

Run: `clojure -M:test -n loci.memory-dlv-test`
Expected: PASS, 200 distinct ids.

- [ ] **Step 7: Prove the test bites by reverting the fix**

Temporarily remove the `locking lock` wrapper, re-run, confirm FAIL, then put it back. A test that passes either way certifies nothing.

- [ ] **Step 8: Run the full suite and commit**

```bash
clojure -M:test
git add src/loci/memory.clj test/loci/memory_dlv_test.clj
git commit -m "fix: minting and appending a fact are one critical section"
```

---

### Task 5: Two processes, not two threads

The spec claims cross-process safety. Task 4 proved in-process only. This task establishes what is actually true and records it.

**Files:**
- Create: `dev/two_writers.clj`
- Modify: `docs/superpowers/specs/2026-08-15-one-substrate-design.md` (record the measured result)

- [ ] **Step 1: Write the harness**

```clojure
;; dev/two_writers.clj — run as:
;;   clojure -M -e "(load-file \"dev/two_writers.clj\")" <dir> <tag> <n>
;; Two JVMs, same dir, different tags, n facts each. Then count.
(require '[loci.dlv :as dlv] '[loci.memory :as mem] '[loci.mold :as mold])
(let [[dir tag n] *command-line-args*
      n (parse-long n)
      m (mem/datalevin-memory (dlv/datalevin-store dir))]
  (dotimes [i n]
    (mold/remember m (str "Writer " tag " fact " i " on subject " tag i ".") {:entities [tag]}))
  (println tag "wrote" n))
(shutdown-agents)
(System/exit 0)
```

> `*command-line-args*` is **not** populated by `clojure -M -e "…" args`. Pass the arguments via a JVM property (`-J-Dtwo-writers.tag=a`) and read them with `System/getProperty`, or use `clojure -X`. This exact mistake produced a silent live write on 2026-08-15 — verify the harness reads its arguments before trusting a run.

- [ ] **Step 2: Run two writers against one directory**

```bash
D=$(mktemp -d)/substrate
clojure -M -e "(load-file \"dev/two_writers.clj\")" "$D" a 100 &
clojure -M -e "(load-file \"dev/two_writers.clj\")" "$D" b 100 &
wait
```

- [ ] **Step 3: Count what survived**

```bash
clojure -M -e "(require '[loci.dlv :as dlv] '[loci.memory :as mem])
               (println (count (mem/all-facts (mem/datalevin-memory (dlv/datalevin-store \"$D\")))))
               (System/exit 0)"
```

- [ ] **Step 4: Record the number honestly**

Expected 200. **If it is 200, say so and say how many runs you did.** If it is fewer, LMDB's writer mutex does not serialise the read-then-write in `mint-id!` across processes, and the spec's cross-process claim is wrong.

In that case do NOT invent a fix in this task. Add a section to the spec titled "Cross-process: measured, not achieved" recording the number, and state the fallback: a second writer must fail loudly. That is still strictly better than today's silent last-wins, and it is the honest outcome.

- [ ] **Step 5: Commit the harness and the finding**

```bash
git add dev/two_writers.clj docs/superpowers/specs/2026-08-15-one-substrate-design.md
git commit -m "test: two writers, one environment — what actually holds"
```

---

### Task 6: `undo!` and `as-of` never touch a fact

**Files:**
- Test: `test/loci/memory_dlv_test.clj` (extend)

- [ ] **Step 1: Write the failing tests**

```clojure
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
        "undoing a notebook edit must not un-learn a fact")))

(deftest as-of-does-not-time-travel-the-memory
  (let [st (dlv/datalevin-store (tmpdir))
        m  (mem/datalevin-memory st)]
    (sub/commit! st {:op :put :id "doc:1" :value {:id "doc:1" :kind :doc :title "T" :value "x"}})
    (mold/remember m "Cobalt blue reached Delft through the Dutch trade." {:entities ["cobalt"]})
    (let [before (mold/recall m "cobalt" {:k 5})]
      (sub/as-of st 0)
      (is (= (mapv :id before) (mapv :id (mold/recall m "cobalt" {:k 5})))
          "recall answers the same regardless of where the substrate is scrubbed to"))))
```

Add `[loci.substrate :as sub]` to the test's `:require`.

- [ ] **Step 2: Run them**

Run: `clojure -M:test -n loci.memory-dlv-test`
Expected: PASS immediately — `DbiLog.append!` writes only to `facts`, and `undo-tx`/`as-of` name only `events`, `counts` and `touched`.

These are regression tests, not drivers. They pass on arrival by construction; their job is to fail the day somebody adds a fact to `commit-tx`.

- [ ] **Step 3: Prove they bite**

Temporarily change `DbiLog.append!` to also write `[[:put "events" 999 rec :long]]`, re-run, confirm `undo-does-not-revoke-a-fact` fails, then revert. Record what you saw.

- [ ] **Step 4: Commit**

```bash
git add test/loci/memory_dlv_test.clj
git commit -m "test: undo and as-of leave the memory alone, and will say so if that changes"
```

---

### Task 7: Recall parity — the migrated store answers identically

**Files:**
- Test: `test/loci/memory_dlv_test.clj` (extend)

- [ ] **Step 1: Write the test**

```clojure
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
    (doseq [q ["taiwan chips" "cobalt" "petrol ban" "vacuum" "inflation"]]
      (is (= (mapv :fact (mold/recall fm q {:k 5}))
             (mapv :fact (mold/recall dm q {:k 5})))
          (str "ranking differs for query: " q)))))
```

Facts, not ids: both backends mint `mem-1`…`mem-5` in the same order, so comparing ids would pass even if the ranking were wrong.

- [ ] **Step 2: Run it**

Run: `clojure -M:test -n loci.memory-dlv-test`
Expected: PASS. A failure here means storage leaked into scoring, which this change is not permitted to do.

- [ ] **Step 3: Commit**

```bash
git add test/loci/memory_dlv_test.clj
git commit -m "test: storage changed, ranking did not"
```

---

### Task 8: The migration

**Files:**
- Create: `src/loci/factmigrate.clj`
- Test: `test/loci/factmigrate_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(ns loci.factmigrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.factlog :as fl]
            [loci.factmigrate :as fm]))

(defn- tmpdir [] (str (System/getProperty "java.io.tmpdir") "/loci-fm-" (System/nanoTime)))

(defn- seeded [dir n]
  (let [path (str dir "/memory.edn")
        l    (fl/file-log path)]
    (dotimes [i n] (fl/append! l {:id (str "mem-" (inc i)) :fact (str "Fact " (inc i) ".")
                                  :entities ["x"] :ts (inc i) :strength 1}))
    path))

(deftest dry-run-writes-nothing
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))
        r  (fm/migrate-facts! st path)]
    (is (:ok? r))
    (is (:dry-run? r))
    (is (= 5 (get-in r [:plan :count])))
    (is (empty? (fl/load-all (dlv/fact-log st))) "a dry run must not write")))

(deftest live-run-moves-every-fact
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))
        r  (fm/migrate-facts! st path {:dry-run? false})]
    (is (:ok? r))
    (is (= 5 (:migrated r)))
    (is (= 5 (count (fl/load-all (dlv/fact-log st)))))
    (is (= "Fact 3." (:fact (get (fl/load-all (dlv/fact-log st)) "mem-3"))))))

(deftest second-run-is-a-no-op
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fm/migrate-facts! st path {:dry-run? false})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (:ok? r))
      (is (:already-done? r))
      (is (= 0 (:migrated r)))
      (is (= 5 (count (fl/load-all (dlv/fact-log st))))))))

(deftest refuses-a-fact-with-no-id
  (let [d (tmpdir), path (seeded d 2)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fl/append! (fl/file-log path) {:fact "no id here"})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (re-find #"no :id" (:error r)))
      (is (empty? (fl/load-all (dlv/fact-log st))) "a refusal writes nothing"))))

(deftest refuses-when-the-target-already-holds-different-facts
  (let [d (tmpdir), path (seeded d 2)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fl/append! (dlv/fact-log st) {:id "mem-1" :fact "something else entirely"})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (re-find #"mem-1" (:error r))))))

(deftest counter-is-set-past-the-migrated-facts
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fm/migrate-facts! st path {:dry-run? false})
    (is (= "mem-6" (fl/mint-id! (dlv/fact-log st)))
        "the next fact must not reissue mem-1 onto a migrated record")))
```

- [ ] **Step 2: Run and watch them fail**

Run: `clojure -M:test -n loci.factmigrate-test`
Expected: FAIL — `No such namespace: loci.factmigrate`

- [ ] **Step 3: Implement**

```clojure
(ns loci.factmigrate
  "`data/memory.edn` into the substrate's `facts` dbi. **Dry run by default.**

   Shaped like `loci.corpus`, for its reasons: nothing runs on require, every
   refusal is a returned report rather than a throw, the whole world is checked
   before any of it is written, and the plan a dry run returns is the value a
   live run reports.

   **The source file is never deleted, never renamed and never written.** It is
   the rollback, and reversing this migration is pointing the config back at it.
   Deleting it on success would make a successful migration the moment the
   rollback stopped existing."
  (:require [loci.factlog :as fl]
            [loci.dlv :as dlv]))

(defn migrate-facts!
  "Copy every record in `path` into `st`'s `facts` dbi.

   Returns a report, never throws:

     :ok? :dry-run? :already-done?
     :plan     {:count n :ids [...]}
     :migrated how many records were written
     :error    why not, when not"
  ([st path] (migrate-facts! st path {}))
  ([st path {:keys [dry-run?] :or {dry-run? true}}]
   (let [src   (fl/load-all (fl/file-log path))
         log   (dlv/fact-log st)
         dst   (fl/load-all log)
         no-id (some (fn [[id rec]] (when (nil? id) rec)) src)
         clash (some (fn [[id rec]]
                       (when-let [there (get dst id)]
                         (when (not= (:fact there) (:fact rec)) [id there rec])))
                     src)]
     (cond
       (empty? src)
       {:ok? false :dry-run? dry-run? :error (str path " holds no facts")}

       no-id
       {:ok? false :dry-run? dry-run?
        :error (str "a record in " path " has no :id, so it cannot be keyed or "
                    "reconciled: " (pr-str no-id))}

       clash
       (let [[id there rec] clash]
         {:ok? false :dry-run? dry-run?
          :error (str "the target already holds " id " with different text — "
                      "refusing rather than overwriting it. target: "
                      (pr-str (:fact there)) " source: " (pr-str (:fact rec)))})

       (every? #(contains? dst %) (keys src))
       {:ok? true :dry-run? dry-run? :already-done? true :migrated 0
        :plan {:count 0 :ids []}}

       :else
       (let [todo (into {} (remove (fn [[id _]] (contains? dst id))) src)
             plan {:count (count todo) :ids (vec (sort (keys todo)))}]
         (if dry-run?
           {:ok? true :dry-run? true :plan plan}
           (do
             ;; No counter bump here. `DbiLog.append!` advances `:facts/next-id`
             ;; in the same transaction as each record, so by the time this
             ;; doseq ends the counter is already past everything migrated.
             ;; Calling `mint-id!` to "make sure" would burn a live id and
             ;; leave a hole for no reason.
             (doseq [[_ rec] todo] (fl/append! log rec))
             {:ok? true :dry-run? false :plan plan :migrated (count todo)})))))))
```

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n loci.factmigrate-test`
Expected: PASS, 6 tests.

> `counter-is-set-past-the-migrated-facts` expects exactly `mem-6` after five facts migrate — no gap. If you see `mem-7`, something bumped the counter an extra time; find it rather than adjusting the expectation. **Do not change a test to match the code without establishing which one is right.** Task 2's review found a repaired test that had silently stopped detecting the bug it was named after; that is the failure mode to avoid here.

- [ ] **Step 5: Commit**

```bash
git add src/loci/factmigrate.clj test/loci/factmigrate_test.clj
git commit -m "feat: memory.edn into the substrate, rehearsed before it runs"
```

---

### Task 9: Wire the server, and rehearse against a copy of the real data

**Files:**
- Modify: `src/loci/memory.clj:1221-1222` (the `memory` singleton)
- Modify: `src/loci/server.clj` — wherever `mem/memory` is deref'd

- [ ] **Step 1: Find every consumer**

```bash
grep -rn "mem/memory\|@mem/memory" src/ test/
```

Read each one before changing anything. Report how many there are.

- [ ] **Step 2: Point the singleton at the substrate**

The singleton is `(delay (file-memory))` and resolves `data-dir` at first use. It must become the Datalevin memory over the server's own store. Because the store is created in `server/-main` rather than being globally available, **this needs a decision, not a guess**: either the singleton takes the store lazily via an atom the server sets at boot, or `mem/memory` is deleted and the store's memory is threaded through the call sites found in Step 1.

Prefer threading it through — a `delay` that depends on another component's boot order is the shape that made `data/` reachable from a bare REPL in the first place. If the call-site count from Step 1 makes that impractical, say so with the number and propose the alternative.

- [ ] **Step 3: Run both suites**

```bash
clojure -M:test
npm run test:browser
```

Both must pass. `clojure -M:test` alone is not "all the tests".

- [ ] **Step 4: Rehearse on a COPY of the real data**

Never against `data/`. Copy `data.mdb` and `VERSION` but **not** `lock.mdb` — a copied `lock.mdb` hangs Datalevin indefinitely with no error:

```bash
S=$(mktemp -d)
mkdir -p "$S/substrate"
cp data/substrate/data.mdb data/substrate/VERSION "$S/substrate/"
cp data/memory.edn "$S/"
md5sum data/substrate/data.mdb data/memory.edn   # record; must be unchanged after
```

Then dry-run the migration against the copy, print the plan, and run it live against the copy only.

- [ ] **Step 5: Report actual numbers**

State: how many facts were in `memory.edn`, how many landed in the dbi, whether the two sets of ids are equal, and what `recall` returns for three queries on each side. 248 facts are expected as of 2026-08-15. **Confirm `md5sum` on the real `data/` is unchanged.**

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: the server's memory is the substrate's memory"
```

Do NOT migrate the real `data/` in this task. That is a separate, deliberate act the user runs, after taking a backup, once these numbers are on the table.

---

## Out of scope

- `data/chunks.edn` (16 MB). Phase 2, and a separate plan. It is derived and rebuildable — delete it and re-sweep — so it carries no data-loss risk and no id to collide.
- Any change to recall scoring, decay, merge thresholds or the embedding path.
- Deleting `data/memory.edn`. It is the rollback until a later session retires it.

---

## Corrections — what this plan got wrong

Recorded after execution, so that a reader following the tasks above does not repeat them. Four of the six defects found during Tasks 1-4 originated here, in the plan, rather than in any implementation — which is the pattern `CLAUDE.md` describes.

**Task 1 — `FileLog.mint-id!` returned the same id twice.** As written it only scanned the file, so two mints with no `append!` between them repeated. The plan's own fourth test caught it. Fixed with a high-water atom, then made O(1) (see below).

**Task 1/2 — the seam has nine callers, not five, and three are in other namespaces.** `classify.clj:255`, `repair.clj:438` and `server.clj:2576` reach into `FileMemory` by destructuring. Clojure binds a missing destructured key to `nil` in silence, so renaming the field while keeping the old one would have left all three writing to a file nothing reads. `server.clj:2576` persists the user's own `durable-by "you"` judgement.

**Everywhere — `clojure -M:test -v <ns>` does not select a namespace.** `-v` selects vars. It is `-n`. Corrected in eleven places.

**Task 2/3 — rescanning the log on every mint cost 642 ms/call.** Measured against the real 8.5 MB `memory.edn`: `remember` went 5.85 ms → 642 ms, essentially all of it parsing 404,480 embedding doubles to find the largest integer in the ids. `FileLog` now seeds a counter once and `append!` advances it (2.13 ms/call, better than the original). `DbiLog` keeps the counter in `meta` and advances it in the *same transaction* as the record.

**Task 4 — the concurrency corpus was wrong in both directions.** The plan's sentences carried a comment claiming they were below the 0.6 reinforcement threshold; they measure **0.7143** between every pair, so correct locked behaviour collapses 200 facts to 1. And once made genuinely dissimilar, **200/200 survive with no lock at all**, because `DbiLog`'s `append!` and `mint-id!` are already individually atomic — a corpus with no possible duplicates cannot exercise the race the lock guards, which is the duplicate-scan racing the insert decision. The shipped test uses 20 topics × 10 concurrent identical texts: 122/106/103 unlocked, 20/20/20 locked.

**Task 6 — the `undo!` test could not have failed.** Two compounding errors. The specified sabotage (writing to `events` from `append!`) is inert, because `undo!` and `object` read the store's in-RAM `!db` and never rescan. Worse, the assertion read `all-facts` from the *same* memory instance before and after, and that instance never re-reads `facts` after construction — so no storage-level corruption of any kind could have failed it. It was a certifying test. The shipped version asserts against a **freshly constructed** memory after the undo, which does fail when `undo-tx` is sabotaged to delete from `facts`.

The general lesson, and the one worth carrying to Phase 2: **a plan's assertion about a threshold, a similarity, or a cost is a guess until someone measures it.** Every number in the corrections above came from running the code; none were visible by reading it.
