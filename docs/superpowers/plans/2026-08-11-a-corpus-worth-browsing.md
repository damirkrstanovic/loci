# A corpus worth browsing — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax. Reviewers **measure**; implementers **prove their tests
> bite**.

**Goal:** enough real, varied, *entangled* material in loci that its ideas can be judged on
something other than a 35-object demo seed.

**Spec:** `docs/superpowers/specs/2026-08-10-a-corpus-worth-browsing-design.md`. **Read it first.**

**Its dependency is now satisfied.** `2026-08-10-facts-that-do-not-decay-design.md` shipped and
ran: facts carry `:durable?`, and the existing 80 were classified (23 durable). So imported facts
are judged by the same rule as everything else instead of winning on arrival date.

**Floors:** `clojure -M:test` **510 tests / 2298 assertions**; `npm run test:browser` **104**.
`data/memory.edn` is `06e5261e6db484ed97656a14f866174e`; `data/substrate.edn` is
`e7a6eecdff7bb44ad6b959bb1a7fb7c6`.

---

## This is texture, not scale — and the design is wrong if it drifts

~5 hub notebooks with a few children, ~15 tables and documents, ~70 facts. **About double the
current corpus.** Nothing here is sized to find the O(n) walls already measured (`recall` at 8,000
facts, `merge-similar!`'s O(n²), LEAP's per-keystroke scan). If a task finds itself generating
rows to make a number bigger, it has misread the spec.

## Two shape constraints, measured — design around them, do not discover them

From `src/loci/content.clj`:

```clojure
(defn- categorical? [x] (and (table? x) (cat-col x) (seq (numeric-cols x))))
(defn- timeseries?  [x] (and (table? x) (contains? (first x) :month)
                             (seq (remove #{:month} (numeric-cols x)))))
```

and `cat-col` takes the **coarsest** string column with **2–12 distinct values** — *"grouping 211
countries by 7 regions says something, by 211 names it doesn't"*.

1. **A raw `country` column never molds as Pivot or Bar.** Nor does MET's `culture` (hundreds) or
   `department` (~19). **Every table must carry a deliberately bucketed coarse column** —
   `era`, `region`, `commodity` — computed **at fetch time**, so the committed file is already in
   the shape `cat-col` needs.
2. **`timeseries?` keys on a column literally named `:month`.** Annual data — most open data —
   never molds as Over time or Headline. **Include exactly one genuinely monthly table** so those
   viewers are exercised, and leave the annual ones annual **so the gap is visible.** Widening
   `timeseries?` is out of scope; it is a predicate five viewers hang off and it would change what
   every seeded table molds as.

## The entanglement is the deliverable

| subject | art | minerals | health | markets | politics | **existing corpus** |
|---|---|---|---|---|---|---|
| `cobalt` | cobalt blue pigment | DRC production | — | commodity price | DRC mining | — |
| `lead white` | dominant white, four centuries | — | blood lead | — | petrol bans | — |
| `gallium` | — | export controls | — | — | export controls | **semiconductors** |
| `china` | porcelain | rare earths | — | — | export controls | **semiconductors** |
| `netherlands` | Dutch masters | — | — | — | — | **ASML** |
| `united states` | MET collection | — | lead phase-out | Fed rates | elections | **semis, world economies** |

A corpus that does not produce those crossings has failed, however much material it contains.
**Task 6 checks them by name.**

---

### Task 1: Fetch and reduce — `corpus/`

**Files:** create `corpus/*.csv`, `corpus/MANIFEST.edn`; modify `.dockerignore`

The **only** task that touches the network. Everything after it is offline and deterministic.

**Licence bar: CC0, US public domain, or CC BY.** Anything whose licence cannot be established
does not go in — an unattributable file committed to a repo is worse than a smaller corpus. CC
BY-SA is copyleft and is **not** acceptable for committed prose.

**Extracts, never dumps.** MET's open-access CSV is ~300 MB. Select and reduce: a few hundred rows
per table, the coarse bucket computed here.

Shapes needed (find sources that fit; do not pin a URL this plan cannot verify):

| table | coarse column (2–12 values) | numeric | notes |
|---|---|---|---|
| art objects | `era` from object date | count or year | MET Open Access, **CC0** |
| mineral production | `commodity` | tonnes, year | USGS, **US public domain** |
| lead phase-out | `region` | year, value | Our World in Data, **CC BY** |
| **policy rates** | `country` (≤12, hand-picked) | rate | **must have a `:month` column** |
| elections / institutions | `region` or `era` | turnout % | OWID or equivalent, **CC BY** |

`MANIFEST.edn` records per file: source URL, licence, retrieval date, **source row count →
committed row count, and the filter applied** — so a reader can tell an extract from a sample and
reproduce it.

- [ ] **Step 1:** Fetch, reduce, bucket, write `MANIFEST.edn`. Report the licence you established
      for each and how.
- [ ] **Step 2: Verify each table molds the way it is supposed to** — load it and call
      `mold/viewers-for`, asserting the expected viewer ids appear. A table that does not offer
      Pivot has the wrong coarse column and must be re-bucketed **now**, not discovered in Task 6.
- [ ] **Step 3:** Add `corpus/` to `.dockerignore` — the image wants the jar, not the source
      material, and the build context is a boundary this repo already treats as one.
- [ ] **Step 4: Commit** — `corpus: raw material, reduced, with its licences`

---

### Task 2: `loci.corpus` — the substrate

**Files:** create `src/loci/corpus.clj`, `test/loci/corpus_test.clj`

Built exactly like `loci.repair` and `loci.classify`: **dry-run by default, idempotent,
append-only in spirit, nothing runs on require, every refusal a returned report.**

- Reads `corpus/` at **runtime from the classpath or a path argument** — no network.
- **One `:tx` per notebook**, so each is independently undoable and the scrubber gets a history
  with shape.
- **Builds events directly, not through `import-csv!`** — that route mints
  `tbl:csv-<count>-<rand>`, one of the three count-plus-random minters left open on 2026-08-09,
  and a random suffix makes the import non-deterministic for no gain.
- Mints through `next-id`/`fresh-dd-id` — the rules the server already uses.
- **Idempotent:** a notebook whose title and source file already exist is skipped, not duplicated.

- [ ] **Step 1: Write the failing tests** against a `sub/fresh-store`.
  - a dry run **writes nothing** and returns a plan naming every notebook, table and document.
  - one `:tx` per notebook — assert the history grows by the number of notebooks, not by 1 and not
    by the number of objects.
  - `sub/undo!` reverses the **last notebook only**, leaving the others.
  - a second run is a no-op.
  - the tables land with their coarse column intact and mold as Task 1 asserted — **this is the
    assertion that connects the two tasks**, and without it Task 1's bucketing can rot silently.
  - a notebook's `:intent` names its source and licence, since that is where a reader sees it.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green** (`clojure -M:test`).
- [ ] **Step 5: Prove it bites.** One giant `:tx`; make the dry run write; drop the idempotence
      check; strip the coarse column.
- [ ] **Step 6: Commit** — `feat: a corpus becomes notebooks, tables and documents`

---

### Task 3: `loci.corpus` — the facts

**Files:** modify `src/loci/corpus.clj`, `test/loci/corpus_test.clj`

Facts are written through `mold/remember` with `:entities` and a `:source` naming the document or
table they came from — the shape `distill!` writes.

**Write them WITHOUT `:durable?`.** Task 5 runs `loci.classify` over them afterwards, so imported
facts are judged by the same rule as the existing 80 rather than by a rule of their own. That is
what the decay spec resolved and it is the whole reason this plan comes second.

**The trap:** `remember` reinforces at jaccard ≥ 0.6 over tokens, collapsing two similar facts into
one with `:strength` bumped. Right for a re-derived fact, wrong for an import.
`bulk-entity-memory` in `server_test.clj` documents it. **Imported facts must be textually
distinct, and the importer must REPORT any fact that reinforced rather than inserted** — a silent
collapse is a fact you thought you added and did not.

**No fact is invented.** Every one traces to a cited source, or it is not imported.

- [ ] **Step 1: Write the failing tests.**
  - a dry run remembers nothing.
  - each fact lands with its `:entities` and a `:source` that resolves to a real object.
  - **no fact carries `:durable?`** — Task 5's classifier owns that.
  - a fact that reinforces an existing one is **reported**, not silent — seed a fixture with a
    near-duplicate and assert the report names it.
  - a second run reinforces rather than duplicating, and says so.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green.**
- [ ] **Step 5: Prove it bites.** Drop the reinforcement report; write `:durable?` at import;
      make the dry run remember.
- [ ] **Step 6: Commit** — `feat: the corpus brings facts, each with somewhere it came from`

---

### Task 4: Rehearse on a copy

**Files:** none. This task produces numbers.

- [ ] **Step 1:** `cp -r data /tmp/loci-corpus-rehearsal/data`. **Copy `data.mdb` and `VERSION`,
      NOT `lock.mdb`** — Task 6 of the decay plan found that a copied `lock.mdb` hangs Datalevin
      **indefinitely, with no error**. Record both md5s of the real `data/`.
- [ ] **Step 2:** Dry-run both halves against the copy and paste the plan.
- [ ] **Step 3:** Run both against the copy. Report: events before/after, objects before/after,
      memory lines before/after, facts before/after, **and any fact that reinforced**.
- [ ] **Step 4: Check the entanglement, on the copy.** For each row of the table above, does the
      subject now appear in the domains claimed? `cobalt` and `netherlands` are the two that
      matter most — **if they do not cross, the corpus has failed its purpose** and Task 5 should
      not run. Report rather than patching the data to make it true.
- [ ] **Step 5:** Confirm the real `data/` md5s are unchanged by the rehearsal.
- [ ] **Step 6: Commit** the rehearsal numbers as a short doc under `docs/superpowers/specs/`.

---

### Task 5: Run it against `data/`, once, then classify

**Files:** none. This changes the user's substrate and memory.

**Gated on Task 4.** If the entanglement check failed, this does not start.

- [ ] **Step 1:** Confirm no server holds the store. `java -jar /app/loci.jar` runs in a container
      on the Docker volume `loci_loci-data` — **confirm the volume with `docker inspect`**, do not
      dismiss the process.
- [ ] **Step 2:** Back up: `cp data/memory.edn data/memory.edn.bak-<date>` and
      `cp -r data/substrate data/substrate.bak-<date>`. Record both md5s.
- [ ] **Step 3–4: INTERLEAVE — dry substrate → compare → run substrate → dry facts → compare →
      run facts.**

      > **Corrected 2026-08-11, after Task 4 rehearsed it.** This step first said "dry-run against
      > the real `data/` and compare with Task 4's, then run both halves". **That is not possible:**
      > `import-facts!` refuses before `import-corpus!` has committed — *"nothing in the store was
      > imported from art-objects.csv"* — because a fact's `:source` must resolve to a real object.
      > There is no point at which both plans exist before anything is written.
      >
      > Compare the **id sets strictly** at each stage; nothing here is model-generated, so each
      > plan must match Task 4's exactly. A second `import-corpus!` is a clean no-op
      > (`:already-done? true`), so an interrupted substrate half is safe to re-run — **the facts
      > half is not**, since a second run restamps `:ts` and bumps `:strength` on all 70.
- [ ] **Step 5: Classify the new facts** — `loci.classify` over the real memory. Dry-run first and
      print the judgements. The existing 80 already carry `:durable-by "agent"` and are skipped, so
      this judges only what the corpus added.
- [ ] **Step 6: Verify.** Events and objects grew by the planned amounts; memory's append-only
      prefix byte-identical to the backup; `:ts`/`:strength` unchanged on the **existing** 80; both
      suites green.
- [ ] **Step 7: Commit** — `data: a corpus worth browsing` (`data/` is gitignored, so the commit
      is the record and Step 6's numbers are its body)

---

### Task 6: Is it any good to use?

**Files:** create `docs/superpowers/specs/<date>-the-corpus-in-use.md`

**This is what the user asked for.** Everything above is machinery.

Boot the app and **use it**, with a real browser. Report in a reader's terms, not a selector's:

- [ ] Open **`cobalt`** as a subject. Does one page show a pigment fact and a supply-chain fact?
- [ ] Type **`netherlands`** into LEAP. Do Dutch masters *and* ASML material come back, under
      headings that say which is which?
- [ ] Mold the art table as **Cards**, then **Pivot**, then **Bar**. Then find a table that offers
      **nothing but Rows** and say which, and why — the `:month` gap should be visible here.
- [ ] The overview at ~50 notebooks. Is it navigable, or is it a wall?
- [ ] The memory pane at ~150 facts. Is it worth reading?
- [ ] The time scrubber over a log that now has real shape.
- [ ] **Anything that looks wrong to a person even if no test fails.** Every prior review on this
      codebase was about correctness; the shell sweep that finally asked "is this any good to use"
      found a Critical nobody's tests could see.

Screenshots where a sentence will not do.

---

## Before calling this done

- [ ] `clojure -M:test` green (floor 510 / 2298), `npm run test:browser` green (floor 104)
- [ ] `data/substrate.edn` still `e7a6eecdff7bb44ad6b959bb1a7fb7c6` — it is the frozen
      pre-Datalevin rollback and nothing in this plan should touch it
- [ ] Every licence in `MANIFEST.edn` established, not assumed

## Findings about loci, surfaced by Task 1 — recorded, deliberately NOT fixed

Real data found three things the seeded corpus never could. None is fixed here; each is a
predicate that several viewers hang off, and changing it would alter what every existing table
molds as. **Task 6 should be able to see all three.**

1. **`cat-col` takes the column with the SMALLEST distinct count in 2–12, so a boolean flag always
   beats a real taxonomy.** MET's `Department` (10 distinct) and USGS's `Is critical mineral 2025`
   (**2** distinct) would each have silently become the Pivot axis in preference to `era` and
   `commodity`. "Coarsest wins" is implemented as "fewest wins", and for a real dataset those are
   not the same thing. Task 1 guards against it *in the extract*; loci is unchanged.
2. **`measure-col` punishes honest column names.** `Voter turnout of registered voters` is neither
   name-matched as a rate nor year-like, so **Bar would have summed percentages**. Task 1 renamed
   it to `turnout_pct` in the extract. A user importing their own CSV gets no such rename.
3. **`line` has no series column for `country`.** It looks for a series among
   `#{:tenure-band :dept :channel :region :stage}`, so the monthly table's ten economies will draw
   as **one interleaved line**. This is the one most likely to look obviously wrong on screen, and
   it is the reason a monthly table was included at all.

4. **Entity linkage is by exact entity string, not by topic** — found by Task 4, and it is the
   sharpest constraint on what "entanglement" can mean.

   `netherlands` crosses because both corpora happened to write the country's name. **`gallium`
   does not, because neither side ever wrote the word on the other** — the pre-existing 80 name
   TSMC, ASML, Taiwan, Synopsys and Cadence but never `gallium`, and never `semiconductors`
   either, so the corpus built a `semiconductors` page nothing pre-existing joins.

   Nothing here is broken: `ent/index` is a projection over the strings the distiller wrote, and
   the design says so. But it means **a subject crosses domains only when both sides spell it**,
   which is a much narrower promise than "these topics are related". It is also the clearest
   argument yet for the **aliases** work the entities spec deferred (§1) — and the second measured
   cost of deferring it, after `taiwan strait` ≠ `taiwan`.

5. **A prose extract's `:entangles` become every fact's `:entities`**, so five voter-turnout
   *methodology* facts land on the `netherlands` page at ranks 6–10 — correctly sourced, saying
   nothing about the Netherlands, outranking the Delft and Kalf material a reader came for.
   Task 6 should look at this: it is a question about whether file-level subject tagging is the
   right granularity, not a bug in anything.

## Out of scope

- **Scale.** See the opening section.
- **Widening `timeseries?`** to accept `:year`. The gap is a finding to *show*, not to fix here.
- **`content.clj`.** The browser suite boots on its seed; 104 tests assert against it.
- **The three count-plus-random minters.** Still open, still a decision, still not this.
