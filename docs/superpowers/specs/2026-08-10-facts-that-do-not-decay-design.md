# Facts that do not decay: design

Date: 2026-08-10
Status: **designed, approved in brainstorming 2026-08-10.** No implementation plan yet.

## The complaint

> *"decay is a problem — there are some real world facts that bear no decay, only some findings
> that agent discovered during execution should undergo decay"*

`loci.memory/decay` (`:53`) is *"1.0 now, halved after ~30 days — recency as a multiplier, not a
cutoff"*, and `weight` applies it to **every** fact on **every** path:

| age | multiplier |
|---|---|
| 30 days | 0.500 |
| 90 days | 0.250 |
| 365 days | **0.076** |
| 730 days | **0.039** |

So `mem-34` — *"Kepler's Third Law states that T² ∝ a³"* — will rank at **7.6%** of its relevance
a year from now and 3.9% the year after. A law of physics, deleted by arithmetic.

## The axis is the sentence, not its provenance

The tempting rule is "agent findings decay, imports do not". **It is measurably wrong on the
existing corpus.** `mem-33`…`mem-37` were distilled by an agent — from an empty document, per the
2026-08-09 forensics — and are timeless: Kepler's Third Law, Mercury's 88-day period, Neptune's
165 years. They sit beside agent-distilled facts that are inherently as-of: *"Lithography capacity
was booked through 2027"*, *"TSMC controls approximately 70% of global foundry revenue"*.

What separates them is whether the claim carries an implicit **"as of now"**. That is a judgement
about the sentence. Provenance is a useful default and a wrong rule.

## The seam

`decay` is consulted in exactly one function, from two call sites (`score` at `:126` and the fused
path at `:1164`). So the whole ranking change is:

```clojure
(defn- weight [now f]
  (* (if (:durable? f) 1.0 (decay (:ts f) now))
     (+ 1.0 (* 0.25 (dec (:strength f 1))))))
```

**Absent means decays** — today's behaviour exactly — so no fact changes rank until something
classifies it. The strength bonus is untouched and still applies to durable facts: a fact that
keeps coming up should still outrank one that came up once, and that is orthogonal to whether it
ages.

## Who wrote the flag is part of the flag

Facts carry `:durable?` **and** `:durable-by`, the latter `"you"` or `"agent"`. This mirrors tags,
which already carry `:by "you"` / `:by "agent"` for the same reason.

It is not decoration. `remember` **reinforces** a re-derived fact — rewriting `:fact`, bumping
`:strength`, unioning `:entities` — so without provenance on the flag, the agent re-deriving a
fact next week would silently revert a judgement you made by hand. That is the failure
`:no-merge` exists to prevent on the merge path, and it should not be reintroduced here.

**The rule: reinforcement may overwrite an agent's judgement, never yours.**

```clojure
;; in remember's reinforcement branch
(if (= "you" (:durable-by dup))
  dup                                     ; your call stands
  (cond-> dup
    (contains? opts :durable?) (assoc :durable?   (:durable? opts)
                                     :durable-by "agent")))
```

**`remember` has two branches and both need this.** The reinforcement branch is above; the
new-fact branch must carry the flag through from `opts` as well, and must **omit both keys
entirely when `opts` does not supply them** rather than writing `:durable? nil`. A key that is
always present and usually nil is what makes `(:durable? f)` and `(contains? f :durable?)`
disagree — the same shape `memory-payload`'s `:degraded` was deliberately designed to avoid, for
the same reason. Absent must keep meaning *"nothing has judged this yet"*.

## Three writers, one field

1. **`distill-facts`** (`src/loci/agent.clj`) gains one field per fact — `"durable": true|false` —
   beside `"fact"` and `"entities"`. No extra call. The prompt must teach the axis **by example**,
   because the words "durable" and "as of now" are not self-explanatory to a model any more than
   they were to us: `T² ∝ a³` durable; *"capacity is booked through 2027"* not. `distill!`
   (`server.clj`) passes it through to `remember`'s opts.
2. **A one-time classification pass** over the existing 80 facts, using the same judgement.
3. **You**, via a toggle in the memory pane — `POST /api/memory-durable {id, durable}`, mirroring
   the existing `/api/memory-unmerge`. `memory-payload` already ships every fact key but `:vec`,
   so the flag reaches the shell with no payload change.

## The classification pass

A new `loci.classify`, built like `loci.repair`: **dry-run by default, idempotent, append-only,
nothing runs on require.** It reads the live facts, asks the classifier in batches, and appends
each fact again with `:durable?` and `:durable-by "agent"` — the same last-wins append that
`store-vector!` and `unmerge!` already use.

**It doubles as the test of whether the classifier is worth trusting**, and that is most of why it
runs first. Known-good answers exist in the corpus:

| fact | expected |
|---|---|
| `mem-34` Kepler's Third Law, T² ∝ a³ | durable |
| `mem-35` Mercury's period is 88 Earth days | durable |
| `mem-36` Neptune ~165 Earth years | durable |
| *"Lithography capacity was booked through 2027"* | **not** durable |
| *"TSMC controls approximately 70% of global foundry revenue"* | **not** durable |

If it gets those wrong, we know before it touches a single new write.

**It must not go through `mold/remember`** — the same trap the 2026-08-09 repair documented:
`remember` would find the fact as its own jaccard ≥ 0.6 duplicate, bump `:strength`, and reset
`:ts` to now, which for a fact that is *not* classified durable would hand it a fresh 1.0 decay
multiplier. Append directly.

`migrate`, `repair` and now `classify` make three one-off namespaces. Worth noticing rather than
waving through — but they are genuinely separate one-time jobs, each small, each reviewable, and
each with a dry run. The alternative is a REPL session nobody can review.

## The pane has to stop lying

The memory pane shows age — *"today"*, *"3d ago"* — beside each fact, and that number currently
carries a meaning it is about to lose. For a durable fact the age is still true and no longer
affects ranking, so a reader seeing *"365d ago"* would reasonably assume the fact is buried when
it is not. The toggle's rendering must make the distinction visible, not just clickable.

## Testing

House rule: reviewers measure, implementers prove their tests bite.

- **`weight`**: a durable fact returns the same weight at 0, 365 and 3,650 days; a non-durable one
  reproduces today's curve exactly. Pin the existing curve with literal numbers, so a refactor
  that changes decay for ordinary facts fails here.
- **Reinforcement**: an agent judgement is overwritten; **a `"you"` judgement is not**. Both
  directions, and prove each bites — this is the whole reason `:durable-by` exists.
- **The classifier's prompt** pins the requirement, not the model's compliance, and says so in its
  docstring. Same weak-test disclosure as Task 11.
- **The pass**: dry run appends nothing; a second run is a no-op; a fact whose text moved between
  plan and write is declined rather than mis-stamped.
- **Browser**: the toggle flips, persists across a pane reopen, and the durable/decaying
  distinction is visible in text rather than only in a class.
- **Measured, on a COPY of the live corpus, never the file** — a before/after top-6 table over the
  same prompts Task 12 used. **This is not optional.** A global ranking change is precisely what
  degraded 7 of 12 prompts on 2026-08-08 and was caught only because someone measured it against
  real data instead of reasoning about it.

## Safety

`data/memory.edn` is `3b219034921e2f1bfe438837a2244e95` with a backup at
`data/memory.edn.bak-2026-08-09`. The pass writes to it, so: back up first, `md5sum` before and
after, no server running (`FileMemory` holds `!facts` in RAM and a concurrent `remember` would
append from a stale atom), append-only, and **the memory half has no undo** — the backup is the
reversal, the same honest limit the repair carries.

## Relationship to the corpus

`2026-08-10-a-corpus-worth-browsing-design.md` depends on this and **must not ship first.** That
spec left open how imported facts should be stamped, on the grounds that ~70 facts stamped today
would outrank the user's real work by ~47% in every recall. This design resolves it: imported
facts are classified like any other, and the existing 80 are classified in the same pass, so the
two halves of the memory are judged by one rule rather than by their arrival date.

It also closes the door the corpus spec would otherwise have opened: marking imports durable while
leaving the existing corpus decaying would have made the imbalance **permanent** rather than
temporary.

## Non-goals

- **Half-lives.** Considered and rejected in brainstorming: richer and probably truer, but every
  extra number is a place to be wrong, and nothing yet says a binary is insufficient.
- **Re-deciding decay's shape or its 30-day constant** for ordinary facts. Unchanged, and pinned.
- **Classifying by heuristic** — dates, percentages, currency in the text. Fragile, and it would
  be a second judgement disagreeing with the first.
- **Anything about the corpus.** Separate spec, separate plan, and it comes second.
