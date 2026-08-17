(ns loci.server
  "Layer 5 backend: the substrate + mold layer served as JSON to the loci shell.

   The HTTP boundary is the substrate/assistance seam — everything the frontend
   shows is molded by `loci.mold` on the Clojure side; the frontend just lays it
   out. (Same code path the headless demo exercises.)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sci.core :as sci]
            [org.httpkit.server :as http]
            [loci.agent :as agent]
            [loci.chunks :as ch]
            [loci.config :as config]
            [loci.content :as c]
            [loci.embed :as embed]
            [loci.entities :as ent]
            [loci.fnlib :as fnlib]
            [loci.lake :as lake]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools]
            [loci.viewspec :as vs])
  (:import [java.util Locale])
  ;; :gen-class is what makes `java -jar` possible at all. AOT alone emits
  ;; loci/server$_main.class but no class *named* loci.server, so the uberjar's
  ;; Main-Class points at nothing and the jar dies with ClassNotFoundException
  ;; before a line of loci runs. `clojure -M:serve` never noticed because it
  ;; loads the namespace instead of a class.
  (:gen-class))

(defn store [] @c/store)

;; ---- keyword <-> string for view ids that round-trip to the browser ----
(defn- kw->str [k] (if (namespace k) (str (namespace k) "/" (name k)) (name k)))
(defn- str->kw [s] (if (str/includes? s "/")
                     (let [[n nm] (str/split s #"/" 2)] (keyword n nm))
                     (keyword s)))

(defn- json-resp [data]
  {:status 200
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str data)})

(defn- parse-query [qs]
  (into {} (for [pair (some-> qs (str/split #"&")) :when (seq pair)]
             (let [[k v] (str/split pair #"=" 2)]
               [k (some-> v (java.net.URLDecoder/decode "UTF-8"))]))))

(defn- body-json [req] (when-let [b (:body req)] (json/read-str (slurp b) :key-fn keyword)))

;; ---- time travel: ?at=N freezes any read at a log prefix; /api/events
;; labels the log for the scrubber. The past is read-only by construction. ----
(defn store-at
  "The store to read from: live when at-str is nil/garbage, else a read-only
   frozen window clamped to [0, event-count]."
  [st at-str]
  (if-let [n (and at-str (parse-long at-str))]
    (sub/frozen-at st (-> n (max 0) (min (count (sub/history st)))))
    st))

(defn- title-of [objs id] (or (:title (get objs id)) id "object"))

(defn- event-label [objs {:keys [op id events]}]
  (case op
    :put    (str "＋ " (title-of objs id))
    ;; :append edits one object the way :assoc does — without this it would fall
    ;; to the default and the ⏱ scrubber would read "append" with no title
    (:assoc :append) (str "✎ " (title-of objs id))
    :delete (str "✕ " (or id "object"))
    :tx     (str (event-label objs (first events))
                 (when (> (count events) 1) (str " (+" (dec (count events)) ")")))
    (name (or op :event))))

(defn events-payload [st]
  (let [evs  (sub/history st)
        objs (sub/objects st)]   ; materialize ONCE — labeling is O(n), not O(n²)
    {:total (count evs)
     :events (vec (map-indexed (fn [i ev] {:i (inc i) :op (name (or (:op ev) :event))
                                           :ts (:ts ev) :label (event-label objs ev)})
                               evs))}))

;; ---- recency: one fold over the log gives every object its newest event ----
;; Read from the log rather than Datalevin's `touched` dbi on purpose: the EDN
;; store is still the documented rollback and the parity suite runs both, and a
;; LEAP that ranks correctly on one store and arbitrarily on the other is worse
;; than one uniformly a millisecond slower. O(events) per request — at ~10^5
;; this must become a map maintained beside the state atom instead.
(defn- event-ids
  "Every object id an event names — a :tx has none of its own, its sub-events do."
  [ev]
  (if (= :tx (:op ev))
    (mapcat event-ids (:events ev))
    (when-let [id (:id ev)] [id])))

(defn last-touched
  "{object-id newest-event-ts} across the whole log."
  [st]
  (reduce (fn [m ev]
            (let [ts (:ts ev)]
              (reduce (fn [m id] (assoc m id ts)) m (event-ids ev))))
          {} (sub/history st)))

(defn- touched-of
  "The recency of a notebook we already hold — taking the object as well as its
   id keeps `state-payload` at one materialization, not one per space (`object`
   on both EDN stores re-folds the whole log to answer)."
  [touched id o]
  (apply max 0 (keep touched (cons id (keep :ref (nb/cells-of o))))))

(defn notebook-touched
  "A notebook is as recent as the newest thing in it — editing a table inside a
   hub makes the hub recent, which is what a reader means by 'touched'."
  [st touched space-id]
  (touched-of touched space-id (sub/object st space-id)))

;; ---- the tag-colour registry, read here and written far below: both payloads
;; have to hide the object, so its id and reader have to be defined before them ----
(def ^:private palette-id "tag-palette")

(defn tag-colors
  "The registry: tag name → hex. Empty until the first tag is set."
  [st]
  (or (:value (sub/object st palette-id)) {}))

;; ---- payloads ----
(defn state-payload [st]
  (let [objm (sub/objects st)
        objs (vals objm)
        touched (last-touched st)]
    {:events  (count (sub/history st))
     :spaces  (->> objs (filter #(= :space (:kind %)))
                   (map (fn [s] (cond-> {:id (:id s) :title (:title s)
                                         :intent (get-in s [:value :intent])
                                         :touched (touched-of touched (:id s) s)
                                         :members (vec (keep :ref (nb/cells-of s)))}
                                  (get-in s [:value :spawned-by :space])
                                  (assoc :spawned-by (get-in s [:value :spawned-by :space]))
                                  (get-in s [:value :merged-from])
                                  (assoc :merged-from (get-in s [:value :merged-from]))
                                  (seq (get-in s [:value :tags]))
                                  (assoc :tags (get-in s [:value :tags])))))
                   vec)
     :objects (->> objs (remove #(#{:space :viewspec :applet :fn :palette} (:kind %)))
                   (map (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))}))
                   vec)
     ;; out of the map already in hand, not via `tag-colors`: that would re-fold
     ;; the whole log for one object, and this payload is built on nearly every
     ;; write — 2.2 ms this way against 3.9 ms on a 3003-event log
     :tag-colors (or (:value (get objm palette-id)) {})
     ;; ONE integer, for the badge on the flows button. The per-flow arithmetic
     ;; stays in `/api/flows`, which is fetched only when the pane opens; this
     ;; is a filter over a sequence already in hand, and it is the difference
     ;; between noticing a flow waiting in another notebook and wandering into
     ;; it by accident.
     :flows-need-you (count (filter #(and (= :flow (:kind %))
                                          (= "needs-you" (get-in % [:value :status])))
                                    objs))}))

;; ---- the entity page ----
;;
;; Above `mold-payload` and not beside the other `/api/…` builders far below,
;; because `mold-payload` calls `entity-payload` for a materialized entity's
;; object page. A forward reference in Clojure is a compile error, not a warning.
;; All any of it needs is the requires and `sub/objects`.

(def ^:private entity-fact-cap
  "How many of an entity's facts the page ranks and ships.

   A cap, and deliberately **not a silent one**: `:count` on the payload counts
   the live facts carrying the name out of the *index*, never out of this page,
   so a subject with more facts than this renders \"50 of 137\" rather than
   quietly looking like it has fifty. That is the whole reason the two are
   separate keys instead of one number, and the reason a renderer is told about
   both.

   Fifty because this is a page load behind a modal and a fact is a sentence
   plus its provenance — a few hundred bytes of JSON each. The alternative
   considered was `:k (count (get idx nm))`, which removes the cap outright and
   makes the two numbers always equal; rejected because it makes the size of a
   page load a property of the corpus, and the corpus only grows."
  50)

(defn- entity-objects
  "Normalized entity name → the object materializing it, out of an objects map
   already in hand.

   Keyed on `[:value :name]` and **never on the title**: the title is a display
   string, and renaming `TSMC` to `TSMC (foundry)` must not re-identify the
   entity or orphan every fact that mentions it.

   The stored name is normalized here rather than trusted, for the reason
   `ent/promoted` normalizes its own `materialized` argument instead of trusting
   it. If this did not, an object carrying an un-normalized `TSMC` would make
   `:promoted` true — through `promoted`, which normalizes — while `:object`
   stayed absent, through this, which would not: one payload contradicting
   itself about whether the entity has been materialized at all.

   **The lexicographically least id wins a duplicate — which is not the same as
   the first created**, and the rule is determinism, not recency. `\"ent:10\"`
   sorts before `\"ent:2\"`, so past nine entities the survivor is not the older
   object; do not read it as one. The id's shape is not this function's to know,
   and parsing a numeric suffix out of it would make this the second place that
   believes something about how entity ids are minted. What is actually needed
   is that the answer not change between two JVMs: two objects for one name is a
   race and not a normal state, but `vals` of an objects map is hash order, so
   with no rule at all the page would name one of the two arbitrarily and
   nothing in the log would explain which.

   Takes the objects map, not the store. On the EDN stores `sub/object` re-folds
   the whole event log per lookup, so one materialization shared between the
   entity lookup, the promotion set and every source's title is 1.7–3.5× cheaper
   there; on Datalevin, which is production, `object` is a map lookup and this
   buys nothing. It is simpler either way, and either way it is about 2% of the
   page — the two passes over the whole memory in `entity-payload` are the
   cost."
  [objm]
  (reduce (fn [m o]
            (let [n (ent/normalize (get-in o [:value :name]))]
              (if (or (str/blank? n)
                      (and (contains? m n) (neg? (compare (:id (get m n)) (:id o)))))
                m
                (assoc m n o))))
          {}
          (filter #(= :entity (:kind %)) (vals objm))))

(defn entity-object
  "The materialized object for a normalized entity name, or nil. Matched on
   `[:value :name]`, never on the title — see `entity-objects`.

   `nm` is expected already normalized, as every name that has been through
   `ent/normalize` or `ent/detect` is. Handing it a raw one simply misses, which
   is the silent failure `ent/detect`'s docstring names — so normalize at the
   door, as `entity-payload` does, rather than here on every lookup.

   **A store-taking convenience, and NOT the calling convention for anything
   that looks up more than one name.** It rebuilds the whole objects map per
   call, so a loop over it is one map per name: measured on an EDN store,
   `leap-payload`'s subject group at 360 matching names over 900 objects cost
   121.98 ms this way against 1.66 ms building `entity-objects` once. Every
   production caller — `leap-payload`, `entity-payload`, `materialize-entity!` —
   therefore calls `entity-objects` with a map already in hand, and this
   survives for callers holding a store and wanting exactly one answer."
  [st nm]
  (get (entity-objects (sub/objects st)) nm))

(defn materialized-names
  "The normalized names that already have an object, so `ent/promoted` can be
   told which entities never demote.

   The same store-taking convenience `entity-object` is, with the same warning:
   it builds the whole objects map, so a caller that also wants the objects
   themselves is asking for two maps. `leap-payload` and `entity-payload` both
   want both, so both bind `(entity-objects objm)` once and pass `(keys …)` to
   `ent/promoted` — which is what `ent/promoted`'s docstring means by
   \"`materialized` is normalized and blank-filtered here\", since those keys
   already are. This is the one-line spelling for a caller that wants only the
   names."
  [st]
  (set (keys (entity-objects (sub/objects st)))))

(defn entity-payload
  "The entity page: the subject, the live facts that mention it ranked by the
   ordinary recall score, and the objects and notebooks those facts came from.

   **One payload, two surfaces.** `GET /api/entity?name=…` serves it to the
   modal you get before materializing; `mold-payload` returns the same map under
   `:rendered` for the entity object's page afterwards. Two renderers would
   drift, and the drift would be invisible until someone materialized something
   and the page they had been reading changed under them.

   `:semantic? false` — the candidate set is already exactly the facts carrying
   this name, so meaning has nothing left to decide, and this is a page load:
   embedding the query would be a 20–50 ms round trip to reorder facts that are
   all on the subject already.

   `:subjects` carries the name because a multi-word one matches no query token
   — `taiwan semiconductor manufacturing` is three tokens to the scorer and one
   name to the index — so without it a fact carrying only that name scores 0 on
   the entity half and `recall` drops it for not being positive: an entity page
   with facts behind it showing none. `:filter {:entities}` carries it too, and
   the two are not the same thing (see `recall`'s `opts:` note): the filter
   makes facts eligible, the subject makes them rank. Naming it in one and not
   the other is the documented silent failure.

   `:count` is how many live facts carry the name and `:facts` is the ranked
   page of at most `entity-fact-cap` of them. **The cap is what normally
   separates them** — the candidate set `recall` sees is exactly the index's own
   set, and every fact in it scores above zero — which is what lets the page say
   \"50 of 137\" instead of truncating in silence. One other thing can:
   `mem/all-facts` and `mold/recall` deref the fact map independently, so a
   `remember` landing between them is counted by one and not the other. Left
   that way deliberately — the alternative is to hand `recall` a snapshot, which
   would put this function inside `loci.memory`'s consistency model to make a
   page load off by one for a moment.

   `:promoted` false with facts present is a real state and must render: a merge
   can push a name back below the threshold, and the page should say so rather
   than vanish.

   `:sources` is in the facts' rank order — the best fact's origins first —
   deduped, with each fact's own ids sorted so nothing depends on the iteration
   order of the set `mem/source-ids` hands back. An id with **no object, or no
   real one**, is DROPPED rather than rendered as `{:title nil}`: a fact outlives
   the document it was distilled from, and a chip with no name on it is worse
   than no chip. Documents and notebooks share one list because a fact's `:source`
   names both and the question the list answers — where did this come from — is
   answered by either.

   Cost: two passes over the whole memory — `ent/index` here for `:count` and
   `:promoted`, and `recall`'s own `df-map` a moment later — plus one
   materialization of the objects map, shared between the entity lookup, the
   promotion set and every source's title. The same shape and the same reason as
   `remembered-context`'s: sharing one index would mean handing `loci.memory` a
   pre-built projection instead of a fact sequence, and this runs on a click, not
   on a keystroke."
  [st m raw-name]
  ;; `nm`, not `name`: a local called `name` shadows clojure.core/name, which
  ;; each source's `:kind` needs below.
  (let [nm (ent/normalize raw-name)]
    (if (str/blank? nm)
      ;; ahead of `all-facts`, `ent/index` and the objects map — none of which
      ;; has anything to answer for a request that named no subject, and the
      ;; last of them walks every object in the store
      {:error "no entity name given"}
      (let [objm  (sub/objects st)
            ments (entity-objects objm)
            idx   (ent/index (mem/all-facts m))
            obj   (get ments nm)
            hits  (mold/recall m nm {:k entity-fact-cap :semantic? false
                                     :subjects #{nm} :filter {:entities #{nm}}})
            srcs  (->> hits (mapcat (comp sort mem/source-ids)) distinct
                       ;; the guard is on `:kind`, not on the object's mere
                       ;; presence. `apply-event` :assoc/:append against an id
                       ;; with no prior :put materializes a husk — measured,
                       ;; `{:note "x"}`, with neither a title nor a kind — and it
                       ;; fails BOTH rules at once: `(name nil)` is an NPE, a 500
                       ;; on a page load, and a husk that survived the NPE would
                       ;; be rendered as a chip with no name on it, which is what
                       ;; the drop below exists to prevent. One condition
                       ;; answers both, and there is no object in this codebase
                       ;; that legitimately lacks `:kind`: `state-payload` calls
                       ;; `(name (:kind o))` on every one of them.
                       (keep (fn [id]
                               (let [o (get objm id)]
                                 (when-let [k (:kind o)]
                                   {:id id :title (:title o) :kind (name k)}))))
                       vec)]
        (cond-> {:name     nm
                 :count    (count (get idx nm #{}))
                 :promoted (contains? (ent/promoted idx (keys ments)) nm)
                 :facts    (mapv #(dissoc % :vec) hits)
                 :sources  srcs}
          obj (assoc :object (:id obj)))))))

(defn dynamic-views [st target]
  (->> (sub/objects st) vals
       (filter #(and (#{:viewspec :applet} (:kind %)) (= target (get-in % [:value :target]))))))

(defn- alternatives [st o]
  (vec (concat (mapv (fn [[vid lbl]] [(kw->str vid) lbl]) (:alternatives (mold/mold (:value o))))
               (mapv (fn [v] [(:id v) (get-in v [:value :label])]) (dynamic-views st (:id o))))))

(defn mold-payload
  "One object, rendered: what `/api/mold` and `/api/object/…` answer, and what
   every `:ref` cell of a notebook payload is built out of.

   **`m` is the memory — or a delay of one — and is FORCED ONLY on the entity
   branch.** All seven call sites hand it `mem/memory` UNDEREFERENCED, which is
   why five of them can: `notebook-payload`, `edit!`, `describe!`, `make-view!`
   and `compute-clj!` have no memory of their own, and molding a doc, a table, a
   flow or an applet through them must not be the thing that opens
   `data/memory.edn`. The alternative the plan named — `@mem/memory` at all seven
   — was rejected on the test seam it opens: it makes `(edit! st \"doc:a\" \"y\")`
   in `edit-rejects-missing-id`, an assertion with nothing to say about memory,
   force the singleton against the user's real data directory, and every future
   test of those five functions after it.

   Be precise about what guards that. `server_test`'s `no-memory` is a delay that
   throws, and it catches the calls a test makes to THIS function directly — nine
   tests, measured, when the force is hoisted out of the branch. It cannot see
   through `edit!`, which passes `mem/memory` itself and would simply succeed
   against the real file. That path is held shut by the shape of the code and by
   nothing else.

   `clojure.core/force` is identity on anything that is not a delay, so a caller
   already holding a memory — every entity test, and anything that would rather
   read like `/api/entity`'s `@mem/memory` neighbours in the router — may simply
   pass it."
  [st m id view]
  (let [o (tbl/hydrate lake/lake (sub/object st id))]
    (cond
      ;; an ENTITY — no viewers, the shell draws the same page it draws before
      ;; materialization, out of the same payload. A `mold/mold` viewer could not
      ;; be this page: a viewer's `:render` only ever receives `(:value o)`,
      ;; which here is `{:name "tsmc"}`, and has no way to reach the memory the
      ;; facts live in. So this is the `:flow` mechanism — "no viewers, the shell
      ;; renders it itself" — and for the same reason.
      ;;
      ;; `entity-payload` is handed THIS `st`, which under `?at=` is a frozen
      ;; store: the source chips are the objects that existed at that point, and
      ;; reading the live store here for them while everything else read the
      ;; frozen one is the one thing that would be wrong. The facts are today's
      ;; either way — the memory has no time dimension and `undo!` never touches
      ;; a fact, so there is no past set to read. That is the memory's design,
      ;; not an omission here.
      ;;
      ;; The branch is on the KIND and never on whether the name looks usable. A
      ;; guard would be a second normalization living outside `ent/normalize`,
      ;; and it would disagree with it — `str/blank?` is false for a lone U+00A0,
      ;; which `ent/normalize` trims to nothing. An object carrying no usable
      ;; name therefore renders `entity-payload`'s own `{:error …}`, which is the
      ;; shape `GET /api/entity?name=` already answers a nameless request with:
      ;; one renderer handles it on both surfaces rather than growing a case that
      ;; exists only here.
      (= :entity (:kind o))
      (let [p (entity-payload st (force m) (get-in o [:value :name]))]
        {:id id :title (:title o) :kind "entity" :view nil :label "entity"
         :rendered p :alternatives []})
      ;; a flow — no viewers, the shell renders the live checklist itself
      (= :flow (:kind o))
      {:id id :title (:title o) :kind "flow" :view nil :label "flow"
       :rendered (:value o) :alternatives []}
      ;; an agent-WRITTEN code view — ship the JS + the data it runs over
      (and view (str/starts-with? view "app:"))
      (let [vo (sub/object st view)]
        {:id id :title (:title o) :kind "applet" :view view
         :label (get-in vo [:value :label]) :code (get-in vo [:value :code])
         :rendered (:value o) :alternatives (alternatives st o)})
      ;; an agent-proposed view-spec — interpret it deterministically
      (and view (str/starts-with? view "view:"))
      (let [vo (sub/object st view)
            {:keys [kind rendered]} (vs/interpret (get-in vo [:value :spec]) (:value o))]
        {:id id :title (:title o) :kind (name kind) :view view
         :label (get-in vo [:value :label]) :rendered rendered :alternatives (alternatives st o)})
      ;; a built-in viewer. `mv`, not `m`: `m` is this function's MEMORY, and
      ;; shadowing it here would be correct only for as long as this branch never
      ;; needs the memory — which is exactly the assumption the entity branch
      ;; above just stopped being able to make for the function as a whole.
      :else
      (let [mv (mold/mold (:value o) (when (seq view) (str->kw view)))]
        {:id id :title (:title o) :kind (name (:kind mv)) :view (when (:view mv) (kw->str (:view mv)))
         :label (:label mv) :rendered (:rendered mv) :alternatives (alternatives st o)}))))

;; ---- LEAP by meaning: the deep query, and only ever the deep query ----
;;
;; Everything below runs for `?deep=1` and for nothing else. The keystroke path
;; must make ZERO embedding requests — a 20–50 ms round trip between a key and a
;; redraw is the thing this whole design is split in half to prevent — so the
;; only caller of `deep-entries` is the `:deep?` branch of `leap-payload`, and
;; there is a test that watches a stubbed embedder receive nothing.

(def ^:private group-cap
  "How many hits one LEAP group shows. Shared by the lexical groups and the
   semantic one, so \"capped like the others\" is the same number rather than
   two numbers that agree today."
  8)

(def ^:private deep-group
  "The group name a semantic hit arrives under. Its own group on purpose: a
   result that matched no word you typed is visibly a different kind of answer
   rather than a mystery."
  "by meaning")

(defn- ell [s n]
  (let [s (str/replace (str s) "\n" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- deep-note
  "A row in the `by meaning` group that is a statement, not a hit.

   The response is a flat list of rows — that is what the keystroke path returns
   and `?deep=1` must return the same shape, otherwise \"deep with no embedder
   returns exactly what deep absent returns\" could not hold. So the two things
   the spec's failure table asks the group to report (that it is degraded, and
   how many chunks await) are rows, marked `:note true` and carrying the pseudo-id
   `__deep__` in the same convention the memory group's `__memory__` already
   uses. A note is never something to open."
  [label extra]
  (merge {:id "__deep__" :label label :group deep-group :note true} extra))

(defn- best-per-object
  "The first chunk seen for each object, from a sequence already in the order
   you want. One row per document: a hit opens the document, so two fragments of
   it are one result, not two."
  [chunks]
  (:out (reduce (fn [{:keys [seen out] :as acc} c]
                  (if (seen (:object c))
                    acc
                    {:seen (conj seen (:object c)) :out (conj out c)}))
                {:seen #{} :out []} chunks)))

(defn- deep-entries
  "The `by meaning` group: the query embedded once, scored by exact cosine
   against every embedded chunk, best first, one row per object.

   **Exact cosine, no index.** 101 chunks over the EDN rollback and ~150–170 over
   the live corpus, where chunking per heading gives more than the design's
   projected 94. Measured 2026-08-08 at 1024 dims: 0.028 ms per cosine, 2.8 ms
   for 101 of them, 5.8 ms for the whole scored-and-sorted scan, 7.8 ms at 202
   chunks. The design's 0.61 ms does NOT reproduce against `embed/cosine` over
   stored vectors — it is ten times that — and the conclusion is unchanged
   anyway: the embedding round trip this scan waits on is 20–50 ms, so an ANN
   index would shave single-digit milliseconds off a request that is already off
   the keystroke path. Revisit past a few thousand chunks, not before.

   **No rerank.** It is a further 50–100 ms round trip on top of the embedding,
   and LEAP is a navigation gesture rather than a research answer.

   **Not scoped** — not by notebook, not by tag, not by lineage. Recall is scoped
   to a notebook's trunk because the agent should reason from what this line of
   enquiry learned; LEAP is the opposite act, the one gesture that must reach
   everything, so that decision deliberately does not carry over.

   `already` is the set of ids the lexical groups returned. A chunk on an object
   already found by substring is dropped, so this group shows what substring
   search could NOT find rather than repeating it."
  [st cs q already touched]
  (let [r (embed/embed-texts [q])]
    (cond
      ;; Nothing configured: `?deep=1` is byte-identical to `deep` absent. A note
      ;; here would itself be a difference, and the spec calls that
      ;; non-negotiable. `embed-texts` answers `:off` without making a request.
      (:off r) []

      ;; Asked and not answered. Today's response plus an honest marker — not an
      ;; error, not an empty page. The message is ours, not the embedder's URL:
      ;; a URL a user set can carry credentials, and this one goes to a browser.
      (:error r)
      [(deep-note "search by meaning is unavailable — the embedder is not answering"
                  {:degraded (:error r)})]

      :else
      (let [model (:model r)          ; the CONFIGURED name, which is what sweep! stamped
            qv    (first (:vectors r))
            objs  (sub/objects st)
            hits  (->> (ch/embedded cs model)
                       ;; `embedded` has already dropped retired chunks; this
                       ;; also drops a chunk whose object went away since the
                       ;; last sweep, which would otherwise open nothing.
                       (keep (fn [c]
                               (when-let [o (and (not (already (:object c)))
                                                 (get objs (:object c)))]
                                 (assoc c :score (embed/cosine qv (:vec c)) :obj o))))
                       ;; Not a relevance threshold — one of those would need
                       ;; calibrating per model, which is why loci.memory refuses
                       ;; to merge on an uncalibrated one. Cosine ≤ 0 is the
                       ;; degenerate case: no shared direction at all, or a
                       ;; length mismatch, for which `cosine` returns 0.0.
                       (filter #(pos? (:score %)))
                       ;; id breaks ties, so the order is total and two identical
                       ;; scores do not sort by whatever the map handed back
                       (sort-by (juxt (comp - :score) :id))
                       best-per-object
                       (take group-cap)
                       (mapv (fn [c]
                               (let [o (:obj c)]
                                 {:id      (:object c)   ; the OBJECT, never the chunk:
                                  ;; a result opens the document, not a fragment of it
                                  :label   (str (or (:title o) (:object c)) " · " (ell (:text c) 70))
                                  :group   deep-group
                                  :kind    (name (:kind o))
                                  :score   (:score c)
                                  :touched (touched (:object c) 0)}))))
            ;; Against the substrate, not the file, so a chunks.edn that was
            ;; never built reports every chunk it implies rather than "0 awaiting"
            ;; over an empty group. ~170 on the live corpus before a first sweep.
            ;;
            ;; It re-chunks and re-hashes the whole substrate to answer. A
            ;; lake-backed table no longer costs a row read for that:
            ;; `all-chunks` builds its synopsis from `tbl/column-samples`, which
            ;; the lake answers with one `SELECT DISTINCT … LIMIT 3` per column
            ;; and memoizes per `[table, snapshot]`. A handle is immutable, so
            ;; each one is queried once in the life of the process.
            ;;
            ;; Re-measured 2026-08-16 over the live corpus, 154 objects / 306
            ;; chunks / 31 row-bearing tables. Interleaved after 30 warmup
            ;; rounds, median of 15 — an un-warmed first call reads 150-250 ms
            ;; and will mislead whoever measures this next:
            ;;
            ;;   14.8 ms  chunking and hashing alone, samples already in hand
            ;;   15.2 ms  every table handle-backed, cache warm ← what a sweep pays
            ;;   25.7 ms  the same tables inline
            ;;   1061 ms  every table handle-backed, cache cold (fresh lake, median of 5)
            ;;
            ;; Warm is within a millisecond of pure chunking — the lake work
            ;; disappears — and beats the inline path, which still walks 3,957
            ;; rows in Clojure to sample them. Cold is 169 queries, one per
            ;; column across the 31 tables, at 6.3 ms each; it is paid once per
            ;; handle for the life of the process, where the hydrating version
            ;; this replaced paid 297 ms on every 15-second sweep.
            ;;
            ;; The SHAPE of the cost changed with it, and that is the part worth
            ;; knowing. Each query is a full column scan, NOT bounded by
            ;; `tbl/mold-cap` the way hydrating was, so a cold miss scales with
            ;; the table rather than being capped: measured on one 10M-row table,
            ;; 20 ms for a 7-distinct-value column against 399-423 ms for
            ;; high-cardinality ones. The trade is an unbounded one-time cost for
            ;; a bounded recurring one, and a description that covers the whole
            ;; column instead of its first 2,000 rows.
            ;;
            ;; So it is still affordable HERE and nowhere else, and now for a
            ;; sharper reason: warm it is ~1× the pure-chunking cost, but the
            ;; first ?deep=1 after a restart — or after any write that mints a
            ;; new handle — is ~70×. It is one of the reasons this function may
            ;; only ever be reached by ?deep=1, and the reason a cheaper answer
            ;; is an open question.
            awaiting (count (ch/pending st cs model))]
        (cond-> hits
          (pos? awaiting)
          (conj (deep-note (str awaiting " chunk(s) awaiting embedding") {:awaiting awaiting})))))))

(defn- lower-root
  "Lower-case under `Locale/ROOT` — the ONE case-folding LEAP is allowed to use.

   `clojure.string/lower-case` is `.toLowerCase()` under the JVM's *default*
   locale, and under a Turkish or Azeri one that maps `I` to a dotless `ı`. Every
   comparison in `leap-payload` folds a query against a haystack, so a
   locale-sensitive fold breaks them in two different ways at once, and both were
   measured under `-Duser.language=tr` against one doc titled `IBM memo` and two
   facts naming `IBM`:

   - the query and an entity name are folded by *different* functions —
     `str/lower-case` for `q`, `ent/normalize`'s `Locale/ROOT` for the name — so
     `IBM` typed into the box became `ıbm` and matched the subject `ibm` never;
   - `hit?` folds the HAYSTACK, so an already-lower-case `ibm` matched the doc
     titled `IBM memo` never, because that title folded to `ıbm memo`.

   Typing `IBM` returned the doc alone, losing the subject and the memory facts;
   typing `ibm` returned the subject and the facts, losing the doc. Neither
   query returned the page. Folding `q` alone with `Locale/ROOT` fixes the first
   and leaves the second, which is why this is a function used at every site
   rather than a one-line change at the top of `leap-payload`.

   `ent/normalize` already made this argument for entity identity: `$LANG` is not
   something a name's identity may depend on. LEAP is the same claim about
   finding it."
  ^String [s]
  (.toLowerCase (str s) Locale/ROOT))

(defn leap-payload
  "ONE incremental search across everything: objects, notebook prose, doc
   bodies, subjects, memory facts, view verbs. Content groups only appear once
   there's a query; each group is capped so it stays incremental-fast.

   The three-argument form is the keystroke path and is lexical, exactly as it
   has always been. `{:deep? true :chunks cs}` adds the `by meaning` group on top
   of — never instead of — everything the lexical pass found; it is what
   `?deep=1` calls, on a pause and on Enter, and it is the ONLY form that embeds
   anything."
  ([st mem q] (leap-payload st mem q nil))
  ([st mem q {:keys [deep? chunks]}]
    ;; `lower-root` at all three sites and `str/lower-case` at none — see its
    ;; docstring. `q` and every haystack it is compared against must be folded by
    ;; the SAME locale-independent function, or the two halves of one comparison
    ;; disagree and a query finds some of its own page.
    (let [q    (lower-root (or q ""))
          hit? (fn [& ss] (str/includes? (lower-root (str/join " " (map str ss))) q))
          snip (fn [text] (let [i (or (str/index-of (lower-root text) q) 0)]
                            (ell (subs text (max 0 (- i 20))) 70)))
          touched (last-touched st)
          ;; recency BEFORE the cap: taking "the first 8 encountered" dropped
          ;; results by whatever order the objects happened to enumerate in
          recent  (fn [e] (or (:touched e) 0))
          cap  (fn [xs] (->> xs (map #(assoc % :touched (touched (:id %) 0)))
                             (sort-by recent >) (take group-cap) vec))
          ;; ONE materialization, shared by the object scan, `intext`, `made`
          ;; and the subject group. Three reads of the store became one, and they
          ;; were not merely wasteful: they were three *snapshots*. A doc
          ;; committed between them whose TITLE matches `q` was offered by no
          ;; group at all — `objs` read the older map and did not have it,
          ;; `intext` read the newer one and excluded it precisely because its
          ;; title hits. One map means one answer about what exists.
          ;;
          ;; A lexical payload is therefore two reads and not one: `nb/notebooks`
          ;; inside `prose` still takes its own, so a NOTEBOOK committed
          ;; mid-request can still be seen by one group and not another. Narrowed
          ;; here and not closed, and said out loud rather than left to be
          ;; rediscovered — closing it means threading `objm` through
          ;; `nb/notebooks`, which is a change to `loci.notebook`'s interface and
          ;; not to this payload.
          ;;
          ;; `materialized-names` and `entity-object` each rebuild the whole
          ;; objects map, so the plain reading of the subject group —
          ;; `entity-object` per promoted name — is N+1 of them **on every
          ;; keystroke**, and N is every name the query matched, not the eight
          ;; that survive `cap`: the row is built before the cap sees it.
          ;; Measured on an EDN store (`objects` re-folds the whole log there),
          ;; the group alone, naive against shared:
          ;;
          ;;   36 names,  42 objects     1.35 ms  →  0.27 ms
          ;;   36 names, 900 objects    16.22 ms  →  0.80 ms
          ;;  360 names, 900 objects   121.98 ms  →  1.66 ms
          ;;
          ;; Datalevin — production — makes `objects` an in-RAM map lookup and
          ;; so removes the fold, but not the shape: `entity-objects` still
          ;; walks every object to build its index, and N of those is still
          ;; 0.36 ms × N at 900 objects. One map, once, is the fix in both.
          objm  (sub/objects st)
          objs  (->> objm vals
                     ;; `:entity` is dropped HERE and deliberately not from
                     ;; `state-payload`, and the asymmetry is a decision rather
                     ;; than an oversight. The two lists answer different
                     ;; questions. `state-payload` is *what exists*: it feeds
                     ;; `STATE.objects`, out of which the shell draws every
                     ;; notebook member — and a materialized entity is a member
                     ;; of the very notebook `materialize-entity!` mints for it,
                     ;; so dropping it there would draw that notebook's one cell
                     ;; as a grey `ent:1 · ?`. LEAP is *what to offer you as a
                     ;; destination*, and there the subject group's row already
                     ;; is that destination: emitting this one too would put one
                     ;; subject under two headings, which is the collision the
                     ;; design split the groups to avoid.
                     ;;
                     ;; The exclusion is unconditional and the subject group is
                     ;; `(seq q)`-guarded, so for the EMPTY query — and only
                     ;; that one — a materialized entity is offered by no group
                     ;; at all. That is the cost, stated rather than discovered:
                     ;; "the subject row is already that destination" is true of
                     ;; every query except the one that has no subject rows.
                     ;; Left as is because nothing becomes unreachable — the
                     ;; notebook `materialize-entity!` mints is listed, and it
                     ;; holds the entity as its one cell — and because gating
                     ;; this on `(seq q)` would put `ent:` rows back into an
                     ;; opened palette under a heading no renderer has, which is
                     ;; the state Task 6 removed. Revisit with the shell, not
                     ;; here.
                     (remove #(#{:entity :viewspec :applet :fn :palette} (:kind %)))
                     (filter #(or (= q "") (hit? (:title %) (:id %) (name (:kind %)))))
                     (map (fn [o] {:id (:id o) :label (:title o) :group (name (:kind o))})))
          verbs (->> @mold/registry
                     (map (fn [v] {:id (kw->str (:id v)) :label (str "view as " (:label v)) :group "viewer"}))
                     (filter #(or (= q "") (hit? (:label %) (:id %)))))
          prose (when (seq q)
                  (for [o (nb/notebooks st), c (nb/cells-of o)
                        :when (and (:text c) (hit? (:text c)))]
                    {:id (:id o) :label (str (:title o) " · " (snip (:text c))) :group "prose"}))
          intext (when (seq q)
                   (for [o (vals objm)
                         :when (and (= :doc (:kind o)) (string? (:value o))
                                    (hit? (:value o)) (not (hit? (:title o) (:id o))))]
                     {:id (:id o) :label (str (:title o) " · …" (snip (:value o))) :group "in text"}))
          made  (when (seq q)
                  (for [o (vals objm)
                        :when (and (#{:applet :viewspec :fn} (:kind o))
                                   (hit? (:title o) (:id o) (get-in o [:value :label])))]
                    {:id (:id o)
                     :label (str (or (get-in o [:value :label]) (:title o))
                                 " · on " (or (get-in o [:value :target]) (get-in o [:value :source]) "?"))
                     :group "views & functions"
                     :target (or (get-in o [:value :target]) (get-in o [:value :source]))}))
          ;; The subject group — promoted entities only. A name with one fact
          ;; behind it is still matched by `ent/detect` for recall, but it is not
          ;; yet a place, and 68 of 104 names on the measured corpus have exactly
          ;; one fact: a group made of every extracted string would be
          ;; two-thirds noise on the one surface that must stay scannable.
          ;;
          ;; **Its own group, not merged into the objects.** Entity names collide
          ;; with object titles by construction — `materialize-entity!` titles the
          ;; notebook it mints with the entity's own name — and a subject and a
          ;; document that read the same are two different places to go. Two rows
          ;; under two headings is the answer; one row that guesses which you
          ;; meant is not, and suppressing either would hide a destination.
          ;;
          ;; `:object` rides on the row when the entity is materialized, so the
          ;; shell has ONE thing to decide on rather than two. The KEY is absent
          ;; when it is not — `{:object nil}` reads as an answer where no key
          ;; reads as "not yet", and JSON gives the shell no third state.
          ;;
          ;; `mem` is this function's memory ARGUMENT and `mem/` is the
          ;; `loci.memory` alias. Both are correct and both appear on the next
          ;; line: a qualified symbol resolves through the ns aliases and is
          ;; never shadowed by a local.
          ;;
          ;; The id is `subject:<name>` and never the object's, even when there
          ;; is one. It is what the row IS, not where it takes you: `cap` looks
          ;; recency up by `:id`, and handing it `ent:1` would let a materialized
          ;; subject outrank the rest of the group by an event that touched the
          ;; object rather than by anything about the subject. Nothing ever
          ;; touches a `subject:` id, so every row here scores 0, `sort-by` is
          ;; stable, and the alphabetical order survives the cap.
          ;;
          ;; **Cost, because this runs on every keystroke.** `ent/index` walks
          ;; every live fact, and it is the second such walk in this function —
          ;; `recall` builds its own `ent/df-map` a few lines down. Measured
          ;; (mean of 200, EDN store, throwaway memory; two runs, so the totals
          ;; are given to the precision they reproduce at and not to five
          ;; digits):
          ;;
          ;;    80 facts / 104 names    group 0.18 ms   leap-payload ~1.1 ms
          ;;   800 facts / 1040 names   group 1.5 ms    leap-payload ~7.5 ms
          ;;
          ;; Fine at both, and not fine forever: at 800 facts the whole payload
          ;; is ~7.5 ms of which `recall` is ~5, so the ceiling this approaches is
          ;; the memory layer's and not this group's. Sharing one index between
          ;; the two would mean handing `loci.memory` a pre-built projection
          ;; instead of a fact sequence — the same trade `entity-payload` and
          ;; `remembered-context` both declined. Revisit when a keystroke costs
          ;; ~16 ms, which is a frame; at today's growth that is a few thousand
          ;; facts away, and the fix is one index passed down, not a cache.
          subs (when (seq q)
                 (let [idx   (ent/index (mem/all-facts mem))
                       ments (entity-objects objm)]
                   (->> (ent/promoted idx (keys ments))
                        ;; Like against like on both axes. `q` was folded by
                        ;; `lower-root` and every name by `ent/normalize`, and
                        ;; those are the same `Locale/ROOT` fold — which they
                        ;; were NOT before this group existed. `q` used
                        ;; `str/lower-case`, so under a Turkish locale a typed
                        ;; `IBM` arrived here as `ıbm` and matched the subject
                        ;; `ibm` never. That was not a gap peculiar to this
                        ;; group: `hit?` folded its haystacks the same way and
                        ;; lost the doc titled `IBM memo` for the opposite
                        ;; query, and `mold/recall` was handed the mangled `q`
                        ;; and lost the facts. See `lower-root`.
                        ;;
                        ;; It does NOT trim — no group here does, and the shell
                        ;; trims before it asks.
                        (filter #(str/includes? % q))
                        sort
                        (map (fn [n]
                               (let [o (get ments n)]
                                 (cond-> {:id (str "subject:" n) :label n
                                          :group "subject" :name n
                                          :count (count (get idx n #{}))}
                                   o (assoc :object (:id o)))))))))
          mems (when (seq q)
                 (map (fn [f] {:id "__memory__" :label (:fact f) :group "memory"})
                      (mold/recall mem q {:k 8})))
          ;; subjects before memory: a subject is a place you go to and a memory
          ;; hit is text you read, and the one that is a destination goes first.
          lex  (vec (concat (cap objs) (cap prose) (cap intext) (cap made) (cap subs) (cap mems)))
          ;; An empty query embeds nothing even with ?deep=1: there is no meaning
          ;; in "" to search by, and asking would be a request per opened palette.
          deep (when (and deep? chunks (seq q))
                 (deep-entries st chunks q (set (map :id lex)) touched))]
      ;; `by meaning` sits after the lexical content and before the view verbs —
      ;; it is content, and the verbs stay where the hands already know they are.
      (vec (concat lex deep verbs)))))

;; ---- notebook payload: cells hydrated (each ref molded by its chosen view),
;; rail links and also-in chips computed fresh every time ----
(defn notebook-payload [st id]
  (let [o (sub/object st id)
        {:keys [connected also-in]} (nb/links st id)
        cells (vec (map-indexed
                    (fn [i c]
                      (cond
                        (:text c) {:i i :type "text" :text (:text c)}
                        (sub/object st (:ref c))
                        (merge {:i i :type "ref"
                                :also-in (get also-in (:ref c))
                                :from (:from (sub/object st (:ref c)))
                                :via  (:via (sub/object st (:ref c)))}
                               (mold-payload st mem/memory (:ref c) (:view c)))
                        :else {:i i :type "missing" :ref (:ref c)}))
                    (nb/cells-of o)))]
    {:id id :title (:title o) :intent (get-in o [:value :intent])
     :spawned-by (get-in o [:value :spawned-by])
     :cells cells :connected connected :events (count (sub/history st))}))

;; ---- tags: subject, the one thing the substrate cannot compute ----
;; Structure is derived (spawned-by, shares, lineage) and never maintained.
;; Subject cannot be, so it is asserted — by the agent or by you, and :by
;; records which, the way the memory pane cites its sources.
(defn- clean-tags
  "Trimmed, lower-cased, de-duplicated, blanks dropped, order preserved.
   When one name arrives twice the first position wins, but YOUR assertion
   outranks the agent's inference: de-duplication must never quietly demote
   something you claimed into something the agent merely guessed."
  [tags]
  (->> tags
       (keep (fn [t] (let [s (str/lower-case (str/trim (str (:tag t))))]
                       (when-not (str/blank? s)
                         {:tag s :by (if (= "agent" (:by t)) "agent" "you")
                          :ts (or (:ts t) (System/currentTimeMillis))}))))
       (reduce (fn [acc t]
                 (if-let [i (first (keep-indexed #(when (= (:tag %2) (:tag t)) %1) acc))]
                   (cond-> acc (= "you" (:by t)) (assoc-in [i :by] "you"))
                   (conj acc t)))
               [])))

(defn- keep-tag-times
  "Carry a tag's original :ts across an edit that did not touch it. The stamp
   says when that tag was asserted; saving the strip again is not a fresh
   assertion of everything already on it."
  [was now]
  (let [prior (into {} (map (juxt (juxt :tag :by) :ts) was))]
    (mapv (fn [t] (if-let [ts (prior [(:tag t) (:by t)])] (assoc t :ts ts) t)) now)))

;; ---- tag colours ----
;; Eight inks in the paper's lightness band, so a strip of them reads as a
;; family. Clay (--attn) is deliberately absent: clay means EXCLUDED in the
;; strip, and a clay tag that was also excluded would be a chip you can't read.
(def tag-inks
  ["#2f6f5b"   ; green — loci's own accent, so the first tag looks like it belongs
   "#2b6b74"   ; teal
   "#3f5a8a"   ; indigo
   "#6b4a8a"   ; violet
   "#8c3f5a"   ; garnet
   "#7a5a2f"   ; bronze
   "#5f6b33"   ; olive
   "#4a5560"]) ; slate

;; `palette-id` and `tag-colors` are defined up beside `state-payload`, which
;; needs them to hide this object and to ship the registry with the state.

(defn- next-ink
  "The ink used by the fewest tags already in `reg`, ties broken by a hash of
   the name. Hashing alone would collide: with eight inks and eight real
   subjects a bare hash lands them on five, and two subjects sharing an ink is
   the one failure colour exists to prevent. So the hash only picks WHERE to
   start looking, and the first least-used ink from there wins — FIRST, not
   `min-key`'s last, so that swapping this for a sort does not silently
   re-colour every tag in the registry.

   `.hashCode` and not Clojure's `hash`: an ink has to survive a restart, and
   murmur3's seed is an implementation detail, where String's is specified."
  [reg tag]
  (let [used   (frequencies (vals reg))
        n      (count tag-inks)
        start  (mod (.hashCode ^String tag) n)   ; `mod` floors, so never negative
        ranked (map #(nth tag-inks (mod (+ start %) n)) (range n))]
    (reduce (fn [best ink] (if (< (get used ink 0) (get used best 0)) ink best))
            (first ranked) (rest ranked))))

(defn- palette-shell
  "The events that make the registry an object, or nil when it already is one.
   Both writers go through this so neither can drift into creating the object a
   second way — key by key and never a whole-object :put, for the reason spelled
   out in `assign-inks!`."
  [pal]
  (when-not pal
    [{:op :assoc :id palette-id :path [:id] :value palette-id}
     {:op :assoc :id palette-id :path [:kind] :value :palette}
     {:op :assoc :id palette-id :path [:title] :value "tag colours"}]))

(defn- assign-inks!
  "Give every unseen name an ink — one event, or none when nothing is new.
   Callers MUST commit this BEFORE the tag event: undo! undoes the last event,
   so undoing a tagging has to remove the tags and leave the colour standing."
  [st names]
  ;; one materialization, not one per read (see the `apply-event` note in
  ;; substrate.clj): this ran `tag-colors` and `object` back to back, 0.94 ms
  ;; each on a 3001-event log, to answer two questions about the same object
  (let [pal   (sub/object st palette-id)
        reg   (or (:value pal) {})
        fresh (remove #(contains? reg %) (distinct names))]
    (when (seq fresh)
      ;; reduce over the accumulating map, not `reg` — two new names in one call
      ;; would otherwise both be weighed against a registry holding neither, and
      ;; two whose hashes start at the same ink would take that same ink
      (let [reg' (reduce (fn [m t] (assoc m t (next-ink m t))) reg fresh)
            ;; One :assoc PER NAME, not one write of the whole map. This registry
            ;; is the first global thing in this file — every other write here is
            ;; scoped to one notebook — and http-kit answers from a worker pool,
            ;; so two notebooks being tagged at once is ordinary. Writing the map
            ;; whole made that a read-modify-write with no lock: whichever landed
            ;; second dropped the other's names, 48 of 64 under eight threads. The
            ;; tags survived and their colours did not, so what you saw was a tag
            ;; with no ink at all. Per-name writes merge where a whole map clobbers.
            ;;
            ;; The object is CREATED the same way, key by key (`palette-shell`),
            ;; for the same reason: a :put carries the whole object, so two threads
            ;; arriving at an empty registry together would clobber each other
            ;; exactly as before. Those three keys are idempotent, so a racing
            ;; creator writing them twice is fine.
            ;;
            ;; What this does NOT fix: two threads assigning two new names, each
            ;; weighed against a registry holding neither, can still choose the same
            ;; ink. That is a duplicate colour rather than a missing one — the strip
            ;; still reads, and every name still has an ink.
            shell (palette-shell pal)
            inks  (mapv (fn [t] {:op :assoc :id palette-id :path [:value t] :value (reg' t)})
                        fresh)]
        ;; still ONE event: a :tx undoes as a single step, so the ordering above holds
        (sub/commit! st {:op :tx :events (into (vec shell) inks)})
        reg'))))

(defn set-tags!
  "Replace a notebook's tags — one reversible event, or none when nothing
   changed. Provenance is content: approving the agent's tag as your own
   changes what the tag claims, so it earns an event of its own."
  [st space tags]
  (let [o (sub/object st space)]
    (if-not (= :space (:kind o))
      {:error (str "not a notebook: " space)}
      (let [was (get-in o [:value :tags])
            now (keep-tag-times was (clean-tags tags))]
        (when (not= (mapv (juxt :tag :by) was) (mapv (juxt :tag :by) now))
          ;; colour first, tags second: undo! undoes the LAST event, so undoing
          ;; a tagging must remove the tags and leave the ink standing
          (assign-inks! st (mapv :tag now))
          (sub/commit! st {:op :assoc :id space :path [:value :tags] :value now}))
        {:state (state-payload st) :tags now}))))

(defn set-tag-color!
  "Choose a tag's colour — one reversible event, or none when it is already
   that. The palette is a closed set: a free-form hex would let the shell write
   a colour that fails the very contrast the palette was chosen for."
  [st tag color]
  ;; the same trim + lower-case `clean-tags` applies, or the colour lands on a
  ;; name no tag actually carries and the chip keeps its old ink
  (let [t (str/lower-case (str/trim (str tag)))]
    (cond
      (str/blank? t)               {:error "no tag"}
      (not ((set tag-inks) color)) {:error (str "not a palette colour: " color)}
      :else
      (let [pal (sub/object st palette-id)]
        (when (not= color (get (or (:value pal) {}) t))
          ;; the picker can be opened on a store where nothing was ever tagged,
          ;; so this may have to create the registry too — key by key, never a
          ;; :put and never a whole-map write; see `assign-inks!` for what that
          ;; cost. A :tx keeps creation and choice one undoable step.
          (let [ink {:op :assoc :id palette-id :path [:value t] :value color}]
            (sub/commit! st (if-let [shell (palette-shell pal)]
                              {:op :tx :events (conj shell ink)}
                              ink))))
        {:state (state-payload st) :tag-colors (tag-colors st)}))))

;; One monitor per notebook id, interned here. Per notebook and not one global
;; lock so that editing two notebooks never serialises — the shell can have
;; several open, and a person reordering cells in one must not wait on a
;; research job's cells landing in another.
;;
;; The map only grows: an id's monitor is never released, because releasing it
;; would need a second lock to make "look up or create" atomic against "throw
;; away", and there is nothing to gain — an empty Object per notebook ever
;; edited, a few tens of bytes each, in a store that holds the notebooks
;; themselves in RAM.
(defonce ^:private notebook-locks (atom {}))

(defn- notebook-lock [space]
  (or (@notebook-locks space)
      ;; the swap! may retry and discard a spare Object; the monitor everyone
      ;; ends up with is the one in the map that swap! actually published
      (get (swap! notebook-locks #(cond-> % (not (% space)) (assoc space (Object.))))
           space)))

(defn notebook-op! [st {:keys [space] :as body}]
  (if (not= :space (:kind (sub/object st space)))
    {:error (str "not a notebook: " space)}
    (do
      ;; Read-modify-write of the whole cell vector, serialised by a lock.
      ;;
      ;; Unlike appending, reorder and delete have no commutative apply-time
      ;; form: two concurrent reorders genuinely conflict, so there is no
      ;; `:append`-shaped event whose effect the substrate could compute at
      ;; apply time. A lock is the right answer instead of a new op *because of
      ;; where these come from* — a person clicking one cell control at a time,
      ;; not agent jobs running in parallel. Contention is therefore negligible
      ;; and throughput is not the concern; correctness is. Measured before this
      ;; lock: 24 concurrent cell ops on one notebook left a notebook reflecting
      ;; one of them, with 11 of 12 appended cells gone.
      ;;
      ;; What the lock buys is that the second op reads the first op's result:
      ;; last-writer-wins on *intent* (whose reorder survives is still whoever
      ;; serialised last), but no cell is lost, which was the actual defect.
      ;;
      ;; It serialises within one JVM only — `locking` is a monitor, not a
      ;; distributed lock. loci is a single process, so that is the whole scope
      ;; of the problem; a second server against the same store would still race.
      ;;
      ;; Nothing but the read and the commit is inside: the payloads are built
      ;; after it, and no agent call or other I/O may move in.
      (locking (notebook-lock space)
        (let [cells  (nb/cells-of (sub/object st space))
              cells' (nb/cell-op cells body)]
          ;; unknown op / stale idx fall through unchanged — don't commit a phantom event
          (when (not= cells cells')
            (sub/commit! st (nb/set-cells-event space cells')))))
      {:state (state-payload st) :notebook (notebook-payload st space)})))

;; ---- writes (every one a substrate event) ----
;;
;; Forward-declared because the minting below starts ~500 lines before these are
;; defined, down beside the entity work `next-id-in` was split out for. Both are
;; 1 + the highest existing suffix. Every unguarded count-based minter in this
;; file now goes through one of them; `import-csv!` is the one holdout, and it
;; still carries the `(rand-int 9999)` suffix the forensics addendum argues about.
(declare next-id next-id-in)

(defn edit! [st id value]
  (if-not (and id (sub/object st id))
    {:error (str "no such object: " id)}
    (do (sub/commit! st {:op :assoc :id id :path [:value] :value value})
        {:state (state-payload st) :object (mold-payload st mem/memory id nil)})))

;; (1) molding-by-description — the agent emits a view-spec stored as a
;; reversible object; it then appears in the object's "view this as…" menu.
(defn describe! [st id prompt]
  (try
    (let [o (tbl/hydrate lake/lake (sub/object st id)) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "describe works on tables"}
        (let [spec (agent/describe-view (mapv name (keys (first rows))) (take 2 rows) prompt)
              ;; max+1 over this source's own run, not `(inc (count :viewspec))`
              ;; over the store — the count form is what overwrote three notebooks
              ;; in July, and `:put` would replace an occupied `view:` in the same
              ;; silence. `next-id` rather than `next-id-in` because the id must be
              ;; minted AFTER `describe-view`'s network call, not before it.
              vid (next-id st (str "view:" (subs id (inc (str/index-of id ":"))) "-"))
              vobj {:id vid :kind :viewspec :title (str "view: " (:label spec))
                    :value {:target id :label (or (:label spec) prompt) :spec spec}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st mem/memory id vid) :events (count (sub/history st))))))
    (catch Exception e {:error (.getMessage e)})))

;; (1b) molding-by-CODE — the agent writes a self-contained JS applet that runs
;; over the object's rows. Stored as a reversible :applet object; appears in the
;; "view this as…" menu and executes in the shell. ponytail: code runs in the
;; page (new Function) — fine for a local single-user prototype; sandbox/iframe
;; if this ever serves untrusted users.
(defn make-view! [st id prompt]
  (try
    (let [o (tbl/hydrate lake/lake (sub/object st id)) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "code views work on tables"}
        (let [code (agent/make-applet (mapv name (keys (first rows))) (take 3 rows) prompt)
              p (str/trim prompt)
              ;; max+1 per source, for `describe!`'s reason directly above
              vid (next-id st (str "app:" (subs id (inc (str/index-of id ":"))) "-"))
              vobj {:id vid :kind :applet :title (str "app: " p)
                    :value {:target id :code code
                            :label (str "▶ " (if (> (count p) 26) (str (subs p 0 26) "…") p))}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st mem/memory id vid) :events (count (sub/history st))))))
    (catch Exception e {:error (.getMessage e)})))

;; (1d) function-as-substrate-object, BACKEND flavour — the agent writes a pure
;; Clojure (fn [rows] …), run SCI-SANDBOXED on the JVM (only clojure.core + Math;
;; no I/O, no interop). Computation stays in the substrate's own language; we have
;; the result server-side, so the :fn AND the derived table commit in ONE :tx
;; (atomic, single undo). The browser only renders.
(def ^:private sci-opts {:classes {'Math java.lang.Math}})
(defn- json-safe [rows] (walk/postwalk #(if (ratio? %) (double %) %) rows))

;; only a real :space may receive a cell append — an existence check alone lets
;; a table id through, and the :assoc on its vector :value poisons the log
(defn- space? [st id] (= :space (:kind (sub/object st id))))

(defn- run-clj-rows
  "Eval agent-written Clojure over rows in the SCI sandbox — accepts
   `(fn [rows] …)` or a bare expression over `rows`. Returns {:rows out}
   or {:error …}; throws on eval errors, callers keep their own try."
  [code rows]
  (let [res (sci/eval-string code (assoc sci-opts :bindings {'rows (vec rows)}))
        out (json-safe (vec (if (fn? res) (res (vec rows)) res)))]
    (if (and (seq out) (every? map? out))
      {:rows out}
      {:error "the function did not return rows"})))

(defn compute-clj! [st id prompt space]
  (try
    (let [o (tbl/hydrate lake/lake (sub/object st id)) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "compute works on tables"}
        (let [cols    (mapv name (keys (first rows)))
              sample  (take 3 rows)
              attempt (fn [prev-code err]
                        (let [code (agent/make-clj-transform cols sample prompt prev-code err)]
                          (assoc (try (run-clj-rows code rows)
                                      (catch Exception e {:error (.getMessage e)}))
                                 :code code)))
              ;; one bad generation shouldn't sink the request: retry once,
              ;; feeding the eval error back so the model can fix its code
              r1   (attempt nil nil)
              r    (if (:error r1) (attempt (:code r1) (:error r1)) r1)
              code (:code r)
              out  (:rows r)]
          (if (:error r)
            {:error (str "compute failed: " (:error r))}
            ;; max+1, not count+1. Counting is how three notebooks died on
            ;; 2026-07-23: `space:dd-` was minted `(inc (count …))` over a store
            ;; that had booted 49 of 80 events, so it re-minted 16/17/18 onto
            ;; live notebooks and `:put` — `assoc-in`, no occupancy check —
            ;; replaced them in silence. These two had neither the
            ;; `(rand-int 9999)` suffix that saved `tbl:extract-14-677` nor an
            ;; existence check, and `fn-apply!` below already minted
            ;; `tbl:derived-` through `next-id`: one prefix, two rules. A count
            ;; also falls with nothing wrong at all — remove a derived table and
            ;; the next compute re-mints the id of one still sitting there.
            ;; `next-id-in` over ONE `sub/objects` rather than `next-id` twice:
            ;; each call re-folds the whole log on the EDN stores (2.334 ms vs
            ;; 1.135 ms at 3003 events), and both ids come from one snapshot.
            (let [objm (sub/objects st)
                  p    (str/trim prompt)
                  fid  (next-id-in objm (str "fn:" (subs id (inc (str/index-of id ":"))) "-"))
                  nid  (next-id-in objm "tbl:derived-")
                  ttl (if (> (count p) 48) (str (subs p 0 48) "…") p)
                  fobj {:id fid :kind :fn :title (str "fn: " p)
                        :value {:source id :prompt p :lang "clojure" :code code}}
                  tobj (tbl/table-object @lake/lake nid ttl out {:from id :via fid})
                  evs (cond-> [{:op :put :id fid :value fobj}
                               {:op :put :id nid :value tobj}]
                        (and space (space? st space))
                        (conj (nb/append-cell-event st space {:ref nid})))]
              (sub/commit! st {:op :tx :events evs})
              {:state (state-payload st) :openId nid :object (mold-payload st mem/memory nid nil)})))))
    (catch Exception e {:error (str "compute failed: " (.getMessage e))})))

;; the functions living in the substrate, with their code — an inspector for the
;; curious. NOT the everyday flow; the everyday flow is just using the verbs.
(defn functions-list [st]
  (->> (sub/objects st) vals
       (filter #(#{:fn :applet :viewspec} (:kind %)))
       (sort-by :id)
       (mapv (fn [o] {:id (:id o) :kind (name (:kind o)) :title (:title o)
                      :lang (or (get-in o [:value :lang])
                                (case (:kind o) :applet "js" :viewspec "view-spec" nil))
                      :target (or (get-in o [:value :source]) (get-in o [:value :target]))
                      :prompt (get-in o [:value :prompt])
                      :code (or (get-in o [:value :code])
                                (when (= :viewspec (:kind o))
                                  (pr-str (get-in o [:value :spec]))))}))))

;; ---- the function palette: built-in single-table verbs (loci.fnlib) plus
;; agent-written :fn objects, previewed live, applied as ONE reversible :tx ----
(defn- table-rows [st id]
  (let [rows (:value (tbl/hydrate lake/lake (sub/object st id)))]
    (when (and (sequential? rows) (seq rows) (every? map? rows)) rows)))

(defn fns-payload [st id]
  (if-let [rows (table-rows st id)]
    {:fns (into (fnlib/catalog rows)
                (->> (sub/objects st) vals
                     (filter #(and (= :fn (:kind %)) (= "clojure" (get-in % [:value :lang]))))
                     (sort-by :id)
                     (mapv (fn [o] {:id (:id o) :label (:title o)
                                    :doc (get-in o [:value :prompt]) :ok true :params []}))))}
    {:error "functions work on tables"}))

(defn- run-any-fn [st fid rows params]
  (cond
    (nil? fid)
    {:error "which function?"}

    (str/starts-with? fid "fn:")
    (let [o (sub/object st fid)]
      (cond
        (not= :fn (:kind o)) {:error (str "unknown function: " fid)}
        (not= "clojure" (get-in o [:value :lang])) {:error "not a runnable function"}
        :else (try
                (run-clj-rows (get-in o [:value :code]) rows)
                (catch Exception e {:error (.getMessage e)}))))

    :else
    (let [r (fnlib/run-fn fid rows params)]
      (if (:error r) r {:rows (json-safe (:rows r))}))))

(defn fn-preview [st id fid params]
  (try
    (if-let [rows (table-rows st id)]
      (let [r (run-any-fn st fid rows params)]
        (if (:error r)
          r
          {:before (json-safe (vec (take 8 rows)))
           :after (vec (take 8 (:rows r))) :count (count (:rows r))}))
      {:error "functions work on tables"})
    (catch Exception e {:error (.getMessage e)})))

(defn fn-apply! [st id fid params space]
  (try
    (if-let [rows (table-rows st id)]
      (if (and space (not (space? st space)))
        {:error (str "not a notebook: " space)}
        (let [r (run-any-fn st fid rows params)]
          (if (:error r)
            r
            (let [src (sub/object st id)
                  lbl (or (:label (first (filter #(= fid (:id %)) fnlib/builtins)))
                          (:title (sub/object st fid)) fid)
                  nid (next-id st "tbl:derived-")
                  tobj (tbl/table-object @lake/lake nid (str (:title src) " · " lbl)
                                         (:rows r) {:from id :via fid :params (or params {})})
                  evs (cond-> [{:op :put :id nid :value tobj}]
                        (and space (space? st space))
                        (conj (nb/append-cell-event st space {:ref nid})))]
              (sub/commit! st {:op :tx :events evs})
              {:state (state-payload st) :openId nid}))))
      {:error "functions work on tables"})
    (catch Exception e {:error (.getMessage e)})))

;; ---- live lineage: derived tables know :from/:via/:params, so the whole
;; downstream chain can be recomputed against fresh source rows — one :tx,
;; one undo. Legacy tables without :params are skipped, never guessed. ----
(defn- recompute [st working obj]
  ;; working = {id -> newly-computed rows} for already-refreshed ancestors
  (let [src-id (:from obj)
        rows   (or (get working src-id)
                   (:value (tbl/hydrate lake/lake (sub/object st src-id))))
        via    (:via obj)]
    (cond
      (nil? rows) {:why (str "source " src-id " is gone")}
      (str/starts-with? (str via) "fn:")
      (if-let [code (get-in (sub/object st via) [:value :code])]
        (try (let [r (run-clj-rows code rows)]        ; already {:rows}/{:error}
               (if (:error r) {:why (:error r)} r))
             (catch Exception e {:why (str "recompute failed: " (.getMessage e))}))
        {:why (str "function " via " is gone")})
      (str/starts-with? (str via) "lib:")
      (if-let [ps (:params obj)]
        (let [r (fnlib/run-fn via rows ps)]
          (if (:error r) {:why (:error r)} {:rows (json-safe (:rows r))}))
        {:why "made before lineage recorded its parameters — re-apply ƒ to refresh"})
      :else {:why (str "unknown lineage via " via)})))

(defn rerun!
  "Refresh id (if derived) and everything derived from it, transitively.
   Children recompute against their parent's NEW rows. All updates land as
   one :tx. Returns {:state :refreshed [ids] :skipped [{:id :why}]}.

   The `try` is the one every sibling has — `fn-apply!`, `compute-clj!`,
   `import-csv!`, `describe!`, `make-view!`, `do!` — and this was the only writer
   without it. `handler` has no top-level catch, so `/api/rerun` answered a
   throw with a 500 and a stack trace. Reachable since `normalize-rows` began
   refusing a column collision: `lib:pivot` names its columns from data VALUES
   (`fnlib.clj:63`), so a source that grows a `\"a b\"` beside an `\"a_b\"`
   makes a recompute collide where the original apply did not. And because the
   commit is one `:tx` after the whole reduce, a throw meant NOTHING refreshed —
   not even the levels that had already succeeded."
  [st id]
  (try
    (let [o (sub/object st id)]
      (if-not (and o (= :table (:kind o)))
        {:error (str "not a table: " id)}
        (let [objs     (vals (sub/objects st))
              children (fn [pid] (->> objs (filter #(= pid (:from %))) (sort-by :id) (map :id)))
              ;; breadth-first over the :from DAG, parents before children
              order    (loop [q (vec (if (:from o) [id] (children id))) seen #{} out []]
                         (if (empty? q)
                           out
                           (let [[x & xs] q]
                             (if (seen x)
                               (recur (vec xs) seen out)
                               (recur (vec (concat xs (children x))) (conj seen x) (conj out x))))))
              {:keys [working refreshed skipped]}
              (reduce (fn [{:keys [working refreshed skipped]} cid]
                        (let [r (recompute st working (sub/object st cid))]
                          (if (:rows r)
                            {:working (assoc working cid (:rows r))
                             :refreshed (conj refreshed cid) :skipped skipped}
                            {:working working :refreshed refreshed
                             :skipped (conj skipped {:id cid :why (:why r)})})))
                      {:working {} :refreshed [] :skipped []}
                      order)]
          ;; `working` holds ROWS, because that is what `recompute` threads from
          ;; an ancestor to its descendants. What lands in the LOG is the handle
          ;; those rows were written to — the seventh writer, and the one that
          ;; builds no `:kind :table` literal for a grep to find.
          (when (seq refreshed)
            (sub/commit! st {:op :tx :events (mapv (fn [cid]
                                                     {:op :assoc :id cid :path [:value]
                                                      :value (tbl/table-value @lake/lake cid
                                                                              (get working cid))})
                                                   refreshed)}))
          {:state (state-payload st) :refreshed refreshed :skipped skipped})))
    (catch Exception e {:error (.getMessage e)})))

;; ONE verb over a table: the user says what they want, the agent picks the
;; simplest mechanism (declarative view-spec / browser applet / server-side
;; Clojure transform) and routes to it. No technique buttons — loci decides.
(defn do! [st id prompt space]
  (try
    (let [o (tbl/hydrate lake/lake (sub/object st id)) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "this works on tables"}
        (let [technique (agent/choose-technique (mapv name (keys (first rows))) (take 3 rows) prompt)]
          (case technique
            "transform" (let [r (compute-clj! st id prompt space)]
                          (if (:error r) r (assoc r :technique "transform")))
            "applet"    (let [r (make-view! st id prompt)]
                          (if (:error r) r {:technique "applet" :state (state-payload st) :object r :openId id}))
            (let [r (describe! st id prompt)]   ; "view" (default)
              (if (:error r) r {:technique "view" :state (state-payload st) :object r :openId id}))))))
    (catch Exception e {:error (.getMessage e)})))

;; delegate! is defined below (after obj-digest) — it is tool-powered.

;; the agent sets up a new space (intention) and gathers relevant members
(defn new-space! [st prompt]
  (try
    (let [catalog (->> (sub/objects st) vals (remove #(#{:space :viewspec :applet :fn} (:kind %)))
                       (mapv (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))})))
          ids (set (map :id catalog))
          spec (try (agent/plan-space catalog prompt) (catch Exception _ nil))
          title (or (not-empty (:title spec)) prompt)
          intent (or (not-empty (:intent spec)) prompt)
          members (vec (filter ids (or (:members spec) [])))
          ;; max+1 over `space:new-`, and this one is not merely the SHAPE that
          ;; destroyed `space:dd-16/17/18` on 2026-07-23 — it is the same form,
          ;; `(inc (count …))` over `:space`, minting a notebook id. `fresh-dd-id`
          ;; does not reach it: that matches `^space:dd-(\d+)$` and this prefix is
          ;; `space:new-`. `:put` is `assoc-in` with no occupancy check, so a count
          ;; that has fallen — a delete, or an event the store cannot see — replaces
          ;; a LIVE notebook's title, intent, cells and lineage and reports success.
          ;; A view or an applet can be re-made from its target; a notebook cannot.
          ;;
          ;; Read at mint time, not from the `objects` fold above: `plan-space` is a
          ;; network call, and taking the id before it would age the snapshot by
          ;; however long the model takes. That is why this is `next-id` and not
          ;; `next-id-in` over a map already in hand.
          sid (next-id st "space:new-")]
      (sub/commit! st {:op :put :id sid
                       :value {:id sid :kind :space :title title
                               :value {:intent intent :members members}}})
      {:state (state-payload st) :spaceId sid})
    (catch Exception e {:error (.getMessage e)})))

;; ---- connect: the old prototype's non-destructive merge — a NEW space
;; unioning two notebooks (prose kept, shared refs deduped), originals
;; intact, one reversible event. Cross-space work = connect, not move. ----
(defn connect! [st a b]
  (let [oa (sub/object st a) ob (sub/object st b)]
    (cond
      (= a b) {:error "connect two different notebooks"}
      (not (and (= :space (:kind oa)) (= :space (:kind ob))))
      {:error "connect works on two notebooks"}
      :else
      (let [taken (fn [cells] (set (keep :ref cells)))
            ca    (nb/cells-of oa)
            cb    (vec (remove #(and (:ref %) ((taken ca) (:ref %))) (nb/cells-of ob)))
            sid   (next-id st "space:mix-")
            sp    {:id sid :kind :space :title (str (:title oa) " × " (:title ob))
                   :value {:intent (str "everything from “" (:title oa) "” and “" (:title ob) "”, together")
                           :cells (vec (concat ca cb))
                           :merged-from [a b]}}]
        (sub/commit! st {:op :put :id sid :value sp})
        {:state (state-payload st) :openId sid}))))

;; ask — answer a question grounded in a compact digest of the workspace
(defn- table-digest
  "Per-column aggregates so the agent can answer questions about a big table
   without seeing every row: numeric sum/avg/min/max, and counts for low-
   cardinality categorical columns."
  [rows]
  (let [cols    (keys (first rows))
        numcols (filter #(number? (get (first rows) %)) cols)
        catcols (filter #(and (string? (get (first rows) %))
                              (let [d (count (distinct (map % rows)))] (and (> d 1) (<= d 12))))
                        cols)
        nums (for [c numcols]
               (let [xs (map #(get % c) rows)]
                 (str "  " (name c) ": sum=" (reduce + xs)
                      " avg=" (Math/round (double (/ (reduce + xs) (count xs))))
                      " min=" (apply min xs) " max=" (apply max xs))))
        cats (for [c catcols]
               (str "  " (name c) " counts: " (json/write-str (frequencies (map #(get % c) rows)))))
        cross (when (and (seq numcols) (seq catcols))
                (let [m (first numcols)]
                  (for [c catcols]
                    (str "  " (name m) " by " (name c) ": "
                         (json/write-str (into {} (map (fn [[k rs]] [k (reduce + (map #(get % m) rs))])
                                                        (group-by c rows))))))))]
    (str/join "\n" (concat nums cats cross))))

(defn- obj-digest [o]
  (let [v (:value o) id (:id o) t (:title o)]
    (cond
      (string? v) (str "## " id " — " t " (doc)\n" v)
      (and (sequential? v) (seq v) (every? :block v))
      (str "## " id " — " t " (report)\n"
           (str/join "\n" (keep #(case (:block %) :heading (str "### " (:text %)) :text (:text %) nil) v)))
      (and (sequential? v) (seq v) (every? map? v))
      (str "## " id " — " t " (table, " (count v) " rows; cols: "
           (str/join ", " (map name (keys (first v)))) ")\n"
           (table-digest v) "\nsample rows: " (json/write-str (vec (take 3 v))))
      :else "")))

(defn- table-catalog
  "The `TABLES (query by id)` block the agent is given — one line per table,
   naming its id, title, columns and row count.

   **Reads the handle; fetches no rows.** Three call sites built this by hand
   out of `(keys (first (:value o)))` and `(or (:table/total o) (count …))`,
   which needed the object hydrated — and hydrating a whole workspace to write
   three dozen characters per table cost 282 ms on every chat turn once the
   rows moved to the lake. A catalog is metadata, and `tbl/table-shape` answers
   it from the handle in microseconds. `obj-digest` is the reader that really
   does need rows, and it still gets them.

   Takes UNHYDRATED objects. Handing it hydrated ones still works — a legacy
   inline vector is `table-shape`'s other branch — but it would pay the cost
   this function exists to avoid."
  [tbls]
  (str/join "\n"
            (map (fn [o]
                   (let [{:keys [cols rows]} (tbl/table-shape lake/lake o)]
                     (str "- " (:id o) " — " (:title o)
                          " (cols: " (str/join ", " (map name cols))
                          "; " (or rows 0) " rows)")))
                 tbls)))

(defn- descendant-sources
  "DOWN. The notebook, the ids of its cells, and the same for everything it
   spawned and everything merged into it — transitively.

   Of the reasons `nb/links` reports, only `spawned` and `merged-from` descend:

     spawned      o's :spawned-by names this notebook — a child. In scope.
     merged-from  o's id is in this notebook's :merged-from — folded in here.
                  In scope: its facts were recorded before the merge existed
                  and there is nowhere else for them to live.
     spawned-by   the parent. Not this walk's edge — `ancestor-sources` takes
                  it, and takes only the parent's own ids, never a re-descent.
     merged       a notebook THIS one was folded into. Not in scope at all: a
                  merge is downstream of its inputs, and its other inputs are
                  siblings by another name.
     shares       two notebooks holding the same object. Not an edge to walk —
                  though note the consequence of taking cells: if the shared
                  object is a cell HERE, a fact distilled from it is in scope,
                  because it is a fact about a document sitting in this
                  notebook. What does not come with it is the other notebook —
                  not its other cells, not its descendants, and not a fact
                  recorded against it with no object (what `ask!` writes).
     derived      one's cell was computed from the other's. Same reasoning.

   Both kept edges are followed transitively: spawning because a deep dive of a
   deep dive is still descent, and merged-from the same way because a notebook
   that absorbed another absorbed whatever that one had absorbed. `seen` bounds
   the walk, so a cycle in the merge graph terminates rather than hanging."
  [st space]
  (loop [queue [space], seen #{}, out #{}]
    (if-let [id (first queue)]
      (if (seen id)
        (recur (subvec queue 1) seen out)
        (let [cells (keep :ref (nb/cells-of (sub/object st id)))
              kids  (keep (fn [{:keys [id reasons]}]
                            (when (some (comp #{"spawned" "merged-from"} :type) reasons)
                              id))
                          (:connected (nb/links st id)))]
          (recur (into (subvec queue 1) kids) (conj seen id) (into (conj out id) cells))))
      out)))

(defn- spawned-by
  "The notebook `id` was spawned from, or nil. Read straight off the object
   rather than through `nb/links`, because `nb/links` answers “who is connected
   to me” by scanning every object, and going up needs exactly one field."
  [st id]
  (get-in (sub/object st id) [:value :spawned-by :space]))

(defn- ancestor-sources
  "UP, and never back down. Each notebook on the path from `space` to the root
   contributes its OWN id and its OWN cell ids — nothing else. It does not
   contribute its other children, and this walk never calls `nb/links`, which
   is what makes that structural rather than a promise: there is no edge here
   that could reach a sibling.

   That restraint is the whole point. One hop up `spawned-by` and then one hop
   down lands on every sibling, which is the leak the scope exists to stop.

   ONLY `:spawned-by` is followed up; `:merged-from` deliberately is not. They
   are different relations. A spawn means “I grew out of this”, and the parent
   existed first, so its facts are the ground this branch stands on. A merge
   means “these were combined” — the merged notebook was created *later*, out
   of its inputs, so from an input's point of view it is not a trunk it grew
   out of but a container it was put into afterwards, and that container's
   other inputs are siblings under another name. Little is lost by the choice:
   `connect!` copies both inputs' cells into the merged notebook, so a notebook
   spawned from a merge picks those cells up as the merge's own. What it does
   not pick up is the input notebooks' ids — so a fact recorded against an
   input with no object (what `ask!` writes) stays out.

   `seen` bounds this walk exactly as it bounds the downward one. Nothing in
   the substrate forbids a cycle in `:spawned-by`, and unbounded this loops
   forever rather than failing.

   An ancestor that no longer exists as an object still contributes its id: the
   lineage was recorded, and facts recorded against it are still the trunk's.
   It contributes no cells, and the walk stops there.

   Honest consequence, same one the `shares` note above describes: an
   ancestor's cell ids are in the set, and the filter matches a fact by `:obj`
   OR `:space`. So a fact distilled from one of the trunk's documents is in
   scope even if it was distilled while standing in a sibling that holds the
   same document. That is the price of taking cells at all; it is bounded by
   the documents the trunk actually holds."
  [st space]
  (loop [id (spawned-by st space), seen #{space}, out #{}]
    (if (and id (not (seen id)))
      (recur (spawned-by st id)
             (conj seen id)
             (into (conj out id) (keep :ref (nb/cells-of (sub/object st id)))))
      out)))

(defn lineage-sources
  "Every id a recall scoped to `space` is allowed to see: this notebook and its
   cells, everything below it (`descendant-sources`), and the own ids of every
   notebook above it (`ancestor-sources`). A branch sees its trunk; it does not
   see the branches beside it.

   The two walks stay separate on purpose. Up and down are different relations
   — down is transitive and takes everything it reaches, up is a single line
   that takes only what is on it — and fusing them into one traversal is
   precisely how this rule gets broken, because the fused version descends from
   the ancestors it reaches and hands you every sibling.

   `recall`'s filter takes a set of ids rather than one notebook id because
   `loci.memory` holds no store and must not: the half that can walk the
   substrate does the walk, the half that recalls takes the answer. This
   function is that walk, and it lives here for the same reason.

   Both the notebook and its cells are in the set because a distilled fact
   records `:source {:obj <the doc it came from> :space <the notebook>}` — the
   notebook id alone already matches everything `distill!` writes, and the cell
   ids additionally catch a fact whose source names only an object.

   Cost: one `nb/links` per notebook in the DOWNWARD scope, and `nb/links`
   scans every object — so that half is O(notebooks below × objects), not one
   0.200 ms call. Measured on a synthetic 240-object store: 0.6 ms for a leaf,
   5.4 ms for a ten-deep chain. The upward half adds one `sub/object` per
   ancestor and no scan at all. Kept anyway, because reusing `nb/links` means
   “spawned” and “merged-from” have exactly one definition and the scope
   follows it, and because this runs on a search the user typed and waits on —
   the same request spends 20–50 ms embedding the query.

   Written for a notebook. `/api/memory?space=` checks with `notebook-or-error`
   first; `remembered-context` does not, because `ask!` takes whatever id the
   shell has open. An id that is not a notebook is not an error here — it has no
   cells, no `:spawned-by`, and `nb/links` finds no edge to it, so the walk
   returns `#{that id}` and the recall comes back empty. Narrow, never leaky.

   Defined above `remembered-context` because that is its first caller; the
   `/api/memory` caller is far below, and browsing widens exactly as recall
   does — deliberately, so the pane shows what the agent is working from."
  [st space]
  (into (descendant-sources st space) (ancestor-sources st space)))

(defn subject-set
  "The subjects a recall is about: **the names `prompt` names, and nothing
   else.** Used for both jobs `recall` separates —
   `:filter {:entities}`, which decides what may be a candidate, and
   `:subjects`, which decides what raises one. One value, because after
   2026-08-09 there is no longer anything for the two to disagree about.

   **This deliberately departs from design §5**, which defines the subject set
   as \"entities detected in the prompt ∪ entities of facts already in this
   lineage\". The second half is gone. Do not restore it — it was built, shipped,
   measured on the real corpus, and it made things worse; the record is
   `docs/superpowers/specs/2026-08-08-entities-recall-measured.md`.

   What the measurement found, over 12 prompts a person would actually type:
   **7 got worse, and all 7 were prompts typed into a notebook that has a
   lineage.** `space:semis`, asked \"What are the chokepoints in the
   semiconductor supply chain?\", came back with six facts about Jupiter, Saturn
   and Mercury, replacing all six on-subject ones. The EUV notebook lost every
   EUV fact; the EDA notebook displaced the US EDA export ban itself.

   The cause was the lineage half, in both of the shapes it was tried:

   - **Unrestricted**, every name the lineage carries scores, so every local
     fact's entity half saturates at 1.0 and they all tie at 0.4000 — the union
     never out-scores anything, it ties and loses the tie-break to recency.
   - **Restricted** to names something outside the lineage also carries — the
     fix for that tie — it deletes precisely the names the notebook is *about*.
     `tsmc`, `taiwan`, `asml`, `synopsys` live in the semis family and nowhere
     else, which is what it MEANS for a research hub to have a subject, so all
     four were stripped and `:score` for a semiconductor question came out as
     `(\"earth\" \"japan\" \"jupiter\" \"mars\" \"mercury\" \"neptune\" \"saturn\"
     \"sun\" \"terrestrial planets\" \"venus\")`. This is structural, not a bad
     corpus: **the larger a notebook's lineage, the fewer of its own subjects
     survive**, and `space:semis` holds 56 of 80 facts.

   Driving the union from the prompt alone was probed against the same corpus
   before it was adopted — baseline ids retained in the top 6, shipped-then
   versus this:

     P1 chokepoints   0/6 → 6/6      P5 blockade cost  1/6 → 3/6 (still worse)
     P2 EUV           1/6 → 6/6      P6 capacity       4/6 → 6/6
     P3 TSMC/Taiwan   3/6 → 6/6      P7 AI customers   3/6 → 5/6
     P4 EDA controls  0/6 → 5/6      P10/P11/P12       all three wins kept

   One prompt degrades instead of seven, and the case the feature exists for —
   a brand-new notebook, where the agent used to be told nothing at all — is
   untouched.

   **The cost, named and accepted: a deep dive no longer inherits its parent's
   SUBJECTS.** Ask a child notebook a question that names nothing, and no fact
   is pulled in from outside its lineage on the strength of what the family
   happens to be about. It still inherits its parent's **facts**, through
   `:filter {:sources}` and `lineage-sources`, exactly as it always has — that
   is a different mechanism and this change does not touch it.

   A consequence worth knowing: **when the prompt names no name the corpus
   knows, this returns `#{}` and the whole feature is inert** — `:subjects` is
   empty so `qs` is just the query tokens, `:filter {:entities}` is empty so it
   admits nothing, and recall is character for character what it was before any
   of this existed. The feature costs nothing when it has nothing to say.

   Names come back normalized and non-blank; `ent/detect` guarantees both.

   Public so a test can call it without going through an LLM."
  [m prompt]
  ;; The name set is built here rather than with `(keys (ent/index …))`. The two
  ;; are the same set by construction — `index` normalizes each `:entities` entry
  ;; and drops the blanks, character for character what this does — but `index`
  ;; also accumulates a set of fact ids under every name, and this would then
  ;; throw all of them away. Measured at 800 facts: 0.700 ms → 0.333 ms.
  (ent/detect (into #{} (comp (mapcat :entities)
                              (map ent/normalize)
                              (remove str/blank?))
                    (mem/all-facts m))
              prompt))

;; ---- the recall seam, used by every agent flow ----
;; remembered-context injects what the agent already learned (cited);
;; distill! writes new memory AFTER a flow — async, best-effort, never undone.
;; :semantic? true here and NOT in leap-payload. This runs once per agent flow,
;; in front of an LLM call that takes seconds — a 20–50 ms query embedding buys
;; the agent facts it phrased differently the first time. /api/leap runs on every
;; keystroke and pays that same 20–50 ms for every character typed, which is why
;; recall's default is lexical and the two callers differ.
(defn- remembered-context
  "What the agent already learned, cited, and scoped to the notebook you are
   standing in — its cells, what it spawned transitively, and what was merged
   into it (`lineage-sources`).

   `recall` applies `:filter` BEFORE both retrievers, so a scoped recall cannot
   leak a fact by meaning either. Nothing is re-filtered here.

   `space` nil is the whole memory, because `ask!` below already falls back to
   the whole workspace for its DOCUMENT context in exactly that case — memory
   matches that rule rather than inventing a second one. The `and space
   (sub/object st space)` test is `ask!`'s, character for character, so the two
   halves of one system prompt can never disagree about whether there is a
   notebook.

   A notebook with nothing in scope yet gets nil — no REMEMBERED block at all.
   That is what a brand-new notebook looks like, and it is a normal state.

   **The scope is no longer lineage alone.** This paragraph REPLACES the one
   dated 2026-08-08 that accepted a cost — \"notebooks that share a SUBJECT but
   not a LINEAGE go blind to each other\", with `Serbia — open economic data`
   and `World economies — output and prices` named as two roots that would never
   offer each other anything. That cost is what this fixes, and the old
   paragraph is gone rather than kept beside this one, so nothing here reads as
   still live when it is not. Candidates are now the lineage's facts UNION the
   facts carrying a name the PROMPT named, and that one set does both jobs
   `recall` keeps separate: `:filter {:entities}` makes an unrelated root's fact
   eligible, `:subjects` makes it rank. Naming a subject in only one of the two
   is the quiet failure `recall`'s own opts comment describes.

   **What widens the scope is the question, never the notebook's habits.** An
   earlier shape of this also unioned in every name the lineage already carried
   — design §5's second half — and it was measured on the real corpus and
   removed on 2026-08-09: it degraded 7 of 12 real prompts, every one of them a
   prompt typed into a notebook that has a lineage. `subject-set`'s docstring
   carries the numbers and the accepted cost. Two consequences to keep in view
   here:

   - `k` stays 6 and the ranking is unchanged, so the block does not grow, it
     competes — a fact from another root has to out-score the local facts on
     the question actually asked.
   - When the prompt names nothing the corpus knows, `subs` is empty and this
     is inert: no name is added to the scope, no name is added to the scoring,
     and the block is exactly what it was before entities existed.

   Cost: one extra pass over the whole memory per agent turn (`all-facts` and a
   name set built from it), which `recall` then walks again for itself.
   Measured, mean of 300, on a synthetic corpus of the live shape: **0.23 ms at
   80 facts** — the live size — 1.37 ms at 500 and 6.8 ms at 3000, so linear and
   about a seventh of the `recall` it feeds (1.7 / 7.0 / 41.6 ms at those
   sizes), all of it in front of an LLM call that takes seconds. Sharing one
   pass would mean handing `loci.memory` a pre-built projection instead of a
   fact sequence, and that is a seam worth 0.23 ms not to open."
  [st prompt space]
  (let [sources (when (and space (sub/object st space)) (lineage-sources st space))
        subs    (subject-set @mem/memory prompt)
        opts    (cond-> {:k 6 :semantic? true :subjects subs}
                  sources (assoc :filter {:sources sources :entities subs}))
        facts (try (mold/recall @mem/memory prompt opts)
                   (catch Exception _ nil))]
    (when (seq facts)
      (str "\n\nREMEMBERED (distilled from earlier work — cite as ⌾ id when it shapes your answer):\n"
           (str/join "\n" (map #(str "- " (:fact %) " (⌾ "
                                     (or (get-in % [:source :obj]) (get-in % [:source :space]) "memory") ")")
                               facts))))))

(defn- distill! [prompt text obj-id space]
  (future
    (try
      (doseq [{:keys [fact entities] :as f} (agent/distill-facts prompt text)]
        (mold/remember @mem/memory fact
                       ;; A judgement passes ONLY if it is one — `boolean?`, on
                       ;; the whole map, and never a `{:keys [durable]}`
                       ;; destructure. `remember` keys on `contains?`, so
                       ;; `:durable? nil` reads as an answer rather than as
                       ;; silence, and every unjudged fact would land stamped
                       ;; `:durable-by "agent"`: judged by nobody, and past the
                       ;; guard that lets a later agent pass revise its own calls.
                       ;;
                       ;; `boolean?` and not `contains?` because a MALFORMED answer
                       ;; is worse than a missing one, and in the same direction:
                       ;; a model answering the string "false" would be truthy, so
                       ;; `weight` would skip decay on the fact it just declined to
                       ;; call timeless — a wrong judgement, stamped, that only a
                       ;; hand toggle can undo. Anything that is not true or false
                       ;; is treated as not having answered, which is the state the
                       ;; whole corpus is in and costs nothing to be in.
                       ;;
                       ;; Renamed on the way through: the model is asked for
                       ;; "durable", the record keys on :durable?.
                       (cond-> {:entities (mapv str entities)
                                :source {:obj obj-id :space space}}
                         (boolean? (:durable f)) (assoc :durable? (:durable f)))))
      (catch Exception _))))

(defn suggest-tags!
  "Ask the agent for tags. Writes NOTHING — a proposal you ignore leaves no
   trace at all, which is a stronger promise than reversibility."
  [st space]
  (let [o (sub/object st space)]
    (if-not (= :space (:kind o))
      {:error (str "not a notebook: " space)}
      (try
        (let [digest (->> (nb/cells-of o) (keep #(sub/object st (:ref %)))
                          (map #(obj-digest (tbl/hydrate lake/lake %))) (str/join "\n"))]
          {:tags (vec (take 3 (agent/propose-tags (:title o)
                                                  (get-in o [:value :intent]) digest)))})
        (catch Exception e {:error (.getMessage e)})))))

(defn inherit-tags
  "A deep-dive child is created ABOUT its parent's subject, so it starts with
   the parent's tags — as inferences, because you asserted them of the parent,
   not of the child. The stamp is now: the child was tagged when it was born,
   not when you tagged its parent."
  [st space]
  (mapv #(assoc % :by "agent" :ts (System/currentTimeMillis))
        (get-in (sub/object st space) [:value :tags])))

(defn ask! [st prompt space]
  (try
    (let [objs    (if-let [sp (and space (sub/object st space))]
                    (keep #(sub/object st (:ref %)) (nb/cells-of sp))  ; scoped to this space
                    (vals (sub/objects st)))                                  ; whole workspace
          objs    (vec (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs))
          tbls    (filterv #(= :table (:kind %)) objs)
          ;; Tables go to the catalog, which reads their handles; only the DOCS
          ;; are hydrated, because only `obj-digest` needs rows. The shape test
          ;; that follows still stands: it is what keeps a non-`:table` object
          ;; whose value happens to BE rows out of the prose digest.
          texty   (->> objs
                       (remove #(= :table (:kind %)))
                       (mapv #(tbl/hydrate lake/lake %))
                       (remove #(and (sequential? (:value %)) (seq (:value %)) (every? map? (:value %))
                                     (not (every? :block (:value %)))))) ; docs + reports (not tables)
          allowed (when space (set (map :id tbls)))
          catalog (table-catalog tbls)
          sys     (str "Answer the user's question about their workspace. Cite the object ids you used. "
                       "Do NOT guess any table figure — call query_table for exact numbers. "
                       "If the data doesn't say, say so plainly. Be concise, markdown.\n\n"
                       "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                       "\n\nTABLES (query these by id):\n" catalog
                       (remembered-context st prompt space))
          tool-fn (fn [nm a]
                    (if (and allowed (= nm "query_table") (not (allowed (:table_id a))))
                      {:error "that table is not in this space"}
                      (tools/dispatch st nm a)))]
      (let [answer (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                     tools/specs tool-fn)]
        (distill! prompt answer nil space)
        {:answer answer}))
    (catch Exception e {:error (.getMessage e)})))

(defn import-csv! [st title csv space]
  (try
    (let [rows (tools/parse-csv csv)
          nid (str "tbl:csv-" (count (filter #(= :table (:kind %)) (vals (sub/objects st)))) "-" (inc (rand-int 9999)))
          obj (tbl/table-object @lake/lake nid (or (not-empty title) "Imported CSV") rows)
          base [{:op :put :id nid :value obj}]
          evs (if (and space (space? st space))
                (conj base (nb/append-cell-event st space {:ref nid}))
                base)]
      (sub/commit! st {:op :tx :events evs})
      {:state (state-payload st) :openId nid})
    (catch Exception e {:error (.getMessage e)})))

(defn- next-id-in
  "`next-id` over an objects map already in hand.

   Split out for the caller that mints TWO ids before one commit
   (`materialize-entity!`). On the EDN stores `sub/objects` re-folds the whole
   event log, so minting twice from the store folds it twice — measured at 3003
   events: `sub/objects` alone 0.717 ms, one `next-id` 1.135 ms, two 2.334 ms.
   That work sat inside a monitor, which is the one place where halving it is
   worth a three-line split."
  [objm prefix]
  (let [n (->> (keys objm)
               (keep #(when (str/starts-with? % prefix)
                        (try (Long/parseLong (subs % (count prefix)))
                             (catch Exception _ nil))))
               (reduce max 0))]
    (str prefix (inc n))))

(defn next-id
  "Mint '<prefix>N' with N = 1 + the highest existing numeric suffix across the
   WHOLE store. Counting a notebook's refs collides after a cell remove — a
   removed ref lowers the count while the object lives on.

   Public for `loci.repair`, which mints a restored findings document and must
   mint it by exactly this rule. A repair carrying its own copy of the rule is
   how one prefix comes to have two counters, neither of which can see all the
   ids under it — the failure § 7 of the 2026-08-09 forensics is about."
  [st prefix]
  (next-id-in (sub/objects st) prefix))

(defn findings-prefix
  "The `find:` prefix a notebook's findings documents are minted under:
   `space:dd-35` → `find:dd-35-`.

   A function rather than the expression inlined at its one call site, because
   there are now two call sites — `research!` and `loci.repair`, which restores
   a findings document that `research!` originally wrote. Two copies of this
   would let a repaired document be named by a rule the server no longer uses."
  [space]
  (str "find:" (subs space (inc (str/index-of space ":"))) "-"))

;; ---- materializing an entity: the one action on the entity page ----
;;
;; ONE global monitor, which is the opposite of `notebook-locks` above and
;; deliberately so. What is serialised here is not one entity's cell vector but
;; `next-id`, a read of the WHOLE store's keys: a per-name lock would let two
;; different names mint the same `ent:1` and the second `:put` would silently
;; replace the first. Measured before this lock, over ten rounds: 8 concurrent
;; materializations of 8 different names left **1 to 3** entity objects, and 32
;; left 8 to 11 — 8 commits every time, each one overwriting the last, so the
;; log says the work happened and the world says it did not. The second half of
;; that measurement is the one to keep in mind: the notebook ids collide too, so
;; several callers were handed the SAME notebook and a user asking about one
;; subject is walked into another's. With the lock: 8 of 8 over ten rounds, 32
;; of 32 over five.
;;
;; It also makes "materializing twice mints nothing" true rather than nearly
;; true. Check-then-commit is a read-modify-write, and two clicks on one name
;; are a plain TOCTOU race without it — measured, 4 to 7 phantom commits and
;; **two objects for one name**, which is exactly what `entity-objects` calls a
;; coin toss. With the lock, one object and one commit every round.
;;
;; Both figures are from the EDN store, where `commit!` is one `swap!`;
;; Datalevin's takes a lock and writes to LMDB, which only widens the window.
;;
;; Contention is one click on a modal against another click on a modal, so there
;; is nothing to gain by being clever here and a whole class of loss by being
;; wrong. `locking` is a monitor and not a distributed lock — loci is a single
;; process, which is the whole scope of the problem; a second server against the
;; same store would still race, exactly as `notebook-op!` records.
(defonce ^:private materialize-lock (Object.))

(defn- about-notebook
  "The id of the notebook that is about the entity `eid`, named `nm` — one whose
   cells reference it — or nil, out of an objects map already in hand.

   **A notebook titled for the entity wins; ties and the rest go to the
   lexicographically least id; nil is a real answer.**

   The two rules answer two different questions, and it is worth keeping them
   apart:

   *The title preference is about being right.* Several notebooks holding one
   entity is not a race — it is the ordinary consequence of adding a subject to
   somebody else's notebook, and one of the candidates is then plainly the
   better answer: the one this materialization made, which `materialize-entity!`
   titles with the name. Least id alone gets that wrong the moment the entity's
   own notebook is not the oldest — materialize `asml`, then `tsmc`, then drop
   `tsmc` into `asml`'s notebook, and `space:about-1` beats `space:about-2` for
   an entity it merely holds. The comparison is on the title and NOT on the id,
   for `entity-objects`' reason: a rule that preferred a `space:about-` prefix
   would make this the second place that believes something about how notebook
   ids are minted.

   The title is a display string, so this is a *preference* and never an
   identity — `entity-objects` still matches on `[:value :name]` alone. Rename
   the notebook and you lose the preference, not the entity; the answer falls
   back to least id, which is deterministic and no longer necessarily the one
   you wanted.

   *Least id is only about being the same answer twice.* `vals` of an objects
   map is hash order, so with no tie-break at all two equally-titled candidates
   would be answered arbitrarily and nothing in the log would explain which.
   That is the same purpose as `entity-objects`' tie-break but not the same
   situation: there, a duplicate is a race artefact and either answer is equally
   wrong, so determinism is all there is to want. Here both candidates are
   legitimate, which is exactly why determinism alone was not enough and the
   preference above exists.

   nil because an entity can genuinely have no notebook: the cell is an ordinary
   cell and can be removed, the notebook is an ordinary object and can be
   deleted, and `undo!` can pop the very commit that made both while an *older*
   materialization of the same name survives. The caller ships the nil rather
   than hiding the key, so a shell can open the entity object instead of trying
   to enter nothing."
  [objm eid nm]
  (->> (vals objm)
       (filter #(and (= :space (:kind %)) (contains? (nb/refs-of %) eid)))
       (sort-by (juxt #(if (= nm (:title %)) 0 1) :id))
       first
       :id))

(defn materialize-entity!
  "POST /api/entity-notebook {\"name\": \"tsmc\"} — the entity becomes a citizen.

   ONE `:tx`: the `:entity` object carrying the normalized name, the notebook
   about it, and the cell that puts one in the other. `undo!` pops one commit, so
   the gesture is reversed whole. Two `commit!`s would need two undos, and the
   state between them — a notebook about an entity that no longer exists — is one
   the shell has no way to draw and nobody asked for.

   Read that precisely: `undo!` pops the LAST commit, not this one. The `:tx` is
   what makes the gesture *atomic*; it does not make it *addressable*. Commit
   anything at all in between and the next `undo!` takes that instead, leaving
   the entity and its notebook standing. Measured, and true of every gesture in
   loci that toasts with an undo — `new-space!`, `connect!`, `keep-note!` — so it
   is the substrate's model of undo and not a shortcoming here. What the `:tx`
   rules out is the halfway state, which is the part that was ours to prevent.

   **No fact is touched, and no fact learns this object's id.** A fact references
   the identity — the normalized name — so undoing this returns the entity to
   being a projection with nothing left dangling. That is the whole reason the
   model is derived, and it is the invariant to protect in review. Today the
   argument list enforces it: this function is never handed the memory, so it
   *cannot* rewrite a fact. Keep it that way; the day it takes a memory is the
   day the invariant becomes a convention.

   Materializing an already-materialized name is not an error and mints nothing:
   it answers with the object that is already there. The page is reached by name,
   and two objects for one name would make `entity-object` a coin toss. It does
   NOT mint a fresh notebook when the old one is gone either — the gesture is
   reached from a modal the shell only draws for an unmaterialized name (Task 8
   opens the object directly when the payload carries one), so this branch is a
   stale click or a race, and answering it by spawning a notebook per stale click
   is a worse failure than answering `nil`.

   The payload, which is the contract Task 8 draws against:

     :entity   the object id. Always present — the entity is a citizen either way.
     :spaceId  the notebook to enter, or **nil**. The key is always there; nil
               means there is no notebook about this and the shell should open
               `:entity` instead. See `about-notebook`.
     :state    always, including on the branch that committed nothing. That
               branch is reached precisely when the client's world is stale —
               someone else materialized this name, or this client's own earlier
               POST landed and the reply was lost — so \"nothing changed\" is
               true of the store and false of the caller. The cost is one walk
               over every object plus one fold over the whole log for recency,
               measured beside `state-payload` itself at 2.2 ms on a 3003-event
               log (cheaper on Datalevin, where the objects map is already
               materialized and only the recency fold remains). Omitting it
               would buy that back by making the shell branch on which of two
               shapes it got — a worse trade for a gesture that happens on a
               click.

   `nm`, not `name`: a local called `name` shadows `clojure.core/name`, which
   nothing here happens to need today and which the next edit to this function
   would find missing for no visible reason. `entity-payload` renamed for the
   same reason."
  [st name*]
  (let [nm (ent/normalize name*)]
    (if (str/blank? nm)
      {:error "no entity name given"}
      ;; Nothing but the reads and the commit is inside the monitor — the payload
      ;; is built after it, and no agent call and no further I/O may move in. The
      ;; commit itself IS I/O on the production store: `DatalevinStore/commit!`
      ;; takes its own lock and writes to LMDB before publishing, so this monitor
      ;; holds across a disk write by construction. Nesting order is
      ;; `materialize-lock` → dlv lock, the same direction `notebook-op!` nests,
      ;; and dlv is innermost and never calls back into this namespace — so the
      ;; two monitors cannot deadlock against each other.
      (let [[eid sid]
            (locking materialize-lock
              (let [objm (sub/objects st)]
                (if-let [o (get (entity-objects objm) nm)]
                  [(:id o) (about-notebook objm (:id o) nm)]
                  (let [eid  (next-id-in objm "ent:")
                        sid  (next-id-in objm "space:about-")
                        eobj {:id eid :kind :entity :title nm :value {:name nm}}
                        sobj {:id sid :kind :space :title nm
                              :value {:intent (str "everything about " nm)
                                      :cells [{:ref eid}]}}]
                    (sub/commit! st {:op :tx :events [{:op :put :id eid :value eobj}
                                                      {:op :put :id sid :value sobj}]})
                    [eid sid]))))]
        {:state (state-payload st) :entity eid :spaceId sid}))))

(defn keep-note! [st space title text]
  (let [sp  (sub/object st space)
        nid (next-id st (str "note:" (subs space (inc (str/index-of space ":"))) "-"))]
    (sub/commit! st {:op :tx :events [{:op :put :id nid :value {:id nid :kind :doc :title title :value text}}
                                      (nb/append-cell-event st space {:ref nid})]})
    {:state (state-payload st) :openId nid}))

;; (2) tool-powered delegation — the agent drafts a brief, calling query_table
;; for exact figures (and web_search once a key is set), grounded in the space.
(defn delegate! [st space]
  (let [sp (sub/object st space)
        members (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
                     (remove #(#{:space :viewspec :applet :fn} (:kind %)))
                     vec)
        tbls    (filterv #(= :table (:kind %)) members)
        ;; docs hydrate (obj-digest reads rows); tables do not (the catalog
        ;; reads their handles) — see `table-catalog`
        texty   (mapv #(tbl/hydrate lake/lake %) (remove #(= :table (:kind %)) members))
        allowed (set (map :id tbls))
        catalog (table-catalog tbls)
        sys (str "You are a concise analyst. Draft a short markdown brief (≤150 words, with a heading) "
                 "for this workspace space. Use query_table for any exact figure — never guess. Cite object ids.\n\n"
                 "Space: " (:title sp) ". Intent: " (get-in sp [:value :intent]) ".\n\n"
                 "DOCS:\n" (str/join "\n\n" (map obj-digest texty)) "\n\nTABLES (query by id):\n" catalog
                 (remembered-context st (str (:title sp) " " (get-in sp [:value :intent])) space))
        tool-fn (fn [nm a] (if (and (= nm "query_table") (not (allowed (:table_id a))))
                             {:error "that table is not in this space"}
                             (tools/dispatch st nm a)))
        text (try (agent/chat-tools [{:role "system" :content sys}
                                     {:role "user" :content "Write the brief now."}]
                                    tools/specs tool-fn)
                  (catch Exception e (str "# Draft for " (:title sp) "\n\n_(agent unavailable: " (.getMessage e) ")_")))
        did (next-id st (str "draft:" (subs space (inc (str/index-of space ":"))) "-"))
        draft {:id did :kind :doc :title (str "Draft — " (:title sp)) :value text}]
    (sub/commit! st {:op :tx :events [{:op :put :id did :value draft}
                                      (nb/append-cell-event st space {:ref did})]})
    (distill! (str "brief for " (:title sp)) text did space)
    {:state (state-payload st) :openId did}))

;; shared agent context for a space (docs inline + table catalog + scoped tools)
(defn- agent-ctx [st space]
  (let [objs (if-let [sp (and space (sub/object st space))]
               (keep #(sub/object st (:ref %)) (nb/cells-of sp))
               (vals (sub/objects st)))
        objs (vec (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs))
        tbls  (filterv #(= :table (:kind %)) objs)
        ;; `obj-digest` genuinely needs rows; the catalog does not. So hydrate
        ;; the DOCS only. Hydrating everything cost 282 ms on every chat turn
        ;; over the migrated store, scaling with the workspace's total rows
        ;; rather than with the table anyone asked about.
        texty (mapv #(tbl/hydrate lake/lake %) (remove #(= :table (:kind %)) objs))
        allowed (when space (set (map :id tbls)))
        catalog (table-catalog tbls)]
    {:context (str "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                   "\n\nTABLES (query by id):\n" catalog)
     :tool-fn (fn [nm a]
                (cond
                  (= nm "save_table")
                  (tools/save-table! st (:title a) (:rows a) space)
                  (and allowed (= nm "query_table") (not (allowed (:table_id a))))
                  {:error "that table is not in this space"}
                  :else (tools/dispatch st nm a)))}))

;; Research — the agent gathers (web + your data) and LANDS the findings as a
;; durable, moldable, reversible object in the space.
(defn research! [st space prompt]
  (try
    (let [{:keys [context tool-fn]} (agent-ctx st space)
          saved (atom [])
          tf (fn [nm a] (let [r (tool-fn nm a)]
                          (when (:saved_as r) (swap! saved conj (:saved_as r)))
                          r))
          sys (str "You are a research assistant for a workspace space. Use web_search for external facts "
                   "and query_table for the user's own data. If your findings are tabular, or you can "
                   "EXTRACT structured rows from a document or technical text in the context below, call "
                   "save_table ONCE with ALL the rows — that table IS the deliverable. After saving it, do "
                   "NOT reproduce the table in your note: write only a 2-3 bullet summary of what the data "
                   "shows and a final '## Sources' section. If there is nothing tabular, write a normal "
                   "markdown findings note instead. Be specific.\n\n" context
                   (remembered-context st prompt space))
          run-once (fn [] (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                            tools/specs tf))
          ;; the tool loop occasionally returns an empty final message —
          ;; retry once, then fail honestly rather than land a blank note
          text (let [t (run-once)] (if (str/blank? t) (run-once) t))
          p (str/trim prompt)]
      (if (str/blank? text)
        {:error "research came back empty — try again (anything it saved mid-way is in the notebook, reversible)"}
        (let [tid (or (first @saved)
                      ;; the model wrote its table INTO the prose instead of
                      ;; calling save_table — salvage it deterministically
                      (when-let [rows (tools/md-table->rows text)]
                        (:saved_as (tools/save-table!
                                    st (str "Extracted — " (if (> (count p) 40) (str (subs p 0 40) "…") p))
                                    rows space))))
              fid (next-id st (findings-prefix space))
              title (str "Findings — " (if (> (count p) 44) (str (subs p 0 44) "…") p))]
          (sub/commit! st {:op :tx :events [{:op :put :id fid :value {:id fid :kind :doc :title title :value text}}
                                            (nb/append-cell-event st space {:ref fid})]})
          (distill! prompt text fid space)
          ;; when extraction produced a real table, THAT is the artifact — open it,
          ;; not the prose note. ponytail: open the data, keep the note as context.
          {:state (state-payload st) :openId (or tid fid)})))
    (catch Exception e {:error (.getMessage e)})))

;; ---- suggest: the agent proposes, you decide ----
;; This was deep-dive!, which proposed AND spawned AND researched on one click.
;; The capability was right and the consent was missing, so it splits here: the
;; half that reads and proposes writes nothing, and the half that acts takes only
;; what you approved.
(defn suggest!
  "Questions worth pursuing in this notebook — proposed from the hub's findings
   AND recalled memory. Commits NOTHING: not a reversible event, no event.
   Dismissing leaves the substrate untouched."
  [st space]
  (try
    (let [sp (sub/object st space)]
      (if-not (= :space (:kind sp))
        {:error (str "not a notebook: " space)}
        (let [digest (str (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
                               (map #(obj-digest (tbl/hydrate lake/lake %))) (str/join "\n"))
                          (remembered-context st (str (:title sp) " " (get-in sp [:value :intent])) space))]
          {:proposals (vec (take 3 (agent/propose-subtopics
                                    (:title sp) (get-in sp [:value :intent]) digest)))})))
    (catch Exception e {:error (.getMessage e)})))

(defn fresh-dd-id
  "One past the highest space:dd-N, and never one that already exists. The old
   form derived the id from the TOTAL notebook count, which had nothing to do
   with the dd sequence; two notebooks in the store mint \"space:dd-3\", so the
   day anything deletes a notebook that :put would overwrite a live notebook in
   silence. The existence loop terminates: n only rises, and the store holds
   finitely many objects.

   Public for `loci.repair`, which restores the three notebooks the count form
   destroyed and must mint their new ids by the rule that replaced it — see
   `next-id` for why the repair does not carry its own copy."
  [st]
  (let [highest (->> (nb/notebooks st)
                     (keep #(some->> (:id %) (re-find #"^space:dd-(\d+)$") second parse-long))
                     (reduce max 0))]
    (loop [n (inc highest)]
      (if (sub/object st (str "space:dd-" n)) (recur (inc n)) (str "space:dd-" n)))))

(defn run-suggestions!
  "Research the questions you approved. `items` is the curated, possibly edited
   list — the agent is not re-asked and the originals are not re-read, so what
   you approved is exactly what runs. `destination` is \"new\" (one connected
   notebook each, as deep-dive did) or \"here\" (cells in this notebook, exactly
   as pressing Research repeatedly would).

   Not atomic across items, deliberately: three researches are three commits, and
   a failure on the second leaves the first in place. A partial result is worth
   keeping and is individually undoable."
  [st space items destination]
  (try
    (let [sp (sub/object st space)]
      (cond
        (not= :space (:kind sp)) {:error (str "not a notebook: " space)}
        (empty? items)           {:error "nothing to research"}
        (not (#{"new" "here"} destination))
        {:error (str "unknown destination: " destination)}
        :else
        ;; every child of this one act inherits the same subject, stamped once
        (let [ptags (inherit-tags st space)
              done  (reduce
                     (fn [acc {:keys [title intent query]}]
                       (if (= "new" destination)
                         (let [sid (fresh-dd-id st)]
                           (sub/commit! st {:op :put :id sid
                                            :value {:id sid :kind :space :title title
                                                    :value (cond-> {:intent intent :cells []
                                                                    :spawned-by {:space space :prompt query}}
                                                             (seq ptags) (assoc :tags ptags))}})
                           (research! st sid query)
                           (conj acc sid))
                         (do (research! st space query) (conj acc space))))
                     [] items)]
          {:state (state-payload st) :ran done :destination destination})))
    (catch Exception e {:error (.getMessage e)})))

;; ---- jobs: long agent flows (research, suggest) run off-request so the
;; browser never holds a minutes-long fetch. The frontend polls /api/job. ----
(defonce ^:private job-seq (atom 0))
(defonce ^:private jobs (atom {}))

(defn start-job! [f]
  (let [id (str "job:" (swap! job-seq inc))]
    (swap! jobs assoc id {:done false})
    (future
      (let [r (try (f) (catch Exception e {:error (.getMessage e)}))]
        (swap! jobs assoc id {:done true :result r})))
    id))

(defn job-status [id]
  ;; unknown id is :done so a poller stops (e.g. after a server restart)
  (get @jobs id {:done true :error (str "unknown job: " id)}))

(defn- notebook-or-error [st space]
  (let [sp (sub/object st space)]
    (when-not (= :space (:kind sp)) {:error (str "not a notebook: " space)})))

(defn suggest-start! [st space]
  (or (notebook-or-error st space)
      {:job (start-job! #(suggest! st space))}))

(defn suggest-run-start! [st space items destination]
  (or (notebook-or-error st space)
      {:job (start-job! #(run-suggestions! st space items destination))}))

;; ---- flows: the full agentic loop as a substrate object. The agent plans,
;; the interpreter executes over the existing verbs, EVERY transition is a
;; reversible event — checkpoint/resume/replay come from the log, and a
;; gate step parks the flow needs-you until the human says go. ----
(def ^:private flow-verbs #{"research" "compute" "ask" "draft" "gate"})

(defn validate-plan
  "Agent-proposed steps → trusted steps: unknown verbs dropped, capped at 6,
   every step normalized to {:verb :args :note :status \"pending\"}."
  [steps]
  (->> steps
       (keep (fn [s] (let [v (str (:verb s))]
                       (when (flow-verbs v)
                         {:verb v :args (let [a (:args s)] (if (map? a) a {}))
                          :note (str (or (:note s) "")) :status "pending"}))))
       (take 6) vec))

(defn resolve-ref
  "\"$N\" in step args means the output at or before step min(N, last) —
   the backward walk skips output-less steps (gates), which also absorbs
   planners that count 1-based. No output anywhere before N → literal."
  [flow v]
  (if (and (string? v) (str/starts-with? v "$"))
    (if-let [n (parse-long (subs v 1))]
      (let [steps (:steps flow)
            n     (min n (dec (count steps)))]
        (or (first (keep #(:out (nth steps % nil)) (range n -1 -1))) v))
      v)
    v))

(defn- table-shaped?
  "Does `id` name something whose value is rows? A handle counts — it IS a
   table, it just keeps its rows in the lake.

   The failure that needs is a flow with a **non-table step before the table**:
   with one step `resolve-ref` has already produced that step's own `:out` and
   `resolve-table-ref`'s `or` falls back to it either way. With a doc in front,
   a scan that reads a handle as 'not a table' skips past the lake-backed table
   and the compute step runs on prose.

   It used to hydrate to find that out — fetching every row of every candidate
   to answer a boolean, on a scan that runs over earlier steps until one
   matches. `tbl/table-shape` answers it from the handle."
  [st id]
  (some? (tbl/table-shape lake/lake (sub/object st id))))

(defn- step-index->ref
  "A bare step index — `0`, `\"0\"` — as the `$`-ref the planner meant.

   Anything else is returned untouched, which is what keeps `tbl:orders` a
   literal id rather than a reference to be resolved. Only a value that could
   not possibly be an object id is rewritten: loci ids all carry a `<kind>:`
   prefix, so a run of digits is unambiguous."
  [v]
  (cond
    (integer? v) (str "$" v)
    (and (string? v) (re-matches #"\d+" v)) (str "$" v)
    :else v))

(defn resolve-table-ref
  "The table a compute step should run on: its `:id` when that names a real
   table, else the nearest earlier output that is one.

   **The fallback is no longer gated on the value looking like a `$`-ref, and
   that gate is what made agent flows fail.** Measured 2026-08-15 on the only
   two flows this project has ever run — one from August, one reproduced today
   — the planner emitted `:id 0` and `:id \"0\"` respectively, a bare step
   INDEX, while putting the real reference in the prose (\"From $0, create a
   new table…\"). Neither starts with `$`, so both were passed through as
   literal object ids, `compute-clj!` looked up an object named `0`, found
   none, and answered \"compute works on tables\" in under a millisecond. The
   research step immediately before had produced a perfectly good table.

   Asking the planner to emit `$0` instead would be asking a model to hold a
   format it has now failed to hold twice. Resolving what it means is cheaper
   and does not depend on it.

   **A literal id is still never hijacked**, and the distinction is narrow on
   purpose: a bare INTEGER is a step index, `tbl:orders` is an id. Only the
   first is rewritten. An explicit id that names nothing still falls through to
   the honest failure it always did, because substituting a different table for
   one somebody named by hand is the worse error.

   No tabular output anywhere → the unresolved value, so the failure
   downstream stays honest rather than becoming a confident wrong table."
  [st flow v]
  (let [ref (step-index->ref v)]
    (if (and (string? ref) (str/starts-with? ref "$"))
      (let [r (resolve-ref flow ref)]
        (if (and (string? r) (table-shaped? st r))
          r
          (or (->> (:steps flow) (keep :out) reverse (filter #(table-shaped? st %)) first)
              r)))
      ;; the ORIGINAL value, not the normalized one: a literal id passes through
      ;; untouched exactly as it always did, and only a reference is resolved
      v)))

(defn flow-create!
  "Commit the flow object + its notebook cell as ONE :tx. Steps must already
   be validated. Returns the flow id."
  [st space goal steps]
  (let [fid (next-id st "flow:")
        fobj {:id fid :kind :flow :title (str "Flow — " (if (> (count goal) 40) (str (subs goal 0 40) "…") goal))
              ;; `:started` so the card can say how long this has been going.
              ;; On the flow rather than derived from the event's `:ts`: an event
              ;; timestamp is the substrate's, and a flow that is undone and
              ;; re-run would read as having started at the undo.
              :value {:goal goal :space space :status "running" :started (System/currentTimeMillis)
                      :steps (vec steps)}}]
    (sub/commit! st {:op :tx :events [{:op :put :id fid :value fobj}
                                      (nb/append-cell-event st space {:ref fid})]})
    fid))

(defn- flow-assoc! [st fid value]
  (sub/commit! st {:op :assoc :id fid :path [:value] :value value}))

(def ^:private flow-over?
  "The statuses after which nothing more runs. `needs-you` is deliberately not
   one: the flow is stopped but not finished, and stamping `:ended` there would
   make the card report an elapsed time that stops while you are deciding and
   then resumes, which is neither wall-clock nor working time."
  #{"done" "failed" "rejected"})

(defn- flow-step!
  "One transition: update step i (and optionally the flow status) — one event."
  [st fid i patch & [flow-status]]
  (let [fl (:value (sub/object st fid))
        fl (cond-> (update-in fl [:steps i] merge patch)
             flow-status (assoc :status flow-status)
             (flow-over? flow-status) (assoc :ended (System/currentTimeMillis)))]
    (flow-assoc! st fid fl)
    fl))

(defn- exec-step [st flow space {:keys [verb args]}]
  (case verb
    "research" (let [r (research! st space (str (:prompt args)))]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "compute"  (let [r (compute-clj! st (str (resolve-table-ref st flow (:id args))) (str (:prompt args)) space)]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "draft"    (let [r (delegate! st space)]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "ask"      (let [r (ask! st (str (:prompt args)) space)]
                 (if (:error r)
                   {:why (:error r)}
                   (let [k (keep-note! st space (str "Answer — " (:prompt args)) (:answer r))]
                     (if (:error k) {:why (:error k)} {:out (:openId k)}))))
    {:why (str "unknown verb " verb)}))

(defn run-flow!
  "Execute pending steps in order. Stops at a gate (needs-you), a failure
   (failed), or the end (done). Synchronous — callers wrap in start-job!."
  [st fid]
  (loop []
    (let [fl (:value (sub/object st fid))
          i  (first (keep-indexed (fn [k s] (when (= "pending" (:status s)) k)) (:steps fl)))]
      (cond
        (nil? i)
        (do (when (not= "done" (:status fl))
              (flow-assoc! st fid (assoc fl :status "done" :ended (System/currentTimeMillis))))
            {:state (state-payload st) :flowId fid :status "done"})

        (= "gate" (get-in fl [:steps i :verb]))
        (do (flow-step! st fid i {:status "needs-you"} "needs-you")
            {:state (state-payload st) :flowId fid :status "needs-you"})

        :else
        (let [_  (flow-step! st fid i {:status "running"})
              fl (:value (sub/object st fid))
              r  (exec-step st fl (:space fl) (get-in fl [:steps i]))]
          (if (:why r)
            (do (flow-step! st fid i {:status "failed" :why (:why r)} "failed")
                {:state (state-payload st) :flowId fid :status "failed"})
            (do (flow-step! st fid i {:status "done" :out (:out r)})
                (recur))))))))

(defn flows-payload
  "GET /api/flows — every flow in the substrate, the ones needing you first.

   **Its own endpoint rather than a widening of `state-payload`.** That payload
   ships every object as `{:id :title :kind}` and is fetched on every state
   change; putting per-flow step arithmetic in it would make the common read pay
   for a pane that is usually closed. This is the same argument `/api/memory`
   already makes for facts.

   `needs-you` first and then newest first, because the whole reason to open
   this is 'is anything waiting on me', and a flow waiting in a notebook you are
   not standing in is otherwise invisible until you wander into it.

   `:question` rides along only for a flow that is actually at a gate — it is
   what the reader has to answer, and fetching the flow again to find it would
   make the list a menu rather than an answer."
  [st]
  (let [flows (->> (sub/objects st) vals (filter #(= :flow (:kind %))))
        row   (fn [o]
                (let [v     (:value o)
                      steps (:steps v)
                      done  (count (filter #(= "done" (:status %)) steps))
                      cur   (first (filter #(#{"running" "needs-you"} (:status %)) steps))]
                  (cond-> {:id (:id o) :title (:title o) :goal (:goal v)
                           :space (:space v) :status (:status v)
                           :steps (count steps) :done done
                           :started (:started v) :ended (:ended v)}
                    cur (assoc :step (:verb cur))
                    (= "needs-you" (:status v))
                    (assoc :question (get-in cur [:args :question])))))]
    {:flows (->> flows
                 (map row)
                 (sort-by (juxt #(if (= "needs-you" (:status %)) 0 1)
                                #(- (or (:started %) 0))))
                 vec)
     :needs-you (count (filter #(= "needs-you" (get-in % [:value :status])) flows))}))

(defn flow-start!
  "Endpoint entry: validate the notebook NOW, then plan + create + run in a job."
  [st space goal]
  (cond
    (not (space? st space)) {:error (str "not a notebook: " space)}
    (str/blank? goal)       {:error "what should the flow do?"}
    :else
    {:job (start-job!
           (fn []
             (let [sp    (sub/object st space)
                   ctx   (str/join "\n" (map #(str "- " (:id %) " — " (:title %))
                                             (keep #(sub/object st (:ref %)) (nb/cells-of sp))))
                   steps (validate-plan (agent/plan-flow goal ctx))]
               (if (empty? steps)
                 {:error "the agent could not plan that — try rephrasing the goal"}
                 (run-flow! st (flow-create! st space goal steps))))))}))

(defn flow-gate!
  "The human answers the gate. Approve → gate step done, flow resumes via
   start-job!. Reject → flow rejected, nothing more runs."
  [st fid approve]
  (let [o (sub/object st fid)]
    (if-not (= :flow (:kind o))
      {:error (str "not a flow: " fid)}
      (let [fl (:value o)
            i  (first (keep-indexed (fn [k s] (when (= "needs-you" (:status s)) k)) (:steps fl)))]
        (cond
          (nil? i) {:error "this flow isn't waiting on you"}
          approve  (do (flow-step! st fid i {:status "done"} "running")
                       {:job (start-job! #(run-flow! st fid)) :flowId fid})
          :else    (do (flow-step! st fid i {:status "rejected"} "rejected")
                       {:state (state-payload st) :flowId fid :status "rejected"}))))))

(defn research-start! [st space prompt]
  (or (notebook-or-error st space)
      (when (str/blank? prompt) {:error "empty prompt"})
      {:job (start-job! #(research! st space prompt))}))

(defn memory-payload
  "The memory pane: every fact newest-first, or the ranked answer to a query.

   `:semantic? true` because this is a search the user typed and then waited
   for — the opposite of /api/leap, which calls recall on every keystroke and
   must not spend a network round trip doing it.

   `:vec` is dropped on the way out. A fact's embedding is ~1024 floats, about
   20 KB as JSON, and the pane shows text — sending them would turn this
   response into megabytes for nothing.

   `:awaiting` is how many facts have no vector for the configured model, and
   `:embedding` is that model or nil. Both, because either alone lies: with no
   embedder configured every fact is awaiting one forever, and a count with no
   model beside it reads like a backlog rather than an unconfigured feature.

   `:total` is how many **live** facts the memory holds — independent of `q` and
   of `scope`, which is the whole point of it. `:facts` under a query is the
   ranked top 20, so a denominator taken from the page would say \"12 of 20
   awaiting\": a claim about the memory made out of a page of results, and one
   that shrinks as the page does. The pane's status line is a statement about the
   memory, so the number in it has to come from the memory. Tombstones are not
   counted, because `all-facts` filters them and a fact absorbed by a merge is
   not a fact anyone can be shown.

   `:merge` is `mem/merge-status` — always present, always a map, one of
   `{:threshold t}`, `{:refused \"…\"}` or `{:off true}`. Without it a switch to
   an uncalibrated embedding model stops deduplication with one line on stderr
   at startup and nothing in the app that ever says so again. It is a lookup in
   the threshold table, not a merge: this is a GET.

   `:degraded` is the opposite shape on purpose — **absent** when nothing is
   wrong, rather than present and nil. Its value exists only when the embedder
   was asked something and did not answer, and a key that is always there and
   usually empty is what makes `if (p.degraded)` and `if ('degraded' in p)`
   disagree in the shell that reads it. `:merge` can be unconditional because it
   always has something true to say and its value is always a map.

   `scope` is nil or `{:space id :sources #{id …}}` from `lineage-sources`. Nil
   is the whole memory and the response is exactly what it has always been —
   same keys, same `recall` opts — because every caller that exists today passes
   no scope and none of them asked for a new field."
  ([m q] (memory-payload m q nil))
  ([m q scope]
   (let [opts (cond-> {:k 20 :semantic? true}
                scope (assoc :filter {:sources (:sources scope)}))
         ;; every live fact, read once and used for both `:total` and the browse
         ;; branches below — not per branch, so `:total` cannot drift into being
         ;; a count of whatever this particular request happened to return
         all  (mem/all-facts m)
         hits (cond
                (seq q) (mold/recall m q opts)
                ;; No query is a browse, and `all-facts` takes no filter, so the
                ;; scope is applied here by the same rule recall applies it by.
                ;; Returning the whole memory for `?space=…` with an empty `q`
                ;; would be a scoped request answered with unscoped facts.
                scope   (filterv #(mem/in-sources? (:sources scope) %) all)
                :else   all)]
     (cond-> {:facts     (mapv #(dissoc % :vec) hits)
              :total     (count all)
              :awaiting  (count (mem/pending-facts m))
              :embedding (when (embed/embedding-configured?) (embed/embed-model))
              :merge     (mem/merge-status)}
       ;; recall marks itself degraded when it asked the embedder something and
       ;; did not get an answer; saying so is cheaper than a pane that silently
       ;; shows fewer results than it did yesterday.
       (:degraded (meta hits)) (assoc :degraded (:degraded (meta hits)))
       ;; A scoped answer says it was scoped. Without this an empty `:facts` is
       ;; ambiguous between "nothing is remembered here" and "nothing is
       ;; remembered at all", and the reading that invites is a fallback to the
       ;; whole memory — the one thing a scope exists to prevent.
       scope (assoc :scope {:space   (:space scope)
                            :sources (count (:sources scope))
                            :empty   (empty? hits)})))))

(defn memory-request
  "/api/memory — the pane, optionally scoped to one notebook's lineage.

   `?space=` narrows recall to `lineage-sources`; absent or blank it is the
   whole memory, unchanged. A `space` that is not a notebook is an **error**,
   not an empty list: `?space=space:typo` answered with `{:facts []}` reads as
   “nothing was remembered here”, and someone would believe it."
  [st m q space]
  (if (str/blank? space)
    (memory-payload m q)
    (or (notebook-or-error st space)
        (memory-payload m q {:space space :sources (lineage-sources st space)}))))

(defn memory-unmerge!
  "POST /api/memory-unmerge — undo one merge.

   Request `{\"survivor\": \"mem-1\", \"absorbed\": \"mem-2\"}`; both are fact ids
   and both are required. Answers 200 with either

     {\"survivor\": {…fact…}, \"restored\": {…fact…}}
     {\"error\": \"…\"}

   the two records as they now stand, and **no `vec` on either** — for the reason
   `memory-payload` drops them, ~1024 floats per fact against a pane that shows
   text. The survivor's `merged-from` is what it is *after* the split, so a
   caller can re-render the `+N merged` list from this answer alone; `no-merge`
   is a set of ids, which JSON carries as an array.

   The two records rather than a whole pane payload, because the pane has a query
   and possibly a scope that this request does not carry, and answering a scoped
   pane with the unscoped memory is the one mistake a scope exists to prevent.
   The caller refetches `/api/memory` with whatever it was already showing —
   where `awaiting` will have gone up by one, since the restored fact has no
   vector until the next sweep.

   A refusal is `{:error \"…\"}` at HTTP 200, the shape every other not-allowed
   answer in this file uses. `mem/unmerge!` returns its refusals as values and
   this adds no way to throw: a missing id is a message, not a 500."
  [m survivor absorbed]
  (let [sid (str survivor)
        aid (str absorbed)]
    (cond
      (str/blank? sid) {:error "unmerge needs a survivor id"}
      (str/blank? aid) {:error "unmerge needs an absorbed id"}
      :else
      (let [r (mem/unmerge! m sid aid)]
        (if (:error r)
          r
          {:survivor (dissoc (:survivor r) :vec)
           :restored (dissoc (:restored r) :vec)})))))

(defn- durable-refusal
  "Why `id` cannot be judged, as a sentence a person can act on — or nil when it
   can.

   A sentence rather than a keyword, for `unmerge-refusal`'s reason: the only
   caller is an endpoint whose only reader is a person, and \"no such fact\" and
   \"that is a tombstone\" want two different next moves."
  [facts id]
  (let [f (get facts id)]
    (cond
      (nil? f)
      (str "there is no fact " id " to judge")

      ;; A merge appends a record carrying :merged-into rather than deleting a
      ;; line, and every reader filters those out through `live`. Stamping one
      ;; would write a judgement onto text nothing will ever rank, under an id
      ;; whose live fact is somewhere else.
      (:merged-into f)
      (str id " is not a fact any more — it was absorbed into " (:merged-into f)
           ", which is the fact that carries this text now. Judge that one.")

      :else nil)))

(defn memory-forget!
  "POST /api/memory-forget — remove a fact for good.

   Request `{\"id\": \"mem-42\"}`. Answers 200 with
   `{\"forgotten\": \"mem-42\", \"fact\": \"…\", \"also\": [\"mem-7\"]}` or
   `{\"error\": \"…\"}`, the shape every other refusal in this file uses.

   **`:fact` comes back deliberately.** It is the last time that text exists
   anywhere the caller can reach, and a confirmation that names what went is
   the difference between a gesture you can check and one you have to trust.
   `:also` is the merge group that went with it — see `mem/forget!` for why
   forgetting a survivor cannot leave its absorbed facts behind.

   **There is no agent route to this and there must not be one.** It is absent
   from `loci.tools/specs`, so no flow can reach it; the only caller is the
   shell, on a person's gesture. Everything else an agent does to the substrate
   is a reversible event behind a review gate, and this is not reversible."
  [m id]
  (if (str/blank? (str id))
    {:error "which fact? this needs an id"}
    (let [r (mem/forget! m id)]
      (if (:ok? r)
        (select-keys r [:forgotten :fact :also])
        {:error (:error r)}))))

(defn memory-durable!
  "POST /api/memory-durable — your judgement about whether a fact goes out of
   date.

   Request `{\"id\": \"mem-1\", \"durable\": true}`. Answers 200 with either the
   updated record, or `{\"error\": \"…\"}` — the shape every other not-allowed
   answer in this file uses, and `memory-unmerge!`'s exactly.

   **The record itself, flat, and not a pane payload.** This request carries no
   `q` and no `space`, and answering a scoped pane with the unscoped memory is
   the one mistake a scope exists to prevent; the caller refetches `/api/memory`
   with whatever it was already showing. Flat rather than under a key because
   there is one record and a fact never carries `:error`, so the two shapes are
   unambiguous — `memory-unmerge!` names its two because it has two to name. No
   `:vec` on the way out, for the reason `memory-payload` drops them.

   **It appends; it does not go through `mold/remember`.** `remember` would find
   this fact as its own jaccard ≥ 0.6 duplicate, bump `:strength` and reset
   `:ts` to now — and `:ts` is a multiplier in `weight`, so marking a fact *not*
   durable would hand it a fresh 1.0 recency multiplier in the same breath as
   recording that it ages. The 2026-08-09 repair documented this trap and
   `loci.classify` walked past it a week ago; this is the third writer and the
   same rule. `unmerge!` is the shape: take the atom and the log, swap, append
   the record the swap landed on.

   The write lives here rather than in `loci.memory` beside `unmerge!` because
   there is no memory logic in it — one key and its provenance, where `unmerge!`
   carries strength arithmetic, `:no-merge` and a four-way refusal. `loci.classify`
   reaches for `{:keys [!facts log]}` from outside the namespace for the same
   reason. The cost is real and is this: the set of things that write to the fact
   log stops being enumerable from `loci.memory` alone — and where `loci.classify`
   is a one-off that runs once and can be deleted, this one stays.

   **`:durable-by \"you\"`, and that is the point of the endpoint.** `remember`
   revises an agent's judgement and refuses to revise yours, so without the
   stamp the next distiller pass over the same sentence silently reverts what
   you did here — the failure `:no-merge` exists to prevent on the merge path,
   reintroduced on a new one.

   **`boolean?`, not merely present.** Ratified when the distiller and then
   `loci.classify` met the same question: `weight` reads the VALUE, and the
   string `\"false\"` is truthy in Clojure, so a hand-rolled POST carrying it
   would mark a fact durable, stamp it as yours, and thereby make the
   classification pass skip it forever. The pane always sends a boolean; this
   guard is about everything that is not the pane.

   Success is read off the record the swap produced rather than from
   `identical?` on the two atom values: marking a fact durable when it already
   is leaves the map unchanged (`assoc` returns the same map when the value is
   already there), and an idempotent toggle is an ordinary success, not \"the
   memory changed while this was being applied\"."
  [m id durable]
  (let [fid (str id)
        {:keys [!facts log]} m]
    (cond
      (str/blank? fid) {:error "a judgement needs the id of the fact it is about"}

      (not (boolean? durable))
      {:error (str "durable must be true or false, not " (pr-str durable)
                   " — a value that is neither is not a judgement, and this one would be "
                   "recorded as yours and never revised by anything")}

      :else
      (or (when-let [why (durable-refusal @!facts fid)] {:error why})
          (let [after (swap! !facts
                             (fn [fs]
                               ;; the same check again, inside the swap, on the
                               ;; map the write will actually land on — a merge
                               ;; may have absorbed this fact since the read above
                               (if (durable-refusal fs fid)
                                 fs
                                 (update fs fid assoc :durable? durable :durable-by "you"))))]
            (if-let [why (durable-refusal after fid)]
              {:error why}
              (let [rec (get after fid)]
                (mem/append-line! log rec)
                (dissoc rec :vec))))))))

;; ---- routing ----
(defn handler [{:keys [uri query-string] :as req}]
  (let [st (store)
        params (parse-query query-string)]
    (cond
      (= uri "/")            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                              :body (slurp (io/resource "public/index.html"))}
      (= uri "/api/state")   (json-resp (state-payload (store-at st (params "at"))))
      (= uri "/api/mold")    (json-resp (mold-payload (store-at st (params "at")) mem/memory
                                                     (params "id") (params "view")))
      (= uri "/api/events")  (json-resp (events-payload st))
      ;; `?deep=1` is the pause/Enter request, and the ONLY one that embeds. The
      ;; chunk singleton is forced here rather than in the params map so a
      ;; keystroke never resolves the data directory or reads chunks.edn.
      (= uri "/api/leap")    (json-resp (if (#{"1" "true" "yes"} (params "deep"))
                                          (leap-payload st @mem/memory (params "q")
                                                        {:deep? true :chunks @ch/chunks})
                                          (leap-payload st @mem/memory (params "q"))))
      (= uri "/api/undo")    (do (sub/undo! st) (json-resp (state-payload st)))
      (= uri "/api/edit")    (let [{:keys [id value]} (body-json req)] (json-resp (edit! st id value)))
      (= uri "/api/delegate")(let [{:keys [space]} (body-json req)] (json-resp (delegate! st space)))
      (= uri "/api/research")(let [{:keys [space prompt]} (body-json req)] (json-resp (research-start! st space prompt)))
      (= uri "/api/flow")     (let [{:keys [space goal]} (body-json req)] (json-resp (flow-start! st space goal)))
      (= uri "/api/flows")    (json-resp (flows-payload (store-at st (params "at"))))
      (= uri "/api/flow-gate")(let [{:keys [id approve]} (body-json req)] (json-resp (flow-gate! st id approve)))
      (= uri "/api/job")     (json-resp (job-status (params "id")))
      (= uri "/api/do")      (let [{:keys [id prompt space]} (body-json req)] (json-resp (do! st id prompt space)))
      (= uri "/api/functions")(json-resp (functions-list st))
      (= uri "/api/fns")       (json-resp (fns-payload st (params "id")))
      (= uri "/api/fn-preview")(let [{:keys [id fnid params]} (body-json req)]
                                 (json-resp (fn-preview st id fnid params)))
      (= uri "/api/fn-apply")  (let [{:keys [id fnid params space]} (body-json req)]
                                 (json-resp (fn-apply! st id fnid params space)))
      (= uri "/api/rerun")   (let [{:keys [id]} (body-json req)] (json-resp (rerun! st id)))
      (= uri "/api/new-space")(let [{:keys [prompt]} (body-json req)] (json-resp (new-space! st prompt)))
      (= uri "/api/connect")(let [{:keys [a b]} (body-json req)] (json-resp (connect! st a b)))
      (= uri "/api/ask")     (let [{:keys [prompt space]} (body-json req)] (json-resp (ask! st prompt space)))
      (= uri "/api/keep-note")(let [{:keys [space title text]} (body-json req)] (json-resp (keep-note! st space title text)))
      (= uri "/api/import-csv")(let [{:keys [title csv space]} (body-json req)] (json-resp (import-csv! st title csv space)))
      (= uri "/api/notebook")(if (= :post (:request-method req))
                               (json-resp (notebook-op! st (body-json req)))
                               (json-resp (notebook-payload (store-at st (params "at")) (params "id"))))
      (= uri "/api/tags")    (let [{:keys [space tags]} (body-json req)]
                               (json-resp (set-tags! st space tags)))
      (= uri "/api/tag-color")(let [{:keys [tag color]} (body-json req)]
                                (json-resp (set-tag-color! st tag color)))
      (= uri "/api/tag-suggest")(let [{:keys [space]} (body-json req)]
                                  (json-resp (if (space? st space)
                                               {:job (start-job! #(suggest-tags! st space))}
                                               {:error (str "not a notebook: " space)})))
      (= uri "/api/links")   (json-resp (nb/links (store-at st (params "at")) (params "space")))
      (= uri "/api/memory")  (json-resp (memory-request st @mem/memory (params "q") (params "space")))
      ;; `store-at`, like every other read route — and `mold-payload`'s entity
      ;; branch already reasons at length about why the source chips must be the
      ;; objects that existed at `?at=`. This route simply did not honour it, and
      ;; the asymmetry was reachable: the shell hands a payload's `:object`
      ;; straight to `/api/object/…?at=`, so under `?at=` the live store answering
      ;; "citizen" sent it to fetch an object the frozen store does not have.
      ;; Measured in a browser — an empty molder showing the word `null`, and far
      ;; enough back an uncaught TypeError and a blank world.
      (= uri "/api/entity")  (json-resp (entity-payload (store-at st (params "at"))
                                                        @mem/memory (params "name")))
      ;; `{nm :name}` and not `{:keys [name]}`: a local called `name` shadows
      ;; `clojure.core/name` for the rest of this branch, in a `cond` where the
      ;; branch above it is one edit away from needing it.
      (= uri "/api/entity-notebook")(let [{nm :name} (body-json req)]
                                      (json-resp (materialize-entity! st nm)))
      (= uri "/api/memory-unmerge")(let [{:keys [survivor absorbed]} (body-json req)]
                                     (json-resp (memory-unmerge! @mem/memory survivor absorbed)))
      ;; `durable` is passed through raw — not coerced, not defaulted. JSON's
      ;; true/false arrive as booleans and anything else is refused downstream by
      ;; name, which is the only way a caller ever learns it sent nonsense.
      (= uri "/api/memory-durable")(let [{:keys [id durable]} (body-json req)]
                                     (json-resp (memory-durable! @mem/memory id durable)))
      (= uri "/api/memory-forget")(let [{:keys [id]} (body-json req)]
                                    (json-resp (memory-forget! @mem/memory id)))
      (= uri "/api/suggest") (let [{:keys [space]} (body-json req)]
                               (json-resp (suggest-start! st space)))
      (= uri "/api/suggest-run")(let [{:keys [space items destination]} (body-json req)]
                                  (json-resp (suggest-run-start! st space items destination)))
      (str/starts-with? uri "/api/object/")
      (json-resp (mold-payload (store-at st (params "at")) mem/memory
                               (java.net.URLDecoder/decode (subs uri (count "/api/object/")) "UTF-8") nil))
      :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))

(defonce server (atom nil))

;; ---- the background chunk sweep ----
;;
;; **Why this thread lives here and not in loci.chunks or loci.memory.**
;;
;; It could have gone in three places, and two of them are wrong:
;;
;; · *extend `mem/start-embed-worker!`* — that worker would then need the
;;   substrate and the chunk store, and `loci.memory` would have to require
;;   `loci.substrate` and `loci.chunks` to schedule work it knows nothing about.
;;   Memory is a store of facts; making it the scheduler for a different store is
;;   an inversion that gets worse with the third thing that needs a tick.
;;
;; · *fold both passes into one thread here* — one timer, but `start-embed-worker!`
;;   also carries the rule that merge only follows a pass that WORKED, which is
;;   tested where it lives. Re-implementing it here to save a thread would copy a
;;   subtle rule into a second place for it to drift from.
;;
;; So: `loci.chunks` stays what its docstring says it is — a library whose
;; `sweep!` a server, a worker or a test calls — and the scheduling lives at the
;; one layer that already knows about both stores and already starts threads.
;; Both are started from `-main` under the SAME `embedding-configured?` guard, so
;; either both run or neither does and startup says which.
;;
;; Nothing starts on require; nothing here blocks a write (`sweep!` never touches
;; the write path, and a commit never waits on it); and a pass that fails leaves
;; its chunks pending for the next one.
(defn start-chunk-sweep!
  "Run `ch/sweep!` over `st`/`cs` every `:interval-ms` (default 15 s, matching
   the memory worker) on one daemon thread. Returns a handle for
   `stop-chunk-sweep!`.

   Daemon, because a sweep parked in a 30-second embedder request must not be
   what keeps the JVM alive. The `catch` is belt-and-braces — `sweep!` returns
   its failures as values and documents that it never throws — but a background
   thread that dies silently would stop embedding for the life of the process."
  ([st cs] (start-chunk-sweep! st cs {}))
  ([st cs {:keys [interval-ms] :or {interval-ms 15000}}]
   (let [wake (java.util.concurrent.ArrayBlockingQueue. 1)
         run? (atom true)
         t    (Thread. ^Runnable
                       (fn []
                         (while @run?
                           (try (ch/sweep! st cs)
                                (catch Throwable e
                                  (binding [*out* *err*]
                                    (println "loci.server: the chunk sweep threw:" e))))
                           (when @run?
                             (try (.poll wake interval-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                                  (catch InterruptedException _ nil)))))
                       "loci-chunk-sweep")]
     (.setDaemon t true)
     (.start t)
     {:thread t :wake wake :run? run?})))

(defn stop-chunk-sweep!
  "Stop a sweep started by `start-chunk-sweep!`. Returns true once its thread has
   died; false means it is still finishing a request, which can legitimately be a
   30-second timeout, not that it is stuck."
  [{:keys [^Thread thread ^java.util.concurrent.ArrayBlockingQueue wake run?]}]
  (reset! run? false)
  (.offer wake :stop)
  (.join thread 2000)
  (not (.isAlive thread)))

(defn- close-lake-at-exit!
  "Register the shutdown hook that closes `lk`, and return the thread.

   Returns it so the two things that matter here can be run by a test: the JVM
   will not run a hook on demand, and both behaviours live in the hook's body.

   Takes the delay rather than reading `lake/lake` for the same reason — a test
   passing its own delay proves the guard without opening the user's lake, and
   proving it is the only way to know the guard is a guard. `-main` registers
   this BEFORE it forces the lake, which is what leaves the `realized?` check
   with something to decide: registered and never forced, exit closes nothing.
   Registered after the force it would always be true and read as a check that
   is not one.

   The catch is not defensive noise. `realized?` does not mean \"opened\" — a
   delay whose body THREW is realized too, and every later deref rethrows the
   cached exception. Same shape `start-chunk-sweep!` puts around its background
   work.

   **A failed boot has nothing to close, and says nothing.** Which is why the
   deref is separated from the close and only the close is reported. With one
   `try` around both, a lake that failed to open printed
   `loci.server: closing the lake threw:` and a full stack trace of the *open*
   failure on the way out — after `-main` had already printed the real error,
   and therefore under it, burying the one line the user needed. The guard was
   added to keep exit quiet about a lake nobody opened; a lake that could not
   be opened is the same case and was getting the opposite treatment. A close
   that genuinely fails still reports, because that is news.

   What the close buys, measured 2026-08-16: DuckDB holds uncheckpointed
   commits in `catalog.duckdb.wal` and checkpoints them into the catalog on
   close, releasing the file lock. After a 100k-row write: `catalog.duckdb`
   12,288 B beside a 12,281 B WAL while open, 3,944,448 B and no WAL after.
   Replaying the WAL on open is DuckDB's own recovery path, so this is a
   checkpoint and a released lock, not data loss avoided — the substrate's LMDB
   env is left to process exit and loses nothing either."
  [lk]
  (let [t (Thread. ^Runnable
                   #(when-let [opened (when (realized? lk)
                                        ;; A delay whose body threw is realized
                                        ;; and rethrows here; that is a boot
                                        ;; failure already reported, not a
                                        ;; close failure, and there is nothing
                                        ;; open to close.
                                        (try @lk (catch Throwable _ nil)))]
                      (try (lake/close! opened)
                           (catch Throwable e
                             (binding [*out* *err*]
                               (println "loci.server: closing the lake threw:" e)))))
                   "loci-lake-close")]
    (.addShutdownHook (Runtime/getRuntime) t)
    t))

(defn -main [& _]
  ;; FIRST, before the store is touched — `(store)` below opens LMDB, and once
  ;; Datalevin has failed the only thing the user sees is a stack trace that
  ;; names neither the directory nor the uid. The check itself lives in
  ;; loci.substrate so it can be tested; all that belongs here is the exit.
  ;; It reports and refuses; it does not chown. See `data-dir-problem`.
  (when-let [problem (sub/data-dir-problem)]
    (binding [*out* *err*] (println problem))
    (System/exit 1))
  ;; Before the force, not after — see `close-lake-at-exit!`, which is where
  ;; the order is the difference between a guard and a line that always passes.
  (close-lake-at-exit! lake/lake)
  ;; Force the lake at boot rather than on first use. 75 ms, measured against
  ;; a lake holding 10M rows — 0.5% of the dependency load already paid — and
  ;; it turns "tables are broken" into a startup error naming the directory.
  ;; After `data-dir-problem`, for the same reason `(store)` is: the lake lives
  ;; under the data directory, and a permissions failure should be reported by
  ;; the check that names the uid rather than by DuckDB.
  @lake/lake
  ;; PORT because that is what every container runtime sets. Through
  ;; `loci.config` like everything else, so that a PORT in loci.env is honoured
  ;; rather than silently ignored by a local run while compose obeys it — the
  ;; advice not to put it there (see loci.env.example) is about the container,
  ;; where the published mapping would not move with the listener. The data
  ;; directory is printed because a packaged loci defaults to a RELATIVE "data" —
  ;; launched from the wrong place it silently starts an empty substrate instead
  ;; of yours, and a line of output is the difference between noticing and not.
  (let [port (or (some-> (config/env "PORT") Integer/parseInt) 7777)
        dir  (sub/data-dir)]
    (reset! server (http/run-server #'handler {:port port}))
    (println (str "loci shell on http://localhost:" port
                  "  (substrate: " dir ", " (count (sub/history (store))) " events)"))
    ;; The embedding backfill, started HERE and nowhere else. loci.memory spawns
    ;; nothing on require, so a test or a REPL that loads it leaves no thread
    ;; behind; a server process is the one place that wants a timer.
    ;;
    ;; With no embedder configured there is nothing to start and nothing to
    ;; apologise for — recall is lexical, which is what it was before any of this
    ;; existed — so it says so instead of starting a thread that would wake every
    ;; fifteen seconds to discover it has no endpoint. The endpoint itself is not
    ;; printed: it is a URL a user set, and a URL can carry credentials.
    (if (embed/embedding-configured?)
      (do (mem/start-embed-worker!
           @mem/memory
           ;; So the startup line's "N fact(s) awaiting embedding" is a
           ;; beginning rather than the last thing you ever hear about it.
           ;; Prints only when a pass embedded something, and once when the
           ;; backlog drains — a quiet server stays quiet.
           {:report (fn [{:keys [embedded pending error]}]
                      (println (str "  embedded " embedded " fact(s), "
                                    (if (zero? (long (or pending 0)))
                                      "all facts now have vectors"
                                      (str pending " still awaiting"))
                                    (when error (str " — " error)))))})
          (start-chunk-sweep! (store) @ch/chunks)
          (println (str "  semantic recall: " (embed/embed-model) ", "
                        (count (mem/pending-facts @mem/memory)) " fact(s) awaiting embedding"))
          ;; Counted against the substrate, so a first run — or a deleted
          ;; chunks.edn — honestly says "170 awaiting" instead of nothing at all.
          (println (str "  LEAP by meaning: "
                        (count (ch/pending (store) @ch/chunks)) " chunk(s) awaiting embedding")))
      (println "  semantic recall: off (no LOCI_EMBED_ENDPOINT) — recall and LEAP are lexical"))
    @(promise)))
