(ns loci.tablemigrate
  "Convert inline tables — rows inside the event — into lake handles.

   Dry run by default, in the shape of `loci.factmigrate` and `loci.corpus`:
   it reports what it would do and refuses what it cannot account for, and a
   refusal comes back in the report rather than as a thrown exception, because
   a migration that dies halfway through tells you less than one that finishes
   and names what it skipped.

   Meant to be called from a REPL attached to the running server — way (2) of
   the three routes to storage. It appends ordinary `:assoc` events, so the
   conversion is itself undoable."
  (:require [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- inline-tables
  "Row-bearing objects whose rows are still in the log.

   Both `:table` and `:metric`: `chunks.clj` treats them as one kind, `hydrate`
   keys off the handle rather than the kind, and `content/seed!` writes both, so
   filtering on `:table` alone would leave two objects inline on every fresh
   install. By kind and never by shape — five `:report` objects have
   vector-of-maps values that a shape filter would sweep in, and DuckDB would
   build LIST/STRUCT columns out of them.

   `report:qbr` also holds a duplicate copy of `metric:mrr`'s rows inside a
   chart spec. That is not a missed migration: a report's `:value` is a
   document, and the rows embedded in it are part of the document."
  [st]
  (->> (sub/objects st) vals
       (filter #(#{:table :metric} (:kind %)))
       (remove #(tbl/handle? (:value %)))))

(defn- widened?
  "True when `b` is `a` after nothing but numeric widening: same number, wider
   type. `100` → `100.0`, and nothing else.

   Exact, via `bigdec`, rather than by coercing both sides to `double` and
   comparing. Above 2^53 that coercion equates genuinely different numbers —
   `9007199254740993` and `9007199254740992.0` become the same double and the
   migration would report a lossless round-trip over a value it had lost. The
   decimal expansions differ, so this says no.

   `try` because `bigdec` throws on NaN and infinity, and a column DuckDB
   returns as a Double can hold both. Anything that cannot be compared as a
   decimal is not widening."
  [a b]
  (and (number? a) (number? b)
       (try (== (bigdec a) (bigdec b))
            (catch Exception _ false))))

(defn- widened-eq?
  "Row vectors that differ by nothing but `widened?` cells.

   Cell by cell rather than by normalizing both sides, so that everything else
   a round-trip could change still shows up as a difference: a row count, a key
   set (which is how a ragged table's explicit nils would surface), a string, a
   nil."
  [in back]
  (and (= (count in) (count back))
       (every? (fn [[ra rb]]
                 (and (= (set (keys ra)) (set (keys rb)))
                      (every? (fn [k]
                                (let [x (get ra k) y (get rb k)]
                                  (or (= x y) (widened? x y))))
                              (keys ra))))
               (map vector in back))))

(defn- convert!
  "One object, or a refusal. Never throws.

   Returns `{:id … :rows n}` on success, `{:id … :why …}` on refusal, and adds
   `:widened true` when the rows came back equal only after numeric widening —
   `read_json_auto` turns a `100` sitting beside a `43.2` into `100.0`, which is
   a real change to the data and is reported rather than hidden, but is not a
   reason to leave a table inline forever.

   `tbl/table-value` rather than `tbl/table-object`: the object already exists,
   only its `:value` moves, and `table-object` hardcodes `:kind :table`, so the
   two `:metric` objects would silently become tables. `assoc` over `:value`
   also keeps the extra keys the live objects carry — `:from` and `:via`.

   **A table whose rows failed to round-trip is not rolled back.** By then the
   `:assoc` is committed, the rows are in the lake and readable, and `undo!` is
   right there. Rolling back would mean a second write to repair a first, which
   is more machinery than a case measured at 0 of 31 — so such an object comes
   back in `:refused` meaning *converted and did not verify*, and the `:why`
   says so."
  [st ts o]
  (try
    (let [h (tbl/table-value ts (:id o) (:value o))]
      (sub/commit! st {:op :assoc :id (:id o) :path [:value] :value h})
      ;; verify against what is DURABLE, not against the rows we just held in
      ;; RAM — the 2026-08-15 migration reported "verified" while its target
      ;; silently lacked an event, because it compared to a stale in-process
      ;; snapshot.
      (let [back (vec (tbl/rows ts (:value (sub/object st (:id o))) {}))
            in   (vec (:value o))]
        (cond
          (= in back)             {:id (:id o) :rows (count back)}
          (widened-eq? in back)   {:id (:id o) :rows (count back) :widened true}
          :else {:id (:id o)
                 :why "rows did not round-trip — the handle is committed; undo! to revert"
                 :expected (count in) :got (count back)})))
    (catch Exception e {:id (:id o) :why (.getMessage e)})))

(defn migrate-tables!
  "Report what would change; with `{:live? true}`, change it.

     :mode    :dry-run | :live
     :tables  how many were (or would be) converted
     :rows    how many rows they hold
     :kinds   {:table n :metric n} over the candidates
     :widened ids whose rows came back equal only after numeric widening
     :refused [{:id … :why …} …] — an empty table, a column-name collision, a
              nested column, or a round-trip that did not verify"
  ([st ts] (migrate-tables! st ts {}))
  ([st ts {:keys [live?]}]
   (let [cands (inline-tables st)
         {ok true empties false} (group-by #(boolean (seq (:value %))) cands)
         empties (mapv (fn [o] {:id (:id o) :why "no rows"}) empties)
         done    (when live? (mapv #(convert! st ts %) ok))
         bad     (filterv :why done)]
     {:mode    (if live? :live :dry-run)
      :tables  (if live? (- (count ok) (count bad)) (count ok))
      :rows    (reduce + 0 (map #(count (:value %)) ok))
      :kinds   (frequencies (map :kind cands))
      :widened (mapv :id (filter :widened done))
      :refused (into empties bad)})))
