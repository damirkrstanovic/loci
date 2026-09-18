# 01 — Core research

Status: **First draft for product and interaction review.**

This specification defines one core journey from a question to a durable result.
It defines product behavior, visible states, and acceptance scenarios.
It does not define an API, a storage structure, or an implementation plan.

## 1. Authority and labels

The [specification index](README.md) shows the sequence and honest PRD coverage.
The [PRD](../product-requirements.md) supplies P0 scope and reviewed product decisions.
The PRD still contains draft requirements that need product review.
This specification does not mark the entire PRD as approved.

The [interaction guide](../interaction-design.md) supplies the current visual and interaction language.
The [served shell](../../resources/public/index.html) supplies the current foundation.
The [v3 mockup](../mockups/research-flow-v3.html) supplies an interaction study only.
Its fixed schema, automatic function save, and single replacement slot are not requirements.

This specification uses these labels:

- **Confirmed requirement** records a user decision or an applied PRD review decision.
- **PRD requirement** records proposed behavior inherited from the draft PRD.
- **Proposed interaction** gives a concrete choice for review.
- **Open decision** gives a bounded choice, recommendation, and impact.
- **Current foundation** describes the served shell now.

## 2. Outcome and boundary

**Confirmed requirement.** The product supports this journey without forcing a wizard or fixed answer policy:

1. Start with a question in a focused Space.
2. Supply table rows that become usable Table data.
3. Select a View without changing the source Table.
4. Transform the full Table into new data.
5. Save and reuse a function when reuse helps.
6. Ask an agent about the exact transformed Table.
7. Receive a Report in the same Space.
8. Inspect the Action, exact inputs, versions, Facts, and origin.
9. Continue work after input data changes.
10. Undo one produced Item without removing the whole Action.

This specification includes the dependency behavior needed for this journey.
It includes Action states, approval, Stop, failure, retry, restart, and missing provenance.

This specification does not define these areas:

- source administration;
- document import;
- content export;
- model setup controls;
- a global function lifecycle;
- fine-grained column lineage;
- human or result judgment controls;
- a new backend architecture.

These exclusions do not remove other P0 domains from the product.
The specification index keeps those domains queued.

## 3. Established shell behavior

**Current foundation.** The shell has one modeless, focused Space surface.
The current code calls it a notebook.
New product copy uses **Space** as a planned name.
This does not require a blanket code rename.

**Current foundation.** The Space header contains a title and intent.
The body contains ordered prose cells and Item reference cells.
An outline rail helps the person move among cells.

**Current foundation.** LEAP groups discovery and action entries in one result list.
It supports keyboard selection and keeps the selected row visible.

**Current foundation.** Space controls include Ask, Research, and Data entry points.
Table controls include View selection, Make, and a function palette.

**Current foundation.** Data opens a temporary panel.
It offers **From a file** and **Paste**.
Its Paste tab accepts a table name and CSV text.
A successful import opens the created Table detail.

**Current foundation.** The function palette appears beside the Table cell.
It shows a before and after preview of the first eight rows.
It states that Apply creates a new Table.

**Current foundation.** Make asks the person what they want from the selected Table.
The shell labels the result as a View, visualization, or computed Table.

**Current foundation.** Space Ask uses the Space as its target.
It returns an answer in a modal.
A durable Table-scoped Ask and linked Report are proposed behavior.

**Confirmed requirement.** Existing keyboard gestures stay unchanged.
Temporary controls preserve modeless focus and a clear return target.

## 4. Example used by this specification

The example makes arithmetic and version behavior testable.
It does not define a required schema or supported format matrix.

The person creates a Space named **Weekly product usage**.
Its purpose is **Find which feature needs a discovery interview.**
The person adds this prose question:

> Which part of product use needs a closer discovery conversation?

The person supplies `weekly-product-usage.csv` with 12 rows:

| customer | feature | weekly_users | seats |
| --- | --- | ---: | ---: |
| Atlas | Search | 32 | 40 |
| Atlas | Reports | 27 | 40 |
| Atlas | Automations | 6 | 40 |
| Birch | Search | 21 | 30 |
| Birch | Reports | 16 | 30 |
| Birch | Automations | 3 | 30 |
| Cove | Search | 14 | 20 |
| Cove | Reports | 9 | 20 |
| Cove | Automations | 1 | 20 |
| Delta | Search | 6 | 10 |
| Delta | Reports | 4 | 10 |
| Delta | Automations | 0 | 10 |

The fixture calls the resulting Item **usage-table-A**.
Its Table title is **Weekly product usage**.
Its first stored version is **usage-A-v1**.
These labels make the scenarios precise and do not define an id format.
Each feature has 100 available seats.

The person requests this transformation:

> Group by feature. Sum weekly users and seats. Calculate weekly users divided by seats. Name the result adoption.

The person selects **Save as function**.
The function name is **Calculate feature adoption**.

The result is the Table **Feature adoption**.
Its Item alias is **adoption-table-B**, and its version alias is **adoption-B-v1**:

| feature | weekly_users | seats | adoption |
| --- | ---: | ---: | ---: |
| Search | 73 | 100 | 73% |
| Reports | 56 | 100 | 56% |
| Automations | 10 | 100 | 10% |

The person then asks:

> Which feature needs a discovery interview first? Use Feature adoption. State the evidence and one limit.

The Report Item is **recommendation-report-C** at **recommendation-C-v1**.
Its Report title is **Discovery recommendation**.
It says that Automations has the lowest adoption.
It cites 10 weekly users across 100 seats, or 10%.
It states that usage shows the gap but does not explain its cause.

Later, the person explicitly changes usage-table-A.
That change creates **usage-A-v2** with three extra Echo rows:

| customer | feature | weekly_users | seats |
| --- | --- | ---: | ---: |
| Echo | Search | 14 | 20 |
| Echo | Reports | 7 | 20 |
| Echo | Automations | 2 | 20 |

Usage-A-v2 has 15 rows.
Its exact totals are Search 87 of 120, Reports 63 of 120, and Automations 12 of 120.
Their adoption values are 72.5%, 52.5%, and 10%.

The old Report still uses adoption-B-v1.
Its origin still reaches usage-A-v1.

Ordinary Data input creates a different Table Item.
It does not turn a new file into usage-A-v2.
For example, a next-week import can create **usage-table-D** at **usage-D-v1**.
Usage-D-v1 contains the same 15 numeric rows shown for usage-A-v2.
The person can reuse Calculate feature adoption on that new Item.

## 5. Surface and control placement

**PRD requirement.** The journey stays inside one focused Space.
The person can keep working while an Action runs.

**Proposed interaction.** Use the existing Space header for the title and purpose.
Place the prose question as an ordinary cell at the start of the outline.

**Proposed interaction.** Keep LEAP in its current top entry position.
Keep contextual controls in the Space and Table headers.
Do not add a permanent sidebar, a chat surface, or a wizard.

| Task | Entry | Target shown before start | Prompt or input | Primary control | Result placement |
| --- | --- | --- | --- | --- | --- |
| Add table data | Space **Data** control or LEAP Data verb | Current Space | Table title and supplied rows | **Add Table** | New Table reference after the active cell |
| Change a View | Table **View** control | That reference | View choice | Selected View | Same reference and same cell |
| Transform data | Table **Make** control or LEAP Make verb | Exact Table Item and version | Free text request and placement choice | **Run** | Same cell or a new cell below |
| Apply a function | Table **ƒ** control or LEAP function result | Exact Table Item and version | Function parameters | **Apply** | New Table in the selected placement |
| Ask about a Table | Table **Ask** control or LEAP Ask verb | Exact Table Item and version | Free text question | **Ask** | New Report cell below the target by default |
| Inspect origin | Result **Origin** control | Selected Item or Action | None | Open relation | Temporary detail surface with return |

**Proposed interaction.** If LEAP starts a verb in a focused Space, show the resolved target in the composer.
The person can change the target before start.

**Proposed interaction.** If LEAP starts outside a Space, require a destination before the Action starts.
This does not require a separate wizard.

**Proposed interaction.** Cancel closes the temporary composer and restores its opener.
Cancel before start creates no Action and no output.

**Proposed interaction.** Keep unfinished text when a temporary detail surface opens and closes.
Keep the Space scroll position and active cell.

## 6. Space question and purpose

**PRD requirement.** A Space has a required title and an optional purpose.
Purpose changes do not require an Action.

**Proposed interaction.** Show the purpose directly below the title.
Use the current intent line style.

**Proposed interaction.** If purpose is absent, show no blank placeholder during reading.
Offer **Add purpose** through the contextual Space controls.

**PRD requirement.** If purpose exists, include it in Action context.
If purpose is absent, use the Space title and current Space content.
State this fallback in the reachable Action context.

The prose question remains Space content.
It is not copied into each result Item.
The Action records that it used the cell and its exact Record version.

## 7. Table input

**Confirmed requirement.** User-supplied rows become usable Table data.
An included example Table does not satisfy this requirement.
A basic table input path does not require a model.

**Proposed interaction.** Keep **Data** in the Space action bar and LEAP.
Open a temporary panel inside the Space body.
Keep the current file and Paste choices as foundation behavior.

**Proposed interaction.** For Paste, ask for a Table title and table text.
Use CSV in this example because the shell supports it now.
Do not present CSV as the P0 format contract.

**Proposed interaction.** Disable **Add Table** until the title and at least one valid row exist.
Show a field-level reason when input is empty or cannot form a table.
Validate the input when the person changes the title or table text.

**Proposed interaction.** While loci reads a local file, show **Reading file** and its name.
Keep Cancel available before Table creation starts.
Disable Add Table until reading and validation finish.

**Proposed interaction.** Before creation, show detected columns, row count, and a bounded preview.
Label the preview with its exact row bound.
Do not call the preview the complete Table.

**PRD requirement.** After creation, open the Table Item.
Show its title, Item type, current version, total row count, and available rows.
The person can page, filter, sort, aggregate, and analyze the supplied rows.

**Proposed interaction.** Place a reference to the new Table after the active cell.
If no cell is active, place it at the end of the Space outline.

**Proposed interaction.** While loci creates the Table, show **Adding Table** and disable another submission.
Keep the person's title and supplied text until creation succeeds.
Once creation starts, hide Cancel until creation succeeds or fails.

**Proposed interaction.** A read, parse, or creation failure keeps the title and supplied text.
Show the reason in the same panel.
Create no Table, reference, or Action.

## 8. Item references and Views

**PRD requirement.** A Space cell references an Item.
It does not copy the Item data.
The same Item can appear in several Spaces.

**PRD requirement.** A View changes presentation only.
It does not change the stored Table or create new data.

| Effect | Visible statement | Data effect | Record effect |
| --- | --- | --- | --- |
| View | “View: Rows” or the selected View name | Source data stays unchanged | Record the reference View change |
| Transformation | “Created Table from Weekly product usage · usage-A-v1” | Creates new Table data | Record output creation and placement |
| Placement | “Added existing Table to this Space” | No data copy | Record the new reference |

**Proposed interaction.** Keep View selection in the Table cell header.
Show the active View name without opening the control.

**Confirmed requirement.** Each reference keeps its own View.
Changing one reference does not change another reference.

Example: Space A can show Weekly product usage as Rows.
Space B can show the same Item as a graph.
Both references retain the same Item id.

**PRD requirement.** An aggregate View must use an exact full-table query before it claims exactness.
If it uses a sample, label it **Incomplete** and show the bound.

## 9. Transformation and function behavior

**Confirmed requirement.** A transformation creates new data.
It never overwrites the old Table storage or Record history.

**Proposed interaction.** Open the transformation composer below the selected Table header.
Show these fields:

1. **From:** Table title, Item id, and selected version.
2. **Request:** the person's exact text.
3. **Show result:** **New cell below** or **In this cell**.
4. **Save as function:** a named choice.
5. **Expected output:** Table.
6. **Run** and **Cancel** controls.

**Proposed interaction.** Default result placement to **New cell below**.
This keeps the source and result visible together.

If the person chooses **In this cell**, replace the reference shown in that cell.
Keep the original Table reachable through Origin and Record history.
Do not change references in other Spaces.

**Proposed interaction.** Do not save every transformation as a reusable function.
Select **Save as function** when the prompt asks for reuse.
Leave it clear and editable before start.

The example saves **Calculate feature adoption** because the person selects the save choice.
Its description keeps the exact operation and parameters.

**PRD requirement.** A person or agent can create the function.
An agent-created function must be visible to the person after creation.
The person and agent can find and reuse the saved function.

**Proposed interaction.** Show saved functions in two places:

- the contextual **ƒ** palette beside a compatible Table;
- the **Views & functions** group in LEAP.

Each function entry shows its name, description, author, and compatible target reason.
An incompatible function stays visible only when the reason helps the person.

**Proposed interaction.** Applying a saved function shows its parameters and output placement.
Preview remains optional.
Label any preview with its bound, such as **First 8 rows**.

**PRD requirement.** A one-off transformation remains available.
If **Save as function** is clear, the Action creates only the result Table.
The exact request remains in the Action.

## 10. Exact table work

**PRD requirement.** Agent table work uses exact table queries.
Filters, groups, aggregates, sorts, and limits run against the full selected Table version.

Calculate feature adoption uses all 12 rows from usage-A-v1.
It does not calculate from the eight-row preview or the visible page.

The Action context shows this statement:

> Exact query over Weekly product usage · usage-A-v1 · 12 of 12 rows eligible.

If the exact operation cannot complete, fail the Action and keep outputs that already completed.
Do not substitute a capped sample for the selected Table.

An explicit LIMIT is exact for the declared query.
A hidden cap or sampled page is not exact for a full-Table query.
Bounded UI previews remain valid when they state their bound.
An existing aggregate View remains **Incomplete** until it uses a full-Table query.

**Proposed interaction.** Show **Exact** beside a saved Table query result.
Open that label to show the input version, eligible count, filters, groups, sort, and limit.

## 11. Prompted analysis and result placement

**Proposed interaction.** Add **Ask** to the contextual Table controls.
The current Space Ask remains available for a Space-wide question.

Open the Ask composer below the selected Table header.
Show the exact Table title and version above the prompt.
Show **Report in Weekly product usage** as the destination.

**Proposed interaction.** The default expected output is one Report Item.
Place its reference in a new cell below the target Table.
Keep the prompt near the new Action and Report.

If the person moves to another Space, keep that Space focused when the result finishes.
Place the result in its recorded destination and show a reachable completion notice.

The Report can contain prose and references to source Items.
Each result block has an Origin entry point.

**PRD requirement.** One Action may create several Items.
Each produced Item and its visible placement form one Undo unit.
The whole Action is not one atomic Undo unit.

**PRD requirement.** Suggest completes when it produces the requested options or data.
It does not wait for the person to accept a suggestion.
Acceptance is not an execution status.

## 12. Reachable Action context

**PRD requirement.** All context available to the agent is visible or reachable.
The current viewport does not limit context.
The product adds no hidden context.

The Action context includes these parts:

| Part | Required content for this journey |
| --- | --- |
| Goal | Exact submitted prompt |
| Space | Space id, title, purpose, and Record point |
| Lineage | Each admitted related Space and the admission reason |
| Direct inputs | Item id, title, selected version, and relation to the Action |
| Prompt Subjects | Each prompt-named Subject that admitted Facts |
| Facts | Exact Fact ids and text recorded as used |
| Tools | Tool name and version when available |
| Tool results | Each result after it exists, or an explicit unavailable state |
| Model | Model name and version when available |
| Service | Service identity and applicable data scope |
| Network use | Whether the Action sends data outside the local Tenant |
| Expected outputs | Item type, count when known, and destination |
| State changes | Items and references the Action can create |
| Actual outputs | Output id, title, version, availability, and destination |

Credentials never appear in Action context.

**Proposed interaction.** The composer shows a compact target, Space, service, destination, and output summary.
Before Run, each known summary link opens its detail without losing the draft.
Do not fabricate Facts, reads, or tool results before they exist.

After start, the Action detail grows as actual reads, Facts, and tool results become available.
It provides the complete context that the Action used.
The person does not need a separate full context screen before ordinary work.

**PRD requirement.** Space lineage remains eligible even when it is off-screen.
Prompt-named Subjects can admit Facts from another branch.
The Action detail states the reason for each admitted Fact.

## 13. Approval boundary

Approval and context are separate.
Showing content in loci does not itself approve external use.
Service configuration does not grant data-use permission.

**PRD requirement.** The normal LEAP action starts without a compulsory approval step.
Ask only when the service or data use lacks applicable approval.

**Proposed interaction.** If approval is needed, keep the Action status **started**.
Show **Approval needed** as a separate condition.
State the service, data scope, purpose, and next step.

If the person approves, continue the same Action.
If the person declines, stop the same Action.
Record the actor and time for the decision.

Do not ask again for ordinary discovery, URLs, tool results, or outputs inside approved research.

If a later step expands the data, service, network use, or destination, block that step.
Keep the Action started with **Approval needed**.
Ask only for the expanded use.

Approval waiting is not Stop.
Stop is a terminal person or system action.
Keep Stop available while the Action waits for approval.

## 14. Action and output states

**PRD requirement.** Execution status uses exactly these values:

| Status | Meaning | Required presentation |
| --- | --- | --- |
| **started** | Execution began and has no finish state | Start time, current step, Stop, recorded inputs, and existing outputs |
| **completed** | The requested execution ended successfully | Finish time, outputs, and any non-judgment limits |
| **failed** | Execution ended because a step could not complete | Finish time, failed step, error, inputs, and existing outputs |
| **stopped** | A person, system, or restart ended execution | Finish time, actor, reason, inputs, and existing outputs |

Do not use these statuses to judge truth, quality, or usefulness.
Do not add **accepted**, **rejected**, **partial**, or **needs you** as execution statuses.
Partial output is an output condition on a started, failed, or stopped Action.

**Proposed interaction.** Show the Action as an outline entry near its prompt and outputs.
The person can open it without leaving the selected Space.

**PRD requirement.** Show **Stop** while the Action is started.
Stop prevents later steps and later network calls.
It keeps every output already created.

An already-sent remote request can finish after Stop.
Its response cannot start later Action work.
If no Item used that response before Stop, do not add a new output after Stop.

## 15. Undo, retry, and restart

**Confirmed requirement.** Undo remains available while an Action runs.
Undo does not stop that Action.

Creating a result Item and placing its reference is one reversible unit.
Undo always names and reverses the latest reversible Record change.

If no other reversible change intervenes, a Table-then-Report example undoes the Report first.
A later Undo then undoes the Table creation and placement.

For same-cell placement, that Undo restores the original reference, View, and outline position.
It does not leave an empty cell.

If another Space adds a later reference, Undo reverses that reference change first.
It cannot silently destroy an active reference in another Space.
Global Delete remains a separate operation.

The Action remains in Record after an output Undo.
It lists that output as **Unavailable · undone**.

If a running Action needs an undone output, its next step can fail.
The Action failure explains the missing input.
This is not an Undo failure.

**PRD requirement.** Stop keeps outputs already made.
Each kept output remains a reversible Record unit.
Global Undo reaches that unit according to Record order.
This specification does not add selective Undo controls to old outputs.

**PRD requirement.** Retry appears only for a failed Action type with a defined retry handler.
Retry creates a new Action id.
The new Action links to the failed Action.

**Proposed interaction.** Label the control **Retry from failed step** only when that handler exists.
Otherwise show the recovery instruction without an inactive Retry control.

**Proposed interaction.** A stopped Action has no Retry control in this specification.
The person can start a new request from its retained prompt and inputs.
The detailed Action specification must name each Action type with a supported retry handler.

**PRD requirement.** After restart, an unfinished started Action becomes stopped.
Use the exact reason **interrupted by restart**.
Keep its inputs and outputs.
Do not resume it automatically.

## 16. Changed data and independent references

**Confirmed requirement.** Each Space reference keeps its own View and version choice.
A new Table version does not silently rewrite an old result explanation.

When usage-A-v2 exists, mark a usage-A-v1 reference as changed.
Show the current version and the selected version.

**Proposed interaction.** Offer these choices on that reference:

- **Update this reference to usage-A-v2**;
- **Keep usage-A-v1 for this reference**;
- **Use the configured choice**, when an applicable choice exists.

Apply the choice to that reference only.
Do not change a reference in another Space.

If the person updates the source reference, do not update Feature adoption or its Report silently.
Keep each started Action on the exact input version it captured.
A source or reference change during the run does not change that input.

**Proposed interaction.** An ordinary Run or Apply creates a new result Item.
Its placement choice controls only where its reference appears.

**Proposed interaction.** Offer **Update result** through the selected result's rerun entry.
An explicit update uses usage-A-v2 and creates adoption-B-v2 on adoption-table-B.
It records Calculate feature adoption and the selected parameters.
It retains adoption-B-v1 and keeps other references on their selected versions.

The old Report keeps adoption-B-v1 as its captured input.
Opening it shows that a newer input version exists.
It does not label the old Report correct or incorrect.

## 17. Origin, versions, and missing evidence

**PRD requirement.** A result provides one provenance entry point.
The person can move from result to Action to exact input versions.

**Proposed interaction.** Label the entry point **Origin**.
Open it in a temporary detail surface over or within the focused panel.
Do not add a permanent inspector.

The Origin detail lists:

1. the selected result and version;
2. its producing Action and exact prompt;
3. the actor, service, model, and tools;
4. each exact input Item and version;
5. each used function, its definition version when available, and its parameters;
6. each used Fact and admission reason;
7. later uses and output availability;
8. available historical versions;
9. unavailable, reclaimed, or live-byte limits.

Each available relation opens its exact target.
A missing relation keeps its id, label, and reason.

**Proposed interaction.** Preserve the selected result when a related target opens.
**Back to Discovery recommendation** returns to its prior scroll position and focus.

Historical inspection is read-only.
It shows the selected Record point.
It does not rewind or change current Facts.

If a used Fact was removed, show **Missing Fact evidence**.
Keep the recorded Fact id, use, and other provenance.
Do not create a hidden historical Fact copy.

If an output was undone, show **Unavailable · undone**.
Do not offer to open it as available content.

If stored history was reclaimed, state that its bytes cannot be recovered.
Show the metadata and relations that remain.

For a live Source, state that current rows are live external bytes.
Do not present them as the bytes used at an old Record point.
If loci did not capture the old bytes, state that the old bytes are unavailable.

## 18. State tables

### 18.1 Table input state

| State | Visible content | Available controls | Record effect |
| --- | --- | --- | --- |
| Closed | Space outline | Data | None |
| Editing | Retained title and supplied text | Disabled Add Table, Cancel | None |
| Empty | Field reason | Cancel | None |
| File read | File name and Reading file | Cancel; disabled Add Table | None |
| Invalid | Parse or shape reason and retained input | Cancel; Add Table stays disabled | None |
| Ready | Columns, total rows, bounded preview | Add Table, Cancel | None |
| Creation | Adding Table and retained input | Submission controls disabled | No complete change yet |
| Added | Table detail and Space reference | View, Make, Ask, Origin | One reversible creation and placement |
| Failed | Retained title, input, and failure reason | Try again, Cancel | No Table or reference |

### 18.2 Table reference state

| State | Visible content | Choice |
| --- | --- | --- |
| Current | Selected Item version and View | Change View or transform |
| Changed | Selected version and newer version | Update, Keep, or use configured choice |
| Historical | Read-only version and Record point | Return to current |
| Unavailable | Item id and reason | Return to Space or inspect Origin |
| Live | Current external rows and live warning | Inspect Source limit |

### 18.3 Transformation state

| State | Visible content | Available controls |
| --- | --- | --- |
| Closed | Table cell | Make, ƒ |
| Composing | Target, prompt, placement, function choice | Run, Cancel |
| Preview | Explicit row bound and before/after | Apply, change parameters, Cancel |
| Running | Linked started Action | Stop, Origin, global Undo for the latest change |
| Result | New Table, exact label, source version | Ask, View, Make, Origin |
| Error | Failed Action and retained prompt | Defined Retry or recovery instruction |

### 18.4 Action state

| Status | Can Stop | Can use global Undo when the output change is latest | Can retry | Survives restart |
| --- | --- | --- | --- | --- |
| started | Yes | Yes | No | Becomes stopped |
| completed | No | Yes | No | Yes |
| failed | No | Yes | Only with defined handler | Yes |
| stopped | No | Yes | No; start a new request | Yes |

### 18.5 Provenance target state

| State | Presentation | Navigation |
| --- | --- | --- |
| Available | Title, id, type, and version | Open exact target |
| Historical | Version and Record point | Open read-only target |
| Missing Fact | Recorded id, use, and missing reason | No false open control |
| Undone output | Output id and undo reason | Open producing Action |
| Reclaimed bytes | Remaining metadata and byte limit | Open available relations |
| Live bytes | Current external state and history limit | Open current Source detail |

## 19. Acceptance scenarios

These scenarios test observable product behavior.
They do not select routes, schemas, or storage structures.

### Scenario 1 — Create the research Space

**PRD:** FR-010, FR-011, FR-012.

Given the person is in the overview,
when they create **Weekly product usage** with the stated purpose,
then loci opens that focused Space and shows its title and purpose.

### Scenario 2 — Reject a missing title

**PRD:** FR-010.

Given the create control is open,
when the person submits an empty title,
then loci shows title validation and creates no Space.

### Scenario 3 — Cancel table input

**PRD:** FR-060, FR-030.

Given the person entered a title and table text,
when they select Cancel before creation,
then loci restores the Space and creates no Table, reference, Action, or Record change.

### Scenario 4 — Add usable table data

**PRD:** FR-012, FR-020, FR-021, FR-060.

Given the person supplies the 12 example rows,
when they select Add Table,
then loci creates usage-table-A at usage-A-v1 with 12 usable rows and adds one reference after the active cell.

### Scenario 5 — Report invalid and empty input

**PRD:** FR-060.

Given the table input is empty or invalid,
when loci validates the changed input,
then loci states the reason, retains editable input, and disables Add Table.

And loci creates no Table or reference.

### Scenario 6 — Keep Views independent

**PRD:** FR-012, FR-020, FR-022.

Given two Spaces reference the same Weekly product usage Item,
when Space B selects a graph View,
then Space A keeps Rows and both references retain the same Item id.

### Scenario 7 — Distinguish a View from a transformation

**PRD:** FR-022, FR-023, FR-030.

Given usage-table-A at usage-A-v1 is shown as Rows,
when the person selects another View,
then loci changes only that reference presentation and creates no Table data.

### Scenario 8 — Create an exact transformed Table

**PRD:** FR-023, FR-030, FR-073.

Given usage-A-v1 has 12 rows,
when the person runs Calculate feature adoption with the stated parameters,
then loci produces 73%, 56%, and 10% from all 12 rows and labels the saved result Exact.

### Scenario 9 — Place a transformation in the same cell

**PRD:** FR-023, FR-031.

Given the person selects In this cell,
when the transformation completes,
then the cell shows the Table titled Feature adoption and the original Table remains reachable through Origin and Record.

When this creation becomes the latest reversible change and the person selects Undo,
then loci restores the original reference, its View, and its outline position.

### Scenario 10 — Save and reuse a function

**PRD:** FR-023, FR-074.

Given Save as function is selected for Calculate feature adoption,
when the Action completes,
then the function appears in the compatible Table palette and the LEAP Views & functions group.

### Scenario 11 — Use a one-off transformation

**PRD:** FR-023, FR-074.

Given Save as function is clear,
when a person or agent runs a transformation,
then loci creates the result and does not create a saved function.

### Scenario 12 — Create the Report from an exact target

**PRD:** FR-021, FR-070, FR-071, FR-073, FR-080.

Given adoption-B-v1 is selected,
when the person submits the stated Ask prompt,
then loci creates a started Action and later places a linked Report below the Table.

And the completed Report cites Automations at 10 of 100 seats and states the evidence limit.

### Scenario 13 — Show complete reachable context

**PRD:** FR-011, FR-070, FR-071, FR-075, FR-080.

Given the Ask Action has started,
when the person opens Action context,
then every used input, Fact, lineage reason, service, tool result, expected output, destination, and state change is visible or reachable.

And content outside the viewport remains included when admitted.

### Scenario 14 — Ask only at an uncovered boundary

**PRD:** FR-070, FR-075.

Given approval covers the selected service and Table data,
when the Action discovers ordinary URLs and creates results,
then loci continues without another approval.

But when a later step expands the data scope,
then loci blocks that step and keeps the Action started with Approval needed.

And Stop remains a separate terminal control.

### Scenario 15 — Preserve a failed model Action

**PRD:** FR-071, FR-073, FR-075.

Given the configured model service fails after the Action starts,
when loci records the failure,
then the Action status is failed and shows the service, failed capability, error, inputs, and existing outputs.

### Scenario 16 — Limit Retry

**PRD:** FR-071, FR-073.

Given a failed Action type has a defined retry handler,
when the person retries it,
then loci creates a new Action id linked to the failed Action.

Given no handler exists,
then loci shows a recovery instruction and no Retry control.

### Scenario 17 — Stop after a partial output

**PRD:** FR-031, FR-071.

Given an Action created a Table and is still started,
when the person selects Stop,
then its status becomes stopped and the Table remains available.

And loci starts no later step or later network call.

### Scenario 18 — Undo an output while the Action runs

**PRD:** FR-030, FR-031, FR-071.

Given a started Action already created a Table,
and that output creation is the latest reversible Record change,
when the person uses global Undo,
then loci removes the Table and placement as one unit without stopping the Action.

And the Action lists the output as Unavailable due to Undo.

### Scenario 19 — Recover Action state after restart

**PRD:** FR-071.

Given an Action was started without a finish time,
when loci restarts,
then its status becomes stopped with reason **interrupted by restart**.

And its recorded inputs and existing outputs remain.

### Scenario 20 — Choose how one reference handles a changed Item

**PRD:** FR-022, FR-023, FR-080.

Given usage-table-A has usage-A-v2 with 15 rows,
when Space A updates its reference and Space B keeps usage-A-v1,
then each Space shows its selected version and keeps its own View.

And the old Report still opens adoption-B-v1 and usage-A-v1.

### Scenario 21 — Reuse a function on a new input Item

**PRD:** FR-060, FR-074.

Given ordinary Data input creates usage-table-D at usage-D-v1,
and usage-D-v1 contains the 15 numeric fixture rows,
when the person applies Calculate feature adoption with the recorded parameters,
then loci creates a new result with Search 87/120=72.5%, Reports 63/120=52.5%, and Automations 12/120=10%.

And usage-table-D does not become a version of usage-table-A.
And Origin shows the function, available definition version, parameters, usage-table-D, and usage-D-v1.
And the old Report still cites adoption-B-v1 and usage-A-v1.

### Scenario 22 — Show missing Fact evidence

**PRD:** FR-040, FR-041, FR-080.

Given the Report used a Fact that was later removed,
when the person opens Origin,
then the Report remains and shows the missing Fact id and recorded use.

And remaining inputs, Action data, and provenance stay available.

### Scenario 23 — State historical byte limits

**PRD:** FR-031, FR-061, FR-080.

Given an old result refers to reclaimed or uncaptured live bytes,
when the person opens that historical input,
then loci states which bytes are unavailable and shows the metadata that remains.

And it does not show current live rows as old evidence.

### Scenario 24 — Preserve focus and unfinished input

**PRD:** FR-003, FR-070.

Given the person has unfinished text in a composer,
when they open and close reachable context,
then loci restores the text, Space position, selected cell, and opener focus.

### Scenario 25 — Keep Suggest completion separate from choice

**PRD:** FR-071.

Given Suggest produces its requested options,
when the options become available,
then its Action status becomes completed before the person chooses an option.

And loci adds no truth, usefulness, acceptance, or rejection status.

Human and result judgment remains deferred under FR-072.

### Scenario 26 — Keep the captured input during a source change

**PRD:** FR-070, FR-071, FR-073, FR-080.

Given an Action starts with usage-table-A at usage-A-v1,
when usage-A-v2 appears or a Space reference changes during that Action,
then the Action continues to use usage-A-v1 and records that exact input.

### Scenario 27 — Do not steal focus after completion

**PRD:** FR-003, FR-071.

Given an Action starts in Weekly product usage,
when the person moves to another Space before the result completes,
then the result lands in Weekly product usage without changing the focused Space.

And loci provides a reachable completion notice.

### Scenario 28 — Respect a later reference change before output Undo

**PRD:** FR-020, FR-030, FR-031.

Given an Action creates a result and another Space later adds a reference to it,
when the person opens Undo,
then loci names the later reference change as the next reversible Record change.

After that change is undone, the output creation can become the next reversible change.
Undo then restores any replaced same-cell reference and does not destroy another active reference.

## 20. Integrity checks and product review

Deterministic acceptance verifies product integrity.
It checks exact arithmetic, ids, versions, status transitions, links, output availability, and Record effects.

These small examples prove the stated semantic outcomes.
They do not replace the 10-million-row release checks in PRD QR-002.

Prototype review answers different questions:

- Can a person tell which Table and version an Action will use?
- Can a person tell a View from a transformation?
- Does result placement feel predictable?
- Is the prompt close enough to the Action and result?
- Can a person find a saved function again?
- Can a person understand why approval appeared?
- Can a person return from Origin without losing their place?
- Does changed data create a clear choice without needless interruption?

These judgments do not replace integrity checks.
The integrity checks do not prove that the workflow feels useful.

## 21. Open decisions

### OD-01 — Default transformation placement

**Open decision.** Use **New cell below** or **In this cell** by default.

Recommendation: use **New cell below**.
It makes source and result visible together and reduces surprise.
The impact is a longer Space outline during iterative work.

### OD-02 — Default function save choice

**Open decision.** Save only after an explicit request or preselect save for every transformation.

Recommendation: save only when the prompt asks for reuse or the person selects the choice.
This avoids a function list filled with one-off work.
The impact is one explicit choice when reuse matters.

### OD-03 — Changed reference default

**Open decision.** Keep the selected version or update to the latest version by default.

Recommendation: keep the selected version until the person or an applicable configuration chooses update.
This protects historical reasoning across independent Spaces.
The impact is a visible changed-data notice on active references.

### OD-04 — Origin detail form

**Open decision.** Use an in-panel detail surface or a temporary overlay.

Recommendation: use the current panel replacement pattern when it can preserve the return target.
Use a temporary overlay when the person must compare result and origin together.
The impact is one responsive behavior to test at narrow widths.

## 22. Review checklist

- Confirm each proposed interaction or return it with a bounded change.
- Confirm the four open decisions or select another listed behavior.
- Check that each control states its target and effect.
- Check that a View never claims to create data.
- Check that a transformation never hides its source version.
- Check that previews always state their bound.
- Check that saved table answers use exact queries.
- Check that all agent context is visible or reachable.
- Check that approval appears only at uncovered use boundaries.
- Check that Action status uses only started, completed, failed, or stopped.
- Check that Stop, Undo, Retry, and restart remain distinct.
- Check that old results keep their captured versions.
- Check that missing Facts and unavailable bytes remain honest.
- Check that keyboard behavior, focus, and return remain intact.
