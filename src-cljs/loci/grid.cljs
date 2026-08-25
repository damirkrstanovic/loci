(ns loci.grid
  "The ClojureScript grid: state, actions and the one effect.

   Two namespaces on purpose. This one owns state and talks to the network;
   `loci.grid.view` is a pure function of state to hiccup and requires nothing,
   so every question about what gets drawn is answerable without a DOM.

   **State lives on the server.** This atom holds the last payload plus the
   view-local preferences a person set — the page they are on, the size, the
   sort, the hidden columns. Nothing here is a second source of truth about
   content: `undo!` and `?at=` work because the substrate owns that, and a
   ratom-shaped cache of it is exactly what this design refuses.

   **A cursor is opaque.** It arrives on the payload and goes back out on the
   query string untouched. Nothing in this namespace decodes one, and the
   browser suite scans this file to keep it that way — three storage strategies
   share one pager precisely because nothing above the store reads a cursor."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [loci.grid.view :as view]
            [replicant.dom :as r]))

(defonce !grids (atom {}))            ; DOM element -> state
(def ^:private reader (t/reader :json))

(def page-one
  "Where a walk begins: no cursor, and no rows before it.

   Both halves of one fact. The cursor is how the server is ASKED for a page and
   is opaque; the offset is what the caption SAYS about it and is a number. They
   are recorded together because they are learned together — see `next-page`."
  {:cursor nil :offset 0})

(defn init-state [id]
  ;; `:stack` holds where each visited page began, so Prev is a pop and is exact.
  ;; A server-computed `prev` would be a second source of truth able to disagree
  ;; with it, and the store cannot compute one under keyset anyway.
  {:id id :payload nil :stack [page-one] :page 0 :limit 100 :sort nil :hidden #{}})

(defn current-cursor [s] (:cursor (nth (:stack s) (:page s) nil)))
;; `has-next?` is `loci.grid.view/has-next?` — a pure question about a payload,
;; and the view namespace is the one that requires nothing. Not duplicated here.

(defn on-payload
  "The fetched page REPLACES what was on screen; it does not join it.

   Clerk's elision appends, which is right for an inspector drilling into one
   value. At the 10M-row scale this targets it is wrong: forty clicks of an
   accreting `more` puts four thousand rows in the DOM, and a bounded DOM was
   half of what paging was for. Design §3.1."
  [s payload] (assoc s :payload payload))

(defn next-page [s]
  (if-let [c (get-in s [:payload :elision :cursor])]
    ;; The offset recorded beside the cursor is not the client counting pages: it
    ;; is the server's offset for the page on screen plus the rows the server
    ;; actually sent. It exists because the LAST page carries no elision — a hole
    ;; describing zero omitted rows is not a hole — and so carries no offset
    ;; either. Without it the final caption reads `showing 1–50 of 250` over rows
    ;; 201–250, which is the same class of defect as the spike's append label
    ;; over replace behaviour: the number and the rows disagreeing.
    (let [{:keys [offset]} (get-in s [:payload :elision])
          start (+ (or offset 0) (count (get-in s [:payload :rendered])))]
      (-> s (assoc :stack (conj (vec (take (inc (:page s)) (:stack s)))
                                {:cursor c :offset start}))
            (update :page inc)))
    s))

(defn prev-page [s] (cond-> s (pos? (:page s)) (update :page dec)))

(defn set-limit [s n]
  ;; A cursor names a position in a walk of a given size, so a new size
  ;; invalidates every cursor in the stack. Back to page one, which is honest —
  ;; the alternative is a caption whose arithmetic no longer matches the rows.
  (assoc s :limit n :stack [page-one] :page 0))

(defn toggle-sort [s col]
  ;; Three states, not two: ascending, descending, and back to the table's own
  ;; order — which for a lake table is the only order that pages by seeking
  ;; rather than by counting, so it must be reachable rather than lost on the
  ;; first click. Reordering invalidates the stack for the same reason a resize
  ;; does.
  (let [[c d] (:sort s)
        nxt   (cond (not= c col) [col "asc"] (= d "asc") [col "desc"] :else nil)]
    (assoc s :sort nxt :stack [page-one] :page 0)))

(defn toggle-col [s col]
  (assoc s :hidden (if (contains? (:hidden s) col)
                     (disj (:hidden s) col)
                     (conj (:hidden s) col))
           :stack [page-one] :page 0))

(defn- visible-cols [s]
  (let [all (get-in s [:payload :table/cols])]
    (when (seq (:hidden s)) (vec (remove (:hidden s) all)))))

(defn- url [s]
  (str "/api/mold?wire=transit&id=" (js/encodeURIComponent (:id s))
       "&limit=" (:limit s)
       (when-let [c (current-cursor s)] (str "&cursor=" (js/encodeURIComponent c)))
       (when-let [[c d] (:sort s)] (str "&sort=" (js/encodeURIComponent (str (name c) "," d))))
       (when-let [cs (visible-cols s)]
         (str "&cols=" (js/encodeURIComponent (str/join "," (map name cs)))))))

(defn- render! [el]
  (r/render el (view/grid el (get @!grids el))))

(defn refresh!
  "The one effect. Everything else in this namespace is a pure state transition."
  [el]
  (-> (js/fetch (url (get @!grids el)))
      (.then #(.text %))
      (.then (fn [body]
               (swap! !grids update el on-payload (t/read reader body))
               (render! el)))))

(defn- act! [el f & args]
  (swap! !grids update el #(apply f % args))
  (refresh! el))

;; **Replicant hands the handler data back VERBATIM.** `replicant.core/
;; get-event-handler` wraps the data in `(fn [e] (*dispatch* rd handler))` and
;; interpolates nothing, so there is no placeholder spelling for "the value of
;; the event that fired" — the plan's `%` was a guess and is not one. What the
;; dispatch does get is the DOM event itself, on the `rd` map every dispatch
;; receives first, and that is where a `<select>`'s value is read from.
(defn- event-value [rd]
  (some-> (or (:replicant/node rd)
              (some-> ^js (:replicant/dom-event rd) .-target))
          .-value))

(r/set-dispatch!
 (fn [rd [[action el arg]]]
   (case action
     :page/next (act! el next-page)
     :page/prev (act! el prev-page)
     :page/size (act! el set-limit (js/parseInt (event-value rd) 10))
     :sort/toggle (act! el toggle-sort arg)
     :cols/toggle (act! el toggle-col arg)
     (js/console.warn "loci.grid: unknown action" (pr-str action)))))

(defn ^:export mount
  "Called BY the vanilla shell from `wireSourceButtons`, with the node it just
   inserted. That is the whole integration surface."
  [el id]
  (swap! !grids assoc el (init-state id))
  (refresh! el))

(defn init [])
