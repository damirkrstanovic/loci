(ns loci.lake-fixture
  "A temp lake for any suite whose code path reaches `@lake/lake`.

   Without it, `mold-payload` and `save-table!` open <data-dir>/lake during the
   test run — the same way forcing `mem/memory` in a test with nothing to say
   about memory opens the real memory. `data/` is test data and writing to it
   would not be an incident, but two suites sharing one lake interfere with
   each other, which is reason enough."
  (:require [clojure.java.io :as io]
            [loci.lake :as lake]))

(defn rm-rf [x]
  (let [x (io/file x)]
    (when (.isDirectory x) (run! rm-rf (.listFiles x)))
    (.delete x)))

(defn with-temp-lake
  "A clojure.test fixture. Use with `use-fixtures :once`."
  [f]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/loci-lake-test-" (System/nanoTime))
        lk  (lake/open-lake! dir)]
    (try
      (with-redefs [lake/lake (delay lk)] (f))
      (finally
        (lake/close! lk)
        (rm-rf dir)))))

(defmacro with-lake
  "A fresh DuckLake in a temp directory, always removed.

   Here rather than in `loci.lake-test`, where it started, because
   `loci.tablemigrate-test` needs it too and a macro cannot be borrowed from a
   namespace whose helpers are private — the expansion names `rm-rf` at the
   call site."
  [[sym] & body]
  `(let [dir# (str (System/getProperty "java.io.tmpdir") "/loci-lake-" (System/nanoTime))
         ~sym (lake/open-lake! dir#)]
     (try ~@body (finally (lake/close! ~sym) (rm-rf dir#)))))
