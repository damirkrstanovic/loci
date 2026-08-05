# Browser Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A headless-browser suite over `resources/public/index.html` covering six core interactions and the four UI defects fixed on 2026-08-05, run with `npm run test:browser`.

**Architecture:** `playwright-core` drives a browser already on disk, under Node 26's built-in test runner. A harness boots the Clojure handler on a free port against a throwaway `LOCI_DATA` (so the fixture is `content.clj`'s deterministic 35-event seed, never the user's `data/`), resolves a browser executable, and wraps each test with a page that dumps a screenshot and console log on failure.

**Tech Stack:** Node 26 (`node --test`), `playwright-core`, Clojure 1.12 server.

**Spec:** `docs/superpowers/specs/2026-08-06-browser-tests-design.md`.

**Facts the implementer needs:**
- The shell is one file, `resources/public/index.html` (1,155 lines). Read it — every selector below comes from it.
- Element ids: `#world`, `#q` (LEAP input), `#results`, `#crumb`, `#ambient` (the "N notebooks · M objects · K events" strip), `#memBtn`, `#fnBtn`, `#timeBtn`, `#undoBtn`, `#timebar`, `#timeRange`, `#timeNow`, `#toast`, `#modal`. Panels are `.panel`, their bodies `#body<i>`, titles `.p-title`.
- Useful globals on `window`: `STATE` (`{spaces, objects, events}`), `enter(i)`, `overview()`, `openObject(id)`, `mode`, `focusIdx`, `TIME`.
- In overview past ~7 notebooks the world gains class `cards`; in time mode it gains `timemode` and `#undoBtn` gains `dim`.
- `server.clj`'s `-main` hardcodes port 7777 — do NOT use `:serve`. Start the handler directly (the exact command is in Task 1).
- A fresh `LOCI_DATA` seeds **35 events, 35 objects, 12 spaces** including `space:cosmos`, `tbl:planets`, `doc:cosmos`.
- Node 26 is at `/usr/bin/node`. Browsers on this machine: `/home/damirk/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome` and `/usr/bin/chromium`.
- `clojure -M:test` is 80 tests / 280 assertions green on this branch and must not change — this plan adds no Clojure code.
- **Never point anything at `data/`.** Its md5 is `0c55ed4bc764f5b89984e0f1ca23ad33` and must stay that way.

---

### Task 1: package.json, harness, and one smoke test

The harness is the whole risk in this plan — if it can't boot a server and drive a browser reliably, nothing else matters. Build it against a single trivial assertion first.

**Files:**
- Create: `package.json`, `test/browser/harness.mjs`, `test/browser/shell.test.mjs`
- Modify: `.gitignore`

- [ ] **Step 1: `package.json`.**

```json
{
  "name": "loci-browser-tests",
  "private": true,
  "type": "module",
  "scripts": {
    "test:browser": "node --test test/browser/"
  },
  "devDependencies": {
    "playwright-core": "^1.62.0"
  }
}
```

Run `npm install`. It must NOT download a browser — `playwright-core` doesn't. Confirm with `du -sh node_modules` (expect single-digit MB).

- [ ] **Step 2: gitignore the generated artefacts.**

Append to `.gitignore`:

```
node_modules/
test/browser/failures/
```

- [ ] **Step 3: Write the harness.** Create `test/browser/harness.mjs`:

```js
// Boots a real loci server against a throwaway substrate, drives a real browser.
// The fixture is content.clj's deterministic seed — the user's data/ is never opened.
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { existsSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright-core';

const REPO = new URL('../../', import.meta.url).pathname;
export const FAILURES = join(REPO, 'test/browser/failures');

const freePort = () => new Promise((res, rej) => {
  const s = createServer();
  s.on('error', rej);
  s.listen(0, () => { const { port } = s.address(); s.close(() => res(port)); });
});

// One :flow object, so the flow-cell regression has something to render.
// The seed has no flows; this mirrors the shape of a real one.
const FLOW_FIXTURE = `
(require '[loci.content :as c] '[loci.substrate :as sub])
(let [st @c/store
      flow {:id "flow:test" :kind :flow :title "Flow — fixture"
            :value {:goal "a fixture flow" :space "space:cosmos" :status "failed"
                    :steps [{:verb "research" :args {:prompt "p"} :note "gather"
                             :status "done" :out "doc:cosmos"}
                            {:verb "compute" :args {:prompt "top 5"} :note "rank"
                             :status "failed" :why "fixture failure"}
                            {:verb "draft" :args {} :note "write it up" :status "pending"}]}}]
  (sub/commit! st {:op :tx :events [{:op :put :id "flow:test" :value flow}
                                    {:op :assoc :id "space:cosmos" :path [:value :cells]
                                     :value (conj (vec (get-in (sub/object st "space:cosmos") [:value :cells]))
                                                  {:ref "flow:test"})}]}))
(System/exit 0)
`;

const run = (args, env, cwd = REPO) => new Promise((res, rej) => {
  const p = spawn('clojure', args, { cwd, env: { ...process.env, ...env } });
  let out = '';
  p.stdout.on('data', d => out += d);
  p.stderr.on('data', d => out += d);
  p.on('exit', code => code === 0 ? res(out) : rej(new Error(`clojure ${args.join(' ')} exited ${code}:\n${out}`)));
});

export async function startServer() {
  const dir = await mkdtemp(join(tmpdir(), 'loci-browser-'));
  const port = await freePort();
  await run(['-M', '-e', FLOW_FIXTURE], { LOCI_DATA: dir });   // seeds, then adds the flow

  const proc = spawn('clojure', ['-M', '-e',
    `(require 'loci.server 'org.httpkit.server)
     (org.httpkit.server/run-server (var loci.server/handler) {:port ${port}})
     (println "ready") @(promise)`],
    { cwd: REPO, env: { ...process.env, LOCI_DATA: dir } });

  let log = '';
  proc.stdout.on('data', d => log += d);
  proc.stderr.on('data', d => log += d);

  const url = `http://localhost:${port}`;
  const deadline = Date.now() + 90_000;
  for (;;) {
    if (proc.exitCode !== null) throw new Error(`server died before it was ready:\n${log}`);
    try { const r = await fetch(`${url}/api/state`); if (r.ok) break; } catch {}
    if (Date.now() > deadline) throw new Error(`server never became ready in 90s:\n${log}`);
    await new Promise(r => setTimeout(r, 300));
  }
  return {
    url,
    async stop() { proc.kill(); await rm(dir, { recursive: true, force: true }); },
    serverLog: () => log,
  };
}

// A browser that is already on disk. Never download one from a test.
function browserPath() {
  const tried = [];
  if (process.env.PLAYWRIGHT_CHROMIUM) {
    tried.push(process.env.PLAYWRIGHT_CHROMIUM);
    if (existsSync(process.env.PLAYWRIGHT_CHROMIUM)) return process.env.PLAYWRIGHT_CHROMIUM;
  }
  const cache = join(process.env.HOME, '.cache/ms-playwright');
  if (existsSync(cache)) {
    for (const d of readdirSync(cache).filter(n => n.startsWith('chromium-')).sort().reverse()) {
      const p = join(cache, d, 'chrome-linux64/chrome');
      tried.push(p);
      if (existsSync(p)) return p;
    }
  }
  for (const p of ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome']) {
    tried.push(p);
    if (existsSync(p)) return p;
  }
  throw new Error(`no chromium found. Set $PLAYWRIGHT_CHROMIUM. Tried:\n  ${tried.join('\n  ')}`);
}

export const launchBrowser = () => chromium.launch({ executablePath: browserPath() });

// A page that reports what went wrong, with a picture.
export async function withPage(browser, name, fn) {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  const diag = { consoleErrors: [], pageErrors: [], failedRequests: [], all: [] };
  page.on('console', m => {
    diag.all.push(`console.${m.type()}: ${m.text()}`);
    if (m.type() === 'error') diag.consoleErrors.push(m.text());
  });
  page.on('pageerror', e => { diag.all.push(`pageerror: ${e.message}`); diag.pageErrors.push(e.message); });
  page.on('requestfailed', r => {
    const line = `requestfailed: ${r.url()} — ${r.failure()?.errorText}`;
    diag.all.push(line); diag.failedRequests.push(r.url());
  });
  try {
    return await fn(page, diag);
  } catch (err) {
    await mkdir(FAILURES, { recursive: true });
    const safe = name.replace(/[^a-z0-9]+/gi, '-');
    try { await page.screenshot({ path: join(FAILURES, `${safe}.png`), fullPage: false }); } catch {}
    await writeFile(join(FAILURES, `${safe}.log`),
      `${err.stack}\n\n--- browser diagnostics ---\n${diag.all.join('\n') || '(none)'}\n`);
    err.message += `\n  screenshot: test/browser/failures/${safe}.png`;
    throw err;
  } finally {
    await page.close();
  }
}

// The shell boots asynchronously; wait for the world to be populated.
export const bootedShell = async (page, url) => {
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => window.STATE && window.STATE.spaces && window.STATE.spaces.length > 0);
  return page;
};
```

- [ ] **Step 4: One smoke test.** Create `test/browser/shell.test.mjs`:

```js
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

test('boots and renders the seeded world', async () => {
  await withPage(browser, 'boots-and-renders', async (page) => {
    await bootedShell(page, server.url);
    const api = await (await fetch(`${server.url}/api/state`)).json();
    const strip = await page.textContent('#ambient');
    assert.match(strip, new RegExp(`${api.spaces.length} notebooks`), `topbar: ${strip}`);
    assert.match(strip, new RegExp(`${api.events} events`), `topbar: ${strip}`);
    assert.equal(await page.locator('.panel').count(), api.spaces.length);
  });
});
```

- [ ] **Step 5: Run it.**

Run: `npm run test:browser`

Expected: `# pass 1`, `# fail 0`. The first run boots a JVM twice (fixture + server), so allow up to 3 minutes.

If it fails, read `test/browser/failures/boots-and-renders.log` — that is the harness proving its own worth.

- [ ] **Step 6: Prove the failure path works.** Temporarily change the panel-count assertion to `api.spaces.length + 1`, run again, and confirm you get a failing test AND a PNG plus a log in `test/browser/failures/`. Look at the PNG. Then revert the sabotage.

Report what the screenshot showed — if it is blank or black, the harness is capturing at the wrong moment and must be fixed now, not later.

- [ ] **Step 7: Commit.**

```bash
git add package.json package-lock.json .gitignore test/browser/harness.mjs test/browser/shell.test.mjs
git commit -m "test: headless browser harness — real server, seeded fixture, screenshots on failure"
```

---

### Task 2: the five remaining core-interaction tests

**Files:**
- Modify: `test/browser/shell.test.mjs`

Add each test, run the file after each, and keep them independent — any test must pass when run alone. The shell keeps state in module globals, so a test that leaves the UI in focus mode or time mode will break the next one. **Every test must return the shell to overview, present-time, no object open** — do that by reloading the page in `bootedShell` at the start of each test rather than by unwinding.

- [ ] **Step 1: focus ↔ overview.**

```js
test('a panel click enters focus and the crumb returns to overview', async () => {
  await withPage(browser, 'focus-and-overview', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await page.waitForFunction(() => document.getElementById('world').classList.contains('overview'));

    const title = await page.locator('.panel .p-title').first().textContent();
    await page.locator('.panel').first().click();
    await page.waitForFunction(() => window.mode === 'focus');
    assert.match(await page.textContent('#crumb'), new RegExp(title.trim().slice(0, 20)));

    await page.locator('#crumb').click();
    await page.waitForFunction(() => window.mode === 'overview');
    assert.match(await page.textContent('#crumb'), /all notebooks/);
  });
});
```

- [ ] **Step 2: LEAP.**

```js
test('LEAP finds seeded content and a hit opens the object', async () => {
  await withPage(browser, 'leap-finds-and-opens', async (page) => {
    await bootedShell(page, server.url);
    await page.click('#q');
    await page.type('#q', 'planets', { delay: 30 });
    await page.waitForFunction(() => document.querySelectorAll('#results .res').length > 0);

    const hits = await page.locator('#results .res').allTextContents();
    assert.ok(hits.some(h => /planet/i.test(h)), `no planet hit in: ${JSON.stringify(hits)}`);

    const objHit = page.locator('#results .res').filter({ hasText: 'tbl:planets' }).first();
    await objHit.click();
    await page.waitForFunction(() => window.openId === 'tbl:planets');
  });
});
```

If `window.openId` is not a global in the shell, find what the shell does set when an object opens (read `openObject` in `index.html`) and assert on that instead — say so in your report.

- [ ] **Step 3: molding.**

```js
test('view this as re-molds a table into a chart', async () => {
  await withPage(browser, 'molding-a-table', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => openObject('tbl:planets'));
    await page.waitForSelector('#moldsel');

    const views = await page.locator('#moldsel option').allTextContents();
    assert.ok(views.length > 1, `expected several viewers, got ${JSON.stringify(views)}`);

    const chart = views.find(v => /bar|chart|over time/i.test(v));
    assert.ok(chart, `no chart-ish viewer among ${JSON.stringify(views)}`);
    await page.selectOption('#moldsel', { label: chart });
    await page.waitForSelector('#moldbody svg', { timeout: 10_000 });
  });
});
```

- [ ] **Step 4: time travel.**

```js
test('time mode drops the event count, hides writes, and ↩ now restores', async () => {
  await withPage(browser, 'time-travel', async (page) => {
    await bootedShell(page, server.url);
    const before = await page.textContent('#ambient');

    await page.click('#timeBtn');
    await page.waitForFunction(() => window.TIME !== null && window.TIME !== undefined);
    const max = Number(await page.getAttribute('#timeRange', 'max'));
    await page.evaluate(v => {
      const r = document.getElementById('timeRange');
      r.value = v; r.dispatchEvent(new Event('input', { bubbles: true }));
    }, String(Math.floor(max * 0.5)));

    await page.waitForFunction(e => document.getElementById('ambient').textContent !== e, before);
    assert.equal(await page.locator('.actions:visible').count(), 0, 'write affordances visible in the past');
    assert.ok(await page.evaluate(() => document.getElementById('world').classList.contains('timemode')));

    await page.click('#timeNow');
    await page.waitForFunction(e => document.getElementById('ambient').textContent === e, before);
  });
});
```

- [ ] **Step 5: write and undo through the UI.**

```js
test('+ prose adds a cell and ↺ undo reverts it', async () => {
  await withPage(browser, 'write-then-undo', async (page) => {
    await bootedShell(page, server.url);
    const events = () => page.evaluate(() => window.STATE.events);
    const before = await events();

    const i = await page.evaluate(() => STATE.spaces.findIndex(s => s.id === 'space:cosmos'));
    await page.evaluate(n => enter(n), i);
    await page.waitForSelector('#addProse');
    await page.click('#addProse');
    await page.waitForSelector('.prosed');
    await page.fill('.prosed', 'a note written by the browser suite');
    await page.keyboard.press('Meta+Enter').catch(() => {});
    // if that is not the save gesture, click the save control — read editProse in index.html
    await page.waitForFunction(b => window.STATE.events === b + 1, before, { timeout: 15_000 });

    await page.click('#undoBtn');
    await page.waitForFunction(b => window.STATE.events === b, before, { timeout: 15_000 });
  });
});
```

**`editProse`'s save gesture is not documented here on purpose** — read it in `index.html` and use whatever it actually is (a button, Enter, or blur). Report which. If prose editing turns out to have no deterministic save path, use `＋ Import CSV` instead: it posts a small CSV and commits one event.

- [ ] **Step 6: Run the file, then run each test in isolation.**

```bash
npm run test:browser
node --test --test-name-pattern='LEAP finds' test/browser/shell.test.mjs
node --test --test-name-pattern='time mode' test/browser/shell.test.mjs
```

All must pass both ways. A test that only passes in sequence is order-dependent — fix it.

- [ ] **Step 7: Commit.**

```bash
git add test/browser/shell.test.mjs
git commit -m "test: six core shell interactions under a real browser"
```

---

### Task 3: the four regression tests

Each pins a defect fixed on 2026-08-05. Name the defect in a comment above each test so a future reader knows what it is protecting.

**Files:**
- Create: `test/browser/regressions.test.mjs`

- [ ] **Step 1: Write the file.**

```js
// Regressions for the four UI defects found by hand on 2026-08-05.
// Each test names the bug it pins; see docs/superpowers/specs/2026-08-06-browser-tests-design.md
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

// BUG: flows have no viewers, so the molder rendered an empty <select> — dead chrome.
test('a flow renders no empty "view this as" control', async () => {
  await withPage(browser, 'flow-no-empty-select', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => openObject('flow:test'));
    await page.waitForSelector('.flowsteps');
    const empties = await page.evaluate(() =>
      [...document.querySelectorAll('select')].filter(s => s.options.length === 0).length);
    assert.equal(empties, 0, 'an empty <select> is dead chrome');
    // the flow itself must have rendered
    assert.equal(await page.locator('.flowstep').count(), 3);
    assert.match(await page.textContent('.flowsteps'), /fixture failure/);
  });
});

// BUG: past ~7 notebooks the overview scaled whole panels down until titles were ~3px.
test('the overview stays legible at 12 notebooks', async () => {
  await withPage(browser, 'overview-legibility', async (page) => {
    await bootedShell(page, server.url);
    await page.evaluate(() => overview());
    await page.waitForFunction(() => document.getElementById('world').classList.contains('cards'));

    const px = await page.evaluate(() => {
      const t = document.querySelector('.panel .p-title');
      return t.getBoundingClientRect().height;      // ON SCREEN, after the ZUI transform
    });
    assert.ok(px >= 12, `notebook titles render at ${px.toFixed(1)}px — unreadable`);
  });
});

// BUG: ↺ undo looked live in time mode; and the dim never cleared on ↩ now.
test('undo reads read-only in time mode and recovers on ↩ now', async () => {
  await withPage(browser, 'undo-dim-in-time-mode', async (page) => {
    await bootedShell(page, server.url);
    const dim = () => page.evaluate(() => document.getElementById('undoBtn').classList.contains('dim'));
    assert.equal(await dim(), false, 'dimmed before entering time mode');

    await page.click('#timeBtn');
    await page.waitForFunction(() => window.TIME);
    assert.equal(await dim(), true, 'undo looks live while the past is read-only');

    await page.click('#timeNow');
    await page.waitForFunction(() => !window.TIME);
    assert.equal(await dim(), false, 'dim never cleared on return to the present');
  });
});

// BUG: every page load 404'd on the favicon.
test('a clean boot makes no failed requests and logs no errors', async () => {
  await withPage(browser, 'clean-boot', async (page, diag) => {
    await bootedShell(page, server.url);
    await page.waitForTimeout(1000);          // let late requests land
    assert.deepEqual(diag.failedRequests, [], 'failed requests on boot');
    assert.deepEqual(diag.consoleErrors, [], 'console errors on boot');
    assert.deepEqual(diag.pageErrors, [], 'page errors on boot');
  });
});
```

- [ ] **Step 2: Run it.**

Run: `npm run test:browser`

Expected: 10 passing tests across both files, 0 failing.

- [ ] **Step 3: Prove each regression test actually bites.** For each of the four, break the fix and confirm the test fails, then restore. The four sabotages, in `resources/public/index.html`:

| test | sabotage |
|---|---|
| flow-no-empty-select | remove the `(c.alternatives||[]).length?` guard so the `<select>` always renders |
| overview-legibility | change the `cards` threshold `<0.3` to `<0.0` so cards mode never engages |
| undo-dim | delete the `classList.toggle('dim', …)` line in `paintTime` |
| clean-boot | delete the inline `<link rel="icon">` |

**Restore `index.html` exactly afterwards** — `git diff resources/public/index.html` must be empty before you commit. Report which sabotages produced a failure and which did not; a test that passes while its bug is present is worthless and must be fixed here.

- [ ] **Step 4: Commit.**

```bash
git add test/browser/regressions.test.mjs
git commit -m "test: pin the four UI defects found on 2026-08-05"
```

---

### Task 4: documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document both suites.** In the Run section, after the `clojure -M:test` line:

```markdown
# the unit tests (Clojure — substrate, notebook, memory, tools, server)
clojure -M:test

# the browser tests (Node — the shell, driven headless against a seeded fixture)
npm install        # once; pulls playwright-core, no browser download
npm run test:browser
```

Add below it:

```markdown
The two suites are deliberately separate: `clojure -M:test` stays fast and needs no
browser, while the browser suite boots its own server against a throwaway substrate — it
never touches `data/`. A failing browser test leaves a screenshot and a console log in
`test/browser/failures/`.
```

- [ ] **Step 2: Layout section.** Add:

```markdown
test/browser/            headless-browser tests for the shell (npm run test:browser)
```

- [ ] **Step 3: Verify both suites from a clean shell.**

```bash
clojure -M:test          # expect 80 tests / 280 assertions, 0 failures
npm run test:browser     # expect 10 tests, 0 failing
md5sum data/substrate.edn   # must be 0c55ed4bc764f5b89984e0f1ca23ad33
```

- [ ] **Step 4: Commit.**

```bash
git add README.md
git commit -m "docs: two suites — clojure -M:test and npm run test:browser"
```

---

## Verification checklist

- [ ] `npm run test:browser` — 10 tests, 0 failing
- [ ] Each test passes when run in isolation via `--test-name-pattern`
- [ ] All four sabotages produced failures; `index.html` restored byte-identical
- [ ] A failing test leaves a readable PNG and a `.log` in `test/browser/failures/`
- [ ] `clojure -M:test` unchanged at 80/280
- [ ] `data/substrate.edn` md5 still `0c55ed4bc764f5b89984e0f1ca23ad33`
- [ ] `node_modules/` and `test/browser/failures/` are gitignored and uncommitted

## Out of scope

- Agent verbs (Ask, Research, Flow, Draft, Deep-dive) — they need the LLM
- Making the agent injectable
- Visual regression / screenshot diffing
- CI configuration
- Cross-browser: Chromium only
