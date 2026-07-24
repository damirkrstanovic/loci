(ns loci.tools-test
  (:require [clojure.test :refer [deftest is]]
            [loci.tools :as tools]))

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

(deftest md-table-salvage-honest-nil
  (is (nil? (tools/md-table->rows "no table here at all")))
  (is (nil? (tools/md-table->rows "just one | piped line"))))
