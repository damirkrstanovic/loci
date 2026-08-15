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

**1. Minting reads RAM, not the store.** `memory/next-id` (memory.clj:184) is "one past the
largest id ever issued", computed from an in-RAM map, and `remember` took no lock at all —
so two *threads* in one process mint the same id, and `spit :append true` with
last-wins-by-`:id` destroys the loser with no error anywhere. Measured: 20 topics × 10
concurrent `remember`s left 98, 90 and 103 facts where 20 was correct. Same failure class
as the 2026-08-09 forensics, where concurrent `commit!`s destroyed 208 of 900 events; the
substrate got a lock for it and the memory never did.

This is a single-process bug and is worth fixing on those grounds alone. It is *not* an
argument for cross-process coordination — see "Who may write" below, which says plainly
that loci has one writer and that taking the app down is the mechanism for anything else.

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

## Who may write: one process, three ways in

**loci is single-writer by design.** One process owns the store. There is no supported
configuration in which two processes hold it open, and nothing here tries to make that
safe — `compose.yaml` runs one container, `:serve` runs one server, and no part of the
codebase coordinates a second writer.

Storage is reached in exactly three ways:

1. **From the running app, over its HTTP API.** The normal path. `/api/*` is the surface;
   the server owns the store behind it.
2. **From a `loci.*` namespace, by loading the facade and calling it in the server's own
   process.** `loci.corpus/import-corpus!`, `loci.conversation/import-conversation!`,
   `loci.factmigrate/migrate-facts!` are public functions with dry-run defaults, meant to
   be called from a REPL attached to the live server — not from a second JVM.
3. **Directly on storage, with the app stopped.** Surgery — a migration, a repair, a
   renumbering — takes the store offline first. Stopping the app is the mechanism; there
   is no lock to acquire and none is wanted.

What this rules out is the fourth way, which is the one that caused trouble on
2026-08-15: a throwaway script opening the store in its own JVM while a server holds it.
That is not a scenario to be made safe. It is a mistake, and the answer is (2) or (3).

### What this means for the id counter

An earlier draft of this spec promised that a monotonic counter in `meta` would make
"don't run two writers" a *guarantee* rather than a convention. That promise is withdrawn:
it was inventing a requirement, and then engineering for it.

`DbiLog` nevertheless mints ids inside `datalevin.core/with-transaction-kv`, and would do
so even if only one thread ever ran. The reason is not concurrency across processes — it is
that read-a-counter-then-write-it-back is a transaction, the database performs
transactions, and the version it replaced was a hand-rolled `(locking lock)` over a plain
`(Object.)` doing the read and the write in two separate LMDB transactions. The native
form is both correct and smaller: it deleted a record field, a monitor, and a class of bug.

Single-writer is a statement about how loci is *operated*. Using the database's own
transaction for a read-modify-write is just how you write that code.

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

1. Concurrent `remember`s **in one process** do not lose a fact. Run it; report the
   numbers. Deliberately threads, not processes: loci is single-writer, so a second JVM is
   not a scenario under test.
2. `undo!` of a notebook leaves the facts citing it intact.
3. `as-of` an earlier index does not change what `recall` returns.
4. Recall over the migrated store returns the same ranked ids as over `memory.edn`.
5. Migration is idempotent — a second run writes nothing and says so.
6. Migration refuses a file with a fact carrying no `:id`.
