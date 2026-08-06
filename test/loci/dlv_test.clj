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

(deftest object-at-equals-a-full-prefix-fold
  ;; the lazy path must agree with the honest one, at EVERY point in history.
  ;; The shapes that can break it are all here: a plain :put, an :assoc, an
  ;; object born inside a :tx, a :delete (existed, then did not), a re-put after
  ;; that delete, a :delete nested inside a :tx, and an id that never existed.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :value 10}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
      (sub/commit! s {:op :tx :events [{:op :assoc :id "b" :path [:value] :value 11}
                                       {:op :put :id "c" :value {:id "c" :kind :doc :value 100}}]})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value 3})
      (sub/commit! s {:op :delete :id "b"})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :value 99}})
      (sub/commit! s {:op :tx :events [{:op :delete :id "c"}
                                       {:op :assoc :id "a" :path [:meta :tag] :value "x"}]})
      (doseq [n (range 0 10), id ["a" "b" "c" "never-existed"]]
        (is (= (get-in (sub/as-of s n) [:objects id]) (dlv/object-at s id n))
            (str "object-at disagreed for " id " at event " n)))
      ;; and pin the answers, so the agreement above cannot be agreement on nil
      (is (= 3 (:value (dlv/object-at s "a" 8))))
      (is (= "x" (get-in (dlv/object-at s "a" 8) [:meta :tag])))
      (is (= 100 (:value (dlv/object-at s "c" 4))))          ; born inside a :tx
      (is (= 11 (:value (dlv/object-at s "b" 4))))
      (is (nil? (dlv/object-at s "b" 6)))                    ; deleted → gone, not stale
      (is (= 99 (:value (dlv/object-at s "b" 7))))           ; and back again
      (is (nil? (dlv/object-at s "c" 8)))                    ; deleted inside a :tx
      (is (nil? (dlv/object-at s "never-existed" 8)))
      (is (nil? (dlv/object-at s "a" 0))))))                 ; before the first event

(deftest counts-match-a-real-count-at-every-event
  ;; layer 1 stores an honest histogram — consumers decide which kinds they
  ;; count. state-payload excludes #{:space :viewspec :applet :fn}; the ⏱
  ;; header can derive that from :kinds without the substrate knowing about it.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "space:x" :value {:id "space:x" :kind :space :value {}}})
      (sub/commit! s {:op :put :id "d" :value {:id "d" :kind :doc :value 1}})
      (sub/commit! s {:op :put :id "space:y" :value {:id "space:y" :kind :space :value {}}})
      (doseq [n (range 1 4)]
        (let [objs (vals (:objects (sub/as-of s n)))]
          (is (= {:objects (count objs)
                  :kinds   (frequencies (map :kind objs))}
                 (dlv/counts-at s n))
              (str "counts disagreed at event " n))))
      (is (= {:space 2 :doc 1} (:kinds (dlv/counts-at s 3))))
      ;; an event that does not exist has no count — nil, not a zero census
      (is (nil? (dlv/counts-at s 4)))
      ;; and undo takes its count back out with it
      (sub/undo! s)
      (is (nil? (dlv/counts-at s 3)))
      (is (= {:objects 2 :kinds {:space 1 :doc 1}} (dlv/counts-at s 2))))))

(deftest rebuild-indices-restores-a-wiped-touch-index
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
      (sub/commit! s {:op :tx :events [{:op :put :id "b" :value {:id "b" :kind :space}}
                                       {:op :delete :id "a"}]})
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 3}})
      (let [touched (fn [id] (vec (d/get-list (:kv s) "touched" id :string :long)))
            counts  (fn [] (mapv #(dlv/counts-at s %) (range 1 5)))
            before  {:a (touched "a") :b (touched "b") :counts (counts)}]
        (dlv/clear-indices! s)
        ;; a cleared index does not answer nil. nil is a WRONG answer here —
        ;; "a" did exist at event 4 — and wrong is worse than absent
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"torn index" (dlv/object-at s "a" 4)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"torn index" (dlv/counts-at s 4)))
        (dlv/rebuild-indices! s)
        (is (= 3 (:value (dlv/object-at s "a" 4))))          ; and it comes back from the log
        (is (nil? (dlv/object-at s "a" 3)))                  ; deleted inside the :tx
        (is (= 1 (:value (dlv/object-at s "a" 1))))
        ;; a rebuild reproduces exactly what commit! wrote — no duplicates, no drift
        (is (= before {:a (touched "a") :b (touched "b") :counts (counts)}))
        ;; and it is derived work only: the log and the state are untouched
        (is (= 4 (count (sub/history s))))
        (is (= (sub/state s) (sub/materialize (sub/history s))))
        ;; a rebuild is a rebuild, not a merge — a stale entry left by a log
        ;; that has since been rewritten must not survive it
        (d/transact-kv (:kv s) [[:put-list "touched" "ghost" [1 2] :string :long]])
        (dlv/rebuild-indices! s)
        (is (empty? (touched "ghost")))
        (is (= before {:a (touched "a") :b (touched "b") :counts (counts)}))))))

(deftest a-half-built-index-refuses-rather-than-answers
  ;; every transaction a rebuild writes is atomic; the SEQUENCE of them is not.
  ;; A rebuild that dies partway leaves an index that answers as of the last
  ;; event that made it in — confidently, and wrongly. That is the case the
  ;; mark exists for; a fully cleared index is the easy one.
  (with-dir [dir]
    (with-store [s dir]
      (doseq [v [1 2 3 4]]
        (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value v}}))
      (is (= 4 (:value (dlv/object-at s "a" 4))))
      (let [real sub/apply-event]
        (with-redefs [sub/apply-event (fn [state ev]
                                        (if (= 3 (get-in ev [:value :value]))
                                          (throw (ex-info "injected mid-rebuild failure" {}))
                                          (real state ev)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected" (dlv/rebuild-indices! s)))))
      ;; the tear is real — events 3 and 4 never made it back in
      (is (= [1 2] (vec (d/get-list (:kv s) "touched" "a" :string :long))))
      ;; so the stale answer is refused rather than given: {:value 2}, not 4
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"torn index" (dlv/object-at s "a" 4)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"torn index" (dlv/counts-at s 4)))
      ;; committing on top of a torn index does not quietly heal it — but the
      ;; log is the source of truth, so the write itself is never refused
      (is (= 5 (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"torn index" (dlv/object-at s "a" 4)))
      ;; a rebuild that finishes is what heals it
      (dlv/rebuild-indices! s)
      (is (= (get-in (sub/as-of s 4) [:objects "a"]) (dlv/object-at s "a" 4)))
      (is (= 4 (:value (dlv/object-at s "a" 4)))))))

(deftest an-index-from-before-the-mark-is-still-believed
  ;; a store written before `meta` existed has an index commit! maintained
  ;; event by event, and it is sound. ABSENCE of the mark therefore has to read
  ;; as trustworthy — the other polarity would make the first open after an
  ;; upgrade refuse every ?at= read on a perfectly good index.
  (with-dir [dir]
    ;; an env with exactly the three dbis Task 2 opened, and nothing else
    (let [kv (d/open-kv dir)]
      (d/open-dbi kv "events")
      (d/open-dbi kv "counts")
      (d/open-list-dbi kv "touched")
      (d/transact-kv kv [[:put "events" 1 {:op :put :id "a" :value {:id "a" :kind :doc :value 1} :ts 0} :long]
                         [:put "counts" 1 {:objects 1 :kinds {:doc 1}} :long]
                         [:put-list "touched" "a" [1] :string :long]])
      (d/close-kv kv))
    (with-store [s dir]
      (is (= 1 (count (sub/history s))))
      (is (= 1 (:value (dlv/object-at s "a" 1))))
      (is (= {:objects 1 :kinds {:doc 1}} (dlv/counts-at s 1))))))

(deftest index-readers-refuse-a-store-with-no-env
  ;; store-at hands a FrozenStore to every ?at= reader and object-at exists for
  ;; exactly that path, so it has to refuse the way reload! does — naming the
  ;; store it needs, not an IllegalArgumentException about dispatching on nil.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc}})
      (let [fz (sub/frozen-at s 1)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DatalevinStore" (dlv/object-at fz "a" 1)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DatalevinStore" (dlv/counts-at fz 1)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DatalevinStore" (dlv/clear-indices! fz)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DatalevinStore" (dlv/rebuild-indices! fz))))
      ;; and the live store is untouched by the refusals
      (is (= "a" (:id (dlv/object-at s "a" 1)))))))

(deftest rebuild-replays-the-durable-log-not-the-ram-one
  ;; the caller this exists for is the bulk writer that put events into
  ;; `events` by some other route than commit! — a migration, an import. If a
  ;; rebuild replayed RAM instead, it would build an index that stops where
  ;; that caller's `reload!` was forgotten, and be silently short.
  (with-dir [dir]
    (with-store [s dir]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
      ;; a bulk writer appends straight to `events`, contiguously, and does not
      ;; touch RAM — so `history` still says one event
      (d/transact-kv (:kv s)
                     [[:put "events" 2 {:op :assoc :id "a" :path [:value] :value 2 :ts 0} :long]
                      [:put "events" 3 {:op :put :id "z" :value {:id "z" :kind :doc} :ts 0} :long]])
      (is (= 1 (count (sub/history s))))
      (dlv/rebuild-indices! s)
      ;; the index covers what is DURABLE, without anyone remembering reload!
      (is (= 2 (:value (dlv/object-at s "a" 3))))
      (is (= "z" (:id (dlv/object-at s "z" 3))))
      (is (= {:objects 2 :kinds {:doc 2}} (dlv/counts-at s 3)))
      ;; and reload! then makes RAM agree with the index it already had
      (dlv/reload! s)
      (is (= 3 (count (sub/history s))))
      (is (= (get-in (sub/as-of s 3) [:objects "a"]) (dlv/object-at s "a" 3))))))

(deftest rebuild-holds-the-lock-commits-take
  ;; a rebuild replays a snapshot of the log. An undo! landing inside that
  ;; window deletes an event the replay has already read, and the replay then
  ;; writes its index entries back — leaving an index that describes a log
  ;; which no longer exists. The lock is the only thing preventing it.
  (with-dir [dir]
    (with-store [s dir]
      (let [n 600]
        (dotimes [i n]
          (sub/commit! s {:op :put :id (str "o" i) :value {:id (str "o" i) :kind :doc}}))
        (let [undoer (future (dotimes [_ 100] (sub/undo! s)))]
          (dlv/rebuild-indices! s)
          @undoer)
        (let [len (count (sub/history s))]
          (is (= 500 len))
          ;; every event that exists has its count ...
          (is (every? #(some? (dlv/counts-at s %)) (range 1 (inc len))))
          ;; ... and no event that does not exist has one
          (is (empty? (remove #(nil? (dlv/counts-at s %)) (range (inc len) (inc n)))))
          ;; nor does any touch entry point past the end of the log
          (is (empty? (for [i (range n)
                            :when (some #(> % len)
                                        (d/get-list (:kv s) "touched" (str "o" i) :string :long))]
                        i)))
          (is (= (sub/state s) (sub/materialize (sub/history s)))))))))
