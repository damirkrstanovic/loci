(ns loci.dlv-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-dir
  "A fresh LMDB directory, always removed — an env left open reserves its
   directory for the life of the JVM, so the cleanup cannot be optional."
  [[sym] & body]
  `(let [~sym (str (System/getProperty "java.io.tmpdir") "/loci-dlv-" (System/nanoTime))]
     (try ~@body (finally (rm-rf ~sym)))))

(defmacro with-store [[sym dir] & body]
  `(let [~sym (dlv/datalevin-store ~dir)]
     (try ~@body (finally (dlv/close! ~sym)))))

(deftest datalevin-store-commits-and-replays
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
      (is (= "world" (:value (sub/object s "a"))))
      (is (= 2 (count (sub/history s)))))
    ;; a brand-new store over the same directory replays to identical state
    (with-store [s2 dir]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a")))))))

(deftest datalevin-undo-pops-and-persists
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (is (= 1 (sub/undo! s)))
      (is (nil? (sub/object s "b"))))
    (with-store [s2 dir]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b"))))))

(deftest datalevin-as-of-and-frozen-at-work-unchanged
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "1'"})
      (is (= "1" (get-in (sub/as-of s 2) [:objects "a" :value])))
      (let [fz (sub/frozen-at s 2)]
        (is (= "1" (:value (sub/object fz "a"))))
        (is (= "B" (:title (sub/object fz "b"))))
        (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}}))))
      (is (= "1'" (:value (sub/object s "a")))))))

(deftest datalevin-normalizes-unreadable-keys
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "t"
                      :value {:id "t" :kind :table
                              :value [{(keyword "Key Proponent(s)") "Canon" :year 2026}]}})
      (is (= [{:key_proponent_s "Canon" :year 2026}] (:value (sub/object s "t")))))))

(deftest datalevin-tx-is-one-undoable-step
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "keep" :value {:id "keep" :kind :doc}})
      (is (= 2 (sub/commit! s {:op :tx :events [{:op :put :id "x" :value {:id "x" :kind :doc}}
                                                {:op :put :id "y" :value {:id "y" :kind :doc}}]})))
      (is (= #{"keep" "x" "y"} (set (keys (sub/objects s)))))
      ;; the touch index records BOTH ids the tx wrote, under its one event
      (is (= [2] (d/get-list (:kv s) "touched" "x" :string :long)))
      (is (= [2] (d/get-list (:kv s) "touched" "y" :string :long)))
      (is (= 1 (sub/undo! s)))                          ; one step undoes the whole tx
      (is (= #{"keep"} (set (keys (sub/objects s)))))
      (is (empty? (d/get-list (:kv s) "touched" "x" :string :long))))
    (with-store [s2 dir]
      (is (= #{"keep"} (set (keys (sub/objects s2))))))))

(deftest datalevin-state-tracks-the-log
  ;; the one invariant the incremental fold must never break: what `state`
  ;; returns is what re-folding the whole log would return.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :space}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
      (sub/commit! s {:op :tx :events [{:op :put :id "c" :value {:id "c" :kind :table}}
                                       {:op :delete :id "b"}]})
      (sub/commit! s {:op :delete :id "a"})
      (is (= (sub/state s) (sub/materialize (sub/history s))))
      (sub/undo! s)
      (is (= (sub/state s) (sub/materialize (sub/history s))))
      (sub/undo! s)
      (is (= (sub/state s) (sub/materialize (sub/history s))))
      (is (= #{"a" "b"} (set (keys (sub/objects s))))))))

(deftest datalevin-concurrent-commits-keep-log-and-state-coherent
  ;; the server commits from `future`s (start-job!) while the request thread
  ;; commits too. Unserialized, both the durable log and the in-RAM state lose
  ;; writes — this is the test that catches it.
  (with-dir [dir]
    (let [n 200]
      (with-store [s dir]
        (run! deref
              [(future (dotimes [i n]
                         (sub/commit! s {:op :put :id (str "a" i)
                                         :value {:id (str "a" i) :kind :doc}})))
               (future (dotimes [i n]
                         (sub/commit! s {:op :put :id (str "b" i)
                                         :value {:id (str "b" i) :kind :doc}})))])
        (is (= (* 2 n) (count (sub/history s))))
        (is (= (* 2 n) (count (sub/objects s))))
        (is (= (sub/state s) (sub/materialize (sub/history s)))))
      ;; and every one of them is durable
      (with-store [s2 dir]
        (is (= (* 2 n) (count (sub/history s2))))
        (is (= (sub/state s2) (sub/materialize (sub/history s2))))))))

(deftest datalevin-counts-are-an-honest-histogram
  ;; layer 1 stores the census; which kinds a given view counts is the view's
  ;; business, so the log keeps the whole histogram rather than one UI's total.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "s1" :value {:id "s1" :kind :space}})
      (sub/commit! s {:op :put :id "d1" :value {:id "d1" :kind :doc}})
      (sub/commit! s {:op :put :id "d2" :value {:id "d2" :kind :doc}})
      (is (= {:objects 1 :kinds {:space 1}} (d/get-value (:kv s) "counts" 1 :long)))
      (is (= {:objects 3 :kinds {:space 1 :doc 2}} (d/get-value (:kv s) "counts" 3 :long))))))

(deftest datalevin-view-is-never-torn
  ;; the log and the state are ONE atom, so a reader that takes one snapshot
  ;; cannot catch the pair mid-commit. Every sample has to satisfy the
  ;; invariant on its own — eventual consistency is not the claim.
  (with-dir [dir]
    (with-store [s dir]
      (let [n       400
            stop    (atom false)
            samples (atom 0)
            torn    (atom [])
            ;; ids are unique puts, so after k events state holds exactly k
            ;; objects — a cheap per-sample check that runs often enough to
            ;; actually catch a tear
            reader  (future
                      (while (not @stop)
                        (let [{:keys [log state]} (dlv/view s)]
                          (swap! samples inc)
                          (when-not (= (count log) (count (:objects state)))
                            (swap! torn conj [(count log) (count (:objects state))])))))]
        (dotimes [i n]
          (sub/commit! s {:op :put :id (str "o" i) :value {:id (str "o" i) :kind :doc}}))
        (reset! stop true)
        @reader
        (is (empty? @torn))
        (is (pos? @samples))
        ;; and the deep invariant on a final snapshot
        (let [{:keys [log state]} (dlv/view s)]
          (is (= state (sub/materialize log))))))))

(deftest datalevin-refuses-a-gapped-event-log
  ;; an event's key IS its position — that is what lets commit! derive the next
  ;; index from the count and undo! delete by it. A gap breaks that silently:
  ;; the next commit! would reuse a live key and overwrite a durable event.
  ;; commit!/undo! cannot produce one, but a bulk migration writing `events`
  ;; directly can, and `reload!` exists precisely for that caller.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc}})
      ;; something other than commit!/undo! lands an event at 4, leaving 3 empty
      (d/transact-kv (:kv s) [[:put "events" 4 {:op :put :id "d" :value {:id "d"} :ts 0} :long]])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not contiguous" (dlv/reload! s)))
      ;; and a refused reload leaves the store exactly as it was
      (is (= 2 (count (sub/history s))))
      (is (= #{"a" "b"} (set (keys (sub/objects s))))))
    ;; a cold open of the same directory refuses too, rather than handing back a
    ;; 3-event log whose next commit would overwrite the event at key 4
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not contiguous"
                          (dlv/datalevin-store dir)))))

(deftest view-serves-any-store-reload-refuses-politely
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc}})
      ;; ?at=N hands a FrozenStore to every reader (store-at), so `view` has to
      ;; serve one — time travel must not 500 because the store has no atom
      (let [fz (sub/frozen-at s 1)]
        (is (= 1 (count (:log (dlv/view fz)))))
        (is (= #{"a"} (set (keys (:objects (:state (dlv/view fz)))))))
        (is (= (:state (dlv/view fz)) (sub/materialize (:log (dlv/view fz)))))
        ;; reload! has no meaning without an env — it must say which store it
        ;; wanted, not throw an NPE from a nil deref
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DatalevinStore" (dlv/reload! fz))))
      ;; and it still serves the live store it was written for
      (is (= 2 (count (:log (dlv/view s))))))))

(deftest close-is-safe-on-a-store-with-no-env
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc}})
      ;; a FrozenStore is a legitimate Store a caller may hold — closing one
      ;; must be a no-op, not an IllegalArgumentException out of Datalevin
      (is (nil? (dlv/close! (sub/frozen-at s 1)))))))
