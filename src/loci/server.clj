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
            [loci.content :as c]
            [loci.embed :as embed]
            [loci.fnlib :as fnlib]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tools :as tools]
            [loci.viewspec :as vs])
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
     :tag-colors (or (:value (get objm palette-id)) {})}))

(defn dynamic-views [st target]
  (->> (sub/objects st) vals
       (filter #(and (#{:viewspec :applet} (:kind %)) (= target (get-in % [:value :target]))))))

(defn- alternatives [st o]
  (vec (concat (mapv (fn [[vid lbl]] [(kw->str vid) lbl]) (:alternatives (mold/mold (:value o))))
               (mapv (fn [v] [(:id v) (get-in v [:value :label])]) (dynamic-views st (:id o))))))

(defn mold-payload [st id view]
  (let [o (sub/object st id)]
    (cond
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
      ;; a built-in viewer
      :else
      (let [m (mold/mold (:value o) (when (seq view) (str->kw view)))]
        {:id id :title (:title o) :kind (name (:kind m)) :view (when (:view m) (kw->str (:view m)))
         :label (:label m) :rendered (:rendered m) :alternatives (alternatives st o)}))))

(defn leap-payload
  "ONE incremental search across everything: objects, notebook prose, doc
   bodies, memory facts, view verbs. Content groups only appear once there's
   a query; each group is capped so it stays incremental-fast."
  [st mem q]
  (let [q    (str/lower-case (or q ""))
        hit? (fn [& ss] (str/includes? (str/lower-case (str/join " " (map str ss))) q))
        ell  (fn [s n] (let [s (str/replace (str s) "\n" " ")]
                         (if (> (count s) n) (str (subs s 0 n) "…") s)))
        snip (fn [text] (let [i (or (str/index-of (str/lower-case text) q) 0)]
                          (ell (subs text (max 0 (- i 20))) 70)))
        touched (last-touched st)
        ;; recency BEFORE the cap: taking "the first 8 encountered" dropped
        ;; results by whatever order the objects happened to enumerate in
        recent  (fn [e] (or (:touched e) 0))
        cap  (fn [xs] (->> xs (map #(assoc % :touched (touched (:id %) 0)))
                           (sort-by recent >) (take 8) vec))
        objs  (->> (sub/objects st) vals
                   (remove #(#{:viewspec :applet :fn :palette} (:kind %)))
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
                 (for [o (vals (sub/objects st))
                       :when (and (= :doc (:kind o)) (string? (:value o))
                                  (hit? (:value o)) (not (hit? (:title o) (:id o))))]
                   {:id (:id o) :label (str (:title o) " · …" (snip (:value o))) :group "in text"}))
        made  (when (seq q)
                (for [o (vals (sub/objects st))
                      :when (and (#{:applet :viewspec :fn} (:kind o))
                                 (hit? (:title o) (:id o) (get-in o [:value :label])))]
                  {:id (:id o)
                   :label (str (or (get-in o [:value :label]) (:title o))
                               " · on " (or (get-in o [:value :target]) (get-in o [:value :source]) "?"))
                   :group "views & functions"
                   :target (or (get-in o [:value :target]) (get-in o [:value :source]))}))
        mems (when (seq q)
               (map (fn [f] {:id "__memory__" :label (:fact f) :group "memory"})
                    (mold/recall mem q {:k 8})))]
    (vec (concat (cap objs) (cap prose) (cap intext) (cap made) (cap mems) verbs))))

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
                               (mold-payload st (:ref c) (:view c)))
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
(defn edit! [st id value]
  (if-not (and id (sub/object st id))
    {:error (str "no such object: " id)}
    (do (sub/commit! st {:op :assoc :id id :path [:value] :value value})
        {:state (state-payload st) :object (mold-payload st id nil)})))

;; (1) molding-by-description — the agent emits a view-spec stored as a
;; reversible object; it then appears in the object's "view this as…" menu.
(defn describe! [st id prompt]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "describe works on tables"}
        (let [spec (agent/describe-view (mapv name (keys (first rows))) (take 2 rows) prompt)
              n (count (filter #(= :viewspec (:kind %)) (vals (sub/objects st))))
              vid (str "view:" (subs id (inc (str/index-of id ":"))) "-" (inc n))
              vobj {:id vid :kind :viewspec :title (str "view: " (:label spec))
                    :value {:target id :label (or (:label spec) prompt) :spec spec}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st id vid) :events (count (sub/history st))))))
    (catch Exception e {:error (.getMessage e)})))

;; (1b) molding-by-CODE — the agent writes a self-contained JS applet that runs
;; over the object's rows. Stored as a reversible :applet object; appears in the
;; "view this as…" menu and executes in the shell. ponytail: code runs in the
;; page (new Function) — fine for a local single-user prototype; sandbox/iframe
;; if this ever serves untrusted users.
(defn make-view! [st id prompt]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "code views work on tables"}
        (let [code (agent/make-applet (mapv name (keys (first rows))) (take 3 rows) prompt)
              n (count (filter #(= :applet (:kind %)) (vals (sub/objects st))))
              p (str/trim prompt)
              vid (str "app:" (subs id (inc (str/index-of id ":"))) "-" (inc n))
              vobj {:id vid :kind :applet :title (str "app: " p)
                    :value {:target id :code code
                            :label (str "▶ " (if (> (count p) 26) (str (subs p 0 26) "…") p))}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st id vid) :events (count (sub/history st))))))
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
    (let [o (sub/object st id) rows (:value o)]
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
            (let [nf (count (filter #(= :fn (:kind %)) (vals (sub/objects st))))
                  nt (count (filter #(= :table (:kind %)) (vals (sub/objects st))))
                  p  (str/trim prompt)
                  fid (str "fn:" (subs id (inc (str/index-of id ":"))) "-" (inc nf))
                  nid (str "tbl:derived-" (inc nt))
                  ttl (if (> (count p) 48) (str (subs p 0 48) "…") p)
                  fobj {:id fid :kind :fn :title (str "fn: " p)
                        :value {:source id :prompt p :lang "clojure" :code code}}
                  tobj {:id nid :kind :table :title ttl :value out :from id :via fid}
                  evs (cond-> [{:op :put :id fid :value fobj}
                               {:op :put :id nid :value tobj}]
                        (and space (space? st space))
                        (conj (nb/append-cell-event st space {:ref nid})))]
              (sub/commit! st {:op :tx :events evs})
              {:state (state-payload st) :openId nid :object (mold-payload st nid nil)})))))
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
(declare next-id)

(defn- table-rows [st id]
  (let [rows (:value (sub/object st id))]
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
                  tobj {:id nid :kind :table :title (str (:title src) " · " lbl)
                        :value (:rows r) :from id :via fid :params (or params {})}
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
        rows   (or (get working src-id) (:value (sub/object st src-id)))
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
   one :tx. Returns {:state :refreshed [ids] :skipped [{:id :why}]}."
  [st id]
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
        (when (seq refreshed)
          (sub/commit! st {:op :tx :events (mapv (fn [cid] {:op :assoc :id cid :path [:value]
                                                            :value (get working cid)})
                                                 refreshed)}))
        {:state (state-payload st) :refreshed refreshed :skipped skipped}))))

;; ONE verb over a table: the user says what they want, the agent picks the
;; simplest mechanism (declarative view-spec / browser applet / server-side
;; Clojure transform) and routes to it. No technique buttons — loci decides.
(defn do! [st id prompt space]
  (try
    (let [o (sub/object st id) rows (:value o)]
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
          n (count (filter #(= :space (:kind %)) (vals (sub/objects st))))
          sid (str "space:new-" (inc n))]
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

;; ---- the recall seam, used by every agent flow ----
;; remembered-context injects what the agent already learned (cited);
;; distill! writes new memory AFTER a flow — async, best-effort, never undone.
;; :semantic? true here and NOT in leap-payload. This runs once per agent flow,
;; in front of an LLM call that takes seconds — a 20–50 ms query embedding buys
;; the agent facts it phrased differently the first time. /api/leap runs on every
;; keystroke and pays that same 20–50 ms for every character typed, which is why
;; recall's default is lexical and the two callers differ.
(defn- remembered-context [prompt]
  (let [facts (try (mold/recall @mem/memory prompt {:k 6 :semantic? true})
                   (catch Exception _ nil))]
    (when (seq facts)
      (str "\n\nREMEMBERED (distilled from earlier work — cite as ⌾ id when it shapes your answer):\n"
           (str/join "\n" (map #(str "- " (:fact %) " (⌾ "
                                     (or (get-in % [:source :obj]) (get-in % [:source :space]) "memory") ")")
                               facts))))))

(defn- distill! [prompt text obj-id space]
  (future
    (try
      (doseq [{:keys [fact entities]} (agent/distill-facts prompt text)]
        (mold/remember @mem/memory fact {:entities (mapv str entities)
                                         :source {:obj obj-id :space space}}))
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
                          (map obj-digest) (str/join "\n"))]
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
          objs    (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs)
          texty   (remove #(and (sequential? (:value %)) (seq (:value %)) (every? map? (:value %))
                                (not (every? :block (:value %)))) objs) ; docs + reports (not tables)
          tbls    (filter #(= :table (:kind %)) objs)
          allowed (when space (set (map :id tbls)))
          catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                   " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                   "; " (count (:value o)) " rows)")) tbls))
          sys     (str "Answer the user's question about their workspace. Cite the object ids you used. "
                       "Do NOT guess any table figure — call query_table for exact numbers. "
                       "If the data doesn't say, say so plainly. Be concise, markdown.\n\n"
                       "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                       "\n\nTABLES (query these by id):\n" catalog
                       (remembered-context prompt))
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
          obj {:id nid :kind :table :title (or (not-empty title) "Imported CSV") :value rows}
          base [{:op :put :id nid :value obj}]
          evs (if (and space (space? st space))
                (conj base (nb/append-cell-event st space {:ref nid}))
                base)]
      (sub/commit! st {:op :tx :events evs})
      {:state (state-payload st) :openId nid})
    (catch Exception e {:error (.getMessage e)})))

(defn- next-id
  "Mint '<prefix>N' with N = 1 + the highest existing numeric suffix across the
   WHOLE store. Counting a notebook's refs collides after a cell remove — a
   removed ref lowers the count while the object lives on."
  [st prefix]
  (let [n (->> (keys (sub/objects st))
               (keep #(when (str/starts-with? % prefix)
                        (try (Long/parseLong (subs % (count prefix)))
                             (catch Exception _ nil))))
               (reduce max 0))]
    (str prefix (inc n))))

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
                     (remove #(#{:space :viewspec :applet :fn} (:kind %))))
        texty   (remove #(= :table (:kind %)) members)
        tbls    (filter #(= :table (:kind %)) members)
        allowed (set (map :id tbls))
        catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                 " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                 "; " (count (:value o)) " rows)")) tbls))
        sys (str "You are a concise analyst. Draft a short markdown brief (≤150 words, with a heading) "
                 "for this workspace space. Use query_table for any exact figure — never guess. Cite object ids.\n\n"
                 "Space: " (:title sp) ". Intent: " (get-in sp [:value :intent]) ".\n\n"
                 "DOCS:\n" (str/join "\n\n" (map obj-digest texty)) "\n\nTABLES (query by id):\n" catalog
                 (remembered-context (str (:title sp) " " (get-in sp [:value :intent]))))
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
        objs (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs)
        texty (remove #(= :table (:kind %)) objs)
        tbls  (filter #(= :table (:kind %)) objs)
        allowed (when space (set (map :id tbls)))
        catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                 " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                 "; " (count (:value o)) " rows)")) tbls))]
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
                   (remembered-context prompt))
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
              fid (next-id st (str "find:" (subs space (inc (str/index-of space ":"))) "-"))
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
                               (map obj-digest) (str/join "\n"))
                          (remembered-context (str (:title sp) " " (get-in sp [:value :intent]))))]
          {:proposals (vec (take 3 (agent/propose-subtopics
                                    (:title sp) (get-in sp [:value :intent]) digest)))})))
    (catch Exception e {:error (.getMessage e)})))

(defn- fresh-dd-id
  "One past the highest space:dd-N, and never one that already exists. The old
   form derived the id from the TOTAL notebook count, which had nothing to do
   with the dd sequence; two notebooks in the store mint \"space:dd-3\", so the
   day anything deletes a notebook that :put would overwrite a live notebook in
   silence. The existence loop terminates: n only rises, and the store holds
   finitely many objects."
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

(defn- table-shaped? [st id]
  (let [rows (:value (sub/object st id))]
    (boolean (and (sequential? rows) (seq rows) (every? map? rows)))))

(defn resolve-table-ref
  "Like resolve-ref, but for compute steps: a $-ref prefers the nearest
   earlier output that is actually a TABLE (research can land prose-only).
   No tabular output anywhere → the plain walk result, so the failure
   downstream stays honest. Literal ids are never second-guessed."
  [st flow v]
  (if (and (string? v) (str/starts-with? v "$"))
    (let [r (resolve-ref flow v)]
      (if (and (string? r) (table-shaped? st r))
        r
        (or (->> (:steps flow) (keep :out) reverse (filter #(table-shaped? st %)) first)
            r)))
    v))

(defn flow-create!
  "Commit the flow object + its notebook cell as ONE :tx. Steps must already
   be validated. Returns the flow id."
  [st space goal steps]
  (let [fid (next-id st "flow:")
        fobj {:id fid :kind :flow :title (str "Flow — " (if (> (count goal) 40) (str (subs goal 0 40) "…") goal))
              :value {:goal goal :space space :status "running" :steps (vec steps)}}]
    (sub/commit! st {:op :tx :events [{:op :put :id fid :value fobj}
                                      (nb/append-cell-event st space {:ref fid})]})
    fid))

(defn- flow-assoc! [st fid value]
  (sub/commit! st {:op :assoc :id fid :path [:value] :value value}))

(defn- flow-step!
  "One transition: update step i (and optionally the flow status) — one event."
  [st fid i patch & [flow-status]]
  (let [fl (:value (sub/object st fid))
        fl (cond-> (update-in fl [:steps i] merge patch)
             flow-status (assoc :status flow-status))]
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
        (do (when (not= "done" (:status fl)) (flow-assoc! st fid (assoc fl :status "done")))
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

(defn lineage-sources
  "Every id a recall scoped to `space` is allowed to see: the notebook itself,
   the ids of its cells, and the same for each notebook it spawned and each
   notebook merged into it — transitively.

   `recall`'s filter takes a set of ids rather than one notebook id because
   `loci.memory` holds no store and must not: the half that can walk the
   substrate does the walk, the half that recalls takes the answer. This
   function is that walk, and it lives here for the same reason.

   Both the notebook and its cells are in the set because a distilled fact
   records `:source {:obj <the doc it came from> :space <the notebook>}` — the
   notebook id alone already matches everything `distill!` writes, and the cell
   ids additionally catch a fact whose source names only an object.

   Of the reasons `nb/links` reports, only `spawned` and `merged-from` are
   lineage:

     spawned      o's :spawned-by names this notebook — a child. In scope.
     merged-from  o's id is in this notebook's :merged-from — folded in here.
                  In scope: its facts were recorded before the merge existed
                  and there is nowhere else for them to live.
     spawned-by   the parent. NOT in scope — walking up reaches every sibling
                  through the shared parent, which is the leak a scope exists
                  to stop.
     merged       a notebook THIS one was folded into. Also upward, also out.
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
   the walk, so a cycle in the merge graph terminates rather than hanging.

   Cost: one `nb/links` per notebook in scope, and `nb/links` scans every object
   — so this is O(notebooks in scope × objects), not one 0.200 ms call. Measured
   on a synthetic 240-object store: 0.6 ms for a leaf, 5.4 ms for a ten-deep
   chain. Kept anyway, because reusing `nb/links` means “spawned” and
   “merged-from” have exactly one definition and the scope follows it, and
   because this runs on a search the user typed and waits on — the same request
   spends 20–50 ms embedding the query.

   Assumes `space` is a notebook; callers check first (`notebook-or-error`)."
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

   `scope` is nil or `{:space id :sources #{id …}}` from `lineage-sources`. Nil
   is the whole memory and the response is exactly what it has always been —
   same keys, same `recall` opts — because every caller that exists today passes
   no scope and none of them asked for a new field."
  ([m q] (memory-payload m q nil))
  ([m q scope]
   (let [opts (cond-> {:k 20 :semantic? true}
                scope (assoc :filter {:sources (:sources scope)}))
         hits (cond
                (seq q) (mold/recall m q opts)
                ;; No query is a browse, and `all-facts` takes no filter, so the
                ;; scope is applied here by the same rule recall applies it by.
                ;; Returning the whole memory for `?space=…` with an empty `q`
                ;; would be a scoped request answered with unscoped facts.
                scope   (filterv #(mem/in-sources? (:sources scope) %) (mem/all-facts m))
                :else   (mem/all-facts m))]
     (cond-> {:facts     (mapv #(dissoc % :vec) hits)
              :awaiting  (count (mem/pending-facts m))
              :embedding (when (embed/embedding-configured?) (embed/embed-model))}
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

;; ---- routing ----
(defn handler [{:keys [uri query-string] :as req}]
  (let [st (store)
        params (parse-query query-string)]
    (cond
      (= uri "/")            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                              :body (slurp (io/resource "public/index.html"))}
      (= uri "/api/state")   (json-resp (state-payload (store-at st (params "at"))))
      (= uri "/api/mold")    (json-resp (mold-payload (store-at st (params "at")) (params "id") (params "view")))
      (= uri "/api/events")  (json-resp (events-payload st))
      (= uri "/api/leap")    (json-resp (leap-payload st @mem/memory (params "q")))
      (= uri "/api/undo")    (do (sub/undo! st) (json-resp (state-payload st)))
      (= uri "/api/edit")    (let [{:keys [id value]} (body-json req)] (json-resp (edit! st id value)))
      (= uri "/api/delegate")(let [{:keys [space]} (body-json req)] (json-resp (delegate! st space)))
      (= uri "/api/research")(let [{:keys [space prompt]} (body-json req)] (json-resp (research-start! st space prompt)))
      (= uri "/api/flow")     (let [{:keys [space goal]} (body-json req)] (json-resp (flow-start! st space goal)))
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
      (= uri "/api/suggest") (let [{:keys [space]} (body-json req)]
                               (json-resp (suggest-start! st space)))
      (= uri "/api/suggest-run")(let [{:keys [space items destination]} (body-json req)]
                                  (json-resp (suggest-run-start! st space items destination)))
      (str/starts-with? uri "/api/object/")
      (json-resp (mold-payload (store-at st (params "at"))
                               (java.net.URLDecoder/decode (subs uri (count "/api/object/")) "UTF-8") nil))
      :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))

(defonce server (atom nil))

(defn -main [& _]
  ;; PORT because that is what every container runtime sets. The data directory is
  ;; printed because a packaged loci defaults to a RELATIVE "data" — launched from
  ;; the wrong place it silently starts an empty substrate instead of yours, and a
  ;; line of output is the difference between noticing and not.
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 7777)
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
      (do (mem/start-embed-worker! @mem/memory)
          (println (str "  semantic recall: " (embed/embed-model) ", "
                        (count (mem/pending-facts @mem/memory)) " fact(s) awaiting embedding")))
      (println "  semantic recall: off (no LOCI_EMBED_ENDPOINT) — recall is lexical"))
    @(promise)))
