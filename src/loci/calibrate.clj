(ns loci.calibrate
  "Measuring the semantic-merge threshold, because a threshold belongs to one
   embedding model and does not travel to another.

   Merge collapses two facts whose vectors are close enough that they are taken
   to be the same fact. \"Close enough\" is a cosine, and a cosine only means
   something inside the space the model that produced it defines. Measured
   2026-08-07 on the same 80 facts, `embed-qwen3-0.6b` and `embed-qwen3-4b` gave
   almost identical *global* distributions — median 0.291 against 0.298, p99
   identical at 0.741 — while the tail moved: one pair of genuinely distinct
   facts (Kepler's Third Law stated, against an observation the law explains)
   went from 0.829 to 0.868. A 0.85 threshold calibrated on the first and
   carried to the second merges them and destroys the law's formulation, and
   nothing anywhere reports that it happened.

   So the threshold is re-measured per model, and this namespace is how.

   The procedure it implements is the one that was actually followed, not an
   automation of it:

     1. embed the corpus with the model that will be used
     2. score every pair and look at the distribution
     3. **read the top pairs** and label each one duplicate or distinct
     4. put the threshold above every distinct pair

   Step 3 is a person's, and there is no way around it. Scores alone cannot say
   which pairs are the same fact, so `calibrate` reports a threshold only for
   pairs it was told the truth about, and says plainly when it was told nothing.

   The classes overlap in practice — under the 0.6b, two true duplicates scored
   0.838 and 0.832 while the false Kepler pair scored 0.829 — so no threshold
   separates them and the choice is which error to make. `suggest-threshold`
   errs high every time: a missed duplicate costs one redundant fact, a wrong
   merge silently destroys a distinct one."
  (:require [clojure.string :as str]
            [loci.embed :as embed]
            [loci.memory :as mem])
  (:import [java.util Locale]))

(def min-corpus
  "Fewer items than this and there is nothing to calibrate against.

   Ten items make 45 pairs, which is already a thin basis for a percentile — but
   below it the numbers stop being a measurement of anything. Refusing is the
   point: a threshold computed from four sentences is a number with a decimal
   point and no evidence behind it, and it would be used exactly as if it had
   evidence behind it."
  10)

(def batch-size
  "Texts per embedding request, matching `loci.memory`. A whole corpus in one
   body works today and stops working at some size nobody will have measured."
  32)

(defn- f3 [x]
  (String/format Locale/ROOT "%.3f" (into-array Object [(double x)])))

;; ---------------------------------------------------------------------------
;; scoring
;; ---------------------------------------------------------------------------

(defn pair-scores
  "Every unordered pair of `items`, scored by cosine, highest first.

   An item is `{:id :text :vec}` and may carry `:group` — two items in the same
   group are the same fact, two in different groups are not. A pair gets a
   `:label` of `:duplicate` or `:distinct` only when the truth about it is
   known: from `labels` (a map of `#{id-a id-b}` to the label) if it is there,
   otherwise from `:group` when **both** items carry one. A pair nobody has
   labelled comes back with no `:label` rather than a guessed one, because a
   guess here becomes a threshold that looks measured.

   The texts ride along on every pair. The step this exists to support is a
   person reading the pairs, and a list of ids is not readable."
  ([items] (pair-scores items {}))
  ([items labels]
   (let [v (vec items)
         n (count v)]
     (->> (for [i (range n)
                j (range (inc i) n)
                :let [a (nth v i)
                      b (nth v j)
                      given (get labels #{(:id a) (:id b)})
                      grouped (when (and (:group a) (:group b))
                                (if (= (:group a) (:group b)) :duplicate :distinct))]]
            (cond-> {:a      (:id a)
                     :b      (:id b)
                     :a-text (:text a)
                     :b-text (:text b)
                     :score  (embed/cosine (:vec a) (:vec b))}
              (or given grouped) (assoc :label (or given grouped))))
          (sort-by (juxt (comp - :score) :a :b))
          vec))))

(defn distribution
  "min/median/mean/p90/p95/p99/max over `scores`, plus `:n`. nil for none —
   there is no distribution of nothing, and a map of zeros would be reported as
   though it were one.

   Percentiles are nearest-rank, so every number in the table except the mean is
   an actual pair's score rather than an interpolation between two of them. That
   matters when the table is being read to choose a cutoff: the value can be
   looked up in the pair list."
  [scores]
  (let [xs (vec (sort (map double scores)))
        n  (count xs)]
    (when (pos? n)
      (let [nr #(nth xs (min (dec n) (max 0 (dec (long (Math/ceil (* % n)))))))]
        {:n      n
         :min    (first xs)
         :median (nr 0.50)
         :mean   (/ (reduce + 0.0 xs) n)
         :p90    (nr 0.90)
         :p95    (nr 0.95)
         :p99    (nr 0.99)
         :max    (peek xs)}))))

(defn top-pairs
  "The `n` highest-scoring pairs of `items`, with their texts — what a person
   reads in order to label them."
  ([items n] (top-pairs items n {}))
  ([items n labels] (vec (take n (pair-scores items labels)))))

;; ---------------------------------------------------------------------------
;; the threshold
;; ---------------------------------------------------------------------------

(defn suggest-threshold
  "A merge threshold for `pairs`, chosen so that **no pair labelled `:distinct`
   reaches it**.

   Merge is `>= threshold`. The suggestion is the midpoint between the highest
   distinct pair and the lowest duplicate that can still be kept above it, so it
   clears every distinct pair by construction and keeps every duplicate that any
   safe threshold could keep. Rounding it *up* within the reported gap is fine
   and is what a person should do — 0.868/0.887 became 0.88 that way.

   Erring high is the rule, not a tuning choice. The labelled pairs are a
   sample: under the 0.6b, two true duplicates sat at 0.838 and 0.832 with a
   false pair at 0.829 between the classes, so a threshold pushed down to
   capture them merges something distinct. A missed duplicate leaves one
   redundant fact in memory. A wrong merge deletes a fact and records that it
   meant to.

   Returns

     {:threshold t              nil when no safe threshold keeps any duplicate
      :false-merges 0           distinct pairs at or above t — computed, not asserted
      :kept n :missed n         duplicates above and below t
      :missed-pairs [...]       the ones given up, with their texts
      :highest-distinct s       the pair the threshold had to clear
      :lowest-kept s            the cheapest duplicate it still keeps
      :overlap? bool            true when some duplicate scores under some distinct pair
      :labelled n :note \"…\"}

   with `:note` present whenever the answer is weaker than it looks."
  [pairs]
  (let [labelled (filterv :label pairs)
        dup      (filterv #(= :duplicate (:label %)) labelled)
        dis      (filterv #(= :distinct (:label %)) labelled)
        hd       (when (seq dis) (apply max (map :score dis)))
        keepable (if hd (filterv #(> (:score %) hd) dup) dup)
        lowest   (when (seq keepable) (apply min (map :score keepable)))
        overlap? (boolean (and hd (some #(<= (:score %) hd) dup)))
        base     {:labelled (count labelled)
                  :duplicates (count dup)
                  :distincts (count dis)
                  :highest-distinct hd
                  :overlap? overlap?}]
    (cond
      (empty? dup)
      (assoc base :threshold nil :false-merges 0 :kept 0 :missed 0 :missed-pairs []
             :lowest-kept nil
             :note (str "no pair is labelled :duplicate, so there is nothing a threshold "
                        "could be measured against — a cutoff above the highest distinct "
                        "pair would merge nothing and prove nothing."))

      (nil? lowest)
      (assoc base :threshold nil :false-merges 0 :kept 0
             :missed (count dup)
             :missed-pairs (vec (sort-by (comp - :score) dup))
             :lowest-kept nil
             :note (str "every pair labelled :duplicate scores at or below the highest "
                        ":distinct pair (" (f3 hd) "), so no threshold merges a duplicate "
                        "without also merging something distinct. Merge nothing, or label "
                        "more pairs — this corpus does not separate."))

      :else
      (let [t   (if hd (/ (+ hd lowest) 2.0) lowest)
            ;; counted by applying the threshold, not carried over from the
            ;; construction above: the claim "zero false merges" has to be a
            ;; measurement of the number being returned.
            fm  (count (filter #(>= (:score %) t) dis))
            in  (filterv #(>= (:score %) t) dup)
            out (filterv #(< (:score %) t) dup)]
        (cond-> (assoc base
                       :threshold t
                       :false-merges fm
                       :kept (count in)
                       :missed (count out)
                       :missed-pairs (vec (sort-by (comp - :score) out))
                       :lowest-kept lowest)
          (nil? hd)
          (assoc :note (str "no pair is labelled :distinct, so nothing constrains this "
                            "threshold from below. It is the lowest duplicate and no more "
                            "than that — label the pairs just under it before trusting it.")))))))

;; ---------------------------------------------------------------------------
;; a corpus
;; ---------------------------------------------------------------------------

(defn- embed-missing
  "Give every item without a `:vec` one, in batches. Returns `{:items …}` or
   `{:error …}` — never a partial corpus, because a corpus missing the items an
   embedder choked on would still produce a confident-looking distribution."
  [items embed-fn]
  (let [need (filterv #(empty? (:vec %)) items)]
    (if (empty? need)
      {:items (vec items)}
      (loop [batches (partition-all batch-size need), acc {}, model nil]
        (if-let [b (first batches)]
          (let [r (embed-fn (mapv :text b))]
            (cond
              (:off r)   {:error (str "no embedding endpoint is configured, so " (count need)
                                      " item(s) cannot be embedded — set LOCI_EMBED_ENDPOINT")}
              (:error r) {:error (:error r)}
              :else      (recur (next batches)
                                (into acc (map vector (map :id b) (:vectors r)))
                                (or model (:model r)))))
          {:items (mapv #(if (empty? (:vec %)) (assoc % :vec (get acc (:id %))) %) items)
           :model model})))))

(defn calibrate
  "Score a corpus and, if it is labelled, suggest a threshold.

   `items` are `{:id :text}` maps; each may already carry `:vec` (and `:group`,
   see `pair-scores`). Anything without a vector is embedded through `:embed-fn`,
   which defaults to `loci.embed/embed-texts` and is a seam so that a corpus
   arriving with its vectors — as in the tests — needs no embedder at all.

   Options: `:embed-fn`, `:labels` (see `pair-scores`), `:top` (how many pairs
   to hand back for reading, default 12), `:model`, `:dim` (recorded in the
   report only).

   Returns `{:error \"…\"}`, and nothing else, when it will not answer: too few
   items, an embedder that could not be reached, or vectors of two different
   lengths in one corpus. That last one is not fussiness — `loci.embed/cosine`
   returns 0.0 for a length mismatch, so a corpus holding two models' vectors
   scores every cross-model pair as maximally dissimilar and the distribution
   becomes a description of the mixture rather than of the model.

   Otherwise `{:n :pairs :model :dim :distribution :top :suggestion}`, with
   `:suggestion` absent when no pair was labelled — a threshold cannot be
   computed from scores alone and this does not pretend otherwise."
  ([items] (calibrate items {}))
  ([items {:keys [embed-fn labels top model dim]
           :or   {embed-fn embed/embed-texts labels {} top 12}}]
   (let [items (vec items)]
     (if (< (count items) min-corpus)
       {:error (str "a corpus of " (count items) " item(s) cannot calibrate a threshold — "
                    "at least " min-corpus " are needed. Refusing rather than returning a "
                    "number computed from nothing.")}
       (let [e (embed-missing items embed-fn)]
         (if (:error e)
           (select-keys e [:error])
           (let [items (:items e)
                 dims  (sort (set (map (comp count :vec) items)))]
             (cond
               (some zero? dims)
               {:error "at least one item has no vector after embedding"}

               (< 1 (count dims))
               {:error (str "the corpus mixes vector dimensions " (pr-str (vec dims))
                            " — vectors from two models are not comparable, and cosine "
                            "answers 0.0 for a length mismatch rather than failing. "
                            "Re-embed every item with one model first.")}

               :else
               (let [pairs (pair-scores items labels)
                     sugg  (when (some :label pairs) (suggest-threshold pairs))]
                 (cond-> {:n           (count items)
                          :pairs       (count pairs)
                          :model       (or model (:model e))
                          :dim         (or dim (first dims))
                          :distribution (distribution (map :score pairs))
                          :top         (vec (take top pairs))}
                   sugg (assoc :suggestion sugg)))))))))))

;; ---------------------------------------------------------------------------
;; the report
;; ---------------------------------------------------------------------------

(defn- pair-lines [pairs]
  (str/join "\n"
            (mapcat (fn [{:keys [a b a-text b-text score label]}]
                      [(str "    " (f3 score) "  " a "  " a-text)
                       (str "           " b "  " b-text
                            (when label (str "   [" (name label) "]")))])
                    pairs)))

(defn report
  "The calibration as text: what was measured, the percentile table, the pairs a
   person has to read, and either a threshold or the reason there is none."
  [{:keys [error n pairs model dim distribution top suggestion]}]
  (if error
    (str "loci calibration refused\n\n  " error "\n")
    (str/join
     "\n"
     (concat
      ["loci calibration — a merge threshold for ONE embedding model"
       ""
       (str "  model      " (or model "(unknown)"))
       (str "  dimension  " (or dim "(unknown)"))
       (str "  facts      " n)
       (str "  pairs      " pairs)
       ""
       "  cosine over every pair (percentiles nearest-rank, so each is a real pair)"
       ""
       (str "    min      " (f3 (:min distribution)))
       (str "    median   " (f3 (:median distribution)))
       (str "    mean     " (f3 (:mean distribution)))
       (str "    p90      " (f3 (:p90 distribution)))
       (str "    p95      " (f3 (:p95 distribution)))
       (str "    p99      " (f3 (:p99 distribution)))
       (str "    max      " (f3 (:max distribution)))
       ""
       (str "  the " (count top) " highest-scoring pairs — read these; they are what a")
       "  threshold decides between"
       ""
       (pair-lines top)
       ""]
      (if-let [{:keys [threshold false-merges kept missed missed-pairs
                       highest-distinct lowest-kept overlap? labelled note]} suggestion]
        (concat
         (if threshold
           [(str "  suggested threshold: " (f3 threshold) "   (merge is >= this)")
            ""
            ;; stated from the count, never from the construction. A version of
            ;; suggest-threshold that stopped erring high would otherwise print
            ;; "above every pair labelled distinct" over a number that is not.
            (if (zero? false-merges)
              (str "    above every pair labelled distinct (highest "
                   (f3 highest-distinct) ")")
              (str "    !! " false-merges " pair(s) labelled DISTINCT are at or above it "
                   "(highest " (f3 highest-distinct) ") — this threshold merges facts "
                   "that are not the same fact"))
            (str "    at or below the lowest duplicate it keeps (" (f3 lowest-kept) ")")
            (str "    false merges: " false-merges "    duplicates kept: " kept
                 "    missed: " missed)
            (str "    labelled pairs read: " labelled
                 (when overlap? "    the classes OVERLAP — a distinct pair outranks a duplicate"))]
           [(str "  no threshold is suggested.")])
         (when (seq missed-pairs)
           (concat ["" "    given up (a duplicate under a distinct pair — costs one"
                    "    redundant fact, which is the cheap error):" ""]
                   [(pair-lines missed-pairs)]))
         (when note ["" (str "    " note)])
         [""
          "  Round up within the gap if you like; do not round down. This number is"
          "  valid for the model named above and for no other."])
        ["  no threshold is suggested: not one pair here is labelled."
         ""
         "  A threshold cannot be computed from scores alone — nothing in a cosine"
         "  says whether two sentences are the same fact. Read the pairs above,"
         "  decide for each, and pass the verdicts back in:"
         ""
         "    :labels {#{\"mem-12\" \"mem-40\"} :duplicate"
         "             #{\"mem-3\"  \"mem-71\"} :distinct}"
         ""])))))

(defn calibrate!
  "Calibrate against the memory file at `path` and print the report.

   **`path` is required and has no default.** `loci.memory/memory` resolves to
   the user's real `memory.edn`; a default here would aim a calibration run at
   it by accident, and this function embeds every fact it is given.

   It reads and never writes. Vectors already stored under the configured model
   are reused; anything else — no vector, or one stamped with a different model
   — is embedded in memory for this run only and **not** persisted. Calibration
   is a measurement, so it leaves the file exactly as it found it.

   Options are `calibrate`'s, plus `:model` to name the model in the report and
   to decide which stored vectors are still usable (default: the configured
   `loci.embed/embed-model`)."
  ([path] (calibrate! path {}))
  ([path opts]
   (when (str/blank? path)
     (throw (IllegalArgumentException.
             "calibrate! needs the path of a memory file; it has no default on purpose")))
   (let [model (or (:model opts) (embed/embed-model))
         facts (mem/all-facts (mem/file-memory path))
         items (mapv (fn [f]
                       (cond-> {:id (:id f) :text (:fact f)}
                         ;; a vector from another model is not reusable here for
                         ;; the same reason it is not reusable in recall: two
                         ;; embedding spaces are not comparable
                         (and (seq (:vec f)) (= model (:model f)))
                         (assoc :vec (:vec f))))
                     facts)
         r (calibrate items (assoc opts :model model))]
     (println (report r))
     r)))

(defn -main
  "clojure -M:calibrate <path-to-memory.edn>

   Prints the distribution and the top pairs. It does **not** print a threshold:
   labelling the pairs is a person's step, and the labels come back through
   `calibrate!`'s `:labels` option from a REPL."
  [& args]
  (let [path (first args)]
    (when (str/blank? path)
      (binding [*out* *err*]
        (println "usage: clojure -M:calibrate <path-to-memory.edn>")
        (println "       (the path is required — there is no default, by design)"))
      (System/exit 2))
    (calibrate! path)
    (shutdown-agents)))
