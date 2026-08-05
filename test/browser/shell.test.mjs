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

    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME !== null);
    const max = Number(await page.getAttribute('#timeRange', 'max'));
    assert.ok(max > 1, `time slider has no history: max=${max}`);
    await page.evaluate(v => {
      const r = document.getElementById('timeRange');
      r.value = v; r.dispatchEvent(new Event('input', { bubbles: true }));
    }, String(Math.floor(max * 0.5)));

    await page.waitForFunction(e => document.getElementById('ambient').textContent !== e, before);
    assert.equal(await page.locator('.actions:visible').count(), 0, 'write affordances visible in the past');
    assert.ok(await page.evaluate(() => document.getElementById('world').classList.contains('timemode')));
    // undo is the one write control that survives overview mode — it must read as read-only
    assert.ok(await page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim')),
      'undo still looks writable in the past');

    await page.click('#timeNow');
    await page.waitForFunction(e => document.getElementById('ambient').textContent === e, before);
    assert.ok(await page.evaluate(() => typeof TIME !== 'undefined' && TIME === null));
    assert.equal(await page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim')), false);
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
