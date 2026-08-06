// ✧ Suggest is the one verb that asks first. These tests are about the asking:
// that pressing it writes nothing, that what the modal says it will run is what
// it runs, and that refusing a row — by unchecking it or by emptying its
// question — is honoured in the labels AND in the request.
//
// No model is called and no key is needed: /api/suggest, /api/suggest-run and
// /api/job are answered by page.route with canned JSON, so the agent's judgment
// (which is not testable) is out of the picture and the shell's consent
// machinery (which is) is all that is under test. The endpoints hand back a job
// id and the shell then polls /api/job — see pollJob in index.html — so the
// stub has to answer all three or nothing ever completes.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const HUB = 'space:finance';

// What the "agent" proposes. Three rows is the smallest corpus in which
// "refusing one lowers the count" and "the title travels with its own row"
// are both distinguishable.
const PROPOSALS = [
  { title: 'Margin structure', intent: 'where the gross margin comes from',
    query: 'How is gross margin distributed across the product lines?' },
  { title: 'Customer concentration', intent: 'how much rides on the top accounts',
    query: 'What share of revenue comes from the ten largest customers?' },
  { title: 'Working capital', intent: 'the cash the business ties up',
    query: 'How has the cash conversion cycle moved over the last eight quarters?' },
];

// The number the label promises. Read out of the text rather than restated, so
// a label that stops counting fails with the string it actually shows.
const said = (label) => {
  const m = /\bResearch (\d+) in\b/.exec(label);
  assert.ok(m, `a destination button must state its count; it reads “${label}”`);
  return Number(m[1]);
};

// The substrate's own count, straight from the server — never from STATE, which
// a stubbed response could have set to anything.
const events = async () => (await (await fetch(`${server.url}/api/state`)).json()).events;

// Canned agent + canned jobs. Returns the request bodies the shell actually
// sent, which is what every "what was sent" assertion reads.
async function stubAgent(page) {
  const seen = { suggest: [], run: [] };
  const json = (route, body) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

  // Regexes, not globs: "**/api/suggest" and "**/api/suggest-run" are one
  // character apart and a mis-ordered glob would silently answer the wrong one.
  await page.route(/\/api\/suggest$/, async route => {
    seen.suggest.push(route.request().postDataJSON());
    await json(route, { job: 'job-propose' });
  });
  await page.route(/\/api\/suggest-run$/, async route => {
    seen.run.push(route.request().postDataJSON());
    await json(route, { job: 'job-run' });
  });
  await page.route(/\/api\/job\?/, async route => {
    const id = new URL(route.request().url()).searchParams.get('id');
    if (id === 'job-propose') return json(route, { done: true, result: { proposals: PROPOSALS } });
    const last = seen.run[seen.run.length - 1] || { items: [], destination: 'new' };
    // a real state payload, so applyState/safeRebuild run their real path
    const state = await (await fetch(`${server.url}/api/state`)).json();
    return json(route, { done: true, result: {
      state,
      ran: last.items.map((_, k) => last.destination === 'new' ? `space:dd-${k + 1}` : HUB),
      destination: last.destination } });
  });
  return seen;
}

// Focus the hub notebook and hand back its panel index.
async function focusHub(page) {
  const i = await page.evaluate(id => {
    const k = STATE.spaces.findIndex(s => s.id === id);
    if (k >= 0) enter(k);
    return k;
  }, HUB);
  assert.ok(i >= 0, `${HUB} is missing from the fixture`);
  await page.waitForSelector(`#body${i} #sgBtn`);
  return i;
}

// Press ✧ Suggest and wait for the proposals to be on screen. pollJob's first
// tick is 2s out, so this is a wait, not a race.
async function openModal(page, i) {
  await page.click(`#body${i} #sgBtn`);
  await page.waitForFunction(
    n => document.querySelectorAll('#modal.on .sgrow').length === n,
    PROPOSALS.length, { timeout: 30_000 });
}

const labels = (page) => page.evaluate(() => ({
  neu: document.getElementById('sgNew').textContent,
  here: document.getElementById('sgHere').textContent,
  newDisabled: document.getElementById('sgNew').disabled,
  hereDisabled: document.getElementById('sgHere').disabled,
}));

test('pressing ✧ Suggest shows the proposals and writes nothing at all', async () => {
  await withPage(browser, 'suggest-proposes-without-writing', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    const before = await events();

    await openModal(page, i);

    assert.deepEqual(seen.suggest, [{ space: HUB }],
      `✧ Suggest must ask about the focused notebook: ${JSON.stringify(seen.suggest)}`);
    // the rows are the proposals, not a placeholder that happens to be 3 long
    assert.deepEqual(await page.$$eval('#modal .sgrow b', els => els.map(e => e.textContent)),
      PROPOSALS.map(p => p.title));
    assert.deepEqual(await page.$$eval('#modal .sgrow .sgq', els => els.map(e => e.value)),
      PROPOSALS.map(p => p.query));

    const after = await events();
    assert.equal(after, before,
      `proposing committed ${after - before} event(s). ✧ Suggest must touch the substrate ` +
      'not at all — not a reversible event, no event — because you have not agreed to ' +
      'anything yet');
    assert.equal(seen.run.length, 0, 'proposing must not run anything');
    // and the button is usable again, so a dismissed proposal can be re-asked
    assert.equal(await page.textContent(`#body${i} #sgBtn`), '✧ Suggest');
  });
});

test('unchecking a row lowers the count in BOTH destination buttons, and drops it from the run', async () => {
  await withPage(browser, 'suggest-uncheck-lowers-both', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    await openModal(page, i);

    const all = await labels(page);
    assert.equal(said(all.neu), 3, `all three are checked by default: “${all.neu}”`);
    assert.equal(said(all.here), 3, `all three are checked by default: “${all.here}”`);
    assert.equal(all.neu, 'Research 3 in new notebooks');
    assert.equal(all.here, 'Research 3 in this notebook');

    await page.uncheck('#modal .sgrow[data-k="1"] input[type=checkbox]');
    const two = await labels(page);
    assert.equal(said(two.neu), 2,
      `unchecking one of three must leave two in the "new notebooks" button; it reads “${two.neu}”`);
    assert.equal(said(two.here), 2,
      `unchecking one of three must leave two in the "this notebook" button too; it reads “${two.here}”`);
    assert.equal(two.neu, 'Research 2 in new notebooks', 'and the plural still agrees');

    // nothing to research is not a runnable state
    await page.uncheck('#modal .sgrow[data-k="0"] input[type=checkbox]');
    await page.uncheck('#modal .sgrow[data-k="2"] input[type=checkbox]');
    const none = await labels(page);
    assert.equal(said(none.neu), 0, `refusing everything must reach zero: “${none.neu}”`);
    assert.equal(none.neu, 'Research 0 in new notebooks', 'the singular must not creep in at zero');
    assert.ok(none.newDisabled && none.hereDisabled,
      'with nothing checked both destination buttons must be disabled, or a click runs an empty batch');

    // and the label is not merely cosmetic: what is sent matches it
    await page.check('#modal .sgrow[data-k="0"] input[type=checkbox]');
    await page.check('#modal .sgrow[data-k="2"] input[type=checkbox]');
    await page.click('#sgNew');
    await page.waitForFunction(() => document.getElementById('toast').classList.contains('on'),
      null, { timeout: 30_000 });
    assert.equal(seen.run.length, 1, `exactly one run request: ${JSON.stringify(seen.run)}`);
    assert.deepEqual(seen.run[0].items.map(x => x.title), ['Margin structure', 'Working capital'],
      'the unchecked row was sent anyway — the label said 2 and the request carried ' +
      JSON.stringify(seen.run[0].items.map(x => x.title)));
  });
});

test('emptying a question refuses it: both counts fall and it is not sent', async () => {
  await withPage(browser, 'suggest-blank-question-refuses', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    await openModal(page, i);

    // still checked — the refusal is the empty field, nothing else
    await page.fill('#modal .sgrow[data-k="0"] .sgq', '   ');
    assert.equal(await page.isChecked('#modal .sgrow[data-k="0"] input[type=checkbox]'), true,
      'this test is only meaningful while the row is still checked');

    const two = await labels(page);
    assert.equal(said(two.neu), 2,
      `a question edited to blank is not a research question, so it must leave the count at ` +
      `2; the "new notebooks" button reads “${two.neu}”`);
    assert.equal(said(two.here), 2,
      `the "this notebook" button must fall with it; it reads “${two.here}”`);

    await page.click('#sgHere');
    await page.waitForFunction(() => document.getElementById('toast').classList.contains('on'),
      null, { timeout: 30_000 });
    assert.equal(seen.run.length, 1);
    assert.deepEqual(seen.run[0].items.map(x => x.title),
      ['Customer concentration', 'Working capital'],
      'a blanked question was sent as an empty prompt: ' + JSON.stringify(seen.run[0].items));
    assert.ok(seen.run[0].items.every(x => x.query.trim().length > 0),
      'an item with an empty query reached the server: ' + JSON.stringify(seen.run[0].items));
  });
});

test('an edited question is what gets sent — asserted on the request, not the screen', async () => {
  await withPage(browser, 'suggest-edit-changes-request', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    await openModal(page, i);

    const EDITED = 'What share of revenue comes from the three largest customers, by segment?';
    await page.fill('#modal .sgrow[data-k="1"] .sgq', EDITED);
    await page.click('#sgNew');
    await page.waitForFunction(() => document.getElementById('toast').classList.contains('on'),
      null, { timeout: 30_000 });

    assert.equal(seen.run.length, 1, `exactly one run request: ${JSON.stringify(seen.run)}`);
    const body = seen.run[0];
    assert.equal(body.space, HUB);
    assert.equal(body.items.length, 3);
    const edited = body.items.find(x => x.title === 'Customer concentration');
    assert.ok(edited, `the edited row lost its title: ${JSON.stringify(body.items)}`);
    assert.equal(edited.query, EDITED,
      'the server was sent the agent\'s original wording, not yours. Editing a question that ' +
      'is then ignored is worse than not offering the field');
    assert.equal(edited.intent, PROPOSALS[1].intent,
      'the intent must still travel with its own row');
    assert.ok(!JSON.stringify(body).includes(PROPOSALS[1].query),
      `the original wording is still in the request body: ${JSON.stringify(body)}`);
    // the rows the user left alone are unchanged, so editing one is not editing all
    assert.equal(body.items.find(x => x.title === 'Margin structure').query, PROPOSALS[0].query);
    assert.equal(body.items.find(x => x.title === 'Working capital').query, PROPOSALS[2].query);
  });
});

test('Dismiss closes the modal, runs nothing and leaves the substrate untouched', async () => {
  await withPage(browser, 'suggest-dismiss-writes-nothing', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    await openModal(page, i);
    // measured from the open modal, so this is about Dismiss and not about the
    // press that got here — that is the test above
    const before = await events();

    await page.click('#sgDismiss');
    await page.waitForFunction(() => !document.getElementById('modal').classList.contains('on'),
      null, { timeout: 10_000 });
    assert.equal(await page.$$eval('#modal .sgrow', els => els.length), 0,
      'Dismiss hid the modal but left its rows in the DOM');

    // The load-bearing one: with the endpoints stubbed, a Dismiss that secretly
    // ran the batch would still leave the event count flat, so the proof that
    // nothing ran is that nothing was ASKED to run.
    assert.deepEqual(seen.run, [],
      'Dismiss sent a run request: ' + JSON.stringify(seen.run));
    assert.equal(await events(), before, 'Dismiss must leave the substrate exactly as it was');
    assert.equal(await page.evaluate(() => document.getElementById('toast').classList.contains('on')),
      false, 'Dismiss reported something happening; nothing happened');
  });
});

test('the two destinations are two buttons, not one button and a toggle', async () => {
  await withPage(browser, 'suggest-two-distinct-destinations', async (page) => {
    const seen = await stubAgent(page);
    await bootedShell(page, server.url);
    const i = await focusHub(page);
    await openModal(page, i);

    // both are live and reachable at the same instant: nothing has to be flipped
    // first, and neither is a mode the other sits inside
    const shape = await page.evaluate(() => {
      const a = document.getElementById('sgNew'), b = document.getElementById('sgHere');
      return { distinct: a !== b, tags: [a.tagName, b.tagName],
               enabled: [!a.disabled, !b.disabled],
               inputs: [...document.querySelectorAll('#modal .modal-f input, #modal .modal-f select')].length };
    });
    assert.ok(shape.distinct, 'the two destinations are the same element');
    assert.deepEqual(shape.tags, ['BUTTON', 'BUTTON']);
    assert.deepEqual(shape.enabled, [true, true], 'both destinations must be live at once');
    assert.equal(shape.inputs, 0,
      'the footer carries a control that could hold a destination — that is a mode');

    await page.click('#sgHere');
    await page.waitForFunction(() => document.getElementById('toast').classList.contains('on'),
      null, { timeout: 30_000 });
    assert.match(await page.textContent('#toast'), /here/,
      'researching in this notebook must not report spawning notebooks');

    // ask again and take the other road; nothing was toggled in between.
    // (the toast fires while safeRebuild's notebook fetch is still in flight —
    // let the panel settle before clicking into it again)
    await page.waitForLoadState('networkidle');
    const j = await focusHub(page);
    await openModal(page, j);
    await page.click('#sgNew');
    await page.waitForFunction(() => /Spawned/.test(document.getElementById('toast').textContent),
      null, { timeout: 30_000 });

    assert.deepEqual(seen.run.map(r => r.destination), ['here', 'new'],
      'the destination is decided by which button was pressed, and by nothing else: ' +
      JSON.stringify(seen.run.map(r => r.destination)));
    assert.deepEqual(seen.run.map(r => r.items.length), [3, 3],
      'both runs carried the same approved batch, so only the destination differed');
  });
});
