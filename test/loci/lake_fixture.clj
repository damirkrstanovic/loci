(ns loci.lake-fixture
  "A temp lake for any suite whose code path reaches `@lake/lake`.

   Without it, `mold-payload` and `save-table!` open <data-dir>/lake during the
   test run — the same way forcing `mem/memory` in a test with nothing to say
   about memory opens the real memory. `data/` is test data and writing to it
   would not be an incident, but two suites sharing one lake interfere with
   each other, which is reason enough."
  (:require [clojure.java.io :as io]
            [loci.lake :as lake]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn rm-rf [x]
  (let [x (io/file x)]
    (when (.isDirectory x) (run! rm-rf (.listFiles x)))
    (.delete x)))

(defn with-temp-lake
  "A clojure.test fixture. Use with `use-fixtures :once`."
  [f]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/loci-lake-test-" (System/nanoTime))
        lk  (lake/open-lake! dir)]
    (try
      (with-redefs [lake/lake (delay lk)] (f))
      (finally
        (lake/close! lk)
        (rm-rf dir)))))

(defn reclaim!
  "Reclaim `table` for real — `DROP TABLE` plus every Parquet it owned — and
   return the files that were deleted, so a caller can assert there was one.

   **The order is the whole helper.** `ducklake_list_files` resolves its argument
   through the live catalog, so after the tombstone it does not report an empty
   file list, it refuses the name outright — see
   `listing-a-dropped-tables-files-throws-rather-than-returning-none` in
   `loci.lake-test`. Every test that injures a table for real has written this
   sequence out by hand and every one of them got the order right only because
   the previous one had already paid for it.

   Asserting on the return value matters as much as the order: DuckLake **inlines
   a small insert into `catalog.duckdb`**, so a table below ~10–20 rows owns no
   Parquet at all, there is nothing to delete, and the read afterwards succeeds.
   A degradation test built on such a table passes while exercising the happy
   path. `rows100` in `loci.lake-test` is the measured threshold."
  [lk table]
  (let [files (mapv :data_file
                    (lake/q lk (str "SELECT data_file FROM ducklake_list_files('lake', '"
                                    table "')")))]
    (lake/x! lk (str "DROP TABLE " table))
    (run! #(io/delete-file % true) files)
    files))

(defn with-reclaimed-table
  "A store holding one table whose rows have been reclaimed, and one healthy.

   Here rather than in either suite because `loci.server-test` and
   `loci.tools-test` both need it, for the same reason `with-lake` moved here.

   **100 rows on the reclaimed one is load-bearing.** Below ~10–20 rows DuckLake
   inlines the insert into `catalog.duckdb`, so the table owns no Parquet, there
   is nothing for `reclaim!` to delete, and the read afterwards succeeds — every
   test built on it would pass while exercising the happy path. The assert on
   `reclaim!`'s return is what makes that a failure rather than a false green.

   Calls `f` with the store; the temp lake is closed and removed after."
  [f]
  (with-temp-lake
    (fn []
      (let [lk   @lake/lake
            gone (tbl/put-rows! lk "tbl:gone" (vec (for [n (range 100)] {:a n})))
            good (tbl/put-rows! lk "tbl:good" [{:b 1}])
            st   (sub/fresh-store)]
        (sub/commit! st {:op :put :id "tbl:gone"
                         :value {:id "tbl:gone" :kind :table :title "Gone" :value gone}})
        (sub/commit! st {:op :put :id "tbl:good"
                         :value {:id "tbl:good" :kind :table :title "Good" :value good}})
        (assert (= 1 (count (reclaim! lk (:table gone))))
                "no Parquet was deleted — the test would prove nothing")
        (f st)))))

(defmacro with-lake
  "A fresh DuckLake in a temp directory, always removed.

   Here rather than in `loci.lake-test`, where it started, because
   `loci.tablemigrate-test` needs it too and a macro cannot be borrowed from a
   namespace whose helpers are private — the expansion names `rm-rf` at the
   call site."
  [[sym] & body]
  `(let [dir# (str (System/getProperty "java.io.tmpdir") "/loci-lake-" (System/nanoTime))
         ~sym (lake/open-lake! dir#)]
     (try ~@body (finally (lake/close! ~sym) (rm-rf dir#)))))
