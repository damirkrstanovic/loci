# loci

A humane, Raskin-faithful interface for the agent age — *agent-as-verb, not agent-as-app*.
One substrate, one LEAP gesture, content stored once and **molded** on demand.

This repo is the Clojure build. (The earlier single-file interaction prototype is `index.html`.)

## Architecture (in dependency order)

| Layer | Role | record / recall |
|---|---|---|
| 1. Event log | deterministic, reversible (an immutable time-aware DB later: XTDB / Datahike) | **record** |
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

# run the unit tests
clojure -M:test
```

`clojure -M:serve` persists its state under `data/` — the substrate is a
Datalevin (LMDB) store at `data/substrate`, the agent's memory an event log at
`data/memory.edn`. Delete that directory to reset to a clean slate. A pre-2026-08
EDN log at `data/substrate.edn` is migrated with `clojure -M -m loci.migrate`.

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
| `POST /api/deep-dive` | spawn + research connected sub-notebooks (background job) |
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
docs/walkthrough.md      the four demo flows, step by step
```
