(ns loci.fnlib-test
  (:require [clojure.test :refer [deftest is]]
            [loci.fnlib :as fnl]))

(def rows [{:region "EMEA" :revenue 100 :units 5}
           {:region "APAC" :revenue 250 :units 2}
           {:region "EMEA" :revenue 50  :units 9}])

(deftest column-helpers
  (is (= [:revenue :units] (fnl/numeric-cols rows)))
  (is (= [:region] (fnl/cat-cols rows))))

(deftest filter-numeric-and-string
  (is (= [{:region "APAC" :revenue 250 :units 2}]
         (:rows (fnl/run-fn "lib:filter" rows {:col "revenue" :op ">" :value "150"}))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "EMEA"})))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "contains" :value "em"})))))
  ;; zero matches is an honest error, not an empty table in the substrate
  (is (:error (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "MARS"}))))

(deftest unknown-function-errors
  (is (:error (fnl/run-fn "lib:nope" rows {}))))

(deftest group-aggregates
  (let [out (:rows (fnl/run-fn "lib:group" rows {:by "region" :measure "revenue" :agg "sum"}))]
    (is (= 2 (count out)))
    (is (= 150 (:sum_revenue (first (filter #(= "EMEA" (:region %)) out))))))
  (let [out (:rows (fnl/run-fn "lib:group" rows {:by "region" :measure "revenue" :agg "count"}))]
    (is (= 2 (:count_revenue (first (filter #(= "EMEA" (:region %)) out)))))))

(deftest top-n-sorts-and-takes
  (is (= [250 100] (map :revenue (:rows (fnl/run-fn "lib:top" rows {:by "revenue" :n "2" :order "desc"})))))
  (is (= [50] (map :revenue (:rows (fnl/run-fn "lib:top" rows {:by "revenue" :n "1" :order "asc"}))))))

(def rows2 [{:region "EMEA" :channel "web"    :revenue 100}
            {:region "EMEA" :channel "retail" :revenue 40}
            {:region "APAC" :channel "web"    :revenue 250}])

(deftest pivot-goes-wide
  (let [out (:rows (fnl/run-fn "lib:pivot" rows2 {:rows_col "region" :cols_col "channel"
                                                  :measure "revenue" :agg "sum"}))
        emea (first (filter #(= "EMEA" (:region %)) out))]
    (is (= 2 (count out)))
    (is (= 100 (:web emea)))
    (is (= 40 (:retail emea)))))

(deftest delta-adds-change-columns
  (let [ts [{:month "Jan" :mrr 100} {:month "Feb" :mrr 110}]
        out (:rows (fnl/run-fn "lib:delta" ts {:col "mrr"}))]
    (is (nil? (:delta (first out))))
    (is (= 10 (:delta (second out))))
    (is (= 10.0 (:pct_change (second out))))))

(deftest share-of-total
  (let [out (:rows (fnl/run-fn "lib:share" rows {:col "revenue"}))]
    (is (< 99.9 (reduce + (keep :share_pct out)) 100.1))  ; shares sum to 100
    (is (= 25.0 (:share_pct (first out))))))              ; 100 of 400

(deftest group-min-max-avg
  (let [g (fn [agg] (:rows (fnl/run-fn "lib:group" rows {:by "region" :measure "revenue" :agg agg})))
        emea (fn [out] (first (filter #(= "EMEA" (:region %)) out)))]
    (is (= 50 (:min_revenue (emea (g "min")))))
    (is (= 100 (:max_revenue (emea (g "max")))))
    (is (= 75.0 (:avg_revenue (emea (g "avg")))))))

(deftest group-survives-missing-or-mixed-measure
  ;; missing measure column: an honest nil aggregate, never JVM jargon
  (let [out (fnl/run-fn "lib:group" rows {:by "region" :measure "nope" :agg "min"})]
    (is (nil? (:error out)))
    (is (nil? (:min_nope (first (:rows out))))))
  ;; mixed-type column: non-numbers are ignored, not a ClassCastException
  (let [mixed [{:g "a" :v 10} {:g "a" :v "n/a"} {:g "b" :v 5}]]
    (is (= 10 (:sum_v (first (filter #(= "a" (:g %))
                                     (:rows (fnl/run-fn "lib:group" mixed {:by "g" :measure "v" :agg "sum"})))))))
    (is (nil? (:error (fnl/run-fn "lib:top" mixed {:by "v" :n "2" :order "desc"}))))))

(deftest pivot-count-counts-rows-and-names-nil-cells
  (let [cells [{:region "EMEA" :channel "web"}
               {:region "EMEA" :channel "web" :revenue 5}
               {:region "EMEA" :revenue 7}]
        out (:rows (fnl/run-fn "lib:pivot" cells {:rows_col "region" :cols_col "channel"
                                                  :measure "revenue" :agg "count"}))
        emea (first out)]
    (is (= 2 (:web emea)))                  ; count counts rows per cell, like lib:group
    (is (= 1 (get emea (keyword "n/a")))))) ; nil category gets a readable name, not :

(deftest share-handles-negative-totals
  (let [out (:rows (fnl/run-fn "lib:share" [{:x -100} {:x -300}] {:col "x"}))]
    (is (= 25.0 (:share_pct (first out))))
    (is (= 75.0 (:share_pct (second out))))))

(deftest catalog-greys-with-reasons-and-fills-options
  (let [profile {:cols [:region :revenue :units] :numeric [:revenue :units]
                 :categorical [:region] :rows 3}      ; has numeric + ONE cat col
        cat (fnl/catalog profile)
        by-id (into {} (map (juxt :id identity)) cat)]
    (is (:ok (by-id "lib:filter")))
    (is (not (:ok (by-id "lib:pivot"))))             ; profile has ONE cat col
    (is (= "needs two category columns" (:why (by-id "lib:pivot"))))
    ;; col params carry the profile's column options for the UI selects
    (let [p (first (filter #(= "by" (:name %)) (:params (by-id "lib:top"))))]
      (is (= ["revenue" "units"] (:options p)))))
  (let [prose {:cols [:note] :numeric [] :categorical [] :rows 2}
        by-id (into {} (map (juxt :id identity)) (fnl/catalog prose))]
    (is (= "needs a numeric column" (:why (by-id "lib:top"))))))

;; ---------------------------------------------------------------------------
;; catalog takes a profile — {:cols :numeric :categorical :rows} — never rows.
;; See loci.tablestore/column-profile, which answers it without fetching one.
;; ---------------------------------------------------------------------------

(def profile
  "What `catalog` needs, and all it needs: column names, which are numeric,
   which are categorical, and how many rows there are. No row is a member."
  {:cols [:region :revenue :name] :numeric [:revenue] :categorical [:region] :rows 2500})

(deftest catalog-takes-a-profile-not-rows
  (let [c (fnl/catalog profile)
        by-id (into {} (map (juxt :id identity)) c)]
    (is (= 6 (count c)))
    (is (= ["region" "revenue" "name"]
           (:options (first (filter #(= "col" (:type %))
                                    (:params (by-id "lib:filter")))))))
    (is (= ["revenue"]
           (:options (first (filter #(= "numcol" (:type %))
                                    (:params (by-id "lib:group")))))))))

(deftest a-verb-is-greyed-from-the-profile
  (let [by-id (into {} (map (juxt :id identity)) (fnl/catalog (assoc profile :numeric [])))]
    (is (false? (:ok (by-id "lib:group"))))
    (is (= "needs a numeric column" (:why (by-id "lib:group"))))))

(deftest pivot-needs-two-category-columns-and-asks-the-profile
  (let [one (into {} (map (juxt :id identity)) (fnl/catalog profile))
        two (into {} (map (juxt :id identity))
                  (fnl/catalog (assoc profile :categorical [:region :name])))]
    (is (false? (:ok (one "lib:pivot"))) "one category column is not two")
    (is (true?  (:ok (two "lib:pivot"))))))

(deftest delta-asks-the-row-count-not-a-sample
  (let [by-id #(into {} (map (juxt :id identity)) (fnl/catalog %))]
    (is (false? (:ok ((by-id (assoc profile :rows 1)) "lib:delta"))))
    (is (true?  (:ok ((by-id profile) "lib:delta"))))))
