(ns loci.repair
  "The 2026-07-23 repair: three astronomy deep-dive notebooks that a count-based
   id minter silently overwrote, and the fifteen facts distilled from them.

   **What happened is in
   `docs/superpowers/specs/2026-08-09-overwritten-deep-dives-forensics.md`**, and
   this namespace is unreviewable without it. In one paragraph: a table column
   named `Key Proponent(s)` produced a keyword `edn/read` cannot read, the loader
   of the day treated the parse failure as EOF, and the store booted with 49 of
   80 events. Deep-dive ids were minted from `(inc (count (nb/notebooks st)))` —
   a count, over that truncated view — so the next run re-minted `space:dd-16`,
   `17` and `18` onto three live notebooks. `:put` is `assoc-in` with no
   occupancy check, so nothing warned. The fifteen facts were filed **correctly
   at the time**; their notebooks were destroyed three hours later.

   **A namespace and not a REPL session, for `loci.migrate`'s reason**: a repair
   you can read, test, dry-run and review is a different object from a form
   someone typed once. This one runs against data that cannot be regenerated, so
   it is built to be *worth* running:

   · **Nothing runs on require.** No `defonce` that opens a store, no top-level
     side effect. Both entry points are `{:dry-run? true}` by DEFAULT, and a dry
     run returns the plan it would have applied without applying any of it.
   · **Every refusal is a returned report, never a throw** — `edn->datalevin!`'s
     contract, for the same reason: the caller is a person reading a sentence.
   · **A repair that half-applies is worse than one that refuses.** Both halves
     check the whole world before writing any of it, and both are no-ops the
     second time.
   · **The restoration is one `:tx`**, so `sub/undo!` reverses all of it — while
     it is still the last commit. The memory half has no undo of its own: its
     reversal is the backup, which is why Task 4 takes one.

   Restored under **fresh ids**, never the old ones. `space:dd-16/17/18` now name
   live semiconductor notebooks; re-minting onto them is the exact bug being
   repaired, one layer up.

   Nothing here is retyped from the forensics. The overwritten `:put`s are still
   in the log — last-write-wins hid them from the fold and did not remove them —
   so `first-put` reads titles, intents, spawning prompts and findings bodies out
   of the events that recorded them. The literals below are what the repair
   ASSERTS, not what it writes: three ids, three titles, two body lengths and
   fifteen fact ids, every one of them a number a reviewer can check against the
   forensics by eye."
  (:require [clojure.string :as str]
            [loci.memory :as mem]
            [loci.notebook :as nb]
            [loci.server :as server]
            [loci.substrate :as sub]))

(def ^:private parent
  "The notebook all three dives were spawned from. Asserted, not written: a
   `:put` whose `:spawned-by` says anything else is not the event this repair
   thinks it is looking at."
  "space:cosmos")

(def overwritten
  "The damage, one entry per destroyed notebook, in the order the log created
   them.

   **The fifteen facts are named one by one, and that is a decision.** The
   tempting alternative is to derive them — \"the facts whose `:source :space` is
   `space:dd-16` and whose `:ts` predates that notebook's second `:put`\" — and
   it is the wrong one here, for a reason the corpus makes concrete rather than
   hypothetical: `mem-42`…`mem-46` carry `{:obj \"find:dd-16-1\", :space
   \"space:dd-16\"}` too, and they are the **semiconductor** facts, correctly
   filed. Ten facts match the source; five of them must not move. The only thing
   separating the two groups is three hours on a clock, so a derivation's whole
   correctness would rest on a `:ts` boundary that can only be checked by running
   it — against data that cannot be regenerated, once.

   Fifteen literals can be checked against § 4 of the forensics by eye, by a
   reviewer, before anything runs. The cost of the literal list is that it does
   not travel to another corpus, and `refile-astronomy-facts!` turns that cost
   into a refusal rather than a mis-target: every id must be found carrying
   exactly the `:source` recorded here, or nothing is written at all.

   `:body-chars` is the **document body**, not the log line. An earlier draft of
   the plan cited 1,733 and 2,752, which are whole EDN lines with the `:tx`
   wrapper and escaping included. `find:dd-18-1` is 0 because it was committed
   with `:value \"\"` — that is asserted like any other length, and it is why the
   third notebook is restored with no findings cell: recreating an empty document
   to hold five facts distilled from nothing would manufacture provenance the log
   says never existed."
  [{:space "space:dd-16" :title "Inner vs. Outer Planets"
    :findings "find:dd-16-1" :body-chars 1416
    :adopt "tbl:extract-14-588"
    :facts ["mem-23" "mem-24" "mem-25" "mem-26" "mem-27"]}
   {:space "space:dd-17" :title "Planetary Moons"
    :findings "find:dd-17-1" :body-chars 2442
    :facts ["mem-28" "mem-29" "mem-30" "mem-31" "mem-32"]}
   {:space "space:dd-18" :title "Orbital Period Scaling"
    :findings "find:dd-18-1" :body-chars 0
    :facts ["mem-33" "mem-34" "mem-35" "mem-36" "mem-37"]}])

;; ----------------------------------------------------------------------------
;; reading the damage back out of the log
;; ----------------------------------------------------------------------------

(defn first-put
  "The value of the FIRST `:put` for `id` anywhere in `log`, descending into a
   `:tx`.

   This is what makes the repair a restoration rather than a reconstruction. The
   overwritten notebooks are missing from the *fold* and only from the fold:
   last-write-wins handed each id to the later `:put` and left the earlier event
   exactly where it was written. So every character restored below — titles,
   intents, spawning prompts, findings bodies — comes out of the event that
   recorded it in July, and nothing is retyped from a document.

   nil for an id the log never `:put` at all, which is a refusal upstream and
   never a reason to invent one."
  [log id]
  (some (fn [ev]
          (some (fn [e] (when (and (= :put (:op e)) (= id (:id e))) (:value e)))
                (if (= :tx (:op ev)) (:events ev) [ev])))
        log))

(defn restorations
  "old id → the notebook that now holds its contents, read out of the store.

   `:restored-from` on a notebook's `:value` is the only record anywhere that
   this repair ran, and it is deliberately shaped like `:merged-from`, which is
   already how a notebook says where its contents came from. Three consequences
   worth having:

   · **the two halves need not run in one process.** `refile-astronomy-facts!`
     finds its targets by reading this, so Task 4 can restore, verify, and
     re-file afterwards — or a year later;
   · **running twice is detectable**, and is a no-op rather than a second set of
     notebooks;
   · **a half-repaired store is detectable**, and is refused.

   Invisible to the shell: `state-payload` copies only the keys it names. Invisible
   to the graph: `nb/links` reads `:merged-from` and not this, so no phantom edge
   appears beside the real `spawned-by` one."
  [st]
  (into {} (keep (fn [o] (when-let [from (get-in o [:value :restored-from])] [from o])))
        (nb/notebooks st)))

;; ----------------------------------------------------------------------------
;; refusals — everything that must be true before anything is written
;; ----------------------------------------------------------------------------

(defn- source-complaint
  "Why the log does not hold the events this entry expects to restore, as a
   sentence, or nil.

   Every check is against something the forensics measured, so a store that has
   moved on since — or a store that is not this one at all — stops here with a
   number in its hand rather than restoring whatever it happens to find under
   `space:dd-16`."
  [log {:keys [space title findings body-chars]}]
  (let [nbv (first-put log space)
        doc (first-put log findings)]
    (cond
      (nil? nbv)
      (str "the log holds no :put for " space " at all, so there is nothing to restore "
           "— this is not the store the repair was written against")

      (not= :space (:kind nbv))
      (str space "'s first :put is a " (pr-str (:kind nbv)) ", not a notebook")

      (not= title (:title nbv))
      (str space "'s first :put is titled " (pr-str (:title nbv)) ", not "
           (pr-str title) " — the log does not say what the forensics says it says")

      (not= parent (get-in nbv [:value :spawned-by :space]))
      (str space " was spawned by " (pr-str (get-in nbv [:value :spawned-by :space]))
           ", not by " parent " — restoring it under " parent " would invent a lineage")

      (nil? doc)
      (str "the log holds no :put for " findings ", which " space " was celled to")

      (not= :doc (:kind doc))
      (str findings "'s first :put is a " (pr-str (:kind doc)) ", not a document")

      (not= body-chars (count (:value doc)))
      (str findings "'s first :put has a body of " (count (:value doc))
           " characters, not the " body-chars " the forensics measured — this is not "
           "the document that was overwritten, so nothing is restored"))))

(defn- adoption-complaint
  "Why the orphan table cannot be adopted, or nil.

   `tbl:extract-14-588` is the one visible survivor of the astronomy dives: its
   object is still live and correct, and only its cell died, with `space:dd-16`'s
   `:cells` vector, when ev 65 replaced the notebook value wholesale. So it is
   adopted — a ref in the restored notebook and no `:put` at all. A table that
   already belongs to a notebook is a table something else has since claimed, and
   giving it a second owner is not this repair's business."
  [st id]
  (let [o       (sub/object st id)
        holders (filterv #(contains? (nb/refs-of %) id) (nb/notebooks st))]
    (cond
      (nil? o)
      (str "the orphaned table " id " is not in the store — it is what ev 57 saved "
           "into the first dive and the one survivor of it")

      (seq holders)
      (str id " is no longer an orphan: " (str/join ", " (sort (map :id holders)))
           " references it, so adopting it would give it a second owner"))))

;; ----------------------------------------------------------------------------
;; Task 2 — the three notebooks
;; ----------------------------------------------------------------------------

(defn- mint
  "The three restorations — new ids, new objects, cells and all — minted against
   a view of the store that already holds the ones minted before them.

   The view is the point. Nothing is committed until the single `:tx` at the end,
   so asking the LIVE store for three notebook ids in a row hands back the same
   id three times, and the second `:put` in the transaction erases the first with
   no error anywhere. That is the bug being repaired, reproduced inside its own
   repair. A `FrozenStore` over the objects-so-far costs nothing and makes each
   mint see the world the one before it left.

   `server/fresh-dd-id` and `server/next-id` rather than local copies, and
   `server/findings-prefix` rather than a second spelling of the same string: a
   repair that mints by its own rule is how one prefix comes to have two
   counters, neither able to see all the ids under it (§ 7 of the forensics).

   The restored objects are the overwritten ones with an id swapped in — `:kind`,
   `:title`, `:intent`, `:spawned-by` (prompt included) and any `:tags` travel
   verbatim, because the whole map does. `:cells` is rebuilt, since the old one
   named documents whose ids now belong to someone else."
  [st log]
  (:out
   (reduce
    (fn [{:keys [objm out]} {:keys [space findings body-chars adopt]}]
      (let [view  (sub/->FrozenStore {:objects objm} [])
            sid   (server/fresh-dd-id view)
            doc?  (pos? body-chars)
            fid   (when doc? (server/next-id view (server/findings-prefix sid)))
            doco  (when doc? (assoc (first-put log findings) :id fid))
            cells (cond-> [] adopt (conj {:ref adopt}) doc? (conj {:ref fid}))
            nbo   (-> (first-put log space)
                      (assoc :id sid)
                      (update :value assoc :cells cells :restored-from space))]
        {:objm (cond-> (assoc objm sid nbo) doc? (assoc fid doco))
         :out  (conj out (cond-> {:from space :id sid :title (:title nbo)
                                  :spawned-by (get-in nbo [:value :spawned-by :space])
                                  :cells cells :object nbo}
                           adopt (assoc :adopts adopt)
                           doc?  (assoc :findings {:from findings :id fid
                                                   :body-chars body-chars :object doco})))}))
    {:objm (sub/objects st) :out []}
    overwritten)))

(defn- printable
  "The plan without the restored objects themselves — the report is meant to be
   pasted into a task record and compared line by line against the rehearsal, and
   two findings documents would put 3,858 characters of July prose in the middle
   of it. Everything a reader checks (ids, titles, lineage, cells, body lengths)
   stays."
  [entries]
  (mapv (fn [e] (cond-> (dissoc e :object)
                  (:findings e) (update :findings dissoc :object)))
        entries))

(defn restore-astronomy-dives!
  "Restore the three astronomy deep dives under fresh ids. **Dry run by default.**

   Returns a report, never throws:

     :ok?           did the store end up holding the restoration
     :dry-run?      was this a plan or an act
     :plan          {:notebooks [{:from :id :title :spawned-by :cells :adopts?
                                  :findings {:from :id :body-chars}?} …]
                     :events n :commits 1}
     :already-done? the three are already restored; nothing was written
     :committed     the store's event count afterwards
     :error         why not, when not

   The plan a dry run returns is **the same value** a live run reports, computed
   by the same code — that is what makes Task 4's \"compare the dry run against
   the rehearsal line by line\" mean something.

   One `sub/commit!` of one `:tx`, so `sub/undo!` reverses the whole restoration
   — and only while it is still the last commit, which is the honest limit of
   this half's reversibility.

   What it refuses, and why refusing beats proceeding: a store already half
   repaired (finishing it off would leave nobody able to say what state it was
   in), a log whose events do not match what the forensics measured (then it is
   not this store), an orphan that has since been adopted by someone else, and a
   minted id that is somehow occupied."
  ([st] (restore-astronomy-dives! st {}))
  ([st {:keys [dry-run?] :or {dry-run? true}}]
   (let [log  (vec (sub/history st))
         done (restorations st)
         mine (mapv :space overwritten)
         held (filterv done mine)]
     (cond
       (= (count held) (count mine))
       {:ok? true :dry-run? dry-run? :already-done? true
        :plan {:notebooks (mapv (fn [s] {:from s :id (:id (done s)) :title (:title (done s))}) mine)
               :events 0 :commits 0}}

       (seq held)
       {:ok? false :dry-run? dry-run?
        :error (str "this store is half-repaired: " (str/join ", " held)
                    " already " (if (= 1 (count held)) "has a restored notebook" "have restored notebooks")
                    " and " (str/join ", " (remove (set held) mine)) " "
                    (if (= 1 (count (remove (set held) mine))) "does not" "do not")
                    ". Refusing rather than finishing a repair nobody can now describe the start of")}

       :else
       (if-let [why (or (some #(source-complaint log %) overwritten)
                        (some #(adoption-complaint st %) (keep :adopt overwritten)))]
         {:ok? false :dry-run? dry-run? :error why}
         (let [entries (mint st log)
               ids     (concat (map :id entries) (keep (comp :id :findings) entries))
               taken   (filterv #(sub/object st %) ids)
               events  (vec (mapcat (fn [e]
                                      ;; the document first, so that at no point in
                                      ;; the fold does a notebook cell dangle — the
                                      ;; order research! writes them in
                                      (cond-> []
                                        (:findings e) (conj {:op :put :id (get-in e [:findings :id])
                                                             :value (get-in e [:findings :object])})
                                        :always (conj {:op :put :id (:id e) :value (:object e)})))
                                    entries))
               plan    {:notebooks (printable entries) :events (count events) :commits 1}]
           (if (seq taken)
             {:ok? false :dry-run? dry-run?
              :error (str "the ids this repair minted are occupied: " (str/join ", " taken)
                          " — minting onto a live object is the bug being repaired")}
             (if dry-run?
               {:ok? true :dry-run? true :plan plan}
               (do (sub/commit! st {:op :tx :events events})
                   {:ok? true :dry-run? false :plan plan
                    :committed (count (sub/history st))})))))))))

;; ----------------------------------------------------------------------------
;; Task 3 — the fifteen facts
;; ----------------------------------------------------------------------------

(defn- target-of
  "Where an entry's facts belong now: `{:obj <restored findings doc> :space
   <restored notebook>}`, or nil when nothing has been restored from it yet.

   **Both halves, and that is the point of the whole exercise.** Rewriting
   `:space` alone would leave `:obj \"find:dd-16-1\"` — a *cell of today's*
   `space:dd-16` — and `lineage-sources` puts a notebook's cell ids in scope
   beside its own, so the facts would still sit inside the semiconductor
   notebook's scope. `remembered-context` cites `(or (:obj …) (:space …))`, so
   the agent would still print a Jupiter fact as `⌾ find:dd-16-1`.

   `:obj` is found by asking the restored notebook which of its cells is a
   document, rather than by remembering what was minted: the third notebook has
   no findings document, so its `:obj` comes out nil without a special case —
   `{:obj nil :space …}` is a shape `ask!` already writes, through `distill!`'s
   nil `obj-id`."
  [st {:keys [space]}]
  (when-let [o (get (restorations st) space)]
    {:obj   (first (for [{:keys [ref]} (nb/cells-of o)
                         :when (= :doc (:kind (sub/object st ref)))]
                     ref))
     :space (:id o)}))

(defn- refile-plan
  "What each of the fifteen would become, or the reasons it cannot.

   Three states per fact, and only three: it carries the `:source` the forensics
   recorded (re-file it), it already carries the target (leave it — this is what
   makes a second run a no-op), or it carries something else. The third is a
   refusal for the whole run, not a skip: a fact whose provenance has moved since
   the forensics is a fact somebody or something else has touched, and applying
   fourteen of fifteen would leave a memory nobody can characterise."
  [st facts]
  (reduce
   (fn [acc {:keys [space findings] :as entry}]
     (if-let [to (target-of st entry)]
       (let [from {:obj findings :space space}]
         (reduce
          (fn [acc id]
            (let [rec (get facts id)]
              (cond
                (nil? rec)
                (update acc :errors conj
                        (str "there is no fact " id " in this memory — the fifteen are named "
                             "in loci.repair/overwritten and every one of them must be found"))

                (:merged-into rec)
                (update acc :errors conj
                        (str id " is a tombstone: the semantic merge absorbed it into "
                             (:merged-into rec) ", which is where its provenance now lives"))

                (= to (:source rec))     (update acc :already conj id)
                (= from (:source rec))   (update acc :refile conj {:id id :from from :to to})

                :else
                (update acc :errors conj
                        (str id "'s :source is " (pr-str (:source rec)) ", which is neither "
                             (pr-str from) " nor " (pr-str to)
                             " — something has moved this fact since the forensics, so nothing "
                             "is re-filed")))))
          acc (:facts entry)))
       (update acc :errors conj
               (str "nothing in the store was restored from " space
                    " — run restore-astronomy-dives! first, or these facts have nowhere to go"))))
   {:refile [] :already [] :errors []}
   overwritten))

(defn- refile-one!
  "One fact's `:source`, replaced, and the record appended.

   The record written is the one taken out of the map the swap landed on, not the
   one the plan read — `store-vector!`'s rule, for `store-vector!`'s reason: a
   reinforcement or an embedding that landed while the repair was deciding is in
   the record now, and appending a copy taken earlier would roll it back under
   the same id with the log reporting success. The in-swap guard is `unmerge!`'s:
   if the `:source` moved since the plan, this writes nothing and says so, rather
   than overwriting whatever moved it.

   **`:ts` and `:strength` are not touched, and that is the whole reason this
   does not go through `mold/remember`.** `remember` would find the fact itself
   as a jaccard ≥ 0.6 duplicate, `(update :strength inc)` and
   `(assoc :ts (System/currentTimeMillis))` — and `:ts` is a multiplier in
   `weight`, so all fifteen planet facts would jump to the top of every ranking,
   which is a worse version of the bug being repaired. Worse still, its `dup` is
   a `some` over an unordered map and can match a *different* fact whose wording
   overlaps 60%; `mem-33`/`mem-34` and `mem-25`/`mem-35` are live candidates.

   One append per fact rather than one swap for all fifteen: the log is
   append-only and last-wins by `:id`, so a run interrupted halfway leaves a
   prefix of correct facts and the rest exactly as they were. Nothing is ever
   rewritten in place — `loci.memory/next-id`'s tombstone reasoning depends on
   that."
  [!facts file {:keys [id from to]}]
  (let [[before after] (swap-vals! !facts
                                   (fn [fs] (if (= from (:source (get fs id)))
                                              (assoc-in fs [id :source] to)
                                              fs)))]
    (if (identical? before after)
      :declined
      (do (mem/append-line! file (get after id)) :applied))))

(defn refile-astronomy-facts!
  "Re-file the fifteen facts onto the notebooks `restore-astronomy-dives!` put
   back. **Dry run by default.**

   `m` is a `loci.memory/FileMemory`; `st` is the store the restoration landed
   in, and is read for one thing only — which notebook now carries each old id,
   and which of its cells is the findings document (`target-of`).

   Returns a report, never throws:

     :ok?       did the memory end up holding the re-filed facts
     :dry-run?  was this a plan or an act
     :plan      {:refile [{:id :from :to} …] :already [id …] :appends n}
     :appended  lines actually appended
     :declined  facts whose :source moved between the plan and the write
     :error     why not, when not

   **No undo.** `data/memory.edn` is append-only and last-wins by `:id`, so the
   reversal of this is fifteen more appends carrying the old `:source` — or, more
   simply, the backup Task 4 takes before running it. Say so plainly rather than
   let a reader assume the substrate's `undo!` covers both halves; it covers
   neither this nor anything committed after it."
  ([st m] (refile-astronomy-facts! st m {}))
  ([st m {:keys [dry-run?] :or {dry-run? true}}]
   (let [{:keys [!facts file]} m]
     (if-not (and !facts file)
       {:ok? false :dry-run? dry-run?
        :error (str "this needs a loci.memory/FileMemory — it appends to the fact log the way "
                    "store-vector! and unmerge! do, and " (.getName (class m)) " has no log")}
       (let [{:keys [refile already errors]} (refile-plan st @!facts)
             plan {:refile refile :already already :appends (count refile)}]
         (cond
           (seq errors)
           {:ok? false :dry-run? dry-run? :error (first errors) :errors errors :plan plan}

           dry-run?
           {:ok? true :dry-run? true :plan plan}

           :else
           (let [done (mapv (fn [e] [(:id e) (refile-one! !facts file e)]) refile)]
             {:ok? true :dry-run? false :plan plan
              :appended (count (filter #(= :applied (second %)) done))
              :declined (mapv first (filter #(= :declined (second %)) done))})))))))
