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
(def ^:private writer (t/writer :json))

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

(defn toggle-col
  "Show or hide one column.

   Hiding narrows the REQUEST — see `cols-param` — so the rows come back a
   different shape, and every cursor in the stack named a walk over the old one.
   Back to page one, for the reason `set-limit` says.

   **The last visible column cannot be hidden.** Server-side an empty projection
   means EVERY column: `project-cols` says so outright, because a table with no
   columns is not something the shell can draw. So unticking the thirty-sixth
   box would bring all thirty-six back — a control whose result is the opposite
   of what was asked of it. It declines instead, and the view disables that last
   checkbox so the refusal is visible before the click rather than after it."
  [s col]
  (let [hidden  (:hidden s)
        hiding? (not (contains? hidden col))
        left    (remove (conj hidden col) (get-in s [:payload :table/cols]))]
    (if (and hiding? (empty? left))
      s
      (assoc s :hidden (if hiding? (conj hidden col) (disj hidden col))
               :stack [page-one] :page 0))))

(defn cols-param
  "The `cols` value for the next request, or **nil for no parameter at all**.

   Nil and not the full list when nothing is hidden. Server-side an absent or
   empty `cols` means every column the handle has, so sending the full list
   explicitly would pin the request to the columns this page happened to know
   about — and a column added to the table later would silently never arrive.

   The list is the payload's `:table/cols`, which `hydrate-page` takes off the
   HANDLE and not off the projected rows. Off the rows, hiding would be one-way:
   a hidden column is absent from every page that follows, so there would be
   nothing left to ask for again.

   **`:prior-cols` is the cold-start fallback, and it is used exactly once.**
   What is stored is which columns a person turned OFF; what the server takes is
   which to turn ON, so the difference needs a list — and on the first request of
   a session there is no payload to take one from. So the list the preference was
   recorded against is remembered beside it. Without that, the one request in a
   session that is a cold read is also the one that ships all thirty-six columns
   of a table someone narrowed to five. The moment a payload lands the handle's
   own list supersedes it, so a column added to the table since is asked for from
   the second request on rather than being hidden forever."
  [s]
  (when (seq (:hidden s))
    (some->> (or (seq (get-in s [:payload :table/cols])) (seq (:prior-cols s)))
             (remove (:hidden s))
             seq
             (map name)
             (str/join ","))))

(defn- url [s]
  (str "/api/mold?wire=transit&id=" (js/encodeURIComponent (:id s))
       "&limit=" (:limit s)
       (when-let [c (current-cursor s)] (str "&cursor=" (js/encodeURIComponent c)))
       (when-let [[c d] (:sort s)] (str "&sort=" (js/encodeURIComponent (str (name c) "," d))))
       (when-let [cs (cols-param s)] (str "&cols=" (js/encodeURIComponent cs)))))

;; ---- remembering a lens, browser-side and never as an event ----
;;
;; **A column-header click is not an act on content.** The event log is what
;; `?at=` rewinds and `undo!` reverses; every hidden column becoming a point in
;; history is noise in the one log that has to stay meaningful. So these live in
;; the browser, per object id. (`:view` stays a substrate event, as today — it is
;; chosen once, not a knob.)

(def ^:private prefs-prefix "loci.grid/")

(defn- storage
  "`localStorage`, or nil where this browser will not give one up.

   **The accessor itself throws** — a private window, cleared site data, or a
   browser set to block storage raises on `window.localStorage` before any
   `getItem` is reached. So an unguarded read here does not return nothing, it
   takes the whole panel down before the first row is drawn.

   **This `try` is redundant against the two call sites below and is kept
   anyway.** Measured 2026-08-26 by deleting it: the browser suite stayed green,
   because `load-prefs` and `save-prefs!` each wrap the whole expression and
   catch the accessor as readily as their own operation. It is here so that the
   function's contract — a store or nil, never a throw — is true of the function
   and not only of its present callers, and this note is here so the next reader
   does not mistake it for load-bearing."
  []
  (try (.-localStorage js/window) (catch :default _ nil)))

(defn load-prefs
  "What was remembered for `id`, or nil. Never throws.

   Transit and not JSON, so a sort of `[:grp \"asc\"]` and a hidden set of
   keywords come back as what they went in as.

   **Its `try` is load-bearing and covers more than the accessor.** `getItem`
   has its own refusals, and the stored VALUE is hand-editable, survives
   upgrades of loci, and can have been truncated by a write that did not finish —
   so `t/read` is a second thing that throws, in a place nothing above `mount` is
   watching. Measured by removing this one alone: the browser reports
   `SyntaxError: Expected property name or '}' in JSON at position 1` out of
   `openObject`, and the panel never draws."
  [id]
  (try
    (when-let [raw (some-> (storage) (.getItem (str prefs-prefix id)))]
      (t/read reader raw))
    (catch :default _ nil)))

(defn save-prefs!
  "Remember `prefs` for `id`. Never throws — `setItem` has its own failures
   (a full quota, a store that reads but will not write) and none of them is
   worth a broken panel."
  [id prefs]
  (try
    (some-> (storage) (.setItem (str prefs-prefix id) (t/write writer prefs)))
    (catch :default _ nil))
  nil)

(defn- prefs-of
  "The slice of state that is a PREFERENCE rather than a position.

   `:page` and `:stack` are deliberately absent. Where someone had walked to is
   not something to restore into a table whose rows may have changed under them,
   and a cursor minted against an older snapshot is not a thing to hand back to
   a server a week later.

   `:cols` is not a preference but the evidence one was recorded against — the
   table's column list at the time. `cols-param` needs it to turn a set of hidden
   columns into a list of wanted ones before the first payload arrives; see its
   docstring."
  [s]
  (cond-> (select-keys s [:sort :hidden :limit])
    (seq (get-in s [:payload :table/cols]))
    (assoc :cols (vec (get-in s [:payload :table/cols])))))

(defn with-prefs
  "`s` with remembered preferences applied — and `s` unchanged where there is
   nothing remembered, or where what was remembered is not the shape this
   version expects.

   **Every field is checked rather than merged.** The value comes out of a store
   this code does not own: it survives upgrades, it is editable by hand, and it
   can carry whatever some earlier loci wrote. A `:hidden` that arrived as a
   vector is used as a predicate by `cols-param` and throws on the first
   render — a blank panel, which is a far worse outcome than a forgotten
   preference."
  [s prefs]
  (let [{:keys [sort hidden limit cols]} prefs]
    (cond-> s
      (and (vector? sort) (= 2 (count sort)) (keyword? (first sort))) (assoc :sort sort)
      (set? hidden)                                                   (assoc :hidden hidden)
      (and (int? limit) (pos? limit))                                 (assoc :limit limit)
      (vector? cols)                                                  (assoc :prior-cols cols))))

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
  (let [was (prefs-of (get @!grids el))
        s   (get (swap! !grids update el #(apply f % args)) el)
        now (prefs-of s)]
    ;; Written only when a preference actually moved. Next is by far the most
    ;; common action here and stores nothing, and a synchronous storage write per
    ;; page turn is a cost paid for no new fact.
    (when (not= was now) (save-prefs! (:id s) now))
    (refresh! el)))

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
  ;; The preferences are in force BEFORE the first request, not applied to the
  ;; page after it lands: otherwise the shell asks the server for a hundred rows
  ;; of all thirty-six columns in the wrong order, throws that away, and asks
  ;; again — and on a 10M-row table that first request is not free.
  (swap! !grids assoc el (with-prefs (init-state id) (load-prefs id)))
  (refresh! el))

(defn init [])
