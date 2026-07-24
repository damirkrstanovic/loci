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
            [loci.fnlib :as fnlib]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tools :as tools]
            [loci.viewspec :as vs]))

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
    :assoc  (str "✎ " (title-of objs id))
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

;; ---- payloads ----
(defn state-payload [st]
  (let [objs (vals (sub/objects st))]
    {:events  (count (sub/history st))
     :spaces  (->> objs (filter #(= :space (:kind %)))
                   (map (fn [s] (cond-> {:id (:id s) :title (:title s)
                                         :intent (get-in s [:value :intent])
                                         :members (vec (keep :ref (nb/cells-of s)))}
                                  (get-in s [:value :spawned-by :space])
                                  (assoc :spawned-by (get-in s [:value :spawned-by :space]))
                                  (get-in s [:value :merged-from])
                                  (assoc :merged-from (get-in s [:value :merged-from])))))
                   vec)
     :objects (->> objs (remove #(#{:space :viewspec :applet :fn} (:kind %)))
                   (map (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))}))
                   vec)}))

(defn dynamic-views [st target]
  (->> (sub/objects st) vals
       (filter #(and (#{:viewspec :applet} (:kind %)) (= target (get-in % [:value :target]))))))

(defn- alternatives [st o]
  (vec (concat (mapv (fn [[vid lbl]] [(kw->str vid) lbl]) (:alternatives (mold/mold (:value o))))
               (mapv (fn [v] [(:id v) (get-in v [:value :label])]) (dynamic-views st (:id o))))))

(defn mold-payload [st id view]
  (let [o (sub/object st id)]
    (cond
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
        cap  (fn [xs] (vec (take 8 xs)))
        objs  (->> (sub/objects st) vals
                   (remove #(#{:viewspec :applet :fn} (:kind %)))
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

(defn notebook-op! [st {:keys [space] :as body}]
  (let [o (sub/object st space)]
    (cond
      (not (= :space (:kind o)))
      {:error (str "not a notebook: " space)}
      :else
      (let [cells  (nb/cells-of o)
            cells' (nb/cell-op cells body)]
        ;; unknown op / stale idx fall through unchanged — don't commit a phantom event
        (when (not= cells cells')
          (sub/commit! st (nb/set-cells-event space cells')))
        {:state (state-payload st) :notebook (notebook-payload st space)}))))

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
        (let [code (agent/make-clj-transform (mapv name (keys (first rows))) (take 3 rows) prompt)
              r    (run-clj-rows code rows)
              out  (:rows r)]
          (if (:error r)
            r
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
(defn- remembered-context [prompt]
  (let [facts (try (mold/recall @mem/memory prompt {:k 6}) (catch Exception _ nil))]
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
          text (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                 tools/specs tf)
          tid (first @saved)
          sp (sub/object st space)
          fid (next-id st (str "find:" (subs space (inc (str/index-of space ":"))) "-"))
          p (str/trim prompt)
          title (str "Findings — " (if (> (count p) 44) (str (subs p 0 44) "…") p))]
      (sub/commit! st {:op :tx :events [{:op :put :id fid :value {:id fid :kind :doc :title title :value text}}
                                        (nb/append-cell-event st space {:ref fid})]})
      (distill! prompt text fid space)
      ;; when extraction produced a real table, THAT is the artifact — open it,
      ;; not the prose note. ponytail: open the data, keep the note as context.
      {:state (state-payload st) :openId (or tid fid)})
    (catch Exception e {:error (.getMessage e)})))

;; deep-dive: the agent proposes subtopics (grounded in the hub's findings AND
;; recalled memory), each spawned as a connected notebook and researched.
(defn deep-dive! [st space]
  (try
    (let [sp (sub/object st space)]
      (if-not (= :space (:kind sp))
        {:error (str "not a notebook: " space)}
        (let [digest (str (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
                               (map obj-digest) (str/join "\n"))
                          (remembered-context (str (:title sp) " " (get-in sp [:value :intent]))))
              subs*  (take 3 (agent/propose-subtopics (:title sp) (get-in sp [:value :intent]) digest))
              spawned
              (vec (for [{:keys [title intent query]} subs*]
                     (let [n   (count (nb/notebooks st))
                           sid (str "space:dd-" (inc n))]
                       (sub/commit! st {:op :put :id sid
                                        :value {:id sid :kind :space :title title
                                                :value {:intent intent :cells []
                                                        :spawned-by {:space space :prompt query}}}})
                       (research! st sid query)
                       sid)))]
          {:state (state-payload st) :spawned spawned})))
    (catch Exception e {:error (.getMessage e)})))

;; ---- jobs: long agent flows (research, deep-dive) run off-request so the
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

(defn deep-dive-start! [st space]
  (or (notebook-or-error st space)
      {:job (start-job! #(deep-dive! st space))}))

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
                         {:verb v :args (or (:args s) {}) :note (str (or (:note s) ""))
                          :status "pending"}))))
       (take 6) vec))

(defn resolve-ref
  "\"$N\" in step args means step N's output object id."
  [flow v]
  (if (and (string? v) (str/starts-with? v "$"))
    (or (get-in flow [:steps (or (parse-long (subs v 1)) -1) :out]) v)
    v))

(defn research-start! [st space prompt]
  (or (notebook-or-error st space)
      (when (str/blank? prompt) {:error "empty prompt"})
      {:job (start-job! #(research! st space prompt))}))

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
      (= uri "/api/links")   (json-resp (nb/links (store-at st (params "at")) (params "space")))
      (= uri "/api/memory")  (json-resp {:facts (let [qq (params "q")]
                                                  (if (seq qq)
                                                    (mold/recall @mem/memory qq {:k 20})
                                                    (mem/all-facts @mem/memory)))})
      (= uri "/api/deep-dive")(if (= :post (:request-method req))
                                (json-resp (deep-dive-start! st (:space (body-json req))))
                                {:status 405 :headers {"Content-Type" "text/plain"} :body "POST only"})
      (str/starts-with? uri "/api/object/")
      (json-resp (mold-payload (store-at st (params "at"))
                               (java.net.URLDecoder/decode (subs uri (count "/api/object/")) "UTF-8") nil))
      :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))

(defonce server (atom nil))

(defn -main [& _]
  (let [port 7777]
    (reset! server (http/run-server #'handler {:port port}))
    (println (str "loci shell on http://localhost:" port "  (substrate: "
                  (count (sub/history (store))) " events)"))
    @(promise)))
