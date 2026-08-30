# External sources, phase 2: the shell learns sources

Date: 2026-08-20
Status: **built and merged in `a2e5d93` on 2026-08-22.** The carry-over file cleanup work
shipped in `98c3757`. The body below records the phase 2 design and its measurements.

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
| `:table/unavailable` | the block, the sentence loci already composes, the columns it had, **Reconnect…** and **Try again** |
| `:table/source-drift` | the block, **both** column sets, **Refresh — adopt the new columns** |

Drift gets the stronger offer because `refresh!` genuinely resolves it in one click.

**Unavailable gets a refresh too**, which an earlier draft of this table left out. A file that is
gone now may be back in a moment — a mount that dropped, a sync that had not finished, a container
restarted — and `refresh!` is exactly the thing that would notice. It is the weaker of the two offers
there, not the absent one.

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

### Measured while building it, 2026-08-20: three things this section had wrong

**`slurp` on a chunk is not byte-exact, and the corruption is permanent.** An earlier draft of the
route read the body with `(slurp (:body req))`. Measured: `region,café\nEMEA,€10\n` sliced one byte
into the two bytes of `é`, each half slurped and re-encoded, is **24 bytes in and 28 bytes out** —
`c3 a9` becomes `ef bf bd ef bf bd`, one U+FFFD per half. An incomplete UTF-8 sequence at either end
of a slice decodes to a replacement character, and a 4 MB slice of an arbitrary file is exactly where
that happens. Because the file is **content-addressed**, the damage lands under a hash *of the
corrupt bytes* and is thereafter indistinguishable from a good file. So nothing on the path is ever a
String: the route hands `(:body req)` through and `io/copy` streams it. The browser must match —
`Blob` from `File.slice` as the body, never `readAsText`.

**Hashing the finished file must stream too.** `content-hash` took a String. Hashing a completed
200 MB upload through it means holding 200 MB as a String, which is the exact property this phase
exists to remove. It now has a `File` arity over a 64 KB buffer, and the two arities are proved equal
on the same bytes — that equality is what makes a pasted CSV and an uploaded one dedup against each
other, and nothing else was checking it.

**The upload id is caller-supplied and becomes a path.** It names a file under `.partial/`, so
`{"id": "../escaped.csv"}` writes outside it. Constrained to `[A-Za-z0-9_-]{1,64}`.

And one behaviour this section did not specify: **chunk 0 truncates rather than appends.** The
expected-sequence atom is process state that no file on disk backs, so a leftover entry from an
abandoned upload would refuse a *new* upload reusing that id, forever. Making chunk 0 a restart makes
the leftover entry harmless as well as small — which is why abandonment leaves it there rather than
buying an eviction nobody asked for.

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

## Measured 2026-08-21: 200 MiB through a real browser

The gate this phase was built for. A 200 MiB CSV chosen with `page.setInputFiles` in a real
chromium, uploaded by the panel's own `uploadChunks`, with the server's heap sampled every 20 ms
on a background thread inside the server JVM — phase 1's method, moved into the spawned server the
browser harness boots. `<LOCI_DATA>` is a throwaway under `.tmp`, on disk and not on this machine's
tmpfs `/tmp`, so the file's own bytes never count against RAM.

### The file

`id,region,amount,day,token` — DuckDB infers `BIGINT, VARCHAR, DOUBLE, DATE, VARCHAR`.

| | bytes | rows | chunks at 4 MiB | generated in |
|---|---|---|---|---|
| large | 209,715,195 (200.0 MiB) | 6,032,871 | 50 | 2,900 ms |
| small | 2,097,127 (2.0 MiB) | 60,340 | 1 | 56 ms |

Two sizes, in one JVM, because that is what makes "flat" mean anything: phase 1's flat-heap claim
was only convincing because 1M and 10M rows both cost 57 MB. The small one is **one chunk**, so
the request *is* the file — the shape phase 1 measured and this phase exists to leave behind.

### 1. The upload — heap is bounded by the collector, not by the file

Default heap (`-Xmx` 3,976 MB). Wall clock is from `setInputFiles` to the last chunk's answer on
the wire; `describe` and `connect` are timed separately below, because they are separate costs.

| | 2.0 MiB / 1 chunk | 200.0 MiB / 50 chunks |
|---|---|---|
| upload wall | 36 ms | **1,347 ms** |
| first byte on the wire, after `setInputFiles` | 10 ms | 8 ms |
| peak JVM heap | 59 MB | **179 MB** |
| live heap, before → after | 55 → 55 MB | **55 → 55 MB** |
| process RSS across the upload | 615 → 624 MB | 635 → 687 MB |
| chunks refused — a `413`, or an out-of-sequence answer | 0 | 0 |

**The design's sentence — "heap stays flat at any file size" — is not quite what happens, and the
difference is the whole finding.** Retained heap is flat to the megabyte: 55 MB live before the
upload and 55 MB after it, at both sizes, because nothing keeps a byte. *Peak* heap is not flat —
59 MB against 179 MB — and reporting "flat" without that would be the easy lie here, the same one
phase 1 caught itself about with RSS.

What the peak actually tracks is **the ceiling the collector is working against**, never the file.
The same 200 MiB upload, same 50 chunks, in a fresh JVM each time:

| `-Xmx` | upload wall | peak heap | live heap, before → after | connect wall | peak heap, connect |
|---|---|---|---|---|---|
| 3,976 MB (default) | 1,347 ms | 179 MB | 55 → 55 MB | 949 ms | 167 MB |
| 256 MB | 1,236 ms | 239 MB | 56.3 → 56.3 MB | 960 ms | 164 MB |
| **128 MB** | **1,250 ms** | **118 MB** | 56.5 → 56.5 MB | 999 ms | 119 MB |

**A 200 MiB file uploads, previews and connects in a 128 MB heap — a ceiling 0.61× the file — and
is no slower for it: 1,250 ms against the 1,347 ms it took with thirty-one times the room.** That is the claim this phase rests on, and it is
falsifiable in the strongest way available: had heap scaled with the file, the 128 MB row could not
exist. Set beside phase 1's number for the same work through the JSON body path — **2,476 MB of
peak heap for a 418 MB file, roughly six times it** — the ratio moves from ~6× the file to ~0.6×,
a factor of ten, and the ceiling stops being a function of what somebody uploads.

The peak is transient allocation the collector has not got to yet, and the third run says so
outright: the *same* file in **2 MiB chunks** — half the buffer per request — peaked **higher**,
267 MB against 179 MB. If the peak were the file, or the chunk, that is impossible. It is 50 or
100 request buffers' worth of garbage, collected whenever G1 decides to.

### 2. Per chunk, or per byte

50 sequential round trips is a real component and it is the part that would grow at 2 GB, so it
was measured on its own: 50 POSTs of **one byte** to `/api/source/upload` from the same page,
sequentially, as the panel sends them.

| | |
|---|---|
| 50 × 1-byte chunks — the round-trip floor | 156.6 ms → **3.13 ms each** |
| 100 × 1-byte chunks | 304.3 ms → 3.04 ms each |
| 200 MiB in 50 chunks (4 MiB) | 1,347 ms |
| 200 MiB in 100 chunks (2 MiB) | 1,428 ms |

Doubling the chunk count on the same bytes cost **+81 ms for +50 round trips — 1.6 ms each in
flight**, below the 3.1 ms idle floor because a request's overhead overlaps the previous one's
bytes. So of the 1,347 ms, **~157 ms is the 50 round trips (12%)** and the remaining ~1,190 ms
moves 209.7 MB, which is **176 MB/s** end to end through a browser, a socket, and `io/copy` to
disk.

At the 2 GB a person would eventually try: 500 round trips ≈ 1.6 s against ~11 s of bytes. Per
chunk stays the minority cost, and the lever if it ever stops being one is the chunk size — which
buys wall clock with heap, linearly, and is the trade this design already made once.

### 3. What arrives, and what a second upload of it does

The stored file is named for the SHA-256 of the bytes that were sent, so reassembly is checked by
the filename and by nothing softer. 50 slices, cut by `File.slice`, sent as `Blob`s, appended by
`io/copy`, hashed from disk:

| | |
|---|---|
| SHA-256 prefix computed over the fixture, outside loci | `78e269ca4ff07dbf` |
| the file in `<LOCI_DATA>/files/` | `78e269ca4ff07dbf.csv` |
| its length | 209,715,195 — exact |
| `files.edn`'s memory of it | `measure-big.csv` |
| `describe` over it — the preview the panel draws | **38 ms** |
| `connect!`, **copy in**, 6,032,871 rows | **949 ms** |

Re-uploading the identical 200 MiB, in 100 chunks rather than 50, answered `{:existed true}` and
the directory still held one copy: **nothing was written, and the file loci already had was
connected instead.** Dedup holds at 200 MiB, not only in the fixture-sized tests.

**The mode at 200 MiB was `materialise` — copy in, the panel's default — and the choice does not
weaken the heap claim.** It is the heavier of the two: DuckDB reads the whole file and writes
Parquet, where passthrough reads it once for a `count(*)` and writes catalog only (phase 1: 294 ms
and 21,543 bytes at 10M rows). If heap stays flat through the heavier one, the lighter one cannot
be worse. And connect is downstream of the upload in every sense — the bytes are on disk and the
request is over before the panel calls `describe`, with a `loci://` uri and the shipped API,
exactly as a picked file does. The other mode is section 4.

`describe` at 38 ms over 200 MiB — faster than the 84 ms the 2 MiB file cost, which was the first
DuckDB call in that JVM and paid for the connection — confirms phase 1's 42 ms sniff: `describe`
does not read the file, and preview-before-commit is free at any size.

### 4. Both modes, from the panel

`materialise` is the panel's default and the only mode the browser suite connects in, so the other
half of *connected from the shell, in either mode* was driven by hand through the same page. The
2.0 MiB file, uploaded, previewed, and the radio set:

| radio | on the wire | what opened |
|---|---|---|
| **copy in** | `materialise` | the rows, and **no** live badge |
| **read live** | `passthrough` | a `live source` badge naming `6b0d26df345ae95f.csv`, a **Refresh**, and the rows under it |

`connect!` cost 347 ms one way and 369 ms the other at this size, and the payload's `:source` block
came back `{:state "live" :mode "passthrough"}` for the live one. The capability holds in both
modes; only one of them has a browser test standing over it.

### 5. Both suites

- `clojure -M:test` — **801 tests, 4,405 assertions, 0 failures, 0 errors.**
- `npm run test:browser` — **148 tests, 148 pass, 0 fail**, 360.6 s of test time (the machine was
  busy, so wall clock was longer). The known `outline` flake — "clicking an entry brings that cell
  into view", which reads a `.flash` class removed on a 900 ms timeout — **did not fire this run**.
  One green run is not a fix; it is a flake behaving.

### Which of the two things each number establishes

CLAUDE.md's rule: a measurement says a mechanism works, **or** that a cost is real, never both.

- **That the cost is real.** Everything at 200 MiB, taken at the size the design names and not over
  `data/`: the 1,347 ms upload, the 176 MB/s and the 12% that is round trips, the 949 ms connect,
  and above all the three `-Xmx` rows — a heap ceiling of 128 MB is enough for a 200 MiB file, and
  6× the file was what the path this replaces needed.
- **That the mechanism works.** The hash matching `78e269ca4ff07dbf` and the length matching to the
  byte; `{:existed true}` on the second upload; the manifest remembering `measure-big.csv`; the
  inferred column types in the preview; the badge and the wire word in both modes; both suites.
  These say the code is correct. They say nothing about whether anything costs too much.

### How it was measured, for whoever repeats it

`ManagementFactory/getMemoryMXBean` sampled every 20 ms on a daemon thread, VmRSS every fifth tick,
and a second http-kit server on a random port so the browser-driven run could `/reset` the peak at
a phase boundary and read it back — the peak is monotonic since reset, so the value read at the end
of the upload window is the upload's own. Live heap is two `System.gc()`s with a pause between.

One trap worth writing down: **`slurp` and `io/reader` cannot read `/proc/self/status`.** Both go
through `StreamDecoder`, which calls `.available()` to size its read, and procfs answers that with
`IOException: Invalid argument`. `java.nio.file.Files/readString` reads it fine. The obvious
spelling of an RSS sampler throws on every sample.

## Deliberately not in this phase

- **GC for `data/files`.** Still none. Deleting a table still does not delete its file, and the storage
  panel says so.
- **Raising `:max-body`.** Chunking makes it unnecessary; the paste ceiling stays and stays documented.
- **Remote locations** (`s3://`, `https://`) and **SQL databases**. Phase 3, unchanged.
- **A file browser over the host filesystem.** `file://` still works by typing a path, and does not
  work against a containerised loci at all — the path resolves inside the container, which is why
  `loci://` exists.
