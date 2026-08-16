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
Readers are unchanged: one `hydrate` call at the top of `mold-payload` swaps the handle back
for rows, so viewers, `alternatives` and `viewspec` never learn that anything moved.

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

- [ ] **Step 8: Commit**

```bash
git add src/loci/tablestore.clj src/loci/tools.clj src/loci/server.clj src/loci/corpus.clj test/
git commit -m "feat: every table writer lands a handle, not rows"
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

## Task 9: Migrate the 29 inline tables

**Files:**
- Create: `src/loci/tablemigrate.clj`
- Test: `test/loci/tablemigrate_test.clj`

Shaped like `loci.factmigrate` and `loci.corpus`: **dry run by default**, refusals returned as
a report rather than thrown, and callable from a REPL attached to the live server — which is
way (2) of the three legitimate routes to storage in `CLAUDE.md`.

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
```

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

(defn- inline-tables [st]
  (->> (sub/objects st) vals
       (filter #(= :table (:kind %)))
       (remove #(tbl/handle? (:value %)))))

(defn migrate-tables!
  "Report what would change; with `{:live? true}`, change it."
  ([st ts] (migrate-tables! st ts {}))
  ([st ts {:keys [live?]}]
   (let [cands (inline-tables st)
         {ok true refused false} (group-by #(boolean (seq (:value %))) cands)
         refused (mapv (fn [o] {:id (:id o) :why "no rows"}) refused)]
     (when live?
       (doseq [o ok]
         (let [h (tbl/put-rows! ts (:id o) (:value o))]
           (sub/commit! st {:op :assoc :id (:id o) :path [:value] :value h})
           ;; verify against what is DURABLE, not against the rows we just held
           ;; in RAM — the 2026-08-15 migration reported "verified" while its
           ;; target silently lacked an event, because it compared to a stale
           ;; in-process snapshot.
           (let [back (tbl/rows ts (:value (sub/object st (:id o))) {})]
             (when-not (= (vec (:value o)) (vec back))
               (throw (ex-info "row mismatch after migration — stopping"
                               {:id (:id o) :expected (count (:value o)) :got (count back)})))))))
     {:mode    (if live? :live :dry-run)
      :tables  (count ok)
      :rows    (reduce + 0 (map #(count (:value %)) ok))
      :refused refused})))
```

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

Expected: `{:mode :dry-run :tables 29 :rows … :refused []}` — confirm the count is 29, which
is what the spec measured. If it is not 29, **stop and report the number** rather than
proceeding.

Then live:

```clojure
(tm/migrate-tables! @dlv/store @lake/lake {:live? true})
```

Report the actual returned map.

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

- [ ] **Step 5: Sabotage**

Make `sweep!` return `{:freed 0}` with no `:mode`. The test must FAIL. Restore.

- [ ] **Step 6: Commit**

```bash
git add src/loci/lake.clj docs/ test/loci/lake_test.clj
git commit -m "feat: table GC is off, and says which bugs are why"
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
  - `mold-payload` latency for `tbl:orders` (1,000 rows) before and after
  - the migration's actual output map

- [ ] **Then:** `superpowers:finishing-a-development-branch`.

## What this plan does not answer

- **`query_table` still reads hydrated rows**, so a query against a 10M-row table would fetch
  `mold-cap` rows and compute over those — wrong, and it will stay wrong until the HoneySQL
  work lands. If any table in the substrate exceeds `mold-cap` before then, this becomes a
  live bug rather than a deferred one. Nothing does today; the largest is 1,000 rows.
- **Column type fidelity through JSON.** `read_json_auto` infers, and a column of mixed
  types will land as VARCHAR. The 29 existing tables come from `parse-csv`, which already
  coerces to long/double/string, so the risk is at ingest of new data rather than at migration.
  Worth a measurement if a table ever comes back with a column the viewers stop treating as
  numeric.
- **The cap is silent.** `hydrate` puts `:table/total` on the object, but `mold-payload` builds
  its return map key by key — `:id :title :kind :view :label :rendered :alternatives` — and
  drops it, so a capped table renders as though it were whole. Found in Task 6, left as Task 6
  specified. It is latent for the same reason as the `query_table` item above: the largest
  table is 1,000 rows against a cap of 2,000, so nothing is currently truncated. Whoever raises
  the first table past 2,000 rows has to plumb `:table/total` through to the shell in the same
  change, or the browser will quietly show a prefix and call it the table.

Nothing else. The concurrency question that would otherwise be here was measured rather than
deferred — see "One connection is enough" above.
