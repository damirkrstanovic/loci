# External Sources Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The shell can see, connect, refresh and upload an external file source — the capability phase 1 shipped and only `curl` could reach.

**Architecture:** Three of the four pieces are shell work over API phase 1 already ships; two small server additions make them possible — a structural `:source` block on the mold payload, and a `GET /api/source/files` listing. Upload is chunked from the browser because http-kit buffers whole request bodies in heap, which keeps every request under the default 8 MiB cap and heap flat at any file size.

**Tech Stack:** Clojure 1.12, http-kit 2.8.0, DuckDB/DuckLake, vanilla JS in `resources/public/index.html`, `node --test` + playwright-core for the browser suite.

**Spec:** `docs/superpowers/specs/2026-08-20-external-sources-phase-2-design.md`

---

## Ground rules for every task

- **The REPL is the execution surface.** Do not run `clojure -M -e` for iteration. Use `mcp__clojure-mcp__clojure_eval` (load via `ToolSearch`, query `select:mcp__clojure-mcp__clojure_eval`) against the nREPL on port 7888, and `:reload` after every edit. A cold JVM costs ~25 s.
- **Browser tests cannot use the REPL.** `npm run test:browser` boots a real server and a real chromium; a single file is `npx node --test test/browser/x.test.mjs` (~20 s). Use that for iteration, the full suite before a commit.
- **Baselines to hold:** `clojure -M:test` → 772 tests, 4287 assertions, 0 failures. `npm run test:browser` → 134 pass, 1 fail. **That one failure is a known flake** — `outline.test.mjs` "clicking an entry brings that cell into view" reads a transient `.flash` class removed on a 900 ms timeout; it passes 4/4 in isolation and four agents have chased it. Do not chase it a fifth time.
- **The shell is `resources/public/index.html`**, not the repo-root `index.html` (a different, unserved file). Plain `grep` reports zero matches on the served one because its long lines read as binary — always `grep -a`.
- **A `"` inside a Clojure docstring must be `\"`** in the source, or you get a `defn` macroexpansion error. Script languages eat one backslash level.
- **Never use an unanchored global find-and-replace on source.** One on this branch changed `(take 6)` → `(take 8)` for a hash width and silently broke an unrelated cap.
- `:reload` does not drop vars for renamed-away tests — `(remove-ns 'the.ns)` first. Reloading `loci.tablestore` invalidates `reify`s in `loci.tablestore-test`.
- **A client-side timeout does not stop DuckDB.** The REPL tool times out at 20 s; a native JDBC call
  keeps running server-side and does not reliably honour an interrupt. Re-issuing `run-tests`
  immediately then races the first run, and two concurrent `with-temp-lake` fixtures stomp the same
  globally-`with-redefs`'d `lake/lake` var and close each other's connections — producing a wall of
  unrelated `Connection was closed` failures across the whole namespace. Observed 2026-08-20. Wait for
  the REPL to go idle before re-running, and treat a sudden broad connection failure as this before
  treating it as a finding.
- **Ask what the assertion looks like when the mechanism is absent.** The sabotage step catches a
  broken fix, not a broken assertion — five assertions on these two phases could not have failed, and
  running the sabotage found none of them. Task 2's `/takings/` check over the whole page is the
  clearest: DuckDB's own error message in the fallback already contains the column names, so it
  passed against a block that fetched nothing. **Print what the sabotaged output actually says and
  read it.** Prefer a scoped element you created over a substring of the page, and a value only the
  mechanism can produce over one the failure mode also produces. If a sabotage fails a *different*
  assertion than you expected, that is a finding about the test.
- **Prove every test bites.** Break the fix, re-run, confirm FAIL, restore. **If a sabotage reports 0 failures, that is a finding about your sabotage** — four tests on phase 1 could not fail, two of them written by the plan's author.
- End every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## File structure

| file | responsibility |
|---|---|
| `src/loci/files.clj` | **new.** The manifest — hash → original name, append-only EDN lines — and the `.partial` sweep. One namespace so the upload route stays a route. |
| `src/loci/server.clj` | the `:source` block on `mold-payload`; `GET /api/source/files`; `POST /api/source/upload`; the `.partial` sweep on boot. |
| `resources/public/index.html` | `sourceHTML` and the `renderMold` branch; the `＋ Data` panel; the refresh button and live badge; chunked upload. |
| `test/loci/files_test.clj` | **new.** The manifest and the sweep, no server. |
| `test/loci/server_test.clj` | the payload block, the listing, the upload route. |
| `test/browser/sources.test.mjs` | **new.** The three faces, the panel, refresh, the scrubbed badge. |
| `test/browser/harness.mjs` | a `SOURCES_FIXTURE` seeding one live, one unavailable and one drifted passthrough table. |

---

### Task 1: the mold payload says which source state a table is in

The shell must not classify by reading `unusable`'s prose — that is the fragile approach phase 1 rejected when `hydrate` chose to ask the filesystem instead.

**Files:**
- Modify: `src/loci/server.clj` — `mold-payload` (public, at line ~370)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests**

Append to `test/loci/server_test.clj`:

```clojure
(deftest mold-payload-says-which-source-state-a-table-is-in
  ;; The shell branches on this rather than on `unusable`'s wording. Reading a
  ;; state out of an English sentence is what `hydrate` deliberately does not do.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                f   (io/file dir "live.csv")
                _   (spit f "a,b\n1,x\n")
                uri (str "loci://files/" (.getName f))
                r   (src/connect! st {:uri uri :format :csv
                                      :mode :passthrough :title "Live"})
                p   (srv/mold-payload st no-memory (:id r) nil)]
            (is (= "live" (get-in p [:source :state])) "a healthy passthrough reads live")
            (is (= uri    (get-in p [:source :uri])))
            (is (= "passthrough" (get-in p [:source :mode])))
            ;; the file goes away
            (.delete f)
            (let [p2 (srv/mold-payload st no-memory (:id r) nil)]
              (is (= "unavailable" (get-in p2 [:source :state])))
              (is (string? (:unusable p2)) "the sentence still ships beside it"))
            ;; and comes back with a different header
            (spit f "c,d\n1,x\n")
            (let [p3 (srv/mold-payload st no-memory (:id r) nil)]
              (is (= "drift" (get-in p3 [:source :state]))))))))))

(deftest a-materialised-source-is-stored-not-live
  ;; Refresh applies to any table with a recipe; the LIVE badge only to
  ;; passthrough. A materialised source must be distinguishable from both a
  ;; passthrough one and a table nobody connected.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                _   (spit (io/file dir "m.csv") "a\n1\n")
                r   (src/connect! st {:uri "loci://files/m.csv" :format :csv
                                      :mode :materialise :title "Stored"})
                p   (srv/mold-payload st no-memory (:id r) nil)]
            (is (= "stored" (get-in p [:source :state])))
            (is (= "materialise" (get-in p [:source :mode])))
            (is (seq (:rendered p)) "and it still renders its rows")))))))

(deftest a-table-nobody-connected-has-no-source-block
  (let [st (store-with-table)
        p  (srv/mold-payload st no-memory "tbl:t" nil)]
    (is (nil? (:source p)) "absent, not a block saying nothing")))
```

- [ ] **Step 2: Run them, confirm they FAIL**

```clojure
(require '[loci.server-test] :reload)
(clojure.test/test-vars [#'loci.server-test/mold-payload-says-which-source-state-a-table-is-in])
```
Expected: FAIL — `(get-in p [:source :state])` is nil.

- [ ] **Step 3: Implement**

In `src/loci/server.clj`, above `mold-payload`, add:

```clojure
(defn- source-block
  "What the shell needs to draw a sourced table, as data rather than as prose.

   **The state is structural on purpose.** `:unusable` carries a sentence and
   the sentence names the failure, but reading a state back out of English is
   exactly what `tablestore/hydrate` refuses to do — it asks the filesystem
   through `sourcespec/unreadable-because` rather than matching on DuckDB's
   words, because the driver's wording is not a contract. A shell branching on
   `indexOf('unreachable')` would reintroduce that.

   Four states, and `stored` is the one that is easy to leave out: a
   MATERIALISED source is not live and is not broken, and it still has a recipe,
   so it still refreshes. Refresh belongs to any table with a `:source`; the
   live badge belongs only to `:passthrough`.

   nil for a table nobody connected — absent rather than a block saying nothing,
   so the shell's test is `if (m.source)` and not a comparison against a
   sentinel."
  [o]
  (when-let [src (:source o)]
    {:state (cond (:table/reclaimed o)    "reclaimed"
                  (:table/unavailable o)  "unavailable"
                  (:table/source-drift o) "drift"
                  (:table/passthrough o)  "live"
                  :else                   "stored")
     :uri   (:uri src)
     :mode  (name (:mode src))}))
```

Then add `:source (source-block o)` to **every branch of `mold-payload` that can render a table** — there are four, and an earlier draft of this plan said two:

1. the degraded branch — the clause testing `(tbl/unusable o)`
2. the normal viewer branch — the trailing `:else`, where `:rendered` comes from `mold/mold`
3. the `app:` branch — `(and view (str/starts-with? view "app:"))`
4. the `view:` branch — `(and view (str/starts-with? view "view:"))`

Branches 3 and 4 are reachable with a hydrated table: `make-view!` and `describe!` each refuse a non-table and then call `mold-payload` with the minted id as `view`. Leaving them out means a broken passthrough source renders silently empty when opened through a code view — the defect phase 2 exists to remove, arriving through a different door. `source-block` is pure and cheap: a payload key present and not yet drawn costs nothing, a missing one forces a second server change later.

- [ ] **Step 4: Run them, confirm they PASS**

```clojure
(require '[loci.server] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```
Expected: 0 failures.

- [ ] **Step 5: Prove they bite**

Make `source-block` return `nil` unconditionally → expect FAIL on all three. Then make it always answer `"live"` → expect FAIL on the unavailable and drift assertions **and** on `a-materialised-source-is-stored-not-live`. Restore, and paste both outputs.

- [ ] **Step 6: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: the mold payload says which source state a table is in" \
  -m "<why the state is structural and not parsed from the sentence>" \
  -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: the shell draws the three faces

**Files:**
- Modify: `resources/public/index.html` — `renderMold` (search `function renderMold`), and add `sourceHTML` beside `reclaimedHTML`
- Modify: `test/browser/harness.mjs` — add `SOURCES_FIXTURE`
- Test: `test/browser/sources.test.mjs` (new)

- [ ] **Step 1: Add the fixture**

In `test/browser/harness.mjs`, beside `RECLAIMED_FIXTURE`, add and export:

```javascript
// Three passthrough tables, one per face. The files live under the server's own
// LOCI_DATA, so `loci://` resolves for the server and the test can break them
// from node by path. `drift` is connected clean and then its header is rewritten
// — the same file, a different shape, which is the only way to produce a drift.
export const SOURCES_FIXTURE = `
(let [st  @c/store
      dir (doto (java.io.File. (str (sub/data-dir) "/files")) .mkdirs)
      w   (fn [n t] (spit (java.io.File. dir n) t) (str "loci://files/" n))]
  (loci.source/connect! st {:uri (w "live.csv" "region,revenue\\nEMEA,10\\nAPAC,20\\n")
                            :format :csv :mode :passthrough :title "Live source"} "space:cosmos")
  (loci.source/connect! st {:uri (w "gone.csv" "region,revenue\\nEMEA,10\\n")
                            :format :csv :mode :passthrough :title "Gone source"} "space:cosmos")
  (loci.source/connect! st {:uri (w "drift.csv" "region,revenue\\nEMEA,10\\n")
                            :format :csv :mode :passthrough :title "Drifted source"} "space:cosmos")
  (.delete (java.io.File. dir "gone.csv"))
  (spit (java.io.File. dir "drift.csv") "area,takings\\nEMEA,10\\n"))
`;
```

Check `harness.mjs`'s fixture preamble for which aliases are in scope (`c`, `sub`, `nb` are used by the existing fixtures); if `loci.source` is not required there, add it to the same require list the other fixtures use.

- [ ] **Step 2: Write the failing browser test**

Create `test/browser/sources.test.mjs`:

```javascript
// A passthrough table has three faces and the shell drew two of them as the
// italic word "empty" — indistinguishable from a table with no rows, and from
// each other. The server sends the state structurally; this pins that the shell
// branches on it and offers the repair.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, SOURCES_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: SOURCES_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

const open = async (page, title) => {
  await bootedShell(page, server.url);
  await page.getByText(title, { exact: false }).first().click();
  await page.waitForSelector('.mold, .srcblock', { timeout: 5000 });
};

test('a healthy passthrough table says it reads live', async () => {
  await withPage(browser, 'live', async (page) => {
    await open(page, 'Live source');
    assert.match(await page.textContent('body'), /live source/i);
    assert.match(await page.textContent('body'), /EMEA/, 'and still shows its rows');
  });
});

test('a source whose file is gone says so and offers a reconnect', async () => {
  await withPage(browser, 'gone', async (page) => {
    await open(page, 'Gone source');
    const t = await page.textContent('body');
    assert.match(t, /unreachable/i);
    assert.doesNotMatch(t, /^\\s*empty\\s*$/i, 'never the bare word empty');
    assert.ok(await page.$('[data-a="reconnect"]'), 'a repair is offered');
  });
});

test('a drifted source names both column sets and offers a refresh', async () => {
  await withPage(browser, 'drift', async (page) => {
    await open(page, 'Drifted source');
    const t = await page.textContent('body');
    assert.match(t, /columns changed/i);
    assert.match(t, /revenue/, 'the columns it was connected as');
    assert.match(t, /takings/, 'and the columns the file has now');
    assert.ok(await page.$('[data-a="refresh"]'), 'refresh is the offered repair');
  });
});
```

- [ ] **Step 3: Run it, confirm it FAILS**

```bash
npx node --test test/browser/sources.test.mjs
```
Expected: all three fail — no `live source` text, no `[data-a]` buttons, the broken two render `empty`.

- [ ] **Step 4: Implement `sourceHTML`**

In `resources/public/index.html`, beside `reclaimedHTML`, add:

```javascript
// A reclaimed table is permanent; a broken source is fixable. That is the whole
// reason this is not reclaimedHTML with different words — these are the only
// table states in loci a person can repair, so they end in a button.
//
// `cur` is the file's columns as they are NOW, fetched separately for a drift.
// The server's sentence does name them, but reading them back out of prose is
// what this design keeps refusing to do; /api/source/describe answers with data.
function sourceHTML(m, cur){
  const s=m.source||{}, cols=m['table/cols']||[], n=m['table/total'];
  const chips=(cs)=>cs.map(c=>'<span class="chip">'+esc(c)+'</span>').join(' ');
  if(s.state==='unavailable')
    return '<div class="reclaimed"><div class="sec-h">source unreachable</div>'+
      '<p>'+esc(m.unusable||'')+'</p>'+
      (cols.length?'<p>It had '+cols.length+' column'+(cols.length===1?'':'s')+': '+chips(cols)+'</p>':'')+
      '<div class="actions"><button class="btn sm" data-a="reconnect">Reconnect…</button>'+
      '<button class="btn ghost sm" data-a="refresh">Try again</button></div></div>';
  if(s.state==='drift')
    return '<div class="reclaimed"><div class="sec-h">the file’s columns changed</div>'+
      '<p>It was connected as '+chips(cols)+'</p>'+
      (cur&&cur.length?'<p>It now has '+chips(cur)+'</p>':'<p>'+esc(m.unusable||'')+'</p>')+
      '<div class="actions"><button class="btn sm" data-a="refresh">Refresh — adopt the new columns</button>'+
      '<button class="btn ghost sm" data-a="reconnect">Reconnect…</button></div></div>';
  return '';
}
// The badge above a live table's rows. Not a refusal — passthrough says how a
// table is stored, never whether it is well, which is why it is drawn beside
// the rows rather than instead of them.
function liveBadge(m){
  const s=m.source||{};
  if(s.state!=='live') return '';
  const where=(s.uri||'').replace(/^loci:\\/\\/files\\//,'');
  return '<div class="livebadge"><span class="badge">live source</span> '+
    '<span class="muted">read from '+esc(where)+(typeof m['table/total']==='number'?' · '+fmtNum(m['table/total'])+' rows':'')+'</span>'+
    '<button class="btn ghost sm" data-a="refresh">Refresh</button></div>';
}
```

In `renderMold`, immediately after the `if(m.reclaimed) return reclaimedHTML(m);` line, add:

```javascript
  if(m.source && (m.source.state==='unavailable'||m.source.state==='drift')){
    // A drift is the one state worth a second request: describe answers with
    // the file's shape as it is now, which is the actionable half.
    if(m.source.state==='drift' && !m._cur){
      API.sourceDescribe({uri:m.source.uri,format:'csv'}).then(d=>{
        if(d&&d.cols){ m._cur=d.cols.map(c=>c.col); const el=document.querySelector('.srcblock');
          if(el) el.outerHTML='<div class="srcblock">'+sourceHTML(m,m._cur)+'</div>'; }
      });
    }
    return '<div class="srcblock">'+sourceHTML(m,m._cur)+'</div>';
  }
```

and prepend `liveBadge(m)` to whatever the healthy table branch returns.

Add to the `API` object beside `importCsv`:

```javascript
  sourceDescribe:(b)=>POST('/api/source/describe',b),
  sourceConnect:(b)=>POST('/api/source/connect',b),
  sourceRefresh:(id)=>POST('/api/source/refresh',{id}),
  sourceFiles:()=>fetch('/api/source/files').then(r=>r.json()),
```

Add CSS beside the existing `.reclaimed` rule:

```css
.livebadge{display:flex;align-items:center;gap:8px;margin:0 0 8px;font-size:11.5px}
.badge{font:10px/1 var(--mono);padding:3px 7px;border-radius:5px;background:#eef4ee;color:#3f6b3f;border:1px solid #d5e4d5}
.badge.past{background:#f6f2e9;color:#7a6330;border-color:#e6dcc4}
.livebadge .muted{color:var(--muted)}
```

- [ ] **Step 5: Wire the two buttons**

Wherever `renderMold`'s output is inserted (find the caller that sets `innerHTML` for a mold), after insertion add:

```javascript
  const rb=document.querySelector('[data-a="refresh"]');
  if(rb) rb.addEventListener('click',async()=>{
    if(TIME){ showToast('time mode — ↩ now to refresh'); return; }
    const r=await API.sourceRefresh(openId);
    if(r.error){ showToast('refresh: '+r.error); return; }
    applyState(r.state); rebuild(); openObject(openId);
    showToast('Source re-read — reversible', undo);
  });
  const cb=document.querySelector('[data-a="reconnect"]');
  if(cb) cb.addEventListener('click',()=>startData(focusIdx));
```

`startData` is Task 4's panel; until then have `reconnect` call `startImport(focusIdx)` and change it in Task 4.

- [ ] **Step 6: Run the test, confirm it PASSES**

```bash
npx node --test test/browser/sources.test.mjs
```
Expected: 3 pass.

- [ ] **Step 7: Prove it bites**

Delete the `renderMold` branch you added → all three fail with `empty`. Restore. Then make `sourceHTML` return the drift block without the `cur` chips → the drift test fails on `/takings/`. Restore. Paste both.

- [ ] **Step 8: Full browser suite, then commit**

```bash
npm run test:browser
```
Expected: 137 pass, 1 fail (the known `outline.test.mjs` flake).

```bash
git add resources/public/index.html test/browser/harness.mjs test/browser/sources.test.mjs
git commit -m "feat: the shell draws a broken source instead of the word empty" \
  -m "<the permanent-vs-fixable distinction, and why drift fetches its own columns>" \
  -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: the scrubbed badge tells the truth

A passthrough table shows today's file whatever time you scrub to. The badge must say so — this is the one place phase 2 must not merely render more prettily.

**Files:**
- Modify: `resources/public/index.html` — `liveBadge`
- Test: `test/browser/sources.test.mjs`

- [ ] **Step 1: Write the failing test**

Append to `test/browser/sources.test.mjs`:

```javascript
test('scrubbed into the past, a live source says it is still showing now', async () => {
  await withPage(browser, 'scrubbed', async (page) => {
    await bootedShell(page, server.url);
    await page.getByText('Live source', { exact: false }).first().click();
    await page.waitForSelector('.livebadge');
    assert.doesNotMatch(await page.textContent('.livebadge'), /not the contents/i);
    // drag the scrubber to the past — the shell sets TIME and repaints
    await page.evaluate(() => { window.TIME = '2026-01-01T00:00:00Z'; rebuild(); openObject(openId); });
    await page.waitForSelector('.livebadge');
    const t = await page.textContent('.livebadge');
    assert.match(t, /showing current contents/i, 'it must not silently imply time travel');
  });
});
```

**Read how the shell actually sets time mode first** (`grep -a -n "TIME" resources/public/index.html`) and use the real mechanism rather than the `window.TIME` shortcut above if it differs — a test that fakes state the app never produces proves nothing.

- [ ] **Step 2: Run it, confirm it FAILS.**

- [ ] **Step 3: Implement**

In `liveBadge`, replace the badge span with:

```javascript
  const past = !!TIME;
  const label = past ? 'live source — showing current contents, not the contents at '+esc(fmtTime(TIME))
                     : 'live source';
  return '<div class="livebadge"><span class="badge'+(past?' past':'')+'">'+label+'</span> '+ …
```

Use whatever the shell already calls to format a scrub instant — `grep -a -n "toLocaleString" resources/public/index.html` and reuse it rather than adding a formatter.

- [ ] **Step 4: Run it, confirm it PASSES.**

- [ ] **Step 5: Prove it bites** — drop the `past` branch, confirm FAIL, restore, paste.

- [ ] **Step 6: Commit.**

---

### Task 4: `GET /api/source/files` — what is in the files directory

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(deftest source-files-lists-what-is-in-the-directory
  (with-temp-data-dir
    (fn []
      (let [dir (doto (io/file (sub/data-dir) "files") .mkdirs)]
        (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
        (spit (io/file dir "hand-placed.csv") "b\n2\n")
        (.mkdirs (io/file dir ".partial"))
        (spit (io/file dir ".partial" "half") "not done")
        (let [r (srv/source-files)]
          (is (= #{"72b441a9c3e05f18.csv" "hand-placed.csv"} (set (map :file (:files r))))
              "both files, and never the .partial working directory")
          (is (every? #(number? (:bytes %)) (:files r)))
          (is (every? #(string? (:uri %)) (:files r)))
          (is (= "loci://files/hand-placed.csv"
                 (:uri (first (filter #(= "hand-placed.csv" (:file %)) (:files r)))))))))))

(deftest source-files-is-empty-and-not-an-error-before-anything-is-imported
  (with-temp-data-dir
    (fn [] (is (= [] (:files (srv/source-files)))))))
```

- [ ] **Step 2: Run, confirm FAIL** (`No such var: srv/source-files`).

- [ ] **Step 3: Implement**

```clojure
(defn source-files
  "What is in `<LOCI_DATA>/files/`, as `{:files [{:file :uri :bytes :name} …]}`.

   Two mechanisms write this directory — `import-csv!` and the upload route,
   both content-addressed — and a person may drop a file in by hand or mount
   one. So the listing is of the directory rather than of any record loci keeps:
   a file loci did not put there still connects, and pretending otherwise would
   make the picker lie about what is available.

   `:name` is the manifest's memory of what an upload was called and is absent
   for anything loci did not name. `.partial` is a working directory, not
   content, and never appears."
  []
  (let [dir (io/file (sub/data-dir) "files")
        named (source-names)]
    {:files (->> (.listFiles dir)
                 (remove #(.isDirectory %))
                 (remove #(str/starts-with? (.getName %) "."))
                 (sort-by #(.getName %))
                 (mapv (fn [f]
                         (cond-> {:file (.getName f)
                                  :uri (str "loci://files/" (.getName f))
                                  :bytes (.length f)}
                           (named (.getName f)) (assoc :name (named (.getName f)))))))}))
```

**`loci.files` does not exist until Task 6.** So in this task write the binding as a private stub in `server.clj` and use it in `source-files`:

```clojure
(def ^:private source-names
  "Stub until `loci.files` lands in Task 6, when this becomes `files/names`.
   Every file therefore lists under its own filename until then, which is
   exactly what a hand-placed file does permanently."
  (constantly {}))
```

and read it as `(source-names)` rather than `(files/names)` in the code above. Task 6 replaces both the stub and the call site. Do not create `loci.files` here.

Route, beside the other three:

```clojure
      (= uri "/api/source/files") (json-resp (source-files))
```

- [ ] **Step 4-6:** run, prove it bites (make it include `.partial` → the first test fails), full suite, commit.

---

### Task 5: the `＋ Data` panel

**Files:**
- Modify: `resources/public/index.html` — rename the button, add `startData`, keep `startImport` as the paste tab
- Test: `test/browser/sources.test.mjs`

- [ ] **Step 1: Write the failing test**

```javascript
test('the Data panel previews a file before connecting it', async () => {
  await withPage(browser, 'panel', async (page) => {
    await bootedShell(page, server.url);
    await page.getByRole('button', { name: /Data/ }).first().click();
    await page.waitForSelector('[data-file]');
    await page.click('[data-file="live.csv"]');
    // describe runs and shows the columns and their DuckDB types, committing nothing
    await page.waitForSelector('.srcpreview');
    const t = await page.textContent('.srcpreview');
    assert.match(t, /region/);
    assert.match(t, /VARCHAR|BIGINT/, 'the type the sniffer found');
    await page.fill('#dtitle', 'From the panel');
    await page.click('[data-a="connect"]');
    await page.waitForSelector('.mold, .livebadge', { timeout: 8000 });
    assert.match(await page.textContent('body'), /From the panel/);
  });
});

test('the Data panel still pastes', async () => {
  await withPage(browser, 'paste', async (page) => {
    await bootedShell(page, server.url);
    await page.getByRole('button', { name: /Data/ }).first().click();
    await page.click('[data-tab="paste"]');
    await page.fill('#csv', 'x,y\n1,2\n');
    await page.fill('#ctitle', 'Pasted still works');
    await page.click('#goImp');
    await page.waitForSelector('.mold', { timeout: 8000 });
    assert.match(await page.textContent('body'), /Pasted still works/);
  });
});
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement**

Rename the button at `resources/public/index.html` (search `id="importBtn"`) from `＋ Import CSV` to `＋ Data`, and point its listener at `startData(i)`.

`startData(i)` renders the two-tab panel. The *file* tab calls `API.sourceFiles()`, lists `{file,name,bytes}` as `[data-file]` rows, and on click calls `API.sourceDescribe({uri,format:'csv'})` and renders the columns into `.srcpreview` as `raw → col · TYPE`. It carries `#dtitle`, a mode toggle defaulting to **copy in** (`materialise`), and `[data-a="connect"]` calling `API.sourceConnect({uri,format,mode,title,space})` then `applyState`/`openObject` exactly as `startImport` does. The *paste* tab renders today's `startImport` body unchanged, ids included, so its existing behaviour and its existing tests are untouched.

**Derive `format` from the file's extension** (`.csv`/`.parquet`/`.json`), not from a dropdown — the three formats phase 1 ships map one-to-one onto extensions, and a dropdown would be a second place to be wrong.

- [ ] **Step 4-6:** run, prove it bites (make `connect` send `mode:'copy in'` → phase 1's validator refuses by name, which is the test), full suite, commit.

---

### Task 6: `loci.files` — the manifest and the `.partial` sweep

**Files:**
- Create: `src/loci/files.clj`
- Test: `test/loci/files_test.clj` (new)

- [ ] **Step 1: Write the failing tests**

```clojure
(ns loci.files-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loci.files :as files]
            [loci.substrate :as sub]))

(defn- with-dir [f]
  (let [d (str (System/getProperty "java.io.tmpdir") "/loci-files-" (System/nanoTime))]
    (try (with-redefs [sub/data-dir (constantly d)] (f d))
         (finally (loci.lake-fixture/rm-rf d)))))

(deftest a-name-round-trips-and-last-write-wins
  (with-dir
    (fn [_]
      (is (= {} (files/names)) "no file yet is not an error")
      (files/remember! "abc123.csv" "sales-2026.csv")
      (is (= {"abc123.csv" "sales-2026.csv"} (files/names)))
      (files/remember! "abc123.csv" "sales-final.csv")
      (is (= {"abc123.csv" "sales-final.csv"} (files/names))
          "append-only, last-wins by id — the same rule chunks and memory keep"))))

(deftest a-corrupt-line-is-skipped-rather-than-fatal
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "one.csv")
      (spit (io/file (sub/data-dir) "files.edn") "{:id \"b.csv\" :name  \n" :append true)
      (files/remember! "c.csv" "three.csv")
      (is (= #{"a.csv" "c.csv"} (set (keys (files/names))))
          "a half-written line loses one name, never the file"))))

(deftest sweeping-partials-removes-the-abandoned-and-keeps-the-fresh
  (with-dir
    (fn [_]
      (let [p (doto (io/file (sub/data-dir) "files" ".partial") .mkdirs)
            old (io/file p "old") new (io/file p "new")]
        (spit old "x") (spit new "y")
        (.setLastModified old (- (System/currentTimeMillis) (* 25 60 60 1000)))
        (is (= ["old"] (files/sweep-partials!)) "returns what it removed")
        (is (not (.exists old)))
        (is (.exists new) "an upload in flight is not garbage")))))
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement `src/loci/files.clj`**

`names` reads `<LOCI_DATA>/files.edn` line by line with `edn/read-string` in a `try`, skipping unreadable lines, reducing into a map last-wins by `:id`. `remember!` appends one `{:id :name :at}` line. `sweep-partials!` deletes files under `files/.partial` older than 24 hours and returns their names.

Docstring must say what the spec says: this is the **third** use of the append-only EDN-lines idiom `loci.chunks` and `loci.memory` keep — and that unlike those two it is **primary, not derived**: delete it and the names are gone for good, the bytes are not.

- [ ] **Step 4:** run, confirm PASS.

- [ ] **Step 5:** replace `server.clj`'s `source-names` stub from Task 4 with `files/names`, and call `files/sweep-partials!` from wherever the server does its boot-time work (`grep -n "defn -main" src/loci/server.clj`). Add a server test that a stale partial is gone after boot.

- [ ] **Step 6:** prove they bite, `clojure -M:test`, commit.

---

### Task 7: `POST /api/source/upload` — chunked

**Files:**
- Modify: `src/loci/server.clj`, `src/loci/files.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest an-upload-arrives-in-chunks-and-lands-as-one-content-addressed-file
  (with-temp-data-dir
    (fn []
      (let [id "up-1"
            a  (srv/source-upload! {:id id :seq 0 :name "sales.csv"} "region,revenue\n")
            b  (srv/source-upload! {:id id :seq 1 :name "sales.csv" :final true} "EMEA,10\n")]
        (is (nil? (:error a))) (is (nil? (:uri a)) "no uri until the last chunk")
        (is (re-matches #"loci://files/[0-9a-f]{16}\.csv" (:uri b)))
        (is (= "region,revenue\nEMEA,10\n"
               (slurp (io/file (sub/data-dir) "files" (subs (:uri b) 14))))
            "the pieces are in order and nothing is lost")
        (is (= {"sales.csv"} #{(get (files/names) (subs (:uri b) 14))})
            "and the name it arrived with is remembered")
        (is (empty? (.listFiles (io/file (sub/data-dir) "files" ".partial")))
            "the partial is gone once it lands")))))

(deftest a-chunk-out-of-sequence-is-refused-rather-than-silently-misordered
  (with-temp-data-dir
    (fn []
      (srv/source-upload! {:id "up-2" :seq 0 :name "x.csv"} "a\n")
      (let [r (srv/source-upload! {:id "up-2" :seq 5 :name "x.csv"} "b\n")]
        (is (:error r))
        (is (re-find #"out of sequence" (:error r)))))))

(deftest re-uploading-identical-bytes-writes-nothing-new
  (with-temp-data-dir
    (fn []
      (let [one (srv/source-upload! {:id "u1" :seq 0 :name "a.csv" :final true} "a\n1\n")
            two (srv/source-upload! {:id "u2" :seq 0 :name "b.csv" :final true} "a\n1\n")]
        (is (= (:uri one) (:uri two)) "the same bytes are the same file")
        (is (= 1 (count (remove #(.isDirectory %)
                                (.listFiles (io/file (sub/data-dir) "files"))))))))))
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement**

`source-upload!` appends the chunk to `files/.partial/<id>`, refusing a `:seq` that is not the next one (track expected seq in an atom keyed by id, or derive it — say which you chose and why). On `:final`, hash the completed file with the same `content-hash` `import-csv!` uses, rename to `<hash>.<ext>` unless it already exists (then delete the partial), `files/remember!` the original name, and return `{:uri …}`.

Route:

```clojure
      (= uri "/api/source/upload")
      (let [meta (json/read-str (get-in req [:headers "x-loci-upload"]) :key-fn keyword)]
        (json-resp (source-upload! meta (slurp (:body req)))))
```

**The metadata travels in a header and the chunk is the raw body** — not a JSON envelope with a base64 field, which would inflate every chunk by a third and re-buffer it.

- [ ] **Step 4-6:** run, prove they bite (drop the sequence check → the second test fails), `clojure -M:test`, commit.

---

### Task 8: the upload tab

**Files:**
- Modify: `resources/public/index.html`
- Test: `test/browser/sources.test.mjs`

- [ ] **Step 1: Write the failing test** — a `page.setInputFiles` of a small CSV written to a temp path, then assert the table lands and `/api/source/files` lists it under its original name.

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** — an `<input type="file">` in the file tab; on change, slice into 4 MB pieces with `File.slice`, POST each to `/api/source/upload` with an `X-Loci-Upload` header carrying `{id,seq,name,final}`, show progress as `n/total`, and on the returned uri run the same describe→preview→connect flow the picker already uses.

- [ ] **Step 4-6:** run, prove it bites, full browser suite, commit.

---

### Task 9: the measurement, and both suites

- [ ] **Step 1:** upload a **200 MB** CSV through the browser with `page.setInputFiles`, sampling heap on a background thread server-side as phase 1's gate did. Report peak heap and wall clock. **The claim to prove is that heap does not scale with the file** — the whole reason for chunking. If it does scale, that is the finding and the design is wrong.
- [ ] **Step 2:** `clojure -M:test` → 0 failures. `npm run test:browser` → the known `outline` flake only.
- [ ] **Step 3:** write the numbers into the spec's own "Measured" section with the date, saying which of the two things each establishes — that the mechanism works, or that the cost is real.
- [ ] **Step 4:** commit.

---

## Definition of done

- [ ] A broken source draws its state and its repair, never the bare word `empty`
- [ ] A live table says so, and says something true when scrubbed into the past
- [ ] A file under `data/files/` can be previewed and connected from the shell, in either mode
- [ ] A file can be uploaded from the browser at a size that would not fit in one request
- [ ] Re-uploading identical bytes writes nothing and reuses the file
- [ ] `clojure -M:test` and `npm run test:browser` both green but for the known `outline` flake
- [ ] Every regression test proved to fail when its fix is reverted
