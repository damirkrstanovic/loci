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

## Never write to `data/`

`data/` is the user's real substrate — live notebooks, research, agent flows. It has already
survived one silent-truncation bug that destroyed 31 events.

- Scratch servers get a throwaway `LOCI_DATA`, never the repo's `data/`
- Verify against a **copy** when a running server holds the store open
- Check `md5sum data/substrate.edn` before and after any risky work
- `data/substrate.edn` is the frozen rollback of the pre-Datalevin log; leave it alone

## Two test suites

- `clojure -M:test` — substrate, notebook, memory, tools, server
- `npm run test:browser` — the shell, headless, against a seeded fixture

`clojure -M:test` does **not** mean "all the tests". Run both.
