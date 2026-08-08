# Entities — a world for the facts to hang on: design

Date: 2026-08-08
Status: **designed, not planned.** Approved in brainstorming 2026-08-08; no implementation
plan written yet.

## The complaint

> "The memory does not have context — it is not linked to anything in the world. We
> remembered some facts about a game, but there is nothing pointing to this game, so it
> just sits there, empty of meaning."

Three distinct failures wear that one face. All three are in scope.

### 1. Entities exist already, and are invisible

A fact is recorded as:

```clojure
{:id "mem-2" :fact "TSMC controls approximately 70% of global foundry revenue…"
 :entities ["tsmc" "taiwan"] :source {:obj "find:semis-1" :space "space:semis"}
 :ts 1784825023059 :strength 1}
```

`distill-facts` (`agent.clj:227`) extracts `:entities` on every fact. Their **only**
consumer is `relevance` (`memory.clj:66`), where they contribute 0.4 of a score. They are
never rendered, never clickable, never searchable — grep the shell and the memory pane
(`index.html:1860-1882`) draws fact text, strength dots, the `:source` chip, age, relevance
and the merge list. A subject index already exists; nothing surfaces it.

### 2. Nothing makes a fact name its own subject

The distiller is asked for "durable, atomic facts — figures, causal claims, named entities",
one sentence each. Nothing requires the sentence to say what it is *about*. A fact distilled
inside a game notebook can read "The Aeon path locks out the Lich ending" and be true,
atomic, durable — and meaningless a month later with no notebook around it.

### 3. Recall is scoped by lineage, so orphan facts go dark

`remembered-context` (`server.clj:1096`) narrows candidates to `lineage-sources`: this
notebook, its cells, what it spawned transitively, what was merged into it. A fact recorded
in a notebook you have since left is **never** injected into any other notebook's context,
however relevant. The 2026-08-08 scoping decision named this cost honestly ("notebooks that
share a SUBJECT but not a LINEAGE go blind to each other") and accepted it. That acceptance
is what this design revisits: the facts do not merely *look* contextless, they are
mechanically unreachable outside the memory pane.

## Measured, before designing

`data/memory.edn`, read (never written) on 2026-08-08:

| | |
|---|---|
| live facts (tombstones removed) | 80 |
| entity mentions | 203 |
| distinct entity names | 104 |
| names appearing exactly once | **68** |
| distinct `:source` objects / spaces | 15 / 12 |
| most frequent | `tsmc` 10, `asml` 8, `jupiter` 7, `taiwan` 7, `united states` 6 |

Two numbers shaped the design. **68 of 104 names appear once** — a world made of every
extracted string is two-thirds noise. And `united states` appears in 6 facts spanning three
unrelated subjects — frequency alone does not make a good subject anchor.

## Decisions

Settled in brainstorming, each with the alternative it beat:

1. **An entity is derived, and materializes on use.** Not a substrate object from the start
   — an entity created by an agent run would be removed by `undo!` while the facts citing it
   survive, manufacturing the dead-provenance failure loci has so far avoided. Not a
   memory-side record either, because then it is not in the world.
2. **Promoted on evidence**, not on extraction and not by curation. A name earns identity.
3. **Recall unions with lineage** rather than replacing it. Dropping lineage would stop a
   deep-dive child inheriting its parent's facts — an immediate regression.
4. **Tags stay separate.** Tags are notebook labels the user curates; entities are a
   fact-level index. They are not merged into one vocabulary. (Considered and rejected:
   one namespace with two provenances.)

## 1. The model

An entity is a **normalized name**, the live facts that mention it, and the objects those
facts came from. Nothing is stored as truth: the projection is rebuildable from
`memory.edn` alone, exactly as `touched` is rebuildable from `events`.

**Normalization** is lowercase + trimmed + internal whitespace collapsed. Nothing cleverer.
It must be *stable*: changing it later re-identifies every entity and orphans every alias,
so the rule is written down here and changed only with a migration.

**Aliases** are the one thing that cannot be derived, so they are recorded — appended to
the memory log as their own record kind, agent-proposed and user-confirmed, reversible.
They reuse the audit shape that `merge`/`unmerge` already established: the proposal is
visible, the reversal is a gesture, and nothing is applied silently.

Facts are **not** rewritten to point at entity ids. They keep their `:entities` strings.
This is the property that makes materialization safe (§3).

## 2. Promotion

A name is *known* from its first extraction. It becomes an *entity* — something you can
open — when **two or more live facts mention it**, or when you open it explicitly (from a
chip or from LEAP). On the corpus above that is 104 names → ~36 entities.

Three traps this creates, all of which the implementation must handle:

- **Merging changes the count.** `absorb` unions the absorbed fact's `:entities` onto the
  survivor, so two facts naming `asml` become one fact naming `asml`. The count is over
  *live facts*, so a merge can push a name back below the threshold. That is correct
  behaviour — the evidence really did collapse into one fact — but it means an entity can
  stop being a place. Say so in the UI rather than making the page vanish.
- **Tombstones don't count.** The projection reads live facts only (`all-facts` already
  removes tombstoned ids).
- **A materialized entity never demotes.** Once it is an object in the world it stays one,
  regardless of fact count. It is yours, not the evidence's.

## 3. Where you meet it

Three surfaces, no new gesture to learn:

- **Fact chips in the memory pane.** Each fact renders its entities as clickable chips —
  the smallest possible fix for "a fact with no context", and it needs no rewriting of what
  was recorded.
- **LEAP gains a subject group.** Typing a subject takes you to its place, alongside
  notebooks and objects. Entity names can collide with object titles, so it is its own
  result group, not a merged list.
- **The entity page.** Title, live facts ranked by the ordinary recall score, the notebooks
  and documents those facts came from, and one action: *New notebook about this*.

## 4. Materialization

The page is computed until you act on it. Write a note on it, add it to a notebook, or make
a notebook about it, and a substrate object `:kind :entity` is created carrying the
normalized name; from then on the page is that object's page — moldable, undoable, a
citizen like any other.

**Facts reference the identity, never the object id.** So `undo!` of the materialization
returns the entity to being a projection and touches no fact. This is the whole reason for
choosing a derived model, and it is the invariant to protect in review: the moment a fact
stores an object id, undo can dangle it.

## 5. Recall

Today:

```
candidates = facts whose :source is in lineage-sources(space)
```

Proposed:

```
candidates  = lineage facts ∪ facts sharing an entity with the subject set
subject set = entities detected in the prompt
            ∪ entities of facts already in this lineage
```

**"Entities detected in the prompt"** means: normalize the prompt, then match it against the
known entity names (every extracted name, not only promoted ones), longest match first so
`wrath of the righteous` wins over `wrath`. No new extractor and no model call — the name
list is already in hand, and a second extraction path would be a second thing to be wrong.

`k` stays 6 and the ranking is unchanged, so a cross-notebook fact must **out-score** a
local one to appear. In a brand-new notebook the subject set comes almost entirely from the
prompt — which is exactly the reported case: ask about the game anywhere, and the game's
facts come back.

**Inverse-frequency weighting on entity overlap.** `relevance` currently divides the
intersection by the number of the fact's entities, treating every name as equally
identifying. `united states` (6 facts, three unrelated subjects) would then drag semiconductor
facts into an astronomy notebook. Weight each name by the inverse of how many live facts
carry it, so ubiquitous names stop acting as anchors and rare ones dominate. This is the
difference between the union helping and the union leaking.

## 6. A fact that stands alone

Two halves, deliberately different:

- **New facts:** `distill-facts` gains one instruction — the sentence must name its own
  subject, because it will be read with no notebook around it. The distiller already
  receives the prompt as context, so it knows what the subject is.
- **The 80 existing facts are not rewritten.** The pane (chips) and the injected
  `REMEMBERED` block carry entity names *beside* the recorded text. Memory is revisable, but
  silently rewriting what was recorded is a different act from reinforcing it. An optional,
  auditable backfill that rewrites vague facts is possible later; the default is no.

## 7. Non-goals

- **Not a knowledge graph.** No relations between entities, no types, no inference, no
  ontology. An entity is a subject facts hang on.
- **Tags are untouched.** No migration, no colour changes, no strip changes.
- **No entity extraction pass over the substrate.** Entities come from facts, not from
  scanning every document — that would be a second extractor with a second failure mode.

## 8. Testing

House rule applies: reviewers measure, implementers prove their tests bite.

- **Unit:** normalization stability; promotion at the threshold, including the
  merge-collapses-the-count case and the tombstone case; alias apply and reverse.
- **Recall:** union behaviour pinned with real-shaped fixtures — a fact reachable only via
  the subject set enters the top-6, and a fact with no subject overlap does not. Each test
  must be shown failing when the union is removed, and separately when the
  inverse-frequency weighting is removed.
- **Browser:** entity chips render and navigate; the LEAP subject group appears and is
  distinct from object results; the entity page lists facts and their sources; materialization
  creates an object and `undo` returns it to a projection **without touching a fact**.
- **Measured, on a copy of the live corpus, never the file:** for a set of real prompts,
  which facts newly enter the top-6 and which are displaced. This change is the one most
  likely to degrade prompt quality silently, so it ships with a before/after table, not an
  assurance.

## 9. Open questions, deliberately deferred

- Whether an entity should carry a **type** (game, company, place, person). It would improve
  rendering and disambiguation and costs a classification step that can be wrong. Not needed
  for any of the three failures above.
- Whether **`:entities` should also be extracted for facts that predate this design** by
  re-running the distiller over their source documents. Today's 80 facts already carry
  entities, so nothing forces the question.
- Whether the entity page should offer **"forget everything about this"** — a bulk
  tombstone. Powerful and easy to regret; leave it out until asked for.

**Prior art to read before planning:** <https://activegraph.ai/> — flagged 2026-08-08. This
design deliberately stops short of a knowledge graph (§7); if that product's model is a
better fit for "the user's world", the decision to stop short is the one to revisit, not the
mechanics above.
