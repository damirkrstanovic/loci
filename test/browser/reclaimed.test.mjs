// A table whose rows the store reclaimed, drawn.
//
// The server-side test (`mold-payload-says-a-table-was-reclaimed`) asserts the
// payload and that it serializes — which is structurally everything it can see,
// and is exactly why this defect survived it. The payload was right; the page
// read none of it. `renderMold` switched on `m.kind` → "table" → `tableHTML([])`
// → the italic word `empty`, which is what a table somebody emptied ON PURPOSE
// looks like. Same pixels for "you deleted the rows" and "the rows are gone".
//
// So this file asserts pixels-side: the word reclaimed, the date, the row count
// and the column names, on both surfaces that draw a mold.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, RECLAIMED_FIXTURE } from './harness.mjs';

let server, browser;
// Its own server, and so its own substrate — overview.test.mjs's reason exactly.
// The fixture adds one notebook and one object, and tags.test.mjs counts the
// corpus: measured on the shared server, its "2/14 split" read 15 and its
// "1 of 16" crumb read 17. A second cold JVM is cheaper than either shifting
// another file's numbers or making the suite order-dependent.
before(async () => {
  server = await startServer({ fresh: true, extra: RECLAIMED_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

test('a reclaimed table says so rather than drawing an empty table', async () => {
  await withPage(browser, 'reclaimed-molder', async (page) => {
    await bootedShell(page, server.url);

    // The payload first, so a failure below separates "the server stopped
    // saying it" from "the page stopped drawing it".
    const p = await page.evaluate(() => API.object('tbl:reclaimed'));
    assert.ok(p.reclaimed, 'the payload no longer carries `reclaimed`');
    assert.equal(p.total, 100, 'the payload no longer carries the row count');
    assert.deepEqual(p.cols, ['region', 'amount'], 'the payload no longer carries the columns');

    // 1. the molder. Wait for the molder BODY, not for `.reclaimed` — the body
    // is what renders either way, so a page that went back to drawing the table
    // fails on what it drew instead of on a 30s selector timeout.
    await page.evaluate(() => openObject('tbl:reclaimed'));
    await page.waitForSelector('.molder .molder-b');
    const molder = (await page.textContent('.molder-b')).trim();
    assert.match(molder, /reclaimed/i,
      `the molder does not say the rows were reclaimed — it drew: ${JSON.stringify(molder)}`);
    assert.equal(await page.locator('.molder-b .reclaimed').count(), 1,
      'no reclaimed panel in the molder');
    assert.match(molder, /100 rows/, 'it does not say what was there');
    assert.match(molder, /region/, 'it does not name the columns that were there');
    assert.doesNotMatch(molder, /^\s*empty\s*$/i, 'still drawing the italic word "empty"');
    assert.equal(await page.locator('.molder-b table.mold').count(), 0,
      'a table with no rows in it is the answer this page exists not to give');

    // The date is drawn, and drawn as a DATE — the wire carries ISO-UTC because
    // that is what an agent reads; a reader gets their own clock. Computed in
    // the page so the locale is the browser's and not node's.
    const local = await page.evaluate(iso => new Date(iso).toLocaleString(), p.reclaimed);
    assert.doesNotMatch(local, /Invalid Date/, 'the payload date does not parse in the browser');
    assert.ok(molder.includes(local),
      `the reclamation date is not on the page: ${molder}`);

    // 2. a notebook cell — the same mold, drawn by the other caller
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:reclaimed'));
    assert.ok(i >= 0, 'space:reclaimed missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .cellmold .bd`);
    const cell = (await page.textContent(`#body${i} .cellmold .bd`)).trim();
    assert.match(cell, /reclaimed/i,
      `the notebook cell does not say it — it drew: ${JSON.stringify(cell)}`);
    assert.match(cell, /100 rows/, 'the cell does not say what was there');
    assert.equal(await page.locator(`#body${i} .cellmold .bd table.mold`).count(), 0);

    // The ✦ and ƒ affordances are gone, because there is nothing to make a view
    // of — `alternatives` is empty, so `mkable` is false. Asserted rather than
    // assumed: it is the one behaviour that changes silently for a reader.
    assert.equal(await page.locator(`#body${i} .cellmold [data-make]`).count(), 0,
      'offering ✦ make… on rows that are gone');
  });
});
