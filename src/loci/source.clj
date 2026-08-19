(ns loci.source
  "Connecting an external source to loci: describe it, land it, refresh it.

   The facade, in the sense CLAUDE.md's *one writer, three ways to reach
   storage* means — public, and meant to be called from a REPL attached to the
   live server as well as from the HTTP routes. `describe` **is** the dry run,
   which is why nothing here takes a `:dry-run?` flag.

   `loci.sourcespec` holds the pure half: URI → location, source → SQL."
  (:require [clojure.string :as str]
            [loci.lake :as lake]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- duckdb-said
  "The one line of a DuckDB JDBC failure that says what actually went wrong.

   A missing file is the commonest way a source fails — a typo, a moved file, a
   `loci://` name that was never uploaded — and it is the only failure in this
   namespace that arrives as a `SQLException` rather than an `ex-info`, because
   it is DuckDB that notices rather than loci. Raw, that message is four lines:

     Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
     Error: IO Error: No files found that match the pattern \"/no/such/file.csv\"

     LINE 1: DESCRIBE SELECT * FROM read_csv('/no/such/file.csv')
                                    ^

   The first line is the JDBC driver describing its own state and says nothing
   about the source; the last two are a SQL dump of text loci generated. Only
   the `Error:` line is about what the caller did. `describe`'s callers put
   `.getMessage` straight into a JSON body — `server.clj` does this at every
   route — so this is what the shell shows."
  [^Throwable e]
  (let [ls (->> (str/split-lines (str (.getMessage e)))
                (map str/trim)
                (remove str/blank?))]
    (or (some->> ls (filter #(str/starts-with? % "Error: ")) first (drop 7) (apply str))
        (first ls)
        (str e))))

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
                    (throw (ex-info (str "loci cannot read " (:uri src) " — " (duckdb-said e))
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
