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

## Cross-process: measured, not achieved

Measured 2026-08-15 with `dev/two_writers.clj`, two real `clojure -M` OS processes against
one fresh `mktemp -d` directory, each calling `mold/remember` 100 times with sentences
unique to its own tag (pairwise jaccard 0.3333, well under the 0.6 reinforcement threshold —
measured with the same `tokens`/`jaccard` this harness writes with, not assumed). **The
claim is false.** Two writers lose facts, and the way they lose them is worse than the
single mechanism the design above worried about — there are two distinct failure modes, and
the first one is more severe than the mint-id race that motivated this section.

### Failure mode 1 — concurrent environment creation corrupts the store

Exactly per Step 2/4 of the task: two processes each call `(dlv/datalevin-store dir)`
against a directory that does not yet hold an LMDB environment, at (as close as `&`/`wait`
gets) the same moment.

| run | outcome |
|---|---|
| 1 | clean — 200/200 (100 tag a / 100 tag b), `:facts/next-id` = 200 |
| 2 | **corrupted** — writer `b`'s own `(dlv/datalevin-store dir)` call throws before writing anything |
| 3 | **corrupted** — same, writer `b` |
| 4 | **corrupted** — same, writer `b` |
| 5 | **corrupted** — same, writer `a` this time (confirms it is whichever process loses the race, not a tag-specific bug) |

The exception is always the same, and it fires at `dlv.clj:79` (`read-log`) — i.e. inside
`datalevin-store`'s own construction, before the harness prints "writing N facts", so no
`remember` has run yet:

```
Execution error (ExceptionInfo) at loci.dlv/read-log$fn (dlv.clj:79).
substrate event keys are not contiguous — expected 1, found 999. The events are intact;
they must be renumbered 1..n before this log can be replayed.
```

`999` is garbage: nothing in this harness, or in `DbiLog`, ever writes to the `events` dbi —
only `facts` and `meta`. **This is not a transient hiccup.** Re-opening the same directory
from a clean third process, well after both writers had exited, reproduces the identical
error on all four runs — the on-disk environment is durably corrupted, not just the losing
process's read. Losing this race does not cost one fact; it costs the whole store.

This matches a real LMDB caveat, not a Datalevin-specific bug: an LMDB environment that does
not yet exist must be created by one opener before others may safely open it; concurrent
*first* creation is unsafe in a way concurrent *use* of an existing environment is not. The
follow-up measurement below confirms this directly — the exact same two writers, against a
directory whose environment was pre-created by one process and closed first, corrupted
nothing in three runs.

### Failure mode 2 — the mint-id race, isolated

Same two writers, same corpus, but the store's environment was created once by a single
process (`open` then `close!`) *before* the two writers were launched — isolating the
read-then-write race in `DbiLog/mint-id!` (dlv.clj) from failure mode 1 above.

| run | total | tag a | tag b | `:facts/next-id` | writer exit codes |
|---|---|---|---|---|---|
| 1 | 200 | 100 | 100 | 200 | 0 / 0 |
| 2 | 200 | 100 | 100 | 200 | 0 / 0 |
| 3 | **181** | **92** | **89** | 181 | 0 / 0 |

Run 3 lost 19 of 200 facts — **silently**. Both processes reported `remember` called 100
times and exited 0; nothing anywhere logged an error. Writer `b`'s *own* in-RAM view (its
local `!facts`, built only from its own writes) already showed 99 distinct ids among its own
100 calls, meaning `b` reused one of its own minted ids without any help from `a` reading at
the same instant it did. The mechanism is not merely "both processes read the same counter
value" — it is that `mint-id!` computes its new value from a read and then writes that fixed
value unconditionally, so **the counter can move backward**: if `a` reads `n=41` at T1 and
`b` reads `n=41` at T2, and `b`'s write (`meta=42`) lands before `a`'s does, then `a`'s write
still unconditionally sets `meta=42` — regressing what `b` had already advanced to 43 back
down to 42, and handing `mem-42` out a second time. `DbiLog.mint-id!`'s `(locking lock)` is a
JVM monitor and synchronises nothing across the two JVMs; LMDB's writer mutex serialises the
two `transact-kv` calls but not the `get-value` that preceded each one, exactly as this
section already suspected of `DatalevinStore.commit!`.

### What this means

"No loss observed in N runs" and "cannot lose" are different claims, and the evidence above
supports neither in the strong direction — it supports the opposite. Two of eight total runs
(1 of 5 fresh-directory, 1 of 3 pre-existing-store) lost data outright; four more corrupted
the store before a single fact was written. **The cross-process guarantee this section
proposed does not hold as designed.** A counter in `meta`, read via `get-value` and written
via `transact-kv` as two separate calls, is not atomic across processes regardless of the
in-process lock around it — same failure class as `DatalevinStore.commit!`'s `(inc (count
log))`, and now demonstrated rather than argued.

Per the design above, the fallback holds: **a second writer must fail loudly rather than
silently corrupt.** Today it does neither reliably — it usually succeeds, sometimes loses
facts with no error, and sometimes corrupts the environment outright. Loud failure (e.g. a
pid/lock file a second opener refuses to proceed past) is strictly better than the current
state and does not require solving distributed consensus.

### A native primitive that might close it — not implemented, not measured

Datalevin exposes `datalevin.core/with-transaction-kv` (public macro, `datalevin/lmdb.clj`,
imported at `datalevin/core.clj:328`), which runs a body inside **one** LMDB read/write
transaction rather than two. Its own docstring's example is this exact shape:

```clojure
(with-transaction-kv [kv lmdb]
  (let [^long now (get-value kv "a" :counter)]
    (transact-kv kv [[:put "a" :counter (inc now)]])
    (get-value kv "a" :counter)))
```

The macro locks on `(write-txn orig-db#)` before opening the transaction, and `open-env!`
(dlv.clj:52) opens with Datalevin's default env flags (`#{:nordahead :notls}` —
`:nolock` is not among them), so the write-transaction acquisition goes through LMDB's
ordinary cross-process writer lock rather than bypassing it. If `mint-id!` read and wrote
the counter inside one `with-transaction-kv` instead of a bare `get-value` + `transact-kv`
under a JVM `locking`, the read and the increment would be one LMDB transaction and the
regression described above should not be possible.

This is a **finding, not a fix**: `with-transaction-kv` has not been used anywhere in this
codebase, and nothing above has implemented or measured it against two real processes. It
does not touch failure mode 1 (environment creation) at all — that needs the store to
already exist before a second process opens it, which is an operational rule, not a code
change. Whether it fully closes failure mode 2, and at what cost (a `with-transaction-kv`
still has to have somewhere to fail loudly if failure mode 1's precondition is violated), is
future work.

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
