// The memory pane shows what the recall layer knows about its own condition:
// one status line that is always there, how each hit was found, and what a fact
// absorbed when it merged.
//
// Every payload here is served by page.route, never by the server. The states
// worth testing need an embedder that is missing, unreachable or uncalibrated —
// three things a test must never go looking for on a network — and the pane is
// pure rendering of one JSON response, so stubbing it tests exactly the code
// this file's subject owns.
//
// Assertions are on TEXT, not on classes: a class-counting assertion on
// index.html passed straight through a shipped no-op on 2026-08-06.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// A regex, not a glob: `?` is a wildcard in playwright's glob syntax and the
// URL we must match has one in it.
const MEMORY_URL = /\/api\/memory/;

// `body` may be a payload or a function of the ?q= the shell asked with — the
// second is how one route serves a browse and then the query typed into it.
const stub = (page, body) => page.route(MEMORY_URL, route => {
  const q = new URL(route.request().url()).searchParams.get('q') || '';
  route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(typeof body === 'function' ? body(q) : body),
  });
});

// No fact text below contains "words", "meaning" or "merged" — the absence of a
// marker is asserted by the absence of those strings.
const fact = (over = {}) => ({
  id: 'fact:neptune', fact: 'Neptune is the eighth planet from the Sun',
  ts: Date.now(), strength: 2, source: { space: 'space:cosmos' }, ...over,
});
const facts = (n) => Array.from({ length: n }, (_, i) =>
  fact({ id: `fact:${i}`, fact: `A remembered thing, number ${i}` }));

const HEALTHY = { embedding: 'embed-qwen3-4b', awaiting: 0, merge: { threshold: 0.88 } };
// the server's own refusal, verbatim in shape: the pane names the model itself
// (`:merge` carries none) and keeps this text for the hover
const REFUSAL = 'no merge threshold is calibrated for the embedding model "embed-bge-m3", ' +
  'so nothing is merged — a threshold belongs to one model. Calibrated: embed-qwen3-4b. ' +
  'Measure this model with loci.calibrate.';

// openMemory is a top-level function declaration, so it is on window (unlike
// `let STATE`). Calling it directly and awaiting the promise renders once with
// no race; the ⌾ button path is exercised by the first test.
const openPane = async (page, q = '') => {
  await page.evaluate(qq => openMemory(qq), q);
  await page.waitForSelector('.modalcard');
};

// Present in every condition — so a break that renders it only when something is
// wrong fails here in milliseconds, with a sentence, instead of timing out.
const statusLine = async (page) => {
  const n = await page.locator('.memstat').count();
  assert.equal(n, 1, 'the status line must be present in every condition, not only an alarm ' +
    'that appears when something is wrong');
  return (await page.textContent('.memstat')).trim();
};

// What the eye reads: innerText, so anything display:none is out of it.
const paneText = (page) => page.innerText('.modalcard');
const rowTexts = (page) => page.$$eval('.memfact', els => els.map(e => e.innerText.trim()));
const statusTitle = async (page) => (await page.getAttribute('.memstat', 'title')) || '(no title)';

// ── the status line ────────────────────────────────────────────────────────

test('healthy and fully embedded: the model and the count, from the ⌾ button', async () => {
  await withPage(browser, 'memory-status-healthy', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { ...HEALTHY, facts: facts(3) });
    await page.click('#memBtn');
    await page.waitForSelector('.modalcard');

    const line = await statusLine(page);
    assert.equal(line, 'embed-qwen3-4b · 3 facts, all embedded');
    assert.ok(!line.includes('merge'),
      'a calibrated threshold is merging happening normally — there is nothing to announce');
  });
});

test('a backlog says how many of how many, beside the model that is behind', async () => {
  await withPage(browser, 'memory-status-backlog', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { embedding: 'embed-qwen3-4b', awaiting: 2, merge: { threshold: 0.88 },
                       facts: facts(4) });
    await openPane(page);

    assert.equal(await statusLine(page), 'embed-qwen3-4b · 2 of 4 awaiting embedding');
  });
});

test('no embedder configured says so, and carries no count', async () => {
  await withPage(browser, 'memory-status-no-embedder', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { embedding: null, awaiting: 4, merge: { off: true }, facts: facts(4) });
    await openPane(page);

    const line = await statusLine(page);
    assert.equal(line, 'no embedder configured — recall is by words only');
    // the rule this pane exists to keep: a number and a model name appear
    // together or not at all. "4 awaiting" with no model reads like a backlog
    // that will clear, when it means the feature is off and every fact awaits
    // an embedder forever.
    assert.ok(!/\d/.test(line), `a count with no model beside it: ${line}`);
    // {off: true} is this same missing embedder. Saying one cause twice is noise.
    assert.ok(!line.includes('merge'), `merge is off because of what line 1 already said: ${line}`);
  });
});

test('an unreachable embedder says the words still answer, and keeps the reason', async () => {
  await withPage(browser, 'memory-status-degraded', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { embedding: 'embed-qwen3-4b', awaiting: 0, merge: { threshold: 0.88 },
                       degraded: 'semantic recall is degraded: Connection refused',
                       facts: facts(3) });
    await openPane(page);

    assert.equal(await statusLine(page), 'embedder unreachable — showing word matches only');
    assert.match(await statusTitle(page), /Connection refused/,
      "the server's own sentence is kept on the hover rather than thrown away");
  });
});

test('a refused merge is a clause on whichever line applies, naming the model', async () => {
  await withPage(browser, 'memory-status-merge-refused', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { embedding: 'embed-bge-m3', awaiting: 0, merge: { refused: REFUSAL },
                       facts: facts(2) });
    await openPane(page);

    // the whole line: merge state is orthogonal to embedding state, so the
    // embedding half must survive the clause being appended to it
    assert.equal(await statusLine(page),
      'embed-bge-m3 · 2 facts, all embedded · merge paused: embed-bge-m3 has no calibrated threshold');
    assert.match(await statusTitle(page), /loci\.calibrate/,
      'the refusal says how to fix it; the hover is where that fits');
  });
});

// ── how each fact was found ────────────────────────────────────────────────

test('a hit found by meaning alone says meaning', async () => {
  await withPage(browser, 'memory-via-semantic', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { ...HEALTHY, facts: [fact({ via: ['semantic'], score: 0.83 })] });
    await openPane(page, 'ice giant');

    const [row] = await rowTexts(page);
    assert.match(row, /meaning/, `no provenance marker on a ranked hit: ${row}`);
    assert.ok(!row.includes('words'),
      `nothing lexical ranked this fact, so "words" must not appear: ${row}`);
  });
});

test('found by both says words + meaning; found by one says only that one', async () => {
  await withPage(browser, 'memory-via-both', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { ...HEALTHY, facts: [
      fact({ id: 'fact:both', fact: 'Neptune was found by mathematics before by telescope',
             via: ['lexical', 'semantic'], score: 0.91 }),
      fact({ id: 'fact:lex', fact: 'Neptune has 14 known moons', via: ['lexical'], score: 0.42 }),
    ] });
    await openPane(page, 'neptune');

    const rows = await rowTexts(page);
    assert.match(rows[0], /words \+ meaning/, `both retrievers ranked it: ${rows[0]}`);
    assert.match(rows[1], /· words$/, `only the lexical half ranked it: ${rows[1]}`);
    assert.ok(!rows[1].includes('meaning'), `nothing semantic ranked it: ${rows[1]}`);
  });
});

test('a browse shows no marker at all, and typing a query brings them out', async () => {
  await withPage(browser, 'memory-browse-has-no-marker', async (page) => {
    await bootedShell(page, server.url);
    // The browse payload carries :via anyway — the pane must not render it just
    // because it is there. A browse ranks nothing, so nothing was found by
    // anything, and a marker would be decoration pretending to be information.
    await stub(page, q => (q
      ? { ...HEALTHY, facts: [fact({ via: ['lexical', 'semantic'], score: 0.77 })] }
      : { ...HEALTHY, facts: [fact({ via: ['lexical', 'semantic'] })] }));
    await openPane(page);

    // the status line is the healthy one, which contains neither string
    const browsed = await paneText(page);
    assert.ok(!browsed.includes('words') && !browsed.includes('meaning'),
      `a browse ranks nothing, so nothing may claim to have been found by anything:\n${browsed}`);

    // the same pane, through the real gesture, once there IS a query
    await page.fill('#memq', 'neptune');
    await page.press('#memq', 'Enter');
    await page.waitForFunction(() =>
      document.querySelector('.modal-h .q')?.textContent.includes('neptune'));

    const [row] = await rowTexts(page);
    assert.match(row, /words \+ meaning/, `the same fact, now ranked, must say how: ${row}`);
  });
});

// ── what a fact absorbed ───────────────────────────────────────────────────

test('a merged fact says how many, and reveals what it absorbed on click', async () => {
  await withPage(browser, 'memory-merged-reveals', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { ...HEALTHY, facts: [
      fact({ id: 'fact:survivor', 'merged-from': [
        { id: 'fact:absorbed-a', fact: 'Neptune takes 165 years to orbit the Sun' },
        { id: 'fact:absorbed-b', fact: 'Neptune is about 30 AU from the Sun' },
      ] }),
      fact({ id: 'fact:plain', fact: 'Uranus is the seventh planet from the Sun' }),
    ] });
    await openPane(page);

    const rows = await rowTexts(page);
    assert.match(rows[0], /\+2 merged/, `two facts were folded into this one: ${rows[0]}`);

    // a merge is only auditable if the absorbed text can be read — but it is not
    // shouted at a reader who did not ask
    const before = await paneText(page);
    assert.ok(!before.includes('165 years'),
      `the absorbed text must stay folded until it is asked for:\n${before}`);

    await page.click('.mergedtoggle');
    const after = await paneText(page);
    assert.ok(after.includes('165 years') && after.includes('30 AU'),
      `clicking must reveal every absorbed fact:\n${after}`);
    assert.ok(after.includes('fact:absorbed-a') && after.includes('fact:absorbed-b'),
      `and their ids, which is what makes a wrong merge addressable:\n${after}`);

    // the check that the marker means something: a fact that absorbed nothing
    // says nothing
    assert.ok(!rows[1].includes('merged'),
      `a fact with no :merged-from must show nothing extra: ${rows[1]}`);
  });
});
