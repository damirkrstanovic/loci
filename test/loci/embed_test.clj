(ns loci.embed-test
  "Where the embedder and reranker live is configuration. These tests exercise
   the RESOLUTION ORDER only — there is no client yet, and nothing here touches
   the network.

   `embed/env` is the seam they bind. System/getenv is a Java static method and
   with-redefs cannot reach it, so a test written directly against the real
   environment would pass or fail for reasons that have nothing to do with the
   resolver — and would pass or fail differently on the machine that has a token
   exported than on the one that does not."
  (:require [clojure.test :refer [deftest is]]
            [loci.embed :as embed]))

(defn- resolved
  "Call one of the resolvers under a fixed environment and a fixed set of
   working-directory files."
  [resolver env files]
  (with-redefs [embed/env #(get env %) embed/from-file #(get files %)]
    (resolver)))

;; ---- defaults: an existing checkout sets none of this and must keep working ----

(deftest nothing-configured-leaves-both-endpoints-off-and-both-keys-nil
  (is (nil? (resolved embed/embed-endpoint {} {})))
  (is (nil? (resolved embed/rerank-endpoint {} {})))
  (is (= "embed-qwen3-0.6b" (resolved embed/embed-model {} {})))
  (is (= "rerank-bge-m3" (resolved embed/rerank-model {} {})))
  (is (nil? (resolved embed/embed-key {} {}))
      "nil, never \"\" — an empty bearer is a 401 that reads like a wrong key")
  (is (nil? (resolved embed/rerank-key {} {}))
      "nil, never \"\" — same reason")
  (is (false? (resolved embed/embedding-configured? {} {})))
  (is (false? (resolved embed/rerank-configured? {} {}))))

;; ---- environment, then file, then default ----

(deftest embed-endpoint-reads-env-then-file
  (is (= "http://env:8080/v1/embeddings"
         (resolved embed/embed-endpoint
                   {"LOCI_EMBED_ENDPOINT" "http://env:8080/v1/embeddings"}
                   {".embed-endpoint" "http://file:8080/v1/embeddings"}))
      "the environment wins over the file")
  (is (= "http://file:8080/v1/embeddings"
         (resolved embed/embed-endpoint {} {".embed-endpoint" "http://file:8080/v1/embeddings"}))
      "the file wins over the default, which is nothing at all"))

(deftest rerank-endpoint-reads-env-then-file
  (is (= "http://env:8080/v1/rerank"
         (resolved embed/rerank-endpoint
                   {"LOCI_RERANK_ENDPOINT" "http://env:8080/v1/rerank"}
                   {".rerank-endpoint" "http://file:8080/v1/rerank"}))
      "the environment wins over the file")
  (is (= "http://file:8080/v1/rerank"
         (resolved embed/rerank-endpoint {} {".rerank-endpoint" "http://file:8080/v1/rerank"}))
      "the file wins over the default"))

(deftest embed-model-reads-env-then-file-then-default
  (is (= "env-model" (resolved embed/embed-model
                               {"LOCI_EMBED_MODEL" "env-model"}
                               {".embed-model" "file-model"})))
  (is (= "file-model" (resolved embed/embed-model {} {".embed-model" "file-model"})))
  (is (= "embed-qwen3-0.6b" (resolved embed/embed-model {} {}))))

(deftest rerank-model-reads-env-then-file-then-default
  (is (= "env-model" (resolved embed/rerank-model
                               {"LOCI_RERANK_MODEL" "env-model"}
                               {".rerank-model" "file-model"})))
  (is (= "file-model" (resolved embed/rerank-model {} {".rerank-model" "file-model"})))
  (is (= "rerank-bge-m3" (resolved embed/rerank-model {} {}))))

;; ---- the embedder's token is its own, on purpose ----
;; A hosted chat model beside a local embedder is two credentials from two
;; operators; LOCI_LLM_API_KEY is the shortcut for the single-provider case.

(deftest embed-key-prefers-its-own-token-to-the-chat-providers
  (is (= "embed-token" (resolved embed/embed-key
                                 {"LOCI_EMBED_API_KEY" "embed-token"
                                  "LOCI_LLM_API_KEY"   "chat-token"}
                                 {}))
      "LOCI_EMBED_API_KEY wins — the local embedder's token is not the chat provider's")
  (is (= "chat-token" (resolved embed/embed-key {"LOCI_LLM_API_KEY" "chat-token"} {}))
      "one provider serving both stays one variable"))

(deftest rerank-key-falls-back-through-embed-to-the-chat-provider
  (is (= "rerank-token" (resolved embed/rerank-key
                                  {"LOCI_RERANK_API_KEY" "rerank-token"
                                   "LOCI_EMBED_API_KEY"  "embed-token"
                                   "LOCI_LLM_API_KEY"    "chat-token"}
                                  {})))
  (is (= "embed-token" (resolved embed/rerank-key
                                 {"LOCI_EMBED_API_KEY" "embed-token"
                                  "LOCI_LLM_API_KEY"   "chat-token"}
                                 {}))
      "rerank is usually the same box as embed, so it inherits that token")
  (is (= "chat-token" (resolved embed/rerank-key {"LOCI_LLM_API_KEY" "chat-token"} {}))
      "and falls all the way through when only the chat key is set"))

;; ---- a blank value is unset ----
;; `LOCI_EMBED_ENDPOINT=` left empty in a loci.env is how this gets got wrong in
;; practice: docker's --env-file sets the variable to "", so the endpoint is
;; present and useless. It must read as unconfigured, not as an empty URL.

(deftest a-blank-value-is-unset-not-a-configured-empty-string
  (is (nil? (resolved embed/embed-endpoint {"LOCI_EMBED_ENDPOINT" ""} {}))
      "an empty LOCI_EMBED_ENDPOINT= in a loci.env is unset")
  (is (false? (resolved embed/embedding-configured? {"LOCI_EMBED_ENDPOINT" ""} {}))
      "and so semantic recall is off, not aimed at an empty URL")
  (is (false? (resolved embed/rerank-configured? {"LOCI_RERANK_ENDPOINT" "   "} {}))
      "whitespace-only is unset too")
  (is (nil? (resolved embed/embed-endpoint {} {".embed-endpoint" "   "}))
      "a file holding only whitespace is unset as well")
  (is (= "http://file:8080/v1/embeddings"
         (resolved embed/embed-endpoint
                   {"LOCI_EMBED_ENDPOINT" ""}
                   {".embed-endpoint" "http://file:8080/v1/embeddings"}))
      "a blank env var does not shadow the file — it never took part")
  (is (= "embed-qwen3-0.6b" (resolved embed/embed-model {"LOCI_EMBED_MODEL" ""} {}))
      "a blank model falls through to the default rather than naming nothing")
  (is (nil? (resolved embed/embed-key {"LOCI_EMBED_API_KEY" ""} {}))
      "a blank key is no key: the client must send no Authorization header")
  (is (= "chat-token" (resolved embed/embed-key
                                {"LOCI_EMBED_API_KEY" "" "LOCI_LLM_API_KEY" "chat-token"}
                                {}))
      "and a blank one does not shadow the key that is actually set"))

;; ---- the two endpoints are optional independently of each other ----

(deftest embed-and-rerank-are-configured-independently
  (let [env {"LOCI_EMBED_ENDPOINT" "http://box:8080/v1/embeddings"}]
    (is (true? (resolved embed/embedding-configured? env {})))
    (is (false? (resolved embed/rerank-configured? env {}))
        "embed configured must not imply rerank configured — rerank is skipped, not guessed"))
  (let [env {"LOCI_RERANK_ENDPOINT" "http://box:8080/v1/rerank"}]
    (is (true? (resolved embed/rerank-configured? env {})))
    (is (false? (resolved embed/embedding-configured? env {}))))
  (let [env {"LOCI_EMBED_ENDPOINT"  "http://box:8080/v1/embeddings"
             "LOCI_RERANK_ENDPOINT" "http://box:8080/v1/rerank"}]
    (is (true? (resolved embed/embedding-configured? env {})))
    (is (true? (resolved embed/rerank-configured? env {})))))
