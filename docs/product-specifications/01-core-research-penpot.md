# Core research flows in Penpot

This handoff maps the clickable Penpot study to [01 — Core research](01-core-research.md).
It records review coverage. It does not change the accepted specification or the app.

## Open the study

1. Open the Penpot file **Research** (`80880d9c-d3dd-8030-8008-a4ce696b3a3b`).
2. Open the page **Core research flows** (`b1db2319-8ca6-80ba-8008-a4e13de5b9f4`).
3. Select the named flow **Core research — main journey**.
4. Use **View** or **Play**. Start at **Flow · overview** (`b1db2319-8ca6-80ba-8008-a4e7b43bd627`).

The original **Page 1** board remains unchanged. The main journey reaches the focused Space, table input, source table, View, Make, saved function, transformed table, Ask, Report, and Origin. The page also contains branch cards for recovery, provenance, version, approval, and focus behavior.

The final canvas audit found 64 boards, 125 native links, and 22 named flow starts. It found no unreachable boards or empty click targets. Three timed links simulate Make, saved Make, and Ask completion. This is structural audit evidence. It is not a claim that every acceptance scenario passed as a live product interaction.

All 122 click areas use a transparent fill. Three timed links bring the total to 125 interactions. The static link audit did not test hit detection. View-mode clicks remain unverified because no browser is connected.

Visual checks covered the corrected start, Make, adoption result, and the provenance graph. The graph export succeeded and visual inspection passed: five centered nodes fit without overlap or clipping, Item and Action styles remain distinct, and the arrows connect. No View-mode click test was done anywhere.

The completed Space outline is covered. Earlier outlines are not covered by this revision.

## Main journey

The main path uses these boards. Controls with native links are clickable in View and Play.

| Step | Board | Board id | Purpose |
| --- | --- | --- | --- |
| 1 | Flow · overview | `b1db2319-8ca6-80ba-8008-a4e7b43bd627` | Open the Research flow. |
| 2 | Flow · create-space | `b1db2319-8ca6-80ba-8008-a4e7b4691d1b` | Show title, purpose, and question. |
| 3 | Flow · start | `b1db2319-8ca6-80ba-8008-a4e162c846e2` | Focused Space with Data and Suggest. |
| 4 | Flow · data-input | `b1db2319-8ca6-80ba-8008-a4e163a2ddd0` | Fixed CSV example. |
| 5 | Flow · data-preview | `b1db2319-8ca6-80ba-8008-a4e1647dc77c` | Bound 12-row preview. |
| 6 | Flow · table-rows | `b1db2319-8ca6-80ba-8008-a4e536531de8` | `usage-table-A`, `usage-A-v1`, 12 rows. |
| 7 | Flow · view-graph | `b1db2319-8ca6-80ba-8008-a4e165e6fc00` | Graph View with the same Item and version. |
| 8 | Flow · make-composer | `b1db2319-8ca6-80ba-8008-a4e18c857cce` | Exact target, prompt, placement, and save choice. |
| 9 | Flow · make-saved | `b1db2319-8ca6-80ba-8008-a4e18d96124e` | Save **Calculate feature adoption**. |
| 10 | Flow · make-running-saved | `b1db2319-8ca6-80ba-8008-a4e5bfa649b4` | Started Make Action. |
| 11 | Flow · adoption-result-saved | `b1db2319-8ca6-80ba-8008-a4e5bfb90301` | `adoption-table-B`, `adoption-B-v1`, 73%, 56%, 10%. |
| 12 | Flow · ask-composer | `b1db2319-8ca6-80ba-8008-a4e190aa4886` | Ask against exact `adoption-B-v1`. |
| 13 | Flow · ask-running | `b1db2319-8ca6-80ba-8008-a4e5bfcc9688` | Started Report Action and captured context. |
| 14 | Flow · report-result | `b1db2319-8ca6-80ba-8008-a4e1918cd18d` | `recommendation-report-C`, `recommendation-C-v1`. |
| 15 | Flow · origin-detail | `b1db2319-8ca6-80ba-8008-a4e205ba2cb5` | Reachable Action, input, function, version, and Fact detail. |

The shown Report states that Automations has 10 weekly users across 100 seats, or 10%, and that usage shows the gap but does not explain its cause.

The named flow **Research provenance** starts at **Flow · report-result** (`b1db2319-8ca6-80ba-8008-a4e1918cd18d`). Click **Provenance** in the Space header to open the five-node graph. The nodes are `usage-A-v1`, `Calculate feature adoption`, `adoption-B-v1`, `Ask for a recommendation`, and `recommendation-C-v1`. Item nodes and Action nodes use distinct styles. Click a graph node to open its exact Item or Action detail. Click **Back** to return to the previous screen.

The completed Space outline shows four clickable cells: **Question**, **Usage**, **Adoption**, and **Report**. The outline keeps all results. Their boards are:

| Board | Board id | Result |
| --- | --- | --- |
| Flow · research-provenance | `72ded177-e9b7-80fa-8008-a5285b4cdd67` | Five-node provenance graph. |
| Flow · completed-question | `72ded177-e9b7-80fa-8008-a5289fb81379` | Question cell. |
| Flow · completed-source | `72ded177-e9b7-80fa-8008-a528ab712d3b` | Usage cell with `usage-A-v1`, 12 rows, and customer, feature, weekly users, and seats columns. |
| Flow · completed-adoption | `72ded177-e9b7-80fa-8008-a528b8bbf803` | Adoption cell with `adoption-B-v1`, 3 rows, and its 12-row source. |
| Flow · report-result | `b1db2319-8ca6-80ba-8008-a4e1918cd18d` | Report cell. |
| Flow · transformation-detail | `72ded177-e9b7-80fa-8008-a528d377fb59` | Transformation detail opened from the adoption cell’s Origin control. |

The upper-right **↳ Origin** control is standardized across the five existing result boards and the completed adoption board. It is 88 by 30 pixels. The completed adoption control opens **Flow · transformation-detail** (`72ded177-e9b7-80fa-8008-a528d377fb59`). The control label is **↳ Origin**. The composer uses **Ask…**, shows **Target: Feature adoption**, and shows **From: adoption-table-B · adoption-B-v1 · Exact**. The Ask note names **Table: Feature adoption**. The revision adds no user naming feature.

These named branch starts are available from the page when a reviewer needs one behavior in isolation:

| Named flow | Start board id |
| --- | --- |
| Approval and Stop | `b1db2319-8ca6-80ba-8008-a4e20ac40b9a` |
| Captured input | `b1db2319-8ca6-80ba-8008-a4e74a353aa5` |
| Changed versions | `b1db2319-8ca6-80ba-8008-a4e20710e084` |
| Completion without focus change | `b1db2319-8ca6-80ba-8008-a4e74a4b241d` |
| Core research — main journey | `b1db2319-8ca6-80ba-8008-a4e7b43bd627` |
| Empty Table input | `b1db2319-8ca6-80ba-8008-a4e749117035` |
| Failure and Retry | `b1db2319-8ca6-80ba-8008-a4e20d1c1ce2` |
| Failure without Retry | `b1db2319-8ca6-80ba-8008-a4e6eff7ff77` |
| File reading | `b1db2319-8ca6-80ba-8008-a4e74952a7f0` |
| Historical byte limits | `b1db2319-8ca6-80ba-8008-a4e74a1a9f5a` |
| Independent Views | `b1db2319-8ca6-80ba-8008-a4e749ea4c0a` |
| Invalid Table input | `b1db2319-8ca6-80ba-8008-a4e74936decc` |
| Later reference Undo | `b1db2319-8ca6-80ba-8008-a4e749b904c6` |
| Missing Space title | `b1db2319-8ca6-80ba-8008-a4e7b48f2324` |
| Provenance limits | `b1db2319-8ca6-80ba-8008-a4e74a04795e` |
| Research provenance | `b1db2319-8ca6-80ba-8008-a4e1918cd18d` |
| Restart recovery | `b1db2319-8ca6-80ba-8008-a4e6f0218794` |
| Reuse function on new Item | `b1db2319-8ca6-80ba-8008-a4e863360842` |
| Saved function | `b1db2319-8ca6-80ba-8008-a4e18d96124e` |
| Partial output — Stop and Undo | `b1db2319-8ca6-80ba-8008-a4ea5fe442a5` |
| Undo and recovery | `b1db2319-8ca6-80ba-8008-a4ea5fab19c0` |
| Undone output failure | `b1db2319-8ca6-80ba-8008-a4e749a05a41` |

## Coverage of the 28 scenarios

The coverage label has this meaning:

- **Clickable path** means the state is part of a native linked flow.
- **Selectable outcome** means the canvas shows a selectable result or branch card, but does not prove the full product transition.
- **Annotated semantics** means the board records the required behavior for design review.
- **Prototype limit** means the behavior needs a real app, backend, or browser-level test.

| Scenario | Coverage in the study |
| --- | --- |
| 1 Create the research Space | Clickable path: overview → create-space → start. |
| 2 Reject a missing title | Selectable named start `create-space-missing-title`; validation is fixed content. |
| 3 Cancel table input | Clickable path: data-input Cancel returns to start. |
| 4 Add usable table data | Clickable path through data-preview; fixed 12-row result shows `usage-table-A` / `usage-A-v1`. |
| 5 Report invalid and empty input | Selectable outcomes: `data-empty` and `data-invalid`; editable validation is simulated. |
| 6 Keep Views independent | Selectable outcome: `independent-views`; cross-Space persistence is annotated. |
| 7 Distinguish a View from a transformation | Clickable path: Rows and Graph keep the same Item/version; no new data is executed. |
| 8 Create an exact transformed Table | Clickable path through Make to the exact adoption result; execution is simulated. |
| 9 Place a transformation in the same cell | Clickable path through same-cell composer (`b1db2319-8ca6-80ba-8008-a4ea5fab19c0`) to Run and the same-cell result; Undo semantics remain simulated. |
| 10 Save and reuse a function | Clickable saved-Make outcome → Views & functions results (`b1db2319-8ca6-80ba-8008-a4ea604a4cff`) → function detail and return; arbitrary reuse and LEAP search remain fixed. |
| 11 Use a one-off transformation | Clickable unchecked Save path through running to the one-off result; no real function is created. |
| 12 Create the Report from an exact target | Clickable path: Ask composer → running → Report result. |
| 13 Show complete reachable context | Clickable path to `action-context` for Make context; complete Ask context and backend drilldown are prototype limits. |
| 14 Ask only at an uncovered boundary | Selectable outcomes: `approval-needed` → `approval-continued`; approval is simulated. |
| 15 Preserve a failed model Action | Selectable outcome: `failed-retry`; no model call occurs. |
| 16 Limit Retry | Selectable outcomes: `retry-action` and `failed-no-retry`; handler rules are annotations. |
| 17 Stop after a partial output | Clickable named flow **Partial output — Stop and Undo**: partial output (`b1db2319-8ca6-80ba-8008-a4ea5fe442a5`) → Stop → stopped state (`b1db2319-8ca6-80ba-8008-a4ea601b1b21`) with the completed output Table retained. |
| 18 Undo an output while the Action runs | Clickable branch in the same named flow: global Undo leads to the unavailable-output state; the Record rule is shown in fixed content. |
| 19 Recover Action state after restart | Selectable named start `restart-stopped`; restart is simulated. |
| 20 Choose how one reference handles a changed Item | Clickable path to `changed-reference` and selectable `source-v2` / `adoption-v2`; Update and Keep are shown, with per-Space behavior annotated. |
| 21 Reuse a function on a new input Item | Clickable path through `function-reuse-D` and `function-reuse-result`; fixed 15-row fixture. |
| 22 Show missing Fact evidence | Selectable named start `missing-fact-origin`; the missing Fact is explicit, while Origin drilldown is a prototype limit. |
| 23 State historical byte limits | Selectable named start `historical-byte-limits`; unavailable bytes are annotated, while historical source drilldown is a prototype limit. |
| 24 Preserve focus and unfinished input | Clickable Ask → context → Back path to `ask-draft-context`; native focus, typing, and scroll restoration are prototype limits. |
| 25 Keep Suggest completion separate from choice | Clickable path from start Suggest to `suggest-options`; no judgment state is added. |
| 26 Keep the captured input during a source change | Selectable named start `captured-input-change`; captured v1 is shown and the transition is simulated. |
| 27 Do not steal focus after completion | Clickable path to `completion-notice`; destination and notice are shown. |
| 28 Respect a later reference change before output Undo | Clickable path: `later-reference-added` → `later-reference-undone`; ordering is annotated. |

## Prototype limits

The canvas uses fixed fixtures and simulated execution. It does not accept arbitrary text entry, read a real file, run an import, query a Table, call a model, or create durable Record or Recall state. It does not verify native keyboard focus, scrolling, or View-mode clicks. It does not prove model failure, restart recovery, approval enforcement, undo ordering, or backend provenance.

The visible CSV and Paste path is shaped like the product path and includes the supplied values. The table and Report outputs are fixed examples. The branch cards make backend semantics reviewable without claiming that the prototype implements them.

The canvas is a design review artifact. Product acceptance still requires the behavior in [01 — Core research](01-core-research.md) to be implemented and tested in the app.
