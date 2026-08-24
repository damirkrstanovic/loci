# External Sources Phase 3 — Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phase's measurements stop contradicting each other, one duplicated user-visible sentence goes away with the machinery that produced it, the test consolidation lands without losing the one browser test that bites, and the phase's most surprising cost — a preview downloads a fixed 32 MB prefix — gets the test it never had.

**Architecture:** Five independent cleanups on `external-sources-phase-3`, ordered so the two that rewrite `lake.clj` docstrings do not collide. Task 2 removes a function and changes a user-visible message; Task 3 only moves numbers. Tasks 4 and 5 touch test files only.

**Tech Stack:** Clojure 1.12, DuckDB/DuckLake + httpfs, http-kit (test fixtures only), Playwright.

**Review source:** the code review of 2026-08-24 in this conversation. Findings recorded in the tasks that fix them.

---

## Ground rules for every task

Carried from `2026-08-23-external-sources-phase-3.md`, because each records a defect that already happened here.

- **The REPL is the execution surface.** `ToolSearch` query `select:mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888; `:reload` after every edit. Do **not** run `clojure -M -e` to iterate — a cold JVM costs ~25 s. `clojure -M:test` is for one final full-suite run before a commit.
- **Prove every test bites.** Reintroduce the defect, watch the test go red, and **read the failure output** rather than counting failures. A test that passes either way certifies rather than tests.
- **`grep` is unreliable in this environment on files with very long lines** — `resources/public/index.html` in particular. It silently reported zero matches for strings that are present four times. Use `rg`, or `python3 -c "print(open(f).read().count(s))"`.
- **Say what you measured, and which of two things it establishes** — that the mechanism works, or that a cost is real. A 3-row fixture can only ever establish the first.

---

## File structure

| File | Task | Responsibility after this plan |
|---|---|---|
| `src/loci/lake.clj` | 1, 2, 3 | `extension-problem` composes the shared mechanism paragraph between a caller-supplied head and tail; `remote-extension-problem` is gone; the image-size figures point at the Dockerfile instead of restating it |
| `test/loci/lake_test.clj` | 1, 2 | the retracted autoinstall claim is gone; the extension-message tests assert the two real call sites rather than an internal flag |
| `docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md` | 1, 3 | the single home for every measured number in the phase |
| `test/loci/sourcespec_test.clj` | 4 | the consolidation already in the working tree, committed |
| `test/browser/sources.test.mjs` | 4 | the consolidation minus the deletion of the paragraph-shape test |
| `test/loci/source_test.clj` | 5 | `with-stub` records headers; a Range-serving stub and the 32 MB prefix test |

---

## Task 1: three sites still carry a retracted measurement

`11ad7e7` corrected two numbers. Each correction landed in some of its homes and not all of them. Nothing here changes behaviour; all three are prose that a reader will act on.

**Files:**
- Modify: `src/loci/lake.clj:663`
- Modify: `test/loci/lake_test.clj:884-885`
- Modify: `docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md` (the `(b)` paragraph in section 4)

- [ ] **Step 1: Read all three sites before editing any of them.**

```
sed -n '535,550p;655,670p' src/loci/lake.clj
sed -n '875,890p' test/loci/lake_test.clj
rg -n '12\.7' docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md
```

`secret-covers?` (around line 542) is the **correct** one — it says 12.7 ms "was the number here until 2026-08-23" and gives 5.8 / 5.3 ms cold and 2.1 / 1.9 ms after. The other two disagree with it.

- [ ] **Step 2: `lake.clj:663` — `ensure-remote!` still asserts the retracted figure as current.**

It reads:

```
   reach. See `secret-covers?` for the three measurements and for why the check
   costs 12.7 ms rather than a trade.
```

Replace with a pointer that cannot go stale, since the number's home is two functions above:

```
   reach. See `secret-covers?` for the three measurements and for why the check
   is not a trade — it costs about 2 ms against the 6,000 ms it can skip.
```

- [ ] **Step 3: `lake_test.clj:884-885` carries the retracted *explanation*, which is worse than the number.**

It reads:

```clojure
  ;; Measured 2026-08-23 on DuckDB 1.5.5 with no AWS credentials present:
  ;; 10.5 s the first time — the `aws` extension autoinstalls, which is also
  ;; how we know `httpfs` alone is not enough for it — and 6.0 s every time
  ;; after, with `Invalid Configuration Error: Secret Validation Failure:
  ;; during `create``.
```

The autoinstall story is exactly what `11ad7e7` disproved: with `aws` already installed and explicitly `LOAD`ed (46 ms), a cold JVM still paid 10,022 ms on its first `CREATE` and 6,018 ms on its second. Replace with:

```clojure
  ;; Measured 2026-08-23 on DuckDB 1.5.5 with no AWS credentials present:
  ;; 10.5 s the first time and 6.0 s every time after, with `Invalid
  ;; Configuration Error: Secret Validation Failure: during `create``.
  ;;
  ;; The extra four seconds are NOT the `aws` extension autoinstalling — an
  ;; earlier version of this comment said they were. With `aws` already
  ;; installed and explicitly LOADed first (46 ms), a cold JVM still paid
  ;; 10,022 ms on its first CREATE and 6,018 ms on its second, twice. It is a
  ;; once-per-process cost inside the chain's first walk, so an offline image
  ;; pays it too. See `ensure-remote!`'s docstring.
```

- [ ] **Step 4: the spec's `(b)` paragraph describes a docstring that has since been fixed.**

It reads `**(b) `secret-covers?` costs about 2 ms, not 12.7 ms.** Its docstring says 12.7 ms.` — present tense, and no longer true. Change that second sentence to past tense (`Its docstring said 12.7 ms; corrected there on the day, and in `ensure-remote!` on 2026-08-24.`). Leave the measurements themselves exactly as they are.

- [ ] **Step 5: Confirm nothing else disagrees.**

```
rg -n '12\.7|autoinstall' src/ test/ docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md
```

Expected: `12.7` survives only in `secret-covers?` and the spec's `(b)`, both as history. `src/loci/content.clj`'s `12.7` is an unrelated subsystem — leave it. Occurrences of `autoinstall` that describe DuckDB's `autoinstall_known_extensions` setting are a different fact and stay.

- [ ] **Step 6: Commit.** No tests change behaviour here, so a namespace reload plus `(clojure.test/run-tests 'loci.lake-test)` in the REPL is the check.

```bash
git add src/loci/lake.clj test/loci/lake_test.clj docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md
git commit -m "docs: one correction landed in two of its four homes"
```

---

## Task 2: the remote failure says it twice, and the machinery that does it goes

**This is the finding, and it is user-visible.** Measured in the REPL on 2026-08-24, `remote-extension-problem` over a cause from `ensure-extension! "httpfs"` renders:

```
loci cannot read s3://bucket/sales.csv — DuckDB's httpfs extension is not available.

loci cannot load DuckDB's httpfs extension.
  looking in /home/damirk/.duckdb — DuckDB's default, from HOME
  ...
```

Two headlines, back to back, saying the same thing. It is drawn in the ＋ Data panel under `.srcerr`.

The cause is one concern — *what does this failure cost the caller* — implemented twice: `extension-problem` derives `required? (= "ducklake" ext)` to gate two sentences, and `ensure-remote!` catches the resulting `ex-info` and **recomposes the message from `(.getMessage cause)`**, `str/trimr`s a trailing newline the flag leaves behind, and reads the extension name back off `(:extension (ex-data cause))` with a `"httpfs"` fallback.

Giving the two halves to the caller collapses all of it.

**Files:**
- Modify: `src/loci/lake.clj` — `extension-problem`, `ensure-extension!`, delete `remote-extension-problem`, both call sites (`open-lake!`, `ensure-remote!`)
- Modify: `test/loci/lake_test.clj` — the `failed-extension` helper and four tests

- [ ] **Step 1: Capture the ducklake message byte-for-byte BEFORE touching anything.** It must not move.

```clojure
(require '[loci.lake :as lake] :reload)
(spit "/tmp/claude-1001/-home-damirk-src-loci/d0ac7a0e-52a1-45c2-abd4-0fb5d086d2e0/scratchpad/ducklake-before.txt"
      ((deref #'lake/extension-problem) "ducklake" (Exception. "DuckDB's own text")))
```

- [ ] **Step 2: Write the failing test — the rendered remote message names the failure ONCE.**

Add to `test/loci/lake_test.clj`, beside `a-missing-httpfs-refuses-one-source-and-says-so`:

```clojure
(deftest the-remote-refusal-does-not-say-it-twice
  ;; Two message composers, one nested inside the other, drew two headlines for
  ;; one failure — measured 2026-08-24, and drawn in the ＋ Data panel under
  ;; `.srcerr` exactly as composed:
  ;;
  ;;   loci cannot read s3://… — DuckDB's httpfs extension is not available.
  ;;
  ;;   loci cannot load DuckDB's httpfs extension.
  ;;
  ;; The count is on "extension is not available"/"cannot load", not on the word
  ;; httpfs: httpfs legitimately appears in the LOAD/INSTALL advice below, so
  ;; counting the extension name would fail against a correct message.
  (let [{:keys [out]} (remote-sql ["https://example.com/a.csv"]
                                  #(str/starts-with? % "LOAD"))
        msg (.getMessage ^Throwable (first out))]
    (is (= 1 (count (re-seq #"(?i)cannot load DuckDB's|extension is not available" msg)))
        (str "one failure, drawn with two headlines: " msg))
    (is (str/includes? msg "https://example.com/a.csv")
        (str "and the surviving headline is the one naming the source: " msg))))
```

- [ ] **Step 3: Run it, confirm FAIL.** Expected: `2` rather than `1`, with both headlines printed in the message.

```clojure
(require '[loci.lake-test] :reload)
(clojure.test/test-vars [#'loci.lake-test/the-remote-refusal-does-not-say-it-twice])
```

- [ ] **Step 4: Implement — `extension-problem` takes the head and tail it cannot compose.**

Replace the two functions. `extension-problem`:

```clojure
(defn- extension-problem
  "The mechanism paragraph — the directory, the uid, DuckDB's own message and
   the fix — between a `head` and a `tail` the CALLER composes.

   Without it the reader gets DuckDB's own error, which names neither loci nor
   anything actionable:

     IO Error: Failed to open … /.duckdb/extensions/…/ducklake.duckdb_extension

   That is the failure this whole mechanism exists for, and it killed the
   container before the listener. `/.duckdb` is the filesystem root because
   DuckDB composes its extension directory from $HOME and the runtime image sets
   none — see `open-lake!`.

   **Everything between `head` and `tail` is about the extension MECHANISM**,
   and is exactly as true for any extension as it is for `ducklake`. What is not
   shared is what the failure COSTS, which only the caller knows: `open-lake!`
   is failing at boot with no lake, and `ensure-remote!` is refusing one source
   while the store stays open. Both are sentences about a consequence, and a
   consequence is not derivable from an extension name.

   **It used to be derived from one anyway, and that is why this signature
   changed.** `required? (= \"ducklake\" ext)` gated ducklake's two sentences,
   and the other caller then wrapped this whole message in a SECOND composer to
   add its own — which rendered two headlines for one failure, `\"loci cannot
   read <uri> — DuckDB's httpfs extension is not available.\"` immediately
   above `\"loci cannot load DuckDB's httpfs extension.\"`, measured
   2026-08-24. That wrapper also had to `str/trimr` a newline the flag left
   behind and read the extension name back off the cause's `ex-data`. All of it
   was one concern implemented twice.

   The ducklake message is byte-identical to the one measured before this
   argument existed; `the-ducklake-paragraph-did-not-move` pins it."
  [head ^Throwable cause tail]
  (let [dir  (extension-dir)
        home (System/getenv "HOME")]
    (str head "\n"
         "  looking in " (cond dir  (str dir " (LOCI_DUCKDB_EXTENSIONS)")
                               home (str home "/.duckdb — DuckDB's default, from HOME")
                               :else "/.duckdb — DuckDB's default, and HOME is unset")
         "\n"
         "  running as " (sub/running-as) "\n"
         "  DuckDB said: " (.getMessage cause) "\n"
         "\n"
         "  Set LOCI_DUCKDB_EXTENSIONS to a directory holding the extension.\n"
         "  A container should set it at build time and install the extension\n"
         "  into the image then: INSTALL is the one step here that reaches the\n"
         "  network, and the directory it writes need never be writable again —\n"
         "  LOAD only reads.\n"
         tail)))
```

`ensure-extension!` — keep the entire existing docstring, and **append** the paragraph below to it; change only the signature, the `ex-info` line, and the paragraph about `ext` being loci's own literal (which stays, unchanged — it is still true and still the thing that closes the injection seam):

```clojure
  [lk ext head tail]
  (try (x! lk (str "LOAD " ext))
       (catch Exception _
         (try (x! lk (str "INSTALL " ext))
              (x! lk (str "LOAD " ext))
              (catch Exception e
                (throw (ex-info (extension-problem head e tail)
                                {:dir (extension-dir) :extension ext} e)))))))
```

Docstring paragraph to append:

```
   **`head` and `tail` are the caller's, not this function's.** What a missing
   extension costs is not derivable from its name — `open-lake!` has no lake and
   is failing at boot, `ensure-remote!` is refusing one source while everything
   else keeps working — so each caller composes the sentences it can justify and
   this composes the paragraph they share. `ext` is still interpolated into both
   heads at the call site, so neither can name an extension other than the one
   that failed.
```

Delete `remote-extension-problem` entirely.

- [ ] **Step 5: Update the two call sites.**

`open-lake!` — the ducklake head and tail are exactly the strings the `required?` flag used to add, so the message does not move:

```clojure
       (ensure-extension! lk "ducklake"
                          "loci cannot load DuckDB's ducklake extension, so the lake cannot open."
                          "\nFailing here rather than at the first table read.")
```

`ensure-remote!` — the head is the one that names the source, and the tail is what `remote-extension-problem` used to append. The `try`/`catch` that wrapped it goes with it; the `ex-info` `:uri` it added is preserved by `assoc`ing onto the cause's `ex-data`, which the existing test `a-missing-httpfs-refuses-one-source-and-says-so` asserts:

```clojure
      (when-not (contains? @ensured :httpfs)
        (try (ensure-extension!
              lk "httpfs"
              (str "loci cannot read " uri " — DuckDB's httpfs extension is not available.")
              (str "\n"
                   "  This source cannot be read; nothing else is affected. The extension is\n"
                   "  loaded only for a remote location, so loci is running, the store is open,\n"
                   "  every loci:// and file:// source still reads, and every table already in\n"
                   "  the lake is unchanged. Connect this one again once the extension is there."))
             (catch clojure.lang.ExceptionInfo e
               (throw (ex-info (.getMessage e) (assoc (ex-data e) :uri uri) e))))
        (swap! ensured conj :httpfs))
```

Also update `ensure-remote!`'s docstring: the sentence *"`extension-problem` composes the directory, the uid and the fix but cannot know the consequence, so `remote-extension-problem` wraps it with the half only this caller has"* now names a function that does not exist. Replace with *"…so this caller hands `ensure-extension!` the head and tail only it can compose: which source cannot be read, and that nothing else is affected."*

- [ ] **Step 6: Run, confirm PASS**, then fix the four existing tests that reference the old shape.

```clojure
(require '[loci.lake] :reload)
(require '[loci.lake-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
```

- `failed-extension` (the helper) must pass a head and tail. Give it head `(str "loci cannot load DuckDB's " ext " extension.")` and tail `""`, so its callers keep testing the mechanism paragraph.
- `ensure-extension-names-the-extension-it-was-asked-for` — unchanged in intent; the `sql` and `ex-data` assertions still hold.
- `only-ducklake-claims-the-lake-cannot-open` — its premise was that `ensure-extension!` decides, and it no longer does. **Rewrite it to assert the two real call sites**, which is a stronger test than the one it replaces:

```clojure
(deftest only-the-lake-claims-the-lake-cannot-open
  ;; Was `only-ducklake-claims-the-lake-cannot-open`, over an internal
  ;; `(= "ducklake" ext)` flag inside `extension-problem`. The consequence is
  ;; the CALLER's now, so this asserts the two messages that actually reach a
  ;; reader rather than the flag that used to pick between them — and the
  ;; shared paragraph is checked in both, which is what stops a caller
  ;; composing a consequence that replaces the directory, the uid and the fix.
  (let [d (:msg (failed-extension "ducklake"
                                  "loci cannot load DuckDB's ducklake extension, so the lake cannot open."
                                  "\nFailing here rather than at the first table read."))
        h (:msg (failed-extension "httpfs"
                                  "loci cannot read https://h/x.csv — DuckDB's httpfs extension is not available."
                                  "\n  This source cannot be read; nothing else is affected."))]
    (is (str/includes? d "so the lake cannot open"))
    (is (str/includes? d "Failing here rather than at the first table read"))
    (is (not (str/includes? h "so the lake cannot open"))
        (str "httpfs claimed the store cannot open: " h))
    (is (not (str/includes? h "Failing here rather than at the first table read"))
        (str "httpfs claimed loci is failing at boot: " h))
    (doseq [shared ["Set LOCI_DUCKDB_EXTENSIONS to a directory holding the extension."
                    "running as "
                    "DuckDB said: injected"
                    "LOAD only reads."]]
      (is (str/includes? d shared) (str "the ducklake message lost: " shared))
      (is (str/includes? h shared) (str "the httpfs message never got: " shared)))))
```

  This requires `failed-extension` to take head and tail. Give it the arity `[ext]` (defaulting as above) **and** `[ext head tail]`, so `ensure-extension-names-the-extension-it-was-asked-for` keeps its one-argument call.
- `the-refusal-names-the-extension-its-cause-carries` — **delete it, with a note in its place.** It tested that `remote-extension-problem` read the extension off `ex-data` rather than hardcoding `"httpfs"`. That drift is now structurally impossible: there is no second composer, and the head is built from `ext` in the caller's own scope. Leave this comment where the test was:

```clojure
;; `the-refusal-names-the-extension-its-cause-carries` was here. It pinned that
;; `remote-extension-problem` read the extension name off its cause's ex-data
;; instead of hardcoding "httpfs", so that a second caller loading `aws` would
;; not be told the wrong one. That function is gone (2026-08-24): the head is
;; composed at the call site from the `ext` being loaded, so there is no second
;; copy of the name to fall behind. Deleted rather than adapted, because what it
;; guarded no longer exists.
```

- [ ] **Step 7: Prove the ducklake message did not move.** Add this test and run it:

```clojure
(deftest the-ducklake-paragraph-did-not-move
  ;; The signature change of 2026-08-24 is a refactor of who composes the
  ;; consequence, and a refactor that alters the message a container operator
  ;; reads at boot is not one. Captured from HEAD~ before the change.
  (is (= (slurp (io/resource "test/ducklake-extension-problem.txt"))
         ((deref #'lake/extension-problem)
          "loci cannot load DuckDB's ducklake extension, so the lake cannot open."
          (Exception. "DuckDB's own text")
          "\nFailing here rather than at the first table read."))))
```

If wiring a resource file is more trouble than it is worth in this suite, compare against `/tmp/…/scratchpad/ducklake-before.txt` in the REPL instead and **report the diff (or that there is none)** rather than committing the test. Say which you did.

- [ ] **Step 8: Prove the new test bites.** Reintroduce the wrapper: have `ensure-remote!` catch and prepend `(str "loci cannot read " uri " — DuckDB's httpfs extension is not available.\n\n")` to the message. `the-remote-refusal-does-not-say-it-twice` must go red with `2`. **Read the printed message** to confirm it is the double headline and not something else.

- [ ] **Step 9:** `clojure -M:test`, commit.

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "fix: one failure drew two headlines, and the flag that caused it"
```

---

## Task 3: one home per measured number

Every measured number in this phase is written down in two to four places, with nothing keeping them in agreement. Task 1 fixes the three that already drifted; this stops the next one.

**The rule:** a number lives where the decision it supports is made. Everywhere else names the fact and points there. The spec's `## Measured 2026-08-23` section is the home for anything that is not a local decision.

**Files:**
- Modify: `src/loci/lake.clj:247-248`
- Modify: `resources/public/index.html` (two comments, lines ~258 and ~1533)

- [ ] **Step 1: Confirm the duplication before changing it.**

```
rg -n '36,436,086|28,456,078|21,570,542|249,391,443|299,418,382|50,026,939' src/ test/ Dockerfile docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md
```

Expected: each figure in three or four files. `test/loci/lake_test.clj:587`'s `36,436,086` is **not** one of them — it is a local fact about the download `seed-extension-dir!` avoids, measured on a different date for a different reason. Leave it.

- [ ] **Step 2: `lake.clj:247-248` restates the image arithmetic, and the Dockerfile is where that decision is made.**

`ensure-extension!`'s docstring currently reads:

```
   should still add its name here. What the three cost, measured on the actual
   build: ducklake 36,436,086 bytes, aws 28,456,078, httpfs 21,570,542, and the
   image went 249,391,443 → 299,418,382, a delta of +50,026,939 (+20.1%) that
   the two new extensions account for to within 319 bytes — the two `.info`
   files. Written down rather than argued from: nobody has complained about the
   size of this image.
```

The Dockerfile's `COPY` comment carries the same six figures with the reasoning attached — which extension is dead weight for whom, and what the alternative is if it has to come back out. That is the decision; this docstring only quotes it. Replace with:

```
   should still add its name here. The three cost about 86 MB of a 299 MB image
   between them, which is the largest thing it spends after the JDK runtime and
   the jar. The per-extension bytes and the build-to-build delta are in the
   Dockerfile beside the `COPY` that ships them, because that is where the
   decision to pay it is made, and in the spec's Measured section. Written down
   rather than argued from: nobody has complained about the size of this image.
```

- [ ] **Step 3: `index.html`'s two comments each restate `6,069 ms` and one restates `62 ms`.**

Both are legitimately local — the panel's copy says "about six of them", and a reader editing that sentence needs to know where six came from. Keep **one** of them whole (the one at ~line 1533, in the JS beside the sentence it justifies) and make the CSS comment at ~line 258 point at it:

```
     `reading its columns…`, which for a URL is not a figure of speech: the
     fetch happens HERE, at preview, and an s3:// location whose credentials
     have to be walked and refused takes ~6 s — see `dataPreview`'s comment for
     the measurement and for why a container with no route out is fast instead.
```

- [ ] **Step 4: Do NOT sweep further.** `10.5 s`, `6.0 s`, `10,022`, `6,028`, `6,079` and `62 ms` in `lake.clj` all sit in `ensure-remote!`'s docstring, which is the function whose behaviour they justify — that is their home, and the spec's copy is the gathered index. `lake_test.clj:789`'s `6.0 s` explains why a test injects rather than provokes; that is local too. Leave all of them. **A number in two places is only a defect when neither is the decision.**

- [ ] **Step 5: Verify the shell still loads and the browser suite is unaffected** — these are comments, but `index.html` is served as one file and a broken comment breaks the shell.

```
npm run test:browser -- test/browser/sources.test.mjs
```

Expected: the sources file green.

- [ ] **Step 6: Commit.**

```bash
git add src/loci/lake.clj resources/public/index.html
git commit -m "docs: a number lives where its decision is made, and is quoted nowhere else"
```

---

## Task 4: land the consolidation, keep the test that bites

The working tree holds an uncommitted consolidation: `test/loci/sourcespec_test.clj` 22 → 19 deftests (four `locate`-prose tests folded into one, `unreadable-because-still-tells-the-local-states-apart` narrowed to its non-duplicated control), and `test/browser/sources.test.mjs` 24 → 23.

The Clojure half is right and should land: 28 of that file's 40 assertions were readings of a sentence that is allowed to change, and the folded test records the sabotage showing only the composition assertion bit.

The browser half deletes `'a preview failure keeps the shape of the paragraph loci wrote'` — with no note, unlike every Clojure deletion beside it. That test is the **only** coverage anywhere for `.reclaimed p.srcerr{white-space:pre-wrap;overflow-wrap:anywhere}`, a rule this phase added three commits ago so remote failure prose keeps its newlines and indents. Its own comment records why the line-box count alone does not bite (`pre-line` draws the same five boxes) and why it measures glyph x-positions instead. Deleting prose assertions in Clojure and this test in the browser move in opposite directions.

**Files:**
- Modify: `test/loci/sourcespec_test.clj` (already modified — commit as is)
- Modify: `test/browser/sources.test.mjs` (restore one test)

- [ ] **Step 1: Restore the deleted browser test.**

```bash
git checkout -p test/browser/sources.test.mjs
```

…is interactive and unavailable here. Instead, extract it from HEAD and append it:

```bash
git show HEAD:test/browser/sources.test.mjs | tail -n +1525 >> test/browser/sources.test.mjs
```

Verify the boundary by hand before running it — `sed -n '1520,1530p'` of the HEAD version — and confirm the result ends with exactly one copy of the test and one trailing `});`.

- [ ] **Step 2: Confirm the file parses and the restored test passes.**

```
npm run test:browser -- test/browser/sources.test.mjs
```

Expected: 24 tests in this file, all green.

- [ ] **Step 3: Confirm the Clojure consolidation is green and that its own claim is true.**

```clojure
(require '[loci.sourcespec-test] :reload)
(clojure.test/run-tests 'loci.sourcespec-test)
```

Expected: 19 tests, 92 assertions, 0 failures.

- [ ] **Step 4: Prove the folded test still bites.** The consolidated `locate-refuses-an-unknown-scheme-by-naming-the-ones-it-knows` claims that replacing `locate`'s composed scheme lists with the literal they equal today turns only the composition assertion red. Redo that sabotage: in `sourcespec.clj`'s `:else` branch, replace `(concat [loci-prefix "file://"] (sort remote-schemes))` with the literal `["loci://" "file://" "http://" "https://" "s3://"]`. **Read the output** — the test must fail on the `str/join ", "` assertion under `with-redefs`, and not merely fail somewhere.

- [ ] **Step 5: Commit both halves together**, with the browser deletion explicitly not taken.

```bash
git add test/loci/sourcespec_test.clj test/browser/sources.test.mjs
git commit -m "test: sixty assertions that read a sentence go, and the one that measures a shape stays"
```

---

## Task 5: the 32 MB prefix has no test, and it is the phase's most surprising cost

`11ad7e7` measured that a `DESCRIBE` of a remote object **fetches a fixed 32,000,000-byte prefix whatever the object weighs**, and that the external file cache does not save the second one — two consecutive DESCRIBEs each issued `HEAD` + `GET bytes=0-31999999`, 71.4 and 68.3 ms. It is a user-visible cost on the ＋ Data panel's default path, and it exists only as prose. Every remote fixture in the suite is 2–3 rows, which by this repo's own rule establishes that the mechanism works and never that a cost is real.

**Files:**
- Modify: `test/loci/source_test.clj` — `with-stub` records headers; add a Range-serving stub and the test

- [ ] **Step 1: Teach `with-stub` to record headers.** It currently keeps `[:uri :request-method]`, so no test can see a `Range`. Adding a key is backwards-compatible — existing assertions use `(map :uri @seen)` and `(count @seen)`.

```clojure
              (fn [req]
                (swap! seen conj (select-keys req [:uri :request-method :headers]))
                (handler req))
```

Update its docstring's last sentence to say headers are recorded too, and why: *"…including that nothing did, and including the `range` header, which is the only instrument that can tell a prefix fetch from a whole-object one."*

- [ ] **Step 2: Write the Range-serving stub.** DuckDB's httpfs only range-reads a server that advertises it; a stub returning 200 with the whole body would make the test prove nothing.

```clojure
(defn- ranged-stub
  "Serve `bytes` with the Range support httpfs needs: `Accept-Ranges` and a
   truthful `Content-Length` on HEAD, and `206` with a `Content-Range` for a
   `Range` GET.

   **Without this the test is vacuous rather than red.** httpfs falls back to
   downloading the whole object against a server that does not advertise ranges,
   so a stub answering 200-with-everything would show a whole-object fetch and
   the test would conclude loci does not range-read, which is the opposite of
   what was measured."
  [^bytes body]
  (let [n (alength body)]
    (fn [req]
      (if (= :head (:request-method req))
        {:status 200 :headers {"content-type"   "text/csv"
                               "content-length" (str n)
                               "accept-ranges"  "bytes"}}
        (if-let [r (get-in req [:headers "range"])]
          (let [[_ a b] (re-matches #"bytes=(\d+)-(\d*)" r)
                from    (Long/parseLong a)
                to      (min (dec n) (if (seq b) (Long/parseLong b) (dec n)))]
            {:status  206
             :headers {"content-type"  "text/csv"
                       "content-range" (str "bytes " from "-" to "/" n)
                       "accept-ranges" "bytes"}
             :body    (java.io.ByteArrayInputStream. body from (inc (- to from)))})
          {:status 200 :headers {"content-type"   "text/csv"
                                 "content-length" (str n)
                                 "accept-ranges"  "bytes"}
           :body (java.io.ByteArrayInputStream. body)})))))

(defn- csv-of-at-least
  "A CSV of at least `want` bytes, as a byte array. Two columns and a counter, so
   DuckDB has a real schema to describe rather than one fabricated column."
  [want]
  (let [sb (StringBuilder. "region,revenue\n")]
    (loop [i 0]
      (when (< (.length sb) want)
        (.append sb "EMEA-") (.append sb i) (.append sb ",") (.append sb i) (.append sb "\n")
        (recur (inc i))))
    (.getBytes (.toString sb) "UTF-8")))
```

- [ ] **Step 3: Write the failing test.**

```clojure
(deftest a-remote-preview-fetches-a-fixed-prefix-and-not-the-object
  ;; The phase's most surprising measurement, and until now prose only: a
  ;; DESCRIBE of a remote CSV downloads a FIXED 32,000,000-byte prefix whatever
  ;; the object weighs — so a preview of a 2 GB table costs the same 32 MB as a
  ;; preview of a 40 MB one, and the ＋ Data panel's default path pays it before
  ;; anyone presses Connect.
  ;;
  ;; **The fixture has to exceed the prefix or the test is vacuous**, which is
  ;; what every other remote test in this file cannot do at three rows: over a
  ;; 3 KB object "the prefix" and "the whole object" are the same bytes, and no
  ;; assertion can separate them. 40 MiB is the smallest fixture that can, and
  ;; it is generated here rather than committed.
  ;;
  ;; Asserted on the RANGE HEADERS and not on wall clock: the number this pins
  ;; is bytes over the wire, and a loopback server measures no network anyway.
  (let [body (csv-of-at-least (* 40 1024 1024))
        n    (alength body)]
    (is (> n 32000000)
        (str "the fixture is " n " bytes, which is not larger than the prefix — "
             "every assertion below would then be true of a whole-object fetch too"))
    (with-stub (ranged-stub body)
      (fn [port seen]
        (reset! (:ensured @lake/lake) #{})
        (src/describe @lake/lake {:uri (str "http://127.0.0.1:" port "/big.csv") :format :csv})
        (let [ranges (keep #(get-in % [:headers "range"]) @seen)
              asked  (reduce + 0 (map (fn [r]
                                        (let [[_ a b] (re-matches #"bytes=(\d+)-(\d*)" r)]
                                          (inc (- (if (seq b) (Long/parseLong b) (dec n))
                                                  (Long/parseLong a)))))
                                      ranges))]
          (is (seq ranges)
              (str "no Range was sent at all, so httpfs downloaded the whole object — "
                   "either the stub stopped advertising ranges or the reader changed: "
                   (pr-str @seen)))
          (is (< asked n)
              (str "a preview pulled " asked " of " n " bytes, which is the whole object: "
                   (pr-str ranges)))
          (is (= 32000000 asked)
              (str "the prefix is no longer 32,000,000 bytes but " asked ". That is not a "
                   "test failure so much as a cost changing under the ＋ Data panel — "
                   "update the spec's Measured section and this number together: "
                   (pr-str ranges))))))))

(deftest a-second-preview-pays-the-prefix-again
  ;; The other half of the same measurement, and the one a reader would guess
  ;; wrong: DuckDB's external file cache does NOT save the second describe. Two
  ;; consecutive DESCRIBEs each issued HEAD + GET bytes=0-31999999, 71.4 and
  ;; 68.3 ms — so a person who previews, presses ‹ back, and previews again has
  ;; downloaded 64 MB.
  (let [body (csv-of-at-least (* 40 1024 1024))]
    (with-stub (ranged-stub body)
      (fn [port seen]
        (reset! (:ensured @lake/lake) #{})
        (let [uri (str "http://127.0.0.1:" port "/big.csv")]
          (src/describe @lake/lake {:uri uri :format :csv})
          (let [after-one (count (keep #(get-in % [:headers "range"]) @seen))]
            (is (pos? after-one) "the first describe ranged, so the comparison has a floor")
            (src/describe @lake/lake {:uri uri :format :csv})
            (is (> (count (keep #(get-in % [:headers "range"]) @seen)) after-one)
                "the second describe was served from a cache, which would be good news
                 and would mean the panel's second preview is free — check
                 duckdb_settings() for enable_external_file_cache and update the spec
                 before believing it")))))))
```

- [ ] **Step 4: Run, and READ the output before deciding whether it passed for the right reason.**

```clojure
(require '[loci.source-test] :reload)
(clojure.test/test-vars [#'loci.source-test/a-remote-preview-fetches-a-fixed-prefix-and-not-the-object
                         #'loci.source-test/a-second-preview-pays-the-prefix-again])
```

**Expected outcomes and what each means:**
- Green on all three assertions → the measurement is now pinned. Report the byte count.
- `no Range was sent at all` → httpfs did not range-read against this stub. **That is a finding about the stub, not about loci**, and the honest response is to say so and fix the stub (check that HEAD advertises `accept-ranges` and a correct `content-length`), not to weaken the assertion or conclude the spec was wrong.
- `asked` is some number other than 32,000,000 → report the actual number, and say whether it is still a fixed prefix (re-run with a 60 MiB fixture: if `asked` does not move, it is fixed). Update **both** this test and the spec's Measured section, with the date.

- [ ] **Step 5: Prove the control is not vacuous.** Temporarily change `csv-of-at-least` to `(* 20 1024 1024)` — below the prefix. The first `is` must go red with "the fixture is … not larger than the prefix". This is what stops the test silently degrading into the 3-row tests it exists to complement. Restore 40 MiB afterwards.

- [ ] **Step 6: Report the runtime cost of these two tests.** They generate ~80 MiB and push ~64 MB through a loopback socket. If they add more than a few seconds to `clojure -M:test`, say the number — a suite nobody will run is worse than a test nobody wrote.

- [ ] **Step 7:** `clojure -M:test`, commit.

```bash
git add test/loci/source_test.clj
git commit -m "test: a preview downloads 32 MB whatever the object weighs, and now something says so"
```

---

## Task 6: both suites, and the spec's record

- [ ] **Step 1:** `clojure -M:test` → 0 failures. Report tests/assertions against the 874 / 4752 recorded in `11ad7e7`, and account for the delta: Task 4 removes assertions, Task 2 and Task 5 add tests.
- [ ] **Step 2:** `npm run test:browser` → report the count against 159/159, which should become 159 again once Task 4 restores the test the working tree had removed.
- [ ] **Step 3:** Add a short `## Cleanup 2026-08-24` section to the spec recording what this plan changed and, for Task 5, the byte count it actually measured — saying which of the two things it establishes.
- [ ] **Step 4:** Commit.

---

## Definition of done

- [ ] No file asserts a measurement that another file records as retracted — `12.7 ms` and the autoinstall story survive only as history
- [ ] A missing-extension refusal for a remote source draws **one** headline, and the ducklake boot message is byte-identical to before
- [ ] `remote-extension-problem`, the `required?` flag, the `str/trimr` fix-up and the `ex-data` name read-back are all gone
- [ ] The image-size figures live in the Dockerfile and the spec, and are quoted in neither `lake.clj` nor twice in `index.html`
- [ ] The sourcespec consolidation is committed, and `.reclaimed p.srcerr`'s `pre-wrap` still has the test that measures glyph positions
- [ ] A test fails if a remote preview stops fetching a bounded prefix, and its fixture is provably larger than that prefix
- [ ] `clojure -M:test` and `npm run test:browser` both green but for the known `outline` flake
- [ ] Every regression test proved to fail when its fix is reverted, and every sabotage's output read rather than counted
