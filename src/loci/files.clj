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
   `loci.server/import-csv!` today, and `POST /api/source/upload` once Task 7 of
   the external-sources phase 2 plan lands. A person may also drop a file in by
   hand or mount one. So the listing is of the DIRECTORY rather than of any
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

   `.partial` never appears. It is where a chunked upload accumulates — Task 7
   writes it, `sweep-partials!` below clears it — and a half-arrived file is not
   something the picker may offer."
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
