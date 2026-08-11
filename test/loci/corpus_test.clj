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
            [clojure.test :refer [deftest is testing]]
            [loci.content]
            [loci.corpus :as corpus]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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
              data (:value tbl)]
          (is (some? tbl) "the table reached the store")
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
                          :when (some #{:table/line} (map :id (mold/viewers-for (:value t))))]
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
