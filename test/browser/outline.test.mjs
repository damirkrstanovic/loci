// The notebook outline: a rail listing the cells, so reaching the sixth one
// does not mean scrolling past five.
//
// space:cosmos in the fixture has several cells including a doc, a table and
// the flow fixture, which is what makes this testable without new seed data.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const openCosmos = async (page) => {
  await bootedShell(page, server.url);
  const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
  assert.ok(i >= 0, 'space:cosmos missing from the fixture');
  await page.evaluate(n => enter(n), i);
  await page.waitForSelector(`#body${i} .cellwrap`);
  return i;
};

test('the outline lists every cell, in order, by what it is', async () => {
  await withPage(browser, 'outline-lists-cells', async (page) => {
    const i = await openCosmos(page);
    await page.waitForSelector(`#body${i} .nbrail`);
    const [rail, cells] = await page.evaluate(n => [
      [...document.querySelectorAll(`#body${n} .nbrail button`)]
        .map(b => ({ jump: b.dataset.jump, label: b.querySelector('.nr-t').textContent })),
      [...document.querySelectorAll(`#body${n} .cellwrap`)].map(c => c.dataset.cell),
    ], i);
    assert.equal(rail.length, cells.length,
      'one rail entry per cell, or the outline is lying about the notebook');
    assert.deepEqual(rail.map(r => r.jump), cells, 'and in the notebook\'s own order');
    assert.ok(rail.every(r => r.label.trim().length),
      `every entry needs a label: ${JSON.stringify(rail)}`);
    // a prose cell is labelled by its words, not by an id
    assert.ok(rail.some(r => /[a-z]{4,}\s+[a-z]{3,}/i.test(r.label)),
      `no entry reads like prose: ${JSON.stringify(rail.map(r => r.label))}`);
  });
});

test('clicking an entry brings that cell into view', async () => {
  await withPage(browser, 'outline-jumps', async (page) => {
    const i = await openCosmos(page);
    await page.waitForSelector(`#body${i} .nbrail`);
    const last = await page.evaluate(n =>
      [...document.querySelectorAll(`#body${n} .nbrail button`)].pop().dataset.jump, i);
    await page.evaluate(({ n, j }) => {
      document.getElementById('body' + n).scrollTop = 0;
      document.querySelector(`#body${n} .nbrail button[data-jump="${j}"]`).click();
    }, { n: i, j: last });
    await page.waitForFunction(n => document.getElementById('body' + n).scrollTop > 0, i,
      { timeout: 5000 });
    // and the arrival is marked, so the jump reads as one
    const flashed = await page.evaluate(n =>
      !!document.querySelector(`#body${n} .cellwrap.flash`), i);
    assert.ok(flashed, 'the cell you landed on is not marked');
  });
});

test('the rail says which cell you are reading', async () => {
  await withPage(browser, 'outline-spy', async (page) => {
    const i = await openCosmos(page);
    await page.waitForSelector(`#body${i} .nbrail button.on`);
    const first = await page.evaluate(n =>
      document.querySelector(`#body${n} .nbrail button.on`).dataset.jump, i);
    assert.equal(first, '0', 'at the top, the first cell is the current one');

    await page.evaluate(n => {
      const b = document.getElementById('body' + n);
      b.scrollTop = b.scrollHeight;
      b.dispatchEvent(new Event('scroll'));
    }, i);
    const after = await page.evaluate(n =>
      document.querySelector(`#body${n} .nbrail button.on`).dataset.jump, i);
    assert.notEqual(after, '0', 'scrolled to the bottom, the rail still points at the top');
  });
});

test('a re-render does not stack scroll listeners', async () => {
  await withPage(browser, 'outline-no-listener-leak', async (page) => {
    const i = await openCosmos(page);
    await page.waitForSelector(`#body${i} .nbrail`);
    // renderNotebook re-runs every 2.5s while a flow is watched, and the body
    // element survives innerHTML — so `add` rather than `replace` would stack
    // one spy per re-render for as long as the notebook is open.
    const same = await page.evaluate(async n => {
      const b = document.getElementById('body' + n);
      const first = b._spy;
      await renderNotebook(n);
      await renderNotebook(n);
      return { replaced: b._spy !== first, hasOne: typeof b._spy === 'function' };
    }, i);
    assert.ok(same.hasOne, 'the spy handle is kept so it can be removed');
    assert.ok(same.replaced, 'a re-render must replace the handler, not add another');
  });
});
