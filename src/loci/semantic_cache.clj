(ns loci.semantic-cache)

(defn cosine [^doubles a ^doubles b]
  (let [n (alength a)]
    (if (or (zero? n) (not= n (alength b))) 0.0
      (loop [i 0 dot 0.0 na 0.0 nb 0.0]
        (if (= i n)
          (let [d (* (Math/sqrt na) (Math/sqrt nb))]
            (if (zero? d) 0.0 (/ dot d)))
          (let [x (aget a i) y (aget b i)]
            (recur (inc i) (+ dot (* x y)) (+ na (* x x)) (+ nb (* y y)))))))))

(defn prepared [cache cs model]
  (locking cache
    (loop []
      (let [snapshot @(:!chunks cs) old @cache]
        (if (and (identical? cs (:store old)) (identical? snapshot (:snapshot old)) (= model (:model old)))
          (:entries old)
          (let [previous (when (and (identical? cs (:store old)) (= model (:model old))) (:entries old))
                entries (into {} (keep (fn [[id rec]]
                                        (when (and (not (true? (:retired rec))) (= model (:model rec)) (seq (:vec rec)))
                                          [id (let [prior (get previous id)]
                                                (if (identical? rec (:record prior)) prior
                                                    {:record rec :array (double-array (:vec rec))}))]))) snapshot)]
            (if (identical? snapshot @(:!chunks cs))
              (do (reset! cache {:store cs :snapshot snapshot :model model :entries entries}) entries)
              (recur))))))))

(defn pending-count [cache store chunks model objects compute]
  (locking cache
    (loop []
      (let [obj (objects) snapshot @chunks old @cache]
        (if (and (identical? store (:store old)) (identical? chunks (:chunks old))
                 (identical? obj (:objects old)) (identical? snapshot (:snapshot old)) (= model (:model old)))
          (:count old)
          (let [n (compute)]
            (if (and (identical? obj (objects)) (identical? snapshot @chunks))
              (do (reset! cache {:store store :chunks chunks :objects obj :snapshot snapshot :model model :count n}) n)
              (recur))))))))
