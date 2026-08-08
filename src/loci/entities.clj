(ns loci.entities
  "The entity projection — a subject for the facts to hang on.

   An entity is a **normalized name**, the live facts that mention it, and
   nothing stored. This whole namespace is a pure function of a fact sequence,
   rebuildable from memory.edn alone exactly as `touched` is rebuildable from
   `events`, and it holds no store, no memory and no atom: the half that can
   walk the substrate passes facts in, and this half answers.

   **Normalization is lowercase + trimmed + internal whitespace collapsed, and
   nothing cleverer.** It must be *stable*: changing it re-identifies every
   entity, so the rule is written here and in the design, and changes only with
   a migration.

   Facts are NEVER rewritten to point at entity ids. They keep their `:entities`
   strings, and that is the property that makes materialization safe to undo."
  (:require [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(defn normalize
  "The stable identity of an entity name: lowercase, trimmed, internal
   whitespace collapsed. nil and blank normalize to \"\"."
  [s]
  (-> (str s) str/trim str/lower-case (str/replace #"\s+" " ")))

(defn index
  "name → set of fact ids, over the **live** facts you pass in.

   Tombstones are the caller's problem — `mem/all-facts` already removes them —
   but a record with no `:entities` contributes nothing rather than throwing, so
   a caller that slips one in degrades quietly instead of failing a page load.
   A blank name is dropped: it is not a subject."
  [facts]
  (reduce (fn [m f]
            (reduce (fn [m e]
                      (let [n (normalize e)]
                        (if (str/blank? n) m (update m n (fnil conj #{}) (:id f)))))
                    m (:entities f)))
          {} facts))

(defn df-map
  "name → how many live facts carry it. The denominator of the inverse-frequency
   weighting, and it is **global on purpose**: computed over a scoped subset,
   `united states` would look rare inside the one notebook that talks about it
   and would go straight back to acting as an anchor."
  [facts]
  (update-vals (index facts) count))

(def ^:private promotion-threshold
  "Live facts a name needs before it is something you can open. Two, because on
   the measured corpus 68 of 104 names appear exactly once — a world made of
   every extracted string is two-thirds noise.

   A name is *known* from its first extraction (it is in `index` and it is
   matched by `detect`); this only governs what gets a place of its own."
  2)

(defn promoted
  "The names that are entities: enough live facts, or already materialized.

   **A materialized entity never demotes.** Once it is an object in the world it
   stays one regardless of the evidence — it is yours, not the evidence's."
  [idx materialized]
  (into (set materialized)
        (keep (fn [[n ids]] (when (>= (count ids) promotion-threshold) n)))
        idx))

(defn- bounded
  "`name` as a pattern that will not match inside a longer word — so \"us\" does
   not match \"thus\". The name is quoted, because it comes from a model and may
   contain regex metacharacters."
  [name]
  (re-pattern (str "(?<![\\p{Alnum}])" (Pattern/quote name) "(?![\\p{Alnum}])")))

(defn detect
  "The names present in `text`, longest match first.

   Every extracted name is eligible, not only the promoted ones: a name with one
   fact behind it is still what the question is about, and refusing to match it
   would make the union useless in exactly the case it was designed for.

   **No new extractor and no model call.** The name list is already in hand, and
   a second extraction path would be a second thing to be wrong.

   Longest-first with blanking, so `wrath of the righteous` wins and `wrath` does
   not also match the same words. Blanking replaces every occurrence, or a second
   mention of the long name would re-expose the short one."
  [names text]
  (let [t0 (normalize text)]
    (if (str/blank? t0)
      #{}
      (loop [ns  (sort-by (juxt (comp - count) identity) (remove str/blank? names))
             t   t0
             out #{}]
        (if-let [n (first ns)]
          (let [p (bounded n)]
            (if (re-find p t)
              (recur (rest ns) (str/replace t p (apply str (repeat (count n) \space))) (conj out n))
              (recur (rest ns) t out)))
          out)))))
