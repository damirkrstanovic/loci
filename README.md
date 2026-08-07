# loci

A humane, Raskin-faithful interface for the agent age — *agent-as-verb, not agent-as-app*.
One substrate, one LEAP gesture, content stored once and **molded** on demand.

This repo is the Clojure build. (The earlier single-file interaction prototype is `index.html`.)

## Architecture (in dependency order)

| Layer | Role | record / recall |
|---|---|---|
| 1. Event log | deterministic, reversible — durable on Datalevin (embedded LMDB) behind the `Store` seam | **record** |
| 2. Content store | tables, documents, blobs — stored canonically, `datafy`/`nav`-able | **record** |
| 3. Recall / AI-memory | `loci.memory` — Clojure-native engine (keyword+entity+recency+strength), persisted, agent-written | **recall** |
| 4. Mold layer | views molded per object, by user *or* agent (Clerk viewer registry) | both |
| 5. Shell | spaces + LEAP | — |

The **mold layer** (`loci.mold`) is built first and is intentionally UI-free: a viewer is
data (`{:id :label :pred :render}`), the registry is data, and a view's `:render` returns
plain Clojure data that any target (Clerk, Portal, a terminal) can draw.

The **recall layer** is a Clojure-native engine (`loci.memory`) behind the
`loci.mold/Recall` protocol — facts distilled by the agent after every flow,
with provenance, reinforcement and decay. Undo never touches it: undo reverts
the record, not the recall. (A Khora sidecar can still replace it — same seam.)

## Run

```bash
# the loci shell — Clojure substrate served to a web frontend (layer 5)
clojure -M:serve        # then open http://localhost:7777

# headless proof of layers 1/2/4 — no browser
clojure -M:demo

# Clerk notebook (an alternative render target)
clojure -X:start        # starts Clerk + opens notebooks/loci.clj

# the unit tests — Clojure: substrate, notebook, memory, tools, server
clojure -M:test

# the browser tests — Node: the shell itself, driven headless
npm install            # once; pulls playwright-core, downloads no browser
npm run test:browser
```

### Packaged

```bash
# a single jar that runs from anywhere
clojure -T:build uber
java -jar target/loci-standalone.jar          # PORT and LOCI_DATA are honoured

# or in a container, with the substrate on a volume
docker build -t loci .
docker run -p 7777:7777 -v loci-data:/data loci
```

The jar carries `Enable-Native-Access: ALL-UNNAMED` in its manifest, standing in
for the `--enable-native-access=ALL-UNNAMED` that `deps.edn` supplies per-alias
and that a bare `java -jar` has no alias to get. It is not what makes Datalevin
work: measured on JDK 26, LMDB opens without it and merely warns that a
restricted method was called. What the attribute buys is a quiet startup now,
and a jar that keeps running when a future JDK blocks restricted native access
instead of warning about it. (The JDK honours it only for `java -jar` — launched
as `java -cp loci-standalone.jar loci.server` it is ignored, and the warnings
come back.)

`LOCI_DATA` defaults to a **relative** `data/`, so a jar launched from elsewhere
starts an empty substrate rather than finding yours — the startup line prints
which directory it opened, for exactly that reason. The image declares
`VOLUME /data`, so even an unmounted `docker run` keeps its substrate across
`docker restart`; but that anonymous volume belongs to the container, and
replacing the container (or `--rm`) starts again from the seed. Mount a named
volume to keep a substrate that outlives it.

Both API keys are read from `DEEPSEEK_API_KEY` and `SEARCH_API_KEY`/`TAVILY_API_KEY`
before falling back to files in the working directory; pass them with `-e` rather
than baking them into an image. `.dockerignore` keeps the repo's own `.deepseek-key`
and `.tavily-key` out of the build context for the same reason.

`clojure -M:serve` persists its state under `data/` — the substrate is a
Datalevin (LMDB) store at `data/substrate`, the agent's memory an event log at
`data/memory.edn`. Delete that directory to reset to a clean slate. A pre-2026-08
EDN log at `data/substrate.edn` is migrated with `clojure -M:migrate`, which
imports it verbatim and verifies the two logs materialize to the same state.

**What rollback costs, stated plainly.** The migration never modifies
`data/substrate.edn`, and `loci.substrate/PersistentStore` is kept — it is the
parity reference the test suite holds `DatalevinStore` to, and it is the
rollback. But the rollback only recovers the log as it stood at migration.
The Datalevin write path serializes with nippy and therefore drops
`safe-event`'s pr-str/read round-trip check (see `src/loci/dlv.clj`), so events
written since are not guaranteed to be EDN-expressible, and there is no
`datalevin->edn!` to carry them back. Reverting `content.clj` after a week on
the new store means losing everything committed in that week. That is the
accepted trade: the check existed to stop an unreadable line truncating a text
file, and a KV store has no lines to truncate.

The two suites are deliberately separate: `clojure -M:test` stays fast and needs no
browser, while the browser suite boots its own server against a throwaway substrate — it
never opens `data/`. A failing browser test leaves a screenshot and a console log in
`test/browser/failures/` (cleared at the start of every run), because the defect that
motivated the suite (notebook titles rendering at ~3px when zoomed out) was invisible to
every DOM assertion and only caught by looking.

Zoomed out, the overview groups each notebook with the ones it spawned — the
structure the substrate already computes from `spawned-by` and `merged-from`, rather than
laying every notebook out as a peer. Cards are sized to their own content, the canvas
scrolls, and a focused notebook names its parent, siblings and children in one line under
its intention.

✧ Suggest reads a notebook and proposes two or three questions worth pursuing. You see them
before anything happens: edit the wording, uncheck what you don't want, then choose whether
the answers land in new connected notebooks or as cells in this one. Proposing writes
nothing at all — dismissing leaves the substrate untouched.

LEAP ranks what it finds by what you touched most recently — a notebook counts as
touched when anything inside it is. From the overview it also offers `⊟ Filter the
overview`, which dims everything that does not match and stays until you enter a notebook
or clear it from the breadcrumb.

Notebooks carry tags — the one thing the substrate cannot work out for itself, since
structure is computed but subject is not. The agent proposes them from what is actually in
a notebook and you approve, edit or ignore; each tag records whether you or the agent
asserted it, and proposing writes nothing at all. In the overview a tag chip cycles
include → exclude → neither, and composes with LEAP's filter: a notebook is lit when it
satisfies both.

Each tag carries a colour, so a tag is recognisable before it is read. The first time a tag
is set it takes the least-used ink from a palette of eight; the swatch on its chip opens the
palette, and changing it changes that tag everywhere at once. On a chip the swatch carries
identity and the fill carries state — an included tag fills with its own ink, an excluded
one is struck in clay with its swatch intact. Undoing a tagging removes the tags and leaves
the colour, because a colour is a standing preference about a subject rather than a claim
about a notebook.

The browser suite needs two things beyond `npm install`:

- **Node 22 or newer.** `npm run test:browser` uses `node --test` with a glob, which
  older Node does not understand and reports as a missing file.
- **A Chromium already on disk.** `playwright-core` deliberately downloads no browser.
  The harness looks in `~/.cache/ms-playwright/chromium-*` (put one there with
  `npx playwright install chromium`), then `/usr/bin/chromium`,
  `/usr/bin/chromium-browser`, `/usr/bin/google-chrome`. To use a specific binary, set
  `PLAYWRIGHT_CHROMIUM=/path/to/chrome` — it is an override, not a hint: if it is set
  and the path does not exist the suite fails and names it rather than quietly running
  a different browser.

The suite is offline-safe: `index.html` loads IBM Plex from a CDN, and every network
assertion is filtered to the test server's own origin, so no network is not a red suite.

The shell talks to the Clojure backend over a JSON API — the HTTP boundary is the
substrate/assistance seam. Molding is done server-side by `loci.mold`; the
frontend only lays out the result.

| endpoint | returns |
|---|---|
| `GET /` | the shell |
| `GET /api/state` | spaces + objects |
| `GET /api/object/:id` | an object's default mold + the "view this as…" menu |
| `GET /api/mold?id=&view=` | the object re-molded by a chosen viewer |
| `GET /api/leap?q=` | incremental find across content + view-verbs |
| `GET /api/undo` | revert the last substrate event |
| `GET/POST /api/notebook` | a notebook's hydrated cells / one cell operation |
| `GET /api/links?space=` | computed connectedness (shares / spawned / lineage) |
| `GET /api/memory?q=` | the agent's memory — browsable, recall-ranked |
| `POST /api/research` | agent research as a background job — returns a job id |
| `POST /api/suggest` | the agent proposes questions (background job); writes nothing |
| `POST /api/suggest-run` | research the questions you approved, in new notebooks or here |
| `GET /api/job?id=` | poll a background agent job — the shell never holds a long fetch |
| `GET /api/fns?id=` | the ƒ palette for a table: built-in verbs + agent-written functions |
| `POST /api/fn-preview` | run a function over the live rows — before/after, commits nothing |
| `POST /api/fn-apply` | keep the result: derived table + cell, ONE reversible event, `from`/`via` provenance |
| `GET /api/events` | the log, humanely labeled — feeds the ⏱ scrubber |
| any read + `?at=N` | the same payload as-of event N — read-only time travel |
| `POST /api/rerun` | recompute a derived table + everything downstream — one reversible event |
| `POST /api/connect` | a NEW notebook unioning two others — non-destructive, originals intact |
| `POST /api/flow` | the agent plans + runs a multi-step flow (background job) |
| `POST /api/flow-gate` | answer a flow's gate: approve resumes, reject stops |
| `POST /api/tags` | replace a notebook's tags — one reversible event |
| `POST /api/tag-suggest` | the agent proposes tags (background job); writes nothing |
| `POST /api/tag-color` | choose a tag's colour — one reversible event |

## Layout

```
src/loci/substrate.clj   layer 1: append-only event log behind a Store protocol
src/loci/dlv.clj         layer 1, durable: the event log on Datalevin (LMDB) + touch index
src/loci/migrate.clj     one-shot import of the old EDN log, verified by state equality
src/loci/content.clj     layer 2: populated content + viewers; datafy/nav
src/loci/mold.clj        layer 4: viewer registry, mold, Recall protocol (UI-free)
src/loci/fnlib.clj       built-in single-table transforms — the ƒ function palette (UI-free)
src/loci/memory.clj      layer 3: AI-memory engine (Recall protocol, persisted)
src/loci/notebook.clj    notebook = space: cells, cell ops, computed links
src/loci/server.clj      layer 5 backend: substrate + mold served as JSON
resources/public/index.html   layer 5 frontend: spaces + LEAP shell
src/loci/demo.clj        headless walkthrough  (clojure -M:demo)
notebooks/loci.clj       Clerk render target (alternative to the shell)
test/browser/            headless-browser tests for the shell (npm run test:browser)
docs/walkthrough.md      the four demo flows, step by step
```
