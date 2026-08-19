(ns loci.source
  "Connecting an external source to loci: describe it, land it, refresh it.

   The facade, in the sense CLAUDE.md's *one writer, three ways to reach
   storage* means — public, and meant to be called from a REPL attached to the
   live server as well as from the HTTP routes. `describe` **is** the dry run,
   which is why nothing here takes a `:dry-run?` flag.

   `loci.sourcespec` holds the pure half: URI → location, source → SQL."
  (:require [clojure.string :as str]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn describe
  "The columns `src` has, and the relation expression that reads them:
   `{:cols [{:raw \"Total Revenue ($)\" :col :total_revenue :type \"BIGINT\"} …]
     :relation \"read_csv('…')\"}`

   One query, doing three jobs at once, which is why materialise and passthrough
   cannot disagree about what the columns are:

   1. **Proof the source reads.** A bad path fails here, not at first render.
   2. **The raw → normalized column map**, through `sub/col-kw`. Both modes
      build their projection from this exact list.
   3. **The schema a preview shows**, before anything is committed.

   Throws with `check-names!`'s ex-data when two headers would become one
   column — the same refusal `normalize-rows` makes for rows."
  [lk src]
  (let [rel  (ssp/relation src (ssp/locate (:uri src)))
        rows (try (lake/q lk (str "DESCRIBE SELECT * FROM " rel))
                  (catch java.sql.SQLException e
                    (throw (ex-info (str "loci cannot read " (:uri src) " — " (ssp/duckdb-said e))
                                    {:uri (:uri src) :relation rel} e))))
        cols (mapv (fn [r] {:raw  (:column_name r)
                            :col  (sub/col-kw (:column_name r))
                            :type (:column_type r)})
                   rows)]
    ;; Kept, and **not** the guard against an empty file, which was what it was
    ;; written for. Measured 2026-08-19: DuckDB's auto-detection never returns
    ;; zero columns for one. A zero-byte CSV and a newline-only CSV both describe
    ;; as a single fabricated `column0 VARCHAR`; an empty JSON file and `[]` both
    ;; describe as a single `json JSON`. So connecting an empty source SUCCEEDS,
    ;; with one garbage column and no rows — undoable like any other `:put`, and
    ;; nobody has asked for it to be refused.
    ;;
    ;; It stays because it costs one line and `readers` will grow: a format whose
    ;; reader is less inventive than `read_csv` can return nothing, and the
    ;; alternative to this line is `create-from-relation!` emitting `SELECT  FROM`
    ;; and DuckDB answering with a bare parser error.
    (when (empty? cols)
      (throw (ex-info (str "the source has no columns: " (:uri src)) {:uri (:uri src)})))
    (tbl/check-names! (map :raw cols))
    {:cols cols :relation rel}))

(def ^:private slug-max
  "How long the readable half of a minted id may be.

   A bound, not a preference. `connect!` is the first path in loci from
   **user-supplied text** to a lake table name, and `lake/table-name` renders
   that id as a *filesystem path component* — DuckLake writes one directory per
   table. ext4 caps a name component at 255 bytes, and `table-name` spends 11 of
   them on its `t_` prefix and `_<8 hex>` suffix, leaving 244.

   Measured 2026-08-19: a 500-character title took `connect!` in `:materialise`
   mode to

     IO Error: Failed to create directory …/t_tbl_aaa…aaa_6f4ec946:
     File name too long

   which names neither loci nor the title that caused it. 64 is well inside the
   headroom and still long enough that a slug reads as its title; the id stays
   unique regardless, because uniqueness lives in the `-2`/`-3` suffix here and
   in a content hash in `table-name`."
  64)

(defn- slug
  "A title → the readable half of an object id: `\"Q3 Revenue ($)\"` → `revenue`
   is wrong, `q3_revenue` is right. Letters, digits and underscores only."
  [title]
  (let [s (-> (str title) str/lower-case
              (str/replace #"[^a-z0-9]+" "_")
              (str/replace #"^_+|_+$" ""))
        s (cond-> s (> (count s) slug-max) (subs 0 slug-max))
        s (str/replace s #"_+$" "")]
    (if (str/blank? s) "table" s)))

(defn- mint-id
  "`tbl:<slug>`, suffixed `-2`, `-3`… until it names nothing in `st`.

   Readable rather than `tbl:csv-<count>-<rand>`, which is one of the
   count-plus-random minters `loci.corpus` already refuses to go through: a
   count falls when an object is removed and then mints an id that is taken.

   **It recycles a deleted id, and that is accepted rather than overlooked.**
   The check is `contains?` against the objects that exist *now*, so deleting
   `tbl:dup-2` and connecting again mints `tbl:dup-2` — measured 2026-08-19.
   Nothing live ever collides, which is the property that matters, and the two
   things that could care do not: the log is append-only, so an `as-of` before
   the delete still resolves the old object and one after resolves the new; and
   `loci.chunks` is derived and staleness there is by content hash, so the next
   sweep rebuilds. Never recycling would mean walking the whole log for every
   id the store has ever named, which is a cost against a failure nobody has
   had."
  [st title]
  (let [objs (sub/objects st)
        base (str "tbl:" (slug title))]
    (if-not (contains? objs base)
      base
      (first (for [n (iterate inc 2)
                   :let [id (str base "-" n)]
                   :when (not (contains? objs id))]
               id)))))

(defn- now-iso [] (str (java.time.Instant/now)))

(defn- land!
  "`src` + `describe`'s answer → the handle to record, for either mode.

   The whole difference between the two modes is these four lines, which is the
   point of the framework: materialise runs one CREATE, passthrough runs
   nothing and keeps the recipe."
  [lk id src d at]
  (if (= :passthrough (:mode src))
    (cond-> {:store :source :uri (:uri src) :format (:format src)
             :cols (mapv :col (:cols d))
             :rows (:n (first (lake/q lk (str "SELECT count(*) AS n FROM " (:relation d)))))
             :at at}
      ;; `:options` are load-bearing on the handle, not decoration: every read
      ;; rebuilds the relation from the handle alone, so a source connected with
      ;; `{:delim ";"}` that did not record it would read right once and then
      ;; fall back to the sniffer forever after. `cond->` rather than an
      ;; unconditional key so a source with no options gets no `:options nil`.
      (seq (:options src)) (assoc :options (:options src)))
    (lake/create-from-relation! lk id (:cols d) (:relation d))))

(defn- source-of
  "The `:source` map to record on the object."
  [src at]
  (cond-> {:uri (:uri src) :format (:format src) :mode (or (:mode src) :materialise) :at at}
    (seq (:options src)) (assoc :options (:options src))))

(defn connect!
  "Read `src` and commit a `:kind :table` object holding its rows or its recipe.

   `src` is `{:uri … :format … :mode … :title … :options …}`. `:mode` defaults
   to `:materialise`. When `space` names a space, a notebook cell referencing
   the new table is appended in the same transaction — `import-csv!`'s shape,
   kept.

   Returns `{:id … :cols … :rows … :mode …}`. Throws whatever `describe` throws:
   a bad location, an unknown format, or two headers that would become one
   column all fail here, before anything is committed."
  ([st src] (connect! st src nil))
  ([st src space]
   (let [lk   @lake/lake
         mode (or (:mode src) :materialise)
         src  (assoc src :mode mode)
         d    (describe lk src)
         at   (now-iso)
         id   (mint-id st (or (not-empty (:title src)) "Table"))
         h    (land! lk id src d at)
         obj  {:id id :kind :table :title (or (not-empty (:title src)) "Table")
               :value h :source (source-of src at)}
         base [{:op :put :id id :value obj}]
         ;; `import-csv!`'s shape means its GUARD too, not just its `conj`: only
         ;; a real `:space` may receive a cell append. An existence check alone
         ;; lets a table id through, and the append on its vector `:value`
         ;; poisons the log — that is the comment standing over `server/space?`,
         ;; which `import-csv!` calls. Reproduced rather than called, because
         ;; `space?` is private to `loci.server` and `loci.server` is what
         ;; requires this namespace; a call the other way would close a cycle.
         evs  (if (and space (= :space (:kind (sub/object st space))))
                (conj base (nb/append-cell-event st space {:ref id}))
                base)]
     (sub/commit! st {:op :tx :events evs})
     {:id id :cols (:cols h) :rows (:rows h) :mode mode})))

(defn refresh!
  "Re-run the recipe recorded on `id` and commit the result.

   A `:put`, never a mutation. A materialised refresh creates a NEW lake table
   and leaves the old one in place, so `undo!` walks back to a handle whose rows
   are still there — and `loci.lakegc`, which already groups every version of a
   table by object, is what bounds them. A passthrough refresh moves no bytes;
   it re-describes, and moving `:at` is what lets the synopsis change."
  [st id]
  (let [o (sub/object st id)]
    (when-not (:source o)
      (throw (ex-info (str id " was not connected from a source, so there is nothing to re-run")
                      {:id id})))
    (let [lk  @lake/lake
          src (:source o)
          d   (describe lk src)
          at  (now-iso)
          h   (land! lk id src d at)]
      (sub/commit! st {:op :put :id id
                       :value (assoc o :value h :source (source-of src at))})
      {:id id :cols (:cols h) :rows (:rows h) :mode (:mode src)})))
