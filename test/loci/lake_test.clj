(ns loci.lake-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [loci.lake :as lake]
            [loci.lake-fixture :refer [with-lake]]
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
