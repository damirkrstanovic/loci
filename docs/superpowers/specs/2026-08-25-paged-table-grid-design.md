# The Rows view becomes a table you can actually read

Date: 2026-08-25
Status: **designed, nothing built.** Every number below marked *measured* was taken on
2026-08-25 against the running container or in the nREPL; every one marked *unmeasured* is
named as such and nothing in the design rests on it.

A 36-column CSV imported cleanly and then rendered as this:

```
2…  Бу…  fa  bu…  0  bu…  6   Ja  2(  5f…  xl  2(  anr  fa  0   Оп…  6 2  Бу…  6…  htt…  0  0  2
```

The import is not the problem. The Rows view is. This spec covers four things at once, at the
user's explicit instruction after the alternative — two specs, the grid first — was offered and
declined: **paging, a sticky header, column widths that work, sorting, column show/hide, and
aggregate views computed over the whole table rather than over a sample.**

---

## 1. What is actually wrong, measured

Against the live container on port 7777, over `tbl:datasets_2026_06_14_20_03` — **36 columns,
2,000 rows**.

### 1.1 The width algorithm computes a scale and then throws it away

`tableHTML` (`resources/public/index.html:2249`) sizes each column in characters:

```js
const width=c=>{
  const seg=Math.max(...c.split('_').map(w=>w.length));
  const longest=shown.reduce((m,r)=>Math.max(m,String(r[c]??'').length),seg);
  return Math.min(Math.max(longest+2,7),28);   // +2 for the cell's padding
};
const widths=cols.map(width), total=widths.reduce((a,b)=>a+b,0);
```

and then emits `<col style="width:{100*w/total}%">` into a table that is
`width:100%; table-layout:fixed` (`index.html:168`).

**Normalising to percentages discards the absolute scale.** The table is therefore exactly the
panel width no matter how many columns it has, and the 7-character floor becomes 0.7 characters
at 36 columns. Measured, with the clamped character widths summing to **584** and a panel of
roughly 1,700 CSS px:

| column | asked | got | text px after 16 px padding | chars that fit |
|---|---|---|---|---|
| `owner_id`, `owner` | 7 ch | 20.4 px | **4.4 px** | 0.7 |
| `archived` | 10 ch | 29.1 px | 13.1 px | 2.2 → `fa` |
| `metric_views` | 8 ch | 23.3 px | 7.3 px | 1.2 → `0` |
| `id` | 26 ch | 75.7 px | 59.7 px | 10.1 → `2…` |
| `description`, `title`, `url` | 28 ch | 81.5 px | 65.5 px | 11.1 → `Бу…` |

That table *is* the screenshot. **The fix is to delete the normalisation.** Everything
else in this section follows from the table being allowed to be wider than its panel.

### 1.2 The horizontal scrollbar is a rounding artefact

`.toFixed(2)` on 36 percentages drifts up to 0.18% over 100% — about 3 px on a 1,700 px table.
That is the scrollbar in the screenshot, and its thumb spans nearly the whole track. There is no
wider table behind it. **Scrolling cannot recover a single character.**

### 1.3 The header is gone because `thead` is not sticky

The scroll box is `max-height:360px; overflow:auto` and nothing pins the header. `rg
"position:sticky"` over `index.html` finds two hits, neither on a table. Scroll one row and the
column names leave — which is the state in the screenshot, and it removes the only thing that
would let a reader decode `fa` as `archived`.

### 1.4 The tooltip is the only reader, and one column's tooltip is 16 kB

Every `<td>` carries `title="<full value>"`. In this table `description` reaches **16,344
characters**. The interaction the current view offers is: hover each cell in turn.

### 1.5 Row 2,001 is unreachable, and that is the real defect

`tbl/mold-cap` (`src/loci/tablestore.clj:322`) is **2,000**, and its docstring already states this
spec's premise:

> "The point of DuckLake is tables of millions of rows, and a payload that materialized all of
> them would move the problem from the substrate to the browser."

So a 10M-row table does **not** ship 10M rows — `hydrate` (`tablestore.clj:555`) asks for
`{:limit mold-cap}` and takes `:table/total` from a separate count. The 5,428,753-byte payload
measured for this table is 2,000 rows × 36 columns at 2,715 bytes per row, inflated by
`description`.

But **the cap is a truncation, not a window.** No offset reaches the request path. The shell
draws 60 of 2,000 of 2,000. At the stated 10M-row target that becomes 60 of 2,000 of 10,000,000 —
**99.98% of the table with no way to reach it.** Paging is not a nicety here; it is the only thing
that makes the rest of the table exist.

### 1.6 The plumbing is built and unused

`TableStore/rows` already takes `{:offset :limit}` (`tablestore.clj:19`), and the Lake
implementation pushes both to SQL (`src/loci/lake.clj:1227`):

```clojure
(rows [lk h {:keys [offset limit]}]
  (q-as lk (str "SELECT " (select-cols (:cols h))
                " FROM " (from-of h)
                (when limit  (str " LIMIT "  (long limit)))
                (when offset (str " OFFSET " (long offset))))
        (:cols h)))
```

Nothing has ever called it with an offset.

### 1.7 A notebook payload carries one full mold per cell

`notebook-payload` (`server.clj:936`) merges `mold-payload` into every `:ref` cell. The screenshot
is a notebook cell, so `/api/notebook` for that notebook already carries the 5.4 MB, and a
notebook with five such cells carries five copies in one response.

### 1.8 The aggregate views describe a sample and look like they describe the table

`:table/rows` renders `identity`, so only Rows and Cards ship raw rows. `Summary`, `Pivot`, `Bar`,
`Histogram`, `Over time` and `Headline` aggregate server-side and ship small results — **over
`hydrate`'s 2,000-row sample.** At 10M rows that is 0.02% of the table, presented without
qualification.

It is worse than wrong sums. The **predicates** run over the sample too, so which views are
offered is inferred from it; and column *selection* is inference over values
(`src/loci/content.clj:227`):

```clojure
(defn- cat-col [rows]
  (->> (string-cols rows)
       (keep (fn [c] (let [d (count (distinct (map #(get % c) rows)))]
                       (when (and (> d 1) (<= d 12)) [d c]))))
       sort first second))
```

A column with 12 distinct values in the first 2,000 rows and 500 in the table gets `Bar` offered
and then groups by 500 categories.

---

## 2. Paging: keyset, and one control everywhere

**Decision: keyset pagination, chosen without measuring deep `OFFSET` cost.** The alternative —
measure first, then decide between page numbers and next/previous — was offered and declined. The
design therefore makes **no claim about seek cost anywhere**, and the control is next/previous
only. This is the conservative direction: a page-number control that hangs on the last page of a
10M-row table is a control that has to be taken away again.

### 2.1 A key exists for one handle mode and not the other

Probed in the nREPL over a **50-row** table. Per the working agreement, a fixture this size
**establishes that the mechanism works and says nothing about cost.**

A materialised DuckLake table exposes `rowid`, 0-based and dense:

```
SELECT rowid, name FROM t_tbl_probe_… LIMIT 3
→ {:rowid 0 :name "r1"} {:rowid 1 :name "r2"} {:rowid 2 :name "r3"}
```

Keyset composes with a user-chosen sort, through DuckDB row comparison:

```
SELECT rowid, name, n FROM t_… WHERE (n, rowid) < (48, 2) ORDER BY n DESC, rowid LIMIT 3
→ rowid 46, 45, 44        ← exactly the continuation of ORDER BY n DESC, rowid
```

**A `read_csv` passthrough source has no row key at all.** Both candidates fail:

```
SELECT rowid FROM read_csv('…')                   → Binder Error: "rowid" not found
SELECT … FROM read_csv('…', file_row_number=true) → Invalid named parameter "file_row_number"
```

`file_row_number` is a Parquet-reader option; DuckDB enumerated all 40-odd valid `read_csv`
parameters in the error and it is not among them. The only row number over `read_csv` is
`row_number() OVER ()`, which materialises the whole scan — the exact cost keyset exists to avoid.

**So the two modes of `from-of` are not the same problem.** The mitigating fact, and the reason
this is acceptable rather than a blocker: a passthrough read re-parses the entire CSV on *every*
read — that is what `read_csv` in a `FROM` clause means, and it is already what page 1 costs
today. `OFFSET` adds to a cost already being paid rather than introducing a new one.

### 2.2 The window

One new concept, carried end to end:

```clojure
{:limit  100        ; clamped to 1..200
 :cursor nil        ; opaque string; absent means page 1
 :sort   nil        ; [col dir] or nil for natural order
 :cols   nil}       ; visible columns, or nil for all
```

Route: `GET /api/mold?id=&view=&at=&limit=&cursor=&sort=&cols=` → `mold-payload` → `hydrate` →
the store. `at` is unchanged and travels with everything else; a page read in the past is a page
of the frozen store, exactly as today's mold is.

### 2.3 `TableStore` grows `page`; `rows` is not touched

**`rows` keeps its signature and its meaning.** Thirteen call sites reach `hydrate`, and twelve
of them are not `mold-payload` — `server.clj:1175,1198,1237,1302,1390,1477,1996,2023,2540,2573,2643`
and `tools.clj:148`. Every one of those wants a *sample* for a digest, a tool result or a
description, not a page. `mold-cap` stays
2,000 and stays their default. Changing `rows` would push a paging concept into agent digests
that have no use for one.

A new protocol method carries the window:

```clojure
(page [ts h window]
  "One page of rows for `handle`, plus the cursor that starts the next page.
   => {:rows [...] :next <cursor-or-nil>}")
```

**Corrected 2026-08-25 while planning, twice, and both corrections shrink it.**

`:prev` is gone. §2.4 gives the shell a stack of the cursors that began each page it has visited,
which is exact; a server-computed `:prev` would be a second source of truth able to disagree with
it. `:from` and `:to` are gone for the same reason: the shell knows its own page index, so
`from = page-index × limit + 1` is exact and needs nothing from the server. `:table/total` — which
comes off the handle as `(:rows h)`, not from a `COUNT(*)` — supplies the rest of
`showing 101–200 of 10,000,000`.

`:next` is produced by asking for `limit + 1` rows: if the extra row comes back there is another
page, and the cursor is built from the last row actually returned.

**Keyset is for natural order only. A sort pages by offset.**

| handle | sort | cursor carries | query |
|---|---|---|---|
| Lake, materialised | none | `rowid` | `WHERE rowid > k ORDER BY rowid LIMIT n+1` |
| Lake, materialised | a column | an offset | `ORDER BY <col> <dir>, rowid LIMIT n+1 OFFSET m` |
| Lake, `:source` passthrough | either | an offset | `[ORDER BY …] LIMIT n+1 OFFSET m` |
| `InlineTable` / literal vector | either | an index | `drop`/`take` |

The compound-key form **works** — §2.1 proves it — but taking it would mean solving two problems
that natural-order keyset does not have: a mixed-direction tiebreak (`ORDER BY col DESC, rowid ASC`
is not what `(col, rowid) < (v, k)` selects, and the probe could not see the difference because its
fixture had no ties), and NULLs in the sort column, where a row comparison against NULL is NULL and
silently drops rows. Both would be solved for a cost nobody has measured, because a sorted page
over 10M rows is dominated by the `ORDER BY` however it is paginated. So: natural order gets the
seek, a sort gets the simple thing, and the compound key stays written down here as the first thing
to reach for if sorted deep pages are ever felt to be slow.

**The cursor is opaque to the shell in every case.** That is the whole reason one control works
everywhere — and it is what lets the table above change without the shell changing at all. It is
the one invariant in this section that a test must defend: the shell must never branch on cursor
content.

### 2.3.1 Measured 2026-08-25 while building: the tie hazard is real, and OFFSET has one of its own

Two findings from implementing §2.3, both measured over the 250-row / 5-group fixture. Per the
working agreement: at that size they **establish that the mechanisms behave as described**, and say
nothing about cost at scale.

**The tie hazard §2.3 asserts is real, and now has a number.** Keyset on the sort column alone —
`WHERE grp > '<last>' ORDER BY grp`, cursor carrying the last sort value — walked over 5 groups of
50 at a limit of 40 returns **200 of 250 rows. 50 lost, 20%**: each page boundary lands inside a
tied run and the `>` predicate skips whatever remains of it. That is the evidence for this
section's decision to page a sort by offset, and until 2026-08-25 the decision rested on reasoning
alone.

**The plan's own sabotage for this could not have shown it, and said it would.** Dropping
`(nil? srt)` from `seek?` loses **zero** rows: the SQL `cond` takes the `seek?` branch first, so the
sort never reaches the query and the walk is a complete rowid walk in the wrong order. It fails on
*"the walk is in sort order"* and on nothing else. A sabotage that fails for a different reason than
the one written next to it is the failure mode the whole discipline exists to catch, and it was
caught by tracing the branch rather than by reading the table.

**`ORDER BY … LIMIT … OFFSET …` is not stable across pages when the sort column has ties.**
Measured: the same query at `OFFSET 0` and at `OFFSET 40` do not slice one consistent order —
DuckDB's top-N pushdown resolves a tie differently depending on how many rows the plan must
materialise, so two pages can both return a row and both skip another. This defeated the tie test
**on the unsabotaged implementation**, at 205 distinct rows out of 250 walked.

So the offset branch needs a tiebreaker that makes the order **total**, and the two handle modes get
different ones:

| handle | sorted `ORDER BY` |
|---|---|
| Lake, materialised | `<col> <dir> NULLS LAST, rowid ASC` — a unique key, so the order is total outright |
| Lake, `:source` | `<col> <dir> NULLS LAST, <every other projected column>` — no `rowid` exists, so the widest available key |

A `:source` handle's fix is total on the row's **values** rather than on its identity. Two rows
identical in every projected column are indistinguishable to any caller, so the concatenation of
pages still yields the correct multiset — which is all that can be observed.

**The `:source` fix is a correctness fix whose cost is unmeasured.** A wider sort key makes each
comparison more expensive; it does not change the algorithmic class, and the `ORDER BY` already
dominates a passthrough read. Nobody has measured it at the scale this design is for, and the
fixture cannot say. It is here because Task 8 makes every column header a sort control on every
table, so a person sorting a live CSV by a repeated value is an ordinary act and not an edge case.

### 2.3.2 What the unsorted passthrough branch depends on, named rather than assumed

An unsorted `:source` page carries **no `ORDER BY` at all** — and that is the most ordinary thing a
person will do with a connected CSV, so the walk's "none lost, none served twice" contract rests on
`read_csv` scanning the file the same way across separate queries at different offsets.

**That rests on `preserve_insertion_order`, which is DuckDB's own default of `true`, and loci sets it
nowhere.** Established from DuckDB's documentation — with the setting on, the system does not
re-order results that carry no `ORDER BY` — and confirmed as the live value on the lake's
connection. It is named here and in `page` so that it is a documented dependency rather than an
assumption, and so that anyone who turns it off knows what they are turning off.

**A failed reproduction is recorded here as a failed reproduction, not as safety.** With the setting
forced to `false`, a walk at 250 rows and two different probes at 200,000 rows produced no lost or
duplicated row. That is a negative result. It does not establish the contract holds at the 10M-row
parallel-scan scale this design targets — it only says the hazard did not appear where it was
looked for, which is worth less than the tie hazard's positive reproduction at 250 rows in §2.3.1.
No `ORDER BY` was added to the unsorted branch on the strength of it: putting a full sort on the
common path is a cost nobody has felt.

### 2.4 Previous

Keyset has no natural backwards step. The shell keeps a stack of the cursors that began each page
it has visited; `‹ Prev` pops it. This makes Prev exact and free, and it means Prev is available
only for pages you arrived at by paging forward — which is the only way to arrive at page N when
there is no jump.

---

## 3. The renderer

`tableHTML` is rewritten. The character heuristic in §1.1 is **kept** — it is a good heuristic and
it was never the bug.

- **Absolute widths.** Emit `min-width: <n>ch` per column from the same clamp. Delete the
  percentage normalisation and the `<colgroup>` with it. The table is as wide as its columns; the
  wrapper scrolls horizontally.
- **Sticky header.** `thead th { position: sticky; top: 0 }` inside the scroll box.
- **Frozen first column.** `th:first-child, td:first-child { position: sticky; left: 0 }`, with an
  opaque background — a sticky cell over scrolling content shows through otherwise. The corner
  cell needs both offsets and a higher `z-index` than either.
- **Headers are sort controls.** Click cycles ascending → descending → natural. The current sort
  is drawn in the header, not only in state.
- **The tooltip is clipped to ~500 characters.** A 16 kB `title` attribute is not a tooltip, and a page of
  full values duplicated into attributes is a second copy of the payload in the DOM.
- **The caption becomes the pager**: `showing 101–200 of 10,000,000   ‹ Prev   Next ›`, with a
  page-size select of 25 / 50 / 100 / 200 defaulting to 100.
- **A column chooser.** All 36 columns are shown by default — a wide table must never understate
  what it holds — and the chooser hides what you do not want. Hidden columns travel in the window
  as `:cols`, so hiding `description` cuts the payload rather than only the pixels.

The 60-row client-side cap disappears; the page size replaces it.

---

## 4. View state lives in the browser

`localStorage`, keyed by object id: `{sort, hidden, limit}`.

**Not the substrate.** The event log is what `?at=` rewinds and `undo!` reverses. Clicking a
column header is fiddling with a lens, not an act on content, and every such click becoming a
point in history you can time-travel to is noise in the one log that has to stay meaningful.
`:view` on a notebook cell stays durable exactly as it is today — that is a choice made once, not
a knob.

Every read and write is wrapped: a private window, cleared site data or a browser set to block
site data must render the table correctly with no stored value.

---

## 5. Viewers that can ask the database

### 5.1 The seam

`mold/mold` calls `(:render viewer) (:value o)` — a vector of rows, with no way to reach the
store. `mold-payload`'s own docstring already names this constraint where it explains why an
entity cannot be a viewer.

A viewer may declare `:needs-store true`; those receive `(render value {:store ts :handle h})`.
Every existing viewer is unchanged and keeps its one-argument `:render`.

**Explicit rather than arity dispatch**, so that a viewer that takes the wrong number of arguments
fails at registration instead of at render time. This is the one decision in this spec taken on
taste rather than on evidence; if the implementation finds a reason it is wrong, say so in the
plan rather than working around it.

### 5.2 Predicates and column choice stop reading the sample

`numericish?`, `categorical?`, `timeseries?`, `measure-col`, `cat-col` and `rate-col?` read:

- the handle's `:cols` and DuckDB's declared column types, and
- `column-samples`, which **already queries the whole table past the mold cap and is memoised per
  handle** — both facts have tests: `sampling-sees-past-the-mold-cap` and
  `sampling-an-immutable-handle-happens-once` (`test/loci/lake_test.clj:313,325`).

This is deliberately reuse and not a new mechanism. The consequence worth stating: `alternatives`
stops needing 2,000 rows to build a menu.

**The menu stays sampled and the numbers become exact.** Making `categorical?` exact would mean a
`COUNT(DISTINCT col)` per string column on every open of every table, including tables nobody ever
charts. A wrong number is a lie; a slightly odd menu entry is an entry you do not click.

### 5.3 The aggregates

| viewer | becomes |
|---|---|
| `summary` | one query of `min/max/avg/sum/count` and null counts across the numeric columns |
| `bar`, `pivot` | `GROUP BY <cat>` with `SUM`/`AVG` per the existing rate rule |
| `histogram` | bucketed in SQL over the true min/max |
| `line`, `kpi`, `cohort` | `GROUP BY month` |

For a literal-vector `:value` or an `InlineTable` handle the **existing Clojure functions stay and
are already correct** — the whole table is in memory there, so the sample is the table.
`InlineTable` is a dry-run and test device (`chunks.clj:195`, `corpus.clj:655`, and
`tablemigrate.clj` exists to migrate away from it); every real table lands in the Lake. This is why
§5.3 is a Lake-only rewrite and not a second implementation of seven functions.

**Live sources compute rather than refuse.** A summary over a passthrough re-parses the file — and
so does page 1 of Rows, and so does every other read of that table. One more scan of a cost
already being paid keeps live and materialised tables behaving identically, and adds nothing to
explain.

---

## 6. Notebook cells

`notebook-payload` ships **one page per `:ref` cell**, and cells get the same pager as the object
view. Same renderer, one code path. This is the fix for a notebook whose five table cells carry
five multi-megabyte payloads in one response, and the screenshot that prompted this spec is a
notebook cell, so it is the surface the work is actually judged on.

---

## 7. Phasing

One spec, four phases, each shippable and testable on its own — so that a defect in phase 4's plan
cannot take phase 2 down with it.

1. **The window.** `page` on `TableStore`, three implementations, `/api/mold` parameters,
   `hydrate`. Server only, no visible change.
2. **The renderer.** Absolute widths, horizontal scroll, sticky header, frozen first column, the
   pager, page size. This is the screenshot fixed.
3. **Sorting and column show/hide.** Header click, the chooser, `:sort` and `:cols` through the
   window, localStorage.
4. **Viewers that ask the database.** The `:needs-store` seam, predicates off `column-samples`,
   the SQL aggregates.

---

## 8. Testing

The working agreement's rule governs: **prove every test fails when its bug is reintroduced, and
read the failure rather than counting failures.**

- **Width.** In the browser suite, a 36-column fixture must produce a table whose scroll width
  exceeds its client width, and `owner_id` — measured above at 0.7 characters — must render its full 4-character values. Measured off
  the DOM — a prose assertion about "readable" columns certifies nothing. Reintroducing the
  percentage normalisation must fail it.
- **Sticky header and frozen column.** After scrolling the box down and right, the header row and
  the first column are still at their edges. Both are position assertions, not style assertions:
  asserting `position:sticky` is in the stylesheet passes while the header scrolls away.
- **Keyset continuity.** Page N+1's first row is the row immediately after page N's last — under
  natural order, and under a sort **whose sort column contains duplicates**. The tie case is the
  entire reason `rowid` is in the compound key, so dropping it from the key must fail this test
  and nothing else.
- **The cursor is opaque.** The shell must not branch on cursor content. A test that feeds it a
  cursor of a different internal shape and still pages correctly.
- **Passthrough paging.** A `read_csv` source pages correctly with no `rowid` in sight — the mode
  where keyset is impossible must still go through the same protocol method and the same control.
- **Aggregate exactness.** `summary` over a table whose rows past 2,000 change the answer — the
  shape of `sampling-sees-past-the-mold-cap`, which already exists to prove exactly this for
  `column-samples`. **This test must fail against today's code.** If it passes either way it
  certifies rather than tests.
- **Payload.** A 36-column × 2,000-row table under a 100-row window, measured in bytes against the
  **5,428,753** recorded in §1.5. Assert an order of magnitude, not a fragile exact figure.
- **The ten `rows` callers.** `clojure -M:test` must show `mold-cap` behaviour unchanged for
  digests and tools. If `rows` is touched at all, this is where it shows.

Both suites: `clojure -M:test` **and** `npm run test:browser`. Phase 2 and 3 are shell changes and
the browser suite is the only thing that sees them.

---

## 9. Deliberately not in this spec

- **Jump-to-page and page numbers.** Available the day someone measures `rowid` seek cost and
  wants them; the cursor is opaque precisely so that adding them later changes no shell code that
  reads its content.
- **A stable key for passthrough sources.** There isn't one, `row_number() OVER ()` is not worth
  its cost, and offset paging over a file that is re-parsed anyway is the honest answer.
- **Exact `categorical?`.** §5.2 states the trade and the price of reversing it.
- **Virtualised scrolling / infinite scroll.** A page of ≤200 rows does not need row recycling, and
  infinite scroll would make the frozen header the only way to know where you are.
- **Column resizing and reordering by drag.** Real table features, nobody has asked, and the
  character heuristic exists so that the default width is usually right.
- **Server-side row filtering.** Sorting and column selection travel in the window; a `WHERE` does
  not. `query_table` is where filtering lives today.
- **Making `?at=` rewind a source's file.** Unchanged from the phase 3 decision: `as-of` rewinds
  loci's log, never the file.
