(ns loci.sourcespec
  "What a source IS, as two pure functions: a location URI becomes something
   DuckDB can address, and a source becomes the SQL relation expression that
   reads it.

   No lake, no file, no network, no clock — so every test here is an `=`, and
   the connector-specific part of the framework is the part you can be sure of
   without a fixture.

   It is also what breaks the cycle. `loci.lake` needs `relation` to read a
   `:source` handle; `loci.source` needs `loci.lake` to run anything. Both
   require this, and this requires neither."
  (:require [clojure.string :as str]
            [loci.substrate :as sub]))

(def readers
  "format → the DuckDB function that reads it. Phase 1's whole connector.

   Excel is absent deliberately: it needs a loadable extension, and shipping one
   into the image is a problem that cost two commits in August 2026
   (`f8f1c94`, `30d3172`). It is a connector, and it can be added the day
   someone has an `.xlsx`."
  {:csv "read_csv" :parquet "read_parquet" :json "read_json_auto"})

(def ^:private loci-prefix "loci://")

(defn locate
  "A location URI → a string DuckDB can address.

   `loci://files/x.csv` resolves under `<LOCI_DATA>`, and that is the scheme
   that makes a substrate portable: the log records no machine path, so the same
   event resolves in a container, on another laptop, or out of a restored
   backup. Which is why a `loci://` path that climbs out of the data directory
   is refused — not as a guard, but because such a path is not a loci path. A
   host path is spelled `file://`, which is unrestricted by decision."
  [uri]
  (let [uri (str uri)]
    (cond
      (str/starts-with? uri loci-prefix)
      (let [rel (subs uri (count loci-prefix))]
        (when (or (str/blank? rel)
                  (str/starts-with? rel "/")
                  (some #{".."} (str/split rel #"/")))
          (throw (ex-info (str "not a loci:// location: " uri
                               " — loci:// names a path under the data directory."
                               " Use file:// for a path outside it.")
                          {:uri uri})))
        (str (sub/data-dir) "/" rel))

      (str/starts-with? uri "file://")
      ;; `java.net.URI.` throws its own `URISyntaxException` before the blank
      ;; check can run — a bare `file://` gives "Expected authority at index 7",
      ;; which names neither loci nor the thing the caller typed. Every other
      ;; branch here answers with an `ex-info` carrying `:uri`, and `describe`'s
      ;; catch turns the message into what the shell shows, so this one does too.
      (let [p (try (.getPath (java.net.URI. uri))
                   (catch java.net.URISyntaxException e
                     (throw (ex-info (str "not a usable file:// location: " uri
                                          " — " (.getMessage e))
                                     {:uri uri} e))))]
        (when (str/blank? p)
          (throw (ex-info (str "file:// location has no path: " uri) {:uri uri})))
        p)

      :else
      (throw (ex-info (str "loci does not know this location: " uri
                           " — it knows loci:// and file://."
                           " Remote schemes (s3://, https://) arrive with httpfs.")
                      {:uri uri})))))

(defn- sql-str
  "A value as a single-quoted SQL string literal."
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

(def ^:private option-name
  "What may appear to the left of the `=` in a reader option: a SQL identifier,
   and nothing else.

   `:options` arrive from a JSON body on `/api/source/connect`, which makes this
   the one place in the framework where a caller chooses SQL *text* rather than a
   value. `sql-str` quotes a value; there is no equivalent for an option NAME,
   because DuckDB wants a bare identifier there and a quoted one is a syntax
   error. So the name is CHECKED rather than escaped, and anything that is not an
   identifier is refused."
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn- opt-sql [[k v]]
  (let [k (name k)]
    (when-not (re-matches option-name k)
      (throw (ex-info (str "not a reader option name: " (pr-str k)) {:option k})))
    (str k "="
         (cond (string? v)  (sql-str v)
               (boolean? v) (if v "true" "false")
               (number? v)  v
               :else (throw (ex-info (str "a reader option takes a string, a number or a boolean — "
                                          (pr-str k) " got " (pr-str v))
                                     {:option k :value v}))))))

(defn relation
  "A source → the SQL relation expression that reads it at `location`.

   Options are emitted in key order so the text is deterministic and the whole
   function is testable with `=`. DuckDB does not care about the order.

   Ordered by the key's NAME rather than the key, because `sort-by key` is a
   partial comparator: two options whose keys are a string and a keyword —
   reachable by hand from the REPL facade, if not from a JSON body — throw a
   `ClassCastException` out of the sort before `opt-sql` can refuse them with a
   sentence. Same order for a keyword-only map, one word longer, and the
   friendly error always wins."
  [{:keys [format options]} location]
  (let [f (get readers format)]
    (when-not f
      (throw (ex-info (str "loci does not know the format " (pr-str format)
                           " — it knows " (str/join ", " (sort (map name (keys readers)))) ".")
                      {:format format})))
    (str f "(" (sql-str location)
         (when (seq options)
           (str ", " (str/join ", " (map opt-sql (sort-by (comp name key) options)))))
         ")")))

(defn readable?
  "True when `uri` names a file this process can read right now.

   The one thing that tells `:table/unavailable` from `:table/source-drift`: a
   query can fail because the bytes are gone, or because they are there and no
   longer match the columns recorded at connect. The first is a missing source,
   the second is a changed one, and a reader does different things about them.

   False rather than a throw for a location `locate` refuses, because this is
   asked on the failure path and must not raise a second error over the first."
  [uri]
  (try (.canRead (java.io.File. ^String (locate uri)))
       (catch Exception _ false)))
