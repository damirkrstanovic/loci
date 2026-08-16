# DuckLake tables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every loci table's rows out of the event log and into a DuckLake catalog, so a
table can hold millions of rows while `undo!`, `as-of` and the time scrubber keep working
exactly as they do today.

**Architecture:** The log stores a *handle* — `{:store :lake :table "t_…" :snapshot 12 …}` —
never rows. Reads always go `AT (VERSION => <recorded snapshot>)`, which is what makes `as-of`
a pointer dereference instead of a re-fold. Writes always create a new lake table, which is
what loci already does semantically (every path that produces a table creates a new one).
Readers are unchanged: a `hydrate` call swaps the handle back for rows at each point an object
leaves the substrate, so viewers, `alternatives` and `viewspec` never learn that anything moved.

*Corrected 2026-08-16 after Task 6's review.* This sentence originally read "one `hydrate` call
at the top of `mold-payload`". There are **twelve** such points, not one — `mold-payload`,
`table-rows`, `describe!`, `make-view!`, `compute-clj!`, `do!`, `recompute`, `obj-digest`'s four
callers, `list_tables` and `query_table`. Task 6 did `mold-payload`; Task 6b does the rest, and
it exists because writing "one call" was an assumption rather than a grep.

**Tech Stack:** DuckDB 1.5.5 + the `ducklake` extension via `org.duckdb/duckdb_jdbc 1.5.5.1`;
`clojure.data.json` (already a dep) for bulk load; the existing `sub/Store` and `loci.dlv`
lifecycle patterns.

**Source spec:** `docs/superpowers/specs/2026-08-16-big-content-design.md`, whose measurement
gate was run on 2026-08-16. The numbers below are from that run, not estimates.

---

## Scope

This plan is **one of three** the spec decomposes into. It builds tables only.

**In scope:** the `TableStore` seam, a DuckLake implementation, the handle in the event,
`hydrate` for readers, converting all six table-writing sites, a dry-run migration of the 29
existing tables, boot/shutdown wiring, and `as-of` correctness.

**Explicitly out of scope, and why:**

- **`ContentStore` / blobs / CIDs / SHA-256** — a separate subsystem with no dependency in
  either direction (DuckLake tables use snapshot ids, not CIDs). Its own plan.
- **Images, EXIF, VLM descriptions** — depend on `ContentStore`, not on this. Its own plan.
- **HoneySQL and rewriting `query_table`** — the spec wants queries as data, but that is a new
  dependency and a new agent-facing surface. `query_table` and `list_tables` keep working
  through `hydrate` in this plan and are rewritten later. Stated so nobody reads the omission
  as an oversight.
- **Table GC** — the spec already decided it ships disabled, because DuckLake
  [cannot expire the latest snapshot](https://github.com/duckdb/ducklake/issues/261) and
  snapshot expiry has open data-loss bugs. Retention is "keep everything". Task 10 makes that
  a stated condition rather than a silent one.
- **In-place table mutation** (append/update/delete on an existing table). Nothing in loci
  does this today — checked, and recorded in the spec. Task 5 ships `restore-to!` and the
  regression test for it anyway, because that is what the measurement gate found; it is not
  wired into a write path because there is no write path that needs it.

**Two protocol methods the spec names and this plan does not have**, so that a reviewer
checking the plan against the spec finds the reasoning here rather than a gap:

- `query` — HoneySQL, deferred with `query_table` above.
- `at [this handle]`, "a read-only view at a past handle" — **subsumed, not dropped.** Because
  decision 1 makes *every* read name its version, a handle already is the read-only view at
  that handle; a separate method would be a second way to say the same thing and a second
  thing to get wrong. If a future caller needs a `TableStore` pinned to a past version rather
  than a handle that carries one, add it then.

## Design decisions this plan locks

Read these before starting any task. Three of them are load-bearing and two are corrections to
what an implementer would otherwise guess.

**1. Reads always specify a version.** Never `SELECT … FROM t`; always
`SELECT … FROM t AT (VERSION => <recorded>)`. This is not an optimisation — it is what makes a
`FrozenStore` read the past correctly, and it costs nothing (measured: 34 ms at 10M rows,
*faster* than reading the current version at 52 ms, because an old snapshot has no deletion
vectors to apply).

**2. Writes always create a new lake table.** `put-rows!` never overwrites. This mirrors
loci's actual semantics — `save-table!`, `import-csv!`, `compute-clj!`, an `fnlib` apply and
the corpus import all mint a new object id — so two events can never contend for one lake
table, and the divergence the measurement found cannot arise.

**3. `undo!` needs no hook, and here is the proof obligation.** Because of (2), popping an
event that created a table leaves the object gone from state and its lake table unreferenced —
orphaned, which is GC's problem, not undo's. The resurrection bug measured on 2026-08-16
requires *two events writing one lake table*, which (2) forbids. `restore-to!` exists and is
tested (Task 5) so that the day in-place mutation arrives, the fix is already proven rather
than rediscovered.

**4. The handle records the lake table name explicitly**, rather than deriving it from the
object id at read time. A derivation function that is ever changed, or that collides after
sanitising, would silently orphan real data. The name is written once and read back.

**5. One connection is enough — measured 2026-08-16, not assumed.** loci is single-writer, but
http-kit serves on a thread pool, so several reads reach `@lake/lake` at once and DuckDB
connections are not thread-safe in general. Two runs on one connection:

| | result |
|---|---|
| 32 concurrent reads over a 200k-row table | 0 errors; 7 distinct sums, each correct for its residue class — no crossed results |
| 8 reader threads (42 reads) while a writer created 6 tables | 0 errors; all 7 tables present afterwards |

So: **one connection, no lock.** Do not add a monitor around reads "to be safe" — that is the
hand-rolled mechanism `CLAUDE.md` names, and the measurement says there is nothing to guard.

**6. Readers hydrate, they do not learn.** `(tbl/hydrate st o)` returns the object with
`:value` swapped from handle to rows. Call it once at the top of `mold-payload` and at the
other read entry points. Do **not** teach `mold/mold`, `alternatives`, `viewspec/interpret`,
or `content.clj`'s predicates about handles — they receive rows, as they do today.

## File structure

| file | responsibility |
|---|---|
| `src/loci/tablestore.clj` (new) | the `TableStore` protocol, the handle predicate, `InlineTable`, `hydrate`, `table-object` |
| `src/loci/lake.clj` (new) | `DuckLakeTable`, connection lifecycle, the `lake` singleton, `restore-to!` |
| `src/loci/tablemigrate.clj` (new) | dry-run-by-default conversion of inline tables to the lake |
| `test/loci/tablestore_test.clj` (new) | protocol behaviour, `InlineTable`, `hydrate` |
| `test/loci/lake_test.clj` (new) | round-trip, versioned reads, `restore-to!`, the divergence regression |
| `test/loci/tablemigrate_test.clj` (new) | dry run writes nothing; live run converts and verifies |
| `src/loci/tools.clj` | `save-table!` and the `:save_as` path build handles |
| `src/loci/server.clj` | `import-csv!`, `compute-clj!`, the `fnlib` apply, `mold-payload` |
| `src/loci/corpus.clj` | the corpus table site |
| `deps.edn`, `build.clj` | the DuckDB dependency and its platform natives |

`loci.content` is **not** the content store — it is the demo seed data, and the name is taken.
The blob plan will need `loci.blobs`.

## How to run things in this project

Read `.claude/skills/clojure-repl-dev` and `CLAUDE.md` first. The short version, because it
changes every "Run:" step below:

- **Do not start a JVM to run Clojure.** `clojure -M -e` costs ~25 s before the first form
  evaluates. Use `mcp__clojure-mcp__clojure_eval` against the running nREPL.
- **Iterate in the REPL.** After editing a namespace, `(require 'loci.lake :reload)` then call
  the test var directly: `(clojure.test/test-vars [#'loci.lake-test/round-trips])`.
- **Reload a protocol namespace BEFORE its implementors**, never after. Reloading
  `loci.tablestore` after `loci.lake` orphans every record with
  `No implementation of method: :put-rows!`.
- `clojure -M:test` is for **one final full-suite run before a commit** — not for iteration.
  It is `-n <namespace>`, not `-v` (which selects vars).
- Both suites must pass before the final commit: `clojure -M:test` **and**
  `npm run test:browser`. The first does not mean "all the tests".

**Every task ends with a sabotage step.** Reintroduce the bug the test exists to catch,
confirm the test **fails**, restore, confirm it passes. A test that passes either way is worse
than no test, because it certifies. Report the actual failure output.

**Sabotaging a `use-fixtures` line needs more than `:reload`** — found the hard way in Task 6,
2026-08-16. `use-fixtures` writes to *namespace metadata*, and re-evaluating `(ns …)` does not
clear it, so deleting the line and calling `(require 'loci.server-test :reload)` leaves the old
fixture still registered. The suite then behaves as though the fixture were present, which
reads as "removing it changes nothing" — a false negative that would retire a fixture that is
actually load-bearing. The honest sequence is `(remove-ns 'loci.server-test)`, drop the symbol
from `*loaded-libs*`, then `require`. `defonce` has the same shape of problem and wants
`ns-unmap` (Task 4). Both are the environment masking the sabotage rather than the code being
dead — the distinction the Task 1 note below insists on.

---

## Task 1: The DuckDB dependency and a lake that opens

**Files:**
- Modify: `deps.edn`
- Modify: `build.clj:29-45` (the `foreign-natives` vector)
- Create: `src/loci/lake.clj`
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Add the dependency**

In `deps.edn`, under `:deps`, after `datalevin/datalevin`:

```clojure
        org.duckdb/duckdb_jdbc {:mvn/version "1.5.5.1"}
```

- [ ] **Step 2: Write the failing test**

Create `test/loci/lake_test.clj`:

```clojure
(ns loci.lake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loci.lake :as lake]))

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-lake
  "A fresh DuckLake in a temp directory, always removed."
  [[sym] & body]
  `(let [dir# (str (System/getProperty "java.io.tmpdir") "/loci-lake-" (System/nanoTime))
         ~sym (lake/open-lake! dir#)]
     (try ~@body (finally (lake/close! ~sym) (rm-rf dir#)))))

(deftest opens-and-answers
  (with-lake [lk]
    (is (= [{:answer 2}] (lake/q lk "SELECT 1 + 1 AS answer")))))

(deftest ducklake-is-attached
  (with-lake [lk]
    (is (= [{:name "lake"}]
           (lake/q lk "SELECT database_name AS name FROM duckdb_databases() WHERE database_name = 'lake'")))))
```

- [ ] **Step 3: Run it to make sure it fails**

In the REPL:

```clojure
(require 'loci.lake-test :reload)
```

Expected: FAIL — `Could not locate loci/lake__init.class` (the namespace does not exist yet).

- [ ] **Step 4: Write the minimal implementation**

Create `src/loci/lake.clj`:

```clojure
(ns loci.lake
  "Tables live here. The substrate's log holds a handle; the rows are Parquet
   under a DuckLake catalog, and every read names the snapshot it wants.

   Measured 2026-08-16 (see the big-content spec): attaching a lake holding 10M
   rows costs 75 ms against loci's ~14 s dependency load, and a versioned read
   at 10M rows is 34 ms — faster than reading the current version, because an
   old snapshot carries no deletion vectors."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.substrate :as sub]))

(defn- conn-of [lk] (:conn lk))

(defn x!
  "Execute one statement. Returns :ok."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))] (.execute st sql))
  :ok)

(defn q
  "Execute one query. Returns a vector of maps with keyword columns."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (let [md (.getMetaData rs)
          n  (.getColumnCount md)
          ks (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n))]
      (loop [acc (transient [])]
        (if (.next rs)
          (recur (conj! acc (into {} (map-indexed (fn [i k] [k (.getObject rs (inc i))]) ks))))
          (persistent! acc))))))

(defn- ensure-extension!
  "LOAD first, INSTALL only if that fails. A first-ever INSTALL fetches over the
   network; a machine that already has the extension must never reach for it,
   and a packaged build must install it at image-build time rather than on a
   user's first boot."
  [lk]
  (try (x! lk "LOAD ducklake")
       (catch Exception _
         (x! lk "INSTALL ducklake")
         (x! lk "LOAD ducklake"))))

(defrecord Lake [conn dir])

(defn open-lake!
  "Open (or create) the DuckLake catalog in `dir`. Defaults to <data-dir>/lake."
  ([] (open-lake! (str (sub/data-dir) "/lake")))
  ([dir]
   (io/make-parents (io/file dir "catalog.duckdb"))
   (.mkdirs (io/file dir))
   ;; The driver is instantiated directly rather than reached through
   ;; DriverManager: DriverManager cannot see a driver loaded by a child
   ;; classloader, which is how this namespace gets exercised from a REPL that
   ;; added the dep with `add-lib`. Direct instantiation works in both.
   (let [conn (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
         lk   (->Lake conn dir)]
     (try
       (ensure-extension! lk)
       (x! lk (str "ATTACH 'ducklake:" dir "/catalog.duckdb' AS lake (DATA_PATH '" dir "/files/')"))
       (x! lk "USE lake")
       lk
       (catch Throwable t (.close conn) (throw t))))))

(defn close! [lk] (when-let [c (conn-of lk)] (.close c)))
```

- [ ] **Step 5: Run the tests to verify they pass**

```clojure
(require 'loci.lake :reload)
(require 'loci.lake-test :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: `{:test 2 :pass 2 :fail 0 :error 0}`.

- [ ] **Step 6: Trim the DuckDB natives from slim builds**

`duckdb_jdbc` bundles four platform natives (81.5 MB compressed). `build.clj` already handles
exactly this shape. Add to the `foreign-natives` vector, following the comment style of its
neighbours:

```clojure
   ;; - DuckDB's JNI binding, as `libduckdb_java.so_<os>_<arch>` — four
   ;;   platforms, one file each, at the jar root.
   #"libduckdb_java\.so_(?!linux_amd64).*"
```

Verify the pattern actually matches what is in the jar before trusting it:

```bash
unzip -l ~/.m2/repository/org/duckdb/duckdb_jdbc/1.5.5.1/duckdb_jdbc-1.5.5.1.jar | grep -i duckdb_java
```

Expected: four entries. Confirm the negative lookahead keeps exactly the linux amd64 one, and
**write the actual filenames into the commit message** — a guessed pattern that matches
nothing silently ships a portable jar labelled slim.

- [ ] **Step 7: Sabotage — prove the tests bite**

Break `ensure-extension!` by removing the `LOAD ducklake` from the `catch` branch and the
initial `try` (leave only `INSTALL`). Re-run: `ducklake-is-attached` must FAIL on the `ATTACH`.
Restore. Re-run: passes. Paste the failure text into the task report.

- [ ] **Step 8: Commit**

```bash
git add deps.edn build.clj src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: a lake that opens, and knows where its bytes go"
```

---

## Task 2: The TableStore seam and today's behaviour behind it

**Files:**
- Create: `src/loci/tablestore.clj`
- Test: `test/loci/tablestore_test.clj`

The protocol comes first and in its own namespace, matching `loci.factlog` (protocol alone,
Datalevin implementation in `loci.dlv`). `InlineTable` — rows in the event, what exists
today — lives here as the parity reference and the thing tests use when they do not want a
lake, exactly as `PersistentStore` serves `loci.substrate`.

- [ ] **Step 1: Write the failing test**

Create `test/loci/tablestore_test.clj`:

```clojure
(ns loci.tablestore-test
  (:require [clojure.test :refer [deftest is]]
            [loci.tablestore :as tbl]))

(def rows [{:id 1 :name "a"} {:id 2 :name "b"} {:id 3 :name "c"}])

(deftest inline-round-trips
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= rows (tbl/rows ts h {})))
    (is (= 3 (:rows h)))
    (is (= [:id :name] (:cols h)))))

(deftest inline-pages
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= [{:id 2 :name "b"}] (tbl/rows ts h {:offset 1 :limit 1})))))

(deftest handle-is-recognisable
  (let [h (tbl/put-rows! (tbl/inline-table) "tbl:x" rows)]
    (is (tbl/handle? h))
    (is (not (tbl/handle? rows)))
    (is (not (tbl/handle? {:any :map})))))
```

- [ ] **Step 2: Run it to verify it fails**

```clojure
(require 'loci.tablestore-test :reload)
```

Expected: FAIL — namespace not found.

- [ ] **Step 3: Write the implementation**

Create `src/loci/tablestore.clj`:

```clojure
(ns loci.tablestore
  "The seam between an object that IS a table and wherever its rows actually
   live. The log stores a handle; this is what turns one back into rows.

   `InlineTable` keeps rows in the handle itself — which is what every loci
   table was before 2026-08-16 — and stays as the parity reference and as the
   store a test uses when it has nothing to say about DuckLake, the same role
   `substrate/PersistentStore` plays next to `dlv/DatalevinStore`."
  (:require [loci.substrate :as sub]))

(defprotocol TableStore
  (put-rows! [this id rows]
    "Land `rows` and return the handle to record in the event. Never overwrites:
     every call creates new storage, which is what lets two events never contend
     for one table.")
  (rows [this handle opts]
    "A page of rows for `handle`. `opts` may carry :offset and :limit.")
  (row-count [this handle] "How many rows `handle` names, without fetching them."))

(defn handle?
  "True for a handle this namespace produced. The `:store` key is explicit
   rather than inferred from shape, because a table's rows are a vector of maps
   and a handle is a map — close enough that a shape test would be one refactor
   from being wrong."
  [v]
  (and (map? v) (contains? #{:inline :lake} (:store v))))

(defn cols-of
  "The column order to preserve. Taken from the first row, which is what
   `save-table!` and `import-csv!` already treat as authoritative."
  [rows]
  (vec (keys (first rows))))

(defrecord InlineTable []
  TableStore
  (put-rows! [_ _ rs]
    (let [rs (vec rs)]
      {:store :inline :rows (count rs) :cols (cols-of rs) :data rs}))
  (rows [_ h {:keys [offset limit]}]
    (cond->> (:data h)
      offset (drop offset)
      limit  (take limit)
      true   vec))
  (row-count [_ h] (:rows h)))

(defn inline-table [] (->InlineTable))
```

- [ ] **Step 4: Run to verify they pass**

```clojure
(require 'loci.tablestore :reload)
(require 'loci.tablestore-test :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

Expected: `{:test 3 :pass 3 :fail 0 :error 0}`.

- [ ] **Step 5: Sabotage**

Change `handle?` to `(map? v)`. `handle-is-recognisable` must FAIL on the `{:any :map}`
assertion. Restore, re-run, passes.

- [ ] **Step 6: Commit**

```bash
git add src/loci/tablestore.clj test/loci/tablestore_test.clj
git commit -m "feat: a seam between a table and where its rows live"
```

---

## Task 3: DuckLakeTable — rows in, rows out, at a stated version

**Files:**
- Modify: `src/loci/lake.clj`
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing tests**

Append to `test/loci/lake_test.clj`:

```clojure
(require '[loci.tablestore :as tbl])

(def ^:private rows3 [{:id 1 :name "a"} {:id 2 :name "b"} {:id 3 :name "c"}])

(deftest lake-round-trips
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      (is (= :lake (:store h)))
      (is (= 3 (:rows h)))
      (is (= [:id :name] (:cols h)))
      (is (integer? (:snapshot h)))
      (is (= rows3 (tbl/rows lk h {}))))))

(deftest lake-preserves-column-order
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:zeta 1 :alpha 2}])]
      (is (= [:zeta :alpha] (:cols h)))
      (is (= [:zeta :alpha] (vec (keys (first (tbl/rows lk h {})))))))))

(deftest lake-pages
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      (is (= [{:id 2 :name "b"}] (tbl/rows lk h {:offset 1 :limit 1})))
      (is (= 3 (tbl/row-count lk h))))))

(deftest two-tables-never-share-storage
  (with-lake [lk]
    (let [a (tbl/put-rows! lk "tbl:x" [{:v 1}])
          b (tbl/put-rows! lk "tbl:x" [{:v 2}])]
      (is (not= (:table a) (:table b))
          "the same object id written twice must land in two lake tables")
      (is (= [{:v 1}] (tbl/rows lk a {})))
      (is (= [{:v 2}] (tbl/rows lk b {}))))))

(deftest a-read-names-its-version
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      ;; move the lake's write head PAST the recorded snapshot
      (lake/x! lk (str "UPDATE " (:table h) " SET name = 'CHANGED'"))
      (is (= rows3 (tbl/rows lk h {}))
          "a handle reads its own snapshot, not whatever the table became"))))
```

- [ ] **Step 2: Run to verify they fail**

```clojure
(require 'loci.lake :reload)
(require 'loci.lake-test :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: FAIL — `No implementation of method: :put-rows!` for `loci.lake.Lake`.

- [ ] **Step 3: Implement**

Add to `src/loci/lake.clj`. Requires `[clojure.data.json :as json]` and
`[loci.tablestore :as tbl]` in the `ns` form.

```clojure
(defn- table-name
  "A loci object id → a lake table name that is a legal SQL identifier and
   unique per call.

   Two parts on purpose. The sanitised id keeps the name debuggable — you can
   see which object `t_tbl_orders_1a2b3c4d` belongs to from a directory
   listing. The suffix makes it unique, because sanitising is lossy
   (`tbl:a-b` and `tbl:a_b` collide) and because writes never overwrite: the
   same object id written twice must land in two tables."
  [id n]
  (let [safe (-> (str id)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+|_+$" ""))
        h    (-> (java.security.MessageDigest/getInstance "SHA-256")
                 (.digest (.getBytes (str id "#" n) "UTF-8")))]
    (str "t_" (if (str/blank? safe) "tbl" safe) "_"
         (apply str (map #(format "%02x" %) (take 4 h))))))

(defn- ident
  "A column name as a quoted SQL identifier. Quoted, not interpolated bare:
   column names arrive from CSV headers and from models, and `sub/col-kw` has
   already made them safe for the LOG without making them safe for SQL."
  [k]
  (str "\"" (str/replace (name k) "\"" "\"\"") "\""))

(defn- select-cols [cols] (str/join ", " (map ident cols)))

(defn current-snapshot
  "The snapshot id the lake's write head is at."
  [lk]
  (:snapshot_id (first (q lk "SELECT max(snapshot_id) AS snapshot_id FROM ducklake_snapshots('lake')"))))

(defn- load-rows!
  "Bulk-load via newline-delimited JSON through a temp file. DuckDB infers the
   types, which is the same inference `read_csv` already gives `import-csv!`,
   and it is the path that stays reasonable at millions of rows — measured
   2.9 s for a 10M-row create."
  [lk table cols rs]
  (let [f (java.io.File/createTempFile "loci-rows-" ".ndjson")]
    (try
      (with-open [w (io/writer f)]
        (doseq [r rs] (json/write r w) (.write w "\n")))
      (x! lk (str "CREATE TABLE " table " AS SELECT " (select-cols cols)
                  " FROM read_json_auto('" (.getAbsolutePath f)
                  "', format='newline_delimited')"))
      (finally (.delete f)))))

(extend-type Lake
  tbl/TableStore
  (put-rows! [lk id rs]
    (let [rs    (vec rs)
          cols  (tbl/cols-of rs)
          table (table-name id (System/nanoTime))]
      (when (empty? rs)
        (throw (ex-info "refusing to store a table with no rows" {:id id})))
      (load-rows! lk table cols rs)
      {:store :lake :table table :snapshot (current-snapshot lk)
       :rows (count rs) :cols cols}))

  (rows [lk h {:keys [offset limit]}]
    (q lk (str "SELECT " (select-cols (:cols h))
               " FROM " (:table h) " AT (VERSION => " (:snapshot h) ")"
               (when limit  (str " LIMIT "  (long limit)))
               (when offset (str " OFFSET " (long offset))))))

  (row-count [_ h] (:rows h)))
```

Note `q` returns keys through `sub/col-kw`, so a column that arrives as `Total Revenue ($)`
comes back as `:total_revenue` — the same normalisation `parse-csv` already applies on the way
in, so a round trip is stable.

- [ ] **Step 4: Run to verify they pass**

```clojure
(require 'loci.tablestore :reload)   ; protocol FIRST — see "How to run things"
(require 'loci.lake :reload)
(require 'loci.lake-test :reload)
(clojure.test/run-tests 'loci.lake-test)
```

Expected: 7 tests, 0 failures.

- [ ] **Step 5: Sabotage — this is the important one**

Remove ` AT (VERSION => …)` from `rows`, leaving a bare `SELECT … FROM t`. `a-read-names-its-version`
must FAIL, reporting `name` as `"CHANGED"`. Restore, re-run, passes.

Then separately: change `table-name` to drop the hash suffix. `two-tables-never-share-storage`
must FAIL. Restore.

Report both failure outputs.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: rows into the lake, and back out at a stated version"
```

---

## Task 4: The lake singleton and boot wiring

**Files:**
- Modify: `src/loci/lake.clj`
- Modify: `src/loci/server.clj` (the `-main` boot path, next to `data-dir-problem`)
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(deftest lake-singleton-is-lazy
  (is (instance? clojure.lang.Delay lake/lake))
  (is (not (realized? lake/lake))
      "requiring loci.lake must open nothing — the rule the whole codebase keeps"))
```

**Read this before running it.** Dereferencing the singleton anywhere in your REPL session
makes this assertion fail for the rest of that session, because a realized delay stays
realized. If it fails, check `(realized? loci.lake/lake)` before assuming the code is wrong;
restart the nREPL if you have already forced it.

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `No such var: lake/lake`.

- [ ] **Step 3: Implement**

Add to `src/loci/lake.clj`, mirroring `dlv/store`:

```clojure
(defonce ^{:doc "The process's one lake at <data-dir>/lake.

   A delay, so requiring this namespace still opens nothing — the rule the
   whole codebase keeps, and the reason `loci.dlv/store` is shaped the same
   way. Tests rebind it with `with-redefs` and a delay of their own.

   One connection, because loci is single-writer: one process owns the store,
   and the three legitimate ways to reach it are the running app's HTTP API, a
   `loci.*` facade called in the server's own process, and direct access with
   the app stopped."}
  lake
  (delay (open-lake!)))
```

- [ ] **Step 4: Wire boot and shutdown**

In `src/loci/server.clj`'s `-main`, after the existing `data-dir-problem` check and next to
where the substrate store is opened, force the lake so a broken lake fails at boot with a
clear message rather than on the first table read:

```clojure
    ;; Force the lake at boot rather than on first use. 75 ms, measured against
    ;; a lake holding 10M rows — 0.5% of the dependency load already paid — and
    ;; it turns "tables are broken" into a startup error naming the directory.
    @lake/lake
```

Add a shutdown hook beside the existing one so the connection closes cleanly:

```clojure
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(when (realized? lake/lake) (lake/close! @lake/lake))))
```

Confirm by reading the surrounding code that this matches how the substrate store is already
closed; follow that shape rather than this sketch if they differ.

- [ ] **Step 5: Run the tests**

```clojure
(require 'loci.lake :reload)
(require 'loci.lake-test :reload)
(clojure.test/run-tests 'loci.lake-test)
```

- [ ] **Step 6: Sabotage**

Change the `defonce` to `(defonce lake (open-lake!))` — eager. `lake-singleton-is-lazy` must
FAIL on the `instance?` assertion. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/lake.clj src/loci/server.clj test/loci/lake_test.clj
git commit -m "feat: one lake per process, opened at boot and closed at exit"
```

---

## Task 5: restore-to! and the regression for the divergence the measurements found

**Files:**
- Modify: `src/loci/lake.clj`
- Test: `test/loci/lake_test.clj`

This task ships the fix for the bug the measurement gate found on 2026-08-16, together with
the test that reproduces it. Design decision 3 explains why it is not wired into a write path:
nothing in loci mutates a table in place, so the bug is latent. It is built now because it is
five lines and because rediscovering it later — after in-place mutation exists — means
discovering it as data loss.

- [ ] **Step 1: Write the failing test — reproduce the measured divergence exactly**

```clojure
(deftest a-write-after-undo-must-not-resurrect-the-undone-change
  ;; The exact scenario measured 2026-08-16. Without restore-to!, the sum is
  ;; 101200: the change at B is still in the table and comes back the moment
  ;; anything else is written. With it, the sum is 1400.
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:u" (mapv (fn [i] {:id i :v 1}) (range 1000)))
          t (:table h)
          sum #(:s (first (lake/q lk (str "SELECT sum(v) AS s FROM " t))))]
      (is (= 1000.0 (double (sum))))
      (lake/x! lk (str "UPDATE " t " SET v = 999 WHERE id < 100"))   ; snapshot B
      (is (= 100800.0 (double (sum))))
      ;; loci pops the event: the substrate points at the handle's snapshot again.
      ;; Reading is already correct...
      (is (= 1000.0 (double (reduce + (map :v (tbl/rows lk h {}))))))
      ;; ...but the write head is still at B, so it must be moved back.
      (lake/restore-to! lk t (:snapshot h))
      (lake/x! lk (str "UPDATE " t " SET v = 5 WHERE id >= 900"))    ; the write after undo
      (is (= 1400.0 (double (sum)))
          "the change at B must be gone, not merely unread"))))

(deftest restore-preserves-every-row
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:u" (mapv (fn [i] {:id i :v 1}) (range 1000)))
          t (:table h)]
      (lake/x! lk (str "DELETE FROM " t " WHERE id < 500"))
      (is (= 500 (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " t))))))
      (lake/restore-to! lk t (:snapshot h))
      (is (= 1000 (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " t)))))))))
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — `No such var: lake/restore-to!`.

- [ ] **Step 3: Implement**

```clojure
(defn restore-to!
  "Move `table`'s write head back to `snapshot`.

   Measured 2026-08-16, and this is the finding that most changed the design:
   DuckLake's `AT (VERSION => n)` is a READ, and a read does not move the write
   head. A substrate that merely re-points its handle after `undo!` diverges
   from the lake silently — the next write builds on the state the undo was
   supposed to remove, and the undone change comes back.

   There is no `ducklake_rollback`; all 26 `ducklake_*` functions were checked.
   A CREATE OR REPLACE from the old version is the native way to say it, and it
   is also the fast way: 182 ms at 10M rows against 520 ms for inverting the
   native change feed, and flat in the size of the edit (a 50% edit restored in
   211/245/260 ms across three runs). The change-feed approach was measured,
   was slower, had four change types to get right, and is not here."
  [lk table snapshot]
  (x! lk (str "CREATE OR REPLACE TABLE " table
              " AS SELECT * FROM " table " AT (VERSION => " snapshot ")"))
  :ok)
```

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Sabotage**

Replace the body of `restore-to!` with `:ok` (a no-op, which is exactly the "just re-point"
design the measurement disproved). `a-write-after-undo-must-not-resurrect-the-undone-change`
must FAIL reporting `101200.0`. Restore. Confirm `1400.0`.

This sabotage is the one that matters most in the plan — paste both numbers into the report.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: restore-to!, because time travel does not move the write head"
```

---

## Task 6: hydrate — readers get rows, and learn nothing

**Files:**
- Modify: `src/loci/tablestore.clj`
- Modify: `src/loci/server.clj:366` (`mold-payload`)
- Test: `test/loci/tablestore_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest hydrate-swaps-a-handle-for-rows
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)
        o  {:id "tbl:x" :kind :table :title "X" :value h}]
    (is (= rows (:value (tbl/hydrate ts o))))
    (is (= "X" (:title (tbl/hydrate ts o))) "everything else survives")))

(deftest hydrate-passes-through-what-is-not-a-handle
  (let [ts (tbl/inline-table)]
    (is (= rows (:value (tbl/hydrate ts {:kind :table :value rows})))
        "a legacy inline table must render unchanged")
    (is (= "hello" (:value (tbl/hydrate ts {:kind :doc :value "hello"}))))
    (is (nil? (tbl/hydrate ts nil)))))

(deftest hydrate-caps-a-large-table
  (let [ts   (tbl/inline-table)
        many (mapv (fn [i] {:id i}) (range 5000))
        o    (tbl/hydrate ts {:kind :table :value (tbl/put-rows! ts "tbl:big" many)})]
    (is (= tbl/mold-cap (count (:value o))))
    (is (= 5000 (:table/total o)) "the true count survives the cap")))
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

Add to `src/loci/tablestore.clj`:

```clojure
(def mold-cap
  "How many rows a molded table carries. The point of DuckLake is tables of
   millions of rows, and a payload that materialized all of them would move the
   problem from the substrate to the browser. The largest table in the substrate
   today is 1,000 rows, so this changes nothing about what currently renders."
  2000)

(defn hydrate
  "An object whose `:value` is a table handle → the same object with rows in
   its place, capped, plus `:table/total`.

   Everything downstream — `mold/mold`, `alternatives`, `viewspec/interpret`,
   `content.clj`'s predicates — receives rows exactly as it did before tables
   moved, which is the point: one call here instead of a handle branch in each
   of them. Anything that is not a handle passes through untouched, so a legacy
   inline table and a doc both survive."
  [ts o]
  (if (and o (handle? (:value o)))
    (let [h (:value o)]
      (assoc o :value       (rows ts h {:limit mold-cap})
               :table/total (row-count ts h)))
    o))
```

- [ ] **Step 4: Wire it into `mold-payload`**

In `src/loci/server.clj:366`, the binding is currently:

```clojure
  [st m id view]
  (let [o (sub/object st id)]
```

Change to:

```clojure
  [st m id view]
  (let [o (tbl/hydrate @lake/lake (sub/object st id))]
```

Add `[loci.tablestore :as tbl]` and `[loci.lake :as lake]` to `server.clj`'s `ns` requires.
Nothing else in `mold-payload` changes — the entity, flow, applet, view-spec and viewer
branches all read `(:value o)` and now get rows, as before.

- [ ] **Step 5: Stop the suites from opening the real lake**

Not optional, and it lands in the same commit as step 4. `mold-payload` now forces
`@lake/lake`, and Task 7 makes `save-table!` do the same — so from here on, any test that molds
or saves a table opens `<data-dir>/lake` **for real**. That is the same trap `mold-payload`'s
own docstring describes for `mem/memory`, and the existing answer is the one to copy: tests
rebind the singleton.

Create `test/loci/lake_fixture.clj`:

```clojure
(ns loci.lake-fixture
  "A temp lake for any suite whose code path reaches `@lake/lake`.

   Without it, `mold-payload` and `save-table!` open <data-dir>/lake during the
   test run — the same way forcing `mem/memory` in a test with nothing to say
   about memory opens the real memory. `data/` is test data and writing to it
   would not be an incident, but two suites sharing one lake interfere with
   each other, which is reason enough."
  (:require [clojure.java.io :as io]
            [loci.lake :as lake]))

(defn with-temp-lake
  "A clojure.test fixture. Use with `use-fixtures :once`."
  [f]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/loci-lake-test-" (System/nanoTime))
        lk  (lake/open-lake! dir)]
    (try
      (with-redefs [lake/lake (delay lk)] (f))
      (finally
        (lake/close! lk)
        (letfn [(rm [x] (let [x (io/file x)]
                          (when (.isDirectory x) (run! rm (.listFiles x)))
                          (.delete x)))]
          (rm dir))))))
```

Add to `test/loci/server_test.clj` and `test/loci/tools_test.clj` (and `corpus_test.clj` if it
molds or saves a table):

```clojure
(use-fixtures :once loci.lake-fixture/with-temp-lake)
```

Then verify it actually took:

```bash
ls data/lake 2>&1   # expected: No such file or directory
```

If `data/lake` exists after a run, a suite is missing the fixture — find which one rather than
deleting the directory and moving on.

- [ ] **Step 6: Run to verify they pass**

```clojure
(require 'loci.tablestore :reload)
(require 'loci.lake :reload)
(require 'loci.tablestore-test :reload)
(clojure.test/run-tests 'loci.tablestore-test)
```

Then the server suite, which must still be green:

```clojure
(require 'loci.server :reload)
(require 'loci.server-test :reload)
(clojure.test/run-tests 'loci.server-test)
```

- [ ] **Step 7: Sabotage**

Make `hydrate` ignore `:limit` (pass `{}` to `rows`). `hydrate-caps-a-large-table` must FAIL
with 5000. Restore.

Then a second sabotage for the fixture, because a fixture that is not doing anything is the
kind of thing that passes quietly: delete the `use-fixtures` line from `server_test.clj`, run
that suite, and confirm `data/lake` appears. Restore the line, delete `data/lake`, re-run, and
confirm it does not.

- [ ] **Step 8: Commit**

```bash
git add src/loci/tablestore.clj src/loci/server.clj test/loci/tablestore_test.clj test/loci/lake_fixture.clj test/loci/server_test.clj test/loci/tools_test.clj
git commit -m "feat: hydrate — one call, and every reader keeps reading rows"
```

---

## Task 6b: the other eleven readers

**Files:**
- Modify: `src/loci/tools.clj:105` (`do-list`), `:111` (`do-query`)
- Modify: `src/loci/server.clj:1062`, `:1085`, `:1124`, `:1188`, `:1259`, `:1315`, `:1758`, `:2015`, `:2045`, `:2118`, `:1743`
- Test: `test/loci/server_test.clj`, `test/loci/tools_test.clj`

**Added 2026-08-16, after Task 6's review, and this task exists because this plan was wrong.**
Line 14 of this plan says "Readers are unchanged: one `hydrate` call at the top of
`mold-payload`". The first half is right and the second is false. `mold-payload` is one of
twelve places where an object leaves the substrate and its `:value` is then read as rows.
Task 6 fixed one. Task 7 lands the handles. Between those two commits the other eleven break,
and this task goes in between.

Measured against a lake-shaped handle committed into a fresh store, before any of this was
written:

- `tools.clj:105` `do-list` (`list_tables`) — `(keys (first (:value o)))` on a handle map gives
  `ClassCastException: class clojure.lang.Keyword cannot be cast to class java.util.Map$Entry`.
  The agent's table catalog dies.
- `tools.clj:111` `do-query` (`query_table`) — **no error, wrong data.** `vs/interpret` walks the
  handle map and returns `[[:store :lake] [:table "t_…"] [:snapshot 1] [:rows 3] [:cols […]]]`
  as the table's rows. The model is told these are the contents, and with `:save_as` it commits
  them as a new table. This is the worst site in the list, because it is silent.
- `server.clj:1188` `table-rows` — the `(sequential? rows)` guard rejects a handle, so
  `fns-payload` (`:1193`), the ƒ preview (`:1222`) and `fn-apply!` (`:1233`) all return
  `{:error "functions work on tables"}`. The ƒ palette goes dark for every table.
- `server.clj:1062` `describe!`, `:1085` `make-view!`, `:1124` `compute-clj!`, `:1315` `do!` —
  the same guard, inline, four times: `{:error "… works on tables"}`.
- `server.clj:1259` `recompute` — a handle is non-nil and non-sequential, so `rerun!` silently
  skips: measured `{:refreshed [] :skipped []}`.
- `server.clj:1758` (ask/chat), `:2015` (`delegate!`), `:2045`, `:2118`, `:1743` — the workspace
  digest and the "cols: …; N rows" catalog fragments. `ClassCastException` at the catalog, and
  `obj-digest` returns `""` for a table, so it vanishes from the agent's context silently.

**The fix is one edit, twelve times: hydrate at the boundary and change nothing downstream.**
Do not add a handle branch anywhere. Do not change a predicate. Once the object is hydrated its
`:value` is a vector of maps again and every guard, predicate and viewer behaves exactly as it
did before tables moved — which is what "readers are unchanged" was supposed to mean.

In particular **leave `server.clj:1763`'s structural predicate alone**:

```clojure
          texty   (remove #(and (sequential? (:value %)) (seq (:value %)) (every? map? (:value %))
                                (not (every? :block (:value %)))) objs)
```

It is tempting to "fix" it to `(remove #(= :table (:kind %)) objs)` to match `:2018` and `:2049`.
Don't. It deliberately keeps `:block` reports, and `:kind`-based filtering would change which
legacy objects survive. Hydrate `objs` first and this predicate is correct again unchanged.

- [ ] **Step 1: Write the failing tests**

Add to `test/loci/tools_test.clj`:

```clojure
(deftest list-tables-reads-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (let [r (first (tools/dispatch st "list_tables" {}))]
      (is (= ["a" "b"] (:columns r)) "columns come from the rows, not the handle's keys")
      (is (= 2 (:rows r))))))

(deftest query-table-reads-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (is (= [{:a 1 :b "p"} {:a 2 :b "q"}]
           (:rows (tools/dispatch st "query_table" {:table_id "tbl:x"})))
        "the handle's own keys must never be returned as rows")))
```

Add to `test/loci/server_test.clj`:

```clojure
(deftest the-fn-palette-sees-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (let [r (srv/fns-payload st "tbl:x")]
      (is (nil? (:error r)) "a handle-backed table must not read as 'not a table'")
      (is (seq (:fns r))))))

(deftest the-workspace-digest-still-describes-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "Sales" :value h}})
    (let [c (:context (srv/agent-context st nil))]
      (is (re-find #"tbl:x" c) "the table must appear in the agent's context at all")
      (is (re-find #"2 rows" c) "and with its true row count"))))
```

`srv/agent-context` is the function at `server.clj:2045` — read it and use its real name and
arity; if it is private, reach it with `#'`. Both suites already have the temp-lake fixture from
Task 6, so `@lake/lake` in a test is the fixture's lake, not `data/lake`.

- [ ] **Step 2: Run to verify they fail**

Expected: `ClassCastException` from `list_tables`, the handle's own keys back from
`query_table`, `{:error "functions work on tables"}` from `fns-payload`, and no `tbl:x` in the
digest. Paste all four.

- [ ] **Step 3: Convert the single-object sites**

Each is the same edit. `server.clj:1188`:

```clojure
(defn- table-rows [st id]
  (let [rows (:value (tbl/hydrate @lake/lake (sub/object st id)))]
    (when (and (sequential? rows) (seq rows) (every? map? rows)) rows)))
```

`server.clj:1062` (`describe!`), `:1085` (`make-view!`), `:1124` (`compute-clj!`), `:1315`
(`do!`) — all four currently read:

```clojure
    (let [o (sub/object st id) rows (:value o)]
```

and all four become:

```clojure
    (let [o (tbl/hydrate @lake/lake (sub/object st id)) rows (:value o)]
```

`server.clj:1259` (`recompute`):

```clojure
        rows   (or (get working src-id)
                   (:value (tbl/hydrate @lake/lake (sub/object st src-id))))
```

`tools.clj:111` (`do-query`):

```clojure
  (if-let [o (some->> (sub/object st table_id) (tbl/hydrate @lake/lake))]
```

- [ ] **Step 4: Convert the collection sites**

Hydrate once, where the object list is assembled, so everything downstream — `texty`, `tbls`,
`catalog`, `obj-digest` — sees rows.

`server.clj:1758` (ask/chat) and `:2045`: the `objs` binding gains a hydrate:

```clojure
          objs    (mapv #(tbl/hydrate @lake/lake %)
                        (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs))
```

`server.clj:2015` (`delegate!`): the same on `members`.

`server.clj:1743` and `:2118`: `(map obj-digest …)` becomes
`(map #(obj-digest (tbl/hydrate @lake/lake %)) …)`.

`tools.clj:105` (`do-list`):

```clojure
(defn- do-list [st _]
  (mapv (fn [o] (let [o (tbl/hydrate @lake/lake o)]
                  {:id (:id o) :title (:title o)
                   :columns (mapv name (keys (first (:value o))))
                   :rows (or (:table/total o) (count (:value o)))}))
        (table-objs st)))
```

Leave `table-objs` itself alone — `:116` and `:154` call it only to count ids, and hydrating
there would fetch every table's rows to produce a number.

- [ ] **Step 5: Say the true count, not the capped one**

`hydrate` caps at `mold-cap`, so `(count (:value o))` after hydration is a lie for a table over
2,000 rows. `:table/total` carries the truth. The three catalog fragments — `server.clj:1769`,
`:2023`, `:2054` — each end `"; " (count (:value o)) " rows)"`. All three become:

```clojure
                                                   "; " (or (:table/total o) (count (:value o))) " rows)")) tbls))
```

`do-list` already got this in step 4. Nothing else counts rows off a hydrated object.

Add `[loci.tablestore :as tbl]` and `[loci.lake :as lake]` to `tools.clj`'s requires;
`server.clj` has both already.

- [ ] **Step 6: Prove no site was missed**

```bash
grep -n "(:value o)\|(:value %)\|(:value (sub/object" src/loci/server.clj src/loci/tools.clj
```

Every hit must be either (a) inside a `let` whose object came from `tbl/hydrate`, (b) reading a
`:viewspec`/`:applet`/`:fn`/`:flow`/`:space` object, whose `:value` is a map and never a table,
or (c) `mold-payload`, already done in Task 6. Walk the list and say which bucket each hit is
in — this is the step that catches the twelfth site, and the reason this task exists is that
the same walk was not done when the plan was written.

- [ ] **Step 7: Run the suites**

```clojure
(doseq [n '[loci.tablestore loci.lake loci.tools loci.server]] (require n :reload))
(clojure.test/run-tests 'loci.tools-test 'loci.server-test 'loci.tablestore-test 'loci.lake-test)
```

All green. `loci.server-test` baseline is 205 tests / 761 assertions.

- [ ] **Step 8: Sabotage**

Revert `table-rows` alone to `(:value (sub/object st id))`.
`the-fn-palette-sees-a-handle-backed-table` must FAIL with
`{:error "functions work on tables"}`. Restore.

Then revert `do-query` alone. `query-table-reads-a-handle-backed-table` must FAIL showing the
handle's own keys as rows — paste the actual returned vector, because that value is what a
model would have been told was the user's data.

- [ ] **Step 9: Commit**

```bash
git add src/loci/tools.clj src/loci/server.clj test/loci/tools_test.clj test/loci/server_test.clj
git commit -m "fix: every reader hydrates, not just mold-payload"
```

---

## Task 6c: the fourteenth reader, and a lake that opens when it is needed

**Files:**
- Modify: `src/loci/tablestore.clj` (`hydrate`)
- Modify: `src/loci/chunks.clj:302` (`all-chunks`)
- Modify: `src/loci/server.clj`, `src/loci/tools.clj` — every `@lake/lake` → `lake/lake`
- Test: `test/loci/chunks_test.clj`, `test/loci/tablestore_test.clj`, `test/loci/server_test.clj`

**Added 2026-08-16, after Task 6b's review.** Three findings, and the first two compose — fixing
either alone makes the other worse.

- [ ] **Step 1: Write the failing tests**

`test/loci/tablestore_test.clj` — hydrate must not open a lake it has no use for:

```clojure
(deftest hydrate-opens-nothing-when-there-is-no-handle
  (let [boom (delay (throw (ex-info "the lake was opened for a table that never left the log" {})))]
    (is (= [{:a 1}] (:value (tbl/hydrate boom {:kind :table :value [{:a 1}]}))))
    (is (= "hi"     (:value (tbl/hydrate boom {:kind :doc   :value "hi"}))))
    (is (nil? (tbl/hydrate boom nil)))
    (is (not (realized? boom))
        "a store with no handle-backed table must never force the lake")))

(deftest hydrate-still-takes-a-store-directly
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= rows (:value (tbl/hydrate ts {:kind :table :value h})))
        "passing the store itself, not a delay, must keep working")))
```

`test/loci/chunks_test.clj` — the synopsis must not depend on where the rows live:

```clojure
(deftest a-table-chunks-the-same-whether-its-rows-are-inline-or-in-the-lake
  ;; The synopsis is what LEAP searches to answer "which table holds this?".
  ;; A handle fails `rows?`, so before this fix a lake-backed table chunked to
  ;; its bare title — columns and sample values gone, silently. And because
  ;; :hash covers the chunk text, migrating a table would retire its synopsis
  ;; chunk and re-embed a weaker one: a model round trip per table, for nothing.
  (let [inline {:id "tbl:x" :kind :table :title "Sales"
                :value [{:region "EMEA" :rev 10} {:region "APAC" :rev 20}]}]
    (lf/with-temp-lake
      (fn []
        (let [h      (tbl/put-rows! @lake/lake "tbl:x" (:value inline))
              handle (assoc inline :value h)
              st     (sub/fresh-store)]
          (sub/commit! st {:op :put :id "tbl:x" :value handle})
          (is (= (map :text (ch/chunks-of inline))
                 (map :text (ch/all-chunks st)))
              "same text — so migrating a table re-embeds nothing")
          (is (= (map :hash (ch/chunks-of inline))
                 (map :hash (ch/all-chunks st)))
              "and therefore the same hashes"))))))
```

`test/loci/server_test.clj` — pin `table-shaped?` with the case that actually bites. Task 6b
converted it on a rationale that does not reproduce: with a single-step flow, `resolve-ref` has
already turned `"$0"` into the step's `:out`, and `resolve-table-ref`'s `or` falls back to that
resolved id either way. The failure needs a *non-table earlier step*:

```clojure
(deftest a-compute-step-resolves-past-a-doc-to-the-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "doc:n" :value {:id "doc:n" :kind :doc :title "N" :value "text"}})
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (is (= "tbl:x"
           (#'srv/resolve-table-ref st {:steps [{:out "doc:n"} {:out "tbl:x"}]} 0))
        "the scan must skip the doc and find the table, wherever its rows live")))
```

Read `resolve-table-ref` at `server.clj:2293` and match its real arity and argument shape — the
call above is the review's reproduction, not necessarily its signature. The point the test must
pin is: **an earlier non-table step, a later handle-backed table, and the table wins.**

- [ ] **Step 2: Run to verify they fail**

Expected: the `boom` delay throws (`hydrate` forces unconditionally today); the chunk texts
differ, `["Sales\ncolumns: region, rev\nregion: EMEA, APAC\nrev: 10, 20"]` against `["Sales"]`;
and `resolve-table-ref` returns `"doc:n"` with `table-shaped?` reverted.

- [ ] **Step 3: Make `hydrate` force lazily**

`src/loci/tablestore.clj`. Only the first line of the body changes, plus the docstring:

```clojure
  [ts o]
  (if (and o (handle? (:value o)))
    (let [ts (force ts)
          h  (:value o)]
      (assoc o :value       (rows ts h {:limit mold-cap})
               :table/total (row-count ts h)))
    o))
```

`force` is identity on anything that is not a delay, so a caller may pass either the store or a
delay of one, and `hydrate-still-takes-a-store-directly` holds that open.

Add to the docstring:

> Takes the store **or a delay of one**, and forces it only when there is
> actually a handle to resolve. Fifteen call sites reach this function, most of
> them on paths — `list_tables`, the ƒ palette, the chunk sweep — that run on
> workspaces holding no lake-backed table at all. Forcing eagerly would open
> `<data-dir>/lake` to answer questions about rows that never left the log,
> which is the trap `mold-payload`'s own docstring describes for `mem/memory`.

- [ ] **Step 4: Pass the delay, not its value**

Every `@lake/lake` in a *reader* becomes `lake/lake`. In `src/loci/server.clj`: `:395`
(`mold-payload`), `:1062`, `:1085`, `:1124`, `:1188`, `:1259`, `:1316`, `:1743`, `:1763`,
`:2018`, `:2051`, `:2126`, `:2247`. In `src/loci/tools.clj`: `:105`, `:112`.

```bash
grep -n "@lake/lake" src/loci/*.clj
```

Expected after this step: **one** hit, `server.clj`'s `-main` boot force, which is meant to be
eager. Everything else passes the delay.

- [ ] **Step 5: Hydrate at the chunk seam**

`src/loci/chunks.clj:302`. `chunks-of` is documented at the top of that namespace as "a **pure
function of one object**. No store, no file, no embedder, no clock", and that contract is what
makes chunks rebuildable — do not hydrate inside it. `all-chunks` is the one function there
that already takes `st`:

```clojure
(defn all-chunks
  "Every chunk the substrate currently implies. Reads the store; writes nothing
   to it, ever.

   Hydrates on the way in, so a table's synopsis is built from its rows whether
   they sit in the log or in the lake. `chunks-of` stays pure — it never learns
   that a lake exists — and the synopsis text comes out byte-identical either
   way, which is what keeps Task 9's migration from retiring and re-embedding
   every table's chunk."
  [st]
  (chunks-in (map #(tbl/hydrate lake/lake %) (vals (sub/objects st)))))
```

Add `[loci.tablestore :as tbl]` and `[loci.lake :as lake]` to `chunks.clj`'s requires. Check
first for a require cycle — if `loci.lake` or `loci.tablestore` reaches `loci.chunks`, stop and
report rather than restructuring.

- [ ] **Step 6: Convert `table-shaped?` on the rationale that reproduces**

Task 6b already hydrated `server.clj:2247`. Leave the code; fix the comment if it states the
single-step rationale, and let the new test carry the real one.

- [ ] **Step 7: Run the suites**

```clojure
(doseq [n '[loci.tablestore loci.lake loci.chunks loci.tools loci.server]] (require n :reload))
(clojure.test/run-tests 'loci.chunks-test 'loci.tools-test 'loci.server-test
                        'loci.tablestore-test 'loci.lake-test)
```

Baselines: `server-test` 207/765, `tools-test` 8/18, `tablestore-test` 6/14, `lake-test` 11/25.

- [ ] **Step 8: Prove the lake stays shut**

The measurement that motivates this task. Install a delay that throws, seed a store with
**only inline tables and docs**, and run every namespace:

```clojure
(with-redefs [loci.lake/lake (delay (throw (ex-info "SOMETHING FORCED THE REAL LAKE" {})))]
  (clojure.test/run-tests 'loci.corpus-test 'loci.agent-test 'loci.leap-deep-test
                          'loci.memory-dlv-test 'loci.repair-test 'loci.chunks-test))
```

Before Task 6c this probe made `list_tables`, `query_table`, `fns-payload` and `agent-ctx` throw
on a workspace with zero handles. After it, all six namespaces must pass. Report both.

- [ ] **Step 9: Sabotage**

Remove `force` from `hydrate`, restoring the eager deref.
`hydrate-opens-nothing-when-there-is-no-handle` must FAIL on the `boom` delay. Restore.

Revert `all-chunks` to `(chunks-in (vals (sub/objects st)))`.
`a-table-chunks-the-same-whether-its-rows-are-inline-or-in-the-lake` must FAIL showing `["Sales"]`
against the full synopsis — paste both strings, because the difference between them is what LEAP
would have lost.

- [ ] **Step 10: Commit**

```bash
git add src/loci/tablestore.clj src/loci/chunks.clj src/loci/server.clj src/loci/tools.clj test/
git commit -m "fix: chunk a table by its rows, and open the lake only for a handle"
```

---

## Task 6d: the round trip must return the same columns it was given

**Files:**
- Modify: `src/loci/lake.clj` (`rows`)
- Modify: `src/loci/chunks.clj` (`all-chunks` docstring)
- Modify: `src/loci/server.clj:573-576` (a measurement that is now stale by two orders of magnitude)
- Test: `test/loci/lake_test.clj`, `test/loci/chunks_test.clj`

**Added 2026-08-16, from Task 6c's review, and this is a correctness bug in Task 3's code —
not in 6c.** It is here because it must be fixed before Task 7 lands handles and Task 9
migrates the 31 real tables.

`lake/q` derives its result keywords with `(sub/col-kw (.getColumnLabel md …))`. `col-kw` passes
a **keyword** through when it already reads back cleanly, which is why `:tenure-band` survives
in the log — but `getColumnLabel` returns a **String**, and the string path lowercases and
squashes `[^a-z0-9]+` to `_`. So a table put into the lake and read back has different column
names than it went in with.

Measured against the live `data/` substrate, all 31 tables through a temp lake: **7 diverge.**

- `tbl:revenue` — `tenure-band` → `tenure_band`
- `tbl:opps` — `close-month` → `close_month`
- `tbl:extract-14-588` — `Category, Property, Mercury_Venus_Earth_Mars` → all lowercased
- `tbl:derived-2`, `tbl:extract-27-7904`, `tbl:extract-10-2125` — uppercase and `_AU` mangled
- `tbl:extract-20-8413` — a `Long`+`String` column comes back JSON-quoted (a *type* issue, not a
  name issue; see the type-fidelity note in "What this plan does not answer" — expect this one
  to still diverge after this task)

**The consequence that matters most is not the chunk text.** `content.clj:256` picks a series
column from `#{:tenure-band :dept :channel :region :stage}` and `content.clj:320` gates the
churn-cohort viewer on `(contains? (first x) :tenure-band)`. `tbl:revenue` has that column.
Migrate it and the mold silently stops matching — a viewer disappears from a table with no
error anywhere.

There is also a latent collision: two columns `:a-b` and `:a_b` in one table both map to
`:a_b`, so one silently overwrites the other in every row map.

- [ ] **Step 1: Write the failing tests**

`test/loci/lake_test.clj`:

```clojure
(deftest a-round-trip-returns-the-columns-it-was-given
  ;; `q` names result columns by running the JDBC label through `sub/col-kw`,
  ;; which lowercases and squashes anything outside [a-z0-9]. `col-kw` leaves a
  ;; readable *keyword* alone, so `:tenure-band` survives in the log and is
  ;; renamed only on the way back out of the lake. Measured over the 31 live
  ;; tables: 7 came back different, and `tbl:revenue`'s `:tenure-band` is the
  ;; one `content.clj`'s churn-cohort viewer keys on by name.
  (with-lake [lk]
    (let [rs [{:tenure-band "0-6m" :Category "A" :rev_AU 1}]
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= [:tenure-band :Category :rev_AU] (:cols h)))
      (is (= rs (tbl/rows lk h {}))
          "the rows that come back must be the rows that went in"))))

(deftest two-columns-that-differ-only-by-punctuation-stay-two-columns
  (with-lake [lk]
    (let [rs [{:a-b 1 :a_b 2}]
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= 2 (count (:cols h))))
      (is (= rs (tbl/rows lk h {}))
          "collapsing both to :a_b would silently drop one column"))))
```

`test/loci/chunks_test.clj` — encode the measurement Task 6c made by hand and left un-pinned.
`all-chunks` is the only hydrate site on a timer, so it is the one that most needs this:

```clojure
(deftest all-chunks-opens-no-lake-for-a-store-with-no-handle
  (let [boom (delay (throw (ex-info "the sweep opened the lake for tables that never left the log" {})))
        st   (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "S"
                                                  :value [{:a 1} {:a 2}]}})
    (with-redefs [lake/lake boom]
      (is (seq (ch/all-chunks st)))
      (is (not (realized? boom))
          "the 15-second sweep must not open a lake to chunk inline tables"))))
```

- [ ] **Step 2: Run to verify they fail**

Expected: `{:tenure_band "0-6m", :category "A", :rev_au 1}` against the input, and one column
where there should be two. Paste both.

- [ ] **Step 3: Implement**

`src/loci/lake.clj`. `q` stays as it is — it is the general query helper and deriving keywords
from labels is right when nobody knows better. `rows` **does** know better: it selected
`(:cols h)` itself, in that order, so it can name the results rather than re-deriving them.

```clojure
(defn- q-as
  "Like `q`, but names the result columns instead of deriving them from the
   result set's labels.

   `q` runs each JDBC label through `sub/col-kw`, which is right when the caller
   has no idea what the columns are. A handle does: it recorded `:cols` when the
   rows were written, and the SELECT names them in that order. Deriving them
   again would be lossy — `col-kw` leaves a readable keyword alone but
   lowercases and squashes a *string*, so `:tenure-band` goes in and
   `:tenure_band` comes back, and `:a-b` and `:a_b` come back as one column."
  [lk sql ks]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (loop [acc (transient [])]
      (if (.next rs)
        (recur (conj! acc (into {} (map-indexed (fn [i k] [k (.getObject rs (inc i))]) ks))))
        (persistent! acc)))))
```

and `rows` in the `extend-type` block becomes:

```clojure
  (rows [lk h {:keys [offset limit]}]
    (q-as lk (str "SELECT " (select-cols (:cols h))
                  " FROM " (:table h) " AT (VERSION => " (long (:snapshot h)) ")"
                  (when limit  (str " LIMIT "  (long limit)))
                  (when offset (str " OFFSET " (long offset))))
          (:cols h)))
```

`q` and `q-as` now share their shape; if you can factor the common loop without making either
harder to read, do — but do not make `q` take an optional key vector, because the two callers
want opposite defaults and an optional argument would hide which one is in play.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Re-measure the live corpus**

The point of the fix. Run all 31 tables in `data/` through a temp lake and compare the synopsis
text inline against handle-backed, exactly as Task 6c's review did:

```clojure
;; a temp lake, `ch/chunks-of` on the inline object vs `ch/all-chunks` on a
;; store holding the handle — compare :text
```

Expected: **30 of 31 identical**, with `tbl:extract-20-8413` still divergent because its
`Long`+`String` column comes back as VARCHAR from `read_json_auto`. Report the actual number. If
more than one still diverges, name each and say why — that is a finding, not a failure of this
task.

`data/` is test data; read it freely. Do not write to it — a temp lake and a fresh store are all
this needs.

- [ ] **Step 6: Correct the `all-chunks` docstring**

`chunks.clj:306-309` currently promises the synopsis "comes out byte-identical either way,
which is what keeps Task 9's migration from retiring and re-embedding every table's chunk."
After step 3 that is true for every table whose columns are single-typed — but **not**
unconditionally, and the docstring should say which conditions rather than make a promise:

> Byte-identical for a table whose columns hold one type each and whose rows
> number `mold-cap` or fewer — which is all 31 in the substrate today, bar one
> with a mixed `Long`/`String` column that `read_json_auto` widens to VARCHAR.
> Past `mold-cap` the synopsis samples only the first 2,000 rows, so a table
> sorted such that a column's variety begins later will chunk differently than
> it did inline. Both are re-embeddings, not losses; the point of getting this
> close is that Task 9's migration re-embeds almost nothing.

- [ ] **Step 7: Update the stale measurement**

`server.clj:573-576` records, of the `?deep=1` LEAP path: *"It re-chunks and re-hashes the whole
substrate to answer: measured 4.2 ms over 101 chunks / 82 events, which is affordable HERE and
nowhere else."* That was true when `all-chunks` touched no store. It is not now.

Measured 2026-08-16, 29 tables × 1,000 rows in a temp DuckLake, median of 7: `all-chunks`
**790 ms** handle-backed against **3.1 ms** for the same tables inline and **0.33 ms**
pre-hydration — about 27 ms per lake-backed table, one versioned SELECT each, bounded by
`mold-cap` (a single 100k-row table costs 41 ms).

Rewrite the comment with the new number and keep its point: it was "affordable HERE and nowhere
else", and it is now considerably less affordable here. Do not change the behaviour — that is
the open question recorded in the plan, not this task's job.

- [ ] **Step 8: Sabotage**

Revert `rows` to call `q`. `a-round-trip-returns-the-columns-it-was-given` must FAIL showing
`:tenure_band`; `two-columns-that-differ-only-by-punctuation-stay-two-columns` must FAIL with
one column. Paste both.

Then revert `all-chunks` to pass `@lake/lake`. `all-chunks-opens-no-lake-for-a-store-with-no-handle`
must FAIL on `realized?`. Restore.

- [ ] **Step 9: Commit**

```bash
git add src/loci/lake.clj src/loci/chunks.clj src/loci/server.clj test/
git commit -m "fix: a table comes back with the columns it went in with"
```

---

## Task 6e: a handle that survives the log, and a table that keeps every column

**Files:**
- Modify: `src/loci/tablestore.clj` (`cols-of`, `put-rows!` on both implementations)
- Test: `test/loci/tablestore_test.clj`, `test/loci/lake_test.clj`

**Added 2026-08-16, from Task 6d's implementer.** Two defects in Task 2/3's code, both latent
today and both written into the log the moment Task 7 lands. The last task before the writers.

**Task 6f made the first of these worse, so this task now repairs two paths rather than one.**
`synopsis` takes its columns from `(:cols h)`, where it previously took the union of keys over
the first 50 rows. Measured on `[{:a 1} {:a 2 :b 9} {:a 3 :c 7}]`, the synopsis went from
`"columns: a, b, c\na: 1, 2, 3\nb: 9\nc: 7"` to `"columns: a\na: 1, 2, 3"` — columns `b` and `c`
vanish from what semantic search can find. No live table is ragged, so the exposure between 6f
and this task is empty, and the fix below is a union over **all** rows rather than the old
50-row window, so it improves on both.

**1. `cols-of` reads the first row only** (`tablestore.clj:27-31`), so a key that first appears
in a later row is dropped — by the lake, silently, while `InlineTable` keeps it:

```
{:cols [:a], :lake [{:a 1} {:a 2}], :inline [{:a 1} {:a 2, :b 9}]}
```

None of the 31 live tables is ragged, but `compute-clj!` and the ƒ palette run arbitrary
Clojure that returns rows, so nothing prevents one. Task 9 would migrate it and lose the column.

**2. `:cols` is the first column keyword to travel through the log as a *value*, and
`safe-event`'s guard does not cover it.** `readable?` (`substrate.clj:44`) compares *printed
strings*. A column keyword containing a space makes `:cols` print as `[:foo bar]`, which reads
back as a **two-element** vector and re-prints identically — so the guard passes while the value
changes:

```clojure
{:printed "{:value {:cols [:foo bar]}}"
 :string-round-trips? true
 :value-round-trips?  false}
```

`normalize-keys` only `col-kw`s map *keys*. Until now every column keyword was a key, so this
never mattered.

The fix for both is in `put-rows!`, and it is the one `save-table!` (`tools.clj:148-152`)
already applies to its own rows: run every column keyword through `sub/col-kw`. That leaves
`:tenure-band` alone — `col-kw` passes a readable keyword through untouched, which is what Task
6d just spent a commit preserving — and normalizes only what the log cannot hold.

- [ ] **Step 1: Write the failing tests**

`test/loci/tablestore_test.clj`:

```clojure
(deftest a-ragged-table-keeps-every-column
  (doseq [ts [(tbl/inline-table)]]
    (let [h (tbl/put-rows! ts "tbl:x" [{:a 1} {:a 2 :b 9}])]
      (is (= [:a :b] (:cols h))
          "a column that first appears in row 2 is still a column"))))

(deftest a-column-keyword-the-log-cannot-hold-is-normalised
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{(keyword "foo bar") 1 :ok 2}])]
    (is (= [:foo_bar :ok] (:cols h)))
    (is (= [{:foo_bar 1 :ok 2}] (tbl/rows ts h {}))
        "the rows must agree with the columns")
    (is (= (:cols h) (:cols (clojure.edn/read-string (pr-str h))))
        "a handle must survive the log it is about to be written to")))

(deftest a-readable-column-keyword-is-left-alone
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:tenure-band "0-6m" :Category "A"}])]
    (is (= [:tenure-band :Category] (:cols h))
        "col-kw passes a readable keyword through — Task 6d's whole point")))
```

`test/loci/lake_test.clj` — the same two properties against the real store, because the lake is
where the column list becomes SQL:

```clojure
(deftest a-ragged-table-keeps-every-column-in-the-lake
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:a 1} {:a 2 :b 9}])]
      (is (= [:a :b] (:cols h)))
      (is (= [{:a 1 :b nil} {:a 2 :b 9}] (tbl/rows lk h {}))
          "a row that lacked the column reads back with nil, not without the key"))))

(deftest a-column-keyword-the-log-cannot-hold-is-normalised-in-the-lake
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{(keyword "foo bar") 1}])]
      (is (= [:foo_bar] (:cols h)))
      (is (= [{:foo_bar 1}] (tbl/rows lk h {}))))))
```

Note the asymmetry the first lake test pins: a missing key comes back as `nil` rather than
absent, because a columnar store has no way to say "this row had no such column". That is a
shape change, not a loss, and it is the honest trade against dropping the column entirely.

- [ ] **Step 2: Run to verify they fail**

Expected: `[:a]` where `[:a :b]` belongs, and `[:foo bar]`-shaped breakage on the handle. Paste
both.

- [ ] **Step 3: Implement**

`src/loci/tablestore.clj`. Add `[loci.substrate :as sub]` to the requires — `loci.substrate`
does not require `loci.tablestore`, so there is no cycle; confirm before editing.

```clojure
(defn cols-of
  "The column order to preserve: every key any row has, in the order first seen.

   First-seen order, not sorted, because the first row is what `save-table!` and
   `import-csv!` treat as authoritative and a table that renders with its
   columns rearranged reads as a different table. Every row rather than the
   first, because a key that appears only in row 2 is still a column, and the
   lake would otherwise drop it silently while `InlineTable` kept it.

   Each key goes through `sub/col-kw`, which leaves a keyword that already reads
   back cleanly alone — `:tenure-band` stays `:tenure-band` — and normalizes one
   that does not. That matters here and nowhere else in the codebase, because
   `:cols` is the only place a column keyword travels through the log as a
   *value*: `normalize-keys` fixes map keys, and `safe-event`'s guard compares
   printed strings, so `[:foo bar]` passes it while reading back as two
   elements."
  [rows]
  (into [] (distinct) (map sub/col-kw (mapcat keys rows))))

(defn- normalize-rows
  "Every row keyed by `cols-of`'s keywords, so the rows and the column list
   cannot disagree."
  [rows]
  (mapv (fn [r] (reduce-kv (fn [m k v] (assoc m (sub/col-kw k) v)) {} r)) rows))
```

Both `put-rows!` implementations then work from `(normalize-rows (vec rs))`. In `Lake`'s
`put-rows!` that is one binding change; in `InlineTable`'s the same. Keep `cols-of` computed
from the normalized rows so the two cannot drift.

`load-rows!` needs no change: `read_json_auto` gives NULL for a key a JSON object omits, and
`select-cols` names every column, so a ragged table loads with nulls where keys were absent.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Confirm the live corpus is unmoved**

The baseline is **27 of 31**, measured twice independently after Task 6f. (Task 6d's 30 of 31
described the hydrate-and-scan synopsis that 6f replaced; do not use it.) The four that diverge
are `tbl:csv-19-7071`, `tbl:csv-18-1542`, `tbl:corpus-2` and `tbl:extract-20-8413`, all for
value-rendering reasons that have nothing to do with column names.

Re-run that measurement and confirm it is **still 27 of 31** — `col-kw` on the existing tables'
keywords must be a no-op, since all 31 already carry readable keywords. If this task moves any
of the 27, it has broken something Task 6d fixed. Report the number and any table that changed
side.

Note also that after this task the *inline* synopsis names normalized columns, because `cols-of`
routes them through `col-kw`. Nothing live is affected — the 27 hold precisely because every
existing column keyword already reads back cleanly — but your sabotage step should expect it.

- [ ] **Step 6: Sabotage**

Revert `cols-of` to `(vec (keys (first rows)))`. `a-ragged-table-keeps-every-column` and its
lake twin must FAIL with `[:a]`. Restore.

Then drop the `sub/col-kw` from `cols-of` alone, leaving `normalize-rows` in place.
`a-column-keyword-the-log-cannot-hold-is-normalised` must FAIL — and note *which* assertion
fails, because if only the `pr-str` one does, the rows and columns have drifted apart, which is
the failure mode `normalize-rows` exists to prevent. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tablestore.clj test/loci/tablestore_test.clj test/loci/lake_test.clj
git commit -m "fix: every column is a column, and a handle survives the log"
```

---

## Task 6f: ask the database for the synopsis, instead of shipping it rows

**Files:**
- Modify: `src/loci/tablestore.clj` (`TableStore` gains `column-samples`; `InlineTable` implements it)
- Modify: `src/loci/lake.clj` (`Lake` implements it, in SQL, memoized)
- Modify: `src/loci/chunks.clj` (`synopsis` splits into a formatter and a sampler; `all-chunks` attaches samples instead of hydrating)
- Test: `test/loci/tablestore_test.clj`, `test/loci/lake_test.clj`, `test/loci/chunks_test.clj`

**Added 2026-08-16.** Task 6c made `all-chunks` hydrate so a lake-backed table would still get a
synopsis. That was the smallest change that restored the behaviour, and it is the wrong shape:
it ships 2,000 rows every 15 seconds to build a 600-character string, and it still samples only
the first 2,000 rows of a table that may have 10,000,000.

Three facts make a better version obvious, and all three are already established:

1. **A synopsis is bounded output.** `synopsis-max-chars` is 600 and the clip is unconditional
   (`chunks.clj:69`), so a 10M-row table and a 3-row table produce the same size vector. We were
   never embedding rows and this task does not change that.
2. **A handle is immutable.** Writes never overwrite — every write creates a new lake table — so
   a given `[table, snapshot]` pair can be sampled once, ever. Re-deriving it every 15 seconds
   is work that provably cannot produce a different answer.
3. **DuckDB can answer the question directly.** The synopsis needs each column's first few
   distinct values. That is `SELECT DISTINCT` with a `LIMIT`, which ships nine values rather
   than 2,000 rows, and is correct over 10M rows rather than over the first 2,000.

**The ordering change, and why it is worth a one-time re-embed.** Today `synopsis` takes the
first three distinct values *in row order*. SQL has no row order to appeal to without an
`ORDER BY`, and a synopsis whose values vary between runs would move the hash on every sweep and
re-embed forever. So both implementations change to: **the distinct values of the column,
ordered by their string form, first three.** Deterministic in both, identical in both — which is
what keeps the lake and inline paths from drifting — and it fixes the >`mold-cap` divergence
recorded in Task 6d rather than moving it.

Ordering by string form rather than natural order is deliberate: a column holding both `50` and
`"~13%"` has no natural order, and the string form is what gets printed into the synopsis anyway.

The cost is that every table's synopsis text changes once, so **all 31 re-embed when this
lands** — one model round trip each, once. That is cheaper than doing it at migration and
cheaper than keeping the bug.

- [ ] **Step 1: Write the failing tests**

`test/loci/tablestore_test.clj`:

```clojure
(deftest column-samples-are-distinct-ordered-and-bounded
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:r "EMEA" :n 1} {:r "APAC" :n 2}
                                      {:r "EMEA" :n 3} {:r "AMER" :n 4}])]
    (is (= {:r ["AMER" "APAC" "EMEA"] :n ["1" "2"]}
           (tbl/column-samples ts h 3 2))
        "distinct, ordered by string form, capped per column")))

(deftest column-samples-skips-nulls
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a nil} {:a "z"}])]
    (is (= {:a ["z"]} (tbl/column-samples ts h 3 5)))))
```

`test/loci/lake_test.clj` — the same contract against the real store, plus the property the
whole task exists for:

```clojure
(deftest the-lake-samples-the-same-values-the-rows-would
  (with-lake [lk]
    (let [rs (into [] (for [i (range 100)] {:r (str "r" (mod i 7)) :n i}))
          hl (tbl/put-rows! lk "tbl:x" rs)
          hi (tbl/put-rows! (tbl/inline-table) "tbl:x" rs)]
      (is (= (tbl/column-samples (tbl/inline-table) hi 3 3)
             (tbl/column-samples lk hl 3 3))
          "inline and lake must agree, or migration re-embeds every table"))))

(deftest sampling-sees-past-the-mold-cap
  ;; The bug this task exists to fix. Sorted so a column's variety begins well
  ;; past `mold-cap`: hydrating 2,000 rows sees one region, the database sees
  ;; all three.
  (with-lake [lk]
    (let [rs (into [] (for [i (range 3000)]
                        {:region (cond (< i 2500) "AAA" (< i 2800) "BBB" :else "CCC") :i i}))
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= ["AAA" "BBB" "CCC"] (:region (tbl/column-samples lk h 3 3)))
          "a column's variety past row 2,000 is still the column's variety"))))

(deftest sampling-an-immutable-handle-happens-once
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:a 1}])
          n (atom 0)]
      (with-redefs [lake/q (let [q lake/q] (fn [& args] (swap! n inc) (apply q args)))]
        (tbl/column-samples lk h 3 3)
        (let [after-first @n]
          (tbl/column-samples lk h 3 3)
          (is (= after-first @n)
              "a handle cannot change, so it must not be sampled twice"))))))
```

`test/loci/chunks_test.clj` — the parity test from Task 6c must still hold, and one new test
that the sweep no longer reads rows:

```clojure
(deftest chunking-a-lake-table-reads-no-rows
  (lf/with-temp-lake
    (fn []
      (let [rs (into [] (for [i (range 500)] {:a i}))
            h  (tbl/put-rows! @lake/lake "tbl:x" rs)
            st (sub/fresh-store)]
        (sub/commit! st {:op :put :id "tbl:x"
                         :value {:id "tbl:x" :kind :table :title "S" :value h}})
        (with-redefs [tbl/rows (fn [& _] (throw (ex-info "the sweep fetched rows" {})))]
          (is (seq (ch/all-chunks st))
              "a synopsis must come from samples, not from a page of rows"))))))
```

- [ ] **Step 2: Run to verify they fail**

Expected: `No such var: tbl/column-samples` throughout. Paste it.

- [ ] **Step 3: Add `column-samples` to the protocol**

`src/loci/tablestore.clj`:

```clojure
(defprotocol TableStore
  (put-rows! [ts id rows])
  (rows [ts h opts])
  (row-count [ts h])
  (column-samples [ts h per-col max-cols]
    "Up to `per-col` sample values for each of the first `max-cols` columns, as
     `{column [\"…\" …]}` with the values already stringified.

     Distinct, ordered by string form, nulls dropped. Ordered rather than
     first-seen because the lake has no row order to appeal to without an
     `ORDER BY`, and a synopsis whose values vary between runs would move its
     hash on every sweep and re-embed forever. Ordered *by string form* because
     a column holding both `50` and `\"~13%\"` has no natural order, and the
     string is what reaches the synopsis anyway.

     This is what a table's chunk is built from. It exists so that describing a
     10M-row table costs nine values rather than a page of rows, and so that the
     description covers the whole column rather than its first `mold-cap` rows."))
```

`InlineTable`'s implementation, which is also the reference definition of the contract:

```clojure
  (column-samples [_ h per-col max-cols]
    (let [rs (:data h)]
      (into {} (for [c (take max-cols (:cols h))]
                 [c (->> rs (keep #(get % c)) (map str) distinct sort (take per-col) vec)]))))
```

- [ ] **Step 4: Implement it in SQL**

`src/loci/lake.clj`. One query per column — `SELECT DISTINCT` with `ORDER BY` and `LIMIT` on a
single column is what a columnar store is for, and the memoize below means a given handle pays
it once:

```clojure
(def ^:private sample-cache
  "Samples for a `[table snapshot]`, computed once.

   `memoize` rather than a hand-rolled atom, and sound rather than merely
   convenient: a handle is immutable, because `put-rows!` always creates a new
   lake table and nothing overwrites one. So a hit is not a stale answer, it is
   the only answer.

   Unbounded, and that is affordable: an entry is at most 24 columns × 3 clipped
   strings, well under 1 KB, so a thousand table versions is about a megabyte.
   Measure before replacing this with an eviction policy."
  (memoize
   (fn [lk table snapshot cols per-col]
     (into {} (for [c cols]
                [c (mapv #(str (first (vals %)))
                         (q lk (str "SELECT DISTINCT CAST(" (ident c) " AS VARCHAR) AS v"
                                    " FROM " table " AT (VERSION => " (long snapshot) ")"
                                    " WHERE " (ident c) " IS NOT NULL"
                                    " ORDER BY v LIMIT " (long per-col))))]))))) 
```

and in the `extend-type Lake` block:

```clojure
  (column-samples [lk h per-col max-cols]
    (sample-cache lk (:table h) (:snapshot h) (vec (take max-cols (:cols h))) per-col))
```

**Confirm by measurement, not by reading, that `ORDER BY` on the CAST alias matches
`InlineTable`'s `sort` over stringified values** — including for a column mixing numbers and
strings, where SQL's VARCHAR collation and Clojure's `compare` on strings could differ. If they
disagree, say so and report both orderings rather than picking one; that is a finding about the
contract, and the contract is what keeps migration from re-embedding.

- [ ] **Step 5: Split `synopsis`**

`src/loci/chunks.clj`. The formatting stays in one place so the two stores cannot drift:

```clojure
(defn- synopsis
  "One bounded description of a table: its title, its column names, and a few
   sample values per column.

   Not its rows. The whole point is that 2,584 rows produce one vector, and the
   clip at the end makes that a guarantee rather than an expectation — a caller
   can assert on the length and the assertion holds for any table.

   Takes samples rather than rows so that a lake-backed table can be described
   without fetching any: the store answers `column-samples` from SQL, and this
   function never learns which store it was. The row *count* is deliberately
   absent: it would move the hash on every appended row and buy nothing anyone
   searches for."
  [title cols samples]
  (let [line (fn [c] (str (col-name c) ": "
                          (str/join ", " (map #(clip % 40) (get samples c)))))]
    (clip (str/join "\n" (concat (when-not (str/blank? (str title)) [(str title)])
                                 [(str "columns: " (str/join ", " (map col-name cols)))]
                                 (map line cols)))
          synopsis-max-chars)))
```

`texts-of`'s `(:table :metric)` branch takes its samples from the object when they are there and
computes them from rows when they are not:

```clojure
    (:table :metric)
    (cond
      (:table/samples obj)
      [(synopsis title (:table/cols obj) (:table/samples obj))]

      (rows? value)
      (let [h (tbl/put-rows! (tbl/inline-table) (:id obj) value)]
        [(synopsis title (vec (take synopsis-cols (:cols h)))
                   (tbl/column-samples (tbl/inline-table) h synopsis-samples synopsis-cols))])

      (not (str/blank? (str title))) [(str title)]
      :else [])
```

`chunks-of` stays **pure** — it reads only the map it was handed. `InlineTable` is a record with
no state, so constructing one is not a store access; if that reads badly to you, lift it to a
`def` rather than threading a store into a pure function.

- [ ] **Step 6: Stop hydrating in `all-chunks`**

```clojure
(defn- with-samples
  "A handle-backed table, described. Attaches what `texts-of` needs and nothing
   else — the rows are never fetched."
  [o]
  (if (tbl/handle? (:value o))
    (let [h (:value o)]
      (assoc o :table/cols    (vec (take synopsis-cols (:cols h)))
               :table/samples (tbl/column-samples (force lake/lake) h
                                                  synopsis-samples synopsis-cols)))
    o))

(defn all-chunks
  "Every chunk the substrate currently implies. Reads the store; writes nothing
   to it, ever.

   A table's synopsis comes from `column-samples`, which the lake answers in SQL
   and memoizes per handle, so this costs no row reads in steady state and stays
   bounded whatever a table's size. `chunks-of` stays pure — it never learns
   that a lake exists."
  [st]
  (chunks-in (map with-samples (vals (sub/objects st)))))
```

Note `(force lake/lake)` inside the handle branch only, preserving Task 6c's property that a
store holding no handle never opens a lake. `all-chunks-opens-no-lake-for-a-store-with-no-handle`
from Task 6d must still pass — if it does not, this branch is in the wrong place.

- [ ] **Step 7: Measure what this was for**

Three numbers, on the live corpus (154 objects, 306 chunks, 31 row-bearing tables), median of 7:

| | Task 6d measured | now |
|---|---|---|
| `all-chunks`, tables inline | 16 ms | ? |
| `all-chunks`, tables handle-backed, cold cache | 297 ms | ? |
| `all-chunks`, tables handle-backed, warm cache | — | ? |

The warm number is the one that matters: it is what the 15-second sweep actually pays. Then
measure the cold cost of a **10M-row** table's samples — the case the whole task is about — and
report it. Building 10M rows takes ~2.9 s via `put-rows!`; that is a fair price for the one
measurement that says whether this design holds at the size it was designed for.

- [ ] **Step 8: Re-measure the live corpus for parity**

Run all 31 tables through a temp lake and compare synopsis text inline vs handle-backed, as
Tasks 6c and 6d did. Expected: **30 of 31 identical**, the exception still
`tbl:extract-20-8413`'s mixed `Long`/`String` column. Report the number.

Separately and importantly: the synopsis text now differs from what is **already stored** in
`chunks.edn`, because the sample ordering changed. Report how many of the 31 tables' chunk
hashes move. That number is the one-time re-embedding cost of this task, and it should be
roughly all of them.

- [ ] **Step 9: Sabotage**

Drop the `sort` from `InlineTable`'s `column-samples`. `the-lake-samples-the-same-values-the-rows-would`
must FAIL — paste both orderings, because that difference is what would re-embed every table on
migration.

Drop the memoize (call the inner function directly). `sampling-an-immutable-handle-happens-once`
must FAIL with a second query count. Restore.

Replace `column-samples` on `Lake` with one that hydrates and samples the rows.
`sampling-sees-past-the-mold-cap` must FAIL with `["AAA"]` — paste it, because that single value
is what a 10M-row table's whole description would have been.

- [ ] **Step 10: Commit**

```bash
git add src/loci/tablestore.clj src/loci/lake.clj src/loci/chunks.clj test/
git commit -m "feat: a table describes itself in SQL, once, however big it is"
```

---

## Task 7: The six writers land handles

**Files:**
- Modify: `src/loci/tablestore.clj` (add `table-object`)
- Modify: `src/loci/tools.clj:117` and `:155`
- Modify: `src/loci/server.clj:1160`, `:1241`, `:1788`
- Modify: `src/loci/corpus.clj:468`
- Test: `test/loci/tools_test.clj`, `test/loci/server_test.clj`

Six sites build a table object as a map literal. Rather than edit each to talk to the lake,
add one constructor and have each call it — DRY, and it means a seventh site added later gets
the behaviour by using the obvious function.

**Three things changed under this task while Tasks 6b–6f ran. Read these before Step 1.**

**(a) Writers force the lake; readers do not.** Task 6c made every *reader* pass `lake/lake` —
the delay — so `hydrate` forces it only when there is a handle to resolve. A writer always
writes, so `@lake/lake` at these call sites is correct and deliberate. Do not "make it
consistent" with the readers. `grep -n "@lake/lake" src/loci/*.clj` should end this task showing
the boot force plus the six writers, and nothing else.

**(b) `put-rows!` now normalizes, so a writer must report the columns it *stored*.**
`tbl/normalize-rows` runs every column keyword through `sub/col-kw`, and since Task 6e a
collision throws rather than silently merging. Two consequences:

- `save-table!` (`tools.clj:148-152`) hand-rolls exactly that normalization. Delete it and let
  `put-rows!` do it — `tbl/normalize-rows` is public for this reason.
- But `save-table!` then computes `:columns (mapv name (keys (first rows)))` from those
  *pre-normalized* rows. Once the hand-rolled pass is gone, that would report the model's
  original names — `"Total Sales"` — while the stored column is `:total_sales`, and the model
  would then call `query_table` with a column that does not exist. **Report `(:cols h)` from the
  handle instead**, at every writer that returns column names to a caller.

**(c) `Lake/put-rows!` refuses an empty table; `InlineTable` does not.** `lake.clj` throws
`"refusing to store a table with no rows"`. Today a compute or an import that yields zero rows
writes an object with `:value []`. After this task it throws. **That is a user-visible behaviour
change on at least `compute-clj!` and the ƒ apply**, both of which can legitimately produce zero
rows from a filter.

Before converting anything, walk all six sites and report, for each: is there an empty-rows path,
what happens today, and what would happen after. Then keep each site's existing guard, or add the
smallest one that preserves today's behaviour. `save-table!` already returns
`{:error "no rows to save"}` and needs nothing. **Do not let a zero-row filter start throwing a
500** — if preserving today's behaviour needs a decision rather than a guard, stop and report.

- [ ] **Step 1: Write the failing test**

Add to `test/loci/tablestore_test.clj`:

```clojure
(deftest table-object-lands-rows-and-returns-a-handle
  (let [ts (tbl/inline-table)
        o  (tbl/table-object ts "tbl:x" "Title" rows {:from "tbl:src"})]
    (is (= "tbl:x" (:id o)))
    (is (= :table (:kind o)))
    (is (= "Title" (:title o)))
    (is (= "tbl:src" (:from o)) "extra keys survive")
    (is (tbl/handle? (:value o)))
    (is (= rows (tbl/rows ts (:value o) {})))))
```

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Implement the constructor**

```clojure
(defn table-object
  "The one way to build a `:kind :table` object. Lands the rows in `ts` and
   returns the object carrying a handle.

   One function rather than six map literals, so that a site added later gets
   lake storage by using the obvious constructor rather than by remembering to."
  [ts id title rows & [extra]]
  (merge {:id id :kind :table :title title :value (put-rows! ts id rows)}
         extra))
```

- [ ] **Step 4: Convert each site**

Each change is the same shape. `tools.clj:155`, in `save-table!`:

```clojure
      (let [nid (str "tbl:extract-" (count (table-objs st)) "-" (inc (rand-int 9999)))
            obj (tbl/table-object @lake/lake nid (or title "Extracted table") rows)]
```

`server.clj:1788`, in `import-csv!`:

```clojure
          obj (tbl/table-object @lake/lake nid (or (not-empty title) "Imported CSV") rows)
```

`server.clj:1160`, in `compute-clj!`:

```clojure
                  tobj (tbl/table-object @lake/lake nid ttl out {:from id :via fid})
```

`server.clj:1241`, the `fnlib` apply:

```clojure
                  tobj (tbl/table-object @lake/lake nid (str (:title src) " · " lbl)
                                         (:rows r) {:from id :via fid :params (or params {})})
```

`corpus.clj:468`:

```clojure
        tobj   (tbl/table-object @lake/lake tid (:title tentry) rows)
```

`tools.clj:117`, the `query_table :save_as` path — read the surrounding code and apply the
same shape; it builds `{:id nid :kind :table :title save_as :value rendered}`.

Add `[loci.tablestore :as tbl]` and `[loci.lake :as lake]` to each namespace's requires.

- [ ] **Step 5: Verify no site was missed**

```bash
grep -rn ":kind :table\|:kind  :table" src/loci/*.clj
```

Expected: only `tablestore.clj`'s `table-object` and `content.clj`'s viewer registrations
(`:kind :table` there is a *viewer* declaration, not an object — leave it alone). Any other hit
is a site still building a literal.

- [ ] **Step 6: Run the affected suites**

```clojure
(doseq [n '[loci.tablestore loci.lake loci.tools loci.server loci.corpus]] (require n :reload))
(clojure.test/run-tests 'loci.tools-test 'loci.server-test 'loci.corpus-test)
```

All must be green. If `corpus_test` compares whole event maps, its expectations now contain a
handle — update the assertions to compare through `tbl/rows` rather than loosening them.

- [ ] **Step 7: Sabotage**

Revert `save-table!` alone to its map literal (`:value rows`). `table-object-lands-rows-and-returns-a-handle`
will still pass — it tests the constructor — so this sabotage must be caught by a *behavioural*
test instead. Add one to `tools_test` first if none exists:

```clojure
(deftest saved-table-stores-a-handle-not-rows
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{:a 1}])]
    (is (tbl/handle? (:value (sub/object st (:saved_as r))))
        "rows must not be in the event")))
```

Confirm it fails with the literal restored, then re-apply the fix and confirm it passes.

Add a second behavioural test, for (b) — the one that would otherwise send a model chasing a
column that does not exist:

```clojure
(deftest a-writer-reports-the-columns-it-stored
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{(keyword "Total Sales") 1}])]
    (is (= ["total_sales"] (:columns r))
        "the model must be told the name query_table will accept, not the one it sent")
    (is (= [:total_sales] (:cols (:value (sub/object st (:saved_as r))))))))
```

Sabotage it by reporting `(keys (first rows))` from the un-normalized input; it must fail with
`["Total Sales"]`.

- [ ] **Step 7b: Prove the log no longer holds rows**

The point of the whole plan, and nothing yet asserts it end to end. `save-table!` a table, then
read the raw event back out of the log and confirm the rows are not in it:

```clojure
(deftest the-log-holds-a-handle-and-not-the-rows
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" (mapv (fn [i] {:i i}) (range 100)))
        ev (last (sub/history st))]
    (is (not (re-find #"\{:i 7\}" (pr-str ev)))
        "a row must not appear anywhere in the event")
    (is (= 100 (:rows (:value (:value ev)))))))
```

Read `sub/history`'s actual shape before writing this — the nesting of `:value` inside the event
map is worth checking rather than assuming. Report the raw event's printed length before and
after the conversion; that number is what this plan bought.

- [ ] **Step 8: Commit**

```bash
git add src/loci/tablestore.clj src/loci/tools.clj src/loci/server.clj src/loci/corpus.clj test/
git commit -m "feat: every table writer lands a handle, not rows"
```

---

## Task 7c: the eighth writer, three uncaught throws, and a value the shell cannot render

**Files:**
- Modify: `src/loci/lake.clj` (`q-as`)
- Modify: `src/loci/content.clj` (`seed!`)
- Modify: `src/loci/tools.clj` (`save-table!`), `src/loci/server.clj` (`rerun!`)
- Modify: `src/loci/tablestore.clj` (`table-object` docstring)
- Test: `test/loci/lake_test.clj`, `test/loci/tools_test.clj`, `test/loci/server_test.clj`, `test/loci/content_test.clj`

**Added 2026-08-16, from Task 7's review.** Four defects, in the order they matter.

**1. `q-as` leaks `org.duckdb.JsonNode` into the payload, and it is a 500.** This is the Task 9
blocker. When `read_json_auto` meets a column holding more than one type it widens it to DuckDB
`JSON`, and JDBC hands back a `JsonNode`. Measured on `tbl:csv-18-1542` round-tripped through a
real lake:

```clojure
(json/write-str (mold-payload …))
;; => "Don't know how to write JSON of class org.duckdb.JsonNode"
```

`json-resp` is `clojure.data.json/write-str` (`server.clj:44-47`), so migrating one of these
tables makes `/api/mold` throw — **the object cannot be opened in the shell at all** — and
`query_table` on it throws into the agent loop. Task 9 as written would refuse three tables and
call it a rendering disagreement. It is not: a storage layer must not return values its callers
cannot serialize.

The fix is at the boundary that created the problem. A `JsonNode`'s `toString` is the JSON text
of the value, so parsing it back restores the original Clojure value exactly — `50` returns as
`50`, `"~13% (1 of 8 countries)"` as that string. The mixed column then round-trips **losslessly**
rather than being refused.

**2. `loci.content/seed!` is an eighth writer**, and the grep missed it for exactly the reason
it missed `rerun!`: `obj` (`content.clj:494`) takes `kind` as a *parameter*, so
`(obj "tbl:orders" :table … orders-rows)` matches no `:kind :table` search. Measured: **10 table
objects, 1,810 rows, 230,300 characters** of `:put` events, `tbl:orders` alone 134,630 chars in
one event. This is production code — `content/store` seeds whenever the log is empty, i.e. every
fresh install — so after Task 9 migrates the store, deleting `data/substrate` and rebooting
re-creates ten inline tables. `table-object`'s "The one way to build a `:kind :table` object" is
false while this stands.

**3. Two writers throw where nothing catches.** Task 6e made `normalize-rows` throw on a column
collision; Task 7 made that throw reachable from two boundaries with no net.

- **`tools/save-table!`** has no `try`, `agent/chat-tools` does not catch `tool-fn`
  (`agent.clj:139`), and `draft-brief!` catches at `server.clj:2079` — so a model emitting both
  `"Total Sales"` and `"total_sales"` blanks the entire brief with
  `_(agent unavailable: two column names would become one column…)_`. That is the same failure
  the empty-rows guard was added to prevent, from the same tool.
- **`server/rerun!`** is the only writer with no `try` — `fn-apply!`, `compute-clj!`,
  `import-csv!`, `describe!`, `make-view!` and `do!` all catch. `handler` has no top-level catch
  either, so `/api/rerun` returns a 500 with a stack trace. Worse, the commit is a single one
  *after* the whole reduce, so a throw means **nothing** refreshes, not even the levels that
  succeeded. Reachable because `lib:pivot` takes column names from data *values*
  (`fnlib.clj:63`).

**4. One missing assertion.** `do-query :save_as` is the only converted writer with no test that
it lands a handle when it has rows; `a-save_as-that-filtered-everything-out-is-an-empty-table`
covers only the empty branch.

- [ ] **Step 1: Write the failing tests**

`test/loci/lake_test.clj`:

```clojure
(deftest a-mixed-type-column-round-trips-as-its-own-values
  ;; read_json_auto widens a column holding more than one type to DuckDB JSON,
  ;; and JDBC returns org.duckdb.JsonNode. Left alone it reaches the payload
  ;; and `clojure.data.json/write-str` throws — measured on tbl:csv-18-1542,
  ;; which would make /api/mold a 500 for that object. A JsonNode's toString is
  ;; the JSON text of the value, so parsing it back gives the value that went in.
  (with-lake [lk]
    (let [rs [{:pct 50} {:pct "~13% (1 of 8 countries)"} {:pct 10}]
          h  (tbl/put-rows! lk "tbl:x" rs)
          back (tbl/rows lk h {})]
      (is (= rs back) "the values that went in must be the values that come out")
      (is (every? #(or (number? %) (string? %)) (map :pct back))
          "and nothing may be a driver type")
      (is (string? (json/write-str back))
          "the whole point: the shell must be able to render it"))))
```

`test/loci/tools_test.clj`:

```clojure
(deftest a-column-collision-is-an-error-not-a-thrown-exception
  ;; save_table runs inside the agent's tool loop, which does not catch. A
  ;; throw here blanks the whole answer with "(agent unavailable: …)" — the
  ;; same failure the empty-rows guard exists to prevent, from the same tool.
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{(keyword "Total Sales") 1 :total_sales 2}])]
    (is (:error r) "a refusal must come back as a value")
    (is (re-find #"one column" (:error r)))
    (is (empty? (sub/objects st)) "and nothing may be committed")))

(deftest a-save_as-with-rows-lands-a-handle
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:src"
                     :value {:id "tbl:src" :kind :table :title "S" :value [{:a 1} {:a 2}]}})
    (let [r (tools/dispatch st "query_table" {:table_id "tbl:src" :save_as "Kept"})]
      (is (tbl/handle? (:value (sub/object st (:saved_as r))))))))
```

`test/loci/server_test.clj`:

```clojure
(deftest a-rerun-that-cannot-write-returns-an-error-not-a-500
  ;; /api/rerun has no net under it: rerun! is the only writer without a try,
  ;; and `handler` does not catch either. The commit is one :tx after the whole
  ;; reduce, so a throw loses every level, including the ones that worked.
  (let [st (sub/fresh-store)]
    (with-redefs [tbl/table-value (fn [& _] (throw (ex-info "boom" {})))]
      (let [r (srv/rerun! st "tbl:nope")]
        (is (:error r) "a refusal must come back as a value")
        (is (not (re-find #"Exception" (str (:error r)))))))))
```

`test/loci/content_test.clj` — read the file first; if there is no such namespace, put this in
whichever suite already exercises the seed:

```clojure
(deftest the-seed-lands-handles-not-rows
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)]
        (content/seed! st)
        (let [tables (->> (sub/objects st) vals (filter #(= :table (:kind %))))]
          (is (seq tables))
          (is (every? #(tbl/handle? (:value %)) tables)
              "a fresh install must not put 1,810 rows in the log")
          (is (not (re-find #":sku " (pr-str (sub/history st))))
              "and no row may appear anywhere in the log"))))))
```

Read `seed!`'s actual arity and the shape of `content/store` before writing this — `:sku` is a
guess at a column name, so use one you have confirmed is in the seed data.

- [ ] **Step 2: Run to verify they fail**

Paste all five failures.

- [ ] **Step 3: Fix `q-as`**

`src/loci/lake.clj`. A `JsonNode` must not escape the storage layer:

```clojure
(defn- unwrap
  "A JDBC value as a Clojure value.

   `read_json_auto` widens a column holding more than one type to DuckDB JSON,
   and the driver returns `org.duckdb.JsonNode` — which `clojure.data.json`
   cannot write, so it reaches `/api/mold` and 500s the object rather than
   merely rendering oddly. Its `toString` is the JSON text of the value, so
   parsing it back gives what was written: `50` returns as `50` and
   `\"~13%\"` as that string. Measured 2026-08-16 over `tbl:csv-18-1542`,
   `tbl:csv-19-7071` and `tbl:extract-20-8413` — the three tables in the
   substrate with such a column, and the three Task 9 would otherwise refuse."
  [v]
  (if (instance? org.duckdb.JsonNode v)
    (json/read-str (str v))
    v))
```

applied in `q-as`'s row construction. **Leave `q` alone** — it is the general helper and its
callers are SQL of this namespace's own making.

Check whether `json/read-str` needs `:key-fn` for a genuinely nested JSON object, and say what
you chose. Also confirm what happens for a JSON `null`.

- [ ] **Step 4: Convert `seed!`**

`content.clj`'s `obj` helper builds every object. Route the table case through
`tbl/table-object`. **`seed!` must take the table store as an argument** rather than forcing
`@lake/lake` itself — `loci.content` is required by tests that have no business opening a lake,
and the namespace's existing property is that requiring it starts nothing. Give it a default of
`@lake/lake` for the boot path if that reads cleanly, or thread it from `content/store`.

**If threading it turns out to reach further than `content/store` — into the browser fixture's
seeding script, or into a test helper you would have to change in several places — stop and
report what it would take.** Exempting `seed!` and documenting it is an acceptable outcome; a
half-threaded store is not.

- [ ] **Step 5: Catch the two throws**

`tools/save-table!` — wrap the `table-object` call so a collision returns
`{:error <message>}`, matching its own `{:error "no rows to save"}` sibling. Nothing may be
committed on that path.

`server/rerun!` — give it the `try`/`catch` its six sibling writers already have, returning
`{:error …}`. Follow whichever of them reads closest.

- [ ] **Step 6: Correct `table-object`'s docstring**

"The one way to build a `:kind :table` object" and "One function rather than six map literals"
are both now false — there were seven writers, then eight. Say the true thing: it is the
constructor every writer should use, name `table-value` as the sibling for the `:assoc` path,
and record that two writers were found only after the `:kind :table` grep came back clean, so
the grep is not the invariant.

- [ ] **Step 7: Re-measure the corpus**

With `q-as` fixed, re-run the 31-object parity measurement. The three JSON-column tables should
now round-trip, so **expect the count to rise from 27 of 31**. Report the actual number and
which tables still diverge — `tbl:corpus-2`'s `1.24E7`/`12400000.0` rendering difference is the
one that should remain, since that is a Double formatting difference and not a JSON one.

Then confirm the thing that motivated all of this:
`(json/write-str (mold-payload …))` on a migrated `tbl:csv-18-1542` must return a string.

- [ ] **Step 8: Sabotage**

Remove `unwrap` from `q-as` → `a-mixed-type-column-round-trips-as-its-own-values` must fail, and
paste the `write-str` error.
Revert `save-table!`'s catch → the collision test must fail by throwing rather than returning.
Revert `rerun!`'s catch → the 500 test must fail.
Revert `seed!` → `the-seed-lands-handles-not-rows` must fail; report the log's character count
with the rows back in it.

- [ ] **Step 9: Commit**

```bash
git add src/loci/lake.clj src/loci/content.clj src/loci/tools.clj src/loci/server.clj src/loci/tablestore.clj test/
git commit -m "fix: the eighth writer, two uncaught throws, and a value the shell cannot render"
```

---

## Task 7d: two more values the shell cannot render, and two metrics the seed missed

**Files:** `src/loci/tablestore.clj`, `src/loci/lake.clj`, `src/loci/content.clj`,
`src/loci/server.clj`; tests in `lake_test.clj`, `tablestore_test.clj`, `server_test.clj`.

**Added 2026-08-17, from Task 7c's review.**

**1. `unwrap` closed one hole of three.** `read_json_auto` widens a *mixed*-type column to
DuckDB `JSON`, which `unwrap` now handles. But a column where every row has the **same** nested
shape is inferred as `STRUCT` or `LIST` instead, never touches `unwrap`, and produces the
identical failure:

```
Don't know how to write JSON of class org.duckdb.DuckDBArray
Don't know how to write JSON of class org.duckdb.DuckDBStruct
```

Reachable from the agent's own path: `save_table` with `[{:name "a" :tags ["x" "y"]}]` succeeds,
and the next `query_table` on it throws into the tool loop. It is a **regression from moving rows
to the lake** — the same rows through `InlineTable` serialize fine.

**The remedy is to refuse, not to support.** A table column holding a vector or a map is not
something loci renders: `content.clj`'s viewer predicates want scalars, `fnlib`'s `numeric-cols`
and `cat-cols` want scalars, and `save_table`'s own tool description says "each row is an object
of column→value". Supporting nested columns properly means `to_json` in the SELECT plus a type
probe per read, which is real machinery for a shape nothing asks for. **0 of the 31 live tables
have a nested value.** Refusing at write time, with a message the agent can act on, is strictly
better than a 500 at read time.

Refuse in **both** stores, in `normalize-rows` beside the collision check, so `InlineTable` and
`Lake` cannot disagree — a divergence between them is the failure this plan has spent four tasks
closing.

**2. `seed!` converted 10 of 12.** It filters `(= :table (:kind o))`, and `content/objects` holds
`{:table 10, :metric 2, …}`. So `metric:mrr` and `metric:headcount` still seed **48 rows,
1,882 characters** of inline rows with `777000` sitting in the log. The new test cannot see it —
`the-seed-lands-handles-not-rows` filters on `:table` and asserts `(= 10 …)`, so it is
structurally blind to the two it misses.

This also falsifies a premise in Task 9: *"nothing writes a `:metric`"*. `seed!` does. Migrating
the live pair without fixing this means a fresh install re-creates them inline — the exact
scenario Task 7c set out to end.

- [ ] **Step 1: Write the failing tests**

```clojure
;; lake_test.clj — the hole unwrap does not cover
(deftest a-uniformly-nested-column-is-refused-not-returned-unserializable
  (with-lake [lk]
    (doseq [rs [[{:tags ["x" "y"]} {:tags ["z"]}]
                [{:meta {"a" 1}} {:meta {"a" 2}}]]]
      (let [r (try (tbl/put-rows! lk "tbl:x" rs) (catch Exception e (.getMessage e)))]
        (is (string? r) "a nested column must be refused at write time")
        (is (re-find #"nested" r))))))

;; tablestore_test.clj — both stores agree
(deftest both-stores-refuse-a-nested-column
  (doseq [ts [(tbl/inline-table)]]
    (is (thrown-with-msg? Exception #"nested"
          (tbl/put-rows! ts "tbl:x" [{:tags ["x"]}])))))

;; server_test.clj — replace the kind-blind seed assertion
(deftest the-seed-lands-handles-for-every-row-bearing-kind
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)]
        (content/seed! st @lake/lake)
        (let [rb (->> (sub/objects st) vals (filter #(#{:table :metric} (:kind %))))]
          (is (= 12 (count rb)) "10 tables and 2 metrics")
          (is (every? #(tbl/handle? (:value %)) rb))
          (is (not (re-find #"777000" (pr-str (sub/history st))))
              "a metric's rows must not be in the log either"))))))
```

Confirm the third test fails at **10 of 12** before the fix, and report the metric objects'
character count.

- [ ] **Step 2: Implement**

Refuse nested values in `normalize-rows` (`tablestore.clj`), beside the collision check. Name the
column and the offending value's type in the `ex-info`, and say in the docstring why refusing
beats supporting — the `content.clj`/`fnlib` scalar assumption, the 0-of-31 measurement, and the
`DuckDBArray`/`DuckDBStruct` errors this prevents.

Then widen `seed!`'s filter from `(= :table (:kind o))` to `#{:table :metric}`.

- [ ] **Step 3: Two documentation corrections**

- `q`'s comment (`lake.clj:79-82`) says `column-samples` "depends on seeing the JSON rendering".
  Measured false: with `unwrap` installed inside `q`, samples are byte-identical, because
  `column-samples` depends on the `CAST(… AS VARCHAR)`, not on `q` declining to unwrap. Fix it —
  this is the same class of inaccurate docstring Task 7c was asked to correct elsewhere.
- `rerun!`'s body was not re-indented after gaining its `try` (`server.clj:1316-1362`, off by one
  throughout).

- [ ] **Step 4: Sabotage**

Remove the nested-value refusal → both new tests fail, and the lake one must reach the
`DuckDBArray` error through `json/write-str`; paste it. Revert `seed!`'s filter → the 12-count
test fails at 10.

- [ ] **Step 5: Commit**

```bash
git add src/loci/tablestore.clj src/loci/lake.clj src/loci/content.clj src/loci/server.clj test/
git commit -m "fix: refuse a nested column, and seed a metric like a table"
```

---

## Task 8: as-of reads a table's past

**Files:**
- Test: `test/loci/server_test.clj`

No implementation is expected here. Decision 1 (reads always name their version) should
already make this work, and this task exists to *prove* it rather than assume it — the spec
claims `as-of` gets faster, and a claim like that needs a test.

- [ ] **Step 1: Write the test**

`tools/save-table!` returns `{:saved_as <id> :rows <n> :columns [...]}`, so the id comes from
`:saved_as` rather than from scanning the objects map:

```clojure
(deftest as-of-reads-the-table-that-existed-then
  (let [st (sub/fresh-store)
        a  (:saved_as (tools/save-table! st "A" [{:v 1}]))
        n1 (count (sub/history st))
        b  (:saved_as (tools/save-table! st "B" [{:v 2}]))
        frozen (sub/frozen-at st n1)]
    (is (some? (sub/object frozen a)) "the first table existed at n1")
    (is (nil? (sub/object frozen b))  "the second did not")
    (is (= [{:v 1}] (:value (tbl/hydrate @lake/lake (sub/object frozen a))))
        "and its rows still resolve, from a store that cannot commit")))
```

- [ ] **Step 2: Run it**

Expected: PASS with no implementation change. **If it fails, stop and report** — it means
decision 1 is not actually holding, and that is a design problem, not a test problem.

- [ ] **Step 3: Sabotage**

Drop ` AT (VERSION => …)` from `lake/rows` again. This test should still pass (each table has
one snapshot), while `a-read-names-its-version` from Task 3 fails. That asymmetry is worth
reporting: it shows this test does *not* cover the versioned read, and Task 3's does.

- [ ] **Step 4: Commit**

```bash
git add test/loci/server_test.clj
git commit -m "test: as-of reads the table that existed then"
```

---

## Task 9: Migrate the 31 inline tables

**Files:**
- Create: `src/loci/tablemigrate.clj`
- Test: `test/loci/tablemigrate_test.clj`

Shaped like `loci.factmigrate` and `loci.corpus`: **dry run by default**, refusals returned as
a report rather than thrown, and callable from a REPL attached to the live server — which is
way (2) of the three legitimate routes to storage in `CLAUDE.md`.

**Four things changed under this task while Tasks 6b–6f ran.**

**(a-1) `report:qbr` holds a duplicate copy of `metric:mrr`'s rows** inside a chart spec, and no
writer converts it because a report is not row-bearing. Found in Task 7d. **Leave it alone** — a
report's `:value` is a document, and its embedded rows are part of that document rather than a
table's storage. Named here only so that a scan for stale row copies finds it and stops rather
than treating it as a missed migration.

**(a0) Do not use `tbl/table-object` to migrate.** It hardcodes `:kind :table`, so the two
`:metric` objects would silently become tables. Use `tbl/table-value` and `assoc` over `:value`,
which also preserves the extra keys the live objects carry (`:from` ×2, `:via` ×2). Found in
Task 7c's review.

**(a1) Numeric widening is accepted, not refused.** `tbl:csv-19-7071`'s `:urban_pct` holds `100`
beside `43.2`, and `read_json_auto` widens the column to DOUBLE, so `100` comes back `100.0`.
That is the one row divergence in 31. **Accept it and report it as `:widened`** — refusing a
table permanently because one integer became a float in a column that is already floats would
leave real data inline forever for a cosmetic difference. Report it distinctly from `:refused`
so the run says what it did rather than implying byte-identity it did not achieve.

**(a) It is 31 objects, not 29.** 29 carry `:kind :table` and 2 carry `:kind :metric`, and both
are row-bearing: `chunks.clj`'s `texts-of` handles `(:table :metric)` in one branch, and
`hydrate` keys off the handle rather than the kind. Filtering on `:table` alone would leave two
objects with rows in the log forever, and no writer would ever convert them because nothing
writes a `:metric`. Migrate both kinds; report the split.

**(b) `put-rows!` can now refuse, and a refusal must not kill the run.** Since Task 6e it throws
when two column names normalize to one keyword. Measured across all 31 live objects: **zero
collide**, so this will not fire today — but "refusals come back in the report rather than as a
thrown exception" is the whole shape of this namespace, and a migration that dies on table 17
tells you less than one that finishes and names what it skipped. Wrap each table's conversion so
a throw becomes a refusal with its reason.

**(c) One shape will fail verification, and should be refused with a reason rather than
mismatch.** A **ragged** table reads back from the lake with explicit nils where a key was
absent, so `(= (vec (:value o)) (vec back))` is false for it even though nothing was lost.
Measured: **0 of 31 are ragged**, so this is a guard against a shape that does not exist yet
rather than a case you will hit.

*Corrected 2026-08-16 by Task 7's review.* This bullet originally also named mixed-type columns,
said there was one such table, and told you to refuse it. All three were wrong. There are
**three** — `tbl:csv-18-1542` (three columns), `tbl:csv-19-7071` (one) and `tbl:extract-20-8413`
(one) — and refusing them was the wrong remedy, because the underlying problem was that
`read_json_auto` widens such a column to DuckDB `JSON` and the driver returns an
`org.duckdb.JsonNode` that `clojure.data.json/write-str` cannot serialize. Migrating one would
have made `/api/mold` **throw a 500** for that object rather than merely render it oddly. Task 7c
fixes it in `q-as`, so all three now round-trip losslessly and none needs refusing. **Verify that
before you start**: if a mixed-type table still fails to round-trip, stop — Task 7c did not
land.

Two other things that survey established about `data/`, worth having: **0 collisions**, 0 nested
values, 0 non-keyword keys, 0 nil-bearing columns, max cell 591 chars. And five `:report`
objects have vector-of-maps values that would pass a naive `(every? map? …)` shape filter — their
rows live inside `:rows` keys on `:block` maps, and DuckDB would build LIST/STRUCT columns out of
them. **Filter by `#{:table :metric}` on `:kind`, never by shape.**

**(c2) There are two parity measurements and they are not the same number.** Verified in the
REPL 2026-08-17 against the live store: `{:table 29 :metric 2}`, **31 row-bearing, 0 already
handles**. Both parities are over that 31.

- **Row round-trip** — `tbl/rows` inline vs lake. **30 of 31** after Task 7c's `unwrap`, up from
  28. The one that still differs is `tbl:csv-19-7071`'s `:urban_pct`, `100` against `100.0` —
  a `Long`/`Double` widening by `read_json_auto`, not a JSON one.
- **Synopsis/chunk** — `column-samples`. **27 of 31**, and Task 7c could not move it: samples go
  through `q` with `CAST(… AS VARCHAR)`, so the quoting that reorders them comes from the CAST,
  not from JDBC. The four are `tbl:csv-19-7071`, `tbl:csv-18-1542`, `tbl:corpus-2`,
  `tbl:extract-20-8413`.

`tbl:corpus-2` is the one to keep straight: its **rows are equal** — `1.24E7` is a Double on both
sides — and only its *samples* diverge. It is a chunk divergence and never was a row one. Verify
against the right measurement or you will chase it.

**(d) Migration moves 4 tables' chunk text, on top of 31 already pending.** Task 6f rewrote
`synopsis`, so all 31 stored chunk records are already stale and will re-embed on the next
sweep regardless of this task. Of those, 4 — `tbl:csv-19-7071`, `tbl:csv-18-1542`,
`tbl:corpus-2`, `tbl:extract-20-8413` — produce *different* text again once their rows are in
the lake, for value-rendering reasons. So the total bill is still one re-embedding per table,
not two; those 4 simply land on the lake-flavoured text. State the number after the run rather
than being surprised by it.

`data/` is test data. Do not checksum it, do not rehearse on a copy. Take a `.bak-*` of
`data/substrate` before the live run because it costs nothing, then get on with it.

- [ ] **Step 1: Write the failing tests**

```clojure
(ns loci.tablemigrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tablemigrate :as tm]))

(defn- seeded []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:a" :value {:id "tbl:a" :kind :table :title "A"
                                                  :value [{:v 1} {:v 2}]}})
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :value "text"}})
    st))

(deftest dry-run-writes-nothing
  (let [st (seeded)
        ts (tbl/inline-table)
        before (count (sub/history st))
        r (tm/migrate-tables! st ts)]
    (is (= :dry-run (:mode r)))
    (is (= 1 (:tables r)))
    (is (= before (count (sub/history st))) "no events appended")
    (is (vector? (:value (sub/object st "tbl:a"))) "still inline")))

(deftest live-run-converts-and-verifies
  (let [st (seeded)
        ts (tbl/inline-table)
        r (tm/migrate-tables! st ts {:live? true})]
    (is (= :live (:mode r)))
    (is (= 1 (:tables r)))
    (is (empty? (:refused r)))
    (is (tbl/handle? (:value (sub/object st "tbl:a"))))
    (is (= [{:v 1} {:v 2}] (tbl/rows ts (:value (sub/object st "tbl:a")) {})))
    (is (= "text" (:value (sub/object st "doc:d"))) "non-tables untouched")))

(deftest an-empty-table-is-refused-not-crashed
  (let [st (sub/fresh-store)
        _  (sub/commit! st {:op :put :id "tbl:e" :value {:id "tbl:e" :kind :table :value []}})
        r  (tm/migrate-tables! st (tbl/inline-table) {:live? true})]
    (is (= ["tbl:e"] (mapv :id (:refused r))))
    (is (= [] (:value (sub/object st "tbl:e"))) "left exactly as it was")))

(deftest already-migrated-is-a-no-op
  (let [st (seeded)
        ts (tbl/inline-table)
        _  (tm/migrate-tables! st ts {:live? true})
        r  (tm/migrate-tables! st ts {:live? true})]
    (is (= 0 (:tables r)) "a handle is not converted twice")))

(deftest a-metric-is-migrated-too
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "met:m" :value {:id "met:m" :kind :metric :title "M"
                                                  :value [{:v 1}]}})
    (let [ts (tbl/inline-table)
          r  (tm/migrate-tables! st ts {:live? true})]
      (is (= 1 (:tables r)) "nothing writes a :metric, so this is its only path out")
      (is (tbl/handle? (:value (sub/object st "met:m")))))))

(deftest a-refusal-does-not-stop-the-run
  ;; The shape the whole namespace exists for. `put-rows!` throws when two
  ;; column names normalize to one keyword (Task 6e); a run that died there
  ;; would leave every later table unmigrated and say nothing about which.
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:bad"
                     :value {:id "tbl:bad" :kind :table :title "B"
                             :value [{(keyword "a b") 1 :a_b 2}]}})
    (sub/commit! st {:op :put :id "tbl:good"
                     :value {:id "tbl:good" :kind :table :title "G" :value [{:v 1}]}})
    (let [ts (tbl/inline-table)
          r  (tm/migrate-tables! st ts {:live? true})]
      (is (= 1 (:tables r)) "the good table still migrated")
      (is (= ["tbl:bad"] (mapv :id (:refused r))))
      (is (re-find #"one column" (:why (first (:refused r)))))
      (is (vector? (:value (sub/object st "tbl:bad"))) "the refused table is untouched"))))

(deftest a-dry-run-writes-nothing-to-the-lake-either
  ;; Not the same assertion as `dry-run-writes-nothing`, which only checks the
  ;; log. `import-corpus!` passed that one while leaving 5 tables and 6
  ;; snapshots of orphan Parquet behind on every preview — and table GC is off
  ;; (Task 10), so orphans are never reclaimed. `InlineTable` has nowhere to
  ;; leak to, so this one has to run against a real lake.
  (lake-test/with-lake [lk]
    (let [st    (seeded)
          n     #(count (lake/q lk "SELECT table_name FROM ducklake_tables('lake')"))
          before (n)]
      (tm/migrate-tables! st lk)
      (is (= before (n)) "a dry run must not mint a lake table")
      (tm/migrate-tables! st lk {:live? true})
      (is (= (inc before) (n)) "and the live run must mint exactly one"))))
```

`with-lake` lives in `test/loci/lake_test.clj`; require that namespace for the macro, or lift it
into a shared test helper if requiring a test namespace from another reads badly to you — say
which you did.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

```clojure
(ns loci.tablemigrate
  "Convert inline tables — rows inside the event — into lake handles.

   Dry run by default, in the shape of `loci.factmigrate` and `loci.corpus`:
   it reports what it would do and refuses what it cannot account for, and a
   refusal comes back in the report rather than as a thrown exception, because
   a migration that dies halfway through tells you less than one that finishes
   and names what it skipped.

   Meant to be called from a REPL attached to the running server — way (2) of
   the three routes to storage. It appends ordinary `:assoc` events, so the
   conversion is itself undoable."
  (:require [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- inline-tables
  "Row-bearing objects whose rows are still in the log.

   Both `:table` and `:metric`: `chunks.clj` treats them as one kind, `hydrate`
   keys off the handle rather than the kind, and nothing writes a `:metric`, so
   filtering on `:table` alone would leave two objects inline forever with no
   path out."
  [st]
  (->> (sub/objects st) vals
       (filter #(#{:table :metric} (:kind %)))
       (remove #(tbl/handle? (:value %)))))

(defn- convert!
  "One table, or a refusal. Never throws.

   Returns `{:id … :rows n}` on success and `{:id … :why …}` otherwise. Each
   table is its own transaction and its own verdict, because a migration that
   dies on table 17 tells you less than one that finishes and names what it
   skipped — the shape `loci.factmigrate` and `loci.corpus` already use."
  [st ts o]
  (try
    (let [h (tbl/put-rows! ts (:id o) (:value o))]
      (sub/commit! st {:op :assoc :id (:id o) :path [:value] :value h})
      ;; verify against what is DURABLE, not against the rows we just held in
      ;; RAM — the 2026-08-15 migration reported "verified" while its target
      ;; silently lacked an event, because it compared to a stale in-process
      ;; snapshot.
      (let [back (tbl/rows ts (:value (sub/object st (:id o))) {})]
        (if (= (vec (:value o)) (vec back))
          {:id (:id o) :rows (count back)}
          {:id (:id o) :why "rows did not round-trip"
           :expected (count (:value o)) :got (count back)
           :first-diff (first (remove nil? (map (fn [a b] (when (not= a b) [a b]))
                                                (:value o) back)))})))
    (catch Exception e {:id (:id o) :why (.getMessage e)})))

(defn migrate-tables!
  "Report what would change; with `{:live? true}`, change it."
  ([st ts] (migrate-tables! st ts {}))
  ([st ts {:keys [live?]}]
   (let [cands (inline-tables st)
         {ok true empties false} (group-by #(boolean (seq (:value %))) cands)
         empties (mapv (fn [o] {:id (:id o) :why "no rows"}) empties)
         done    (when live? (mapv #(convert! st ts %) ok))
         bad     (filterv :why done)]
     {:mode    (if live? :live :dry-run)
      :tables  (if live? (- (count ok) (count bad)) (count ok))
      :rows    (reduce + 0 (map #(count (:value %)) ok))
      :kinds   (frequencies (map :kind cands))
      :refused (into empties bad)})))
```

Note what `convert!` does **not** do: it does not roll back a table whose rows failed to
round-trip. The `:assoc` is already committed at that point, so the object holds a handle whose
rows differ from what it had. That is the honest outcome — the rows are in the lake and readable,
they simply are not byte-identical — and `undo!` is right there if it matters. Rolling back would
mean a second write to repair a first, which is more machinery than a case measured at 0 of 31.
Say this in the docstring rather than leaving a reader to wonder.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Sabotage**

Change the verification to compare against `(:value o)` twice (i.e. compare RAM to RAM). Then
break `lake/rows` to return `[]`. `live-run-converts-and-verifies` must FAIL — if it passes,
the verification is verifying nothing, which is the exact defect this project has hit before.
Restore both.

- [ ] **Step 6: Run it for real**

```bash
cp -r data/substrate data/substrate.bak-$(date +%Y%m%d-%H%M%S)
```

Then in the REPL attached to the server, dry run first:

```clojure
(require '[loci.tablemigrate :as tm] '[loci.dlv :as dlv] '[loci.lake :as lake])
(tm/migrate-tables! @dlv/store @lake/lake)
```

Expected: `{:mode :dry-run :tables 31 :kinds {:table 29 :metric 2} :rows … :refused []}` —
confirm the count is 31 across both kinds. If it is not, **stop and report the number** rather
than proceeding.

**Confirm the dry run wrote nothing to the lake**, not merely nothing to the log — count lake
tables and snapshots before and after. A dry run that leaves Parquet behind is the bug Task 7
found in `import-corpus!`, and table GC is disabled (Task 10), so orphans never get reclaimed.

Then live:

```clojure
(tm/migrate-tables! @dlv/store @lake/lake {:live? true})
```

Report the actual returned map. Expect `tbl:extract-20-8413` in `:refused` with a round-trip
reason — its `Long`/`String` column comes back widened by `read_json_auto` — and treat any
*other* refusal as a finding to report rather than a result to accept.

- [ ] **Step 6b: Confirm what the migration cost and what it bought**

Three numbers, after the live run:

1. **Log size.** The printed length of the whole event history before and after. This is what
   the plan bought; Task 7 measured 88–279× per table on a fresh store, and this is the same
   measurement on real data.
2. **Chunk re-embeddings.** How many of the 31 stored chunk records now differ from what the
   code computes. All 31 were already stale from Task 6f, so the answer should still be 31 —
   not 62. If it is more than 31, something re-embedded twice and that is worth knowing.
3. **A read still works.** `mold-payload` on a migrated table, end to end, returning rows —
   because every other assertion in this plan has been about storage, and this is the one that
   says the thing a user looks at still arrives.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tablemigrate.clj test/loci/tablemigrate_test.clj
git commit -m "feat: convert inline tables to lake handles, dry-run by default"
```

---

## Task 10: Say that table GC is off

**Files:**
- Modify: `src/loci/lake.clj`
- Modify: `docs/` — the architecture doc that describes the data directory
- Test: `test/loci/lake_test.clj`

The spec decided table GC ships disabled, inheriting DuckLake's open snapshot-expiry bugs. The
failure mode to avoid is a sweep that *implies* it was complete. This task makes the condition
stated, the way `embedding-configured?` makes "semantic recall: off" a stated condition.

**Added 2026-08-16, measured during Task 5's review, and it is a stronger reason than the
upstream bugs.** Expiry is not merely buggy — it is *incompatible with how loci reads tables*.
Every handle reads `AT (VERSION => n)`, so a handle is a reference to a snapshot. Running
`ducklake_expire_snapshots(older_than => now())` followed by `ducklake_cleanup_old_files`
makes an existing handle's read throw:

> `Invalid Input Error: No snapshot found at version 1`

Reclaiming disk therefore invalidates stored handles across the log, and the day someone
enables GC to free space is the day every historical object stops rendering. This holds even
if #261/#527/#529 were all fixed tomorrow. `sweep!`'s docstring must say it: a working expiry
still needs loci to first decide which snapshots are still referenced by live handles, and
that machinery does not exist. Keep the upstream issue numbers — they are the reason it is off
*today* — and add this as the reason it cannot simply be switched on later.

- [ ] **Step 1: Write the test**

```clojure
(deftest table-gc-is-off-and-says-so
  (with-lake [lk]
    (let [r (lake/sweep! lk #{})]
      (is (= :disabled (:mode r)))
      (is (string? (:why r)))
      (is (re-find #"261|527|529" (:why r)) "the reason names the upstream issues"))))
```

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Implement**

```clojure
(defn sweep!
  "Table GC. Disabled, deliberately, and it reports that rather than returning
   a zero that reads like a completed sweep.

   DuckLake cannot expire the latest snapshot (duckdb/ducklake#261) — which is
   exactly the state an undo leaves — and snapshot expiry has open data-loss
   bugs (#527, #529). Blob GC has no such dependency and ships in its own plan,
   which means loci will for a while reclaim blob space and not table space.
   Saying so is the whole point of this function existing before it works."
  [_lk _live]
  {:mode :disabled
   :freed 0
   :why (str "table GC is off: DuckLake cannot expire the latest snapshot "
             "(duckdb/ducklake#261) and snapshot expiry has open data-loss bugs "
             "(#527, #529). Retention is: keep everything.")})
```

- [ ] **Step 4: Update the two docs that describe the data directory**

There is no single architecture doc; `data/`'s layout is described in two places, and both are
now wrong.

**`README.md`** — the `LOCI_DATA` section around line 85, and the Docker volume advice at
110-113 which tells the reader to `chown -R` because `/data/substrate` would otherwise stay
root-owned. That advice is still right and now also covers `/data/lake`; say so, since a
reader who chowns only `substrate` gets a working log and broken tables.

**`docs/superpowers/specs/2026-08-15-one-substrate-design.md`** — its opening describes `data/`
as "one database and two flat files beside it", and its resolution records the consolidation to
a single environment. Add a dated note that tables moved out to `data/lake` on 2026-08-16, with
the reason: DuckLake is a second store, and the "one directory is the whole backup" property
that the 2026-08-15 work bought is preserved because `lake/` sits inside `data/` — the property
was never "one database", it was one directory.

Both should show:

```
data/
  substrate/   LMDB — the log. Truth: metadata, structure, and handles.
  lake/        DuckLake catalog + Parquet — every table's rows.
```

And both should state that table GC is off, so nobody reads a growing `lake/` as a bug.

**Two more places say something that is now false. Grep before you write.**

- **`data/` is no longer one directory you can copy while the server runs.** Anything in
  `README.md` or the spec that describes backup as "copy `data/`" now spans two stores that are
  not written in one transaction: a `:put` lands in LMDB and the Parquet lands in the lake, and a
  copy taken between them has a handle pointing at a table that is not in the copy. This is a
  real change to a stated property and it needs saying plainly — the fix for a user is to stop
  the app first, which is route (3) in `CLAUDE.md` and costs nothing.
- **Anything that says the log holds a table's rows.** Task 7 made that false in eight places.
  `grep -rn "rows" README.md docs/superpowers/specs/2026-08-15-one-substrate-design.md` and check
  each hit.

**Record the numbers this branch measured**, in the spec note rather than the README — they are
the evidence for the design, and a later reader deciding whether to keep DuckLake will want them:

| | |
|---|---|
| log size, `tbl:corpus-1` (352 rows) | 66,959 → 302 chars (**222×**) |
| the `content/seed!` ten tables | 230,300 → 2,572 chars |
| lake open at boot (10M rows in the catalog) | 75 ms |
| versioned read, 10M rows | 34 ms |
| `restore-to!`, 10M rows | 182 ms |
| chunk sweep, warm | 15 ms (was 297 ms hydrating) |
| chunk sweep, cold, per process | ~1,060 ms |

- [ ] **Step 5: Sabotage**

Make `sweep!` return `{:freed 0}` with no `:mode`. The test must FAIL. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj docs/ test/loci/lake_test.clj
git commit -m "feat: table GC is off, and says which bugs are why"
```

---

## Task 11: metadata readers stop fetching rows to count them

**Files:** `src/loci/tablestore.clj`, `src/loci/tools.clj`, `src/loci/server.clj`;
tests in `tablestore_test.clj`, `tools_test.clj`, `server_test.clj`.

**Added 2026-08-17, after the live migration, and it is the one regression a user would feel.**

Measured against the migrated store (31 objects, 3,957 rows), median of 5–11 warm runs:

| | before migration | after |
|---|---|---|
| `mold-payload`, `tbl:orders` (1,000 rows) | 6.8 ms | **60.8 ms** |
| `tools/dispatch "list_tables"` | — | **266 ms** |
| `srv/agent-ctx` | — | **277 ms** |
| `ch/all-chunks` | 16 ms | 14 ms |

`all-chunks` is untouched because Task 6f already stopped it fetching rows. The other three are
all the same cost: **DuckDB's JDBC driver is slow row-wise.** Measured directly — walking 1,000×8
cells with `.getObject` and building *nothing* costs 42 ms, and `tbl/rows` costs 42 ms, so the
map-building is free and there is nothing to optimise in our own code. It is not the versioned
read either: `AT (VERSION => n)` and a bare `SELECT *` are both 43 ms, while `count(*)` is 3 ms.

`mold-payload` at 61 ms is fine — a user opening a table does not feel that. **`agent-ctx` at
277 ms is not**, because it runs on every chat turn, and it scales with the total rows in the
workspace rather than with the table being looked at.

**The fix is the one Task 6b deliberately deferred.** That task's note records it exactly:
metadata readers "want columns + row count… should read the handle's `:cols`/`:rows` without
fetching any rows at all", and the trade was recorded as acceptable because nobody had felt it.
The migration is what makes someone feel it.

A handle already carries `:cols` and `:rows`. These readers need nothing else.

- [ ] **Step 1: Write the failing tests**

```clojure
;; tablestore_test.clj
(deftest table-shape-costs-no-rows
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (is (= {:cols [:a :b] :rows 2} (tbl/table-shape ts {:kind :table :value h})))
    (is (= {:cols [:a :b] :rows 2}
           (tbl/table-shape ts {:kind :table :value [{:a 1 :b "p"} {:a 2 :b "q"}]}))
        "a legacy inline table answers the same question")
    (is (nil? (tbl/table-shape ts {:kind :doc :value "hi"})))))

(deftest table-shape-never-reads-rows
  (let [boom (reify tbl/TableStore
               (put-rows! [_ _ _] (throw (ex-info "no" {})))
               (rows [_ _ _] (throw (ex-info "table-shape fetched rows" {})))
               (row-count [_ h] (:rows h))
               (column-samples [_ _ _ _] (throw (ex-info "no" {}))))
        h {:store :lake :table "t_x" :snapshot 1 :rows 1000 :cols [:a :b]}]
    (is (= {:cols [:a :b] :rows 1000} (tbl/table-shape boom {:kind :table :value h}))
        "the handle already knows; nothing may be fetched")))
```

```clojure
;; tools_test.clj — the behaviour, not the helper
(deftest list-tables-reads-no-rows
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! (tbl/inline-table) "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (with-redefs [tbl/rows (fn [& _] (throw (ex-info "list_tables fetched rows" {})))]
      (let [r (first (tools/dispatch st "list_tables" {}))]
        (is (= ["a" "b"] (:columns r)))
        (is (= 2 (:rows r)))))))
```

Write the equivalent for `srv/agent-ctx`'s catalog — with `tbl/rows` stubbed to throw, building
the context for a store holding a handle-backed table must still name the table, its columns and
its true row count.

- [ ] **Step 2: Run to verify they fail**

Expected: `No such var: tbl/table-shape`, then the two stubbed-`rows` tests throwing.

- [ ] **Step 3: Implement**

```clojure
(defn table-shape
  "`{:cols [...] :rows n}` for a table object, or nil if it is not one.

   Reads the handle and fetches nothing. A handle recorded `:cols` and `:rows`
   when the rows were written, which is everything a catalog, a table list or a
   column menu needs — and DuckDB's JDBC driver is slow row-wise, so fetching
   them to count them is the difference between a 15 ms chat turn and a 277 ms
   one. Measured 2026-08-17 over the migrated store: `agent-ctx` 277 ms and
   `list_tables` 266 ms, against 42 ms to walk 1,000×8 cells for a single table.

   Falls back to counting a legacy inline vector, so a store that has not been
   migrated answers the same question at the same cost it always did."
  [ts o]
  (let [v (:value o)]
    (cond
      (handle? v)      {:cols (vec (cols-of-handle v)) :rows (row-count ts v)}
      (and (sequential? v) (seq v) (every? map? v))
      {:cols (vec (keys (first v))) :rows (count v)}
      :else nil)))
```

`cols-of-handle` is a placeholder for however you read `:cols` off a handle — use the real
accessor, and if there isn't one, `(:cols v)` is fine and clearer than inventing one.

- [ ] **Step 4: Convert the four metadata readers**

`tools.clj` `do-list`, and the three catalog fragments in `server.clj` (in `ask!`, `delegate!`
and `agent-ctx`) that read `(keys (first (:value o)))` and `(or (:table/total o) (count (:value o)))`.
Each becomes a `table-shape` call. **Do not touch the row readers** — `do-query`, `table-rows`,
`describe!`, `make-view!`, `compute-clj!`, `do!`, `obj-digest`, `recompute` and `mold-payload`
all genuinely need rows and must keep hydrating.

`obj-digest` is the judgement call: it prints a row count *and* a `table-digest` of sums and
sample rows, so it genuinely needs rows. **Leave it hydrating** — but check whether the objects
it is handed are hydrated once and shared with the catalog, and if so make sure your change does
not cause them to be hydrated twice.

`table-shaped?` (`server.clj`, used by `resolve-table-ref`) asks a boolean and can now answer it
with `table-shape`. Convert it; the plan's open-questions section has recorded that cost as
unfixed since Task 6b.

- [ ] **Step 5: Measure again**

Same three numbers, same method, against the migrated store. Report them beside the ones above.
Expected: `list_tables` and `agent-ctx` fall to roughly `all-chunks`' 14 ms; `mold-payload` is
**unchanged at ~61 ms**, because it genuinely needs the rows.

If `mold-payload` also drops, something is wrong — you have removed a fetch a reader needed.

- [ ] **Step 6: Sabotage**

Point `table-shape`'s handle branch at `rows` instead of the handle. Both "reads no rows" tests
must fail with the stub's exception. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/tablestore.clj src/loci/tools.clj src/loci/server.clj test/
git commit -m "perf: a catalog reads the handle, not the rows"
```

---

## Final verification

- [ ] **Both suites, from a cold JVM.** This is the one time `clojure -M:test` is right:

```bash
clojure -M:test
npm run test:browser
```

Record the actual counts. The baseline before this plan is **566 tests / 3664 assertions / 0
failures** (Clojure) and **130/130** (browser). A drop in either is a regression to explain,
not a number to update.

- [ ] **Measure what shipped, and write the numbers down.** The spec's measurements were taken
  against a bare DuckLake, not against loci. Report:
  - server boot time before and after the lake is wired in (expected: +~75 ms)
  - `mold-payload` latency for `tbl:orders` (1,000 rows) before and after — this is the one a
    user feels, and the only one where the lake could plausibly have made things *worse*
  - the migration's actual output map
  - `data/substrate` and `data/lake` on disk, before and after

- [ ] **Boot the app and look at it.** Every assertion in this plan is about storage. Start the
  server against the migrated store, open a migrated table and one seeded table, and confirm each
  renders with its viewers. Use the `run` skill if there is one. A green suite and a broken shell
  is the outcome this plan is most exposed to, because the browser suite runs against
  `content.clj`'s seed rather than against the lake.

- [ ] **Then:** `superpowers:finishing-a-development-branch`.

## What this plan does not answer

- **`query_table` reads hydrated rows** — after Task 6b; before it, it read the raw handle and
  returned the handle's own keys as data. So a query against a 10M-row table fetches `mold-cap`
  rows and computes over those — wrong, and it will stay wrong until the HoneySQL work lands.
  If any table in the substrate exceeds `mold-cap` before then, this becomes a live bug rather
  than a deferred one. Nothing does today; the largest is 1,000 rows. (This bullet originally
  asserted `query_table` already hydrated. It did not, and nothing had checked.)
- **Column type fidelity through JSON.** `read_json_auto` infers, and a column of mixed
  types will land as VARCHAR. The 29 existing tables come from `parse-csv`, which already
  coerces to long/double/string, so the risk is at ingest of new data rather than at migration.
  Worth a measurement if a table ever comes back with a column the viewers stop treating as
  numeric.
- ~~**The chunk sweep now reads the lake every 15 seconds.**~~ *Fixed in Task 6f — the synopsis
  comes from `column-samples` in SQL, memoized per handle, so the sweep reads no rows in steady
  state. The measurement below is kept because it is what motivated that task.*

  **The chunk sweep now reads the lake every 15 seconds.** `all-chunks` hydrates, and
  `start-chunk-sweep!` (`server.clj:2828`) calls `sweep!` on a 15 s daemon — before the
  `embedding-configured?` check, so it runs whether or not an embedder exists. Measured
  2026-08-16, 29 tables × 1,000 rows, median of 7: **790 ms** per pass handle-backed against
  **3.1 ms** inline and **0.33 ms** before hydration — ~27 ms per table, one versioned SELECT
  each, bounded by `mold-cap` rather than by table size (a 100k-row table costs 41 ms). On a
  fully migrated store the size of today's that is ~5.6% of one core, continuously, recomputing
  synopses that have not changed. Two ways out, neither built: bound the read (a synopsis needs
  ≤50 rows, not 2,000 — this would also fix the >`mold-cap` divergence in Task 6d), or cache by
  handle, which is sound because writes never overwrite so a given handle's rows are immutable.
  Measured and written down rather than fixed, because nothing is migrated yet and so nobody has
  felt it.
- **The browser suite is not evidence about table storage.** `npm run test:browser` runs against
  `content.clj`'s seed, which builds every object through its own local `obj` helper with inline
  rows — deliberately, so that requiring `loci.content` never needs a lake. So 130/130 green
  says nothing about whether a writer landed a handle. Found in Task 7. The seed should stay as
  it is; the property it protects is worth more than the coverage it costs. Cite `server_test`'s
  `every-server-writer-lands-a-handle` instead, which asserts `tbl/handle?` directly — a test
  that reads through `hydrate` **cannot** detect an unconverted writer, because `hydrate` passes
  literals through unchanged. That is the hole `rerun!` hid in.
- **Six places assume the first row carries every key.** Measured in Task 6e's review, and
  latent only because **0 of the 31 live tables are ragged**: `content.clj:169` (`numeric-cols`),
  `:177` (`string-cols`), `:229` (`timeseries?`), `:256`, `:320`, plus `viewspec.clj:42` and
  `server.clj:1430` (`table-digest`). The last two do not merely mis-report — they throw. A nil
  in a numeric column makes `viewspec`'s sort keyfn return a String for one row and a Long for
  the rest (`ClassCastException`), and `table-digest` does `(reduce + xs)` over a column
  containing nil (`NullPointerException`), which takes down `obj-digest` and with it the agent's
  whole context. Both throw identically for inline and lake, so they are pre-existing — but
  before Task 6e a ragged column was silently dropped from `:cols` and therefore unreachable,
  and now it is advertised to the UI and the agent. Reachable the moment an agent writes a
  ragged table through `save_table`.

  Curiously `content.clj`'s `summary` viewer is the one that gets *better*: it already computes
  `:missing` and its comment says holes are expected, so the lake's explicit nil repairs a
  first-row bug rather than causing one.
- **A namespaced column keyword collides on `name`.** `lake/ident` uses `(name k)`, so `{:a/b 1}`
  round-trips fine but `{:a/b 1 :c/b 2}` gives DuckDB `Invalid Input Error: … Object
  {"b":1,"b":2} has duplicate key "b"`, where `InlineTable` keeps both. It fails **loudly**, so
  a writer sees it rather than storing corruption. Recorded rather than fixed.
- **The cap now sits in front of three paths that *commit*.** `compute-clj!` (`server.clj:1124`
  → `:1162`), `fn-apply!` via `table-rows` (`:1188` → `:1244`) and `recompute` (`:1259` →
  `rerun!` at `:1306`) all build a derived table from hydrated rows and write it back. Over
  `mold-cap` that means a transform, a ƒ verb or a downstream refresh silently produces a table
  computed from the first 2,000 rows. Found in Task 6b's review; worse than the digest item
  below, and the same threshold. Inert today — largest table 1,000 rows against a cap of 2,000
  — and the real fix is the HoneySQL pushdown that would stop materializing rows to transform
  them at all.
- **What an agent digest of a huge table should say.** `obj-digest` (`server.clj:1416`) reports
  `(count v)` rows, which after hydration is the capped 2,000. Task 6b deliberately left it:
  `table-digest`'s sums and the three sample rows just below come from the same capped `v`, so
  substituting the true `:table/total` would print an accurate total above aggregates covering
  only 2,000 rows — a worse lie than a consistently capped digest. Deciding this properly means
  deciding what an agent should be told about a 10M-row table, and that belongs with whoever
  revisits `mold-cap`, not with a mechanical hydrate pass.
- **`table-shaped?` fetches rows to answer a boolean.** `server.clj:2247` hydrates — up to
  `mold-cap` rows — only to ask whether an object is a table, and `resolve-table-ref` can call
  it once per flow step. `tbl/handle?` would answer for free, but that is a handle branch, and
  Task 6b's rule was to add none. Left mechanical on purpose: the largest table is 1,000 rows,
  so nobody has felt this. It becomes worth fixing when someone does.
- **The cap is silent.** `hydrate` puts `:table/total` on the object, but `mold-payload` builds
  its return map key by key — `:id :title :kind :view :label :rendered :alternatives` — and
  drops it, so a capped table renders as though it were whole. Found in Task 6, left as Task 6
  specified. It is latent for the same reason as the `query_table` item above: the largest
  table is 1,000 rows against a cap of 2,000, so nothing is currently truncated. Whoever raises
  the first table past 2,000 rows has to plumb `:table/total` through to the shell in the same
  change, or the browser will quietly show a prefix and call it the table.

Nothing else. The concurrency question that would otherwise be here was measured rather than
deferred — see "One connection is enough" above.
