(ns loci.lake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.tablestore :as tbl]))

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-lake
  "A fresh DuckLake in a temp directory, always removed."
  [[sym] & body]
  `(let [dir# (str (System/getProperty "java.io.tmpdir") "/loci-lake-" (System/nanoTime))
         ~sym (lake/open-lake! dir#)]
     (try ~@body (finally (lake/close! ~sym) (rm-rf dir#)))))

(deftest opens-and-answers
  (with-lake [lk]
    (is (= [{:answer 2}] (lake/q lk "SELECT 1 + 1 AS answer")))))

(deftest ducklake-is-attached
  (with-lake [lk]
    (is (= [{:name "lake"}]
           (lake/q lk "SELECT database_name AS name FROM duckdb_databases() WHERE database_name = 'lake'")))))

(deftest the-lake-is-the-current-database
  ;; Not the same assertion as `ducklake-is-attached`, and the difference is
  ;; the whole point: `duckdb_databases()` lists every ATTACHed database
  ;; whether or not it is current, so it stays green with `USE lake` gone.
  ;; Without `USE lake` the current database is `memory`, DuckDB's transient
  ;; in-process catalog — every table this connection creates would land there
  ;; and be gone at close, with nothing in the lake and no error anywhere.
  ;; Every later task in this plan writes through this connection, so this one
  ;; line is what stops all of them persisting into nothing.
  (with-lake [lk]
    (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db")))))

(def ^:private rows3 [{:id 1 :name "a"} {:id 2 :name "b"} {:id 3 :name "c"}])

(deftest lake-round-trips
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      (is (= :lake (:store h)))
      (is (= 3 (:rows h)))
      (is (= [:id :name] (:cols h)))
      (is (integer? (:snapshot h)))
      (is (= rows3 (tbl/rows lk h {}))))))

(deftest lake-preserves-column-order
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:zeta 1 :alpha 2}])]
      (is (= [:zeta :alpha] (:cols h)))
      (is (= [:zeta :alpha] (vec (keys (first (tbl/rows lk h {})))))))))

(deftest a-round-trip-returns-the-columns-it-was-given
  ;; `q` names result columns by running the JDBC label through `sub/col-kw`,
  ;; which lowercases and squashes anything outside [a-z0-9]. `col-kw` leaves a
  ;; readable *keyword* alone, so `:tenure-band` survives in the log and is
  ;; renamed only on the way back out of the lake. Measured over the 31 live
  ;; tables: 7 came back different, and `tbl:revenue`'s `:tenure-band` is the
  ;; one `content.clj`'s churn-cohort viewer keys on by name.
  (with-lake [lk]
    (let [rs [{:tenure-band "0-6m" :Category "A" :rev_AU 1}]
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= [:tenure-band :Category :rev_AU] (:cols h)))
      (is (= rs (tbl/rows lk h {}))
          "the rows that come back must be the rows that went in"))))

(deftest two-columns-that-differ-only-by-punctuation-stay-two-columns
  (with-lake [lk]
    (let [rs [{:a-b 1 :a_b 2}]
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= 2 (count (:cols h))))
      (is (= rs (tbl/rows lk h {}))
          "collapsing both to :a_b would silently drop one column"))))

(deftest lake-pages
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      (is (= [{:id 2 :name "b"}] (tbl/rows lk h {:offset 1 :limit 1})))
      (is (= 3 (tbl/row-count lk h))))))

(deftest two-tables-never-share-storage
  (with-lake [lk]
    (let [a (tbl/put-rows! lk "tbl:x" [{:v 1}])
          b (tbl/put-rows! lk "tbl:x" [{:v 2}])]
      (is (not= (:table a) (:table b))
          "the same object id written twice must land in two lake tables")
      (is (= [{:v 1}] (tbl/rows lk a {})))
      (is (= [{:v 2}] (tbl/rows lk b {}))))))

(deftest a-read-names-its-version
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows3)]
      ;; move the lake's write head PAST the recorded snapshot
      (lake/x! lk (str "UPDATE " (:table h) " SET name = 'CHANGED'"))
      (is (= rows3 (tbl/rows lk h {}))
          "a handle reads its own snapshot, not whatever the table became"))))

(deftest a-write-after-undo-must-not-resurrect-the-undone-change
  ;; The exact scenario measured 2026-08-16. Without restore-to!, the sum is
  ;; 101200: the change at B is still in the table and comes back the moment
  ;; anything else is written. With it, the sum is 1400.
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:u" (mapv (fn [i] {:id i :v 1}) (range 1000)))
          t (:table h)
          sum #(:s (first (lake/q lk (str "SELECT sum(v) AS s FROM " t))))]
      (is (= 1000.0 (double (sum))))
      (lake/x! lk (str "UPDATE " t " SET v = 999 WHERE id < 100"))   ; snapshot B
      (is (= 100800.0 (double (sum))))
      ;; loci pops the event: the substrate points at the handle's snapshot again.
      ;; Reading is already correct...
      (is (= 1000.0 (double (reduce + (map :v (tbl/rows lk h {}))))))
      ;; ...but the write head is still at B, so it must be moved back.
      (lake/restore-to! lk t (:snapshot h))
      (lake/x! lk (str "UPDATE " t " SET v = 5 WHERE id >= 900"))    ; the write after undo
      (is (= 1400.0 (double (sum)))
          "the change at B must be gone, not merely unread"))))

(deftest restore-preserves-every-row
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:u" (mapv (fn [i] {:id i :v 1}) (range 1000)))
          t (:table h)]
      (lake/x! lk (str "DELETE FROM " t " WHERE id < 500"))
      (is (= 500 (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " t))))))
      (lake/restore-to! lk t (:snapshot h))
      (is (= 1000 (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " t))))))
      (is (= 1000 (count (tbl/rows lk h {})))
          "restore replaces the table; the handle must still resolve its own version"))))

(deftest the-lake-samples-the-same-values-the-rows-would
  (with-lake [lk]
    (let [rs (into [] (for [i (range 100)] {:r (str "r" (mod i 7)) :n i}))
          hl (tbl/put-rows! lk "tbl:x" rs)
          hi (tbl/put-rows! (tbl/inline-table) "tbl:x" rs)]
      (is (= (tbl/column-samples (tbl/inline-table) hi 3 3)
             (tbl/column-samples lk hl 3 3))
          "inline and lake must agree, or migration re-embeds every table"))))

(deftest sampling-sees-past-the-mold-cap
  ;; The bug this task exists to fix. Sorted so a column's variety begins well
  ;; past `mold-cap`: hydrating 2,000 rows sees one region, the database sees
  ;; all three.
  (with-lake [lk]
    (let [rs (into [] (for [i (range 3000)]
                        {:region (cond (< i 2500) "AAA" (< i 2800) "BBB" :else "CCC") :i i}))
          h  (tbl/put-rows! lk "tbl:x" rs)]
      (is (= ["AAA" "BBB" "CCC"] (:region (tbl/column-samples lk h 3 3)))
          "a column's variety past row 2,000 is still the column's variety"))))

(deftest sampling-an-immutable-handle-happens-once
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:a 1}])
          n (atom 0)]
      (with-redefs [lake/q (let [q lake/q] (fn [& args] (swap! n inc) (apply q args)))]
        (tbl/column-samples lk h 3 3)
        (let [after-first @n]
          (tbl/column-samples lk h 3 3)
          (is (= after-first @n)
              "a handle cannot change, so it must not be sampled twice"))))))

(deftest requiring-this-namespace-opens-no-lake
  (is (delay? lake/lake)
      "the process lake is a delay — requiring loci.lake must not touch the data dir")
  (is (not (realized? lake/lake))
      "nothing in this suite has forced it; every test passes an explicit temp dir"))
