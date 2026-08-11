# What the subject union changes about what the agent is told

Date: 2026-08-09 (Task 12 of `docs/superpowers/plans/2026-08-08-entities.md`)
Status: **measured. The result does not clear the plan's own bar, and Task 12 Step 4 says
stop and report.** 7 of 12 prompts degrade — 58%, against a stated stop threshold of one
third. Nothing was changed in `src/` to make this document; it is evidence for a decision
the plan reserves for the user.

---

## The one-paragraph version

The union works exactly as designed in the case it was built for — a **brand-new notebook
with no lineage**, where the agent used to be told nothing at all, now gets six on-subject
facts (P10, P11, P12). It fails in the case nobody measured: **a notebook that already has
a lineage.** On all seven such prompts the top 6 got worse, and on the largest notebook in
the corpus it got worse absolutely — a question about semiconductor chokepoints came back
with six facts about Jupiter and Saturn. The cause is not the inverse-frequency weighting.
It is that `subject-set`'s `:score` restriction systematically deletes the names a notebook
is *about* (they are lineage-only, which is what a research hub means) and keeps only the
names it accidentally shares with unrelated notebooks — and that the entity half then
saturates at 1.0 and is worth 0.4 against a keyword half worth 0.004–0.11.

---

## Method

### The baseline is the real old code

`git worktree add /tmp/loci-entities-measure/before df26e2b` — the commit this branch
started from — and the same prompts were run there, against the same copied corpus,
sequentially (never concurrently: Datalevin locks the store directory).

`remembered-context` is private and its shape changed, so the calls were made one level
down, at `mold/recall`, with **character-for-character the opts the old `remembered-context`
built**:

```clojure
;; df26e2b, server.clj:1120
(cond-> {:k 6 :semantic? true}
  (and space (sub/object st space))
  (assoc :filter {:sources (lineage-sources st space)}))
```

`lineage-sources` is public in both trees, so the lineage walk is the real one, not a
reconstruction. What was skipped is the string formatting of the REMEMBERED block, which
does not affect ranking.

A **second, simulated** baseline was also run — today's tree with `#'loci.memory/relevance`
redefined to the df26e2b formula and old-shaped opts — purely as a cross-check. It agreed
with the real worktree run on **12 of 12 prompts, top-6 ids in order**. That is what makes
the variant runs below (`max w`, and the two probes) trustworthy: they are redefinitions in
one process, and that process has been shown to reproduce the real baseline exactly.

### Lexical only, and why

Every run passed `:semantic? false`. **Not one of the 80 live facts carries a `:vec`**
(measured: `(count (filter #(seq (:vec %)) facts))` = 0). The semantic half therefore
contributes nothing on this corpus — `semantic-ranking` would return `{:off true}` with no
embedder configured, or an empty ranking with one, and both fall through to `only-lex`. The
table below is a claim about the lexical ranking and nothing else. Reporting a fused ranking
here would be a claim about a model that never ran.

### Safety

`data/` was copied, never opened. `data/memory.edn` and `data/substrate/` were copied to
`/tmp/loci-entities-measure/data`; every JVM ran with both `LOCI_DATA` and
`-Dloci.data-dir` pointed there, and every memory was constructed with an explicit path
(`mem/file-memory "/tmp/…/memory.edn"`) rather than by dereferencing `loci.memory/memory`.
Fingerprints before and after are identical (§ Verification).

---

## The corpus, as measured

Run against the copy on 2026-08-09. **No drift from the design's 2026-08-08 figures** — the
numbers are identical, name for name and count for count.

| | design cites | measured |
|---|---|---|
| live facts | 80 | **80** |
| entity mentions | 203 | **203** |
| distinct names | 104 | **104** |
| names appearing exactly once | 68 | **68** |
| facts carrying a `:vec` | — | **0** |

Frequent names: `tsmc` 10, `asml` 8, `jupiter` 7, `taiwan` 7, `united states` 6, `china` 5,
`earth` 5, `ice giants` 5, `neptune` 5, `saturn` 5, `sun` 5, `terrestrial planets` 5,
`gas giants` 4, `japan` 4, `synopsys` 4, then eight names at 3.

Facts by source notebook:

| notebook | facts | | notebook | facts |
|---|---|---|---|---|
| `space:semis` | 11 | | `space:dd-15` | 5 |
| `space:cosmos` | 10 | | `space:dd-32` | 5 |
| `space:dd-16` | 10 | | `space:dd-22` | 3 |
| `space:dd-17` | 10 | | `space:dd-27` | 3 |
| `space:dd-18` | 10 | | `space:dd-28` | 3 |
| `space:dd-13` | 5 | | `space:dd-14` | 5 |

`space:semis` and its six descendants hold **56 of 80** facts, exactly as `subject-set`'s
docstring says. `space:serbia` and `space:pub-econ` — the two roots the design named as the
motivating case — hold **zero facts of their own**.

### The surprise, and it matters for how you read the table

**15 of the 80 facts are mis-filed.** `space:dd-16` ("Geopolitical Shock Scenarios"),
`space:dd-17` ("TSMC's Global Fab Buildout") and `space:dd-18` ("Customer Diversification
Challenges") each hold five Solar-System facts alongside their five semiconductor ones —
`mem-23`…`mem-37`: Kepler's Third Law, Hill spheres, Phobos and Deimos, the Theia
collision. Those notebook ids were plainly reused, and the facts recorded against the
earlier occupant were never re-homed.

This is **pre-existing corpus damage, not caused by this change**, and the pre-change code
was immune to it by accident: the old entity half intersected a fact's names with the
*query tokens*, and a semiconductor question contains no planet words, so those facts scored
0 and `(filter #(pos? (:score %)))` dropped them. The new code puts the planet names into
the subject set and they win. Because that confounds the reading, every affected prompt was
re-run against a corpus with those 15 facts removed; both results are reported.

---

## The table

Per prompt: the ids that **entered** the top 6 and the ids that were **displaced**, against
the real df26e2b run. `→` marks the direction of the trade.

| | prompt | notebook | entered | displaced | judgement |
|---|---|---|---|---|---|
| P1 | What are the chokepoints in the semiconductor supply chain? | `space:semis` | `mem-76` `mem-78` `mem-77` `mem-58` `mem-60` `mem-35` — **all six about planets** | `mem-41` `mem-14` `mem-46` `mem-51` `mem-44` `mem-17` — all six on subject | **WORSE, total.** 6 of 6 replaced. Jupiter, Saturn, terrestrial planets and Mercury's orbital period, for a question about chip supply chains. |
| P2 | Why can no one else build an EUV machine? | `space:dd-13` | `mem-2` `mem-53` `mem-52` `mem-51` `mem-49` | `mem-6` `mem-1` `mem-7` `mem-20` `mem-21` — **every EUV fact** | **WORSE.** The notebook is *about* EUV. All five EUV facts gone, replaced by generic TSMC/Taiwan/US-capacity facts. |
| P3 | How risky is TSMC's concentration in Taiwan? | `space:dd-14` | `mem-76` `mem-78` `mem-77` (planets) | `mem-53` `mem-48` `mem-51` | **WORSE.** Three planetary facts take half the block from three Taiwan-capacity facts. |
| P4 | How do export controls on EDA tools affect China? | `space:dd-15` | `mem-2` `mem-52` `mem-10` `mem-53` `mem-51` `mem-49` | `mem-17` `mem-18` `mem-16` `mem-3` `mem-21` `mem-1` — **every EDA fact** | **WORSE, total.** 6 of 6. `mem-16` (the US EDA export ban on China, literally the question) displaced by `mem-53`, a fab-cost fact. |
| P5 | What would a Taiwan blockade cost the global economy? | `space:dd-16` | `mem-78` `mem-76` `mem-77` (planets) `mem-51` `mem-53` | `mem-38` `mem-41` `mem-42` `mem-11` `mem-4` | **WORSE.** `mem-42` ("GDP −2.8%, $2.7tn") and `mem-11` ("$5–10tn shock") are the answer, and both were displaced — by Jupiter. |
| P6 | How much of TSMC's capacity will be outside Taiwan by 2030? | `space:dd-17` | `mem-77` `mem-76` (Jupiter) | `mem-38` `mem-41` | **WORSE, mildly.** The displaced pair were duplicative chokepoint restatements, but two Jupiter facts are not an improvement on them. |
| P7 | Why can't AI chip customers move away from TSMC? | `space:dd-18` | `mem-51` `mem-61` (the Sun) `mem-50` | `mem-53` `mem-41` `mem-55` | **WORSE.** `mem-55` (CoWoS packaging sold out through 2027) and `mem-53` (fab cost 4–5×) are the two facts that answer "why can't they move", and both left. |
| P8 | How does Serbia's growth compare with the rest of the region? | `space:serbia` | — | — | **Unchanged: still nothing.** The named motivating case is *not fixed*. `space:serbia` owns no facts, its prompt names no entity in the corpus, so `:scope` is empty and the union has nothing to widen with. |
| P9 | Which economies have the highest inflation and unemployment? | `space:pub-econ` | — | — | **Unchanged: still nothing.** Same reason. The design's second named root is also not fixed. |
| P10 | What do we know about TSMC and Taiwan? | `space:zzz-new` (brand-new) | `mem-2` `mem-53` `mem-52` `mem-38` `mem-41` `mem-40` | none — the block was **empty** before | **BETTER.** This is the feature working, and it is the reported case: six on-subject facts where the agent previously got no REMEMBERED block at all. |
| P11 | Tell me about Jupiter and the gas giants. | `space:zzz-new` (brand-new) | `mem-77` `mem-76` `mem-57` `mem-26` `mem-24` `mem-27` | none — **empty** before | **BETTER.** All six on subject. |
| P12 | How exposed is the United States to all this? | `space:new-25` (brand-new) | `mem-49` `mem-43` `mem-16` `mem-45` `mem-3` `mem-9` | none — **empty** before | **BETTER, with the caveat below.** All six carry `united states` and nothing else connects them; this is the anchoring case in its purest form. |

### Totals

- **Changed at all: 10 of 12.** (P8, P9 unchanged — both still return nothing.)
- **Changed for the worse: 7 of 12 — 58%.**
- Changed for the better: 3 of 12, all of them the brand-new-notebook case.
- **Every prompt typed into a notebook that has a lineage got worse. Every one.** 7 for 7.

The plan's Step 4 sets the stop threshold at one third. 58% is not near it.

### With the 15 mis-filed facts removed

To separate the corpus damage from the design, the same prompts were re-run against a
corpus without `mem-23`…`mem-37`.

| | recovers? | |
|---|---|---|
| P1 | **yes** | 4 of 6 baseline ids return; `mem-9` (the trilateral EUV export regime) and `mem-4` (wafer suppliers) enter in place of `mem-44` (S&P 500 crash) and `mem-17`. A **good** trade for a chokepoint question. |
| P3 | mostly | 5 of 6; `mem-10` (China's €37bn EUV effort) enters, `mem-51` leaves. Mildly worse. |
| P6 | **yes** | `mem-49` enters, `mem-41` leaves — and `mem-49` (US share of advanced-node capacity by 2030) is genuinely relevant to the question. Better. |
| P2 | **no** | top 6 identical to the shipped, degraded one. Every EUV fact still gone. |
| P4 | **no** | top 6 identical to the shipped, degraded one. Every EDA fact still gone. |
| P5 | partly | 3 of 6 baseline ids return (against 1 shipped), but `mem-42` and `mem-11` — the two facts that state what a blockade costs — are still displaced. Still worse. |
| P7 | **no** | 3 of 6, same as shipped; still loses `mem-55` and `mem-53`. |

So the corpus damage accounts for three of the seven (P1, P3 and P6 recover; P1 and P6 end
up *better* than the baseline). **On a clean corpus the change still degrades 5 of 12
prompts — 42% — which is still above the one-third stop threshold.** Fixing the mis-filed
facts is worth doing on its own account, but it does not rescue this change.

---

## Step 4 — why the prompts that got worse, got worse

Two mechanisms, and neither is the one the plan's OPEN question anticipated.

### (a) `:score` deletes exactly the names the notebook is about

`subject-set`'s `:score` set is `detected(prompt) ∪ (lineage-names ∩ elsewhere-names)` —
the lineage half restricted to names that also appear outside the lineage. The restriction
exists for a good, measured reason (without it every local fact ties at 0.4000 and the union
never delivers anything). But look at what it produces for the biggest notebook in the
corpus:

> **P1, `space:semis`, "What are the chokepoints in the semiconductor supply chain?"**
> `:score` = `("earth" "japan" "jupiter" "mars" "mercury" "neptune" "saturn" "sun"
> "terrestrial planets" "venus")`

Ten names, and **not one of them is a semiconductor**. `tsmc`, `taiwan`, `asml`, `synopsys`
are all lineage-only — they exist in the semis family and nowhere else in the corpus, which
is precisely what it means for a research hub to be about a subject — so the restriction
strips every one of them. What survives is `japan` (shared with an income-groups deep dive)
and the planet names (shared with `space:cosmos`, via the mis-filed facts). The subject set
for a semiconductor question is a list of planets, and the entity half then does exactly
what it is asked to.

This is structural, not incidental: **the larger a notebook's lineage, the fewer of its own
subjects survive into `:score`, and the more the entity half is decided by whatever names
happen to leak in from elsewhere.** `space:semis` holds 56 of 80 facts, so almost nothing
survives. The small deep dives (P2, P4) keep more names — but they keep the *wrong* ones:
`dd-13` is about EUV, and its `:score` set keeps `tsmc`, `taiwan`, `china` and
`united states` while `euv` itself survives only because the prompt happens to name it.

### (b) the entity half saturates, and it outweighs the keyword half by up to 100×

The within-fact ratio does not discriminate. A fact **all** of whose names are in the
subject set scores `ent = 1.0`, whatever those names are — `jupiter` (df 7), `tsmc` (df 10)
and a df-1 name are worth identically 1.0.

Measured over the shipped top-6s:

| prompt | top-6 facts with `ent` exactly 1.0 |
|---|---|
| P1 | 6 of 6 |
| P2 | 6 of 6 |
| P3 | 6 of 6 |
| P4 | 6 of 6 |
| P5 | 6 of 6 |
| P6 | 5 of 6 |
| P7 | 6 of 6 |
| P10 | 6 of 6 |
| P11 | 1 of 6 |
| P12 | 1 of 6 |

**On 8 of the 10 non-empty blocks, at least 5 of the 6 facts are pinned at the maximum
entity score.** The entity half is not ranking anything; it is a gate. Selection into the
top 6 is decided by *did every one of your names get matched*, which rewards facts with the
fewest, most generic names and punishes the richest ones — and the ordering inside the top 6
is then left to the keyword half and recency.

And the keyword half is tiny. Mean over each shipped top 6:

| prompt | `0.6·kw` | `0.4·ent` | ratio |
|---|---|---|---|
| P1 | 0.0348 | 0.4000 | 11 : 1 |
| P2 | 0.0040 | 0.4000 | **100 : 1** |
| P3 | 0.0630 | 0.4000 | 6 : 1 |
| P4 | 0.0124 | 0.4000 | 32 : 1 |
| P5 | 0.0446 | 0.4000 | 9 : 1 |
| P6 | 0.1144 | 0.3614 | 3 : 1 |
| P7 | 0.0362 | 0.4000 | 11 : 1 |
| P10 | 0.0675 | 0.4000 | 6 : 1 |
| P11 | 0.0648 | 0.1948 | 3 : 1 |
| P12 | 0.0318 | 0.1171 | 4 : 1 |

A single matched entity name is worth between three and a hundred times everything the words
of the question have to say. That is why `mem-77` — "Jupiter alone accounts for 71.2% of
total planetary mass" — beats every semiconductor fact in `space:semis`: it carries one
name, that name is in the subject set, and it collects the full 0.4.

---

## Step 4a — the OPEN question, with numbers

### Is `united states` actually anchoring?

**Yes, and the OPEN note's prediction is confirmed exactly** — but it is the smaller half of
the problem.

Confirmed: `mem-49`, whose only entity is `united states` (df 6), scores `ent = 1.000`. It
is not demoted at all. Where that shows up in practice:

| prompt | `united states` in the subject set? | top-6 facts it pulled in |
|---|---|---|
| P2 — "Why can no one else build an EUV machine?" | yes | **`mem-49`** — "The U.S. share of global advanced-node capacity is forecast to reach 22–28% by 2030" |
| P4 — "How do export controls on EDA tools affect China?" | yes | **`mem-49`** — same fact |
| P12 — "How exposed is the United States to all this?" | yes | all six: `mem-49` `mem-43` `mem-16` `mem-45` `mem-3` `mem-9` |
| P3, P5, P6, P7 | yes | none |

So it *is* anchoring, not merely a theoretical hazard: on P2 and P4 a df-6 name lifts an
off-topic fact about US fab capacity into a top 6 about EUV machines and EDA export
controls, and on P12 it is the sole reason all six facts are there — `mem-49` takes the top
slot at 0.3046, **3.8× the runner-up** at 0.0793, purely because `united states` is its only
name.

But it is not special. The same table shows `tsmc` (df 10), `taiwan` (df 7), `jupiter` (df
7), `saturn` (df 5) and `sun` (df 5) doing the identical thing. Across the seven lineage
prompts, **39 of the 42 top-6 slots went to facts whose matched names are all df ≥ 5**, and
**38 of those 39 scored `ent` = 1.000** (the exception is `mem-47` at 0.421, the only
richly-named fact in any top 6). `united states` accounts for 2 of the 39. Fixing it
specifically would fix about a twentieth of the problem.

### What the `max w` candidate does

`ent = (Σ_{e∈hit} w(e) / Σ_{e∈es} w(e)) · max_{e∈hit} w(e)`, run in the scratch process only.

**How much it moves:** on the seven lineage prompts it changes 2–5 of the 6 ids
(P1 2, P2 5, P3 3, P4 4, P5 5, P6 5, P7 5). On the three brand-new-notebook prompts it
changes 0–1 — those results are essentially unaffected, because a fresh notebook's subject
set is small and its candidates are all genuinely on subject.

**Whether it moves for the better: mixed, and net worse.** Two clear wins:

- P2 restores `mem-6` ("ASML's annual production of 50–60 EUV systems is the physical
  ceiling") to **#1** — the correct answer, which the shipped ranking dropped entirely.
- P4 restores `mem-16` (the US EDA export ban on China) to **#1** — likewise.

Against that, it destroys three prompts the shipped version merely damaged. Overlap with the
df26e2b baseline, `|B ∩ shipped|` → `|B ∩ maxw|`:

| | P1 | P2 | P3 | P4 | P5 | P6 | P7 |
|---|---|---|---|---|---|---|---|
| shipped | 0 | 1 | 3 | 0 | 1 | **4** | **3** |
| `max w` | 1 | 1 | 3 | 3 | **0** | **1** | **0** |

P6 goes from 4 baseline ids to 1; P7 from 3 to 0; P5 from 1 to 0. P5's `max w` top 6 is
Synopsys' EDA share, Samsung's yields, Samsung/Intel, terrestrial planets, Arizona fabs —
for "what would a Taiwan blockade cost the global economy".

**And it does not fix P1 at all**: 5 of 6 are still planetary. P1's failure is the subject
*set*, and no weighting formula can repair a subject set that contains no subject.

**The effect on `0.6·kw + 0.4·ent`, in real numbers.** Mean entity contribution over each
top 6 collapses from 0.400 to 0.020–0.105 (median 0.055) — a 4× to 20× shrink — while the
keyword half is unchanged at 0.004–0.114. The two halves swap places:

| | today | with `max w` | which half decides |
|---|---|---|---|
| P2 | 0.4000 vs 0.0040 | 0.0502 vs 0.0040 | still entity, 12 : 1 |
| P3 | 0.4000 vs 0.0630 | 0.0648 vs 0.0630 | **dead even** |
| P6 | 0.3614 vs 0.1144 | 0.0554 vs 0.1144 | **keyword now wins, 2 : 1** |
| P10 | 0.4000 vs 0.0675 | 0.0486 vs 0.0675 | **keyword now wins** |
| P11 | 0.1948 vs 0.0648 | 0.0356 vs 0.0648 | **keyword now wins** |
| P12 | 0.1171 vs 0.0318 | 0.0195 vs 0.0318 | **keyword now wins** |

The OPEN note warned that `max w` "quietly hands the keyword half more of the decision as
the corpus grows". It does not need the corpus to grow. **At 80 facts it has already
happened**, on 4 of the 10 non-empty prompts, and the keyword half is a jaccard between the
query tokens and a whole fact sentence — a signal that measures 0.004 on P2. Handing it the
decision is not obviously an improvement over handing it to a saturated entity gate.

### Recommendation

**Do not adopt `max w`, and do not ship the union in its current form.** `max w` is a real
improvement to the *formula* — it is the only variant that put `mem-6` and `mem-16` back at
the top of the two prompts whose own subject the shipped ranking lost — but on this corpus
it wins two prompts and loses three, it leaves P1 untouched because P1's problem is upstream
of scoring, and it rescales the entity half below the keyword half on four of ten prompts,
which is a large uncontrolled change to every caller (`/api/leap` on every keystroke, the
memory pane, the entity page) bought for a net-negative result on the caller it was aimed
at. The trade it asks you to accept is *"give up a saturated entity gate in exchange for a
jaccard over long sentences"*, and neither side of that trade is a ranking anyone designed.
What the numbers actually point at is `subject-set`: for the notebook holding 56 of 80
facts, `:score` contains **no name that notebook is about**, and every degraded prompt
traces back to that. I would put the decision to the user as a choice between (i) fixing the
subject set and leaving `relevance` exactly as it is, or (ii) fixing both — but not (iii)
changing `relevance` alone, which is the only option currently on the table.

**Evidence for (i), since a recommendation without numbers is an assurance.** One probe was
run in the scratch process — `:filter {:entities detected}` and `:subjects detected`, i.e.
the union driven only by names **the prompt itself names**, with `relevance` untouched:

| | baseline ids retained, shipped | baseline ids retained, prompt-detected |
|---|---|---|
| P1 | 0 of 6 | **6 of 6** |
| P2 | 1 of 6 | **6 of 6** |
| P3 | 3 of 6 | **6 of 6** |
| P4 | 0 of 6 | **5 of 6** (`mem-10`, China's EUV effort, enters — a good trade for a China question) |
| P5 | 1 of 6 | 3 of 6 (still loses `mem-42` and `mem-11`) |
| P6 | 4 of 6 | **6 of 6** |
| P7 | 3 of 6 | **5 of 6** |
| P10 / P11 / P12 | 6 new facts each | **identical — all three wins fully retained** |

One prompt (P5) still degrades, against seven today, and the brand-new-notebook case — the
whole point of the feature — is unaffected. This is a probe, not a proposal: it drops the
lineage half of the subject set entirely, which means a deep dive no longer inherits its
parent's *subjects*, only its parent's *facts* (via `:sources`, unchanged). Whether that is
an acceptable loss is a design question, and it is the user's.

---

## The entity page, checked on real data

Task 4 observed on its fixture that the dominant signal on an entity page is inverse
dilution — the richest fact ranked last, 0.048 against 0.52. **It holds on the live corpus,
and it is worse than the fixture suggested**, because a real page's richest fact is also its
most substantial one.

`GET /api/entity?name=tsmc` — 10 facts, in the order the page renders them:

| rank | score | id | entities | |
|---|---|---|---|---|
| 1 | 0.2921 | `mem-52` | `tsmc` | "TSMC controls ~70% of global foundry revenue…" |
| 2 | 0.2826 | `mem-53` | `tsmc` | "A single leading-edge fab costs $20-40B…" |
| 3 | 0.2826 | `mem-40` | `tsmc` | "TSMC is the chokepoint where everything converges…" |
| 4 | 0.1635 | `mem-2` | `tsmc` `taiwan` | |
| 5 | 0.1313 | `mem-41` | `tsmc` `taiwan` | |
| 6 | 0.1239 | `mem-38` | `tsmc` `taiwan` | |
| 7 | 0.0843 | `mem-50` | `tsmc` `arizona` | |
| 8 | 0.0773 | `mem-12` | `tsmc` `arizona` | |
| **9** | **0.0694** | `mem-47` | `tsmc` `taiwan` `arizona` | "By 2030, only ~15% of TSMC's advanced-node capacity will be outside Taiwan, overwhelmingly in Arizona." |
| **10** | **0.0465** | `mem-55` | `tsmc` `cowos` | "TSMC's CoWoS advanced packaging capacity is sold out through 2027, lead times 52-78 weeks." |

The ranking is a near-perfect sort by **how few other things the fact mentions**: every
1-entity fact, then every 2-entity, then the 3-entity one. `mem-47` — arguably the single
most informative TSMC fact in the corpus — sits ninth, **4.2× below** a bare restatement of
market share.

`asml` is starker still. `mem-7` ("An EUV machine has ~457,000 components sourced from
~5,100 suppliers across 17+ countries, optics from Carl Zeiss SMT, light source from
TRUMPF") ranks **last of eight** at 0.0199, **5.8× below** `mem-10` at 0.1164. The one fact
that explains *why* the monopoly exists is at the bottom of the page about the monopoly.

Same open question, different clothes, as the task anticipated: a within-fact ratio reads
"how much of this fact is about you", and by that measure the fact that knows most about you
scores worst.

---

## Verification

```
$ md5sum -c /tmp/loci-entities-measure/before.md5
/home/damirk/src/loci/data/memory.edn: OK
/home/damirk/src/loci/data/substrate.edn: OK

$ git status --short          # nothing under data/, nothing at all
$ ls -la data/                # substrate/ mtime unchanged: Aug 6 06:40
```

`data/memory.edn` = `1999a9d108df48b17ccbb51291a0a955`,
`data/substrate.edn` = `e7a6eecdff7bb44ad6b959bb1a7fb7c6` — both as expected, before and
after. `data/substrate/` was never opened; the LMDB directory's mtime is untouched.

No file under `src/` was modified. Every variant in this document is a `with-redefs` or an
opts change inside a scratch process under
`/tmp/claude-1001/…/scratchpad`, and the worktree at
`/tmp/loci-entities-measure/before` was removed on completion.

---

## What surprised me

1. **The mis-filed facts.** 15 of 80 — nearly a fifth of the corpus — are Solar-System facts
   sitting in three semiconductor deep dives whose notebook ids were reused. Nothing in the
   product surfaces this, and the old scoring hid it perfectly by accident. The entity work
   is what made it visible, which is an argument *for* entities even as it damages this
   measurement.
2. **`:score` containing no subject of the notebook it is for.** I expected the restriction
   to be conservative. It is the opposite: it is a filter that keeps precisely the names a
   notebook does *not* own, and it gets stricter the more a notebook knows.
3. **The saturation.** I expected the entity half to rank; it gates. 5-or-6-of-6 at exactly
   1.0 on eight of ten prompts means the 0.4 term contributes no ordering information at all
   in the common case — it only decides membership.
4. **The two motivating notebooks are still blind.** `Serbia — open economic data` and
   `World economies — output and prices` were named in the design as the roots that would
   never offer each other anything. They own zero facts between them, so the union has
   nothing to widen with and they return nothing, before and after. The change as measured
   does not fix its own headline example.
5. **`max w` making four prompts keyword-dominated at 80 facts**, when the OPEN note framed
   that as a risk to watch as the corpus grew.

---

# Addendum, 2026-08-09: the fix, measured

Everything above stands as written; nothing in it has been edited. This section records what
happened after the user read it and decided.

## The decision

**Drive the union from prompt-detected names only. Drop the lineage half of the subject set
entirely — from both `:filter {:entities}` and `:subjects`. Leave `relevance` alone.**

That is a deliberate departure from design §5, which defines the subject set as "entities
detected in the prompt ∪ entities of facts already in this lineage". The second half is
gone, and the cost is named and accepted: **a deep dive no longer inherits its parent's
*subjects*. It still inherits its parent's *facts*, through `:filter {:sources}` and
`lineage-sources`, exactly as it always has** — a different mechanism, untouched.

`subject-set` collapses to one value and no longer takes `sources`:

```clojure
(defn subject-set [m prompt]
  (ent/detect (names-of (mem/all-facts m)) prompt))
```

The saturation finding in § (b) above is confirmed and **not** addressed here. The weighting
is unchanged.

## Method

Identical to the Method section above, reused rather than re-derived:

- the **copy** at `/tmp/loci-entities-measure/data`; `data/` was never opened
- `mem/file-memory` with an explicit path; `loci.memory/memory` never dereferenced
- `:semantic? false`, because not one of the 80 facts carries a `:vec`
- **the baseline was not re-run.** Top-6 ids were read from the recorded
  `/tmp/loci-entities-measure/before.edn` — the real df26e2b worktree run. The lineage walk
  agrees with it: `:n-sources` matches, prompt for prompt.

Results written to `/tmp/loci-entities-measure/after-prompt-only.edn`.

## Retention against the df26e2b baseline

| | prompt | notebook | detected in prompt | retained | what traded |
|---|---|---|---|---|---|
| P1 | chokepoints in the semiconductor supply chain | `space:semis` | **none** | **6 of 6, in order** | nothing — see below |
| P2 | why can no one else build an EUV machine | `space:dd-13` | `euv` | **6 of 6, in order** | nothing |
| P3 | how risky is TSMC's concentration in Taiwan | `space:dd-14` | `taiwan` `tsmc` | **6 of 6, in order** | nothing |
| P4 | export controls on EDA tools and China | `space:dd-15` | `china` | **5 of 6** | `mem-10` in, `mem-1` out |
| P5 | what would a Taiwan blockade cost | `space:dd-16` | `taiwan` | **3 of 6** | `mem-51` `mem-48` `mem-47` in; `mem-42` `mem-11` `mem-4` out |
| P6 | TSMC capacity outside Taiwan by 2030 | `space:dd-17` | `taiwan` `tsmc` | **6 of 6**, reordered | nothing enters or leaves |
| P7 | why can't AI chip customers move away | `space:dd-18` | `tsmc` | **5 of 6** | `mem-38` in, `mem-55` out |
| P8 | Serbia's growth vs the region | `space:serbia` | none | — | still nothing, before and after |
| P9 | highest inflation and unemployment | `space:pub-econ` | none | — | still nothing |
| P10 | what do we know about TSMC and Taiwan | brand-new | `taiwan` `tsmc` | — | `mem-2` `mem-53` `mem-52` `mem-38` `mem-41` `mem-40` — **identical to the shipped run** |
| P11 | tell me about Jupiter and the gas giants | brand-new | `gas giants` `jupiter` | — | `mem-77` `mem-76` `mem-57` `mem-26` `mem-24` `mem-27` — **identical** |
| P12 | how exposed is the United States | brand-new | `united states` | — | `mem-49` `mem-43` `mem-16` `mem-45` `mem-3` `mem-9` — **identical** |

### Totals

- **Changed for the worse: 1 of 12 — P5.** Was 7 of 12.
- Changed for the better: 3 of 12 — P10, P11, P12, the brand-new-notebook case, **all three
  wins retained id for id.**
- Identical to the baseline top 6: 5 of 12 (P1, P2, P3, P8, P9).
- P4 and P7 each trade one fact; P6 keeps all six and only reorders.
- **Every one of the seven lineage prompts that regressed now retains 5 or 6 of 6, except
  P5.** The plan's one-third stop threshold is 33%; this is 8%.

This reproduces the probe the decision was made on, prompt for prompt, with no result better
or worse than it predicted.

## P1 is inert, and that is the mechanism

`space:semis`, *"What are the chokepoints in the semiconductor supply chain?"* — the prompt
that returned six facts about Jupiter and Saturn — now returns the baseline's six ids in the
baseline's order, because **the detected subject set is empty**. The corpus has no entity
named `chokepoint`, `semiconductor` or `supply chain`; the prompt names nothing, so
`:subjects` is `#{}` and `:filter {:entities}` is `#{}`, and both are no-ops inside `recall`.
The block is character for character what df26e2b produced.

Worth stating plainly rather than claiming a win: on this prompt the fix does not out-rank
the planets, it declines to nominate them. Most real prompts do not name an entity, and on
those the entity work now costs nothing and does nothing — which is the honest description of
what was bought. `server_test`'s
`a-prompt-naming-nothing-recalls-exactly-what-it-did-before-entities` pins that equivalence
against df26e2b's opts.

## The one prompt still worse, and why it is not the union's fault

**P5, `space:dd-16`, "What would a Taiwan blockade cost the global economy?"** Detected:
`taiwan`. The two facts that *are* the answer are both displaced:

| id | entities | fact |
|---|---|---|
| `mem-42` | `institute for economics & peace`, **`taiwan strait`** | GDP −2.8% ($2.7tn) in the first year |
| `mem-11` | **`taiwan strait`**, `bloomberg economics` | a $5–10tn global shock |
| `mem-4` | 7 names incl. `taiwan`, six of them df 1 | the top five wafer suppliers |

Two distinct causes, **neither of them the subject union**:

1. **`taiwan strait` is a different entity from `taiwan`.** The prompt says "Taiwan
   blockade", so `taiwan` is detected; the facts that answer it are filed under
   `taiwan strait`, and the comparison is equality on normalized names. They score 0 on the
   entity half while `mem-51` and `mem-48` — whose *only* name is `taiwan` — score 1.0. This
   is an **alias** problem, and aliases are explicitly out of scope in the plan ("the one
   part of the model that cannot be derived… it needs its own plan"). P5 is the first
   measured case of what that costs.
2. **Within-fact dilution.** `mem-4` carries `taiwan` plus six names of df 1, so its entity
   half is ≈ 0.027 — it is penalised for the rare names it does *not* match. This is the
   plan's OPEN ratio-versus-global-rarity question, in the wild.

Both are recorded, neither is fixed here. Fixing either would be a scoring change, and the
decision above was explicitly to leave `relevance` alone.

P7's single trade has the same second cause: `mem-55` (CoWoS sold out through 2027, a genuine
answer to "why can't they move") carries `tsmc` and `cowos`, and `cowos` is df 1, so it
scores ≈ 0.09 on the entity half against `mem-38`'s ≈ 0.41. P4's trade goes the other way and
is good: `mem-10`, China's €37bn domestic EUV effort, enters a question about export controls
on China, displacing a generic ASML-monopoly fact.

## Verification

```
$ md5sum -c /tmp/loci-entities-measure/before.md5
/home/damirk/src/loci/data/memory.edn: OK
/home/damirk/src/loci/data/substrate.edn: OK
```

`data/memory.edn` = `1999a9d108df48b17ccbb51291a0a955`, `data/substrate.edn` =
`e7a6eecdff7bb44ad6b959bb1a7fb7c6`, unchanged. Nothing under `data/` was opened; every JVM
ran against the copy with `LOCI_DATA` and `-Dloci.data-dir` pointed there.

Suites at the commit that carries this: `clojure -M:test` 458 tests / 1830 assertions, 0
failures; `npm run test:browser` 89 / 89.
