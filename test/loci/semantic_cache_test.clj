(ns loci.semantic-cache-test
  (:require [clojure.test :refer [deftest is]]
            [loci.embed :as embed]
            [loci.semantic-cache :as cache]))

(deftest exact-cosine-and-cache-invalidation
  (let [api cache/prepared]
    (is api "semantic cache must exist")
    (when api
      (let [cache (atom nil) cs {:!chunks (atom {"a" {:id "a" :model "m" :vec [1.0 2.0]}
                                                 "b" {:id "b" :model "m" :vec [2.0 1.0]}})}
            prepare #(api cache cs %)
            a (prepare "m") old (get a "a")]
        (is (= (embed/cosine [0.5 1.0] [1.0 2.0])
               (cache/cosine (double-array [0.5 1.0]) (:array old))))
        (swap! (:!chunks cs) assoc "b" {:id "b" :model "m" :vec [3.0 1.0]})
        (let [b (prepare "m")]
          (is (identical? (:array old) (:array (get b "a"))))
          (is (not (identical? (:array (get a "b")) (:array (get b "b"))))))
        (swap! (:!chunks cs) assoc "a" {:id "a" :retired true})
        (is (nil? (get (prepare "m") "a")))
        (is (empty? (prepare "other")))
        (let [other {:!chunks (atom {"a" {:id "a" :model "m" :vec [9.0 0.0]}})}]
          (is (= 9.0 (aget ^doubles (:array (get (api cache other "m") "a")) 0))))))))

(deftest pending-count-refreshes-when-inputs-change
  (let [api cache/pending-count]
    (is api "pending cache must exist")
    (when api
      (let [cache (atom nil) objects (atom {:a 1}) chunks (atom {:a 1}) calls (atom 0)
            compute #(do (swap! calls inc) (+ (count @objects) (count @chunks)))
            run (fn [] (api cache objects chunks "m" (fn [] @objects) compute))]
        (is (= 2 (run))) (is (= 2 (run))) (is (= 1 @calls))
        (swap! objects assoc :b 2) (is (= 3 (run)))
        (swap! chunks assoc :b 2) (is (= 4 (run)))
        (is (= 4 (api cache objects chunks "other" #(deref objects) compute)))
        (is (= 4 (api cache (atom @objects) chunks "other" #(deref objects) compute)))
        (is (= 5 @calls))))))

(deftest pending-count-retries-if-a-write-arrives-during-computation
  (let [api cache/pending-count
        cache (atom nil) objects (atom {:a 1}) chunks (atom {}) calls (atom 0)
        started (promise) release (promise)
        compute (fn [] (let [n (count @objects)]
                         (when (= 1 (swap! calls inc)) (deliver started true) @release)
                         n))
        result (future (api cache objects chunks "m" (fn [] @objects) compute))]
    @started
    (swap! objects assoc :b 2)
    (deliver release true)
    (is (= 2 @result))
    (is (= 2 @calls))
    (is (= 2 (api cache objects chunks "m" (fn [] @objects) compute)))
    (is (= 2 @calls))))
