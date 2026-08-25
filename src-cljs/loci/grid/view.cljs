(ns loci.grid.view
  "Hiccup, and nothing else. Requires no DOM, no network and no state container,
   so every question about what gets drawn is answered by `cljs.test` in Node.

   If a function here reaches for `js/document`, it is in the wrong namespace —
   that is the whole reason the split exists. `loci.grid` owns state and the one
   effect; this owns what a person sees, as data.")

(defn caption
  "`showing 401–402 of 10000000`, or `… of 10000000+` when the total is a claim
   rather than a fact.

   **Counted from the server's `:offset`, never from the client's page index.**
   The client's is arithmetic over a page size it may have changed; the server's
   is where the rows it just sent actually start."
  [{:keys [payload]}]
  (let [{:keys [rendered elision table/total]} payload
        off   (or (:offset elision) 0)
        n     (count rendered)
        total (or (:total elision) total n)]
    (str "showing " (inc off) "–" (+ off n) " of " total
         (when (:unbounded? elision) "+"))))

(defn has-next?
  "Is there a hole? A page with no elision omitted nothing, so there is nowhere
   to go. Here rather than in `loci.grid` because it is a pure question about a
   payload, and this namespace is the one that requires nothing."
  [{:keys [payload]}]
  (some? (get-in payload [:elision :cursor])))

;; `grid` takes the element as well as the state, from the start: the pager, the
;; sort headers and the column chooser all dispatch actions that must name WHICH
;; grid they act on, and a signature that grows between tasks is one two call
;; sites disagree about for a commit.
(defn grid [_el _state] [:div.grid])
