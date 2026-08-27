(ns loci.sandbox-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.sandbox :as sbx]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

;; A minimal max-plus-one minter, standing in for `server/next-id` — `sandbox`
;; cannot require `server` (a cycle), so `context` takes a `mint-id` fn and the
;; test supplies its own, exactly as Task 10 will supply `server`'s.
(defn- counter-mint-id [st]
  (fn [prefix]
    (let [n (->> (keys (sub/objects st))
                 (keep #(when (str/starts-with? % prefix)
                          (try (Long/parseLong (subs % (count prefix)))
                               (catch Exception _ nil))))
                 (reduce max 0))]
      (str prefix (inc n)))))

(defn- put-table! [st id rows]
  (let [h (tbl/put-rows! @lake/lake id rows)]
    (sub/commit! st {:op :put :id id :value {:id id :kind :table :title id :value h}})
    h))

(defn- put-notebook! [st id title refs]
  (sub/commit! st {:op :put :id id
                   :value {:id id :kind :space :title title
                           :value {:cells (mapv (fn [r] {:ref r}) refs)}}}))

(defn- big-rows [n]
  (vec (for [i (range n)] {:region (str "r" (mod i 5)) :v 1.0 :n i})))

(defn- fixture
  "A store with one table `tbl:big`, 2,500 rows, 3 columns, 5 regions."
  [f]
  (lf/with-temp-lake
   (fn []
     (let [st (sub/fresh-store)]
       (put-table! st "tbl:big" (big-rows 2500))
       (f st)))))

;; ---- 1: q's GROUP BY covers every row, not a mold-cap sample --------------

(deftest q-groups-over-every-row
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           q   (get (:bindings ctx) 'q)
           tbl (get (:bindings ctx) 'table)
           r   (q {:select [:region [[:sum :v] :v]] :from [[tbl :t]] :group-by [:region]})]
       (is (= 5 (count r)))
       (is (= 2500.0 (double (reduce + (map :v r))))
           "2000.0 would mean a mold-cap sample came back, not every row")))))

;; ---- 2: q's metadata carries the TRUE total when a limit truncates -------

(deftest q-carries-the-true-total-when-capped
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           q   (get (:bindings ctx) 'q)
           tbl (get (:bindings ctx) 'table)
           r   (q {:select [:*] :from [[tbl :t]] :limit 10})]
       (is (= 10 (count r)))
       (is (= 2500 (:total (meta r))))
       (is (true? (:capped (meta r))))))))

;; ---- 3: q with no limit at all caps at tbl/max-query-rows -----------------

(deftest q-with-no-limit-caps-at-max-query-rows
  (lf/with-temp-lake
   (fn []
     (let [st (sub/fresh-store)
           n  (inc tbl/max-query-rows)
           h  (lake/create-from-sql! @lake/lake "tbl:cap"
                                     (str "SELECT i AS n FROM range(" n ") t(i)"))]
       (sub/commit! st {:op :put :id "tbl:cap" :value {:id "tbl:cap" :kind :table :title "Cap" :value h}})
       (let [ctx (sbx/context st "tbl:cap" nil (counter-mint-id st))
             q   (get (:bindings ctx) 'q)
             tbl (get (:bindings ctx) 'table)
             r   (q {:select [:*] :from [[tbl :t]]})]
         (is (= tbl/max-query-rows (count r)))
         (is (= n (:total (meta r))))
         (is (true? (:capped (meta r)))))))))

;; ---- 4: q-reduce folds over every row of a table larger than one batch ---

(deftest q-reduce-folds-over-every-row
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           qr  (get (:bindings ctx) 'q-reduce)
           tbl (get (:bindings ctx) 'table)]
       (is (= 2500 (qr {:select [:*] :from [[tbl :t]]}
                       (fn [acc batch] (+ acc (count batch))) 0))
           "2,500 rows is more than one 2,048-row batch")))))

;; ---- 5: q-all returns every row -------------------------------------------

(deftest q-all-returns-every-row
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           qa  (get (:bindings ctx) 'q-all)
           tbl (get (:bindings ctx) 'table)
           r   (qa {:select [:*] :from [[tbl :t]]})]
       (is (= 2500 (count r)))
       (is (= 2500.0 (double (reduce + (map :v r)))))))))

;; ---- 6/7: keep! accumulates and never commits -----------------------------

(deftest keep-with-a-spec-accumulates-and-does-not-commit
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           k   (get (:bindings ctx) 'keep!)
           tbl (get (:bindings ctx) 'table)
           id  (k "Second half" {:select [:*] :from [[tbl :t]] :where [:> :n 1499]})]
       (is (string? id))
       (is (nil? (sub/object st id)) "keep! must accumulate; the run commits once, at the end")
       (let [evs (sbx/events ctx)]
         (is (= 1 (count evs)))
         (is (= 1000 (:rows (:value (:value (first evs)))))
             "n > 1499 over 0..2499 is exactly 1,000 rows — the true count of the answer"))))))

(deftest keep-with-rows-accumulates-and-does-not-commit
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           k   (get (:bindings ctx) 'keep!)
           id  (k "Three rows" [{:a 1} {:a 2} {:a 3}])]
       (is (string? id))
       (is (nil? (sub/object st id)) "keep! must accumulate; the run commits once, at the end")
       (let [evs (sbx/events ctx)]
         (is (= 1 (count evs)))
         (is (= 3 (:rows (:value (:value (first evs)))))))))))

;; ---- 8: :reads is structural — recorded only for a table q actually used -

(deftest reads-records-only-what-was-actually-queried
  (lf/with-temp-lake
   (fn []
     (let [st (sub/fresh-store)]
       (put-table! st "tbl:a" (vec (for [i (range 30)] {:region (str "r" (mod i 3)) :v (double i)})))
       (put-table! st "tbl:b" (vec (for [i (range 20)] {:m i})))
       (put-notebook! st "space:s" "S" ["tbl:a" "tbl:b"])
       (let [ctx  (sbx/context st "tbl:a" "space:s" (counter-mint-id st))
             q    (get (:bindings ctx) 'q)
             tbl  (get (:bindings ctx) 'table)
             tbls (get (:bindings ctx) 'tables)]
         (is (= #{"tbl:a" "tbl:b"} (set (keys tbls))) "both notebook tables are in scope")
         (is (empty? (sbx/reads ctx)) "nothing queried yet")
         (q {:select [:*] :from [[tbl :t]] :limit 1})
         (is (= #{"tbl:a"} (sbx/reads ctx))
             "only tbl:a was touched — a query joining tbl:b would be a different assertion")
         (q {:select [:*]
             :from   [[(get tbls "tbl:a") :a] [(get tbls "tbl:b") :b]]
             :limit  1})
         (is (= #{"tbl:a" "tbl:b"} (sbx/reads ctx))
             "a query joining two tables from `tables` must record both ids"))))))

;; ---- 9: estimate reports rows, cols, seconds ------------------------------

(deftest estimate-reports-rows-cols-and-seconds
  (fixture
   (fn [st]
     (let [ctx (sbx/context st "tbl:big" nil (counter-mint-id st))
           est (sbx/estimate ctx "tbl:big")]
       (is (= 2500 (:rows est)))
       (is (= 3 (:cols est)))
       (is (number? (:seconds est)))
       (is (pos? (:seconds est)))))))

;; ---- 10: a legacy inline table (rows in the event, no handle) still gets a
;;          working q — landed in the real lake, not a throwaway InlineTable

(deftest legacy-inline-table-gets-a-working-q
  (lf/with-temp-lake
   (fn []
     (let [st   (sub/fresh-store)
           rows (vec (for [i (range 24)] {:x i}))]
       (sub/commit! st {:op :put :id "tbl:legacy"
                        :value {:id "tbl:legacy" :kind :table :title "Legacy" :value rows}})
       (let [ctx (sbx/context st "tbl:legacy" nil (counter-mint-id st))
             q   (get (:bindings ctx) 'q)
             tbl (get (:bindings ctx) 'table)
             r   (q {:select [[[:sum :x] :x]] :from [[tbl :t]]})]
         (is (= 1 (count r)))
         (is (= (reduce + (map :x rows)) (long (:x (first r)))))
         (is (= 1 (:total (meta r)))
             ":total is the size of THIS query's answer — one aggregate row — not the source's 24"))))))

;; ---- 11: two q calls in one run read the same snapshot --------------------

(deftest two-q-calls-in-one-run-read-the-same-snapshot
  (lf/with-temp-lake
   (fn []
     (let [st (sub/fresh-store)]
       (put-table! st "tbl:v" (vec (for [i (range 2500)] {:n i})))
       (let [ctx    (sbx/context st "tbl:v" nil (counter-mint-id st))
             q      (get (:bindings ctx) 'q)
             tbl    (get (:bindings ctx) 'table)
             before (:total (meta (q {:select [:*] :from [[tbl :t]] :limit 1})))]
         ;; A write lands on the SAME id after the context is built — a new
         ;; handle naming a new snapshot. put-rows! never overwrites.
         (put-table! st "tbl:v" (vec (for [i (range 5000)] {:n i})))
         (let [after (:total (meta (q {:select [:*] :from [[tbl :t]] :limit 1})))]
           (is (= 2500 before))
           (is (= 2500 after)
               "table's relation must stay pinned to the snapshot context was built with")))))))
