(ns loci.files
  "`<LOCI_DATA>/files/` — the directory a connected file's bytes live in, and
   everything that knows its rules: where it is, what in it counts as content,
   how a file in it is spelled as a location, the manifest of original names,
   and the sweep that clears abandoned upload partials.

   **One namespace owns the directory, and that is the point of this one.**
   `(io/file (sub/data-dir) \"files\")` was written out twice in `loci.server`
   alone before this existed, `loci.sourcespec/locate` computes the inverse, and
   the upload route is a third writer arriving. Worse, the `.partial` convention
   was asserted by a docstring in `loci.server` and a fixture in its test while
   nothing in `src/` created `.partial` at all — the rule and its producer in
   different namespaces is how a directory convention comes to have two
   spellings, neither of which can see the other.

   ## The manifest

   `files/` holds `72b441a9c3e05f18.csv`. Content addressing is what makes dedup
   work and what makes a passthrough file safe to re-read, and it is also what
   makes the directory unbrowsable by a person: measured during phase 1, putting
   the title in the filename defeats the dedup the moment one CSV arrives under
   two titles — \"Sales\" and \"Sales again\" wrote two copies of identical
   bytes. So the file is addressed and the name is metadata, and the metadata
   lives beside it in `<LOCI_DATA>/files.edn`.

   **Append-only EDN lines, last-wins by `:id`** — the third use of the idiom
   `loci.chunks` and `loci.memory` keep. Deliberately not a new mechanism.

   **One difference from those two, and it matters: this one is PRIMARY, not
   derived.** A chunk is a pure function of an object, so `chunks.edn` can be
   deleted and re-swept byte-equivalently; a fact's line is the fact. `files.edn`
   can be recomputed from nothing at all — the hash in the directory does not
   carry the name, and no other record in loci keeps it. Delete it and the
   original filenames are gone for good. The bytes survive and the picker
   degrades to listing every file under its hash, so it is a loss of legibility
   rather than of data — but it is **not a cache, and must not be described as
   one.**"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loci.substrate :as sub]))

;; ---------------------------------------------------------------------------
;; where it is, and what is in it
;; ---------------------------------------------------------------------------

(defn dir
  "`<LOCI_DATA>/files/` as a `File`. Resolved on every call, never cached: the
   tests and the demo redefine `sub/data-dir`, and a value captured at load time
   would send a write and the read after it to different directories."
  ^java.io.File []
  (io/file (sub/data-dir) "files"))

(defn uri
  "How a file in `dir` is spelled as a source location: `loci://files/<name>`.

   The inverse of `loci.sourcespec/locate`, which resolves any `loci://` path
   under the data directory and so stays the general one. This is the specific
   half — the only spelling `import-csv!`, the picker and the upload route are
   allowed to mint — and it exists so the prefix is written once."
  [file-name]
  (str "loci://files/" file-name))

(defn content-hash
  "The first 16 hex characters of the SHA-256 of some bytes — the whole name of
   the file holding them. `text` is hashed as its UTF-8 bytes; a `File` is
   hashed by streaming it.

   Sixteen because this IS the filename rather than a suffix on one: 64 bits,
   so two different CSVs collide at around four billion files, and `dir` holds
   one entry per distinct import. The same digest `lake/table-name` truncates
   for the same reason, kept longer here because there is no other component to
   fall back on.

   **The two shapes must agree byte for byte, and that is not a nicety.** A CSV
   pasted into `/api/import-csv` arrives as a String and a CSV uploaded through
   `/api/source/upload` arrives as a completed file; if the two disagreed the
   same bytes would land twice under two names and the dedup this directory is
   built on would quietly stop working for exactly the pair a person is most
   likely to produce. `content-hash-agrees-between-a-string-and-the-file-
   holding-those-bytes` is the only thing that checks it.

   **The `File` shape streams, and that is the whole reason it exists.** It
   lives here rather than in `loci.server` because an upload is finished by
   hashing the file it just wrote, and hashing a 200 MB upload by reading it
   into a String would put 200 MB on the heap — undoing the chunking the upload
   route exists for. A 64 KB buffer holds however large the file is."
  [x]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (if (instance? java.io.File x)
      (with-open [in (io/input-stream x)]
        (let [buf (byte-array 65536)]
          (loop []
            (let [n (.read in buf)]
              (when (pos? n)
                ;; `0 n` and not the whole buffer: the last read is short, and
                ;; digesting all 65536 bytes would fold in whatever the
                ;; previous read left behind.
                (.update md buf 0 n)
                (recur))))))
      (.update md (.getBytes (str x) "UTF-8")))
    (->> (.digest md)
         (take 8)
         (map #(format "%02x" %))
         (apply str))))

(defn- ^java.io.File manifest-file [] (io/file (sub/data-dir) "files.edn"))

(defn- ^java.io.File partial-dir [] (io/file (dir) ".partial"))

(defn- content?
  "Is `f` a file the picker may offer?

   Two rules, and they are separate because each catches something the other
   misses. **Not a directory** — `.partial` is one, and so is any folder a
   person drops in. **Not dot-prefixed** — `.DS_Store` and its kind are not
   directories and would otherwise list as offerable CSVs."
  [^java.io.File f]
  (and (not (.isDirectory f))
       (not (str/starts-with? (.getName f) "."))))

;; ---------------------------------------------------------------------------
;; the manifest — append-only EDN lines, last-wins by :id
;; ---------------------------------------------------------------------------

(defn names
  "`{id name}` for every file the manifest remembers. Last-wins by `:id`.

   No manifest at all is `{}` and not an error. A file placed by hand, by
   `rsync`, or by a mounted directory is a legitimate way for `dir` to hold
   content loci never named — `listing` offers such a file under its hash — so
   an absent or partial manifest is an ordinary state, not a broken one.

   **Read line by line with `edn/read-string`, and NOT with the whole-reader
   `edn/read` `loci.chunks` uses.** The difference is what one torn line costs,
   and it is worse than it looks. `edn/read` over a reader does not know where a
   line ends: given `{:id \"b\" :name` it keeps going, takes the NEXT record as
   `:name`'s value, and then runs out of input still looking for the brace that
   would close the outer map. Measured 2026-08-21 against exactly that file: the
   torn record AND the good record after it are both lost, and so is everything
   appended later. Reading one line at a time and dropping the ones that do not
   parse loses exactly the line that was torn."
  []
  (let [f (manifest-file)]
    (if (.exists f)
      (with-open [r (io/reader f)]
        (reduce (fn [acc line]
                  (let [rec (try (edn/read-string line) (catch Exception _ nil))]
                    (if (and (map? rec) (:id rec) (:name rec))
                      (assoc acc (:id rec) (:name rec))
                      acc)))
                {}
                (line-seq r)))
      {})))

(defn remember!
  "Record that the file `id` — a content-addressed name like
   `72b441a9c3e05f18.csv` — arrived as `original`. Returns the record written.

   One line, appended, never a rewrite. Remembering an id a second time under a
   different name is not an error and does not need to find the old line:
   `names` is last-wins, which is exactly how `loci.memory` reinforces a fact
   and how `loci.chunks` re-embeds one."
  [id original]
  (let [rec {:id id :name original :at (System/currentTimeMillis)}
        f   (manifest-file)]
    (io/make-parents f)
    (spit f (str (pr-str rec) "\n") :append true)
    rec))

;; ---------------------------------------------------------------------------
;; the listing the picker draws
;; ---------------------------------------------------------------------------

(defn listing
  "What is in `dir`, as `[{:file :uri :bytes :name} …]` **sorted by `:file`**.

   Two mechanisms write this directory, both content-addressed:
   `loci.server/import-csv!` for a pasted CSV, and `POST /api/source/upload` for
   an uploaded one — which finishes through `finish-upload!` above. A person may
   also drop a file in by hand or mount one. So the listing is of the DIRECTORY rather than of any
   record loci keeps: a file loci did not put there still connects, and
   pretending otherwise would make the picker lie about what is available.

   `:name` is the manifest's memory of what an upload was called, and is absent
   for anything loci never named — absent rather than defaulted to `:file`, so
   the shell can tell a remembered name from a hash and draw them differently.

   **Sorted, and that is not cosmetic.** `File.listFiles` promises no order at
   all; measured 2026-08-21 it answers creation order here and the docs allow
   hash order elsewhere. Unsorted, the picker reshuffles its rows between two
   opens of the same panel with nothing having changed, which is the kind of
   motion a person reads as *something happened*.

   `.partial` never appears. It is where a chunked upload accumulates —
   `append-chunk!` writes it, `finish-upload!` empties it and `sweep-partials!`
   clears what neither did — and a half-arrived file is not something the picker
   may offer."
  []
  (let [named (names)]
    (->> (.listFiles (dir))
         (filter content?)
         (sort-by (fn [^java.io.File f] (.getName f)))
         (mapv (fn [^java.io.File f]
                 (let [n (.getName f)]
                   (cond-> {:file n :uri (uri n) :bytes (.length f)}
                     (named n) (assoc :name (named n)))))))))

;; ---------------------------------------------------------------------------
;; the chunked upload — the `.partial` working file, and the sequence that
;; fills it. Here and not in the route for the reason this namespace exists at
;; all: `sweep-partials!` below already DESCRIBES this mechanism, and a rule
;; whose producer lives in another namespace is how `.partial` came to be
;; asserted by a docstring in `loci.server` while nothing in `src/` made one.
;; `loci.server` keeps the route, the header and the JSON envelope.
;; ---------------------------------------------------------------------------

(def ^:private upload-id-re
  ;; The id names a file under `.partial`, and it arrives in a header from the
  ;; browser — so it is the one caller-supplied string in this namespace that
  ;; becomes a path. `{"id": "../../../etc/cron.d/x"}` is a directory traversal
  ;; and this is what refuses it. No dots, no separators, nothing to resolve.
  #"[A-Za-z0-9_-]{1,64}")

(defonce ^:private expected
  ;; `{upload-id -> the :seq the next chunk of that upload must carry}`.
  ;;
  ;; **An atom because it cannot be derived.** The obvious alternative is to
  ;; read the partial's length and infer how much has arrived, and it does not
  ;; work: chunks vary in size — the last one always does, and a browser is
  ;; free to slice differently — so a length tells you how many BYTES landed
  ;; and never how many CHUNKS.
  ;;
  ;; **An abandoned upload leaves its entry behind until restart, and that is
  ;; the answer rather than an oversight.** It is the same garbage `.partial`
  ;; accumulates on disk, and the disk side already has a sweep because bytes
  ;; are the part that costs something. A key and a boxed long is on the order
  ;; of a hundred bytes; a loci that took a thousand abandoned uploads between
  ;; restarts holds a hundred kilobytes of them. An eviction policy here would
  ;; be a second sweep, with its own clock and its own way to be wrong, for a
  ;; cost nobody has felt.
  ;;
  ;; `defonce` so that reloading this namespace does not drop an upload that is
  ;; mid-flight — the REPL is the execution surface here, and every `:reload`
  ;; would otherwise strand whatever the browser was sending.
  (atom {}))

(defn- extension
  "The extension to give the stored file, taken from the name it was uploaded
   under. `sales.csv` → `csv`.

   Lower-cased, and refused unless it is short and alphanumeric. The hash is
   the whole of the filename's stem, so `original` never reaches the disk — but
   the extension does, and an extension is a place a `/` could hide. Anything
   else falls back to `csv`, the only format this phase connects."
  [original]
  (let [i (str/last-index-of (or original "") ".")
        e (when (and i (pos? i)) (str/lower-case (subs original (inc i))))]
    (if (and e (re-matches #"[a-z0-9]{1,8}" e)) e "csv")))

(defn append-chunk!
  "Append `chunk` — anything `io/copy` reads: an `InputStream`, a byte array, a
   String — to upload `id`'s working file under `.partial`, as chunk number `n`.
   Answers `{:id :seq :bytes}` where `:bytes` is the partial's length so far.

   Throws `ex-info` rather than answering `{:error …}`: `loci.server` wraps
   this in the same `try`/`catch` its other `source-*` functions use, and the
   envelope is the route's business.

   **`n` must be the next one, and an unknown id expects 0.** That single rule
   covers both failures the spec names — a chunk out of sequence and a chunk
   for an upload nobody started — because an upload nobody started is an upload
   whose next chunk is its first. A refused chunk changes nothing: the file is
   not touched and the expected number is not advanced, so the correct chunk
   can still follow.

   **`n` = 0 always starts an upload, and truncates rather than appends.**
   Nothing about beginning is out of sequence, so chunk 0 is never refused —
   not even when `expected` still remembers a number for this id from an upload
   that was abandoned. That is what makes the leftover entry harmless as well as
   small: the only chunk that can start an upload is the one chunk that ignores
   it, so a retry under a reused id starts over instead of being refused forever
   by a state nothing on disk backs. Truncating is the other half — appending to
   what a dead attempt left would silently produce a file with a prefix of
   itself glued to the front, and the bytes are content-addressed, so that
   corruption would land under a hash OF the corrupt bytes and look exactly as
   valid as any other file in the directory."
  [id n chunk]
  (when-not (and (string? id) (re-matches upload-id-re id))
    (throw (ex-info (str "unusable upload id: " (pr-str id)) {:id id})))
  (when-not (integer? n)
    (throw (ex-info (str "upload chunk carries no sequence number: " (pr-str n))
                    {:id id :seq n})))
  (let [f    (io/file (partial-dir) id)
        want (get @expected id 0)]
    (when-not (or (zero? n) (= n want))
      (throw (ex-info (str "chunk " n " arrived out of sequence for upload " id
                           " — expected " want)
                      {:id id :seq n :expected want})))
    (io/make-parents f)
    (when (zero? n) (.delete f))
    (with-open [out (java.io.FileOutputStream. f true)]
      (when chunk (io/copy chunk out :buffer-size 65536)))
    (swap! expected assoc id (inc n))
    {:id id :seq n :bytes (.length f)}))

(defn finish-upload!
  "Turn upload `id`'s completed `.partial` file into a content-addressed file in
   `dir`, remembered as `original`. Answers `{:file :uri :existed}`.

   **Hashed by streaming**, never by reading the file in — see `content-hash`.
   The point of taking the upload in chunks is that no step holds the whole
   file, and hashing is the step that would most easily forget.

   **A hash that is already in `dir` is discarded, not rewritten**, and the
   partial is deleted. It is `import-csv!`'s rule and it is here for the sharper
   version of `import-csv!`'s reason: a `loci://` passthrough source re-reads
   its file on EVERY query, so rewriting a file whose name is its content would
   change what a table already on screen answers. Identical bytes make that
   rewrite harmless in principle and pointless in practice — but the only way
   to be sure the bytes are identical is the hash that just matched, so the
   cheap move and the safe move are the same one.

   The name is remembered after the move, and only if there is one: a caller
   with no name for the file leaves it in the state `listing` already handles —
   a file loci never named, offered under its hash."
  [id original]
  (let [part (io/file (partial-dir) id)]
    (when-not (.exists part)
      (throw (ex-info (str "no upload in progress for " (pr-str id)) {:id id})))
    (let [d    (doto (dir) .mkdirs)
          nm   (str (content-hash part) "." (extension original))
          dest (io/file d nm)
          had? (.exists dest)]
      (if had?
        (.delete part)
        (java.nio.file.Files/move (.toPath part) (.toPath dest)
                                  (into-array java.nio.file.CopyOption [])))
      (swap! expected dissoc id)
      (when (not-empty original) (remember! nm original))
      {:file nm :uri (uri nm) :existed had?})))

;; ---------------------------------------------------------------------------
;; the sweep
;; ---------------------------------------------------------------------------

(defn sweep-partials!
  "Delete anything under `<LOCI_DATA>/files/.partial` last modified more than 24
   hours ago. Returns the names removed, sorted.

   A chunked upload appends into `.partial/<id>` and renames the finished file
   into `dir` on its last chunk, so a partial still sitting there is either an
   upload in flight or a browser that went away mid-transfer — and nothing on
   disk tells those apart at the moment you look. Age does. 24 hours is far
   longer than any single upload and far shorter than the point at which
   anything would want the bytes back.

   No `.partial` directory is `[]` and not an error: `.listFiles` answers nil
   for a directory that does not exist, and a loci that has never taken an
   upload has never made one. Boot calls this before anything could have."
  []
  (let [cutoff (- (System/currentTimeMillis) (* 24 60 60 1000))]
    (->> (.listFiles (partial-dir))
         (remove (fn [^java.io.File f] (.isDirectory f)))
         (filter (fn [^java.io.File f] (< (.lastModified f) cutoff)))
         (filter (fn [^java.io.File f] (.delete f)))
         (map (fn [^java.io.File f] (.getName f)))
         sort
         vec)))
