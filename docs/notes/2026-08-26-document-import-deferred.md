# Documents in loci: the half that is missing, and the half that is already built

Date: 2026-08-26
Status: **a note, not a spec.** Nothing was built and nothing was measured. This is a code
reading, written on the day the gap was found and deferred in the same conversation — the
`query_table` work came first. It exists so that whoever picks this up starts from what the
source already says, and does not re-derive it.

---

## The one-paragraph version

loci takes in tables and cannot take in documents. That is one gap, not two: the retrieval
half of a RAG system is already built and running. `:doc` objects exist, `loci.chunks` cuts
their text into chunks and hashes each one, `loci.embed` gives the chunks vectors, LEAP
searches those vectors, and `distill!` turns a document into memory facts. What is missing is
the step before all of that. Nothing turns a PDF, a Word file, a Markdown file or a web page
into a `:doc`. `/api/source/upload` lands the bytes and `loci.sourcespec` then offers three
readers only — `read_csv`, `read_parquet` and `read_json_auto`. A file that is not a table
has nowhere to go.

---

## What already exists

**`:doc` is a real object kind, and four writers make one today.**

| Writer | What it writes |
|---|---|
| `loci.corpus` | fourteen quoted prose extracts, from `corpus/MANIFEST.edn` |
| `loci.conversation` | one Gemini export, as notebooks, tables and documents |
| `server/keep-note!` | a note you typed |
| `server/delegate!` and `server/research!` | a Draft and a Findings note the agent wrote |

Each commits `{:id … :kind :doc :title … :value <string>}` to the log. So the substrate
already holds documents. It just has no way to be *given* one.

**The retrieval half is built and does not care where the text came from.**

- `chunks-of` is a pure function of one object. No store, no file, no embedder, no clock.
- Chunks are derived and are never substrate events, so they rebuild from the objects.
- Staleness is by content hash. An edited document re-chunks itself and nothing else.
- `sweep!` embeds what has no vector, and is resumable.
- LEAP already searches those vectors, and `loci.embed` holds the reranker.

Nothing in that list needs a change to serve an imported document. `chunks-of` sees an object
with a string `:value` and does its work.

## What is missing

One step: **bytes on disk → text → a `:doc` event.**

`/api/source/upload` already does the hard part of the first half. It streams the body to
disk as bytes and never as a String, content-addressed under its hash in
`<LOCI_DATA>/files/`. That route is format-blind and would need no change.

The branch after it is where a document has no path. `/api/source/connect` calls
`loci.source/describe`, which asks DuckDB to `DESCRIBE SELECT * FROM read_csv('<file>')`.
`loci.sourcespec` maps a format to one of three readers. A PDF matches none of them, and the
connect fails with a DuckDB error about a file it cannot parse.

## Where it would attach

The seam is `loci.sourcespec`. It is already the namespace that answers "what IS this
source", as two pure functions, and it already holds the format table:

```clojure
{:csv "read_csv" :parquet "read_parquet" :json "read_json_auto"}
```

A document format is a fourth answer to the same question, but it does not resolve to a SQL
relation. It resolves to text. So the honest shape is a second branch beside the relation
one, not a fourth entry in that map — a source is either a relation DuckDB reads, or a
document loci extracts.

Everything downstream is then unchanged: commit a `:doc`, append a cell, let `sweep!` embed
it, let `distill!` take facts from it if that is wanted.

## What it would cost

No text extractor is on the classpath. `deps.edn` today carries clojure, http-kit,
data.json, transit-clj, sci, datalevin and duckdb_jdbc. Nothing reads a PDF.

The usual answers, none of them measured here and none of them tried:

| Format | Library | Note |
|---|---|---|
| PDF | Apache PDFBox | text only; a scanned page needs OCR and is a separate question |
| DOCX | Apache POI | POI is large |
| HTML | jsoup, or Readability4J | Readability4J is named in the 2026-08-26 search note, for the same job |
| Markdown, plain text | none | read the bytes as UTF-8 |

Apache Tika covers all of them behind one call and is the obvious first thought. It is also
very large. **Measure the jar before treating its size as a reason, and write the number
down** — see `CLAUDE.md`, tell 4. Nobody has complained about the uberjar.

## Open questions — do not answer them here

1. **Where does a long document's text live?** A `:doc`'s text is in the event. The
   2026-08-16 big-content spec says that is deliberate for docs and notes, and puts images,
   video and blobs in a content store instead. A 300-page PDF sits between the two cases,
   and the blob store that spec calls "the gap" was never built.
2. **Does the original file stay?** `loci.files` already keeps content-addressed bytes and a
   manifest of original names. Keeping the PDF beside the extracted text costs a little disk
   and buys re-extraction with a better parser later.
3. **Is a web page a document or a source?** It has a URL, so it can be refreshed like a
   `:materialise` table. That makes it a source with a document body, which the two-branch
   shape above allows and nothing yet asks for.
4. **What does extraction failure look like?** `hydrate` degrades a broken table rather than
   throwing, and marks which repair it needs. A document that extracted to nothing should
   say so the same way, and not be a `:doc` holding an empty string.

## Why it was deferred

Two gaps were found on 2026-08-26. The other one is that `query_table` and the ƒ palette both
read a 2,000-row sample and treat it as the whole table, so the agent reports a sum over
2,000 of 1,000,000 rows as exact. That is a wrong number reaching a report, a fact and then
recall. This one is a capability loci does not have yet. A wrong answer beats a missing
feature to the front of the queue.
