# External sources: a location, a format, a mode — and CSV first

Date: 2026-08-19
Status: **design settled, nothing built.** Two facts below are measured on 2026-08-19 (DuckDB's
CSV type inference, and what those types do to `json/write-str`); everything else is design.
Scope is deliberately one connector: files. Object storage, SQL databases and REST APIs are
named here only where they constrain the framework, and each gets its own spec.

## The want

loci should import large tables — a CSV of millions of rows is the case asked for — and have a
general way to name an external source, so that the next four kinds are connectors rather than
re-designs.

## What exists today

Tables already live in DuckLake. `loci.lake/put-rows!` writes NDJSON to a temp file and runs
`CREATE TABLE … AS SELECT … FROM read_json_auto(…)`, measured 2026-08-16 at **2.9 s for 10M
rows**. Handles are `{:store :lake :table … :snapshot …}`, reads are `AT (VERSION => n)`,
`hydrate` caps a molded table at `mold-cap` = 2000 rows, and `loci.lakegc` reclaims a table's
out-of-window versions per object. That machinery is finished and this design adds nothing to it.

The CSV path is not that machinery, and is the thing being replaced:

| | today |
|---|---|
| route | `POST /api/import-csv`, CSV as a **string in a JSON body** |
| parse | `tools/parse-csv` → `clojure.data.csv` → a vector of Clojure maps, all in RAM |
| types | `tools/->val` — three regexes: `-?\d+`, `-?\d+\.\d+`, else string |
| then | those maps re-serialized to NDJSON and read back by DuckDB |
| callers in the shell | **one** — `startImport`, a paste-CSV-into-a-textarea flow (`resources/public/index.html:1102`) |

Three full in-memory copies before a byte reaches DuckDB — and the copies are the whole story,
because there **is** a user-reachable path and it is the one that makes them unavoidable.
`startImport` gives you a textarea to paste a CSV into, so the file arrives as a JSON string in a
request body and is already in RAM before loci sees it. That is fine for the paste it was built
for and cannot be made to work for a 500 MB file, which is why phase 2 adds a streaming upload
rather than widening this one.

**Corrected 2026-08-19.** An earlier draft of this table said the shell had no caller at all. That
came from grepping `index.html` at the repo root — a different, unserved file. The shell is
`resources/public/index.html` (2952 lines), which is what `server.clj:2991` slurps, and a plain
`grep` on it silently reports zero matches because its long lines make grep treat it as binary.
Use `grep -a`, and grep the served file.

The other CSV ingest is `loci.corpus`, which reads files off disk and builds events directly.

`loci.tools` already declares the seam this design takes up: *"This is where MCP servers would
register too: same shape — a JSON spec + a handler — so the model reaches every connected source
through one mechanism."*

## The rule

**A source is a location, a format and a mode. DuckDB is the connector framework; loci's job is
to name a source, not to reimplement one.**

`read_csv`, `read_parquet`, `read_json_auto`, `httpfs`, `postgres_scanner` and `CREATE SECRET`
already exist on the classpath or one `INSTALL` away. Per CLAUDE.md — *use what the tool already
provides, before inventing a mechanism* — loci contributes the naming, the event, and the
degradation shape when a source is not there.

## The framework

A connector is **two pure functions and no state**:

```clojure
(locate   uri)               ; → a string DuckDB can address
(relation source location)   ; → "read_csv('/abs/x.csv', header=true)"
```

Neither touches a lake, a file or a network, so both are testable with `=` and no fixture.
Everything else is shared and written once:

```clojure
(describe lk src)   ; DESCRIBE SELECT * FROM <rel>
(connect! st src)   ; describe → materialise | passthrough → commit :put
(refresh! st id)    ; re-run the recorded recipe → new handle → commit :put
```

### `describe` is load-bearing

One cheap query yields three things at once, and having one source for all three is why
materialise and passthrough cannot disagree about what the columns are:

1. **Proof the source reads.** A bad path fails at connect, not at first render.
2. **The raw → normalized column map.** `"Total Revenue ($)"` → `:total_revenue`, through the
   existing `sub/col-kw`. Both modes build their projection from this.
3. **The schema a preview shows**, so the shell can display it before anything is committed.

`tablestore/check-names!` applies unchanged to a header: two CSV columns normalizing onto one
column name is caught at connect rather than silently dropping a column. (It was extracted from
`normalize-rows`' private `collisions` for exactly this, so rows and headers run one check.)

**Two literally identical headers are not loci's problem** — measured 2026-08-19, DuckDB dedupes
them before `DESCRIBE` returns: `Region,Region,Total` describes as `Region`, `Region_1`, `Total`,
which normalize apart. So `describe` never sees a duplicate name, and `check-names!` is only ever
asked the normalization question it was written for.

### A source lives on the table object

No new object kind. `:kind :table` grows a `:source`:

```clojure
{:id "tbl:sales" :kind :table :title "Sales 2026"
 :value  {:store :lake :table "t_sales_1723…" :snapshot 41 :rows 10000000 :cols [...]}
 :source {:uri "loci://files/sales.csv"
          :format :csv :options {:header true}
          :mode :materialise
          :at   "2026-08-19T10:00:00Z"}}
```

Every existing mold, chunk, synopsis, notebook ref, entity reader and the `lakegc` grouping —
which is already per-object — works unchanged. **Zero new object kind** was the point: a
`:kind :source` object would have to be taught to `chunks`, `mold`, the shell, entities and
recall, for a file connector that produces exactly one table.

A source that yields *many* tables (a Postgres `ATTACH`) does not fit this and does not have to:
that is the SQL spec's problem, and it may add a source object then. It is not this spec's job
to build the abstraction that connector will want.

### Two handle shapes, one executor

| mode | handle | reads |
|---|---|---|
| `:materialise` | `{:store :lake :table … :snapshot … :rows … :cols …}` — **unchanged** | `… FROM t AT (VERSION => n)` |
| `:passthrough` | `{:store :source :uri … :format … :options … :cols … :rows … :at …}` — **the handle is the recipe** | `… FROM read_csv('…')` |

The passthrough handle carries its `:at` because `column-samples` receives **only the handle** —
see "The synopsis cache needs a key that can change" for what it is for.

**It carries its `:options` for the same reason, and this is the easier one to lose.** A read
rebuilds the relation from the handle alone, so a source connected with `{:delim ";"}` that does
not record it would read correctly once — `describe` saw the real options — and then fall back to
DuckDB's sniffer on every read afterwards. The likely symptom is not an error but a spurious
`:table/source-drift` on a file that never changed, because the sniffer guessed differently
without the option it was not given.

Passthrough creates **no catalog object**: no DuckLake view, no snapshot to fake, nothing for GC
to track. `Lake` executes both — a `:source` handle inlines the relation expression where a
`:lake` handle names a table. `tablestore/handle?` learns `:source` alongside `:inline` and
`:lake`.

### Refresh is a `:put`, not a mutation

`refresh!` materialises a **new** lake table and commits a new handle. The old handle stays in
the log, so `undo!` walks back to it and `as-of` still resolves it. Refresh is where a source's
versions come from, and `loci.lakegc` — which already classifies every version of a table by
object and reclaims the out-of-window ones — bounds them with no change whatever.

For a passthrough source, refresh re-runs `describe` and commits a handle with fresh `:cols`,
`:rows` and `:at`.
It moves no bytes because there are none to move.

## Locations

A source has a **location URI**, not an origin kind:

| scheme | resolves to | phase |
|---|---|---|
| `loci://files/sales.csv` | `<LOCI_DATA>/files/sales.csv` | 1 |
| `file:///data/sales.csv` | itself | 1 |
| `s3://…`, `gs://…`, `https://…` | itself, via httpfs | 3 |

**Upload is not an origin.** It is a route that streams a request body into `<LOCI_DATA>/files/`
and then connects an ordinary `loci://` source. Phase 1 is local locations; phase 2 is a way to
*make* one; phase 3 is remote ones.

`loci://` is reachable in phase 1 without the upload route: a file copied into
`<LOCI_DATA>/files/` by hand, and the rewritten `/api/import-csv`, which writes posted text there
before connecting. Phase 2 adds the streaming route, not the scheme.

Three consequences, all of them wins:

- **Passthrough works for an uploaded file**, because `loci://files/x.csv` is a stable location.
  Re-uploading to the same location *is* a refresh: the recipe never changed, the bytes did.
- **The log stores no machine paths.** `loci://files/sales.csv` resolves in the container, on
  another machine, out of a restored backup. `/home/damirk/src/loci/data/files/sales.csv` does
  none of that.
- **"One directory to back up" survives** — `data/files/` sits beside `data/lake` and
  `data/substrate`.

### Decided: `file://` is unrestricted over HTTP

The API accepts any scheme, including arbitrary host paths. loci is single-user local software;
the process can already read those files and the REPL facade can already do this. An allowlist
would be a mechanism nobody has asked for — CLAUDE.md, *don't build for a requirement nobody
stated*.

### Costs of loci owning bytes outside the lake

- **A materialised upload keeps both the CSV and the Parquet.** Deliberate: refresh needs the
  recipe to stay reproducible.
- **`/api/storage` gains a second class.** It reports lake classes today; `data/files` becomes
  one more.
- **There is no GC for `data/files` in this spec.** Deleting a table does not delete its file,
  and the storage panel says so rather than implying otherwise.

## Passthrough — the object outside the promise

A passthrough table's bytes can change with no event. That breaks a promise the substrate makes
everywhere else. The design makes it **visible and bounded**; it does not paper over it.

Reads are bounded exactly as everything else is: `SELECT <projection> FROM read_csv('…')
LIMIT 2000`, the same `mold-cap`. **Which** 2000 is not promised, and that is not new: a
`LIMIT` without an `ORDER BY` has no guaranteed row order over Parquet either, which is
why `tablestore/column-samples` already sorts in SQL — *"the lake has no row order to
appeal to without an `ORDER BY`, and a synopsis whose values vary between runs would move
its hash on every sweep and re-embed forever."* A molded table has always been a page of an
unordered relation; passthrough inherits that rather than introducing it. So a 10M-row passthrough file renders like a 10M-row
materialised one. `row-count` would be a full scan, so the handle carries the count observed at
connect and `hydrate` reports it as `:table/total` **with the passthrough marker attached** — a
stale count that announces itself is safe; one that does not, is not.

### Three failure modes, on the shape `hydrate` already has

The reclaimed path proved this shape works: `:value []` so every downstream
`(and (sequential? v) (seq v) (every? map? v))` guard behaves as it does for an empty table, the
true `:table/total` and `:table/cols` off the handle, and a marker a reader must check **before
acting on rows**.

| what happened | marker | the shell says |
|---|---|---|
| file gone or unreadable | `:table/unavailable` | "source unreachable since …" |
| readable, columns no longer match | `:table/source-drift` | "the file's columns changed — reconnect or refresh" |
| normal | `:table/passthrough` | a live badge |

**Drift is detectable because the projection names columns.** `SELECT "Total Revenue ($)" AS
total_revenue` throws when the header changes; `SELECT *` would silently return a
differently-shaped table. That is the argument for the named projection, and it applies to both
modes.

`server/recompute` already sets the precedent — it refuses on `:table/reclaimed` rather than
recomputing a derived table from zero rows and committing the empty result over good data. Every
marker above gets the same treatment in the same readers.

### The synopsis cache needs a key that can change

`lake.clj`'s `sample-cache` memoizes `column-samples`, and its docstring names its own expiry
date: **"whoever adds in-place mutation has to invalidate this."** Passthrough is that day.

A cache key here answers one question — *which bytes did these samples come from?* — and to
answer it the key has to do two jobs: **separate** different tables from one another, and
**change** when the bytes behind one table change. For a materialised table the lake table name
does both in a single token, because every write mints a new name. That is why the existing key
carries no object id and has never needed one.

Passthrough splits the two jobs apart — but not the way it first appears, and the difference is
worth stating because the obvious answer produces a key no test can check.

The **separating** job is already done. The cached SQL is keyed on its own FROM text, and a
passthrough handle's FROM text names the file's path, so two sources at two paths can never
collide. Two sources at *one* path are a different matter, and they must share: one file is one
set of bytes, so one answer is the right answer. An object id in this key would therefore be
inert — it would distinguish only cases that must not be distinguished, and read as a guarantee
it is not making.

What is genuinely missing is the **version**. A `:lake` handle's FROM text carries one already:
it names the table and the snapshot, and a write never overwrites — measured 2026-08-19, two
`put-rows!` under a single object id land in `t_tbl_same_d08b834d` and `t_tbl_same_912090c8`. A
path carries none.

So: **a passthrough handle keys the sample cache on its `:at`**, the instant of the connect or
refresh that last looked, and a `:lake` handle adds nothing. `:at` is the only thing in a source
that moves when the operator says the bytes changed.

**The known limit, stated rather than hidden.** For a materialised table the version in the key
is a fact DuckLake guarantees. For a passthrough table `at` is a claim loci makes on the
operator's behalf: overwrite the file without refreshing and the synopsis is stale, and nothing
detects it. Only the synopsis — **the rows are always live and correct**, because reads go
straight to the file. `at` is a refresh marker, not a version, and the spec does not pretend
otherwise.

Materialised handles keep the existing key and the existing argument, untouched.

### The scrubber tells the truth

Scrub to last Tuesday and a passthrough table shows **today's** file. There is no other answer,
because loci never held those bytes. So it says so: scrubbed into the past the badge changes from
*live* to **"live source — showing current contents, not the contents at ‹T›"**.

The one thing this design will not do is render a passthrough table identically to a materialised
one and let the reader assume time travel worked.

## One inference

**`read_csv` becomes the only way a CSV becomes rows.** `tools/parse-csv` and `tools/->val` are
deleted. This is the choice that makes the whole thing work at size — DuckDB's sniffer is
per-column, sampled, and never holds the file — and it is not free.

### Measured 2026-08-19: the sniffer's types, and what they do to JSON

A six-column CSV through `read_csv` on a fresh in-memory DuckDB:

| column | DuckDB type | JDBC class | `json/write-str` |
|---|---|---|---|
| `2026-01-02` | `DATE` | `java.time.LocalDate` | **throws** |
| `2026-01-02 03:04:05` | `TIMESTAMP` | `java.sql.Timestamp` | serializes, **shifts the instant** |
| `1234` | `BIGINT` | `Long` | ok |
| `1.50` | `DOUBLE` | `Double` | ok |
| `true` | `BOOLEAN` | `Boolean` | ok |
| `alice` | `VARCHAR` | `String` | ok |

The throw is verbatim *"Don't know how to write JSON of class java.time.LocalDate"* — the
identical failure `lake.clj`'s `unwrap` already documents for `org.duckdb.JsonNode`, whose
docstring names the remedy correctly: *"a storage layer handing its callers values they cannot
serialize is the actual defect."* And `java.sql.Date`/`Timestamp` round-trip through the JVM
default zone: `2026-01-02` came back as `"2026-01-01T23:00:00Z"`, wrong by a day, silently.

**So `unwrap` grows** — same function, same reason. `LocalDate`, `LocalDateTime`, `Timestamp`
and `Time` render to ISO-8601 strings **through `java.time`, never through `java.sql`**. Without
this, the first CSV with a date column is unopenable in the shell, and `query_table` on it throws
inside the agent's tool loop.

`Boolean` is new too — today's `->val` leaves `"true"` a string — and needs nothing. It
serializes, and it is what the user wrote.

### Three places outside the framework pay for the switch

- **`chunks.clj`** documents `parse-csv`'s artifacts by name (`tbl:csv-19-7071`'s `urban_pct`
  rendering `100.0` because every unquoted decimal parsed as a Double). Those docstrings become
  false and are corrected in the same commit that deletes `->val`.
- **`loci.corpus`** stops carrying a parser; its five fixture CSVs become five sources. The real
  cost is its *derivations*, which compare with `=` against literals — `(= year (:year %))`,
  `(sort-by :production_tonnes >)`, `(filterv number? (map :year hit))`. Clojure's `=` is
  type-strict, so rows typed by DuckDB rather than by three regexes can match fewer. Some of this
  fails loud already (the docstring records that superlatives answer nil on a tie and the import
  stops); the rest is a task that re-imports and diffs the fact count. `data/` is disposable per
  CLAUDE.md, so this is work, not risk.
- **The markdown-table salvage keeps `md-cell-val`.** Extracting `30` from
  `"~30% (estimated)"` is *prose salvage*, not type inference, and DuckDB will never do it. The
  regex stays and returns the extracted **string**; the salvaged rows then go out through the
  same temp-file → `read_csv` path as everything else. `->val` still dies and one inference still
  holds.

## Surfaces

In the shape CLAUDE.md's *one writer, three ways to reach storage* already fixes:

- **Facade** — `loci.source/describe`, `connect!`, `refresh!`, public, called from a REPL
  attached to the live server. `describe` **is** the dry run, so these do not need a `:dry-run?`
  flag of their own.
- **HTTP** — `POST /api/source/describe`, `/api/source/connect`, `/api/source/refresh`.
  `POST /api/source/upload` in phase 2, streaming the raw body.
- **`/api/import-csv` survives as a wrapper** — posted text written to `<LOCI_DATA>/files/`, then
  `connect!`. Its route and its test keep working while `parse-csv` disappears beneath it.
- **Shell** — a **Connect data** affordance showing the `describe` schema before anything is
  committed, a mode toggle, a refresh button on a sourced table, and the live badge.

### Formats in phase 1: CSV, Parquet, JSON

All three are built into DuckDB and differ by one function name. Three of them proves the format
axis; one only asserts it.

**Excel is deferred.** It needs a loadable extension, and shipping one into the image is a
problem that just cost two commits (`f8f1c94`, `30d3172`). It is a connector, and it can be added
the day someone has an `.xlsx`.

## Phasing

Three plans, each its own `writing-plans` pass.

1. **Framework + files, local.** `locate`/`relation`/`describe`/`connect!`/`refresh!`;
   `loci://` and `file://`; CSV, Parquet, JSON; both modes; the `:source` handle and its markers;
   `unwrap` grown; `parse-csv` and `->val` deleted; corpus re-seeded; `/api/import-csv` rewritten
   as a wrapper.
2. **Upload + shell.** Streamed raw-body route into `<LOCI_DATA>/files/`; the Connect data
   affordance, the preview, the mode toggle, the refresh button, the live badge and the scrubbed
   wording; `/api/storage` reports `data/files`.
3. **Remote locations.** httpfs, `s3://`/`gs://`/`https://`, and DuckDB `SECRET`s — which is also
   the spec that unblocks SQL databases, since credentials are the thing they share.

## What to measure before building is done

Taken from the design's stated target, **never from `data/`** — CLAUDE.md is explicit that the
fixture can establish that a mechanism works and never that a cost is real.

1. **A generated 10M-row CSV (~500 MB) in a temp dir.** Report wall clock **and peak RSS** for
   connect+materialise, beside `import-csv!` as it stands — which holds the file as a JSON
   string, as Clojure maps and again as NDJSON, and is expected to fail outright. That failure is
   the number that justifies this work.

   The 2.9 s measured for `put-rows!` at 10M rows is **not** the bar: it is the cost of the
   NDJSON write plus DuckDB's read, starting from rows that are already in RAM — which for a
   500 MB CSV is the state you cannot reach. The bar is DuckDB reading the CSV directly, and the
   thing to prove is that peak RSS stays flat as the file grows.
2. **Passthrough at 10M rows** — the `LIMIT 2000` render, and `count(*)`.
3. **The corpus re-import** — same row counts, and a written list of every column whose type
   changed.
4. **Both suites**: `clojure -M:test` and `npm run test:browser`. Neither is "all the tests".
5. **Every regression test proved to fail when its fix is reverted.** A test that passes either
   way is worse than none, because it certifies.

## Measured

Measured **2026-08-20**, on the machine this was written on: 6 cores, 15 GiB RAM, JDK default max
heap **3,976 MB** — no `-Xmx` given, because the default is what `clojure -M:serve` runs with and
item 4 below only means anything against it. DuckDB 1.5.5.1, `memory_limit` 12.4 GiB, `threads` 6.

Method, because the numbers are only as good as it. Each figure is its own fresh JVM, so nothing
carries over between sizes. Memory is **sampled on a 20 ms daemon thread for the length of every
call** and the maximum reported — heap as `.totalMemory - .freeMemory`, process RSS as field 2 of
`/proc/self/statm` — because a before/after delta misses the peak entirely; a triple `System/gc`
brackets each call for the live-heap figures beside it. The fixture lives on real disk under
`/var/tmp`, deliberately **not** on this machine's `/tmp`, which is a tmpfs and would have made
the file's own bytes count against RAM.

Item 3 of the list above — the corpus re-import — belongs to its own plan task and is not in this
run.

### The file

`id,region,amount,day`, from which DuckDB infers `BIGINT, VARCHAR, DOUBLE, DATE`; `region` carries
six distinct values.

| rows | bytes | generated in |
|---|---|---|
| 10,000,000 | 417,658,901 (398 MiB) | 2,457 ms |
| 1,000,000 | 40,765,900 (39 MiB) | 296 ms |

398 MiB rather than the ~500 MB asked for above. The row count is the target and it is exact; the
byte count falls out of the column shape.

### 1. Materialise — memory does not scale with the file

| | 1M rows / 39 MiB | 10M rows / 398 MiB |
|---|---|---|
| `source/connect!` wall | 238 ms | **893 ms** |
| peak JVM heap | 57 MB | **57 MB** |
| live heap, before → after | 55 → 55 MB | 55 → 55 MB |
| peak process RSS | 744 MB | 953 MB |
| RSS across the call | 620 → 712 MB | 585 → 914 MB |
| Parquet written | 9.3 MB | 89 MB |

Repeated three times in one JVM at 10M, because 893 ms was surprising enough to want measuring
twice: 751 / 747 / 792 ms, peak heap 56 / 56 / 57 MB, RSS 609 → 725 → 766 → 873 MB, and DuckDB's
own `duckdb_memory()` reporting 4.0 / 6.4 / 6.4 MB throughout. Each of the three tables verified
independently — `count(*)` 10,000,000, `min(id)` 1, `max(id)` 10,000,000, six distinct regions,
identical `sum(amount)`.

**This is the claim the design rests on, and it holds — with one correction.** The JVM heap is
flat to the megabyte: 57 MB at 1M and 57 MB at 10M, live heap 55 MB either side, because the rows
never enter this process. Process **RSS is not flat**, and pretending otherwise would be the easy
lie here: a file 10× larger cost 3.6× the RSS delta, +92 MB against +329 MB. That is DuckDB's
native buffer manager, bounded by its own `memory_limit` rather than by the file — three
successive passes over the same 398 MiB added ~90 MB each, not 398 MB each.

The 893 ms also settles the note above about `put-rows!`. DuckDB reading the CSV directly is
**three times faster** than the 2.9 s NDJSON path, from a starting state — a file on disk — that
the NDJSON path cannot reach at all.

### 2. Passthrough at 10M rows

| | |
|---|---|
| `DESCRIBE SELECT *` — the sniff `describe` runs | 42 ms |
| `SELECT count(*)` over the whole 398 MiB file | 244 ms (246 ms on a repeat) |
| `connect!` with `:mode :passthrough`, total | **294 ms** |
| bytes written to the lake | **21,543** — catalog only, no Parquet |
| `tbl/hydrate` — the `LIMIT 2000` render plus `row-count` | 124 ms cold, 94 ms warm |
| peak heap during hydrate | 139 MB |

`hydrate` returned 2,000 rows, `:table/total 10000000`, `:table/passthrough true`, and
`tablestore/unusable` nil. `row-count` costs no query at all — it is `(:rows h)` off the handle.

**The `count(*)` is 244 of the 294 ms — 83% of a passthrough connect.** That is the one thing that
makes passthrough not free at connect time, and it deserves saying plainly: connecting live is
3× cheaper than materialising and writes nothing, but it is not O(1). It reads the file once, to
learn how many rows are in it.

### 3. What the JSON-string import path actually does

**It does not fail.** The prediction above — *expected to fail outright; that failure is the
number that justifies this work* — is wrong at 10M rows, and the justification has to change with
it.

**Over HTTP the file never reaches loci at all.** `server.clj` calls
`(http/run-server #'handler {:port port})` with no `:max-body`, and http-kit 2.8.0 defaults that
to 8,388,608 bytes. Posted at a live loci:

| POST to `/api/import-csv` | answer |
|---|---|
| 116-byte body | 200, `tbl:tiny`, 2 rows, 85 ms |
| 9,688,511-byte body | **413** `request body 9688511; max request body 8388608` — **0 bytes uploaded**, 0.6 ms |
| 427,658,912-byte body, the 10M CSV | **413**, same message, **0 bytes uploaded**, 0.7 ms |
| 8,388,588-byte body — the largest that fits | 200, **203,163 rows**, 2.15 s |

So today's ceiling on this route is ~8 MiB of JSON body ≈ **203,163 rows of this shape**, two
percent of the target, refused on the `Content-Length` header before a byte is read. That is a
hard ceiling rather than a memory pressure, and lifting it is phase 2's first job.

**In process, with the cap out of the way, the whole chain succeeds on the default heap.**

| step | wall | peak heap | peak RSS |
|---|---|---|---|
| `slurp` the 418 MB CSV into one String | 420–475 ms | 1,480 MB | 1,968 MB |
| `json/write-str {:csv …}` → a 427,658,912-char body | 7,712 ms | 2,439 MB | 2,890 MB |
| `json/read-str` it back — what `body-json` does | 2,529 ms | 2,616 MB | 3,524 MB |
| `body-json` over the 408 MiB body read off disk | 2,922 ms | **2,476 MB** | 3,028 MB |
| `server/import-csv!` on the parsed String | 2,387 ms | 865 MB | 2,304 MB |

The table lands — `tbl:sales_http`, 10,000,000 rows, `:materialise` — with no `OutOfMemoryError`
anywhere, and the round trip is exact at 417,658,901 characters back.

So the number that justifies the streaming route is not a crash. It is **2,476 MB of peak heap,
62% of the default 3,976 MB and roughly six times the file**, to move bytes that DuckDB reads in
893 ms holding 57 MB. `import-csv!` also writes a **second full copy** of the CSV into
`<LOCI_DATA>/files/` — 417,658,901 bytes — on top of the 89 MB of Parquet, which the streaming
route removes as well. Constant memory, not feasibility.

### 4. Both suites

- `clojure -M:test` — **769 tests, 4,279 assertions, 0 failures, 0 errors**, 59.5 s.
- `npm run test:browser` — **135 pass, 0 fail**, 309.9 s.

### Which of the two things each number establishes

CLAUDE.md's rule: a measurement tells you a mechanism works, **or** that a cost is real, never
both. Sorted, so nobody has to guess:

- **That the cost is real.** Every figure at 10M rows, all taken at the design's stated target and
  none over `data/`: the 57 MB heap flat against a 398 MiB file, the 244 ms `count(*)` that is
  83% of a passthrough connect, the 2,476 MB peak of the JSON path, and the 8 MiB HTTP cap with
  its 203,163-row ceiling.
- **That the mechanism works.** The inferred `BIGINT, VARCHAR, DOUBLE, DATE`; the
  `:table/passthrough` shape and its nil `unusable`; the byte-exact JSON round trip; both suites.
  These say the code is correct. They say nothing about whether anything costs too much.

## Deliberately not in this spec

- **Object storage, SQL databases, REST APIs.** Named above only where they constrain the
  framework. Each is its own spec against a framework that by then exists and has been exercised.
- **Scheduled refresh.** Refresh is a manual act — a button, a route, a facade call. Nobody has
  asked for a scheduler.
- **GC for `data/files`.** Stated as a known gap that the storage panel reports honestly.
- **A `:kind :source` object.** Deferred to the first connector that produces many tables from
  one source, which is the SQL spec.
- **Excel**, until someone has a spreadsheet.
