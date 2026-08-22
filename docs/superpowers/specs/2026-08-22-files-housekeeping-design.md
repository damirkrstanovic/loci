# The files directory learns housekeeping

Date: 2026-08-22
Status: **design settled, nothing built.** Three carry-overs from phase 2, found by its own
Definition-of-done walk and by the review of Task 4. Everything below is design except where it says
measured.

Phase 2 shipped and merged at `a2e5d93`: the shell can connect a file, upload one, and show a source
that broke. It left three things behind. None of them blocks phase 3, and all three get *harder*
once remote sources exist — which is why they go first.

## The want

| | |
|---|---|
| **connecting a file never warns you it is already connected** | `:duplicateOf` is computed in `import-csv!` (`server.clj:2206`) and nowhere else. Pasting the same CSV twice tells you; picking the same file twice from the ＋ Data panel silently makes a second table. |
| **`data/files/` only grows** | `import-csv!`'s docstring says so plainly. Deleting a table does not delete its file. Phase 2 added a route that fills the directory faster than paste did. |
| **the browser suite leaks 58 MB per server** | Measured during phase 2: **26 stale `libduckdb_java*.so`, 1.5 GB**, swept by hand at the end of Task 9. `deleteOnExit` cannot fire, because the harness kills the process group. |

## The rule

**Each of these is finished by a mechanism that already exists, not by a new one.** The duplicate
scan is written; the GC has a working precedent in `loci.lakegc`; the leak has a directory the
harness already removes. Where this design adds something, it is because two callers need one rule —
never because a rule was missing.

The corollary is the one thing to watch: `lakegc`'s `event-handles` walk must not be copied.

## 1. A duplicate is named before you connect, not after

`import-csv!` already scans and already answers:

```clojure
seen (->> (sub/objects st) vals
          (filter #(= uri (:uri (:source %))))
          (map :id) sort first)
```

**Reported, never refused**, and the existing comment settles why: *"two tables from one file is a
thing someone may well want, and loci's answer to a mistake is `undo!`, not a veto."* That does not
change. What changes is *when* you hear it.

**It lands on the listing, not on `describe`.** `describe` reads a file and answers with its columns;
giving it a store scan would make it two things, and the panel would learn about the duplicate only
after a click. `files/listing` already walks every file, so it gains **`:connectedAs`** — the first
table id bound to that uri in sort order, absent when there is none. First-in-sort rather than
oldest: that is what `import-csv!`'s `(map :id) sort first` already answers, ids are strings like
`tbl:sales` rather than numbers, and one call site changing the meaning of an answer both give is a
worse outcome than the answer being arbitrary among duplicates. The file tab shows it on the
row, before you pick, and the preview repeats it above the columns with Connect still enabled.

One request, no new route, and the scan is written once and called by both.

**A consequence worth stating**: `listing` stops being a pure directory read and starts needing the
store. That is the same widening `loci.files` took in phase 2 when it took ownership of the
directory, and it is the reason `listing` is the right home rather than the route.

## 2. A file loci wrote, that nothing has named for a while, can be reclaimed

`loci.lakegc` solved this exact shape for Parquet in August 2026 and the design transfers whole:
`classify` / `report` / `reclaim!`, a window of `{:versions 3 :days 30}`, walked over `sub/history`
**rather than current state**, reported per object in the storage panel and reclaimed on request.
`report` is the dry run; there is no dry-run flag because of it.

**What the window's two halves mean here**, because a file has no versions and the phrase would
otherwise be borrowed without meaning. `:days 30` transfers directly: a file named by any handle in
the last 30 days stays. `:versions 3` becomes **the three most recent files per object** — an object
re-uploaded or reconnected several times names a different hash each time, and those are its
versions. Whichever of the two keeps more wins, exactly as `lakegc/default-window`'s docstring says:
a count alone drops a busy afternoon, an age alone puts no ceiling on a table rewritten forty times
in one.

Three rules make it a files GC rather than a lake GC.

**Only files loci wrote.** A file with no manifest entry was put there by a person — `rsync`, a
Docker mount, a volume — and loci must never delete it. The picker already promises this in the other
direction: a hand-placed file is listed under its own name *"permanently, and that is not a degraded
case."* Deleting it would be destroying data loci did not create, and no storage panel can undo that.

This needs one fix first: **`import-csv!` does not call `files/remember!`**. Only `finish-upload!`
does (`files.clj:340`). So today a pasted CSV is indistinguishable from a hand-placed one, and the
rule above would refuse to reclaim exactly the files the complaint is about. `import-csv!` starts
remembering — which is independently a picker improvement, because a pasted CSV then lists under its
title instead of a hash.

**A live passthrough table's file is never reclaimable**, window or no window. It is re-read on every
query; reclaiming it would turn a working table into `:table/unavailable`. This is not a retention
question and the window does not apply to it.

**Reclaiming retires the manifest line, it does not delete it.** An appended `{:id … :retired …}`
record, the same idiom `loci.chunks` keeps and the third place it applies. `files.edn` is
append-only and **primary** — phase 2's spec is explicit that unlike `chunks.edn` it cannot be
rebuilt — so a GC that rewrote it would be the one operation in loci that can lose a name for good.

### The structural change

`lakegc/event-handles` is `:tx`-aware, and its docstring says why in the strongest terms in the
codebase: a classifier that walked only top-level events *"would find no handle for those tables,
call them orphaned, and reclaim rows a live object is still using — the worst outcome this namespace
can produce."*

A files GC needs the same walk, extracting the source `:uri` instead of the lake `:table`. **Copying
it is how that trap gets sprung twice.** So the `:tx`-aware walk over every handle an event names
lifts into one place, and both GCs call it. That is the only new abstraction in this design, and it
exists because a known-dangerous piece of reasoning would otherwise have two copies.

### On the size of `data/files`

Measured 2026-08-22: `data/files/` holds **0 files, 0 bytes** on this machine.

That number establishes nothing and is recorded so that nobody quotes it. CLAUDE.md is explicit
about this failure, and it has already happened once on this repository: on 2026-08-17 a per-table GC
was measured against the fixture and reported back as *"viable, and pointless today"* — correct about
a 117 KB corpus, and wrong about the feature, which exists for the sizes DuckLake was chosen for. The
target here is the same one phase 2 measured against: **a 200 MiB upload**, of which ten stale ones
is 2 GB. Take the scale from there.

## 3. The leaked library dies with the server that made it

Not a sweep, not an age heuristic, no new mechanism. `harness.mjs` already `mkdtemp`s a directory per
server for `LOCI_DATA` and already `rm -rf`s it in both `reap()` (line 252) and `stop()` (line 307).
Point that JVM's `java.io.tmpdir` at **that** directory rather than at `.tmp`, and the 58 MB library
is removed with the server that extracted it.

This **replaces** the `-J-Djava.io.tmpdir=.tmp` fix committed at `ed06822`, which moved the leak off
tmpfs without ending it. Two properties the earlier fix does not have: the library is attributed to
the process that made it, so nothing has to guess which files are stale; and no sweep can ever delete
a library another JVM — the nREPL, a concurrent suite — still has mapped.

`deps.edn` keeps its `-Djava.io.tmpdir=.tmp` on every alias that runs loci. That is a different
problem, correctly solved, and its `:build` exception stays documented.

## Errors

Nothing here invents a failure. `:connectedAs` is absent or an id. A reclaim that cannot delete a
file reports which and why, the way `lakegc/reclaim!` does. A file that vanished between `report` and
`reclaim!` is not an error — it is the outcome the caller asked for.

## Testing

- **`import-csv!` remembers**, and the pasted file then lists under its title rather than its hash.
- **`:connectedAs` names the lowest id** when two tables share a uri, and is absent when none does.
- **The GC classifier**: a file inside the window survives; outside it and unreferenced, it is
  reclaimable; **named only by a sub-event inside a `:tx`, it is not** — that is the test the lifted
  walk exists for, and it must be proved to fail against a top-level-only walk.
- **A hand-placed file is never reclaimable**, whatever its age.
- **A live passthrough table's file is never reclaimable**, even outside the window.
- **A reclaim retires the manifest line and the name survives** `names` returning it as retired.
- **The harness leaves no `.so` behind**: count `.tmp` before and after a browser run.
- Every regression test proved to fail when its fix is reverted, and every sabotage's output read
  rather than counted.

## Deliberately not in this scope

- **Remote locations and SQL databases.** The next two specs, in that order. Credentials are the
  thing they share and neither is designed here.
- **An automatic GC on a timer.** `lakegc` is reported and reclaimed on request and this follows it.
  A timer is a decision about someone's data taken while they are not looking.
- **Reclaiming a file a materialised table could still `refresh!` from.** The window covers it: a
  materialised table needs its file only to refresh, so a file outside the window is one whose table
  has not been refreshed in 30 days *and* has three newer versions. Refreshing after a reclaim fails
  with `:table/unavailable`, which is the state phase 2 already draws and offers a reconnect for.
- **Deduplicating hand-placed files against loci's own.** Two identical files, one placed and one
  uploaded, stay two files. Content addressing dedups what loci writes; it does not police the
  directory.
