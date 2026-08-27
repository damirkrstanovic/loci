(ns loci.lake
  "Tables live here. The substrate's log holds a handle; the rows are Parquet
   under a DuckLake catalog, and every read names the snapshot it wants.

   Measured 2026-08-16 (see the big-content spec): attaching a lake holding 10M
   rows costs 75 ms against loci's ~14 s dependency load, and a versioned read
   at 10M rows is 34 ms — faster than reading the current version, because an
   old snapshot carries no deletion vectors."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [honey.sql :as sql]
            [loci.config :as config]
            [loci.honeyq :as hq]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

(defn- conn-of [lk] (:conn lk))

(defn x!
  "Execute one statement. Returns :ok."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))] (.execute st sql))
  :ok)

(defn- unwrap
  "A JDBC value as something the rest of loci can hold and serialize.

   `read_json_auto` widens a column holding more than one type to DuckDB `JSON`,
   and JDBC hands those cells back as `org.duckdb.JsonNode`. That class is not a
   Clojure value and not a `clojure.data.json` one: `json-resp` is `write-str`
   (`server.clj:44-47`), so a single such cell makes the whole object
   **unopenable in the shell** — measured on `tbl:csv-18-1542` round-tripped
   through a real lake:

     Don't know how to write JSON of class org.duckdb.JsonNode

   and `query_table` on it throws into the agent's tool loop. Three tables in
   the live substrate carry one: `tbl:csv-18-1542` (`:inflation_pct`,
   `:growth_pct`, `:unemployment_pct`), `tbl:csv-19-7071` (`:density_per_km2`)
   and `tbl:extract-20-8413` (`:approx_low_income_share`).

   **The two `tbl:csv-*` are historical, and no CSV connected today can join
   them.** They were imported by `tools/parse-csv`, which typed one CELL at a
   time, so a blank cell arrived beside numbers as `\"\"` and `read_json_auto`
   had to widen the column to hold both. `read_csv` sniffs per COLUMN and makes
   that same blank a SQL NULL inside a `DOUBLE` — measured 2026-08-20.

   So the **`JsonNode` branch** below is now reachable only through `put-rows!`,
   which is still every row loci writes out of RAM: `save_table`, `seed!`,
   `tablemigrate`. It is not that this whole function is — `unwrap` runs on
   every value of every row read, `:lake` and `:source` handles alike, and its
   temporal branches exist precisely for a source-connected CSV with a date
   column. `create-from-relation!` issues `CREATE TABLE … AS SELECT … FROM
   read_csv(…)` and never goes near `read_json_auto`, so a CSV connected today
   cannot produce a `JsonNode` at all. Their Parquet was re-read the same day and all four
   columns are still `JSON`; `tbl:extract-20-8413` is inlined in the catalog
   with no Parquet to read, so it is the one not re-measured.

   A `JsonNode`'s `toString` is the JSON text of the value it holds, so parsing
   it back is lossless and none of the three needs refusing: `50` returns as
   `50` and `\"~13% (1 of 8 countries)\"` as that string. Refusing them was the
   remedy Task 9 planned; a storage layer handing its callers values they cannot
   serialize is the actual defect.

   **String keys for a genuinely nested JSON object**, which is `read-str`'s
   default and is deliberate. These keys are values DuckDB parsed out of user
   data, not loci column names — `sub/col-kw` normalizes columns and nothing
   normalizes these — and `:key-fn keyword` on `{\"odd key\": …}` mints `:odd
   key`, a keyword that does not read back. Measured 2026-08-16.

   **Two spellings of \"no value\", one result.** A SQL NULL never reaches here
   at all: JDBC returns Java `null` for it, JsonNode or not. A JSON `null`
   literal parses to `nil`. Both land on `nil`, which is what every other column
   type already gives.

   **Temporal types render through `java.time`, never through `java.sql`.**
   `read_csv` types a date column DATE, and JDBC hands it back as
   `java.time.LocalDate` — a class `clojure.data.json` refuses outright, exactly
   as it refuses `JsonNode`:

     Don't know how to write JSON of class java.time.LocalDate

   `java.sql.Timestamp` is the worse case because it does NOT throw. It
   serializes through the JVM default zone, so `2026-01-02` comes back
   `\"2026-01-01T23:00:00Z\"` — measured 2026-08-19, wrong by a day and silent.
   `.toLocalDateTime` / `.toLocalDate` take the wall-clock value the file
   actually contains and `str` renders it ISO-8601 with no zone applied, which
   is what a CSV cell meant."
  [v]
  (condp instance? v
    org.duckdb.JsonNode      (json/read-str (str v))
    java.time.LocalDate      (str v)
    java.time.LocalDateTime  (str v)
    java.time.LocalTime      (str v)
    java.sql.Timestamp       (str (.toLocalDateTime ^java.sql.Timestamp v))
    java.sql.Date            (str (.toLocalDate ^java.sql.Date v))
    java.sql.Time            (str (.toLocalTime ^java.sql.Time v))
    v))

(defn- rs->maps
  "Drain a ResultSet into row maps, naming column `i` with `(nth ks i)` and
   passing each value through `vf`. The only things `q` and `q-as` disagree
   about are where `ks` comes from and whether values are unwrapped."
  [rs ks vf]
  (loop [acc (transient [])]
    (if (.next rs)
      (recur (conj! acc (into {} (map-indexed (fn [i k] [k (vf (.getObject rs (inc i)))]) ks))))
      (persistent! acc))))

(defn q
  "Execute one query. Returns a vector of maps with keyword columns derived from
   the result set's own labels — which is the right guess when the caller has no
   idea what the columns are. A caller that does know names them itself; see
   `q-as`, and do not add an optional key vector here."
  [lk sql]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (let [md (.getMetaData rs)
          n  (.getColumnCount md)]
      ;; `identity`, not `unwrap`, on scope rather than on need: this is the
      ;; general helper over this namespace's OWN SQL — snapshot ids, table
      ;; names, the CAST-to-VARCHAR `column-samples` runs — and none of those
      ;; result sets has a JSON column in it, so there is nothing to unwrap.
      ;;
      ;; It is NOT that `column-samples` depends on seeing the JSON rendering.
      ;; That was written here and is false: measured 2026-08-17 by installing
      ;; `unwrap` in `q` and re-running the synopsis parity over the live
      ;; tables, the samples came back byte-identical. The quotes that reorder
      ;; them come from `CAST(… AS VARCHAR)`, which happens in SQL before any
      ;; value reaches JDBC.
      (rs->maps rs (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n)) identity))))

(defn- q-as
  "Like `q`, but names the result columns instead of deriving them from the
   result set's labels.

   `q` runs each JDBC label through `sub/col-kw`, which is right when the caller
   has no idea what the columns are. A handle does: it recorded `:cols` when the
   rows were written, and the SELECT names them in that order. Deriving them
   again would be lossy — `col-kw` leaves a readable keyword alone but
   lowercases and squashes a *string*, so `:tenure-band` goes in and
   `:tenure_band` comes back, and `:a-b` and `:a_b` come back as one column.

   It also `unwrap`s, because this is the path a table's ROWS come back on and
   they go straight to `json-resp`."
  [lk sql ks]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (rs->maps rs ks unwrap)))

(def batch-rows
  "Rows handed to `reduce-rows`'s function at a time. **2048 because that is
   DuckDB's vector**, and `setFetchSize` is accepted and silently ignored —
   verified 2026-08-27. Not ours to tune."
  2048)

(defn reduce-rows
  "Fold `f` over every row of `sql`, one batch at a time. `f` is
   `(fn [acc batch] …)` where `batch` is a vector of row maps.

   **Takes the reducing function so it owns the scope.** It does not hand back a
   lazy seq: one escaping `with-open` is the classic Clojure bug, and `q` closes
   its statement on the way out.

   **What this bounds is RETENTION, and not time.** Measured 2026-08-27 over
   1,000,000 rows of four columns: advancing the cursor costs 4,954 ms, reading
   the four columns costs 22,297 ms, and building maps takes it to 27,335 ms —
   so 82% of the cost is the driver and batching saves the other 18%. It does not
   bound allocation pressure either: over 857,142 rows with batches dropped every
   2,048, observed heap still rose to 506 MB between collections.

   Retained heap for a whole 1,000,000-row result was 220 MB — 231 bytes a row —
   so on any modern heap **time is the binding constraint, not memory**.

   DuckDB's JDBC does stream: `executeQuery` over 1,000,000 rows returns in 18 ms
   and the first row in 1 ms.

   `f` is never called with an empty batch, so an empty result returns `init`."
  [lk sql f init]
  (with-open [st (.createStatement (conn-of lk))
              rs (.executeQuery st sql)]
    (let [md (.getMetaData rs)
          n  (.getColumnCount md)
          ks (mapv #(sub/col-kw (.getColumnLabel md (inc %))) (range n))]
      (loop [acc init]
        (let [batch (loop [i 0, b (transient [])]
                      (if (and (< i batch-rows) (.next rs))
                        (recur (inc i)
                               (conj! b (into {} (map-indexed
                                                   (fn [idx k] [k (unwrap (.getObject rs (inc idx)))])
                                                   ks))))
                        (persistent! b)))]
          (if (seq batch)
            (recur (f acc batch))
            acc))))))

(defn- extension-dir
  "Where DuckDB should keep its extensions, or nil to leave DuckDB's own default
   alone — see `open-lake!` for why loci has to be able to say this at all.

   Read through `loci.config/env`, not `System/getenv`, so `loci.env` sets it the
   same way it sets everything else and a non-Docker deployment has one place to
   write it."
  []
  (config/env "LOCI_DUCKDB_EXTENSIONS"))

(defn- extension-problem
  "The mechanism paragraph — the directory, the uid, DuckDB's own message and
   the fix — between a `head` and a `tail` the CALLER composes.

   Without it the reader gets DuckDB's own error, which names neither loci nor
   anything actionable:

     IO Error: Failed to create directory \"/.duckdb\": Permission denied

   That is the failure this whole mechanism exists for, and it killed the
   container before the listener. `/.duckdb` is the filesystem root because
   DuckDB composes its extension directory from $HOME and the runtime image sets
   none — see `open-lake!`.

   **Everything between `head` and `tail` is about the extension MECHANISM**,
   and is exactly as true for any extension as it is for `ducklake`. What is not
   shared is what the failure COSTS, which only the caller knows: `open-lake!`
   is failing at boot with no lake, and `ensure-remote!` is refusing one source
   while the store stays open. Both are sentences about a consequence, and a
   consequence is not derivable from an extension name.

   **It used to be derived from one anyway, and that is why this signature
   changed.** `required? (= \"ducklake\" ext)` gated ducklake's two sentences,
   and the other caller then wrapped this whole message in a SECOND composer to
   add its own — which rendered two headlines for one failure, `\"loci cannot
   read <uri> — DuckDB's httpfs extension is not available.\"` immediately
   above `\"loci cannot load DuckDB's httpfs extension.\"`, measured
   2026-08-24. That wrapper also had to `str/trimr` a newline the flag left
   behind and read the extension name back off the cause's `ex-data`. All of it
   was one concern implemented twice.

   The ducklake message is byte-identical to the one measured before this
   argument existed; `the-ducklake-paragraph-did-not-move` pins it."
  [head ^Throwable cause tail]
  (let [dir  (extension-dir)
        home (System/getenv "HOME")]
    (str head "\n"
         "  looking in " (cond dir  (str dir " (LOCI_DUCKDB_EXTENSIONS)")
                               home (str home "/.duckdb — DuckDB's default, from HOME")
                               :else "/.duckdb — DuckDB's default, and HOME is unset")
         "\n"
         "  running as " (sub/running-as) "\n"
         "  DuckDB said: " (.getMessage cause) "\n"
         "\n"
         "  Set LOCI_DUCKDB_EXTENSIONS to a directory holding the extension.\n"
         "  A container should set it at build time and install the extension\n"
         "  into the image then: INSTALL is the one step here that reaches the\n"
         "  network, and the directory it writes need never be writable again —\n"
         "  LOAD only reads.\n"
         tail)))

(defn- ensure-extension!
  "LOAD `ext` first, INSTALL only if that fails. **Do not delete this.** It
   looks dead on any machine that has already run loci, and it is the only
   reason a machine that has not can open a lake at all.

   Measured twice on DuckDB 1.5.5 (2026-08-16), because the first measurement
   supported the opposite conclusion and was incomplete.

   What is true, and is what makes this look dead: `ATTACH 'ducklake:…'` LOADS
   the extension by itself. `duckdb_extensions()` reports ducklake unloaded
   immediately before an ATTACH and loaded immediately after, on a connection
   that issued neither LOAD nor INSTALL, and it does this even with
   `autoload_known_extensions` and `autoinstall_known_extensions` both SET false.
   So breaking this function changes nothing observable here, and the sabotage
   step that should have proven the tests bite could not.

   What is also true, and is why it stays: **ATTACH loads, but it does not
   install.** Pointed at an empty `extension_directory` — a machine that has
   never seen the extension — ATTACH fails outright with
   `IO Error: Extension \"…/ducklake.duckdb_extension\" not found`, and the
   catalog never opens. Running this function's own sequence by hand in that
   same state gives: LOAD fails, INSTALL succeeds, LOAD succeeds — and the
   ATTACH that follows then succeeds.

   So this is a guard after all, and the thing it guards is first run. The
   INSTALL is the one line here that can reach the network — and **the image no
   longer reaches it at all**: since 2026-08-18 the Dockerfile installs into
   `/opt/duckdb-extensions` in the build stage and sets
   `LOCI_DUCKDB_EXTENSIONS`, so in a container LOAD succeeds on the first try
   and this fallback never fires. Verified with `docker run --network none`.

   **`ducklake`, `httpfs` and `aws`**, as of 2026-08-23 — the last two added
   the day `ensure-remote!` landed, because until then an `httpfs` LOAD in a
   container fell through to INSTALL and died under `--network none`, refusing
   a remote source with a download error. Whoever adds a line to that `RUN`
   should still add its name here. The three cost about 86 MB of a 299 MB image
   between them, which is the largest thing it spends after the JDK runtime and
   the jar. The per-extension bytes and the build-to-build delta are in the
   Dockerfile beside the `COPY` that ships them, because that is where the
   decision to pay it is made, and in the spec's Measured section. Written down
   rather than argued from: nobody has complained about the size of this image.

   It stays for the machine that is not a container: a `clojure -M:serve` on a
   laptop that has never opened a lake still needs it, and there this branch is
   the whole reason that works.

   **`ext` is loci's own literal and never anything a person typed**, and that
   is the whole of what makes interpolating it bare into the SQL right. There
   is a closed set of callers — `open-lake!` and `ensure-remote!`, both passing
   a hard-coded string — and nothing derives this argument from anything.

   An earlier draft gave a second reason and it was **false**, so it is written
   down here rather than deleted: *\"LOAD and INSTALL take a bare extension
   name, not a string, so there is nothing to quote\"*. Measured 2026-08-23 on
   DuckDB 1.5.5 — `LOAD 'ducklake'` is accepted and indistinguishable from
   `LOAD ducklake`, so quoting is available; and `.execute` runs `;`-separated
   statements, so `LOAD ducklake; CREATE TABLE pwned(i INT); INSERT INTO pwned
   VALUES (42)` ran all three and the table held 42. This is a textbook
   injection seam and the caller set is the only thing closing it. Do **not**
   add validation or quoting for a case nobody has — but if you ever compute
   `ext` from a scheme→extension map keyed on a uri someone typed, this
   paragraph is the one that should stop you.

   It is also what the message names, which is the point of the argument rather
   than a side effect of it. Everything measured above is about `ducklake`,
   whose failure means the store cannot open; a caller whose failure means
   something narrower gets a sentence about the extension IT asked for, and
   `extension-problem` composes only the consequence it can justify.

   **`head` and `tail` are the caller's, not this function's.** What a missing
   extension costs is not derivable from its name — `open-lake!` has no lake and
   is failing at boot, `ensure-remote!` is refusing one source while everything
   else keeps working — so each caller composes the sentences it can justify and
   this composes the paragraph they share. `ext` is still interpolated into both
   heads at the call site, so neither can name an extension other than the one
   that failed."
  [lk ext head tail]
  (try (x! lk (str "LOAD " ext))
       (catch Exception _
         (try (x! lk (str "INSTALL " ext))
              (x! lk (str "LOAD " ext))
              (catch Exception e
                (throw (ex-info (extension-problem head e tail)
                                {:dir (extension-dir) :extension ext} e)))))))

;; `ensured` holds the set of one-per-connection preparations already done —
;; `#{:httpfs :s3}`, see `ensure-remote!`. An atom ON THE RECORD rather than a
;; memo keyed by the connection, because the fact it records is a fact about
;; the connection and dies with it: `sample-cache` below is the memo shape, and
;; its own docstring records the cost — "the key holds the `Lake` it was asked
;; about, so an entry pins a JDBC connection", which a test run that opens many
;; lakes leaks one of each. That is a price worth paying for samples and not
;; for a boolean. It is also why this is not a global atom: `with-lake` makes a
;; fresh lake per test, and a global one would leak across them and make
;; "issued once" depend on test order.
(defrecord Lake [conn dir ensured])

(defn open-lake!
  "Open (or create) the DuckLake catalog in `dir`. Defaults to <data-dir>/lake.

   **`OVERRIDE_DATA_PATH` is what makes the lake relocatable, and it is not
   optional.** DuckLake writes DATA_PATH into the catalog when the lake is
   created and compares it on every later attach; without the override, a lake
   opened from anywhere but the directory it was created in fails at boot with

     Invalid Configuration Error: DATA_PATH parameter \"/tmp/x/lake/files/\"
     does not match existing data path in the catalog \"data/lake/files/\".

   which breaks copying `data/`, restoring a backup, running the uberjar from
   another working directory, and mounting a locally-created `data/` into a
   container. loci owns the whole lake directory and the files are always at
   `<dir>/files/`, so the catalog's remembered path is never the authority —
   `dir` is.

   Measured 2026-08-17 on DuckDB 1.5.5, because \"override\" could mean several
   things and only one of them is safe:

   - It is a **per-session re-root, not a rewrite.** After attaching a moved
     lake with it, `ducklake_options('lake')` still reports the path the lake
     was created at. Nothing in the catalog is modified, so an override that
     turns out to be wrong is one attach, not a migration.
   - It is **total**, because every path under the catalog is stored relative:
     `ducklake_table.path` is `t_tbl_x/` and `ducklake_data_file.path` is the
     bare `ducklake-….parquet`. A 5,000-row table read back all 5,000 rows from
     a moved directory, and a write *after* the move landed its Parquet under
     the new root, not the old one.
   - It is **quiet** — no SQLWarning on the connection — and it is accepted on a
     brand-new catalog too, which still records the DATA_PATH it was given.

   The canonical path is the other half, and on its own it would be a
   regression rather than a fix: the live `data/lake` was created before this
   and has the relative `data/lake/files/` baked in, so canonicalizing without
   the override *refuses a store that used to open*. Measured — that is exactly
   the error above. What canonicalizing buys is that a new catalog records
   where it actually is, so `data_path` means something to someone reading the
   catalog with the duckdb CLI, instead of a bare `data/` that names a
   different directory from every working directory but one.

   **`extension_directory` is the other thing this connection has to be told,
   and `HOME` is not a substitute for it.** DuckDB composes that directory from
   `$HOME`; the runtime image sets no HOME — `USER` does not set one and
   `debian:bookworm-slim` does not either — so `getenv(\"HOME\")` is null, the
   path becomes `/.duckdb`, and `docker compose up` died with

     IO Error: Failed to create directory \"/.duckdb\": Permission denied

   before the listener ever started.

   `ENV HOME=/home/loci` in the image looks like the fix and covers only half of
   how loci is run. `compose.yaml` sets `user: \"${LOCI_UID:-10001}:…\"` and tells
   you to put your own uid there, because the substrate is a bind mount of a
   directory you own — so loci commonly runs as a uid with no `/etc/passwd` entry
   in the container and no claim on `/home/loci`. Anything resolved through HOME
   is right for the named-volume path and wrong for the bind-mount one. Naming
   the directory outright is right for both, and it is also what lets the image
   ship the extension read-only: a writable extension directory would let a
   version mismatch quietly re-download 36 MB at boot instead of failing.

   Unset leaves DuckDB's default alone, which is what a developer on a laptop
   with a real HOME wants."
  ([] (open-lake! (str (sub/data-dir) "/lake")))
  ([dir]
   (io/make-parents (io/file dir "catalog.duckdb"))
   ;; The driver is instantiated directly rather than reached through
   ;; DriverManager: DriverManager cannot see a driver loaded by a child
   ;; classloader, which is how this namespace gets exercised from a REPL that
   ;; added the dep with `add-lib`. Direct instantiation works in both.
   (let [dir   (.getCanonicalPath (io/file dir))
         props (java.util.Properties.)
         _     (when-let [ext (extension-dir)]
                 (.setProperty props "extension_directory" ext))
         conn  (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" props)
         lk    (->Lake conn dir (atom #{}))]
     (try
       (ensure-extension! lk "ducklake"
                          "loci cannot load DuckDB's ducklake extension, so the lake cannot open."
                          "\nFailing here rather than at the first table read.")
       (x! lk (str "ATTACH 'ducklake:" dir "/catalog.duckdb' AS lake"
                   " (DATA_PATH '" dir "/files/', OVERRIDE_DATA_PATH true)"))
       (x! lk "USE lake")
       lk
       (catch Throwable t (.close conn) (throw t))))))

(def ^:private s3-prefix
  "The one remote scheme that costs a trip through the AWS credential chain.

   Deliberately not `ssp/remote?` and not a copy of `ssp/remote-schemes`. Those
   answer *does DuckDB fetch this*, and every scheme in that set answers yes;
   the question here is *does this location need a credential*, which `http://`
   and `https://` answer no to — so the two predicates staying apart is the
   point rather than a duplication to be tidied away.

   **The scheme is the secret's own SCOPE, and that is the argument — the cost
   only says how much it hurts.** An s3 secret created without an explicit
   SCOPE takes the default `[s3://, s3n://, s3a://]`; measured 2026-08-23 on
   DuckDB 1.5.5, `duckdb_secrets()` reports exactly that, and
   `which_secret('https://h/x.csv', 's3')` returns **zero rows** against it
   while `which_secret('s3://bucket/x.csv', 's3')` returns the secret. So the
   secret `ensure-remote!` issues *cannot apply* to an `https://` url at all.
   Widening this prefix would spend the six seconds measured there — on every
   plaintext read, on every machine without AWS credentials — to obtain a
   credential DuckDB would then decline to consult.

   That also answers the S3-compatible question rather than deferring it. MinIO
   and R2 are reached as `https://minio.internal/bucket/x.csv`, so they get
   httpfs and no credential, and they could not have used one from this secret
   even if it were issued: an endpoint like that needs its own secret carrying
   an ENDPOINT and an explicit SCOPE, which is an operator's `CREATE PERSISTENT
   SECRET` and is the escape hatch `ensure-remote!` now checks for and leaves
   alone.

   It is a prefix and not a set because there is one of it. `gs://` and `az://`
   are not here for the same reason they are not in `remote-schemes`: they
   arrive the day someone has one, and each brings its own secret type."
  "s3://")

(defn- credential-problem
  "One paragraph naming the location, DuckDB's own error and the fix —
   `extension-problem`'s job, for the other thing a remote read needs.

   Without it the reader gets DuckDB's, which does not say the word
   credentials, names no variable and offers nothing to do — measured
   2026-08-23 on DuckDB 1.5.5:

     Invalid Configuration Error: Secret Validation Failure: during `create`
     using the following:
     Credential Chain: 'config'

   `'config'` there is the CHAIN's name — the AWS SDK's default provider chain
   — and not a file's, so a reader who goes looking for a config loci wrote is
   looking for something that does not exist. That is the sentence this
   replaces, and DuckDB's own text is kept below it rather than hidden, the
   same way `extension-problem` keeps it.

   It says what loci will NOT do, because that is a decision and a reader
   deserves to see it as one rather than as a missing feature: there is no
   paste-a-key box and no `secrets.edn`. loci has nowhere to put a credential —
   the substrate is an append-only log, so a secret written into it would be
   recoverable forever by design.

   And it names the way out that does exist, which is the one thing the
   previous text left the reader to guess at. Everything it listed —
   environment variables, `~/.aws/credentials`, an instance profile, IRSA — is
   an input to the chain, so a reader whose chain cannot be made to work read a
   paragraph with no option in it. `CREATE PERSISTENT SECRET` is DuckDB's own
   answer and `ensure-remote!` now honours it, so it belongs here: it is the
   difference between \"fix your environment\" and \"here is a thing you can
   do\"."
  [uri ^Throwable cause]
  (str "loci cannot get AWS credentials for " uri ".\n"
       "  running as " (sub/running-as) "\n"
       "  DuckDB said: " (.getMessage cause) "\n"
       "\n"
       "  loci stores no credentials and has nowhere to put one. It asks DuckDB's\n"
       "  credential chain, which reads AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY,\n"
       "  ~/.aws/credentials, an EC2 instance profile, or an IRSA role. Give the\n"
       "  loci process one of those and connect the source again.\n"
       "\n"
       "  Or give DuckDB the secret directly and loci will not ask the chain at\n"
       "  all: in the duckdb CLI, CREATE PERSISTENT SECRET (TYPE s3, KEY_ID '…',\n"
       "  SECRET '…') writes it under ~/.duckdb, and loci checks for a secret\n"
       "  covering the location before it issues its own. That one is DuckDB's to\n"
       "  store, not loci's.\n"
       "\n"
       "  Only s3:// needs this — an http:// or https:// location is fetched with\n"
       "  no credential and never reaches here."))

(defn- secret-covers?
  "Does a secret DuckDB already holds apply to `uri`? If so `ensure-remote!`
   issues none, and the operator's is the one that gets used.

   **This is what makes the escape hatch the spec promises actually work.** The
   spec says a bucket the chain cannot reach \"fails with an auth error, and the
   user fixes it outside loci\", and DuckDB's documented way to do that is
   `CREATE PERSISTENT SECRET`. Without this check the fix does not take — two
   measurements from 2026-08-23 on DuckDB 1.5.5, and what they leave the
   operator holding:

   - a persistent `loci_s3` and a temporary one **coexist with no error**, and
     at equal scope the TEMPORARY wins: `which_secret` named the operator's
     secret, then named loci's the moment it existed. So loci silently
     overrode a working credential with one from a chain that had already
     failed.
   - worse on the machine that actually needs the hatch. `CREATE SECRET …
     PROVIDER credential_chain` throws when the chain is empty, so the source
     is refused **before any read**, with the operator's working keys sitting
     unused in `~/.duckdb/stored_secrets`.
   - `credential-problem` listed environment variables, `~/.aws/credentials`,
     an instance profile and IRSA — every one of them an input to the chain,
     and not one of them the thing that was already set up.

   **Cheap enough that it is not a trade.** ~2 ms against the 6,000 ms
   `CREATE SECRET` it can skip, and it needs neither `httpfs` nor `aws`:
   measured on a bare connection with httpfs `loaded false`, `which_secret`
   answered. So it also removes the six seconds on a machine that has a secret,
   which is the deployment where a lake gets connected most often.

   **12.7 ms was the number here until 2026-08-23 and it was a first call.**
   Re-measured in a cold JVM: 5.8 and 5.3 ms for the first `which_secret` of a
   process, 2.1 and 1.9 ms after; warm, five calls gave 2.3 / 7.4 / 2.1 / 2.4 /
   2.6 ms. Corrected rather than left, because a reader deciding whether to add
   a cache in front of this should see the real figure — there is nothing here
   to cache.

   `which_secret` and not `duckdb_secrets()` because the question is about THIS
   uri, not about the secret table: it takes the path and the type and returns
   the secret that would be consulted for it, scope matching included — an
   operator's secret scoped to one bucket answers for that bucket and not for
   another. Zero rows means nothing covers it, measured rather than assumed.

   The uri is escaped into the literal and that is not decoration. Unlike
   `ensure-extension!`'s `ext`, which is loci's own literal and whose docstring
   argues at length that a closed caller set is what closes the seam, this
   argument is a location a person typed into the ＋ Data panel."
  [lk uri]
  (boolean (seq (q lk (str "SELECT name FROM which_secret('"
                           (str/replace (str uri) "'" "''")
                           "', 's3')")))))

(defn ensure-remote!
  "Make this connection able to read `uri`, once per connection: load `httpfs`,
   and for an `s3://` location issue the credential secret unless a secret
   DuckDB already holds covers it. Returns :ok.

   **Precondition: `uri` is one `ssp/remote?` answers true to.** The LOAD below
   is unconditional, so calling this for a `loci://` or `file://` location
   issues `LOAD httpfs` for a file on local disk — and on a machine without the
   extension that falls through to `ensure-extension!`'s `INSTALL`, the one
   statement here that reaches the network, which under `--network none` dies
   and refuses a local CSV with a sentence about httpfs. The only caller,
   `loci.source/describe`, holds the guard and says why; a second caller must
   too. It is not asserted here because `describe` is where the scheme is
   already known and where a reader of the call needs to see it.

   **Called on the first remote read, not from `open-lake!`.** `open-lake!`
   calls `ensure-extension!` inside a `try` that closes the connection and
   rethrows, which is right for `ducklake` — no extension, no lake, and loci
   should say so at boot. It would be wrong here: a loci that never touches a
   remote source must not fail to start because an extension it does not need
   is missing. So this fails one source at connect time, not the store at boot
   — and says so: `extension-problem` composes the directory, the uid and the
   fix but cannot know the consequence, so this caller hands `ensure-extension!`
   the head and tail only it can compose: which source cannot be read, and that
   nothing else is affected.

   **The secret is `PROVIDER credential_chain`, so loci hands DuckDB a provider
   and never a key.** The AWS SDK chain reads environment variables,
   `~/.aws/credentials`, an EC2 instance profile or IRSA. Nothing is stored,
   nothing is committed, and `undo!` cannot resurrect a credential because none
   was ever written — which matters here more than anywhere else in loci, since
   the substrate is an append-only log and a secret in it would be recoverable
   forever by design. The secret is also TEMPORARY, which is DuckDB's default
   and is kept: a `PERSISTENT` one is written unencrypted under `~/.duckdb`, so
   the one word would move loci from storing no credential to storing one.

   **Only `s3://` gets the secret, and that is measured rather than tidy.**
   `CREATE SECRET … PROVIDER credential_chain` is validated when it is
   *created*: DuckDB's `aws` extension walks the whole chain then and there,
   and on a machine the chain cannot satisfy it does not hand back an unused
   secret, it throws. Measured 2026-08-23 on DuckDB 1.5.5 with no AWS
   credentials present: **10.5 s** the first time and **6.0 s** every time
   after. Issuing it for
   every remote scheme would therefore put six seconds and a hard failure in
   front of every `https://` read on every machine without AWS credentials —
   most laptops, and the whole test suite — to obtain a credential that scheme
   never uses. See `s3-prefix`.

   **The first time is not the autoinstall, corrected 2026-08-23 after
   re-measuring.** The sentence here used to say the extra four seconds were
   `aws` arriving over the network. They are not: with `aws` already installed
   AND explicitly `LOAD`ed first, a cold JVM still paid 10,022 ms on its first
   CREATE and 6,018 ms on its second, twice, while the `LOAD` itself cost
   46 ms. It is a once-per-process cost inside the chain's first walk — a warm
   process that had already paid it opened a brand-new lake connection and got
   6,028 ms on that connection's first CREATE. So an **offline image pays the
   four seconds too**, which is the opposite of what the autoinstall story
   predicts, and the reason to correct it rather than round it away.

   That measurement also said what the `aws` extension costs, which is a fact
   about the image rather than about this function: `credential_chain` needs
   `aws` as well as `httpfs`, and on a laptop it arrives by autoinstall over
   the network. **The image ships both as of 2026-08-23** — `ensure-extension!`
   records what they weigh — and `docker run --network none` connecting an
   `s3://` source is the control that proves it rather than a claim: *before*,
   it died at 509 ms with `IO Error: Failed to download extension \"httpfs\"`
   and never reached the secret at all; *after*, it reaches the secret and
   fails at **62 ms** with `credential-problem`.

   Keep those 62 ms beside the 6.0 s above, because the two read as a
   contradiction and are not. With no route out the AWS chain gives up at once;
   the six seconds on a networked host are spent waiting on IMDS. **Fast here
   means unreachable, not configured** — an operator timing a container against
   this docstring should find that here rather than conclude one of the two
   numbers is wrong.

   **The LOAD looks dead, and against a networked machine it is.** loci sets
   neither `autoload_known_extensions` nor `autoinstall_known_extensions`, and
   `duckdb_settings()` reports both `true` on a lake this file opened — so a
   read loads httpfs by itself. Measured 2026-08-23 against a loopback port
   nothing is listening on, which keeps it off the network: a fresh lake
   reports httpfs `loaded false`, `read_csv('https://127.0.0.1:1/x.csv')`
   fails on the connection, and httpfs is `loaded true` afterwards with no
   LOAD from loci at all. That is the same trap `ensure-extension!`'s own
   docstring records for `ducklake` and ATTACH, where it defeated the sabotage
   that should have proven the tests bite. Two things autoload does not cover.
   The **secret** is one: no read triggers a `CREATE SECRET`, so without this an
   `s3://` read goes out anonymous and comes back 403. The other is **where the
   failure lands** — an autoload that cannot reach the network fails inside a
   read with DuckDB's own message, where this fails at connect with
   `extension-problem`'s paragraph naming the directory, the uid and the fix,
   which is the entire reason that function exists.

   **An operator's own secret is left alone.** `secret-covers?` asks
   `which_secret` before anything is created, and a `CREATE PERSISTENT SECRET`
   the operator made outside loci therefore wins instead of being silently
   shadowed by a temporary one — which is what happened before, measured, and
   which broke the escape hatch the spec promises for a bucket the chain cannot
   reach. See `secret-covers?` for the three measurements and for why the check
   is not a trade — it costs about 2 ms against the 6,000 ms it can skip.

   **A failure is not remembered.** The flag moves only after the statement
   returns, so a machine that grows credentials, or an extension directory that
   grows the extension, is one reconnect away rather than one restart. The
   guard is not decoration either: `CREATE SECRET` without `OR REPLACE` refuses
   a name that already exists, so dropping it would make the second remote read
   of a connection throw.

   **The guard and the statement are one critical section, on the `ensured`
   atom's own monitor.** Read-then-write across a six-second statement is a
   race with an observable wrong answer, not a theoretical one: two threads
   both find `:s3` absent, both issue the CREATE, and the loser gets
   `Invalid Input Error: Temporary secret with name 'loci_s3' already exists!`
   — measured 2026-08-23 on DuckDB 1.5.5 — which the `catch` below dresses as
   `credential-problem`, telling a machine whose credentials are **fine** to go
   set AWS_ACCESS_KEY_ID. It is reachable on the real paths: `describe` is
   deliberately outside `loci.source/connect-lock` and `refresh!` takes no lock
   at all, both on http-kit worker threads, so two tabs connecting `s3://`
   sources — or a connect racing a refresh — inside the first six seconds of s3
   use is the whole of what it takes.

   Serialising is the fix and `CREATE SECRET IF NOT EXISTS` is not: measured,
   it still walks the chain (6,079 ms) before it looks at the name, so the
   loser pays the six seconds instead of throwing. Under the monitor the loser
   waits, then finds the guard set and does nothing.

   The monitor is the `ensured` atom because its lifetime is exactly right —
   per connection, born with the record, dying with it — and a new field would
   be a second object saying the same thing. It is a **leaf**: nothing called
   from inside it re-enters this namespace, and `connect-lock` is never held
   over a call to here, since `describe` is outside it and `land!` relies on
   the preparation `describe` already did rather than repeating it. So there is
   no lock order to get wrong.

   **A `credential_chain` secret resolves ONCE, at CREATE — write the number
   down and build nothing.** That is what the throws-on-create measurement
   above proves. `lake` is a `defonce` delay, one connection per process, so
   `:s3` is set at most once ever; on the deployments named above — an EC2
   instance profile, IRSA — the chain hands out a session token that typically
   expires in an hour, after which every `s3://` read 403s and nothing here
   re-issues. The platform has the facility if it is ever wanted: `REFRESH
   auto` **parses on 1.5.5**, measured — `CREATE SECRET chk (TYPE s3, PROVIDER
   credential_chain, REFRESH auto)` failed on chain validation at 6,179 ms, not
   on a parse or binder error. Nobody has hit this, so it is one word in a
   docstring and not a mechanism; the day someone does, that word is the fix
   and a restart is the workaround."
  [lk uri]
  (let [ensured (:ensured lk)]
    (locking ensured
      (when-not (contains? @ensured :httpfs)
        (try (ensure-extension!
              lk "httpfs"
              (str "loci cannot read " uri " — DuckDB's httpfs extension is not available.")
              (str "\n"
                   "  This source cannot be read; nothing else is affected. The extension is\n"
                   "  loaded only for a remote location, so loci is running, the store is open,\n"
                   "  every loci:// and file:// source still reads, and every table already in\n"
                   "  the lake is unchanged. Connect this one again once the extension is there."))
             (catch clojure.lang.ExceptionInfo e
               (throw (ex-info (.getMessage e) (assoc (ex-data e) :uri uri) e))))
        (swap! ensured conj :httpfs))
      (when (and (str/starts-with? (str uri) s3-prefix)
                 (not (contains? @ensured :s3)))
        (when-not (secret-covers? lk uri)
          (try (x! lk "CREATE SECRET loci_s3 (TYPE s3, PROVIDER credential_chain)")
               (catch Exception e
                 (throw (ex-info (credential-problem uri e) {:uri uri} e)))))
        (swap! ensured conj :s3))))
  :ok)

(defn close! [lk] (when-let [c (conn-of lk)] (.close c)))

(defonce ^{:doc "The process's one lake at <data-dir>/lake.

   A delay, so requiring this namespace still opens nothing — the rule the
   whole codebase keeps, and the reason `loci.dlv/store` is shaped the same
   way. Nothing rebinds this: the tests never touch it, because `open-lake!`
   takes a directory and every test passes its own.

   **DuckDB refuses a second attach of one catalog file, process-wide**, the
   same way Datalevin refuses a second connection to one directory. Measured
   2026-08-16 — a second `open-lake!` on a directory this JVM already holds
   fails with

     Binder Error: Failed to attach DuckLake MetaData
     \"__ducklake_metadata_lake\" at path + \"…/catalog.duckdb\"Unique file handle
     conflict: Cannot attach \"__ducklake_metadata_lake\" - the database file
     \"…/catalog.duckdb\" is already attached by database
     \"__ducklake_metadata_lake\"

   which is why the laziness matters more here than it does for chunks or the
   memory: forcing this delay in a process that already holds the lake is not
   an unwanted file, it is a hard failure. So there is one handle and everyone
   shares it — which loci can do because it is single-writer."}
  lake
  (delay (open-lake!)))

(defn- table-name
  "A loci object id → a lake table name that is a legal SQL identifier and
   unique per call.

   Two parts on purpose. The sanitised id keeps the name debuggable — you can
   see which object `t_tbl_orders_1a2b3c4d` belongs to from a directory
   listing. The suffix makes it unique, because sanitising is lossy
   (`tbl:a-b` and `tbl:a_b` collide) and because writes never overwrite: the
   same object id written twice must land in two tables."
  [id n]
  (let [safe (-> (str id)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+|_+$" ""))
        h    (-> (java.security.MessageDigest/getInstance "SHA-256")
                 (.digest (.getBytes (str id "#" n) "UTF-8")))]
    (str "t_" (if (str/blank? safe) "tbl" safe) "_"
         (apply str (map #(format "%02x" %) (take 4 h))))))

(defn- ident
  "A column name as a quoted SQL identifier. Quoted, not interpolated bare:
   column names arrive from CSV headers and from models, and `sub/col-kw` has
   already made them safe for the LOG without making them safe for SQL."
  [k]
  (str "\"" (str/replace (name k) "\"" "\"\"") "\""))

(defn- select-cols [cols] (str/join ", " (map ident cols)))

(defn- tiebreak-order
  "ORDER BY terms for every column in `proj` other than `sort-col` — a total
   order on the row's *values*, for a handle with no `rowid` to fall back on.

   Two rows identical in every projected column are indistinguishable to any
   caller, so this is the widest tiebreaker that can matter: once it is
   exhausted, the multiset a paged walk returns is correct even though no two
   rows can be told apart by position. Cost is unmeasured — a wider sort key
   makes each comparison do more work, though it does not change the
   algorithmic class, since the sort already dominates the query either way.

   Excludes `sort-col` itself so it is not named twice in one ORDER BY — the
   second mention would be dead weight, since the first already resolves every
   tie it could break.

   `NULLS LAST` on each term explicitly, even though a plain `ASC`/`DESC`
   already measured as NULLS-last by default here (DuckDB's own default —
   `(VALUES (1),(NULL),(3),(2))` ordered `ASC` came back `1 2 3 nil`, and
   `DESC` came back `3 2 1 nil`, both nulls-last with no clause at all).
   Written out anyway because the guarantee this function exists for should
   not depend on a server default nobody here controls."
  [proj sort-col]
  (->> proj
       (remove #(= (name sort-col) (name %)))
       (map #(str ", " (ident %) " ASC NULLS LAST"))
       (apply str)))

(defn- source-handle?
  "A `:source` handle names a `read_csv`/passthrough relation rather than a
   materialised table — no `rowid`, no key of any kind. One predicate rather
   than `(= :source (:store h))` at one site and `(not= …)` at another: the
   two used to disagree in *form* (a double negative in `page`'s `seek?` and
   its mirror in the tiebreak dispatch), which is the kind of thing a later
   edit inverts by accident."
  [h]
  (= :source (:store h)))

(defn- sorted-order-by
  "The ` ORDER BY …` clause for a sorted page, including the tiebreaker that
   makes the order total.

   Needed because `ORDER BY <col> … LIMIT … OFFSET …` is not guaranteed
   stable across separate queries that differ only in OFFSET once the sort
   column ties — DuckDB's own top-N pushdown can resolve a tie differently
   depending on how many rows it has to materialise, so two pages of one
   query can both return a row and both skip another. Checked for ASC and
   DESC, and for both handle kinds — see §2.3.1 of
   `docs/superpowers/specs/2026-08-25-paged-table-grid-design.md` for the
   measurements this rests on: the size of the failure on the unsabotaged
   code, and the size of the fix below.

   `:lake` gets `, rowid ASC` — a unique key, so the order is total outright.
   `:source` has no rowid, so the fallback is `tiebreak-order`: every OTHER
   projected column, appended to the ORDER BY. Two rows tied in every
   projected column are what a caller can no longer tell apart anyway, so
   exhausting the projection is a total order on everything that could
   matter — see `tiebreak-order`'s own docstring for the NULLS-LAST and cost
   notes."
  [h proj srt]
  (str " ORDER BY " (ident (first srt))
       (if (= "desc" (second srt)) " DESC" " ASC")
       " NULLS LAST"
       (if (source-handle? h)
         (tiebreak-order proj (first srt))
         ", rowid ASC")))

(defn- current-snapshot
  "The snapshot id the lake's write head is at.

   Catalog-wide, not per-table: a DuckLake snapshot is one transaction against
   the whole lake, so this is the max over every table, and two `put-rows!`
   running concurrently can hand the first one a snapshot the second minted.

   Measured 2026-08-16, and benign **only because writes never overwrite**: a
   table read at a snapshot later than the one that created it returns exactly
   the same rows, because nothing between the two touched it. Interleaved
   `tbl:a`/`tbl:b` writes, then reading `a` at `b`'s snapshot, gave `[{:v 1}]`
   either way.

   That stops being true the day a table is mutated in place — a handle stamped
   with a later snapshot would then read another transaction's edits as its own.
   Whoever adds in-place mutation has to stamp the snapshot the write itself
   produced rather than the catalog's high-water mark. See `restore-to!`, which
   is the other half of that same day's work."
  [lk]
  (:snapshot_id (first (q lk "SELECT max(snapshot_id) AS snapshot_id FROM ducklake_snapshots('lake')"))))

(defn restore-to!
  "Move `table`'s write head back to `snapshot`.

   Measured 2026-08-16, and this is the finding that most changed the design:
   DuckLake's `AT (VERSION => n)` is a READ, and a read does not move the write
   head. A substrate that merely re-points its handle after `undo!` diverges
   from the lake silently — the next write builds on the state the undo was
   supposed to remove, and the undone change comes back.

   There is no `ducklake_rollback`; all 26 `ducklake_*` functions were checked.
   A CREATE OR REPLACE from the old version is the native way to say it, and it
   is also the fast way: 182 ms at 10M rows against 520 ms for inverting the
   native change feed, and flat in the size of the edit (a 50% edit restored in
   211/245/260 ms across three runs). The change-feed approach was measured,
   was slower, had four change types to get right, and is not here.

   **Unconditional — do not call it speculatively.** It rewrites the table
   whether or not the version asked for is the one already current, and each
   rewrite is a snapshot and a Parquet file. Measured: three restores to the
   current version took the catalog from 2 snapshots and 1 file to 5 and 4, for
   one 1000-row table. The files staying is correct — older versions still read
   through them — so the caller is the one that has to know it needs this."
  [lk table snapshot]
  (x! lk (str "CREATE OR REPLACE TABLE " table
              " AS SELECT * FROM " table " AT (VERSION => " (long snapshot) ")"))
  :ok)

(defn live-tables
  "Every table the lake currently holds, with its file count and bytes.

   One query for the whole catalog — a report over 31 tables must not be 31
   round trips. A **dropped** table is absent from this, which is what makes
   `dropped-at` a question worth asking separately. An **inlined** table has
   `file_count` 0: DuckLake keeps small tables inside `catalog.duckdb` rather
   than as Parquet, so there is no file to delete and nothing to reclaim.

   **The bytes are every `table_id` that ever bore the name, not just the live
   one** — which is why this counts `ducklake_data_file` itself instead of
   asking `ducklake_table_info('lake')`. That function reports the live
   `table_id` only, and `restore-to!` is a `CREATE OR REPLACE` that leaves a
   tombstoned sibling's Parquet in the same directory. Measured 2026-08-17 on a
   restored 100-row table: `ducklake_table_info` said `file_count` 1 and 906
   bytes with **1812** bytes on disk.

   The half of that which matters is that `loci.lakegc/reclaim!` deletes what
   `table-files` finds, and `report` has to predict the same number, so both
   have to be counting the same files. `table_id` stays in the shape and is the
   live one; the whole point is that it is no longer the only one.

   Restricted to names with a live catalog row, so a reclaimed table stays
   absent even though its metadata rows survive the drop."
  [lk]
  (q lk (str "SELECT t.table_name AS table_name,"
             "       max(t.table_id) AS table_id,"
             "       count(df.data_file_id) AS file_count,"
             "       coalesce(sum(df.file_size_bytes), 0) AS file_size_bytes"
             "  FROM __ducklake_metadata_lake.main.ducklake_table t"
             "  LEFT JOIN __ducklake_metadata_lake.main.ducklake_data_file df"
             "    ON df.table_id = t.table_id"
             " WHERE EXISTS (SELECT 1 FROM __ducklake_metadata_lake.main.ducklake_table live"
             "                WHERE live.table_name = t.table_name"
             "                  AND live.end_snapshot IS NULL)"
             " GROUP BY t.table_name")))

(defn dropped-tables
  "`{table-name when}` for every table this lake has dropped — `dropped-at`'s
   answer for all of them at once, and the function `dropped-at` is now one
   lookup into.

   **One query, because the cost here is per query and not per table.** Measured
   2026-08-17 over the migrated store (29 tables): this whole map costs 3.38 ms
   and a single `dropped-at` costs 3.29 ms, so asking once per table is 29× the
   work for the same answer. `list_tables` measured 0.10 ms before it asked about
   reclamation and **100 ms** asking table by table — undoing the entire reason
   that reader stopped hydrating — against 3.5 ms batched. `table-catalog` sits
   on the same cliff, on every chat turn.

   The `NOT EXISTS` is the load-bearing clause and it is `dropped-at`'s: a
   tombstoned row does not mean a dead table, because `restore-to!` is a
   `CREATE OR REPLACE` that stamps `end_snapshot` on the old row while minting a
   live one under the same name. The question is not \"is there a tombstone\" but
   \"is there no live row\". `arg_max` over `end_snapshot` picks the newest
   tombstone, which is what `ORDER BY dt.end_snapshot DESC LIMIT 1` did per
   table.

   Note the metadata tables are invisible to `duckdb_tables()` and must be named
   in full."
  [lk]
  (into {}
        (map (fn [r] [(:table_name r) (java.util.Date/from (.toInstant (:t r)))]))
        (q lk (str "SELECT dt.table_name AS table_name,"
                   "       arg_max(s.snapshot_time, dt.end_snapshot) AS t"
                   "  FROM __ducklake_metadata_lake.main.ducklake_table dt"
                   "  JOIN ducklake_snapshots('lake') s"
                   "    ON s.snapshot_id = dt.end_snapshot"
                   " WHERE dt.end_snapshot IS NOT NULL"
                   "   AND NOT EXISTS (SELECT 1 FROM __ducklake_metadata_lake.main.ducklake_table live"
                   "                    WHERE live.table_name = dt.table_name"
                   "                      AND live.end_snapshot IS NULL)"
                   " GROUP BY dt.table_name"))))

(defn dropped-at
  "When `table` was dropped from this lake, or nil if it was never dropped.

   The catalog is the record, so nothing here keeps a parallel one. DuckLake
   sets `ducklake_table.end_snapshot` on a drop and `ducklake_snapshots` carries
   that snapshot's time, so \"was this reclaimed, and when\" is a question the
   store that did the reclaiming can already answer.

   Returns nil for a name that was never a table at all — absent is not the same
   as reclaimed, and a read that fails for any other reason must stay an error
   rather than being reported to a user as a successful cleanup.

   Note the metadata tables are invisible to `duckdb_tables()` and must be named
   in full.

   **A tombstoned row does not mean a dead table, and the `NOT EXISTS` is what
   makes the answer true.** `restore-to!` is a `CREATE OR REPLACE`, which mints
   a *new* `table_id` under the same table name and directory while stamping
   `end_snapshot` on the old one — so one live table can own two catalog rows,
   one of them tombstoned:

     table_id 2  t_tbl_y_c41a2b1d  begin 2  end 3    ← replaced
     table_id 3  t_tbl_y_c41a2b1d  begin 3  end nil  ← live

   Filtering to non-null `end_snapshot` and taking the newest therefore finds
   the tombstone and never sees the live sibling. Measured 2026-08-17 on two
   tables given identical injury — every Parquet deleted, `DROP TABLE` never
   issued — the plain one answered nil and `hydrate` rethrew, and the restored
   one answered an instant and `hydrate` reported a reclamation for rows that
   were never reclaimed. That is the one thing this function exists to prevent,
   so the question it asks is not \"is there a tombstone\" but **\"is there no
   live row\"**.

   No longer latent: as of 2026-08-17 `list_tables` and `table-catalog` both
   filter on this answer — through `dropped-tables`, but it is the same
   question — so a restored table would vanish from the catalog the agent sees.

   **One lookup into `dropped-tables` rather than its own query**, so the
   `NOT EXISTS` above is written once. Measured the same 2026-08-17: a
   table-filtered query costs 3.29 ms and the whole map costs 3.38 ms, because
   the catalog is 29 tables and the cost is the round trip, not the scan. There
   was no cheaper single-table query to keep."
  [lk table]
  (get (dropped-tables lk) table))

(defn table-files
  "The absolute paths of every Parquet file `table` owns, with their sizes.

   Files, not the directory. `restore-to!` is a `CREATE OR REPLACE`, which mints
   a new `table_id` under the **same** table name and directory — measured
   2026-08-17, one directory held a dead and a live Parquet at once — so
   removing a directory can destroy live data. The individual path is the only
   safe unit.

   **Every `table_id` that ever bore the name, which is why this reads
   `ducklake_data_file` rather than `ducklake_list_files`.** That function
   resolves through the live catalog and names the live `table_id`'s files only.
   Measured 2026-08-17: reclaiming a restored table through it left the
   pre-restore Parquet on disk, and `CREATE TABLE t AS SELECT * FROM t AT
   (VERSION => 1)` then returned all 100 rows against a table that had just been
   reclaimed — so the reclamation was not real, and a later undo would appear to
   restore rows that are gone. The bytes were also uncollectable afterwards: the
   name leaves `live-tables` on the drop, so nothing would ever offer them
   again.

   **The path is composed, not read.** DuckLake stores all three levels
   relative — `ducklake_schema.path` is `main/`, `ducklake_table.path` is
   `t_tbl_x_…/`, and `ducklake_data_file.path` is the bare
   `ducklake-….parquet` — so this joins the two parents and prefixes the lake's
   own root. Each level carries `path_is_relative`, and a level that says false
   is already absolute and stops the composition there.

   The root is `(:dir lk)`, never `ducklake_options`. That is the same authority
   `open-lake!`'s `OVERRIDE_DATA_PATH` establishes: the catalog still reports
   the path the lake was *created* at, which is the wrong answer for a lake that
   has been moved, copied or mounted.

   Unlike `ducklake_list_files`, this does not throw for a dropped table — the
   metadata rows survive the tombstone (measured: still two rows after the
   `DROP`). `loci.lakegc/reclaim!` still lists before it drops, because the
   paths are what it is about to delete and there is no reason to hold them
   across a mutation."
  [lk table]
  (let [under (fn [parent rel? p] (if rel? (str parent p) p))]
    (mapv (fn [r]
            {:data_file (-> (str (:dir lk) "/files/")
                            (under (:s_rel r) (:s_path r))
                            (under (:t_rel r) (:t_path r))
                            (under (:f_rel r) (:f_path r)))
             :data_file_size_bytes (:file_size_bytes r)})
          (q lk (str "SELECT df.path AS f_path, df.path_is_relative AS f_rel,"
                     "       df.file_size_bytes AS file_size_bytes,"
                     "       t.path AS t_path, t.path_is_relative AS t_rel,"
                     "       s.path AS s_path, s.path_is_relative AS s_rel"
                     "  FROM __ducklake_metadata_lake.main.ducklake_data_file df"
                     "  JOIN __ducklake_metadata_lake.main.ducklake_table t"
                     "    ON t.table_id = df.table_id"
                     "  JOIN __ducklake_metadata_lake.main.ducklake_schema s"
                     "    ON s.schema_id = t.schema_id"
                     " WHERE t.table_name = '" table "'")))))

(defn drop-table!
  "Drop `table` from the catalog. Frees no bytes on its own — a DuckLake drop is
   a versioned tombstone, and the table still reads `AT` a version where it
   existed. `loci.lakegc` deletes the files afterwards; that is what makes it
   real.

   It also **mints** a snapshot rather than removing one (measured 2026-08-17:
   3 snapshots before a drop, 4 after), which is the whole reason reclamation
   can be done this way — every other object's handle still finds the version it
   reads at."
  [lk table]
  (x! lk (str "DROP TABLE " table)))

(defn- load-rows!
  "Bulk-load via newline-delimited JSON through a temp file. DuckDB infers the
   types, which is the same inference `read_csv` already gives `import-csv!`,
   and it is the path that stays reasonable at millions of rows — measured
   2.9 s for a 10M-row create."
  [lk table cols rs]
  (let [f (java.io.File/createTempFile "loci-rows-" ".ndjson")]
    (try
      (with-open [w (io/writer f)]
        (doseq [r rs] (json/write r w) (.write w "\n")))
      (x! lk (str "CREATE TABLE " table " AS SELECT " (select-cols cols)
                  " FROM read_json_auto('" (.getAbsolutePath f)
                  "', format='newline_delimited')"))
      (finally (.delete f)))))

(defn- projection
  "`\"Total Revenue ($)\" AS \"total_revenue\", …` from `describe`'s column list.

   Named rather than `SELECT *` on purpose, and the reason is passthrough: a
   named projection THROWS when the file's header changes, where `SELECT *`
   would quietly return a differently-shaped table. That throw is what
   `hydrate` turns into `:table/source-drift`.

   **A header that is already loci's column name is emitted bare, with no `AS`**,
   and that is about the message rather than the SQL — the two forms select the
   same thing. `\"a\" AS \"a\"` puts DuckDB on its lateral-column-alias path, so
   when the file no longer has an `a` it answers about the SELECT list instead of
   about the file. Measured 2026-08-20, same relation, header now `c,d`:

     \"a\" AS \"a\" → Column \"a\" referenced that exists in the SELECT clause -
                  but this column cannot be referenced before it is defined
     \"a\"        → Referenced column \"a\" not found in FROM clause!
                  Candidate bindings: \"c\"

   `Candidate bindings` is the half that names what the file holds NOW, which is
   the one thing a reader needs in order to reconnect — see `duckdb-said`, whose
   whole paragraph rule exists to keep it. The redundant alias would drop it for
   exactly the CSVs whose headers were already clean."
  [cols]
  (str/join ", "
            (map (fn [{:keys [raw col]}]
                   (if (= (name raw) (name col))
                     (ident col)
                     (str (ident raw) " AS " (ident col))))
                 cols)))

(defn create-from-relation!
  "Materialise `rel` — any SQL relation expression — into a new lake table, and
   return the handle. The rows never enter this process: DuckDB reads the file
   and writes Parquet, and loci sees a count.

   The counterpart to `load-rows!`, which is the path for rows ALREADY in RAM.
   Both mint a table name per call, because writes never overwrite.

   `cols` is `loci.source/describe`'s output: `[{:raw \"…\" :col :…} …]`."
  [lk id cols rel]
  (let [table (table-name id (System/nanoTime))]
    (x! lk (str "CREATE TABLE " table " AS SELECT " (projection cols) " FROM " rel))
    {:store :lake :table table :snapshot (current-snapshot lk)
     :rows  (:n (first (q lk (str "SELECT count(*) AS n FROM " table))))
     :cols  (mapv :col cols)}))

(defn relation-rows
  "Every row of `rel` — any SQL relation expression — read into this process
   under `describe`'s column names, **creating nothing**.

   `create-from-relation!`'s sibling, one `CREATE TABLE … AS` short of it: same
   `projection`, so the raw header becomes the same normalized column, and the
   same `q-as`, so `unwrap` renders a DATE the same way. Measured 2026-08-20
   over the five `corpus/` CSVs — 1,311 rows — the two paths are `=` row for
   row and value for value, which is what lets a caller read a file the way a
   materialised import will store it without storing it.

   Wanted for exactly one thing, and it is the thing `create-from-relation!`
   cannot do: `loci.corpus` reads each of its files to CHECK it and to derive
   facts from it, on dry runs as well as live ones, and a preview that landed a
   table per file would leave the orphans and snapshots `import-corpus!`'s
   `InlineTable` exists to avoid. Measured the same day: five
   `create-from-relation!` calls take a fresh lake from 1 snapshot to 6 and
   leave five tables; five `relation-rows` calls leave 1 and none.

   **Not a way to read a table.** A `:lake` handle names a table and a version
   and `tbl/rows` pages it; this reads a *recipe* and holds all of it, so the
   caller has to know the relation is small. The corpus's largest file is 445
   rows."
  [lk cols rel]
  (q-as lk (str "SELECT " (projection cols) " FROM " rel) (mapv :col cols)))

(defn- from-of
  "The FROM text for a handle: a versioned table, or a source's reader call
   **already renamed to loci's columns**.

   Both modes go through one function so a read cannot resolve differently from
   the projection built beside it — and for a `:source` handle the projection is
   INSIDE here rather than beside it, because there are two callers and they
   build different SELECTs. `rows` projects the columns; `column-samples` CASTs
   one at a time. Measured 2026-08-20, with the rename left to each of them, a
   passthrough source over `Total Revenue ($),Region` failed BOTH ways with

     Binder Error: Referenced column \"total_revenue\" not found in FROM clause!
     Candidate bindings: \"Total Revenue ($)\"

   — because a handle's `:cols` are loci's names and `read_csv` exposes the
   file's. Renaming in the FROM is what `create-from-relation!` gets for free by
   writing a table whose columns already carry them, and it means neither caller
   has to know the difference. Anything reading through this sees loci's names.

   `:raw` is the file's own header, recorded at connect beside `:cols`. A
   `:source` handle committed before `:raw` existed falls back to `:cols` —
   correct exactly when the headers were already identifiers, which is the only
   case such a handle could ever have read in. `refresh!` re-describes and
   writes `:raw`, so it repairs one.

   Still a NAMED projection and not `SELECT *`: a positional read would quietly
   return a differently-shaped table where this throws, and that throw is what
   `hydrate` turns into `:table/source-drift`."
  [h]
  (if (= :source (:store h))
    (str "(SELECT " (projection (map (fn [raw col] {:raw raw :col col})
                                     (or (:raw h) (:cols h))
                                     (:cols h)))
         " FROM " (ssp/relation h (ssp/locate (:uri h))) ")")
    (str (:table h) " AT (VERSION => " (long (:snapshot h)) ")")))

(defn- samples-version
  "What `from-of` cannot say: **which bytes**. nil when the FROM text already
   settles it.

   A cache key here does two jobs — separate different tables, and change when
   one table's bytes change — and for a `:lake` handle the FROM text does both
   on its own, because it names the table *and* the snapshot and a write never
   overwrites. Measured 2026-08-19: two `put-rows!` under one object id land in
   `t_tbl_same_d08b834d` and `t_tbl_same_912090c8`. So there is nothing to add,
   and nil is the whole answer.

   A `:source` handle's FROM text names a path, and the file at that path can be
   rewritten with no event anywhere. Its `:at` — the instant of the connect or
   refresh that last looked — is the only thing that moves when the bytes do.

   **An object id is deliberately NOT here.** It looks like it belongs, because
   an id is what separates two tables; but two source handles sharing a path
   share bytes, so sharing their samples is correct rather than a collision, and
   the path is already in the FROM text. An id in this key would be a component
   no test could distinguish — which is worse than absent, because it reads as a
   guarantee.

   **`:at` is a refresh marker, not a version.** Overwrite the file without
   refreshing and the synopsis is stale and nothing detects it. Only the
   synopsis: the ROWS are always live, because they go straight to the file.

   Two refreshes *are* distinguished, and it is worth saying why rather than
   assuming it. `:at` is `(str (java.time.Instant/now))`, which is nanosecond
   resolution — measured 2026-08-19, 2000 consecutive calls gave 2000 distinct
   strings — while the cheapest possible refresh is a `describe` round trip at
   2.24 ms. Two refreshes of one path would have to land in the same nanosecond
   with 2.24 ms of work between them. No tiebreaker is needed here; if `:at`
   ever comes from something coarser than `Instant/now`, this is the paragraph
   that stops being true."
  [h]
  (when (= :source (:store h)) (:at h)))

(def ^:private sample-cache
  "Samples for a `[lake version from cols per-col]`, computed once. `from` is the
   FROM text — a lake table with its snapshot, or a source's relation over a path
   — and `version` is what that text cannot say: nil for a `:lake` handle, the
   handle's `:at` for a `:source`. See `from-of` and `samples-version`.

   `memoize` rather than a hand-rolled atom, and **sound for a `:lake` handle
   rather than merely convenient**: such a handle is immutable, because
   `put-rows!` always creates a new lake table and nothing overwrites one. So for
   those a hit is not a stale answer, it is the only answer.

   **A `:source` handle does not get that guarantee, and cannot.** Its bytes live
   in a file loci does not own, and `:at` is the instant of the last connect or
   refresh rather than a version of the file — so overwrite the file without
   refreshing and a hit here is a stale synopsis and nothing detects it, exactly
   as `samples-version` says. That is a bounded staleness and it is the one
   loci chose: only the SYNOPSIS goes stale, because the rows are read straight
   from the file on every query, and `refresh!` moves `:at` and mints a fresh
   entry. Do not read the soundness paragraph above as covering both.

   That first invariant is the same one `current-snapshot` above depends on, and
   it stops holding on the same day: **whoever adds in-place mutation to the lake
   has to invalidate this**. `restore-to!` is already a `CREATE OR REPLACE` and
   is the closest thing to a counter-example, but it is not one — measured
   2026-08-16, a cached answer still matches a fresh one across an `UPDATE` and
   a `restore-to!`, because `AT (VERSION => n)` resolves the handle's own
   snapshot and the rewrite only moves the write head.

   Unbounded, and that is affordable *because the clip happens in SQL rather
   than downstream*. An entry is at most `max-cols` × `per-col` values of
   `tbl/sample-max-chars` code points — 24 × 3 × 41 ≈ 3 KB, or double that if
   every code point is astral. Measured 2026-08-16 over the 31 live tables: a
   median entry of 117 characters and a maximum of 879 (1,113 bytes of EDN). A
   table with a 5,000-character text column, which without the clip would put
   15 KB in one entry, produces 123 characters.

   It retains more than the samples: the key holds the `Lake` it was asked
   about, so an entry pins a JDBC connection. In the server there is one lake
   and so one reference; a test run that opens many leaks one each until the
   var is rebound. Measure before replacing this with an eviction policy.

   **`_version` is in the argument list only to be part of the memo key**, and it
   is what keeps the soundness argument true now that a passthrough handle
   exists. See `samples-version`: a `:lake` handle contributes nil, because its
   FROM text already names a table and a snapshot that never change; a `:source`
   handle contributes its `:at`, because its FROM text names only a path and the
   file there can be rewritten with no event anywhere."
  (memoize
   (fn [lk _version from cols per-col]
     (into {} (for [c cols]
                [c (mapv #(str (first (vals %)))
                         ;; Clipped BEFORE the DISTINCT, matching
                         ;; `tbl/clip-sample`: two values sharing a 40-point
                         ;; prefix are one sample, not two that print alike.
                         (q lk (str "SELECT DISTINCT CASE WHEN length(s) > " tbl/sample-max-chars
                                    " THEN substr(s, 1, " tbl/sample-max-chars ") || '…'"
                                    " ELSE s END AS v FROM ("
                                    "SELECT CAST(" (ident c) " AS VARCHAR) AS s"
                                    " FROM " from
                                    " WHERE " (ident c) " IS NOT NULL)"
                                    " ORDER BY v LIMIT " (long per-col))))])))))

(def ^:private numeric-type-re
  "Matched, case-insensitively, against a `DESCRIBE`d column's `column_type` to
   decide `column-profile`'s `:numeric`.

   The task that specified this listed `integer/decimal/double/float/real/
   hugeint/numeric`, and that set alone misses the type DuckDB actually gives a
   JSON integer: measured 2026-08-27, `put-rows!` over `{:i 1}` — the path every
   agent-authored table and CSV import takes — types the column `BIGINT`, which
   contains none of those seven words. `bigint`/`smallint`/`tinyint` are added
   for that reason, as substrings so the unsigned forms (`UBIGINT`, `USMALLINT`,
   `UTINYINT`, `UINTEGER`) match too without being spelled out.

   Not widened to a bare `int`, even though every name above already contains
   it: DuckDB's `INTERVAL` type contains `int` as well (*int*erval) and is not a
   number."
  #"(?i)integer|decimal|double|float|real|hugeint|numeric|bigint|smallint|tinyint")

(defn relation-of
  "A handle as a HoneySQL FROM entry, through `from-of`.

   **A subquery map, not a bare `[:raw …]`.** DuckDB refuses an alias directly on
   a versioned relation — `FROM t AT (VERSION => 1) AS x` is
   `Parser Error: syntax error at or near \"AS\"` — so it has to be wrapped to be
   aliasable at all, which is also what lets one query join two tables. Verified
   2026-08-27.

   **`from-of` and not a table name, and that is the whole fix.** This function
   first read `(:table h)` and `(:snapshot h)` directly, which a `:source`
   handle does not have, so `query_table` on a passthrough table threw an NPE
   inside `ident`. `from-of` answers for both stores AND renames a source's
   columns to loci's inside the FROM — see its docstring for the binder error
   that rename prevents."
  [h]
  {:select [:*] :from [[[:raw (from-of h)]]]})

(defn create-from-sql!
  "Materialise any SQL SELECT as a new lake table and return the handle.

   `create-from-relation!`'s sibling for a QUERY rather than a file, and the
   difference is `:cols`. `create-from-relation!` is given `describe`'s columns
   because it is reading a file whose header it just read. A query's columns are
   **not** the source's — `SELECT region, SUM(v) AS v` has two whatever the
   source had — so they are read back off the result with `DESCRIBE`.

   Getting this wrong writes a handle whose `:cols` name columns the table does
   not have, and every later read of it fails with `Binder Error: Referenced
   column not found` — the same failure `loci.source`'s docstring records for a
   passthrough handle carrying half a recipe."
  [lk id sql]
  (let [table (table-name id (System/nanoTime))]
    (x! lk (str "CREATE TABLE " table " AS " sql))
    {:store :lake :table table :snapshot (current-snapshot lk)
     :rows  (:n (first (q lk (str "SELECT count(*) AS n FROM " table))))
     :cols  (mapv #(sub/col-kw (:column_name %))
                  (q lk (str "DESCRIBE SELECT * FROM " table)))}))

(defn- run-honey
  "Format `hq` with values inlined and run it. One string, so `q` is unchanged.

   Inlined rather than parameterised because loci is single-user and personal —
   decided 2026-08-27. HoneySQL emits parameters separately if that ever changes,
   so the work would be a JDBC path and not a rewrite."
  [lk hq]
  (q lk (first (sql/format hq {:inline true}))))

(extend-type Lake
  tbl/TableStore
  (put-rows! [lk id rs]
    (let [rs    (tbl/normalize-rows (vec rs))
          cols  (tbl/cols-of rs)
          table (table-name id (System/nanoTime))]
      (when (empty? rs)
        (throw (ex-info "refusing to store a table with no rows" {:id id})))
      (load-rows! lk table cols rs)
      {:store :lake :table table :snapshot (current-snapshot lk)
       :rows (count rs) :cols cols}))

  (rows [lk h {:keys [offset limit]}]
    (q-as lk (str "SELECT " (select-cols (:cols h))
                  " FROM " (from-of h)
                  (when limit  (str " LIMIT "  (long limit)))
                  (when offset (str " OFFSET " (long offset))))
          (:cols h)))

  (page [lk h window]
    ;; **Keyset in natural order; an offset for everything else.** A materialised
    ;; DuckLake table exposes a dense `rowid`, so an unsorted page is a range
    ;; predicate rather than a scan-and-discard — which is the whole reason
    ;; paging is keyset-shaped at all.
    ;;
    ;; A SORT pages by offset even here, and that is a decision rather than a
    ;; shortcut. The compound-key form works — the spec's §2.1 proves
    ;; `WHERE (n, rowid) < (48, 2) ORDER BY n DESC, rowid` returns the exact
    ;; continuation — but taking it costs two problems natural order does not
    ;; have: `ORDER BY col DESC, rowid ASC` is not what `(col, rowid) < (v, k)`
    ;; selects, and a row comparison against a NULL is NULL, so rows in a
    ;; nullable sort column vanish. Both would be paid to speed up a query that
    ;; is dominated by its ORDER BY however it is paginated, and nobody has
    ;; measured that. §2.3 keeps the compound key written down as the first thing
    ;; to reach for the day someone feels it. `sorted-order-by` is where the
    ;; ORDER BY itself — and the tiebreaker an offset needs to stay correct —
    ;; gets built.
    ;;
    ;; A `:source` handle has no key of any kind — `rowid` is a binder error over
    ;; `read_csv` and `file_row_number` is not a valid `read_csv` parameter — so
    ;; it is always the offset branch. Its scan is not new: `read_csv` in a FROM
    ;; clause re-parses the file on every read, including page 1's.
    ;;
    ;; An UNSORTED `:source` page carries no `ORDER BY` at all, so it leans on
    ;; something loci itself never sets: `preserve_insertion_order`. Checked,
    ;; not assumed — `current_setting('preserve_insertion_order')` on this
    ;; connection is `true`, and DuckDB's docs name that as the default and say
    ;; what it buys: with it on, "the system" does NOT "re-order any results
    ;; that do not contain ORDER BY clauses", `read_csv` included, so separate
    ;; OFFSET queries against one file agree on a row's position. loci relies
    ;; on that default rather than setting it, so the guarantee ends the moment
    ;; anyone turns it off — nothing here would notice.
    ;;
    ;; A cursor also has to be checked against the CURRENT request's ordering
    ;; before its `:o` is trusted, below. Both offset modes — an unsorted
    ;; `:source` and a sorted page on either handle — share one cursor shape,
    ;; so nothing about `{:o 5}` says which order it was minted under; reread
    ;; against a changed `:sort` it is still a valid-looking cursor for the
    ;; WRONG walk, landing on whatever row offset 5 happens to be this time.
    ;; That is not a crafted cursor — `decode-cursor` and `cursor-int` are for
    ;; that — it is an ordinary caller paging forward and then changing the
    ;; sort mid-walk, which a sort control on every column header makes routine
    ;; rather than rare.
    (let [lim   (tbl/page-limit (:limit window))
          proj  (tbl/project-cols h (:cols window))
          srt   (tbl/sort-spec h (:sort window))
          seek? (and (nil? srt) (not (source-handle? h)))
          cur   (tbl/decode-cursor (:cursor window))
          after (tbl/cursor-int cur :k -1)
          ;; `:o` is only meaningful if THIS cursor was minted under THIS same
          ;; ordering — otherwise it is a valid-shaped number pointing into a
          ;; different order entirely, and the safe read is the same one a
          ;; cursor that fails to decode at all gets: restart at page 1. `=` is
          ;; safe against any decoded value, crafted or not — a mismatch of any
          ;; kind just fails the check rather than throwing.
          off   (if (= srt (:s cur)) (tbl/cursor-int cur :o 0) 0)
          ks    (cond->> proj seek? (cons :rowid))
          sql   (str "SELECT " (when seek? "rowid, ") (select-cols proj)
                     " FROM " (from-of h)
                     (cond seek? (str " WHERE rowid > " (long after) " ORDER BY rowid")
                           srt   (sorted-order-by h proj srt))
                     " LIMIT " (inc lim)
                     (when-not seek? (str " OFFSET " (long off))))
          got   (q-as lk sql (vec ks))
          more  (> (count got) lim)
          kept  (vec (take lim got))]
      {:rows   (mapv #(dissoc % :rowid) kept)
       ;; **`:offset` is a fact in both modes, and it is not the same fact.**
       ;; Offset mode was handed one. Keyset mode derives it from the first row's
       ;; `rowid`, which is the ordinal only because a DuckLake table's rowid is
       ;; dense and 0-based — the same property `WHERE rowid > k` already relies
       ;; on. An empty page has no first row and no position to report, so nil.
       :offset (if seek? (:rowid (first kept)) off)
       :next   (when more
                 (tbl/encode-cursor (if seek?
                                      {:k (:rowid (peek kept))}
                                      {:o (+ off lim) :s srt})))}))

  (row-count [_ h] (:rows h))

  (reclaimed-at [lk h]
    ;; Nothing can reclaim a file loci does not own, so a source handle is never
    ;; reclaimed — its failure modes are unavailable and drift, which `hydrate`
    ;; classifies.
    ;;
    ;; The guard saves a scan, not a crash. Measured 2026-08-19: `dropped-at`
    ;; with a nil table answers nil quite happily, because `dropped-tables`
    ;; queries the whole catalog and no real table is ever named nil. So this is
    ;; here to say that the question is meaningless for a source — and to stop a
    ;; catalog-wide query being run to answer it.
    (when-not (= :source (:store h)) (dropped-at lk (:table h))))

  (column-samples [lk h per-col max-cols]
    (sample-cache lk (samples-version h) (from-of h)
                  (vec (take max-cols (:cols h))) per-col))

  (column-profile [lk h]
    ;; `from-of`, not `relation-of` — `relation-of` reads `(:table h)` and
    ;; `(:snapshot h)` directly and only a `:lake` handle has either. `from-of`
    ;; is the function `rows` and `column-samples` already use for exactly this
    ;; reason: it is snapshot-pinned SQL text for a `:lake` handle and a renamed
    ;; `read_csv`/passthrough relation for a `:source` one, so one query works
    ;; for both handle shapes this store answers for.
    (let [cols    (:cols h)
          types   (into {}
                        (map (fn [r] [(sub/col-kw (:column_name r)) (:column_type r)]))
                        (q lk (str "DESCRIBE SELECT * FROM " (from-of h))))
          numeric (vec (filter #(re-find numeric-type-re (str (types %))) cols))
          numset  (set numeric)
          samples (tbl/column-samples lk h 13 (count cols))
          categorical (vec (for [c cols
                                  :when (and (not (numset c))
                                             (<= 2 (count (get samples c)) 12))]
                              c))]
      {:cols cols :numeric numeric :categorical categorical :rows (:rows h)}))

  (query [this handle spec]
    (let [rel   (relation-of handle)
          base  (hq/->honey (dissoc spec :limit) rel)
          ;; Count FIRST, always. Measured 2026-08-27 over 1,000,000 rows: the
          ;; GROUP BY costs 20 ms and this costs 6 ms, because DuckDB answers a
          ;; grouped count from the cardinality and never computes the sums.
          ;; It is also the PARSE CHECK — HoneySQL emits wrong SQL rather than
          ;; raising, and this is where DuckDB says so before any result is
          ;; trusted.
          total (long (:n (first (run-honey this (hq/->count base)))))
          lim   (or (:limit spec) tbl/max-query-rows)
          rows  (vec (run-honey this (assoc base :limit lim)))]
      {:rows rows :total total :capped (> total (count rows))}))

  (create-from-spec [this handle spec id _title]
    (create-from-sql! this id
                      (first (sql/format (hq/->honey (dissoc spec :limit) (relation-of handle))
                                         {:inline true})))))

(defn sweep!
  "Automatic table GC. Disabled, deliberately, and it reports that rather than
   returning a zero that reads like a completed sweep.

   **It is snapshot expiry that is off, not reclamation.**
   `loci.lakegc/report` and `loci.lakegc/reclaim!` are the manual per-table path
   and they work — read the first for what every object's versions cost, call
   the second to free one object's out-of-window ones. They work *precisely
   because they never expire a snapshot*: `reclaim!` issues `DROP TABLE` and
   deletes that table's own Parquet, and a drop MINTS a snapshot rather than
   removing one, so every other handle in the log goes on reading. Everything
   below is why the automatic path cannot borrow that safety — a caller who
   reads only this function's `:why` and stops must not conclude that nothing in
   loci can reclaim table space, because that has not been true since
   `loci.lakegc` shipped.

   Two reasons the automatic path stays off, and the second is the load-bearing
   one.

   **Today: DuckLake's expiry is not safe to run.** It cannot expire the latest
   snapshot (duckdb/ducklake#261) — which is exactly the state an undo leaves —
   and snapshot expiry has open data-loss bugs (#527, #529).

   **After those are fixed: expiry is still incompatible with how loci reads a
   table.** Every handle reads `AT (VERSION => n)`, so a handle is a *reference
   to a snapshot*, and the log keeps handles forever — an undone `:put` still
   names the table it wrote. Measured 2026-08-16 during Task 5's review:
   `ducklake_expire_snapshots(older_than => now())` followed by
   `ducklake_cleanup_old_files` makes an existing handle's read throw

     Invalid Input Error: No snapshot found at version 1

   So reclaiming disk invalidates stored handles *across the whole log*, and the
   day someone enables GC to free space is the day every historical object stops
   rendering. A working expiry is therefore not enough: loci would first have to
   decide which snapshots are still referenced by live handles — walk the log,
   collect every `{:store :lake}` value, keep those versions — and that
   machinery does not exist. `live` is the argument it would take.

   Blob GC has no such dependency and ships in its own plan. Automatic
   retention here is: keep everything. Saying so is the whole point of this
   function existing before it works — the same job `embedding-configured?`
   does for semantic recall, and the failure it avoids is a sweep that
   *implies* it was complete.

   `live` is the argument the automatic path would take, and it is exactly the
   question `loci.lakegc/classify` already answers per table. The gap is not
   knowledge; it is that expiry is catalog-wide and reclamation has to be
   per-table."
  [_lk _live]
  {:mode :disabled
   :freed 0
   :why (str "automatic table GC is off: DuckLake cannot expire the latest "
             "snapshot (duckdb/ducklake#261) and snapshot expiry has open "
             "data-loss bugs (#527, #529) — and even fixed, expiring a snapshot "
             "invalidates every handle that reads AT (VERSION => it), which "
             "loci does not yet track. Reclaiming table space is done by hand "
             "instead: loci.lakegc/report says what each object's versions "
             "cost and loci.lakegc/reclaim! frees one object's out-of-window "
             "ones, by dropping those tables and deleting their Parquet — "
             "never by expiring a snapshot, which is why it is safe. Automatic "
             "retention is: keep everything.")})
