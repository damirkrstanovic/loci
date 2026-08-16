(ns loci.tools-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [loci.lake :as lake]
            [loci.lake-fixture]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools]))

(use-fixtures :once loci.lake-fixture/with-temp-lake)

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
    (is (= 30 (:market_share_pct (first rows))))          ; "~30% (estimated)" → 30
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
        rows (:value (sub/object st (:saved_as r)))]
    (is (= {:key_proponent_s "Canon" :how_it_works "stamp" :year 2026} (first rows)))
    (is (= #{"key_proponent_s" "how_it_works" "year"} (set (:columns r))))
    (is (= rows (edn/read-string (pr-str rows))))))

(deftest parse-csv-sanitizes-headers
  (let [rows (tools/parse-csv "Key Proponent(s),Total Revenue ($)\nCanon,5")]
    (is (= {:key_proponent_s "Canon" :total_revenue 5} (first rows)))))

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
