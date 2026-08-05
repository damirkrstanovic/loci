# loci — Datalevin substrate + hybrid recall: design

Date: 2026-08-05
Status: **phase 1 planned, built and merge-ready** (plan:
`docs/superpowers/plans/2026-08-05-datalevin-store-phase1.md`; code: `src/loci/dlv.clj`,
`src/loci/migrate.clj`). Phases 2 and 3 remain as designed below. Sections corrected
against what was actually built are marked "as built".

## Goal

Move the substrate off the hand-rolled EDN-lines log onto **Datalevin 1.0.0**, and give
loci the retrieval it has never had: **lexical, semantic and structural indices** over
everything it holds.

Four drivers, all of them stated:

1. **Durability.** On 2026-08-05 a research table arrived with a column named
   `Key Proponent(s)`. As a keyword it printed happily and could not be read back, so
   `load-events` treated it as EOF and silently discarded 31 events — six deep-dive
   notebooks, derived tables, an applet and a whole flow run. Two further lines (46, 70)
   *parsed* but read back as shifted garbage. The guard added that day makes the class
   detectable; moving to a store that never round-trips through `pr-str` makes it
   **impossible**.
2. **Query power.** LEAP substring-scans. Lineage and links are recomputed in Clojure on
   every request. Recall is keyword overlap.
3. **Performance.** `state` re-folds the entire log on every API request.
4. **Thesis fidelity.** The README always described the in-process log as a placeholder.

## Decisions (settled in brainstorming, with evidence)

- **Engine: Datalevin 1.0.0**, embedded, zero-setup. Spiked on Java 26.0.2: KV, datalog,
  full-text and vector index all work. One JVM flag required
  (`--enable-native-access=ALL-UNNAMED`).
- **Events stay the source of truth.** `undo!`, `as-of` and `frozen-at` keep their exact
  current meaning. Everything else — state, projection, indices — is derived and rebuildable.
- **Rejected: Datomic Local.** It is Apache-licensed and genuinely embeddable, but it
  limits strings to **4096 characters** — 11 of loci's 60 objects exceed that, the largest
  at 134 KB — forcing a separate blob store. It also offers neither full-text nor vector
  indices, so all three of the required indices would have been bolted on anyway, leaving
  it providing only `as-of`, which loci already has and has live-fire tested.
- **Rejected: XTDB.** v2 runs in-process but is server-shaped (Arrow/Netty, three JVM
  flags, in-process documented as a testing convenience); v1 is the older line. Under
  events-as-truth its bitemporality would sit idle.
- **Rejected: SQLite + DataScript.** A sound pairing, but Datalevin covers the same ground
  in one dependency and adds vectors natively.
- **Embeddings come from the user's local llama server** (`lizard10.local:8080`):
  `embed-qwen3-0.6b`, 1024-dim, OpenAI-compatible; reranking via `rerank-bge-m3` at
  `/v1/rerank`. DeepSeek has **no** embeddings endpoint (verified: `POST /embeddings` → 404).
- **Near-duplicate detection: Jaccard at write, semantic merge in the background** (§5.1).
  Embedding is asynchronous, so an incoming fact has no vector at the moment `remember`
  runs — cosine cannot be a write-time check without breaking offline commits. The
  semantic pass therefore belongs to the embed worker, and lands in **phase 3**; phase 1
  ships today's behaviour unchanged.

## Measured evidence

Benchmarks against the current EDN store, on this machine (Java 26.0.2), events averaging
~490 bytes unless noted. Numbers are steady-state after warmup.

| operation | EDN today | Datalevin |
|---|---|---|
| commit, per event (durable) | 0.231 ms | **0.030 ms** |
| boot, 10k events | 353 ms | 227 ms |
| **state, per API request** | **10.6 ms** | ~0 (atom) |
| datalog lineage query | — | **0.1 ms** |
| full-text query | — | **0.1 ms** |
| vector search, top-10, 1024-dim | — | 0.78 ms |
| vector indexing | — | 1.84 ms/vector |

At 10,000,000 synthetic events (87 bytes each, 871 MB, 1M objects):

| | |
|---|---|
| write, durable, batched | 23.8 s (0.0024 ms/event) |
| datalog lineage @ 1M objects | **0.1 ms** |
| full-text @ 1M docs | **0.1 ms** |
| boot from a snapshot + tail (measured, but not built — see §1) | 2.53 s (heap 806 MB) |
| as-of, full-state fold + snapshot | 642 ms |
| **as-of, lazy per-object** | **0.2 ms — one object; 4.2 ms — 18 panels** |

Two findings shaped the design and would not have surfaced without measuring:

- **Datalog and full-text are flat at 0.1 ms from 5k to 1M objects.** The query layer does
  not care about scale.
- **Full-state `as-of` was the bottleneck, not the storage.** Rebuilding a million objects
  to render eighteen panels is the naive part. Indexing which events touched which object
  makes the scrubber flat in total history size, and removes the need for periodic
  snapshots entirely (which had ballooned disk from 871 MB to 3 GB).

Caveat on the 10M figures: those events averaged 87 bytes against a real average of ~490,
so a real log of that size would be roughly 5× larger. The shape of the findings holds.

## 1. Storage layout

One LMDB environment under `data/`, several dbis. Substrate and memory never share a
transaction — the record/recall separation is a rule about writes, not just about files.

| dbi | kind | contents |
|---|---|---|
| `events` | KV, key `:long` | **the truth.** event index → event map, verbatim |
| `counts` | KV, key `:long` | event index → `{:objects n :kinds {kind n}}`, so the ⏱ header is an O(1) read. An honest histogram: layer 1 counts kinds, consumers decide which ones they call "objects" |
| `touched` | list, key `:string` | object id → sorted event indices that touched it |
| ~~`head`~~ | — | **not built.** See "Boot" below |
| `objects` | datalog | the spine: `:object/id · kind · title · from · via · spawned-by · merged-from · cells` |
| `chunks` | datalog | `:chunk/id · object · text · model · dim · embedded?` |
| `facts` | datalog | `:fact/id · source · entities · ts · strength` (layer 3) |
| — | vector index | 1024-dim, cosine, keyed by chunk id |
| — | FTS | over titles, prose cells, doc bodies, fact text |

**Truth vs derived.** Only `events` is truth. `touched`, the projection, FTS and vectors
are all rebuildable from it — a corrupted or stale index is a rebuild, never a data loss.
Memory facts are a *parallel* truth with their own log; undo never touches them.

**Boot: fold the log, no snapshot.** Measured fold rate is ~0.75 µs/event, so boot costs
~2 ms at today's 80 events, 227 ms at 10k, and 3.75 s at 1M. A head snapshot buys nothing
below roughly a million events and costs a second thing that can go stale, disagree with
the log, and be got wrong in `undo!`. Build it when boot is actually felt, and derive the
interval then: with a 200 ms budget the tail can run to ~265,000 events. An earlier draft
of this spec said "every 10,000 commits", which was a guess 26× tighter than the
measurement supports — recorded here so the mistake isn't repeated.

Datalevin serializes EDN natively (nippy). There is no `pr-str`/`read-string` anywhere in
the write path.

## 2. The Store seam

`loci.substrate/Store` keeps its seven functions; a new `DatalevinStore` record implements
them, and `content.clj:500` swaps `persistent-store` → `datalevin-store`. `FrozenStore`
wraps any `Store`, so `?at=N` and the ⏱ scrubber need no changes. No caller in
`server.clj` changes.

- **`commit!`** — one LMDB transaction: append the event **verbatim** to `events`, write
  that event index's census to `counts`, and append the index to `touched` for every
  object the event names; then update the state atom. The counts are a *separate dbi
  keyed by event index*, not fields stamped onto the event — the event is stored exactly
  as it was committed, so the log is still the truth and nothing derived is mixed into
  it (see the table in §1). Projection and embed jobs are phases 2 and 3.
- **`state` / `objects` / `object`** — read the in-memory atom. This is where the
  per-request 10.6 ms goes to zero. Note honestly: that win comes from the atom, which
  the EDN store could also have had; Datalevin's contribution is the indices and the
  write path.
- **`as-of` / `frozen-at`** — **as built, neither is lazy.** `as-of` folds a prefix of the
  in-RAM log (`dlv.clj`), and `frozen-at` is untouched: it still takes a prefix of
  `history` and materializes it. The lazy reader exists and is tested — `dlv/object-at`
  reconstructs ONE object by folding only its own `touched` indices, bounded at n by
  `list-range` — but nothing is wired to it yet, deliberately. Wiring it means changing
  what `store-at` hands the `?at=` readers, and a `FrozenStore` has no back-pointer to
  the store it froze, so the reader that would call `object-at` cannot reach the index
  from it. That is phase 2's work, and it is a change to the seam, not to this record.
  Counts for the scrubber header are the O(1) read today, via `dlv/counts-at`.
- **`undo!`** — delete the last event, reverse its effect in the atom, projection and
  `touched`. Still a pop; unchanged semantics.
- **`history`** — as built, a read of the in-RAM log vector, which is the whole point of
  keeping log and state in one atom: `history` and `state` can never disagree because
  they are one value. The durable range read happens once, at boot (`read-log`), and
  again on `reload!`.

**Ceiling, accepted and documented:** the present-state atom holds every object in heap
(~800 MB at 1M objects). That is a limit on *objects*, not events, and it is far beyond
loci's scale (60 objects today). Past it, reads would move to the disk-backed projection.

## 3. Retrieval

**Chunking.** Titles and notebook intents: one vector each. Doc and findings bodies:
chunked by markdown heading, ~600 tokens. Prose cells and memory facts: one each. Tables:
**one synopsis vector** (title + column names + sample values) — a thousand row-vectors
would be a thousand useless vectors; what users search for is which table holds the data.

**Embedding is asynchronous and never in the write path.** The event lands durably, then a
background worker embeds. If `lizard10.local` is unreachable the commit still succeeds and
the object is immediately findable lexically; a resumable backfill sweeps unembedded
chunks. Each chunk stores its model and dimension, so changing embedder is detectable
rather than silently meaningless. The memory pane surfaces "N awaiting embedding".

**Fusion.** Lexical (FTS) and semantic (vectors) run in parallel and merge by reciprocal
rank; the top ~20 go to `rerank-bge-m3`; decay and strength apply as a post-fusion
multiplier so reinforcement still counts. Datalog is not a scorer — it is the filter.

**LEAP keeps incremental find.** Per keystroke → FTS only (0.1 ms, feels identical to
today but indexed). On pause (~250 ms) or Enter → semantic + rerank, and results deepen.
A rerank round-trip is 50–100 ms and must never sit in the keystroke path.

## 4. Deterministic filtering

> **Datalog decides what is eligible. Vectors decide what is relevant. Rerank decides the
> order. The filter is never approximate.**

Scope in loci is a *traversal*, not a column: "recall from this notebook" means its cells,
the notebooks it spawned (transitively), and anything merged into it — the walk
`nb/links` already performs. Facts join to notebooks through `:fact/source`, never through
a denormalized space id, because an object can live in several notebooks and can be
spawned or merged after a fact was recorded.

Ranking happens *within* the eligible set, never before it. Fetching a global top-k and
post-filtering routinely returns zero — the notebook's facts were never in the global
top-k. Measured: exact cosine over 100 candidates is **0.61 ms**, over 1,000 is **2.9 ms**,
so at loci's scale the exact path is the only one that runs; the ANN index is reserved for
large global queries (≥ ~2,000 candidates).

An empty scope reports "nothing remembered from this notebook yet". It never falls back to
global results.

## 5. Layer 3 — the `Recall` protocol

The interface holds. `FileMemory` is replaced by a Datalevin-backed record; the four call
sites in `server.clj` (`remembered-context`, `leap-payload`, the post-flow distill hook,
`/api/memory`) do not change. Three amendments are required:

1. **`all-facts` becomes a protocol function.** Today `loci.memory/all-facts` reaches into
   `@(:!facts m)` — a disk-backed implementation has no such atom.
2. **The return shape is pinned explicitly.** The docstring promises
   `{:fact :score :meta}`; the implementation returns the flat fact record with `:score`
   assoc'd, and the UI depends on the flat keys. Hybrid retrieval additionally returns
   which retriever matched and the rerank score, so the memory pane can distinguish
   "found by words" from "found by meaning".
3. **`opts` grows additively** — `{:k 8 :rerank? false :filter {:space "space:semis"}}`.
   Existing callers keep working on defaults.

Behind it: `recall` becomes hybrid. Fact ids move off `(inc (count @!facts))` — safe only
while nothing is ever deleted — onto a real sequence.

### 5.1 Dedup: Jaccard at write, semantic merge in the background

This matters because auto-distill runs after every ask, research and draft, so the same
fact is re-derived constantly. Without dedup, recall returns five rewordings of one
sentence and `:strength` never accumulates — losing loci's only signal for "this keeps
coming up".

**At write:** unchanged. Jaccard ≥ 0.6 over token sets; on a hit, reinforce in place
(bump `:strength`, refresh `:ts`, union `:entities`, keep the id). Instant, and it works
with the embedder offline.

**In the background:** having just embedded a new fact, the worker compares it against
already-embedded facts (exact cosine — memory is small, and 100 candidates cost 0.61 ms)
and merges above threshold. Merge rules:

- the **older** fact survives — it holds the provenance and anything already references it
- `:strength` sums; `:entities` union; `:ts` takes the newer
- the merged fact's id and text are recorded on the survivor as `:merged-from`, so a merge
  is auditable and reversible. Nothing is silently destroyed — consistent with "revisable,
  never undone".

**Threshold must be calibrated, not guessed.** Measured during brainstorming:
`embed-qwen3-0.6b` scores **0.471 cosine between two entirely unrelated sentences**, so
its similarity scale is compressed and a borrowed default like 0.85 is meaningless here.
Calibrate against the existing 61 facts, and err high — a wrong merge folds two distinct
facts together, and while `:merged-from` makes that recoverable, it still degrades recall
until someone notices.

## 6. Migration

A replay, not a conversion. Events 1..N from `data/substrate.edn` are written to `events`
in order, preserving indices and timestamps, so the scrubber still travels the same
moments with the same labels. `touched`, the projection and FTS build in the same pass;
embeddings backfill afterwards. Memory's 61 facts import the same way.

Correctness check, total and cheap: **`(= (materialize edn-events) (materialize dlv-events))`**.
The `.edn` files remain on disk as the rollback.

## 7. Failure modes

| | |
|---|---|
| **LMDB map size** | ~~Needs a generous default and an honest error.~~ **Neither was built, on evidence.** LMDB does preallocate a maximum and `MAP_FULL` was hit during benchmarking, but Datalevin already catches `Util$MapFullException` and grows the map itself (`binding/cpp.clj`), and it honours `:mapsize` **only when the directory does not yet exist** — so a default passed at `open-kv` is a knob that silently stops applying after the first boot, and an error handler for a case the library retries is dead code. Both were written and then deleted. `open-env!` passes no `:mapsize` and says why. |
| embedder offline | commits succeed, lexical works, semantic degrades, backfill drains later, UI says so |
| crash mid-write | LMDB is ACID — the 2026-08-05 bug class becomes structurally impossible |
| concurrent writers | LMDB serializes writes, so no corruption — but loci's own read-modify-write races (`nb/append-cell-event` losing a cell append) are **unchanged**, and must not be claimed as fixed |
| embedding model change | detected via the stamped model name → resumable re-embed |
| missing `--enable-native-access` | warnings today, hard failure on a future JDK → belongs in `deps.edn` |
| **sci version conflict** | Datalevin 1.0.0 pulls sci 0.13.53; loci pins 0.8.43 for the `compute-clj!` sandbox. Must be verified against the existing compute tests, not assumed. |

## 8. Testing

- **Parameterize `substrate_test.clj` over both implementations**, so behavioural
  equivalence between the EDN and Datalevin stores is proven rather than asserted.
- Lazy `as-of` must equal a full prefix fold, per object, over the real 80-event log.
- Stamped counts must equal a real count at every event index.
- `undo!` must leave the atom, projection and `touched` mutually consistent.
- Scoped recall must never return an out-of-scope fact; an empty scope must report empty.
- A stubbed-failing embedder must still allow commits, and the backfill must be resumable.
- Migration: import the real log into a temp environment and assert state equality.

## 9. Phasing

Three independently shippable, independently revertible steps:

1. **`DatalevinStore` behind the existing protocol** — KV log, state atom, `touched`,
   stamped counts, migration, parity tests. No user-visible change except speed.
2. **Projection + FTS** — LEAP stops substring-scanning; lineage and links become queries.
3. **Embeddings + vectors + rerank** — `Recall` reimplemented, scoped filtering, dedup upgrade.

Phase 1 is worth shipping alone.

## Out of scope

- Rows-as-entities (querying *inside* tables with datalog)
- Moving reads off the state atom onto the projection (only needed past ~1M objects)
- Fixing the `append-cell-event` write race — a real bug, but a separate one
- Multi-user, client/server Datalevin, or any deployment beyond single-process
- Re-ranking inside the LEAP keystroke path
- Blob/GC policy for superseded chunks
- Un-merging a wrongly merged fact through the UI (`:merged-from` records it; no affordance)
