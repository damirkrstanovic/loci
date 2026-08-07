# Configurable Endpoints, and One JVM Per Browser Run

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** (A) point loci at any OpenAI-compatible LLM, so it can run entirely on local
hardware. (B) stop the browser suite paying a cold JVM per test file.

**Tech Stack:** Clojure (`src/loci/agent.clj`, `src/loci/tools.clj`), Node test runner
(`test/browser/harness.mjs`).

---

## CRITICAL, both tasks

- **Never write to, point a server at, or modify `data/`** — the user's real substrate,
  `md5 e7a6eecdff7bb44ad6b959bb1a7fb7c6`. Check it before and after; report it.
- **Do not use `pkill -f`** — a pattern matching your own shell command kills the shell.
- **Do not call a real model.** `/api/new-space`, `/api/ask`, `/api/research` all hit the
  agent, and a live DeepSeek key is present in the repo and environment. Use a **local stub
  server** you write yourself, or `/api/connect`, which is model-free.
- **Never write a secret to a file**, and do not put one in a prompt or a commit.
- **Do not write a comment that overstates what the code does.** Repeatedly treated as a
  defect on this repo.
- **Verification budget.** Single-file test runs while iterating (`node --test <file>`,
  or `clojure -M:test` only when the Clojure side changed). Full suites once, at the end.

---

### Task A: The LLM endpoint becomes configurable

**Files:** `src/loci/agent.clj` (`:17`, `:22`, `:25`), `test/loci/`, `loci.env.example`, `README.md`

The endpoint is hardcoded:

```clojure
src/loci/agent.clj:17  (def ^:private endpoint "https://api.deepseek.com/chat/completions")
```

Only the key and model are configurable. So loci cannot be pointed at a local
OpenAI-compatible server (the user runs one at `lizard10.local:8080`) even though the wire
format is identical. This is also the precondition for the embeddings/rerank work.

- [ ] **Step 1: Write the failing tests**

In the existing Clojure test namespace for the agent (find it; if there is none, add tests to
`test/loci/server_test.clj` beside the other `agent/*` redefs). Test the **resolution
functions**, not the network:

- with nothing set, the endpoint is the DeepSeek URL (unchanged default — back-compat)
- `LOCI_LLM_ENDPOINT` overrides it
- a `.llm-endpoint` file in the working directory overrides the default but loses to the env
  var — matching the existing `env, then file, then default` order used by
  `api-key` (`:25`) and `model` (`:22`); read those and follow them exactly
- the key resolves from `LOCI_LLM_API_KEY` first, falling back to `DEEPSEEK_API_KEY`, then
  `.deepseek-key` — so a local server's token has a name that is not a lie, while every
  existing setup keeps working untouched

To make this testable, the endpoint/key/model resolution must be **functions**, not a `def`
evaluated at load. `endpoint` is currently a `def`, so it is frozen at namespace load and
`with-redefs` on `System/getenv` cannot reach it. Turn it into a defn like `api-key` and
`model` already are, and say why in a comment.

- [ ] **Step 2: Run them, watch them fail.** `clojure -M:test 2>&1 | tail -20`

- [ ] **Step 3: Implement.** Follow `api-key`/`model`'s existing shape and `from-file` helper.

- [ ] **Step 4: Run the tests.** Green.

- [ ] **Step 5: Prove it end-to-end against a STUB, not a real model**

Write a throwaway OpenAI-compatible stub (any language; a few lines of Node or Python is
fine) that listens on a port and answers `POST /v1/chat/completions` with a fixed
`{"choices":[{"message":{"content":"…"}}]}`. Start loci with `LOCI_LLM_ENDPOINT` pointing at
it and a dummy key, call `/api/ask`, and assert the stub was hit and the answer came back.
**Report the stub's request log** — the actual JSON loci sent — so it is visible that the
model name and messages are what you expect. Use a throwaway `LOCI_DATA`.

- [ ] **Step 6: Prove it bites.** Ignore `LOCI_LLM_ENDPOINT` in the resolver; the
  stub test must fail. Restore. Report the output.

- [ ] **Step 7: Docs.** Add to `loci.env.example` and the README's configuration table:
  `LOCI_LLM_ENDPOINT` (falls back to `.llm-endpoint`, then the DeepSeek URL) and
  `LOCI_LLM_API_KEY` (falls back to `DEEPSEEK_API_KEY`, then `.deepseek-key`). Say plainly
  that any OpenAI-compatible `/v1/chat/completions` works, and that `DEEPSEEK_MODEL` names
  the model on **whatever** server is configured — the variable name is historical.

- [ ] **Step 8: Commit**

```bash
git add src/loci/agent.clj test/ loci.env.example README.md
git commit -m "feat: the LLM endpoint is configurable — loci can run on your own hardware"
```

**Out of scope:** making the Tavily URL configurable (a SearXNG backend needs a different
request and response shape, which is its own piece of work — see the web-search note),
embeddings, reranking.

---

### Task B: One JVM per browser run

**Files:** `test/browser/harness.mjs`, `package.json`, possibly each `test/browser/*.test.mjs`

Today `npm run test:browser` runs 5 test files, each calling `startServer()`, each paying a
cold JVM that loads `loci.content` — **measured at 9.9 s**. An earlier fix folded three
fixture JVMs into the server's own process (167 s → 64 s); this is the remaining 5 → 1.

- [ ] **Step 1: Measure the baseline.** `npm run test:browser`, record wall time and the
  reported `duration_ms`. Count the JVMs actually spawned (e.g. by logging in `startServer`).
  Report real numbers.

- [ ] **Step 2: Check what the runner supports.** Node 26 is installed. Find out whether
  `--test-isolation=none` (all files in one process) and/or a global-setup hook exist and are
  not experimental-with-warnings. **Report what you found rather than assuming**; the plan's
  suggestion is a starting point, not a specification.

- [ ] **Step 3: Share one server.** With files in one process, memoize `startServer()` so all
  five share a server and a browser, and reap it once at the end.

- [ ] **Step 4: The real risk — cross-file interference.** The suite currently gets a fresh
  substrate per file. Shared, it does not. Known mutators: the tags tests write tag colours,
  the overview test creates notebooks, the suggest tests intercept routes (so they write
  nothing). **Before declaring success, prove order-independence**: run the suite at least
  twice with the file order reversed (or shuffled) and confirm the same 37 pass.

  **If interference is real, do not paper over it with sleeps or by weakening assertions.**
  Either give the mutating files their own server (4 JVMs is still better than 5, and honest),
  or make the affected tests set up their own state. Report exactly what you found and which
  route you took.

- [ ] **Step 5: Report the numbers.** New wall time, new `duration_ms`, JVMs spawned. If the
  win is smaller than expected, say so with the measurement instead of rounding up.

- [ ] **Step 6: Full suites once.** `npm run test:browser` (expect 37 pass) and
  `clojure -M:test` (expect 175/703/0).

- [ ] **Step 7: Commit**

```bash
git add test/browser/ package.json
git commit -m "test: one JVM for the whole browser run"
```

**Out of scope:** rewriting any test's assertions, changing what is covered, parallelising
within a file.
