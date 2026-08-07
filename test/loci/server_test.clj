(ns loci.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.agent :as agent]
            [loci.embed :as embed]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.server :as srv]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

(deftest leap-searches-everything
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "doc:x" :value {:id "doc:x" :kind :doc :title "Pricing notes"
                                                  :value "The plan rose 18% in March."}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "Notebook N"
                                                    :value {:intent "i"
                                                            :cells [{:text "churn concentrated in young accounts"}]}}})
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (let [groups (fn [q] (set (map :group (srv/leap-payload st m q))))]
      (is (contains? (groups "churn") "prose"))        ; cell prose
      (is (contains? (groups "march") "in text"))      ; doc body, title doesn't match
      (is (contains? (groups "downgrades") "memory"))  ; memory fact
      (is (contains? (groups "notebook") "space")))))  ; notebook by title

(deftest leap-finds-views-and-functions-with-target
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "tbl:p" :value {:id "tbl:p" :kind :table :title "Planets" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "app:p-1"
                     :value {:id "app:p-1" :kind :applet :title "app: orbits"
                             :value {:target "tbl:p" :code ";" :label "▶ orbit animation"}}})
    (sub/commit! st {:op :put :id "fn:p-1"
                     :value {:id "fn:p-1" :kind :fn :title "fn: densities"
                             :value {:source "tbl:p" :lang "clojure" :code "rows"}}})
    (let [hits  (srv/leap-payload st m "orbit")
          orbit (first (filter #(= "app:p-1" (:id %)) hits))]
      (is (some? orbit))
      (is (= "views & functions" (:group orbit)))
      (is (= "tbl:p" (:target orbit))))
    (is (some #(= "fn:p-1" (:id %)) (srv/leap-payload st m "densities")))
    ;; query-only, like prose: the empty listing stays uncluttered
    (is (not-any? #(= "app:p-1" (:id %)) (srv/leap-payload st m "")))))

(deftest notebook-op-rejects-non-notebooks
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    ;; non-space target: rejected, nothing committed, state still materializes
    (is (:error (srv/notebook-op! st {:space "tbl:t" :op "add-text" :text "x"})))
    (is (:error (srv/notebook-op! st {:space "nope" :op "add-text" :text "x"})))
    (is (= 2 (count (sub/history st))))
    ;; unknown op: no phantom event committed
    (srv/notebook-op! st {:space "space:n" :op "bogus"})
    (is (= 2 (count (sub/history st))))
    ;; a real op still works
    (srv/notebook-op! st {:space "space:n" :op "add-text" :text "hi"})
    (is (= 3 (count (sub/history st))))
    (is (= [{:text "hi"}] (get-in (sub/object st "space:n") [:value :cells])))))

;; ---------------------------------------------------------------------------
;; concurrent cell operations
;;
;; `notebook-op!` reads a notebook's whole cell vector, computes the new one and
;; writes the whole thing back — the read-modify-write `append-cell-event`
;; stopped doing after 24 concurrent appends left 6 cells. Every event lands;
;; what is lost is content, because each writer computed its vector from a read
;; taken before the others committed.
;; ---------------------------------------------------------------------------

(def ^:private cell-seeds
  (mapv (fn [i] {:text (str "seed-" i)}) (range 24)))

(def ^:private cell-op-mix
  "The op mix, chosen so that the cell COUNT and the fate of every appended cell
   follow from the ops alone, not from the order they happen to serialise in:

     add-text   +1, carrying a text nothing else writes
     move       a permutation inside [0,4) — count-neutral
     edit-text  idx in [4,8) — count-neutral, and every cell here is a text cell
                so `cell-op`'s type guard never turns it into a no-op
     remove     idx 0 — exactly -1

   24 seeds and 4 removes means the vector never falls below 20, so no index
   used here is ever out of range: every op really applies rather than falling
   through `cell-op`'s totality. And an appended cell lands at index >= 19 and
   drops at most 4 as removes shift it down, while moves reach only [0,4) — so
   nothing that was added can be moved, edited or removed away by this mix."
  (vec (concat (for [i (range 12)] {:op "add-text" :text (str "add-" i)})
               (for [i (range 4)]  {:op "move" :idx i :to (- 3 i)})
               (for [i (range 4)]  {:op "edit-text" :idx (+ 4 i) :text (str "edit-" i)})
               (repeat 4 {:op "remove" :idx 0}))))

(defn- cell-ops-together!
  "Fire every op at one notebook from its own thread, released together. The
   latch is what makes them collide: started threads alone would mostly run one
   after another."
  [st space ops]
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        ths   (mapv (fn [op] (Thread. (fn []
                                        (.await latch)
                                        (srv/notebook-op! st (assoc op :space space)))))
                    ops)]
    (doseq [t ths] (.start t))
    (.countDown latch)
    (doseq [t ths] (.join t))
    (nb/cells-of (sub/object st space))))

(deftest concurrent-cell-ops-lose-no-cell
  (let [st       (sub/fresh-store)
        ops      cell-op-mix
        adds     (set (keep :text (filter #(= "add-text" (:op %)) ops)))
        edits    (set (keep :text (filter #(= "edit-text" (:op %)) ops)))
        universe (into (set (map :text cell-seeds)) (concat adds edits))
        expected (- (+ (count cell-seeds) (count adds)) 4)]   ; seeds + adds - removes
    (sub/commit! st {:op :put :id "space:n"
                     :value {:id "space:n" :kind :space :title "N"
                             :value {:intent "i" :cells cell-seeds}}})
    (let [final (cell-ops-together! st "space:n" ops)
          texts (mapv :text final)]
      (is (= expected (count final))
          (str "no cell lost: " (count final) " cells survived of " expected))
      (is (every? (set texts) adds)
          "every concurrently appended cell is still there")
      (is (= (count texts) (count (set texts)))
          "and no op was applied twice")
      (is (every? universe texts)
          "no phantom cell: everything present was written by a seed or an op")
      ;; Deliberately NOT asserted, because it is genuinely not guaranteed:
      ;; the final ORDER (concurrent reorders conflict, and the winner is
      ;; whichever serialised last), and WHICH cell an index-addressed op landed
      ;; on — a `remove` at index 0 removes whatever the reorders left there.
      ;; That is last-writer-wins on intent, which is a design question. Losing a
      ;; cell is not; that is the defect, and it is what the assertions above
      ;; pin down. For the same reason the `edit-` texts are not all required to
      ;; survive: two edits at different indices can land on the same cell once
      ;; a remove has shifted it, and then the later one legitimately wins.
      )))

(deftest editing-two-notebooks-does-not-serialise
  ;; The lock is per notebook. If a later simplification collapses it to one
  ;; global monitor, this hangs: the fast notebook's edit would wait behind an
  ;; unrelated notebook's. Held open by a latch rather than a sleep, so the
  ;; happy path costs nothing and the failing path is a timeout, not a guess.
  (let [st      (sub/fresh-store)
        entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        done    (promise)
        secs    java.util.concurrent.TimeUnit/SECONDS
        real    nb/cell-op]
    (doseq [id ["space:slow" "space:fast"]]
      (sub/commit! st {:op :put :id id
                       :value {:id id :kind :space :title id :value {:cells []}}}))
    (with-redefs [nb/cell-op (fn [cells body]
                               ;; block INSIDE the critical section, only for one notebook
                               (when (= "space:slow" (:space body))
                                 (.countDown entered)
                                 (.await release 10 secs))
                               (real cells body))]
      (let [slow (Thread. #(srv/notebook-op! st {:space "space:slow" :op "add-text" :text "s"}))
            fast (Thread. #(do (srv/notebook-op! st {:space "space:fast" :op "add-text" :text "f"})
                               (deliver done true)))]
        (.start slow)
        (is (.await entered 10 secs) "the slow notebook's op reached the critical section")
        (.start fast)
        (is (true? (deref done 5000 false))
            "an edit to a different notebook must not wait on the first")
        (.countDown release)
        (.join slow)
        (.join fast)
        (is (= [{:text "s"}] (nb/cells-of (sub/object st "space:slow"))))
        (is (= [{:text "f"}] (nb/cells-of (sub/object st "space:fast"))))))))

(deftest edit-rejects-missing-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (is (:error (srv/edit! st nil "v")))
    (is (:error (srv/edit! st "nope" "v")))
    (is (= 1 (count (sub/history st))))          ; nothing committed
    (is (nil? (:error (srv/edit! st "doc:a" "y"))))
    (is (= "y" (:value (sub/object st "doc:a"))))))

(defn- await-job [id]
  (loop [n 0]
    (let [s (srv/job-status id)]
      (if (or (:done s) (> n 100))
        s
        (do (Thread/sleep 50) (recur (inc n)))))))

(deftest jobs-run-async-and-surface-results
  (let [id (srv/start-job! (fn [] {:x 1}))]
    (is (string? id))
    (let [s (await-job id)]
      (is (:done s))
      (is (= {:x 1} (:result s))))))

(deftest jobs-surface-thrown-errors
  (let [s (await-job (srv/start-job! (fn [] (throw (Exception. "boom")))))]
    (is (:done s))
    (is (= "boom" (get-in s [:result :error])))))

(deftest unknown-job-is-done-with-error
  (let [s (srv/job-status "job:nope")]
    (is (:done s))                 ; a poller must stop, not spin forever
    (is (:error s))))

(deftest suggest-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/suggest-start! st "tbl:t")))     ; not a notebook
    (is (:error (srv/suggest-start! st "nope")))      ; missing
    (with-redefs [srv/suggest! (fn [_ _] {:proposals [{:title "A"}]})]
      (let [{:keys [job error]} (srv/suggest-start! st "space:h")]
        (is (nil? error))
        (is (= [{:title "A"}] (get-in (await-job job) [:result :proposals])))))))

(deftest suggest-run-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/suggest-run-start! st "nope" [{:title "A"}] "new")))   ; missing notebook
    (with-redefs [srv/run-suggestions! (fn [_ _ _ _] {:ran ["space:dd-1"]})]
      (let [{:keys [job error]} (srv/suggest-run-start! st "space:h" [{:title "A"}] "new")]
        (is (nil? error))
        (is (= ["space:dd-1"] (get-in (await-job job) [:result :ran])))))))

(deftest research-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/research-start! st "nope" "q")))   ; missing notebook
    (is (:error (srv/research-start! st "space:h" " "))) ; blank prompt
    (with-redefs [srv/research! (fn [_ _ _] {:openId "find:h-1"})]
      (let [{:keys [job error]} (srv/research-start! st "space:h" "q")]
        (is (nil? error))
        (is (= "find:h-1" (get-in (await-job job) [:result :openId])))))))

(deftest state-payload-exposes-spawned-by
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "space:dd-1"
                     :value {:id "space:dd-1" :kind :space :title "Child"
                             :value {:intent "sub" :cells []
                                     :spawned-by {:space "space:h" :prompt "q"}}}})
    (let [by-id (into {} (map (juxt :id identity)) (:spaces (srv/state-payload st)))]
      (is (= "space:h" (get-in by-id ["space:dd-1" :spawned-by])))
      (is (nil? (get-in by-id ["space:h" :spawned-by]))))))

(deftest note-ids-survive-cell-removal
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    (srv/keep-note! st "space:n" "one" "1")
    (srv/keep-note! st "space:n" "two" "2")
    (srv/keep-note! st "space:n" "three" "3")
    ;; remove the MIDDLE note's cell (idx 1) — note:n-2's object lives on
    (srv/notebook-op! st {:space "space:n" :op "remove" :idx 1})
    (srv/keep-note! st "space:n" "four" "4")
    ;; the new note must NOT reuse an existing id
    (is (= "3" (:value (sub/object st "note:n-3"))))   ; old note untouched
    (is (= "4" (:value (sub/object st "note:n-4"))))))  ; new note got a fresh id

(def ^:private trows [{:region "EMEA" :revenue 100} {:region "APAC" :revenue 250}])

(defn- store-with-table []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value trows}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    st))

(deftest fns-payload-lists-builtins-and-agent-fns
  (let [st (store-with-table)]
    (is (:error (srv/fns-payload st "space:n")))          ; not a table
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [ids (set (map :id (:fns (srv/fns-payload st "tbl:t"))))]
      (is (contains? ids "lib:filter"))
      (is (contains? ids "fn:t-1")))))

(deftest fn-preview-commits-nothing
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-preview st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"})]
    (is (= [{:region "APAC" :revenue 250}] (:after r)))
    (is (= 2 (count (:before r))))
    (is (= 1 (:count r)))
    (is (= before (count (sub/history st))))))            ; preview is FREE

(deftest fn-apply-commits-one-tx-with-provenance
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")
        nid (:openId r)
        t (sub/object st nid)]
    (is (= (inc before) (count (sub/history st))))        ; ONE event (a :tx)
    (is (= "tbl:t" (:from t)))
    (is (= "lib:top" (:via t)))
    (is (some #(= nid (:ref %)) (get-in (sub/object st "space:n") [:value :cells])))
    (sub/undo! st)                                        ; one undo reverts table AND cell
    (is (nil? (sub/object st nid)))))

(deftest fn-apply-runs-agent-written-sci-fns
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [r (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil)]
      (is (= [200 500] (map :revenue (:value (sub/object st (:openId r)))))))))

(deftest fn-apply-rejects-non-space-target
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "tbl:t")]
    (is (:error r))                                       ; a table is not a notebook
    (is (= before (count (sub/history st))))              ; NOTHING committed
    (is (map? (srv/state-payload st)))))                  ; log not poisoned

(deftest fn-endpoints-reject-unknown-and-nil-fnid
  (let [st (store-with-table)
        before (count (sub/history st))]
    (is (:error (srv/fn-apply! st "tbl:t" nil {} nil)))
    (is (:error (srv/fn-apply! st "tbl:t" "lib:nope" {} nil)))
    (is (:error (srv/fn-preview st "tbl:t" nil {})))
    (is (= before (count (sub/history st))))))            ; nothing committed

(deftest fn-preview-surfaces-empty-result-as-error
  (let [st (store-with-table)
        r (srv/fn-preview st "tbl:t" "lib:filter" {:col "revenue" :op ">" :value "9999"})]
    (is (:error r))                                       ; honest "no rows came out"
    (is (nil? (:after r)))))                              ; not an empty table

(deftest store-at-clamps-and-freezes
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (sub/commit! st {:op :put :id "doc:b" :value {:id "doc:b" :kind :doc :title "B" :value "y"}})
    ;; nil/garbage → the live store itself
    (is (identical? st (srv/store-at st nil)))
    (is (identical? st (srv/store-at st "garbage")))
    ;; a valid n → frozen prefix
    (is (nil? (sub/object (srv/store-at st "1") "doc:b")))
    (is (= "A" (:title (sub/object (srv/store-at st "1") "doc:a"))))
    ;; clamped: negative → 0 events, huge → all events
    (is (empty? (sub/objects (srv/store-at st "-5"))))
    (is (= "B" (:title (sub/object (srv/store-at st "999") "doc:b"))))))

(deftest events-payload-has-humane-labels
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "Revenue" :value [{:a 1}]}})
    (sub/commit! st {:op :tx :events [{:op :put :id "doc:n" :value {:id "doc:n" :kind :doc :title "Note" :value "hi"}}
                                      {:op :assoc :id "tbl:t" :path [:title] :value "Revenue2"}]})
    (sub/commit! st {:op :delete :id "doc:n"})
    (let [{:keys [total events]} (srv/events-payload st)]
      (is (= 3 total))
      (is (= 3 (count events)))
      (is (= [1 2 3] (map :i events)))
      (is (str/includes? (:label (first events)) "Revenue"))   ; put labels with title
      (is (str/includes? (:label (second events)) "(+1)"))     ; tx aggregates
      (is (str/includes? (:label (nth events 2)) "doc:n")))))  ; delete labels the id

(deftest append-events-are-labelled-by-their-notebook
  ;; :append is not one of the shapes `case` used to know, and the default
  ;; label is the bare op name — the ⏱ scrubber would have read "append"
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n"
                     :value {:id "space:n" :kind :space :title "Cosmos" :value {:cells []}}})
    (sub/commit! st (nb/append-cell-event st "space:n" {:text "hello"}))
    (let [{:keys [events]} (srv/events-payload st)]
      (is (= "append" (:op (second events))))
      (is (str/includes? (:label (second events)) "Cosmos")))))

(deftest fn-apply-stamps-params
  (let [st (store-with-table)
        r  (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")]
    (is (= {:by "revenue" :n "1" :order "desc"} (:params (sub/object st (:openId r)))))))

(deftest rerun-recomputes-the-chain-in-one-tx
  (let [st (store-with-table)
        ;; b = top-1 of t (lib, params stamped) ; c = doubled b (agent fn, code stored)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil))   ; c derives from t too
        ;; now change the SOURCE: EMEA revenue jumps past APAC
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "EMEA" :revenue 900} {:region "APAC" :revenue 250}]})
        before (count (sub/history st))
        r  (srv/rerun! st "tbl:t")]
    (is (= (inc before) (count (sub/history st))))                 ; ONE :tx for the whole chain
    (is (= #{b c} (set (:refreshed r))))
    (is (= [{:region "EMEA" :revenue 900}] (:value (sub/object st b))))       ; top-1 follows the new data
    (is (= [1800 500] (map :revenue (:value (sub/object st c)))))             ; doubled fresh rows
    (sub/undo! st)                                                 ; one undo restores BOTH
    (is (= [{:region "APAC" :revenue 250}] (:value (sub/object st b))))))

(deftest rerun-on-a-derived-table-refreshes-it-then-descendants
  (let [st (store-with-table)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "2" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st b "fn:t-1" {} nil))          ; c derives from B (a chain!)
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "X" :revenue 7} {:region "Y" :revenue 3}]})
        r  (srv/rerun! st b)]                                      ; hit ↻ on the DERIVED table
    (is (= [b c] (:refreshed r)))                                  ; parent before child
    (is (= [14 6] (map :revenue (:value (sub/object st c)))))))    ; child used b's NEW rows

(deftest rerun-skips-paramless-legacy-and-reports-why
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "tbl:old"
                     :value {:id "tbl:old" :kind :table :title "Old derived"
                             :value [{:a 1}] :from "tbl:t" :via "lib:top"}})   ; no :params
    (let [before (count (sub/history st))
          r (srv/rerun! st "tbl:t")]
      (is (= [] (:refreshed r)))
      (is (= "tbl:old" (:id (first (:skipped r)))))
      (is (string? (:why (first (:skipped r)))))
      (is (= before (count (sub/history st)))))))                  ; nothing to refresh → no event

(deftest rerun-validates
  (let [st (store-with-table)]
    (is (:error (srv/rerun! st "nope")))
    (is (:error (srv/rerun! st "space:n")))))

(deftest connect-unions-two-notebooks-non-destructively
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:a"
                     :value {:id "space:a" :kind :space :title "Alpha"
                             :value {:intent "ia" :cells [{:text "pa"} {:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:b"
                     :value {:id "space:b" :kind :space :title "Beta"
                             :value {:intent "ib" :cells [{:ref "tbl:x" :view "table/pivot"} {:text "pb"}]}}})
    (let [before (count (sub/history st))
          r   (srv/connect! st "space:a" "space:b")
          sid (:openId r)
          sp  (sub/object st sid)]
      (is (= (inc before) (count (sub/history st))))               ; ONE event
      (is (= "Alpha × Beta" (:title sp)))
      (is (= ["space:a" "space:b"] (get-in sp [:value :merged-from])))
      ;; union: prose from both kept, shared ref deduped (first occurrence wins)
      (is (= [{:text "pa"} {:ref "tbl:x"} {:text "pb"}] (get-in sp [:value :cells])))
      ;; originals untouched
      (is (= 2 (count (get-in (sub/object st "space:a") [:value :cells]))))
      ;; state payload exposes merged-from
      (let [by-id (into {} (map (juxt :id identity)) (:spaces (:state r)))]
        (is (= ["space:a" "space:b"] (get-in by-id [sid :merged-from]))))
      ;; undo removes the connected space, originals intact
      (sub/undo! st)
      (is (nil? (sub/object st sid)))
      (is (sub/object st "space:a")))))

(deftest connect-validates
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:a" :value {:id "space:a" :kind :space :title "A" :value {:cells []}}})
    (is (:error (srv/connect! st "space:a" "space:a")))            ; self
    (is (:error (srv/connect! st "space:a" "nope")))               ; missing
    (is (:error (srv/connect! st "nope" "space:a")))
    (is (= 1 (count (sub/history st))))))

(deftest flow-plan-validation-drops-unknown-verbs-and-caps
  (let [raw [{:verb "research" :args {:prompt "p"} :note "n"}
             {:verb "hack-the-gibson" :args {}}
             {:verb "gate" :args {:question "ok to proceed?"}}
             {:verb "compute" :args {:id "$0" :prompt "top 5"}}
             {:verb "ask"} {:verb "draft"} {:verb "research" :args {:prompt "x"}}
             {:verb "research" :args {:prompt "y"}}]
        v (srv/validate-plan raw)]
    (is (= ["research" "gate" "compute" "ask" "draft" "research"] (map :verb v)))  ; unknown dropped, capped at 6
    (is (every? #(= "pending" (:status %)) v))
    (is (= {:prompt "p"} (:args (first v))))
    (is (= "" (:note (second v)))))                       ; missing note → ""
  ;; non-map :args from a weird LLM must not survive as-is
  (is (= {} (:args (first (srv/validate-plan [{:verb "research" :args "foo"}]))))))

(deftest flow-step-refs-resolve-to-outputs
  (let [flow {:steps [{:out "tbl:derived-3"} {:out "note:n-1"}]}]
    (is (= "tbl:derived-3" (srv/resolve-ref flow "$0")))
    (is (= "note:n-1" (srv/resolve-ref flow "$1")))
    (is (= "note:n-1" (srv/resolve-ref flow "$9")))       ; past the end → the latest output
    (is (= "tbl:t" (srv/resolve-ref flow "tbl:t")))       ; plain ids pass through
    (is (= 5 (srv/resolve-ref flow 5)))))                 ; non-strings untouched

(deftest flow-compute-refs-prefer-tabular-outputs
  ;; live-fire finding #2: research sometimes lands prose only; a $-ref from
  ;; compute must find the nearest TABLE output, never hand compute a note.
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "prose"}})
    (let [flow {:steps [{:out "tbl:t"} {:out "doc:d"} {}]}]
      (is (= "tbl:t" (srv/resolve-table-ref st flow "$1")))      ; $1 is a doc → nearest table wins
      (is (= "tbl:t" (srv/resolve-table-ref st flow "$0")))
      ;; no table among outputs → pass the walk result through (honest failure downstream)
      (is (= "doc:d" (srv/resolve-table-ref st {:steps [{:out "doc:d"}]} "$0")))
      ;; literal ids are never hijacked
      (is (= "tbl:x" (srv/resolve-table-ref st flow "tbl:x"))))))

(deftest flow-compute-step-receives-a-table-not-a-note
  (let [seen (atom nil)]
    (with-redefs [srv/research!    (fn [st space prompt]
                                     (if (str/includes? prompt "first") {:openId "tbl:t"} {:openId "doc:d"}))
                  srv/compute-clj! (fn [st id prompt space] (reset! seen id) {:openId "tbl:d"})]
      (let [st (store-with-table)]
        (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "p"}})
        (let [fid (srv/flow-create! st "space:n" "g"
                                    [{:verb "research" :args {:prompt "first"} :note "" :status "pending"}
                                     {:verb "research" :args {:prompt "second"} :note "" :status "pending"}
                                     {:verb "compute" :args {:id "$1" :prompt "top"} :note "" :status "pending"}])]
          (srv/run-flow! st fid)
          (is (= "tbl:t" @seen)))))))                            ; skipped the note, found the table

(deftest research-retries-empty-responses-then-fails-honestly
  ;; live-fire finding #4: v4-flash occasionally returns an empty final
  ;; message from the tool loop. One retry, then an honest error.
  (let [n (atom 0)]
    (with-redefs [agent/chat-tools (fn [& _] (if (= 1 (swap! n inc)) "" "real findings"))
                  loci.server/distill! (fn [& _] nil)]
      (let [st (store-with-table)
            r  (srv/research! st "space:n" "q")]
        (is (= 2 @n))
        (is (nil? (:error r)))
        (is (= "real findings" (:value (sub/object st (:openId r))))))))
  (with-redefs [agent/chat-tools (fn [& _] "  ")
                loci.server/distill! (fn [& _] nil)]
    (let [st (store-with-table)
          before (count (sub/history st))
          r (srv/research! st "space:n" "q")]
      (is (:error r))
      (is (= before (count (sub/history st)))))))          ; nothing committed

(deftest research-salvages-prose-tables
  (with-redefs [agent/chat-tools (fn [& _] "intro\n\nname | share_pct\n--- | ---\nA | 30%\nB | 20%\n\ndone")
                loci.server/distill! (fn [& _] nil)]
    (let [st (store-with-table)
          r  (srv/research! st "space:n" "extract a table of shares")]
      (is (clojure.string/starts-with? (str (:openId r)) "tbl:extract"))
      (is (= [{:name "A" :share_pct 30} {:name "B" :share_pct 20}]
             (:value (sub/object st (:openId r))))))))

(deftest compute-retries-once-with-error-feedback
  ;; live-fire finding: one bad LLM sample ("Could not resolve symbol")
  ;; coin-flipped the whole flow. One retry, error fed back, then honest.
  (let [calls (atom [])]
    (with-redefs [agent/make-clj-transform
                  (fn [cols sample prompt & [prev-code err]]
                    (swap! calls conj err)
                    (if err "(mapv #(assoc % :x 1) rows)" "(bad"))]
      (let [st (store-with-table)
            r  (srv/compute-clj! st "tbl:t" "add x" nil)]
        (is (nil? (:error r)))
        (is (= 2 (count @calls)))
        (is (nil? (first @calls)))                        ; first attempt: no error context
        (is (string? (second @calls)))                    ; retry got the eval error
        (is (= 1 (:x (first (:value (sub/object st (:openId r)))))))))))

(deftest compute-fails-honestly-after-two-bad-attempts
  (with-redefs [agent/make-clj-transform (fn [& _] "(bad")]
    (let [st (store-with-table)
          before (count (sub/history st))
          r (srv/compute-clj! st "tbl:t" "x" nil)]
      (is (str/starts-with? (:error r) "compute failed:"))
      (is (= before (count (sub/history st)))))))         ; nothing committed

(deftest flow-step-refs-walk-back-past-outputless-steps
  ;; the live-fire bug: the planner counted 1-based, so "$1" hit the GATE
  ;; (no :out) and the literal "$1" reached compute. $N now means "the
  ;; output at or before step min(N, last)" — deterministic, absorbs both
  ;; numbering conventions for research → gate → compute shapes.
  (let [flow {:steps [{:verb "research" :out "tbl:extract-1"}
                      {:verb "gate"}
                      {:verb "compute"}]}]
    (is (= "tbl:extract-1" (srv/resolve-ref flow "$1")))  ; gate has no out → walk back
    (is (= "tbl:extract-1" (srv/resolve-ref flow "$0")))
    (is (= "$0" (srv/resolve-ref {:steps [{:verb "gate"}]} "$0")))  ; nothing to find → literal
    (is (= "$x" (srv/resolve-ref flow "$x")))))           ; malformed → literal

(defn- flow-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:f" :value {:id "space:f" :kind :space :title "F"
                                                    :value {:intent "i" :cells []}}})
    st))

(defn- flow-of [st fid] (:value (sub/object st fid)))

(deftest flow-executes-steps-in-order-and-lands-as-a-cell
  (with-redefs [srv/research!   (fn [st space prompt] {:openId (str "find:" prompt)})
                srv/compute-clj! (fn [st id prompt space] {:openId (str "tbl:d-" id)})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "compute" :args {:id "$0" :prompt "top"} :note "" :status "pending"}])
          _   (srv/run-flow! st fid)
          fl  (flow-of st fid)]
      (is (= "done" (:status fl)))
      (is (= ["done" "done"] (map :status (:steps fl))))
      (is (= "find:a" (:out (first (:steps fl)))))
      (is (= "tbl:d-find:a" (:out (second (:steps fl)))))          ; $0 resolved to step 0's out
      ;; the flow is a CELL in the notebook
      (is (some #(= fid (:ref %)) (get-in (sub/object st "space:f") [:value :cells])))
      ;; every transition was an event: as-of mid-history shows step 0 done, step 1 pending
      (let [n   (count (sub/history st))
            mid (some (fn [k] (let [v (get-in (sub/as-of st k) [:objects fid :value])]
                                (when (and (= "done" (get-in v [:steps 0 :status]))
                                           (= "pending" (get-in v [:steps 1 :status]))) k)))
                      (range n))]
        (is (some? mid))))))                                       ; the scrubber can watch it work

(deftest flow-gate-parks-then-approve-resumes-reject-stops
  ;; start-job! stubbed synchronous so approve's resume can't race the test
  (with-redefs [srv/research! (fn [st space prompt] {:openId "find:x"})
                srv/delegate! (fn [st space] {:openId "draft:x"})
                srv/start-job! (fn [f] (f) "job:test")]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "gate" :args {:question "go on?"} :note "" :status "pending"}
                                 {:verb "draft" :args {} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [fl (flow-of st fid)]
        (is (= "needs-you" (:status fl)))
        (is (= ["done" "needs-you" "pending"] (map :status (:steps fl)))))
      ;; reject on a fresh identical flow stops it
      (let [fid2 (srv/flow-create! st "space:f" "g2"
                                   [{:verb "gate" :args {:question "?"} :note "" :status "pending"}
                                    {:verb "draft" :args {} :note "" :status "pending"}])]
        (srv/run-flow! st fid2)
        (srv/flow-gate! st fid2 false)
        (let [fl2 (flow-of st fid2)]
          (is (= "rejected" (:status fl2)))
          (is (= "rejected" (get-in fl2 [:steps 0 :status])))
          (is (= "pending" (get-in fl2 [:steps 1 :status])))))     ; never ran
      ;; approve resumes the first flow to completion (start-job! stub is sync)
      (srv/flow-gate! st fid true)
      (let [fl (flow-of st fid)]
        (is (= "done" (:status fl)))
        (is (= "draft:x" (:out (nth (:steps fl) 2))))))))

(deftest flow-step-failure-fails-the-flow-honestly
  (with-redefs [srv/research! (fn [st space prompt] {:error "no key"})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "draft" :args {} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [fl (flow-of st fid)]
        (is (= "failed" (:status fl)))
        (is (= "failed" (get-in fl [:steps 0 :status])))
        (is (= "no key" (get-in fl [:steps 0 :why])))
        (is (= "pending" (get-in fl [:steps 1 :status])))))))      ; honest stop, no cascade

(deftest flow-ask-step-lands-a-note
  (with-redefs [srv/ask! (fn [st prompt space] {:answer "42"})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "ask" :args {:prompt "meaning?"} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [out (:out (first (:steps (flow-of st fid))))]
        (is (str/starts-with? out "note:"))
        (is (= "42" (:value (sub/object st out))))))))

(deftest flow-create-validates-and-flow-mold-renders
  (let [st (flow-store)]
    (is (:error (srv/flow-start! st "nope" "g")))                  ; not a notebook → sync error
    (let [fid (srv/flow-create! st "space:f" "g" [{:verb "draft" :args {} :note "" :status "pending"}])
          m   (srv/mold-payload st fid nil)]
      (is (= "flow" (:kind m)))
      (is (= "g" (get-in m [:rendered :goal]))))))

(deftest state-payload-time-travels-via-frozen-store
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (let [past (srv/state-payload (srv/store-at st "1"))]
      (is (= 1 (:events past)))
      (is (= ["space:n"] (map :id (:spaces past))))
      (is (empty? (:objects past))))))

;; ---- real-world tables have gaps ----
;; Found by importing World Bank data: not every country reports unemployment,
;; so a numeric column arrives as a mix of numbers and "". Four of six viewers
;; threw ClassCastException on it — the seeded corpus has no missing values, so
;; nothing caught it. A substrate that cannot mold real data is not a substrate.

(defn- gappy-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:gappy"
                     :value {:id "tbl:gappy" :kind :table :title "With gaps"
                             :value [{:country "A" :region "North" :gdp 10.0 :jobless 5.0}
                                     {:country "B" :region "North" :gdp 20.0 :jobless ""}
                                     {:country "C" :region "South" :gdp 30.0 :jobless 7.0}
                                     {:country "D" :region "South" :gdp ""   :jobless ""}]}})
    st))

(deftest every-viewer-survives-missing-values
  (let [st (gappy-store)]
    (doseq [v ["table/rows" "table/summary" "table/cards" "table/pivot"
               "table/bar" "table/hist"]]
      (is (some? (:rendered (srv/mold-payload st "tbl:gappy" v)))
          (str v " threw or rendered nothing on a table with gaps")))))

(deftest aggregates-ignore-gaps-rather-than-counting-them
  (let [st (gappy-store)
        summ (:rendered (srv/mold-payload st "tbl:gappy" "table/summary"))
        gdp  (first (filter #(= "gdp" (:column %)) summ))]
    (is (= 3 (:n gdp)) "n must count the values that exist, not the rows")
    (is (= 60.0 (:sum gdp)))
    (is (= 20.0 (:avg gdp)) "the average must divide by what was measured")
    (is (= 10.0 (:min gdp)))
    (is (= 30.0 (:max gdp)))))

(deftest a-column-is-numeric-even-when-its-first-value-is-missing
  ;; numeric-cols used to sample only (first rows)
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:lead"
                     :value {:id "tbl:lead" :kind :table :title "Leading gap"
                             :value [{:name "A" :score ""} {:name "B" :score 5.0}
                                     {:name "C" :score 7.0}]}})
    (let [summ (:rendered (srv/mold-payload st "tbl:lead" "table/summary"))]
      (is (= ["score"] (mapv :column summ)))
      (is (= 12.0 (:sum (first summ)))))))

;; ---- the default chart must pick a column worth charting ----
;; Found with World Bank data: Bar summed inflation percentages across
;; countries and drew it confidently. It charted (first (numeric-cols rows)),
;; and above 8 keys Clojure hands back hash order, so "first" was arbitrary.
;; Column order cannot carry the answer — nippy thaws a 9-key map as a
;; hash-map, so the order is gone before a viewer ever sees it. The choice has
;; to come from what the column MEANS.

(defn- charted [st id]
  (:rendered (srv/mold-payload st id "table/bar")))

(deftest bar-prefers-a-quantity-over-a-rate-whatever-the-key-order
  (let [st (sub/fresh-store)
        rows (for [i (range 20)]
               {:country (str "C" i) :region (if (even? i) "North" "South")
                :iso3 (str "X" i) :gdp_usd_bn (* 10.0 (inc i)) :gdp_year 2024
                :gdp_per_capita 5000.0 :growth_pct 2.0
                :inflation_pct 3.0 :unemployment_pct 4.0})]
    (sub/commit! st {:op :put :id "tbl:wb"
                     :value {:id "tbl:wb" :kind :table :title "WB" :value (vec rows)}})
    (let [c (charted st "tbl:wb")]
      (is (= "gdp_usd_bn" (:y c))
          (str "charted " (:y c) " — a percentage or a year is not a quantity"))
      (is (= "sum" (:agg c))))))

(deftest bar-averages-when-every-numeric-column-is-a-rate
  ;; summing percentages is meaningless; averaging them is not
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:rates"
                     :value {:id "tbl:rates" :kind :table :title "Rates"
                             :value [{:country "A" :region "North" :inflation_pct 2.0}
                                     {:country "B" :region "North" :inflation_pct 4.0}
                                     {:country "C" :region "South" :inflation_pct 9.0}]}})
    (let [c (charted st "tbl:rates")]
      (is (= "inflation_pct" (:y c)))
      (is (= "avg" (:agg c)) "a rate must not be summed")
      (is (= 3.0 (get (first (filter #(= "North" (get % "region")) (:rows c)))
                      "inflation_pct"))
          "North should be the mean of 2 and 4"))))

(deftest a-year-column-is-never-the-measure
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:yr"
                     :value {:id "tbl:yr" :kind :table :title "Years"
                             :value [{:place "A" :region "N" :year 2024 :tonnes 5.0}
                                     {:place "B" :region "S" :year 2023 :tonnes 7.0}]}})
    (is (= "tonnes" (:y (charted st "tbl:yr"))))))

(deftest bar-picks-the-headline-quantity-not-a-small-per-person-measure
  ;; World Bank population table: summing life expectancy across countries is
  ;; meaningless, and "life_expectancy" is not a rate by name — so the choice
  ;; cannot come from names alone. Population is three orders of magnitude
  ;; larger, and that is the signal.
  (let [st (sub/fresh-store)
        rows (for [i (range 12)]
               {:country (str "C" i) :region (if (even? i) "North" "South")
                :iso3 (str "X" i)
                :population (* 1000000.0 (inc i)) :population_year 2024
                :urban_pct 55.0 :life_expectancy (+ 70.0 i)
                :density_per_km2 100.0 :fertility_rate 1.8
                :age_65_plus_pct 12.0 :note "x"})]
    (sub/commit! st {:op :put :id "tbl:pop"
                     :value {:id "tbl:pop" :kind :table :title "Pop" :value (vec rows)}})
    (let [c (:rendered (srv/mold-payload st "tbl:pop" "table/bar"))]
      (is (= "population" (:y c)) (str "charted " (:y c)))
      (is (= "sum" (:agg c))))))

;; ---- recency: what did you touch, and when ----
;; LEAP capped each group at "the first 8 encountered", which after the move to
;; Datalevin is hash order — so results were dropped by coin-flip. Ranking by
;; last-touched turns that cap into "the 8 most recent".

(deftest last-touched-picks-the-newest-event-that-names-an-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value 2})
    (let [t (srv/last-touched st)]
      (is (> (t "a") (t "b")) "a was edited after b was created")
      (is (= (t "a") (:ts (last (sub/history st))))))))

(deftest last-touched-sees-inside-a-tx
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "old" :value {:id "old" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :tx :events [{:op :put :id "x" :value {:id "x" :kind :doc :value 1}}
                                      {:op :put :id "y" :value {:id "y" :kind :doc :value 1}}]})
    (let [t (srv/last-touched st)]
      (is (some? (t "x")) "a :tx has no :id of its own; its sub-events do")
      (is (= (t "x") (t "y")))
      (is (> (t "x") (t "old"))))))

(deftest a-notebook-is-touched-when-anything-in-it-is
  ;; otherwise a hub you work in constantly looks dormant, because you never
  ;; edit the space object itself
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S"
                             :value {:intent "i" :cells [{:ref "tbl:t"}]}}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value []}})
    (let [t   (srv/last-touched st)
          nb  (srv/notebook-touched st t "space:s")]
      (is (> (t "tbl:t") (t "space:s")) "the table is newer than the notebook object")
      (is (= nb (t "tbl:t")) "the notebook inherits its newest member's recency"))))

(deftest state-payload-carries-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S" :value {:intent "i" :cells []}}})
    (is (number? (:touched (first (:spaces (srv/state-payload st))))))))

(defn- leap-of [st q] (srv/leap-payload st (mem/file-memory (tmpfile)) q))

(deftest leap-orders-each-group-most-recent-first
  (let [st (sub/fresh-store)]
    (doseq [n (range 3)]
      (sub/commit! st {:op :put :id (str "doc:d" n)
                       :value {:id (str "doc:d" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id))]
      (is (= ["doc:d2" "doc:d1" "doc:d0"] ids) "newest first"))))

(deftest leaps-cap-keeps-the-newest-eight-not-the-first-eight
  ;; the assertion that would have failed before this change: 12 matches, and
  ;; the 8 kept must be the 8 most recently touched
  (let [st (sub/fresh-store)]
    (doseq [n (range 12)]
      (sub/commit! st {:op :put :id (str "doc:w" n)
                       :value {:id (str "doc:w" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id) set)]
      (is (= 8 (count ids)))
      (is (= (set (map #(str "doc:w" %) (range 4 12))) ids)
          "the eight most recent, not doc:w0..w7"))))

(deftest leap-entries-carry-their-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:one"
                     :value {:id "doc:one" :kind :doc :title "widget" :value "x"}})
    (is (number? (:touched (first (filter #(= "doc" (:group %)) (leap-of st "widget"))))))))

(deftest ask-and-verbs-are-not-reordered-by-recency
  ;; "the top result is already the action" — Ask stays first, and view verbs
  ;; (which have no timestamp) keep their existing order
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "rows here"
                                                  :value [{:a 1}]}})
    (let [groups (mapv :group (leap-of st "rows"))]
      (is (= "viewer" (last groups)) "verbs stay at the end, unsorted"))))

;; ---- tags: the one thing the substrate cannot derive ----
;; Structure is computed (spawned-by, shares, lineage); subject is not. Tags
;; are agent-proposed and human-approved, and carry :by so an inference is
;; never mistaken for an assertion.

(defn- tagged-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:t"
                     :value {:id "space:t" :kind :space :title "T" :value {:intent "i" :cells []}}})
    st))

(deftest set-tags-commits-one-reversible-event
  ;; a first-time tag also earns its colour, so it costs two events: the
  ;; palette first, the tags second. The ORDER is the point — undo! undoes
  ;; the last event, so undo must remove the tags, not the colour.
  (let [st (tagged-store)
        before (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
    (is (= (+ 2 before) (count (sub/history st))) "one colour event, one tag event")
    (is (= ["semiconductors"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags]))))
    (sub/undo! st)
    (is (nil? (get-in (sub/object st "space:t") [:value :tags])) "undo restores the previous tags")
    (is (= "semiconductors" (first (keys (srv/tag-colors st))))
        "and leaves the colour standing — a colour is a preference about a subject")))

(deftest set-tags-stamps-provenance-and-time
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "world data" :by "agent"}])
        t  (first (get-in (sub/object st "space:t") [:value :tags]))]
    (is (= "agent" (:by t)))
    (is (number? (:ts t)))))

(deftest set-tags-normalizes-and-refuses-nonsense
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "  Semiconductors  " :by "you"}
                                 {:tag "semiconductors" :by "you"}
                                 {:tag "" :by "you"}])
    (is (= ["semiconductors"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags])))
        "trimmed, lower-cased, de-duplicated; blanks dropped")
    (is (:error (srv/set-tags! st "tbl:nope" [{:tag "x" :by "you"}])))
    (is (re-find #"not a notebook" (:error (srv/set-tags! st "tbl:nope" [{:tag "x" :by "you"}]))))))

(deftest setting-the-same-tags-commits-nothing
  ;; the notebook-op! discipline: never commit a phantom event
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (is (= n (count (sub/history st))))))

(deftest state-payload-carries-tags
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "agent"}])
    (is (= ["a"] (mapv :tag (:tags (first (:spaces (srv/state-payload st)))))))))

(deftest your-assertion-outranks-the-agents-inference
  ;; the same name arriving twice: de-duplication must not silently demote what
  ;; you asserted into something the agent merely inferred
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "agent"}
                                 {:tag "Semiconductors" :by "you"}])
    (let [tags (get-in (sub/object st "space:t") [:value :tags])]
      (is (= ["semiconductors"] (mapv :tag tags)))
      (is (= ["you"] (mapv :by tags)) "you asserted it; the agent only guessed it"))))

(deftest re-asserting-an-agents-tag-as-your-own-records-it
  ;; :by is the whole point of the model, so a change of provenance IS a change
  (let [st  (tagged-store)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "agent"} {:tag "b" :by "you"}])
        was (get-in (sub/object st "space:t") [:value :tags])
        n   (count (sub/history st))
        _   (Thread/sleep 2)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "you"} {:tag "b" :by "you"}])
        now (get-in (sub/object st "space:t") [:value :tags])]
    (is (= (inc n) (count (sub/history st))) "approving an agent's tag is an event")
    (is (= ["you" "you"] (mapv :by now)))
    (is (= (:ts (second was)) (:ts (second now)))
        "a tag you did not touch keeps when it was asserted")))

(deftest suggesting-tags-writes-nothing
  ;; a proposal you ignore must leave no trace — not a reversible event, none
  (with-redefs [agent/propose-tags (fn [_ _ _] ["semiconductors" "chokepoints"])]
    (let [st (tagged-store)
          before (count (sub/history st))
          r (srv/suggest-tags! st "space:t")]
      (is (= ["semiconductors" "chokepoints"] (:tags r)))
      (is (= before (count (sub/history st))) "proposing must not commit"))))

(deftest suggesting-tags-refuses-a-non-notebook
  (with-redefs [agent/propose-tags (fn [_ _ _] ["x"])]
    (let [st (tagged-store)]
      (is (re-find #"not a notebook" (:error (srv/suggest-tags! st "tbl:nope")))))))

(deftest a-failed-suggestion-leaves-the-notebook-untouched
  (with-redefs [agent/propose-tags (fn [_ _ _] (throw (Exception. "no key")))]
    (let [st (tagged-store)
          before (count (sub/history st))
          r (srv/suggest-tags! st "space:t")]
      (is (:error r) "an agent failure is reported, not thrown")
      (is (= before (count (sub/history st)))))))

(deftest a-spawned-notebook-inherits-its-parents-tags
  ;; without this a grandchild drops out of its own family under a filter
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
    (let [kid (srv/inherit-tags st "space:t")]
      (is (= ["semiconductors"] (mapv :tag kid)))
      (is (= ["agent"] (mapv :by kid)) "an inherited tag is an inference, not your assertion"))))

;; ---- tag colours ----
;; Colour belongs to the NAME, globally: "world data" green here and plum there
;; would defeat the point of colouring at all.

;; The names below are deliberately real subjects, not "a".."h". Single letters
;; are consecutive char codes, so a bare hash mod 8 is a perfect permutation of
;; the eight inks and every test here passes with no least-used rule at all —
;; certifying the one thing it was written to catch. These eight hash onto five
;; slots, which is what real tags do.
(def ^:private eight-subjects
  ["semiconductors" "world data" "chokepoints" "supply chain"
   "policy" "energy" "logistics" "materials"])

(deftest eight-tags-receive-eight-distinct-inks
  (let [st (tagged-store)]
    (doseq [t eight-subjects]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)]
      (is (= 8 (count reg)) "every name is in the registry")
      (is (= 8 (count (set (vals reg)))) "and no two share an ink"))))

(deftest the-ninth-tag-reuses-a-least-used-ink
  (let [st (tagged-store)]
    (doseq [t (conj eight-subjects "lithography")]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)
          f   (frequencies (vals reg))]
      (is (= 9 (count reg)))
      (is (every? (set srv/tag-inks) (vals reg)) "only palette inks are ever assigned")
      (is (= 2 (apply max (vals f))) "exactly one ink is doubled")
      (is (= 7 (count (filter #(= 1 %) (vals f)))) "the other seven are untouched"))))

(deftest several-new-tags-in-one-call-get-different-inks
  ;; "world data" and "supply chain" hash to the SAME starting ink, so this is
  ;; the pair that catches assignment weighed against the registry as it was
  ;; read: measured against a map holding neither, both take that ink. Two
  ;; names that started apart would come out distinct even with the bug.
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "world data" :by "you"}
                                 {:tag "supply chain" :by "you"}])
    (let [reg (srv/tag-colors st)]
      (is (= 2 (count reg)))
      (is (= 2 (count (set (vals reg))))
          "the second name is weighed against a registry that already holds the first"))))

(deftest assignment-is-deterministic
  (let [ink (fn [] (let [st (tagged-store)]
                     (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
                     (get (srv/tag-colors st) "semiconductors")))]
    (is (= (ink) (ink)) "the same name always gets the same ink from an empty registry")))

(deftest concurrent-taggings-do-not-lose-colours
  ;; The registry is the first thing in server.clj that is GLOBAL — every other
  ;; write is scoped to one notebook, so nothing before it could contend. http-kit
  ;; serves from a worker pool, so two notebooks being tagged at once is ordinary.
  ;; A read-modify-write of the whole map loses whichever assignment lands second;
  ;; the tags survive and the colour does not, so the symptom is a tag with no ink.
  (let [st      (sub/fresh-store)
        threads 8
        per     8
        names   (fn [i] (mapv #(str "subject " i "-" %) (range per)))
        latch   (java.util.concurrent.CountDownLatch. 1)]
    (doseq [i (range threads)]
      (sub/commit! st {:op :put :id (str "space:" i)
                       :value {:id (str "space:" i) :kind :space :title "T"
                               :value {:intent "i" :cells []}}}))
    (let [ts (mapv (fn [i]
                     (doto (Thread.
                            #(do (.await latch)
                                 (srv/set-tags! st (str "space:" i)
                                                (mapv (fn [n] {:tag n :by "you"}) (names i)))))
                       (.start)))
                   (range threads))]
      (.countDown latch)
      (doseq [t ts] (.join t)))
    (let [reg  (srv/tag-colors st)
          want (mapcat names (range threads))]
      (is (= (* threads per) (count reg))
          "every name assigned by every thread is still in the registry")
      (is (every? #(contains? reg %) want) "no name lost its colour")
      (is (every? (set srv/tag-inks) (vals reg))))))

(deftest a-tag-whose-colour-exists-costs-one-event
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"} {:tag "a" :by "agent"}])
    (is (= n (count (sub/history st))) "nothing changed at all")
    (srv/set-tags! st "space:t" [])
    (is (= (inc n) (count (sub/history st))) "clearing is one event; no colour is involved")))

(deftest refusing-a-non-notebook-assigns-nothing
  (let [st (tagged-store)]
    (is (:error (srv/set-tags! st "tbl:nope" [{:tag "ghost" :by "you"}])))
    (is (empty? (srv/tag-colors st)) "a refused write must not leave a colour behind")))

(deftest state-payload-carries-tag-colors
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (let [p (srv/state-payload st)]
      (is (contains? (:tag-colors p) "a"))
      (is (not-any? #(= "palette" (:kind %)) (:objects p))
          "the registry is plumbing — it must not sit in the object list next to your notebooks"))))

(deftest the-palette-object-never-appears-in-leap
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (let [ids (map :id (leap-of st ""))]
      (is (seq ids) "the empty query still lists objects, so this is a real exclusion")
      (is (not-any? #(= "tag-palette" %) ids)))))

(deftest suggesting-tags-assigns-no-colours
  ;; proposing writes nothing at all — colours included
  (let [st (tagged-store)
        n  (count (sub/history st))]
    (with-redefs [agent/propose-tags (fn [_ _ _] ["ghost-tag"])]
      (srv/suggest-tags! st "space:t"))
    (is (= n (count (sub/history st))) "zero events")
    (is (empty? (srv/tag-colors st)) "and no ink claimed by a proposal you may discard")))

;; ---- choosing a colour yourself ----

(deftest set-tag-color-changes-one-tag-everywhere
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))
        ink (nth srv/tag-inks 4)]
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")))
    (is (= (inc n) (count (sub/history st))) "one event")
    (srv/set-tag-color! st "a" ink)
    (is (= (inc n) (count (sub/history st))) "setting the colour it already is commits nothing")))

(deftest set-tag-color-refuses-what-is-not-in-the-palette
  (let [st (tagged-store)]
    (is (re-find #"palette" (:error (srv/set-tag-color! st "a" "#ff00ff"))))
    (is (:error (srv/set-tag-color! st "   " (first srv/tag-inks))))
    (is (empty? (srv/tag-colors st)) "a refused write leaves nothing behind")))

(deftest set-tag-color-normalizes-the-name
  (let [st (tagged-store)]
    (srv/set-tag-color! st "  World Data  " (first srv/tag-inks))
    (is (contains? (srv/tag-colors st) "world data")
        "the same normalisation clean-tags applies, or the colour attaches to a name no tag has")))

(deftest choosing-a-colour-is-undoable
  ;; every other write in this file is reversible, and the shell's undo is one
  ;; button: a colour that could not be taken back would be the one write you
  ;; cannot walk out of.
  (let [st  (tagged-store)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        was (get (srv/tag-colors st) "a")
        ink (first (remove #{was} srv/tag-inks))]
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")) "the chosen ink is the assigned one changed")
    (sub/undo! st)
    (is (= was (get (srv/tag-colors st) "a")) "undo restores the previous ink")
    (is (= ["a"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags])))
        "and the tag it belongs to is still there")))

(deftest set-tag-color-works-before-any-tag-exists
  ;; the picker can be opened on a store where nothing has ever been tagged, so
  ;; this is the path that has to CREATE the registry object rather than add a
  ;; key to it
  (let [st  (tagged-store)
        n   (count (sub/history st))
        ink (nth srv/tag-inks 2)]
    (is (nil? (sub/object st "tag-palette")) "no registry yet")
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")))
    (is (= :palette (:kind (sub/object st "tag-palette")))
        "the registry it created is a real object, hideable by :kind")
    (is (= (inc n) (count (sub/history st))) "creating it is still one event")))

(deftest concurrent-colour-choices-do-not-lose-each-other
  ;; set-tag-color! writes the same global object assign-inks! does, so it
  ;; inherits the same hazard: a whole-map read-modify-write drops whichever
  ;; choice lands second. Written as a whole-map :put this function passes every
  ;; other test in this namespace — only contention tells the two apart.
  (let [st      (sub/fresh-store)
        threads 8
        per     8
        names   (fn [i] (mapv #(str "subject " i "-" %) (range per)))
        latch   (java.util.concurrent.CountDownLatch. 1)
        ts (mapv (fn [i]
                   (doto (Thread.
                          #(do (.await latch)
                               (doseq [n (names i)]
                                 (srv/set-tag-color! st n (nth srv/tag-inks (mod i 8))))))
                     (.start)))
                 (range threads))]
    (.countDown latch)
    (doseq [t ts] (.join t))
    (let [reg  (srv/tag-colors st)
          want (mapcat names (range threads))]
      (is (= (* threads per) (count reg)) "every name coloured by every thread is still there")
      (is (every? #(contains? reg %) want) "no choice was clobbered by a concurrent one"))))

;; ---- ✧ suggest ----
;; The agent proposes; you decide. suggest! reads and proposes and commits
;; NOTHING; run-suggestions! acts on exactly the list you approved and never
;; re-asks the agent.

(defn- hub-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:hub"
                     :value {:id "space:hub" :kind :space :title "Hub"
                             :value {:intent "the hub" :cells []}}})
    st))

(def ^:private three-proposals
  [{:title "A" :intent "ai" :query "qa"}
   {:title "B" :intent "bi" :query "qb"}
   {:title "C" :intent "ci" :query "qc"}])

(defn- spawned-kids [st]
  (->> (nb/notebooks st) (filter #(get-in % [:value :spawned-by :space]))))

(deftest suggest-commits-nothing-at-all
  ;; the whole point: a proposal you may discard must not touch the substrate
  (let [st (hub-store)
        n  (count (sub/history st))]
    (with-redefs [agent/propose-subtopics (fn [_ _ _] three-proposals)]
      (let [r (srv/suggest! st "space:hub")]
        (is (= 3 (count (:proposals r))))
        (is (= "qa" (:query (first (:proposals r)))))))
    (is (= n (count (sub/history st))) "zero events")))

(deftest suggest-refuses-a-non-notebook
  (let [st (hub-store)]
    (is (re-find #"not a notebook" (:error (srv/suggest! st "tbl:nope"))))))

(deftest a-failed-proposal-is-reported-not-thrown
  (with-redefs [agent/propose-subtopics (fn [_ _ _] (throw (Exception. "no key")))]
    (let [st (hub-store)
          n  (count (sub/history st))]
      (is (= "no key" (:error (srv/suggest! st "space:hub"))))
      (is (= n (count (sub/history st)))))))

(deftest run-suggestions-new-spawns-one-notebook-each
  (let [st (hub-store)]
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "new"))
    (let [kids (spawned-kids st)]
      (is (= 3 (count kids)))
      (is (every? #(= "space:hub" (get-in % [:value :spawned-by :space])) kids))
      (is (= #{"A" "B" "C"} (set (map :title kids)))))))

(deftest run-suggestions-here-spawns-nothing
  (let [st (hub-store)
        before (count (nb/notebooks st))
        seen (atom [])]
    (with-redefs [srv/research! (fn [_ sid q] (swap! seen conj [sid q]) {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "here"))
    (is (= before (count (nb/notebooks st))) "no notebook is created")
    (is (= [["space:hub" "qa"] ["space:hub" "qb"] ["space:hub" "qc"]] @seen)
        "each question is researched in the hub itself, in order")))

(deftest run-suggestions-runs-only-what-you-approved
  ;; the curated list is the input; the server must not re-ask the agent
  (let [st (hub-store)
        seen (atom [])]
    (with-redefs [agent/propose-subtopics (fn [_ _ _] (throw (Exception. "must not be called")))
                  srv/research! (fn [_ _ q] (swap! seen conj q) {:ok true})]
      (srv/run-suggestions! st "space:hub"
                            [{:title "A" :intent "ai" :query "edited by hand"}] "here"))
    (is (= ["edited by hand"] @seen))))

(deftest items-arrive-over-http-with-the-keys-the-code-destructures
  ;; run-suggestions! destructures :title/:intent/:query, and `items` comes off
  ;; the wire as JSON. Drive the REAL body reader so the assumption that
  ;; body-json keywordizes nested object keys is proven, not assumed.
  (let [st (hub-store)
        body (str "{\"space\":\"space:hub\",\"destination\":\"here\",\"items\":"
                  "[{\"title\":\"A\",\"intent\":\"ai\",\"query\":\"qa\"},"
                  " {\"title\":\"B\",\"intent\":\"bi\",\"query\":\"qb\"}]}")
        {:keys [space items destination]} (#'srv/body-json {:body (java.io.StringReader. body)})
        seen (atom [])]
    (with-redefs [srv/research! (fn [_ sid q] (swap! seen conj [sid q]) {:ok true})]
      (srv/run-suggestions! st space items destination))
    (is (= [["space:hub" "qa"] ["space:hub" "qb"]] @seen)
        "the questions survived the JSON round-trip into the researcher")))

(deftest run-suggestions-refuses-nonsense-honestly
  (let [st (hub-store)]
    (is (re-find #"not a notebook" (:error (srv/run-suggestions! st "tbl:x" three-proposals "new"))))
    (is (:error (srv/run-suggestions! st "space:hub" [] "new")))
    (is (re-find #"destination" (:error (srv/run-suggestions! st "space:hub" three-proposals "sideways"))))
    (is (= 1 (count (nb/notebooks st))) "a refused run creates nothing")))

(deftest a-failure-midway-keeps-what-already-landed
  ;; deliberate: a partial result is worth keeping and is individually undoable.
  ;; B's notebook is committed BEFORE its research runs, so a throw inside that
  ;; research leaves B's notebook standing and empty — honest, and undoable.
  ;; What must not happen is A's finished work being rolled back, or C running
  ;; on after a failure nobody has seen yet.
  (let [st (hub-store)
        n  (atom 0)]
    (with-redefs [srv/research! (fn [_ _ _] (if (= 2 (swap! n inc))
                                              (throw (Exception. "boom"))
                                              {:ok true}))]
      (is (= "boom" (:error (srv/run-suggestions! st "space:hub" three-proposals "new")))
          "the failure is reported, not thrown"))
    (is (= #{"A" "B"} (set (map :title (spawned-kids st))))
        "A's notebook survives B's failure; C never started")
    (is (= 2 @n) "the run stopped at the failure")))

(deftest a-new-notebook-never-overwrites-an-existing-one
  ;; the id came from the TOTAL notebook count, so it had nothing to do with the
  ;; dd sequence — and the write is a :put, which overwrites in silence. Two
  ;; notebooks in the store is exactly the state where `(inc (count …))` mints
  ;; "space:dd-3": the shape you get after two of an earlier batch are deleted.
  (let [st (hub-store)]
    (sub/commit! st {:op :put :id "space:dd-3"
                     :value {:id "space:dd-3" :kind :space :title "PRECIOUS"
                             :value {:intent "do not lose me" :cells []}}})
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "new"))
    (is (= "PRECIOUS" (:title (sub/object st "space:dd-3"))) "untouched")
    (is (= 5 (count (nb/notebooks st))) "hub + precious + three new")
    (is (= 3 (count (set (map :id (spawned-kids st))))) "and the three got distinct ids")))

(deftest spawned-children-inherit-the-hubs-tags
  (let [st (hub-store)]
    (srv/set-tags! st "space:hub" [{:tag "semiconductors" :by "you"}])
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" [(first three-proposals)] "new"))
    (let [kid (first (spawned-kids st))]
      (is (= ["semiconductors"] (mapv :tag (get-in kid [:value :tags])))))))

;; ---- where the model lives: endpoint, key and model are resolved, not frozen ----
;; The wire format is OpenAI's, so any server that speaks POST /v1/chat/completions
;; will do — including one on your own hardware. These tests exercise the
;; RESOLUTION ORDER only; nothing here touches the network.
;;
;; `agent/env` is the seam they bind. System/getenv is a Java static method and
;; with-redefs cannot reach it, so a test written directly against it would pass
;; or fail for reasons that have nothing to do with the resolver.

(defn- resolved
  "Call one of the agent's private resolvers under a fixed environment and a
   fixed set of working-directory files."
  [resolver env files]
  (with-redefs [agent/env #(get env %) agent/from-file #(get files %)]
    (resolver)))

(deftest llm-endpoint-defaults-to-deepseek
  ;; back-compat: an existing checkout sets none of this, and must not move.
  (is (= "https://api.deepseek.com/chat/completions"
         (resolved #'agent/endpoint {} {}))))

(deftest llm-endpoint-reads-env-then-file-then-default
  (is (= "http://from-env:8080/v1/chat/completions"
         (resolved #'agent/endpoint
                   {"LOCI_LLM_ENDPOINT" "http://from-env:8080/v1/chat/completions"}
                   {".llm-endpoint" "http://from-file:8080/v1/chat/completions"}))
      "the environment wins over the file")
  (is (= "http://from-file:8080/v1/chat/completions"
         (resolved #'agent/endpoint {} {".llm-endpoint" "http://from-file:8080/v1/chat/completions"}))
      "the file wins over the default"))

(deftest llm-key-is-not-named-after-a-vendor-you-may-not-use
  ;; someone pointing loci at their own llama server should not have to file
  ;; its token under DEEPSEEK_API_KEY or in a file named .deepseek-key — but
  ;; everyone who already did keeps working. The whole chain, in order:
  ;; LOCI_LLM_API_KEY → DEEPSEEK_API_KEY → .llm-key → .deepseek-key.
  (let [all-four {"LOCI_LLM_API_KEY" "neutral-env" "DEEPSEEK_API_KEY" "vendor-env"}
        both-files {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}]
    (is (= "neutral-env" (resolved #'agent/api-key all-four both-files))
        "LOCI_LLM_API_KEY wins over everything")
    (is (= "vendor-env" (resolved #'agent/api-key {"DEEPSEEK_API_KEY" "vendor-env"} both-files))
        "DEEPSEEK_API_KEY still works untouched, and still beats both files")
    (is (= "neutral-file" (resolved #'agent/api-key {} both-files))
        ".llm-key is the vendor-neutral file, and wins over .deepseek-key")
    (is (= "vendor-file" (resolved #'agent/api-key {} {".deepseek-key" "vendor-file"}))
        "and .deepseek-key alone still resolves, so no setup that predates .llm-key changes"))
  (is (nil? (resolved #'agent/api-key {} {}))
      "nothing configured resolves to nothing — request throws, it does not guess"))

(deftest a-blank-key-at-any-step-falls-through-rather-than-shadowing-the-next
  ;; "" is truthy in Clojure, so without `non-blank` at every step an empty
  ;; LOCI_LLM_API_KEY= in a loci.env — which is exactly what loci.env.example
  ;; ships — would shadow a perfectly good DEEPSEEK_API_KEY or key file below
  ;; it. An empty file is the same hazard: `touch .llm-key` must not hide
  ;; .deepseek-key. Each step is checked against the one immediately after it.
  (is (= "vendor-env" (resolved #'agent/api-key
                                {"LOCI_LLM_API_KEY" "" "DEEPSEEK_API_KEY" "vendor-env"}
                                {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}))
      "blank LOCI_LLM_API_KEY falls through to DEEPSEEK_API_KEY")
  (is (= "neutral-file" (resolved #'agent/api-key
                                  {"LOCI_LLM_API_KEY" "  " "DEEPSEEK_API_KEY" "\t"}
                                  {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}))
      "blank DEEPSEEK_API_KEY falls through to .llm-key")
  (is (= "vendor-file" (resolved #'agent/api-key {} {".llm-key" "" ".deepseek-key" "vendor-file"}))
      "a blank .llm-key falls through to .deepseek-key")
  (is (nil? (resolved #'agent/api-key
                      {"LOCI_LLM_API_KEY" "" "DEEPSEEK_API_KEY" " "}
                      {".llm-key" "  " ".deepseek-key" "\n"}))
      "all four blank is no key at all — the honest error fires, nothing is guessed"))

(deftest a-blank-llm-value-is-unset-not-a-configured-empty-string
  ;; loci.env.example ships `LOCI_LLM_API_KEY=` empty, and "" is truthy in
  ;; Clojure — so an empty key resolved to "" and the request went out as
  ;; `Authorization: Bearer `, which a llama.cpp started without --api-key
  ;; rejects. The honest "no LLM key" error never fired and the user saw a 401
  ;; that reads like a wrong key.
  (is (nil? (resolved #'agent/api-key {"LOCI_LLM_API_KEY" ""} {}))
      "an empty key is no key, so request throws the honest error")
  (is (= "vendor" (resolved #'agent/api-key {"LOCI_LLM_API_KEY" "   " "DEEPSEEK_API_KEY" "vendor"} {}))
      "a blank value falls through to the next source rather than shadowing it")
  (is (= "https://api.deepseek.com/chat/completions"
         (resolved #'agent/endpoint {"LOCI_LLM_ENDPOINT" ""} {}))
      "an empty endpoint is not a configured empty URL")
  (is (= "deepseek-v4-flash" (resolved #'agent/model {"DEEPSEEK_MODEL" "  "} {}))
      "and a blank model does not become the model name"))

(deftest llm-model-still-reads-env-then-file-then-default
  ;; DEEPSEEK_MODEL now names the model on WHATEVER server is configured; the
  ;; variable name is historical, and its resolution order must not have moved.
  (is (= "local-thing" (resolved #'agent/model {"DEEPSEEK_MODEL" "local-thing"}
                                 {".deepseek-model" "file-thing"})))
  (is (= "file-thing" (resolved #'agent/model {} {".deepseek-model" "file-thing"})))
  (is (= "deepseek-v4-flash" (resolved #'agent/model {} {}))))

;; ============================================================================
;; the memory pane, and which callers pay for meaning
;; ============================================================================

(defn- embed-env
  "loci.embed's resolvers seeing exactly `env` and no files. Nothing here opens
   a socket — every assertion below is about the *shape* of a payload."
  [env]
  {:env #(get env %) :file (constantly nil)})

(deftest the-memory-pane-reports-what-awaits-an-embedding-and-never-ships-a-vector
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (mold/remember m "Neptune takes 165 years to orbit the Sun." {})
    ;; one fact given a vector directly: the pane's job is to report the state of
    ;; the store, not to produce it, so this needs no embedder and no network
    (swap! (:!facts m) update "mem-1" assoc :vec (vec (repeat 1024 0.01)) :model "m" :dim 1024)

    (let [{:keys [env file]} (embed-env {})]
      (with-redefs [embed/env env embed/from-file file]
        (let [p (srv/memory-payload m nil)]
          (is (= 2 (count (:facts p))))
          (is (nil? (:embedding p)))
          (is (= 2 (:awaiting p))
              "with no embedder configured every fact awaits one forever — reported next to a nil model, so it reads as an unconfigured feature and not as a backlog"))))

    (let [{:keys [env file]} (embed-env {"LOCI_EMBED_ENDPOINT" "http://127.0.0.1:9/v1/embeddings"
                                         "LOCI_EMBED_MODEL"    "m"})]
      (with-redefs [embed/env env embed/from-file file]
        (let [p (srv/memory-payload m nil)]
          (is (= "m" (:embedding p)))
          (is (= 1 (:awaiting p)) "the embedded one awaits nothing")
          (is (every? #(nil? (:vec %)) (:facts p))
              "and no vector goes out — ~1024 floats is about 20 KB of JSON per fact, and the pane shows text"))))))

(defn- recall-spy
  "A Recall that answers nothing and writes down what it was asked.

   A `with-redefs` of `mold/recall` would not see these calls at all: the
   compiler turns a protocol call into a cached callsite that goes straight to
   the implementing class, so rebinding the var intercepts nothing. Both payload
   functions take the memory as an argument, so handing them one is both simpler
   and closer to what actually happens."
  [seen]
  (reify mold/Recall
    (remember  [_ _ _] :ok)
    (all-facts [_] [])
    (recall    [_ q opts] (swap! seen conj [q opts]) [])))

(deftest the-pane-searches-by-meaning-and-the-keystroke-path-does-not
  ;; The single most expensive decision in hybrid recall, asserted at the two
  ;; call sites that differ. /api/leap runs on every keystroke; embedding a query
  ;; is a 20–50 ms network round trip.
  (let [seen (atom [])
        m    (recall-spy seen)]
    (srv/memory-payload m "chips")
    (is (= [["chips" {:k 20 :semantic? true}]] @seen)
        "a search the user typed and is waiting for asks for meaning")
    (reset! seen [])
    (srv/leap-payload (sub/fresh-store) m "chips")
    (is (= [["chips" {:k 8}]] @seen))
    (is (not (:semantic? (second (first @seen))))
        "and the keystroke path does not — it must stay exactly as fast as it was")))

;; ============================================================================
;; memory scoped to one notebook's lineage (/api/memory?space=…)
;; ============================================================================

(defn- put-space!
  "A notebook holding one doc cell, plus whatever lineage keys the case needs."
  [st id title doc-id doc-text extra]
  (sub/commit! st {:op :put :id doc-id
                   :value {:id doc-id :kind :doc :title (str title " findings") :value doc-text}})
  (sub/commit! st {:op :put :id id
                   :value {:id id :kind :space :title title
                           :value (merge {:intent title :cells [{:ref doc-id}]} extra)}}))

(defn- lineage-fixture
  "A substrate whose shape covers every reason `nb/links` can report:

     space:semis   the root
       space:kid     spawned-by semis          — descent
         space:gk      spawned-by kid          — descent, one more hop
     space:other   unrelated
     space:mix     merged-from [semis other]   — absorbed both
     space:peer    shares find:semis-1 as a cell of its own

   Every notebook holds one doc cell, and every fact below is recorded the way
   `distill!` records one: `:source {:obj <doc> :space <notebook>}`."
  []
  (let [st (sub/fresh-store)]
    (put-space! st "space:semis" "Semis" "find:semis-1" "chips fabs" {})
    (put-space! st "space:kid"   "Kid"   "find:kid-1"   "chips euv"
                {:spawned-by {:space "space:semis" :prompt "go deeper"}})
    (put-space! st "space:gk"    "GKid"  "find:gk-1"    "chips litho"
                {:spawned-by {:space "space:kid" :prompt "deeper still"}})
    (put-space! st "space:other" "Other" "find:other-1" "chips unrelated" {})
    (put-space! st "space:mix"   "Mix"   "find:mix-1"   "chips merged"
                {:merged-from ["space:semis" "space:other"]})
    (sub/commit! st {:op :put :id "space:peer"
                     :value {:id "space:peer" :kind :space :title "Peer"
                             :value {:intent "peer" :cells [{:ref "find:semis-1"}]}}})
    st))

(defn- lineage-memory
  "One fact per notebook, all of them lexically matching \"chips\" so the
   *filter* is the only thing that can separate them."
  []
  (let [m (mem/file-memory (tmpfile))]
    (doseq [[nb obj txt] [["space:semis" "find:semis-1" "chips are fabricated in Taiwan."]
                          ["space:kid"   "find:kid-1"   "chips need EUV lithography."]
                          ["space:gk"    "find:gk-1"    "chips shrink with each node."]
                          ["space:other" "find:other-1" "chips are unrelated here."]
                          ["space:mix"   "find:mix-1"   "chips were merged together."]
                          ["space:peer"  "find:peer-1"  "chips seen by a peer notebook."]]]
      (mold/remember m txt {:source {:obj obj :space nb}}))
    m))

(defn- scoped-facts
  "The fact strings /api/memory returns for `q` under `space`, with no embedder
   configured anywhere — `:semantic? true` degrades to lexical, so this test
   opens no socket and passes offline."
  [st m q space]
  (let [{:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file]
      (let [p (srv/memory-request st m q space)]
        (assoc p :fact-set (set (map :fact (:facts p))))))))

(deftest memory-scoped-to-a-notebook-sees-its-lineage-and-nothing-else
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; descent, transitively: the notebook, its child, and its child's child
    (is (= #{"chips are fabricated in Taiwan." "chips need EUV lithography."
             "chips shrink with each node."}
           (:fact-set (scoped-facts st m "chips" "space:semis")))
        "spawned is lineage and it is transitive; nothing else is")
    ;; upward is not lineage — from the child you do not see the parent
    (is (= #{"chips need EUV lithography." "chips shrink with each node."}
           (:fact-set (scoped-facts st m "chips" "space:kid")))
        "spawned-by is the parent, and following it would reach every sibling")
    ;; a merge absorbs both sides' facts
    (is (= #{"chips were merged together." "chips are fabricated in Taiwan."
             "chips need EUV lithography." "chips shrink with each node."
             "chips are unrelated here."}
           (:fact-set (scoped-facts st m "chips" "space:mix")))
        "merged-from brings in what was folded in — and its descent with it")
    ;; …but the merge is downstream of semis, so semis does not gain the mix
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:semis"))
                        "chips were merged together."))
        "merged is upward: a notebook this one was folded into is not its lineage")
    ;; A shared cell brings the facts distilled FROM THAT CELL and nothing else.
    ;; space:peer holds find:semis-1 as one of its own cells, so the fact whose
    ;; :source :obj is find:semis-1 is in peer's scope — it is a fact about a
    ;; document sitting in peer. What peer does NOT get is the rest of semis:
    ;; not its child notebooks, and not a fact recorded against semis with no
    ;; object (which is what `ask!` writes).
    (is (= #{"chips seen by a peer notebook." "chips are fabricated in Taiwan."}
           (:fact-set (scoped-facts st m "chips" "space:peer")))
        "a shared cell is in scope; the notebook that shares it is not")
    (mold/remember m "chips policy discussed with no document." {:source {:space "space:semis"}})
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:peer"))
                        "chips policy discussed with no document."))
        "sharing one cell does not make peer a descendant of semis")
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:semis"))
                        "chips seen by a peer notebook.")))))

(deftest a-scope-that-holds-no-matching-fact-returns-empty-and-says-so
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; "unrelated" matches exactly one fact, and it lives in space:other
    (let [p (scoped-facts st m "unrelated" "space:other")]
      (is (= #{"chips are unrelated here."} (:fact-set p))))
    (let [p (scoped-facts st m "unrelated" "space:semis")]
      (is (= [] (:facts p))
          "a scoped recall that matches nothing in scope answers with nothing — it does NOT fall back to the whole memory")
      (is (= "space:semis" (get-in p [:scope :space])))
      (is (true? (get-in p [:scope :empty]))
          "and it says the emptiness is the scope's, not the memory's")
      (is (pos? (get-in p [:scope :sources]))
          "the eligible set was non-empty, so this is 'nothing here', not 'nothing to look at'"))))

(deftest a-scoped-browse-with-no-query-is-scoped-too
  ;; all-facts takes no filter, so this is the path most likely to leak
  (let [st (lineage-fixture)
        m  (lineage-memory)
        p  (scoped-facts st m nil "space:kid")]
    (is (= #{"chips need EUV lithography." "chips shrink with each node."} (:fact-set p)))
    (is (= 6 (count (mem/all-facts m))) "…out of six remembered")))

(deftest an-unknown-or-non-notebook-scope-is-an-error-not-an-empty-answer
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    (is (= {:error "not a notebook: space:typo"} (srv/memory-request st m "chips" "space:typo")))
    (is (= {:error "not a notebook: find:semis-1"} (srv/memory-request st m "chips" "find:semis-1"))
        "a doc is not a notebook; answering [] would read as 'nothing remembered here'")))

(deftest without-a-space-the-response-is-exactly-what-it-was
  (let [st (lineage-fixture)
        m  (lineage-memory)
        {:keys [env file]} (embed-env {})
        ;; :score is dropped before comparing because it decays against
        ;; System/currentTimeMillis — two calls a millisecond apart differ in
        ;; the 8th decimal place and always did. Everything else, including the
        ;; ORDER of the facts, must match exactly.
        norm #(update % :facts (fn [fs] (mapv (fn [f] (dissoc f :score)) fs)))]
    (with-redefs [embed/env env embed/from-file file]
      (doseq [q [nil "chips"], space [nil ""]]
        (is (= (norm (srv/memory-payload m q)) (norm (srv/memory-request st m q space)))
            (str "q=" (pr-str q) " space=" (pr-str space) ": same map, key for key"))
        (is (= (keys (srv/memory-payload m q)) (keys (srv/memory-request st m q space))))
        (is (nil? (:scope (srv/memory-request st m q space)))
            "and no new key appears on the unscoped response"))))
  ;; and recall is still asked for exactly the opts it was asked for before
  (let [seen (atom [])]
    (srv/memory-request (sub/fresh-store) (recall-spy seen) "chips" nil)
    (is (= [["chips" {:k 20 :semantic? true}]] @seen)
        "no :filter key at all when nothing asked for a scope")))

(deftest lineage-sources-names-notebooks-and-their-cells
  (let [st (lineage-fixture)]
    (is (= #{"space:semis" "find:semis-1" "space:kid" "find:kid-1" "space:gk" "find:gk-1"}
           (srv/lineage-sources st "space:semis"))
        "the notebook and its cells, then the same for each descendant — a fact's :source names both")
    (is (= #{"space:peer" "find:semis-1"} (srv/lineage-sources st "space:peer")))))

(deftest a-merge-cycle-terminates
  ;; :merged-from is data, and nothing in the substrate forbids two notebooks
  ;; naming each other. The walk must stop rather than hang the request.
  (let [st (sub/fresh-store)]
    (put-space! st "space:a" "A" "doc:a" "a" {:merged-from ["space:b"]})
    (put-space! st "space:b" "B" "doc:b" "b" {:merged-from ["space:a"]})
    (is (= #{"space:a" "doc:a" "space:b" "doc:b"} (srv/lineage-sources st "space:a")))))

;; ============================================================================
;; what the AGENT is told it remembers — the same lineage scope, on ✦ Ask,
;; ✎ Draft, 🔍 Research and ✧ Suggest
;; ============================================================================

(defn- remembered
  "`remembered-context` with `m` standing in for the server's memory singleton.

   The stand-in goes in by rebinding the `mem/memory` DELAY — swapping the
   receiver — and never by redefining `mold/recall`. `recall` is a protocol
   method: the compiler turns the call into a cached callsite straight to the
   implementing class, so a `with-redefs` of it is silently ignored and the test
   passes green while observing nothing. `mem/memory` is an ordinary var deref,
   so rebinding it is seen, and the real `data/memory.edn` delay is never forced.

   No embedder is configured, so `:semantic? true` degrades to lexical and this
   opens no socket."
  [st m prompt space]
  (let [{:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file
                  mem/memory (delay m)]
      (#'srv/remembered-context st prompt space))))

(deftest the-agent-remembers-within-this-notebook-and-its-lineage
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; a fact learned in an unrelated notebook is not offered here
    (let [ctx (remembered st m "chips" "space:other")]
      (is (str/includes? ctx "chips are unrelated here.")
          "what this notebook itself learned is still offered")
      (is (not (str/includes? ctx "chips are fabricated in Taiwan."))
          "a fact from an unrelated notebook must not reach the agent's instructions")
      (is (not (str/includes? ctx "chips need EUV lithography."))))
    ;; the hub is offered what its deep dives found, transitively
    (let [ctx (remembered st m "chips" "space:semis")]
      (is (str/includes? ctx "chips need EUV lithography.") "the child's finding")
      (is (str/includes? ctx "chips shrink with each node.") "and the child's child's")
      (is (not (str/includes? ctx "chips are unrelated here."))))))

(deftest the-scope-runs-DOWN-the-lineage-and-not-up
  ;; NOTE FOR THE READER OF THE PLAN: the plan's Step 2 says "lineage runs both
  ;; ways — a fact recorded in the hub is offered to the child". It does not,
  ;; and it must not. `lineage-sources` deliberately refuses to follow
  ;; `spawned-by` upward (see its docstring), because the parent's other
  ;; children hang off that same edge and one hop up is every sibling. The
  ;; plan's own Step 6 names the upward walk as the sabotage that must break a
  ;; test — this is that test.
  (let [st (lineage-fixture)
        m  (lineage-memory)
        ctx (remembered st m "chips" "space:kid")]
    (is (str/includes? ctx "chips need EUV lithography.") "its own")
    (is (str/includes? ctx "chips shrink with each node.") "and its descendants'")
    (is (not (str/includes? ctx "chips are fabricated in Taiwan."))
        "but not its parent's — walking up would reach every sibling through the shared parent")
    (is (not (str/includes? ctx "chips are unrelated here."))
        "and certainly not a sibling's, which is what walking up would deliver")))

(deftest a-brand-new-notebook-brings-no-remembered-context
  ;; a normal state, not an error: nothing has been learned here yet, so the
  ;; system prompt simply has no REMEMBERED block
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    (sub/commit! st {:op :put :id "space:new"
                     :value {:id "space:new" :kind :space :title "New"
                             :value {:intent "just made" :cells []}}})
    (is (nil? (remembered st m "chips" "space:new"))
        "no facts in scope → nil, and the caller's `str` adds nothing")
    (is (seq (mem/all-facts m)) "…while memory itself is full of matching facts")))

(deftest with-no-notebook-at-all-memory-stays-global
  ;; `ask!` falls back to the whole workspace for its DOCUMENT context when
  ;; `space` is nil; memory matches that rule rather than inventing a second one
  (let [st (lineage-fixture)
        m  (lineage-memory)
        ctx (remembered st m "chips" nil)]
    (is (str/includes? ctx "chips are fabricated in Taiwan."))
    (is (str/includes? ctx "chips are unrelated here.")
        "with no notebook to scope to, an unrelated notebook's fact IS eligible")
    (is (str/includes? ctx "chips seen by a peer notebook."))))

(deftest the-remembered-block-still-carries-its-citations
  (let [st (lineage-fixture)
        m  (lineage-memory)
        ctx (remembered st m "chips" "space:kid")]
    (is (str/starts-with? ctx "\n\nREMEMBERED (distilled from earlier work"))
    (is (str/includes? ctx "chips need EUV lithography. (⌾ find:kid-1)")
        "the ⌾ citation is the object the fact came from, unchanged")
    (is (str/includes? ctx "chips shrink with each node. (⌾ find:gk-1)"))))

(deftest all-four-agent-flows-recall-inside-the-lineage
  ;; ✦ Ask, ✎ Draft, 🔍 Research, ✧ Suggest — every call site, one spy.
  ;; The model is stubbed everywhere; nothing here opens a socket.
  ;;
  ;; A FRESH store per flow: ✎ Draft and 🔍 Research each append a cell to the
  ;; notebook, which legitimately widens the next call's scope. Reusing one
  ;; store would compare each flow against a stale expectation.
  (let [want {:k 6 :semantic? true
              :filter {:sources (srv/lineage-sources (lineage-fixture) "space:kid")}}
        opts (fn [f]
               (let [st   (lineage-fixture)
                     seen (atom [])]
                 (with-redefs [mem/memory (delay (recall-spy seen))
                               loci.server/distill! (fn [& _] nil)
                               agent/chat-tools (fn [& _] "drafted")
                               agent/propose-subtopics (fn [& _] [])]
                   (f st))
                 (mapv second @seen)))]
    (is (= [want] (opts #(srv/ask! % "chips" "space:kid"))) "✦ Ask")
    (is (= [want] (opts #(srv/delegate! % "space:kid"))) "✎ Draft")
    (is (= [want] (opts #(srv/research! % "space:kid" "chips"))) "🔍 Research")
    (is (= [want] (opts #(srv/suggest! % "space:kid"))) "✧ Suggest")
    ;; and with no notebook, ✦ Ask asks for no filter at all
    (is (= [{:k 6 :semantic? true}] (opts #(srv/ask! % "chips" nil)))
        "space nil → no :filter key, exactly as before this change")))
