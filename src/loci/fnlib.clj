(ns loci.fnlib
  "Built-in single-table transforms — simple-Excel verbs, close to the data.
   A function is DATA {:id :label :doc :params :pred :run}; the registry is a
   vector. UI-free: catalog and run-fn work over plain rows, any shell renders."
  (:require [clojure.string :as str]))

(defn numeric-cols
  "Columns whose value is a number — judged from the first row only,
   a cheap guess rather than a full scan.

   `catalog` stopped calling this on 2026-08-27 — it now takes a profile that
   already answers the numeric question without a row in sight, see
   `loci.tablestore/column-profile`. Kept, and still exported, because
   `loci.tools/md-cell-val`'s pinned test (`a-salvaged-number-column-is-
   numeric-to-the-palette` in `tools_test.clj`) asserts against it directly:
   the thing it is checking is whether a salvaged markdown cell reads back as a
   number at all, over rows that were never landed in a store."
  [rows]
  (vec (for [[k v] (first rows) :when (number? v)] k)))

(defn cat-cols
  "Category columns: all-string columns with 2–12 distinct values —
   few enough to group by, more than one so grouping means something.

   `catalog` stopped calling this on 2026-08-27, for the same reason as
   `numeric-cols` above. Kept for its own test in `fnlib-test`; nothing else in
   `src/` calls it, checked 2026-08-27."
  [rows]
  (vec (for [k (keys (first rows))
             :let [vs (map k rows)]
             :when (and (every? string? vs) (<= 2 (count (distinct vs)) 12))]
         k)))

(defn- ->num [s] (try (Double/parseDouble (str s)) (catch Exception _ nil)))

(defn- filter-run [rows {:keys [col op value]}]
  (let [k (keyword col) vn (->num value)]
    (vec (filter (fn [r]
                   (let [v (get r k)]
                     (case op
                       "="  (if (and (number? v) vn) (== v vn) (= (str v) (str value)))
                       "≠"  (if (and (number? v) vn) (not (== v vn)) (not= (str v) (str value)))
                       ">"  (boolean (and (number? v) vn (> v vn)))
                       "<"  (boolean (and (number? v) vn (< v vn)))
                       "contains" (str/includes? (str/lower-case (str v)) (str/lower-case (str value)))
                       false)))
                 rows))))

(defn- agg-val [agg xs]
  (case agg
    "sum" (reduce + 0 xs)
    "avg" (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0)
    "min" (when (seq xs) (apply min xs))
    "max" (when (seq xs) (apply max xs))
    "count" (count xs)))

(defn- group-run [rows {:keys [by measure agg]}]
  (let [bk (keyword by) mk (keyword measure) agg (or agg "sum")
        ok (keyword (str agg "_" measure))]
    (vec (for [[g rs] (group-by bk rows)]
           {bk g ok (if (= agg "count") (count rs) (agg-val agg (filter number? (map mk rs))))}))))

(defn- top-run [rows {:keys [by n order]}]
  (let [k (keyword by) n (int (or (->num n) 10))
        s (sort-by #(let [v (get % k)] (if (number? v) v 0)) rows)]
    (vec (take n (if (= order "asc") s (reverse s))))))

(defn- needs-numeric [{:keys [numeric]}] (when (empty? numeric) "needs a numeric column"))

(defn- pivot-run [rows {:keys [rows_col cols_col measure agg]}]
  (let [rk (keyword rows_col) ck (keyword cols_col) mk (keyword measure) agg (or agg "sum")]
    (vec (for [[rv rs] (group-by rk rows)]
           (into {rk rv}
                 (for [[cv cs] (group-by ck rs)]
                   [(keyword (if (nil? cv) "n/a" (str cv)))
                    (if (= agg "count")
                      (count cs)
                      (agg-val agg (filter number? (map mk cs))))]))))))

(defn- delta-run [rows {:keys [col]}]
  (let [k (keyword col)]
    (vec (map (fn [prev r]
                (let [v (get r k) p (when prev (get prev k))]
                  (assoc r
                         :delta (when (and (number? v) (number? p)) (- v p))
                         :pct_change (when (and (number? v) (number? p) (not (zero? p)))
                                       (/ (* 100.0 (- v p)) p)))))
              (cons nil rows) rows))))

(defn- share-run [rows {:keys [col]}]
  (let [k (keyword col) tot (reduce + 0.0 (filter number? (map k rows)))]
    (vec (map #(assoc % :share_pct (when (and (number? (get % k)) (not (zero? tot)))
                                     (/ (* 100.0 (get % k)) tot)))
              rows))))

(def builtins
  [{:id "lib:filter" :label "Filter rows" :doc "keep rows where a column passes a test"
    :params [{:name "col" :type "col"}
             {:name "op" :type "choice" :options ["=" "≠" ">" "<" "contains"]}
             {:name "value" :type "text"}]
    :run filter-run
    :spec (fn [{:keys [col op value]}] {:filter {:col col :op op :val value}})}
   {:id "lib:group" :label "Group & aggregate" :doc "one row per group — sum/avg/min/max/count a column"
    :params [{:name "by" :type "col"} {:name "measure" :type "numcol"}
             {:name "agg" :type "choice" :options ["sum" "avg" "min" "max" "count"]}]
    :pred needs-numeric :run group-run
    :spec (fn [{:keys [by measure agg]}] {:group_by by :measure measure :agg (or agg "sum")})}
   ;; lib:top gets NO :spec, deliberately, despite carrying exactly the
   ;; :sort/:measure/:limit shape `query` already understands. Verified
   ;; 2026-08-27: `create-from-spec` always `(dissoc spec :limit)` — the
   ;; correct contract for `query_table :save_as`, where a display limit must
   ;; never truncate what gets saved — so pushing "Top 5" through it lands
   ;; every sorted row, not 5. `fn-preview`'s own `(assoc spec :limit 8)`
   ;; collides with the same field for the same reason: it would report
   ;; "top 1" as two rows out of two, not one. Both confirmed at the REPL
   ;; against a 2-row table with `:limit 1`. Until something teaches
   ;; `create-from-spec` to tell "N is the answer" apart from "N is a display
   ;; cap", lib:top stays on `:run`, over every row via `store+handle` same as
   ;; before — correct, just not pushed down.
   {:id "lib:top" :label "Top N" :doc "sort by a numeric column and keep the first N"
    :params [{:name "by" :type "numcol"} {:name "n" :type "num"}
             {:name "order" :type "choice" :options ["desc" "asc"]}]
    :pred needs-numeric :run top-run}
   {:id "lib:pivot" :label "Pivot" :doc "rows × columns grid — aggregate a measure by two categories"
    :params [{:name "rows_col" :type "catcol"} {:name "cols_col" :type "catcol"}
             {:name "measure" :type "numcol"} {:name "agg" :type "choice" :options ["sum" "avg" "count"]}]
    :pred (fn [{:keys [categorical] :as p}]
            (or (needs-numeric p)
               (when (< (count categorical) 2) "needs two category columns")))
    :run pivot-run}
   {:id "lib:delta" :label "Change vs previous row" :doc "adds delta and %-change columns, row over row"
    :params [{:name "col" :type "numcol"}]
    :pred (fn [{:keys [rows] :as p}]
            (or (needs-numeric p) (when (< rows 2) "needs at least two rows")))
    :run delta-run}
   {:id "lib:share" :label "Share of total" :doc "adds each row's % of the column total"
    :params [{:name "col" :type "numcol"}]
    :pred needs-numeric :run share-run}])

(defn catalog
  "The palette for one table: every builtin with :ok/:why (greyed functions
   stay visible — the reason teaches the vocabulary) and col params filled
   with the table's column options — taken from a PROFILE, never rows.

   `profile` is `{:cols :numeric :categorical :rows}`, exactly what
   `loci.tablestore/column-profile` answers without fetching a row. Before
   2026-08-27 this took `rows` — a 2,000-row sample on any table larger than
   that — and judged :numeric from the first row alone and :categorical by
   scanning the sample it was handed, so a verb was offered or greyed on a
   guess about a million-row table's other 998,000 rows."
  [{:keys [cols numeric categorical] :as profile}]
  (let [opts {"col"    (mapv name cols)
              "numcol" (mapv name numeric)
              "catcol" (mapv name categorical)}]
    (mapv (fn [{:keys [id label doc params pred]}]
            (let [why (when pred (pred profile))]
              {:id id :label label :doc doc :ok (nil? why) :why why
               :params (mapv #(if-let [o (opts (:type %))] (assoc % :options o) %) params)}))
          builtins)))

(defn spec-of
  "The view-spec a builtin's params imply, or nil when the verb cannot be
   pushed down. Nil is the signal to fall back to `:run` over rows.

   Only `lib:filter` and `lib:group` carry a `:spec`. `lib:pivot`, `lib:delta`
   and `lib:share` do not: DuckDB can express all three — `PIVOT`, `lag()`,
   `sum() OVER ()` — but each needs new grammar in the view-spec language, and
   the language grows when something hurts, not ahead of it. `lib:top` does
   not either, despite fitting the `:sort`/`:measure`/`:limit` shape exactly —
   see the comment on its builtin entry for why `:limit` there is not safe to
   push through `create-from-spec` yet."
  [fid params]
  (when-let [f (:spec (first (filter #(= fid (:id %)) builtins)))]
    (f params)))

(defn run-fn
  "Run builtin `fid` over rows with string-valued params. Returns
   {:rows [...]} on success or {:error \"...\"} — never throws; an
   empty result is an honest error, not an empty table."
  [fid rows params]
  (if-let [f (some #(when (= fid (:id %)) %) builtins)]
    (try
      (let [out ((:run f) rows params)]
        (if (and (seq out) (every? map? out))
          {:rows out}
          {:error "no rows came out — nothing to keep"}))
      (catch Exception e {:error (.getMessage e)}))
    {:error (str "unknown function: " fid)}))
