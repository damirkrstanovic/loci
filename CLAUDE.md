# loci — working agreements

## Always execute plans with subagents

When a plan exists, execute it with **`superpowers:subagent-driven-development`** — a fresh
subagent per task, with review between tasks. Never inline execution, and never "just do it"
without a plan for anything beyond a one-line fix.

This is not ceremony. Over 2026-08-05/06 the loop caught defects that reading the code did
not, and most of them originated in the *plan*, not the implementation:

- concurrent `commit!`s destroying 208 of 900 events — found by running two store
  implementations under load and comparing, not by reading the diff
- a migration verifying against a stale in-RAM snapshot, reporting "verified" while the
  target silently lacked an event
- `object-at` reading an object's entire touch list, defeating the index it was built on
  (3.21 ms → 0.03 ms once bounded)
- three of four regression tests that passed *while their bug was present*, revealed only
  by breaking each fix in turn

So: ask reviewers to **measure and report actual numbers**, and ask implementers to **prove
their tests fail when the bug is reintroduced**. A test that passes either way is worse than
no test, because it certifies.

## Answer the question that was asked — ask before measuring

When the ask is "compare X and Y", "look at Z", "what do you think about W", the deliverable
is **the reasoning**: read the code, read the docs, say what you found and what it means.
Do the minimum the question requires.

**Do not start a benchmark, a spike, or a measurement campaign to support an answer.** On
2026-08-08 a "Datahike vs Datalevin" question became an unrequested 38-minute background
benchmark over 3,000 synthetic events. It yielded exactly one correction that mattered; the
rest was side-quest, and the actual answer arrived late and in pieces.

So: if something would run for minutes — a benchmark, a long test campaign, a multi-agent
fan-out, a spike against a second implementation — **say what it would measure, say roughly
what it would cost, and wait to be told yes.**

This does not weaken the rule below. "Verify by measuring" governs **changes to the code**:
when you have written something, prove it works and prove the test bites. It is not a licence
to go measure things nobody asked about.

## Never write to `data/`

`data/` is the user's real substrate — live notebooks, research, agent flows. It has already
survived one silent-truncation bug that destroyed 31 events.

- Scratch servers get a throwaway `LOCI_DATA`, never the repo's `data/`
- **A live REPL counts as a scratch server.** `clojure -M:nrepl` (what ClojureMCP starts) is
  one `(loci.dlv/datalevin-store)` or `@loci.memory/memory` away from opening the real store,
  and an agent evaluating forms has no sandbox around it. Export a throwaway `LOCI_DATA`
  before evaluating anything that opens a store, and never deref `loci.memory/memory`
- Verify against a **copy** when a running server holds the store open
- Check `md5sum data/substrate.edn` before and after any risky work
- `data/substrate.edn` is the frozen rollback of the pre-Datalevin log; leave it alone

## Two test suites

- `clojure -M:test` — substrate, notebook, memory, tools, server
- `npm run test:browser` — the shell, headless, against a seeded fixture

`clojure -M:test` does **not** mean "all the tests". Run both.
