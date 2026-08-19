(ns loci.source-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.source :as src]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

;; `with-temp-lake` rather than `with-lake`, from the first test: it REBINDS
;; `lake/lake`, and a later task's `source/connect!` reads that var. A local
;; lake would leave those tests writing to `<data-dir>/lake`.
(use-fixtures :once lf/with-temp-lake)

(defn- csv!
  "A temp CSV holding `text`, as a file:// uri. Never cleaned up inside a test —
   the OS temp dir is the fixture."
  [text]
  (let [f (java.io.File/createTempFile "src-" ".csv")]
    (spit f text)
    (str "file://" (.getAbsolutePath f))))

(deftest describe-names-the-columns-and-the-types
  (let [lk  @lake/lake
        uri (csv! "Total Revenue ($),Region,day\n5,north,2026-01-02\n")
        d   (src/describe lk {:uri uri :format :csv})]
    (is (= [{:raw "Total Revenue ($)" :col :total_revenue :type "BIGINT"}
            {:raw "Region"            :col :region        :type "VARCHAR"}
            {:raw "day"               :col :day           :type "DATE"}]
           (:cols d)))
    (is (= (str "read_csv('" (subs uri 7) "')") (:relation d))
        "describe returns the relation it proved, so connect! cannot rebuild it differently")))

(deftest describe-refuses-two-headers-that-become-one-column
  (let [uri (csv! "a b,a_b\n1,2\n")
        e   (try (src/describe @lake/lake {:uri uri :format :csv}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "two headers normalizing to one column must be refused at connect")
    (is (= :a_b (:column (ex-data e))))))

(deftest describe-fails-on-a-file-that-is-not-there
  (is (thrown? Exception
               (src/describe @lake/lake {:uri "file:///no/such/file.csv" :format :csv}))
      "a bad location must fail at connect, not at first render"))

(deftest describe-reads-parquet-and-json-as-well-as-csv
  ;; Three formats ship in phase 1 because three prove the format axis where
  ;; one only asserts it. Parquet is written BY duckdb so the test needs no
  ;; second library.
  (let [lk @lake/lake
        pq (str (System/getProperty "java.io.tmpdir") "/src-" (System/nanoTime) ".parquet")
        js (java.io.File/createTempFile "src-" ".json")]
    (lake/x! lk (str "COPY (SELECT 1 AS a, 'x' AS b) TO '" pq "' (FORMAT parquet)"))
    (spit js "[{\"a\":1,\"b\":\"x\"}]")
    (is (= [:a :b] (mapv :col (:cols (src/describe lk {:uri (str "file://" pq)
                                                       :format :parquet})))))
    (is (= [:a :b] (mapv :col (:cols (src/describe lk {:uri (str "file://" (.getAbsolutePath js))
                                                       :format :json})))))))

(deftest describe-says-which-source-it-could-not-read
  ;; The commonest real failure — a typo, a moved file, a loci:// name never
  ;; uploaded — and the only one DuckDB notices rather than loci. Raw it is four
  ;; lines: a JDBC driver describing its own state, then the real error, then a
  ;; dump of the SQL loci generated. Callers put .getMessage into a JSON body.
  (let [e (try (src/describe @lake/lake {:uri "file:///no/such/file.csv" :format :csv}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "must be an ex-info, not a bare SQLException")
    (is (= "file:///no/such/file.csv" (:uri (ex-data e))))
    (is (re-find #"loci cannot read file:///no/such/file\.csv" (.getMessage e)))
    (is (re-find #"No files found" (.getMessage e)) "and it keeps what DuckDB actually said")
    (is (not (re-find #"pending query result" (.getMessage e)))
        "without the JDBC driver's account of its own state")
    (is (not (re-find #"LINE 1" (.getMessage e)))
        "and without a dump of SQL the caller never wrote")))

(deftest describe-succeeds-on-an-empty-source-with-a-fabricated-column
  ;; Pins what the empty-columns guard does NOT do. DuckDB's auto-detection
  ;; never returns zero columns, so an empty file connects as a one-column,
  ;; zero-row table. Recorded as a comment first; a comment is not a guarantee.
  (let [lk @lake/lake
        f  (fn [suffix text] (str "file://" (.getAbsolutePath
                                             (doto (java.io.File/createTempFile "empty-" suffix)
                                               (spit text)))))]
    (is (= [{:raw "column0" :col :column0 :type "VARCHAR"}]
           (:cols (src/describe lk {:uri (f ".csv" "") :format :csv}))))
    (is (= [{:raw "column0" :col :column0 :type "VARCHAR"}]
           (:cols (src/describe lk {:uri (f ".csv" "\n") :format :csv}))))
    (is (= [{:raw "json" :col :json :type "JSON"}]
           (:cols (src/describe lk {:uri (f ".json" "[]") :format :json}))))))

(deftest a-source-handle-reads-the-file-on-every-query
  (let [lk  @lake/lake
        uri (csv! "a,b\n1,x\n2,y\n")
        h   {:store :source :uri uri :format :csv
             :cols [:a :b] :rows 2 :at "2026-08-19T10:00:00Z"}]
    (is (true? (tbl/handle? h)) "a :source map is a handle")
    (is (= [{:a 1 :b "x"} {:a 2 :b "y"}] (tbl/rows lk h {})))
    (is (= 2 (tbl/row-count lk h)) "the count is the one recorded at connect")
    (is (nil? (tbl/reclaimed-at lk h)) "nothing can reclaim a file loci does not own")
    ;; the file changes underneath, with no event anywhere
    (spit (subs uri 7) "a,b\n1,x\n2,y\n3,z\n")
    (is (= 3 (count (tbl/rows lk h {}))) "reads go to the file, so they see the new row")
    (is (= 1 (count (tbl/rows lk h {:limit 1}))) "and a page is still a page")
    (is (= [{:a 2 :b "y"}] (tbl/rows lk h {:offset 1 :limit 1}))
        "a source handle pages like any other — OFFSET reaches the second row")))

(deftest a-source-handle-keys-its-samples-on-at
  (let [lk  @lake/lake
        uri (csv! "a\nfirst\n")
        h   {:store :source :uri uri :format :csv
             :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}]
    (is (= {:a ["first"]} (tbl/column-samples lk h 3 8)))
    (spit (subs uri 7) "a\nsecond\n")
    (is (= {:a ["first"]} (tbl/column-samples lk h 3 8))
        "same :at, same answer — the synopsis is as of the last connect or refresh")
    (is (= {:a ["second"]} (tbl/column-samples lk (assoc h :at "2026-08-19T11:00:00Z") 3 8))
        "a new :at is a new key, so refresh is what moves a synopsis")))

(deftest two-source-handles-at-different-paths-do-not-share-samples
  ;; N tables with identical headers is ordinary, and what separates them is the
  ;; PATH, which is already in the FROM text — not any object id. Same `:at`
  ;; deliberately, so the only thing that can be doing the separating is `from`.
  (let [lk  @lake/lake
        jan (csv! "a\njanuary\n")
        feb (csv! "a\nfebruary\n")
        at  "2026-08-19T10:00:00Z"]
    (is (= {:a ["january"]}
           (tbl/column-samples lk {:store :source :uri jan :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))
    (is (= {:a ["february"]}
           (tbl/column-samples lk {:store :source :uri feb :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))))

(deftest two-source-handles-at-one-path-share-samples-on-purpose
  ;; The other half, and the reason no object id is in the key: one file is one
  ;; set of bytes, so two handles reading it must agree. A test that only ever
  ;; compares different files cannot tell an id-keyed cache from a path-keyed
  ;; one, and would pass with the key wrong.
  (let [lk  @lake/lake
        uri (csv! "a\nshared\n")
        at  "2026-08-19T10:00:00Z"]
    (is (= {:a ["shared"]}
           (tbl/column-samples lk {:store :source :uri uri :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8)))
    (spit (subs uri 7) "a\nrewritten\n")
    (is (= {:a ["shared"]}
           (tbl/column-samples lk {:store :source :uri uri :format :csv
                                   :cols [:a] :rows 1 :at at} 3 8))
        "the same path and the same :at is the same cache entry")))

(defn- src-obj [h] {:id "tbl:x" :kind :table :title "Live" :value h})

(deftest hydrate-marks-a-passthrough-table-as-live
  (let [lk  @lake/lake
        uri (csv! "a,b\n1,x\n")
        o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                      :cols [:a :b] :rows 1 :at "2026-08-19T10:00:00Z"}))]
    (is (= [{:a 1 :b "x"}] (:value o)))
    (is (true? (:table/passthrough o)) "a reader has to be able to tell")
    (is (= 1 (:table/total o)))
    (is (nil? (:table/unavailable o)))
    (is (nil? (:table/source-drift o)))))

(deftest hydrate-degrades-when-the-file-is-gone
  (let [lk  @lake/lake
        uri (csv! "a\n1\n")
        _   (.delete (java.io.File. ^String (subs uri 7)))
        o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                      :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}))]
    (is (= [] (:value o)) "the same shape every downstream guard already handles")
    (is (= 1 (:table/total o)) "the count survives on the handle")
    (is (= [:a] (:table/cols o)))
    (is (true? (:table/passthrough o)) "still a live source, just an unreachable one")
    (is (string? (:table/unavailable o)) "carrying loci's own account of why")
    (is (nil? (:table/source-drift o)))))

(deftest hydrate-degrades-when-the-columns-changed
  (let [lk  @lake/lake
        uri (csv! "a,b\n1,x\n")
        _   (spit (subs uri 7) "c,d\n1,x\n")            ; same file, new header
        o   (tbl/hydrate lk (src-obj {:store :source :uri uri :format :csv
                                      :cols [:a :b] :rows 1 :at "2026-08-19T10:00:00Z"}))]
    (is (= [] (:value o)))
    (is (string? (:table/source-drift o))
        "readable but unqueryable is drift, not unavailability")
    (is (nil? (:table/unavailable o)))))

(deftest hydrate-still-rethrows-a-real-failure-for-a-lake-handle
  (is (thrown? Exception
               (tbl/hydrate @lake/lake
                            {:id "tbl:x" :kind :table :title "X"
                             :value {:store :lake :table "t_does_not_exist"
                                     :snapshot 1 :rows 1 :cols [:a]}}))
      "a broken lake stays a broken lake — only a deliberate loss is swallowed"))

(deftest a-directory-is-unavailable-not-drift
  ;; File.canRead says a directory is readable, so a boolean built on it alone
  ;; tells the reader to reconnect a file that does not exist and never will.
  (let [d (java.io.File. (str (System/getProperty "java.io.tmpdir")
                              "/src-dir-" (System/nanoTime)))]
    (.mkdirs d)
    (let [uri (str "file://" (.getAbsolutePath d))
          o   (tbl/hydrate @lake/lake
                           (src-obj {:store :source :uri uri :format :csv
                                     :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}))]
      (is (nil? (:table/source-drift o)) "a directory is not a changed file")
      (is (string? (:table/unavailable o)))
      (is (re-find #"is a directory, not a file" (:table/unavailable o))))))

(deftest an-unavailable-message-is-locis-own-words
  (let [uri (csv! "a\n1\n")
        _   (.delete (java.io.File. ^String (subs uri 7)))
        o   (tbl/hydrate @lake/lake
                         (src-obj {:store :source :uri uri :format :csv
                                   :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}))]
    (is (re-find #"there is no file at " (:table/unavailable o)))
    (is (not (re-find #"pending query result" (:table/unavailable o)))
        "not the driver's account of its own state")
    (is (not (re-find #"LINE 1" (:table/unavailable o)))
        "and not a dump of SQL the caller never wrote")))

(deftest a-drift-message-names-the-columns-the-file-has-now
  (let [uri (csv! "a,b\n1,x\n")
        _   (spit (subs uri 7) "c,d\n1,x\n")
        o   (tbl/hydrate @lake/lake
                         (src-obj {:store :source :uri uri :format :csv
                                   :cols [:a :b] :rows 1 :at "2026-08-19T10:00:00Z"}))]
    (is (nil? (:table/unavailable o)))
    (is (re-find #"Candidate bindings" (:table/source-drift o))
        "DuckDB's message is the actionable half here — it names what IS there")
    (is (not (re-find #"pending query result" (:table/source-drift o)))
        "but still without the driver's preamble")))

(deftest unreadable-because-answers-nil-for-a-good-file
  (is (nil? (ssp/unreadable-because (csv! "a\n1\n")))))

(deftest an-unreadable-file-is-unavailable-and-says-who-cannot-read-it
  ;; The fourth branch of `unreadable-because`, and the only one no other test
  ;; reaches. Written with the skip rather than omitted, which is the pattern
  ;; `loci.lake-test/an-unusable-extension-directory-says-so` already keeps and
  ;; the reason `sub/effective-user` is public: root can read a 0000 file, so
  ;; there is no refusal to provoke and every assertion below would pass while
  ;; exercising nothing. Say so rather than reporting a green.
  (let [f (java.io.File/createTempFile "noperm-" ".csv")]
    (spit f "a\n1\n")
    (.setReadable f false false)
    (if (or (= 0 (:uid (sub/effective-user))) (.canRead f))
      (println "SKIPPED an-unreadable-file-is-unavailable-and-says-who-cannot-read-it:"
               "this process can read a file it just made unreadable")
      (let [uri (str "file://" (.getAbsolutePath f))
            o   (tbl/hydrate @lake/lake
                             (src-obj {:store :source :uri uri :format :csv
                                       :cols [:a] :rows 1 :at "2026-08-19T10:00:00Z"}))]
        (is (nil? (:table/source-drift o)) "unreadable is not changed")
        (is (re-find #"is not readable by " (:table/unavailable o)))
        (is (re-find (re-pattern (str (:uid (sub/effective-user)))) (:table/unavailable o))
            "and it names who could not read it, because that is the repair")))
    (.setReadable f true false)
    (.delete f)))
