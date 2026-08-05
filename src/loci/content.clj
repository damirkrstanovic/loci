(ns loci.content
  "Layers 1–2 (canonical content) + layer 4 (the viewers that mold it).

   Now populated with varied, large, realistic corporate data — generated
   deterministically (seeded) so it's reproducible and the substrate stays
   stable across runs. Spans finance, sales, support, people, marketing, plus
   composite 'report' objects that intersperse prose, tables and charts.

   The point of the variety: molding becomes genuinely contextual. A 400-row
   ticket table is noise as rows; it's signal as a Summary or a Bar. A report
   is prose with embedded data. Each object's 'view this as…' menu is whatever
   its *shape* earns."
  (:require [clojure.core.protocols :as p]
            [clojure.string :as str]
            [loci.dlv :as dlv]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

;; ============================================================================
;; deterministic generators
;; ============================================================================

(def ^:private rng (java.util.Random. 42))
(defn- pick [coll] (nth coll (.nextInt rng (count coll))))
(defn- between [a b] (+ a (.nextInt rng (inc (- b a)))))
(defn- round2 [x] (Double/parseDouble (String/format java.util.Locale/US "%.2f" (to-array [(double x)]))))

(def ^:private months-2026 (mapv #(format "2026-%02d" %) (range 1 13)))
(def ^:private months-24
  (vec (for [y [2025 2026] m (range 1 13)] (format "%d-%02d" y m))))

(def ^:private account-names
  ["Northwind" "Globex" "Initech" "Umbrella" "Hooli" "Stark Inc" "Wayne Co"
   "Acme" "Soylent" "Vandelay" "Wonka" "Tyrell" "Cyberdyne" "Massive Dynamic"
   "Pied Piper" "Gekko Capital"])
(def ^:private regions ["NA" "EMEA" "APAC" "LATAM"])
(def ^:private stages  ["Prospect" "Qualified" "Proposal" "Negotiation" "Closed Won" "Closed Lost"])
(def ^:private owners  ["Lee" "Mara" "Otto" "Priya" "Sam" "Tess"])
(def ^:private depts   ["Engineering" "Sales" "Support" "Marketing" "Finance" "People"])
(def ^:private levels  ["L2" "L3" "L4" "L5" "M1"])
(def ^:private cities  ["Belgrade" "Berlin" "Lisbon" "Austin" "Singapore" "São Paulo"])
(def ^:private channels ["Search" "Social" "Email" "Events" "Referral"])
(def ^:private themes  ["Price / cost" "Missing feature" "Onboarding" "Bug" "Praise"])
(def ^:private prios   ["P1" "P2" "P3" "P4"])
(def ^:private statuses ["open" "pending" "resolved" "closed"])

;; ---- small hand-authored sets (kept stable; the demo references these) ----
(def ^:private revenue-rows
  [(array-map :month "2026-01" :tenure-band "<12mo" :churn 3.1 :accounts 412)
   (array-map :month "2026-02" :tenure-band "<12mo" :churn 3.4 :accounts 430)
   (array-map :month "2026-03" :tenure-band "<12mo" :churn 4.2 :accounts 455)
   (array-map :month "2026-01" :tenure-band "12mo+" :churn 1.2 :accounts 980)
   (array-map :month "2026-02" :tenure-band "12mo+" :churn 1.3 :accounts 991)
   (array-map :month "2026-03" :tenure-band "12mo+" :churn 1.5 :accounts 999)])

(def ^:private themes-rows
  [(array-map :theme "Price / cost" :tickets 147 :sentiment "negative")
   (array-map :theme "Missing feature" :tickets 63 :sentiment "neutral")
   (array-map :theme "Onboarding" :tickets 41 :sentiment "mixed")
   (array-map :theme "Praise" :tickets 28 :sentiment "positive")])

;; ---- generated large tables ----
(def ^:private opps-rows
  (vec (for [i (range 180)]
         (array-map :id (format "OPP-%04d" (inc i))
                    :stage (pick stages) :region (pick regions) :owner (pick owners)
                    :account (pick account-names) :amount (* 1000 (between 5 250))
                    :close-month (pick months-2026)))))

(def ^:private tickets-rows
  (vec (for [i (range 420)]
         (array-map :id (format "TK-%05d" (inc i))
                    :priority (pick prios) :theme (pick themes) :status (pick statuses)
                    :csat (between 1 5) :account (pick account-names)
                    :created (pick months-2026)))))

(def ^:private employees-rows
  (vec (for [i (range 130)]
         (array-map :id (format "E-%04d" (inc i))
                    :dept (pick depts) :level (pick levels) :location (pick cities)
                    :comp (* 1000 (between 60 220)) :start (pick months-24)))))

(def ^:private campaigns-rows
  (vec (for [i (range 24)]
         (let [spend (* 1000 (between 5 80)) leads (between 40 1200)]
           (array-map :name (format "CMP-%02d" (inc i)) :channel (pick channels)
                      :spend spend :leads leads
                      :cpl (round2 (/ spend (max 1 leads)))
                      :conv (round2 (/ (between 2 18) 100.0)))))))

(def ^:private pnl-rows
  (vec (for [m months-2026]
         (let [rev (* 1000 (between 800 1400)) cogs (* 1000 (between 200 400))
               opex (* 1000 (between 300 600))]
           (array-map :month m :revenue rev :cogs cogs :opex opex
                      :net (- rev cogs opex))))))

(def ^:private mrr-rows
  (loop [ms months-24 v 740000 acc []]
    (if (empty? ms) acc
        (let [v' (int (* v (+ 1 (/ (between 1 6) 100.0))))]
          (recur (rest ms) v' (conj acc (array-map :month (first ms) :mrr v')))))))

(def ^:private headcount-rows
  (loop [ms months-24 h 84 acc []]
    (if (empty? ms) acc
        (let [h' (+ h (between 0 5))]
          (recur (rest ms) h' (conj acc (array-map :month (first ms) :headcount h')))))))

(def ^:private docs
  {"doc:churn"   ["Retention notes — Q3"
                  (str "# Retention — Q3\nChurn rose to 4.2% in Q3, concentrated in accounts under twelve months old.\n"
                       "## Pricing\nSupport volume for these accounts tripled after the March pricing change.\n"
                       "## Recovery\nOutreach within 7 days of a downgrade historically recovered ~22%.")]
   "doc:pricing" ["Pricing change — March"
                  (str "# Pricing change — March\nOn March 4 the mid-tier monthly plan rose 18%.\n"
                       "## Rationale\nMargin pressure on infra costs.\n"
                       "## Observed effect\nA support spike from <12mo accounts within two weeks.")]
   "doc:raskin"  ["Raskin — Humane Interface"
                  (str "# Raskin — The Humane Interface\n## LEAP\nFinding is navigating.\n"
                       "## Modelessness\nMeaning must never depend on hidden state.\n"
                       "## Locus of attention\nProtect the one thing the person is attending to.")]
   "doc:mercury" ["Mercury OS — flows"
                  (str "# Mercury OS — Jason Yuan\n## Flows\nIntention-centric bundles replace apps.\n"
                       "## Intention as context\nVaccinated against unintentional consumption of information.")]
   ;; A technical narrative with macro figures buried in prose — the extraction
   ;; demo: ask the agent to pull a year/GDP/inflation/unemployment table out of it.
   ;; Figures are illustrative, drawn from open-data orders of magnitude (World Bank / SORS / IMF).
   "doc:serbia-econ"
   ["Serbia — macroeconomic brief"
    (str "# Republic of Serbia — Macroeconomic Brief (illustrative open data)\n\n"
         "Serbia's recovery from the mid-2010s fiscal consolidation gave way to a period of solid growth before "
         "the pandemic and the 2022 inflation shock. In 2015 nominal GDP stood at roughly US$37.2bn with real "
         "growth of just 1.8%; by 2016 output expanded 3.3% (≈US$38.3bn) and in 2017 a milder 2.1% (≈US$42.0bn). "
         "2018 was the strongest pre-pandemic year, growing 4.5% to about US$50.6bn, followed by 4.3% in 2019 "
         "(≈US$51.5bn).\n\n"
         "## Pandemic and rebound\n"
         "Serbia contracted only mildly in 2020, with real GDP down 0.9% (nominal ≈US$53.3bn), one of Europe's "
         "shallower downturns. 2021 saw a sharp rebound of 7.7% to roughly US$63.1bn, before growth slowed to "
         "2.5% in 2022 (≈US$63.6bn) and held near 2.5% in 2023 (≈US$75.2bn as the dinar strengthened).\n\n"
         "## Prices and labour\n"
         "Consumer-price inflation was subdued for most of the period — about 1.4% in 2015, 1.1% in 2016, 3.1% in "
         "2017, 2.0% in 2018, 1.9% in 2019 and 1.6% in 2020 — then accelerated to 4.1% in 2021 and spiked to "
         "around 12.0% in 2022, easing only slightly to ~12.4% on an annual-average basis in 2023. "
         "The labour market improved steadily: registered unemployment fell from 17.7% in 2015 to 15.3% (2016), "
         "13.5% (2017), 12.7% (2018), 10.4% (2019) and 9.0% in 2020, ticking up to ~11.0% in 2021 before settling "
         "near 9.4% in 2022 and 9.5% in 2023.")]})

;; ============================================================================
;; viewers — render returns plain data; :kind tells the target how to draw it
;; ============================================================================

(defn- rows?   [x] (and (sequential? x) (seq x) (every? map? x)))
(defn- report? [x] (and (rows? x) (every? :block x)))
(defn- table?  [x] (and (rows? x) (not (report? x))))
(defn- doc?    [x] (string? x))

(defn- numeric-cols [rows] (filter #(number? (get (first rows) %)) (keys (first rows))))
(defn- string-cols  [rows] (filter #(string? (get (first rows) %)) (keys (first rows))))
(defn- cat-col [rows]
  (->> (string-cols rows)
       (filter (fn [c] (let [d (count (distinct (map #(get % c) rows)))] (and (> d 1) (<= d 12)))))
       first))

(defn- numericish?  [x] (and (table? x) (seq (numeric-cols x))))
(defn- categorical? [x] (and (table? x) (cat-col x) (seq (numeric-cols x))))
(defn- timeseries?  [x] (and (table? x) (contains? (first x) :month)
                             (seq (remove #{:month} (numeric-cols x)))))

(defn- summary [rows]
  (vec (for [c (numeric-cols rows)]
         (let [xs (map #(get % c) rows)]
           (array-map :column (name c) :n (count xs)
                      :min (apply min xs) :max (apply max xs)
                      :avg (round2 (/ (reduce + xs) (double (count xs))))
                      :sum (round2 (reduce + xs)))))))

(defn- bar [rows]
  (let [cat (cat-col rows) val (first (numeric-cols rows))]
    {:chart "bar" :x (name cat) :y (name val)
     :rows (->> rows (group-by #(get % cat))
                (map (fn [[k rs]] (array-map (name cat) (str k)
                                             (name val) (round2 (reduce + (map #(get % val) rs))))))
                (sort-by #(get % (name val)) >) vec)}))

(defn- line [rows]
  (let [y (first (remove #{:month} (numeric-cols rows)))
        series (some #{:tenure-band :dept :channel :region :stage} (keys (first rows)))]
    {:chart "line" :x "month" :y (name y) :series (when series (name series)) :rows rows}))

(defn- cohort [rows]
  (->> rows (group-by :tenure-band)
       (map (fn [[band rs]]
              (array-map :tenure-band band :months (count rs)
                         :avg-churn (round2 (/ (reduce + (map :churn rs)) (double (count rs))))
                         :latest (:churn (last (sort-by :month rs))))))
       (sort-by :tenure-band) vec))

(defn- outline [text]
  (->> (str/split-lines text) (filter #(str/starts-with? % "#")) (mapv str/trim)))

(defn- pivot [rows]
  (let [cat (cat-col rows) val (first (numeric-cols rows))]
    (->> rows (group-by #(get % cat))
         (map (fn [[k rs]]
                (let [vs (map #(get % val) rs)]
                  (array-map (name cat) (str k) :count (count rs)
                             :sum (round2 (reduce + vs))
                             :avg (round2 (/ (reduce + vs) (double (count rs))))))))
         (sort-by :sum >) vec)))

(defn- histogram [rows]
  (let [col (first (numeric-cols rows))
        xs (map #(get % col) rows)
        lo (apply min xs) hi (apply max xs)
        bins 8 w (max 1 (/ (- hi lo) (double bins)))
        bucket #(min (dec bins) (int (/ (- % lo) w)))
        counts (frequencies (map bucket xs))]
    {:chart "bar" :x "bucket" :y "count"
     :rows (vec (for [b (range bins)]
                  (array-map "bucket" (str (Math/round (double (+ lo (* b w)))) "–"
                                           (Math/round (double (+ lo (* (inc b) w)))))
                             "count" (get counts b 0))))}))

(defn- kpi [rows]
  (let [y (first (remove #{:month} (numeric-cols rows)))
        srt (sort-by :month rows)
        latest (get (last srt) y)
        prev (get (last (butlast srt)) y latest)
        delta (- latest prev)]
    {:metric (name y) :latest latest :prev prev :delta delta
     :pct (round2 (* 100.0 (/ delta (double (max 1 prev)))))
     :min (apply min (map #(get % y) rows)) :max (apply max (map #(get % y) rows))
     :periods (count rows)}))

;; report helpers
(defn- headings [blocks] (->> blocks (filter #(= :heading (:block %))) (mapv :text)))
(defn- data-blocks [blocks] (filterv #(#{:table :chart} (:block %)) blocks))

(defn register-default-viewers! []
  ;; order = precedence; Rows is the confident default for any table
  (mold/register! {:id :table/rows    :label "Rows"            :kind :table  :pred table? :render identity})
  (mold/register! {:id :table/summary :label "Summary"         :kind :table  :pred numericish?  :render summary})
  (mold/register! {:id :table/cards   :label "Cards"           :kind :cards  :pred table?       :render identity})
  (mold/register! {:id :table/pivot   :label "Pivot"           :kind :table  :pred categorical? :render pivot})
  (mold/register! {:id :table/bar     :label "Bar"             :kind :chart  :pred categorical? :render bar})
  (mold/register! {:id :table/hist    :label "Histogram"       :kind :chart  :pred numericish?  :render histogram})
  (mold/register! {:id :table/line    :label "Over time"       :kind :chart  :pred timeseries?  :render line})
  (mold/register! {:id :table/kpi     :label "Headline"        :kind :kpi    :pred timeseries?  :render kpi})
  (mold/register! {:id :churn/cohort  :label "Churn cohort"    :kind :table
                   :pred (fn [x] (and (table? x) (contains? (first x) :tenure-band) (contains? (first x) :churn)))
                   :render cohort})
  (mold/register! {:id :doc/raw       :label "Full text"       :kind :text   :pred doc? :render identity})
  (mold/register! {:id :doc/outline   :label "Outline"         :kind :list   :pred doc? :render outline})
  (mold/register! {:id :report/full   :label "Report"          :kind :report :pred report? :render identity})
  (mold/register! {:id :report/outline :label "Outline"        :kind :list   :pred report? :render headings})
  (mold/register! {:id :report/data   :label "Just the data"   :kind :report :pred report? :render data-blocks}))

(register-default-viewers!)

;; ============================================================================
;; composite reports — prose interspersed with tables and charts
;; ============================================================================

(def ^:private qbr-report
  [{:block :heading :text "Q3 Business Review"}
   {:block :text :text "Net retention held, but new-logo churn climbed and the pipeline thinned in EMEA. Three things need a decision this quarter."}
   {:block :heading :text "Finance"}
   {:block :text :text "Revenue grew month over month; MRR compounding remains the story."}
   {:block :table :title "P&L (monthly)" :rows pnl-rows}
   {:block :chart :spec (line mrr-rows)}
   {:block :heading :text "Pipeline"}
   {:block :text :text "Bookings concentrate late-stage; a lot of value sits unqualified."}
   {:block :chart :spec (bar opps-rows)}
   {:block :heading :text "Support"}
   {:block :chart :spec (bar tickets-rows)}
   {:block :text :text "Price/cost is the dominant theme and tracks the March change."}])

(def ^:private retention-report
  [{:block :heading :text "Retention memo — Q3"}
   {:block :text :text "Churn rose to 4.2% in Q3, concentrated in accounts under twelve months old."}
   {:block :table :title "Churn by month & tenure" :rows revenue-rows}
   {:block :chart :spec (line revenue-rows)}
   {:block :heading :text "Recovery"}
   {:block :text :text "Outreach within 7 days of a downgrade historically recovered ~22%."}])

;; ============================================================================
;; creative population — real-world datasets, a large generated table, and
;; reports that intersperse prose, tables and charts. (Drawn after the existing
;; generators, so the shared seeded rng leaves the corporate data untouched.)
;; ============================================================================

(defn- agg-by [rows kcol vcol]
  (->> rows (group-by #(get % kcol))
       (map (fn [[k rs]] (array-map (name kcol) (str k)
                                    (name vcol) (round2 (reduce + (map #(get % vcol) rs))))))
       (sort-by #(get % (name kcol))) vec))

;; ---- REAL data: the planets of the Solar System ----
(def ^:private planets-rows
  [(array-map :planet "Mercury" :type "Terrestrial" :diameter_km 4879   :mass_earths 0.055  :distance_au 0.39  :moons 0   :orbit_days 88     :gravity_g 0.38)
   (array-map :planet "Venus"   :type "Terrestrial" :diameter_km 12104  :mass_earths 0.815  :distance_au 0.72  :moons 0   :orbit_days 225    :gravity_g 0.91)
   (array-map :planet "Earth"   :type "Terrestrial" :diameter_km 12742  :mass_earths 1.0    :distance_au 1.0   :moons 1   :orbit_days 365    :gravity_g 1.0)
   (array-map :planet "Mars"    :type "Terrestrial" :diameter_km 6779   :mass_earths 0.107  :distance_au 1.52  :moons 2   :orbit_days 687    :gravity_g 0.38)
   (array-map :planet "Jupiter" :type "Gas Giant"   :diameter_km 139820 :mass_earths 317.8  :distance_au 5.20  :moons 95  :orbit_days 4333   :gravity_g 2.53)
   (array-map :planet "Saturn"  :type "Gas Giant"   :diameter_km 116460 :mass_earths 95.2   :distance_au 9.58  :moons 146 :orbit_days 10759  :gravity_g 1.07)
   (array-map :planet "Uranus"  :type "Ice Giant"   :diameter_km 50724  :mass_earths 14.5   :distance_au 19.2  :moons 28  :orbit_days 30687  :gravity_g 0.89)
   (array-map :planet "Neptune" :type "Ice Giant"   :diameter_km 49244  :mass_earths 17.1   :distance_au 30.05 :moons 16  :orbit_days 60190  :gravity_g 1.14)])

;; ---- REAL-ish data: largest economies (approx 2023; illustrative open data) ----
(def ^:private countries-rows
  [(array-map :country "United States"  :continent "N. America" :population_m 335  :area_k_km2 9834  :gdp_usd_bn 27360 :gdp_pc_usd 81700)
   (array-map :country "China"          :continent "Asia"       :population_m 1411 :area_k_km2 9597  :gdp_usd_bn 17790 :gdp_pc_usd 12600)
   (array-map :country "Japan"          :continent "Asia"       :population_m 125  :area_k_km2 378   :gdp_usd_bn 4210  :gdp_pc_usd 33700)
   (array-map :country "Germany"        :continent "Europe"     :population_m 84   :area_k_km2 358   :gdp_usd_bn 4460  :gdp_pc_usd 53100)
   (array-map :country "India"          :continent "Asia"       :population_m 1429 :area_k_km2 3287  :gdp_usd_bn 3550  :gdp_pc_usd 2480)
   (array-map :country "United Kingdom" :continent "Europe"     :population_m 67   :area_k_km2 244   :gdp_usd_bn 3340  :gdp_pc_usd 49800)
   (array-map :country "France"         :continent "Europe"     :population_m 68   :area_k_km2 552   :gdp_usd_bn 3030  :gdp_pc_usd 44500)
   (array-map :country "Italy"          :continent "Europe"     :population_m 59   :area_k_km2 301   :gdp_usd_bn 2250  :gdp_pc_usd 38100)
   (array-map :country "Brazil"         :continent "S. America" :population_m 216  :area_k_km2 8516  :gdp_usd_bn 2170  :gdp_pc_usd 10000)
   (array-map :country "Canada"         :continent "N. America" :population_m 40   :area_k_km2 9985  :gdp_usd_bn 2140  :gdp_pc_usd 53500)
   (array-map :country "Russia"         :continent "Europe"     :population_m 144  :area_k_km2 17098 :gdp_usd_bn 2020  :gdp_pc_usd 14000)
   (array-map :country "Mexico"         :continent "N. America" :population_m 128  :area_k_km2 1964  :gdp_usd_bn 1790  :gdp_pc_usd 14000)
   (array-map :country "South Korea"    :continent "Asia"       :population_m 52   :area_k_km2 100   :gdp_usd_bn 1710  :gdp_pc_usd 32900)
   (array-map :country "Australia"      :continent "Oceania"    :population_m 26   :area_k_km2 7692  :gdp_usd_bn 1690  :gdp_pc_usd 65000)
   (array-map :country "Spain"          :continent "Europe"     :population_m 48   :area_k_km2 506   :gdp_usd_bn 1580  :gdp_pc_usd 32900)
   (array-map :country "Indonesia"      :continent "Asia"       :population_m 277  :area_k_km2 1905  :gdp_usd_bn 1370  :gdp_pc_usd 4950)
   (array-map :country "Turkey"         :continent "Asia"       :population_m 85   :area_k_km2 784   :gdp_usd_bn 1110  :gdp_pc_usd 13100)
   (array-map :country "Netherlands"    :continent "Europe"     :population_m 18   :area_k_km2 42    :gdp_usd_bn 1120  :gdp_pc_usd 62200)
   (array-map :country "Saudi Arabia"   :continent "Asia"       :population_m 37   :area_k_km2 2150  :gdp_usd_bn 1070  :gdp_pc_usd 28900)
   (array-map :country "Switzerland"    :continent "Europe"     :population_m 9    :area_k_km2 41    :gdp_usd_bn 885   :gdp_pc_usd 98300)
   (array-map :country "Poland"         :continent "Europe"     :population_m 38   :area_k_km2 313   :gdp_usd_bn 810   :gdp_pc_usd 21300)
   (array-map :country "Argentina"      :continent "S. America" :population_m 46   :area_k_km2 2780  :gdp_usd_bn 640   :gdp_pc_usd 13900)
   (array-map :country "Sweden"         :continent "Europe"     :population_m 10   :area_k_km2 450   :gdp_usd_bn 590   :gdp_pc_usd 59000)
   (array-map :country "Egypt"          :continent "Africa"     :population_m 112  :area_k_km2 1001  :gdp_usd_bn 395   :gdp_pc_usd 3530)
   (array-map :country "Nigeria"        :continent "Africa"     :population_m 224  :area_k_km2 924   :gdp_usd_bn 390   :gdp_pc_usd 1740)
   (array-map :country "South Africa"   :continent "Africa"     :population_m 60   :area_k_km2 1221  :gdp_usd_bn 380   :gdp_pc_usd 6330)])

;; ---- LARGE generated table: 1,000 e-commerce orders ----
(def ^:private order-cats ["Electronics" "Home" "Apparel" "Grocery" "Toys" "Books" "Beauty" "Sports"])
(def ^:private order-countries ["US" "DE" "UK" "FR" "RS" "BR" "JP" "IN" "AU" "CA"])
(def ^:private orders-rows
  (vec (for [i (range 1000)]
         (let [units (between 1 20) price (between 5 400)]
           (array-map :order (format "ORD-%05d" (inc i))
                      :month (pick months-2026)
                      :category (pick order-cats)
                      :country (pick order-countries)
                      :channel (pick channels)
                      :units units
                      :revenue (* units price)
                      :margin_pct (between 8 45))))))

;; ---- LARGE document: a long-form essay ----
(def ^:private cosmos-doc
  (str "# The Solar System — a field guide\n\n"
       "Our planetary system formed about 4.6 billion years ago from a collapsing cloud of gas and dust. "
       "At its centre sits the Sun, holding 99.8% of the system's mass; everything else — eight planets, "
       "their moons, dwarf planets, asteroids and comets — orbits in the flat disc left behind.\n\n"
       "## The inner, terrestrial worlds\n"
       "Mercury, Venus, Earth and Mars are small, dense and rocky. Mercury is airless and cratered; Venus "
       "is wrapped in a runaway greenhouse that bakes its surface to ~465°C; Earth alone sits in the narrow "
       "band where liquid water is stable; Mars is a cold desert with the tallest volcano in the system, "
       "Olympus Mons. None of the inner planets has rings, and between them all they hold just three moons.\n\n"
       "## The asteroid belt\n"
       "Beyond Mars lies a belt of rocky debris — leftover planetesimals that Jupiter's gravity never let "
       "coalesce into a planet. The largest, Ceres, is round enough to count as a dwarf planet.\n\n"
       "## The gas and ice giants\n"
       "Jupiter and Saturn are vast balls of hydrogen and helium; Jupiter alone outweighs all the other "
       "planets combined two and a half times over. Saturn's rings are the system's showpiece — mostly water "
       "ice, spanning hundreds of thousands of kilometres yet only metres thick. Uranus and Neptune, the ice "
       "giants, are smaller and colder, rich in water, ammonia and methane; methane gives both their blue tint. "
       "The giants dominate the moon count, with well over a hundred apiece.\n\n"
       "## The edge\n"
       "Past Neptune lies the Kuiper Belt — Pluto's neighbourhood — and far beyond it the hypothesised Oort "
       "Cloud, the source of long-period comets. Light from the Sun, which reaches Earth in eight minutes, "
       "takes more than five hours to reach Neptune.\n\n"
       "## How to read the data\n"
       "The companion table lists each planet's diameter, mass (in Earths), distance from the Sun (in AU), "
       "moon count, orbital period and surface gravity. Mold it as a bar chart to feel the size gap between "
       "the rocky worlds and the giants; sort by distance to see how the orbital periods stretch out."))

;; ---- reports: prose interspersed with tables and charts ----
(def ^:private cosmos-report
  [{:block :heading :text "The Solar System — a tour"}
   {:block :text :text "Eight planets orbit the Sun, splitting cleanly into small rocky worlds near in and giants far out. The numbers below make the divide vivid."}
   {:block :table :title "The eight planets" :rows planets-rows}
   {:block :heading :text "Size"}
   {:block :text :text "Diameter, in kilometres. Jupiter and Saturn dwarf everything else."}
   {:block :chart :spec {:chart "bar" :x "planet" :y "diameter_km" :rows planets-rows}}
   {:block :heading :text "Moons"}
   {:block :text :text "Moon counts climb steeply with size — the giants act as local gravity wells."}
   {:block :chart :spec {:chart "bar" :x "planet" :y "moons" :rows planets-rows}}
   {:block :heading :text "Distance vs. year"}
   {:block :text :text "The farther out a planet sits, the longer its orbit: Neptune's year is 165 of ours."}
   {:block :chart :spec {:chart "bar" :x "planet" :y "orbit_days" :rows planets-rows}}])

(def ^:private world-report
  [{:block :heading :text "The world economy, at a glance"}
   {:block :text :text "Nominal GDP concentrates heavily: the United States and China together account for roughly 45% of the output of the 26 largest economies listed here. (Figures approximate, 2023.)"}
   {:block :table :title "Largest economies" :rows countries-rows}
   {:block :heading :text "Output"}
   {:block :text :text "GDP in US$ billions for the top dozen."}
   {:block :chart :spec {:chart "bar" :x "country" :y "gdp_usd_bn" :rows (vec (take 12 countries-rows))}}
   {:block :heading :text "Output by continent"}
   {:block :text :text "Summed across the listed countries."}
   {:block :chart :spec (let [a (agg-by countries-rows :continent :gdp_usd_bn)]
                          {:chart "bar" :x "continent" :y "gdp_usd_bn" :rows a})}])

(def ^:private commerce-report
  [{:block :heading :text "E-commerce — Q1 2026 review"}
   {:block :text :text "1,000 orders across eight categories, ten markets and five channels. As rows it's noise; molded, it's a business."}
   {:block :heading :text "Revenue by category"}
   {:block :table :title "By category" :rows (agg-by orders-rows :category :revenue)}
   {:block :chart :spec {:chart "bar" :x "category" :y "revenue" :rows (agg-by orders-rows :category :revenue)}}
   {:block :heading :text "Revenue by month"}
   {:block :chart :spec {:chart "line" :x "month" :y "revenue" :rows (agg-by orders-rows :month :revenue)}}
   {:block :heading :text "By market"}
   {:block :chart :spec {:chart "bar" :x "country" :y "revenue" :rows (agg-by orders-rows :country :revenue)}}])

;; ============================================================================
;; objects + spaces
;; ============================================================================

(defn- obj [id kind title value & [extra]]
  (merge {:id id :kind kind :title title :value value} extra))

(def objects
  (concat
   [(obj "tbl:revenue"   :table  "Q3 revenue & churn"   revenue-rows)
    (obj "tbl:themes"    :table  "Tickets by theme"     themes-rows)
    (obj "tbl:opps"      :table  "Sales pipeline"       opps-rows)
    (obj "tbl:tickets"   :table  "Support tickets"      tickets-rows)
    (obj "tbl:employees" :table  "Headcount roster"     employees-rows)
    (obj "tbl:campaigns" :table  "Marketing campaigns"  campaigns-rows)
    (obj "tbl:pnl"       :table  "P&L — monthly"        pnl-rows)
    (obj "metric:mrr"    :metric "MRR (24 months)"      mrr-rows)
    (obj "metric:headcount" :metric "Headcount (24 months)" headcount-rows)
    (obj "report:qbr"       :report "Q3 Business Review"   qbr-report)
    (obj "report:retention" :report "Retention memo — Q3"  retention-report)
    (obj "tbl:planets"   :table  "Planets of the Solar System" planets-rows)
    (obj "tbl:countries" :table  "Countries by GDP"            countries-rows)
    (obj "tbl:orders"    :table  "E-commerce orders (1,000)"   orders-rows)
    (obj "doc:cosmos"    :doc    "The Solar System — field guide" cosmos-doc)
    (obj "report:cosmos"   :report "The Solar System — a tour"  cosmos-report)
    (obj "report:world"    :report "The world economy"          world-report)
    (obj "report:commerce" :report "E-commerce — Q1 review"     commerce-report)]
   (for [[id [title text]] docs] (obj id :doc title text))
   [(obj "space:retention" :space "Retention, Q3"
         {:intent "Find why Q3 churn rose, then draft the memo."
          :members ["tbl:revenue" "tbl:themes" "doc:churn" "doc:pricing" "report:retention"]})
    (obj "space:finance" :space "Revenue & Finance"
         {:intent "Track P&L and MRR; prep the QBR."
          :members ["tbl:pnl" "metric:mrr" "report:qbr"]})
    (obj "space:sales" :space "Sales pipeline"
         {:intent "Where is the quarter's revenue, and what's stuck?"
          :members ["tbl:opps"]})
    (obj "space:support" :space "Support operations"
         {:intent "Keep the queue calm; surface the themes that matter."
          :members ["tbl:tickets" "tbl:themes"]})
    (obj "space:people" :space "People & headcount"
         {:intent "Headcount, comp and growth by team."
          :members ["tbl:employees" "metric:headcount"]})
    (obj "space:marketing" :space "Marketing"
         {:intent "Channel efficiency: spend, leads, CPL."
          :members ["tbl:campaigns"]})
    (obj "space:reading" :space "Reading — Humane interfaces"
         {:intent "Gather Raskin & Mercury sources; synthesize later."
          :members ["doc:raskin" "doc:mercury"]})
    (obj "space:serbia" :space "Serbia — open economic data"
         {:intent "Pull Serbia macro data in, extract it into tables, then chart and compare."
          :members ["doc:serbia-econ"]})
    (obj "space:cosmos" :space "The Solar System"
         {:intent "Tour the planets: read the guide, mold the data, see the size and orbit gaps."
          :members ["doc:cosmos" "tbl:planets" "report:cosmos"]})
    (obj "space:world" :space "World — countries & economies"
         {:intent "Compare the largest economies by GDP, population and density."
          :cells [{:text "Nominal GDP concentrates hard: the United States and China together produce roughly 45% of the output of the 26 largest economies listed here."}
                  {:ref "tbl:countries" :view "table/bar"}
                  {:text "Grouped by continent the picture shifts — Europe's many mid-size economies stack up against the two giants."}
                  {:ref "tbl:countries" :view "table/pivot"}
                  {:text "The narrative version, for reading end to end:"}
                  {:ref "report:world"}]})
    (obj "space:commerce" :space "E-commerce — orders"
         {:intent "A 1,000-order book: mold it by category, market, channel and month."
          :members ["tbl:orders" "report:commerce"]})
    (obj "space:semis" :space "Semiconductors — research hub"
         {:intent "Map the semiconductor supply chain: who makes what, and where are the chokepoints."
          :cells [{:text "Research hub. Use Research to gather the map; Deep-dive spawns focused notebooks that stay connected here."}]})]))

;; ============================================================================
;; seeded store (layer 1)
;; ============================================================================

(defn seed! [store]
  (doseq [o objects] (sub/commit! store {:op :put :id (:id o) :value o}))
  store)

(defonce store
  (delay (let [s (dlv/datalevin-store)]
           ;; a non-empty log on disk wins; otherwise seed (which writes the log)
           (if (seq (sub/history s)) s (seed! s)))))

(defn members [store space-id]
  (->> (nb/cells-of (sub/object store space-id))
       (keep :ref)
       (map #(sub/object store %))))

;; ============================================================================
;; datafy / nav — moldable navigation
;; ============================================================================

(def accounts
  {"A-100" {:id "A-100" :name "Northwind" :tenure-band "<12mo" :plan "mid"}
   "A-205" {:id "A-205" :name "Globex"    :tenure-band "12mo+" :plan "enterprise"}
   "A-300" {:id "A-300" :name "Initech"   :tenure-band "<12mo" :plan "mid"}})

(defrecord Ticket [id account-id text]
  p/Datafiable
  (datafy [t]
    (with-meta (into {} t)
      {`p/nav (fn [_coll k v] (if (= k :account-id) (get accounts v) v))})))

(def sample-ticket
  (->Ticket "tkt:8821" "A-100" "Cancelling — the new price is too high for us."))
