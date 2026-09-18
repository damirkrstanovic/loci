# loci information architecture — current state

Date: 2026-09-02

Status: research note. This note describes current code. It is not a specification or a plan.

Scope: the single-user loci workspace, its stored information, views, navigation, and HTTP boundary.
Method: read the current source, tests, served shell, README, walkthrough, and status-marked designs. Code is the authority where documents disagree.

## Executive model

loci has one implicit workspace. It has no workspace object. The current Record state is one global map from ids to objects. A notebook is a `:space` object that orders prose cells and references to those global objects. The same object can appear in many notebooks without a copy.

Record is an ordered, reversible event log. A commit appends one event. Undo removes the last event. Recall is a separate store of agent-written facts. Table rows live outside Record in table handles. The server molds Record objects into plain data. The shell lays out that data and keeps the current location in browser memory.

```text
implicit workspace
  Record event log ──fold──> global id → object map
    :space (notebook) ──ordered cells──> text | reference to global object
    global objects ──handles──> table rows in DuckLake / external source
  Recall facts ──ranked separately──> agent context and Memory
  derived chunks ──embedded separately──> deep LEAP
  server molds an object ──> shell navigation and overlays
```

## 1. Information map

| Part | Current role | Main boundary |
|---|---|---|
| Workspace | The implied set of all Record objects. | No `:workspace` object exists. |
| Notebook | A `:space` object with an intent, cells, tags, and optional lineage. | Cells keep text or object references. |
| Global object | An object in the one id-to-object map. | A reference cell does not own or copy it. |
| Record | Event log and its deterministic folded state. | Undo and time travel affect this layer. |
| Recall | Agent-written facts with source provenance. | It can decay, merge, and survive an undo. |
| Table store | Row storage behind table handles. | Large rows are not normally in an event. |
| Search chunks | Derived Record text and table synopsis vectors. | They are rebuildable and are not Recall facts. |
| Mold | A selection of a view and plain Clojure render data. | The server chooses and computes it. |
| Shell | The served JavaScript page and the grid seam. | It lays out results and sends actions back to the API. |

`Store` defines Record reads and writes. Its state shape is `{:objects {id object}}`, and `materialize` folds all events into that shape. There is no object that contains a workspace or selects one workspace first. [substrate.clj:80](../../src/loci/substrate.clj#L80) [substrate.clj:128](../../src/loci/substrate.clj#L128)

## 2. Terms and names

The interface and code use different names for some visible things. This is a current translation table, not a proposed vocabulary.

| Visible term | Code term | Meaning |
|---|---|---|
| notebook | `:space` | An object that orders cells and carries intent. |
| subject | `:entity` | A normalized Recall name. It can later have a Record object. |
| copy in | `:materialise` | Read a source once and write a new lake table. |
| read live | `:passthrough` | Store a source recipe and read its current bytes on each query. |
| Record | substrate | Reversible event-log state. |
| Recall / Memory | `loci.memory` | Separate fact store and ranker. |
| view | built-in viewer or `:viewspec` / `:applet` | These are two different mechanisms. |
| connected | `nb/links` result | A computed notebook relation, not an object graph stored separately. |

The shell still contains code labels such as “New space (agent),” while most visible copy says notebook. [index.html:2907](../../resources/public/index.html#L2907)

## 3. Current object inventory

The common Record object shape is normally `{:id ... :kind ... :title ... :value ...}`. Seed content uses this helper, but no central schema validates all objects. Each reader dispatches on `:kind` or on the value shape it expects. [content.clj:502](../../src/loci/content.clj#L502) [server.clj:196](../../src/loci/server.clj#L196)

| Kind | Current use | Usual visibility |
|---|---|---|
| `:space` | Notebook with intent, cells, tags, and lineage fields. | Main overview and focus view. |
| `:doc` | Text document. | Object, cell, and LEAP result. |
| `:table` | Rows or a table handle. It can have source metadata. | Object, cell, grid, and LEAP result. |
| `:metric` | Table-shaped metric data. | Object and molded view. |
| `:report` | Ordered report blocks, which can include object references. | Object and cell. |
| `:flow` | Durable Record flow and its steps. | Cell and Flows overlay. |
| `:entity` | Materialized subject name. | Subject page and optional cell. |
| `:viewspec` | Declarative agent view for a target object. | Hidden from the normal object list. |
| `:applet` | Agent-written code view for a target object. | Hidden from the normal object list. |
| `:fn` | Stored agent function and provenance. | Hidden from the object list; available through functions. |
| `:palette` | Tag-color registry. | Hidden implementation object. |

The state payload hides `:space`, `:viewspec`, `:applet`, `:fn`, and `:palette` from its general object list. This is a shell list rule. It is not an object-schema rule. [server.clj:201](../../src/loci/server.clj#L201) [server.clj:213](../../src/loci/server.clj#L213)

Tables can be legacy inline row vectors, lake handles, or source handles. Source metadata belongs on a table object at `:source`; the system has no `:source` object kind. [tablestore.clj:154](../../src/loci/tablestore.clj#L154) [server.clj:462](../../src/loci/server.clj#L462)

## 4. Notebooks, references, and relationships

A notebook value stores ordered `:cells`. A cell is either `{:text string}` or `{:ref object-id :view optional-view-id}`. Legacy `:members` values become reference cells on read. All new writes use `:cells`. [notebook.clj:1](../../src/loci/notebook.clj#L1) [notebook.clj:16](../../src/loci/notebook.clj#L16)

Cell operations add, edit, remove, move, or set a cell view. Most full changes are one `:assoc` event. A concurrent append uses `:append`; the fold makes the vector change when it applies the event. [notebook.clj:24](../../src/loci/notebook.clj#L24) [notebook.clj:49](../../src/loci/notebook.clj#L49) [notebook.clj:52](../../src/loci/notebook.clj#L52)

Notebook tests cover legacy normalization, the cell operations, concurrent appends, and undo of an appended cell. [notebook_test.clj:6](../../test/loci/notebook_test.clj#L6) [notebook_test.clj:63](../../test/loci/notebook_test.clj#L63) [notebook_test.clj:97](../../test/loci/notebook_test.clj#L97)

A notebook cell stores an object id. It does not own the object. `also-in` is computed by finding other notebooks that reference the same id. Each reference cell is molded with its selected view when the server builds the notebook response. [notebook.clj:94](../../src/loci/notebook.clj#L94) [server.clj:1138](../../src/loci/server.clj#L1138)

Tags are asserted notebook labels. They remain separate from fact entities. [server.clj:1192](../../src/loci/server.clj#L1192)

### Computed notebook links

`nb/links` derives links from present Record state. It reports:

| Reason | Rule |
|---|---|
| `shares` | Two notebooks reference the same object. |
| `spawned` / `spawned-by` | One notebook has `:value :spawned-by :space` equal to the other id. |
| `merged` / `merged-from` | One notebook id appears in the other notebook’s `:merged-from`. |
| `derived` | A referenced object’s direct `:from` id is referenced by the other notebook. |
| `also-in` | A referenced object appears in another notebook. |

The derived rule is one `:from` hop. It does not follow a longer chain. It also does not show sandbox `:reads` dependencies. [notebook.clj:63](../../src/loci/notebook.clj#L63) [notebook.clj:71](../../src/loci/notebook.clj#L71)

### Precise Recall lineage

Recall lineage is not the same as the visible link graph.

- Downward scope includes the notebook id, its cell ids, all spawned descendants, and all notebooks merged into it. Both relations are transitive.
- Upward scope follows only `:spawned-by`. Each ancestor contributes its own id and direct cell ids. The walk never descends from an ancestor, so it excludes siblings.
- `:merged-from` is not an upward ancestor. A notebook merged later is not the trunk of an input notebook.
- A shared object can bring its fact into scope when it is a cell here. It does not bring the other notebook or that notebook’s other cells.
- A cycle ends through a visited-id set.

The final scope is the union of the two walks. [server.clj:2308](../../src/loci/server.clj#L2308) [server.clj:2356](../../src/loci/server.clj#L2356) [server.clj:2400](../../src/loci/server.clj#L2400)

## 5. Record: events, history, and indices

Record events use `:put`, `:assoc`, `:append`, `:delete`, and `:tx`. A `:tx` contains sub-events but is one undoable event. The fold starts from an empty object map. [substrate.clj:101](../../src/loci/substrate.clj#L101) [substrate.clj:121](../../src/loci/substrate.clj#L121) [substrate.clj:128](../../src/loci/substrate.clj#L128)

Datalevin keys each durable event by its position, `1..n`. Event position is the history identity used by undo and time travel. A commit adds a millisecond `:ts`. A `:tx` sub-event has no separate timestamp; consumers inherit the transaction timestamp where needed. [dlv.clj:66](../../src/loci/dlv.clj#L66) [dlv.clj:122](../../src/loci/dlv.clj#L122) [tablestore.clj:168](../../src/loci/tablestore.clj#L168)

Undo deletes the last event and rebuilds state from the shortened log. `as-of` folds an event prefix. `store-at` exposes that prefix as a read-only frozen store for routes that call it. [dlv.clj:136](../../src/loci/dlv.clj#L136) [substrate.clj:85](../../src/loci/substrate.clj#L85) [server.clj:123](../../src/loci/server.clj#L123)

Datalevin also keeps derived `touched` and `counts` DBIs. The log is the truth. These indices can be rebuilt and must not answer while a rebuild is marked incomplete. [dlv.clj:22](../../src/loci/dlv.clj#L22) [dlv.clj:223](../../src/loci/dlv.clj#L223)

Record and Recall are separate. Undo and Record time travel never modify Recall. A fact can refer to an object that an undo later removes. [memory.clj:5](../../src/loci/memory.clj#L5) [server.clj:547](../../src/loci/server.clj#L547)

The durable-memory tests reopen Recall after undo and confirm that the fact remains. [memory_dlv_test.clj:61](../../test/loci/memory_dlv_test.clj#L61)

## 6. Tables and sources

### Handles and storage

There are three table handle modes:

| Handle | Meaning | Time behavior |
|---|---|---|
| `:inline` | Legacy rows live inside the object value. | Record event holds the rows. |
| `:lake` | A DuckLake table and snapshot hold rows. | New writes create new lake storage. |
| `:source` | A source recipe has URI, format, raw columns, normalized columns, and recorded count. | Reads current source bytes. |

The event log stores a handle, not large lake rows. A table write creates new storage instead of overwriting a table version. Source refresh records two `:assoc` sub-events inside one `:tx`. A materialised refresh creates new lake storage. A passthrough refresh moves no rows. [tablestore.clj:154](../../src/loci/tablestore.clj#L154) [source.clj:263](../../src/loci/source.clj#L263) [source.clj:424](../../src/loci/source.clj#L424)

`data/substrate/` holds Datalevin events, facts, and chunks. `data/lake/` holds the DuckLake catalog and Parquet versions. `data/files/` holds files owned by loci. `<LOCI_DATA>/files.edn` is a last-wins EDN manifest for original names and retirement records. It is primary storage, not Record and not derived. Uploads and pasted CSV use content-addressed names under `data/files`. Upload writes bytes and the manifest before connect, but alone commits no Record event. [files.clj:16](../../src/loci/files.clj#L16) [files.clj:37](../../src/loci/files.clj#L37) [files.clj:242](../../src/loci/files.clj#L242) [server.clj:2886](../../src/loci/server.clj#L2886)

The two storage engines do not share one transaction. A lake write can complete before the Record commit. A commit failure can therefore leave unreferenced lake storage for later reclaim. [sandbox.clj:47](../../src/loci/sandbox.clj#L47) [sandbox.clj:55](../../src/loci/sandbox.clj#L55)

Table hydration returns a bounded sample. Notebook responses split a 500-row total budget across reference cells, with no more than the normal 100-row page for one cell. The grid fetches its own paged mold result through the ClojureScript seam. [server.clj:1124](../../src/loci/server.clj#L1124) [tablestore.clj:379](../../src/loci/tablestore.clj#L379) [grid.cljs:140](../../src-cljs/loci/grid.cljs#L140) [grid.cljs:243](../../src-cljs/loci/grid.cljs#L243) [index.html:2063](../../resources/public/index.html#L2063) [index.html:2317](../../resources/public/index.html#L2317)

### Source shapes and failures

A source has a URI, format, mode, optional options, and time of last connect or refresh. `describe` is a read-only preview. It checks that DuckDB can read the location and returns raw and normalized columns before a connect commits anything. [source.clj:17](../../src/loci/source.clj#L17)

`materialise` reads the source once into DuckLake. `passthrough` keeps the recipe and builds the source relation on each read. Remote `http://`, `https://`, and `s3://` sources can only materialize. [source.clj:103](../../src/loci/source.clj#L103) [source.clj:139](../../src/loci/source.clj#L139)

Hydration can mark a table as reclaimed, unavailable, or source-drifted. The server turns these into source states `reclaimed`, `unavailable`, `drift`, `live`, and `stored`. A stored source still has a refresh recipe. [tablestore.clj:732](../../src/loci/tablestore.clj#L732) [server.clj:433](../../src/loci/server.clj#L433)

A passthrough handle is intentionally live. Its current rows are not content-versioned by Record. A frozen Record point can therefore resolve a source handle from the past and read current external bytes. The handle count is only the count recorded when that handle was created or refreshed. [tablestore.clj:859](../../src/loci/tablestore.clj#L859)

Manual table reclaim can remove out-of-window lake table versions and Parquet files. Automatic snapshot expiry is disabled. The manual operation makes some past row handles unreadable, even though the Record event remains. [lake.clj:1549](../../src/loci/lake.clj#L1549)

## 7. Recall and subjects

A current fact has `:id`, `:fact`, `:entities`, `:source`, `:ts`, and `:strength`. It can also carry vector, model, dimension, durability, and merge information. A source can name both an object and a notebook. [memory.clj:5](../../src/loci/memory.clj#L5) [memory.clj:885](../../src/loci/memory.clj#L885)

Facts reinforce near duplicates. They can decay unless durable, merge through calibrated semantic rules, unmerge, or be forgotten. Forget is permanent from the Recall store. [memory.clj:122](../../src/loci/memory.clj#L122) [memory.clj:1003](../../src/loci/memory.clj#L1003) [memory.clj:1230](../../src/loci/memory.clj#L1230)

Entities are normalized names projected from live facts. A name is known after one fact. It is promoted after two live facts or materialisation. A materialized `:entity` object never demotes. The system has no aliases, types, or relations for entities. [entities.clj:21](../../src/loci/entities.clj#L21) [entities.clj:66](../../src/loci/entities.clj#L66)

Agent Recall uses two eligibility paths:

```text
fact source is in the notebook lineage
OR
fact carries a known subject named in the current prompt
```

The subject set comes from the prompt only. It does not add all subjects inferred from the lineage. This means the README claim that separately rooted notebooks about the same subject are always blind is stale. [memory.clj:929](../../src/loci/memory.clj#L929) [server.clj:2443](../../src/loci/server.clj#L2443)

Server tests cover both rules: lineage excludes siblings, and a prompt-named subject can admit facts from another root. [server_test.clj:2401](../../test/loci/server_test.clj#L2401) [server_test.clj:2986](../../test/loci/server_test.clj#L2986)

The agent gets direct Record context from the current notebook’s cells. Lineage changes Recall facts, not the direct document and table context. Table work uses the sandbox query surface rather than a capped row sample. [sandbox.clj:1](../../src/loci/sandbox.clj#L1) [sandbox.clj:167](../../src/loci/sandbox.clj#L167)

The Memory endpoint supports `?space=` and then uses lineage sources only. It does not add prompt subjects because the endpoint has no prompt subject input. The current shell API wrapper calls `/api/memory` without `space`, so the visible Memory pane is global. [server.clj:3746](../../src/loci/server.clj#L3746) [index.html:749](../../resources/public/index.html#L749)

Record output and automatic Recall distillation are not atomic. The Record object can persist when Recall work fails or finishes later. [server.clj:2593](../../src/loci/server.clj#L2593)

## 8. Molding, reports, views, and agent table access

The built-in viewer registry is an ordered atom. The first matching viewer is the default. A caller can force a registered view id. Molding returns plain data and alternatives. [mold.clj:22](../../src/loci/mold.clj#L22) [mold.clj:47](../../src/loci/mold.clj#L47)

The current `register!` mutates that atom. It does not commit a Record event, despite an older namespace comment that says registry registration is reversible. [mold.clj:8](../../src/loci/mold.clj#L8) [mold.clj:24](../../src/loci/mold.clj#L24)

Built-in viewers include rows, summary, cards, pivot, charts, document views, and report views. They are code registry entries. [content.clj:317](../../src/loci/content.clj#L317)

Record-backed dynamic views differ from the registry:

- A `:viewspec` object holds a declarative filter, grouping, aggregation, sort, limit, columns, and output kind.
- An `:applet` object holds code and a target object.
- The server adds both object kinds to the alternatives for their target.

[server.clj:425](../../src/loci/server.clj#L425) [viewspec.clj:77](../../src/loci/viewspec.clj#L77)

Reports can contain inline blocks and reference blocks. The server resolves a report reference through the same mold path as a notebook reference cell. [server.clj:590](../../src/loci/server.clj#L590)

The sandbox gives agent code snapshot-pinned relations and `q`, `q-reduce`, `q-all`, and `keep!`. HoneySQL data becomes DuckDB SQL. Query execution tracks exact relation reads, not text matches. Saved derived objects carry direct `:from`; function objects also record `:reads` for actual table dependencies. [sandbox.clj:167](../../src/loci/sandbox.clj#L167) [sandbox.clj:202](../../src/loci/sandbox.clj#L202) [honeyq.clj:55](../../src/loci/honeyq.clj#L55)

## 9. Search and discovery

LEAP has two paths.

| Path | Finds | Limit |
|---|---|---|
| Lexical LEAP | Objects, notebook prose, document text, subject results, Memory facts, views, functions, and viewer verbs. | Six lexical content groups are capped at 8; viewer verbs are appended separately without that cap. |
| Deep LEAP | Derived Record chunks by embedding similarity. | One chunk per object, at most eight hits, plus at most one status note. |

The lexical cap applies to six content groups. Viewer verbs are appended after those groups. Deep LEAP keeps one chunk per object, takes at most eight hits, and can append one status note. [server.clj:1112](../../src/loci/server.clj#L1112) [server.clj:1119](../../src/loci/server.clj#L1119) [server.clj:805](../../src/loci/server.clj#L805) [server.clj:827](../../src/loci/server.clj#L827)

Deep chunks come from notebook title, intent, and prose cells; document sections; report prose; and one bounded table synopsis. Chunks exclude Recall facts and several hidden object kinds. A table synopsis has at most 24 columns and three sample values per column. [chunks.clj:1](../../src/loci/chunks.clj#L1) [chunks.clj:76](../../src/loci/chunks.clj#L76) [chunks.clj:258](../../src/loci/chunks.clj#L258)

Deep LEAP searches Record chunks, not Recall vectors. Plain LEAP does search Recall facts lexically. A semantic Recall hit can therefore help the agent but not appear in Deep LEAP. [server.clj:4050](../../src/loci/server.clj#L4050) [memory.clj:965](../../src/loci/memory.clj#L965)

Deep-LEAP tests also protect the split: ordinary keystrokes make no embedding request. [leap_deep_test.clj:161](../../test/loci/leap_deep_test.clj#L161)

## 10. Navigation and UI hierarchy

```text
overview
  notebooks, tags, and LEAP filter
focused notebook
  intent, relationship line, action bar, cells, connected rail
object detail
  replaces the focused notebook body; returns to the selected owner
global overlays
  Memory, functions, flows, storage, answers, and subjects
time mode
  selected Record prefix; writes and LEAP disabled
```

The top bar exposes overview, Memory, functions, flows, storage, time, and undo. Focused notebooks add Ask, Research, Suggest, Flow, Draft, Data, and tags. [index.html:681](../../resources/public/index.html#L681)

Object detail is client state, not an addressable page. `mode`, `focusIdx`, and `openId` hold navigation. The shell does not use browser history, hash state, or a URL route for an open object. [index.html:831](../../resources/public/index.html#L831) [index.html:1881](../../resources/public/index.html#L1881)

The overview groups a notebook below `:spawned-by` or only the first `:merged-from` id. When an object has many notebook owners, `openObject` selects the first matching notebook. [index.html:848](../../resources/public/index.html#L848) [index.html:1881](../../resources/public/index.html#L1881)

Only the table grid is ClojureScript. The served shell is otherwise plain JavaScript. The shell creates a grid seam; the grid makes a Transit mold request and owns its paging state. [grid.cljs:1](../../src-cljs/loci/grid.cljs#L1) [grid.cljs:140](../../src-cljs/loci/grid.cljs#L140) [grid.cljs:243](../../src-cljs/loci/grid.cljs#L243) [index.html:2063](../../resources/public/index.html#L2063) [index.html:2317](../../resources/public/index.html#L2317) [server.clj:4182](../../src/loci/server.clj#L4182)

Time mode reads `/api/events`, then calls only selected read routes with `?at=N`. It keeps Recall current by design. [server.clj:144](../../src/loci/server.clj#L144) [server.clj:547](../../src/loci/server.clj#L547)

## 11. Jobs and durable flows

Jobs live in a process-local atom. A future writes its result there and the browser polls `/api/job`. A restart loses that state. There is no durable job history, retry, cancellation, or recovery. [server.clj:3365](../../src/loci/server.clj#L3365)

A `:flow` is different. It is a Record object with steps and status. Valid verbs are `research`, `compute`, `ask`, `draft`, and `gate`. Each step update is a Record event. A gate stops at `needs-you`; approval starts another ephemeral job and rejection ends the flow. [server.clj:3391](../../src/loci/server.clj#L3391) [server.clj:3650](../../src/loci/server.clj#L3650)

Flows have no branch or retry model. Durable flow state does not make its running job durable.

## 12. API information map

The HTTP interface is action-oriented RPC. It is not a strict resource REST API. Most routes do not check the HTTP method. `/api/undo` mutates through GET. Notebook and destructive storage paths are the main method-gated cases. [server.clj:4038](../../src/loci/server.clj#L4038) [server.clj:4078](../../src/loci/server.clj#L4078) [server.clj:4156](../../src/loci/server.clj#L4156)

| User purpose | Main routes |
|---|---|
| Start and inspect | `/`, `/api/state`, `/api/events`, `/api/object/:id`, `/api/mold` |
| Change Record | `/api/undo`, `/api/edit`, `/api/notebook`, `/api/new-space`, `/api/connect`, `/api/keep-note` |
| Search and agent work | `/api/leap`, `/api/ask`, `/api/research`, `/api/suggest`, `/api/suggest-run`, `/api/do`, `/api/rerun`, `/api/job` |
| Notebook structure | `/api/links`, tags routes, functions routes, flow routes |
| Recall and subjects | `/api/memory`, Memory mutation routes, `/api/entity`, `/api/entity-notebook` |
| Data and storage | source describe/connect/refresh/files/upload routes and storage report/reclaim routes |

`?at=N` works on `/api/state`, `/api/mold`, `/api/flows`, notebook reads, links, entity reads, and object reads. It does not make every read historical. LEAP, Memory, functions, jobs, sources, and storage remain live. [server.clj:4045](../../src/loci/server.clj#L4045) [server.clj:4125](../../src/loci/server.clj#L4125) [server.clj:4179](../../src/loci/server.clj#L4179)

## 13. Lifecycle examples

### Connect a source

```text
choose or upload a location
→ /api/source/describe validates it and returns columns
→ choose copy in (:materialise) or local read live (:passthrough)
→ /api/source/connect creates one :table object and may append a notebook cell in one :tx
→ mold reads table rows or reports source state
→ refresh creates a new lake handle for a materialised source
```

`describe` is a dry run. Connect records the table object and source metadata. [source.clj:17](../../src/loci/source.clj#L17) [source.clj:348](../../src/loci/source.clj#L348)

### Research and distill Recall

```text
prompt in a notebook
→ job runs agent work with direct notebook context and scoped Recall
→ Record objects and cells commit
→ distillation writes facts with object and notebook provenance
→ later agent Recall ranks lineage facts plus prompt-named subject facts
```

The later Recall write is best effort and separate from the Record commit. [server.clj:2524](../../src/loci/server.clj#L2524) [server.clj:2593](../../src/loci/server.clj#L2593)

### Derive and rerun a table

```text
agent or function queries a snapshot-pinned table relation
→ a query records exact :reads
→ keep! creates a new lake table and stages a :put with :from
→ one :tx commits the derived object and related Record work
→ rerun uses :from and :reads to rebuild downstream work
```

The visible notebook graph only presents direct `:from` provenance. [sandbox.clj:247](../../src/loci/sandbox.clj#L247) [sandbox.clj:270](../../src/loci/sandbox.clj#L270) [notebook.clj:71](../../src/loci/notebook.clj#L71)

### Inspect past Record with current Recall

```text
pick event N in time mode
→ a supported read route receives a FrozenStore at N
→ Record objects, links, and source handles come from N
→ Recall facts remain current
→ a passthrough source handle may read current external bytes
```

This is intentional Record/Recall separation, with the source-handle exception to content history. [server.clj:123](../../src/loci/server.clj#L123) [server.clj:547](../../src/loci/server.clj#L547) [tablestore.clj:859](../../src/loci/tablestore.clj#L859)

## 14. Current IA gaps and contradictions

These points rank by likely user impact. They describe current behavior. They do not propose changes.

1. **Locations are not addressable.** A notebook, object, overlay, view, or time position has no stable browser URL. Reload, browser history, sharing, and bookmarks cannot restore the current location. [index.html:831](../../resources/public/index.html#L831)
2. **Object ownership is ambiguous.** Opening a shared object moves focus to the first notebook that contains it. Orphan object detail still appears inside the current notebook chrome. [index.html:1881](../../resources/public/index.html#L1881)
3. **Merge parents are reduced in the overview.** The Record model can have many `:merged-from` ids, but overview parent selection uses index zero. [index.html:848](../../resources/public/index.html#L848)
4. **Recall scope is hidden and differs by surface.** Agent Recall has lineage plus prompt-named subjects. Scoped Memory has lineage only. The visible shell Memory pane is global because it sends no `space`. [memory.clj:929](../../src/loci/memory.clj#L929) [server.clj:3746](../../src/loci/server.clj#L3746) [index.html:749](../../resources/public/index.html#L749)
5. **Source refresh visibility differs from source capability.** The server reports stored materialized sources as refreshable. The shell shows source controls primarily for live passthrough sources, so a copied-in source can look like an ordinary static table. [server.clj:452](../../src/loci/server.clj#L452)
6. **LEAP has dead viewer rows.** The server returns viewer verbs, but selecting one closes LEAP without a selected target to change. [index.html:3006](../../resources/public/index.html#L3006)
7. **Layer names drift.** Notebook/space, subject/entity, copy in/materialise, and read live/passthrough each have two names. `view` also means a built-in registry entry and a Record object.
8. **The add-object control is id-first.** The notebook add-object datalist stores object ids as values and titles as labels. Cells and LEAP also expose object ids. [index.html:1054](../../resources/public/index.html#L1054) [index.html:1062](../../resources/public/index.html#L1062)
9. **Jobs are ephemeral.** A server restart loses job state. A flow can persist its steps but cannot resume a lost running job. [server.clj:3365](../../src/loci/server.clj#L3365)
10. **No central object schema exists.** Object shape enforcement is spread over writers and readers. New kinds can be hidden, unchunked, or unmolded until each consumer knows them. [chunks.clj:258](../../src/loci/chunks.clj#L258)
11. **Visible provenance is incomplete.** The connected graph omits sandbox `:reads`. Its derived relation is only one direct `:from` hop. [sandbox.clj:270](../../src/loci/sandbox.clj#L270) [notebook.clj:71](../../src/loci/notebook.clj#L71)
12. **Search has different semantic domains.** Deep LEAP covers Record chunks, while semantic Recall covers facts. The two semantic result sets do not meet. [chunks.clj:17](../../src/loci/chunks.clj#L17) [memory.clj:965](../../src/loci/memory.clj#L965)
13. **Table search is bounded by design.** A table synopsis does not index arbitrary rows. This supports table discovery, not a full table search. [chunks.clj:201](../../src/loci/chunks.clj#L201)
14. **Subjects have no aliases, types, or relations.** They are normalized names and fact projections, plus an optional Record object. [entities.clj:66](../../src/loci/entities.clj#L66)
15. **There is no blob or media store.** Documents remain in Record, and tables use DuckLake or sources. README states this boundary directly. [README.md:12](../../README.md#L12)
16. **Historical live reads are not historical content.** A passthrough source can change with no Record event. Frozen Record state may therefore display current source bytes. [tablestore.clj:892](../../src/loci/tablestore.clj#L892)
17. **Some ids can be reused across lifetimes.** Source table id minting checks current objects only. A deleted id can later name a new table. The event log distinguishes the two by position. [source.clj:234](../../src/loci/source.clj#L234)
18. **A source URI can expose credentials in Record history.** A presigned URL or `user:pass@host` URL can be committed verbatim as source metadata. [2026-08-23-external-sources-phase-3-design.md:83](../superpowers/specs/2026-08-23-external-sources-phase-3-design.md#L83)
19. **Manual storage reclaim changes historical readability.** The log retains its past handles, but reclaimed lake versions cannot read their old rows. [lake.clj:1572](../../src/loci/lake.clj#L1572)
20. **README overstates history and Recall parity.** “Any read + `?at=N`” is broader than the router. The claim that same-subject roots are blind, and that Memory shows the agent context, no longer matches current prompt-subject scope and global shell Memory. [README.md:26](../../README.md#L26) [README.md:515](../../README.md#L515)

## 15. Documentation status

The current authorities are the source, tests, and served shell. `README.md` remains the product and deployment guide. `docs/walkthrough.md` remains a useful demo guide but has stale claims.

The original Record/Recall design labels itself as an original shipped design and says later work moved both stores to Datalevin. It also states “no embeddings,” which is no longer current. [2026-07-22-record-recall-notebooks-design.md:1](../superpowers/specs/2026-07-22-record-recall-notebooks-design.md#L1) [2026-07-22-record-recall-notebooks-design.md:27](../superpowers/specs/2026-07-22-record-recall-notebooks-design.md#L27)

The external-source phase 2 and phase 3 designs have built status lines. They are useful for intent and historical limits, but code remains the current behavior. [2026-08-20-external-sources-phase-2-design.md:1](../superpowers/specs/2026-08-20-external-sources-phase-2-design.md#L1) [2026-08-23-external-sources-phase-3-design.md:1](../superpowers/specs/2026-08-23-external-sources-phase-3-design.md#L1)

The ClojureScript shell design says it supersedes parts of the earlier paged-grid design. [2026-08-25-cljs-shell-and-elision-design.md:75](../superpowers/specs/2026-08-25-cljs-shell-and-elision-design.md#L75)

The walkthrough title says “the four flows” but has seven sections. Its “Deep-dive” action label is also stale. [walkthrough.md:1](../walkthrough.md#L1) [walkthrough.md:28](../walkthrough.md#L28)

## 16. Evidence map and open questions

### Evidence map

| Question | Primary current evidence |
|---|---|
| Record state and events | `substrate.clj`, `dlv.clj`, and substrate tests. |
| Notebook cells and links | `notebook.clj`, `server.clj`, and notebook/server tests. |
| Row storage and source behavior | `tablestore.clj`, `lake.clj`, `source.clj`, and source/lake tests. |
| Recall facts and subjects | `memory.clj`, `entities.clj`, and memory/server tests. |
| Search | `chunks.clj`, `server.clj`, and LEAP tests. |
| Shell navigation | `resources/public/index.html` and browser tests. |
| History of decisions | Status-marked files in `docs/superpowers/specs/`. |

### Open questions

- Which user-visible state must have a stable URL: notebook, object, subject, view, overlay, query, time point, or all of them?
- Is the first notebook owner an acceptable return target for a shared object?
- Should Memory expose the same scope that agent actions use, and how should it explain prompt-named subject facts?
- Which source states must show Refresh, and should a stored source look distinct from an unsourced table?
- Which provenance edges should a person see: direct `:from`, recursive derivation, sandbox `:reads`, source history, or all of these?
- What result should semantic search provide when Record chunks and Recall facts disagree or overlap?
- What type, alias, and relation information does a subject need before it becomes more than a fact index?
- Which historical-read guarantee should a live source have, if any?
- What durable job state, if any, must exist after a server restart?
- Which object kinds and fields need a documented shared schema before new kinds are added?
