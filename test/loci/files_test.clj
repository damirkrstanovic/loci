(ns loci.files-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loci.files :as files]
            [loci.lake-fixture :as lf]
            [loci.substrate :as sub]))

(defn- with-dir
  "Run `f` with `sub/data-dir` answering a fresh temp directory, removed after.

   The shape `loci.server-test/with-temp-data-dir` already uses, and for the
   same reason: `loci.files` resolves both the manifest and the `.partial`
   directory through that ONE var, so redefining it is what keeps a write and
   the read that follows it in the same place."
  [f]
  (let [d (str (System/getProperty "java.io.tmpdir") "/loci-files-" (System/nanoTime))]
    (try (with-redefs [sub/data-dir (constantly d)] (f d))
         (finally (lf/rm-rf d)))))

(deftest a-name-round-trips-and-last-write-wins
  (with-dir
    (fn [_]
      (is (= {} (files/names)) "no file yet is not an error")
      (files/remember! "abc123.csv" "sales-2026.csv")
      (is (= {"abc123.csv" "sales-2026.csv"} (files/names)))
      (files/remember! "abc123.csv" "sales-final.csv")
      (is (= {"abc123.csv" "sales-final.csv"} (files/names))
          "append-only, last-wins by id — the same rule chunks and memory keep"))))

(deftest a-corrupt-line-is-skipped-rather-than-fatal
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "one.csv")
      (spit (io/file (sub/data-dir) "files.edn") "{:id \"b.csv\" :name  \n" :append true)
      (files/remember! "c.csv" "three.csv")
      (is (= #{"a.csv" "c.csv"} (set (keys (files/names))))
          "a half-written line loses one name, never the file")
      (is (= "three.csv" (get (files/names) "c.csv"))
          "and the record AFTER the torn one is read whole, not eaten as its value"))))

(deftest sweeping-partials-removes-the-abandoned-and-keeps-the-fresh
  (with-dir
    (fn [_]
      (let [p (doto (io/file (sub/data-dir) "files" ".partial") .mkdirs)
            old (io/file p "old") new (io/file p "new")]
        (spit old "x") (spit new "y")
        (.setLastModified old (- (System/currentTimeMillis) (* 25 60 60 1000)))
        (is (= ["old"] (files/sweep-partials!)) "returns what it removed")
        (is (not (.exists old)))
        (is (.exists new) "an upload in flight is not garbage")))))

(deftest sweeping-with-no-partial-directory-at-all-is-not-an-error
  ;; The test above makes `.partial` itself, so it cannot see the case boot
  ;; hits on every loci that has never taken an upload: no directory, and
  ;; `.listFiles` answering nil rather than an empty array.
  (with-dir
    (fn [_]
      (is (= [] (files/sweep-partials!))))))

;; ---------------------------------------------------------------------------
;; the listing the picker draws. These five moved here from `loci.server-test`
;; with `loci.files/listing`: they are assertions about what the DIRECTORY
;; contains and how it is ordered, and leaving them beside the route while the
;; rules live here is the same split that let `.partial` be described in one
;; namespace and created in another.
;; ---------------------------------------------------------------------------

(deftest listing-is-what-is-in-the-directory
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        ;; Reverse lexical on purpose — see the ordering test below.
        (spit (io/file dir "hand-placed.csv") "b\n2\n")
        (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
        (.mkdirs (io/file dir ".partial"))
        (spit (io/file dir ".partial" "half") "not done")
        (let [r (files/listing)]
          (is (= #{"72b441a9c3e05f18.csv" "hand-placed.csv"} (set (map :file r)))
              "both files, and never the .partial working directory")
          (is (= 4 (:bytes (first (filter #(= "72b441a9c3e05f18.csv" (:file %)) r))))
              "the real length of \"a\\n1\\n\", not merely some number — a hashCode would be one of those")
          (is (= "loci://files/hand-placed.csv"
                 (:uri (first (filter #(= "hand-placed.csv" (:file %)) r))))
              "and the loci:// spelling sourcespec/locate resolves"))))))

(deftest listing-is-in-name-order-and-not-in-disk-order
  ;; `File.listFiles` promises no order at all; measured 2026-08-21 it answers
  ;; CREATION order here, and the docs allow hash order elsewhere. So a fixture
  ;; written in sorted order comes back sorted whether or not anything sorted
  ;; it — which is exactly how the `sort-by` in the code this moved from went
  ;; five tests without a single assertion that could see it. Writing in
  ;; reverse is what makes the sort the only thing that can produce this vector.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (doseq [n ["zulu.csv" "mike.csv" "kilo.csv" "alpha.csv"]]
          (spit (io/file dir n) "x"))
        (is (= ["alpha.csv" "kilo.csv" "mike.csv" "zulu.csv"] (mapv :file (files/listing)))
            "a picker that reshuffles between two opens reads as something having happened")))))

(deftest listing-is-empty-and-not-an-error-before-anything-is-imported
  (with-dir
    (fn [_] (is (= [] (files/listing))))))

(deftest listing-skips-directories-that-are-not-dotfiles
  ;; The tests above only ever put a DIRECTORY under a dot-prefixed name
  ;; (`.partial`), so they cannot tell the dotfile filter from the directory
  ;; filter — either alone would pass them. A subdirectory with an ordinary
  ;; name is what makes the `.isDirectory` half of `content?` load-bearing.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "real.csv") "a\n1\n")
        (.mkdirs (io/file dir "subdir"))
        (spit (io/file dir "subdir" "nested.csv") "a\n1\n")
        (is (= ["real.csv"] (mapv :file (files/listing)))
            "a subdirectory is not content, dotfile-named or not")))))

(deftest listing-skips-dotfiles-that-are-not-directories
  ;; The other fixtures only ever dot-prefix a DIRECTORY (`.partial`), so the
  ;; `isDirectory` half already protects them — dropping the dotfile half by
  ;; itself changes nothing they can see. A dot-prefixed ORDINARY FILE is what
  ;; makes it load-bearing.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "real.csv") "a\n1\n")
        (spit (io/file dir ".DS_Store") "junk")
        (is (= ["real.csv"] (mapv :file (files/listing)))
            "a dotfile is not content, directory or not")))))

(deftest listing-reports-the-manifest-name-for-a-file-loci-named
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
        (spit (io/file dir "hand-placed.csv") "b\n2\n")
        (files/remember! "72b441a9c3e05f18.csv" "sales-2026.csv")
        (let [by-file (into {} (map (juxt :file identity)) (files/listing))]
          (is (= "sales-2026.csv" (:name (by-file "72b441a9c3e05f18.csv")))
              "the listing reads the real manifest")
          (is (nil? (:name (by-file "hand-placed.csv")))
              "and a file loci never named has no name, rather than borrowing one"))))))
