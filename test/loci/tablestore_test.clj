(ns loci.tablestore-test
  (:require [clojure.edn]
            [clojure.string :as str]
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

;; A DuckDB httpfs failure in the driver's own shape: a preamble about the
;; driver's state, the `Error:` paragraph that is the only line about what the
;; caller did, then a dump of SQL loci generated. `duckdb-said` keeps the middle
;; one, and "(HTTP 403)" appears in NO phrase loci writes — which is what makes
;; matching on it an actual test of whether the message was carried.
(def ^:private duckdb-403
  (str "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result\n"
       "Error: HTTP Error: HTTP GET error on 'https://acme-reports.s3.amazonaws.com/q3.csv' (HTTP 403)\n"
       "\n"
       "LINE 1: DESCRIBE SELECT * FROM read_csv('s3://acme-reports/q3.csv')\n"
       "                               ^"))

(defn- forbidden
  "A store whose every read fails the way an expired credential does."
  []
  (reify tbl/TableStore
    (put-rows! [_ _ _] nil)
    (rows [_ _ _] (throw (java.sql.SQLException. duckdb-403)))
    (row-count [_ h] (:rows h))
    (reclaimed-at [_ _] nil)
    (column-samples [_ _ _ _] nil)))

(defn- src-hydrate [uri]
  (tbl/hydrate (forbidden)
               {:id "tbl:q3" :kind :table :title "Q3"
                :value {:store :source :uri uri :format :csv :cols [:a]
                        :rows 1 :at "2026-08-23T10:00:00Z"}}))

(deftest a-remote-source-that-will-not-read-says-what-duckdb-said
  ;; `unreadable-because` takes a uri and nothing else, so over a remote one it
  ;; can name the location and no more — there is no filesystem it could have
  ;; consulted. The reason was in the exception this branch is already holding,
  ;; and dropping it drew a block headed "source unreachable" that could not say
  ;; whether the credentials had expired or the key was never written.
  (let [why (:table/unavailable (src-hydrate "s3://acme-reports/q3.csv"))]
    (is (string? why))
    (is (str/includes? why "s3://acme-reports/q3.csv")
        (str "the location, verbatim: " why))
    (is (str/includes? why "(HTTP 403)")
        (str "and DuckDB's own account, which is the only account of WHY that
              exists on this branch: " why))
    (is (not (str/includes? why "pending query result"))
        (str "through duckdb-said, so not the driver's state: " why))
    (is (not (str/includes? why "LINE 1"))
        (str "and not a dump of SQL the caller never wrote: " why))))

(deftest a-local-unavailable-source-stays-in-locis-own-words
  ;; The control, and what fails if the append above is written unconditionally.
  ;; loci looked at the filesystem to choose this branch and knows the real
  ;; answer, so DuckDB's line is the same fact in a worse voice — the rule this
  ;; file's docstring states, and the one the remote branch is an exception to
  ;; because there loci looked at nothing.
  (let [why (:table/unavailable (src-hydrate (str "file:///no/such/dir-" (System/nanoTime)
                                                  "/q3.csv")))]
    (is (str/includes? why "there is no file at")
        (str "loci's own phrase, from the filesystem it asked: " why))
    (is (not (str/includes? why "HTTP 403"))
        (str "and nothing appended over it — a local phrase is the whole message: " why))))

(deftest check-names-refuses-two-names-that-become-one-column
  (is (nil? (tbl/check-names! ["Total Revenue ($)" "Region"]))
      "names that normalize apart pass silently")
  (let [e (try (tbl/check-names! ["a b" "a_b"]) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "two names normalizing to one column must throw")
    (is (= :a_b (:column (ex-data e))))
    (is (= ["a b" "a_b"] (sort (map str (:from (ex-data e)))))))
  (let [e (try (tbl/normalize-rows [{"a b" 1 :a_b 2}]) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :a_b (:column (ex-data e)))
        "normalize-rows must keep throwing exactly as it did, through the same check")))

(deftest handles-in-history-finds-a-handle-inside-a-tx
  (let [st (sub/fresh-store)
        h  {:store :lake :table "t_one" :snapshot 1}]
    (sub/commit! st {:op :tx :events [{:op :put :id "tbl:a" :value {:id "tbl:a" :value h}}
                                      {:op :put :id "note:a" :value {:id "note:a" :value "x"}}]})
    (let [by-obj (tbl/handles-in-history st)]
      (is (= ["t_one"] (map #(:table (:handle %)) (get by-obj "tbl:a")))
          "a sub-event's handle is found — a walk that saw only top-level events would call
           this orphaned and reclaim rows a live object is still using")
      (is (= [(:ts (first (sub/history st)))] (mapv :ts (get by-obj "tbl:a")))
          ;; Not `(every? :ts …)`, which the plan spelled and which CANNOT FAIL:
          ;; `every?` over an empty seq is true, so it passes in precisely the
          ;; state the assertion above exists to catch — the walk finding no
          ;; sub-event at all. Measured 2026-08-22 by breaking the `:tx` branch:
          ;; the first assertion failed, this one did not. Comparing against the
          ;; transaction's own committed stamp cannot pass vacuously, and pins
          ;; *inherits the transaction's* rather than merely *has one*. Read from
          ;; the log, not written by the test, because `commit!` overwrites any
          ;; `:ts` a caller supplies.
          "and inherits the transaction's :ts, because a sub-event carries none of its own"))))

(deftest handles-in-history-carries-the-source-a-materialised-refresh-drops
  (let [st  (sub/fresh-store)
        src {:uri "loci://files/abc.csv" :format :csv :mode :materialise}
        v1  {:store :lake :table "t_one" :snapshot 1}
        v2  {:store :lake :table "t_two" :snapshot 2}]
    (sub/commit! st {:op :put :id "tbl:a" :value {:id "tbl:a" :value v1 :source src}})
    (sub/commit! st {:op :assoc :id "tbl:a" :path [:value] :value v2})
    (let [hs (get (tbl/handles-in-history st) "tbl:a")]
      (is (= ["t_two" "t_one"] (map #(:table (:handle %)) hs))
          "newest first, both versions")
      (is (= [nil "loci://files/abc.csv"] (map #(:uri (:source %)) hs))
          "the refresh names no uri — only the :put does. A files GC reading handles alone
           would find this file unreferenced and reclaim a live table's source."))))

(deftest handles-in-history-finds-a-passthrough-uri-on-the-handle-itself
  (let [st (sub/fresh-store)
        h  {:store :source :uri "loci://files/live.csv" :format :csv}]
    (sub/commit! st {:op :put :id "tbl:b" :value {:id "tbl:b" :value h :source {:uri "loci://files/live.csv"}}})
    (is (= "loci://files/live.csv" (:uri (:handle (first (get (tbl/handles-in-history st) "tbl:b")))))
        "a passthrough handle IS the recipe and names its own location")))

(deftest handles-in-history-ignores-a-handle-that-is-not-at-the-object-s-value
  ;; An entry asserts *this object's value IS this handle*, so a handle sitting
  ;; at a deeper path produces a WRONG entry, not a missing one — and wrong is
  ;; the worse of the two. `lakegc/classify` folds entries into
  ;; `{table {:object :ts :age-rank}}`, last write wins per table name, so a
  ;; spurious entry can *replace* the true owner's and flip a version `undo!`
  ;; can still reach from `:in-window` to `:reclaimable`. `reclaim!`'s live-set
  ;; guard does not catch it: that guard catches under-coverage (a live table
  ;; called `:orphaned`), and this is over-coverage.
  ;;
  ;; Neither shape below is committed anywhere in `src/` today — checked
  ;; 2026-08-22, every handle-carrying site is a `:put` or an `:assoc` at
  ;; exactly `[:value]`. This pins the closed shape so the docstring stays true
  ;; when a third writer appears.
  (let [st   (sub/fresh-store)
        real {:store :lake :table "t_real" :snapshot 1}
        prev {:store :lake :table "t_prev" :snapshot 2}]
    (sub/commit! st {:op :put :id "nb:1" :value {:id "nb:1" :value real}})
    (sub/commit! st {:op :assoc :id "nb:1" :path [:value :preview] :value prev})
    (sub/commit! st {:op :append :id "nb:1" :path [:value :cells] :value {:value prev}})
    (is (= ["t_real"] (map #(:table (:handle %)) (get (tbl/handles-in-history st) "nb:1")))
        ;; Two-sided on purpose: it fails if the gate lets the deeper paths
        ;; through (ungated it reads ["t_prev" "t_prev" "t_real"], the newest of
        ;; which is what displaces the true owner) AND it fails if the gate is
        ;; too tight and drops the `:put` as well. `(is (empty? …))` over the
        ;; deeper events alone would pass in the state where the walk finds
        ;; nothing at all, which is the other way this can be broken.
        "only a :put and an :assoc at exactly [:value] name the object's value — a handle
         at [:value :preview] or inside an appended cell must yield no entry at all")))

(def ^:private many (mapv (fn [i] {:id i :grp (str "g" (mod i 5)) :note nil}) (range 250)))

(deftest a-window-limit-is-clamped-and-defaulted
  (is (= 100 (tbl/page-limit nil))    "no limit means 100")
  (is (= 100 (tbl/page-limit "")) "a blank query-string limit means 100")
  (is (= 200 (tbl/page-limit 5000))   "200 is the ceiling")
  (is (= 1   (tbl/page-limit 0))      "1 is the floor")
  (is (= 25  (tbl/page-limit "25"))   "a query string arrives as a string"))

(deftest a-cursor-round-trips-and-a-mangled-one-is-page-one
  (is (= {:k 41} (tbl/decode-cursor (tbl/encode-cursor {:k 41}))))
  (is (nil? (tbl/decode-cursor nil)))
  (is (nil? (tbl/decode-cursor "")))
  (is (nil? (tbl/decode-cursor "not-base64-at-all!!"))
      "a bookmarked cursor that rotted restarts the table rather than 500ing")
  (is (nil? (tbl/decode-cursor (tbl/encode-cursor [1 2 3])))
      "a cursor that decodes to something other than a map is not a cursor"))

(deftest inline-pages-forward-to-the-end
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)
        p1 (tbl/page ts h {:limit 100})
        p2 (tbl/page ts h {:limit 100 :cursor (:next p1)})
        p3 (tbl/page ts h {:limit 100 :cursor (:next p2)})]
    (is (= 100 (count (:rows p1))))
    (is (= 0   (:id (first (:rows p1)))))
    (is (= 100 (:id (first (:rows p2)))) "page 2 starts where page 1 stopped")
    (is (= 50  (count (:rows p3))))
    (is (nil?  (:next p3)) "the last page says so, and nothing asks for a fourth")))

(deftest inline-page-says-next-exists-when-one-more-row-remains
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:boundary" (mapv #(hash-map :id %) (range 101)))]
    (is (some? (:next (tbl/page ts h {:limit 100})))
        "101 rows, limit 100 — exactly one row remains")))

(deftest inline-page-says-no-next-when-exactly-full
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:exact" (mapv #(hash-map :id %) (range 100)))]
    (is (nil? (:next (tbl/page ts h {:limit 100})))
        "exactly limit rows means no next page, not just a short one")))

(deftest a-cursor-with-a-wrong-typed-field-is-page-one-not-a-crash
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)
        p  (tbl/page ts h {:limit 100 :cursor (tbl/encode-cursor {:i "not-a-number"})})]
    (is (= 100 (count (:rows p))))
    (is (= 0   (:id (first (:rows p))))
        "a crafted cursor with a wrong-typed field restarts at page 1, not a crash")))

(deftest a-cursor-field-falls-back-to-what-its-store-means-by-the-start
  ;; `:i`/`:o` are indices — page 1 starts at row 0. `:k` (Lake, Task 2) is a
  ;; bound on a 0-based rowid read back as `WHERE rowid > k`, so ITS start is
  ;; -1: `rowid > 0` would skip row 0 of every table. The 2-arity's hardcoded
  ;; 0 is right for the first pair and wrong for the second, which is why
  ;; cursor-int takes the default explicitly rather than assuming it.
  (is (= 0  (tbl/cursor-int nil :i))     "an index starts at 0")
  (is (= 0  (tbl/cursor-int {:i "x"} :i)) "and a wrong-typed one restarts")
  (is (= -1 (tbl/cursor-int nil :k -1))
      "a keyset bound starts BEFORE the first rowid — 0 would skip row 0")
  (is (= -1 (tbl/cursor-int {:k "x"} :k -1))
      "and a wrong-typed bound restarts at the same place"))

(deftest a-window-projects-only-the-columns-it-asks-for
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)]
    (is (= [:id] (keys (first (:rows (tbl/page ts h {:limit 1 :cols ["id"]})))))
        "hiding a column cuts the payload, not only the pixels")
    (is (= [:id :grp :note] (tbl/project-cols h ["nope"]))
        "an unknown column name leaves the projection alone")
    (is (= [:id :grp] (tbl/project-cols h ["grp" "id"]))
        "handle order, not the caller's — q-as names a result set positionally")))

(deftest a-sort-is-validated-against-the-handle
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)]
    (is (= ["id" "desc"] (tbl/sort-spec h ["id" "desc"])))
    (is (= ["id" "asc"]  (tbl/sort-spec h ["id" "sideways"])) "an odd direction is asc")
    (is (nil? (tbl/sort-spec h ["; DROP TABLE" "asc"]))
        "a column the handle does not have is no sort at all")))

(deftest inline-sorts-with-nulls-last-in-both-directions
  (let [ts   (tbl/inline-table)
        h    (tbl/put-rows! ts "tbl:n" [{:v 2} {:v nil} {:v 1}])
        vals #(mapv :v (:rows (tbl/page ts h {:limit 10 :sort %})))]
    (is (= [1 2 nil]  (vals ["v" "asc"])))
    (is (= [2 1 nil]  (vals ["v" "desc"])) "nulls stay last when the sort flips")))

(deftest a-page-reports-where-it-starts
  ;; The elision's caption reads `showing 101–200 of 250`, and the 101 is a fact
  ;; the store knows rather than arithmetic the caller does over a page size it
  ;; may have changed mid-walk.
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)
        p1 (tbl/page ts h {:limit 100})
        p2 (tbl/page ts h {:limit 100 :cursor (:next p1)})]
    (is (= 0   (:offset p1)) "page one starts at row zero")
    (is (= 100 (:offset p2)) "and page two says so without the caller counting")))

;; ---------------------------------------------------------------------------
;; a table answers a question: query and create-from-spec
;; ---------------------------------------------------------------------------

(def qrows
  (vec (for [n (range 30)]
         {:region (str "r" (mod n 3)) :v (double n) :name (str "n" n)})))

(deftest inline-query-groups-and-totals
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        r  (tbl/query ts h {:group_by "region" :measure "v" :agg "sum"})]
    (is (= 3 (:total r)) ":total is the answer's size, not the page's")
    (is (= 3 (count (:rows r))))
    (is (= 435.0 (reduce + (map :v (:rows r)))) "every row is summed, not a sample")))

(deftest inline-query-returns-keyword-keys
  ;; The two stores implement ONE protocol method and must agree on key type.
  ;; viewspec builds a grouped row with (array-map (name gb) ...), which is a
  ;; String key; Lake/query returns keywords via sub/col-kw. Without this,
  ;; (:region row) reads nil on one store and works on the other, and nothing
  ;; notices until far downstream.
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)]
    (doseq [spec [{:group_by "region" :measure "v" :agg "sum"}
                  {:group_by "region" :agg "count"}
                  {}]]
      (let [row (first (:rows (tbl/query ts h spec)))]
        (is (every? keyword? (keys row))
            (str "string keys leaked for " (pr-str spec) ": " (pr-str (keys row))))))
    (is (= #{"r0" "r1" "r2"}
           (set (map :region (:rows (tbl/query ts h {:group_by "region" :measure "v"}))))))))

(deftest inline-query-total-survives-a-limit
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        r  (tbl/query ts h {:limit 4})]
    (is (= 4 (count (:rows r))))
    (is (= 30 (:total r)) ":total must be the full answer, or a report lies about it")
    (is (true? (:capped r)))))

(deftest inline-query-is-not-capped-when-it-fits
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)]
    (is (false? (:capped (tbl/query ts h {:limit 100}))))
    (is (false? (:capped (tbl/query ts h {}))))))

(deftest inline-query-filters
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)]
    (is (= 20 (:total (tbl/query ts h {:filter {:col "v" :op ">" :val 9}}))))
    (is (= 10 (:total (tbl/query ts h {:filter {:col "region" :op "=" :val "r0"}}))))
    (is (= 20 (:total (tbl/query ts h {:filter {:col "region" :op "≠" :val "r0"}}))))
    (is (= 11 (:total (tbl/query ts h {:filter {:col "name" :op "contains" :val "N1"}})))
        "contains is case-insensitive: n1 plus n10..n19")))

(deftest inline-query-min-and-max
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)]
    (is (= 0.0  (:v (first (:rows (tbl/query ts h {:group_by "region" :measure "v" :agg "min"}))))))
    (is (= 27.0 (:v (first (:rows (tbl/query ts h {:group_by "region" :measure "v" :agg "max"}))))))))

(deftest inline-column-profile
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:p"
                          (vec (for [n (range 40)]
                                 {:region (str "r" (mod n 3))
                                  :v (double n)
                                  :note (str "unique-" n)})))
        p  (tbl/column-profile ts h)]
    (is (= [:region :v :note] (:cols p)))
    (is (= [:v] (:numeric p)))
    (is (= [:region] (:categorical p)) ":note has 40 distinct values — not a category")
    (is (= 40 (:rows p)))))

(deftest inline-create-from-spec-returns-a-handle
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:q" qrows)
        h2 (tbl/create-from-spec ts h {:group_by "region" :measure "v" :agg "sum"}
                                 "tbl:derived-1" "By region")]
    (is (tbl/handle? h2))
    (is (= 3 (:rows h2)))
    (is (= 3 (count (tbl/rows ts h2 {}))))
    (is (every? keyword? (keys (first (tbl/rows ts h2 {})))))))
