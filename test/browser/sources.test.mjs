// A passthrough source has three faces, and the shell drew two of them as the
// italic word "empty" — indistinguishable from a table somebody emptied on
// purpose, and from each other. reclaimed.test.mjs pins the same defect for a
// table whose rows the store reclaimed; this is the other half of it, and the
// halves differ in the thing that matters: a reclaimed table is PERMANENT, a
// broken source is FIXABLE. So these blocks end in a button.
//
// The server ships the state structurally (`source-block` in server.clj) rather
// than leaving the shell to read it out of `unusable`'s English. What this file
// pins is that the shell branches on that state and offers the repair.
//
// Its own server and its own substrate — see SOURCES_FIXTURE in harness.mjs.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, SOURCES_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: SOURCES_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

// By title and not by a hard-coded `tbl:live_source`: `mint-id` slugs the title
// and suffixes on collision, so the id is derived and the title is the fixture's
// own word. A miss throws here, where it reads as "the fixture did not land",
// rather than as a selector timeout thirty seconds later.
const open = async (page, title) => {
  await bootedShell(page, server.url);
  const id = await page.evaluate(t => {
    const o = Object.values(STATE.objects).find(x => x.title === t);
    if (!o) throw new Error('no object titled ' + t + ' — SOURCES_FIXTURE did not land');
    return o.id;
  }, title);
  await page.evaluate(i => openObject(i), id);
  // the molder BODY, not `.srcblock` — the body renders whatever the page chose
  // to draw, so a page that went back to drawing an empty table fails on what it
  // drew instead of on a selector timeout
  await page.waitForSelector('.molder .molder-b');
  return id;
};

test('a healthy passthrough table says it reads live', async () => {
  await withPage(browser, 'sources-live', async (page) => {
    const id = await open(page, 'Live source');

    // the payload first, so a failure below separates "the server stopped saying
    // it" from "the page stopped drawing it"
    const p = await page.evaluate(i => API.object(i), id);
    assert.equal(p.source && p.source.state, 'live', 'the payload no longer says the source is live');
    assert.equal(p.source.mode, 'passthrough');

    assert.equal(await page.locator('.molder-b .livebadge').count(), 1,
      'a live passthrough table draws no badge — the reader cannot tell it from a copy');
    const badge = (await page.textContent('.molder-b .livebadge')).trim();
    assert.match(badge, /live/i, `the badge does not say the source is live: ${badge}`);
    assert.match(badge, /live\.csv/, `the badge does not say which file it reads: ${badge}`);

    // beside the rows, never instead of them: passthrough says how a table is
    // STORED, never whether it is well
    const body = (await page.textContent('.molder-b')).trim();
    assert.equal(await page.locator('.molder-b table.mold').count(), 1,
      'the badge replaced the table instead of sitting above it');
    assert.match(body, /EMEA/, 'and still shows its rows');
    assert.match(body, /APAC/);
  });
});

test('a source whose file is gone says so and offers a reconnect', async () => {
  await withPage(browser, 'sources-gone', async (page) => {
    await open(page, 'Gone source');
    const body = (await page.textContent('.molder-b')).trim();

    assert.match(body, /unreachable/i,
      `the page does not say the source is unreachable — it drew: ${JSON.stringify(body)}`);
    assert.doesNotMatch(body, /^\s*empty\s*$/i, 'still drawing the italic word "empty"');
    assert.equal(await page.locator('.molder-b table.mold').count(), 0,
      'a table with no rows in it is the answer this page exists not to give');
    assert.match(body, /gone\.csv/, `it does not say which file is missing: ${body}`);
    assert.match(body, /revenue/, 'it does not say what the table was connected as');

    // the repair. A reclaimed table is permanent and ends in a sentence; this
    // one is fixable, which is the whole reason it is not that block.
    assert.equal(await page.locator('.molder-b [data-a="reconnect"]').count(), 1,
      'no repair is offered for a state a person can actually repair');
  });
});

test('a drifted source names both column sets and offers a refresh', async () => {
  await withPage(browser, 'sources-drift', async (page) => {
    await open(page, 'Drifted source');
    // `.srcnow` arrives on a second request — /api/source/describe, a real dry
    // run, answering with the file's shape as it is NOW. 8s and not the 30s
    // default so a sabotage of this block fails fast.
    await page.waitForSelector('.molder-b .srcnow', { timeout: 8000 });

    const body = (await page.textContent('.molder-b')).trim();
    assert.match(body, /columns changed/i,
      `the page does not say the columns changed — it drew: ${JSON.stringify(body)}`);
    assert.equal(await page.locator('.molder-b table.mold').count(), 0);

    // Both column sets, each scoped to its own element — asserting them over the
    // whole body would pass on `unusable`'s sentence alone, which names both and
    // is prose. Reading data back out of prose is the thing this design refuses
    // to do; the chips below come from /api/source/describe.
    const was = (await page.textContent('.molder-b .srcwas')).trim();
    assert.match(was, /revenue/, `the columns it was connected as are not drawn: ${was}`);
    const now = (await page.textContent('.molder-b .srcnow')).trim();
    assert.match(now, /takings/, `the columns the file has NOW are not drawn: ${now}`);
    assert.match(now, /area/);
    assert.doesNotMatch(now, /revenue/,
      `the "now" columns are the ones it was connected as, not the file's: ${now}`);

    assert.equal(await page.locator('.molder-b [data-a="refresh"]').count(), 1,
      'refresh — adopt the new columns — is the repair a drift wants, and it is not offered');
  });
});

// The other caller. `renderMold` serves the molder and every notebook cell, so
// a cell draws these blocks too — and `wireSourceButtons` is called from
// renderNotebook for that exact reason. reclaimed.test.mjs asserts both
// surfaces on the same argument, and the second half of it is what this pins:
// markup that renders identically proves nothing about listeners attached by a
// different function.
//
// The click is `reconnect` and not `refresh` deliberately. Reconnect opens the
// import panel and writes NOTHING, so this test stays read-only and the file
// stays order-independent; a refresh click-through mutates the fixture and
// belongs last in the file, the way storage.test.mjs orders its mutating tests.
test('a notebook cell draws the same faces, and its buttons are wired there too', async () => {
  await withPage(browser, 'sources-cells', async (page) => {
    await bootedShell(page, server.url);
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing — SOURCES_FIXTURE connects all three tables into it');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .cellmold .bd`);

    // space:cosmos carries the seeded cells as well as ours, so a cell is
    // reached through the object it references and never by position.
    const cellFor = async (title) => {
      const id = await page.evaluate(t => {
        const o = Object.values(STATE.objects).find(x => x.title === t);
        if (!o) throw new Error('no object titled ' + t + ' — SOURCES_FIXTURE did not land');
        return o.id;
      }, title);
      const c = page.locator(`#body${i} .cellmold`, { has: page.locator(`[data-open="${id}"]`) });
      assert.equal(await c.count(), 1, `${title} has no cell in space:cosmos`);
      return c;
    };

    // 1. the badge, drawn by the other caller
    const live = await cellFor('Live source');
    assert.equal(await live.locator('.livebadge').count(), 1,
      'a live source draws its badge in the molder but not in a cell');
    assert.match((await live.locator('.bd').textContent()).trim(), /EMEA/,
      'and the cell still shows its rows');

    // 2. and a broken one draws its block rather than the word empty
    const gone = await cellFor('Gone source');
    const t = (await gone.locator('.bd').textContent()).trim();
    assert.match(t, /unreachable/i, `the cell does not say it — it drew: ${JSON.stringify(t)}`);
    assert.doesNotMatch(t, /^\s*empty\s*$/i, 'still drawing the italic word "empty"');

    // 3. the repair is LIVE here, not just drawn. Without this the test passes
    // on markup alone, and renderNotebook could stop wiring the buttons
    // altogether without a single assertion moving.
    await gone.locator('[data-a="reconnect"]').click();
    // The ＋ Data panel, on its file tab: reconnecting IS picking a file, so it
    // is the same door and not a second one. This waited for `#csv` while paste
    // was the only import surface there was; the listing is the stronger wait
    // anyway, because it only appears once /api/source/files has answered.
    await page.waitForSelector(`#body${i} .datapanel [data-file]`, { timeout: 8000 });
  });
});

// A passthrough table's handle IS the recipe — every read inlines
// `read_csv('…')` against whatever bytes sit on disk right now. `as-of` rewinds
// loci's LOG, never the file, so scrubbed into the past there is no other
// answer than today's file: the badge is where the page has to admit that,
// rather than silently keep saying "live source" as though the rows on screen
// were the rows AT that point.
//
// Driven through the real controls — #timeBtn and #timeRange, the same pair
// timescrub.test.mjs drives — and never `window.TIME` set by hand: a test that
// pokes state the app never produces on its own would prove nothing about
// liveBadge.
// `why` may be a plain string or an async thunk — the two calls below that pass
// a thunk want the CURRENT badge text at the moment of timeout, not the one at
// the moment `until` was called (which would always say "live source", since
// that is what triggered the wait in the first place).
const until = async (fn, why, ms = 5000) => {
  const t0 = Date.now();
  for (;;) {
    if (await fn()) return;
    if (Date.now() - t0 > ms) throw new Error(typeof why === 'function' ? await why() : why);
    await new Promise(r => setTimeout(r, 50));
  }
};

const scrubTo = async (page, n) => {
  await page.evaluate(x => {
    const r = document.getElementById('timeRange');
    r.value = String(x);
    r.dispatchEvent(new Event('input', { bubbles: true }));
  }, n);
  await until(async () => (await page.evaluate(() => TIME && TIME.n)) === n,
    `the scrubber never settled on event ${n}`);
};

test('a live badge admits it cannot scrub, and stops admitting it back at now', async () => {
  await withPage(browser, 'sources-badge-time', async (page) => {
    await open(page, 'Live source');
    const badgeText = () => page.textContent('.molder-b .badge').then(t => t.trim());
    const pastCount = () => page.locator('.molder-b .badge.past').count();

    const plain = await badgeText();
    assert.doesNotMatch(plain, /not the contents|showing current/i,
      `a live badge at "now", before anything was scrubbed, already talks about the past: ${plain}`);
    assert.equal(await pastCount(), 0, `the badge already carries .past before scrubbing anywhere: ${plain}`);

    // into the past, through the real slider
    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME !== null);
    const total = Number(await page.getAttribute('#timeRange', 'max'));
    await scrubTo(page, total - 1);

    // timeCaption() is the shell's own phrase for this exact point in time — the
    // task says reuse it rather than reformat TIME by hand, so it is also this
    // test's oracle for what the badge ought to quote back.
    const caption = await page.evaluate(() => timeCaption());
    await until(async () => /not the contents|showing current/i.test(await badgeText()),
      async () => `the badge never changed once scrubbed into the past: ${await badgeText()}`);
    const scrubbed = await badgeText();
    assert.match(scrubbed, /not the contents|showing current/i,
      `the badge does not admit it is not showing the contents at that point: ${scrubbed}`);
    assert.ok(scrubbed.includes(caption),
      `the badge does not quote timeCaption()'s own phrase (${JSON.stringify(caption)}): ${scrubbed}`);
    assert.equal(await pastCount(), 1, `the badge does not carry .past while scrubbed into the past: ${scrubbed}`);

    // and the "now" end of the slider — the case !!TIME gets wrong, because
    // TIME is non-null there too
    await scrubTo(page, total);
    await until(async () => (await pastCount()) === 0,
      async () => `the badge is still marked .past at "now": ${await badgeText()}`);
    const atNow = await badgeText();
    assert.doesNotMatch(atNow, /not the contents|showing current/i,
      `scrubbed back to "now" (TIME.n===TIME.total, still non-null), the badge still talks about the past: ${atNow}`);
    assert.equal(atNow, plain, `the badge at "now" while still in time mode differs from the badge before time mode: ${atNow} vs ${plain}`);
  });
});

// ---------------- the ＋ Data panel ----------------------------------------
//
// The action bar does not grow. It already carries seven buttons, and a second
// "get data in" door would make a reader choose between them before knowing the
// difference — so `＋ Import CSV` becomes `＋ Data`, and paste becomes ONE TAB of
// its panel rather than the whole of it. That is what paste now is: one origin
// among several, and the most constrained.
//
// What earns the tabs is `describe`. It commits nothing, so a file can be read
// and its columns shown BEFORE anything lands — most of why connecting a file
// beats pasting one — and that answer needs somewhere to live. A file row bolted
// onto the paste panel has nowhere to put it.
//
// These two write, so they come last in the file: the five above read the
// fixture, and storage.test.mjs orders its mutating tests the same way.
const enterCosmos = async (page) => {
  await bootedShell(page, server.url);
  const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
  assert.ok(i >= 0, 'space:cosmos missing — SOURCES_FIXTURE connects all three tables into it');
  await page.evaluate(n => enter(n), i);
  await page.waitForSelector(`#body${i} #dataBtn`);
  return i;
};

// The toast that reports a refused connect is gone in 2.5s, so it is caught as
// it appears rather than read after the wait gives up. Without this a wrong
// `mode` on the wire reads as a bare selector timeout, and the server's refusal
// — which names what was actually sent — never reaches the failure message.
const catchToast = (page, box) => page.evaluate(() => {
  const t = document.getElementById('toast');
  return t && t.classList.contains('on') ? t.textContent.trim() : '';
}).then(s => { if (s) box.text = s; });

test('the ＋ Data panel previews a file before connecting it', async () => {
  await withPage(browser, 'sources-data-file', async (page) => {
    const i = await enterCosmos(page);
    const at = `#body${i} `;

    await page.click(`${at}#dataBtn`);
    // /api/source/files lists the DIRECTORY, not anything loci recorded — which
    // is why gone.csv, whose table still exists, is not offered: the fixture
    // deleted the file.
    await page.waitForSelector(`${at}[data-file="live.csv"]`, { timeout: 8000 });
    assert.equal(await page.locator(`${at}[data-file="drift.csv"]`).count(), 1,
      'the listing shows only one of the two files on disk');
    assert.equal(await page.locator(`${at}[data-file="gone.csv"]`).count(), 0,
      'gone.csv is offered as a file to connect, and it was deleted — the listing is not of the directory');

    // Asked of the SERVER, not read off `STATE`. `STATE.events` only moves when
    // the shell calls applyState, so a preview that committed would leave it
    // untouched and this would certify rather than test — the exact shape of
    // assertion this branch has already shipped five of.
    const events = () => page.evaluate(() => API.state().then(s => s.events));
    const before = await events();
    await page.click(`${at}[data-file="live.csv"]`);
    await page.waitForSelector(`${at}.srcpreview`, { timeout: 8000 });

    // Scoped to `.srcpreview`, and asserting a TYPE. A column name over the whole
    // body would pass on the file's own name and on the title field beside it;
    // `VARCHAR` and `BIGINT` are things only /api/source/describe can say.
    const prev = (await page.textContent(`${at}.srcpreview`)).trim();
    assert.match(prev, /region/, `the preview does not name the file's columns: ${JSON.stringify(prev)}`);
    assert.match(prev, /revenue/, `the preview does not name the file's columns: ${JSON.stringify(prev)}`);
    assert.match(prev, /VARCHAR/, `the preview does not show DuckDB's type for a text column: ${JSON.stringify(prev)}`);
    assert.match(prev, /BIGINT/, `the preview does not show DuckDB's type for a numeric column: ${JSON.stringify(prev)}`);

    // `describe` IS the dry run. If a preview cost an event the panel would be
    // committing before you had decided, and Cancel would owe you an undo.
    assert.equal(await events(), before,
      'previewing a file committed an event — describe is supposed to commit nothing');

    // copy in / read live, and the wire word is what the control carries: phase 1
    // refuses a mode BY NAME, so the UI's own phrasing must not reach it.
    assert.equal(await page.inputValue(`${at}input[name^="dmode"]:checked`), 'materialise',
      'the mode does not default to copy in, or it does not send materialise');

    await page.fill(`${at}#dtitle`, 'From the panel');
    const toast = { text: '' };
    await page.click(`${at}[data-a="connect"]`);
    await until(async () => {
      await catchToast(page, toast);
      return (await page.locator('.molder-h .t').count()) > 0;
    }, () => `the connected table never opened. the page said: ${JSON.stringify(toast.text)}`, 20000);

    assert.match((await page.textContent('.molder-h .t')).trim(), /From the panel/,
      'something opened, but not the table the panel just made');
    assert.equal(await page.locator('.molder-b table.mold').count(), 1,
      'the connected table drew no rows');
    assert.match(await page.textContent('.molder-b'), /EMEA/,
      'the table landed with none of the file’s rows in it');
    // And the same oracle the "describe commits nothing" check used, now moving
    // — without this that check is unfalsifiable furniture, because a counter
    // that never moves reads as "nothing was committed" forever.
    assert.ok(await events() > before,
      'the connect committed no event, so the count above proves nothing about describe');
  });
});

test('the ＋ Data panel still pastes', async () => {
  await withPage(browser, 'sources-data-paste', async (page) => {
    const i = await enterCosmos(page);
    const at = `#body${i} `;

    await page.click(`${at}#dataBtn`);
    await page.click(`${at}[data-tab="paste"]`);
    // the same ids the paste panel has always had — this body is unchanged, and
    // that is the point: paste moved behind a tab, it did not get rewritten
    await page.fill(`${at}#csv`, 'x,y\n1,2\n3,4\n');
    await page.fill(`${at}#ctitle`, 'Pasted still works');
    const toast = { text: '' };
    await page.click(`${at}#goImp`);
    await until(async () => {
      await catchToast(page, toast);
      return (await page.locator('.molder-h .t').count()) > 0;
    }, () => `the pasted table never opened. the page said: ${JSON.stringify(toast.text)}`, 20000);

    assert.match((await page.textContent('.molder-h .t')).trim(), /Pasted still works/,
      'something opened, but not the table the paste tab just made');
    assert.equal(await page.locator('.molder-b table.mold').count(), 1,
      'the pasted table drew no rows');
  });
});
