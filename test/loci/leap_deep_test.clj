(ns loci.leap-deep-test
  "LEAP's deep query — `/api/leap?q=…&deep=1`, the half that searches by meaning.

   The keystroke path is the thing being protected here, so most of this file is
   about what must NOT happen: no embedding request per keystroke, no chunk id
   where an object id belongs, no semantic repeat of something substring search
   already found, and no difference at all when there is no embedder.

   Nothing reaches a real embedder. The stub binds port 0 and is asked which port
   it got, and `loci.embed`'s env/file seams are redefined so a
   LOCI_EMBED_ENDPOINT exported in the shell cannot aim these tests at somebody's
   actual box — the same arrangement as `loci.chunks-test`. Every chunk store is
   a temp path passed in explicitly; nothing here derefs `loci.chunks/chunks`,
   which resolves to the user's real data directory, and the last test in the
   file checks that."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.chunks :as ch]
            [loci.embed :as embed]
            [loci.memory :as mem]
            [loci.server :as srv]
            [loci.substrate :as sub]
            [org.httpkit.server :as server])
  (:import [java.net ServerSocket]))

;; ---------------------------------------------------------------------------
;; temp locations that do not outlive the test
;; ---------------------------------------------------------------------------

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defmacro with-tmpdir
  "A fresh directory, always removed. `chunks` and `memory` are paths inside it."
  [[chunks memory] & body]
  `(let [root#    (str (System/getProperty "java.io.tmpdir") "/loci-leap-test-" (System/nanoTime))
         ~chunks  (str root# "/chunks.edn")
         ~memory  (str root# "/memory.edn")]
     (try ~@body (finally (rm-rf root#)))))

;; ---------------------------------------------------------------------------
;; an embedder stub with MEANING in it
;; ---------------------------------------------------------------------------
;;
;; `loci.chunks-test`'s stub hashes the text, which is enough to test what gets
;; stored but says nothing about what is close to what. These tests are about
;; retrieval, so the stub has to answer questions like "is this document about
;; the thing that was asked?" — and it has to answer them for text that shares no
;; word with the query, because that is the whole claim.
;;
;; So: three orthogonal unit vectors, one per topic, chosen by marker words.
;; "where are chips made" and "Fabrication capacity is concentrated in Hsinchu"
;; land on the same axis without sharing a word; anything else is orthogonal to
;; both and scores exactly 0.

(def ^:private topics
  {:fab   [1.0 0.0 0.0]
   :churn [0.0 1.0 0.0]
   :other [0.0 0.0 1.0]})

(defn- topic-of [text]
  (let [t    (str/lower-case (str text))
        has? (fn [& ws] (boolean (some #(str/includes? t %) ws)))]
    (cond
      (has? "chip" "fabrication" "hsinchu" "wafer") :fab
      (has? "churn" "retention" "downgrade")        :churn
      :else                                         :other)))

(defn- json-body [status data]
  {:status status :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- embed-handler [req]
  (let [input (:input (json/read-str (:body req) :key-fn keyword))]
    (json-body 200 {:model "/models/qwen3-embedding-0.6b-q8_0.gguf"
                    :data  (vec (map-indexed
                                 (fn [i t] {:index i :embedding (topics (topic-of t))})
                                 input))})))

(defn- with-stub
  "Start the embedder stub on an ephemeral loopback port, call `(f port seen)`,
   stop it. `seen` holds every request received, so a test can assert on what
   actually went over the wire — including that nothing did."
  [f]
  (let [seen (atom [])
        srv  (server/run-server
              (fn [req]
                (let [r (assoc (select-keys req [:uri :request-method])
                               :body (some-> (:body req) slurp))]
                  (swap! seen conj r)
                  (embed-handler r)))
              {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (server/server-port srv) seen)
         (finally (server/server-stop! srv {:timeout 100})))))

(defn- with-env
  "Run `f` with loci.embed's resolvers seeing exactly `env` and no files."
  [env f]
  (with-redefs [embed/env #(get env %) embed/from-file (constantly nil)]
    (f)))

(defn- embedding-at [port]
  {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" port "/v1/embeddings")})

(defn- a-closed-port
  "A port with nothing listening: bind one, ask which it was, close it."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- texts-asked-for [seen]
  (vec (mapcat #(:input (json/read-str (:body %) :key-fn keyword)) @seen)))

;; ---------------------------------------------------------------------------
;; the corpus — chosen so that substring search and meaning disagree
;; ---------------------------------------------------------------------------

(defn- doc [id title text] {:id id :kind :doc :title title :value text})

(defn- corpus []
  [(doc "doc:supply" "Supply notes"
        "Fabrication capacity is concentrated in Hsinchu, and the newest lines are booked years ahead.")
   (doc "doc:retention" "Retention"
        "Churn rose to 4.2% in Q3, concentrated in accounts under twelve months old.")
   (doc "doc:winback" "Win-back"
        "Outreach within seven days of a downgrade historically recovered about 22%.")
   (doc "doc:catering" "Office catering"
        "The Thursday sandwiches are ordered from the place on the corner.")])

(defn- store-with [objs]
  (let [st (sub/fresh-store)]
    (doseq [o objs] (sub/commit! st {:op :put :id (:id o) :value o}))
    st))

(defn- of-group [entries g] (filterv #(= g (:group %)) entries))

(defn- meaning [entries] (remove :note (of-group entries "by meaning")))

(defn- notes [entries] (filterv :note entries))

(defmacro with-leap
  "The common arrangement: a substrate holding `objs`, a chunk store at a temp
   path, an empty memory, and a live stub embedder. Binds `st cs m port seen`."
  [[objs] & body]
  `(with-tmpdir [chunks# memfile#]
     (with-stub
       (fn [port# seen#]
         (let [~'st   (store-with ~objs)
               ~'cs   (ch/file-chunks chunks#)
               ~'m    (mem/file-memory memfile#)
               ~'port port#
               ~'seen seen#]
           (with-env (embedding-at port#) (fn [] ~@body)))))))

;; ===========================================================================
;; the keystroke path — the reason the design is split in two
;; ===========================================================================

(deftest the-keystroke-path-makes-zero-embedding-requests
  ;; The single most expensive decision in this feature, asserted against an
  ;; embedder that is configured, reachable, and receives nothing. A 20–50 ms
  ;; round trip between a key and a redraw is what the deep/plain split exists to
  ;; prevent, and it is the kind of regression that shows up as "LEAP feels
  ;; sluggish" months later rather than as a failing test.
  (with-leap [(corpus)]
    (ch/sweep! st cs)                       ; so there IS something to find by meaning
    (reset! seen [])
    (let [plain (srv/leap-payload st m "churn")]
      (is (= [] @seen)
          (str "the keystroke path embedded something: " (pr-str (texts-asked-for seen))))
      (is (empty? (of-group plain "by meaning")))
      ;; and holding the chunk store without asking for depth changes nothing
      (is (= plain (srv/leap-payload st m "churn" {:chunks cs})))
      (is (= plain (srv/leap-payload st m "churn" nil)))
      (is (= [] @seen)))))

(deftest an-empty-query-embeds-nothing-even-with-deep
  ;; The palette opens with no query and lists everything. There is no meaning in
  ;; "" to search by, and asking would be one request per opened palette.
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (reset! seen [])
    (is (= (srv/leap-payload st m "")
           (srv/leap-payload st m "" {:deep? true :chunks cs})))
    (is (= [] @seen))))

;; ===========================================================================
;; ?deep=1 — what substring search cannot find
;; ===========================================================================

(deftest deep-finds-a-document-that-shares-no-word-with-the-query
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (let [q     "where are chips made"
          plain (srv/leap-payload st m q)
          deep  (srv/leap-payload st m q {:deep? true :chunks cs})
          hits  (meaning deep)]
      (is (empty? (remove #(= "viewer" (:group %)) plain))
          "substring search finds nothing at all for this query — that is the point")
      (is (= ["doc:supply"] (mapv :id hits))
          "the document about fabrication, which shares no word with the query")
      (is (= "doc" (:kind (first hits))))
      (is (str/includes? (:label (first hits)) "Supply notes"))
      (is (str/includes? (:label (first hits)) "Fabrication capacity")))))

(deftest a-hit-is-the-object-and-never-the-chunk
  ;; A result opens the document. A chunk id ("doc:supply#0") would open nothing:
  ;; the substrate has no such object, so the shell would land on an empty mold.
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (let [hits (meaning (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs}))]
      (is (seq hits))
      (is (every? #(sub/object st (:id %)) hits)
          (str "a hit that is not an object in the substrate: " (pr-str (mapv :id hits))))
      (is (not-any? #(str/includes? (str (:id %)) "#") hits)
          (str "a chunk id was returned where an object id belongs: " (pr-str (mapv :id hits)))))))

(deftest a-long-document-is-one-result-and-not-one-per-section
  (with-leap [[(doc "doc:fab" "Fab notes"
                    (str "# Capacity\nFabrication capacity is concentrated in Hsinchu.\n"
                         "## Wafers\nWafer starts rose through the quarter.\n"
                         "## Lithography\nThe newest chip lines are booked years ahead."))]]
    (ch/sweep! st cs)
    (is (< 1 (count (ch/all-chunks st))) "the document really does chunk into several")
    (let [hits (meaning (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs}))]
      (is (= ["doc:fab"] (mapv :id hits))
          "one row per document — two fragments of the same document are one result"))))

(deftest an-object-already-found-lexically-is-not-repeated-by-meaning
  ;; The new group must show what substring search could NOT find, rather than
  ;; saying the same thing twice in two different voices.
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (let [deep (srv/leap-payload st m "churn" {:deep? true :chunks cs})]
      (is (= ["doc:retention"] (mapv :id (of-group deep "in text")))
          "substring search finds the document whose body says churn")
      (is (= ["doc:winback"] (mapv :id (meaning deep)))
          "and meaning adds the one it could not — not the one it already had")
      (is (= 1 (count (filter #(= "doc:retention" (:id %)) deep)))
          "doc:retention appears exactly once in the whole response"))))

(deftest the-semantic-group-is-capped-like-the-others
  (let [many (vec (for [i (range 12)]
                    (doc (str "doc:fab-" i) (str "Fab " i)
                         (str "Wafer starts at line " i " rose through the quarter."))))]
    (with-leap [many]
      (ch/sweep! st cs)
      (let [hits (meaning (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs}))]
        (is (= 12 (count (ch/embedded cs))) "twelve candidates")
        (is (= 8 (count hits)) "eight shown, the same cap the lexical groups use")
        (is (= 8 (count (distinct (map :id hits)))))))))

(deftest hits-arrive-best-first
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (let [hits (meaning (srv/leap-payload st m "why do accounts churn" {:deep? true :chunks cs}))]
      (is (seq hits))
      (is (= (mapv :score hits) (reverse (sort (mapv :score hits)))))
      (is (every? #(pos? (:score %)) hits)
          "a chunk with no shared direction at all is not a hit by meaning"))))

;; ===========================================================================
;; degradation — the two ways the embedder is not there
;; ===========================================================================

(deftest deep-with-no-embedder-configured-is-exactly-deep-absent
  ;; Non-negotiable in the spec: with nothing configured, LEAP is what it is
  ;; today. Not a marker, not an empty group, not one extra field.
  (with-leap [(corpus)]
    (ch/sweep! st cs)                       ; vectors exist; the endpoint then goes away
    (reset! seen [])
    (with-env {}
      (fn []
        (doseq [q ["churn" "where are chips made" ""]]
          (is (= (srv/leap-payload st m q)
                 (srv/leap-payload st m q {:deep? true :chunks cs}))
              (str "?deep=1 differed from plain with no embedder, for " (pr-str q))))))
    (is (= [] (texts-asked-for seen))
        "and nothing was sent to a baked-in default")))

(deftest an-unreachable-embedder-degrades-honestly-rather-than-erroring
  (with-tmpdir [chunks memfile]
    (let [st (store-with (corpus))
          cs (ch/file-chunks chunks)
          m  (mem/file-memory memfile)]
      ;; embed the chunks first, so the only thing missing later is the query
      (with-stub (fn [port _] (with-env (embedding-at port) #(ch/sweep! st cs))))
      (with-env (embedding-at (a-closed-port))
        (fn []
          (let [plain (srv/leap-payload st m "churn")
                deep  (srv/leap-payload st m "churn" {:deep? true :chunks cs})]
            (is (vector? deep) "a value, not a throw and not an error map")
            (is (= plain (filterv #(not= "by meaning" (:group %)) deep))
                "today's response is still there, entry for entry, in order")
            (is (seq plain) "and it is not an empty page")
            (let [note (first (notes deep))]
              (is (some? note) "the response says it is degraded")
              (is (string? (:degraded note)))
              (is (str/includes? (:label note) "not answering"))
              (is (= "__deep__" (:id note))
                  "a note is not an object — it carries the pseudo-id, like the memory group's"))))))))

(deftest chunks-not-embedded-yet-are-reported-rather-than-passed-off-as-searched
  ;; An empty `by meaning` group is ambiguous between "nothing here means that"
  ;; and "nothing has been embedded yet", and the second reading is the one that
  ;; would have somebody believe their document is not in loci.
  (with-leap [(corpus)]
    (let [deep (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs})
          note (first (notes deep))]
      (is (empty? (meaning deep)) "nothing is embedded yet")
      (is (some? note))
      (is (= (count (ch/all-chunks st)) (:awaiting note))
          "counted against the substrate, so a chunks.edn that was never built says so")
      (is (str/includes? (:label note) "awaiting embedding"))
      ;; and once the sweep has run, the note is gone
      (ch/sweep! st cs)
      (let [after (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs})]
        (is (empty? (notes after)))
        (is (seq (meaning after)))))))

;; ===========================================================================
;; vectors must not outlive what they describe
;; ===========================================================================

(deftest a-deleted-objects-chunk-is-never-a-hit-before-or-after-the-sweep-that-retires-it
  (with-leap [(corpus)]
    (ch/sweep! st cs)
    (is (= ["doc:supply"] (mapv :id (meaning (srv/leap-payload st m "where are chips made"
                                                               {:deep? true :chunks cs})))))
    (sub/commit! st {:op :delete :id "doc:supply"})
    ;; The vector is still in the file — no sweep has run — and it is still the
    ;; closest thing to the query. It must not be offered: it would open an
    ;; object that no longer exists.
    (is (empty? (meaning (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs})))
        "a chunk whose object went away opens nothing, so it is not a hit")
    (is (pos? (:retired (ch/sweep! st cs))))
    (is (empty? (meaning (srv/leap-payload st m "where are chips made" {:deep? true :chunks cs})))
        "and a retired chunk is not a hit either")))

;; ===========================================================================
;; the background sweep — started from -main, never on require
;; ===========================================================================

(deftest the-chunk-sweep-worker-embeds-in-the-background-and-stops-when-told
  (with-leap [(corpus)]
    (let [w (srv/start-chunk-sweep! st cs {:interval-ms 20})]
      (try
        (is (loop [i 0]
              (cond (empty? (ch/pending st cs)) true
                    (> i 300)                   false
                    :else (do (Thread/sleep 10) (recur (inc i)))))
            "the worker embedded every pending chunk within three seconds")
        (finally
          (is (srv/stop-chunk-sweep! w) "and it stops when asked")))
      (is (= (count (ch/all-chunks st)) (count (ch/embedded cs))))
      ;; the point of the worker: what it embedded is now findable by meaning
      (is (= ["doc:supply"]
             (mapv :id (meaning (srv/leap-payload st m "where are chips made"
                                                  {:deep? true :chunks cs}))))))))

(deftest requiring-the-server-starts-no-sweep-and-touches-no-data-directory
  (is (not-any? #(= "loci-chunk-sweep" (.getName ^Thread %)) (keys (Thread/getAllStackTraces)))
      "a namespace that spawns on load leaves a thread behind in every process that read it")
  (is (delay? @#'ch/chunks) "the server's chunk store is a delay")
  (is (not (realized? @#'ch/chunks))
      "and nothing in this suite has forced it — every test passes an explicit temp path"))
