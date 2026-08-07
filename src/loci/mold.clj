(ns loci.mold
  "The moldable view spine.

   One conviction, borrowed from Glamorous Toolkit: *there is no single
   representation of anything.* So content is stored once (canonically) and
   **molded** into whatever representation answers the question you're holding.

   A viewer is just data: {:id :label :pred :render}. The registry is data too.
   Both the user (\"view this as a chart\") and an agent (mints a new viewer)
   register through `register!` — in the real substrate each call lands as a
   reversible event, so molding is always undoable.

   This namespace is deliberately free of any UI/Clerk dependency: a view's
   `:render` returns plain Clojure data, and the render target (Clerk, Portal,
   a terminal, anything) decides how to draw it."
  (:require [clojure.string :as str]))

;; ----------------------------------------------------------------------------
;; the registry — ordered; earlier = higher precedence (the confident default)
;; ----------------------------------------------------------------------------

(defonce ^{:doc "vector of viewer maps"} registry (atom []))

(defn register!
  "Add (or replace, by :id) a viewer. Returns the viewer's :id.
   viewer = {:id keyword, :label string, :pred (fn [obj] -> truthy),
             :render (fn [obj] -> data)}."
  [{:keys [id pred render] :as viewer}]
  (assert (and id pred render) "a viewer needs :id, :pred and :render")
  (swap! registry (fn [r] (conj (filterv #(not= (:id %) id) r) viewer)))
  id)

(defn forget!
  "Drop a viewer by id (the reversible inverse of register!)."
  [id]
  (swap! registry (fn [r] (filterv #(not= (:id %) id) r)))
  id)

(defn viewers-for
  "Every registered viewer whose predicate accepts obj, in precedence order.
   This is the 'view this as…' menu for an object."
  [obj]
  (filterv (fn [{:keys [pred]}]
             (try (boolean (pred obj)) (catch Throwable _ false)))
           @registry))

(defn mold
  "Mold obj into a representation.

   With no view-id: use the first matching viewer — the confident default,
   the LEAP rule that 'the top result is already the action.'
   With a view-id: force that specific viewer.

   Returns {:view id, :label, :rendered data, :alternatives [[id label]...]}."
  ([obj] (mold obj nil))
  ([obj view-id]
   (let [matches (viewers-for obj)
         chosen  (or (when view-id (some #(when (= (:id %) view-id) %) @registry))
                     (first matches))]
     (if chosen
       {:view         (:id chosen)
        :label        (:label chosen)
        :kind         (:kind chosen :data) ;; how the target should lay it out
        :rendered     ((:render chosen) obj)
        :alternatives (mapv (juxt :id :label) matches)}
       ;; nothing molds it yet — hand back the raw value (still inspectable)
       {:view nil :label "raw" :kind :data :rendered obj :alternatives []}))))

;; ----------------------------------------------------------------------------
;; the recall / AI-memory seam (Khora's role) — STUBBED behind a protocol.
;; Swap NaiveRecall for a Khora sidecar or a Clojure-native engine later;
;; callers never change.
;; ----------------------------------------------------------------------------

(defprotocol Recall
  "The recall half of record/recall: revisable, inferred memory.
   `remember` adds; `recall` retrieves by relevance. The real engine fuses
   vector + graph + keyword + temporal signals — this stub does naive substring
   scoring so the shape exists while the engine is undecided."
  (remember [this fact opts] "store a fact (string) with metadata; returns :ok")
  (recall   [this query opts]
    "return ranked [{:fact :score :via …} …] for a query.

     opts an engine may honour: :k how many, :semantic? whether to spend a
     network round trip embedding the query (**false by default** — /api/leap
     calls this on every keystroke), :rerank? whether to let a reranker order
     the head, :filter {:sources #{id …}} to narrow to facts recorded against
     those ids. An engine that ignores an opt still answers; NaiveRecall below
     ignores all of them, which is why it is a stub and not a store.")
  (all-facts [this]
    "every stored fact, newest first — the browsable memory pane.

     On the protocol rather than reaching into an implementation's atom,
     because a store that keeps its facts on disk has no atom to deref."))

(defrecord NaiveRecall [store]
  Recall
  (remember [_ fact opts]
    (swap! store conj {:fact fact :meta (or opts {})})
    :ok)
  ;; conj on a vector appends, so newest is last; reversed to match the
  ;; protocol's "newest first". These facts carry no :ts to sort by.
  (all-facts [_] (vec (reverse @store)))
  (recall [_ query _opts]
    (let [q (str/lower-case (str query))]
      (->> @store
           (map #(assoc % :score (if (str/includes? (str/lower-case (:fact %)) q) 1.0 0.0)))
           (filter (comp pos? :score))
           (sort-by :score >)
           vec))))

(defn naive-recall
  "A throwaway in-memory Recall. Marks the seam; not the engine."
  []
  (->NaiveRecall (atom [])))
