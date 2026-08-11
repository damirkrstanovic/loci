# What not decaying does to what the agent is told

Date: 2026-08-11 (Task 6 of `docs/superpowers/plans/2026-08-11-facts-that-do-not-decay.md`)
Status: **measured. It clears the plan's bar with room to spare — 0 of 12 prompts degrade
against a stated stop threshold of one third — and Task 7 should run.** Nothing in `src/`
was changed to make this document. The classifier really ran, against a **copy** of the live
corpus; `data/` was never opened.

---

## The one-paragraph version

The classifier judged **27 of 80** facts durable and 53 decaying, and got all five known-good
answers right. On the 12 prompts a person would actually type, **two prompts change which
facts they return** and two more change only the order; the other eight are identical, ids
and order. Nothing degrades. That is not luck and it is not a weak test: the effect is small
because the corpus is **3–18 days old**, so a durable fact currently gains only 1.10–1.60×
over an identical decaying one, and because the durable set is **88% astronomy** while the
seven semiconductor prompts are scoped to a lineage holding **2 durable facts out of 41**.
Re-run with every `:ts` moved back a year — the case the design is actually about, where the
multiplier is 13.8× — the answer barely moves: the same two prompts change membership, one of
them by one extra fact. The one thing worth arguing about is `mem-16`, a dated policy
reversal the classifier called durable, which takes the **#1 slot on three prompts** a year
out, including one where it is not the best answer. That is the axis's built-in limit:
**durability of a claim is not durability of its relevance.**

---

## Method

Reused from `docs/superpowers/specs/2026-08-08-entities-recall-measured.md`, not re-derived —
the same 12 prompts, the same notebooks, the same copied corpus, the same lexical-only
reasoning.

### What "before" means here, and why it is not the 2026-08-08 baseline

The recorded baseline in that document is the **df26e2b worktree**, run to measure the entity
union. It is not the baseline for this change and cannot be: both the code (`subject-set` was
rewritten on 2026-08-09) and the corpus (the repair re-filed 15 astronomy facts on the same
day; `data/memory.edn` went from `1999a9d1…` to `3b219034…`) have moved since.

The baseline this task needs is different and simpler. **The change under test is `:durable?`
on a fact record, and `weight` already ships the `if`.** So both runs are today's tree, on the
same code path, over two copies of the same 80 facts that differ in exactly two keys:

- **before** — `/tmp/loci-decay-measure/before-memory.edn`, the untouched copy, **nothing
  classified**, which is what `data/memory.edn` is today
- **after** — `/tmp/loci-decay-measure/data/memory.edn`, the same file after `loci.classify`
  appended one line per fact

One variable, isolated. Verified rather than assumed: between the two files, `:ts`,
`:strength`, `:fact`, `:entities` and `:source` are unchanged on all 80 facts, and the only
difference is `{[:durable-by :durable?] 80}` — two keys added, nothing else.

### The call is `remembered-context`'s, character for character

`remembered-context` is private, so the calls were made one level down at `mold/recall` with
the opts it builds (`server.clj:1685`):

```clojure
(let [sources (when (and space (sub/object st space)) (lineage-sources st space))
      subs    (subject-set m prompt)]
  (cond-> {:k 6 :semantic? false :subjects subs}
    sources (assoc :filter {:sources sources :entities subs})))
```

`lineage-sources` and `subject-set` are public and are the real ones, not reconstructions. Two
deliberate differences from the shipped line, both stated so nobody has to guess:

1. **`m` is passed in.** The shipped code reads `@mem/memory` — the global singleton, which
   resolves to the repo's `data/`. Dereferencing it is the thing this task must never do.
2. **`:semantic? false`** where the shipped code says `true`. See below.

### Lexical only, and why

Every run passed `:semantic? false`. **Not one of the 80 live facts carries a `:vec`**
(measured: 0), and no embedder is configured in this environment. With `:semantic? true` the
semantic half returns `{:off true}` and falls through to `only-lex` — the identical answer by
a longer route. Passing `false` says what actually ran. The table below is a claim about the
lexical ranking and nothing else.

This does not weaken the result. `weight` is applied **once on both paths** — on the lexical
score in `score`, and on the fused score at `memory.clj:1210` — so `:durable?` enters the
fused ranking by exactly the same multiplication. What a fused run would change is which
candidates are near the boundary, not what the flag does to them.

### Safety

`data/` was copied, never opened.

- `data/memory.edn` and `data/substrate/` → `/tmp/loci-decay-measure/data`; the copy's
  `lock.mdb` was deleted so Datalevin would build its own
- every JVM ran with `LOCI_DATA` **and** `-Dloci.data-dir` pointed at the copy
- every memory was constructed as `(mem/file-memory "/tmp/loci-decay-measure/…")`;
  `loci.memory/memory` was never dereferenced
- the classifier wrote to the **copy** and to nothing else
- fingerprints before and after are identical (§ Verification)

---

## The corpus, as measured

Run against the copy. **No drift from the figures recorded on 2026-08-09** — name for name
and count for count, which is worth stating because the notebooks under those facts *did*
move.

| | 2026-08-09 | now |
|---|---|---|
| live facts | 80 | **80** |
| entity mentions | 203 | **203** |
| distinct names | 104 | **104** |
| names appearing exactly once | 68 | **68** |
| facts carrying a `:vec` | 0 | **0** |
| facts carrying `:durable?` | — | **0** |

Frequent names, unchanged: `tsmc` 10, `asml` 8, `jupiter` 7, `taiwan` 7, `united states` 6,
then six names at 5.

**What did change is where the astronomy facts live.** The 2026-08-09 repair re-homed
`mem-23`…`mem-37` out of `space:dd-16/17/18` into `space:dd-35/36/37`, so the three
semiconductor deep dives no longer hold five Solar-System facts each. That repair is why the
semiconductor prompts in this measurement cannot see an astronomy fact at all, and it matters
for reading the table: **the largest predictable risk of this change — durable planet facts
outranking decaying chip facts — is currently held off by `:filter {:sources}`, not by the
scoring.**

### The corpus is young, and that bounds everything below

| age | facts | `decay` | what a durable fact gains |
|---|---|---|---|
| 3 days | 5 | 0.909 | 1.10× |
| 4 days | 14 | 0.882 | 1.13× |
| 18 days | 61 | 0.625 | **1.60×** |

Nothing in the memory is older than 18 days. The design's motivating number — Kepler's Third
Law at 0.076 of itself — is a **year** out, where the multiplier is 13.2×. So the primary
table measures this change at roughly a twelfth of the strength it will eventually have, and
the addendum re-runs it at full strength.

---

## Step 1 — the classifier, and the five known-good answers

One live run over the copy: **80 facts, 4 batches of 20, 64.9 seconds, `:ok? true`,
80 appended, 0 declined, 0 unjudged, 0 already-judged, 0 yours.** The plan's four lists were
disjoint and covered every fact.

The exact instruction that produced these numbers is `loci.classify/system-prompt`, public for
this reason and quoted rather than paraphrased:

> You judge whether each of these remembered facts will go out of date.
> `"durable": true` — the claim will never go out of date: a law, a definition, a mathematical
> relation, a historical record.
> `"durable": false` — the claim carries an implicit "as of now", so re-checking the world
> later could change it: a price, a market share, a capacity, a current holder of an office,
> anything measured.
> "Kepler's Third Law states that T² ∝ a³" is durable. "Lithography capacity is booked through
> 2027" is not — it was true when it was written, and re-checking could change it.

### The gate

| fact | expected | got | |
|---|---|---|---|
| `mem-34` Kepler's Third Law, T² ∝ a³ | durable | **durable** | ✅ |
| `mem-35` Mercury's period is 88 Earth days | durable | **durable** | ✅ |
| `mem-36` Neptune ~165 Earth years | durable | **durable** | ✅ |
| *"lithography capacity booked through 2027"* — `mem-55`, CoWoS packaging sold out through 2027 | **not** durable | **decays** | ✅ |
| *"TSMC controls ~70% of global foundry revenue"* — `mem-2` **and** `mem-52`, the same claim twice | **not** durable | **decays**, both | ✅ |

**5 of 5.** The nearest sentences to the two negative examples were used, since neither is in
the corpus verbatim; the other capacity-through-a-year fact, `mem-5` (HBM sold out through
2026), also came back decaying, and so did `mem-6` (ASML's 50–60 systems a year — a capacity).

Full list of all 80 judgements: **Appendix A**.

---

## Step 4 — the shape of the classification

**27 of 80 durable (33.75%), 53 decaying.** The first thing to rule out is a classifier that
answers "durable" to everything, which would pass every structural test in `classify_test` and
be worthless. It did not: it answered durable to a third.

But a third could still be a coin. It is not, and here is the evidence.

### It splits by subject, hard

| area | facts | durable | |
|---|---|---|---|
| astronomy (`cosmos`, `dd-35/36/37`) | 25 | **22** | 88% |
| economics (`dd-22/27/28/32`) | 14 | **3** | 21% |
| semiconductors (`semis`, `dd-13`…`dd-18`) | 41 | **2** | 5% |

A coin gives ~50% in each cell and no correlation with subject. This is a 17-fold spread
between the extremes, and it is the spread the design predicted in prose: laws of orbital
mechanics do not go out of date, market shares do.

### It also splits *within* subjects, which is what rules out "it just classified the topic"

The subject split alone could be a model reading "planets = timeless" off the topic rather
than the sentence. The exceptions say otherwise. Every one of these is a pair of adjacent
facts in the same notebook, judged differently:

| | durable | decaying | the distinction it drew |
|---|---|---|---|
| astronomy | `mem-59` Neptune's period is 60,190 days | **`mem-58`** Saturn has the most moons, **146 confirmed** | a moon count is a measurement of a world that moves; an orbital period is not |
| astronomy | `mem-57` Jupiter's diameter and mass | **`mem-79`, `mem-80`** the core-accretion *model*, and the Juno/Cassini data supporting it | a model is a claim that could be revised |
| economics | **`mem-63`** South Asia, 100% in 1987 → 13% **in 2023** | `mem-64` Latin America, 9% in 1987 → 44% **today** | both are the same shape of sentence; only one anchors its endpoint to a year |
| economics | **`mem-70`, `mem-73`** *"this is called the latitude–income gradient"* | `mem-68`, `mem-69`, `mem-71`, `mem-72` the correlation itself | a name for a thing is a definition; the correlation is measured |
| semis | **`mem-8`** ASML spent €10bn and 20 years; Nikon and Canon abandoned it | `mem-6` ASML builds 50–60 systems a year | a history versus a rate |
| semis | **`mem-16`** the US EDA export ban of May 2025, rescinded 2 July 2025 | `mem-9` the trilateral export-control regime, and Japan's 87–91% of photoresist | a dated event versus a standing arrangement |

And it is **self-consistent on duplicates**, which a coin cannot be. The corpus restates the
same claim in different notebooks ten times; every group was checked and **every group agrees
with itself — 12 pairwise comparisons, 12 agreements, 0 split verdicts**:

`mem-2`/`mem-52` (TSMC's ~70%) · `mem-3`/`mem-15`/`mem-39` (EDA market share) ·
`mem-12`/`mem-50` (12 Arizona fabs, $165B) · `mem-38`/`mem-41` (TSMC/Taiwan is the chokepoint) ·
`mem-66`/`mem-67`/`mem-74` (East Asia spans four income groups) · `mem-68`/`mem-72` (latitude
and GDP) · `mem-69`/`mem-71` (low-income near the equator) — all decaying;
`mem-70`/`mem-73` (the *name* latitude–income gradient) · `mem-33`/`mem-61` (distance and
period) · `mem-36`/`mem-59` (Neptune's 165 years) — all durable.

A coin flipped 12 times agrees with itself on all 12 groups about one time in 64.

**Verdict: a judgement, not a coin.** It is reading the sentence.

### The two calls worth arguing about

Both are defensible on the letter of the prompt ("a historical record" is named as durable),
and both deserve to be seen rather than buried:

- **`mem-8`** — *"ASML spent over €10 billion and ~20 years of R&D on EUV"*. A running total
  that keeps running. As a statement of the barrier to entry it is durable; as a number it is
  not. It enters two top-6s below and improves both.
- **`mem-16`** — *"The US export ban on EDA tools to China in May 2025 was rescinded on July 2,
  2025"*. A dated past event, correctly durable, and **the single fact responsible for three
  of the four prompt changes measured**. See § What got worse.

If you disagree with either, the pane's toggle is one click and `:durable-by "you"` means no
later pass will revert it. Disagreeing with `mem-16` would erase most of the semiconductor
half of this change.

---

## Step 2 — the 12 prompts, before and after

Top 6 each way, on the corpus as it is **today** (3–18 days old). `→` reads before → after.

| | prompt | notebook | entered | displaced | judgement |
|---|---|---|---|---|---|
| P1 | What are the chokepoints in the semiconductor supply chain? | `space:semis` | `mem-16` at #4 (was #11) | `mem-17` (to #7) | **Neutral.** Neither is a chokepoint statement. `mem-17` (Big-3 EDA revenue from China) out, `mem-16` (the EDA export ban, rescinded) in — a trade between two second-order EDA facts. |
| P2 | Why can no one else build an EUV machine? | `space:dd-13` | `mem-8` at #5 (was #7) | `mem-21` (to #7) | **Better.** `mem-8` — €10bn, 20 years, Nikon and Canon gave up — is a direct answer to *why can no one else*. `mem-21` (China's parallel programmes) is about who is trying. |
| P3 | How risky is TSMC's concentration in Taiwan? | `space:dd-14` | — | — | **Identical**, ids and order. No durable fact in scope. |
| P4 | How do export controls on EDA tools affect China? | `space:dd-15` | — | — | **Better.** Same six ids; `mem-16` moves **#3 → #1**. `mem-16` *is* the US export ban on EDA tools to China. The question's own answer went to the top. |
| P5 | What would a Taiwan blockade cost the global economy? | `space:dd-16` | — | — | **Identical**, ids and order. |
| P6 | How much of TSMC's capacity will be outside Taiwan by 2030? | `space:dd-17` | — | — | **Identical**, ids and order. |
| P7 | Why can't AI chip customers move away from TSMC? | `space:dd-18` | — | — | **Identical**, ids and order. |
| P8 | How does Serbia's growth compare with the rest of the region? | `space:serbia` | — | — | **Still nothing, before and after.** `space:serbia` owns no facts and the prompt names no entity the corpus knows, so there are 0 candidates. Unchanged by this work and not its business. |
| P9 | Which economies have the highest inflation and unemployment? | `space:pub-econ` | — | — | **Still nothing.** Same reason, 0 candidates. |
| P10 | What do we know about TSMC and Taiwan? | brand-new | — | — | **Identical**, ids and order. All six are semiconductor facts; none is durable. |
| P11 | Tell me about Jupiter and the gas giants. | brand-new | — | — | **Identical ids, identical order, every score up.** All six are durable, so all six lift by the same 1.10–1.60× and nothing reorders. `mem-77` 0.383 → 0.430. |
| P12 | How exposed is the United States to all this? | brand-new | — | — | **Neutral.** Same six ids; `mem-16` moves **#3 → #2**, above `mem-43` (a $10tn war-loss estimate). Defensible; not obviously an improvement. |

### Totals

- **Changed at all (ids or order): 4 of 12** — P1, P2, P4, P12.
- **Changed membership: 2 of 12** — P1, P2, one fact each.
- **Changed for the worse: 0 of 12.**
- Changed for the better: 2 of 12 — P2, P4.
- Identical, id for id and rank for rank: 8 of 12 (six of them non-empty).

The plan's stop threshold is one third — 4 of 12. **This measures 0.**

For comparison, the change this plan was written to be afraid of, measured the same way:
the 2026-08-08 recall union degraded **7 of 12**.

---

## Step 3 — what got worse

A measurement that only confirms is not a measurement, so: here is everything that got worse
or could, stated as plainly as the wins.

### 1. One fact is doing almost all of the work, and it is the most arguable one

`mem-16` accounts for **three of the four** changed prompts today (P1, P4, P12) and, a year
out, takes **#1 on all three**. Its judgement is defensible — a dated event that has already
happened cannot un-happen — but the consequence is that a policy reversal from July 2025
becomes the permanently top-ranked fact of the semiconductor family, on questions that are not
about export controls.

This is the axis's built-in limit and it should be written down: **durability of a claim is not
durability of its relevance.** Decay was quietly doing two jobs — *is this still true* and *is
this still what we are working on* — and this change separates only the first. For a durable
fact, the second is now unrepresented; the only remaining counterweight is `:strength`, which
rises when a fact keeps coming up, and relevance itself.

### 2. Inside a family that is mostly durable, recency stops being a tiebreaker

P11 is the clean case: all six of its facts are durable, so all six gain the same multiplier
and **the order is now frozen** — `mem-77` will outrank `mem-76` by the same ratio for ever.
Before, a re-derived or newer astronomy fact would eventually float up past an older one on
recency alone. It will not any more. Whether that is a loss depends on whether you think
recency was carrying information inside a set of timeless facts; on 22 astronomy facts, I do
not think it was, but it is a behaviour change nobody asked for and it is real.

`mem-58` (Saturn's 146 moons) is the exception that proves the rule: it is the one cosmos fact
still ageing, so a year from now it will have sunk below every durable sibling. That is the
correct outcome — moon counts change — and it is worth noticing that the classifier produced
it deliberately.

### 3. The astronomy/semiconductor asymmetry is real and is currently masked

22 of 27 durable facts are astronomy; 39 of 41 semiconductor facts decay. A year out, any
recall that can see both halves ranks astronomy 13× higher for the same relevance. On these 12
prompts that never happens, but **only because `:filter {:sources}` keeps each lineage prompt
inside its own family**, and because no prompt names a name the two halves share. It is a
scoping accident, not a property of the scoring.

Where it would stop being masked: a brand-new notebook (no lineage, so no `:sources` filter)
whose prompt names something both halves carry. **Measured: there is no such name.** The three
subject areas' entity sets are almost disjoint —

| | astronomy (23 names) | semiconductors (51) | economics (31) |
|---|---|---|---|
| shared with astronomy | — | **none** | **none** |
| shared with semiconductors | none | — | `japan`, and only `japan` |

So a mixed-subject collision cannot currently be constructed on this corpus at all, and P11's
six planet facts have no route into a semiconductor prompt however durable they become. **The
import in `2026-08-10-a-corpus-worth-browsing-design.md` is what will create the first ones**,
and that spec now has a number to design against: where the name sets do overlap, whichever
half is classified durable wins a mixed recall by up to 13× on relevance ties.

### 4. What did not get fixed, and never claimed to be

P8 and P9 return nothing, before and after — the two notebooks the entities design named as
its motivating case still own no facts. Unrelated to decay, restated so the record does not
imply otherwise.

---

## Addendum — the same 12 prompts, one year on

**Not requested by the plan.** It is the same measurement with the clock moved, it cost one
re-run of an existing script and no model calls, and without it the primary table would be
reporting this change at a twelfth of its strength — which for a change whose entire
motivation is *"a year from now Kepler ranks at 0.076"* would be the wrong number to hand a
decision.

Method: every fact's `:ts` moved back 365 days in both corpora, in RAM only. Nothing is
redefined — `decay` reads `now - ts`, so ageing the records is exactly equivalent to running
this a year from now. Ages become 368–383 days; the durable multiplier becomes **13.2–13.8×**.

| | before → after | judgement |
|---|---|---|
| P1 | `mem-16` (#1) and `mem-8` (#2) enter; `mem-44`, `mem-17` leave. `mem-16` scores 0.041 against the best decaying fact's 0.011 — **3.6×** | **Mixed.** `mem-8` entering is right. `mem-16` at #1 for *"what are the chokepoints"* is the concern in § 1 arriving. |
| P2 | `mem-8` enters at #2; `mem-21` leaves | **Better**, same trade as today, one rank higher |
| P3, P5, P6, P7, P10 | **identical**, ids and order | — |
| P4 | same six ids; `mem-16` **#3 → #1** at 0.170 against 0.019 — **8.8×** | **Better.** It is the answer to the question. |
| P8, P9 | still nothing | — |
| P11 | **identical ids, identical order**; every score ×13.2 | — |
| P12 | same six ids; `mem-16` **#3 → #1**, above `mem-49` (the US share of advanced-node capacity) | **Mildly worse.** For *"how exposed is the United States"*, `mem-49` is the better answer and it lost the top slot to a rescinded ban. |

### Totals, a year out

- Changed at all: **4 of 12** — the same four.
- Changed membership: **2 of 12** — the same two. P1 trades two facts instead of one.
- **Changed for the worse: 1 of 12 (P12), and P1 is mixed.** Threshold is 4.

**The striking result is how little the multiplier matters.** Thirteen times the leverage
moves one extra fact. The reason is structural: `:filter {:sources}` means a prompt only ever
competes facts against their own family, and the two families that hold 55 of 80 facts are
either almost entirely durable (astronomy, 22 of 25 — so nothing reorders) or almost entirely
decaying (semiconductors, 2 of 41 — so there is nothing to lift).

---

## Step 5 — verification

```
$ md5sum -c /tmp/loci-decay-measure/before.md5
/home/damirk/src/loci/data/memory.edn: OK
/home/damirk/src/loci/data/substrate.edn: OK

$ git status --short data/      # nothing
```

`data/memory.edn` = `3b219034921e2f1bfe438837a2244e95`, `data/substrate.edn` =
`e7a6eecdff7bb44ad6b959bb1a7fb7c6`, both before and after. `data/substrate/` was never opened
— its `data.mdb` mtime is unchanged at Aug 10 16:30, which it would not be if a JVM had taken
the write lock.

No file under `src/` or `test/` was modified. Everything above ran from scripts under the
session scratchpad against `/tmp/loci-decay-measure`.

### The write path, checked on the copy — these are Task 7's Step 5 assertions

Worth doing here, because a copy is where you find out:

| check | result |
|---|---|
| line count | 99 → **179**, exactly +80, matching `:appends 80` |
| append-only prefix | first 28,517 bytes of the written file md5 to `3b219034921e2f1bfe438837a2244e95` — **byte-identical to the input** |
| `:ts` changed on | **no fact** |
| `:strength` changed on | **no fact** |
| `:fact` / `:entities` / `:source` changed on | **no fact** |
| keys added | `{[:durable-by :durable?] 80}` — two keys, every fact, nothing else |
| ids | identical set, 80 → 80 |

The `mold/remember` trap the design and Task 4 both warned about — reset `:ts`, bumped
`:strength` — did not happen. That is now measured on the real 80 facts, not only on fixtures.

---

## What surprised me

1. **How little happened.** I expected a global ranking change to move more than two prompts,
   and I expected the year-out run to be the one that broke something. It moved the same two.
   The reason is worth keeping: **absent-means-decays plus a classifier that says durable to a
   third plus `:filter {:sources}`** compose into a change that can only act inside a family,
   and the families here are lopsided in both directions.

2. **The classifier discriminating inside a sentence pair.** `mem-63` (*"…to approximately 13%
   in 2023"*) durable and `mem-64` (*"…to 44% today"*) decaying is the same claim shape about
   the same kind of quantity, separated only by whether the endpoint is anchored to a year. I
   did not expect that resolution, and it is the single strongest piece of evidence that the
   judgement is about the sentence rather than the topic.

3. **`mem-58`.** *"Saturn has the most moons of any planet, with 146 confirmed"* is the only
   cosmos fact judged decaying, and it is the only cosmos fact whose number a survey can
   change. Nothing in the prompt mentions moons or counts.

4. **One fact carrying the whole effect.** Three of four changed prompts trace to `mem-16`
   alone. A measurement over 80 facts and 12 prompts that comes down to one judgement is a
   thinner result than the totals suggest, and it means the user's disagreement with **one**
   toggle would undo most of the semiconductor half of this change.

5. **The corpus is too young for this change to matter yet.** Every fact is 3–18 days old and
   the maximum lift available today is 1.60×. The design's headline number (0.076 at a year) is
   a statement about a corpus that does not exist yet. This is the right time to make the
   change — it is cheapest when nothing is old — but "measured, no degradation" today is a
   weaker guarantee than it sounds, and the year-out addendum is the honest version of it.

6. **A copied `lock.mdb` hangs Datalevin.** `cp -r data/substrate` brought a lock file whose
   process-shared mutex belonged to a dead process, and the first JVM to open the copy blocked
   indefinitely with no error. Deleting `lock.mdb` from the copy fixes it — LMDB rebuilds it.
   Not a defect in this repo; a trap for the next measurement, so: **copy `data.mdb` and
   `VERSION`, not `lock.mdb`.**

---

## Recommendation

**Task 7 should run.** 0 of 12 prompts degrade today, 1 of 12 a year out, against a stop
threshold of 4; the classifier passes all five known-good checks; the split is 27/80 and is
demonstrably a judgement; and the write path leaves `:ts`, `:strength` and the text of every
fact untouched, verified against the real corpus on a copy.

Two things to carry into it rather than discover later:

- Task 7 Step 3 compares its dry run against this one. **`mem-16` and `mem-8` are the two
  judgements to look at with your eyes** — they are the arguable ones and they are the ones
  that move the ranking. `agent/request` sends `:temperature 0.2`, so a differing judgement is
  a question; a differing set of ids is a stop.
- **`java -jar /app/loci.jar` is running** (container `3adae49cfad1`, image `loci`, healthy).
  It is on the Docker volume `loci_loci-data`, **not** the repo's `data/`, so it does not hold
  the file Task 7 writes — but Step 1 says confirm and say what you found, and `pgrep` will
  find it. Confirm the volume before concluding it is harmless.

---

## Appendix A — all 80 judgements

As returned by `loci.classify` on 2026-08-11, one live run over the copy. 27 durable, 53
decaying.

| id | judgement | fact |
|---|---|---|
| `mem-1` | decays | ASML holds a 100% monopoly on EUV lithography machines needed for sub-7nm chips, with no alternative. |
| `mem-2` | decays | TSMC controls ~70% of global foundry revenue and >90% of sub-7nm advanced chips, all produced in Taiwan. |
| `mem-3` | decays | Synopsys, Cadence, and Siemens EDA collectively control over 70% of the electronic design automation market, enabling US export controls on chip design tools. |
| `mem-4` | decays | The top five silicon wafer suppliers—Shin-Etsu, SUMCO, GlobalWafers, Siltronic, and SK Siltron—control about 90% of the market, with Japan and Taiwan dominating high-purity supply. |
| `mem-5` | decays | Three firms—Samsung, SK Hynix, and Micron—control over 90% of the DRAM market, and HBM memory for AI is sold out through 2026. |
| `mem-6` | decays | ASML's annual production of roughly 50-60 EUV systems is the physical ceiling on how much leading-edge fab capacity the world can add each year. |
| `mem-7` | decays | An EUV machine has ~457,000 components sourced from ~5,100 suppliers across 17+ countries, with optics from Carl Zeiss SMT and light source from TRUMPF. |
| `mem-8` | **durable** | ASML spent over €10 billion and ~20 years of R&D on EUV; competitors Nikon and Canon abandoned the technology. |
| `mem-9` | decays | The US, Netherlands, and Japan coordinate a trilateral export control regime covering EUV, with the Netherlands controlling ASML and Japan controlling 87-91% of photoresist supply. |
| `mem-10` | decays | China is investing ~€37 billion in a domestic EUV effort, with commercial production estimated at 2028-2030 at earliest, versus ASML's three-decade head start. |
| `mem-11` | decays | A Taiwan Strait blockade could trigger a $5–10 trillion global economic shock and wipe $5–10 trillion from equity markets within days. |
| `mem-12` | decays | TSMC's Arizona expansion plans $165B+ for up to 12 fabs, with N4 already in production and 2nm/A16 targeted by ~2030. |
| `mem-13` | decays | Samsung (7.2% share) and Intel (18A process targeting H2 2025) are years behind TSMC at leading nodes. |
| `mem-14` | decays | Over $250B in global government incentives (CHIPS Act, EU Chips Act, Japan, India) are funding diversification, but a genuinely distributed supply chain is years away. |
| `mem-15` | decays | Synopsys, Cadence, and Siemens EDA collectively control ~74% of the global EDA market, with Siemens' Calibre holding >70% of the physical verification sign-off market. |
| `mem-16` | **durable** | The US export ban on EDA tools to China in May 2025 was rescinded on July 2, 2025, after China agreed to resume rare-earth magnet licensing to US firms. |
| `mem-17` | decays | The Big-3 EDA firms derive 12-16% of revenue from China, with Synopsys alone earning ~$1B from China in FY2024. |
| `mem-18` | decays | Empyrean leads China's domestic EDA firms with ~50% of domestic EDA sales, but collectively Chinese EDA companies hold <10% of the global market. |
| `mem-19` | decays | No viable non-Western alternative exists for advanced nodes (sub-7nm and GAA architectures), and Huawei's claimed 14nm domestic EDA breakthrough in 2023 remains far from leading-edge parity. |
| `mem-20` | decays | No single technology today can fully replace ASML's EUV for high-volume leading-edge manufacturing; Canon's Nanoimprint Lithography (NIL) has a mask lifetime of ~50 wafers and high defectivity, while Japan's Free-Electron Laser (FEL) requires a $400M particle accelerator with no commercial timeline before the 2030s. |
| `mem-21` | decays | China is pursuing multiple parallel lithography paths including a conventional LPP-based EUV clone (prototype completed Dec 2025, targeting chips by 2028-2030), an SSMB synchrotron approach, and a particle-beam 'Xizhi' system, but all are years behind ASML and face supply-chain gaps in optics (Zeiss), light sources (TRUMPF), and yield learning. |
| `mem-22` | decays | The most realistic near-term alternatives are complements or stopgaps: Directed Self-Assembly (DSA) works alongside EUV to shrink features below EUV's single-exposure limit; multi-beam e-beam direct write serves low-volume/high-mix niches; and DUV multi-patterning (used by SMIC for 7nm) is a costly, low-yield dead-end below 5nm. |
| `mem-23` | **durable** | Terrestrial planets are rocky/metallic with solid surfaces, while gas giants are ~90% hydrogen and helium with no solid surface, and ice giants are mostly ices (water, methane, ammonia) with ~20% H/He and no solid surface. |
| `mem-24` | **durable** | Terrestrial planets are small (4,879–12,756 km diameter) and dense (3,933–5,514 kg/m³), gas giants are enormous (120,536–142,984 km) and low-density (687–1,326 kg/m³), and ice giants are intermediate in size (~49,000–51,000 km). |
| `mem-25` | **durable** | Terrestrial planets orbit close to the Sun (57.9–227.9 million km) with short orbital periods (88–687 days), while ice giants are the most distant (2,872–4,495 million km) with the longest orbital periods (84–164 years). |
| `mem-26` | **durable** | Terrestrial planets have few or no moons and no rings, whereas both gas and ice giants have ring systems and many moons. |
| `mem-27` | **durable** | Gas giants rotate very fast (~10 hours) and have strong internal heat, ice giants rotate moderately (~16–17 hours) with weak internal heat, and Uranus has an extreme axial tilt (~98°). |
| `mem-28` | **durable** | Giant planets have masses 14–318 times Earth's, giving them Hill spheres 35–60 times larger than terrestrial planets, enabling far more moon captures. |
| `mem-29` | **durable** | Neptune has the largest Hill sphere (86.9 million km) despite only 5% of Jupiter's mass, because its greater distance from the Sun weakens solar tidal forces. |
| `mem-30` | **durable** | Terrestrial planets like Mercury and Venus have Hill spheres under 1 million km, making stable moon orbits nearly impossible. |
| `mem-31` | **durable** | Earth's single large moon formed from a rare giant impact (Theia collision), not from capture. |
| `mem-32` | **durable** | Mars's two tiny moons, Phobos and Deimos, are captured asteroids that barely remain in orbit due to Mars's low mass. |
| `mem-33` | **durable** | The orbital period of a planet increases with its distance from the Sun, following Kepler's Third Law. |
| `mem-34` | **durable** | Kepler's Third Law states that the square of a planet's orbital period is proportional to the cube of its semi-major axis. |
| `mem-35` | **durable** | Mercury has the shortest orbital period (88 Earth days) and is closest to the Sun. |
| `mem-36` | **durable** | Neptune has the longest orbital period (about 165 Earth years) and is farthest from the Sun among the classical planets. |
| `mem-37` | **durable** | The relationship between orbital period and distance is not linear; it follows a power law with exponent 1.5. |
| `mem-38` | decays | TSMC's foundry concentration in Taiwan is the single most important chokepoint, with ~70% of global foundry revenue and 90%+ of sub-7nm chips made there. |
| `mem-39` | decays | Synopsys, Cadence, and Siemens control the EDA software pipeline for all advanced chip design. |
| `mem-40` | decays | TSMC is the chokepoint where everything converges; without its fabrication and advanced packaging, no advanced chip gets built. |
| `mem-41` | decays | The geographic concentration of TSMC in Taiwan makes it the #1 systemic risk in the entire semiconductor supply chain. |
| `mem-42` | decays | A Taiwan Strait blockade could reduce global GDP by 2.8% ($2.7 trillion) in the first year, according to the Institute for Economics & Peace. |
| `mem-43` | decays | Full-scale war involving the US could cause a global GDP loss of $10–10.6 trillion (~10% of global GDP), per Bloomberg Economics. |
| `mem-44` | decays | BCA Research estimates the S&P 500 could crash 40% in a full-scale war scenario. |
| `mem-45` | decays | Moody's Analytics finds that US military intervention leads to a sharper but shorter recession (10% trough) compared to a prolonged no-intervention conflict (8% trough but deeper long-run scars). |
| `mem-46` | decays | The semiconductor chokehold from a blockade could force $1.6 trillion in foregone revenue from chip-dependent industries, per Rhodium Group. |
| `mem-47` | decays | By 2030, only ~15% of TSMC's advanced-node capacity will be outside Taiwan, overwhelmingly in Arizona. |
| `mem-48` | decays | Taiwan's share of global advanced-node capacity (all players) is projected to decline from ~71% in 2021 to ~58% by 2030. |
| `mem-49` | decays | The U.S. share of global advanced-node capacity is forecast to reach 22–28% by 2030, up from negligible in 2021. |
| `mem-50` | decays | TSMC plans up to 12 fabs in Arizona with a total investment of $165–$265 billion. |
| `mem-51` | decays | A disruption in Taiwan by 2030 would still cut off ~85% of the world's most advanced chip supply, reducing the risk from 'total' to 'severe'. |
| `mem-52` | decays | TSMC controls ~70% of global foundry revenue and >90% of sub-7nm chip production. |
| `mem-53` | decays | A single leading-edge fab costs $20-40B, and TSMC's US fabs cost 4-5x more than in Taiwan. |
| `mem-54` | decays | Samsung's 3nm GAA yields are reportedly below 20%, and Intel 18A has no major external customer. |
| `mem-55` | decays | TSMC's CoWoS advanced packaging capacity is sold out through 2027, with lead times of 52-78 weeks. |
| `mem-56` | decays | The minimum timeline to establish a credible second source at advanced nodes is 3-5 years under optimistic assumptions. |
| `mem-57` | **durable** | Jupiter is the largest planet with a diameter of 139,820 km and a mass 317.8 times Earth's. |
| `mem-58` | decays | Saturn has the most moons of any planet in the solar system, with 146 confirmed. |
| `mem-59` | **durable** | Neptune has the longest orbital period at 60,190 days, equivalent to about 165 Earth years. |
| `mem-60` | **durable** | Terrestrial planets (Mercury, Venus, Earth, Mars) are significantly smaller than gas and ice giants. |
| `mem-61` | **durable** | The distance from the Sun correlates with orbital period; outer planets take much longer to orbit. |
| `mem-62` | decays | Sub-Saharan Africa contains over 50% of the World Bank's 26 low-income economies, the highest proportion of any region. |
| `mem-63` | **durable** | The share of low-income countries in South Asia fell from 100% in 1987 to approximately 13% in 2023. |
| `mem-64` | decays | The share of high-income countries in Latin America and the Caribbean grew from 9% in 1987 to 44% today. |
| `mem-65` | decays | The Middle East and North Africa had no low-income countries in 1987 but now has approximately 10%, largely due to conflict. |
| `mem-66` | decays | East Asia and Pacific is the only region that spans all four income groups, from low-income North Korea to high-income Australia, Japan, Singapore, and South Korea. |
| `mem-67` | decays | East Asia and Pacific is the only region that spans all four World Bank income groups: low, lower-middle, upper-middle, and high. |
| `mem-68` | decays | Countries with capital cities located farther from the equator tend to have higher GDP per capita. |
| `mem-69` | decays | Low-income countries are disproportionately concentrated in tropical regions near the equator. |
| `mem-70` | **durable** | The consistent association between a country's latitude and its economic development is referred to as the latitude-income gradient. |
| `mem-71` | decays | Low-income countries cluster near the equator. |
| `mem-72` | decays | Greater distance from the equator correlates with higher GDP per capita. |
| `mem-73` | **durable** | This relationship is known as the latitude–income gradient. |
| `mem-74` | decays | The East Asia and Pacific region is the only World Bank region spanning all four income classifications. |
| `mem-75` | decays | The latitude–income gradient is strongest in the Africa-Europe axis and weakest in regions with resource-export economies or colonial settlement patterns. |
| `mem-76` | **durable** | Jupiter and Saturn together hold 92.5% of all planetary mass in the Solar System. |
| `mem-77` | **durable** | Jupiter alone accounts for 71.2% of total planetary mass, at 317.8 Earth masses. |
| `mem-78` | **durable** | The four terrestrial planets combined account for only 0.44% of planetary mass. |
| `mem-79` | decays | The core-accretion model explains the mass distribution by a threshold effect: beyond the snow line, cores reaching ~10–15 Earth masses undergo runaway gas accretion to form giant planets. |
| `mem-80` | decays | Juno and Cassini data revealing heavy-element cores and elevated atmospheric metallicities in Jupiter and Saturn support core accretion over disk instability. |

## Appendix B — the top 6, verbatim, on the two prompts that changed membership

**P1 — `space:semis`, "What are the chokepoints in the semiconductor supply chain?"**
Detected subjects: none. Lineage: 24 sources, 35 candidates. The entity union is inert here,
exactly as `subject-set`'s docstring says.

| # | before | after |
|---|---|---|
| 1 | `mem-41` 0.09836 | `mem-41` 0.09836 |
| 2 | `mem-14` 0.05517 | `mem-14` 0.05517 |
| 3 | `mem-46` 0.04875 | `mem-46` 0.04875 |
| 4 | `mem-51` 0.03738 | **`mem-16` 0.04138** ← durable |
| 5 | `mem-44` 0.03560 | `mem-51` 0.03738 |
| 6 | **`mem-17` 0.03238** | `mem-44` 0.03560 |

**P2 — `space:dd-13`, "Why can no one else build an EUV machine?"**
Detected subjects: `euv`. Lineage: 6 sources, 10 candidates.

| # | before | after |
|---|---|---|
| 1 | `mem-6` 0.24472 | `mem-6` 0.24472 |
| 2 | `mem-1` 0.04063 | `mem-1` 0.04063 |
| 3 | `mem-7` 0.03724 | `mem-7` 0.03724 |
| 4 | `mem-20` 0.02282 | `mem-20` 0.02282 |
| 5 | `mem-40` 0.01495 | **`mem-8` 0.02222** ← durable |
| 6 | **`mem-21` 0.01407** | `mem-40` 0.01495 |
