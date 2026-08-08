(ns loci.config
  "One config file, Docker or not.

   `loci.env` was a Docker env-file and nothing else: `docker compose` turns each
   line into a real environment variable before loci starts, and loci reads it
   through System/getenv. Run `clojure -M:serve` and none of it applied — you had
   to export the same values by hand, which is the same configuration written
   twice and one of the two copies always stale.

   So every configurable value resolves in this order:

     real environment → loci.env → the single-value dotfile → default

   Only the first two steps live here; the dotfile and the default belong to
   whoever is asking, because only they know which file and which default.

   **The real environment stays first.** A one-off
   `LOCI_EMBED_MODEL=… clojure -M:serve` must still beat the file, and it is also
   what keeps the container exactly as it was: compose has already turned
   loci.env into real environment variables by the time anything here runs, so in
   Docker every value arrives by the first route and this namespace finds no file
   at all — `.dockerignore` keeps loci.env out of the image on purpose.

   **Blank is unset — in the file.** `LOCI_LLM_API_KEY=` is how loci.env.example
   ships, and an unfilled line must fall through rather than resolve to \"\" and
   shadow the `.llm-key` below it. A blank in the *real* environment is the
   opposite thing: someone typing `LOCI_EMBED_ENDPOINT= clojure -M:serve` to turn
   something off for one run. That wins, and the caller's own `non-blank` check
   turns it into the unset it means."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- default-path
  "`loci.env` in the working directory — the same file `compose.yaml` names in
   `env_file` and `docker run --env-file` takes. A function, not a `def`, so a
   test can bind it to a temp file; deliberately not user-configurable, because a
   second way to say where the config lives is a second thing to get wrong."
  []
  "loci.env")

(defn- getenv
  "One indirection over System/getenv, for the same reason `loci.agent/env` and
   `loci.embed/env` have one: it is a Java static that with-redefs cannot reach,
   so without this seam the order above is untestable."
  [k]
  (System/getenv k))

(def ^:private key-pattern
  "What may appear to the left of the `=`. Anything else is a malformed line
   rather than a variable named `not a key`."
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn- unquoted
  "Strip one matching pair of surrounding quotes, if there is one. Quotes are how
   a value keeps leading or trailing spaces that the trim above would eat."
  [v]
  (if (and (>= (count v) 2)
           (let [q (first v)]
             (and (or (= \" q) (= \' q)) (= q (last v)))))
    (subs v 1 (dec (count v)))
    v))

(defn parse
  "Parse env-file `text` into `{:vars {k v} :skipped [{:line n} …]}`.

   The dotenv shape compose already requires and no more: `KEY=VALUE` per line,
   `#` comments, blank lines ignored, surrounding quotes stripped, and only the
   FIRST `=` split on so a value may contain one. A leading `export ` is
   tolerated because the same file gets `source`d by a shell often enough.

   Two deliberate non-features. A `#` *inside* a value is part of the value —
   compose does not treat it as a comment, and a password containing one would
   otherwise be silently truncated. And a line that is not `KEY=VALUE` is
   **skipped, not fatal**: a config file that stops the app over one bad line is
   worse than one that ignores it. `:skipped` carries the line numbers so the
   caller can say which — numbers only, never the text, because a malformed line
   is exactly where a mistyped key ends up."
  [text]
  (reduce
   (fn [acc [n raw]]
     (let [line (str/trim raw)]
       (if (or (str/blank? line) (str/starts-with? line "#"))
         acc
         (let [line (str/replace line #"^export\s+" "")
               i    (str/index-of line "=")
               k    (when i (str/trim (subs line 0 i)))
               v    (when i (unquoted (str/trim (subs line (inc i)))))]
           (cond
             (or (nil? i) (not (re-matches key-pattern k)))
             (update acc :skipped conj {:line n})

             ;; blank is unset: the example's unfilled lines fall through to
             ;; whatever is below them instead of shadowing it with "".
             (str/blank? v) acc

             :else (assoc-in acc [:vars k] v))))))
   {:vars {} :skipped []}
   (map-indexed (fn [i l] [(inc i) l]) (str/split-lines text))))

(def ^:private cached
  "The last parse, as `{:key [path modified length] :vars {…}}`.

   Keyed on the file's `lastModified` **and** its length, so an edit on disk is
   picked up inside a running process: a config value frozen at first touch is
   the bug `loci.agent/endpoint` used to have as a `def`, and it is worth not
   reintroducing one level down. Length is in the key because `lastModified` is
   millisecond-granular at best and a fast rewrite can land in the same tick.

   The cache is not for speed — re-reading a 2 KB file per call would be fine.
   It is so the skipped-line warning is said once per version of the file rather
   than once per lookup, and an agent turn makes a dozen lookups."
  (atom nil))

(defn dotenv
  "The variables set in `loci.env` (or in `p`), as a map of string to string.

   `{}` when there is no such file — an existing checkout has none and must keep
   resolving exactly as it did. Blank values are absent, and malformed lines are
   skipped with a line number on stderr."
  ([] (dotenv (default-path)))
  ([p]
   (let [f (io/file p)]
     (if-not (.exists f)
       {}
       (let [k [(str p) (.lastModified f) (.length f)]
             c @cached]
         (if (= k (:key c))
           (:vars c)
           (let [{:keys [vars skipped]} (parse (slurp f))]
             (doseq [{:keys [line]} skipped]
               (binding [*out* *err*]
                 (println (str p ":" line ": ignored — not KEY=VALUE."
                               " (The line is not quoted here: a malformed line is"
                               " often a mistyped key, and a log is a bad place for one.)"))))
             (reset! cached {:key k :vars vars})
             vars)))))))

(defn env
  "`k` from the real environment, else from `loci.env`, else nil.

   nil rather than \"\" when neither has it, so the caller's own dotfile and
   default still run — this inserts a step into that chain, it does not replace
   it."
  [k]
  (or (getenv k) (get (dotenv) k)))
