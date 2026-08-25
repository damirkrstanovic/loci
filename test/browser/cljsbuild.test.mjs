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

// Both halves of the flag, because the flag is renamed in this task and nothing
// else would notice a typo in one of its three sites. `?cljs` present: the seam
// is in the DOM and the module that fills it has loaded. Absent: see below.
test('the compiled module is served, and ?cljs puts its seam in the DOM', async () => {
  await withPage(browser, 'cljs-loads', async (page) => {
    await bootedShell(page, server.url + '?cljs');
    await page.waitForFunction(() => window.loci && typeof window.loci.grid.mount === 'function',
                               null, { timeout: 15000 });
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    assert.equal(await page.locator('.molder-b [data-cljs-grid]').count(), 1,
      'with ?cljs the table mold must be the ClojureScript seam');
  });
});

test('without ?cljs the shell renders its own table and mounts nothing', async () => {
  await withPage(browser, 'cljs-off', async (page) => {
    await bootedShell(page, server.url);
    // A table has to be ON SCREEN for this to mean anything: counting zero seams
    // on a page with no table is an assertion that cannot fail. `tbl:planets` is
    // in the deterministic seed and several other files open it the same way.
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');
    const m = await page.evaluate(() => ({
      seams: document.querySelectorAll('[data-cljs-grid]').length,
      tables: document.querySelectorAll('.molder-b table.mold').length,
    }));
    assert.equal(m.tables, 1, 'the vanilla renderer must still draw the table');
    assert.equal(m.seams, 0, 'the ClojureScript seam must be inert without the flag');
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
