# ClojureScript Shell and the Elision Protocol — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Rows view becomes a ClojureScript component — paged, sorted, sticky-headed, with a frozen first column and a column chooser — fed by a Transit payload whose truncation is a labelled hole rather than silence.

**Architecture:** Replicant renders hiccup into a node the existing vanilla shell hands it, so the other ~3,000 lines don't move. The payload carries an **elision** — Clerk's shape, loci's semantics: it *replaces* the page rather than appending, and carries the opaque cursor `TableStore/page` already understands. Transit replaces JSON on that one route so a namespaced keyword survives the wire.

**Tech Stack:** Clojure 1.12, shadow-cljs 3.2.0, Replicant 2025.06.21, Transit (`transit-clj` 1.0.333 / `transit-cljs` 0.8.280), DuckDB/DuckLake, Playwright via `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-25-cljs-shell-and-elision-design.md`. Read §3 and §3.1 before Task 4 — the replace-not-append decision is the one thing in this plan a reader will find surprising.

---

## Ground rules for every task

- **The REPL is the execution surface.** `ToolSearch` for `select:mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888; `:reload` after every edit. Never `clojure -M -e` to iterate — a cold JVM costs ~25 s. `clojure -M:test` is for one final full-suite run per task.
- **Prove every test bites.** Reintroduce the defect, watch it go red, and **read the failure** rather than counting failures. Three sabotage rows in the previous plan for this work were wrong, and were caught only because implementers read failures.
- **Measure, don't assert.** A browser test that checks a CSS rule is present passes while the header scrolls away. Assert on measured positions and sizes.
- **`rg -n`, never `rg -rn`** — `-r` is *replace* and silently rewrites every match in the output. Plain `grep` is unreliable on `index.html` because of its long lines.
- **Everything FOREGROUND**, timeout up to 900000 ms. Never background a command; two subagents have already parked on a backgrounded browser run in this project.
- **Two Clojure suites and one JS suite.** `clojure -M:test`, `npm run test:browser`, and from Task 2 also `clojure -M:cljs -m shadow.cljs.devtools.cli compile test && node target/cljs-test.js`.

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

test('the compiled module is served and defines its mount function', async () => {
  await withPage(browser, 'cljs-loads', async (page) => {
    await bootedShell(page, server.url + '?cljs');
    await page.waitForFunction(() => window.loci && typeof window.loci.grid.mount === 'function',
                               null, { timeout: 15000 });
  });
});

test('without ?cljs the shell renders its own table and mounts nothing', async () => {
  await withPage(browser, 'cljs-off', async (page) => {
    await bootedShell(page, server.url);
    const n = await page.evaluate(() => document.querySelectorAll('[data-cljs-grid]').length);
    assert.equal(n, 0, 'the ClojureScript seam must be inert without the flag');
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
npm run test:browser -- --test-name-pattern=cljs
```

- [ ] **Step 5: Green, then prove the npm test bites.** Temporarily change `:replace-deps` to `:extra-deps` in `deps.edn`'s `:cljs` alias, run a clean release build (`rm -rf .shadow-cljs && clojure -M:cljs -m shadow.cljs.devtools.cli release grid`), and confirm `package.json` gains dependencies and the test goes red. **Report what it installed.** Restore `:replace-deps`, `git checkout package.json package-lock.json`, rebuild.

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
- Create: `test-cljs/loci/smoke_test.cljs`

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
           :test {:target    :node-test
                  :output-to "target/cljs-test.js"
                  :ns-regexp "-test$"}}}
```

- [ ] **Step 2: Add the test path** to the `:cljs` alias in `deps.edn`:

```clojure
  :cljs  {:replace-paths ["src-cljs" "test-cljs"]
```

- [ ] **Step 3: Write the failing test.** Create `test-cljs/loci/smoke_test.cljs`:

```clojure
(ns loci.smoke-test
  "Proves the ClojureScript test loop exists at all. If this file stops running,
   every other cljs test in this plan is silently not running either."
  (:require [cljs.test :refer [deftest is]]
            [loci.grid :as grid]))

(deftest the-cljs-test-runner-runs
  (is (fn? grid/mount) "the module under test loads and exports its seam"))
```

- [ ] **Step 4: Run it.**

```
clojure -M:cljs -m shadow.cljs.devtools.cli compile test && node target/cljs-test.js
```

Expected: `Testing loci.smoke-test` … `1 tests, 1 assertions, 0 failures.` **Report the wall-clock time** — it is the number that justifies this task.

- [ ] **Step 5: Prove it bites.** Rename `the-cljs-test-runner-runs` to `the-cljs-test-runner` (no `-test` suffix on the *namespace* is what `:ns-regexp` matches, so instead: temporarily rename the file's ns to `loci.smoke`), recompile, and confirm the runner reports **0 tests** rather than passing. A test build that silently runs nothing is the failure mode this step exists to catch. Restore.

- [ ] **Step 6: Commit.**

```bash
git add shadow-cljs.edn deps.edn test-cljs/loci/smoke_test.cljs
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
clojure -M:cljs -m shadow.cljs.devtools.cli compile test && node target/cljs-test.js
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

**Files:**
- Modify: `src-cljs/loci/grid/view.cljs`
- Modify: `resources/public/index.html` — grid CSS only
- Modify: `test/browser/harness.mjs` — `GRID_FIXTURE`
- Create: `test/browser/grid.test.mjs`

- [ ] **Step 1: Add the fixture.** In `test/browser/harness.mjs`, beside `STORAGE_FIXTURE`:

```javascript
// 36 columns, because that is the width at which the old renderer collapsed
// every column to under one character. 250 rows, so at limit 100 there are three
// pages and a short last one — the only way to see the elision go absent.
//
// `grp` has five values, 50 rows each, so a page boundary under a sort by `grp`
// lands inside a run of equal values. `note` is one long value, for the tooltip
// clip. `maybe` is null every third row, for NULLS LAST.
//
// Above DuckLake's ~10–20 row inlining threshold, so this is a real Parquet
// table with a real rowid and the keyset branch is what is under test.
export const GRID_FIXTURE = `
(let [st @c/store
      lk @lake/lake
      row (fn [i] (into {:id (str "row-" i)
                         :grp (str "g" (mod i 5))
                         :note (apply str (repeat 900 "x"))
                         :maybe (when (pos? (mod i 3)) i)}
                        (for [c (range 32)] [(keyword (str "c" c)) (* i (inc c))])))
      h   (tbl/put-rows! lk "tbl:grid" (mapv row (range 250)))]
  (assert (= 36 (count (:cols h))) "the grid fixture stopped being 36 columns wide")
  (sub/commit! st {:op :tx :events
                   [{:op :put :id "tbl:grid"
                     :value {:id "tbl:grid" :kind :table :title "Grid fixture" :value h}}
                    {:op :put :id "space:grid"
                     :value {:id "space:grid" :kind :space :title "Grid fixture"
                             :value {:intent "a table 36 columns wide"
                                     :cells [{:ref "tbl:grid"}]}}}]}))
`;
```

- [ ] **Step 2: Write the failing tests.** Create `test/browser/grid.test.mjs`:

```javascript
// The ClojureScript grid, measured in a real browser. Every assertion here reads
// a measured position or size off the DOM. Asserting that `position:sticky` is
// in the stylesheet passes while the header scrolls away; asserting a column
// "is readable" passes at 0.7 characters.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, GRID_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: GRID_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

const openGrid = async (page) => {
  await bootedShell(page, server.url + '?cljs');
  await page.goto(server.url + '?cljs#space:grid');
  await page.waitForSelector('.grid table tbody tr');
};

test('a 36-column table is wider than its panel and has somewhere to scroll', async () => {
  await withPage(browser, 'cljs-grid-scrolls', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(() => {
      const box = document.querySelector('.grid [data-grid-scroll]');
      return { table: box.querySelector('table').getBoundingClientRect().width,
               box: box.clientWidth, scrollWidth: box.scrollWidth };
    });
    assert.ok(m.table > m.box * 1.5,
      `36 columns should overflow; table ${m.table}px in a ${m.box}px box`);
    assert.ok(m.scrollWidth > m.box + 50,
      `${m.scrollWidth - m.box}px of scroll is rounding, not somewhere to go`);
  });
});

test('a narrow column shows its whole value', async () => {
  await withPage(browser, 'cljs-grid-widths', async (page) => {
    await openGrid(page);
    const t = await page.evaluate(() => {
      const heads = [...document.querySelectorAll('.grid thead th')];
      const i = heads.findIndex(h => h.textContent.trim().startsWith('grp'));
      return document.querySelectorAll('.grid tbody tr')[0].children[i].textContent.trim();
    });
    assert.equal(t, 'g0', `grp rendered as ${JSON.stringify(t)}`);
  });
});

test('the header stays put when the rows scroll', async () => {
  await withPage(browser, 'cljs-grid-sticky', async (page) => {
    await openGrid(page);
    const move = await page.evaluate(async () => {
      const box = document.querySelector('.grid [data-grid-scroll]');
      const th = document.querySelector('.grid thead th');
      const before = th.getBoundingClientRect().top;
      box.scrollTop = 300;
      await new Promise(r => requestAnimationFrame(r));
      return Math.abs(th.getBoundingClientRect().top - before);
    });
    assert.ok(move < 2, `the header moved ${move}px`);
  });
});

test('the first column stays put, and is not transparent', async () => {
  await withPage(browser, 'cljs-grid-frozen', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(async () => {
      const box = document.querySelector('.grid [data-grid-scroll]');
      const td = document.querySelector('.grid tbody td');
      const before = td.getBoundingClientRect().left;
      box.scrollLeft = 600;
      await new Promise(r => requestAnimationFrame(r));
      const r2 = td.getBoundingClientRect();
      return { move: Math.abs(r2.left - before),
               onTop: td.contains(document.elementFromPoint(r2.left + 4, r2.top + 4)) };
    });
    assert.ok(m.move < 2, `the first column moved ${m.move}px`);
    assert.ok(m.onTop, 'scrolled rows show through the frozen column');
  });
});

test('a long value is clipped in the tooltip', async () => {
  await withPage(browser, 'cljs-grid-tooltip', async (page) => {
    await openGrid(page);
    const len = await page.evaluate(() => {
      const heads = [...document.querySelectorAll('.grid thead th')];
      const i = heads.findIndex(h => h.textContent.trim().startsWith('note'));
      return document.querySelectorAll('.grid tbody tr')[0].children[i]
               .getAttribute('title').length;
    });
    assert.ok(len <= 520, `a 900-character value produced a ${len}-character tooltip`);
  });
});
```

- [ ] **Step 3: Run, watch fail.** Expected: no `.grid table` — Task 6 left `grid` a stub.

- [ ] **Step 4: Implement the view.** Replace `grid` in `src-cljs/loci/grid/view.cljs`:

```clojure
(def ^:private tooltip-max
  "How much of a value the `title` attribute carries. Measured on the real
   substrate: a `description` column reached 16,344 characters, which is not a
   tooltip — and a page of full values duplicated into attributes is a second
   copy of the rows in the DOM."
  500)

(defn- clip [s] (if (> (count s) tooltip-max) (str (subs s 0 tooltip-max) "…") s))

(defn- numeric-col?
  "A column is numeric if its values say so — CSS cannot see that, so the markup
   carries it. Blanks are not evidence either way: real tables have holes."
  [rows c]
  (let [vs (remove nil? (map #(get % c) rows))]
    (and (seq vs) (every? number? vs))))

(defn grid [el {:as s :keys [payload]}]
  (let [{:keys [rendered table/cols]} payload
        all-cols cols                       ; the table's full list, off the handle
        cols     (vec (remove (:hidden s) all-cols))]
    [:div.grid
     ;; `width: max-content` and nothing else. The old renderer computed a
     ;; per-column character width and then normalised it to percentages of a
     ;; `width:100%` table, which discards the scale — at 36 columns the
     ;; 7-character floor became 0.7 characters and the scrollbar was rounding.
     ;; One property replaces that arithmetic; Clerk reaches the same result the
     ;; same way.
     [:div {:data-grid-scroll true
            :style {:overflow "auto" :max-height "420px"}}
      [:table.mold {:style {:width "max-content"}}
       [:thead [:tr (for [c cols]
                      [:th {:key c :title (name c)
                            :class (when (numeric-col? rendered c) "num")}
                       (name c)])]]
       [:tbody
        (for [[i row] (map-indexed vector rendered)]
          [:tr {:replicant/key i}
           (for [c cols]
             (let [v (str (get row c))]
               [:td {:key c :title (clip v)
                     :class (if (numeric-col? rendered c) "num" "txt")}
                v]))])]]]
     [:div.pgcap (caption s)]]))
```

- [ ] **Step 5: Add the CSS.** In `resources/public/index.html`'s `<style>`, after the `table.mold` rules:

```css
  /* The ClojureScript grid. `table.mold`'s own rules still apply — this adds
     only what the old renderer could not do. */
  .grid table.mold{width:max-content}
  /* The header IS the column key: at 36 columns `fa` is decodable as `archived`
     only while the header is on screen. `background` is not decoration — a
     sticky element is painted over scrolling content and shows through without
     one. */
  .grid thead th{position:sticky;top:0;z-index:2;background:var(--white)}
  /* The first column is the row's identity, and a table wider than its panel
     loses it on the first horizontal scroll. */
  .grid th:first-child,.grid td:first-child{
    position:sticky;left:0;background:var(--white);box-shadow:1px 0 0 var(--line)}
  /* the corner is sticky on both axes and must outrank both. */
  .grid thead th:first-child{z-index:3}
```

- [ ] **Step 6: Green.** **Step 7: Prove they bite:**

| break | test that must go red |
|---|---|
| `{:style {:width "100%"}}` on the table | `a 36-column table is wider than its panel`, `a narrow column shows its whole value` |
| remove `position:sticky` from `.grid thead th` | `the header stays put` |
| remove `background` from the frozen column | the `onTop` assertion in `the first column stays put` |
| drop `clip` | `a long value is clipped in the tooltip` — reports 900 |

- [ ] **Step 8: Both suites, then commit.**

```
npm run test:browser
clojure -M:cljs -m shadow.cljs.devtools.cli compile test && node target/cljs-test.js
```

```bash
git add src-cljs resources/public/index.html test/browser/
git commit -m "feat: a column as wide as what it holds, a header that stays, a column that stays"
```

---

## Task 8: the pager

**Files:**
- Modify: `src-cljs/loci/grid/view.cljs`
- Modify: `test/browser/grid.test.mjs`

- [ ] **Step 1: Write the failing tests.** Append to `test/browser/grid.test.mjs`:

```javascript
test('the pager walks to the last page and stops, and prev pops back', async () => {
  await withPage(browser, 'cljs-grid-pager', async (page) => {
    await openGrid(page);
    const read = () => page.evaluate(() => ({
      caption: document.querySelector('.grid .pgcap').textContent.trim(),
      first: document.querySelector('.grid tbody td').textContent.trim(),
      rows: document.querySelectorAll('.grid tbody tr').length,
      nextOff: document.querySelector('[data-a="page-next"]').disabled,
      prevOff: document.querySelector('[data-a="page-prev"]').disabled,
    }));

    let s = await read();
    assert.match(s.caption, /showing 1–100 of 250/);
    assert.equal(s.first, 'row-0');
    assert.ok(s.prevOff, 'there is no page before the first');

    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelector('.grid tbody td').textContent.trim() === 'row-100');
    s = await read();
    assert.match(s.caption, /showing 101–200 of 250/);
    assert.equal(s.rows, 100, 'a page REPLACES — 100 rows, not 200 accumulated');

    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelectorAll('.grid tbody tr').length === 50);
    s = await read();
    assert.match(s.caption, /showing 201–250 of 250/);
    assert.ok(s.nextOff, 'the last page has no hole, so there is nowhere to go');

    await page.click('[data-a="page-prev"]');
    await page.waitForFunction(() =>
      document.querySelector('.grid tbody td').textContent.trim() === 'row-100');
    assert.match((await read()).caption, /showing 101–200 of 250/);
  });
});

test('the page size is adjustable and restarts the walk', async () => {
  await withPage(browser, 'cljs-grid-pagesize', async (page) => {
    await openGrid(page);
    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelector('.grid .pgcap').textContent.includes('101–200'));
    await page.selectOption('[data-a="page-size"]', '25');
    await page.waitForFunction(() =>
      document.querySelectorAll('.grid tbody tr').length === 25);
    assert.match(await page.textContent('.grid .pgcap'), /showing 1–25 of 250/);
    assert.ok(await page.$eval('[data-a="page-prev"]', b => b.disabled),
      'a cursor names a position in a walk of a given size');
    assert.deepEqual(
      await page.$$eval('[data-a="page-size"] option', os => os.map(o => o.value)),
      ['25', '50', '100', '200']);
  });
});

test('the shell never reads a cursor', async () => {
  // The invariant that lets three storage strategies share one control.
  const fs = await import('node:fs/promises');
  for (const f of ['../../src-cljs/loci/grid.cljs', '../../src-cljs/loci/grid/view.cljs']) {
    const src = await fs.readFile(new URL(f, import.meta.url), 'utf8');
    for (const probe of [':k ', ':o ', 'atob', 'js/JSON.parse']) {
      assert.ok(!src.includes(probe), `${f} inspects a cursor: found ${probe}`);
    }
  }
});
```

- [ ] **Step 2: Run, watch fail.**

- [ ] **Step 3: Implement.** Append to `grid` in `src-cljs/loci/grid/view.cljs`, replacing the bare `.pgcap` div:

```clojure
     [:div.pager
      [:span.pgcap (caption s)]
      [:span {:style {:flex "1"}}]
      [:select.btn.ghost.sm {:data-a "page-size"
                             :on {:change [[:page/size el (js/parseInt (.. % -target -value))]]}}
       (for [n [25 50 100 200]]
         [:option {:key n :value n :selected (= n (:limit s))} (str n " rows")])]
      [:button.btn.ghost.sm {:data-a "page-prev" :disabled (zero? (:page s))
                             :on {:click [[:page/prev el]]}} "‹ Prev"]
      [:button.btn.ghost.sm {:data-a "page-next" :disabled (not (has-next? s))
                             :on {:click [[:page/next el]]}} "Next ›"]]
```

`grid` already takes `el` and `has-next?` already lives in `loci.grid.view` (both from Task 6), so neither signature moves here.

**The `%` above is a guess and must not survive as one.** Replicant's spelling for "the value of the event that fired" needs checking in its README before you write it. **If you cannot confirm it in five minutes, take the fallback**: dispatch `[[:page/size el]]` with no argument and read the value off the DOM event, which `set-dispatch!` receives as its first parameter. Report which of the two you used.

- [ ] **Step 4: Green.** **Step 5: Prove they bite:**

| break | test that must go red |
|---|---|
| `on-payload` appends instead of replacing | `the pager walks…` — 200 rows after one Next |
| `prev-page` re-fetches from the elision rather than popping | the final Prev assertion |
| `set-limit` keeps the stack | `the page size is adjustable and restarts the walk` |
| add `(:k (t/read reader cursor))` anywhere in `grid.cljs` | `the shell never reads a cursor` |

- [ ] **Step 6: Both suites, then commit.**

```bash
git add src-cljs test/browser/grid.test.mjs
git commit -m "feat: a pager that replaces, and a stack that makes Prev exact"
```

---

# Phase 4 — sorting and the column chooser

## Task 9: click a header to sort

**Files:**
- Modify: `src-cljs/loci/grid/view.cljs`
- Modify: `test-cljs/loci/grid_test.cljs`, `test/browser/grid.test.mjs`

`toggle-sort` and the window plumbing exist from Task 6 and Task 1 of the previous plan. This is the control.

- [ ] **Step 1: Write the failing tests.** In `test-cljs/loci/grid_test.cljs`:

```clojure
(deftest sorting-cycles-through-three-states-and-the-third-is-natural-order
  (let [s0 (grid/init-state "tbl:x")
        s1 (grid/toggle-sort s0 :grp)
        s2 (grid/toggle-sort s1 :grp)
        s3 (grid/toggle-sort s2 :grp)]
    (is (= [:grp "asc"]  (:sort s1)))
    (is (= [:grp "desc"] (:sort s2)))
    (is (nil? (:sort s3)) "natural order is the only order that pages by seeking")
    (is (= [:grp "asc"] (:sort (grid/toggle-sort s2 :other)))
        "a different column starts its own cycle")))

(deftest sorting-restarts-the-walk
  (let [s (grid/next-page (assoc (grid/init-state "tbl:x")
                                 :payload {:elision {:cursor "C"}}))]
    (is (= [nil] (:stack (grid/toggle-sort s :grp))))
    (is (= 0 (:page (grid/toggle-sort s :grp))))))
```

In `test/browser/grid.test.mjs`:

```javascript
test('clicking a header sorts, twice reverses, three times restores', async () => {
  await withPage(browser, 'cljs-grid-sort', async (page) => {
    await openGrid(page);
    const head = '.grid thead th:nth-child(2)';   // grp
    const col2 = () => page.$$eval('.grid tbody td:nth-child(2)',
                                   c => c.map(x => x.textContent.trim()));
    await page.click(head);
    await page.waitForFunction(() =>
      document.querySelector('.grid thead th:nth-child(2)').dataset.sort === 'asc');
    assert.equal((await col2())[0], 'g0');

    await page.click(head);
    await page.waitForFunction(() =>
      document.querySelector('.grid thead th:nth-child(2)').dataset.sort === 'desc');
    assert.equal((await col2())[0], 'g4');

    await page.click(head);
    await page.waitForFunction(() =>
      !document.querySelector('.grid thead th:nth-child(2)').dataset.sort);
    assert.equal((await page.textContent('.grid tbody td')).trim(), 'row-0');
  });
});

test('a sort restarts the walk at page one', async () => {
  await withPage(browser, 'cljs-grid-sort-resets', async (page) => {
    await openGrid(page);
    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelector('.grid .pgcap').textContent.includes('101–200'));
    await page.click('.grid thead th:nth-child(2)');
    await page.waitForFunction(() =>
      document.querySelector('.grid .pgcap').textContent.includes('showing 1–'));
    assert.ok(await page.$eval('[data-a="page-prev"]', b => b.disabled));
  });
});
```

- [ ] **Step 2: Run, watch fail.** **Step 3: Implement** — the `th` gains the control:

```clojure
       [:thead [:tr (for [c cols]
                      (let [[sc sd] (:sort s)
                            dir (when (= sc c) sd)]
                        [:th {:key c :title (name c)
                              :data-sort dir
                              :style {:cursor "pointer"}
                              :class (when (numeric-col? rendered c) "num")
                              :on {:click [[:sort/toggle el c]]}}
                         (name c)
                         (case dir "asc" " ▲" "desc" " ▼" nil)]))]]
```

- [ ] **Step 4: Green.** **Step 5: Prove they bite** — make the third click return `[col "asc"]` (the natural-order assertion fails); keep the stack across a sort (`a sort restarts the walk` fails).

- [ ] **Step 6: Both suites, then commit.**

```bash
git add src-cljs test-cljs test/browser/grid.test.mjs
git commit -m "feat: a header sorts three ways, and the third is the order that seeks"
```

---

## Task 10: the column chooser, remembered

**Files:**
- Modify: `src-cljs/loci/grid.cljs`, `src-cljs/loci/grid/view.cljs`
- Modify: `test-cljs/loci/grid_test.cljs`, `test/browser/grid.test.mjs`

- [ ] **Step 1: Write the failing tests.** In `test-cljs/loci/grid_test.cljs`:

```clojure
(deftest hiding-a-column-narrows-the-request-not-only-the-page
  (let [s (grid/toggle-col (assoc (grid/init-state "tbl:x")
                                  :payload {:table/cols [:a :b :c]}) :b)]
    (is (= #{:b} (:hidden s)))
    (is (= "a,c" (grid/cols-param s)) "the hidden column leaves the payload")
    (is (nil? (grid/cols-param (grid/toggle-col s :b)))
        "nothing hidden means no parameter — an empty cols list means ALL columns
         server-side, and sending every column explicitly would silently drop one
         added to the table later")))
```

In `test/browser/grid.test.mjs`:

```javascript
test('hiding a column removes it from the request', async () => {
  await withPage(browser, 'cljs-grid-cols', async (page) => {
    await openGrid(page);
    const urls = [];
    page.on('request', r => { if (r.url().includes('/api/mold')) urls.push(r.url()); });
    await page.click('[data-a="cols"]');
    await page.click('[data-colpick="note"]');
    await page.waitForFunction(() =>
      ![...document.querySelectorAll('.grid thead th')]
        .some(h => h.textContent.trim().startsWith('note')));
    assert.ok(urls.some(u => u.includes('cols=') && !u.includes('note')),
      'the hidden column must be absent from the window, or the payload never shrank');
  });
});

test('sort, hidden columns and page size survive a reload', async () => {
  await withPage(browser, 'cljs-grid-persist', async (page) => {
    await openGrid(page);
    await page.selectOption('[data-a="page-size"]', '25');
    await page.waitForFunction(() => document.querySelectorAll('.grid tbody tr').length === 25);
    await page.click('.grid thead th:nth-child(2)');
    await page.waitForFunction(() =>
      document.querySelector('.grid thead th:nth-child(2)').dataset.sort === 'asc');
    await page.reload();
    await page.waitForSelector('.grid tbody tr');
    assert.equal(await page.$$eval('.grid tbody tr', r => r.length), 25);
    assert.equal(await page.$eval('.grid thead th:nth-child(2)', h => h.dataset.sort), 'asc');
  });
});

test('a browser that refuses storage still draws the grid', async () => {
  await withPage(browser, 'cljs-grid-no-storage', async (page) => {
    await page.addInitScript(() => {
      Object.defineProperty(window, 'localStorage',
        { get() { throw new DOMException('denied', 'SecurityError'); } });
    });
    await openGrid(page);
    assert.ok(await page.$$eval('.grid tbody tr', r => r.length) > 0,
      'a private window must get a grid, not a blank panel');
  });
});
```

- [ ] **Step 2: Run, watch fail.** **Step 3: Implement.**

`cols-param` in `loci.grid`, extracted from the existing `url`:

```clojure
(defn cols-param
  "The visible columns as the `cols` query parameter, or nil when nothing is
   hidden. **nil and not the full list**: server-side an empty `cols` means all
   columns, and sending every column explicitly would make a stale preference
   silently drop a column added to the table later."
  [s]
  (when (seq (:hidden s))
    (->> (get-in s [:payload :table/cols])
         (remove (:hidden s)) (map name) (str/join ","))))
```

Persistence, in `loci.grid`:

```clojure
;; Per-object, in the browser, never in the substrate. The event log is what
;; `?at=` rewinds and `undo!` reverses; clicking a column header is fiddling
;; with a lens, not an act on content, and every such click becoming a point in
;; history you can travel to is noise in the one log that has to stay meaningful.
;; (`:view` stays a substrate event, as it is today — chosen once, not a knob.)
;;
;; Every access is wrapped: a private window, cleared site data or a browser set
;; to block storage throws on the ACCESSOR itself, so an unguarded read takes the
;; whole panel down rather than returning nothing.
(defn- prefs-key [id] (str "loci.grid." id))

(defn- load-prefs [s]
  (try
    (if-let [raw (.getItem js/localStorage (prefs-key (:id s)))]
      (let [p (t/read reader raw)]
        (merge s (select-keys p [:limit :sort :hidden])))
      s)
    (catch :default _ s)))

(defn- save-prefs! [s]
  (try
    (.setItem js/localStorage (prefs-key (:id s))
              (t/write (t/writer :json) (select-keys s [:limit :sort :hidden])))
    (catch :default _ nil)))
```

`mount` calls `load-prefs`; `act!` calls `save-prefs!` after the swap. Transit for storage too, so a `:sort` of `[:grp "asc"]` round-trips as keywords rather than as strings.

The chooser, in the pager row:

```clojure
      [:details.colpick
       [:summary.btn.ghost.sm {:data-a "cols"}
        (if (seq (:hidden s))
          (str "columns · " (count cols) "/" (count all-cols))
          "columns")]
       [:div.colpick-b
        ;; ALL columns, from the payload's `:table/cols`, which `hydrate-page`
        ;; takes off the handle rather than off the rendered rows — a page
        ;; projected with `:cols` no longer contains the hidden ones, and reading
        ;; the list back from the rows would make hiding one-way.
        (for [c all-cols]
          [:label {:key c}
           [:input {:type "checkbox" :data-colpick (name c)
                    :checked (not (contains? (:hidden s) c))
                    :on {:change [[:cols/toggle el c]]}}]
           (name c)])]]
```

- [ ] **Step 4: Green.** **Step 5: Prove they bite** — filter the columns in the view instead of sending `cols` (the request assertion fails while the page still looks right, which is the point); remove the `try` from `load-prefs` (the storage test fails with a blank panel).

- [ ] **Step 6: Both suites, then commit.**

```bash
git add src-cljs test-cljs test/browser/grid.test.mjs
git commit -m "feat: hide a column and it leaves the payload, and the browser remembers"
```

---

# Phase 5 — parity and the switch

## Task 11: the browser suite moves to the new markup

**Files:**
- Modify: every file in `test/browser/` that asserts on a table's DOM

This is the cost §7 of the spec names and does not minimise. Find them first:

```bash
rg -ln "table\.mold|tableHTML|thead|tbody" test/browser/
```

- [ ] **Step 1: List them and report the count** before changing anything.
- [ ] **Step 2: For each, decide and record which of two things it is**: a test *about a table's markup* (rewrite against `.grid`), or a test that merely *uses a table to reach something else* (leave it — it will exercise whichever renderer is default).
- [ ] **Step 3: Rewrite the first kind**, one file per commit, running `npm run test:browser` after each.
- [ ] **Step 4: Report any test you could not port and why.** A test that cannot be expressed against the new markup is telling you something about the new markup.

```bash
git commit -m "test: the browser suite reads the ClojureScript grid"
```

---

## Task 12: the flag goes, and so does `tableHTML`

**Files:**
- Modify: `resources/public/index.html`
- Modify: `test/browser/cljsbuild.test.mjs`

- [ ] **Step 1: Make it the default.** Delete `CLJS_GRID` and its branch; `renderMold`'s table case becomes:

```js
    case 'table':  return liveBadge(m)+'<div data-cljs-grid="'+esc(m.id)+'"></div>';
```

- [ ] **Step 2: Delete `tableHTML`** and the `table.mold` CSS rules the grid does not use.

**`renderReport` and the ƒ preview also call `tableHTML`** (around `index.html:1889` and the `case 'table'` inside `renderReport`). They render report blocks and before/after previews, which are *not* windowed and have no elision.

**Recommended: keep a small local `staticTable(rows)` for them**, rather than mounting a grid per report block. Those tables are a handful of rows inside prose — they need no pager, no sort and no chooser, and mounting a Replicant component per block would put a fetch and a state entry behind something that already has its rows in hand. Take the *rendering* half of `tableHTML` (the `table.mold` markup and the numeric-column alignment) and drop the width arithmetic and the 60-row cap with it.

If you disagree, argue it rather than implementing it. **Do not delete `tableHTML` until both call sites are handled** — `rg -n "tableHTML" resources/public/index.html` and report every site you found.

- [ ] **Step 3: Update the build test.** `without ?cljs the shell renders its own table and mounts nothing` is now false. Replace it with one asserting the grid mounts *without* a flag.

- [ ] **Step 4: Both suites green**, then commit.

```bash
git add resources/public/index.html test/browser/
git commit -m "feat: the ClojureScript grid is the table, and tableHTML is gone"
```

---

## Finishing

- [ ] `clojure -M:test`, `node target/cljs-test.js` and `npm run test:browser`, all green, all foreground.
- [ ] Update the spec's Status to **built and measured, 2026-08-25**, and record what building changed — in particular the Replicant event-placeholder spelling from Task 8, the real per-cell budget arithmetic from Task 5, and how many browser tests Task 11 had to rewrite.
- [ ] `superpowers:finishing-a-development-branch` to decide how `spike-cljs` lands, and whether it merges into `paged-table-grid` or replaces it.
