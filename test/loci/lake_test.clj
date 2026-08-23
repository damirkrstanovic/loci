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

(defn- bare-lake
  "A `Lake` over a connection that has NOT been through `open-lake!` — no
   ducklake, no ATTACH, no `USE lake`.

   Wanted by every test whose subject is what `open-lake!` does *before* there
   is a lake, which an already-open one cannot observe: `ensure-extension!`
   loading ducklake, `ensure-remote!` loading httpfs, and the pre-fix relative
   DATA_PATH a moved lake used to be refused for.

   The third field is `ensure-remote!`'s once-per-connection set. It starts
   empty here for the same reason `open-lake!` gives it an empty one: the guard
   is a fact about this connection, so a second `Lake` over a second connection
   must not inherit the first's."
  [conn dir]
  (lake/->Lake conn dir (atom #{})))

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
            lk   (bare-lake conn rel)]
        (try
          (#'lake/ensure-extension! lk "ducklake")
          (lake/x! lk (str "ATTACH 'ducklake:" rel "/catalog.duckdb' AS lake (DATA_PATH '" rel "/files/')"))
          (lake/x! lk "USE lake")
          (lake/x! lk "CREATE TABLE t_rel AS SELECT * FROM range(100) t(id)")
          (finally (.close conn))))
      (is (seq (parquets rel))
          "the rows must be in a Parquet file under files/, or the reopen proves nothing")
      (is (= [{:key "data_path" :value (str rel "/files/")}]
             (let [conn (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
                   lk   (bare-lake conn abs)]
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

;; ---------------------------------------------------------------------------
;; ensure-extension! takes the extension's name
;; ---------------------------------------------------------------------------

(defn- failed-extension
  "`ensure-extension!` for `ext` with the SQL captured and every LOAD forced to
   fail — what it issued, and what it threw.

   **The failure is injected rather than provoked, because provoking it goes to
   the network.** The obvious way to make this function fail is to ask for an
   extension that does not exist. Measured 2026-08-23 on DuckDB 1.5.5, that is
   not a local refusal: LOAD fails locally in 9 ms, but the INSTALL behind it is
   a download, and it took 237 ms to come back with

     HTTP Error: Failed to download extension \"not_a_real_extension_xyz\" at URL
     \"http://extensions.duckdb.org/v1.5.5/linux_amd64/not_a_real_extension_xyz.duckdb_extension.gz\"
     (HTTP 404)

   That is an outbound request on every run of this suite, for a refusal that is
   DuckDB's rather than loci's, and one that answers differently on a machine
   with no route out. The network is not a test fixture — the same rule
   `seed-extension-dir!` above exists for.

   INSTALL is allowed to SUCCEED here, so the retry's second LOAD is reached and
   the sequence below is all three statements rather than the two an INSTALL
   failure would stop at."
  [ext]
  (let [sql (atom [])
        e   (with-redefs [lake/x! (fn [_ s]
                                    (swap! sql conj s)
                                    (if (str/starts-with? s "LOAD")
                                      (throw (java.sql.SQLException. "injected"))
                                      :ok))]
              ;; nil for the lake: `x!` is the only thing here that touches a
              ;; connection, and it is stubbed.
              (try (#'lake/ensure-extension! nil ext) nil
                   (catch clojure.lang.ExceptionInfo e e)))]
    {:sql @sql :e e :msg (str (some-> ^Throwable e .getMessage))}))

(deftest ensure-extension-really-loads-the-extension-it-was-asked-for
  ;; A BARE connection, not `with-lake`, and that IS the test. `ATTACH
  ;; 'ducklake:…'` loads the extension by itself — `ensure-extension!`'s own
  ;; docstring records that measurement, and it is why an assertion taken over
  ;; an already-open lake cannot tell this function from a no-op.
  ;;
  ;; The plan sketched `(is (nil? (ensure-extension! lk \"ducklake\")))` here.
  ;; Measured 2026-08-23: the function returns `:ok`, because that is what `x!`
  ;; returns — so that assertion is not merely vacuous, it is inverted. It fails
  ;; against the working function and passes against every implementation that
  ;; does nothing at all.
  ;;
  ;; No `extension_directory` is set, so this is DuckDB's default and a machine
  ;; that has never installed ducklake would INSTALL here. That is not new
  ;; exposure: `with-lake` reaches `open-lake!` the same way, and every test in
  ;; this namespace uses it.
  (let [conn    (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
        lk      (bare-lake conn (str (System/getProperty "java.io.tmpdir") "/loci-no-lake"))
        loaded? #(:loaded (first (lake/q lk (str "SELECT loaded FROM duckdb_extensions()"
                                                 " WHERE extension_name = 'ducklake'"))))]
    (try
      (is (false? (loaded?))
          "a fresh connection must NOT already carry it, or this observes nothing")
      (is (= :ok (#'lake/ensure-extension! lk "ducklake")))
      (is (true? (loaded?))
          "and now it does — the one assertion here that a no-op cannot pass")
      (finally (.close conn)))))

(deftest ensure-extension-names-the-extension-it-was-asked-for
  (let [{:keys [sql e msg]} (failed-extension "httpfs")]
    (is (= ["LOAD httpfs" "INSTALL httpfs" "LOAD httpfs"] sql)
        "LOAD first, INSTALL only if that fails, then LOAD again — and all three
         name the extension they were asked for rather than a hard-coded one")
    (is (instance? clojure.lang.ExceptionInfo e)
        "and the failure is loci's ex-info, not a raw JDBC exception escaping")
    (is (str/includes? msg "httpfs") "the message names it too")
    (is (not (str/includes? msg "ducklake"))
        (str "the message named the other extension: " msg))
    (is (= "httpfs" (:extension (ex-data e))))
    (is (= "injected" (some-> ^Throwable e .getCause .getMessage))
        "with DuckDB's own error kept as the cause, the way the ducklake path keeps it")))

(deftest only-ducklake-claims-the-lake-cannot-open
  ;; The two sentences that are about the CONSEQUENCE rather than the mechanism.
  ;; `open-lake!` closes the connection and rethrows, so for ducklake they are
  ;; true; for an extension loaded lazily on the path that needs it they would be
  ;; a false claim about the whole store.
  ;;
  ;; The shared half is asserted for BOTH, deliberately. A split that gave the
  ;; new caller a short sentence and dropped the directory, the uid and the fix
  ;; would pass any assertion that only read the first line — and it is the
  ;; actionable half that would have gone.
  (let [d (:msg (failed-extension "ducklake"))
        h (:msg (failed-extension "httpfs"))]
    (is (str/includes? d "so the lake cannot open"))
    (is (str/includes? d "Failing here rather than at the first table read"))
    (is (not (str/includes? h "so the lake cannot open"))
        (str "httpfs claimed the store cannot open: " h))
    (is (not (str/includes? h "Failing here rather than at the first table read"))
        (str "httpfs claimed loci is failing at boot: " h))
    (doseq [shared ["Set LOCI_DUCKDB_EXTENSIONS to a directory holding the extension."
                    "running as "
                    "DuckDB said: injected"
                    "LOAD only reads."]]
      (is (str/includes? d shared) (str "the ducklake message lost: " shared))
      (is (str/includes? h shared) (str "the httpfs message never got: " shared)))))

;; ---------------------------------------------------------------------------
;; ensure-remote! — httpfs and the secret, once, lazily
;; ---------------------------------------------------------------------------

(defn- remote-sql
  "What `ensure-remote!` issues for each of `uris` in turn against ONE
   connection, with `x!` captured and nothing executed.

   `fail?` is a predicate on the SQL; a statement it matches throws the way
   DuckDB's would. **Injected rather than provoked, because provoking either
   failure is a trip nobody asked for.** A missing extension means an `INSTALL`
   that downloads — measured 2026-08-23 at 237 ms and an HTTP 404 against
   extensions.duckdb.org, see `failed-extension` above. A credential chain that
   finds nothing means a `CREATE SECRET` that walks the AWS SDK for **6.0 s**
   before refusing, measured the same day. The network is not a test fixture,
   and neither is the absence of an AWS account.

   `covered?` is a predicate on the QUERY text, and says whether DuckDB already
   holds a secret for the location `secret-covers?` is asking about. Stubbed
   rather than set up for real because a real one is a `CREATE PERSISTENT
   SECRET`, which writes a file into the developer's own `~/.duckdb` and
   outlives the test run. `which_secret`'s real answers are zero rows and one
   `{:name …}` row, measured 2026-08-23 on DuckDB 1.5.5, and that is what this
   returns.

   Returns the statements, the queries, whatever each call answered or threw,
   and the guard set — the last because the SQL alone cannot show which
   failures were remembered."
  ([uris] (remote-sql uris (constantly false) (constantly false)))
  ([uris fail?] (remote-sql uris fail? (constantly false)))
  ([uris fail? covered?]
   (let [sql     (atom [])
         queries (atom [])
         ;; nil for the connection: `x!` and `q` are the only things that touch
         ;; one and both are stubbed, as `failed-extension` above does it.
         lk      (bare-lake nil "/no-such-lake")
         out (with-redefs [lake/x! (fn [_ s]
                                     (swap! sql conj s)
                                     (if (fail? s)
                                       (throw (java.sql.SQLException. "injected"))
                                       :ok))
                           lake/q  (fn [_ s]
                                     (swap! queries conj s)
                                     (if (covered? s) [{:name "opsecret"}] []))]
               (mapv (fn [u] (try (lake/ensure-remote! lk u)
                                  (catch clojure.lang.ExceptionInfo e e)))
                     uris))]
     {:sql @sql :queries @queries :out out :ensured @(:ensured lk)})))

(deftest ensure-remote-really-loads-httpfs
  ;; The one assertion in this section a stub cannot make, and the reason this
  ;; test exists beside the SQL-counting ones below: those see the statements
  ;; and never the effect.
  ;;
  ;; A BARE connection, because an already-open lake cannot observe it either
  ;; way. `ensure-extension!`'s docstring records that `ATTACH 'ducklake:…'`
  ;; loads ducklake by itself; httpfs has the same trap one step further out,
  ;; since loci sets neither `autoload_known_extensions` nor
  ;; `autoinstall_known_extensions` and both default true, so a
  ;; `read_csv('https://…')` would load it with no LOAD from loci at all.
  ;; Measured 2026-08-23 on DuckDB 1.5.5: `open-lake!` leaves httpfs
  ;; `loaded false`, and this call flips it to true.
  ;;
  ;; A machine that has never installed httpfs INSTALLs here — 313 ms and one
  ;; request, measured the same day. That is the one outbound request this
  ;; phase's ground rules allow, and it is the exposure
  ;; `ensure-extension-really-loads-the-extension-it-was-asked-for` already has
  ;; for ducklake.
  (let [conn    (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" (java.util.Properties.))
        lk      (bare-lake conn (str (System/getProperty "java.io.tmpdir") "/loci-no-lake"))
        loaded? #(:loaded (first (lake/q lk (str "SELECT loaded FROM duckdb_extensions()"
                                                 " WHERE extension_name = 'httpfs'"))))]
    (try
      (is (false? (loaded?))
          "a fresh connection must NOT already carry httpfs, or this observes nothing")
      (is (= :ok (lake/ensure-remote! lk "https://example.com/x.csv")))
      (is (true? (loaded?))
          "and now it does — the one assertion here a no-op cannot pass")
      (finally (.close conn)))))

(deftest ensure-remote-loads-httpfs-and-issues-the-secret-once
  (let [{:keys [sql queries out ensured]} (remote-sql ["s3://bucket/sales.csv"
                                                       "s3://bucket/sales.csv"])]
    (is (= ["SELECT name FROM which_secret('s3://bucket/sales.csv', 's3')"] queries)
        "the check for an existing secret runs BEFORE the six-second CREATE, and
         it is inside the same guard — asked once for the connection, not once
         per read, so it cannot become a per-read query nobody counted")
    ;; Equality and not two counts, because it also pins the ORDER and the
    ;; absence of anything else: the whole of what a remote read costs a
    ;; connection is two statements, and a count of each would stay green if a
    ;; third appeared beside them.
    (is (= ["LOAD httpfs" "CREATE SECRET loci_s3 (TYPE s3, PROVIDER credential_chain)"] sql)
        "twice asked, once done — this runs before every remote read")
    (is (= [:ok :ok] out))
    (is (= #{:httpfs :s3} ensured))
    (is (not (re-find #"(?i)or\s+replace" (str/join "\n" sql)))
        "no OR REPLACE, so DuckDB refuses a duplicate secret name and the guard is
         what stops a second remote read on this connection throwing. Counting the
         statements without this leaves the guard looking like an optimisation")))

(deftest an-http-location-never-runs-the-credential-chain
  ;; The measurement this branch exists for, and the one correction to the plan
  ;; that changes behaviour rather than a test. `CREATE SECRET … PROVIDER
  ;; credential_chain` is validated when it is CREATED: DuckDB's `aws`
  ;; extension walks the whole chain then and there, and on a machine the chain
  ;; cannot satisfy it does not hand back an unused secret, it throws.
  ;;
  ;; Measured 2026-08-23 on DuckDB 1.5.5 with no AWS credentials present:
  ;; 10.5 s the first time — the `aws` extension autoinstalls, which is also
  ;; how we know `httpfs` alone is not enough for it — and 6.0 s every time
  ;; after, with `Invalid Configuration Error: Secret Validation Failure:
  ;; during `create``.
  ;;
  ;; So a secret issued for every remote scheme would put six seconds and a
  ;; hard failure in front of every https:// read on every machine without AWS
  ;; credentials: most laptops, and this suite. Task 6's loopback fetch is the
  ;; test that would go red; this is the one that says why.
  (let [{:keys [sql out ensured]} (remote-sql ["https://example.com/a.csv"
                                               "http://127.0.0.1:9/b.csv"])]
    (is (= ["LOAD httpfs"] sql)
        "httpfs once, and NO credential chain — an http(s) location needs none")
    (is (= [:ok :ok] out))
    (is (= #{:httpfs} ensured)
        "and the s3 guard is left unset, so an s3:// source arriving later on this
         same connection still gets its secret")))

(deftest the-secret-is-issued-when-an-s3-location-finally-arrives
  (let [{:keys [sql ensured]} (remote-sql ["https://h/a.csv" "s3://b/c.csv" "s3://b/d.csv"])]
    (is (= ["LOAD httpfs" "CREATE SECRET loci_s3 (TYPE s3, PROVIDER credential_chain)"] sql)
        "the s3 read did not load httpfs a second time and the one after it did not
         re-issue the secret — two guards, independent, each firing once")
    (is (= #{:httpfs :s3} ensured))))

(deftest the-s3-secret-carries-no-credential-material
  (let [sql    (:sql (remote-sql ["s3://bucket/sales.csv"]))
        secret (first (filter #(re-find #"(?i)create\s+secret" %) sql))]
    ;; The assertion that stops the rest being vacuous, and it is not
    ;; theoretical: a negative match over a string the test itself produced
    ;; passes just as happily against an empty one, and `(first (filter …))` of
    ;; nothing is nil. Without this line an `ensure-remote!` that issued no
    ;; secret at all would read as clean.
    (is (some? secret) (str "no CREATE SECRET was issued at all: " (pr-str sql)))
    (is (re-find #"(?i)provider\s+credential_chain" (str secret))
        "the whole design is that loci hands DuckDB a provider, not a key")
    (is (not (re-find #"(?i)key_id|secret\s+'|session_token|password"
                      (str/join "\n" sql)))
        (str "the SQL names credential material: " (pr-str sql)))
    (is (not (re-find #"(?i)persistent" (str secret)))
        "TEMPORARY is DuckDB's default and keeping it is the point: a PERSISTENT
         secret is written unencrypted under ~/.duckdb, so that one word would move
         loci from storing no credential to storing one")))

(deftest a-secret-duckdb-already-holds-is-not-overridden
  ;; The escape hatch the spec promises — "a bucket the chain cannot reach fails
  ;; with an auth error, and the user fixes it outside loci" — and DuckDB's
  ;; documented way to do that is `CREATE PERSISTENT SECRET`. Without this
  ;; check it did not work, measured 2026-08-23 on DuckDB 1.5.5: a persistent
  ;; `loci_s3` and a temporary one COEXIST with no error, and at equal scope the
  ;; temporary wins — `which_secret('s3://bucket/x.csv','s3')` named the
  ;; operator's secret, then named loci's the moment it existed. On the machine
  ;; that actually needs the hatch it is worse: the chain has nothing, so the
  ;; CREATE throws first and the source is refused before any read, with working
  ;; keys sitting unused in ~/.duckdb/stored_secrets.
  (let [{:keys [sql queries out ensured]}
        (remote-sql ["s3://bucket/sales.csv"] (constantly false) (constantly true))]
    (is (= ["LOAD httpfs"] sql)
        "httpfs still, because the read needs it — but NO CREATE SECRET, so the
         operator's own secret is the one DuckDB consults")
    (is (= ["SELECT name FROM which_secret('s3://bucket/sales.csv', 's3')"] queries)
        "asked about THIS location and not about the secret table, so a secret
         scoped to some other bucket does not count as covering this one")
    (is (= [:ok] out))
    (is (= #{:httpfs :s3} ensured)
        "and the connection is still marked prepared — a covered location is
         prepared, so the query is not repeated on every later remote read")))

(deftest a-quote-in-the-location-cannot-break-out-of-the-secret-check
  ;; Unlike `ensure-extension!`'s `ext`, whose docstring argues at length that a
  ;; closed caller set is the only thing closing its injection seam, this
  ;; argument is a location a person typed into the ＋ Data panel — and `x!`'s
  ;; `.execute` runs `;`-separated statements, measured in that same docstring.
  (let [{:keys [queries]} (remote-sql ["s3://b/x'); CREATE TABLE pwned(i INT); --"])]
    (is (= ["SELECT name FROM which_secret('s3://b/x''); CREATE TABLE pwned(i INT); --', 's3')"]
           queries)
        "the quote is doubled, so the payload stays inside the string literal")))

(deftest the-refusal-names-the-extension-its-cause-carries
  ;; `ensure-extension!` already puts the name it was asked for into ex-data, so
  ;; writing "httpfs" here as well was a second copy of the same fact. One
  ;; caller makes the two agree by luck; a second one loading `aws` — which
  ;; `ensure-remote!`'s docstring says an offline image will need — would make
  ;; this message name the wrong extension, in a sentence whose whole job is to
  ;; say which one.
  (let [aws (#'lake/remote-extension-problem
             "s3://b/x.csv" (ex-info "boom" {:extension "aws" :dir "/x"}))
        raw (#'lake/remote-extension-problem
             "s3://b/x.csv" (java.sql.SQLException. "boom"))]
    (is (str/includes? aws "DuckDB's aws extension is not available") aws)
    (is (not (str/includes? aws "httpfs"))
        (str "it named the extension the caller happens to use today: " aws))
    (is (str/includes? raw "DuckDB's httpfs extension is not available")
        (str "a cause carrying no ex-data must still say something true — httpfs "
             "is the only way to reach here: " raw))))

(deftest two-threads-arriving-together-issue-one-secret
  ;; The guard and the statement it guards were separated by a SIX SECOND
  ;; `CREATE SECRET`, so two threads in that window both found `:s3` absent and
  ;; both issued it. The loser gets `Invalid Input Error: Temporary secret with
  ;; name 'loci_s3' already exists!` — measured 2026-08-23 on DuckDB 1.5.5 by
  ;; issuing the statement twice on one connection — which the `catch` turns
  ;; into `credential-problem`, telling a machine whose credentials are FINE to
  ;; go and set AWS_ACCESS_KEY_ID.
  ;;
  ;; Not an invented concurrency requirement: `loci.source/describe` is
  ;; deliberately OUTSIDE `connect-lock` and `refresh!` takes no lock at all,
  ;; both on http-kit worker threads, so two tabs connecting s3:// sources is
  ;; the whole of what it takes. `source.clj` records a measured 8-thread race
  ;; on `mint-id` that lost seven objects, which is why `connect-lock` exists;
  ;; this is the same shape.
  ;;
  ;; **Deterministic, and not a load test.** The interleave is forced rather
  ;; than hoped for: the stub holds thread A inside the CREATE on a latch, so A
  ;; provably has not reached `(swap! ensured conj :s3)` when B starts, and B is
  ;; released to run with the guard in exactly the state that makes the race
  ;; real. Nothing here measures how often anything goes wrong — the two
  ;; outcomes are distinguished by which of them happened at all.
  (let [uri      "s3://bucket/sales.csv"
        sql      (atom [])
        seen     (atom 0)
        at-chain (java.util.concurrent.CountDownLatch. 1)
        release  (java.util.concurrent.CountDownLatch. 1)
        create?  #(str/includes? % "CREATE SECRET")
        ;; off for the warm-up below, or the warm-up is the thread that parks on
        ;; the latch and the suite never returns
        armed    (atom false)
        stub     (fn [_ s]
                   (swap! sql conj s)
                   (when (and @armed (create? s))
                     (if (= 1 (swap! seen inc))
                       ;; the first one in stands where the six seconds are.
                       ;; Bounded, so a mistake in the driver below fails the
                       ;; test instead of hanging the suite — which it did once.
                       (do (.countDown at-chain)
                           (.await release 30 java.util.concurrent.TimeUnit/SECONDS))
                       (throw (java.sql.SQLException.
                               (str "Invalid Input Error: Temporary secret with"
                                    " name 'loci_s3' already exists!")))))
                   :ok)]
    (with-redefs [lake/x! stub
                  lake/q  (fn [_ _] [])]
      ;; Warm every class this path touches before the race, so a
      ;; class-initialization monitor cannot be mistaken for ours below.
      (lake/ensure-remote! (bare-lake nil "/no-such-lake") uri)
      (reset! sql []) (reset! seen 0) (reset! armed true)
      (let [lk (bare-lake nil "/no-such-lake")
            a  (future (try (lake/ensure-remote! lk uri) (catch Throwable t t)))
            ;; A is provably inside the CREATE and provably has NOT yet set the
            ;; guard, which is the whole of what makes the interleave forced
            in? (.await at-chain 30 java.util.concurrent.TimeUnit/SECONDS)
            bx (atom nil)
            tb (doto (Thread. ^Runnable #(reset! bx (try (lake/ensure-remote! lk uri)
                                                         (catch Throwable t t))))
                 (.start))
            ;; B is now heading for one of two terminal states: parked on the
            ;; monitor, or through the guard and into the stub. Poll for
            ;; whichever arrives instead of sleeping a guessed interval — the
            ;; bound exists so a third possibility fails the test rather than
            ;; hanging the suite.
            outcome (loop [n 0]
                      (cond
                        (> @seen 1)                             :second-create-issued
                        (= Thread$State/BLOCKED (.getState tb)) :parked-on-the-monitor
                        (> n 20000)                             :neither
                        :else (do (Thread/sleep 1) (recur (inc n)))))]
        (.countDown release)
        (.join tb 30000)
        (let [ax @a]
          (is (true? in?) "A never reached the CREATE, so nothing below is testing a race")
          (is (= :parked-on-the-monitor outcome)
              (str "B got past a guard A had not yet set — the read-then-write is "
                   "not serialised: " outcome))
          (is (= 1 (count (filter create? @sql)))
              (str "the six-second statement was issued twice: " (pr-str @sql)))
          (is (= :ok ax) (str "the winner: " (pr-str ax)))
          (is (= :ok @bx)
              (str "the loser must WAIT and then find the work done, not be told "
                   "its credentials are broken: "
                   (if (instance? Throwable @bx) (.getMessage ^Throwable @bx)
                       (pr-str @bx))))
          (is (= #{:httpfs :s3} @(:ensured lk))))))))

(deftest a-failed-credential-chain-names-what-to-fix-and-is-not-remembered
  (let [{:keys [sql out ensured]} (remote-sql ["s3://bucket/x.csv" "s3://bucket/x.csv"]
                                              #(re-find #"(?i)create\s+secret" %))
        e   (first out)
        ;; `(some-> ^Throwable e .getMessage)` was the obvious spelling and it
        ;; is the wrong one: against an `ensure-remote!` that threw nothing,
        ;; `e` is `:ok`, the hint casts, and the test dies with a
        ;; ClassCastException BEFORE its first assertion — a failure that
        ;; reports the sabotage's shape instead of the assertion's meaning.
        msg (if (instance? Throwable e) (.getMessage ^Throwable e) (pr-str e))]
    (is (instance? clojure.lang.ExceptionInfo e)
        "loci's ex-info, not a raw SQLException escaping into connect!")
    (is (str/includes? msg "s3://bucket/x.csv") "the sentence names the location")
    (is (str/includes? msg "AWS_ACCESS_KEY_ID")
        (str "and names what to set. DuckDB's own text does not contain the word "
             "credentials and names no variable — it is `Secret Validation Failure: "
             "during `create` … Credential Chain: 'config'`, where 'config' is the "
             "chain's name and not a file loci wrote: " msg))
    (is (str/includes? msg "DuckDB said: injected")
        "with DuckDB's own error kept rather than swallowed")
    (is (= "injected" (when (instance? Throwable e) (some-> ^Throwable e .getCause .getMessage))))
    (is (= "s3://bucket/x.csv" (:uri (ex-data e))))
    (is (= ["LOAD httpfs"
            "CREATE SECRET loci_s3 (TYPE s3, PROVIDER credential_chain)"
            "CREATE SECRET loci_s3 (TYPE s3, PROVIDER credential_chain)"]
           sql)
        "the failure is not remembered, so the next connect retries the secret — a
         machine that grows credentials is one reconnect away and not one restart.
         And httpfs was NOT loaded again, which is what makes the guards separate
         rather than one flag doing two jobs")
    (is (= #{:httpfs} ensured))))

(deftest a-missing-httpfs-refuses-one-source-and-says-so
  ;; `extension-problem` puts its two consequence sentences behind `required?`,
  ;; because ", so the lake cannot open" is true only for ducklake — where
  ;; `open-lake!`'s try closes the connection and rethrows. That is right, and
  ;; it leaves httpfs with a message that names no consequence at all: loci
  ;; cannot load an extension, and nothing about what that means for the thing
  ;; the reader was doing. The consequence is the half only this caller knows,
  ;; and "refuse this source, not the store" is the requirement the whole
  ;; laziness exists to meet, so it belongs where the user reads it.
  (let [{:keys [sql out ensured]} (remote-sql ["https://example.com/a.csv"]
                                              #(str/starts-with? % "LOAD"))
        e   (first out)
        ;; See the sibling test above for why this is not `some->`.
        msg (if (instance? Throwable e) (.getMessage ^Throwable e) (pr-str e))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (str/includes? msg "https://example.com/a.csv") "which source was refused")
    (is (str/includes? msg "This source cannot be read; nothing else is affected")
        (str "the message never says what the failure costs: " msg))
    (is (not (str/includes? msg "so the lake cannot open"))
        (str "it claimed the store cannot open, which is ducklake's sentence: " msg))
    (is (str/includes? msg "Set LOCI_DUCKDB_EXTENSIONS to a directory holding the extension.")
        "and `extension-problem`'s actionable paragraph survives the wrapping. A
         consequence sentence that REPLACED the directory, the uid and the fix
         would be a worse message than the one it set out to improve")
    (is (= "httpfs" (:extension (ex-data e)))
        "ex-data keeps ensure-extension!'s keys under the wrapper's :uri")
    (is (= "https://example.com/a.csv" (:uri (ex-data e))))
    (is (= ["LOAD httpfs" "INSTALL httpfs" "LOAD httpfs"] sql)
        "the LOAD-then-INSTALL retry ran, so this failed the way a real missing
         extension fails and not one statement early")
    (is (empty? ensured)
        "and nothing was remembered, so a container that gains the extension does
         not need a restart before a source can use it")))

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
