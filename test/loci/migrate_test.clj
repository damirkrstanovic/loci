(ns loci.migrate-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defmacro quietly
  "`load-events` announces an unreadable line on *err*, which is right and is
   also noise in a suite that provokes one on purpose."
  [& body]
  `(binding [*err* (java.io.StringWriter.)] ~@body))

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

(defn- ev [i id]
  (str (pr-str {:op :put :id id :value {:id id :kind :doc :value i} :ts i}) "\n"))

(deftest migration-preserves-state-and-history
  (with-tmp [path dir]
    (let [src    (seed! path)
          report (mig/edn->datalevin! path dir)]
      (with-store [dst dir]
        (is (:ok? report))
        (is (= 3 (:events report)))
        (is (= 0 (:skipped report)))                           ; nothing was lost on the way
        (is (= :migrated (:target report)))
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
      (is (re-find #"not empty" (:error report)))
      (is (= :untouched (:target report))))
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
      (is (:ok? report))                                       ; a lost tail is benign
      (is (= 2 (:events report)))
      (is (= 3 (:lines report)))
      (is (= 1 (:skipped report)))                             ; and it says so out loud
      (is (:truncated-tail? report))
      (is (= [] (:damaged report)))                            ; nothing damaged IN the log
      (with-store [dst dir]
        (is (= 2 (count (sub/history dst))))
        (is (= (sub/history src) (sub/history dst)))
        (is (= (sub/state src) (sub/state dst)))
        (is (nil? (dlv/object-at dst "b" 2)))))))

(deftest an-unreadable-line-before-the-last-refuses-until-it-is-accepted
  ;; a damaged line in the MIDDLE is not a crash mid-append — it is a log that
  ;; has been quietly dropping an event on every boot, which is how 31 of them
  ;; went missing this morning. The line is still in the file, so the migration
  ;; is the last moment it can be repaired; `migrate && mv substrate.edn
  ;; substrate.edn.migrated` on a cheerful exit 0 throws that chance away.
  (quietly
   (with-tmp [path dir]
     (io/make-parents (io/file path))
     (spit path (str (ev 1 "a")
                     "{:op :put :id \"b\" :value {:id \"b\" (damaged in place\n"
                     (ev 3 "c")))
     (let [src    (sub/persistent-store path)
           report (mig/edn->datalevin! path dir)]
       (is (= 2 (count (sub/history src))))
       (is (not (:ok? report)))
       (is (= [2] (:damaged report)))                          ; 1-based, as an editor counts
       (is (false? (:truncated-tail? report)))
       (is (re-find #"repaired by hand" (:error report)))
       (is (= :untouched (:target report)))
       (is (not (.exists (io/file dir))))                      ; and nothing was written
       ;; the operator who has decided the line is unrecoverable can still go
       (let [forced (mig/edn->datalevin! path dir {:accept-damaged-lines? true})]
         (is (:ok? forced))
         (is (= 2 (:events forced)))
         (is (= 3 (:lines forced)))
         (is (= 1 (:skipped forced))))
       (with-store [dst dir]
         (is (= (sub/history src) (sub/history dst)))
         (is (= #{"a" "c"} (set (keys (sub/objects dst))))))))))

(deftest a-commit-landing-mid-import-is-refused-not-verified
  ;; `PersistentStore` caches: `history` is the vector `load-events` read when
  ;; the store was constructed, and the file is never re-read. content.clj
  ;; holds one such store on data/substrate.edn for the life of the server and
  ;; commits to it from request threads and futures — so without a re-read at
  ;; the end, an import copies 2 events out of a 3-event file and reports
  ;; "state verified identical". The event is not destroyed, but the target
  ;; never sees it and the next commit! gives its key to a different event.
  (with-tmp [path dir]
    (seed! path)
    (let [fired  (atom false)
          real   dlv/rebuild-indices!
          report (with-redefs [dlv/rebuild-indices!
                               (fn [st]
                                 (when (compare-and-set! fired false true)
                                   ;; a second store on the same file, exactly as
                                   ;; the running server would have
                                   (sub/commit! (sub/persistent-store path)
                                                {:op :put :id "late"
                                                 :value {:id "late" :kind :doc}}))
                                 (real st))]
                   (mig/edn->datalevin! path dir))]
      (is (not (:ok? report)))
      (is (re-find #"changed while the import was running" (:error report)))
      (is (re-find #"Stop the server" (:error report)))
      (is (= :emptied (:target report)))
      (with-store [dst dir]
        (is (= [] (sub/history dst))))
      ;; and with the server stopped the same command takes all four events
      (let [good (mig/edn->datalevin! path dir)]
        (is (:ok? good))
        (is (= 4 (:events good))))
      (with-store [dst dir]
        (is (= 4 (count (sub/history dst))))
        (is (= "late" (:id (sub/object dst "late"))))))))

(deftest an-empty-source-creates-no-target
  ;; a run that has nothing to do must not leave an LMDB directory behind —
  ;; the next, real run would then refuse it as a target it did not recognise.
  (with-tmp [path dir]
    (let [report (mig/edn->datalevin! path dir)]
      (is (not (:ok? report)))
      (is (re-find #"nothing to migrate" (:error report)))
      (is (= :untouched (:target report)))
      (is (not (.exists (io/file dir)))))))

(deftest a-target-that-is-a-file-or-holds-the-source-is-refused
  ;; `… data/substrate.edn data/substrate.edn` is one slip of a finger, and
  ;; `… data/substrate.edn data` is one character short of the right answer —
  ;; the second would scatter VERSION/data.mdb/lock.mdb into the directory
  ;; that holds the only copy of the log. Refuse before Datalevin sees it.
  (with-tmp [path dir]
    (seed! path)
    (let [before (slurp path)
          root   (.getParent (io/file path))
          other  (str dir "-not-an-env")]
      (spit other "some other file entirely")
      (let [r (mig/edn->datalevin! path path)]
        (is (not (:ok? r)))
        (is (re-find #"not a directory" (:error r))))
      (let [r (mig/edn->datalevin! path other)]
        (is (not (:ok? r)))
        (is (re-find #"not a directory" (:error r))))
      (let [r (mig/edn->datalevin! path root)]
        (is (not (:ok? r)))
        (is (re-find #"must not contain the source log" (:error r)))
        (is (= :untouched (:target r))))
      ;; nothing was written to any of them
      (is (= before (slurp path)))
      (is (= "some other file entirely" (slurp other)))
      (is (not (.exists (io/file root "data.mdb"))))
      (is (not (.exists (io/file dir)))))))

(deftest a-source-that-is-not-a-file-reports-rather-than-throws
  ;; the contract is a report. A source path that is a directory used to come
  ;; back as a FileNotFoundException and a stack trace out of -main.
  (with-tmp [path dir]
    (let [as-source (str dir "-source-dir")]
      (.mkdirs (io/file as-source))
      (let [r (mig/edn->datalevin! as-source dir)]
        (is (not (:ok? r)))
        (is (re-find #"is a directory" (:error r)))
        (is (= :untouched (:target r)))
        (is (not (.exists (io/file dir))))))))

(deftest a-target-that-cannot-be-opened-reports-rather-than-throws
  ;; a gapped `events` dbi — what an interrupted bulk write leaves — makes
  ;; `read-log` refuse, and that refusal has to reach the operator as a
  ;; migration report rather than as an ExceptionInfo from three layers down.
  (with-tmp [path dir]
    (seed! path)
    (with-store [s dir]
      (d/transact-kv (:kv s) [[:put "events" 2 {:op :put :id "x" :value {:id "x"} :ts 0} :long]]))
    (let [r (mig/edn->datalevin! path dir)]
      (is (not (:ok? r)))
      (is (re-find #"target cannot be opened" (:error r)))
      (is (re-find #"not contiguous" (:error r)))
      (is (= :untouched (:target r)))
      (is (instance? clojure.lang.ExceptionInfo (:cause r))))))

(deftest the-command-line-is-two-paths-or-none
  ;; one path is a typo, not a run — it used to be an ArityException
  (let [parse #(#'mig/parse-args %)]
    (is (:usage? (parse ["only-one-path"])))
    (is (:usage? (parse ["a" "b" "c"])))
    (is (:usage? (parse ["a" "b" "--nonsense"])))
    (is (= {:path "a" :dir "b" :accept-damaged-lines? false} (parse ["a" "b"])))
    (is (= {:path "a" :dir "b" :accept-damaged-lines? true}
           (parse ["a" "b" "--accept-damaged-lines"])))
    (let [d (parse [])]
      (is (str/ends-with? (:path d) "/substrate.edn"))
      (is (str/ends-with? (:dir d) "/substrate"))
      (is (false? (:accept-damaged-lines? d))))))

(defn- corrupting-transact
  "d/transact-kv, but the first event it writes lands with a different :id — a
   write that SUCCEEDS and is wrong, which no exception announces and only the
   verification can catch. Everything else (counts, touched, meta) passes
   through untouched, so the rollback under test is not itself sabotaged.
   Every arity of the real var, so that a `dlv` which starts using one of the
   others fails as itself rather than as a migration bug."
  [real]
  (fn ([kv ops]
       (real kv (mapv (fn [[op dbi k v t :as o]]
                        (if (and (= :put op) (= "events" dbi) (= 1 k))
                          [op dbi k (assoc v :id "wrong") t]
                          o))
                      ops)))
    ([kv a b] (real kv a b))
    ([kv a b c] (real kv a b c))
    ([kv a b c d] (real kv a b c d))))

(defn- exploding-transact
  "d/transact-kv, but the SECOND event never lands — a copy that dies partway,
   the way an out-of-space or a killed env would end it."
  [real]
  (fn ([kv ops]
       (if (some (fn [[op dbi k]] (and (= :put op) (= "events" dbi) (= 2 k))) ops)
         (throw (ex-info "injected LMDB failure mid-copy" {}))
         (real kv ops)))
    ([kv a b] (real kv a b))
    ([kv a b c] (real kv a b c))
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
      (is (= :emptied (:target bad)))
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

(deftest an-exception-mid-copy-rolls-the-target-back-too
  ;; not every failure is a mismatch — a copy can die on its second write, and
  ;; the durable prefix it already wrote is just as untrustworthy as a wrong
  ;; one. Same rollback, same repeatable command.
  (with-tmp [path dir]
    (seed! path)
    (let [bad (with-redefs [d/transact-kv (exploding-transact d/transact-kv)]
                (mig/edn->datalevin! path dir))]
      (is (not (:ok? bad)))
      (is (re-find #"import failed" (:error bad)))
      (is (re-find #"injected LMDB failure" (:error bad)))
      (is (= :emptied (:target bad)))
      (is (instance? clojure.lang.ExceptionInfo (:cause bad)))  ; debuggable, not just a string
      (with-store [dst dir]
        (is (= [] (sub/history dst))))
      (is (:ok? (mig/edn->datalevin! path dir))))))

(deftest a-rollback-that-itself-fails-names-the-directory-to-delete
  ;; the one case the migration cannot fix for the operator. It must not
  ;; pretend otherwise: :target is :dirty, the message names the directory, and
  ;; the next run refuses it rather than merging into a half-written log.
  (with-tmp [path dir]
    (seed! path)
    (let [bad (with-redefs [d/clear-dbi (fn [& _] (throw (ex-info "injected rollback failure" {})))]
                (mig/edn->datalevin! path dir))]
      (is (not (:ok? bad)))
      (is (= :dirty (:target bad)))
      (is (re-find #"could NOT be emptied" (:error bad)))
      (is (str/includes? (:error bad) dir)))
    (let [again (mig/edn->datalevin! path dir)]
      (is (not (:ok? again)))
      (is (re-find #"not empty" (:error again)))
      (is (= :untouched (:target again))))))
