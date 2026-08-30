# loci roadmap

> **Agent-produced best-effort draft**
>
> This roadmap began as a proposal for user review.
> The user can continue to change the order, scope, or content.
> No item below is a commitment.

## Current baseline

Treat the code on `master` as the current state.

Some old status lines said "nothing built" after later work shipped.
The cleanup below corrected them against the code and merged history.

- Record and recall use the Datalevin substrate.
- Notebooks support references, lineage, undo, and time travel.
- Recall supports lexical search, semantic search, entities, durable facts, merge, and unmerge.
- Tables use DuckLake and immutable handles.
- Table history supports per-table reports and manual reclaim.
- File sources support CSV, JSON, and Parquet.
- Remote sources support HTTP, HTTPS, and S3 in materialise mode.
- The ClojureScript grid supports paging, sorting, and column selection.
- Table questions run in DuckDB through HoneySQL data.
- Agent code can use `q`, `q-all`, `q-reduce`, and `keep!`.
- Reports can refer to stored tables instead of copied row samples.

Evidence:

- [`src/loci/dlv.clj`](src/loci/dlv.clj)
- [`src/loci/memory.clj`](src/loci/memory.clj)
- [`src/loci/entities.clj`](src/loci/entities.clj)
- [`src/loci/lake.clj`](src/loci/lake.clj)
- [`src/loci/lakegc.clj`](src/loci/lakegc.clj)
- [`src/loci/source.clj`](src/loci/source.clj)
- [`src/loci/tablestore.clj`](src/loci/tablestore.clj)
- [`src/loci/sandbox.clj`](src/loci/sandbox.clj)
- [`src-cljs/loci/grid.cljs`](src-cljs/loci/grid.cljs)

### Documentation status cleanup

Completed 2026-08-30. These documents described an earlier point in the work and now carry
current-state corrections:

- The paged grid design said "nothing built" after the grid reached `master`.
- The query design said "nothing built" after query pushdown reached `master`.
- The big-content design said "nothing built" after its table and lake reclaim work shipped.
- The corpus and durable-fact designs said no plan existed after their code shipped.
- The README said semantic merge was unbuilt after `merge-similar!` and `unmerge!` shipped.
- The original record/recall and LEAP filter specs had pre-build status lines.

Evidence:

- [`docs/superpowers/specs/2026-08-25-paged-table-grid-design.md`](docs/superpowers/specs/2026-08-25-paged-table-grid-design.md)
- [`docs/superpowers/specs/2026-08-27-let-the-model-write-a-query-design.md`](docs/superpowers/specs/2026-08-27-let-the-model-write-a-query-design.md)
- [`docs/superpowers/specs/2026-08-16-big-content-design.md`](docs/superpowers/specs/2026-08-16-big-content-design.md)
- [`docs/superpowers/specs/2026-08-10-a-corpus-worth-browsing-design.md`](docs/superpowers/specs/2026-08-10-a-corpus-worth-browsing-design.md)
- [`docs/superpowers/specs/2026-08-10-facts-that-do-not-decay-design.md`](docs/superpowers/specs/2026-08-10-facts-that-do-not-decay-design.md)
- [`README.md`](README.md)
- [`docs/superpowers/specs/2026-07-22-record-recall-notebooks-design.md`](docs/superpowers/specs/2026-07-22-record-recall-notebooks-design.md)
- [`docs/superpowers/specs/2026-08-06-leap-filter-and-recency-design.md`](docs/superpowers/specs/2026-08-06-leap-filter-and-recency-design.md)

## Next

No item is selected yet. Promote a candidate from **Later** after review.

## Completed

### Correct stale documentation

Completed 2026-08-30. The cleanup preserved the design history and corrected stale status
lines and current-state claims in the README and design notes.

## Later

> **Unordered candidates**

### SQL database sources

Add SQL databases through the existing source model and DuckDB connector path.

Write a separate spec first.
Reuse the existing credential decision where it applies.

Evidence:

- [`docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md`](docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md)
- [`docs/superpowers/specs/2026-08-19-external-sources-design.md`](docs/superpowers/specs/2026-08-19-external-sources-design.md)

### Import documents

Let loci import PDF, DOCX, Markdown, HTML, and plain-text files as `:doc` objects.

The retrieval path already supports document text.
The missing path converts file bytes into text and commits a `:doc`.

This work was deferred while exact table queries took priority.
That higher-priority query work now exists on `master`.

Before implementation:

- Write a spec from the deferred note.
- Decide where long extracted text lives.
- Decide whether loci keeps the source file.
- Define the result of an empty or failed extraction.
- Compare extractor dependencies with a small, approved measurement.

Evidence:

- [`docs/notes/2026-08-26-document-import-deferred.md`](docs/notes/2026-08-26-document-import-deferred.md)
- [`src/loci/chunks.clj`](src/loci/chunks.clj)
- [`src/loci/embed.clj`](src/loci/embed.clj)
- [`src/loci/files.clj`](src/loci/files.clj)

### Images and content-addressed blobs

Finish the unbuilt media half of the big-content design.

The table half shipped.
No `ContentStore`, image object, video object, or media route exists.

Start with images.
Keep video as a separate design unless the user selects it.

Evidence:

- [`docs/superpowers/specs/2026-08-16-big-content-design.md`](docs/superpowers/specs/2026-08-16-big-content-design.md)
- [`src/loci/tablestore.clj`](src/loci/tablestore.clj)
- [`src/loci/lakegc.clj`](src/loci/lakegc.clj)

### Other source types

Consider REST APIs only after a user need defines the source contract.

The source framework leaves room for them.
No REST source spec exists.

## Research and validation gates

These items are not ready for implementation.

### Web retrieval

Tavily still ships.
The DonSeTch note reports a better result on one corpus.
It does not make an adoption decision.

First decide whether loci should fetch pages or keep buying extracts.
Then approve any new measurement.

Evidence:

- [`docs/notes/2026-08-26-web-search-backend-measured.md`](docs/notes/2026-08-26-web-search-backend-measured.md)
- [`docs/notes/2026-08-27-donsetch-measured.md`](docs/notes/2026-08-27-donsetch-measured.md)

### Memory conflict and supersession

Do not plan this work from the current sketch.

First measure near-but-not-merged fact pairs with embeddings enabled.
Then review the pairs by hand.
Stop if a real corpus contains almost no conflicts.

Evidence:

- [`docs/superpowers/specs/2026-08-08-memory-conflict-design.md`](docs/superpowers/specs/2026-08-08-memory-conflict-design.md)

### Forkable agent flows

Do not plan branches before real flow use supplies evidence.

First record whether users reach gates and decline them.
The existing note has no decline data.

Evidence:

- [`docs/superpowers/specs/2026-08-08-forkable-flows-design.md`](docs/superpowers/specs/2026-08-08-forkable-flows-design.md)

### Model-call cache

Treat the cache as a separate candidate from flow branches.

It could make repeated flow work cheaper and more repeatable.
First measure the cache hit rate on a real flow rerun.

Evidence:

- [`docs/superpowers/specs/2026-08-08-forkable-flows-design.md`](docs/superpowers/specs/2026-08-08-forkable-flows-design.md)

### Short readable IDs

This is a readability and simplification idea.
Nothing is currently broken.

Hold it until the user approves an ID shape and coexistence rule.

Evidence:

- [`docs/superpowers/specs/2026-08-15-readable-ids-design.md`](docs/superpowers/specs/2026-08-15-readable-ids-design.md)

## Not committed

This draft does not commit loci to:

- The proposed order.
- A delivery date.
- A release boundary.
- A dependency choice.
- A document extractor.
- A database connector.
- A web retrieval change.
- Images or video.
- Flow branches.
- New ID formats.
- Any benchmark or measurement campaign.

This remains a best-effort roadmap. The user owns its priorities and can reorder it at any time.
