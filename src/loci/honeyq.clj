(ns loci.honeyq
  "A view-spec's QUESTION half, as HoneySQL data. Pure: no store, no lake, no
   connection, no clock.

   `loci.viewspec` keeps the SHAPE half — `:kind`, `:rendered`, chart series —
   and runs over the answer this produces, which is small by construction. That
   split is the whole point: the question goes to DuckDB, the shaping stays in
   Clojure, and neither one grew a second language.

   **Its own namespace, not part of `viewspec`.** `viewspec` is required by the
   render path (`tools`, `server`); this is required by the storage path (`lake`,
   `tablestore`). Folding it into `viewspec` would make storage depend on render.

   **Two traps the 2026-08-27 measurement gate found, both of which HoneySQL
   emits rather than raises:**

   · `{:from [R]}` where `R` is a relation formats as `FROM raw AS \"…\"`,
     because `:from` reads a two-element vector as a table/alias pair. The
     relation must arrive already nested — `loci.lake/relation-of` builds it as a
     subquery map, and `[[relation :t]]` is then ordinary HoneySQL.
   · `[:over [[:sum :x] {} :alias]]` formats as `over AS SUM(x)  alias`. The
     alias belongs OUTSIDE the `:over`: `[[:over [[:sum :x] {}]] :alias]`.

   Neither is caught by a reader and neither raises. What catches them is
   `->count`, which DuckDB parses before any result is trusted."
  (:require [clojure.string :as str]))

(def aggs
  "The aggregates the spec language admits. `min` and `max` are new on
   2026-08-27, so `lib:group`'s five choices all fit the one language."
  #{"sum" "avg" "count" "min" "max"})

(defn- kw [x]
  (when (and x (not (str/blank? (str x)))) (keyword (str x))))

(defn- where-clause
  "One filter as a HoneySQL predicate, or nil.

   `contains` is `LOWER(CAST(col AS VARCHAR)) LIKE '%v%'` and not `ILIKE`: the
   column may be numeric, and `ILIKE` on a DOUBLE is a binder error where the
   cast is not. Lower-casing both sides rather than relying on the collation
   keeps the two stores' answers identical — `loci.fnlib/filter-run` already
   lower-cases both sides in Clojure."
  [{:keys [col op val]}]
  (when-let [c (kw col)]
    (case op
      "="        [:= c val]
      "≠"        [:<> c val]
      ">"        [:> c val]
      "<"        [:< c val]
      "contains" [:like [:lower [:cast c :varchar]]
                  (str "%" (str/lower-case (str val)) "%")]
      nil)))

(defn ->honey
  "`spec` — `group_by` / `measure` / `agg` / `filter` / `sort` / `limit` — against
   `relation`, as one HoneySQL map.

   `relation` must already be a HoneySQL expression that can sit inside
   `[[relation :t]]`. `loci.lake/relation-of` builds it; a test may pass any
   subquery map."
  [spec relation]
  (let [gb   (kw (:group_by spec))
        meas (kw (:measure spec))
        agg  (let [a (or (:agg spec) "sum")] (if (aggs a) a "sum"))
        ycol (keyword (or (some-> meas name) agg))
        sel  (if gb
               [gb [(if (= "count" agg) [:count :*] [(keyword agg) meas]) ycol]]
               :*)
        skey (if gb ycol (or meas ycol))]
    (cond-> {:select sel :from [[relation :t]]}
      gb              (assoc :group-by [gb])
      (:filter spec)  (as-> m (if-let [w (where-clause (:filter spec))] (assoc m :where w) m))
      (:sort spec)    (assoc :order-by [[skey (if (= "desc" (:sort spec)) :desc :asc)]])
      (:limit spec)   (assoc :limit (:limit spec)))))

(defn ->count
  "`SELECT count(*) FROM ( <q> )` — the size of the answer, and the parse check.

   Measured 2026-08-27 over 1,000,000 rows: `GROUP BY` costs 20 ms and this costs
   **6 ms**, because DuckDB answers it from the group cardinality and never
   computes the sums. So counting first is the cheapest thing in the design, not
   a cost to justify."
  [q]
  {:select [[[:count :*] :n]] :from [[q :c]]})
