# A branch sees its trunk, not its siblings

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax.

**Goal:** a notebook spawned from research can see back to what it grew out of, without
seeing the other branches that grew out of the same place.

**Decision:** the user's, 2026-08-08. Their words: *"I go on a research and then create a
branch — when I ask that branch it should have data to the root but not siblings, since
that's a different branch. We don't have explicit branching but very often it is not
necessary to be explicit to have this workflow."*

`spawned-by` already records that branching; nothing new needs declaring.

---

## The rule, exactly

`lineage-sources` (`src/loci/server.clj:806`) currently walks **downward only**: the notebook,
its cells, and the same for everything it spawned transitively and everything merged into it.

It gains one thing: **every ancestor's own ids** — each notebook on the path from the root
down to this one, contributing its own id and its own cell ids, **without re-descending.**

| relation | in scope? | why |
|---|---|---|
| self | yes | unchanged |
| own cells | yes | unchanged |
| descendants, transitively (+ their cells) | yes | unchanged — the work below you is yours |
| **ancestors, own ids only** | **new: yes** | the trunk you grew out of |
| **siblings / cousins** | **no** | a different branch |

**The whole difficulty is the last row.** Reaching an ancestor and then descending from it
lands on every sibling — that is precisely the leak the downward-only walk existed to
prevent. Going up must not turn into coming back down.

Concretely on the live corpus: `Taiwan's Foundry Concentration` must see
`Semiconductors — research hub` (its parent) and `Geopolitical Shock Scenarios` (its own
child), and must **not** see `EUV Lithography Monopoly` (its sibling).

## CRITICAL

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. Check and report both. Never deref `loci.memory/memory`
  in a test.
- **Do not call a real model or embedder.** A live key is in the repo and the environment.
  Stub everything; the suite must pass offline.
- Never print or commit a token. No `pkill -f`. **Never overstate what the code does.**
- `clojure -M:test` once. **Do not run the browser suite** — no shell-facing shape changes.

---

### Task 1: Walk up for ids, never down again

**Files:** modify `src/loci/server.clj` (`lineage-sources`, `:806`); tests in `test/loci/server_test.clj`

- [x] **Step 1: Read first.** `lineage-sources` and its existing tests, plus
  `the-scope-runs-DOWN-the-lineage-and-not-up` (added yesterday) — **that test now encodes the
  old rule and must be rewritten, not deleted.** Its comment points at a contradiction in the
  previous plan; replace it with a test of the new rule and a comment that says what changed
  and why, so the next reader does not "fix" it back.

  Also check how an ancestor is found. `:spawned-by` is `{:space parent}` on the child's
  value; `merged-from` is a vector of ids. Decide deliberately whether a merge parent counts
  as an ancestor for this purpose and **say why in the docstring** — a merged-from edge is not
  obviously the same relation as a spawn, and guessing silently is the failure mode here.

- [x] **Step 2: Write the failing tests.** Build a fixture with a real shape: root → two
  children → one grandchild under the first child. Pin:

  - a **child** sees its parent's facts and its own; **not its sibling's**
  - a **grandchild** sees the whole chain up to the root — parent *and* grandparent — and not
    its parent's other children
  - a **root** still sees all its descendants, exactly as today (regression guard)
  - **an ancestor's other descendants never appear** — the leak. Assert it from the
    grandchild, which is the position that would expose a two-step mistake
  - a **cycle** in `spawned-by` (however it got there) terminates rather than hanging — bound
    the upward walk with a `seen` set the way the downward walk already is
  - a notebook with no parent behaves exactly as before

  Stub recall with a `reify` recorder — `with-redefs` **cannot** intercept a protocol call
  (`mold/recall` compiles to a cached callsite straight to the implementing class, so the
  redef is silently ignored and the test passes while recording nothing). The existing tests
  hand memory in by rebinding the `mem/memory` **delay**; follow that.

- [x] **Step 3: Run, watch fail.** Report the real output.

- [x] **Step 4: Implement.** Keep the two walks separate and obviously so — one collects
  descendants (as now), one collects ancestors' own ids. Do not merge them into a single
  clever traversal; the reason this rule is easy to get wrong is exactly that up and down are
  different, and the code should show it.

- [x] **Step 5: Green.**

- [x] **Step 6: Prove it bites.** In one batch, each failing a distinct test: let the upward
  walk descend from each ancestor (the sibling leak); stop the upward walk after one step
  (grandchild loses the root); drop the `seen` bound on the upward walk (the cycle test must
  hang or fail — if it hangs, say so, and make sure the test has a timeout rather than
  wedging the suite). Report real output.

- [x] **Step 7: Note the second caller.** `GET /api/memory?q=&space=` uses the same function,
  so scoped *browsing* widens in exactly the same way. That is right — what you can browse
  should match what the agent can recall — but say so in your report and in the README
  sentence, rather than letting it change silently.

- [x] **Step 8: README.** Update the paragraph added yesterday: a branch sees its trunk, not
  the branches beside it. Keep it to a few sentences in the existing voice.

- [x] **Step 9: Commit** — `feat: a branch remembers its trunk, and not the branches beside it`

## Out of scope

- Scoping by tag rather than lineage
- Any change to `recall`, the filter itself, or `loci.memory`
- Making the relation explicit in the UI — `spawned-by` already records it
