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
//
// `(\?|$)` because /api/memory-unmerge starts with /api/memory: a bare
// /\/api\/memory/ matches both, and the pane's route would then answer the
// unmerge POST with a pane payload.
const MEMORY_URL = /\/api\/memory(\?|$)/;
const UNMERGE_URL = /\/api\/memory-unmerge$/;

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
    await stub(page, { ...HEALTHY, total: 3, facts: facts(3) });
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
                       total: 4, facts: facts(4) });
    await openPane(page);

    assert.equal(await statusLine(page), 'embed-qwen3-4b · 2 of 4 awaiting embedding');
  });
});

// ── the denominator is the memory's, never the page's ──────────────────────

test('under a query the denominator is the whole memory, not the twenty on screen', async () => {
  await withPage(browser, 'memory-total-is-not-the-page', async (page) => {
    await bootedShell(page, server.url);
    // exactly the shape a query produces: the ranked top 20 out of 80 live facts,
    // 12 of which have no vector yet. The pane's line is a statement about the
    // memory, so "12 of 20" would be the page being made to speak for it — and it
    // is a number that shrinks as the page does.
    await stub(page, { embedding: 'embed-qwen3-4b', awaiting: 12, merge: { threshold: 0.88 },
                       total: 80, facts: facts(20) });
    await openPane(page, 'planets');

    const line = await statusLine(page);
    assert.equal(line, 'embed-qwen3-4b · 12 of 80 awaiting embedding');
    assert.ok(!line.includes('of 20'),
      `the denominator came off the page instead of out of the memory: ${line}`);
    // the page still says what the page is — the two counts coexist and differ
    assert.match(await page.textContent('.modal-h .q'), /20 remembered facts/,
      'the header counts what is shown; the status line counts the memory');
  });
});

test('a scoped payload counts the whole memory too', async () => {
  await withPage(browser, 'memory-total-survives-a-scope', async (page) => {
    await bootedShell(page, server.url);
    // `:scope` is on the payload and `facts` is one notebook's slice; the memory
    // is still 80 facts and 12 of them are still waiting for an embedder.
    await stub(page, { embedding: 'embed-qwen3-4b', awaiting: 12, merge: { threshold: 0.88 },
                       total: 80, scope: { space: 'space:cosmos', sources: 4, empty: false },
                       facts: facts(3) });
    await openPane(page);

    assert.equal(await statusLine(page), 'embed-qwen3-4b · 12 of 80 awaiting embedding');
  });
});

test('a payload with no total at all says the count and no denominator', async () => {
  await withPage(browser, 'memory-total-absent', async (page) => {
    await bootedShell(page, server.url);
    // An unknown total is left unsaid rather than replaced with the number that
    // happens to be on screen — a wrong denominator reads as authoritative.
    await stub(page, { embedding: 'embed-qwen3-4b', awaiting: 2, merge: { threshold: 0.88 },
                       facts: facts(4) });
    await openPane(page, 'planets');

    const line = await statusLine(page);
    assert.equal(line, 'embed-qwen3-4b · 2 awaiting embedding');
    assert.ok(!line.includes('of 4'), `the page invented a denominator: ${line}`);
  });
});

test('no embedder configured says so, and carries no count', async () => {
  await withPage(browser, 'memory-status-no-embedder', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, { embedding: null, awaiting: 4, merge: { off: true }, total: 4,
                       facts: facts(4) });
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
                       total: 3, facts: facts(3) });
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
                       total: 2, facts: facts(2) });
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
    await stub(page, { ...HEALTHY, total: 40, facts: [fact({ via: ['semantic'], score: 0.83 })] });
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
    await stub(page, { ...HEALTHY, total: 40, facts: [
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
      ? { ...HEALTHY, total: 1, facts: [fact({ via: ['lexical', 'semantic'], score: 0.77 })] }
      : { ...HEALTHY, total: 1, facts: [fact({ via: ['lexical', 'semantic'] })] }));
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
    await stub(page, { ...HEALTHY, total: 2, facts: [
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

// ── undoing one merge ──────────────────────────────────────────────────────
//
// POST /api/memory-unmerge answers with the two records and NOTHING else — no
// facts, no q, no scope — so the pane cannot be patched from the response and
// refetches /api/memory instead. Both routes are stubbed: the endpoint's own
// behaviour is loci.server-test's subject, and what is under test here is the
// gesture and what the pane does with the answer.

// A survivor holding two absorbed facts, plus one fact that absorbed nothing.
const MERGED_PANE = {
  embedding: 'embed-qwen3-4b', awaiting: 0, merge: { threshold: 0.88 }, total: 2,
  facts: [
    fact({ id: 'fact:survivor', 'merged-from': [
      { id: 'fact:absorbed-a', fact: 'Neptune takes 165 years to orbit the Sun' },
      { id: 'fact:absorbed-b', fact: 'Neptune is about 30 AU from the Sun' },
    ] }),
    fact({ id: 'fact:plain', fact: 'Uranus is the seventh planet from the Sun' }),
  ],
};

// After absorbed-b is restored: the survivor holds one entry, the restored fact
// is a row of its own, `total` went 2 → 3 and `awaiting` 0 → 1, because a merge
// dropped the restored fact's vector and no sweep has run since. That rise is
// correct and the status line simply shows it.
const AFTER_PANE = {
  embedding: 'embed-qwen3-4b', awaiting: 1, merge: { threshold: 0.88 }, total: 3,
  facts: [
    fact({ id: 'fact:survivor', strength: 1, 'merged-from': [
      { id: 'fact:absorbed-a', fact: 'Neptune takes 165 years to orbit the Sun' },
    ] }),
    fact({ id: 'fact:absorbed-b', fact: 'Neptune is about 30 AU from the Sun' }),
    fact({ id: 'fact:plain', fact: 'Uranus is the seventh planet from the Sun' }),
  ],
};

// The endpoint's success shape, verbatim from the route's docstring: the two
// records, `merged-from` ABSENT rather than [] when the last entry goes, and no
// `vec` on either.
const unmergeOk = (survivor, absorbed, held = []) => ({
  survivor: {
    id: survivor, fact: 'Neptune is the eighth planet from the Sun', strength: 1,
    'no-merge': [absorbed], ...(held.length ? { 'merged-from': held } : {}),
  },
  restored: { id: absorbed, fact: 'a fact again', strength: 1, 'no-merge': [survivor] },
});

// Records every POST body, so "the request names the right pair" is asserted on
// what actually went over the wire rather than on an attribute in the DOM.
const stubUnmerge = (page, reply) => {
  const seen = [];
  return page.route(UNMERGE_URL, route => {
    const body = JSON.parse(route.request().postData() || '{}');
    seen.push(body);
    route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify(reply(body)) });
  }).then(() => seen);
};

// The one merged row whose visible text names `id` — clicking the control inside
// it is how "per entry" is tested by behaviour instead of by counting elements.
const rowFor = (page, id) => page.locator('.mergedlist > div', { hasText: id });

test('every absorbed entry carries its own unmerge control', async () => {
  await withPage(browser, 'memory-unmerge-control-per-entry', async (page) => {
    await bootedShell(page, server.url);
    await stub(page, MERGED_PANE);
    await openPane(page);
    await page.click('.mergedtoggle');

    const lines = await page.$$eval('.mergedlist > div', els => els.map(e => e.innerText.trim()));
    assert.equal(lines.length, 2, `one line per absorbed fact:\n${lines.join('\n')}`);
    for (const [id, text] of [['fact:absorbed-a', '165 years'], ['fact:absorbed-b', '30 AU']]) {
      const line = lines.find(l => l.includes(id));
      assert.ok(line, `no line for ${id}:\n${lines.join('\n')}`);
      assert.ok(line.includes(text), `${id}'s line lost its text: ${line}`);
      assert.ok(line.includes('unmerge'),
        `${id} can be read but not undone, which makes the list a report and not a control: ${line}`);
    }
    // the fact that absorbed nothing has nothing to undo
    const pane = await paneText(page);
    assert.equal(pane.match(/unmerge/g).length, 2,
      `one control per absorbed entry and no others:\n${pane}`);
  });
});

test('unmerging names the pair it was clicked for, and the pane comes back refetched', async () => {
  await withPage(browser, 'memory-unmerge-sends-the-right-pair', async (page) => {
    await bootedShell(page, server.url);
    let restored = false;
    // one route, two states: the refetch after a successful unmerge must see the
    // memory as it now is, which is the whole reason the pane refetches
    await page.route(MEMORY_URL, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(restored ? AFTER_PANE : MERGED_PANE),
    }));
    const seen = await stubUnmerge(page, b => {
      restored = true;
      return unmergeOk(b.survivor, b.absorbed,
                       [{ id: 'fact:absorbed-a', fact: 'Neptune takes 165 years to orbit the Sun' }]);
    });

    await openPane(page);
    await page.click('.mergedtoggle');
    // the SECOND entry, not the first: a control that always sends the first
    // absorbed id would pass a click on either one
    await rowFor(page, 'fact:absorbed-b').locator('.unmerge').click();

    await page.waitForFunction(() =>
      document.querySelector('.memstat')?.textContent.includes('awaiting'));

    assert.deepEqual(seen, [{ survivor: 'fact:survivor', absorbed: 'fact:absorbed-b' }],
      'one request, naming the survivor it was rendered under and the entry that was clicked');

    // the pane reflects the result — and reflects it by refetching, so the
    // restored fact is a row of its own rather than a patched-in list item
    const rows = await rowTexts(page);
    assert.equal(rows.length, 3, `the restored fact is a fact now:\n${rows.join('\n---\n')}`);
    assert.match(rows[0], /\+1 merged/, `the survivor now holds one: ${rows[0]}`);
    assert.ok(rows.some(r => r.includes('30 AU')),
      `the restored fact must appear as a fact, not only inside the merged list:\n${rows.join('\n---\n')}`);
    // the count went UP, because the restored fact has no vector until the next
    // sweep. Honest rather than flattering, and the line just says it.
    assert.equal(await statusLine(page), 'embed-qwen3-4b · 1 of 3 awaiting embedding');
  });
});

test('a refused unmerge keeps the pane and says the server’s own sentence', async () => {
  await withPage(browser, 'memory-unmerge-refused', async (page) => {
    await bootedShell(page, server.url);
    // 200 with {error}, which is how every not-allowed answer in server.clj reads
    const REFUSED = 'fact:survivor did not absorb fact:absorbed-b; it holds fact:absorbed-a';
    await stub(page, MERGED_PANE);
    const seen = await stubUnmerge(page, () => ({ error: REFUSED }));

    await openPane(page);
    await page.click('.mergedtoggle');
    await rowFor(page, 'fact:absorbed-b').locator('.unmerge').click();

    await page.waitForSelector('.toast.on');
    const toast = (await page.textContent('.toast')).trim();
    assert.ok(toast.includes(REFUSED),
      `the server's reason is what the reader needs; anything else is a guess: ${toast}`);
    assert.equal(seen.length, 1);

    // and nothing was re-rendered as if it had worked
    const rows = await rowTexts(page);
    assert.equal(rows.length, 2, `the pane still shows what it showed:\n${rows.join('\n---\n')}`);
    assert.match(rows[0], /\+2 merged/, `both entries are still absorbed: ${rows[0]}`);
    assert.equal(await statusLine(page), 'embed-qwen3-4b · 2 facts, all embedded');
  });
});
