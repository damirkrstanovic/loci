# The time slider holds still

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax. Prove your tests bite.

**Goal:** make the time scrubber usable with a mouse — a track that does not resize while you
drag it, enough pixels per event to aim, and a scrub that does not close what you were reading.

**Reported by the user, 2026-08-10:** *"the time slider is finicky to use via mouse — it changes
size, i cannot do fine control, if i slide too little nothing happens, there is no sticky to a
point in time."*

---

## The mechanism, diagnosed

`.timebar` (`resources/public/index.html:213-217`) is a **fixed-width** flex pill —
`width:min(720px,92vw)` — containing `↩ now`, the range input at `flex:1`, and `.timelabel` at
`flex:0 1 auto; max-width:40%`.

**The label sizes to its own text, and that text changes on every scrub.** `timeCaption()`
returns `now · 116 events` at one end and
`event 65/116 · ＋ Geopolitical Shock Scenarios · 23/07/2026, 21:07:24` in the middle.

| position | label ≈ | track ≈ | px per event (116 events) |
|---|---|---|---|
| at `now` | 105 px | 575 px | 4.96 |
| mid-log | 288 px (capped) | 392 px | **3.38** |

So the track loses about a third of its width as you drag into the middle of the log, **live**,
and the pixel↔event mapping shifts under the cursor. That one fact explains three of the four
complaints: the size change is literal; fine control is impossible against a moving scale; and
"slide a little, nothing happens" is what a track *growing* beneath the thumb feels like.

The fourth — "no sticky to a point in time" — is separate: `step` is already 1, so the value does
snap, but the only feedback is a label that is itself moving, and nothing marks a position you
want to come back to.

**And a fifth the user did not name:** `scrubTo` (`:1435`) runs `mode='overview'; openId=null` on
every settle, so scrubbing closes whatever notebook or object you were reading. You cannot watch
one thing change over time, which is the main reason to have a scrubber.

---

### Task 1: The track stops resizing

**Files:** modify `resources/public/index.html` (`.timebar` CSS and the `#timebar` markup)
**Test:** `test/browser/timescrub.test.mjs` (**create**)

Put the label on **its own row above the track**, so its width cannot reach the track at all, and
give the track the pill's full width. Two rows in the pill; it gets taller, and the track goes
from ~392–575 px to a stable ~688 px — **5.93 px per event, constant**.

Reserving a fixed-width label instead (`flex:0 0 288px`) would also stop the resize, and is a
smaller diff — but it permanently spends 40% of the bar on text and leaves the track at ~4 px per
event. The row split is what actually buys the precision, so take it.

Keep `↩ now` where the hands already know it.

- [ ] **Step 1: Write the failing browser test.** Enter time mode, then **measure
      `#timeRange.getBoundingClientRect().width` at three slider positions** — `0`, mid-log, and
      `max` — and assert all three are equal. That is the defect, stated directly. Assert the
      label's text really does differ at those three positions, or the test passes for the wrong
      reason (a label that never changes would also never resize anything).
- [ ] **Step 2: Run it and watch it fail** — the widths differ today.
- [ ] **Step 3: Implement. Step 4: green**, then `npm run test:browser` whole.
- [ ] **Step 5: Prove it bites.** Restore `flex:1` on the range with the label back in the row;
      the width assertion must fail. Separately, make `timeCaption()` return a constant string —
      the "labels differ" assertion must fail.
- [ ] **Step 6: Commit** — `fix: the time slider stops resizing while you drag it`

---

### Task 2: Scrubbing keeps your place

**Files:** modify `resources/public/index.html` (`scrubTo`, `enterTime`)
**Test:** `test/browser/timescrub.test.mjs`

`scrubTo` forces `mode='overview'; openId=null` on every settle. Drop it, and re-render whatever
is open at the new `?at=`.

**The thing to get right:** an object open at event 90 may not exist at event 40. `openObject`
already asks `/api/object/<id>?at=`, and the server answers a missing object with
`{title:null, kind:"data", rendered:null}` — which is the empty molder the entities sweep found
and fixed at a different door. So: if the open object is absent from the new state, fall back to
the overview **and say so** with a toast naming the object, rather than silently emptying the
panel. `state-payload`'s `:objects` is what to check against.

`enterTime` has the same `overview()` call. Decide whether entering time should keep your place
too — it probably should, for the same reason — and say what you chose.

- [ ] **Step 1: Write the failing tests.** Open a document, enter time, scrub back one event that
      does not remove it: the same document is still open (assert on its title in the molder, not
      on a class). Then scrub back past its creation: the overview is showing **and** a toast
      names it. Both fail today — the first because the panel closes, the second because nothing
      is said.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green**, both suites.
- [ ] **Step 5: Prove it bites.** Restore `mode='overview'`; the keeps-your-place test fails.
      Drop the toast; the second fails. Make the fallback silent-but-empty; the second must still
      fail — if it does not, the assertion is on the wrong thing.
- [ ] **Step 6: Commit** — `fix: scrubbing time no longer closes what you were reading`

---

### Task 3: Aim, and come back

**Files:** modify `resources/public/index.html`
**Test:** `test/browser/timescrub.test.mjs`

Two small affordances, both cheap:

1. **Keyboard stepping, made visible.** A range input already answers ← → when focused; nothing
   says so. Put the hint in the label row (`← → step one event`) and make sure the input takes
   focus when time mode opens, so the keys work without a click. This is the real answer to "I
   cannot do fine control" — 1 event per press beats 5.93 px of mouse travel.
2. **A mark you can return to.** "Sticky to a point in time" — the position you entered time from
   is `TIME.total` (now), and `↩ now` already returns there. What is missing is a mark for an
   *interesting* moment you scrubbed to. Add a single `⌖ mark` toggle that records the current
   `n` and draws a tick on the track; clicking the tick returns to it. One mark, not many —
   more than one needs a design, and this needs to earn its place first.

**If you think (2) is over-building for the complaint, say so and build only (1).** The user said
"no sticky to a point in time", which may equally mean "the value does not snap" — it does, `step`
is 1 — or "I lose the place I was looking at". Task 2 fixes the second reading. Use your judgement
and **argue it in the report** rather than building both to be safe.

- [ ] **Step 1: Write the failing tests** for whatever you build. For the keyboard hint: focus is
      on `#timeRange` after `enterTime`, and a `ArrowLeft` press moves the value by exactly 1.
- [ ] **Step 2: fail. Step 3: implement. Step 4: green**, both suites.
- [ ] **Step 5: Prove it bites.**
- [ ] **Step 6: Commit** — `feat: step the scrubber a event at a time, and mark where you were`

---

## Before calling this done

- [ ] `npm run test:browser` green (floor **96**), `clojure -M:test` green (floor **478 / 2108**)
- [ ] **Drive it by hand.** Boot the app, enter time mode, and scrub with the mouse across the
      whole log. Report what it feels like in the user's own terms — does the track hold still,
      can you land on a chosen event, does what you were reading stay open. A green test is not
      the deliverable here; the deliverable is that it stops being finicky.
- [ ] `md5sum data/memory.edn` unchanged (`3b219034921e2f1bfe438837a2244e95` as of the repair)

## Out of scope

- The cost of a scrub. Each settle is a round trip and the server rebuilds the world
  (`frozen-at` materializes an n-event prefix, then `state-payload` folds the whole log again for
  recency). At 116 events that is not what makes it feel bad — the resizing track is. **Do not
  optimize it**; if the hand-drive says otherwise, report that rather than acting on it.
- The 120 ms debounce, for the same reason.
- Anything about entities. That branch's work is done.
