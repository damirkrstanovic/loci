# loci

A humane, Raskin-faithful interface for the agent age — *agent-as-verb, not agent-as-app*.
One substrate, one LEAP gesture, content stored once and **molded** on demand.

This repo is the Clojure build. (The earlier single-file interaction prototype is `index.html`.)

## Architecture (in dependency order)

| Layer | Role | record / recall |
|---|---|---|
| 1. Event log | deterministic, reversible — durable on Datalevin (embedded LMDB) behind the `Store` seam | **record** |
| 2. Content store | tables, documents, blobs — stored canonically, `datafy`/`nav`-able | **record** |
| 3. Recall / AI-memory | `loci.memory` — Clojure-native engine (keyword+entity+recency+strength), persisted, agent-written | **recall** |
| 4. Mold layer | views molded per object, by user *or* agent (Clerk viewer registry) | both |
| 5. Shell | spaces + LEAP | — |

The **mold layer** (`loci.mold`) is built first and is intentionally UI-free: a viewer is
data (`{:id :label :pred :render}`), the registry is data, and a view's `:render` returns
plain Clojure data that any target (Clerk, Portal, a terminal) can draw.

The **recall layer** is a Clojure-native engine (`loci.memory`) behind the
`loci.mold/Recall` protocol — facts distilled by the agent after every flow,
with provenance, reinforcement and decay. Undo never touches it: undo reverts
the record, not the recall. (A Khora sidecar can still replace it — same seam.)

**Recall is scoped to the notebook you are standing in.** ✦ Ask, ✎ Draft, 🔍 Research
and ✧ Suggest are each given only what was learned in that notebook's *lineage* — the
notebook, its cells, everything it spawned transitively, everything merged into it, and
**the trunk it grew out of**: each notebook on the path up to the root contributes its own
id and its own cells, and nothing else. So a branch remembers what it came from, while the
branches beside it stay invisible — going up never turns into coming back down, which is
the one way this rule breaks. A hub is still told what all its deep dives found. Only
`spawned-by` is followed upward; a merge is downstream of what it combined, not a trunk
those notebooks grew out of. The trade, chosen deliberately: two notebooks about the *same
subject* that were started separately are blind to each other, because lineage is what the
substrate computes and subject is what tags record. A brand-new notebook is told nothing,
which is a normal state and not an error. With no notebook open at all, recall is global —
matching the document context, which already falls back to the whole workspace in that
case. `GET /api/memory?q=&space=` browses that same scope, on purpose, and so it widened
with it: what the pane shows you is what the agent is working from.

## Run

```bash
# the loci shell — Clojure substrate served to a web frontend (layer 5)
clojure -M:serve        # then open http://localhost:7777

# headless proof of layers 1/2/4 — no browser
clojure -M:demo

# Clerk notebook (an alternative render target)
clojure -X:start        # starts Clerk + opens notebooks/loci.clj

# the unit tests — Clojure: substrate, notebook, memory, tools, server
clojure -M:test

# the browser tests — Node: the shell itself, driven headless
npm install            # once; pulls playwright-core, downloads no browser
npm run test:browser
```

### Packaged

```bash
# a single jar that runs from anywhere
clojure -T:build uber
java -jar target/loci-standalone.jar          # PORT and LOCI_DATA are honoured

# or in a container, with the substrate on a volume
docker build -t loci .
docker run -p 7777:7777 -v loci-data:/data loci
```

The jar carries `Enable-Native-Access: ALL-UNNAMED` in its manifest, standing in
for the `--enable-native-access=ALL-UNNAMED` that `deps.edn` supplies per-alias
and that a bare `java -jar` has no alias to get. It is not what makes Datalevin
work: measured on JDK 26, LMDB opens without it and merely warns that a
restricted method was called. What the attribute buys is a quiet startup now,
and a jar that keeps running when a future JDK blocks restricted native access
instead of warning about it. (The JDK honours it only for `java -jar` — launched
as `java -cp loci-standalone.jar loci.server` it is ignored, and the warnings
come back.)

`LOCI_DATA` defaults to a **relative** `data/`, so a jar launched from elsewhere
starts an empty substrate rather than finding yours — the startup line prints
which directory it opened, for exactly that reason. The image declares
`VOLUME /data`, so even an unmounted `docker run` keeps its substrate across
`docker restart`; but that anonymous volume belongs to the container, and
replacing the container (or `--rm`) starts again from the seed. Mount a named
volume to keep a substrate that outlives it.

The container runs as an unprivileged `loci` (uid 10001), not root. `/data` is
chowned to it *before* `VOLUME` is declared, which is what makes a freshly
created volume inherit that ownership — verified by committing an event through
`POST /api/connect` inside the container and finding it still there after
`docker restart`. A `HEALTHCHECK` asks `/api/state` for a 200, so a healthy
container means the JVM is up *and* LMDB opened. It uses bash's `/dev/tcp`
rather than curl, which this base image does not have and which is not worth
installing to check a port; the cost is that it reads the status line only, not
the body. `start-period` is 45 s — measured cold starts on this machine ranged
from about 3 s to 13.6 s, and the margin is deliberate.

**Upgrading a volume that predates the non-root container.** Docker seeds a
volume's ownership from the image once, when the volume is *created*; a volume
made while loci still ran as root keeps its root-owned files forever, and uid
10001 cannot write them. One line fixes it, with the container stopped:

```bash
docker run --rm -v loci-data:/data alpine chown -R 10001:10001 /data
```

Use `-R`: chowning `/data` alone leaves `/data/substrate` root-owned, which
fails in the same place. loci checks both before it opens anything and refuses
to start with a message naming the path, the uid and that command, rather than
handing you an LMDB permission error. It will not chown for you — the same path
may be a bind-mounted host directory, and quietly taking ownership of your own
files is the worse surprise.

**The jar in the image is not portable.** The Dockerfile builds it with
`clojure -T:build uber :slim true`, which excludes the native libraries for
every platform but linux-x86_64: Datalevin ships linux-arm64, macosx-arm64 and
windows-x86_64 alongside it, zstd-jni ships 18 architectures and JNA 24.
Measured 2026-08-07: 51 files, 80.7 MB → 63.9 MB of jar, 445 MB → 428 MB of
image. That is a smaller saving than the uncompressed `unzip -l` sizes
(44.9 MB) suggest, because the jar stores them deflated — 17.0 MB compressed is
what actually leaves.
Nothing checks the platform at runtime — a slim jar on arm64 or macOS fails
when Datalevin first loads libdtlv, not at startup — so the manifest records
which kind it is:

```bash
unzip -p target/loci-standalone.jar META-INF/MANIFEST.MF | grep Loci-Platform
# Loci-Platform: linux-x86_64   (slim)   or   Loci-Platform: any   (portable)
```

Plain `clojure -T:build uber` still builds the portable jar, and that is the
default; only the Dockerfile opts in.

**Configuration is read from the environment first, then from files in the working
directory** — and a container has neither of those files, because `.dockerignore`
deliberately keeps them out of the build context:

| variable | falls back to | then to |
|---|---|---|
| `LOCI_LLM_ENDPOINT` | `.llm-endpoint` | `https://api.deepseek.com/chat/completions` |
| `LOCI_LLM_API_KEY` | `DEEPSEEK_API_KEY`, then `.llm-key`, then `.deepseek-key` | *unset — the agent refuses with "no LLM key"* |
| `DEEPSEEK_MODEL` | `.deepseek-model` | `deepseek-v4-flash` |
| `SEARCH_API_KEY` / `TAVILY_API_KEY` | `.tavily-key` | *unset — research falls back to no web search* |
| `LOCI_EMBED_ENDPOINT` | `.embed-endpoint` | *unset — semantic recall is off; recall stays lexical* |
| `LOCI_EMBED_MODEL` | `.embed-model` | `embed-qwen3-0.6b` |
| `LOCI_EMBED_API_KEY` | `LOCI_LLM_API_KEY` | *unset — sent with no `Authorization` header* |
| `LOCI_RERANK_ENDPOINT` | `.rerank-endpoint` | *unset — rerank is skipped* |
| `LOCI_RERANK_MODEL` | `.rerank-model` | `rerank-bge-m3` |
| `LOCI_RERANK_API_KEY` | `LOCI_EMBED_API_KEY`, then `LOCI_LLM_API_KEY` | *unset — no header* |
| `LOCI_DATA` | — | a **relative** `data/` |
| `PORT` | — | `7777` |

**Any server that speaks OpenAI's `POST /v1/chat/completions` works** — llama.cpp,
vLLM, Ollama, LM Studio, a gateway — so loci can run entirely on your own hardware:

```bash
LOCI_LLM_ENDPOINT=http://your-box:8080/v1/chat/completions \
LOCI_LLM_API_KEY=whatever-your-server-wants \
DEEPSEEK_MODEL=the-model-your-server-serves \
clojure -M:serve
```

`DEEPSEEK_MODEL` names the model on **whichever** server is configured; the variable name
is historical, kept so existing setups keep working with nothing changed. For the same
reason `DEEPSEEK_API_KEY` and `.deepseek-key` still resolve — `LOCI_LLM_API_KEY` just wins,
so a token for your own server need not be filed under a vendor you are not using. The file
half of that chain is neutral too: `.llm-key` resolves before `.deepseek-key`, the way
`.llm-endpoint` already did for the endpoint. A **blank** value at any of the four steps
counts as unset and falls through to the next, so an empty `LOCI_LLM_API_KEY=` or a
`touch`ed `.llm-key` cannot shadow a key that is actually set below it.

`DEEPSEEK_MODEL` is also the one that fails quietly. A missing key is refused out loud, but
a missing model is not: the container simply runs `deepseek-v4-flash`, which is very likely
not the model your endpoint serves. Pass it whenever you pass the key.

**The embedder takes its own token, and that is the point rather than a nicety.** The obvious
deployment is a hosted chat model beside a local embedder — two operators, two credentials —
so `LOCI_EMBED_API_KEY` need not be filed under the chat provider. `LOCI_LLM_API_KEY` remains
the single-provider shortcut, and rerank falls back to the embed key because it is usually
the same box. An unset key means the request carries **no** `Authorization` header rather
than an empty one: a llama.cpp started without `--api-key` rejects an empty bearer, which
would turn "no key needed" into a 401 that reads like a wrong key. For the same reason an
*empty* value counts as unset — `LOCI_EMBED_ENDPOINT=` in a `loci.env` leaves semantic recall
off rather than aiming loci at an empty URL. The two endpoints are optional independently of
each other: with embed but no rerank, fusion runs and rerank is skipped.

Semantic recall is landing in phases. With an embed endpoint configured, facts are embedded
by a background worker and `recall` fuses cosine with the lexical signals; **semantic merge —
collapsing two facts whose vectors are close enough to be the same fact — is the next phase
and is not implemented yet.** The threshold it will merge above is already measured, and the
warning below is about that number rather than about code you can run today.

**Change `LOCI_EMBED_MODEL` and the merge threshold has to be re-measured.** This is the one
setting that does not take care of itself, so it is worth reading before you change it.

That threshold is a property of **one embedding model** and is not portable to another.
Switching models needs no migration — every fact embedded by the old model becomes pending
again and the next background pass re-embeds it at the new dimension, verified live across
both Qwen models on one memory file — but **the threshold does not follow the re-embedding.**
Nothing breaks, nothing warns, and a number that was right yesterday is quietly wrong today.

Measured 2026-08-07 on the same 80 facts:

| model | dim | threshold |
|---|---|---|
| `embed-qwen3-0.6b` | 1024 | 0.85 |
| `embed-qwen3-4b` | 2560 | 0.88 |
| `embed-bge-m3` | 1024 | *uncalibrated* |

The *global* distributions of the two Qwen models are almost identical — median 0.291 against
0.298, p99 identical at 0.741 — which is exactly what makes carrying the number over feel
safe. **The tail is what moved.** Two genuinely distinct facts, *"The orbital period of a
planet increases with its distance from the Sun, following Kepler's Third Law"* and
*"Kepler's Third Law states that the square of a planet's orbital period is proportional to
the cube of its semi-major axis"*, scored **0.829** under the 0.6b and **0.868** under the 4B.
Under the 0.6b's 0.85 they stay apart; carry that 0.85 to the 4B and they merge, the law's
formulation is gone, and the only trace is a `:merged-from` on the fact that survived.

**The rule merge follows, therefore:** never a threshold as a constant. It looks the
configured model up in that table, and for a model that is **not** in it — `embed-bge-m3`
included — it **refuses to merge and says so**: no fallback to the nearest number, no guess,
nothing merged. An unmerged memory is merely redundant; a wrongly merged one has lost
something.

To re-measure, against your own memory file and with the new model configured:

```bash
LOCI_EMBED_MODEL=your-new-model clojure -M:calibrate data/memory.edn
```

The path is required and has no default, so a calibration run cannot be aimed at your real
memory by accident. It reads and never writes. What comes back is the percentile table over
every pair and the highest-scoring pairs **with their texts** — because the step that decides
the threshold is you reading those pairs and saying, for each, whether the two are the same
fact. Scores alone cannot tell you; the whole failure above is two sentences about Kepler's
Third Law that a cosine cannot separate. Pass your verdicts back from a REPL and it puts the
number above every pair you called distinct:

```clojure
(loci.calibrate/calibrate! "data/memory.edn"
  {:labels {#{"mem-12" "mem-40"} :duplicate
            #{"mem-3"  "mem-71"} :distinct}})
```

It errs high on purpose. The two classes overlap — under the 0.6b, true duplicates at 0.838
and 0.832 sat *above* the false Kepler pair at 0.829 — so no threshold separates them and one
of the two errors has to be chosen. A missed duplicate costs one redundant fact. A wrong
merge destroys a distinct one silently. So the suggestion clears every pair you labelled
distinct, reports how many duplicates that cost, and names them.

Configuration goes in **`loci.env`** — gitignored, and excluded from the build context, so
it is never baked into a layer:

```bash
cp loci.env.example loci.env      # then fill in the key
docker compose up

# the same file without compose
docker run -p 7777:7777 -v loci-data:/data --env-file loci.env loci
```

Never bake a key into an image: a layer that carries one survives `docker push` and every
tag built from it. And do not put `PORT` in `loci.env` — it would move the listener inside
the container without moving the published mapping, leaving something that looks healthy
and answers nothing.

`clojure -M:serve` persists its state under `data/` — the substrate is a
Datalevin (LMDB) store at `data/substrate`, the agent's memory an event log at
`data/memory.edn`. Delete that directory to reset to a clean slate. A pre-2026-08
EDN log at `data/substrate.edn` is migrated with `clojure -M:migrate`, which
imports it verbatim and verifies the two logs materialize to the same state.

**What rollback costs, stated plainly.** The migration never modifies
`data/substrate.edn`, and `loci.substrate/PersistentStore` is kept — it is the
parity reference the test suite holds `DatalevinStore` to, and it is the
rollback. But the rollback only recovers the log as it stood at migration.
The Datalevin write path serializes with nippy and therefore drops
`safe-event`'s pr-str/read round-trip check (see `src/loci/dlv.clj`), so events
written since are not guaranteed to be EDN-expressible, and there is no
`datalevin->edn!` to carry them back. Reverting `content.clj` after a week on
the new store means losing everything committed in that week. That is the
accepted trade: the check existed to stop an unreadable line truncating a text
file, and a KV store has no lines to truncate.

The two suites are deliberately separate: `clojure -M:test` stays fast and needs no
browser, while the browser suite boots its own server against a throwaway substrate — it
never opens `data/`. A failing browser test leaves a screenshot and a console log in
`test/browser/failures/` (cleared at the start of every run), because the defect that
motivated the suite (notebook titles rendering at ~3px when zoomed out) was invisible to
every DOM assertion and only caught by looking.

Zoomed out, the overview groups each notebook with the ones it spawned — the
structure the substrate already computes from `spawned-by` and `merged-from`, rather than
laying every notebook out as a peer. Cards are sized to their own content, the canvas
scrolls, and a focused notebook names its parent, siblings and children in one line under
its intention.

✧ Suggest reads a notebook and proposes two or three questions worth pursuing. You see them
before anything happens: edit the wording, uncheck what you don't want, then choose whether
the answers land in new connected notebooks or as cells in this one. Proposing writes
nothing at all — dismissing leaves the substrate untouched.

LEAP ranks what it finds by what you touched most recently — a notebook counts as
touched when anything inside it is. From the overview it also offers `⊟ Filter the
overview`, which dims everything that does not match and stays until you enter a notebook
or clear it from the breadcrumb.

Notebooks carry tags — the one thing the substrate cannot work out for itself, since
structure is computed but subject is not. The agent proposes them from what is actually in
a notebook and you approve, edit or ignore; each tag records whether you or the agent
asserted it, and proposing writes nothing at all. In the overview a tag chip cycles
include → exclude → neither, and composes with LEAP's filter: a notebook is lit when it
satisfies both.

Each tag carries a colour, so a tag is recognisable before it is read. The first time a tag
is set it takes the least-used ink from a palette of eight; the swatch on its chip opens the
palette, and changing it changes that tag everywhere at once. On a chip the swatch carries
identity and the fill carries state — an included tag fills with its own ink, an excluded
one is struck in clay with its swatch intact. Undoing a tagging removes the tags and leaves
the colour, because a colour is a standing preference about a subject rather than a claim
about a notebook.

The browser suite needs two things beyond `npm install`:

- **Node 22 or newer.** `npm run test:browser` uses `node --test` with a glob, which
  older Node does not understand and reports as a missing file.
- **A Chromium already on disk.** `playwright-core` deliberately downloads no browser.
  The harness looks in `~/.cache/ms-playwright/chromium-*` (put one there with
  `npx playwright install chromium`), then `/usr/bin/chromium`,
  `/usr/bin/chromium-browser`, `/usr/bin/google-chrome`. To use a specific binary, set
  `PLAYWRIGHT_CHROMIUM=/path/to/chrome` — it is an override, not a hint: if it is set
  and the path does not exist the suite fails and names it rather than quietly running
  a different browser.

The suite is offline-safe: `index.html` loads IBM Plex from a CDN, and every network
assertion is filtered to the test server's own origin, so no network is not a red suite.

The shell talks to the Clojure backend over a JSON API — the HTTP boundary is the
substrate/assistance seam. Molding is done server-side by `loci.mold`; the
frontend only lays out the result.

| endpoint | returns |
|---|---|
| `GET /` | the shell |
| `GET /api/state` | spaces + objects |
| `GET /api/object/:id` | an object's default mold + the "view this as…" menu |
| `GET /api/mold?id=&view=` | the object re-molded by a chosen viewer |
| `GET /api/leap?q=` | incremental find across content + view-verbs |
| `GET /api/undo` | revert the last substrate event |
| `GET/POST /api/notebook` | a notebook's hydrated cells / one cell operation |
| `GET /api/links?space=` | computed connectedness (shares / spawned / lineage) |
| `GET /api/memory?q=&space=` | the agent's memory — browsable, recall-ranked; `space=` scopes it to that notebook's lineage (trunk above, everything below) |
| `POST /api/research` | agent research as a background job — returns a job id |
| `POST /api/suggest` | the agent proposes questions (background job); writes nothing |
| `POST /api/suggest-run` | research the questions you approved, in new notebooks or here |
| `GET /api/job?id=` | poll a background agent job — the shell never holds a long fetch |
| `GET /api/fns?id=` | the ƒ palette for a table: built-in verbs + agent-written functions |
| `POST /api/fn-preview` | run a function over the live rows — before/after, commits nothing |
| `POST /api/fn-apply` | keep the result: derived table + cell, ONE reversible event, `from`/`via` provenance |
| `GET /api/events` | the log, humanely labeled — feeds the ⏱ scrubber |
| any read + `?at=N` | the same payload as-of event N — read-only time travel |
| `POST /api/rerun` | recompute a derived table + everything downstream — one reversible event |
| `POST /api/connect` | a NEW notebook unioning two others — non-destructive, originals intact |
| `POST /api/flow` | the agent plans + runs a multi-step flow (background job) |
| `POST /api/flow-gate` | answer a flow's gate: approve resumes, reject stops |
| `POST /api/tags` | replace a notebook's tags — one reversible event |
| `POST /api/tag-suggest` | the agent proposes tags (background job); writes nothing |
| `POST /api/tag-color` | choose a tag's colour — one reversible event |

## Layout

```
src/loci/substrate.clj   layer 1: append-only event log behind a Store protocol
src/loci/dlv.clj         layer 1, durable: the event log on Datalevin (LMDB) + touch index
src/loci/migrate.clj     one-shot import of the old EDN log, verified by state equality
src/loci/content.clj     layer 2: populated content + viewers; datafy/nav
src/loci/mold.clj        layer 4: viewer registry, mold, Recall protocol (UI-free)
src/loci/fnlib.clj       built-in single-table transforms — the ƒ function palette (UI-free)
src/loci/memory.clj      layer 3: AI-memory engine (Recall protocol, persisted)
src/loci/notebook.clj    notebook = space: cells, cell ops, computed links
src/loci/server.clj      layer 5 backend: substrate + mold served as JSON
resources/public/index.html   layer 5 frontend: spaces + LEAP shell
src/loci/demo.clj        headless walkthrough  (clojure -M:demo)
notebooks/loci.clj       Clerk render target (alternative to the shell)
test/browser/            headless-browser tests for the shell (npm run test:browser)
docs/walkthrough.md      the four demo flows, step by step
```
