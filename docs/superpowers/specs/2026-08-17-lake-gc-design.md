# Per-table lake GC — design

**Status:** built and merged in `bc07f18` on 2026-08-18. Manual per-table reports and
reclaim work. Automatic catalog snapshot expiry remains off because it can break live handles.
The body below records the settled design and its measurements from 2026-08-17.

**Goal:** a user can see how much space each table's historical versions occupy, and reclaim
them one table at a time, without losing anything they have not been told they are losing.

---

## Why this exists

Table rows moved out of the substrate's event log into a DuckLake catalog on 2026-08-16. Writes
never overwrite: every `put-rows!` mints a **new lake table**, so a table refreshed ten times
leaves ten lake tables on disk. Nothing reclaims them, and `lake/sweep!` returns
`{:mode :disabled}`.

At the size loci's fixture happens to be, that costs nothing — the whole lake is 117 KB of
Parquet. **That is not the case this is for.** DuckLake was chosen for tables of millions of
rows, where one 10M-row table is ~107 MB *per version*; ten refreshes retain a gigabyte. The
fixture's scale says whether the mechanism works, never whether the cost is real. (See
`CLAUDE.md`, "And its *scale* is not evidence about anything" — that rule was written because an
earlier draft of this very design was dismissed by quoting the fixture back at it.)

## What was measured, 2026-08-17

Full campaign in the DuckLake tables plan's open-questions section. The load-bearing results:

**DuckLake's own cleanup cannot do this.**
- `DROP TABLE` is a *versioned tombstone*. It frees **zero bytes**, and the dropped table's own
  handle still reads, because that handle names a version at which the table existed.
- `ducklake_cleanup_old_files` will not touch a file until **every** snapshot below its
  `end_snapshot` is expired. Measured: expiring one snapshot freed 0 bytes; expiring snapshots
  1–5 freed the 915,918-byte file **and broke three unrelated live handles**.
- Snapshots are catalog-wide, so "expire enough to free A's old version" always means "expire the
  snapshots B and C read at". There is no per-table expiry. This path is dead.

**`DROP TABLE` plus deleting that table's own Parquet files works.** Measured end to end: exact
bytes freed, **zero snapshots expired**, every other handle still reading, catalog healthy across
a close/reopen, and a later `cleanup_old_files` tolerant of the already-missing file.

**Two constraints that path must respect.**
- **Delete individual files, never the directory.** `restore-to!` is `CREATE OR REPLACE`, which
  mints a new `table_id` under the *same* `table_name` and directory — one directory was observed
  holding a dead and a live Parquet. The safe unit is the individual `ducklake_data_file.path`.
- **Some tables have no Parquet at all.** 8 of 31 live tables (3–8 rows each) are inlined into
  `catalog.duckdb` as `ducklake_inlined_data_*`. Reclaim must report 0 for them, not error.

**The failure mode today is a hard throw.** A handle whose file is gone makes `hydrate`,
`mold-payload`, `query_table` **and `all-chunks`** raise
`IO Error: Cannot open file "…parquet": No such file or directory` (`java.sql.SQLException`).
`all-chunks` is whole-substrate, so one such table stops semantic-recall indexing for **every**
object. `list_tables` does the opposite — since Task 11 it reads the handle, so it cheerfully
reports the old row count for a table with no rows left.

**`undo!` deletes the event.** `loci.dlv`'s `undo!` transacts a delete by key and pops the
in-RAM log, so an undone write's handle is gone from the log entirely. Its lake table is
referenced by nothing and can never be referenced again.

**Orphans can also be created by a crash.** A `put-rows!` whose caller dies between DuckLake's
commit and `sub/commit!` of the event leaves a fully-committed, permanently unreferenced table.
Reproduced accidentally during the campaign. It cannot be closed by making the two writes atomic
— they are different stores — so it is an argument for orphan cleanup, not a bug with a local
fix. Note `ducklake_delete_orphaned_files` does **not** help: the table is properly in the
catalog; it is loci that has no reference to it.

---

## Decisions

**1. Four classes, computed from the log.**

| class | definition | treatment |
|---|---|---|
| **current** | named by a handle on a live object | never touched |
| **in-window** | superseded, inside the keep-window | kept — undo and `as-of` stay exact here |
| **reclaimable** | superseded, outside the window | reclaimed on request |
| **orphaned** | named by no handle anywhere in the log | reclaimed regardless of window |

Orphans ignore the window because there is nothing to protect: nothing can reference them again.

**2. The keep-window is `max(3 versions, 30 days)`.** Whichever keeps more. A version count alone
would drop recent history on a busy day; an age alone puts no ceiling on a table rewritten forty
times in an afternoon. Taking the larger keeps recent work under either reading, and still bounds
a table once it goes quiet.

**3. Reaching a reclaimed version degrades per object, never per operation.** This is the
governing requirement. An `as-of` or an `undo` that lands on a state containing one reclaimed
table must **complete**: every other object answers normally, and that one renders as reclaimed.
The reclaimed object still shows its column names and row count, because both live on the handle
and survive the rows.

**4. Nothing new is stored.** DuckLake's catalog already records the drop —
`ducklake_table.end_snapshot` is set, and `ducklake_snapshots` carries that snapshot's timestamp.
So "was this reclaimed, and when" is a query against the store that did the reclaiming.

The read path is: attempt the read; **on failure** ask the catalog whether that table was dropped;
if so it is `reclaimed on <date>`, and if not it is a genuine error worth surfacing. Zero cost on
the happy path, and it distinguishes a reclamation from a failing disk.

A sidecar was considered and rejected twice over. It would be a second source of truth for
something the catalog already knows — and the precedent it would have followed no longer exists:
`chunks.edn` and `memory.edn` were both migrated into Datalevin dbis (`chunks`, `facts`), and
only the migration routes remain in the source. If loci-side state is ever needed here, it is a
dbi in the existing environment, not a file.

**5. Manual only.** `lake/sweep!` stays disabled. Automatic policy-driven GC is a different
feature and inherits the upstream expiry bugs; this is user-initiated and per-table.

---

## Architecture

Two phases. **Phase 1 is worth shipping alone** — it makes the system resilient to an unreadable
table however that arose, and it is what makes Phase 2 safe.

### Phase 1 — readers tolerate a missing table

No GC. Six reader sites stop throwing and start degrading, each for one object only:

| site | today | required |
|---|---|---|
| `tbl/hydrate` | throws `SQLException` | returns the object marked reclaimed |
| `srv/mold-payload` | throws → `/api/mold` 500s | renders it as reclaimed |
| `ch/all-chunks` | **whole sweep aborts** | that object loses its chunk; the sweep completes |
| `srv/agent-ctx`, `ask!` digest | throws | that table omitted, rest of the context intact |
| `tools/query_table` | throws into the agent's tool loop | `{:error "reclaimed …"}` the model can act on |
| `tools/list_tables` | reports a stale row count | says reclaimed |

The marker carries the drop date from the catalog, so the object can say *when*.

### Phase 2 — classification, reclaim, surface

**`src/loci/lakegc.clj`** — the whole feature's logic, and the only new namespace.
- `report` — walks the log once, asks the catalog for file sizes in one query, returns the four
  classes per table with byte counts. **Hydrates nothing** (Task 11's lesson: a storage panel that
  costs 277 ms defeats itself).
- `reclaim!` — one table. `DROP TABLE`, then delete that table's own `ducklake_data_file` paths.

**`src/loci/lake.clj`** gains the primitives it owns the connection for: dropping a table,
listing a table's files, and asking whether a table was dropped and when.

**`src/loci/server.clj`** gains two routes:
- `GET /api/storage` → the report. **The GET is the dry run**; there is no `:live?` flag
  pretending otherwise.
- `POST /api/storage/reclaim {:id "tbl:orders"}` → does one table, returns what it freed.

```clojure
;; GET /api/storage
{:window {:versions 3 :days 30}
 :lake   {:files 117278 :catalog 6565888 :total 6683166}
 :tables [{:id "tbl:orders" :title "E-commerce orders"
           :current 4200000 :versions 10 :in-window 3
           :reclaimable 29400000 :reclaimable-versions 7
           :orphaned 0 :inlined? false}]}
```

**The shell panel** lists tables with their reclaimable bytes and a per-row reclaim control.

It **must show the catalog line honestly.** On the current store the catalog is 6.5 MB against
117 KB of Parquet — reclaim everything and the directory barely moves. A panel showing only
reclaimable bytes would make that read as a bug. Total, reclaimable, and
catalog-that-never-shrinks are three separate numbers.

---

## Safety

**Reclaim is irreversible and not undoable.** It deletes files. The endpoint says so plainly.

**One trap to close by test.** A dropped table can be recreated by name from an old version —
`CREATE TABLE t AS SELECT * FROM t AT (VERSION => n)` succeeded during the campaign. Because the
Parquet is deleted too, that recreate now fails; but a test must prove it, or a future undo could
*appear* to restore rows that are gone.

**One race stated rather than engineered around.** `as-of` reading a superseded version while
reclaim deletes it. loci is single-writer and this needs one user doing both at once. A sentence
in the docstring, not a lock.

---

## Testing

Every guard gets a sabotage. Beyond that:

- the window is honoured **exactly** — keep 3 leaves exactly 3, and the 4th is gone
- an orphan constructed via `undo!` is found and reclaimed
- **bytes freed equal what the report predicted**
- **snapshot count is unchanged**, proving `expire_snapshots` was never called
- every other table's handle still reads afterwards, including at older versions
- an inlined table reports 0 and does not error
- recreating a reclaimed table by name **fails**
- **one reclaimed table plus `all-chunks` still returns chunks for every other object** — the
  governing requirement, and the assertion most likely to be quietly weakened
- `report` fetches no rows (stub `tbl/rows` to throw, as Task 11's tests do)

---

## Out of scope

- Automatic or scheduled GC. `sweep!` stays disabled; its docstring is updated to point at the
  manual path rather than implying nothing exists.
- Reclaiming catalog space. `catalog.duckdb` never shrinks and is 98% of the lake at fixture
  scale. That ratio inverts at the sizes this feature is for, but shrinking the catalog is a
  separate problem with a separate mechanism.
- Cross-table or global "reclaim everything" — per-table is the stated ask, and a global button
  over an irreversible operation is a different risk conversation.

## Open questions

- **Where the window is configured.** A global default is assumed. Whether it becomes per-table
  is deferred until someone wants a different answer for one table — the report makes that
  visible before it becomes a guess.
- **Whether the panel should offer "reclaim all reclaimable".** Deliberately left out; revisit if
  reclaiming table-by-table proves tedious against a real workspace.
