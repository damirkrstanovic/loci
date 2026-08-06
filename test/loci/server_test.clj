(ns loci.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.agent :as agent]
            [loci.memory :as mem]
            [loci.mold :as mold]
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

(deftest deep-dive-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/deep-dive-start! st "tbl:t")))     ; not a notebook
    (is (:error (srv/deep-dive-start! st "nope")))      ; missing
    (with-redefs [srv/deep-dive! (fn [_ _] {:spawned ["space:dd-1"]})]
      (let [{:keys [job error]} (srv/deep-dive-start! st "space:h")]
        (is (nil? error))
        (is (= ["space:dd-1"] (get-in (await-job job) [:result :spawned])))))))

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
  (let [st (tagged-store)
        before (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
    (is (= (inc before) (count (sub/history st))) "exactly one event")
    (is (= ["semiconductors"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags]))))
    (sub/undo! st)
    (is (nil? (get-in (sub/object st "space:t") [:value :tags])) "undo restores the previous tags")))

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

(deftest eight-tags-receive-eight-distinct-inks
  ;; the test that fails the moment assignment is reduced to a bare hash:
  ;; with eight inks and eight names a hash collides more often than not
  (let [st (tagged-store)]
    (doseq [t ["a" "b" "c" "d" "e" "f" "g" "h"]]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)]
      (is (= 8 (count reg)) "every name is in the registry")
      (is (= 8 (count (set (vals reg)))) "and no two share an ink"))))

(deftest real-tag-names-still-receive-distinct-inks
  ;; the test above does NOT bite: "a".."h" are consecutive char codes, so a
  ;; bare hash mod 8 is a perfect permutation for exactly that input and eight
  ;; single letters come out distinct even with no least-used rule at all.
  ;; Real subjects are not consecutive — these eight land on five slots — so
  ;; this is the one that fails the moment assignment becomes a bare hash.
  (let [st (tagged-store)]
    (doseq [t ["semiconductors" "world data" "chokepoints" "supply chain"
               "policy" "energy" "logistics" "materials"]]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)]
      (is (= 8 (count reg)))
      (is (= 8 (count (set (vals reg)))) "eight subjects, eight inks, no collision"))))

(deftest the-ninth-tag-reuses-a-least-used-ink
  (let [st (tagged-store)]
    (doseq [t ["a" "b" "c" "d" "e" "f" "g" "h" "i"]]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)
          f   (frequencies (vals reg))]
      (is (= 9 (count reg)))
      (is (every? (set srv/tag-inks) (vals reg)) "only palette inks are ever assigned")
      (is (= 2 (apply max (vals f))) "exactly one ink is doubled")
      (is (= 7 (count (filter #(= 1 %) (vals f)))) "the other seven are untouched"))))

(deftest several-new-tags-in-one-call-get-different-inks
  ;; assignment must accumulate within the call, or both new names take the
  ;; same "least-used" ink because neither is in the registry yet
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "x" :by "you"} {:tag "y" :by "you"}])
    (let [reg (srv/tag-colors st)]
      (is (= 2 (count reg)))
      (is (= 2 (count (set (vals reg))))))))

(deftest assignment-is-deterministic
  (let [ink (fn [] (let [st (tagged-store)]
                     (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
                     (get (srv/tag-colors st) "semiconductors")))]
    (is (= (ink) (ink)) "the same name always gets the same ink from an empty registry")))
