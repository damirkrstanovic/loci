(ns loci.memory
  "Layer 3: the recall half of record/recall — a Clojure-native AI-memory
   engine behind the loci.mold/Recall protocol.

   A fact is {:id :fact :entities :source :ts :strength}, and once embedded it
   also carries {:vec :model :dim}. remember reinforces near-duplicates instead
   of duplicating; recall fuses keyword, entity, recency and strength signals,
   and — when asked for it with `:semantic? true` — cosine against the query's
   embedding, by reciprocal rank. This is the agent's domain: revisable and
   decaying — deliberately NOT the substrate, and never touched by undo.

   Persistence is an append-only EDN-lines file; reinforcement appends the
   updated fact under the same :id and load is last-wins by :id. **Embedding is
   the same append**: the fact's own line, written again with :vec/:model/:dim
   added. That is why vectors need no new store, no schema and no migration.

   Embedding happens *after* the write and never during it. `remember` does not
   call the embedder at all, so a fact is recorded whether or not an embedder
   exists; `embed-pending!` sweeps afterwards and is resumable, because a fact
   that failed simply still has no :vec. Nothing here starts on require —
   `start-embed-worker!` is explicit and `stop-embed-worker!` undoes it.

   The same worker then merges what the vectors say is the same fact
   (`merge-similar!`), at a threshold that belongs to **one** embedding model
   and is refused for a model nobody has measured. The older fact survives and
   the absorbed one's id and text land on it as :merged-from, so a merge is
   auditable and reversible; the absorbed id keeps a tombstone in the log, which
   is what stops it being reissued to a different fact later. `unmerge!` is that
   reversal, and it records `:no-merge` on both facts so that the next sweep —
   which re-embeds the restored fact and finds the same cosine — does not fold
   them straight back."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [loci.embed :as embed]
            [loci.mold :as mold]
            [loci.substrate :as sub])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

;; a trailing "." on a token is sentence punctuation, not part of the word —
;; the middle-dot form (e.g. "4.2", "22%") stays intact since [.%-] must be
;; followed by another alnum to extend the match; only a dangling separator
;; at the very end (a sentence-final period) is excluded.
(defn- tokens [s] (set (re-seq #"[a-z0-9]+(?:[.%-][a-z0-9]+)*%?" (str/lower-case (str s)))))

(defn- jaccard [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (/ (count (set/intersection a b)) (double (count (set/union a b))))))

(defn- decay
  "1.0 now, halved after ~30 days — recency as a multiplier, not a cutoff."
  [ts now]
  (/ 1.0 (+ 1.0 (/ (max 0 (- now ts)) (* 30.0 86400000.0)))))

(defn- relevance
  "How well `f` matches the query tokens, before recency and strength: 0.6 of the
   keyword overlap plus 0.4 of the entity overlap, in [0, 1].

   Split out of `score` so the two halves can be applied at different moments.
   Fusion ranks by this and multiplies by `weight` once at the end; multiplying
   inside the ranking too would decay every fused fact twice."
  [qt f]
  (let [kw  (jaccard qt (tokens (:fact f)))
        es  (set (map str/lower-case (:entities f)))
        ent (if (empty? es) 0.0
                (/ (count (set/intersection qt es)) (double (count es))))]
    (+ (* 0.6 kw) (* 0.4 ent))))

(defn- weight
  "Recency times the strength bonus — the part of a fact's score that has nothing
   to do with the query. Applied exactly once on every path."
  [now f]
  (* (decay (:ts f) now)
     (+ 1.0 (* 0.25 (dec (:strength f 1))))))

(defn- score [qt now f] (* (relevance qt f) (weight now f)))

(defn- append-line! [file rec]
  (io/make-parents (io/file file))
  (spit file (str (pr-str rec) "\n") :append true))

(defn- load-facts [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(def ^:private id-pattern #"mem-(\d+)")

(defn- id-ordinal
  "The <n> of a mem-<n> id, or nil for an id in any other shape."
  [id]
  (when-let [[_ n] (re-matches id-pattern (str id))] (parse-long n)))

(defn- next-id
  "One past the largest id ever issued — not (inc (count …)).

   Counting is collision-free only while nothing is ever deleted. Delete mem-2
   of three facts and the count is 2, so the next remember mints mem-3 and
   overwrites a *different, still-live* fact: same id, last line wins, the
   original text gone with no error anywhere. The semantic merge below deletes
   the absorbed fact, so this is no longer hypothetical — which is why the
   absorbed fact leaves a tombstone in the map rather than vanishing from it.
   `facts` here is the whole map, tombstones included, and must stay that way.

   An id that is not mem-<n> contributes nothing to the maximum rather than
   throwing; there are none today, and a hand-edited log should not brick
   remembering."
  [facts]
  (str "mem-"
       (inc (reduce (fn [hi id] (max hi (or (id-ordinal id) 0))) 0 (keys facts)))))

;; ----------------------------------------------------------------------------
;; tombstones — how an absorbed fact stops being a fact
;; ----------------------------------------------------------------------------

(defn- merged-away?
  "True for a record the semantic merge absorbed into another one.

   The log is append-only and last-wins by :id, so a merge cannot remove a line;
   it appends a final record for the absorbed id carrying `:merged-into`. That
   record stays in the map — `next-id` counts it, so the id is never reissued —
   and every reader of the facts filters it out through `live`."
  [f]
  (some? (:merged-into f)))

(defn- live
  "The records that are facts: everything the merge has not absorbed. Every path
   that reads the map goes through this, because a tombstone still carries the
   absorbed text and anything scanning texts would otherwise match it."
  [facts]
  (remove merged-away? facts))

;; ----------------------------------------------------------------------------
;; embedding — after the write, never during it
;; ----------------------------------------------------------------------------

(defn- awaiting?
  "True when `f` has no vector usable by `model`: none at all, or one stamped
   with a different model.

   A vector from another model is worse than none. Two embedding spaces are not
   comparable, and cosine between them still returns a number that sorts — so a
   stale vector produces confident nonsense, while no vector produces a fact
   that competes lexically and is queued for re-embedding."
  [f model]
  (or (empty? (:vec f)) (not= model (:model f))))

(defonce ^{:private true
           :doc "messages already reported by warn-once!, so a timer does not repeat them"}
  !warned (atom #{}))

(defn- warn-once!
  "Print `msg` to stderr the first time it is seen and never again.

   A backfill runs on a timer, so an embedder answering with a wrong dimension
   would otherwise write the same line every interval for as long as loci is up.
   The set is keyed on the message, so a *different* failure still gets its own
   line — including a different dimension, which the client puts in the text."
  [msg]
  (when-not (contains? @!warned msg)
    (swap! !warned conj msg)
    (binding [*out* *err*] (println "loci.memory:" msg))))

(defn all-facts
  "Every remembered fact, newest first — the browsable memory pane.

   A thin function over the protocol method so existing callers read unchanged."
  [m]
  (mold/all-facts m))

(defn pending-facts
  "The facts awaiting an embedding for the configured model — what the memory
   pane reports as \"N awaiting embedding\" rather than pretending they are
   searchable by meaning."
  ([m] (pending-facts m (embed/embed-model)))
  ([m model] (filterv #(awaiting? % model) (mold/all-facts m))))

(def ^:private batch-size
  "Texts per request. One request would carry loci's whole memory today; this
   only keeps a first backfill of a much larger memory from being a single
   enormous body, and costs nothing at this scale."
  32)

(defn- store-vector!
  "Persist one fact's embedding: the same :id appended again with :vec, :model
   and :dim added. The log is last-wins by id, so this is exactly what
   reinforcement already does — no new store, no migration.

   The update is applied to the fact as it stands *now*, not to the snapshot the
   pass started from, so a reinforcement that landed while the embedder was
   thinking is not rolled back. If the text changed under us — or the fact is
   gone — nothing is written and nothing is appended: the vector describes text
   that is no longer stored, and pairing it with the new text is the silent
   mismatch this whole path exists to avoid. The fact simply stays pending and
   the next pass embeds what it now says."
  [{:keys [!facts file]} fact vector model dim]
  (let [id   (:id fact)
        text (:fact fact)
        [_ after] (swap-vals! !facts
                              (fn [fs]
                                (if (= text (:fact (get fs id)))
                                  (update fs id assoc :vec vector :model model :dim dim)
                                  fs)))
        rec  (get after id)]
    (when (and rec (= text (:fact rec)))
      (append-line! file rec)
      true)))

(defn embed-pending!
  "Embed every fact awaiting one and append each result. Returns

     {:embedded n :pending n :model \"…\"}   with :error added if a batch failed
     {:off true}                            no embedding endpoint configured

   and never throws.

   A plain function: the server calls it, `start-embed-worker!` calls it on a
   timer, a test calls it directly. Requiring this namespace calls it never.

   **Resumable by construction.** Nothing marks a fact as tried — a failed
   embedding leaves the fact with no :vec, which is the same state it was in
   before, so the next pass picks it up again. A worker killed mid-sweep loses
   only the batch in flight.

   It stops at the first failing batch rather than working through the rest. The
   overwhelmingly likely cause is that the embedder is down, and twenty more
   30-second timeouts discover that twenty more times.

   Two passes running at once do the same work twice and append the same :id
   twice. Last-wins by :id makes that harmless — it costs a duplicate line, not
   a wrong fact — which is why no lock is taken."
  [m]
  (if-not (embed/embedding-configured?)
    {:off true}
    (let [model (embed/embed-model)]
      (loop [batches (partition-all batch-size (pending-facts m model))
             done    0]
        (if-let [batch (seq (first batches))]
          (let [r (embed/embed-texts (mapv :fact batch))]
            (if (:error r)
              (do (warn-once! (:error r))
                  {:embedded done
                   :pending  (count (pending-facts m model))
                   :model    model
                   :error    (:error r)})
              ;; :embedded counts what was actually stored, not what was asked
              ;; for — store-vector! declines a fact whose text moved under it.
              (let [stored (reduce (fn [n [f v]]
                                     ;; :model here is the *configured* name;
                                     ;; the server's own (llama.cpp answers with
                                     ;; a file path) would never match again and
                                     ;; the fact would be re-embedded forever.
                                     (if (store-vector! m f v (:model r) (:dim r))
                                       (inc n) n))
                                   0 (map vector batch (:vectors r)))]
                (recur (next batches) (+ done stored)))))
          {:embedded done
           :pending  (count (pending-facts m model))
           :model    model})))))

;; ----------------------------------------------------------------------------
;; semantic merge — after a pass, in the worker, never on the write path
;; ----------------------------------------------------------------------------

(def merge-thresholds
  "Cosine at or above which two facts are taken to be the same fact — **per
   embedding model**, because a threshold belongs to the space that produced the
   numbers and does not travel to another one.

   Measured 2026-08-07 over the 80 facts in the author's memory, all pairs
   scored, the top pairs read and labelled by hand (the spec's §5.1.1). The
   global distributions of the two models are nearly identical — median 0.298
   against 0.291, p99 0.741 both — which is exactly what makes carrying a number
   across look safe. The tail is what moved: one pair of genuinely *distinct*
   facts, Kepler's Third Law stated against an observation the law explains,
   scored 0.829 under the 0.6b and 0.868 under the 4b. A 0.85 calibrated on the
   first and reused on the second merges them and destroys the law's
   formulation, and nothing anywhere reports that it happened.

   These two numbers were chosen deliberately above what `loci.calibrate`'s
   `suggest-threshold` returns on the same labelled pairs (~0.835 for the 0.6b).
   Only the top pairs were labelled, so the 0.83–0.85 band is unexamined; the
   table is the authority and is not recomputed from the labels.

   A model that is not a key here has no threshold, and `merge-similar!` refuses
   rather than borrowing the nearest one. `embed-bge-m3` is the live example: it
   is in use and it is uncalibrated. An unmerged corpus is redundant; a wrongly
   merged one has lost something."
  {"embed-qwen3-0.6b" 0.85
   "embed-qwen3-4b"   0.88})

(defn merge-threshold
  "The calibrated merge threshold for `model`, or nil when there is none.

   nil is the answer to \"what should I use for a model nobody measured\", and
   every caller has to handle it. Re-measure with `loci.calibrate/calibrate!`
   and add the result here."
  [model]
  (get merge-thresholds model))

(defn- no-threshold-message
  "Why a model with no measured threshold merges nothing.

   One function because two callers must say exactly the same thing:
   `merge-similar!` warns it when a pass refuses, and `merge-status` reports it
   to the memory pane without a pass ever running. Two copies would drift, and
   the pane would then explain the refusal in words the log does not use."
  [model]
  (str "no merge threshold is calibrated for the embedding model \"" model
       "\", so nothing is merged — a threshold belongs to one model and the "
       "nearest number from another one would merge facts that are not the "
       "same fact. Calibrated: "
       (str/join ", " (sort (keys merge-thresholds)))
       ". Measure this model with loci.calibrate and add it to "
       "loci.memory/merge-thresholds."))

(defn merge-status
  "Whether merging is running, as a value — **a lookup, never a pass**:

     {:threshold 0.88}   the configured model is calibrated; merges happen
     {:refused \"…\"}      it is not, and merge-similar! refuses every pass
     {:off true}         no embedder is configured, so there are no vectors

   Read on a GET (the memory pane), so it must not merge, must not embed and
   must not warn. Answering by calling `merge-similar!` would spend O(n²)
   cosines over the whole memory on a page load, and its `warn-once!` would
   consume the single startup line into a request nobody reads stderr for.
   Nothing here does more than resolve the configuration and look in a map.

   `{:off true}` for an unconfigured embedder is the one non-obvious answer.
   `embed-model` still resolves with no embedder — to the default, which is
   calibrated — so the bare lookup would report `{:threshold 0.85}` for a memory
   in which not one fact carries a vector and `merge-similar!` therefore
   considers nothing, forever. Nor is it a refusal: a refusal names the model as
   the reason it cannot merge, and here the model is fine and the embedder is
   missing. `:off` is the word `loci.embed` and `semantic-ranking` already use
   for not-configured as against not-working, and it lets a pane report the
   absent embedder once rather than twice."
  []
  (if-not (embed/embedding-configured?)
    {:off true}
    (let [model (embed/embed-model)]
      (if-let [t (merge-threshold model)]
        {:threshold t}
        {:refused (no-threshold-message model)}))))

(defn- older
  "Whichever of `a` and `b` survives a merge: the older by `:ts`.

   The survivor holds the provenance — it is the fact the `:source` was recorded
   against, and the one whose id anything outside memory may already refer to.
   A tie is broken by id ordinal so that the choice is deterministic rather than
   an accident of map iteration; a non-mem id sorts last and then by string."
  [a b]
  (let [k (fn [f] [(:ts f 0) (or (id-ordinal (:id f)) Long/MAX_VALUE) (str (:id f))])]
    (if (neg? (compare (k a) (k b))) a b)))

(defn- no-merge?
  "True when `a` and `b` have been split apart by hand (`unmerge!`) and must not
   be merged again.

   **The representation is a set of ids under `:no-merge` on BOTH facts**, and
   this reads the union of the two directions. On both rather than on one side
   with a directional lookup, for three reasons:

   · the pass compares an unordered pair, so a one-sided record has to be looked
     up in both directions anyway — and then *which* side holds it becomes a
     convention every call site must keep agreeing on;
   · either record can lose its place in the comparison. A reworded fact drops
     its vector; a fact can be absorbed by a third one and become a tombstone.
     With the pair written on both, whichever record is still live still carries
     the refusal;
   · it is where a person will look. The fact someone opens to ask \"why is this
     not merging\" is as likely to be the one that was split out as the one it
     was split out of.

   The cost is one duplicated id per split pair, and duplication is only a
   hazard when the two copies can disagree. They cannot say different things
   here: this predicate ORs them, so a lost copy weakens nothing and neither
   copy can assert a merge.

   `absorb` carries the union onto the survivor, so a refusal outlives a later
   merge of either side — see the comment there for why that is the behaviour
   and not an accident."
  [a b]
  (or (contains? (:no-merge a) (:id b))
      (contains? (:no-merge b) (:id a))))

(defn- absorb
  "The survivor as it stands after taking `gone` into itself.

   `:fact` is deliberately *not* touched. The survivor's vector describes the
   survivor's text, and rewriting the text here would leave a vector pointing at
   a sentence that is no longer stored — the same mismatch `remember` drops a
   vector to avoid.

   `:merged-from` carries the absorbed fact whole, minus its embedding: id and
   text so the merge is auditable and reversible, `:strength`, `:entities` and
   `:source` so what was folded in can be told apart from what was already
   there. The absorbed fact's own `:merged-from` comes along, or a chain of
   merges would drop everything but the last step."
  [survivor gone]
  ;; **A refusal to re-merge is inherited.** If B was split out of A by hand and
  ;; A is later absorbed by C, then C now carries A's text and A's identity, and
  ;; letting B fold into C would undo the split through a side door one merge
  ;; later. The consistency argument is the threshold's own: C and A were judged
  ;; the same fact, and B was judged *not* the same fact as A, so B is not the
  ;; same fact as C. The cost is a set of ids that is never expired and can
  ;; therefore suppress a pair the person never spoke about; the cost of the
  ;; other choice is the gesture itself, silently, on the next pass.
  (let [no-merge (into (set (:no-merge survivor)) (:no-merge gone))]
    (cond-> (-> survivor
                (assoc :ts (max (:ts survivor 0) (:ts gone 0)))
                (assoc :strength (+ (:strength survivor 1) (:strength gone 1)))
                (assoc :entities (vec (distinct (concat (:entities survivor) (:entities gone)))))
                (assoc :merged-from (-> (vec (:merged-from survivor))
                                        (conj (dissoc gone :vec :model :dim :merged-from))
                                        (into (:merged-from gone)))))
      ;; written only when there is something to write, so an ordinary merge
      ;; does not put an empty set on every survivor in the log
      (seq no-merge) (assoc :no-merge no-merge))))

(defn- apply-merge!
  "Fold `gone` into `keep` in the atom and append both records. Returns true
   when it happened.

   The guard is `store-vector!`'s: both facts must still be exactly the ones
   that were scored. A reinforcement that landed while the pass was computing
   cosines may have rewritten either text, and the vectors that justified this
   merge describe the texts as they were. Rather than merge on stale evidence it
   declines, and the next pass — over the new text, with a fresh vector —
   decides again.

   Two appends, not a rewrite: the survivor's updated record, and the absorbed
   id's tombstone. Reload is last-wins by :id, so this is the same mechanism
   reinforcement and embedding already use."
  [{:keys [!facts file]} keep gone]
  (let [kid (:id keep)
        gid (:id gone)
        [before after]
        (swap-vals! !facts
                    (fn [fs]
                      (let [k (get fs kid)
                            g (get fs gid)]
                        (if (and k g
                                 (= (:fact k) (:fact keep))
                                 (= (:fact g) (:fact gone))
                                 (not (merged-away? k))
                                 (not (merged-away? g))
                                 ;; re-read here, not only where the pair was
                                 ;; scored: a refusal can land *after* the
                                 ;; cosines were computed. Either someone
                                 ;; unmerged the pair while the pass was
                                 ;; running, or an earlier merge in this same
                                 ;; pass carried one onto this survivor.
                                 (not (no-merge? k g)))
                          (-> fs
                              (assoc kid (absorb k g))
                              ;; the tombstone keeps the text so a person reading
                              ;; the log sees what was absorbed and where it
                              ;; went; the survivor's :merged-from is what makes
                              ;; it recoverable in memory
                              (assoc gid {:id gid :fact (:fact g) :ts (:ts g)
                                          :merged-into kid}))
                          fs))))]
    (when-not (identical? before after)
      (append-line! file (get after kid))
      (append-line! file (get after gid))
      true)))

(defn merge-similar!
  "Merge the facts whose vectors say they are the same fact. Returns

     {:merged n :considered n :model \"…\" :threshold t :pairs [{…}]}
     {:merged 0 :considered n :model \"…\" :refused \"…\"}   no calibrated threshold

   and never throws.

   **It refuses a model it has no measured threshold for**, warns once, and
   merges nothing — it does not fall back to the nearest number in the table and
   it does not guess. See `merge-thresholds` for what that would cost.

   Only facts carrying a vector stamped with `model` are considered, so two
   embedding spaces are never compared: cosine across them still returns a
   number that sorts, and at these thresholds it would sort into a merge. Facts
   awaiting an embedding are simply not in the pass and are reconsidered once
   they have one. Two vectors of different length — which under one model name
   would mean the log has been hand-edited — score 0.0 in `loci.embed/cosine`
   and so cannot reach any threshold; this fails closed rather than refusing,
   because there is nothing here to destroy by declining to merge.

   Pairs are applied highest score first, and a fact absorbed in this pass takes
   no further part in it: if A absorbs B and B also scored above the threshold
   against C, C is left for the next pass, where it is compared against A — the
   survivor, whose text and vector are unchanged. Merging along a chain inside
   one pass would depend on the order the pairs happened to be visited.

   **A pair `unmerge!` split apart is not a candidate**, at any cosine, in this
   pass or any later one — see `no-merge?`. Without that this function would
   undo every unmerge within one worker interval: the restored fact has no
   vector, the next sweep gives it one, and the cosine that merged the pair is
   still exactly what it was.

   O(n²) cosines per call, on the worker's interval and on no request path.
   Measured 2026-08-07 at 1024 dimensions, every pair scored and none merged:
   80 facts (3,160 pairs) 38 ms, 500 facts (124,750 pairs) 1.5 s. At 15-second
   intervals that second figure is a tenth of a core spent re-deciding what was
   already decided, so somewhere in the low thousands of facts this needs to
   compare only what has changed — a smaller interval is not the fix."
  ([m] (merge-similar! m (embed/embed-model)))
  ([m model]
   (let [facts (filterv #(not (awaiting? % model)) (mold/all-facts m))]
     (if-let [t (merge-threshold model)]
       (let [v     (vec (sort-by (juxt :ts :id) facts))
             n     (count v)
             above (->> (for [i (range n)
                              j (range (inc i) n)
                              :let [a (nth v i)
                                    b (nth v j)]
                              ;; before the cosine, not after: a pair someone
                              ;; unmerged is not a candidate at any score, and
                              ;; there is nothing to learn from scoring it
                              :when (not (no-merge? a b))
                              :let [c (embed/cosine (:vec a) (:vec b))]
                              :when (>= c t)]
                          {:score c :a a :b b})
                        (sort-by (juxt (comp - :score) (comp :id :a) (comp :id :b)))
                        vec)
             r     (reduce (fn [acc {:keys [score a b]}]
                             (if (or (contains? (:gone acc) (:id a))
                                     (contains? (:gone acc) (:id b)))
                               acc
                               (let [keep (older a b)
                                     gone (if (identical? keep a) b a)]
                                 (if (apply-merge! m keep gone)
                                   (-> acc
                                       (update :gone conj (:id gone))
                                       (update :pairs conj {:survivor (:id keep)
                                                            :absorbed (:id gone)
                                                            :score    score}))
                                   acc))))
                           {:gone #{} :pairs []} above)]
         {:merged     (count (:gone r))
          :considered n
          :model      model
          :threshold  t
          :pairs      (:pairs r)})
       (let [msg (no-threshold-message model)]
         (warn-once! msg)
         {:merged 0 :considered (count facts) :model model :refused msg})))))

;; ----------------------------------------------------------------------------
;; unmerge — putting an absorbed fact back, and having it stay back
;; ----------------------------------------------------------------------------

(defn- merged-entry
  "The `:merged-from` entry `survivor` holds for `id`, or nil."
  [survivor id]
  (first (filter #(= id (:id %)) (:merged-from survivor))))

(defn- unmerge-refusal
  "Why `absorbed-id` cannot be split back out of `survivor-id`, as a sentence a
   person can act on — or nil when it can.

   A sentence rather than a keyword because the only caller is an endpoint whose
   only reader is a person, and \"not in :merged-from\" has three quite different
   causes that want three different next moves."
  [facts survivor-id absorbed-id]
  (let [s (get facts survivor-id)
        t (get facts absorbed-id)]
    (cond
      (nil? s)
      (str "there is no fact " survivor-id " to unmerge anything from")

      (merged-away? s)
      (str survivor-id " is not a fact any more — it was itself absorbed into "
           (:merged-into s) ", which is where its merged facts went. Unmerge from there.")

      (nil? (merged-entry s absorbed-id))
      (str survivor-id " did not absorb " absorbed-id
           (if-let [ids (seq (map :id (:merged-from s)))]
             (str "; it holds " (str/join ", " ids))
             "; it holds no merged facts at all"))

      ;; Only reachable through a hand-edited log — a merge leaves a tombstone
      ;; under the absorbed id — but the write below is `assoc`, so if it ever is
      ;; reachable it overwrites a live fact with a different one under its id.
      ;; That is the exact failure `next-id`'s tombstones exist to prevent.
      (and t (not (merged-away? t)))
      (str absorbed-id " is already a live fact, so restoring it would overwrite one")

      :else nil)))

(defn unmerge!
  "Split `absorbed-id` back out of `survivor-id`. Returns

     {:survivor {…} :restored {…}}   the two records as they now stand
     {:error \"…\"}                    that pair is not a merge this survivor made

   and never throws.

   What comes back is what `absorb` stored — the absorbed fact minus its
   embedding — so its text, `:strength`, `:entities` and `:source` are restored
   exactly. Three things are **not** restored, and none of them can be:

   · **No `:vec`.** A merge stores none: a vector is ~1024 floats and is
     re-derivable from the text. So the restored fact is pending and the next
     sweep embeds it — which is precisely why `:no-merge` has to exist. The
     sweep hands it back the same vector, the pass finds the same cosine, and
     without the refusal the merge is reapplied inside one worker interval and
     this function does nothing at all.

   · **The survivor's `:ts`.** `absorb` overwrote it with the newer of the two
     and nothing anywhere stored the old one. It keeps the merged value, so a
     survivor reads as more recently seen than it was, and recency is a
     multiplier in `recall` — this makes it rank slightly high, forever. The
     restored fact's own `:ts` *is* stored and does come back, with one
     qualification: it is its `:ts` at the moment of that merge, so a fact that
     had already absorbed something was already carrying the newer timestamp of
     the earlier merge. The loss compounds along a chain. The alternative is to
     invent a number, and a plausible invented timestamp is worse than a real
     one that is too new, because nothing downstream can tell it is invented.

   · **The survivor's `:entities`.** `absorb` unions them and a union is not
     invertible: subtracting the absorbed fact's entities would delete the ones
     the survivor already had. They are left alone, so a survivor can keep an
     entity that only the absorbed fact ever justified.

   `:strength` is decremented by the absorbed fact's and floored at 1. The floor
   is not decoration: a chain double-counts. When A absorbs B and C then absorbs
   A, C's entry for A carries a strength that already includes B's, and B has an
   entry of its own on C — so unmerging both subtracts B's strength twice. A
   strength of 0 would turn `weight`'s \"it keeps coming up\" bonus into a
   penalty, and a negative one would invert the ranking.

   A restored fact comes back **without** a `:merged-from` of its own, even if it
   had absorbed something before it was itself absorbed: `absorb` hoisted those
   entries onto the outer survivor, and that is where they still are. So in a
   chain the innermost fact stays recoverable — from the outer survivor, not from
   the one just restored. Any `:no-merge` the fact carried at the time does come
   back with it, so an older split is not quietly reopened by restoring a newer
   one.

   Two appends, like every other write here: the survivor's updated record, and
   the absorbed id's record without its `:merged-into`. Reload is last-wins by
   id, so the restored line replaces the tombstone. The id is not *reissued* —
   it is given back to the one fact it always named, which is why this does not
   reopen what `next-id` warns about.

   Only `merge-similar!` honours `:no-merge`. `remember`'s duplicate check is a
   different rule over different evidence (jaccard over tokens, not cosine) and
   an unmerge does not change it: the same sentence remembered again still
   reinforces whichever live fact its words overlap."
  [{:keys [!facts file]} survivor-id absorbed-id]
  (if-let [refusal (unmerge-refusal @!facts survivor-id absorbed-id)]
    {:error refusal}
    (let [[before after]
          (swap-vals! !facts
                      (fn [fs]
                        ;; the same check again, inside the swap, on the map the
                        ;; write will actually land on — a second unmerge of this
                        ;; pair, or a merge that moved the survivor, may have
                        ;; landed since the read above
                        (if (unmerge-refusal fs survivor-id absorbed-id)
                          fs
                          (let [s     (get fs survivor-id)
                                entry (merged-entry s absorbed-id)
                                held  (filterv #(not= absorbed-id (:id %)) (:merged-from s))
                                s'    (-> s
                                          (update :strength #(max 1 (- (or % 1) (:strength entry 1))))
                                          (update :no-merge (fnil conj #{}) absorbed-id))
                                s'    (if (seq held)
                                        (assoc s' :merged-from held)
                                        ;; dropped rather than left as [], so a
                                        ;; survivor that holds nothing does not
                                        ;; read as one that holds an empty list
                                        (dissoc s' :merged-from))
                                back  (-> entry
                                          ;; taken whole rather than rebuilt
                                          ;; field by field, so a field added to
                                          ;; a fact later is restored without
                                          ;; touching this code. What is removed
                                          ;; is only what would make it not a
                                          ;; fact: :merged-into is what a
                                          ;; tombstone *is*, and an embedding on
                                          ;; an entry (which `absorb` never
                                          ;; writes) could only describe some
                                          ;; other text.
                                          (dissoc :merged-into :vec :model :dim)
                                          ;; :ts is on the entry for every log
                                          ;; this code wrote; the fallbacks are
                                          ;; for a hand-edited one, and prefer a
                                          ;; real timestamp that is too new over
                                          ;; a 0 that decays the fact to nothing
                                          (assoc :ts (or (:ts entry) (:ts (get fs absorbed-id)) (:ts s 0)))
                                          (update :no-merge (fnil conj #{}) survivor-id))]
                            (assoc fs survivor-id s' absorbed-id back)))))]
      (if (identical? before after)
        {:error (str "unmerging " absorbed-id " from " survivor-id
                     " was refused: the memory changed while it was being applied")}
        (let [s (get after survivor-id)
              r (get after absorbed-id)]
          (append-line! file s)
          (append-line! file r)
          {:survivor s :restored r})))))

(defn start-embed-worker!
  "Start one background thread that runs `embed-pending!` on `m` every
   `:interval-ms` (default 15 s), then `merge-similar!` when that pass
   succeeded, until stopped. Returns a handle to pass to `stop-embed-worker!`.

   Explicit on purpose. Requiring this namespace starts nothing: a namespace
   that spawns on load leaves a thread behind in every process that merely read
   it, tests included.

   It polls rather than being woken by `remember`, so `remember` holds no
   reference to the worker at all — there is nothing there for a later edit to
   make it wait on. A pass with nothing pending makes no HTTP request, so the
   idle cost is a scan of the facts and the merge's cosines.

   **Merge only follows a pass that worked.** An embedder that is off or not
   answering leaves part of the corpus without vectors, and merging the part
   that has them is deciding on evidence that is missing for the rest. The cost
   of waiting is that a corpus stays redundant until the embedder is back, which
   is the cheap error.

   The thread is a daemon: a worker parked in a 30-second request must not be
   what keeps the JVM alive."
  ([m] (start-embed-worker! m {}))
  ([m {:keys [interval-ms] :or {interval-ms 15000}}]
   (let [wake (ArrayBlockingQueue. 1)
         run? (atom true)
         t    (Thread. ^Runnable
                       (fn []
                         (while @run?
                           (try (let [r (embed-pending! m)]
                                  (when-not (or (:off r) (:error r))
                                    (merge-similar! m)))
                                (catch Throwable e
                                  (warn-once! (str "the embedding worker threw: " e))))
                           (when @run?
                             (try (.poll wake interval-ms TimeUnit/MILLISECONDS)
                                  (catch InterruptedException _ nil)))))
                       "loci-embed-worker")]
     (.setDaemon t true)
     (.start t)
     {:thread t :wake wake :run? run?})))

(defn stop-embed-worker!
  "Stop a worker started by `start-embed-worker!`. Returns true once its thread
   has died.

   The wake queue is what makes that quick: the thread spends its life parked in
   a poll whose timeout is the interval, and an offer returns it immediately
   instead of after the rest of the interval. A request already in flight is not
   interrupted — it can legitimately be a 30-second timeout — so the join is
   bounded and a false return means \"still finishing a request\", not \"stuck\"."
  [{:keys [^Thread thread ^ArrayBlockingQueue wake run?]}]
  (reset! run? false)
  (.offer wake :stop)
  (.join thread 2000)
  (not (.isAlive thread)))

;; ----------------------------------------------------------------------------
;; recall — words, meaning, and the fusion of the two
;; ----------------------------------------------------------------------------

(def ^:private rrf-damping
  "The 60 in 1/(60 + rank) — reciprocal rank fusion's damping constant.

   **Not tuned here.** It is the value the original RRF paper used and that
   every implementation since has copied, and tuning it is not something that
   can be done by reading: it needs a set of queries with known-right facts to
   score against, which loci does not have. What it controls is how much rank 1
   is worth over rank 10 — at 60 that is 1/61 against 1/70, a 13% edge, so a
   fact both retrievers place in their top ten beats a fact only one of them
   places first. A smaller constant makes each retriever's own first place
   harder to outvote; a larger one flattens everything toward a plain vote
   count."
  60)

(def ^:private per-retriever-limit
  "How deep each retriever's ranking reaches into the fused set.

   The semantic side needs a bound and the lexical side does not: every embedded
   fact has *some* cosine against any query, so unbounded it would nominate the
   entire memory and every fact would come back with a nonzero score. A fact
   that ranks fiftieth by meaning and matched no words is not a recall hit."
  50)

(def ^:private rerank-window
  "How many of the fused head the reranker is shown. A reranker reads every
   document against the query — it is the expensive, accurate step — so it is
   pointed at the part of the list where the order is still in doubt."
  20)

(defn source-ids
  "The ids a fact's `:source` names. The server records `{:obj … :space …}`, so
   a fact belongs to both its object and its notebook and either can scope it."
  [f]
  (let [s (:source f)]
    (cond
      (map? s)  (into #{} (remove nil?) [(:obj s) (:space s)])
      (some? s) #{s}
      :else     #{})))

(defn in-sources?
  "Whether `f` was recorded against one of `sources`.

   Public because the memory pane browses without a query — `all-facts`, not
   `recall` — and a scoped browse must be narrowed by exactly the rule a scoped
   query is narrowed by. Two copies of “what a `:source` names” would drift, and
   the one that drifted would be the one showing facts a scope excluded."
  [sources f]
  (boolean (some sources (source-ids f))))

(defn- scoped
  "The facts a query is allowed to see. `{:filter {:sources #{…}}}` narrows to
   facts recorded against one of those ids; no `:sources` key means no filter.

   `loci.memory` takes ids rather than a notebook id on purpose — it holds no
   store and cannot walk the substrate's links, and giving it one would put the
   record half inside the recall half. The caller that has the store does the
   traversal and passes the result.

   **An empty set is an empty result.** The caller asked for the facts belonging
   to a scope that owns none; answering with the whole memory instead would be a
   scoped recall quietly returning unscoped facts, which is the one failure a
   scope exists to prevent."
  [facts opts]
  (if-let [sources (get-in opts [:filter :sources])]
    (filterv #(in-sources? sources %) facts)
    (vec facts)))

(defn- semantic-ranking
  "Facts ordered by cosine against the query's embedding, best first. Returns

     {:ranking [fact …]}   the embedder answered
     {:off true}           no embedding endpoint configured
     {:error \"…\"}          there is one and it did not answer

   Facts embedded under a different model are not in the ranking: two embedding
   spaces are not comparable and cosine across them still returns a number that
   sorts. They stay in `pending-facts`, which is what the memory pane counts, so
   they are reported as awaiting rather than silently dropped.

   Only a positive cosine ranks. `cosine` returns 0.0 for a pair it cannot
   compare at all — a length mismatch, a zero vector — and those must not enter
   the ranking ahead of a fact that genuinely scored low."
  [query facts]
  (let [model    (embed/embed-model)
        eligible (filterv #(not (awaiting? % model)) facts)]
    (cond
      (not (embed/embedding-configured?)) {:off true}
      ;; Nothing to compare a query vector against, so do not spend the round
      ;; trip to make one. This is the state of a fresh memory and of one whose
      ;; model just changed.
      (empty? eligible) {:ranking []}
      :else
      (let [r (embed/embed-texts [query])]
        (cond
          (:off r)   {:off true}
          (:error r) {:error (:error r)}
          :else
          (let [qv (first (:vectors r))]
            {:ranking (->> eligible
                           (keep (fn [f] (let [c (embed/cosine qv (:vec f))]
                                           (when (pos? c) [c f]))))
                           (sort-by (fn [[c f]] [(- c) (:id f)]))
                           (take per-retriever-limit)
                           (mapv second))}))))))

(defn- fuse
  "Reciprocal rank fusion of `rankings` — a map of retriever → ordered facts.

   A fact at rank r in a ranking contributes 1/(rrf-damping + r), summed across
   the retrievers that ranked it. **Rank, not score**: the lexical scorer's 0.15
   and a cosine of 0.71 are not on the same scale, and no normalisation makes
   them one. RRF only ever compares a retriever's output to itself, which is why
   adding a retriever cannot corrupt the others' contributions.

   Each fused fact carries `:via` — the retrievers that ranked it — in the fixed
   order [:lexical :semantic], so the value is deterministic and a caller can
   read it as provenance rather than as an accident of map ordering."
  [rankings]
  (->> rankings
       (reduce (fn [m [via ranked]]
                 (reduce (fn [m [i f]]
                           (-> m
                               (update-in [(:id f) :score] (fnil + 0.0)
                                          (/ 1.0 (+ rrf-damping (inc i))))
                               (update-in [(:id f) :via] (fnil conj #{}) via)
                               (assoc-in [(:id f) :fact] f)))
                         m (map-indexed vector ranked)))
               {})
       (mapv (fn [[_ {:keys [score via fact]}]]
               (assoc fact :score score :via (filterv via [:lexical :semantic]))))))

(defn- rerank-head
  "Let the reranker order the head of the fused list. Returns `[hits status]`.

   The window keeps its own fused scores as a multiset and hands them back out
   in the reranker's order. So the reranker decides the order *within* the
   window and cannot push a fact below one that was never in it — it reorders
   the head, it does not rescore the tail into it.

   `:rerank` carries the model's raw score for provenance. It is a logit, not a
   probability (rerank-bge-m3 measured 0.687 down to −11.04), so nothing here
   thresholds on it and a negative one is an ordinary low-relevance answer.

   A reranker that omits a document leaves it in the window, after everything
   the reranker did rank and without a `:rerank` key."
  [query hits]
  (let [window (vec (take rerank-window hits))
        r      (embed/rerank query (mapv :fact window))]
    (if-not (vector? r)
      [hits (if (:off r) {:rerank :off}
                {:rerank :error :degraded (str "rerank is degraded: " (:error r))})]
      (let [order  (mapv :index r)
            idxs   (into order (remove (set order) (range (count window))))
            scores (vec (sort > (map :score window)))
            by-idx (into {} (map (juxt :index :score)) r)]
        [(into (mapv (fn [pos i]
                       (cond-> (assoc (nth window i) :score (nth scores pos))
                         (by-idx i) (assoc :rerank (by-idx i))))
                     (range (count idxs)) idxs)
               (drop rerank-window hits))
         {:rerank :ok}]))))

;; ----------------------------------------------------------------------------

(defrecord FileMemory [!facts file]
  mold/Recall
  ;; NOTHING in here touches the embedder. That is the design, not an omission:
  ;; a fact is recorded whether or not an embedder exists, at the speed of one
  ;; file append, and `embed-pending!` catches up afterwards.
  (remember [_ fact opts]
    (let [ft  (tokens fact)
          ;; `live`, not every record: a tombstone still carries the absorbed
          ;; text, and reinforcing one would put a merged-away fact back into
          ;; circulation under an id the merge already accounted for.
          dup (some (fn [f] (when (>= (jaccard ft (tokens (:fact f))) 0.6) f))
                    (live (vals @!facts)))
          rec (if dup
                (cond-> (-> dup
                            (assoc :fact fact :ts (System/currentTimeMillis))
                            (assoc :source (or (:source opts) (:source dup)))
                            (update :strength (fnil inc 1))
                            (update :entities #(vec (distinct (concat % (:entities opts))))))
                  ;; Reinforcement rewrites :fact, and a vector describes the
                  ;; exact text it was made from. Identical wording (the common
                  ;; case — auto-distill re-derives the same sentence) keeps its
                  ;; vector; different wording drops it, so the next pass
                  ;; re-embeds rather than leaving a vector pointing at a
                  ;; sentence that is no longer stored.
                  (not= fact (:fact dup)) (dissoc :vec :model :dim))
                {:id (next-id @!facts)
                 :fact fact :entities (vec (:entities opts))
                 :source (:source opts) :ts (System/currentTimeMillis) :strength 1})]
      (append-line! file rec)
      (swap! !facts assoc (:id rec) rec)
      :ok))
  ;; opts: {:k 6 :semantic? false :rerank? false :filter {:sources #{id …}}}
  ;;
  ;; :semantic? is false by DEFAULT and that is the whole shape of this function.
  ;; /api/leap calls recall on every keystroke, and embedding a query is a 20–50
  ;; ms network round trip — an opt-out would make every keystroke wait on a
  ;; model server. The callers that want meaning (the agent's remembered-context,
  ;; the memory pane's search) ask for it.
  ;;
  ;; Returns a vector of facts, each with :score and :via, with metadata saying
  ;; what actually ran: {:semantic :skipped|:off|:ok|:error, :rerank …,
  ;; :degraded "…"}. Metadata rather than a wrapper map because every existing
  ;; caller takes the sequence, and a caller that does not care about degradation
  ;; should not have to unwrap for it — but nothing is hidden: something asked
  ;; for that did not work always leaves :degraded behind.
  (recall [_ query opts]
    (let [now        (System/currentTimeMillis)
          k          (or (:k opts) 6)
          qt         (tokens query)
          candidates (scoped (live (vals @!facts)) opts)
          ;; today's scorer, unchanged, over the eligible facts
          lex        (->> candidates
                          (map #(assoc % :score (score qt now %)))
                          (filter #(pos? (:score %)))
                          (sort-by :score >)
                          vec)
          ;; One retriever has nothing to fuse with. Running RRF over a single
          ;; ranking would replace the scorer's relevance with its rank and throw
          ;; away how *well* each fact matched, so whenever the semantic half did
          ;; not run — not asked for, not configured, not answering, nothing
          ;; embedded yet — the answer is exactly what it was before any of this
          ;; existed.
          only-lex   (fn [m] (with-meta (mapv #(assoc % :via [:lexical]) (take k lex)) m))]
      (if-not (:semantic? opts)
        (only-lex {:semantic :skipped})
        (let [sem (semantic-ranking query candidates)]
          (cond
            (:off sem)              (only-lex {:semantic :off})
            (:error sem)            (only-lex {:semantic :error
                                               :degraded (str "semantic recall is degraded: "
                                                              (:error sem))})
            (empty? (:ranking sem)) (only-lex {:semantic :ok})
            :else
            ;; sorted before rerank-head, not after: "the top 20" has to mean the
            ;; top 20, and `fuse` returns whatever order the accumulator's map
            ;; iterates in.
            (let [f (->> (fuse {:lexical  (take per-retriever-limit lex)
                                :semantic (:ranking sem)})
                         (sort-by (juxt (comp - :score) :id))
                         vec)
                  ;; rerank is consulted only here: it reorders a fused list, and
                  ;; the lexical-only path above is deliberately untouched.
                  [f st] (if (:rerank? opts) (rerank-head query f) [f nil])]
              (->> f
                   ;; decay and the strength bonus land once, on the fused score,
                   ;; so "this keeps coming up" still counts for something after
                   ;; both retrievers have had their say
                   (map #(assoc % :score (* (:score %) (weight now %))))
                   (sort-by (juxt (comp - :score) :id))
                   (take k)
                   vec
                   (#(with-meta % (merge {:semantic :ok} st))))))))))
  (all-facts [_] (->> (vals @!facts) live (sort-by :ts >) vec)))

(defn file-memory
  ([] (file-memory (str (sub/data-dir) "/memory.edn")))
  ([path] (->FileMemory (atom (load-facts path)) path)))

(defonce ^{:doc "the server's memory singleton (data-dir resolved at first use)"}
  memory (delay (file-memory)))
