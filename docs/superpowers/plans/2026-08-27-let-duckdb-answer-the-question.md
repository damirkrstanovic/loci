# Let DuckDB Answer the Question — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop five callers reading a 2,000-row sample and calling it the table — give `TableStore` a way to ask a table a question, and let the model write that question.

**Architecture:** `loci.tablestore`'s protocol gains `query` and `create-from-spec`. `Lake` answers them by building HoneySQL and running SQL; `InlineTable` answers them by folding in Clojure. A new pure namespace `loci.honeyq` turns a view-spec into HoneySQL. Every caller that wanted rows in order to compute over them asks `query` instead. The `sci` sandbox stops receiving `rows` and receives a query surface, so model-written Clojure computes in DuckDB by default.

**Tech Stack:** Clojure 1.12, DuckDB 1.5.5 via `org.duckdb/duckdb_jdbc`, DuckLake, HoneySQL 2.7.1310 (new), `sci` for the sandbox.

**The spec:** `docs/superpowers/specs/2026-08-27-let-the-model-write-a-query-design.md`. Read it before Task 1. Its gate is run; its five inline corrections are the traps this plan avoids.

---

## How to run things in this repo — read before Task 1

`CLAUDE.md` governs and overrides the generic habits of this plan format.

- **Never start a JVM to run Clojure.** `clojure -M -e` costs ~25 s before the first form. Use `mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888, and `:reload` after every edit.
- **Per-step test runs are REPL evals**, shown in each step as `REPL:`.
- **`clojure -M:test` runs ONCE per phase**, before the phase commit. `npm run test:browser` too — `clojure -M:test` does not mean "all the tests".
- **Every test task ends with a sabotage step.** Break the fix, prove the test goes red, put the fix back. A test that passes either way is worse than no test, because it certifies. Five of the assertions written on this project could not fail; do not add a sixth.

To run one test namespace in the REPL:

```clojure
(require '[loci.tablestore-test] :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

To run one test:

```clojure
(require '[loci.honeyq-test] :reload)
(clojure.test/test-vars [#'loci.honeyq-test/filter-becomes-a-where])
```

`clojure.test/test-vars` prints nothing on success and prints the failure on failure. Wrap it to see a count:

```clojure
(clojure.test/run-test-var #'loci.honeyq-test/filter-becomes-a-where)
```

---

## File structure

**Created:**

| file | responsibility |
|---|---|
| `src/loci/honeyq.clj` | a view-spec's *question* half as HoneySQL data. Pure — no store, no lake, no connection |
| `src/loci/sandbox.clj` | the bindings `sci` gets: `q`, `q-reduce`, `q-all`, `keep!`, `table`, `tables`. Owns the run's accumulated events and its `:reads` set |
| `test/loci/honeyq_test.clj` | `->honey` unit tests, no store |
| `test/loci/sandbox_test.clj` | the four verbs against both stores |

**Modified:**

| file | change |
|---|---|
| `deps.edn` | add HoneySQL |
| `src/loci/tablestore.clj` | protocol gains `query` + `create-from-spec`; `InlineTable` implements both; `hydrate`'s contract unchanged |
| `src/loci/lake.clj` | `Lake` implements both; `relation-of` pins the snapshot |
| `src/loci/viewspec.clj` | `interpret` keeps the shape half; the question half moves out; four language additions |
| `src/loci/fnlib.clj` | each builtin gains `:spec`, a params→view-spec fn; `catalog` takes a column profile, not rows |
| `src/loci/tools.clj` | `do-query` uses `query` / `create-from-spec` |
| `src/loci/server.clj` | `table-rows` deleted; `fns-payload`, `fn-preview`, `fn-apply!`, `compute-clj!` rewired; `:ref` blocks |
| `src/loci/content.clj` | `:ref` and `:chart :ref` report blocks |
| `src/loci/agent.clj` | `make-clj-transform`'s prompt |

**Why `loci.honeyq` is its own namespace and not part of `viewspec`:** `viewspec` is required by `tools` and `server` for rendering. `honeyq` is required by `lake` and `tablestore`. Putting the SQL builder in `viewspec` would make the storage layer depend on the render layer.

---

## Phase 1 — the method that was specified and never built

### Task 1: `loci.honeyq` — a view-spec as HoneySQL

**Files:**
- Create: `src/loci/honeyq.clj`
- Create: `test/loci/honeyq_test.clj`
- Modify: `deps.edn:12` (add the dependency)

- [ ] **Step 1: Add the dependency**

In `deps.edn`, in the `:deps` map, after the `org.duckdb/duckdb_jdbc` line:

```clojure
        org.duckdb/duckdb_jdbc {:mvn/version "1.5.5.1"}
        ;; HoneySQL builds the SQL that `Lake/query` runs. 41,815 bytes, and its
        ;; only dependency is Clojure — measured 2026-08-27 against a 166,940,673
        ;; byte uberjar, so 0.025%. Chosen for the 2026-08-16 big-content spec's
        ;; reason, not for size: a query expressed as data is storable, diffable,
        ;; reviewable before it runs, and reversible.
        com.github.seancorfield/honeysql {:mvn/version "2.7.1310"}}
```

- [ ] **Step 2: Write the failing tests**

Create `test/loci/honeyq_test.clj`:

```clojure
(ns loci.honeyq-test
  (:require [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [loci.honeyq :as hq]))

(def R
  "A relation that stands in for a handle, in the shape `relation-of` produces."
  {:select [:*] :from [[[:raw "\"t_a\" AT (VERSION => 3)\""]]]})

(defn- fmt [spec] (first (sql/format (hq/->honey spec R) {:inline true})))

(deftest a-bare-spec-selects-everything
  (is (= "SELECT * FROM (SELECT * FROM \"t_a\" AT (VERSION => 3)\") AS t"
         (fmt {}))))

(deftest group-by-becomes-group-by
  (is (= (str "SELECT region, SUM(revenue) AS revenue "
              "FROM (SELECT * FROM \"t_a\" AT (VERSION => 3)\") AS t "
              "GROUP BY region")
         (fmt {:group_by "region" :measure "revenue" :agg "sum"}))))

(deftest count-does-not-need-a-measure
  (is (= (str "SELECT region, COUNT(*) AS count "
              "FROM (SELECT * FROM \"t_a\" AT (VERSION => 3)\") AS t "
              "GROUP BY region")
         (fmt {:group_by "region" :agg "count"}))))

(deftest min-and-max-are-aggregates
  (is (clojure.string/includes? (fmt {:group_by "region" :measure "v" :agg "min"}) "MIN(v)"))
  (is (clojure.string/includes? (fmt {:group_by "region" :measure "v" :agg "max"}) "MAX(v)")))

(deftest an-unknown-agg-falls-back-to-sum
  (is (clojure.string/includes? (fmt {:group_by "region" :measure "v" :agg "median"}) "SUM(v)")))

(deftest filter-becomes-a-where
  (is (clojure.string/includes? (fmt {:filter {:col "v" :op ">" :val 10}}) "WHERE v > 10")))

(deftest not-equal-becomes-a-where
  (is (clojure.string/includes? (fmt {:filter {:col "region" :op "≠" :val "r1"}})
                                "WHERE region <> 'r1'")))

(deftest contains-becomes-a-case-insensitive-like
  (let [s (fmt {:filter {:col "region" :op "contains" :val "R1"}})]
    (is (clojure.string/includes? s "LIKE"))
    (is (clojure.string/includes? s "'%r1%'"))
    (is (clojure.string/includes? s "LOWER"))))

(deftest sort-and-limit-land
  (let [s (fmt {:group_by "region" :measure "v" :agg "sum" :sort "desc" :limit 5})]
    (is (clojure.string/includes? s "ORDER BY v DESC"))
    (is (clojure.string/includes? s "LIMIT 5"))))

(deftest the-relation-is-never-a-bare-raw
  ;; The 2026-08-27 gate: `:from [R]` formats as `FROM raw AS "..."` — wrong SQL,
  ;; and HoneySQL does not raise. This asserts the nesting that avoids it.
  (is (not (clojure.string/includes? (fmt {}) "FROM raw"))))

(deftest counting-wraps-the-whole-query
  (is (= (str "SELECT count(*) AS n FROM (SELECT region, SUM(revenue) AS revenue "
              "FROM (SELECT * FROM \"t_a\" AT (VERSION => 3)\") AS t GROUP BY region) AS c")
         (first (sql/format (hq/->count (hq/->honey {:group_by "region" :measure "revenue"} R))
                            {:inline true})))))
```

- [ ] **Step 3: Run them and watch them fail**

REPL:

```clojure
(require '[loci.honeyq-test] :reload)
```

Expected: `FileNotFoundException — could not locate loci/honeyq.clj`.

- [ ] **Step 4: Write `src/loci/honeyq.clj`**

```clojure
(ns loci.honeyq
  "A view-spec's QUESTION half, as HoneySQL data. Pure: no store, no lake, no
   connection, no clock.

   `loci.viewspec` keeps the SHAPE half — `:kind`, `:rendered`, chart series —
   and runs over the answer this produces, which is small by construction. That
   split is the whole point: the question goes to DuckDB, the shaping stays in
   Clojure, and neither one grew a second language.

   **Its own namespace, not part of `viewspec`.** `viewspec` is required by the
   render path (`tools`, `server`); this is required by the storage path (`lake`,
   `tablestore`). Folding it into `viewspec` would make storage depend on render.

   **Two traps the 2026-08-27 gate found, both of which HoneySQL emits rather
   than raises:**

   · `{:from [R]}` where `R` is a relation formats as `FROM raw AS \"…\"`,
     because `:from` reads a two-element vector as a table/alias pair. The
     relation must arrive already nested — `relation-of` builds it as a subquery
     map, and `[[relation :t]]` is then ordinary HoneySQL.
   · `[:over [[:sum :x] {} :alias]]` formats as `over AS SUM(x)  alias`. The
     alias belongs OUTSIDE the `:over`: `[[:over [[:sum :x] {}]] :alias]`.

   Neither is caught by a reader and neither raises. What catches them is
   `->count`, which DuckDB parses before any result is trusted."
  (:require [clojure.string :as str]))

(def aggs
  "The aggregates the spec language admits. `min` and `max` are new on
   2026-08-27, so `lib:group`'s five choices all fit the one language."
  #{"sum" "avg" "count" "min" "max"})

(defn- kw [x]
  (when (and x (not (str/blank? (str x)))) (keyword (str x))))

(defn- where-clause
  "One filter as a HoneySQL predicate, or nil.

   `contains` is `LOWER(CAST(col AS VARCHAR)) LIKE '%v%'` and not `ILIKE`: the
   column may be numeric, and `ILIKE` on a DOUBLE is a binder error where the
   cast is not. Lower-casing both sides rather than relying on the collation
   keeps the two stores' answers identical — `loci.fnlib`'s `filter-run` already
   lower-cases both sides in Clojure."
  [{:keys [col op val]}]
  (when-let [c (kw col)]
    (case op
      "="        [:= c val]
      "≠"        [:<> c val]
      ">"        [:> c val]
      "<"        [:< c val]
      "contains" [:like [:lower [:cast c :varchar]]
                  (str "%" (str/lower-case (str val)) "%")]
      nil)))

(defn ->honey
  "`spec` — `group_by` / `measure` / `agg` / `filter` / `sort` / `limit` — against
   `relation`, as one HoneySQL map.

   `relation` must already be a HoneySQL expression that can sit inside
   `[[relation :t]]`. `loci.lake/relation-of` builds it; a test may pass any
   subquery map."
  [spec relation]
  (let [gb   (kw (:group_by spec))
        meas (kw (:measure spec))
        agg  (let [a (or (:agg spec) "sum")] (if (aggs a) a "sum"))
        ycol (keyword (or (some-> meas name) agg))
        sel  (if gb
               [gb [(if (= "count" agg) [:count :*] [(keyword agg) meas]) ycol]]
               :*)
        skey (if gb ycol (or meas ycol))]
    (cond-> {:select sel :from [[relation :t]]}
      gb              (assoc :group-by [gb])
      (:filter spec)  (as-> m (if-let [w (where-clause (:filter spec))] (assoc m :where w) m))
      (:sort spec)    (assoc :order-by [[skey (if (= "desc" (:sort spec)) :desc :asc)]])
      (:limit spec)   (assoc :limit (:limit spec)))))

(defn ->count
  "`SELECT count(*) FROM ( <q> )` — the size of the answer, and the parse check.

   Measured 2026-08-27 over 1,000,000 rows: `GROUP BY` costs 20 ms and this costs
   **6 ms**, because DuckDB answers it from the group cardinality and never
   computes the sums. So counting first is the cheapest thing in the design, not
   a cost to justify."
  [q]
  {:select [[[:count :*] :n]] :from [[q :c]]})
```

- [ ] **Step 5: Run the tests and watch them pass**

REPL:

```clojure
(require '[loci.honeyq] :reload)
(require '[loci.honeyq-test] :reload)
(clojure.test/run-tests 'loci.honeyq-test)
```

Expected: `11 tests, N assertions, 0 failures, 0 errors.`

- [ ] **Step 6: Sabotage — prove `the-relation-is-never-a-bare-raw` bites**

In `src/loci/honeyq.clj`, change `:from [[relation :t]]` to `:from [relation]`. Then:

```clojure
(require '[loci.honeyq] :reload)
(require '[loci.honeyq-test] :reload)
(clojure.test/run-tests 'loci.honeyq-test)
```

Expected: **failures**, and `the-relation-is-never-a-bare-raw` among them. If it stays green, the assertion is not testing what it claims and must be rewritten before you go on.

Put `:from [[relation :t]]` back and re-run. Expected: `0 failures`.

- [ ] **Step 7: Commit**

```bash
git add deps.edn src/loci/honeyq.clj test/loci/honeyq_test.clj
git commit -m "feat: a view-spec's question half is HoneySQL data, and its count is the parse check

loci.honeyq turns group_by/measure/agg/filter/sort/limit into one HoneySQL map
against a relation. Pure — no store, no lake, no connection — and its own
namespace because viewspec is required by the render path and this is required
by the storage path.

Four additions to the language so lib:group's five aggregates fit it: the
filter ops \\u2260 and contains, and the aggregates min and max.

->count wraps the whole query. Measured 2026-08-27 over 1,000,000 rows, GROUP BY
costs 20 ms and counting it costs 6 ms, because DuckDB answers from the group
cardinality. It is also the parse check: HoneySQL emits wrong SQL rather than
raising, and DuckDB is what notices."
```

---

### Task 2: the protocol gains `query` and `create-from-spec`

**Files:**
- Modify: `src/loci/tablestore.clj:14-96` (the `defprotocol`)
- Modify: `src/loci/tablestore.clj` (`InlineTable`'s `extend-type`)
- Test: `test/loci/tablestore_test.clj`

- [ ] **Step 1: Write the failing tests**

Append to `test/loci/tablestore_test.clj`:

```clojure
(def qrows
  (vec (for [n (range 30)]
         {:region (str "r" (mod n 3)) :v (double n) :name (str "n" n)})))

(deftest inline-query-groups-and-totals
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        r  (tbl/query ts h {:group_by "region" :measure "v" :agg "sum"})]
    (is (= 3 (:total r)) ":total is the answer's size, not the page's")
    (is (= 3 (count (:rows r))))
    (is (= #{"r0" "r1" "r2"} (set (map :region (:rows r)))))
    (is (= 435.0 (reduce + (map :v (:rows r)))) "every row is summed, not a sample")))

(deftest inline-query-total-survives-a-limit
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        r  (tbl/query ts h {:limit 4})]
    (is (= 4 (count (:rows r))))
    (is (= 30 (:total r)) ":total must be the full answer, or a report lies about it")))

(deftest inline-query-filters
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)]
    (is (= 20 (:total (tbl/query ts h {:filter {:col "v" :op ">" :val 9}}))))
    (is (= 10 (:total (tbl/query ts h {:filter {:col "region" :op "=" :val "r0"}}))))
    (is (= 20 (:total (tbl/query ts h {:filter {:col "region" :op "≠" :val "r0"}}))))
    (is (= 3  (:total (tbl/query ts h {:filter {:col "name" :op "contains" :val "N1"}})))
        "contains is case-insensitive: n1, n10..n19 minus the ones without — check the count")))

(deftest inline-create-from-spec-returns-a-handle
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        h2 (tbl/create-from-spec ts h {:group_by "region" :measure "v" :agg "sum"}
                                 "tbl:derived-1" "By region")]
    (is (tbl/handle? h2))
    (is (= 3 (:rows h2)))
    (is (= 3 (count (tbl/rows ts h2 {}))))))
```

> **Note on the `contains` count:** `n1` matches `n1`, `n10`–`n19`. `qrows` has 30 rows, so `n1`, `n10`–`n19` is 11 — but only `n1`, `n10`, `n11`, ..., `n19` exist for indexes < 30, giving 11. Set the expected value to **11**, not 3. Fix the assertion above to `(is (= 11 (:total …)))` before running. This is deliberate: verify the arithmetic yourself rather than trusting a number in a plan.

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.tablestore-test] :reload)
```

Expected: `Unable to resolve symbol: query`.

- [ ] **Step 3: Add the two methods to the protocol**

In `src/loci/tablestore.clj`, inside `(defprotocol TableStore …)`, after `column-samples`:

```clojure
  (query [this handle spec]
    "Run `spec`'s question half — `group_by` / `measure` / `agg` / `filter` /
     `sort` / `limit` — against `handle`.

     => `{:rows [...] :total <n> :capped <bool>}`

     **`:total` is the size of the ANSWER, before `:limit`**, and it is not
     optional. It is what lets a caller say \"200 of 900,000\" rather than
     implying 200 is all there is — the same rule the entity page follows with
     `\"50 of 137\"`, for the same reason.

     **`:capped` is true when the store returned fewer rows than `:total`.** A
     capped result is NOT an error. A large answer is a table, and this design
     has `create-from-spec` for exactly that.

     Specified by the 2026-08-16 big-content spec and never built. Its absence is
     why five callers read a `mold-cap` sample and treated it as the table.")
  (create-from-spec [this handle spec id title]
    "The same question, materialised as a new table under `id`. => a handle.

     Named after `loci.lake/create-from-relation!`, which is what the lake
     implementation calls. Measured 2026-08-27: `CREATE TABLE … AS <spec>` over a
     927,830-row answer costs **81 ms and 0 MB of heap**, where pulling the same
     rows through JDBC into Clojure maps costs about 25,000 ms and 214 MB.")
```

- [ ] **Step 4: Implement both on `InlineTable`**

Find `(defrecord InlineTable [] …)` in `src/loci/tablestore.clj` and add, inside it:

```clojure
  (query [this handle spec]
    ;; The inline store folds in Clojure, which is what it has always done and is
    ;; correct here: its rows are IN the handle, so there is nothing to push down
    ;; to. It is the parity reference — `Lake` must agree with it.
    (let [all (vec (rows this handle {}))
          out (vs/question spec all)]
      {:rows   (if-let [l (:limit spec)] (vec (take l out)) out)
       :total  (count out)
       :capped (boolean (and (:limit spec) (> (count out) (:limit spec))))}))
  (create-from-spec [this handle spec id _title]
    (put-rows! this id (:rows (query this handle (dissoc spec :limit)))))
```

Add `[loci.viewspec :as vs]` to `loci.tablestore`'s `:require`.

> **Dependency check:** `loci.viewspec` requires only `clojure.string`. `loci.tablestore` requiring it introduces no cycle. Confirm with `(require '[loci.tablestore] :reload)` — a cycle raises immediately.

- [ ] **Step 5: Split `viewspec/interpret` so `question` exists**

In `src/loci/viewspec.clj`, replace the whole of `interpret` with these two
functions. `question` is `interpret`'s body from its `rows` binding through
`sorted`, with the four new language terms folded in; `interpret` keeps the rest.

```clojure
(defn question
  "The question half of a spec, over rows: filter → group/aggregate → sort.
   **Not limit** — the caller applies that, because it needs the unlimited count
   to report `:total` honestly.

   Extracted from `interpret` on 2026-08-27 so `InlineTable/query` and the shape
   half share ONE implementation of the language rather than two that drift.
   `loci.viewspec-test/interpret-and-question-agree-on-the-question` is what
   notices if they ever do."
  [spec rows]
  (let [gb      (kw (:group_by spec))
        measure (kw (:measure spec))
        agg     (or (:agg spec) "sum")
        rows    (if-let [{:keys [col op val]} (:filter spec)]
                  (let [c (kw col)]
                    (filterv (fn [r]
                               (let [v (get r c)]
                                 (case op
                                   "="  (= (str v) (str val))
                                   "≠"  (not= (str v) (str val))
                                   ">"  (boolean (when-let [a (num v)] (when-let [b (num val)] (> a b))))
                                   "<"  (boolean (when-let [a (num v)] (when-let [b (num val)] (< a b))))
                                   ;; Lower-cases BOTH sides, matching `honeyq`'s
                                   ;; `LOWER(CAST(c AS VARCHAR)) LIKE '%v%'` and
                                   ;; `fnlib/filter-run`, so the two stores answer
                                   ;; a `contains` identically.
                                   "contains" (str/includes? (str/lower-case (str v))
                                                             (str/lower-case (str val)))
                                   true)))
                             rows))
                  (vec rows))
        ycol    (name (or measure (keyword agg)))
        agged   (if gb
                  (->> rows (group-by gb)
                       (mapv (fn [[g rs]]
                               (let [vs (keep #(num (get % measure)) rs)
                                     m  (case agg
                                          "count" (count rs)
                                          "avg"   (round2 (/ (reduce + vs) (double (max 1 (count vs)))))
                                          "min"   (when (seq vs) (apply min vs))
                                          "max"   (when (seq vs) (apply max vs))
                                          (round2 (reduce + vs)))]
                                 (array-map (name gb) (str g) ycol m)))))
                  rows)]
    (if-let [s (:sort spec)]
      (let [key     (if gb ycol (or (some-> measure name)
                                    (name (first (keys (first agged))))))
            ordered (sort-by #(or (num (get % (keyword key))) (str (get % (keyword key))))
                             agged)]
        (vec (if (= s "desc") (reverse ordered) ordered)))
      agged)))

(defn interpret
  "A spec's answer, shaped by `:kind`. The question goes to `question`; what is
   left here is limit, column projection and the four render shapes."
  [spec rows]
  (let [gb      (kw (:group_by spec))
        measure (kw (:measure spec))
        agg     (or (:agg spec) "sum")
        kind    (keyword (or (:kind spec) "table"))
        ycol    (name (or measure (keyword agg)))
        sorted  (question spec rows)
        limited (if-let [l (:limit spec)] (vec (take l sorted)) sorted)
        out     (if-let [cols (:columns spec)]
                  (mapv #(select-keys % (map keyword cols)) limited)
                  limited)]
    (case kind
      :chart {:kind :chart
              :rendered {:chart (or (:chart spec) "bar")
                         :x (name (or gb (first (keys (first out)))))
                         :y (if gb ycol (name (last (keys (first out)))))
                         :rows out}}
      :list  {:kind :list  :rendered (mapv (fn [r] (str/join " — " (map str (vals r)))) out)}
      :cards {:kind :cards :rendered out}
      {:kind :table :rendered out})))
```

- [ ] **Step 6: Run and watch them pass**

REPL:

```clojure
(require '[loci.viewspec] '[loci.tablestore] :reload)
(require '[loci.tablestore-test] :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 7: Sabotage — prove `:total` is not the page size**

In `InlineTable/query`, change `:total (count out)` to `:total (count (if-let [l (:limit spec)] (take l out) out))`. Run:

```clojure
(require '[loci.tablestore] :reload)
(require '[loci.tablestore-test] :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

Expected: `inline-query-total-survives-a-limit` **fails**. If it does not, the assertion is worthless — rewrite it before continuing.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 8: Commit**

```bash
git add src/loci/tablestore.clj src/loci/viewspec.clj test/loci/tablestore_test.clj
git commit -m "feat: TableStore can be asked a question, and InlineTable answers it first

query and create-from-spec join the protocol. The 2026-08-16 big-content spec
designed query and it was never built; its absence is why five callers read a
mold-cap sample and treated it as the table.

:total is the answer's size before :limit and is not optional — it is what lets
a caller say \\\"200 of 900,000\\\" instead of implying 200 is all of it. A capped
result is not an error.

viewspec/interpret splits: question does filter/group/sort, interpret shapes what
comes back. InlineTable folds through question, so the two stores share one
implementation of the language rather than two that drift."
```

---

### Task 3: `Lake` answers `query` with SQL

**Files:**
- Modify: `src/loci/lake.clj` (add `relation-of`, extend the `Lake` type)
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing tests**

Append to `test/loci/lake_test.clj`:

```clojure
(deftest lake-query-groups-over-every-row
  ;; 2,500 rows is load-bearing: it EXCEEDS tbl/mold-cap (2000). A fixture below
  ;; the cap passes whether or not the bug is present, which is the whole defect
  ;; this task removes.
  (with-lake [lk]
    (let [rows (vec (for [n (range 2500)] {:region (str "r" (mod n 5)) :v 1.0}))
          h    (tbl/put-rows! lk "tbl:big" rows)
          r    (tbl/query lk h {:group_by "region" :measure "v" :agg "sum"})]
      (is (= 5 (:total r)))
      (is (= 2500.0 (reduce + (map :v (:rows r))))
          "the sum must cover 2,500 rows — 2,000 would mean the sample came back"))))

(deftest lake-query-total-is-the-answer-not-the-page
  (with-lake [lk]
    (let [rows (vec (for [n (range 2500)] {:n n}))
          h    (tbl/put-rows! lk "tbl:big" rows)
          r    (tbl/query lk h {:limit 10})]
      (is (= 10 (count (:rows r))))
      (is (= 2500 (:total r)))
      (is (true? (:capped r))))))

(deftest lake-query-pins-the-handles-snapshot
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:v" (vec (for [n (range 2500)] {:n n})))]
      (lake/x! lk (str "DELETE FROM " (:table h) " WHERE n < 1000"))
      (is (= 2500 (:total (tbl/query lk h {})))
          "the handle names a snapshot; a later write must not change its answer"))))

(deftest lake-and-inline-agree
  (with-lake [lk]
    (let [rows (vec (for [n (range 2500)]
                      {:region (str "r" (mod n 5)) :v (double (mod n 7))}))
          hl   (tbl/put-rows! lk "tbl:p" rows)
          ii   (tbl/inline-table)
          hi   (tbl/put-rows! ii "tbl:p" rows)]
      (doseq [spec [{}
                    {:group_by "region" :measure "v" :agg "sum"}
                    {:group_by "region" :measure "v" :agg "count"}
                    {:filter {:col "v" :op ">" :val 3}}
                    {:filter {:col "region" :op "≠" :val "r0"}}]]
        (is (= (:total (tbl/query ii hi spec)) (:total (tbl/query lk hl spec)))
            (str "stores disagree on :total for " (pr-str spec)))))))

(deftest lake-create-from-spec-never-materialises-rows
  (with-lake [lk]
    (let [h  (tbl/put-rows! lk "tbl:big" (vec (for [n (range 2500)] {:n n :v 1.0})))
          h2 (tbl/create-from-spec lk h {:filter {:col "n" :op ">" :val 499}}
                                   "tbl:derived-1" "Filtered")]
      (is (tbl/handle? h2))
      (is (= :lake (:store h2)))
      (is (= 2000 (:rows h2)) "the derived table must hold every matching row"))))
```

- [ ] **Step 2: Run and watch them fail**

REPL:

```clojure
(require '[loci.lake-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: errors — `No implementation of method: :query`.

- [ ] **Step 3: Implement on `Lake`**

In `src/loci/lake.clj`, add above `(extend-type Lake …)`:

```clojure
(defn relation-of
  "A handle as a HoneySQL FROM entry that pins its snapshot.

   **A subquery map, not a bare `[:raw …]`, and the reason is measured.** DuckDB
   refuses an alias directly on a versioned relation —
   `FROM t AT (VERSION => 1) AS x` is `Parser Error: syntax error at or near
   \"AS\"` — so the relation has to be wrapped to be aliasable at all, which is
   also what lets a query join two tables. Verified 2026-08-27: the quoted,
   bare and subquery-wrapped forms all read; only the aliased bare form does not.

   `ident` quotes the table name. Names are minted by `table-name`, so this is
   belt and braces rather than a fix."
  [h]
  {:select [:*]
   :from   [[[:raw (str (ident (:table h))
                        " AT (VERSION => " (long (:snapshot h)) ")")]]]})

(defn- run-honey
  "Format `hq` with values inlined and run it. One string, so `q` is unchanged.

   Inlined rather than parameterised because loci is single-user and personal —
   decided 2026-08-27. HoneySQL emits parameters separately if that ever changes,
   so the work would be a JDBC path and not a rewrite."
  [lk hq ks]
  (let [sql (first (sql/format hq {:inline true}))]
    (if ks (q-as lk sql ks) (q lk sql))))
```

Add to `loci.lake`'s `:require`: `[honey.sql :as sql]` and `[loci.honeyq :as hq]`.

Then inside `(extend-type Lake …)`:

```clojure
  (query [this handle spec]
    (let [rel  (relation-of handle)
          base (hq/->honey (dissoc spec :limit) rel)
          ;; Count FIRST, always. 6 ms against the query's 20 ms over 1,000,000
          ;; rows — DuckDB answers a grouped count from the cardinality. It is
          ;; also the parse check: HoneySQL emits wrong SQL rather than raising,
          ;; and this is where DuckDB says so before any result is trusted.
          total (long (:n (first (run-honey this (hq/->count base) nil))))
          lim   (or (:limit spec) max-query-rows)
          rows  (run-honey this (assoc base :limit lim) nil)]
      {:rows (vec rows) :total total :capped (> total (count rows))}))
  (create-from-spec [this handle spec id _title]
    (create-from-sql! this id
                      (first (sql/format (hq/->honey (dissoc spec :limit) (relation-of handle))
                                         {:inline true}))))
```

And the helper both `create-from-spec` and the sandbox's `keep!` use, beside
`create-from-relation!`:

```clojure
(defn create-from-sql!
  "Materialise any SQL SELECT as a new lake table and return the handle.

   `create-from-relation!`'s sibling for a QUERY rather than a file, and the
   difference is `:cols`. `create-from-relation!` is given `describe`'s columns
   because it is reading a file whose header it just read. A query's columns are
   **not** the source's — `SELECT region, SUM(v) AS v` has two, whatever the
   source had — so they are read back off the result with `DESCRIBE`.

   Getting this wrong writes a handle whose `:cols` name columns the table does
   not have, and every later read of it fails with `Binder Error: Referenced
   column not found` — the same failure `loci.source`'s docstring records for a
   passthrough handle carrying half a recipe."
  [lk id sql]
  (let [table (table-name id (System/nanoTime))]
    (x! lk (str "CREATE TABLE " table " AS " sql))
    {:store :lake :table table :snapshot (current-snapshot lk)
     :rows  (:n (first (q lk (str "SELECT count(*) AS n FROM " table))))
     :cols  (mapv #(sub/col-kw (:column_name %))
                  (q lk (str "DESCRIBE SELECT * FROM " table)))}))
```

And add near `mold-cap`'s definition in `loci.tablestore`:

```clojure
(def max-query-rows
  "How many rows a `query` returns when the spec sets no `:limit`.

   **A latency budget, not a memory one.** Measured 2026-08-27: crossing JDBC into
   Clojure maps costs about 5 µs a row plus 3.7 µs a cell, so 1,000,000 rows of
   four columns is 27 s and 220 MB. The heap is fine on any modern box; 27 s is
   not. 10,000 rows of that shape is 0.8 s.

   A result above this is not an error. It comes back capped, with the true
   `:total`, and `create-from-spec` is how a caller keeps all of it."
  10000)
```

- [ ] **Step 4: Run and watch them pass**

REPL:

```clojure
(require '[loci.tablestore] '[loci.lake] :reload)
(require '[loci.lake-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Sabotage — prove the 2,500-row fixture bites**

In `Lake/query`, change `rel` to read the sample instead of the table:

```clojure
          rel  (assoc (relation-of handle) :limit 2000)
```

Run:

```clojure
(require '[loci.lake] :reload)
(require '[loci.lake-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: `lake-query-groups-over-every-row` fails with `2000.0` where `2500.0` was expected. **This is the defect the whole plan exists to remove — if this test stays green, stop and fix the test.**

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 6: Sabotage — prove snapshot pinning bites**

In `relation-of`, drop the version clause:

```clojure
   :from   [[[:raw (ident (:table h))]]]
```

Run the suite. Expected: `lake-query-pins-the-handles-snapshot` fails with `1500` where `2500` was expected.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 7: Full suites, then commit**

```bash
clojure -M:test
npm run test:browser
```

Expected: both green. Then:

```bash
git add src/loci/lake.clj src/loci/tablestore.clj test/loci/lake_test.clj
git commit -m "feat: the lake answers a question in SQL, and counts it before it trusts it

Lake/query builds HoneySQL from a view-spec, counts the answer, then reads a
bounded page of it. relation-of wraps the versioned table in a subquery because
DuckDB refuses an alias on AT (VERSION => n) — verified 2026-08-27 — and the
wrap is also what makes a join possible.

The count runs first and always. 6 ms against the query's 20 ms over 1,000,000
rows, because DuckDB answers a grouped count from the cardinality. It doubles as
the parse check: HoneySQL emits wrong SQL rather than raising.

max-query-rows is 10,000 and it is a latency budget, not a memory one. Crossing
JDBC costs ~5 us a row plus 3.7 us a cell, so 1M rows of four columns is 27 s and
220 MB — the heap is fine and the clock is not.

The regression fixtures hold 2,500 rows, above mold-cap. Below it every one of
them passes with the bug present."
```

---

### Task 4: `loci.viewspec` shape half runs over the answer

**Files:**
- Modify: `src/loci/viewspec.clj` (already split in Task 2 — this verifies it)
- Test: `test/loci/viewspec_test.clj` (create if absent)

- [ ] **Step 1: Check whether a test namespace exists**

```bash
ls test/loci/viewspec_test.clj 2>/dev/null || echo "absent — create it"
```

- [ ] **Step 2: Write the failing test**

Create or append to `test/loci/viewspec_test.clj`:

```clojure
(ns loci.viewspec-test
  (:require [clojure.test :refer [deftest is]]
            [loci.viewspec :as vs]))

(def rows (vec (for [n (range 30)] {:region (str "r" (mod n 3)) :v (double n)})))

(deftest question-does-not-limit
  (is (= 30 (count (vs/question {:limit 5} rows)))
      "question returns the whole answer; the caller limits, because it needs the count"))

(deftest interpret-still-limits
  (is (= 5 (count (:rendered (vs/interpret {:kind "table" :limit 5} rows))))))

(deftest interpret-and-question-agree-on-the-question
  (let [spec {:group_by "region" :measure "v" :agg "sum"}]
    (is (= (vs/question spec rows)
           (:rendered (vs/interpret spec rows)))
        "one implementation of the language, not two")))

(deftest the-four-new-language-terms-work
  (is (= 20 (count (vs/question {:filter {:col "region" :op "≠" :val "r0"}} rows))))
  (is (= 30 (count (vs/question {:filter {:col "region" :op "contains" :val "R"}} rows))))
  (is (= 0.0 (:v (first (vs/question {:group_by "region" :measure "v" :agg "min"} rows)))))
  (is (= 27.0 (:v (first (vs/question {:group_by "region" :measure "v" :agg "max"} rows))))))
```

- [ ] **Step 3: Run and watch it fail or pass**

REPL:

```clojure
(require '[loci.viewspec] :reload)
(require '[loci.viewspec-test] :reload)
(clojure.test/run-tests 'loci.viewspec-test)
```

If Task 2's split was done correctly these pass. If `interpret-and-question-agree-on-the-question` fails, `interpret` is not calling `question` and the language now has two implementations — fix that before continuing.

- [ ] **Step 4: Sabotage — prove the agreement test bites**

In `interpret`, replace `(question spec rows)` with an inlined copy that ignores the filter. Run the suite. Expected: `interpret-and-question-agree-on-the-question` fails.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 5: Commit**

```bash
git add src/loci/viewspec.clj test/loci/viewspec_test.clj
git commit -m "test: one implementation of the spec language, pinned by a test that notices two"
```

---

## Phase 2 — the four callers that had no way to ask

### Task 5: `query_table` returns an exact figure

**Files:**
- Modify: `src/loci/tools.clj:147-166` (`do-query`)
- Test: `test/loci/tools_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/tools_test.clj`:

```clojure
(deftest query-table-is-exact-above-mold-cap
  ;; 2,500 rows, above tbl/mold-cap. Below it this passes with the bug present.
  (lf/with-temp-lake
    (fn []
      (let [st   (sub/fresh-store)
            rows (vec (for [n (range 2500)] {:region (str "r" (mod n 5)) :v 1.0}))
            h    (tbl/put-rows! @lake/lake "tbl:big" rows)]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (let [r (tools/dispatch st "query_table"
                                {:table_id "tbl:big" :group_by "region"
                                 :measure "v" :agg "sum"})]
          (is (= 2500.0 (reduce + (map :v (:rows r))))
              "2000.0 here means the mold-cap sample came back")
          (is (= 5 (:total r)) "the model must be told the answer's true size"))))))

(deftest query-table-says-when-it-capped
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (let [r (tools/dispatch st "query_table" {:table_id "tbl:big" :limit 10})]
          (is (= 10 (count (:rows r))))
          (is (= 2500 (:total r)))
          (is (true? (:capped r))))))))

(deftest query-table-save-as-keeps-every-row
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (let [r   (tools/dispatch st "query_table"
                                  {:table_id "tbl:big"
                                   :filter {:col "n" :op ">" :val 499}
                                   :save_as "Filtered"})
              obj (sub/object st (:saved_as r))]
          (is (= 2000 (:rows (:value obj)))
              "a derived table built from a 2,000-row sample would hold 1,500"))))))
```

- [ ] **Step 2: Run and watch them fail**

REPL:

```clojure
(require '[loci.tools-test] :reload)
(clojure.test/run-tests 'loci.tools-test)
```

Expected: `query-table-is-exact-above-mold-cap` fails with `2000.0`.

- [ ] **Step 3: Rewrite `do-query`**

Replace the body of `do-query` in `src/loci/tools.clj` with:

```clojure
(defn- do-query [st {:keys [table_id save_as] :as args}]
  (if-let [o (sub/object st table_id)]
    (let [h (:value o)]
      (cond
        (not (tbl/handle? h))
        ;; A legacy inline table: rows live in the event. `inline-table` answers
        ;; the same protocol, so the language is identical either way.
        (let [spec (select-keys args [:group_by :measure :agg :filter :sort :limit])
              r (tbl/query (tbl/inline-table) (tbl/put-rows! (tbl/inline-table) table_id h) spec)]
          (select-keys r [:rows :total :capped]))

        :else
        (if-let [why (tbl/unusable (tbl/hydrate lake/lake o))]
          {:error (str "table " table_id " cannot be read: " why)}
          (let [spec (select-keys args [:group_by :measure :agg :filter :sort :limit])]
            (if save_as
              (let [nid (next-id-for st "tbl:derived-")
                    h2  (tbl/create-from-spec (force lake/lake) h spec nid save_as)
                    obj {:id nid :kind :table :title save_as :value h2
                         :from table_id :via "query_table"}]
                (sub/commit! st {:op :put :id nid :value obj})
                {:saved_as nid :rows (:rows h2)})
              (let [r (tbl/query (force lake/lake) h spec)]
                {:rows (:rows r) :total (:total r) :capped (:capped r)}))))))
    {:error (str "no table " table_id)}))
```

Add a private helper above it, replacing the `(str "tbl:derived-" (count …) "-" (rand-int …))` id minting, which `compute-clj!`'s comment already calls out as the shape that destroyed three notebooks:

```clojure
(defn- next-id-for
  "max+1 over the objects, never count+1. A count re-mints a live id the moment
   anything is deleted — see `server/fresh-dd-id`, which exists because a count
   silently overwrote three notebooks on 2026-07-23."
  [st prefix]
  (let [n (->> (sub/objects st) keys
               (keep #(some->> % (re-find (re-pattern (str "^" prefix "(\\d+)$"))) second parse-long))
               (reduce max 0))]
    (loop [k (inc n)]
      (if (sub/object st (str prefix k)) (recur (inc k)) (str prefix k)))))
```

- [ ] **Step 4: Update the tool description so the model is told the truth**

In `src/loci/tools.clj`, in the `query_table` spec, change `:description` to:

```clojure
               :description (str "Filter / group / aggregate a table and return the resulting "
                                 "rows. Runs in DuckDB over every row — use this for any exact "
                                 "figure, never guess. The reply carries `total`, the answer's "
                                 "true size; when `capped` is true you were given a page of it, "
                                 "so aggregate further or set save_as to keep all of it.")
```

- [ ] **Step 5: Run and watch them pass**

REPL:

```clojure
(require '[loci.tools] :reload)
(require '[loci.tools-test] :reload)
(clojure.test/run-tests 'loci.tools-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Sabotage — prove the exactness test bites**

In `do-query`, replace `(tbl/query (force lake/lake) h spec)` with the old shape:

```clojure
              (let [rows (:value (tbl/hydrate lake/lake o))
                    r {:rows (vec (take 200 (vs/question spec rows))) :total 0 :capped false}]
                r)
```

Run the suite. Expected: `query-table-is-exact-above-mold-cap` fails with `2000.0`, and `query-table-says-when-it-capped` fails on `:total`.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tools.clj test/loci/tools_test.clj
git commit -m "fix: query_table sums every row, and says how many the answer really has

do-query hydrated a 2,000-row sample and folded it in Clojure, then reported the
result as exact — for a tool whose own description reads \\\"use this for any exact
figure, never guess\\\". Over a 1M-row table it summed 2,000 rows. The number then
reached a report, distill! made it a fact, and chunks embedded it.

It now goes through TableStore/query, so the arithmetic happens in DuckDB, and the
reply carries total and capped so a page can never read as the whole answer.
save_as goes through create-from-spec: CREATE TABLE AS, no row in the JVM.

Also replaces count+1 id minting with max+1. A count re-mints a live id the moment
anything is deleted — the shape that overwrote three notebooks on 2026-07-23."
```

---

### Task 6: the ƒ palette opens without reading a row

**Files:**
- Modify: `src/loci/fnlib.clj:110-124` (`catalog`, `numeric-cols`, `cat-cols`)
- Modify: `src/loci/tablestore.clj` (add `column-profile` to the protocol)
- Modify: `src/loci/server.clj` (`fns-payload`)
- Test: `test/loci/fnlib_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/fnlib_test.clj`:

```clojure
(ns-unmap *ns* 'profile)

(def profile
  "What `catalog` needs, and all it needs: names, which are numeric, which are
   categorical, and how many rows there are."
  {:cols [:region :revenue :name] :numeric [:revenue] :categorical [:region] :rows 2500})

(deftest catalog-takes-a-profile-not-rows
  (let [c (fnlib/catalog profile)]
    (is (= 6 (count c)))
    (is (every? :id c))
    (is (= ["region" "revenue" "name"]
           (:options (first (filter #(= "col" (:type %))
                                    (:params (first (filter #(= "lib:filter" (:id %)) c))))))))))

(deftest a-verb-is-greyed-from-the-profile
  (let [c (fnlib/catalog (assoc profile :numeric []))
        g (first (filter #(= "lib:group" (:id %)) c))]
    (is (false? (:ok g)))
    (is (= "needs a numeric column" (:why g)))))

(deftest delta-needs-two-rows-and-knows-it-from-the-count
  (is (false? (:ok (first (filter #(= "lib:delta" (:id %))
                                  (fnlib/catalog (assoc profile :rows 1)))))))
  (is (true?  (:ok (first (filter #(= "lib:delta" (:id %))
                                  (fnlib/catalog profile)))))))
```

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.fnlib-test] :reload)
(clojure.test/run-tests 'loci.fnlib-test)
```

Expected: failure — `catalog` currently takes rows and calls `(keys (first rows))` on a map.

- [ ] **Step 3: Add `column-profile` to the protocol**

In `src/loci/tablestore.clj`'s `defprotocol`, after `column-samples`:

```clojure
  (column-profile [this handle]
    "`{:cols [...] :numeric [...] :categorical [...] :rows n}` — everything the ƒ
     palette needs to decide which verbs to offer, **fetching no rows**.

     `numeric` is judged from the store's own types, not from the first row.
     `categorical` is a column with 2–12 distinct values, which is an exact
     question for the lake (`count(DISTINCT c)`) and a scan for `InlineTable`.

     Before 2026-08-27 the palette hydrated a 2,000-row sample to answer this, so
     `lib:pivot` was offered or greyed on a guess about the other 998,000 rows.")
```

`InlineTable`:

```clojure
  (column-profile [this handle]
    (let [rs (vec (rows this handle {}))
          ks (vec (keys (first rs)))]
      {:cols ks
       :numeric (vec (for [[k v] (first rs) :when (number? v)] k))
       :categorical (vec (for [k ks
                               :let [vs (map k rs)]
                               :when (and (every? string? vs)
                                          (<= 2 (count (distinct vs)) 12))]
                           k))
       :rows (count rs)}))
```

`Lake` (in `loci.lake`, inside `extend-type Lake`):

```clojure
  (column-profile [this handle]
    (let [rel  (str "(SELECT * FROM " (ident (:table handle))
                    " AT (VERSION => " (long (:snapshot handle)) "))")
          cols (:cols handle)
          types (into {} (for [r (q this (str "DESCRIBE SELECT * FROM " rel))]
                           [(sub/col-kw (:column_name r)) (:column_type r)]))
          numeric? #(re-find #"(?i)INT|DECIMAL|DOUBLE|FLOAT|REAL|HUGEINT|NUMERIC"
                             (str (get types %)))
          ;; One query for every column's distinct count, not one query per
          ;; column: `list_tables` measured 100 ms per-table against 3.5 ms for
          ;; one round trip over the same store. The cost is the round trip.
          dsql (str "SELECT "
                    (str/join ", " (for [c cols]
                                     (str "count(DISTINCT " (ident c) ") AS " (ident c))))
                    " FROM " rel)
          dist (first (q this dsql))]
      {:cols cols
       :numeric (vec (filter numeric? cols))
       :categorical (vec (for [c cols
                               :when (and (not (numeric? c))
                                          (<= 2 (long (or (get dist c) 0)) 12))]
                           c))
       :rows (long (row-count this handle))}))
```

- [ ] **Step 4: Rewrite `fnlib/catalog` and the predicates**

In `src/loci/fnlib.clj`, replace `numeric-cols`, `cat-cols` and `catalog`:

```clojure
(defn- needs-numeric [{:keys [numeric]}]
  (when (empty? numeric) "needs a numeric column"))

(defn catalog
  "The palette for one table, from its COLUMN PROFILE rather than from its rows.

   `profile` is `TableStore/column-profile`'s output:
   `{:cols [...] :numeric [...] :categorical [...] :rows n}`.

   Rows were never the right input. Every question here is about columns and a
   count, and answering them from a 2,000-row sample meant `lib:pivot` was
   offered or greyed on a guess about a table of any size."
  [profile]
  (let [opts {"col"    (mapv name (:cols profile))
              "numcol" (mapv name (:numeric profile))
              "catcol" (mapv name (:categorical profile))}]
    (mapv (fn [{:keys [id label doc params pred]}]
            (let [why (when pred (pred profile))]
              {:id id :label label :doc doc :ok (nil? why) :why why
               :params (mapv #(if-let [o (opts (:type %))] (assoc % :options o) %) params)}))
          builtins)))
```

And in `builtins`, change the three `:pred`s that took rows:

```clojure
    :pred (fn [p] (or (needs-numeric p)
                      (when (< (count (:categorical p)) 2) "needs two category columns")))
```

```clojure
    :pred (fn [p] (or (needs-numeric p)
                      (when (< (long (or (:rows p) 0)) 2) "needs at least two rows")))
```

- [ ] **Step 5: Rewire `fns-payload`**

In `src/loci/server.clj`, replace `fns-payload`:

```clojure
(defn fns-payload [st id]
  (let [o (sub/object st id)
        h (:value o)]
    (if-not (or (tbl/handle? h) (and (sequential? h) (seq h) (every? map? h)))
      {:error "functions work on tables"}
      (let [ts      (if (tbl/handle? h) (force lake/lake) (tbl/inline-table))
            h       (if (tbl/handle? h) h (tbl/put-rows! ts id h))
            profile (tbl/column-profile ts h)]
        {:fns (into (fnlib/catalog profile)
                    (->> (sub/objects st) vals
                         (filter #(and (= :fn (:kind %))
                                       (= "clojure" (get-in % [:value :lang]))))
                         (sort-by :id)
                         (mapv (fn [o] {:id (:id o) :label (:title o)
                                        :doc (get-in o [:value :prompt]) :ok true :params []}))))}))))
```

- [ ] **Step 6: Run and watch them pass**

REPL:

```clojure
(require '[loci.tablestore] '[loci.lake] '[loci.fnlib] '[loci.server] :reload)
(require '[loci.fnlib-test] :reload)
(clojure.test/run-tests 'loci.fnlib-test)
```

Expected: `0 failures, 0 errors.` Existing `fnlib` tests that pass rows to `catalog` will fail — update them to pass a profile.

- [ ] **Step 7: Sabotage — prove the greying test bites**

In `catalog`, change `(when pred (pred profile))` to `nil`. Run the suite. Expected: `a-verb-is-greyed-from-the-profile` and `delta-needs-two-rows-and-knows-it-from-the-count` both fail.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 8: Commit**

```bash
git add src/loci/fnlib.clj src/loci/tablestore.clj src/loci/lake.clj src/loci/server.clj test/loci/fnlib_test.clj
git commit -m "fix: the palette opens on a column profile, and reads no rows to do it

catalog asked three questions — which columns exist, which are numeric, which have
2-12 distinct values — and answered all three from a 2,000-row sample, so lib:pivot
was offered or greyed on a guess about the rest of the table.

None of the three is a row question. column-profile joins the protocol: the lake
reads types from DESCRIBE and distinct counts in one query, because the cost is the
round trip and not the scan — measured 100 ms per-table against 3.5 ms for one, on
list_tables. InlineTable scans, which is what it has always done.

lib:delta's predicate now asks row-count instead of counting a sample."
```

---

### Task 7: the `lib:` verbs become specs, and apply pushes down

**Files:**
- Modify: `src/loci/fnlib.clj` (add `:spec` to three builtins)
- Modify: `src/loci/server.clj` (`fn-preview`, `fn-apply!`, delete `table-rows`)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/server_test.clj`:

```clojure
(deftest fn-apply-filters-every-row
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big"
                              (vec (for [n (range 2500)] {:n n :v 1.0})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (let [r   (server/fn-apply! st "tbl:big" "lib:filter"
                                    {"col" "n" "op" ">" "value" "499"} nil)
              obj (sub/object st (:openId r))]
          (is (= 2000 (:rows (:value obj)))
              "1,500 here means the derived table was built from a 2,000-row sample"))))))

(deftest fn-preview-reports-the-true-count
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big"
                              (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (let [r (server/fn-preview st "tbl:big" "lib:filter"
                                   {"col" "n" "op" ">" "value" "499"})]
          (is (= 8 (count (:after r))) "a preview is still eight rows")
          (is (= 2000 (:count r)) "but the count is the whole answer"))))))
```

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: `fn-apply-filters-every-row` fails with `1500`.

- [ ] **Step 3: Give three builtins a `:spec`**

In `src/loci/fnlib.clj`, add a `:spec` key to `lib:filter`, `lib:group` and `lib:top`. `:spec` is `params → view-spec`, and its presence is what tells a caller the verb can be pushed down:

```clojure
   {:id "lib:filter" :label "Filter rows" :doc "keep rows where a column passes a test"
    :params [{:name "col" :type "col"}
             {:name "op" :type "choice" :options ["=" "≠" ">" "<" "contains"]}
             {:name "value" :type "text"}]
    :spec (fn [{:strs [col op value]}] {:filter {:col col :op op :val value}})
    :run filter-run}
   {:id "lib:group" :label "Group & aggregate" :doc "one row per group — sum/avg/min/max/count a column"
    :params [{:name "by" :type "col"} {:name "measure" :type "numcol"}
             {:name "agg" :type "choice" :options ["sum" "avg" "min" "max" "count"]}]
    :pred needs-numeric
    :spec (fn [{:strs [by measure agg]}] {:group_by by :measure measure :agg (or agg "sum")})
    :run group-run}
   {:id "lib:top" :label "Top N" :doc "sort by a numeric column and keep the first N"
    :params [{:name "by" :type "numcol"} {:name "n" :type "num"}
             {:name "order" :type "choice" :options ["desc" "asc"]}]
    :pred needs-numeric
    :spec (fn [{:strs [by n order]}]
            {:sort (if (= "asc" order) "asc" "desc")
             :measure by
             :limit (int (or (->num n) 10))})
    :run top-run}
```

`lib:pivot`, `lib:delta` and `lib:share` get **no** `:spec` and stay in Clojure. That is deliberate and recorded in the spec: DuckDB can express all three, but each needs new grammar in the view-spec language, and the language should grow when something hurts.

Add a lookup:

```clojure
(defn spec-of
  "The view-spec a builtin's params imply, or nil when the verb cannot be pushed
   down. Nil is the signal to fall back to `:run` over rows."
  [fid params]
  (when-let [f (:spec (first (filter #(= fid (:id %)) builtins)))]
    (f params)))
```

- [ ] **Step 4: Rewrite `fn-preview` and `fn-apply!`, delete `table-rows`**

In `src/loci/server.clj`, delete `table-rows` entirely and replace the two callers:

```clojure
(defn- store+handle
  "The store and handle for an object, whatever it is backed by. An inline table
   is landed in an `InlineTable` so one code path serves both."
  [o]
  (let [v (:value o)]
    (cond
      (tbl/handle? v) [(force lake/lake) v]
      (and (sequential? v) (seq v) (every? map? v))
      (let [ts (tbl/inline-table)] [ts (tbl/put-rows! ts (:id o) v)])
      :else nil)))

(defn fn-preview [st id fid params]
  (try
    (if-let [[ts h] (store+handle (sub/object st id))]
      (if-let [spec (fnlib/spec-of fid params)]
        ;; Pushed down: eight rows out of DuckDB, and the count of the whole answer.
        (let [before (:rows (tbl/query ts h {:limit 8}))
              r      (tbl/query ts h (assoc spec :limit 8))]
          {:before (json-safe (vec before))
           :after  (json-safe (vec (:rows r)))
           :count  (:total r)})
        ;; Not pushed down — pivot, delta, share, or an agent-written fn. Runs
        ;; over a bounded page and SAYS SO, so a preview is never read as the
        ;; answer. Apply runs over everything.
        (let [total (tbl/row-count ts h)
              rows  (:rows (tbl/query ts h {:limit tbl/mold-cap}))
              r     (run-any-fn st fid rows params)]
          (if (:error r)
            r
            {:before (json-safe (vec (take 8 rows)))
             :after (vec (take 8 (:rows r)))
             :count (count (:rows r))
             :sampled (when (> total (count rows))
                        {:over (count rows) :of total})})))
      {:error "functions work on tables"})
    (catch Exception e {:error (.getMessage e)})))

(defn fn-apply! [st id fid params space]
  (try
    (if-let [[ts h] (store+handle (sub/object st id))]
      (if (and space (not (space? st space)))
        {:error (str "not a notebook: " space)}
        (let [src  (sub/object st id)
              lbl  (or (:label (first (filter #(= fid (:id %)) fnlib/builtins)))
                       (:title (sub/object st fid)) fid)
              nid  (next-id st "tbl:derived-")
              ttl  (str (:title src) " · " lbl)
              spec (fnlib/spec-of fid params)
              tobj (if spec
                     ;; CREATE TABLE AS — 81 ms and 0 MB for 927,830 rows,
                     ;; measured 2026-08-27, against ~25 s and 214 MB to pull them.
                     {:id nid :kind :table :title ttl
                      :value (tbl/create-from-spec ts h spec nid ttl)
                      :from id :via fid :params (or params {})}
                     ;; Not pushed down: every row through Clojure, which is the
                     ;; 2026-08-27 decision. q-all's cost note applies.
                     (let [rows (:rows (tbl/query ts h {:limit Long/MAX_VALUE}))
                           r    (run-any-fn st fid rows params)]
                       (when (:error r) (throw (ex-info (:error r) {})))
                       (tbl/table-object @lake/lake nid ttl (:rows r)
                                         {:from id :via fid :params (or params {})})))
              evs  (cond-> [{:op :put :id nid :value tobj}]
                     (and space (space? st space))
                     (conj (nb/append-cell-event st space {:ref nid})))]
          (sub/commit! st {:op :tx :events evs})
          {:state (state-payload st) :openId nid}))
      {:error "functions work on tables"})
    (catch Exception e {:error (.getMessage e)})))
```

> **`{:limit Long/MAX_VALUE}` is not a cap** — `Lake/query` applies `(or (:limit spec) max-query-rows)`, so passing an explicit huge limit is how a caller says "all of it". Verify this reads correctly; if `max-query-rows` is applied unconditionally, change `Lake/query` so an explicit `:limit` always wins.

- [ ] **Step 5: Run and watch them pass**

REPL:

```clojure
(require '[loci.fnlib] '[loci.server] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Sabotage — prove `fn-apply-filters-every-row` bites**

In `fn-apply!`, replace `(tbl/create-from-spec ts h spec nid ttl)` with the old shape:

```clojure
                     (let [rows (:value (tbl/hydrate lake/lake src))
                           r (fnlib/run-fn fid rows params)]
                       (tbl/table-object @lake/lake nid ttl (:rows r) {:from id :via fid}))
```

Run the suite. Expected: `fn-apply-filters-every-row` fails with `1500`.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 7: Full suites, then commit**

```bash
clojure -M:test
npm run test:browser
```

Both green, then:

```bash
git add src/loci/fnlib.clj src/loci/server.clj test/loci/server_test.clj
git commit -m "fix: the palette applies to every row, and server/table-rows is gone

table-rows was the shared helper behind fns-payload, fn-preview, fn-apply! and
compute-clj!. It called hydrate, which asks for mold-cap rows, so a sort, a filter
or a group over a 1M-row table ran over the first 2,000 and fn-apply! wrote the
result as a table with nothing on it to say so.

lib:filter, lib:group and lib:top now carry a :spec — params to a view-spec — so
apply is CREATE TABLE AS and no row enters the JVM. 81 ms and 0 MB for 927,830
rows, measured, against ~25,000 ms and 214 MB to pull them.

lib:pivot, lib:delta and lib:share keep :run and no :spec. DuckDB can express all
three, but each needs new grammar in the language, and the language grows when
something hurts. Preview for those now carries :sampled, so it can never read as
the answer.

lib:delta stays undefined over a lake table: it reads the previous row, and a lake
table has no order without an ORDER BY. Unchanged, and written down so nobody
reports it as new."
```

---

## Phase 3 — the sandbox is handed a database

### Task 8: `loci.sandbox` — the four verbs

**Files:**
- Create: `src/loci/sandbox.clj`
- Create: `test/loci/sandbox_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/loci/sandbox_test.clj`:

```clojure
(ns loci.sandbox-test
  (:require [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.sandbox :as sbx]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- fixture [f]
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big"
                              (vec (for [n (range 2500)]
                                     {:region (str "r" (mod n 5)) :v 1.0 :n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (f st)))))

(deftest q-groups-over-every-row
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           q   (get (:bindings ctx) 'q)
           tbl (get (:bindings ctx) 'table)
           r   (q {:select [:region [[:sum :v] :v]] :from [[tbl :t]] :group-by [:region]})]
       (is (= 5 (count r)))
       (is (= 2500.0 (reduce + (map :v r)))
           "2000.0 means a sample came back")))))

(deftest q-carries-the-total-in-metadata
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           q   (get (:bindings ctx) 'q)
           tbl (get (:bindings ctx) 'table)
           r   (q {:select [:*] :from [[tbl :t]] :limit 10})]
       (is (= 10 (count r)))
       (is (= 2500 (:total (meta r))))
       (is (true? (:capped (meta r))))))))

(deftest q-reduce-folds-without-holding
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           qr  (get (:bindings ctx) 'q-reduce)
           tbl (get (:bindings ctx) 'table)]
       (is (= 2500 (qr {:select [:*] :from [[tbl :t]]}
                       (fn [acc batch] (+ acc (count batch))) 0)))))))

(deftest keep-with-a-spec-accumulates-and-does-not-commit
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           k   (get (:bindings ctx) 'keep!)
           tbl (get (:bindings ctx) 'table)
           id  (k "Filtered" {:select [:*] :from [[tbl :t]] :where [:> :n 499]})]
       (is (string? id))
       (is (nil? (sub/object st id)) "keep! must accumulate; the run commits once at the end")
       (let [evs (sbx/events ctx)]
         (is (= 1 (count evs)))
         (is (= 2000 (:rows (:value (:value (first evs)))))))))))

(deftest reads-records-what-was-actually-queried
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           q   (get (:bindings ctx) 'q)
           tbl (get (:bindings ctx) 'table)]
       (is (empty? (sbx/reads ctx)))
       (q {:select [:*] :from [[tbl :t]] :limit 1})
       (is (= #{"tbl:big"} (sbx/reads ctx)))))))

(deftest q-all-states-its-cost-before-it-runs
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil)
           est (sbx/estimate ctx "tbl:big")]
       (is (= 2500 (:rows est)))
       (is (= 3 (:cols est)))
       (is (number? (:seconds est)))))))
```

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.sandbox-test] :reload)
```

Expected: `FileNotFoundException — could not locate loci/sandbox.clj`.

- [ ] **Step 3: Write `src/loci/sandbox.clj`**

```clojure
(ns loci.sandbox
  "What the `sci` sandbox is handed. Before 2026-08-27 it was one binding —
   `rows`, a `mold-cap` sample — and model-written Clojure folded it and reported
   the result as the table's.

   **`rows` no longer exists.** That is the enforcement, and it is the same trick
   the report rule uses: the model cannot fold a sample because there is no
   sample, so the easy path is the query.

   Five things are bound. `table` and `tables` are HoneySQL relations that pin
   their handles' snapshots; `q`, `q-reduce`, `q-all` and `keep!` are the verbs.
   They line up one-to-one with `TableStore`, which is the sign that this is the
   protocol exposed rather than a new idea.

   **Snapshot pinning.** Every marker resolves ONCE, when the context is built, so
   every query in one run reads the same instant and a report cannot contradict
   itself between two of its own paragraphs. Every other read in loci names the
   snapshot it wants; this must not be where that lapses.

   **`keep!` accumulates and never commits.** The run's events land in ONE `:tx`
   at the end, exactly as `compute-clj!` already commits its `:fn` and its table
   together. So an aborted run commits nothing — `suggest!`'s rule — and `undo!`
   removes a report and every table it made in one step.

   The leak, named rather than prevented: `keep!` with a spec calls
   `create-from-relation!`, which writes Parquet at once. An aborted run leaves
   that table behind. `loci.lakegc` reclaims tables no live event references, so
   it is garbage and not corruption."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(def per-row-micros
  "Measured 2026-08-27 on DuckDB 1.5.5 over 1,000,000 rows: about 5 µs to advance
   a JDBC row and 3.7 µs to read one cell. 82% of the cost is the driver and 18%
   is building Clojure maps, which is the opposite of what this design first
   assumed."
  {:row 5.0 :cell 3.7})

(defn- store+handle [st id]
  (let [o (sub/object st id) v (:value o)]
    (cond
      (tbl/handle? v) [(force lake/lake) v]
      (and (sequential? v) (seq v) (every? map? v))
      (let [ts (tbl/inline-table)] [ts (tbl/put-rows! ts id v)])
      :else nil)))

(defn- relation [ts h]
  (if (= :lake (:store h)) (lake/relation-of h) h))

(defn estimate
  "What `q-all` over `id` will cost, before it runs. Not a budget and not a
   governor — nobody asked for either. It informs, exactly as a capped `q` result
   informs, and it is one multiplication over numbers the handle already carries."
  [ctx id]
  (when-let [[ts h] (get-in ctx [:tables id])]
    (let [rows (long (tbl/row-count ts h))
          cols (count (:cols h))]
      {:rows rows :cols cols
       :seconds (/ (* rows (+ (:row per-row-micros)
                              (* cols (:cell per-row-micros))))
                   1e6)})))

(defn context
  "The sandbox context for a run over `id`, scoped to `space`'s tables.

   Returns `{:bindings {…} :events <atom> :reads <atom> :tables {id [ts h]}}`.
   Pass `:bindings` to `sci/eval-string`."
  [st id space]
  (let [ids     (if-let [sp (and space (sub/object st space))]
                  (->> (nb/cells-of sp) (keep #(:ref %))
                       (filter #(tbl/handle? (:value (sub/object st %))))
                       (cons id) distinct vec)
                  [id])
        tables  (into {} (for [i ids :when (store+handle st i)] [i (store+handle st i)]))
        [ts h]  (get tables id)
        events  (atom [])
        reads   (atom #{})
        rel-of  (fn [i] (let [[ts h] (get tables i)] (relation ts h)))
        note!   (fn [hq] (doseq [[i _] tables]
                           (when (str/includes? (pr-str hq) (pr-str (rel-of i)))
                             (swap! reads conj i))))
        run     (fn [hq lim]
                  (note! hq)
                  (let [sqlstr (first (sql/format (assoc hq :limit lim) {:inline true}))
                        cnt    (first (lake/q (force lake/lake)
                                              (first (sql/format {:select [[[:count :*] :n]]
                                                                  :from [[hq :c]]}
                                                                 {:inline true}))))
                        rows   (vec (lake/q (force lake/lake) sqlstr))]
                    (with-meta rows {:total (long (:n cnt))
                                     :capped (> (long (:n cnt)) (count rows))})))]
    {:tables tables :events events :reads reads
     :bindings
     {'table    (rel-of id)
      'tables   (into {} (for [[i _] tables] [i (rel-of i)]))
      'q        (fn ([hq] (run hq tbl/max-query-rows))
                  ([hq lim] (run hq lim)))
      'q-reduce (fn [hq f init]
                  (note! hq)
                  (let [sqlstr (first (sql/format hq {:inline true}))]
                    (lake/reduce-rows (force lake/lake) sqlstr f init)))
      'q-all    (fn [hq] (note! hq)
                  (vec (lake/q (force lake/lake) (first (sql/format hq {:inline true})))))
      'keep!    (fn [title spec-or-rows]
                  (let [nid (str "tbl:derived-" (count @events) "-" (System/nanoTime))
                        obj (if (map? spec-or-rows)
                              ;; create-from-sql!, NOT create-from-relation!. An
                              ;; aggregate's columns are not the source's, so the
                              ;; handle's :cols must be read off the RESULT — see
                              ;; that function's docstring for what a wrong :cols
                              ;; does to every later read.
                              {:id nid :kind :table :title title
                               :value (lake/create-from-sql!
                                       (force lake/lake) nid
                                       (first (sql/format spec-or-rows {:inline true})))
                               :from id}
                              (tbl/table-object @lake/lake nid title (vec spec-or-rows) {:from id}))]
                    (swap! events conj {:op :put :id nid :value obj})
                    nid))}}))

(defn events [ctx] @(:events ctx))
(defn reads  [ctx] @(:reads ctx))
```

> **`lake/reduce-rows` does not exist yet.** Task 9 adds it. Until then `q-reduce`'s test will error — that is expected, and the two tasks may be merged if the executing agent prefers.

- [ ] **Step 4: Run, expect `q-reduce` to be the only failure**

REPL:

```clojure
(require '[loci.sandbox] :reload)
(require '[loci.sandbox-test] :reload)
(clojure.test/run-tests 'loci.sandbox-test)
```

Expected: every test passes except `q-reduce-folds-without-holding`, which errors on `Unable to resolve: lake/reduce-rows`.

- [ ] **Step 5: Commit what works**

```bash
git add src/loci/sandbox.clj test/loci/sandbox_test.clj
git commit -m "feat: the sandbox is handed a database, and rows no longer exists

Five bindings replace one. table and tables are HoneySQL relations that pin their
handles' snapshots; q, q-reduce, q-all and keep! line up one-to-one with the
TableStore protocol.

rows is gone, and that is the enforcement — the model cannot fold a sample because
there is no sample.

Every marker resolves once, when the context is built, so two queries in one run
read the same instant and a report cannot contradict itself between its own
paragraphs.

keep! accumulates and never commits; the run lands one :tx at the end. An aborted
run commits nothing, and undo removes a report with every table it made.

q-reduce lands with lake/reduce-rows in the next commit."
```

---

### Task 9: `lake/reduce-rows` — one cursor, bounded retention

**Files:**
- Modify: `src/loci/lake.clj`
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/lake_test.clj`:

```clojure
(deftest reduce-rows-sees-every-row
  (with-lake [lk]
    (lake/x! lk "CREATE TABLE r_a AS SELECT i AS n FROM range(5000) s(i)")
    (is (= 5000 (lake/reduce-rows lk "SELECT * FROM r_a"
                                  (fn [acc batch] (+ acc (count batch))) 0)))
    (is (= 12497500 (lake/reduce-rows lk "SELECT * FROM r_a"
                                      (fn [acc batch] (+ acc (reduce + (map :n batch)))) 0)))))

(deftest reduce-rows-hands-over-batches-not-one-big-vector
  (with-lake [lk]
    (lake/x! lk "CREATE TABLE r_a AS SELECT i AS n FROM range(5000) s(i)")
    (let [sizes (lake/reduce-rows lk "SELECT * FROM r_a"
                                  (fn [acc batch] (conj acc (count batch))) [])]
      (is (> (count sizes) 1) "one batch means nothing was streamed")
      (is (every? #(<= % 2048) sizes) "DuckDB's vector is 2048; a batch must not exceed it"))))

(deftest reduce-rows-closes-its-statement
  ;; A lazy seq escaping with-open is the classic bug. reduce-rows takes the
  ;; reducing function so the scope is closed; this proves the connection is
  ;; still usable afterwards.
  (with-lake [lk]
    (lake/x! lk "CREATE TABLE r_a AS SELECT i AS n FROM range(100) s(i)")
    (lake/reduce-rows lk "SELECT * FROM r_a" (fn [acc _] acc) nil)
    (is (= [{:n 100}] (lake/q lk "SELECT count(*) AS n FROM r_a")))))
```

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.lake-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: `Unable to resolve symbol: reduce-rows`.

- [ ] **Step 3: Implement `reduce-rows`**

In `src/loci/lake.clj`, after `q-as`:

```clojure
(def batch-rows
  "Rows handed to `reduce-rows`'s function at a time. **2048 because that is
   DuckDB's vector**, and `setFetchSize` is accepted and silently ignored —
   verified 2026-08-27. Not ours to tune."
  2048)

(defn reduce-rows
  "Fold `f` over every row of `sql`, one batch at a time. `f` is
   `(fn [acc batch] …)` where `batch` is a vector of row maps.

   **Takes the reducing function so it owns the scope.** It does not hand back a
   lazy seq: one escaping `with-open` is the classic Clojure bug, and `q` closes
   its statement on the way out.

   **What this bounds is RETENTION, and not time.** Measured 2026-08-27 over
   1,000,000 rows of four columns: advancing the cursor costs 4,954 ms, reading
   the four columns costs 22,297 ms, and building maps takes it to 27,335 ms. So
   82% of the cost is the driver and batching saves the other 18%. It does not
   bound allocation pressure either — over 857,142 rows with batches dropped
   every 2,048, observed heap still rose to 506 MB between collections.

   DuckDB's JDBC does stream: `executeQuery` over 1,000,000 rows returns in 18 ms
   and the first row in 1 ms."
  [lk sql f init]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (let [md (.getMetaData rs)
          n  (.getColumnCount md)
          ks (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n))]
      (loop [acc init batch (transient [])]
        (if (.next rs)
          (let [row (persistent!
                     (reduce (fn [m i] (assoc! m (nth ks i) (unwrap (.getObject rs (inc i)))))
                             (transient {}) (range n)))
                b   (conj! batch row)]
            (if (>= (count b) batch-rows)
              (recur (f acc (persistent! b)) (transient []))
              (recur acc b)))
          (let [b (persistent! batch)]
            (if (seq b) (f acc b) acc)))))))
```

- [ ] **Step 4: Run and watch them pass**

REPL:

```clojure
(require '[loci.lake] '[loci.sandbox] :reload)
(require '[loci.lake-test] '[loci.sandbox-test] :reload)
(clojure.test/run-tests 'loci.lake-test)
(clojure.test/run-tests 'loci.sandbox-test)
```

Expected: `0 failures, 0 errors` in both.

- [ ] **Step 5: Sabotage — prove the batching test bites**

Change `batch-rows` to `1000000`. Run the suite. Expected: `reduce-rows-hands-over-batches-not-one-big-vector` fails — `(> (count sizes) 1)` is false.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: one cursor, one batch at a time, and the scope belongs to the fold

reduce-rows takes the reducing function rather than returning a lazy seq, because
a lazy seq escaping with-open is the classic bug and q closes its statement on the
way out.

What it bounds is retention, not time. Measured 2026-08-27 over 1,000,000 rows of
four columns: cursor advance 4,954 ms, plus getObject on four columns 22,297 ms,
plus map building 27,335 ms. 82% is the driver. Batching saves the other 18%.

Batch size is 2048 because that is DuckDB's vector, and setFetchSize is accepted
and silently ignored. DuckDB's JDBC does stream: executeQuery over 1M rows returns
in 18 ms, first row in 1 ms."
```

---

### Task 10: `compute-clj!` returns three shapes and commits once

**Files:**
- Modify: `src/loci/server.clj` (`run-clj-rows`, `compute-clj!`)
- Modify: `src/loci/agent.clj` (`make-clj-transform`)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/server_test.clj`:

```clojure
(deftest compute-returns-a-table-for-rows
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _] "(q {:select [[[:count :*] :n]] :from [[table :t]]})")]
          (let [r (server/compute-clj! st "tbl:big" "count them" nil)]
            (is (= :table (:kind (sub/object st (:openId r)))))))))))

(deftest compute-returns-a-report-for-blocks
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _]
                        (str "(let [id (keep! \"All\" {:select [:*] :from [[table :t]]})]"
                             "  [{:block :heading :text \"Report\"}"
                             "   {:block :ref :id id}])"))]
          (let [r   (server/compute-clj! st "tbl:big" "report" nil)
                obj (sub/object st (:openId r))]
            (is (= :report (:kind obj)))
            (is (= 2 (count (:value obj))))
            (is (= :ref (:block (second (:value obj)))))
            (is (some? (sub/object st (:id (second (:value obj)))))
                "the ref must point at a table the same :tx committed")))))))

(deftest compute-commits-once-and-undo-takes-it-all
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _]
                        (str "(let [a (keep! \"A\" {:select [:*] :from [[table :t]] :limit 5})"
                             "      b (keep! \"B\" {:select [:*] :from [[table :t]] :limit 6})]"
                             "  [{:block :ref :id a} {:block :ref :id b}])"))]
          (let [before (count (sub/objects st))
                r      (server/compute-clj! st "tbl:big" "two tables" nil)]
            (is (= (+ before 4) (count (sub/objects st))) "report + fn + two tables")
            (sub/undo! st)
            (is (= before (count (sub/objects st)))
                "one :tx means one undo removes the report and both tables")))))))

(deftest compute-records-what-it-read
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big" (vec (for [n (range 2500)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _] "(q {:select [:*] :from [[table :t]] :limit 3})")]
          (let [r  (server/compute-clj! st "tbl:big" "peek" nil)
                fo (first (filter #(= :fn (:kind %)) (vals (sub/objects st))))]
            (is (= #{"tbl:big"} (set (:reads (:value fo))))
                ":reads must come from what q resolved, never from parsing the code")))))))
```

- [ ] **Step 2: Run and watch them fail**

REPL:

```clojure
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: failures — `compute-clj!` still binds `rows` and commits a table only.

- [ ] **Step 3: Rewrite `run-clj-rows` and `compute-clj!`**

In `src/loci/server.clj`, replace both:

```clojure
(defn- run-sandboxed
  "Eval model-written Clojure with the sandbox context's bindings. Returns the
   value the code produced — rows, blocks or a string. Throws on eval errors;
   callers keep their own try."
  [code ctx]
  (let [res (sci/eval-string code (assoc sci-opts :bindings (:bindings ctx)))]
    (if (fn? res) (res) res)))

(defn- shape-of
  "What the code returned, branched on SHAPE and not on a flag — the idiom
   `hydrate` and `obj-digest` already use.

   `:report` before `:table`, because a vector of blocks is also a vector of maps
   and `content/report?` is the predicate that separates them."
  [v]
  (cond
    (string? v)                                   :doc
    (and (sequential? v) (seq v) (every? :block v)) :report
    (and (sequential? v) (seq v) (every? map? v))  :table
    :else nil))

(defn compute-clj! [st id prompt space]
  (try
    (let [[ts h] (store+handle (sub/object st id))]
      (if-not ts
        {:error "compute works on tables"}
        (let [profile (tbl/column-profile ts h)
              samples (tbl/column-samples ts h 3 12)
              attempt (fn [prev-code err]
                        (let [ctx  (sbx/context st id space)
                              code (agent/make-clj-transform profile samples prompt prev-code err)]
                          (try {:value (run-sandboxed code ctx) :code code :ctx ctx}
                               (catch Exception e {:error (.getMessage e) :code code :ctx ctx}))))
              r1 (attempt nil nil)
              r  (if (:error r1) (attempt (:code r1) (:error r1)) r1)]
          (if (:error r)
            {:error (str "compute failed: " (:error r))}
            (let [{:keys [value code ctx]} r
                  kind (shape-of value)]
              (if-not kind
                {:error "the function returned neither rows, nor report blocks, nor text"}
                (let [objm (sub/objects st)
                      p    (str/trim prompt)
                      fid  (next-id-in objm (str "fn:" (subs id (inc (str/index-of id ":"))) "-"))
                      ttl  (if (> (count p) 48) (str (subs p 0 48) "…") p)
                      fobj {:id fid :kind :fn :title (str "fn: " p)
                            :value {:source id :reads (vec (sbx/reads ctx))
                                    :prompt p :lang "clojure" :code code}}
                      oid  (next-id-in objm (case kind
                                              :table  "tbl:derived-"
                                              :report "rep:"
                                              :doc    "note:"))
                      oobj (case kind
                             :table  (tbl/table-object @lake/lake oid ttl value {:from id :via fid})
                             :report {:id oid :kind :report :title ttl :value (vec value)
                                      :from id :via fid}
                             :doc    {:id oid :kind :doc :title ttl :value value
                                      :from id :via fid})
                      ;; ONE :tx. keep!'s events, the fn, the result, the cell.
                      evs  (cond-> (into (vec (sbx/events ctx))
                                         [{:op :put :id fid :value fobj}
                                          {:op :put :id oid :value oobj}])
                             (and space (space? st space))
                             (conj (nb/append-cell-event st space {:ref oid})))]
                  (sub/commit! st {:op :tx :events evs})
                  {:state (state-payload st) :openId oid
                   :object (mold-payload st mem/memory oid nil)})))))))
    (catch Exception e {:error (str "compute failed: " (.getMessage e))})))
```

Add `[loci.sandbox :as sbx]` to `loci.server`'s `:require`.

- [ ] **Step 4: Rewrite the prompt**

In `src/loci/agent.clj`, replace `make-clj-transform`'s signature and system prompt. The new signature is `[profile samples prompt prev-code err]`. The prompt body:

```clojure
(def ^:private clj-transform-sys
  (str
   "You write ONE Clojure expression that answers a question about a data table.\n\n"
   "You do NOT have the rows. You have a database. These are in scope:\n\n"
   "  (q honeysql)                 → rows, as a vector of maps. Runs in DuckDB.\n"
   "                                 (meta result) carries :total, the answer's\n"
   "                                 true size, and :capped when you got a page.\n"
   "  (q-reduce honeysql f init)   → fold over EVERY row, one batch at a time.\n"
   "                                 f is (fn [acc batch] …); batch is a vector.\n"
   "  (q-all honeysql)             → every row in memory. EXPENSIVE — about\n"
   "                                 5 µs a row plus 3.7 µs a cell. Use last.\n"
   "  (keep! \"Title\" honeysql)     → materialise a result as its own table.\n"
   "                                 Returns its id. No row enters memory.\n"
   "  (keep! \"Title\" rows)         → same, for rows you already hold.\n"
   "  table                        → this table, as a HoneySQL relation.\n"
   "  tables                       → {id relation} for the notebook's tables.\n\n"
   "Write queries like this:\n"
   "  (q {:select [:region [[:sum :revenue] :revenue]]\n"
   "      :from [[table :t]] :group-by [:region] :order-by [[:revenue :desc]]})\n\n"
   "DO THE ARITHMETIC IN q. Use Clojure only for what SQL cannot say.\n\n"
   "Return ONE of three things:\n"
   "  · a vector of maps            → becomes a data table\n"
   "  · a vector of report blocks   → becomes a report\n"
   "  · a string                    → becomes a note\n\n"
   "A report block is one of:\n"
   "  {:block :heading :text \"…\"}\n"
   "  {:block :text :text \"…\"}\n"
   "  {:block :ref :id <a keep! id>}\n"
   "  {:block :chart :ref <a keep! id> :spec {…}}\n\n"
   "A block must NEVER carry rows from a capped q result. If (meta r) says\n"
   ":capped, call keep! and reference the table instead.\n\n"
   "Return the expression only. No prose, no markdown fence."))
```

and the user message carries the profile and samples:

```clojure
(defn make-clj-transform
  "The Clojure the model writes for one table. `profile` is
   `TableStore/column-profile`'s output and `samples` is `column-samples`' — both
   bounded, and neither is a row of the table.

   Before 2026-08-27 this was handed three sample rows and asked for a function
   over `rows`. There is no `rows` any more; see `loci.sandbox`."
  [profile samples prompt prev-code err]
  (let [ctx (str "columns: " (str/join ", " (map name (:cols profile))) "\n"
                 "numeric: " (str/join ", " (map name (:numeric profile))) "\n"
                 "categorical: " (str/join ", " (map name (:categorical profile))) "\n"
                 "rows: " (:rows profile) "\n"
                 "sample values:\n"
                 (str/join "\n" (for [[c vs] samples]
                                  (str "  " (name c) ": " (str/join " | " vs)))))
        usr (cond-> (str ctx "\n\nTask: " prompt)
              prev-code (str "\n\nYour previous attempt:\n" prev-code
                             "\n\nIt failed with:\n" err "\n\nFix it."))]
    (chat [{:role "system" :content clj-transform-sys}
           {:role "user" :content usr}])))
```

- [ ] **Step 5: Run and watch them pass**

REPL:

```clojure
(require '[loci.agent] '[loci.sandbox] '[loci.server] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Sabotage — prove the one-`:tx` test bites**

In `compute-clj!`, commit `keep!`'s events separately before the main commit:

```clojure
                  (doseq [e (sbx/events ctx)] (sub/commit! st e))
                  (sub/commit! st {:op :tx :events (remove #(some #{%} (sbx/events ctx)) evs)})
```

Run the suite. Expected: `compute-commits-once-and-undo-takes-it-all` fails — one `undo!` leaves the two tables behind.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 7: Sabotage — prove `:reads` is not parsed from the code**

In `compute-clj!`, replace `(vec (sbx/reads ctx))` with `[id]`. Run the suite. Expected: `compute-records-what-it-read` still passes, because this run reads only `id`. **That means the test is too weak.** Strengthen it: add a second table to the notebook, have the stub code query both through `tables`, and assert `:reads` holds both ids. Then re-run the sabotage and confirm it fails.

This step exists because an assertion that cannot fail is worse than no assertion — five of them have shipped on this project.

- [ ] **Step 8: Full suites, then commit**

```bash
clojure -M:test
npm run test:browser
```

Both green, then:

```bash
git add src/loci/server.clj src/loci/agent.clj test/loci/server_test.clj
git commit -m "feat: compute-clj! grows up — a query surface, three shapes, one :tx

The sandbox no longer gets rows. compute-clj! builds a loci.sandbox context and
the model writes queries; the prompt is rewritten around the four verbs and told
to do the arithmetic in q.

What the code returns is branched on SHAPE and not on a flag, the idiom hydrate
and obj-digest already use: a vector of maps is a table, a vector of blocks is a
report, a string is a note.

Everything lands in ONE :tx — keep!'s tables, the :fn, the result, the cell — so
an aborted run commits nothing and one undo removes a report with every table it
made.

The :fn object gains :reads, recorded from which relations q actually resolved and
never parsed out of the code, so rerun! refreshes a report when any input changes."
```

---

## Phase 4 — the report points at real objects

### Task 11: `:ref` and `:chart :ref` blocks

**Files:**
- Modify: `src/loci/content.clj` (report predicate and viewers)
- Modify: `src/loci/server.clj` (`mold-payload` resolves a `:ref` block)
- Test: `test/loci/content_test.clj` (create if absent)

- [ ] **Step 1: Confirm `report?` needs no change, and find the public entry point**

`loci.content/report?` is **private** and reads `(and (rows? x) (every? :block x))`
(`content.clj:157`). It therefore already accepts `{:block :ref :id …}`, and
`table?` is `(and (rows? x) (not (report? x)))`, so a report is never mistaken for
a table. **No change to `report?` is needed** — do not invent one.

Because it is private, the test must go through the mold registry. Find the public
call in the REPL before writing the test:

```clojure
(require '[loci.mold :as mold] '[loci.content])
(clojure.repl/dir loci.mold)
```

Use whichever of `mold/views-for` / `mold/mold` / `mold/alternatives` the registry
exposes — the test below is written against `mold/mold`; if the public name differs,
change the two call sites and nothing else.

- [ ] **Step 2: Write the failing test**

Create `test/loci/content_test.clj`:

```clojure
(ns loci.content-test
  (:require [clojure.test :refer [deftest is]]
            [loci.content]
            [loci.mold :as mold]))

(def report-with-ref
  [{:block :heading :text "Revenue by region"}
   {:block :text :text "EMEA leads."}
   {:block :ref :id "tbl:derived-31"}
   {:block :chart :ref "tbl:derived-31" :spec {:kind "bar"}}])

(def report-inline
  [{:block :heading :text "Revenue by region"}
   {:block :table :title "By region" :rows [{:region "EMEA" :v 1}]}])

(deftest a-ref-block-report-molds-as-a-report
  (is (= :report (:kind (mold/mold report-with-ref))))
  (is (= :report (:kind (mold/mold report-inline)))
      "the shipped inline form must keep working"))

(deftest a-ref-block-report-is-not-molded-as-a-table
  (is (not= :table (:kind (mold/mold report-with-ref)))
      "table? is (and rows? (not report?)) — a ref block must not fall through it"))

(deftest the-outline-viewer-names-only-the-headings
  (is (= ["Revenue by region"]
         (:rendered (mold/mold report-with-ref :report/outline)))))
```

- [ ] **Step 3: Run — these should pass without touching `content.clj`**

REPL:

```clojure
(require '[loci.content] '[loci.mold] :reload)
(require '[loci.content-test] :reload)
(clojure.test/run-tests 'loci.content-test)
```

Expected: `0 failures`. If `a-ref-block-report-molds-as-a-report` fails, `report?`
is narrower than `content.clj:157` shows and the plan's premise is wrong — read it
again before changing anything.

- [ ] **Step 4: Resolve a `:ref` block in `mold-payload`**

In `src/loci/server.clj`'s `mold-payload`, where a report's blocks are prepared for the wire, add:

```clojure
        ;; A :ref block resolves through mold-payload, THE SAME CALL the notebook
        ;; cell renderer already makes for {:ref id}. One mechanism in two places,
        ;; not a second resolver — a report block and a cell must never disagree
        ;; about how an object is drawn.
        resolve-block
        (fn [b]
          (case (:block b)
            :ref   (assoc b :object (mold-payload st recall (:id b) nil))
            :chart (cond-> b (:ref b) (assoc :object (mold-payload st recall (:ref b) nil)))
            b))
```

and map it over the report's `:value` before it goes on the wire.

> **Recursion guard:** `mold-payload` calling itself is bounded because a `:ref` names a `:table`, and a table's payload has no blocks. If a `:ref` ever names a report, this loops. Add an explicit refusal: `:ref` to an object whose `:kind` is `:report` returns `{:error "a report may not embed a report"}` rather than recursing.

- [ ] **Step 5: Run and watch them pass**

REPL:

```clojure
(require '[loci.content] '[loci.server] :reload)
(require '[loci.content-test] :reload)
(clojure.test/run-tests 'loci.content-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Sabotage — prove the recursion guard bites**

Write a test that builds a report whose `:ref` names another report, and assert `mold-payload` returns the error rather than looping. Remove the guard and confirm the test hangs or blows the stack — then restore it. **Run this one with a timeout**, because the failure mode is a hang:

```clojure
(let [f (future (server/mold-payload st nil "rep:1" nil))]
  (deref f 3000 :TIMED-OUT))
```

Expected without the guard: `:TIMED-OUT`. With it: the error map.

- [ ] **Step 7: Full suites, then commit**

```bash
clojure -M:test
npm run test:browser
```

Both green, then:

```bash
git add src/loci/content.clj src/loci/server.clj test/loci/content_test.clj
git commit -m "feat: a report block can point at a real table, and one resolver draws both

{:block :ref :id …} and {:block :chart :ref … :spec …} join the report kind.
Inline {:block :table :rows …} stays — the shipped demo report uses it.

A :ref resolves through mold-payload, the same call the notebook cell renderer
already makes for {:ref id}. One mechanism in two places, so a report block and a
cell can never disagree about how an object is drawn.

Every number in a report is now openable, moldable, pivotable and undoable on its
own, which is what loci is.

A :ref naming a report is refused rather than recursed, and the test for it runs
under a timeout because the failure mode is a hang and not an exception."
```

---

### Task 12: capped rows may never go inline

**Files:**
- Modify: `src/loci/server.clj` (`compute-clj!`'s validation)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/loci/server_test.clj`:

```clojure
(deftest a-capped-result-cannot-be-put-inline-in-a-report
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:big"
                              (vec (for [n (range 12000)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:big"
                         :value {:id "tbl:big" :kind :table :title "Big" :value h}})
        ;; 12,000 rows is above max-query-rows (10,000), so this q is capped.
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _]
                        (str "(let [r (q {:select [:*] :from [[table :t]]})]"
                             "  [{:block :table :title \"All\" :rows r}])"))]
          (let [r (server/compute-clj! st "tbl:big" "dump it" nil)]
            (is (:error r))
            (is (clojure.string/includes? (:error r) "capped")
                "the error must name the rule, so the retry can fix it")))))))

(deftest a-full-result-may-go-inline
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:small" (vec (for [n (range 50)] {:n n})))]
        (sub/commit! st {:op :put :id "tbl:small"
                         :value {:id "tbl:small" :kind :table :title "Small" :value h}})
        (with-redefs [loci.agent/make-clj-transform
                      (fn [& _]
                        (str "(let [r (q {:select [:*] :from [[table :t]]})]"
                             "  [{:block :table :title \"All\" :rows r}])"))]
          (let [r (server/compute-clj! st "tbl:small" "dump it" nil)]
            (is (nil? (:error r)))
            (is (= :report (:kind (sub/object st (:openId r)))))))))))
```

- [ ] **Step 2: Run and watch it fail**

REPL:

```clojure
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: `a-capped-result-cannot-be-put-inline-in-a-report` fails — no rule exists yet.

- [ ] **Step 3: Mark capped rows and refuse them inline**

`q` already returns rows with `{:capped true}` in their metadata. Add to `compute-clj!`, after `shape-of` returns `:report`:

```clojure
(defn- capped-inline-block
  "The first report block carrying rows that came back capped, or nil.

   **This is the rule that keeps a report honest.** If `q` capped, the only way
   those numbers reach a report is `keep!` — which makes a real table the block
   refs, and the grid's paging, cursor and \"showing 101–200 of 900,000\" caption
   then come for free. Same trick as removing `rows` from the sandbox: the honest
   path is the only path, rather than the one the prompt asks nicely for."
  [blocks]
  (first (filter #(and (= :table (:block %)) (:capped (meta (:rows %)))) blocks)))
```

and in `compute-clj!`, between `shape-of` and the commit:

```clojure
                (if-let [bad (and (= :report kind) (capped-inline-block value))]
                  {:error (str "block \"" (:title bad) "\" holds rows from a capped query. "
                               "Call keep! and use {:block :ref :id <id>} instead.")}
                  ;; …the existing commit path…
                  )
```

> **Metadata survival:** `q` attaches `:capped` with `with-meta` on the rows vector. Putting that vector in a map preserves its metadata. Verify in the REPL before relying on it:
> ```clojure
> (:capped (meta (:rows {:rows (with-meta [1 2] {:capped true})})))
> ```
> Expected: `true`. If `json-safe` or `walk/postwalk` runs over the value first, metadata is lost — in that case carry `:capped` as a key on the block instead, and change the predicate to match.

- [ ] **Step 4: Run and watch them pass**

REPL:

```clojure
(require '[loci.server] :reload)
(require '[loci.server-test] :reload)
(clojure.test/run-tests 'loci.server-test)
```

Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Sabotage — prove both tests bite**

Change `capped-inline-block` to return `nil` always. Run the suite. Expected: `a-capped-result-cannot-be-put-inline-in-a-report` fails.

Then change it to return the first `:table` block regardless of `:capped`. Run again. Expected: `a-full-result-may-go-inline` fails. **Both directions must fail** — a one-sided guard is a guard that refuses everything or nothing.

Restore and re-run. Expected: `0 failures`.

- [ ] **Step 6: Full suites, then commit**

```bash
clojure -M:test
npm run test:browser
```

Both green, then:

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: a capped result may not go inline in a report, and the error says why

If q capped, the only way those numbers reach a report is keep! — which makes a
real table the block refs, and the grid's paging, cursor and 'showing 101-200 of
900,000' caption all come for free from the work that shipped on 2026-08-25.

Same trick as removing rows from the sandbox: the honest path is the only path,
rather than the one the prompt asks nicely for. The error names the rule so the
retry loop can fix it.

Both directions are pinned: refusing a capped block, and NOT refusing a full one.
A one-sided guard refuses everything or nothing."
```

---

## Final: the spec's tests, checked off

The spec lists eight tests that must bite. Confirm each has a home before calling this done:

| spec test | task |
|---|---|
| 1. `query_table` exact above `mold-cap` | Task 5 |
| 2. two `q` calls read one snapshot | **Task 3** covers pinning; add the two-call case to Task 8's suite |
| 3. `:total` is the answer, not the page | Tasks 2, 3, 5 |
| 4. `keep!` with a spec, true count, no heap | Tasks 3, 8 |
| 5. blocks → `:report`, maps → `:table` | Task 10 |
| 6. `undo!` removes report and its tables | Task 10 |
| 7. `q-reduce` completes where `q-all` fails | **not covered** — see below |
| 8. `:ref` block and cell resolve the same | Task 11 |

**Test 7 has no task, and that is deliberate.** It needs a table larger than the JVM heap, which on a 4 GB heap means about 18 million rows of four columns — a 2.9 s build per the 2026-08-16 spec, but a multi-minute test. Add it as a **manual REPL check** recorded in the spec, not as a suite test: a suite that takes four minutes to run stops being run.

- [ ] **Final step: record the manual check**

Append to the spec's gate section:

```markdown
**Manual, not in the suite:** `q-reduce` over a table larger than heap completes
where `q-all` fails. About 18M rows of four columns on a 4 GB heap. Run it once by
hand in the REPL after Task 9; do not put a four-minute test in `clojure -M:test`,
because a suite that slow stops being run.
```

```bash
git add docs/superpowers/specs/2026-08-27-let-the-model-write-a-query-design.md
git commit -m "docs: the one test that stays out of the suite, and why"
```
