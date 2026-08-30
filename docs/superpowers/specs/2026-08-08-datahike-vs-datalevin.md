# Datahike vs Datalevin, for loci's substrate

Date: 2026-08-08
Status: **analysis only — no decision, no work implied.** Written because the
2026-08-05 substrate design rejected Datomic Local, XTDB and SQLite+DataScript by name
but never evaluated Datahike, which had been parked as "XTDB/Datahike swap, out of scope"
in the 2026-07-22 design. This closes that gap for later consideration.

Current state, 2026-08-30: loci still uses Datalevin through KV, not Datalog. The
environment has five KV DBIs plus the `touched` list DBI. Facts, chunks, and their vectors
now live there.

Evidence base: the Datahike source at `~/src/datahike` (main @ `cbdbd242`, 2026-08-04) and
the Datalevin 1.0.0 jar in `~/.m2`, read directly. Where a claim comes from a project's own
documentation rather than from its code or a measurement, it says so.

---

## 0. The fact that framed the 2026-08-08 analysis

At that time, loci did not use Datalevin as a Datalog database. `src/loci/dlv.clj` was
326 lines and used it as an ordered KV store: `open-kv`, three DBIs plus one list DBI, one
`transact-kv` per commit, `get-range` for the boot replay, `list-range` bounded
`[:at-most n]` for `object-at`. There is no `d/q` anywhere in `src/`, no schema, no
full-text index, no vector index.

The Datalog, full-text, and vector phases of the 2026-08-05 design were planned but unbuilt.
Semantic recall then lived outside the store. `memory.clj` and `embed.clj` did not touch
Datalevin.

Thus, this was not a comparison of two Datalog engines. It compared what sat under the
326-line adapter and the planned phases.

## 1. They are not the same category of thing

| | Datalevin 1.0.0 | Datahike (main, 2026-08-04) |
|---|---|---|
| Shape | LMDB + datalog + a first-class KV API | Datalog over konserve-backed persistent trees |
| Values | nippy — arbitrary Clojure, no text round-trip | Datomic type set **plus `:db.type/bytes`** (absent from `doc/schema.md`'s table; measured to work) |
| Large/opaque values | any value, any size | `:db.type/store-ref` → content-addressed konserve blob (`src/datahike/blob.cljc`) |
| History | none — the application keeps its own log | native `as-of` / `since` / `history`, `purge`, and git-like **branches** |
| Full-text | built in (`search.clj`, `analyzer.clj`, `stem.clj`), same env and same transaction as the data | Scriptum (Lucene) as a secondary index — **"Status: Experimental"**, separate store, async backfill (`doc/secondary-indices.md`) |
| Vectors | built in (`vector.clj`, usearch HNSW) plus `embedding.clj` / `llm.clj` | Proximum (HNSW) secondary index — experimental, Java 22+ |
| Write path | LMDB pages, one fsync per transaction (default env flags `#{:nordahead :notls}` — no `:nosync`, `constants.clj:117`) | rewrites the root→leaf path of every index per commit + two records (`doc/write-amplification.md`) |
| Storage reclaim | LMDB free-page reuse, automatic | `d/gc-storage`, with grace periods — an operational duty (`doc/gc.md`) |
| Write concurrency | caller's problem (loci hand-rolled `locking` in `dlv.clj`) | single writer thread behind a queue (`src/datahike/writer.cljc`) |
| Platform | native LMDB per arch (`dtlvnative-*`, 3.7 MB), needs `--enable-native-access` | pure JVM — **and** cljs on Node and the browser (IndexedDB), beta |
| Scale-out | client/server mode, Raft HA (`ha.clj`, `replica.clj`, jraft) | Distributed Index Space: many readers straight off shared storage, one writer |
| Size / bus factor | 80.5k lines Clojure + 77 Java classes, single author, EPL 2.0 | 38.6k lines, 1775 commits, 174 in the last 12 months, 145 of those by one author, 6 contributors, EPL 1.0 |

## 2. Where Datahike would hurt loci

**~~An event is not expressible as datoms.~~ Corrected 2026-08-08 by measurement — see the
bench doc.** Datahike *does* have `:db.type/bytes` (it is missing from `doc/schema.md`'s type
table, which is what produced the error). All 3,000 benchmark events round-tripped through
nippy into a byte-array datom with zero mismatches, including the 134 KB value. There is no
`pr-str` in that path, so the 2026-08-05 data-loss class does not return.

What survives of the objection is sharper. A nippy byte array is **opaque to datalog**: you
would take on the engine and still not be able to query inside an event. The three real
options are therefore:

1. **Decompose each event into datoms.** The only one that buys the query power; it changes
   what an "event" is, and `undo!` stops being "delete key n".
2. **A byte-array datom** (what the benchmark did). Verbatim, safe, and query-invisible.
3. **`:db.type/store-ref` blobs.** Content-addressed via `hasch`, written with konserve's
   `bassoc`, time-travel-safe by construction (`blob.cljc` explains why a mutable pointer
   would silently break `as-of`) — the right shape if the values were much larger.

Option 2 is what a port would actually do, and it means the datalog engine is paid for and
not used.

**Small, frequent commits are the worst case for persistent trees.** loci commits once per
user gesture. Datahike's own `doc/write-amplification.md` puts a small commit at roughly
`depth + 1` node objects *per index*, plus a commit record and a branch-head record —
multiplied again when `:keep-history?` is on. It offers three opt-in mitigations
(`:diff-buf-size`, `:fuse-index-roots?`, `:commit-graph? false`) and says they matter most
on object storage and "change little" on a local filesystem. LMDB writes the dirty pages of
one transaction and fsyncs once.

**Garbage collection becomes an operational duty.** Structural sharing means superseded
snapshots accumulate until `d/gc-storage` sweeps them, with grace-period semantics to get
right. LMDB reuses free pages with no operator in the loop.

## 3. Where Datahike would help loci

**The concurrency defect loci already suffered would be structurally impossible.**
Datahike serializes every transaction through one writer thread and a queue
(`writer.cljc`). loci found concurrent `commit!`s destroying 208 of 900 events and fixed it
with a hand-rolled `locking` — correct, but it is loci's invariant to keep, and every new
writer (`rebuild-indices!`, a future migration) has to remember it.

**Branching is a surprisingly exact fit for the thesis.** loci's pitch is that everything
the agent does is reversible and gated. Datahike branches a whole database in near-constant
time through structural sharing (`doc/versioning.md`, beta): an agent flow could run *on a
branch* and merge on approval, rather than running against truth and being undone
afterwards. Datalevin has no analogue.

**The shell could hold a queryable replica.** Datahike runs in ClojureScript on Node and in
the browser over IndexedDB (`doc/cljs-support.md`, beta). Today every LEAP keystroke is an
HTTP round-trip to the Clojure server. Datalevin's answer to the same need is a client/server
protocol — the opposite direction.

**Both cover the unbuilt phases 2 and 3 — but not equally.** Datalevin's full-text and
vector indices live in the same LMDB environment and the same transaction as the events.
Datahike's are three optional libraries (`scriptum`, `proximum`, `stratum`) behind an
infrastructure whose own doc says *"Status: Experimental — the secondary index
infrastructure and all integrations are functional but may receive breaking API changes"*,
and whose worked example needs a `Thread/sleep 1000` for the async backfill.

## 4. Measured

A loci-shaped benchmark was run separately: 3,000 events with the real size distribution
(including a 134 KB value), one durable commit per event, cold replay, an `object-at`-shaped
read, on-disk size and dependency footprint, on OpenJDK 26.0.2.

**See `docs/superpowers/specs/2026-08-08-datahike-vs-datalevin-bench.md` for the numbers and
the raw harness.** They are kept separate because they age differently from the analysis:
the argument above is about shape, and shape changes slowly; timings are about two specific
versions on one machine.

For reference, the 2026-08-05 design measured Datalevin at **0.030 ms per durable commit**
and 227 ms to boot 10k events, against the EDN log's 0.231 ms and 353 ms.

## 5. Verdict

**Staying on Datalevin is right, but not for the reason the 2026-08-05 design gives.**
That doc decided on feature count in one dependency. The actual reason is that loci's
binding is LMDB-shaped in four specific ways — nippy values, one atomic transaction across
four dbis, a list-valued dbi with a bounded range scan, and fsync-per-commit — and Datahike
provides none of those as such. A port would not be a rewrite of `dlv.clj`; it would be a
redesign of what an event is.

**The question the design never asked is the inverse one.** loci keeps its own event log,
its own projection, its own vector search and its own recall. It is therefore paying for a
datalog engine it does not use. That is a defensible bet — you are paying for LMDB and
getting datalog free when phase 2 arrives — but the "three indices in one dependency"
argument that settled the matter is an argument about a future that has not been built.

**What would justify revisiting.** Only these, and none of them is on the roadmap today:

- the browser shell needing to query the substrate locally rather than over `/api`;
- wanting agent flows to run on a *branch* of the substrate and merge on approval, instead
  of running against truth and relying on `undo!`;
- multi-machine or multi-user use, where Datahike's many-readers-one-writer storage model is
  a better fit than a KV file plus a server process.

Absent those, the cost is a redesign of the event and the gain is a query engine loci has
not yet asked for.

## Appendix: what each project actually ships (read from source, 2026-08-08)

**Datalevin 1.0.0** — `datalevin/{core,kv,lmdb,db,storage,query,query_optimizer,search,
analyzer,stem,vector,embedding,llm,mcp,txlog,ha,replica,server,remote,client,csv,dump,
migrate}.clj` — 104 namespaces. Notable in 1.0: a WAL (`txlog` with codec, segments,
recovery, sync state), Raft-based HA, an MCP server, and built-in embedding generation.
Transitive deps include jraft, nippy, eclipse-collections, JavaFastPFOR, sci, bouncycastle,
and per-platform `dtlvnative` artifacts (3.7 MB for linux-x86_64 alone).

**Datahike main** — `datahike/{api,core,db,query,index,writer,writing,gc,online_gc,
versioning,blob,optimistic,audit,bitemporal,http,js,pod,cli,migrate,norm}` plus
`src-secondary/datahike/index/secondary/{scriptum,proximum,stratum}.clj`. Notable since
0.8: the compiled query planner is now the default engine, git-like branching (beta),
Distributed Index Space, write-amplification controls, GDPR purge + GC, ClojureScript on
Node and browser, and a JSON/HTTP server. Deps are pure-JVM: konserve, hasch,
persistent-sorted-set, superv.async, malli, RoaringBitmap, clj-cbor.
