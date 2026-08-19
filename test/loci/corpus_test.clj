(ns loci.corpus-test
  "The corpus import, end to end, against a `sub/fresh-store` and the real
   `corpus/` directory.

   **The fixture is the committed corpus itself**, not a miniature of it. A
   two-row stand-in would prove the code runs and would prove nothing about the
   thing the import exists to guarantee — that these five files still carry the
   coarse column Task 1 bucketed them for, and still mold the way
   `corpus/fetch/verify_molds.clj` measured. That assertion is the join between
   Task 1 and Task 2 and it can only be made against the real files.

   Everything the tests read out of `corpus/MANIFEST.edn` is read **here**, with
   `edn/read-string`, and never through `loci.corpus`. A test that asked the
   importer what the manifest says would agree with the importer by
   construction.

   Nothing here opens `data/`: the substrate is `sub/fresh-store`, and the
   refusal tests copy `corpus/` into a temp directory and mangle the copy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [loci.content]
            [loci.corpus :as corpus]
            [loci.entities :as ent]
            [loci.lake :as lake]
            [loci.lake-fixture]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; `mint-hub` lands its rows in the lake now, so every `import-corpus!` in here
;; — including the DRY runs, which mint the same objects in order to report the
;; plan — reaches `@lake/lake`. Without this the suite would open <data-dir>/lake.
(use-fixtures :once loci.lake-fixture/with-temp-lake)

(def ^:private dir "corpus")

(def manifest
  "Read once, independently of `loci.corpus`."
  (delay (edn/read-string (slurp (io/file dir "MANIFEST.edn")))))

(defn- tables      [] (:files @manifest))
(defn- prose-files [] (:files (:prose @manifest)))

(defn- squash
  "Whitespace collapsed — the manifest's long strings are hard-wrapped EDN
   literals, so `:attribution` arrives with newlines in it. Written out here
   rather than borrowed from `loci.corpus` for this namespace's whole reason."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn- ran! [st & [opts]]
  (let [r (corpus/import-corpus! st (merge {:dir dir :dry-run? false} opts))]
    (is (:ok? r) (str "import refused: " (:error r)))
    r))

(defn- notebooks-by-file [st]
  (into {} (keep (fn [o] (when-let [f (get-in o [:value :imported-from])] [f o])))
        (nb/notebooks st)))

;; ---------------------------------------------------------------------------
;; a dry run writes nothing, and the plan names everything
;; ---------------------------------------------------------------------------

(deftest dry-run-writes-nothing-and-names-everything
  (let [st (sub/fresh-store)
        r  (corpus/import-corpus! st {:dir dir})
        p  (:plan r)]
    (is (:ok? r) (str "dry run refused: " (:error r)))
    (is (:dry-run? r))

    (testing "nothing was written"
      (is (zero? (count (sub/history st))))
      (is (empty? (sub/objects st))))

    (testing "every notebook is named"
      (is (= (count (tables)) (count (:notebooks p))))
      (is (= (set (map :file (tables))) (set (map :from (:notebooks p)))))
      (is (every? (comp seq :title) (:notebooks p)))
      (is (every? (comp seq :id) (:notebooks p))))

    (testing "every table is named, with the manifest's title and row count"
      (let [by-file (into {} (map (juxt (comp :file :table) :table)) (:notebooks p))]
        (is (= (count (tables)) (count by-file)))
        (doseq [{:keys [file title rows]} (tables)]
          (is (= title (:title (by-file file))) file)
          (is (= (:committed rows) (:rows (by-file file))) file))))

    (testing "every document is named, once, with the manifest's title"
      (let [docs (mapcat :documents (:notebooks p))]
        (is (= (count (prose-files)) (count docs)))
        (is (= (mapv :file (sort-by :file (prose-files)))
               (mapv :file (sort-by :file docs)))
            "each prose file appears exactly once across the notebooks")
        (let [by-file (into {} (map (juxt :file identity)) docs)]
          (doseq [{:keys [file title]} (prose-files)]
            (is (= title (:title (by-file file))) file)))))

    (testing "the shape of the run it would make"
      (is (= (count (tables)) (:commits p)) "one commit per notebook")
      (is (= (count (tables)) (:tables p)))
      (is (= (count (prose-files)) (:documents p)))
      (is (= (+ (count (tables)) (count (prose-files)) (count (tables))) (:events p))
          "one :put per table, per document, and per notebook"))))

(deftest the-dry-plan-is-the-plan-that-runs
  ;; repair's property, and Task 4's whole method: the rehearsal is only worth
  ;; comparing against if the two plans are the same value.
  (let [dry  (corpus/import-corpus! (sub/fresh-store) {:dir dir})
        live (ran! (sub/fresh-store))]
    (is (= (:plan dry) (:plan live)))))

;; ---------------------------------------------------------------------------
;; one :tx per notebook
;; ---------------------------------------------------------------------------

(deftest one-tx-per-notebook
  (let [st (sub/fresh-store)
        r  (ran! st)
        log (vec (sub/history st))]
    (is (= (count (tables)) (count log))
        "the history grows by the number of notebooks — not by 1, and not by the number of objects")
    (is (= (count (tables)) (:committed r)))
    (is (every? #(= :tx (:op %)) log) "every commit is a transaction")
    (is (= (+ (count (tables)) (count (prose-files)) (count (tables)))
           (reduce + (map (comp count :events) log)))
        "and between them they carry one :put per object")
    (testing "a notebook is the last event of its own transaction, so no cell ever dangles"
      (doseq [ev log]
        (let [evs (:events ev)]
          (is (= :space (:kind (:value (last evs)))))
          (is (every? #(not= :space (:kind (:value %))) (butlast evs))))))))

(deftest what-a-full-run-creates
  (let [st (sub/fresh-store)]
    (ran! st)
    (let [objs (vals (sub/objects st))
          kinds (frequencies (map :kind objs))]
      (is (= (count (tables)) (:space kinds)))
      (is (= (count (tables)) (:table kinds)))
      (is (= (count (prose-files)) (:doc kinds)))
      (is (= (+ (* 2 (count (tables))) (count (prose-files))) (count objs))))
    (testing "every notebook cells its table first and then its documents"
      (doseq [[file nbk] (notebooks-by-file st)]
        (let [refs (mapv :ref (nb/cells-of nbk))
              kind #(:kind (sub/object st %))]
          (is (= :table (kind (first refs))) file)
          (is (every? #(= :doc (kind %)) (rest refs)) file))))))

;; ---------------------------------------------------------------------------
;; undo reverses the last notebook only
;; ---------------------------------------------------------------------------

(deftest undo-reverses-the-last-notebook-only
  (let [st   (sub/fresh-store)
        r    (ran! st)
        last-nb (last (:notebooks (:plan r)))
        others  (butlast (:notebooks (:plan r)))
        ids-of  (fn [n] (concat [(:id n) (get-in n [:table :id])] (map :id (:documents n))))]
    (sub/undo! st)
    (is (= (dec (count (tables))) (count (sub/history st))))
    (testing "the last notebook, its table and its documents are gone"
      (doseq [id (ids-of last-nb)]
        (is (nil? (sub/object st id)) id)))
    (testing "every earlier notebook is untouched"
      (doseq [n others, id (ids-of n)]
        (is (some? (sub/object st id)) id)))))

(deftest undone-then-re-run-restores-only-what-is-missing
  ;; the consequence of one :tx per notebook that makes it worth having: each is
  ;; independently reversible AND independently re-appliable.
  (let [st (sub/fresh-store)
        r1 (ran! st)]
    (sub/undo! st)
    (let [r2 (ran! st)]
      (is (= 1 (:commits (:plan r2))) "only the undone notebook is rebuilt")
      (is (= (dec (count (tables))) (count (:already (:plan r2)))))
      (is (= (count (tables)) (count (sub/history st))))
      (is (= (set (map :from (:notebooks (:plan r1))))
             (set (keys (notebooks-by-file st))))))))

;; ---------------------------------------------------------------------------
;; a second run is a no-op
;; ---------------------------------------------------------------------------

(deftest a-second-run-is-a-no-op
  (let [st (sub/fresh-store)]
    (ran! st)
    (let [before (sub/objects st)
          n      (count (sub/history st))
          r      (ran! st)]
      (is (:already-done? r))
      (is (zero? (:commits (:plan r))))
      (is (zero? (:events (:plan r))))
      (is (empty? (:notebooks (:plan r))))
      (is (= (count (tables)) (count (:already (:plan r)))))
      (is (= n (count (sub/history st))) "no event was appended")
      (is (= before (sub/objects st)) "and nothing in the store moved"))
    (testing "a dry run over an imported store says the same"
      (let [r (corpus/import-corpus! st {:dir dir})]
        (is (:ok? r))
        (is (:already-done? r))
        (is (zero? (:commits (:plan r))))))))

;; ---------------------------------------------------------------------------
;; the tables land with their coarse column intact, and mold as Task 1 measured
;; ---------------------------------------------------------------------------

(def ^:private cat-col
  "`loci.content`'s own predicate, not a re-derivation of it. Which column a
   table pivots on is the thing being asserted; a local copy of the rule would
   assert the copy."
  @#'loci.content/cat-col)

(deftest tables-mold-as-the-manifest-measured
  (let [st (sub/fresh-store)
        _  (ran! st)
        by-file (into {} (for [[f nbk] (notebooks-by-file st)]
                           [f (sub/object st (:ref (first (nb/cells-of nbk))))]))]
    (doseq [{:keys [file coarse-column coarse-values rows]} (tables)]
      (testing file
        (let [tbl  (by-file file)
              ;; the log holds a handle; the molds are asserted against the rows
              data (:value (tbl/hydrate lake/lake tbl))]
          (is (some? tbl) "the table reached the store")
          (is (tbl/handle? (:value tbl))
              "and it reached it as a handle — hydrate would pass rows through, so
               nothing else in this suite can tell whether the writer was converted")
          (is (= (:committed rows) (count data)) "with all its rows")

          (testing "the coarse column survived the round trip through the log"
            (let [c (sub/col-kw coarse-column)]
              (is (contains? (first data) c))
              (is (= (set coarse-values) (set (map #(get % c) data))))
              (is (= c (cat-col data))
                  "and it is still the column cat-col picks")))

          (testing "and it molds the way corpus/fetch/verify_molds.clj asserts"
            (is (= (get (:molds-as @manifest) file)
                   (mapv :id (mold/viewers-for data))))
            (is (some #{:table/pivot} (map :id (mold/viewers-for data)))
                "no Pivot means the coarse column is wrong")))))

    (testing "exactly one table is a time series, and it is the monthly one"
      (let [monthly (for [[f t] by-file
                          :when (some #{:table/line}
                                      (map :id (mold/viewers-for (:value (tbl/hydrate lake/lake t)))))]
                      f)]
        (is (= ["interest-rates-monthly.csv"] (vec monthly)))))))

;; ---------------------------------------------------------------------------
;; the documents land verbatim
;; ---------------------------------------------------------------------------

(deftest documents-are-the-files-verbatim
  (let [st (sub/fresh-store)]
    (ran! st)
    (let [docs (into {} (for [o (vals (sub/objects st)) :when (= :doc (:kind o))]
                          [(:title o) o]))]
      (is (= (count (prose-files)) (count docs)) "no two documents share a title")
      (doseq [{:keys [file title]} (prose-files)]
        (let [body (slurp (io/file dir (:dir (:prose @manifest)) file))]
          (is (= body (:value (docs title))) file)
          (is (str/starts-with? body (str "# " title))
              "the manifest's title is the file's own heading"))))))

;; ---------------------------------------------------------------------------
;; the intent names the source and the licence — where a reader sees it
;; ---------------------------------------------------------------------------

(defn- sources-of
  "The manifest entries behind one notebook: its table, then its documents."
  [st nbk]
  (let [file    (get-in nbk [:value :imported-from])
        by-t    (into {} (map (juxt :file identity)) (tables))
        by-p    (into {} (map (juxt :title identity)) (prose-files))
        cells   (nb/cells-of nbk)
        doc-ttl (keep #(let [o (sub/object st (:ref %))] (when (= :doc (:kind o)) (:title o))) cells)]
    (cons (by-t file) (map by-p doc-ttl))))

(deftest a-notebooks-intent-names-its-source-and-licence
  (let [st (sub/fresh-store)]
    (ran! st)
    (doseq [[file nbk] (notebooks-by-file st)]
      (testing file
        (let [intent (get-in nbk [:value :intent])]
          (is (seq intent))
          (doseq [entry (sources-of st nbk)]
            (is (some? entry) "every cell traces to a manifest entry")
            (let [pub (squash (get-in entry [:source :publisher]))
                  lic (get-in entry [:licence :id])
                  att (get-in entry [:licence :attribution])]
              (is (str/includes? intent pub) (str "publisher missing: " pub))
              (is (str/includes? intent lic) (str "licence missing: " lic))
              (when (and att (str/starts-with? lic "CC-BY"))
                (is (str/includes? intent (squash att))
                    (str "CC BY attribution missing from " file))))))))

    (testing "the CC BY citations really are in there"
      (let [intents (str/join "\n" (map #(get-in % [:value :intent]) (nb/notebooks st)))]
        (doseq [{:keys [licence]} (tables)
                :when (str/starts-with? (:id licence) "CC-BY")]
          (is (str/includes? intents (squash (:attribution licence)))))))))

;; ---------------------------------------------------------------------------
;; placement — every prose file lands with material it is actually about
;; ---------------------------------------------------------------------------

(deftest prose-lands-in-the-notebook-it-belongs-to
  (let [st (sub/fresh-store)]
    (ran! st)
    (let [by-t     (into {} (map (juxt :file identity)) (tables))
          by-p     (into {} (map (juxt :title identity)) (prose-files))
          ;; the one HARD link the manifest carries between a prose extract and
          ;; a table: the same dataset URL. Two of the fourteen have it, and they
          ;; are exactly the two the domain and entanglement checks below cannot
          ;; separate — both OWID, both :politics, both entangling `united
          ;; states` and `netherlands`. Without this, moving the turnout note
          ;; into the lead notebook breaks nothing.
          same-url (into {} (for [d (prose-files)
                                  t (tables)
                                  :when (= (get-in d [:source :url]) (get-in t [:source :url]))]
                              [(:file d) (:file t)]))]
      (is (= 2 (count same-url))
          "the URL check is doing something — if this drops to 0 it stopped constraining anything")
      (doseq [[file nbk] (notebooks-by-file st)]
        (let [tbl  (by-t file)
              docs (keep #(let [o (sub/object st (:ref %))]
                            (when (= :doc (:kind o)) (by-p (:title o))))
                         (nb/cells-of nbk))]
          (doseq [d docs]
            (testing (str (:file d) " in " file)
              (is (= (:domain tbl) (:domain d))
                  "a document sits under a table from its own domain")
              (is (seq (filter (set (:entangles tbl)) (:entangles d)))
                  "and shares at least one subject with it")
              (when-let [want (same-url (:file d))]
                (is (= want file)
                    "and an extract citing a table's own dataset sits with that table"))))))
      (testing "every URL-linked extract really was placed"
        (let [placed (into {} (for [{:keys [file prose]} corpus/hubs, p prose] [p file]))]
          (doseq [[p t] same-url]
            (is (= t (placed p)) p)))))))

;; ---------------------------------------------------------------------------
;; refusals — a mangled copy of corpus/, never corpus/ itself
;; ---------------------------------------------------------------------------

(defn- copy-corpus!
  "corpus/ duplicated into a temp directory, so a test can break a file without
   breaking the repository's."
  []
  (let [dst (.toFile (Files/createTempDirectory "loci-corpus" (make-array FileAttribute 0)))]
    (doseq [f (file-seq (io/file dir))
            :when (.isFile f)]
      (let [rel (subs (.getPath f) (inc (count dir)))
            out (io/file dst rel)]
        (io/make-parents out)
        (io/copy f out)))
    dst))

(defn- rm-r! [f]
  (doseq [c (reverse (file-seq (io/file f)))] (.delete c)))

(defmacro ^:private with-copy [[sym] & body]
  `(let [~sym (copy-corpus!)]
     (try ~@body (finally (rm-r! ~sym)))))

(deftest refuses-rather-than-throws
  (testing "no corpus directory at all"
    (let [st (sub/fresh-store)
          r  (corpus/import-corpus! st {:dir "corpus-that-is-not-there" :dry-run? false})]
      (is (false? (:ok? r)))
      (is (str/includes? (:error r) "corpus-that-is-not-there"))
      (is (zero? (count (sub/history st))))))

  (testing "a manifest that cannot be read"
    (with-copy [d]
      (spit (io/file d "MANIFEST.edn") "{:files [")
      (let [r (corpus/import-corpus! (sub/fresh-store) {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "MANIFEST.edn")))))

  (testing "a table file that is not there"
    (with-copy [d]
      (.delete (io/file d "art-objects.csv"))
      (let [r (corpus/import-corpus! (sub/fresh-store) {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "art-objects.csv")))))

  (testing "a prose file that is not there"
    (with-copy [d]
      (.delete (io/file d "prose/delft-nautilus-cup.md"))
      (let [r (corpus/import-corpus! (sub/fresh-store) {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "delft-nautilus-cup.md")))))

  (testing "a table whose coarse column has been flattened"
    ;; the failure Task 1 exists to prevent, staged: era collapsed to one value,
    ;; so cat-col finds nothing in 2-12 and Pivot disappears.
    (with-copy [d]
      (let [f (io/file d "art-objects.csv")]
        (spit f (str/replace (slurp f) #"(?m),(Before 500 CE|500-1399|1400-1599|1600-1699|1700-1799|1800-1899|1900-1945|After 1945),"
                             ",all,")))
      (let [st (sub/fresh-store)
            r  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "era"))
        (is (zero? (count (sub/history st))) "and nothing at all was written"))))

  (testing "a prose file whose heading no longer matches the manifest"
    (with-copy [d]
      (let [f (io/file d "prose/rare-earths-export-controls.md")]
        (spit f (str "# Something else entirely\n" (str/join "\n" (rest (str/split-lines (slurp f)))))))
      (let [r (corpus/import-corpus! (sub/fresh-store) {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "rare-earths-export-controls.md")))))

  (testing "a table whose row count no longer matches the manifest"
    (with-copy [d]
      (let [f (io/file d "lead-phaseout.csv")
            ls (str/split-lines (slurp f))]
        (spit f (str/join "\n" (take 40 ls))))
      (let [r (corpus/import-corpus! (sub/fresh-store) {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "lead-phaseout.csv")))))

  (testing "a notebook of the store's own already wearing a hub's title"
    (let [st    (sub/fresh-store)
          title (:title (first corpus/hubs))]
      (sub/commit! st {:op :put :id "space:new-1"
                       :value {:id "space:new-1" :kind :space :title title
                               :value {:intent "written by hand, months ago" :cells []}}})
      (let [r (corpus/import-corpus! st {:dir dir :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "space:new-1"))
        (is (= 1 (count (sub/history st)))))))

  (testing "a notebook already imported from this file under another title"
    (let [st (sub/fresh-store)]
      (sub/commit! st {:op :put :id "space:mine-1"
                       :value {:id "space:mine-1" :kind :space :title "My own art notebook"
                               :value {:intent "mine" :cells []
                                       :imported-from "art-objects.csv"}}})
      (let [r (corpus/import-corpus! st {:dir dir :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "My own art notebook"))
        (is (= 1 (count (sub/history st))) "nothing was written on top of it")))))

(deftest a-refusal-writes-nothing-even-half-way
  ;; the fourth table is broken, so the first three must not land either.
  (with-copy [d]
    (let [f (io/file d "elections-turnout.csv")]
      (spit f (str/join "\n" (take 20 (str/split-lines (slurp f))))))
    (let [st (sub/fresh-store)
          r  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})]
      (is (false? (:ok? r)))
      (is (zero? (count (sub/history st))))
      (is (empty? (sub/objects st))))))

;; ---------------------------------------------------------------------------
;; the declaration and the manifest agree
;; ---------------------------------------------------------------------------

(deftest every-file-in-the-manifest-is-placed-exactly-once
  (is (= (set (map :file (tables))) (set (map :file corpus/hubs)))
      "every table has a notebook, and no notebook names a table the manifest does not")
  (let [placed (mapcat :prose corpus/hubs)]
    (is (= (count placed) (count (set placed))) "no prose file is placed twice")
    (is (= (set (map :file (prose-files))) (set placed))
        "every prose file is placed, and nothing is placed that is not prose")))

;; ===========================================================================
;; the facts
;; ===========================================================================
;;
;; The memory these tests write to is a fresh `mem/file-memory` under
;; `java.io.tmpdir`, one per test. Never `@mem/memory`: that delay opens
;; `data/memory.edn`, the real one, and this half of the import has no undo —
;; a test that reached it would append seventy facts to somebody's work with
;; no way back but a backup.

(defn- fresh-memory []
  (mem/file-memory (str (System/getProperty "java.io.tmpdir")
                        "/loci-corpus-test-" (System/nanoTime) "/memory.edn")))

(defn- imported-store
  "A store with the substrate half already run — what `import-facts!` needs, and
   the thing it refuses without."
  []
  (let [st (sub/fresh-store)] (ran! st) st))

(defn- declared
  "How many facts the declarations promise: one per locator, one per query.
   Read off `loci.corpus`'s own vectors rather than written down as 70, because
   the number that matters is *all of them*, not a number that agrees with
   today's count."
  []
  (+ (count corpus/quoted) (count corpus/derived)))

(defn- facts! [st mm]
  (let [r (corpus/import-facts! st mm {:dir dir :dry-run? false})]
    (is (:ok? r) (str "facts refused: " (:error r)))
    r))

;; ---------------------------------------------------------------------------
;; a dry run remembers nothing
;; ---------------------------------------------------------------------------

(deftest a-dry-run-remembers-nothing
  (let [st (imported-store)
        mm (fresh-memory)
        r  (corpus/import-facts! st mm {:dir dir})]
    (is (:ok? r) (str "dry run refused: " (:error r)))
    (is (:dry-run? r))
    (is (empty? (mold/all-facts mm)) "not one fact was written")
    (is (nil? (:remembered r)) "and the report does not claim any were")
    (testing "the plan names every fact the declarations promise"
      (is (= (declared) (:count (:plan r))))
      (is (= (declared) (count (:facts (:plan r)))))
      (is (every? (comp seq :fact) (:facts (:plan r))))
      (is (every? (comp seq :entities) (:facts (:plan r)))))
    (testing "…spread over all five notebooks"
      (is (= (set (map :title corpus/hubs)) (set (keys (:by-notebook (:plan r))))))
      (is (= (declared) (reduce + (vals (:by-notebook (:plan r)))))))))

(deftest the-dry-fact-plan-is-the-plan-that-runs
  (let [st (imported-store)
        dry  (corpus/import-facts! st (fresh-memory) {:dir dir})
        live (facts! st (fresh-memory))]
    (is (= (:plan dry) (:plan live))
        "same value, so Task 4's rehearsal on a copy is worth comparing against")))

;; ---------------------------------------------------------------------------
;; each fact lands with its entities and a source that resolves
;; ---------------------------------------------------------------------------

(deftest every-fact-lands-with-its-entities-and-a-source-that-resolves
  (let [st (imported-store)
        mm (fresh-memory)
        r  (facts! st mm)
        fs (mold/all-facts mm)]
    (is (= (declared) (count fs))
        "every declared fact is its own record — none was folded into another")
    (is (= (declared) (:remembered r)))
    (is (empty? (:reinforced r))
        "and the importer agrees: nothing reinforced on the way in")

    (doseq [f fs]
      (testing (:fact f)
        (is (seq (:entities f)) "a fact with no entities reaches no entity page")
        (is (every? #(not (str/blank? %)) (:entities f)))
        (is (= (:entities f) (mapv ent/normalize (:entities f)))
            "names are already in the form ent/normalize would put them in")
        (let [{:keys [obj space]} (:source f)
              o (sub/object st obj)
              s (sub/object st space)]
          (is (some? o) (str "no object " obj " — the citation resolves to nothing"))
          (is (contains? #{:doc :table} (:kind o)) "a fact cites a document or a table")
          (is (= :space (:kind s)) "…inside a notebook")
          (is (contains? (set (map :ref (nb/cells-of s))) obj)
              "and the object it cites is a cell of the notebook it cites"))))))

(deftest no-imported-fact-carries-a-durability-judgement
  ;; Task 5's classifier owns `:durable?`, and `contains?` is the assertion
  ;; because `false` and absent are different states: `weight` reads the VALUE,
  ;; so a fact stamped `:durable? false` has been judged to decay, while a fact
  ;; without the key has not been judged at all. `remember` keys on `contains?`
  ;; too, so nothing but silence produces silence.
  (let [st (imported-store)
        mm (fresh-memory)]
    (facts! st mm)
    (doseq [f (mold/all-facts mm)]
      (is (not (contains? f :durable?)) (str "judged on arrival: " (:fact f)))
      (is (not (contains? f :durable-by)) (str "stamped with a judge: " (:fact f))))))

;; ---------------------------------------------------------------------------
;; nothing is invented
;; ---------------------------------------------------------------------------

(deftest every-quoted-fact-is-in-the-document-it-cites
  ;; the hardest rule in the spec, asserted against the two places the text has
  ;; to match: the file on disk and the object the store now holds.
  (let [st (imported-store)
        r  (corpus/import-facts! st (fresh-memory) {:dir dir})
        qs (filterv #(= :quoted (:via %)) (:facts (:plan r)))]
    (is (= (count corpus/quoted) (count qs)))
    (doseq [f qs]
      (testing (:from f)
        (is (str/includes? (squash (slurp (io/file dir "prose" (:from f)))) (:fact f))
            "the sentence is in the file, character for character")
        (is (str/includes? (squash (:value (sub/object st (get-in f [:source :obj])))) (:fact f))
            "…and in the document object the fact cites")
        ;; The substring check above is necessary and NOT sufficient, and that
        ;; was found by sabotage rather than by reading: a span that ignores its
        ;; closing anchor and runs to the end of the file is still a substring,
        ;; and passes it. The anchors are what say where the sentence stops, so
        ;; they are what has to be asserted.
        (is (str/starts-with? (:fact f) (:from (:args f)))
            "the fact begins at the anchor it is located by")
        (is (str/ends-with? (:fact f) (:to (:args f)))
            "…and ends at the one that closes it")
        (is (= (vec (:entangles (get (into {} (map (juxt :file identity)) (prose-files))
                                     (:from f))))
               (:entities f))
            "and its entities are the manifest's own record of what the extract touches")))))

(deftest a-tie-is-not-a-superlative
  ;; `commonest` and `only-one` are the only places this namespace states a
  ;; claim about uniqueness — "their commonest culture", "the earliest ban",
  ;; "more than any other year" — and a stable sort would answer every one of
  ;; them from a tie by handing back whichever row came first in the file. Both
  ;; answer nil instead, and every caller turns nil into a refusal.
  (let [commonest @#'corpus/commonest
        only-one  @#'corpus/only-one]
    (is (= ["a" 2] (vec (commonest :k [{:k "a"} {:k "a"} {:k "b"}])))
        "a clear winner is returned")
    (is (nil? (commonest :k [{:k "a"} {:k "b"}])) "a tie is not")
    (is (nil? (commonest :k [{:k ""} {:k nil}])) "and blanks are not values")
    (is (= 1 (:n (only-one < :n [{:n 2} {:n 1} {:n 3}]))))
    (is (nil? (only-one < :n [{:n 1} {:n 1} {:n 3}])) "a tied extreme is not an extreme")
    (is (nil? (only-one > :n [])))))

(deftest derived-facts-say-what-the-rows-say
  ;; every number below is computed HERE, off the CSV, and never asked of
  ;; loci.corpus — a check that re-derived through the code under test would
  ;; agree with it by construction.
  (let [st (imported-store)
        r  (corpus/import-facts! st (fresh-memory) {:dir dir})
        ;; selected by the declaration that produced it — `:via` plus `:args`
        ;; — because two `:leader` facts differ only in their arguments and a
        ;; test that took "the :leader fact" would silently assert about
        ;; whichever one came last.
        one    (fn [via args] (:fact (first (filter #(and (= via (:via %)) (= args (:args %)))
                                                    (:facts (:plan r))))))
        ;; `parse-csv` moved into `loci.corpus` when `read_csv` became the only
        ;; way a CSV becomes rows everywhere else; the corpus is its last caller.
        rows   #(#'corpus/parse-csv (slurp (io/file dir %)))]

    (testing "the cobalt leader is the largest 2025 producer in the file"
      (let [g (sort-by :production_tonnes >
                       (filter #(and (= "Cobalt" (:commodity %)) (= 2025 (:year %)))
                               (rows "mineral-production.csv")))
            f (one :leader {:commodity "Cobalt" :year 2025})]
        (is (str/includes? f (:country (first g))))
        (is (str/includes? f "230000") "the tonnage as the row carries it")
        (is (= 230000.0 (:production_tonnes (first g))) "…and that is what the row carries")
        (is (str/includes? f (:country (second g))) "with the runner-up named")))

    (testing "the absence really is an absence"
      (let [g (filter #(= "Gallium" (:commodity %)) (rows "mineral-production.csv"))]
        (is (not-any? #(= "United States" (:country %)) g)
            "the USGS extract lists no US gallium production…")
        (is (str/includes? (one :absent {:commodity "Gallium" :country "United States"})
                           "no United States gallium production")
            "…which is what the fact says")))

    (testing "every ban year is the year beside that country in the file"
      (let [by (into {} (map (juxt :entity identity)) (rows "lead-phaseout.csv"))
            f  (one :ban-years {:countries ["Japan" "United States" "Netherlands" "China"]})]
        (doseq [c ["Japan" "United States" "Netherlands" "China"]]
          (is (str/includes? f (str c " " (:ban_year (by c)))) c))))

    (testing "the MET count is the number of rows that match"
      (let [n (count (filter #(re-find #"(?i)\bcobalt\b" (str (:medium %) " " (:title %)))
                             (rows "art-objects.csv")))]
        (is (pos? n))
        (is (str/starts-with? (one :matching {:word "cobalt" :columns [:medium :title]
                                              :as "medium or title"})
                              (str n " MET objects")))))))

(deftest every-table-and-every-extract-is-read-by-at-least-one-fact
  ;; the quiet failure this catches: an extract fetched, licence-cleared,
  ;; committed and imported as a document that no fact ever quotes, so the
  ;; entity pages it was fetched for show nothing.
  (let [st (imported-store)
        r  (corpus/import-facts! st (fresh-memory) {:dir dir})
        by-file (frequencies (map :from (:facts (:plan r))))]
    (doseq [{:keys [file]} (tables)]
      (is (pos? (get by-file file 0)) (str "no fact reads " file)))
    (doseq [{:keys [file]} (prose-files)]
      (is (pos? (get by-file file 0)) (str "no fact quotes " file)))))

;; ---------------------------------------------------------------------------
;; reinforcement is reported, never silent
;; ---------------------------------------------------------------------------

(deftest a-fact-that-reinforces-an-existing-one-is-reported
  ;; `remember` folds two facts into one at jaccard ≥ 0.6 and returns :ok either
  ;; way, so an import that did not check would report seventy facts written and
  ;; have written sixty-nine. The fixture is a near-duplicate of one of them —
  ;; the same sentence with its last word gone, which shares every other token.
  (let [st     (imported-store)
        mm     (fresh-memory)
        plan   (:facts (:plan (corpus/import-facts! st mm {:dir dir})))
        victim (first (filter #(= :earliest-ban (:via %)) plan))
        near   (str/join " " (butlast (str/split (:fact victim) #"\s+")))]
    (mold/remember mm near {:entities ["seeded"] :source {:obj "doc:seed" :space "space:seed"}})
    (let [r  (facts! st mm)
          rf (:reinforced r)]
      (is (= 1 (count rf)) "one fact was absorbed, and the report says so")
      (is (= (:fact victim) (:fact (first rf))) "…naming which one")
      (is (= "mem-1" (:into (first rf))) "…and which record swallowed it")
      (is (= near (:absorbed (first rf)))
          "…and the wording that is now gone, which nothing else records")
      (is (= 2 (:strength (first rf))))
      (is (= (dec (count plan)) (:remembered r))
          "the count of facts written is one short, and honestly so")
      (is (= (count plan) (count (mold/all-facts mm)))
          "seeded fact + 69 written = 70 records: the absorbed one added nothing"))))

(deftest a-second-run-reinforces-rather-than-duplicating-and-says-so
  (let [st (imported-store)
        mm (fresh-memory)
        r1 (facts! st mm)
        n1 (count (mold/all-facts mm))
        r2 (facts! st mm)]
    (is (= n1 (count (mold/all-facts mm))) "not one new record")
    (is (zero? (:remembered r2)))
    (is (= (count (:facts (:plan r2))) (count (:reinforced r2)))
        "every fact is reported as reinforced, not silently re-remembered")
    (is (every? #(= (:fact %) (:absorbed %)) (:reinforced r2))
        "each absorbed its own earlier self, wording unchanged")
    (is (every? #(= 2 (:strength %)) (:reinforced r2)))
    (is (= (:remembered r1) n1))))

;; ---------------------------------------------------------------------------
;; the entanglement — what the facts exist for
;; ---------------------------------------------------------------------------

(deftest cobalt-and-netherlands-reach-more-than-one-notebook
  ;; the corpus's whole purpose, asserted at the level the entity page reads:
  ;; a name on a fact, and the notebook that fact cites.
  (let [st  (imported-store)
        mm  (fresh-memory)
        _   (facts! st mm)
        fs  (mold/all-facts mm)
        nbs (notebooks-by-file st)
        spaces (fn [nm] (set (keep (fn [f] (when (some #{nm} (:entities f))
                                             (get-in f [:source :space])))
                                   fs)))
        space-of #(:id (get nbs %))]
    (testing "cobalt is a pigment and a supply chain"
      (let [s (spaces "cobalt")]
        (is (contains? s (space-of "art-objects.csv")) "…in the art notebook")
        (is (contains? s (space-of "mineral-production.csv")) "…and in the minerals notebook")
        (is (<= 2 (count s)))))
    (testing "netherlands is Dutch painting and a petrol ban and an election"
      (let [s (spaces "netherlands")]
        (is (contains? s (space-of "art-objects.csv")))
        (is (contains? s (space-of "lead-phaseout.csv")))
        (is (contains? s (space-of "elections-turnout.csv")))))
    (testing "lead is a paint and a metal and a law"
      (let [s (spaces "lead")]
        (is (contains? s (space-of "art-objects.csv")))
        (is (contains? s (space-of "mineral-production.csv")))
        (is (contains? s (space-of "lead-phaseout.csv")))))))

;; ---------------------------------------------------------------------------
;; refusals — nothing is remembered when anything is wrong
;; ---------------------------------------------------------------------------

(deftest facts-refuse-rather-than-throw
  (testing "the notebooks are not there yet"
    (let [mm (fresh-memory)
          r  (corpus/import-facts! (sub/fresh-store) mm {:dir dir :dry-run? false})]
      (is (false? (:ok? r)))
      (is (str/includes? (str (:error r)) "import-corpus!"))
      (is (empty? (mold/all-facts mm)))))

  (testing "an anchor that no longer matches its extract"
    (with-copy [d]
      (let [f (io/file d "prose/cobalt-the-congo-export-ban.md")]
        (spit f (str/replace (slurp f) "In February, Congo" "In March, Congo")))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            _  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (str (:error r)) "cobalt-the-congo-export-ban.md"))
        (is (str/includes? (str (:error r)) "In February, Congo"))
        (is (empty? (mold/all-facts mm))
            "and the eighteen facts declared before it were not written either"))))

  (testing "a derivation whose rows are gone"
    ;; the row COUNT and the coarse column are untouched, so this gets past
    ;; material-complaint and is caught by the derivation itself.
    (with-copy [d]
      (let [f (io/file d "mineral-production.csv")]
        (spit f (str/replace (slurp f) "United States" "USA")))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            _  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (str (:error r)) "producer"))
        (is (str/includes? (str (:error r)) "mineral-production.csv"))
        (is (empty? (mold/all-facts mm))))))

  (testing "an extract whose manifest entry lost its entangled subjects"
    ;; a fact with no name is written, counted, reported as imported — and
    ;; reachable from no entity page, because `entity-payload` finds facts BY
    ;; name. Neither declaration can catch it: a quotation's names come out of
    ;; the manifest, so the plan is the first place the gap is visible.
    (with-copy [d]
      (let [f (io/file d "MANIFEST.edn")]
        (spit f (str/replace (slurp f) "\n    :entangles [\"cobalt\" \"cobalt blue\" \"pigment\"]}"
                             "}")))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            _  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (str (:error r)) "no entity name"))
        (is (str/includes? (str (:error r)) "cobalt-blue-the-pigment.md"))
        (is (empty? (mold/all-facts mm))))))

  (testing "two things wrong, and the first is what is reported"
    ;; also found by sabotage: with nothing carrying a refusal out through the
    ;; remaining notebooks, the reason reported is whichever failure came LAST,
    ;; and a reader fixing the corpus is sent to the wrong file.
    (with-copy [d]
      (doseq [[f from to] [["prose/cobalt-blue-the-pigment.md"
                            "Excluding smalt" "Excluding SMALT"]
                           ["prose/rare-earths-export-controls.md"
                            "In April 2025, China tightened" "In May 2025, China tightened"]]]
        (let [g (io/file d f)] (spit g (str/replace (slurp g) from to))))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            _  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (str (:error r)) "cobalt-blue-the-pigment.md")
            "the art notebook comes first, so its anchor is the one named")
        (is (not (str/includes? (str (:error r)) "rare-earths"))
            "…and the later failure does not overwrite it")
        (is (empty? (mold/all-facts mm))))))

  (testing "an absence the table no longer supports"
    ;; `:absent` is the one derivation whose claim is about rows that are NOT
    ;; there, so it is the only one a change in the data can turn into a lie
    ;; while every other check still passes: same row count, same coarse column,
    ;; same everything the manifest records. Sabotage found this — dropping the
    ;; guard broke no test at all, because on the committed corpus the sentence
    ;; comes out identical either way. Give USGS a United States gallium row and
    ;; the fact has to refuse rather than restate.
    (with-copy [d]
      (let [f (io/file d "mineral-production.csv")]
        (spit f (str/join "\n" (map #(if (str/starts-with? % "Gallium,Russia,")
                                       (str/replace % "Gallium,Russia," "Gallium,United States,")
                                       %)
                                    (str/split-lines (slurp f))))))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            _  (corpus/import-corpus! st {:dir (.getPath d) :dry-run? false})
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (str (:error r)) "absent"))
        (is (empty? (mold/all-facts mm))))))

  (testing "a table that is no longer the one the manifest describes"
    (with-copy [d]
      (let [f  (io/file d "lead-phaseout.csv")
            ls (str/split-lines (slurp f))]
        (spit f (str/join "\n" (take 40 ls))))
      (let [mm (fresh-memory)
            st (sub/fresh-store)
            r  (corpus/import-facts! st mm {:dir (.getPath d) :dry-run? false})]
        (is (false? (:ok? r)))
        (is (empty? (mold/all-facts mm))))))

  (testing "no corpus at all"
    (let [mm (fresh-memory)
          r  (corpus/import-facts! (sub/fresh-store) mm {:dir "corpus-that-is-not-there"
                                                         :dry-run? false})]
      (is (false? (:ok? r)))
      (is (empty? (mold/all-facts mm))))))

(deftest every-declaration-names-something-the-manifest-carries
  (let [tbl (set (map :file (tables)))
        prs (set (map :file (prose-files)))]
    (is (every? tbl (map :table corpus/derived))
        "every derived fact reads a table the manifest describes")
    (is (every? prs (map :doc corpus/quoted))
        "every quoted fact quotes an extract the manifest describes")
    (is (= tbl (set (map :table corpus/derived))) "and every table is read")
    (is (= prs (set (map :doc corpus/quoted))) "and every extract is quoted")))

(deftest a-dry-run-writes-nothing-to-the-lake-either
  ;; "Returns the plan it would have applied without applying any of it" has to
  ;; cover the lake as well as the log. Minting IS how the plan is computed, so a
  ;; dry run runs `mint-hub` in full — and once tables moved to DuckLake that
  ;; meant every preview left orphan Parquet behind for a plan nobody applied.
  ;; Measured before the fix: 5 tables and 6 snapshots per dry run.
  (let [count-of (fn [sql] (:n (first (lake/q @lake/lake sql))))
        tables   #(count-of "SELECT count(*) AS n FROM duckdb_tables()")
        snaps    #(count-of "SELECT count(*) AS n FROM ducklake_snapshots('lake')")
        [t0 s0]  [(tables) (snaps)]
        st       (sub/fresh-store)
        r        (corpus/import-corpus! st {:dir dir})]      ; DRY RUN
    (is (:ok? r))
    (is (= 5 (:commits (:plan r))) "it planned all five notebooks")
    (is (zero? (count (sub/history st))) "and committed none of them")
    (is (= t0 (tables)) "no table may be left in the lake")
    (is (= s0 (snaps)) "and no snapshot")
    ;; the same run live does write — otherwise the assertions above would pass
    ;; on a lake nothing can reach
    (ran! st)
    (is (= (+ t0 5) (tables)) "a live run lands one table per notebook")))
