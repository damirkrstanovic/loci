(ns loci.tools
  "Agent tools — the connectors layer. The model decides to call these; the
   handlers execute deterministically against the substrate. Tools that mutate
   (import_csv, query_table :save_as) commit reversible events.

   This is where MCP servers would register too: same shape — a JSON spec + a
   handler — so the model reaches every connected source through one mechanism."
  (:require [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as hc]
            [loci.config :as config]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.viewspec :as vs]))

(defn- table-objs [st] (->> (sub/objects st) vals (filter #(= :table (:kind %)))))

;; ---- CSV ----
(defn- ->val [s]
  (let [s (str/trim (str s))]
    (cond (re-matches #"-?\d+" s)        (Long/parseLong s)
          (re-matches #"-?\d+\.\d+" s)   (Double/parseDouble s)
          :else s)))

(defn parse-csv [text]
  (let [[hdr & rows] (csv/read-csv (java.io.StringReader. (str/trim text)))
        ks (mapv sub/col-kw hdr)]                          ; "Total Revenue ($)" → :total_revenue
    (mapv (fn [r] (into {} (map vector ks (map ->val r)))) rows)))

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
   \"2025-06-01\") — half-parsing those would be silently wrong."
  [s]
  (if-let [m (re-matches #"[^A-Za-z0-9]*?(-?\d+(?:\.\d+)?)[^0-9]*" s)]
    (->val (second m))
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
               :description "Filter / group / aggregate a table and return the resulting rows. Use this for any exact figure — never guess."
               :parameters {:type "object"
                            :properties {:table_id {:type "string"}
                                         :group_by {:type "string" :description "column to group by (optional)"}
                                         :measure  {:type "string" :description "numeric column to aggregate (optional)"}
                                         :agg      {:type "string" :enum ["sum" "avg" "count"]}
                                         :filter   {:type "object"
                                                    :properties {:col {:type "string"}
                                                                 :op {:type "string" :enum ["=" ">" "<"]}
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
(defn- do-list [st _]
  (mapv (fn [o] {:id (:id o) :title (:title o)
                 :columns (mapv name (keys (first (:value o))))
                 :rows (count (:value o))})
        (table-objs st)))

(defn- do-query [st {:keys [table_id save_as] :as args}]
  (if-let [o (sub/object st table_id)]
    (let [spec (assoc (select-keys args [:group_by :measure :agg :filter :sort :limit]) :kind "table")
          rendered (vec (take 200 (:rendered (vs/interpret spec (:value o)))))]
      (if save_as
        (let [nid (str "tbl:derived-" (count (table-objs st)) "-" (inc (rand-int 9999)))
              obj {:id nid :kind :table :title save_as :value rendered}]
          (sub/commit! st {:op :put :id nid :value obj})
          {:saved_as nid :rows rendered})
        {:rows rendered}))
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
  ;; the model names its own columns — "Key Proponent(s)" must not become a
  ;; keyword the log cannot read back (the substrate would refuse the event)
  (let [rows (mapv (fn [r] (if (map? r)
                             (reduce-kv (fn [m k v] (assoc m (sub/col-kw k) v)) {} r)
                             r))
                   (vec rows))]
    (if (empty? rows)
      {:error "no rows to save"}
      (let [nid (str "tbl:extract-" (count (table-objs st)) "-" (inc (rand-int 9999)))
            obj {:id nid :kind :table :title (or title "Extracted table") :value rows}]
        (sub/commit! st (if (and space (sub/object st space))
                          {:op :tx :events [{:op :put :id nid :value obj}
                                            (nb/append-cell-event st space {:ref nid})]}
                          {:op :put :id nid :value obj}))
        {:saved_as nid :rows (count rows) :columns (mapv name (keys (first rows)))}))))

(defn dispatch [st name args]
  (case name
    "list_tables" (do-list st args)
    "query_table" (do-query st args)
    "web_search"  (do-search st args)
    "save_table"  (save-table! st (:title args) (:rows args))
    {:error (str "unknown tool " name)}))
