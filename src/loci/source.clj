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
   column — the same refusal `normalize-rows` makes for rows.

   **This is also where a remote connection is prepared, and it is the one
   place that is true for every read loci makes.** `connect!`, `refresh!` and
   the ＋ Data panel's own preview all arrive here and nowhere else, so
   `lake/ensure-remote!` is called once, here, rather than at each of the three
   — and `land!`'s CREATE runs on the same connection immediately after, so the
   materialise is covered by the preparation the description already did."
  [lk src]
  (let [rel  (ssp/relation src (ssp/locate (:uri src)))
        ;; **Guarded on the scheme, and the guard is not a tidiness.**
        ;; `ensure-remote!` LOADs httpfs for whatever uri it is handed, and this
        ;; function sees every uri there is. Called unguarded, an ordinary
        ;; `loci://` CSV would issue `LOAD httpfs`; on a machine without the
        ;; extension that falls through to `INSTALL`, which is the one statement
        ;; in `ensure-extension!` that reaches the network — and under
        ;; `--network none`, the container this phase targets, it dies. The
        ;; person is then refused a file on local disk with a sentence about
        ;; httpfs. `ensure-extension!`'s own paragraph records that an httpfs
        ;; LOAD in a container still falls through, so that is reachable rather
        ;; than theoretical, and `a-local-connect-asks-for-no-httpfs` is the
        ;; assertion.
        ;;
        ;; Here rather than inside `ensure-remote!` for two reasons. `describe`
        ;; is the only caller, and the scheme is the reason the call exists at
        ;; all — a reader of this function should not have to open `lake.clj` to
        ;; learn that a local connect touches no extension. And `lake.clj` was
        ;; under review as this landed, so a behavioural change there would have
        ;; raced it. `ensure-remote!`'s docstring carries the precondition, so a
        ;; second caller is told rather than left to find out.
        _    (when (ssp/remote? (:uri src)) (lake/ensure-remote! lk (:uri src)))
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

(def ^:private modes
  "The two ways a source can be stored, and the only two values `:mode` may take.

   Validated rather than defaulted-through, because the failure is silent and
   permanent. `land!` branches on `(= :passthrough mode)`, so **every** value
   that is not that exact keyword selects materialise — including the string
   `\"passthrough\"`, which is precisely what a JSON body produces:
   `server.clj`'s `body-json` keywordises a map's KEYS and leaves its VALUES
   alone. Measured 2026-08-19: `:mode \"passthrough\"` created a real DuckLake
   table while `connect!` returned `:mode \"passthrough\"` and the object
   recorded it, so the lie survived into the log and every later `refresh!`
   repeated it.

   A source whose whole purpose is not copying a 500 MB file must not quietly
   copy it and report success."
  #{:materialise :passthrough})

(defn- mode-of
  "`src`'s `:mode`, normalized to one of `modes` or refused.

   A string and a keyword both normalize, because a JSON body carries the one
   and a REPL caller writes the other. Anything else — a number, a map, a
   collection — is refused with the SAME message rather than coerced: it is
   `(keyword 5)` that would otherwise decide, and its ClassCastException names
   neither loci nor the mode. `(keyword \"\")` is a legal keyword with an empty
   name, and falls out here as an unknown mode, which is what it is."
  [src]
  (let [m  (or (:mode src) :materialise)
        m' (if (or (keyword? m) (string? m)) (keyword m) m)]
    (when-not (modes m')
      (throw (ex-info (str "unknown source mode: " (pr-str (:mode src))
                           " — loci knows "
                           (str/join " and " (sort (map str modes))))
                      {:mode (:mode src) :known modes})))
    m'))

(defn- refuse-remote-passthrough!
  "Refuse `:mode :passthrough` when the location is one DuckDB fetches.

   **A guard, not a validation, and the distinction is the reason it is here
   rather than on the handle.** It refuses new CONNECTS; it does not refuse
   existing objects. `refresh!` reads `:mode` back off the object `connect!`
   already committed and never comes through this function, so an object that
   was committed as a remote passthrough before this landed keeps that state for
   as long as it exists — the mode is on the log, and the log is append-only.
   That is the whole argument for refusing here, in front of the first read,
   rather than reporting the problem at render time: there is no later moment at
   which loci can take it back. **No such object exists outside this branch** —
   `master`'s `locate` refuses `s3://` outright, so a remote scheme only became
   connectable four commits ago in `e1a5083` — so the point is structural and
   there is no migration to go looking for.

   The check is on `(:mode src)` as a KEYWORD, which is right only because
   `connect!` calls this on `(assoc src :mode (mode-of src))`. Its own traffic
   is HTTP and REPL callers — the shell has no remote-uri door — and
   `server/source-connect!` does not coerce `:mode` while `body-json`
   keywordises keys and not values, so every HTTP caller sends the STRING
   `\"passthrough\"`. Running this before `mode-of` would let all of them
   through. `connect-refuses-a-remote-passthrough-spelled-as-a-string` is the
   assertion, and it is the `modes` defect below one function along.

   **And it was reachable, not hypothetical.** DuckDB sets neither
   `autoload_known_extensions` nor `autoinstall_known_extensions`, so both
   default true and it installs and loads `httpfs` on its own. Measured
   2026-08-23 with the guard removed: `connect!` over `s3://bucket/x.csv`
   reached `bucket.s3.amazonaws.com` for real and came back with an HTTP 403 —
   which is to say the read happened. Against a bucket the credential chain CAN
   reach it would have described, landed, and committed
   `{:store :source :uri \"s3://…\"}` with no complaint from anywhere.

   The cost is measured rather than disliked. Phase 1 measured a passthrough
   connect at 10M rows as 294 ms, of which 244 ms is one `count(*)` — a full
   pass over the location. A `:store :source` handle repeats that pass on every
   query, and `hydrate` adds a liveness check on every render. Locally those are
   disk reads. Remotely each one is a fetch of the whole object across the
   network, and loci has no query timeout to stop it, so a single mis-chosen
   mode makes a notebook slow in a way nothing local can with the cause
   invisible. `:materialise` plus `refresh!` — phase 2 already built the button
   — is the same source at a cost the reader chose.

   `modes` one docstring above is the precedent for both the shape and the
   reasoning: it exists because `:mode \"passthrough\"` silently created a real
   table while every report said passthrough, so the lie survived into the log
   and every later `refresh!` repeated it. This is the same class one axis over
   — a passthrough that must not quietly become an unbounded fetch — and the
   answer is the same, a refusal before anything is committed."
  [src]
  (when (and (= :passthrough (:mode src)) (ssp/remote? (:uri src)))
    (throw (ex-info (str "loci does not connect a remote location as passthrough: " (:uri src)
                         " — a passthrough source is re-read on every query and checked"
                         " on every render, which over the network is a fetch of the whole"
                         " object each time and nothing in loci times it out."
                         " Connect it with :mode :materialise, and refresh it when it changes.")
                    {:uri (:uri src) :mode (:mode src)}))))

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

   The whole difference between the two modes is the branch below, which is the
   point of the framework: materialise runs one CREATE, passthrough runs
   nothing and keeps the recipe. Both build from the SAME `describe` output, and
   both keep both halves of it — the raw header and loci's column — because both
   have to emit the same `\"raw\" AS \"col\"` projection. Materialise emits it
   once, into a table; passthrough emits it on every read.

   `(:mode src)` is trusted to be one of `modes`, because `connect!` normalized
   and validated it and `refresh!` reads back what `connect!` recorded. The `=`
   below is why: every value that is not the exact keyword `:passthrough`
   selects materialise, silently."
  [lk id src d at]
  (if (= :passthrough (:mode src))
    (cond-> {:store :source :uri (:uri src) :format (:format src)
             :cols (mapv :col (:cols d))
             ;; `:raw` beside `:cols`, because a passthrough read has to build
             ;; the projection `create-from-relation!` writes into a table:
             ;; `"Total Revenue ($)" AS "total_revenue"`. The handle IS the
             ;; recipe, and half a recipe reads nothing — measured 2026-08-20, a
             ;; handle carrying only `:cols` failed every read of that file with
             ;; `Binder Error: Referenced column "total_revenue" not found`,
             ;; which `hydrate` then reported as drift on a file nobody had
             ;; touched. Only the raw half is new; `describe` has always
             ;; returned it and materialise has always used it.
             :raw  (mapv :raw (:cols d))
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
  "The `:source` map to record on the object.

   `(:mode src)` straight, with no default behind it: `connect!` normalizes and
   validates through `mode-of` before anything else runs, and `refresh!` reads
   back what `connect!` recorded. A second `or` here would be a second place the
   default is decided, and the two would drift."
  [src at]
  (cond-> {:uri (:uri src) :format (:format src) :mode (:mode src) :at at}
    (seq (:options src)) (assoc :options (:options src))))

;; One monitor over mint-and-commit, because `mint-id` reads the objects and
;; `connect!` writes under what it read.
;;
;; Measured 2026-08-19: 8 threads connecting one title minted `tbl:race` eight
;; times and one survived — seven callers got a success response for an object
;; unreachable by the id they were handed. The shadowed lake tables are not
;; leaked; `loci.lakegc` groups every `:put` on an id and reclaims them as
;; ordinary stale versions. The lost update is the damage.
;;
;; **Here rather than in the HTTP route**, which is where the analogous
;; `server/materialize-lock` lives, because this namespace is a facade
;; CLAUDE.md's *three ways to reach storage* means to be callable from a REPL
;; attached to the live server. A lock in the route would leave a REPL caller
;; racing an in-flight request, which is the case the route cannot see.
;;
;; **The landing is inside the monitor too**, which serialises a big
;; materialise — a 10M-row CSV holds it for the length of one `CREATE TABLE`.
;; That is deliberate: releasing between the mint and the commit reopens the
;; same window, since an id is only taken once it is committed. Contention is
;; one person connecting a source against another person connecting a source,
;; so there is nothing to gain by being clever and a whole class of loss by
;; being wrong — the argument `materialize-lock` already makes.
;;
;; Nesting is `connect-lock` → the store's own lock, the same direction
;; `materialize-entity!` and `notebook-op!` nest; the store never calls back
;; into this namespace, so they cannot deadlock.
;;
;; `refresh!` deliberately does NOT take it. It mints nothing: it `:put`s an id
;; that already exists and that only its own object names, so there is no
;; read-modify-write to lose. Two concurrent refreshes of one id are two runs of
;; one recipe; the loser becomes an ordinary stale lake version, which is what
;; `loci.lakegc` already reclaims, and the object stays reachable throughout.
;; Taking the monitor there would only serialise a long materialise against
;; every connect for no property gained.
(defonce ^:private connect-lock (Object.))

(defn connect!
  "Read `src` and commit a `:kind :table` object holding its rows or its recipe.

   `src` is `{:uri … :format … :mode … :title … :options …}`. `:mode` defaults
   to `:materialise`, normalizes a string, and is REFUSED if it is neither of
   `modes` — this is the one place that decides it, and everything downstream
   trusts the answer. When `space` names a space, a notebook cell referencing
   the new table is appended in the same transaction — `import-csv!`'s shape,
   kept.

   Returns `{:id … :cols … :rows … :mode …}`, whose `:mode` is read off the
   handle that was built rather than echoed from `src`. Throws whatever
   `describe` throws: a bad location, an unknown format, or two headers that
   would become one column all fail here, before anything is committed — and
   before any of those, `refuse-remote-passthrough!` refuses a remote location
   asked for as passthrough, which is the one refusal that happens without
   reading the source at all."
  ([st src] (connect! st src nil))
  ([st src space]
   (let [lk    @lake/lake
         src   (assoc src :mode (mode-of src))
         ;; Between the mode being decided and the source being read, which is
         ;; the only window there is: `describe` below is the first fetch, and
         ;; for a remote location that fetch is the cost this refuses.
         _     (refuse-remote-passthrough! src)
         title (or (not-empty (:title src)) "Table")
         d     (describe lk src)
         at    (now-iso)
         ;; Everything from the mint to the commit is inside the monitor, and
         ;; the payload is built after it. `describe` is deliberately OUTSIDE:
         ;; it commits nothing, and a bad location must not hold a monitor while
         ;; DuckDB decides the file is missing.
         [id h]
         (locking connect-lock
           (let [id  (mint-id st title)
                 h   (land! lk id src d at)
                 obj {:id id :kind :table :title title
                      :value h :source (source-of src at)}
                 base [{:op :put :id id :value obj}]
                 ;; `import-csv!`'s shape means its GUARD too, not just its
                 ;; `conj`: only a real `:space` may receive a cell append. An
                 ;; existence check alone lets a table id through, and the
                 ;; append on its vector `:value` poisons the log — that is the
                 ;; comment standing over `server/space?`, which `import-csv!`
                 ;; calls. Reproduced rather than called, because `space?` is
                 ;; private to `loci.server` and `loci.server` is what requires
                 ;; this namespace; a call the other way would close a cycle.
                 evs  (if (and space (= :space (:kind (sub/object st space))))
                        (conj base (nb/append-cell-event st space {:ref id}))
                        base)]
             (sub/commit! st {:op :tx :events evs})
             [id h]))]
     ;; The mode is read back off the handle that was actually built, not echoed
     ;; from the input — the two disagreeing is the whole of the string-mode
     ;; defect, and a derived answer is structurally unable to.
     {:id id :cols (:cols h) :rows (:rows h)
      :mode (if (= :source (:store h)) :passthrough :materialise)})))

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
      ;; Two `:assoc`s in one `:tx`, never a `:put` of the object read above.
      ;; `apply-event :put` is `assoc-in [:objects id]` — a whole-object replace
      ;; — so committing a map built from an earlier read silently discards
      ;; anything else that landed on the object in between. Demonstrated
      ;; 2026-08-19, deterministically and with no error anywhere: an
      ;; `/api/edit` on a table between this function's read and its commit was
      ;; gone afterwards. `server/rerun!` already avoids exactly this by
      ;; assoc-ing `[:value]` rather than re-putting the object, and this is the
      ;; same hazard with the same remedy — one refresh touches the two paths a
      ;; refresh means, and nothing else.
      (sub/commit! st {:op :tx
                       :events [{:op :assoc :id id :path [:value]  :value h}
                                {:op :assoc :id id :path [:source] :value (source-of src at)}]})
      {:id id :cols (:cols h) :rows (:rows h) :mode (:mode src)})))
