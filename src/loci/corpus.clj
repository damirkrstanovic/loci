(ns loci.corpus
  "`corpus/` — five reduced open-data tables and fourteen quoted prose extracts,
   with `MANIFEST.edn` recording where every one of them came from and under what
   licence — becoming notebooks, tables and documents in the substrate.

   **Read `corpus/MANIFEST.edn` before this namespace.** It is the contract: this
   file names which prose belongs with which table and writes the sentences that
   introduce them, and takes *everything else* — titles, publishers, licences,
   attributions, row counts, the coarse column each table was bucketed for — out
   of the manifest at run time. Nothing provenance-bearing is retyped here, for
   `loci.repair`'s reason: a second copy of a citation is a citation that can
   drift from the thing it cites, and this corpus's whole claim is that nothing
   in it was invented.

   **Built like `loci.repair` and `loci.classify`, for their reasons.**

   · **Nothing runs on require.** No top-level side effect, no `defonce` that
     opens a store. `import-corpus!` is `{:dry-run? true}` by DEFAULT, and a dry
     run returns the plan it would have applied without applying any of it —
     **the same value** a live run reports, computed by the same code, which is
     what makes \"rehearse on a copy, then compare\" mean anything.
   · **Every refusal is a returned report, never a throw.** The caller is a
     person reading a sentence.
   · **The whole world is checked before any of it is written.** Every file must
     be present, parse, carry the row count and the coarse values the manifest
     recorded, and — for prose — open with the heading the manifest gives as its
     title. A run that would fail on the fourth notebook writes none of the
     first three.
   · **Offline.** `corpus/` is read from a path argument; there is no network
     call anywhere in here, so the same input gives the same events.

   **Two halves, two functions, and they are not one transaction.**
   `import-corpus!` writes notebooks, tables and documents to the substrate;
   `import-facts!` writes facts to the memory. They are deliberately separate
   calls because the two stores are not the same kind of thing: the substrate is
   an append-only log with `undo!` over it, and the memory is an append-only log
   with **no undo at all**. Folding the facts into a notebook's `:tx` would make
   one gesture look reversible when half of it is not, and `sub/undo!` would take
   back the document a fact still cites. So the facts go second, on their own,
   and say so.

   **One `:tx` per notebook, not one giant commit.** Five commits rather than
   one: each notebook is then independently undoable — `sub/undo!` takes back the
   last one and leaves the rest — and re-runnable, since an undone notebook is
   simply one the store no longer holds and the next run rebuilds it. The time
   scrubber gets a history with shape instead of a single step. The cost is that
   an import interrupted half way leaves a prefix of complete notebooks, which is
   the state this shape is *for*: every one of them is whole, and the next run
   finishes the job.

   **Events are built directly and NOT through `server/import-csv!`.** That route
   mints `tbl:csv-<count>-<rand>` — one of the three count-plus-random minters
   left open on 2026-08-09 — and a random suffix makes an import that is
   otherwise a pure function of `corpus/` non-deterministic for no gain. Ids come
   from `server/next-id`, the rule the server already uses; a namespace that
   minted by its own rule is how one prefix comes to have two counters, neither
   able to see all the ids under it.

   **A notebook records the corpus file it was built from, under
   `:imported-from`.** That is the whole idempotence mechanism and it is
   deliberately shaped like `:restored-from`: invisible to the shell, because
   `state-payload` copies only the keys it names; invisible to the graph, because
   `nb/links` reads `:merged-from` and not this, so no phantom edge appears.

   **`content.clj` is untouched and stays untouched.** Three things real data
   found about it — `cat-col` preferring the FEWEST-valued column, `measure-col`
   punishing honest column names, `line` having no series column for `country` —
   are recorded in `MANIFEST.edn`'s `:findings` and are predicates several
   viewers hang off. Task 1 guarded against the first *in the extract*. Nothing
   here works around any of them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.lake :as lake]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.server :as server]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools]))

(def hubs
  "The five notebooks: which corpus table each is built on, what it is for, and
   which prose belongs with it.

   **The prose placement is a literal list and that is a decision.** The
   manifest gives every extract a `:domain` and a set of `:entangles` subjects,
   and deriving the placement from those is the tempting alternative. It does not
   work, and the corpus makes the failure concrete rather than hypothetical:
   `lead-phaseout.csv` and `elections-turnout.csv` are BOTH `:politics`, and
   `voter-turnout-what-it-measures.md` shares exactly two entangled subjects with
   each of them — `united states` and `netherlands`. Any rule that picks by
   domain, or by overlap, has to break that tie by something, and whatever it
   broke it by would be this list with an argument wrapped around it.

   Fourteen filenames can be checked against `MANIFEST.edn`'s `:prose` by eye,
   before anything runs. The cost is that the list does not travel to another
   corpus, and `coverage-complaint` turns that cost into a refusal rather than a
   silent gap: every prose file in the manifest must be placed exactly once, and
   nothing may be placed that the manifest does not carry.

   `:about` is the only prose in this namespace that is not quoted from
   somewhere, and it says what the notebook is *for*; the sources and licences
   that follow it in `:intent` all come out of the manifest.

   The finance notebook has no prose, and that is the corpus and not an
   oversight: nothing in `corpus/prose/` is `:finance`. It is also the notebook
   worth opening first — the only monthly table, so the only one that offers Over
   time and Headline at all, and the one where `line` draws ten economies as a
   single interleaved series."
  [{:file  "art-objects.csv"
    :title "Art objects: cobalt blue, white lead, and the Dutch trade"
    :about (str "What the pigments were for, era by era — cobalt blue from Jingdezhen to Delft, "
                "white lead behind four centuries of European painting — and what Dutch painting "
                "owed to a trade route.")
    :prose ["cobalt-blue-the-pigment.md"
            "white-lead-the-painters-white.md"
            "cobalt-blue-kangxi-porcelain.md"
            "cobalt-blue-banned-in-joseon-korea.md"
            "dutch-trade-in-a-still-life.md"
            "chinese-vases-in-a-dutch-parlour.md"
            "delft-flower-pyramid.md"
            "delft-nautilus-cup.md"]}

   {:file  "mineral-production.csv"
    :title "Critical minerals: where cobalt, gallium and rare earths come from"
    :about (str "World production for nine commodities, by country and year, beside the 2023-2025 "
                "export controls that made three of them news — the supply-chain sense of the same "
                "metals the art notebook grinds into paint.")
    :prose ["cobalt-the-congo-export-ban.md"
            "gallium-and-the-export-controls.md"
            "rare-earths-export-controls.md"]}

   {:file  "lead-phaseout.csv"
    :title "Lead: from the painter's white to the petrol ban"
    :about (str "One metal followed out of the paint box and into public health law — the year "
                "each country banned leaded petrol, and the year the United States put lead back "
                "on its critical minerals list.")
    :prose ["the-leaded-petrol-phase-out.md"
            "lead-becomes-a-critical-mineral.md"]}

   {:file  "elections-turnout.csv"
    :title "Elections: voter turnout, and what it measures"
    :about (str "Turnout at the most recent national election in each country since 2000, and a "
                "note on what \"of registered voters\" does and does not count.")
    :prose ["voter-turnout-what-it-measures.md"]}

   {:file  "interest-rates-monthly.csv"
    :title "Money-market rates, month by month, in ten economies"
    :about (str "Three-month rates through the 2021-2024 cycle. The corpus's only monthly table, "
                "and so the only one loci molds as Over time or Headline — the four annual tables "
                "are left annual on purpose, because `timeseries?` keys on a column literally "
                "named month.")
    :prose []}])

;; ----------------------------------------------------------------------------
;; the manifest, and the material it describes
;; ----------------------------------------------------------------------------

(defn- squash
  "Whitespace collapsed to single spaces.

   The manifest is hand-wrapped EDN, so a long `:attribution` or `:publisher`
   arrives with newlines inside it. A notebook's `:intent` is one paragraph the
   shell prints under a title; pasting a hard-wrapped citation into it would put
   the line breaks of a source file into a sentence."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn read-manifest
  "`<dir>/MANIFEST.edn`, parsed — `{:manifest m}` or `{:error \"…\"}`.

   `edn/read-string` and never `load-file`: the manifest says of itself that
   every value in it is a literal and nothing in it needs eval, and reading data
   as data is how that stays true."
  [dir]
  (let [f (io/file dir "MANIFEST.edn")]
    (cond
      (not (.isDirectory (io/file dir)))
      {:error (str "there is no corpus directory at " dir
                   " — this import reads the committed corpus and fetches nothing")}

      (not (.isFile f))
      {:error (str dir " has no MANIFEST.edn, and without it nothing here knows what licence "
                   "any of the material is under — refusing rather than importing unattributed files")}

      :else
      (try
        (let [m (edn/read-string (slurp f))]
          (if (and (map? m) (seq (:files m)))
            {:manifest m}
            {:error (str (.getPath f) " does not read as a manifest: no :files in it")}))
        (catch Exception e
          {:error (str (.getPath f) " could not be read as EDN: " (.getMessage e))})))))

(defn- by-file [m] (into {} (map (juxt :file identity)) (:files m)))
(defn- prose-by-file [m] (into {} (map (juxt :file identity)) (:files (:prose m))))
(defn- prose-file [dir m nm] (io/file dir (or (:dir (:prose m)) "prose") nm))

(defn- coverage-complaint
  "Why `hubs` and the manifest do not describe the same corpus, or nil.

   Checked before any file is opened, because the answer is about the two
   declarations and not about the disk. A prose file the manifest carries and
   nobody places is an extract that was fetched, licence-cleared, committed —
   and then silently never imported, which is the kind of gap that is only ever
   noticed by whoever goes looking for it."
  [m]
  (let [want-t  (set (map :file (:files m)))
        got-t   (map :file hubs)
        want-p  (set (map :file (:files (:prose m))))
        got-p   (mapcat :prose hubs)
        dup     (fn [xs] (->> (frequencies xs) (keep (fn [[k n]] (when (> n 1) k))) sort))]
    (cond
      (seq (dup got-t))
      (str "two notebooks are declared for the same table: " (str/join ", " (dup got-t)))

      (not= want-t (set got-t))
      (str "the manifest's tables and the declared notebooks disagree: "
           "the manifest has " (str/join ", " (sort want-t))
           " and loci.corpus/hubs names " (str/join ", " (sort got-t)))

      (seq (dup got-p))
      (str "the same prose file is placed in two notebooks: " (str/join ", " (dup got-p)))

      (not= want-p (set got-p))
      (str "the manifest's prose and the declared notebooks disagree — unplaced: "
           (str/join ", " (sort (remove (set got-p) want-p)))
           "; placed but not in the manifest: "
           (str/join ", " (sort (remove want-p got-p)))))))

(defn- table-complaint
  "Why one table file is not the file the manifest describes, or nil.

   Three checks, each against a number or a set the manifest wrote down at fetch
   time, and every message names the file so a reader knows which one to look at.

   The coarse-column check is the one worth arguing for. It is the whole reason
   Task 1 bucketed these files: `cat-col` takes the coarsest string column with
   2-12 distinct values, so a table whose `era` collapsed to one value — or grew
   past twelve — stops offering Pivot and Bar, and *nothing warns you*. The table
   still imports, still renders as Rows, and the moldability the corpus exists to
   demonstrate is quietly gone. Checking the values here turns that into a
   refusal with the column's name in it.

   Not an md5 against `:checksums`. The manifest and the file it describes are
   one artifact committed together, so a hash of one recorded in the other
   catches an accidental edit and nothing else; the checks below catch the edits
   that would actually change what loci does with the file."
  [dir m {:keys [file]}]
  (let [f     (io/file dir file)
        entry (get (by-file m) file)]
    (if-not (.isFile f)
      (str "the corpus has no " file " — " (.getPath f) " is not there")
      (let [rows (try (tools/parse-csv (slurp f)) (catch Exception e {:error (.getMessage e)}))]
        (cond
          (map? rows)
          (str file " could not be parsed as CSV: " (:error rows))

          (empty? rows)
          (str file " has no rows in it")

          (not= (get-in entry [:rows :committed]) (count rows))
          (str file " has " (count rows) " rows, not the "
               (get-in entry [:rows :committed]) " the manifest recorded — this is not the "
               "extract the manifest describes, so nothing is imported")

          :else
          (let [c    (sub/col-kw (:coarse-column entry))
                seen (set (map #(get % c) rows))
                want (set (:coarse-values entry))]
            (cond
              (not (contains? (first rows) c))
              (str file " no longer has the coarse column " (:coarse-column entry)
                   " the manifest bucketed it for — without it the table molds as Rows and "
                   "nothing else")

              (not= seen want)
              (str file "'s coarse column " (:coarse-column entry) " holds "
                   (count seen) " distinct values, not the " (count want)
                   " the manifest recorded ("
                   (str/join ", " (sort (concat (map #(str "unexpected " (pr-str %))
                                                     (remove want seen))
                                                (map #(str "missing " (pr-str %))
                                                     (remove seen want)))))
                   ") — cat-col takes the coarsest column with 2-12 values, so this changes "
                   "what the table molds as"))))))))

(defn- prose-complaint
  "Why one prose file is not the extract the manifest describes, or nil.

   The heading check is a cross-check and not a formality. The manifest says of
   itself that a prose extract's title *is* the file's `# ` heading, so the two
   can be compared — and a document imported under a title its own first line
   contradicts is one a reader finds by one name and reads under another."
  [dir m nm]
  (let [entry (get (prose-by-file m) nm)
        f     (prose-file dir m nm)]
    (cond
      (nil? entry)
      (str nm " is placed in a notebook but the manifest does not carry it")

      (not (.isFile f))
      (str "the corpus has no prose/" nm " — " (.getPath f) " is not there")

      :else
      (let [body (slurp f)
            head (str "# " (:title entry))]
        (cond
          (str/blank? body)
          (str "prose/" nm " is empty")

          (not (str/starts-with? body head))
          (str "prose/" nm " opens with " (pr-str (first (str/split-lines body)))
               ", but the manifest titles it " (pr-str (:title entry))
               " — importing it would file it under a name its own first line contradicts"))))))

(defn- material-complaint
  "The first reason the material on disk is not the corpus the manifest
   describes, or nil. Every hub is checked before any of them is written."
  [dir m]
  (or (some #(table-complaint dir m %) hubs)
      (some #(prose-complaint dir m %) (mapcat :prose hubs))))

;; ----------------------------------------------------------------------------
;; attribution — the part a licence makes a condition rather than a courtesy
;; ----------------------------------------------------------------------------

(defn- credit
  "One source, as a notebook's `:intent` names it: who published it, under what
   licence, and — for CC BY only — the citation the publisher asked for.

   The asymmetry is the licence's, not a style choice. CC0 and US public domain
   impose no attribution condition at all, so naming the publisher and the
   dedication is complete and a pasted citation would only be longer. CC BY makes
   attribution a *condition of use*, and the manifest records the form each
   publisher asked for, so those are carried verbatim (whitespace aside). Three
   of the five tables are CC BY; that is where the intents get long, and that is
   where they have to be."
  [{:keys [source licence]}]
  (let [id   (:id licence)
        base (str (squash (:publisher source)) " — " id)]
    (cond-> base
      (and (str/starts-with? (str id) "CC-BY") (:attribution licence))
      (str ", cite as: " (squash (:attribution licence))))))

(defn- unsubsumed
  "Credits with the ones already spelled out inside another credit removed.

   Not cosmetic. The leaded-petrol notebook holds an OWID table and an OWID
   extract, so it credits `Our World in Data — CC-BY-4.0, cite as: …` and then
   `Our World in Data — CC-BY-4.0` — the second says nothing the first has not
   already said, character for character, and a citation that reads as a stutter
   is one a reader stops reading. Dropping only *substrings* is what makes this
   safe: every character of a dropped credit is still in the sentence, so no
   attribution is weakened by it. V-Dem's two credits survive, because
   `V-Dem, Democracy report v16 (2026), processed by Our World in Data` is not
   inside `V-Dem, processed by Our World in Data` — they really are two
   different citations of the same project."
  [credits]
  (let [cs (vec (distinct credits))]
    (vec (for [c cs :when (not-any? #(and (not= % c) (str/includes? % c)) cs)] c))))

(defn- intent-for
  "A notebook's `:intent`: what it is for, then every source in it with its
   licence.

   **This is where a reader actually sees the attribution**, which is why it goes
   here and not into a comment or a side file. `state-payload` ships `:intent` to
   the shell for every notebook, so a CC BY table's citation travels with the
   notebook that holds it — including into the overview, where somebody browsing
   sees whose data they are looking at without opening anything.

   Sources are given in cell order: an art notebook holding eight Church and
   Cleveland extracts names each publisher once."
  [about entries]
  (str about " Sources: " (str/join "; " (unsubsumed (map credit entries))) "."))

;; ----------------------------------------------------------------------------
;; what is already imported
;; ----------------------------------------------------------------------------

(defn imported
  "corpus file → the notebook built from it, read out of the store.

   `:imported-from` on a notebook's `:value` is the only record anywhere that
   this import ran, and every idempotence property hangs off it: a second run
   finds all five and writes nothing, a run after `sub/undo!` finds four and
   rebuilds the fifth, and a store that was never imported into finds none."
  [st]
  (into {} (keep (fn [o] (when-let [f (get-in o [:value :imported-from])] [f o])))
        (nb/notebooks st)))

(defn- claim-complaint
  "Why the store's existing claims on these files cannot be trusted, or nil.

   Three states refuse rather than proceed. A file claimed by two notebooks means
   something imported it twice under ids nobody can now reconcile — skipping
   would leave the duplicate and importing would make three. A file claimed by a
   notebook with a different title means either the corpus has been re-titled
   under a store that already holds it, or somebody's own notebook happens to
   carry the key; either way, writing a second notebook for the same file makes
   the store hold two answers to one question.

   The third is about the store this is aimed at. `data/` is somebody's real
   work, and a hub title that a notebook *already there* happens to use would
   leave two identically-titled notebooks in the overview and make
   `about-notebook`'s \"a notebook titled for the entity wins\" a coin toss
   between them. Refusing hands that back as a sentence before anything is
   written, which is the only point at which renaming a hub is cheap."
  [st]
  (let [done (imported st)
        dups (->> (nb/notebooks st)
                  (keep #(get-in % [:value :imported-from]))
                  frequencies
                  (keep (fn [[k n]] (when (> n 1) k)))
                  sort)]
    (or
     (when (seq dups)
       (str "more than one notebook says it was imported from "
            (str/join ", " dups) " — refusing rather than adding a third"))

     (some (fn [{:keys [file title]}]
             (when-let [o (get done file)]
               (when (not= title (:title o))
                 (str (:id o) " says it was imported from " file " but is titled "
                      (pr-str (:title o)) ", not " (pr-str title)
                      " — refusing rather than writing a second notebook for one file"))))
           hubs)

     (some (fn [{:keys [file title]}]
             (when-let [o (first (filter #(and (= title (:title %))
                                               (not= file (get-in % [:value :imported-from])))
                                         (nb/notebooks st)))]
               (str (:id o) " is already titled " (pr-str title)
                    " and was not imported from " file
                    " — refusing rather than putting two notebooks of that name in the overview")))
           hubs))))

;; ----------------------------------------------------------------------------
;; minting
;; ----------------------------------------------------------------------------

(defn- view
  "A read-only store over the objects minted so far.

   The view is the point, and it is `loci.repair`'s lesson repeated: nothing is
   committed until each notebook's `:tx`, so asking the LIVE store for five
   notebook ids in a row hands back the same id five times and the last `:put`
   erases four with no error anywhere. A `FrozenStore` over the objects-so-far
   costs a map lookup and makes every mint see the world the mints before it
   left — across notebooks as well as within one, so that the plan a dry run
   returns holds exactly the ids a live run writes."
  [objm]
  (sub/->FrozenStore {:objects objm} []))

(defn- mint-hub
  "One notebook's objects and its transaction, minted against `objm`.

   The table is put first, then each document, then the notebook — so at no
   point in the fold does a notebook cell reference an object that is not there
   yet. That is `research!`'s order and `loci.repair`'s reason.

   Returns `[objm' entry]`, the entry carrying both the printable plan and the
   `:tx` that realises it.

   `ts` is the table store the rows are landed in, and a **dry run passes an
   `InlineTable`** — see `import-corpus!`. Minting is how the plan is computed,
   so a dry run runs this function in full; landing its rows in the real lake
   would leave five orphan tables and six snapshots behind every preview
   (measured 2026-08-16), for a plan that is then discarded. An inline handle
   carries the rows in the `:tx` nobody applies and writes nothing anywhere."
  [ts objm dir m {:keys [file title about prose]}]
  (let [tentry (get (by-file m) file)
        rows   (tools/parse-csv (slurp (io/file dir file)))
        tid    (server/next-id (view objm) "tbl:corpus-")
        tobj   (tbl/table-object ts tid (:title tentry) rows)
        objm   (assoc objm tid tobj)
        [objm docs]
        (reduce (fn [[objm acc] nm]
                  (let [pentry (get (prose-by-file m) nm)
                        body   (slurp (prose-file dir m nm))
                        did    (server/next-id (view objm) "doc:corpus-")
                        dobj   {:id did :kind :doc :title (:title pentry) :value body}]
                    [(assoc objm did dobj)
                     (conj acc {:id did :file nm :title (:title pentry)
                                :chars (count body) :object dobj :entry pentry})]))
                [objm []] prose)
        sid    (server/next-id (view objm) "space:corpus-")
        cells  (into [{:ref tid}] (map (fn [d] {:ref (:id d)})) docs)
        intent (intent-for about (cons tentry (map :entry docs)))
        sobj   {:id sid :kind :space :title title
                :value {:intent intent :cells cells :imported-from file}}
        events (-> [{:op :put :id tid :value tobj}]
                   (into (map (fn [d] {:op :put :id (:id d) :value (:object d)})) docs)
                   (conj {:op :put :id sid :value sobj}))]
    [(assoc objm sid sobj)
     {:id sid :title title :from file
      :table {:id tid :file file :title (:title tentry) :rows (count rows)}
      :documents (mapv #(dissoc % :object :entry) docs)
      :events (count events)
      :tx events}]))

(defn- mint [ts st dir m todo]
  (second (reduce (fn [[objm out] hub]
                    (let [[objm entry] (mint-hub ts objm dir m hub)]
                      [objm (conj out entry)]))
                  [(sub/objects st) []]
                  todo)))

;; ----------------------------------------------------------------------------
;; the import
;; ----------------------------------------------------------------------------

(defn import-corpus!
  "Turn `corpus/` into notebooks, tables and documents. **Dry run by default.**

   `opts` takes `:dir` (default `\"corpus\"`) and `:dry-run?` (default `true`).
   Returns a report, never throws:

     :ok?           did the store end up holding the corpus
     :dry-run?      was this a plan or an act
     :plan          {:notebooks [{:id :title :from :events
                                  :table {:id :file :title :rows}
                                  :documents [{:id :file :title :chars} …]} …]
                     :already   [{:from :id :title} …]  already imported, skipped
                     :tables n :documents n :events n :commits n}
     :already-done? every notebook is already there; nothing was written
     :committed     the store's event count afterwards
     :error         why not, when not

   The plan a dry run returns is **the same value** a live run reports, computed
   by the same code and carrying the same ids — which is what makes Task 4's
   \"dry-run the copy, then compare the plans strictly\" a real check rather than
   a look.

   Computed by the same code but **not into the same table store**: a dry run
   mints into an `InlineTable` and a live run into the lake. Minting is how the
   plan is computed, so a dry run has to run `mint-hub` in full, and once tables
   moved to DuckLake that meant every preview left five orphan tables and six
   snapshots behind for a plan nobody applied — measured 2026-08-16, and a leak
   that compounds, because the lake's table GC is off. \"Without applying any of
   it\" has to cover the lake as well as the log.

   **One `sub/commit!` of one `:tx` per notebook.** `sub/undo!` therefore takes
   back the last notebook and only the last, while it is still the last commit —
   the honest limit of this half's reversibility, and the same one the repair
   carries.

   What it refuses, and why refusing beats proceeding: a directory that is not a
   corpus or a manifest that will not read (importing files whose licence nothing
   states is the one thing this corpus was built not to do); a declaration and a
   manifest that describe different corpora (a prose file nobody places is one
   that was fetched and silently never imported); a file whose row count, coarse
   values or heading are not what the manifest recorded (then it is not the
   extract the manifest describes, and the coarse column in particular decides
   what the table molds as); a store where one corpus file is already claimed
   twice, or claimed under another title; and a minted id that is somehow
   occupied — minting onto a live object is the 2026-07-23 bug, and this
   namespace is not going to be the second one to commit it."
  ([st] (import-corpus! st {}))
  ([st {:keys [dir dry-run?] :or {dir "corpus" dry-run? true}}]
   (let [{:keys [manifest error]} (read-manifest dir)]
     (if error
       {:ok? false :dry-run? dry-run? :error error}
       (if-let [why (or (coverage-complaint manifest)
                        (material-complaint dir manifest)
                        (claim-complaint st))]
         {:ok? false :dry-run? dry-run? :error why}
         (let [done    (imported st)
               already (vec (for [{:keys [file]} hubs
                                  :let [o (get done file)]
                                  :when o]
                              {:from file :id (:id o) :title (:title o)}))
               todo    (remove #(get done (:file %)) hubs)]
           (if (empty? todo)
             {:ok? true :dry-run? dry-run? :already-done? true
              :plan {:notebooks [] :already already
                     :tables 0 :documents 0 :events 0 :commits 0}}
             ;; A dry run mints into an `InlineTable`, so it opens no lake and
             ;; leaves nothing behind — the `if` also means `@lake/lake` is never
             ;; forced by a preview. The plan is the same value either way: the
             ;; handle lives in `:tx`, which `plan` dissocs.
             (let [entries (mint (if dry-run? (tbl/inline-table) @lake/lake)
                                 st dir manifest todo)
                   ids     (mapcat (fn [e] (map :id (:tx e))) entries)
                   taken   (filterv #(sub/object st %) ids)
                   plan    {:notebooks (mapv #(dissoc % :tx) entries)
                            :already   already
                            :tables    (count entries)
                            :documents (reduce + (map (comp count :documents) entries))
                            :events    (reduce + (map :events entries))
                            :commits   (count entries)}]
               (cond
                 (seq taken)
                 {:ok? false :dry-run? dry-run?
                  :error (str "the ids this import minted are occupied: " (str/join ", " taken)
                              " — minting onto a live object is the 2026-07-23 bug")}

                 dry-run?
                 {:ok? true :dry-run? true :plan plan}

                 :else
                 (do (doseq [e entries] (sub/commit! st {:op :tx :events (:tx e)}))
                     {:ok? true :dry-run? false :plan plan
                      :committed (count (sub/history st))}))))))))))

;; ============================================================================
;; the facts — the half the substrate cannot take back
;; ============================================================================

;; **No fact in this namespace is a sentence somebody typed.** That is the one
;; rule the whole section is built to make structural rather than aspirational,
;; because the claim `corpus/MANIFEST.edn` makes about the material — "nothing in
;; it was invented" — is worth nothing if the facts distilled out of it are.
;;
;; So there are exactly two ways a fact gets written, and neither of them lets a
;; sentence in through the front door:
;;
;;   `quoted`  — a pair of anchors. The fact is the span of the extract BETWEEN
;;               them, sliced out of the file at run time. The declaration is a
;;               locator, not a quotation: nothing is retyped, so nothing can be
;;               retyped wrong, and an anchor that no longer matches its file
;;               refuses the import instead of quietly shifting the quote.
;;   `derived` — a table, a named derivation and its arguments. The sentence is
;;               assembled from cells the derivation read; the WORDING is written
;;               here, the CLAIM is computed there. A derivation that cannot find
;;               its rows returns nil and the import refuses, so "the earliest
;;               ban is Japan's" cannot outlive the row that made it true.
;;
;; What a reader should hold onto: every number below comes off a row, every
;; sentence in quotation comes off a line of a file, and if either stops being
;; there the import says so rather than importing something it made up.

(defn- oxford
  "`[\"a\" \"b\" \"c\"]` → `\"a, b and c\"`. Serial comma deliberately absent; the
   corpus's own prose is British-published and so is this."
  [xs]
  (case (count xs)
    0 ""
    1 (str (first xs))
    (str (str/join ", " (butlast xs)) " and " (last xs))))

(defn- printed
  "A cell as a sentence prints it: `230000.0` → `\"230000\"`, `78.3` → `\"78.3\"`.

   `parse-csv` reads every unquoted decimal as a Double, so a whole-number
   tonnage arrives as `2600000.0` and a sentence that printed it that way would
   read like a machine talking. Only the trailing `.0` is dropped and only when
   the value really is integral — `-0.582` and `78.3` print as they parsed."
  [x]
  (if (and (number? x) (== (double x) (Math/rint (double x))) (< (Math/abs (double x)) 1e15))
    (str (long x))
    (str x)))

(defn- ordinal [n]
  (str n (if (#{11 12 13} (mod n 100))
           "th"
           (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(defn- commonest
  "`[value count]` for the most frequent non-blank value of `k`, or nil when the
   winner is not unique.

   Ties return nil rather than picking one, and every caller turns that into a
   refusal. \"China is their commonest culture\", said of two cultures that
   appear equally often, is a sentence the data does not support — and `sort-by`
   is stable, so without this it would answer from file order and read exactly
   as confident as a real answer."
  [k rows]
  (let [fr (->> rows (map k) (remove #(str/blank? (str %))) frequencies
                (sort-by (juxt (comp - val) key)))]
    (when (and (seq fr) (or (= 1 (count fr)) (> (val (first fr)) (val (second fr)))))
      (first fr))))

(defn- only-one
  "The single row that is extreme on `k` by `cmp`, or nil when two rows tie.

   Same reason as `commonest`: \"the earliest ban in the table\" is a claim about
   uniqueness, and a stable sort answering it from a tie would state as fact
   something that is merely first in file order."
  [cmp k rows]
  (let [sorted (sort-by k cmp rows)]
    (when (and (seq sorted)
               (or (= 1 (count sorted)) (not= (k (first sorted)) (k (second sorted)))))
      (first sorted))))

;; ----------------------------------------------------------------------------
;; the derivations — the wording is here, the claim is in the rows
;; ----------------------------------------------------------------------------

(def ^:private derivations
  "derivation name → `(fn [rows args] {:fact … :entities [...]})`, or nil when
   the rows the arguments name are not in the table.

   **nil is the honesty mechanism**, and it is why these are functions over the
   whole table rather than templates over one row. `:absent` asserts that the
   USGS table lists no United States gallium production; it returns nil the day
   it does, and the import refuses rather than writing a sentence that has
   quietly become false. Same for every superlative: `only-one` and `commonest`
   answer nil on a tie, so \"more than in any other year\" is either true of the
   committed rows or the import stops.

   **Entities come off the cells the derivation selected on**, never from a list
   written beside the declaration — `:commodity` and `:country` lower-cased are
   the names the manifest's `:entangles` already uses (`democratic republic of
   congo`, `rare earths`), so the two halves of the corpus land on one spelling
   without either being copied into the other."
  {;; ---- art-objects.csv -----------------------------------------------------
   :matching
   (fn [rows {:keys [word columns as]}]
     (let [rx  (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote word) "\\b"))
           hit (filterv (fn [r] (some #(re-find rx (str (get r %))) columns)) rows)
           ys  (filterv number? (map :year hit))
           top (commonest :culture hit)]
       (when (and (seq hit) (seq ys) top)
         {:fact (str (count hit) " MET objects name " word " in their " as
                     ", dated " (apply min ys) " to " (apply max ys)
                     "; " (key top) " is their commonest culture (" (val top) ").")
          :entities [(str/lower-case word)]})))

   :from-country
   (fn [rows {:keys [country]}]
     (let [hit (filterv #(= country (:country %)) rows)
           ys  (filterv number? (map :year hit))
           top (commonest :culture hit)]
       (when (and (seq hit) (seq ys) top)
         {:fact (str (count hit) " MET objects give " country
                     " as their country, dated " (apply min ys) " to " (apply max ys)
                     "; " (key top) " is their commonest culture (" (val top) ").")
          :entities [(str/lower-case country)]})))

   ;; ---- mineral-production.csv ---------------------------------------------
   :leader
   (fn [rows {:keys [commodity year]}]
     (let [g (sort-by :production_tonnes >
                      (filterv #(and (= commodity (:commodity %)) (= year (:year %))) rows))
           [a b] g]
       (when (and b (not= (:production_tonnes a) (:production_tonnes b)))
         {:fact (str "In " year " " (:country a) " produced " (printed (:production_tonnes a))
                     " " (:unit a) " of " (str/lower-case commodity)
                     ", ahead of " (:country b) " at " (printed (:production_tonnes b)) ".")
          :entities [(str/lower-case commodity)
                     (str/lower-case (:country a))
                     (str/lower-case (:country b))]})))

   :producer
   (fn [rows {:keys [commodity country year]}]
     (let [g (sort-by :production_tonnes >
                      (filterv #(and (= commodity (:commodity %)) (= year (:year %))) rows))
           i (first (keep-indexed #(when (= country (:country %2)) %1) g))]
       (when i
         {:fact (str country " produced " (printed (:production_tonnes (nth g i))) " "
                     (:unit (nth g i)) " of " (str/lower-case commodity) " in " year ", "
                     (ordinal (inc i)) " of the " (count g)
                     " countries USGS lists that year.")
          :entities [(str/lower-case commodity) (str/lower-case country)]})))

   :absent
   (fn [rows {:keys [commodity country]}]
     (let [g  (filterv #(= commodity (:commodity %)) rows)
           cs (sort (distinct (map :country g)))
           ys (sort (distinct (map :year g)))]
       (when (and (seq g) (not-any? #(= country (:country %)) g))
         {:fact (str "The USGS table records no " country " " (str/lower-case commodity)
                     " production in " (str/join " or " ys) ": only " (oxford cs) " appear.")
          :entities (into [(str/lower-case commodity) (str/lower-case country)]
                          (map str/lower-case) cs)})))

   ;; ---- lead-phaseout.csv ---------------------------------------------------
   :ban-years
   (fn [rows {:keys [countries]}]
     (let [by  (into {} (map (juxt :entity identity)) rows)
           hit (mapv by countries)]
       (when (every? some? hit)
         {:fact (str "Ban years for leaded petrol in road vehicles: "
                     (str/join ", " (map #(str (:entity %) " " (:ban_year %)) hit)) ".")
          :entities (into ["leaded petrol"] (map (comp str/lower-case :entity)) hit)})))

   :earliest-ban
   (fn [rows _]
     (when-let [r (only-one < :ban_year rows)]
       {:fact (str "The earliest leaded-petrol ban in the OWID table is " (:entity r)
                   "'s, in " (:ban_year r) ".")
        :entities ["leaded petrol" (str/lower-case (:entity r))]}))

   :commonest-ban-year
   (fn [rows _]
     (when-let [[y n] (commonest :ban_year rows)]
       {:fact (str n " of the table's " (count rows) " countries banned leaded petrol in "
                   y ", more than in any other year.")
        :entities ["leaded petrol"]}))

   :bans-by-region
   (fn [rows {:keys [regions]}]
     (let [g (mapv (fn [r] (filterv #(= r (:world_region_according_to_owid %)) rows)) regions)]
       (when (every? seq g)
         {:fact (str "Of the " (count rows) " countries in the leaded-petrol table, "
                     (str/join "; " (mapv (fn [reg rs]
                                            (str (count rs) " are in " reg ", banning between "
                                                 (apply min (map :ban_year rs)) " and "
                                                 (apply max (map :ban_year rs))))
                                          regions g))
                     ".")
          :entities (into ["leaded petrol"] (map str/lower-case) regions)})))

   ;; ---- elections-turnout.csv -----------------------------------------------
   :turnout-of
   (fn [rows {:keys [countries]}]
     (let [by  (into {} (map (juxt :entity identity)) rows)
           hit (mapv by countries)]
       (when (every? some? hit)
         {:fact (str "Turnout of registered voters at the most recent national election since "
                     "2000: "
                     (oxford (mapv #(str (:entity %) " " (:turnout_pct %) "% in " (:year %)) hit))
                     ".")
          :entities (into ["voter turnout"] (map (comp str/lower-case :entity)) hit)})))

   :turnout-extremes
   (fn [rows _]
     (let [hi (only-one > :turnout_pct rows)
           lo (only-one < :turnout_pct rows)]
       (when (and hi lo)
         {:fact (str "The highest turnout in the V-Dem extract is " (:entity hi) "'s "
                     (:turnout_pct hi) "% in " (:year hi) ", the lowest " (:entity lo) "'s "
                     (:turnout_pct lo) "% in " (:year lo) ".")
          :entities ["voter turnout" (str/lower-case (:entity hi)) (str/lower-case (:entity lo))]})))

   :turnout-region
   (fn [rows {:keys [region]}]
     (let [g  (filterv #(= region (:world_region_according_to_owid %)) rows)
           hi (only-one > :turnout_pct g)
           lo (only-one < :turnout_pct g)]
       (when (and hi lo)
         {:fact (str region " supplies " (count g) " of the turnout extract's " (count rows)
                     " countries, from " (:entity lo) "'s " (:turnout_pct lo) "% to "
                     (:entity hi) "'s " (:turnout_pct hi) "%.")
          :entities ["voter turnout" (str/lower-case region)]})))

   :most-elections
   (fn [rows _]
     (when-let [r (only-one > :elections_since_2000 rows)]
       {:fact (str (:entity r) " has held " (:elections_since_2000 r)
                   " national elections with turnout data since 2000, more than any other "
                   "country in the extract.")
        :entities ["voter turnout" (str/lower-case (:entity r))]}))

   ;; ---- interest-rates-monthly.csv ------------------------------------------
   :rate-swing
   (fn [rows {:keys [country]}]
     (let [g  (filterv #(= country (:country %)) rows)
           hi (only-one > :rate_pct g)
           lo (only-one < :rate_pct g)]
       (when (and hi lo)
         {:fact (str "Eurostat's three-month rate for " country " runs from "
                     (printed (:rate_pct lo)) "% in " (:month lo) " to "
                     (printed (:rate_pct hi)) "% in " (:month hi) ".")
          :entities [(str/lower-case country)]})))

   :rate-spread
   (fn [rows {:keys [month]}]
     (let [g  (filterv #(= month (:month %)) rows)
           hi (only-one > :rate_pct g)
           lo (only-one < :rate_pct g)]
       (when (and hi lo)
         {:fact (str "In " month " the " (count g) " three-month rates in the Eurostat table "
                     "span " (printed (:rate_pct lo)) "% in " (:country lo) " to "
                     (printed (:rate_pct hi)) "% in " (:country hi) ".")
          :entities [(str/lower-case (:country lo)) (str/lower-case (:country hi))]})))

   :below-zero
   (fn [rows _]
     (let [all (sort (distinct (map :country rows)))
           neg (sort (distinct (map :country (filter #(neg? (:rate_pct %)) rows))))]
       (when (and (seq neg) (< (count neg) (count all)))
         {:fact (str (count neg) " of the " (count all) " economies in the rates table go below "
                     "zero at some point: " (oxford neg) ".")
          :entities (mapv str/lower-case neg)})))

   ;; `:month` is a string — "2024-03", left as it parsed, because `->val` reads
   ;; it as one and `timeseries?` keys on the column's NAME rather than its type.
   ;; So the latest month is the last of a lexical sort and never `max`, which
   ;; is only correct at all because the manifest committed ISO months.
   :coverage
   (fn [rows _]
     (let [by    (group-by :country rows)
           full  (apply max (map (comp count val) by))
           ends  (sort-by second (for [[c rs] by :when (< (count rs) full)]
                                   [c (last (sort (map :month rs)))]))
           whole (count (filter #(= full (count (val %))) by))]
       (when (seq ends)
         {:fact (str "Coverage in the rates table is ragged: "
                     (oxford (mapv (fn [[c m]] (str c " stops at " m)) ends))
                     ", while " whole " economies run all " full " months to "
                     (last (sort (map :month rows))) ".")
          :entities (mapv (comp str/lower-case first) ends)})))})

;; ----------------------------------------------------------------------------
;; the declarations — locators and queries, in notebook order
;; ----------------------------------------------------------------------------

(def quoted
  "Every fact lifted out of a prose extract, as `{:doc :from :to}`.

   The fact is the span running from the first occurrence of `:from` to the end
   of the first `:to` that follows it, in the file with its whitespace collapsed
   — so a declaration holds two short ASCII anchors and never the sentence
   itself. Three things follow, and all three are the point:

   · **A quotation cannot be mistyped into a paraphrase.** The characters
     written are the file's, including the curly apostrophes, the en dash in
     `(1662-1722)` and the OCR's `The'nard's blue`, none of which appear here.
   · **An anchor that stops matching refuses the import.** Re-fetch a prose file
     under a new revision and the run stops with the file and the anchor named,
     instead of silently taking a different sentence.
   · **A repeated passage is harmless.** `the-leaded-petrol-phase-out.md` carries
     its whole first paragraph twice, because OWID's `description` repeats its
     `descriptionShort`; the first occurrence is taken and the second is the same
     characters, so which one is found does not change the fact.

   `:entities` is not declared and cannot be: every fact off an extract carries
   that extract's `:entangles` out of the manifest, verbatim. Those are the
   fetcher's own record of what the file touches, written when it was fetched;
   choosing a narrower set per sentence would be this namespace deciding what a
   quotation is about, which is exactly the judgement it has no standing to
   make."
  [;; ---- art-objects.csv -----------------------------------------------------
   {:doc "cobalt-blue-the-pigment.md" :from "Excluding smalt" :to "cobalt blue.'"}
   {:doc "cobalt-blue-the-pigment.md" :from "The best known of these" :to "like the phosphate."}
   {:doc "cobalt-blue-the-pigment.md" :from "The original and simplest form" :to "and cobalt oxide."}

   {:doc "white-lead-the-painters-white.md" :from "The drawbacks attendant" :to "soluble in water."}
   {:doc "white-lead-the-painters-white.md" :from "On the other hand" :to "siccative character."}
   {:doc "white-lead-the-painters-white.md" :from "For when an old oil-picture" :to "if at all."}

   {:doc "cobalt-blue-kangxi-porcelain.md" :from "Underglaze blue-and-white designs" :to "professional painters."}
   {:doc "cobalt-blue-kangxi-porcelain.md" :from "Blue-and-white porcelains in brilliant" :to "foreign markets."}

   {:doc "cobalt-blue-banned-in-joseon-korea.md" :from "The blue here indicates" :to "at that time."}
   {:doc "cobalt-blue-banned-in-joseon-korea.md" :from "In that context" :to "rare luxury."}

   {:doc "dutch-trade-in-a-still-life.md" :from "Pieces like the silver platter" :to "surface textures."}
   {:doc "dutch-trade-in-a-still-life.md" :from "Kalf used different kinds" :to "surrounding shadows."}

   {:doc "chinese-vases-in-a-dutch-parlour.md" :from "Furthermore, the richly carved chest" :to "trade with Asia."}
   {:doc "chinese-vases-in-a-dutch-parlour.md" :from "The identity of the sitters" :to "considerable wealth."}

   {:doc "delft-flower-pyramid.md" :from "Flower pyramids are among" :to "in the late 1600s."}
   {:doc "delft-flower-pyramid.md" :from "During his tenure" :to "Delft ceramics."}

   {:doc "delft-nautilus-cup.md" :from "Nautilus shells were brought" :to "wealthy collectors."}
   {:doc "delft-nautilus-cup.md" :from "Early Dutch nautilus cups" :to "seventeenth centuries."}

   ;; ---- mineral-production.csv ---------------------------------------------
   {:doc "cobalt-the-congo-export-ban.md" :from "In February, Congo" :to "low prices."}
   {:doc "cobalt-the-congo-export-ban.md" :from "In October, the temporary ban" :to "national strategic reserves."}
   {:doc "cobalt-the-congo-export-ban.md" :from "China remained the world leading" :to "lithium-ion battery industry."}
   {:doc "cobalt-the-congo-export-ban.md" :from "The cobalt content of lithium-ion" :to "market share in China."}
   ;; the crossing this corpus was assembled for: a mineral report naming lead
   ;; among the substitutes "in paints", in the same sentence as the magnets.
   {:doc "cobalt-the-congo-export-ban.md" :from "Potential substitutes in other applications" :to "in diamond tools"}

   {:doc "gallium-and-the-export-controls.md" :from "No domestic primary" :to "since 1987."}
   {:doc "gallium-and-the-export-controls.md" :from "In November 2025, China lifted" :to "December 2024."}
   {:doc "gallium-and-the-export-controls.md" :from "China accounted for 99%" :to "Japan and Russia."}
   {:doc "gallium-and-the-export-controls.md" :from "GaAs was used to manufacture" :to "and solar cells."}
   {:doc "gallium-and-the-export-controls.md" :from "Gallium nitride (GaN)" :to "accounted for 26%."}

   {:doc "rare-earths-export-controls.md" :from "In April 2025, China tightened" :to "and yttrium."}
   {:doc "rare-earths-export-controls.md" :from "In October, China expanded" :to "for 1 year."}
   {:doc "rare-earths-export-controls.md" :from "The April export controls remained" :to "selected exporters."}

   ;; ---- lead-phaseout.csv ---------------------------------------------------
   {:doc "the-leaded-petrol-phase-out.md" :from "This dataset reports the year" :to "in road vehicles."}
   {:doc "the-leaded-petrol-phase-out.md" :from "Algeria was the final country" :to "in 2021."}
   {:doc "the-leaded-petrol-phase-out.md" :from "The specific date of phase-out" :to "in road vehicles)."}
   {:doc "the-leaded-petrol-phase-out.md" :from "This dataset was built by" :to "from multiple sources."}

   {:doc "lead-becomes-a-critical-mineral.md" :from "On November 7, 2025" :to "(90 FR 50494)."}
   {:doc "lead-becomes-a-critical-mineral.md" :from "The changes in the 2025 list" :to "for the 2025 list."}
   {:doc "lead-becomes-a-critical-mineral.md" :from "Based on that input" :to "were also added."}

   ;; ---- elections-turnout.csv -----------------------------------------------
   {:doc "voter-turnout-what-it-measures.md" :from "Denotes the voter turnout" :to "official results."}
   {:doc "voter-turnout-what-it-measures.md" :from "Measures the percentage of registered" :to "voter participation."}
   {:doc "voter-turnout-what-it-measures.md" :from "Data sourced from" :to "regional averages."}
   {:doc "voter-turnout-what-it-measures.md" :from "Country averages weigh" :to "typical country is doing."}
   {:doc "voter-turnout-what-it-measures.md" :from "Population-weighted averages weigh" :to "person's experience."}])

(def derived
  "Every fact read off a table, as `{:table :as :args}` — the derivation named by
   `:as` runs over the file's rows with `:args`.

   **Why so few per table, and why they are not one template repeated.**
   `mold/remember` folds two facts into one at jaccard 0.6 over their tokens, and
   a template is a machine for producing facts that are 0.6 similar: five rows
   through `\"<country> banned leaded petrol in road vehicles in <year>.\"` share
   six of nine tokens and collapse into one fact with `:strength 5`, which looks
   like a successful import and is four facts that were never written. The
   defence is arithmetic, not vigilance — jaccard is |A ∩ B| / |A ∪ B|, so a
   family stays apart only while what varies between two of its facts is large
   next to what they share. Hence terse sentences, several numbers each, and
   at most a handful of instances of any one derivation; where that was not
   enough — four countries' ban years, two countries' turnout — the rows are
   folded into ONE fact instead, which is also the sentence a person would have
   written. Measured over the seventy with `loci.memory`'s own jaccard: the
   closest pair is **0.529** — `China produced 900 metric tons of gallium…`
   against `Australia produced 92000 metric tons of lithium…` — so the family
   with the least room left is the one with the most numbers in it.

   The other half of that defence is a test, not a rule: importing into an empty
   memory must leave exactly seventy facts, and `import-facts!` reports every one
   that reinforced instead."
  [;; ---- art-objects.csv -----------------------------------------------------
   {:table "art-objects.csv" :as :matching
    :args {:word "cobalt" :columns [:medium :title] :as "medium or title"}}
   {:table "art-objects.csv" :as :matching
    :args {:word "lead" :columns [:medium] :as "medium"}}
   {:table "art-objects.csv" :as :matching
    :args {:word "delft" :columns [:culture :title :medium] :as "culture, title or medium"}}
   {:table "art-objects.csv" :as :matching
    :args {:word "porcelain" :columns [:medium] :as "medium"}}
   {:table "art-objects.csv" :as :from-country :args {:country "Netherlands"}}

   ;; ---- mineral-production.csv ---------------------------------------------
   {:table "mineral-production.csv" :as :leader :args {:commodity "Cobalt" :year 2025}}
   {:table "mineral-production.csv" :as :leader :args {:commodity "Gallium" :year 2025}}
   {:table "mineral-production.csv" :as :leader :args {:commodity "Lithium" :year 2025}}
   {:table "mineral-production.csv" :as :leader :args {:commodity "Nickel" :year 2025}}
   {:table "mineral-production.csv" :as :producer
    :args {:commodity "Rare Earths" :country "United States" :year 2025}}
   {:table "mineral-production.csv" :as :producer
    :args {:commodity "Copper" :country "Democratic Republic of Congo" :year 2025}}
   {:table "mineral-production.csv" :as :absent
    :args {:commodity "Gallium" :country "United States"}}

   ;; ---- lead-phaseout.csv ---------------------------------------------------
   {:table "lead-phaseout.csv" :as :ban-years
    :args {:countries ["Japan" "United States" "Netherlands" "China"]}}
   {:table "lead-phaseout.csv" :as :earliest-ban :args {}}
   {:table "lead-phaseout.csv" :as :commonest-ban-year :args {}}
   {:table "lead-phaseout.csv" :as :bans-by-region :args {:regions ["Africa" "Europe"]}}

   ;; ---- elections-turnout.csv -----------------------------------------------
   {:table "elections-turnout.csv" :as :turnout-of
    :args {:countries ["Netherlands" "United States"]}}
   {:table "elections-turnout.csv" :as :turnout-extremes :args {}}
   {:table "elections-turnout.csv" :as :turnout-region :args {:region "Europe"}}
   {:table "elections-turnout.csv" :as :most-elections :args {}}

   ;; ---- interest-rates-monthly.csv ------------------------------------------
   {:table "interest-rates-monthly.csv" :as :rate-swing :args {:country "United States"}}
   {:table "interest-rates-monthly.csv" :as :rate-swing :args {:country "Hungary"}}
   {:table "interest-rates-monthly.csv" :as :rate-swing :args {:country "Japan"}}
   {:table "interest-rates-monthly.csv" :as :rate-swing :args {:country "Euro area"}}
   {:table "interest-rates-monthly.csv" :as :rate-spread :args {:month "2022-07"}}
   {:table "interest-rates-monthly.csv" :as :below-zero :args {}}
   {:table "interest-rates-monthly.csv" :as :coverage :args {}}])

;; ----------------------------------------------------------------------------
;; assembling them — every declaration checked before any fact is written
;; ----------------------------------------------------------------------------

(defn- hub-of
  "corpus file (table OR prose) → the hub it belongs to."
  [file]
  (or (first (filter #(= file (:file %)) hubs))
      (first (filter #(some #{file} (:prose %)) hubs))))

(defn- facts-coverage-complaint
  "Why the declarations above and the manifest do not describe the same corpus,
   or nil. Checked before a file is opened, for `coverage-complaint`'s reason.

   The two directions are not the same check and both matter. A declaration
   naming a file the manifest does not carry would cite a document that was
   never imported. A manifest file that NO fact names is the quieter failure:
   an extract fetched, licence-cleared, committed, imported as a document — and
   then never distilled, so the entity pages it was fetched for show nothing and
   only whoever goes looking finds out."
  [m]
  (let [tables (set (map :file (:files m)))
        prose  (set (map :file (:files (:prose m))))
        d-t    (set (map :table derived))
        d-p    (set (map :doc quoted))
        unknown-as (sort (distinct (remove derivations (map :as derived))))]
    (cond
      (seq unknown-as)
      (str "no such derivation: " (str/join ", " unknown-as))

      (seq (remove tables d-t))
      (str "facts are declared against tables the manifest does not carry: "
           (str/join ", " (sort (remove tables d-t))))

      (seq (remove prose d-p))
      (str "facts are declared against prose the manifest does not carry: "
           (str/join ", " (sort (remove prose d-p))))

      (seq (remove d-t tables))
      (str "these tables were imported and no fact reads them: "
           (str/join ", " (sort (remove d-t tables))))

      (seq (remove d-p prose))
      (str "these extracts were imported and no fact quotes them: "
           (str/join ", " (sort (remove d-p prose)))))))

(defn- fact-sources
  "What a fact can cite: corpus table file → `{:obj <table id> :space <notebook
   id>}`, and prose file → the same for its document.

   Read out of the store rather than re-minted, which is the whole reason the
   facts go second: `:obj` is the id `import-corpus!` actually committed, so a
   fact's `:source` is a pair `sub/object` resolves — the shape `distill!`
   writes and `remembered-context` cites as `⌾ doc:corpus-9`.

   A document is matched to its file by TITLE, not by cell position. Position
   would agree with `hubs` by construction and would keep agreeing after
   somebody reordered one and not the other."
  [st m]
  (let [done  (imported st)
        by-ttl (into {} (map (juxt :title :file)) (:files (:prose m)))]
    (reduce (fn [acc {:keys [file]}]
              (if-let [nbk (get done file)]
                (let [objs (keep #(sub/object st (:ref %)) (nb/cells-of nbk))
                      tbl  (first (filter #(= :table (:kind %)) objs))]
                  (into (cond-> acc
                          tbl (assoc file {:obj (:id tbl) :space (:id nbk)}))
                        (for [o objs
                              :when (= :doc (:kind o))
                              :let [f (by-ttl (:title o))]
                              :when f]
                          [f {:obj (:id o) :space (:id nbk)}])))
                acc))
            {} hubs)))

(defn- span
  "The extract from the first `from` to the end of the first `to` after it, or a
   complaint naming the anchor that did not match."
  [nm body from to]
  (if-let [i (str/index-of body from)]
    (if-let [j (str/index-of body to (+ i (count from)))]
      {:text (subs body i (+ j (count to)))}
      {:error (str "prose/" nm " has no " (pr-str to) " after " (pr-str from)
                   " — the anchors this fact is located by no longer bracket anything")})
    {:error (str "prose/" nm " does not contain " (pr-str from)
                 " — the anchor this fact is located by is not in the file")}))

(defn- quoted-facts
  "The facts quoted out of one prose file: `{:facts [...]}` or `{:error \"…\"}`."
  [dir m nm src]
  (let [entry (get (prose-by-file m) nm)
        body  (squash (slurp (prose-file dir m nm)))
        ds    (filterv #(= nm (:doc %)) quoted)]
    (reduce (fn [acc {:keys [from to]}]
              (let [{:keys [text error]} (span nm body from to)]
                (if error
                  (reduced {:error error})
                  (update acc :facts conj
                          {:fact text :entities (vec (:entangles entry))
                           :source src :from nm :via :quoted :args {:from from :to to}}))))
            {:facts []} ds)))

(defn- derived-facts
  "The facts derived from one table: `{:facts [...]}` or `{:error \"…\"}`."
  [file rows src]
  (reduce (fn [acc {:keys [as args]}]
            (if-let [{:keys [fact entities]} ((derivations as) rows args)]
              (update acc :facts conj
                      {:fact fact :entities entities :source src :from file :via as :args args})
              (reduced {:error (str "the " as " derivation found nothing in " file
                                    " for " (pr-str args)
                                    " — the rows that made this fact true are not in the "
                                    "committed table, so it is not imported")})))
          {:facts []} (filterv #(= file (:table %)) derived)))

(defn- plan-facts
  "Every fact this import would write, in notebook order — table first, then the
   documents in the order the notebook cells them. `{:facts [...]}` or
   `{:error \"…\"}`, and one bad anchor anywhere refuses the whole run.

   The `(:error acc)` guard carries a refusal out through the remaining hubs
   untouched, so the reason reported is the FIRST one and not the last — the
   same rule `material-complaint`'s `some` follows, written the long way because
   this fold is accumulating as well as deciding.

   The last check is over the assembled facts rather than over either
   declaration, because neither one can make it: a quoted fact's names come out
   of the manifest and a derived fact's out of the rows, so \"this fact has no
   names\" is only answerable once both have been read. A fact with no name is
   not a small defect — `entity-payload` finds facts BY name, so it is a fact
   that is written, counted, reported as imported, and reachable from no entity
   page at all."
  [dir m srcs]
  (let [out (reduce
             (fn [acc {:keys [file prose]}]
               (if (:error acc)
                 acc
                 (let [rows (tools/parse-csv (slurp (io/file dir file)))]
                   (reduce (fn [acc r]
                             (if (:error r)
                               (reduced r)
                               (update acc :facts into (:facts r))))
                           acc
                           (cons (derived-facts file rows (get srcs file))
                                 (for [nm prose] (quoted-facts dir m nm (get srcs nm))))))))
             {:facts []} hubs)]
    (or (when-not (:error out)
          (when-let [f (first (remove #(some (complement str/blank?) (:entities %))
                                      (:facts out)))]
            {:error (str "this fact from " (:from f) " carries no entity name, so nothing "
                         "would ever find it: " (pr-str (:fact f)))}))
        out)))

;; ----------------------------------------------------------------------------
;; writing them — and saying which ones were absorbed instead
;; ----------------------------------------------------------------------------

(defn- write-facts!
  "Remember each fact, and separate the ones that landed from the ones
   `remember` folded into a fact already there.

   **Observed, never predicted.** `remember` reinforces at jaccard ≥ 0.6 over
   tokens, and this asks it what happened rather than working out in advance what
   it would do: a second copy of that rule living here is a copy that can drift
   from `loci.memory`'s, and would then report confidently about a threshold the
   engine no longer uses. The cost is that a DRY RUN cannot say which facts would
   reinforce — it lists what it would write, and the live run reports what became
   of each one.

   **The detection is the COUNT of facts, and deliberately not the ids.**
   `remember` either mints a record or updates one in place, so `all-facts` — a
   live-filtered view — grows by exactly one on an insert and not at all on a
   reinforcement. Counting is the only form of this that holds for the whole
   protocol: `mold/Recall` promises `all-facts`, it does not promise that a fact
   carries an `:id`, and a set-difference over ids reads a stub whose records
   have none as though every fact it wrote had been absorbed.

   The absorbing record is then found by its text, because reinforcement
   overwrites `:fact` with the newer sentence — which is also why `:absorbed` is
   worth reporting: it is the wording that is now gone, and this report is the
   only place it is still legible.

   One `all-facts` scan per fact, which is quadratic in a memory of this size and
   costs nothing at seventy: the alternative is a memory-internal hook, and
   knowing which existing fact swallowed which import is worth more than the
   microseconds."
  [mm facts]
  (reduce (fn [acc f]
            (let [prior  (mold/all-facts mm)
                  before (into {} (map (juxt :id :fact)) prior)]
              (mold/remember mm (:fact f) {:entities (:entities f) :source (:source f)})
              (let [after (mold/all-facts mm)]
                (if (> (count after) (count prior))
                  (update acc :remembered conj
                          (assoc f :id (:id (first (remove #(contains? before (:id %)) after)))))
                  (let [into-rec (first (filter #(= (:fact f) (:fact %)) after))]
                    (update acc :reinforced conj
                            {:fact (:fact f) :from (:from f) :into (:id into-rec)
                             :strength (:strength into-rec)
                             :absorbed (get before (:id into-rec))}))))))
          {:remembered [] :reinforced []} facts))

(defn import-facts!
  "Write the corpus's facts into `mm`, each citing the object it came from.
   **Dry run by default.**

   `st` is the store `import-corpus!` already wrote to — read only, and only to
   turn a corpus file into the id of the table or document that now holds it.
   `mm` is anything implementing `loci.mold/Recall`. `opts` takes `:dir`
   (default `\"corpus\"`) and `:dry-run?` (default `true`).

   Returns a report, never throws:

     :ok?         did the memory end up holding the corpus's facts
     :dry-run?    was this a plan or an act
     :plan        {:facts [{:fact :entities :source :from :via :args} …]
                   :count n :by-notebook {\"<title>\" n …}}
     :remembered  facts that landed as new records

   `:via` and `:args` carry the declaration that produced each sentence — the
   derivation and its arguments, or the two anchors a quotation was located by.
   They cost a few characters in the report and buy the thing a plan is for: a
   reader looking at a sentence can see the query it answers, and a caller can
   name one fact without counting positions in a vector.

     :reinforced  [{:fact :from :into :strength :absorbed} …] — the ones
                  `remember` folded into a fact already there
     :error       why not, when not

   **`:durable?` is not written, and its absence is the decision.** A fact
   arrives here unjudged, exactly like every fact an agent distils, and
   `loci.classify` judges it afterwards by the same rule as the eighty already
   in the memory. Passing `:durable? true` at import would let seventy facts
   win on arrival date what nothing else in the corpus won on argument — and
   `remember` keys on `contains?`, so passing `:durable? false` would be a
   judgement too, stamped `:durable-by \"agent\"` and past the guard that lets a
   later pass revise its own calls. Writing neither key is the only way to say
   \"nobody has judged this yet\", and that is what `weight` reads.

   **There is no undo and this does not pretend otherwise.** The substrate half
   commits five reversible transactions; this appends to a log that `sub/undo!`
   has never touched. A run interrupted half way leaves a prefix of facts, every
   one of them correctly sourced, and the only way back is the backup taken
   before it.

   What it refuses, and why refusing beats proceeding: a directory that is not a
   corpus or a manifest that will not read; material that is not what the
   manifest describes (the facts are read off these rows, so a table that has
   changed is a table these sentences are no longer about); a declaration and a
   manifest that describe different corpora, in either direction; a corpus file
   whose notebook is not in the store yet (the fact would have nothing to cite —
   run `import-corpus!` first); an anchor that no longer matches its extract; and
   a derivation that cannot find its rows."
  ([st mm] (import-facts! st mm {}))
  ([st mm {:keys [dir dry-run?] :or {dir "corpus" dry-run? true}}]
   (let [{:keys [manifest error]} (read-manifest dir)]
     (if error
       {:ok? false :dry-run? dry-run? :error error}
       (let [srcs (fact-sources st manifest)
             gap  (first (concat (remove srcs (map :table derived))
                                 (remove srcs (map :doc quoted))))]
         (if-let [why (or (facts-coverage-complaint manifest)
                          (material-complaint dir manifest)
                          (when gap
                            (str "nothing in the store was imported from " gap
                                 " — run import-corpus! first, or these facts have nothing "
                                 "to cite")))]
           {:ok? false :dry-run? dry-run? :error why}
           (let [{:keys [facts error]} (plan-facts dir manifest srcs)]
             (if error
               {:ok? false :dry-run? dry-run? :error error}
               (let [by-nb (reduce (fn [acc f]
                                     (update acc (:title (hub-of (:from f))) (fnil inc 0)))
                                   {} facts)
                     plan  {:facts facts :count (count facts) :by-notebook by-nb}]
                 (if dry-run?
                   {:ok? true :dry-run? true :plan plan}
                   (let [{:keys [remembered reinforced]} (write-facts! mm facts)]
                     {:ok? true :dry-run? false :plan plan
                      :remembered (count remembered)
                      :reinforced reinforced})))))))))))
