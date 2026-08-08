# loci — the memory pane tells you what it knows about itself

Date: 2026-08-08
Status: proposed

## Goal

The recall layer now knows a great deal about its own condition — which retriever found a
fact, how many facts await embedding, whether merging is running, what a fact absorbed. **None
of it is visible.** `grep` finds no `via`, `awaiting`, `degraded` or `merged-from` anywhere in
`resources/public/index.html`.

That gap matters more than it sounds. If you switched to `embed-bge-m3`, merge would refuse
(no calibrated threshold), you would get one line on stderr at startup, and thereafter your
memory would silently stop deduplicating with nothing in the app to say so.

## Decisions

- **One status line, always present.** Not a badge that appears only when something is wrong —
  a line that always says what the memory layer is doing, so "everything is fine" is a thing
  you can *read* rather than infer from an absence.
- **Provenance only when it means something.** `:via` exists only for a query — a browse
  ranks nothing, so nothing was "found by" anything. Showing a marker on a browse would be
  decoration pretending to be information.
- **A merge is auditable in the pane, not just in the file.** The merge design promised
  `:merged-from` makes a merge reversible and inspectable; today you would have to read
  `data/memory.edn` to see one.
- **No new gesture.** The pane already opens from the ⌾ memory button and searches with a
  field. This adds information to what is there; it does not add a mode, a filter or a tab.
- **Nothing here changes what is recalled.** Display only.

## 1. The status line

One line under the search field, in the pane's existing mono/muted register. Exactly one of:

| condition | line |
|---|---|
| healthy, all embedded | `embed-qwen3-4b · 80 facts, all embedded` |
| healthy, backlog | `embed-qwen3-4b · 12 of 80 awaiting embedding` |
| no embedder | `no embedder configured — recall is by words only` |
| embedder unreachable | `embedder unreachable — showing word matches only` |
| merge refused | `… · merge paused: embed-bge-m3 has no calibrated threshold` |

The last is appended to whichever of the first four applies, because merge state is orthogonal
to embedding state. **A number and a model name always appear together** — `12 awaiting` with
no model reads like a backlog, when with no embedder configured it means the feature is off
and every fact awaits one forever.

## 2. How each fact was found

With a query, each fact carries a small marker: `words`, `meaning`, or `words + meaning`. That
is `:via`, which the fusion step already returns and the payload already carries.

This is the one thing that tells you whether the semantic half is earning its keep. A corpus
where every hit says `words` means the embedder is adding nothing; one where hits say
`meaning` alone means it is finding what substring search could not.

Without a query: no marker.

## 3. What a fact absorbed

A fact that has absorbed others shows `+2 merged` beside its strength dots. Clicking reveals
the absorbed texts and their ids, so you can see what was folded together and judge whether it
should have been.

**This is the check on a threshold being wrong.** The calibration erred high deliberately, but
a wrong merge is recoverable only if someone can see it happened.

## 4. What the server must add

`memory-payload` already carries `:facts` (with `:via`, `:merged-from`, `:strength`,
`:source`, `:score`), `:awaiting` and `:embedding`. Two gaps:

- **`:degraded`** — `recall` reports degradation in the returned vector's *metadata*, which
  cannot survive JSON. It must become a plain key.
- **`:merge`** — `{:threshold 0.88}` when merging, or `{:refused "…"}` when the configured
  model has no calibrated threshold. The refusal message already exists in
  `merge-similar!`; it just never leaves the server.

## 5. Testing

**Clojure:** the payload carries `:degraded` as a plain key when the embedder is unreachable;
`:merge` reports the threshold for a calibrated model and a refusal for an uncalibrated one;
neither key changes the response when everything is healthy and configured.

**Browser:** the status line renders each of the five conditions from a stubbed payload —
asserted on **text content**, not on class names; a fact with `via: ["semantic"]` shows
`meaning` and one with both shows `words + meaning`; a browse (no query) shows no marker at
all; a fact with `merged-from` shows the count and reveals the absorbed text on click; a fact
without it shows nothing extra.

The pane's judgement about whether a merge was *correct* is not testable and is not tested.
What is tested is that a merge is visible at all.

## Out of scope

- Undoing a merge from the pane — the data supports it; the gesture is a separate decision
- Editing or deleting a fact by hand
- Any change to recall, fusion, merge or scoping
- Showing vectors, scores beyond the existing relevance number, or chunk state (that is LEAP's)
