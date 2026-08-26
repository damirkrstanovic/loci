(ns loci.grid.view
  "Hiccup, and nothing else. Requires no DOM, no network and no state container,
   so every question about what gets drawn is answered by `cljs.test` in Node.

   If a function here reaches for `js/document`, it is in the wrong namespace —
   that is the whole reason the split exists. `loci.grid` owns state and the one
   effect; this owns what a person sees, as data."
  (:require [clojure.string :as str]))

(defn- page-start
  "How many rows precede the page on screen.

   The elision's `:offset` whenever there is one, because it is the SERVER's and
   the server's is a fact — the client's page index is arithmetic over a page
   size a person can change mid-walk.

   The fallback is for the LAST page, which carries no elision — a hole
   describing zero omitted rows is not a hole — and therefore no offset. It is
   still not the client counting pages: `loci.grid/next-page` recorded it when
   Next was pressed, as the server's offset for the page then on screen plus the
   rows the server actually sent. Without it the last caption of the 250-row
   fixture reads `showing 1–50 of 250` over rows 201–250."
  [{:keys [payload stack page]}]
  (or (get-in payload [:elision :offset])
      (:offset (nth stack page nil))
      0))

(defn group-digits
  "`10000000` → `10,000,000`. Design §3.1 shows the caption grouped, and at the
   scale this design targets an ungrouped eight-digit number is genuinely hard
   to read — `10000000` and `1000000` differ by a glance.

   **A regex and not `toLocaleString`.** The caption is asserted on by two
   suites, and `toLocaleString` answers with whatever the machine's locale says:
   `10.000.000` under de-DE, a narrow no-break space under fr-FR, `1,00,00,000`
   under en-IN. The same source would pass here and fail on a colleague's
   laptop. This groups one way, everywhere, and is a property of loci rather
   than of the box it runs on.

   `\\B` is what keeps a comma out of position zero — and out of the gap after a
   minus sign, which IS a word boundary, so `-1000` groups as `-1,000` rather
   than as `-,1000`. The lookahead fires only where the digits remaining to the
   end are a multiple of three, so `999` is left alone and `1000` is not."
  [n]
  (str/replace (str n) #"\B(?=(\d{3})+$)" ","))

(defn caption
  "`showing 401–402 of 10,000,000`, or `… of 10,000,000+` when the total is a
   claim rather than a fact."
  [{:as s :keys [payload]}]
  (let [{:keys [rendered elision table/total]} payload
        off   (page-start s)
        n     (count rendered)
        total (or (:total elision) total n)]
    (str "showing " (group-digits (inc off)) "–" (group-digits (+ off n))
         " of " (group-digits total)
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

(defn- aria-sort
  "What `aria-sort` this column's header carries, or nil for no attribute.

   **One attribute, not two.** `aria-sort` is the standard way a header
   announces its state, the browser suite reads it, and the stylesheet draws the
   arrow off it — a `data-grid-sort` beside it would be a second copy of one
   fact, able to disagree with the first the day someone edits only one.

   nil rather than `\"none\"` for an unsorted column: Replicant removes an
   attribute whose value is falsy, so nil is what a *selector* can tell apart —
   `th[aria-sort]` is the sorted one, and there is exactly one.

   The arrow itself is a `::after` pseudo-element in the stylesheet — drawn on
   the header's button, selected as `th[aria-sort] .colsort::after`, so it is
   still this one attribute the stylesheet reads — and NOT text in the cell. The
   browser suite finds every column by its header's `textContent`; a marker
   inside the cell would rename whichever of the 36 is sorted, halfway through a
   test. A pseudo-element is not in `textContent`, which is the whole reason it
   is one."
  [[c d] col]
  (when (= c col) (if (= "desc" d) "descending" "ascending")))

(defn- header
  "One column's header: a `th` that carries the state, and inside it the control
   a person actually presses.

   **A `th` is not a control, and Task 9 shipped one as if it were.** The click
   handler sat on the cell, so `aria-sort` let a screen reader READ the sort
   while nothing could reach it without a mouse — a table is not in the tab
   order, takes no focus and fires nothing on Enter. The fix is a real
   `<button>`: the browser gives it the tab order, the focus ring, Enter and
   Space, and the `button` role, none of which is worth reimplementing on a
   cell with `tabindex` and a keydown handler.

   **The button fills the cell.** `.grid thead th` drops its padding and the
   button takes it, so the whole header rectangle is the control — a button
   sitting inside a padded cell would leave the cell's margin clicking nothing,
   which is how the mouse half of this control would have regressed.

   `aria-sort` stays on the `th`, where `columnheader` is the role that takes
   it, and stays the only place the sort state is written down."
  [el s nums c]
  [:th {:replicant/key c :title (name c)
        :aria-sort (aria-sort (:sort s) c)
        :class (when (nums c) "num")}
   [:button.colsort {:type "button" :on {:click [[:sort/toggle el c]]}}
    (name c)]])

(def ^:private page-sizes
  "What a person can ask for, not what the server will honour: `page-limit`
   clamps, and the elision reports the size it actually used."
  [25 50 100 200])

(defn- pager
  "Prev, Next, a size, and a caption that counts.

   **A Prev control is the visible half of §3.1.** Clerk's elision appends, so
   `more` is the only control it needs — there is nowhere to go back to when
   nothing was replaced. loci's REPLACES, so a walk needs both directions and a
   caption saying which slice is on screen. If someone ever deletes Prev, they
   have quietly turned this back into Clerk's protocol.

   The actions carry `el`, because `!grids` is keyed by the mounted element and a
   page may hold more than one grid.

   **`data-grid-*` and not `data-a`**, which the vanilla shell already wires by
   value in `wireSourceButtons` — two owners of one attribute is a collision
   waiting for whoever adds `data-a=\"prev\"` to the shell."
  [el s]
  [:div.pager
   [:button.btn.ghost.sm {:data-grid-prev true
                          ;; `false` REMOVES the attribute — Replicant's
                          ;; `update-attr` treats any falsy value as absence —
                          ;; so this is a real enable, not `disabled=\"false\"`,
                          ;; which HTML reads as disabled.
                          :disabled (not (pos? (:page s)))
                          :on {:click [[:page/prev el]]}} "‹ Prev"]
   [:button.btn.ghost.sm {:data-grid-next true
                          :disabled (not (has-next? s))
                          :on {:click [[:page/next el]]}} "Next ›"]
   ;; **`:selected` on the options, never `:value` on the select.** Replicant sets
   ;; an element's attributes BEFORE appending its children (`core/create-node`),
   ;; and `value` on a `<select>` is a property assignment — so a `:value` here
   ;; would be applied to a select with no options yet, silently do nothing, and
   ;; then never be re-applied because the vdom sees no change. The browser suite
   ;; measures the select's value on first paint for exactly this reason.
   [:select {:data-grid-size true :on {:change [[:page/size el]]}}
    (for [n page-sizes]
      [:option {:replicant/key n :value (str n) :selected (= n (:limit s))}
       (str n " rows")])]
   [:div.pgcap (caption s)]])

(defn- chooser
  "Every column the TABLE has, ticked or not — never only the ones on screen.

   The list is the payload's `:table/cols`, which `hydrate-page` takes off the
   handle rather than off the projected rows. Off the rows it would be one-way:
   a hidden column is missing from every page that follows, so there would be
   nothing left to tick back on.

   **Unticking narrows the REQUEST and not just the page.** `loci.grid/url`
   sends what is left as `cols`; the server projects. A chooser that filtered
   here instead would look identical and would still be shipping every column
   of a 10M-row table across the wire, which is the whole point missed.

   `<details>` and not a popup: it is a disclosure, the browser owns opening and
   closing it, and Replicant never writes `open` — so a re-render after a fetch
   leaves it exactly as the person left it."
  [el s cols]
  (let [hidden (:hidden s)
        shown  (remove hidden cols)]
    [:details.colpick {:data-grid-cols true}
     [:summary (str (count shown) " of " (count cols) " columns")]
     [:div.colpick-list
      (for [c cols]
        (let [on? (not (contains? hidden c))]
          [:label {:replicant/key c :data-grid-col (name c)}
           [:input {:type "checkbox"
                    ;; Replicant sets `checked` as a PROPERTY — `(set! (.-checked
                    ;; el) v)` in `replicant.dom` — and not as an attribute, so
                    ;; this survives the browser's own dirty-checkedness flag and
                    ;; a re-render cannot leave a box disagreeing with the state.
                    :checked on?
                    ;; The last one standing cannot be unticked: an empty
                    ;; projection means ALL columns server-side, so the click
                    ;; would bring every column back. `toggle-col` declines as
                    ;; well; this is the half that says so before the click.
                    :disabled (and on? (= 1 (count shown)))
                    :on {:change [[:cols/toggle el c]]}}]
           (name c)]))]]))

;; `grid` takes the element as well as the state, from the start: the pager, the
;; sort headers and the column chooser all dispatch actions that must name WHICH
;; grid they act on, and a signature that grows between tasks is one two call
;; sites disagree about for a commit.
(defn grid [el {:as s :keys [payload]}]
  (let [{:keys [rendered table/cols]} payload
        all  (vec cols)
        cols (vec (remove (:hidden s) all))
        nums (numeric-cols rendered cols)]
    [:div.grid
     (chooser el s all)
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
       ;; **A header is a control with three states, not two.** Ascending,
       ;; descending, and back to the table's own order — which is the only
       ;; order a lake table pages by SEEKING (`WHERE rowid > k`) rather than by
       ;; counting, so it has to be reachable rather than lost on the first
       ;; click. `loci.grid/toggle-sort` is the cycle; `header` is the control,
       ;; and it is a `<button>` so that a keyboard can reach it.
       ;;
       ;; The server does the sorting. This sends `[col dir]` on the window and
       ;; `sort-spec` validates it against the handle, so a header naming a
       ;; column the table has since lost is ignored rather than fatal.
       [:thead [:tr (for [c cols] (header el s nums c))]]
       [:tbody
        (for [[i row] (map-indexed vector rendered)]
          [:tr {:replicant/key i}
           (for [c cols]
             (let [v (str (get row c))]
               [:td {:replicant/key c :title (clip v)
                     :class (if (nums c) "num" "txt")}
                v]))])]]]
     (pager el s)]))
