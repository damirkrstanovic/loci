// The build's own guarantees, which are easy to lose silently:
//   - the compiled module is served and loads
//   - `?cljs` absent leaves every existing path byte-identical
//   - the build installs nothing from npm
// The last one is a test rather than a comment because `datalevin` pulls
// `io.github.nextjournal/markdown`, whose jar declares npm dependencies, and a
// classpath regression would quietly reinstall Clerk's markdown-it stack.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

const REPO = dirname(dirname(dirname(fileURLToPath(import.meta.url))));
let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// **No `?cljs` on this URL, and that is the assertion.** Until Task 12 there
// were two of these — one for each half of a flag — and the second asserted
// that the shell drew its own table when the flag was absent. `CLJS_GRID` and
// `tableHTML` are both gone, so that sentence is now false about the shell
// rather than true about the flag, and this is what replaced it.
test('a molded table is the ClojureScript grid, with nothing asking for it', async () => {
  await withPage(browser, 'cljs-loads', async (page) => {
    await bootedShell(page, server.url);
    await page.waitForFunction(() => window.loci && typeof window.loci.grid.mount === 'function',
                               null, { timeout: 15000 });
    // A table has to be ON SCREEN for this to mean anything: counting a seam on
    // a page with no table would be an assertion that cannot fail. `tbl:planets`
    // is in the deterministic seed and several other files open it the same way.
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    assert.equal(await page.locator('.molder-b [data-cljs-grid]').count(), 1,
      'the table mold is not the ClojureScript seam');
    // …and something mounted INTO it. A seam is a hole until the module fills
    // it, and the count above cannot tell the two apart — which is exactly the
    // failure a broken build produces: the shell renders, and the panel is
    // empty.
    const filled = await page.waitForSelector('.molder-b [data-cljs-grid] table.mold tbody tr',
                                              { timeout: 8000 }).then(() => true, () => false);
    assert.ok(filled, 'the seam is in the DOM and nothing ever mounted into it — the module ' +
                      'did not load, or mount threw, and the panel is a hole where the table was');
  });
});

// The OTHER renderer, and the reason `staticTable` exists at all. A report block
// is prose with a table in it: its rows come down inside the payload the molder
// already has, so it draws them itself — no seam, no second fetch, no pager.
//
// Nothing else in seventeen files opens a report, so without this the deletion
// of `tableHTML` — which `renderReport` called — goes unwitnessed by the suite.
test('a table inside a report draws its own rows and asks for no grid', async () => {
  await withPage(browser, 'cljs-report', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => openObject('report:cosmos'));
    // A report has no alternatives, so no `#moldsel` to wait on. The title is
    // the sync point, and it is the right one: `renderDetail` writes the header
    // and the body in one pass, so once the title is up the table either is
    // there or is never coming.
    await page.waitForFunction(
      () => document.querySelector('.molder-h .t')?.textContent.includes('Solar System'),
      null, { timeout: 10000 });
    const m = await page.evaluate(() => ({
      seams: document.querySelectorAll('.molder-b [data-cljs-grid]').length,
      tables: document.querySelectorAll('.molder-b .stable table.mold').length,
      rows: document.querySelectorAll('.molder-b .stable table.mold tbody tr').length,
      first: document.querySelector('.molder-b .stable table.mold tbody td')?.textContent,
      // `staticTable` reads numeric from the VALUES, and it is the half of
      // `tableHTML` that had to survive: a column of figures that does not
      // right-align is not a column of figures.
      num: document.querySelectorAll('.molder-b .stable table.mold thead th.num').length,
    }));
    // The seam FIRST, and the order is the diagnosis. A report block that mounts
    // a grid draws no `.stable` table either, so leaving this last reports "the
    // report block drew no table" for a page that drew the wrong one — measured,
    // by making `renderReport` emit a seam.
    assert.equal(m.seams, 0,
      'a report block mounted a grid — a fetch, a pager and a column chooser per paragraph');
    assert.equal(m.tables, 1, 'the report block drew no table');
    assert.equal(m.rows, 8, `"The eight planets" drew ${m.rows} rows`);
    assert.equal(m.first, 'Mercury', `the first row is ${JSON.stringify(m.first)}`);
    assert.equal(m.num, 6,
      `${m.num} of the eight columns are marked numeric — planet and type are the two ` +
      'that are not, and the other six are figures');
  });
});

test('the build declares no npm dependencies', () => {
  const pkg = JSON.parse(readFileSync(join(REPO, 'package.json'), 'utf8'));
  assert.deepEqual(pkg.dependencies ?? {}, {},
    'a CLJS build pulled npm packages in — check `:replace-deps` on the :cljs alias');
  const lock = JSON.parse(readFileSync(join(REPO, 'package-lock.json'), 'utf8'));
  const leaked = Object.keys(lock.packages ?? {}).filter(k => /markdown|punycode/.test(k));
  assert.deepEqual(leaked, [], 'markdown-it residue in the lock file');
});
