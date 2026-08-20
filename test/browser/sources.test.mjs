// A passthrough source has three faces, and the shell drew two of them as the
// italic word "empty" — indistinguishable from a table somebody emptied on
// purpose, and from each other. reclaimed.test.mjs pins the same defect for a
// table whose rows the store reclaimed; this is the other half of it, and the
// halves differ in the thing that matters: a reclaimed table is PERMANENT, a
// broken source is FIXABLE. So these blocks end in a button.
//
// The server ships the state structurally (`source-block` in server.clj) rather
// than leaving the shell to read it out of `unusable`'s English. What this file
// pins is that the shell branches on that state and offers the repair.
//
// Its own server and its own substrate — see SOURCES_FIXTURE in harness.mjs.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, SOURCES_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: SOURCES_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

// By title and not by a hard-coded `tbl:live_source`: `mint-id` slugs the title
// and suffixes on collision, so the id is derived and the title is the fixture's
// own word. A miss throws here, where it reads as "the fixture did not land",
// rather than as a selector timeout thirty seconds later.
const open = async (page, title) => {
  await bootedShell(page, server.url);
  const id = await page.evaluate(t => {
    const o = Object.values(STATE.objects).find(x => x.title === t);
    if (!o) throw new Error('no object titled ' + t + ' — SOURCES_FIXTURE did not land');
    return o.id;
  }, title);
  await page.evaluate(i => openObject(i), id);
  // the molder BODY, not `.srcblock` — the body renders whatever the page chose
  // to draw, so a page that went back to drawing an empty table fails on what it
  // drew instead of on a selector timeout
  await page.waitForSelector('.molder .molder-b');
  return id;
};

test('a healthy passthrough table says it reads live', async () => {
  await withPage(browser, 'sources-live', async (page) => {
    const id = await open(page, 'Live source');

    // the payload first, so a failure below separates "the server stopped saying
    // it" from "the page stopped drawing it"
    const p = await page.evaluate(i => API.object(i), id);
    assert.equal(p.source && p.source.state, 'live', 'the payload no longer says the source is live');
    assert.equal(p.source.mode, 'passthrough');

    assert.equal(await page.locator('.molder-b .livebadge').count(), 1,
      'a live passthrough table draws no badge — the reader cannot tell it from a copy');
    const badge = (await page.textContent('.molder-b .livebadge')).trim();
    assert.match(badge, /live/i, `the badge does not say the source is live: ${badge}`);
    assert.match(badge, /live\.csv/, `the badge does not say which file it reads: ${badge}`);

    // beside the rows, never instead of them: passthrough says how a table is
    // STORED, never whether it is well
    const body = (await page.textContent('.molder-b')).trim();
    assert.equal(await page.locator('.molder-b table.mold').count(), 1,
      'the badge replaced the table instead of sitting above it');
    assert.match(body, /EMEA/, 'and still shows its rows');
    assert.match(body, /APAC/);
  });
});

test('a source whose file is gone says so and offers a reconnect', async () => {
  await withPage(browser, 'sources-gone', async (page) => {
    await open(page, 'Gone source');
    const body = (await page.textContent('.molder-b')).trim();

    assert.match(body, /unreachable/i,
      `the page does not say the source is unreachable — it drew: ${JSON.stringify(body)}`);
    assert.doesNotMatch(body, /^\s*empty\s*$/i, 'still drawing the italic word "empty"');
    assert.equal(await page.locator('.molder-b table.mold').count(), 0,
      'a table with no rows in it is the answer this page exists not to give');
    assert.match(body, /gone\.csv/, `it does not say which file is missing: ${body}`);
    assert.match(body, /revenue/, 'it does not say what the table was connected as');

    // the repair. A reclaimed table is permanent and ends in a sentence; this
    // one is fixable, which is the whole reason it is not that block.
    assert.equal(await page.locator('.molder-b [data-a="reconnect"]').count(), 1,
      'no repair is offered for a state a person can actually repair');
  });
});

test('a drifted source names both column sets and offers a refresh', async () => {
  await withPage(browser, 'sources-drift', async (page) => {
    await open(page, 'Drifted source');
    // `.srcnow` arrives on a second request — /api/source/describe, a real dry
    // run, answering with the file's shape as it is NOW. 8s and not the 30s
    // default so a sabotage of this block fails fast.
    await page.waitForSelector('.molder-b .srcnow', { timeout: 8000 });

    const body = (await page.textContent('.molder-b')).trim();
    assert.match(body, /columns changed/i,
      `the page does not say the columns changed — it drew: ${JSON.stringify(body)}`);
    assert.equal(await page.locator('.molder-b table.mold').count(), 0);

    // Both column sets, each scoped to its own element — asserting them over the
    // whole body would pass on `unusable`'s sentence alone, which names both and
    // is prose. Reading data back out of prose is the thing this design refuses
    // to do; the chips below come from /api/source/describe.
    const was = (await page.textContent('.molder-b .srcwas')).trim();
    assert.match(was, /revenue/, `the columns it was connected as are not drawn: ${was}`);
    const now = (await page.textContent('.molder-b .srcnow')).trim();
    assert.match(now, /takings/, `the columns the file has NOW are not drawn: ${now}`);
    assert.match(now, /area/);
    assert.doesNotMatch(now, /revenue/,
      `the "now" columns are the ones it was connected as, not the file's: ${now}`);

    assert.equal(await page.locator('.molder-b [data-a="refresh"]').count(), 1,
      'refresh — adopt the new columns — is the repair a drift wants, and it is not offered');
  });
});
