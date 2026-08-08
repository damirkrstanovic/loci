// LEAP asks twice: the lexical query on every keystroke, and — ~250 ms after
// the keys stop, or on Enter — a second `deep=1` query whose `by meaning` rows
// are merged into what is already on screen.
//
// Every /api/leap here is answered by page.route, so these tests need no
// embedder, never reach a model, and are pure functions of the fixtures below.
// That is deliberate: the interesting behaviour is the RACE between two
// responses, and a real embedder would make the timing unrepeatable.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// ---------------- the stubbed endpoint ----------------
// Ids are real ones from the seeded corpus, because acting on a row runs the
// shell's real enter()/openObject() against the real server.
const VERB = { id: 'view:table', label: 'view as Table', group: 'viewer' };

// The label carries the query verbatim, which is what makes "whose results are
// these?" answerable from the DOM alone — the staleness test rests on it.
const content = (q) => q ? [
  { id: 'space:cosmos', label: 'LEX-A ' + q, group: 'space' },
  { id: 'doc:cosmos', label: 'LEX-B ' + q, group: 'doc' },
] : [];

// A notebook and a table, so "a space hit enters, everything else opens" has
// both cases. Neither id appears lexically, so nothing here is a duplicate.
const defaultDeep = (q) => [
  { id: 'space:semis', label: 'DEEP-SPACE ' + q, group: 'by meaning', kind: 'space', score: 0.71, touched: 0 },
  { id: 'tbl:planets', label: 'DEEP-OBJ ' + q, group: 'by meaning', kind: 'table', score: 0.63, touched: 0 },
];

// A ?deep=1 response is the flat lexical array with the semantic rows added —
// same shape, extra rows. `hold` lets a test keep one in flight for as long as
// it needs, which is how "the lexical list is drawn first" is provable at all.
//
// `deepRows(q, nth)` is given the ordinal of the deep request, 1-based, so a test
// can answer the second ask differently from the first — which is the only way
// "asking again brought back something else" is visible in the DOM at all.
async function stubLeap(page, opts = {}) {
  const seen = { lex: [], deep: [], done: [] };
  const deepRows = opts.deepRows || defaultDeep;
  await page.route(u => u.pathname === '/api/leap', async (route) => {
    const url = new URL(route.request().url());
    const q = url.searchParams.get('q') || '';
    const deep = url.searchParams.get('deep') === '1';
    if (!deep) { seen.lex.push(q); await route.fulfill({ json: [...content(q), VERB] }); return; }
    const nth = seen.deep.push(q);
    if (opts.hold) await opts.hold(q);
    await route.fulfill({ json: [...content(q), ...deepRows(q, nth), VERB] });
    seen.done.push(q);
  });
  return seen;
}

// A gate a test can hold a deep response behind and release when it chooses.
const gate = () => { let open; const p = new Promise(r => open = r); return { wait: () => p, open }; };

// ---------------- reading the list ----------------
// The dropdown is group headers interleaved with rows, and where a row sits
// relative to its header is part of what is being asserted — so read the
// children in order rather than the rows alone.
const listing = (page) => page.$$eval('#results > *', els => els.map(e =>
  e.classList.contains('group')
    ? { group: e.textContent }
    : { row: e.querySelector('.title').textContent, id: e.querySelector('.gp').textContent,
        cls: [...e.classList].filter(c => c !== 'sel' && c !== 'res').join(' ') }));

const rowTexts = (page) => page.$$eval('#results .res .title', els => els.map(e => e.textContent));

const leapTo = async (page, text) => {
  await page.click('#q');
  await page.locator('#q').pressSequentially(text, { delay: 20 });
  await page.waitForFunction(t => [...document.querySelectorAll('#results .res .title')]
    .some(el => el.textContent.includes('Ask the agent: “' + t + '”')), text);
};

const hasByMeaning = (page) => page.evaluate(() =>
  [...document.querySelectorAll('#results .group')].some(g => g.textContent === 'By meaning'));

// Walk the selection to a named row with the arrow keys, the way a hand would.
// Not `sel = n` from evaluate(): the point of the Enter tests is the keyboard
// path, and the keydown handler reading a selection somebody else set is not it.
const selectRow = async (page, text) => {
  const target = await page.$$eval('#results .res',
    (els, t) => els.findIndex(e => e.querySelector('.title').textContent === t), text);
  assert.ok(target >= 0, `there is no row reading “${text}”: ` +
    JSON.stringify(await page.$$eval('#results .res .title', e => e.map(x => x.textContent))));
  for (let i = 0; i <= target + 40; i++) {
    const cur = await page.evaluate(() => sel);
    if (cur === target) return target;
    await page.locator('#q').press(cur < target ? 'ArrowDown' : 'ArrowUp');
  }
  throw new Error(`the arrows never reached “${text}”`);
};

// Poll rather than sleep, and fail with what was actually seen.
const until = async (fn, what, ms = 10_000) => {
  const deadline = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > deadline) throw new Error(`timed out after ${ms}ms waiting for ${what}`);
    await new Promise(r => setTimeout(r, 25));
  }
};

// Give a response that has already been fulfilled time to be *rendered* before
// asserting it was not. Two frames is what a render costs; the margin is for
// the fetch continuation. Only used where the assertion is that nothing happened.
const settle = async (page) => {
  await new Promise(r => setTimeout(r, 400));
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
};

// ---------------- the tests ----------------

test('the lexical list is on screen while the deep request is still out, and one deep request covers the whole word', async () => {
  await withPage(browser, 'leap-deep-lexical-first', async (page) => {
    const g = gate();
    const seen = await stubLeap(page, { hold: () => g.wait() });
    await bootedShell(page, server.url);
    try {
      await leapTo(page, 'planets');

      // the deep answer is being held open right now, so everything below is
      // what the keystroke path put there on its own
      const l = await listing(page);
      assert.ok(l.some(x => x.row === 'LEX-A planets'),
        `the lexical rows are not drawn while the deep request is out: ${JSON.stringify(l)}`);
      assert.equal(await hasByMeaning(page), false,
        'a By meaning group appeared before any deep response arrived');

      // the deep request must have been fired ONCE, after the keys stopped —
      // not once per keystroke, which is exactly what the 250 ms pause is for
      await until(() => seen.deep.length > 0, 'the deep request to be fired');
      assert.ok(seen.deep.length <= 2,
        `${seen.deep.length} deep requests for 7 keystrokes — the pause is not debouncing: ` +
        JSON.stringify(seen.deep));
      assert.equal(seen.deep.at(-1), 'planets',
        `the deep request asked for “${seen.deep.at(-1)}”, not the word that was typed`);
      assert.ok(seen.deep.every(x => 'planets'.startsWith(x)),
        `a deep request was fired for something never typed: ${JSON.stringify(seen.deep)}`);
    } finally { g.open(); }
  });
});

test('the by-meaning group merges in after the pause and the earlier results are still there', async () => {
  await withPage(browser, 'leap-deep-merges', async (page) => {
    const g = gate();
    const seen = await stubLeap(page, { hold: () => g.wait() });
    await bootedShell(page, server.url);
    await leapTo(page, 'planets');
    await until(() => seen.deep.length > 0, 'the deep request to be fired');

    const before = await listing(page);
    assert.ok(!before.some(x => x.row && x.row.startsWith('DEEP-')), 'the gate did not hold');

    // record every state the list passes through: a "merge" that empties the
    // container first is a flicker, and only a mutation record can see it
    await page.evaluate(() => {
      window.__snaps = [];
      new MutationObserver(() => window.__snaps.push(
        [...document.querySelectorAll('#results .res .title')].map(e => e.textContent)
      )).observe(document.getElementById('results'), { childList: true, subtree: true, characterData: true });
    });

    g.open();
    await page.waitForFunction(() => [...document.querySelectorAll('#results .group')]
      .some(x => x.textContent === 'By meaning'));

    const after = await listing(page);
    // the deep rows arrived...
    assert.ok(after.some(x => x.row === 'DEEP-SPACE planets'), `no semantic hit: ${JSON.stringify(after)}`);
    assert.ok(after.some(x => x.row === 'DEEP-OBJ planets'), `no semantic hit: ${JSON.stringify(after)}`);
    // ...and everything that was on screen is still on screen, in order, unchanged
    assert.deepEqual(after.filter(x => !(x.row || '').startsWith('DEEP-') && x.group !== 'By meaning'), before,
      'the deep response did not merge — the lexical list changed under it');

    // where they landed: content, then by meaning, then the verbs
    const order = after.map(x => x.group || x.row);
    assert.ok(order.indexOf('By meaning') < order.indexOf('View verbs'),
      `by meaning must sit before the view verbs: ${JSON.stringify(order)}`);
    assert.equal(order[order.indexOf('By meaning') + 1], 'DEEP-SPACE planets',
      `the semantic hits are not under their own header: ${JSON.stringify(order)}`);

    const snaps = await page.evaluate(() => window.__snaps);
    assert.ok(snaps.length > 0, 'the list never redrew, so the merge was not observed at all');
    const lost = snaps.filter(s => !s.includes('LEX-A planets'));
    assert.deepEqual(lost, [],
      `the lexical rows vanished from ${lost.length} of ${snaps.length} redraws — that is the flicker`);
  });
});

test('a slow deep response for an old query never lands on a newer list', async () => {
  await withPage(browser, 'leap-deep-stale-dropped', async (page) => {
    const g = gate();
    // only "alpha" is held; "alphabet" answers at once, so the slow response is
    // guaranteed to arrive after the fast one has already been drawn
    const seen = await stubLeap(page, { hold: q => q === 'alpha' ? g.wait() : Promise.resolve() });
    await bootedShell(page, server.url);

    await leapTo(page, 'alpha');
    await until(() => seen.deep.includes('alpha'), 'the deep request for “alpha”');

    await page.locator('#q').pressSequentially('bet', { delay: 20 });
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent.includes('Ask the agent: “alphabet”')));
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === 'DEEP-SPACE alphabet'));

    g.open();                                   // the stale answer comes home
    await until(() => seen.done.includes('alpha'), 'the held response to be delivered');
    await settle(page);

    const rows = await rowTexts(page);
    assert.ok(rows.includes('DEEP-SPACE alphabet'),
      `the current query's semantic hits are gone: ${JSON.stringify(rows)}`);
    assert.ok(!rows.includes('DEEP-SPACE alpha'),
      `a semantic hit for “alpha” is on screen while the box says “alphabet”: ${JSON.stringify(rows)}`);
    assert.ok(!rows.includes('DEEP-OBJ alpha'),
      `a semantic hit for “alpha” is on screen while the box says “alphabet”: ${JSON.stringify(rows)}`);
    assert.ok(rows.includes('LEX-A alphabet') && !rows.includes('LEX-A alpha'),
      `the lexical half belongs to the wrong query: ${JSON.stringify(rows)}`);
  });
});

// The test above passes with the input-value check gone, because the rows are
// stashed against the query they answer and a mismatched stash is never drawn.
// That is defence in depth, and it hides this: a late answer that is merely
// DISCARDED costs nothing, but one that overwrites the stash throws away the
// fresh answer the shell was holding, and the group disappears the next time
// the list is redrawn for the same query. This is that redraw.
test('a stale deep answer does not displace the fresh one already in hand', async () => {
  await withPage(browser, 'leap-deep-stale-no-clobber', async (page) => {
    const slow = gate(), never = gate();
    let alphabets = 0;
    const seen = await stubLeap(page, {
      hold: (q) => {
        if (q === 'alpha') return slow.wait();          // the old query, home late
        if (q === 'alphabet' && ++alphabets === 1) return Promise.resolve();
        return never.wait();                            // no second chance to self-repair
      },
    });
    await bootedShell(page, server.url);
    try {
      await leapTo(page, 'alpha');
      await until(() => seen.deep.includes('alpha'), 'the deep request for “alpha”');
      await page.locator('#q').pressSequentially('bet', { delay: 20 });
      await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
        .some(el => el.textContent === 'DEEP-SPACE alphabet'));

      slow.open();
      await until(() => seen.done.includes('alpha'), 'the held response to be delivered');
      await settle(page);

      // a keystroke and a backspace: the query leaves and comes back, which
      // rebuilds the list from whatever deep answer the shell is still holding
      await page.locator('#q').press('x');
      await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
        .some(el => el.textContent === 'LEX-A alphabetx'));
      await page.locator('#q').press('Backspace');
      await page.waitForFunction(() => {
        const t = [...document.querySelectorAll('#results .res .title')].map(e => e.textContent);
        return t.includes('LEX-A alphabet') && !t.includes('LEX-A alphabetx');
      });

      const rows = await rowTexts(page);
      assert.ok(rows.includes('DEEP-SPACE alphabet'),
        'the semantic hits for the current query are gone — a stale answer overwrote the ' +
        `fresh one the shell had already fetched: ${JSON.stringify(rows)}`);
      assert.ok(!rows.includes('DEEP-SPACE alpha'),
        `the stale answer is being shown under the wrong query: ${JSON.stringify(rows)}`);
    } finally { slow.open(); never.open(); }
  });
});

test('a deep response carrying no semantic rows leaves exactly the lexical list', async () => {
  await withPage(browser, 'leap-deep-empty-noop', async (page) => {
    // what the server returns with no embedder configured: today's response,
    // byte for byte, and the spec calls that non-negotiable
    const seen = await stubLeap(page, { deepRows: () => [] });
    await bootedShell(page, server.url);
    await leapTo(page, 'planets');
    const before = await listing(page);

    await until(() => seen.done.length > 0, 'the deep response to be delivered');
    await settle(page);

    assert.deepEqual(await listing(page), before,
      'a deep response with no semantic rows changed the list');
    assert.equal(await hasByMeaning(page), false,
      'an empty By meaning group was drawn for a response that carried none');
  });
});

test('a notebook found by meaning is entered; anything else is opened', async () => {
  await withPage(browser, 'leap-deep-space-vs-object', async (page) => {
    await stubLeap(page);
    await bootedShell(page, server.url);

    await leapTo(page, 'planets');
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === 'DEEP-SPACE planets'));

    // the notebook wears a notebook's mark, which is the same claim the click makes
    const l = await listing(page);
    const spaceRow = l.find(x => x.row === 'DEEP-SPACE planets');
    assert.equal(spaceRow.cls, 'space',
      `a :space hit renders as “${spaceRow.cls}” — it should look like the notebook it is`);

    await page.locator('#results .res').filter({ hasText: 'DEEP-SPACE planets' }).click();
    await page.waitForFunction(() => mode === 'focus');
    assert.equal(await page.evaluate(() => STATE.spaces[focusIdx].id), 'space:semis',
      'clicking a :space hit must enter that notebook');
    assert.equal(await page.evaluate(() => openId), null,
      'a notebook was opened as an object — openObject() is the wrong verb for a :space hit');

    // and the other kind, from the same list — back to the overview first, which
    // is where every other test drives LEAP from
    await page.evaluate(() => overview());
    await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));
    await leapTo(page, 'planets');
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === 'DEEP-OBJ planets'));
    await page.locator('#results .res').filter({ hasText: 'DEEP-OBJ planets' }).click();
    await page.waitForFunction(() => openId !== null);
    assert.equal(await page.evaluate(() => openId), 'tbl:planets',
      'a table found by meaning must open as an object');
  });
});

test('the __deep__ note is a statement, not a result: it has its own group and does not act', async () => {
  await withPage(browser, 'leap-deep-note-inert', async (page) => {
    await stubLeap(page, {
      deepRows: () => [{ id: '__deep__', note: true, group: 'by meaning',
                         label: '3 chunk(s) awaiting embedding', awaiting: 3 }],
    });
    await bootedShell(page, server.url);
    await leapTo(page, 'planets');
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === '3 chunk(s) awaiting embedding'));

    const l = await listing(page);
    const order = l.map(x => x.group || x.row);
    const note = l.find(x => x.row === '3 chunk(s) awaiting embedding');
    assert.equal(order[order.indexOf('By meaning') + 1], '3 chunk(s) awaiting embedding',
      `the note must sit under a By meaning header, not under “Content”: ${JSON.stringify(order)}`);
    assert.equal(note.cls, 'note', `the note renders as “${note.cls}” — it must not look like a hit`);

    const was = await page.evaluate(() => ({ mode, openId, live: cmd.classList.contains('live'), q: q.value }));
    await page.locator('#results .res').filter({ hasText: '3 chunk(s) awaiting embedding' }).click();
    await settle(page);
    assert.deepEqual(await page.evaluate(() => ({ mode, openId, live: cmd.classList.contains('live'), q: q.value })),
      was, 'clicking the __deep__ note did something — there is no object behind that id');
  });
});

// ---------------- Enter ----------------
// Enter is one key with two jobs, split by the row it lands on, and the two tests
// below are the two halves of that. Neither asserts "Enter fired a deep request"
// on its own: the defect being pinned against is a request that is fired and then
// thrown away, which such an assertion would pass.

// Half one. A `by meaning` note is the only row act() refuses, so it is the only
// row where nothing closes the palette, the box still holds the query, and a
// second answer has somewhere to land. That makes Enter on a note "ask again" —
// which matters, because both notes the server sends are transient: an embedder
// that was down may be up, and chunks that were awaiting may be swept.
test('Enter on a `by meaning` note asks again, and the second answer replaces the note on screen', async () => {
  await withPage(browser, 'leap-deep-enter-asks-again', async (page) => {
    const DEGRADED = 'search by meaning is unavailable — the embedder is not answering';
    // The first ask is degraded, the second finds something. Answering the two
    // asks differently is what makes "the second answer was drawn" — rather than
    // fetched and discarded — readable from the rows alone.
    await stubLeap(page, {
      deepRows: (q, nth) => nth === 1
        ? [{ id: '__deep__', note: true, group: 'by meaning', label: DEGRADED, degraded: 'refused' }]
        : [{ id: 'tbl:planets', label: 'DEEP-OBJ ' + q, group: 'by meaning',
             kind: 'table', score: 0.62, touched: 0 }],
    });
    await bootedShell(page, server.url);
    await leapTo(page, 'planets');
    await page.waitForFunction(t => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === t), DEGRADED);

    await selectRow(page, DEGRADED);
    await page.locator('#q').press('Enter');

    await until(async () => (await rowTexts(page)).includes('DEEP-OBJ planets'),
      'the second deep answer to reach the screen — Enter on the note either asked ' +
      'nothing or asked and dropped the answer');
    const rows = await rowTexts(page);
    assert.ok(!rows.includes(DEGRADED),
      `the stale note is still on screen beside the fresh answer: ${JSON.stringify(rows)}`);
    // it had a home only because a note is not something to activate: the palette
    // is still open and still holding the query the answer belongs to
    assert.ok(await page.locator('#results').isVisible(),
      'the palette closed — Enter activated a note, which has no object behind it');
    assert.equal(await page.inputValue('#q'), 'planets',
      'the box no longer holds the query the second answer was for');
    assert.ok(rows.includes('LEX-A planets'),
      `asking again threw the lexical half away: ${JSON.stringify(rows)}`);
  });
});

// Half two, and the reason the call is gated rather than left where it was: on a
// row that acts, Enter must ask nothing. Every acting path in act() calls
// closeLeap(), which empties the box, so deepen()'s staleness check drops that
// answer — the request bought an embedding round trip and a re-chunk of the whole
// substrate for a response nobody reads.
//
// Be plain about the assertion: a discarded response leaves no trace in the DOM,
// by construction, so "nothing was asked" is only legible in the requests. The
// rendered half here is that the row really did activate; the request count is
// the half that fails on the version which asked anyway.
test('Enter on a row that acts activates it and asks nothing by meaning', async () => {
  await withPage(browser, 'leap-deep-enter-acts-only', async (page) => {
    const seen = await stubLeap(page);
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));
    assert.equal(await page.textContent('#crumbName'), 'all notebooks',
      'this test starts from the overview so that entering a notebook is a visible change');

    await leapTo(page, 'planets');
    await until(() => seen.done.length > 0, 'the deep answer the 250 ms pause asks for');
    await page.waitForFunction(() => [...document.querySelectorAll('#results .res .title')]
      .some(el => el.textContent === 'DEEP-SPACE planets'));
    const asked = seen.deep.length;

    // LEX-A is space:cosmos, a lexical notebook hit — act() enters it, and
    // enter() closes the palette
    await selectRow(page, 'LEX-A planets');
    await page.locator('#q').press('Enter');

    await until(async () => (await page.textContent('#crumbName')) === 'The Solar System',
      'the notebook named by the selected row to be entered — Enter did not activate it');
    assert.ok(!(await page.locator('#results').isVisible()),
      'the palette is still open after Enter activated a row');
    await settle(page);
    assert.equal(await page.inputValue('#q'), '',
      'closeLeap() left the query in the box, so this test no longer tests what it says');
    assert.equal(seen.deep.length, asked,
      `Enter asked ${seen.deep.length - asked} further deep question(s) on its way out of ` +
      'the palette. Acting closes the palette, which empties the box, so that answer is ' +
      'dropped by the staleness check in deepen() — the request is an embedding round trip ' +
      'and a full re-chunk spent on a response that is thrown away.');
  });
});
