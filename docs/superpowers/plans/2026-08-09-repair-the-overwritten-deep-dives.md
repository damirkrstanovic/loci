# Repair the overwritten deep dives, and close the minter that overwrote them

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax. Reviewers **measure**; implementers **prove their tests
> bite**.

**Goal:** restore the three astronomy deep-dive notebooks that a count-based id minter silently
overwrote on 2026-07-23, re-file the 15 facts orphaned by that overwrite, and close the last two
count-based minters still live in `src/`.

**Forensics:** `docs/superpowers/specs/2026-08-09-overwritten-deep-dives-forensics.md` (written by
Task 0 below, from an investigation completed 2026-08-09). **Read it before touching anything.**

**Decision:** the user's, 2026-08-09. Fix the minter in this branch; repair **faithfully** (restore
the notebooks, not just re-point the facts); re-file `mem-33`…`mem-37` with the rest.

---

## What happened, in five lines

1. A research table arrived with a column named `Key Proponent(s)`. `pr-str` wrote `:Key Proponent(s)`, which `edn/read` cannot read.
2. The old `load-events` treated the parse failure as **EOF**, so the store booted with **49 of 80 events** — `space:dd-16/17/18` were invisible.
3. Deep-dive ids were minted `space:dd-` + `(inc (count (nb/notebooks st)))` — from a **count**, over that truncated view.
4. It re-minted 16, 17, 18 onto three **live** astronomy notebooks, and `:put` overwrote them with no error. `find:dd-16-1`, `find:dd-17-1`, `find:dd-18-1` collided the same way.
5. The 15 facts distilled from those notebooks were filed **correctly at the time**. Their notebooks were destroyed three hours later. They now read as Solar-System facts inside semiconductor deep dives.

Replaying the first 49 events and re-running the old minting form predicts **4 of 4** collisions, including the table id. Bug (2) is closed (`col-kw`, `safe-event`, a line-oriented loader, and Datalevin's nippy write path). Bug (3) is closed **for `space:dd-`** by `fresh-dd-id`. It is **not** closed everywhere — see Task 1.

## The hard rule for this whole plan

**Tasks 2 and 3 write to `data/`.** That is the one thing this project otherwise forbids outright,
and it is why they are the last two tasks, why each is rehearsed on a copy first, and why each is
reversible.

- **The server must be stopped.** `FileMemory` holds `!facts` in RAM; a `remember` landing during
  the repair would append a record rebuilt from the stale atom and undo it silently. Datalevin
  holds a directory lock on `data/substrate`.
- **Back up first, verify after.** `data/memory.edn` is `1999a9d108df48b17ccbb51291a0a955` and
  `data/substrate.edn` is `e7a6eecdff7bb44ad6b959bb1a7fb7c6` as of 2026-08-09.
- **Append, never rewrite.** `data/memory.edn` is append-only and last-wins by `:id`; the tombstone
  reasoning in `loci.memory/next-id` depends on that. Editing lines in place breaks it.
- **Rehearse on a copy and diff before touching the real thing.** Every task below says so.

---

## File structure

| file | responsibility |
|---|---|
| **create** `docs/superpowers/specs/2026-08-09-overwritten-deep-dives-forensics.md` | the evidence, so the repair is reviewable against something |
| modify `src/loci/server.clj` | `compute!`'s two count-based minters → `next-id` |
| modify `test/loci/server_test.clj` | pin that both mint past an existing id |
| **create** `src/loci/repair.clj` | the repair itself: **dry-run by default**, one function per phase, no `:main`, nothing runs on require |
| **create** `test/loci/repair_test.clj` | the whole repair, end to end, against synthetic stores — including that a dry run writes nothing |

`loci.repair` rather than a REPL session, for the reason `loci.migrate` exists: a repair you can
read, test, dry-run and review is a different object from a form someone typed once.

---

### Task 0: Write the forensics down

**Files:** create `docs/superpowers/specs/2026-08-09-overwritten-deep-dives-forensics.md`

The investigation is complete and its evidence is in this session's transcript; it is not in the
repo, and the repair is unreviewable without it.

- [ ] **Step 1:** Record, with event numbers and timestamps: the six reused ids and their two
      `:put`s each (`space:dd-16` ev 56 → ev 65, and so on); the poisoned line 50 and its exact
      reader error; the replay showing the old minting form predicting all four collisions; the 15
      facts as a table with `:source`, `:entities` and `:ts`; and the three hypotheses that were
      **disproved** (`distill!`'s `space` argument, the 2026-08-05 repair re-associating anything,
      deletion-driven collision — zero `:delete` events in 115).
- [ ] **Step 2:** Record what survives and what does not: the three notebooks' titles, intents and
      `:spawned-by {:space "space:cosmos"}`; the findings text for dd-16 (1,733 chars) and dd-17
      (2,752 chars); that **dd-18's findings document was committed empty** (`:value ""`), which is
      why `mem-33`…`mem-37` are generic; and the orphan `tbl:extract-14-588`, referenced by no
      notebook.
- [ ] **Step 3: Commit** — `docs: how three notebooks were overwritten, with the events`

---

### Task 1: Close the last count-based minters

**Files:** modify `src/loci/server.clj` (`compute!`), `test/loci/server_test.clj`

`compute!` mints `fn:<src>-<(inc nf)>` and **`tbl:derived-<(inc nt)>`** from counts, with **no
random suffix and no existence check**, landing via `:put` — the exact shape that destroyed three
notebooks. `server.clj` already mints `tbl:derived-` through `next-id` (max+1) elsewhere, so there
are **two rules for one prefix**.

It does not collide today (22 tables live, next mint 23). It collides when two computes run
concurrently, or after any event the store cannot see — which is precisely what happened in 2026-07.

- [ ] **Step 1: Write the failing tests.** A store already holding `tbl:derived-5` and `fn:x-5`
      with **fewer than five** of each kind present (that is the collision shape — count and max
      disagree). Assert the next mint is `-6`, not `-2`. Make the fixture's count and max
      genuinely differ, or the test cannot fail.
- [ ] **Step 2: fail. Step 3:** switch both to `next-id`. Step 4: green (`clojure -M:test`).
- [ ] **Step 5: Prove it bites.** Restore the count form; both assertions must fail.
- [ ] **Step 6: Commit** — `fix: the last two ids minted from a count, which is how we lost three notebooks`

---

### Task 2: Restore the three notebooks — substrate, reversible

**Files:** create `src/loci/repair.clj`, `test/loci/repair_test.clj`

Restore under **fresh ids** — never the old ones, which now name live semiconductor notebooks.
`fresh-dd-id` gives `space:dd-35` next (dd-34 is the highest).

What to restore, from the events named in Task 0:

| | from | becomes |
|---|---|---|
| "Inner vs. Outer Planets" | ev 56 | a fresh `space:dd-N`, `:spawned-by {:space "space:cosmos"}` |
| "Planetary Moons" | ev 59 | a fresh `space:dd-N` |
| "Orbital Period Scaling" | ev 61 | a fresh `space:dd-N` |
| its findings doc (**1,416** chars) | ev 58 | a fresh `find:` doc, celled into the first |
| its findings doc (**2,442** chars) | ev 60 | a fresh `find:` doc, celled into the second |

> **Correction, 2026-08-09.** An earlier draft of this table said 1,733 and 2,752. Those are the
> lengths of the whole EDN **log lines** — `:tx` wrapper, ids, titles, escaping. The document
> **bodies** are **1,416** and **2,442**. Assert against the body lengths; asserting against the
> line lengths would fail and look like data loss.
| `tbl:extract-14-588` | **already live, orphaned** | celled into the first — adopted, not copied |

**dd-18's findings document was empty and stays uncreated.** Restoring an empty document to hold
five facts distilled from nothing would manufacture provenance. Its notebook is restored; it simply
has no findings cell, which is what the log says happened.

- [ ] **Step 1: Write the failing tests** against a `sub/fresh-store` seeded to mimic the real
      shape. Pin: **one `:tx`**, so `sub/undo!` reverses the whole restoration; fresh ids that
      collide with nothing; `:spawned-by` pointing at `space:cosmos`; the orphan table **adopted**
      (its object unchanged, a cell added) rather than duplicated; and **a dry run writes nothing** —
      assert `(count (sub/history st))` is unchanged and the returned plan says what it would do.
- [ ] **Step 2: fail. Step 3:** implement `loci.repair/restore-astronomy-dives!` with
      `{:dry-run? true}` as the **default**. Step 4: green.
- [ ] **Step 5: Prove it bites.** Split the `:tx`; make the dry run write; reuse an old id.
- [ ] **Step 6: Rehearse on a copy.** `cp -r data /tmp/loci-repair-rehearsal/data`, run against
      `LOCI_DATA=/tmp/loci-repair-rehearsal/data`, and **report the diff** — object count, event
      count, the three new notebooks, the adopted table. Confirm `md5sum` of the real `data/` is
      unchanged by the rehearsal.
- [ ] **Step 7: Commit** the code and the rehearsal transcript. **Do not run it against `data/` yet** —
      Task 4 does that, once, deliberately.

---

### Task 3: Re-file the 15 facts — memory, reversible

**Files:** `src/loci/repair.clj`, `test/loci/repair_test.clj`

**Both halves of `:source` must change.** Rewriting only `:space` leaves `:obj "find:dd-16-1"`,
which is a **cell of today's `space:dd-16`** — `lineage-sources` includes cell ids, so the facts
would stay in the semiconductor notebook's scope, and `remembered-context` cites
`(or (:obj …) (:space …))`, so the block would still print a Jupiter fact as `⌾ find:dd-16-1`.

Targets, by group: `mem-23`…`mem-27` → restored "Inner vs. Outer Planets"; `mem-28`…`mem-32` →
"Planetary Moons"; `mem-33`…`mem-37` → "Orbital Period Scaling" (the user's decision: re-filed with
the rest, despite being distilled from an empty document). `:obj` is the restored findings doc where
one exists, and `nil` for the dd-18 group — `{:obj nil :space …}` is a shape `ask!` already writes.

**Do NOT route this through `mold/remember`.** It would find the fact itself as a jaccard ≥ 0.6
duplicate, `(update :strength inc)` and **`(assoc :ts (System/currentTimeMillis))`** — and `:ts` is a
multiplier in `weight`, so all 15 planet facts would jump to the top of every ranking. Worse, `dup`
is `(some …)` over an unordered map, so it can match a *different* fact whose text is 60% similar;
`mem-33`/`mem-34` and `mem-25`/`mem-35` are live candidates. **Append the record directly**, the way
`store-vector!` and `unmerge!` do.

- [ ] **Step 1: Write the failing tests.** Pin: exactly 15 appended lines; **`:ts` and `:strength`
      unchanged** on every one (the `remember` trap, asserted directly); `:vec`/`:model`/`:dim`
      preserved if present; the record read **at write time**, not from a snapshot taken earlier;
      a dry run appending nothing; and that a second run is a **no-op** (already-correct facts are
      not re-appended).
- [ ] **Step 2: fail. Step 3:** implement `loci.repair/refile-astronomy-facts!`, dry-run by default.
      Step 4: green.
- [ ] **Step 5: Prove it bites.** Route it through `mold/remember` and watch the `:ts` assertion
      fail; change only `:space` and watch the `:obj` assertion fail; take a snapshot first and
      watch the read-at-write-time assertion fail.
- [ ] **Step 6: Rehearse on the copy**, on top of Task 2's rehearsal, and report: 15 lines added,
      `mem-23`…`mem-37` now resolving to the restored notebooks, every other fact byte-identical.
- [ ] **Step 7: Commit.** Still not against `data/`.

---

### Task 4: Run it, once, against `data/` — and prove it

**Files:** none. This task changes the user's data and nothing else.

- [ ] **Step 1:** Confirm no server is running (`pgrep -af 'loci\|clojure'`), and say what you found.
- [ ] **Step 2:** Back up. `cp data/memory.edn data/memory.edn.bak-2026-08-09` and
      `cp -r data/substrate data/substrate.bak-2026-08-09`. Record `md5sum` of both before.
- [ ] **Step 3:** Dry-run both phases against the real `data/` and **paste the plan they report**.
      Compare it line by line with the rehearsal. Any difference is a stop.
- [ ] **Step 4:** Run both, in order — restore, then re-file.
- [ ] **Step 5: Verify, three ways.** The three notebooks exist with their titles and
      `:spawned-by`; `mem-23`…`mem-37` resolve to them and to no `dd-16/17/18`; **every other fact
      is byte-identical** to the backup (diff, do not eyeball); the substrate's other 89 objects are
      unchanged; `clojure -M:test` and `npm run test:browser` both still green.
- [ ] **Step 6: Say what it cost.** Event count before and after, fact count before and after, and
      the new `md5sum`s — so the next person can tell this repair from a corruption.
- [ ] **Step 7: Commit** — `data: the astronomy deep dives are notebooks again` (the code, not
      `data/`, which is gitignored — so the commit is the record, and Step 6's numbers are its body)

---

## Out of scope

- **`forget!`** — still undecided, still its own spec.
- **Anything about the entities feature.** That branch's work stands; this plan repairs data it
  merely made visible.
- **The Serbia / World-economies notebooks.** They own zero facts because no agent flow has ever
  run in them — `space:pub-geo` proves the mechanism works, holding 16 facts through four
  researched children. **Untested, not broken.** Nothing to repair.
- **Datalevin's missing `safe-event`.** `loci/dlv.clj` runs `normalize-keys` but not `safe-event`,
  so a Datalevin log can hold values the EDN log cannot express. That matters only on a rollback to
  `PersistentStore`, it is already documented there, and it is not what broke here.
