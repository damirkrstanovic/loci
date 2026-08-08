# loci phase 3b — LEAP finds by meaning

Date: 2026-08-08
Status: proposed
Parent: `2026-08-05-datalevin-substrate-design.md` §3; builds on `2026-08-07-semantic-recall-design.md`

## Goal

Today "find by meaning" works on the ~80 facts the agent distilled and on **nothing you
wrote**. Your notebooks, findings documents and tables are found by substring only. Typing
*"where are chips made"* finds nothing unless those words appear literally.

This extends the embedding layer from memory facts to the substrate itself.

## Measured, so the design is not speculative

The live corpus: 35 notebooks, 22 doc bodies (40,846 chars, median 2,027, max 5,078),
22 tables (2,584 rows), 7 prose cells, 5 reports, 2 metrics.

Chunked as below that is **~94 chunks** — the same order as the 80 memory facts. So:

- **No vector index.** Exact cosine over ~100 candidates is 0.61 ms (measured). The ANN index
  is reserved for ≥ ~2,000 candidates, and this is nowhere near it. Same conclusion as phase
  3a, for the same reason.
- One-off embedding cost ~94 × 41 ms ≈ **4 seconds**; storage ~1.1 MB at 1024 dims, ~2.8 MB at
  2560.

## Decisions

- **LEAP is not scoped.** Recall was scoped to a notebook's trunk because the agent should
  work from what this line of enquiry learned. **LEAP is the opposite act** — it is how you
  find anything anywhere, and the tags/overview spec says finding a notebook is explicitly not
  the problem tags solve. Applying lineage scope here would break the one gesture that must
  always reach everything.
- **The keystroke path does not change.** Per keystroke: today's substring scan, 0.26–0.71 ms.
  On pause (~250 ms) or Enter: a second request that adds semantic results. A 20–50 ms
  embedding round trip must never sit between a key and a redraw — the same rule that made
  `:semantic?` default to false in `recall`.
- **Results say how they were found.** Semantic hits arrive in their own group, so a result
  that matched no word you typed is visibly a different kind of answer rather than a mystery.
- **Chunks are derived, never truth.** They live in a sidecar, rebuildable from the substrate
  at any time. **They are not substrate events** — putting them in the log would make derived
  data undoable, drag them into time travel, and let a stale vector answer a question about
  the past. `undo` must not have to know embeddings exist.
- **Staleness is detected, not assumed.** Every chunk stores a hash of the text it was built
  from plus its model. A sweep re-embeds anything whose hash or model no longer matches. Edit
  a document and its chunks go stale and are refreshed; no separate invalidation protocol.
- **Embedding never blocks a write**, exactly as in phase 3a. The same worker does both.
- **With no embedder configured, LEAP is exactly what it is today.** Non-negotiable.

## 1. What gets a vector

| what | chunking | count today |
|---|---|---|
| notebook title + intent | one vector | 35 |
| doc / report bodies | by markdown heading, ~600 tokens, never splitting mid-heading | ~28 |
| prose cells | one each | 7 |
| tables | **one synopsis**: title + column names + a few sample values | 22 |
| metrics | one vector | 2 |

**Tables get one vector, not one per row.** 2,584 row-vectors would be 2,584 useless vectors:
nobody searches for a row, they search for *which table holds the data*. This is the parent
spec's rule and the measurement supports it — 22 synopses against 2,584 rows.

`:viewspec`, `:applet`, `:fn` and the tag palette are not embedded. They are machinery, and
LEAP already hides them from its object list.

## 2. Storage

`data/chunks.edn` — append-only EDN lines, last-wins by `:id`, the same shape `loci.memory`
already uses and for the same reasons: no new mechanism, no migration, and a corrupt or stale
file is a rebuild rather than a loss.

```clojure
{:id "doc:findings-4#2" :object "doc:findings-4" :kind :doc
 :text "…the chunk text…" :hash "sha256:…" :vec [...] :model "embed-qwen3-0.6b" :dim 1024}
```

`:hash` is over the chunk text. `:object` is what a hit points at, so a result opens the
document, not the fragment.

**Deleting `data/chunks.edn` must be safe** — the next sweep rebuilds it. There is a test for
that, because "derived" is a claim that rots unless something checks it.

## 3. Retrieval

`GET /api/leap?q=…` is unchanged and stays lexical — the keystroke path.

`GET /api/leap?q=…&deep=1` additionally embeds the query, scores exact cosine over all chunks,
and returns the top few in a **`by meaning`** group alongside the existing groups. The shell
fires it on a ~250 ms pause and on Enter, and drops it if the query has moved on.

A chunk hit already covered by a lexical hit on the same object is dropped, so the new group
shows what substring search could not find rather than repeating it.

Reranking is **not** applied here. It is a 50–100 ms round trip on top of the embedding, LEAP
is a navigation gesture rather than a research answer, and the parent spec puts rerank outside
the keystroke path for exactly this reason.

## 4. Failure modes

| what fails | what happens |
|---|---|
| no embed endpoint | `deep=1` returns no semantic group; LEAP is today's LEAP |
| embedder unreachable | same, plus the response says it is degraded |
| chunks not built yet | the group is empty and reports how many chunks await |
| a document edited | its chunks' hashes no longer match; the next sweep re-embeds them |
| model changed | every chunk's `:model` mismatches; the sweep re-embeds all of them |
| `data/chunks.edn` deleted | rebuilt from the substrate on the next sweep |

## 5. Testing

- chunking is deterministic and total: every doc body produces at least one chunk, headings
  are not split mid-section, and re-chunking unchanged text yields identical ids and hashes
- a table's synopsis contains its title and column names and **not** 2,584 rows
- `deep=1` with no embedder configured returns exactly what `deep` absent returns
- a semantic hit that shares no word with the query is returned, and points at its **object**
- a hit already found lexically is not duplicated into the semantic group
- editing a document's text makes exactly its chunks pending, and nothing else
- deleting the chunks file and sweeping restores byte-equivalent chunk ids and hashes
- the keystroke path makes **zero** embedding requests — asserted on a stubbed embedder, the
  way `recall`'s lexical-only test already is
- **browser:** typing into LEAP shows lexical results immediately; the semantic group appears
  after the pause without the earlier results flickering or being replaced

## Out of scope

- Reranking LEAP results
- Row-level table vectors
- A vector index (revisit past ~2,000 chunks)
- Scoping LEAP by notebook, tag or lineage
- Embedding viewspecs, applets or functions
- Datalevin phase 2's projection and FTS — orthogonal, still deferred on measurement
