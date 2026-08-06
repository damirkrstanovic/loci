// Tags narrow the overview: include, exclude, or neither — and they compose
// with LEAP's filter rather than being a second mechanism. The harness tags
// four of the seeded notebooks (company on two of them, and sales carries a
// second tag) which is the smallest corpus in which include, exclude and
// "exclude beats include" are distinguishable.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// Panels transition opacity over .4s, so a value read the moment a chip is
// clicked is the pre-filter opacity on its way down — and a dim rule that does
// nothing at all reads identically. Wait for the numbers to stop moving, then
// assert them; a broken rule settles too, at the wrong value, which fails with
// a number rather than a timeout. (Same helper as leapfilter.test.mjs — each
// browser test file stands alone.)
const settleOpacity = (page) => page.evaluate(() => new Promise((res, rej) => {
  const key = () => panels.map(p => getComputedStyle(p).opacity).join(',');
  let last = null, stable = 0, frames = 0;
  const clock = setTimeout(() => rej(new Error('panel opacity never settled in 10s')), 10_000);
  const tick = () => {
    const k = key();
    if (k === last) { if (++stable >= 3) { clearTimeout(clock); return res(); } } else stable = 0;
    last = k;
    if (++frames > 600) { clearTimeout(clock); return rej(new Error('opacity never settled in 600 frames')); }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}));

// What the eye sees, keyed by notebook. Class membership is deliberately NOT
// the measure: layout()'s place() writes opacity inline on every panel, so the
// dim class can be on every non-matching panel while the screen is unchanged.
const opacities = (page) => page.evaluate(() => {
  const out = {};
  STATE.spaces.forEach((s, k) => { out[s.id] = +getComputedStyle(panels[k]).opacity; });
  return out;
});

// getAttribute would return "tagchip " — the template leaves a trailing space
// where the state class goes — so read the classList, which is what the rules
// actually match on.
const chipClass = (page, tag) =>
  page.$eval(`#tagstrip .tagchip[data-tag="${tag}"]`, e => [...e.classList].join(' '));

// `.panel.dim:hover` lifts a dimmed panel to .5 on purpose, so a pointer left
// over the canvas turns a measurement of the filter into a measurement of the
// hover. Park it on the topbar first.
const restAndSettle = async (page) => {
  await page.mouse.move(5, 5);
  await settleOpacity(page);
};

const inOverview = async (page) => {
  await page.evaluate(() => overview());
  await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));
  await page.waitForSelector('#tagstrip .tagchip');
  await restAndSettle(page);
};

test('the strip shows every tag in the corpus, most common first', async () => {
  await withPage(browser, 'tag-strip-renders', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const chips = await page.$$eval('#tagstrip .tagchip', els => els.map(e => e.dataset.tag));
    assert.deepEqual(chips.slice(0, 1), ['company'],
      `two notebooks carry "company", so it leads: got ${JSON.stringify(chips)}`);
    assert.ok(chips.includes('astronomy') && chips.includes('pipeline') && chips.includes('world data'),
      `a tag asserted on a notebook is missing from the strip: ${JSON.stringify(chips)}`);
    // the count beside the name is the promise the ordering rests on
    const counts = await page.$$eval('#tagstrip .tagchip',
      els => Object.fromEntries(els.map(e => [e.dataset.tag, e.querySelector('i').textContent])));
    assert.equal(counts['company'], '2');
    assert.equal(counts['astronomy'], '1');
  });
});

test('including a tag lights its notebooks and dims the rest, in pixels', async () => {
  await withPage(browser, 'tag-include-dims', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    await page.click('#tagstrip .tagchip[data-tag="company"]');
    await restAndSettle(page);

    assert.equal(await chipClass(page, 'company'), 'tagchip in');
    const o = await opacities(page);
    assert.equal(o['space:finance'], 1, 'a notebook tagged company must stay lit');
    assert.equal(o['space:sales'], 1, 'a notebook tagged company must stay lit');
    // the assertion the shipped bug slipped past: every class was right and the
    // crumb counted correctly, but place()'s inline opacity:1 outranked the
    // stylesheet and nothing on screen moved. Measure the pixels.
    const others = Object.entries(o).filter(([id]) => !['space:finance', 'space:sales'].includes(id));
    const brightest = Math.max(...others.map(([, v]) => v));
    assert.ok(brightest <= 0.3,
      `notebooks without the tag are still at opacity ${brightest} — .panel.dim is a no-op ` +
      'against the inline opacity layout() writes on every panel');
    assert.equal(others.length, 14, 'the fixture no longer splits the overview 2/14');
    assert.match(await page.textContent('#crumbFilter'), /\+company/);
  });
});

test('a second click excludes, and exclude beats include', async () => {
  await withPage(browser, 'tag-exclude-wins', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    // sales carries BOTH company and pipeline: include company, exclude pipeline
    await page.click('#tagstrip .tagchip[data-tag="company"]');
    await page.click('#tagstrip .tagchip[data-tag="pipeline"]');
    await page.click('#tagstrip .tagchip[data-tag="pipeline"]');   // in → out
    await restAndSettle(page);

    assert.equal(await chipClass(page, 'pipeline'), 'tagchip out');
    assert.equal(await chipClass(page, 'company'), 'tagchip in');
    const o = await opacities(page);
    assert.equal(o['space:finance'], 1, 'company, not pipeline — stays lit');
    assert.ok(o['space:sales'] <= 0.3,
      `space:sales is at opacity ${o['space:sales']}: it carries an excluded tag, so it ` +
      'must go dark despite also carrying an included one');
    assert.match(await page.textContent('#crumbFilter'), /−pipeline/,
      'the crumb must say the exclusion out loud');
  });
});

test('a third click returns the tag to neutral', async () => {
  await withPage(browser, 'tag-cycles-to-neutral', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const chip = '#tagstrip .tagchip[data-tag="astronomy"]';
    await page.click(chip);
    assert.equal(await chipClass(page, 'astronomy'), 'tagchip in');
    await page.click(chip);
    assert.equal(await chipClass(page, 'astronomy'), 'tagchip out');
    await page.click(chip);
    await restAndSettle(page);

    assert.equal(await chipClass(page, 'astronomy'), 'tagchip',
      'a third click must return the tag to neutral, not back to include');
    assert.equal(await page.evaluate(() => Object.keys(tagState).length), 0,
      'neutral means the tag is gone from the state, not held at a third value');
    const o = await opacities(page);
    const dark = Object.entries(o).filter(([, v]) => v !== 1);
    assert.deepEqual(dark, [], 'neutral means nothing is dimmed');
    assert.equal(await page.locator('#crumbFilter').count(), 0,
      'nothing is filtered, so the crumb must not claim anything is');
  });
});

test('a tag and a LEAP filter intersect, and one ✕ clears both', async () => {
  await withPage(browser, 'tag-and-leap-compose', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    await page.click('#tagstrip .tagchip[data-tag="company"]');

    // "pipeline" matches space:sales (its title) and tbl:opps (inside it) and
    // nothing else — so of the two company notebooks LEAP keeps exactly one
    await page.click('#q');
    await page.locator('#q').pressSequentially('pipeline', { delay: 25 });
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent.includes('Ask the agent: “pipeline”')));
    await page.locator('#results .res').filter({ hasText: 'Filter the overview' }).click();
    await page.waitForFunction(() => typeof overviewFilter !== 'undefined' && overviewFilter !== null);
    await restAndSettle(page);

    const o = await opacities(page);
    assert.equal(o['space:sales'], 1, 'tagged company AND matched by LEAP');
    assert.ok(o['space:finance'] <= 0.3,
      `space:finance is at opacity ${o['space:finance']} — tagged company but not matched ` +
      'by LEAP, so the two predicates are not intersecting');
    const crumb = await page.textContent('#crumbFilter');
    assert.match(crumb, /\+company/, `one crumb carries both: ${crumb}`);
    assert.match(crumb, /“pipeline”/, `one crumb carries both: ${crumb}`);
    assert.match(crumb, /1 of 16/, `the crumb should count the intersection: ${crumb}`);

    await page.click('#crumbFilter .x');
    await restAndSettle(page);
    assert.equal(await page.evaluate(() => Object.keys(tagState).length), 0,
      'the ✕ cleared LEAP but left the tag applied — one crumb, one ✕, both filters');
    const still = await page.evaluate(() => overviewFilter === null ? null : overviewFilter.q);
    assert.equal(still, null, `the ✕ left “${still}” applied`);
    assert.equal(await page.evaluate(() => mode), 'overview', 'clearing must not change mode');
    const cleared = await opacities(page);
    assert.deepEqual(Object.entries(cleared).filter(([, v]) => v !== 1), [],
      'the ✕ cleared the state but the canvas is still grey');
    assert.equal(await page.locator('#crumbFilter').count(), 0, 'the ✕ did not remove itself');
  });
});

// ---------------- inks ----------------
// Colour is the one property of a tag that no class name can stand in for: the
// swatch, the include border and the exclude clay are all written by CSS custom
// properties resolved at paint time, so every assertion below reads
// getComputedStyle. A 2026-08-06 defect in this very file passed a class-name
// check while the screen was unchanged; that is not repeatable here.

// Chromium serialises colours as "rgb(r, g, b)" — build the same shape from the
// palette hex so a mismatch prints two comparable strings.
const rgb = (hex) => `rgb(${parseInt(hex.slice(1, 3), 16)}, ` +
                     `${parseInt(hex.slice(3, 5), 16)}, ${parseInt(hex.slice(5, 7), 16)})`;

// --accent-line, what .tagchip.in used to border in for every tag alike.
const ACCENT_LINE = 'rgb(115, 167, 151)';
// --attn, the clay that says "subtracting". Not a member of TAG_INKS, which is
// what lets an exclude assertion and an ink assertion tell each other apart.
const CLAY = 'rgb(169, 99, 47)';

// [tag, painted swatch colour] for every chip on the strip.
const stripInks = (page) => page.$$eval('#tagstrip .tagchip',
  els => els.map(e => [e.dataset.tag, getComputedStyle(e.querySelector('.ink')).backgroundColor]));

// The palette the client mirrors from srv/tag-inks, as painted colours. Read from
// the page rather than restated here, so the test follows the palette.
const palette = async (page) => (await page.evaluate(() => TAG_INKS)).map(rgb);

// borderTopColor, not the borderColor shorthand: the shorthand serialises to a
// single value only while all four sides agree, so a rule that changed one side
// would come back as a four-part string and compare unequal for the wrong reason.
const chipPaint = (page, tag) => page.$eval(`#tagstrip .tagchip[data-tag="${tag}"]`, e => {
  const s = getComputedStyle(e);
  return { border: s.borderTopColor, text: s.color, deco: s.textDecorationLine,
           ink: getComputedStyle(e.querySelector('.ink')).backgroundColor };
});

test('every tag on the strip wears its assigned ink, and no two share one', async () => {
  await withPage(browser, 'tag-inks-assigned-distinct', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);

    const inks = await stripInks(page);
    assert.equal(inks.length, 4,
      `the fixture tags four notebooks with four distinct tags: got ${JSON.stringify(inks)}`);

    const known = await palette(page);
    const registry = await page.evaluate(() => ({ ...STATE.tagColors }));
    for (const [tag, painted] of inks) {
      assert.notEqual(painted, 'rgba(0, 0, 0, 0)',
        `"${tag}" painted no swatch at all — --tag-ink resolved to nothing`);
      assert.ok(known.includes(painted),
        `"${tag}" is painted ${painted}, which is not one of the eight palette inks ${known.join(' ')}`);
      // the swatch must be the ink the SERVER assigned, not something the client
      // made up: set-tags! wrote these into the registry and /api/state ships them
      assert.ok(registry[tag], `"${tag}" has no registry entry — set-tags! did not assign an ink`);
      assert.equal(painted, rgb(registry[tag]),
        `"${tag}" is registered ${registry[tag]} but painted ${painted}`);
    }

    const distinct = new Set(inks.map(([, c]) => c));
    assert.equal(distinct.size, inks.length,
      'two tags are wearing the same ink, so the swatch stops identifying anything: ' +
      JSON.stringify(inks));
  });
});

test('an included chip borders in its own ink, not the one accent every tag shared', async () => {
  await withPage(browser, 'tag-include-borders-own-ink', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);

    const own = (await chipPaint(page, 'company')).ink;
    assert.notEqual(own, ACCENT_LINE, 'the fixture ink coincides with --accent-line — ' +
      'the assertion below could not tell the two apart');

    await page.click('#tagstrip .tagchip[data-tag="company"]');
    await restAndSettle(page);

    const p = await chipPaint(page, 'company');
    assert.equal(p.border, own,
      `an included chip must border in the tag's own ink ${own}; it borders ${p.border}`);
    assert.notEqual(p.border, ACCENT_LINE,
      'the included chip is bordered in --accent-line — every tag lights up the same ' +
      'green again and the ink says nothing');
    assert.equal(p.text, own, `the label should take the ink too; it is ${p.text}`);
  });
});

test('an excluded chip is clay and struck, and its swatch still names the tag', async () => {
  await withPage(browser, 'tag-exclude-clay-keeps-ink', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);

    const before = (await chipPaint(page, 'pipeline')).ink;
    assert.notEqual(before, CLAY,
      'the tag is already clay, so "the swatch survived" below would prove nothing');

    const chip = '#tagstrip .tagchip[data-tag="pipeline"]';
    await page.click(chip);   // neutral → in
    await page.click(chip);   // in → out
    await restAndSettle(page);
    assert.equal(await chipClass(page, 'pipeline'), 'tagchip out', 'two clicks must reach exclude');

    const p = await chipPaint(page, 'pipeline');
    assert.ok(p.deco.includes('line-through'),
      `an excluded tag must read as struck through; text-decoration-line is "${p.deco}"`);
    assert.equal(p.border, CLAY,
      `exclude is clay, not the tag's ink: the chip borders ${p.border}`);
    assert.equal(p.ink, before,
      `the swatch changed from ${before} to ${p.ink} — excluding a tag must not take away ` +
      'the one mark that says WHICH tag is being subtracted');
  });
});

test('picking an ink in the tags editor reaches the strip', async () => {
  await withPage(browser, 'tag-ink-picker-reaches-strip', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);
    const was = (await chipPaint(page, 'astronomy')).ink;

    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.click(`#body${i} #tagBtn`);
    const sw = `#body${i} .tagedit .tagchip .ink[data-ink="astronomy"]`;
    await page.click(sw);
    await page.waitForSelector(`#body${i} .inkpick b`);

    // An ink no tag is wearing yet: the pick is global and outlives this test, so
    // leaving the corpus with two tags on one ink would sabotage whatever runs next.
    const target = await page.evaluate(() => {
      const taken = new Set(Object.values(STATE.tagColors || {}));
      return TAG_INKS.find(c => !taken.has(c)) || null;
    });
    assert.ok(target, 'no free ink in the palette — this test needs one that differs');
    const want = rgb(target);
    assert.notEqual(want, was, 'the picked ink is the one the tag already wears');

    // the current ink is marked, and marked with a real border rather than a class
    const marked = await page.$$eval(`#body${i} .inkpick b`, els => els
      .filter(e => getComputedStyle(e).borderTopColor !== 'rgba(0, 0, 0, 0)')
      .map(e => e.dataset.c));
    assert.deepEqual(marked.map(rgb), [was],
      `exactly the tag's current ink should be ringed in the picker; ringed: ${JSON.stringify(marked)}`);

    await page.click(`#body${i} .inkpick b[data-c="${target}"]`);
    // The POST is in flight. Wait on the editor chip repainting rather than a
    // sleep — that is draw() running after applyState, which is the whole success
    // path — and report what it actually shows if it never gets there.
    try {
      await page.waitForFunction(([s, w]) => {
        const u = document.querySelector(s);
        return !!u && getComputedStyle(u).backgroundColor === w;
      }, [sw, want], { timeout: 15_000 });
    } catch {
      const now = await page.$eval(sw, e => getComputedStyle(e).backgroundColor).catch(e => `unreadable (${e.message})`);
      const toast = await page.evaluate(() => document.getElementById('toast')?.textContent || '(no toast)');
      assert.fail(`picked ${target} (${want}) but the editor chip is still ${now}. toast: ${toast}`);
    }

    // What this proves is the end-to-end outcome — the pick is standing, global and
    // on the strip when you come back — NOT the renderTagStrip() the picker's own
    // success path calls. That call cannot be observed: .world.cards hides .p-body,
    // so the editor exists only in focus, where renderTagStrip() returns at its
    // first line, and every road back to the overview runs layout(), which starts
    // by calling it anyway. Deleting it from the picker leaves all 30 tests green.
    await inOverview(page);
    const now = (await chipPaint(page, 'astronomy')).ink;
    assert.equal(now, want,
      `the strip chip is ${now}, not the ${target} just chosen — a colour picked in the ` +
      'editor is standing and global, so the overview must show it');
    assert.notEqual(now, was, 'the strip chip never moved off its old ink');
  });
});

test('a tag the colour registry has never seen still renders an ink', async () => {
  await withPage(browser, 'tag-ink-fallback', async (page) => {
    await bootedShell(page, server.url);
    await inOverview(page);

    // A corpus tagged before colours existed has no palette object, and applyState
    // turns that into `STATE.tagColors = {}` — so {} is exactly the shape such a
    // server hands the shell. There is no button that produces it (set-tags! now
    // assigns on the way in, and the fixture went through it), so it is set here
    // and the strip re-rendered through the same renderTagStrip the app calls.
    const inks = await page.evaluate(() => {
      STATE.tagColors = {};
      renderTagStrip();
      return [...document.querySelectorAll('#tagstrip .tagchip')].map(e =>
        [e.dataset.tag, getComputedStyle(e.querySelector('.ink')).backgroundColor]);
    });
    assert.equal(inks.length, 4, `the strip lost its chips: ${JSON.stringify(inks)}`);

    const known = await palette(page);
    for (const [tag, painted] of inks) {
      assert.notEqual(painted, 'rgba(0, 0, 0, 0)',
        `"${tag}" has no registry entry and painted no swatch at all — an untouched ` +
        'corpus would show a strip of empty boxes');
      assert.ok(known.includes(painted),
        `"${tag}" fell back to ${painted}, which is not a palette ink (${known.join(' ')})`);
    }
    // Not distinctness: the fallback is a bare hash and may put two tags on one
    // ink — only the server's least-used assignment guarantees otherwise. But if
    // every tag comes back the SAME colour the fallback has collapsed into the
    // single accent green it exists to avoid, and that is what this catches.
    assert.ok(new Set(inks.map(([, c]) => c)).size > 1,
      `every tag fell back to one colour: ${JSON.stringify(inks)}`);
  });
});
