(ns loci.substrate-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

;; ---------------------------------------------------------------------------
;; :append — the op whose effect is computed at APPLY time
;;
;; That is the whole point (the read-modify-write lands inside commit!'s lock),
;; and it is also the risk: an op whose effect depended on anything but the
;; event and the prior state would make a replay disagree with the live fold,
;; and `as-of` / `object-at` would answer a wrong past rather than an error.
;; ---------------------------------------------------------------------------

(deftest append-replays-to-the-cells-it-showed-live
  (with-flavours [label open reopen close]
    (let [s    (open)
          seen (atom [])
          look #(get-in (sub/object s "s") [:value :cells])]
      (sub/commit! s {:op :put :id "s" :value {:id "s" :kind :space :value {:cells []}}})
      (swap! seen conj (look))
      (doseq [r ["a" "b" "c"]]
        (sub/commit! s {:op :append :id "s" :path [:value :cells] :value {:ref r}})
        (swap! seen conj (look)))
      (is (= [[] [{:ref "a"}] [{:ref "a"} {:ref "b"}] [{:ref "a"} {:ref "b"} {:ref "c"}]]
             @seen)
          label)
      ;; every prefix, re-folded now, equals what the store showed at the time
      (doseq [n (range 1 5)]
        (is (= (@seen (dec n)) (get-in (sub/as-of s n) [:objects "s" :value :cells]))
            (str label " as-of " n)))
      (is (= (sub/state s) (sub/materialize (sub/history s))) label)
      ;; and it survives the write path: pr-str/read on the EDN log, nippy on
      ;; Datalevin. A store reopened from disk folds to the same present.
      (let [s2 (reopen s)]
        (is (= :append (:op (nth (sub/history s2) 1))) label)
        (is (= [{:ref "a"} {:ref "b"} {:ref "c"}] (get-in (sub/object s2 "s") [:value :cells]))
            label)
        (is (= (sub/state s) (sub/state s2)) label)
        (close s2)))))

(deftest append-seeds-an-absent-path-once
  ;; :seed exists for the legacy notebook (:members, no :cells): it normalizes
  ;; the old shape without a separate racing write. Used ONLY when the path is
  ;; absent, so a second writer carrying the same seed just appends.
  (with-flavours [label open _ close]
    (let [s (open)]
      (sub/commit! s {:op :put :id "s" :value {:id "s" :kind :space :value {:members ["a"]}}})
      (sub/commit! s {:op :append :id "s" :path [:value :cells]
                      :value {:ref "b"} :seed [{:ref "a"}]})
      (sub/commit! s {:op :append :id "s" :path [:value :cells]
                      :value {:ref "c"} :seed [{:ref "a"}]})
      (is (= [{:ref "a"} {:ref "b"} {:ref "c"}] (get-in (sub/object s "s") [:value :cells])) label)
      ;; undoing the seeding append takes :cells away again, back to :members
      (sub/undo! s) (sub/undo! s)
      (is (nil? (get-in (sub/object s "s") [:value :cells])) label)
      (is (= ["a"] (get-in (sub/object s "s") [:value :members])) label)
      (close s))))

(deftest concurrent-appends-survive-both-durable-stores
  ;; the durable half of the race: 24 threads released together, then the store
  ;; reopened from disk. Order is asserted only as a set — PersistentStore
  ;; writes its file outside the swap!, so under concurrency its file order and
  ;; its RAM order need not agree. What must not vary is that all 24 are there.
  (with-flavours [label open reopen close]
    (let [s (open)
          n 24]
      (sub/commit! s {:op :put :id "s" :value {:id "s" :kind :space :value {:cells []}}})
      (let [latch (java.util.concurrent.CountDownLatch. 1)
            ths   (mapv (fn [i] (Thread. (fn [] (.await latch)
                                           (sub/commit! s {:op :append :id "s"
                                                           :path [:value :cells]
                                                           :value {:ref (str "o" i)}}))))
                        (range n))]
        (doseq [t ths] (.start t))
        (.countDown latch)
        (doseq [t ths] (.join t)))
      (let [refs (mapv :ref (get-in (sub/object s "s") [:value :cells]))]
        (is (= n (count refs)) (str label " — every concurrent append survives"))
        (is (= n (count (set refs))) (str label " — none duplicated")))
      (let [s2   (reopen s)
            refs (mapv :ref (get-in (sub/object s2 "s") [:value :cells]))]
        (is (= (set (map #(str "o" %) (range n))) (set refs))
            (str label " — and all of them are durable"))
        (close s2)))))

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

;; ---------------------------------------------------------------------------
;; the data directory has to be writable, and saying so has to be worth reading
;;
;; d439bc6 made the container run as uid 10001. Docker applies the image's /data
;; ownership only to a FRESH volume, so every volume created before that kept
;; its root-owned files and every existing deployment died on
;; `Fail to open database: "Permission denied"` — a Datalevin trace naming
;; neither the path, nor the uid, nor the one-line chown that fixes it.
;;
;; THE TRAP IN TESTING THIS: root ignores permission bits, so under `sudo` (or
;; in a root container, or on a filesystem that does not enforce them) a
;; `chmod 000` directory is still writable and every negative test below passes
;; while asserting nothing — a test that certifies. `permissions-enforced?`
;; probes for that and the tests skip out loud instead.
;; ---------------------------------------------------------------------------

(defmacro with-tmpdir
  "A fresh empty directory, always removed — modes restored first, since a dir
   left at 000 cannot be listed and so cannot be cleaned up."
  [[sym] & body]
  `(let [root# (io/file (tmproot))
         ~sym  root#]
     (.mkdirs root#)
     (try ~@body
          (finally (chmod-r! root# "rwx------")
                   (rm-rf root#)))))

(defn- chmod!
  [f perms]
  (java.nio.file.Files/setPosixFilePermissions
   (.toPath (io/file f))
   (java.nio.file.attribute.PosixFilePermissions/fromString perms)))

(defn- chmod-r! [f perms]
  (let [f (io/file f)]
    (when (.exists f)
      (chmod! f perms)
      (when (.isDirectory f) (run! #(chmod-r! % perms) (.listFiles f))))))

(defn- permissions-enforced?
  "Can this process be kept out of a `chmod 000` directory at all? Root cannot,
   and neither can a process on a filesystem that ignores the bits. Probed by
   attempting a real write rather than by comparing `user.name` to \"root\",
   because the name is not the only way to hold the privilege."
  []
  (let [d (io/file (tmproot))]
    (try
      (.mkdirs d)
      (chmod! d "---------")
      (try (.createNewFile (io/file d "probe")) false
           (catch Exception _ true))
      (finally (chmod! d "rwx------") (rm-rf d)))))

(defn- write-probe-ok
  "nil when a file can be created here — the test's own copy of the check, so
   the assertion that /data is writable does not lean on the code under test."
  [dir]
  (let [f (io/file dir (str "probe-" (System/nanoTime)))]
    (try (.createNewFile f) (.delete f) nil
         (catch Exception e (.getMessage e)))))

(defn- skipped! [what]
  (println (str "  SKIPPED " what
                " — permission bits are not enforced for this process"
                " (running as root?), so the assertion would hold vacuously")))

(deftest writable-data-dir-is-accepted
  (with-tmpdir [d]
    (is (nil? (sub/data-dir-problem (str d)))
        "an existing, writable directory is fine")))

(deftest missing-data-dir-under-a-writable-parent-is-accepted
  ;; loci creates its data directory on first run; refusing here would refuse
  ;; every first run. Nested, because io/make-parents creates the whole chain.
  (with-tmpdir [d]
    (is (nil? (sub/data-dir-problem (str d "/not-yet/either"))))))

(deftest unwritable-data-dir-is-refused-and-says-why
  (with-tmpdir [d]
    (let [dir (io/file d "substrate-home")]
      (.mkdirs dir)
      (chmod! dir "---------")
      (if-not (permissions-enforced?)
        (skipped! "unwritable-data-dir-is-refused-and-says-why")
        (let [msg (sub/data-dir-problem (str dir))]
          (is (string? msg) "an unwritable directory is refused")
          (is (str/includes? (str msg) (str dir)) "the message names the path")
          (is (str/includes? (str msg) "uid") "the message names the effective uid")
          (is (str/includes? (str msg) "chown") "the message carries the fix"))))))

(deftest missing-data-dir-under-an-unwritable-parent-is-refused
  (with-tmpdir [d]
    (let [parent (io/file d "locked")]
      (.mkdirs parent)
      (chmod! parent "---------")
      (if-not (permissions-enforced?)
        (skipped! "missing-data-dir-under-an-unwritable-parent-is-refused")
        (let [msg (sub/data-dir-problem (str parent "/substrate-home"))]
          (is (string? msg) "a directory we could not create is refused")
          (is (str/includes? (str msg) (str parent "/substrate-home"))
              "the message names the path that was asked for"))))))

(deftest a-file-where-the-data-dir-should-be-is-refused
  (with-tmpdir [d]
    (let [f (io/file d "substrate-home")]
      (spit f "not a directory")
      (let [msg (sub/data-dir-problem (str f))]
        (is (string? msg))
        (is (str/includes? (str msg) "not a directory")
            "the message says what is actually wrong")
        (is (str/includes? (str msg) (str f)))))))

(deftest the-writability-check-is-an-attempted-write
  ;; the reason the implementation must not use java.io.File/.canWrite: it reads
  ;; permission bits, which are not the only thing that decides. A read-only
  ;; bind mount is the case that bit us in a container; here the cheap proof is
  ;; that a directory whose bits say "writable" to root still refuses a write —
  ;; so the two answers can differ, and only one of them is the truth.
  (with-tmpdir [d]
    (let [dir (io/file d "substrate-home")]
      (.mkdirs dir)
      (chmod! dir "---------")
      (if-not (permissions-enforced?)
        (skipped! "the-writability-check-is-an-attempted-write")
        (do (is (false? (.canWrite dir)) "bits and reality agree here")
            (is (some? (sub/data-dir-problem (str dir)))))))))

(deftest a-writable-data-dir-with-an-unwritable-substrate-inside-is-refused
  ;; the half-fixed volume: `chown 10001 /data` without -R leaves the store
  ;; directory root-owned. /data probes clean, Datalevin then dies exactly the
  ;; way this check exists to prevent — so the store directory is probed too.
  (with-tmpdir [d]
    (let [dir   (io/file d "substrate-home")
          store (io/file dir "substrate")]
      (.mkdirs store)
      (chmod! store "---------")
      (if-not (permissions-enforced?)
        (skipped! "a-writable-data-dir-with-an-unwritable-substrate-inside-is-refused")
        (do (is (nil? (write-probe-ok dir)) "the data dir itself is writable")
            (let [msg (sub/data-dir-problem (str dir))]
              (is (string? msg) "and it is still refused")
              (is (str/includes? (str msg) (str store))
                  "the message names the directory that actually failed")))))))

(deftest a-missing-data-dir-is-not-judged-by-a-sibling-substrate-dir
  ;; the store probe applies to <data-dir>/substrate, not <ancestor>/substrate.
  ;; With the data dir absent the check walks up to the parent — and an
  ;; unrelated, unwritable `substrate` sitting in that parent must not be
  ;; mistaken for ours.
  (with-tmpdir [d]
    (let [decoy (io/file d "substrate")]
      (.mkdirs decoy)
      (chmod! decoy "---------")
      (is (nil? (sub/data-dir-problem (str d "/loci-data")))
          "a first run under a writable parent still passes"))))
