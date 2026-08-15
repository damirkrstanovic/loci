# One substrate: memory facts into the Datalevin environment

Date: 2026-08-15
Status: **design, awaiting approval.** No code written.

## The complaint

`data/` holds one database and two flat files beside it:

```
data/substrate/data.mdb    988K   LMDB, indexed, transactional
data/memory.edn            8.5M   append-only text, re-folded on every boot
data/chunks.edn             16M   ditto
```

The two files are twenty-five times the size of the database they sit next to, and they
are the ones that are not in it. "Substrate" is supposed to mean one substrate.

The project's own spec already said so. `2026-08-05-datalevin-substrate-design.md` § 1:

> One LMDB environment under `data/`, several dbis. Substrate and memory never share a
> transaction — the record/recall separation is a rule about writes, not just about files.

"Never share a transaction" is a rule about **writes**. It does not require a second
storage technology, and the sentence says as much — *not just about files*. What shipped
put memory outside the environment anyway.

## What the split actually cost, with evidence

Three things, all observed on 2026-08-15 while importing a Gemini conversation.

**1. Id minting has no single authority.** `memory/next-id` (memory.clj:184) is "one past
the largest id ever issued", computed from an in-RAM map. Two processes hold two maps and
mint the same id; persistence is `spit :append true` with last-wins-by-`:id`, so the loser
is destroyed with no error anywhere. This is the same failure class as the 2026-08-09
forensics — concurrent `commit!`s destroying 208 of 900 events — which the substrate fixed
with a lock and the memory never got. Today it forced a running server to be stopped
before 106 facts could be written, and "stop the server first" is not a mechanism.

**2. Backup is ambiguous.** The substrate is a directory, the memory is a file, the chunks
are another file, and `migrate.clj` touches none of the latter two. "I backed it up" covers
one of three things and nobody can tell which. `data/` currently carries three
`memory.edn.bak-*` files, taken by hand, on three different dates.

**3. Boot re-parses 25 MB.** Both files are append-only logs folded last-wins on load. The
substrate solved this with indices and an atom published in one write; the memory re-reads
its whole history every time a JVM starts.

## What stays separate, and why

The separation that matters is **semantic, not physical**:

- `undo!` must never revoke a fact. Undoing a notebook edit cannot un-learn something.
- `as-of` must never time-travel the memory. If it did, recall in the past would answer
  differently, and the memory pane would show a history that never happened.
- Facts decay, merge and are revisable by the agent. Events are yours and are immutable.

All three are properties of *which log a write goes to*, not of *which file it lands in*.
Separate dbis in one environment give every one of them, because `undo!` and `as-of` read
`events` and nothing else. That is precisely the spec's "never share a transaction".

## Target layout

One environment, one more dbi:

| dbi | kind | contents | undo? | as-of? |
|---|---|---|---|---|
| `events` | KV `:long` | the truth — substrate events | yes | yes |
| `counts` | KV `:long` | derived, rebuildable | — | — |
| `touched` | list `:string` | derived, rebuildable | — | — |
| `meta` | KV | dirty mark, and now the fact counter | — | — |
| **`facts`** | **KV `:string`** | **one record per fact id — parallel truth** | **never** | **never** |

`dlv/open-env!` (dlv.clj:56) already opens four dbis in four lines; this is a fifth.

## The crux: where a fact id comes from

This is the decision the whole change turns on, and it is worth being exact about, because
moving the bytes does not fix it by itself.

`DatalevinStore.commit!` (dlv.clj:119) computes the next event index as `(inc (count log))`
from the in-RAM log, under `locking lock`. That serialises writers **within one process**
and is why the 208-event bug is fixed. It does **not** serialise two processes: LMDB's
writer mutex orders the two `transact-kv` calls, but both computed `i` from their own stale
snapshot, so the second overwrites the first. The substrate has the same theoretical
exposure as the memory; it survives because exactly one process opens it.

So the honest claim for this change is:

- **In-process:** one lock, one authority — the memory gains what the substrate already has.
- **Cross-process:** a monotonic counter kept in `meta`, read and incremented inside the
  same write path rather than derived from RAM. This is new, and it is the part that makes
  "don't run two writers" a guarantee instead of an operating convention.

If the counter proves awkward, the fallback is to make a second writer *fail loudly* rather
than silently, which is still strictly better than today. **Either way, this must be
demonstrated with two concurrent processes, not argued for** — per the working agreements,
a reviewer runs it and reports numbers.

## Migration

Shaped like `loci.corpus`: dry-run by default, refusals as returned reports, nothing
committed until the whole world is checked.

1. `read` `data/memory.edn` by the existing loader — last-wins by `:id`, tombstones kept.
2. Refuse unless every fact has an `:id`, and the count matches the loader's own map.
3. Transact all records into `facts` in one `transact-kv`.
4. Verify by reading back: same ids, same `:fact` text, same `:vec` presence, same count.
5. Leave `memory.edn` on disk untouched as the rollback. Rename it only after a
   subsequent session confirms the store.

Reversal is deleting the `facts` dbi and pointing the config back at the file, which is why
step 5 keeps the file rather than deleting it.

## What must not change

- `mold/Recall` — `remember` / `recall` keep their signatures. `FileMemory` stays for tests
  and `loci.calibrate` (which requires a path and reads only, deliberately).
- Recall scoring — jaccard, decay, strength, semantic fusion, thresholds. This change moves
  storage and touches no ranking. A recall over the migrated store must return the **same
  ranked ids** as over the file, and that is the acceptance test.
- `undo!` and `as-of` — untouched, and a test must prove a fact survives an `undo!` that
  takes back the notebook it cites.

## Scope

**Phase 1 — memory.** The one with the correctness hazard. 248 facts today.

**Phase 2 — chunks.** Bigger on disk (16 MB) but derived and rebuildable: `chunks.edn` can
be deleted and re-swept byte-equivalently, so there is no data to lose and no id to
collide. Lower risk, lower urgency, and a separate deliverable.

Phase 2 does not start until Phase 1 is merged.

## Tests that must bite

Per the working agreements, each of these must be shown to fail when its fix is reverted:

1. Two processes writing facts concurrently do not lose one. Run it; report the numbers.
2. `undo!` of a notebook leaves the facts citing it intact.
3. `as-of` an earlier index does not change what `recall` returns.
4. Recall over the migrated store returns the same ranked ids as over `memory.edn`.
5. Migration is idempotent — a second run writes nothing and says so.
6. Migration refuses a file with a fact carrying no `:id`.
