(ns loci.embed
  "Where the embedder and the reranker live: how they are configured, how they
   are called, and cosine. Fusion is not here — `loci.memory` does that, because
   fusion is about facts and this namespace knows nothing about facts.

   Every call here returns a value, never an exception. `{:off true}` means not
   configured, `{:error \"…\"}` means the call was made and did not work, and
   those are different things a caller wants to report differently. A down
   embedder must degrade recall to what it is today, not break it.

   Each configuration value reads the environment first, then a file in the
   working directory, then a default — the order `loci.agent` already established:

     LOCI_EMBED_ENDPOINT  → .embed-endpoint  → unset: semantic recall is off
     LOCI_EMBED_MODEL     → .embed-model     → embed-qwen3-0.6b
     LOCI_EMBED_API_KEY   → LOCI_LLM_API_KEY → unset: no Authorization header
     LOCI_RERANK_ENDPOINT → .rerank-endpoint → unset: rerank is skipped
     LOCI_RERANK_MODEL    → .rerank-model    → rerank-bge-m3
     LOCI_RERANK_API_KEY  → LOCI_EMBED_API_KEY → LOCI_LLM_API_KEY → unset

   The embedder carries its own token on purpose rather than as a nicety: the
   obvious deployment is a hosted chat model beside a local embedder, which is
   two credentials against two operators. Falling back to LOCI_LLM_API_KEY keeps
   the single-provider case to one variable; rerank falls back to the embed key
   because it is usually the same box.

   The two endpoints are optional independently of each other. With neither,
   recall is exactly what it is today."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.client :as hc]))

;; Copied verbatim from loci.agent, deliberately — not reimplemented. Sharing it
;; means either making that namespace's private helper public, so this config
;; layer would have to require the chat client for one line of io, or a new
;; namespace holding a single expression. Neither is worth it for one line. What
;; matters is that the copy is exact: a change to one is a change to both.
(defn- from-file [path] (let [f (io/file path)] (when (.exists f) (str/trim (slurp f)))))

(defn- env
  "One indirection over System/getenv. It is a Java static, which with-redefs
   cannot reach, so without this seam the resolution order below is untestable."
  [k]
  (System/getenv k))

(defn- non-blank
  "nil for nil, \"\" and whitespace-only — a blank value is unset, not
   configured. `LOCI_EMBED_ENDPOINT=` left empty in a loci.env is how this gets
   got wrong in practice, and it must leave the endpoint unconfigured rather
   than aim a client at an empty URL."
  [s]
  (when-not (str/blank? s) s))

;; Everything below resolves per call, never at load. A `def` here would freeze
;; whatever the environment held when the namespace was first required — which
;; is both unreachable from a test and wrong for anything set afterwards.

(defn embed-endpoint
  "Full URL of the embedding server, or nil when semantic recall is not
   configured. A full URL, path included, so any OpenAI-compatible embeddings
   endpoint will do."
  []
  (or (non-blank (env "LOCI_EMBED_ENDPOINT"))
      (non-blank (from-file ".embed-endpoint"))))

(defn embed-model
  "The embedding model to ask for. Every embedded fact records this, so a change
   of model is detectable rather than silently meaningless."
  []
  (or (non-blank (env "LOCI_EMBED_MODEL"))
      (non-blank (from-file ".embed-model"))
      "embed-qwen3-0.6b"))

(defn embed-key
  "The embedder's bearer token, or **nil** when none is configured — never \"\".

   That distinction is the contract for whoever writes the client: nil means
   send no Authorization header at all, not an empty one. A llama.cpp started
   without --api-key rejects an empty bearer, so `Authorization: Bearer ` turns
   \"no key needed\" into a 401 that reads like a wrong key.

   LOCI_EMBED_API_KEY wins so a local embedder's token need not be filed under
   the chat provider; LOCI_LLM_API_KEY covers the single-provider case."
  []
  (or (non-blank (env "LOCI_EMBED_API_KEY"))
      (non-blank (env "LOCI_LLM_API_KEY"))))

(defn rerank-endpoint
  "Full URL of the reranking server, or nil — in which case rerank is skipped
   and the fused result is returned marked as unranked by it."
  []
  (or (non-blank (env "LOCI_RERANK_ENDPOINT"))
      (non-blank (from-file ".rerank-endpoint"))))

(defn rerank-model
  "The reranking model to ask for."
  []
  (or (non-blank (env "LOCI_RERANK_MODEL"))
      (non-blank (from-file ".rerank-model"))
      "rerank-bge-m3"))

(defn rerank-key
  "The reranker's bearer token, or **nil** when none is configured — never \"\",
   for the same reason as `embed-key`: nil means send no Authorization header.
   Falls back to the embed key because rerank is usually served by the same box."
  []
  (or (non-blank (env "LOCI_RERANK_API_KEY"))
      (embed-key)))

(defn embedding-configured?
  "True once an embedding endpoint resolves to something non-blank. False leaves
   recall exactly as it is today — lexical only, nothing degraded."
  []
  (some? (embed-endpoint)))

(defn rerank-configured?
  "True once a reranking endpoint resolves to something non-blank. Independent
   of `embedding-configured?`: embed may be configured without rerank, in which
   case fusion runs and rerank is skipped."
  []
  (some? (rerank-endpoint)))

;; ---------------------------------------------------------------------------
;; The client
;; ---------------------------------------------------------------------------

(def ^:private timeout-ms
  "Generous, because a batch backfill of every unembedded fact goes through this
   same call and a cold model server can take seconds to answer the first one.
   It is a ceiling on how long a *background* worker waits; nothing on a
   keystroke path calls this."
  30000)

(defn- headers-for
  "JSON content type, and a bearer only when there is a key to bear.

   `k` is nil rather than \"\" when no key is configured (see `embed-key`), and
   acting on that distinction is the whole reason it exists: a llama.cpp started
   without --api-key rejects `Authorization: Bearer `, so sending an empty one
   turns \"no key needed\" into a 401 that reads like a wrong key."
  [k]
  (cond-> {"Content-Type" "application/json"}
    k (assoc "Authorization" (str "Bearer " k))))

(defn- post-json
  "POST `body` as JSON to `url`. Returns `{:body parsed}` or `{:error message}`,
   and never throws — a caller ranking facts has nothing useful to do with an
   exception and everything to do with \"the embedder is not answering\".

   The two are distinct keys rather than one map so that a server which happens
   to answer 200 with an \"error\" field of its own cannot be mistaken for a
   transport failure here."
  [url k body]
  (let [resp (try @(hc/post url {:timeout timeout-ms
                                 :headers (headers-for k)
                                 :body    (json/write-str body)})
                  (catch Throwable t {:error t}))]
    (cond
      ;; http-kit hands back a connection failure in :error rather than throwing
      (:error resp)      {:error (str "request to " url " failed: " (:error resp))}
      (not= 200 (:status resp)) {:error (str url " returned HTTP " (:status resp))}
      :else (try {:body (json/read-str (:body resp) :key-fn keyword)}
                 (catch Throwable _
                   {:error (str "could not parse the response from " url " as JSON")})))))

(defn- vectors-in-input-order
  "Turn an OpenAI-shaped embeddings response into vectors in the caller's order.

   The response is a list of objects each carrying an `:index`, and it is not
   required to arrive in input order. Reading it by array position is the
   failure this function exists to prevent: it throws nothing and looks fine,
   and it attaches one fact's meaning to a different fact permanently. So the
   indices are required to be exactly 0..n-1 — a missing, duplicated or
   out-of-range one is an error, not something to fall back from."
  [body n model]
  (let [data (:data body)]
    (cond
      (not (sequential? data))
      {:error "the embedding response had no :data array"}

      (or (not= n (count data))
          (not= (set (range n)) (set (map :index data))))
      {:error (str "the embedding response carried indices " (pr-str (mapv :index data))
                   " for " n " input(s) — expected exactly 0.." (dec n))}

      :else
      (let [by-index (reduce (fn [m {:keys [index embedding]}]
                               (assoc m index (vec embedding)))
                             {} data)
            vectors  (mapv by-index (range n))
            dims     (set (map count vectors))]
        (cond
          (not= 1 (count dims))
          ;; Ragged means at least one of these is not the vector it claims to
          ;; be, and there is no way to tell which. The spec's failure table
          ;; asks for the dimension seen, so it is in the message.
          {:error (str "the embedding response mixed dimensions " (pr-str (vec (sort dims)))
                       " in one batch")}

          (zero? (first dims))
          {:error "the embedding response contained an empty vector (dimension 0)"}

          :else
          ;; :model is the *configured* name, not the one the response reports.
          ;; llama.cpp answers with the loaded model's path, and a fact stamped
          ;; with that would never match `(embed-model)` again — every backfill
          ;; pass would re-embed it forever.
          {:vectors vectors :model model :dim (first dims)})))))

(defn embed-texts
  "Embed `texts` with the configured embedder.

   Returns one of:

     {:vectors [[…] …] :model \"…\" :dim n}  vectors in the order of `texts`
     {:off true}                            no embedding endpoint configured
     {:error \"…\"}                           it was asked and it did not work

   `:dim` is read off the response rather than assumed; embed-qwen3-0.6b
   returned 1024 on 2026-08-07, but that is one model's answer."
  [texts]
  (let [url (embed-endpoint)]
    (cond
      (nil? url) {:off true}

      ;; Nothing to ask about. Some servers 400 an empty "input", which would
      ;; surface to a caller who asked for nothing as though the embedder were
      ;; broken. :dim is nil because there is no vector to have a dimension.
      (empty? texts) {:vectors [] :model (embed-model) :dim nil}

      :else
      (let [model (embed-model)
            r     (post-json url (embed-key) {:model model :input (vec texts)})]
        (if (:error r)
          (select-keys r [:error])
          (vectors-in-input-order (:body r) (count texts) model))))))

(defn rerank
  "Score `documents` against `query` with the configured reranker.

   Returns one of:

     [{:index i :score s} …]  best first; :index points into `documents`
     {:off true}              no rerank endpoint configured — proceed unranked
     {:error \"…\"}             it was asked and it did not work

   Success is a vector and both other cases are maps, so a caller discriminates
   with `vector?`.

   `:score` is the model's raw output and not a probability: rerank-bge-m3
   measured 0.687 down to -11.04 on 2026-08-07. A negative score is an ordinary
   low-relevance answer, not a failure, and any cutoff on these numbers would
   need calibrating per model."
  [query documents]
  (let [url (rerank-endpoint)]
    (cond
      (nil? url)         {:off true}
      (empty? documents) []
      :else
      (let [n (count documents)
            r (post-json url (rerank-key)
                         {:model (rerank-model) :query query :documents (vec documents)})]
        (if (:error r)
          (select-keys r [:error])
          (let [results (:results (:body r))
                usable? (fn [{:keys [index relevance_score]}]
                          (and (integer? index) (< -1 index n) (number? relevance_score)))]
            (cond
              (not (sequential? results))
              {:error "the rerank response had no :results array"}

              ;; An index past the documents we sent would throw in whoever
              ;; indexes with it, far from here and long after.
              (not (every? usable? results))
              {:error (str "the rerank response contained an entry that is not "
                           "{:index 0.." (dec n) " :relevance_score number}")}

              :else
              (->> results
                   (mapv (fn [{:keys [index relevance_score]}]
                           {:index index :score (double relevance_score)}))
                   (sort-by :score >)
                   vec))))))))

(defn cosine
  "Cosine similarity of two vectors of equal length: dot / (|a|·|b|), in [-1, 1].

   The division is not redundant. embed-qwen3-0.6b happens to return
   L2-normalised vectors — measured norm 1.0 on 2026-08-07 — which makes the
   divisor 1.0 and a bare dot product indistinguishable from cosine. That is a
   property of that model, not of the embeddings format: a model that did not
   normalise would give a dot product scores outside [-1, 1] that still sort
   plausibly, so nothing would look wrong.

   Returns 0.0 — not an exception, and not a partial answer — when either vector
   is empty, either is all zeros, or the two differ in length. A caller ranking
   facts needs \"not similar\" here; vectors of different length come from
   different models and cannot be compared at all, and a dot product over their
   common prefix would be a number that sorts."
  [a b]
  (let [a (vec a) b (vec b) n (count a)]
    (if (or (zero? n) (not= n (count b)))
      0.0
      (loop [i 0, dot 0.0, na 0.0, nb 0.0]
        (if (= i n)
          (let [d (* (Math/sqrt na) (Math/sqrt nb))]
            (if (zero? d) 0.0 (/ dot d)))
          (let [x (double (nth a i))
                y (double (nth b i))]
            (recur (inc i) (+ dot (* x y)) (+ na (* x x)) (+ nb (* y y)))))))))
