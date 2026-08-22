# Files Housekeeping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A duplicate is named before you connect it, a file loci wrote can be reclaimed when nothing has named it for a while, and the browser suite stops leaking a 58 MB library per server.

**Architecture:** Three carry-overs from phase 2, each finished by a mechanism that already exists. `files/listing` gains `:connectedAs` from the scan `import-csv!` already does. A files GC mirrors `loci.lakegc` — `classify`/`report`/`reclaim!`, the same window, walked over `sub/history`. The one new abstraction lifts `lakegc`'s `:tx`-aware handle walk so both GCs share it, because its own docstring calls missing a sub-event the worst outcome that namespace can produce.

**Tech Stack:** Clojure 1.12, Datalevin/LMDB, DuckDB/DuckLake, vanilla JS in `resources/public/index.html`, `node --test` + playwright-core.

**Spec:** `docs/superpowers/specs/2026-08-22-files-housekeeping-design.md`

---

## Ground rules for every task

Every one of these records a defect that already happened on this repository. They are not boilerplate.

- **The REPL is the execution surface.** Load it with `ToolSearch` query `select:mcp__clojure-mcp__clojure_eval` and evaluate against the nREPL on port 7888; `:reload` after every edit. Do **not** run `clojure -M -e` to iterate — a cold JVM costs ~25 s (0.78 s JVM, 14 s Datalevin, 12 s loci source, measured 2026-08-15). `clojure -M:test` is for one final full-suite run before a commit.
- **A client-side timeout does not stop DuckDB.** The REPL tool times out at 20 s; the native JDBC call keeps running and does not reliably honour an interrupt. Re-issuing `run-tests` immediately races the first run, and two concurrent `with-temp-lake` fixtures stomp the same globally-`with-redefs`'d `lake/lake` var and close each other's connections — a wall of unrelated `Connection was closed` failures across the whole namespace. Wait for the REPL to go idle, and treat a sudden broad connection failure as this before treating it as a finding.
- **Browser tests cannot use the REPL.** `npm run test:browser` boots a real server and chromium. One file: `TMPDIR=.tmp npx node --test test/browser/sources.test.mjs` (~35 s).
- **The shell is `resources/public/index.html`**, not the repo-root `index.html` (a different, unserved file). Plain `grep` reports **zero** matches on the served one because its long lines read as binary — always `grep -a`.
- **Baselines to hold:** `clojure -M:test` → **801 tests, 4405 assertions, 0 failures**. `npm run test:browser` → **149 tests, 149 pass**. There is a known flake — `outline.test.mjs`, "clicking an entry brings that cell into view", reading a transient `.flash` class removed on a 900 ms timeout. It did not fire on the last two runs. **Six agents have chased it; do not chase it a seventh time.**
- **A `"` inside a Clojure docstring must be `\"`** in the source, or you get a `defn` macroexpansion error.
- **Never use an unanchored global find-and-replace on source.** One on the phase-2 branch changed `(take 6)` → `(take 8)` for a hash width and silently broke an unrelated cap.
- `:reload` does not drop vars for renamed-away tests — `(remove-ns 'the.ns)` first.
- **`data/` is test data.** Prefer a throwaway `LOCI_DATA`, not to protect it but because tests that share a store interfere. No checksums, no rehearsals on copies.
- **`data/`'s scale is not evidence.** `data/files/` holds 0 files. A measurement over it establishes that a mechanism **works**, never that a cost is **real**. Take the scale from the spec's stated target: a 200 MiB upload, of which ten stale ones is 2 GB.
- **Prove every test bites.** Break the fix, re-run, confirm FAIL, restore. **If a sabotage reports 0 failures, that is a finding about your sabotage.**
- **Ask what the assertion looks like when the mechanism is absent.** The sabotage step catches a broken *fix*, not a broken *assertion* — eleven assertions across phases 1 and 2 could not have failed, and running the sabotage found none of them. **Print the sabotaged output and read it.** Prefer a scoped element you created over a substring of the page, and a value only the mechanism can produce over one the failure mode also produces. If a sabotage fails a *different* assertion than you expected, that is a finding about the test.
- End every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## File structure

| file | responsibility |
|---|---|
| `test/browser/harness.mjs` | the per-server tmpdir, so the extracted library dies with the server |
| `src/loci/tablestore.clj` | **the lifted walk** — every handle and source any event names, `:tx`-aware. One copy, two callers. |
| `src/loci/lakegc.clj` | drops its private walk, calls the lifted one. Behaviour unchanged. |
| `src/loci/files.clj` | `remember!`/`retire!`/`names`/`retired`, `connected-as`, `listing` with `:connectedAs`, and the GC — `default-window`, `classify`, `report`, `reclaim!` |
| `src/loci/server.clj` | `import-csv!` remembers; `source-files` passes the store; the storage payload and reclaim route learn about files |
| `resources/public/index.html` | the duplicate note on the file row and in the preview; the storage panel's files section |
| `test/loci/tablestore_test.clj` | the lifted walk, including the `:tx` case |
| `test/loci/files_test.clj` | the manifest's retire, `connected-as`, `:connectedAs`, and every GC class |
| `test/loci/server_test.clj` | `import-csv!` remembering, the payload, the route |
| `test/browser/sources.test.mjs` | the duplicate note is drawn |

---

### Task 1: the leaked library dies with the server that made it

**Files:**
- Modify: `test/browser/harness.mjs:267-284`

No Clojure changes; nothing in `clojure -M:test` can observe this.

- [ ] **Step 1: Measure the leak as it stands**

```bash
ls .tmp/libduckdb_java*.so 2>/dev/null | wc -l
TMPDIR=.tmp npx node --test test/browser/sources.test.mjs 2>&1 | tail -5
ls .tmp/libduckdb_java*.so 2>/dev/null | wc -l
```

Expected: the second count is higher than the first — one `.so` per `startServer` in that file. Write both numbers down; they are the before half of Step 4.

- [ ] **Step 2: Point the server JVM's tmpdir at the directory the harness already removes**

`bootServer` already does `const dir = await mkdtemp(join(tmpdir(), 'loci-browser-'))` and `reap()` already does `rmSync(rec.dir, {recursive: true, force: true})` (line 252), as does `stop()` (line 307). Replace the spawn:

```javascript
  // -J-Djava.io.tmpdir: this JVM extracts DuckDB's ~58 MB native library on
  // first use, and deleteOnExit cannot fire because we kill the process group.
  // Pointing it at `dir` — the per-server directory reap() and stop() already
  // rm -rf — means the library is removed by whoever removes the server, with
  // no sweep to write and no risk of deleting a library another JVM (the
  // nREPL, a concurrent suite) still has mapped. Replaces `-Djava.io.tmpdir=.tmp`,
  // which moved the leak off tmpfs without ending it.
  const proc = spawn('clojure',
    ['-J--enable-native-access=ALL-UNNAMED', `-J-Djava.io.tmpdir=${dir}`,
     '-M', '-e', serverMain(extra)],
    { cwd: REPO, env: { ...process.env, LOCI_DATA: dir }, detached: true });
```

`dir` is absolute (`mkdtemp` over `tmpdir()`), so this does not reintroduce the relative-path trap that `5a11360` fixed.

- [ ] **Step 3: Run the file again**

```bash
TMPDIR=.tmp npx node --test test/browser/sources.test.mjs 2>&1 | tail -5
```

Expected: same pass count as Step 1.

- [ ] **Step 4: Confirm the count did not move**

```bash
ls .tmp/libduckdb_java*.so 2>/dev/null | wc -l
```

Expected: **equal to the count from Step 1**, not higher. If it grew, the JVM did not take the property — check for a typo in the `-J` flag by adding `(println (System/getProperty "java.io.tmpdir"))` to `serverMain` temporarily.

- [ ] **Step 5: Prove it bites**

Revert the `-J-Djava.io.tmpdir=${dir}` back to `.tmp`, re-run, confirm the count grows again, restore. **This is the sabotage** — the assertion here is a file count, so run it and read the two numbers rather than trusting the edit.

- [ ] **Step 6: Full browser suite, then commit**

```bash
npm run test:browser 2>&1 | tail -8
git add test/browser/harness.mjs
git commit
```

Expected: 149 pass (or 148 pass / 1 fail if the known `outline` flake fires).

---

### Task 2: `import-csv!` remembers what it wrote

**Files:**
- Modify: `src/loci/server.clj` (`import-csv!`, around line 2160-2206)
- Test: `test/loci/server_test.clj`

`files/remember!` is called only from `finish-upload!` (`files.clj:340`). Until this task, a pasted CSV is indistinguishable from a hand-placed one, and Task 6's GC rule would refuse to reclaim exactly the files the complaint is about.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest import-csv-remembers-the-title-it-was-given
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            r  (srv/import-csv! st {:csv "region,revenue\nEMEA,10\n" :title "Q3 Takings"} nil)
            nm (first (keys (files/names)))]
        (is (nil? (:error r)))
        (is (re-matches #"[0-9a-f]{16}\.csv" nm)
            "the file is still content-addressed — the title is metadata, not the name")
        (is (= "Q3 Takings" (get (files/names) nm))
            "and the manifest remembers what it was called, so the picker can show it")
        (is (= "Q3 Takings" (:name (first (filter #(= nm (:file %)) (files/listing)))))
            "which is what makes a pasted CSV list under its title instead of its hash")))))

(deftest import-csv-with-no-title-remembers-nothing-rather-than-a-blank
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            _  (srv/import-csv! st {:csv "a,b\n1,2\n"} nil)
            nm (first (map :file (files/listing)))]
        (is (empty? (files/names))
            "an untitled paste has no name to remember, and an empty string is not a name")
        (is (nil? (:name (first (files/listing))))
            "so the picker draws it under its hash, which is the honest answer")))))
```

- [ ] **Step 2: Run, confirm FAIL**

Expected: the first fails on `(= "Q3 Takings" nil)`; the second **passes already** (nothing is remembered today). That asymmetry is the point — the second test is a guard, not a driver, and it must keep passing after Step 3.

- [ ] **Step 3: Implement**

In `import-csv!`, after the file is written and before `source-connect!`, add the remember. `import-csv!` already computes the filename via `files/content-hash` and writes with `(when-not (.exists f) …)`; remember unconditionally, because a second paste of the same bytes under a *new* title should update the name (the manifest is last-wins by `:id` and that is what last-wins is for):

```clojure
    (when-let [t (not-empty title)]
      (files/remember! nm t))
```

`nm` is the `<hash>.csv` filename already in scope. `not-empty` is what keeps `""` and `nil` out — a blank title is not a name.

- [ ] **Step 4: Run, confirm PASS.** Both tests.

- [ ] **Step 5: Prove they bite**

Delete the `when-let` — the first test must fail on `(= "Q3 Takings" nil)`. Then change `not-empty` to `identity` — the *second* test must fail, because an untitled paste now remembers `nil` or `""`. Run both sabotages, print both outputs, restore.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 3: the `:tx`-aware walk lifts out of `lakegc`

**Files:**
- Modify: `src/loci/tablestore.clj` (add), `src/loci/lakegc.clj:45-63` (remove its private copy)
- Test: `test/loci/tablestore_test.clj`

`lakegc/event-handles` returns `{:table :object :ts}` — a lake table name. A files GC needs the source **uri**, and for a materialised source the uri is not on the handle at all: `refresh!` commits an `:assoc` at `[:value]` whose value is a lake handle naming no uri, so only the original `:put` of the whole object carries `:source`. The lifted walk therefore returns **both**.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest handles-in-history-finds-a-handle-inside-a-tx
  (let [st (sub/fresh-store)
        h  {:store :lake :table "t_one" :snapshot 1}]
    (sub/commit! st {:op :tx :events [{:op :put :id "tbl:a" :value {:id "tbl:a" :value h}}
                                      {:op :put :id "note:a" :value {:id "note:a" :value "x"}}]})
    (let [by-obj (tbl/handles-in-history st)]
      (is (= ["t_one"] (map #(:table (:handle %)) (get by-obj "tbl:a")))
          "a sub-event's handle is found — a walk that saw only top-level events would call
           this orphaned and reclaim rows a live object is still using")
      (is (every? :ts (get by-obj "tbl:a"))
          "and inherits the transaction's :ts, because a sub-event carries none of its own"))))

(deftest handles-in-history-carries-the-source-a-materialised-refresh-drops
  (let [st  (sub/fresh-store)
        src {:uri "loci://files/abc.csv" :format :csv :mode :materialise}
        v1  {:store :lake :table "t_one" :snapshot 1}
        v2  {:store :lake :table "t_two" :snapshot 2}]
    (sub/commit! st {:op :put :id "tbl:a" :value {:id "tbl:a" :value v1 :source src}})
    (sub/commit! st {:op :assoc :id "tbl:a" :path [:value] :value v2})
    (let [hs (get (tbl/handles-in-history st) "tbl:a")]
      (is (= ["t_two" "t_one"] (map #(:table (:handle %)) hs))
          "newest first, both versions")
      (is (= [nil "loci://files/abc.csv"] (map #(:uri (:source %)) hs))
          "the refresh names no uri — only the :put does. A files GC reading handles alone
           would find this file unreferenced and reclaim a live table's source."))))

(deftest handles-in-history-finds-a-passthrough-uri-on-the-handle-itself
  (let [st (sub/fresh-store)
        h  {:store :source :uri "loci://files/live.csv" :format :csv}]
    (sub/commit! st {:op :put :id "tbl:b" :value {:id "tbl:b" :value h :source {:uri "loci://files/live.csv"}}})
    (is (= "loci://files/live.csv" (:uri (:handle (first (get (tbl/handles-in-history st) "tbl:b")))))
        "a passthrough handle IS the recipe and names its own location")))
```

- [ ] **Step 2: Run, confirm FAIL** — `No such var: tbl/handles-in-history`.

- [ ] **Step 3: Implement in `src/loci/tablestore.clj`**

```clojure
(defn event-handles
  "Every `{:handle :source :object :ts}` one event names, **including inside a `:tx`**.

   Lifted out of `loci.lakegc` on 2026-08-22 when a second GC needed the same
   walk. Its reasoning is why it is one function and not two: a `:tx` is several
   sub-events committed as one undoable step, and `tools/save-table!` commits one
   whenever it also appends a notebook cell. A classifier that walked only
   top-level events would find no handle for those tables, call them orphaned,
   and reclaim rows a live object is still using — the worst outcome either GC
   can produce. Sub-events carry no `:ts` of their own, so they inherit the
   transaction's.

   Two shapes carry a handle: a `:put` whose value is the whole object, and an
   `:assoc` at `[:value]` whose value is the handle itself (that is how
   `rerun!` refreshes a table).

   **`:source` is carried beside `:handle` and is not redundant.** A
   materialised source's handle is `{:store :lake :table …}` and names no
   location, so a refresh — an `:assoc` at `[:value]` — drops the uri
   entirely. Only the `:put` of the whole object has it. A caller walking
   handles alone would find a live materialised table's file unreferenced."
  [ev]
  (case (:op ev)
    :tx (mapcat #(event-handles (assoc % :ts (:ts ev))) (:events ev))
    (let [v (:value ev)
          h (cond (handle? v)          v
                  (handle? (:value v)) (:value v))]
      (when h [{:handle h :source (:source v) :object (:id ev) :ts (:ts ev)}]))))

(defn handles-in-history
  "Every `{:handle :source :object :ts}` the log has ever named, newest first
   per object, as `{object-id [entry …]}`.

   From `sub/history` rather than from current state, because `as-of` and undo
   can reach any of them — that is exactly what a GC window protects."
  [st]
  (->> (sub/history st)
       (mapcat event-handles)
       (group-by :object)
       (into {} (map (fn [[o hs]] [o (vec (reverse hs))])))))
```

`(:source v)` is `nil` when `v` is a bare handle — an `:assoc` — which is the behaviour the second test pins.

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Rewrite `lakegc` to call it, changing no behaviour**

Delete `lakegc/event-handles` and `lakegc/handles-in-history` (lines 45-63). In `classify`, change:

```clojure
        by-obj  (tbl/handles-in-history st)
        seen    (into {} (for [[o hs] by-obj
                               [i {:keys [handle ts]}] (map-indexed vector hs)]
                           [(:table handle) {:object o :ts ts :age-rank i}]))]
```

`lakegc` already requires `loci.tablestore :as tbl`. Leave a one-line comment at the deletion site naming where the walk went and why.

- [ ] **Step 6: Run the lake GC's own tests, confirm PASS unchanged**

```clojure
(remove-ns 'loci.lakegc-test)
(require 'loci.lakegc-test :reload)
(clojure.test/run-tests 'loci.lakegc-test)
```

Expected: identical counts to before the change. **This is a refactor; a test that changes is a defect.** If `loci.tablestore` is reloaded, note the ground rule — it invalidates `reify`s in `loci.tablestore-test`, so reload that namespace too.

- [ ] **Step 7: Prove the `:tx` case bites**

In the lifted `event-handles`, replace the `:tx` branch with `nil` (so sub-events are skipped). Re-run **both** `loci.tablestore-test` and `loci.lakegc-test`. Expected: the new `:tx` test fails, and so does whatever `lakegc` test covered transactions. Print both, restore.

- [ ] **Step 8:** `clojure -M:test`, commit.

---

### Task 4: the manifest can retire a line, and `names` still answers

**Files:**
- Modify: `src/loci/files.clj` (`remember!` area, ~line 156)
- Test: `test/loci/files_test.clj`

`files.edn` is append-only and **primary** — unlike `chunks.edn` it cannot be rebuilt. So a reclaim retires a line rather than rewriting the file.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest retiring-a-name-keeps-it-readable-and-marks-it
  (with-dir
    (fn [_]
      (files/remember! "abc.csv" "Q3 Takings")
      (is (= #{} (files/retired)) "nothing is retired before anything is")
      (files/retire! "abc.csv")
      (is (= "Q3 Takings" (get (files/names) "abc.csv"))
          "the name survives the file — files.edn is primary, and a reclaim that lost
           a name would be the one operation in loci that cannot be undone")
      (is (= #{"abc.csv"} (files/retired)))
      (files/remember! "abc.csv" "Q3 Takings")
      (is (= #{} (files/retired))
          "and re-uploading the same bytes un-retires it — last-wins, the whole point"))))

(deftest retiring-a-name-loci-never-knew-is-not-an-error
  (with-dir
    (fn [_]
      (files/retire! "never-seen.csv")
      (is (= #{"never-seen.csv"} (files/retired)))
      (is (nil? (get (files/names) "never-seen.csv"))
          "a hand-placed file has no name to carry forward, and inventing one would lie"))))
```

- [ ] **Step 2: Run, confirm FAIL** — `No such var: files/retire!`.

- [ ] **Step 3: Implement**

`retire!` appends a record carrying the name forward so the line is self-contained, and `names` is unchanged — it already reduces last-wins by `:id` on `:name`, and a retired record still has one (or `nil`, which `names` must not turn into an entry):

```clojure
(defn retire!
  "Mark `id`'s file as reclaimed, keeping its name.

   An appended `{:id :name :at :retired true}` line, the third use of the
   append-only EDN-lines idiom `loci.chunks` and `loci.memory` keep. It carries
   `:name` forward so the record is self-contained and `names` needs no special
   case — and because `files.edn` is PRIMARY: a reclaim that rewrote the file to
   drop a line would be the one operation in loci that can lose a name for good.

   Retiring an id loci never named is not an error. A hand-placed file has no
   name to carry, and the GC never reclaims one anyway; this only has to not
   throw."
  [id]
  (let [rec {:id id :name (get (names) id) :at (System/currentTimeMillis) :retired true}
        f   (manifest-file)]
    (io/make-parents f)
    (spit f (str (pr-str rec) "\n") :append true)
    rec))

(defn retired
  "The set of ids whose most recent manifest record is a retirement."
  []
  (->> (records) (reduce (fn [m r] (assoc m (:id r) r)) {})
       vals (filter :retired) (map :id) set))
```

There is no `append-line!` in this namespace — `remember!` inlines its own `spit` (`files.clj:167-171`) and `retire!` matches it. If a third writer ever appears, extract one then.

`names` and `retired` must share one reading rule, so extract the line-by-line read into a private `records` returning every readable record in file order, and rewrite `names` over it. **`names` currently requires both `(:id rec)` and `(:name rec)`** (`files.clj:149`) — keep that filter in `names`, not in `records`, or a retired hand-placed id (whose `:name` is `nil`) would enter the map as a nil name:

```clojure
(defn- records
  "Every readable record in `files.edn`, in file order. Unreadable lines are
   skipped — a half-written line loses one name, never the file. **Line by line
   with `read-string`, not `edn/read` over the whole reader**: measured
   2026-08-20, a torn record read with `edn/read` takes the NEXT record as its
   missing value and then hits EOF looking for the closing brace, losing the
   torn line, the good line after it, and everything appended later."
  []
  (let [f (manifest-file)]
    (if-not (.exists f)
      []
      (into [] (keep (fn [line] (try (edn/read-string line) (catch Exception _ nil)))
                     (str/split-lines (slurp f)))))))
```

Keep `names`' existing docstring reasoning; it now reduces over `records` and must still drop records whose `:name` is `nil` so a retired hand-placed id yields no name.

- [ ] **Step 4: Run, confirm PASS.** Re-run the whole `loci.files-test` namespace — `names`' existing tests, including the torn-line one, must be unchanged.

- [ ] **Step 5: Prove they bite**

Three sabotages, each printed: drop `:retired true` from the appended record (`retired` returns `#{}`); drop `:name` from it (the first test fails on the surviving name); make `retired` read only the *last* record in the file rather than the last per id (the un-retire assertion fails). Restore each.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 5: `:connectedAs` — the listing says what is already connected

**Files:**
- Modify: `src/loci/files.clj` (`listing`), `src/loci/server.clj` (`source-files`, `import-csv!`)
- Test: `test/loci/files_test.clj`, `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(deftest connected-as-names-the-table-already-bound-to-a-uri
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (is (nil? (files/connected-as st "loci://files/a.csv"))
            "nothing connected is nil, not a blank string")
        (sub/commit! st {:op :put :id "tbl:sales"
                         :value {:id "tbl:sales" :source {:uri "loci://files/a.csv"}}})
        (sub/commit! st {:op :put :id "tbl:again"
                         :value {:id "tbl:again" :source {:uri "loci://files/a.csv"}}})
        (is (= "tbl:again" (files/connected-as st "loci://files/a.csv"))
            "first in sort order — the same answer import-csv! already gives, and one
             call site changing the meaning is worse than the answer being arbitrary")
        (is (nil? (files/connected-as st "loci://files/b.csv"))
            "and a different uri is not connected just because something is")))))

(deftest listing-marks-the-file-that-is-already-a-table
  (with-dir
    (fn [d]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "a.csv") "x\n1\n")
        (spit (io/file (files/dir) "b.csv") "y\n2\n")
        (sub/commit! st {:op :put :id "tbl:sales"
                         :value {:id "tbl:sales" :source {:uri "loci://files/a.csv"}}})
        (let [by-file (into {} (map (juxt :file identity) (files/listing st)))]
          (is (= "tbl:sales" (:connectedAs (get by-file "a.csv"))))
          (is (nil? (:connectedAs (get by-file "b.csv")))
              "absent, not false — the shell tells a remembered value from a missing one"))))))
```

- [ ] **Step 2: Run, confirm FAIL** — `connected-as` undefined, and `listing` takes no argument.

- [ ] **Step 3: Implement**

In `src/loci/files.clj`:

```clojure
(defn connected-as
  "The id of the table already bound to `uri`, or nil.

   **First in sort order, not oldest.** That is what `loci.server/import-csv!`'s
   `:duplicateOf` has always answered — ids are strings like `tbl:sales` rather
   than numbers — and this exists so both call sites give one answer. Making
   this one 'better' would mean the panel and the paste box disagreeing about
   which duplicate they mean.

   Reported so the panel can warn, never so it can refuse: two tables from one
   file is a thing someone may well want, and loci's answer to a mistake is
   `undo!`, not a veto."
  [st uri]
  (->> (sub/objects st) vals
       (filter #(= uri (:uri (:source %))))
       (map :id) sort first))
```

Change `listing` to `[st]` and add the key:

```clojure
                   (cond-> {:file n :uri (uri n) :bytes (.length f)}
                     (named n)               (assoc :name (named n))
                     (connected-as st (uri n)) (assoc :connectedAs (connected-as st (uri n))))
```

Bind `(connected-as st (uri n))` once in a `let` rather than calling it twice — it scans every object.

In `src/loci/server.clj`: `source-files` becomes `(defn source-files [st] {:files (files/listing st)})`, its route call site passes `st`, and `import-csv!`'s inline `seen` scan is replaced by `(files/connected-as st uri)` so the duplicate rule has one implementation.

- [ ] **Step 4: Run, confirm PASS.** Re-run `loci.files-test` and `loci.server-test` whole — every existing `listing` caller changed arity, so a missed one is an `ArityException` here.

**Named, because they were written one task earlier and will break:** Task 2's `import-csv-remembers-the-title-it-was-given` and `import-csv-with-no-title-remembers-nothing-rather-than-a-blank` both call `(files/listing)` with no argument. They need `(files/listing st)` and the `st` is already in scope in both. Grep for the rest rather than trusting this list: `grep -rn "files/listing" src/ test/`.

- [ ] **Step 5: Prove they bite**

Sabotage `connected-as` to compare `(:uri %)` on the object rather than `(:uri (:source %))` — the listing test must fail with `:connectedAs` absent. Then sabotage `sort` away (take `first` of the unsorted seq) and confirm the two-table assertion fails; **if it passes, your fixture's insertion order already matches sort order** and the test proves nothing — reorder the commits so it bites, as Task 4 of phase 2 had to for `listing`'s own sort.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 6: the GC classifier

**Files:**
- Modify: `src/loci/files.clj`
- Test: `test/loci/files_test.clj`

- [ ] **Step 1: Write the failing test**

**Do not backdate an event to age it out.** `EventStore/commit!` does `(assoc event :ts (System/currentTimeMillis))` (`substrate.clj:133`) — it **overwrites** any `:ts` a caller supplies, silently, so a test that commits with a 90-day-old `:ts` gets one stamped *now* and its window assertion becomes vacuous. `loci.lakegc`'s own tests never backdate; they pass a **shrunken window** instead (`{:versions 3 :days 0}`, `lakegc_test.clj:38`). Do the same.

`nothing-retained` below is `{:versions 0 :days -1}`, not `{:versions 0 :days 0}`. With `:days 0` the cutoff is *now*, and an event committed in the same millisecond satisfies `(>= ts cutoff)` and comes back `:in-window` — a rare flake that would look like a real failure. A negative day puts the cutoff in the future, so the age half is off deterministically.

```clojure
(def ^:private nothing-retained
  ;; retain nothing: no versions, and a cutoff in the FUTURE so no event can be
  ;; inside the age half. See the note above on why not `:days 0`.
  {:versions 0 :days -1})

(defn- put-source! [st id uri mode]
  (sub/commit! st {:op :put :id id
                   :value {:id id :source {:uri uri :mode mode}
                           :value (if (= mode :passthrough)
                                    {:store :source :uri uri :format :csv}
                                    {:store :lake :table (str "t_" id) :snapshot 1})}}))

(deftest classify-keeps-a-file-a-live-table-still-names
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "a.csv") "x\n")
        (files/remember! "a.csv" "kept")
        (put-source! st "tbl:a" "loci://files/a.csv" :materialise)
        (is (= :current (:class (get (files/classify st nothing-retained) "a.csv")))
            "current beats the window — a live object's file is never reclaimable however old")))))

(deftest classify-reclaims-a-file-loci-wrote-that-nothing-names-any-more
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (files/remember! "old.csv" "gone")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (is (= :reclaimable (:class (get (files/classify st nothing-retained) "old.csv")))
            "no live object names it and its only mention is 90 days old")))))

(deftest classify-never-reclaims-a-file-loci-did-not-write
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "x\n")
        (is (= :foreign (:class (get (files/classify st nothing-retained) "placed.csv")))
            "no manifest entry means a person put it there — rsync, a mount, a volume —
             and deleting it would destroy data loci never created")))))

(deftest classify-never-reclaims-a-live-passthrough-tables-file
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "live.csv") "x\n")
        (files/remember! "live.csv" "live")
        (put-source! st "tbl:p" "loci://files/live.csv" :passthrough)
        (is (= :current (:class (get (files/classify st nothing-retained) "live.csv")))
            "it is re-read on every query — reclaiming it turns a working table
             into :table/unavailable, and no window makes that acceptable")))))

(deftest classify-keeps-a-file-inside-the-window
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "recent.csv") "x\n")
        (files/remember! "recent.csv" "recent")
        (put-source! st "tbl:a" "loci://files/recent.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (is (= :in-window (:class (get (files/classify st files/default-window) "recent.csv")))
            "deleted yesterday — undo and as-of can still reach it, which is what
             the window is for")))))

(deftest classify-finds-a-file-named-only-inside-a-tx
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "txd.csv") "x\n")
        (files/remember! "txd.csv" "in a tx")
        (sub/commit! st {:op :tx
                         :events [{:op :put :id "tbl:t"
                                   :value {:id "tbl:t" :source {:uri "loci://files/txd.csv"}
                                           :value {:store :lake :table "t_t" :snapshot 1}}}
                                  {:op :put :id "note:t" :value {:id "note:t" :value "x"}}]})
        (is (= :current (:class (get (files/classify st nothing-retained) "txd.csv")))
            "THE test the lifted walk exists for — a top-level-only walk finds no
             handle here, calls the file unreferenced, and reclaims a live table's source")))))
```

- [ ] **Step 2: Run, confirm FAIL** — `No such var: files/classify`.

- [ ] **Step 3: Implement**

```clojure
(def default-window
  "How much history a reclaim keeps: whichever of these keeps more.

   `:days` transfers directly from `loci.lakegc/default-window` — a file named
   by any handle in the last 30 days stays. **`:versions` needed reinterpreting,
   because a file has no versions**: it is the three most recent FILES per
   object, since an object re-uploaded or reconnected names a different hash each
   time. Whichever keeps more wins, for lakegc's reason: a count alone drops a
   busy afternoon's work, an age alone puts no ceiling on a table rewritten
   forty times in that afternoon."
  {:versions 3 :days 30})

(defn classify
  "`{filename {:class … :object … :ts …}}` for every file in `dir`.

   Five classes. **foreign** — no manifest entry, so a person put it there and
   loci never touches it. **current** — named by a live object, including every
   live passthrough table, whose file is re-read on every query. **in-window** —
   named only by history, inside `window`, so undo and `as-of` stay exact.
   **reclaimable** — named only by history, outside it. **orphaned** — remembered
   by the manifest but named by nothing at all, which `undo!` produces.

   Walks `sub/history` through `tbl/handles-in-history` rather than current
   state, and takes the uri from `:source` as well as from the handle: a
   materialised source's handle names no location, so a refresh drops the uri
   and only the original `:put` carries it."
  [st {:keys [versions days] :as _window}]
  (let [gone    (retired)                       ; ONE manifest read, see below
        mine    (into #{} (remove gone) (keys (names)))
        live    (set (keep #(:uri (:source %)) (vals (sub/objects st))))
        cutoff  (- (System/currentTimeMillis) (* days 24 60 60 1000))
        by-obj  (tbl/handles-in-history st)
        seen    (into {} (for [[o hs] by-obj
                               [i e]  (map-indexed vector hs)
                               :let [u (or (:uri (:source e)) (:uri (:handle e)))]
                               :when u]
                           [u {:object o :ts (:ts e) :age-rank i}]))]
    (into {}
          (for [{:keys [file uri]} (listing st)
                :let [m (get seen uri)]]
            [file
             (cond
               (not (mine file))           {:class :foreign :object nil :ts nil}
               (live uri)                  (assoc (or m {}) :class :current)
               (nil? m)                    {:class :orphaned :object nil :ts nil}
               (or (< (:age-rank m) versions)
                   (>= (or (:ts m) 0) cutoff)) (assoc m :class :in-window)
               :else                       (assoc m :class :reclaimable))]))))
```

Note the `cond` order: **`:foreign` first**, so a hand-placed file can never fall through to any other class, and **`:current` before the window**, mirroring `lakegc` and mirroring `source-block`'s precedence lesson from phase 2.

**Read the manifest once.** An earlier draft of this `let` called `(retired)` inside a `keep` over `(keys (names))` — one full parse of `files.edn` per manifest entry. Measured 2026-08-22 during Task 4's review: `records` costs ~43 ms over 10,000 lines, so that shape is ~10,000 × 43 ms ≈ **7 minutes for one `classify`** — and `classify` is what `report` calls, which Task 9 asks to be cheap enough to sit in a panel that draws on open. Both `(retired)` and `(names)` are hoisted into the `let` and each reads the file once. If you add another manifest consumer here, hoist it too.

- [ ] **Step 4: Run, confirm PASS.** All six.

- [ ] **Step 5: Prove they bite — six sabotages, each printed**

| break | must fail |
|---|---|
| drop the `:foreign` branch | `classify-never-reclaims-a-file-loci-did-not-write` |
| put `(live uri)` after the window check | the passthrough test, and the 90-day `:current` test |
| take the uri from `(:uri (:handle e))` only | a materialised table's file misclassifies — write the extra assertion if none of the six catches it |
| `(:tx …)` branch of `tbl/event-handles` → nil | `classify-finds-a-file-named-only-inside-a-tx` |
| `cutoff` sign flipped | `classify-keeps-a-file-inside-the-window` |
| `mine` ignores `retired` | add a test — a reclaimed file that reappears must not be `:foreign` |

The third and sixth rows are the ones most likely to report 0 failures. **If either does, that is a finding about the test, and the missing test is the deliverable.**

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 7: `report` and `reclaim!`

**Files:**
- Modify: `src/loci/files.clj`
- Test: `test/loci/files_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(deftest report-says-what-a-reclaim-would-free-without-freeing-it
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "0123456789")
        (files/remember! "old.csv" "gone")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [r (files/report st nothing-retained)]
          (is (= 10 (:reclaimable-bytes r)) "the file's actual length, not a count")
          (is (= ["old.csv"] (map :file (:reclaimable r))))
          (is (.exists (io/file (files/dir) "old.csv"))
              "report is the dry run — there is no dry-run flag because of it"))))))

(deftest reclaim-deletes-the-file-retires-the-name-and-leaves-the-rest
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (spit (io/file (files/dir) "keep.csv") "y\n")
        (files/remember! "old.csv" "gone") (files/remember! "keep.csv" "kept")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (put-source! st "tbl:k" "loci://files/keep.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [r (files/reclaim! st nothing-retained)]
          (is (= ["old.csv"] (map :file r)))
          (is (not (.exists (io/file (files/dir) "old.csv"))))
          (is (.exists (io/file (files/dir) "keep.csv")) "a live table's file is untouched")
          (is (= #{"old.csv"} (files/retired)))
          (is (= "gone" (get (files/names) "old.csv"))
              "the name outlives the bytes — files.edn is primary"))))))

(deftest reclaim-reports-a-file-it-could-not-delete-rather-than-dropping-it
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (files/remember! "old.csv" "gone")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        ;; The file must stay a FILE — `listing` filters directories out via
        ;; `content?`, so a directory named `old.csv` never becomes a candidate
        ;; and the test would pass on an empty result. Deletion on POSIX is a
        ;; property of the containing directory, so make that unwritable.
        (.setWritable (files/dir) false)
        (try
          (let [r (files/reclaim! st nothing-retained)]
            ;; Running as root ignores the permission bit. Say so loudly rather
            ;; than passing green on a mechanism that never ran — a silent skip
            ;; here is a test that certifies.
            (is (not (empty? r)) "the candidate is still reported, not silently dropped")
            (if (.exists (io/file (files/dir) "old.csv"))
              (do (is (re-find #"could not delete" (str (:error (first r)))))
                  (is (= #{} (files/retired))
                      "and the name is NOT retired, because the bytes are still there"))
              (is false "the delete succeeded despite an unwritable directory — running as root? this test proved nothing")))
          (finally (.setWritable (files/dir) true)))))))

(deftest reclaim-with-nothing-reclaimable-deletes-nothing
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "x\n")
        (is (= [] (files/reclaim! st nothing-retained)))
        (is (.exists (io/file (files/dir) "placed.csv"))
            "a hand-placed file survives a reclaim that had no other candidates —
             the class that protects it is not a special case of having something else to do")))))
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement**

```clojure
(defn report
  "What every file costs and what a reclaim would free. Deletes nothing.

   `report` is the dry run, which is why `reclaim!` has no dry-run flag — the
   same shape `loci.lakegc` keeps."
  [st window]
  (let [cls (classify st window)
        rows (for [{:keys [file bytes]} (listing st)]
               (assoc (get cls file) :file file :bytes bytes))]
    {:files (vec rows)
     :reclaimable (vec (filter #(= :reclaimable (:class %)) rows))
     :reclaimable-bytes (reduce + 0 (map :bytes (filter #(= :reclaimable (:class %)) rows)))
     :total-bytes (reduce + 0 (map :bytes rows))}))

**`retire!` takes the name, it does not look it up.** Task 4's `retire!` is `[id]` and resolves the name with `(get (names) id)` — a full manifest parse. Called once per reclaimed file, that is O(N²): measured 2026-08-22, reclaiming 2,000 files costs **27.9 s** against **0.040 s** when the name is resolved once. Give `retire!` a second arity `[id name]` and have `reclaim!` pass the name it already has from the `report` row, or hoist one `(names)` out of the loop. 700× at N=2,000, measured.

(defn reclaim!
  "Delete every `:reclaimable` file and retire its manifest line. Irreversible.

   The bytes go and the NAME STAYS — `retire!` appends rather than rewriting,
   because `files.edn` is primary and cannot be rebuilt. A file that vanished
   between `report` and here is not an error; it is the outcome the caller
   asked for."
  [st window]
  (vec (for [{:keys [file bytes]} (:reclaimable (report st window))
             :let [f    (io/file (dir) file)
                   gone (or (.delete f) (not (.exists f)))]]
         (if gone
           (do (retire! file) {:file file :bytes bytes})
           {:file file :bytes bytes :error (str "loci could not delete " file
                                                " — it is still in " (.getPath (dir)))}))))
```

**Every candidate gets a row, and a failure carries `:error` rather than vanishing from the result.** The spec asks for this by name: *"A reclaim that cannot delete a file reports which and why."* A `:when` filter would silently return a shorter vector, and a caller comparing counts would read that as success. Note the name is **not** retired when the delete failed — the bytes are still there.

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove they bite**

Make `reclaim!` skip `retire!` (the name/retired assertions fail); make `report` return every file as reclaimable (the `keep.csv` assertion fails, and **check it is that assertion and not the count** — if only the count fails, the test is weaker than it reads); make `report` delete as it goes (the dry-run assertion fails). Print all three.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 8: the storage panel counts files, and can reclaim them

**Files:**
- Modify: `src/loci/server.clj` (`storage-payload`, the `/api/storage/reclaim` route), `resources/public/index.html`
- Test: `test/loci/server_test.clj`, `test/browser/sources.test.mjs`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest storage-payload-carries-what-the-files-directory-costs
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "0123456789")
        (let [p (srv/storage-payload st)]
          (is (= 10 (get-in p [:files :total-bytes])))
          (is (= 0 (get-in p [:files :reclaimable-bytes]))
              "a hand-placed file costs bytes and frees none — the panel must not
               offer to delete something loci did not write"))))))

(deftest the-files-reclaim-route-answers-through-the-URL
  (with-temp-data-dir
    (fn []
      (with-redefs [c/store (delay (sub/fresh-store))]
        (let [r (srv/handler {:request-method :post :uri "/api/storage/reclaim-files" :body nil})]
          (is (= 200 (:status r)))
          (is (= [] (:reclaimed (json/read-str (:body r) :key-fn keyword)))))))))
```

The `with-redefs [c/store …]` is not optional — `handler` binds `st (store)` **before** the `cond` (`server.clj:3251`), so every handler test forces the `defonce` delay and would open and seed the user's real Datalevin store. Its four siblings all rebind it and say so.

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement**

Add to `storage-payload`, beside the lake numbers:

```clojure
   :files (let [r (files/report st nothing-retained)]
            (select-keys r [:total-bytes :reclaimable-bytes :reclaimable]))
```

and a route beside `/api/storage/reclaim`:

```clojure
      (and (= :post (:request-method req)) (= uri "/api/storage/reclaim-files"))
      (json-resp {:reclaimed (files/reclaim! st nothing-retained)})
```

Method-gated because it deletes, exactly as `/api/storage/reclaim` is and for the same reason its comment gives.

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: The shell — the duplicate note**

Add to `API` beside `sourceFiles` (`index.html:574`):

```javascript
  reclaimFiles: () => fetch('/api/storage/reclaim-files', {method:'POST'}).then(r=>r.json()),
```

In `dataFileTab`'s row template (`index.html:1216-1219`), append the note after `.fmeta`:

```javascript
      files.map(f=>'<button type="button" class="filerow" data-file="'+esc(f.file)+'">'+
        '<span class="fn">'+esc(f.name||f.file)+'</span>'+
        '<span class="fmeta">'+esc(srcFormat(f.uri))+' · '+fmtBytes(f.bytes)+'</span>'+
        (f.connectedAs?'<span class="dupe">already '+esc(f.connectedAs)+'</span>':'')+
        '</button>').join('')+
```

In `dataPreview`, above the column table, the same fact as a sentence — Connect stays enabled:

```javascript
  const dupe = f.connectedAs
    ? '<div class="dupe-note">loci already has this file as <b>'+esc(f.connectedAs)+'</b>. '+
      'Connecting again makes a second table.</div>'
    : '';
```

CSS beside the panel's existing rules (`index.html:207`):

```css
  .dupe { font: 10px/1 ui-monospace, monospace; color: var(--muted); margin-left: 8px; }
  .dupe-note { font-size: 12px; color: var(--muted); padding: 0 0 8px; }
```

In the storage panel, a files row: total bytes, reclaimable bytes, and a button **only when reclaimable is non-zero**. When it is zero, say what the directory holds and draw no button at all — a disabled control invites a click that can never work, and `fnlib/catalog`'s existing pattern is to grey with a `:why`. The why here is "nothing loci wrote is unreferenced".

- [ ] **Step 6: Browser test**

```javascript
test('the file tab says a file is already a table, and connects it anyway', async () => {
  const srv = await startServer({fresh: true});
  try {
    await withPage(srv, async (page) => {
      await bootedShell(page);
      // two files, one already connected
      await page.evaluate(async () => {
        await fetch('/api/source/upload', {
          method:'POST',
          headers:{'X-Loci-Upload': JSON.stringify({id:'dupe1', seq:0, name:'taken.csv', final:true})},
          body: new Blob(['region,revenue\nEMEA,10\n'])});
        await fetch('/api/source/upload', {
          method:'POST',
          headers:{'X-Loci-Upload': JSON.stringify({id:'dupe2', seq:0, name:'free.csv', final:true})},
          body: new Blob(['region,revenue\nAPAC,20\n'])});
      });
      const files = await page.evaluate(() => fetch('/api/source/files').then(r=>r.json()));
      const taken = files.files.find(f => f.name === 'taken.csv');
      await page.evaluate(async (uri) => {
        await fetch('/api/source/connect', {method:'POST',
          headers:{'Content-Type':'application/json'},
          body: JSON.stringify({uri, format:'csv', mode:'materialise', title:'Taken'})});
      }, taken.uri);

      await page.click('#dataBtn');
      await page.waitForSelector('.filerow');

      const rows = await page.$$eval('.filerow', els => els.map(e => ({
        text: e.querySelector('.fn').textContent,
        dupe: e.querySelector('.dupe') ? e.querySelector('.dupe').textContent : null,
      })));
      const t = rows.find(r => r.text === 'taken.csv');
      const f = rows.find(r => r.text === 'free.csv');
      assert.ok(t.dupe && /tbl:/.test(t.dupe),
        `the connected file carries no duplicate note: ${JSON.stringify(t)}`);
      assert.equal(f.dupe, null,
        'the unconnected file carries one anyway — the note is not conditional on anything');

      // and Connect is still offered on it: warn, never refuse
      await page.click('.filerow:has(.dupe)');
      await page.waitForSelector('.srcpreview');
      assert.equal(await page.$$eval('[data-a="dconnect"]:not([disabled])', e => e.length), 1,
        'Connect was disabled on a duplicate — loci warns, it does not veto');
    });
  } finally { await srv.stop(); }
});
```

Both halves matter. The `f.dupe === null` assertion is what stops a note drawn unconditionally from passing, and the enabled-Connect assertion is the spec's "warn, never refuse" made testable. Assert on a **scoped element you created** (`.dupe`), never a substring of the page — phase 2's `/takings/` check over the whole page passed against a block that fetched nothing, because DuckDB's error message in the fallback already contained the string.

Check `[data-a="dconnect"]` against the real attribute in `dataPreview` before running — `grep -a 'data-a="d' resources/public/index.html` — and use whatever it actually is.

- [ ] **Step 7: Prove they bite**

Force `:connectedAs` to `nil` in `listing` and confirm the browser test fails on the `.dupe` count, not on a timeout. Force `report`'s `:reclaimable-bytes` to `:total-bytes` and confirm the hand-placed-file test fails.

- [ ] **Step 8:** both suites, commit.

---

### Task 9: the measurement, and both suites

- [ ] **Step 1: Measure at the spec's stated target, not at `data/`**

`data/files/` holds 0 files. Generate the scale the feature is for: **ten 200 MiB CSVs**, connected and then deleted, so the directory holds 2 GB of reclaimable bytes. Report `report`'s wall clock over that directory and `reclaim!`'s, and the bytes actually freed.

The claim to check is that `report` is cheap enough to sit in a panel that draws on open: `lakegc/report` "fetches no rows" for exactly this reason, and a files report that stats 10,000 files must say what that costs. If it is slow, that is the finding.

- [ ] **Step 2:** `clojure -M:test` → 0 failures. `npm run test:browser` → 149 pass, or the known `outline` flake only.

- [ ] **Step 3:** write the numbers into the spec's own **Measured** section with the date, saying for each which of the two things it establishes — that the mechanism works, or that the cost is real. A measurement over a generated 2 GB directory can establish the second; nothing measured over `data/` can.

- [ ] **Step 4:** delete the generated fixtures — they are 2 GB — and commit.

---

## Definition of done

- [ ] Picking a file that is already a table says so **before** you connect it, and lets you connect it anyway
- [ ] `import-csv!` remembers its title, so a pasted CSV lists under it rather than under a hash
- [ ] A file loci wrote, unreferenced and outside the window, is reported and can be reclaimed
- [ ] A hand-placed file is never reclaimable, whatever its age
- [ ] A live passthrough table's file is never reclaimable, whatever its age
- [ ] A file named only by a sub-event inside a `:tx` is never reclaimable, and a test proves it by failing against a top-level-only walk
- [ ] A reclaim retires the manifest line; the name outlives the bytes
- [ ] `lakegc`'s behaviour is unchanged, proved by its own suite being untouched
- [ ] The browser suite leaves no `.so` behind
- [ ] `clojure -M:test` and `npm run test:browser` both green but for the known `outline` flake
- [ ] Every regression test proved to fail when its fix is reverted, and every sabotage's output read rather than counted
