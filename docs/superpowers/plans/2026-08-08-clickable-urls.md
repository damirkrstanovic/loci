# A bare URL in agent prose is a link you can click

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax.

**One task.** It commits on its own.

---

## CRITICAL

- **NEVER write to, point anything at, or modify `data/`.** Report `md5sum data/substrate.edn`
  and `data/memory.edn` before and after.
- **Never read, print or edit `loci.env`** — it holds real keys.
- Do not call a real model or embedder; the suites must pass offline.
- No `pkill -f`. **Never overstate what the code does.**

---

## The defect, already traced — do not re-derive it

The user's research note ends in a `## Sources` list of bare URLs. None of them are clickable.

`mdToHtml`'s `inline()` (`resources/public/index.html:1688`) has exactly one link rule:

```js
.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>')
```

`[title](url)` becomes an anchor; a bare `https://…` does not. The research note reaches this
renderer — `research!` saves a `:doc` (`src/loci/server.clj:1311`), `:doc/raw` molds it as
`:kind :text` (`src/loci/content.clj:322`), and the shell's `renderMold` sends `text` through
`mdToHtml` (`index.html:1068`). So the whole Sources list arrives as plain prose.

Measured, by extracting `esc()` + `mdToHtml()` and feeding them the user's list: **1 anchor**
(the one hand-written markdown link in the sample), **0** for the seven bare URLs.

## The fix

Add an autolink pass to `inline()`. Three things make it non-trivial; a version that ignores
them is worse than none:

1. **It must run last, and skip what earlier rules already emitted.** The chain is
   code → bold → em → `[md](link)`. By the time autolink runs, anchors exist. Linkifying the
   text inside an `<a>` (or its `href=`) yields nested garbage, and a URL the author put in
   backticks is code, not a link. So walk the string split on tags, and keep a depth counter
   for `<a>`/`<code>` — a `<code>` inside anchor text must not clear the skip on `</code>`.
   Do NOT reorder the existing rules: `[**bold**](u)` works today only because bold runs first.
2. **The text is already HTML-escaped** (`esc()` runs before the line-walker). So `"` is
   `&quot;`, `&` is `&amp;`. `"https://x"` must not swallow the closing `&quot;`, and a query
   string's `&amp;` must survive into the `href` (the browser un-escapes it on parse).
3. **Trailing punctuation is not part of the URL.** `see https://x.com/page.` ends a sentence;
   `(https://x.com/a)` is parenthesised. But `…/wiki/Foo_(bar)` keeps its *balanced* parens —
   research sources are full of those. Trim entities and `.,;:!?` off the end, then trim `)`/`]`
   only while unbalanced, and repeat until it stops changing.

Emit the same anchor shape as the existing rule (`target="_blank" rel="noopener"`) — the shell
is a single fixed surface, and navigating it away loses the state.

Then `.md a` (`index.html:197`) needs `overflow-wrap:anywhere`: a bare URL is one unbreakable
token and a notebook panel is narrow. Fixing the link while breaking the layout is not a fix.

- [x] **Step 1: Write the failing tests** in a new `test/browser/markdown.test.mjs`, driving the
  real `mdToHtml` in the page via `page.evaluate`. Follow `test/browser/regressions.test.mjs`
  for the harness idiom. Cover:
  - a bare URL in a list item becomes an `<a>` whose `href` is exactly the URL
  - a sentence-final `.` and a wrapping `)` stay out of the `href`; `/wiki/Foo_(bar)` keeps its
  - `&` in a query string: assert on the live DOM's `a.href`, so `&amp;` round-trips to `&`
  - an existing `[title](url)` still renders once — no nested `<a>`, anchor text still the title
  - `[https://x](https://x)`, where the anchor *text* is a URL, does not nest either
  - a URL inside backticks stays inside `<code>` and is not linkified
  - **the escape still holds**: `https://x.com/&quot;onmouseover=&quot;alert(1)` (i.e. the raw
    text `https://x.com/"onmouseover="alert(1)`) cannot break out of the attribute, and a
    `javascript:` URL is not linkified at all
  - **end to end, not just the function**: add a fixture doc to `test/browser/harness.mjs`
    (follow `FLOW_FIXTURE`) holding a `## Sources` list of bare URLs, appended as a cell of
    `space:cosmos`; assert the rendered notebook cell contains a clickable `.md a` with the
    right `href`. This is the test that pins the *wiring*, which is what the user actually hit.

- [x] **Step 2-4: fail, implement, green.** Keep the file's compact style. One comment says why
  the pass runs last and is tag-aware — the next reader will otherwise "simplify" it into the
  inline chain and reintroduce the nesting.

- [x] **Step 5: Prove each test bites.** One batch, distinct sabotages, each reverted after:
  drop the autolink pass (the bare-URL tests must fail); drop the tag-aware skip (nested-anchor
  and backtick tests must fail); drop the trailing trim (punctuation test must fail); drop the
  unbalanced check so every `)` is trimmed (the `Foo_(bar)` test must fail); drop
  `overflow-wrap` — say honestly whether any test catches that, and if none does, say so rather
  than claiming coverage.

- [x] **Step 6: Run both suites** — `clojure -M:test` and `npm run test:browser`. Report the
  actual counts.

- [x] **Step 7: Report** the diff, the numbers, and the md5s. **Do not commit.**
