# Semantic LEAP — chunks, the deep query, the shell

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** LEAP finds what you wrote, by meaning — without touching the keystroke path.

**Spec:** `docs/superpowers/specs/2026-08-08-semantic-leap-design.md`

**Three tasks:** the chunk store; the deep query; the shell.

---

## Context for every task

The embedding layer exists and is proven: `src/loci/embed.clj` (`embed-texts`, `cosine`,
config resolvers, blank-is-unset, unset-key-means-no-header) and `src/loci/memory.clj` (facts
carrying `:vec`/`:model`/`:dim`, embedded off the write path by `embed-pending!`, scheduled by
`start-embed-worker!`, merged at a per-model threshold). Read both before starting — this task
extends the same pattern to substrate objects and should not invent a second one.

**Measured on the live corpus:** 35 notebooks, 22 doc bodies (40,846 chars, median 2,027, max
5,078), 22 tables (2,584 rows), 7 prose cells, 5 reports, 2 metrics → **~94 chunks**. Exact
cosine over ~100 candidates is 0.61 ms. **No vector index.** LEAP's current lexical scan is
0.26–0.71 ms.

## CRITICAL, every task

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. **Check and report both.** The chunk store's real path is
  `(sub/data-dir)/chunks.edn`; every test must pass an explicit temp path, exactly as the
  memory tests do. Never deref a delay that resolves to the user's directory.
- **Do not call a real model or embedder.** A live key is in the repo and the environment.
  Stub; the suite must pass offline.
- Never print or commit a token. No `pkill -f`. **Never overstate what the code does.**
- **Verification budget:** single test file while iterating; `clojure -M:test` once per task;
  `npm run test:browser` only in Task 3.

---

### Task 1: Chunks — build, hash, sweep

**Files:** create `src/loci/chunks.clj`, `test/loci/chunks_test.clj`

- [ ] **Step 1: Write the failing tests.**

Chunking is a **pure function of an object** — test it with no store and no embedder:

- a notebook → **one** chunk from title + intent
- a doc body → chunks split on markdown headings, each ≤ ~600 tokens (≈2400 chars), and a
  section longer than that splits again **without splitting mid-heading**; a body with no
  headings still yields at least one chunk
- a table → **one synopsis** containing its title and column names and **not** its rows.
  Assert on a table with many rows that the synopsis length is bounded — 2,584 row-vectors is
  the failure this rule exists to prevent
- prose cells → one chunk each; metrics and reports → covered
- `:viewspec`, `:applet`, `:fn`, `:palette` → **no** chunks
- **determinism:** chunking the same object twice yields identical ids **and** hashes
- **stability:** editing one doc changes only that doc's chunk hashes; every other object's
  ids and hashes are byte-identical

Then the store, against a **temp path**:

- `sweep!` embeds pending chunks and persists them; a second `sweep!` embeds nothing
- editing a doc's text makes exactly its chunks pending again — nothing else
- changing the configured model makes **every** chunk pending
- **deleting the chunks file and sweeping restores byte-equivalent ids and hashes** — this is
  the test that keeps "derived, never truth" honest
- the embedder being unreachable leaves chunks unembedded, is not an error, and the next
  sweep picks them up (resumable)

- [ ] **Step 2: Run, watch fail.** **Step 3: Implement.**

`data/chunks.edn`, append-only EDN lines, last-wins by `:id` — the same mechanism
`loci.memory` uses. Chunk id is stable and derived from the object id plus its index
(`"doc:findings-4#2"`). `:hash` is over the chunk text; `:object` is what a hit points at.

**Chunks are derived and must never become substrate events.** Do not commit them to the log:
it would make derived data undoable, drag it into time travel, and let a stale vector answer a
question about the past. Say that in the namespace docstring.

- [ ] **Step 4: Green. Step 5: Prove it bites.** One batch, distinct tests: make the hash
  cover the object id instead of the text (editing stops invalidating); emit one chunk per
  table row (the synopsis bound fails); make chunk ids depend on iteration order (determinism
  fails).

- [ ] **Step 6: Commit** — `feat: the substrate gets chunks, derived and rebuildable`

---

### Task 2: The deep query

**Files:** modify `src/loci/server.clj` (`leap-payload`, the `/api/leap` route, `-main`); tests

- [ ] **Step 1: Write the failing tests.**

- `GET /api/leap?q=…` — unchanged, byte-identical to today, and makes **zero** embedding
  requests. Assert on a stubbed embedder that received nothing. This is the keystroke path and
  it is the point of the whole design.
- `?deep=1` adds a **`by meaning`** group; a chunk whose text shares no word with the query is
  returned, and its `:id` is the **object**, not the chunk
- an object already returned by a lexical group is **not** duplicated into the semantic group
- `?deep=1` with no embedder configured returns exactly what `deep` absent returns
- with the embedder unreachable, the response is today's plus an honest degraded marker — not
  an error, not an empty page
- the semantic group is capped like the others

- [ ] **Step 2-3: fail, implement.** Reuse `embed/cosine`; do not reimplement scoring. Do
  **not** rerank — the spec says why. Do **not** scope by notebook, tag or lineage: LEAP is
  the gesture that must reach everything, and the recall scoping decision does not apply here.

- [ ] **Step 4: Wire the sweep.** `-main` already starts memory's embed worker when
  `embedding-configured?`. The chunk sweep belongs on the same tick — decide the cleanest
  wiring (extend that worker, or a server-level tick that calls both) and **justify it in a
  comment**. Constraints: nothing starts on require; nothing blocks a write; if the embedder
  is unconfigured neither runs and startup says so.

- [ ] **Step 5: Green. Step 6: Prove it bites.** One batch, distinct tests: make the plain
  path embed the query (the zero-requests assertion fails); drop the already-found-lexically
  filter (the duplicate test fails); return the chunk id instead of the object id.

- [ ] **Step 7:** `clojure -M:test`. **Commit** — `feat: LEAP can search by meaning, off the keystroke path`

---

### Task 3: The shell

**Files:** modify `resources/public/index.html`; create `test/browser/semanticleap.test.mjs`

- [ ] **Step 1: Read `leap()` and its input handler in full** before editing. Find how the
  current request is issued and debounced, and match it.

- [ ] **Step 2: Implement.** On input: today's request, unchanged, immediately. Then after a
  **~250 ms pause**, and on Enter, a second request with `deep=1` whose results **merge into**
  the list as a `by meaning` group.

  **The earlier results must not flicker or be replaced** — the deep response arrives later and
  adds to what is on screen. If the query has moved on by the time it lands, drop it: compare
  against the current input value, and do not let a stale response overwrite a newer one. That
  race is the most likely defect here.

- [ ] **Step 3: Browser tests** in a new file, following `test/browser/tags.test.mjs` for
  structure and `harness.mjs` for the server. Intercept `/api/leap` with `page.route` so no
  embedder is needed:
  - typing shows lexical results immediately, before any deep response
  - the `by meaning` group appears after the pause, and the earlier results are **still there**
  - a slow deep response for an **old** query does not overwrite results for the new one —
    assert on what is rendered, not on request counts
  - with the deep response empty, the list is exactly the lexical list

- [ ] **Step 4: Prove they bite.** Break: remove the debounce so deep fires per keystroke;
  replace rather than merge the results; drop the staleness check. Each must fail a distinct
  test. Report real output.

- [ ] **Step 5:** `npm run test:browser` once, and `clojure -M:test` once. Check both `data/`
  md5s. **Commit** — `feat: LEAP deepens on a pause, and keeps what it already showed`

## Out of scope

- Reranking LEAP results; row-level table vectors; a vector index
- Scoping LEAP; embedding viewspecs/applets/functions
- Datalevin phase 2
