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
            [loci.notebook :as nb]
            [loci.server :as server]
            [loci.substrate :as sub]
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
   `:tx` that realises it."
  [objm dir m {:keys [file title about prose]}]
  (let [tentry (get (by-file m) file)
        rows   (tools/parse-csv (slurp (io/file dir file)))
        tid    (server/next-id (view objm) "tbl:corpus-")
        tobj   {:id tid :kind :table :title (:title tentry) :value rows}
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

(defn- mint [st dir m todo]
  (second (reduce (fn [[objm out] hub]
                    (let [[objm entry] (mint-hub objm dir m hub)]
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
             (let [entries (mint st dir manifest todo)
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
