# Facts that do not decay — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax. Reviewers **measure**; implementers **prove their tests
> bite** — break the fix, watch the test fail, restore.

**Goal:** a fact whose claim is timeless stops being ranked into oblivion by age, while a fact
that carries an implicit "as of now" keeps decaying exactly as it does today.

**Spec:** `docs/superpowers/specs/2026-08-10-facts-that-do-not-decay-design.md`. **Read it first.**

**Architecture:** `weight` is the only place `decay` is consulted, so the ranking change is one
`if`. Facts gain `:durable?` and `:durable-by`; the distiller proposes, a one-time `loci.classify`
pass judges the existing 80, and the memory pane lets you override. Absent means decays, so
nothing changes rank until something classifies it.

**Tech stack:** Clojure (`src/loci/`), `clojure -M:test`; the shell is one file,
`resources/public/index.html`, tested by `npm run test:browser`. **Both suites must pass.**

**Floors:** `clojure -M:test` **478 tests / 2108 assertions**; `npm run test:browser` **100**.
`data/memory.edn` is `3b219034921e2f1bfe438837a2244e95`.

---

## Why this is riskier than it looks

It changes ranking for **every fact in the corpus**. That is the same class of change as the
2026-08-08 recall union, which degraded **7 of 12 real prompts** and was caught only because
Task 12 measured it on a copy. Reasoning about it is exactly what failed then.

So Task 6 is not optional and not ceremony, and the plan is ordered so nothing touches
`data/` until the ranking change and the classifier have both been measured.

## File structure

| file | responsibility |
|---|---|
| modify `src/loci/memory.clj` | `weight` consults `:durable?`; `remember` carries the flag on both branches |
| modify `test/loci/memory_recall_test.clj` | the curve, pinned; the reinforcement rules, both directions |
| modify `src/loci/agent.clj` | `distill-facts` asks for one more field |
| modify `src/loci/server.clj` | `distill!` passes it; `POST /api/memory-durable` |
| modify `resources/public/index.html` | the pane shows and toggles it |
| **create** `src/loci/classify.clj` | the one-time pass: dry-run by default, append-only |
| **create** `test/loci/classify_test.clj` | the pass, against synthetic memories |
| **create** `docs/superpowers/specs/2026-08-11-decay-measured.md` | Task 6's before/after table |

---

### Task 1: `weight` stops decaying a durable fact

**Files:** modify `src/loci/memory.clj` (`weight`, `:119`); test `test/loci/memory_recall_test.clj`

```clojure
(defn- weight [now f]
  (* (if (:durable? f) 1.0 (decay (:ts f) now))
     (+ 1.0 (* 0.25 (dec (:strength f 1))))))
```

The strength bonus is untouched and still applies to durable facts — "it keeps coming up" is
orthogonal to "it ages", and conflating them here would be a second change hiding inside this one.

- [ ] **Step 1: Write the failing tests.**
  - a durable fact returns the **same** weight at 0, 365 and 3,650 days.
  - a non-durable fact reproduces today's curve, asserted with **literal numbers** —
    `1.0`, `0.5` at 30 days, `0.076…` at 365 — so a refactor that changes decay for ordinary
    facts fails here rather than silently.
  - `:durable? false` decays. **Absent** decays. Both, separately: a `(if (:durable? f) …)` and a
    `(if (contains? f :durable?) …)` behave identically for `true` and differently for `false`,
    and only one of them is right.
  - the strength bonus still multiplies a durable fact (strength 3 → 1.5).
- [ ] **Step 2: Run and watch them fail.** Run: `clojure -M:test -n loci.memory-recall-test`
- [ ] **Step 3: Implement. Step 4: green** (`clojure -M:test` in full — `weight` is on every path).
- [ ] **Step 5: Prove it bites.** Return `1.0` unconditionally (the curve test fails); return
      `(decay …)` unconditionally (the durable test fails); key on `contains?` instead of the value
      (the `:durable? false` test fails, and **only** that one).
- [ ] **Step 6: Commit** — `feat: a fact whose claim is timeless stops ageing out of recall`

---

### Task 2: `remember` carries the flag, and never reverts your judgement

**Files:** modify `src/loci/memory.clj` (`remember`, `:1043`); test `test/loci/memory_recall_test.clj`

**Both branches need it**, and they differ.

**New fact** — carry `:durable?`/`:durable-by` from `opts`, and **omit both keys entirely when
`opts` supplies neither.** Do not write `:durable? nil`: a key that is always present and usually
nil is what makes `(:durable? f)` and `(contains? f :durable?)` disagree, which is exactly why
`memory-payload`'s `:degraded` is absent-when-healthy rather than present-and-nil. Absent must keep
meaning *"nothing has judged this yet"*.

**Reinforcement** — an agent judgement may be overwritten; **yours may not.**

```clojure
;; ONE MORE CLAUSE in the existing cond->, NOT a wrapper around it
(cond-> dup
  ;; … the existing :fact / :ts / :source / :strength / :entities clauses …
  (and (contains? opts :durable?)
       (not= "you" (:durable-by dup)))
  (assoc :durable? (:durable? opts) :durable-by "agent"))
```

> **Corrected 2026-08-11, after Task 2 implemented it.** This plan first wrote the guard as
> `(if (= "you" (:durable-by dup)) dup …)`. Read literally that returns `dup` **whole** — skipping
> the `:strength` bump, the `:fact` rewrite and the `:entities` union — so a fact you had marked
> durable would be frozen at strength 1 forever, with text that never updated. **Only the
> judgement stands; the rest of reinforcement must still happen.** Task 2 pinned it with a
> sabotage that restores the literal form and watches `(= 2 (:strength f))` fail.
> **Task 5: do not "correct" the shipped code back to this plan's first draft.**

Without this, the agent re-deriving a fact next week silently reverts a judgement you made by
hand — the failure `:no-merge` exists to prevent on the merge path, reintroduced on a new one.

- [ ] **Step 1: Write the failing tests.**
  - a new fact with no `:durable?` in opts has **neither key** (assert `contains?`, not the value).
  - a new fact with `:durable? true` stores it with `:durable-by "agent"`.
  - reinforcing a fact whose `:durable-by` is `"agent"` **takes** the new judgement.
  - reinforcing a fact whose `:durable-by` is `"you"` **keeps** yours — including when the incoming
    opts say the opposite. This is the assertion the field exists for.
  - reinforcing with no `:durable?` in opts leaves an existing flag alone, either provenance.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green** (full suite).
- [ ] **Step 5: Prove it bites.** Drop the `"you"` guard (the override test fails); write
      `:durable? nil` on the new-fact branch (the `contains?` test fails); take `opts` unconditionally
      on reinforcement (the leaves-it-alone test fails).
- [ ] **Step 6: Commit** — `feat: the agent may change its mind about a fact, never yours`

---

### Task 3: The distiller proposes

**Files:** modify `src/loci/agent.clj` (`distill-facts`, `:227`), `src/loci/server.clj`
(`distill!`, `:1695`); test `test/loci/agent_test.clj`

One more JSON field per fact — `"durable": true|false` — beside `"fact"` and `"entities"`. No
extra call. **The prompt must teach the axis by example**, because "durable" is no more
self-explanatory to a model than it was to us:

> `T² ∝ a³` is durable. *"Capacity is booked through 2027"* is not. A law, a definition or a
> historical record does not go out of date; a measurement of a world that moves does.

`distill!` destructures `{:keys [fact entities]}` today and must pass `:durable` through to
`remember`'s opts as `:durable?`.

- [ ] **Step 1: Write the failing tests**, extending `agent_test.clj` (Task 11's file — it already
      stubs `agent/chat`, which is a plain `defn`, so `with-redefs` works here; it does **not** work
      on protocol methods and that trap has cost this codebase a round already).
  - the system prompt requires the field, asserted on the requirement rather than the literal
    sentence (two substring checks, as Task 11 established, so a reword that keeps both halves
    still passes).
  - a canned JSON body carrying `"durable": true` parses to `:durable true` on the fact.
  - **a body with no `"durable"` field still parses** — an older model, or a refusal to answer,
    must not break distillation. The fact arrives unjudged, which is the honest default.
  - `distill!` passes it to `remember` — capture the opts with a `reify` of `mold/Recall`.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green.**
- [ ] **Step 5: Prove it bites.** Remove the instruction; drop the pass-through in `distill!`.
- [ ] **Step 6: Commit** — `feat: the distiller says whether a fact will go out of date`

---

### Task 4: `loci.classify` — judge the existing facts, dry-run by default

**Files:** create `src/loci/classify.clj`, `test/loci/classify_test.clj`

Built like `loci.repair`: **dry-run by default, idempotent, append-only, nothing runs on require,
every refusal a returned report rather than a throw.**

It reads the live facts, asks the classifier in batches, and appends each fact again with
`:durable?` and `:durable-by "agent"` — the same last-wins append `store-vector!` and `unmerge!`
use.

**It must NOT go through `mold/remember`.** `remember` would find the fact as its own jaccard ≥ 0.6
duplicate, bump `:strength`, and **reset `:ts` to now** — handing every not-durable fact a fresh
1.0 multiplier, which is the opposite of this plan. The 2026-08-09 repair documented this trap;
do not rediscover it.

**Read the record at write time**, not from the snapshot the batch started with — a re-embed sweep
or a `remember` landing mid-pass would otherwise be clobbered. `loci.repair` does this; copy it.

- [ ] **Step 1: Write the failing tests** against a fresh `mem/file-memory` under
      `java.io.tmpdir`, with the classifier stubbed (`with-redefs` on `agent/chat` — a plain var).
  - a dry run appends **nothing** and returns a plan naming every fact and its judgement.
  - a live run appends exactly one line per fact, with `:durable-by "agent"`.
  - **`:ts` and `:strength` are unchanged** on every fact — assert directly; this is the
    `remember` trap, and it is the assertion most worth having.
  - a second run is a **no-op** — already-classified facts are not re-appended.
  - a fact carrying `:durable-by "you"` is **skipped**, not re-judged.
  - a fact whose text moved between plan and write is **declined**, and the report says so.
  - the classifier answering with an unparseable body **refuses the whole pass** rather than
    writing a partial one.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green.**
- [ ] **Step 5: Prove it bites.** Route it through `mold/remember` (the `:ts` assertion fails);
      snapshot at plan time (the moved-text assertion fails); drop the `"you"` skip; make the dry
      run write.
- [ ] **Step 6: Commit** — `feat: a one-time pass judges what the memory already holds`

---

### Task 5: The pane shows it, and lets you say otherwise

**Files:** modify `src/loci/server.clj` (a route beside `memory-unmerge!` at `:2419`/`:2523`),
`resources/public/index.html` (`openMemory`, `:2091`; `API` at `:413`);
test `test/browser/memorypane.test.mjs`

`POST /api/memory-durable {id, durable}` → sets `:durable?` with `:durable-by "you"`, appends,
answers with the updated record. Mirror `memory-unmerge!`'s shape: a 200 with `{error}` on refusal,
the record and **not** a whole pane payload on success, and no `:vec` on the way out.

**It appends directly — not through `mold/remember`.** Same trap as Task 4: `remember` would find
the fact as its own duplicate, bump `:strength` and reset `:ts`, so marking a fact *not* durable
would hand it a fresh 1.0 multiplier in the same breath. `unmerge!` is the shape to copy — it
takes the atom and the file, swaps, and appends the record it landed on.

`memory-payload` already ships every fact key but `:vec`, so **no payload change is needed** —
verify that rather than assuming it.

**The pane has to stop lying.** It shows age — *"today"*, *"365d ago"* — and that number is about
to lose the meaning it carries. A reader seeing *"365d ago"* on a durable fact would reasonably
assume it is buried when it is not. Render the distinction in **text**, not only a class: a durable
fact should say so where the age is, e.g. `365d ago · does not age`.

- [ ] **Step 1: Write the failing tests.**
  - **Clojure:** the route sets the flag with `:durable-by "you"`; toggling back sets `false` and
    keeps `"you"`; an unknown id is a 200 `{error}`, not a 500; `:ts`/`:strength` unchanged.
  - **Browser** (stub `/api/memory` with `page.route` — note `/api/memory` is a **prefix** of
    `/api/memory-durable` and of `/api/memory-unmerge`, so the route regex shape matters; copy
    `memorypane.test.mjs`'s `MEMORY_URL`): a durable fact renders the "does not age" text and a
    decaying one does not; clicking the control POSTs the right id and value; the pane refetches.
  - Use the `until()`/`untilSel()` helper — a sabotage that fails by a 30-second `waitForSelector`
    timeout means the assertion naming the defect never ran, and that has happened three times on
    this codebase.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green**, both suites.
- [ ] **Step 5: Prove it bites.** Drop `:durable-by "you"` from the route (a later reinforcement
      would then revert it — assert that end-to-end, it is the whole point of Task 2); render the
      marker unconditionally; drop the refetch.
- [ ] **Step 6: Commit** — `feat: the memory pane says which facts age, and lets you disagree`

---

### Task 6: Measured — what this does to what the agent is told

**Files:** create `docs/superpowers/specs/2026-08-11-decay-measured.md`. **No source changes.**

**This is the task the plan exists to reach.** A global ranking change is what degraded 7 of 12
prompts on 2026-08-08.

**Read `docs/superpowers/specs/2026-08-08-entities-recall-measured.md`'s Method section and reuse
it exactly** — the same 12 prompts, the same copied corpus, the same lexical-only reasoning (no
fact carries a `:vec`). Do not re-derive the baseline; it is recorded there.

- [ ] **Step 0: Copy and fingerprint.** `data/memory.edn` is `3b219034921e2f1bfe438837a2244e95`;
      `data/substrate.edn` is `e7a6eecdff7bb44ad6b959bb1a7fb7c6`. Work at
      `/tmp/loci-decay-measure/data`. **Never open the repo's `data/substrate`** — copy it.
- [ ] **Step 1: Run the classifier over the copy** and print **all 80 judgements**. Check the five
      known-good answers the spec names: `mem-34` (Kepler), `mem-35` (Mercury 88 days), `mem-36`
      (Neptune 165 years) durable; *"lithography capacity booked through 2027"* and *"TSMC controls
      ~70% of global foundry revenue"* **not** durable. **If it fails those, stop and report** —
      the classifier is not fit to touch the real memory and no table below will be worth reading.
- [ ] **Step 2: The 12 prompts, before and after**, top 6 each way. One row per prompt: what
      entered, what was displaced, and a one-line human judgement.
- [ ] **Step 3: Say what got worse.** A measurement that only confirms is not a measurement. **If
      more than a third of the prompts degrade, stop and report** — that is the same threshold that
      caught the union, and the decision is the user's.
- [ ] **Step 4: Report the shape of the classification** — how many of 80 durable, and whether the
      split looks like a judgement or like a coin. A classifier answering "durable" to everything
      would pass every test in Tasks 1–5 and be worthless.
- [ ] **Step 5: Verify `data/` untouched** — both md5s, and `git status` clean under `data/`.
- [ ] **Step 6: Commit** — `docs: what not decaying does to what the agent is told`

---

### Task 7: Run it against `data/`, once

**Files:** none. This changes the user's memory and nothing else.

**Gated on Task 6.** If Task 6 stopped and reported, this task does not start.

- [ ] **Step 1:** Confirm no server is running (`pgrep -af 'loci|clojure'`) and say what you found.
      `FileMemory` holds `!facts` in RAM; a concurrent `remember` would append from a stale atom.
- [ ] **Step 2:** Back up — `cp data/memory.edn data/memory.edn.bak-2026-08-11`. Record the md5.
- [ ] **Step 3:** Dry-run against the real `data/` and **paste the plan**. Compare it line by line
      with Task 6's run on the copy. Any difference is a stop.
- [ ] **Step 4:** Run it.
- [ ] **Step 5: Verify.** Line count grows by **exactly the number the dry run planned** — not
      necessarily 80, since a fact already carrying `:durable-by "you"` is skipped; the append-only
      prefix byte-identical to the backup; `:ts`/`:strength` unchanged on every classified fact
      (diff, do not eyeball); the count of durable facts matches Task 6's; both suites green.
- [ ] **Step 6: Say what it cost** — facts before/after, how many durable, the new md5.
- [ ] **Step 7: Commit** — `data: the memory knows which of its facts age` (`data/` is gitignored,
      so the commit is the record and Step 6's numbers are its body)

---

## Before calling this done

- [ ] `clojure -M:test` green (floor 478 / 2108), `npm run test:browser` green (floor 100)
- [ ] Every sabotage actually failed the test it was aimed at, and the note saying so is in the
      review rather than in someone's memory
- [ ] `data/substrate.edn` still `e7a6eecdff7bb44ad6b959bb1a7fb7c6`

## Out of scope

- **Half-lives.** Rejected in brainstorming: every extra number is a place to be wrong.
- **Decay's shape or its 30-day constant** for ordinary facts — unchanged, and now pinned by Task 1.
- **Heuristic classification** (dates, percentages, currency in the text) — a second judgement that
  would disagree with the first.
- **The corpus.** `2026-08-10-a-corpus-worth-browsing-design.md` depends on this and comes after.
