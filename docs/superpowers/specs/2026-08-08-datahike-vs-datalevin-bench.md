# Datahike vs Datalevin, measured on a loci-shaped workload

Date: 2026-08-08
Status: **PARTIAL — the run was stopped at 38 minutes.** The Datahike side completed
(3 variants × 3 runs); the Datalevin side has 3 surviving latency runs and no cold-open,
`object-at` or crash-probe results. Several probes never ran. Everything below is what
actually survives on disk; nothing is estimated.

## The caveat that governs every number here

**The stores were written to `/tmp`, which on this machine is `tmpfs` — RAM.** `fsync` is
therefore a no-op for both engines, and none of these figures measure durability cost. They
measure structure and CPU: how much work each engine does per commit, how many objects it
writes, how much it writes.

This does not invalidate the comparison, but it changes its meaning. On a real disk both
engines get slower; Datahike writes ~20 files per commit against LMDB's pages in one mapped
file, so the gap would be expected to *widen*, not narrow. That is reasoning, not a
measurement — the measurement was not run.

## Workload

3,000 events in loci's shape: mostly small `{:op :put :id … :value {…}}` maps, `:tx` events
touching several ids, `:assoc` events with a path, and **11 large values up to 134 KB**
(matching the real corpus, where 11 of 60 objects exceed 4 KB). Touched ids are Zipf-ish over
~400 objects, so a few ids are touched by hundreds of events. One durable commit per event.
Projection ends at 376 objects. OpenJDK 26.0.2, `--enable-native-access=ALL-UNNAMED`.

**Datalevin** ran loci's own `loci.dlv` unmodified — the production path, `events` + `counts`
+ `touched` in one `transact-kv` per commit.

## The Datahike schema, and a correction to the analysis

The harness found what the documentation's type table does not list: **Datahike has
`:db.type/bytes`.** From the harness's own comment:

> `:event/value` `:db.type/bytes`, nippy — the only Datahike value type that can hold an
> arbitrary nested Clojure map. There is no `:db.type/edn` / `:db.type/map`; and a RAW map
> value breaks the index comparator (`datahike.datom/compare-value` bottoms out in
> `clojure.core/compare`, which throws on maps). Byte arrays are safe there — `compare-value`
> routes them to `datahike.array/compare-arrays`.

```clojure
[{:db/ident :event/idx     :db/valueType :db.type/long   :db/cardinality :db.cardinality/one :db/index true}
 {:db/ident :event/value   :db/valueType :db.type/bytes  :db/cardinality :db.cardinality/one}
 {:db/ident :event/census  :db/valueType :db.type/bytes  :db/cardinality :db.cardinality/one}
 {:db/ident :event/touched :db/valueType :db.type/string :db/cardinality :db.cardinality/many :db/index true}]
```

**All 3,000 events round-tripped with 0 mismatches, including the 134 KB value.** So the
analysis doc's claim that loci's events are not expressible without `pr-str` or a blob store
is **wrong, and is corrected there**. Datahike can hold them verbatim.

What survives of that argument is different and sharper: a nippy byte array is **opaque to
datalog**. You would take on the engine and still not be able to query inside an event — the
query power that motivates the move stops at the blob boundary.

## Append: one durable commit per event, 3,000 events

| engine / variant | p50 | p95 | p99 | max | total |
|---|---|---|---|---|---|
| **Datalevin** (run 1) | **0.299 ms** | 0.826 | 1.247 | 24.28 | **1.12 s** |
| **Datalevin** (run 2) | 0.326 ms | 0.883 | 1.308 | 19.68 | 1.21 s |
| **Datalevin** (run 3) | 0.312 ms | 0.907 | 1.606 | 24.29 | 1.22 s |
| Datahike `:opt` | 3.308 ms | 5.337 | 6.909 | 27.80 | 10.82 s |
| Datahike `nohist` | 5.692 ms | 8.938 | 12.698 | 39.75 | 18.30 s |
| Datahike `hist` (run 1) | 6.645 ms | 10.250 | 17.960 | 85.58 | 21.92 s |
| Datahike `hist` (run 2) | 7.315 ms | 11.131 | 20.682 | 104.83 | 23.77 s |
| Datahike `hist` (run 3) | 8.200 ms | 13.540 | 26.444 | 359.37 | 28.11 s |

Datalevin's maxima are first-write JIT (the first event costs ~9-10 ms in every run).
Datahike's `hist` p50 drifts upward across its three runs (6.6 → 7.3 → 8.2 ms) — the tree
deepens as the log grows, and the run-3 max of 359 ms is a rewrite the others did not hit.

`:opt` = `:keep-history? false` + `{:diff-buf-size 256}` + `:fuse-index-roots? true` +
`:commit-graph? false`.

## Disk after 3,000 events — the finding of the run

| engine / variant | bytes | files |
|---|---|---|
| **Datalevin** | **9.79 MB** | **4** |
| Datahike `:opt` | 49.2 MB | 474 |
| Datahike `nohist` | **3.10 GB** | 32,606 |
| Datahike `hist` | **6.15 GB** | 61,093 |

The payload is ~10 MB. Datahike with default settings wrote **600×** that, in 61,093 files —
about 20 files per commit, which is exactly what `doc/write-amplification.md` predicts
(root-to-leaf path per index, ×4 indices with history, plus two records). Its own mitigations
are not marginal on a local filesystem, as the doc suggests: they take it from 6.15 GB to
49 MB, a **125× reduction**, at half the commit latency.

The likely mechanism — untested, because the diagnostic that would have confirmed it is the
one that crashed (below) — is that the 134 KB byte arrays sit inline in index leaves, so any
commit touching a leaf rewrites the blob with it.

## Reads

| | Datahike `hist` | Datahike `nohist` | Datalevin |
|---|---|---|---|
| cold open + ordered replay of 3,000 | 252 ms | 299 ms | *not measured* |
| `object-at` hot id, p50 (`:datoms`) | 0.263 ms | 0.313 ms | *not measured* |
| `object-at` hot id, p50 (`:q`) | 0.127 ms | 0.128 ms | *not measured* |

For context from the 2026-08-05 design, on the same machine: Datalevin booted 10k events in
227 ms, and `object-at` measured 0.03–0.23 ms. Datahike's read side is **not** the problem —
it is in the same range.

## What did not finish

- **Datalevin cold-open, `object-at`, disk-over-time and dependency-tree runs.** The store
  sizes above were measured post-hoc from the surviving directories; the rest is absent.
- **The `nohist-nolarge` diagnostic crashed** — `IllegalArgumentException` at
  `bench.dh-run/config`, a missing branch in the harness's own `case`. That was the run that
  would have shown how much of the 3.1 GB is attributable to the 134 KB values. **Unanswered.**
- **Crash/durability probes** (`crash-close`, `crash-halt`, `crash-noclose`, `crash-sig`
  stores exist) produced one unattributed line — `COMMITTED 200 dbi 200 / READY` — which
  cannot be tied to an engine from the surviving files. **The durability question is
  unanswered**, and on tmpfs it could not have been answered anyway.
- Only Datahike got 3 runs per variant; Datalevin's 3 runs are of one configuration.

## What it means for a single-user local-first app

Committing once per user gesture, Datalevin is **~20× faster per commit** (0.30 ms vs
6.6 ms default, 3.3 ms tuned) and writes **600× less** by default. Neither engine's read path
is a concern. Since loci's events are opaque nippy blobs either way, the datalog engine would
be paid for and not used — which is the same conclusion the analysis reached from the shape
of the code, now with numbers behind it.

Raw harness and logs: `<scratchpad>/bench/` — `results/dh-full.log`, `results/dh-full2.log`,
`stores/*/lat.edn`. They are outside the repo and will not survive the session.
