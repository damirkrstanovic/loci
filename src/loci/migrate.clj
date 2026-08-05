(ns loci.migrate
  "One-shot import of the EDN-lines log into Datalevin. A replay, not a
   conversion: events are copied verbatim and in order, so the ⏱ scrubber
   still travels the same moments with the same labels.

   Run it:  clojure -M -m loci.migrate            (data/substrate.edn → data/substrate)
            clojure -M -m loci.migrate <edn> <dir>

   The EDN file is never modified — it is the rollback."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [loci.dlv :as dlv]
            [loci.substrate :as sub]))

(defn- non-blank-lines
  "How many lines the FILE holds, against which the events actually read can be
   compared. `load-events` salvages a truncated tail and skips an unreadable
   line in the middle; both are the right call — the migration must copy the
   log the running system HAS, not invent an event it never managed to read —
   but the difference has to be visible. A log lost 31 events to exactly this,
   silently, because nothing ever counted the lines it could not use."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (with-open [r (io/reader f)] (count (remove str/blank? (line-seq r))))
      0)))

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

(defn- complaint
  "Everything that must be true before the EDN file stops being the truth, or
   the first reason it is not.

   State equality is the total check but not a sufficient one: two different
   logs can fold to the same state (an event and its own undo, a :put re-put
   with the same value, a re-ordering that happens to commute), and the log is
   what the scrubber and the audit trail read. So compare the event vectors
   too — and compare against what `reload!` re-read from disk, not against the
   vector this namespace just handed to LMDB."
  [src dst]
  (let [{:keys [log state]} (dlv/view dst)
        in (sub/history src)]
    (or (when-not (= (count in) (count log))
          (str "event count differs after import — " (count in)
               " in, " (count log) " out"))
        (when-not (= in log)
          (let [i (->> (map vector in log)
                       (keep-indexed (fn [i [a b]] (when (not= a b) (inc i))))
                       first)]
            (str "the imported log is not verbatim — event " i " differs")))
        (when-not (= (sub/state src) state)
          "state mismatch after import — NOT safe to switch over")
        (index-complaint dst (count log) (count (:objects state))))))

(defn- rollback!
  "Put the target back to empty. An import that wrote every event and then
   failed verification leaves a target that is full and untrustworthy, which
   the `not empty` guard would read as a migration already done — the retry
   would be refused and the operator's only move would be to delete a
   directory by hand and hope it was the right one. Undoing it makes a failed
   import a repeatable command instead.

   `rebuild-indices!` over the now-empty log is what lifts the dirty mark, so
   what is left behind is an honestly empty store rather than one that refuses
   every read."
  [dst dir]
  (try
    (d/clear-dbi (:kv dst) "events")
    (dlv/reload! dst)
    (dlv/rebuild-indices! dst)
    {:rolled-back? true}
    (catch Throwable t
      {:rolled-back? false
       :rollback-error (str "the half-imported target could NOT be emptied — delete "
                            dir " by hand before retrying: " (ex-message t))})))

(defn- failed
  ([dst dir why] (failed dst dir why nil))
  ([dst dir why cause]
   (let [{:keys [rolled-back? rollback-error]} (rollback! dst dir)]
     (cond-> {:ok?          false
              :rolled-back? rolled-back?
              :error        (if rolled-back?
                              (str why " — the target was emptied, so this run can simply be repeated")
                              (str why " — AND " rollback-error))}
       ;; the throwable itself, not just its message: a migration that dies has
       ;; to be debuggable from the one run it got
       cause (assoc :cause cause)))))

(defn- import!
  [src log dst dir]
  (try
    (copy-events! (:kv dst) log)
    ;; republish RAM from what is actually durable, rather than trusting this
    ;; function to hand back the same log it just wrote
    (dlv/reload! dst)
    (dlv/rebuild-indices! dst)
    (if-let [why (complaint src dst)]
      (failed dst dir why)
      {:ok? true :events (count log)})
    (catch Throwable t
      (failed dst dir (str "import failed: " (.getName (class t)) ": " (ex-message t)) t))))

(defn- unusable-target
  "Refuse before Datalevin is handed a path it would try to open as an LMDB
   directory. The one that matters is `… <edn> <edn>` — a second argument
   fat-fingered onto the source log, which is the only copy of the log there
   is, and which this namespace must never write to."
  [path dir]
  (let [canon #(.getCanonicalPath (io/file %))]
    (cond
      (.isFile (io/file dir)) (str "target is not a directory: " dir " is a file")
      (= (canon path) (canon dir)) (str "target is not a directory: " dir " is the source log"))))

(defn edn->datalevin!
  "Copy every event from the EDN log at `path` into a Datalevin store at `dir`.
   Refuses a non-empty target. Verifies the copy — event by event, then by
   folding both logs, then by asking the rebuilt index — and empties the target
   again if any of that disagrees, so a failed import is one that can be re-run
   rather than one that has to be cleaned up.

   Returns {:ok? :events :lines :skipped :error}, where `events` is the length
   of the SOURCE log — what the run was asked to move — and `skipped` is the
   lines of the file the EDN store could not read and therefore never had."
  [path dir]
  (let [src        (sub/persistent-store path)
        log        (sub/history src)
        lines      (non-blank-lines path)
        stats      {:events (count log) :lines lines :skipped (- lines (count log))}
        bad-target (unusable-target path dir)]
    (cond
      ;; both checks come BEFORE the target is opened: a run that cannot
      ;; proceed must not leave an LMDB directory behind for the next, real
      ;; run to refuse as a migration already done
      (empty? log)
      (merge stats {:ok? false :error (str "nothing to migrate: " path " has no events")})

      bad-target
      (merge stats {:ok? false :error bad-target})

      :else
      (let [dst (dlv/datalevin-store dir)]
        (try
          (merge stats
                 (if-let [existing (seq (sub/history dst))]
                   {:ok? false :error (str "target is not empty: " dir " already has "
                                           (count existing) " events")}
                   (import! src log dst dir)))
          (finally (dlv/close! dst)))))))

(defn -main
  ([] (-main (str (sub/data-dir) "/substrate.edn") (str (sub/data-dir) "/substrate")))
  ([path dir]
   (let [{:keys [ok? events lines skipped error cause]} (edn->datalevin! path dir)]
     (when (and skipped (pos? skipped))
       (println (format "WARNING: %d of the %d lines in %s could not be read by the EDN store and were never part of its log"
                        skipped lines path)))
     (if ok?
       (println (format "migrated %,d events: %s → %s (log verbatim, state and index verified)"
                        events path dir))
       (do (println "migration FAILED:" error)
           (when cause (.printStackTrace ^Throwable cause))))
     (System/exit (if ok? 0 1)))))
