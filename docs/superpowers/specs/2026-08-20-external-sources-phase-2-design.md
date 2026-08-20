# External sources, phase 2: the shell learns sources

Date: 2026-08-20
Status: **design settled, nothing built.** Two facts below are measured on 2026-08-20 — what http-kit
does with a request body, and what phase 1 left the shell unable to draw. Everything else is design.

Phase 1 shipped and merged at `8757b79`: a file at a `loci://` or `file://` location becomes a loci
table, materialised into DuckLake or read through on every query. This phase makes the shell able to
use it.

## The want

Phase 1 is reachable only by `curl` or a REPL. The action bar still offers `＋ Import CSV`, which
pastes text into a textarea, and a table whose source has vanished draws as the italic word *empty*.

## What phase 1 left behind, and why each is here

| | |
|---|---|
| **the shell cannot show a broken source** | `renderMold` branches only on `reclaimed`. `:table/unavailable` and `:table/source-drift` both fall through to `tableHTML([])`. The server already sends the sentence as `unusable`. |
| **no way in but paste** | `grep -a "api/source" resources/public/index.html` → 0 hits. |
| **no upload** | a file has to be put under `data/files/` by other means first. |
| **no refresh, no live badge** | `/api/source/refresh` exists and nothing calls it. |

## The rule

**The shell can be made honest before it is made capable.** Showing a broken source, and refreshing
one, change nothing about what loci can do — they make visible what phase 1 already built. Connecting
and uploading extend it. Build in that order, because the first half is what a person hits today.

**Corrected while writing the plan:** showing a broken source does need a small server change, and an
earlier draft of this section said it did not. `mold-payload` sends `:unusable` — a *sentence* — and
nothing that says which failure it is; `grep table/passthrough src/loci/server.clj` returns nothing, so
a healthy passthrough table's payload is indistinguishable from a materialised one. The shell could
match on the sentence's words, but that is exactly the fragile classification phase 1 rejected:
`hydrate` asks the filesystem rather than reading DuckDB's prose. So the payload gains a structural
`:source` block — the state, the uri, the mode — and the shell branches on that.

The file picker needs a listing too — `GET /api/source/files` — so this phase adds **two** routes, not
one. The earlier claim of "no new route until upload" was wrong in both halves.

That ordering is still the plan: the shell can be made honest before it is made capable.

## Measured 2026-08-20: http-kit buffers the whole request body in heap

The phase 1 design said phase 2 would add "a streaming upload route". **It cannot stream.** From
http-kit 2.8.0's own source:

```java
public class HttpRequest {
    private byte[] body;                    // the entire body, in heap
    public InputStream getBody() {
        return new BytesInputStream(body, contentLength);
```

`:body` is an `InputStream` over a byte array that already exists. A 398 MiB upload costs 398 MiB of
heap before loci sees a byte — which is exactly the property phase 2 was supposed to lift. Raising
`:max-body` moves the ceiling without removing it.

**So the browser chunks instead.** `File.slice` into ~4 MB pieces, appended server-side. Every
request stays small, heap stays flat at any file size, and — the useful consequence —
**http-kit's default 8 MiB `:max-body` is already sufficient**, so this phase adds no config knob and
makes no deployment judgement about a byte limit.

The 203,163-row paste ceiling therefore **stays**, documented. Paste is now the small-input path;
the file route is the large one.

## The shell

### `＋ Data` — one button, two tabs

`＋ Import CSV` becomes `＋ Data`. The action bar does not grow; it already has seven buttons, and a
second "get data in" door would make a reader choose before knowing the difference. Paste becomes one
tab of the panel rather than the whole of it, which is what it now is: one origin among several, and
the most constrained.

**From a file** lists the manifest's names, plus any file in `data/files/` the manifest does not know
— put there by `rsync`, a Docker mount, or the rewritten `import-csv!` — under its hash. Plus an
upload button. Picking one calls `/api/source/describe` and shows what phase 1 already returns: the
raw header, loci's column name, DuckDB's type. Then a table name, **copy in** or **read live**, and
Connect.

**Preview before commit is most of why connecting a file beats pasting one**, and it is why the panel
has tabs rather than the paste panel growing a file row: `describe` is a real dry run and needs
somewhere to put its answer.

**Paste** is today's flow, unchanged.

### The three faces of a passthrough table

`reclaimedHTML`'s block shape is reused, because a reader already knows it. What changes is that
**a reclaimed table is permanent and a broken source is fixable** — these are the only table states in
loci a person can repair, so they get a button and not only a sentence.

| state | drawn as |
|---|---|
| healthy | a `live source` badge above the rows, naming the file |
| `:table/unavailable` | the block, the sentence loci already composes, the columns it had, **Reconnect…** |
| `:table/source-drift` | the block, **both** column sets, **Refresh — adopt the new columns** |

Drift gets the stronger offer because `refresh!` genuinely resolves it in one click.

**Where the file's current columns come from.** Not by parsing them out of the message — phase 1's
sentence does carry them (`Candidate bindings: "Gross Revenue ($)"`) but reading them back out of
prose is the thing this design keeps refusing to do. The shell calls `/api/source/describe` with the
recorded uri instead: a real dry run, shipped and tested, that answers with the file's shape as it is
now. One extra request, only for a drifted table, only when it is drawn. If that call fails too, the
block falls back to the sentence, which still names them.

### The scrubber

A passthrough table shows today's file whatever time you scrub to. There is no other answer — loci
never held those bytes. So the badge changes from `live source` to
**`live source — showing current contents, not the contents at ‹T›`**.

This is the one place phase 2 must not merely render more prettily. It has to say something true.

## Upload

### The route

`POST /api/source/upload`, one call per chunk, carrying an upload id and a sequence number. The server
appends to `<LOCI_DATA>/files/.partial/<id>`. On the final chunk it hashes the completed file, renames
it to `<hash>.<ext>`, records the original name, and returns the `loci://` uri.

**It does not connect.** The panel then calls `describe` and `connect!` through the shipped API, so
the preview survives and there is one connect path rather than two.

Two rules keep it from rotting:

- **A completed upload whose hash already exists is discarded, not rewritten** — the same dedup
  `import-csv!` does, for the same reason: a file whose name is its content must never be rewritten
  with different bytes, because a passthrough source re-reads it on every query.
- **`.partial/` is swept on server start.** An abandoned upload is the one piece of garbage this
  design creates that is not a table, so it is the one thing that needs a sweep.

### The manifest

`data/files/` holds `72b441a9c3e05f18.csv`. The name you uploaded lives beside it in
`<LOCI_DATA>/files.edn` — **append-only EDN lines, last-wins by `:id`**, the third use of the idiom
`loci.chunks` and `loci.memory` already keep. This is deliberately not a new mechanism.

**One difference from those two, and it matters.** Chunks are derived: delete `chunks.edn`, sweep, and
the ids and hashes come back byte-equivalent. `files.edn` is **primary** — delete it and the original
filenames are gone for good. The bytes survive and the picker degrades to hash names, so it is a loss
of legibility rather than of data, but it is not a cache and must not be described as one.

Why a manifest at all: content addressing is what makes dedup work and what makes a passthrough file
safe to re-read, and it is also what makes the directory unbrowsable by a person. Measured during
phase 1: putting the title in the filename defeats the dedup the moment one CSV arrives under two
titles — `"Sales"` and `"Sales again"` wrote two copies of identical bytes. So the file is addressed
and the name is metadata.

## Errors

Every failure the panel can produce already has a sentence from phase 1, and the panel shows it rather
than inventing its own: a bad location, an unknown format, two headers that normalize to one column, a
source that cannot be read. The one new failure is a chunk arriving out of sequence or for an unknown
upload id, which answers `{:error …}` like every other route in `server.clj`.

## Testing

- **Browser**, against the seeded fixture: the panel's two tabs, a `describe` preview, a connect in
  each mode, and all three faces of a passthrough table including the scrubbed wording.
- **Server**: chunked upload end to end; a resumed sequence; an abandoned partial; a re-upload of
  identical bytes writing nothing; the `.partial` sweep removing a stale file and leaving a live one.
- Every regression test proved to fail when its fix is reverted.

## Deliberately not in this phase

- **GC for `data/files`.** Still none. Deleting a table still does not delete its file, and the storage
  panel says so.
- **Raising `:max-body`.** Chunking makes it unnecessary; the paste ceiling stays and stays documented.
- **Remote locations** (`s3://`, `https://`) and **SQL databases**. Phase 3, unchanged.
- **A file browser over the host filesystem.** `file://` still works by typing a path, and does not
  work against a containerised loci at all — the path resolves inside the container, which is why
  `loci://` exists.
