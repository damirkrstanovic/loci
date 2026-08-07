// Boots a real loci server against a throwaway substrate, drives a real browser.
// The fixture is content.clj's deterministic seed — the user's data/ is never opened.
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { existsSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

// fileURLToPath, not URL.pathname: the latter is percent-encoded, so a repo
// checked out under a path with a space in it would spawn clojure in "/home/a%20b".
const REPO = dirname(dirname(dirname(fileURLToPath(import.meta.url))));
export const FAILURES = join(REPO, 'test/browser/failures');

// Stale artefacts read as fresh failures. Clear at module load — every test file
// does this before it boots a server, and long before any test can write one.
rmSync(FAILURES, { recursive: true, force: true });

// One :flow object, so the flow-cell regression has something to render.
// The seed has no flows; this mirrors the shape of a real one.
// nb/cells-of is what the server reads, and it falls back to :members when a
// space has no :cells — so build the new cell vector through it, or appending
// a cell would silently erase space:cosmos's three seeded members.
const FLOW_FIXTURE = `
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
                                     :value (conj (nb/cells-of (sub/object st "space:cosmos"))
                                                  {:ref "flow:test"})}]}))
`;

// A parent with two children, one of which has a child of its own — the
// overview's clustering is only meaningful against a real tree, and the
// deterministic seed contains none.
const FAMILY_FIXTURE = `
(let [st @c/store
      mk (fn [id title parent]
           {:op :put :id id
            :value (cond-> {:id id :kind :space :title title
                            :value {:intent (str title " — fixture") :cells []}}
                     parent (assoc-in [:value :spawned-by] {:space parent}))})]
  (sub/commit! st {:op :tx :events [(mk "space:fam-root" "Fixture hub" nil)
                                    (mk "space:fam-a" "Fixture child A" "space:fam-root")
                                    (mk "space:fam-b" "Fixture child B" "space:fam-root")
                                    (mk "space:fam-a1" "Fixture grandchild" "space:fam-a")]}))
`;

// Tags on the seeded corpus, so the strip has something to show. Two notebooks
// share a tag and one carries two, which is what makes include/exclude testable.
const TAG_FIXTURE = `
(let [st @c/store]
  (srv/set-tags! st "space:cosmos"  [{:tag "astronomy" :by "agent"}])
  (srv/set-tags! st "space:world"   [{:tag "world data" :by "agent"}])
  (srv/set-tags! st "space:finance" [{:tag "company" :by "you"}])
  (srv/set-tags! st "space:sales"   [{:tag "company" :by "you"} {:tag "pipeline" :by "you"}]))
`;

// http-kit binds the port itself and tells us which one it got. Asking the OS for a
// free port here and handing the number to a JVM that binds it ~7s later is a race —
// two test files start concurrently and would pick independently.
//
// The fixtures run HERE, in the server's own JVM, rather than in three `clojure -M -e`
// invocations of their own. Requiring loci.content loads the namespace and seeds the
// store, measured at 9.9s; paying that four times per test file was the whole cost of
// the browser suite. One JVM per file writes the same events in the same order.
const SERVER_MAIN = `
(require '[loci.server :as srv] '[loci.content :as c] '[loci.substrate :as sub]
         '[loci.notebook :as nb] 'org.httpkit.server)
${FLOW_FIXTURE}
${FAMILY_FIXTURE}
${TAG_FIXTURE}
(let [s (org.httpkit.server/run-server (var loci.server/handler)
          {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
  (println "LOCI-READY" (org.httpkit.server/server-port s))
  (flush)
  @(promise))
`;

// Every server we started and every directory we made, so an interrupted run
// leaves no JVM holding a port and no /tmp/loci-browser-* behind. `detached: true`
// (needed to kill the whole process group) also means SIGINT from the terminal
// never reaches the JVM — nothing else would reap it.
const LIVE = new Set();
const killGroup = pid => {
  if (!pid) return;
  try { process.kill(-pid, 'SIGKILL'); } catch { try { process.kill(pid, 'SIGKILL'); } catch {} }
};
const reap = () => {
  for (const rec of LIVE) {
    killGroup(rec.pid);
    try { rmSync(rec.dir, { recursive: true, force: true }); } catch {}
  }
  LIVE.clear();
};
let cleanupInstalled = false;
function installCleanup() {
  if (cleanupInstalled) return;
  cleanupInstalled = true;
  process.on('exit', reap);
  // a listener replaces node's default die-on-signal, so exit explicitly
  process.on('SIGINT', () => { reap(); process.exit(130); });
  process.on('SIGTERM', () => { reap(); process.exit(143); });
  process.on('SIGHUP', () => { reap(); process.exit(129); });
}

async function bootServer() {
  installCleanup();
  const dir = await mkdtemp(join(tmpdir(), 'loci-browser-'));
  const rec = { pid: null, dir };
  LIVE.add(rec);

  // detached: the `clojure` wrapper may still be a bash script when we kill it,
  // so signal the whole process group — never orphan a JVM holding the port.
  const proc = spawn('clojure', ['-M', '-e', SERVER_MAIN],
    { cwd: REPO, env: { ...process.env, LOCI_DATA: dir }, detached: true });
  rec.pid = proc.pid;

  let log = '';
  proc.stdout.on('data', d => log += d);
  proc.stderr.on('data', d => log += d);
  const exited = new Promise(r => proc.on('exit', r));

  const kill = () => killGroup(proc.pid);

  let port = null;
  const deadline = Date.now() + 120_000;
  for (;;) {
    if (proc.exitCode !== null) throw new Error(`server died before it was ready:\n${log}`);
    if (port === null) { const m = log.match(/LOCI-READY (\d+)/); if (m) port = Number(m[1]); }
    else {
      try { const r = await fetch(`http://127.0.0.1:${port}/api/state`); if (r.ok) break; } catch {}
    }
    if (Date.now() > deadline) { kill(); throw new Error(`server never became ready in 120s:\n${log}`); }
    await new Promise(r => setTimeout(r, 300));
  }
  return {
    url: `http://127.0.0.1:${port}`,
    async stop() { LIVE.delete(rec); kill(); await exited; await rm(dir, { recursive: true, force: true }); },
    serverLog: () => log,
  };
}

// One server for the whole run. `npm run test:browser` passes --test-isolation=none,
// so node loads every test file into one process and this memo spans the suite: six
// cold JVMs become two — overview.test.mjs opts out, and says there why. A cold one
// costs 13.6s from spawn to a live /api/state, measured on an idle machine.
//
// This buys resources, not wall time, and the next person to touch it should know
// that before "fixing" it: measured here the suite takes 104–122s over six parallel
// worker processes and 121–135s in one serial process. Node was already running the
// six files concurrently, and --test-isolation=none gives that up. What it does buy
// is two JVMs and one browser instead of six of each — so the suite stops needing six
// cores not to thrash, and a reported test duration becomes the test's own work
// instead of a measure of contention (one read 84.5s under the old layout).
//
// Refcounted, not "whoever calls stop() first kills it": node runs every file's root
// `after` hook, and each one stops the server it was handed. Only the last release
// reaps. Run one file on its own — `node --test test/browser/tags.test.mjs` — and the
// count is 1, so that path behaves exactly as it did before.
//
// `{ fresh: true }` opts out into a private server and a private substrate.
let sharedServer = null, serverUsers = 0;
export async function startServer({ fresh = false } = {}) {
  if (fresh) return bootServer();
  serverUsers++;
  sharedServer ??= bootServer().then(s => ({
    url: s.url,
    serverLog: s.serverLog,
    async stop() { if (--serverUsers === 0) { sharedServer = null; await s.stop(); } },
  }));
  return sharedServer;
}

// One browser too, on the same refcount, for the same reason — a chromium launch
// per file is pure overhead. newPage() opens a fresh context each time, so pages
// stay as isolated from each other as they were when every file had its own browser.
let sharedBrowser = null, browserUsers = 0;
export async function launchBrowser() {
  browserUsers++;
  sharedBrowser ??= chromium.launch({ executablePath: browserPath() });
  const b = await sharedBrowser;
  return {
    newPage: (...a) => b.newPage(...a),
    async close() { if (--browserUsers === 0) { sharedBrowser = null; await b.close(); } },
  };
}

// A browser that is already on disk. Never download one from a test.
function browserPath() {
  // $PLAYWRIGHT_CHROMIUM is an override, not a hint: falling through to the cache
  // when it is set but wrong would silently test a different browser than asked for.
  const pinned = process.env.PLAYWRIGHT_CHROMIUM;
  if (pinned) {
    if (existsSync(pinned)) return pinned;
    throw new Error(`$PLAYWRIGHT_CHROMIUM is set to ${pinned}, which does not exist. ` +
                    `Fix it or unset it to fall back to the playwright cache.`);
  }
  const tried = [];
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

// A page that reports what went wrong, with a picture.
export async function withPage(browser, name, fn) {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  // consoleErrors carries {text, url}: a failed resource load says only "Failed to
  // load resource", so without the location a caller cannot tell one of ours from a
  // font on a CDN — see the origin filter in the clean-boot regression.
  const diag = { consoleErrors: [], pageErrors: [], failedRequests: [], all: [] };
  page.on('console', m => {
    diag.all.push(`console.${m.type()}: ${m.text()} ← ${m.location().url}`);
    if (m.type() === 'error') diag.consoleErrors.push({ text: m.text(), url: m.location().url });
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
    const shot = join(FAILURES, `${safe}.png`);
    let shotNote = `screenshot: ${shot}`;
    try { await page.screenshot({ path: shot, fullPage: false }); }
    catch (e) { shotNote = `screenshot FAILED: ${e.message}`; }
    err.message += `\n  ${shotNote}`;
    await writeFile(join(FAILURES, `${safe}.log`),
      `${err.stack}\n\n${shotNote}\n\n--- browser diagnostics ---\n${diag.all.join('\n') || '(none)'}\n`);
    throw err;
  } finally {
    await page.close();
  }
}

// The shell boots asynchronously; wait for the world to be populated.
// index.html declares `let STATE` at the top level of a classic <script>, which
// is a global *lexical* binding — it never becomes a property of `window`.
export const bootedShell = async (page, url) => {
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => {
    const s = typeof STATE !== 'undefined' ? STATE : window.STATE;
    return !!(s && s.spaces && s.spaces.length > 0);
  });
  await page.locator('.panel').first().waitFor({ state: 'attached' });
  return page;
};
