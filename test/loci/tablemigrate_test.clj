(ns loci.tablemigrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :refer [with-lake]]
            [loci.substrate :as sub]
            [loci.tablemigrate :as tm]
            [loci.tablestore :as tbl]))

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

(deftest a-metric-is-migrated-and-stays-a-metric
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "met:m" :value {:id "met:m" :kind :metric :title "M"
                                                  :value [{:v 1}]}})
    (let [ts (tbl/inline-table)
          r  (tm/migrate-tables! st ts {:live? true})]
      (is (= 1 (:tables r)))
      (is (tbl/handle? (:value (sub/object st "met:m"))))
      (is (= :metric (:kind (sub/object st "met:m")))
          "table-object would have restamped this as a :table"))))

(deftest a-refusal-does-not-stop-the-run
  ;; The colliding key is the STRING `"a b"`, not `(keyword "a b")`. A keyword
  ;; key cannot carry a collision into a stored object at all: `normalize-keys`
  ;; postwalks every committed event and runs `col-kw` over keyword keys, so
  ;; `{(keyword "a b") 1 :a_b 2}` is already `{:a_b 2}` by the time it is in the
  ;; log and there is nothing left for `normalize-rows` to refuse. `col-kw` is
  ;; applied only `(cond-> k (keyword? k) …)`, so a string key survives the log
  ;; intact and still collapses onto `:a_b` in `cols-of`.
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:bad"
                     :value {:id "tbl:bad" :kind :table :title "B"
                             :value [{"a b" 1 :a_b 2}]}})
    (sub/commit! st {:op :put :id "tbl:good"
                     :value {:id "tbl:good" :kind :table :title "G" :value [{:v 1}]}})
    (let [ts (tbl/inline-table)
          r  (tm/migrate-tables! st ts {:live? true})]
      (is (= 1 (:tables r)) "the good table still migrated")
      (is (= ["tbl:bad"] (mapv :id (:refused r))))
      (is (vector? (:value (sub/object st "tbl:bad"))) "the refused table is untouched"))))

(deftest a-report-is-never-migrated
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "report:r"
                     :value {:id "report:r" :kind :report
                             :value [{:block :table :rows [{:a 1}]}]}})
    (let [r (tm/migrate-tables! st (tbl/inline-table) {:live? true})]
      (is (= 0 (:tables r)) "filter by kind, never by shape"))))

(deftest widening-is-reported-and-a-real-difference-is-not
  ;; `read_json_auto` widens a column holding `100` beside `43.2` to DOUBLE, so
  ;; `100` reads back `100.0`. That is a real change to the data and is named
  ;; rather than hidden — but it is not a reason to leave a table inline
  ;; forever. What the comparison must NOT do is call two different numbers
  ;; equal, which a blanket `double` coercion would above 2^53.
  (is (@#'tm/widened-eq? [{:a 100 :b 43.2}] [{:a 100.0 :b 43.2}])
      "an integer that became a float, exactly")
  (is (not (@#'tm/widened-eq? [{:a 100}] [{:a 100.5}]))
      "a value that actually changed")
  (is (not (@#'tm/widened-eq? [{:a 9007199254740993}] [{:a 9.007199254740992E15}]))
      "a long that lost precision on the way to a double is NOT widening")
  (is (not (@#'tm/widened-eq? [{:a 1}] [{:a 1 :b nil}]))
      "a ragged table reading back with an explicit nil is not widening")
  (is (not (@#'tm/widened-eq? [{:a 1}] [{:a 1} {:a 2}])) "row counts differ"))

(deftest a-dry-run-writes-nothing-to-the-lake-either
  ;; `dry-run-writes-nothing` only checks the log. `import-corpus!` passed that
  ;; one while leaving 5 tables and 6 snapshots of orphan Parquet behind on
  ;; every preview, and table GC is off (Task 10), so orphans never get
  ;; reclaimed.
  (with-lake [lk]
    (let [st (seeded)
          ;; `duckdb_tables()` filtered to the lake catalog. `ducklake_tables`
          ;; is not a function this DuckLake ships — only `ducklake_snapshots`
          ;; and `ducklake_table_changes` exist.
          n  #(count (lake/q lk "SELECT table_name FROM duckdb_tables() WHERE database_name = 'lake'"))
          snaps #(:n (first (lake/q lk "SELECT count(*) AS n FROM ducklake_snapshots('lake')")))
          before (n)
          before-snaps (snaps)]
      (tm/migrate-tables! st lk)
      (is (= before (n)) "a dry run must not mint a lake table")
      (is (= before-snaps (snaps)) "nor a snapshot")
      (tm/migrate-tables! st lk {:live? true})
      (is (= (inc before) (n)) "and the live run must mint exactly one"))))
