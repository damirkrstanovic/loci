(ns loci.lake
  "Tables live here. The substrate's log holds a handle; the rows are Parquet
   under a DuckLake catalog, and every read names the snapshot it wants.

   Measured 2026-08-16 (see the big-content spec): attaching a lake holding 10M
   rows costs 75 ms against loci's ~14 s dependency load, and a versioned read
   at 10M rows is 34 ms — faster than reading the current version, because an
   old snapshot carries no deletion vectors."
  (:require [clojure.java.io :as io]
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
  "LOAD first, INSTALL only if that fails — so that the one line which can reach
   the network is written down here, where a packaged build can hoist it to
   image-build time instead of a user's first boot.

   It is a signpost, not a guard, and the difference was measured on DuckDB
   1.5.5 (2026-08-16). `ATTACH 'ducklake:…'` loads the extension by itself:
   `duckdb_extensions()` reports ducklake unloaded immediately before the ATTACH
   and loaded immediately after, and it does this even with
   `autoload_known_extensions` and `autoinstall_known_extensions` both SET
   false. So nothing this function does is observable from outside, no test can
   be made to fail by breaking it, and it must not be relied on to keep an
   offline machine off the network — if that guarantee is ever wanted, it has to
   come from shipping the extension into the image, not from this call."
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
