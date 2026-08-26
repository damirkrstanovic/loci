(ns loci.honeyq-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [loci.honeyq :as hq]))

(def R
  "A relation that stands in for a handle, in the shape `loci.lake/relation-of`
   produces: the versioned table wrapped in a subquery so it can be aliased."
  {:select [:*] :from [[[:raw "\"t_a\" AT (VERSION => 3)"]]]})

(defn- fmt [spec] (first (sql/format (hq/->honey spec R) {:inline true})))

(def ^:private FROM "FROM (SELECT * FROM \"t_a\" AT (VERSION => 3)) AS t")

(deftest a-bare-spec-selects-everything
  (is (= (str "SELECT * " FROM) (fmt {}))))

(deftest group-by-becomes-group-by
  (is (= (str "SELECT region, SUM(revenue) AS revenue " FROM " GROUP BY region")
         (fmt {:group_by "region" :measure "revenue" :agg "sum"}))))

(deftest count-does-not-need-a-measure
  (is (= (str "SELECT region, COUNT(*) AS count " FROM " GROUP BY region")
         (fmt {:group_by "region" :agg "count"}))))

(deftest min-and-max-are-aggregates
  (is (str/includes? (fmt {:group_by "region" :measure "v" :agg "min"}) "MIN(v)"))
  (is (str/includes? (fmt {:group_by "region" :measure "v" :agg "max"}) "MAX(v)")))

(deftest an-unknown-agg-falls-back-to-sum
  (is (str/includes? (fmt {:group_by "region" :measure "v" :agg "median"}) "SUM(v)")))

(deftest filter-becomes-a-where
  (is (str/includes? (fmt {:filter {:col "v" :op ">" :val 10}}) "WHERE v > 10")))

(deftest not-equal-becomes-a-where
  (is (str/includes? (fmt {:filter {:col "region" :op "≠" :val "r1"}})
                     "WHERE region <> 'r1'")))

(deftest contains-becomes-a-case-insensitive-like
  (let [s (fmt {:filter {:col "region" :op "contains" :val "R1"}})]
    (is (str/includes? s "LIKE"))
    (is (str/includes? s "'%r1%'"))
    (is (str/includes? s "LOWER"))))

(deftest sort-and-limit-land
  (let [s (fmt {:group_by "region" :measure "v" :agg "sum" :sort "desc" :limit 5})]
    (is (str/includes? s "ORDER BY v DESC"))
    (is (str/includes? s "LIMIT 5"))))

(deftest the-relation-is-never-a-bare-raw
  ;; The 2026-08-27 measurement gate found this: `:from [relation]` (dropping
  ;; the `[[relation :t]]` nesting that names the subquery alias) silently
  ;; drops the alias entirely instead of raising. `(not (includes? ... "FROM
  ;; raw"))` alone does not catch that — the sabotaged output never contains
  ;; the literal string "raw", it just comes out unaliased — so this also
  ;; asserts the alias is present.
  (is (not (str/includes? (fmt {}) "FROM raw")))
  (is (str/includes? (fmt {}) "AS t")))

(deftest counting-wraps-the-whole-query
  (is (= (str "SELECT COUNT(*) AS n FROM (SELECT region, SUM(revenue) AS revenue "
              FROM " GROUP BY region) AS c")
         (first (sql/format (hq/->count (hq/->honey {:group_by "region" :measure "revenue"} R))
                            {:inline true})))))
