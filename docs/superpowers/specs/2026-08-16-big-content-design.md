# Big content: tables, blobs and media, without giving up undo

Date: 2026-08-16
Status: **design settled and measured, nothing built.** Every open question is answered and
the measurement gate has been run — see "What to measure before building", all five items
done. It found one design error (undo must restore, not re-point — the spec said the wrong
thing and is corrected inline) and killed one optimisation before it was written. What remains
is the implementation plan. No code written. Numbers below are measured on 2026-08-16 unless
marked otherwise.

## The want

loci should hold large content of several kinds — tables of millions of rows, images, video,
arbitrary blobs — and keep everything the substrate promises: `undo!`, `as-of`, the time
scrubber, one directory to back up.

Today it holds none of them. A table's rows live inside the event that created them; there is
no image or video kind at all (`grep` finds one `<img>` in the shell and it is the favicon).

## What is in the test substrate today — and why it does not size this design

| | |
|---|---|
| tables | 29 |
| median rows | **24** |
| largest | 1,000 — `tbl:orders`, seeded demo data |
| largest real table | 445 |
| all 29 together | 603 KB of JSON |
| images, video, blobs | none, no kind, no route, no renderer |

**This is a test substrate and must not be read as the target workload.** It is seeded demo
data plus a few months of light real use; it says what loci has been *able* to hold, which is
a statement about the current implementation and not about the intent. The design targets
tables of millions of rows and video files, because that is what was asked for, and the table
above is included only so nobody later mistakes today's numbers for evidence that the design
was over-built.

The one thing it does establish: there is no migration burden. Whatever ships can convert 29
small tables in a single pass.

## The rule

**Large payloads are immutable and addressed by an opaque handle. The log stores handles,
never payloads.**

That is the whole design. Everything below is consequences of it.

| content | store | handle | immutable because |
|---|---|---|---|
| table (any size) | DuckLake | snapshot id | Parquet is never modified in place |
| image, video, blob | content store | a CID | the name IS the hash |
| doc, note, small value | the event itself | — | the log is append-only |

Two handle types, one rule. Tables could have used a CID too — every loci table is create-only
today — and "Decided: one table interface" records why DuckLake was chosen anyway and what
that costs.

## Undo is not a feature to add. It is a consequence of never overwriting

The brief asked how to support undo, and whether we must implement it ourselves if the
underlying mechanism does not. The answer is that we should choose mechanisms where the
question does not arise:

- **Content-addressed blobs make undo free.** You cannot mutate a blob — a changed image is
  different bytes, so a different hash, so a different handle, so a new event. The old bytes
  are still there under the old hash, still referenced by the earlier event. `undo!` pops the
  event and the previous handle is current again. Nothing to implement.
- **DuckLake makes undo cheap.** Every transaction produces a snapshot; the event records
  which one. `undo!` pops the event and the previous event's snapshot is current.

So: **two copies is not a cost to be avoided, it is the mechanism.** The design does not try
to be clever about it. What bounds it is garbage collection, which is a separate act with
different rules — see below.

### Correction, measured 2026-08-16: the second bullet was wrong

"`undo!` pops the event and the previous event's snapshot is current" does not hold. It
assumed DuckLake's time travel rewinds the table. It does not — **`AT (VERSION => n)` is a
read, and reads do not move the write head.** Measured on a 1000-row table:

| step | snapshot | `sum(v)` |
|---|---|---|
| create, all `v=1` | A=7 | 1000 |
| update 100 rows to `v=999` | B=8 | 100800 |
| loci pops the event — substrate now points at A | — | reads at A give 1000 ✓ |
| **user writes again** (100 rows to `v=5`) | C=9 | **101200** ✗ |

Had undo held, C would be 1400. It is 101200 — the change written at B, which the substrate
believes was undone, is **still in the table and came back the moment anything else was
written**. The substrate and the lake had silently diverged: the substrate's snapshot pointer
said A, DuckLake's write head said B, and the next write built on B.

This is the same class of bug as the migration that verified against a stale in-RAM snapshot:
a pointer that says one thing while the durable state says another, reporting success.

**The fix, and it is the boring one.** `undo!` must not merely re-point; it must restore:

```sql
CREATE OR REPLACE TABLE t AS SELECT * FROM t AT (VERSION => <recorded>)
```

That is a normal DuckLake transaction producing a new snapshot whose contents equal the old
one. The write head and the substrate agree again, because the write head *moved*. There is
no `ducklake_rollback` — I checked all 26 `ducklake_*` functions — so this is the native way
to say it, not a mechanism invented around a missing one.

**It is also, surprisingly, the fast way.** I expected to need the native change feed
(`ducklake_table_changes`, which emits `update_preimage`/`update_postimage`) to invert only
the rows that changed, at O(changed) instead of O(table). Measured on 10M rows with a 100-row
edit, both correct:

| repair | time |
|---|---|
| `CREATE OR REPLACE ... AT (VERSION => n)` | **182 ms** |
| invert the change feed | 520 ms |

The full restore is **2.8× faster than the clever delta**, and its cost does not scale with
the size of the edit: restoring after a *50%* edit took 211/245/260 ms across three runs — the
same as after a 100-row edit. The delta path is more code, more edge cases (four change types,
and getting the filter wrong silently produced a no-op that *looked* like it worked), and
slower. It is dropped.

### The mapping, concretely

```
event 41  {:op :put :id "img:hero" :value {:kind :image :content "bafkrei9f2c…" :w 3000 …}}
event 42  {:op :assoc :id "img:hero" :path [:value :content] :value "bafkrei41ab…"}   ; cropped

undo!            → event 42 gone; img:hero's :content is …9f2c… again; both blobs on disk
as-of 41         → …9f2c…
gc (later)       → …41ab… is referenced by no live event → collectable
```

and for a table, the same shape with the other handle type:

```
event 57  {:op :put   :id "tbl:orders" :value {:kind :table :snapshot 12 :rows 4200000 :cols 8}}
event 58  {:op :assoc :id "tbl:orders" :path [:value :snapshot] :value 13}

as-of 57         → SELECT … FROM tbl_orders AT (VERSION => 12)
undo!            → snapshot 12 is current again; 13 is orphaned until GC
```

`as-of` gets **faster**, not slower: today it re-folds a prefix of the log to rebuild a
table's rows; with a handle it is a pointer dereference.

## loci already has the hard half

[Perkeep](https://perkeep.org/doc/schema/permanode) models mutable data on an immutable
content-addressed store with **permanodes** (a stable identity) and **claims** (timestamped
mutation blobs); an object's current state is the fold of its claims. loci already has all
three under different names:

| Perkeep | loci |
|---|---|
| permanode | an object id — `tbl:planets`, `img:hero` |
| claim | an event — `:put`, `:assoc` |
| fold of claims | `sub/materialize` |
| content-addressed blob store | **missing — this is the gap** |

So the work is not to adopt a new architecture. It is to notice that the substrate is already
a claim log over permanodes, and to give it a payload store that matches.

## Portable references: identity is not location

A reference must survive the blob moving — to S3, to a NAS, to IPFS, to another machine —
without rewriting a single event. Every system that has solved this solved it the same way,
and the rule is one sentence:

> **The handle is an identity. Where the bytes currently live is separate, mutable, and not
> authoritative.**

RFC 6920 states it as a rule about equality: *"all `ni` names with different authorities refer
to the same object if and only if the digest algorithm, length, and value are the same"* — the
authority is explicitly outside the identity. Git separates an oid from its remotes; OCI
separates a digest from the registry serving it; IPFS separates a CID from the peers providing
it. loci's log stores the identity and nothing else.

**The consequence worth naming: moving every blob to S3 changes zero events.** The log does not
know or care where bytes are, so a substrate copied to another machine, or pointed at a
different backend, still resolves — and `as-of` still resolves, because the identity in a
five-year-old event is still the identity.

### What an event carries

Modelled on an [OCI content descriptor](https://github.com/opencontainers/image-spec/blob/main/descriptor.md)
— `{mediaType, digest, size}` — which is the most widely deployed content-addressing in
practice, plus whatever the kind needs:

```clojure
{:kind       :image
 :content    "bafkrei…"      ; identity. Never changes. This is the whole reference.
 :media-type "image/png"     ; what it is, without fetching it
 :size       481203          ; how big, without fetching it — a UI can plan
 :w 3000 :h 2000}            ; kind-specific
```

`:size` and `:media-type` are on the event deliberately: the shell must be able to lay out,
label and decide about a blob it has not fetched and may not be able to reach.

### Where the bytes are is configuration

```
LOCI_BLOB_STORES=file:data/blobs,s3://bucket/loci,ipfs://
```

An ordered list the resolver tries. A blob may exist in several at once; nothing records
which. Adding a backend is a config change, never a migration.

### Untrusted backends are safe, and that is the point

Because the handle *is* the hash, every fetch is verifiable: hash what came back, compare to
the handle you asked for. RFC 6920 gives this as the reason the scheme exists — it "allows for
many forms of in-network storage without requiring as much trust in the infrastructure". So an
S3 bucket, a public IPFS gateway or a shared NAS need no trust: a corrupted or substituted
blob fails verification rather than entering the substrate. **Verification on read is not
optional in this design**; it is what makes remote backends acceptable at all.

### The handle format: a CID, not a bespoke string

Three candidates, all portable, differing in what they are self-describing about:

| | form | self-describes | notes |
|---|---|---|---|
| OCI digest | `sha256:<hex>` | algorithm, by convention | simplest, universally understood |
| RFC 6920 | `ni:///sha-256;<b64url>` | algorithm | IETF standard, defines an HTTP mapping |
| **CIDv1** | `bafkrei…` | algorithm **and content type** | native IPFS address; multicodec |

**Recommend CIDv1.** Two reasons that are about loci and not about fashion:

- **IPFS was named as a target, and a CID *is* an IPFS address.** With any other format an
  IPFS backend needs a translation layer that can only be lossy in one direction.
- **It makes the chunking upgrade a non-event.** The earlier section commits to not building
  content-defined chunking now while not precluding it. A CID's multicodec is exactly the
  field that distinguishes "this hash is of raw bytes" from "this hash is of a chunk list" —
  so a chunked blob is a different codec under the same identifier scheme, and nothing above
  `ContentStore` changes. With `sha256:<hex>` that distinction has nowhere to live.

**Verified 2026-08-16, in a REPL against the real library.** `mvxcvi/multiformats` (Greg Look)
provides `multiformats.hash` and `multiformats.cid`, and produces real CIDs that round-trip:

```
sha2-256   bafkreihnuim2vbzphn3quytkyf6ic5illcoqswoqjlxlmiw34j4chkhxey
blake3     bafkr4iaha4dqobyha4dqobyha4dqobyha4dqobyha4dqobyha4dqobyha4
```

The `bafkrei…` form is exactly what IPFS uses for raw blocks, so these are IPFS addresses and
not merely IPFS-shaped. BLAKE3 is not in the library's keyword table, but `mhash/create`
accepts a raw multicodec, so `(mhash/create 0x1e digest)` expresses it today with **no patch
and no fork** — which is what makes the algorithm decision above reversible rather than
theoretical. The `ni:` fallback is not needed.

### Which hash: SHA-256, decided by measurement that reversed the first answer

The published comparison says BLAKE3 is 3–10× faster than SHA-256 — ~1 GB/s per core against
300–400 MB/s — because BLAKE3 is a Merkle tree over 1 KB chunks and parallelises, while
SHA-256's Merkle–Damgård chain is inherently sequential. On that basis this spec first chose
BLAKE3, and the choice was approved.

**Then it was measured on the JVM, and it inverted.** Same run, same methodology, 256 MB
through each:

| | throughput |
|---|---|
| SHA-256, `java.security.MessageDigest` | **417 MB/s** |
| BLAKE3, `io.github.rctcwyvrn/blake3` (pure Java) | **97 MB/s** |

BLAKE3 is **4.3× slower** here. The reason is that all of BLAKE3's advantage is SIMD, which
pure Java cannot express, while the JDK's SHA-256 has hand-written JIT intrinsics. This is not
a hardware-acceleration artifact either: the machine is an i5-8400 with **no `sha_ni` flag**,
so SHA-256 wins without any help from the CPU.

**So: SHA-256.** Zero dependencies, in the JDK, and 417 MB/s puts a 4 GB video at about ten
seconds of ingest — a one-time cost per file.

Revisit only with a **JNI or vectorised** BLAKE3, and only if ingest is felt. The published
numbers are real for the algorithm; they are not real for a pure-Java implementation, and the
distinction is the entire finding.

### What depends on the hash staying the same: nothing

This is the property the self-describing handle was chosen for, so it is worth stating
explicitly. An old handle names its own algorithm, so an event written today keeps resolving
unchanged whatever future content is hashed with. Nothing is rewritten, `as-of` is unaffected,
and GC marks by handle string and is algorithm-blind. The BLAKE3 reversal above changed which
algorithm ships first and **not one thing about the design**, which is the demonstration.

Three consequences, small but not surprises:

- **Verification of old algorithms must be kept forever.** Not used — checked. "We switched to
  X" can never mean "we dropped Y", or every handle predating the switch becomes unverifiable.
- **Dedup does not span an algorithm change.** Identical bytes ingested either side of a switch
  get different CIDs and are two blobs. A storage miss, not a correctness problem.
- **The same applies to the chunking upgrade, which is likelier.** A video later stored as a
  chunk list gets a different CID — different codec, hash of a DAG rather than of raw bytes.
  Old handles keep working; re-ingesting the same file afterwards yields a second identity.

The failure this avoids is a format that does *not* record its algorithm: adding a second one
then means you cannot tell old from new, and the only ways out are migrating every event or
guessing.

## Decided: one table interface — and where that lands

**Answered 2026-08-16:** one interface for all tables, with the file format free to vary — a
small table can stay a `.csv` or `.json`, a large one becomes Parquet, and nothing above the
interface can tell.

That is right, and DuckDB makes it nearly free: `read_csv`, `read_json_auto` and
`read_parquet` mean the same SQL runs over all three, so format really is an implementation
detail. But taken to its conclusion the instinct says something stronger than "DuckLake for
all", and it is worth stating before anything is built.

### Every table in loci is immutable already

Checked, not assumed. Every path that produces a table — `tools/save-table!`, the agent's
`save_table`, `import-csv!`, `compute-clj!`, an `fnlib` apply, the corpus import — **creates a
new one**. Deriving a table never edits the table it derived from. There is no append, no
row update, no delete anywhere in the codebase.

So for every table loci can currently make, **a file in the content store is sufficient**:

- addressed by CID, exactly like an image
- undo is the mechanism already designed — new rows are new bytes, so a new CID, so a new
  event, and the old file is still there under the old handle
- queried by DuckDB over the file, at whatever format suits its size
- no catalog, no snapshots, and none of DuckLake's caveats: no
  [cannot-expire-the-latest](https://github.com/duckdb/ducklake/issues/261), no
  [expiry data-loss bugs](https://github.com/duckdb/ducklake/issues/527)

**One undo mechanism instead of two.** Images, video and tables all become "content-addressed
immutable payload, handle in the event", and the design shrinks rather than grows.

### So when is DuckLake actually needed?

When a table becomes **mutable** — appended to, updated or deleted from in place, at a size
where rewriting the whole file per change is absurd. A live log table, a dataset that grows
daily. That is a real future want and DuckLake is the right answer to it; it is simply not
today's, and building the catalog now means carrying its snapshot semantics and its open bugs
before anything needs them.

**Decided 2026-08-16: DuckLake from the start.** Uniformity wins — every table gets snapshots
whether or not it currently needs them, and the mutable case arrives without a second
mechanism appearing at the moment it is least convenient.

The section above stays because it names what that choice costs, and those costs are now
shipping constraints rather than hypotheticals:

- **Two undo mechanisms, not one.** CIDs for blobs, snapshot ids for tables. Both are
  handles in an event and both are swept by mark-and-sweep, so the *rule* is still single —
  but there are two implementations of it to keep honest.
- **DuckLake's snapshot-expiry bugs are inherited on day one**, so table GC ships disabled
  with retention "keep everything". See the GC section.
- **A small table is Parquet in a catalog, not the CSV it arrived as.** The `less`-readable
  property is given up. If that turns out to matter, `FileTable` is the escape hatch and the
  interface already allows it.

### What the interface hides

```
tbl:planets      8 rows        DuckLake snapshot 3
tbl:orders       1,000 rows    DuckLake snapshot 7
tbl:events       40M rows      DuckLake snapshot 12
```

One store, one handle type, one query surface. Ingest still accepts csv/json/parquet — DuckDB
reads all three natively — but what lands is Parquet under the catalog, and the caller cannot
tell which it started as.

## Images: metadata is not optional

**Answered 2026-08-16:** images now, video deliberately left room for; and an image
participates in recall — *"it should not just live without anything"*.

An image arrives with more than pixels, and loci should take all of it:

1. **Embedded metadata**, extracted on ingest — EXIF/XMP: capture time, camera, lens, GPS,
   dimensions, orientation. Facts the file already asserts about itself.
2. **A description, generated by a VLM.** This is the part that makes an image a first-class
   substrate object rather than an opaque attachment.
3. **Whatever the agent adds** — the same way it distils facts from a document.

### Why the description is load-bearing

An image has no text, so without one it is invisible to everything loci is: LEAP cannot find
it, `recall` cannot cite it, `chunks-of` has nothing to embed, and an entity page can never
mention it. **The description is the surface all of those attach to.** With it, an image is
chunked and embedded like any document, and "the photo of the Delft nautilus cup" becomes
findable by meaning.

That also sets the failure mode: an image with no description is an incomplete object, and the
ingest path should say so out loud when no VLM is configured — the way
`embedding-configured?` already makes "semantic recall: off" a stated condition rather than a
silent degradation.

### Video: what is left room for, and what is not designed

`:kind :video` gets the same content-addressed handle and the same metadata treatment, and
nothing in this design forbids it. What is **not** designed here: playback, HTTP range
requests, thumbnail extraction, transcoding, and how a VLM describes something with a time
axis. Those are a separate spec, and pretending otherwise would be the same mistake as
promising cross-process safety before measuring it.

### Open questions this raises

### Decided: the VLM, and where the description lives

**`LOCI_VLM_MODEL=gemma-4-26b-a4b`**, with the endpoint falling back to `LOCI_LLM_ENDPOINT`
when `LOCI_VLM_ENDPOINT` is unset — a vision model here is a chat-completions call with an
image in the message, so it is the same surface with a different model. That fallback chain is
the one `LOCI_RERANK_API_KEY` already uses (embed's key, then the LLM's), so it is a pattern
the config file has rather than a new idea. Absent a model, ingest states "no VLM configured"
the way `embedding-configured?` states "semantic recall: off".

**The description is a field on the image's `:value`**, not a separate object. It matches
`:title` and `:intent`, it makes `chunks-of` an ordinary read of the object rather than a
join, and an `:assoc` on that one path is how a correction becomes a reversible event.

### Verified: EXIF on the JVM

`com.drewnoakes/metadata-extractor` 2.20.0 (Apache 2.0), exercised in a REPL 2026-08-16. It
reads EXIF, IPTC, XMP and ICC — and **for video too**, which is the room this design left.

It answers what the descriptor needs even for an image carrying no EXIF whatsoever: a
generated JPEG still yielded `Detected MIME Type = image/jpeg`, `Image Width = 640`,
`Image Height = 480`. So `:media-type`, `:w` and `:h` come from one call rather than from
guessing at a file extension.

**One thing to filter.** The library reports a `File` directory containing `File Name` and
`File Modified Date`. In a content-addressed store those are a temp filename and the moment of
ingest — accidents of transport, not facts about the image. Recording them would put a lie in
the substrate that reads exactly like provenance. Drop that directory; keep JPEG/Exif/GPS/XMP.

## Garbage collection, and why it is not undo

Space is reclaimed by a **mark-and-sweep over the live log**, never by undo:

1. **Mark** — walk `sub/history`, collect every handle any live event mentions.
2. **Sweep** — delete blobs not in that set; `ducklake_expire_snapshots` for snapshot ids not
   in it.

Two rules make this safe, and both are the reason it is a separate act:

- **Only `undo!` orphans a handle.** loci's undo physically removes the event, so its handle
  becomes unreferenced immediately. Every event still in the log is reachable by `as-of`, so
  its handle must never be collected — "old" is not "garbage" in a substrate with time travel.
- **Sweep is conservative and manual, dry-run by default**, in the shape of
  `loci.factmigrate` and `loci.corpus`: it reports what it would free and refuses on anything
  it cannot account for. A GC that silently deletes the wrong blob is unrecoverable in a way
  a stale index never is.

**DuckLake caveat, inherited on day one by the decision to adopt it now —** measured from its
issue tracker: it
[cannot expire the latest snapshot](https://github.com/duckdb/ducklake/issues/261) — which is
exactly the state an undo leaves — and snapshot expiry has open data-loss bugs
([#527](https://github.com/duckdb/ducklake/issues/527),
[#529](https://github.com/duckdb/ducklake/issues/529)). So table GC ships **disabled**, with
retention "keep everything", and is enabled only once those are closed. Blob GC has no such
dependency and can ship first — which means for a while loci reclaims image space and not
table space, and should say so rather than implying a sweep was complete.

## Storage cost, and the upgrade path we should not preclude

Two copies of a 4K video is 2× storage, and for a small edit that is nearly all waste.
[restic and borg](https://deepwiki.com/restic/restic/5.3-data-deduplication) solve this with
**content-defined chunking**: a rolling hash splits a file into variable-sized chunks, so an
insertion does not shift every subsequent boundary, and unchanged regions dedupe. Borg's
smaller average chunk wins 10–15% on datasets of small partially-changing files; restic added
zstd compression in 0.14.

loci should **not build this now** — there is no media in the substrate at all, so the saving
is currently zero and unmeasurable. But the design must not preclude it, and it does not:
if a blob's handle is the hash of a *chunk list* rather than of the bytes, dedup becomes an
implementation detail behind `ContentStore` with no change to the log, the events, or undo.
That is the only forward-compatibility requirement this design carries.

## Shape

Two protocols, in the house style already set by `sub/Store`, `mold/Recall` and
`fl/FactLog`:

```clojure
(defprotocol ContentStore
  (put!   [this in]      "bytes/stream → handle. Idempotent: same bytes, same handle.")
  (fetch  [this handle]  "→ stream, or nil. VERIFIES the bytes against the handle.")
  (size   [this handle]  "→ bytes, or nil")
  (sweep! [this live]    "delete handles not in `live`. Dry run by default."))

;; …and a resolver over an ordered list of stores, so `file:`, `s3:` and `ipfs:`
;; are the same protocol behind one lookup. Nothing records WHICH store holds a
;; blob: that is the point of identity-not-location, and it is what makes adding
;; a backend a config change rather than a migration.

(defprotocol TableStore
  (rows      [this ref opts] "a page, for the Rows viewer")
  (query     [this q]        "HoneySQL data → results")
  (put-rows! [this rows fmt] "→ the handle to record in the event: a CID, or a snapshot id")
  (at        [this handle]   "a read-only view at a past handle"))
```

Implementations: `FileContentStore` (`data/blobs/ab/cd/abcd…`) and `DuckLakeTable`.
`InlineTable` — rows in the event, what exists now — survives for tests. `FileTable` (a
csv/json/parquet blob queried by DuckDB over its path) is the documented escape hatch if
catalog-managed Parquet turns out to cost more than it buys.

**Queries are [HoneySQL](https://github.com/seancorfield/honeysql) data, not SQL strings**, and
that is a thesis point rather than a taste one: `compute-clj!` currently has the agent write
*Clojure code* to transform a table. A query expressed as data is storable in the substrate,
diffable, reviewable before it runs, and reversible — which is "the agent proposes, you
decide" applied to computation instead of to prose.

### On disk

```
data/
  substrate/      LMDB — the log. Truth: metadata, structure, and handles.
  blobs/          content-addressed, immutable, swept — tables, images, video alike
  lake/           DuckLake catalog + Parquet — every table
```

Ingest accepts csv/json/parquet, since DuckDB reads all three; what lands is Parquet under the
catalog.

One directory is still the whole backup, which was the point of the 2026-08-15 consolidation
and must not be given back. **What it no longer is, once `lake/` lands, is one directory you
can copy while the server runs**: `substrate/` and `lake/` are not written in one
transaction, so a copy taken between a `:put` and its Parquet holds a handle naming a table
the copy does not contain. Stopping the app first is the fix, and it is route (3) of "who may
write" — it costs nothing and was already the rule for anything touching storage directly.

## What this costs

- **`duckdb_jdbc` is 81.5 MB compressed**, bundling four platform natives, which roughly
  doubles the uberjar. Recorded as a fact, not as an objection: `build.clj`'s
  `foreign-natives` already handles exactly this shape and a fourth pattern trims it in slim
  builds. Nothing about it should influence the design, and it is not an open question.
- Two new stores to open, close and check at boot, next to `data-dir-problem`'s existing
  checks.
- `mold-payload` grows a branch: a table's rows may now need fetching rather than reading.

## Open questions for the user

None outstanding. Everything the design needed decided is decided; what remains is
measurement.

**Closed:** CIDv1 vs `ni:` (CIDv1, library verified in a REPL). Which hash (SHA-256, by
measurement that reversed the first answer). One interface for all tables, format free to vary
(yes). Media scope (images now, room for video). Whether images participate in recall (yes —
and the description is what makes it possible). DuckLake from the start rather than deferred.
The VLM (`gemma-4-26b-a4b`, endpoint falling back to `LOCI_LLM_ENDPOINT`). Description as a
field. EXIF library (`metadata-extractor` 2.20.0, verified in a REPL).

## What to measure before building — all run 2026-08-16

DuckDB 1.5.5 with the `ducklake` extension, via `org.duckdb/duckdb_jdbc 1.5.5.1`, on a
throwaway lake under `/tmp` (deleted after). Same i5-8400 as the hash measurements.

**1. Round-trip.** Schema `(id BIGINT, name VARCHAR, category VARCHAR, amount DOUBLE,
ts TIMESTAMP)`, generated in-engine from `range()`; the update touches 10% of rows.

| rows | create | `GROUP BY` | update 10% | **`AT (VERSION => n)`** | current-version read | disk |
|---:|---:|---:|---:|---:|---:|---:|
| 10 k | 35 ms | 27 ms | 15 ms | **7 ms** | 4 ms | <1 MB |
| 1 M | 308 ms | 24 ms | 236 ms | **8 ms** | 8 ms | 10 MB |
| 10 M | 2 900 ms | 53 ms | 1 432 ms | **34 ms** | 52 ms | 107 MB |

Time travel is free — at 10M rows, reading an old version (34 ms) is *faster* than reading the
current one (52 ms), because the old snapshot has no deletion vectors to apply. And it is
correct: the pre-update sums came back exactly (499 995 000 against 549 990 000 current at 1M;
4 999 950 000 against 5 499 900 000 at 10M). Nothing here is a bottleneck.

**2. Boot cost.** Attaching a lake that already holds 10M rows:

| open connection | `LOAD ducklake` | `ATTACH` | `USE` | **total** | first query |
|---:|---:|---:|---:|---:|---:|
| 9 ms | 43 ms | 4 ms | 19 ms | **75 ms** | 9 ms |

Against loci's existing ~14 s dependency load, this is **0.5%**. The startup budget was the
worry; it is not one. Note the extension was already installed to `~/.duckdb` — a first-ever
`INSTALL ducklake` fetches over the network, so the packaged build must ship it or install at
image-build time, never on first user boot.

**3. `undo!` latency.** 182–260 ms on 10M rows, flat in the size of the edit, and it is a
*restore* rather than a pointer move — see "Correction, measured 2026-08-16" above, which is
the finding that matters most from this round.

**4. Does undo history cost unbounded disk?** No, and reclaiming is cheap. Each restore writes
a fresh Parquet copy (~38 MB per 10M rows), so a lake at 551 MB across three restores and a
50% edit came back to **265 MB — 286 MB reclaimed in 69 ms**:

```sql
CALL ducklake_expire_snapshots('lake', older_than => now());  -- 42 ms
CALL ducklake_cleanup_old_files('lake', cleanup_all => true); -- 27 ms
```

Current data survived intact (10M rows still readable). This is the native form of the
garbage collection this spec already argues is a separate act from undo: expiring snapshots is
exactly "drop undo history older than the horizon", and it costs milliseconds.

**5.** ~~Hash throughput on this machine~~ — **done earlier.** SHA-256 417 MB/s, pure-Java
BLAKE3 97 MB/s, no `sha_ni` on this CPU. Recorded above.

### What the measurements changed

- **One design error found and fixed**: undo must restore, not re-point. It would have shipped
  as silent data resurrection — the worst possible failure for a substrate whose whole promise
  is that undo means undo.
- **One optimisation killed before it was built**: the change-feed delta inversion is slower
  and more fragile than the naive restore.
- **Two worries retired**: boot cost (75 ms) and time-travel cost (34 ms at 10M) are both
  negligible, and neither needs mitigation in the plan.
