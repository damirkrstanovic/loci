(ns loci.config-test
  "`loci.env` was a Docker env-file and nothing else: compose turned each line
   into a real environment variable, and loci read it through System/getenv — so
   `clojure -M:serve` applied none of it and the same configuration had to be
   written twice. These tests pin the order it resolves in — real environment,
   then `loci.env`, then the caller's own dotfile/default chain — and the parsing
   of the file itself.

   `config/getenv` is the seam they bind, for exactly the reason `loci.agent/env`
   and `loci.embed/env` exist: System/getenv is a Java static that with-redefs
   cannot reach, so a test written against the real environment would pass or
   fail for reasons that have nothing to do with the resolver.

   `config/default-path` is the second seam, and it matters more than it looks:
   every test here writes its own `loci.env` under a temp directory and binds
   that seam to it. Nothing in this namespace reads the repo's own `loci.env`,
   which on a developer's machine holds real keys."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loci.agent :as agent]
            [loci.config :as config]
            [loci.embed :as embed]
            [loci.substrate :as sub]))

(defn- tmp-env-file
  "Write `text` as a `loci.env` in its own fresh temp directory; return the path.
   Its own directory, so no two tests share a file and the parse cache is
   exercised per path rather than per test order."
  ^String [text]
  (let [dir (java.io.File/createTempFile "loci-config-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (let [f (io/file dir "loci.env")]
      (spit f text)
      (.deleteOnExit f)
      (.deleteOnExit dir)
      (str f))))

(defn- missing-path
  "A path where no file exists — a checkout with no `loci.env` at all."
  ^String []
  (let [f (java.io.File/createTempFile "loci-config-absent" ".env")]
    (.delete f)
    (str f)))

(defn- resolved
  "Resolve `k` under a fixed real environment and a `loci.env` holding `text`."
  [env text k]
  (with-redefs [config/getenv       #(get env %)
                config/default-path (constantly (tmp-env-file text))]
    (config/env k)))

;; ---------------------------------------------------------------------------
;; the order: real environment → loci.env → (the caller's dotfile → default)
;; ---------------------------------------------------------------------------

(deftest the-real-environment-wins-over-loci-env
  (is (= "from-the-environment"
         (resolved {"LOCI_EMBED_MODEL" "from-the-environment"}
                   "LOCI_EMBED_MODEL=from-the-file\n"
                   "LOCI_EMBED_MODEL"))
      "a one-off LOCI_EMBED_MODEL=… clojure -M:serve must still beat the file — and
       in a container compose has already turned loci.env into real environment
       variables, so this is the route every value arrives by there"))

(deftest a-key-only-in-loci-env-resolves
  (is (= "embed-qwen3-4b"
         (resolved {} "LOCI_EMBED_MODEL=embed-qwen3-4b\n" "LOCI_EMBED_MODEL"))))

(deftest a-key-in-neither-is-nil
  (is (nil? (resolved {} "SOMETHING_ELSE=x\n" "LOCI_EMBED_MODEL"))
      "nil rather than \"\", so the caller's own dotfile/default chain still runs"))

(deftest blank-in-loci-env-is-unset-and-falls-through
  (is (nil? (resolved {} "LOCI_EMBED_MODEL=\n" "LOCI_EMBED_MODEL"))
      "`LOCI_LLM_API_KEY=` is how loci.env.example ships — an unfilled line must
       fall through, not resolve to \"\" and shadow the file below it")
  (is (nil? (resolved {} "LOCI_EMBED_MODEL=   \n" "LOCI_EMBED_MODEL"))
      "whitespace-only is blank too")
  (is (= "still-here"
         (resolved {} "LOCI_EMBED_MODEL=\nOTHER=still-here\n" "OTHER"))
      "and skipping it does not disturb the rest of the file"))

(deftest a-blank-real-variable-is-a-deliberate-override-and-wins
  ;; The asymmetry with the line above is the point. A blank in the FILE is the
  ;; example's unfilled line. A blank in the REAL environment is a person typing
  ;; `LOCI_EMBED_ENDPOINT= clojure -M:serve` to turn something off for one run,
  ;; and if the file were allowed to win there, that would be unsayable.
  (is (= "" (resolved {"LOCI_EMBED_ENDPOINT" ""}
                      "LOCI_EMBED_ENDPOINT=http://box:8080/v1/embeddings\n"
                      "LOCI_EMBED_ENDPOINT"))
      "\"\" reaches the caller, whose own non-blank check then makes it unset")
  (with-redefs [config/getenv       {"LOCI_EMBED_ENDPOINT" ""}
                config/default-path (constantly
                                     (tmp-env-file "LOCI_EMBED_ENDPOINT=http://box:8080/v1/embeddings\n"))
                embed/from-file     (constantly nil)]
    (is (false? (embed/embedding-configured?))
        "end to end: blanking it on the command line really does turn recall off")))

;; ---------------------------------------------------------------------------
;; parsing — the dotenv shape compose already requires, nothing more
;; ---------------------------------------------------------------------------

(deftest parsing-follows-the-env-file-shape
  (let [{:keys [vars skipped]}
        (config/parse (str "# a comment\n"
                           "\n"
                           "   \n"
                           "   # an indented comment\n"
                           "LOCI_EMBED_MODEL=embed-qwen3-4b\n"
                           "export LOCI_LLM_ENDPOINT=http://box:8080/v1/chat/completions\n"
                           "  SPACED  =  a spaced value  \n"
                           "DOUBLE=\"quoted value\"\n"
                           "SINGLE='quoted value'\n"
                           "KEEPS_SPACES=\"  padded  \"\n"
                           "EQUALS=a=b=c\n"
                           "HASH=pa#ssword\n"))]
    (is (= {"LOCI_EMBED_MODEL"   "embed-qwen3-4b"
            "LOCI_LLM_ENDPOINT"  "http://box:8080/v1/chat/completions"
            "SPACED"             "a spaced value"
            "DOUBLE"             "quoted value"
            "SINGLE"             "quoted value"
            "KEEPS_SPACES"       "  padded  "
            "EQUALS"             "a=b=c"
            "HASH"               "pa#ssword"}
           vars)
        "comments and blanks ignored, `export` tolerated, surrounding quotes
         stripped, only the first = split on, and a # inside a value kept —
         compose does not treat that as a comment and neither may we")
    (is (= [] skipped))))

(deftest a-malformed-line-is-skipped-not-fatal
  (let [{:keys [vars skipped]}
        (config/parse (str "GOOD=1\n"
                           "this line has no equals sign\n"
                           "=nothing-to-the-left\n"
                           "not a key=value\n"
                           "ALSO_GOOD=2\n"))]
    (is (= {"GOOD" "1" "ALSO_GOOD" "2"} vars)
        "a config file that stops the app over one bad line is worse than one
         that ignores it — the good lines still resolve")
    (is (= [2 3 4] (mapv :line skipped))
        "and the bad ones are named, by line number")))

(deftest a-malformed-loci-env-still-resolves-the-rest
  (is (= "embed-qwen3-4b"
         (binding [*err* (java.io.StringWriter.)]
           (resolved {} "!!! not a config line\nLOCI_EMBED_MODEL=embed-qwen3-4b\n"
                     "LOCI_EMBED_MODEL")))
      "nothing throws on the way out of the file"))

(deftest a-skipped-line-is-reported-by-number-and-never-quoted
  (let [err (java.io.StringWriter.)
        p   (tmp-env-file "LOCI_LLM_API_KEY sk-secret-value-here\nGOOD=1\n")]
    (binding [*err* err]
      (is (= {"GOOD" "1"} (config/dotenv p))))
    (let [said (str err)]
      (is (str/includes? said ":1") "says which line it ignored")
      (is (not (str/includes? said "sk-secret-value-here"))
          "and does NOT echo it: a malformed line is exactly where a mistyped
           key ends up, and a warning that quotes it puts the secret in the log"))))

;; ---------------------------------------------------------------------------
;; the file on disk
;; ---------------------------------------------------------------------------

(deftest a-missing-loci-env-is-not-an-error
  (let [p (missing-path)]
    (is (= {} (config/dotenv p)))
    (with-redefs [config/getenv (constantly nil)
                  config/default-path (constantly p)]
      (is (nil? (config/env "LOCI_EMBED_MODEL"))
          "a checkout with no loci.env at all resolves exactly as it did before"))))

(deftest an-empty-loci-env-is-not-an-error
  (is (= {} (config/dotenv (tmp-env-file "")))))

(deftest the-file-is-re-read-when-it-changes
  (let [p (tmp-env-file "LOCI_EMBED_MODEL=first\n")
        f (io/file p)]
    (is (= "first" (get (config/dotenv p) "LOCI_EMBED_MODEL")))
    (spit f "LOCI_EMBED_MODEL=second-and-rather-longer\n")
    (.setLastModified f (+ (.lastModified f) 2000))
    (is (= "second-and-rather-longer" (get (config/dotenv p) "LOCI_EMBED_MODEL"))
        "a config value must not be frozen at first touch, the way `endpoint`
         once was as a `def`")))

;; ---------------------------------------------------------------------------
;; the three seams that read the environment — all of them, or this is half done
;; ---------------------------------------------------------------------------

(deftest loci-env-reaches-the-embed-model
  ;; The case that prompted all of this: LOCI_EMBED_MODEL=embed-qwen3-4b in
  ;; loci.env took effect in the container and nowhere else.
  (with-redefs [config/getenv       (constantly nil)
                config/default-path (constantly (tmp-env-file "LOCI_EMBED_MODEL=embed-qwen3-4b\n"))
                embed/from-file     (constantly nil)]
    (is (= "embed-qwen3-4b" (embed/embed-model)))))

(deftest loci-env-reaches-the-chat-model
  (with-redefs [config/getenv       (constantly nil)
                config/default-path (constantly (tmp-env-file "DEEPSEEK_MODEL=a-local-model\n"))
                agent/from-file     (constantly nil)]
    (is (= "a-local-model" (#'agent/model)))))

(deftest loci-env-reaches-the-data-directory
  ;; LOCI_DATA leaves loci.env.example because the value there was a container
  ;; path; the KEY stays supported, for whoever genuinely wants it.
  (let [wanted (str (System/getProperty "java.io.tmpdir") "/loci-config-test-never-created")]
    (is (nil? (System/getProperty "loci.data-dir"))
        "precondition: -Dloci.data-dir would legitimately win over the file")
    (with-redefs [config/getenv       (constantly nil)
                  config/default-path (constantly (tmp-env-file (str "LOCI_DATA=" wanted "\n")))]
      (is (= wanted (sub/data-dir))))))

(deftest nothing-configured-still-gives-the-old-defaults
  (testing "an existing checkout with no loci.env and no environment is unchanged"
    (with-redefs [config/getenv       (constantly nil)
                  config/default-path (constantly (missing-path))
                  embed/from-file     (constantly nil)
                  agent/from-file     (constantly nil)]
      (is (= "embed-qwen3-0.6b" (embed/embed-model)))
      (is (= "rerank-bge-m3" (embed/rerank-model)))
      (is (nil? (embed/embed-endpoint)))
      (is (nil? (embed/embed-key)))
      (is (= "deepseek-v4-flash" (#'agent/model)))
      (is (= "https://api.deepseek.com/chat/completions" (#'agent/endpoint)))
      (is (= "data" (sub/data-dir))))))
