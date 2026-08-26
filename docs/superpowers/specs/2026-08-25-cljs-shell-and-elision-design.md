# The shell learns ClojureScript, and a hole that knows it is a hole

Date: 2026-08-25
Status: **built and measured**, 2026-08-26, on `spike-cljs` — twelve tasks from the spike
(`713568e`) to `bf6f44d`, where `CLJS_GRID` and `tableHTML` were both deleted and a molded table
simply *is* the ClojureScript grid. Green at the end: `clojure -M:test` 915 tests / 4,817
assertions, `npm run test:cljs` 30 / 78, `npm run test:browser` 180. Every number marked
*measured* below was taken on 2026-08-25 against this machine or this repository.

### What building it changed

**The browser suite cost was over-estimated, and by a lot.** §7 predicted that "the tests that
drive a table's DOM are written against `tableHTML`'s markup and will need rewriting". **Zero
were rewritten.** Four files matched `rg -ln "table\.mold|tableHTML|thead|tbody" test/browser/`:
one is the new grid's own test, one is the build's, and the two remaining — `reclaimed.test.mjs`
and `sources.test.mjs` — turned out on inspection to *use* a table to reach something else
rather than to be about a table's markup. Both kept their markup and their claims.

What they needed instead was **synchronisation**, which the spec did not foresee at all. Under
`tableHTML` the rows were in the string the shell built; the grid is mounted into a seam and
filled by its own Transit request, so an instant now exists in which the panel is open and the
rows are not there. Ten assertions were reading that instant. It was found by *measuring* rather
than by reading: delaying `loci.grid.mount` by 300 ms — a fetch on a table of the size this
design targets — turned seven tests in `sources.test.mjs` red, and 3 s turned an eighth. Across
all 180 tests in seventeen files those eight, plus the flag's own build test, were the only
casualties. Three of the eight name no table markup at all and the grep does not find them: they
read `/EMEA/` and `/café/` out of the panel's text. One was worse than late — `doesNotMatch(body,
/�/)` is satisfied by an empty panel, so it had been certifying that no replacement character
reached a table it never waited for.

The mirror image, in `reclaimed.test.mjs` and for the two broken-source blocks: "no `table.mold`
on screen" is no longer the same claim as "no table is coming". Those count the **seam** as well.

**A mounted grid does not survive a re-render of the surface it sits in**, and §6 did not say so.
The grid's state is an atom keyed by the seam *element*, so when the shell redraws a notebook body
the seam is a new node, `wireSourceButtons` mounts a new grid, and that grid fetches. Measured by
stamping the cell's `.bd` element to see whether it was the same one: `enter(i)` replaces the body
the boot had already filled, and for one 25 ms sample the cell holds a mounted seam and no table —
table, then nothing, then table. So a re-render of a surface showing tables costs one `/api/mold`
per table and flashes an empty one while it is in flight. Bounded and small, because the payload is
one page — but it is the flip side of keeping all of the grid's state in the client, and the spec
should have named it. Every browser assertion that reads a table now reads both counts in one
`evaluate`, because sampling the two separately can catch that gap.

**Replicant interpolates nothing into handler data** (§4.2 left this open, and Task 8 was told to
find out rather than guess). `core/get-event-handler` wraps the handler in `(fn [e] (*dispatch* rd
handler))` and hands the data back verbatim — there is no placeholder for "the value of the event
that fired", so the `<select>`'s value is read off `:replicant/node`, which is `(.-target e)`. A
second finding beside it: **`:selected` on the options, never `:value` on the select.**
`core/create-node` sets attributes before appending children and `value` on a select is a property
assignment, so a `:value` there leaves the control reading `25` while the size in force is 100.

**One sabotage in the plan could not work, and was replaced rather than pretended.** "The frozen
column is opaque" was to be checked with `elementFromPoint`, which hit-tests: a transparent cell
wins a hit test exactly as an opaque one does, so the assertion passed while rows showed through.
The claim is now made by the honest instrument — two screenshots of the same viewport rectangle,
before and after scrolling, decoded through a canvas because Node has no PNG decoder. Measured: 0
of 14,896 pixels differ with the background, 807 without it. A neighbouring one was wrong in the
other direction: the sticky-header test measured the *first* `th`, which is sticky for the frozen
column's sake, so deleting the header rule left it green. It measures the second `th` now.

**And the port exposed a shell defect that predates it.** Making the sort header a real `<button>`
so a keyboard can reach it revealed that Space did nothing: the global "type anywhere to search"
handler tests `e.key.length===1`, which Space passes, so focus jumped to the LEAP field and the
keypress a button's activation depends on landed on an input. Its field guard covered
INPUT/TEXTAREA/SELECT — and so already covered the column chooser's checkboxes — but not BUTTON or
SUMMARY. One key, one guard, and nothing to do with ClojureScript.

**Two smaller ones.** The caption groups digits with a regex rather than `toLocaleString`, which
answers with whatever the machine's locale says and would pass here and fail on a colleague's
laptop. And `tableHTML`'s two non-molder callers became a local `staticTable` as §8 planned, minus
the width arithmetic — `table-layout:auto` is the same answer in one CSS property — but **keeping**
its 60-row cap, which is the only bound on how many rows a report block can put in the DOM.

**Supersedes the shell half of `2026-08-25-paged-table-grid-design.md`.** That document's §1
(the measured defect) and §2 (paging, `TableStore/page`, the opaque cursor) stand and are built.
Its §3 (the renderer), §4 (view state) and §6 (notebook cells) are replaced by this one. Its §5
(viewers that ask the database) is untouched and still pending.

---

## 1. Why this exists, honestly

The superseded spec planned five tasks of hand-written vanilla JavaScript to give one table a
sticky header, a frozen first column, sorting, a column chooser and a pager. The user's objection
was that these are ordinary components and hand-writing them is work for nothing. The objection
was correct, and the defence offered for it — one sentence in `docs/walkthrough.md` reading
*"No framework — the substrate is the framework"* — was a slogan doing the work of an argument.

Two things followed from taking it seriously.

**Clerk was read rather than recalled.** loci already depends on it (`:dev` and `:start` carry
`io.github.nextjournal/clerk 0.17.1102`), it is the acknowledged reference for loci's moldable
half, and it solves the same problem: a JVM decides how a value should be viewed, a browser
renders it. What it turned out to have, and not have, is §3.

**A spike was run rather than argued.** shadow-cljs, Replicant and Transit through the existing
shell, behind a `?cljs` flag. It answered four questions and raised one the design had not asked.

---

## 2. What Clerk actually has, measured by reading it

`src/nextjournal/clerk/render/table.cljs` is **5,416 bytes**. That is the whole table renderer.

| | Clerk |
|---|---|
| Horizontal scroll | ✅ `{:width "max-content" :max-width "100%"}` inside `overflow-x-auto` |
| Sticky header | ⚠️ npm `vh-sticky-table-header`, which **clones the table** and overlays the clone |
| Numeric right-align | ✅ `number-col?` — loci already has the same idea |
| Nested values in a cell | ✅ `inspect-presented` recurses, so a cell can be a map, an image, a table |
| Error viewer for bad shapes | ✅ names the four formats it accepts |
| **Column sorting** | ❌ |
| **Frozen / pinned column** | ❌ |
| **Column show/hide** | ❌ |
| **Pager, page size** | ❌ — it loads more |

The four absences were checked by code search across the whole repository, not by reading the
renderer alone: `sortable` 0 hits, `pinned` 0, `column-visib` 0, `hide-column` 0. `ensure-sorted`
exists and sorts *map keys*, not columns.

**So "copy the components from somewhere" has no answer in Clerk.** Its table is an inspector, not
a grid. What Clerk has that is worth taking is not a component at all — it is a protocol.

`{:width "max-content"}` is also worth taking on its own: it is one CSS property where the
superseded spec reached the same result through a per-column `min-width: <n>ch` calculation.

---

## 3. The elision protocol

An **elision** is an omission — the `…` where something was cut. Clerk sends a node *in place of*
the part it left out:

```clojure
{:nextjournal/viewer 'elision-viewer
 :nextjournal/value  {:n 25 :offset 25 :total 2000 :path [0 1] :unbounded? false}}
```

The browser draws that as a row reading `1,975 more`. Clicking it calls a `fetch-fn` with those
opts; the server re-presents at that path and the gap is filled.

**A truncation is silence about what is missing. An elision is a hole that knows it is a hole** —
how much, where in the structure, and what to send back. That is the difference from `mold-cap`,
which cuts at 2,000 rows and says nothing, leaving row 2,001 unreachable.

Of what the protocol offers, one is worth having today, one is worth reserving room for, one
solves a problem loci has for a different reason than Clerk does, and one cannot be used at all.

**Worth having now: the budget.** `:!budget` is decremented as items are presented and each page is
`(min n @!budget)`, so a value holding many collections does not ship a full page for each. This is
not hypothetical here — `notebook-payload` merges one mold per `:ref` cell, so a notebook with five
table cells is five pages in one response. `mold-cap` bounds one table; a budget bounds the
response, which is the thing that was actually too big.

**Worth having eventually: positional addressing.** `:path` names *where* the gap is, so a
collection nested inside a value expands in place. loci cannot use this yet and should not pretend
to: `normalize-rows` refuses a vector or a map in a column, so every cell is a scalar and a table
payload has exactly one hole, at the end. **The shape carries `:path` anyway** — a single-element
path today — because the alternative is inventing a second shape the day cells stop being scalars,
and because a notebook payload already has one hole per cell and needs to say which.

`bounded-count` also earns its place directly. Clerk never forces a lazy sequence to find
`:total`; it counts to a bound and sets `:unbounded? true` if it hits it, so the label reads
`1,975+ more`. loci needs exactly this for a different reason: `:table/total` comes off the handle
as a count recorded at connect, and a live passthrough source's file can have changed since. Today
loci would print a confidently wrong total.

**Not worth having: Clerk's `drop+take-xf`.**

```clojure
(defn drop+take-xf [{:keys [n offset] :or {offset 0}}]
  (cond-> (drop offset) (int? n) (comp (take n))))
```

That is `drop`/`take` over an **in-memory value**. For a 10M-row DuckLake table every row would
have to be realised first. loci's `TableStore/page` — keyset on `rowid` in natural order, an
offset otherwise, behind an opaque cursor — is the half Clerk does not have and cannot borrow.
**It stays exactly as built.**

### 3.1 The decision Clerk's protocol does not make: replace, not append

**Decided 2026-08-25. loci's elision REPLACES the page. Clerk's appends.**

Clicking Clerk's `N more` accretes: the fetched rows join the ones on screen. That is right for an
inspector, where you are drilling into one value and want to see more of it.

It is wrong here, and the spike is what made it obvious. Its elision carried an *append* label
over *replace* behaviour: clicking `1 more` on a 26-row table gave one row and a caption reading
`showing 1 of 26`. The cursor was correct — it fetched precisely the right slice — but the two
halves of the interaction disagreed.

Accretion loses the thing paging is for. At the stated 10M-row target, clicking `more` forty times
puts four thousand rows in the DOM; bounded payload **and bounded DOM** were both the point, and
`§1.5` of the superseded spec records the browser being the place the problem was moved to.

So loci takes the elision's *shape* and not its *semantics*. Concretely, the payload's rows branch
carries:

```clojure
{:elision {:n         100          ; page size, as honoured — already clamped by `page-limit`
           :offset    100          ; how many rows precede this page, for the caption
           :total     10000000     ; from the handle; may be stale for a live source
           :unbounded? false       ; true when `:total` could not be established
           :path      [:rendered]  ; which hole, for a payload with more than one
           :cursor    "eyJrIjo5OX0="}}  ; OPAQUE — handed back, never read
```

- fetching **replaces** the rows rather than joining them
- there is a **Prev** control, which an append model has no need of
- the label says `showing 101–200 of 10,000,000`, not `1,975 more`
- `:offset` is the server's, and the shell's own page index must agree with it; where they
  disagree the server's wins, because the shell's is arithmetic and the server's is a fact

Prev is a **stack of the cursors that began each visited page**, kept by the shell. That is exact,
and a server-computed `:prev` would be a second source of truth able to disagree with it.

**This is a deliberate departure and the reason must survive in the code**, because the next reader
will find Clerk's protocol and wonder why loci's differs.

---

## 4. The stack

Measured in the spike unless marked otherwise.

| Layer | Choice | Version |
|---|---|---|
| Build | **shadow-cljs** | 3.2.0 |
| Rendering | **Replicant** | 2025.06.21 |
| Wire | **Transit** | `transit-clj` 1.0.333 / `transit-cljs` 0.8.280 |
| State | one atom and a dispatch function | — |
| Styling | **the existing 565-line stylesheet** | — |
| Grid | **built here** | — |

### 4.1 shadow-cljs

It reads the `deps.edn` that already exists — `{:deps {:aliases [:cljs]}}`, the same arrangement
Clerk uses. Node v26.7.0 is already installed for Playwright. Measured: **11.1 s** to compile, ~25 s
wall including JVM start.

**`:replace-deps`, not `:extra-deps`, and this is not a style preference.** `datalevin/datalevin
1.0.0` pulls `io.github.nextjournal/markdown`, whose jar carries a `deps.cljs` declaring npm
dependencies. With the server's classpath inherited, shadow-cljs ran `npm install --save` for
Clerk's entire markdown-it stack and rewrote `package.json` — **six packages added to a project
whose shell has none**, for a namespace the shell never requires. Reproduced from clean twice. A
CLJS build has no business loading Datalevin or DuckDB; a minimal classpath removes it at source.

### 4.2 Replicant, and why not Reagent

Replicant has **zero dependencies** and is a pure function from data to hiccup: no component-local
state, no ratoms, no subscriptions.

That matters here more than it would elsewhere. **loci's design is that the substrate owns state**
— `undo!`, `?at=` time travel and the event log all work because there is exactly one source of
truth. Reagent's `r/atom` reactivity would create a second one, and every ratom would be state the
time slider cannot rewind. Replicant's data-driven handlers close the same loop loci already has:
a click returns an action *as data*, the action becomes an API call, the call commits an event, the
new state re-renders.

Measured: the whole compiled module is **188 K raw, 46 K gzipped**. The entire current shell is
79 K gzipped; React plus react-dom alone would be roughly 45 K.

**Reagent is the fallback and the risk is named.** It is what Clerk uses, it is better documented
and it has a far larger community; Replicant is younger. If Replicant runs out of road, most hiccup
ports across unchanged. The choice is made on the state model, not on ecosystem size, and if that
turns out to be the wrong trade the migration is small.

### 4.3 Transit

**It keeps a namespaced keyword whole.** Measured on the live route, same object:

```
JSON     {"total":26,"cols":["country",…],"next":"ezprIDJ9"}
Transit  ["^ ","~:table/total",26,"~:table/cols",["~:country",…],"~:page/next","ezprIDJ9"]
```

`clojure.data.json` renders a namespaced keyword key by its bare name. That is not a cosmetic
difference: it broke two tasks of the superseded plan, whose shell code read `m['table/total']` and
would have found `undefined` — and `resources/public/index.html:2038` has carried a comment saying
so since `reclaimedHTML` was written. Under Transit the class of bug stops existing rather than
being something to remember.

**It is also smaller.** Transit caches repeated map keys, so a 36-column page writes each column
name once instead of once per row. Measured on a 36-column × 100-row page:
**100,324 bytes of JSON → 58,728 of Transit, 41.5% smaller.**

`?wire=transit` selects it per request. Every existing caller keeps JSON; only the ClojureScript
shell asks for Transit, until nothing else is left.

### 4.4 The stylesheet stays, and no Tailwind

565 lines, IBM Plex, the muted palette with `--paper` and `--accent`. It is the product's identity
and it works. Replicant emits plain class names, so it applies unchanged. Clerk's renderer is
Tailwind throughout — adopting that would mean rewriting the visual design to gain nothing.

### 4.5 The grid is built, not taken

[Tabulator](https://tabulator.info) is MIT, loads from a plain script tag with no bundler, and ships
frozen columns, sticky headers, sorting, column show/hide and remote pagination. It was seriously
considered and is rejected for two reasons: its remote pagination is **page-number based**, which is
precisely the control this design does not have — deep `OFFSET` cost is unmeasured and §2.3 of the
superseded spec records the decision to offer next/previous only — and it would need re-theming to
match §4.4.

In hiccup the features are small. Sticky header is two CSS properties, a frozen first column three,
sorting is a click handler and a keyword in state with the *server* doing the sort, the column
chooser is a list of checkboxes, and paging is §3. The reason these were grinding work in the
superseded plan is string concatenation and re-attaching listeners by hand after every render —
which is what a diffing renderer removes.

---

## 5. What exists, what is needed, what gets built

| Piece | Where it comes from |
|---|---|
| Keyset/offset paging pushed into SQL | **built** — `TableStore/page`, tasks 1–2 |
| Opaque cursor, `cursor-int`, sort/column validation | **built** — task 1 |
| `/api/mold` window params | **built** — task 3 |
| One page per notebook `:ref` cell | **built** — task 4 |
| Elision shape, `:path`, `:unbounded?`, budget | **Clerk**, adapted (§3, §3.1) |
| `width: max-content` | **Clerk** |
| Transit wire | **spiked**, `?wire=transit` |
| shadow-cljs build, mount seam | **spiked**, 4 lines in `wireSourceButtons` |
| Sticky header, frozen column | **build** — CSS, ~5 properties |
| Column sorting | **build** — click handler; the sort travels in the window |
| Column chooser | **build** — checkbox list; hidden columns travel as `:cols` |
| Pager, page size 25/50/100/200 | **build** — §3.1 |
| Table error viewer | **build later** — Clerk has one, loci has nothing; not this spec |

**Nothing in Phase 1 is discarded.** It is the half Clerk lacks.

---

## 6. The seam

`renderMold`'s table case returns `<div data-cljs-grid="<id>">`; `wireSourceButtons` — which
already exists to attach behaviour after every render, and whose own comment records a bug where a
control was drawn on one surface and wired on the other — mounts the component. Measured: **four
lines**, and the other ~3,000 lines of the shell do not move.

The ClojureScript side fetches its own payload over Transit, so the vanilla side never needs to
know the payload's shape. That is what keeps the seam one line wide.

`?cljs` gates it during development. It becomes the default when the grid reaches parity, and the
flag is deleted then rather than kept as an option.

---

## 7. What this costs

- **A build step where there is none**, and a Node stage in a Dockerfile that has none. The image is
  445 MB today; a 188 KB artifact is not the concern — the build's *time* and its place in
  `build.clj` are.
- **The browser suite.** 159/159 pass with the spike wired, because it is opt-in. They will not all
  pass when the CLJS grid becomes the default: the tests that drive a table's DOM are written
  against `tableHTML`'s markup and will need rewriting against the new markup. That is a real cost
  and it is not small.
  *(Wrong, and left standing as the prediction it was — see the Status section. None were
  rewritten. The cost was real but of a different kind: ten assertions across two files were
  reading the panel before the grid's fetch filled it, and needed a wait, not new markup.)*
- **A second language in the repository** — though it is the same language as the substrate, which
  is the argument for it rather than against.

What is gained beyond the components: **the shell becomes REPL-testable**. Today every shell change
is verified by a Playwright run measured in minutes, in a project whose working agreement makes the
REPL the execution surface everywhere else.

---

## 8. Phasing

1. **The build.** `:cljs` alias with `:replace-deps`, `shadow-cljs.edn`, the static `/js` route, the
   `?cljs` flag, the mount seam. All spiked; this is committing it deliberately with tests.
2. **Transit.** `?wire=transit` on `/api/mold`, and the elision shape replacing `:page/next` in the
   payload. Server-side, with the JSON path unchanged.
3. **The grid.** Replicant: rows, `width: max-content`, sticky header, frozen first column, the
   pager with page size, and the elision as §3.1 defines it.
4. **Sorting and the column chooser**, both travelling in the window that already exists.
5. **Parity and the switch.** The browser tests move to the new markup, `?cljs` becomes the default,
   `tableHTML` is deleted along with the flag.

Each phase leaves the shell working, because until step 5 the old renderer is still the default.

---

## 9. Testing

- **The elision replaces and does not append.** Fetch page 2 and assert the page-1 rows are *gone*
  — the spike's own defect, turned into the test that would have caught it.
- **Prev is a stack pop**, exact after three forward pages and two back.
- **A namespaced keyword survives the wire.** Assert on `:table/total` as a keyword, in
  ClojureScript, over the real route. This is the bug class §4.3 exists to end.
- **Width.** A 36-column fixture must produce a table whose scroll width exceeds its client width,
  and a named column must render its full value — measured off the DOM, because asserting a
  stylesheet rule passes while the header scrolls away.
- **Sticky header and frozen column**, asserted by *position after scrolling*, not by the presence
  of a CSS rule.
- **The cursor is never read by the shell.** No `atob`, no `JSON.parse(cursor)`, no field access —
  it is what lets one control front two storage strategies.
- **The build has no npm side effect.** `package.json` unchanged after a clean release build; §4.1
  is why this is a test and not a comment.
- **Payload.** Transit against JSON on a realistic page, asserting an order of magnitude rather than
  the 41.5% measured here, which is one fixture's arithmetic.

---

## 10. Deliberately not in this spec

- **Porting the rest of the shell.** ~3,000 lines and 100 functions stay vanilla until they need
  work. The table earns its port because it is broken; nothing else does yet.
- **re-frame.** The substrate owns the state; the client atom holds a cached payload and view-local
  preferences. One atom and a dispatch function is ~30 lines, and re-frame is machinery for an app
  that owns its own state.
- **SCI in the browser.** Clerk evaluates viewer code client-side, which is how an agent-minted view
  could run without a round trip. Genuinely interesting for loci's `view:` objects and completely
  out of scope here.
- **editscript state patches.** Clerk diffs and sends patches. loci sends whole payloads and the
  payload is now bounded, so nobody has felt this.
- **Nested elisions inside a cell.** The shape reserves `:path` for it (§3) but nothing produces
  one: `normalize-rows` refuses a vector or a map in a column, so every cell is a scalar. The day
  that changes, the shape already has the field.
- **Aggregate views over the whole table.** §5 of the superseded spec, unchanged and still pending.
- **Column resize and drag-reorder.** Nobody has asked. If they are ever wanted together with
  virtualised scrolling, §4.5's rejection of a grid library should be revisited rather than defended.
