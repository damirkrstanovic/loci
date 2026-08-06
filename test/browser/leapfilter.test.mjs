// Filtering the overview is an entry in LEAP's own list, not a mode. The fixture
// seeds 12 notebooks plus the family from harness.mjs — 16 in all, of which
// exactly one (space:cosmos) holds tbl:planets while its title says "The Solar
// System", so "planets" is a query that splits the overview 1/15 and can only
// match through a contained object.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// Every keystroke fires its own renderLeap, and each awaits the network — the
// list one keystroke behind is a real state to read. The Ask entry carries the
// query verbatim in either mode, so it is the marker for "what is on screen
// belongs to the last keystroke".
const leapTo = async (page, text) => {
  await page.click('#q');
  await page.locator('#q').pressSequentially(text, { delay: 25 });
  await page.waitForFunction(t => [...document.querySelectorAll('#results .res .title')]
    .some(el => el.textContent.includes('Ask the agent: “' + t + '”')), text);
};

const filterRow = (page) => page.locator('#results .res').filter({ hasText: 'Filter the overview' });

// Panels transition opacity over .4s, so a value read the moment the filter is
// applied is the pre-filter opacity on its way down — and a dim rule that does
// nothing at all reads identically. Wait for the numbers to stop moving, then
// assert them; a broken rule settles too, at the wrong value, which fails with
// a number rather than a timeout.
const settleOpacity = (page) => page.evaluate(() => new Promise((res, rej) => {
  const key = () => panels.map(p => getComputedStyle(p).opacity).join(',');
  let last = null, stable = 0, frames = 0;
  const clock = setTimeout(() => rej(new Error('panel opacity never settled in 10s')), 10_000);
  const tick = () => {
    const k = key();
    if (k === last) { if (++stable >= 3) { clearTimeout(clock); return res(); } } else stable = 0;
    last = k;
    if (++frames > 600) { clearTimeout(clock); return rej(new Error('opacity never settled in 600 frames')); }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}));

// What the eye sees: which panels are still on the canvas, which are lit, and how
// dark the dimmed ones actually got. Class membership is deliberately NOT the
// measure — layout() writes opacity inline on every panel it places, so the dim
// classes can all be present while nothing on screen has changed.
const measure = (page) => page.evaluate(() => {
  const op = p => +getComputedStyle(p).opacity;
  const dim = panels.filter(p => p.classList.contains('dim'));
  const lit = panels.filter(p => !p.classList.contains('dim'));
  return {
    total: panels.length,
    present: document.querySelectorAll('.panel').length,
    matched: overviewFilter ? [...overviewFilter.ids].sort() : null,
    dim: dim.length,
    dimMax: dim.length ? Math.max(...dim.map(op)) : null,
    litMin: lit.length ? Math.min(...lit.map(op)) : null,
    litIds: panels.map((p, k) => [p, k]).filter(([p]) => !p.classList.contains('dim'))
                  .map(([, k]) => STATE.spaces[k].id).sort(),
  };
});

const applyFilter = async (page, q) => {
  await leapTo(page, q);
  await filterRow(page).click();
  await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter !== null);
  // the dropdown closes under the pointer, which then hovers whatever panel was
  // behind it — and `.panel.dim:hover` deliberately lifts that one to .22→.5.
  // Park the mouse on the topbar so the measurement is of the filter, not of a
  // hover the user did not ask for.
  await page.mouse.move(5, 5);
  await settleOpacity(page);
};

test('the Filter entry appears in the overview, above the fold, and never in focus', async () => {
  await withPage(browser, 'filter-entry-overview-only', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    // a single letter matches enough of the substrate that the list overflows its
    // 46vh — which is the only condition under which "above the fold" means anything
    await leapTo(page, 'a');
    const m = await page.evaluate(() => {
      const box = document.getElementById('results');
      const rows = [...box.querySelectorAll('.res')];
      const i = rows.findIndex(r => r.querySelector('.title').textContent.includes('Filter the overview'));
      if (i < 0) return { i, rows: rows.length };
      const br = box.getBoundingClientRect(), fr = rows[i].getBoundingClientRect();
      return { i, rows: rows.length, label: rows[i].querySelector('.title').textContent,
               scrollH: box.scrollHeight, clientH: box.clientHeight,
               top: fr.top - br.top, bottom: fr.bottom - br.top };
    });
    assert.ok(m.i >= 0, `no Filter entry among ${m.rows} results in the overview`);
    assert.match(m.label, /⊟ Filter the overview: “a” · \d+ of \d+/,
      `the entry should say what it would do and to how many: ${m.label}`);
    assert.ok(m.scrollH > m.clientH + 40,
      `the fixture no longer overflows the results fold (${m.scrollH} ≤ ${m.clientH}); ` +
      'this half of the test proves nothing');
    // appended last it sat at flat index 11 of 31, ~1500px down a 505px box:
    // present in the DOM, invisible on screen, which is the burial it exists to undo
    assert.ok(m.bottom <= m.clientH,
      `the Filter entry is ${Math.round(m.top)}–${Math.round(m.bottom)}px into a ` +
      `${m.clientH}px list — below the fold, so nobody will ever see it`);
    assert.equal(m.i, 1, 'the Filter entry belongs right under Ask');

    await page.keyboard.press('Escape');
    await page.evaluate(() => enter(0));
    await leapTo(page, 'a');
    // one snapshot, so "the list for a" and "no Filter entry" are facts about the
    // same render — an emptied list would satisfy the second on its own
    const focus = await page.evaluate(() =>
      [...document.querySelectorAll('#results .res .title')].map(e => e.textContent));
    assert.ok(focus.some(t => t.includes('Ask the agent: “a”')),
      `the focus list is not the one for “a”: ${JSON.stringify(focus.slice(0, 3))}`);
    assert.equal(focus.filter(t => t.includes('Filter the overview')).length, 0,
      'focus mode has no overview to filter');
  });
});

test('selecting it dims non-matching notebooks without removing them', async () => {
  await withPage(browser, 'filter-dims-not-hides', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await applyFilter(page, 'planets');
    const m = await measure(page);

    assert.ok(m.matched.length > 0 && m.matched.length < m.total,
      `"planets" matched ${m.matched.length} of ${m.total} notebooks — a query that ` +
      'matches all or none cannot show the difference between dimmed and lit');
    assert.equal(m.present, m.total, 'panels must be dimmed, never removed');
    assert.equal(m.dim, m.total - m.matched.length);
    assert.deepEqual(m.litIds, m.matched, 'the lit panels are not the matching ones');
    // the assertion the shipped bug slipped past: the classes were all there and
    // the crumb was right, but place()'s inline opacity:1 outranked the stylesheet
    // and the screen never changed. Measure the pixels, not the class list.
    assert.ok(m.dimMax <= .3,
      `non-matching panels are still at opacity ${m.dimMax} — .panel.dim is a no-op ` +
      'against the inline opacity layout() writes on every panel');
    assert.equal(m.litMin, 1, 'a matching notebook must stay fully lit');
  });
});

test('a notebook matches through an object it contains, not only by title', async () => {
  await withPage(browser, 'filter-matches-by-content', async (page) => {
    await bootedShell(page, server.url);
    // tbl:planets lives in space:cosmos, whose title says nothing about planets
    const r = await page.evaluate(async () => {
      const entries = await API.leap('planets');
      const ids = notebooksMatching(entries);
      return { has: ids.has('space:cosmos'), size: ids.size, total: STATE.spaces.length,
               groups: entries.map(e => e.group + ':' + e.id),
               titleMatched: entries.some(e => e.group === 'space' && e.id === 'space:cosmos') };
    });
    assert.equal(r.has, true,
      `the notebook holding tbl:planets should match; LEAP returned ${JSON.stringify(r.groups)}`);
    assert.equal(r.titleMatched, false, 'and not because its own title matched');
    assert.ok(r.size < r.total, 'a filter that matches every notebook filters nothing');
  });
});

test('the filter survives a resize and clears on entering a notebook', async () => {
  await withPage(browser, 'filter-sticky-and-cleared', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await applyFilter(page, 'planets');
    const before = await measure(page);

    // a resize re-runs layout(), which rewrites opacity inline on every panel it
    // places — the filter has to be re-applied after it, and visibly so
    await page.setViewportSize({ width: 1200, height: 900 });
    await page.evaluate(() => layout());
    await settleOpacity(page);
    const after = await measure(page);
    assert.ok(after.dim > 0, 'the filter did not survive layout()');
    assert.deepEqual(after.matched, before.matched, 'the resize changed what matched');
    assert.equal(after.dim, after.total - after.matched.length);
    assert.ok(after.dimMax <= .3,
      `after a resize the dimmed panels are back at opacity ${after.dimMax} — ` +
      'layout() re-lit them and the filter only looks applied');
    assert.ok(await page.locator('#crumbFilter').count() > 0, 'the crumb lost the filter on resize');

    // enter() drops the filter in the same tick, so read it rather than wait for
    // it: a waitForFunction here turns "it never cleared" into a 30s timeout with
    // nothing to read, instead of a one-line failure naming the filter still on
    await page.evaluate(() => enter(0));
    await settleOpacity(page);
    const still = await page.evaluate(() => overviewFilter === null ? null : overviewFilter.q);
    assert.equal(still, null,
      `entering a notebook must drop the overview filter — “${still}” is still applied`);
    const cleared = await measure(page);
    assert.equal(cleared.dim, 0, 'entering a notebook left panels dimmed');
    assert.equal(cleared.present, cleared.total, 'entering a notebook removed panels');
    assert.equal(await page.locator('#crumbFilter').count(), 0,
      'the crumb still advertises a filter that is gone');
  });
});

test('the crumb shows the filter and its ✕ clears it without changing mode', async () => {
  await withPage(browser, 'filter-crumb', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await applyFilter(page, 'planets');

    await page.waitForSelector('#crumbFilter');
    const txt = await page.textContent('#crumbFilter');
    assert.match(txt, /“planets”/, `the crumb should quote the query back: ${txt}`);
    const counts = await page.evaluate(() =>
      [document.querySelector('#crumbFilter b').textContent,
       overviewFilter.ids.size + ' of ' + STATE.spaces.length]);
    assert.equal(counts[0], counts[1], 'the crumb count disagrees with what is lit');

    await page.click('#crumbFilter .x');
    await settleOpacity(page);
    const still = await page.evaluate(() => overviewFilter === null ? null : overviewFilter.q);
    assert.equal(still, null, `the ✕ left “${still}” applied`);
    const m = await measure(page);
    assert.equal(await page.evaluate(() => mode), 'overview',
      'clearing the filter must not toggle the crumb into focus');
    assert.equal(m.dim, 0, 'the ✕ left panels dimmed');
    assert.equal(m.litMin, 1, 'the ✕ cleared the classes but the canvas is still grey');
    assert.equal(await page.locator('#crumbFilter').count(), 0, 'the ✕ did not remove itself');
  });
});
