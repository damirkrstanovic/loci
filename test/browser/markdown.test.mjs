// A bare URL in agent prose is a link you can click.
//
// The user's research notes end in a `## Sources` list of bare URLs. mdToHtml
// only ever linkified `[title](url)`, so the whole list arrived as plain prose.
// These tests drive the REAL mdToHtml in the page — not a copy — and the last
// one drives the whole wiring: a doc cell in a real notebook, rendered by the
// real renderMold, clicked-through to a real <a href>.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// Renders markdown through the page's own mdToHtml and reads the result back
// as a *parsed DOM*, not as a string: getAttribute('href') is entity-decoded by
// the parser, which is exactly the round-trip a real click depends on.
const render = (page, md) => page.evaluate(m => {
  const host = document.createElement('div');
  host.innerHTML = mdToHtml(m);
  return {
    html: host.innerHTML,
    text: host.textContent,
    anchors: [...host.querySelectorAll('a')].map(a => ({
      href: a.getAttribute('href'),          // decoded, un-normalized
      resolved: a.href,                      // what the browser will actually fetch
      label: a.textContent,
      nested: a.querySelectorAll('a').length,
      inCode: !!a.closest('code'),
      attrs: [...a.attributes].map(x => x.name).sort(),
      parent: a.parentElement.tagName.toLowerCase(),
    })),
    codes: [...host.querySelectorAll('code')].map(c => c.textContent),
  };
}, md);

test('a bare URL in a Sources list becomes a clickable link', async () => {
  await withPage(browser, 'md-bare-url-list', async (page) => {
    await bootedShell(page, server.url);
    const r = await render(page,
      '## Sources\n\n- https://example.org/a/b\n- https://example.org/c?x=1\n');
    assert.deepEqual(r.anchors.map(a => a.href),
      ['https://example.org/a/b', 'https://example.org/c?x=1'],
      `bare URLs were not linkified: ${r.html}`);
    assert.deepEqual(r.anchors.map(a => a.label),
      ['https://example.org/a/b', 'https://example.org/c?x=1']);
    assert.deepEqual(r.anchors.map(a => a.parent), ['li', 'li'],
      'the links must stay inside their list items');
    assert.deepEqual(r.anchors.map(a => a.attrs),
      [['href', 'rel', 'target'], ['href', 'rel', 'target']],
      'same anchor shape as the markdown-link rule: target=_blank rel=noopener');
    assert.ok(/target="_blank"/.test(r.html) && /rel="noopener"/.test(r.html), r.html);

    // a bare URL in a paragraph too — the Sources list is not the only shape
    const p = await render(page, 'The field guide lives at https://example.org/guide and is short.');
    assert.deepEqual(p.anchors.map(a => a.href), ['https://example.org/guide']);
    assert.equal(p.text, 'The field guide lives at https://example.org/guide and is short.',
      'linkifying must not change the visible text');
  });
});

test('trailing punctuation stays out of the href, balanced parens stay in', async () => {
  await withPage(browser, 'md-url-punctuation', async (page) => {
    await bootedShell(page, server.url);

    const dot = await render(page, 'See https://example.org/page.');
    assert.deepEqual(dot.anchors.map(a => a.href), ['https://example.org/page'],
      'a sentence-final period is not part of the URL');
    assert.equal(dot.text, 'See https://example.org/page.', 'the period must survive as text');

    const paren = await render(page, 'A note (https://example.org/a) in brackets.');
    assert.deepEqual(paren.anchors.map(a => a.href), ['https://example.org/a'],
      'a wrapping ) is not part of the URL');
    assert.equal(paren.text, 'A note (https://example.org/a) in brackets.');

    const both = await render(page, 'End (https://example.org/a).');
    assert.deepEqual(both.anchors.map(a => a.href), ['https://example.org/a'],
      ') then . — the trim must repeat until it stops changing');

    const wiki = await render(page, '- https://en.wikipedia.org/wiki/Mercury_(planet)\n');
    assert.deepEqual(wiki.anchors.map(a => a.href),
      ['https://en.wikipedia.org/wiki/Mercury_(planet)'],
      'a BALANCED trailing ) belongs to the URL — research sources are full of these');

    const wikiDot = await render(page, 'See https://en.wikipedia.org/wiki/Mercury_(planet).');
    assert.deepEqual(wikiDot.anchors.map(a => a.href),
      ['https://en.wikipedia.org/wiki/Mercury_(planet)'],
      'the period goes, the balanced parens stay');

    const quoted = await render(page, 'Quoted "https://example.org/q" here.');
    assert.deepEqual(quoted.anchors.map(a => a.href), ['https://example.org/q'],
      'the escaped closing quote (&quot;) must not be swallowed into the href');

    const bracket = await render(page, 'Ref [https://example.org/r] here.');
    assert.deepEqual(bracket.anchors.map(a => a.href), ['https://example.org/r'],
      'an unbalanced trailing ] is not part of the URL');
  });
});

test('an & in a query string survives into the href', async () => {
  await withPage(browser, 'md-url-ampersand', async (page) => {
    await bootedShell(page, server.url);
    const r = await render(page, '- https://example.org/s?q=ice&t=1&u=2\n');
    assert.equal(r.anchors.length, 1, r.html);
    assert.equal(r.anchors[0].href, 'https://example.org/s?q=ice&t=1&u=2',
      'the DOM must decode &amp; back to & — this is the URL that gets fetched');
    assert.equal(r.anchors[0].resolved, 'https://example.org/s?q=ice&t=1&u=2');
    // …and the markup itself stays escaped. Emitting a raw & would be a hole.
    assert.ok(r.html.includes('href="https://example.org/s?q=ice&amp;t=1&amp;u=2"'),
      `the href must carry &amp; in the markup, not a raw &: ${r.html}`);

    const trailing = await render(page, 'Odd https://example.org/s?q=1& end.');
    assert.equal(trailing.anchors[0].href, 'https://example.org/s?q=1',
      'a trailing &amp; with nothing after it is punctuation, not query');
  });
});

test('an existing [title](url) still renders exactly once', async () => {
  await withPage(browser, 'md-existing-link', async (page) => {
    await bootedShell(page, server.url);
    const r = await render(page, 'Read [the field guide](https://example.org/guide) first.');
    assert.equal(r.anchors.length, 1, `expected one anchor, got ${r.html}`);
    assert.equal(r.anchors[0].href, 'https://example.org/guide');
    assert.equal(r.anchors[0].label, 'the field guide', 'the anchor text is still the title');
    assert.equal(r.anchors[0].nested, 0, 'a nested <a> inside an <a>');
    assert.equal((r.html.match(/<a\s/g) || []).length, 1, r.html);

    // …and bold inside a link still works: the autolink pass must NOT reorder the chain
    const b = await render(page, 'Read [**the** guide](https://example.org/guide).');
    assert.equal(b.anchors.length, 1);
    assert.ok(b.html.includes('<strong>the</strong>'), b.html);
  });
});

test('a link whose anchor text is itself a URL does not nest', async () => {
  await withPage(browser, 'md-url-labelled-url', async (page) => {
    await bootedShell(page, server.url);
    const r = await render(page, '- [https://example.org/x](https://example.org/x)\n');
    assert.equal(r.anchors.length, 1, `the anchor text was linkified again: ${r.html}`);
    assert.equal(r.anchors[0].nested, 0, 'a nested <a> inside an <a>');
    assert.equal(r.anchors[0].href, 'https://example.org/x');
    assert.equal(r.anchors[0].label, 'https://example.org/x');

    // The skip is a DEPTH, not a flag: a </code> inside the anchor text must not
    // re-arm linkifying for the rest of that anchor.
    const mixed = await render(page,
      'Read [see `https://example.org/z` and https://example.org/w](https://example.org/target).');
    assert.equal(mixed.anchors.length, 1, `something inside the anchor was linkified: ${mixed.html}`);
    assert.equal(mixed.anchors[0].nested, 0, 'a nested <a> inside an <a>');
    assert.equal(mixed.anchors[0].href, 'https://example.org/target');
    assert.ok(mixed.anchors[0].label.includes('and https://example.org/w'),
      `the text after </code> must stay text: ${mixed.html}`);
  });
});

test('a URL inside backticks stays code and is not linkified', async () => {
  await withPage(browser, 'md-url-in-code', async (page) => {
    await bootedShell(page, server.url);
    const r = await render(page, 'Call `https://example.org/api/v1` with a token.');
    assert.deepEqual(r.codes, ['https://example.org/api/v1'], r.html);
    assert.equal(r.anchors.length, 0, `a URL in backticks was linkified: ${r.html}`);
  });
});

test('the HTML escape still holds and javascript: is never linkified', async () => {
  await withPage(browser, 'md-url-escaping', async (page) => {
    await bootedShell(page, server.url);
    // the raw text a hostile source could put in a Sources list
    const r = await render(page, 'Bad https://example.org/"onmouseover="alert(1) here.');
    assert.equal(r.anchors.length, 1, r.html);
    assert.deepEqual(r.anchors[0].attrs, ['href', 'rel', 'target'],
      `the URL broke out of the href attribute: ${r.html}`);
    assert.equal(r.anchors[0].href, 'https://example.org/"onmouseover="alert(1)',
      'the quotes must stay INSIDE the attribute value');
    assert.ok(r.html.includes('&quot;'), `the markup must stay escaped: ${r.html}`);
    // every attribute the parser found anywhere in the fragment — an escape that
    // leaked would show up here as an extra a@onmouseover
    const attrs = await page.evaluate(() => {
      const host = document.createElement('div');
      host.innerHTML = mdToHtml('Bad https://example.org/"onmouseover="alert(1) here.');
      return [...host.querySelectorAll('*')]
        .flatMap(e => [...e.attributes].map(a => e.tagName.toLowerCase() + '@' + a.name));
    });
    assert.deepEqual(attrs, ['div@class', 'a@href', 'a@target', 'a@rel'],
      `an attribute escaped the href: ${JSON.stringify(attrs)}`);

    const js = await render(page, 'Try javascript:alert(1) or JAVASCRIPT:alert(2) now.');
    assert.equal(js.anchors.length, 0, `a javascript: URL was linkified: ${js.html}`);

    const data = await render(page, 'Or data:text/html;base64,PHNjcmlwdD4= now.');
    assert.equal(data.anchors.length, 0, `a data: URL was linkified: ${data.html}`);
  });
});

// The link is useless if it blows the panel out. A URL is one unbreakable token
// and a notebook cell is narrow, so measure the anchor ON SCREEN against its box.
test('a long bare URL wraps instead of overflowing a narrow cell', async () => {
  await withPage(browser, 'md-url-wrapping', async (page) => {
    await bootedShell(page, server.url);
    const m = await page.evaluate(() => {
      const host = document.createElement('div');
      host.style.cssText = 'position:absolute;left:-9999px;top:0;width:240px';
      host.innerHTML = mdToHtml(
        '- https://example.org/a-very-long-path-segment-that-cannot-break-anywhere/1234567890/abcdefghijklmnop\n');
      document.body.appendChild(host);
      const a = host.querySelector('a');
      const out = a ? { a: a.getBoundingClientRect().width,
                        box: a.closest('li').getBoundingClientRect().width,
                        wrap: getComputedStyle(a).overflowWrap } : null;
      host.remove();
      return out;
    });
    assert.ok(m, 'no anchor rendered — the wrapping check would be vacuous');
    assert.ok(m.box > 100, `the measuring box collapsed to ${m.box}px`);
    assert.ok(m.a <= m.box + 1,
      `the link is ${m.a.toFixed(0)}px wide inside a ${m.box.toFixed(0)}px box — ` +
      `it overflows the panel (overflow-wrap: ${m.wrap})`);
  });
});

// The one that pins the WIRING, which is what the user actually hit: a research
// note saved as a :doc, molded :text by doc/raw, drawn as a notebook cell.
test('a Sources list in a real notebook cell is clickable end to end', async () => {
  await withPage(browser, 'md-sources-cell-end-to-end', async (page) => {
    await bootedShell(page, server.url);
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    assert.ok(i >= 0, 'space:cosmos missing from the fixture');
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .cellmold`);
    await page.waitForFunction(n => [...document.querySelectorAll(`#body${n} .chip`)]
      .some(c => c.textContent === 'doc:sources'), i, { timeout: 20_000 });

    const cell = await page.evaluate(n => {
      const chip = [...document.querySelectorAll(`#body${n} .chip`)]
        .find(c => c.textContent === 'doc:sources');
      const mold = chip.closest('.cellmold');
      return {
        heading: !!mold.querySelector('.md h3'),
        hrefs: [...mold.querySelectorAll('.md a')].map(a => a.getAttribute('href')),
        text: mold.querySelector('.md').textContent,
      };
    }, i);

    assert.ok(cell.heading, 'the fixture doc did not render as markdown at all');
    assert.deepEqual(cell.hrefs, [
      'https://example.org/fixture/one',
      'https://example.org/fixture/search?q=one&two=2',
      'https://example.org/fixture/three',
    ], `the Sources list in a live notebook cell is not clickable: ${cell.text}`);
  });
});

// ---------------------------------------------------------------------------
// object ids the agent cited, resolved to what they name
// ---------------------------------------------------------------------------
//
// The ask and draft prompts instruct the agent to "cite object ids", and they
// arrived as dead text: "Mass figures from tbl:derived-2" told the reader
// nothing and could not be followed. Resolution is against STATE.objects, so
// only an id the workspace actually holds becomes a chip.

const refs = (page, md) => page.evaluate(m => {
  const host = document.createElement('div');
  host.innerHTML = mdToHtml(m);
  return {
    text: host.textContent,
    chips: [...host.querySelectorAll('.objref')].map(c => ({
      id: c.dataset.open, label: c.textContent, title: c.getAttribute('title'),
      inCode: !!c.closest('code'), inAnchor: !!c.closest('a'),
    })),
  };
}, md);

test('a cited id becomes the thing it names, and carries the id on hover', async () => {
  await withPage(browser, 'objref-resolves', async (page) => {
    await bootedShell(page, server.url);
    // a real object from the seed, whichever it is — read its title from STATE
    const { id, title } = await page.evaluate(() => {
      const o = Object.values(STATE.objects).find(o => o.kind === 'table');
      return { id: o.id, title: o.title };
    });
    const r = await refs(page, `Mass figures from ${id}; see also the notes.`);
    assert.equal(r.chips.length, 1, `expected one chip: ${JSON.stringify(r.chips)}`);
    assert.equal(r.chips[0].id, id);
    assert.equal(r.chips[0].title, id, 'the raw id stays reachable on hover');
    assert.ok(title.startsWith(r.chips[0].label.replace(/…$/, '')),
      `the chip must read as the object's title, not its id: ${r.chips[0].label}`);
    assert.ok(!r.text.includes(id),
      'the bare id should no longer be sitting in the prose');
  });
});

test('an id that names nothing stays plain text', async () => {
  await withPage(browser, 'objref-unresolved', async (page) => {
    await bootedShell(page, server.url);
    // a chip that opens nothing is worse than no chip
    const r = await refs(page, 'Figures from tbl:does-not-exist-9999 are missing.');
    assert.equal(r.chips.length, 0);
    assert.match(r.text, /tbl:does-not-exist-9999/, 'the text must survive unchanged');
  });
});

test('an ordinary colon phrase is not mistaken for an id', async () => {
  await withPage(browser, 'objref-not-a-id', async (page) => {
    await bootedShell(page, server.url);
    const r = await refs(page, 'note: see below. Also http://example.com/a:b for context.');
    assert.equal(r.chips.length, 0, `nothing here names an object: ${JSON.stringify(r.chips)}`);
    assert.match(r.text, /note: see below/);
  });
});

test('an id inside backticks is being shown, not cited', async () => {
  await withPage(browser, 'objref-in-code', async (page) => {
    await bootedShell(page, server.url);
    const { id } = await page.evaluate(() => {
      const o = Object.values(STATE.objects).find(o => o.kind === 'table');
      return { id: o.id };
    });
    const r = await refs(page, `The id is \`${id}\` exactly.`);
    assert.equal(r.chips.length, 0, 'code shows an id; it does not reference one');
    assert.match(r.text, new RegExp(id.replace(/[:.]/g, '\\$&')));
  });
});

test('a URL containing a real object id stays one link, not a chip inside a link', async () => {
  await withPage(browser, 'objref-url-intact', async (page) => {
    await bootedShell(page, server.url);
    // This is what makes the pass ORDER load-bearing. A plain https:// URL is
    // safe either way — the id pattern needs [a-z0-9] after the colon and a URL
    // has "//" — so an ordinary URL cannot tell the two orders apart. A URL with
    // a real id in its path can: run objlink first and the id becomes a chip
    // buried inside the href, which then linkifies into nonsense.
    const r = await page.evaluate(() => {
      const id = Object.values(STATE.objects).find(o => o.kind === 'table').id;
      const host = document.createElement('div');
      host.innerHTML = mdToHtml('See https://example.com/wiki/' + id + ' for more.');
      return { id,
               anchors: [...host.querySelectorAll('a')].map(a => a.getAttribute('href')),
               chips: host.querySelectorAll('.objref').length,
               chipsInAnchors: host.querySelectorAll('a .objref').length };
    });
    assert.deepEqual(r.anchors, ['https://example.com/wiki/' + r.id],
      'the href must survive whole');
    assert.equal(r.chipsInAnchors, 0, 'never a chip inside a link');
    assert.equal(r.chips, 0);
  });
});

test('a cited id in a real notebook cell is a chip you can click through', async () => {
  await withPage(browser, 'objref-cell-end-to-end', async (page) => {
    await bootedShell(page, server.url);
    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector(`#body${i} .cellmold`);
    await page.waitForFunction(n => [...document.querySelectorAll(`#body${n} .chip`)]
      .some(c => c.textContent === 'doc:sources'), i, { timeout: 20_000 });

    // the fixture prose cites an object AND a notebook; both must resolve, and
    // to DIFFERENT attributes — a space is entered, not opened as an object
    const chips = await page.evaluate(n => {
      const chip = [...document.querySelectorAll(`#body${n} .chip`)]
        .find(c => c.textContent === 'doc:sources');
      return [...chip.closest('.cellmold').querySelectorAll('.md .objref')]
        .map(c => ({ open: c.dataset.open || null, space: c.dataset.space || null,
                     label: c.textContent, title: c.getAttribute('title') }));
    }, i);
    assert.equal(chips.length, 2, `expected both citations to resolve: ${JSON.stringify(chips)}`);
    assert.deepEqual(chips.map(c => c.title), ['doc:sources', 'space:cosmos']);
    assert.equal(chips[0].open, 'doc:sources', 'an object opens');
    assert.equal(chips[1].space, 'space:cosmos', 'a notebook is entered, not opened');
    assert.ok(chips.every(c => !/^[a-z]+:/.test(c.label)),
      `a chip must read as a title, not an id: ${JSON.stringify(chips.map(c => c.label))}`);

    // and clicking the object one actually opens it
    await page.evaluate(n => document.querySelector(`#body${n} .md .objref[data-open]`).click(), i);
    await page.waitForSelector('.molder', { timeout: 10_000 });
    assert.match(await page.textContent('.molder'), /Sources — fixture/);
  });
});

// ---------------------------------------------------------------------------
// the paths that are NOT markdown
// ---------------------------------------------------------------------------
//
// A notebook's intent and a prose cell are plain sentences rendered with esc(),
// so the objref pass missed them entirely — and an id cited in an intent is
// exactly as dead as one cited in a draft. They get URLs and ids made
// followable and nothing else: full markdown there would start rendering a
// stray asterisk as emphasis in a line somebody typed by hand.

test('a cited id in a notebook intent is followable', async () => {
  await withPage(browser, 'objref-intent', async (page) => {
    await bootedShell(page, server.url);
    const chip = await page.evaluate(() => {
      const host = document.createElement('div');
      host.innerHTML = richInline(
        'Start here: read doc:sources, then mold tbl:nosuch-9999.');
      const c = [...host.querySelectorAll('.objref')];
      return { ids: c.map(x => x.dataset.open), text: host.textContent };
    });
    assert.deepEqual(chip.ids, ['doc:sources'],
      'the id that names something resolves; the one that does not is left alone');
    assert.match(chip.text, /tbl:nosuch-9999/, 'an id naming nothing stays as written');
  });
});

test('a plain sentence is not turned into markdown', async () => {
  await withPage(browser, 'richinline-not-markdown', async (page) => {
    await bootedShell(page, server.url);
    const out = await page.evaluate(() =>
      richInline('Rates rose 5*3 and #2 was **not** a heading'));
    assert.ok(!/<strong>|<em>|<h[1-6]>/.test(out),
      `an intent is one line somebody typed, not markdown: ${out}`);
    assert.match(out, /\*\*not\*\*/, 'the asterisks survive as characters');
  });
});

test('a URL in a prose cell is clickable too', async () => {
  await withPage(browser, 'richinline-url', async (page) => {
    await bootedShell(page, server.url);
    const hrefs = await page.evaluate(() => {
      const host = document.createElement('div');
      host.innerHTML = richInline('See https://example.org/fixture/one for the source.');
      return [...host.querySelectorAll('a')].map(a => a.getAttribute('href'));
    });
    assert.deepEqual(hrefs, ['https://example.org/fixture/one'],
      'the same URL is a link in a doc; it must not be dead in a prose cell');
  });
});
