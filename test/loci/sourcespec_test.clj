(ns loci.sourcespec-test
  (:require [clojure.test :refer [deftest is]]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]))

(deftest locate-resolves-loci-relative-to-the-data-dir
  (is (= (str (sub/data-dir) "/files/sales.csv")
         (ssp/locate "loci://files/sales.csv"))))

(deftest locate-passes-a-file-uri-through-as-a-path
  (is (= "/data/sales.csv" (ssp/locate "file:///data/sales.csv")))
  (is (= "/data/with space.csv" (ssp/locate "file:///data/with%20space.csv"))))

(deftest locate-refuses-a-loci-path-that-escapes-the-data-dir
  ;; loci:// MEANS "under the data dir" — that is what makes a substrate
  ;; portable between machines. A path that leaves it is a file:// URI, which
  ;; is unrestricted and needs no disguise.
  (doseq [bad ["loci://../etc/passwd" "loci://files/../../etc/passwd" "loci:///abs" "loci://"]]
    (is (thrown? clojure.lang.ExceptionInfo (ssp/locate bad)) (str "must refuse " bad))))

(deftest locate-names-the-phase-when-it-does-not-know-a-scheme
  (let [e (try (ssp/locate "s3://bucket/x.csv") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"loci://" (.getMessage e)) "the error must say what it DOES know")))

(deftest relation-builds-one-reader-call-per-format
  (is (= "read_csv('/tmp/a.csv')"
         (ssp/relation {:format :csv} "/tmp/a.csv")))
  (is (= "read_parquet('/tmp/a.parquet')"
         (ssp/relation {:format :parquet} "/tmp/a.parquet")))
  (is (= "read_json_auto('/tmp/a.json')"
         (ssp/relation {:format :json} "/tmp/a.json"))))

(deftest relation-orders-options-so-the-text-is-deterministic
  (is (= "read_csv('/tmp/a.csv', delim=';', header=true, sample_size=1024)"
         (ssp/relation {:format :csv :options {:sample_size 1024 :header true :delim ";"}}
                       "/tmp/a.csv"))))

(deftest relation-escapes-a-quote-in-the-location
  (is (= "read_csv('/tmp/o''brien.csv')"
         (ssp/relation {:format :csv} "/tmp/o'brien.csv"))))

(deftest relation-refuses-an-option-name-that-is-not-an-identifier
  ;; :options reach here from a JSON body, and an option NAME cannot be quoted
  ;; the way a value can — DuckDB wants a bare identifier. So it is checked.
  (doseq [bad ["header, x" "1=1" "" "a b" "he'ader"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ssp/relation {:format :csv :options {bad true}} "/tmp/a.csv"))
        (str "must refuse option name " (pr-str bad)))))

(deftest relation-refuses-an-option-value-it-cannot-render
  (is (thrown? clojure.lang.ExceptionInfo
               (ssp/relation {:format :csv :options {:columns {"a" "INT"}}} "/tmp/a.csv"))
      "a map is not a reader option value, and must not be interpolated raw"))

(deftest relation-refuses-an-unknown-format
  (let [e (try (ssp/relation {:format :xlsx} "/tmp/a.xlsx") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"csv" (.getMessage e)) "the error must list the formats it knows")))
