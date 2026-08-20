(ns loci.tools-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [loci.fnlib :as fnlib]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.server :as server]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools]))

(use-fixtures :once loci.lake-fixture/with-temp-lake)

(defn- with-temp-data-dir
  "Run `f` with `sub/data-dir` answering a fresh temp directory, removed after.

   `import-csv!` writes the posted text under `<LOCI_DATA>/files/` before it
   connects, so without this the suite drops a CSV into the real `data/` on
   every run. Redefining the ONE function is what keeps the round trip whole:
   `sourcespec/locate` resolves `loci://` through the same var, so the write
   and the read cannot end up in different directories."
  [f]
  (let [d (str (System/getProperty "java.io.tmpdir") "/loci-tools-data-" (System/nanoTime))]
    (try (with-redefs [sub/data-dir (constantly d)] (f))
         (finally (lf/rm-rf d)))))

(deftest md-table-salvage-parses-prose-tables
  ;; live-fire finding #3: the model wrote the table INTO the note instead of
  ;; calling save_table. Deterministic salvage — its own text, parsed by rules.
  (let [text (str "Based on the research gathered:\n\n"
                  "**Supplier** | **market_share_pct**\n"
                  ":-- | :--\n"
                  "JSR Corporation (Japan) | ~30% (estimated)\n"
                  "Tokyo Ohka Kogyo | ~25%\n\n"
                  "- Japanese firms dominate.")
        rows (tools/md-table->rows text)]
    (is (= 2 (count rows)))
    (is (= "JSR Corporation (Japan)" (:supplier (first rows))))
    (is (= 30 (:market_share_pct (first rows))))         ; "~30% (estimated)" → 30
    (is (= 25 (:market_share_pct (second rows))))))

(deftest md-table-salvage-piped-and-typed
  (let [rows (tools/md-table->rows "| name | q | when |\n|---|---|---|\n| A | 1.5 | Q3 2025 |")]
    (is (= [{:name "A" :q 1.5 :when "Q3 2025"}] rows))))  ; letters-first cells stay strings

(deftest md-table-salvage-never-mangles-numbers
  ;; final-review finding: thousands separators and dates must NOT half-parse
  (let [rows (tools/md-table->rows "a | b | c | d\n---|---|---|---\n$1,234 | 2025-06-01 | ~30% | -5")]
    (is (= "$1,234" (:a (first rows))))                   ; not 1
    (is (= "2025-06-01" (:b (first rows))))               ; not 2025
    (is (= 30 (:c (first rows))))
    (is (= -5 (:d (first rows))))))

(deftest md-table-salvage-honest-nil
  (is (nil? (tools/md-table->rows "no table here at all")))
  (is (nil? (tools/md-table->rows "just one | piped line"))))

;; ---- every ingest path must mint readable column keywords ----
;; save_table (agent JSON) and CSV headers used to keywordize verbatim, so a
;; column called "Key Proponent(s)" became a keyword the log could not read back.

(deftest save-table-sanitizes-agent-column-names
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{(keyword "Key Proponent(s)") "Canon"
                                       (keyword "How It Works") "stamp"
                                       :year 2026}])
        h    (:value (sub/object st (:saved_as r)))
        rows (tbl/rows @lake/lake h {})]
    (is (= {:key_proponent_s "Canon" :how_it_works "stamp" :year 2026} (first rows)))
    (is (= #{"key_proponent_s" "how_it_works" "year"} (set (:columns r))))
    ;; the log now holds the handle, so the handle is what has to read back —
    ;; and `:cols` is where the column keywords travel through it as a *value*
    (is (= h (edn/read-string (pr-str h))))))

(deftest import-csv-sanitizes-headers-through-read-csv
  ;; What `parse-csv` used to prove, proved where the work now happens. The
  ;; header normalization is still `sub/col-kw`; the inference underneath it is
  ;; DuckDB's sniffer and nothing else in loci.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            r  (server/import-csv! st "T" "Key Proponent(s),Total Revenue ($)\nCanon,5\n" nil)
            o  (tbl/hydrate @lake/lake (sub/object st (:openId r)))]
        (is (nil? (:error r)))
        (is (= [{:key_proponent_s "Canon" :total_revenue 5}] (:value o))
            "the header normalization is the same; the inference is DuckDB's")))))

;; ---- the agent's two table tools read through the handle, not around it ----
;; A table's rows live in the lake and the log holds a handle. Both tools used
;; to read `(:value o)` straight: list_tables died on the handle's keys, and
;; query_table silently returned the handle's OWN keys as the user's data.

(deftest list-tables-reads-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (let [r (first (tools/dispatch st "list_tables" {}))]
      (is (= ["a" "b"] (:columns r)) "columns come from the rows, not the handle's keys")
      (is (= 2 (:rows r))))))

(deftest query-table-reads-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (is (= [{:a 1 :b "p"} {:a 2 :b "q"}]
           (:rows (tools/dispatch st "query_table" {:table_id "tbl:x"})))
        "the handle's own keys must never be returned as rows")))

;; ---- the writers land a handle: the point of the whole move ----

(deftest saved-table-stores-a-handle-not-rows
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{:a 1}])]
    (is (tbl/handle? (:value (sub/object st (:saved_as r))))
        "rows must not be in the event")))

(deftest a-writer-reports-the-columns-it-stored
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{(keyword "Total Sales") 1}])]
    (is (= ["total_sales"] (:columns r))
        "the model must be told the name query_table will accept, not the one it sent")
    (is (= [:total_sales] (:cols (:value (sub/object st (:saved_as r))))))))

(deftest the-log-holds-a-handle-and-not-the-rows
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" (mapv (fn [i] {:i i}) (range 100)))
        ev (last (sub/history st))]
    (is (not (re-find #"\{:i 7\}" (pr-str ev)))
        "a row must not appear anywhere in the event")
    (is (= 100 (:rows (:value (:value ev)))))))

(deftest a-table-with-no-rows-keeps-them-in-the-object
  ;; `Lake/put-rows!` refuses an empty table, and a filter that matched nothing
  ;; is not an error — `compute-clj!`, the ƒ apply and `query_table :save_as` all
  ;; committed `:value []` before tables moved and must go on doing so.
  (let [o (tbl/table-object @lake/lake "tbl:empty" "Nothing" [])]
    (is (= [] (:value o)))
    (is (not (tbl/handle? (:value o))))
    (is (= [] (:value (tbl/hydrate lake/lake o)))
        "and it hydrates to the empty table the shell already draws")))

(deftest a-save_as-that-filtered-everything-out-is-an-empty-table
  ;; the reachable half of the empty-rows change: `vs/interpret` with a filter
  ;; that matched nothing returns [], and `Lake/put-rows!` refuses it. Left
  ;; unguarded the throw escapes `dispatch` into the agent's tool loop, where
  ;; `draft-brief!` turns the whole brief into "(agent unavailable: …)".
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])
        _  (sub/commit! st {:op :put :id "tbl:x"
                            :value {:id "tbl:x" :kind :table :title "X" :value h}})
        r  (tools/dispatch st "query_table" {:table_id "tbl:x" :save_as "nothing"
                                             :filter {:col "b" :op "=" :val "zzz"}})]
    (is (= [] (:rows r)))
    (is (= [] (:value (sub/object st (:saved_as r)))))))

(deftest a-column-collision-is-a-tool-error-not-a-throw
  ;; A model that emits both "Total Sales" and "total_sales" names two columns
  ;; that become one, and `normalize-rows` refuses. Nothing above catches it —
  ;; `agent/chat-tools` does not wrap `tool-fn` — so the throw ran all the way to
  ;; `draft-brief!`'s catch and blanked the whole brief.
  (let [st (sub/fresh-store)
        r  (tools/save-table! st "T" [{(keyword "Total Sales") 1 :total_sales 2}])]
    (is (re-find #"two column names would become one column" (str (:error r))))
    (is (nil? (:saved_as r)))
    (is (zero? (count (sub/history st))) "and it commits nothing on that path")))

(deftest a-save_as-with-rows-lands-a-handle
  ;; the converted writer's positive case — the existing save_as test covers
  ;; only the empty branch, and `hydrate` cannot tell a literal from a handle
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])
        _  (sub/commit! st {:op :put :id "tbl:x"
                            :value {:id "tbl:x" :kind :table :title "X" :value h}})
        r  (tools/dispatch st "query_table" {:table_id "tbl:x" :save_as "kept"})]
    (is (tbl/handle? (:value (sub/object st (:saved_as r)))))
    (is (= 2 (:rows (:value (sub/object st (:saved_as r))))))))

(deftest list-tables-reads-no-rows
  ;; `list_tables` wants columns and a row count, and a handle already carries
  ;; both. It used to hydrate to get them, which on the migrated store cost
  ;; 271 ms for 31 tables — DuckDB's JDBC driver is slow row-wise, and this
  ;; reader never needed a row.
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! (tbl/inline-table) "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (with-redefs [tbl/rows (fn [& _] (throw (ex-info "list_tables fetched rows" {})))]
      (let [r (first (tools/dispatch st "list_tables" {}))]
        (is (= ["a" "b"] (:columns r)))
        (is (= 2 (:rows r)))))))

;; ---------------------------------------------------------------------------
;; a reclaimed table, on the two reader paths tools.clj owns
;; ---------------------------------------------------------------------------

(deftest list-tables-says-reclaimed-instead-of-a-stale-count
  ;; `do-list` reads the HANDLE and never hydrates, which is what makes it fast
  ;; — and is exactly why it cannot see a reclamation the way the other readers
  ;; do: the handle still says 100 rows long after the Parquet is gone.
  (lf/with-reclaimed-table
    (fn [st]
      (let [by-id (into {} (map (juxt :id identity)) (tools/dispatch st "list_tables" {}))]
        (is (:reclaimed (get by-id "tbl:gone"))
            "reading the handle would otherwise report 100 rows that cannot be fetched")
        (is (= 1 (:rows (get by-id "tbl:good"))))))))

(deftest query-table-returns-an-error-for-a-reclaimed-table
  (lf/with-reclaimed-table
    (fn [st]
      (let [r (tools/dispatch st "query_table" {:table_id "tbl:gone"})]
        (is (:error r) "the model gets something it can act on")
        (is (re-find #"reclaimed" (:error r)))))))

(deftest one-reclamation-is-spelled-one-way-on-the-agent-surface
  ;; `list_tables` ships a Date, which clojure.data.json renders ISO-UTC;
  ;; `query_table` builds a string, which used to interpolate the same Date and
  ;; get `Date/toString`'s "Mon Aug 17 13:40:29 CEST 2026". Same instant, same
  ;; tool loop, one message apart, and nothing tells a model they are the same
  ;; event.
  (lf/with-reclaimed-table
    (fn [st]
      (let [row  (first (filter #(= "tbl:gone" (:id %)) (tools/dispatch st "list_tables" {})))
            wire (json/read-str (json/write-str row) :key-fn keyword)
            err  (:error (tools/dispatch st "query_table" {:table_id "tbl:gone"}))]
        (is (re-find #"\d{4}-\d{2}-\d{2}T[\d:.]+Z" (:reclaimed wire))
            "list_tables no longer reaches the model as ISO-UTC")
        (is (str/includes? err (:reclaimed wire))
            (str "two spellings of one instant: " (pr-str (:reclaimed wire)) " vs " (pr-str err)))))))

(deftest a-salvaged-number-column-is-numeric-to-the-palette
  ;; The property that actually degrades if `md-cell-val` returns a string.
  ;; Pinned on `numeric-cols` rather than on `catalog` because `catalog` still
  ;; LISTS Group, Top N, Change and Share — greyed, with a :why naming a numeric
  ;; column"` — so its shape does not change and an assertion on it would not
  ;; bite. What changes is whether the column counts as a number at all.
  (let [rows (tools/md-table->rows "| region | share |\n|---|---|\n| EMEA | ~30% |\n| APAC | ~25% |\n")]
    (is (= [:share] (fnlib/numeric-cols rows)))))
