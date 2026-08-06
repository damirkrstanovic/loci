(ns loci.demo
  "Headless proof of layers 1, 2 and 4 — no Clerk, no browser.
   Run:  clojure -M:demo"
  (:require [clojure.datafy :as d]
            [clojure.pprint :refer [pprint]]
            [loci.content :as c]
            [loci.dlv :as dlv]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

(defn- banner [s] (println (str "\n── " s " " (apply str (repeat (max 0 (- 60 (count s))) "─")))))

(defn -main [& _]
  ;; never touch the real data/ — the demo gets its own throwaway substrate
  (System/setProperty "loci.data-dir"
                      (str (System/getProperty "java.io.tmpdir") "/loci-demo-" (System/currentTimeMillis)))
  (let [store @c/store]

    (banner "the substrate (layer 1) — content stored as events")
    (println (count (sub/history store)) "events committed;"
             (count (sub/objects store)) "objects materialized")
    (println "spaces:" (->> (sub/objects store) vals (filter #(= :space (:kind %))) (map :title)))

    (banner "a space, populated")
    (let [s (sub/object store "space:retention")]
      (println (:title s) "—" (get-in s [:value :intent]))
      (doseq [m (c/members store "space:retention")]
        (println (format "   · %-12s %s" (name (:kind m)) (:title m)))))

    (banner "one stored object, molded three ways")
    (let [rev (:value (sub/object store "tbl:revenue"))]
      (doseq [view [:table/rows :churn/cohort :table/line]]
        (let [{:keys [label kind rendered alternatives]} (mold/mold rev view)]
          (println (format "• %-16s kind=%-7s menu=%s" label (name kind) (mapv second alternatives)))
          (when (= view :churn/cohort) (print "   ") (pprint rendered)))))
    (println "\n(the plain themes table offers fewer molds — the menu is contextual:)")
    (println "  tbl:themes menu →"
             (mapv second (:alternatives (mold/mold (:value (sub/object store "tbl:themes"))))))

    (banner "universal undo (layer 1) — every edit is reversible")
    (let [text #(:value (sub/object store "doc:churn"))]
      (println "before           :" (subs (text) 0 24) "…")
      (sub/commit! store {:op :assoc :id "doc:churn" :path [:value] :value "(agent rewrote this)"})
      (println "after agent edit :" (text))
      (sub/undo! store)
      (println "after undo!      :" (subs (text) 0 24) "…")
      (println "history length restored:" (count (sub/history store))))

    (banner "moldable navigation (datafy / nav)")
    (let [t (d/datafy c/sample-ticket)]
      (println "nav :account-id →" (d/nav t :account-id (:account-id t))))

    (banner "recall seam (stubbed — Khora's role later)")
    (let [r (mold/naive-recall)]
      (mold/remember r "Q3 churn = 4.2%, up from 3.1% in Q2." {:source "tbl:revenue#R412"})
      (mold/remember r "Pricing changed on March 4." {:source "doc:pricing#v3"})
      (println "recall \"churn\" →" (mapv :fact (mold/recall r "churn" {}))))

    (banner "notebook = space — ordered cells, molded per cell")
    (doseq [c (nb/cells-of (sub/object store "space:world"))]
      (println (if (:text c)
                 (str "   ¶ " (let [t (:text c)] (if (> (count t) 52) (str (subs t 0 52) "…") t)))
                 (str "   ▤ " (:ref c) (when (:view c) (str "  · molded as " (:view c)))))))

    (banner "links — connectedness is computed, never stored")
    (println "space:retention ↔"
             (mapv (fn [c] [(:id c) (mapv :type (:reasons c))])
                   (:connected (nb/links store "space:retention"))))

    (banner "two logs: substrate records, memory recalls")
    (let [m (mem/file-memory (str (sub/data-dir) "/memory.edn"))]
      (mold/remember m "Churn concentrates in accounts under 12 months."
                     {:entities ["churn"] :source {:obj "tbl:revenue"}})
      (mold/remember m "Churn concentrates in accounts under twelve months."
                     {:entities ["retention"] :source {:obj "doc:churn"}})
      (println "2 remembers, near-duplicate →" (count (mem/all-facts m))
               "fact, strength" (:strength (first (mem/all-facts m))))
      (sub/commit! store {:op :assoc :id "doc:churn" :path [:value] :value "scratch"})
      (sub/undo! store)
      (println "after a substrate undo!, memory still holds" (count (mem/all-facts m))
               "fact — undo reverts the record, never the recall")
      (println "recall \"churn\" →" (mapv :fact (mold/recall m "churn" {}))))

    ;; last, because it ends by closing the store: a real restart, not a second
    ;; handle. Datalevin refuses two LMDB connections to one directory in one
    ;; process, so the only honest way to show the log outliving its process is
    ;; to close the store, reopen the SAME directory, and replay from disk.
    ;; Nothing below this reads `store` again.
    (banner "persistence — the substrate survives restart")
    (let [n   (count (sub/history store))
          st  (sub/state store)
          _   (dlv/close! store)
          dir (str (sub/data-dir) "/substrate")
          again (dlv/datalevin-store dir)]
      (println n "events on disk;" (count (sub/history again))
               "replayed into a fresh store — identical state:" (= st (sub/state again)))
      ;; and closed again: an open LMDB env is a live resource that keeps the
      ;; JVM from exiting, and a headless demo has to end.
      (dlv/close! again))

    (banner "done")
    (println "layers 1 (event log+undo), 2 (content), 4 (mold) hold; recall stubbed; shell next\n")))
