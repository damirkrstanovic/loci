# loci — demo walkthrough (the four flows)

Start: `clojure -M:serve` → http://localhost:7777. Reset anytime with `rm -rf data`.
Flows 2–3 need the DeepSeek key; web search needs the Tavily key.

## 1 · A moldable notebook-briefing (offline)
Focus **World — countries & economies**. It's an authored notebook: prose
interleaved with the SAME `tbl:countries` molded two ways (bar, pivot) plus the
full report. Re-mold any cell from its menu — the data is stored once, the cell
only points. Edit prose in place, add cells, reorder — every step is one
substrate event: ↺ undo walks it all back. Restart the server: it's all still there.

On any table cell, **ƒ** opens the function palette (filter, group, pivot,
top-N, change, share — plus any functions the agent has written). Pick one,
see a live before/after preview, Apply — the derived table lands as a new
cell, provenance chips intact, one ↺ undo away.

Derived tables stay alive: change the source, hit **↻** on the derived cell —
the whole downstream chain recomputes in one reversible step.

## 2 · Research (agent + web)
Focus **Semiconductors — research hub** → **Research** → e.g.
"who fabricates leading-edge chips, and where are the supply chokepoints?".
The agent searches, extracts a table (that table IS the deliverable, molded like
any other), and lands a findings note — both as new cells. Then open **⌾ memory**:
the distilled facts are there, each citing the object it came from.

## 3 · Connected deep-dives (agent)
On the hub, hit **⌘ Deep-dive**. It runs in the background — keep working,
the notebooks land when ready (and cluster under their hub in the overview).
The agent proposes 2-3 subtopics (informed by
what it now REMEMBERS from flow 2 — watch for ⌾ citations), spawns a connected
notebook per subtopic and researches each. The hub grows a **connected** rail;
shared tables show **also in** chips; derived tables show their lineage.
Links are computed from the substrate — nothing to maintain, nothing to go stale.

## 4 · One search across everything (offline)
Tap any key → LEAP. Type `churn`: notebooks, tables, docs, notebook prose,
document bodies and memory facts, in one incremental list. Enter jumps —
a prose hit opens its notebook, a memory hit opens the memory pane.
One gesture; the whole substrate answers.

## 5 · Scrub time (offline)
Hit **⏱ time**. Drag the scrubber: the whole workspace reconstructs itself at
any past event — before the import, before the deep-dive, before everything.
Focus a notebook to read it as it was; nothing is editable in the past. This
is the same event log undo walks — time made visible. **↩ now** (or esc)
returns to the present. The ⌾ memory pane stays live: the scrubber travels
the record, never the recall.

## 6 · Connect two notebooks (offline)
Focus a notebook, LEAP, type another notebook's name → **⧉ Connect**. A NEW
notebook appears holding everything from both — the originals untouched. Zoom
out: connections (deep-dive spawns, merges) are drawn as dashed threads
between panels, straight from the substrate. LEAP also finds everything
you've *made*: a view or animation hit lands on its table, already molded;
a function hit opens the ƒ inspector.

## 7 · A full agent flow, gated (agent + web)
On any notebook hit **⚑ Flow** and give it a goal. The agent plans a short
flow — research → gate → compute → draft — as a real object IN the notebook:
a live checklist, every step a reversible event. Watch it work; at a **gate**
it stops and asks (⚑ needs-you) — approve to continue, reject to stop.
When it's done, scrub ⏱ time backwards: you can watch the flow execute
step by step through the substrate's own history. No framework — the
substrate is the framework.
