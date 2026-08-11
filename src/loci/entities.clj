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
  (:import [java.util Locale]
           [java.util.regex Pattern]))

(defn normalize
  "The stable identity of an entity name: lowercase, trimmed, internal
   whitespace collapsed. nil and blank normalize to \"\".

   Collapses on `[\\s\\p{Z}]+`, not bare `\\s+`: Java's `\\s` is ASCII
   whitespace only, so a non-breaking space (U+00A0) or a narrow no-break
   space (U+202F) — both of which arrive intact out of a pasted PDF or a
   model's JSON output — would survive as a distinct character instead of
   collapsing, quietly splitting one entity into two that never merge.
   Collapse runs *before* trim for the same reason: a leading or trailing
   NBSP has to become an ordinary space first, or trim never sees it as
   whitespace to strip.

   Lowercases via `Locale/ROOT`, not `clojure.string/lower-case` — which is
   `.toLowerCase()` under the JVM's *default* locale. Under a Turkish
   locale that maps `I` to dotless `ı`, so entity identity would depend on
   `$LANG`. `Locale/ROOT` is the one answer that does not depend on where
   the JVM happens to be running."
  [s]
  (let [^String s (-> (str s) (str/replace #"[\s\p{Z}]+" " ") str/trim)]
    (.toLowerCase s Locale/ROOT)))

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
   stays one regardless of the evidence — it is yours, not the evidence's.

   `materialized` is normalized and blank-filtered here rather than trusted
   as already-clean. The call this exists for is
   `(contains? (promoted idx (materialized-names st)) nm)` with `nm`
   normalized — an un-normalized stored name would make that lookup miss
   and report \"not promoted\" for an entity that plainly is one,
   contradicting the sentence above instead of merely failing to help. A
   blank name, left in, would sit in the set as an entity that is not
   really a subject at all."
  [idx materialized]
  (into (into #{} (comp (map normalize) (remove str/blank?)) materialized)
        (keep (fn [[n ids]] (when (>= (count ids) promotion-threshold) n)))
        idx))

(defn- bounded
  "`name` as a pattern that will not match inside a longer word — so \"us\" does
   not match \"thus\". The name is quoted, because it comes from a model and may
   contain regex metacharacters.

   `(?U)` turns on UNICODE_CHARACTER_CLASS: Java's `\\p{Alnum}` is `[a-zA-Z0-9]`
   unless told otherwise, so without the flag a non-ASCII letter reads as
   \"not alnum\" and the boundary is satisfied where it should not be — a
   name embedded in non-ASCII text (`\"žus radi\"`, `\"u Nišu danas\"`) then
   over-matches the same way \"us\" would have matched inside \"thus\" with no
   boundary check at all. The corpus already has a Serbian-language
   notebook, so this is live text, not a hypothetical."
  [name]
  (re-pattern (str "(?U)(?<![\\p{Alnum}])" (Pattern/quote name) "(?![\\p{Alnum}])")))

(defn detect
  "The names present in `text`, longest match first.

   Every extracted name is eligible, not only the promoted ones: a name with one
   fact behind it is still what the question is about, and refusing to match it
   would make the union useless in exactly the case it was designed for.

   **No new extractor and no model call.** The name list is already in hand, and
   a second extraction path would be a second thing to be wrong.

   Longest-first with blanking, so `wrath of the righteous` wins and `wrath` does
   not also match the same words. Blanking replaces every occurrence, or a second
   mention of the long name would re-expose the short one.

   `names` is normalized here too, not just `text`: trusting the caller to
   have already normalized every name would fail *silently* rather than
   loudly — an un-normalized name just never matches, so the union looks
   like it ran and returns nothing, instead of erroring where the mistake
   was made. The returned set is therefore always normalized, which is
   what recall's `:subjects` and `:filter {:entities …}` already assume
   when they compare against it.

   Three known, deliberate limits, not bugs:
   - a name's detectability is a property of the *name set*, not of the
     text alone — blanking a longer match can occasionally manufacture a
     shorter one that was never really there on its own
     (`(detect #{\"aa\" \".\"} \"aa.\")` finds both), though longest-first
     protects every name shape the distiller actually produces;
   - two names of equal length that overlap the same span: only one
     survives, because the tie is broken alphabetically and blanking then
     hides the other from the rest of the pass, even though both are
     genuine subjects;
   - collapsing newlines during normalization lets a multi-word name match
     across a line break (`\"TSMC\\nTaiwan\"` still matches `tsmc taiwan`).
     That is deliberate, not an oversight — it is what lets a multi-word
     name match text a renderer has wrapped — and a full stop or bullet
     still blocks it."
  [names text]
  (let [t0 (normalize text)]
    (if (str/blank? t0)
      #{}
      (loop [ns  (sort-by (juxt (comp - count) identity)
                          (into #{} (comp (map normalize) (remove str/blank?)) names))
             t   t0
             out #{}]
        (if-let [n (first ns)]
          (let [p (bounded n)]
            (if (re-find p t)
              (recur (rest ns) (str/replace t p (apply str (repeat (count n) \space))) (conj out n))
              (recur (rest ns) t out)))
          out)))))
