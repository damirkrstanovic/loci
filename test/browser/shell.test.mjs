import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// index.html renders every notebook body into `#body<i>`, and ids inside a body
// (#addProse, .prosed) repeat across panels. Scope by panel, never by bare id —
// an unfocused panel has pointer-events:none but is still "visible" to Playwright.
const body = i => `#body${i}`;

// The write half of index.html's `.world.timemode …{display:none!important}` rule, as
// far as a user can reach it in the past. `.fnpal` is left out on purpose: it only
// exists after a click on ƒ, and ƒ (.mkcell) is hidden by this very rule.
const NOTEBOOK_WRITES = ['.actions', '.addrow', 'select[data-remold]', '.mkcell'];

// dom AND visible: a selector that matches nothing is not "hidden", it is absent, and
// an assertion about an absent element holds however the stylesheet is butchered.
const affordances = async (page, sels) => {
  const out = {};
  for (const s of sels) out[s] = { dom: await page.locator(s).count(), vis: await page.locator(`${s}:visible`).count() };
  return out;
};

test('boots and renders the seeded world', async () => {
  await withPage(browser, 'boots-and-renders', async (page) => {
    await bootedShell(page, server.url);
    const api = await (await fetch(`${server.url}/api/state`)).json();
    const strip = await page.textContent('#ambient');
    assert.match(strip, new RegExp(`${api.spaces.length} notebooks`), `topbar: ${strip}`);
    assert.match(strip, new RegExp(`${api.events} events`), `topbar: ${strip}`);
    assert.equal(await page.locator('.panel').count(), api.spaces.length);
  });
});

test('a panel click enters focus and the crumb returns to overview', async () => {
  await withPage(browser, 'focus-and-overview', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await page.waitForFunction(() => document.getElementById('world').classList.contains('overview'));

    const title = (await page.locator('.panel .p-title').first().textContent()).trim();
    await page.locator('.panel').first().click();
    await page.waitForFunction(() => typeof mode !== 'undefined' && mode === 'focus');
    const crumb = await page.textContent('#crumb');
    assert.ok(crumb.includes(title), `crumb ${JSON.stringify(crumb)} does not name ${JSON.stringify(title)}`);

    await page.locator('#crumb').click();
    await page.waitForFunction(() => typeof mode !== 'undefined' && mode === 'overview');
    assert.match(await page.textContent('#crumb'), /all notebooks/);
  });
});

test('LEAP finds seeded content and a hit opens the object', async () => {
  await withPage(browser, 'leap-finds-and-opens', async (page) => {
    await bootedShell(page, server.url);
    await page.locator('#q').click();                       // focus opens the palette
    await page.locator('#q').pressSequentially('planets', { delay: 30 });
    // every keystroke fires an async /api/leap; wait for the one that carries the hit
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res')]
      .some(r => r.textContent.includes('tbl:planets')));

    const hits = await page.locator('#results .res').allTextContents();
    assert.ok(hits.some(h => /planet/i.test(h)), `no planet hit in: ${JSON.stringify(hits)}`);

    const objHit = page.locator('#results .res').filter({ hasText: 'tbl:planets' }).first();
    await objHit.click();
    await page.waitForFunction(() => typeof openId !== 'undefined' && openId === 'tbl:planets');
    await page.waitForSelector('.molder-h');
  });
});

test('view this as re-molds a table into a chart', async () => {
  await withPage(browser, 'molding-a-table', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');

    const views = await page.locator('#moldsel option').allTextContents();
    assert.ok(views.length > 1, `expected several viewers, got ${JSON.stringify(views)}`);

    const chart = views.find(v => /bar|chart|over time/i.test(v));
    assert.ok(chart, `no chart-ish viewer among ${JSON.stringify(views)}`);
    await page.selectOption('#moldsel', { label: chart });
    await page.waitForSelector('#moldbody svg', { timeout: 10_000 });
  });
});

test('time mode drops the event count, hides writes, and ↩ now restores', async () => {
  await withPage(browser, 'time-travel', async (page) => {
    await bootedShell(page, server.url);
    const before = await page.textContent('#ambient');
    const eventCount = async () => Number((await page.textContent('#ambient')).match(/(\d+) events/)[1]);
    const now = await eventCount();
    // the slider repaints the strip on every step; wait for the repaint, not a timeout
    const scrub = async v => {
      const was = await page.textContent('#ambient');
      await page.evaluate(x => {
        const r = document.getElementById('timeRange');
        r.value = x; r.dispatchEvent(new Event('input', { bubbles: true }));
      }, String(v));
      await page.waitForFunction(w => document.getElementById('ambient').textContent !== w, was);
    };

    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME !== null);
    const max = Number(await page.getAttribute('#timeRange', 'max'));
    assert.ok(max > 1, `time slider has no history: max=${max}`);

    await scrub(Math.floor(max * 0.5));
    const halfway = await eventCount();
    assert.ok(halfway < now, `the past still shows all ${now} events`);
    assert.ok(await page.evaluate(() => document.getElementById('world').classList.contains('timemode')));
    // undo is the one write control that survives overview mode — it must read as read-only
    assert.ok(await page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim')),
      'undo still looks writable in the past');

    // Write affordances live inside a *focused* notebook — the overview renders a bare
    // title list — and half way through this fixture's history the seed has not created
    // a notebook at all. So enter one, from the newest past state: still the past (one
    // event short of now), and furnished. Clicking a panel in time mode calls enter(),
    // so this is a place a real user lands, not a contrivance.
    await scrub(max - 1);
    assert.ok(await eventCount() < now, 'one event before the present is not the past');
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing one event before the present');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`${body(i)} .actions`, { state: 'attached', timeout: 20_000 });

    const past = await affordances(page, NOTEBOOK_WRITES);
    for (const [sel, c] of Object.entries(past)) {
      assert.ok(c.dom > 0, `${sel} is absent from the notebook, not hidden — this check cannot fail`);
      assert.equal(c.vis, 0, `${sel} is a write affordance and it is visible in the past: ${JSON.stringify(past)}`);
    }
    // the per-cell ↑↓✎✕ bar only appears on hover, so hover it — otherwise "hidden"
    // means nothing and .cellbar in that CSS rule is untested
    await page.locator(`${body(i)} .cellwrap`).first().hover();
    assert.ok(await page.locator(`${body(i)} .cellbar`).count() > 0, 'no .cellbar in the notebook');
    assert.equal(await page.locator(`${body(i)} .cellbar:visible`).count(), 0, '✎/✕ cell bar reachable in the past');

    // the other two live on an opened object: ✦ make… (table-shaped) and Edit (text)
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    assert.ok(await page.locator('#makebtn').count() > 0, '#makebtn absent, not hidden');
    assert.equal(await page.locator('#makebtn:visible').count(), 0, '✦ make… is clickable in the past');
    assert.equal(await page.locator('#moldsel:visible').count(), 1,
      'the past went read-only *and* unreadable — "view this as" is a read, it must survive');
    await page.evaluate(() => openObject('doc:cosmos'));
    await page.waitForSelector('#editbtn', { state: 'attached' });
    assert.equal(await page.locator('#editbtn:visible').count(), 0, 'Edit is clickable in the past');

    // back to the notebook before leaving the past, so ↩ now returns to a body that
    // renders itself (rebuild() drops the opened object's payload)
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`${body(i)} .actions`, { state: 'attached', timeout: 20_000 });

    await page.click('#timeNow');
    await page.waitForFunction(e => document.getElementById('ambient').textContent === e, before);
    assert.ok(await page.evaluate(() => typeof TIME !== 'undefined' && TIME === null));
    assert.equal(await page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim')), false);

    // …and the control the old one-sided assertion lacked: in the present the very same
    // affordances are on screen. Without this, deleting the CSS rule leaves the suite green.
    // ↩ now re-applies the present state, so re-read the panel index rather than assume it.
    const j = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    await page.waitForSelector(`${body(j)} .actions`, { state: 'visible', timeout: 20_000 });
    const restored = await affordances(page, NOTEBOOK_WRITES);
    for (const [sel, c] of Object.entries(restored)) {
      assert.ok(c.vis > 0, `${sel} never comes back in the present: ${JSON.stringify(restored)}`);
    }
    await page.locator(`${body(j)} .cellwrap`).first().hover();
    await page.locator(`${body(j)} .cellbar`).first().waitFor({ state: 'visible', timeout: 5_000 });
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#makebtn', { state: 'visible' });
    await page.evaluate(() => openObject('doc:cosmos'));
    await page.waitForSelector('#editbtn', { state: 'visible' });
  });
});

test('＋ prose adds a cell and ↺ undo reverts it', async () => {
  await withPage(browser, 'write-then-undo', async (page) => {
    await bootedShell(page, server.url);
    const events = () => page.evaluate(() => STATE.events);
    const before = await events();

    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.click(`${body(i)} #addProse`);
    // editProse swaps the add-row for a textarea plus an explicit Save button
    await page.fill(`${body(i)} .prosed`, 'a note written by the browser suite');
    await page.click(`${body(i)} [data-a="save"]`);
    await page.waitForFunction(b => STATE.events === b + 1, before, { timeout: 15_000 });

    await page.click('#undoBtn');
    await page.waitForFunction(b => STATE.events === b, before, { timeout: 15_000 });
  });
});
