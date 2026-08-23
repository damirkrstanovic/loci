(ns loci.source-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [loci.files :as files]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.server :as server]
            [loci.source :as src]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools]
            [org.httpkit.server :as httpd]))

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
        ;; Absolute, like the JSON half below. `file://` plus a RELATIVE path is
        ;; not a file URI — it reads the first segment as a host. It only ever
        ;; worked here because `java.io.tmpdir` happened to be `/tmp`; the test
        ;; alias now sets `.tmp`, and `deps.edn` says why.
        pq (.getAbsolutePath
            (io/file (System/getProperty "java.io.tmpdir")
                     (str "src-" (System/nanoTime) ".parquet")))
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

(deftest describe-refuses-a-two-slash-file-uri-before-duckdb-sees-it
  ;; What the ＋ Data connect box does with `file://data/sales.csv`. `locate`
  ;; runs OUTSIDE describe's catch, so a refused location must never reach the
  ;; lake — and the reader must not be told about a path they did not type.
  ;; Before the refusal this read /sales.csv and DuckDB answered "No files
  ;; found that match the pattern \"/sales.csv\"", so asserting only that it
  ;; throws proves nothing: the absence of DuckDB's words is the whole point.
  (let [e (try (src/describe @lake/lake {:uri "file://data/sales.csv" :format :csv}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= "file://data/sales.csv" (:uri (ex-data e))))
    (is (re-find #"file:///data/sales\.csv" (.getMessage e))
        "it names the spelling that would have worked")
    (is (not (re-find #"No files found" (.getMessage e)))
        "and DuckDB never ran, so it cannot name /sales.csv")
    (is (not (re-find #"loci cannot read" (.getMessage e)))
        "this fails at locate, not at the read")))

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

(deftest connect-materialise-lands-rows-in-the-lake
  (let [lk @lake/lake]
    (let [st (sub/fresh-store)
          r  (src/connect! st {:uri (csv! "Total Revenue ($)\n5\n7\n")
                               :format :csv :mode :materialise :title "Revenue"})
          o  (sub/object st (:id r))]
      (is (= "tbl:revenue" (:id r)) "the id is a readable slug of the title")
      (is (= :lake (:store (:value o))) "materialise yields today's handle")
      (is (= [:total_revenue] (:cols (:value o))))
      (is (= "Revenue" (:title o)))
      (is (= :materialise (:mode (:source o))))
      (is (string? (:at (:source o))))
      (is (= [{:total_revenue 5} {:total_revenue 7}] (:value (tbl/hydrate lk o)))))))

(deftest connect-passthrough-stores-the-recipe-and-creates-no-table
  (let [lk @lake/lake]
    (let [st     (sub/fresh-store)
          before (count (lake/q lk "SELECT table_name FROM duckdb_tables()"))
          r      (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                                   :mode :passthrough :title "Live"})
          h      (:value (sub/object st (:id r)))]
      (is (= :source (:store h)))
      ;; DEVIATION from the plan's draft of this test, which read
      ;; `(is (= "tbl:live" (:id h)) "the handle carries its own id, for the
      ;; sample key")`. It is stale: `lake/samples-version` keys a source's
      ;; samples on the PATH plus `:at`, and its docstring says an object id is
      ;; deliberately not in that key — "a component no test could distinguish,
      ;; which is worse than absent, because it reads as a guarantee".
      ;; `two-source-handles-at-one-path-share-samples-on-purpose` above pins it.
      ;; So the handle must NOT carry one; nothing reads it, and a key put there
      ;; for a reason that no longer exists is the guarantee that paragraph warns
      ;; about.
      (is (nil? (:id h)) "the handle carries no object id — the sample key is path + :at")
      (is (string? (:at h)) "it carries its :at, which is that key's only version component")
      (is (= before (count (lake/q lk "SELECT table_name FROM duckdb_tables()")))
          "passthrough must create no catalog object at all"))))

(deftest passthrough-reads-a-file-whose-headers-are-not-identifiers
  ;; **The fixture is the test.** Every other passthrough fixture in this
  ;; namespace uses `a`, `b`, `share` — headers that are ALREADY loci
  ;; identifiers, so raw and normalized coincide and a read that names the wrong
  ;; half still binds. `Total Revenue ($)` is what a real CSV looks like, and
  ;; against it the handle's `:cols` are the one set of names the file does NOT
  ;; expose. Measured 2026-08-20, with the handle carrying only `:cols`:
  ;;
  ;;   Binder Error: Referenced column "total_revenue" not found in FROM clause!
  ;;   Candidate bindings: "Total Revenue ($)"
  ;;
  ;; from `tbl/rows` AND from `tbl/column-samples`, and `hydrate` classified that
  ;; throw as `:table/source-drift` on a file that had never changed — telling
  ;; the reader to reconnect a source that could never have read.
  (let [lk  @lake/lake
        st  (sub/fresh-store)
        uri (csv! "Total Revenue ($),Region\n5,north\n7,south\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Revenue"})
        o   (sub/object st (:id r))
        h   (:value o)]
    (is (= :source (:store h)) "nothing was copied")
    (is (= [:total_revenue :region] (:cols h)))
    (is (= ["Total Revenue ($)" "Region"] (:raw h))
        "the handle carries the file's own header too, or no later read can build the projection")
    (is (= [{:total_revenue 5 :region "north"}
            {:total_revenue 7 :region "south"}]
           (tbl/rows lk h {}))
        "rows come back under loci's names — the same ones materialise would have stored")
    (is (= [{:total_revenue 7 :region "south"}] (tbl/rows lk h {:offset 1 :limit 1}))
        "and a page goes through the same projection, not a second one")
    (is (= {:total_revenue ["5" "7"] :region ["north" "south"]}
           (tbl/column-samples lk h 3 8))
        "the synopsis reads too — a table's chunk and its recall vector are these samples")
    (let [hy (tbl/hydrate lk o)]
      (is (true? (:table/passthrough hy)))
      (is (= 2 (:table/total hy)))
      (is (= [{:total_revenue 5 :region "north"}
              {:total_revenue 7 :region "south"}]
             (:value hy)))
      (is (nil? (:table/source-drift hy))
          "a file that has never changed must never be reported as drift")
      (is (nil? (:table/unavailable hy)))
      (is (nil? (tbl/unusable hy))
          "and it reads as usable through the one predicate every reader calls"))))

(deftest a-real-header-that-actually-changes-is-still-drift
  ;; The other half, and the reason the fix keeps a NAMED projection. Verified by
  ;; sabotage 2026-08-20: renaming POSITIONALLY instead — `SELECT #1 AS
  ;; "total_revenue", #2 AS "region"` — makes the test above pass and this one
  ;; fail, because any file of the same arity reads and nothing ever looks like
  ;; drift. Neither test can stand without the other: one says the read must
  ;; work, this one says it must still refuse.
  (let [lk  @lake/lake
        st  (sub/fresh-store)
        uri (csv! "Total Revenue ($),Region\n5,north\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Drift"})
        o   (sub/object st (:id r))]
    (spit (subs uri 7) "Gross Revenue,Area\n5,north\n")   ; same file, new header
    (let [hy (tbl/hydrate lk o)]
      (is (= [] (:value hy)) "the shape every downstream guard already handles")
      (is (true? (:table/passthrough hy)))
      (is (nil? (:table/unavailable hy)) "the file is right there — this is drift, not absence")
      (is (string? (:table/source-drift hy)))
      (is (re-find #"connected as total_revenue, region" (:table/source-drift hy))
          "loci's own half, true whatever the driver says")
      (is (re-find #"Gross Revenue" (:table/source-drift hy))
          "and DuckDB's half names what the file holds NOW"))))

(deftest a-source-handle-with-no-raw-still-reads-and-refresh-repairs-it
  ;; `:raw` is new. This branch has already committed `:source` handles without
  ;; it — the corpus re-seed, every test store — so a handle that lacks it falls
  ;; back to using `:cols` as the file's own header. That is correct exactly when
  ;; the headers were already identifiers, which is the only case such a handle
  ;; could ever have read in, and it is bit-for-bit what the read did before.
  ;;
  ;; It cannot be made right for an old handle over `Total Revenue ($)`: the raw
  ;; name was never recorded, so there is nothing to fall back TO. `refresh!` is
  ;; the repair, and it is a repair because it re-describes and writes `:raw`.
  (let [lk  @lake/lake
        uri (csv! "a,b\n1,x\n")
        old {:store :source :uri uri :format :csv
             :cols [:a :b] :rows 1 :at "2026-08-20T10:00:00Z"}]
    (is (nil? (:raw old)))
    (is (= [{:a 1 :b "x"}] (tbl/rows lk old {})) "an identifier-headed file reads as it always did")
    (is (= {:a ["1"] :b ["x"]} (tbl/column-samples lk old 3 8)))
    (let [st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "tbl:old"
                       :value {:id "tbl:old" :kind :table :title "Old" :value old
                               :source {:uri uri :format :csv :mode :passthrough
                                        :at "2026-08-20T10:00:00Z"}}})
      (src/refresh! st "tbl:old")
      (is (= ["a" "b"] (:raw (:value (sub/object st "tbl:old"))))
          "refresh re-describes, so it records the raw header a pre-:raw handle never had"))))

(deftest connect-mints-a-second-id-rather-than-overwriting
  (let [_ @lake/lake]
    (let [st (sub/fresh-store)
          a  (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                               :mode :materialise :title "Sales"})
          b  (src/connect! st {:uri (csv! "a\n2\n") :format :csv
                               :mode :materialise :title "Sales"})]
      (is (= "tbl:sales" (:id a)))
      (is (= "tbl:sales-2" (:id b)) "a taken id is suffixed, never reused"))))

(deftest a-connected-source-keeps-its-reader-options-on-the-handle
  ;; `:options` are load-bearing on the handle, not decoration. Every read
  ;; rebuilds the relation from the handle ALONE, so a handle that forgets them
  ;; reads right once — `describe` was given the real options — and then falls
  ;; back to DuckDB's sniffer forever after. The symptom is not an error: it is a
  ;; spurious `:table/source-drift` on a file nobody touched.
  ;;
  ;; `#` deliberately, and not `;`: DuckDB's sniffer finds `,`, `|`, `;` and tab
  ;; on its own, so a test written with one of those PASSES with `:options`
  ;; dropped and certifies nothing. Measured 2026-08-19 — `a#b\n1#x\n` describes
  ;; as two columns with `delim='#'`, and as one `a#b` without it.
  (let [lk  @lake/lake
        st  (sub/fresh-store)
        uri (csv! "a#b\n1#x\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough
                              :title "Hash" :options {:delim "#"}})
        o   (sub/object st (:id r))
        h   (:value o)]
    (is (= [:a :b] (:cols h)) "describe saw two columns, because it was given the option")
    (is (= {:delim "#"} (:options h)) "so the handle must carry it, or no later read can repeat that")
    (is (= {:delim "#"} (:options (:source o)))
        "and the recipe carries it, so refresh! re-describes the same way")
    (let [hy (tbl/hydrate lk o)]
      (is (= [{:a 1 :b "x"}] (:value hy)))
      (is (nil? (:table/source-drift hy))
          "a handle that forgot the option drifts against a file that never changed"))))

(deftest refresh-commits-a-new-handle-and-undo-restores-the-old-one
  (let [lk @lake/lake]
    (let [st  (sub/fresh-store)
          uri (csv! "a\n1\n")
          r   (src/connect! st {:uri uri :format :csv :mode :materialise :title "R"})
          h1  (:value (sub/object st (:id r)))]
      (spit (subs uri 7) "a\n1\n2\n")
      (src/refresh! st (:id r))
      (let [h2 (:value (sub/object st (:id r)))]
        (is (not= (:table h1) (:table h2)) "refresh materialises a NEW table")
        (is (= 2 (:rows h2)))
        (is (= 2 (count (tbl/rows lk h2 {})))))
      (sub/undo! st)
      (is (= h1 (:value (sub/object st (:id r))))
          "the old handle is still in the log, so undo walks back to it")
      (is (= 1 (count (tbl/rows lk (:value (sub/object st (:id r))) {})))
          "and its rows are still there, because writes never overwrite"))))

(deftest refresh-of-a-passthrough-moves-at-and-cols-and-writes-no-bytes
  (let [_ @lake/lake]
    (let [st  (sub/fresh-store)
          uri (csv! "a\n1\n")
          r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "P"})
          h1  (:value (sub/object st (:id r)))]
      (spit (subs uri 7) "a,b\n1,x\n2,y\n")
      (src/refresh! st (:id r))
      (let [h2 (:value (sub/object st (:id r)))]
        (is (= :source (:store h2)))
        (is (= [:a :b] (:cols h2)) "refresh re-describes")
        (is (= 2 (:rows h2)))
        (is (not= (:at h1) (:at h2)) "at moves, which is what invalidates the synopsis")))))

(deftest refresh-refuses-an-object-with-no-source
  (let [lk @lake/lake]
    (let [st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "tbl:hand"
                       :value (tbl/table-object lk "tbl:hand" "Hand" [{:a 1}])})
      (is (thrown? clojure.lang.ExceptionInfo (src/refresh! st "tbl:hand"))
          "a table nobody connected has no recipe to re-run"))))

(deftest a-very-long-title-still-connects
  ;; `connect!` is the first path in loci from user-supplied text to a lake
  ;; table name, and `lake/table-name` renders an id as a filesystem path
  ;; component. Measured 2026-08-19, before `slug-max`: a 500-character title
  ;; took materialise mode to `IO Error: Failed to create directory
  ;; "…/t_tbl_aaa…aaa_6f4ec946": File name too long`.
  (let [uri (csv! "a\n1\n")
        r   (src/connect! (sub/fresh-store)
                          {:uri uri :format :csv :mode :materialise
                           :title (apply str (repeat 500 "a"))})]
    (is (nil? (:error r)))
    (is (< (count (:id r)) 80) "the readable half is bounded, not the whole title")
    (is (= 1 (:rows r)) "and it really landed"))
  ;; passthrough never touches the filesystem, so it must survive it too
  (let [uri (csv! "a\n1\n")
        r   (src/connect! (sub/fresh-store)
                          {:uri uri :format :csv :mode :passthrough
                           :title (apply str (repeat 500 "b"))})]
    (is (nil? (:error r)))
    (is (< (count (:id r)) 80))))

(deftest a-title-with-no-usable-characters-still-mints-an-id
  (doseq [t ["!!! *** ???" "" "   " nil]]
    (let [r (src/connect! (sub/fresh-store)
                          {:uri (csv! "a\n1\n") :format :csv
                           :mode :passthrough :title t})]
      (is (re-matches #"tbl:[a-z0-9_]+" (:id r)) (str "unusable id for title " (pr-str t))))))

(deftest a-mode-that-is-a-string-still-means-what-it-says
  ;; body-json keywordises a JSON object's KEYS, not its VALUES, so this is
  ;; exactly the shape an HTTP request produces.
  (let [st (sub/fresh-store)
        r  (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                             :mode "passthrough" :title "StrMode"})
        h  (:value (sub/object st (:id r)))]
    (is (= :source (:store h)) "a string mode must not silently materialise")
    (is (= :passthrough (:mode r)) "and the answer must come from what was built")
    (is (= :passthrough (:mode (:source (sub/object st (:id r)))))
        "and the log must record the keyword, not the string")))

(deftest an-unknown-mode-is-refused-rather-than-defaulted
  ;; `5` is in the list on purpose: the normalisation has to survive a value
  ;; that is neither a string nor a keyword — `(keyword 5)` is a
  ;; ClassCastException, which names neither loci nor the mode.
  (doseq [bad [:materialize "copy" :cached "" 5]]
    (let [e (try (src/connect! (sub/fresh-store)
                               {:uri (csv! "a\n1\n") :format :csv
                                :mode bad :title "Bad"})
                 nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str "must refuse mode " (pr-str bad)))
      (is (re-find #"unknown source mode" (str (.getMessage ^Exception e)))
          (str "…and say so, for mode " (pr-str bad))))))

(defn- no-remote-reads
  "`describe`, real for a local uri and throwing for a remote one.

   **A test that reaches the network when it regresses is a test that reaches
   the network.** The refusals below never read today — that is what they
   assert — but without this the day the guard breaks is the day
   `clojure -M:test` calls `bucket.s3.amazonaws.com`, with no timeout anywhere
   in loci, in a suite the spec says stays offline. It delegates for `file://`
   so the local control in each test is still a real connect, and the `::read`
   marker is what a broken guard trips instead of a socket."
  []
  (let [real src/describe]
    (fn [lk s]
      (if (ssp/remote? (:uri s))
        (throw (ex-info "the suite does not reach the network" {::read true}))
        (real lk s)))))

(deftest connect-refuses-passthrough-for-a-remote-location
  ;; Not a pre-emptive refusal. DuckDB sets neither `autoload_known_extensions`
  ;; nor `autoinstall_known_extensions`, so both default true and it installs
  ;; and loads `httpfs` by itself — measured 2026-08-23 against this suite's own
  ;; lake. Without the guard `describe` therefore READS over `s3://`. Against
  ;; THIS fixture that read then 403s rather than succeeding, which is why the
  ;; emptiness assertion below needs its control and why the next test exists at
  ;; all; against a bucket the credential chain can reach it would have
  ;; described, landed and committed `{:store :source :uri "s3://…"}`, the shape
  ;; the spec's measurement says must not exist.
  (with-redefs [src/describe (no-remote-reads)]
    (let [st (sub/fresh-store)
          e  (is (thrown? clojure.lang.ExceptionInfo
                          (src/connect! st {:uri "s3://bucket/x.csv" :format :csv
                                            :mode :passthrough :title "S3"})))
          m  (str (some-> e .getMessage))]
      ;; The ex-data, and it is the only assertion here that DISCRIMINATES.
      ;; `re-find #"passthrough"` matches `mode-of`'s refusal too — measured, its
      ;; message ends "loci knows :materialise and :passthrough" — and
      ;; `#"s3://bucket/x.csv"` matches `describe`'s own failure, which names the
      ;; uri as well. Only `:uri` on the ex-data belongs to this guard alone.
      (is (= "s3://bucket/x.csv" (:uri (ex-data e))))
      (is (= :passthrough (:mode (ex-data e))))
      (is (not (::read (ex-data e))) "and it refused before the read")
      ;; The two below are about the SENTENCE a person is shown, which is a
      ;; separate thing from which branch threw. Kept for that, not for
      ;; discrimination — see above.
      (is (re-find #"passthrough" m) "the sentence names the mode that was refused")
      (is (re-find #"s3://bucket/x\.csv" m) "and the location it was refused for")
      (is (empty? (sub/objects st))
          "and nothing was committed — a refusal that leaves an object behind is
           worse than no refusal, because the shell then draws a broken table, and
           the guard is on connect! rather than on the handle, so an object that
           got past it keeps its degraded state forever")
      ;; The CONTROL for the assertion above, not a second test of `connect!`.
      ;; `(empty? (sub/objects st))` over a fresh store is a negative: it passes
      ;; unchanged if `connect!` threw before doing anything at all — a typo in
      ;; this map, a `mode-of` refusal, an ExceptionInfo from somewhere else — and
      ;; then proves nothing about where the guard sits. So the SAME store takes
      ;; the SAME call one scheme apart, down to the title, and only because that
      ;; one lands an object does the emptiness above mean the refusal kept one
      ;; out rather than that nothing was ever going to arrive.
      (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                        :mode :passthrough :title "S3"})
      (is (= 1 (count (sub/objects st)))
          "this store does record a passthrough connect"))))

(deftest connect-refuses-a-remote-passthrough-spelled-as-a-string
  ;; **The guard compares a KEYWORD, and is only right because `connect!` hands
  ;; it `(assoc src :mode (mode-of src))`.** Every other test of it passes
  ;; `:passthrough`; every HTTP caller passes `"passthrough"`.
  ;; `server/source-connect!` deliberately does not coerce `:mode`, and
  ;; `body-json` keywordises KEYS and not values — and the shell has no
  ;; remote-uri door at all, reaching `sourceConnect` from the file listing and
  ;; the paste box only, so an HTTP client or a REPL caller is this guard's
  ;; entire traffic.
  ;;
  ;; What it closes: hoisting `(refuse-remote-passthrough! src)` above the
  ;; `(assoc src :mode (mode-of src))` line — a plausible "guard first" edit, and
  ;; the comment there says "between the mode being decided and the source being
  ;; read" without saying which decision is load-bearing. Every other test still
  ;; passes and the JSON path lands `{:store :source :uri "s3://…"}` again. That
  ;; is the `modes` defect verbatim one function along, and `modes`' own
  ;; docstring records what it cost on 2026-08-19.
  (with-redefs [src/describe (no-remote-reads)]
    (let [st (sub/fresh-store)
          e  (is (thrown? clojure.lang.ExceptionInfo
                          (src/connect! st {:uri "s3://bucket/x.csv" :format :csv
                                            :mode "passthrough" :title "S3"})))]
      (is (= "s3://bucket/x.csv" (:uri (ex-data e)))
          "the string reached the guard as a mode, not as an unknown one")
      (is (= :passthrough (:mode (ex-data e)))
          "and it is the NORMALIZED mode on the ex-data, which is the whole
           reason the guard runs after mode-of rather than before it")
      (is (not (::read (ex-data e))) "and nothing was read")
      (is (empty? (sub/objects st)) "and nothing was committed"))))

(deftest the-remote-passthrough-refusal-happens-before-the-first-read
  ;; The assertion that pins WHERE the guard sits, because `(empty? …)` above
  ;; cannot. Measured 2026-08-23, with the guard moved to after the commit:
  ;; the s3:// case in the test above still passes every assertion it has,
  ;; because `describe` reaches `bucket.s3.amazonaws.com`, gets an HTTP 403 and
  ;; throws before anything is committed — so a LATE guard and a MISSING guard
  ;; are indistinguishable there. Over a loopback server, where the read
  ;; succeeds, the same late guard threw the right sentence and left
  ;; `{:store :source :uri "http://…"}` in the store: one object, the exact
  ;; state the sentence above calls worse than no refusal.
  ;;
  ;; Stubbing the read is the direct way to say it, and it needs no server and
  ;; no network in ANY state — a guard that is removed or merely late reaches
  ;; `describe` and comes back carrying the marker instead of the refusal.
  (with-redefs [src/describe (fn [& _] (throw (ex-info "describe ran" {::read true})))]
    (let [st (sub/fresh-store)
          e  (is (thrown? clojure.lang.ExceptionInfo
                          (src/connect! st {:uri "s3://bucket/x.csv" :format :csv
                                            :mode :passthrough :title "S3"})))]
      (is (not (::read (ex-data e)))
          "connect! read the source before refusing it — the fetch this refusal
           exists to prevent is the one it just made")
      (is (= "s3://bucket/x.csv" (:uri (ex-data e)))
          "and the refusal is THIS guard's. `re-find #\"passthrough\"` cannot say
           that: mode-of's message lists both modes by name and matches it too,
           so it would keep a partial fix invisible")
      (is (re-find #"passthrough" (str (some-> e .getMessage)))
          "and the sentence a person sees names the mode"))))

(deftest connect-still-allows-passthrough-for-a-local-file
  ;; The guard must be about the SCHEME and not about `connect!` having grown a
  ;; new failure path — phase 1's whole passthrough story is local. Written
  ;; against `(remote? uri)` this passes; written against `:mode` it fails here,
  ;; and `connect-passthrough-stores-the-recipe-and-creates-no-table` above
  ;; fails in the same run.
  (let [st (sub/fresh-store)
        r  (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                             :mode :passthrough :title "Live"})
        h  (:value (sub/object st (:id r)))]
    ;; DEVIATION from the plan's draft, which asserted `(is (nil? (:error r)))`.
    ;; `connect!` returns `{:id :cols :rows :mode}` and throws rather than
    ;; carrying an `:error` — so that assertion reads `(nil? nil)` on a map with
    ;; no such key and cannot fail, on this branch or any other. `:mode` is read
    ;; off the handle `land!` actually built, so it is the one key that a guard
    ;; written too wide could move.
    (is (= :passthrough (:mode r)) "the mode connect! reports comes off the handle it built")
    (is (= :source (:store h))
        "a local passthrough still stores the recipe, not a lake table")))


;; ---------------------------------------------------------------------------
;; a remote location, over a loopback server — and what went over the wire
;; ---------------------------------------------------------------------------

;; Copied from `loci.chunks-test`, where the same shape stubs the embedder. It
;; is private there, and a test namespace reaching across for another's helper
;; couples two suites through a var neither owns — so this is a copy on purpose
;; and not a missed extraction. The `seen` atom is what makes the copy worth
;; having: a remote read is only provable from the wire, and a row count alone
;; cannot tell a fetch from a re-read of the table the last fetch created.
;;
;; The body slurp `chunks-test` does is dropped: httpfs sends none, and slurping
;; a nil body records nothing.
(defn- with-stub
  "Start `handler` on an ephemeral loopback port, call `(f port seen)`, stop the
   server. `seen` holds every request received."
  [handler f]
  (let [seen (atom [])
        srv  (httpd/run-server
              (fn [req]
                (swap! seen conj (select-keys req [:uri :request-method]))
                (handler req))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (httpd/server-port srv) seen)
         (finally (httpd/server-stop! srv {:timeout 100})))))

(defn- with-data-dir
  "Run `(f root)` with `<LOCI_DATA>` at a fresh directory holding an empty
   `files/`, always removed.

   `files/listing` reads `(io/file (sub/data-dir) \"files\")`, and nothing in
   the test alias moves `data-dir` — so without this the assertion below that
   nothing landed in `<LOCI_DATA>/files/` would be over the repository's own
   `data/files/`, and would start failing the day a person connects a file
   through the running app. It also removes the other way that assertion could
   pass for nothing: `File.listFiles` on a directory that does not exist answers
   nil, and `listing` renders that as `[]`."
  [f]
  (let [root (str (System/getProperty "java.io.tmpdir") "/loci-src-data-" (System/nanoTime))]
    (.mkdirs (io/file root "files"))
    (try (with-redefs [sub/data-dir (constantly root)] (f root))
         (finally (lf/rm-rf root)))))

(deftest a-remote-csv-materialises-into-the-lake
  (with-stub (fn [_] {:status 200 :headers {"content-type" "text/csv"}
                      :body "region,revenue\nEMEA,10\nAPAC,20\n"})
    (fn [port seen]
      (with-data-dir
        (fn [root]
          ;; The fixture's lake is `:once` for this whole namespace and
          ;; `ensure-remote!` remembers `:httpfs` on it, while `deftest` order is
          ;; `ns-interns` order and therefore unspecified. Without this reset the
          ;; LOAD assertion below would pass or fail on which remote test ran
          ;; first, which is the worst kind of green.
          (reset! (:ensured @lake/lake) #{})
          (let [sql  (atom [])
                real lake/x!
                st   (sub/fresh-store)
                r    (with-redefs [lake/x! (fn [l s] (swap! sql conj s) (real l s))]
                       (src/connect! st {:uri (str "http://127.0.0.1:" port "/sales.csv")
                                         :format :csv :mode :materialise :title "Remote"}))
                h    (:value (sub/object st (:id r)))]
            ;; No `(is (nil? (:error r)))` — `connect!` returns `{:id :cols :rows
            ;; :mode}` and throws rather than carrying an `:error`, so that
            ;; assertion cannot fail on any branch. It reached this plan's
            ;; sketch twice; `connect-still-allows-passthrough-for-a-local-file`
            ;; records the same deviation.
            (is (= 2 (:rows r)) "the rows came from the stub and nowhere else")
            (is (= [:region :revenue] (:cols r)))
            (is (= :lake (:store h))
                "materialised, so the handle names a lake table and not the uri")
            (is (= #{"/sales.csv"} (set (map :uri @seen)))
                (str "DuckDB asked this server for something other than the path loci
                      was given — `locate` is verbatim precisely so a uri is not
                      re-encoded on the way through, and this is where that shows: "
                     (pr-str @seen)))
            ;; **The assertion that survives autoload**, and the reason it is
            ;; here rather than only `(seq @seen)`. httpfs is a known extension
            ;; and loci sets neither `autoload_known_extensions` nor
            ;; `autoinstall_known_extensions`, so DuckDB loads it by itself and
            ;; every assertion above passes with `ensure-remote!` deleted —
            ;; measured 2026-08-23 on this machine, where httpfs is installed but
            ;; reports `loaded false` on a fresh connection. What autoload cannot
            ;; do is the two things `ensure-remote!`'s docstring names: issue the
            ;; s3 secret, and put the failure at connect with the paragraph that
            ;; names the extension directory and the uid instead of inside a read
            ;; with DuckDB's own message. So this asserts that loci issues the
            ;; LOAD, which is the decision, rather than that the read worked,
            ;; which this machine would grant either way.
            (is (some #(re-find #"(?i)load\s+httpfs" %) @sql)
                (str "loci left the httpfs load to DuckDB's autoload, which is not
                      available in the offline image this phase targets: " (pr-str @sql)))
            (is (empty? (files/listing st))
                "and NOTHING landed in <LOCI_DATA>/files/ — the spec's claim that the
                 files GC never sees a remote source, asserted rather than assumed")
            ;; The control for the line above, which is otherwise a negative over
            ;; a directory this test made empty two lines ago. `files/listing` is
            ;; only evidence that no fetch was cached there if it can see a file
            ;; that is.
            (spit (io/file root "files" "cafebabe.csv") "a\n1\n")
            (is (= 1 (count (files/listing st)))
                "the listing does observe this directory, so the emptiness above is
                 about the remote connect and not about where it was looking")))))))

(deftest refresh-of-a-remote-source-re-fetches-the-object
  ;; A remote source is materialise-only, so `refresh!` is the only way its
  ;; bytes ever change — and `(> (count @seen) n1)` is the load-bearing half.
  ;; Ask what the row count alone would report if `refresh!` re-read the LAKE
  ;; TABLE rather than the source: with the body changed it answers 1 and fails,
  ;; but with the body unchanged it answers correctly for the wrong reason, and
  ;; a later reader trusts it. The wire count is what only a re-fetch produces.
  (let [body (atom "region,revenue\nEMEA,10\n")]
    (with-stub (fn [_] {:status 200 :headers {"content-type" "text/csv"} :body @body})
      (fn [port seen]
        (let [st (sub/fresh-store)
              r  (src/connect! st {:uri (str "http://127.0.0.1:" port "/s.csv")
                                   :format :csv :mode :materialise :title "R"})
              n1 (count @seen)]
          (is (= 1 (:rows r)))
          (is (pos? n1) "the connect fetched, so the comparison below has a floor")
          (reset! body "region,revenue\nEMEA,10\nAPAC,20\nLATAM,30\n")
          (let [r2 (src/refresh! st (:id r))]
            (is (= 3 (:rows r2)) "the new object was read, not the old handle re-reported")
            (is (> (count @seen) n1)
                "and it went over the wire again — a refresh that answered from the
                 lake would give the same row count as a re-fetch of unchanged bytes,
                 so the request count is the only thing that separates them")))))))

(deftest a-local-connect-asks-for-no-httpfs
  ;; `ensure-remote!` LOADs httpfs for whatever uri it is handed, and `describe`
  ;; — the one place that serves `connect!`, `refresh!` and the panel's own
  ;; preview — sees EVERY uri there is. So the call is guarded on the scheme,
  ;; and this is the assertion that says so.
  ;;
  ;; Unguarded it is not a wasted statement, it is a broken local connect: LOAD
  ;; fails on a machine without the extension, falls through to INSTALL, and
  ;; INSTALL is the one line here that reaches the network — which under
  ;; `--network none`, the container this whole phase targets, dies. The person
  ;; is then refused a CSV on local disk with a sentence about httpfs.
  ;; `ensure-extension!`'s own paragraph records that an httpfs LOAD in a
  ;; container still falls through, so this is reachable rather than theoretical.
  ;; **The reset is what makes this bite**, and without it the test is a false
  ;; green. `ensure-remote!` remembers `:httpfs` on the lake, the fixture's lake
  ;; is `:once` for the namespace, and `a-remote-csv-materialises-into-the-lake`
  ;; leaves it set — so with the guard removed the call here would return early,
  ;; issue no LOAD, and this test would pass. Measured 2026-08-23: with the reset
  ;; the same sabotage fails, without it it does not.
  (reset! (:ensured @lake/lake) #{})
  (let [sql  (atom [])
        real lake/x!
        st   (sub/fresh-store)]
    (with-redefs [lake/x! (fn [l s] (swap! sql conj s) (real l s))]
      (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                        :mode :materialise :title "Local"}))
    (is (some #(re-find #"(?i)create table" %) @sql)
        (str "the spy saw no CREATE TABLE, so it saw nothing and the assertion below
              would hold over an empty list: " (pr-str @sql)))
    (is (empty? (filter #(re-find #"(?i)load\s+httpfs" %) @sql))
        (str "a local connect asked DuckDB to load httpfs: " (pr-str @sql)))))

(deftest concurrent-connects-do-not-mint-one-id-twice
  ;; `mint-id` reads the objects and `connect!` commits under what it read, so
  ;; two connects that both read before either commits both mint `tbl:race` —
  ;; and the second `:put` REPLACES the first. Every caller still gets a success
  ;; response, for an object only one of them can reach.
  ;;
  ;; Threads on a latch rather than `future`s, which is the shape
  ;; `server-test/concurrent-materializations-mint-distinct-ids` already keeps:
  ;; a thread pool can serialise the calls by itself and certify a lock that is
  ;; not there.
  (let [st      (sub/fresh-store)
        uri     (csv! "a\n1\n")
        latch   (java.util.concurrent.CountDownLatch. 1)
        results (atom [])
        ts      (mapv (fn [_]
                        (doto (Thread.
                               #(do (.await latch)
                                    (swap! results conj
                                           (src/connect! st {:uri uri :format :csv
                                                             :mode :passthrough
                                                             :title "Race"}))))
                          (.start)))
                      (range 8))]
    (.countDown latch)
    (doseq [t ts] (.join t))
    (let [ids (map :id @results)]
      (is (= 8 (count @results)) "every thread answered")
      (is (= 8 (count (distinct ids))) "every caller gets its own id")
      ;; DEVIATION from the review's draft, which asserted
      ;; `(= 8 (count (filter #(sub/object st %) ids)))`. Measured 2026-08-19
      ;; without the lock: it PASSES. All eight ids are the same `tbl:race`,
      ;; that one object exists, and filtering a seq of eight duplicates for
      ;; existence counts eight. It certifies. Counting the TABLES the store
      ;; actually holds is the assertion that falls over — 1, not 8.
      (is (= 8 (count (filter #(= :table (:kind %)) (vals (sub/objects st)))))
          "and the store really holds eight — a lost update is a success response for data nobody can reach")
      (is (= (set ids) (set (map :id (filter #(= :table (:kind %)) (vals (sub/objects st))))))
          "…the eight the callers were handed, and not eight others"))))

(deftest connect-appends-a-cell-to-a-space-and-skips-anything-else
  ;; The 3-arity `space` argument, both branches. The guard is load-bearing:
  ;; `:append` lands on `[:value :cells]` of whatever id it is given, so a table
  ;; id here would rewrite that table's handle map into one carrying a `:cells`
  ;; key — the poisoning `server/space?`'s comment names.
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n"
                     :value {:id "space:n" :kind :space :title "N"
                             :value {:intent "i" :cells []}}})
    (let [r (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                              :mode :passthrough :title "Celled"} "space:n")]
      (is (= [{:ref (:id r)}] (get-in (sub/object st "space:n") [:value :cells]))
          "a real space gets a cell referencing the new table"))
    (let [t (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                              :mode :passthrough :title "Victim"})
          r (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                              :mode :passthrough :title "Second"} (:id t))
          v (:value (sub/object st (:id t)))]
      (is (some? (sub/object st (:id r))) "the connect itself still succeeds")
      (is (nil? (:cells v)) "a table id named as the space must not receive a cell")
      (is (= :source (:store v)) "its handle is untouched"))
    (let [r (src/connect! st {:uri (csv! "a\n1\n") :format :csv
                              :mode :passthrough :title "Third"} "space:nope")]
      (is (some? (sub/object st (:id r)))
          "an id naming nothing is skipped too, and does not fail the connect")
      (is (nil? (sub/object st "space:nope"))
          "…and certainly does not conjure a notebook"))))

(deftest a-materialise-refresh-adopts-the-columns-the-file-has-now
  ;; The materialise half of `refresh-of-a-passthrough-moves-at-and-cols`.
  ;; Refresh means re-run the recipe, NOT verify the schema is unchanged — a
  ;; later "helpful" stability check would break this silently, and only the
  ;; passthrough case was pinned.
  (let [lk  @lake/lake
        st  (sub/fresh-store)
        uri (csv! "a,b\n1,x\n")
        r   (src/connect! st {:uri uri :format :csv :mode :materialise :title "Adopt"})
        h1  (:value (sub/object st (:id r)))]
    (is (= [:a :b] (:cols h1)))
    (spit (subs uri 7) "c,d,e\n1,x,2\n2,y,3\n")
    (src/refresh! st (:id r))
    (let [h2 (:value (sub/object st (:id r)))]
      (is (= :lake (:store h2)))
      (is (= [:c :d :e] (:cols h2))
          "refresh adopts the columns the file has now, rather than refusing them")
      (is (not= (:table h1) (:table h2)) "…into a NEW lake table")
      (is (= 2 (:rows h2)))
      (is (= [{:c 1 :d "x" :e 2} {:c 2 :d "y" :e 3}] (tbl/rows lk h2 {})))
      (is (= 1 (count (tbl/rows lk h1 {})))
          "and the old table is still readable, so undo still walks back to it"))))

(deftest refresh-does-not-discard-a-write-that-lands-while-it-runs
  ;; `apply-event :put` is a whole-object replace, so a commit built from an
  ;; earlier read drops whatever landed in between — no error, no trace.
  ;; `server/rerun!` already avoids this by assoc-ing the one path it means.
  ;;
  ;; **The write has to land INSIDE refresh!'s window** — after it reads the
  ;; object and before it commits. A first draft of this test committed the edit
  ;; before calling `refresh!`, so `refresh!` simply read the edited object and
  ;; the test passed against the whole-object `:put` it was written to catch.
  ;; Hooking `land!`, which runs between the read and the commit, is what makes
  ;; the window real.
  (let [st   (sub/fresh-store)
        uri  (csv! "a\n1\n")
        r    (src/connect! st {:uri uri :format :csv :mode :materialise :title "Keep"})
        id   (:id r)
        orig @#'src/land!]
    (spit (subs uri 7) "a\n1\n2\n")
    (with-redefs [src/land! (fn [& args]
                              (sub/commit! st {:op :assoc :id id :path [:title]
                                               :value "Renamed while refreshing"})
                              (apply orig args))]
      (src/refresh! st id))
    (let [o (sub/object st id)]
      (is (= "Renamed while refreshing" (:title o))
          "a refresh must not roll back a write it never read")
      (is (= 2 (:rows (:value o))) "and it must still have refreshed"))))

;; ---------------------------------------------------------------------------
;; one answer for why a table's rows must not be acted on
;;
;; Four markers and four readers is sixteen places to be right, and `recompute`
;; has already been wrong once here in a way that destroyed data. `tbl/unusable`
;; is the single answer; these tests pin both halves of it — what it refuses,
;; and what it must NOT refuse.
;; ---------------------------------------------------------------------------

(deftest unusable-names-every-marker-a-reader-must-refuse-on
  (is (nil? (tbl/unusable {:value [{:a 1}]})))
  (is (re-find #"reclaimed" (tbl/unusable {:table/reclaimed (java.util.Date.)})))
  (is (re-find #"unreachable" (tbl/unusable {:table/unavailable "no such file"})))
  (is (re-find #"columns" (tbl/unusable {:table/source-drift "Binder Error"})))
  (is (nil? (tbl/unusable {:table/passthrough true :value [{:a 1}]}))
      "a healthy live table is usable — the badge is not a refusal"))

(deftest recompute-refuses-a-source-whose-file-is-gone
  ;; `recompute` is private and takes [st working obj]; a derived table names
  ;; its source under `:from` and its transform under `:via`. This is the
  ;; failure it already guards for a RECLAIMED source: an agent-written fn that
  ;; aggregates turns zero rows into `[{:n 0}]`, nothing downstream refuses a
  ;; non-empty result, and `rerun!` commits it over the good table and reports
  ;; it as refreshed. Measured 2026-08-17 as [{:n 100}] -> [{:n 0}].
  (let [st  (sub/fresh-store)
        uri (csv! "a\n1\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Src"})]
    (.delete (java.io.File. ^String (subs uri 7)))
    (sub/commit! st {:op :put :id "tbl:derived-1"
                     :value {:id "tbl:derived-1" :kind :table :title "D"
                             :value [] :from (:id r) :via "lib:top"
                             :params {:by "a" :n "1" :order "desc"}}})
    (let [out (#'server/recompute st {} (sub/object st "tbl:derived-1"))]
      (is (:why out) "recompute must refuse, not rebuild from zero rows")
      (is (re-find #"unreachable" (:why out))))))

(deftest recompute-rebuilds-from-a-healthy-live-source
  ;; The other half, and the one that catches `:table/passthrough` being read as
  ;; a refusal. It is `true` on a healthy live table and on both failing ones,
  ;; so a reader that takes it for a liveness signal refuses every passthrough
  ;; source there is — and every derived table downstream of one stops
  ;; refreshing, silently, with a plausible reason attached.
  (let [st  (sub/fresh-store)
        uri (csv! "a\n1\n2\n3\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough
                              :title "Live src"})]
    (sub/commit! st {:op :put :id "tbl:derived-2"
                     :value {:id "tbl:derived-2" :kind :table :title "D2"
                             :value [] :from (:id r) :via "lib:top"
                             :params {:by "a" :n "1" :order "desc"}}})
    (let [out (#'server/recompute st {} (sub/object st "tbl:derived-2"))]
      (is (nil? (:why out)) "a live source's rows are as real as any other's")
      (is (= [{:a 3}] (:rows out))))))

(deftest query-table-answers-a-live-source-and-refuses-an-unreachable-one
  ;; The `query_table` reader, over the two shapes a passthrough table has.
  ;; Without the refusal the tool answers `{:rows []}` — "your filter matched
  ;; nothing" — and the model reports a real zero over a missing file.
  (let [st  (sub/fresh-store)
        uri (csv! "a\n1\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Q"})]
    (is (= [{:a 1}] (:rows (tools/dispatch st "query_table" {:table_id (:id r)})))
        "a live source answers with its rows")
    (.delete (java.io.File. ^String (subs uri 7)))
    (let [e (:error (tools/dispatch st "query_table" {:table_id (:id r)}))]
      (is e "the model gets something it can act on, not an empty result")
      (is (re-find #"unreachable" e)))))

(deftest a-drifted-source-refuses-through-every-reader-not-just-the-fragment
  ;; Drift was pinned at `tbl/unusable`'s fragment and at `hydrate`, but never
  ;; through a reader's composed output — and the composed string is what a
  ;; person and the model actually see. The unavailable marker has that cover;
  ;; this is the other half.
  (let [st  (sub/fresh-store)
        uri (csv! "a,b\n1,x\n")
        r   (src/connect! st {:uri uri :format :csv :mode :passthrough :title "Drifter"})
        id  (:id r)]
    (spit (subs uri 7) "c,d\n1,x\n")                     ; same file, new header
    ;; query_table, the reader the model sees
    (let [out (tools/dispatch st "query_table" {:table_id id})]
      (is (:error out) "a drifted source must refuse, not answer with zero rows")
      (is (nil? (:rows out)))
      (is (re-find #"columns changed" (:error out)))
      (is (re-find #"connected as a, b" (:error out))
          "and it names the columns loci recorded, which is true whatever the driver says"))
    ;; recompute, the reader that can destroy data
    (sub/commit! st {:op :put :id "tbl:derived-drift"
                     :value {:id "tbl:derived-drift" :kind :table :title "D"
                             :value [] :from id :via "lib:top"
                             :params {:by "a" :n "1" :order "desc"}}})
    (let [out (#'server/recompute st {} (sub/object st "tbl:derived-drift"))]
      (is (:why out) "and it must refuse before lib:top answers 'no rows came out'")
      (is (re-find #"columns changed" (:why out))))))
