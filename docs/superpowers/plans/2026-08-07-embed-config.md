# Embedder & Reranker Configuration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** the embedding and reranking endpoints, models and tokens are configuration, so
loci can be pointed at a local box without touching source.

**Spec:** `docs/superpowers/specs/2026-08-07-semantic-recall-design.md` §1

**Scope:** resolution only. No embedding client, no cosine, no fusion — those are the next
step and depend on this one.

---

### Task 1: Resolve embedder and reranker configuration

**Files:**
- Create: `src/loci/embed.clj`
- Modify: `loci.env.example`, `README.md`, `.gitignore`
- Test: `test/loci/embed_test.clj`

**Read `src/loci/agent.clj` first.** It establishes the pattern this must match exactly:
a private `env` fn wrapping `System/getenv` (the seam that makes this testable — you cannot
`with-redefs` a Java static), a `from-file` helper, and `endpoint`/`model`/`api-key` as
**defns**, not defs, so a value is resolved per call rather than frozen at namespace load.

Resolution order per the spec, matching `agent.clj`'s convention — **environment, then file,
then default**:

| fn | env | file | default |
|---|---|---|---|
| `embed-endpoint` | `LOCI_EMBED_ENDPOINT` | `.embed-endpoint` | `nil` |
| `embed-model` | `LOCI_EMBED_MODEL` | `.embed-model` | `"embed-qwen3-0.6b"` |
| `embed-key` | `LOCI_EMBED_API_KEY` | — | falls back to `LOCI_LLM_API_KEY`, else `nil` |
| `rerank-endpoint` | `LOCI_RERANK_ENDPOINT` | `.rerank-endpoint` | `nil` |
| `rerank-model` | `LOCI_RERANK_MODEL` | `.rerank-model` | `"rerank-bge-m3"` |
| `rerank-key` | `LOCI_RERANK_API_KEY` | — | falls back to `embed-key` | 

Plus two predicates the later work and `/api/memory` will use:
`(embedding-configured?)` and `(rerank-configured?)` — true when the respective endpoint
resolves to something non-blank.

- [ ] **Step 1: Write the failing tests** in `test/loci/embed_test.clj`

Cover, using a `with-redefs` on the private `env` seam (see `test/loci/server_test.clj` for
how the agent tests do it — a bare `loci.embed/env` symbol, not `#'loci.embed/env`, which
fails with `ClassCastException: Cons cannot be cast to Symbol`):

- every default with nothing set: endpoints `nil`, models the two named above, keys `nil`
- each env var overrides its default
- each endpoint/model file overrides the default but **loses to** its env var
- `LOCI_EMBED_API_KEY` wins over `LOCI_LLM_API_KEY`; with only `LOCI_LLM_API_KEY` set, the
  embed key resolves to it
- `LOCI_RERANK_API_KEY` wins over `LOCI_EMBED_API_KEY`; with neither, rerank falls all the
  way through to `LOCI_LLM_API_KEY`
- **a blank or whitespace-only value is treated as unset**, not as a configured empty string
  — an empty `LOCI_EMBED_ENDPOINT=` in a `loci.env` is how this will actually be got wrong,
  and `embedding-configured?` must be false there
- `embedding-configured?` / `rerank-configured?` are false by default and true once their
  endpoint is set — and they are **independent**: embed configured with rerank unset must
  leave `rerank-configured?` false

- [ ] **Step 2: Run them, watch them fail.** `clojure -M:test 2>&1 | tail -20`

- [ ] **Step 3: Implement `src/loci/embed.clj`.**

Namespace docstring should say what this is for and what it deliberately is not yet: the
resolution layer for phase 3a, with no client in it. Mirror `agent.clj`'s `from-file` rather
than duplicating a subtly different one — if it is worth sharing, share it and say why in a
comment; if copying is cleaner than a new shared namespace, say that instead.

**One thing that is not a nicety and must be honoured** (spec §1): an unset key means the
request carries **no** `Authorization` header, not an empty one. A llama.cpp started without
`--api-key` rejects an empty bearer, which would turn "no key needed" into a 401 that reads
like a wrong key. There is no client here yet, so encode it in the contract: `embed-key`
returns `nil` — never `""` — and say why in its docstring so the client author cannot get it
wrong.

- [ ] **Step 4: Run the tests.** Green.

- [ ] **Step 5: Prove they bite.** Break resolution in three ways in one batch, confirming
  each fails a *distinct* test, then restore: (a) drop the env lookup from `embed-endpoint`
  so only the file is consulted; (b) make `embed-key` fall back to `LOCI_LLM_API_KEY` first
  instead of preferring `LOCI_EMBED_API_KEY`; (c) treat blank as configured. Report the
  actual failure output for each. If two breaks fail the same test, isolate them.

- [ ] **Step 6: `.gitignore`** — add `.embed-endpoint`, `.embed-model`, `.rerank-endpoint`,
  `.rerank-model`. They are working-directory config files this plan tells people to create;
  without this they show up as untracked repo noise. (`.llm-endpoint` is already there —
  follow it.)

- [ ] **Step 7: Docs.**

`loci.env.example` — add the six variables in the spec's order, commented out, each with one
line saying what happens when it is absent. Match the file's existing voice: it says what
fails loudly and what fails quietly. Note that with no embed endpoint, recall is exactly what
it is today.

`README.md` — extend the configuration table with the six rows. Add a short paragraph: the
embedder takes its own token because the obvious setup is a hosted chat model plus a local
embedder, two operators and two credentials; `LOCI_LLM_API_KEY` remains the single-provider
shortcut.

- [ ] **Step 8: Full suite once.** `clojure -M:test 2>&1 | tail -3`. The browser suite cannot
  be affected by this change — do **not** run it.

- [ ] **Step 9: Commit**

```bash
git add src/loci/embed.clj test/loci/embed_test.clj loci.env.example README.md .gitignore
git commit -m "feat: the embedder and reranker are configuration — endpoint, model and token"
```

## Out of scope

- Any HTTP call to an embedder or reranker
- Cosine, fusion, the async worker, dedup calibration
- Touching `loci.memory` or the `Recall` protocol
