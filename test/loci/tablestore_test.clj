(ns loci.tablestore-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]
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
    (is (= {:a ["z"]} (tbl/column-samples ts h 3 5))))
  ;; A ragged table puts a column in `:cols` that a given row simply lacks.
  ;; That must contribute no sample, not a "" or a "null" — the same `keep`
  ;; that drops an explicit nil drops an absent key, and this pins it now that
  ;; `cols-of` can name more columns than any single row carries.
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a 1} {:a 2 :b 9}])]
    (is (= {:a ["1" "2"] :b ["9"]} (tbl/column-samples ts h 3 5))
        "a column absent from a row contributes no sample")))

(deftest a-ragged-table-keeps-every-column
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a 1} {:a 2 :b 9}])]
    (is (= [:a :b] (:cols h))
        "a column that first appears in row 2 is still a column")))

(deftest a-column-keyword-the-log-cannot-hold-is-normalised
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{(keyword "foo bar") 1 :ok 2}])
        ;; the guard Task 7's writer will actually pass this handle through,
        ;; not a stand-in for it. It is deliberately not the thing that saves
        ;; us: measured on a handle carrying an unnormalised `:cols`,
        ;; `safe-event` does NOT throw — `normalize-keys` fixes `:data`'s map
        ;; keys and leaves the `:cols` *values* alone, and `readable?` compares
        ;; printed strings, which `[:foo bar]` matches while reading back as
        ;; three elements instead of two. The log write is the assertion.
        ev (sub/safe-event {:op :put :id "tbl:x" :value h})]
    (is (= [:foo_bar :ok] (:cols h)))
    (is (= [{:foo_bar 1 :ok 2}] (tbl/rows ts h {}))
        "the rows must agree with the columns")
    (is (= (:cols h) (:cols (:value (clojure.edn/read-string (pr-str ev)))))
        "a handle must survive the log it is about to be written to")))

(deftest two-column-names-must-not-become-one-column
  ;; The same failure `col-kw` prevents at the `:cols` level, one layer down and
  ;; costing more. There, an unnormalised column keyword corrupts its own value
  ;; on the way out of the log. Here, both keys normalise to `:a_b`, one value
  ;; wins, `:cols` and the rows agree with each other perfectly — and a column's
  ;; data is simply gone with nothing downstream able to notice. Reachable today
  ;; through `save_table`, where a model names its own columns.
  (let [ts (tbl/inline-table)
        e  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refusing to merge them"
                                 (tbl/put-rows! ts "tbl:x" [{(keyword "a b") 1 :a_b 2}])))]
    (is (= :a_b (:column (ex-data e))) "the exception names the column they collided on")
    (is (= [(keyword "a b") :a_b] (:from (ex-data e))) "and both originals"))
  ;; Across the table, not per row: no single row loses a value here, and the
  ;; table still ends up with one column fed by two names.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refusing to merge them"
                        (tbl/put-rows! (tbl/inline-table) "tbl:x"
                                       [{(keyword "a b") 1} {:a_b 2}]))
      "a ragged table spells the collision one row apart"))

(deftest cols-of-normalises-on-its-own
  ;; `put-rows!` hands `cols-of` rows that `normalize-rows` has already fixed,
  ;; so the two can never disagree — and so the `col-kw` inside `cols-of` is
  ;; unreachable from either store. It is not decoration: `cols-of` is public,
  ;; `lake.clj` calls it, and a caller with raw rows must not get back a column
  ;; list the log cannot hold. This is the assertion that reaches it.
  (is (= [:foo_bar :ok] (tbl/cols-of [{(keyword "foo bar") 1 :ok 2}]))))

(deftest a-readable-column-keyword-is-left-alone
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:tenure-band "0-6m" :Category "A"}])]
    (is (= [:tenure-band :Category] (:cols h))
        "col-kw passes a readable keyword through — Task 6d's whole point")))

(deftest hydrate-caps-a-large-table
  (let [ts   (tbl/inline-table)
        many (mapv (fn [i] {:id i}) (range 5000))
        o    (tbl/hydrate ts {:kind :table :value (tbl/put-rows! ts "tbl:big" many)})]
    (is (= tbl/mold-cap (count (:value o))))
    (is (= 5000 (:table/total o)) "the true count survives the cap")))

(deftest table-object-lands-rows-and-returns-a-handle
  (let [ts (tbl/inline-table)
        o  (tbl/table-object ts "tbl:x" "Title" rows {:from "tbl:src"})]
    (is (= "tbl:x" (:id o)))
    (is (= :table (:kind o)))
    (is (= "Title" (:title o)))
    (is (= "tbl:src" (:from o)) "extra keys survive")
    (is (tbl/handle? (:value o)))
    (is (= rows (tbl/rows ts (:value o) {})))))

(deftest a-nested-column-is-refused-by-the-inline-store-too
  ;; The refusal lives in `normalize-rows` rather than in `Lake` so that BOTH
  ;; stores refuse. Only the lake can actually fail on one — inline would
  ;; serialize a vector happily — and that is exactly why the check belongs
  ;; here: an inline/lake divergence is the failure this move exists to close.
  (let [ts (tbl/inline-table)
        e  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                 #"holds a PersistentVector — a loci table column holds scalars"
                                 (tbl/put-rows! ts "tbl:x" [{:name "a" :tags ["x" "y"]}])))]
    (is (= :tags (:column (ex-data e))) "the refusal names the column")
    (is (= "clojure.lang.PersistentVector" (:type (ex-data e))) "and the offending type"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"holds a .*Map"
                        (tbl/put-rows! (tbl/inline-table) "tbl:x" [{:m {"k" 1}}]))
      "a map is refused the same way")
  ;; Across the table, not per row — `collisions`' rule, for its reason
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"holds a"
                        (tbl/put-rows! (tbl/inline-table) "tbl:x" [{:v "flat"} {:v [1 2]}]))
      "a ragged table still has a nested column")
  (is (some? (tbl/put-rows! (tbl/inline-table) "tbl:x" [{:a 1 :b "s" :c nil :d true}]))
      "scalars, nil and booleans are untouched"))

;; ---------------------------------------------------------------------------
;; a catalog reads the handle, not the rows
;; ---------------------------------------------------------------------------

(deftest table-shape-costs-no-rows
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (is (= {:cols [:a :b] :rows 2} (tbl/table-shape ts {:kind :table :value h})))
    (is (= {:cols [:a :b] :rows 2}
           (tbl/table-shape ts {:kind :table :value [{:a 1 :b "p"} {:a 2 :b "q"}]}))
        "a legacy inline table answers the same question")
    (is (nil? (tbl/table-shape ts {:kind :doc :value "hi"})))))

(deftest table-shape-never-reads-rows
  (let [boom (reify tbl/TableStore
               (put-rows! [_ _ _] (throw (ex-info "no" {})))
               (rows [_ _ _] (throw (ex-info "table-shape fetched rows" {})))
               (row-count [_ h] (:rows h))
               (reclaimed-at [_ _] (throw (ex-info "no" {})))
               (column-samples [_ _ _ _] (throw (ex-info "no" {}))))
        h {:store :lake :table "t_x" :snapshot 1 :rows 1000 :cols [:a :b]}]
    (is (= {:cols [:a :b] :rows 1000} (tbl/table-shape boom {:kind :table :value h}))
        "the handle already knows; nothing may be fetched")))

(deftest hydrate-still-throws-for-a-real-failure
  (let [boom (reify tbl/TableStore
               (put-rows! [_ _ _] nil)
               (rows [_ _ _] (throw (ex-info "disk on fire" {})))
               (row-count [_ h] (:rows h))
               (reclaimed-at [_ _] nil)
               (column-samples [_ _ _ _] nil))
        h {:store :lake :table "t_x" :snapshot 1 :rows 2 :cols [:a]}]
    (is (thrown-with-msg? Exception #"disk on fire"
          (tbl/hydrate boom {:kind :table :value h}))
        "only a table the catalog says was dropped may be swallowed")))

(deftest hydrate-keeps-the-read-error-when-the-catalog-is-broken-too
  ;; Asking "was this reclaimed" is itself a read, and a store damaged enough
  ;; to fail the first one can fail the second. The answer the user needs is
  ;; why their rows would not load — not why the bookkeeping would not load.
  (let [boom (reify tbl/TableStore
               (put-rows! [_ _ _] nil)
               (rows [_ _ _] (throw (ex-info "disk on fire" {})))
               (row-count [_ h] (:rows h))
               (reclaimed-at [_ _] (throw (ex-info "catalog unreadable" {})))
               (column-samples [_ _ _ _] nil))
        h {:store :lake :table "t_x" :snapshot 1 :rows 2 :cols [:a]}]
    (is (thrown-with-msg? Exception #"disk on fire"
          (tbl/hydrate boom {:kind :table :value h}))
        "the original read error survives, not the one from asking about it")))
