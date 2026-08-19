# External Sources Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A CSV, Parquet or JSON file at a `loci://` or `file://` location becomes a loci table — materialised into DuckLake or read through on every query — with DuckDB doing the reading, so a 10M-row file never lands in RAM.

**Architecture:** A source is a **location URI, a format and a mode**. Two pure functions build the SQL (`sourcespec/locate`, `sourcespec/relation`); one query proves it reads and names its columns (`source/describe`); `:materialise` runs `CREATE TABLE … AS SELECT … FROM <rel>` and yields today's unchanged `{:store :lake}` handle, while `:passthrough` yields a `{:store :source}` handle that **is** the recipe and creates no catalog object. `read_csv` becomes the only way a CSV becomes rows, so `tools/parse-csv` and `tools/->val` are deleted.

**Tech Stack:** Clojure 1.12, DuckDB JDBC 1.5.5.1 (`read_csv` / `read_parquet` / `read_json_auto`), DuckLake, `clojure.test` via `clojure -M:test`, Playwright via `npm run test:browser`.

**Spec:** `docs/superpowers/specs/2026-08-19-external-sources-design.md`

---

## Ground rules for every task

- **The REPL is the execution surface.** Do not run `clojure -M -e`. Use `mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888 and `(require '[loci.x] :reload)` after every edit. A cold JVM costs ~25 s; the same work warm is milliseconds. See `.claude/skills/clojure-repl-dev`.
- **Run one test from the REPL** like this — it is the loop, not `clojure -M:test`:
  ```clojure
  (require '[loci.source-test] :reload)
  (clojure.test/test-vars [#'loci.source-test/the-one-test])
  ```
  `clojure -M:test` is for the single full-suite run in Task 14.
- **The nREPL on port 7888 is shared.** If more than one agent is working at once, another agent's mid-edit definitions live in the same JVM as yours, and a namespace you did not touch can fail — or, worse, *pass* — for reasons that are not on disk. Observed 2026-08-19: `loci.tablestore-test` reported 8 failures against a `tablestore.clj` that `git diff` showed clean. Always `(require '[the.ns] :reload)` from disk before trusting any cross-namespace result, and treat a surprising result as possible contamination before treating it as a finding. The controller runs agents serially for this reason; if you see evidence of a second writer, stop and report it.
- **A sabotage that did not take effect is a false green — check that it landed.** Two ways to get
  this wrong were hit on 2026-08-19. `(in-ns 'loci.x)` nested inside a `do`/vector does NOT change
  the namespace the enclosing form is *compiled* in, so the `defn` beside it defines `user/foo`
  and sabotages nothing — send `in-ns` as its own top-level form, or edit the file. And
  `with-redefs` on a widely-used helper can break more than the target (`sub/col-kw` also breaks
  `check-names!`, giving 3 failures where 1 was wanted) — when the signal is muddy, edit the
  source instead. Either way: if the sabotage run reports 0 failures, that is a finding about your
  sabotage, not about the code.
- **Prove every test bites.** After a test passes, break the implementation it covers, re-run, confirm FAIL, restore. A test that passes either way is worse than none, because it certifies.
- **End every commit message with** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. This
  branch's earlier commits predate the rule and are not being rewritten for it.
- **The shell is `resources/public/index.html`, and plain `grep` lies about it.** The repo root
  holds a different, unserved `index.html`; `server.clj:2991` slurps the one under `resources`.
  And grep treats the served file as binary because of its long lines, so it reports zero matches
  silently — always `grep -a`. A conclusion of "nothing in the shell calls this" drawn any other
  way is worthless; one was drawn that way on 2026-08-19 and was wrong.
- **`data/` is disposable.** Do not checksum it, do not rehearse on copies. Prefer a throwaway `LOCI_DATA` where a test does not need the real store.

## File structure

| file | responsibility |
|---|---|
| `src/loci/sourcespec.clj` | **new.** Pure: URI → addressable location, source → SQL relation text. No lake, no file, no network. Depends only on `loci.substrate`. |
| `src/loci/source.clj` | **new.** The facade: `describe`, `connect!`, `refresh!`. Commits events. |
| `src/loci/lake.clj` | `unwrap` grows temporal types; `create-from-relation!`; `:source` handle reads; the sample-cache key. |
| `src/loci/tablestore.clj` | `handle?` learns `:source`; `check-names!` extracted; `hydrate` grows three markers. |
| `src/loci/tools.clj` | `parse-csv` and `->val` deleted; `md-cell-val` returns a string. |
| `src/loci/server.clj` | `import-csv!` rewritten as a wrapper; `/api/source/*` routes; four marker readers taught the new keys. |
| `src/loci/corpus.clj` | imports its five CSVs through `source/describe` instead of `parse-csv`. |
| `test/loci/sourcespec_test.clj` | **new.** Pure-function tests, no fixture. |
| `test/loci/source_test.clj` | **new.** Against a temp lake via `loci.lake-fixture`. |

`loci.sourcespec` is split from `loci.source` for the reason the spec gives: the connector-specific part is a string builder that must be testable with `=`, no lake and no file. It is also what breaks the dependency cycle — `loci.lake` needs `relation` to read a `:source` handle, and `loci.source` needs `loci.lake` to run anything.

---

### Task 1: `unwrap` renders DuckDB's temporal types

DuckDB's CSV sniffer returns `java.time.LocalDate` for a DATE column, which `clojure.data.json/write-str` refuses outright, and `java.sql.Timestamp` for TIMESTAMP, which serializes through the JVM default zone and shifts the instant. Measured 2026-08-19. Without this task the first CSV with a date column is unopenable in the shell.

**Files:**
- Modify: `src/loci/lake.clj` — the `unwrap` function (search for `(defn- unwrap`)
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/lake_test.clj`:

```clojure
(deftest unwrap-renders-temporal-types-as-iso-strings
  ;; DuckDB's CSV sniffer gives LocalDate for DATE and java.sql.Timestamp for
  ;; TIMESTAMP. json/write-str throws on the first and shifts the second
  ;; through the JVM default zone. Measured 2026-08-19:
  ;;   (json/write-str {:d (java.sql.Date/valueOf "2026-01-02")})
  ;;   => "{\"d\":\"2026-01-01T23:00:00Z\"}"   ; wrong by a day
  (lf/with-lake [lk]
    (let [f (java.io.File/createTempFile "unwrap-" ".csv")]
      (try
        (spit f "day,when,amount,flag\n2026-01-02,2026-01-02 03:04:05,7,true\n")
        (let [h (lake/create-from-relation!
                 lk "tbl:temporal"
                 [{:raw "day" :col :day} {:raw "when" :col :when}
                  {:raw "amount" :col :amount} {:raw "flag" :col :flag}]
                 (str "read_csv('" (.getAbsolutePath f) "')"))
              r (first (tbl/rows lk h {}))]
          (is (= "2026-01-02" (:day r))
              "a DATE must render as the day the file says, with no zone shift")
          (is (= "2026-01-02T03:04:05" (:when r))
              "a TIMESTAMP must render as written, with no zone applied")
          (is (= 7 (:amount r)))
          (is (= true (:flag r)))
          (is (string? (json/write-str r))
              "the whole row must survive json/write-str — this is the defect"))
        (finally (.delete f))))))
```

Add `[clojure.data.json :as json]` to the `loci.lake-test` ns `:require` if it is not already there.

`create-from-relation!` does not exist yet, so this test also drives Task 2's helper. Write Task 2 first if you prefer; the plan orders them this way because `unwrap` is the smaller change and the one with the measured motivation.

- [ ] **Step 2: Run test to verify it fails**

```clojure
(require '[loci.lake-test] :reload)
(clojure.test/test-vars [#'loci.lake-test/unwrap-renders-temporal-types-as-iso-strings])
```

Expected: FAIL — `Unable to resolve symbol: create-from-relation!`. Complete Task 2's Step 3 first, then re-run and expect FAIL on the `:day` assertion with a `LocalDate` where a string was wanted.

- [ ] **Step 3: Grow `unwrap`**

In `src/loci/lake.clj`, replace the body of `unwrap` (keep the existing docstring and append the paragraph below to it):

```clojure
  [v]
  (condp instance? v
    org.duckdb.JsonNode      (json/read-str (str v))
    java.time.LocalDate      (str v)
    java.time.LocalDateTime  (str v)
    java.time.LocalTime      (str v)
    java.sql.Timestamp       (str (.toLocalDateTime ^java.sql.Timestamp v))
    java.sql.Date            (str (.toLocalDate ^java.sql.Date v))
    java.sql.Time            (str (.toLocalTime ^java.sql.Time v))
    v))
```

Append to `unwrap`'s docstring:

```
   **Temporal types render through `java.time`, never through `java.sql`.**
   `read_csv` types a date column DATE, and JDBC hands it back as
   `java.time.LocalDate` — a class `clojure.data.json` refuses outright, exactly
   as it refuses `JsonNode`:

     Don't know how to write JSON of class java.time.LocalDate

   `java.sql.Timestamp` is the worse case because it does NOT throw. It
   serializes through the JVM default zone, so `2026-01-02` comes back
   `"2026-01-01T23:00:00Z"` — measured 2026-08-19, wrong by a day and silent.
   `.toLocalDateTime` / `.toLocalDate` take the wall-clock value the file
   actually contains and `str` renders it ISO-8601 with no zone applied, which
   is what a CSV cell meant.
```

- [ ] **Step 4: Run test to verify it passes**

```clojure
(require '[loci.lake] :reload)
(require '[loci.lake-test] :reload)
(clojure.test/test-vars [#'loci.lake-test/unwrap-renders-temporal-types-as-iso-strings])
```

Expected: PASS, 5 assertions.

- [ ] **Step 5: Prove the test bites**

Change the `java.time.LocalDate` line to `java.time.LocalDate v` (pass it through unchanged), `:reload`, re-run. Expected: FAIL on the `:day` assertion **and** on `json/write-str`. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "fix: unwrap renders DuckDB's temporal types, through java.time"
```

---

### Task 2: `create-from-relation!` — materialise from any SQL relation

The one statement that turns a relation expression into today's unchanged `{:store :lake}` handle. `put-rows!` keeps its NDJSON path for rows already in RAM; this is the path for rows that must never be.

**Files:**
- Modify: `src/loci/lake.clj` — after `load-rows!` (search for `(defn- load-rows!`)
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/lake_test.clj`:

```clojure
(deftest create-from-relation-projects-raw-headers-onto-loci-columns
  (lf/with-lake [lk]
    (let [f (java.io.File/createTempFile "rel-" ".csv")]
      (try
        (spit f "Total Revenue ($),Region\n5,north\n7,south\n")
        (let [h (lake/create-from-relation!
                 lk "tbl:rev"
                 [{:raw "Total Revenue ($)" :col :total_revenue} {:raw "Region" :col :region}]
                 (str "read_csv('" (.getAbsolutePath f) "')"))]
          (is (= :lake (:store h)) "materialise yields today's handle, unchanged")
          (is (= [:total_revenue :region] (:cols h)))
          (is (= 2 (:rows h)) "the row count comes from the table, not from a caller")
          (is (number? (:snapshot h)))
          (is (= [{:total_revenue 5 :region "north"}
                  {:total_revenue 7 :region "south"}]
                 (tbl/rows lk h {})))
          (is (= 2 (tbl/row-count lk h))))
        (finally (.delete f))))))
```

- [ ] **Step 2: Run test to verify it fails**

```clojure
(require '[loci.lake-test] :reload)
(clojure.test/test-vars [#'loci.lake-test/create-from-relation-projects-raw-headers-onto-loci-columns])
```

Expected: FAIL — `Unable to resolve symbol: create-from-relation!`.

- [ ] **Step 3: Implement it**

In `src/loci/lake.clj`, immediately after `load-rows!`, add:

```clojure
(defn- projection
  "`\"Total Revenue ($)\" AS \"total_revenue\", …` from `describe`'s column list.

   Named rather than `SELECT *` on purpose, and the reason is passthrough: a
   named projection THROWS when the file's header changes, where `SELECT *`
   would quietly return a differently-shaped table. That throw is what
   `hydrate` turns into `:table/source-drift`."
  [cols]
  (str/join ", " (map (fn [{:keys [raw col]}] (str (ident raw) " AS " (ident col))) cols)))

(defn create-from-relation!
  "Materialise `rel` — any SQL relation expression — into a new lake table, and
   return the handle. The rows never enter this process: DuckDB reads the file
   and writes Parquet, and loci sees a count.

   The counterpart to `load-rows!`, which is the path for rows ALREADY in RAM.
   Both mint a table name per call, because writes never overwrite.

   `cols` is `loci.source/describe`'s output: `[{:raw \"…\" :col :…} …]`."
  [lk id cols rel]
  (let [table (table-name id (System/nanoTime))]
    (x! lk (str "CREATE TABLE " table " AS SELECT " (projection cols) " FROM " rel))
    {:store :lake :table table :snapshot (current-snapshot lk)
     :rows  (:n (first (q lk (str "SELECT count(*) AS n FROM " table))))
     :cols  (mapv :col cols)}))
```

- [ ] **Step 4: Run test to verify it passes**

```clojure
(require '[loci.lake] :reload)
(require '[loci.lake-test] :reload)
(clojure.test/test-vars [#'loci.lake-test/create-from-relation-projects-raw-headers-onto-loci-columns])
```

Expected: PASS, 6 assertions. Now re-run Task 1's test too; it should pass.

- [ ] **Step 5: Prove the test bites**

Change `projection` to return `"*"`, `:reload`, re-run. Expected: FAIL on the `(tbl/rows lk h {})` assertion, with

```
Binder Error: Referenced column "total_revenue" not found in FROM clause!
Candidate bindings: "Total Revenue ($)"
```

**Not** on `(:cols h)`, which passes under the sabotage: the handle's `:cols` is echoed from the caller's argument rather than read back from the table, so it reports what `describe` claimed and not what the CREATE actually built. That is correct — `describe` read the real file — but it means the handle cannot self-check, and the read is what catches a projection that did not do its job. Restore afterwards.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: materialise a lake table from any SQL relation expression"
```

---

### Task 3: `check-names!` extracted from `normalize-rows`

`describe` must refuse two CSV headers that normalize onto one column, with the same message and the same ex-data `normalize-rows` already produces for rows. Extract it rather than write it twice.

**Files:**
- Modify: `src/loci/tablestore.clj` — `collisions` (private) and `normalize-rows`
- Test: `test/loci/tablestore_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/tablestore_test.clj`:

```clojure
(deftest check-names-refuses-two-names-that-become-one-column
  (is (nil? (tbl/check-names! ["Total Revenue ($)" "Region"]))
      "names that normalize apart pass silently")
  (let [e (try (tbl/check-names! ["a b" "a_b"]) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "two names normalizing to one column must throw")
    (is (= :a_b (:column (ex-data e))))
    (is (= ["a b" "a_b"] (sort (map str (:from (ex-data e)))))))
  (let [e (try (tbl/normalize-rows [{"a b" 1 :a_b 2}]) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :a_b (:column (ex-data e)))
        "normalize-rows must keep throwing exactly as it did, through the same check")))
```

- [ ] **Step 2: Run test to verify it fails**

```clojure
(require '[loci.tablestore-test] :reload)
(clojure.test/test-vars [#'loci.tablestore-test/check-names-refuses-two-names-that-become-one-column])
```

Expected: FAIL — `Unable to resolve symbol: check-names!`.

- [ ] **Step 3: Extract it**

In `src/loci/tablestore.clj`, replace the private `collisions` fn with these two (keep `collisions`' existing docstring on `name-collisions`, and add the sentence below):

```clojure
(defn- name-collisions
  "The source names `col-kw` would collapse onto one column, as
   `{normalized [source …]}` — empty whenever the normalization is injective,
   which it is for all 31 tables in the corpus (measured 2026-08-16).

   Takes NAMES rather than rows, so a CSV header and a vector of row maps run
   the same check: `loci.source/describe` calls `check-names!` on the header
   before anything is created, and `normalize-rows` calls it on every key any
   row has.

   Across the whole table rather than per row, deliberately. A per-row check
   passes a ragged table whose row 1 says `\"a b\"` and whose row 2 says `:a_b`:
   no single row loses a value there, and yet the table still ends with one
   column fed by two different names. `:cols` is a table-level fact, so the
   invariant that produces it is one too — and the table-level check is a strict
   superset, since a row is a table of one.

   Private: `check-names!` is the only caller and the only thing anyone should
   need. A reader that wants to LIST collisions rather than refuse them can have
   this made public on the day it exists."
  [names]
  (into (sorted-map)
        (keep (fn [[k ks]] (when (next ks) [k (vec (sort-by pr-str ks))])))
        (group-by sub/col-kw (set names))))

(defn check-names!
  "Throw if two of `names` would become one column. Returns nil otherwise.

   Refusing rather than merging is the register this codebase already keeps:
   `put-rows!` throws on a table with no rows, and `safe-event` throws rather
   than write a line it could not read back. `{\"a b\" 1, :a_b 2}` loses a whole
   column — both keys become `:a_b`, one value wins, and nothing downstream can
   tell, because `:cols` and the rows still agree with each other perfectly."
  [names]
  (let [cs (name-collisions names)]
    (when (seq cs)
      (let [[k ks] (first cs)]
        (throw (ex-info (str "two column names would become one column: "
                             (str/join " and " (map pr-str ks))
                             " both normalize to " k " — refusing to merge them")
                        {:column k :from ks :collisions cs}))))))
```

Then in `normalize-rows`, replace the opening `(let [cs (collisions rows)] …)` block with:

```clojure
  (check-names! (into #{} (mapcat keys) rows))
```

leaving the `nested-value` check and the final `mapv` exactly as they are.

- [ ] **Step 4: Run tests to verify they pass**

```clojure
(require '[loci.tablestore] :reload)
(require '[loci.tablestore-test] :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

Expected: PASS, 0 failures — the new test **and** every existing one, because the behaviour is unchanged.

- [ ] **Step 5: Prove the test bites**

Make `check-names!` return `nil` unconditionally, `:reload`, re-run. Expected: FAIL on both the new test and the pre-existing collision test in `loci.tablestore-test`. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/tablestore.clj test/loci/tablestore_test.clj
git commit -m "refactor: one column-name collision check, for headers as well as rows"
```

---

### Task 4: `loci.sourcespec` — `locate` and `relation`

The two pure functions. No lake, no file, no network, so every test here is an `=`.

**Files:**
- Create: `src/loci/sourcespec.clj`
- Test: `test/loci/sourcespec_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/loci/sourcespec_test.clj`:

```clojure
(ns loci.sourcespec-test
  (:require [clojure.test :refer [deftest is]]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]))

(deftest locate-resolves-loci-relative-to-the-data-dir
  (is (= (str (sub/data-dir) "/files/sales.csv")
         (ssp/locate "loci://files/sales.csv"))))

(deftest locate-passes-a-file-uri-through-as-a-path
  (is (= "/data/sales.csv" (ssp/locate "file:///data/sales.csv")))
  (is (= "/data/with space.csv" (ssp/locate "file:///data/with%20space.csv"))))

(deftest locate-refuses-a-loci-path-that-escapes-the-data-dir
  ;; loci:// MEANS "under the data dir" — that is what makes a substrate
  ;; portable between machines. A path that leaves it is a file:// URI, which
  ;; is unrestricted and needs no disguise.
  (doseq [bad ["loci://../etc/passwd" "loci://files/../../etc/passwd" "loci:///abs" "loci://"]]
    (is (thrown? clojure.lang.ExceptionInfo (ssp/locate bad)) (str "must refuse " bad))))

(deftest locate-names-the-phase-when-it-does-not-know-a-scheme
  (let [e (try (ssp/locate "s3://bucket/x.csv") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"loci://" (.getMessage e)) "the error must say what it DOES know")))

(deftest relation-builds-one-reader-call-per-format
  (is (= "read_csv('/tmp/a.csv')"
         (ssp/relation {:format :csv} "/tmp/a.csv")))
  (is (= "read_parquet('/tmp/a.parquet')"
         (ssp/relation {:format :parquet} "/tmp/a.parquet")))
  (is (= "read_json_auto('/tmp/a.json')"
         (ssp/relation {:format :json} "/tmp/a.json"))))

(deftest relation-orders-options-so-the-text-is-deterministic
  (is (= "read_csv('/tmp/a.csv', delim=';', header=true, sample_size=1024)"
         (ssp/relation {:format :csv :options {:sample_size 1024 :header true :delim ";"}}
                       "/tmp/a.csv"))))

(deftest relation-escapes-a-quote-in-the-location
  (is (= "read_csv('/tmp/o''brien.csv')"
         (ssp/relation {:format :csv} "/tmp/o'brien.csv"))))

(deftest relation-refuses-an-option-name-that-is-not-an-identifier
  ;; :options reach here from a JSON body, and an option NAME cannot be quoted
  ;; the way a value can — DuckDB wants a bare identifier. So it is checked.
  (doseq [bad ["header, x" "1=1" "" "a b" "he'ader"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ssp/relation {:format :csv :options {bad true}} "/tmp/a.csv"))
        (str "must refuse option name " (pr-str bad)))))

(deftest relation-refuses-an-option-value-it-cannot-render
  (is (thrown? clojure.lang.ExceptionInfo
               (ssp/relation {:format :csv :options {:columns {"a" "INT"}}} "/tmp/a.csv"))
      "a map is not a reader option value, and must not be interpolated raw"))

(deftest relation-refuses-an-unknown-format
  (let [e (try (ssp/relation {:format :xlsx} "/tmp/a.xlsx") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"csv" (.getMessage e)) "the error must list the formats it knows")))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.sourcespec-test] :reload)
```

Expected: FAIL — `Could not locate loci/sourcespec__init.class`.

- [ ] **Step 3: Implement it**

Create `src/loci/sourcespec.clj`:

```clojure
(ns loci.sourcespec
  "What a source IS, as two pure functions: a location URI becomes something
   DuckDB can address, and a source becomes the SQL relation expression that
   reads it.

   No lake, no file, no network, no clock — so every test here is an `=`, and
   the connector-specific part of the framework is the part you can be sure of
   without a fixture.

   It is also what breaks the cycle. `loci.lake` needs `relation` to read a
   `:source` handle; `loci.source` needs `loci.lake` to run anything. Both
   require this, and this requires neither."
  (:require [clojure.string :as str]
            [loci.substrate :as sub]))

(def readers
  "format → the DuckDB function that reads it. Phase 1's whole connector.

   Excel is absent deliberately: it needs a loadable extension, and shipping one
   into the image is a problem that cost two commits in August 2026
   (`f8f1c94`, `30d3172`). It is a connector, and it can be added the day
   someone has an `.xlsx`."
  {:csv "read_csv" :parquet "read_parquet" :json "read_json_auto"})

(def ^:private loci-prefix "loci://")

(defn locate
  "A location URI → a string DuckDB can address.

   `loci://files/x.csv` resolves under `<LOCI_DATA>`, and that is the scheme
   that makes a substrate portable: the log records no machine path, so the same
   event resolves in a container, on another laptop, or out of a restored
   backup. Which is why a `loci://` path that climbs out of the data directory
   is refused — not as a guard, but because such a path is not a loci path. A
   host path is spelled `file://`, which is unrestricted by decision."
  [uri]
  (let [uri (str uri)]
    (cond
      (str/starts-with? uri loci-prefix)
      (let [rel (subs uri (count loci-prefix))]
        (when (or (str/blank? rel)
                  (str/starts-with? rel "/")
                  (some #{".."} (str/split rel #"/")))
          (throw (ex-info (str "not a loci:// location: " uri
                               " — loci:// names a path under the data directory."
                               " Use file:// for a path outside it.")
                          {:uri uri})))
        (str (sub/data-dir) "/" rel))

      (str/starts-with? uri "file://")
      ;; `java.net.URI.` throws its own `URISyntaxException` before the blank
      ;; check can run — a bare `file://` gives "Expected authority at index 7",
      ;; which names neither loci nor the thing the caller typed. Every other
      ;; branch here answers with an `ex-info` carrying `:uri`, and `describe`'s
      ;; catch turns the message into what the shell shows, so this one does too.
      (let [p (try (.getPath (java.net.URI. uri))
                   (catch java.net.URISyntaxException e
                     (throw (ex-info (str "not a usable file:// location: " uri
                                          " — " (.getMessage e))
                                     {:uri uri} e))))]
        (when (str/blank? p)
          (throw (ex-info (str "file:// location has no path: " uri) {:uri uri})))
        p)

      :else
      (throw (ex-info (str "loci does not know this location: " uri
                           " — it knows loci:// and file://."
                           " Remote schemes (s3://, https://) arrive with httpfs.")
                      {:uri uri})))))

(defn- sql-str
  "A value as a single-quoted SQL string literal."
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

(def ^:private option-name
  "What may appear to the left of the `=` in a reader option: a SQL identifier,
   and nothing else.

   `:options` arrive from a JSON body on `/api/source/connect`, which makes this
   the one place in the framework where a caller chooses SQL *text* rather than a
   value. `sql-str` quotes a value; there is no equivalent for an option NAME,
   because DuckDB wants a bare identifier there and a quoted one is a syntax
   error. So the name is CHECKED rather than escaped, and anything that is not an
   identifier is refused."
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn- opt-sql [[k v]]
  (let [k (name k)]
    (when-not (re-matches option-name k)
      (throw (ex-info (str "not a reader option name: " (pr-str k)) {:option k})))
    (str k "="
         (cond (string? v)  (sql-str v)
               (boolean? v) (if v "true" "false")
               (number? v)  v
               :else (throw (ex-info (str "a reader option takes a string, a number or a boolean — "
                                          (pr-str k) " got " (pr-str v))
                                     {:option k :value v}))))))

(defn relation
  "A source → the SQL relation expression that reads it at `location`.

   Options are emitted in key order so the text is deterministic and the whole
   function is testable with `=`. DuckDB does not care about the order.

   Ordered by the key's NAME rather than the key, because `sort-by key` is a
   partial comparator: two options whose keys are a string and a keyword —
   reachable by hand from the REPL facade, if not from a JSON body — throw a
   `ClassCastException` out of the sort before `opt-sql` can refuse them with a
   sentence. Same order for a keyword-only map, one word longer, and the
   friendly error always wins."
  [{:keys [format options]} location]
  (let [f (get readers format)]
    (when-not f
      (throw (ex-info (str "loci does not know the format " (pr-str format)
                           " — it knows " (str/join ", " (sort (map name (keys readers)))) ".")
                      {:format format})))
    (str f "(" (sql-str location)
         (when (seq options)
           (str ", " (str/join ", " (map opt-sql (sort-by (comp name key) options)))))
         ")")))
```

- [ ] **Step 4: Run tests to verify they pass**

```clojure
(require '[loci.sourcespec] :reload)
(require '[loci.sourcespec-test] :reload)
(clojure.test/run-tests 'loci.sourcespec-test)
```

Expected: PASS, 12 tests, 0 failures.

- [ ] **Step 5: Prove the tests bite**

Delete the `(some #{".."} …)` clause from `locate`, `:reload`, re-run. Expected: FAIL on `locate-refuses-a-loci-path-that-escapes-the-data-dir`. Restore. Then delete the `re-matches option-name` guard, `:reload`, re-run. Expected: FAIL on `relation-refuses-an-option-name-that-is-not-an-identifier`, and read the SQL it produces — `read_csv('/tmp/a.csv', 1=1=true)` is the shape the guard exists to stop. Restore. Then **delete the sort entirely** — `(map opt-sql options)` — `:reload`, re-run. Expect FAIL on `relation-orders-options-so-the-text-is-deterministic` with the options in hash order.

Do **not** try `sort-by identity` as the sabotage: it is not a distinguishing mutation, and measuring why is worth more than the sabotage was. A `MapEntry` is a `clojure.lang.APersistentVector`, so `identity` compares `[k v]` element-wise — key first, value only on a tie — and map keys are unique, so the value is never consulted. `sort-by identity` and `sort-by key` are provably the same for any map with mutually-comparable keys. Removing the sort is the only mutation that changes the output.

- [ ] **Step 6: Commit**

```bash
git add src/loci/sourcespec.clj test/loci/sourcespec_test.clj
git commit -m "feat: a source is a location and a format, as two pure functions"
```

---

### Task 5: `source/describe` — the one query that proves it reads

**Files:**
- Create: `src/loci/source.clj`
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/loci/source_test.clj`:

```clojure
(ns loci.source-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.source :as src]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

;; `with-temp-lake` rather than `with-lake`, from the first test: it REBINDS
;; `lake/lake`, and `source/connect!` reads that var. A local lake would leave
;; every later task's tests writing to `<data-dir>/lake`.
(use-fixtures :once lf/with-temp-lake)

(defn- csv!
  "A temp CSV holding `text`, as a file:// uri. Never cleaned up inside a test —
   the OS temp dir is the fixture."
  [text]
  (let [f (java.io.File/createTempFile "src-" ".csv")]
    (spit f text)
    (str "file://" (.getAbsolutePath f))))

(deftest describe-names-the-columns-and-the-types
  (let [lk  @lake/lake
        uri (csv! "Total Revenue ($),Region,day\n5,north,2026-01-02\n")
        d   (src/describe lk {:uri uri :format :csv})]
    (is (= [{:raw "Total Revenue ($)" :col :total_revenue :type "BIGINT"}
            {:raw "Region"            :col :region        :type "VARCHAR"}
            {:raw "day"               :col :day           :type "DATE"}]
           (:cols d)))
    (is (= (str "read_csv('" (subs uri 7) "')") (:relation d))
        "describe returns the relation it proved, so connect! cannot rebuild it differently")))

(deftest describe-refuses-two-headers-that-become-one-column
  (let [uri (csv! "a b,a_b\n1,2\n")
        e   (try (src/describe @lake/lake {:uri uri :format :csv}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "two headers normalizing to one column must be refused at connect")
    (is (= :a_b (:column (ex-data e))))))

(deftest describe-fails-on-a-file-that-is-not-there
  (is (thrown? Exception
               (src/describe @lake/lake {:uri "file:///no/such/file.csv" :format :csv}))
      "a bad location must fail at connect, not at first render"))

(deftest describe-reads-parquet-and-json-as-well-as-csv
  ;; Three formats ship in phase 1 because three prove the format axis where
  ;; one only asserts it. Parquet is written BY duckdb so the test needs no
  ;; second library.
  (let [lk @lake/lake
        pq (str (System/getProperty "java.io.tmpdir") "/src-" (System/nanoTime) ".parquet")
        js (java.io.File/createTempFile "src-" ".json")]
    (lake/x! lk (str "COPY (SELECT 1 AS a, 'x' AS b) TO '" pq "' (FORMAT parquet)"))
    (spit js "[{\"a\":1,\"b\":\"x\"}]")
    (is (= [:a :b] (mapv :col (:cols (src/describe lk {:uri (str "file://" pq)
                                                       :format :parquet})))))
    (is (= [:a :b] (mapv :col (:cols (src/describe lk {:uri (str "file://" (.getAbsolutePath js))
                                                       :format :json})))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.source-test] :reload)
```

Expected: FAIL — `Could not locate loci/source__init.class`.

- [ ] **Step 3: Implement `describe`**

Create `src/loci/source.clj`:

```clojure
(ns loci.source
  "Connecting an external source to loci: describe it, land it, refresh it.

   The facade, in the sense CLAUDE.md's *one writer, three ways to reach
   storage* means — public, and meant to be called from a REPL attached to the
   live server as well as from the HTTP routes. `describe` **is** the dry run,
   which is why nothing here takes a `:dry-run?` flag.

   `loci.sourcespec` holds the pure half: URI → location, source → SQL."
  (:require [clojure.string :as str]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn describe
  "The columns `src` has, and the relation expression that reads them:
   `{:cols [{:raw \"Total Revenue ($)\" :col :total_revenue :type \"BIGINT\"} …]
     :relation \"read_csv('…')\"}`

   One query, doing three jobs at once, which is why materialise and passthrough
   cannot disagree about what the columns are:

   1. **Proof the source reads.** A bad path fails here, not at first render.
   2. **The raw → normalized column map**, through `sub/col-kw`. Both modes
      build their projection from this.
   3. **The schema a preview shows**, before anything is committed.

   Throws with `check-names!`'s ex-data when two headers would become one
   column — the same refusal `normalize-rows` makes for rows."
  [lk src]
  (let [rel  (ssp/relation src (ssp/locate (:uri src)))
        rows (lake/q lk (str "DESCRIBE SELECT * FROM " rel))
        cols (mapv (fn [r] {:raw  (:column_name r)
                            :col  (sub/col-kw (:column_name r))
                            :type (:column_type r)})
                   rows)]
    ;; Kept, and **not** the guard against an empty file, which was what it was
    ;; written for. Measured 2026-08-19: DuckDB's auto-detection never returns
    ;; zero columns for one. A zero-byte CSV and a newline-only CSV both describe
    ;; as a single fabricated `column0 VARCHAR`; an empty JSON file and `[]` both
    ;; describe as a single `json JSON`. So connecting an empty source SUCCEEDS,
    ;; with one garbage column and no rows — undoable like any other `:put`, and
    ;; nobody has asked for it to be refused.
    ;;
    ;; It stays because it costs one line and `readers` will grow: a format whose
    ;; reader is less inventive than `read_csv` can return nothing, and the
    ;; alternative to this line is `create-from-relation!` emitting `SELECT  FROM`
    ;; and DuckDB answering with a bare parser error.
    (when (empty? cols)
      (throw (ex-info (str "the source has no columns: " (:uri src)) {:uri (:uri src)})))
    (tbl/check-names! (map :raw cols))
    {:cols cols :relation rel}))
```

- [ ] **Step 4: Run tests to verify they pass**

```clojure
(require '[loci.source] :reload)
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: PASS, 3 tests, 0 failures.

- [ ] **Step 5: Prove the tests bite**

Delete the `(tbl/check-names! …)` line, `:reload`, re-run. Expected: FAIL on `describe-refuses-two-headers-that-become-one-column`. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/source.clj test/loci/source_test.clj
git commit -m "feat: describe a source once, and get its columns, types and relation"
```

---

### Task 6: the `:source` handle — passthrough reads

A passthrough handle **is** the recipe: no catalog object, no snapshot, nothing for GC to track. `Lake` executes it by inlining the relation expression where a `:lake` handle names a table.

**Files:**
- Modify: `src/loci/tablestore.clj` — `handle?`
- Modify: `src/loci/lake.clj` — the `extend-type Lake tbl/TableStore` block and `sample-cache`
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/source_test.clj`:

```clojure
(deftest a-source-handle-reads-the-file-on-every-query
  (let [lk @lake/lake]
    (let [uri (csv! "a,b\n1,x\n2,y\n")
          h   {:store :source :uri uri :format :csv
               :cols [:a :b] :rows 2 :at "2026-08-19T10:00:00Z"}]
      (is (true? (tbl/handle? h)) "a :source map is a handle")
      (is (= [{:a 1 :b "x"} {:a 2 :b "y"}] (tbl/rows lk h {})))
      (is (= [{:a 2 :b "y"}] (tbl/rows lk h {:offset 1 :limit 1})))
      (is (= 2 (tbl/row-count lk h)) "the count is the one recorded at connect")
      (is (nil? (tbl/reclaimed-at lk h)) "nothing can reclaim a file loci does not own")
      ;; the file changes underneath, with no event anywhere
      (spit (subs uri 7) "a,b\n1,x\n2,y\n3,z\n")
      (is (= 3 (count (tbl/rows lk h {}))) "reads go to the file, so they see the new row"))))

(deftest a-source-handle-keys-its-samples-on-id-and-at
  (let [lk @lake/lake]
    (let [uri (csv! "a\nfirst\n")
          h   {:store :source :uri uri :format :csv
               :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}]
      (is (= {:a ["first"]} (tbl/column-samples lk h 3 8)))
      (spit (subs uri 7) "a\nsecond\n")
      (is (= {:a ["first"]} (tbl/column-samples lk h 3 8))
          "same :at, same answer — the synopsis is as of the last connect or refresh")
      (is (= {:a ["second"]} (tbl/column-samples lk (assoc h :at "2026-08-19T11:00:00Z") 3 8))
          "a new :at is a new key, so refresh is what moves a synopsis"))))

(deftest two-source-handles-at-different-paths-do-not-share-samples
  ;; N tables with identical headers is ordinary, and what separates them is the
  ;; PATH, which is already in the FROM text — not the object id. Same `:at`
  ;; deliberately, so the only thing that can be doing the separating is `from`.
  (let [lk @lake/lake
        jan (csv! "a\njanuary\n")
        feb (csv! "a\nfebruary\n")
        at  "2026-08-19T10:00:00Z"]
    (is (= {:a ["january"]}
           (tbl/column-samples lk {:store :source :uri jan :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))
    (is (= {:a ["february"]}
           (tbl/column-samples lk {:store :source :uri feb :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))))

(deftest two-source-handles-at-one-path-share-samples-on-purpose
  ;; The other half, and the reason no object id is in the key: one file is one
  ;; set of bytes, so two handles reading it must agree. A test that only ever
  ;; compares different files cannot tell an id-keyed cache from a path-keyed
  ;; one, and would pass with the key wrong.
  (let [lk  @lake/lake
        uri (csv! "a\nshared\n")
        at  "2026-08-19T10:00:00Z"]
    (is (= {:a ["shared"]}
           (tbl/column-samples lk {:store :source :uri uri :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))
    (spit (subs uri 7) "a\nrewritten\n")
    (is (= {:a ["shared"]}
           (tbl/column-samples lk {:store :source :uri uri :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8))
        "the same path and the same :at is the same cache entry")))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: FAIL — `handle?` returns false for `:source`, and the reads throw.

- [ ] **Step 3: Teach `handle?` the new store**

In `src/loci/tablestore.clj`, change `handle?`'s set and append to its docstring:

```clojure
  (and (map? v) (contains? #{:inline :lake :source} (:store v))))
```

```
   `:source` is the passthrough handle, and it is the odd one: it carries no
   rows and names no storage, because it IS the recipe — a location, a format
   and the columns observed when it was connected. Reading it runs the reader
   function against the file every time.
```

- [ ] **Step 4: Implement the reads**

In `src/loci/lake.clj`, add `[loci.sourcespec :as ssp]` to the ns `:require`, then add above the `extend-type Lake` block:

```clojure
(defn- from-of
  "The FROM text for a handle: a versioned table, or a source's reader call.

   Both modes go through one function so a read cannot resolve differently from
   the projection built beside it."
  [h]
  (if (= :source (:store h))
    (ssp/relation h (ssp/locate (:uri h)))
    (str (:table h) " AT (VERSION => " (long (:snapshot h)) ")")))

(defn- samples-version
  "What `from-of` cannot say: **which bytes**. nil when the FROM text already
   settles it.

   A cache key here does two jobs — separate different tables, and change when
   one table's bytes change — and for a `:lake` handle the FROM text does both
   on its own, because it names the table *and* the snapshot and a write never
   overwrites. Measured 2026-08-19: two `put-rows!` under one object id land in
   `t_tbl_same_d08b834d` and `t_tbl_same_912090c8`. So there is nothing to add,
   and nil is the whole answer.

   A `:source` handle's FROM text names a path, and the file at that path can be
   rewritten with no event anywhere. Its `:at` — the instant of the connect or
   refresh that last looked — is the only thing that moves when the bytes do.

   **The object id is deliberately NOT here.** It looks like it belongs, because
   an id is what separates two tables; but two source handles sharing a path
   share bytes, so sharing their samples is correct rather than a collision, and
   the path is already in the FROM text. An id in this key would be a component
   no test could distinguish — which is worse than absent, because it reads as a
   guarantee.

   **`:at` is a refresh marker, not a version.** Overwrite the file without
   refreshing and the synopsis is stale and nothing detects it. Only the
   synopsis: the ROWS are always live, because they go straight to the file."
  [h]
  (when (= :source (:store h)) (:at h)))
```

Change `sample-cache`'s memoized function to take the key and the FROM text — replace its argument vector and the `FROM` fragment inside the SQL:

```clojure
  (memoize
   (fn [lk _key from cols per-col]
     (into {} (for [c cols]
                [c (mapv #(str (first (vals %)))
                         (q lk (str "SELECT DISTINCT CASE WHEN length(s) > " tbl/sample-max-chars
                                    " THEN substr(s, 1, " tbl/sample-max-chars ") || '…'"
                                    " ELSE s END AS v FROM ("
                                    "SELECT CAST(" (ident c) " AS VARCHAR) AS s"
                                    " FROM " from
                                    " WHERE " (ident c) " IS NOT NULL)"
                                    " ORDER BY v LIMIT " (long per-col))))]))))
```

Append to `sample-cache`'s docstring:

```
   **`_version` is in the argument list only to be part of the memo key**, and it
   is what keeps the soundness argument true now that a passthrough handle
   exists. See `samples-version`: a `:lake` handle contributes nil, because its
   FROM text already names a table and a snapshot that never change; a `:source`
   handle contributes its `:at`, because its FROM text names only a path and the
   file there can be rewritten with no event anywhere.
```

Then replace the four `extend-type Lake` methods that touch a handle:

```clojure
  (rows [lk h {:keys [offset limit]}]
    (q-as lk (str "SELECT " (select-cols (:cols h))
                  " FROM " (from-of h)
                  (when limit  (str " LIMIT "  (long limit)))
                  (when offset (str " OFFSET " (long offset))))
          (:cols h)))

  (row-count [_ h] (:rows h))

  (reclaimed-at [lk h]
    ;; Nothing can reclaim a file loci does not own, so a source handle is
    ;; never reclaimed — its failure modes are unavailable and drift, which
    ;; `hydrate` classifies.
    (when-not (= :source (:store h)) (dropped-at lk (:table h))))

  (column-samples [lk h per-col max-cols]
    (sample-cache lk (samples-version h) (from-of h)
                  (vec (take max-cols (:cols h))) per-col)))
```

- [ ] **Step 5: Run tests to verify they pass**

```clojure
(require '[loci.tablestore] :reload)
(require '[loci.lake] :reload)
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: PASS on both namespaces, 0 failures. `loci.lake-test` matters here — `samples-key` and `from-of` changed the materialised path too, and it must be byte-identical.

- [ ] **Step 6: Prove the tests bite**

Make `samples-version` return `nil` unconditionally, `:reload`, re-run. Expected: FAIL on `a-source-handle-keys-its-samples-on-id-and-at`, third assertion — a new `:at` no longer moves the synopsis, so a refreshed source keeps describing the file it saw first.

Then check the other direction, which is the one that matters for whether the key is honest: **add** `(:uri h)` into `samples-version`'s `:source` branch — so it returns `[(:uri h) (:at h)]` — and re-run. Use `:uri`, a field every handle really carries; adding `(:id h)` proves nothing, because no source handle has an `:id` and the component is always nil whatever the key design. Expected: **everything still passes.** That is the finding, not a failure — it is why the id is not in the key. A component no test can distinguish reads as a guarantee and is not one. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tablestore.clj src/loci/lake.clj test/loci/source_test.clj
git commit -m "feat: a passthrough handle is the recipe, and its samples key on id and at"
```

---

### Task 7: `hydrate`'s three new markers

`hydrate` already degrades a reclaimed table to `:value []` plus a marker a reader must check. Passthrough adds three more states on the same shape.

**Files:**
- Modify: `src/loci/tablestore.clj` — `hydrate`
- Modify: `src/loci/sourcespec.clj` — add `readable?`
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/source_test.clj`:

```clojure
(defn- src-obj [h] {:id (:id h) :kind :table :title "Live" :value h})

(deftest hydrate-marks-a-passthrough-table-as-live
  (let [lk @lake/lake]
    (let [uri (csv! "a,b\n1,x\n")
          o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                        :cols [:a :b] :rows 1 :at "2026-08-19T10:00:00Z"}))]
      (is (= [{:a 1 :b "x"}] (:value o)))
      (is (true? (:table/passthrough o)) "a reader has to be able to tell")
      (is (= 1 (:table/total o)))
      (is (nil? (:table/unavailable o)))
      (is (nil? (:table/source-drift o))))))

(deftest hydrate-degrades-when-the-file-is-gone
  (let [lk @lake/lake]
    (let [uri (csv! "a\n1\n")
          _   (.delete (java.io.File. (subs uri 7)))
          o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                        :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}))]
      (is (= [] (:value o)) "the same shape every downstream guard already handles")
      (is (= 1 (:table/total o)) "the count survives on the handle")
      (is (= [:a] (:table/cols o)))
      (is (string? (:table/unavailable o)) "carrying what the store actually said")
      (is (nil? (:table/source-drift o))))))

(deftest hydrate-degrades-when-the-columns-changed
  (let [lk @lake/lake]
    (let [uri (csv! "a,b\n1,x\n")
          _   (spit (subs uri 7) "c,d\n1,x\n")            ; same file, new header
          o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                        :cols [:a :b] :rows 1 :at "2026-08-19T10:00:00Z"}))]
      (is (= [] (:value o)))
      (is (string? (:table/source-drift o))
          "readable but unqueryable is drift, not unavailability")
      (is (nil? (:table/unavailable o))))))

(deftest hydrate-still-rethrows-a-real-failure-for-a-lake-handle
  (let [lk @lake/lake]
    (is (thrown? Exception
                 (tbl/hydrate lk {:id "tbl:x" :kind :table :title "X"
                                  :value {:store :lake :table "t_does_not_exist"
                                          :snapshot 1 :rows 1 :cols [:a]}}))
        "a broken lake stays a broken lake — only a deliberate loss is swallowed")))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: FAIL — no `:table/passthrough` key, and the two degradation tests throw instead of degrading.

- [ ] **Step 3: Add `readable?` to `loci.sourcespec`**

Append to `src/loci/sourcespec.clj`:

```clojure
(defn readable?
  "True when `uri` names a file this process can read right now.

   The one thing that tells `:table/unavailable` from `:table/source-drift`: a
   query can fail because the bytes are gone, or because they are there and no
   longer match the columns recorded at connect. The first is a missing source,
   the second is a changed one, and a reader does different things about them.

   False rather than a throw for a location `locate` refuses, because this is
   asked on the failure path and must not raise a second error over the first."
  [uri]
  (try (.canRead (java.io.File. ^String (locate uri)))
       (catch Exception _ false)))
```

- [ ] **Step 4: Grow `hydrate`**

In `src/loci/tablestore.clj`, add `[loci.sourcespec :as ssp]` to the ns `:require`, then replace `hydrate`'s body:

```clojure
  [ts o]
  (if (and o (handle? (:value o)))
    (let [ts (force ts)
          h  (:value o)
          pt (= :source (:store h))]
      (try
        (cond-> (assoc o :value       (rows ts h {:limit mold-cap})
                         :table/total (row-count ts h))
          pt (assoc :table/passthrough true))
        (catch Exception e
          (let [gone (assoc o :value [] :table/total (:rows h) :table/cols (:cols h))]
            (cond
              pt (assoc gone :table/passthrough true
                             (if (ssp/readable? (:uri h))
                               :table/source-drift
                               :table/unavailable)
                             (str (.getMessage e)))

              :else
              (if-let [at (try (reclaimed-at ts h) (catch Exception _ nil))]
                (assoc gone :table/reclaimed at)
                (throw e)))))))
    o))
```

Append to `hydrate`'s docstring:

```
   **A passthrough table adds three more shapes on the same rule.** A healthy one
   carries `:table/passthrough true` beside its rows, so a reader can tell that
   its `:table/total` is the count recorded at connect rather than a fact about
   the file right now. A failing one degrades exactly as a reclaimed one does —
   `:value []`, the count and cols off the handle — and carries the store's own
   message under `:table/unavailable` when the file cannot be read, or
   `:table/source-drift` when it can be read but no longer matches the columns
   recorded at connect. Those are different repairs: find the file, versus
   reconnect it.
```

- [ ] **Step 5: Run tests to verify they pass**

```clojure
(require '[loci.sourcespec] :reload)
(require '[loci.tablestore] :reload)
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: PASS, 9 tests, 0 failures.

- [ ] **Step 6: Prove the tests bite**

Make `ssp/readable?` return `true` unconditionally, `:reload`, re-run. Expected: FAIL on `hydrate-degrades-when-the-file-is-gone`. Restore. Then remove the `pt` branch entirely so a source failure falls to the `reclaimed-at` path. Expected: FAIL on both degradation tests **and** the rethrow test must still pass. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/sourcespec.clj src/loci/tablestore.clj test/loci/source_test.clj
git commit -m "feat: hydrate tells a live source from a missing one and a changed one"
```

---

### Task 8: `connect!` and `refresh!`

**Files:**
- Modify: `src/loci/source.clj`
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/source_test.clj`:

```clojure
(deftest connect-materialise-lands-rows-in-the-lake
  (let [lk @lake/lake]
    (let [st (sub/fresh-store)
          r  (src/connect! st {:uri (csv! "Total Revenue ($)\n5\n7\n")
                               :format :csv :mode :materialise :title "Revenue"})
          o  (sub/object st (:id r))]
      (is (= "tbl:revenue" (:id r)) "the id is a readable slug of the title")
      (is (= :lake (:store (:value o))) "materialise yields today's handle")
      (is (= [:total_revenue] (:cols (:value o))))
      (is (= "Revenue" (:title o)))
      (is (= :materialise (:mode (:source o))))
      (is (string? (:at (:source o))))
      (is (= [{:total_revenue 5} {:total_revenue 7}] (:value (tbl/hydrate lk o)))))))

(deftest connect-passthrough-stores-the-recipe-and-creates-no-table
  (let [lk @lake/lake]
    (let [st     (sub/fresh-store)
          before (count (lake/q lk "SELECT table_name FROM duckdb_tables()"))
          r      (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                                   :mode :passthrough :title "Live"})
          h      (:value (sub/object st (:id r)))]
      (is (= :source (:store h)))
      (is (= "tbl:live" (:id h)) "the handle carries its own id, for the sample key")
      (is (string? (:at h))      "and its at, for the same reason")
      (is (= before (count (lake/q lk "SELECT table_name FROM duckdb_tables()")))
          "passthrough must create no catalog object at all"))))

(deftest connect-mints-a-second-id-rather-than-overwriting
  (let [_ @lake/lake]
    (let [st (sub/fresh-store)
          a  (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                               :mode :materialise :title "Sales"})
          b  (src/connect! st {:uri (csv! "a\n2\n") :format :csv
                               :mode :materialise :title "Sales"})]
      (is (= "tbl:sales" (:id a)))
      (is (= "tbl:sales-2" (:id b)) "a taken id is suffixed, never reused"))))

(deftest refresh-commits-a-new-handle-and-undo-restores-the-old-one
  (let [lk @lake/lake]
    (let [st  (sub/fresh-store)
          uri (csv! "a\n1\n")
          r   (src/connect! st {:uri uri :format :csv :mode :materialise :title "R"})
          h1  (:value (sub/object st (:id r)))]
      (spit (subs uri 7) "a\n1\n2\n")
      (src/refresh! st (:id r))
      (let [h2 (:value (sub/object st (:id r)))]
        (is (not= (:table h1) (:table h2)) "refresh materialises a NEW table")
        (is (= 2 (:rows h2)))
        (is (= 2 (count (tbl/rows lk h2 {})))))
      (sub/undo! st)
      (is (= h1 (:value (sub/object st (:id r))))
          "the old handle is still in the log, so undo walks back to it")
      (is (= 1 (count (tbl/rows lk (:value (sub/object st (:id r))) {})))
          "and its rows are still there, because writes never overwrite"))))

(deftest refresh-of-a-passthrough-moves-at-and-cols-and-writes-no-bytes
  (let [_ @lake/lake]
    (let [st  (sub/fresh-store)
          uri (csv! "a\n1\n")
          r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "P"})
          h1  (:value (sub/object st (:id r)))]
      (spit (subs uri 7) "a,b\n1,x\n2,y\n")
      (src/refresh! st (:id r))
      (let [h2 (:value (sub/object st (:id r)))]
        (is (= :source (:store h2)))
        (is (= [:a :b] (:cols h2)) "refresh re-describes")
        (is (= 2 (:rows h2)))
        (is (not= (:at h1) (:at h2)) "at moves, which is what invalidates the synopsis")))))

(deftest refresh-refuses-an-object-with-no-source
  (let [lk @lake/lake]
    (let [st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "tbl:hand"
                       :value (tbl/table-object lk "tbl:hand" "Hand" [{:a 1}])})
      (is (thrown? clojure.lang.ExceptionInfo (src/refresh! st "tbl:hand"))
          "a table nobody connected has no recipe to re-run"))))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: FAIL — `Unable to resolve symbol: connect!`.

- [ ] **Step 3: Implement `connect!` and `refresh!`**

Append to `src/loci/source.clj`:

```clojure
(defn- slug
  "A title → the readable half of an object id: `\"Q3 Revenue ($)\"` → `revenue`
   is wrong, `q3_revenue` is right. Letters, digits and underscores only."
  [title]
  (let [s (-> (str title) str/lower-case
              (str/replace #"[^a-z0-9]+" "_")
              (str/replace #"^_+|_+$" ""))]
    (if (str/blank? s) "table" s)))

(defn- mint-id
  "`tbl:<slug>`, suffixed `-2`, `-3`… until it names nothing in `st`.

   Readable rather than `tbl:csv-<count>-<rand>`, which is one of the
   count-plus-random minters `loci.corpus` already refuses to go through: a
   count falls when an object is removed and then mints an id that is taken."
  [st title]
  (let [objs (sub/objects st)
        base (str "tbl:" (slug title))]
    (if-not (contains? objs base)
      base
      (first (for [n (iterate inc 2)
                   :let [id (str base "-" n)]
                   :when (not (contains? objs id))]
               id)))))

(defn- now-iso [] (str (java.time.Instant/now)))

(defn- land!
  "`src` + `describe`'s answer → the handle to record, for either mode.

   The whole difference between the two modes is these four lines, which is the
   point of the framework: materialise runs one CREATE, passthrough runs
   nothing and keeps the recipe."
  [lk id src d at]
  (if (= :passthrough (:mode src))
    (cond-> {:store :source :uri (:uri src) :format (:format src)
             :cols (mapv :col (:cols d))
             :rows (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " (:relation d)))))
             :at at}
      ;; `:options` are load-bearing on the handle, not decoration: every read
      ;; rebuilds the relation from the handle alone, so a source connected with
      ;; `{:delim ";"}` that did not record it would read right once and then
      ;; fall back to the sniffer forever after. `cond->` rather than an
      ;; unconditional key so a source with no options gets no `:options nil`.
      (seq (:options src)) (assoc :options (:options src)))
    (lake/create-from-relation! lk id (:cols d) (:relation d))))

(defn- source-of
  "The `:source` map to record on the object."
  [src at]
  (cond-> {:uri (:uri src) :format (:format src) :mode (or (:mode src) :materialise) :at at}
    (seq (:options src)) (assoc :options (:options src))))

(defn connect!
  "Read `src` and commit a `:kind :table` object holding its rows or its recipe.

   `src` is `{:uri … :format … :mode … :title … :options …}`. `:mode` defaults
   to `:materialise`. When `space` names a space, a notebook cell referencing
   the new table is appended in the same transaction — `import-csv!`'s shape,
   kept.

   Returns `{:id … :cols … :rows … :mode …}`. Throws whatever `describe` throws:
   a bad location, an unknown format, or two headers that would become one
   column all fail here, before anything is committed."
  ([st src] (connect! st src nil))
  ([st src space]
   (let [lk   @lake/lake
         mode (or (:mode src) :materialise)
         src  (assoc src :mode mode)
         d    (describe lk src)
         at   (now-iso)
         id   (mint-id st (or (not-empty (:title src)) "Table"))
         h    (land! lk id src d at)
         obj  {:id id :kind :table :title (or (not-empty (:title src)) "Table")
               :value h :source (source-of src at)}
         base [{:op :put :id id :value obj}]
         evs  (if space (conj base (nb/append-cell-event st space {:ref id})) base)]
     (sub/commit! st {:op :tx :events evs})
     {:id id :cols (:cols h) :rows (:rows h) :mode mode})))

(defn refresh!
  "Re-run the recipe recorded on `id` and commit the result.

   A `:put`, never a mutation. A materialised refresh creates a NEW lake table
   and leaves the old one in place, so `undo!` walks back to a handle whose rows
   are still there — and `loci.lakegc`, which already groups every version of a
   table by object, is what bounds them. A passthrough refresh moves no bytes;
   it re-describes, and moving `:at` is what lets the synopsis change."
  [st id]
  (let [o (sub/object st id)]
    (when-not (:source o)
      (throw (ex-info (str id " was not connected from a source, so there is nothing to re-run")
                      {:id id})))
    (let [lk  @lake/lake
          src (:source o)
          d   (describe lk src)
          at  (now-iso)
          h   (land! lk id src d at)]
      (sub/commit! st {:op :put :id id
                       :value (assoc o :value h :source (source-of src at))})
      {:id id :cols (:cols h) :rows (:rows h) :mode (:mode src)})))
```

**Why the `:once` fixture matters here:** `connect!` and `refresh!` read `@lake/lake`, and only `lf/with-temp-lake` rebinds that var. `loci.source-test` has used it since Task 5 for exactly this reason.

- [ ] **Step 4: Run tests to verify they pass**

```clojure
(require '[loci.source] :reload)
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: PASS, 15 tests, 0 failures.

- [ ] **Step 5: Prove the tests bite**

In `refresh!`, replace `(land! lk id src d at)` with `(:value o)` (re-point instead of re-read), `:reload`, re-run. Expected: FAIL on `refresh-commits-a-new-handle-and-undo-restores-the-old-one`. Restore. Then make `mint-id` always return `base`. Expected: FAIL on `connect-mints-a-second-id-rather-than-overwriting`. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/source.clj test/loci/source_test.clj
git commit -m "feat: connect a source, and refresh it as a put rather than a mutation"
```

---

### Task 9: the four marker readers refuse on an unavailable or drifted source

`server/recompute` already refuses on `:table/reclaimed` rather than recomputing a derived table from zero rows and committing the empty result over good data. Two other readers report it. All three must treat the new markers the same way, or the first missing file silently overwrites good data.

**There is a fifth reader and it is deliberately not routed.** `server/reclaimed-on` feeds
`table-catalog` and `table-shaped?`, which take **unhydrated** objects on purpose — hydrating a
whole workspace to write a catalog is the cost `table-shape` exists to avoid — so `unusable`,
which reads keys only `hydrate` sets, would be a no-op there. Measured 2026-08-19: a passthrough
source whose file is gone is still listed to the agent by `table-catalog` and still passes
`table-shaped?`. Nothing acts on its rows — `query_table` and `recompute` both refuse through
`unusable` — so no data is at risk; the cost is one wasted agent step and a misleading message
from `compute-clj!`. Closing it needs a filesystem stat per candidate on a path that runs every
agent turn, for a failure nobody has had, so **phase 1 records it rather than closing it**.

**Files:**
- Modify: `src/loci/tools.clj:144` (the `query_table` reader)
- Modify: `src/loci/server.clj:408` (the object payload), `src/loci/server.clj:1331` (`recompute`)
- Test: `test/loci/source_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/source_test.clj`:

```clojure
(deftest unusable-names-every-marker-a-reader-must-refuse-on
  (is (nil? (tbl/unusable {:value [{:a 1}]})))
  (is (re-find #"reclaimed" (tbl/unusable {:table/reclaimed (java.util.Date.)})))
  (is (re-find #"unreachable" (tbl/unusable {:table/unavailable "no such file"})))
  (is (re-find #"columns" (tbl/unusable {:table/source-drift "Binder Error"})))
  (is (nil? (tbl/unusable {:table/passthrough true :value [{:a 1}]}))
      "a healthy live table is usable — the badge is not a refusal"))

(deftest recompute-refuses-a-source-whose-file-is-gone
  ;; `recompute` is private and takes [st working obj]; a derived table names
  ;; its source under `:from` and its transform under `:via`. This is the
  ;; failure it already guards for a RECLAIMED source: an agent-written fn that
  ;; aggregates turns zero rows into `[{:n 0}]`, nothing downstream refuses a
  ;; non-empty result, and `rerun!` commits it over the good table and reports
  ;; it as refreshed. Measured 2026-08-17 as [{:n 100}] -> [{:n 0}].
  (let [st  (sub/fresh-store)
        uri (csv! "a\n1\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Src"})]
    (.delete (java.io.File. (subs uri 7)))
    (sub/commit! st {:op :put :id "tbl:derived-1"
                     :value {:id "tbl:derived-1" :kind :table :title "D"
                             :value [] :from (:id r) :via "lib:top"
                             :params {:by "a" :n "1" :order "desc"}}})
    (let [out (#'server/recompute st {} (sub/object st "tbl:derived-1"))]
      (is (:why out) "recompute must refuse, not rebuild from zero rows")
      (is (re-find #"unreachable" (:why out))))))
```

Add `[loci.server :as server]` to the `loci.source-test` ns `:require`. `#'server/recompute` reaches the private var — this is the one place the plan tests a private function, because the public `rerun!` would need a whole lineage to exercise the same branch.

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
```

Expected: FAIL — `Unable to resolve symbol: unusable`.

- [ ] **Step 3: Add `unusable` to `loci.tablestore`**

Append to `src/loci/tablestore.clj`, after `reclaimed-str`:

```clojure
(defn unusable
  "Why a hydrated table's rows must not be acted on, as one sentence — or nil
   when they may be.

   One function rather than a marker check per reader, because there are now
   four markers and four readers, and the failure this prevents is arithmetic:
   sixteen places to be right, and `recompute` has already been wrong once by
   being ready to rebuild a derived table from zero rows and commit the empty
   result over good data.

   `:table/passthrough` is deliberately NOT here. It is a badge, not a refusal —
   a live table's rows are as real as any other's."
  [o]
  (cond
    (:table/reclaimed o)    (str "its rows were reclaimed on " (reclaimed-str (:table/reclaimed o)))
    (:table/unavailable o)  (str "its source is unreachable — " (:table/unavailable o))
    (:table/source-drift o) (str "its source's columns changed — " (:table/source-drift o)
                                 " — reconnect or refresh it")
    :else nil))
```

- [ ] **Step 4: Route the three readers through it**

In `src/loci/tools.clj`, replace the `(if (:table/reclaimed o) …)` branch at line ~144 so the message comes from `(tbl/unusable o)` instead of being built from `reclaimed-str`. Keep the surrounding shape exactly; only the condition and the string change.

In `src/loci/server.clj` at line ~408, replace `(:table/reclaimed o)` in the `cond` with `(tbl/unusable o)` and carry that string in the payload alongside the existing `:reclaimed` key (keep `:reclaimed` so the shell's current rendering does not break — the browser work is Phase 2).

In `src/loci/server.clj` at line ~1331, replace the `(:table/reclaimed src)` clause of `recompute`'s `cond` with the line below. It must stay **before** the `(nil? rows)` clause, for the reason the existing comment gives: `[]` passes a `nil?` check.

```clojure
      (tbl/unusable src) {:why (str "source " src-id " cannot be read: " (tbl/unusable src))}
```

Bind it as `why` in `recompute`'s **existing** `let` and use it in both places. An earlier draft of this plan justified calling `(tbl/unusable src)` twice by claiming a binding would need a `cond`-to-`let` rewrite; that was simply wrong — `recompute` already opens with a `let` binding `src-id`, `known`, `src`, `rows` and `via`. `unusable` is nil-safe and cheap, so eager evaluation costs nothing even when `src` is nil.

- [ ] **Step 5: Run tests to verify they pass**

```clojure
(require '[loci.tablestore] :reload)
(require '[loci.tools] :reload)
(require '[loci.server] :reload)
(require '[loci.source-test] :reload)
(clojure.test/run-tests 'loci.source-test)
(clojure.test/run-tests 'loci.tools-test)
(clojure.test/run-tests 'loci.server-test)
```

Expected: PASS on all three, 0 failures. `loci.server-test` and `loci.tools-test` matter most — they cover the reclaimed path these readers already had.

- [ ] **Step 6: Prove the tests bite**

Make `unusable` return `nil` for `:table/unavailable`, `:reload`, re-run. Expected: FAIL on `recompute-refuses-a-source-whose-file-is-gone`. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tablestore.clj src/loci/tools.clj src/loci/server.clj test/loci/source_test.clj
git commit -m "feat: one answer for why a table's rows must not be acted on"
```

---

### Task 10: the `/api/source/*` routes

**Three things Task 8 handed forward — deal with them here, they are not optional:**

1. **`connect!` and `refresh!` throw; routes must not.** Every other mutating route in `server.clj`
   returns `{:error …}` rather than a 500. The handlers below already wrap in `try`/`catch`; keep it.
2. **The mint-and-commit monitor is already taken, and it lives in `loci.source`.** Do NOT add a
   second one here. It went into the facade rather than the route because `loci.source` is
   callable from a REPL attached to the live server — a lock in `server.clj` would leave a REPL
   call racing an in-flight request, which is the one case the route cannot see. That is where the
   analogy to `server/materialize-lock` stops: its lock and its facade are the same function.
   Measured before the fix: 8 concurrent `connect!`s minted one id and 7 callers got a success
   response for data unreachable by the id they were handed.
3. **A passthrough `connect!`/`refresh!` runs `SELECT count(*)` over the whole file.** That is a
   full parse of a 500 MB CSV on an explicit user action, which is the right place to pay it —
   but Task 14 must report the number, not assume it.



**Files:**
- Modify: `src/loci/server.clj` — the route `cond` (search for `(= uri "/api/import-csv")`)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/server_test.clj`, matching the file's existing style for calling handlers:

```clojure
(deftest source-describe-previews-without-committing
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            f  (doto (java.io.File/createTempFile "api-" ".csv")
                 (spit "Total Revenue ($)\n5\n"))
            d  (server/source-describe st {:uri (str "file://" (.getAbsolutePath f))
                                           :format :csv})]
        (is (= [:total_revenue] (mapv :col (:cols d))))
        (is (empty? (sub/objects st)) "describe commits nothing — it IS the dry run")))))

(deftest source-connect-then-refresh-over-the-api
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            f  (doto (java.io.File/createTempFile "api-" ".csv")
                 (spit "a\n1\n"))
            u  (str "file://" (.getAbsolutePath f))
            c  (server/source-connect! st {:uri u :format :csv :mode :materialise :title "A"})]
        (is (= "tbl:a" (:openId c)))
        (is (:state c) "connect returns the state payload, like every other mutating route")
        (spit f "a\n1\n2\n")
        (let [r (server/source-refresh! st "tbl:a")]
          (is (= 2 (:rows r)))
          (is (:state r)))))))

(deftest source-connect-returns-an-error-map-rather-than-throwing
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            c  (server/source-connect! st {:uri "file:///no/such.csv" :format :csv})]
        (is (:error c) "a bad location is an error map, not a 500")
        (is (empty? (sub/objects st)))))))
```

Add `[loci.lake-fixture :as lf]` to `loci.server-test`'s `:require` if absent.

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.server-test] :reload)
(clojure.test/test-vars [#'loci.server-test/source-describe-previews-without-committing])
```

Expected: FAIL — `Unable to resolve symbol: source-describe`.

- [ ] **Step 3: Add the handlers**

In `src/loci/server.clj`, next to `import-csv!`, add:

```clojure
(defn source-describe
  "Preview a source: its columns, their types, and the SQL that reads them.

   Commits nothing, which is why `loci.source` has no `:dry-run?` flag — this
   is the dry run, and the shell shows its answer before anything lands.

   `:format` and `:mode` arrive from JSON as strings — `body-json` gives
   `{:format \"csv\"}` — and `loci.sourcespec` keys on keywords, so both are
   coerced at this boundary and nowhere deeper."
  [_st src]
  (try (src/describe @lake/lake (update src :format keyword))
       (catch Exception e {:error (.getMessage e)})))

(defn source-connect!
  ([st src] (source-connect! st src nil))
  ([st src space]
   (try (let [src (update src :format keyword)   ; :format only — never :mode
              r   (src/connect! st src (when (and space (space? st space)) space))]
          (assoc r :state (state-payload st) :openId (:id r)))
        (catch Exception e {:error (.getMessage e)}))))

(defn source-refresh! [st id]
  (try (assoc (src/refresh! st id) :state (state-payload st))
       (catch Exception e {:error (.getMessage e)})))
```

Add `[loci.source :as src]` to the `loci.server` ns `:require`.

Then in the route `cond`, beside `/api/import-csv`:

```clojure
      (= uri "/api/source/describe")(let [b (body-json req)] (json-resp (source-describe st b)))
      (= uri "/api/source/connect") (let [b (body-json req)]
                                      (json-resp (source-connect! st (dissoc b :space) (:space b))))
      (= uri "/api/source/refresh") (let [{:keys [id]} (body-json req)]
                                      (json-resp (source-refresh! st id)))
```

`:format` is coerced in the handlers above. **`:mode` is not, and must not be** — `loci.source/connect!` validates and normalises it itself, refusing anything that is not `:materialise` or `:passthrough` with a named error, precisely because a silently-defaulted mode materialises a file the user asked not to copy. Coercing here as well would put the decision in two places. This test is what holds the `:format` coercion:

```clojure
(deftest source-connect-accepts-a-format-as-a-json-string
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            f  (doto (java.io.File/createTempFile "api-" ".csv") (spit "a\n1\n"))
            c  (server/source-connect! st {:uri (str "file://" (.getAbsolutePath f))
                                           :format "csv" :mode "materialise" :title "S"})]
        (is (nil? (:error c)))
        (is (= "tbl:s" (:openId c)))))))
```

- [ ] **Step 4: Run tests to verify they pass**

```clojure
(require '[loci.server] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: PASS, 0 failures.

- [ ] **Step 5: Prove the tests bite**

Delete the `(update src :format keyword)` coercion, `:reload`, re-run. Expected: FAIL on `source-connect-accepts-a-format-as-a-json-string` with *loci does not know the format "csv"*. Restore.

Then **add** a `:mode` coercion — `(cond-> src (:mode src) (update :mode keyword))` — and re-run. Expected: FAIL on the unknown-mode test. Note what does **not** work as a check here: passing `"passthrough"` through the route and watching a passthrough handle land proves nothing, because `(keyword "passthrough")` gives the same answer whether the route coerced it or the facade did. The discriminating case is an *unknown* mode, where a route-level coercion turns the refusal from `"nonsense"` into `:nonsense` and names something the caller never typed. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: describe, connect and refresh a source over the API"
```

---

### Task 11: delete `parse-csv` and `->val`

`read_csv` becomes the only way a CSV becomes rows. The markdown salvage keeps its regex — extracting `30` from `"~30% (estimated)"` is prose salvage, not type inference, and DuckDB will never do it — but it now returns the extracted **string** and lets DuckDB type it.

**Files:**
- Modify: `src/loci/tools.clj` — delete `->val` and `parse-csv`, change `md-cell-val`
- Modify: `src/loci/server.clj` — `import-csv!` becomes a wrapper
- Test: `test/loci/tools_test.clj`, `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

Replace `parse-csv-sanitizes-headers` in `test/loci/tools_test.clj` (line ~62) with:

```clojure
(deftest import-csv-sanitizes-headers-through-read-csv
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            r  (server/import-csv! st "T" "Key Proponent(s),Total Revenue ($)\nCanon,5\n" nil)
            o  (tbl/hydrate @lake/lake (sub/object st (:openId r)))]
        (is (= [{:key_proponents "Canon" :total_revenue 5}] (:value o))
            "the header normalization is the same; the inference is DuckDB's")))))
```

And change `md-cell-val`'s expectations in `test/loci/tools_test.clj` (line ~35):

```clojure
(deftest md-cell-val-extracts-the-number-as-text-for-duckdb-to-type
  (let [rows (tools/md-table->rows "a | b | c | d\n---|---|---|---\n$1,234 | 2025-06-01 | ~30% | -5")]
    (is (= [{:a "$1,234" :b "2025-06-01" :c "30" :d "-5"}] rows)
        "prose salvage pulls the number OUT as text; DuckDB decides what it is")))
```

- [ ] **Step 2: Run tests to verify they fail**

```clojure
(require '[loci.tools-test] :reload)
(clojure.test/run-tests 'loci.tools-test)
```

Expected: FAIL — `md-cell-val` still returns numbers, and `parse-csv` still exists.

- [ ] **Step 3: Change `loci.tools`**

Delete `->val` and `parse-csv` entirely. Replace `md-cell-val`:

```clojure
(defn- md-cell-val
  "\"~30% (estimated)\" → \"30\"; \"1.5\" → \"1.5\". Letters-first cells stay as
   they are, and so does anything with MORE digits after the number
   (\"$1,234\", \"2025-06-01\") — half-parsing those would be silently wrong.

   **Returns a string, always.** This is prose salvage, not type inference:
   pulling a number out of a model's sentence is something DuckDB will never do,
   and deciding what that number IS is something only DuckDB should do. The
   salvaged rows go on to `read_csv` like every other table, so there is one
   inference in loci and this is not it."
  [s]
  (if-let [m (re-matches #"[^A-Za-z0-9]*?(-?\d+(?:\.\d+)?)[^0-9]*" s)]
    (second m)
    s))
```

Remove `[clojure.data.csv :as csv]` from the ns `:require`. Then remove `org.clojure/data.csv` from `deps.edn` **only if** nothing else uses it — check with `grep -rn "data.csv\|clojure.data.csv" src/ test/ dev/` first, and leave it if anything does.

- [ ] **Step 4: Rewrite `import-csv!`**

In `src/loci/server.clj`, replace `import-csv!` (line ~1949) with:

```clojure
(defn import-csv!
  "Posted CSV **text** → a table, through the same reader as every other source.

   The text is written under `<LOCI_DATA>/files/` and connected as a `loci://`
   source, so this route and a phase-2 upload land identically: one inference,
   one refresh path, and a file that stays reproducible.

   It keeps the in-RAM copy of the posted body, because a JSON string body is
   already the whole file in memory. That is the limit this route has always
   had and the reason the streaming upload route exists in phase 2 — not a
   regression introduced here."
  [st title csv space]
  (try
    (let [name (str (or (not-empty title) "imported") "-" (System/currentTimeMillis) ".csv")
          name (str/replace (str/lower-case name) #"[^a-z0-9._-]+" "-")
          dir  (io/file (sub/data-dir) "files")
          f    (io/file dir name)]
      (.mkdirs dir)
      (spit f csv)
      (src/connect! st {:uri (str "loci://files/" name)
                        :format :csv :mode :materialise
                        :title (or (not-empty title) "Imported CSV")}
                    (when (and space (space? st space)) space)))
    (catch Exception e {:error (.getMessage e)})))
```

The last line must be `(source-connect! st {…} space)`, not `src/connect!` — the route's callers want `{:state … :openId …}` and Task 10's handler is what adds them. **This is a live shell contract, not just a test's:** `resources/public/index.html:1102` reads `res.error`, then `applyState(res.state)` and `openObject(res.openId)`. Break the shape and the paste-CSV flow breaks with it. Two existing tests in `test/loci/server_test.clj` hold that shape and must stay green:

- `every-server-writer-lands-a-handle` (line ~4280) asserts `(tbl/handle? (:value (sub/object st (:openId …))))`. `create-from-relation!` returns `{:store :lake …}`, so this passes unchanged.
- `a-csv-with-only-a-header-is-an-empty-table-not-an-error` (line ~4295) asserts a header-only CSV gives no `:error` and `(= [] (rows-of st (:openId r)))`.

**The header-only case changes shape, and that is a decision, not an accident.** `tbl/table-value` refuses an empty table and commits `:value []`; `create-from-relation!` creates a real, empty lake table and commits a handle with `:rows 0`. The existing test still passes, because it reads through `rows-of`, which hydrates — and hydrating an empty lake table gives `[]` just as a literal `[]` does. Landing the empty table is the simpler rule and the one that keeps `refresh!` working: `[]` is not a handle, so a `:value []` table has nothing to re-run. Add a test that pins the new shape deliberately rather than leaving it to be discovered:

```clojure
(deftest a-header-only-csv-lands-an-empty-lake-table-not-a-literal
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            r  (srv/import-csv! st "C" "region,revenue" nil)
            h  (:value (sub/object st (:openId r)))]
        (is (nil? (:error r)))
        (is (tbl/handle? h) "a header-only CSV lands a handle, where table-value gave []")
        (is (= 0 (:rows h)))
        (is (= [] (:value (tbl/hydrate @lake/lake (sub/object st (:openId r))))))))))
```

- [ ] **Step 5: Run the suites that touch it**

```clojure
(require '[loci.tools] :reload)
(require '[loci.server] :reload)
(require '[loci.tools-test] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.tools-test)
(clojure.test/run-tests 'loci.server-test)
```

Expected: PASS, 0 failures.

- [ ] **Step 6: Prove the tests bite**

Make `md-cell-val` return `s` unchanged, `:reload`, re-run. Expected: FAIL on `md-cell-val-extracts-the-number-as-text-for-duckdb-to-type` — `~30%` instead of `30`. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tools.clj src/loci/server.clj test/loci/tools_test.clj test/loci/server_test.clj deps.edn
git commit -m "refactor: read_csv is the only way a CSV becomes rows"
```

---

### Task 12: correct the docstrings `->val` made true

`chunks.clj` documents `parse-csv`'s artifacts by name. Those paragraphs are now false, and a false docstring about a deleted function is worse than none.

**Files:**
- Modify: `src/loci/chunks.clj:403-410`
- Modify: `src/loci/corpus.clj:647`, `src/loci/corpus.clj:880`

- [ ] **Step 1: Find every one**

```bash
grep -rn "parse-csv\|->val\|100\.0" src/loci/chunks.clj src/loci/corpus.clj src/loci/tablestore.clj
```

- [ ] **Step 2: Rewrite each in place**

Each paragraph currently explains a rendering difference caused by `->val` reading every unquoted decimal as a Double. Replace the *cause* with DuckDB's sniffer and **re-measure the example rather than guessing it** — open the named table in the REPL and read what its sample actually is now:

```clojure
(require '[loci.lake :as lake] '[loci.tablestore :as tbl] '[loci.substrate :as sub])
(let [st (sub/persistent-store)]
  (tbl/column-samples @lake/lake (:value (sub/object st "tbl:csv-19-7071")) 3 8))
```

If the table no longer exists after Task 13's re-seed, say so in the docstring and name a table that does. Do not leave an example that names a column whose rendering you did not check.

- [ ] **Step 3: Commit**

```bash
git add src/loci/chunks.clj src/loci/corpus.clj
git commit -m "docs: the rendering artifacts belonged to ->val, which is gone"
```

---

### Task 13: `loci.corpus` imports through sources

The corpus is the only real CSV ingest in loci and its five fixture files are already on disk. It becomes the first consumer of the file connector.

**Files:**
- Modify: `src/loci/corpus.clj:255`, `:475`, `:1197` (the three `parse-csv` calls)
- Test: `test/loci/corpus_test.clj:642`

- [ ] **Step 1: Read the three call sites and the dry-run rule**

```bash
sed -n '245,260p;465,485p;1190,1205p' src/loci/corpus.clj
```

`mint-hub` takes `ts`, and **a dry run passes an `InlineTable`** so a preview lands no rows in the real lake. That rule must survive: a dry run must not create a lake table and must not leave orphan snapshots. Rows for the *derivations* still have to be in hand either way, because the derivations are functions over rows.

- [ ] **Step 2: Replace `parse-csv` with a read through the lake**

Add to `src/loci/corpus.clj`:

```clojure
(defn- read-rows
  "A corpus CSV → rows, through the same reader every other source uses.

   Materialises into a temp lake table, reads it back, drops it. The corpus
   files are hundreds of rows, so holding them is free — and the point is not
   size but that the corpus's types are DuckDB's types, the same ones a user's
   import gets. `loci.corpus` carrying its own parser is how the fixture came
   to be typed by three regexes that nothing else in loci uses."
  [lk dir file]
  (let [uri (str "file://" (.getAbsolutePath (io/file dir file)))
        d   (src/describe lk {:uri uri :format :csv})
        h   (lake/create-from-relation! lk (str "tbl:corpus-read-" (System/nanoTime))
                                        (:cols d) (:relation d))]
    (try (tbl/rows lk h {})
         (finally (lake/drop-table! lk (:table h))))))
```

Add `[loci.source :as src]` and `[loci.lake :as lake]` to the ns `:require`. Replace all three `(tools/parse-csv (slurp …))` calls with `(read-rows lk dir file)`, threading `lk` from `@lake/lake` at each entry point. Remove `[loci.tools :as tools]` from the `:require` **only if** nothing else in the file uses it.

- [ ] **Step 3: Re-seed and diff**

```clojure
(require '[loci.corpus] :reload)
;; dry run first — it must land nothing
(loci.corpus/import-corpus!)
```

Then run it for real against a **throwaway** `LOCI_DATA`, and write down every column whose type changed:

```clojure
(System/setProperty "loci.data-dir" "/tmp/loci-corpus-reseed")
```

Compare row counts per table against the live store's. Row counts must match exactly; types will not, and the list of what changed is the deliverable of this task.

- [ ] **Step 4: Fix the derivations the new types broke**

The corpus derivations compare with `=` against literals — `(= year (:year %))`, `(= country (:country %))` — and Clojure's `=` is type-strict. A `:year` that was a Long under `->val` and is a Long under DuckDB is fine; a `:month` that was a string and is now a DATE is not. `mint-hub` runs `material-complaint` and the superlatives answer nil on a tie, so some breakage stops the import loudly. **Run the import and fix what it reports**, then re-run until the fact count matches the number the manifest implies.

- [ ] **Step 5: Update `test/loci/corpus_test.clj:642`**

It calls `tools/parse-csv` directly. Point it at `read-rows` (make it non-private, or test through the public import). Run:

```clojure
(require '[loci.corpus-test] :reload)
(clojure.test/run-tests 'loci.corpus-test)
```

Expected: PASS, 0 failures.

- [ ] **Step 6: Re-seed the real store and commit**

```bash
git add src/loci/corpus.clj test/loci/corpus_test.clj
git commit -m "refactor: the corpus imports through the file connector, not its own parser"
```

Note in the commit message which columns changed type. `data/` is disposable — re-seed it and move on.

---

### Task 14: the measurement gate

Taken from the design's stated target, **never from `data/`**. CLAUDE.md is explicit: a measurement over the fixture can establish that a mechanism works, and never that a cost is real.

**Files:**
- Create: nothing. This is measurement, reported in the commit message and pasted into the spec.

- [ ] **Step 1: Generate a 10M-row CSV**

In the REPL:

```clojure
(def big (java.io.File. "/tmp/loci-10m.csv"))
(time
 (with-open [w (clojure.java.io/writer big)]
   (.write w "id,region,amount,day\n")
   (dotimes [i 10000000]
     (.write w (str i "," (nth ["north" "south" "east" "west"] (mod i 4))
                    "," (* i 3) ",2026-01-0" (inc (mod i 9)) "\n")))))
(.length big)
```

- [ ] **Step 2: Measure the new path**

```clojure
(require '[loci.source :as src] '[loci.substrate :as sub])
(def st (sub/fresh-store))
(let [rt (Runtime/getRuntime)]
  (System/gc)
  (let [before (- (.totalMemory rt) (.freeMemory rt))
        r      (time (src/connect! st {:uri (str "file://" (.getAbsolutePath big))
                                       :format :csv :mode :materialise :title "Big"}))
        after  (- (.totalMemory rt) (.freeMemory rt))]
    {:result r :heap-delta-mb (double (/ (- after before) 1024 1024))}))
```

Record wall clock and heap delta. **The claim to prove is that the heap delta does not scale with the file** — re-run with a 1M-row file and confirm the delta is comparable, not a tenth.

- [ ] **Step 3: Measure passthrough at the same size**

```clojure
(def ph (:value (sub/object st (:id (src/connect! st {:uri (str "file://" (.getAbsolutePath big))
                                                      :format :csv :mode :passthrough
                                                      :title "Big live"})))))
(require '[loci.tablestore :as tbl])
(time (count (tbl/rows @loci.lake/lake ph {:limit 2000})))
(time (:rows ph))
```

- [ ] **Step 4: Show the old path cannot do it**

The point of the measurement. Read a 500 MB file into a JSON string and through `clojure.data.csv` in a REPL with loci's normal heap:

```clojure
(time (count (slurp big)))
```

Record what happens — an `OutOfMemoryError`, or a time so long it is its own answer. **If it succeeds**, that is the finding: say so, record the number, and note that the justification for this work is then constant memory rather than feasibility. Do not report a failure that did not happen.

- [ ] **Step 5: Run both suites**

```bash
clojure -M:test
npm run test:browser
```

Both. `clojure -M:test` does not mean "all the tests". Expected: 0 failures in each.

- [ ] **Step 6: Paste the numbers into the spec and commit**

Add a "Measured" section to `docs/superpowers/specs/2026-08-19-external-sources-design.md` under "What to measure before building is done", with the four figures and the date. Then:

```bash
git add docs/superpowers/specs/2026-08-19-external-sources-design.md
git commit -m "measure: a 10M-row CSV through the file connector"
```

---

## Definition of done

- [ ] A `loci://` or `file://` CSV, Parquet or JSON file connects as `:materialise` or `:passthrough`
- [ ] `refresh!` commits a new handle; `undo!` walks back to one whose rows still read
- [ ] A passthrough table renders live, and degrades to `:table/unavailable` / `:table/source-drift` with `:value []`
- [ ] `tbl/unusable` is the single answer, and all four readers go through it
- [ ] `tools/parse-csv` and `tools/->val` do not exist; `grep -rn "parse-csv\|->val" src/ test/` is clean
- [ ] The corpus imports through the connector, and the type changes are written down
- [ ] `clojure -M:test` and `npm run test:browser` both green
- [ ] Every regression test above proved to fail when its fix is reverted
