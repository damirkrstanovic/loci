(ns loci.files
  "The manifest — which original filename a content-addressed file in
   `<LOCI_DATA>/files/` arrived under — and the sweep that clears abandoned
   upload partials.

   `data/files/` holds `72b441a9c3e05f18.csv`. Content addressing is what makes
   dedup work and what makes a passthrough file safe to re-read, and it is also
   what makes the directory unbrowsable by a person: measured during phase 1,
   putting the title in the filename defeats the dedup the moment one CSV
   arrives under two titles — \"Sales\" and \"Sales again\" wrote two copies of
   identical bytes. So the file is addressed and the name is metadata, and the
   metadata lives beside it in `<LOCI_DATA>/files.edn`.

   **Append-only EDN lines, last-wins by `:id`** — the third use of the idiom
   `loci.chunks` and `loci.memory` already keep. Deliberately not a new
   mechanism.

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
            [loci.substrate :as sub]))

(defn- ^java.io.File manifest [] (io/file (sub/data-dir) "files.edn"))

(defn- ^java.io.File partial-dir [] (io/file (sub/data-dir) "files" ".partial"))

(defn names
  "`{id name}` for every file the manifest remembers. Last-wins by `:id`.

   No manifest at all is `{}` and not an error. A file placed by hand, by
   `rsync`, or by a mounted directory is a legitimate way for
   `<LOCI_DATA>/files/` to hold content loci never named — `loci.server/source-
   files` lists such a file under its hash — so an absent or partial manifest is
   an ordinary state, not a broken one.

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
  (let [f (manifest)]
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
        f   (manifest)]
    (io/make-parents f)
    (spit f (str (pr-str rec) "\n") :append true)
    rec))

(defn sweep-partials!
  "Delete anything under `<LOCI_DATA>/files/.partial` last modified more than 24
   hours ago. Returns the names removed, sorted.

   A chunked upload appends into `.partial/<id>` and renames the finished file
   into `files/` on its last chunk, so a partial still sitting there is either
   an upload in flight or a browser that went away mid-transfer — and nothing on
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
