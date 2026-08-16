(ns loci.tablestore-test
  (:require [clojure.test :refer [deftest is]]
            [loci.tablestore :as tbl]))

(def rows [{:id 1 :name "a"} {:id 2 :name "b"} {:id 3 :name "c"}])

(deftest inline-round-trips
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= rows (tbl/rows ts h {})))
    (is (= 3 (:rows h)))
    (is (= [:id :name] (:cols h)))))

(deftest inline-pages
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= [{:id 2 :name "b"}] (tbl/rows ts h {:offset 1 :limit 1})))))

(deftest handle-is-recognisable
  (let [h (tbl/put-rows! (tbl/inline-table) "tbl:x" rows)]
    (is (tbl/handle? h))
    (is (not (tbl/handle? rows)))
    (is (not (tbl/handle? {:any :map})))))

(deftest hydrate-swaps-a-handle-for-rows
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)
        o  {:id "tbl:x" :kind :table :title "X" :value h}]
    (is (= rows (:value (tbl/hydrate ts o))))
    (is (= "X" (:title (tbl/hydrate ts o))) "everything else survives")))

(deftest hydrate-passes-through-what-is-not-a-handle
  (let [ts (tbl/inline-table)]
    (is (= rows (:value (tbl/hydrate ts {:kind :table :value rows})))
        "a legacy inline table must render unchanged")
    (is (= "hello" (:value (tbl/hydrate ts {:kind :doc :value "hello"}))))
    (is (nil? (tbl/hydrate ts nil)))))

(deftest hydrate-opens-nothing-when-there-is-no-handle
  (let [boom (delay (throw (ex-info "the lake was opened for a table that never left the log" {})))]
    (is (= [{:a 1}] (:value (tbl/hydrate boom {:kind :table :value [{:a 1}]}))))
    (is (= "hi"     (:value (tbl/hydrate boom {:kind :doc   :value "hi"}))))
    (is (nil? (tbl/hydrate boom nil)))
    (is (not (realized? boom))
        "a store with no handle-backed table must never force the lake")))

(deftest hydrate-still-takes-a-store-directly
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" rows)]
    (is (= rows (:value (tbl/hydrate ts {:kind :table :value h})))
        "passing the store itself, not a delay, must keep working")))

(deftest column-samples-are-distinct-ordered-and-bounded
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:r "EMEA" :n 1} {:r "APAC" :n 2}
                                      {:r "EMEA" :n 3} {:r "AMER" :n 4}])]
    ;; per-col is 3, and the two columns show the two halves of the contract:
    ;; `:r` has exactly three distinct values, so all survive and the order is
    ;; the evidence (first-seen would put EMEA first); `:n` has four, so one is
    ;; dropped, which is the cap.
    (is (= {:r ["AMER" "APAC" "EMEA"] :n ["1" "2" "3"]}
           (tbl/column-samples ts h 3 2))
        "distinct, ordered by string form, capped per column")))

(deftest column-samples-skips-nulls
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a nil} {:a "z"}])]
    (is (= {:a ["z"]} (tbl/column-samples ts h 3 5)))))

(deftest hydrate-caps-a-large-table
  (let [ts   (tbl/inline-table)
        many (mapv (fn [i] {:id i}) (range 5000))
        o    (tbl/hydrate ts {:kind :table :value (tbl/put-rows! ts "tbl:big" many)})]
    (is (= tbl/mold-cap (count (:value o))))
    (is (= 5000 (:table/total o)) "the true count survives the cap")))
