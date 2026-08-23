(ns loci.sourcespec
  "What a source IS, as two pure functions: a location URI becomes something
   DuckDB can address, and a source becomes the SQL relation expression that
   reads it.

   No lake, no network, no clock — so `locate`, `relation` and `duckdb-said` are
   testable with `=`, and the connector-specific part of the framework is the
   part you can be sure of without a fixture.

   **`unreadable-because` is the exception, and deliberately so**: it asks the
   filesystem, because telling a missing source from a changed one is a question
   only the filesystem can answer, and answering it by matching on DuckDB's
   error text would be the fragile alternative. Its tests use temp files.

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

(def ^:private remote-schemes
  "The location schemes DuckDB's httpfs extension reads, and therefore the ones
   `locate` hands through untouched.

   `gs://` and `az://` are absent deliberately. `gs://` rides httpfs and would
   cost one entry here; `az://` is a separate extension with its own
   install-at-build-time problem — the one that cost two commits for ducklake in
   August 2026. Both arrive the day someone has one, which is the same rule the
   `readers` map above already applies to Excel."
  #{"http://" "https://" "s3://"})

(defn remote?
  "Is `uri` a location DuckDB fetches rather than one loci resolves?

   A scheme test and nothing more — it does not ask whether the object exists,
   which is the whole point: for a remote location, nothing short of a fetch
   can answer that, and the callers that use this are choosing a code path
   rather than reporting a state."
  [uri]
  (boolean (some #(str/starts-with? (str uri) %) remote-schemes)))

(defn locate
  "A location URI → a string DuckDB can address.

   `loci://files/x.csv` resolves under `<LOCI_DATA>`, and that is the scheme
   that makes a substrate portable: the log records no machine path, so the same
   event resolves in a container, on another laptop, or out of a restored
   backup. Which is why a `loci://` path that climbs out of the data directory
   is refused — not as a guard, but because such a path is not a loci path. A
   host path is spelled `file://`, which is unrestricted by decision.

   **Unrestricted, but still a URI**: after `file://` comes a HOST, and the path
   begins at the third slash. So `file://data/x.csv` does not name a relative
   path — it names `/x.csv` on a machine called `data` — and it is refused
   rather than resolved, because resolving it hands the caller a path at the
   filesystem root they never typed. That is the trap `5a11360` hit with
   `file://.tmp/…` and fixed at one call site; this closes it.

   `file://localhost/x` is the one authority that is ACCEPTED, because RFC 8089
   defines it as this machine, which makes it the three-slash form spelled
   long. Accepting it costs one clause and buys a refusal message that is always
   right: every authority it can now fire on really is a first path segment
   someone typed with two slashes instead of three, so naming that as what they
   meant is never a guess. Refusing localhost too would be one clause shorter
   and would have to tell the one caller who spelled it correctly to use
   `file:///localhost/x`, which is a different file.

   **A `remote?` scheme is answered VERBATIM**, and that identity is the whole
   of phase 3: `relation` already emits `read_csv('<location>')` from whatever
   this returns, so a location DuckDB can fetch needs no reader, no handle
   shape and no route of its own. Nothing is parsed on the way through — see
   the branch."
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
      (let [^java.net.URI u (try (java.net.URI. uri)
                                 (catch java.net.URISyntaxException e
                                   (throw (ex-info (str "not a usable file:// location: " uri
                                                        " — " (.getMessage e))
                                                   {:uri uri} e))))
            auth (.getAuthority u)
            p    (.getPath u)]
        ;; The AUTHORITY, not `.getHost`: `file://.tmp/x` has authority ".tmp"
        ;; and host nil, because a leading dot is not a legal hostname. Reading
        ;; the host would let exactly the shape that started this through.
        (when (and auth (not= "localhost" auth))
          (let [tail (subs uri (count "file://"))]
            (throw (ex-info (str "not a file:// location: " uri
                                 " — file:// is followed by a HOST, so " (pr-str auth)
                                 " here names a machine and not the first directory."
                                 " Use file:///" tail " for a path on this machine,"
                                 " or loci://" tail " for a path under the data directory.")
                            {:uri uri}))))
        (when (str/blank? p)
          (throw (ex-info (str "file:// location has no path: " uri) {:uri uri})))
        p)

      (remote? uri)
      ;; Verbatim. `relation` wraps this in `sql-str` and DuckDB parses it —
      ;; loci re-encoding it here could only make it wrong, and a `java.net.URI`
      ;; round trip is exactly what mangled `file://data/x.csv` into `/x.csv`
      ;; before `606505c` refused it. Measured 2026-08-23: that round trip does
      ;; not even survive `s3://bucket/a b.csv`, which throws "Illegal character
      ;; in path at index 13" rather than answering anything.
      uri

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

(defn duckdb-said
  "The one line of a DuckDB JDBC failure that says what actually went wrong.

   A missing file is the commonest way a source fails — a typo, a moved file, a
   `loci://` name that was never uploaded — and it is the only failure in
   `loci.source` that arrives as a `SQLException` rather than an `ex-info`,
   because it is DuckDB that notices rather than loci. Raw, that message is four
   lines:

     Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
     Error: IO Error: No files found that match the pattern \"/no/such/file.csv\"

     LINE 1: DESCRIBE SELECT * FROM read_csv('/no/such/file.csv')
                                    ^

   The first line is the JDBC driver describing its own state and says nothing
   about the source; the last two are a SQL dump of text loci generated. Only
   the `Error:` line is about what the caller did. `describe`'s callers put
   `.getMessage` straight into a JSON body — `server.clj` does this at every
   route — so this is what the shell shows.

   Lives here rather than in `loci.source` because `tablestore/hydrate` needs it
   too, for the message it carries under `:table/source-drift`, and `tablestore`
   cannot require `loci.source` without closing a cycle.

   **The whole `Error:` paragraph, not just its first line.** A missing file is
   one line, but a drifted one is two, and the second is the actionable half.
   Measured 2026-08-19, for a source recorded with columns `a,b` whose file now
   holds `c,d`:

     Error: Binder Error: Referenced column \"a\" not found in FROM clause!
     Candidate bindings: \"c\"

   `Candidate bindings` is what names the columns the file has NOW — the one
   thing a reader needs in order to reconnect it — and a first-line-only rule
   drops it. The paragraph ends at the blank line before the `LINE 1:` dump, so
   nothing about the missing-file message changes: its `Error:` line is followed
   by that blank immediately."
  [^Throwable e]
  (let [ls   (map str/trim (str/split-lines (str (.getMessage e))))
        para (->> ls
                  (drop-while #(not (str/starts-with? % "Error: ")))
                  (take-while (complement str/blank?)))]
    (or (when (seq para)
          (str/join " " (cons (subs (first para) 7) (rest para))))
        (first (remove str/blank? ls))
        (str e))))

(defn unreadable-because
  "Why `uri` cannot be read as a file right now, as a phrase — or nil when it
   can.

   This is what tells `:table/unavailable` from `:table/source-drift`: a
   passthrough read can fail because the bytes are not there, or because they
   are and no longer match the columns recorded at connect. Those want different
   repairs — find the file, versus reconnect it — so guessing between them
   badly is worse than not classifying at all.

   **A phrase rather than a boolean, and it is the phrase that gets shown.**
   DuckDB's own message for an unreadable file is not usable: for a `chmod 000`
   file its JDBC driver reports four lines, three of which are the driver
   describing its own state and a dump of SQL loci generated, and for some it
   throws a bare `NullPointerException` — `Cannot invoke
   \"java.lang.Number.doubleValue()\" because \"x\" is null` — which names
   nothing at all. loci has already looked at the filesystem by this point and
   knows the real answer, so it says it rather than forwarding that.

   **A directory is unreadable here, and `.canRead` disagrees.** Java calls a
   directory readable when its listing can be enumerated, so a boolean built on
   `.canRead` alone reports a source pointed at a directory as *drift* — telling
   the reader to reconnect a file that does not exist and never will. Measured
   2026-08-19.

   A phrase rather than a throw for a location `locate` refuses, too — this is
   asked on the failure path, while an error is already being handled, and must
   never raise a second one over the first. So every branch answers, including
   the one where the uri itself is the problem."
  [uri]
  (try
    (let [f (java.io.File. ^String (locate uri))]
      (cond
        (not (.exists f))  (str "there is no file at " (.getPath f))
        (.isDirectory f)   (str (.getPath f) " is a directory, not a file")
        (not (.isFile f))  (str (.getPath f) " is not a regular file")
        (not (.canRead f)) (str (.getPath f) " is not readable by " (sub/running-as))
        :else nil))
    (catch Exception e
      (str "loci cannot resolve " uri " — " (.getMessage e)))))
