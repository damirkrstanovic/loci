(ns loci.factlog-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.factlog :as fl]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-fl-" (System/nanoTime) "/memory.edn"))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-dbi-" (System/nanoTime) "/substrate"))

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

(deftest dbi-log-round-trips
  (let [st (dlv/datalevin-store (tmpdir))
        l  (dlv/fact-log st)]
    (fl/append! l {:id "mem-1" :fact "a" :ts 1})
    (fl/append! l {:id "mem-2" :fact "b" :ts 2})
    (is (= #{"mem-1" "mem-2"} (set (keys (fl/load-all l)))))
    (is (= "a" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest dbi-log-is-last-wins-by-id
  (let [l (dlv/fact-log (dlv/datalevin-store (tmpdir)))]
    (fl/append! l {:id "mem-1" :fact "first"})
    (fl/append! l {:id "mem-1" :fact "second"})
    (is (= 1 (count (fl/load-all l))))
    (is (= "second" (:fact (get (fl/load-all l) "mem-1"))))))

(deftest dbi-log-counter-survives-a-reopen
  (let [dir (tmpdir)
        st1 (dlv/datalevin-store dir)]
    (let [l (dlv/fact-log st1)]
      (is (= "mem-1" (fl/mint-id! l)))
      (is (= "mem-2" (fl/mint-id! l))))
    ;; datalevin refuses a second open of the same dir in one process, so the
    ;; first env must be closed before "restarting" against the same dir
    (dlv/close! st1)
    ;; a counter that lived only in RAM would restart at mem-1 and destroy mem-2
    (let [st2 (dlv/datalevin-store dir)
          l   (dlv/fact-log st2)]
      (is (= "mem-3" (fl/mint-id! l)))
      (dlv/close! st2))))

(deftest dbi-log-counter-follows-a-bulk-append
  (let [l (dlv/fact-log (dlv/datalevin-store (tmpdir)))]
    ;; the migration appends without minting; the counter must still advance
    (fl/append! l {:id "mem-40" :fact "migrated"})
    (is (= "mem-41" (fl/mint-id! l)))))

(deftest dbi-log-seeds-a-missing-counter-from-the-facts-present
  (let [dir (tmpdir)
        st  (dlv/datalevin-store dir)]
    ;; facts written by something that bypassed append! — no counter exists
    (dlv/put-fact-raw-for-test! st {:id "mem-9" :fact "hand loaded"})
    (is (= "mem-10" (fl/mint-id! (dlv/fact-log st)))
        "minting from zero would reissue mem-1 onto a live fact")))
