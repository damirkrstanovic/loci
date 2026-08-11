(ns loci.classify
  "The one-time pass that judges the facts the memory already holds.

   `weight` skips decay for a fact carrying `:durable?`, and the distiller now
   proposes one per new fact — but **absent means decays**, deliberately, so
   nothing that was already on file changed rank when either of those landed.
   Kepler's Third Law is as true as it was in 1619 and ranks at 0.076 of itself
   a year after its `:ts`, 0.039 the year after that. This pass is what stops
   that, once, for the facts nobody will ever distil again.

   **Built like `loci.repair`, for `loci.repair`'s reasons.** It runs against
   data that cannot be regenerated, so it is built to be *worth* running:

   · **Nothing runs on require.** No top-level side effect, no `defonce` that
     opens a memory. `classify!` is `{:dry-run? true}` by DEFAULT, and a dry run
     returns the plan it would have applied without applying any of it.
   · **Every refusal is a returned report, never a throw.** The caller is a
     person reading a sentence — `edn->datalevin!`'s contract and `repair`'s.
   · **Every batch is asked before anything is written.** A classifier that dies
     on the fourth batch of four costs a pass, never half a memory: the model
     calls all happen in the plan, and the plan is refused whole. This is the
     one place the shape differs from `refile-astronomy-facts!`, which has
     nothing to ask.
   · **Idempotent.** A fact already carrying a `boolean?` `:durable?` is not
     re-judged and is not even shown to the model, so a second run is a no-op
     that costs nothing.
   · **Append-only**, one line per judged fact, last-wins by `:id` — the same
     write `store-vector!` and `unmerge!` make.

   **It must NOT go through `mold/remember`**, and that is the single most
   important line here. `remember` would find each fact as its own jaccard ≥ 0.6
   duplicate, `(update :strength inc)` it and `(assoc :ts (System/…))` — and
   `:ts` is a multiplier in `weight`, so every fact this pass judged *not*
   durable would receive a fresh 1.0 recency multiplier in the same breath as
   being recorded as ageing. Marking the corpus would then rank it up. The
   2026-08-09 repair documented this trap and this is the second namespace to
   walk past it.

   **`boolean?`, not `contains?`** — ratified when the distiller met the same
   question. A model answering the *string* `\"false\"` is truthy in Clojure, so
   `weight` would skip decay on the very fact the classifier said ages, stamped
   `:durable-by \"agent\"` and undoable only by hand in the pane. `contains?`
   guards against silence and not against noise, and the two fail in the same
   direction. A malformed answer leaves the fact unjudged, exactly as no answer
   does — which is where the whole corpus already is, and costs nothing.

   **No undo.** `data/memory.edn` is append-only and last-wins by `:id`, so the
   reversal of this is one more append per fact carrying the old shape — or,
   honestly, the backup taken before running it. The substrate's `undo!` covers
   neither this nor anything else in the memory.

   **A dry run costs the same model calls as a live one.** The judgements *are*
   the plan; what a dry run does not cost is a write. Two runs over the same
   corpus can differ on a fact or two — `agent/request` asks at temperature 0.2,
   not 0 — so comparing a dry run against an earlier one is worth doing, and a
   difference in one judgement is a question to answer rather than proof that
   something is broken. A difference in the *set of ids* is not: that means the
   memory moved.

   **The axis is spelled out here as well as in `agent/distill-facts`, and the
   copy is deliberate.** Sharing one string was the alternative and it is worse
   in both directions: this prompt is the record of what judged the existing 80
   facts, so a later reword of the distiller's prompt must not retroactively
   change what this says it did; and the two calls ask different questions in
   different shapes — one distils prose and judges what it found, this one
   judges N given sentences and must name each by id — so only the axis itself
   could be shared and the sentences around it would still diverge. The cost is
   real and is this: if the axis is ever revised, both must move, and nothing
   enforces it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [loci.agent :as agent]
            [loci.memory :as mem]
            [loci.mold :as mold]))

(def system-prompt
  "What the classifier is asked. Public so a measurement can quote the exact
   instruction that produced its numbers rather than paraphrase it.

   The axis is taught BY EXAMPLE and both sides are shown. Named and left
   undefined, \"durable\" gets an answer from a model, just not one about this;
   shown only the durable side, it learns half a distinction and answers true to
   everything, which would pass every structural test in this file and be
   worthless.

   Judgements are keyed by the `id` they name, never by position — see `ask`."
  (str "You judge whether each of these remembered facts will go out of date.\n"
       "\"durable\": true — the claim will never go out of date: a law, a definition, "
       "a mathematical relation, a historical record.\n"
       "\"durable\": false — the claim carries an implicit \"as of now\", so re-checking "
       "the world later could change it: a price, a market share, a capacity, a "
       "current holder of an office, anything measured.\n"
       "\"Kepler's Third Law states that T² ∝ a³\" is durable. \"Lithography capacity is "
       "booked through 2027\" is not — it was true when it was written, and re-checking "
       "could change it.\n"
       "Judge EVERY fact you are given, and name each judgement by the \"id\" it arrived "
       "with. Respond ONLY as JSON "
       "{\"judgements\":[{\"id\":\"mem-1\",\"durable\":true},…]}."))

(def ^:private default-batch
  "Facts per request.

   The whole live memory in one prompt is one long body and one answer the model
   has to keep complete to the end; one fact per request is eighty round trips
   for a judgement that needs no context beyond the sentence itself. Twenty is
   four calls over the corpus this was written for, and an answer short enough
   that a dropped judgement is visible in the plan rather than inferred from a
   count."
  20)

;; ----------------------------------------------------------------------------
;; who is eligible
;; ----------------------------------------------------------------------------

(defn- judged?
  "True for a fact that already carries a judgement — `boolean?` on the value,
   not `contains?` on the key.

   The two differ only for a record whose `:durable?` is neither `true` nor
   `false`, and for that record `boolean?` is the answer that matches `weight`:
   `weight` reads the VALUE, so a `:durable?` of `\"false\"` or nil is a fact that
   ranks as though something judged it while nothing did. Re-judging it is the
   only way it ever gets a real answer, and this pass is what does that."
  [f]
  (boolean? (:durable? f)))

(defn- yours?
  "True for a judgement you made by hand.

   Skipped whatever its value, including a malformed one: repairing your call is
   not this pass's business, and the whole point of `:durable-by` is that an
   agent may revise an agent's judgement and never yours."
  [f]
  (= "you" (:durable-by f)))

(defn- open?
  "True for a fact this pass may judge: nobody has judged it, and nobody includes
   you."
  [f]
  (and (not (yours? f)) (not (judged? f))))

;; ----------------------------------------------------------------------------
;; asking
;; ----------------------------------------------------------------------------

(defn- ask
  "One batch of facts → `{:judgements {id boolean}}`, or `{:error \"…\"}`.

   Answers are matched by **the id they name**, never by position. A model that
   drops one fact out of twenty would otherwise shift every judgement after it
   onto a different sentence, and nothing downstream could tell: the facts would
   all be stamped, all `:durable-by \"agent\"`, and all about their neighbours. An
   id the batch did not contain is dropped for the same reason.

   Two ways a batch fails, and both refuse the whole pass upstream: the answer
   cannot be read as JSON, or it can and carries no usable judgement for any fact
   in it. The second is not pedantry — \"the model answered about none of these\"
   and \"the call did not work\" are the same amount of knowledge, and a run that
   reported `:ok?` with nothing in it would read afterwards as a corpus that had
   been judged.

   Silence about SOME of a batch is a different thing and is not a failure: that
   fact stays unjudged, which is where every fact already is.

   The `try` wraps the call and the parse and nothing else. Both throw for
   reasons a person should read as a sentence rather than a stack trace —
   `agent/chat` when no endpoint is configured, `json/read-str` on an empty body
   — and a catch any wider would swallow a defect in the code below it."
  [batch]
  (let [ids (into #{} (map :id) batch)]
    (try
      (let [body (agent/chat [{:role "system" :content system-prompt}
                              {:role "user"
                               :content (json/write-str
                                         {:facts (mapv #(select-keys % [:id :fact]) batch)})}]
                             :json? true)
            js   (:judgements (json/read-str (str body) :key-fn keyword))
            got  (into {} (keep (fn [{:keys [id durable]}]
                                  (when (and (contains? ids id) (boolean? durable))
                                    [id durable])))
                       (when (sequential? js) js))]
        (if (empty? got)
          {:error (str "the classifier answered about none of the " (count batch)
                       " facts in this batch (" (str/join ", " (sort ids))
                       ") — nothing is written, because a pass that judged none of what "
                       "it asked about is a pass that did not run")}
          {:judgements got}))
      (catch Exception e
        {:error (str "the classifier's answer could not be read: " (.getMessage e)
                     " — the batch was " (str/join ", " (sort ids))
                     ". Nothing is written; a half-judged memory is worse than an "
                     "unjudged one, because nobody can say afterwards which half")}))))

(defn- judge-all
  "Every pending fact judged, in batches — `{:judgements {id boolean}}` — or the
   first batch that failed, as `{:error \"…\"}`.

   `reduced` on the first error rather than collecting them: the overwhelmingly
   likely cause of one failed batch is that the endpoint is down, and three more
   60-second timeouts discover that three more times. `embed-pending!` stops at
   the first failing batch for the same reason.

   Nothing pending means no request at all, which is what makes a second run
   free as well as harmless."
  [pending n]
  (reduce (fn [acc batch]
            (let [r (ask batch)]
              (if (:error r) (reduced r) (update acc :judgements merge (:judgements r)))))
          {:judgements {}}
          (partition-all n pending)))

;; ----------------------------------------------------------------------------
;; writing
;; ----------------------------------------------------------------------------

(defn- classify-one!
  "One fact's judgement, applied and appended. `:applied` or `:declined`.

   The record written is the one the swap landed on, not the one the plan read —
   `store-vector!`'s rule for `store-vector!`'s reason: an embedding or a
   reinforcement that arrived while the classifier was thinking is in the record
   now, and appending a copy taken earlier would roll it back under the same id
   with the pass reporting success.

   The in-swap guard is three checks and each declines a real event:

   · **the text still says what was judged.** A judgement is about a sentence. If
     `remember` rewrote it mid-pass, stamping the new sentence with the old
     sentence's answer is the silent mismatch `store-vector!` exists to decline.
   · **nothing has judged it since** — your toggle in the pane, or a distiller
     pass landing a fact this one was already holding. Both are newer than this
     plan and both win.
   · **it is not a tombstone.** A merge landing mid-pass leaves `:merged-into`
     behind, and appending that record again with a judgement bolted on would
     write a line that only `live` saves us from.

   One append per fact rather than one swap for all of them: the log is
   append-only and last-wins by `:id`, so a run interrupted halfway leaves a
   prefix of judged facts and the rest exactly as they were. Nothing is ever
   rewritten in place — `loci.memory/next-id`'s tombstone reasoning depends on
   that."
  [!facts file {:keys [id fact durable?]}]
  (let [[before after]
        (swap-vals! !facts
                    (fn [fs]
                      (let [rec (get fs id)]
                        (if (and rec
                                 (= fact (:fact rec))
                                 (open? rec)
                                 (nil? (:merged-into rec)))
                          (assoc fs id (assoc rec :durable? durable? :durable-by "agent"))
                          fs))))]
    (if (identical? before after)
      :declined
      (do (mem/append-line! file (get after id)) :applied))))

(defn classify!
  "Judge every unjudged fact in `m` and record the answers. **Dry run by
   default.**

   `m` is a `loci.memory/FileMemory`. Returns a report, never throws:

     :ok?       did the memory end up holding the judgements
     :dry-run?  was this a plan or an act
     :plan      {:classify [{:id :fact :durable?} …]   what would be written
                 :already  [id …]   an agent judged it — a second run's whole answer
                 :yours    [id …]   your judgement, never re-judged
                 :unjudged [id …]   the classifier said nothing usable about these
                 :facts n :appends n :durable n}
     :appended  lines actually appended
     :declined  facts that moved between the plan and the write
     :error     why not, when not

   Those four lists are **disjoint and cover every live fact**, which is the
   invariant worth checking a plan against before running it: a fact missing from
   all four has been dropped, and a fact in two of them means the eligibility
   rules disagree with each other. `:facts` is the same count — live facts, since
   `all-facts` filters the merge's tombstones out and a tombstone is not a fact.

   `:durable` is in the plan so that a classifier answering \"durable\" to
   everything is one number rather than a reading exercise: it would satisfy
   every structural assertion in `classify_test` and be worthless.

   `opts` also takes `:batch-size`, which exists so a test can make a three-fact
   pass into three batches and prove that the third one failing loses the first
   two. Nothing else should need it.

   The order is `:id`, sorted — lexicographic, so `mem-10` precedes `mem-2`. That
   is not insertion order and must not be read as one; it is `recall`'s rule,
   and one arbitrary-but-stated ordering shared with the rest of the codebase
   beats a second one invented here.

   What it refuses, and why refusing beats proceeding: a memory it cannot append
   to (going through `remember` instead would re-stamp `:ts`, which is the whole
   thing this avoids), and any batch the classifier did not answer usably —
   before a single line is written, because the state nobody can describe is the
   half-judged one."
  ([m] (classify! m {}))
  ([m {:keys [dry-run? batch-size] :or {dry-run? true batch-size default-batch}}]
   (let [{:keys [!facts file]} m]
     (if-not (and !facts file)
       {:ok? false :dry-run? dry-run?
        ;; `(pr-str (type m))` and not `(.getName (class m))`, which NPEs on a
        ;; nil memory — a refusal that throws is not a refusal
        :error (str "this needs a loci.memory/FileMemory — it appends to the fact log the "
                    "way store-vector! and unmerge! do, and " (pr-str (type m))
                    " has no log")}
       (let [facts   (vec (sort-by :id (mold/all-facts m)))
             pending (filterv open? facts)
             asked   (judge-all pending batch-size)]
         (if-let [why (:error asked)]
           {:ok? false :dry-run? dry-run? :error why}
           (let [js       (:judgements asked)
                 classify (vec (for [f pending :when (contains? js (:id f))]
                                 {:id (:id f) :fact (:fact f) :durable? (get js (:id f))}))
                 plan     {:classify classify
                           ;; disjoint from :yours rather than overlapping it —
                           ;; a fact you judged is skipped for being yours, and
                           ;; reporting it twice would make the four lists stop
                           ;; adding up to the corpus
                           :already  (mapv :id (remove yours? (filter judged? facts)))
                           :yours    (mapv :id (filter yours? facts))
                           :unjudged (vec (for [f pending :when (not (contains? js (:id f)))]
                                            (:id f)))
                           :facts    (count facts)
                           :appends  (count classify)
                           :durable  (count (filter :durable? classify))}]
             (if dry-run?
               {:ok? true :dry-run? true :plan plan}
               (let [done (mapv (fn [e] [(:id e) (classify-one! !facts file e)]) classify)]
                 {:ok? true :dry-run? false :plan plan
                  :appended (count (filter #(= :applied (second %)) done))
                  :declined (mapv first (filter #(= :declined (second %)) done))})))))))))
