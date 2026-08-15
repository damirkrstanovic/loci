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

## `data/` is test data — don't be precious about it

`data/` holds notebooks, research and agent flows, but it is **test data in this
repository**. Do not spend effort protecting it. No md5 checksums before and after, no
rehearsing on copies, no refusing to run something because it might touch the store.
If a run destroys it, that is an acceptable outcome and not an incident.

What still applies, because it costs nothing:

- Prefer a throwaway `LOCI_DATA` when a test or scratch server doesn't need the real data —
  not to protect `data/`, but because tests that share a store interfere with each other
- `data/` already carries several `.bak-*` files. Take another if you're about to do
  something wholesale, then get on with it

This replaces an earlier rule that treated `data/` as irreplaceable. That rule cost far more
than it saved: on 2026-08-15 it produced checksum verification in every single subagent
prompt, a full rehearsal of an import against a copied store, and a refusal to write facts
until a server was stopped — all to guard data the user regards as disposable.

## The REPL is the execution surface — see `.claude/skills/clojure-repl-dev`

**Do not start a JVM to run Clojure.** `clojure -M -e "…"` costs ~25 seconds before the
first form evaluates (0.78 s JVM, 14 s Datalevin, 12 s loci source, measured 2026-08-15).
The same work warm is milliseconds. Use `mcp__clojure-mcp__clojure_eval` against the
running nREPL, call the namespace's own public facade, and `:reload` after every edit.

`clojure -M:test` is for one final full-suite run before a commit — not for iteration,
and never for checking whether one sabotage bit.

## Two test suites

- `clojure -M:test` — substrate, notebook, memory, tools, server
- `npm run test:browser` — the shell, headless, against a seeded fixture

`clojure -M:test` does **not** mean "all the tests". Run both.
