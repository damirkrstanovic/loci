;; Step 2 of Task 1: does each committed table actually mold the way it must?
;; Reads corpus/*.csv exactly as loci does (tools/parse-csv -> col-kw headers)
;; and asks mold/viewers-for. Opens no store.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[loci.tools :as tools]
         '[loci.mold :as mold]
         '[loci.content])

(def cat-col     @#'loci.content/cat-col)
(def measure-col @#'loci.content/measure-col)
(def numeric-cols @#'loci.content/numeric-cols)
(def string-cols  @#'loci.content/string-cols)

(def expected
  {"art-objects.csv"            #{:table/rows :table/summary :table/cards :table/pivot :table/bar :table/hist}
   "mineral-production.csv"     #{:table/rows :table/summary :table/cards :table/pivot :table/bar :table/hist}
   "lead-phaseout.csv"          #{:table/rows :table/summary :table/cards :table/pivot :table/bar :table/hist}
   "elections-turnout.csv"      #{:table/rows :table/summary :table/cards :table/pivot :table/bar :table/hist}
   "interest-rates-monthly.csv" #{:table/rows :table/summary :table/cards :table/pivot :table/bar :table/hist
                                  :table/line :table/kpi}})

(def files (->> (.listFiles (io/file "corpus"))
                (filter #(str/ends-with? (.getName %) ".csv"))
                (sort-by #(.getName %))))

(def failures (atom []))

(doseq [f files]
  (let [nm   (.getName f)
        rows (tools/parse-csv (slurp f))
        ids  (mapv :id (mold/viewers-for rows))
        cc   (cat-col rows)
        mc   (measure-col rows)
        want (get expected nm)]
    (println "\n====" nm)
    (println "  rows       :" (count rows))
    (println "  columns    :" (vec (keys (first rows))))
    (println "  map type   :" (.getSimpleName (class (first rows))))
    (println "  string cols:" (vec (for [c (string-cols rows)]
                                     [c (count (distinct (map #(get % c) rows)))])))
    (println "  numeric    :" (vec (numeric-cols rows)))
    (println "  cat-col    :" cc "  measure-col:" mc)
    (println "  has :month :" (contains? (first rows) :month))
    (println "  VIEWERS    :" ids)
    (when want
      (let [missing (remove (set ids) want)]
        (if (seq missing)
          (do (println "  *** MISSING" (vec missing))
              (swap! failures conj [nm (vec missing)]))
          (println "  ok — every expected viewer present"))))
    (when-not (some #{:table/pivot} ids)
      (println "  *** NO PIVOT — the coarse column is wrong")
      (swap! failures conj [nm :no-pivot]))))

;; ---------------------------------------------------------------------------
;; the prose half: corpus/prose/*.md must mold as a document, not as raw data.
;; `doc?` is just `string?`, so anything slurped qualifies — what is worth
;; asserting is that :doc/outline finds the headings, because a document whose
;; outline is empty is one LEAP's `in text` group can rank but a reader cannot
;; navigate.
;; ---------------------------------------------------------------------------
(def prose (->> (.listFiles (io/file "corpus/prose"))
                (filter #(str/ends-with? (.getName %) ".md"))
                (sort-by #(.getName %))))

(println "\n==== prose")
(doseq [f prose]
  (let [text (slurp f)
        ids  (mapv :id (mold/viewers-for text))
        out  (:rendered (mold/mold text :doc/outline))]
    (println (format "  %-44s %s  headings=%d  bytes=%d"
                     (.getName f) (pr-str ids) (count out) (count text)))
    (when-not (= ids [:doc/raw :doc/outline])
      (println "  *** unexpected viewers") (swap! failures conj [(.getName f) ids]))
    (when (< (count out) 2)
      (println "  *** outline too thin") (swap! failures conj [(.getName f) :thin-outline]))
    ;; every piece must carry its provenance in the body, since that is where a
    ;; reader sees it — a quote with no citation is the thing this corpus is not
    (doseq [marker ["## Source and licence" "**Licence:"]]
      (when-not (str/includes? text marker)
        (println "  *** missing" marker)
        (swap! failures conj [(.getName f) marker])))))

(println "\n==== summary")
(if (seq @failures)
  (do (println "FAILURES:" @failures) (System/exit 1))
  (println "all tables mold as required; all prose molds as a document with its citation"))
