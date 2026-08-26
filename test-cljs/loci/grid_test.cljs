(ns loci.grid-test
  "State transitions and the view's DATA. What needs a real DOM — sticky
   positions, scroll width — is measured by test/browser/grid.test.mjs, because
   a headless DOM answers those questions wrongly rather than not at all."
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is]]
            [loci.grid :as grid]
            [loci.grid.view :as view]))

(def ^:private payload
  {:table/cols [:a :b]
   :rendered   [{:a 1 :b "x"} {:a 2 :b "y"}]
   :elision    {:n 2 :offset 0 :total 10 :unbounded? false
                :path [:rendered] :cursor "CUR1"}})

(deftest a-fresh-grid-is-on-page-one-with-an-empty-stack
  (let [s (grid/init-state "tbl:x")]
    (is (= "tbl:x" (:id s)))
    (is (= [grid/page-one] (:stack s))
        "page one begins at no cursor and at row zero")
    (is (= 0 (:page s)))
    (is (= 100 (:limit s)))))

(deftest next-pushes-the-elisions-cursor-and-prev-pops-it
  (let [s0 (assoc (grid/init-state "tbl:x") :payload payload)
        s1 (grid/next-page s0)
        s2 (grid/prev-page s1)]
    (is (= [{:cursor nil :offset 0} {:cursor "CUR1" :offset 2}] (:stack s1))
        "the cursor and where it lands are learned together and recorded together")
    (is (= 1 (:page s1)))
    (is (= "CUR1" (grid/current-cursor s1)) "the next request asks from the hole")
    (is (= 0 (:page s2)))
    (is (nil? (grid/current-cursor s2)) "prev is a pop, not a recomputation")))

(deftest prev-on-page-one-does-nothing
  (let [s (grid/init-state "tbl:x")]
    (is (= s (grid/prev-page s)))))

(deftest changing-the-page-size-restarts-the-walk
  ;; A cursor names a position in a walk of a given size. Keeping the stack
  ;; would leave the caption's arithmetic describing rows that are not there.
  (let [s1 (grid/next-page (assoc (grid/init-state "tbl:x") :payload payload))
        s2 (grid/set-limit s1 25)]
    (is (= 25 (:limit s2)))
    (is (= [grid/page-one] (:stack s2)))
    (is (= 0 (:page s2)))))

(deftest a-fetched-page-REPLACES-the-rows-it-does-not-append
  ;; The decision in §3.1, and the defect the spike shipped: an elision labelled
  ;; "N more" that replaced. loci replaces AND says so; this test is what stops
  ;; someone "fixing" it into Clerk's accretion.
  (let [s1 (grid/on-payload (grid/init-state "tbl:x") payload)
        s2 (grid/on-payload s1 (assoc payload :rendered [{:a 3 :b "z"}]
                                              :elision nil))]
    (is (= 1 (count (:rendered (:payload s2)))))
    (is (= 3 (:a (first (:rendered (:payload s2))))) "page two only")))

(deftest the-caption-counts-from-the-servers-offset-not-the-clients-page
  (let [s (grid/on-payload (grid/init-state "tbl:x")
                           (assoc-in payload [:elision :offset] 400))]
    (is (= "showing 401–402 of 10" (view/caption s))
        "the server's offset is a fact; the client's page index is arithmetic")))

(deftest an-unbounded-total-says-so
  (let [s (grid/on-payload (grid/init-state "tbl:x")
                           (assoc-in payload [:elision :unbounded?] true))]
    (is (= "showing 1–2 of 10+" (view/caption s)))))

(deftest a-last-page-has-no-elision-and-next-is-disabled
  (let [s (grid/on-payload (grid/init-state "tbl:x") (dissoc payload :elision))]
    (is (not (view/has-next? s)))
    (is (= "showing 1–2 of 2" (view/caption s))
        "with no hole, the total is what is on screen")))

(deftest the-last-page-counts-from-where-the-walk-left-off
  ;; A final page carries NO elision — a hole describing zero omitted rows is not
  ;; a hole — so it carries no offset either, and the caption has nothing from the
  ;; server to count from. Without the offset `next-page` recorded, this reads
  ;; "showing 1–3 of 5" over the last three rows of five.
  (let [s1 (grid/on-payload (grid/init-state "tbl:x") payload)
        s2 (grid/next-page s1)
        s3 (grid/on-payload s2 {:table/cols [:a :b] :table/total 5
                                :rendered [{:a 3} {:a 4} {:a 5}]})]
    (is (= "showing 3–5 of 5" (view/caption s3)))
    (is (not (view/has-next? s3)) "there is no hole, so there is nowhere to go")))

(defn- els
  "Every hiccup element in `tree` whose tag is `tag`, class shorthand and all —
   `:button.btn.ghost.sm` is a button. The pager is private, so the tests reach
   it the way a reader does: through what `grid` returns, which is data.

   **The boundary is not decoration.** A bare `starts-with?` also matches
   `:thead` when asked for `\"th\"`, and `:tbody` when asked for `\"tb\"` — so a
   test reading the header cells' attributes silently got the `thead` wrapper's
   as its first answer, and every assertion about the first column's header was
   really about a wrapper that has no attributes at all. Found 2026-08-26, by
   the assertion going red on a correct implementation."
  [tree tag]
  (let [tag? (fn [k] (let [n (name k)]
                       (and (str/starts-with? n tag)
                            (contains? #{nil \. \#} (get n (count tag))))))]
    (filter #(and (vector? %) (keyword? (first %)) (tag? (first %)))
            (tree-seq coll? seq tree))))

(deftest prev-is-dead-on-page-one-and-next-is-dead-at-the-end
  (let [on-1  (grid/on-payload (grid/init-state "tbl:x") payload)
        [prev nxt] (els (view/grid nil on-1) "button")
        last-p (grid/on-payload (grid/next-page on-1) (dissoc payload :elision))
        [prev2 nxt2] (els (view/grid nil last-p) "button")]
    (is (true? (:disabled (second prev))) "nothing precedes page one")
    (is (false? (:disabled (second nxt))) "the payload carries a hole")
    (is (false? (:disabled (second prev2))) "page two can go back")
    (is (true? (:disabled (second nxt2))) "the last page omitted nothing")))

(deftest the-size-select-shows-the-size-in-force
  ;; Not a formality: Replicant sets an element's attributes before appending its
  ;; children, so `:value` on the `<select>` itself would be assigned to a select
  ;; with no options and silently do nothing. The selection is on the options.
  (let [s (grid/set-limit (grid/on-payload (grid/init-state "tbl:x") payload) 50)
        opts (els (view/grid nil s) "option")]
    (is (= [25 50 100 200] (map #(js/parseInt (:value (second %)) 10) opts)))
    (is (= [50] (keep #(when (:selected (second %))
                         (js/parseInt (:value (second %)) 10)) opts))
        "exactly one option is selected, and it is the size in force")))

;; ---- Task 9: the sort control ----

(deftest a-header-cycles-ascending-descending-and-back-to-the-tables-own-order
  ;; **Three states and not two.** Natural order is the only order a lake table
  ;; pages by SEEKING — `WHERE rowid > k` — rather than by counting rows it then
  ;; throws away, so it has to be reachable rather than lost on the first click.
  ;; A two-state toggle would make the cheapest ordering the one state a person
  ;; can never get back to.
  (let [s0 (grid/init-state "tbl:x")
        s1 (grid/toggle-sort s0 :b)
        s2 (grid/toggle-sort s1 :b)
        s3 (grid/toggle-sort s2 :b)]
    (is (= [:b "asc"] (:sort s1)) "the first click sorts ascending")
    (is (= [:b "desc"] (:sort s2)) "the second reverses it")
    (is (nil? (:sort s3)) "and the third asks for no sort at all, not for ascending again")))

(deftest a-different-column-starts-its-own-cycle
  ;; Otherwise the second column would inherit the first's phase — click `grp`
  ;; twice, then `id` once, and get `id` descending, which nobody asked for.
  (let [s (grid/toggle-sort (grid/toggle-sort (grid/init-state "tbl:x") :b) :b)]
    (is (= [:b "desc"] (:sort s)))
    (is (= [:a "asc"] (:sort (grid/toggle-sort s :a)))
        "a new column starts at ascending whatever the old one had reached")))

(deftest sorting-restarts-the-walk
  ;; A cursor names a position in ONE ordering — the same reason `set-limit`
  ;; throws the stack away. Under keyset the cursor is a rowid, which means
  ;; nothing once the rows are ordered by a column; under offset the server
  ;; already refuses a cursor minted under a different `:sort`. Either way page
  ;; two of the old order is not page two of the new one.
  (let [s1 (grid/next-page (assoc (grid/init-state "tbl:x") :payload payload))
        s2 (grid/toggle-sort s1 :a)]
    (is (= 1 (:page s1)) "the walk really had moved off page one")
    (is (= [grid/page-one] (:stack s2)) "every cursor in the stack named the old order")
    (is (= 0 (:page s2)))))

(deftest the-sorted-header-says-which-way-and-the-others-say-nothing
  ;; `aria-sort` and not a `data-*` attribute: it is what a screen reader reads,
  ;; what the stylesheet draws the arrow off, and what the browser suite asserts
  ;; on. Absent — not "none" — when a column is unsorted, so that `th[aria-sort]`
  ;; selects exactly the one column that is.
  (let [asc  (grid/toggle-sort (grid/on-payload (grid/init-state "tbl:x") payload) :b)
        desc (grid/toggle-sort asc :b)
        nat  (grid/toggle-sort desc :b)
        aria (fn [s] (map #(:aria-sort (second %)) (els (view/grid nil s) "th")))]
    (is (= [nil "ascending"] (aria asc)) "one header carries the state; the other carries nothing")
    (is (= [nil "descending"] (aria desc)))
    (is (= [nil nil] (aria nat)) "the table's own order is no column's sort")))

(deftest a-header-click-names-the-grid-and-the-column
  ;; The action carries `el` because `!grids` is keyed by the mounted element and
  ;; a page can hold more than one grid; it carries the column because the header
  ;; is the only thing that knows which one was pressed. Replicant hands handler
  ;; data back verbatim, so what is written here is exactly what `set-dispatch!`
  ;; receives — there is no placeholder interpolation to get wrong.
  (let [ths (els (view/grid ::el (grid/on-payload (grid/init-state "tbl:x") payload)) "th")]
    (is (= [[[:sort/toggle ::el :a]] [[:sort/toggle ::el :b]]]
           (map #(get-in (second %) [:on :click]) ths)))))
