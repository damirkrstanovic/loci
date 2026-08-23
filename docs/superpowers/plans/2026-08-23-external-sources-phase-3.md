# External Sources Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A CSV, Parquet or JSON file at `http://`, `https://` or `s3://` becomes a loci table, with no credential ever stored by loci.

**Architecture:** `sourcespec/locate` returns a remote URI verbatim, and `sourcespec/relation` already emits `read_csv('<location>')` from whatever `locate` returns — so the reader, the handle shapes, the failure markers and the routes are all untouched. Three things are genuinely new: `httpfs` must be loaded (lazily, so a missing extension fails one source rather than the store), an S3 secret must be issued from the credential chain, and `connect!` must refuse passthrough for a remote location.

**Tech Stack:** Clojure 1.12, DuckDB/DuckLake + httpfs, http-kit (test fixtures only), Datalevin.

**Spec:** `docs/superpowers/specs/2026-08-23-external-sources-phase-3-design.md`

---

## Ground rules for every task

Every one of these records a defect that already happened on this repository. They are not boilerplate.

- **The REPL is the execution surface.** `ToolSearch` query `select:mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888; `:reload` after every edit. Do **not** run `clojure -M -e` to iterate — a cold JVM costs ~25 s. `clojure -M:test` is for one final full-suite run before a commit.
- **A client-side timeout does not stop DuckDB.** The REPL tool times out at 20 s; the native JDBC call keeps running and does not reliably honour an interrupt. Re-issuing `run-tests` immediately races the first run, and two concurrent `with-temp-lake` fixtures stomp the same globally-`with-redefs`'d `lake/lake` var and close each other's connections — a wall of unrelated `Connection was closed` failures across the whole namespace. Wait for idle, and treat a sudden broad connection failure as this before treating it as a finding.
- **The shell is `resources/public/index.html`**, not the repo-root `index.html`. Plain `grep` reports **zero** matches on it — its long lines read as binary. Always `grep -a`. (This phase should not need to touch it; if you find you do, that is a finding.)
- **Baselines to hold:** `clojure -M:test` → **843 tests, 4539 assertions, 0 failures**. `npm run test:browser` → **153 tests, 153 pass**. There is a known flake — `outline.test.mjs`, "clicking an entry brings that cell into view", a transient `.flash` class on a 900 ms timeout. **Ten agents have chased it; do not chase it an eleventh time.**
- **A `"` inside a Clojure docstring must be `\"`** in the source.
- **Never use an unanchored global find-and-replace on source.** One on the phase-2 branch changed `(take 6)` → `(take 8)` and silently broke an unrelated cap.
- `:reload` does not drop vars for renamed-away tests — `(remove-ns 'the.ns)` first. Reloading `loci.tablestore` invalidates `reify`s in `loci.tablestore-test`.
- **`data/` is test data.** Prefer a throwaway `LOCI_DATA`, not to protect it but because tests that share a store interfere.
- **`data/`'s scale is not evidence.** A measurement over the fixture establishes that a mechanism **works**, never that a cost is **real**.
- **Prove every test bites.** Break the fix, re-run, confirm FAIL, restore. **If a sabotage reports 0 failures, that is a finding about your sabotage.**
- **Ask what the assertion looks like when the mechanism is absent.** The sabotage step catches a broken *fix*, not a broken *assertion*. Over twenty such assertions have been found across three phases, and the files-housekeeping branch found one on **every single task** — including three of six sabotages in one task, and a sabotage written for a shape the code no longer had. Print the sabotaged output and read it.
- **The network is not a test fixture.** Every test in this phase reads from a loopback server or from nothing. `INSTALL httpfs` may reach the network once on a machine that has never seen it; nothing else may.
- End every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, and record the suite counts in it.

## File structure

| file | responsibility |
|---|---|
| `src/loci/sourcespec.clj` | `locate` answers a remote uri verbatim; `unreadable-because` gets a remote branch; a `remote?` predicate both use |
| `src/loci/lake.clj` | `ensure-extension!` generalised to any extension; a lazy `ensure-remote!` that loads `httpfs` and issues the secret once |
| `src/loci/source.clj` | `connect!` refuses `:mode :passthrough` for a remote location, and calls `ensure-remote!` before a remote read |
| `Dockerfile` | `INSTALL httpfs` beside `INSTALL ducklake`, with the same `test -f` guard |
| `test/loci/sourcespec_test.clj` | `locate` and `unreadable-because` over remote uris |
| `test/loci/source_test.clj` | the passthrough refusal, and a materialise over a loopback HTTP server |
| `test/loci/lake_test.clj` | `ensure-remote!` is issued once and carries no credential material |

---

### Task 1: `locate` answers a remote uri verbatim

**Files:**
- Modify: `src/loci/sourcespec.clj` (`locate`, and a new `remote?`)
- Test: `test/loci/sourcespec_test.clj`

`locate` currently has three branches — `loci://`, `file://`, and an `:else` that throws *"loci does not know this location: … Remote schemes (s3://, https://) arrive with httpfs."* That `:else` message was written in phase 1 as a promise; this task keeps it.

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest locate-answers-a-remote-uri-verbatim
  (is (= "s3://bucket/sales.csv" (ssp/locate "s3://bucket/sales.csv")))
  (is (= "https://example.com/a/b.csv" (ssp/locate "https://example.com/a/b.csv")))
  (is (= "http://127.0.0.1:8080/x.csv" (ssp/locate "http://127.0.0.1:8080/x.csv"))
      "plaintext too — httpfs reads it, and loci does not police the transport of a
       location a person typed, exactly as file:// is unrestricted by decision")
  (is (= "s3://bucket/a b.csv" (ssp/locate "s3://bucket/a b.csv"))
      "verbatim means verbatim: no re-encoding, no URI round trip. DuckDB is handed
       the string the user gave, quoted by sql-str, and it is DuckDB's job to parse it"))

(deftest locate-still-refuses-a-scheme-it-does-not-know
  (doseq [u ["gs://bucket/x.csv" "az://c/x.csv" "ftp://h/x.csv" "redis://h/1"]]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (ssp/locate u)))]
      (is (re-find #"loci does not know this location" (str (some-> e .getMessage)))
          (str u " must be refused by name, not resolved")))))

(deftest remote?-is-about-the-scheme-and-nothing-else
  (is (true?  (ssp/remote? "s3://b/x.csv")))
  (is (true?  (ssp/remote? "https://h/x.csv")))
  (is (true?  (ssp/remote? "http://h/x.csv")))
  (is (false? (ssp/remote? "loci://files/x.csv")))
  (is (false? (ssp/remote? "file:///data/x.csv")))
  (is (false? (ssp/remote? "gs://b/x.csv"))
      "a scheme loci does not know is not remote — it is unknown, and locate says so")
  (is (false? (ssp/remote? nil)) "nil is not a location")
  (is (false? (ssp/remote? "HTTPS://h/x.csv"))
      "case-sensitive, deliberately: every uri loci mints is lower-case, and a
       case-insensitive test here would have to be repeated in locate to agree"))
```

- [ ] **Step 2: Run, confirm FAIL** — `No such var: ssp/remote?`, and `locate` throwing on `s3://`.

- [ ] **Step 3: Implement**

```clojure
(def ^:private remote-schemes
  "The location schemes DuckDB's httpfs extension reads, and therefore the ones
   `locate` hands through untouched.

   `gs://` and `az://` are absent deliberately. `gs://` rides httpfs and would
   cost one entry here; `az://` is a separate extension with its own
   install-at-build-time problem — the one that cost two commits for ducklake in
   August 2026. Both arrive the day someone has one, which is the same rule the
   `readers` map above already applies to Excel."
  #{"http://" "https://" "s3://"})

(defn remote?
  "Is `uri` a location DuckDB fetches rather than one loci resolves?

   A scheme test and nothing more — it does not ask whether the object exists,
   which is the whole point: for a remote location, nothing short of a fetch
   can answer that, and the callers that use this are choosing a code path
   rather than reporting a state."
  [uri]
  (boolean (some #(str/starts-with? (str uri) %) remote-schemes)))
```

and a branch in `locate`, **before** the `:else`:

```clojure
      (remote? uri)
      ;; Verbatim. `relation` wraps this in `sql-str` and DuckDB parses it —
      ;; loci re-encoding it here could only make it wrong, and a `java.net.URI`
      ;; round trip is exactly what mangled `file://data/x.csv` into `/x.csv`
      ;; before `606505c` refused it.
      uri
```

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove they bite**

Three sabotages, each printed and read:
1. Drop the `remote?` branch from `locate` → the verbatim tests fail with the `:else` message.
2. Add `"gs://"` to `remote-schemes` → `locate-still-refuses-a-scheme-it-does-not-know` fails on the `gs://` case, and `remote?-is-about-the-scheme` fails too.
3. Make `remote?` lower-case its argument → the `HTTPS://` assertion fails.

**Interrogate before trusting:** sabotage 1's tests compare against a literal, so they cannot pass vacuously — but check that the *refusal* test would still fail if `locate` returned `nil` for an unknown scheme rather than throwing. `(is (thrown? …))` catches that; a `(is (nil? …))` would not.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 2: `unreadable-because` stops inventing a file path

**Files:**
- Modify: `src/loci/sourcespec.clj` (`unreadable-because`)
- Test: `test/loci/sourcespec_test.clj`

After Task 1, `locate` returns `"s3://bucket/x.csv"` and this function does `(java.io.File. "s3://bucket/x.csv")`, whose `.exists` is false — so it answers **"there is no file at s3:/bucket/x.csv"**. Wrong, with a mangled path (`java.io.File` collapses `//`), on the failure path its own docstring says must never raise a second error over the first.

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest unreadable-because-does-not-invent-a-file-for-a-remote-uri
  (doseq [u ["s3://bucket/sales.csv" "https://example.com/x.csv" "http://127.0.0.1:1/x.csv"]]
    (let [msg (str (ssp/unreadable-because u))]
      (is (not (re-find #"there is no file at" msg))
          (str "it claimed a missing local file for " u " — that sentence is about a
                filesystem loci did not consult: " msg))
      (is (not (re-find #"s3:/[^/]" msg))
          (str "java.io.File collapsed the scheme's slashes into the message: " msg)))))

(deftest unreadable-because-still-answers-for-a-local-file
  (let [f (java.io.File/createTempFile "ubc" ".csv")]
    (try
      (is (nil? (ssp/unreadable-because (str "file://" (.getAbsolutePath f))))
          "a readable local file is still readable")
      (.delete f)
      (is (re-find #"there is no file at"
                   (str (ssp/unreadable-because (str "file://" (.getAbsolutePath f)))))
          "and a missing one still says so — the local branches are untouched")
      (finally (.delete f)))))
```

- [ ] **Step 2: Run, confirm FAIL** — the first test fails on the `there is no file at` match.

- [ ] **Step 3: Implement.** A branch at the top of the `try`, before the `File` is constructed:

```clojure
    (if (remote? uri)
      ;; A remote location is not a file, and loci cannot cheaply learn why a
      ;; fetch failed — a 404, a 403, an expired credential and a bucket in the
      ;; wrong region are all one round trip away and none of them is knowable
      ;; from the uri. So this says only what it knows. `duckdb-said` carries
      ;; the real reason from the failed read, which is what the caller shows.
      nil
      (let [f (java.io.File. ^String (locate uri))]
        …))
```

**`nil` and not a phrase.** Read `unreadable-because`'s docstring before accepting this: it returns nil for *"or nil when it can"* be read, and its callers use nil to mean **drift** rather than **unavailable**. Returning nil for a remote uri therefore classifies every remote read failure as drift. Work out whether that is right — a remote materialised table only reads at `refresh!`, and a refresh that fails against a moved column set *is* drift while one that fails against a 404 is not. If nil is wrong, a phrase naming the uri without claiming a filesystem is the alternative. **Decide, say which and why, and make the test match your decision rather than the sketch above.**

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove they bite.** Remove the remote branch → the first test fails, and **read the message it prints** to confirm it is the mangled-path one and not something else.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 3: `ensure-extension!` takes an extension name

**Files:**
- Modify: `src/loci/lake.clj` (`ensure-extension!`, `extension-problem`, `open-lake!`)
- Test: `test/loci/lake_test.clj`

`ensure-extension!` is `[lk]` and hard-codes `ducklake` in three places. Task 4 needs the same LOAD-then-INSTALL retry for `httpfs` with a **different consequence** — refuse one source, not the store.

**This is a refactor. `open-lake!`'s behaviour and the ducklake failure message must not change.** That message is measured prose about a real deployment failure and it earns its length.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest ensure-extension-names-the-extension-it-was-asked-for
  (with-temp-lake lk
    (is (nil? (lake/ensure-extension! lk "ducklake"))
        "an extension already present is a no-op that answers nil")
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lake/ensure-extension! lk "not_a_real_extension_xyz")))]
      (is (re-find #"not_a_real_extension_xyz" (str (some-> e .getMessage)))
          "the message names the extension that failed, not a hard-coded one"))))
```

- [ ] **Step 2: Run, confirm FAIL** — wrong arity.

- [ ] **Step 3: Implement.** Give `ensure-extension!` an `ext` argument and thread it through the two SQL strings and the `ex-info`. `extension-problem` keeps composing the ducklake prose — pass it the name, and let it keep its existing sentences for `ducklake` while a different extension gets a shorter one, or split it. **Say which you chose.**

Do **not** widen its visibility beyond what Task 4 needs.

- [ ] **Step 4: Run, confirm PASS**, and re-run `loci.lake-test` whole — it must be unchanged except for your one new test.

- [ ] **Step 5: Prove it bites.** Hard-code `"ducklake"` back into the SQL while keeping the argument → the new test fails on the message assertion. **Also confirm the ducklake prose is byte-identical** — `git diff` the `extension-problem` output for the ducklake case, or assert it in the REPL against the string from `HEAD~1`.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 4: `ensure-remote!` — httpfs and the secret, once, lazily

**Files:**
- Modify: `src/loci/lake.clj`
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest ensure-remote-loads-httpfs-and-issues-a-secret-once
  (with-temp-lake lk
    (let [sql (atom [])]
      (with-redefs [lake/x! (fn [l s] (swap! sql conj s) nil)]
        (lake/ensure-remote! lk)
        (lake/ensure-remote! lk))
      (is (= 1 (count (filter #(re-find #"(?i)load httpfs" %) @sql)))
          "twice asked, once done — this runs before every remote read")
      (is (= 1 (count (filter #(re-find #"(?i)create secret" %) @sql)))))))

(deftest the-secret-carries-no-credential-material
  (with-temp-lake lk
    (let [sql (atom [])]
      (with-redefs [lake/x! (fn [l s] (swap! sql conj s) nil)]
        (lake/ensure-remote! lk))
      (let [secret (first (filter #(re-find #"(?i)create secret" %) @sql))]
        (is (re-find #"(?i)provider\s+credential_chain" secret)
            "the whole design is that loci hands DuckDB a provider, not a key")
        (is (not (re-find #"(?i)key_id|secret\s*=|session_token" secret))
            (str "the SQL names credential material: " secret))))))
```

- [ ] **Step 2: Run, confirm FAIL** — `No such var: lake/ensure-remote!`.

- [ ] **Step 3: Implement.** A `defonce`-style once-per-lake guard — the `Lake` record is per-connection, so an atom on the record or a `memoize` keyed by the connection are both available. **`with-temp-lake` makes a fresh lake per test, so a global atom would leak across tests and make the "once" assertion order-dependent.** Say which you chose and why.

```clojure
(defn ensure-remote!
  "Load `httpfs` and issue the S3 credential secret, once per lake connection.

   **Called on the first remote read, not from `open-lake!`.** `open-lake!`
   calls `ensure-extension!` inside a `try` that closes the connection and
   rethrows, which is right for `ducklake` — no extension, no lake, and loci
   should say so at boot. It would be wrong here: a loci that never touches a
   remote source would fail to start because an extension it does not need is
   missing. So this fails one source at connect time, not the store at boot.

   The secret is `PROVIDER credential_chain`, so loci hands DuckDB a *provider*
   and never a key. The AWS SDK chain reads env vars, `~/.aws/credentials`, an
   instance profile or IRSA. Nothing is stored, nothing is committed, and
   `undo!` cannot resurrect a credential because none was ever written — which
   matters here more than anywhere, since the substrate is an append-only log
   and a secret in it would be recoverable forever by design."
  [lk] …)
```

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove they bite**

1. Drop the once-guard → the first test fails on `(= 1 2)`.
2. Change `credential_chain` to a literal `KEY_ID '…' SECRET '…'` → the second test fails on **both** assertions.
3. Skip the `LOAD httpfs` → the first test fails on the load count.

**Interrogate:** the once-test redefines `lake/x!`, so it sees the SQL and not the effect. Ask what it would report if `ensure-remote!` issued the SQL twice but DuckDB ignored the second — the test would fail, correctly. Then ask the harder one: what does it report if `ensure-remote!` does nothing at all? `(= 1 0)` — it fails. Good. But **a test that only counts SQL cannot see that httpfs actually loaded**; if you want that, one real remote read is the only evidence, and Task 6 has it.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 5: `connect!` refuses passthrough for a remote location

**Files:**
- Modify: `src/loci/source.clj` (`connect!`)
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest connect-refuses-passthrough-for-a-remote-location
  (with-temp-lake _
    (let [st (sub/fresh-store)
          e  (is (thrown? clojure.lang.ExceptionInfo
                          (src/connect! st {:uri "s3://bucket/x.csv" :format :csv
                                            :mode :passthrough :title "S3"})))
          m  (str (some-> e .getMessage))]
      (is (re-find #"passthrough" m) "the sentence names the mode that was refused")
      (is (re-find #"s3://bucket/x.csv" m) "and the location it was refused for")
      (is (empty? (sub/objects st))
          "and nothing was committed — a refusal that leaves an object behind is
           worse than no refusal, because the shell then draws a broken table"))))

(deftest connect-still-allows-passthrough-for-a-local-file
  ;; The guard must be about the SCHEME and not about `connect!` having grown a
  ;; new failure path — phase 1's whole passthrough story is local, and this is
  ;; the assertion that fails if the refusal is written too wide.
  (let [st (sub/fresh-store)
        r  (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                             :mode :passthrough :title "Live"})
        h  (:value (sub/object st (:id r)))]
    (is (nil? (:error r)))
    (is (= :source (:store h))
        "a local passthrough still stores the recipe, not a lake table")))
```

`csv!` is this namespace's existing helper for a temp CSV at a `file://` uri — `connect-passthrough-stores-the-recipe-and-creates-no-table` (`source_test.clj:302`) uses exactly this shape, and reusing it is the point: if the refusal is written against `:mode` rather than against `(remote? uri)`, that pre-existing test fails too, and you will see it in the same run.

- [ ] **Step 2: Run, confirm FAIL** — the first test throws nothing, and a `:store :source` handle over an s3 uri gets committed.

- [ ] **Step 3: Implement.** In `connect!`, after `mode-of` validates and before anything reads, refuse the combination. `mode-of` is the precedent for the shape and for the reasoning — its docstring is about a mode that silently did the wrong thing, which is exactly this failure.

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove they bite.** Remove the guard → the first test fails. **Check which assertion fails**: if it is the `(empty? (sub/objects st))` one rather than the `thrown?`, the guard is in the wrong place — it ran after a commit.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 6: a remote CSV becomes a table

**Files:**
- Modify: `src/loci/source.clj` (call `ensure-remote!` before a remote read)
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Serve the CSV from a loopback http-kit server, the way `test/loci/chunks_test.clj:64`'s `with-stub` already does — read it and copy the shape, including its `seen` atom, so this test can assert what actually went over the wire.

```clojure
(deftest a-remote-csv-materialises-into-the-lake
  (with-temp-lake _
    (with-stub (fn [_] {:status 200 :headers {"content-type" "text/csv"}
                        :body "region,revenue\nEMEA,10\nAPAC,20\n"})
      (fn [port seen]
        (let [st (sub/fresh-store)
              r  (src/connect! st {:uri (str "http://127.0.0.1:" port "/sales.csv")
                                   :format :csv :mode :materialise :title "Remote"})]
          (is (nil? (:error r)))
          (is (= 2 (:rows r)))
          (is (= [:region :revenue] (:cols r)))
          (is (= :lake (:store (:value (sub/object st (:id r)))))
              "materialised, so the handle names a lake table and not the uri")
          (is (seq @seen) "and the bytes actually came over the wire")
          (is (empty? (files/listing st))
              "and NOTHING landed in <LOCI_DATA>/files/ — the spec's claim that the
               files GC never sees a remote source, asserted rather than assumed"))))))
```

A second test, because the spec asks for it and nothing else covers it — a remote source is materialise-only, so **`refresh!` is the only way its bytes ever change**:

```clojure
(deftest refresh-of-a-remote-source-re-fetches-the-object
  (with-temp-lake _
    (let [body (atom "region,revenue\nEMEA,10\n")]
      (with-stub (fn [_] {:status 200 :body @body})
        (fn [port seen]
          (let [st (sub/fresh-store)
                r  (src/connect! st {:uri (str "http://127.0.0.1:" port "/s.csv")
                                     :format :csv :mode :materialise :title "R"})
                n1 (count @seen)]
            (is (= 1 (:rows r)))
            (reset! body "region,revenue\nEMEA,10\nAPAC,20\nLATAM,30\n")
            (let [r2 (src/refresh! st (:id r))]
              (is (= 3 (:rows r2)) "the new object was read, not the old handle re-reported")
              (is (> (count @seen) n1)
                  "and it went over the wire again — a refresh that answered from the
                   lake would give the same row count as a re-fetch of unchanged bytes,
                   so the request count is the only thing that separates them"))))))))
```

The `(> (count @seen) n1)` assertion is the load-bearing one. Ask what the row-count assertion alone would report if `refresh!` re-read the *lake table* rather than the source: with the body changed it would answer 1 and fail — but with the body *unchanged* it would answer correctly for the wrong reason, and a later reader would trust it. The wire count is what only a re-fetch can produce.

`files/listing` and `with-stub` both need requiring into this namespace; `with-stub` is private to `chunks_test`, so copy it rather than reaching across test namespaces, and say in a comment where it came from.

- [ ] **Step 2: Run, confirm FAIL.** Expect a DuckDB error about `httpfs` or an unknown protocol — **read it**, because it tells you whether `INSTALL httpfs` has ever run on this machine, which changes what Step 3 has to do.

- [ ] **Step 3: Implement.** `connect!` calls `lake/ensure-remote!` before a remote read. Find the one place that is true for both `describe` and `connect!` rather than calling it twice.

- [ ] **Step 4: Run, confirm PASS.**

- [ ] **Step 5: Prove it bites.** Remove the `ensure-remote!` call → the test fails on the read. **On a machine where httpfs is already loaded into the connection by something else, this sabotage may report 0 failures — that is a finding about the sabotage**, and the honest response is to say so and check `duckdb_extensions()` for whether httpfs was already loaded rather than claiming the call is unnecessary.

- [ ] **Step 6:** `clojure -M:test`, commit.

---

### Task 7: the image carries httpfs

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1:** Add `INSTALL httpfs` beside `INSTALL ducklake` in the build-stage `RUN` at `Dockerfile:152`, and extend the `test -f` guard to cover it. Read the surrounding comment block first — it explains why the line is raw JDBC with no alias (so the extension version matches the driver that will load it) and why the guard exists (*"a silently empty /duckdb-ext produces an image"*).

- [ ] **Step 2:** Build the image. Report the size delta against the previous image — httpfs is not free and the number belongs in the commit message.

- [ ] **Step 3:** `docker run --network none` and connect a `file://` source, to prove the image still opens a lake offline. This is the check that caught the ducklake packaging problem twice.

- [ ] **Step 4:** Commit with the size delta.

---

### Task 8: the measurement, and both suites

- [ ] **Step 1:** Measure a real remote read **once**, against a loopback server, at the design's stated scale — a 200 MiB CSV over HTTP. Report wall clock and peak heap for `connect!` with `:mode :materialise`, beside phase 1's local numbers (10M rows / 398 MiB materialises in **893 ms at 57 MB peak heap**). The question is whether a remote fetch changes the heap story or only the wall clock; loopback removes the network variable deliberately, so **say that this establishes the mechanism and the local-side cost, and not what a real S3 fetch costs.**

- [ ] **Step 2:** `clojure -M:test` → 0 failures. `npm run test:browser` → 153/153 or the known `outline` flake only.

- [ ] **Step 3:** Write the numbers into the spec's own **Measured** section with the date, saying for each which of the two things it establishes — that the mechanism works, or that the cost is real.

- [ ] **Step 4:** Walk the Definition of done below item by item, with evidence. Commit.

---

## Definition of done

- [ ] `http://`, `https://` and `s3://` locations resolve verbatim; `gs://`, `az://` and unknown schemes are refused by name
- [ ] `unreadable-because` never claims a local file for a remote uri
- [ ] A remote CSV materialises into the lake and the rows are right, and nothing lands in `<LOCI_DATA>/files/`
- [ ] `refresh!` of a remote source re-fetches, proved by the request count and not only by the row count
- [ ] `connect!` refuses passthrough for a remote location, commits nothing, and names both the mode and the uri
- [ ] `httpfs` loads lazily — a loci with no httpfs still boots and still serves local sources
- [ ] The S3 secret is issued once per connection and its SQL contains no credential material
- [ ] The image carries httpfs and still opens a lake with `--network none`
- [ ] `clojure -M:test` and `npm run test:browser` both green but for the known `outline` flake
- [ ] Every regression test proved to fail when its fix is reverted, and every sabotage's output read rather than counted
