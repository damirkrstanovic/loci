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
  s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => res(port)); });
});

// One :flow object, so the flow-cell regression has something to render.
// The seed has no flows; this mirrors the shape of a real one.
// nb/cells-of is what the server reads, and it falls back to :members when a
// space has no :cells — so build the new cell vector through it, or appending
// a cell would silently erase space:cosmos's three seeded members.
const FLOW_FIXTURE = `
(require '[loci.content :as c] '[loci.substrate :as sub] '[loci.notebook :as nb])
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
(System/exit 0)
`;

const run = (args, env, cwd = REPO) => new Promise((res, rej) => {
  const p = spawn('clojure', args, { cwd, env: { ...process.env, ...env } });
  let out = '';
  p.stdout.on('data', d => out += d);
  p.stderr.on('data', d => out += d);
  p.on('error', rej);
  p.on('exit', code => code === 0 ? res(out) : rej(new Error(`clojure ${args.join(' ')} exited ${code}:\n${out}`)));
});

export async function startServer() {
  const dir = await mkdtemp(join(tmpdir(), 'loci-browser-'));
  const port = await freePort();
  await run(['-M', '-e', FLOW_FIXTURE], { LOCI_DATA: dir });   // seeds, then adds the flow

  // detached: the `clojure` wrapper may still be a bash script when we kill it,
  // so signal the whole process group — never orphan a JVM holding the port.
  const proc = spawn('clojure', ['-M', '-e',
    `(require 'loci.server 'org.httpkit.server)
     (org.httpkit.server/run-server (var loci.server/handler) {:port ${port}})
     (println "ready") @(promise)`],
    { cwd: REPO, env: { ...process.env, LOCI_DATA: dir }, detached: true });

  let log = '';
  proc.stdout.on('data', d => log += d);
  proc.stderr.on('data', d => log += d);
  const exited = new Promise(r => proc.on('exit', r));

  const kill = () => { try { process.kill(-proc.pid, 'SIGKILL'); } catch { try { proc.kill('SIGKILL'); } catch {} } };

  const url = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 120_000;
  for (;;) {
    if (proc.exitCode !== null) throw new Error(`server died before it was ready:\n${log}`);
    try { const r = await fetch(`${url}/api/state`); if (r.ok) break; } catch {}
    if (Date.now() > deadline) { kill(); throw new Error(`server never became ready in 120s:\n${log}`); }
    await new Promise(r => setTimeout(r, 300));
  }
  return {
    url,
    async stop() { kill(); await exited; await rm(dir, { recursive: true, force: true }); },
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
