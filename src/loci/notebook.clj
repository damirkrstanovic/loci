(ns loci.notebook
  "Notebook = space. A space's :value carries ordered :cells — prose
   ({:text s}) interleaved with object references ({:ref id, :view v?}).
   Data is never copied into a cell; re-molding a cell is changing its :view.

   Legacy {:members [id …]} normalizes to ref cells on read; all writes write
   :cells. Every mutation is ONE substrate event, hence undoable — appending is
   an `:append`, whose effect the substrate computes at apply time, while the
   edit/move/delete path still carries the full new vector (`set-cells-event`).

   Links between notebooks are COMPUTED here, never stored: shared refs,
   spawn edges (:spawned-by), and derivation lineage (:from/:via)."
  (:require [clojure.set :as set]
            [loci.substrate :as sub]))

(defn cells-of [obj]
  (let [v (:value obj)]
    (vec (or (:cells v) (map (fn [id] {:ref id}) (:members v))))))

(defn refs-of [obj] (set (keep :ref (cells-of obj))))

(defn notebooks [st] (->> (sub/objects st) vals (filter #(= :space (:kind %)))))

(defn cell-op
  "Pure: apply one UI cell operation to a cells vector.
   op ∈ add-text | add-ref | edit-text | set-view | remove | move.
   Total: a bad or stale idx (or a type mismatch) returns cells unchanged —
   never corrupt the undo log with a half-applied edit."
  [cells {:keys [op idx text ref view to]}]
  (let [cells (vec cells)
        at?   (fn [pred] (and (int? idx) (< -1 idx (count cells))
                              (pred (cells idx))))]
    (case op
      "add-text"  (conj cells {:text (or text "")})
      "add-ref"   (conj cells (cond-> {:ref ref} (seq view) (assoc :view view)))
      "edit-text" (if (at? :text) (assoc cells idx {:text (or text "")}) cells)
      "set-view"  (if (at? :ref)
                    (update cells idx #(if (seq view) (assoc % :view view) (dissoc % :view)))
                    cells)
      "remove"    (if (at? any?) (vec (concat (subvec cells 0 idx) (subvec cells (inc idx)))) cells)
      "move"      (if (and (at? any?) (int? to))
                    (let [c  (cells idx)
                          w  (vec (concat (subvec cells 0 idx) (subvec cells (inc idx))))
                          to (max 0 (min (count w) to))]
                      (vec (concat (subvec w 0 to) [c] (subvec w to))))
                    cells)
      cells)))

(defn set-cells-event [space-id cells]
  {:op :assoc :id space-id :path [:value :cells] :value (vec cells)})

(defn append-cell-event
  "Event appending one cell to a notebook. The new cell vector is computed when
   the event is APPLIED, not here, so two concurrent appends cannot overwrite
   each other — this used to emit the whole new vector, and 24 concurrent
   appends left 6 cells. :seed carries the normalized legacy :members and is
   read only if :cells is still absent when the event lands."
  [st space-id cell]
  (let [o (sub/object st space-id)]
    (cond-> {:op :append :id space-id :path [:value :cells] :value cell}
      (nil? (get-in o [:value :cells])) (assoc :seed (cells-of o)))))

(defn links
  "Connectedness for one notebook: {:connected [{:id :title :reasons […]}]
   :also-in {obj-id [{:id :title}]}}. reason :type ∈ shares|spawned|spawned-by|derived."
  [st space-id]
  (let [objs    (sub/objects st)
        nb      (objs space-id)
        mine    (refs-of nb)
        others  (filter #(and (= :space (:kind %)) (not= (:id %) space-id)) (vals objs))
        srcs    (fn [refs] (set (keep #(:from (objs %)) refs)))
        my-srcs (srcs mine)
        connected
        (vec (keep
              (fn [o]
                (let [theirs  (refs-of o)
                      shares  (set/intersection mine theirs)
                      lineage (set/union
                               (set/difference (set/intersection my-srcs theirs) shares)
                               (set/difference (set/intersection (srcs theirs) mine) shares))
                      reasons (-> []
                                  (cond-> (= (get-in nb [:value :spawned-by :space]) (:id o))
                                    (conj {:type "spawned-by"}))
                                  (cond-> (= (get-in o [:value :spawned-by :space]) space-id)
                                    (conj {:type "spawned"}))
                                  (cond-> (some #{(:id o)} (get-in nb [:value :merged-from]))
                                    (conj {:type "merged-from"}))
                                  (cond-> (some #{space-id} (get-in o [:value :merged-from]))
                                    (conj {:type "merged"}))
                                  (into (map (fn [r] {:type "shares" :obj r}) (sort shares)))
                                  (into (map (fn [s] {:type "derived" :obj s}) (sort lineage))))]
                  (when (seq reasons) {:id (:id o) :title (:title o) :reasons reasons})))
              others))
        also-in
        (into {} (keep (fn [r]
                         (let [in (vec (keep #(when ((refs-of %) r) {:id (:id %) :title (:title %)})
                                             others))]
                           (when (seq in) [r in])))
                       mine))]
    {:connected connected :also-in also-in}))
