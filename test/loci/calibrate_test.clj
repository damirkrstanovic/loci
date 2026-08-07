(ns loci.calibrate-test
  "Calibrating the merge threshold — the procedure, not a number.

   **Nothing here touches an embedder, and nothing here touches the user's
   memory.** The corpus below is twelve sentences the author made up, each
   paired with a vector that was *constructed* rather than measured: every item
   is a unit vector built from a shared background axis, an optional topic axis
   it shares with one other item, and a private axis of its own, so the cosine
   between any two of them is a number chosen in advance. That is what makes
   the arithmetic assertable offline.

   **The fixture exists to test the procedure.** Its numbers are not evidence
   about any embedding model, and no threshold derived from it means anything
   outside this file — a real threshold can only come from a real corpus,
   embedded by the model that will be used, with the top pairs read by a person
   and labelled by hand. The one thing the fixture reproduces faithfully is the
   *shape* that defeats a naively chosen threshold: a pair of genuinely
   distinct facts (a law stated versus the same law observed — the Kepler case
   from the spec, rewritten with an invented law) that scores **above** a pair
   of true duplicates. The classes overlap, so no threshold separates them, and
   the calibration has to choose which error to make."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loci.calibrate :as cal]))

;; ---------------------------------------------------------------------------
;; the synthetic corpus
;; ---------------------------------------------------------------------------

(def ^:private dim
  "One background axis, four topic axes, one private axis per item."
  17)

(defn- unit
  "A vector of `dim` with the given weights on the given axes and zero elsewhere.

   Weights are chosen so the sum of their squares is 1, which makes the vector
   a unit vector and the cosine of two of them a plain dot product — the same
   property the real embedders happen to have (measured norm 1.0), though
   `loci.embed/cosine` divides by the norms regardless."
  [weights]
  (mapv #(double (get weights % 0.0)) (range dim)))

(defn- item
  "One fixture item. `g` is its weight on the shared background axis 0, `topic`
   the axis (1-4) it shares with its partner and `a` its weight there; the
   remainder of the unit budget goes on the item's own axis, so two items
   sharing a topic score exactly `g² + a²` and two that do not score `g₁·g₂`."
  [idx {:keys [id text group g topic a]}]
  (let [g (double g)
        a (double (or a 0.0))
        b (Math/sqrt (- 1.0 (* g g) (* a a)))]
    {:id    id
     :text  text
     :group group
     :vec   (unit (cond-> {0 g (+ 5 idx) b}
                    topic (assoc topic a)))}))

;; Targets, stated here rather than buried in the weights:
;;
;;   the two foundry sentences       0.95  duplicate
;;   the two valley sentences        0.86  duplicate
;;   the law stated vs observed      0.85  DISTINCT   <- the Kepler shape
;;   the two coffee sentences        0.84  duplicate  <- below a distinct pair
;;
;; so the highest-scoring *distinct* pair outranks a true duplicate. That is the
;; overlap, and it is the whole reason a calibration has to pick a side.
(def corpus
  (vec (map-indexed
        item
        [{:id "s-1" :group :foundry :g 0.50 :topic 1 :a (Math/sqrt 0.70)
          :text "The Aldane foundry produces about 70% of the world's memory controllers."}
         {:id "s-2" :group :foundry :g 0.50 :topic 1 :a (Math/sqrt 0.70)
          :text "About 70% of the world's memory controllers come out of the Aldane foundry."}

         {:id "s-3" :group :valley :g 0.52 :topic 2 :a (Math/sqrt 0.5896)
          :text "Villages in the Tolm valley sit almost entirely above 2,000 metres."}
         {:id "s-4" :group :valley :g 0.52 :topic 2 :a (Math/sqrt 0.5896)
          :text "Nearly every village in the Tolm valley lies above 2,000 metres."}

         {:id "s-5" :group :coffee :g 0.48 :topic 3 :a (Math/sqrt 0.6096)
          :text "Drinking coffee after four in the afternoon delays the onset of sleep."}
         {:id "s-6" :group :coffee :g 0.48 :topic 3 :a (Math/sqrt 0.6096)
          :text "Coffee taken after 4 p.m. pushes back the time it takes to fall asleep."}

         ;; The Kepler shape. Two *different* facts: the first is the law's
         ;; formulation, the second an observation the law accounts for. Merging
         ;; them keeps the observation and loses the formulation, and nothing
         ;; anywhere reports that it happened.
         {:id "s-7" :group :law-stated :g 0.55 :topic 4 :a (Math/sqrt 0.5475)
          :text "Verrin's Law states that a pendulum's period is proportional to the square root of its length."}
         {:id "s-8" :group :law-observed :g 0.55 :topic 4 :a (Math/sqrt 0.5475)
          :text "A longer pendulum swings more slowly than a short one, which is what Verrin's Law predicts."}

         {:id "s-9" :group :archive :g 0.44
          :text "The Rethy archive closes to the public on the first Monday of each month."}
         {:id "s-10" :group :ink :g 0.47
          :text "Ink made from oak gall turns brown as it oxidises."}
         {:id "s-11" :group :tram :g 0.50
          :text "The 1908 tramway ran on a gauge of 1,000 millimetres."}
         {:id "s-12" :group :marsh :g 0.53
          :text "Salt marsh reclaimed before 1850 carries no legal right of way."}])))

(def ^:private no-embedder
  "An embed fn that fails loudly. Passed wherever the corpus already carries
   vectors, so that a regression which starts calling an embedder here is a test
   failure rather than a silent network call."
  (fn [_] (throw (ex-info "the tests must not need an embedder" {}))))

(defn- about [x y] (< (Math/abs (- (double x) (double y))) 1e-9))

(defn- pair-score [pairs a b]
  (:score (first (filter #(= #{a b} #{(:a %) (:b %)}) pairs))))

;; ---------------------------------------------------------------------------

(deftest the-fixture-scores-where-it-was-built-to
  ;; Not a test of loci.calibrate so much as of the fixture: if these drift, the
  ;; assertions below stop meaning what they say they mean.
  (let [pairs (cal/pair-scores corpus)]
    (is (= 66 (count pairs)) "12 items make 66 pairs")
    (is (about 0.95 (pair-score pairs "s-1" "s-2")))
    (is (about 0.86 (pair-score pairs "s-3" "s-4")))
    (is (about 0.84 (pair-score pairs "s-5" "s-6")))
    (is (about 0.85 (pair-score pairs "s-7" "s-8")))
    (is (> (pair-score pairs "s-7" "s-8") (pair-score pairs "s-5" "s-6"))
        "the distinct pair outranks a true duplicate — the classes overlap")))

(deftest suggest-threshold-sits-above-every-distinct-pair
  (let [{:keys [threshold highest-distinct lowest-kept]}
        (cal/suggest-threshold (cal/pair-scores corpus))]
    (is (some? threshold))
    (is (about 0.85 highest-distinct) "the law-stated/law-observed pair")
    (is (about 0.86 lowest-kept) "the valley pair, the lowest duplicate still keepable")
    (is (> threshold highest-distinct)
        "a threshold at or below the highest distinct pair merges it")
    (is (<= threshold lowest-kept)
        "and one above the lowest duplicate it claims to keep does not keep it")))

(deftest overlap-costs-recall-and-never-safety
  (let [{:keys [threshold false-merges kept missed missed-pairs overlap?]}
        (cal/suggest-threshold (cal/pair-scores corpus))]
    (is (true? overlap?) "the corpus is built so the classes overlap")
    (is (zero? false-merges)
        "erring high: not one pair labelled distinct is at or above the threshold")
    (is (= 2 kept) "the foundry pair at 0.95 and the valley pair at 0.86")
    (is (= 1 missed) "the coffee pair at 0.84, which sits under a distinct pair")
    (is (= [#{"s-5" "s-6"}] (mapv #(set [(:a %) (:b %)]) missed-pairs))
        "and it says which one it gave up, by id")
    (is (every? string? (mapcat (juxt :a-text :b-text) missed-pairs))
        "with the texts, so the miss can be judged rather than counted")
    ;; the arithmetic the two claims above rest on, spelled out
    (is (zero? (count (filter #(and (= :distinct (:label %)) (>= (:score %) threshold))
                              (cal/pair-scores corpus)))))))

(deftest a-corpus-that-does-not-separate-gets-no-threshold
  (testing "every duplicate under the highest distinct pair"
    ;; The coffee pair (0.84, duplicate) is the only duplicate labelled, and it
    ;; scores under the law pair (0.85, distinct). There is no cut.
    (let [bare (mapv #(dissoc % :group) corpus)
          {:keys [threshold missed note false-merges]}
          (cal/suggest-threshold
           (cal/pair-scores bare {#{"s-5" "s-6"} :duplicate
                                  #{"s-7" "s-8"} :distinct}))]
      (is (nil? threshold) "a number here would merge the law pair")
      (is (zero? false-merges))
      (is (= 1 missed))
      (is (str/includes? note "0.850"))
      (is (str/includes? note "does not separate"))))
  (testing "nothing labelled duplicate at all"
    (let [bare (mapv #(dissoc % :group) corpus)
          {:keys [threshold note]}
          (cal/suggest-threshold (cal/pair-scores bare {#{"s-7" "s-8"} :distinct}))]
      (is (nil? threshold))
      (is (str/includes? note ":duplicate")))))

(deftest distribution-reports-the-whole-shape
  (let [d (cal/distribution (map :score (cal/pair-scores corpus)))]
    (is (= 66 (:n d)))
    (is (every? #(number? (get d %)) [:min :median :mean :p90 :p95 :p99 :max]))
    (is (about 0.95 (:max d)))
    (is (<= (:min d) (:median d) (:p90 d) (:p95 d) (:p99 d) (:max d)))
    (is (<= (:min d) (:mean d) (:max d)))
    (is (nil? (cal/distribution [])) "there is no distribution of nothing")))

(deftest top-pairs-carries-the-texts-a-person-has-to-read
  (let [top (cal/top-pairs corpus 3)]
    (is (= 3 (count top)))
    (is (= [#{"s-1" "s-2"} #{"s-3" "s-4"} #{"s-7" "s-8"}]
           (mapv #(set [(:a %) (:b %)]) top))
        "highest first — and the third is the distinct pair, not a duplicate")
    (is (str/includes? (:a-text (nth top 2)) "Verrin's Law states"))
    (is (str/includes? (:b-text (nth top 2)) "swings more slowly"))))

(deftest a-corpus-too-small-is-refused-not-answered
  (let [{:keys [error] :as r} (cal/calibrate (take 6 corpus) {:embed-fn no-embedder})]
    (is (string? error) "six items get a message, not a number")
    (is (str/includes? error "6"))
    (is (str/includes? error (str cal/min-corpus)))
    (is (nil? (:threshold r)))
    (is (nil? (:suggestion r)))
    (is (nil? (:distribution r))))
  (testing "and the full corpus is not"
    (is (nil? (:error (cal/calibrate corpus {:embed-fn no-embedder}))))))

(deftest calibrate-needs-no-embedder-when-the-corpus-carries-vectors
  (let [r (cal/calibrate corpus {:embed-fn no-embedder})]
    (is (= 12 (:n r)))
    (is (= 66 (:pairs r)))
    (is (some? (:distribution r)))
    (is (zero? (:false-merges (:suggestion r))))
    (is (string? (cal/report r)))
    (is (str/includes? (cal/report r) "0.855") "the report states the number it suggests")))

(deftest vectors-of-two-dimensions-are-refused
  ;; Two models' vectors in one corpus: cosine returns 0.0 on a length mismatch,
  ;; so every cross-model pair would look maximally dissimilar and the threshold
  ;; would be computed off a distribution that is mostly a lie.
  (let [mixed (assoc-in (vec corpus) [0 :vec] [0.1 0.2 0.3])
        {:keys [error]} (cal/calibrate mixed {:embed-fn no-embedder})]
    (is (string? error))
    (is (str/includes? error "3"))
    (is (str/includes? error (str dim)))))

(deftest an-unlabelled-corpus-gets-no-threshold
  ;; A threshold cannot come from scores alone. Without labels the report gives
  ;; the distribution and the pairs to read, and says so.
  (let [bare (mapv #(dissoc % :group) corpus)
        r    (cal/calibrate bare {:embed-fn no-embedder})]
    (is (nil? (:error r)))
    (is (nil? (:suggestion r)))
    (is (str/includes? (cal/report r) "no threshold"))))

(deftest calibrate-bang-has-no-default-path
  ;; `loci.memory/memory` resolves to the user's real memory.edn. A default
  ;; argument here would point a calibration run at it by accident.
  (is (= [['path] ['path 'opts]] (:arglists (meta #'cal/calibrate!)))
      "the path is a required argument, and stays one")
  (is (thrown-with-msg? IllegalArgumentException #"path"
                        (cal/calibrate! "  " {:embed-fn no-embedder}))))

(deftest calibrate-bang-does-not-write-to-the-file-it-reads
  (let [path (str (System/getProperty "java.io.tmpdir")
                  "/loci-calibrate-test-" (System/nanoTime) "/memory.edn")
        facts (map-indexed (fn [i c] {:id (:id c) :fact (:text c) :entities [] :ts i
                                      :strength 1 :vec (:vec c) :model "fixture-model"
                                      :dim dim})
                           corpus)]
    (io/make-parents (io/file path))
    (spit path (str/join "\n" (map pr-str facts)))
    (let [before (slurp path)
          ;; A memory file records no verdicts about its own pairs, so the
          ;; labels arrive from the person who read them — which is the whole
          ;; procedure, and the reason calibrate! prints a threshold only here.
          out    (with-out-str
                   (cal/calibrate! path {:embed-fn no-embedder
                                         :model    "fixture-model"
                                         :labels   {#{"s-1" "s-2"} :duplicate
                                                    #{"s-3" "s-4"} :duplicate
                                                    #{"s-5" "s-6"} :duplicate
                                                    #{"s-7" "s-8"} :distinct}}))]
      (is (= before (slurp path)) "calibration reads; it must never append")
      (is (str/includes? out "0.855") "the same threshold the labelled corpus gives")
      (is (str/includes? out "fixture-model")
          "and it names the model the number belongs to"))))

(deftest an-unlabelled-memory-file-gets-the-procedure-not-a-number
  (let [path (str (System/getProperty "java.io.tmpdir")
                  "/loci-calibrate-test-" (System/nanoTime) "/memory.edn")
        facts (map-indexed (fn [i c] {:id (:id c) :fact (:text c) :entities [] :ts i
                                      :strength 1 :vec (:vec c) :model "fixture-model"
                                      :dim dim})
                           corpus)]
    (io/make-parents (io/file path))
    (spit path (str/join "\n" (map pr-str facts)))
    (let [out (with-out-str
                (cal/calibrate! path {:embed-fn no-embedder :model "fixture-model"}))]
      (is (str/includes? out "no threshold is suggested"))
      (is (str/includes? out ":labels") "and says how to supply what is missing"))))
