// The flow card as a PLAN, and the flows pane as its home.
//
// Three complaints, 2026-08-16: the card did not read as a plan, the status was
// the bare word "running" with no sense of where it had got to, and a flow
// waiting at a gate in a notebook you were not standing in was invisible.
//
// The card tests call `renderFlow` directly. It is a global, and driving it is
// how a gate — which the seeded fixture has none of — gets exercised without
// adding a fixture that other suites would have to account for.
//
// Weight is asserted through COMPUTED STYLE, not class names. The whole change
// is "done recedes, current is full strength, future is light"; a class
// assertion would pass on a rule that no longer applies to anything, which is
// the failure mode memorypane.test.mjs records from 2026-08-06.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

const FLOW = {
  goal: 'compare the solar system to other star systems',
  status: 'needs-you',
  started: Date.now() - 67000,
  steps: [
    { verb: 'research', note: 'gather a unifying dataset', status: 'done', out: 'tbl:t' },
    { verb: 'gate', args: { question: 'Proceed to compute a comparison?' }, status: 'needs-you' },
    { verb: 'compute', note: 'transform detection data', status: 'pending' },
    { verb: 'draft', note: 'write a short brief', status: 'pending' },
  ],
};

const draw = (page, flow) => page.evaluate((f) => {
  const el = document.createElement('div');
  el.id = 'probe';
  el.innerHTML = renderFlow(f, 'flow:probe');
  document.body.appendChild(el);
}, flow);

test('the card states the plan: how many steps, which one, how long', async () => {
  await withPage(browser, 'flowplan-header', async (page) => {
    await bootedShell(page, server.url);
    await draw(page, FLOW);
    const line = (await page.textContent('#probe .flowplan')).trim();
    assert.match(line, /4 STEPS/, `the plan must say its length: ${line}`);
    assert.match(line, /NEEDS YOU/, `and that it is waiting: ${line}`);
    assert.match(line, /STEP 2/, `and where it got to: ${line}`);
    // the SHAPE, not a value: `started` is stamped when this file loads and the
    // page renders seconds later, so an exact figure would drift with machine
    // speed. Exact formatting is pinned in the next test, off the clock.
    assert.match(line, /\d+m \d+s$|\d+s$/, `and how long it has been going: ${line}`);
  });
});

test('elapsed reads in minutes and seconds, and is absent before a flow starts', async () => {
  await withPage(browser, 'flowplan-elapsed', async (page) => {
    await bootedShell(page, server.url);
    assert.deepEqual(
      await page.evaluate(() => [
        fmtElapsed(1000, 1000),           // no time at all
        fmtElapsed(1000, 46000),          // under a minute stays in seconds
        fmtElapsed(1000, 68000),          // over it, minutes and seconds
        fmtElapsed(1000, 3661000),
        fmtElapsed(null),                 // never started — say nothing
      ]),
      ['0s', '45s', '1m 7s', '61m 0s', '']);
  });
});

test('the steps are numbered, so position is stated rather than counted', async () => {
  await withPage(browser, 'flowplan-numbers', async (page) => {
    await bootedShell(page, server.url);
    await draw(page, FLOW);
    assert.deepEqual(
      await page.locator('#probe .fnum').allTextContents(),
      ['1', '2', '3', '4']);
  });
});

test('done recedes, the current step is loud, the future is lighter still', async () => {
  await withPage(browser, 'flowplan-weight', async (page) => {
    await bootedShell(page, server.url);
    await draw(page, FLOW);
    const weight = i => page.evaluate((n) => {
      const v = document.querySelectorAll('#probe .flowstep')[n].querySelector('.fverb');
      const cs = getComputedStyle(v);
      return { color: cs.color, weight: cs.fontWeight, opacity: +getComputedStyle(v).opacity };
    }, i);
    const [doneS, gateS, futureS] = [await weight(0), await weight(1), await weight(2)];
    assert.notEqual(doneS.color, gateS.color,
      'a finished step and the one waiting on you must not look the same');
    assert.notEqual(futureS.color + futureS.opacity, doneS.color + doneS.opacity,
      'a step that has happened must not look identical to one merely planned');
    assert.ok(+gateS.weight >= 600, `the step needing you should be bold: ${gateS.weight}`);
  });
});

test('a gate is marked before you reach it, and says what it will ask', async () => {
  await withPage(browser, 'flowplan-gate', async (page) => {
    await bootedShell(page, server.url);
    await draw(page, FLOW);
    const txt = await page.textContent('#probe');
    assert.match(txt, /Proceed to compute a comparison\?/,
      'the gate shows its question in the plan, not only when it fires');
    assert.match(txt, /NEEDS YOU/);
    // the gate's verb is coloured apart from an ordinary step's
    const [gate, plain] = await page.evaluate(() => {
      const s = document.querySelectorAll('#probe .flowstep');
      return [getComputedStyle(s[1].querySelector('.fverb')).color,
              getComputedStyle(s[2].querySelector('.fverb')).color];
    });
    assert.notEqual(gate, plain, 'a gate must not look like any other step');
  });
});

test('a running flow says which step it is on, not just "running"', async () => {
  await withPage(browser, 'flowplan-running', async (page) => {
    await bootedShell(page, server.url);
    await draw(page, { ...FLOW, status: 'running',
      steps: FLOW.steps.map((s, i) => i === 1 ? { ...s, verb: 'compute', status: 'running' } : s) });
    const line = (await page.textContent('#probe .flowplan')).trim();
    assert.match(line, /RUNNING STEP 2/, `bare "running" was the complaint: ${line}`);
  });
});

test('the flows pane lists every flow and puts the waiting one first', async () => {
  await withPage(browser, 'flowpane-list', async (page) => {
    await bootedShell(page, server.url);
    await page.route(/\/api\/flows$/, r => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ needs_you: 1, flows: [
        { id: 'flow:9', goal: 'the one waiting', space: 'space:cosmos', status: 'needs-you',
          steps: 4, done: 1, step: 'gate', question: 'Proceed to compute?', started: Date.now() - 5000 },
        { id: 'flow:8', goal: 'an older finished one', space: 'space:cosmos', status: 'done',
          steps: 3, done: 3, started: Date.now() - 90000, ended: Date.now() - 30000 },
      ] }) }));
    await page.click('#flowBtn');
    await page.waitForSelector('.flowrow');
    const rows = await page.locator('.flowrow').allTextContents();
    assert.equal(rows.length, 2);
    assert.match(rows[0], /the one waiting/, 'the flow needing you must come first');
    assert.match(rows[0], /Proceed to compute\?/, 'and say what it is asking');
    assert.match(rows[1], /1m 0s|60s/, 'a finished flow reports how long it took');
    assert.equal(await page.locator('.flowrow.needs').count(), 1);
  });
});

test('the button carries a count when a flow is waiting on you', async () => {
  await withPage(browser, 'flowpane-badge', async (page) => {
    await bootedShell(page, server.url);
    // hidden when nothing waits
    assert.ok(await page.locator('#flowBadge').isHidden(), 'no badge with nothing waiting');
    await page.evaluate(() => { STATE['flows-need-you'] = 2; renderFlowBadge(); });
    assert.equal((await page.textContent('#flowBadge')).trim(), '2');
    assert.ok(await page.locator('#flowBadge').isVisible());
  });
});
