# loci — headless browser tests for the shell: design

Date: 2026-08-06
Status: accepted in brainstorming

## Goal

Give `resources/public/index.html` — 1,155 lines of vanilla JS with no test coverage of any
kind — a headless-browser suite that protects the core interactions and the four UI bugs
found by hand on 2026-08-05.

The motivating evidence: that day's browser pass found four defects the Clojure suite could
not see, and one of them (the overview rendering notebook titles at ~3px past seven
notebooks) was invisible to any DOM assertion. It was caught by looking at a screenshot.
A suite that only asserts is not enough; it has to leave an image behind when it fails.

## Decisions

- **Separate from `clojure -M:test`.** Browser tests run under `npm run test:browser`.
  Hooking them into the Clojure suite would make a 12-second unit run depend on node, a
  browser and a live server, and let a browser flake fail the unit suite. The cost, stated
  plainly: `clojure -M:test` no longer means "all the tests", so the README must say both.
- **Node's built-in test runner** (`node --test`, stable in Node 26). No jest, no vitest,
  no mocha. One devDependency: `playwright-core`.
- **`playwright-core`, not `playwright`.** The full package downloads ~150 MB of browsers on
  install; `playwright-core` is ~2 MB and drives a browser already on disk.
- **The fixture is a fresh seed, never `data/`.** Each run gets a throwaway `LOCI_DATA`, so
  `content.clj` seeds its deterministic 35 events / 35 objects / 12 spaces. The user's real
  substrate is never opened, and no failing test can corrupt it.
- **Screenshots and console logs on failure**, written to `test/browser/failures/`
  (gitignored). See the motivating evidence above.
- **No agent verbs.** Ask, Research, Flow, Draft and Deep-dive all require the LLM. Testing
  them means making the agent injectable, which is a production change and a separate piece
  of work. `＋ prose` and `＋ Import CSV` are the only LLM-free write paths, and they are
  enough for a genuine end-to-end write-and-undo test.

## 1. Structure

```
package.json                        playwright-core devDep, "test:browser" script
test/browser/harness.mjs            server lifecycle, browser resolution, page fixture
test/browser/shell.test.mjs         6 core-interaction tests
test/browser/regressions.test.mjs   4 tests for the 2026-08-05 fixes
test/browser/failures/              screenshots + console logs (gitignored)
```

## 2. The harness

Four responsibilities, each independently usable:

**`startServer()`** — asks the OS for a free port, creates a temp `LOCI_DATA` directory,
commits one `:flow` fixture object into it (see §3), spawns the Clojure handler on that
port, polls `/api/state` until it answers, and returns `{url, stop}`. `stop` kills the
process and removes the temp directory. `server.clj`'s `-main` hardcodes port 7777, so the
harness starts the handler directly with `clojure -M -e` rather than through `:serve`.

**`launchBrowser()`** — resolves an executable in order: `$PLAYWRIGHT_CHROMIUM`, then the
Playwright cache under `~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome`, then
`/usr/bin/chromium`. If none exists it throws naming every path it tried — a missing
browser must never look like a failing assertion.

**`withPage(name, fn)`** — opens a page, records `console` errors, page errors and failed
requests, runs `fn(page, diagnostics)`, and closes the page. On throw it writes
`failures/<name>.png` and `failures/<name>.log` before rethrowing.

**Teardown** — one server and one browser are shared by all tests in a file (booting the
JVM per test would dominate the runtime); both are closed in an `after` hook.

## 3. The flow fixture

The seed contains no `:flow` object, so regression 7 (a flow cell must not render an empty
"view this as" control) would be untestable against a fresh seed. Before starting the
server, the harness commits one `:flow` object into the temp store, mirroring the shape of
the real `flow:1`: a goal, `:status "failed"`, and steps in the states the mold renders
differently (`done`, `failed` with a `:why`, `pending`). It is added to `space:cosmos` as a
cell so it renders both inline and in the molder.

## 4. Coverage

**Core interactions** (`shell.test.mjs`):

1. **boots and renders the seeded world** — the topbar's "N notebooks · M objects · K events"
   agrees with `/api/state`, and 12 panels exist
2. **focus ↔ overview** — clicking a panel enters focus and the crumb returns to overview
3. **LEAP** — typing `planets` yields grouped results; clicking a hit opens that object
4. **molding** — opening `tbl:planets` offers alternatives in "view this as"; switching to a
   chart view renders an `<svg>`
5. **⏱ time travel** — entering time mode drops the event count, hides every write
   affordance, and `↩ now` restores both
6. **write and undo through the UI** — `＋ prose` adds a cell and increments the event
   counter; `↺ undo` reverts both

**Regressions** (`regressions.test.mjs`), each naming the 2026-08-05 defect it pins:

7. **no empty molder control** — a flow has no viewers, and an empty `<select>` is dead chrome
8. **overview legibility** — at 12 notebooks a panel title measures ≥ 12px on screen (it was
   ~3px before the card-mode fix; asserting the *rendered* height is what makes this catchable)
9. **undo reads read-only in time mode** — `.dim` present in time mode, absent after `↩ now`
   (the second half is its own bug: the class did not clear on return)
10. **a clean boot** — zero console errors and zero failed requests (the favicon 404)

## 5. Failure behaviour

A failing assertion leaves `test/browser/failures/<test-name>.png` and `.log`. The log holds
every console message, page error and failed request seen during that test, in order. Both
are overwritten per run and the directory is gitignored.

A test that cannot reach the server fails with the server's captured stdout/stderr in the
message, not a bare timeout — a JVM that failed to boot should say why.

## 6. What this does not cover

- Agent verbs (Ask, Research, Flow, Draft, Deep-dive) — they need the LLM
- The memory pane and ƒ functions modal — reachable, but their content is agent-produced
- Applet rendering (`app:` objects run agent-written canvas code)
- Any assertion about visual style beyond the measured legibility threshold in test 8
- Cross-browser: Chromium only

## Out of scope

- Making the agent injectable so agent flows can be tested
- Visual regression / screenshot diffing
- CI configuration — there is no CI in this repo yet
- Testing the Clerk notebook render target
