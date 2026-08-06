# loci — LEAP as a filter, and recency as a ranking signal: design

Date: 2026-08-06
Status: accepted in brainstorming

## Goal

Two changes to how you find things, both reached through LEAP rather than through new
chrome:

1. **Filtering the overview is a LEAP verb.** In overview, LEAP offers
   `⊟ Filter the overview: "…"` alongside Ask / Notebooks / Content / Create. Choosing it
   closes LEAP and dims non-matching notebooks. The filter is sticky until you enter a
   notebook or clear it.
2. **Recency ranks LEAP's results.** Within each group, most recently *touched* first —
   and the per-group cap of 8 takes the 8 most recent rather than the first 8 encountered.

The problem behind (1): with 24 notebooks the overview scrolls, and LEAP's dropdown covers
nearly half of it. Filtering by typing was the obvious fix, but suppressing the dropdown in
overview would have made memory hits, view verbs, Ask and New space unreachable from there.
Making the filter an *entry in the list* keeps LEAP's single gesture intact — the dropdown
closes because you finished with it, not because a mode changed.

The problem behind (2): `leap-payload` caps each group at 8 in whatever order the objects
happen to be enumerated. That is a coin-flip today, and it silently hides results.

## Decisions

- **Filter dims, never hides.** Consistent with the tag filter in
  `2026-08-06-tags-and-overview-design.md`, and for the same reason: removing cards
  destroys the spatial memory the overview exists to build. The point is to *see* the
  overview.
- **Recency orders within a group, never across groups.** `Ask` must stay first —
  "the top result is already the action" is a LEAP rule, not a default.
- **A notebook counts as touched when anything inside it is touched.** Editing a table in
  `Semiconductors` makes that notebook recent. Without this, a hub you work in constantly
  looks dormant because you never edit the space object itself.
- **Last-touched is computed from the event log, not read from Datalevin's `touched` dbi.**
  The dbi would be faster, but `PersistentStore` still exists, is still the documented
  rollback, and the parity suite runs every substrate assertion against both. A LEAP that
  ranks correctly on one store and arbitrarily on the other is worse than one that is
  uniformly a millisecond slower.
- **The sticky filter lives in the crumb.** There is nowhere else for persistent state to
  live in an interface with no chrome, and the crumb already names where you are.

## 1. Last-touched

One fold over `(sub/history st)` produces `{object-id last-ts}`: for each event, every id
it names — including each sub-event of a `:tx` — records the event's `:ts`.

A notebook's recency is then `max` of its own last-touched and that of every id in its
`:members`. Computed once per payload and passed to both `state-payload` and
`leap-payload`.

**Cost:** O(events) per request — microseconds at today's 97 events, roughly a millisecond
at 100,000. That is the same cost class as the per-request `materialize` removed on
2026-08-05, so it must not be reintroduced casually: if the log grows past ~10⁵ events this
becomes a maintained map in the store rather than a fold, alongside the state atom.

**Payload:** `state-payload`'s spaces each gain `:touched <ms>`; `leap-payload`'s entries
each gain `:touched <ms>` so the frontend can show age without a second request.

## 2. Recency in LEAP

`leap-payload` sorts each group's entries by `:touched` descending *before* applying the
existing `cap` of 8. Group order is unchanged: ask, content objects, prose, in-text,
views & functions, memory, verbs.

Entries with no meaningful timestamp — view verbs, the `__ask__` and `__new__` synthetic
entries — keep their current position and are not sorted.

## 3. Filter as a LEAP verb

**The entry.** When `mode === 'overview'` and the query is non-empty, the frontend appends
`{id:'__filter__', label:'⊟ Filter the overview: "…"', group:'filter'}` to `filtered`,
rendered under a `Filter` group heading. It is frontend-only — the server has no idea the
overview exists, and should not.

**Matching.** A notebook matches if any of:
- it is itself a `space` hit (title or id matched), or
- a `prose` hit carries its id (LEAP already returns the *notebook's* id for prose), or
- any id in its `:members` appears among the content hits (`in text` hits carry the
  document's id, not its notebook's, so this mapping is what lights the right card).

**Applying.** Selecting the entry closes LEAP, sets `overviewFilter = {q, ids}`, and dims
every panel whose notebook is not in `ids` to 22%. `layout()` reapplies the dimming so it
survives resize and re-render.

**Sticky.** It persists across re-renders and stays until: the query's ✕ in the crumb is
clicked, `Escape` is pressed in overview with no LEAP query, or a notebook is entered
(`enter(i)` clears it). The crumb reads `all notebooks · "lithography" ✕`.

**Below the fold.** The canvas scrolls, so a filter whose matches are all below the fold
would show a screen of grey and look broken. Applying a filter scrolls the first match into
view, and the crumb reports `6 of 24` so a zero-match filter is unmistakable rather than
looking like a rendering failure.

## 4. Testing

**Clojure:**
- last-touched picks the newest event that names an id, including inside a `:tx`
- a notebook's recency is the max over itself and its members
- `leap-payload` returns each group most-recent-first
- the cap of 8 keeps the 8 *most recent*, not the first 8 — the assertion that would have
  failed before this change
- an object never touched since creation still has a timestamp (its creating event)

**Browser:**
- with a query in overview, a `Filter` entry appears; in focus it does not
- selecting it closes LEAP and dims non-matching panels without removing them
- a notebook matches through a prose cell and through a contained object, not only by title
- the filter survives a resize (`layout()` reapplies it)
- entering a notebook clears it; the crumb's ✕ clears it
- a filter whose first match is below the fold scrolls it into view

## Out of scope

- An age gradient on the overview cards (discussed; a separate, larger idea)
- Sorting the overview itself by recency — recency ranks LEAP, arrangement stays structural
- Stable card positions across notebook creation (a real defect in the current greedy
  column packer, filed separately — adding a notebook can reshuffle columns and move a card
  you had memorised)
- Tags (`2026-08-06-tags-and-overview-design.md` §2), which this is independent of
- Maintaining last-touched incrementally in the store rather than folding per request
