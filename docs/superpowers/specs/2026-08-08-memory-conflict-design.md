# When memory disagrees with itself: contradiction and supersession

Date: 2026-08-08
Status: **noted, not designed.** Recorded while reading ActiveGraph (2026-08-08). No
brainstorming session has been held; the approaches below are sketches, not decisions, and
nothing here is approved for planning.

## The defect

loci's memory has two mechanisms for "these two facts are related", and both of them mean
*the same*:

- **write time** — `remember` finds a near-duplicate by Jaccard over tokens and reinforces
  it: `:strength` goes up, the new text is discarded.
- **background** — `merge-similar!` finds a near-duplicate by cosine at a calibrated
  threshold and absorbs one into the other, recording `:merged-from` so the merge is
  auditable and `unmerge!`-able.

Nothing represents **disagreement**. Two facts that differ exactly where it matters — a
number, a polarity, a date, a status — are either merged (and one of them silently wins) or
they coexist, both live, both eligible for `recall`, both injected into the same
`REMEMBERED` block, with no signal that they cannot both be true.

An example the corpus invites: `mem-6` records "ASML's annual production of roughly 50-60
EUV systems is the physical ceiling…". A research run six months later that finds 70 does
not correct it. It either merges into it — reinforcing a ceiling that moved — or sits beside
it, and the agent is asked to reason from both.

**The scoring asymmetry makes it worse.** Reinforcement raises `:strength`, and `weight`
(`memory.clj`) multiplies by `1 + 0.25 × (strength − 1)`. A wrong fact repeated by three
research runs outranks a single later correction until recency decay catches up, which takes
about a month per halving.

## Measured state, 2026-08-08 — read this before designing anything

`data/memory.edn`, read (never written):

| | |
|---|---|
| live facts | 80 |
| facts carrying `:vec` | **0** |
| occurrences of `:vec` in the whole file | **0** |

The embed worker starts only when an embedding endpoint is configured
(`server.clj:1855`, else the "semantic recall: off" line at `:1863`). So on this corpus
**no fact has ever been embedded**, `merge-similar!` has never had vectors to work with, and
recall is running on keyword overlap + entity overlap alone. Whether that is configuration or
an unreachable embedder was not investigated — `loci.env` is not read.

Two consequences:

1. Any design here that assumes cosine is available must **first make that true**, and then
   measure. The population this feature would serve — pairs that are near-duplicates but not
   merged — is currently unmeasurable and might be near zero.
2. It raises the value of the inverse-frequency entity weighting in the entities design
   (`2026-08-08-entities-design.md` §5): entity overlap is 0.4 of the *only* signal in play
   right now, not 0.4 of a signal that a vector pass backstops.

## Prior art

ActiveGraph (<https://activegraph.ai/>, Apache 2.0, Yohei Nakajima; paper *"The Log is the
Agent"*, arXiv 2605.21997) models `claim` / `evidence` objects with **`supports`** and
**`contradicts`** as typed edges carrying behaviour. Its architecture is otherwise
convergent with loci's — append-only log as truth, deterministic graph projection — which is
why the divergence is informative: it treats relations between claims as first-class, where
loci has only similarity.

## Sketch of the approaches, with their traps

**A. Recall-time surfacing (writes nothing).** When the top-k contains two facts that are
near-duplicates but were not merged, mark them in the `REMEMBERED` block so the model sees
the tension instead of averaging over it. Cheapest, reversible by construction, and honest —
but it fixes the prompt and not the memory, and it fires only when both facts happen to be
retrieved together.

**B. Background adjudication, proposed for consent.** Extend the sweep that already runs
`merge-similar!`: pairs in the band *below* the merge threshold get an LLM adjudication —
same / supersedes / contradicts / unrelated — and anything but "same" becomes a **proposal**
you accept or decline, reusing the audit shape `merge`/`unmerge` established. Fits where the
machinery already lives; costs model calls on a schedule; needs embeddings to exist first.

**C. Write-time, at distillation.** `distill-facts` already receives the prompt; give it the
existing facts that share the new fact's entities and ask it to mark supersession explicitly.
Attention is where the work is, so the judgement is best-informed here — but it makes every
research run more expensive and puts a correctness decision inside a `future` that currently
swallows its exceptions (`server.clj:1132`).

**The trap that decides the shape.** A later fact that disagrees may mean *the world changed*
(supersession — the old fact was true and is dated) or *the record was wrong*
(contradiction — one of them must be retracted). These need different handling and the
difference is a judgement, not a threshold. That argues for proposal-with-consent (B or C)
over anything automatic, and against ever letting a similarity score decide it.

## Dependency on the entities work

Conflicting facts almost always share entities. The entity index is therefore the natural
**candidate generator** — it turns "compare every pair" into "compare pairs that share a rare
entity". This work is meaningfully cheaper after `2026-08-08-entities-design.md` ships, which
is the sequencing argument for doing entities first.

## What to measure before building

1. Turn on embeddings, let the worker sweep, then report the pairwise cosine distribution
   over the live corpus and the count of pairs in the near-but-not-merged band.
2. Of those pairs, how many a human calls a genuine conflict rather than a topical neighbour.
3. If (2) is ~0 on a real corpus, **do not build this.** Record the number and close it.

## Non-goals

Not truth maintenance. No confidence propagation, no belief-revision calculus, no automatic
retraction. The most this should ever do without you is *show* you that two things it
remembers cannot both be true.
