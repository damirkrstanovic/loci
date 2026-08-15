(ns loci.factmigrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.factlog :as fl]
            [loci.factmigrate :as fm]))

(defn- tmpdir [] (str (System/getProperty "java.io.tmpdir") "/loci-fm-" (System/nanoTime)))

(defn- seeded [dir n]
  (let [path (str dir "/memory.edn")
        l    (fl/file-log path)]
    (dotimes [i n] (fl/append! l {:id (str "mem-" (inc i)) :fact (str "Fact " (inc i) ".")
                                  :entities ["x"] :ts (inc i) :strength 1}))
    path))

(deftest dry-run-writes-nothing
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))
        r  (fm/migrate-facts! st path)]
    (is (:ok? r))
    (is (:dry-run? r))
    (is (= 5 (get-in r [:plan :count])))
    (is (empty? (fl/load-all (dlv/fact-log st))) "a dry run must not write")))

(deftest live-run-moves-every-fact
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))
        r  (fm/migrate-facts! st path {:dry-run? false})]
    (is (:ok? r))
    (is (= 5 (:migrated r)))
    (is (= 5 (count (fl/load-all (dlv/fact-log st)))))
    (is (= "Fact 3." (:fact (get (fl/load-all (dlv/fact-log st)) "mem-3"))))))

(deftest second-run-is-a-no-op
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fm/migrate-facts! st path {:dry-run? false})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (:ok? r))
      (is (:already-done? r))
      (is (= 0 (:migrated r)))
      (is (= 5 (count (fl/load-all (dlv/fact-log st))))))))

(deftest refuses-a-fact-with-no-id
  (let [d (tmpdir), path (seeded d 2)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fl/append! (fl/file-log path) {:fact "no id here"})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (re-find #"no :id" (:error r)))
      (is (empty? (fl/load-all (dlv/fact-log st))) "a refusal writes nothing"))))

(deftest refuses-when-the-target-already-holds-different-facts
  (let [d (tmpdir), path (seeded d 2)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fl/append! (dlv/fact-log st) {:id "mem-1" :fact "something else entirely"})
    (let [r (fm/migrate-facts! st path {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (re-find #"mem-1" (:error r))))))

(deftest counter-is-set-past-the-migrated-facts
  (let [d (tmpdir), path (seeded d 5)
        st (dlv/datalevin-store (str d "/substrate"))]
    (fm/migrate-facts! st path {:dry-run? false})
    (is (= "mem-6" (fl/mint-id! (dlv/fact-log st)))
        "the next fact must not reissue mem-1 onto a migrated record")))
