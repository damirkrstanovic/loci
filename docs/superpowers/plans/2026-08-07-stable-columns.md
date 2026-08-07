# Stable Overview Columns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A notebook you are looking at must not move because you created a different one.

**Architecture:** The overview keeps each root cluster in the column it is already in. A root
that has no column yet takes the shortest one, as today. Nothing else about the layout
changes — same three columns, same greedy choice for newcomers, same spines.

**Tech Stack:** vanilla JS in `resources/public/index.html`; `npm run test:browser`.

---

## The defect, measured

`layout()`'s overview branch (`resources/public/index.html:1241`) does:

```javascript
    roots.forEach(r=>{
      const c=colY.indexOf(Math.min(...colY));
```

Each cluster is placed in whichever column is **currently** shortest, so its column depends
on every cluster placed before it. Two things perturb that:

1. **Root order is not creation order.** `state-payload`'s `:spaces` comes from
   `(vals (sub/objects st))` — a hash map — so a newly created notebook appears at an
   arbitrary position. Measured: position 34 of 36 on a copy of the real corpus, position 1
   of 35 on a smaller store. Anything inserted before the end shifts every later cluster by
   one slot in the packing order.
2. **Any height change re-routes everything downstream** — a cluster gaining a child, a
   title wrapping to two lines.

Measured on a copy of the real 35-notebook corpus, creating one unrelated notebook moved
`space:pub-pop` from `x=72` to `x=569` — one full column across the screen. On the
16-notebook browser fixture nothing moved, which is why this needs a test built on a
deliberately perturbing arrangement rather than on the fixture as it stands.

**Not in scope:** making the *order* creation-stable on the server. It would help, but it
would not fix the height-change case, and sticky columns fix both.

---

### Task 1: Roots keep their column

**Files:**
- Modify: `resources/public/index.html` — `layout()`'s overview branch (~`:1232-1261`)
- Test: `test/browser/overview.test.mjs`

- [ ] **Step 1: Read first**

Read `layout()` in full, from `function layout()` (~`:1189`) to the end of the overview
branch. Note: `colY` is `[padTop,padTop,padTop]`, `roots` is the cluster forest, `place()`
writes `transform` inline, and `panels` is the module-level array parallel to `STATE.spaces`.

Also read `clusterTree()` and `applyState` to see how `roots` is derived and when `layout()`
runs.

- [ ] **Step 2: Write the failing test**

In `test/browser/overview.test.mjs`, following that file's existing structure and helpers.
The 16-notebook fixture does not perturb on its own — build the perturbation explicitly by
creating notebooks until at least one existing card changes column under today's code.

```javascript
test('creating a notebook never moves the ones already on screen', async () => {
  // …boot the shell and enter the overview as the other tests in this file do…
  const columns = () => page.evaluate(() => {
    const o = {};
    STATE.spaces.forEach((s, k) => {
      const m = panels[k].style.transform.match(/translate\(([-\d.]+)px,/);
      if (m) o[s.id] = Math.round(+m[1]);
    });
    return o;
  });

  const before = await columns();
  // six creations, because a single one may land at the end of the hash order and
  // perturb nothing — the defect is data-dependent and one sample can miss it
  for (let n = 0; n < 6; n++) {
    await page.evaluate(async i => {
      await fetch('/api/new-space', { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: `stability probe ${i}` }) });
      const r = await fetch('/api/state'); applyState(await r.json()); rebuild(); overview();
    }, n);
    await page.waitForTimeout(250);
  }
  const after = await columns();

  const moved = Object.keys(before).filter(id => after[id] !== undefined && after[id] !== before[id]);
  assert.deepStrictEqual(moved, [],
    `these notebooks changed column when unrelated ones were created: ` +
    moved.map(id => `${id} ${before[id]}->${after[id]}`).join(', '));
});
```

**Note:** `/api/new-space` may invoke the agent to title the notebook. Check `new-space!` in
`src/loci/server.clj` — if it calls a model, intercept the route with `page.route` and stub
it, or use a path that writes a plain notebook without a model. The test must not need
credentials. Say in your report which you did.

- [ ] **Step 3: Run it and watch it fail**

Run: `node --test test/browser/overview.test.mjs`
Expected: FAIL, naming at least one notebook that changed column. **If it passes, the
perturbation is not strong enough — raise the creation count or vary the titles until it
fails, and report what it took.** A test that passes before the fix proves nothing.

- [ ] **Step 4: Implement**

Add a module-level map near the other layout state, and consult it in the packing loop:

```javascript
// A cluster keeps the column it is already in. The greedy "whichever is shortest
// right now" choice made a root's column depend on every root placed before it, so
// creating one notebook could move a different one a full column across the screen
// (measured: space:pub-pop, x 72 -> 569, on a 35-notebook corpus). Newcomers still
// take the shortest column; it is only the re-deciding that was wrong.
let colOf = {};                       // root id -> column index
```

In the packing loop, replace the column choice:

```javascript
    roots.forEach(r=>{
      const id = STATE.spaces[r.i].id;
      let c = colOf[id];
      if (c === undefined || c >= colY.length) { c = colY.indexOf(Math.min(...colY)); colOf[id] = c; }
      const x = padX + c*(colW+COLGAP);
```

Then handle the two cases where remembering is wrong:

- **The column count changes** (window resize, or however `colY.length` is derived — read the
  code; if it is a fixed 3 this branch is still worth keeping honest). When it changes, the
  remembered indices are meaningless, so reset `colOf = {}` before packing.
- **A root stops being a root** (it gained a parent, or was removed). Its entry is stale but
  harmless — it is simply never consulted. Do **not** grow `colOf` forever silently: prune
  ids no longer in `STATE.spaces` at the top of the overview branch, and say in a comment
  that the prune is for size, not correctness.

**Decide and state plainly in a comment:** the cost of stickiness is that columns can drift
out of balance as notebooks accumulate, because nothing ever re-balances except a resize.
That is the trade — a still overview that is slightly uneven beats a balanced one that moves
under you. If you disagree after reading the code, say so in your report rather than
silently choosing something else.

- [ ] **Step 5: Run the test**

Run: `node --test test/browser/overview.test.mjs` — the new test passes and the file's
existing tests still pass.

- [ ] **Step 6: Prove it bites**

Restore the original `const c=colY.indexOf(Math.min(...colY));` line, run the single file,
confirm the new test fails with the notebook names in the message, restore. Report the
actual failure output.

- [ ] **Step 7: Check the layout still looks right**

The point of packing into the shortest column was balance. Measure it: with the fix in
place, print the three final `colY` values on the fixture and after the six creations, and
report them. If the tallest column is more than roughly twice the shortest, say so — that is
a real cost and the user should hear the number rather than a reassurance.

- [ ] **Step 8: Full browser suite once**

Run: `npm run test:browser 2>&1 | tail -8`. Expected 37/37 (36 today, plus the new one).

- [ ] **Step 9: Commit**

```bash
git add resources/public/index.html test/browser/overview.test.mjs
git commit -m "fix: a cluster keeps its column, so creating a notebook stops moving the others"
```

## Out of scope

- Making `state-payload`'s space order creation-stable on the server
- Rebalancing columns on demand, or a "tidy up" gesture
- Changing the number of columns, or making it responsive to width
- Animating the cards that legitimately do move
