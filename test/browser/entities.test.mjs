// The entity page — ONE payload shape, ONE renderer, two surfaces: a modal
// before the subject is a citizen of the substrate, the molder after. Two
// renderers would drift, and the drift would be invisible until somebody
// materialized something, so the load-bearing test in this file is the one that
// draws the SAME payload through both surfaces and compares the text.
//
// Every entity payload below is served by page.route, never by the server. The
// states worth testing are a 50-of-137 cap, a subject that lost its promotion to
// a merge, and a request that named nothing — all three are loci.server-test's
// subject. What this file owns is what the shell does with the JSON.
//
// Assertions are on TEXT, not on classes: a class-counting assertion on
// index.html passed straight through a shipped no-op on 2026-08-06.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// Regexes, not globs: `?` is a wildcard in playwright's glob syntax and the URL
// the shell asks with has one in it.
//
// `(\?|$)` because /api/entity-notebook starts with /api/entity — a bare
// /\/api\/entity/ matches both, and the page's route would then answer the
// materialization POST with an entity payload. The same trap memorypane.test.mjs
// hit with /api/memory and /api/memory-unmerge.
const ENTITY_URL = /\/api\/entity(\?|$)/;
const ENTNB_URL = /\/api\/entity-notebook$/;
// the molder is reached by openObject → API.object → /api/object/<id>, NOT by
// /api/mold (which only `remold` uses, and an entity has no alternatives to
// remold to). The id is percent-encoded on the way out: `ent:1` → `ent%3A1`.
const OBJECT_URL = /\/api\/object\//;

const fact = (id, text, over = {}) => ({
  id, fact: text, entities: ['tsmc'], ts: Date.now(), strength: 1,
  source: { obj: 'doc:fab', space: 'space:semis' }, score: 0.52, via: ['lexical'], ...over,
});

// The shape `entity-payload` answers with, verbatim in its keys.
const PAGE = (over = {}) => ({
  name: 'tsmc', count: 2, promoted: true,
  facts: [
    fact('mem-1', 'TSMC controls roughly seventy per cent of global foundry revenue'),
    fact('mem-2', 'TSMC builds its most advanced nodes in Hsinchu'),
  ],
  sources: [
    { id: 'doc:fab', title: 'Fab capacity', kind: 'doc' },
    { id: 'space:semis', title: 'Semiconductors', kind: 'space' },
  ],
  ...over,
});

// What /api/object/<id> answers for a materialized entity: mold-payload's entity
// branch, with entity-payload's own map under `rendered` and no alternatives.
const MOLD = (rendered, id = 'ent:1') => ({
  id, title: rendered.name || id, kind: 'entity', view: null, label: 'entity',
  rendered, alternatives: [],
});

// `body` may be a payload or a function of the ?name= the shell asked with.
// Returns the names asked for, so "the request carried what the caller typed" is
// asserted on what went over the wire.
const stubEntity = (page, body) => {
  const asked = [];
  return page.route(ENTITY_URL, route => {
    const nm = new URL(route.request().url()).searchParams.get('name') || '';
    asked.push(nm);
    route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(typeof body === 'function' ? body(nm) : body),
    });
  }).then(() => asked);
};

// Records every POST body, so "nothing was written" is a claim about the wire
// and not about the DOM.
const stubEntityNotebook = (page, reply) => {
  const seen = [];
  return page.route(ENTNB_URL, route => {
    const body = JSON.parse(route.request().postData() || '{}');
    seen.push(body);
    route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify(reply(body)) });
  }).then(() => seen);
};

// Records every id the shell tried to open, and serves the ones we have.
const stubObjects = (page, byId) => {
  const asked = [];
  return page.route(OBJECT_URL, route => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname
      .slice('/api/object/'.length));
    asked.push(id);
    if (byId[id]) {
      route.fulfill({ status: 200, contentType: 'application/json',
                      body: JSON.stringify(byId[id]) });
    } else route.continue();
  }).then(() => asked);
};

// openEntity is a top-level function declaration, so it is on window (unlike
// `let STATE`). Awaiting the evaluate renders once with no race.
const openEnt = (page, name = 'tsmc') => page.evaluate(n => openEntity(n), name);
const modalText = page => page.innerText('.modalcard');

// A gesture that reaches no endpoint at all fails HERE, in five seconds with a
// sentence, instead of thirty seconds later in a waitForSelector that can only
// say the selector never appeared. Sabotaging the chip delegation and the
// spaceId fallback both land in exactly that shape.
const until = async (fn, why, ms = 5000) => {
  const t0 = Date.now();
  for (;;) {
    if (await fn()) return;
    if (Date.now() - t0 > ms) throw new Error(why);
    await new Promise(r => setTimeout(r, 50));
  }
};

// ── the page, on the modal surface ─────────────────────────────────────────

test('the page lists its facts and names where they came from', async () => {
  await withPage(browser, 'entity-facts-and-sources', async (page) => {
    await bootedShell(page, server.url);
    const asked = await stubEntity(page, PAGE());
    await openEnt(page, 'TSMC');
    await page.waitForSelector('.modalcard');

    const txt = await modalText(page);
    assert.ok(txt.includes('seventy per cent of global foundry revenue'),
      `the first fact is not on the page:\n${txt}`);
    assert.ok(txt.includes('Hsinchu'), `the second fact is not on the page:\n${txt}`);

    // the chips read as their TITLES — a fact outlives the document it came from,
    // and a chip with an id on it instead of a name is a chip nobody can use
    const chips = await page.$$eval('.modalcard .entsrc .ghostchip',
      els => els.map(e => e.innerText.trim()));
    assert.deepEqual(chips, ['Fab capacity', 'Semiconductors'],
      'the source chips are the payload’s sources, in the payload’s order');

    // the caller's raw string goes over the wire; normalizing is the server's job
    // and never a second implementation in the page
    assert.deepEqual(asked, ['TSMC']);
    // …and what is DRAWN is the server's normalized answer, not what was typed
    const nm = (await page.innerText('.entname')).trim();
    assert.ok(nm.includes('tsmc') && !nm.includes('TSMC'),
      `the page drew the typed string instead of the identity it was answered with: ${nm}`);
  });
});

test('a source chip opens the object it names', async () => {
  await withPage(browser, 'entity-source-chip-opens', async (page) => {
    await bootedShell(page, server.url);
    await stubEntity(page, PAGE());
    const opened = await stubObjects(page, { 'space:semis': {
      id: 'space:semis', title: 'Semiconductors', kind: 'text', view: null, label: 'Text',
      rendered: 'Fab capacity, in wafer starts per month.', alternatives: [],
    } });

    await openEnt(page);
    await page.waitForSelector('.modalcard .entsrc .ghostchip');
    // the SECOND chip, not the first: a delegation that always opened the first
    // source on the page would pass a click on either one
    await page.locator('.modalcard .entsrc .ghostchip', { hasText: 'Semiconductors' }).click();

    await until(() => opened.length > 0,
      'the chip click reached no endpoint at all — the modal has no [data-open] delegation');
    await page.waitForSelector('.molder');
    assert.deepEqual(opened, ['space:semis'],
      'the chip must open the id it carries, not the first source on the page');
    assert.equal(await page.locator('.modalcard').count(), 0,
      'the modal stayed over the object it just sent you to');
    assert.ok((await page.innerText('.molder')).includes('wafer starts'),
      'the object opened but did not render');
  });
});

test('a subject with too little evidence says so; a promoted one keeps quiet', async () => {
  await withPage(browser, 'entity-promotion-line', async (page) => {
    await bootedShell(page, server.url);
    // a merge folded two facts into one, so the name fell back below the
    // threshold. The page must say that rather than vanish.
    let payload = PAGE({ promoted: false, count: 1,
      facts: [fact('mem-1', 'TSMC controls roughly seventy per cent of global foundry revenue')] });
    await stubEntity(page, () => payload);

    await openEnt(page);
    await page.waitForSelector('.modalcard');
    const demoted = await modalText(page);
    assert.ok(demoted.includes('not yet a subject of its own'),
      `a name below the threshold must say so:\n${demoted}`);
    // and the evidence it does have is still on the page — "say so, don't vanish"
    assert.ok(demoted.includes('seventy per cent'),
      `the page said "not a subject" and then showed nothing:\n${demoted}`);

    payload = PAGE();
    await openEnt(page);
    await page.waitForFunction(() =>
      document.querySelector('.modalcard')?.innerText.includes('Hsinchu'));
    const promoted = await modalText(page);
    assert.ok(!promoted.includes('not yet a subject of its own'),
      `a promoted subject must not be told it is provisional:\n${promoted}`);
  });
});

test('the page says how many of how many when the fifty-fact cap bites', async () => {
  await withPage(browser, 'entity-count-vs-page', async (page) => {
    await bootedShell(page, server.url);
    // `count` is every live fact carrying the name; `facts` is the ranked page of
    // at most 50. Collapsed into one number the cap is invisible and a subject
    // with 137 facts reads as one with 50.
    let payload = PAGE({ count: 137,
      facts: Array.from({ length: 50 }, (_, i) => fact(`mem-${i}`, `A remembered thing, number ${i}`)) });
    await stubEntity(page, () => payload);

    await openEnt(page);
    await page.waitForSelector('.entmeta');
    const capped = (await page.innerText('.entmeta')).trim();
    assert.ok(capped.includes('50 of 137'),
      `the cap truncated in silence: ${capped}`);

    // …and when nothing is truncated the page does not invent a denominator
    payload = PAGE();
    await openEnt(page);
    await page.waitForFunction(() =>
      document.querySelector('.modalcard')?.innerText.includes('Hsinchu'));
    const whole = (await page.innerText('.entmeta')).trim();
    assert.ok(whole.includes('2 facts'), `the page lost its count: ${whole}`);
    assert.ok(!whole.includes(' of '), `a denominator that says nothing: ${whole}`);
  });
});

// ── the decision the server takes, not the shell ───────────────────────────

test('a materialized subject opens its object — the modal never appears', async () => {
  await withPage(browser, 'entity-object-short-circuit', async (page) => {
    await bootedShell(page, server.url);
    const rendered = PAGE({ object: 'ent:1' });
    await stubEntity(page, rendered);
    const opened = await stubObjects(page, { 'ent:1': MOLD(rendered) });
    const posted = await stubEntityNotebook(page, () => ({ error: 'must never be called' }));

    // openEntity returns the openObject promise, so awaiting the evaluate is
    // awaiting the whole gesture — and the modal check therefore runs first, and
    // says what is wrong, rather than waiting thirty seconds for a molder that
    // was never going to come
    await openEnt(page);
    assert.equal(await page.locator('.modalcard').count(), 0,
      'a subject that is already a citizen must not be offered the modal that makes it one');
    await page.waitForSelector('.molder');

    assert.deepEqual(opened, ['ent:1'],
      'the payload named an object and the shell did not open it');
    assert.equal(await page.locator('#entNb').count(), 0,
      '“New notebook about this” is on screen for a subject that already has one');
    assert.deepEqual(posted, [], 'nothing may be materialized twice');
    // it really is the entity page, not an empty molder
    assert.ok((await page.innerText('.molder')).includes('Hsinchu'),
      'the object opened but the entity page did not render into it');
  });
});

// ── one renderer, two surfaces ─────────────────────────────────────────────

test('the molder and the modal draw the same page from the same payload', async () => {
  await withPage(browser, 'entity-one-renderer', async (page) => {
    await bootedShell(page, server.url);
    // the molder surface: the payload as a materialized entity answers it
    await stubObjects(page, { 'ent:1': MOLD(PAGE({ object: 'ent:1' })) });
    await page.evaluate(() => openObject('ent:1'));
    await page.waitForSelector('.molder .entname');
    const molder = (await page.innerText('.molder-b')).trim();

    // the modal surface: the same payload, minus the `object` that would send it
    // to the molder instead
    await stubEntity(page, PAGE());
    await openEnt(page);
    await page.waitForSelector('.modalcard .entname');
    const modal = (await page.innerText('.modal-b')).trim();

    assert.ok(molder.includes('Hsinchu') && molder.includes('Fab capacity'),
      `the molder rendered nothing, so the comparison below is vacuous:\n${molder}`);
    assert.equal(modal, molder,
      'two surfaces, one renderer — the moment these differ the page has quietly become two pages');
  });
});

test('the molder draws the entity page, not its JSON, and grows no dead chrome', async () => {
  await withPage(browser, 'entity-molder-chrome', async (page) => {
    await bootedShell(page, server.url);
    await stubObjects(page, { 'ent:1': MOLD(PAGE({ object: 'ent:1' })) });
    await page.evaluate(() => openObject('ent:1'));
    await page.waitForSelector('.molder');

    const body = await page.innerText('.molder-b');
    assert.ok(body.includes('Hsinchu'), `the entity page is not in the molder:\n${body}`);
    assert.equal(await page.locator('.molder-b pre').count(), 0,
      'the molder fell through to the JSON default — renderMold has no entity case');
    assert.ok(!body.includes('"facts"'), `raw payload keys on screen:\n${body}`);

    // :alternatives is [] for an entity, so none of the molder's viewer chrome
    // may draw. An empty <select> is the defect regressions.test.mjs pins for
    // flows; an entity is the second kind with no viewers.
    assert.equal(await page.locator('.molder select').count(), 0,
      'an entity has no alternatives — an empty “view this as” is dead chrome');
    assert.equal(await page.locator('#makebtn').count(), 0,
      '✦ make… is for table-shaped objects');
    assert.equal(await page.locator('#editbtn').count(), 0,
      'Edit is for :kind text — an entity’s text is its facts, and they are the memory’s');
  });
});

// ── a request that named nothing ───────────────────────────────────────────

test('a payload with no subject says the server’s own sentence, on both surfaces', async () => {
  await withPage(browser, 'entity-error-both-surfaces', async (page) => {
    await bootedShell(page, server.url);
    const ERR = 'no entity name given';
    await stubEntity(page, { error: ERR });
    const posted = await stubEntityNotebook(page, () => ({ error: 'must never be called' }));

    await openEnt(page, '   ');
    await page.waitForSelector('.modalcard');
    const modal = await modalText(page);
    assert.ok(modal.includes(ERR), `the server's reason is what the reader needs:\n${modal}`);
    // …and none of the page a subject would have: a blank name rendered through
    // the ordinary path says "0 facts" and "Nothing is remembered about this yet",
    // which is a statement about a subject that was never named
    assert.ok(!modal.includes('Nothing is remembered'),
      `an error rendered as an empty subject:\n${modal}`);
    assert.ok(!modal.includes('fact'), `an error rendered as an empty subject:\n${modal}`);
    assert.equal(await page.locator('#entNb').count(), 0,
      'there is nothing to make a notebook about');
    assert.deepEqual(posted, []);

    // the same sentence on the molder: an :entity object whose stored name
    // normalizes to nothing renders entity-payload's own {:error …}
    await stubObjects(page, { 'ent:9': MOLD({ error: ERR }, 'ent:9') });
    await page.evaluate(() => { closeModal(); return openObject('ent:9'); });
    await page.waitForSelector('.molder');
    const molder = await page.innerText('.molder-b');
    assert.ok(molder.includes(ERR),
      `the molder must render the error through the same renderer:\n${molder}`);
    assert.equal(await page.locator('.molder-b pre').count(), 0,
      'the error fell through to the JSON default');
  });
});

// ── acting on a subject makes it real ──────────────────────────────────────

test('✦ New notebook about this posts the name and lands in the notebook it is answered with',
  async () => {
    await withPage(browser, 'entity-materialize', async (page) => {
      await bootedShell(page, server.url);
      // the real state, from the real server — the reply carries a whole
      // state-payload and the shell applies it, so a hand-rolled one would be
      // testing a shape the server does not send
      const state = await (await fetch(`${server.url}/api/state`)).json();
      // a notebook that is NOT the one the shell is already focused on, so
      // "entered it" cannot pass by standing still
      const target = state.spaces[state.spaces.length - 1];
      const start = await page.evaluate(() => (STATE.spaces[focusIdx] || {}).id);
      assert.notEqual(target.id, start, 'the fixture cannot show a move');

      await stubEntity(page, PAGE());
      const posted = await stubEntityNotebook(page,
        () => ({ state, entity: 'ent:1', spaceId: target.id }));

      await openEnt(page, 'TSMC');
      await page.waitForSelector('#entNb');
      await page.click('#entNb');

      await page.waitForFunction(t => document.getElementById('crumbName').textContent === t,
        target.title);
      assert.deepEqual(posted, [{ name: 'tsmc' }],
        'the POST carries the identity the page was drawn from, not the string that was typed');
      assert.equal(await page.locator('.modalcard').count(), 0,
        'the modal stayed over the notebook it just made');
      assert.equal(await page.evaluate(() => (STATE.spaces[focusIdx] || {}).id), target.id);
      // reversible, and said so — the same shape createSpace and connect use
      const toast = await page.innerText('.toast');
      assert.ok(toast.includes('Undo'),
        `a commit with no way back offered on the toast: ${toast}`);
    });
  });

test('a subject whose notebook is gone opens the subject itself', async () => {
  await withPage(browser, 'entity-materialize-null-space', async (page) => {
    await bootedShell(page, server.url);
    const state = await (await fetch(`${server.url}/api/state`)).json();
    await stubEntity(page, PAGE());
    // `spaceId` is always a KEY and null is a real answer: the notebook about
    // this subject was renamed or had the cell removed, and materialize-entity!
    // answers nil rather than minting a second one.
    const posted = await stubEntityNotebook(page,
      () => ({ state, entity: 'ent:1', spaceId: null }));
    const opened = await stubObjects(page, { 'ent:1': MOLD(PAGE({ object: 'ent:1' })) });

    await openEnt(page);
    await page.waitForSelector('#entNb');
    await page.click('#entNb');

    await until(() => opened.length > 0,
      'no notebook came back and the shell opened nothing at all — the gesture committed and ' +
      'then showed the reader the same modal it started from');
    await page.waitForSelector('.molder .entname');
    assert.equal(posted.length, 1);
    assert.deepEqual(opened, ['ent:1'],
      'no notebook came back, so the gesture must land on the subject rather than nowhere');
    assert.ok((await page.innerText('.molder')).includes('Hsinchu'),
      'the fallback opened an object that rendered nothing');
  });
});

test('in the past the button writes nothing and says why', async () => {
  await withPage(browser, 'entity-time-mode', async (page) => {
    await bootedShell(page, server.url);
    await stubEntity(page, PAGE());
    const posted = await stubEntityNotebook(page, () => ({ error: 'must never be called' }));

    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME);

    // reading a subject in the past is fine — it is acting that is refused
    await openEnt(page);
    await page.waitForSelector('#entNb');
    await page.click('#entNb');

    await page.waitForSelector('.toast.on');
    const toast = (await page.innerText('.toast')).trim();
    assert.match(toast, /time mode/,
      `the past is read-only and the button must say so: ${toast}`);
    assert.deepEqual(posted, [],
      'the past is a record — a click in it committed an entity and a notebook');
    assert.equal(await page.locator('.modalcard').count(), 1,
      'the modal was torn down as if something had happened');
    assert.ok(await page.evaluate(() => typeof TIME !== 'undefined' && !!TIME),
      'the click dropped the reader out of the past');
  });
});

// ── a fact says what it is about ───────────────────────────────────────────
//
// The memory pane, not the entity page — and no server change under any of it.
// Every fact has carried `:entities` since the distiller's first run and
// `memory-payload` has always shipped them (it drops `:vec` and nothing else);
// they had simply never been drawn. So what these tests own is rendering, and one
// delegation.
//
// Stubbed exactly as memorypane.test.mjs stubs it, `(\?|$)` and all:
// /api/memory-unmerge starts with /api/memory, so a bare /\/api\/memory/ would
// answer the unmerge POST with a pane payload — the same trap as /api/entity and
// /api/entity-notebook above.
const MEMORY_URL = /\/api\/memory(\?|$)/;
const stubMemory = (page, body) => page.route(MEMORY_URL, route => {
  const q = new URL(route.request().url()).searchParams.get('q') || '';
  route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify(typeof body === 'function' ? body(q) : body),
  });
});

const HEALTHY = { embedding: 'embed-qwen3-4b', awaiting: 0, merge: { threshold: 0.88 } };
// A pane fact — `fact` above is an entity-PAGE fact and the two shapes differ.
const memFact = (over = {}) => ({
  id: 'fact:neptune', fact: 'Neptune is the eighth planet from the Sun',
  ts: Date.now(), strength: 2, source: { space: 'space:cosmos' }, ...over,
});

// openMemory is a top-level function declaration, so it is on window.
const openPane = async (page, q = '') => {
  await page.evaluate(qq => openMemory(qq), q);
  await page.waitForSelector('.modalcard');
};
const chipTexts = page =>
  page.$$eval('.memfact .ents .ghostchip', els => els.map(e => e.innerText.trim()));

// Locate by the TEXT the chip must carry, and fail in five seconds with a
// sentence if there is no clickable thing carrying it. Clicking straight through
// a locator that matches nothing is a thirty-second timeout that can only say the
// selector never appeared — which is how three of Task 8's sabotages managed to
// "fail" without ever running the assertion that named the defect.
const clickChip = async (page, name) => {
  const chip = page.locator('.memfact .ents .ghostchip', { hasText: name });
  await until(async () => (await chip.count()) === 1,
    `no clickable subject reading “${name}” in the memory pane — the subjects were drawn ` +
    'as plain text, so there is nothing to click');
  await chip.click();
};

test('a fact draws one chip per subject, folded to the identity the server keys on', async () => {
  await withPage(browser, 'memory-fact-chips', async (page) => {
    await bootedShell(page, server.url);
    // What `absorb` really leaves behind: it unions `:entities` with `distinct`
    // over the RAW strings, so a survivor carries every casing and spacing any
    // absorbed fact happened to be written with — plus the "" and null a model's
    // JSON can put there. Six strings, two subjects, one of them named nothing.
    await stubMemory(page, { ...HEALTHY, total: 1, facts: [memFact({
      fact: 'The most advanced nodes are three years ahead of anyone else’s',
      entities: ['TSMC', 'taiwan', ' tsmc ', '', null, 'Taiwan'] })] });
    await openPane(page);

    assert.deepEqual(await chipTexts(page), ['tsmc', 'taiwan'],
      'one chip per subject, in the order the fact names them, drawn as the identity the ' +
      'server keys on rather than as the six strings the distiller happened to write');

    // …the row was added, not swapped in for the fact
    const [row] = await page.$$eval('.memfact', els => els.map(e => e.innerText.trim()));
    assert.ok(row.includes('three years ahead'), `the chips replaced the fact itself:\n${row}`);
    // …and it really is folded: no spelling but the folded one is on screen, so a
    // pane that drew the raw strings and merely deduplicated them fails here
    assert.ok(!row.includes('TSMC') && !row.includes('Taiwan'),
      `a second spelling of a subject that already has a chip is a second subject to a ` +
      `reader, and there is only one page behind both:\n${row}`);
  });
});

test('a fact about nothing draws no row, and the line under it is untouched', async () => {
  await withPage(browser, 'memory-fact-chips-absent', async (page) => {
    await bootedShell(page, server.url);
    // Four facts identical in everything the `.src` line is built from — strength
    // dots, a merge, a source, an age, a relevance and a `via`, which is every
    // segment of that `·`-separated run at once — and differing only in their
    // subjects. So anything the new row does to that line shows up as a
    // difference between these four strings.
    const common = { strength: 2, score: 0.52, via: ['lexical', 'semantic'],
      'merged-from': [{ id: 'fact:absorbed', fact: 'Neptune is about 30 AU from the Sun' }] };
    await stubMemory(page, { ...HEALTHY, total: 4, facts: [
      memFact({ id: 'fact:has',   fact: 'Neptune is the eighth planet from the Sun',
                entities: ['neptune'], ...common }),
      memFact({ id: 'fact:empty', fact: 'The ice giants are cold', entities: [], ...common }),
      memFact({ id: 'fact:blank', fact: 'Something was written down',
                entities: ['', null, '   '], ...common }),
      memFact({ id: 'fact:none',  fact: 'A fact from before there was a distiller', ...common }),
    ] });
    await openPane(page, 'neptune');

    const srcs = await page.$$eval('.memfact .src', els => els.map(e => e.innerText.trim()));
    assert.equal(srcs.length, 4, `one .src line per fact:\n${srcs.join('\n')}`);
    // the whole run, spelled out: a dangling `·` or a doubled one is a character
    // in this string and nowhere else
    assert.equal(srcs[0],
      '●● +1 merged · ⌾ space:cosmos · today · relevance 0.52 · words + meaning',
      'the line under a fact that names a subject');
    for (const s of srcs) assert.equal(s, srcs[0],
      'a fact with subjects and a fact without must read identically on the line below ' +
      `them — the chips go above it, and never into that run:\n${srcs.join('\n')}`);

    assert.deepEqual(await chipTexts(page), ['neptune'],
      '"" and null are not subjects, and neither is a missing key');
    // Structural on purpose, and the only assertion in this file that is: an empty
    // <div class="ents"> contributes nothing to innerText and six pixels to the
    // screen, so no assertion about text can see it. Rendering the row
    // unconditionally is exactly the defect.
    assert.equal(await page.locator('.memfact .ents').count(), 1,
      'a row of subjects was drawn for a fact that names none — three empty rows, one under ' +
      'each of the three facts that had nothing to put in them');
  });
});

test('clicking a subject asks the server for it, and lands on its page', async () => {
  await withPage(browser, 'memory-fact-chip-opens-subject', async (page) => {
    await bootedShell(page, server.url);
    await stubMemory(page, { ...HEALTHY, total: 2, facts: [
      memFact({ id: 'fact:tw',  fact: 'The island exports most of the world’s leading-edge silicon',
                entities: ['taiwan'] }),
      memFact({ id: 'fact:fab', fact: 'The most advanced nodes are three years ahead',
                entities: ['tsmc'] }),
    ] });
    const asked = await stubEntity(page, PAGE());
    // Nothing here is an object. A chip carrying `data-open` would be picked up by
    // openMemory's own [data-open] delegation and fetched as /api/object/tsmc, so
    // this records what would happen and asserts it did not.
    const opened = await stubObjects(page, {});

    await openPane(page);
    // the chip on the SECOND fact, so a delegation that always opened the first
    // subject on the page would pass a click on either one
    await clickChip(page, 'tsmc');

    // Either endpoint ends the wait, and the assertions below say which one was
    // reached. Waiting on `asked` alone would let a chip that went to openObject
    // instead fall through to a thirty-second waitForSelector whose only message
    // is that a selector never appeared.
    await until(() => asked.length + opened.length > 0,
      'the chip click reached no endpoint at all — the pane has no [data-entity] delegation');
    assert.deepEqual(opened, [],
      'the chip was opened as an object — data-open and data-entity are two different ' +
      'gestures, and there is no object whose id is “tsmc”');
    assert.deepEqual(asked, ['tsmc'],
      'the chip must ask for the subject it carries, not the first one on the page');
    await page.waitForSelector('.modalcard .entname');

    const txt = await modalText(page);
    assert.ok(txt.includes('seventy per cent of global foundry revenue'),
      `the subject page did not render:\n${txt}`);
    assert.ok(!txt.includes('leading-edge silicon'),
      `the memory pane is still on screen under the subject it opened:\n${txt}`);
  });
});

test('a subject that is already a citizen opens its object, and the pane does not stay over it',
  async () => {
    await withPage(browser, 'memory-fact-chip-materialized', async (page) => {
      await bootedShell(page, server.url);
      await stubMemory(page, { ...HEALTHY, total: 1, facts: [
        memFact({ id: 'fact:fab', fact: 'The most advanced nodes are three years ahead',
                  entities: ['tsmc'] }),
      ] });
      const rendered = PAGE({ object: 'ent:1' });
      await stubEntity(page, rendered);
      const opened = await stubObjects(page, { 'ent:1': MOLD(rendered) });

      await openPane(page);
      await clickChip(page, 'tsmc');

      await until(() => opened.length > 0,
        'the chip click reached no endpoint at all — the pane has no [data-entity] delegation');
      // asserted BEFORE the molder is waited for: a chip carrying data-open sends
      // its own text to openObject, which puts /api/object/tsmc on this very list
      // and satisfies the wait above — and then the molder never comes, so the
      // whole test would "fail" thirty seconds later with nothing to say. Measured:
      // it did exactly that until this line was moved up.
      assert.deepEqual(opened, ['ent:1'],
        'the payload named an object and the chip opened something else — a subject is not ' +
        'an id, and /api/object/tsmc is a request for an object nothing has');
      await page.waitForSelector('.molder .entname');
      // openEntity short-circuits to openObject and never touches the modal — that
      // is deliberate, and it is why the pane is only gone if the chip's own
      // handler closed it first. The source chips in openEntity's modal carry the
      // same rule for the same reason.
      assert.equal(await page.locator('.modalcard').count(), 0,
        'the memory pane stayed on screen over the object the chip sent you to');
    });
  });

// ── LEAP acts on a subject ─────────────────────────────────────────────────
//
// Task 7 taught `/api/leap` to emit a `subject` row, and the shell did not know
// what one was: it drew it under **Content**, wearing `▤` — the document's mark —
// and Enter handed `subject:tsmc` to openObject, which the server answers with a
// null title and an empty body. So the molder OPENED, and only its emptiness said
// that anything had gone wrong. Measured on a real payload after materializing
// `tsmc`, a document and a subject came back indistinguishable but for the grey
// id column.
//
// That heading is the design's whole argument for a separate group — "entity
// names can collide with object titles, so it is its own result group, not a
// merged list" — so a test that only checked the row was PRESENT would have
// passed on every one of those defects. What is asserted here is therefore where
// the row sits, what it is marked with, and what goes over the wire when it is
// picked.

// A row exactly as `leap-payload`'s subject group builds it: the id is
// `subject:<name>`, which is what the row IS and never where it goes, and the
// name travels in a field of its own rather than being re-parsed out of it.
const subjRow = (name = 'tsmc', over = {}) =>
  ({ id: 'subject:' + name, label: name, group: 'subject', name, count: 2, ...over });

// A predicate rather than a glob, for the reason ENTITY_URL is a regex: `?` is a
// wildcard in playwright's glob syntax and every LEAP url carries one. Records
// the two asks separately — the lexical list goes out on the keystroke and a
// `deep=1` one follows 250 ms after the keys stop — because "the past asked at
// all" is a claim about the wire.
const stubLeap = async (page, rows) => {
  const seen = { lex: [], deep: [] };
  await page.route(u => u.pathname === '/api/leap', async (route) => {
    const url = new URL(route.request().url());
    const q = url.searchParams.get('q') || '';
    (url.searchParams.get('deep') === '1' ? seen.deep : seen.lex).push(q);
    await route.fulfill({ json: q ? rows : [] });
  });
  return seen;
};

// Headings interleaved with rows, in document order: WHERE a row sits relative to
// its heading is most of what is being asserted, so read #results' children in
// order rather than the rows alone.
const listing = page => page.$$eval('#results > *', els => els.map(e =>
  e.classList.contains('group')
    ? { group: e.textContent }
    : { row: e.querySelector('.title').textContent,
        ic: e.querySelector('.ic').textContent,
        gp: e.querySelector('.gp').textContent,
        cls: [...e.classList].filter(c => c !== 'sel' && c !== 'res').join(' ') }));

const leapTo = async (page, text) => {
  await page.click('#q');
  await page.locator('#q').pressSequentially(text, { delay: 20 });
  await page.waitForFunction(t => [...document.querySelectorAll('#results .res .title')]
    .some(el => el.textContent === 'Ask the agent: “' + t + '”'), text);
};

// The row whose title is EXACTLY this — “tsmc” as a substring also matches
// “Ask the agent: “tsmc””, and clicking that instead would ask the agent.
const rowIndex = async (page, title) => {
  const i = await page.$$eval('#results .res',
    (els, t) => els.findIndex(e => e.querySelector('.title').textContent === t), title);
  assert.ok(i >= 0, `there is no row reading “${title}”: ` +
    JSON.stringify(await page.$$eval('#results .res .title', e => e.map(x => x.textContent))));
  return i;
};

test('a subject is its own group, with a mark that is neither a document’s nor memory’s',
  async () => {
    await withPage(browser, 'leap-subject-group', async (page) => {
      // one row per group a subject has to be told apart from, all on screen at
      // once: a notebook, a document, and a memory hit
      await stubLeap(page, [
        { id: 'space:about-1', label: 'About TSMC', group: 'space' },
        { id: 'doc:teardown', label: 'A teardown', group: 'doc' },
        subjRow('tsmc'),
        { id: '__memory__', group: 'memory',
          label: 'TSMC controls roughly seventy per cent of global foundry revenue' },
      ]);
      await bootedShell(page, server.url);
      await leapTo(page, 'tsmc');

      const l = await listing(page);
      const si = l.findIndex(x => x.group === 'Subjects');
      assert.ok(si >= 0,
        'the subject was drawn under some other heading — the heading is the only thing on a ' +
        `row that says a subject is not a document:\n${JSON.stringify(l, null, 1)}`);
      const sub = l[si + 1];
      assert.equal(sub && sub.row, 'tsmc',
        `the “Subjects” heading has something other than the subject under it:\n` +
        JSON.stringify(l, null, 1));
      // and it is a heading of its own, not a rename of one that was already there
      assert.ok(l.some(x => x.group === 'Content') && l.some(x => x.group === 'Notebooks'),
        `the subject group swallowed a group that was already there:\n${JSON.stringify(l, null, 1)}`);

      // the marks, side by side: ⌾ is memory's, and a subject is where memory
      // hangs, so those two above all have to be tellable apart at a glance
      const mem = l[l.findIndex(x => x.group === 'Memory') + 1];
      assert.equal(mem.ic, '⌾',
        'the fixture stopped putting memory’s mark on screen, so the assertion below compares ' +
        'the subject’s mark against nothing');
      assert.equal(sub.ic, '◈', `a subject wears the wrong mark: ${sub.ic}`);

      // The grey right-hand column. `subject:tsmc` there is the heading and the
      // label again, in the one slot on the row whose job is to say something the
      // row does not already say. How much is behind a name is what decides
      // whether it is worth going to, and it is the only thing that tells two
      // subjects apart. `:object` rides on the row too and is deliberately NOT
      // drawn: it is a snapshot act() must not read, and advertising it invites
      // exactly the short-circuit the design refused.
      assert.equal(sub.gp, '2 facts', `the grey column says nothing the row had not said: ${sub.gp}`);

      // Structural, and the second such assertion in this file, for the reason the
      // first one gives: a class contributes nothing to innerText, so no assertion
      // about the text on screen can see a subject row dressed as something else.
      // `cls` is a claim about the GESTURE — `space` promises Enter takes you
      // inside a notebook, `verb` promises Enter does something rather than going
      // somewhere — and a subject's Enter opens a page to read, which is what an
      // undressed row already promises.
      assert.equal(sub.cls, '',
        `a subject row is dressed as “${sub.cls}”, which promises something about Enter that ` +
        'acting on it does not keep');
    });
  });

test('selecting a subject asks the server for the name, never for the subject: id', async () => {
  await withPage(browser, 'leap-subject-acts', async (page) => {
    // the second row carries no `:name` at all. Not a shape the server sends —
    // `:label` and `:name` are the same string by construction — but the fallback
    // exists because `openEntity(undefined)` asks for a subject literally named
    // “undefined”, and that renders as a page rather than as an error. Two paths,
    // and this is the one that would otherwise never be walked.
    await stubLeap(page, [subjRow('tsmc'), subjRow('taiwan', { name: undefined })]);
    const asked = await stubEntity(page, PAGE());
    // Nothing on this row is an object id, so a request for one is the defect
    // itself: /api/object/subject:tsmc is answered with a null title and an empty
    // body, and the molder opens on it.
    const opened = await stubObjects(page, {});
    await bootedShell(page, server.url);
    await leapTo(page, 'tsmc');

    await page.locator('#results .res').nth(await rowIndex(page, 'tsmc')).click();
    // Either endpoint ends the wait and the assertions say which was reached.
    // Waiting on `asked` alone would let a fall-through to openObject run out a
    // thirty-second waitForSelector whose only message is that a selector never
    // appeared — which is how three of Task 8's sabotages "failed".
    await until(() => asked.length + opened.length > 0,
      'selecting the subject reached no endpoint at all');
    assert.deepEqual(opened, [],
      'the row was opened as an object — `subject:tsmc` is what the row IS, not where it ' +
      'goes, and nothing in the substrate has that id');
    assert.deepEqual(asked, ['tsmc'],
      'the name travels in its own field; re-parsing it out of the id would be a second place ' +
      'that has to know how a subject id is spelled');
    await page.waitForSelector('.modalcard .entname');
    // the palette is a keystroke sequence, and picking a row is what ends it
    assert.equal(await page.locator('#cmd.live').count(), 0,
      'the palette stayed live under the subject page it sent you to');

    // …and the row with no `:name` asks for what the reader can see on it
    await page.evaluate(() => closeModal());
    await leapTo(page, 'tsmc');
    await page.locator('#results .res').nth(await rowIndex(page, 'taiwan')).click();
    await until(() => asked.length > 1, 'the second subject reached no endpoint at all');
    assert.deepEqual(asked, ['tsmc', 'taiwan'],
      'a row that names no subject asked for “undefined”, which is a page and not an error');
  });
});

test('a subject and a document that read the same are two rows under two headings', async () => {
  await withPage(browser, 'leap-subject-collision', async (page) => {
    // `materialize-entity!` titles the notebook it mints with the entity's own
    // name, so a subject colliding with an object's title is the ordinary case
    // and not a contrived one. Suppressing either would hide a destination; one
    // row that guesses which was meant is not an answer either.
    await stubLeap(page, [
      { id: 'doc:teardown', label: 'tsmc', group: 'doc' },
      subjRow('tsmc', { count: 1 }),
    ]);
    await bootedShell(page, server.url);
    await leapTo(page, 'tsmc');

    const l = await listing(page);
    assert.equal(l.filter(x => x.row === 'tsmc').length, 2,
      `both destinations must be offered:\n${JSON.stringify(l, null, 1)}`);
    const ci = l.findIndex(x => x.group === 'Content');
    const si = l.findIndex(x => x.group === 'Subjects');
    assert.ok(ci >= 0 && si >= 0,
      `two rows reading the same under one heading is the collision the group exists to ` +
      `undo:\n${JSON.stringify(l, null, 1)}`);
    assert.deepEqual([l[ci + 1].row, l[ci + 1].ic], ['tsmc', '▤'],
      'the document must stay a document');
    assert.deepEqual([l[si + 1].row, l[si + 1].ic], ['tsmc', '◈'],
      'the subject is under its own heading with its own mark');
    // one fact, said in the singular — the grey column is prose, not a field dump
    assert.equal(l[si + 1].gp, '1 fact', `the count reads as a machine wrote it: ${l[si + 1].gp}`);
  });
});

test('the past offers no subject to act on, because it offers no palette at all', async () => {
  await withPage(browser, 'leap-subject-time-mode', async (page) => {
    // Why act()'s subject branch carries no TIME guard, pinned rather than
    // assumed. Every guarded branch in act() guards a WRITE — ask, create,
    // connect — and a subject is a destination, like a notebook or a document,
    // neither of which is guarded. Reading one in the past is fine; `openEntity`'s
    // one writing control refuses on its own, which the time-mode test above
    // pins. What makes a guard here unreachable rather than merely redundant is
    // this: the palette does not open in the past, and entering the past closes it
    // through overview().
    const seen = await stubLeap(page, [subjRow('tsmc')]);
    await bootedShell(page, server.url);
    await page.click('#timeBtn');
    await page.waitForFunction(() => typeof TIME !== 'undefined' && TIME);

    await page.click('#q');
    // Asserted here, before anything is waited for, and both halves of that matter.
    // The focus handler is synchronous — it either adds `live` or blurs and toasts,
    // and the click has returned — so there is nothing to wait for. And when the
    // guard is gone there is no toast at all: measured, waiting for one first turned
    // this test into a 31-second waitForSelector whose message was that a selector
    // never appeared, with the line below — the one that names the defect — never run.
    assert.equal(await page.locator('#cmd.live').count(), 0,
      'the palette opened in the past — a subject row is reachable there, so act() needs the ' +
      'guard this task argued it did not');
    await until(() => page.locator('.toast.on').count().then(n => n > 0),
      'the palette shut in the reader’s face and said nothing at all');
    const toast = (await page.innerText('.toast')).trim();
    assert.match(toast, /time mode/, `the palette shut without saying why: ${toast}`);
    assert.deepEqual(seen.lex, [],
      'the past asked /api/leap, whose answers are all about the present');
  });
});
