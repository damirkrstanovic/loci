# loci — notebook tags + a structural overview: design

Date: 2026-08-06
Status: **§1 built and merged** (commits f813f73…5583209); §2 (tags) is what remains.
Amended 2026-08-06 for tri-state include/exclude filtering.

## Goal

Two related quality-of-life problems, at 24 notebooks and growing three with every
deep-dive:

1. **Too many on screen, all as peers.** The overview is a flat grid of equal cards. Nine
   of the 24 are deep-dive children competing for attention with the notebooks that
   spawned them.
2. **The structure is invisible.** The substrate computes `spawned-by`, `merged-from`,
   shared objects and derived lineage. Today that shows up as a few dashed threads and
   nothing you can navigate.
3. **No way to group by subject.** Semiconductors, world data and the seeded corporate set
   are three unrelated bodies of work, related only by topic — which is the one thing the
   substrate genuinely cannot derive, and therefore the one thing tags should do.

Explicitly *not* a problem: finding a notebook. LEAP already searches everything.

## Decisions

- **Tags are agent-proposed and human-editable**, never auto-applied unseen. loci has
  precedent on both sides — links are computed and never maintained, memory facts are
  agent-distilled with provenance — and tags follow the memory precedent, because a
  hand-maintained tag vocabulary is exactly the filing system Raskin argued no one keeps
  current. At 24 notebooks that point is already past.
- **The overview keeps one modeless surface.** No grouping-mode selector, no persistent
  tree rail. A rail was considered and rejected: it is the app chrome loci exists to argue
  against, it consumes attention whether or not it is wanted, and it makes the overview
  redundant.
- **The tag strip dims rather than hides.** Filtering that removes cards destroys the
  spatial memory the overview exists to build. Verified in the mock: with the
  semiconductor cluster lit and everything else at 22% opacity, the arrangement still
  reads.
- **The overview scrolls.** At 24 notebooks the grouped arrangement already exceeds one
  screen — measured, not predicted. Shrink-to-fit was rejected because it is what produced
  the 3px titles fixed on 2026-08-05; zoom tiers were rejected as materially more
  machinery than the rest of this work. The cost is honest: the overview stops being a
  single glance, and LEAP plus the tag strip become how you narrow it.
- **Two phases.** §1 needs no LLM, no schema change and no new endpoint. It ships alone.

## 1. The overview — frontend only

`layout()`'s overview branch and CSS in `resources/public/index.html`. Nothing in the
substrate changes; `/api/state` already carries `spawned-by` (a bare id) and
`merged-from` (a vector of ids).

**Clusters.** A root and its descendants are one visual group: the root at full card size,
descendants smaller and indented, joined by a vertical spine drawn in the existing
`#edges` SVG. Recursive — a deep-dive that spawned its own deep-dives owns them in turn,
as `Taiwan's Foundry Concentration` does. Roots are balanced across three columns by
cluster height. Measured on the live corpus: **24 notebooks become 15 clusters.**

**What nesting makes redundant.** A child's eyebrow becomes `DEEP-DIVE` — its position
already names its parent, and `DEEP-DIVE · SPAWNED FROM…` was truncated anyway. The
"spawned" status pill goes. Card height grows so a two-line serif title plus intent stops
being cut mid-sentence.

**Structure line when focused.** The focused panel's head gains one line —
`↰ Semiconductors — research hub · 2 siblings · 3 deep-dives` — with each part a link.
This is the tree-view-on-selection, and it costs one line rather than a permanent rail.

## 2. Tags — substrate, agent, filter

**Model.** `:tags` on the space's `:value`:

```clojure
{:tags [{:tag "semiconductors" :by "agent" :ts 1785…}
        {:tag "chokepoints"    :by "you"   :ts 1785…}]}
```

One reversible event per change. `:by` is recorded because the distinction matters — an
agent's assertion is an inference and you should be able to see which are which, exactly
as the memory pane cites its sources.

**Proposal.** A `⌗ tags` action on a notebook asks the agent for 2–3 tags drawn from the
notebook's actual contents, through the existing jobs layer (it is an LLM call, and the
shell must not hold a long fetch). The proposal is *shown*, not applied: accept, edit, or
ignore. A notebook with no tags is a normal state, not a defect.

**Inheritance.** A deep-dive child is created *about* its parent's subject, so it inherits
its parent's tags at creation and may gain its own. Without this, a grandchild drops out
of its own family under a filter — observed in the mock, where `Geopolitical Shock
Scenarios` dimmed while its siblings stayed lit.

**The strip.** A row beneath LEAP — not a floating overlay; the mock put it under the LEAP
bar where nothing could be clicked, and only a programmatic click hid the fact.

**Each tag is tri-state: neutral → include → exclude → neutral**, cycled by clicking it.
Include and exclude are visually distinct (accent fill vs a struck, clay-coloured chip), so
a glance says which is which without a legend.

The predicate is the ordinary one: **a notebook shows if it carries at least one included
tag (or none are included) AND carries none of the excluded ones.** Exclude beats include,
because "everything about semiconductors except the geopolitics" is the question people
actually ask, and the alternative — include winning — makes exclusion unexpressible.

**It composes with LEAP's filter rather than replacing it.** Both narrow the same set, and
both dim rather than hide (`2026-08-06-leap-filter-and-recency-design.md` §3). A notebook
is lit when it satisfies the LEAP filter *and* the tag predicate; the crumb shows both, e.g.
`all notebooks · "lithography" +semiconductors −geopolitics · 4 of 25`. There is exactly one
dimming mechanism and one place that says what is narrowing the view — a second, parallel
filter with its own rules would be the mode this design has avoided throughout.

Clearing follows the same rules as the LEAP filter: `Escape` in overview clears everything,
entering a notebook clears everything, and the crumb's ✕ clears everything. Tag state does
not survive entering a notebook, for the same reason the LEAP filter does not — it belongs
to the overview.

**Endpoints.** `POST /api/tags {space, tags}` sets them (one event, honest error on a
non-space id, same `space?` guard as every other notebook write). `POST /api/tag-suggest
{space}` returns a job id; `GET /api/job?id=` polls, as research and flows already do.
`/api/state` gains `:tags` per space.

## 3. Testing

- **Clojure:** setting tags commits exactly one reversible event; `undo` restores the
  previous tags; a non-space id is refused with an honest message; `state-payload` carries
  tags; a spawned child inherits its parent's tags at creation.
- **Browser:** the tag strip is clickable where it sits (the mock's actual failure —
  it was under the LEAP bar, and only a programmatic click concealed that); a tag cycles
  neutral → include → exclude → neutral; an included tag lights its notebooks and dims the
  rest **by computed opacity, not by class** (a class-counting assertion passed throughout a
  shipped no-op on 2026-08-06 — `layout()` writes inline `opacity:1` on every panel, which
  beats a stylesheet rule); an excluded tag dims its notebooks even when they also carry an
  included one; a LEAP filter and a tag filter together light only their intersection; and
  entering a notebook clears both.
- The agent's proposal quality is not testable and is not tested. What is tested is that a
  proposal never applies itself, and that a failed or offline suggestion leaves the
  notebook untouched.

## Out of scope

- Tag rename/merge across notebooks, and tag deletion cascades
- Tags on objects rather than notebooks
- Auto-tagging every notebook on creation (an LLM call per notebook, unprompted)
- Tag-based grouping *layout* — tags filter, structure groups
- Zoom tiers for the overview (revisit if the scroll becomes tiresome past ~40 notebooks)
- Any change to LEAP's ranking
