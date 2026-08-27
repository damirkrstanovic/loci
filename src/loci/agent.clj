(ns loci.agent
  "The assistance layer — an OpenAI-compatible chat client, DeepSeek by default.

   Two uses, both Raskin-honest: the agent only ever *proposes*. For molding it
   emits a declarative view-spec our deterministic interpreter executes; for
   delegation it drafts text that lands as a reversible substrate object. The
   probabilistic model never touches the substrate's rules directly.

   Endpoint, key and model each read the real environment first, then `loci.env`
   (see `loci.config` — the one file, Docker or not), then a single-value file in
   the working directory, then a default:

     LOCI_LLM_ENDPOINT → .llm-endpoint → https://api.deepseek.com/chat/completions
     LOCI_LLM_API_KEY  → DEEPSEEK_API_KEY → .llm-key → .deepseek-key (both gitignored)
     DEEPSEEK_MODEL    → .deepseek-model → deepseek-v4-flash

   The endpoint is a full URL, path included, so any OpenAI-compatible
   chat-completions endpoint will do — including one on your own hardware.
   deepseek-v4-flash is the V4 name of what deepseek-chat used to route to.
   DEEPSEEK_MODEL names the model on whichever server is configured; the
   variable name is historical, kept so existing setups keep working."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.config :as config]
            [org.httpkit.client :as hc]))

(defn- from-file [path] (let [f (io/file path)] (when (.exists f) (str/trim (slurp f)))))

(defn- env
  "One indirection over the environment. System/getenv is a Java static, which
   with-redefs cannot reach, so without this seam the resolution order below is
   untestable — every config test on this repo binds it.

   `loci.config/env` reads the real environment first and then `loci.env`, so the
   file that already configures `docker compose up` configures `clojure -M:serve`
   too. Still a function here rather than an alias, because it is that seam."
  [k]
  (config/env k))

;; A set-but-empty variable is unset. `loci.env.example` ships
;; `LOCI_LLM_API_KEY=` with no value, and "" is truthy in Clojure — so an empty
;; key resolved to "" and the request went out as `Authorization: Bearer `,
;; which a llama.cpp started without --api-key rejects. The honest "no LLM key"
;; error never fired, and what the user saw was a 401 that reads like a wrong
;; key. Applied at each site rather than inside `env`, because with-redefs
;; replaces `env` wholesale and would redefine the check away in the very tests
;; meant to prove it.
(defn- non-blank [s] (when-not (str/blank? s) s))

;; Endpoint, key and model all resolve per call rather than at load. `endpoint`
;; used to be a `def`: frozen when the namespace was first required, so it could
;; be neither pointed elsewhere nor reached by a test.
(defn- endpoint []
  (or (non-blank (env "LOCI_LLM_ENDPOINT")) (non-blank (from-file ".llm-endpoint"))
      "https://api.deepseek.com/chat/completions"))

(defn- model []
  (or (non-blank (env "DEEPSEEK_MODEL")) (non-blank (from-file ".deepseek-model"))
      "deepseek-v4-flash"))

(defn- timeout-ms
  "How long to wait on one LLM call, in milliseconds. `LOCI_LLM_TIMEOUT_MS`,
   else 180000.

   **This is http-kit's IDLE timeout, and for a non-streaming completion that
   makes it effectively time-to-first-byte** — the whole body arrives at once
   when the model has finished thinking, so nothing arrives until then. It is
   not a budget for a slow trickle; it is a deadline for the entire response.

   It was 60000, and that number is what made agent flows unusable. Measured
   2026-08-15 against the one flow this project has ever run: a bare
   `research!` on a narrow prompt finished in 31 s and passed, while the same
   verb inside a flow — same model, plus the notebook context and a broader
   prompt — exceeded 60 s and died with `TimeoutException: idle timeout`, at
   step 1 of 5. No gate had ever been reached in the history of the project.

   180 s is chosen to clear a research step with several tool rounds rather
   than to be generous: `chat-tools` allows five rounds, each its own request,
   so this bounds ONE of them. A configurable knob because the right value
   belongs to whichever model is behind `LOCI_LLM_ENDPOINT`, and a reasoning
   model is slower than a chat one by more than a factor a default can guess."
  []
  (or (some-> (non-blank (env "LOCI_LLM_TIMEOUT_MS")) parse-long) 180000))

(defn- api-key []
  ;; LOCI_LLM_API_KEY first: a token for your own server should not have to be
  ;; filed under a vendor you are not using. `.llm-key` is the same argument for
  ;; the file half of the chain, which used to end at `.deepseek-key` — the
  ;; endpoint already had `.llm-endpoint`, so this only closes the gap. Both
  ;; DeepSeek names still resolve, after the neutral ones, so every setup that
  ;; predates this keeps working with nothing changed.
  (or (non-blank (env "LOCI_LLM_API_KEY")) (non-blank (env "DEEPSEEK_API_KEY"))
      (non-blank (from-file ".llm-key")) (non-blank (from-file ".deepseek-key"))))

(defn request
  "POST to the configured LLM; return the assistant message map {:content .. :tool_calls ..}."
  [messages & {:keys [json? tools]}]
  (let [key (or (api-key)
                (throw (ex-info (str "no LLM key (LOCI_LLM_API_KEY / DEEPSEEK_API_KEY / "
                                     ".llm-key / .deepseek-key)")
                                {})))
        resp @(hc/post (endpoint)
                {:timeout (timeout-ms)
                 :headers {"Authorization" (str "Bearer " key) "Content-Type" "application/json"}
                 :body (json/write-str (cond-> {:model (model) :messages messages :temperature 0.2}
                                         json? (assoc :response_format {:type "json_object"})
                                         tools (assoc :tools tools)))})]
    (when-let [e (:error resp)] (throw (ex-info (str "LLM request failed: " e) {})))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (when (:error body) (throw (ex-info (str "LLM error: " (get-in body [:error :message])) {})))
      (-> body :choices first :message))))

(defn chat
  "Send messages; return the assistant text content. :json? true forces JSON."
  [messages & opts]
  (:content (apply request messages opts)))

(defn- strip-dsml
  "DeepSeek sometimes leaks tool-call markup (<｜｜DSML｜｜…>) into content text.
   Cut from the first such marker to the end — it's always trailing junk."
  [s]
  (when s (str/trim (str/replace s #"(?s)<[^>]*DSML.*$" ""))))

(defn chat-tools
  "Tool-using loop: the model may emit tool_calls, which `tool-fn` (name args ->
   result data) executes; results feed back until it returns a final answer.
   Capped at 5 rounds."
  [messages tools tool-fn]
  (loop [msgs (vec messages) n 0]
    (let [msg (request msgs :tools tools)
          tcs (:tool_calls msg)]
      (if (and (seq tcs) (< n 5))
        (let [results (mapv (fn [tc]
                              (let [nm   (get-in tc [:function :name])
                                    args (try (json/read-str (get-in tc [:function :arguments]) :key-fn keyword)
                                              (catch Exception _ {}))]
                                {:role "tool" :tool_call_id (:id tc)
                                 :content (json/write-str (tool-fn nm args))}))
                            tcs)]
          (recur (into (conj msgs msg) results) (inc n)))
        ;; If the model still wants tools but we hit the cap, force one final
        ;; turn telling it to stop and synthesize — otherwise it leaks the tool
        ;; call as raw DSML text. ponytail: one extra call, no state machine.
        (strip-dsml
         (if (seq tcs)
           (:content (request (conj msgs {:role "user" :content
             "Stop using tools. Using only what you've gathered, write the final answer now. Do NOT emit any tool call."})))
           (:content msg)))))))

(defn- strip-code-fence [s]
  (when s (-> s str/trim
              (str/replace #"(?s)^```[a-zA-Z]*\n?" "")
              (str/replace #"(?s)\n?```\s*$" "")
              str/trim)))

(defn make-applet
  "Natural-language request → a self-contained JS function body that visualizes
   or computes over the object's rows. Called in the browser as fn(data, el):
   `data` is the array of row objects, `el` is an empty element to render into.
   The agent proposes; the code is stored as a reversible, inspectable object."
  [columns sample prompt]
  (let [sys (str "You write ONE self-contained JavaScript function BODY that renders a visualization in the "
                 "browser. It is called as fn(data, el): `data` is an array of row objects with keys "
                 (json/write-str columns) " (sample: " (json/write-str sample) "); `el` is an empty DOM "
                 "element you render into. Create a <canvas> or DOM nodes inside `el` and draw. For animation "
                 "use requestAnimationFrame and STOP the loop when the element leaves the page: start each "
                 "frame with `if(!el.isConnected)return;`. Size to ~640x420. Use only vanilla JS and the "
                 "Canvas/DOM APIs — NO external libraries, NO imports, NO network. Derive everything from "
                 "`data`. Respond with ONLY the function body (statements) — no markdown fences, no prose, "
                 "no function signature, no surrounding braces.")]
    (strip-code-fence (chat [{:role "system" :content sys} {:role "user" :content prompt}]))))


(defn choose-technique
  "Given a table and a free-form request, pick the SIMPLEST mechanism that does
   the job. The user never picks the implementation — the agent does."
  [columns sample prompt]
  (let [sys (str "A user wants something done with a data table (columns " (json/write-str columns)
                 ", sample " (json/write-str sample) "). Pick the SIMPLEST technique. Respond ONLY as "
                 "JSON {\"technique\": one of}:\n"
                 "\"view\" — a static rendering of the EXISTING rows: chart (bar/line), grouped/aggregated/"
                 "sorted/filtered/pivoted table, cards or list. Prefer this whenever a declarative chart or "
                 "table answers the request.\n"
                 "\"transform\" — produce NEW data to keep: add or derive a column, compute, filter or "
                 "aggregate INTO a new table the user can mold further.\n"
                 "\"applet\" — needs custom drawing, animation, simulation or interaction a static chart "
                 "can't do (e.g. animate, simulate, orbit, interactive).\n"
                 "Choose exactly one.")
        t (-> (chat [{:role "system" :content sys} {:role "user" :content prompt}] :json? true)
              (json/read-str :key-fn keyword) :technique)]
    (if (#{"view" "applet" "transform"} t) t "view")))

(def ^:private clj-transform-sys
  "System prompt for `make-clj-transform`. Five verbs, the same five rows as
   the protocol table in `loci.sandbox`'s namespace docstring — `table`/
   `tables`, `q`, `q-reduce`, `q-all`, `keep!` — and the one instruction that
   matters most: do the arithmetic in `q`. There is no `rows` in scope; a
   model that writes one gets an honest eval error, not a silent sample."
  (str
   "You write ONE Clojure expression that answers a question about a data table. "
   "You do NOT have the rows in hand — you have a database. These are in scope:\n\n"
   "  table                          this table, as a HoneySQL relation\n"
   "  tables                         {id relation} for every table in the notebook\n"
   "  (q honeysql)                   => rows, a vector of maps. Runs in DuckDB.\n"
   "                                  (meta result) carries :total (the answer's\n"
   "                                  true size) and :capped (true when you got a\n"
   "                                  page and not the whole answer)\n"
   "  (q-reduce honeysql f init)     fold over EVERY row, one bounded batch at a\n"
   "                                  time — f is (fn [acc batch] …), batch a vector\n"
   "  (q-all honeysql)               every row of the answer, in memory. EXPENSIVE —\n"
   "                                  about 5 µs/row plus 3.7 µs/cell. Use only when\n"
   "                                  nothing else will do.\n"
   "  (keep! \"Title\" honeysql)       materialise a query as its own table — no row\n"
   "                                  ever enters memory. Returns the new table's id.\n"
   "  (keep! \"Title\" rows)           same, for rows you already hold in Clojure.\n\n"
   "DO THE ARITHMETIC IN q — group, sum, filter, sort, join, all in HoneySQL. Use "
   "Clojure only for what SQL cannot say.\n\n"
   "A HoneySQL query looks like:\n"
   "  {:select [:region [[:sum :revenue] :revenue]]\n"
   "   :from [[table :t]] :group-by [:region] :order-by [[:revenue :desc]]}\n\n"
   "Return exactly ONE of three shapes:\n"
   "  · a vector of maps           → becomes a data table\n"
   "  · a vector of report blocks  → becomes a report\n"
   "  · a string                   → becomes a note\n\n"
   "A report block is one of:\n"
   "  {:block :heading :text \"…\"}\n"
   "  {:block :text :text \"…\"}\n"
   "  {:block :ref :id <a keep! id>}\n"
   "  {:block :chart :ref <a keep! id> :spec {…}}\n\n"
   "If (meta r) says :capped for a result you want to show in full, call keep! and "
   "reference the table it makes — a report block must never carry rows straight "
   "from a capped q result.\n\n"
   "Use ONLY clojure.core and Math/* (e.g. Math/round, Math/pow, Math/sqrt). NO "
   "other Java interop, NO I/O, NO atoms. Numeric results must be doubles or "
   "rounded longs, NEVER ratios — wrap division as (double (/ a b)) or round it.\n\n"
   "Respond with ONLY the Clojure expression. No prose, no markdown fence."))

(defn make-clj-transform
  "The Clojure the model writes for one sandboxed run over a table. `profile`
   is `TableStore/column-profile`'s output and `samples` is `column-samples`'
   — both bounded, and NEITHER is a row of the table. With `prev-code` + `err`,
   asks the model to correct its failed attempt — the retry contract this
   function always had.

   Before 2026-08-27 this took three sample rows and asked for a function over
   `rows`, which is exactly what let the model fold a sample and hand the
   result back as the whole table's answer. There is no `rows` any more — see
   `loci.sandbox`'s namespace docstring for why that is the enforcement and
   not merely a convention."
  [profile samples prompt prev-code err]
  (let [ctx (str "columns: " (str/join ", " (map name (:cols profile))) "\n"
                 "numeric: " (str/join ", " (map name (:numeric profile))) "\n"
                 "categorical: " (str/join ", " (map name (:categorical profile))) "\n"
                 "rows: " (:rows profile) "\n"
                 "sample values:\n"
                 (str/join "\n" (for [[c vs] samples]
                                  (str "  " (name c) ": " (str/join " | " vs)))))
        usr (cond-> (str ctx "\n\nTask: " prompt)
              ;; a failed attempt comes back with its error so the model can fix it
              prev-code (str "\n\nYour previous attempt:\n" prev-code
                             "\n\nIt failed with:\n" err "\n\nFix it."))]
    (strip-code-fence (chat [{:role "system" :content clj-transform-sys}
                             {:role "user" :content usr}]))))

(defn describe-view
  "Natural-language request → a JSON view-spec over the given columns."
  [columns sample prompt]
  (let [sys (str "Translate a natural-language request into a JSON view-spec over tabular data. "
                 "Available columns: " (json/write-str columns) ". Sample rows: " (json/write-str sample) ". "
                 "Respond ONLY with JSON: {\"label\":string, \"kind\":\"table|chart|cards|list\", "
                 "\"chart\":\"bar|line\"|null, \"group_by\":col|null, \"measure\":numeric-col|null, "
                 "\"agg\":\"sum|avg|count\", \"filter\":{\"col\":..,\"op\":\"=|>|<\",\"val\":..}|null, "
                 "\"sort\":\"asc|desc\"|null, \"limit\":int|null, \"columns\":[col,...]|null}. "
                 "Use only listed column names. Keep the spec minimal and faithful to the request.")]
    (json/read-str (chat [{:role "system" :content sys} {:role "user" :content prompt}] :json? true)
                   :key-fn keyword)))

(defn answer
  "Answer a question grounded in a digest of the workspace data."
  [digest question]
  (chat [{:role "system"
          :content (str "Answer the user's question using ONLY the workspace data below. "
                        "Cite the object ids you used (e.g. tbl:revenue). If the data doesn't contain "
                        "the answer, say so plainly — do not invent. Be concise. Markdown.\n\n"
                        "=== WORKSPACE DATA ===\n" digest)}
         {:role "user" :content question}]))

(defn plan-space
  "A user's goal → a space (intention) + the relevant existing objects to gather."
  [objects prompt]
  (let [sys (str "You set up a workspace 'space' — an intention — from the user's goal. "
                 "Existing objects you may gather as members: " (json/write-str objects) ". "
                 "Respond ONLY with JSON: {\"title\":string (short, no quotes), "
                 "\"intent\":string (one sentence), "
                 "\"members\":[id,...] (only ids from the list that are genuinely relevant; may be empty)}.")]
    (json/read-str (chat [{:role "system" :content sys} {:role "user" :content prompt}] :json? true)
                   :key-fn keyword)))

(defn distill-facts
  "Post-flow memory distillation: pull 1-5 atomic facts out of what an agent
   flow produced. Returns [{:fact string :entities [string]} …], each fact
   also carrying `:durable` true/false — or **not carrying the key at all**,
   which is a third state and the one the rest of this docstring is about.

   Each sentence is told to name its own subject: a fact is read later with
   no notebook around it, so 'the Aeon path locks out the Lich ending' has to
   say which game. `context` already tells the model what the subject is —
   this only makes saying so part of the sentence. Applies to new facts only;
   the ones already on file are not retroactively rewritten (see entities
   design doc §6) — that would be a silent rewrite of what was recorded,
   which is a different act from reinforcing it.

   `:durable` is the model's answer to a second question: does this claim go
   out of date? A law, a definition or a historical record does not; a
   measurement of a world that moves does. `weight` reads it (as `:durable?`)
   to skip decay, so a fact like T² ∝ a³ stops being ranked into oblivion by
   arithmetic. The prompt teaches the axis BY EXAMPLE, because \"durable\" is
   no more self-explanatory to a model than it was to us — naming the field
   and leaving it undefined would get an answer, just not one about this.

   **`:durable` is absent, not false, when the model did not answer** — an
   older model, a refusal, a malformed line. Nothing here coerces it: absent
   means *nobody has judged this yet*, which is the state the whole corpus is
   in, and `remember` writes neither key for it. `distill!` (server.clj) keeps
   the distinction on the way through.

   The word 'durable' has been dropped from the first sentence, where it used
   to mean 'worth remembering'. Leaving it was the alternative, and it would
   have put the same word in front of the model twice in one prompt with two
   different meanings — the exact ambiguity a field this terse cannot afford.
   'worth remembering' was already there and carries what was lost."
  [context text]
  (let [sys (str "Extract 1-5 atomic facts worth remembering from this work — "
                 "figures, causal claims, named entities. One sentence each; skip process "
                 "notes and pleasantries. **Each sentence must name its own subject** — it "
                 "will be read on its own, with no notebook around it, so \"the Aeon path "
                 "locks out the Lich ending\" must say which game. "
                 "For each fact also judge whether it is DURABLE: true if the claim will "
                 "never go out of date — a law, a definition, a historical record — and "
                 "false if it carries an implicit \"as of now\", so that re-checking the "
                 "world later could change it. \"Kepler's Third Law states that T² ∝ a³\" "
                 "is durable; \"lithography capacity is booked through 2027\" is not. "
                 "Respond ONLY as JSON "
                 "{\"facts\":[{\"fact\":\"…\",\"entities\":[\"lowercase\",…],"
                 "\"durable\":true|false}]}."
                 (when (seq (str context)) (str "\nThe work was about: " context)))]
    (:facts (json/read-str (chat [{:role "system" :content sys}
                                  {:role "user" :content (str text)}] :json? true)
                           :key-fn keyword))))

(defn propose-subtopics
  "Deep-dive planning: given a research-hub notebook, propose 2-3 focused
   subtopics. Returns [{:title string :intent string :query string} …]."
  [title intent digest]
  (let [sys (str "You plan research deep-dives. Given a hub notebook, propose 2-3 focused "
                 "subtopics that each deserve their own notebook. Respond ONLY as JSON "
                 "{\"subtopics\":[{\"title\":\"short\",\"intent\":\"one sentence\","
                 "\"query\":\"the research question to pursue\"}]}.\n"
                 "Hub: " title ". Intent: " intent ".\nFindings so far:\n" digest)]
    (:subtopics (json/read-str (chat [{:role "system" :content sys}
                                      {:role "user" :content "Propose the deep-dives now."}]
                                     :json? true)
                               :key-fn keyword))))

(defn propose-tags
  "Two or three subject tags for a notebook, drawn from what is actually in it.
   Returns [string …] — the server cleans and the human approves."
  [title intent digest]
  (let [sys (str "You tag a research notebook by SUBJECT so it can be grouped with related "
                 "work. Propose 2-3 short lower-case tags — one or two words each, the "
                 "subject matter and not the activity ('semiconductors', not 'research'). "
                 "Respond ONLY as JSON {\"tags\":[\"…\"]}.\n"
                 "Notebook: " title ". Intent: " intent ".\nWhat is in it:\n" digest)]
    (:tags (json/read-str (chat [{:role "system" :content sys}
                                 {:role "user" :content "Propose the tags now."}]
                                :json? true)
                          :key-fn keyword))))

(defn plan-flow
  "Plan a multi-step flow toward `goal`. Returns raw steps (server validates)."
  [goal ctx]
  (let [sys (str "You plan a short agent flow for a research notebook. Return JSON "
                 "{\"steps\":[{\"verb\":…,\"args\":{…},\"note\":\"why this step\"}]} — at most 6 steps.\n"
                 "Verbs:\n"
                 "- research {\"prompt\"}: web+data research, lands findings in the notebook. If a later step computes over it, phrase the prompt to EXTRACT A TABLE (e.g. \"…extract a table of supplier, market_share_pct\")\n"
                 "- compute {\"id\",\"prompt\"}: derive a new table from table `id` (steps are numbered from 0; \"$N\" means the output of step N — only research/compute produce outputs)\n"
                 "- ask {\"prompt\"}: answer a question from the notebook's data, kept as a note\n"
                 "- draft {}: write a brief from everything in the notebook\n"
                 "- gate {\"question\"}: STOP and ask the human before continuing — use before expensive or judgment-heavy steps\n"
                 "Prefer research → gate → compute/ask → draft shapes. Only JSON.")
        out (request [{:role "system" :content sys}
                      {:role "user" :content (str "Goal: " goal "\n\nNotebook context:\n" ctx)}]
                     :json? true)]
    (:steps (json/read-str (:content out) :key-fn keyword))))
