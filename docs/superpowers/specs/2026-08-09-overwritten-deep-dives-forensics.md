# How three notebooks were overwritten, with the events

Date: 2026-08-09 (Task 0 of `docs/superpowers/plans/2026-08-09-repair-the-overwritten-deep-dives.md`)
Status: **investigation complete.** Nothing under `data/` and nothing under `src/` was modified to
produce this document. It is the evidence the repair in Tasks 2–4 is reviewable against; without it,
a reader in six months finds three notebooks appearing from nowhere and fifteen facts changing
owner, and has no way to tell a repair from a corruption.

---

## The one-paragraph version

On 2026-07-23 a research table arrived with a column named `Key Proponent(s)`. `pr-str` wrote it as
the keyword `:Key Proponent(s)`, which `edn/read` cannot read back, and the loader of the day
treated a parse failure as **end of file**. From the next boot the store's in-RAM world was **49 of
80 events** — and the three astronomy deep-dive notebooks `space:dd-16/17/18`, created ten minutes
before the poisoned line was written, were among the 31 events that had gone invisible. Deep-dive
ids were minted from `(inc (count (nb/notebooks st)))` — a **count**, over that truncated view — so
the next deep-dive run re-minted 16, 17 and 18 onto three live notebooks and `:put` overwrote them
with no error anywhere. Their findings documents collided the same way. The fifteen facts distilled
from those notebooks live in a different file, `data/memory.edn`, which nothing truncated: **they
were filed correctly, and they are still exactly where they were put.** What moved was the world
underneath them.

---

## How this was investigated

`data/` was copied, never opened. `data/memory.edn`, `data/substrate/`, `data/substrate.edn` and
`data/substrate.edn.bak-2026-08-05` were copied to `/tmp/loci-misfiled-investigate/data`; every JVM
ran with `LOCI_DATA` pointed there and every store was constructed with an explicit path rather than
by dereferencing `loci.memory/memory` or `loci.substrate/data-dir`. Fingerprints before and after
are identical (§ Verification). The live Datalevin store's directory was never opened at the repo
path — a concurrent measurement run held it, and Datalevin takes a directory lock.

---

## 1. The chain, in order

### (a) the poisoned column

`tbl:extract-13-7202`, "Alternative Technologies to Replace ASML EUV Lithography", saved by
`tools/save-table!` at 2026-07-23T18:05:21.394Z. One of its columns was named `Key Proponent(s)`.
As a map key it became the keyword `:Key Proponent(s)`, which prints without complaint:

```
{:technology "Nanoimprint Lithography (NIL)", :Key Proponent(s) "Canon (Japan)", …}
```

and does not read back. In `data/substrate.edn.bak-2026-08-05` — the pre-repair log, md5
`09338e34a1ee9abb2069bb77c6dc5aef` — that event is **line 50 of 80**, and the exact reader error is:

```
LINE 50 UNREADABLE: Map literal must contain an even number of forms
```

`Proponent(s)` tokenizes as the symbol `Proponent` followed by the list `(s)`, so the map ends up
with an odd number of forms. Nothing about it is a *truncated* line; it is a complete, well-formed
line of text that is not EDN.

### (b) the loader treated that as EOF

The loader before `1626b3a`, verbatim:

```clojure
(defn- load-events [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc []]
          ;; a crash mid-append can truncate the last line — salvage the prefix
          (let [ev (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof ev) acc (recur (conj acc ev))))))
      [])))
```

The comment says what it was for — a crash mid-append truncates the **last** line — and the `catch`
cannot tell that case from a bad line in the middle. A bad line anywhere returns the prefix before
it, silently, with no message and no error.

Run verbatim against the pre-repair log, it returns:

```
OLD loader over the pre-repair log: 49 events out of 80 lines
  objects 45  spaces 15  tables 13
  space:dd-16 visible? false
  highest visible space:dd-N: 15
  last event the old loader saw: {:op :assoc, :id "space:dd-13", :ts 1784829860874}
```

**49 events.** That is not a reconstruction of the number in `1626b3a`'s commit message ("repaired
separately, 49 → 80 events, 30 → 60 objects"); it is the same code over the same file, reproduced.
80 − 49 = 31, the "31 events recovered" of the commit subject.

### (c) an id minted from a count, over that view

The minting form deleted by `747bd29`:

```clojure
-  (let [n   (count (nb/notebooks st))
-        sid (str "space:dd-" (inc n))]
```

A count, of every notebook in the store, with nothing tying it to the `dd` sequence and no check
that the id it produces is free. Over a state showing 15 notebooks it produces `space:dd-16` —
which, in the file, already named a live notebook the state could not see.

### (d) `:put` overwrote it, in silence

`apply-event :put` is `(assoc-in st [:objects id] value)`. There is no occupancy check anywhere on
the path, by design: `:put` is how an object is created *and* how it is replaced. Nothing
distinguished "create dd-16" from "replace dd-16", so nothing could warn.

The old line stayed in the file. After the 2026-08-05 repair made line 50 readable again, the full
fold ran for the first time — and last-write-wins handed each id to the **later** `:put`. The
astronomy notebooks did not decay; they were resurrected into the log and immediately lost the
fold.

---

## 2. The six reused ids

Event numbers are positions in `data/substrate.edn` (one line = one event); they are identical in
the live Datalevin store, which holds 115 events. Timestamps are UTC, as stored.

| id | first `:put` | title | second `:put` | title |
|---|---|---|---|---|
| `space:dd-16` | ev **56** — 18:09:30.288Z | "Inner vs. Outer Planets" | ev **65** — 21:07:24.596Z | "Geopolitical Shock Scenarios" |
| `space:dd-17` | ev **59** — 18:10:02.864Z | "Planetary Moons" | ev **67** — 21:08:03.945Z | "TSMC's Global Fab Buildout" |
| `space:dd-18` | ev **61** — 18:10:32.202Z | "Orbital Period Scaling" | ev **69** — 21:08:42.855Z | "Customer Diversification Challenges" |
| `find:dd-16-1` | ev **58** — 18:10:02.863Z | "Findings — How do the physical and orbital properties o…" | ev **66** — 21:08:03.943Z | "Findings — What are the estimated global GDP and equity…" |
| `find:dd-17-1` | ev **60** — 18:10:32.202Z | "Findings — What explains the steep increase in moon cou…" | ev **68** — 21:08:42.854Z | "Findings — By 2030, what fraction of TSMC's advanced no…" |
| `find:dd-18-1` | ev **62** — 18:10:46.643Z | "Findings — How does orbital period relate to distance f…" | ev **71** — 21:09:28.658Z | "Findings — What technical and economic barriers prevent…" |

**2.97 hours** between the two waves (ev 56 → ev 65). The astronomy notebooks carry
`:spawned-by {:space "space:cosmos", :prompt …}`; the semiconductor ones carry
`:spawned-by {:space "space:dd-14", …}` — "Taiwan's Foundry Concentration".

**Six ids, and only six.** Every `:put` in the whole 115-event live log was grouped by id: 32
notebook ids have exactly one `:put`, three have two, and the same three-and-only-three pattern
holds for the `find:` documents. Nothing else in the history was ever overwritten.

**Both halves of `:source` collided, and that is the fact the repair turns on.** A distilled fact
records `{:obj <the findings doc> :space <the notebook>}`, and `lineage-sources` puts a notebook's
**cell ids** in scope alongside the notebook id — `find:dd-16-1` is a cell of today's `space:dd-16`.
So these facts sit inside the semiconductor notebook's scope by two independent keys, and
`remembered-context` cites `(or (:obj …) (:space …))`, which is why the agent prints a Jupiter fact
as `⌾ find:dd-16-1`. Rewriting `:space` alone changes nothing a user would see.

---

## 3. The replay: the old form predicts all four collisions

Materializing the first 49 events and re-running `(inc (count (nb/notebooks st)))` at each mint
point, against what the log actually recorded:

```
as-of 49 (what the boot replayed)      objects= 45 spaces=15 tables=13
  space:dd-16 present? false
after ev63+ev64 in the same session    objects= 47 spaces=15 tables=14
  before ev65  spaces=15 -> old form mints space:dd-16   | actual: ["space:dd-16"]
  before ev67  spaces=16 -> old form mints space:dd-17   | actual: ["space:dd-17"]
  before ev69  spaces=17 -> old form mints space:dd-18   | actual: ["space:dd-18"]
  before ev70  tables=14 -> old form mints tbl:extract-14| actual: ["tbl:extract-14-677"]
```

**Four for four.** The two events committed in that same session before the collisions — ev 63
(`note:semis-1`, not a notebook) and ev 64 (`tbl:derived-1`, not a notebook) — are applied so the
counts are the ones the running process actually held, and they are what makes the table id line up
as well as the notebook ids.

The table id is the part that removes any remaining doubt, because it is an **independent** counter
over a **different** kind. `tools/save-table!` mints `"tbl:extract-" + (count (table-objs st)) + "-"
+ (inc (rand-int 9999))`. At ev 70 the visible table count was 14 — and the id committed was
`tbl:extract-14-677`, colliding numerically with the astronomy table `tbl:extract-14-588` from ev
57. Two counters, over two kinds, both off by exactly the events the truncation hid, both landing on
the number the truncated view predicts. The random suffix is the only reason that one did not
overwrite anything.

---

## 4. The fifteen facts

All 15 are Solar-System facts whose `:source` names a semiconductor deep dive. Every one has
`:strength 1`; none carries a `:vec` (`grep -c ':vec' data/memory.edn` = 0 across all 84 lines).
`:ts` is epoch ms as stored, with the UTC reading beside it.

| id | fact (abridged) | `:entities` | `:source :obj` | `:source :space` | `:ts` |
|---|---|---|---|---|---|
| mem-23 | Terrestrial rocky/metallic; gas giants ~90% H/He; ice giants mostly ices | terrestrial planets, gas giants, ice giants, jupiter, saturn, uranus, neptune | `find:dd-16-1` | `space:dd-16` | 1784830205451 · 18:10:05.451Z |
| mem-24 | Diameters 4,879–12,756 km vs 120,536–142,984 km vs ~49–51,000 km; densities | terrestrial planets, gas giants, ice giants | `find:dd-16-1` | `space:dd-16` | …452 |
| mem-25 | Orbits 57.9–227.9 M km vs 2,872–4,495 M km; periods 88 d – 164 y | terrestrial planets, ice giants, sun | `find:dd-16-1` | `space:dd-16` | …453 |
| mem-26 | Terrestrials have few/no moons and no rings; giants have both | terrestrial planets, gas giants, ice giants | `find:dd-16-1` | `space:dd-16` | …454 |
| mem-27 | Gas giants rotate ~10 h, ice giants ~16–17 h; Uranus tilt ~98° | gas giants, ice giants, uranus | `find:dd-16-1` | `space:dd-16` | …455 |
| mem-28 | Giants 14–318 Earth masses → Hill spheres 35–60× larger | jupiter, saturn, uranus, neptune, earth | `find:dd-17-1` | `space:dd-17` | 1784830234578 · 18:10:34.578Z |
| mem-29 | Neptune's Hill sphere largest (86.9 M km) at 5% of Jupiter's mass | neptune, jupiter, sun | `find:dd-17-1` | `space:dd-17` | …578 |
| mem-30 | Mercury and Venus have Hill spheres under 1 M km | mercury, venus | `find:dd-17-1` | `space:dd-17` | …579 |
| mem-31 | Earth's Moon formed from the Theia impact, not capture | earth, theia | `find:dd-17-1` | `space:dd-17` | …580 |
| mem-32 | Phobos and Deimos are captured asteroids | mars, phobos, deimos | `find:dd-17-1` | `space:dd-17` | …581 |
| mem-33 | Orbital period increases with distance, per Kepler's Third Law | kepler's third law | `find:dd-18-1` | `space:dd-18` | 1784830248504 · 18:10:48.504Z |
| mem-34 | Kepler's Third Law: T² ∝ a³ | kepler's third law | `find:dd-18-1` | `space:dd-18` | …505 |
| mem-35 | Mercury's period is 88 Earth days, closest to the Sun | mercury, sun | `find:dd-18-1` | `space:dd-18` | …506 |
| mem-36 | Neptune ~165 Earth years, farthest classical planet | neptune, sun | `find:dd-18-1` | `space:dd-18` | …507 |
| mem-37 | Period–distance is a power law with exponent 1.5 | *(none)* | `find:dd-18-1` | `space:dd-18` | …508 |

### They were filed correctly at the time

**This is the sentence the whole repair rests on.** `distill!` (server.clj) takes `space` straight
from its caller and writes `{:obj obj-id :space space}` — the notebook the flow ran in, at the
moment it ran. At 18:10:05.451Z `space:dd-16` **was** "Inner vs. Outer Planets", created 35 seconds
earlier. Nothing mis-filed anything. The facts are the last correct record of three notebooks that
were destroyed three hours later.

The timing proves the coupling. `distill!` runs in a `future` fired immediately after the findings
commit, so the first fact of each group lands a couple of seconds after the `:put` it was distilled
from:

| findings `:put` | first fact | delta |
|---|---|---|
| ev 58 `find:dd-16-1` (astronomy) | mem-23 | **2.588 s** |
| ev 60 `find:dd-17-1` (astronomy) | mem-28 | **2.376 s** |
| ev 62 `find:dd-18-1` (astronomy) | mem-33 | **1.861 s** |
| ev 66 `find:dd-16-1` (semis) | mem-42 | 3.071 s |
| ev 68 `find:dd-17-1` (semis) | mem-47 | 2.666 s |
| ev 71 `find:dd-18-1` (semis) | mem-52 | 2.179 s |

The same 1.9–3.1 s signature on both waves. Ten facts now share each `find:dd-N-1` — five from a
document about planets and five from a document about chips — and the only thing separating them is
three hours on the clock.

---

## 5. What was ruled out

A disproved hypothesis is a result. Three were tested and all three failed.

### `distill!`'s `space` argument — disproved

The hypothesis: an astronomy flow ran while a semiconductor notebook was the "current" space, so
`distill!` was handed the wrong id. It cannot happen. `distill!` is called from exactly three sites
— `research!` passes the notebook it just committed the findings into, `ask!` passes the open space,
`draft!` passes the notebook it drafted for — and every one of them passes the id it is already
writing substrate events against. There is no path that files a fact against a notebook other than
the one the flow ran in. Confirmed independently by the timing table above: each group's facts
follow *that group's* findings commit by two seconds.

### The 2026-08-05 repair re-associating something — disproved

The hypothesis: the repair that recovered the 31 events rewrote provenance while it was in there.
Diffing the repaired `data/substrate.edn` against `data/substrate.edn.bak-2026-08-05` line by line,
**only three lines differ — 46, 50 and 70** — and every difference is a column keyword normalized by
`sub/col-kw`:

- line 46, `tbl:extract-12-484`: `:Siemens EDA "13%"` → `:siemens_eda "13%"`
- line 50, `tbl:extract-13-7202`: `:Key Proponent(s)` → `:key_proponent_s`
- line 70, `tbl:extract-14-677`: `:Category`/`:Barrier`/`:Severity` → `:category`/`:barrier`/`:severity`

No id changed, no `:source` changed, no event was added, removed or reordered, and every
`:ts` is untouched. The collision predates the repair by 12 days; the repair is what made it
*visible*, by letting the full fold run for the first time.

Line 46 is worth its own note, because it is the corruption with no exception at all: `:Siemens EDA
"13%"` reads back as the key `:Siemens` with the value `EDA`, and every pair after it slides by one.
That line parsed. It just meant something else.

### Deletion-driven collision — disproved

The hypothesis `fresh-dd-id`'s docstring anticipates: a count-based id collides after something is
deleted. Plausible, and not what happened here. **There are zero `:delete` events in the entire
115-event live log.** Nothing was ever deleted from this store. The count did not fall because
objects went away; it fell because the loader stopped reading.

That distinction matters for Task 1. `fresh-dd-id` fixes the deletion case — it takes max+1 rather
than count+1 — but it asks the *same* store for both the maximum and the existence check, so a
truncated view would still defeat it. What actually holds the line here is bug (2) being closed, not
bug (3).

---

## 6. What survives, and what does not

### Gone

The three notebook objects. Their `:put`s were overwritten and there is no `space:dd-16` in any
state that is not the semiconductor one. Reconstructable **only** from the log:

| from | title | intent | `:spawned-by` |
|---|---|---|---|
| ev 56 | Inner vs. Outer Planets | "Compare the terrestrial and giant planets across size, composition, and orbital characteristics." | `{:space "space:cosmos", :prompt "How do the physical and orbital properties of terrestrial planets differ from gas and ice giants?"}` |
| ev 59 | Planetary Moons | "Explore the distribution of moons across planets and why giants dominate the moon count." | `{:space "space:cosmos", :prompt "What explains the steep increase in moon counts from terrestrial to giant planets?"}` |
| ev 61 | Orbital Period Scaling | "Investigate how orbital period scales with distance from the Sun and the implications for planetary years." | `{:space "space:cosmos", :prompt "How does orbital period relate to distance from the Sun across the solar system?"}` |

All three `:cells []` at creation; the findings doc was celled in by the `:assoc` in the same `:tx`
as its `:put`.

### Recoverable

The findings text for two of the three, as the `:value` string of the overwritten `:put`:

| doc | astronomy body | the semiconductor body that replaced it |
|---|---|---|
| `find:dd-16-1` | **1,416 chars** (ev 58) | 3,384 chars (ev 66) |
| `find:dd-17-1` | **2,442 chars** (ev 60) | 2,917 chars (ev 68) |
| `find:dd-18-1` | **0 chars** (ev 62) | 2,129 chars (ev 71) |

> **Correction to the plan.** Task 2 quotes 1,733 and 2,752 chars for these documents. Those are the
> lengths of the whole EDN **log lines** — `:tx` wrapper, ids, titles, escaping and all — which is
> what the first pass of the investigation measured. The document **bodies** are **1,416** and
> **2,442** chars. Use these when restoring; the difference is 317 and 310 characters of envelope,
> not of prose.

### Committed empty, and it matters

**`find:dd-18-1` was committed with `:value ""`.** The raw event:

```clojure
{:op :tx, :events [{:op :put, :id "find:dd-18-1", :value {:id "find:dd-18-1", :kind :doc,
  :title "Findings — How does orbital period relate to distance f…", :value ""}}
  {:op :assoc, :id "space:dd-18", :path [:value :cells], :value [{:ref "find:dd-18-1"}]}],
 :ts 1784830246643}
```

Five facts — `mem-33`…`mem-37` — were distilled from it 1.861 s later. They could be, because
`agent/distill-facts` takes `[context text]` and puts the context in the *system* message: with an
empty `text` the model still saw "The work was about: How does orbital period relate to distance
from the Sun across the solar system?" and answered from its own knowledge. That is exactly what the
five facts look like — Kepler's Third Law twice, Mercury at 88 days, Neptune at 165 years, a power
law of exponent 1.5 — textbook statements with no figure traceable to any table in this workspace,
and `mem-37` carries no entities at all.

This is why **Task 2 restores dd-18's notebook with no findings cell.** Recreating an empty document
to hold five facts distilled from nothing would manufacture provenance the log says never existed.
The notebook is real; the document it briefly pointed at never had contents.

*(Why the old `research!` committed a blank findings note is not established here. Today's
`research!` retries once on a blank tool-loop result and returns `{:error "research came back
empty"}` rather than committing — the comment at that line says the empty final message was a known
failure. Whether that guard postdates 2026-07-23 was not traced; it is not load-bearing for the
repair.)*

### Orphaned, and adoptable

`tbl:extract-14-588` — "Terrestrial vs Gas Giant vs Ice Giant Planet Properties", saved at ev 57
into the astronomy dd-16 — **is still a live object in the store and is referenced by no notebook at
all.** Its cell reference died with dd-16's `:cells` vector when ev 65 replaced the notebook value
wholesale. It is the one visible survivor of the astronomy deep dives, sitting in the workspace for
15 days with nothing linking to it. Task 2 adopts it rather than copying it: the object is already
correct, only its cell is missing.

---

## 7. What is closed, and what is not

### Bug (2) — the truncation — is closed three ways, and a fourth by the engine

| | where | what it stops |
|---|---|---|
| `col-kw` | `substrate.clj` | any column name becomes a keyword that survives a `pr-str`/`read` round-trip |
| `safe-event` | `substrate.clj` | normalizes every event's keys and **throws** rather than write one that still cannot be read back |
| `load-events` | `substrate.clj` | line-oriented: a bad **middle** line is skipped with a message naming the line number; only a bad **last** line salvages quietly, which is the case the original `catch` was actually for |
| Datalevin | `dlv.clj` | there is **no `pr-str` anywhere in the write path** — events serialize with nippy, so a half-written or unreadable record cannot exist |

The live store is Datalevin, so the failure that started this cannot be expressed there. One
asymmetry, already documented in `loci/dlv.clj` and out of scope for this plan: Datalevin runs
`normalize-keys` but **not** `safe-event`, so its log may hold values the EDN log cannot express.
That is a hazard on a *rollback* to `PersistentStore`, not on the path that broke.

### Bug (3) — count-based ids — is closed for `space:dd-` only

`fresh-dd-id` (server.clj) takes `1 + max` over `^space:dd-(\d+)$` and then loops while the id
exists. That closes the deletion case the old form failed. It does **not** make a stale view safe,
because it asks the same store for both the max and the existence check.

Still count-based in `src/` today:

| site | id minted | guard |
|---|---|---|
| **`server.clj` `compute!`** | **`fn:<src>-<(inc nf)>`** | **none** |
| **`server.clj` `compute!`** | **`tbl:derived-<(inc nt)>`** | **none** |
| `server.clj` `import-csv!` | `tbl:csv-<count tables>-<rand 1..9999>` | random suffix |
| `tools.clj` `save-table!` | `tbl:extract-<count tables>-<rand 1..9999>` | random suffix |
| `tools.clj` `do-query` (`save_as`) | `tbl:derived-<count tables>-<rand 1..9999>` | random suffix |

The three with a random suffix are the shape that saved `tbl:extract-14-677` from destroying
`tbl:extract-14-588` — a 1-in-9,999 escape, not a design, but an escape.

`compute!`'s two have **neither a random suffix nor an existence check**, and they land via `:put`.
That is the exact shape that destroyed three notebooks, with the guard removed. Two computes running
concurrently both read `nt`, both mint the same id, and the second `:put` erases the first with the
log saying the work happened and the world saying it did not — the same measurement
`materialize-entity!`'s monitor already records for `next-id`.

It does not collide today: 22 tables live and only `tbl:derived-1` exists, so the next `compute!`
mints `tbl:derived-23`.

**And `server.clj` already mints `tbl:derived-` through `next-id` elsewhere.** Two rules for one
prefix, in one file, one of them max+1 and the other count+1. Whatever else is arguable, that is not.

---

## 8. Serbia and World economies: untested, not broken

The entities design named `space:serbia` ("Serbia — open economic data") and `space:pub-econ`
("World economies — output and prices") as its motivating roots, and the recall measurement found
they own **zero facts between them**. Confirmed, and nothing is preventing it:

- `space:serbia` — **1 cell**, `{:ref "doc:serbia-econ"}`. No `find:`, `note:` or `draft:` object
  with a `serbia` stem exists anywhere among the store's 89 objects.
- `space:pub-econ` — **2 cells**, an imported CSV table and a provenance note. Same: nothing derived.
- Neither has a single child. Nothing in the store carries `:spawned-by {:space "space:serbia"}` or
  `{:space "space:pub-econ"}`.

Facts exist only where `distill!` ran, and it runs from `research!`, `ask!` and `draft!`. None has
ever run in either notebook.

**The control proves the mechanism works.** `space:pub-geo` ("World geography — countries") also owns
**zero facts of its own** — and it holds **16** through four researched children (`space:dd-22` 5,
`space:dd-27` 3, `space:dd-28` 3, `space:dd-32` 5), every one of them reachable because
`lineage-sources` walks descendants. Serbia and pub-econ have no children and no findings, so there
is nothing to reach.

Press Research once in `space:serbia` and it owns facts like any other notebook. **Untested, not
unfixable-by-design.** Nothing to repair.

---

## Verification

```
$ md5sum -c /tmp/loci-misfiled-investigate/before.md5
/home/damirk/src/loci/data/memory.edn: OK
/home/damirk/src/loci/data/substrate.edn: OK

$ ls -ld data/substrate
drwxr-x--- 1 damirk damirk 46 Aug  6 06:40 data/substrate      # mtime unchanged
```

`data/memory.edn` = `1999a9d108df48b17ccbb51291a0a955`, `data/substrate.edn` =
`e7a6eecdff7bb44ad6b959bb1a7fb7c6`, both before and after. `data/substrate/` was never opened at the
repo path. No file under `src/` or `test/` was modified to produce this document.

Corpus figures quoted above, measured on the copy: **115** events and **89** objects in the live
Datalevin store; **82** lines in the repaired `data/substrate.edn` and **80** in the
2026-08-05 backup; **35** notebooks, highest `space:dd-34` (so `fresh-dd-id` gives `space:dd-35`);
**22** tables; **zero** `:delete` events; **84** lines and **80** distinct facts in
`data/memory.edn`, **0** of them carrying a `:vec`.

---

## What surprised me

1. **The facts were never wrong.** The instinct on seeing "Solar-System facts in a semiconductor
   notebook" is that something mis-filed them. Nothing did. `data/memory.edn` is the only file in
   this workspace that recorded what those three notebooks were about and survived — the notebooks
   themselves did not.
2. **The table id.** Two independent counters, over two different object kinds, both off by exactly
   the events the truncation hid. The notebook ids alone would have been a strong hypothesis;
   `tbl:extract-14` twice is what makes it a demonstration.
3. **How quiet every step was.** A column name is not an error. A `catch` around a reader is not an
   error. A count is not an error. A `:put` onto an occupied id is not an error. Four ordinary
   decisions composed into 31 events vanishing and three notebooks being destroyed, and the only
   reason anyone found out is that an unrelated change to entity scoring started ranking Jupiter
   above TSMC.
4. **`find:dd-18-1` was empty.** Five facts distilled from a document with no contents, because the
   distiller reads the prompt from the system message. They are not wrong — Kepler's Third Law is
   Kepler's Third Law — but they are facts about the world rather than facts about this work, and
   nothing in the record said so until someone counted the characters.
