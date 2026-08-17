(ns loci.lakegc-test
  (:require [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.lakegc :as gc]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(deftest classify-names-the-current-version
  (lf/with-lake [lk]
    (let [h  (tbl/put-rows! lk "tbl:x" [{:a 1}])
          st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
      (let [c (gc/classify st lk {:versions 3 :days 30})]
        (is (= :current (get-in c [(:table h) :class])))
        (is (= "tbl:x" (get-in c [(:table h) :object])))))))

(deftest classify-keeps-what-is-inside-the-window
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          hs (vec (for [i (range 5)]
                    (let [h (tbl/put-rows! lk "tbl:x" [{:a i}])]
                      (sub/commit! st {:op :put :id "tbl:x"
                                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
                      h)))
          c  (gc/classify st lk {:versions 3 :days 0})]
      (is (= :current    (get-in c [(:table (hs 4)) :class])))
      (is (= :in-window  (get-in c [(:table (hs 3)) :class])))
      (is (= :in-window  (get-in c [(:table (hs 2)) :class])))
      (is (= :reclaimable (get-in c [(:table (hs 1)) :class])))
      (is (= :reclaimable (get-in c [(:table (hs 0)) :class]))))))

(deftest classify-keeps-anything-recent-whatever-the-version-count
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          hs (vec (for [i (range 5)]
                    (let [h (tbl/put-rows! lk "tbl:x" [{:a i}])]
                      (sub/commit! st {:op :put :id "tbl:x"
                                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
                      h)))
          c  (gc/classify st lk {:versions 3 :days 30})]
      (is (every? #{:current :in-window} (map :class (vals c)))
          "max(3 versions, 30 days) — all five were written seconds ago"))))

(deftest classify-sees-a-handle-committed-inside-a-tx
  ;; `save-table!` commits {:op :tx :events [...]} when it also appends a
  ;; notebook cell. A classifier that walked only top-level events would call
  ;; this table orphaned and reclaim rows a live object is still using.
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h  (tbl/put-rows! lk "tbl:x" [{:a 1}])]
      (sub/commit! st {:op :tx
                       :events [{:op :put :id "tbl:x"
                                 :value {:id "tbl:x" :kind :table :title "X" :value h}}
                                {:op :put :id "doc:d"
                                 :value {:id "doc:d" :kind :doc :value "note"}}]})
      (is (= :current (get-in (gc/classify st lk gc/default-window) [(:table h) :class]))
          "a handle inside a transaction is still a reference"))))

(deftest classify-sees-a-handle-an-assoc-put-there
  ;; `rerun!` refreshes a table with {:op :assoc :path [:value] :value <handle>},
  ;; where the event's value IS the handle rather than an object containing one.
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h1 (tbl/put-rows! lk "tbl:x" [{:a 1}])
          h2 (tbl/put-rows! lk "tbl:x" [{:a 2}])]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value h1}})
      (sub/commit! st {:op :assoc :id "tbl:x" :path [:value] :value h2})
      (let [c (gc/classify st lk gc/default-window)]
        (is (= :current (get-in c [(:table h2) :class])))
        (is (= :in-window (get-in c [(:table h1) :class]))
            "the version it replaced is history, not an orphan")))))

(deftest classify-finds-an-orphan-left-by-undo
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h1 (tbl/put-rows! lk "tbl:x" [{:a 1}])]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value h1}})
      (sub/undo! st)
      (is (= :orphaned (get-in (gc/classify st lk {:versions 3 :days 30}) [(:table h1) :class]))
          "undo! deletes the event, so nothing can ever name this table again"))))

(deftest report-adds-up-per-object
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)]
      (dotimes [i 5]
        (let [h (tbl/put-rows! lk "tbl:x" (vec (for [n (range 200)] {:a n :b (str "r" n)})))]
          (sub/commit! st {:op :put :id "tbl:x"
                           :value {:id "tbl:x" :kind :table :title "X" :value h}})))
      (let [r (gc/report st lk {:versions 3 :days 0})
            t (first (:tables r))]
        (is (= "tbl:x" (:id t)))
        (is (= 5 (:versions t)))
        (is (= 3 (:in-window t)))
        (is (= 2 (:reclaimable-versions t)))
        (is (pos? (:reclaimable t)) "and it costs real bytes")
        (is (pos? (:current t)))
        (is (pos? (get-in r [:lake :catalog]))
            "the catalog is reported separately — it never shrinks and would otherwise make a reclaim look like it did nothing")))))

(deftest report-fetches-no-rows
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h  (tbl/put-rows! lk "tbl:x" [{:a 1}])]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
      (with-redefs [tbl/rows (fn [& _] (throw (ex-info "report fetched rows" {})))]
        (is (seq (:tables (gc/report st lk gc/default-window)))
            "a storage panel that costs a row read per table defeats itself")))))

(deftest report-marks-an-inlined-table
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h  (tbl/put-rows! lk "tbl:tiny" [{:a 1}])]
      (sub/commit! st {:op :put :id "tbl:tiny"
                       :value {:id "tbl:tiny" :kind :table :title "T" :value h}})
      (let [t (first (:tables (gc/report st lk gc/default-window)))]
        (is (:inlined? t) "a 1-row table lives in the catalog, not in Parquet")
        (is (= 0 (:reclaimable t)) "so there is nothing to reclaim for it")))))
