(ns loci.viewspec-test
  "What `question` and `interpret` each own, pinned.

   The split landed on 2026-08-27 so `InlineTable/query` and the render path
   share ONE implementation of the spec language. These tests are what notice if
   they ever become two."
  (:require [clojure.test :refer [deftest is]]
            [loci.viewspec :as vs]))

(def rows (vec (for [n (range 30)] {:region (str "r" (mod n 3)) :v (double n)})))

(defn- group-row
  "The grouped row for `g`. By value, not by position — `group-by` promises no
   ordering, so `(first …)` would be testing the map implementation."
  [out g]
  (first (filter #(= g (get % "region")) out)))

(deftest question-does-not-limit
  (is (= 30 (count (vs/question {:limit 5} rows)))
      "question returns the whole answer; the caller limits, because it needs the count"))

(deftest interpret-still-limits
  (is (= 5 (count (:rendered (vs/interpret {:kind "table" :limit 5} rows))))))

(deftest interpret-and-question-agree-on-the-question
  ;; The drift detector. If interpret stops calling question, the language has
  ;; two implementations and they will diverge silently.
  (doseq [spec [{:group_by "region" :measure "v" :agg "sum"}
                {:filter {:col "region" :op "=" :val "r0"}}
                {:group_by "region" :measure "v" :agg "sum" :sort "desc"}]]
    (is (= (vs/question spec rows) (:rendered (vs/interpret spec rows)))
        (str "one implementation of the language, not two — " (pr-str spec)))))

(deftest grouped-rows-carry-string-keys
  ;; Not an accident to be tidied: interpret has always rendered these and the
  ;; shell reads them. InlineTable/query normalises to keywords one layer up.
  (let [out (vs/question {:group_by "region" :measure "v" :agg "sum"} rows)]
    (is (every? string? (keys (first out))))
    (is (every? keyword? (keys (first (vs/question {} rows))))
        "ungrouped rows pass through untouched, so they keep their keyword keys")))

(deftest the-four-new-language-terms-work
  (is (= 20 (count (vs/question {:filter {:col "region" :op "≠" :val "r0"}} rows)))
      "≠ keeps r1 and r2")
  (is (= 30 (count (vs/question {:filter {:col "region" :op "contains" :val "R"}} rows)))
      "contains is case-insensitive, and every region starts with r")
  (is (= 10 (count (vs/question {:filter {:col "region" :op "contains" :val "R0"}} rows)))
      "and it still discriminates")
  (let [mins (vs/question {:group_by "region" :measure "v" :agg "min"} rows)
        maxs (vs/question {:group_by "region" :measure "v" :agg "max"} rows)]
    (is (= 0.0 (get (group-row mins "r0") "v")))
    (is (= 27.0 (get (group-row maxs "r0") "v")))
    (is (= 1.0 (get (group-row mins "r1") "v")))
    (is (= 29.0 (get (group-row maxs "r2") "v")))))
