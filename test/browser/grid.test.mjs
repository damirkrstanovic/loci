// The ClojureScript grid, measured in a real browser. Every assertion here reads
// a measured position or size off the DOM. Asserting that `position:sticky` is
// in the stylesheet passes while the header scrolls away; asserting a column
// "is readable" passes at 0.7 characters.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
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

// ---- the pager ----
const caption = (page) => page.textContent(`${G} .pgcap`);
const rows = (page) => page.evaluate(g => document.querySelectorAll(`${g} tbody tr`).length, G);
const dead = (page, what) =>
  page.evaluate(([g, w]) => document.querySelector(`${g} [data-grid-${w}]`).disabled, [G, what]);

// Click, then wait for the CAPTION to change rather than for a timeout: the
// click dispatches an action, the action refetches, and the page is drawn when
// the payload lands. Waiting on the caption waits on all three.
const step = async (page, act) => {
  const was = await caption(page);
  await act();
  await page.waitForFunction(
    ([g, w]) => document.querySelector(`${g} .pgcap`)?.textContent !== w, [G, was],
    { timeout: 10000 });
};
const clickNext = (page) => step(page, () => page.click(`${G} [data-grid-next]`));
const clickPrev = (page) => step(page, () => page.click(`${G} [data-grid-prev]`));

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

// **The decision in §3.1, measured on the rendered page.** loci's elision
// REPLACES the rows it fetched over; Clerk's, whose shape it borrows, appends.
// Task 6 guards the state transition; these guard what a person actually sees —
// a caption that counts, a Prev control an append model would have no use for,
// and a DOM that does not grow. The spike shipped the two disagreeing: an
// elision labelled `1 more` over replace behaviour.
test('the walk reaches the end and stops there', async () => {
  await withPage(browser, 'cljs-grid-walk', async (page) => {
    await openGrid(page);
    assert.equal(await caption(page), 'showing 1–100 of 250');
    assert.equal(await rows(page), 100);
    assert.equal(await dead(page, 'prev'), true, 'nothing precedes page one');
    assert.equal(await dead(page, 'next'), false, 'there are 150 rows still to come');

    await clickNext(page);
    assert.equal(await caption(page), 'showing 101–200 of 250');

    await clickNext(page);
    // 250 rows at 100 a page is THREE pages, so the walk ends after two Nexts —
    // the plan said three. The last page is the short one, and it is the only
    // page on which the server sends no elision at all: no cursor, and no offset
    // either, so this caption is also what proves the shell remembered where the
    // page it asked for begins.
    assert.equal(await caption(page), 'showing 201–250 of 250');
    assert.equal(await rows(page), 50, 'the last page of 250 at 100 a page is 50 rows');
    assert.equal(await dead(page, 'next'), true, 'the last page omitted nothing');
    assert.equal(await dead(page, 'prev'), false);
  });
});

test('a page REPLACES the one before it — 100 rows after a Next, not 200', async () => {
  await withPage(browser, 'cljs-grid-replaces', async (page) => {
    await openGrid(page);
    assert.equal(await cellText(page, 'id'), 'row-0');
    await clickNext(page);
    const n = await rows(page);
    assert.equal(n, 100,
      `${n} rows after one Next: ${n === 200 ? 'the page was APPENDED, which is ' +
       'Clerk\'s semantics and not loci\'s — design §3.1' : 'unexpected'}`);
    assert.equal(await cellText(page, 'id'), 'row-100',
      'page two starts at row 100, so page one is gone rather than scrolled past');
  });
});

test('Prev is exact — it pops the cursor that began the page', async () => {
  await withPage(browser, 'cljs-grid-prev', async (page) => {
    await openGrid(page);
    await clickNext(page);
    await clickNext(page);
    assert.equal(await caption(page), 'showing 201–250 of 250');
    await clickPrev(page);
    assert.equal(await caption(page), 'showing 101–200 of 250');
    // The caption alone would pass on a recomputed cursor that fetched the wrong
    // slice, which is the whole reason Prev is a stack pop. The rows are what
    // say it landed where it said it did.
    assert.equal(await cellText(page, 'id'), 'row-100');
    assert.equal(await rows(page), 100);
  });
});

test('changing the page size restarts the walk', async () => {
  await withPage(browser, 'cljs-grid-size', async (page) => {
    await openGrid(page);
    // Before anything else: the select shows the size actually in force. It is
    // built with `:selected` on the options because Replicant sets a node's
    // attributes before appending its children, so a `:value` on the select
    // itself would be assigned to a select with no options and vanish.
    assert.equal(await page.inputValue(`${G} [data-grid-size]`), '100');
    await clickNext(page);
    assert.equal(await caption(page), 'showing 101–200 of 250');
    await step(page, () => page.selectOption(`${G} [data-grid-size]`, '25'));
    // A cursor names a position in a walk of a given size, so every cursor in
    // the stack is void. Page one, and Prev dead.
    assert.equal(await caption(page), 'showing 1–25 of 250');
    assert.equal(await rows(page), 25);
    assert.equal(await dead(page, 'prev'), true, 'the walk restarted, so there is no back');
    assert.equal(await cellText(page, 'id'), 'row-0');
  });
});

// No browser, no server: a question about the source, asked of the source.
//
// **The cursor is opaque, and that is what lets one pager serve three storage
// strategies.** A lake table pages by keyset (`{"k": <rowid>}`), a source by
// offset (`{"o": <n>, "s": <sort>}`), and the shell must not be able to tell —
// the moment it reads a cursor's contents, changing how the store pages becomes
// a change to the shell. Base64 makes peeking easy enough to do by accident,
// which is why this is a test rather than a comment.
test('nothing in src-cljs/ can read a cursor', () => {
  const dir = join(dirname(dirname(dirname(fileURLToPath(import.meta.url)))), 'src-cljs');
  const files = [];
  const walk = (d) => readdirSync(d, { withFileTypes: true }).forEach(e =>
    e.isDirectory() ? walk(join(d, e.name)) : files.push(join(d, e.name)));
  walk(dir);
  assert.ok(files.length >= 2, `only ${files.length} files under src-cljs/ — the scan found nothing to scan`);
  // `atob`/`js/atob` decode it, `JSON.parse` reads what came out, and `"k"` or
  // `"o"` is what someone who decoded it would go looking for. `:keys` is not a
  // false positive here: the quotes are required.
  const banned = [/\batob\b/, /JSON\.parse/, /js\/JSON/, /"k"/, /"o"/, /\.-k\b/, /\.-o\b/];
  for (const f of files) {
    const src = readFileSync(f, 'utf8');
    for (const re of banned) {
      assert.ok(!re.test(src), `${f} matches ${re} — the shell is reading inside a cursor`);
    }
  }
  console.log(`  MEASURED: ${files.length} files under src-cljs/, none of them peek`);
});

// ---- the sort control ----
//
// The server does the sorting: the window carries `[col dir]`, `sort-spec`
// validates it against the handle and `ORDER BY … NULLS LAST, rowid ASC` builds
// the order. So what these two measure is the CONTROL — that a click reaches the
// request, and that the third click asks for no sort at all rather than for
// ascending again.

// Every cell of one column, top to bottom. A single cell is not enough to tell
// a sorted page from an unsorted one here: natural order's first row and `grp`
// ascending's first row are BOTH `g0`/`row-0`, because the ascending tiebreak is
// `rowid ASC`. What separates them is the whole column — natural order cycles
// g0…g4 down the page, ascending holds one value for fifty rows.
const colValues = (page, name) => page.evaluate(([g, n]) => {
  const heads = [...document.querySelectorAll(`${g} thead th`)];
  const i = heads.findIndex(h => h.textContent.trim() === n);
  return [...document.querySelectorAll(`${g} tbody tr`)].map(r => r.children[i].textContent.trim());
}, [G, name]);

const uniq = (xs) => [...new Set(xs)];

// The header's own state, read the two ways it is published: `aria-sort`, which
// is what a screen reader is told, and the `::after` the stylesheet draws off
// it, which is what an eye sees. The second is not a stylesheet assertion — it
// is the resolved value of the pseudo-element that is actually painted, so a
// deleted rule reads `none` here.
const sortState = (page, name) => page.evaluate(([g, n]) => {
  const th = [...document.querySelectorAll(`${g} thead th`)].find(h => h.textContent.trim() === n);
  return { aria: th.getAttribute('aria-sort'),
           arrow: getComputedStyle(th, '::after').content,
           // the header must keep saying which column it is: the arrow is a
           // pseudo-element precisely so that it does not join this string.
           text: th.textContent.trim() };
}, [G, name]);

// Click a header and wait for the whole round trip. **Not `step`**, which waits
// on the caption: all three sorts of page one caption `showing 1–100 of 250`, so
// the string is identical before and after and the wait would return instantly
// on a page that had not been refetched. `aria-sort` changes on every one of the
// three transitions, and it is written by the same render that writes the rows —
// `refresh!` draws once, when the payload lands — so waiting on it waits on the
// fetch as well.
//
// It waits for the state to CHANGE and then asserts what it changed to, rather
// than waiting for the wanted value. The two agree on a correct implementation
// and differ on a broken one: waiting for `null` on a third click that returned
// to ascending reports `Timeout 10000ms exceeded`, which does not say what
// happened; waiting for a change reports `expected null, actual 'ascending'`,
// which does.
const sortBy = async (page, name, want) => {
  const i = await colIndex(page, name);
  assert.ok(i >= 0, `the ${name} column is missing from the fixture`);
  const was = (await sortState(page, name)).aria;
  await page.click(`${G} thead th:nth-child(${i + 1})`);
  await page.waitForFunction(([g, n, w]) => {
    const th = [...document.querySelectorAll(`${g} thead th`)].find(h => h.textContent.trim() === n);
    return th && th.getAttribute('aria-sort') !== w;
  }, [G, name, was], { timeout: 10000 });
  assert.equal((await sortState(page, name)).aria, want,
    `clicking ${name} while it was ${JSON.stringify(was)} should leave it ${JSON.stringify(want)}`);
};

test('a header sorts ascending, then descending, then back to the table\'s own order', async () => {
  await withPage(browser, 'cljs-grid-sort', async (page) => {
    await openGrid(page);

    // Natural order, before anything is clicked: `grp` cycles g0…g4 down the
    // page, one value per row, because row i has `g(i mod 5)`.
    assert.deepEqual((await colValues(page, 'grp')).slice(0, 5), ['g0', 'g1', 'g2', 'g3', 'g4']);
    assert.equal(await cellText(page, 'id'), 'row-0');
    assert.deepEqual(await sortState(page, 'grp'),
      { aria: null, arrow: 'none', text: 'grp' }, 'nothing is sorted yet');

    await sortBy(page, 'grp', 'ascending');
    let v = await colValues(page, 'grp');
    // 250 rows over five groups is 50 each, so an ascending page of 100 is
    // exactly the g0 block then the g1 block — a shape natural order cannot
    // produce and one cell could never have shown.
    assert.deepEqual(uniq(v.slice(0, 50)), ['g0'], `first fifty were ${uniq(v.slice(0, 50))}`);
    assert.deepEqual(uniq(v.slice(50)), ['g1'], `second fifty were ${uniq(v.slice(50))}`);
    assert.equal(await cellText(page, 'id'), 'row-0', 'ties break by rowid, so g0 starts at row-0');
    const asc = await sortState(page, 'grp');
    assert.equal(asc.aria, 'ascending');
    assert.ok(asc.arrow.includes('▲'), `the header's marker is ${asc.arrow}`);
    assert.equal(asc.text, 'grp', 'the arrow is painted, not written into the header');

    await sortBy(page, 'grp', 'descending');
    v = await colValues(page, 'grp');
    assert.deepEqual(uniq(v.slice(0, 50)), ['g4']);
    assert.deepEqual(uniq(v.slice(50)), ['g3']);
    assert.equal(await cellText(page, 'id'), 'row-4', 'g4 is rows 4, 9, 14 … and the tiebreak is ascending');
    const desc = await sortState(page, 'grp');
    assert.equal(desc.aria, 'descending');
    assert.ok(desc.arrow.includes('▼'), `the header's marker is ${desc.arrow}`);

    // **The third click, which is the whole point of this test.** Natural order
    // is the only order a lake table pages by seeking rather than by counting,
    // so a two-state toggle would make the cheapest ordering the one state a
    // person can never get back to.
    await sortBy(page, 'grp', null);
    assert.deepEqual((await colValues(page, 'grp')).slice(0, 5), ['g0', 'g1', 'g2', 'g3', 'g4'],
      'the third click asked for ascending again rather than for no sort at all');
    assert.equal(await cellText(page, 'id'), 'row-0');
    assert.deepEqual(await sortState(page, 'grp'), { aria: null, arrow: 'none', text: 'grp' });
  });
});

test('sorting restarts the walk at page one', async () => {
  await withPage(browser, 'cljs-grid-sort-restarts', async (page) => {
    await openGrid(page);
    await clickNext(page);
    assert.equal(await caption(page), 'showing 101–200 of 250');

    await sortBy(page, 'grp', 'ascending');
    // **`dead(prev)` is the assertion that bites here and the caption is not.**
    // Measured 2026-08-26 by making `toggle-sort` keep both the stack and the
    // page: the server refuses a cursor minted under a different `:sort` — it
    // compares the cursor's `:s` against the request's — so it answers with the
    // first page of the new order anyway and this caption still reads
    // `showing 1–100 of 250`. What the sabotage leaves visible is a Prev control
    // that is alive on page one. The caption is kept because it fails to the
    // OTHER defect: a shell that sent the stale cursor to a store that honoured
    // it would land mid-table.
    assert.equal(await caption(page), 'showing 1–100 of 250');
    assert.equal(await dead(page, 'prev'), true, 'the walk restarted, so there is no back');
    assert.equal(await cellText(page, 'id'), 'row-0');
    assert.deepEqual(uniq((await colValues(page, 'grp')).slice(0, 50)), ['g0']);
  });
});

// ---- the column chooser ----

const headerCount = (page) =>
  page.evaluate(g => document.querySelectorAll(`${g} thead th`).length, G);

const summary = (page) => page.textContent(`${G} [data-grid-cols] > summary`);

// **Every `/api/mold` this page asked FOR THIS TABLE, in order.** The assertion
// that matters in this task is about the request and not about the render:
// filtering the columns in the view would leave the page looking exactly right
// while the server still shipped all 36 across the wire, which is the whole
// point missed. So the instrument has to be the URL.
//
// Filtered by id, because with `?cljs` on the seeded `space:cosmos` notebook
// mounts a grid of its own for `tbl:planets` and its requests interleave with
// these. `js/encodeURIComponent` writes the colon as `%3A`.
const moldRequests = (page) => {
  const seen = [];
  page.on('request', r => {
    const u = r.url();
    if (u.includes('/api/mold') && u.includes('id=tbl%3Agrid')) seen.push(u);
  });
  return seen;
};

const openChooser = async (page) => {
  if (await page.evaluate(g => document.querySelector(`${g} [data-grid-cols]`).open, G)) return;
  await page.click(`${G} [data-grid-cols] > summary`);
  await page.waitForFunction(g => document.querySelector(`${g} [data-grid-cols]`).open, G,
                             { timeout: 5000 });
};

// Tick or untick one box and wait for the refetch. The header COUNT changing is
// the whole round trip: the click dispatches, the dispatch refetches with a
// narrower `cols`, and the table is redrawn when the payload lands.
const toggleColumn = async (page, name) => {
  await openChooser(page);
  const before = await headerCount(page);
  await page.click(`${G} [data-grid-cols] label[data-grid-col="${name}"] input`);
  await page.waitForFunction(([g, n]) =>
    document.querySelectorAll(`${g} thead th`).length !== n, [G, before], { timeout: 10000 });
};

test('unticking a column narrows the REQUEST, not merely the page', async () => {
  await withPage(browser, 'cljs-grid-cols', async (page) => {
    const asked = moldRequests(page);
    await openGrid(page);

    // **Page one asks for no `cols` at all.** Server-side an absent or empty
    // `cols` means every column the handle has, so an explicit full list would
    // pin the request to the 36 this build knew about and silently drop a 37th
    // added to the table later.
    const first = asked.at(-1);
    assert.ok(first, 'no /api/mold request was captured at all');
    assert.equal(new URL(first).searchParams.get('cols'), null, `page one asked ${first}`);
    assert.equal(await summary(page), '36 of 36 columns');

    await toggleColumn(page, 'grp');

    const after = new URL(asked.at(-1)).searchParams.get('cols');
    assert.ok(after, `no cols parameter after unticking a column: ${asked.at(-1)}`);
    const names = after.split(',');
    assert.equal(names.length, 35, `the request asked for ${names.length} columns`);
    assert.ok(!names.includes('grp'),
      'the hidden column is still in the request — the filtering is happening in the view');
    // and the page agrees with the request it made
    assert.equal(await headerCount(page), 35);
    assert.equal(await colIndex(page, 'grp'), -1);
    assert.equal(await summary(page), '35 of 36 columns');

    // The hidden column is still ON THE LIST, which is how it comes back. The
    // chooser reads `:table/cols` off the HANDLE; read back off the projected
    // rows, hiding would be one-way.
    assert.equal(await page.locator(`${G} [data-grid-cols] label`).count(), 36);
    await toggleColumn(page, 'grp');
    assert.equal(await headerCount(page), 36);
    assert.equal(new URL(asked.at(-1)).searchParams.get('cols'), null,
      'with nothing hidden the parameter goes away again rather than listing all 36');
  });
});

test('a hidden column, a sort and a page size survive a reload', async () => {
  await withPage(browser, 'cljs-grid-prefs', async (page) => {
    const asked = moldRequests(page);
    await openGrid(page);
    await toggleColumn(page, 'grp');
    await sortBy(page, 'id', 'ascending');
    await step(page, () => page.selectOption(`${G} [data-grid-size]`, '25'));
    const n = asked.length;

    // A real reload: the whole shell boots again and the grid is mounted from
    // nothing. Preferences live in `localStorage` and never in the event log —
    // fiddling with a lens is not an act on content, and every column-header
    // click becoming a point in history is noise in the one log `?at=` rewinds.
    await openGrid(page);

    // **The FIRST request after the reload, and that is the whole assertion.**
    // Preferences applied after the payload landed would end up showing the same
    // page — having first asked the server for a hundred rows of all thirty-six
    // columns in the wrong order and thrown them away. On a 10M-row table that
    // request is not free.
    const u = new URL(asked[n]);
    assert.equal(u.searchParams.get('limit'), '25', `the first request back was ${asked[n]}`);
    assert.equal(u.searchParams.get('sort'), 'id,asc');
    const cols = u.searchParams.get('cols');
    // Named rather than dereferenced: a cold mount has no payload to take a
    // column list from, so this is the assertion that goes red if the list the
    // preference was recorded against stops being remembered beside it — and
    // `null.split` reports a TypeError rather than saying that.
    assert.ok(cols, `the first request back carried no cols at all: ${asked[n]}`);
    const names = cols.split(',');
    assert.equal(names.length, 35);
    assert.ok(!names.includes('grp'));

    assert.equal(await rows(page), 25);
    assert.equal(await headerCount(page), 35);
    assert.equal(await page.inputValue(`${G} [data-grid-size]`), '25');
    assert.equal((await sortState(page, 'id')).aria, 'ascending');
    assert.equal(await summary(page), '35 of 36 columns');
  });
});

test('a browser that refuses localStorage still draws the grid', async () => {
  await withPage(browser, 'cljs-grid-nostorage', async (page, diag) => {
    // A private window, cleared site data, or a browser set to block storage
    // throws on the ACCESSOR — `window.localStorage` itself, before any getItem
    // is reached. So an unguarded read does not return nothing, it takes the
    // whole panel down before a row is drawn.
    await page.addInitScript(() => {
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        get() { throw new DOMException('storage is blocked', 'SecurityError'); },
      });
    });
    await openGrid(page);

    // **Prove the environment really is sabotaged first.** If `defineProperty`
    // had failed, everything below would pass on an ordinary browser and this
    // test would certify a `try` that was never exercised.
    assert.ok(await page.evaluate(() => {
      try { void window.localStorage; return false; } catch { return true; }
    }), 'localStorage did not throw — this test would pass on an unguarded read');

    assert.equal(await rows(page), 100, 'no rows: the storage read took the panel down');
    assert.equal(await headerCount(page), 36);
    // and the controls still work; they simply do not remember
    await toggleColumn(page, 'grp');
    assert.equal(await headerCount(page), 35);
    assert.deepEqual(diag.pageErrors, [], 'the page threw');
  });
});

test('a stored preference that will not decode is ignored, not fatal', async () => {
  await withPage(browser, 'cljs-grid-badprefs', async (page, diag) => {
    // The other half of the storage guard, and a different failure from a
    // refused accessor: the value itself. It is hand-editable, it survives
    // upgrades of loci, and a truncated write leaves a string Transit cannot
    // read — so `t/read` throws inside `mount`, where nothing above it is
    // watching. A forgotten preference is the right outcome; a dead panel is not.
    await openGrid(page);
    await page.evaluate(() => localStorage.setItem('loci.grid/tbl:grid', '{not transit'));
    await openGrid(page);
    assert.equal(await rows(page), 100, 'the grid did not draw at all');
    assert.equal(await headerCount(page), 36, 'and it fell back to no preferences rather than none');
    assert.equal(await page.inputValue(`${G} [data-grid-size]`), '100');
    assert.deepEqual(diag.pageErrors, [], 'the page threw');
  });
});
