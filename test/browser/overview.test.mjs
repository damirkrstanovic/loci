// The overview groups a notebook with the deep-dives it spawned. The fixture
// adds a hub with two children, one of which has a child of its own.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
// Its own server, and so its own substrate. Every other browser file now shares one,
// but the stability test below creates three real notebooks through /api/connect, and
// those outlive it: run against the shared server, tags.test.mjs then counts a corpus
// of 19 and fails its "2/14 split" and "1 of 16" assertions (measured, both of them).
// Leaving that to file ordering would make the suite order-dependent, so this file
// pays a second cold JVM instead. Setup only — no assertion in this file changes.
before(async () => { server = await startServer({ fresh: true }); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// The shell boots in focus mode, so overview() starts a .5s transform
// transition on every panel. A rect read before it settles is the focus
// layout on its way out — a broken arrangement would measure fine mid-flight.
// The spines are plain SVG attributes and land instantly; the cards are what
// we have to wait for.
const settle = (page) => page.evaluate(() => new Promise((res, rej) => {
  const key = () => panels.map(p => { const r = p.getBoundingClientRect();
    return [r.x, r.y, r.width, r.height].map(v => Math.round(v)).join(','); }).join(';');
  let last = null, stable = 0, frames = 0;
  const give = why => rej(new Error(why + ' — the .5s panel transition settles in ~40 frames'));
  const clock = setTimeout(() => give('the overview never settled in 15s'), 15_000);
  const tick = () => {
    const k = key();
    if (k === last) { if (++stable >= 3) { clearTimeout(clock); return res(); } } else stable = 0;
    last = k;
    if (++frames > 600) { clearTimeout(clock); return give('the overview never settled in 600 frames'); }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}));

const inOverview = async (page) => {
  await page.evaluate(() => overview());
  await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));
  await settle(page);
};

// Panels are positioned inside #world, which is where the #edges SVG's own
// coordinate space starts too — so a card's rect and a spine's attributes are
// directly comparable once the world's scroll offset is added back.
const familyGeometry = (page) => page.evaluate(() => {
  const w = document.getElementById('world');
  const box = id => {
    const i = STATE.spaces.findIndex(s => s.id === id);
    if (i < 0) throw new Error(id + ' is missing from the fixture');
    const r = panels[i].getBoundingClientRect();
    return { x: r.x + w.scrollLeft, y: r.y + w.scrollTop, w: r.width, h: r.height,
             kid: panels[i].classList.contains('kid') };
  };
  const lines = [...document.querySelectorAll('#edges line.spine')].map(l => ({
    x1: +l.getAttribute('x1'), y1: +l.getAttribute('y1'),
    x2: +l.getAttribute('x2'), y2: +l.getAttribute('y2') }));
  return { lines, root: box('space:fam-root'), a: box('space:fam-a'),
           b: box('space:fam-b'), a1: box('space:fam-a1') };
});

test('clusterTree covers every notebook and nests three deep', async () => {
  await withPage(browser, 'cluster-tree-covers-all', async (page) => {
    await bootedShell(page, server.url);
    const r = await page.evaluate(() => {
      const roots = clusterTree();
      const count = ns => ns.reduce((a, n) => a + 1 + count(n.kids), 0);
      const depth = (ns, l) => ns.length ? Math.max(...ns.map(n => depth(n.kids, l + 1))) : l;
      const idsUnder = id => {
        const find = ns => { for (const n of ns) {
          if (n.sp.id === id) return n; const hit = find(n.kids); if (hit) return hit; } return null; };
        const n = find(roots);
        return n ? n.kids.map(k => k.sp.id).sort() : null;
      };
      return { roots: roots.length, total: count(roots),
               spaces: STATE.spaces.length, deepest: depth(roots, 0),
               hubKids: idsUnder('space:fam-root'), aKids: idsUnder('space:fam-a'),
               rootIds: roots.map(n => n.sp.id) };
    });
    assert.equal(r.total, r.spaces, 'a notebook was dropped from the tree');
    assert.ok(r.roots < r.spaces, 'nothing was grouped');
    assert.equal(r.deepest, 3, 'the grandchild did not nest');
    // …and grouped by the right edges, not merely grouped
    assert.deepEqual(r.hubKids, ['space:fam-a', 'space:fam-b'],
      'the hub did not adopt exactly its two children');
    assert.deepEqual(r.aKids, ['space:fam-a1'], 'the grandchild hangs off the wrong parent');
    for (const id of ['space:fam-a', 'space:fam-b', 'space:fam-a1'])
      assert.ok(!r.rootIds.includes(id), `${id} was left at the top level`);
    assert.ok(r.rootIds.includes('space:fam-root'), 'the hub itself should be a root');
  });
});

test('a child renders smaller than its parent and under it', async () => {
  await withPage(browser, 'child-nests-under-parent', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const box = await familyGeometry(page);
    assert.equal(box.root.kid, false, 'the hub should not be a kid');
    assert.equal(box.a.kid, true);
    assert.equal(box.b.kid, true);
    assert.equal(box.a1.kid, true);
    assert.ok(box.a.w < box.root.w, `child ${box.a.w} not narrower than parent ${box.root.w}`);
    assert.ok(box.a.y > box.root.y, 'child should sit below its parent');
    assert.ok(box.a.x > box.root.x, 'child should be indented');
    assert.ok(box.a1.x > box.a.x, 'grandchild should be indented further');
    // the brood stays with its hub: same column, and nothing else wedged between
    assert.ok(box.b.y > box.a1.y, 'the second child should follow the first one’s subtree');
    assert.ok(Math.abs(box.b.x - box.a.x) < 1, 'siblings should share an indent');
  });
});

test('the spine joins a hub to its brood', async () => {
  await withPage(browser, 'spine-drawn', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const g = await familyGeometry(page);
    assert.ok(g.lines.length >= 4,
      `expected a spine and an elbow per child, got ${g.lines.length} lines`);

    const near = (p, q) => Math.abs(p - q) <= 2;
    // ['tick', railX, y, kidX] renders as a horizontal elbow ending at the left
    // edge of the card it points to; ['spine', x, y0, y1] as a vertical rail.
    const elbows = g.lines.filter(l => near(l.y1, l.y2));
    const rails = g.lines.filter(l => near(l.x1, l.x2));
    const elbowInto = (name, c) => {
      const hit = elbows.find(l => near(l.x2, c.x) && l.y1 > c.y - 2 && l.y1 < c.y + c.h + 2);
      assert.ok(hit, `no elbow reaches ${name} at x=${c.x}, y∈[${c.y}, ${c.y + c.h}]`);
      return hit;
    };
    const [ea, eb, ea1] = [elbowInto('child A', g.a), elbowInto('child B', g.b),
                           elbowInto('the grandchild', g.a1)];
    assert.ok(near(ea.x1, eb.x1), `siblings hang off different rails: ${ea.x1} vs ${eb.x1}`);
    assert.ok(ea.x1 > g.root.x && ea.x1 < g.a.x, 'the hub rail should run between hub and child');
    // the point of the arrangement: a notebook that spawned something carries
    // its OWN rail, so the grandchild hangs off child A and not off the hub
    assert.ok(ea1.x1 > ea.x1,
      `the grandchild hangs off the hub's rail (${ea1.x1}), not its parent's (${ea.x1})`);
    assert.ok(ea1.x1 > g.a.x, 'child A’s rail should be indented past child A itself');
    const railX = [...new Set(rails.map(l => Math.round(l.x1)))];
    assert.ok(railX.length >= 2, `one rail for a two-level tree: ${JSON.stringify(railX)}`);
    // the hub's rail hangs from under the hub card down to its last child
    const hubRail = rails.find(l => near(l.x1, ea.x1));
    assert.ok(hubRail, 'the hub has an elbow but no rail');
    assert.ok(Math.min(hubRail.y1, hubRail.y2) >= g.root.y + g.root.h - 2,
      'the hub rail starts above the bottom of the hub card');
    assert.ok(Math.max(hubRail.y1, hubRail.y2) >= eb.y1 - 2,
      'the hub rail stops short of its last child');
  });
});

test('the overview scrolls rather than shrinking below reading size', async () => {
  await withPage(browser, 'overview-scrolls', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const m = await page.evaluate(() => {
      const w = document.getElementById('world');
      const t = document.querySelector('.panel:not(.kid) .p-title');
      const k = document.querySelector('.panel.kid .p-title');
      return { scrollH: w.scrollHeight, clientH: w.clientHeight,
               overflowY: getComputedStyle(w).overflowY,
               edgesH: document.getElementById('edges').getBoundingClientRect().height,
               titlePx: t.getBoundingClientRect().height,
               kidTitlePx: k ? k.getBoundingClientRect().height : null };
    });
    assert.equal(m.overflowY, 'auto', 'the overview must be scrollable');
    assert.ok(m.titlePx >= 12, `titles at ${m.titlePx}px are unreadable`);
    assert.notEqual(m.kidTitlePx, null, 'no nested card in the overview — nothing to measure');
    assert.ok(m.kidTitlePx >= 12, `nested titles at ${m.kidTitlePx}px are unreadable`);
    // the canvas has to actually extend past the fold, and the spine layer with
    // it — an SVG left at viewport height clips every rail below the fold
    assert.ok(m.scrollH > m.clientH,
      `the fixture no longer overflows (${m.scrollH} ≤ ${m.clientH}); this test proves nothing`);
    assert.ok(m.edgesH >= m.scrollH - 4,
      `the spine layer is ${m.edgesH}px inside a ${m.scrollH}px canvas — rails below the fold are clipped`);
    // scrolling is only worth having if it goes somewhere
    const moved = await page.evaluate(() => {
      const w = document.getElementById('world');
      w.scrollTop = 400; return w.scrollTop;
    });
    assert.ok(moved > 0, 'the overview would not scroll');
  });
});

// A notebook you are looking at must not move because you created a different
// one. layout() used to pack each cluster into whichever column was shortest
// *right now*, so a cluster's column depended on every cluster placed before
// it. Measured on the real 35-notebook corpus, creating one unrelated notebook
// moved space:pub-pop from x=72 to x=569 — a full column across the screen.
//
// The 16-notebook fixture does not perturb on its own, so this test builds the
// perturbation. Both of the defect's causes are reproduced:
//
//   * a cluster growing taller, which re-routes every cluster packed after it —
//     driven by real writes through /api/connect. That is the only route that
//     creates a notebook without calling the model (server.clj new-space! goes
//     through agent/plan-space, which needs a key and a network); what it
//     creates carries :merged-from, so it nests under its first parent.
//   * a new notebook arriving at an arbitrary index, because /api/state's
//     :spaces comes from a hash map and is therefore not creation order
//     (measured at position 1 of 35 on a real store). No route creates a plain
//     top-level notebook without a model, so that one is spliced into the
//     payload here; applyState, rebuild and layout are the real ones.
const PERTURB = [
  ['connect', { a: 'space:cosmos', b: 'space:world' }],
  ['splice', 1],
  ['connect', { a: 'space:finance', b: 'space:sales' }],
  ['splice', 4],
  ['connect', { a: 'space:world', b: 'space:finance' }],
  ['splice', 7],
];

// layout() writes each card's position as an INLINE transform, which beats any
// stylesheet rule — so read the inline style. A class-counting assertion in
// this file once passed straight through a shipped no-op for exactly that reason.
const columnX = (page) => page.evaluate(() => {
  const o = {};
  STATE.spaces.forEach((s, k) => {
    const m = panels[k].style.transform.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px/);
    if (m) o[s.id] = { x: Math.round(+m[1]), y: Math.round(+m[2]) };
  });
  return o;
});

const perturb = (page, [kind, arg]) => page.evaluate(async ([kind, arg]) => {
  if (kind === 'connect') {
    const r = await fetch('/api/connect', { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(arg) });
    const j = await r.json();
    if (j.error) throw new Error('/api/connect refused: ' + j.error);
  } else {
    window.__spliced = (window.__spliced || []).concat(arg);
  }
  const s = await (await fetch('/api/state')).json();
  // Clone a real top-level notebook rather than inventing one, so every field
  // the shell reads is present; drop the parent links so the clone is a ROOT.
  (window.__spliced || []).forEach((at, n) => {
    const src = s.spaces.find(sp => !sp['spawned-by'] && !(sp['merged-from'] || []).length);
    const fresh = { ...src, id: 'space:probe-' + n, title: 'Stability probe ' + n,
                    intent: 'a notebook created while you were looking elsewhere' };
    delete fresh['spawned-by']; delete fresh['merged-from'];
    s.spaces.splice(Math.min(at, s.spaces.length), 0, fresh);
  });
  applyState(s); rebuild(); overview();
}, [kind, arg]);

test('creating a notebook never moves the ones already on screen', async () => {
  await withPage(browser, 'columns-stay-put', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const before = await columnX(page);

    for (const step of PERTURB) { await perturb(page, step); await settle(page); }
    const after = await columnX(page);

    // Guard against a vacuous pass: the perturbation has to have actually
    // rearranged the overview. If notebooks stopped arriving, or nothing below
    // them shifted, this test would certify a layout it never disturbed.
    const survivors = Object.keys(before).filter(id => after[id]);
    assert.equal(survivors.length, Object.keys(before).length,
      'the fixture lost a notebook — the perturbation did more than create');
    assert.ok(Object.keys(after).length >= Object.keys(before).length + PERTURB.length,
      `only ${Object.keys(after).length - Object.keys(before).length} notebooks arrived ` +
      `out of ${PERTURB.length} — the perturbation did not take`);
    assert.ok(survivors.some(id => after[id].y !== before[id].y),
      'not one card moved vertically, so the packing was never re-run — this test proves nothing');

    const moved = survivors.filter(id => after[id].x !== before[id].x);
    assert.deepStrictEqual(moved, [],
      'these notebooks changed column when unrelated ones were created: ' +
      moved.map(id => `${id} ${before[id].x}->${after[id].x}`).join(', '));
  });
});

test('a focused child says where it sits, and the parent link works', async () => {
  await withPage(browser, 'structure-line', async (page) => {
    await bootedShell(page, server.url);
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:fam-a1'));
    assert.ok(i >= 0, 'space:fam-a1 missing from the fixture');
    // the structure line of whichever panel is focused, read from its own body id
    const structure = async (n, who) => {
      const t = await page.evaluate(k => {
        const el = document.getElementById('body' + k).closest('.panel').querySelector('.p-structure');
        return el ? el.textContent : null;
      }, n);
      assert.notEqual(t, null, `${who} has no structure line at all`);
      return t;
    };

    await page.evaluate(n => enter(n), i);
    await page.waitForSelector('.p-structure');
    const txt = await structure(i, 'the grandchild');
    assert.match(txt, /Fixture child A/, `structure line read: ${txt}`);
    assert.doesNotMatch(txt, /Fixture hub/, `the grandchild named the hub as its parent: ${txt}`);

    // …and the hub's own line counts what it spawned rather than naming a parent
    const hub = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:fam-root'));
    await page.evaluate(n => enter(n), hub);
    const hubTxt = await structure(hub, 'the hub');
    assert.match(hubTxt, /2 deep-dives/, `hub structure line read: ${hubTxt}`);
    assert.doesNotMatch(hubTxt, /↰/, `the hub claims a parent: ${hubTxt}`);

    // child A sits between the two: one sibling above, one deep-dive below
    const a = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:fam-a'));
    await page.evaluate(n => enter(n), a);
    const aTxt = await structure(a, 'child A');
    assert.match(aTxt, /Fixture hub/, `child A structure line read: ${aTxt}`);
    assert.match(aTxt, /1 sibling/, `child A structure line read: ${aTxt}`);
    assert.match(aTxt, /1 deep-dive/, `child A structure line read: ${aTxt}`);

    // the parent is a link, and following it focuses the parent
    await page.evaluate(n => document.getElementById('body' + n).closest('.panel')
                               .querySelector('.p-structure .slink[data-space]').click(), a);
    await page.waitForFunction(() =>
      typeof focusIdx !== 'undefined' && STATE.spaces[focusIdx].id === 'space:fam-root');
  });
});
