# loci phase 3a — recall that finds by meaning

Date: 2026-08-07
Status: proposed
Parent: `2026-08-05-datalevin-substrate-design.md` §3, §4, §5, §5.1, §9

## Goal

`recall` stops being substring matching. A fact recorded as "TSMC concentrates advanced
nodes in Taiwan" should surface for "where is chip manufacturing concentrated" — which today
it does not, because no word overlaps.

This is phase 3 of the Datalevin design, scoped down to what is shippable on its own.

## Scope, and what is deliberately left out

**In: the `Recall` layer — memory facts.** ~84 recorded facts, one embedding each.

**Out, for now: semantic LEAP over substrate objects.** The parent spec's chunking rules
(§3 — docs by heading at ~600 tokens, tables as one synopsis vector) are right and are the
natural next step, but they mean embedding the whole substrate and a chunk store. Recall is
self-contained, is what §9 actually names as phase 3, and delivers the visible win first.

**Out: the Datalevin vector index.** Measured in the parent spec: exact cosine over 100
candidates is 0.61 ms, over 1,000 is 2.9 ms, and the ANN index is reserved for ≥ ~2,000
candidates. loci has 84 facts. **Exact cosine is the only path that runs at this scale**, and
building an index we cannot justify would be the same mistake as phase 2 — which measurement
just deferred (LEAP is 0.26–0.71 ms today against a 0.1 ms indexed target).

**Out: phase 2's projection.** Nothing here needs it. Vectors live with the facts, in
memory's own log — memory is a parallel truth with its own log, per the parent spec §1.

## Decisions

- **Embedding is asynchronous and never in the write path.** `remember` returns exactly as it
  does today. A worker embeds afterwards. If the embedder is unreachable the fact is still
  recorded, still findable lexically, and a resumable backfill sweeps what is unembedded. The
  memory pane reports "N awaiting embedding" rather than pretending.
- **Every embedded fact records its `:model` and `:dim`.** A fact embedded by a different
  model is not compared against the current one — changing embedder becomes detectable
  instead of silently meaningless.
- **Lexical never regresses.** Today's keyword+entity+recency+strength scorer stays and keeps
  working with the embedder offline. Semantic is added *beside* it, and the two fuse.
- **Fusion by reciprocal rank**, then optional rerank of the top ~20, then decay and strength
  as a post-fusion multiplier — so "this keeps coming up" still counts for something.
  Rerank is a 50–100 ms round trip and must never sit in a keystroke path.
- **The result says how it was found.** Each hit carries which retriever matched, so the
  memory pane can distinguish "found by words" from "found by meaning". This is not
  decoration: it is how we will know whether the semantic half is earning its keep.
- **Scope is a traversal, not a column.** `{:filter {:space "space:semis"}}` means that
  notebook's cells, what it spawned transitively, and what was merged into it — the walk
  `nb/links` already does in 0.200 ms (measured). Facts join through `:source`, never a
  denormalized space id, because an object can live in several notebooks and can be spawned
  or merged after a fact was recorded. Ranking happens *within* the eligible set; an empty
  scope reports "nothing remembered from this notebook yet" and never falls back to global.

## 1. Configuration

Following the pattern `LOCI_LLM_ENDPOINT` already established:

| variable | falls back to | then to |
|---|---|---|
| `LOCI_EMBED_ENDPOINT` | `.embed-endpoint` | *unset — semantic recall is off* |
| `LOCI_EMBED_MODEL` | `.embed-model` | `embed-qwen3-0.6b` |
| `LOCI_EMBED_API_KEY` | `LOCI_LLM_API_KEY` | *unset — sent without an Authorization header* |
| `LOCI_RERANK_ENDPOINT` | `.rerank-endpoint` | *unset — rerank is skipped* |
| `LOCI_RERANK_MODEL` | `.rerank-model` | `rerank-bge-m3` |
| `LOCI_RERANK_API_KEY` | `LOCI_EMBED_API_KEY`, then `LOCI_LLM_API_KEY` | *unset — no header* |

**The embedder gets its own token, and this is the point rather than a nicety.** The obvious
deployment is a hosted chat model and a local embedder — DeepSeek plus `lizard10.local` —
and those are two different credentials against two different operators. Falling back to
`LOCI_LLM_API_KEY` keeps the single-provider case to one variable; rerank falls back to the
embed key because they are usually the same box.

An unset key means the request is sent with **no** `Authorization` header, not with an empty
one. A local llama.cpp started without `--api-key` rejects an empty bearer, so sending one
would turn "no key needed" into a 401 that reads like a wrong key.

**Both endpoints are optional and independently so**: with neither, loci behaves exactly as
it does today; with embed but no rerank, fusion runs and rerank is skipped. Degrading is
reported in `/api/memory`, never hidden.

## 2. Storage

A fact gains three fields:

```clojure
{:id … :fact "…" :entities […] :source … :ts … :strength …
 :vec [0.013 -0.221 …]     ; 1024 floats, absent until embedded
 :model "embed-qwen3-0.6b"
 :dim 1024}
```

`loci.memory`'s log is append-only EDN-lines, last-wins by `:id` — so embedding a fact is an
ordinary append of the same `:id` with `:vec` added, exactly like reinforcement today. **No
new store, no migration, no schema.** A 1024-float vector is ~12 KB as EDN text; at 84 facts
that is ~1 MB, which is fine, and the honest note is that this does not scale past a few
thousand facts. When it does, that is when the chunk store and the vector index earn their
place.

**Fact ids move off `(inc (count @!facts))`** onto a real sequence, per the parent spec — that
form is safe only while nothing is ever deleted, and merge (§4) deletes.

## 3. Retrieval

```
lexical (today's scorer)  ─┐
                           ├─ reciprocal-rank fusion ─→ top 20 ─→ rerank? ─→ ×(decay·strength) ─→ top k
semantic (exact cosine)   ─┘
```

`recall`'s `opts` grows additively — `{:k 8 :rerank? false :filter {:space …}}` — and every
existing caller keeps working on defaults. `all-facts` becomes a protocol function, because a
future disk-backed implementation has no `@(:!facts m)` to reach into.

Facts with no `:vec` still compete lexically. Facts embedded under a different `:model` are
excluded from the semantic half and counted in the "awaiting embedding" number, since a
stale vector is worse than none.

## 4. Dedup, and why the threshold must be measured

Auto-distill runs after every ask, research and draft, so the same fact is re-derived
constantly. Without dedup, recall returns five rewordings of one sentence and `:strength`
never accumulates — losing loci's only signal for "this keeps coming up".

**At write: unchanged.** Jaccard ≥ 0.6 over token sets, reinforce in place. Instant, works
offline.

**In the background: semantic merge.** Having embedded a fact, the worker compares it against
already-embedded facts and merges above a threshold. The older fact survives (it holds the
provenance), `:strength` sums, `:entities` union, `:ts` takes the newer, and the absorbed
fact's id and text are recorded on the survivor as `:merged-from` — so a merge is auditable
and reversible. Nothing is silently destroyed.

**The threshold was calibrated, not chosen.** Run 2026-08-07 against the real corpus: 80
unique facts from `data/memory.edn` (84 lines, last-wins by id), embedded with
`embed-qwen3-0.6b`, all 3,160 pairs scored. Vectors come back **L2-normalised (norm 1.0)**,
so cosine is a plain dot product.

| | cosine |
|---|---|
| min | 0.065 |
| median | 0.298 |
| mean | 0.337 |
| p90 | 0.543 |
| p95 | 0.612 |
| p99 | 0.741 |
| p99.5 | 0.794 |
| max | 0.954 |

**The classes overlap, and that decides the threshold.** Reading the top pairs by hand:
everything at **≥ 0.85 is a genuine restatement** — eight pairs, e.g. *"TSMC controls ~70% of
global foundry revenue and >90% of sub-7nm advanced chips, all produced in Taiwan"* against
the same sentence without the last clause (0.954), and *"Low-income countries are
disproportionately concentrated in tropical regions near the equator"* against *"Low-income
countries cluster near the equator"* (0.925).

Below that the band 0.82–0.84 is **mixed**, which is why no threshold separates the classes
perfectly:

- 0.838 and 0.832 are true duplicates (the latitude–income gradient naming; East Asia
  spanning four income groups) — these would be **missed**
- **0.829 is a false positive**: *"The orbital period of a planet increases with its distance
  from the Sun, following Kepler's Third Law"* against *"Kepler's Third Law states that the
  square of a planet's orbital period is proportional to the cube of its semi-major axis."*
  Related, but the second states the law and the first states an observation. Merging them
  would lose the law's formulation.

**Threshold: 0.85.** It merges 8 pairs of 3,160 with zero false merges in the sample, and
misses two true duplicates. That is the trade the spec asked for — a miss costs one redundant
fact, a wrong merge silently destroys a distinct one. Recalibrate whenever the embedding
model changes; the numbers above are meaningless for a different model, which is exactly why
`:model` is stored per fact.

**Merge ships behind the calibration, in its own step.** The distribution separates well
enough at 0.85 that it can proceed.

### 5.2 What the retrieval is actually worth, measured

Same run, comparing exact cosine against word-overlap on queries chosen to share no
vocabulary with the target fact:

| query | semantic top hit | lexical top hit |
|---|---|---|
| "why are poor nations hot" | *Low-income countries cluster near the equator* (0.660) | *Directed Self-Assembly (DSA)…* — matched on "are" |
| "where is chip manufacturing concentrated" | *TSMC's foundry concentration in Taiwan is the single most important chokepoint* (0.641) | a different TSMC fact, on 3 shared words |
| "which world is slowest to circle the sun" | *outer planets take much longer to orbit* (0.724) | **Mercury — the fastest planet**, on 4 shared words |

The third is the case for this whole phase: word overlap returned the semantically **opposite**
answer, confidently.

**Rerank earns its place, with one caveat.** Over four queries with known targets, reranking
the embedder's top 8 moved two from MISS to HIT (*"which planet takes longest to orbit"* →
Neptune's fact rather than the general "outer planets" one; *"where are the most advanced
chips made"* → the fact naming Taiwan), kept one HIT, and left one MISS. It reorders
aggressively — the embedder's #1 landed at position 2–4 every time — which is the point:
it picks the *specific* fact over the *topical* one.

The caveat: on the unusual phrasing *"which world is slowest to circle the sun"* it ranked
Mercury above Neptune, i.e. exactly wrong. It returns raw logits (0.687 down to −11.04), not
probabilities, so a score threshold would need its own calibration. This is why rerank is
applied to the *fused* candidate set rather than trusted alone, and never in the keystroke
path.

## 5. Failure modes

| what fails | what happens |
|---|---|
| embedder unreachable | commit unaffected; fact recorded; lexical recall unchanged; counted as awaiting |
| embedder returns wrong dimension | fact left unembedded, logged once with the dimension seen — never stored as if valid |
| reranker unreachable | fusion result returned unranked by it, marked as such |
| model changed | old-model facts drop out of the semantic half, appear in "awaiting"; backfill re-embeds |
| worker dies mid-sweep | backfill is resumable — it selects facts lacking `:vec` for the current model |

## 6. Testing

- `remember` commits and returns without the embedder being reachable at all — asserted with
  the endpoint pointed at a dead port, and it must not slow down measurably
- a fact with no `:vec` is still returned by lexical recall
- cosine is correct against hand-computed vectors, including the orthogonal and identical cases
- fusion: a fact that only the semantic side finds appears; a fact that only lexical finds
  still appears; neither retriever can be starved by the other
- a scoped recall returns only facts whose `:source` is in the notebook's traversal, and an
  empty scope says so rather than returning global results
- a wrong-dimension response leaves the fact unembedded and does not throw
- a fact embedded under model A is not compared against a query embedded under model B
- **the merge tests use the calibrated threshold**, and one asserts that two facts known to be
  distinct are *not* merged at it

The quality of what the embedder considers similar is not testable and is not tested. What is
tested is that nothing blocks on it, nothing is lost when it is absent, and nothing is merged
that the calibration does not justify.

## 7. What this needs from you

Calibration and the end-to-end checks need a reachable embedder. `lizard10.local:8080` is up
and returns 401 — it needs its bearer token. Put it in `loci.env` or a gitignored
`.llm-key`-style file; it must not be pasted into a plan, a commit, or a subagent prompt.

## Out of scope

- Semantic LEAP over substrate objects (chunking, the chunk store) — the next step
- The Datalevin vector index, and phase 2's projection
- Re-ranking inside the LEAP keystroke path
- Embedding anything other than memory facts
- Multi-model or multi-dimension coexistence beyond detecting and excluding
