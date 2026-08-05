# Datalevin Store (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the EDN-lines log with a Datalevin-backed `Store` implementation that behaves *identically* — proven by running the existing substrate test suite against both — while removing `pr-str`/`read-string` from the write path and building the touch index that later phases need.

**Architecture:** `DatalevinStore` keeps the log vector and the materialized state in memory (RAM stays the hot path — this is what makes `as-of`/`frozen-at`/`history` behave exactly as today) with Datalevin as the durable, indexed backing. `commit!` writes one LMDB transaction and updates both atoms; `state`/`objects`/`object` read the state atom instead of re-folding (the current 10.6 ms-per-request cost). A `touched` list dbi records which events touched which object, and a `counts` dbi records object/space counts per event index — both built now, exercised in phase 2.

**Tech Stack:** Clojure 1.12, Datalevin 1.0.0 (LMDB), cognitect test-runner. Java 26 requires `--enable-native-access=ALL-UNNAMED`.

**Spec:** `docs/superpowers/specs/2026-08-05-datalevin-substrate-design.md` (phase 1 only).

**Decisions locked before writing this plan:**
- **Counts live in their own `counts` dbi, NOT stamped inside the event map.** The spec's §1 said "stamped at commit"; putting them in the event would change the event shape, breaking both migration-as-pure-copy and the parity tests. Events stay byte-identical to what the EDN store writes.
- **`as-of` stays a prefix fold** (`materialize (take n @!log)`), identical to today. Lazy per-object reconstruction lands as a *new* function `object-at`, tested here but not wired into read paths until phase 2 — it only pays past ~10⁵ events, and phase 1's job is parity.
- **`touched` is written immediately after the event transaction, not inside it.** Whether `transact-kv` accepts list-dbi ops was not verified during the spike, and this plan does not guess. `touched` is derived, so a crash between the two costs a rebuild, never correctness — and Task 3 ships that rebuild.
- **No head snapshot.** Boot folds the log: ~2 ms at today's 80 events, 227 ms at 10k. See the spec's §1 "Boot".

**Facts about the codebase the implementer needs:**
- `Store` is 7 fns: `commit! state objects object history undo! as-of` (`src/loci/substrate.clj:19`). `FrozenStore` (`:175`) wraps a materialized snapshot plus a log prefix; `frozen-at` (`:185`) calls `(history st)` and takes `n` — so any store whose `history` returns the full event vector gets time travel for free.
- `commit!` must return the new event count (callers use it), and `undo!` the count after popping.
- `safe-event` (`:44` region) does two things: `normalize-keys` (unreadable keyword keys → `col-kw`) and a `pr-str` round-trip check. Datalevin needs the normalization (a keyword with spaces would poison datalog attributes and FTS tokens later) but not the round-trip check. `normalize-keys` is currently `defn-` and must be made public.
- The store singleton is `content.clj:499` — `(defonce store (delay (let [s (sub/persistent-store)] (if (seq (sub/history s)) s (seed! s)))))`.
- `sub/data-dir` reads `loci.data-dir` property or `LOCI_DATA` env, defaulting to `"data"`.
- Baseline: **80 tests / 280 assertions green** on the current working tree. Run with `clojure -M:test`.
- The working tree currently has uncommitted changes to `substrate.clj`, `tools.clj`, `index.html` and two test files (this morning's data-loss fix and UI nits). Commit or stash them before Task 1 — this plan is written against them being present.

---

### Task 1: dependency + native-access flag, with the sci conflict verified

Datalevin 1.0.0 pulls `org.babashka/sci 0.13.53`; loci pins `0.8.43` for the `compute-clj!` sandbox. This task proves the existing suite survives that before any code depends on it.

**Files:**
- Modify: `deps.edn`

- [ ] **Step 1: Add the dependency and the JVM flag.**

In `:deps`, after the `org.babashka/sci` entry:

```clojure
        datalevin/datalevin   {:mvn/version "1.0.0"}
```

Then add `:jvm-opts` to the three aliases that run code (`:serve`, `:demo`, `:test`). LMDB is loaded through JavaCPP; without the flag Java 26 prints restricted-method warnings and a future JDK will refuse outright:

```clojure
  {:serve {:main-opts ["-m" "loci.server"]
           :jvm-opts  ["--enable-native-access=ALL-UNNAMED"]}

   :demo  {:main-opts ["-m" "loci.demo"]
           :jvm-opts  ["--enable-native-access=ALL-UNNAMED"]}

   :test  {:extra-paths ["test"]
           :extra-deps  {io.github.cognitect-labs/test-runner
                         {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
           :main-opts   ["-m" "cognitect.test-runner"]
           :jvm-opts    ["--enable-native-access=ALL-UNNAMED"]}
```

- [ ] **Step 2: Verify the sci-dependent tests still pass.**

Run: `clojure -M:test`

Expected: `Ran 80 tests containing 280 assertions. 0 failures, 0 errors.` — the same baseline as before the dependency was added.

The tests that matter here are `fn-apply-runs-agent-written-sci-fns`, `fn-preview-commits-nothing` and `fn-apply-commits-one-tx-with-provenance` in `test/loci/server_test.clj`; they exercise `run-clj-rows`, which is the only sci consumer.

- [ ] **Step 3: If and only if step 2 failed, pin sci explicitly.**

Clojure's dependency resolution lets a directly-declared version win over a transitive one. Add to `:deps` (keep the existing entry, this is the same coordinate):

```clojure
        org.babashka/sci      {:mvn/version "0.8.43"}
```

Re-run `clojure -M:test` and confirm 80/280 green. If it passes only with the pin, record that in the commit message — phase 3 will need to know.

- [ ] **Step 4: Confirm which sci version is actually on the classpath.**

Run: `clojure -Stree | grep -i "sci"`

Expected: a single `org.babashka/sci` line. Note the version in the commit message.

- [ ] **Step 5: Commit.**

```bash
git add deps.edn
git commit -m "deps: datalevin 1.0.0 + --enable-native-access; sci conflict verified"
```

---

### Task 2: `DatalevinStore` — the Store protocol over LMDB

**Files:**
- Create: `src/loci/dlv.clj`
- Modify: `src/loci/substrate.clj` (make `normalize-keys` public)
- Test: `test/loci/dlv_test.clj`

- [ ] **Step 1: Make `normalize-keys` public.**

In `src/loci/substrate.clj`, change the definition from `defn-` to `defn`:

```clojure
(defn normalize-keys
  "Only keys can poison a line — a string value with spaces is fine, a keyword
   key with spaces is not. Walk the event and fix the keys."
  [event]
  (walk/postwalk
   (fn [n] (if (map? n)
             (reduce-kv (fn [m k v] (assoc m (cond-> k (keyword? k) col-kw) v)) {} n)
             n))
   event))
```

- [ ] **Step 2: Write the failing test.**

Create `test/loci/dlv_test.clj`:

```clojure
(ns loci.dlv-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-dlv-" (System/nanoTime)))

(deftest datalevin-store-commits-and-replays
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
    (is (= "world" (:value (sub/object s "a"))))
    (is (= 2 (count (sub/history s))))
    (dlv/close! s)
    ;; a brand-new store over the same directory replays to identical state
    (let [s2 (dlv/datalevin-store dir)]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a"))))
      (dlv/close! s2))))

(deftest datalevin-undo-pops-and-persists
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (is (= 1 (sub/undo! s)))
    (is (nil? (sub/object s "b")))
    (dlv/close! s)
    (let [s2 (dlv/datalevin-store dir)]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b")))
      (dlv/close! s2))))

(deftest datalevin-as-of-and-frozen-at-work-unchanged
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "1'"})
    (is (= "1" (get-in (sub/as-of s 2) [:objects "a" :value])))
    (let [fz (sub/frozen-at s 2)]
      (is (= "1" (:value (sub/object fz "a"))))
      (is (= "B" (:title (sub/object fz "b"))))
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}}))))
    (is (= "1'" (:value (sub/object s "a"))))
    (dlv/close! s)))

(deftest datalevin-normalizes-unreadable-keys
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "t"
                    :value {:id "t" :kind :table
                            :value [{(keyword "Key Proponent(s)") "Canon" :year 2026}]}})
    (is (= [{:key_proponent_s "Canon" :year 2026}] (:value (sub/object s "t"))))
    (dlv/close! s)))
```

- [ ] **Step 3: Run the tests to verify they fail.**

Run: `clojure -M:test -n loci.dlv-test`

Expected: FAIL — `Could not locate loci/dlv__init.class` (the namespace does not exist yet).

- [ ] **Step 4: Write the implementation.**

Create `src/loci/dlv.clj`:

```clojure
(ns loci.dlv
  "Layer 1, durable: the same append-only event log, on Datalevin (LMDB).

   The log vector and the materialized state stay in RAM — that is what keeps
   `history`, `as-of` and `frozen-at` behaving exactly as they did over the EDN
   log, and what takes `state` off the per-request re-fold. Datalevin is the
   durable, indexed backing: events serialize natively (nippy), so there is no
   pr-str/read-string anywhere in the write path and a half-written or
   unreadable line cannot exist.

   Two derived dbis are maintained for later phases:
     · `touched` — object id → the event indices that touched it (lazy as-of)
     · `counts`  — event index → {:objects n :spaces n} (the ⏱ header, O(1))
   Both are rebuildable from `events`; `rebuild-indices!` does it."
  (:require [datalevin.core :as d]
            [loci.substrate :as sub]))

(defn- touched-ids
  "Which object ids an event writes. A :tx touches everything its sub-events do."
  [ev]
  (case (:op ev)
    :tx (into #{} (mapcat touched-ids) (:events ev))
    (if-let [id (:id ev)] #{id} #{})))

(defn- count-pair [state]
  {:objects (count (:objects state))
   :spaces  (count (filter #(= :space (:kind %)) (vals (:objects state))))})

(defn- open-env! [dir]
  (let [kv (d/open-kv dir {:mapsize 4096})]     ; MB — see map-full! below
    (d/open-dbi kv "events")
    (d/open-dbi kv "counts")
    (d/open-list-dbi kv "touched")
    kv))

(defn- map-full!
  "LMDB preallocates a maximum size and refuses writes once it is reached. Say
   so in words the caller can act on, instead of leaking MDB_MAP_FULL upward."
  [^Exception e dir]
  (if (re-find #"(?i)map.?full" (str (.getMessage e)))
    (throw (ex-info (str "the substrate is full — LMDB's map size at " dir
                         " is exhausted. Raise :mapsize (MB) in open-env! and restart; "
                         "nothing was written and nothing was lost.")
                    {:dir dir} e))
    (throw e)))

(defn- read-log [kv]
  (into [] (map second) (d/get-range kv "events" [:all] :long)))

(defrecord DatalevinStore [kv dir !log !state]
  sub/Store
  (commit! [_ event]
    (let [ev (sub/normalize-keys (assoc event :ts (System/currentTimeMillis)))
          i  (inc (count @!log))
          st (swap! !state sub/apply-event ev)]
      (try
        (d/transact-kv kv [[:put "events" i ev :long]
                           [:put "counts" i (count-pair st) :long]])
        (catch Exception e (map-full! e dir)))
      ;; `touched` is derived — a crash between these two costs a rebuild, not data
      (doseq [id (touched-ids ev)]
        (d/put-list-items kv "touched" id [i] :string :long))
      (count (swap! !log conj ev))))
  (state   [_] @!state)
  (objects [_] (:objects @!state))
  (object  [_ id] (get-in @!state [:objects id]))
  (history [_] @!log)
  (undo!   [_]
    (let [i (count @!log)]
      (when (pos? i)
        (let [ev  (peek @!log)
              log (swap! !log pop)]
          (d/transact-kv kv [[:del "events" i :long] [:del "counts" i :long]])
          (doseq [id (touched-ids ev)]
            (d/del-list-items kv "touched" id [i] :string :long))
          (reset! !state (sub/materialize log))))
      (count @!log)))
  (as-of   [_ n] (sub/materialize (take n @!log))))

(defn datalevin-store
  "Open (or create) a durable store in `dir`. Defaults to <data-dir>/substrate."
  ([] (datalevin-store (str (sub/data-dir) "/substrate")))
  ([dir]
   (let [kv  (open-env! dir)
         log (read-log kv)]
     (->DatalevinStore kv dir (atom log) (atom (sub/materialize log))))))

(defn close! [st] (d/close-kv (:kv st)))
```

- [ ] **Step 5: Run the tests to verify they pass.**

Run: `clojure -M:test -n loci.dlv-test`

Expected: `Ran 4 tests containing 14 assertions. 0 failures, 0 errors.`

- [ ] **Step 6: Run the whole suite.**

Run: `clojure -M:test`

Expected: `Ran 84 tests containing 294 assertions. 0 failures, 0 errors.`

- [ ] **Step 7: Commit.**

```bash
git add src/loci/dlv.clj src/loci/substrate.clj test/loci/dlv_test.clj
git commit -m "feat: DatalevinStore — the event log on LMDB, state served from RAM"
```

---

### Task 3: touch index, counts, `object-at`, and rebuild

The index written in Task 2 is now proven correct and given its reader. `object-at` is the lazy per-object reconstruction that phase 2 wires into `?at=` read paths.

**Files:**
- Modify: `src/loci/dlv.clj`
- Test: `test/loci/dlv_test.clj`

- [ ] **Step 1: Write the failing tests.**

Append to `test/loci/dlv_test.clj`:

```clojure
(deftest object-at-equals-a-full-prefix-fold
  ;; the lazy path must agree with the honest one, at EVERY point in history
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :value 10}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
    (sub/commit! s {:op :tx :events [{:op :assoc :id "b" :path [:value] :value 11}
                                     {:op :put :id "c" :value {:id "c" :kind :doc :value 100}}]})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value 3})
    (doseq [n (range 1 6), id ["a" "b" "c"]]
      (is (= (get-in (sub/as-of s n) [:objects id]) (dlv/object-at s id n))
          (str "object-at disagreed for " id " at event " n)))
    (dlv/close! s)))

(deftest counts-match-a-real-count-at-every-event
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "space:x" :value {:id "space:x" :kind :space :value {}}})
    (sub/commit! s {:op :put :id "d" :value {:id "d" :kind :doc :value 1}})
    (sub/commit! s {:op :put :id "space:y" :value {:id "space:y" :kind :space :value {}}})
    (doseq [n (range 1 4)]
      (let [objs (:objects (sub/as-of s n))]
        (is (= {:objects (count objs)
                :spaces  (count (filter #(= :space (:kind %)) (vals objs)))}
               (dlv/counts-at s n))
            (str "counts disagreed at event " n))))
    (dlv/close! s)))

(deftest rebuild-indices-restores-a-wiped-touch-index
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
    (dlv/clear-indices! s)
    (is (nil? (dlv/object-at s "a" 2)))            ; index gone → honest nil
    (dlv/rebuild-indices! s)
    (is (= 2 (:value (dlv/object-at s "a" 2))))    ; and it comes back from the log
    (dlv/close! s)))
```

- [ ] **Step 2: Run the tests to verify they fail.**

Run: `clojure -M:test -n loci.dlv-test`

Expected: FAIL — `No such var: dlv/object-at`.

- [ ] **Step 3: Write the implementation.**

Add to `src/loci/dlv.clj`, after `close!`:

```clojure
(defn object-at
  "One object as it was after event `n` — folding only the events that touched
   it. Cost is the object's own history, not the world's: measured 0.2 ms
   against a 10M-event log where a full-state fold took 642 ms."
  [st id n]
  (let [idxs (take-while #(<= % n) (d/get-list (:kv st) "touched" id :string :long))]
    (when (seq idxs)
      (-> (reduce (fn [state i]
                    (sub/apply-event state (d/get-value (:kv st) "events" i :long)))
                  {:objects {}} idxs)
          (get-in [:objects id])))))

(defn counts-at
  "{:objects n :spaces n} after event `n` — an O(1) read, never a scan."
  [st n]
  (d/get-value (:kv st) "counts" n :long))

(defn clear-indices!
  "Drop the derived dbis. They are rebuildable; the log is untouched."
  [st]
  (d/clear-dbi (:kv st) "touched")
  (d/clear-dbi (:kv st) "counts"))

(defn rebuild-indices!
  "Replay the log to rebuild `touched` and `counts`. Safe to run at any time —
   a stale or missing index is a rebuild, never a data loss."
  [st]
  (clear-indices! st)
  (reduce (fn [state [i ev]]
            (let [state' (sub/apply-event state ev)]
              (d/transact-kv (:kv st) [[:put "counts" i (count-pair state') :long]])
              (doseq [id (touched-ids ev)]
                (d/put-list-items (:kv st) "touched" id [i] :string :long))
              state'))
          {:objects {}}
          (map-indexed (fn [k ev] [(inc k) ev]) @(:!log st)))
  :ok)
```

- [ ] **Step 4: Run the tests to verify they pass.**

Run: `clojure -M:test -n loci.dlv-test`

Expected: `Ran 7 tests containing 40 assertions. 0 failures, 0 errors.`

- [ ] **Step 5: Commit.**

```bash
git add src/loci/dlv.clj test/loci/dlv_test.clj
git commit -m "feat: touch index + per-event counts — lazy object-at, O(1) scrubber header"
```

---

### Task 4: parity — the existing suite runs against both stores

This is the task that makes the swap safe. Rather than trusting that `DatalevinStore` behaves like `PersistentStore`, run the same assertions against both.

**Files:**
- Modify: `test/loci/substrate_test.clj`

- [ ] **Step 1: Add the flavour helper.**

Both records already carry what the helper needs — `PersistentStore` has `:file`, `DatalevinStore` has `:dir`. At the top of `test/loci/substrate_test.clj`, after the `tmpfile` helper:

```clojure
(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-parity-" (System/nanoTime)))

(defn- flavours
  "Both durable Store implementations, as [label open-fn reopen-fn close-fn].
   `open-fn` takes no args and returns a fresh store over a fresh location;
   `reopen-fn` takes that store and returns a NEW store over the same data."
  []
  [["edn" (fn [] (sub/persistent-store (tmpfile)))
          (fn [s] (sub/persistent-store (:file s)))
          (fn [_])]
   ["dlv" (fn [] (dlv/datalevin-store (tmpdir)))
          (fn [s] (dlv/close! s) (dlv/datalevin-store (:dir s)))
          (fn [s] (dlv/close! s))]])
```

- [ ] **Step 2: Write the parity tests.**

Add to `test/loci/substrate_test.clj`:

```clojure
(deftest both-stores-replay-identically
  (doseq [[label open reopen close] (flavours)]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
      (is (= "world" (:value (sub/object s "a"))) label)
      (let [s2 (reopen s)]
        (is (= 2 (count (sub/history s2))) label)
        (is (= "world" (:value (sub/object s2 "a"))) label)
        (close s2)))))

(deftest both-stores-undo-identically
  (doseq [[label open reopen close] (flavours)]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (is (= 1 (sub/undo! s)) label)
      (is (nil? (sub/object s "b")) label)
      (let [s2 (reopen s)]
        (is (= 1 (count (sub/history s2))) label)
        (is (nil? (sub/object s2 "b")) label)
        (close s2)))))

(deftest both-stores-time-travel-identically
  (doseq [[label open _ close] (flavours)]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "1'"})
      (is (= "1" (get-in (sub/as-of s 2) [:objects "a" :value])) label)
      (let [fz (sub/frozen-at s 2)]
        (is (= "1" (:value (sub/object fz "a"))) label)
        (is (= 2 (count (sub/history fz))) label)
        (is (thrown? UnsupportedOperationException (sub/undo! fz)) label))
      (is (= "1'" (:value (sub/object s "a"))) label)
      (close s))))

(deftest both-stores-normalize-keys-identically
  (doseq [[label open _ close] (flavours)]
    (let [s (open)
          ev {:op :put :id "t"
              :value {:id "t" :kind :table
                      :value [{(keyword "Maturity / Status") "R&D" :year 2026}]}}]
      (sub/commit! s ev)
      (is (= [{:maturity_status "R&D" :year 2026}] (:value (sub/object s "t"))) label)
      (close s))))

(deftest both-stores-agree-on-a-tx
  (doseq [[label open _ close] (flavours)]
    (let [s (open)]
      (sub/commit! s {:op :put :id "s" :value {:id "s" :kind :space :value {:cells []}}})
      (sub/commit! s {:op :tx :events [{:op :put :id "d" :value {:id "d" :value 1}}
                                       {:op :assoc :id "s" :path [:value :cells]
                                        :value [{:ref "d"}]}]})
      (is (= 1 (:value (sub/object s "d"))) label)
      (is (= [{:ref "d"}] (get-in (sub/object s "s") [:value :cells])) label)
      (is (= 2 (count (sub/history s))) label)          ; a :tx is ONE event
      (is (= 1 (sub/undo! s)) label)
      (is (nil? (sub/object s "d")) label)              ; and undoes as one
      (is (= [] (get-in (sub/object s "s") [:value :cells])) label)
      (close s))))
```

- [ ] **Step 3: Add the require.**

At the top of `test/loci/substrate_test.clj`, extend the `:require`:

```clojure
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))
```

- [ ] **Step 4: Run the tests to verify they pass.**

Run: `clojure -M:test -n loci.substrate-test`

Expected: all green. If `dlv` disagrees with `edn` on any assertion, the failure message names the flavour — fix `DatalevinStore`, never the test.

- [ ] **Step 5: Run the whole suite.**

Run: `clojure -M:test`

Expected: `Ran 89 tests containing 320 assertions. 0 failures, 0 errors.` (counts approximate — the point is zero failures.)

- [ ] **Step 6: Commit.**

```bash
git add test/loci/substrate_test.clj src/loci/dlv.clj
git commit -m "test: the substrate suite runs against both stores — parity proven, not assumed"
```

---

### Task 5: migration from the EDN log

**Files:**
- Create: `src/loci/migrate.clj`
- Test: `test/loci/migrate_test.clj`

- [ ] **Step 1: Write the failing test.**

Create `test/loci/migrate_test.clj`:

```clojure
(ns loci.migrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.migrate :as mig]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-mig-" (System/nanoTime) "/substrate.edn"))
(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-mig-dlv-" (System/nanoTime)))

(deftest migration-preserves-state-and-history
  (let [path (tmpfile)
        src  (sub/persistent-store path)]
    (sub/commit! src {:op :put :id "space:x" :value {:id "space:x" :kind :space :value {:cells []}}})
    (sub/commit! src {:op :tx :events [{:op :put :id "d" :value {:id "d" :kind :doc :value "hi"}}
                                       {:op :assoc :id "space:x" :path [:value :cells]
                                        :value [{:ref "d"}]}]})
    (sub/commit! src {:op :assoc :id "d" :path [:value] :value "hi again"})
    (let [dir    (tmpdir)
          report (mig/edn->datalevin! path dir)
          dst    (dlv/datalevin-store dir)]
      (is (:ok? report))
      (is (= 3 (:events report)))
      (is (= (sub/state src) (sub/state dst)))               ; the total check
      (is (= (sub/history src) (sub/history dst)))           ; events copied verbatim
      (is (= (sub/as-of src 2) (sub/as-of dst 2)))           ; and the past matches
      (is (= (:value (dlv/object-at dst "d" 2)) "hi"))       ; indices built during import
      (dlv/close! dst))))

(deftest migration-refuses-a-non-empty-target
  (let [path (tmpfile)
        _    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
        dir  (tmpdir)]
    (mig/edn->datalevin! path dir)
    (let [report (mig/edn->datalevin! path dir)]
      (is (not (:ok? report)))
      (is (re-find #"not empty" (:error report))))))
```

- [ ] **Step 2: Run the tests to verify they fail.**

Run: `clojure -M:test -n loci.migrate-test`

Expected: FAIL — `Could not locate loci/migrate__init.class`.

- [ ] **Step 3: Write the implementation.**

Create `src/loci/migrate.clj`:

```clojure
(ns loci.migrate
  "One-shot import of the EDN-lines log into Datalevin. A replay, not a
   conversion: events are copied verbatim and in order, so the ⏱ scrubber
   still travels the same moments with the same labels.

   Run it:  clojure -M -m loci.migrate            (data/substrate.edn → data/substrate)
            clojure -M -m loci.migrate <edn> <dir>

   The EDN file is never modified — it is the rollback."
  (:require [datalevin.core :as d]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

(defn edn->datalevin!
  "Copy every event from the EDN log at `path` into a Datalevin store at `dir`.
   Refuses a non-empty target. Verifies by folding both logs and comparing the
   resulting state — if those differ the import is wrong, whatever else looks
   right. Returns {:ok? :events :error}."
  [path dir]
  (let [src (sub/persistent-store path)
        dst (dlv/datalevin-store dir)]
    (try
      (cond
        (seq (sub/history dst))
        {:ok? false :error (str "target is not empty: " dir " already has "
                                (count (sub/history dst)) " events")}

        (empty? (sub/history src))
        {:ok? false :error (str "nothing to migrate: " path " has no events")}

        :else
        (do
          ;; commit! re-stamps :ts, so write the raw events straight to the dbis
          ;; and rebuild the derived indices from them — the log must be verbatim
          (doseq [[i ev] (map-indexed (fn [k ev] [(inc k) ev]) (sub/history src))]
            (d/transact-kv (:kv dst) [[:put "events" i ev :long]]))
          (reset! (:!log dst) (vec (sub/history src)))
          (reset! (:!state dst) (sub/materialize (sub/history src)))
          (dlv/rebuild-indices! dst)
          (let [ok? (= (sub/state src) (sub/state dst))]
            {:ok? ok?
             :events (count (sub/history src))
             :error (when-not ok? "state mismatch after import — NOT safe to switch over")})))
      (finally (dlv/close! dst)))))

(defn -main
  ([] (-main (str (sub/data-dir) "/substrate.edn") (str (sub/data-dir) "/substrate")))
  ([path dir]
   (let [{:keys [ok? events error]} (edn->datalevin! path dir)]
     (if ok?
       (println (format "migrated %,d events: %s → %s (state verified identical)"
                        events path dir))
       (println "migration FAILED:" error))
     (System/exit (if ok? 0 1)))))
```

- [ ] **Step 4: Run the tests to verify they pass.**

Run: `clojure -M:test -n loci.migrate-test`

Expected: `Ran 2 tests containing 8 assertions. 0 failures, 0 errors.`

- [ ] **Step 5: Migrate the real log, into a scratch copy first.**

Never migrate `data/` in place on the first run.

```bash
mkdir -p /tmp/loci-mig && cp data/substrate.edn /tmp/loci-mig/
clojure -M -m loci.migrate /tmp/loci-mig/substrate.edn /tmp/loci-mig/substrate
```

Expected: `migrated 80 events: /tmp/loci-mig/substrate.edn → /tmp/loci-mig/substrate (state verified identical)`

If it reports a state mismatch, stop and diff the two states — do not proceed to Task 6.

- [ ] **Step 6: Commit.**

```bash
git add src/loci/migrate.clj test/loci/migrate_test.clj
git commit -m "feat: migrate the EDN log into Datalevin — verified by state equality"
```

---

### Task 6: switch the server over

**Files:**
- Modify: `src/loci/content.clj:499`
- Modify: `README.md`

- [ ] **Step 1: Migrate the real data directory.**

```bash
clojure -M -m loci.migrate
```

Expected: `migrated 80 events: data/substrate.edn → data/substrate (state verified identical)`

`data/substrate.edn` stays exactly where it is — it is the rollback.

- [ ] **Step 2: Swap the store singleton.**

In `src/loci/content.clj`, change the `require` to add `[loci.dlv :as dlv]`, then replace the `defonce`:

```clojure
(defonce store
  (delay (let [s (dlv/datalevin-store)]
           ;; a non-empty log on disk wins; otherwise seed (which writes the log)
           (if (seq (sub/history s)) s (seed! s)))))
```

- [ ] **Step 3: Verify against the real data, headless.**

`-M:serve` hardcodes port 7777 (`server.clj:965`), so start the handler directly on **7779** against a *copy* of `data/`. Never point a scratch server at the real directory.

```bash
rm -rf /tmp/loci-check && mkdir -p /tmp/loci-check && cp -r data /tmp/loci-check/
LOCI_DATA=/tmp/loci-check/data clojure -M -e '
(require (quote loci.server) (quote org.httpkit.server))
(org.httpkit.server/run-server (var loci.server/handler) {:port 7779})
(println "up") @(promise)' &
until curl -s -o /dev/null -m 2 http://localhost:7779/api/state; do sleep 1; done
curl -s localhost:7779/api/state | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['events'], len(d['spaces']), len(d['objects']))"
```

Expected: `80 18 40`

Then check time travel and that a flow object still molds:

```bash
curl -s "localhost:7779/api/state?at=44" | python3 -c "import json,sys; print(json.load(sys.stdin)['events'])"
curl -s "localhost:7779/api/object/flow:1" | head -c 120
```

Expected: `44`, then a JSON payload containing `"kind":"flow"`.

Kill the server when done: `pkill -f 7779`

- [ ] **Step 4: Run the whole suite.**

Run: `clojure -M:test`

Expected: zero failures.

- [ ] **Step 5: Update the README.**

In the Run section, after the `clojure -M:serve` paragraph, replace the sentence about `data/`:

```markdown
`clojure -M:serve` persists its state under `data/` — the substrate is a
Datalevin (LMDB) store at `data/substrate`, the agent's memory an event log at
`data/memory.edn`. Delete that directory to reset to a clean slate. A pre-2026-08
EDN log at `data/substrate.edn` is migrated with `clojure -M -m loci.migrate`.
```

In the Layout section, add after the `substrate.clj` line:

```markdown
src/loci/dlv.clj         layer 1, durable: the event log on Datalevin (LMDB) + touch index
src/loci/migrate.clj     one-shot import of the old EDN log, verified by state equality
```

- [ ] **Step 6: Commit.**

```bash
git add src/loci/content.clj README.md
git commit -m "feat: the shell runs on the Datalevin substrate"
```

---

## Verification checklist

- [ ] `clojure -M:test` — zero failures, and the parity tests report both flavours
- [ ] `clojure -M -m loci.migrate` on a copy of `data/` reports state verified identical
- [ ] The served shell shows 80 events, 18 notebooks, 40 objects
- [ ] `?at=44` returns 44 events; `flow:1` still molds
- [ ] `clojure -Stree | grep sci` shows one version, and the sci-backed tests pass
- [ ] No `--enable-native-access` warnings on `clojure -M:serve`

## Out of scope (phase 2 and later)

- Wiring `object-at` / `counts-at` into `?at=` read paths — the index is built and tested here, used there
- The datalog projection, full-text index, LEAP rewrite
- Embeddings, vector index, reranking, the `Recall` reimplementation
- Deleting the EDN store — `PersistentStore` stays as the parity reference and the rollback
- Moving `data/memory.edn` — layer 3 moves in phase 3
- The `append-cell-event` write race — real, adjacent, and still not this plan's job
