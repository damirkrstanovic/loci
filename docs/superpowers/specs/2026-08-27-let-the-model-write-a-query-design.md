# Let the model write a query, not a scan

Date: 2026-08-27
Status: **design settled with the user, nothing built, the measurement gate is RUN.** Every
decision below was taken in conversation on 2026-08-27. The gate ran the same day against a
throwaway lake of 1,000,000 rows and **found five errors in this spec, two of which would have
shipped as wrong SQL**. All are corrected inline and marked *Corrected, measured 2026-08-27*.
See "What the gate found" at the end. Numbers quoted from other specs are marked.

## The want

A user has a table of 10M rows and asks loci for a report. loci writes Clojure to produce it,
expresses every query in that code as HoneySQL, and keeps the code in the substrate as a
function to reuse. The transformation may be more complex than SQL, and that is fine — but the
code must push as much as it can into SQL.

## What is wrong today

**Five callers read a 2,000-row sample and treat it as the table.**

`tbl/hydrate` asks storage for `{:limit mold-cap}`, and `mold-cap` is 2000
(`tablestore.clj:485`). Every path below goes through it:

| caller | what it does with the sample |
|---|---|
| `tools/do-query` — `query_table` | folds it in Clojure and returns 200 rows |
| `tools/do-query` `:save_as` | writes a derived table from it |
| `server/fns-payload` | decides which ƒ verbs to offer |
| `server/fn-preview` / `fn-apply!` | runs a `lib:` verb, commits the result |
| `server/compute-clj!` | runs model-written Clojure, commits the result |

`query_table`'s own tool description reads *"Use this for any exact figure — never guess."*
Over a 1M-row table it sums 2,000 rows and reports the total as exact. That number then enters
a report, `distill!` turns it into a memory fact, and `loci.chunks` embeds it. **A capped number
becomes a remembered one.**

`mold-cap` is not the fault. It is a correct bound on a mold payload, and its docstring says so.
The fault is that five callers had no other way to ask a table a question.

### Why they had no other way

`loci.tablestore`'s protocol ships six methods: `put-rows!`, `rows`, `page`, `row-count`,
`reclaimed-at`, `column-samples`. The read half, and it grew.

The 2026-08-16 big-content spec designed four (line 493):

```clojure
(defprotocol TableStore
  (rows      [this ref opts] "a page, for the Rows viewer")
  (query     [this q]        "HoneySQL data → results")
  (put-rows! [this rows fmt] "→ the handle to record in the event")
  (at        [this handle]   "a read-only view at a past handle"))
```

**`query` was specified and never built.** That is the whole gap. `at` folded into the handle's
`:snapshot` and needs no method.

So this spec is not new design. It is the implementation of a method its own design already
has, plus the sandbox contract that method makes possible.

### HoneySQL was also already chosen

The same spec, line 505:

> **Queries are HoneySQL data, not SQL strings**, and that is a thesis point rather than a taste
> one: `compute-clj!` currently has the agent write *Clojure code* to transform a table. A query
> expressed as data is storable in the substrate, diffable, reviewable before it runs, and
> reversible — which is "the agent proposes, you decide" applied to computation instead of to
> prose.

That reason is better than any correctness argument. It is why HoneySQL is in this spec.

## The rule

**The sandbox is handed a database, not rows.**

Today `compute-clj!` binds one symbol:

```clojure
(sci/eval-string code {:bindings {'rows <2,000-row sample>}})
```

After this, **`rows` does not exist**. The model cannot fold a sample because there is no
sample. That is the enforcement, and it is the same trick used twice more below: make the
honest path the only path rather than the one the prompt asks nicely for.

## The sandbox API

Four verbs. They line up one-to-one with the protocol that already exists, which is the sign
that the API is the protocol exposed rather than a new idea.

| sandbox | protocol | Clojure heap |
|---|---|---|
| `(q spec)` | `rows` | one page of maps, plus the true total |
| `(q-reduce spec f init)` | — | one batch at a time |
| `(q-all spec)` | — | every row, realised |
| `(keep! title spec-or-rows)` | `put-rows!` / `create-from-relation!` | **nothing**, for a spec |

Plus two values: `table` — the current table, usable as `:from [table]` — and `tables`, a map of
id to marker for the notebook's other tables, so a report can join. Scoped to the notebook,
matching the rule `agent-ctx` already applies to `query_table`.

### `q`

```clojure
(q {:select   [:region [[:sum :revenue] :revenue] [[:count :*] :orders]]
    :from     [table]
    :where    [:> :ts "2026-01-01"]
    :group-by [:region]
    :order-by [[:revenue :desc]]})
```

Four steps:

1. **Resolve the markers.** Each table marker is bound to a HoneySQL **subquery map**:

   ```clojure
   {:select [:*] :from [[[:raw "tbl_orders_1723… AT (VERSION => 12)"]]]}
   ```

   The model then writes ordinary HoneySQL — `:from [[table :t]]` — and `q` rewrites nothing.

   *Corrected, measured 2026-08-27.* This spec first said to substitute `[:raw …]` straight into
   `:from`. That is wrong twice over, and neither failure raises:

   - `{:from [R]}` formats as `FROM raw AS "big AT (VERSION => 1)"`, because `:from` reads a
     two-element vector as a table/alias pair. `{:from [[R]]}` is the form that works.
   - DuckDB **refuses an alias directly on a versioned relation**: `FROM big AT (VERSION => 1)
     AS t` is `Parser Error: syntax error at or near "AS"`. So the relation has to be wrapped in
     a subquery to be aliasable at all — which is what makes joins across `tables` work.
2. **Count.** `SELECT count(*) FROM ( <the query> )`, always, before the result.
3. **Format and run.** Values inlined, one string, `lake/q`. **`lake/q` and `x!` do not change**
   and no `PreparedStatement` is needed — see "SQL injection" below.
4. **Return** rows plus the true total.

### `q`'s contract, and what it is not

**The total comes back with every result, always.** Whether or not the rows were capped. This
is the "the user should understand how many results there are" requirement, and it costs one
number per call.

**Over the cap is not an error.** `q` returns the first page, the true total, and a marker
saying the rows are partial. The code carries on. A report then says "200 of 900,000" rather
than quietly saying nothing.

**A fishy ratio informs; it does not block.** A result that is 90% of its source did not
aggregate. That fact goes into the response for the user and into the retry feedback for the
model. It stops nothing.

**`EXPLAIN` is optional and off by default.** When enabled it is *what the message shows* beside
a fishy ratio, so a person can see why the count is what it is — a full scan, a filter that did
not push down, a join that fanned out. Never on the path of a healthy query.

### Rejected: a gate

An earlier draft made a large result throw, and asked the user to approve or narrow it, keyed by
a hash of the HoneySQL map. **Dropped 2026-08-27.** A large result is not a failure — it is a
table, and loci already has a handle, `page`, a cursor, `:table/total` and a grid that says
"showing 101–200 of 250". The thing being gated against is the thing the system is built to
hold.

`sci` has no continuations, so the gate could never have suspended mid-call anyway; it would
have re-run the whole function on approval. That is not wrong, it is just unnecessary.

### Rejected: paging an ad-hoc query

`loci.lake` line 797 already says why: *"`ORDER BY <col> … LIMIT … OFFSET …` is not guaranteed
stable across separate queries that differ only in OFFSET."* Two pages of one ad-hoc query can
overlap or skip rows. `page` is safe because it pages a **handle**, with a rowid tiebreaker.

So the two honest ways to walk a large result are **one open cursor** (`q-reduce`) or
**materialise and page the table** (`keep!`). Not a re-run per page.

### `q-reduce`, and where the saving is

`q-reduce` iterates one JDBC `ResultSet` and builds Clojure maps in batches, handing each batch
to `f` and dropping it.

*Corrected, measured 2026-08-27.* This spec first said the saving is on loci's side of JDBC,
because "the Clojure maps are what was going to exhaust the heap". **Both halves are wrong.**

**The cost is the driver, not Clojure.** 1,000,000 rows of a 4-column table, three ways:

| | time |
|---|---:|
| advance the cursor, read no column | 4,954 ms |
| advance + `getObject` on 4 columns | 22,297 ms |
| `lake/q` — the above, plus building maps | 27,335 ms |

So **82% is JDBC and 18% is Clojure**. Batching the maps saves the 18%.

**And heap was never the binding constraint.** Holding all 1,000,000 rows retained **220 MB**
after GC — 231 bytes a row — on a 4 GB heap. The binding constraint is **time**: 27 s for 1M
rows of 4 columns. Per row about 5 µs, per cell about 3.7 µs, so a **36-column table costs
roughly 138 s per million rows**.

**What `q-reduce` is actually for**, then: it bounds *retention*, not time and not allocation
pressure. Measured over 857,142 rows with batches dropped every 2,048: 19.6 s, and observed heap
still rose to 506 MB between collections. Retention is one batch; churn is not.

**DuckDB's JDBC does stream — verified.** `executeQuery` over 1,000,000 rows returns in **18 ms**
and the first row in **1 ms**; it does not materialise first. Fetch size is **fixed at 2048** —
DuckDB's vector size — and `setFetchSize` is accepted and silently ignored.

`q-reduce` **takes the reducing function and owns the scope.** It does not hand back a lazy seq.
A lazy seq escaping a `with-open` is the classic Clojure bug, and `lake/q` closes its statement
on the way out.

### `q-all`

Every row, realised, in the JVM. This is the choice taken on 2026-08-27: agent-written Clojure
that SQL cannot express runs over every row rather than over a sample. It is deliberately named,
deliberately documented as expensive, and the whole design is arranged so reaching for it is a
choice.

*Corrected, measured 2026-08-27, and this needs deciding again.* Extrapolated from 27.3 s per
million rows of 4 columns: **10M rows is about 4.5 minutes and 2.2 GB**, and at 36 columns about
**23 minutes**. The heap fits. The clock does not. The decision that agent-written Clojure runs
over every row is viable at 1M and is not viable at 10M, which is what the gate existed to find.

### `keep!`

```clojure
(keep! "Revenue by region" by-region)                  ; rows already in hand
(keep! "Orders per day" {:select … :from [table] …})   ; a spec — rows never enter the JVM
```

One name, two shapes, branched on shape rather than on a flag — the idiom `hydrate` and
`obj-digest` already use. Rows go through `put-rows!`. A spec goes to
`lake/create-from-relation!`, so a derived table of any size costs no heap.

Both record `{:from <source> :via <fn>}` lineage, so `rerun!` already knows how to refresh them.
Returns the new id, which the code puts in a block.

**Measured 2026-08-27, and it is the headline of this spec.** `CREATE TABLE … AS <spec>` over a
927,830-row result: **81 ms, heap delta 0 MB**. Pulling those same rows through JDBC into Clojure
maps costs about **25,000 ms and 214 MB**. Three hundred times, for the same rows.

## Snapshot pinning

**Every table marker resolves once, at the start of the run.** So every query in one report reads
the same instant and the report cannot contradict itself between two of its own paragraphs.

Every other read in loci already names the snapshot it wants. The sandbox must not be where that
lapses.

## One `:tx` at the end

`keep!` **accumulates**; it does not commit as it goes. Everything lands in one `:tx` at the
end, as `compute-clj!` already commits its `:fn` and its table together.

Two things fall out rather than being engineered:

- An aborted run commits **nothing** — `suggest!`'s rule, unchanged.
- `undo!` removes the report and every table it made, in one step.

**One leak, named.** `keep!` with a spec calls `create-from-relation!`, which writes Parquet
immediately — a side effect outside the log. An aborted run leaves that table behind.
`loci.lakegc` already reclaims tables no live event references, so it is garbage rather than
corruption. Worth writing down; not worth preventing.

## What the code may return — three shapes

`run-clj-rows` today demands a vector of maps and errors otherwise. Widen it, branching on shape:

| returns | committed as |
|---|---|
| a vector of maps | one `:table` — today's behaviour, unchanged |
| a vector of blocks | a `:report` |
| a string | a `:doc` |

`content/report?` is already the predicate that separates the second from the first. No flag and
no new grammar.

## The report block gains `:ref`

A report is already a first-class kind: a vector of `{:block :heading|:text|:table|:chart}`, with
`:report/full`, `:report/outline` and `:report/data` registered against `report?`. Add:

```clojure
{:block :ref   :id "tbl:derived-31"}
{:block :chart :ref "tbl:derived-31" :spec {…}}
```

`{:block :table :rows […]}` stays — the shipped demo report uses it and nothing should break.

A `:ref` resolves through `mold-payload`, **the same call the notebook cell renderer already
makes** for `{:ref id}`. One mechanism in two places, not a second resolver.

**Decided 2026-08-27: refs, not inline rows.** Every number in a report is then openable,
moldable, pivotable, chartable and undoable on its own, which is what loci is. It costs more
objects per report.

### The rule that keeps a report honest

**A report block may not carry inline rows from a capped result.** If `q` capped, the rows are
marked, and `keep!` is the only way those numbers reach a report — which makes a real table the
block refs.

That is what makes "load all, or some" work with nothing new built. The block points at a table;
the grid pages it; the cursor, the count and the "showing 101–200 of 900,000" caption all
shipped on 2026-08-25. The report inherits them.

Same trick as removing `rows` from the sandbox: the honest path is the only path.

## The `:fn` object gains one key

Today `{:source id :prompt p :lang "clojure" :code code}`.

A report may read several tables, so one `:source` is not enough lineage. Add `:reads #{ids}` —
**recorded from which markers `q` actually resolved during the run**, not parsed out of the code.
Then `rerun!` refreshes a report when any input changes, and reuse holds across a multi-table
report.

## What the model is told

`agent/make-clj-transform` changes from "three sample rows, write a function over `rows`":

- column names with **DuckDB types**, off the handle — not guessed from a first row
- `column-samples` for a few values per column — bounded, and already built for chunk synopses
- `row-count`
- the four verbs, `table` and `tables`
- **compute in `q`; use Clojure only for what SQL cannot say**
- that `q-all` brings every row into memory

The retry loop stays and gains one error class: a DuckDB error comes back like an eval error, so
a bad query is one the model can fix on its second attempt.

## Entry point

**Decided 2026-08-27: `compute-clj!` grows up.** Same verb, wider contract. It inherits the
`:fn` object, the retry loop, the ƒ palette and `rerun!` unchanged, and the reuse the want asks
for is already built there. No new route, no new button.

The two alternatives — a new ▤ Report verb beside Draft, and teaching Draft to choose between
prose and code — were considered and dropped as more surface for no more capability.

## The other four callers

The same `query` method fixes them, and none needs the sandbox:

| caller | after |
|---|---|
| `tools/do-query` | `query` with the viewspec; return a page with the true total |
| `tools/do-query` `:save_as` | `create-from-relation!` — no row enters the JVM |
| `fns-payload` | a column profile off the handle; the palette opens reading no rows |
| `fn-preview` / `fn-apply!` (`lib:`) | `lib:filter`, `lib:group` and `lib:top` become specs |

`loci.viewspec`'s language gains four things so the `lib:` verbs fit it: `≠` and `contains` as
filter ops, `min` and `max` as aggregates.

`lib:pivot`, `lib:delta` and `lib:share` become HoneySQL — `PIVOT`, `lag()` and `sum() OVER ()`
are two lines of data each, against a string-building job nobody should review.

`server/table-rows`, the helper all four palette callers share, is where the truncation lives.
It goes.

**`lib:delta` is already undefined over a lake table** and this does not fix it. It reads "the
previous row", and a lake table has no row order without an `ORDER BY`. `lag()` makes the SQL
expressible; it does not decide what "previous" means. Written down so nobody reports it as new.

## SQL injection

**Out of scope, decided 2026-08-27:** loci is a personal system. Values are inlined into the SQL
string and `lake/q` is unchanged.

This is what makes the change small. The alternative — a `PreparedStatement` path beside `q` and
`x!` — was in an earlier draft and is deleted.

Two consequences to know if that ever changes. HoneySQL already emits parameters separately, so
the work is a JDBC path and not a rewrite. And the agent's language stays `viewspec` for
`query_table` — six keys, closed — while HoneySQL is reachable only from sandboxed code the user
asked for. DuckDB's own guidance is that SQL from an untrusted source needs sandboxing and that
`lock_configuration` and `enable_external_access` are defence in depth rather than a substitute.

## What this does not do

- **It does not import documents.** See `docs/notes/2026-08-26-document-import-deferred.md`.
- **It does not widen the agent's own query language.** `query_table` keeps `viewspec`.
- **It does not export anything.** loci still has no download path.
- **It does not fix `lib:delta`'s row order.**

## Tests that must bite

Each must fail when its fix is reverted.

1. A lake table of more than `mold-cap` rows. `q` with `:group-by` returns the true sums, and
   **not** the sums of the first 2,000 rows. *The fixture must exceed 2,000 rows or the test
   certifies nothing.*
2. Two `q` calls in one run read the same snapshot, with a write landing between them.
3. `q` over an unaggregated 10M-row select returns a page **plus the true total**, and the total
   is not the page size.
4. `keep!` with a spec produces a table whose `:table/total` is the true count, and the run's
   peak heap does not scale with that count.
5. A returned vector of blocks becomes a `:report`; a returned vector of maps still becomes a
   `:table`. Both in one test, or the shape branch is untested.
6. `undo!` after a report removes the report **and** the tables `keep!` made.
7. `q-reduce` over a table larger than heap completes, and `q-all` over the same table is the
   only thing that fails.
8. A `:ref` block and a notebook `{:ref id}` cell resolve through the same call.

## The gate — RUN 2026-08-27

DuckDB 1.5.5 via `org.duckdb/duckdb_jdbc 1.5.5.1`, HoneySQL 2.7.1310 added at the REPL with
`add-lib`. A throwaway lake under `.tmp`, deleted after. One table, 1,000,000 rows, four columns
`(id BIGINT, region VARCHAR, revenue DECIMAL, ts TIMESTAMP)`, built in-engine from `range()` in
**239 ms**.

**1. Counting first is cheap — cheaper than the query.**

| | time |
|---|---:|
| `GROUP BY region` over 1M rows | 20.0 ms |
| `count(*)` over that same `GROUP BY` | **6.0 ms** |
| `count(*)` over `SELECT * WHERE revenue > 10` (927,830 rows) | **13.9 ms** |

This spec said the count "does the aggregation, and then you do it again". **Wrong.** DuckDB
answers `count(*)` over a grouped subquery from the group cardinality and does not compute the
sums, so the count costs less than a third of the query it is checking. Counting first is not a
cost to justify; it is the cheapest thing in the design.

**2. The `q` cap is set by time, not by memory.**

| rows into Clojure maps | time | retained heap | per row |
|---:|---:|---:|---:|
| 10,000 | 762 ms | 2 MB | 222 B |
| 100,000 | 3,024 ms | 22 MB | 230 B |
| 1,000,000 | 27,335 ms | 220 MB | 231 B |

220 MB of a 4 GB heap is nothing. **27 seconds is not.** So the cap is "how long may a `q` block
for", and on this shape 100k rows is 3 s and 10k is 0.8 s. **Start the cap at 10,000.** It is a
latency budget, and it should be config.

**3. DuckDB's JDBC streams.** `executeQuery` on 1M rows: 18 ms, first row 1 ms. Fetch size fixed
at 2048; `setFetchSize` silently ignored. So `q-reduce` bounds retention, and its batch size is
DuckDB's vector and not ours to pick.

**4. HoneySQL's `{:inline true}` is a real global option.** `(sql/format q {:inline true})`
inlines every parameter — `revenue > 10`, `region = 'r3'` — and returns a one-element vector. So
`lake/q` needs no change, as the design assumed.

**5. The uberjar delta — measured, and it is a rounding error.** The HoneySQL jar is **41,815
bytes**, and its only dependency is Clojure, which is already on the path. The uberjar today is
**166,940,673 bytes**. The delta is **0.025%**. Closed; do not raise it again.

**6. The ratio threshold — no measurement can set it, so stop looking for one.** **Decided: 0.5,
in config.** A result more than half the size of its source did not aggregate, and the number
only decides what a *message* says — it blocks nothing. Change it when someone is annoyed by it.

**7. `q-all` should say what it will cost, before it runs.** Not a budget and not a governor —
nobody asked for either. It knows `row-count`, it knows the column count off the handle, and the
gate now knows the rate: about **5 µs a row plus 3.7 µs a cell**. So it can state
*"10,000,000 rows x 36 columns — about 23 minutes"* in the response before it starts. That
informs, exactly as a capped `q` result informs, and it is one multiplication.

## What the gate found

Five errors, and the two worst are the same shape: **HoneySQL emits wrong SQL rather than
raising.**

1. **`:from [[:raw "…"]]` silently becomes `FROM raw AS "…"`.** `:from` reads a two-element
   vector as table-and-alias. Corrected in `q`, step 1.
2. **`[:over [[:sum :x] {} :alias]]` silently becomes `over AS SUM(x)  alias`.** The alias
   belongs outside: `[[:over [[:sum :x] {}]] :alias]`. Same failure mode, different clause.
3. **DuckDB refuses an alias on a versioned relation.** `FROM big AT (VERSION => 1) AS t` is a
   parser error, so the relation must be wrapped in a subquery. That is what fixed (1) properly
   and what makes joins across `tables` possible at all.
4. **The claim that the cost is Clojure's heap.** It is the driver's clock: 82% of the time is
   JDBC, 18% is map building, and the heap was never close to a limit.
5. **The claim that counting twice costs two passes.** It costs 0.3 of a pass.

**And one thing the gate gave back for free.** Errors (1) and (2) are invisible to HoneySQL and
invisible to a reader, and they are caught by **the count**: `SELECT count(*) FROM ( <the query> )`
over the malformed SQL returned `Parser Error: syntax error at or near "("`. So the count-first
step is also the **validation** step, and every query is parsed by DuckDB before its result is
trusted. That was not why it was put there.

**What it did not change.** The shape of the design — sandbox as a query surface, four verbs,
count first, capped-is-not-an-error, `keep!` for anything large, one `:tx` at the end — survived
intact, and `keep!`'s 81 ms against 25,000 ms is the measurement that makes the case for all of
it.

**One decision to reopen.** `q-all` over 10M rows is minutes, not seconds. See its section.

## Costs

- **One dependency**, HoneySQL 2.7.x, pure Clojure. Unmeasured — item 5.
- **`viewspec/interpret` splits in two.** The question half moves to the store; the shape half
  stays in Clojure and runs over an answer that is small by construction.
- **`agent/make-clj-transform`'s prompt is rewritten**, which is the part most likely to need
  two or three passes against the real model.
- **One protocol, two implementations to keep in parity.** `InlineTable` must answer `query` the
  same way `Lake` does, which is test 5's shape and the reason `InlineTable` exists.
