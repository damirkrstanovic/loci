(ns loci.viewspec
  "Deterministic interpreter for agent-proposed view-specs.

   The agent emits a small declarative spec (group-by / measure / agg / filter /
   sort / limit / columns / kind). This namespace *executes* it against rows —
   no model in the loop, fully reproducible. That split (agent proposes the
   spec, the substrate runs it) is what keeps molding-by-description trustworthy."
  (:require [clojure.string :as str]))

(defn- num [x] (if (number? x) x (try (Double/parseDouble (str x)) (catch Exception _ nil))))
(defn- round2 [x] (Double/parseDouble (String/format java.util.Locale/US "%.2f" (to-array [(double x)]))))
(defn- kw [x] (when (and x (not (str/blank? (str x)))) (keyword (str x))))

(defn question
  "The question half of a spec, over rows: filter → group/aggregate → sort.
   **Not limit** — the caller applies that, because it needs the unlimited count
   to report `:total` honestly.

   Extracted from `interpret` on 2026-08-27 so `InlineTable/query` and the shape
   half share ONE implementation of the language rather than two that drift.

   **Grouped rows carry String keys**, because that is what `interpret` has always
   rendered and the shell reads. `InlineTable/query` normalizes them to keywords;
   this does not, because changing it would change what the shell receives."
  [spec rows]
  (let [gb      (kw (:group_by spec))
        measure (kw (:measure spec))
        agg     (or (:agg spec) "sum")
        rows    (if-let [{:keys [col op val]} (:filter spec)]
                  (let [c (kw col)]
                    (filterv (fn [r]
                               (let [v (get r c)]
                                 (case op
                                   "="  (= (str v) (str val))
                                   "≠"  (not= (str v) (str val))
                                   ">"  (boolean (when-let [a (num v)] (when-let [b (num val)] (> a b))))
                                   "<"  (boolean (when-let [a (num v)] (when-let [b (num val)] (< a b))))
                                   ;; Lower-cases BOTH sides, matching `honeyq`'s
                                   ;; `LOWER(CAST(c AS VARCHAR)) LIKE '%v%'` and
                                   ;; `fnlib/filter-run`, so the two stores answer
                                   ;; a `contains` identically.
                                   "contains" (str/includes? (str/lower-case (str v))
                                                             (str/lower-case (str val)))
                                   true)))
                             rows))
                  (vec rows))
        ycol    (name (or measure (keyword agg)))
        agged   (if gb
                  (->> rows (group-by gb)
                       (mapv (fn [[g rs]]
                               (let [vs (keep #(num (get % measure)) rs)
                                     m  (case agg
                                          "count" (count rs)
                                          "avg"   (round2 (/ (reduce + vs) (double (max 1 (count vs)))))
                                          "min"   (when (seq vs) (apply min vs))
                                          "max"   (when (seq vs) (apply max vs))
                                          (round2 (reduce + vs)))]
                                 (array-map (name gb) (str g) ycol m)))))
                  rows)]
    (if-let [s (:sort spec)]
      (let [key     (if gb ycol (or (some-> measure name)
                                    (name (first (keys (first agged))))))
            ;; **Both key types, and that is the bug this fixes.** A grouped row
            ;; is built by `(array-map (name gb) …)` and carries STRING keys,
            ;; while an ungrouped row passes through with its original keywords.
            ;; Looking up only `(keyword key)` made every grouped sort key nil,
            ;; so every row compared equal: "asc" returned `group-by`'s arbitrary
            ;; order and "desc" returned it reversed. Found 2026-08-27, when
            ;; `Lake/query` began sorting the same spec correctly in SQL and the
            ;; two stores disagreed.
            at      (fn [r] (let [v (get r key ::none)]
                              (if (= ::none v) (get r (keyword key)) v)))
            ordered (sort-by #(or (num (at %)) (str (at %))) agged)]
        (vec (if (= s "desc") (reverse ordered) ordered)))
      agged)))

(defn interpret
  "A spec's answer, shaped by `:kind`. The question goes to `question`; what is
   left here is limit, column projection and the four render shapes."
  [spec rows]
  (let [gb      (kw (:group_by spec))
        measure (kw (:measure spec))
        agg     (or (:agg spec) "sum")
        kind    (keyword (or (:kind spec) "table"))
        ycol    (name (or measure (keyword agg)))
        sorted  (question spec rows)
        limited (if-let [l (:limit spec)] (vec (take l sorted)) sorted)
        out     (if-let [cols (:columns spec)]
                  (mapv #(select-keys % (map keyword cols)) limited)
                  limited)]
    (case kind
      :chart {:kind :chart
              :rendered {:chart (or (:chart spec) "bar")
                         :x (name (or gb (first (keys (first out)))))
                         :y (if gb ycol (name (last (keys (first out)))))
                         :rows out}}
      :list  {:kind :list  :rendered (mapv (fn [r] (str/join " — " (map str (vals r)))) out)}
      :cards {:kind :cards :rendered out}
      {:kind :table :rendered out})))
