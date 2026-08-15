(ns loci.conversation
  "A one-off import of one Gemini conversation export — 24 prompt/response
   turns on five unrelated subjects — into notebooks, documents and facts.

   **This is deliberately not a framework.** The filename, the 24 turn titles
   and the five-way placement are literals below, checked by eye against the
   file. Generalising to other exports is a rewrite, and should be.

   Three passes, each with one job, in the order they may safely run:

     `import-conversation!`  file      → substrate   5 notebooks, 24 documents
     `distil-turns!`         file      → an EDN file  (no store, no memory)
     `import-facts!`         EDN + store → memory

   `distil-turns!` deliberately does NOT depend on the first pass. It is the
   slow, nondeterministic, costs-money one, and keeping it away from both
   stores means it can be re-run, interrupted or thrown away without anything
   durable noticing. Its output is a file a person reads and edits; only then
   does `import-facts!` write, and by then the sentences are fixed, so a dry
   run reports exactly what a live run will do.

   **The memory has no undo.** `import-conversation!` commits five reversible
   transactions; `import-facts!` appends to a log `sub/undo!` has never
   touched. That is why they are separate calls, and why the middle pass
   exists at all.

   **The facts here are a chatbot's and nothing verifies them.** `loci.corpus`
   goes to great lengths to make it structurally impossible for a model to
   write one of its facts — quoted spans and computed derivations, never a
   generated sentence. This namespace does the opposite, on purpose, because
   the material is a conversation rather than a cited source. The two kinds of
   fact stay apart by their notebook: every hub title below starts `Gemini —`
   and every intent says the link is private and the claims unchecked, so
   wherever `entity-payload` cites one it cites the notebook title beside it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [loci.agent :as agent]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.server :as server]
            [loci.substrate :as sub]))

(def source-file
  "The export this namespace imports, and the only one it can import."
  "Quantum Mechanics_ Four Postulates.txt")

(def provenance
  "The sentence every notebook's `:intent` ends with.

   The URL is in it and is useless to anybody else — a Gemini share link
   resolves only for the account that owns it. Saying so is the point: an
   intent that cited it without comment would look like a source."
  (str "Source: a Gemini conversation exported 2026-08-15 from "
       "https://gemini.google.com/app/74d060160889c9bd — a private link that "
       "resolves for nobody else. These are a chatbot's claims, not cited "
       "sources, and nothing in them has been checked."))

(def titles
  "turn number → the document's title.

   Hand-written, because the prompts are not titles: several are three
   sentences of thinking-aloud, and one opens \"ok now something completely
   different\". Titles are what LEAP matches on substring, so they are the one
   piece of this import worth typing out."
  {1  "Physics stuck in abstract maths, and the experiments that rescued it"
   2  "The aether and the Higgs field"
   3  "The Casimir effect as evidence for vacuum energy"
   4  "The Tesla cult, and Velimir Abramović"
   5  "Dejan Raković and quantum medicine"
   6  "The psychology of the pseudoscientist: ego, not money"
   7  "Why universities cannot discipline tenured pseudoscientists"
   8  "Unsolved problems in modern physics"
   9  "What axions are"
   10 "Experiments hunting dark matter and dark energy"
   11 "State-backed pseudoscience in the United States"
   12 "Historical parallels for states that reject science"
   13 "Who was punished for rejecting reality, and who was not"
   14 "The CCP as a technocratic elite: a way forward?"
   15 "Is eugenics scientifically defensible?"
   16 "Survival as the sole a-priori value, and why eugenics fails it"
   17 "Who first formulated the diversity-as-survival argument"
   18 "Japan, South Korea and the causes of population collapse"
   19 "Will these countries be run over in twenty years?"
   20 "What a pragmatic, amoral ruler would do about demographics"
   21 "Which countries actually do the pragmatic version"
   22 "Demographics and immigration in the United States"
   23 "Does removing illegal immigrants get the US what it wants?"
   24 "Low-wage work, automation and basic income"})

(def hubs
  "The five notebooks, and which turns each holds.

   **The turn lists are not contiguous and that is the whole reason they are
   written out.** The conversation returns to physics at turn 8 after four
   turns on Tesla, so any rule that cut the file into five sequential blocks
   would produce two physics notebooks that do not know about each other.
   Reading the 24 prompts and grouping them took ten minutes; a classifier
   that got this right would have taken longer and been less legible."
  [{:slug  :physics
    :title "Gemini — physics stuck in maths, and the tools that rescued it"
    :about (str "Where theory retreated into formalism until an instrument settled it — vortex "
                "atoms, the S-matrix bootstrap, epicycles — then the same question asked of the "
                "present: the aether beside the Higgs field, the Casimir effect, and what is "
                "still open.")
    :turns [1 2 3 8 9 10]}

   {:slug  :pseudoscience
    :title "Gemini — the Tesla cult and the people who sell it"
    :about (str "The cult that grew around Tesla's wilder claims, two Serbian professors who "
                "trade on it, what the swindler gets when the gain is not money, and why tenure "
                "makes it so hard to stop.")
    :turns [4 5 6 7]}

   {:slug  :states
    :title "Gemini — when states reject science"
    :about (str "Lysenko, Deutsche Physik, American eugenics and the present-day United States, "
                "asked as one question: who actually paid for rejecting reality, and who got "
                "away with it.")
    :turns [11 12 13 14]}

   {:slug  :eugenics
    :title "Gemini — eugenics, and survival as the only value"
    :about (str "Whether the science was ever sound, then the argument stripped of moral "
                "philosophy — that eugenics fails on genetic diversity alone, from a single "
                "a-priori premise — and who first put it that way.")
    :turns [15 16 17]}

   {:slug  :demography
    :title "Gemini — population collapse and the immigration answer"
    :about (str "Japan and South Korea as the leading cases, what a ruler unconstrained by "
                "morality would do about it, which countries do a version of that already, and "
                "how United States immigration policy fits.")
    :turns [18 19 20 21 22 23 24]}])

;; ----------------------------------------------------------------------------
;; the splitter — a pure function of the file's text
;; ----------------------------------------------------------------------------

(defn- strip-bom
  "The text without its byte-order mark.

   Not left to `str/trim`: the BOM is not whitespace, so trim leaves it, and it
   would end up as the first character of turn 1's prompt and of the title
   check below — invisible in every terminal that would show you the bug."
  [s]
  (if (str/starts-with? s (str \uFEFF)) (subs s 1) s))

(defn turns
  "The export's turns as `[{:n :prompt :response :raw} …]`, or `{:error \"…\"}`.

   A pure function of the text — no store, no clock, no model — which is what
   makes the reassembly check below worth anything.

   Every prompt occupies exactly one line and its response begins on the next;
   a turn runs to the line before the following prompt. That is asserted rather
   than assumed, because it is the assumption a silently-changed export would
   break first.

   **The last check is the one that matters.** A regex that drops a turn
   produces an import that looks entirely successful: five notebooks, documents
   in each, facts distilled from them. Nothing downstream counts to 24 on your
   behalf. So the turns are concatenated back and compared with the file below
   its header, and any difference is a refusal.

   That comparison is over LINES, not bytes, and the distinction is worth being
   honest about: this export is CRLF throughout, `str/split-lines` drops the
   carriage returns, and the documents are therefore stored LF — deliberately,
   since nothing downstream wants CRLF in a markdown body. So the check proves
   no turn was dropped, duplicated or reordered, and proves nothing about line
   endings. Dropping a turn is the failure that would otherwise be invisible;
   a changed line ending is not."
  [raw]
  (let [text  (strip-bom raw)
        lines (vec (str/split-lines text))
        us    (vec (keep-indexed #(when (str/starts-with? %2 "User prompt:") %1) lines))
        rs    (vec (keep-indexed #(when (str/starts-with? %2 "Response:") %1) lines))]
    (cond
      (empty? us)
      {:error "no line starts with \"User prompt:\" — this is not a Gemini export"}

      (not= (count us) (count rs))
      {:error (str "the export has " (count us) " prompts and " (count rs)
                   " responses — every prompt must have exactly one")}

      (not= rs (mapv inc us))
      {:error (str "a response does not begin on the line after its prompt — first at line "
                   (inc (first (for [i (range (count us)) :when (not= (rs i) (inc (us i)))]
                                 (us i))))
                   ", so the turns cannot be cut apart by prompt line")}

      (< (first us) 2)
      {:error "the export has no title and URL above its first prompt"}

      :else
      (let [ts (mapv (fn [n from to]
                       (let [seg (subvec lines from to)]
                         {:n n
                          :prompt   (str/trim (subs (first seg) (count "User prompt:")))
                          :response (str/trim (str/replace-first (str/join "\n" (rest seg))
                                                                 #"^Response:" ""))
                          :raw      (str/join "\n" seg)}))
                     (range 1 (inc (count us)))
                     us
                     (conj (subvec us 1) (count lines)))]
        (cond
          (some #(str/blank? (:prompt %)) ts)
          {:error (str "turn " (:n (first (filter #(str/blank? (:prompt %)) ts)))
                       " has an empty prompt")}

          (some #(str/blank? (:response %)) ts)
          {:error (str "turn " (:n (first (filter #(str/blank? (:response %)) ts)))
                       " has an empty response")}

          (not= (str/join "\n" (map :raw ts))
                (str/join "\n" (subvec lines (first us))))
          {:error "the turns do not reassemble into the file — the split lost text"}

          :else
          {:turns ts :header (subvec lines 0 (first us))})))))

(defn- doc-text
  "One turn as the document that holds it: the title, the prompt as a
   blockquote, then the response as it came.

   Prompt and response together, never the response alone — an answer without
   its question is unreadable six months later, and the prompt is also the
   natural context argument to the distiller. The numbered \"Sources\" trailers
   Gemini appends are left in place; they are part of what it said."
  [t]
  (str "# " (titles (:n t)) "\n\n> " (str/replace (:prompt t) "\n" "\n> ")
       "\n\n" (:response t) "\n"))

;; ----------------------------------------------------------------------------
;; what the declarations promise, checked before anything opens
;; ----------------------------------------------------------------------------

(defn- coverage-complaint
  "Why `hubs` and `titles` do not describe one conversation of `n` turns, or nil.

   Answered from the two literals alone, before the file is read, because that
   is where the mistake would be: a turn placed in two notebooks, or in none,
   is a typo in a vector and should be reported as one."
  [n]
  (let [placed (mapcat :turns hubs)
        dup    (->> (frequencies placed) (keep (fn [[k c]] (when (> c 1) k))) sort)
        want   (set (range 1 (inc n)))]
    (cond
      (seq dup)
      (str "these turns are placed in more than one notebook: " (str/join ", " dup))

      (not= want (set placed))
      (let [missing (sort (remove (set placed) want))
            extra   (sort (remove want placed))]
        (str "the placement does not cover the conversation's " n " turns"
             (when (seq missing) (str " — unplaced: " (str/join ", " missing)))
             (when (seq extra) (str " — placed but not in the file: " (str/join ", " extra)))))

      (seq (remove titles placed))
      (str "these turns have no title: " (str/join ", " (sort (remove titles placed)))))))

(defn imported
  "hub slug → the notebook already imported for it.

   `:imported-from` is `{:file … :hub …}` rather than corpus.clj's bare
   filename, because five notebooks come out of ONE file here and a map keyed
   by filename would collapse them. Invisible to the shell either way:
   `state-payload` copies only the keys it names, and `nb/links` reads
   `:merged-from`, so no phantom edge appears in the graph."
  [st]
  (into {} (keep (fn [o]
                   (let [f (get-in o [:value :imported-from])]
                     (when (and (map? f) (= source-file (:file f)) (:hub f))
                       [(:hub f) o]))))
        (nb/notebooks st)))

(defn- claim-complaint
  "Why the store's existing notebooks make this import unsafe, or nil."
  [st]
  (let [done (imported st)]
    (or
     (some (fn [{:keys [slug title]}]
             (when-let [o (get done slug)]
               (when (not= title (:title o))
                 (str (:id o) " says it was imported for " slug " but is titled "
                      (pr-str (:title o)) ", not " (pr-str title)
                      " — refusing rather than writing a second notebook for one hub"))))
           hubs)

     (some (fn [{:keys [slug title]}]
             (when-let [o (first (filter #(and (= title (:title %))
                                               (not= slug (get-in % [:value :imported-from :hub])))
                                         (nb/notebooks st)))]
               (str (:id o) " is already titled " (pr-str title)
                    " — refusing rather than putting two notebooks of that name in the overview")))
           hubs))))

;; ----------------------------------------------------------------------------
;; pass 0 — the substrate half
;; ----------------------------------------------------------------------------

(defn- view
  "A read-only store over the objects minted so far.

   Nothing is committed until each notebook's `:tx`, so asking the LIVE store
   for 24 document ids in a row hands back the same id 24 times and the last
   `:put` erases the rest with no error anywhere. This is corpus.clj's guard
   and it is here for the same reason."
  [objm]
  (sub/->FrozenStore {:objects objm} []))

(defn- mint
  "Every object this import would write, as one entry per notebook carrying its
   `:tx`.

   **Documents are minted in turn order, before any notebook**, so `doc:gem-7`
   is turn 7 and stays turn 7. Minting them hub by hub would have made
   `doc:gem-4` the eighth turn, because the physics notebook takes 1-3 and then
   8-10 — an id that lies about which turn it holds, in a store where ids are
   what people quote at each other. The ids are still `server/next-id`'s; only
   the order it is called in is chosen."
  [st ts todo]
  (let [[objm docs]
        (reduce (fn [[objm acc] t]
                  (let [did (server/next-id (view objm) "doc:gem-")
                        obj {:id did :kind :doc :title (titles (:n t))
                             :value (doc-text t)}]
                    [(assoc objm did obj) (assoc acc (:n t) obj)]))
                [(sub/objects st) {}] ts)]
    (second
     (reduce (fn [[objm out] {:keys [slug title about turns]}]
               (let [sid   (server/next-id (view objm) "space:gem-")
                     mine  (mapv docs turns)
                     sobj  {:id sid :kind :space :title title
                            :value {:intent (str about " " provenance)
                                    :cells  (mapv (fn [d] {:ref (:id d)}) mine)
                                    :imported-from {:file source-file :hub slug}}}
                     events (conj (mapv (fn [d] {:op :put :id (:id d) :value d}) mine)
                                  {:op :put :id sid :value sobj})]
                 [(assoc objm sid sobj)
                  (conj out {:id sid :title title :hub slug
                             :documents (mapv (fn [n] {:turn n :id (:id (docs n))
                                                       :title (titles n)
                                                       :chars (count (:value (docs n)))})
                                              turns)
                             :events (count events)
                             :tx events})]))
             [objm []] todo))))

(defn import-conversation!
  "Turn the export into five notebooks and 24 documents. **Dry run by default.**

   `opts` takes `:file` (default `source-file`) and `:dry-run?` (default true).
   Returns a report, never throws:

     :ok?            did the store end up holding the conversation
     :dry-run?       was this a plan or an act
     :plan           {:notebooks [{:id :title :hub :events
                                   :documents [{:turn :id :title :chars} …]} …]
                      :already [{:hub :id :title} …]
                      :notebooks-n :documents-n :events :commits}
     :already-done?  every hub is already there; nothing was written
     :committed      the store's event count afterwards
     :error          why not, when not

   A dry run returns the same plan value a live run reports, computed by the
   same code and carrying the same ids, so rehearsing against a copy of the
   store and comparing means something.

   **One commit per notebook, five in all.** `sub/undo!` therefore takes back
   the last notebook and its documents while it is still the last commit, which
   is the honest limit of this half's reversibility. A run interrupted half way
   leaves a prefix of whole notebooks and the next run finishes the job."
  ([st] (import-conversation! st {}))
  ([st {:keys [file dry-run?] :or {file source-file dry-run? true}}]
   (let [f (io/file file)]
     (if-not (.isFile f)
       {:ok? false :dry-run? dry-run? :error (str "there is no export at " (.getPath f))}
       (let [{ts :turns :keys [error]} (turns (slurp f))]
         (if error
           {:ok? false :dry-run? dry-run? :error error}
           (if-let [why (or (coverage-complaint (count ts)) (claim-complaint st))]
             {:ok? false :dry-run? dry-run? :error why}
             (let [done    (imported st)
                   already (vec (for [{:keys [slug]} hubs :let [o (get done slug)] :when o]
                                  {:hub slug :id (:id o) :title (:title o)}))
                   todo    (remove #(get done (:slug %)) hubs)]
               (if (empty? todo)
                 {:ok? true :dry-run? dry-run? :already-done? true
                  :plan {:notebooks [] :already already
                         :notebooks-n 0 :documents-n 0 :events 0 :commits 0}}
                 (let [entries (mint st ts todo)
                       ids     (mapcat (fn [e] (map :id (:tx e))) entries)
                       taken   (filterv #(sub/object st %) ids)
                       plan    {:notebooks   (mapv #(dissoc % :tx) entries)
                                :already     already
                                :notebooks-n (count entries)
                                :documents-n (reduce + (map (comp count :documents) entries))
                                :events      (reduce + (map :events entries))
                                :commits     (count entries)}]
                   (cond
                     (seq taken)
                     {:ok? false :dry-run? dry-run?
                      :error (str "the ids this import minted are occupied: "
                                  (str/join ", " taken))}

                     dry-run?
                     {:ok? true :dry-run? true :plan plan}

                     :else
                     (do (doseq [e entries] (sub/commit! st {:op :tx :events (:tx e)}))
                         {:ok? true :dry-run? false :plan plan
                          :committed (count (sub/history st))}))))))))))))

;; ----------------------------------------------------------------------------
;; pass 1 — the distiller, and the file you read before anything is remembered
;; ----------------------------------------------------------------------------

(def ^:private hub-of
  (into {} (for [h hubs, n (:turns h)] [n h])))

(defn distil-turns!
  "Run the distiller over each turn and write the proposed facts to `out`.

   **Writes to no store and no memory.** The output is an EDN file a person
   reads, edits and deletes lines from; `import-facts!` then writes exactly
   what survives. That is the only way to get a meaningful dry run out of a
   nondeterministic step, and the only gate available in front of a memory that
   cannot undo.

   `opts` takes `:file` (default `source-file`) and `:only` — a set of turn
   numbers, for re-running the handful the model did badly on without paying
   for the other twenty.

   Each turn is distilled with its notebook's subject as context, so a sentence
   that would otherwise read \"the ban came in 1998\" says which ban. A turn the
   model fails on is recorded with `:error` and no facts rather than dropped,
   because a missing turn in this file is invisible and a recorded failure is
   a line you can see."
  [out & [{:keys [file only] :or {file source-file}}]]
  (let [{ts :turns :keys [error]} (turns (slurp (io/file file)))]
    (if error
      {:ok? false :error error}
      (let [want (filter #(or (nil? only) (contains? only (:n %))) ts)
            rows (vec (for [t want
                            :let [h (hub-of (:n t))
                                  ctx (str (:title h) " — " (titles (:n t)))]]
                        (try
                          {:turn (:n t)
                           :facts (vec (for [f (agent/distill-facts ctx (doc-text t))]
                                         (cond-> {:fact (:fact f)
                                                  :entities (mapv str (:entities f))}
                                           (boolean? (:durable f))
                                           (assoc :durable (:durable f)))))}
                          (catch Exception e
                            {:turn (:n t) :facts [] :error (.getMessage e)}))))
            payload {:file file
                     :turns (count want)
                     :facts (vec (for [r rows, f (:facts r)] (assoc f :turn (:turn r))))
                     :failed (vec (for [r rows :when (:error r)]
                                    {:turn (:turn r) :error (:error r)}))}]
        (with-open [w (io/writer (io/file out))]
          (binding [*out* w] (pp/pprint payload)))
        {:ok? true :out (str out) :turns (count want)
         :facts (count (:facts payload)) :failed (:failed payload)}))))

;; ----------------------------------------------------------------------------
;; pass 2 — the half that cannot be taken back
;; ----------------------------------------------------------------------------

(defn- turn-sources
  "turn number → `{:obj <doc id> :space <notebook id>}`, read out of the store.

   Documents are matched to turns **by title, not by cell position**. Position
   would agree with `hubs` by construction and would keep agreeing after
   somebody reordered one and not the other."
  [st]
  (let [by-title (into {} (map (juxt val key)) titles)]
    (into {} (for [[_ nbk] (imported st)
                   o       (keep #(sub/object st (:ref %)) (nb/cells-of nbk))
                   :when   (= :doc (:kind o))
                   :let    [n (by-title (:title o))]
                   :when   n]
               [n {:obj (:id o) :space (:id nbk)}]))))

(defn- write-facts!
  "Remember each fact, separating the ones that landed from the ones `remember`
   folded into a fact already there.

   Observed, never predicted: `remember` reinforces above a similarity
   threshold that lives in `loci.memory`, and a second copy of that rule here
   would drift from it. So this asks what happened. Detection is by the COUNT
   of facts — `remember` either mints a record or updates one in place — and
   not by a set difference over ids, which `mold/Recall` does not promise.

   `:absorbed` is the wording the reinforcement overwrote. This report is the
   only place it is still legible, which is exactly why it is reported."
  [mm facts]
  (reduce (fn [acc f]
            (let [prior  (mold/all-facts mm)
                  before (into {} (map (juxt :id :fact)) prior)]
              (mold/remember mm (:fact f)
                             (cond-> {:entities (:entities f) :source (:source f)}
                               (boolean? (:durable f)) (assoc :durable? (:durable f))))
              (let [after (mold/all-facts mm)]
                (if (> (count after) (count prior))
                  (update acc :remembered conj
                          (assoc f :id (:id (first (remove #(contains? before (:id %)) after)))))
                  (let [into-rec (first (filter #(= (:fact f) (:fact %)) after))]
                    (update acc :reinforced conj
                            {:fact (:fact f) :turn (:turn f) :into (:id into-rec)
                             :strength (:strength into-rec)
                             :absorbed (get before (:id into-rec))}))))))
          {:remembered [] :reinforced []} facts))

(defn import-facts!
  "Write the sidecar's facts into `mm`, each citing the document it came from.
   **Dry run by default.**

   `st` is read only, and only to turn a turn number into the id of the
   document that now holds it — so `import-conversation!` must have run first,
   or the facts would have nothing to cite. `mm` is anything implementing
   `loci.mold/Recall`.

   Returns a report, never throws:

     :ok? :dry-run?
     :plan        {:facts [{:fact :entities :durable :turn :source} …]
                   :count n :by-notebook {\"<title>\" n …}}
     :remembered  how many landed as new records
     :reinforced  [{:fact :turn :into :strength :absorbed} …]
     :error       why not, when not

   Because the sentences come off a file rather than a model, the plan a dry
   run prints is the plan a live run writes. What it still cannot tell you is
   which facts will REINFORCE rather than land — that is `remember`'s decision,
   observed after the fact, and the reason to read the sidecar first.

   **There is no undo.** A run interrupted half way leaves a prefix of facts,
   every one correctly sourced, and the only way back is a backup taken before
   it."
  ([st mm sidecar] (import-facts! st mm sidecar {}))
  ([st mm sidecar {:keys [dry-run?] :or {dry-run? true}}]
   (let [f (io/file sidecar)]
     (if-not (.isFile f)
       {:ok? false :dry-run? dry-run? :error (str "there is no sidecar at " (.getPath f)
                                                  " — run distil-turns! first")}
       (let [payload (try (edn/read-string (slurp f)) (catch Exception e {::bad (.getMessage e)}))
             srcs    (turn-sources st)]
         (cond
           (::bad payload)
           {:ok? false :dry-run? dry-run? :error (str sidecar " is not readable EDN: " (::bad payload))}

           (not (seq (:facts payload)))
           {:ok? false :dry-run? dry-run? :error (str sidecar " carries no :facts")}

           :else
           (let [want (sort (distinct (map :turn (:facts payload))))
                 gap  (first (remove srcs want))
                 bad  (first (remove #(some (complement str/blank?) (:entities %))
                                     (:facts payload)))]
             (cond
               gap
               {:ok? false :dry-run? dry-run?
                :error (str "nothing in the store holds turn " gap
                            " — run import-conversation! first, or these facts have "
                            "nothing to cite")}

               bad
               {:ok? false :dry-run? dry-run?
                :error (str "this fact from turn " (:turn bad) " carries no entity name, so "
                            "no entity page would ever find it: " (pr-str (:fact bad)))}

               :else
               (let [facts (mapv #(assoc % :source (srcs (:turn %))) (:facts payload))
                     by-nb (reduce (fn [acc x]
                                     (update acc (:title (hub-of (:turn x))) (fnil inc 0)))
                                   {} facts)
                     plan  {:facts facts :count (count facts) :by-notebook by-nb}]
                 (if dry-run?
                   {:ok? true :dry-run? true :plan plan}
                   (let [{:keys [remembered reinforced]} (write-facts! mm facts)]
                     {:ok? true :dry-run? false :plan plan
                      :remembered (count remembered)
                      :reinforced reinforced})))))))))))
