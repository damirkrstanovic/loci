// Regressions for the four UI defects found by hand on 2026-08-05.
// Each test names the bug it pins; see docs/superpowers/specs/2026-08-06-browser-tests-design.md
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const emptySelects = page => page.evaluate(() =>
  [...document.querySelectorAll('select')].filter(s => s.options.length === 0)
    .map(s => s.outerHTML.slice(0, 80)));

// BUG: flows have no viewers, so the molder rendered an empty <select> — dead chrome.
// A flow is drawn twice, by two different guards: as a notebook cell (renderNotebook)
// and as an opened object (renderDetail). Both are checked here — sabotaging either
// guard alone must turn this test red.
test('a flow renders no empty "view this as" control', async () => {
  await withPage(browser, 'flow-no-empty-select', async (page) => {
    await bootedShell(page, server.url);

    // 1. as a cell inside its notebook
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .flowsteps`);
    assert.deepEqual(await emptySelects(page), [], 'an empty <select> is dead chrome');
    // …while the cells that DO have viewers still carry a populated one, so the
    // assertion above cannot pass merely because nothing rendered a <select>.
    const populated = await page.locator(`#body${i} select[data-remold] option`).count();
    assert.ok(populated > 0, 'no populated <select> in the notebook — the check above is vacuous');
    assert.equal(await page.locator(`#body${i} .flowstep`).count(), 3);
    assert.match(await page.textContent(`#body${i} .flowsteps`), /fixture failure/);

    // 2. as an opened object in the molder
    await page.evaluate(() => openObject('flow:test'));
    await page.waitForSelector('.molder .flowsteps');
    assert.deepEqual(await emptySelects(page), [], 'an empty <select> is dead chrome');
    assert.equal(await page.locator('.flowstep').count(), 3);
    assert.match(await page.textContent('.flowsteps'), /fixture failure/);
  });
});

// BUG: past ~7 notebooks the overview scaled whole panels down until titles were ~3px.
// The defect is invisible to the DOM — font-size never changed, the ZUI transform did.
// So measure the title's height ON SCREEN, and only once the .5s panel transition has
// settled: mid-transition the panel is still large and a broken layout would read fine.
test('the overview stays legible at 12 notebooks', async () => {
  await withPage(browser, 'overview-legibility', async (page) => {
    await bootedShell(page, server.url);
    const n = await page.evaluate(() => STATE.spaces.length);
    assert.ok(n >= 8, `fixture too small to trigger the bug: ${n} notebooks`);

    await page.evaluate(() => overview());
    await page.waitForFunction(() => typeof mode !== 'undefined' && mode === 'overview');

    // node:test's default timeout is Infinity, so an unbounded wait in here is a wait
    // forever: cap the frames, and keep a wall clock too in case rAF stops firing at all.
    const px = await page.evaluate(() => new Promise((res, rej) => {
      const t = document.querySelector('.panel .p-title');
      let last = -1, stable = 0, frames = 0;
      const give = why => rej(new Error(`${why} — last height ${last.toFixed(2)}px; ` +
        `the .5s panel transition settles in ~40 frames`));
      const clock = setTimeout(() => give('panel title height never settled in 15s'), 15_000);
      const tick = () => {
        const h = t.getBoundingClientRect().height;    // ON SCREEN, after the ZUI transform
        if (Math.abs(h - last) < 0.05) {
          if (++stable >= 3) { clearTimeout(clock); return res(h); }
        } else stable = 0;
        last = h;
        if (++frames > 600) { clearTimeout(clock); return give(`panel title height never settled in ${frames} frames`); }
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    }));
    const cards = await page.evaluate(() => document.getElementById('world').classList.contains('cards'));
    assert.ok(px >= 12, `notebook titles render at ${px.toFixed(1)}px — unreadable (world.cards=${cards})`);
    assert.ok(cards, 'the overview shed the panel bodies for cards — this is what keeps it legible');
  });
});

// BUG: ↺ undo looked live in time mode; and the dim never cleared on ↩ now.
test('undo reads read-only in time mode and recovers on ↩ now', async () => {
  await withPage(browser, 'undo-dim-in-time-mode', async (page) => {
    await bootedShell(page, server.url);
    const dim = () => page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim'));
    assert.equal(await dim(), false, 'dimmed before entering time mode');

    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME);
    assert.equal(await dim(), true, 'undo looks live while the past is read-only');

    await page.click('#timeNow');
    await page.waitForFunction(() => typeof TIME === 'undefined' || !TIME);
    assert.equal(await dim(), false, 'dim never cleared on return to the present');
  });
});

// BUG: every page load 404'd on the favicon.
// A 404 is a *completed* response, so it never reaches page.on('requestfailed'); and the
// favicon is fetched by the browser process, so it never reaches page.on('response')
// either. The console error is the one place it surfaces — and its text names no URL,
// which is why the harness records m.location().url with every console error.
// Response statuses are watched too, for a same-origin fetch that starts 404ing.
// Every one of the three is filtered to this server's origin: index.html also pulls
// IBM Plex from a CDN, and a flaky CDN — or no network at all — is not a loci
// regression. Run this offline and it must still pass.
test('a clean boot makes no failed requests and logs no errors', async () => {
  await withPage(browser, 'clean-boot', async (page, diag) => {
    const bad = [];
    const mine = u => u.startsWith(server.url);
    // a console error with no location came from the page itself, so keep it
    const mineErr = e => !e.url || mine(e.url);
    page.on('response', r => { if (r.status() >= 400 && mine(r.url())) bad.push(`${r.status()} ${r.url()}`); });

    await bootedShell(page, server.url);
    // the favicon is fetched at low priority and can land after networkidle
    await page.waitForTimeout(1000);

    const errs = diag.consoleErrors.filter(mineErr);
    assert.deepEqual(bad, [], 'error responses on boot');
    assert.deepEqual(diag.failedRequests.filter(mine), [], 'failed requests on boot');
    assert.deepEqual(errs, [], `console errors on boot: ${JSON.stringify(errs)}`);
    assert.deepEqual(diag.pageErrors, [], 'page errors on boot');
  });
});
