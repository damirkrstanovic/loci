# Semantic Recall — client, vectors, fusion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `recall` finds by meaning, not substring — without blocking a write, without
breaking when the embedder is down, and without putting a network round trip in LEAP's
keystroke path.

**Spec:** `docs/superpowers/specs/2026-08-07-semantic-recall-design.md` (config layer already
built in `src/loci/embed.clj`, `15c64b3`).

**Three tasks:** the client; facts that carry vectors; hybrid `recall`.

---

## Context for every task

`loci.memory/FileMemory` holds `!facts` — an atom of `{id → fact}` — over an append-only
EDN-lines file, last-wins by `:id`. `remember` does Jaccard ≥ 0.6 dedup and appends;
`recall` scores `0.6·keyword + 0.4·entity`, times a 30-day half-life decay, times a strength
bonus. `src/loci/embed.clj` already resolves endpoint/model/key for embed and rerank, with
blank treated as unset and an unset key resolving to `nil` (never `""`).

**Measured against the real corpus 2026-08-07** — vectors are **1024-dim and L2-normalised
(norm 1.0)**; merge threshold calibrated at **0.85**; rerank returns **raw logits**
(0.687 down to −11.04), not probabilities.

## CRITICAL, all tasks

- **Never write to, point anything at, or modify `data/`** — the user's real substrate and
  memory (`data/substrate.edn` md5 `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`). Check both, report both. Tests use temp files.
- **Tests must not need a live embedder.** Write a local stub server; the suite must pass
  offline and in CI. Do not call `lizard10.local` from a test.
- **Never print or commit a token.** `loci.env` holds real keys — do not echo its values.
- **Do not use `pkill -f`.** Comments explain WHY; **never overstate what the code does.**
- **Verification budget:** iterate with the single test file; full `clojure -M:test` at the
  end of each task; **do not run the browser suite** — none of this reaches the shell.

---

### Task 1: The client — embed, rerank, cosine

**Files:** modify `src/loci/embed.clj`; create `test/loci/embed_client_test.clj`

- [ ] **Step 1: Write the failing tests, against a stub you start in the test**

Use a tiny in-process HTTP server (http-kit is already a dependency — `org.httpkit.server/run-server` on port 0, read the bound port, exactly as `test/browser/harness.mjs` does for its JVM). Cover:

- `embed-texts` with no endpoint configured returns `{:off true}` — **not** an error, and
  makes no request
- a normal response yields `{:vectors [[…]] :model … :dim 1024}`, vectors in **input order**
  (the API returns objects with an `:index`; a response deliberately shuffled in the stub
  must still come back in the caller's order — this is the bug that will otherwise pair a
  fact with someone else's vector)
- **no key configured → the request carries no `Authorization` header at all.** Assert on the
  header map the stub received. A llama.cpp without `--api-key` rejects an empty bearer, so
  `Bearer ` is worse than nothing. With a key, exactly one `Bearer <key>` header.
- a non-200, a connection refused, and a malformed body each return `{:error …}` — never throw
- **a dimension that disagrees with the first vector in the same response is an error**, not a
  silently ragged result
- `rerank` returns `[{:index i :score s} …]` sorted best-first; with no rerank endpoint it
  returns `{:off true}` and the caller proceeds unranked
- `cosine` is correct: identical vectors → 1.0, orthogonal → 0.0, opposite → −1.0, and it
  agrees with a hand-computed value on a small non-unit pair

- [ ] **Step 2: Run, watch fail.** `clojure -M:test 2>&1 | tail -20`

- [ ] **Step 3: Implement.**

`cosine` must be **true cosine** — `dot / (norm-a · norm-b)` — not a bare dot product.
The current model returns unit vectors so the divisor is 1.0, but that is a property of
`embed-qwen3-0.6b`, not of the format, and a future model that does not normalise would
silently produce nonsense scores. Say exactly that in a comment; do not write a comment
claiming the vectors are always normalised.

Guard against a zero vector (norm 0) → return 0.0 rather than dividing by zero.

- [ ] **Step 4: Green.**

- [ ] **Step 5: Prove it bites.** In one batch, three breaks that must fail *distinct* tests:
  drop the `:index` reordering; send `Bearer ` when the key is nil; make `cosine` a bare dot
  product (this one needs the non-unit test to catch it — confirm it does). Report the real
  output; if a break fails no test, that test is missing and you must add it.

- [ ] **Step 6: Commit** — `feat: an embedding and reranking client that degrades honestly`

---

### Task 2: Facts carry vectors, embedded off the write path

**Files:** modify `src/loci/memory.clj`, `src/loci/mold.clj` (protocol); tests

- [ ] **Step 1: Write the failing tests**

- **`remember` does not block.** With the embed endpoint pointed at a **dead port**,
  `remember` returns `:ok` and the fact is in `!facts` — and it is not measurably slower than
  with embedding unconfigured. Assert with a timing bound generous enough not to be flaky
  (e.g. under 100 ms) and say in a comment why the bound is loose.
- after the worker runs, the fact has `:vec` (1024 floats from the stub), `:model` and `:dim`
- a fact whose embedding failed has **no** `:vec`, is not marked embedded, and is picked up by
  a second backfill pass — resumability
- `embed-pending!` selects only facts lacking a `:vec` **for the current model**; a fact
  carrying a vector from model A is pending again once the configured model is B
- the vector is persisted: append the same `:id` with `:vec` added, reload the file, and the
  vector is still there (this is the whole storage design — one append, no new store)
- `all-facts` is now a **protocol function** on `Recall`, and `loci.memory/all-facts` keeps
  working for existing callers (`server.clj:1268`, `demo.clj`) — check both compile
- new fact ids do not collide after a deletion: ids come from `max existing + 1`, not
  `(inc (count …))`. Seed a store with `mem-1`/`mem-5`, remove `mem-5` from the atom, remember
  something new, assert the id is `mem-6` and nothing was overwritten.

- [ ] **Step 2: Run, watch fail.**

- [ ] **Step 3: Implement.**

The worker: a single-threaded queue is enough at this scale, and simpler than a pool. Whatever
you choose, **it must not be started by requiring the namespace** — a test that loads
`loci.memory` must not spawn a thread that outlives it. Provide `embed-pending!` as a plain
function the server can call, and make any background scheduling explicit and stoppable. Say
in a comment what happens if two workers run at once (they both append the same `:id` with the
same `:vec`; last-wins makes that harmless, but state it rather than leaving it to be
rediscovered).

Store `:model` and `:dim` on every embedded fact. A vector without them is unusable.

- [ ] **Step 4: Green.**

- [ ] **Step 5: Prove it bites.** Break, in one batch: make `remember` await the embedding
  (the dead-port test must fail on time or on error); drop `:model` from the stored fact (the
  model-change test must fail); use `(inc (count …))` for ids (the collision test must fail).

- [ ] **Step 6: Commit** — `feat: facts carry vectors, embedded after the write and never during it`

---

### Task 3: Hybrid recall

**Files:** modify `src/loci/memory.clj`, `src/loci/server.clj` call sites; tests

- [ ] **Step 1: Read this before designing the signature**

`recall` is called from four places (`server.clj` `remembered-context`, `leap-payload`,
the post-flow distill hook, `/api/memory`). **`/api/leap` runs on every keystroke.** Embedding
a query is a 20–50 ms network round trip, so the semantic half must be **opt-in**:

- `opts` gains `:semantic? false` **by default** — LEAP's keystroke path stays exactly as fast
  as today
- `remembered-context` (the agent's own recall) and `/api/memory?q=` pass `:semantic? true`
- `:rerank?` is separately opt-in and defaults false

If you find a call site where this is wrong, say so rather than quietly changing its latency.

- [ ] **Step 2: Write the failing tests**

- lexical-only (`:semantic? false`) returns exactly what it returns today — a regression guard
  on the existing scorer, with the embedder stubbed and **unused**: assert the stub received
  zero requests
- with `:semantic? true`, a fact that shares **no words** with the query is returned; use a
  stub returning hand-built vectors so the test is deterministic and needs no model
- a fact that only lexical finds is still returned when semantic is on — fusion must not let
  one retriever starve the other
- each hit carries **which retriever matched** (`:via` containing `:lexical`, `:semantic`, or
  both) and, when reranked, the rerank score
- facts with **no** `:vec` still compete lexically when `:semantic? true`
- a fact embedded under model A is excluded from the semantic half when the configured model
  is B — and is reported in the "awaiting" count rather than silently dropped
- **scoped filter**: `{:filter {:sources #{…}}}` returns only facts whose `:source` is in the
  set; an empty result is an empty result — assert it does **not** fall back to global
- with the embedder unreachable and `:semantic? true`, recall still returns lexical results
  and marks itself degraded rather than erroring

- [ ] **Step 3: Run, watch fail.**

- [ ] **Step 4: Implement.**

Fusion is **reciprocal rank**: for each retriever that ran, a fact at rank *r* contributes
`1/(60+r)`; sum across retrievers. Then optionally rerank the top ~20, then multiply by decay
and the strength bonus so "this keeps coming up" still counts. The constant 60 is the standard
RRF damping — say in a comment that it was not tuned here and what would tune it.

**The scope filter takes a set of source ids, not a space id.** `loci.memory` must not import
the substrate to walk `nb/links`; the server has the store and does the traversal (measured at
0.200 ms), then passes `{:filter {:sources #{…}}}`. Keeping memory ignorant of the substrate is
the record/recall separation the whole design rests on.

- [ ] **Step 5: Green.**

- [ ] **Step 6: Prove it bites.** In one batch: make `:semantic? false` still embed (the
  zero-requests assertion must fail); make fusion take only the semantic ranking (the
  lexical-only-fact test must fail); make the scope filter fall back to global on empty (that
  test must fail).

- [ ] **Step 7: Wire the call sites.** `remembered-context` and `/api/memory?q=` pass
  `:semantic? true`; `leap-payload` does not. `/api/memory` reports how many facts await
  embedding. Nothing in the shell changes in this task.

- [ ] **Step 8: Full suite.** `clojure -M:test`. Then check both `data/` md5s.

- [ ] **Step 9: Commit** — `feat: recall fuses words and meaning, and says which one found it`

## Out of scope

- Background **semantic merge** at the calibrated 0.85 — the spec's §5.1, its own step after this
- Semantic LEAP over substrate objects (chunking, the chunk store)
- Any UI change; the memory pane's "found by meaning" display comes later
- Tuning the RRF constant, or a rerank score cutoff
