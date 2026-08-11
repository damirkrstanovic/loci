(ns loci.repair-test
  "The 2026-07-23 repair, end to end, against a store that has the damage in it.

   The fixture is a REPLAY, not a description: `damaged-store` commits the same
   events the real log holds — three astronomy deep dives, a table and two
   findings documents celled into them, and then the same three ids `:put` again
   three hours later. Everything the repair reads, it reads out of that log, so
   a test that passes here is a test against the shape the real store is in.

   Nothing in this namespace opens `data/`. The substrate half runs on
   `sub/fresh-store`; the memory half writes to a temp file removed in a
   `finally`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.repair :as repair]
            [loci.server :as server]
            [loci.substrate :as sub]))

;; ---------------------------------------------------------------------------
;; the damage, replayed
;; ---------------------------------------------------------------------------

(defn- body-of
  "A findings body of exactly `n` characters. The length is what the repair
   asserts against — it is what the forensics measured — and the marker is so a
   test can tell one restored document from another."
  [marker n]
  (let [s (str marker " ")]
    (subs (apply str (repeat (inc (quot n (count s))) s)) 0 n)))

(def dd-16-body (body-of "inner-outer" 1416))
(def dd-17-body (body-of "moons" 2442))

(def table-rows
  [{:planet "Mercury" :kind "terrestrial"} {:planet "Jupiter" :kind "gas giant"}])

(defn- space-ev [id title intent parent prompt]
  {:op :put :id id
   :value {:id id :kind :space :title title
           :value {:intent intent :cells []
                   :spawned-by {:space parent :prompt prompt}}}})

(defn- doc-tx [space did title body cells]
  {:op :tx :events [{:op :put :id did :value {:id did :kind :doc :title title :value body}}
                    {:op :assoc :id space :path [:value :cells] :value cells}]})

(defn damaged-store
  "The store as 2026-07-23 left it.

   Events 56–62 create the astronomy dives; 65–71 re-mint the same three ids
   onto semiconductor notebooks. `space:dd-15` is here so that max+1 has to
   clear a notebook that is not one of the three, and `space:semis` so the
   overwriting notebooks have the parent they really had."
  []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:cosmos"
                     :value {:id "space:cosmos" :kind :space :title "Cosmos"
                             :value {:intent "The solar system." :cells []}}})
    (sub/commit! st {:op :put :id "space:dd-14"
                     :value {:id "space:dd-14" :kind :space :title "Taiwan's Foundry Concentration"
                             :value {:intent "Foundries." :cells []}}})
    (sub/commit! st (space-ev "space:dd-15" "Lithography" "Lithography." "space:dd-14" "euv?"))

    ;; ev 56 — "Inner vs. Outer Planets"
    (sub/commit! st (space-ev "space:dd-16" "Inner vs. Outer Planets"
                              "Compare the terrestrial and giant planets across size, composition, and orbital characteristics."
                              "space:cosmos"
                              "How do the physical and orbital properties of terrestrial planets differ from gas and ice giants?"))
    ;; ev 57 — the table, celled in
    (sub/commit! st {:op :tx :events
                     [{:op :put :id "tbl:extract-14-588"
                       :value {:id "tbl:extract-14-588" :kind :table
                               :title "Terrestrial vs Gas Giant vs Ice Giant Planet Properties"
                               :value table-rows}}
                      {:op :assoc :id "space:dd-16" :path [:value :cells]
                       :value [{:ref "tbl:extract-14-588"}]}]})
    ;; ev 58 — the findings, 1,416 chars
    (sub/commit! st (doc-tx "space:dd-16" "find:dd-16-1"
                            "Findings — How do the physical and orbital properties o…"
                            dd-16-body
                            [{:ref "tbl:extract-14-588"} {:ref "find:dd-16-1"}]))
    ;; ev 59/60 — "Planetary Moons" and its findings, 2,442 chars
    (sub/commit! st (space-ev "space:dd-17" "Planetary Moons"
                              "Explore the distribution of moons across planets and why giants dominate the moon count."
                              "space:cosmos"
                              "What explains the steep increase in moon counts from terrestrial to giant planets?"))
    (sub/commit! st (doc-tx "space:dd-17" "find:dd-17-1"
                            "Findings — What explains the steep increase in moon cou…"
                            dd-17-body [{:ref "find:dd-17-1"}]))
    ;; ev 61/62 — "Orbital Period Scaling" and a findings document committed EMPTY
    (sub/commit! st (space-ev "space:dd-18" "Orbital Period Scaling"
                              "Investigate how orbital period scales with distance from the Sun and the implications for planetary years."
                              "space:cosmos"
                              "How does orbital period relate to distance from the Sun across the solar system?"))
    (sub/commit! st (doc-tx "space:dd-18" "find:dd-18-1"
                            "Findings — How does orbital period relate to distance f…"
                            "" [{:ref "find:dd-18-1"}]))

    ;; …three hours later, the same three ids again
    (sub/commit! st (space-ev "space:dd-16" "Geopolitical Shock Scenarios"
                              "Model the economic and market consequences of a Taiwan Strait blockade."
                              "space:dd-14" "blockade?"))
    (sub/commit! st (doc-tx "space:dd-16" "find:dd-16-1"
                            "Findings — What are the estimated global GDP and equity…"
                            (body-of "gdp" 3384) [{:ref "find:dd-16-1"}]))
    (sub/commit! st (space-ev "space:dd-17" "TSMC's Global Fab Buildout"
                              "Where TSMC is building." "space:dd-14" "fabs?"))
    (sub/commit! st (doc-tx "space:dd-17" "find:dd-17-1"
                            "Findings — By 2030, what fraction of TSMC's advanced no…"
                            (body-of "fabs" 2917) [{:ref "find:dd-17-1"}]))
    (sub/commit! st (space-ev "space:dd-18" "Customer Diversification Challenges"
                              "Who buys the chips." "space:dd-14" "customers?"))
    (sub/commit! st (doc-tx "space:dd-18" "find:dd-18-1"
                            "Findings — What technical and economic barriers prevent…"
                            (body-of "customers" 2129) [{:ref "find:dd-18-1"}]))
    st))

(defn- restored-ok! [st]
  (let [r (repair/restore-astronomy-dives! st {:dry-run? false})]
    (is (:ok? r) (str "restore refused: " (:error r)))
    r))

;; ---------------------------------------------------------------------------
;; the memory fixture
;; ---------------------------------------------------------------------------

(def astronomy-facts
  "The fifteen, with the `:ts` and `:strength` the real log carries. The point
   of pinning real timestamps is that `remember` would replace every one of
   them with `System/currentTimeMillis`, and an assertion against a value the
   fixture invented would not notice."
  (concat
   (map-indexed (fn [i t] {:id (str "mem-" (+ 23 i)) :fact t :entities ["jupiter"]
                           :source {:obj "find:dd-16-1" :space "space:dd-16"}
                           :ts (+ 1784830205451 i) :strength 1})
                ["Terrestrial planets are rocky." "Gas giants are ~90% hydrogen."
                 "Terrestrial planets orbit close to the Sun."
                 "Terrestrial planets have few moons." "Gas giants rotate fast."])
   (map-indexed (fn [i t] {:id (str "mem-" (+ 28 i)) :fact t :entities ["neptune"]
                           :source {:obj "find:dd-17-1" :space "space:dd-17"}
                           :ts (+ 1784830234578 i) :strength 1})
                ["Giant planets have large Hill spheres." "Neptune's Hill sphere is largest."
                 "Mercury and Venus have tiny Hill spheres." "Earth's Moon formed from Theia."
                 "Phobos and Deimos are captured asteroids."])
   (map-indexed (fn [i t] {:id (str "mem-" (+ 33 i)) :fact t :entities ["kepler's third law"]
                           :source {:obj "find:dd-18-1" :space "space:dd-18"}
                           :ts (+ 1784830248504 i) :strength 1})
                ["Orbital period rises with distance." "Kepler's Third Law is T² ∝ a³."
                 "Mercury's period is 88 days." "Neptune's period is 165 years."
                 "Period and distance follow a power law."])))

(def semiconductor-facts
  "mem-42…mem-46 — five facts with the SAME `:source` as mem-23…mem-27 and no
   business moving anywhere. They are in the fixture because they are the reason
   the fifteen are named one by one rather than derived from `:source`: a
   derivation over `{:obj \"find:dd-16-1\"}` selects ten facts, and five of them
   are correctly filed."
  (map-indexed (fn [i t] {:id (str "mem-" (+ 42 i)) :fact t :entities ["taiwan"]
                          :source {:obj "find:dd-16-1" :space "space:dd-16"}
                          :ts (+ 1784840886000 i) :strength 1})
               ["A blockade could cut global GDP 2.8%." "War could cost $10 trillion."
                "The S&P 500 could fall 40%." "Intervention shortens the recession."
                "Chip industries would forgo $1.6 trillion."]))

(defn- with-memory*
  [facts f]
  (let [path (str (System/getProperty "java.io.tmpdir") "/loci-repair-" (System/nanoTime) ".edn")]
    (try
      (spit path (apply str (map #(str (pr-str %) "\n") facts)))
      (f (mem/file-memory path) path)
      (finally (.delete (io/file path))))))

(defmacro with-memory
  "A FileMemory over a temp log seeded with `facts`, always removed."
  [[m path facts] & body]
  `(with-memory* ~facts (fn [~m ~path] ~@body)))

(defn- lines [path] (vec (remove str/blank? (str/split-lines (slurp path)))))

(defn- fact-line-count [path] (count (lines path)))

;; ===========================================================================
;; Task 2 — the substrate half
;; ===========================================================================

(deftest a-dry-run-writes-nothing-and-says-what-it-would-do
  (let [st     (damaged-store)
        before (count (sub/history st))
        objs   (sub/objects st)
        r      (repair/restore-astronomy-dives! st)]
    (is (:ok? r))
    (is (:dry-run? r) "dry-run? must be the DEFAULT, not something a caller opts into")
    (is (= before (count (sub/history st))) "a dry run committed an event")
    (is (= objs (sub/objects st)) "a dry run changed an object")
    ;; and it is a plan, not an empty acknowledgement
    (is (= 3 (count (get-in r [:plan :notebooks]))))
    (is (= 5 (get-in r [:plan :events])) "two documents and three notebooks, in one :tx")
    (is (= 1 (get-in r [:plan :commits])))
    (is (= ["Inner vs. Outer Planets" "Planetary Moons" "Orbital Period Scaling"]
           (mapv :title (get-in r [:plan :notebooks]))))
    (is (= ["space:dd-16" "space:dd-17" "space:dd-18"]
           (mapv :from (get-in r [:plan :notebooks]))))
    (is (= "tbl:extract-14-588" (:adopts (first (get-in r [:plan :notebooks]))))))

  ;; the explicit dry run is the same answer as the default
  (let [st (damaged-store)
        a  (repair/restore-astronomy-dives! st)
        b  (repair/restore-astronomy-dives! st {:dry-run? true})]
    (is (= (:plan a) (:plan b)))))

(deftest the-restoration-is-one-tx-that-undo-reverses
  (let [st     (damaged-store)
        before (count (sub/history st))
        objs   (sub/objects st)
        r      (restored-ok! st)]
    (is (= (inc before) (count (sub/history st)))
        "the restoration must be ONE event, or undo! reverses part of it")
    (is (= :tx (:op (peek (sub/history st)))))
    (is (= 5 (count (:events (peek (sub/history st))))))
    (is (= (inc before) (:committed r)))
    ;; …and one undo! puts the world back exactly
    (sub/undo! st)
    (is (= before (count (sub/history st))))
    (is (= objs (sub/objects st)) "undo! left something of the restoration behind")))

(deftest fresh-ids-collide-with-nothing-and-the-occupants-are-untouched
  (let [st     (damaged-store)
        before (sub/objects st)
        r      (restored-ok! st)
        ids    (concat (mapv :id (get-in r [:plan :notebooks]))
                       (keep (comp :id :findings) (get-in r [:plan :notebooks])))]
    (is (= 5 (count ids)))
    (doseq [id ids]
      (is (nil? (get before id)) (str "the repair minted " id ", which already existed")))
    ;; max+1 past space:dd-18, and never one of the three
    (is (= ["space:dd-19" "space:dd-20" "space:dd-21"]
           (mapv :id (get-in r [:plan :notebooks]))))
    (is (= ["find:dd-19-1" "find:dd-20-1"]
           (vec (keep (comp :id :findings) (get-in r [:plan :notebooks])))))
    ;; the live semiconductor notebooks and their findings are exactly as they were
    (doseq [id ["space:dd-16" "space:dd-17" "space:dd-18"
                "find:dd-16-1" "find:dd-17-1" "find:dd-18-1"]]
      (is (= (get before id) (sub/object st id))
          (str "the repair rewrote " id ", which now names live work")))
    (is (= "Geopolitical Shock Scenarios" (:title (sub/object st "space:dd-16"))))))

(deftest the-restored-notebooks-carry-what-the-log-recorded
  (let [st (damaged-store)
        r  (restored-ok! st)
        [a b c] (mapv #(sub/object st (:id %)) (get-in r [:plan :notebooks]))]
    (testing "titles, intents and lineage come from the overwritten :put verbatim"
      (is (= "Inner vs. Outer Planets" (:title a)))
      (is (= "Planetary Moons" (:title b)))
      (is (= "Orbital Period Scaling" (:title c)))
      (doseq [o [a b c]]
        (is (= :space (:kind o)))
        (is (= "space:cosmos" (get-in o [:value :spawned-by :space]))
            "a restored dive must hang off space:cosmos, not off the semiconductor parent"))
      (is (= "How do the physical and orbital properties of terrestrial planets differ from gas and ice giants?"
             (get-in a [:value :spawned-by :prompt]))
          "the spawning prompt is part of the notebook and is restored with it")
      (is (str/starts-with? (get-in a [:value :intent]) "Compare the terrestrial and giant planets")))
    (testing "the provenance of the repair is in the store, not only in a report"
      (is (= "space:dd-16" (get-in a [:value :restored-from])))
      (is (= "space:dd-17" (get-in b [:value :restored-from])))
      (is (= "space:dd-18" (get-in c [:value :restored-from]))))
    (testing "the findings documents come back with their bodies intact"
      (let [da (sub/object st (get-in r [:plan :notebooks 0 :findings :id]))
            db (sub/object st (get-in r [:plan :notebooks 1 :findings :id]))]
        (is (= 1416 (count (:value da))))
        (is (= 2442 (count (:value db))))
        (is (= dd-16-body (:value da)) "the body is the one the log holds, not a body of the right length")
        (is (= dd-17-body (:value db)))
        (is (= "Findings — How do the physical and orbital properties o…" (:title da)))
        (is (= :doc (:kind da)))))
    (testing "dd-18's findings document was committed empty and stays uncreated"
      (is (nil? (:findings (get-in r [:plan :notebooks 2]))))
      (is (= [] (nb/cells-of c))
          "restoring an empty document would manufacture provenance the log denies")
      (is (nil? (some #(when (= "" (:value %)) (:id %))
                      (remove #(= "find:dd-18-1" (:id %)) (vals (sub/objects st)))))
          "an empty document was created somewhere"))))

(deftest the-orphan-table-is-adopted-and-not-copied
  (let [st     (damaged-store)
        before (sub/object st "tbl:extract-14-588")
        n      (count (sub/objects st))
        r      (restored-ok! st)
        first-nb (sub/object st (get-in r [:plan :notebooks 0 :id]))]
    (is (= before (sub/object st "tbl:extract-14-588"))
        "the adopted table's object must be untouched — only its cell was missing")
    (is (= [{:ref "tbl:extract-14-588"} {:ref (get-in r [:plan :notebooks 0 :findings :id])}]
           (nb/cells-of first-nb))
        "the table is the first cell, ahead of the findings, as ev 57 and ev 58 left it")
    ;; a copy would be a sixth object and a sub-event naming the table
    (is (= (+ n 5) (count (sub/objects st)))
        "the restoration added an object it should have adopted")
    (is (nil? (some #(when (= "tbl:extract-14-588" (:id %)) %)
                    (:events (peek (sub/history st)))))
        "the :tx writes the table — adoption means the object is never re-put")
    (is (= 1 (count (filter #(= (:title before) (:title %)) (vals (sub/objects st)))))
        "the table's title appears twice — it was copied, not adopted")))

(deftest the-restored-lineage-widens-cosmos-and-nothing-else
  (let [st (damaged-store)
        cosmos-before (server/lineage-sources st "space:cosmos")
        semis-before  (server/lineage-sources st "space:dd-16")
        r  (restored-ok! st)
        nbs (get-in r [:plan :notebooks])
        ids (set (mapv :id nbs))
        ;; a notebook contributes its own id AND its cells' refs — that is what
        ;; makes a fact whose :source names only the findings document reachable
        cells (set (mapcat #(map :ref (:cells %)) nbs))]
    (is (= 3 (count ids)))
    (is (= #{"tbl:extract-14-588" "find:dd-19-1" "find:dd-20-1"} cells))
    ;; exact, not "contains": a scope that gained anything ELSE would be a fact
    ;; from somewhere unexpected showing up in the agent's REMEMBERED block
    (is (= (into cosmos-before (concat ids cells))
           (server/lineage-sources st "space:cosmos"))
        "space:cosmos gains exactly the three restored notebooks and their cells")
    (is (= semis-before (server/lineage-sources st "space:dd-16"))
        "the semiconductor notebook's scope must not move an inch")
    (is (not-any? (server/lineage-sources st "space:dd-16") ids))
    ;; and a sibling root is untouched, so the widening is not global
    (is (= #{"space:dd-15" "space:dd-14"} (server/lineage-sources st "space:dd-15")))))

(deftest a-store-that-does-not-look-as-expected-is-refused
  (testing "a findings body of the wrong length is a different document"
    (let [st (sub/fresh-store)]
      (sub/commit! st (space-ev "space:dd-16" "Inner vs. Outer Planets" "Compare." "space:cosmos" "q"))
      (sub/commit! st (doc-tx "space:dd-16" "find:dd-16-1" "Findings — x" (body-of "x" 1400) []))
      (let [r (repair/restore-astronomy-dives! st {:dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "1400"))
        (is (str/includes? (:error r) "1416"))
        (is (= 2 (count (sub/history st))) "a refusal wrote something"))))

  (testing "a notebook the log never held"
    (let [st (sub/fresh-store)
          r  (repair/restore-astronomy-dives! st {:dry-run? false})]
      (is (false? (:ok? r)))
      (is (str/includes? (:error r) "space:dd-16"))
      (is (zero? (count (sub/history st))))))

  (testing "the orphan table already belongs to someone"
    (let [st (damaged-store)]
      (sub/commit! st {:op :assoc :id "space:dd-15" :path [:value :cells]
                       :value [{:ref "tbl:extract-14-588"}]})
      (let [n (count (sub/history st))
            r (repair/restore-astronomy-dives! st {:dry-run? false})]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "space:dd-15"))
        (is (= n (count (sub/history st)))))))

  (testing "a half-repaired store is refused rather than finished off"
    (let [st (damaged-store)]
      (restored-ok! st)
      ;; drop one of the three restorations, which is what a partial repair looks like
      (let [victim (:id (first (filter #(= "space:dd-17" (get-in % [:value :restored-from]))
                                       (nb/notebooks st))))]
        (sub/commit! st {:op :delete :id victim})
        (let [n (count (sub/history st))
              r (repair/restore-astronomy-dives! st {:dry-run? false})]
          (is (false? (:ok? r)))
          (is (str/includes? (:error r) "half-repaired"))
          (is (= n (count (sub/history st)))))))))

(deftest running-the-restoration-twice-restores-nothing-twice
  (let [st (damaged-store)]
    (restored-ok! st)
    (let [n (count (sub/history st))
          o (sub/objects st)
          r (repair/restore-astronomy-dives! st {:dry-run? false})]
      (is (:ok? r))
      (is (:already-done? r))
      (is (= n (count (sub/history st))))
      (is (= o (sub/objects st))))))

;; ===========================================================================
;; Task 3 — the memory half
;; ===========================================================================

(deftest refiling-appends-exactly-fifteen-lines-and-changes-only-the-source
  (let [st (damaged-store)
        r  (restored-ok! st)
        [a b c] (get-in r [:plan :notebooks])]
    (with-memory [m path (concat astronomy-facts semiconductor-facts)]
      (let [before (into {} (map (juxt :id identity)) (mold/all-facts m))
            n      (fact-line-count path)
            rr     (repair/refile-astronomy-facts! st m {:dry-run? false})
            after  (into {} (map (juxt :id identity)) (mold/all-facts (mem/file-memory path)))]
        (is (:ok? rr) (str "refile refused: " (:error rr)))
        (is (= 15 (:appended rr)))
        (is (= 15 (- (fact-line-count path) n)) "exactly fifteen lines, appended")

        (testing "both halves of :source move, together"
          (doseq [id ["mem-23" "mem-24" "mem-25" "mem-26" "mem-27"]]
            (is (= {:obj (get-in a [:findings :id]) :space (:id a)} (:source (after id)))))
          (doseq [id ["mem-28" "mem-29" "mem-30" "mem-31" "mem-32"]]
            (is (= {:obj (get-in b [:findings :id]) :space (:id b)} (:source (after id)))))
          (doseq [id ["mem-33" "mem-34" "mem-35" "mem-36" "mem-37"]]
            (is (= {:obj nil :space (:id c)} (:source (after id)))
                "dd-18 had no findings document, so :obj is nil — the shape ask! writes")))

        (testing "no fact still names a notebook that is now a semiconductor one"
          (doseq [id (map :id astronomy-facts)]
            (is (not (contains? #{"space:dd-16" "space:dd-17" "space:dd-18"}
                                (get-in (after id) [:source :space]))))
            (is (not (contains? #{"find:dd-16-1" "find:dd-17-1" "find:dd-18-1"}
                                (get-in (after id) [:source :obj])))
                "rewriting :space alone leaves the fact a cell of today's space:dd-16")))

        (testing ":ts and :strength are untouched — the remember trap, asserted directly"
          (doseq [id (map :id astronomy-facts)]
            (is (= (:ts (before id)) (:ts (after id)))
                (str id "'s :ts moved — :ts is a multiplier in weight"))
            (is (= (:strength (before id)) (:strength (after id))))
            (is (= (:fact (before id)) (:fact (after id))))
            (is (= (:entities (before id)) (:entities (after id))))))

        (testing "the five correctly-filed facts on the same :source do not move"
          (doseq [f semiconductor-facts]
            (is (= f (after (:id f)))
                (str (:id f) " was re-filed — it is a semiconductor fact and was never misfiled"))))))))

(deftest refiling-preserves-an-embedding-it-finds
  (let [st (damaged-store)]
    (restored-ok! st)
    (with-memory [m path (map #(cond-> % (= "mem-23" (:id %))
                                       (assoc :vec [0.1 0.2] :model "embed-qwen3-0.6b" :dim 2))
                              astronomy-facts)]
      (is (:ok? (repair/refile-astronomy-facts! st m {:dry-run? false})))
      (let [f (first (filter #(= "mem-23" (:id %)) (mold/all-facts (mem/file-memory path))))]
        (is (= [0.1 0.2] (:vec f)) "the vector describes the text, which did not change")
        (is (= "embed-qwen3-0.6b" (:model f)))
        (is (= 2 (:dim f)))))))

(deftest a-refile-dry-run-appends-nothing
  (let [st (damaged-store)]
    (restored-ok! st)
    (with-memory [m path astronomy-facts]
      (let [text (slurp path)
            r    (repair/refile-astronomy-facts! st m)]
        (is (:ok? r))
        (is (:dry-run? r) "dry-run? must be the DEFAULT here too")
        (is (= text (slurp path)) "a dry run appended to the log")
        (is (= 15 (get-in r [:plan :appends])))
        (is (= 15 (count (get-in r [:plan :refile]))))
        (is (= "mem-23" (:id (first (get-in r [:plan :refile])))))
        (is (= {:obj "find:dd-16-1" :space "space:dd-16"} (:from (first (get-in r [:plan :refile])))))
        (is (empty? (get-in r [:plan :already])))))))

(deftest a-second-refile-is-a-no-op
  (let [st (damaged-store)]
    (restored-ok! st)
    (with-memory [m path astronomy-facts]
      (repair/refile-astronomy-facts! st m {:dry-run? false})
      (let [n (fact-line-count path)
            r (repair/refile-astronomy-facts! st m {:dry-run? false})]
        (is (:ok? r))
        (is (= 0 (:appended r)))
        (is (= 15 (count (get-in r [:plan :already]))))
        (is (empty? (get-in r [:plan :refile])))
        (is (= n (fact-line-count path)) "an already-correct fact was appended again")))))

(deftest the-record-is-read-at-write-time
  ;; A watch on the fact map bumps mem-30's :strength once, after the FIRST
  ;; fact's swap lands — so the change exists in the atom by the time mem-30's
  ;; turn comes, and not before the run started. An implementation that built
  ;; its fifteen records up front would append mem-30 with the strength it had
  ;; when the plan was made, silently rolling the bump back under the same id.
  (let [st (damaged-store)]
    (restored-ok! st)
    (with-memory [m path astronomy-facts]
      (add-watch (:!facts m) ::bump
                 (fn [k a _ _]
                   (remove-watch a k)
                   (swap! a update-in ["mem-30" :strength] inc)))
      (is (:ok? (repair/refile-astronomy-facts! st m {:dry-run? false})))
      (let [f (first (filter #(= "mem-30" (:id %)) (mold/all-facts (mem/file-memory path))))]
        (is (= 2 (:strength f))
            "mem-30 was appended from a snapshot taken before the run, not read at write time")
        (is (= "space:dd-20" (get-in f [:source :space]))     ; mem-30 is a dd-17 fact
            ;; guards the guard: if the refile had skipped mem-30 entirely, the
            ;; strength assertion above would pass on the seeded line
            "mem-30 was not re-filed at all, so the strength assertion proved nothing")))))

(deftest refiling-refuses-a-memory-that-does-not-look-as-expected
  (testing "nothing has been restored yet"
    (let [st (damaged-store)]
      (with-memory [m path astronomy-facts]
        (let [text (slurp path)
              r    (repair/refile-astronomy-facts! st m {:dry-run? false})]
          (is (false? (:ok? r)))
          (is (str/includes? (:error r) "restore-astronomy-dives!"))
          (is (= text (slurp path)))))))

  (testing "a fact whose source is neither the old one nor the new one"
    (let [st (damaged-store)]
      (restored-ok! st)
      (with-memory [m path (map #(cond-> % (= "mem-31" (:id %))
                                         (assoc :source {:obj nil :space "space:pub-geo"}))
                                astronomy-facts)]
        (let [text (slurp path)
              r    (repair/refile-astronomy-facts! st m {:dry-run? false})]
          (is (false? (:ok? r)))
          (is (str/includes? (:error r) "mem-31"))
          (is (= text (slurp path))
              "a repair that half-applies is worse than one that refuses")))))

  (testing "a fact that is not there at all"
    (let [st (damaged-store)]
      (restored-ok! st)
      (with-memory [m path (remove #(= "mem-37" (:id %)) astronomy-facts)]
        (let [text (slurp path)
              r    (repair/refile-astronomy-facts! st m {:dry-run? false})]
          (is (false? (:ok? r)))
          (is (str/includes? (:error r) "mem-37"))
          (is (= text (slurp path)))))))

  ;; A tombstone is a state this repair can genuinely meet on the real memory:
  ;; the embed worker runs `merge-similar!` on a timer, and the forensics named
  ;; mem-33/mem-34 and mem-25/mem-35 as pairs close enough to be candidates. An
  ;; absorbed fact carries no `:source` at all, so the generic "neither" branch
  ;; would also stop the run — but it would report a nil and say nothing about
  ;; where the provenance went, and the survivor is the record that now has to
  ;; be re-filed by hand.
  (testing "a fact the semantic merge absorbed"
    (let [st (damaged-store)]
      (restored-ok! st)
      (with-memory [m path (map #(if (= "mem-33" (:id %))
                                   {:id "mem-33" :fact (:fact %) :ts (:ts %) :merged-into "mem-34"}
                                   %)
                                astronomy-facts)]
        (let [text (slurp path)
              r    (repair/refile-astronomy-facts! st m {:dry-run? false})]
          (is (false? (:ok? r)))
          (is (str/includes? (:error r) "mem-33"))
          (is (str/includes? (:error r) "tombstone"))
          (is (str/includes? (:error r) "mem-34")
              "the refusal must name the survivor — that is where the provenance went")
          (is (= text (slurp path))))))))
