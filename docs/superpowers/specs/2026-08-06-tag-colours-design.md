# loci — tag colours: suggested on creation, chosen by you

Date: 2026-08-06
Status: proposed

## Goal

Give every tag a colour, so a tag is recognisable before it is read. loci is deliberately
thin on colour — a paper ground, one green accent, one clay for attention — and tags are
where that scarce resource buys the most, because a tag is exactly the kind of thing you
want to spot rather than parse.

A colour is **suggested when a tag first appears** and is **yours to change**.

## Decisions

- **Colour belongs to the tag name, globally — not to a tag on a notebook.** `world data`
  is the same subject wherever it appears; if the colour lived on each notebook's tag entry
  it could be green on one card and plum on the next, which destroys the one thing colour
  is here to do. So there is one registry, and changing a colour changes it everywhere at
  once.
- **The registry is an ordinary object in the substrate**, so a colour change is an ordinary
  reversible event. No new storage mechanism, no preferences file, no localStorage —
  colours survive a reload and a rebuild because everything else does.
- **Suggested at creation, not derived on the fly.** Hashing the name to an ink would need
  no storage, but with eight inks and six tags a collision is likelier than not, and two
  subjects sharing an ink is precisely the failure this feature exists to prevent. So the
  suggestion is *assigned* — the least-used ink at the moment the tag is first set — and
  then it is a fact, stable against every later change.
- **Proposing still writes nothing.** `suggest-tags!` assigns no colours. Only actually
  setting a tag does. The rule that the agent's proposal touches the substrate not at all
  is worth more than the tidiness of pre-colouring a suggestion you may discard.
- **State beats identity in the strip.** Include and exclude already mean something in
  colour (accent fill, clay strike). Where they compete with a tag's own ink, the state
  wins and the ink retreats to the swatch — see §4.
- **Cards are untouched.** Whether tag colour should also mark the notebook cards in the
  overview is a real question and a separate one.

## 1. The palette

Eight inks, all in the same lightness band as the paper's rules, so a strip of them reads
as a family rather than a set of stickers:

| name | hex | | name | hex |
|---|---|---|---|---|
| green | `#2f6f5b` | | garnet | `#8c3f5a` |
| teal | `#2b6b74` | | bronze | `#7a5a2f` |
| indigo | `#3f5a8a` | | olive | `#5f6b33` |
| violet | `#6b4a8a` | | slate | `#4a5560` |

Green is loci's existing accent, so the first tag you make looks like it belongs.

**Clay (`--attn`, `#a9632f`) is deliberately not in the palette.** Clay means *excluded* in
the strip; a clay tag that was also excluded would be a chip you could not read.

Past eight tags the inks repeat, least-used first. Eight distinct subjects at a glance is
already more than the overview can hold; a ninth sharing an ink with the first is a smaller
cost than a palette stretched into mud.

## 2. Storage

One object, created on first use:

```clojure
{:id "tag-palette" :kind :palette :title "tag colours"
 :value {"world data" "#2f6f5b"
         "company"    "#3f5a8a"}}
```

A colour change is one event:

```clojure
{:op :assoc :id "tag-palette" :path [:value "world data"] :value "#6b4a8a"}
```

`:palette` joins `#{:space :viewspec :applet :fn}` in the two places `server.clj` already
hides infrastructure from you — the `:objects` list in `state-payload` and LEAP's result
set. A registry is plumbing; it must not turn up in search results next to your notebooks.

`/api/state` gains `:tag-colors` — the map itself, so the shell can colour any chip without
a second request.

## 3. Assignment

`set-tags!` gains one responsibility: after cleaning the tags, any name **not already in the
registry** is assigned the ink used by the fewest existing tags, ties broken by a hash of
the name so the choice is deterministic and therefore testable.

Assignment commits a **second event**, separate from the tag event. This is deliberate and
worth stating plainly: **undoing "I tagged this notebook" does not un-choose the colour.**
The colour is a standing preference about a subject, not a claim about a notebook, and
having a tag change its colour every time you undo an unrelated edit would be worse than
the small untidiness of an event that outlives its cause.

When nothing is new, nothing is committed — re-saving an unchanged strip stays free, as it
is today.

## 4. Where colour shows

**The strip.** Each chip gains a small ink square before its label.

| state | chip |
|---|---|
| neutral | ink square, otherwise as today — white, `--line` border, `--muted` label |
| include | the tag's own ink for border and label, over an 11% fill of the same ink |
| exclude | clay border and label, struck through, **ink square unchanged** |

Exclude overrides the ink because the strike and the clay are what tell you the tag is
subtracting; the square still says which tag it is.

**The editor.** In `⌗ tags`, each chip's swatch is a button. Clicking it opens a small
palette of the eight inks with the current one marked; picking one commits the colour event
and every chip of that tag — in the editor, in the strip — changes at once. The agent's
tags keep their dashed border: colour says *which subject*, the border still says *who
claimed it*.

**Endpoint.** `POST /api/tag-color {tag, color}` → one event, or none when the colour is
already that. It refuses a blank tag and refuses a colour that is not one of the eight,
each with an honest message rather than an exception — the palette is a closed set, and a
free-form hex would let the shell write a colour that fails the very contrast the palette
was chosen for.

## 5. Testing

**Clojure:**
- setting a brand-new tag commits the tag event **and** one palette event; the registry then
  holds that tag
- setting an existing tag again commits **no** palette event
- eight distinct tag names receive **eight distinct inks** — the test that fails if
  assignment is ever reduced to a bare hash
- the ninth tag reuses an ink, and reuses a least-used one
- `suggest-tags!` commits **zero** events, colours included
- `set-tags!` on a non-space id is still refused, and assigns nothing
- `POST /api/tag-color` with a colour outside the palette is refused with a message
- undoing the tag event leaves the colour in place (asserting the decision in §3, so that a
  later change to it is a deliberate change and not a silent regression)
- `state-payload` carries `:tag-colors`, and the palette object appears in neither the
  `:objects` list nor LEAP's results

**Browser:**
- every chip in the strip and the editor renders its tag's ink, read as a **computed
  colour**, not as a class name — the class-counting assertion that passed straight through
  a shipped no-op on 2026-08-06 is exactly the mistake to not repeat here
- an included chip's border colour equals its tag's ink and **not** the generic accent
- an excluded chip is clay and struck **even though** its tag's ink is something else, and
  its swatch still shows the ink
- picking a colour in the editor changes the strip chip's computed colour too
- a tag whose colour was never chosen still renders a colour (the suggestion), so an
  untouched corpus is never grey

## Out of scope

- Colouring the notebook cards in the overview by tag — worth doing, decided separately
- Renaming or merging tags (already out of scope for tags generally)
- A custom colour picker or free-form hex
- Per-notebook colour overrides
- Colour in the memory pane, LEAP results, or anywhere outside the strip and the editor
