// The ClojureScript grid, measured in a real browser. Every assertion here reads
// a measured position or size off the DOM. Asserting that `position:sticky` is
// in the stylesheet passes while the header scrolls away; asserting a column
// "is readable" passes at 0.7 characters.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, GRID_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: GRID_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

// **`.molder`, not `.grid`.** With `?cljs` on, EVERY table the shell draws is a
// ClojureScript grid — including the `tbl:planets` cell inside the seeded
// `space:cosmos` notebook, which is on the page before this suite opens
// anything. A bare `.grid` selector measured that one instead: 8 columns, 566px
// wide, and four assertions about a table nobody asked for. The molder is the
// surface `openObject` draws and the one thing on the page there is exactly one
// of.
const G = '.molder .grid';

// `?cljs` is the flag, and `openObject` is the route: the shell has no hash
// router — `enter(i)` and `openObject(id)` are how every other suite here
// navigates.
const openGrid = async (page) => {
  await bootedShell(page, server.url + '?cljs');
  await page.evaluate(() => openObject('tbl:grid'));
  await page.waitForSelector(`${G} table tbody tr`);
};

// A column is found by its HEADER, never by its index. `normalize-rows` rebuilds
// each row onto a 36-key hash map, so the column order is stable but arbitrary —
// measured, `id` is the twenty-sixth column and not the first.
const colIndex = (page, name) => page.evaluate(([g, n]) => {
  const heads = [...document.querySelectorAll(`${g} thead th`)];
  return heads.findIndex(h => h.textContent.trim() === n);
}, [G, name]);

const cellText = (page, name, row = 0) => page.evaluate(([g, n, r]) => {
  const heads = [...document.querySelectorAll(`${g} thead th`)];
  const i = heads.findIndex(h => h.textContent.trim() === n);
  return document.querySelectorAll(`${g} tbody tr`)[r].children[i].textContent.trim();
}, [G, name, row]);

test('a 36-column table is wider than its panel and has somewhere to scroll', async () => {
  await withPage(browser, 'cljs-grid-scrolls', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(g => {
      const box = document.querySelector(`${g} [data-grid-scroll]`);
      return { table: box.querySelector('table').getBoundingClientRect().width,
               box: box.clientWidth, scrollWidth: box.scrollWidth,
               cols: document.querySelectorAll(`${g} thead th`).length };
    }, G);
    assert.equal(m.cols, 36, 'the fixture stopped being 36 columns wide');
    assert.ok(m.table > m.box * 1.5,
      `36 columns should overflow; table ${m.table}px in a ${m.box}px box`);
    assert.ok(m.scrollWidth > m.box + 50,
      `${m.scrollWidth - m.box}px of scroll is rounding, not somewhere to go`);
    console.log(`  MEASURED: table ${Math.round(m.table)}px in a ${m.box}px box, ` +
                `scrollWidth ${m.scrollWidth}px`);
  });
});

test('a narrow column shows its whole value', async () => {
  await withPage(browser, 'cljs-grid-widths', async (page) => {
    await openGrid(page);
    const t = await cellText(page, 'grp');
    assert.equal(t, 'g0', `grp rendered as ${JSON.stringify(t)}`);
    // **`textContent` is not evidence of this and the plan's version stopped
    // there.** `text-overflow:ellipsis` clips what is PAINTED and leaves the
    // node's text alone, so the assertion above reads "g0" at 23.7px per column
    // just as happily as at 40 — measured, it passed under the `width:100%`
    // sabotage it was supposed to catch. What "shows its whole value" means is
    // that the cell does not overflow itself, which is a width against a width.
    const m = await page.evaluate(([g, n]) => {
      const heads = [...document.querySelectorAll(`${g} thead th`)];
      const i = heads.findIndex(h => h.textContent.trim() === n);
      const td = document.querySelectorAll(`${g} tbody tr`)[0].children[i];
      return { client: td.clientWidth, scroll: td.scrollWidth };
    }, [G, 'grp']);
    assert.ok(m.scroll <= m.client + 1,
      `grp is clipped: ${m.scroll}px of content in a ${m.client}px cell`);
  });
});

// **Measured against the scroll box, not against the viewport.** `openObject`
// calls `layout()`, and `.panel` carries `transition:transform .5s` — so for
// half a second after the grid appears the whole molder is still sliding, and a
// viewport-absolute `left` drifts ~24px between two frames whatever the header
// does. The question these two ask is "did it stay at the scrollport's edge",
// and that is the difference of the two rects, which the animation cancels out
// of. It still measures: unstick the column and this reads -600.

test('the header stays put when the rows scroll', async () => {
  await withPage(browser, 'cljs-grid-sticky', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(async g => {
      const box = document.querySelector(`${g} [data-grid-scroll]`);
      // **The SECOND header cell, and that is the whole point of this test
      // biting.** `.grid th:first-child` is sticky for the FROZEN COLUMN's
      // sake, with `left:0`; the `.grid thead th` rule adds `top:0` to it. So
      // the first `th` is stuck vertically whether or not the header rule says
      // `position:sticky` — measured 2026-08-26 by deleting exactly that word
      // from the header rule and watching this test stay green. Every other
      // `th` is sticky only because the header rule says so.
      const th = document.querySelectorAll(`${g} thead th`)[1];
      const top = () => th.getBoundingClientRect().top - box.getBoundingClientRect().top;
      const before = top();
      box.scrollTop = 300;
      await new Promise(r => requestAnimationFrame(r));
      return { move: Math.abs(top() - before), scrolled: box.scrollTop };
    }, G);
    assert.equal(m.scrolled, 300, 'the rows did not scroll, so nothing was asked of the header');
    assert.ok(m.move < 2, `the header moved ${m.move}px`);
  });
});

test('the first column stays put when the table scrolls', async () => {
  await withPage(browser, 'cljs-grid-frozen', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(async g => {
      const box = document.querySelector(`${g} [data-grid-scroll]`);
      const td = document.querySelector(`${g} tbody td`);
      const left = () => td.getBoundingClientRect().left - box.getBoundingClientRect().left;
      const before = left();
      box.scrollLeft = 600;
      await new Promise(r => requestAnimationFrame(r));
      const r2 = td.getBoundingClientRect();
      return { move: Math.abs(left() - before), scrolled: box.scrollLeft,
               onTop: td.contains(document.elementFromPoint(r2.left + 4, r2.top + 4)) };
    }, G);
    assert.equal(m.scrolled, 600, 'the table did not scroll, so nothing was asked of the column');
    assert.ok(m.move < 2, `the first column moved ${m.move}px`);
    // Stacking, which is a different question from opacity and is all this
    // assertion answers: no scrolled cell is in FRONT of the frozen one. Whether
    // one shows THROUGH it is the test below, and `elementFromPoint` cannot tell
    // you — it hit-tests, and a transparent element wins a hit test exactly as an
    // opaque one does.
    assert.ok(m.onTop, 'something else is in front of the frozen column');
  });
});

// **The one assertion here that could not be made by measuring geometry.** A
// frozen column with no `background` is transparent, so the cells scrolling
// underneath are painted straight through it — and nothing in the layout tree
// records that: the rects are identical, the stacking order is identical, and
// `elementFromPoint` returns the frozen cell either way. The plan named a
// screenshot comparison as the honest instrument and it is; this is that.
//
// Two pictures of the SAME viewport rectangle — the frozen column's own strip —
// one at `scrollLeft: 0` where nothing is under it and one at 600 where five
// columns are. Opaque, the column paints itself both times and the pixels are
// identical. Transparent, the second picture carries other columns' text.
//
// Decoded through a canvas in the page, because Node has no PNG decoder and a
// byte comparison of two PNGs answers "different" without saying how much.
// Measured on this fixture: **0 of 14,896 pixels differ with the background,
// 807 without it** — so the threshold below is not a tolerance for flake, it is
// the middle of a gap of nearly three orders. Unsabotaged it has read 0 on every
// run.
const pixelDiff = (page, a, b) => page.evaluate(async ([pa, pb]) => {
  const load = async (b64) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d');
    ctx.drawImage(img, 0, 0);
    return { d: ctx.getImageData(0, 0, img.width, img.height).data, w: img.width, h: img.height };
  };
  const A = await load(pa), B = await load(pb);
  if (A.w !== B.w || A.h !== B.h) return { differing: -1, total: -1, w: A.w, h: A.h };
  let differing = 0;
  for (let i = 0; i < A.d.length; i += 4) {
    // 8 per channel: JPEG-free PNG is exact, so this is headroom against
    // subpixel antialiasing on a border, not against a repaint.
    if (Math.abs(A.d[i] - B.d[i]) > 8 || Math.abs(A.d[i + 1] - B.d[i + 1]) > 8 ||
        Math.abs(A.d[i + 2] - B.d[i + 2]) > 8) differing++;
  }
  return { differing, total: A.d.length / 4, w: A.w, h: A.h };
}, [a.toString('base64'), b.toString('base64')]);

// `.panel` carries `transition:transform .5s` and `openObject` calls `layout()`,
// so for half a second the molder is still sliding. Two screenshots across that
// are pictures of two different places, and every pixel differs. Wait for the
// rect to stop moving — six identical frames, ~100 ms — before either is taken.
const settled = (page) => page.evaluate(async () => {
  // And wait for the webfonts. The stylesheet pulls IBM Plex from Google with
  // `display=swap`, so a face arriving between the two screenshots repaints
  // every glyph in the strip — a difference about font loading, reported as a
  // transparent column. Measured, the table's own width varies 1,697–1,738px
  // across runs for exactly this reason.
  try { await document.fonts.ready; } catch {}
  return new Promise(done => {
    let last = '', same = 0;
    const tick = () => {
      const r = document.querySelector('.molder').getBoundingClientRect();
      const k = `${r.left},${r.top},${r.width},${r.height}`;
      if (k === last) { if (++same === 6) return done(k); } else { same = 0; last = k; }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
});

test('the frozen column paints over the rows that scroll under it', async () => {
  await withPage(browser, 'cljs-grid-opaque', async (page) => {
    await openGrid(page);
    await settled(page);
    const clip = await page.evaluate(g => {
      // `clip` is in viewport coordinates and `getBoundingClientRect` is too, so
      // the two agree only while the page itself has not scrolled. Assert that
      // rather than compensate for it — a compensated-but-wrong rectangle would
      // photograph some other part of the page, where nothing ever changes, and
      // pass forever.
      const se = document.scrollingElement;
      if (se.scrollTop || se.scrollLeft) throw new Error('the page itself is scrolled');
      const r = document.querySelector(`${g} tbody td`).getBoundingClientRect();
      // The whole visible COLUMN, not one cell. Measured both ways: one 38×24
      // cell gave 18 differing pixels under the sabotage against 0 without it,
      // and 18 is too near a threshold to be a comfortable instrument — a 38px
      // window catches whatever fragment of one glyph happens to land in it. The
      // strip is every row at once, so what shows through is ~16 rows of other
      // columns' text.
      const box = document.querySelector(`${g} [data-grid-scroll]`);
      const b = box.getBoundingClientRect();
      // `clientHeight`, so the bottom edge stops above the horizontal scrollbar —
      // which the scroll itself moves, and which is not the column.
      const bottom = b.top + box.clientHeight;
      // Inset 2px: the cells' borders and the 1px box-shadow on the column's
      // right edge sit on the boundary, and a half-covered border pixel is a
      // difference that says nothing about what is behind the column.
      return { x: Math.ceil(r.left) + 2, y: Math.ceil(r.top) + 2,
               width: Math.floor(r.width) - 4,
               height: Math.floor(bottom - r.top) - 4 };
    }, G);
    const before = await page.screenshot({ clip });
    const scrolled = await page.evaluate(async g => {
      const box = document.querySelector(`${g} [data-grid-scroll]`);
      box.scrollLeft = 600;
      await new Promise(r => requestAnimationFrame(r));
      return box.scrollLeft;
    }, G);
    assert.equal(scrolled, 600, 'nothing scrolled under the column, so nothing was asked of it');
    const after = await page.screenshot({ clip });
    const d = await pixelDiff(page, before, after);
    console.log(`  MEASURED: ${d.differing} of ${d.total} pixels differ ` +
                `in the frozen column's ${d.w}×${d.h} strip after scrolling 600px`);
    assert.ok(d.total > 500, `only ${d.total} pixels photographed — that is not a column`);
    assert.ok(d.differing >= 0 && d.differing < 20,
      `${d.differing} of ${d.total} pixels in the frozen column changed when the ` +
      `table scrolled under it — the rows are showing through`);
  });
});

test('a long value is clipped in the tooltip', async () => {
  await withPage(browser, 'cljs-grid-tooltip', async (page) => {
    await openGrid(page);
    const i = await colIndex(page, 'note');
    assert.ok(i >= 0, 'the note column is missing from the fixture');
    const len = await page.evaluate(([g, n]) => document.querySelectorAll(`${g} tbody tr`)[0]
      .children[n].getAttribute('title').length, [G, i]);
    assert.ok(len <= 520, `a 900-character value produced a ${len}-character tooltip`);
  });
});
