(ns loci.source
  "Connecting an external source to loci: describe it, land it, refresh it.

   The facade, in the sense CLAUDE.md's *one writer, three ways to reach
   storage* means — public, and meant to be called from a REPL attached to the
   live server as well as from the HTTP routes. `describe` **is** the dry run,
   which is why nothing here takes a `:dry-run?` flag.

   `loci.sourcespec` holds the pure half: URI → location, source → SQL."
  (:require [loci.lake :as lake]
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
        rows (lake/q lk (str "DESCRIBE SELECT * FROM " rel))
        cols (mapv (fn [r] {:raw  (:column_name r)
                            :col  (sub/col-kw (:column_name r))
                            :type (:column_type r)})
                   rows)]
    (when (empty? cols)
      (throw (ex-info (str "the source has no columns: " (:uri src)) {:uri (:uri src)})))
    (tbl/check-names! (map :raw cols))
    {:cols cols :relation rel}))
