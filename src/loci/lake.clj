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

(defn- rs->maps
  "Drain a ResultSet into row maps, naming column `i` with `(nth ks i)`. The
   only thing `q` and `q-as` disagree about is where `ks` comes from."
  [rs ks]
  (loop [acc (transient [])]
    (if (.next rs)
      (recur (conj! acc (into {} (map-indexed (fn [i k] [k (.getObject rs (inc i))]) ks))))
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
      (rs->maps rs (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n))))))

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
    (rs->maps rs ks)))

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
  "Open (or create) the DuckLake catalog in `dir`. Defaults to <data-dir>/lake."
  ([] (open-lake! (str (sub/data-dir) "/lake")))
  ([dir]
   (io/make-parents (io/file dir "catalog.duckdb"))
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

(defonce ^{:doc "The process's one lake at <data-dir>/lake.

   A delay, so requiring this namespace still opens nothing — the rule the
   whole codebase keeps, and the reason `loci.dlv/store` is shaped the same
   way. Nothing rebinds this: the tests never touch it, because `open-lake!`
   takes a directory and every test passes a temp one.

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
    (let [rs    (vec rs)
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
