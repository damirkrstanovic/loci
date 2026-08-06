# Structural Overview (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the overview from a flat grid of 24 equal cards into 15 clusters, each a notebook with its deep-dives nested beneath it, and give the focused notebook a one-line view of where it sits.

**Architecture:** Frontend only. `layout()`'s overview branch groups `STATE.spaces` by `spawned-by`/`merged-from` into a recursive tree, lays each root out as a cluster with indented descendants joined by a spine drawn in the existing `#edges` SVG, and lets the canvas scroll. `buildPanel` drops the now-redundant "spawned from X" eyebrow, and `renderNotebook` gains a structure line. **No Clojure changes, no new endpoints, no substrate change** — `/api/state` already carries everything needed.

**Tech Stack:** Vanilla JS + CSS in one file (`resources/public/index.html`), Node's test runner + playwright-core for the browser suite.

**Spec:** `docs/superpowers/specs/2026-08-06-tags-and-overview-design.md` (§1 only; tags are phase 2).

**Facts the implementer needs:**
- Everything lives in `resources/public/index.html` (~1,240 lines). Read `layout()` (line ~896), `buildPanel()` (~340) and `renderNotebook()` before starting.
- **`/api/state` flattens `spawned-by` to a bare id string** (e.g. `"space:pub-geo"`), NOT a map. `merged-from` is a vector of ids. A mock that assumed `spawned-by.space` silently produced 24 groups instead of 15 — check the shape, don't assume it.
- Nesting is recursive: `space:dd-16` is spawned from `space:dd-14`, which is spawned from `space:semis`. Three levels exist in real data.
- The shell's globals are **not** on `window` — `let STATE`, `function layout()` etc. are lexical globals. `page.evaluate(() => overview())` works; `window.STATE` is always `undefined`. In `waitForFunction`, reference them bare with a `typeof x !== 'undefined'` guard.
- `panels` is a JS array of `.panel` elements, index-aligned with `STATE.spaces`. `buildPanel` pushes them in order.
- Panels are absolutely positioned and moved with `transform: translate(x,y) scale(s)`; `.world` is `position:fixed;inset:0` and `body` is `overflow:hidden`.
- `#edges` is an SVG spanning the world; `layout()` writes `<line>` elements into it. `.edges line` is currently dashed (`stroke-dasharray:5 5`).
- The card-mode CSS from 2026-08-05 (`.world.cards .p-body{display:none}` etc.) is what makes a panel render as a card. Keep using it.
- Baseline: `clojure -M:test` 128 tests / 602 assertions; `npm run test:browser` 10 tests. Both must stay green. This plan adds no Clojure code.
- **Never point anything at `data/`** — it is the user's real substrate (md5 `e7a6eecdff7bb44ad6b959bb1a7fb7c6`). The browser harness already uses a throwaway `LOCI_DATA`.
- The browser fixture seeds **12 notebooks and no spawned children**, so cluster tests must create their own parent/child fixture (Task 4 shows how).

---

### Task 1: group the notebooks into a tree

Pure function first, with no layout involved — it is the piece most easily got wrong and the easiest to test.

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: Write the function.** Immediately above `function layout(){`, add:

```js
// ---------------- the shape the substrate already knows ----------------
// A notebook spawned by a deep-dive belongs UNDER it, recursively — the
// substrate computes spawned-by/merged-from and the overview should show that
// rather than laying 24 notebooks out as peers. Returns root nodes:
//   {i, sp, kids:[…same…]}
function clusterTree(){
  const byId={}, roots=[];
  STATE.spaces.forEach((sp,i)=>{ byId[sp.id]={i,sp,kids:[]}; });
  STATE.spaces.forEach(sp=>{
    // /api/state flattens spawned-by to a bare id; merged-from is a vector
    const parent = sp['spawned-by'] || (sp['merged-from']||[])[0];
    const node = byId[sp.id];
    if(parent && byId[parent] && parent!==sp.id) byId[parent].kids.push(node);
    else roots.push(node);
  });
  // a cycle would orphan its members from every root; surface them rather
  // than letting them vanish from the overview entirely
  const seen=new Set();
  (function walk(ns){ ns.forEach(n=>{ seen.add(n.i); walk(n.kids); }); })(roots);
  STATE.spaces.forEach((sp,i)=>{ if(!seen.has(i)) roots.push(byId[sp.id]); });
  return roots;
}
```

- [ ] **Step 2: Check it against the real corpus.** With the server running on 7777:

```bash
cd /tmp/claude-1001/-home-damirk-src-loci/d5fc3dfc-241a-458b-b3e4-d9c62d813634/scratchpad
node -e "
const {chromium}=require('playwright-core');
(async()=>{const b=await chromium.launch({executablePath:'/home/damirk/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome'});
const p=await b.newPage(); await p.goto('http://localhost:7777/',{waitUntil:'networkidle'});
await p.waitForFunction(()=>typeof STATE!=='undefined'&&STATE.spaces.length>0);
console.log(await p.evaluate(()=>{
  const r=clusterTree(), count=ns=>ns.reduce((a,n)=>a+1+count(n.kids),0);
  return {roots:r.length, total:count(r), spaces:STATE.spaces.length,
          deepest:(function d(ns,l){return ns.length?Math.max(...ns.map(n=>d(n.kids,l+1))):l;})(r,0)};
}));
await b.close();})();"
```

Expected: `{roots: 15, total: 24, spaces: 24, deepest: 3}` — 15 clusters covering all 24 notebooks, three levels deep. **`total` must equal `spaces`**; if it doesn't, a notebook has been dropped.

- [ ] **Step 3: Commit.**

```bash
git add resources/public/index.html
git commit -m "feat: group notebooks into the tree the substrate already knows"
```

---

### Task 2: lay the clusters out, and let them scroll

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: Replace the overview branch of `layout()`.** Replace everything from `} else {` through the line `document.getElementById('edges').innerHTML=lines.join('');` (currently lines ~906-935) with:

```js
  } else {
    world.classList.add('overview'); world.classList.add('cards');
    const PW=380, PH=150, KW=302, KH=96, KGAP=8, GROUPGAP=26, COLGAP=34, INDENT=18;
    const padX=vw*0.045, padTop=top+16;
    const colW=(vw-2*padX-COLGAP*2)/3;
    const s=Math.min(colW/PW,1);
    // pack each cluster into whichever column is currently shortest — measured
    // as it is placed, so no height needs predicting
    const roots=clusterTree();
    const colY=[padTop,padTop,padTop], ctr=[], spines=[];
    const place=(n,x,y,w,h)=>{
      const p=panels[n.i];
      p.style.width=w+'px'; p.style.height=h+'px';
      p.style.transform='translate('+x+'px,'+y+'px) scale('+s+')';
      p.style.opacity='1'; p.style.zIndex=2; p.style.pointerEvents='auto';
      p.classList.toggle('kid', w!==PW);
      ctr[n.i]=[x+w*s/2, y+h*s/2];
      return y+h*s;
    };
    roots.forEach(r=>{
      const c=colY.indexOf(Math.min(...colY));
      const x=padX+c*(colW+COLGAP);
      const top0=colY[c];
      let y=place(r,x,top0,PW,PH)+KGAP;
      (function walk(n,depth){
        n.kids.forEach(k=>{
          const kx=x+INDENT*depth+10, y0=y;
          y=place(k,kx,y,KW,KH)+KGAP;
          spines.push(['tick',x+9,y0+KH*s/2,kx]);   // the elbow into each child
          walk(k,depth+1);
        });
      })(r,1);
      if(r.kids.length) spines.push(['spine',x+9,top0+PH*s,y-KGAP]);
      colY[c]=y+GROUPGAP;
    });
    // .world is fixed at viewport size; scrolling comes from content extending
    // past it. Absolutely-positioned panels do create scrollable overflow, but a
    // spacer makes the bottom margin explicit — and the edges SVG must grow too,
    // or every spine below the fold is clipped.
    const contentH=Math.max(vh,Math.max(...colY)+40);
    let end=document.getElementById('canvasEnd');
    if(!end){ end=document.createElement('div'); end.id='canvasEnd'; world.appendChild(end); }
    end.style.cssText='position:absolute;left:0;width:1px;height:1px;top:'+contentH+'px';
    const edges=document.getElementById('edges');
    edges.style.height=contentH+'px';
    edges.innerHTML=spines.map(sp=>
      sp[0]==='spine'
        ? '<line class="spine" x1="'+sp[1]+'" y1="'+sp[2]+'" x2="'+sp[1]+'" y2="'+sp[3]+'"/>'
        : '<line class="spine" x1="'+sp[1]+'" y1="'+sp[2]+'" x2="'+sp[3]+'" y2="'+sp[2]+'"/>'
    ).join('');
  }
```

- [ ] **Step 2: Reset the canvas height on focus.** In the `if(mode==='focus'){` branch, immediately after `world.classList.remove('cards');`, add:

```js
    // focus is one panel: nothing to scroll, nothing below the fold
    world.scrollTop=0;
    const endEl=document.getElementById('canvasEnd'); if(endEl) endEl.style.top='0px';
    document.getElementById('edges').style.height='';
```

- [ ] **Step 3: Make the world scrollable, and style the spine.** In the CSS, replace:

```css
  .world{position:fixed;inset:0;z-index:10}
```

with:

```css
  .world{position:fixed;inset:0;z-index:10}
  /* the grouped overview is taller than the screen past ~20 notebooks; the
     canvas scrolls rather than shrinking cards below reading size */
  .world.overview{overflow-y:auto;overflow-x:hidden}
```

and replace:

```css
  .edges line{stroke:var(--accent-line);stroke-width:1.5;stroke-dasharray:5 5}
```

with:

```css
  /* a solid spine joins a notebook to the deep-dives it spawned — this is
     containment, not the dashed "related to" thread it replaces */
  .edges line.spine{stroke:var(--accent-line);stroke-width:1;stroke-dasharray:none;opacity:.5}
```

- [ ] **Step 4: Style the nested children.** Add after the existing `.world.cards` rules:

```css
  /* a nested notebook is subordinate: smaller, quieter, no crop marks — its
     position already says whose it is */
  .world.cards .panel.kid .p-title{font-size:16px;line-height:1.2}
  .world.cards .panel.kid .p-intent{display:none}
  .world.cards .panel.kid .p-head{padding:13px 16px 11px}
  .world.cards .panel.kid .p-head::before,
  .world.cards .panel.kid .p-head::after{display:none}
  .world.cards .panel.kid{box-shadow:0 10px 22px -20px rgba(20,30,25,.5)}
```

- [ ] **Step 5: Syntax-check and look at it.**

```bash
python3 -c "
import re; h=open('resources/public/index.html').read()
open('/tmp/shell.js','w').write(re.search(r'<script>(.*)</script>',h,re.S).group(1))"
node --check /tmp/shell.js && echo "JS OK"
```

Then screenshot the real corpus (server on 7777) and **look at the PNG**:

```bash
cd /tmp/claude-1001/-home-damirk-src-loci/d5fc3dfc-241a-458b-b3e4-d9c62d813634/scratchpad
node -e "
const {chromium}=require('playwright-core');
(async()=>{const b=await chromium.launch({executablePath:'/home/damirk/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome'});
const p=await b.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:2});
await p.goto('http://localhost:7777/',{waitUntil:'networkidle'});
await p.waitForFunction(()=>typeof STATE!=='undefined'&&STATE.spaces.length>0);
await p.evaluate(()=>overview()); await p.waitForTimeout(1200);
await p.screenshot({path:'shots/task2-clusters.png'});
console.log(await p.evaluate(()=>({scrollH:document.getElementById('world').scrollHeight,
  viewH:innerHeight, kids:document.querySelectorAll('.panel.kid').length})));
await b.close();})();"
```

Expected: `kids: 9`, and `scrollH` greater than `viewH` (the canvas is taller than the screen — that is the point). Report what the screenshot showed; if intents are cut mid-sentence, raise `PH` until they are not.

- [ ] **Step 6: Commit.**

```bash
git add resources/public/index.html
git commit -m "feat: the overview groups a notebook with the deep-dives it spawned"
```

---

### Task 3: drop what nesting made redundant

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: Simplify the child's eyebrow.** In `buildPanel` (line ~340), replace:

```js
  p.innerHTML='<div class="p-status"><span class="adot"></span>'+(merged?'connected':hub?'spawned':'notebook')+'</div>'+
    '<div class="p-head"><div class="p-eyebrow">'+(merged?'connected · '+esc(mtitles):hub?'deep-dive · spawned from '+esc(hub.title):'notebook · intention')+'</div>'+
```

with:

```js
  // In the overview a child sits UNDER its parent, so naming the parent in the
  // eyebrow is redundant — and it was truncated at card width anyway. Focus
  // mode has no such context, so the full form survives there via .p-parent.
  p.innerHTML='<div class="p-status"><span class="adot"></span>'+(merged?'connected':hub?'spawned':'notebook')+'</div>'+
    '<div class="p-head"><div class="p-eyebrow">'+
      (merged?'connected · '+esc(mtitles):hub?'deep-dive':'notebook · intention')+'</div>'+
```

- [ ] **Step 2: Hide the now-duplicated status pill on nested cards.** Add to the CSS beside the other `.world.cards .panel.kid` rules:

```css
  .world.cards .panel.kid .p-status{display:none}   /* the spine already says it */
```

- [ ] **Step 3: Verify nothing else referenced the old eyebrow text.**

Run: `grep -n "spawned from" resources/public/index.html`

Expected: no hits in `buildPanel`. If the browser tests reference that string, they must be updated in the same commit — report if so.

- [ ] **Step 4: Run the browser suite.**

Run: `npm run test:browser`

Expected: 10 tests, 0 failing. If `the overview stays legible at 12 notebooks` fails, the card metrics changed too far — report the measured px rather than adjusting the test.

- [ ] **Step 5: Commit.**

```bash
git add resources/public/index.html
git commit -m "feat: a nested notebook stops repeating who its parent is"
```

---

### Task 4: the structure line when focused

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: Add the builder.** Immediately above `function buildPanel(`, add:

```js
// One line telling a focused notebook where it sits: its parent, how many
// notebooks share that parent, and what it spawned. The tree-view on
// selection, at the cost of a line rather than a permanent rail.
function structureLine(i){
  const sp=STATE.spaces[i]; if(!sp) return '';
  const pid=sp['spawned-by']||(sp['merged-from']||[])[0];
  const parent=pid?STATE.spaces.find(x=>x.id===pid):null;
  const kids=STATE.spaces.filter(x=>(x['spawned-by']||(x['merged-from']||[])[0])===sp.id);
  const sibs=parent?STATE.spaces.filter(x=>(x['spawned-by']||(x['merged-from']||[])[0])===parent.id&&x.id!==sp.id):[];
  const bits=[];
  if(parent) bits.push('<span class="slink" data-space="'+esc(parent.id)+'">↰ '+esc(parent.title)+'</span>');
  if(sibs.length) bits.push(sibs.length+(sibs.length===1?' sibling':' siblings'));
  if(kids.length) bits.push('<span class="slink" data-kids="1">'+kids.length+
                            (kids.length===1?' deep-dive':' deep-dives')+'</span>');
  return bits.length?'<div class="p-structure">'+bits.join(' · ')+'</div>':'';
}
```

- [ ] **Step 2: Render it.** In `buildPanel`, replace:

```js
    '<h2 class="p-title">'+esc(sp.title)+'</h2><p class="p-intent">'+esc(sp.intent)+'</p></div>'+
```

with:

```js
    '<h2 class="p-title">'+esc(sp.title)+'</h2><p class="p-intent">'+esc(sp.intent)+'</p>'+
    structureLine(i)+'</div>'+
```

- [ ] **Step 3: Wire the parent link.** In `buildPanel`, replace:

```js
  p.addEventListener('click',e=>{ if(mode==='overview'){ e.stopPropagation(); enter(i);} });
```

with:

```js
  p.addEventListener('click',e=>{
    const link=e.target.closest('.slink[data-space]');
    if(link){ e.stopPropagation();
      const j=STATE.spaces.findIndex(x=>x.id===link.dataset.space);
      if(j>=0) enter(j);
      return; }
    if(e.target.closest('.slink[data-kids]')){ e.stopPropagation(); overview(); return; }
    if(mode==='overview'){ e.stopPropagation(); enter(i);}
  });
```

- [ ] **Step 4: Style it.** Add to the CSS beside the `.p-intent` rule:

```css
  .p-structure{font-family:var(--mono);font-size:10.5px;color:var(--faint);margin:9px 0 0}
  .p-structure .slink{color:var(--accent);cursor:pointer;border-bottom:1px solid transparent}
  .p-structure .slink:hover{border-bottom-color:var(--accent-line)}
  .world.cards .p-structure{display:none}   /* the arrangement already shows this */
```

- [ ] **Step 5: Verify by hand against the real corpus.** Server on 7777:

```bash
cd /tmp/claude-1001/-home-damirk-src-loci/d5fc3dfc-241a-458b-b3e4-d9c62d813634/scratchpad
node -e "
const {chromium}=require('playwright-core');
(async()=>{const b=await chromium.launch({executablePath:'/home/damirk/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome'});
const p=await b.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:2});
await p.goto('http://localhost:7777/',{waitUntil:'networkidle'});
await p.waitForFunction(()=>typeof STATE!=='undefined'&&STATE.spaces.length>0);
const i=await p.evaluate(()=>STATE.spaces.findIndex(s=>s.id==='space:dd-16'));
await p.evaluate(n=>enter(n),i); await p.waitForTimeout(1200);
console.log('structure:',await p.evaluate(n=>{
  const el=document.getElementById('body'+n).closest('.panel').querySelector('.p-structure');
  return el?el.textContent:'(none)';},i));
await p.screenshot({path:'shots/task4-structure.png'});
await b.close();})();"
```

`space:dd-16` (Geopolitical Shock Scenarios) is spawned from `space:dd-14`, which has two other children. Expected: `↰ Taiwan's Foundry Concentration · 2 siblings`. Report the actual string.

- [ ] **Step 6: Commit.**

```bash
git add resources/public/index.html
git commit -m "feat: a focused notebook says where it sits"
```

---

### Task 5: browser tests for the arrangement

The fixture seeds 12 notebooks with **no** spawned children, so these tests build their own family. `/api/new-space` needs the LLM, so the fixture is committed directly, the same way the harness seeds its flow object.

**Files:**
- Modify: `test/browser/harness.mjs`
- Create: `test/browser/overview.test.mjs`

- [ ] **Step 1: Add a family to the fixture.** In `test/browser/harness.mjs`, find the `FLOW_FIXTURE` template literal and add this second fixture immediately after it:

```js
// A parent with two children, one of which has a child of its own — the
// overview's clustering is only meaningful against a real tree, and the
// deterministic seed contains none.
const FAMILY_FIXTURE = `
(require '[loci.content :as c] '[loci.substrate :as sub])
(let [st @c/store
      mk (fn [id title parent]
           {:op :put :id id
            :value (cond-> {:id id :kind :space :title title
                            :value {:intent (str title " — fixture") :cells []}}
                     parent (assoc-in [:value :spawned-by] {:space parent}))})]
  (sub/commit! st {:op :tx :events [(mk "space:fam-root" "Fixture hub" nil)
                                    (mk "space:fam-a" "Fixture child A" "space:fam-root")
                                    (mk "space:fam-b" "Fixture child B" "space:fam-root")
                                    (mk "space:fam-a1" "Fixture grandchild" "space:fam-a")]}))
(System/exit 0)
`;
```

Then, in `startServer()`, immediately after the line that runs `FLOW_FIXTURE`, add:

```js
  await run(['-M', '-e', FAMILY_FIXTURE], { LOCI_DATA: dir });
```

- [ ] **Step 2: Write the failing tests.** Create `test/browser/overview.test.mjs`:

```js
// The overview groups a notebook with the deep-dives it spawned. The fixture
// adds a hub with two children, one of which has a child of its own.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const inOverview = async (page) => {
  await page.evaluate(() => overview());
  await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));
};

test('clusterTree covers every notebook and nests three deep', async () => {
  await withPage(browser, 'cluster-tree-covers-all', async (page) => {
    await bootedShell(page, server.url);
    const r = await page.evaluate(() => {
      const roots = clusterTree();
      const count = ns => ns.reduce((a, n) => a + 1 + count(n.kids), 0);
      const depth = (ns, l) => ns.length ? Math.max(...ns.map(n => depth(n.kids, l + 1))) : l;
      return { roots: roots.length, total: count(roots),
               spaces: STATE.spaces.length, deepest: depth(roots, 0) };
    });
    assert.equal(r.total, r.spaces, 'a notebook was dropped from the tree');
    assert.ok(r.roots < r.spaces, 'nothing was grouped');
    assert.equal(r.deepest, 3, 'the grandchild did not nest');
  });
});

test('a child renders smaller than its parent and under it', async () => {
  await withPage(browser, 'child-nests-under-parent', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const box = await page.evaluate(() => {
      const at = id => {
        const i = STATE.spaces.findIndex(s => s.id === id);
        const r = panels[i].getBoundingClientRect();
        return { x: r.x, y: r.y, w: r.width, kid: panels[i].classList.contains('kid') };
      };
      return { root: at('space:fam-root'), a: at('space:fam-a'), a1: at('space:fam-a1') };
    });
    assert.equal(box.root.kid, false, 'the hub should not be a kid');
    assert.equal(box.a.kid, true);
    assert.ok(box.a.w < box.root.w, `child ${box.a.w} not narrower than parent ${box.root.w}`);
    assert.ok(box.a.y > box.root.y, 'child should sit below its parent');
    assert.ok(box.a.x > box.root.x, 'child should be indented');
    assert.ok(box.a1.x > box.a.x, 'grandchild should be indented further');
  });
});

test('the spine joins a hub to its brood', async () => {
  await withPage(browser, 'spine-drawn', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const n = await page.evaluate(() => document.querySelectorAll('#edges line.spine').length);
    assert.ok(n >= 4, `expected a spine and an elbow per child, got ${n} lines`);
  });
});

test('the overview scrolls rather than shrinking below reading size', async () => {
  await withPage(browser, 'overview-scrolls', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const m = await page.evaluate(() => {
      const w = document.getElementById('world');
      const t = document.querySelector('.panel:not(.kid) .p-title');
      return { scrollH: w.scrollHeight, clientH: w.clientHeight,
               overflowY: getComputedStyle(w).overflowY,
               titlePx: t.getBoundingClientRect().height };
    });
    assert.equal(m.overflowY, 'auto', 'the overview must be scrollable');
    assert.ok(m.titlePx >= 12, `titles at ${m.titlePx}px are unreadable`);
  });
});

test('a focused child says where it sits, and the parent link works', async () => {
  await withPage(browser, 'structure-line', async (page) => {
    await bootedShell(page, server.url);
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:fam-a1'));
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector('.p-structure');
    const txt = await page.evaluate(n =>
      document.getElementById('body' + n).closest('.panel').querySelector('.p-structure').textContent, i);
    assert.match(txt, /Fixture child A/, `structure line read: ${txt}`);

    await page.evaluate(n => document.getElementById('body' + n).closest('.panel')
                              .querySelector('.p-structure .slink[data-space]').click(), i);
    await page.waitForFunction(() =>
      typeof focusIdx !== 'undefined' && STATE.spaces[focusIdx].id === 'space:fam-a');
  });
});
```

- [ ] **Step 3: Run them.**

Run: `npm run test:browser`

Expected: 15 tests, 0 failing (10 existing + 5 new). If `clusterTree` is not defined the harness is serving a stale page — the server reads `index.html` from disk per request, so restart nothing; check you edited the right file.

- [ ] **Step 4: Prove the cluster tests bite.** Temporarily change `clusterTree`'s parent lookup from

```js
    const parent = sp['spawned-by'] || (sp['merged-from']||[])[0];
```

to

```js
    const parent = (sp['spawned-by']||{}).space || (sp['merged-from']||[])[0];
```

— the exact mistake that produced 24 groups instead of 15 during design. Run `npm run test:browser` and confirm the cluster tests FAIL. Then restore the correct line and confirm `git diff resources/public/index.html` shows only your intended changes.

Report which tests failed under the sabotage. If they all passed, the tests are worthless and must be fixed here.

- [ ] **Step 5: Commit.**

```bash
git add test/browser/harness.mjs test/browser/overview.test.mjs
git commit -m "test: the overview clusters, nests, scrolls and says where you are"
```

---

### Task 6: docs and final verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Describe the overview.** In `README.md`, immediately after the paragraph beginning "The two suites are deliberately separate", add:

```markdown
Zoomed out, the overview groups each notebook with the deep-dives it spawned — the
structure the substrate already computes from `spawned-by` and `merged-from`, rather than
laying every notebook out as a peer. The canvas scrolls; a focused notebook shows its
parent, siblings and children in one line under its intention.
```

- [ ] **Step 2: Run everything.**

```bash
clojure -M:test          # expect 128 tests / 602 assertions, 0 failures
npm run test:browser     # expect 15 tests, 0 failing
md5sum data/substrate.edn   # must be e7a6eecdff7bb44ad6b959bb1a7fb7c6
```

- [ ] **Step 3: Look at the result one last time.** Screenshot the real corpus in overview and focus, and confirm by eye: 15 clusters, spines joining each hub to its brood, no card content cut mid-sentence, and the structure line present on a child.

- [ ] **Step 4: Commit.**

```bash
git add README.md
git commit -m "docs: the overview groups by what spawned what"
```

---

## Verification checklist

- [ ] `clusterTree()` covers all 24 notebooks in 15 roots, three levels deep
- [ ] 9 panels carry `.kid`; none of the 15 roots does
- [ ] The world scrolls (`overflow-y:auto`) and root titles measure ≥ 12px
- [ ] Spines join each hub to its brood; no dashed cross-screen threads remain
- [ ] A focused child shows `↰ parent · N siblings`, and the parent link enters it
- [ ] `clojure -M:test` unchanged at 128/602; `npm run test:browser` at 15
- [ ] The sabotage in Task 5 Step 4 made the cluster tests fail
- [ ] `data/substrate.edn` md5 unchanged

## Out of scope (phase 2 and later)

- Tags entirely — the model, the `⌗ tags` action, the agent proposal, the filter strip
- Tag inheritance for spawned children
- Collapsing a cluster to a count chip
- Zoom tiers (revisit past ~40 notebooks, per the spec)
- Any change to LEAP
