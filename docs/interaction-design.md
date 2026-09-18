# loci interaction design guide

This guide preserves the interaction language already present in loci. Use it when you
design a shell change, a mockup, or a UI review. Start with the served shell and current
behaviour. Use the product requirements for intended behaviour. Use the v3 mockup as a
study only.

## Authority and status

The source of visual truth is [`resources/public/index.html`](../resources/public/index.html).
It contains the production CSS and JavaScript. The server supplies content; the shell
lays it out. The table grid is the ClojureScript seam in
[`src-cljs/loci/grid.cljs`](../src-cljs/loci/grid.cljs).

The [information architecture note](notes/2026-09-02-loci-information-architecture.md)
records current code boundaries. The [PRD](product-requirements.md)
records proposed direction. The [walkthrough](walkthrough.md) is a useful demo aid but
can contain stale claims. The root [`index.html`](../index.html) is an old focus
prototype; read its opening comment for the original one-surface intent only. The [v3
mockup](mockups/research-flow-v3.html) is a study, not production authority.

This guide uses three labels:

- **Current** means the served shell or source implements it now.
- **Direction** means the product requirements ask for it, but the shell may not support it.
- **Study** means the v3 mockup illustrates an option. It is not a requirement.

Do not turn a study shortcut, a current gap, or a fixture detail into a product rule.

## The central surface

Design one focused **Space** on a modeless work surface. Temporary dialogs and contextual
controls remain valid; do not turn them into permanent modes. A Space is the place for related work;
it holds purpose, prose, references to reusable Items, Actions, and relations. The current
code calls a Space a notebook and stores it as `:space`. Use **Space** in new product copy;
keep **notebook** when describing current code or migration.

The same rule applies to proposed **Item** versus current **object**, and proposed
**Facts** page versus current **Memory** page. Use current names when a narrow shell change
needs a code or route label; do not trigger a broad rename from a design document.

Overview gives the person a map of Spaces, tags, relation lines, status, and counts. Focus
gives one Space the reading and working surface. Delegation lets other work continue without
making the focused Space a tabbed application. This is the original shell intent, not a
claim that every current ambient signal disappears in focus: `.ambient`, toasts, and some
status controls remain visible in the served shell.

Current navigation is browser state in `mode`, `focusIdx`, and `openId`; `layout()` places
panels, and `renderNotebook()` draws the focused Space. Object detail replaces the Space
body. `openObject()` currently chooses the first notebook that references an Item, so this
is not ownership or a reliable return target. Direction calls for stable addresses and clear
return targets. Do not add a permanent sidebar, inspector, or wizard as a default layout.

## LEAP is the general entry function

LEAP is one general entry function for search, discovery, instructions, creation, filters,
and product actions. Contextual Space and Table controls remain useful alongside it. The
production `#cmd` bar and `renderLeap()` show grouped results; `act()` performs the selected
verb. Keep discovery and action together. Do not invent a search mode followed by a separate
action mode.

An entry must state its effect. Use familiar verbs such as **Ask**, **Research**, **Suggest**,
**Flow**, **Draft**, **Data**, **Make**, and view verbs. A result should show its kind,
title or excerpt, source Space or Item when available, and current state. Viewer verbs may
be listed with search results, but they need a target before they claim to perform a change.

Prompts are user-entered work instructions. Keep the prompt near its resulting Action or
output so the person can see what was asked, what ran, and what resulted. A transient toast
is useful feedback; it is not a durable outcome. Direction requires a durable Action for
each started execution and a reachable result. Do not promise that every current job lands
directly below its initiating cell.

## Space outline and molded content

The Space body is an outline: prose cells and reference cells in order. A reference points
to an Item id; it does not copy the Item. The same Table, Document, or Report can appear in
several Spaces. Current `renderNotebook()` shows the intent, relationship line, action bar,
cells, and connected rail. The `.nbrail` appears when the current layout has more than one
cell; it is a navigation aid, not a second workspace.

The server chooses and computes the mold. It returns plain data and alternatives; the shell
uses `renderMold()` to draw them. Do not move molding into the frontend. Keep the useful
current controls: a select or dropdown is acceptable when it says which View or effect is
active. Preserve the person’s current place while a View changes.

Separate these effects in labels and review:

| Effect | Meaning | Storage consequence |
| --- | --- | --- |
| View or mold | Present the same Item as rows, cards, chart, pivot, report, or text. | Source data stays the same. |
| Transformation | Filter, group, aggregate, or otherwise compute new data. | Creates new data; the result may use a new Item/reference or same-cell placement. |
| Placement | Put an existing Item in a Space or cell. | Adds a reference; it does not copy data. |

The same cell may show a transformation result when that is clearer. Same-cell display
does not overwrite old table storage or Record history. The person can keep the original
Table in another Space and choose how a changed shared Item affects each reference.

The current function palette is `openFnPalette()` and `.fnpal`. It offers Preview and Apply
near the data, then applies a function as a reversible new table. `startDo()` in the served
shell distinguishes “Molded a view”, “Built a visualization”, and “Computed a new table”.
Keep that distinction. Functions written by the person or agent must be visible and reusable;
automatic saving after every interaction is not required.

## Context and approval

Context means content visible or reachable in loci. It includes Space lineage, referenced
Items, the prompt, recalled Facts, tool results when they exist, expected outputs, service,
destination, and state changes. It must not shrink to the current viewport. The person
should be able to open the context used by an Action.

Approval is separate from context. Ask before an uncovered service or data use. Reuse an
existing approval for covered work. Do not ask for every ordinary input, output, URL, or
tool result inside approved research. Keep service identity and data scope visible or
reachable. Configuration does not grant permission by itself.

## Provenance and durable outcomes

Every durable result should answer five questions close to the work:

1. Who or what acted: person, agent, system, or migration.
2. What action ran and which Space started it.
3. Which exact inputs and captured context were used.
4. Which version or Record point produced the result.
5. What result exists now, and where it can be opened.

Use plain labels and links. A graph is optional; the explanation is required. Let a person
move from result to Action to exact input versions, then reach history from the result and
return with the selected result intact. Show when a referenced Item changed and offer the
intended update, keep, or configured choice. An old Report keeps its old captured inputs.
State limits for unavailable, reclaimed, or live external bytes. Current visible relations
show direct `:from`, shared, spawned, merged, and derived links. They do not yet show every
sandbox `:reads` dependency. Direction asks for complete reachable provenance; mark that
gap rather than implying it is current.

**Record** is the reversible, solid, addressable event history. **Recall** is the separate,
secondary, revisable store of agent-written Facts. Mono supports ids and metadata; it does
not label all Record content. Undo and past Record views do not change Recall. Current Record
output and automatic Recall distillation are not atomic. A completed Record result can exist
while Recall distillation finishes later or fails. Action states such as pending, failed, or
partial output are separate concerns.

Direction asks the interface to show pending, failed, partial output, and Stop states clearly.
Stop ends running work; Undo reverses a produced Item and its placement after it exists. Undo
does not Stop a running Action. A produced Table and Report are separate undo units. Preserve
the entered prompt, return path, focus, and unfinished input while temporary controls appear.
Facts removed from Recall remain missing rather than becoming a hidden historical copy. Do
not claim these direction rules are fully implemented.

## Visual language

Reuse the production tokens in `:root` of the served shell:

| Role | Token/value |
| --- | --- |
| Paper | `--paper: #ECEEEA` |
| Panel | `--panel: #F7F8F5` |
| White surface | `--white: #fff` |
| Ink | `--ink: #1B211E` |
| Secondary ink | `--ink-2: #3f4a44` |
| Muted/faint | `--muted: #6d7872`, `--faint: #97a09a` |
| Lines | `--line: #d6dad3`, `--line-2: #e6e9e3` |
| Record grey | `--record: #c8cdc5` |
| Accent | `--accent: #2f6f5b` |
| Accent line/soft | `--accent-line: #73a797`, `--accent-soft: rgba(47,111,91,.09)` |
| Attention | `--attn: #a9632f`, `--attn-soft: rgba(169,99,47,.10)` |

Use IBM Plex Sans for interface text, IBM Plex Mono for labels, keys, ids, and data, and
IBM Plex Serif for Space title and intent. Keep the production type scale: body `12.5px`
with `1.5` line height, Space title `25px/1.18`, italic intent `14px`, prose `13.5px/1.65`,
and ids/metadata in `9.5–11px` Mono. The served shell loads these fonts from Google Fonts,
but font delivery is an implementation detail, not a network requirement.

Keep these anchors unless a clear layout need requires change: top bar `58px`, page padding
`22px`, LEAP max width `680px`, LEAP input padding `13px 16px`, Space panel radius `16px`,
object and mold surfaces around `10–12px`, `.cellmold` radius `4px`, `.mkcell` radius `6px`,
and compact controls around `7–8px`. The body paper grid is `28px`. The `.nbrail` is `138px`
inside the scrolling `.p-body`. Focused layout uses `Math.min(940, vw - 48)`, starts at
`top: 124px`, and uses panel height `vh - 124 - 28`; `.p-head` stays fixed within the panel
and `.p-body` scrolls. Keep that header and body hierarchy.
Use the header registration ticks in `.p-head`; they frame the instrument without boxing it.
Use solid borders for ordinary structure. Dashed borders mark a specific state or tool,
such as `.fnpal`, and do not mean Recall everywhere. Clay signals attention or needs-you;
teal signals action or active state; grey records structure and inactive state.

Keep the hierarchy of surfaces: paper behind panels, a panel for a Space, white surfaces
for Items and molds, and a modal only for work that needs focus. Recall is secondary and
revisable. Dashed is a state or tool treatment, not a truth label.

Readability is a design requirement. Preserve visible focus, keyboard access, contrast,
clear labels, sticky table headers, readable column names, and reduced-motion behaviour.
State only what the current code proves; these are review criteria where implementation is
not yet complete.

## What to avoid

Do not add product scope for document import, source administration, export, service setup
UI, non-local access, or human/result judgment. The product requirements defer these areas
or keep service setup in configuration for P0.

Do not codify the CSV fixture schema, one replacement slot, mandatory auto-save for every
function, a clay border on every Report, a permanent sidebar or inspector, or a wizard flow.
Do not use a dropdown that hides whether the person chose a View or a transformation. Do
not call a current sample, missing provenance, or process-local job state durable history.

## Design examples

For a Table cell, “Rows”, “Pivot”, and “Chart” are Views. “Filter to open cases” can be a
View when the source remains unchanged. “Compute monthly totals” creates a new Table and
shows its source and captured inputs. The person may place that result in the same visible
cell while the original Table remains addressable.

For a Research prompt, keep the entered question, Space, recalled Facts, exact source
inputs, service, Action status, and result reachable together. If the agent proposes a
delegated Space, show the relation and status in overview; keep the focused Space usable.

## Review checks

- Does the design start from the served shell and preserve its visual grammar?
- Can one focused Space support reading, editing, and agent work without tabs?
- Does LEAP search, discover, and act through one entry function?
- Does every verb state its target and effect?
- Can a person tell View, transformation, and placement apart?
- Do cells reference reusable Items without copying them?
- Are prompts and durable outcomes close to the work?
- Can the person reach the full Action context beyond the current viewport?
- Is approval separate from context admission?
- Can the person identify actor, action, exact inputs, version, and result?
- Can the person return from provenance history to the selected result without losing focus?
- Does the design preserve Record versus Recall and state current gaps?
- Are production tokens, IBM Plex roles, focus states, and table readability preserved?
- Does the proposal avoid deferred scope and study-only shortcuts?
