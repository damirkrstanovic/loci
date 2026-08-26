# Replacing Tavily: what SearXNG, reading the page, and a headless browser each bought

Date: 2026-08-26
Status: **a note, not a spec.** Measured, then called off — Tavily stays. Nothing in `src/`
changed to produce this, no spec was written and no plan was executed. This is the record of why
the swap was not made, so that if anyone picks it up again they start from numbers instead of
from the 2026-08-07 spike's single query.

---

## The one-paragraph version

SearXNG finds the right pages and describes them in 160 characters. Against Tavily's five
~1,100-character extracts it answered **15 of 21** real research questions to Tavily's **20**,
and on 3 of the 5 it missed, **its results contained Tavily's answer-bearing URL** — the page was
found, the snippet was empty. So the deficit is text, not retrieval, and the fix is to read the
page. Reading works: a plain HTTP GET plus Readability4J turns **141 of 289** real result URLs
into a median of **10,451 characters** of article text, roughly nine times what Tavily's extract
carries. The other half does not yield, and no browser fixes it: **Lightpanda recovered 8 of 147
failures**, and real headless Chromium got **HTTP 403 on 45 of the 61 worst** — the same
publishers refuse a real browser. What stops loci reading those pages is IP reputation and
paywalls, not JavaScript. The work needed to beat Tavily is therefore real but unglamorous
(fetch, extract, PDF, chunk, rerank, and honest reporting of blocked pages), and it is not
what today was for. Tavily keeps working; this is deferred, not rejected.

---

## What was measured, and against what

The query set is **21 real research questions**, recovered from the deep-dive spaces'
`:spawned-by :prompt` in the live substrate — the full text, not the 44-character findings title.
Semiconductors, Taiwan-Strait risk, World Bank income classification, solar-system properties.

**Fidelity note that changes the result:** loci never sends the research prompt to a backend.
`research!` hands the prompt to the model, which picks a `web_search` string from it. So each
prompt was first turned into a search string by the model, exactly as the agent does, and the
backends were compared on *those*. Judging, though, was against the **original question** — the
findings note has to answer that, not the search string.

A throwaway SearXNG ran in Docker on `localhost:8888` with the two settings the 2026-08-07 spike
identified as necessary: `search.formats: [html, json]` and `server.limiter: false`.

---

## 1. The four variants

**A** = SearXNG top 5 snippets · **B** = top 10 · **C** = all results reranked by `rerank-bge-m3`
on `lizard10.local`, top 5 · **Tavily** = shipped config, `max_results 5`.

Shape, averaged over 21 queries:

| | results | chars/result | chars/query | unique domains | latency |
|---|---|---|---|---|---|
| Tavily | 5 | 1,137 | 5,683 | 69 | 1.68 s |
| A | 5 | 170 | 848 | 79 | 1.03 s |
| B | 10 | 171 | 1,712 | 130 | 1.03 s |
| C | 5 | 174 | 870 | 79 | 1.39 s |

Whether the returned text carries what the question needs, judged per item:

| | queries answered | mean rank of first useful | useful items/query |
|---|---|---|---|
| Tavily | **20 / 21** (0.95) | 1.40 | 2.90 |
| A | 15 / 21 (0.71) | 2.40 | 1.48 |
| B | 15 / 21 (0.71) | 2.40 | 2.38 |
| C | 14 / 21 (0.67) | **1.71** | 1.86 |

**B is dead.** B is A plus five more results and it rescued **zero** queries — same 15/21, same
mean rank. Breadth is not the lever: the answer is in SearXNG's top 5 or in none of its 20–30.
This killed the recommendation made one message earlier in the same session, which had argued
for B on exactly that theory.

**C is a real effect pointed at the wrong text.** Rerank moved the first useful result from rank
2.40 to 1.71 — it orders honestly — but it *lost* a query by dropping an answer-bearing item out
of the top 5. On 170-character snippets it has too little to be safe. The reranker belongs on
passages, which is the phase that was never built.

---

## 2. SearXNG's engines collapse under agent load

After **four queries in about thirty seconds** from a fresh container on a residential IP:
`brave` and `wikipedia` suspended on "too many requests" (180 s), `duckduckgo` and `startpage`
on CAPTCHA (3,600 s). Queries 5–21 all ran on `google cse` alone, which is why their result
count pins to exactly 20.

Conditioned on engine health: with all engines alive (**n=4**, so suggestive only) A/B/C hit
**4/4, level with Tavily**; single-engine they fall to 0.65/0.65/0.59 against Tavily's 0.94.
**The 0.71 above therefore measures a rate-limited SearXNG, not SearXNG.** That confound was
never resolved — settling it needs a paced re-run after the CAPTCHA suspension expires.

Independently corroborated: a [published test](https://apiserpent.com/blog/searxng-self-hosted-serp-api-tested)
as a SERP API from one residential IP found exactly 1 of 4 engines usable (different survivors,
same shape) and concluded it "behaves like a DuckDuckGo proxy, not a Google API". Their
aggregation overhead of 0.74–0.89 s matches the 1.03 s measured here.

---

## 3. Reading the page — the premise, confirmed

289 unique URLs, every one returned by Tavily or SearXNG for the 21 questions. Plain HTTP GET
with a Chrome User-Agent, 15 s timeout, then Readability4J (`net.dankito.readability4j` 1.0.8,
jsoup-based, added to the running REPL with `clojure.repl.deps/add-lib` — **not** to `deps.edn`).

| | |
|---|---|
| genuinely readable articles | **141 / 289 (48.8%)** |
| median extracted text | **10,451 chars** (p25 5,136 · p75 23,314 · max 438,543) |
| vs Tavily's per-result extract | 1,137 chars → **~9× more text** |
| mean latency | 2.4 s/URL |

The 148 failures: **77 blocked** (75 of them hard `403`), 24 thin/JS-rendered, 20 HTTP errors
(19 of which are `facebook.com` returning `400`), 19 PDFs, 6 network errors.

The p75 of 23,314 and max of 438,543 characters are why chunk-and-rerank is not optional: one
page can be forty times the entire context Tavily currently supplies.

---

## 4. Lightpanda: 8 of 147

[Lightpanda](https://lightpanda.io/) — headless browser in Zig, V8 for JS, no rendering engine,
AGPL-3.0, 0.3.7, official image 78.6 MB. Its `fetch --dump markdown` is genuinely attractive: one
subprocess call doing fetch, JS execution and extraction, plus `--obey-robots`. Run against all
147 tier-0 failures:

| verdict | n |
|---|---|
| **article** | **8** |
| bot-check interstitial | 45 |
| login / paywall wall | 16 |
| empty | 30 |
| error | 12 |
| blank output | 35 |

**5.4% of failures; +2.8 points of total coverage. Zero of 19 PDFs.**

Two corrections were needed to reach that number, both recorded because they are the failure mode
of this kind of test:

1. **The first scoring said 79%.** It counted any output over 500 characters as a recovery, and
   Cloudflare's *"Performing security verification… this website uses a security service to
   protect against malicious bots"* page is ~900 characters. Every number here is a model verdict
   on the actual text, not a length threshold. This is precisely the soft failure the
   `searxng-mcp` author warns about — a fetcher that returns success with a challenge page is how
   a confident, sourceless findings note gets written.
2. **The first run under-configured it.** `--wait-until load` with the default 5 s under-serves
   JS-rendered pages, which is Lightpanda's actual specialty. The 24 thin pages were retried with
   `--wait-until networkalmostidle --wait-ms 15000`: 12 outputs then cleared 1,500 characters, but
   judged, still **2 articles** — the rest bot-checks, login walls and navigation.

The mechanism is stated in its own `--fetch` help: `--user-agent` *"Must not impersonate other
browsers; any value containing 'Mozilla' is forbidden."* It announces `Lightpanda/X.Y` by policy,
and Cloudflare-class services challenge it on sight. `--http-header` would override the UA, but
see the control below — the UA is not the binding constraint.

---

## 5. The control: a real browser does no better

Real headless Chromium (playwright-core 1.62 driving the system browser) over the 61 pages
Lightpanda lost to bot-checks and login walls:

- **45 returned HTTP 403 to real Chrome too** — ScienceDirect, Wiley, ResearchGate, MDPI,
  Stimson, VisualCapitalist
- 13 articles recovered, **every one of them `facebook.com`**

A real browser's entire advantage over Lightpanda on this corpus is Facebook posts. The blocking
is at IP-reputation, TLS-fingerprint and challenge level, not user-agent level, and the blockers
are academic publishers whose content is paywalled anyway.

**Cheaper than any browser tier, on this same corpus:**

- **PDF text extraction: 19 URLs (6.6%)** — more than double Lightpanda's yield, for one JVM
  library, no container, no beta dependency.
- **A fetch-stage domain skiplist** (facebook 19, quora 3): 22 URLs no fetcher should spend time
  on, and 16 of the 19 Facebook URLs are login walls even to real Chrome.

---

## What these numbers establish, and what they do not

**They establish** that on loci's own research URLs the obstacle is not JavaScript, so a browser
of any kind buys almost nothing; that reading the page is worth ~9× the text on half the corpus;
and that breadth (B) and snippet-reranking (C) are both dead ends.

**They do not establish** that SearXNG is worse than Tavily at retrieval. The 0.71 hit rate was
measured on a SearXNG whose engines had collapsed, and the four healthy-engine queries went 4/4.
That is the open question any resumption must settle first.

**Scale caveat.** 289 URLs from 21 questions on one residential IP, weighted toward academic
publishers and news. A corpus of JS-heavy app-like sites — Lightpanda's target market — would
plausibly reverse its result. What is measured here is loci's corpus, not the web.

---

## Method

Harness lived in the session scratchpad (ephemeral, not preserved): a search comparison over the
21 queries, a tier-0 fetch/extract pass over the 289 URLs, a Lightpanda pass over the 147
failures, a Chromium control over 61, and a judge pass classifying every fetched text as
`article` / `botcheck` / `login` / `error` / `empty`. All LLM judging went through
`loci.agent/chat` with `:json? true`. The substrate was read, never written. Readability4J
entered the running REPL via `add-lib` and is **not** a dependency of this project.
