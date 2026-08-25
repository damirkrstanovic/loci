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

(def ^:private tooltip-max
  "How much of a value the `title` attribute carries. Measured on the real
   substrate: a `description` column reached 16,344 characters, which is not a
   tooltip — and a page of full values duplicated into attributes is a second
   copy of the rows in the DOM."
  500)

(defn- clip [s] (if (> (count s) tooltip-max) (str (subs s 0 tooltip-max) "…") s))

(defn- numeric-cols
  "The columns whose values say they are numbers — CSS cannot see that, so the
   markup carries it. Blanks are not evidence either way: real tables have holes.

   Computed once per render and not once per cell. Per cell it is
   `columns × rows²`: 36 columns over a 100-row page is 360,000 lookups for an
   answer that is the same 3,600 times over."
  [rows cols]
  (into #{} (filter (fn [c]
                      (let [vs (remove nil? (map #(get % c) rows))]
                        (and (seq vs) (every? number? vs)))))
        cols))

;; `grid` takes the element as well as the state, from the start: the pager, the
;; sort headers and the column chooser all dispatch actions that must name WHICH
;; grid they act on, and a signature that grows between tasks is one two call
;; sites disagree about for a commit.
(defn grid [_el {:as s :keys [payload]}]
  (let [{:keys [rendered table/cols]} payload
        cols (vec (remove (:hidden s) cols))
        nums (numeric-cols rendered cols)]
    [:div.grid
     ;; `width: max-content` and nothing else. The old renderer computed a
     ;; per-column character width and then normalised it to percentages of a
     ;; `width:100%` table, which discards the scale — at 36 columns the
     ;; 7-character floor became 0.7 characters and the scrollbar was rounding.
     ;; One property replaces that arithmetic; Clerk reaches the same result the
     ;; same way.
     ;;
     ;; It lives in the stylesheet's `.grid table.mold` rule rather than inline
     ;; here, because it is not one property after all: `table.mold` already
     ;; carries `table-layout:fixed`, which divides one width between the
     ;; columns and is precisely the arithmetic being replaced. Two of them have
     ;; to move together, and a rule beside the one it overrides is where a
     ;; reader will look.
     [:div {:data-grid-scroll true
            :style {:overflow "auto" :max-height "420px"}}
      [:table.mold
       [:thead [:tr (for [c cols]
                      [:th {:replicant/key c :title (name c)
                            :class (when (nums c) "num")}
                       (name c)])]]
       [:tbody
        (for [[i row] (map-indexed vector rendered)]
          [:tr {:replicant/key i}
           (for [c cols]
             (let [v (str (get row c))]
               [:td {:replicant/key c :title (clip v)
                     :class (if (nums c) "num" "txt")}
                v]))])]]]
     [:div.pgcap (caption s)]]))
