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

## Measured 2026-08-22: 2 GB reports in under a millisecond, and the manifest is the term that grows

Measured on the machine this was written on: 6 cores, 15.5 GiB RAM, JDK 26.0.2, JVM default max heap
**3,976 MB** — no `-Xmx`, because that is what `clojure -M:serve` runs with. Both fixtures live under
`.tmp`, which is on disk and not on this machine's tmpfs `/tmp`, so their bytes never count against
RAM. `<LOCI_DATA>` is a throwaway directory per fixture; `data/` was never touched.

Method, because the numbers are only as good as it. Wall clock is the **median of nine warm runs**
in the nREPL the working agreement makes the execution surface, after three untimed ones — five runs
rather than nine for the log-axis rows in section 3, which rebuild a store per point. Not a
fresh JVM per figure, which phases 1 and 2 needed because they were measuring a cold DuckDB and this
measures Clojure over a directory.

Memory is sampled on a **20 ms daemon thread** and the maximum
reported — heap as `.totalMemory - .freeMemory`, process RSS as field 2 of `/proc/self/statm` — with
a triple `System.gc` bracketing each call for the live-heap figures. **The store is a record whose
`objects` and `history` are plain derefs**, the shape `loci.dlv` has in production;
`sub/fresh-store` re-materialises the whole log on every `objects` call, and measuring against it
would price the store walks as the log rather than as the object map. One consequence of the warm
REPL: the **RSS figures are a long-lived nREPL's whole process** and are a ceiling rather than a call's
footprint. The heap figures are the sharp ones.

**The plan fixtured ten 200 MiB files and claimed something about 10,000 of them.** Those are two
different questions and both are here: the byte axis is the complaint this phase opens with, and the
count axis is what decides whether `report` can sit in the payload the storage panel draws on open —
which is where it now lives, `storage-payload` calling it with `default-window`.

### 1. The byte axis — 2.0 GiB in ten files

Ten CSVs of 209,717,421 bytes (200.00 MiB each), **2,097,175,010 bytes** in `<LOCI_DATA>/files/`;
ten `remember!` lines dated 40 days ago; a store holding ten materialised tables `:put` and then
`:delete`d, so nothing live names them. Window `{:versions 0 :days -1}` — the shrunken window the
tests use, because `EventStore/commit!` overwrites any `:ts` a caller supplies and an event cannot
be backdated.

| | |
|---|---|
| `classify` | ten of ten `:reclaimable` |
| **`report` wall** | **0 ms** — median of nine, best 0, worst 1 |
| `report` peak heap / live heap | 80 MB / 79 → 79 MB |
| `report`'s `:reclaimable-bytes` | **2,097,175,010** — the whole directory |
| **`reclaim!` wall** | **267 ms** |
| `reclaim!` peak heap / live heap | 79 MB / 78 → 78 MB |
| `:freed` / `:errors` | 2,097,175,010 / none |
| the directory afterwards | **0 files, 0 bytes** |
| `files.edn` afterwards | 20 lines — ten remembers, ten tombstones |
| `names` afterwards | **10** — every name outlived its bytes |

**`report` is O(files) and not O(bytes), and the number that says so is a zero.** It never opens a
file: `.length` is a stat, and 2 GB of CSV costs less to report than a keystroke. The whole cost of
this phase's headline case is in `reclaim!`, and that is ten `unlink`s — the ten `retire!` appends
that go with them are together under a millisecond, measured separately.

**One thing to know before quoting the 267 ms.** This machine's `/home` is btrfs mounted
`compress=zstd:3`, and the fixture is one 1 MiB block of CSV repeated, so its extents were ~375 MB
rather than 2 GB and `df` returned 375 MB, not 2 GB. Cross-checked against incompressible bytes — a
200 MiB file of `/dev/urandom` — one `unlink` costs **17 ms**, so ten is ~170 ms of the 267. The
consequence is worth stating on its own, because it is a property of the panel and not of the
fixture: **on a compressing filesystem the bytes a reclaim returns to the device are not the bytes
the panel offered.** `report`, `:freed` and the panel all count `.length`, which is what a person
sees a file weigh, and it is the right number to show — it is simply not the one `df` moves by.

### 2. The count axis — 10,000 files

10,000 files named as loci names them (16 hex characters and `.csv`), 47 bytes each; a manifest of
10,000 lines / 712 KB (72 bytes a line); a store of **15,000 objects and 30,000 events** — 5,000
live materialised tables, each a `:put` and an `:assoc [:value]` refresh that names no uri; 5,000
tables since deleted; 10,000 objects that name no file at all.

| term | empty store | 15,000 objects / 30,000 events |
|---|---|---|
| the directory walk alone — `.listFiles`, `content?`, the sort, 10,000 `.length` | 36 ms | 36 ms |
| `records` — one manifest parse | 35 ms | 35 ms |
| `names` — that parse plus its fold | 38 ms | 38 ms |
| `latest` — that parse plus its fold | 38 ms | 38 ms |
| `connections` | 0 ms | 3 ms |
| `tbl/handles-in-history` | 0 ms | 14 ms |
| `listing` | 77 ms | 81 ms |
| `classify`, handed the rows | 45 ms | 73 ms |
| `classify`, walking the directory itself | 121 ms | 156 ms |
| **`report`** | **126 ms** | **160 ms** |
| what `report` would cost if it walked twice | 197 ms | 234 ms |

`report` at 10,000 files and a fresh manifest is **160 ms**, peak heap 195 MB, live heap 79 → 82 MB,
peak RSS 626 MB. Nothing is retained: the peak is transient allocation for 10,000 rows, and the live
heap is where it started. `reclaim!` over the 5,000 doomed files cost **430 ms** (716 ms on the
first of two runs), peak heap 242 MB, live heap flat — about 0.09 ms a file, against the 27 ms a
file the 200 MiB unlinks cost.

**What this table does to `classify`'s own docstring: one number corrected, one prediction
confirmed.**

- **The 68 ms attributed to `listing` is not ~12 ms of directory and ~56 ms of manifest.** That
  split was a subtraction between two figures taken at different moments, and measured directly on
  one fixture the walk is **36 ms against the parse's 38 ms** — about half, not a fifth. Hoisting a
  manifest read would collect a little under half of what taking the rows collects, not most of it.
  The docstring now says so.
- **The empty store's fraction was the honest worry and it survives.** The docstring predicted, by
  arithmetic rather than measurement, that at 20,000 objects and 40,000 events the one-walk saving
  would be "74 of 223, about a third". Measured at 15,000 objects and 30,000 events: **74 of 234**.
  The prediction was right to within the noise, which is the one case where quoting arithmetic back
  was justified.

**And the fraction the third question asked for.** The docstring's figures were taken over an empty
store, and at production scale `classify`'s own walks sit on top of them. Measured: the store's
contribution is 34 ms of `report`'s 160 — **21%** — at 15,000 objects and 30,000 events, and it is
165 of 291 — **57%** — at 285,000 objects and 300,000 events (section 3's last row against the same
empty-store 126 ms). So the empty-store numbers are a floor that the log walks past, not a
different-by-a-rounding-error approximation. Which term dominates depends on which of the three
axes a particular loci has grown along, and that is section 3.

### 3. `report` has no floor, and a reclaim makes every later one slower

The review that asked for this was right, and the demonstration is one line of the table below:
**halving the directory made `report` more expensive, not less.**

Directory, manifest and `report`, walking one reclaim cycle at the count axis — store held fixed:

| | files in `dir` | lines in `files.edn` | `report` |
|---|---|---|---|
| a fresh 10,000-file directory | 10,000 | 10,000 | **167 ms** |
| after reclaiming the 5,000 doomed ones | 5,000 | 15,000 | **189 ms** |
| generation 2 — those 5,000 come back as new uploads | 10,000 | 20,000 | **249 ms** |
| a manifest that has lived through ~25,000 reclaims | 10,000 | 60,000 | **560 ms** |

Run twice end to end, because the second row is the surprising one: 162 / 181 / 249 the first time
and 167 / 189 / 249 the second, and 554 / 560 ms at 60,000 lines. The mechanism is exactly what the
review named — `retire!` appends one line **forever**, `report` parses `files.edn` **twice** (once
as `names` inside `listing`, once as `latest` inside `classify`), and the parse is linear at
~3.5 µs a line. A directory that is swept regularly therefore carries the cost of every sweep it has
ever had, and the panel that offers the sweep is the thing that pays.

The other term with no floor is the log. `tbl/handles-in-history` folds `sub/history`, which only
grows, and deleting a file never removes the events that named it:

| events / objects | `handles-in-history` | `report` |
|---|---|---|
| 30,000 / 15,000 | 14 ms | 160–171 ms |
| 100,000 / 85,000 | 27 ms | 189 ms |
| 300,000 / 285,000 | 78 ms | 291 ms |

(`connections` is 3 ms at 15,000 objects and 32 ms at 285,000, and `report` reads it twice.)

**Is it real at the stated scale, or only in principle?** Real, and slow enough to notice only well
past it. At the target — a directory of files this phase is about, a manifest that has not yet been
swept twenty times, a log of tens of thousands of events — `report` is 160 to 250 ms, which is a
panel that opens without a spinner. It reaches half a second at 60,000 manifest lines and 300 ms at
300,000 events, and both of those are ratchets: neither number ever comes down on its own. Nothing
here asks for a fix today. What it asks for is that the next person who finds the storage panel slow
looks at `files.edn`'s line count and `sub/history`'s length **before** looking at the directory,
because the directory is the one term of the three that a reclaim actually shrinks.

### 4. The sabotage the lifted walk exists for, re-run at the end

Every task proved its own tests bite. One was re-run on the finished tree, because it is the one the
structural change was made for: `tbl/event-handles`' `:tx` branch replaced with `nil`, which is the
top-level-only walk `lakegc`'s docstring calls the worst outcome either GC can produce.

Four assertions failed, and **which** four is the finding:

- `handles-in-history-finds-a-handle-inside-a-tx` fails on both of its assertions — the sub-event's
  handle comes back `()`, no table and no inherited `:ts`.
- `classify-finds-a-file-named-only-inside-a-tx` fails on `:object` and `:age-rank`, **not on
  `:class`**. A live table's file still reports `:current` with the walk gutted, because `live` is
  built from `connections`, which reads the objects' own `:source` and never the walk. That is the
  silent absorption `classify`'s docstring warns about, visible in a sabotage rather than argued:
  the assertion that actually bites is the one taken **after** the table is deleted, where the file
  comes back `{:class :orphaned :object nil}` instead of `:reclaimable` naming `tbl:t`.

So the test earns its place, and it earns it on its second half. A version of it that asserted only
`:class` on the live table would have passed against the walk it exists to rule out.

### Which of the two things each number establishes

CLAUDE.md's rule: a measurement says a mechanism **works**, or that a cost is **real**, never both.

- **That the cost is real.** Everything taken at the design's stated target and not over `data/`,
  which holds 0 files: the 0 ms `report` over 2.0 GiB and the 267 ms `reclaim!` that freed it; the
  160 ms `report` over 10,000 files and the 234 ms it would have cost walking twice; the 36/38 ms
  split inside `listing`; the 167 → 189 → 249 → 560 ms manifest ratchet and the 14 → 78 ms log one.
  These are the numbers that may be quoted about whether something costs too much.
- **That the mechanism works.** `:freed` equal to `:reclaimable-bytes` to the byte, 2,097,175,010
  both times, with the directory left at 0 files; ten tombstones appended and ten names still
  answered by `names` after their bytes were gone; ten of ten classified `:reclaimable` at the byte
  axis and 5,000 of 10,000 at the count axis, which left 5,000 files on disk and freed exactly half
  the directory's bytes; no `:error` row in either sweep. And the sabotage in section 5. These say
  the code is correct and say nothing about anyone's panel.

### Known gaps, decided rather than overlooked

Each of these was found in review, argued, and left in. They are recorded here so the next person
finds the decision rather than the surprise.

- **`reclaim!` does not close the interleave** where a file is connected between `classify` reading
  `connections` and the `.delete`. Argued at length in its own docstring; the cheap fix — one
  `(connections st)` at the top of the loop, ~6 ms in total, as a **filter** that gives the file an
  `:error` row rather than as `lakegc`'s whole-call refusal — is priced there and is what to take
  when someone actually feels the window.
- **A `retire!` that throws mid-sweep loses the result vector for the files already deleted.**
  Deliberate: catching it would produce a row carrying `:error` for a file that **was** deleted,
  which the storage panel renders as a false sentence. The bytes are gone and the name stays
  un-retired, which nothing downstream reads — `classify` is driven by the directory.
- **`:freed` counts a file that vanished between `report` and `reclaim!`.** `Files/deleteIfExists`
  answers false and the row is a success, because that is the outcome the caller asked for. The
  spec's Errors section blesses this and it is not an error.
- **`report` never shrinks.** Section 3 above, in full: two of its terms are ratchets and only the
  directory walk is not.

### Both suites

- `clojure -M:test` — **843 tests, 4,539 assertions, 0 failures, 0 errors.**
- `npm run test:browser` — **153 tests, 153 pass, 0 fail**, 383 s. The known `outline` flake —
  "clicking an entry brings that cell into view", which reads a `.flash` class removed on a 900 ms
  timeout — did not fire. Ten agents have chased it; one green run is still not a fix, it is a flake
  behaving.
- **`ls .tmp/libduckdb_java*.so | wc -l` is 0 before that run and 0 after it**, against the 26 stale
  libraries and 1.5 GB the want table above records for phase 2. The whole suite, not one file, and
  the per-test assertion — `the server keeps its DuckDB library inside its own directory` — is the
  one that says *why* the count is 0 rather than leaving 0 to mean the library moved somewhere else.
