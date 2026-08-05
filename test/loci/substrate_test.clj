(ns loci.substrate-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

;; ---------------------------------------------------------------------------
;; temp locations that do not outlive the test
;;
;; Every path a test writes lives under ONE fresh directory, removed in a
;; `finally`. Left to itself this namespace leaked a directory per test into
;; java.io.tmpdir (200+ of them accumulated on one machine), and an LMDB env
;; that is never closed reserves its directory for the life of the JVM — so
;; neither the close nor the cleanup can be conditional on the test passing.
;; ---------------------------------------------------------------------------

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defn- tmproot []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime)))

(defmacro with-tmpfile
  "A fresh EDN log path under a fresh directory, always removed."
  [[sym] & body]
  `(let [root# (tmproot)
         ~sym  (str root# "/substrate.edn")]
     (try ~@body (finally (rm-rf root#)))))

(deftest persistent-store-replays
  (with-tmpfile [path]
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
      (is (= "world" (:value (sub/object s "a"))))
      ;; a brand-new store over the same file replays to identical state
      (let [s2 (sub/persistent-store path)]
        (is (= 2 (count (sub/history s2))))
        (is (= "world" (:value (sub/object s2 "a"))))
        (is (= (sub/state s) (sub/state s2)))))))

(deftest persistent-undo-truncates-file
  (with-tmpfile [path]
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (sub/undo! s)
      (is (nil? (sub/object s "b")))
      (let [s2 (sub/persistent-store path)]
        (is (= 1 (count (sub/history s2))))
        (is (nil? (sub/object s2 "b")))))))

(deftest append-after-reload-preserves-order
  (with-tmpfile [path]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    ;; a reloaded store appends after what it replayed
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (let [s2 (sub/persistent-store path)]
        (is (= [:put :put] (mapv :op (sub/history s2))))
        (is (= 1 (:value (sub/object s2 "a"))))
        (is (= 2 (:value (sub/object s2 "b"))))))))

(deftest frozen-store-is-a-read-only-window
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value "1'"})
    (let [fz (sub/frozen-at st 2)]
      ;; sees exactly the first 2 events
      (is (= "1" (:value (sub/object fz "a"))))          ; edit (event 3) not applied
      (is (= "B" (:title (sub/object fz "b"))))
      (is (= 2 (count (sub/history fz))))
      ;; the present is untouched
      (is (= "1'" (:value (sub/object st "a"))))
      ;; the past cannot be edited
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}})))
      (is (thrown? UnsupportedOperationException (sub/undo! fz)))
      ;; as-of within the window still works (nested time travel)
      (is (nil? (sub/object (sub/frozen-at fz 1) "b"))))))

(deftest corrupt-trailing-line-salvages-prefix
  (with-tmpfile [path]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    (spit path "{:op :put :id \"b\" :va" :append true)   ; simulate crash mid-append
    (let [s (sub/persistent-store path)]
      (is (= 1 (count (sub/history s))))
      (is (= 1 (:value (sub/object s "a")))))))

;; ---- log readability: an event that cannot be read back is a lost log ----
;; Found live: a research table arrived with a column named "Key Proponent(s)".
;; As a keyword it PRINTS fine and reads back as garbage, so one line poisoned
;; the file and every later event silently vanished on the next boot.

(deftest col-kw-makes-any-column-name-readable
  (is (= :key_proponent_s (sub/col-kw "Key Proponent(s)")))
  (is (= :maturity_status (sub/col-kw "Maturity / Status")))
  (is (= :how_it_works    (sub/col-kw (keyword "How It Works"))))
  (is (= :year            (sub/col-kw "year")))            ; already clean → untouched
  (is (= :table/rows      (sub/col-kw :table/rows)))       ; readable namespaced kw survives
  (is (= :col             (sub/col-kw "  ")))              ; nothing left → stable fallback
  (doseq [s ["Key Proponent(s)" "Maturity / Status" "%" "a b" "  "]]
    (let [k (sub/col-kw s)]
      (is (= k (edn/read-string (pr-str k))) (str "must round-trip: " s)))))

(deftest unreadable-keys-never-poison-the-log
  (with-tmpfile [path]
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "t"
                      :value {:id "t" :kind :table
                              :value [{(keyword "Key Proponent(s)") "Canon"
                                       (keyword "Maturity / Status") "R&D"
                                       :year 2026}]}})
      ;; normalized on the way in, so memory and disk cannot disagree
      (is (= [{:key_proponent_s "Canon" :maturity_status "R&D" :year 2026}]
             (:value (sub/object s "t"))))
      (let [s2 (sub/persistent-store path)]
        (is (= 1 (count (sub/history s2))))
        (is (= (sub/state s) (sub/state s2)))))))

(deftest wide-rows-commit-fine
  ;; a map with more than 8 keys re-prints in a different order (array-map →
  ;; hash-map); the readability guard must judge the VALUE, not the string,
  ;; or every table with 9+ columns becomes uncommittable.
  (with-tmpfile [path]
    (let [s   (sub/persistent-store path)
          row (into {} (map (fn [i] [(keyword (str "c" i)) i]) (range 12)))]
      (sub/commit! s {:op :put :id "t" :value {:id "t" :kind :table :value [row]}})
      (is (= row (first (:value (sub/object s "t")))))
      (is (= 1 (count (sub/history (sub/persistent-store path))))))))

(deftest bad-middle-line-is-skipped-not-swallowed
  (with-tmpfile [path]
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
      ;; the real shape: a keyword with a space in it leaves the map an odd number
      ;; of forms — "Map literal must contain an even number of forms"
      (spit path "{:op :put :id \"bad\" :value {:Key Proponent(s)}}\n" :append true)
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (let [out (java.io.StringWriter.)
            s2  (binding [*err* out] (sub/persistent-store path))]
        (is (= 2 (count (sub/history s2))))                  ; the tail still replays
        (is (= 1 (:value (sub/object s2 "a"))))
        (is (= 2 (:value (sub/object s2 "b"))))
        (is (re-find #"line 2" (str out)))))))               ; and it says so out loud

;; ---------------------------------------------------------------------------
;; parity — the same assertions against both durable stores
;;
;; The swap from the EDN log to Datalevin is only safe if the two behave the
;; same, and the honest way to know that is to run one set of assertions
;; against both rather than to write a separate suite per store and trust that
;; they say the same thing. Every `is` carries the flavour label, so a failure
;; names which implementation disagreed.
;; ---------------------------------------------------------------------------

(defn- flavours
  "Both durable Store implementations, as [label open-fn reopen-fn close-fn].
   `open-fn` takes no args and returns a fresh store under `root`;
   `reopen-fn` takes that store and returns a NEW store over the same data."
  [root]
  [["edn" (fn [] (sub/persistent-store (str root "/edn/substrate.edn")))
          (fn [s] (sub/persistent-store (:file s)))
          (fn [s] (dlv/close! s))]                     ; no env to close — a no-op
   ["dlv" (fn [] (dlv/datalevin-store (str root "/dlv")))
          (fn [s] (dlv/close! s) (dlv/datalevin-store (:dir s)))
          (fn [s] (dlv/close! s))]])

(defmacro with-flavours
  "Run `body` once per durable Store flavour, with `label`, `open`, `reopen`
   and `close` bound. Every store handed out is closed and the whole directory
   removed in a `finally`: a failing assertion must not leak an LMDB env, which
   would hold its directory — and its lock — for the life of the JVM."
  [[label open reopen close] & body]
  `(let [root#    (tmproot)
         !opened# (atom [])
         track#   (fn [s#] (swap! !opened# conj s#) s#)]
     (try
       (doseq [[~label open# reopen# close#] (flavours root#)]
         (let [~open   (fn [] (track# (open#)))
               ~reopen (fn [s#] (track# (reopen# s#)))
               ~close  close#]
           ~@body))
       (finally
         ;; close-kv is guarded on an already-closed env, so closing a store the
         ;; test already closed is free; a store it never reached is not.
         (run! (fn [s#] (try (dlv/close! s#) (catch Throwable _#))) @!opened#)
         (rm-rf root#)))))

(deftest both-stores-replay-identically
  (with-flavours [label open reopen close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
      (is (= "world" (:value (sub/object s "a"))) label)
      (let [s2 (reopen s)]
        (is (= 2 (count (sub/history s2))) label)
        (is (= "world" (:value (sub/object s2 "a"))) label)
        (close s2)))))

(deftest both-stores-undo-identically
  (with-flavours [label open reopen close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (is (= 1 (sub/undo! s)) label)
      (is (nil? (sub/object s "b")) label)
      (let [s2 (reopen s)]
        (is (= 1 (count (sub/history s2))) label)
        (is (nil? (sub/object s2 "b")) label)
        (close s2)))))

(deftest both-stores-time-travel-identically
  (with-flavours [label open _ close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value "1'"})
      (is (= "1" (get-in (sub/as-of s 2) [:objects "a" :value])) label)
      (let [fz (sub/frozen-at s 2)]
        (is (= "1" (:value (sub/object fz "a"))) label)
        (is (= 2 (count (sub/history fz))) label)
        (is (thrown? UnsupportedOperationException (sub/undo! fz)) label))
      (is (= "1'" (:value (sub/object s "a"))) label)
      (close s))))

(deftest both-stores-normalize-keys-identically
  (with-flavours [label open _ close]
    (let [s  (open)
          ev {:op :put :id "t"
              :value {:id "t" :kind :table
                      :value [{(keyword "Maturity / Status") "R&D" :year 2026}]}}]
      (sub/commit! s ev)
      (is (= [{:maturity_status "R&D" :year 2026}] (:value (sub/object s "t"))) label)
      (close s))))

(deftest both-stores-agree-on-a-tx
  (with-flavours [label open _ close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "s" :value {:id "s" :kind :space :value {:cells []}}})
      (sub/commit! s {:op :tx :events [{:op :put :id "d" :value {:id "d" :value 1}}
                                       {:op :assoc :id "s" :path [:value :cells]
                                        :value [{:ref "d"}]}]})
      (is (= 1 (:value (sub/object s "d"))) label)
      (is (= [{:ref "d"}] (get-in (sub/object s "s") [:value :cells])) label)
      (is (= 2 (count (sub/history s))) label)          ; a :tx is ONE event
      (is (= 1 (sub/undo! s)) label)
      (is (nil? (sub/object s "d")) label)              ; and undoes as one
      (is (= [] (get-in (sub/object s "s") [:value :cells])) label)
      (close s))))

(deftest both-stores-keep-state-equal-to-their-log
  ;; the invariant a cached fold can break and a re-fold cannot: what `state`
  ;; returns must be what re-folding the whole log returns. PersistentStore
  ;; satisfies it by construction (its `state` IS the fold); DatalevinStore
  ;; keeps the fold in RAM and updates it incrementally, which is exactly the
  ;; kind of bookkeeping that drifts — it caught a real bug there once. Every
  ;; op shape that can drift is here: :put, :assoc, :delete, a :tx that both
  ;; creates and deletes, and undo! walking back through the :tx.
  (with-flavours [label open _ close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
      (sub/commit! s {:op :put :id "b" :value {:id "b" :kind :space}})
      (sub/commit! s {:op :assoc :id "a" :path [:value] :value 2})
      (sub/commit! s {:op :tx :events [{:op :put :id "c" :value {:id "c" :kind :table}}
                                       {:op :delete :id "b"}]})
      (sub/commit! s {:op :delete :id "a"})
      (is (= (sub/state s) (sub/materialize (sub/history s))) (str label " after 5 events"))
      (is (= #{"c"} (set (keys (sub/objects s)))) label)
      (sub/undo! s)                                     ; back through the :delete
      (is (= (sub/state s) (sub/materialize (sub/history s))) (str label " after 1 undo"))
      (sub/undo! s)                                     ; back through the :tx
      (is (= (sub/state s) (sub/materialize (sub/history s))) (str label " after 2 undos"))
      (is (= #{"a" "b"} (set (keys (sub/objects s)))) label)
      (is (= 2 (:value (sub/object s "a"))) label)      ; the :assoc survived the undos
      (close s))))
