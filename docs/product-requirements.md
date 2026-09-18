# loci product requirements

## changelog 
Date: 2026-09-03
Status: **Agent-produced draft for user review. No requirement, recommendation, or release boundary is approved.**

Date: 2026-09-14
Status: **Second review decisions applied for points 1 through 7. P0 uses table input only; source administration, document import, and content export are deferred. P0 uses configuration-file service setup; no Service settings UI is required. D3 remains undecided. Full-document review is deferred at the user's request.**

Date: 2026-09-14
Status: **Third review decisions applied. Data discovery remains in P0 through LEAP. Source administration and document import remain deferred. Table input creates usable Table data. Transformations preserve independent references and let the person choose how changed data affects them. Undo applies to one produced Item at a time. Fact removal shows affected uses and missing provenance.**


## 1. Purpose 

This document proposes the first dependable product release from the current loci prototype.

Loci prototype currently supports all required basic functionality but creating a full product 
out of it that supports the user flows that we want, so it will specify the use cases and other stuff so we can test whether it fulfills the purpose.

## 2. Vision

loci is a  product where a person and an agent work together on data analysis and synthesis in 
order for the user to reach a certain conclusions

The central UX is formed around work by Jef Raskin - The Humane Environment, where you use a single action (key on his custom text editing computer) to do anything - so nothing gets in your way. It is based on LEAP - a single way to get to anything in the environment. 

Loci tries to use these principles but for the age of AI agents based on GenAI. 

Loci supports working on hypothesis, analyzing data in tabular in other format, showing the data in different, moldable formats, creating functions to transform the data, all the while remembering how we came to a certain data point and able to recall anything you've stored.


## 3. Persona and core job

Primary persona of Loci is a knowledge worker - researcher, analyst, or someone who does research and analysis of information as part of their job. This person works with documents, local files, tables, questions, publicly available data and agent help over days or months.

Core job to be done is:

When I investigate a question with documents, data, and an agent, help me make useful results, understand their provenance, and continue or change the work safely later. During my work I may go on a wild goose chase and that's fine - I can abandon a certain approach but it is recorded and I can mark it as failed, go back to it, learn from experience, etc. 
I need to know what is good, what is not, what can be reused later and what is a dead-end.

This reported workflow is the accepted initial product basis for the prototype. The user reports that other people have the same needs. This document uses that report to define the first research journeys. It does not require broader discovery before progress.

Human/result judgment is a known gap to resolve through product use and remains deferred outside P0. A person may find a result complete, incomplete, useful, or a dead end. A dead end remains available as experience for further analysis. A fact may prove incorrect or apply only during a period. These are future design questions, not approved status fields or controls. The vocabulary, validity periods, recall treatment, and responsibility of a program, agent, or person remain open. Execution status remains separate from human/result judgment.

When I compare this to a typical chatgpt chat I get the following:
1. In chat, everything is linear, apart from stuff like projects that is built into chatgpt. The conversation goes long but it is difficult to find anything that was a conclusion, fact or something to hold on to.
There is a possibility of creating documents and that is what I do - Claude has artifacts that can be managed and versioned and in CLI (codex/claude/opencode) I maintain markdown files and use git.
There is no way to determine which document is the outcome, which is the input - you need to note this somewhere (in another document). Markdown dominates - html can be used but it is still very simplistic.
2. sometimes it is difficult to pull conclusions from chats and documents together
3. I am limited to what harness offers in way of showing the data
4. provenance is not always shown, often only when I insist
5. it is difficult to go back to larger conversation - i often need to ask "what did we discuss"
6. linear way of conversation is tiresome for research - i sometimes want to say "ok, here is the current state, let's go into several directions and check X, Y and Z". there is a way to do branching in chats, but the UX is cumbersome
7. when I need to change an assumption, who the heck knows where did this conclusion came from

## 4. Product model and principles

The person can use the same Item in several Spaces. They can choose a View for each use. They can inspect where a result came from, what it used, what it changed, and what the agent tried.

The agent works through Actions in a Space. The person can inspect, start, stop, accept, reject, undo, and recover an Action result.

```text
Tenant (currently single-tenant)
  ├── Spaces - similar notion to notebooks elsewhere
  │     ├── Space-owned prose
  │     └── references to reusable Items
  ├── Items: Documents, Tables, Reports
  ├── Actions and Flows
  ├── Facts and Subjects
  ├── Sources
  └── Record history
```

| Term | Product meaning |
|---|---|
| Tenant | All information for one isolated owner or group. P0 has one implied local Tenant. |
| Space | A place where related work happens. It has a title, optional purpose, prose, Items, Actions, and relations. Similar to a notion of a notebook but named separately to note the fundamental difference. |
| Item | One reusable Document, Table, Report, or later supported type. It has one identity in a Tenant. |
| Action | One started agent execution. It has a stable id, action context, status, inputs, outputs, and provenance. |
| Flow | A group of several Actions. Usually created as agent workflow |
| Record | The reversible history of changes to Spaces, Items, Views, and Actions. Fact changes are outside Record. |
| Fact | A stored statement with Subjects and provenance. |
| Recall | The agent action that selects relevant information for a task. |
| View | A selected presentation of an Item. |
| Source | The origin and refresh rule for connected information. |
| Subject | A named person, place, company, concept, or other topic that connects Facts. |
| provenance | The evidence that explains origin, inputs, actions, outputs, and later uses. |

The current code has one global id-to-object map. A Space holds prose and Item references. One Item can appear in several Spaces without a copy. [Current boundary](notes/2026-09-02-loci-information-architecture.md#L12) [Store state](notes/2026-09-02-loci-information-architecture.md#L40)

The product uses these principles:

1. One Tenant is the P0 boundary.
2. A Space is where related work happens.
3. An Item has one identity in a Tenant.
4. A Space can refer to an Item without copying it.
5. Facts exist independently from Record. The agent recalls Facts for a task.
6. A View is a chosen presentation. The product does not expose mold as a user concept.
7. Record history explains and reverses user-visible change.
8. Provenance must help from an Item, Fact, or Action. It must not force a graph-first workflow.
9. A failed attempt is information. Do not hide it.
10. The product must state when information is live, stale, unavailable, reclaimed, or incomplete.
11. A result in a Space must retain the context of the evidence used when it was produced. A refresh or changed evidence must not silently replace the historical explanation. A refresh does not prove the earlier result correct or incorrect.
12. A View presents data. A transformation creates new data. Same-cell presentation or placement does not remove prior table storage or Record history. A Space may keep the original Table while another Space uses transformed data.

### Interaction principles

LEAP is one entry function. A person enters text and uses a verb to search, find data, instruct an agent, create content, or use a product action.

Discovery and action are not separate modes or required stages. An action can discover data. The purpose is to test these interaction principles in work with agents.

Data has moldable Views. A person can choose a presentation that suits the task. Text can appear directly or through a summary in the chosen form. Tables can use functions and appear as graphs.

The person and the agent can create functions. Functions must be visible somewhere in the product and reusable in the product. A one-off summary or transformation remains possible. Reuse is preferred, not required.

Research shall be seamless. The person works with the content shown in loci and with content reachable through its interface. New research results become content in loci and shall be visible or reachable there. The product shall not require a separate display of assembled agent context before research starts, or display tool results before they exist.

All task context used by the agent shall remain visible or reachable through the interface. Context admission shall preserve Space lineage and prompt-named Subjects. The product shall not hide context or narrow it to the current viewport.

Approval and workspace context are separate concerns. Existing approval may cover private customer data sent to a service when the proposed use is covered by that approval. If the service or data use is not covered, loci shall ask before that use. Ordinary discovery and new results within approved research shall not require repeated approval for every input, output, URL, or tool result. Service identity and applicable data use shall remain visible or reachable. Service configuration alone shall not grant permission to send any data.

A View can apply a function or show a graph to present data. The stored source data stays the same. A transformation applies a function or summary and creates new data. The new result may replace the content shown in the same cell or appear in another cell. Same-cell presentation does not overwrite prior table storage or Record history.

Existing gestures and keyboard behavior remain unchanged.

### Vocabulary migration

| Current name | Product name |
|---|---|
| workspace | Tenant |
| notebook | Space |
| object | Item |
| Memory page | Facts page |
| Recall as a noun | Facts; use *recall* only as a verb |

## 5. Current foundation and product gaps

The following current behavior supports the proposal. It is not complete product behavior.

- A Space stores ordered prose and Item references. References support shared Items, origin, merge, and direct derivation relations. [Space structure](notes/2026-09-02-loci-information-architecture.md#L83) [Relations](notes/2026-09-02-loci-information-architecture.md#L95)
- Record supports event changes, grouped changes, undo, and past views. [Record events](notes/2026-09-02-loci-information-architecture.md#L121) [Undo and past views](notes/2026-09-02-loci-information-architecture.md#L125)
- Tables can use a local stored version or a live Source. Current sources support CSV, JSON, and Parquet. [Table handles](notes/2026-09-02-loci-information-architecture.md#L137) [Live Source limit](notes/2026-09-02-loci-information-architecture.md#L161) [Roadmap](../ROADMAP.md#L21) [Remote sources](../ROADMAP.md#L22)
- Facts persist outside Record. They can decay, merge automatically, become durable, unmerge, or be removed. [Facts](notes/2026-09-02-loci-information-architecture.md#L165) [Fact and Record separation](notes/2026-09-02-loci-information-architecture.md#L129)
- Search has lexical and deeper Record-content paths. [Search paths](notes/2026-09-02-loci-information-architecture.md#L213) [Deep Search limit](notes/2026-09-02-loci-information-architecture.md#L224)
- Views include built-in and Item-backed alternatives. Built aggregate Views remain incomplete: current aggregate Views can use a sample and are not exact table aggregates. [Views](notes/2026-09-02-loci-information-architecture.md#L193) [Item-backed Views](notes/2026-09-02-loci-information-architecture.md#L199) [Aggregate-view limit](superpowers/specs/2026-08-25-paged-table-grid-design.md#L116)
- A Flow is Record-backed, but its running job is not durable. [Flow status](notes/2026-09-02-loci-information-architecture.md#L257)
- Current visible provenance shows only direct `:from` relations. It omits sandbox `:reads`. [Provenance limit](notes/2026-09-02-loci-information-architecture.md#L341)

The prototype lacks stable product addresses, complete first-run behavior, direct control of a Space purpose, full Item lifecycle rules, durable Actions, backup and restore, product-safe local access, and a complete provenance inspector. Service setup remains in the configuration file for P0. Source administration, document import, and content export are future add-ons outside P0. Authored document creation and editing remain in scope.

## 6. Scope

### P0 proposal

P0 makes loci a dependable single-Tenant local product for deliberate work.

- One implied Tenant and a future isolation boundary.
- Stable addresses and clear first-run state.
- Spaces with required titles and optional editable purposes.
- Documents, Tables, and Reports as useful Item types, plus Actions and Flows.
- Record change, undo, and past Record views.
- Facts, Fact selection explanation, Fact controls, and basic Subject pages.
- Table input is necessary to bring table data into loci. P0 shall provide table input for Table Items without requiring a new source administration workflow or an approved input-format matrix.
- One LEAP entry function for Search, data discovery, agent instructions, content creation, and product actions. Source administration remains deferred.
- Actions in a Space, action context, consent for uncovered service or data use, durable Actions, failure, retry, Stop, and exact table answers.
- Clickable provenance for Items, Facts, and Actions.
- Backup and restore.
- Document import and content export remain future add-ons outside P0. D3 delivery remains undecided.

### P1 proposal

P1 adds authenticated non-local access, new remote Source types, richer Views, stronger Subject behavior, more provenance detail, and an explicit Tenant record.

### Not in P0

P0 excludes source administration, document import, content export, non-local access, several visible Tenants, sharing, SQL Sources, media, true Flow branches, run comparison, column provenance, and a full Subject graph.

## 7. Main journeys

### Research journeys

These examples describe work the product must support. They do not approve new connectors, formats, branching features, or other deferred scope.

#### Security research

1. The person starts with a question about who did what.
2. The person brings incident data, logs, audit logs, and data from several systems.
3. The person connects the evidence into an account of the incident.
4. The person reviews the account, does more research when needed, and reuses the result later.

#### Product research

1. The person starts with a product question.
2. The person combines market and competitor data, trends, technology, customer interviews, feedback, and internal team conversations.
3. The person uses the combined evidence to inform a product decision.
4. The person reviews the decision, does more research when needed, and reuses the result later.

#### Released product analysis

1. The person starts with a question about a released product.
2. The person brings usage metrics and inspects features, customers, and patterns through analysis and graphs.
3. The person uses an existing roadmap as input.
4. The person produces a revised or prioritized roadmap when the research supports it.
5. The person reviews the result, does more research when needed, and reuses the result later.

#### Shared research journey

Across these examples, the person moves from a question to evidence, analysis, and a result. The person can review the result, continue with more research, and reuse the work later. A result keeps the historical context of the evidence used to produce it, while live-source history limits remain explicit.

### Feature journeys

#### J1. First run

1. The person starts loci with an empty Tenant.
2. loci offers **Create Space** and **Table input**.
3. loci shows the local data location and configured external-service state.
4. Example content appears only after explicit opt-in.
5. If the store cannot open, loci shows a store failure. It does not show an empty Tenant.

Document import from configured sources is a future add-on. It is not a P0 first-run step. Table input remains a P0 need because it brings table data into loci.

#### J2. Work in a Space

1. The person creates a Space with a title.
2. The person adds, edits, or removes an optional purpose.
3. If purpose is absent, agent context uses the title and current Space content.
4. The person adds prose and refers to Items.
5. The person sees related Spaces and the reason for each relation.

#### J3. Use an Item

1. The person creates or finds a Document, Table, or Report.
2. The person opens the Item and sees its type and identity.
3. The person refers to it from another Space without a copy.
4. The person selects an available View for that reference.
5. If a transformation changes the data, loci shows the change when a referring Space opens. The person can update that reference or result, or keep the prior version.
6. loci lists every Space that refers to the Item.

#### J4. Table input

1. The person or agent brings table data into loci as a Table Item.
2. loci shows the resulting Table Item and its available data.
3. The supplied rows become usable Table data. The person can inspect and analyze them afterward.
4. The person can use the Table Item in a Space and select a View for each reference.
5. Existing source-backed content keeps its source state and provenance.

Source administration is a future add-on. It will define data sources and document repositories, such as CRM systems, databases, document repositories, filesystem folders, and Google Drive. From central navigation through LEAP, a person may later refer to or import content from configured sources. Imported document content will become available to the agent, not only its location or title. Existing Source behavior and provenance handling remain available for existing source-backed content, without requiring source setup in P0.

Future export may produce Markdown, a document format such as DOCX or PDF, or table data in a database. These are examples for later definition. Backup and restore remain separate P0 capabilities.

#### J5. Start an Action

1. The person enters text in LEAP and uses a verb to start an Action from a Space.
2. loci makes the goal, inputs, expected outputs, services, network use, destination, and available task context visible or reachable through the interface.
3. The person starts the Action through the normal LEAP action.
4. loci asks before a service or data use that existing approval does not cover. It does not ask again for ordinary discovery or new results within approved research.
5. Suggest records its Action when execution starts. It records options or data as the agent produces them.
6. loci records each started execution and its completed, failed, or stopped Action in Record. The person may choose an option later without changing the Suggest Action.

#### J6. Inspect Facts and provenance

1. The person finds a Fact or a result.
2. The person opens Fact selection explanation or provenance.
3. loci shows direct inputs, producing action, outputs, later uses, and the historical versions of the result that are available.
4. The person can open a historical version from the result, inspect what happened, and see the Facts and evidence associated with that version.
5. If a Fact was removed, affected results remain available and show missing Fact evidence. If an output was undone, it remains identified as unavailable.
6. The person opens a related Item, Fact, or Action without losing the selected result.

#### J7. Inspect past Record

1. The person inspects a past Record point.
2. The person can reach the past view from the result when the result has historical provenance.
3. loci shows what the past view includes, the available evidence and Facts for that version, and does not change current Facts or the current result.

#### J8. Back up and restore

1. The person creates a backup.
2. loci states what it includes, excludes, and references.
3. The person restores to a selected inactive target.
4. loci verifies the target before it becomes active.

## 8. Functional requirements

### Tenant, access, and navigation

**FR-001 — One Tenant (P0)**

loci shall operate on one local Tenant.

Acceptance criteria:

- The product shall state that all visible information belongs to the current Tenant.
- P0 shall not show a Tenant switcher.
- A backup, address, configuration value, and local data path shall identify one Tenant boundary.

**FR-002 — Stable addresses (P0)**

loci shall give stable local addresses to a Space, Item, Action, Fact, Subject, Flow, provenance detail, and past Record point.

Acceptance criteria:

- An automated browser test shall open each address and confirm its exact target id and requested Record point.
- An unavailable target shall show its id, reason, and one action: **Return to overview**.
- A deep link shall not require replay of a previous navigation path.

**FR-003 — Product navigation (P0)**

loci shall provide consistent navigation between overview, Space, Item, Search, Facts, Subjects, Flows, provenance, and Record history.

Acceptance criteria:

- If the person opens an Item from a Space, they shall return to that Space.
- If the person opens an Item from another location, they shall return to the prior product location.
- The Item detail shall list every Space that refers to the Item. The person can open each listed Space.
- From provenance detail, the person shall return to the selected result.

**FR-004 — First-run status (P0)**

loci shall show the state of a new or recovering local Tenant.

Acceptance criteria:

- A fresh data directory shall show an empty Tenant, **Create Space**, **Table input**, the resolved local data path, and no example content.
- The test fixture shall show example content only after an explicit opt-in action.
- A forced store-open failure shall show the failure state and shall not render the empty-Tenant screen.
- The screen shall show each configured external service as disabled, ready, or failed.

**FR-005 — Safe local access (P0)**

loci shall use safe local access defaults.

Acceptance criteria:

- A default start shall bind only to loopback. An integration test shall reject a connection through a non-loopback address.
- P0 shall reject a non-local bind setting before it starts the server.
- A state-changing request from an untrusted Origin shall return a rejection response and shall not change Record.
- A test shall prove that opening the initial page makes no third-party network request.

**FR-006 — Delivery and upgrade (D3, unapproved review choice)**

Before P0, product review shall select at least one supported package and OS/browser set.

Acceptance criteria, if D3 is approved:

- The selected package shall display its loci version and resolved data path.
- The package shall provide start and stop steps that need no developer tools.
- The selected package shall open loci in the selected browser or packaged desktop shell.
- A test shall upgrade current prototype data to the selected release and verify Spaces, Items, Facts, and Record history.
- A forced failed upgrade shall keep source data unchanged and state the recovery step.

Draft recommendation: use the existing portable jar and a selected browser as the baseline package. Test the container as a separate distribution. Do not claim an OS or browser that the release has not tested.

### Spaces and Items

**FR-010 — Space lifecycle (P0)**

A person shall create, rename, open, and remove a Space.

Acceptance criteria:

- Creating a Space without a title shall fail validation. Creating one with a title shall succeed.
- A Space purpose is optional. The person shall add, edit, or remove it without an Action.
- A removal confirmation shall list the Space prose, its Item references, and the fact that referenced Items remain unless removed separately.
- A Record test shall prove that a removed Space appears at its prior Record point.

**FR-011 — Space purpose and agent context (P0)**

loci shall show and use the current Space purpose.

Acceptance criteria:

- If purpose exists, the Space shall show it beside the title and the Action context shall include it.
- If purpose is absent, the Action context shall state that it uses the Space title and current Space content.
- A browser test shall edit and remove a purpose, then confirm the reachable Action context changes.

**FR-012 — Space content and relationships (P0)**

A Space shall contain ordered prose and references to Items.

Acceptance criteria:

- The person shall add, edit, move, and remove Space-owned prose.
- Adding an existing Item shall retain its id. A test shall prove no copy appears.
- The Space shall label each direct shared-Item, origin, merge, or derivation relation and open the related Space.

**FR-020 — Item lifecycle and types (P0)**

loci shall support Documents, Tables, and Reports as Items. A Flow groups Actions.

Acceptance criteria:

- Every Item detail shall show Item id, type, title, current state, and all referring Spaces.
- Removing an Item reference shall remove that reference only. It shall not delete the Item.
- Archive shall retain the Item and its existing references. It shall hide the Item from default Search and the new-reference picker.
- Global delete shall reject while any Space refers to the Item.
- Global delete confirmation shall list every Fact that will retain a missing Item source.
- A Record test shall prove the reference-removal, archive, rejected global-delete, global-delete, and past-Record states.
- The product shall not expose later Item types as P0 features.

**FR-021 — Minimum useful Item behavior (P0)**

Each P0 Item type shall support its minimum useful behavior.

Acceptance criteria:

- A Document shall show and edit text and show provenance. If a Document has an existing Source, it shall identify that Source.
- A Table shall show a page of rows, total row count, and an exact query result. If it has existing source-backed content, it shall show the selected Source state.
- A Report shall order prose and Item-reference blocks, open each reference, and show provenance for each result block.

**FR-022 — Views (P0)**

A person shall select an available View for an Item reference.

Acceptance criteria:

- The product shall show the selected View and the available Views.
- Changing one Space reference View shall not change another reference.
- A built aggregate View shall show **incomplete** until it uses a full-table aggregate query. P0 shall not label an aggregate over a sample as exact.
- A text View shall support direct text and a summary in the selected form.
- A Table View shall support functions and graphs when the selected View supports them.

**FR-023 — Transformations (P0)**

A transformation shall apply a function or summary and create new data.

Acceptance criteria:

- The person or agent shall be able to create a transformation from available data.
- The new result may replace the content shown in the same cell or appear in another cell.
- Showing a transformation result in the same cell shall not overwrite prior table storage or remove Record history.
- Each reference shall keep its own View. A function or graph used by a View shall leave the stored source data unchanged.
- One Space may keep the original Table while another Space uses transformed data.
- When a Space opens and its referenced data changed, loci shall show that change. The person shall choose whether to update the referenced data or result, or keep the prior version.
- An applicable configuration choice may determine this behavior according to the person's intent.

### Record, Facts, and Search

**FR-030 — Record changes (P0)**

loci shall record every user-visible change to a Space, Item, View, or Action.

Acceptance criteria:

- Each entry shall show actor, time, plain summary, kind and id for every affected Space, Item, View, or Action, and whether it is one grouped action.
- The product shall not present a Record change as complete before the Record entry exists.
- Fact creation, strengthening, decay, merge, unmerge, durability change, and removal shall remain outside Record.
- A deterministic test shall make a grouped change and confirm one Record event and the stated actor.

**FR-031 — Undo and past views (P0)**

A person shall undo the latest reversible Record action and inspect a past Record point.

Acceptance criteria:

- Undo shall name the next Record change it removes and refresh the affected view.
- For Action outputs, producing an Item and placing it visibly shall be one reversible unit. If an Action produces a Table and then a Report, the person can undo the Report and then the Table. Other reversible Record changes, such as prose edits and renames, remain undoable as their existing Record changes.
- Undo shall be available while an Action runs. It shall not implicitly stop the Action. A later Action step may fail because an input or output no longer exists; that is an execution failure, not an Undo failure.
- The Action's execution history shall remain when one of its outputs is undone. The undone output shall remain identified as unavailable.
- A past view shall show its Record point and reject state changes.
- The interface shall state that Facts remain current during a past Record view.
- If the past view includes a live Source, the Source shall state that its rows are current external content, not past Record content.

**FR-040 — Facts and Fact selection explanation (P0, label for review)**

loci shall provide a page named **Facts** for Fact inspection and controls.

Acceptance criteria:

- The page shall list Fact text, source, Subjects, durability, and current status.
- Action detail shall list each Fact that the action used.
- For each used Fact, the detail shall state whether Space lineage or a prompt-named Subject admitted it. The person can open the Fact and its provenance when it is available; otherwise the detail shall identify the missing Fact and preserve the recorded use.
- The person shall reach the Fact selection explanation from the Action context.
- Product review shall confirm or replace the page name before release.

**FR-041 — Fact controls and Subjects (P0)**

A person shall control Fact lifecycle and inspect basic Subjects.

Acceptance criteria:

- The person shall make a Fact durable, inspect an automatic merge, unmerge it, and remove it after a permanent-effect warning.
- Before removal, loci shall identify dependent Reports, calculations, and other results that refer to the Fact. It shall explain that removal makes Fact provenance unavailable or incomplete for those results.
- The person may confirm removal even when references exist. Affected results shall remain available and show missing Fact evidence. Remaining provenance shall stay intact.
- Fact removal shall not create a hidden historical copy to satisfy provenance. It shall remain outside Record, and Undo and past Record views shall not restore the removed Fact.
- P0 shall not offer a manual Fact merge action.
- A Subject page shall list its available Facts and link to each Fact source.
- P0 shall not claim Subject aliases, types, or a relationship graph.

**FR-050 — Search (P0)**

loci shall provide Search through the LEAP entry function across user-visible information.

Acceptance criteria:

- A deterministic search fixture shall find matching Space titles and prose, Item titles and document text, Table names and columns, Report text, Fact text, Subject names, and Action id, goal, and status.
- Each result shall show kind, title or text excerpt, source Space or Item when present, and current state. It shall open the exact target.
- Search shall label lexical and deeper results. It shall label a disabled, failed, or incomplete deeper search.
- Search shall be available through the LEAP entry function. LEAP shall also accept verbs for data discovery, agent instructions, content creation, and product actions.
- Search shall not claim to search all table rows from a bounded table synopsis.
- An empty query shall show the documented default result set. A query with no result shall show a no-result state and retain the query.
- One Item that appears in several Spaces shall appear once in general search. Its result shall list all referring Spaces.

### Sources, import, and export

**FR-060 — Table input (P0)**

loci shall let a person or an agent bring table data into loci as a Table Item.

Acceptance criteria:

- Supplied rows shall become usable table data in a Table Item. The person shall be able to inspect and analyze those rows afterward.
- An already available example Table shall not satisfy the input requirement by itself.
- The input shall show the resulting Table Item and its available data.
- P0 shall not require a new Connect Source control, source administration workflow, or approved input-format matrix.
- Existing CSV, JSON, and Parquet Source behavior may remain available as current foundation behavior. It does not define a new P0 input-format commitment.

**FR-061 — Source state and credentials (P0)**

loci shall preserve Source state and protect credentials for existing source-backed content.

Acceptance criteria:

- Existing Source detail shall show location, format, mode, latest successful refresh, and stored, live, drifted, unavailable, or reclaimed state.
- P0 shall reject an HTTP or HTTPS URL with URI user information, any query string, or a fragment before Record writes or network access. This is a deliberate safe P0 limit.
- Secrets shall not appear in Record, Facts, logs, addresses, provenance, backups, exports, or visible Source summaries. A regression test shall inspect each result.
- S3 shall use its external credential chain. loci shall not store S3 credentials.

**FR-062 — Document import (D1, deferred outside P0)**

Future source administration may support document import from configured sources. The earlier candidate formats are local TXT, Markdown, HTML, PDF with embedded text, and DOCX. They are future candidates, not approved P0 requirements.

Future candidate acceptance criteria, when this add-on is defined:

- Import shall preview the extracted title, text length, and first text section before it creates a Document.
- The Document shall retain a link to the original Source.
- Import shall reject scanned PDF and OCR work. It shall state that no embedded text was found.
- Parse failure shall leave the original file unchanged, create no Document, and show the format and failure reason.
- The design must define long-text storage, display, search, and export before implementation starts.

**FR-063 — Content export (D2, deferred outside P0)**

Future export may produce Markdown, a document format such as DOCX or PDF, or table data in a database. These are examples, not a committed connector or format matrix.

Future candidate acceptance criteria, when this add-on is defined:

- Spaces, Documents, and Reports shall export as Markdown.
- A full Table shall export as CSV or Parquet. A page export shall identify itself as page-only and state its page bound.
- Facts and provenance shall export as JSON.
- A selected View shall export as standalone HTML only when that View supports standalone HTML export. Otherwise the product shall reject the request and name the supported export.
- Every export shall identify export time, source Item ids, and whether it is full or bounded. It shall not change source content.

### Actions, execution, and external services

**FR-070 — Action context and consent (P0)**

loci shall make Action context visible or reachable as the person starts and reviews an Action.

Acceptance criteria:

- The Action context shall list or link to the goal, instructions, Space context, direct inputs, recalled Facts, tool results when they exist, expected outputs, network service, destination, and state changes.
- All task context available to the agent shall be visible to the person or reachable through the interface. The product shall not add hidden context. Credentials shall remain excluded from context.
- Context admission shall keep Space lineage and prompt-named Subjects. Visibility shall not add a rule that excludes context because it is off-screen.
- Starting an Action shall use the normal LEAP action. It shall not require a separate pre-start context display or approval step.
- If a service or data use is not covered by existing approval, loci shall ask before that use. Ordinary discovery and new results within approved research shall not require repeated approval for every input, output, URL, or tool result.
- If an Action expands its inputs, outputs, network use, or destination beyond applicable approval, loci shall stop and ask before that expanded step.

**FR-071 — Durable Actions and Stop (P0)**

loci shall retain one stable Record-backed Action for each started execution, including Suggest.

Acceptance criteria:

- The Action shall link to its Space, Flow when present, and output Items. It shall retain the identity and execution history of an output that is later undone, and identify that output as unavailable.
- A Flow shall show ordered Actions, Action status, inputs, outputs, and a link to each Action.
- The Action shall show Action type, submitted instructions, parameters, Facts used, model and tool names, versions when available, service, actor, start, finish, status, inputs, outputs, and errors. Secret values shall be excluded.
- Execution status shall be started, completed, failed, or stopped. It shall describe execution only and shall not judge truth or usefulness.
- Suggest completes when it produces its requested options or data. Completion does not require the person to accept an option.
- The Action shall remain after restart and open from its Space, Flow, Search result, and each available output Item. Missing or undone outputs shall remain identified in the Action without requiring them to open as available.
- After restart, a started Action without a finish time shall become stopped with the reason “interrupted by restart.” It shall retain its recorded inputs and outputs. P0 shall not resume it automatically.
- A running Action shall show Stop. Stop shall prevent later steps and later network calls, retain outputs already made, and record the actor and time.
- An already-sent remote request can finish after Stop. Its result shall not start later Action work after Stop.

**FR-072 — Human/result judgment (deferred outside P0)**

Human/result judgment remains a future design question outside P0.

Future design questions:

- A person may judge a result complete, incomplete, useful, or a dead end. The product shall keep a dead-end result available for further analysis.
- Facts may later prove incorrect or apply only during a period. The vocabulary, validity periods, recall treatment, and responsibility for these judgments remain open.

**FR-073 — Failure, retry, and table answers (P0)**

loci shall preserve an Action failure and support defined retry.

Acceptance criteria:

- A failed Action shall show failed step, error or stop reason, inputs, and outputs that exist.
- The retry control shall appear only for an Action type with a defined retry handler. The test shall prove that its new Action has a new id and links to the failed Action.
- Agent table work shall use exact table queries for filters, groups, aggregates, sorts, and limits. A 10-million-row fixture test shall compare an aggregate and a filtered page to a trusted exact result.
- Action provenance shall list every Table Item read by a saved result.

**FR-074 — Reusable functions (P0)**

loci shall let a person or an agent create functions for Item data.

Acceptance criteria:

- A function shall be visible somewhere in the product.
- The person and the agent shall be able to reuse a function in the product.
- The person shall be able to request a one-off summary or transformation without creating a reusable function.

**FR-075 — External services (P0)**

loci shall configure model connections, task-based model selection, internet search, and related service setup through the existing configuration file. P0 shall make service use visible in each Action.

Acceptance criteria:

- P0 shall not require a model setup or Service settings UI, provider-add control, or enable/disable control.
- Each user-started network Action shall make its relevant service identity, data scope, and task context visible or reachable, even when configuration exists. Credentials and other secrets shall remain excluded.
- No third-party call shall occur on page load. A browser network test shall prove this.
- If a configured service times out, has invalid credentials, or fails, the product shall show the service identity, failed capability, timeout or error, and a useful retry or recovery action. It shall not misreport the local Tenant as empty.
- Service configuration shall not become hidden task context. The Action shall make relevant service state and use visible or reachable.

### Provenance, backup, and restore

**FR-080 — Provenance inspector (P0)**

loci shall provide one provenance entry point on a result Item, Fact, and Action.

Acceptance criteria:

- A deterministic provenance fixture shall include a direct input, producing Action, output, downstream use, Fact source, and missing source.
- Each relation shall have a plain label and shall open its target without losing the selected result when the target is available. A removed Fact or undone output shall remain identified as missing or unavailable.
- Each expected fixture relation shall be clickable or show its missing or unavailable state.
- A result in a Space shall retain the context of the evidence used when it was produced.
- A refresh or changed evidence shall not silently replace the historical explanation for an earlier result.
- A refresh shall not be presented as proof that an earlier result was correct or incorrect. Human/result judgment remains deferred outside P0 under FR-072.
- Version history shall be reachable from the result. The person shall be able to inspect an available prior version, what happened, and the Facts and evidence associated with that version.
- Historical Fact evidence shall be shown with the historical result. It shall remain distinct from the current live Facts page. If the Fact was removed, the result shall show that its Fact evidence is missing and shall keep other provenance.
- Historical inspection shall not rewind or mutate current Facts.
- If history is unavailable or reclaimed, the inspector shall state that it cannot recover bytes no longer stored.
- For live Sources, the inspector shall state the limits of available history. P0 shall not promise to retain old external bytes unless the product has them.
- Action provenance shall show the exact submitted instructions, parameters, Facts used, inputs, outputs, model and tool names, versions when available, service, status, start, finish, and errors that the Action recorded. Secret values shall be excluded.
- Fact detail shall show its source, lifecycle state, related Action when present, and a missing-source state when its source is unavailable. A removed Fact shall remain identified as removed where it appears in recorded provenance.

**FR-090 — Backup (P0)**

loci shall create a complete local Tenant backup.

Acceptance criteria:

- The backup shall include Record history, Facts, stored local table data, loci-owned files, Source recipes, product version, non-secret Tenant settings, and all other local product data classes.
- The backup shall not include external `file://`, HTTP(S), or S3 bytes. Its report shall list them as references.
- The product shall require the app to stop before a file-level backup, or use an application backup operation that creates a verified consistent snapshot.
- The completed backup shall state its path, creation time, included data classes, and excluded secrets.
- The backup destination shall be outside the active Tenant data directory. The product shall reject the active directory as a destination.

**FR-091 — Restore (P0)**

loci shall restore a backup safely.

Acceptance criteria:

- Restore shall accept same-release backups and the explicitly versioned `prototype-2026-09-03` fixture. It shall reject every other version before any write or activation.
- Restore shall require a selected backup and an empty inactive target directory. It shall reject the active data directory and a non-empty target.
- Before activation, restore shall verify Record history, Facts, stored local table data, loci-owned files, Source recipes, product version, and non-secret Tenant settings.
- Restore shall not fetch external Source references. An absent external reference shall appear as unavailable and shall not invalidate restore.
- If verification fails, the restore target shall remain inactive and the active Tenant shall remain unchanged.
- A release test shall create a backup with each local data class, restore it to a new target, and compare Record ids, Fact ids, table row counts, and owned-file hashes.

## 9. Product quality requirements

**QR-001 — Data and action integrity (P0)**

- A completed Record change shall have a durable Record entry.
- A referenced unavailable Item shall show its id and state.
- A release check shall create, fail, stop, and complete Actions, then verify their Record-backed Actions.

**QR-002 — Table scale (P0)**

- The stated design target is ten million rows. This is not a response-time guarantee.
- A release fixture with ten million rows shall verify exact filter, group, aggregate, sort, and keyset page traversal results against trusted results.
- The test shall prove that paged access reaches rows beyond the initial page.
- Built aggregate Views remain incomplete until they use full-table queries. The release shall not call their sampled results exact.

Current evidence measures in-engine creation of a ten-million-row table from `range()` at about 2.9 seconds. It establishes that the mechanism works at that scale, not end-user cost. [Big-content measurement](superpowers/specs/2026-08-16-big-content-design.md#L561) [Fixture warning](../AGENTS.md#L273)

**QR-003 — Recovery and migration (P0)**

- The release shall test backup, restore, failed restore isolation, the `prototype-2026-09-03` fixture migration, and same-release backups.
- Other backup and prototype versions shall reject before any write. Migration shall retain existing references and Record history.

**QR-004 — Verification (P0)**

- The release shall run `clojure -M:test`, `npm run test:cljs`, and `npm run test:browser`.
- The release shall run deterministic agent-boundary tests for scope expansion, Suggest execution and output persistence, Actions, Stop, Fact selection explanation, external service use, and no-third-party page load.
- Each automated regression test shall fail when its protected defect returns.
- The release review shall report each check, result, and known skip.

### Product success at prototype stage

During this prototype stage, the user judges product success by whether these research journeys produce useful results and support review, further research, and later reuse. No numeric product target, pilot cohort, or formal validation campaign is required now. Engineering integrity checks and the ten-million-row design target remain separate checks for the implementation.

## 10. Dependencies and offline rules

| Dependency | Needed by | P0 rule |
|---|---|---|
| Local Record, Facts, local table data, and owned files | All local work | The product must work without an external service. |
| Model service | Action | The person starts the Action. Failure must preserve local data and the Action. |
| Embedding or rerank service | Configured semantic work | Configuration selects the service. The product shows disabled, ready, or failed state. |
| Existing Source-backed content | Source state and provenance | Preserve the current Source state and provenance behavior. P0 does not require source setup. |

If no external service is configured, loci shall support local Spaces, Items, Record, Facts already stored locally, local Sources, Search without deeper semantic work, and backup/restore.

No third-party request occurs on page load. A user-started network Action must make its service and data scope visible or reachable. Existing approval may cover the service and data use. If the use is not covered, the product must ask before it starts. P0 binds loopback only and rejects a non-local bind. State-changing requests reject untrusted Origins. Authenticated non-local access is P1 work.

Secrets must stay out of Record, Facts, logs, addresses, provenance, backup, and export. P0 rejects HTTP and HTTPS URLs with URI user information, a query string, or a fragment. This is a deliberate safe P0 limit. S3 uses its external credential chain. [Remote-source credential finding](superpowers/specs/2026-08-23-external-sources-phase-3-design.md#L83)

## 11. Risks and release checks

| Rank | Risk | Related requirement | Release check |
|---:|---|---|---|
| 1 | A non-local server exposes Tenant data. | FR-005, FR-075 | Verify loopback default, non-local-bind rejection, and untrusted-Origin rejection. |
| 2 | A secret enters durable information. | FR-061, FR-075 | Attempt credential URL and inspect Record, Facts, logs, backup, and provenance. |
| 3 | Backup misses a storage class or restore harms active data. | FR-090, FR-091 | Restore a complete backup into a new target; force verification failure and confirm active data stays unchanged. |
| 4 | A started Suggest or other Action disappears after restart. | FR-070, FR-071 | Restart after each Action state and inspect the linked Action and its outputs. |
| 5 | A table sample is shown as the full result. | FR-060, FR-073, QR-002 | Compare paged and exact-query results from the 10-million-row fixture. |
| 6 | Existing Source-backed content loses state or provenance. | FR-031, FR-061 | Change a live Source, open a past Record point, and verify the live warning. |
| 7 | Removing an Item reference damages another Space. | FR-020 | Remove one shared-Item reference and verify other references remain. Then verify global delete rejects while references exist. |
| 8 | Vocabulary migration loses old links or history. | QR-003 | Migrate a fixture with Spaces, Items, Facts, and Record history. |
| 9 | External semantic work runs without clear consent. | FR-070, FR-075 | Test page load, configuration-file service state, existing approval reuse, uncovered service or data use, and scope expansion. |
| 10 | Fact removal hides affected uses or provenance loss. | FR-041, FR-080 | Remove a Fact with dependent Reports and calculations, confirm the warning, and verify the results remain with missing Fact evidence and intact remaining provenance. |

## 12. Review choices and open decisions

These are not approved. Items marked “draft recommendation” state this document's proposed answer only. The second review decisions for points 1 through 7 are applied, including the clarification that shown workspace context means content visible or reachable in loci, and that seamless research does not require a separate pre-start context display. The third review decisions are applied for P0 data discovery through LEAP, usable table input, changed shared data, per-Item Undo, and Fact removal with missing provenance. Human/result judgment in FR-072 is deferred outside P0. D1 document import and D2 content export are deferred outside P0. P0 table input is separate from source administration. P0 service setup stays in the configuration file. D3 delivery remains undecided.

### D3 — Delivery and upgrade

Choose one or more supported delivery sets before P0:

1. Portable jar and a selected browser.
2. Container and a selected browser.
3. Packaged desktop application.

Draft recommendation: use the existing portable jar and a selected browser as the baseline. Test the container as a separate distribution. Do not claim a browser or operating system without a release test.

1. Confirm the P0 boundary and release review definition.
2. Confirm **Facts** as the page name or choose another name.
3. Confirm or revise the Space and Item archive and removal rules in FR-020.
4. Confirm or revise FR-002 address grammar, unavailable-target fallback, and past-record address retention.
5. Define the actor vocabulary for person, agent, system, and migration Record entries.
6. Define the first provenance inspector layout and relation labels.
- Decide whether an applicable configuration choice should control how a Space responds to changed referenced data, while preserving the person's intent and independent Views.
7. Define future source administration and the P1 remote Source list. Preserve current Source behavior for existing source-backed content without requiring source setup in P0.
8. Define P1 non-local authentication and trusted-Origin design.
9. Define the application backup format if file-level backup is not acceptable.
10. Define the long-text design before future D1 implementation.
11. Define the LEAP path for future configured sources. Candidate sources include CRM systems, databases, document repositories, filesystem folders, and Google Drive.
12. Define whether future import provides document content to the agent and which candidate formats it supports. Earlier candidates are TXT, Markdown, HTML, PDF with embedded text, and DOCX. Exclude OCR and scanned-PDF extraction unless later approved.
13. Define future export formats and whether export includes a provenance summary. Examples include Markdown, DOCX, PDF, and table data in a database. Do not treat these examples as a committed connector or format matrix.
14. Define which action types have a retry handler and what retry reuses.
15. Confirm the Action-to-Flow relation and define only Action and Flow presentation. An Action is a stable Record-backed product concept in this draft.
16. Define how the interface identifies individual output groups for Undo while preserving Action execution history.
17. Define the point where the implied Tenant becomes a stored Tenant record.
18. **Draft recommendation:** approve D3 portable jar plus a selected browser as the baseline package. Test the container as a separate distribution.
19. Define P1 only after P0 journeys have evidence from real use.

## 13. Evidence map

- [Current information architecture](notes/2026-09-02-loci-information-architecture.md)
- [Provenance product research](notes/2026-08-31-provenance-product-research.md)
- [Product walkthrough](walkthrough.md)
- [Record and Fact design](superpowers/specs/2026-07-22-record-recall-notebooks-design.md)
- [External Source design](superpowers/specs/2026-08-19-external-sources-design.md)
- [Remote Source credential design](superpowers/specs/2026-08-23-external-sources-phase-3-design.md)
- [Paged table design](superpowers/specs/2026-08-25-paged-table-grid-design.md)
- [Big-content design](superpowers/specs/2026-08-16-big-content-design.md)
- [Agent flow status](superpowers/specs/2026-08-08-forkable-flows-design.md)
- [Repository roadmap](../ROADMAP.md)
- [Repository agent guide](../AGENTS.md)
