(ns loci.lake
  "Tables live here. The substrate's log holds a handle; the rows are Parquet
   under a DuckLake catalog, and every read names the snapshot it wants.

   Measured 2026-08-16 (see the big-content spec): attaching a lake holding 10M
   rows costs 75 ms against loci's ~14 s dependency load, and a versioned read
   at 10M rows is 34 ms — faster than reading the current version, because an
   old snapshot carries no deletion vectors."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- conn-of [lk] (:conn lk))

(defn x!
  "Execute one statement. Returns :ok."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))] (.execute st sql))
  :ok)

(defn- unwrap
  "A JDBC value as something the rest of loci can hold and serialize.

   `read_json_auto` widens a column holding more than one type to DuckDB `JSON`,
   and JDBC hands those cells back as `org.duckdb.JsonNode`. That class is not a
   Clojure value and not a `clojure.data.json` one: `json-resp` is `write-str`
   (`server.clj:44-47`), so a single such cell makes the whole object
   **unopenable in the shell** — measured on `tbl:csv-18-1542` round-tripped
   through a real lake:

     Don't know how to write JSON of class org.duckdb.JsonNode

   and `query_table` on it throws into the agent's tool loop. Three tables in
   the live substrate carry one: `tbl:csv-18-1542` (`:inflation_pct`,
   `:growth_pct`, `:unemployment_pct`), `tbl:csv-19-7071` (`:density_per_km2`)
   and `tbl:extract-20-8413` (`:approx_low_income_share`).

   A `JsonNode`'s `toString` is the JSON text of the value it holds, so parsing
   it back is lossless and none of the three needs refusing: `50` returns as
   `50` and `\"~13% (1 of 8 countries)\"` as that string. Refusing them was the
   remedy Task 9 planned; a storage layer handing its callers values they cannot
   serialize is the actual defect.

   **String keys for a genuinely nested JSON object**, which is `read-str`'s
   default and is deliberate. These keys are values DuckDB parsed out of user
   data, not loci column names — `sub/col-kw` normalizes columns and nothing
   normalizes these — and `:key-fn keyword` on `{\"odd key\": …}` mints `:odd
   key`, a keyword that does not read back. Measured 2026-08-16.

   **Two spellings of \"no value\", one result.** A SQL NULL never reaches here
   at all: JDBC returns Java `null` for it, JsonNode or not. A JSON `null`
   literal parses to `nil`. Both land on `nil`, which is what every other column
   type already gives."
  [v]
  (if (instance? org.duckdb.JsonNode v) (json/read-str (str v)) v))

(defn- rs->maps
  "Drain a ResultSet into row maps, naming column `i` with `(nth ks i)` and
   passing each value through `vf`. The only things `q` and `q-as` disagree
   about are where `ks` comes from and whether values are unwrapped."
  [rs ks vf]
  (loop [acc (transient [])]
    (if (.next rs)
      (recur (conj! acc (into {} (map-indexed (fn [i k] [k (vf (.getObject rs (inc i)))]) ks))))
      (persistent! acc))))

(defn q
  "Execute one query. Returns a vector of maps with keyword columns derived from
   the result set's own labels — which is the right guess when the caller has no
   idea what the columns are. A caller that does know names them itself; see
   `q-as`, and do not add an optional key vector here."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (let [md (.getMetaData rs)
          n  (.getColumnCount md)]
      ;; `identity`, not `unwrap`, on scope rather than on need: this is the
      ;; general helper over this namespace's OWN SQL — snapshot ids, table
      ;; names, the CAST-to-VARCHAR `column-samples` runs — and none of those
      ;; result sets has a JSON column in it, so there is nothing to unwrap.
      ;;
      ;; It is NOT that `column-samples` depends on seeing the JSON rendering.
      ;; That was written here and is false: measured 2026-08-17 by installing
      ;; `unwrap` in `q` and re-running the synopsis parity over the live
      ;; tables, the samples came back byte-identical. The quotes that reorder
      ;; them come from `CAST(… AS VARCHAR)`, which happens in SQL before any
      ;; value reaches JDBC.
      (rs->maps rs (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n)) identity))))

(defn- q-as
  "Like `q`, but names the result columns instead of deriving them from the
   result set's labels.

   `q` runs each JDBC label through `sub/col-kw`, which is right when the caller
   has no idea what the columns are. A handle does: it recorded `:cols` when the
   rows were written, and the SELECT names them in that order. Deriving them
   again would be lossy — `col-kw` leaves a readable keyword alone but
   lowercases and squashes a *string*, so `:tenure-band` goes in and
   `:tenure_band` comes back, and `:a-b` and `:a_b` come back as one column.

   It also `unwrap`s, because this is the path a table's ROWS come back on and
   they go straight to `json-resp`."
  [lk sql ks]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (rs->maps rs ks unwrap)))

(defn- ensure-extension!
  "LOAD first, INSTALL only if that fails. **Do not delete this.** It looks dead
   on any machine that has already run loci, and it is the only reason a machine
   that has not can open a lake at all.

   Measured twice on DuckDB 1.5.5 (2026-08-16), because the first measurement
   supported the opposite conclusion and was incomplete.

   What is true, and is what makes this look dead: `ATTACH 'ducklake:…'` LOADS
   the extension by itself. `duckdb_extensions()` reports ducklake unloaded
   immediately before an ATTACH and loaded immediately after, on a connection
   that issued neither LOAD nor INSTALL, and it does this even with
   `autoload_known_extensions` and `autoinstall_known_extensions` both SET false.
   So breaking this function changes nothing observable here, and the sabotage
   step that should have proven the tests bite could not.

   What is also true, and is why it stays: **ATTACH loads, but it does not
   install.** Pointed at an empty `extension_directory` — a machine that has
   never seen the extension — ATTACH fails outright with
   `IO Error: Extension \"…/ducklake.duckdb_extension\" not found`, and the
   catalog never opens. Running this function's own sequence by hand in that
   same state gives: LOAD fails, INSTALL succeeds, LOAD succeeds — and the
   ATTACH that follows then succeeds.

   So this is a guard after all, and the thing it guards is first run. The
   INSTALL is the one line here that can reach the network; a packaged build
   should hoist it to image-build time so a user's first boot does not, and
   until it does, an air-gapped first run cannot work no matter what this
   function says."
  [lk]
  (try (x! lk "LOAD ducklake")
       (catch Exception _
         (x! lk "INSTALL ducklake")
         (x! lk "LOAD ducklake"))))

(defrecord Lake [conn dir])

(defn open-lake!
  "Open (or create) the DuckLake catalog in `dir`. Defaults to <data-dir>/lake.

   **`OVERRIDE_DATA_PATH` is what makes the lake relocatable, and it is not
   optional.** DuckLake writes DATA_PATH into the catalog when the lake is
   created and compares it on every later attach; without the override, a lake
   opened from anywhere but the directory it was created in fails at boot with

     Invalid Configuration Error: DATA_PATH parameter \"/tmp/x/lake/files/\"
     does not match existing data path in the catalog \"data/lake/files/\".

   which breaks copying `data/`, restoring a backup, running the uberjar from
   another working directory, and mounting a locally-created `data/` into a
   container. loci owns the whole lake directory and the files are always at
   `<dir>/files/`, so the catalog's remembered path is never the authority —
   `dir` is.

   Measured 2026-08-17 on DuckDB 1.5.5, because \"override\" could mean several
   things and only one of them is safe:

   - It is a **per-session re-root, not a rewrite.** After attaching a moved
     lake with it, `ducklake_options('lake')` still reports the path the lake
     was created at. Nothing in the catalog is modified, so an override that
     turns out to be wrong is one attach, not a migration.
   - It is **total**, because every path under the catalog is stored relative:
     `ducklake_table.path` is `t_tbl_x/` and `ducklake_data_file.path` is the
     bare `ducklake-….parquet`. A 5,000-row table read back all 5,000 rows from
     a moved directory, and a write *after* the move landed its Parquet under
     the new root, not the old one.
   - It is **quiet** — no SQLWarning on the connection — and it is accepted on a
     brand-new catalog too, which still records the DATA_PATH it was given.

   The canonical path is the other half, and on its own it would be a
   regression rather than a fix: the live `data/lake` was created before this
   and has the relative `data/lake/files/` baked in, so canonicalizing without
   the override *refuses a store that used to open*. Measured — that is exactly
   the error above. What canonicalizing buys is that a new catalog records
   where it actually is, so `data_path` means something to someone reading the
   catalog with the duckdb CLI, instead of a bare `data/` that names a
   different directory from every working directory but one."
  ([] (open-lake! (str (sub/data-dir) "/lake")))
  ([dir]
   (io/make-parents (io/file dir "catalog.duckdb"))
   ;; The driver is instantiated directly rather than reached through
   ;; DriverManager: DriverManager cannot see a driver loaded by a child
   ;; classloader, which is how this namespace gets exercised from a REPL that
   ;; added the dep with `add-lib`. Direct instantiation works in both.
   (let [dir  (.getCanonicalPath (io/file dir))
         conn (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
         lk   (->Lake conn dir)]
     (try
       (ensure-extension! lk)
       (x! lk (str "ATTACH 'ducklake:" dir "/catalog.duckdb' AS lake"
                   " (DATA_PATH '" dir "/files/', OVERRIDE_DATA_PATH true)"))
       (x! lk "USE lake")
       lk
       (catch Throwable t (.close conn) (throw t))))))

(defn close! [lk] (when-let [c (conn-of lk)] (.close c)))

(defonce ^{:doc "The process's one lake at <data-dir>/lake.

   A delay, so requiring this namespace still opens nothing — the rule the
   whole codebase keeps, and the reason `loci.dlv/store` is shaped the same
   way. Nothing rebinds this: the tests never touch it, because `open-lake!`
   takes a directory and every test passes its own.

   **DuckDB refuses a second attach of one catalog file, process-wide**, the
   same way Datalevin refuses a second connection to one directory. Measured
   2026-08-16 — a second `open-lake!` on a directory this JVM already holds
   fails with

     Binder Error: Failed to attach DuckLake MetaData
     \"__ducklake_metadata_lake\" at path + \"…/catalog.duckdb\"Unique file handle
     conflict: Cannot attach \"__ducklake_metadata_lake\" - the database file
     \"…/catalog.duckdb\" is already attached by database
     \"__ducklake_metadata_lake\"

   which is why the laziness matters more here than it does for chunks or the
   memory: forcing this delay in a process that already holds the lake is not
   an unwanted file, it is a hard failure. So there is one handle and everyone
   shares it — which loci can do because it is single-writer."}
  lake
  (delay (open-lake!)))

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

(defn- current-snapshot
  "The snapshot id the lake's write head is at.

   Catalog-wide, not per-table: a DuckLake snapshot is one transaction against
   the whole lake, so this is the max over every table, and two `put-rows!`
   running concurrently can hand the first one a snapshot the second minted.

   Measured 2026-08-16, and benign **only because writes never overwrite**: a
   table read at a snapshot later than the one that created it returns exactly
   the same rows, because nothing between the two touched it. Interleaved
   `tbl:a`/`tbl:b` writes, then reading `a` at `b`'s snapshot, gave `[{:v 1}]`
   either way.

   That stops being true the day a table is mutated in place — a handle stamped
   with a later snapshot would then read another transaction's edits as its own.
   Whoever adds in-place mutation has to stamp the snapshot the write itself
   produced rather than the catalog's high-water mark. See `restore-to!`, which
   is the other half of that same day's work."
  [lk]
  (:snapshot_id (first (q lk "SELECT max(snapshot_id) AS snapshot_id FROM ducklake_snapshots('lake')"))))

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
   was slower, had four change types to get right, and is not here.

   **Unconditional — do not call it speculatively.** It rewrites the table
   whether or not the version asked for is the one already current, and each
   rewrite is a snapshot and a Parquet file. Measured: three restores to the
   current version took the catalog from 2 snapshots and 1 file to 5 and 4, for
   one 1000-row table. The files staying is correct — older versions still read
   through them — so the caller is the one that has to know it needs this."
  [lk table snapshot]
  (x! lk (str "CREATE OR REPLACE TABLE " table
              " AS SELECT * FROM " table " AT (VERSION => " (long snapshot) ")"))
  :ok)

(defn dropped-at
  "When `table` was dropped from this lake, or nil if it was never dropped.

   The catalog is the record, so nothing here keeps a parallel one. DuckLake
   sets `ducklake_table.end_snapshot` on a drop and `ducklake_snapshots` carries
   that snapshot's time, so \"was this reclaimed, and when\" is a question the
   store that did the reclaiming can already answer.

   Returns nil for a name that was never a table at all — absent is not the same
   as reclaimed, and a read that fails for any other reason must stay an error
   rather than being reported to a user as a successful cleanup.

   Note the metadata tables are invisible to `duckdb_tables()` and must be named
   in full.

   The `end_snapshot IS NOT NULL` clause is **explicit, not load-bearing**, and
   is worth knowing before anyone tries to prove it matters: the inner join
   already excludes a live table, because `end_snapshot` is NULL for one and
   `NULL = s.snapshot_id` is never true. Measured 2026-08-17 — deleting the
   clause changes no result, so a sabotage of it cannot bite. What is
   load-bearing is the column (`begin_snapshot` in its place reports a live
   table's *creation* time as a drop) and the `table_name` filter (without it a
   live table inherits a sibling's drop time). Both of those sabotages fail the
   tests."
  [lk table]
  (some-> (first (q lk (str "SELECT s.snapshot_time AS t"
                            "  FROM __ducklake_metadata_lake.main.ducklake_table dt"
                            "  JOIN ducklake_snapshots('lake') s"
                            "    ON s.snapshot_id = dt.end_snapshot"
                            " WHERE dt.table_name = '" table "'"
                            "   AND dt.end_snapshot IS NOT NULL"
                            " ORDER BY dt.end_snapshot DESC LIMIT 1")))
          :t
          (.toInstant)
          (java.util.Date/from)))

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

(def ^:private sample-cache
  "Samples for a `[table snapshot]`, computed once.

   `memoize` rather than a hand-rolled atom, and sound rather than merely
   convenient: a handle is immutable, because `put-rows!` always creates a new
   lake table and nothing overwrites one. So a hit is not a stale answer, it is
   the only answer.

   That is the same invariant `current-snapshot` above depends on, and it stops
   holding on the same day: **whoever adds in-place mutation has to invalidate
   this**. `restore-to!` is already a `CREATE OR REPLACE` and is the closest
   thing to a counter-example, but it is not one — measured 2026-08-16, a
   cached answer still matches a fresh one across an `UPDATE` and a
   `restore-to!`, because `AT (VERSION => n)` resolves the handle's own
   snapshot and the rewrite only moves the write head.

   Unbounded, and that is affordable *because the clip happens in SQL rather
   than downstream*. An entry is at most `max-cols` × `per-col` values of
   `tbl/sample-max-chars` code points — 24 × 3 × 41 ≈ 3 KB, or double that if
   every code point is astral. Measured 2026-08-16 over the 31 live tables: a
   median entry of 117 characters and a maximum of 879 (1,113 bytes of EDN). A
   table with a 5,000-character text column, which without the clip would put
   15 KB in one entry, produces 123 characters.

   It retains more than the samples: the key holds the `Lake` it was asked
   about, so an entry pins a JDBC connection. In the server there is one lake
   and so one reference; a test run that opens many leaks one each until the
   var is rebound. Measure before replacing this with an eviction policy."
  (memoize
   (fn [lk table snapshot cols per-col]
     (into {} (for [c cols]
                [c (mapv #(str (first (vals %)))
                         ;; Clipped BEFORE the DISTINCT, matching
                         ;; `tbl/clip-sample`: two values sharing a 40-point
                         ;; prefix are one sample, not two that print alike.
                         (q lk (str "SELECT DISTINCT CASE WHEN length(s) > " tbl/sample-max-chars
                                    " THEN substr(s, 1, " tbl/sample-max-chars ") || '…'"
                                    " ELSE s END AS v FROM ("
                                    "SELECT CAST(" (ident c) " AS VARCHAR) AS s"
                                    " FROM " table " AT (VERSION => " (long snapshot) ")"
                                    " WHERE " (ident c) " IS NOT NULL)"
                                    " ORDER BY v LIMIT " (long per-col))))])))))

(extend-type Lake
  tbl/TableStore
  (put-rows! [lk id rs]
    (let [rs    (tbl/normalize-rows (vec rs))
          cols  (tbl/cols-of rs)
          table (table-name id (System/nanoTime))]
      (when (empty? rs)
        (throw (ex-info "refusing to store a table with no rows" {:id id})))
      (load-rows! lk table cols rs)
      {:store :lake :table table :snapshot (current-snapshot lk)
       :rows (count rs) :cols cols}))

  (rows [lk h {:keys [offset limit]}]
    (q-as lk (str "SELECT " (select-cols (:cols h))
                  " FROM " (:table h) " AT (VERSION => " (long (:snapshot h)) ")"
                  (when limit  (str " LIMIT "  (long limit)))
                  (when offset (str " OFFSET " (long offset))))
          (:cols h)))

  (row-count [_ h] (:rows h))

  (column-samples [lk h per-col max-cols]
    (sample-cache lk (:table h) (:snapshot h) (vec (take max-cols (:cols h))) per-col)))

(defn sweep!
  "Table GC. Disabled, deliberately, and it reports that rather than returning
   a zero that reads like a completed sweep.

   Two reasons, and the second is the load-bearing one.

   **Today: DuckLake's expiry is not safe to run.** It cannot expire the latest
   snapshot (duckdb/ducklake#261) — which is exactly the state an undo leaves —
   and snapshot expiry has open data-loss bugs (#527, #529).

   **After those are fixed: expiry is still incompatible with how loci reads a
   table.** Every handle reads `AT (VERSION => n)`, so a handle is a *reference
   to a snapshot*, and the log keeps handles forever — an undone `:put` still
   names the table it wrote. Measured 2026-08-16 during Task 5's review:
   `ducklake_expire_snapshots(older_than => now())` followed by
   `ducklake_cleanup_old_files` makes an existing handle's read throw

     Invalid Input Error: No snapshot found at version 1

   So reclaiming disk invalidates stored handles *across the whole log*, and the
   day someone enables GC to free space is the day every historical object stops
   rendering. A working expiry is therefore not enough: loci would first have to
   decide which snapshots are still referenced by live handles — walk the log,
   collect every `{:store :lake}` value, keep those versions — and that
   machinery does not exist. `live` is the argument it would take.

   Blob GC has no such dependency and ships in its own plan, which means loci
   will for a while reclaim blob space and not table space. Retention here is:
   keep everything. Saying so is the whole point of this function existing
   before it works — the same job `embedding-configured?` does for semantic
   recall, and the failure it avoids is a sweep that *implies* it was complete."
  [_lk _live]
  {:mode :disabled
   :freed 0
   :why (str "table GC is off: DuckLake cannot expire the latest snapshot "
             "(duckdb/ducklake#261) and snapshot expiry has open data-loss bugs "
             "(#527, #529) — and even fixed, expiring a snapshot invalidates "
             "every handle that reads AT (VERSION => it), which loci does not "
             "yet track. Retention is: keep everything.")})
