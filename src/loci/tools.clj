(ns loci.tools
  "Agent tools — the connectors layer. The model decides to call these; the
   handlers execute deterministically against the substrate. Tools that mutate
   (import_csv, query_table :save_as) commit reversible events.

   This is where MCP servers would register too: same shape — a JSON spec + a
   handler — so the model reaches every connected source through one mechanism."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as hc]
            [loci.config :as config]
            [loci.lake :as lake]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- table-objs [st] (->> (sub/objects st) vals (filter #(= :table (:kind %)))))

;; ---- markdown-table salvage: models sometimes write the table INTO prose
;; instead of calling save_table. Deterministic extraction from the agent's
;; own text — parsed by rules, never re-asked. ----
(defn- md-cells [line]
  (let [line (str/trim line)
        line (cond-> line (str/starts-with? line "|") (subs 1))
        line (cond-> line (str/ends-with? line "|") (subs 0 (dec (count line))))]
    (mapv #(str/trim (str/replace % "**" "")) (str/split line #"\|"))))

(defn- md-sep? [line] (boolean (re-matches #"[\s|:\-]+" line)))

(defn- md-cell-val
  "\"~30% (estimated)\" → 30; \"1.5\" → 1.5. Letters-first cells stay strings,
   and so does anything with MORE digits after the number (\"$1,234\",
   \"2025-06-01\") — half-parsing those would be silently wrong.

   **This is prose salvage, and it returns a number.** An earlier draft of the
   external-sources plan had it return the extracted *string*, on the theory
   that DuckDB should do all the typing and loci none — the same rule that
   took `->val` out of `loci.tools` and made `read_csv` the only way a CSV a
   user imports becomes rows. That rule does not reach here, and the reason is
   worth writing down: these rows never touch `read_csv`. `md-table->rows`' only
   caller is `server/research!`, which hands them to `save-table!` → `put-rows!` →
   `load-rows!`, and that path writes NDJSON and reads it back with
   `read_json_auto`. **DuckDB types these cells either way.** The only thing
   decided here is whether it is handed a JSON number or a JSON string, and a
   cell that says `~30%` holds a number.

   Measured 2026-08-19, handing it a string instead: the lake column comes back
   `VARCHAR`, and `fnlib/numeric-cols` answers `[]` where it answered
   `[:share]`. `fnlib/catalog` still LISTS Group, Top N, Change and Share, each
   greyed with `{:ok false :why needs-a-numeric-column}` — `needs-numeric`
   is wired into all five. So the table is not broken, it is inert: the palette
   says why, and the user is left reading `~30%` as text nobody can total.

   So: extracting a number out of a model's sentence is loci's job, because
   nothing else will ever do it; deciding what that number IS remains DuckDB's."
  [s]
  (if-let [m (re-matches #"[^A-Za-z0-9]*?(-?\d+(?:\.\d+)?)[^0-9]*" s)]
    (let [n (second m)]
      (if (str/includes? n ".") (Double/parseDouble n) (Long/parseLong n)))
    s))

(defn md-table->rows
  "The first markdown table in text → vector of maps with keyword columns,
   or nil when there is no table worth the name (≥2 cols, ≥1 data row)."
  [text]
  (let [tbl (->> (str/split-lines (str text))
                 (partition-by #(str/includes? % "|"))
                 (filter #(str/includes? (first %) "|"))
                 (filter #(>= (count %) 2))
                 first)]
    (when tbl
      (let [[hdr & body] (remove md-sep? tbl)
            ks   (mapv sub/col-kw (md-cells hdr))
            rows (keep (fn [l] (let [cs (md-cells l)]
                                 (when (= (count cs) (count ks))
                                   (zipmap ks (map md-cell-val cs)))))
                       body)]
        (when (and (>= (count ks) 2) (seq rows)) (vec rows))))))

;; ---- tool specs (OpenAI-style) ----
(def specs
  [{:type "function"
    :function {:name "list_tables"
               :description "List the data tables available, with their columns and row counts."
               :parameters {:type "object" :properties {}}}}
   {:type "function"
    :function {:name "query_table"
               :description (str "Filter / group / aggregate a table and return the resulting "
                                 "rows. Runs in DuckDB over every row — use this for any exact "
                                 "figure, never guess. The reply carries `total`, the answer's "
                                 "true size; when `capped` is true you were given a page of it, "
                                 "so aggregate further, add a limit, or set save_as to keep all "
                                 "of it as a new table.")
               :parameters {:type "object"
                            :properties {:table_id {:type "string"}
                                         :group_by {:type "string" :description "column to group by (optional)"}
                                         :measure  {:type "string" :description "numeric column to aggregate (optional)"}
                                         :agg      {:type "string" :enum ["sum" "avg" "count" "min" "max"]}
                                         :filter   {:type "object"
                                                    :properties {:col {:type "string"}
                                                                 :op {:type "string" :enum ["=" "≠" ">" "<" "contains"]}
                                                                 :val {}}}
                                         :sort     {:type "string" :enum ["asc" "desc"]}
                                         :limit    {:type "integer"}
                                         :save_as  {:type "string" :description "if set, save the result as a new table with this title"}}
                            :required ["table_id"]}}}
   {:type "function"
    :function {:name "web_search"
               :description "Search the web for current information not in the workspace."
               :parameters {:type "object" :properties {:query {:type "string"}} :required ["query"]}}}
   {:type "function"
    :function {:name "save_table"
               :description "Save structured rows you gathered or EXTRACTED (pulled out of a document, technical text, or web results) as a new moldable data table in the space. Use this whenever your result is tabular — prefer it over writing a markdown table in prose, so the user can pivot/chart/filter it. Each row is an object of column->value; all rows must share the same keys; use real numbers (not strings) for numeric values."
               :parameters {:type "object"
                            :properties {:title {:type "string"}
                                         :rows  {:type "array" :items {:type "object"}
                                                 :description "array of row objects sharing the same keys"}}
                            :required ["title" "rows"]}}}])

;; ---- handlers ----
;; A LIST of tables is metadata: ids, titles, columns, counts. It used to
;; hydrate every table to read the columns off the first row and count the
;; rest — 271 ms over the migrated store, for an answer every handle already
;; carries. `table-shape` is the reader that fetches nothing.
;;
;; Reading the handle is also why this reader alone has to ASK about
;; reclamation. Every other table reader goes through `hydrate`, which discovers
;; a reclamation by failing to read the rows and then confirming with the
;; catalog; this one never reads a row, so nothing fails and the handle goes on
;; reporting the count it was written with long after the Parquet is gone.
;; It is the read `lake/dropped-at`'s docstring anticipated.
;;
;; `lake/dropped-tables` and NOT `tbl/reclaimed-at` per table, because the cost
;; is the round trip and not the scan: one call per table measured 100 ms over
;; the 29-table store, against 0.10 ms before this reader asked at all and 3.5 ms
;; asking once. Per-table would have handed back every millisecond the move to
;; `table-shape` bought. The lake is forced only when some table is handle-backed
;; — `table-shape`'s rule, for its reason.
(defn- do-list [st _]
  (let [os      (table-objs st)
        dropped (when (some #(tbl/handle? (:value %)) os)
                  (lake/dropped-tables (force lake/lake)))]
    (mapv (fn [o] (let [{:keys [cols rows]} (tbl/table-shape lake/lake o)
                        at (get dropped (:table (:value o)))]
                    (cond-> {:id (:id o) :title (:title o)
                             :columns (mapv name cols)
                             :rows (or rows 0)}
                      at (assoc :reclaimed at))))
          os)))

(defn- store+handle
  "The store and handle for a table object, whatever backs it. A legacy inline
   table — rows in the event — is landed in an `InlineTable` so one code path
   serves both, and the query language is then identical either way."
  [o]
  (let [v (:value o)]
    (cond
      (tbl/handle? v) [(force lake/lake) v]
      (and (sequential? v) (seq v) (every? map? v))
      (let [ts (tbl/inline-table)] [ts (tbl/put-rows! ts (:id o) v)])
      :else nil)))

(defn- why-unreadable
  "Why this table's rows must not be acted on, or nil.

   **`reclaimed-at` first, because it answers without fetching rows.** Asking
   `unusable` means hydrating, and `hydrate` reads a `mold-cap` sample — about
   0.28 s of JDBC for a wide table, on every `query_table` call, to answer a
   question the catalog already knows. Measured 2026-08-17: one catalog round
   trip costs 3.5 ms.

   `hydrate` is still the fallback for a `:source` handle, because a passthrough
   table's file can vanish or change its header with no event committed, and
   `reclaimed-at` answers nil for such a handle by design — see its docstring."
  [ts h o]
  (if (= :source (:store h))
    (tbl/unusable (tbl/hydrate lake/lake o))
    (when-let [at (tbl/reclaimed-at ts h)]
      (str "its rows were reclaimed at " (tbl/reclaimed-str at)))))

(defn- do-query [st {:keys [table_id save_as] :as args}]
  (if-let [o (sub/object st table_id)]
    (if-let [[ts h] (store+handle o)]
      (if-let [why (why-unreadable ts h o)]
        {:error (str "table " table_id " cannot be read: " why)}
        (let [spec (select-keys args [:group_by :measure :agg :filter :sort :limit])]
          (if save_as
            (let [nid  (str "tbl:derived-" (count (table-objs st)) "-" (inc (rand-int 9999)))
                  h2   (tbl/create-from-spec ts h spec nid save_as)
                  tobj {:id nid :kind :table :title save_as :value h2
                        :from table_id :via "query_table"}]
              (sub/commit! st {:op :put :id nid :value tobj})
              {:saved_as nid :rows (:rows h2)})
            (let [r (tbl/query ts h spec)]
              {:rows (:rows r) :total (:total r) :capped (:capped r)}))))
      {:error (str "table " table_id " has no rows")})
    {:error (str "no table " table_id)}))

;; Through `loci.config`, like every other configured value: `TAVILY_API_KEY` is
;; one of the keys `loci.env.example` ships, so leaving this on System/getenv
;; would make the one file work for `docker compose up` and, for this key alone,
;; silently not for `clojure -M:serve` — the exact shape of bug that file was
;; unified to remove.
(defn- search-key []
  (or (config/env "SEARCH_API_KEY") (config/env "TAVILY_API_KEY")
      (let [f (java.io.File. ".tavily-key")] (when (.exists f) (str/trim (slurp f))))))
(defn- do-search [_ {:keys [query]}]
  (if-let [k (search-key)]
    (try
      (let [resp @(hc/post "https://api.tavily.com/search"
                    {:timeout 20000 :headers {"Content-Type" "application/json"}
                     :body (json/write-str {:api_key k :query query :max_results 5})})
            body (json/read-str (:body resp) :key-fn keyword)]
        {:results (mapv #(select-keys % [:title :url :content]) (:results body))})
      (catch Exception e {:error (.getMessage e)}))
    {:error "web search not configured — set SEARCH_API_KEY (e.g. a Tavily key) to enable it"}))

(defn save-table!
  "Land agent-gathered/extracted rows as a reversible, moldable :table object.
   With a space, also assoc it into that space's members (one :tx step)."
  [st title rows & [space]]
  ;; The model names its own columns — "Key Proponent(s)" must not become a
  ;; keyword the log cannot read back. `put-rows!` normalizes (and refuses a
  ;; collision), so this no longer does it by hand; what it must do instead is
  ;; report the columns the store actually holds. `(keys (first rows))` would
  ;; name "Total Sales" while the stored column is :total_sales, and the model's
  ;; next `query_table` would then ask for a column that does not exist.
  ;;
  ;; And normalizing can now REFUSE: a model that emits both "Total Sales" and
  ;; "total_sales" names two columns that become one, and `normalize-rows`
  ;; throws rather than silently keep one value. Nothing above catches it —
  ;; `agent/chat-tools` does not wrap `tool-fn` (agent.clj:139) — so the throw
  ;; runs to `draft-brief!`'s catch and blanks the whole brief with
  ;; `_(agent unavailable: two column names would become one column…)_`. A tool
  ;; handler answers with `{:error …}`; this one already does for no rows.
  (let [rows (vec rows)]
    (if (empty? rows)
      {:error "no rows to save"}
      (let [nid (str "tbl:extract-" (count (table-objs st)) "-" (inc (rand-int 9999)))
            obj (try (tbl/table-object @lake/lake nid (or title "Extracted table") rows)
                     (catch Exception e e))]
        (if (instance? Exception obj)
          {:error (.getMessage ^Exception obj)}                 ; and commit nothing
          (let [h (:value obj)]
            (sub/commit! st (if (and space (sub/object st space))
                              {:op :tx :events [{:op :put :id nid :value obj}
                                                (nb/append-cell-event st space {:ref nid})]}
                              {:op :put :id nid :value obj}))
            {:saved_as nid :rows (:rows h) :columns (mapv name (:cols h))}))))))

(defn dispatch [st name args]
  (case name
    "list_tables" (do-list st args)
    "query_table" (do-query st args)
    "web_search"  (do-search st args)
    "save_table"  (save-table! st (:title args) (:rows args))
    {:error (str "unknown tool " name)}))
