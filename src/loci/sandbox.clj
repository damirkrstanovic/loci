(ns loci.sandbox
  "What the `sci` sandbox is handed. Before 2026-08-27 `compute-clj!` bound one
   symbol — `rows`, a `mold-cap` sample fetched through `hydrate` — and
   model-written Clojure folded the sample and the result was committed and
   reported as the whole table's.

   **`rows` does not exist here.** That is the enforcement, and it is the same
   trick used twice more below: make the honest path the only path, rather than
   the one a prompt asks nicely for. A model cannot fold a sample because there
   is no sample to fold; the easy path is now a query.

   Five things are bound, and they line up one-to-one with `loci.tablestore`'s
   `TableStore` protocol — the sign that this is the protocol exposed to `sci`
   rather than a new idea:

   | binding                  | protocol            |
   |---------------------------|---------------------|
   | `table` / `tables`        | `relation-of`, pinned |
   | `(q hq)` / `(q hq lim)`   | `query`              |
   | `(q-reduce hq f init)`    | `lake/reduce-rows`   |
   | `(q-all hq)`               | `rows` with no `:limit` |
   | `(keep! title spec-or-rows)` | `put-rows!` / `create-from-sql!` |

   **Snapshot pinning.** `table` and every entry of `tables` are ordinary
   HoneySQL relation maps, resolved ONCE — inside `context`, before any of the
   bindings run — via `loci.lake/relation-of`, which bakes the handle's
   `:snapshot` into the FROM text (`t_xyz AT (VERSION => n)`). A relation is a
   plain Clojure value handed to `sci`, not a marker `q` resolves per call, so
   there is structurally nowhere for a later write to leak in: every query in
   one run reads the instant `context` was built, and a report cannot
   contradict itself between two of its own paragraphs. Every other read in
   loci already names the snapshot it wants; this is where that rule would
   otherwise have lapsed.

   **A non-lake table still gets a query surface.** `q` builds SQL, so every
   table needs a lake-backed handle — but `compute-clj!` runs over any table,
   including a legacy one whose rows live directly in the event (`:value` is a
   plain vector of maps, not a handle). Those are landed in the REAL lake with
   `tbl/put-rows!`, not in a throwaway `InlineTable` — `InlineTable` has no SQL
   surface, so `q` could not query it, only fold it in Clojure, which is
   exactly the sample-and-fold failure this namespace exists to end. This is
   cheap and safe: the 2026-08-16 big-content spec measured legacy inline
   tables at a MEDIAN of 24 rows, largest 1,000. It leaves behind an
   unreferenced lake table that `loci.lakegc` reclaims — the same documented
   class of leak `keep!` has, below.

   **`keep!` accumulates and never commits.** It appends a `:put` to `(:events
   ctx)`, an atom, and touches the substrate not at all. The run's events land
   in ONE `:tx` at the end — Task 10's job, and exactly how `compute-clj!`
   already commits its `:fn` object and its table together. Two things fall
   out rather than being engineered: an aborted run commits NOTHING —
   `suggest!`'s rule, unchanged — and `undo!` removes a report and every table
   it made in one step.

   **The leak this does not prevent, named rather than engineered around.**
   `keep!` with a HoneySQL spec calls `lake/create-from-sql!`, which issues
   `CREATE TABLE … AS` and writes Parquet AT ONCE — a side effect outside the
   event log, before the run's `:tx` ever commits. An aborted run (the model
   erred, the user declined) leaves that table sitting in the lake with
   nothing in the log pointing at it. `loci.lakegc` already reclaims tables no
   live event references, so this is garbage, not corruption — worth writing
   down, not worth building a rollback for.

   **`mint-id` is injected, not minted here.** An earlier draft of this
   namespace minted derived-table ids with `(str \"tbl:derived-\" (count
   @events) (System/nanoTime))` — a fresh rule, invented on the spot.
   `server/next-id` already owns the right one: max-plus-one across the whole
   store with an existence loop, which exists BECAUSE a count-based minter
   silently overwrote three notebooks on 2026-07-23. `loci.sandbox` cannot
   require `loci.server` — `server` will require `sandbox`, and that is a
   cycle — so `context` takes a `mint-id` function, `(fn [prefix] …)`, and the
   caller (Task 10, in `server.clj`) supplies `next-id` partially applied to
   its store. This namespace does not add a third copy of that rule."
  (:require [clojure.walk :as walk]
            [honey.sql :as sql]
            [loci.honeyq :as hq]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(def per-row-micros
  "Measured 2026-08-27 on DuckDB 1.5.5 over 1,000,000 rows of four columns:
   27,335 ms total to cross JDBC into Clojure maps, of which 82% (22,297 ms) is
   the driver advancing the cursor and reading cells and 18% is building the
   maps themselves — the opposite of what this design first assumed, which was
   that Clojure's own allocation was the cost. Expressed per unit: about 5 µs
   to advance one row and 3.7 µs to read one cell.

   `estimate` below is the one caller: `rows * (row + cols * cell)`, in
   microseconds, converted to seconds. One multiplication over numbers a
   handle already carries — not a budget, not a governor, nobody asked for
   either — so a caller can say \"10,000,000 rows x 36 columns — about 23
   minutes\" before `q-all` runs, exactly as a capped `q` result already says
   how many rows it left out."
  {:row 5.0 :cell 3.7})

(defn- fmt
  "One HoneySQL map, formatted with every value inlined, as the single SQL
   string `lake/q` and friends take.

   Inlined rather than parameterised because loci is single-user and
   personal — decided 2026-08-27, the same call `loci.lake/run-honey` makes.
   `sql/format` with `{:inline true}` always returns a one-element vector;
   `first` is not an approximation of that, it is reading the whole thing."
  [m]
  (first (sql/format m {:inline true})))

(defn- table-ids
  "Every table id in scope for a run over `id`: `id` itself, plus — when
   `space` names a notebook — every cell in it whose object is `:kind :table`.

   Narrower than `server/agent-ctx`'s rule on purpose. `agent-ctx` falls back
   to EVERY object in the store when no space is given, because a chat has no
   inherent table at all. A sandbox run always has exactly one — `id` — and
   only grows when a notebook groups more around it, so the no-space case here
   is `[id]`, not the whole store.

   `id` first and `distinct` after, so `(get tables id)` and `table` always
   name the same relation even if a notebook cell also happens to reference
   it."
  [st id space]
  (let [refs (if-let [sp (and space (sub/object st space))]
               (keep :ref (nb/cells-of sp))
               [])]
    (->> refs
         (filter #(= :table (:kind (sub/object st %))))
         (cons id)
         distinct
         vec)))

(defn- table-handle
  "`[(force lake/lake) handle]` for object `o`, or nil when `o` is not a
   table.

   Deliberately NOT `tbl/store+handle`: that function lands a legacy
   inline-rows table in a throwaway `InlineTable`, which answers `rows` but
   has no SQL surface — `q` cannot run HoneySQL against it, only fold it in
   Clojure, which is the exact sample-and-fold failure this namespace exists
   to end. So a legacy table is landed in the REAL lake instead — see the
   namespace docstring for the cost and the leak that follows."
  [o]
  (let [v (:value o)]
    (cond
      (tbl/handle? v) [(force lake/lake) v]
      (and (sequential? v) (seq v) (every? map? v))
      [(force lake/lake) (tbl/put-rows! (force lake/lake) (:id o) v)]
      :else nil)))

(defn estimate
  "What `q-all` over `id` will cost, before it runs — `{:rows :cols
   :seconds}`, or nil if `id` is not one of this context's tables.

   Not a budget and not a governor; nobody asked for either. It reads
   `row-count` and the column count straight off the handle `context` already
   resolved, and multiplies by `per-row-micros`. It informs, exactly as a
   capped `q` result's `:total`/`:capped` inform, and it costs one
   multiplication."
  [ctx id]
  (when-let [[ts h] (get (:tables ctx) id)]
    (let [rows (long (tbl/row-count ts h))
          cols (count (:cols h))]
      {:rows rows :cols cols
       :seconds (/ (* rows (+ (:row per-row-micros) (* cols (:cell per-row-micros))))
                   1e6)})))

(defn context
  "The sandbox context for a run over `id`, scoped to `space`'s tables, plus
   any `extra-ids` — ids to pull in even though `space` does not name them
   (nil-able, and nothing when omitted).

   `extra-ids` exists for exactly one caller: `server/rerun!` refreshing a
   report outside any notebook, over the `:reads` its ORIGINAL run recorded.
   `space` cannot stand in for that — a report's `:fn` object never stores
   which space it ran in, only `:reads` — so without this a multi-table
   report's refresh would only ever see `id`, the same gap `:reads` exists to
   close.

   `mint-id` is `(fn [prefix] …)` — see the namespace docstring for why it is
   injected rather than owned here.

   => `{:bindings {…} :events <atom> :reads <atom> :queries <atom> :tables {id
   [ts h]}}`. Pass `:bindings` to `sci/eval-string`; `events`, `reads` and
   `queries` are read after the run, by `(events ctx)` / `(reads ctx)` /
   `(queries ctx)` below.

   Every table this context can see is resolved to a handle EXACTLY ONCE,
   right here, before any binding runs — see \"Snapshot pinning\" in the
   namespace docstring. `table`/`tables` are the resulting HoneySQL relations,
   plain values; nothing downstream re-resolves them."
  [st id space mint-id & [extra-ids]]
  (let [ids       (vec (distinct (concat (table-ids st id space) extra-ids)))
        tables    (into {} (keep (fn [i] (when-let [o (sub/object st i)]
                                            (when-let [th (table-handle o)]
                                              [i th])))
                                  ids))
        relations (into {} (for [[i [_ h]] tables] [i (lake/relation-of h)]))
        rel->id   (into {} (for [[i r] relations] [r i]))
        events    (atom [])
        reads     (atom #{})
        queries   (atom [])
        ;; The `:reads` bookkeeping the plan first drafted matched with
        ;; `(str/includes? (pr-str hq) (pr-str relation))` — a relation
        ;; appearing inside a string literal in the query would have counted.
        ;; This walks the HoneySQL STRUCTURE and collects the ids whose
        ;; relation value appears by `=` — exact, and immune to what the
        ;; query's strings happen to contain.
        note!     (fn [hq]
                    (walk/postwalk (fn [x]
                                     (when-let [i (get rel->id x)] (swap! reads conj i))
                                     x)
                                   hq))
        count-of  (fn [hq] (long (:n (first (lake/q (force lake/lake)
                                                     (fmt (hq/->count (dissoc hq :limit))))))))
        run-q     (fn [hq lim]
                    (note! hq)
                    (let [total  (count-of hq)
                          rows   (vec (lake/q (force lake/lake) (fmt (assoc hq :limit lim))))
                          capped (> total (count rows))]
                      (swap! queries conj {:total total :count (count rows) :capped capped})
                      (with-meta rows {:total total :capped capped})))]
    {:tables tables
     :events events
     :reads  reads
     :queries queries
     :bindings
     {'table  (get relations id)
      'tables relations

      ;; `(q hq)` uses hq's own `:limit` if the model wrote one — that is how
      ;; a deliberately small ask (`q-carries-the-total-in-metadata`) stays
      ;; small — and falls back to `tbl/max-query-rows` otherwise. `(q hq
      ;; lim)` always uses the explicit `lim`, overriding anything embedded in
      ;; hq: an explicit call-site limit is stronger than whatever the map
      ;; already said.
      'q (fn ([hq] (run-q hq (or (:limit hq) tbl/max-query-rows)))
           ([hq lim] (run-q hq lim)))

      'q-reduce (fn [hq f init]
                  (note! hq)
                  (lake/reduce-rows (force lake/lake) (fmt hq) f init))

      'q-all (fn [hq]
               (note! hq)
               (vec (lake/q (force lake/lake) (fmt hq))))

      ;; `keep!` branches on shape, the idiom `hydrate`/`table-object` already
      ;; use elsewhere: a map is a HoneySQL query and goes through
      ;; `create-from-sql!` so no row ever enters the JVM; a sequential is
      ;; rows already in hand and goes through `table-object`. Either way the
      ;; new object is only APPENDED to `events` — see the namespace
      ;; docstring's "accumulates and never commits".
      'keep! (fn [title spec-or-rows]
               (let [nid (mint-id "tbl:derived-")
                     obj (if (map? spec-or-rows)
                           {:id nid :kind :table :title title
                            :value (lake/create-from-sql! (force lake/lake) nid (fmt spec-or-rows))
                            :from id}
                           (tbl/table-object (force lake/lake) nid title (vec spec-or-rows) {:from id}))]
                 (swap! events conj {:op :put :id nid :value obj})
                 nid))}}))

(defn events
  "The `:put` events `keep!` accumulated during this run, oldest first. Task
   10 wraps these — plus the `:fn` object itself — in one `:tx` and commits
   once."
  [ctx]
  @(:events ctx))

(defn reads
  "The set of table ids `q`, `q-reduce` and `q-all` actually resolved a
   relation for during this run — not every table `context` could see, only
   the ones a query touched. `server`'s `:fn` object stores this as `:reads`,
   and both readers of it are load-bearing: `rerun!` treats a table in
   another object's `:reads` as a dependency edge, exactly like `:from`, so a
   multi-table report refreshes when ANY input changes — and a report
   refresh re-derives `context` with these ids as `extra-ids`, so a table
   read only via `tables` (never `:from`) is still in scope the second time."
  [ctx]
  @(:reads ctx))

(defn queries
  "The outcome of every `(q …)` call this run made, oldest first — `{:total
   :count :capped}`, the same numbers `run-q` also stamps onto its rows as
   metadata.

   Exists because metadata does not survive what a model ordinarily does with
   `q`'s result — `mapv`, `map`, `for`, a renamed key all drop it (confirmed
   at the REPL: `(meta (mapv identity (with-meta [{:a 1}] {:capped
   true})))` => nil) — so a report block built from a reshaped capped result
   carries no mark a caller could read off the ROWS THEMSELVES. This is a
   property of the RUN instead: `server/capped-block`'s count-match check
   refuses an inline block whose row count equals a capped call's count,
   which a reshape that preserves row count (every one of the examples
   above) cannot defeat, because the model's code never sees this atom and
   so cannot touch it."
  [ctx]
  @(:queries ctx))
