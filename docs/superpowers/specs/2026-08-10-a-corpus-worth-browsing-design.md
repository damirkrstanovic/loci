# A corpus worth browsing: design

Date: 2026-08-10
Status: **shipped and imported after a rehearsal** in commits `760bbb1`, `a41cde0`,
`d5841a3`, and `249e467`.

## What this is for, and what it is not

> *"add a bunch of data from free online sources from various areas of human endeavour to loci so I can see how it behaves — arts, sciences, politics, finance"*

Asked what "behaves" meant, the user chose **texture over stress**: a corpus rich and varied
enough that loci's ideas get judged on real material rather than on a 35-object demo seed.

**So this is not a scale test, and the design would be wrong if it were.** It adds roughly 5 hub
notebooks with a few children, ~15 tables and documents, and ~70 facts — about double the current
corpus. Nothing here is sized to find the O(n) walls this codebase has already measured
(`recall` at 8,000 facts, `merge-similar!`'s O(n²), LEAP's per-keystroke scan). Those are a
different exercise with a different design.

The four decisions taken in brainstorming:

| | |
|---|---|
| purpose | texture — is loci good to *use* |
| shape | **few domains, deliberately entangled** — not a wide sweep |
| facts | **imported with real citations**, deterministic, no LLM |
| destination | the user's real `data/`, on top of existing work |

## Why entanglement is the whole design

A wide sweep — a dozen domains, one notebook each — maximises variety on the overview and teaches
almost nothing. Connectedness (`nb/links`) has nothing to find because no two notebooks share an
object. Entities have nothing to find because no subject appears twice. LEAP's ranking is never
tested because every query is unambiguous.

The entity work shipped this session is the part most in need of real material, and its central
question — measured, and still open — is what happens when **one subject spans unrelated
subjects**. `united states` in six facts across three unrelated notebooks is exactly the anchoring
case that made the recall union degrade 7 of 12 prompts before it was fixed.

So the corpus is built around subjects that genuinely recur, and the thread chosen is **materials**:

| subject | art | minerals | health | finance | politics | **existing corpus** |
|---|---|---|---|---|---|---|
| `cobalt` | cobalt blue pigment | DRC production | — | commodity price | DRC mining | — |
| `lead white` | dominant white, four centuries | — | blood lead levels | — | leaded petrol bans | — |
| `gallium` | — | export controls | — | — | export controls | **semiconductors** |
| `china` | porcelain | rare earths | — | — | export controls | **semiconductors** |
| `netherlands` | Dutch masters | — | — | — | — | **ASML** |
| `united states` | MET collection | — | lead phase-out | Fed rates | elections | **semis, world economies** |

`netherlands` reaching both Dutch Golden Age painting and ASML, and `cobalt` spanning a
fifteenth-century altarpiece and a battery supply chain, are the two an entity page exists for.
Neither is producible by a wide sweep.

Landing in the **existing** `data/` is what makes rows 3–6 work at all. In a fresh workspace the
corpus would have to be self-sufficient and half the point would be gone.

## The shape a table must have — measured, not assumed

This constrains the corpus more than the source choice does. `mold/viewers-for` selects by
predicate (`src/loci/content.clj:227-230`):

```clojure
(defn- numericish?  [x] (and (table? x) (seq (numeric-cols x))))
(defn- categorical? [x] (and (table? x) (cat-col x) (seq (numeric-cols x))))
(defn- timeseries?  [x] (and (table? x) (contains? (first x) :month)
                             (seq (remove #{:month} (numeric-cols x)))))
```

and `cat-col` (`:219`) takes the **coarsest** string column with **2–12 distinct values**, with
the reason in its own comment: *"grouping 211 countries by 7 regions says something, by 211 names
it doesn't"*.

Two consequences the importer must design around rather than discover:

1. **A raw `country` column never molds as Pivot or Bar.** Nor does MET's `culture` (hundreds of
   values) or `department` (~19). Every table must carry a **deliberately bucketed** coarse
   column — `era`, `region`, `commodity class` — or it molds as Rows and Summary and nothing else,
   and the moldability the corpus exists to demonstrate is invisible.
2. **`timeseries?` keys on a column literally named `:month`**, so *annual* data — which is most
   open data — never molds as Over time or Headline, however obviously temporal it is.

**(2) is a finding about loci, not about the corpus, and it is deliberately NOT fixed here.**
Widening `timeseries?` to accept `:year` is a change to a predicate five viewers hang off, it
would alter what every existing seeded table molds as, and it belongs to whoever decides that
after seeing annual data sit there un-charted. The corpus should include at least one genuinely
monthly table so the time viewers are exercised, and several annual ones so the gap is visible.

## Sources

Committed material, so licence matters more than convenience.

| source | licence | what it gives |
|---|---|---|
| MET Open Access | **CC0** | object title, artist, culture, object date, medium, department |
| USGS Mineral Commodity Summaries | **US public domain** | production by country and year |
| Our World in Data | **CC BY 4.0** | leaded-petrol phase-out, blood lead, energy series |
| FRED (public-domain series only) | varies — **check per series** | monthly policy rates, the one genuinely monthly table |

CC BY requires attribution, so a `corpus/MANIFEST.edn` records source URL, licence and retrieval
date per file, and each notebook's `:intent` names its source where a reader will actually see it.
**Anything whose licence cannot be established does not go in** — an unattributable file committed
to a repo is worse than a smaller corpus.

**Extracts, never whole dumps.** MET's open-access CSV is ~300 MB and USGS ships workbooks per
commodity; committing either whole would put more data in the repo than there is repo. The fetch
step selects and **reduces** — a few hundred rows per table, with the bucketed coarse column
computed at fetch time so the committed file is already in the shape `cat-col` needs. The manifest
records the reduction (source row count → committed row count, and the filter applied) so a reader
can tell an extract from a sample and reproduce it.

`corpus/` must also be added to **`.dockerignore`**: the image needs the jar, not the source
material, and the build context is a security boundary this repo already treats as one.

## Architecture

A new `loci.corpus`, built exactly like `loci.repair`, for the same reason: *a corpus you can
read, test, dry-run and review is a different object from a script someone ran once.*

- **`corpus/`** — raw material fetched **once**, plus `MANIFEST.edn`. Committed.
- **`src/loci/corpus.clj`** — turns that material into events. **Dry-run by default. Nothing runs
  on require. Idempotent.** Offline: no network at import time, so same input → same events.
- **One `:tx` per notebook**, not one giant commit. Each notebook is independently undoable, and
  the time scrubber gets a history with shape instead of a single step.

**Events are built directly rather than through `import-csv!`.** That route mints
`tbl:csv-<count>-<rand>` — one of the three count-plus-random minters left open on 2026-08-09 —
and a random suffix makes the import non-deterministic for no gain.

**`content.clj` is not touched.** The browser suite boots on its deterministic seed and 100 tests
assert against it; changing the seed to add a corpus would be a hundred-test rewrite in service of
a demo.

## Facts, and the two traps

Facts are appended with `:entities` and a `:source` naming the document or table they came from —
the same shape `distill!` writes. Provenance is arguably better than an LLM distillation, because
every fact traces to a named source rather than to a model's summary. That it bypasses `distill!`
is the honest caveat, and it means this corpus does **not** test whether the distiller now names
its own subject (the Task 11 change).

**Trap 1 — `remember` reinforces at jaccard ≥ 0.6.** Two facts phrased similarly collapse into one
with `:strength` incremented, which is right for a re-derived fact and wrong for a bulk import.
`bulk-entity-memory` in `server_test.clj` records exactly this. Imported facts must be textually
distinct, and the importer should **report** any fact that reinforced rather than inserted, so a
silent collapse is visible.

**Trap 2 — `remember` stamps `:ts` with `(System/currentTimeMillis)`**, and `decay` is a
multiplier in `weight`. Every imported fact would be stamped today while the existing 80 are ~2
weeks old: `1/(1 + 14/30) = 0.68` against `1.0`, so the new corpus gets a **~47% ranking advantage
over the user's real work** in every recall. That is a real distortion of the thing being judged.

> **RESOLVED, 2026-08-10, by a separate design — and this spec now DEPENDS on it.**
> See `2026-08-10-facts-that-do-not-decay-design.md`. The user's answer to the `:ts` problem was
> that the premise was wrong: *"there are some real world facts that bear no decay — only some
> findings that agent discovered during execution should undergo decay."*
>
> Facts gain a `:durable?` flag, `weight` skips `decay` when it is set, the distiller proposes it,
> and **the existing 80 facts are classified in the same pass** so both halves of the memory are
> judged by one rule rather than by their arrival date.
>
> **That spec must ship first.** Importing durable facts into a memory whose existing facts are
> still decaying would turn the ~47% advantage described above from temporary into *permanent* —
> strictly worse than the problem it was meant to solve.

## Safety

`data/` is the user's real substrate and this is the second thing this month to write to it.

- Backup first; `md5sum` before and after.
- **Dry run prints the whole plan** — notebooks, tables, docs, facts — before anything writes.
- **Idempotent**: a notebook id that already exists is skipped, not duplicated.
- Append-only. Never rewrite a line of `memory.edn`.
- Reversal: `undo!` per notebook while it is still the last commit; otherwise the backup. **The
  memory half has no undo** — the same honest limit the repair carries.
- No server may be running: `FileMemory` holds `!facts` in RAM and a concurrent `remember` would
  append from a stale atom.

## A risk that is really a test

Real CSV headers are messy, and `Key Proponent(s)` is precisely how July's data loss began — an
unreadable keyword that truncated the log at 49 of 80 events. `col-kw` and `safe-event` now
normalize on the way in, and **this corpus is the first real test of that fix against headers
nobody sanitised.** If it breaks, that is a finding worth more than the corpus.

## What it should let the user judge

Stated as things to look at, because "is it good to use" cannot be asserted by a test:

- Open `cobalt` as a subject and see a pigment fact and a supply-chain fact on one page.
- Type `netherlands` into LEAP and get Dutch masters *and* ASML material, under headings that say which is which.
- Mold a MET table as Cards, then Pivot, then Bar — and notice which tables offer nothing but Rows.
- The overview at ~50 notebooks.
- Whether the memory pane is worth reading at 150 facts.

## Non-goals

- **No LLM calls.** Deterministic import only.
- **No scale testing.** See the opening section.
- **No new features**, and specifically not widening `timeseries?`.
- **`content.clj` untouched.**
- **No fact is invented.** Every fact traces to a cited source, or it is not imported.
