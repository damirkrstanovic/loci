(ns loci.migrate
  "One-shot import of the EDN-lines log into Datalevin. A replay, not a
   conversion: events are copied verbatim and in order, so the ⏱ scrubber
   still travels the same moments with the same labels.

   STOP THE SERVER FIRST. `content.clj` holds one `PersistentStore` on
   data/substrate.edn for the life of the process and commits to it from
   request threads and from `future`s, and a store's `history` is the vector
   `load-events` read at construction — the file is never re-read. So an
   import can copy a perfectly verified log that grew two events ago. This
   namespace re-reads the file at the end and REFUSES if it moved, which is
   all a migration can do: it can notice, it cannot merge.

   Run it:  clojure -M -m loci.migrate            (data/substrate.edn → data/substrate)
            clojure -M -m loci.migrate <edn> <dir>

   The EDN file is never modified — it is the rollback. What is NOT covered:
   a kill -9 partway through leaves a partial import that looks exactly like a
   populated target, and the next run refuses it as one. That is this design's
   floor; the EDN file is still intact, so the cure is to delete the target
   directory and run again."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

;; ----------------------------------------------------------------------------
;; reading the source — including what `load-events` throws away
;; ----------------------------------------------------------------------------

(defn- scan
  "Read the file the way `load-events` does, but keep what it discards: the
   non-blank line count, and WHICH lines the reader could not use.

   The distinction that matters is not how many lines were lost but where. The
   LAST line of a file is a crash mid-append: the event never made it, never
   existed, and copying the log without it is correct. Any line before it is a
   log that was damaged in place — its events were being silently dropped on
   every boot, which is how 31 of them went missing this morning — and a
   migration is the last moment that line is still there to be repaired."
  [path]
  (let [f (io/file path)]
    (if-not (.isFile f)
      {:lines 0 :damaged [] :truncated-tail? false}
      (with-open [r (io/reader f)]
        (let [lines  (vec (line-seq r))
              last-i (dec (count lines))
              bad    (into [] (keep-indexed
                               (fn [i line]
                                 (when (and (not (str/blank? line))
                                            (= ::bad (try (edn/read-string line)
                                                          (catch Exception _ ::bad))))
                                   i))
                               lines))]
          {:lines           (count (remove str/blank? lines))
           ;; 1-based, so they name lines the way an editor does
           :damaged         (mapv inc (remove #(= % last-i) bad))
           :truncated-tail? (boolean (some #(= % last-i) bad))})))))

(defn- snapshot
  "The source as it is right now: the events a fresh `PersistentStore` reads,
   and the shape of the file they came from. Taken twice — before the import
   and after — because a cached `history` cannot tell you the file has moved."
  [path]
  {:log (sub/history (sub/persistent-store path)) :scan (scan path)})

;; ----------------------------------------------------------------------------
;; writing the target
;; ----------------------------------------------------------------------------

(defn- copy-events!
  "The log, straight into the `events` dbi. Deliberately not through `commit!`:
   `commit!` re-stamps `:ts`, and a log whose every event happened at the
   moment of the migration is a different log — the scrubber would show one
   afternoon where there were weeks.

   Keys are 1..n, the invariant `read-log` enforces, and one transaction per
   event, which is what `rebuild-indices!` does over the same log immediately
   afterwards: batching the copy could at best halve the transaction count of
   an import that runs once, and would add a boundary to get wrong."
  [kv log]
  (doseq [[i ev] (map-indexed (fn [k ev] [(inc k) ev]) log)]
    (d/transact-kv kv [[:put "events" i ev :long]])))

;; ----------------------------------------------------------------------------
;; verification — every way the copy could be wrong that we can still catch
;; ----------------------------------------------------------------------------

(defn- index-complaint
  "The derived index, checked the only way that means anything: by asking it.
   `counts-at` throws if the validity mark is still dirty, and a census that
   disagrees with the folded state means the rebuild replayed something other
   than the log just written. Neither shows up in a state comparison, and both
   leave a store whose every ?at= read is an exception instead of a page."
  [dst n objects]
  (try
    (let [c (dlv/counts-at dst n)]
      (when-not (= objects (:objects c))
        (str "the derived index disagrees with the log — after event " n
             " it counts " (:objects c) " objects, the log folds to " objects)))
    (catch clojure.lang.ExceptionInfo e
      (str "the derived index is unusable after import: " (ex-message e)))))

(defn- drift-complaint
  "Did the file move under us? Compare a fresh read against the one the import
   copied. A `PersistentStore` caches, so this is the one question the import
   cannot answer from the store it already holds — and the answer decides
   whether it copied the log or a photograph of it. The event is not lost, it
   is still in the EDN file; but the target would never see it, and the next
   `commit!` would give its key to a different event, so the two logs diverge
   permanently and in silence."
  [path before]
  (let [now (snapshot path)]
    (when (not= before now)
      (str "the source log changed while the import was running — "
           (count (:log before)) " events / " (:lines (:scan before)) " lines at the start, "
           (count (:log now)) " events / " (:lines (:scan now)) " at the end. "
           "Stop the server (it holds an open store on this file and commits to it "
           "from request threads and futures) and run the migration again"))))

(defn- complaint
  "Everything that must be true before the EDN file stops being the truth, or
   the first reason it is not.

   State equality is the total check but not a sufficient one: two different
   logs can fold to the same state (an event and its own undo, a :put re-put
   with the same value, a re-ordering that happens to commute), and the log is
   what the scrubber and the audit trail read. So compare the event vectors
   too — and compare against what `reload!` re-read from disk, not against the
   vector this namespace just handed to LMDB."
  [path before dst]
  (let [{:keys [log state]} (dlv/view dst)
        in (:log before)]
    (or (drift-complaint path before)
        (when-not (= (count in) (count log))
          (str "event count differs after import — " (count in)
               " in, " (count log) " out"))
        (when-not (= in log)
          (let [i (->> (map vector in log)
                       (keep-indexed (fn [i [a b]] (when (not= a b) (inc i))))
                       first)]
            (str "the imported log is not verbatim — event " i " differs")))
        (when-not (= (sub/materialize in) state)
          "state mismatch after import — NOT safe to switch over")
        (index-complaint dst (count log) (count (:objects state))))))

;; ----------------------------------------------------------------------------
;; failing safely — a failed import must leave a target the next run accepts
;; ----------------------------------------------------------------------------

(defn- rollback!
  "Put the target back to empty. An import that wrote every event and then
   failed verification leaves a target that is full and untrustworthy, which
   the `not empty` guard would read as a migration already done — the retry
   refused, and the operator left deleting a directory by hand and hoping it
   was the right one. Undoing it makes a failed import a repeatable command.

   `rebuild-indices!` over the now-empty log is what lifts the dirty mark, so
   what is left behind is an honestly empty store rather than one that refuses
   every read."
  [dst dir]
  (try
    (d/clear-dbi (:kv dst) "events")
    (dlv/reload! dst)
    (dlv/rebuild-indices! dst)
    {:target :emptied}
    (catch Throwable t
      {:target :dirty
       :rollback-error (str "the half-imported target could NOT be emptied — delete "
                            dir " by hand before retrying: " (ex-message t))})))

(defn- failed
  ([dst dir why] (failed dst dir why nil))
  ([dst dir why cause]
   (let [{:keys [target rollback-error]} (rollback! dst dir)]
     (cond-> {:ok?    false
              :target target
              :error  (if (= :emptied target)
                        (str why " — the target was emptied, so this run can simply be repeated")
                        (str why " — AND " rollback-error))}
       ;; the throwable itself, not just its message: a migration that dies has
       ;; to be debuggable from the one run it got
       cause (assoc :cause cause)))))

(defn- import!
  [path before dst dir]
  (try
    (copy-events! (:kv dst) (:log before))
    ;; republish RAM from what is actually durable, rather than trusting this
    ;; function to hand back the same log it just wrote
    (dlv/reload! dst)
    (dlv/rebuild-indices! dst)
    (if-let [why (complaint path before dst)]
      (failed dst dir why)
      {:ok? true :target :migrated})
    (catch Throwable t
      (failed dst dir (str "import failed: " (.getName (class t)) ": " (ex-message t)) t))))

;; ----------------------------------------------------------------------------
;; the guards that run before anything is opened or written
;; ----------------------------------------------------------------------------

(defn- unusable-target
  "Refuse before Datalevin is handed a path it would try to open as an LMDB
   directory. The two that matter both aim at the source: `… <edn> <edn>`, and
   `… data/substrate.edn data` — one character short of data/substrate, and it
   would scatter VERSION/data.mdb/lock.mdb into the directory that holds the
   only copy of the log."
  [path dir]
  (let [canon #(.getCanonicalPath (io/file %))
        src   (canon path)
        tgt   (canon dir)]
    (cond
      (.isFile (io/file dir))
      (str "target is not a directory: " dir " is a file")

      (= src tgt)
      (str "target is not a directory: " dir " is the source log")

      (str/starts-with? src (str tgt java.io.File/separator))
      (str "target must not contain the source log: " dir " holds " path))))

(defn- damaged-complaint
  [path damaged n]
  (let [one? (= 1 (count damaged))]
    (str "the source log has " (count damaged) " unreadable line" (when-not one? "s")
         " that the EDN store skips on every boot: line" (if one? " " "s ")
         (str/join ", " damaged) " of " path ". Whatever " (if one? "it" "they")
         " held is still IN the file, "
         "and a migration is the last moment it can be repaired by hand — fix the line"
         (when-not one? "s") " and run again. If it is genuinely unrecoverable, re-run "
         "with --accept-damaged-lines to migrate the " n " events that do read")))

(def ^:private no-stats
  {:events 0 :lines 0 :skipped 0 :damaged [] :truncated-tail? false})

(defn edn->datalevin!
  "Copy every event from the EDN log at `path` into a Datalevin store at `dir`.

   Refuses a non-empty target, a target that is or contains the source, a
   source with unreadable lines before its last (unless
   `:accept-damaged-lines?`), and a source that changed while the import ran.
   Verifies the copy event by event, then by folding both logs, then by asking
   the rebuilt index — and empties the target again if any of that disagrees,
   so a failed import is one that can be re-run rather than one that has to be
   cleaned up first.

   Total: every refusal is a returned report, never a throw. The report is

     :ok?             did the target end up holding a verified copy
     :events          length of the source log — what the run was asked to move
     :lines           non-blank lines in the source file
     :skipped         lines the EDN store could not read (:lines minus :events)
     :damaged         1-based lines it could not read, EXCLUDING a truncated tail
     :truncated-tail? was the last line half-written (a crash mid-append)
     :target          what `dir` holds now — :migrated, :untouched (nothing was
                      written, or nothing of ours), :emptied (written, then
                      rolled back), :dirty (written, and the rollback failed —
                      a human has to delete it), or :unknown
     :error           why not, when not
     :cause           the throwable, when there was one"
  ([path dir] (edn->datalevin! path dir {}))
  ([path dir {:keys [accept-damaged-lines?]}]
   (try
     (if (.isDirectory (io/file path))
       (merge no-stats {:ok? false :target :untouched
                        :error (str "source is not a file: " path " is a directory")})
       (let [before (snapshot path)
             {:keys [lines damaged truncated-tail?]} (:scan before)
             log    (:log before)
             stats  (merge no-stats {:events          (count log)
                                     :lines           lines
                                     :skipped         (- lines (count log))
                                     :damaged         damaged
                                     :truncated-tail? truncated-tail?})
             refuse #(merge stats {:ok? false :target :untouched :error %})]
         (cond
           ;; every guard runs BEFORE the target is opened: a run that cannot
           ;; proceed must not leave an LMDB directory behind for the next,
           ;; real run to refuse as a migration already done
           (and (seq damaged) (not accept-damaged-lines?))
           (refuse (damaged-complaint path damaged (count log)))

           (empty? log)
           (refuse (str "nothing to migrate: " path " has no events"))

           (unusable-target path dir)
           (refuse (unusable-target path dir))

           :else
           (let [opened (try {:store (dlv/datalevin-store dir)} (catch Throwable t {:cause t}))]
             (if-let [t (:cause opened)]
               ;; a target that cannot even be opened — a gapped log from an
               ;; interrupted bulk write, a directory that is not an env — is
               ;; a report like any other refusal, not a stack trace
               (merge stats {:ok? false :target :untouched :cause t
                             :error (str "target cannot be opened: " dir " — "
                                         (.getName (class t)) ": " (ex-message t))})
               (let [dst (:store opened)]
                 (try
                   (merge stats
                          (if-let [existing (seq (sub/history dst))]
                            {:ok? false :target :untouched
                             :error (str "target is not empty: " dir " already has "
                                         (count existing) " events")}
                            (import! path before dst dir)))
                   (finally (dlv/close! dst)))))))))
     (catch Throwable t
       ;; the contract is a report, not a stack trace — but say plainly that
       ;; nothing is known about the target when the failure was not one this
       ;; namespace anticipated
       (merge no-stats {:ok? false :target :unknown :cause t
                        :error (str "migration could not run: "
                                    (.getName (class t)) ": " (ex-message t))})))))

;; ----------------------------------------------------------------------------
;; the command
;; ----------------------------------------------------------------------------

(def ^:private usage
  (str "usage: clojure -M -m loci.migrate [<edn-log> <target-dir>] [--accept-damaged-lines]\n"
       "       with no paths: <data-dir>/substrate.edn → <data-dir>/substrate\n"
       "       STOP THE SERVER FIRST — it holds an open store on the EDN log, and a\n"
       "       commit landing mid-import is refused, not merged."))

(defn- parse-args
  "Positional paths and flags, or `:usage?` — one path is a typo, not a run."
  [args]
  (let [flag?   #(str/starts-with? % "--")
        flags   (into #{} (filter flag?) args)
        pos     (vec (remove flag? args))
        unknown (remove #{"--accept-damaged-lines"} flags)]
    (if (or (seq unknown) (not (#{0 2} (count pos))))
      {:usage? true}
      {:path                  (if (empty? pos) (str (sub/data-dir) "/substrate.edn") (pos 0))
       :dir                   (if (empty? pos) (str (sub/data-dir) "/substrate") (pos 1))
       :accept-damaged-lines? (contains? flags "--accept-damaged-lines")})))

(defn -main [& args]
  (let [{:keys [usage? path dir] :as parsed} (parse-args args)]
    (if usage?
      (do (println usage) (System/exit 2))
      (do
        (println "loci migrate: the server must NOT be running — it holds an open store on"
                 "the EDN log, and a commit landing mid-import is refused, not merged.")
        (let [{:keys [ok? events lines skipped damaged truncated-tail? error cause]}
              (edn->datalevin! path dir (select-keys parsed [:accept-damaged-lines?]))]
          (when truncated-tail?
            (println (format "note: the last line of %s is half-written (a crash mid-append) — that event never existed"
                             path)))
          (when (seq damaged)
            (println (format "WARNING: %d of the %d lines in %s could not be read by the EDN store (line%s %s) and were never part of its log"
                             (count damaged) lines path
                             (if (= 1 (count damaged)) "" "s") (str/join ", " damaged))))
          (if ok?
            (println (format "migrated %,d events: %s → %s (log verbatim, state and index verified%s)"
                             events path dir
                             (if (pos? skipped) (format "; %d unreadable line(s) skipped" skipped) "")))
            (do (println "migration FAILED:" error)
                (when cause (.printStackTrace ^Throwable cause))))
          (System/exit (if ok? 0 1)))))))
