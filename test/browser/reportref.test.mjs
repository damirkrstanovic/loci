// A report `:ref` block, drawn — Task 11.
//
// `server_test.clj` already proves the PAYLOAD carries the resolved table:
// `report-ref-block-resolves-the-referenced-table` asserts `mold-payload`
// merges `{:kind "table" :rendered [...]}` onto the `:ref` block. That test
// cannot see what the shell does with it — `renderReport`'s old
// `default: return ''` made an unrecognised block render as NOTHING, so a
// correct payload and a blank panel looked identical from the server side.
// This file asserts the pixels: the referenced table's own rows, on screen.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, REPORT_REF_FIXTURE } from './harness.mjs';

let server, browser;
// Its own server, and so its own substrate — RECLAIMED_FIXTURE's reason
// exactly: the fixture adds objects and tags.test.mjs counts the corpus.
before(async () => {
  server = await startServer({ fresh: true, extra: REPORT_REF_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

test('a report :ref block renders the referenced table, not a blank space', async () => {
  await withPage(browser, 'report-ref-renders', async (page) => {
    await bootedShell(page, server.url);

    // The payload first, so a failure below separates "the server stopped
    // resolving it" from "the page stopped drawing it".
    const p = await page.evaluate(() => API.object('report:ref'));
    assert.equal(p.kind, 'report');
    const ref = p.rendered.find(b => b.block === 'ref');
    assert.ok(ref, 'the report lost its :ref block');
    assert.equal(ref.error, undefined, `the server refused to resolve it: ${ref.error}`);
    assert.equal(ref.kind, 'table', 'the payload no longer merges the resolved object in');

    // The molder — `report:ref` opened directly, the same door
    // reclaimed.test.mjs uses for its own object.
    await page.evaluate(() => openObject('report:ref'));
    await page.waitForSelector('.molder .molder-b');
    // A live grid, mounted asynchronously — waiting on rows rather than on the
    // container catches BOTH failure modes: a block that renders nothing
    // (sabotage A, `renderReport`'s ref case removed) and a block that draws
    // a container that never fills (`data-cljs-grid` present, `.grid` never
    // mounted). Either way the timeout below is the failure, not a false pass.
    await page.waitForSelector('.molder-b .grid table tbody tr', { timeout: 10000 });
    const molder = (await page.textContent('.molder-b')).trim();
    assert.match(molder, /EMEA/, `the referenced table's rows are not on the page: ${molder}`);
    assert.match(molder, /APAC/, `the referenced table's rows are not on the page: ${molder}`);
    assert.notEqual(molder, '', 'a report with a :ref block must not render blank');

    // A notebook cell — the same mold, drawn by the other caller.
    // `space:reportref` has one cell: `{:ref "report:ref"}`.
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:reportref'));
    assert.ok(i >= 0, 'space:reportref missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .cellmold .bd .grid table tbody tr`, { timeout: 10000 });
    const cell = (await page.textContent(`#body${i} .cellmold .bd`)).trim();
    assert.match(cell, /EMEA/, `the notebook cell does not draw the ref — it drew: ${JSON.stringify(cell)}`);
    assert.match(cell, /APAC/, `the notebook cell does not draw the ref — it drew: ${JSON.stringify(cell)}`);
  });
});
