(ns loci.source-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.source :as src]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

;; `with-temp-lake` rather than `with-lake`, from the first test: it REBINDS
;; `lake/lake`, and a later task's `source/connect!` reads that var. A local
;; lake would leave those tests writing to `<data-dir>/lake`.
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
