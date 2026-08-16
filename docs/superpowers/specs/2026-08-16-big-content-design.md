# Big content: tables, blobs and media, without giving up undo

Date: 2026-08-16
Status: **research and design, awaiting approval.** No code written. Numbers below are
measured against the live substrate on 2026-08-16 unless marked otherwise.

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
| large table | DuckLake | snapshot id | Parquet files are never modified in place |
| blob / image / video | content-addressed store | a CID | the name IS the hash |
| small table, doc, note | the event itself | — | the log is append-only |

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

### The mapping, concretely

```
event 41  {:op :put :id "img:hero" :value {:kind :image :content "bafkrei9f2c…" :w 3000 …}}
event 42  {:op :assoc :id "img:hero" :path [:value :content] :value "bafkrei41ab…"}   ; cropped

undo!            → event 42 gone; img:hero's :content is …9f2c… again; both blobs on disk
as-of 41         → …9f2c…
gc (later)       → …41ab… is referenced by no live event → collectable
```

and for a table:

```
event 57  {:op :put :id "tbl:orders" :value {:kind :table :store :lake :snapshot 12 :rows 4200000}}
event 58  {:op :assoc :id "tbl:orders" :path [:value :snapshot] :value 13}

as-of 57         → SELECT … FROM tbl_orders AT (VERSION => 12)
undo!            → snapshot 12 is current again; 13 is orphaned
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

**Check before committing:** whether a usable multiformats/CID library exists for the JVM. If
it does not, the fallback is `ni:` — an IETF standard, trivial to encode — and IPFS interop
becomes a translation layer. This is a real open question, not a formality.

### Which hash, and why it can be deferred

[BLAKE3 is 3–10× faster than SHA-256](https://ssojet.com/compare-hashing-algorithms/sha-256-vs-blake3)
— ~1 GB/s per core against 300–400 MB/s, and it parallelises near-linearly while SHA-256 stays
single-threaded. For multi-gigabyte video that is the difference between seconds and minutes.

For the workload this design targets, that gap is real rather than theoretical: a 4 GB video
is roughly 11 seconds of single-threaded SHA-256, against about a second of BLAKE3 across six
cores. Ingesting video is exactly where it would be felt.

**So: prefer BLAKE3 if a sound JVM implementation exists, and fall back to SHA-256 — which is
in the JDK — if not.** Either way the decision is reversible, because a self-describing
handle lets BLAKE3 blobs sit beside SHA-256 ones in the same store with every old handle
still resolving.

The reason this is a deferral and not a mistake is the self-describing handle. A CID carries
its own multihash code, so BLAKE3 blobs can be added later beside SHA-256 ones, in the same
store, with every old handle still resolving and no migration. **That is the concrete payoff
for paying multiformats' complexity, and it is why the format decision matters more than the
algorithm one.**

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

**DuckLake caveat, measured from its issue tracker:** it
[cannot expire the latest snapshot](https://github.com/duckdb/ducklake/issues/261) — which is
exactly the state an undo leaves — and snapshot expiry has open data-loss bugs
([#527](https://github.com/duckdb/ducklake/issues/527),
[#529](https://github.com/duckdb/ducklake/issues/529)). So table GC ships **disabled**, with
retention "keep everything", and is enabled only once those are closed. Blob GC has no such
dependency and can ship first.

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
  (rows      [this id opts]  "a page, for the Rows viewer")
  (query     [this q]        "HoneySQL data → results")
  (put-rows! [this id rows]  "→ the snapshot id to record in the event")
  (at        [this snap]     "a read-only view at a past snapshot"))
```

Implementations: `FileContentStore` (`data/blobs/ab/cd/abcd…`) and `DuckLakeTable`, with
`InlineTable` — rows in the event, what exists today — surviving for tests and for tables too
small to be worth a snapshot.

**Queries are [HoneySQL](https://github.com/seancorfield/honeysql) data, not SQL strings**, and
that is a thesis point rather than a taste one: `compute-clj!` currently has the agent write
*Clojure code* to transform a table. A query expressed as data is storable in the substrate,
diffable, reviewable before it runs, and reversible — which is "the agent proposes, you
decide" applied to computation instead of to prose.

### On disk

```
data/
  substrate/      LMDB — the log. Truth: metadata, structure, and handles.
  blobs/          content-addressed, immutable, swept
  lake/           DuckLake catalog (.duckdb) + Parquet
```

One directory is still the whole backup, which was the point of the 2026-08-15 consolidation
and must not be given back.

## What this costs

- **`duckdb_jdbc` is 81.5 MB compressed**, bundling four platform natives, which roughly
  doubles the uberjar. Recorded as a fact, not as an objection: `build.clj`'s
  `foreign-natives` already handles exactly this shape and a fourth pattern trims it in slim
  builds. Nothing about it should influence the design, and it is not an open question.
- Two new stores to open, close and check at boot, next to `data-dir-problem`'s existing
  checks.
- `mold-payload` grows a branch: a table's rows may now need fetching rather than reading.

## Open questions for the user

1. **DuckLake for all tables, or above a threshold?** I would argue all: one code path, and a
   threshold makes a table's *semantics* depend on its row count — "undo worked on my 900-row
   table but not my 1,100-row one" is the worst kind of surprise. `InlineTable` stays for
   tests. The current corpus is small enough that converting all of it is one pass, so
   "everything" costs nothing to adopt.
2. **Media scope.** Images are cheap — a kind, a route, an `<img>`. Video raises playback,
   ranges, thumbnails and transcoding, none of which this design addresses.
3. **Does a blob participate in LEAP and recall?** An image has no text. A caption or an
   agent-written description would be the chunkable surface, and that is a separate design.
4. **CIDv1 or `ni:`** — decided in favour of CIDv1 above, but contingent on a usable JVM
   multiformats library existing. If none does, is a translation layer for IPFS acceptable, or
   should the format follow whatever ships cleanly?

## What to measure before building

1. **DuckLake round-trip on a real table**, at 10k / 1M / 10M rows: write, snapshot, query,
   `AT (VERSION =>)`, and the size on disk. All three numbers are guesses today.
2. **Boot cost** of attaching the lake, against loci's existing ~14 s Datalevin load — the
   startup budget is already the thing that made every test run expensive.
3. **`undo!` latency** when it moves a snapshot pointer versus popping an inline table.
4. **Hash throughput on this machine**, SHA-256 and BLAKE3 against a realistic video, before
   choosing — the figures above are from published benchmarks, not from here. And whether a
   sound JVM BLAKE3 implementation exists at all.
