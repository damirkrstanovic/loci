# loci — ✧ Suggest: the agent proposes, you decide

Date: 2026-08-06
Status: accepted in brainstorming

## Goal

Replace **⌘ Deep-dive** with **✧ Suggest**: the agent reads the notebook and proposes two
or three questions worth pursuing; you see them, edit or refuse them, and choose whether
the answers land in new notebooks or in this one.

Deep-dive today does all of that without asking. One click and three notebooks appear,
researched, on questions you never saw. Measured on the live corpus it is the most
productive verb in the app — **9 of 25 notebooks and 8 of 10 findings documents came from
it** — and simultaneously the one that scatters attention hardest: results you did not
request, on questions you did not pick, in notebooks you must now manage, while you were in
the middle of something else.

The capability is right. The absence of consent is the problem.

## The redundancy question, settled

Deep-dive was suspected of overlapping Research and Flow. It does not:

| verb | who picks the question | where the answer lands |
|---|---|---|
| Research | you | this notebook |
| **Suggest** | **the agent proposes, you approve** | **your choice** |
| Flow | you set a goal, the agent plans steps | this notebook |

Flow *cannot* express it: its verbs are research, compute, ask, draft and gate, and every
one acts inside the current notebook. Nothing in flow creates a notebook.

The real overlap is elsewhere and is left alone: a one-step flow whose only verb is
`research` **is** the Research button with a planning round-trip in front. That is worth
collapsing one day, along with the five-button agent row, in the same spirit as the four
molding buttons that became `✦ make…`. Not here.

## Decisions

- **Nothing runs until you say so.** Proposing costs one small agent call and **writes no
  events at all**. Dismissing leaves the substrate untouched — not a reversible event, no
  event.
- **Two destination buttons, never one mutating button.** A button whose meaning depends on
  a toggle is a mode, and the two outcomes differ sharply — three new notebooks versus
  three cells here. Modelessness is the thesis; a mis-click here is expensive.
- **Questions are editable, not merely checkable.** The agent's phrasing is usually close
  and rarely exact. Once the text is on screen a field costs nothing, and refusing a good
  subtopic over one wrong word is a real loss.
- **One destination for the whole batch.** Mixing is expressible by running it twice; a
  per-row toggle triples the controls for a case that will not come up.
- **Blocking, because the wait is short.** The proposal is a single JSON-mode call, not a
  research run. The modal loci already uses for Ask is the right surface: it blocks, it is
  transient, and it is where decisions are already made.
- **Renamed.** "Deep-dive" describes going deeper into one thing; this proposes several and
  may keep them where they are. Renaming while the behaviour changes costs one habit
  change instead of two.

## 1. Flow of control

1. **✧ Suggest** on a notebook → the button shows a working state.
2. `POST /api/suggest {space}` → a job (an LLM call must not hold a request open, as with
   research and flows) → returns `{proposals:[{title, intent, query}]}`. **No writes.**
3. The modal opens with one editable row per proposal: a checkbox, the title, and the
   research question in a text field. All checked by default.
4. Two buttons: **`Research in new notebooks`** and **`Research in this notebook`**, each
   labelled with the count — `Research 2 in new notebooks`. Plus **Dismiss**.
5. `POST /api/suggest-run {space, items, destination}` → a job → does the work, then the
   shell polls as it does for research today.

`destination` is `"new"` or `"here"`. `items` is the curated, possibly edited list — the
server does not re-ask the agent and does not re-read the originals, so what you approved
is exactly what runs.

## 2. What each destination does

**`"new"`** — today's behaviour, unchanged: for each item, commit a `:space` carrying
`:spawned-by {:space <hub> :prompt <query>}`, then run the existing `research!` inside it.
The id prefix stays `space:dd-N`; nine existing notebooks use it, and an id is an id.

**`"here"`** — run `research!` in the hub itself, once per item. Each lands its findings
document (and any extracted table) as cells in the current notebook, exactly as pressing
Research repeatedly would.

Both are ordinary reversible events. Neither is atomic across items: three researches are
three separate commits, and a failure on the second leaves the first in place. That is the
existing behaviour of deep-dive and is deliberate — a partial result is worth keeping and
is individually undoable.

## 3. Server shape

`deep-dive!` splits along the seam it always had:

- `suggest! [st space]` — validates the notebook, builds the same digest (every cell's
  object digest plus recalled memory), calls `agent/propose-subtopics`, returns the
  proposals. Writes nothing.
- `run-suggestions! [st space items destination]` — takes the curated items and does the
  work. Refuses a non-space id, an empty item list, and an unknown destination, each with
  an honest message.

`deep-dive!`, `deep-dive-start!` and `POST /api/deep-dive` are deleted. The frontend's
`ddBtn` and its handler go with them.

The proposal prompt itself is unchanged — it is twenty words asking for 2–3 subtopics as
JSON, grounded in the notebook's full digest, and the quality it has produced comes from
the model's judgment rather than the prompt's elaboration. Changing it is a separate
question from changing who approves the result.

## 4. Testing

**Clojure:**
- `suggest!` commits **zero** events — asserted by event count before and after, with the
  agent stubbed
- `suggest!` refuses a non-space id honestly
- `run-suggestions!` with `"new"` creates one notebook per item, each carrying
  `:spawned-by` pointing at the hub
- `run-suggestions!` with `"here"` creates **no** notebooks and appends one findings cell
  per item to the hub
- an empty item list, an unknown destination and a non-space id are each refused with a
  message, not an exception
- a failure on the second of three items leaves the first's work committed

**Browser:**
- pressing ✧ Suggest opens the modal without committing anything (event count unchanged)
- unchecking a row lowers both buttons' counts
- editing a question changes what is sent — asserted on the request body, not on the UI
- Dismiss closes the modal and leaves the event count unchanged
- the two destination buttons are distinct controls; there is no toggle

The agent's judgment about *which* subtopics are worth proposing is not testable and is not
tested. What is tested is that nothing happens without approval, and that what you approved
is what runs.

## Out of scope

- Asking for more or fewer than the agent's 2–3 proposals
- Re-proposing without closing the modal
- Per-row destinations
- Collapsing Research into a one-step Flow, or the five agent buttons into one verb — real,
  and its own piece of work
- Changing the proposal prompt
- Making a multi-item run atomic
