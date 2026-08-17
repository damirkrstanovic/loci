(ns loci.lakegc-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.lakegc :as gc]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- dir-bytes
  "Bytes actually on disk under `f`."
  [f]
  (let [f (io/file f)]
    (if (.isDirectory f) (reduce + 0 (map dir-bytes (.listFiles f))) (.length f))))

(defn- snapshot-ids [lk]
  (set (map :snapshot_id (lake/q lk "SELECT snapshot_id FROM ducklake_snapshots('lake')"))))

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

(deftest reclaim-frees-what-the-report-predicted
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)]
      (dotimes [_ 5]
        (let [h (tbl/put-rows! lk "tbl:x" (vec (for [n (range 500)] {:a n :b (str "row" n)})))]
          (sub/commit! st {:op :put :id "tbl:x"
                           :value {:id "tbl:x" :kind :table :title "X" :value h}})))
      (let [w      {:versions 3 :days 0}
            files  (str (:dir lk) "/files")
            before (:reclaimable (first (:tables (gc/report st lk w))))
            b0     (dir-bytes files)
            s0     (snapshot-ids lk)
            r      (gc/reclaim! st lk "tbl:x" w)]
        (is (= before (:freed r)) "freed exactly what the report promised")
        (is (= 2 (:versions r)))
        ;; Against the disk and not only against `:freed`. `:freed` is summed
        ;; from the catalog's own file listing, so it is right whether or not
        ;; anything was deleted — measured 2026-08-17 by removing the deletion
        ;; and leaving the DROP: every other assertion in this test still
        ;; passed, over 906 bytes still sitting in `files/`.
        (is (= before (- b0 (dir-bytes files)))
            "and the bytes left the disk, not just the catalog")
        (is (= 0 (:reclaimable (first (:tables (gc/report st lk w)))))
            "and there is nothing left to reclaim")
        ;; A DuckLake drop MINTS a snapshot (3 → 4, measured), so this cannot be
        ;; an equality on the count. What must hold is that no snapshot went
        ;; away: expiry is catalog-wide and would break every other object's
        ;; handles, which is why reclamation is a drop and never an expiry.
        (is (set/subset? s0 (snapshot-ids lk))
            "no snapshot was expired — expiry is catalog-wide and would break other handles")
        (is (= (+ (count s0) 2) (count (snapshot-ids lk)))
            "two drops, two new snapshots — a tombstone is added, not removed")))))

(deftest reclaim-leaves-every-other-handle-reading
  (lf/with-lake [lk]
    (let [st    (sub/fresh-store)
          other (tbl/put-rows! lk "tbl:other" [{:z 9}])]
      (sub/commit! st {:op :put :id "tbl:other"
                       :value {:id "tbl:other" :kind :table :title "O" :value other}})
      (dotimes [i 5]
        (let [h (tbl/put-rows! lk "tbl:x" [{:a i}])]
          (sub/commit! st {:op :put :id "tbl:x"
                           :value {:id "tbl:x" :kind :table :title "X" :value h}})))
      (gc/reclaim! st lk "tbl:x" {:versions 3 :days 0})
      (is (= [{:z 9}] (tbl/rows lk other {})) "an unrelated table is untouched")
      (is (= [{:a 4}] (tbl/rows lk (:value (sub/object st "tbl:x")) {}))
          "and the current version still reads"))))

(deftest a-reclaimed-table-cannot-be-recreated-from-its-old-version
  ;; Measured 2026-08-17: `CREATE TABLE t AS SELECT * FROM t AT (VERSION => n)`
  ;; succeeds against a merely-dropped table. Deleting the Parquet is what makes
  ;; the reclamation real — without this, a later undo would appear to restore
  ;; rows that are gone.
  ;;
  ;; 100 rows a version, not 1: DuckLake inlines a table below ~10-20 rows into
  ;; `catalog.duckdb`, so it owns no Parquet, nothing is deleted, and the CREATE
  ;; succeeds for a reason that has nothing to do with reclamation.
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          hs (vec (for [i (range 5)]
                    (let [h (tbl/put-rows! lk "tbl:x" (vec (for [n (range 100)] {:a n :i i})))]
                      (sub/commit! st {:op :put :id "tbl:x"
                                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
                      h)))]
      (gc/reclaim! st lk "tbl:x" {:versions 3 :days 0})
      (is (thrown? Exception
                   (lake/x! lk (str "CREATE TABLE " (:table (hs 0))
                                    " AS SELECT * FROM " (:table (hs 0)) " AT (VERSION => 1)")))))))

(deftest reclaim-takes-the-parquet-a-restore-left-behind
  ;; The test the directory sabotage could not be. `restore-to!` is a CREATE OR
  ;; REPLACE: it mints a new `table_id` under the same name and leaves the old
  ;; one's Parquet in the same directory. `ducklake_list_files` names only the
  ;; live one, so reclaiming through it dropped the table, freed half the bytes,
  ;; and left the table recreatable from its pre-restore version — measured
  ;; 2026-08-17, `CREATE TABLE t AS SELECT * FROM t AT (VERSION => 1)` returned
  ;; all 100 rows against a table that had just been reclaimed.
  ;;
  ;; Removing the directory would also have passed this and is still wrong: it
  ;; is safe only because the whole name is doomed here.
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          h1 (tbl/put-rows! lk "tbl:x" (vec (for [n (range 100)] {:a n})))]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value h1}})
      ;; the undo path: move the write head back, re-point the handle
      (lake/restore-to! lk (:table h1) (:snapshot h1))
      (sub/commit! st {:op :assoc :id "tbl:x" :path [:value]
                       :value (assoc h1 :snapshot (:snapshot_id (first (lake/q lk "SELECT max(snapshot_id) AS snapshot_id FROM ducklake_snapshots('lake')"))))})
      ;; and push it out of the window
      (dotimes [i 4]
        (let [h (tbl/put-rows! lk "tbl:x" (vec (for [n (range 100)] {:a n :i i})))]
          (sub/commit! st {:op :put :id "tbl:x"
                           :value {:id "tbl:x" :kind :table :title "X" :value h}})))
      (let [w   {:versions 3 :days 0}
            dir (str (:dir lk) "/files/main/" (:table h1))
            b0  (dir-bytes dir)
            pre (:reclaimable (first (:tables (gc/report st lk w))))
            r   (gc/reclaim! st lk "tbl:x" w)]
        (is (= 2 (count (lake/table-files lk (:table h1))))
            "two table_ids bore this name, so there are two Parquets to take")
        (is (= 0 (dir-bytes dir))
            "the restored table's directory lost every byte, not just the live half")
        (is (= pre (:freed r))
            "and report predicted the whole of it — the two must not drift apart")
        (is (thrown? Exception
                     (lake/x! lk (str "CREATE TABLE " (:table h1)
                                      " AS SELECT * FROM " (:table h1) " AT (VERSION => 1)")))
            "the pre-restore version cannot be recreated either")
        (is (pos? b0))))))

(deftest reclaim-refuses-an-unknown-object
  (lf/with-lake [lk]
    (is (:error (gc/reclaim! (sub/fresh-store) lk "tbl:nope" gc/default-window)))))

(deftest reclaim-collects-the-orphans-undo-left
  ;; An orphan is a table no handle names, so `classify` has no object to
  ;; attribute it to and nil is how you ask for them.
  (lf/with-lake [lk]
    (let [st   (sub/fresh-store)
          gone (tbl/put-rows! lk "tbl:x" (vec (for [n (range 100)] {:a n})))]
      (sub/commit! st {:op :put :id "tbl:x"
                       :value {:id "tbl:x" :kind :table :title "X" :value gone}})
      (sub/undo! st)
      (let [b0 (dir-bytes (str (:dir lk) "/files"))
            r  (gc/reclaim! st lk nil gc/default-window)]
        (is (= 1 (:versions r)))
        (is (pos? (:freed r)))
        (is (= (:freed r) (- b0 (dir-bytes (str (:dir lk) "/files"))))
            "the bytes left the disk, not just the catalog")
        (is (= 0 (get-in (gc/report st lk gc/default-window) [:orphaned :tables])))))))

(deftest reclaim-refuses-when-the-classifier-offers-a-live-table
  ;; `classify`'s `cond` tests `(nil? m)` before `(live table_name)` on purpose,
  ;; so a gap in `event-handles`' coverage surfaces as a LIVE table classified
  ;; `:orphaned` — loud — rather than as a live table quietly called `:current`.
  ;; Orphans ignore the window and carry no object, so `(reclaim! … nil …)` is
  ;; exactly the call that would delete it. `bad` is the map that gap produces.
  ;;
  ;; `reclaim!` must stop dead on it. Filtering the live table out and deleting
  ;; the rest would be the wrong repair: it frees the bytes and leaves the
  ;; disagreement unlooked-at.
  (lf/with-lake [lk]
    (let [st (sub/fresh-store)
          hs (vec (for [i (range 5)]
                    (let [h (tbl/put-rows! lk "tbl:x" (vec (for [n (range 100)] {:a n :i i})))]
                      (sub/commit! st {:op :put :id "tbl:x"
                                       :value {:id "tbl:x" :kind :table :title "X" :value h}})
                      h)))
          w    {:versions 3 :days 0}
          bad  (assoc (gc/classify st lk w)
                      ;; the shape classify emits for an orphan, over the table
                      ;; the live object is reading right now
                      (:table (hs 4)) {:class :orphaned :object nil :ts nil}
                      (:table (hs 0)) {:class :orphaned :object nil :ts nil})
          b0   (dir-bytes (str (:dir lk) "/files"))
          r    (with-redefs [gc/classify (fn [& _] bad)]
                 (gc/reclaim! st lk nil w))]
      (is (:error r) "a live table offered for deletion is a refusal, not a filter")
      (is (= 0 (:freed r)) "and nothing was freed")
      (is (= 5 (count (lake/live-tables lk))) "nothing was dropped")
      (is (= b0 (dir-bytes (str (:dir lk) "/files"))) "nothing was deleted")
      (is (= 100 (count (tbl/rows lk (hs 0) {})))
          "not even the genuinely deletable one it was offered alongside"))))
