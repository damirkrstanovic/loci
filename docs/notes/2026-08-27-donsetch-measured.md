# DonSeTch against the 147 pages nothing could read

Date: 2026-08-27
Status: **a note, not a spec.** Nothing in `src/` changed. Tavily still ships. This corrects a
conclusion in `2026-08-26-web-search-backend-measured.md` written the day before, and it exists
so nobody repeats yesterday's search on yesterday's premise.

---

## The one-paragraph version

Yesterday's round tested **browsers** and concluded that no browser fixes the blocked half:
Lightpanda read 8 of 147, and headless Chromium got HTTP 403 on 45 of the 61 worst. That is
right about browsers and wrong about the lever. **DonSeTch read 106 of the same 147**, judged by
the same judge, on the same 1,200-character samples. **79 of the 106 came from tier 1 — plain
HTTP with the TLS fingerprint of real Chrome, no browser at all**, at a median of 1.2 s. The
other 27 came from its browser escalation, and its own trace names the mechanism: **8 pages were
opened by `1+ghost-solve` — the browser cleared the challenge and handed the page back to fast
HTTP**. Of the 45 pages yesterday judged `botcheck`, **38 now read as articles**. Corpus coverage
goes from 141 of 289 to 247 of 289 — 49% to 85%.

---

## What was tested, and why the two numbers compare

The same **147 URLs**: the pages a plain HTTP GET plus Readability4J failed on, out of 289
recovered from real research prompts in the live substrate.

The same **judge**: `spike.judgefetch/judge-all`, the same system prompt, batches of 8, the first
1,200 characters of each sample, classifying `article | botcheck | login | error | empty`. The
judge and the corpus both survived in the running REPL from 2026-08-26, so nothing was rebuilt
and nothing was re-specified.

**DonSeTch** 3.2.3 (Rust, AGPL, `npm i donsetch`, profile `chrome-150`), installed into a
throwaway prefix. Two runs: `--tier 1` over all 147 with 6 workers, and `--tier auto` over the
46 hardest with 2 workers and a 120 s deadline.

## The result

| | articles, of 147 |
|---|---:|
| plain GET + Readability4J (2026-08-26) | 0 — these are its failures |
| **Lightpanda** (2026-08-26) | **8** |
| **DonSeTch tier 1** — HTTP only, no browser | **79** |
| **DonSeTch tier auto** — with browser escalation | **106** |

By what the page was yesterday:

| yesterday's verdict | n | tier 1 | tier auto |
|---|---:|---:|---:|
| `botcheck` | 45 | 17 | **38** |
| not judged | 36 | 26 | 28 |
| `empty` | 30 | 19 | 20 |
| `login` | 16 | 6 | 6 |
| `error` | 12 | 9 | 9 |
| `article` — the 8 Lightpanda read | 8 | 2 | 4 |

## Why it works, which is the finding

**Yesterday tested the wrong lever.** Lightpanda and headless Chromium are both browsers, and a
browser answers "does this page need JavaScript". Most of these pages did not.

DonSeTch's tier 1 sends **the TLS fingerprint of real Chrome over plain HTTP**. Many bot walls
read that fingerprint and never look for JavaScript. So **17 of the 45 bot walls opened with no
browser at all**, in about a second each.

Its browser tier then does something a plain browser does not. The escalation trace over the 29
pages it recovered at `tier=auto`:

| tier reported | n | what it did |
|---|---:|---|
| `ghost-dom` | 14 | rendered the page in the browser |
| `1+ghost-solve` | **8** | **browser cleared the challenge, then HTTP fetched the content** |
| `1(warm)` | 4 | plain HTTP, warmed by a prior browser session |
| `1` | 3 | plain HTTP on a retry |

`1+ghost-solve` and `1(warm)` are the half yesterday had no way to express: the browser exists to
**earn the cookie**, and the cheap HTTP path then reads the page. Twelve of 29 went that way.

**PDFs are a second, separate gain.** 18 of the 19 PDF URLs parsed, median 5,871 characters.
Neither Lightpanda nor Chromium addressed a PDF at all.

## What is still shut, and it is the half yesterday got right

**16 of 147 return nothing**, and **24 more return something the judge calls empty.**

At `tier=auto` the residue is 17 URLs: `sciencedirect.com` (3), `openknowledge.worldbank.org` (2),
`jstor.org` (2), and singletons at SAGE, AGU, Wiley, PMC, IMF, DiVA.

DonSeTch says why, and it is candid: *"tier 2 browser could not solve it — interactive
verification needed; no automated path (by design DonSeTch does not solve captchas)"*. So
**yesterday's second finding stands unchanged**: the last wall is IP reputation and an
interactive captcha, not JavaScript.

## Three things that are not in the headline

- **It is not a superset of Lightpanda.** Of the 8 pages Lightpanda read as articles, DonSeTch
  recovered **4**. Four pages want something it does not do.
- **The test's own request rate is a confound.** Tier 1 made 147 requests in 88 seconds from one
  IP, 8 of them to ResearchGate. Rate is exactly what drops a Cloudflare trust score, so some of
  the residual wall may be self-inflicted. **Untested, and free to test:** re-run the residue in
  serial, one request per domain per 30 s.
- **A captcha solver is the wrong tool for what is left.** A Turnstile token is bound to the IP
  that solved it and to a short window, so a solving service's token fails when presented from
  another IP. The working configuration is a clean residential IP *and* a consistent fingerprint
  *and* interaction — a solver supplies one of three. Most of the residue is academic anyway, and
  **OpenAlex/Unpaywall resolve a DOI to a legal open-access copy**; PMC states its own APIs are
  the only permitted automated path. That is the cheaper and correct route.

## What this does NOT decide

Nothing is adopted. **Tavily still ships.** This measures one tool against one corpus on one day,
and every caveat from the 2026-08-26 note applies to this one too: the judge is an LLM, the web is
live, and both numbers carry the same method error — which is exactly why they compare.

The open question yesterday raised is still the open question: whether loci should read pages at
all, or keep paying for extracts. What changed is the price of reading them. Yesterday it looked
like half the web was unreadable without a browser and unreadable with one. It is 85% readable,
and the cheap tier does most of it.
