(ns loci.notebook-test
  (:require [clojure.test :refer [deftest is]]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

(deftest cells-normalization
  ;; legacy :members upgrade to ref cells; :cells pass through
  (is (= [{:ref "a"} {:ref "b"}] (nb/cells-of {:value {:members ["a" "b"]}})))
  (is (= [{:text "hi"} {:ref "a" :view "table/bar"}]
         (nb/cells-of {:value {:cells [{:text "hi"} {:ref "a" :view "table/bar"}]}}))))

(deftest cell-ops
  (let [cells [{:text "a"} {:ref "t1"}]]
    (is (= [{:text "a"} {:ref "t1"} {:text "new"}]
           (nb/cell-op cells {:op "add-text" :text "new"})))
    (is (= [{:text "a"} {:ref "t1"} {:ref "t2" :view "table/bar"}]
           (nb/cell-op cells {:op "add-ref" :ref "t2" :view "table/bar"})))
    (is (= [{:text "edited"} {:ref "t1"}]
           (nb/cell-op cells {:op "edit-text" :idx 0 :text "edited"})))
    (is (= [{:text "a"} {:ref "t1" :view "table/pivot"}]
           (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})))
    (is (= [{:text "a"} {:ref "t1"}]
           (nb/cell-op (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})
                       {:op "set-view" :idx 1 :view ""})))   ; empty view clears back to default
    (is (= [{:ref "t1"}] (nb/cell-op cells {:op "remove" :idx 0})))
    (is (= [{:ref "t1"} {:text "a"}] (nb/cell-op cells {:op "move" :idx 0 :to 1})))))

(deftest cell-op-is-total-on-bad-input
  (let [cells [{:text "a"} {:ref "t1"}]]
    (is (= cells (nb/cell-op cells {:op "edit-text" :idx 1 :text "clobber"})))  ; ref cell: no type-confusion
    (is (= cells (nb/cell-op cells {:op "set-view" :idx 0 :view "v"})))          ; text cell: no malformed view
    (is (= cells (nb/cell-op cells {:op "set-view" :idx 2 :view "v"})))          ; idx == count
    (is (= cells (nb/cell-op cells {:op "remove" :idx 9})))
    (is (= cells (nb/cell-op cells {:op "edit-text" :idx nil :text "x"})))
    (is (= cells (nb/cell-op cells {:op "move" :idx 0 :to nil})))))

;; ---------------------------------------------------------------------------
;; appending a cell — the race, and the legacy trap the fix has to survive
;;
;; `append-cell-event` used to read the whole cell vector and emit a whole new
;; one, so two concurrent appends each wrote their own idea of the whole and one
;; was lost: measured, 24 concurrent appends left 6 cells. Reachable today from
;; a research job and a flow step landing cells in the same notebook.
;; ---------------------------------------------------------------------------

(defn- appended-refs
  "Fire `n` appends at one notebook from `n` threads released together, then
   return the notebook's refs. The latch is what makes them collide: started
   threads alone would mostly run one after another."
  [st space-id n prefix]
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        ths   (mapv (fn [i]
                      (Thread. (fn []
                                 (.await latch)
                                 (sub/commit! st (nb/append-cell-event
                                                  st space-id {:ref (str prefix i)})))))
                    (range n))]
    (doseq [t ths] (.start t))
    (.countDown latch)
    (doseq [t ths] (.join t))
    (mapv :ref (nb/cells-of (sub/object st space-id)))))

(deftest concurrent-appends-do-not-lose-cells
  (let [st (sub/fresh-store)
        n  24]
    (sub/commit! st {:op :put :id "space:t"
                     :value {:id "space:t" :kind :space :title "T"
                             :value {:intent "i" :cells []}}})
    (let [refs (appended-refs st "space:t" n "obj-")]
      (is (= n (count refs)) "every concurrently appended cell survives")
      (is (= n (count (set refs))) "and none is duplicated"))))

(deftest appending-to-a-legacy-notebook-keeps-its-members
  ;; `cells-of` falls back to :members when a space has no :cells, so appending
  ;; straight to [:value :cells] would create a one-element vector and orphan
  ;; every member. The browser harness carries a comment about being bitten by
  ;; exactly this.
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:old"
                     :value {:id "space:old" :kind :space :title "Old"
                             :value {:intent "i" :members ["a" "b" "c"]}}})   ; no :cells
    (sub/commit! st (nb/append-cell-event st "space:old" {:ref "d"}))
    (is (= ["a" "b" "c" "d"] (mapv :ref (nb/cells-of (sub/object st "space:old")))))))

(deftest concurrent-appends-to-a-legacy-notebook-keep-everything
  ;; the nastiest case: every writer sees :cells absent, so every one carries a
  ;; seed. Whichever lands first seeds; the rest must simply append.
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:old"
                     :value {:id "space:old" :kind :space :title "Old"
                             :value {:intent "i" :members ["a" "b"]}}})
    (let [refs (appended-refs st "space:old" 8 "n")]
      (is (= ["a" "b"] (take 2 refs)) "the members are still there, still first")
      (is (= 10 (count refs)) "and all eight new cells landed")
      (is (= 10 (count (set refs))) "with the seed applied once, not once per writer"))))

(deftest undoing-an-append-removes-exactly-that-cell
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:t"
                     :value {:id "space:t" :kind :space :title "T"
                             :value {:intent "i" :cells [{:text "kept"}]}}})
    (sub/commit! st (nb/append-cell-event st "space:t" {:ref "a"}))
    (sub/commit! st (nb/append-cell-event st "space:t" {:ref "b"}))
    (is (= [{:text "kept"} {:ref "a"} {:ref "b"}] (nb/cells-of (sub/object st "space:t"))))
    (sub/undo! st)
    (is (= [{:text "kept"} {:ref "a"}] (nb/cells-of (sub/object st "space:t"))))
    (sub/undo! st)
    (is (= [{:text "kept"}] (nb/cells-of (sub/object st "space:t"))))))

(deftest undoing-the-seeding-append-leaves-the-legacy-members
  ;; the append that normalizes :members into :cells must undo back to a
  ;; notebook that still reads as its three members, not an empty one
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:old"
                     :value {:id "space:old" :kind :space :title "Old"
                             :value {:intent "i" :members ["a" "b" "c"]}}})
    (sub/commit! st (nb/append-cell-event st "space:old" {:ref "d"}))
    (sub/undo! st)
    (is (= ["a" "b" "c"] (mapv :ref (nb/cells-of (sub/object st "space:old")))))))

(deftest links-reasons
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "tbl:d" :value {:id "tbl:d" :kind :table :title "D" :value [{:a 2}]
                                                  :from "tbl:x" :via "fn:x-1"}})
    (sub/commit! st {:op :put :id "space:hub" :value {:id "space:hub" :kind :space :title "Hub"
                                                      :value {:cells [{:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:sub" :value {:id "space:sub" :kind :space :title "Sub"
                                                      :value {:cells [{:ref "tbl:x"}]
                                                              :spawned-by {:space "space:hub" :prompt "p"}}}})
    (sub/commit! st {:op :put :id "space:der" :value {:id "space:der" :kind :space :title "Der"
                                                      :value {:cells [{:ref "tbl:d"}]}}})
    (let [{:keys [connected also-in]} (nb/links st "space:hub")
          by-id (into {} (map (juxt :id identity) connected))]
      (is (= #{"space:sub" "space:der"} (set (keys by-id))))
      (is (= #{{:type "spawned"} {:type "shares" :obj "tbl:x"}}
             (set (:reasons (by-id "space:sub")))))
      (is (= [{:type "derived" :obj "tbl:x"}] (:reasons (by-id "space:der"))))
      (is (= [{:id "space:sub" :title "Sub"}] (get also-in "tbl:x"))))))

(deftest links-surface-merged-both-directions
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:a" :value {:id "space:a" :kind :space :title "A" :value {:cells []}}})
    (sub/commit! st {:op :put :id "space:b" :value {:id "space:b" :kind :space :title "B" :value {:cells []}}})
    (sub/commit! st {:op :put :id "space:mix-1"
                     :value {:id "space:mix-1" :kind :space :title "A × B"
                             :value {:cells [] :merged-from ["space:a" "space:b"]}}})
    (let [reasons (fn [from to] (->> (:connected (nb/links st from))
                                     (filter #(= to (:id %))) first :reasons (map :type) set))]
      (is (contains? (reasons "space:mix-1" "space:a") "merged-from"))   ; child → parent
      (is (contains? (reasons "space:a" "space:mix-1") "merged")))))     ; parent → child
