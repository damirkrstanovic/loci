# The memory pane shows what the memory knows about itself

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Spec:** `docs/superpowers/specs/2026-08-08-memory-pane-design.md`

**Two tasks:** the two missing server keys; then the pane.

---

## CRITICAL, both tasks

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. **Check and report both.** `loci.memory/memory` is a
  delay onto the user's real file — never deref it in a test.
- **Never read, print or edit `loci.env`** — it holds real keys.
- Do not call a real model or embedder; stub, and the suite must pass offline.
- No `pkill -f`. **Never overstate what the code does.**
- Task 1: `clojure -M:test` once, no browser suite. Task 2: both, once each.

---

### Task 1: `:degraded` and `:merge` reach the payload

**Files:** modify `src/loci/server.clj` (`memory-payload`), possibly `src/loci/memory.clj`; tests

`memory-payload` already carries `:facts` (each with `:via`, `:merged-from`, `:strength`,
`:source`, `:score`), `:awaiting` and `:embedding`. Two things do not get out:

- **`:degraded`** — `recall` reports degradation in the returned vector's **metadata**
  (`loci.memory` ~`:719`), and metadata cannot survive JSON. Read how it is attached before
  designing the fix.
- **`:merge`** — `{:threshold 0.88}` when merging is possible, `{:refused "…"}` when the
  configured model has no calibrated threshold. `merge-similar!` already produces that message;
  it never leaves the server. **Do not run a merge to find out** — the answer is a lookup in
  the threshold table against `(embed/embed-model)`, and computing it must not have side
  effects.

- [ ] **Step 1: Write the failing tests.**
  - unreachable embedder → `:degraded` is a plain string key on the payload
  - healthy + configured → **no** `:degraded` key at all (absent, not `nil`/`false` — an
    always-present key that is usually empty invites a truthiness bug in the shell)
  - calibrated model → `:merge` reports the threshold; uncalibrated → `:merge` reports a
    refusal naming the model
  - **no embedder configured** → say what `:merge` is then, and pin it. Merging cannot happen
    without vectors, so a threshold alone would be misleading
  - the healthy configured response is otherwise **key-for-key what it is today** — a
    regression guard, since four callers depend on this shape

- [ ] **Step 2: fail. Step 3: implement. Step 4: green.**

- [ ] **Step 5: Prove it bites.** One batch, distinct tests: drop `:degraded`; make an
  uncalibrated model report a threshold; make `:degraded` always present as `nil`.

- [ ] **Step 6: Commit** — `feat: the memory payload says whether it is degraded and whether it merges`

---

### Task 2: The pane

**Files:** modify `resources/public/index.html` (`openMemory`); create `test/browser/memorypane.test.mjs`

**Read `openMemory` in full first.** It renders a modal with a search field and a `.memfact`
per fact showing strength dots, a source chip, an age and a relevance score. Match that
register — mono, muted, small. This adds information; it does not add a mode, a tab or a
filter.

- [ ] **Step 1: The status line.** One line under the search field, always present, exactly one
  of the five conditions in spec §1, with the merge clause appended when it applies.

  **A number and a model name always appear together.** `12 awaiting` with no model reads like
  a backlog when it actually means the feature is off and every fact awaits one forever.

- [ ] **Step 2: Provenance.** With a query, each fact shows `words`, `meaning` or
  `words + meaning` from `via`. **Without a query, no marker** — a browse ranks nothing, so
  nothing was found by anything, and a marker there would be decoration pretending to be
  information.

- [ ] **Step 3: Merges.** A fact with `merged-from` shows `+N merged` beside its dots; clicking
  reveals the absorbed texts and ids. This is the only way a wrong merge becomes visible, which
  is what makes the calibrated threshold auditable rather than merely careful.

- [ ] **Step 4: Browser tests** in a new file, following `test/browser/tags.test.mjs` for
  structure and `harness.mjs` for the server. Intercept `/api/memory` with `page.route` so the
  payloads are yours and no embedder is needed. Assert on **text content, not class names** —
  a class-counting assertion on this file passed straight through a shipped no-op on
  2026-08-06.

  Cover: each of the five status conditions; `via: ["semantic"]` → `meaning`; both → `words +
  meaning`; a browse → no marker anywhere; `merged-from` → the count, and the absorbed text
  appears on click; a fact without it shows nothing extra.

- [ ] **Step 5: Prove they bite.** One batch, each failing a distinct test: render the status
  line only when unhealthy; show the marker on a browse too; drop the merged count. Report real
  output. If a break fails no test, add the missing test and say so.

- [ ] **Step 6:** `npm run test:browser` and `clojure -M:test`, once each. Both `data/` md5s.

- [ ] **Step 7: Commit** — `feat: the memory pane says how it found things, and what it merged`

## Out of scope

- Undoing a merge from the pane; editing or deleting a fact
- Any change to recall, fusion, merge or scoping
- Chunk/LEAP state — that is not this pane
