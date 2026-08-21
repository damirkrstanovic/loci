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
