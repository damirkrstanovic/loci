(ns loci.factlog-test
  (:require [clojure.test :refer [deftest is]]
            [loci.factlog :as fl]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-fl-" (System/nanoTime) "/memory.edn"))

(deftest file-log-round-trips
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1" :fact "a" :ts 1})
    (fl/append! l {:id "mem-2" :fact "b" :ts 2})
    (is (= {"mem-1" {:id "mem-1" :fact "a" :ts 1}
            "mem-2" {:id "mem-2" :fact "b" :ts 2}}
           (fl/load-all l)))))

(deftest file-log-is-last-wins-by-id
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1" :fact "first"})
    (fl/append! l {:id "mem-1" :fact "second"})
    (is (= "second" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest file-log-mints-past-the-highest-id-ever-seen
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "mem-1"})
    (fl/append! l {:id "mem-7"})
    ;; not (inc (count …)) — mem-2 would collide with a live fact after a delete
    (is (= "mem-8" (fl/mint-id! l)))
    (is (= "mem-9" (fl/mint-id! l)))))

(deftest file-log-ignores-ids-of-another-shape
  (let [l (fl/file-log (tmpfile))]
    (fl/append! l {:id "hand-edited"})
    (is (= "mem-1" (fl/mint-id! l)))))
