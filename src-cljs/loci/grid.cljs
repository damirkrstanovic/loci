(ns loci.grid
  "SPIKE 2026-08-25 — does the ClojureScript seam work?

   Four things this is trying to establish, and nothing else:

   1. shadow-cljs compiles against the existing `deps.edn` and the output can be
      mounted into the existing vanilla shell without touching the other ~3,000
      lines of it.
   2. Replicant renders hiccup into a node handed to it by that shell.
   3. **Transit preserves a namespaced keyword.** `:table/total` must arrive as
      the keyword `:table/total`, not the string \"total\" — that silent rename is
      what broke two tasks of the paging plan when the wire was JSON.
   4. Clerk's elision shape reads naturally as data on this side: a row that IS
      the hole, carrying what is needed to fill it.

   It is a spike. It is not the design."
  (:require [cognitect.transit :as t]
            [replicant.dom :as r]))

(defonce !state (atom {}))

(declare view)   ; fetch-page! renders, and view is defined below it

(def ^:private reader (t/reader :json))

(defn- fetch-page!
  "One page of a mold, over Transit. `cursor` is opaque — handed back untouched,
   never read, which is the whole reason one control can front two different
   storage strategies underneath."
  [el id cursor]
  (-> (js/fetch (str "/api/mold?id=" (js/encodeURIComponent id)
                     "&wire=transit&limit=25"
                     (when cursor (str "&cursor=" (js/encodeURIComponent cursor)))))
      (.then #(.text %))
      (.then (fn [body]
               (let [m (t/read reader body)]
                 (swap! !state assoc el m)
                 (r/render el (view el m)))))))

;; Replicant's data-driven handlers: a handler RETURNS an action as data rather
;; than performing an effect. The dispatch below is the only place an effect
;; happens — which is the same shape loci's substrate already has, where a click
;; becomes an event rather than a mutation.
(defn- view [el {:as m :keys [table/total table/cols page/next id rendered]}]
  [:div.gridspike
   [:div {:style {:overflow "auto" :max-height "420px"}}
    [:table.mold {:style {:width "max-content" :max-width "none"}}
     [:thead
      [:tr (for [c cols]
             [:th {:title (name c)} (name c)])]]
     [:tbody
      (for [[i row] (map-indexed vector rendered)]
        [:tr {:replicant/key i}
         (for [c cols] [:td (str (get row c))])])
      ;; the elision: a row that IS the hole. Clerk's shape, loci's cursor.
      (when next
        [:tr.elision
         [:td {:colSpan (count cols)
               :on {:click [[:fetch-more el id next]]}}
          (str (- total (count rendered)) " more")]])]]]
   [:div.pgcap (str "showing " (count rendered)
                    " of " total
                    " · " (count cols) " columns")]])

(r/set-dispatch!
 (fn [_ [[action el id cursor]]]
   (case action
     :fetch-more (fetch-page! el id cursor)
     (js/console.warn "unknown action" (pr-str action)))))

(defn ^:export mount
  "Called BY the existing vanilla shell, from `wireSourceButtons`, with the node
   it just inserted. That is the whole integration surface."
  [el id]
  (fetch-page! el id nil))

(defn init [] (js/console.log "loci.grid spike loaded"))
