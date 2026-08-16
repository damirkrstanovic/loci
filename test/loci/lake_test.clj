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

(deftest the-lake-is-the-current-database
  ;; Not the same assertion as `ducklake-is-attached`, and the difference is
  ;; the whole point: `duckdb_databases()` lists every ATTACHed database
  ;; whether or not it is current, so it stays green with `USE lake` gone.
  ;; Without `USE lake` the current database is `memory`, DuckDB's transient
  ;; in-process catalog — every table this connection creates would land there
  ;; and be gone at close, with nothing in the lake and no error anywhere.
  ;; Every later task in this plan writes through this connection, so this one
  ;; line is what stops all of them persisting into nothing.
  (with-lake [lk]
    (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db")))))
