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
