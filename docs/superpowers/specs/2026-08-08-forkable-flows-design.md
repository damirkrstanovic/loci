# Forkable flows: run it on a branch, then decide

Date: 2026-08-08, updated 2026-08-16
Status: **noted, not designed, and NOT ready to plan.** Recorded while reading ActiveGraph
(2026-08-08). Its own "what to measure" gate was run on 2026-08-16 and came back negative:
no gate had ever been reached, because agent flows had never completed a run at all. Two
bugs behind that are now fixed and a flow runs end to end, but the question this spec exists
to answer — *are gates ever declined?* — still has no data. **Read "The gate was run" before
acting on any sketch here;** the sketches predate it, and approach B's cost basis has moved
by 22×.

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

## The gate was run, 2026-08-16 — read this first

This spec ends with three things to measure before building. The first was measured, and it
stopped the work.

**Question 1 — "how often is a gate actually declined?" — assumed flows run.** They did not.
Across the whole history of the substrate there was **exactly one flow object**, `flow:1`,
and it had failed at step 1 of 5:

```
goal    "add positions, speeds and other data of known solar objects…"
step 1  research  FAILED  — "research came back empty"
step 2  gate      pending   ← never reached
step 3  compute   pending
step 4  draft     pending
```

**No gate had ever been reached, let alone declined.** The mechanism this spec proposes to
improve had never completed a run. Two bugs were behind it, both since fixed:

- `agent/request` hardcoded a 60 s idle timeout. A bare `research!` on a narrow prompt took
  31 s; the same verb inside a flow, with the notebook context, ran past 60 s and died.
  Now `LOCI_LLM_TIMEOUT_MS`, default 180 s.
- `resolve-table-ref` only rewrote `$`-prefixed values, but the planner emits a bare step
  INDEX (`:id "0"` in the August run, `:id 0` in the reproduction) while putting the real
  reference in the prose. `compute` looked up an object named `0` and failed in under a
  millisecond with a good table one step earlier.

With both fixed, a flow ran end to end — research → gate → approve → compute → draft. So
**question 1 is now answerable and still unanswered**: the instrument works, and nobody has
used it enough to say whether gates get declined. Until flows are actually used, this remains
machinery for a case that has not been observed once.

**Approach B's arithmetic has also moved.** The sketch says "today's substrate is 652 KB, so
copying is free at this scale". After the 2026-08-15 one-substrate work, `data/substrate` is
**14.1 MB** — facts and 2560-dimension chunk vectors now live in the same environment — and it
grows with every embedded chunk. A copy per fork is no longer free, and the thing that made it
expensive was moving the memory *in*. Approach B needs re-costing, or needs to copy only the
`events` dbi and rebuild the rest.

Approaches A and C are untouched by that. A in particular gets *more* attractive: it never
copies anything.

## What to measure before building

1. **Is the payoff real?** How often is a gate actually declined, and how often does a
   declined flow leave work that had to be undone? If flows are approved nearly always, this
   is machinery for a rare case. **Run 2026-08-16: unanswerable, because flows had never
   completed. Now they do; the question needs real use before it has an answer.**
2. **Copy cost** (approach B) at today's 652 KB and at 10×, measured, not estimated.
   **Superseded: the substrate is 14.1 MB as of 2026-08-15, not 652 KB. Measure at that, and
   consider copying only `events` and rebuilding `counts`/`touched`/`chunks`, which are
   derived.**
3. **Cache hit rate** on a real re-run of an existing flow — the number that decides whether
   the cache is worth its own complexity.

## Non-goals

No merge-conflict resolution between two divergent branches that both wrote the same object —
if that case arises, refuse and say so. No multi-user branching. No distributed anything.
