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
