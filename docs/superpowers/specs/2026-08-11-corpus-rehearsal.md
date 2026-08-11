# The corpus, rehearsed on a copy

Date: 2026-08-11
Status: **rehearsal complete, entanglement gate PASSED.** Task 5 may run.

Task 4 of `docs/superpowers/plans/2026-08-11-a-corpus-worth-browsing.md`. No source file, test or
corpus file was touched; the run happened entirely at `/tmp/loci-corpus-rehearsal/data`, a copy of
`data/` made with `data.mdb` and `VERSION` and **not** `lock.mdb` — the decay plan's Task 6 found a
copied `lock.mdb` hangs Datalevin indefinitely with no error.

## The gate: do `cobalt` and `netherlands` cross?

**Yes, both.** Checked through `server/entity-payload` — the same function that answers
`GET /api/entity` and renders the entity object's page — against the rehearsed copy.

### `cobalt` — 14 live facts, promoted, 8 sources on one page

Art and minerals, on one page, in one rank order:

| rank | side | fact |
|---|---|---|
| 1 | art | *45 MET objects name cobalt in their medium or title, dated 1300 to 1850; China is their commonest culture (38).* |
| 2 | minerals | *In February, Congo (Kinshasa) temporarily banned cobalt exports to address market oversupply and low prices.* |
| 7 | minerals | *In 2025 Democratic Republic of Congo produced 230000 metric tons of cobalt, ahead of Indonesia at 44000.* |
| 8 | art | *Excluding smalt, which owes its colour to a cobalt silicate, there are at least three pigments which go under the name of ' cobalt ' or ' cobalt blue.'* |
| 13 | art | *The blue here indicates cobalt pigment. In the 1700s, the royal court banned the importation of cobalt blue…* |

Sources listed on the page span both notebooks: `space:corpus-1` *"Art objects: cobalt blue, white
lead, and the Dutch trade"* with `doc:corpus-1`/`-3`/`-4`, and `space:corpus-2` *"Critical minerals:
where cobalt, gallium and rare earths come from"* with `doc:corpus-9` and `tbl:corpus-2`. **8 of the
14 are art-side, 6 minerals-side.** All 14 are new: the pre-existing 80 carried nothing on cobalt,
exactly as the plan's table predicted (`existing corpus: —`).

### `netherlands` — 17 live facts, promoted, 13 sources on one page

**This is the crossing between the two corpora**, and it is one page:

| rank | side | fact |
|---|---|---|
| 1 | art (new) | *10 MET objects give Netherlands as their country, dated 1727 to 1739; Dutch is their commonest culture (10).* |
| 3 | health (new) | *Ban years for leaded petrol in road vehicles: Japan 1986, United States 1996, Netherlands 1998, China 2000.* |
| **4** | **semis (pre-existing)** | ***The US, Netherlands, and Japan coordinate a trilateral export control regime covering EUV, with the Netherlands controlling ASML and Japan controlling 87-91% of photoresist supply.*** |
| 5 | art (new) | *Nautilus shells were brought to the Netherlands from Indonesia. Skilled Dutch silversmiths mounted them in silver…* |
| 16 | art (new) | *During his tenure, the factory became the most prestigious of Delft potteries… Queen Mary II…* |

16 corpus facts and 1 pre-existing, and the pre-existing one ranks **4th of 17** — not buried. The
source list carries `find:dd-13-1` / `space:dd-13` *"EUV Lithography Monopoly"* beside
`doc:corpus-5`…`-8` (Kalf, de Hooch, the Delft pyramid, the nautilus cup). Dutch masters and ASML,
under one subject, is the thing the whole design exists for and it renders.

### The rest of the table

| subject | claimed | found |
|---|---|---|
| `cobalt` | art **and** minerals | **PASS** — 8 art / 6 minerals, 14 total |
| `netherlands` | art **and** ASML/semis | **PASS** — 16 art+health / 1 pre-existing EUV, 17 total |
| `china` | minerals/politics **and** existing semis | **PASS** — 28 facts: 23 new, **5 pre-existing** from `space:dd-13`, `space:dd-15`, `space:semis` |
| `gallium` | minerals/politics **and** existing semis | **PARTIAL** — 7 facts, all new; see below |
| `lead` | art **and** public health | **PASS** — 16 facts: MET objects and white-lead prose beside the petrol phase-out and the 2025 critical-minerals list |
| `lead white` | art | **PASS** — 3 facts, all from `doc:corpus-2` |
| `united states` | several | **PASS** — 37 facts across `corpus-2`/`-3`/`-4`/`-5` and **6 pre-existing** from `dd-13`, `dd-15`, `dd-16`, `dd-17`, `semis` |

**`gallium` does not join the existing semiconductor notebooks on its own page, and this is a
finding, not a defect to patch.** Measured on the pre-import memory: **no pre-existing fact carries
`gallium` as an entity, and none mentions the word at all** (`grep -c gallium` → 0 over all 179
records). Nor does any pre-existing fact carry `semiconductors` — so the `semiconductors` page is
also 100% corpus. Gallium reaches the ASML/EDA material only through the subjects the two corpora
*do* share: `china`, `united states` and `japan`. The plan's table read `gallium … existing corpus:
semiconductors`, and what that turned out to mean is a shared *topic*, not a shared *name*.

## The numbers

### Substrate — `import-corpus!`

|  | before | after | delta |
|---|---|---|---|
| commits in the log (`sub/history`) | 116 | 121 | **+5** — one `:tx` per notebook |
| objects | 94 | 118 | **+24** — 5 tables, 14 documents, 5 notebooks |
| notebooks | 38 | 43 | +5 |

Plan and act agreed exactly: `(= (:plan dry) (:plan live))` → **true**. Ids minted:
`space:corpus-1`…`-5`, `tbl:corpus-1`…`-5`, `doc:corpus-1`…`-14`. Rows committed: art 352,
minerals 214, lead 129, turnout 171, rates 445. Per-notebook `:tx` sizes 10 / 5 / 4 / 3 / 2 events.

### Memory — `import-facts!`

|  | before | after | delta |
|---|---|---|---|
| lines in `memory.edn` | 179 | 249 | **+70** |
| distinct ids | 80 | 150 | **+70** |
| live facts | 80 | 150 | +70 |
| facts carrying `:durable?` | 80 | 80 | **+0** — Task 5's classifier owns that |

By notebook: art 23, minerals 20, lead 11, elections 9, rates 7 = 70.

**No fact reinforced.** `:reinforced` was `[]` and `:remembered` was 70 — one new record per
declared fact, every one at `:strength 1`. The read-only cross-check Task 3 ran (0 pairs at ≥0.6
between the 70 and the live 80, none even at ≥0.5) held under the live run.

**The append-only prefix is byte-identical.** `head -179` of the post-run file md5s to
`06e5261e6db484ed97656a14f866174e` — the whole pre-run file. Compared record by record, **0 of the
existing 80 changed in any way**: no restamped `:ts`, no bumped `:strength`.

**No embedding was written**, and that is not a change: the pre-existing 80 carry no `:vec` either
(0 of 80 before, 0 of 150 after). Semantic recall is not part of this rehearsal.

## Two sequencing facts, proved rather than assumed

1. **`import-facts!` cannot be dry-run before `import-corpus!` has committed.** Against the pristine
   copy it refuses:

   > `nothing in the store was imported from art-objects.csv — run import-corpus! first, or these
   > facts have nothing to cite`

   `fact-sources` reads `:imported-from` out of the store, so the facts plan does not exist until
   the notebooks do. **Task 5's Step 3 must therefore interleave**: dry-run the substrate half,
   compare, run it, *then* dry-run the facts half, compare, run it. There is no point at which both
   plans can be inspected before anything is written.

2. **A second `import-corpus!` is a clean no-op.** Against the rehearsed copy the dry run returns
   `:already-done? true`, `:commits 0`, and `:already` naming all five notebooks by id. So an
   interrupted substrate half is safe to re-run. `import-facts!` has no such guard — a second run
   restamps `:ts` and bumps `:strength` on all 70 — which is why Task 5 runs it **once**.

## Safety

The real `data/` is unchanged. All four fingerprints identical before and after:

```
06e5261e6db484ed97656a14f866174e  data/memory.edn
e7a6eecdff7bb44ad6b959bb1a7fb7c6  data/substrate.edn
fc0c1e6b9edbfffd7764831ea93bb45f  data/substrate/data.mdb
47cd76e43f74bbc2e1baaf194d07e1fa  data/substrate/VERSION
```

`data/substrate/lock.mdb` was never copied and its mtime is untouched. `git status` is clean apart
from this file. The memory was opened as `(mem/file-memory "/tmp/loci-corpus-rehearsal/data/memory.edn")`
and `loci.memory/memory` was never dereferenced.

## Recommendation

**Task 5 should run.** The gate passes on both subjects that matter, the plan is deterministic
(dry and live plans compared equal on both halves), nothing reinforced, the existing 80 are
untouched, and the substrate half is re-runnable if interrupted. Carry forward:

- interleave the dry runs with the live runs, per the sequencing finding above
- the facts half runs **once**
- expect exactly +5 commits, +24 objects, +70 memory lines, and 70 facts arriving without `:durable?`
