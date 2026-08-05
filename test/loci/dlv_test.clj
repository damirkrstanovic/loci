(ns loci.dlv-test
  (:require [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

(defn- tmpdir []
  (str (System/getProperty "java.io.tmpdir") "/loci-dlv-" (System/nanoTime)))

(deftest datalevin-store-commits-and-replays
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
    (is (= "world" (:value (sub/object s "a"))))
    (is (= 2 (count (sub/history s))))
    (dlv/close! s)
    ;; a brand-new store over the same directory replays to identical state
    (let [s2 (dlv/datalevin-store dir)]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a"))))
      (dlv/close! s2))))

(deftest datalevin-undo-pops-and-persists
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (is (= 1 (sub/undo! s)))
    (is (nil? (sub/object s "b")))
    (dlv/close! s)
    (let [s2 (dlv/datalevin-store dir)]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b")))
      (dlv/close! s2))))

(deftest datalevin-as-of-and-frozen-at-work-unchanged
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "1'"})
    (is (= "1" (get-in (sub/as-of s 2) [:objects "a" :value])))
    (let [fz (sub/frozen-at s 2)]
      (is (= "1" (:value (sub/object fz "a"))))
      (is (= "B" (:title (sub/object fz "b"))))
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}}))))
    (is (= "1'" (:value (sub/object s "a"))))
    (dlv/close! s)))

(deftest datalevin-normalizes-unreadable-keys
  (let [dir (tmpdir)
        s   (dlv/datalevin-store dir)]
    (sub/commit! s {:op :put :id "t"
                    :value {:id "t" :kind :table
                            :value [{(keyword "Key Proponent(s)") "Canon" :year 2026}]}})
    (is (= [{:key_proponent_s "Canon" :year 2026}] (:value (sub/object s "t"))))
    (dlv/close! s)))
