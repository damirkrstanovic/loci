# loci — record/recall notebooks: design

Date: 2026-07-22
Status: **the original build shipped** in commits `e4c5bbd`…`d455b74`. Later work moved
record and recall from the EDN stores described below into Datalevin. The body remains the
original build spec.

## Goal

Extend the loci prototype into a complete record/recall system:

- **Record** — the deterministic substrate (event log): the user's domain. Everything the
  user does — edits, imports, notebook authoring — remains a reversible substrate event.
- **Recall** — a real (Clojure-native) AI-memory engine behind the existing `Recall`
  protocol: the agent's domain. The agent writes distilled facts with provenance after
  every agent flow; recall shapes later answers and is visibly cited.

Four demoable flows: (1) a moldable notebook-briefing on world economic data,
(2) live research on the semiconductor supply chain, (3) agent-spawned connected
deep-dive notebooks, (4) one LEAP search across everything — notebooks, objects,
cell prose, docs, and memory.

## Decisions (settled in brainstorming)

| Decision | Choice |
|---|---|
| Memory engine | Clojure-native: entities + keyword + recency + strength; **no embeddings**, no new deps |
| Persistence | Both logs persist to disk under `data/`, replayed on boot |
| Notebook model | **Notebook = space.** A space's value gains ordered `:cells`; prose interleaved with object refs |
| Memory writes | Automatic after agent flows; inspectable; recalled facts visibly cited |
| Store relationship | **Two parallel logs.** Substrate: deterministic, undoable. Memory: revisable, decaying, *not* covered by undo |
| Connectedness UI | Threads rail (per-notebook "connected" panel) **+** inline "also in / derived from" chips on cells |
| Research topic (flow 2) | The semiconductor supply chain; deep-dives e.g. Taiwan/TSMC, export controls |

## 1. Object model — notebook = space

A space **is** a notebook. Its `:value` is:

```clojure
{:intent "Compare the largest economies…"
 :cells  [{:text "Prose cell — markdown-ish plain text."}
          {:ref "tbl:countries" :view "table/bar"}   ; pointer + chosen mold; no view = default mold
          {:ref "report:world"}]
 ;; only on agent-spawned notebooks:
 :spawned-by {:space "space:semis" :prompt "Taiwan & TSMC"}}
```

- A cell is exactly one of `{:text string}` or `{:ref object-id, :view view-id-string?}`.
  Data is never copied into a cell; re-molding a cell is changing its `:view`.
- **Back-compat:** legacy `{:members [id …]}` is normalized on read to
  `{:cells [{:ref id} …]}` by one helper (`cells-of`), used everywhere. All writes write
  `:cells`. Existing server flows that `conj` onto `[:value :members]` are converted to
  cell appends.
- Cell operations — each ONE substrate event (`:assoc` of the full new `:cells` vector),
  hence undoable: add-text, add-ref, edit-text, set-view, move, remove.
- Derived tables keep their existing provenance keys `:from` (source object) and `:via`
  (the `:fn` object).

## 2. Memory layer — new namespace `loci.memory`

Implements `loci.mold/Recall` plus browsing. A fact:

```clojure
{:id "mem-7" :fact "TSMC produces ~90% of leading-edge logic chips."
 :entities ["tsmc" "taiwan"]                 ; lowercase strings, agent-supplied
 :source {:obj "find:semis-1" :space "space:semis"}
 :ts 1784752000000 :strength 1}
```

- **remember:** near-duplicate facts (token-Jaccard of `:fact` ≥ 0.6 against an existing
  fact) **reinforce** — bump `:strength`, refresh `:ts`, keep the newer wording — instead
  of duplicating.
- **recall scoring:** `score = (0.6·kw + 0.4·ent) · decay · boost` where `kw` =
  token-overlap (Jaccard) of query vs fact text, `ent` = overlap of query tokens vs
  `:entities`, `decay = 1/(1 + age-days/30)`, `boost = 1 + 0.25·(strength-1)`.
  Return top-k `{:fact :score :source :ts :strength}`; drop zero scores. Exact weights
  are implementation latitude; the *signals* (keyword, entity, recency, strength) are not.
- **Auto-distill:** after `research!`, `ask!`, and `delegate!` complete, one extra agent
  call (`agent/distill-facts`) extracts 1–5 salient facts as JSON
  `{:facts [{:fact string :entities [string]}]}`; each is remembered with provenance
  (the landed object id + space; for `ask!`, which lands nothing, `:obj` is nil and
  the space alone is the source). Distill failures are swallowed (memory is best-effort;
  the flow's own result still returns).
- **Recall injection:** before running `research!`, `ask!`, `delegate!`, and deep-dives,
  recall top-6 facts for the prompt and prepend a
  `REMEMBERED (from earlier work — cite as ⌾ source-id):` section to the system context.
- **Persistence & undo:** append-only `data/memory.edn`, one fact per line; reinforcement
  appends the updated fact (same `:id`); load is last-wins by `:id`. **Undo never touches
  memory** — undo reverts what happened; the agent doesn't forget what it learned.

## 3. Persistence — `loci.substrate` grows a durable store

- `PersistentStore` implements the existing `Store` protocol: in-memory log atom + a
  file. `commit!` appends one EDN line (`pr-str` of the event) to `data/substrate.edn`;
  `undo!` pops the log and **rewrites** the file (logs are small; correctness over
  cleverness).
- Boot: if `data/substrate.edn` exists and is non-empty → replay it (no re-seed);
  else seed from `loci.content/objects` (each seed committed, hence written to the file).
- Data dir configurable: `LOCI_DATA` env var or `loci.data-dir` system property,
  default `"data"` — the headless demo uses a scratch dir so it never clobbers real data.
- Reset = delete `data/`.
- EDN, not JSON: keywords and nested structures round-trip exactly.

## 4. Connectedness — computed, never stored

Links are derived on request from the substrate:

- **shares** — notebooks A and B both have a cell ref'ing the same object.
- **spawned / spawned-by** — from `:spawned-by` on the notebook.
- **derived** — a table in A has `:from` pointing at an object ref'd in B (or vice versa).

API: `GET /api/links?space=id` →

```clojure
{:connected [{:id "space:semis-1" :title "Taiwan & TSMC"
              :reasons [{:type "spawned"} {:type "shares" :obj "tbl:extract-7"}]}]
 :also-in   {"tbl:extract-7" [{:id "space:semis-2" :title "Export controls"}]}}
```

UI: a **threads rail** ("connected · N", each entry: title + why + click-to-jump) inside
the notebook panel, and quiet **chips** on cells — `↳ also in <notebook>` for shared
objects, `↳ derived from <obj> via <fn>` for derived tables.

## 5. The four demo flows

1. **Moldable briefing (seeded).** `space:world` is re-seeded as an authored notebook:
   intro prose → `tbl:countries` molded as Bar → prose → same table as Pivot (by
   continent) → `report:world`. Demo: re-mold a cell from the mold menu, edit prose,
   add a cell, undo each — all substrate events.
2. **Research (live).** Seeded hub notebook `space:semis` ("Semiconductors — research
   hub", one intent-setting prose cell). Run the research verb ("map the semiconductor
   supply chain: who makes what, where are the chokepoints") → findings note + extracted
   table land as cells; memory distills facts. Uses the existing Tavily key.
3. **Connected deep-dives (live).** New **deep-dive verb** on the hub:
   `agent/propose-subtopics` (grounded in the hub's findings + recalled memory) returns
   2–3 `{:title :intent :query}`; for each, spawn a notebook (`:spawned-by` set), run
   research into it sequentially. Hub's threads rail and cell chips now show the web.
   Memory recalled from flow 2 visibly informs the deep-dives (⌾ citations).
4. **Universal search (LEAP).** `/api/leap` extended to search, in one incremental
   query: notebook titles + intents, object titles, **cell prose**, **doc text content**,
   and **memory facts** — grouped results (`notebook / table / doc / … / memory / viewer`).
   Text/prose/memory hits jump to their notebook or object; memory hits open the memory
   pane. Cap per-group results (e.g. 8) to keep it incremental-fast.

## 6. Server & agent additions

New/changed endpoints (all existing patterns: JSON in/out, `{:error …}` on failure):

| Endpoint | Does |
|---|---|
| `GET /api/notebook?id=` | Hydrated notebook: cells (ref cells molded via chosen `:view`, with mold alternatives + also-in chips), rail links |
| `POST /api/notebook` | `{space op …}` — add-text / add-ref / edit-text / set-view / move / remove; one event each |
| `GET /api/links?space=` | Connected notebooks + also-in map (§4) |
| `GET /api/memory[?q=]` | All facts recent-first, or recall-ranked for `q` |
| `POST /api/deep-dive` | `{space}` → propose subtopics, spawn + research each; returns `{:state :spawned [ids]}` (slow — sequential research; acceptable for prototype) |
| `GET /api/leap?q=` | Extended per §5.4 |

New `loci.agent` fns (same propose-only discipline, JSON-mode):
`distill-facts`, `propose-subtopics`. Existing flows (`research!` etc.) gain recall
injection + post-flow distill.

## 7. Frontend — extend `resources/public/index.html`

Same aesthetic, no frameworks. New: notebook panels render cells (prose editable in
place; ref cells reuse the existing molder + per-cell mold select), add-cell affordance
(prose, or object by id with a datalist of existing objects), threads rail, chips,
a memory pane (modal: facts with provenance + strength, searchable), LEAP result groups
incl. memory. Deep-dive + research buttons on notebook panels with progress toasts.

## 8. Housekeeping

- `.gitignore` += `data/`, `.superpowers/`, `.clerk/`.
- Note: the directory is not currently a git repository; `git init` is recommended so
  specs/plans can be committed (ask the user).

## 9. Verification

Headless first (`clojure -M:demo`, extended; scratch data dir):

1. **Persistence:** commit events → new store instance over same dir → identical state; undo → file shrinks.
2. **Memory:** remember 3 facts + 1 near-duplicate → 3 facts, one reinforced; recall ranking respects keyword/entity/recency; undo leaves memory untouched.
3. **Notebook ops:** members→cells normalization; add/edit/re-mold/remove cells; each undoable.
4. **Links:** shares / spawned / derived reasons computed correctly on a constructed trio of notebooks.
5. **LEAP:** query hits across titles, prose, doc text, memory.

Then a manual browser walkthrough of the four flows in §5 (agent + Tavily required for
flows 2–3; flows 1 and 4 work fully offline).

## Out of scope

Embeddings/Khora, XTDB/Datahike swap, multi-user, JS-applet sandboxing (already flagged
in code), Clerk notebook parity, memory forgetting/GC beyond decay-at-recall.
