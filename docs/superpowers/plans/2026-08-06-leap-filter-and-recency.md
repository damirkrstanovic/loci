# LEAP Filter + Recency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank LEAP's results by what you touched most recently, and let LEAP filter the overview through an entry in its own list rather than a mode change.

**Architecture:** A pure function folds the event log into `{object-id last-ts}`; `state-payload` and `leap-payload` both take it, so a notebook's recency includes anything inside it. `leap-payload` sorts each group before its existing cap of 8 — which turns that cap from a coin-flip into "the 8 most recent". The frontend appends a synthetic `__filter__` entry when in overview, and applying it dims non-matching panels until you enter a notebook or clear it.

**Tech Stack:** Clojure 1.12 (`src/loci/server.clj`), vanilla JS/CSS (`resources/public/index.html`), cognitect test-runner, node --test + playwright-core.

**Spec:** `docs/superpowers/specs/2026-08-06-leap-filter-and-recency-design.md`.

**Facts the implementer needs:**
- `leap-payload` (`src/loci/server.clj:122`) builds groups `objs prose intext made mems verbs`, each `(cap …)` at 8, concatenated in that order. `cap` is `(fn [xs] (vec (take 8 xs)))` — **the first 8 in enumeration order**, which after the Datalevin migration is hash order.
- Entry shape is `{:id :label :group}`, plus `:target` for the views & functions group. Group names reaching the frontend: `space`, `doc`, `table`, `report`, `metric`, `flow`, `prose`, `in text`, `views & functions`, `memory`, `viewer`.
- `state-payload` (`src/loci/server.clj:73`) already gives each space `:members` — a vector of the object ids its cells reference. That is what maps a content hit to the notebook holding it.
- Every event carries `:ts` (epoch ms), stamped by `commit!`. A `:tx` event carries `:events`, a vector of sub-events each with their own `:id`; the `:tx` itself has no `:id`.
- `sub/history` returns the event vector, oldest first.
- Frontend LEAP state: `filtered` and `sel` (`index.html:286`), `renderLeap()` (~1106) builds `filtered` then the DOM, `act()` (~1135) dispatches on `e.group`. `closeLeap()` (~1302) clears the input and blurs.
- The shell's globals are **not on `window`** — `let STATE`, `function layout()` are lexical globals. `page.evaluate(() => overview())` works; `window.STATE` is always `undefined`. Guard `waitForFunction` with `typeof x !== 'undefined'`.
- `mode` is `'overview'` or `'focus'`; `enter(i)` and `overview()` switch. `layout()` positions every panel on each call and on resize.
- The crumb is `<button class="crumb" id="crumb">` containing `#crumbHint` and `#crumbName` (`index.html:227`), and its click handler toggles focus/overview — **a ✕ inside it must call `stopPropagation`** or clearing the filter will also change mode.
- Baseline: `clojure -M:test` 128 tests / 602 assertions; `npm run test:browser` 15 tests. Both must stay green.
- **Never point anything at `data/`** — the user's real substrate, md5 `e7a6eecdff7bb44ad6b959bb1a7fb7c6`. A server is running on :7777 against it; leave it alone. The browser harness boots its own on a throwaway `LOCI_DATA`.

---

### Task 1: last-touched, folded from the log

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests.** Append to `test/loci/server_test.clj`:

```clojure
;; ---- recency: what did you touch, and when ----
;; LEAP capped each group at "the first 8 encountered", which after the move to
;; Datalevin is hash order — so results were dropped by coin-flip. Ranking by
;; last-touched turns that cap into "the 8 most recent".

(deftest last-touched-picks-the-newest-event-that-names-an-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value 2})
    (let [t (srv/last-touched st)]
      (is (> (t "a") (t "b")) "a was edited after b was created")
      (is (= (t "a") (:ts (last (sub/history st))))))))

(deftest last-touched-sees-inside-a-tx
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "old" :value {:id "old" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :tx :events [{:op :put :id "x" :value {:id "x" :kind :doc :value 1}}
                                      {:op :put :id "y" :value {:id "y" :kind :doc :value 1}}]})
    (let [t (srv/last-touched st)]
      (is (some? (t "x")) "a :tx has no :id of its own; its sub-events do")
      (is (= (t "x") (t "y")))
      (is (> (t "x") (t "old"))))))

(deftest a-notebook-is-touched-when-anything-in-it-is
  ;; otherwise a hub you work in constantly looks dormant, because you never
  ;; edit the space object itself
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S"
                             :value {:intent "i" :cells [{:ref "tbl:t"}]}}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value []}})
    (let [t   (srv/last-touched st)
          nb  (srv/notebook-touched st t "space:s")]
      (is (> (t "tbl:t") (t "space:s")) "the table is newer than the notebook object")
      (is (= nb (t "tbl:t")) "the notebook inherits its newest member's recency"))))

(deftest state-payload-carries-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S" :value {:intent "i" :cells []}}})
    (is (number? (:touched (first (:spaces (srv/state-payload st))))))))
```

- [ ] **Step 2: Run them to watch them fail.**

Run: `clojure -M:test -n loci.server-test`

Expected: FAIL — `No such var: srv/last-touched`.

- [ ] **Step 3: Implement.** In `src/loci/server.clj`, immediately above `(defn state-payload`, add:

```clojure
;; ---- recency: one fold over the log gives every object its newest event ----
;; Read from the log rather than Datalevin's `touched` dbi on purpose: the EDN
;; store is still the documented rollback and the parity suite runs both, and a
;; LEAP that ranks correctly on one store and arbitrarily on the other is worse
;; than one uniformly a millisecond slower. O(events) per request — at ~10^5
;; this must become a map maintained beside the state atom instead.
(defn- event-ids
  "Every object id an event names — a :tx has none of its own, its sub-events do."
  [ev]
  (if (= :tx (:op ev))
    (mapcat event-ids (:events ev))
    (when-let [id (:id ev)] [id])))

(defn last-touched
  "{object-id newest-event-ts} across the whole log."
  [st]
  (reduce (fn [m ev]
            (let [ts (:ts ev)]
              (reduce (fn [m id] (assoc m id ts)) m (event-ids ev))))
          {} (sub/history st)))

(defn notebook-touched
  "A notebook is as recent as the newest thing in it — editing a table inside a
   hub makes the hub recent, which is what a reader means by 'touched'."
  [st touched space-id]
  (let [o (sub/object st space-id)]
    (apply max 0 (keep touched (cons space-id (keep :ref (nb/cells-of o)))))))
```

- [ ] **Step 4: Carry it in the payload.** In `state-payload`, replace:

```clojure
(defn state-payload [st]
  (let [objs (vals (sub/objects st))]
```

with:

```clojure
(defn state-payload [st]
  (let [objs (vals (sub/objects st))
        touched (last-touched st)]
```

and replace:

```clojure
                   (map (fn [s] (cond-> {:id (:id s) :title (:title s)
                                         :intent (get-in s [:value :intent])
                                         :members (vec (keep :ref (nb/cells-of s)))}
```

with:

```clojure
                   (map (fn [s] (cond-> {:id (:id s) :title (:title s)
                                         :intent (get-in s [:value :intent])
                                         :touched (notebook-touched st touched (:id s))
                                         :members (vec (keep :ref (nb/cells-of s)))}
```

- [ ] **Step 5: Run the tests.**

Run: `clojure -M:test -n loci.server-test`

Expected: PASS. Then `clojure -M:test` — expect 132 tests / ~612 assertions, 0 failures.

- [ ] **Step 6: Commit.**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: last-touched, folded from the log"
```

---

### Task 2: recency ranks LEAP, and fixes its cap

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests.** Append to `test/loci/server_test.clj`:

```clojure
(defn- leap-of [st q] (srv/leap-payload st (mem/file-memory (tmpfile)) q))

(deftest leap-orders-each-group-most-recent-first
  (let [st (sub/fresh-store)]
    (doseq [n (range 3)]
      (sub/commit! st {:op :put :id (str "doc:d" n)
                       :value {:id (str "doc:d" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id))]
      (is (= ["doc:d2" "doc:d1" "doc:d0"] ids) "newest first"))))

(deftest leaps-cap-keeps-the-newest-eight-not-the-first-eight
  ;; the assertion that would have failed before this change: 12 matches, and
  ;; the 8 kept must be the 8 most recently touched
  (let [st (sub/fresh-store)]
    (doseq [n (range 12)]
      (sub/commit! st {:op :put :id (str "doc:w" n)
                       :value {:id (str "doc:w" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id) set)]
      (is (= 8 (count ids)))
      (is (= (set (map #(str "doc:w" %) (range 4 12))) ids)
          "the eight most recent, not doc:w0..w7"))))

(deftest leap-entries-carry-their-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:one"
                     :value {:id "doc:one" :kind :doc :title "widget" :value "x"}})
    (is (number? (:touched (first (filter #(= "doc" (:group %)) (leap-of st "widget"))))))))

(deftest ask-and-verbs-are-not-reordered-by-recency
  ;; "the top result is already the action" — Ask stays first, and view verbs
  ;; (which have no timestamp) keep their existing order
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "rows here"
                                                  :value [{:a 1}]}})
    (let [groups (mapv :group (leap-of st "rows"))]
      (is (= "viewer" (last groups)) "verbs stay at the end, unsorted"))))
```

- [ ] **Step 2: Run them to watch them fail.**

Run: `clojure -M:test -n loci.server-test`

Expected: FAIL — `leaps-cap-keeps-the-newest-eight-not-the-first-eight` reports a set containing `doc:w0`, and `leap-entries-carry-their-recency` reports `nil` is not a number.

- [ ] **Step 3: Implement.** In `leap-payload`, replace:

```clojure
        cap  (fn [xs] (vec (take 8 xs)))
```

with:

```clojure
        touched (last-touched st)
        ;; recency BEFORE the cap: taking "the first 8 encountered" dropped
        ;; results by whatever order the objects happened to enumerate in
        recent  (fn [e] (or (:touched e) 0))
        cap  (fn [xs] (->> xs (map #(assoc % :touched (touched (:id %) 0)))
                           (sort-by recent >) (take 8) vec))
```

Note `prose` entries carry the *notebook's* id, so they rank by the notebook's own last event — which is what a reader means by "that notebook, recently".

- [ ] **Step 4: Run the tests.**

Run: `clojure -M:test -n loci.server-test`

Expected: PASS. Then `clojure -M:test` — expect 136 tests / ~622 assertions, 0 failures.

- [ ] **Step 5: Prove the cap test bites.** Temporarily change `(sort-by recent >)` to `(sort-by recent <)` (oldest first) and re-run. `leaps-cap-keeps-the-newest-eight-not-the-first-eight` and `leap-orders-each-group-most-recent-first` must both FAIL. Restore, and confirm `git diff src/loci/server.clj` shows only your intended change.

Report which tests failed under the inversion.

- [ ] **Step 6: Commit.**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: LEAP ranks by what you touched last, and its cap keeps the newest"
```

---

### Task 3: the filter verb

**Files:**
- Modify: `resources/public/index.html`

> **One deviation from the spec, deliberate.** The spec asks for `6 of 24 · 4 below`.
> This plan shows `6 of 24` and *scrolls the first match into view*, which addresses the
> failure the "below" count was there to prevent — a screen of grey that looks broken.
> Counting what lies below the fold means measuring every panel against the viewport on
> every scroll, for a number nobody acts on. If the scroll turns out not to be enough,
> add it then.

- [ ] **Step 1: Add the filter state and the matcher.** Immediately above `async function renderLeap(){`, add:

```js
// ---------------- LEAP filters the overview ----------------
// Filtering is an entry in LEAP's own list rather than a mode: the dropdown
// closes because you finished with it, and Ask / New space / memory hits stay
// reachable from the overview, which suppressing the panel would have cost.
let overviewFilter=null;          // {q, ids:Set} — sticky until you leave overview

// A notebook matches on its own title/id, on a prose cell inside it (LEAP
// returns the NOTEBOOK's id for prose), or on containing an object that
// matched — `in text` hits carry the document's id, not its notebook's.
function notebooksMatching(entries){
  const direct=new Set(entries.filter(e=>e.group==='space'||e.group==='prose').map(e=>e.id));
  const hits=new Set(entries.map(e=>e.id));
  const ids=new Set();
  STATE.spaces.forEach(sp=>{
    if(direct.has(sp.id) || (sp.members||[]).some(m=>hits.has(m))) ids.add(sp.id);
  });
  return ids;
}

function applyOverviewFilter(){
  const on=!!overviewFilter;
  panels.forEach((p,k)=>p.classList.toggle('dim', on && !overviewFilter.ids.has(STATE.spaces[k].id)));
  const cr=document.getElementById('crumbFilter');
  if(cr) cr.remove();
  if(on){
    const el=document.createElement('span'); el.id='crumbFilter'; el.className='crumbfilter';
    el.innerHTML='· “'+esc(overviewFilter.q)+'” <b>'+overviewFilter.ids.size+' of '+
                 STATE.spaces.length+'</b> <span class="x" title="clear the filter">✕</span>';
    el.querySelector('.x').addEventListener('click',e=>{ e.stopPropagation(); clearOverviewFilter(); });
    crumb.appendChild(el);
  }
}
function clearOverviewFilter(){ overviewFilter=null; applyOverviewFilter(); }

// with the canvas scrolling, a filter whose matches are all below the fold
// shows a screen of grey and looks broken — bring the first one into view
function scrollToFirstMatch(){
  if(!overviewFilter) return;
  const k=STATE.spaces.findIndex(s=>overviewFilter.ids.has(s.id));
  if(k<0) return;
  const r=panels[k].getBoundingClientRect(), w=document.getElementById('world');
  if(r.top<140||r.bottom>innerHeight) w.scrollTop+=r.top-180;
}
```

- [ ] **Step 2: Offer the entry.** In `renderLeap()`, immediately after the block that appends the `__connect:` entries (the `if(mode==='focus'&&!TIME&&qv&&STATE.spaces[focusIdx]){…}` block), add:

```js
  // ⊟ filter: only from the overview, where the dropdown is what's in the way
  if(mode==='overview'&&!TIME&&qv){
    const n=notebooksMatching(entries).size;
    filtered.push({id:'__filter__', group:'filter',
                   label:'⊟ Filter the overview: “'+qv+'” · '+n+' of '+STATE.spaces.length});
  }
```

- [ ] **Step 3: Name the group and give it an icon.** In `renderLeap()`, replace:

```js
  const gname={ask:'Ask',space:'Notebooks',viewer:'View verbs',create:'Create',prose:'In notebooks','in text':'In documents',memory:'Memory','views & functions':'Views & functions'};
```

with:

```js
  const gname={ask:'Ask',space:'Notebooks',viewer:'View verbs',create:'Create',filter:'Filter',prose:'In notebooks','in text':'In documents',memory:'Memory','views & functions':'Views & functions'};
```

and replace:

```js
    const cls=(e.group==='space')?'space':(e.group==='viewer'||e.group==='create'||e.group==='ask')?'verb':'';
    const ic=e.group==='ask'?'✦':e.group==='space'?'▢':e.group==='viewer'?'▸':e.group==='create'?'✦':e.group==='memory'?'⌾':e.group==='prose'?'¶':e.group==='in text'?'§':e.group==='views & functions'?'ƒ':'▤';
```

with:

```js
    const cls=(e.group==='space')?'space':(e.group==='viewer'||e.group==='create'||e.group==='ask'||e.group==='filter')?'verb':'';
    const ic=e.group==='ask'?'✦':e.group==='space'?'▢':e.group==='viewer'?'▸':e.group==='create'?'✦':e.group==='filter'?'⊟':e.group==='memory'?'⌾':e.group==='prose'?'¶':e.group==='in text'?'§':e.group==='views & functions'?'ƒ':'▤';
```

- [ ] **Step 4: Act on it.** In `act()`, immediately before the line `if(e.group==='ask'){ askAgent(…`, add:

```js
  if(e.group==='filter'){
    const qv=q.value.trim();
    let entries; try{ entries=await API.leap(qv); }catch(err){ showToast('leap: network error'); return; }
    overviewFilter={q:qv, ids:notebooksMatching(entries)};
    closeLeap(); applyOverviewFilter(); scrollToFirstMatch();
    if(!overviewFilter.ids.size) showToast('no notebook matches “'+qv+'”');
    return;
  }
```

- [ ] **Step 5: Keep it applied, and clear it on entering.** In `layout()`, as the LAST statement before the closing `}` of the function (after the `crumbName.textContent=…` line), add:

```js
  applyOverviewFilter();   // survives resize and re-render
```

and in `enter(i)`, replace:

```js
function enter(i){ mode='focus'; focusIdx=i; openId=null; closeLeap(); renderBody(i); layout(); }
```

with:

```js
function enter(i){ mode='focus'; focusIdx=i; openId=null; closeLeap();
                   overviewFilter=null;            // the filter belongs to the overview
                   renderBody(i); layout(); }
```

- [ ] **Step 6: Let Escape clear it.** In the global keydown handler, replace:

```js
  if(e.key==='Escape'){ if(TIME){ exitTime(); return; } if(openId){openId=null;renderBody(focusIdx);} else if(mode==='focus') overview(); return; }
```

with:

```js
  if(e.key==='Escape'){ if(TIME){ exitTime(); return; }
    if(openId){openId=null;renderBody(focusIdx);}
    else if(overviewFilter){ clearOverviewFilter(); }
    else if(mode==='focus') overview(); return; }
```

- [ ] **Step 7: Style it.** Add to the CSS, beside the `.crumb` rules:

```css
  .crumbfilter{font-family:var(--mono);font-size:10.5px;color:var(--muted);margin-left:7px}
  .crumbfilter b{color:var(--accent);font-weight:500}
  .crumbfilter .x{margin-left:5px;cursor:pointer;color:var(--faint);padding:0 3px}
  .crumbfilter .x:hover{color:var(--attn)}
  .panel.dim{opacity:.22}
  .panel.dim:hover{opacity:.5}
```

- [ ] **Step 8: Syntax-check, then use it by hand.**

```bash
python3 -c "
import re; h=open('resources/public/index.html').read()
open('/tmp/shell.js','w').write(re.search(r'<script>(.*)</script>',h,re.S).group(1))"
node --check /tmp/shell.js && echo "JS OK"
```

Then, against the server on :7777, drive it in a real browser: go to overview, type `lithography`, confirm a `Filter` group appears in LEAP, select it, and **screenshot the result**. Read the image: are the semiconductor notebooks lit and everything else dimmed? Does the crumb read `all notebooks · "lithography" 7 of 24 ✕`? Does clicking ✕ restore everything without changing mode?

Report what the screenshot showed and the actual crumb text.

- [ ] **Step 9: Commit.**

```bash
git add resources/public/index.html
git commit -m "feat: LEAP filters the overview, and the filter is sticky"
```

---

### Task 4: browser tests for the filter

**Files:**
- Create: `test/browser/leapfilter.test.mjs`

- [ ] **Step 1: Write the tests.** Create `test/browser/leapfilter.test.mjs`:

```js
// Filtering the overview is an entry in LEAP's list, not a mode. The fixture
// seeds 12 notebooks plus the family from harness.mjs.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const typeLeap = async (page, text) => {
  await page.click('#q');
  await page.locator('#q').pressSequentially(text, { delay: 25 });
  await page.waitForFunction(t => {
    const rs = [...document.querySelectorAll('#results .res .title')];
    return rs.some(r => r.textContent.includes(t));
  }, text);
};

test('the Filter entry appears in overview and not in focus', async () => {
  await withPage(browser, 'filter-entry-overview-only', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await typeLeap(page, 'Filter the overview');
    assert.match(await page.textContent('#results'), /Filter the overview/);

    await page.keyboard.press('Escape');
    await page.evaluate(() => enter(0));
    await page.click('#q');
    await page.locator('#q').pressSequentially('fixture', { delay: 25 });
    await page.waitForFunction(() => document.querySelectorAll('#results .res').length > 0);
    assert.doesNotMatch(await page.textContent('#results'), /Filter the overview/,
      'focus mode has no overview to filter');
  });
});

test('selecting it dims non-matching notebooks without removing them', async () => {
  await withPage(browser, 'filter-dims-not-hides', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await typeLeap(page, 'Filter the overview');
    await page.evaluate(() => {
      const i = filtered.findIndex(e => e.group === 'filter');
      sel = i; return act();
    });
    await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter !== null);
    const m = await page.evaluate(() => ({
      dim: document.querySelectorAll('.panel.dim').length,
      total: panels.length,
      present: [...document.querySelectorAll('.panel')].length,
      matched: overviewFilter.ids.size,
    }));
    assert.ok(m.dim > 0, 'nothing was dimmed');
    assert.equal(m.present, m.total, 'panels must be dimmed, never removed');
    assert.equal(m.dim, m.total - m.matched);
  });
});

test('a notebook matches through an object it contains, not only by title', async () => {
  await withPage(browser, 'filter-matches-by-content', async (page) => {
    await bootedShell(page, server.url);
    // tbl:planets lives in space:cosmos, whose title says nothing about planets
    const r = await page.evaluate(async () => {
      const entries = await API.leap('planets');
      const ids = notebooksMatching(entries);
      return { has: ids.has('space:cosmos'),
               titleMatched: entries.some(e => e.group === 'space' && e.id === 'space:cosmos') };
    });
    assert.equal(r.has, true, 'the notebook holding tbl:planets should match');
    assert.equal(r.titleMatched, false, 'and not because its own title matched');
  });
});

test('the filter survives a resize and clears on entering a notebook', async () => {
  await withPage(browser, 'filter-sticky-and-cleared', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await typeLeap(page, 'Filter the overview');
    await page.evaluate(() => { sel = filtered.findIndex(e => e.group === 'filter'); return act(); });
    await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter !== null);

    await page.setViewportSize({ width: 1200, height: 900 });
    await page.evaluate(() => layout());
    assert.ok(await page.evaluate(() => document.querySelectorAll('.panel.dim').length) > 0,
      'the filter did not survive layout()');

    await page.evaluate(() => enter(0));
    await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter === null);
    assert.equal(await page.evaluate(() => document.querySelectorAll('.panel.dim').length), 0);
  });
});

test('the crumb shows the filter and its ✕ clears it without changing mode', async () => {
  await withPage(browser, 'filter-crumb', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await typeLeap(page, 'Filter the overview');
    await page.evaluate(() => { sel = filtered.findIndex(e => e.group === 'filter'); return act(); });
    await page.waitForSelector('#crumbFilter');
    assert.match(await page.textContent('#crumbFilter'), /of \d+/);

    await page.click('#crumbFilter .x');
    await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter === null);
    assert.equal(await page.evaluate(() => mode), 'overview',
      'clearing the filter must not toggle the crumb into focus');
  });
});
```

- [ ] **Step 2: Run them.**

Run: `npm run test:browser`

Expected: 20 tests, 0 failing (15 existing + 5 new).

- [ ] **Step 3: Prove they bite.** Two sabotages, one at a time, in `resources/public/index.html`:

| sabotage | test that must fail |
|---|---|
| in `applyOverviewFilter`, change `p.classList.toggle('dim', …)` to `p.classList.toggle('dim', false)` | `selecting it dims non-matching notebooks` |
| in `enter(i)`, delete `overviewFilter=null;` | `the filter survives a resize and clears on entering` |

Restore after each and confirm `git diff resources/public/index.html` is empty before committing. Report which tests failed under each; any that passed is not testing its claim and must be fixed here.

- [ ] **Step 4: Commit.**

```bash
git add test/browser/leapfilter.test.mjs
git commit -m "test: the overview filter dims, sticks, and clears"
```

---

### Task 5: documentation and final verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document both behaviours.** In `README.md`, immediately after the paragraph beginning "Zoomed out, the overview groups", add:

```markdown
LEAP ranks what it finds by what you touched most recently — a notebook counts as touched
when anything inside it is. From the overview it also offers `⊟ Filter the overview`, which
dims everything that does not match and stays until you enter a notebook or clear it from
the breadcrumb.
```

- [ ] **Step 2: Run everything.**

```bash
clojure -M:test          # expect 136 tests, 0 failures
npm run test:browser     # expect 20 tests, 0 failing
md5sum data/substrate.edn   # must be e7a6eecdff7bb44ad6b959bb1a7fb7c6
```

- [ ] **Step 3: Check recency against the real corpus.** With the server on :7777:

```bash
curl -s "localhost:7777/api/state" | python3 -c "
import json,sys,datetime
d=json.load(sys.stdin)
for s in sorted(d['spaces'], key=lambda x:-x.get('touched',0))[:6]:
    t=datetime.datetime.fromtimestamp(s['touched']/1000).strftime('%m-%d %H:%M')
    print(f\"  {t}  {s['title'][:44]}\")"
```

The three World Bank notebooks and the deep-dives created this morning should sort to the
top, and the seeded corporate notebooks to the bottom. Report the actual list — if a
notebook you know you touched today ranks low, `notebook-touched` is not seeing its members.

- [ ] **Step 4: Commit.**

```bash
git add README.md
git commit -m "docs: LEAP ranks by recency and can filter the overview"
```

---

## Verification checklist

- [ ] `srv/last-touched` sees inside a `:tx`; a notebook inherits its newest member's time
- [ ] LEAP's cap keeps the 8 most recent — the inversion sabotage in Task 2 Step 5 failed
- [ ] `Ask` stays the first result; view verbs stay last and unsorted
- [ ] The `Filter` entry appears only in overview
- [ ] Applying it dims without removing; panels count is unchanged
- [ ] A notebook matches through a contained object, not only its title
- [ ] The filter survives `layout()` and clears on `enter()` and on the crumb's ✕
- [ ] Clearing via ✕ does not toggle the crumb into focus mode
- [ ] `clojure -M:test` 136 · `npm run test:browser` 20 · `data/substrate.edn` md5 unchanged
- [ ] Real-corpus recency ordering puts today's notebooks on top

## Out of scope

- An age gradient on the overview cards
- Sorting the overview itself by recency — recency ranks LEAP; arrangement stays structural
- Stable card positions across notebook creation (a real defect in the greedy column packer,
  filed in the spec)
- Tags (`2026-08-06-tags-and-overview-design.md` §2)
- Maintaining last-touched incrementally in the store rather than folding per request
