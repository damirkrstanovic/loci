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

(deftest hydrate-caps-a-large-table
  (let [ts   (tbl/inline-table)
        many (mapv (fn [i] {:id i}) (range 5000))
        o    (tbl/hydrate ts {:kind :table :value (tbl/put-rows! ts "tbl:big" many)})]
    (is (= tbl/mold-cap (count (:value o))))
    (is (= 5000 (:table/total o)) "the true count survives the cap")))
