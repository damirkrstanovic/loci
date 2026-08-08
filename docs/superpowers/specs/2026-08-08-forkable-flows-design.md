# Forkable flows: run it on a branch, then decide

Date: 2026-08-08
Status: **noted, not designed.** Recorded while reading ActiveGraph (2026-08-08). No
brainstorming session has been held; the approaches below are sketches, not decisions, and
nothing here is approved for planning.

## What loci has, and what it doesn't

The substrate can move backwards along one line: `undo!` pops the last event, `as-of n`
folds the log to `n`, `?at=` hands every reader a `FrozenStore`. What it cannot do is move
**sideways** — there is no way to say "run this flow, but on a branch, and let me look before
it becomes true".

Today an agent flow (`research → gate → compute → draft`) writes into truth as it goes and
stops at gates to ask. Consent is real, but it is consent *about work already recorded*: the
notebook has changed by the time you are asked, and declining means undoing. The gate asks
permission to continue; it cannot offer you a look at the alternative.

## Why this is the interesting one

loci's thesis is that the agent proposes and you decide. Forking makes that structural rather
than procedural: the flow runs on a branch, at the gate you **diff** it against trunk, and
then merge it or drop it. Dropping costs nothing and leaves nothing — a stronger promise than
reversibility, which is the promise `suggest-tags!` already makes for a single proposal
("writes NOTHING — a proposal you ignore leaves no trace at all"). This would extend that
promise from one proposal to a whole flow.

## Prior art

**ActiveGraph** (<https://activegraph.ai/>, arXiv 2605.21997) forks a run at any historical
event, runs forward with different configuration, and **structurally diffs** the fork against
its parent. Forks are cheap because model calls are cached: the shared prefix is replayed, not
re-paid. Its architecture is otherwise convergent with loci's (append-only log as truth,
deterministic projection), which is what makes the capability transferable rather than
aspirational.

**Datahike** offers branching at the *store* level through structural sharing
(`2026-08-08-datahike-vs-datalevin.md` §3). Worth noting that it is not needed here:
ActiveGraph's fork is at the **run** level, on its own log — so loci can have this **without
changing stores**, which is the same conclusion the store analysis reached from the other
direction.

## The concrete obstacle

`dlv/read-log` requires the durable event keys to be exactly `1..n` and **refuses a gap**,
loudly, rather than routing around it:

> "An event's key IS its position: that is what lets `commit!` derive the next index from the
> count and `undo!` delete by it. So the keys must be exactly 1..n, and a gap is not a thing
> to route around — the next `commit!` would reuse a key that is still live and overwrite a
> durable event while telling the caller it succeeded."

That invariant is load-bearing and correct. Every branching scheme has to say what replaces
it. Three further things multiply with branches, and any design must answer all four:

1. **Key space** — `(branch, idx)` or one log per branch.
2. **`undo!`** — becomes per-branch; popping trunk's last event while a branch descends from
   it must be impossible or must be defined.
3. **`as-of`** — becomes `(branch, n)`; `?at=` and `FrozenStore` both grow a dimension, and
   `view`'s "one consistent `{:log :state}` pair" must be per-branch.
4. **The RAM pair** — `dlv` holds exactly one `{:log :state}` atom, which is what makes
   readers lock-free. Branches mean several, and the cheapness of that design is the thing at
   risk.

## Sketch of the approaches

**A. Shadow flow, no branching at all (smallest, probably first).** The flow runs against a
*proposed* state derived from trunk, accumulating the events it would commit. At the gate you
see a diff; approval commits them as one transaction, rejection drops them and writes
nothing. The log stays linear, the 1..n invariant is untouched, `undo!` and `as-of` do not
change. This is `suggest`'s consent shape scaled from one proposal to a flow, and it buys most
of the user-visible value.
*Limit:* a shadow cannot be resumed after a restart, and two shadows cannot be compared.

**B. Copy-on-fork: one store directory per branch.** Fork copies the log up to `n` into a new
store and runs there; merge replays the branch's events onto trunk. Today's substrate is
652 KB, so copying is free at this scale and the honest limit is stated rather than hidden.
No invariant changes — each branch is an ordinary `1..n` log.
*Limit:* cost is linear in log size, so it has a shelf life; merge is a replay, and replay onto
a moved trunk needs a rule.

**C. Branch pointers in one log.** Keys become `(branch, idx)`; trunk is a branch. The most
capable and the one that touches everything named in "The concrete obstacle".

## The model-call cache is worth building either way

ActiveGraph's forks are cheap because a replayed prefix costs no API calls. loci has no such
cache: every research step hits the model and Tavily afresh. A cache keyed by
`(model, messages, tools)` would make forks cheap, make a re-run of a flow deterministic, and
make the test suite cheaper — three payoffs, none of which requires branching to exist first.
It is separable and could be done alone.

## What to measure before building

1. **Is the payoff real?** How often is a gate actually declined, and how often does a
   declined flow leave work that had to be undone? If flows are approved nearly always, this
   is machinery for a rare case.
2. **Copy cost** (approach B) at today's 652 KB and at 10×, measured, not estimated.
3. **Cache hit rate** on a real re-run of an existing flow — the number that decides whether
   the cache is worth its own complexity.

## Non-goals

No merge-conflict resolution between two divergent branches that both wrote the same object —
if that case arises, refuse and say so. No multi-user branching. No distributed anything.
