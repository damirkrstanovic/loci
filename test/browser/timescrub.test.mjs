// The time scrubber, driven the way a hand drives it.
//
// Reported 2026-08-10: "the time slider is finicky to use via mouse — it changes
// size, i cannot do fine control, if i slide too little nothing happens, there is
// no sticky to a point in time."
//
// Three of those four are ONE mechanism. `.timebar` is a fixed-width pill, and the
// label inside it sizes to its own text — text that changes on every scrub, from
// `now · 31 events` to `event 17/31 · ＋ The Solar System — a tour · 23/07/2026,
// 21:07`. While the label shared a row with the track, the track absorbed the
// difference: measured at 575px at one end and 392px in the middle, live, mid-drag.
// A scale that moves under the cursor is exactly what "it changes size", "I cannot
// do fine control" and "I slide a little and nothing happens" feel like.
//
// So the load-bearing test here measures the TRACK, at three slider positions, and
// asserts the three are the same — plus that the three labels genuinely differ. A
// label that never changed would also never resize anything, and the width
// assertion would then pass for a reason that has nothing to do with the fix.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// A gesture that never lands fails HERE, in five seconds with a sentence, rather
// than thirty seconds later in a waitForSelector whose only message is that a
// selector never appeared — with every assertion below it never running. The
// entities sweep measured that shape at 31.6s, three times.
const until = async (fn, why, ms = 5000) => {
  const t0 = Date.now();
  for (;;) {
    if (await fn()) return;
    if (Date.now() - t0 > ms) throw new Error(why);
    await new Promise(r => setTimeout(r, 50));
  }
};

const enterTime = async page => {
  await page.click('#timeBtn');
  await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME !== null);
};

// Drive the slider the way the browser does — `input`, not `change` — and wait for
// the debounced settle to have landed by watching the event count the payload
// carries. `state-payload`'s :events IS the prefix length, so `STATE.events === n`
// says "the world you are looking at is the world at event n", with no timeout.
// Not page.waitForFunction: measured, a slider that snaps (step≠1) turns this into
// a 30-second wall whose only message is that a predicate stayed false, with the
// assertions below it never running. Both halves of the wait say what went wrong
// instead — the value the track accepted, and the world failing to follow it.
const scrubTo = async (page, n) => {
  const took = await page.evaluate(x => {
    const r = document.getElementById('timeRange');
    r.value = String(x);
    r.dispatchEvent(new Event('input', { bubbles: true }));
    return +r.value;
  }, n);
  assert.equal(took, n,
    `the track refused event ${n} and snapped to ${took} — step is no longer 1, so ` +
    `there are points in the log you cannot stand on`);
  await until(async () => (await page.evaluate(() => STATE.events)) === n,
    `the world never arrived at event ${n}: the debounced settle never landed, or ` +
    `it landed on a different point than the thumb is at`);
};

// The doc: prose plus a `## Sources` list, appended to space:cosmos by the
// harness fixture in ONE :tx — so a single event separates "in a notebook" from
// "does not exist", which is exactly the boundary the fallback has to notice.
const DOC = 'doc:sources', DOC_TITLE = 'Sources — fixture';

const stateAt = async n => (await fetch(`${server.url}/api/state?at=${n}`)).json();
// Openable = the object exists AND some notebook at that point holds it. Both
// halves matter: the molder draws INSIDE a notebook panel, and the seed puts
// every document before the spaces that reference them.
const openableIn = (s, id) => s.objects.some(o => o.id === id) &&
                              s.spaces.some(sp => sp.members.includes(id));
// Nothing in this fixture is ever deleted, so "openable" is monotone in n and a
// binary search finds the event that created it — six requests instead of forty,
// and no hard-coded index to rot the next time the fixture grows a line.
const bornAt = async (id, total) => {
  let lo = 0, hi = total;                       // openableIn(lo)=false, openableIn(hi)=true
  assert.ok(openableIn(await stateAt(hi), id), `${id} is not in the fixture at all`);
  assert.ok(!openableIn(await stateAt(lo), id), `${id} exists before the log starts`);
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (openableIn(await stateAt(mid), id)) hi = mid; else lo = mid;
  }
  return hi;
};

// Set the slider and read the bar back in the SAME frame: `paintTime` writes the
// label synchronously inside `scrubTo` (only the state fetch is debounced), and
// getBoundingClientRect forces the layout that the new label would have caused.
// So this returns the track width the drag would have had at that position.
const atPosition = (page, v) => page.evaluate(x => {
  const r = document.getElementById('timeRange');
  r.value = String(x);
  r.dispatchEvent(new Event('input', { bubbles: true }));
  return { n: x, w: r.getBoundingClientRect().width,
           label: document.getElementById('timeLabel').textContent };
}, v);

test('the track is the same width wherever the thumb is', async () => {
  await withPage(browser, 'timescrub-track-holds-still', async (page) => {
    await bootedShell(page, server.url);
    await enterTime(page);

    const max = Number(await page.getAttribute('#timeRange', 'max'));
    assert.ok(max > 4, `the fixture has no history to scrub: max=${max}`);

    // the two ends and the middle: `now · N events` is the shortest caption the
    // bar ever shows and a mid-log event is the longest, so if the track moves at
    // all, it moves between these three.
    const ends = await atPosition(page, max);
    const mid = await atPosition(page, Math.floor(max / 2));
    const start = await atPosition(page, 0);
    const seen = [ends, mid, start];
    const show = seen.map(s => `n=${s.n}: track ${s.w.toFixed(1)}px ` +
      `(${(s.w / max).toFixed(2)} px per event) · "${s.label}"`).join('\n  ');

    // the labels first: this is what stops the width assertion below from passing
    // because nothing about the bar ever changed
    const labels = new Set(seen.map(s => s.label));
    assert.equal(labels.size, 3,
      `the three positions do not read differently, so a track that resizes with its\n` +
      `label would still pass the width check below:\n  ${show}`);

    const widths = seen.map(s => s.w);
    const spread = Math.max(...widths) - Math.min(...widths);
    assert.ok(spread <= 0.5,
      `the track resizes by ${spread.toFixed(1)}px while you drag it — the pixel↔event\n` +
      `scale moves under the cursor, which is the whole complaint:\n  ${show}`);
  });
});

// ── scrubbing keeps your place ─────────────────────────────────────────────

// `scrubTo` ran `mode='overview'; openId=null` on every settle, so watching one
// document change across the log — the main reason to have a scrubber at all —
// was impossible: the first millimetre of drag closed it.
test('scrubbing back does not close the document you were reading', async () => {
  await withPage(browser, 'timescrub-keeps-your-place', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(id => openObject(id), DOC);
    await until(async () => (await page.locator('.molder .t').count()) > 0,
      'the document never opened in the present — the fixture, not the fix');
    assert.equal((await page.textContent('.molder .t')).trim(), DOC_TITLE);

    await enterTime(page);
    const total = Number(await page.getAttribute('#timeRange', 'max'));
    // one event back, and that event is a tag being set — it removes nothing
    await scrubTo(page, total - 1);

    await until(async () => (await page.locator('.molder .t').count()) > 0,
      `scrubbing one event back closed the document: the panel is showing something\n` +
      `other than the molder, so you cannot watch anything change over time`);
    assert.equal((await page.textContent('.molder .t')).trim(), DOC_TITLE,
      'a molder is open, but not on the document that was open before the scrub');
    // and it is drawn at the point we are standing, not left over from the present
    assert.equal(await page.evaluate(() => STATE.events), total - 1);
  });
});

test('scrubbing past a document’s creation falls back to the overview and says so', async () => {
  await withPage(browser, 'timescrub-fallback-is-loud', async (page) => {
    await bootedShell(page, server.url);
    const total = Number((await stateAt(1e9)).events);
    const born = await bornAt(DOC, total);

    await page.evaluate(id => openObject(id), DOC);
    await until(async () => (await page.locator('.molder .t').count()) > 0,
      'the document never opened in the present — the fixture, not the fix');

    await enterTime(page);
    await scrubTo(page, born - 1);        // one event before it existed at all

    // The toast first, because it is the part that is time-limited on screen, and
    // because a silent fallback is the defect: /api/object answers a missing object
    // with {title:null, kind:"data", rendered:null} and the molder draws an empty
    // header over the word `null` — the same silent emptiness the entities sweep
    // fixed at a different door.
    await until(async () => {
      const t = page.locator('.toast.on');
      return (await t.count()) > 0 && (await t.innerText()).includes(DOC_TITLE);
    }, `nothing said why the document went away — the panel just emptied. The toast\n` +
       `has to name "${DOC_TITLE}" at the moment it is dropped.`);

    // …and it has to be READABLE next to the thing it is about. The toast and the
    // bar both live at the bottom of the screen, and the bar grew a row when the
    // label moved off the track — measured on a hand-drive, the message landed on
    // top of the track. Geometry is the only thing that can see this: both elements
    // are present and correct, they are just in the same place.
    //
    // Measured ONCE, when the slide-in has settled — NOT by polling for the answer
    // we want. The toast takes itself down after 2.5s, so a poll that never sees
    // what it is waiting for ends by dereferencing a toast that is gone: measured,
    // a TypeError naming nothing where the assertion should have been.
    const box = await page.evaluate(() => new Promise(resolve => {
      const t = document.querySelector('.toast.on');
      if (!t) return resolve(null);
      let last = t.getBoundingClientRect().top, still = 0, frames = 0;
      const tick = () => {
        const now = t.getBoundingClientRect().top;
        still = Math.abs(now - last) < 0.1 ? still + 1 : 0;
        last = now;
        if (still >= 3 || ++frames > 120) {
          const a = t.getBoundingClientRect(), r = document.getElementById('timeRange').getBoundingClientRect();
          return resolve({ toast: [a.top, a.bottom], track: [r.top, r.bottom] });
        }
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    }));
    assert.ok(box, 'the toast was already gone by the time it could be measured');
    const [tt, tb] = box.toast, [rt, rb] = box.track;
    assert.ok(!(tb > rt && tt < rb),
      `the toast is drawn over the track it is telling you about — you cannot see ` +
      `where the thumb went while it is up. toast ${tt.toFixed(0)}–${tb.toFixed(0)}, ` +
      `track ${rt.toFixed(0)}–${rb.toFixed(0)}`);

    // Structure, deliberately, and this is the exception to "assert on text": an
    // EMPTY molder is textually identical to no molder at all, and "this should not
    // have been drawn" is a claim nothing but the DOM can settle.
    //
    // BOTH counts, because the two half-fixes fail differently and the first
    // version of this test could only see one of them. Leave `openId` set while
    // switching to the overview and no molder is ever drawn: renderBody draws the
    // detail for an open id whatever the mode is, and with no payload in hand that
    // is the `loading…` stub — a panel that says it is fetching something, forever.
    // Measured: that sabotage passed a bare `.molder === 0`.
    const left = await page.evaluate(() => ({
      molders: document.querySelectorAll('.molder').length,
      loading: [...document.querySelectorAll('.p-body')]
        .filter(b => b.innerText.trim() === 'loading…').length,
    }));
    assert.deepEqual(left, { molders: 0, loading: 0 },
      'the fallback left something behind in the panel: a molder drawn over a null ' +
      'payload, or a body stuck on “loading…” because the open id was never cleared');

    // …and last, because a fallback that clears the open id but stays in the
    // focused notebook leaves neither of the two above: "the overview is showing"
    // in the shell's own words, not in a class name.
    assert.equal((await page.textContent('#crumbName')).trim(), 'all notebooks',
      'the fallback left the shell in a focused notebook rather than the overview');
  });
});

// ── aiming ─────────────────────────────────────────────────────────────────

// "I cannot do fine control" has a better answer than pixels: a range input
// already answers ← → with exactly one step, and `step` is already 1. What was
// missing is that nothing said so and nothing had focus, so the keys did nothing
// until you had clicked the track — which is the gesture you were trying to avoid.
// One press per event beats 5.9px of mouse travel at any log length.
test('the slider takes the arrow keys the moment time opens, and says so', async () => {
  await withPage(browser, 'timescrub-arrow-keys', async (page) => {
    await bootedShell(page, server.url);
    await enterTime(page);

    assert.equal(await page.evaluate(() => document.activeElement.id), 'timeRange',
      'the track does not have focus on the way in, so ← → do nothing until you ' +
      'click it first — and clicking the track is the gesture being replaced');

    // the hint, in the bar itself: a key that works but is never mentioned is a
    // key nobody presses
    const bar = await page.innerText('#timebar');
    assert.ok(/←\s*→/.test(bar) && /event/.test(bar),
      `the bar never mentions the keys, so only someone who already knew would\n` +
      `press them:\n  ${JSON.stringify(bar)}`);

    const value = () => page.evaluate(() => +document.getElementById('timeRange').value);
    const before = await value();
    await page.keyboard.press('ArrowLeft');
    await until(async () => (await value()) !== before,
      'ArrowLeft moved nothing at all — the key never reached the track');
    assert.equal(await value(), before - 1,
      'one press is not one event: fine control is exactly what this is for');
    assert.equal(await page.evaluate(() => TIME.n), before - 1,
      'the thumb moved but the world did not follow it');

    // Escape is the shell's way out of the past, and focusing the track put an
    // INPUT in front of the global handler — which blurs a focused field and
    // returns. Without a way through, ⏱ would need two Escapes to leave.
    await page.keyboard.press('Escape');
    await until(() => page.evaluate(() => TIME === null),
      'Escape did not leave the past: the focused slider swallowed it, and the ' +
      'first press now only blurs the field');
  });
});
