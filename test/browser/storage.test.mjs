// The storage panel: what the lake costs, and what a reclaim would not touch.
//
// The one number a reader will reach for is "how much would this free", and it
// is the one number that answers almost nothing on its own — measured on the
// real store, reclaiming EVERY object's history moves the lake directory by
// 1.8%, because catalog.duckdb is 98% of it and never shrinks. A panel showing
// only reclaimable bytes makes a correct reclaim look like it did nothing, so
// the three figures are separate here and the catalog one says out loud that it
// is not going anywhere.
//
// Its own server and its own lake — see STORAGE_FIXTURE in harness.mjs. Not only
// for the reason overview.test.mjs and reclaimed.test.mjs give (it adds objects,
// and other files count the corpus) but because `/api/storage` reports what the
// WHOLE lake costs: sharing a lake directory would make every figure here
// somebody else's arithmetic.
//
// The tests below run in order and the last two MUTATE: one clicks a reclaim
// through, which deletes Parquet. Anything read-only belongs above them.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, STORAGE_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: STORAGE_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

const openPanel = async (page) => {
  await bootedShell(page, server.url);
  await page.click('[data-open-storage]');
  await page.waitForSelector('.storage-panel');
};
const RECLAIM_URL = /\/api\/storage\/reclaim$/;

test('the storage panel shows total, reclaimable and catalog separately', async () => {
  await withPage(browser, 'storage-three-figures', async (page) => {
    await openPanel(page);

    const labels = await page.$$eval('.storage-total, .storage-reclaimable, .storage-catalog',
                                     els => els.map(e => e.textContent.trim()));
    assert.equal(labels.length, 3,
      'a panel showing only reclaimable bytes makes a correct reclaim look like it did nothing — ' +
      `drew: ${JSON.stringify(labels)}`);

    // Grounded in the payload, not in each other. Three boxes drawing the same
    // number under three labels would satisfy the count above, and would be the
    // same lie in a different shape.
    const p = await page.evaluate(() => fetch('/api/storage').then(r => r.json()));
    const sum = p.tables.reduce((a, t) => a + t.reclaimable, 0);
    assert.ok(p.lake.catalog > 0, 'the payload carries no catalog size — the fixture is not what this file thinks');
    assert.ok(sum > 0, 'nothing is reclaimable in the fixture — the panel has nothing to be right about');
    assert.ok(p.lake.files > 0, 'no Parquet in the fixture at all');

    const nums = await page.$$eval('.storage-total .stnum, .storage-reclaimable .stnum, .storage-catalog .stnum',
                                   els => els.map(e => e.textContent.trim()));
    const fmt = n => page.evaluate(x => fmtBytes(x), n);
    assert.deepEqual(nums,
      [await fmt(p.lake.files + p.lake.catalog), await fmt(sum), await fmt(p.lake.catalog)],
      'the three figures are not total / reclaimable / catalog');
    assert.notEqual(nums[0], nums[1], `total and reclaimable drew the same figure: ${nums[0]}`);

    // the catalog is the bulk of it and does not move; the panel must not let
    // that pass silently, or a correct reclaim reads as a broken one
    const cat = labels[2];
    assert.match(cat, /reclaim/i, `the catalog figure says nothing about reclaiming: ${cat}`);
    assert.match(cat, /not|never|cannot|can’t|won’t/i,
      `the catalog figure does not say it is NOT reclaimable, which is the whole point: ${cat}`);
  });
});

test('an inlined table says where its bytes are, and orphans are named but not offered', async () => {
  await withPage(browser, 'storage-inlined-and-orphans', async (page) => {
    await openPanel(page);
    const p = await page.evaluate(() => fetch('/api/storage').then(r => r.json()));

    // 8 of the 31 tables on the real store are like this: DuckLake keeps a table
    // under ~10–20 rows inside catalog.duckdb, so `current` is 0 bytes. "0 B" is
    // true and reads as a table that failed to write.
    const inl = p.tables.find(t => t.id === 'tbl:inline');
    assert.ok(inl && inl['inlined?'], 'the fixture no longer produces an inlined table');
    assert.equal(inl.current, 0, 'an inlined table should own no Parquet');
    const row = page.locator('.strow', { has: page.locator('.stid', { hasText: 'tbl:inline' }) });
    const meta = (await row.locator('.stmeta').textContent()).trim();
    assert.doesNotMatch(meta, /\b0 B\b/, `the inlined row draws "0 B", which reads as broken: ${meta}`);
    assert.match(meta, /catalog/i, `the inlined row does not say where its bytes are: ${meta}`);

    // Orphans have no object to name, so they are in no row — but their bytes
    // ARE inside the total. Unnamed, they are an unexplained remainder.
    assert.ok(p.orphaned.tables > 0, 'the fixture no longer produces an orphan');
    const orph = (await page.locator('.storphan').textContent()).trim();
    assert.match(orph, new RegExp(`\\b${p.orphaned.tables}\\b`), `the orphan count is not drawn: ${orph}`);
    assert.match(orph, /lakegc\/reclaim!/, `the orphan line names no way to act on it: ${orph}`);
    // …and offers no control, because POST /api/storage/reclaim refuses a
    // request that names no object — deliberately, since a bodyless one is also
    // how you sweep every orphan by accident.
    assert.equal(await page.locator('.storphan button, .storphan [data-reclaim]').count(), 0,
      'the panel offers to reclaim orphans, which no route can do');

    // exactly one control, on the one row with something older than the window
    const controls = await page.$$eval('[data-reclaim]', els => els.map(e => e.dataset.reclaim));
    assert.deepEqual(controls, ['tbl:aged'],
      'a reclaim control on a row with nothing to reclaim teaches the reader to click through the dialog');
  });
});

test('the reclaim control asks before it fires, and posts nothing when refused', async () => {
  await withPage(browser, 'storage-reclaim-asks-first', async (page) => {
    // Stubbed, not merely counted: if the confirmation is gone, this test must
    // not be the thing that deletes the fixture's Parquet for the test below.
    let posts = 0;
    await page.route(RECLAIM_URL, route => {
      posts++;
      route.fulfill({ status: 200, contentType: 'application/json',
                      body: JSON.stringify({ object: 'tbl:aged', versions: 0, files: 0, freed: 0 }) });
    });

    const dialogs = [];
    // dismiss, i.e. the reader says no — playwright dismisses by default, but say
    // it, because the default is what makes a missing dialog indistinguishable
    // from a refused one
    page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });

    await openPanel(page);
    await page.click('[data-reclaim="tbl:aged"]');
    // the POST, if any, is a click away and not awaited by anything here
    await page.waitForTimeout(500);

    assert.equal(dialogs.length, 1,
      'the reclaim control fired with no confirmation — one click deletes Parquet forever');
    const said = dialogs[0];
    assert.match(said, /tbl:aged/, `the confirmation does not name the object: ${said}`);
    assert.match(said, /cannot be undone|permanent/i,
      `the confirmation does not say the act is irreversible: ${said}`);
    assert.match(said, /\d+(\.\d+)?\s?(B|KB|MB|GB)\b/,
      `the confirmation does not say how many bytes go: ${said}`);
    assert.equal(posts, 0, 'refusing the confirmation posted the reclaim anyway');
  });
});

test('confirmed, a reclaim frees the old versions and the panel says what moved', async () => {
  await withPage(browser, 'storage-reclaim-confirmed', async (page) => {
    page.on('dialog', d => d.accept());
    await openPanel(page);

    const was = await page.evaluate(() => fetch('/api/storage').then(r => r.json()));
    const wasRecl = was.tables.reduce((a, t) => a + t.reclaimable, 0);
    assert.ok(wasRecl > 0, 'nothing left to reclaim before this test runs');

    await page.click('[data-reclaim="tbl:aged"]');
    // the panel re-renders from a fresh /api/storage once the POST lands
    await page.waitForSelector('[data-reclaim="tbl:aged"]', { state: 'detached' });

    const now = await page.evaluate(() => fetch('/api/storage').then(r => r.json()));
    const aged = now.tables.find(t => t.id === 'tbl:aged');
    assert.equal(aged['reclaimable-versions'], 0, 'the old versions are still there');
    assert.ok(now.lake.files < was.lake.files,
      `Parquet was not deleted: ${was.lake.files} → ${now.lake.files}`);
    // the current rows survive — that is the promise the confirmation makes,
    // and the one thing a reclaim must never break
    assert.ok(aged.current > 0, 'the reclaim took the current version too');
    const m = await page.evaluate(() => API.object('tbl:aged'));
    assert.ok(!m.reclaimed, 'tbl:aged now reads as reclaimed — its live handle was dropped');
    assert.ok((m.rendered || []).length > 0, 'tbl:aged no longer reads back any rows');

    // and the panel's own arithmetic moved with it
    const total = (await page.textContent('.storage-total .stnum')).trim();
    assert.equal(total, await page.evaluate(x => fmtBytes(x), now.lake.files + now.lake.catalog),
      'the panel is still showing the total from before the reclaim');
  });
});
