# Hard-scope `remembered-context` by lineage

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** the agent recalls only what was learned in the notebook you are standing in and its
lineage — not across your whole memory.

**Decision:** the user's, taken 2026-08-08 after being shown the trade. Do not re-litigate it
in code or comments; record it accurately and move on.

---

## What changes, in user terms

`remembered-context` (`src/loci/server.clj:814`) injects the six most relevant remembered
facts into the agent's instructions. It runs on four user actions: **✦ Ask** (`ask!`),
**✎ Draft** (`delegate!`), **🔍 Research** (`research!`) and **✧ Suggest** (`suggest!`).

Today it searches all of memory. After this, it searches only facts whose `:source` falls in
the current notebook's lineage — the notebook, its cells, what it spawned transitively, and
what merged into it.

**The consequence the user accepted, which must be stated in a comment and nowhere
overstated:** notebooks that share a *subject* but not a *lineage* become blind to each
other. On the live corpus `space:semis` and its six deep-dive children are one lineage (56 of
80 facts), but `Serbia — open economic data` and `World economies — output and prices` are
separate roots, so neither will offer the other anything. Lineage is what the substrate
computes; subject is what tags record. This is deliberate.

## CRITICAL

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. Check and report both. `loci.memory/memory` is a delay
  onto the user's real file — never deref it in a test.
- **Do not call a real model or embedder.** A live DeepSeek key is present in the repo and
  the environment; stub everything. The suite must pass offline.
- Never print or commit a token. No `pkill -f`. **Never overstate what the code does.**
- `clojure -M:test` once. **Do not run the browser suite** — no response the shell reads
  changes shape.

---

### Task 1: Scope it

**Files:** modify `src/loci/server.clj`; tests in `test/loci/server_test.clj`

- [ ] **Step 1: Solve the ordering problem first, and verify it.**

`lineage-sources` is defined at `:1261`. All four callers are at `:854`–`:1026`, and
`remembered-context` itself at `:814`. Move `lineage-sources` above `remembered-context`, or
`declare` it — **and then run a clean `clojure -M:test` to prove there is no forward
reference.** A previous plan on this repo shipped exactly this kind of forward reference; do
not repeat it by assuming.

- [ ] **Step 2: Write the failing tests**

The behaviour to pin:

- a fact recorded in notebook A is **not** offered when the same question is asked in an
  unrelated notebook B
- a fact recorded in a deep-dive **child** *is* offered to its parent hub, and a fact recorded
  in the hub *is* offered to the child — lineage runs both ways through the same walk
- a **brand-new notebook** gets no remembered context at all, and that is a normal state, not
  an error — `remembered-context` returns `nil` and the prompt simply has no REMEMBERED block
- **no notebook at all** (`space` is nil): memory stays global. `ask!` already falls back to
  the whole workspace for its *document* context in exactly this case
  (`src/loci/server.clj:856-858`), so memory must match rather than invent a second rule —
  say so in a comment
- the injected block still carries the `⌾ id` citations it does today

Stub the recall — do not stand up an embedder. Note that `with-redefs` **cannot** intercept a
protocol call (`mold/recall`): the compiler caches the callsite straight to the implementing
class, so a redef is silently ignored and the test passes while recording nothing. Pass a
`reify` recorder, as `test/loci/server_test.clj` already does elsewhere — find that example
and follow it.

- [ ] **Step 3: Run, watch fail.** Report the real output.

- [ ] **Step 4: Implement.**

Give `remembered-context` the store and the space. Where a space is present, pass
`{:filter {:sources (lineage-sources st space)}}` through to `recall`; where it is not, pass
no filter. Update all four call sites.

`recall` already applies the filter **before both retrievers**, so a scoped recall cannot leak
a fact by meaning either — do not re-implement filtering here.

- [ ] **Step 5: Green.**

- [ ] **Step 6: Prove it bites.** In one batch, each failing a distinct test: drop the
  `:filter` so recall goes global again; make a nil space scope to the empty set instead of
  going global; make the lineage walk follow `spawned-by` (upward), which would reach every
  sibling through a shared ancestor and defeat the point. Report the real output for each.

- [ ] **Step 7: README.** One short paragraph in the memory/recall section: the agent now
  remembers within the notebook and its lineage, what that means for sibling notebooks about
  one subject, and that `GET /api/memory?q=&space=` browses the same scope deliberately.

- [ ] **Step 8: Commit** — `feat: the agent remembers within this notebook's lineage, not across everything`

## Out of scope

- Any change to `recall`, `loci.memory`, or the scope filter itself — all built and tested
- Boosting instead of filtering; the user chose the hard scope
- Scoping by tag rather than lineage
