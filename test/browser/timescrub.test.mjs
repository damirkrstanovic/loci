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
