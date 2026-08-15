(ns loci.factmigrate
  "`data/memory.edn` into the substrate's `facts` dbi. **Dry run by default.**

   Shaped like `loci.corpus`, for its reasons: nothing runs on require, every
   refusal is a returned report rather than a throw, the whole world is checked
   before any of it is written, and the plan a dry run returns is the value a
   live run reports.

   **The source file is never deleted, never renamed and never written.** It is
   the rollback, and reversing this migration is pointing the config back at it.
   Deleting it on success would make a successful migration the moment the
   rollback stopped existing."
  (:require [loci.factlog :as fl]
            [loci.dlv :as dlv]))

(defn migrate-facts!
  "Copy every record in `path` into `st`'s `facts` dbi.

   Returns a report, never throws:

     :ok? :dry-run? :already-done?
     :plan     {:count n :ids [...]}
     :migrated how many records were written
     :error    why not, when not"
  ([st path] (migrate-facts! st path {}))
  ([st path {:keys [dry-run?] :or {dry-run? true}}]
   (let [src   (fl/load-all (fl/file-log path))
         log   (dlv/fact-log st)
         dst   (fl/load-all log)
         no-id (some (fn [[id rec]] (when (nil? id) rec)) src)
         clash (some (fn [[id rec]]
                       (when-let [there (get dst id)]
                         (when (not= (:fact there) (:fact rec)) [id there rec])))
                     src)]
     (cond
       (empty? src)
       {:ok? false :dry-run? dry-run? :error (str path " holds no facts")}

       no-id
       {:ok? false :dry-run? dry-run?
        :error (str "a record in " path " has no :id, so it cannot be keyed or "
                    "reconciled: " (pr-str no-id))}

       clash
       (let [[id there rec] clash]
         {:ok? false :dry-run? dry-run?
          :error (str "the target already holds " id " with different text — "
                      "refusing rather than overwriting it. target: "
                      (pr-str (:fact there)) " source: " (pr-str (:fact rec)))})

       (every? #(contains? dst %) (keys src))
       {:ok? true :dry-run? dry-run? :already-done? true :migrated 0
        :plan {:count 0 :ids []}}

       :else
       (let [todo (into {} (remove (fn [[id _]] (contains? dst id))) src)
             plan {:count (count todo) :ids (vec (sort (keys todo)))}]
         (if dry-run?
           {:ok? true :dry-run? true :plan plan}
           (do
             ;; No counter bump here. `DbiLog.append!` advances `:facts/next-id`
             ;; in the same transaction as each record, so by the time this
             ;; doseq ends the counter is already past everything migrated.
             ;; Calling `mint-id!` to "make sure" would burn a live id and
             ;; leave a hole for no reason.
             (doseq [[_ rec] todo] (fl/append! log rec))
             {:ok? true :dry-run? false :plan plan :migrated (count todo)})))))))
