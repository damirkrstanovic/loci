(ns loci.migrate-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [loci.dlv :as dlv]
            [loci.migrate :as mig]
            [loci.substrate :as sub]))

;; ---------------------------------------------------------------------------
;; temp locations that do not outlive the test
;;
;; The source log and the target store share ONE fresh directory, removed in a
;; `finally`: an LMDB env that is never closed reserves its directory for the
;; life of the JVM, so neither the close nor the cleanup can be conditional on
;; the test passing. Same shape as dlv-test/with-dir and substrate-test/
;; with-tmpfile, which this namespace needs both of at once.
;; ---------------------------------------------------------------------------

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-tmp
  "A fresh EDN log path and a fresh LMDB directory, both always removed."
  [[path dir] & body]
  `(let [root# (str (System/getProperty "java.io.tmpdir") "/loci-mig-" (System/nanoTime))
         ~path (str root# "/substrate.edn")
         ~dir  (str root# "/substrate")]
     (try ~@body (finally (rm-rf root#)))))

(defmacro with-store [[sym dir] & body]
  `(let [~sym (dlv/datalevin-store ~dir)]
     (try ~@body (finally (dlv/close! ~sym)))))

(defn- seed!
  "Three events with every shape the fold cares about: a plain :put, a :tx that
   writes two objects, and an :assoc into an existing one."
  [path]
  (let [src (sub/persistent-store path)]
    (sub/commit! src {:op :put :id "space:x" :value {:id "space:x" :kind :space :value {:cells []}}})
    (sub/commit! src {:op :tx :events [{:op :put :id "d" :value {:id "d" :kind :doc :value "hi"}}
                                       {:op :assoc :id "space:x" :path [:value :cells]
                                        :value [{:ref "d"}]}]})
    (sub/commit! src {:op :assoc :id "d" :path [:value] :value "hi again"})
    src))

(deftest migration-preserves-state-and-history
  (with-tmp [path dir]
    (let [src    (seed! path)
          report (mig/edn->datalevin! path dir)]
      (with-store [dst dir]
        (is (:ok? report))
        (is (= 3 (:events report)))
        (is (= 0 (:skipped report)))                           ; nothing was lost on the way
        (is (= (sub/state src) (sub/state dst)))               ; the total check
        (is (= (sub/history src) (sub/history dst)))           ; events copied verbatim
        (is (= (sub/as-of src 2) (sub/as-of dst 2)))           ; and the past matches
        (is (= (:value (dlv/object-at dst "d" 2)) "hi"))       ; indices built during import
        ;; the ⏱ header reads too — a migrated store whose index mark was left
        ;; dirty answers every ?at= read with an exception, not a page
        (is (= {:objects 2 :kinds {:space 1 :doc 1}} (dlv/counts-at dst 3)))
        ;; and the timestamps are the original ones: commit! would have
        ;; re-stamped every event with the moment of the migration
        (is (= (mapv :ts (sub/history src)) (mapv :ts (sub/history dst))))))))

(deftest migration-refuses-a-non-empty-target
  (with-tmp [path dir]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    (mig/edn->datalevin! path dir)
    (let [report (mig/edn->datalevin! path dir)]
      (is (not (:ok? report)))
      (is (re-find #"not empty" (:error report))))
    ;; and the refusal did not append the log a second time
    (with-store [dst dir]
      (is (= 1 (count (sub/history dst)))))))

(deftest a-single-event-log-migrates
  ;; the smallest real log there is: one event, one object, key 1..1
  (with-tmp [path dir]
    (let [src    (sub/persistent-store path)
          _      (sub/commit! src {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
          report (mig/edn->datalevin! path dir)]
      (is (:ok? report))
      (is (= 1 (:events report)))
      (with-store [dst dir]
        (is (= (sub/history src) (sub/history dst)))
        (is (= (sub/state src) (sub/state dst)))
        (is (= 1 (:value (dlv/object-at dst "a" 1))))))))

(deftest the-source-log-is-never-modified
  ;; the EDN file IS the rollback. A migration that touched it would take the
  ;; only copy of the log with it if the import turned out wrong.
  (with-tmp [path dir]
    (seed! path)
    (let [before (slurp path)]
      (is (:ok? (mig/edn->datalevin! path dir)))
      (is (= before (slurp path)))
      ;; including the run that refuses
      (mig/edn->datalevin! path dir)
      (is (= before (slurp path))))))

(deftest a-truncated-tail-is-copied-as-the-edn-store-READS-it
  ;; a crash mid-append leaves a half-written last line. `load-events` salvages
  ;; the prefix, so the running system's log is 2 events even though the file
  ;; has 3 lines — and the migration must copy the log the system HAS, not
  ;; invent the event it never managed to read. Silent is the part that is not
  ;; acceptable: the report says how many lines it could not use.
  (with-tmp [path dir]
    (let [torn (pr-str {:op :put :id "b" :value {:id "b" :kind :doc :value 3} :ts 3})]
      (io/make-parents (io/file path))
      (spit path (str (pr-str {:op :put :id "a" :value {:id "a" :kind :doc :value 1} :ts 1}) "\n"
                      (pr-str {:op :assoc :id "a" :path [:value] :value 2 :ts 2}) "\n"
                      (subs torn 0 22))))
    (let [src    (sub/persistent-store path)
          report (mig/edn->datalevin! path dir)]
      (is (= 2 (count (sub/history src))))                     ; what the EDN store reads
      (is (:ok? report))
      (is (= 2 (:events report)))
      (is (= 3 (:lines report)))
      (is (= 1 (:skipped report)))                             ; and it says so out loud
      (with-store [dst dir]
        (is (= 2 (count (sub/history dst))))
        (is (= (sub/history src) (sub/history dst)))
        (is (= (sub/state src) (sub/state dst)))
        (is (nil? (dlv/object-at dst "b" 2)))))))

(deftest an-empty-source-creates-no-target
  ;; a run that has nothing to do must not leave an LMDB directory behind —
  ;; the next, real run would then refuse it as a target it did not recognise.
  (with-tmp [path dir]
    (let [report (mig/edn->datalevin! path dir)]
      (is (not (:ok? report)))
      (is (re-find #"nothing to migrate" (:error report)))
      (is (not (.exists (io/file dir)))))))

(deftest the-target-may-not-be-the-source-log
  ;; `clojure -M -m loci.migrate data/substrate.edn data/substrate.edn` is one
  ;; slip of a finger, and it aims an LMDB env at the only copy of the log.
  ;; Refuse before Datalevin is handed the path at all.
  (with-tmp [path dir]
    (seed! path)
    (let [before (slurp path)
          report (mig/edn->datalevin! path path)]
      (is (not (:ok? report)))
      (is (re-find #"not a directory" (:error report)))
      (is (= before (slurp path)))
      (is (.isFile (io/file path)))
      (is (not (.exists (io/file dir)))))))

(defn- corrupting-transact
  "d/transact-kv, but the first event it writes lands with a different :id — a
   write that SUCCEEDS and is wrong, which no exception announces and only the
   verification can catch. Everything else (counts, touched, meta) passes
   through untouched, so the rollback under test is not itself sabotaged."
  [real]
  (fn ([kv ops]
       (real kv (mapv (fn [[op dbi k v t :as o]]
                        (if (and (= :put op) (= "events" dbi) (= 1 k))
                          [op dbi k (assoc v :id "wrong") t]
                          o))
                      ops)))
    ([kv a b] (real kv a b))
    ([kv a b c d] (real kv a b c d))))

(deftest a-failed-verification-empties-the-target-so-the-run-can-be-repeated
  ;; the case the guard would otherwise trap: an import writes every event,
  ;; then finds the result does not match the source. The target now holds
  ;; data that is not trustworthy, and "refuses a non-empty target" would block
  ;; the retry — the operator's only move being to delete a directory by hand
  ;; and hope that was the right one. So a failed import undoes itself.
  (with-tmp [path dir]
    (let [src (seed! path)
          bad (with-redefs [d/transact-kv (corrupting-transact d/transact-kv)]
                (mig/edn->datalevin! path dir))]
      (is (not (:ok? bad)))
      (is (re-find #"verbatim" (:error bad)))
      (is (:rolled-back? bad))
      ;; the target is empty again — not half-full of a log nobody can trust
      (with-store [dst dir]
        (is (= [] (sub/history dst)))
        (is (= {} (sub/objects dst)))
        ;; and its index is clean rather than dirty, so it reads as the empty
        ;; store it is instead of throwing "torn index" at the next reader
        (is (nil? (dlv/counts-at dst 1))))
      ;; so the very same command simply works the second time
      (let [good (mig/edn->datalevin! path dir)]
        (is (:ok? good))
        (is (= 3 (:events good))))
      (with-store [dst dir]
        (is (= (sub/history src) (sub/history dst)))
        (is (= (sub/state src) (sub/state dst)))))))
