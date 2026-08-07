(ns loci.embed
  "Where the embedder and the reranker live — phase 3a's configuration layer,
   and deliberately nothing else. There is no HTTP client here, no cosine and no
   fusion; those are the next step and read their endpoint, model and token from
   this namespace.

   Each value reads the environment first, then a file in the working directory,
   then a default — the order `loci.agent` already established:

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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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
