# ClojureScript Shell and the Elision Protocol — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Rows view becomes a ClojureScript component — paged, sorted, sticky-headed, with a frozen first column and a column chooser — fed by a Transit payload whose truncation is a labelled hole rather than silence.

**Architecture:** Replicant renders hiccup into a node the existing vanilla shell hands it, so the other ~3,000 lines don't move. The payload carries an **elision** — Clerk's shape, loci's semantics: it *replaces* the page rather than appending, and carries the opaque cursor `TableStore/page` already understands. Transit replaces JSON on that one route so a namespaced keyword survives the wire.

**Tech Stack:** Clojure 1.12, shadow-cljs 3.2.0, Replicant 2025.06.21, Transit (`transit-clj` 1.0.333 / `transit-cljs` 0.8.280), DuckDB/DuckLake, Playwright via `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-25-cljs-shell-and-elision-design.md`. Read §3 and §3.1 before Task 4 — the replace-not-append decision is the one thing in this plan a reader will find surprising.

---

## A note on this plan's form, 2026-08-26

**Tasks 1–6 carry implementation code. Tasks 7–12 deliberately do not.**

Measured after Phase 2: 3,459 lines of plan had produced 709 lines of shipped code, and
implementers had found **thirteen defects in the plan** — three tests that could not fail, a
budget calculation that silently doubled the common case, tests that would have read the real
data directory, an output path unrunnable under `"type": "module"`, a signature that changed
between tasks, and a sabotage that cannot detect what it claims to. Almost every one was in
*code written into the plan* rather than in prose: the implementation, written badly by someone
who could not run it, and then written again properly by someone who could.

So from Task 7 on, a task states its **files**, the **decision and why**, what each test must
**establish**, and the **sabotage** that proves the test bites. The implementer writes the code,
at the REPL, with the file open.

**The sabotage stays.** It costs little, it lives inside implementation time, and it produced
every genuine finding in this work: the DuckDB `ORDER BY … OFFSET` tie instability that broke
the plan's own test on unsabotaged code, a cursor crashable by editing a URL, a ClojureScript
suite that exits 0 while running zero tests.

## Ground rules for every task

- **The REPL is the execution surface.** `ToolSearch` for `select:mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888; `:reload` after every edit. Never `clojure -M -e` to iterate — a cold JVM costs ~25 s. `clojure -M:test` is for one final full-suite run per task.
- **Prove every test bites.** Reintroduce the defect, watch it go red, and **read the failure** rather than counting failures. Three sabotage rows in the previous plan for this work were wrong, and were caught only because implementers read failures.
- **Measure, don't assert.** A browser test that checks a CSS rule is present passes while the header scrolls away. Assert on measured positions and sizes.
- **`rg -n`, never `rg -rn`** — `-r` is *replace* and silently rewrites every match in the output. Plain `grep` is unreliable on `index.html` because of its long lines.
- **Everything FOREGROUND**, timeout up to 900000 ms. Never background a command; two subagents have already parked on a backgrounded browser run in this project.
- **To iterate on ONE browser file, name the file — never `--test-name-pattern`.** Measured 2026-08-25: `--test-name-pattern` filters which *tests execute* and does **not** stop each file's `before` hook, so all seventeen files still boot a fixture server. One run had **six server JVMs up at once, 4.7 GB RSS on a 15 GB box**, to execute the tests in one of them. `node --test test/browser/<file>.test.mjs` boots one. The full `npm run test:browser` is still what you run at the end of a task.
- **Two Clojure suites and one JS suite.** `clojure -M:test`, `npm run test:browser`, and from Task 2 also `npm run test:cljs`. **Always the npm script, never the raw `compile test && node …`** — a `:node-test` build whose `:ns-regexp` matches nothing prints `Ran 0 tests` and exits 0, so the raw form reports a suite that is not there as green. The script is the guard.

## What the spike already left in place

Commit `713568e` is a **spike, not production**. It is on the branch and it works; this plan hardens it. Do not assume any of it is tested.

| File | State |
|---|---|
| `deps.edn` | `:cljs` alias with `:replace-deps` — correct, keep |
| `shadow-cljs.edn` | one `:grid` build — extended in Task 2 |
| `src-cljs/loci/grid.cljs` | 77 lines of spike. **Rewritten wholesale in Task 6** |
| `src/loci/server.clj` | `transit-resp`, `wire-resp`, the `/js/…` static route — kept, tested in Tasks 1 and 3 |
| `resources/public/index.html` | `SPIKE_CLJS` flag, `data-cljs-grid`, the mount hook — renamed and tested in Task 1 |
| `package-lock.json` | **carries residue** — 7 markdown-it entries from the npm install `:replace-deps` now prevents. Task 1 removes them |

## File structure

| File | Tasks | Responsibility after this plan |
|---|---|---|
| `deps.edn` | 1 | the `:cljs` alias, minimal classpath |
| `shadow-cljs.edn` | 1, 2 | the `:grid` release build and the `:test` node-test build |
| `src/loci/server.clj` | 3, 4, 5 | Transit beside JSON; the elision on the rows branch; the budget across a notebook payload |
| `src/loci/tablestore.clj` | 4 | `page` returns `:offset` |
| `src/loci/lake.clj` | 4 | `Lake/page` supplies `:offset` in both modes |
| `src-cljs/loci/grid.cljs` | 6, 8, 9, 10 | state, actions, fetch — no DOM |
| `src-cljs/loci/grid/view.cljs` | 6, 7, 8, 9, 10 | hiccup only, a pure function of state |
| `test-cljs/loci/grid_test.cljs` | 6, 8, 9, 10 | cljs.test over state transitions and the view's data |
| `test/loci/server_test.clj` | 3, 4, 5 | wire format, elision shape, budget |
| `test/browser/grid.test.mjs` | 7, 8, 9, 10 | measured DOM: widths, sticky, frozen, pager, sort, chooser |
| `resources/public/index.html` | 1, 7, 11, 12 | the seam, the grid CSS, then `tableHTML`'s deletion |

---

# Phase 1 — the build, made deliberate

## Task 1: the spike becomes a build, with a test that guards it

**Files:**
- Modify: `package-lock.json` (remove residue)
- Modify: `resources/public/index.html` — rename `SPIKE_CLJS`
- Create: `test/browser/cljsbuild.test.mjs`

- [ ] **Step 1: Remove the npm residue.**

The `:replace-deps` fix stops shadow-cljs installing Clerk's markdown-it stack, but the lock file still records it. Remove and reinstall from the true dependency set:

```bash
rm -rf node_modules package-lock.json
npm install
python3 -c "
import json; d=json.load(open('package-lock.json'))
bad=[k for k in d.get('packages',{}) if 'markdown' in k or 'punycode' in k]
print('residue entries:', len(bad)); assert not bad, bad
"
```

Expected: `residue entries: 0`.

- [ ] **Step 2: Rename the flag — it is a build, not a spike.**

In `resources/public/index.html`, replace the three `SPIKE_CLJS` sites:

```js
// The ClojureScript grid, opt-in until it reaches parity with tableHTML (Task
// 12, which deletes both this flag and tableHTML). `?cljs` on the URL, so a
// reviewer can put the two renderers side by side in two tabs.
const CLJS_GRID = new URLSearchParams(location.search).has('cljs');
```

and in `renderMold`:

```js
    // A div and an id is the ENTIRE integration surface: the ClojureScript side
    // fetches its own payload over Transit, so nothing here needs to know the
    // payload's shape. That is what keeps this seam one line wide.
    case 'table':  return liveBadge(m)+(CLJS_GRID
                     ? '<div data-cljs-grid="'+esc(m.id)+'"></div>'
                     : tableHTML(d));
```

Update the comment on the `<script defer src="/js/grid.js">` tag to drop the word SPIKE.

- [ ] **Step 3: Write the failing test.** Create `test/browser/cljsbuild.test.mjs`:

```javascript
// The build's own guarantees, which are easy to lose silently:
//   - the compiled module is served and loads
//   - `?cljs` absent leaves every existing path byte-identical
//   - the build installs nothing from npm
// The last one is a test rather than a comment because `datalevin` pulls
// `io.github.nextjournal/markdown`, whose jar declares npm dependencies, and a
// classpath regression would quietly reinstall Clerk's markdown-it stack.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

const REPO = dirname(dirname(dirname(fileURLToPath(import.meta.url))));
let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// Both halves of the flag, because the flag is renamed in this task and nothing
// else would notice a typo in one of its three sites.
test('the compiled module is served, and ?cljs puts its seam in the DOM', async () => {
  await withPage(browser, 'cljs-loads', async (page) => {
    await bootedShell(page, server.url + '?cljs');
    await page.waitForFunction(() => window.loci && typeof window.loci.grid.mount === 'function',
                               null, { timeout: 15000 });
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    assert.equal(await page.locator('.molder-b [data-cljs-grid]').count(), 1,
      'with ?cljs the table mold must be the ClojureScript seam');
  });
});

test('without ?cljs the shell renders its own table and mounts nothing', async () => {
  await withPage(browser, 'cljs-off', async (page) => {
    await bootedShell(page, server.url);
    // A table has to be ON SCREEN for this to mean anything: counting zero seams
    // on a page with no table is an assertion that cannot fail — with the flag
    // forced permanently on it still read zero. `tbl:planets` is in the
    // deterministic seed and several other files open it the same way.
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    const m = await page.evaluate(() => ({
      seams: document.querySelectorAll('[data-cljs-grid]').length,
      tables: document.querySelectorAll('.molder-b table.mold').length,
    }));
    assert.equal(m.tables, 1, 'the vanilla renderer must still draw the table');
    assert.equal(m.seams, 0, 'the ClojureScript seam must be inert without the flag');
  });
});

test('the build declares no npm dependencies', () => {
  const pkg = JSON.parse(readFileSync(join(REPO, 'package.json'), 'utf8'));
  assert.deepEqual(pkg.dependencies ?? {}, {},
    'a CLJS build pulled npm packages in — check `:replace-deps` on the :cljs alias');
  const lock = JSON.parse(readFileSync(join(REPO, 'package-lock.json'), 'utf8'));
  const leaked = Object.keys(lock.packages ?? {}).filter(k => /markdown|punycode/.test(k));
  assert.deepEqual(leaked, [], 'markdown-it residue in the lock file');
});
```

- [ ] **Step 4: Run, watch the third test fail if you skipped Step 1.**

```
node --test test/browser/cljsbuild.test.mjs
```

- [ ] **Step 5: Green, then prove the npm test bites.** Temporarily change `:replace-deps` to `:extra-deps` in `deps.edn`'s `:cljs` alias, run a clean release build (`rm -rf .shadow-cljs && clojure -M:cljs -m shadow.cljs.devtools.cli release grid`), and confirm `package.json` gains dependencies and the test goes red. **Report what it installed.** Restore `:replace-deps`, then restore `package.json` and `package-lock.json` **from copies taken before this step** — `git checkout` them and you restore the residue Step 1 removed and has not yet committed — then `rm -rf node_modules && npm ci` and rebuild.

- [ ] **Step 6: Commit.**

```bash
git add deps.edn package.json package-lock.json resources/public/index.html test/browser/cljsbuild.test.mjs
git commit -m "build: the ClojureScript build is deliberate, and a test says it installs nothing"
```

---

## Task 2: ClojureScript that can be tested without a browser

**Files:**
- Modify: `shadow-cljs.edn`
- Modify: `deps.edn` — `:cljs` alias gains the test path
- Create: `test-cljs/loci/cljs_smoke_test.cljs`

Without this, every ClojureScript change is verified by a Playwright run measured in minutes, in a project whose working agreement makes the REPL the execution surface. `:node-test` runs `cljs.test` in Node in seconds.

- [ ] **Step 1: Add the test build.** `shadow-cljs.edn` becomes:

```clojure
{:deps    {:aliases [:cljs]}
 :nrepl   false
 :builds  {:grid {:target     :browser
                  :output-dir "resources/public/js"
                  :asset-path "/js"
                  :modules    {:grid {:init-fn loci.grid/init}}}
           ;; `:node-test` and not `:browser-test`: everything worth unit-testing
           ;; here is a state transition or a hiccup structure, both of which are
           ;; data. What needs a real DOM — sticky positions, scroll widths — is
           ;; measured by the Playwright suite instead, because a headless DOM
           ;; would answer those questions wrongly rather than not at all.
           ;;
           ;; **`.cjs`, not `.js`.** `package.json` says `"type": "module"` for
           ;; the Playwright suite, so Node reads a bare `.js` as ESM while
           ;; shadow-cljs emits CommonJS: `ReferenceError: __dirname is not
           ;; defined in ES module scope`, before the first test runs.
           :test {:target    :node-test
                  :output-to "target/cljs-test.cjs"
                  :ns-regexp "-test$"}}}
```

- [ ] **Step 2: Add the test path** to the `:cljs` alias in `deps.edn`:

```clojure
  :cljs  {:replace-paths ["src-cljs" "test-cljs"]
```

- [ ] **Step 3: Write the failing test.** Create `test-cljs/loci/cljs_smoke_test.cljs`. **`cljs-smoke-test`, not `smoke-test`** — `test/loci/smoke_test.clj` is already the JVM suite's canary, and two namespaces of one name make `Testing loci.smoke-test` ambiguous in the one place it is read:

```clojure
(ns loci.cljs-smoke-test
  "Proves the ClojureScript test loop exists at all. If this file stops running,
   every other cljs test in this plan is silently not running either."
  (:require [cljs.test :refer [deftest is]]
            [loci.grid :as grid]))

(deftest the-cljs-test-runner-runs
  (is (fn? grid/mount) "the module under test loads and exports its seam"))
```

- [ ] **Step 4: Run it.**

```
npm run test:cljs
```

Expected: `Testing loci.cljs-smoke-test` … `Ran 1 tests containing 1 assertions. 0 failures, 0 errors.` **Report the wall-clock time** — it is the number that justifies this task.

Add it to `package.json`'s `scripts` beside `test:browser`, **with a guard on the test count**, and quote `npm run test:cljs` everywhere after this. A `:node-test` build whose `:ns-regexp` matches nothing compiles, prints `Ran 0 tests containing 0 assertions` and **exits 0**, while a real failure exits 1 — so the raw `compile test && node …` cannot tell a suite that passed from a suite that was not there, and Tasks 6–10 put every ClojureScript test in this plan behind it.

POSIX `case` on the captured transcript, not `grep` in a pipeline: a pipeline's exit status is its LAST stage's, so `node … | grep …` discards node's. Measured 2026-08-25 against GNU grep 3.12, `node … | tee /dev/stderr | grep -qv 'Ran 0 tests'` exits **0 on a failing run and 0 on a zero-test run** — it catches neither of the two things it was for, because `-qv` asks "is any line NOT this?", which every transcript answers yes to.

- [ ] **Step 5: Prove it bites.** `:ns-regexp` matches the *namespace*, so rename the file to `test-cljs/loci/cljs_smoke.cljs` and its ns to `loci.cljs-smoke` (shadow-cljs requires the two to agree), recompile, and confirm the runner reports **0 tests** rather than passing. Measured 2026-08-25: the build drops from 79 compiled files to 51, prints `Ran 0 tests containing 0 assertions`, and **exits 0** — while a real failure exits 1. Nothing downstream can tell those apart, which is the whole reason this file exists. Restore, then break the assertion too (`(def ^:export mount 42)` in `loci.grid`) and confirm `expected: (fn? grid/mount) / actual: (not (fn? 42))`.

- [ ] **Step 6: Commit.**

```bash
git add shadow-cljs.edn deps.edn package.json test-cljs/loci/cljs_smoke_test.cljs
git commit -m "test: ClojureScript runs in Node, so the shell stops being Playwright-only"
```

---

# Phase 2 — Transit and the elision

## Task 3: Transit on one route, and a test that the namespace survives

**Files:**
- Modify: `src/loci/server.clj` — `transit-resp`/`wire-resp` docstrings only
- Modify: `test/loci/server_test.clj`

`wire-resp` exists from the spike and is correct. It has no test.

- [ ] **Step 1: Write the failing tests.** Append to `test/loci/server_test.clj`:

```clojure
(deftest transit-keeps-a-namespaced-keyword-and-json-does-not
  ;; The bug this exists to end: `clojure.data.json` renders a namespaced
  ;; keyword KEY by its bare name, so `:table/total` reaches the shell as
  ;; "total". Two tasks of the previous plan read `m['table/total']` and would
  ;; have found undefined; `index.html:2038` has said so since reclaimedHTML.
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 30)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "Windowed" rs {})})
      (let [json    (:body (srv/handler {:uri "/api/mold" :query-string "id=tbl:w&limit=5"}))
            transit (:body (srv/handler {:uri "/api/mold"
                                         :query-string "id=tbl:w&limit=5&wire=transit"}))]
        (is (str/includes? json "\"total\"")
            "JSON strips the namespace — this is the behaviour being routed around")
        (is (not (str/includes? json "table/total")))
        (is (str/includes? transit "~:table/total")
            "Transit writes the keyword whole")
        (is (= 2000 (:table/total (transit-read transit)))
            "and it reads back as the keyword, not as a string key")))))

(deftest a-request-without-wire-transit-is-byte-identical-to-today
  (with-lake [lk]
    (let [st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "doc:d"
                       :value {:id "doc:d" :kind :doc :title "D" :value "hello"}})
      (let [r (srv/handler {:uri "/api/mold" :query-string "id=doc:d"})]
        (is (= "application/json; charset=utf-8" (get-in r [:headers "Content-Type"])))))))
```

with this helper beside the other test helpers in the file:

```clojure
(defn- transit-read [^String s]
  (transit/read (transit/reader (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")) :json)))
```

and `[cognitect.transit :as transit]` plus `[clojure.string :as str]` in the test ns if absent.

**Correct the `2000` in the first test** to the table's real total (30) when you write it — it is written here as a deliberate wrong value so that a test copied without reading fails immediately.

- [ ] **Step 2: Run, watch fail.** Expected: the `2000` assertion fails, and after fixing it to `30` everything passes — `wire-resp` is already implemented.

- [ ] **Step 3: There is no Step 3.** The implementation exists; this task is the test that was missing. Instead, **read `transit-resp` and `wire-resp` and check their docstrings against what the code does.** This codebase treats a docstring that overstates as a defect. Fix anything that overstates; report what you found.

- [ ] **Step 4: Add the payload-size test** the design's §9 asks for. Transit caches repeated map keys, so a wide table writes each column name once rather than once per row:

```clojure
(deftest transit-is-smaller-than-json-on-a-wide-page
  ;; Measured 2026-08-25 on a 36-column, 100-row page: 100,324 bytes of JSON
  ;; against 58,728 of Transit. Asserting a RATIO and not that figure — the
  ;; figure is one fixture's arithmetic, the ratio is the mechanism.
  (with-lake [lk]
    (let [st   (sub/fresh-store)
          cols (mapv #(keyword (str "column_name_" %)) (range 36))
          rs   (vec (for [i (range 200)]
                      (into {} (map (fn [c] [c (str "value-" i)])) cols)))
          size (fn [qs] (count (.getBytes ^String (:body (srv/handler {:uri "/api/mold"
                                                                      :query-string qs}))
                                          "UTF-8")))]
      (sub/commit! st {:op :put :id "tbl:wide"
                       :value (tbl/table-object lk "tbl:wide" "Wide" rs {})})
      (let [j (size "id=tbl:wide&limit=100")
            t (size "id=tbl:wide&limit=100&wire=transit")]
        (is (< t (* 0.8 j))
            (str "transit " t " vs json " j " — key caching should pay on a wide table"))))))
```

**Report both byte counts.**

- [ ] **Step 5: Prove they bite.** Change `wire-resp` to ignore the parameter and always return `json-resp`. `transit-keeps-a-namespaced-keyword-and-json-does-not` must go red on the `~:table/total` assertion, and `transit-is-smaller-than-json-on-a-wide-page` must go red with two identical byte counts. Restore.

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "test: Transit keeps :table/total whole, and JSON is measured losing it"
```

---

## Task 4: the elision replaces the cursor

**Files:**
- Modify: `src/loci/tablestore.clj` — the `page` protocol docstring, `InlineTable/page`
- Modify: `src/loci/lake.clj` — `Lake/page`
- Modify: `src/loci/server.clj` — `mold-payload`'s `:else` branch
- Modify: `test/loci/tablestore_test.clj`, `test/loci/lake_test.clj`, `test/loci/server_test.clj`

Spec §3.1. `:page/next` becomes an elision carrying everything the caption and the next request need.

**`:offset` must be a fact in all three modes**, which means `page` starts returning it:

- **Lake, keyset**: the first returned row's `rowid`. `rowid` is 0-based and dense, so it *is* the ordinal.
- **Lake, offset**: the offset it was given.
- **InlineTable**: the index it was given.

- [ ] **Step 1: Write the failing tests.** In `test/loci/tablestore_test.clj`:

```clojure
(deftest a-page-reports-where-it-starts
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)
        p1 (tbl/page ts h {:limit 100})
        p2 (tbl/page ts h {:limit 100 :cursor (:next p1)})]
    (is (= 0   (:offset p1)) "page one starts at row zero")
    (is (= 100 (:offset p2)) "and page two says so without the caller counting")))
```

In `test/loci/lake_test.clj`:

```clojure
(deftest a-lake-page-reports-its-offset-in-both-modes
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:o" (vec (for [i (range 250)] {:i i :grp (str "g" (mod i 5))})))]
      ;; keyset: rowid is dense and 0-based, so the first row's rowid IS the ordinal
      (let [p1 (tbl/page lk h {:limit 100})
            p2 (tbl/page lk h {:limit 100 :cursor (:next p1)})]
        (is (= 0 (:offset p1)))
        (is (= 100 (:offset p2)) "keyset knows its ordinal from rowid"))
      ;; offset mode, forced by a sort
      (let [p1 (tbl/page lk h {:limit 100 :sort ["grp" "asc"]})
            p2 (tbl/page lk h {:limit 100 :cursor (:next p1) :sort ["grp" "asc"]})]
        (is (= 0 (:offset p1)))
        (is (= 100 (:offset p2)))))))
```

In `test/loci/server_test.clj`:

```clojure
(deftest a-rows-mold-carries-an-elision-and-not-a-bare-cursor
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "W" rs {})})
      (let [{:keys [elision] :as m} (srv/mold-payload st no-memory "tbl:w" nil {:limit 100})]
        (is (nil? (:page/next m)) "the bare cursor is gone")
        (is (= 100 (:n elision)))
        (is (= 0   (:offset elision)))
        (is (= 250 (:total elision)))
        (is (false? (:unbounded? elision)))
        (is (= [:rendered] (:path elision)))
        (is (string? (:cursor elision)) "opaque, and present because there IS a next page")))))

(deftest the-last-page-has-no-elision
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 30)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "W" rs {})})
      (is (nil? (:elision (srv/mold-payload st no-memory "tbl:w" nil {:limit 100})))
          "nothing was omitted, so there is no hole to describe"))))

(deftest an-aggregate-view-has-no-elision
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i :grp (str "g" (mod i 5))}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "W" rs {})})
      (is (nil? (:elision (srv/mold-payload st no-memory "tbl:w" "table/summary" {:limit 10})))
          "a summary is not a window over rows"))))
```

- [ ] **Step 2: Run, watch all five fail.**

- [ ] **Step 3: `page` returns `:offset`.** In `src/loci/tablestore.clj`, extend the protocol docstring and `InlineTable/page`:

```clojure
      {:rows   (mapv #(select-keys % proj) (take lim got))
       :offset i
       :next   (when more (encode-cursor {:i (+ i lim)}))}
```

In `src/loci/lake.clj`, `Lake/page`:

```clojure
      {:rows   (mapv #(dissoc % :rowid) kept)
       ;; **`:offset` is a fact in both modes, and it is not the same fact.**
       ;; Offset mode was handed one. Keyset mode derives it from the first row's
       ;; `rowid`, which is the ordinal only because a DuckLake table's rowid is
       ;; dense and 0-based — the same property `WHERE rowid > k` already relies
       ;; on. An empty page has no first row and no position to report, so nil.
       :offset (if seek? (:rowid (first kept)) off)
       :next   (when more
                 (tbl/encode-cursor (if seek?
                                      {:k (:rowid (peek kept))}
                                      {:o (+ off lim) :s srt})))}
```

- [ ] **Step 4: `hydrate-page` carries it, and `mold-payload` builds the elision.**

In `src/loci/tablestore.clj`, `hydrate-page` assocs `:page/offset` beside `:page/next`.

In `src/loci/server.clj`, replace the `:page/next` clause of the `:else` branch:

```clojure
          ;; **The elision, and ONLY for a viewer that renders rows.** A hole is
          ;; a statement about omitted rows; `table/summary` renders a chart
          ;; computed from something else and a pager under it would be a lie.
          ;;
          ;; Absent when nothing was omitted — `page` returns no cursor on the
          ;; last page, and a hole describing zero missing rows is not a hole.
          ;;
          ;; `:path` is `[:rendered]` and single-element by construction:
          ;; `normalize-rows` refuses a vector or a map in a column, so a table
          ;; payload has exactly one hole and it is at the end. The field is here
          ;; rather than omitted so that a nested elision needs no second shape —
          ;; see §3 of the design.
          (and (:page/next o) (#{:table/rows :table/cards} (:view mv)))
          (assoc :elision {:n          (tbl/page-limit (:limit window))
                           :offset     (:page/offset o)
                           :total      (:table/total o)
                           :unbounded? (boolean (:table/unbounded o))
                           :path       [:rendered]
                           :cursor     (:page/next o)})
```

- [ ] **Step 5: Green.** **Step 6: Prove they bite**, one at a time, restoring after each:

| break | test that must go red |
|---|---|
| `Lake/page` keyset returns `off` (always 0) instead of the rowid | `a-lake-page-reports-its-offset-in-both-modes` |
| build the elision unconditionally, dropping the `#{:table/rows :table/cards}` guard | `an-aggregate-view-has-no-elision` |
| build the elision even when `:page/next` is nil | `the-last-page-has-no-elision` |
| keep `:page/next` on the payload beside `:elision` | `a-rows-mold-carries-an-elision-and-not-a-bare-cursor` |

- [ ] **Step 7: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/tablestore.clj src/loci/lake.clj src/loci/server.clj test/loci/
git commit -m "feat: the payload carries a hole that knows it is a hole"
```

---

## Task 5: `:unbounded?` is real, and a notebook gets a budget

**Files:**
- Modify: `src/loci/tablestore.clj` — `hydrate-page`
- Modify: `src/loci/server.clj` — `notebook-payload`
- Modify: `test/loci/server_test.clj`

Two things §3 asks for that Task 4 stubbed.

**`:unbounded?`** — `:table/total` is `(:rows h)`, a count recorded at connect. For a `:source` handle the file can have changed since, so the total is a claim rather than a fact and the caption must say `1,975+` rather than a confident wrong number.

**The budget** — `notebook-payload` merges one mold per `:ref` cell, so five table cells are five pages in one response. `mold-cap` bounds one table; a budget bounds the response.

- [ ] **Step 1: Write the failing tests.**

```clojure
(deftest a-live-sources-total-is-marked-unbounded
  (with-lake [lk]
    (let [st  (sub/fresh-store)
          dir (doto (java.io.File. (str (.getAbsolutePath (java.io.File. ".tmp"))
                                        "/unbounded-" (System/nanoTime))) .mkdirs)
          csv (java.io.File. dir "rows.csv")]
      (spit csv (apply str "region,amount\n" (for [i (range 250)] (str "r" (mod i 5) "," i "\n"))))
      (try
        (loci.source/connect! st {:uri (str "file://" (.getAbsolutePath csv)) :format :csv
                                  :mode :passthrough :title "Live"} nil)
        (let [id (first (for [[k o] (sub/objects (sub/state st)) :when (= :table (:kind o))] k))
              e  (:elision (srv/mold-payload st no-memory id nil {:limit 100}))]
          (is (true? (:unbounded? e))
              "a passthrough total was recorded at connect and the file can have moved since"))
        (finally (lf/rm-rf dir))))))

(deftest a-materialised-tables-total-is-not-unbounded
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "W" rs {})})
      (is (false? (:unbounded? (:elision (srv/mold-payload st no-memory "tbl:w" nil {:limit 100}))))
          "loci wrote these rows and nothing else can have changed them"))))

(deftest a-notebooks-cells-share-one-budget
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))
          mk (fn [id] {:op :put :id id :value (tbl/table-object lk id id rs {})})]
      (sub/commit! st {:op :tx :events
                       [(mk "tbl:a") (mk "tbl:b") (mk "tbl:c") (mk "tbl:d") (mk "tbl:e")
                        {:op :put :id "space:n"
                         :value {:id "space:n" :kind :space :title "N"
                                 :value {:intent "five tables"
                                         :cells (mapv (fn [i] {:ref i})
                                                      ["tbl:a" "tbl:b" "tbl:c" "tbl:d" "tbl:e"])}}}]})
      (let [cells (:cells (srv/notebook-payload st "space:n"))
            rows  (reduce + (map #(count (:rendered %)) cells))]
        (is (= 5 (count cells)))
        (is (<= rows srv/notebook-budget)
            (str "five cells shipped " rows " rows; the budget is the response, not the table"))
        (is (every? #(pos? (count (:rendered %))) cells)
            "and every cell still draws — a budget divides, it does not starve")))))
```

- [ ] **Step 2: Run, watch fail.**

- [ ] **Step 3: Implement `:unbounded?`.** In `hydrate-page`, mark it from the handle's own mode:

```clojure
        (let [{:keys [rows next offset]} (page ts h window)]
          (cond-> (assoc o :value rows
                           :table/total (row-count ts h)
                           :table/cols (:cols h)
                           :page/offset offset
                           :page/next next)
            ;; A `:source` handle's row count was recorded at connect and the
            ;; file it counted can have been rewritten since with no event
            ;; anywhere — the same reason `:table/passthrough` exists. So the
            ;; total is a claim, and the caption has to say so.
            (= :source (:store h)) (assoc :table/passthrough true
                                          :table/unbounded true)))
```

- [ ] **Step 4: Implement the budget.** In `src/loci/server.clj`, beside `notebook-payload`:

```clojure
(def notebook-budget
  "How many rows a whole notebook payload may carry, across every `:ref` cell.

   **The response is the thing that was too big, not the table.** Measured on
   the real substrate before this work: one 36-column, 2,000-row cell was
   5,428,753 bytes, and `notebook-payload` merges one mold per cell — so five
   such cells were five copies of that in a single response. `mold-cap` bounds a
   table; nothing bounded the response.

   Clerk's `:!budget` is the same idea and defaults to 200 items. 500 here
   because loci's cells are tables rather than arbitrary nested values, and a
   notebook of five tables showing 100 rows each is an ordinary page."
  500)
```

and divide it in `notebook-payload`, computing the per-cell limit from the number of `:ref` cells before molding any of them:

```clojure
  (let [o     (sub/object st id)
        refs  (count (filter :ref (nb/cells-of o)))
        ;; A share each, floored at 1 — a notebook of six hundred table cells
        ;; still draws a row per cell rather than an empty grid per cell.
        per   (max 1 (quot notebook-budget (max 1 refs)))
        ...
                               (mold-payload st mem/memory (:ref c) (:view c) {:limit per}))
```

- [ ] **Step 5: Green.** **Step 6: Prove they bite** — pass `{:limit tbl/default-limit}` per cell instead of the share (`a-notebooks-cells-share-one-budget` must report 500 rows against a 500 budget… confirm the arithmetic and **report the real number**); and drop the `:table/unbounded` assoc (`a-live-sources-total-is-marked-unbounded` goes red).

- [ ] **Step 7: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/tablestore.clj src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: a live total says it is a claim, and a notebook budgets its response"
```

---

# Phase 3 — the Replicant grid

## Task 6: state, actions and fetch — no DOM

**Files:**
- Rewrite: `src-cljs/loci/grid.cljs`
- Create: `src-cljs/loci/grid/view.cljs`
- Create: `test-cljs/loci/grid_test.cljs`

The spike's 77 lines are replaced. Two namespaces: `grid` owns state and effects, `grid/view` is a pure function of state to hiccup and requires nothing.

- [ ] **Step 1: Write the failing tests.** Create `test-cljs/loci/grid_test.cljs`:

```clojure
(ns loci.grid-test
  "State transitions and the view's DATA. What needs a real DOM — sticky
   positions, scroll width — is measured by test/browser/grid.test.mjs, because
   a headless DOM answers those questions wrongly rather than not at all."
  (:require [cljs.test :refer [deftest is]]
            [loci.grid :as grid]
            [loci.grid.view :as view]))

(def ^:private payload
  {:table/cols [:a :b]
   :rendered   [{:a 1 :b "x"} {:a 2 :b "y"}]
   :elision    {:n 2 :offset 0 :total 10 :unbounded? false
                :path [:rendered] :cursor "CUR1"}})

(deftest a-fresh-grid-is-on-page-one-with-an-empty-stack
  (let [s (grid/init-state "tbl:x")]
    (is (= "tbl:x" (:id s)))
    (is (= [nil] (:stack s)) "the cursor that began page one is no cursor at all")
    (is (= 0 (:page s)))
    (is (= 100 (:limit s)))))

(deftest next-pushes-the-elisions-cursor-and-prev-pops-it
  (let [s0 (assoc (grid/init-state "tbl:x") :payload payload)
        s1 (grid/next-page s0)
        s2 (grid/prev-page s1)]
    (is (= [nil "CUR1"] (:stack s1)))
    (is (= 1 (:page s1)))
    (is (= "CUR1" (grid/current-cursor s1)) "the next request asks from the hole")
    (is (= 0 (:page s2)))
    (is (nil? (grid/current-cursor s2)) "prev is a pop, not a recomputation")))

(deftest prev-on-page-one-does-nothing
  (let [s (grid/init-state "tbl:x")]
    (is (= s (grid/prev-page s)))))

(deftest changing-the-page-size-restarts-the-walk
  ;; A cursor names a position in a walk of a given size. Keeping the stack
  ;; would leave the caption's arithmetic describing rows that are not there.
  (let [s1 (grid/next-page (assoc (grid/init-state "tbl:x") :payload payload))
        s2 (grid/set-limit s1 25)]
    (is (= 25 (:limit s2)))
    (is (= [nil] (:stack s2)))
    (is (= 0 (:page s2)))))

(deftest a-fetched-page-REPLACES-the-rows-it-does-not-append
  ;; The decision in §3.1, and the defect the spike shipped: an elision labelled
  ;; "N more" that replaced. loci replaces AND says so; this test is what stops
  ;; someone "fixing" it into Clerk's accretion.
  (let [s1 (grid/on-payload (grid/init-state "tbl:x") payload)
        s2 (grid/on-payload s1 (assoc payload :rendered [{:a 3 :b "z"}]
                                              :elision nil))]
    (is (= 1 (count (:rendered (:payload s2)))))
    (is (= 3 (:a (first (:rendered (:payload s2))))) "page two only")))

(deftest the-caption-counts-from-the-servers-offset-not-the-clients-page
  (let [s (grid/on-payload (grid/init-state "tbl:x")
                           (assoc-in payload [:elision :offset] 400))]
    (is (= "showing 401–402 of 10" (view/caption s))
        "the server's offset is a fact; the client's page index is arithmetic")))

(deftest an-unbounded-total-says-so
  (let [s (grid/on-payload (grid/init-state "tbl:x")
                           (assoc-in payload [:elision :unbounded?] true))]
    (is (= "showing 1–2 of 10+" (view/caption s)))))

(deftest a-last-page-has-no-elision-and-next-is-disabled
  (let [s (grid/on-payload (grid/init-state "tbl:x") (dissoc payload :elision))]
    (is (not (view/has-next? s)))
    (is (= "showing 1–2 of 2" (view/caption s))
        "with no hole, the total is what is on screen")))
```

- [ ] **Step 2: Run, watch fail.**

```
npm run test:cljs
```

- [ ] **Step 3: Implement `src-cljs/loci/grid.cljs`.**

```clojure
(ns loci.grid
  "The ClojureScript grid: state, actions and the one effect.

   Two namespaces on purpose. This one owns state and talks to the network;
   `loci.grid.view` is a pure function of state to hiccup and requires nothing,
   so every question about what gets drawn is answerable without a DOM.

   **State lives on the server.** This atom holds the last payload plus the
   view-local preferences a person set — the page they are on, the size, the
   sort, the hidden columns. Nothing here is a second source of truth about
   content: `undo!` and `?at=` work because the substrate owns that, and a
   ratom-shaped cache of it is exactly what this design refuses."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [loci.grid.view :as view]
            [replicant.dom :as r]))

(defonce !grids (atom {}))            ; DOM element -> state
(def ^:private reader (t/reader :json))

(defn init-state [id]
  ;; `:stack` holds the cursor that BEGAN each visited page, so Prev is a pop and
  ;; is exact. A server-computed `prev` would be a second source of truth able to
  ;; disagree with it, and the store cannot compute one under keyset anyway.
  {:id id :payload nil :stack [nil] :page 0 :limit 100 :sort nil :hidden #{}})

(defn current-cursor [s] (nth (:stack s) (:page s) nil))
;; `has-next?` is `loci.grid.view/has-next?` — a pure question about a payload,
;; and the view namespace is the one that requires nothing. Not duplicated here.

(defn on-payload [s payload] (assoc s :payload payload))

(defn next-page [s]
  (if-let [c (get-in s [:payload :elision :cursor])]
    (-> s (assoc :stack (conj (vec (take (inc (:page s)) (:stack s))) c))
          (update :page inc))
    s))

(defn prev-page [s] (cond-> s (pos? (:page s)) (update :page dec)))

(defn set-limit [s n]
  ;; A cursor names a position in a walk of a given size, so a new size
  ;; invalidates every cursor in the stack. Back to page one, which is honest —
  ;; the alternative is a caption whose arithmetic no longer matches the rows.
  (assoc s :limit n :stack [nil] :page 0))

(defn toggle-sort [s col]
  ;; Three states, not two: ascending, descending, and back to the table's own
  ;; order — which for a lake table is the only order that pages by seeking
  ;; rather than by counting, so it must be reachable rather than lost on the
  ;; first click. Reordering invalidates the stack for the same reason a resize
  ;; does.
  (let [[c d] (:sort s)
        nxt   (cond (not= c col) [col "asc"] (= d "asc") [col "desc"] :else nil)]
    (assoc s :sort nxt :stack [nil] :page 0)))

(defn toggle-col [s col]
  (assoc s :hidden (if (contains? (:hidden s) col)
                     (disj (:hidden s) col)
                     (conj (:hidden s) col))
           :stack [nil] :page 0))

(defn- visible-cols [s]
  (let [all (get-in s [:payload :table/cols])]
    (when (seq (:hidden s)) (vec (remove (:hidden s) all)))))

(defn- url [s]
  (str "/api/mold?wire=transit&id=" (js/encodeURIComponent (:id s))
       "&limit=" (:limit s)
       (when-let [c (current-cursor s)] (str "&cursor=" (js/encodeURIComponent c)))
       (when-let [[c d] (:sort s)] (str "&sort=" (js/encodeURIComponent (str (name c) "," d))))
       (when-let [cs (visible-cols s)]
         (str "&cols=" (js/encodeURIComponent (str/join "," (map name cs)))))))

(defn- render! [el]
  (r/render el (view/grid (get @!grids el))))

(defn refresh!
  "The one effect. Everything else in this namespace is a pure state transition."
  [el]
  (-> (js/fetch (url (get @!grids el)))
      (.then #(.text %))
      (.then (fn [body]
               (swap! !grids update el on-payload (t/read reader body))
               (render! el)))))

(defn- act! [el f & args]
  (swap! !grids update el #(apply f % args))
  (refresh! el))

(r/set-dispatch!
 (fn [_ [[action el arg]]]
   (case action
     :page/next (act! el next-page)
     :page/prev (act! el prev-page)
     :page/size (act! el set-limit arg)
     :sort/toggle (act! el toggle-sort arg)
     :cols/toggle (act! el toggle-col arg)
     (js/console.warn "loci.grid: unknown action" (pr-str action)))))

(defn ^:export mount
  "Called BY the vanilla shell from `wireSourceButtons`, with the node it just
   inserted. That is the whole integration surface."
  [el id]
  (swap! !grids assoc el (init-state id))
  (refresh! el))

(defn init [])
```

- [ ] **Step 4: Implement `src-cljs/loci/grid/view.cljs`** with `caption` and a `grid` stub returning `[:div]`, enough for the tests. The real markup lands in Task 7.

```clojure
(ns loci.grid.view
  "Hiccup, and nothing else. Requires no DOM, no network and no state container,
   so every question about what gets drawn is answered by `cljs.test` in Node."
  (:require [clojure.string :as str]))

(defn caption
  "`showing 401–402 of 10,000,000`, or `… of 10,000,000+` when the total is a
   claim rather than a fact.

   **Counted from the server's `:offset`, never from the client's page index.**
   The client's is arithmetic over a page size it may have changed; the server's
   is where the rows it just sent actually start."
  [{:keys [payload]}]
  (let [{:keys [rendered elision table/total]} payload
        off   (or (:offset elision) 0)
        n     (count rendered)
        total (or (:total elision) total n)]
    (str "showing " (inc off) "–" (+ off n) " of " total
         (when (:unbounded? elision) "+"))))

;; Takes the element as well as the state, from the start: the pager, the sort
;; headers and the column chooser all dispatch actions that must name WHICH grid
;; they act on, and a signature that grows between tasks is one two call sites
;; disagree about for a commit.
(defn has-next?
  "Is there a hole? A page with no elision omitted nothing, so there is nowhere
   to go. Here rather than in `loci.grid` because it is a pure question about a
   payload, and this namespace is the one that requires nothing."
  [{:keys [payload]}]
  (some? (get-in payload [:elision :cursor])))

(defn grid [_el _state] [:div.grid])
```

- [ ] **Step 5: Green.** **Step 6: Prove they bite:**

| break | test that must go red |
|---|---|
| `on-payload` merges `:rendered` instead of replacing | `a-fetched-page-REPLACES-the-rows-it-does-not-append` |
| `caption` uses `(* page limit)` instead of the server's `:offset` | `the-caption-counts-from-the-servers-offset-not-the-clients-page` |
| `prev-page` recomputes a cursor rather than popping | `next-pushes-the-elisions-cursor-and-prev-pops-it` |
| `set-limit` keeps the stack | `changing-the-page-size-restarts-the-walk` |
| `caption` drops the `+` | `an-unbounded-total-says-so` |

- [ ] **Step 7: Commit.**

```bash
git add src-cljs test-cljs shadow-cljs.edn
git commit -m "feat: the grid's state is data, and a page replaces rather than accretes"
```

---

## Task 7: the table it draws

**Files:** `src-cljs/loci/grid/view.cljs` · `resources/public/index.html` (CSS only) · `test/browser/harness.mjs` (`GRID_FIXTURE`) · `test/browser/grid.test.mjs` (new)

**Partly done already.** Phase 3 was stopped mid-task; `git status` shows uncommitted work in all four files. Read it first and decide whether to finish or restart it — say which and why.

**What it must do.** Render rows as a table using the *existing* `table.mold` styling, with: the table sized `width: max-content` so it is as wide as its columns and the wrapper scrolls; a header that stays put vertically; a first column that stays put horizontally; numeric columns right-aligned from the values, not from the column name; and a `title` tooltip clipped to ~500 characters.

**Why `max-content` and not per-column arithmetic.** The old renderer computed a character width per column and then normalised those to percentages of a `width:100%` table, which throws the scale away — at 36 columns the 7-character floor became 0.7 characters, and the horizontal scrollbar was `.toFixed(2)` rounding drift with nothing behind it. One CSS property replaces the arithmetic. Clerk reaches the same result the same way.

**The fixture.** 36 columns and 250 rows, in its own notebook. 36 because that is the width at which the old renderer collapsed. 250 because at limit 100 that is three pages with a short last one — the only way to see the elision go absent. Give it a `grp` column with five values (50 rows each, so a page boundary lands inside a tied run), a `note` column with one 900-character value, and a `maybe` column null every third row. Assert in the fixture that it really is 36 columns wide, so it cannot silently stop being the thing under test. It must exceed DuckLake's ~10–20 row inlining threshold or the keyset branch is not what is being exercised.

**Tests — all four measure the DOM, none inspect a stylesheet.**

| must establish | measured by |
|---|---|
| 36 columns overflow the panel, and there is real scroll | table width vs `clientWidth`; `scrollWidth − clientWidth` well past rounding |
| a narrow column shows its whole value | `grp`'s first cell reads `g0`, not `g…` |
| the header survives scrolling | `th` bounding-rect top unchanged after `scrollTop = 300` |
| the first column survives scrolling | first `td` bounding-rect left unchanged after `scrollLeft = 600` |
| a long value does not become a long tooltip | the `note` cell's `title` length |

**Sabotage.** Restore `width:100%` — the first two must go red. Remove `position:sticky` from the header, then from the first column — one each. Drop the clip — the tooltip test reports 900.

**A sabotage that does NOT work, found 2026-08-25.** Removing the frozen column's `background` does not fail an `elementFromPoint` check: hit-testing returns the topmost element whether or not it is transparent. So a "the frozen column is opaque" assertion written that way passes while rows show through it. Either find an assertion that actually detects the transparency — a screenshot pixel comparison is the honest instrument — or **drop the claim and do not pretend to test it**. Say which you chose.

---

## Task 8: the pager

**Files:** `src-cljs/loci/grid/view.cljs` · `test/browser/grid.test.mjs`

**What it must do.** A caption reading `showing 101–200 of 250`, Prev and Next, and a page-size select of 25/50/100/200. Next is disabled when the payload carries no elision; Prev when the page index is zero.

**The decision it embodies.** loci's elision **replaces** the page; Clerk's, which it borrows from, **appends**. Forty clicks of "more" on a 10M-row table would put four thousand rows in the DOM, and bounded DOM was half the point of paging. Task 6 already has `a-fetched-page-REPLACES-the-rows-it-does-not-append` guarding the state transition; this task's browser test guards the rendered result.

**Prev is a stack pop.** `loci.grid` keeps the cursor that began each visited page. A server-computed `prev` would be a second source of truth able to disagree with it, and under keyset the store cannot compute one anyway.

**Replicant's event-value API is unverified.** The dep is `no.cjohansen/replicant 2025.06.21`; its README is on the classpath. Find the current spelling for "the value of the event that fired". If you cannot confirm it in five minutes, dispatch with no argument and read the value off the DOM event in `set-dispatch!`. **Report which you used and how you confirmed it.**

**Tests.**

| must establish | measured by |
|---|---|
| the walk reaches the end and stops | captions across three Nexts; 50 rows on the last; Next disabled |
| a page replaces rather than accretes | **100 rows after one Next, not 200** |
| Prev is exact | after Next, Next, Prev the caption is page two's again |
| page size restarts the walk | select 25 → caption `1–25`, Prev disabled |
| the shell never reads a cursor | scan `src-cljs/` for `:k`, `:o`, `atob`, `JSON.parse` — none may appear |

**Sabotage.** Make `on-payload` append — the row count assertion goes red. Make Prev recompute rather than pop — the Prev caption goes red. Make `set-limit` keep the stack — the restart test goes red.

---

## Task 9: click a header to sort

**Files:** `src-cljs/loci/grid/view.cljs` · `test-cljs/loci/grid_test.cljs` · `test/browser/grid.test.mjs`

`toggle-sort` exists from Task 6, and the window carries `:sort` to the server, which does the sorting. This is only the control.

**Three states, not two.** Ascending → descending → **the table's own order**. Natural order is the only order a lake table pages by *seeking* rather than by counting, so it has to be reachable rather than lost on the first click.

**Sorting invalidates every cursor in the stack**, for the same reason changing the page size does: a cursor names a position in one ordering.

**Tests.** In `cljs.test`: the three-state cycle, and that a different column starts its own cycle, and that sorting resets stack and page. In the browser: clicking `grp`'s header puts `g0` first, again puts `g4` first, a third time restores `row-0`; and sorting from page two returns to page one with Prev disabled.

**Sabotage.** Make the third click return to ascending — the natural-order assertion goes red. Keep the stack across a sort — the reset test goes red.

---

## Task 10: the column chooser, remembered

**Files:** `src-cljs/loci/grid.cljs` · `src-cljs/loci/grid/view.cljs` · `test-cljs/loci/grid_test.cljs` · `test/browser/grid.test.mjs`

**What it must do.** A `<details>` listing every column with a checkbox; unchecking one removes it from the **request**, not merely from the page. Sort, hidden columns and page size persist per object id.

**Two things that are easy to get wrong.**

The chooser lists the payload's `:table/cols`, which `hydrate-page` takes off the **handle**. A page projected with `:cols` no longer contains the hidden columns, so reading the list back from the rendered rows would make hiding one-way — you could never unhide.

When nothing is hidden, send **no** `cols` parameter rather than the full list. Server-side an empty `cols` means all columns, and an explicit full list would silently drop a column added to the table later.

**Persistence is browser-local and never a substrate event.** The event log is what `?at=` rewinds and `undo!` reverses; a column-header click is fiddling with a lens, not an act on content, and every one becoming a point in history is noise in the one log that has to stay meaningful. (`:view` stays a substrate event, as today — chosen once, not a knob.) Use Transit for the stored value so a sort of `[:grp "asc"]` round-trips as keywords.

**Wrap every storage access.** A private window, cleared site data or a browser set to block storage throws on the **accessor itself**, so an unguarded read takes the whole panel down rather than returning nothing.

**Tests.** In `cljs.test`: hiding a column produces the right `cols` parameter, and hiding nothing produces none. In the browser: the request URL for a hidden column omits it — assert on the URL, because asserting on the rendered page passes when the filtering happens client-side; preferences survive a reload; and a browser whose `localStorage` accessor throws still draws rows.

**Sabotage.** Filter columns in the view instead of sending `cols` — the URL assertion goes red while the page still looks right, which is the point. Remove the storage `try` — the private-window test goes red with a blank panel.

---

## Task 11: the browser suite moves to the new markup

**Files:** whichever files in `test/browser/` assert on a table's DOM.

`rg -ln "table\.mold|tableHTML|thead|tbody" test/browser/` — **report the count before changing anything.**

For each, decide and record which of two things it is: a test *about a table's markup* (rewrite against the grid), or a test that merely *uses a table to reach something else* (leave it — it will exercise whichever renderer is default). One file per commit, suite green after each.

**Report any test you could not port, and why.** A test that cannot be expressed against the new markup is telling you something about the new markup.

---

## Task 12: the flag goes, and so does `tableHTML`

**Files:** `resources/public/index.html` · `test/browser/cljsbuild.test.mjs`

Delete `CLJS_GRID` and its branch so the grid is simply what a table renders as. Then delete `tableHTML` and the `table.mold` rules the grid does not use.

**`tableHTML` has two other callers** — `renderReport`'s table blocks and the ƒ preview's before/after. Neither is windowed and neither has an elision. **Recommended: give them a small local `staticTable(rows)`** rather than mounting a component per report block: they are a handful of rows inside prose, they need no pager, sort or chooser, and they already have their rows in hand. Take the markup and the numeric alignment; drop the width arithmetic and the 60-row cap. If you disagree, argue it rather than implementing it.

`rg -n "tableHTML" resources/public/index.html` and **report every site** before deleting anything.

The build test's "without `?cljs` the shell renders its own table" case is now false — replace it with one asserting the grid mounts with no flag.

---

## Finishing

- [x] `clojure -M:test`, `npm run test:cljs` and `npm run test:browser`, all green, all foreground. 915/4,817 · 30/78 · 180.
- [x] Update the spec's Status to **built and measured**, recording what building changed: the Replicant event form from Task 8, the frozen-column sabotage that could not work, and how many browser tests Task 11 had to rewrite. (**None**, which is the finding — see the spec's Status section.)
- [ ] `superpowers:finishing-a-development-branch` to decide how `spike-cljs` lands, and whether it merges into `paged-table-grid` or replaces it.
