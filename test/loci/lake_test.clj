(ns loci.lake-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.config :as config]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf :refer [rm-rf with-lake]]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

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

;; ---------------------------------------------------------------------------
;; the lake is relocatable
;; ---------------------------------------------------------------------------

(def ^:private rows100
  "Enough rows that the write lands a **Parquet file**, which is what the tests
   below are actually about.

   Measured 2026-08-17 on DuckDB 1.5.5: DuckLake inlines a small insert into
   `catalog.duckdb` itself — the snapshot's change list says `inlined_insert`
   and `files/` holds nothing but an empty directory. The threshold is between
   10 and 20 rows. A move test written with `rows3` would therefore move one
   file and prove nothing about whether the *data* was found again."
  (mapv (fn [i] {:id i :name (str "n" i)}) (range 100)))

(defn- tmpdir [tag] (str (System/getProperty "java.io.tmpdir") "/loci-lake-" tag "-" (System/nanoTime)))

(defn- parquets [dir]
  (filter #(str/ends-with? (.getName ^java.io.File %) ".parquet") (file-seq (io/file dir))))

(deftest a-lake-opens-after-its-directory-moves
  ;; DuckLake records DATA_PATH in the catalog and compares it on every attach,
  ;; so a lake created at one path refused to open at another — which broke
  ;; copying data/, restoring a backup, running the uberjar from another
  ;; directory, and mounting a local data/ into a Docker volume. loci owns the
  ;; whole lake directory, so the catalog's remembered path is never authority.
  ;;
  ;; Without OVERRIDE_DATA_PATH the reopen below throws, before any row is read:
  ;;   Invalid Configuration Error: DATA_PATH parameter
  ;;   "/tmp/loci-lake-moved-…/files/" does not match existing data path in the
  ;;   catalog "/tmp/loci-lake-made-…/files/".
  (let [made  (tmpdir "made")
        moved (tmpdir "moved")]
    (try
      (let [h (let [lk (lake/open-lake! made)]
                (try (tbl/put-rows! lk "tbl:x" rows100) (finally (lake/close! lk))))]
        (is (seq (parquets made))
            "the rows must be in a Parquet file under files/, or the move proves nothing")
        (is (.renameTo (io/file made) (io/file moved)) "the move itself must succeed")
        (let [lk (lake/open-lake! moved)]
          (try
            (is (= rows100 (tbl/rows lk h {}))
                "a handle written before the move must still read its rows after it")
            (finally (lake/close! lk)))))
      (finally (rm-rf made) (rm-rf moved)))))

(deftest a-lake-opens-when-its-catalog-remembers-a-relative-path
  ;; The case this really is. The live data/lake was created with the default
  ;; RELATIVE data directory and has `data/lake/files/` baked into its catalog
  ;; — a string that names a different directory from every working directory
  ;; but one, so `java -jar loci-standalone.jar` from anywhere else could not
  ;; open it.
  ;;
  ;; Constructed the way the live store was rather than by moving the JVM's
  ;; working directory, which a test cannot do: a lake created UNDER the
  ;; working directory and attached by its relative name, then reopened the way
  ;; `open-lake!` now does it. That is also why canonicalizing the path is not
  ;; on its own the fix — measured against the live store, canonicalizing
  ;; without the override REFUSES a lake that used to open:
  ;;   Invalid Configuration Error: DATA_PATH parameter
  ;;   "/home/damirk/src/loci/data/lake/files/" does not match existing data
  ;;   path in the catalog "data/lake/files/".
  (let [rel (str "target/loci-lake-rel-" (System/nanoTime))
        abs (.getCanonicalPath (io/file rel))]
    (try
      (.mkdirs (io/file rel))
      ;; create it the pre-fix way: relative DATA_PATH, no override
      (let [conn (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
            lk   (lake/->Lake conn rel)]
        (try
          (#'lake/ensure-extension! lk)
          (lake/x! lk (str "ATTACH 'ducklake:" rel "/catalog.duckdb' AS lake (DATA_PATH '" rel "/files/')"))
          (lake/x! lk "USE lake")
          (lake/x! lk "CREATE TABLE t_rel AS SELECT * FROM range(100) t(id)")
          (finally (.close conn))))
      (is (seq (parquets rel))
          "the rows must be in a Parquet file under files/, or the reopen proves nothing")
      (is (= [{:key "data_path" :value (str rel "/files/")}]
             (let [conn (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
                   lk   (lake/->Lake conn abs)]
               (try
                 (lake/x! lk (str "ATTACH '" abs "/catalog.duckdb' AS cat (READ_ONLY)"))
                 (lake/q lk "SELECT key, value FROM cat.main.ducklake_metadata WHERE key = 'data_path'")
                 (finally (.close conn)))))
          "the catalog must really hold the relative path, or this test is about nothing")
      ;; and now the absolute path `open-lake!` derives must still open it
      (let [lk (lake/open-lake! abs)]
        (try
          (is (= 100 (:n (first (lake/q lk "SELECT count(*) AS n FROM t_rel")))))
          (finally (lake/close! lk))))
      (finally (rm-rf rel)))))

(deftest a-new-lake-records-where-it-actually-is
  ;; The other half, and the half nothing else would notice — which is how it
  ;; would come to be deleted as dead code. With OVERRIDE_DATA_PATH on, the
  ;; recorded data_path is never read back, so a relative one costs nothing at
  ;; runtime and says nothing to anyone: `data/lake/files/` in a catalog opened
  ;; with the duckdb CLI names a different directory from every working
  ;; directory but one. Canonicalizing costs one syscall at boot and makes the
  ;; value mean something. It is NOT what makes the lake relocatable; the two
  ;; tests above are.
  (let [rel (str "target/loci-lake-abs-" (System/nanoTime))
        abs (.getCanonicalPath (io/file rel))]
    (try
      (let [lk (lake/open-lake! rel)]
        (try
          (is (= [{:value (str abs "/files/")}]
                 (lake/q lk "SELECT value FROM ducklake_options('lake') WHERE option_name = 'data_path'"))
              "a lake opened by a relative name must record the absolute one")
          (finally (lake/close! lk))))
      (finally (rm-rf rel)))))

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

(deftest the-two-stores-disagree-on-a-mixed-type-column
  ;; Recorded, not asserted away. `column-samples`' own docstring motivates
  ;; ordering-by-string with "a column holding both 50 and \"~13%\"" — and that
  ;; column is precisely where the two stores part company, so the example needs
  ;; to carry its own exception. `read_json_auto` widens a Long/String column to
  ;; JSON, the lake's values arrive quoted, and `\"` (0x22) sorts below a digit.
  ;;
  ;; Pin it here so that a future change to either store has to come past this
  ;; test and say whether it closed the gap or widened it.
  (with-lake [lk]
    (let [rs [{:m 50} {:m "~13%"} {:m 7}]
          hl (tbl/put-rows! lk "tbl:m" rs)
          hi (tbl/put-rows! (tbl/inline-table) "tbl:m" rs)]
      (is (= ["50" "7" "~13%"] (:m (tbl/column-samples (tbl/inline-table) hi 3 3)))
          "inline sorts the raw string forms")
      (is (= ["\"~13%\"" "50" "7"] (:m (tbl/column-samples lk hl 3 3)))
          "the lake sorts JSON renderings, and the quote reorders them"))))

(deftest clipping-happens-before-deduplicating
  ;; Two values sharing a 40-point prefix are ONE sample. Deduplicating first
  ;; and clipping afterwards prints the same string twice and says nothing.
  (with-lake [lk]
    (let [long-a #(str (apply str (repeat 44 "a")) %)
          rs     [{:c (long-a "1")} {:c (long-a "2")} {:c "zzz"}]
          hl     (tbl/put-rows! lk "tbl:c" rs)
          hi     (tbl/put-rows! (tbl/inline-table) "tbl:c" rs)]
      (is (= [(str (apply str (repeat 40 "a")) "…") "zzz"]
             (:c (tbl/column-samples lk hl 3 3)))
          "one clipped sample, not two that print alike")
      (is (= (:c (tbl/column-samples (tbl/inline-table) hi 3 3))
             (:c (tbl/column-samples lk hl 3 3)))
          "and both stores clip at the same point"))))

(deftest a-clip-counts-code-points-in-both-stores
  ;; DuckDB's `length`/`substr` count code points; Clojure's `count`/`subs`
  ;; count Java chars, and 45 🙂 is 45 to one and 90 to the other. A `subs`-based
  ;; clip would keep 20 of them against DuckDB's 40 — one table, two synopses.
  (with-lake [lk]
    (let [rs [{:c (apply str (repeat 45 "🙂"))}]
          hl (tbl/put-rows! lk "tbl:e" rs)
          hi (tbl/put-rows! (tbl/inline-table) "tbl:e" rs)]
      (is (= [(str (apply str (repeat tbl/sample-max-chars "🙂")) "…")]
             (:c (tbl/column-samples lk hl 3 3))))
      (is (= (:c (tbl/column-samples (tbl/inline-table) hi 3 3))
             (:c (tbl/column-samples lk hl 3 3)))
          "code points in both, or an emoji splits the stores"))))

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

(deftest a-ragged-table-keeps-every-column-in-the-lake
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{:a 1} {:a 2 :b 9}])]
      (is (= [:a :b] (:cols h)))
      (is (= [{:a 1 :b nil} {:a 2 :b 9}] (tbl/rows lk h {}))
          "a row that lacked the column reads back with nil, not without the key"))))

(deftest a-column-keyword-the-log-cannot-hold-is-normalised-in-the-lake
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" [{(keyword "foo bar") 1}])]
      (is (= [:foo_bar] (:cols h)))
      (is (= [{:foo_bar 1}] (tbl/rows lk h {}))))))

(deftest two-column-names-must-not-become-one-column-in-the-lake
  ;; Refused before any SQL runs, so the lake never gets a table whose column
  ;; silently holds one of the two values it was given.
  (with-lake [lk]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refusing to merge them"
                          (tbl/put-rows! lk "tbl:x" [{(keyword "a b") 1 :a_b 2}])))
    (is (empty? (lake/q lk "SELECT table_name FROM duckdb_tables() WHERE database_name = 'lake'"))
        "and nothing was written on the way to refusing")))

(deftest the-two-stores-disagree-on-a-ragged-row
  ;; Recorded, not asserted away — the house pattern of
  ;; `the-two-stores-disagree-on-a-mixed-type-column` above.
  ;;
  ;; New the moment `cols-of` began taking the union over every row. The two
  ;; stores agree on `:cols`, and they agree on `column-samples` — `keep` and
  ;; `IS NOT NULL` both drop the absent key, which is why synopsis parity over
  ;; the live corpus did not move. They disagree on the ROWS, and that is what
  ;; `hydrate` hands every viewer: a columnar store has no way to say "this row
  ;; had no such column", so the key comes back present and nil.
  ;;
  ;; That is the honest trade against dropping the column entirely, and it is a
  ;; shape change rather than a loss. Pin it so a future change to either store
  ;; has to come past this test and say whether it closed the gap or widened it.
  (with-lake [lk]
    (let [rs [{:region "north" :sales 10} {:region "south" :sales 20 :returns 3}]
          hl (tbl/put-rows! lk "tbl:r" rs)
          hi (tbl/put-rows! (tbl/inline-table) "tbl:r" rs)]
      (is (= [:region :sales :returns] (:cols hl) (:cols hi))
          "both stores name the same three columns")
      (is (= [{:region "north" :sales 10}
              {:region "south" :sales 20 :returns 3}]
             (tbl/rows (tbl/inline-table) hi {}))
          "inline leaves the key absent")
      (is (= [{:region "north" :sales 10 :returns nil}
              {:region "south" :sales 20 :returns 3}]
             (tbl/rows lk hl {}))
          "the lake returns it present and nil")
      (is (= (tbl/column-samples (tbl/inline-table) hi 3 3)
             (tbl/column-samples lk hl 3 3))
          "and the samples agree regardless, which is why the synopsis did not move"))))

(deftest requiring-this-namespace-opens-no-lake
  (is (delay? lake/lake)
      "the process lake is a delay — requiring loci.lake must not touch the data dir")
  (is (not (realized? lake/lake))
      "nothing in this suite has forced it; every test passes an explicit temp dir"))

(deftest a-mixed-type-column-round-trips-as-its-own-values
  ;; `read_json_auto` widens a column holding a Long and a String to DuckDB
  ;; JSON, and JDBC hands those cells back as `org.duckdb.JsonNode` — which
  ;; `clojure.data.json/write-str` refuses, so ONE such cell makes the whole
  ;; object unopenable in the shell. Three tables in the live substrate carry
  ;; one. Unwrapping is lossless, so none of them needs refusing.
  (with-lake [lk]
    (let [rs [{:pct 50} {:pct "~13% (1 of 8 countries)"} {:pct 10}]
          h  (tbl/put-rows! lk "tbl:x" rs)
          back (tbl/rows lk h {})]
      (is (= rs back))
      (is (every? #(or (number? %) (string? %)) (map :pct back)))
      (is (string? (json/write-str back))))))

(deftest a-null-in-a-mixed-column-is-nil-not-a-json-node
  ;; The other half of `unwrap`'s contract: a SQL NULL is Java null whatever the
  ;; column type, so it never reaches `unwrap` at all and comes back as the same
  ;; `nil` every other column type gives.
  (with-lake [lk]
    (let [rs   [{:pct 50} {:pct "~13%"} {:pct nil}]
          back (tbl/rows lk (tbl/put-rows! lk "tbl:n" rs) {})]
      (is (= rs back))
      (is (nil? (:pct (nth back 2))))
      (is (string? (json/write-str back))))))

(deftest a-nested-column-is-refused-rather-than-stored
  ;; `unwrap` closed one hole of three. A column whose rows all share one shape
  ;; is inferred as STRUCT or LIST, never JSON, so it bypasses `unwrap` and the
  ;; object cannot be serialized at all. Refused at write time instead —
  ;; naming the column, which is something the agent can act on.
  (with-lake [lk]
    (doseq [[what rs] [["a vector" [{:name "a" :tags ["x" "y"]} {:name "b" :tags ["z"]}]]
                       ["a map"    [{:name "a" :meta {"k" 1}} {:name "b" :meta {"k" 2}}]]]]
      (let [r (try {:handle (tbl/put-rows! lk (str "tbl:" what) rs)}
                   (catch clojure.lang.ExceptionInfo e {:ex e}))]
        (is (some? (:ex r)) (str what " must be refused at write time"))
        (is (re-find #"holds a .* — a loci table column holds scalars"
                     (str (some-> ^Exception (:ex r) .getMessage))))
        (is (#{:tags :meta} (:column (ex-data (:ex r)))) "the refusal names the column")
        ;; What the refusal prevents, made reachable: stored instead, these rows
        ;; come back as org.duckdb.DuckDBArray / DuckDBStruct and write-str dies.
        (when-let [h (:handle r)]
          (is (string? (json/write-str (tbl/rows lk h {})))
              "stored, the rows would be unserializable"))))))

(deftest create-from-relation-projects-raw-headers-onto-loci-columns
  (lf/with-lake [lk]
    (let [f (java.io.File/createTempFile "rel-" ".csv")]
      (try
        (spit f "Total Revenue ($),Region\n5,north\n7,south\n")
        (let [h (lake/create-from-relation!
                 lk "tbl:rev"
                 [{:raw "Total Revenue ($)" :col :total_revenue} {:raw "Region" :col :region}]
                 (str "read_csv('" (.getAbsolutePath f) "')"))]
          (is (= :lake (:store h)) "materialise yields today's handle, unchanged")
          (is (= [:total_revenue :region] (:cols h)))
          (is (= 2 (:rows h)) "the row count comes from the table, not from a caller")
          (is (number? (:snapshot h)))
          (is (= [{:total_revenue 5 :region "north"}
                  {:total_revenue 7 :region "south"}]
                 (tbl/rows lk h {})))
          (is (= 2 (tbl/row-count lk h))))
        (finally (.delete f))))))

(deftest table-gc-is-off-and-says-so
  (with-lake [lk]
    (let [r (lake/sweep! lk #{})]
      (is (= :disabled (:mode r)))
      (is (string? (:why r)))
      (is (re-find #"261|527|529" (:why r)) "the reason names the upstream issues"))))

(deftest sweep-points-at-the-thing-that-does-work
  (with-lake [lk]
    (let [r (lake/sweep! lk #{})]
      (is (= :disabled (:mode r)))
      (is (re-find #"261|527|529" (:why r)) "the upstream issues are still named")
      (is (re-find #"lakegc" (:why r))
          "and so is the manual path, or a reader concludes nothing can reclaim anything"))))

;; ---------------------------------------------------------------------------
;; a reclaimed table, and what still reads
;; ---------------------------------------------------------------------------

(deftest dropped-at-knows-a-dropped-table
  (with-lake [lk]
    (let [h1 (tbl/put-rows! lk "tbl:x" [{:a 1}])
          h2 (tbl/put-rows! lk "tbl:x" [{:a 2}])]
      (is (nil? (lake/dropped-at lk (:table h1))) "a live table was not reclaimed")
      (lake/x! lk (str "DROP TABLE " (:table h1)))
      (is (inst? (lake/dropped-at lk (:table h1)))
          "a dropped table reports when it was dropped")
      (is (nil? (lake/dropped-at lk (:table h2))) "and its sibling is untouched"))))

(deftest dropped-at-is-nil-for-a-table-that-never-existed
  (with-lake [lk]
    (is (nil? (lake/dropped-at lk "t_never_existed_00000000"))
        "absent is not the same as reclaimed — a genuine error must stay an error")))

(deftest dropped-at-sees-past-a-restores-tombstone
  ;; `restore-to!` is a CREATE OR REPLACE, so it stamps `end_snapshot` on the
  ;; old `table_id` and mints a live one under the SAME name. Asking "is there
  ;; a tombstone" answers yes for a table that is perfectly alive; the question
  ;; has to be "is there no live row".
  (with-lake [lk]
    (let [ha (tbl/put-rows! lk "tbl:a" rows100)
          hb (tbl/put-rows! lk "tbl:b" rows100)]
      (lake/restore-to! lk (:table ha) (:snapshot ha))
      (lake/restore-to! lk (:table hb) (:snapshot hb))
      (is (= 2 (count (lake/q lk (str "SELECT table_id FROM"
                                      " __ducklake_metadata_lake.main.ducklake_table"
                                      " WHERE table_name = '" (:table ha) "'"))))
          "one live table, two catalog rows — the state that made this wrong")
      (is (nil? (lake/dropped-at lk (:table ha)))
          "a restored table is live, however many tombstones it has behind it")
      (lake/x! lk (str "DROP TABLE " (:table hb)))
      (is (inst? (lake/dropped-at lk (:table hb)))
          "and dropping a restored table is still a drop"))))

(deftest a-dropped-table-still-serves-its-rows-until-its-files-go
  ;; The tombstone alone frees nothing and breaks nothing: every handle reads
  ;; `AT (VERSION => n)`, which time-travels straight past it. Deleting the
  ;; Parquet is what a reader can feel — which is why Task 8 has to do both,
  ;; and why a test that only drops would prove nothing about degradation.
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows100)]
      (lake/x! lk (str "DROP TABLE " (:table h)))
      (is (inst? (lake/dropped-at lk (:table h))) "the catalog says it was dropped")
      (is (= 100 (count (tbl/rows lk h {:limit 2000})))
          "and yet every row still reads")
      (is (nil? (:table/reclaimed (tbl/hydrate lk {:kind :table :value h})))
          "so hydrate returns real rows, not a reclamation marker"))))

(deftest hydrate-rethrows-when-a-live-restored-tables-files-vanish
  ;; The `reify` in `loci.tablestore-test` cannot see this class of bug — its
  ;; `reclaimed-at` is hardcoded nil, so it agrees with any implementation.
  ;; This is the one that bites: a real catalog, a real restore, real rows that
  ;; were never reclaimed, and a real IO error that must reach the caller.
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:x" rows100)]
      (lake/restore-to! lk (:table h) (:snapshot h))
      ;; Every Parquet the table ever had — `ducklake_list_files` reports only
      ;; the current version's, and the handle reads the one before it.
      (doseq [f (parquets (:dir lk))] (io/delete-file f true))
      (is (nil? (lake/dropped-at lk (:table h))) "nothing dropped this table")
      (is (thrown-with-msg? Exception #"Cannot open file"
            (tbl/hydrate lk {:kind :table :value h}))
          "a live table with no files is a broken disk, not a tidy reclamation"))))

(deftest listing-a-dropped-tables-files-throws-rather-than-returning-none
  ;; Whoever deletes files for real has to collect the paths BEFORE the DROP.
  ;; `ducklake_list_files` resolves its argument through the live catalog, so
  ;; after the tombstone it does not report an empty file list — it refuses the
  ;; name outright, and a sweep that dropped first would take the exception
  ;; instead of the disk space.
  (with-lake [lk]
    (let [h   (tbl/put-rows! lk "tbl:x" rows100)
          sql (str "SELECT data_file FROM ducklake_list_files('lake', '" (:table h) "')")]
      (is (= 1 (count (lake/q lk sql))) "a live table lists its Parquet")
      (lake/x! lk (str "DROP TABLE " (:table h)))
      (is (thrown-with-msg? Exception #"does not exist" (lake/q lk sql))))))

(deftest hydrate-marks-a-reclaimed-table-instead-of-throwing
  (with-lake [lk]
    (let [rs    (mapv (fn [i] {:a i :b (str "p" i)}) (range 100))
          h     (tbl/put-rows! lk "tbl:x" rs)
          o     {:id "tbl:x" :kind :table :title "X" :value h}
          ;; Before the DROP, for the reason the test above records.
          files (mapv :data_file
                      (lake/q lk (str "SELECT data_file FROM ducklake_list_files('lake', '"
                                      (:table h) "')")))]
      ;; 100 rows rather than a handful, and this is load-bearing: DuckLake
      ;; inlines a small insert into the catalog, so a 2-row table has NO
      ;; Parquet to delete and still reads back perfectly after the DROP —
      ;; the same measurement `rows100` above was written for. Such a test
      ;; would exercise the happy path and report that degradation works.
      (is (= 1 (count files)) "the rows are in a file, so deleting it can break the read")
      (lake/x! lk (str "DROP TABLE " (:table h)))
      (doseq [f files] (io/delete-file f true))
      (let [r (tbl/hydrate lk o)]
        (is (= [] (:value r)) "an empty table, so every downstream shape guard still holds")
        (is (inst? (:table/reclaimed r)) "and it says when")
        (is (= 100 (:table/total r)) "the row count survives — it lives on the handle")
        (is (= [:a :b] (:table/cols r)) "and so do the column names")
        (is (= "X" (:title r)) "everything else survives")))))

;; ---------------------------------------------------------------------------
;; where DuckDB's extensions live
;; ---------------------------------------------------------------------------

(defn- seed-extension-dir!
  "Put the already-installed ducklake extension into `dir`, laid out the way an
   `INSTALL` into `dir` would lay it out.

   Without it this test hands DuckDB an EMPTY directory, `ensure-extension!`
   falls through to INSTALL, and every run of the suite pulls **36 MB** over the
   network — measured 2026-08-18 at 508 ms and 36,436,086 bytes, on a machine
   that already had the extension. It also makes the suite unrunnable offline,
   which would be a poor thing to introduce in the same change that makes the
   *container* boot offline.

   `duckdb_extensions().install_path` is the absolute path of the extension this
   DuckDB has loaded, and its last three components are exactly
   `<version>/<platform>/<file>` — the layout `INSTALL` writes under an explicit
   `extension_directory`. So neither the version nor the platform is hardcoded
   here, and a DuckDB upgrade needs no edit."
  [dir]
  (let [src  (io/file (with-redefs [config/env (constantly nil)]
                        (with-lake [lk]
                          (:install_path
                           (first (lake/q lk (str "SELECT install_path FROM duckdb_extensions()"
                                                  " WHERE extension_name = 'ducklake'")))))))
        plat (.getParentFile src)
        ver  (.getParentFile plat)
        dest (io/file dir (.getName ver) (.getName plat) (.getName src))]
    (io/make-parents dest)
    (io/copy src dest)
    dest))

(deftest an-explicit-extension-directory-is-honoured
  ;; The container has no usable HOME — `USER` does not set it and compose runs
  ;; loci as the host uid, which has no passwd entry — so DuckDB composes
  ;; "/.duckdb" and dies with EACCES before the listener starts. loci must be
  ;; able to say where the extension lives without depending on HOME.
  (let [ext (str (System/getProperty "java.io.tmpdir") "/loci-ext-" (System/nanoTime))]
    (.mkdirs (io/file ext))
    (try
      (is (.exists (seed-extension-dir! ext))
          "the extension has to BE there, or this measures an INSTALL instead")
      (with-redefs [config/env {"LOCI_DUCKDB_EXTENSIONS" ext}]
        (with-lake [lk]
          (is (= ext (:d (first (lake/q lk "SELECT current_setting('extension_directory') AS d"))))
              "DuckDB was told where to look")
          (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db"))
              "and the lake still opens")))
      (finally (rm-rf ext)))))

(deftest no-explicit-directory-changes-nothing
  (with-redefs [config/env (constantly nil)]
    (with-lake [lk]
      (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db"))
          "an unset value must leave DuckDB's own default alone"))))

(deftest an-unusable-extension-directory-says-so
  ;; The failure this whole mechanism exists for used to surface as DuckDB's own
  ;;
  ;;   IO Error: Failed to create directory "/.duckdb": Permission denied
  ;;
  ;; out of a stack that named `ensure-extension!` and nothing a reader could
  ;; act on. The replacement has to name the variable, or it is the same trace
  ;; with more words.
  (let [ext (str (System/getProperty "java.io.tmpdir") "/loci-ext-ro-" (System/nanoTime))
        dir (str (System/getProperty "java.io.tmpdir") "/loci-lake-ro-" (System/nanoTime))
        f   (io/file ext)]
    (if (= 0 (:uid (sub/effective-user)))
      ;; Root can write a 0555 directory, so there is no refusal to provoke and
      ;; the assertions below would pass without exercising anything. Say so
      ;; rather than reporting a green.
      (println "SKIPPED an-unusable-extension-directory-says-so: running as root,"
               "an unwritable directory is not unwritable to us")
      (do
        (.mkdirs f)
        (.setWritable f false)
        (try
          (with-redefs [config/env {"LOCI_DUCKDB_EXTENSIONS" ext}]
            (let [t (try (lake/close! (lake/open-lake! dir)) nil
                         (catch clojure.lang.ExceptionInfo e e))
                  m (or (some-> ^Throwable t .getMessage) "")]
              (is (some? t) "an unwritable extension directory must refuse to open")
              (is (str/includes? m "LOCI_DUCKDB_EXTENSIONS") "the message names the variable")
              (is (str/includes? m ext) "and the directory DuckDB tried")
              (is (str/includes? m "ducklake") "and which extension")
              ;; Losing the cause would throw away the OS's own reason — the
              ;; difference between a permission bit, a read-only mount and a
              ;; full disk, none of which loci can diagnose for itself.
              (is (str/includes? (or (some-> ^Throwable t .getCause .getMessage) "") "Permission denied")
                  "and DuckDB's own error survives as the cause")))
          (finally (.setWritable f true) (rm-rf ext) (rm-rf dir)))))))

(deftest unwrap-renders-temporal-types-as-iso-strings
  ;; DuckDB's CSV sniffer gives LocalDate for DATE and java.sql.Timestamp for
  ;; TIMESTAMP. json/write-str throws on the first and shifts the second
  ;; through the JVM default zone. Measured 2026-08-19:
  ;;   (json/write-str {:d (java.sql.Date/valueOf "2026-01-02")})
  ;;   => "{\"d\":\"2026-01-01T23:00:00Z\"}"   ; wrong by a day
  (lf/with-lake [lk]
    (let [f (java.io.File/createTempFile "unwrap-" ".csv")]
      (try
        (spit f "day,when,amount,flag\n2026-01-02,2026-01-02 03:04:05,7,true\n")
        (let [h (lake/create-from-relation!
                 lk "tbl:temporal"
                 [{:raw "day" :col :day} {:raw "when" :col :when}
                  {:raw "amount" :col :amount} {:raw "flag" :col :flag}]
                 (str "read_csv('" (.getAbsolutePath f) "')"))
              r (first (tbl/rows lk h {}))]
          (is (= "2026-01-02" (:day r))
              "a DATE must render as the day the file says, with no zone shift")
          (is (= "2026-01-02T03:04:05" (:when r))
              "a TIMESTAMP must render as written, with no zone applied")
          (is (= 7 (:amount r)))
          (is (= true (:flag r)))
          (is (string? (json/write-str r))
              "the whole row must survive json/write-str — this is the defect"))
        (finally (.delete f))))))
