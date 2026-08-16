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

## Don't build for a requirement nobody stated

Before treating something as a constraint and engineering around it: **check the docs and
specs. If they don't say, and it isn't obvious, ask.** An assumption that generates work is
a question, not a premise.

Two from 2026-08-15, both mine, both expensive:

- **Fact ids are `mem-<n>`, so I defended the sequence.** Nothing asked for it. Exactly one
  place uses the ordinal semantically — `older`, breaking a `:ts` tie — and its docstring
  already handles non-sequential ids. Defending it produced `mint-id!` on a protocol, a
  high-water atom, a durable counter, a seeding path, a lock, and a transaction. Measured:
  `(random-uuid)` passes the same tests and deletes all of it. (The counter stays; the
  point is that nobody was asked whether it should.)
- **I wrote "cross-process safety" into my own spec, then built a two-JVM harness to test
  it.** loci is single-writer and always was. A closed loop with no outside input.

The tells, in order of how loudly they should stop you:

1. You are **measuring how often your own mechanism corrupts data**. It should not exist.
2. You are **testing a requirement you wrote yourself**. Ask whose it is.
3. You are **building infrastructure to preserve a property** — an id shape, an ordering, a
   format — that nothing in the docs asks for.
4. You are **treating a cost nobody has hit as a constraint on the design**. On 2026-08-16 a
   dependency that would double the uberjar got raised three times in one conversation — in
   the research, in the spec's costs, and as an open question — for a jar nobody has
   complained about, on a project with no distribution problem. Measure it, write the number
   down, and move on. A cost becomes a constraint when someone feels it, not when you notice
   it could exist.

Ask. One question costs a message; a side-quest costs an afternoon and lands work nobody
wanted. This is the same rule as "answer the question that was asked" above, one step
earlier: before the work starts, not after it has run long.

## One writer. Three ways to reach storage, and a fourth that is a mistake

loci is **single-writer**. One process owns the store. Reach it by:

1. **The running app's HTTP API** — `/api/*`. The normal path.
2. **A `loci.*` facade, called in the server's own process.**
   `loci.corpus/import-corpus!`, `loci.conversation/import-conversation!`,
   `loci.factmigrate/migrate-facts!` are public, dry-run by default, and meant to be
   called from a REPL attached to the live server.
3. **Directly, with the app stopped.** Surgery takes the store offline first. Stopping the
   app *is* the mechanism; there is no lock to acquire and none is wanted.

The fourth way is a scratch script opening the store in its own JVM while a server holds
it. That is not a scenario to make safe — it is a mistake, and the fix is (2) or (3). On
2026-08-15 doing it anyway produced a two-JVM test harness, a spec promising a
cross-process guarantee nobody asked for, and a contaminated measurement of a requirement
that did not exist.

## Use what the tool already provides, before inventing a mechanism

Before writing coordination, locking, transactions, counters, retries or caching by hand,
**look for the native facility first**. The dependency almost certainly has one, it is
almost certainly correct, and a hand-rolled version is a second thing to be wrong.

The 2026-08-15 case, kept because it is embarrassing and typical. Fact ids needed to be
unique across writers. What got built:

- a `(locking lock)` around a read-then-write, where `lock` is a plain `(Object.)`
- a counter in the `meta` dbi, incremented by reading it and writing it back
- a high-water-mark atom in `FileLog` to patch a gap the above left
- a two-JVM harness to measure how often it lost data

Datalevin ships `with-transaction-kv`: *"Evaluate body within the context of a single new
read/write transaction, ensuring atomicity of key-value operations."* One transaction,
correct by construction. The whole apparatus above was an unreliable reimplementation of a
feature already on the classpath, and replacing it **deleted** a record field, a monitor
and a class of bug.

Two tells, both present here. **Measuring how often your own mechanism corrupts data** is
not a research finding; it means the mechanism should not exist. And **testing a
requirement you invented** — the harness existed to check a cross-process guarantee this
spec had promised and nothing had asked for — is a loop with no outside input in it.

So: grep the dependency, read its API, ask the REPL (`(clojure.repl/doc …)`,
`(clojure.repl/apropos "transaction")`). loci is small, single-server software — it is not
a place to invent concurrency primitives.

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
