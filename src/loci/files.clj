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
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]))

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

(defn- records
  "Every record in `files.edn`, in file order — a map carrying an `:id`. No
   manifest at all is `[]`. A line that does not parse is skipped, and so is one
   that parses to something that is not a record: a bad line loses one name,
   never the file.

   The one place the manifest is read, so that `names` and `retired` cannot
   drift into two reading rules for the same file. What each does with a record
   is its own business; getting them off the disk — and deciding what counts as
   one — is shared.

   **`(map? rec)` and `(:id rec)` are HERE and not in the callers**, because the
   commit that first split this reader out proved the drift it exists to
   prevent. `names` kept the guard it had always had, `retired` was written
   without one, and measured 2026-08-22 that is visible: `#{:id :retired}` is a
   readable line and not a map, `(:id #{:id :retired})` is the keyword `:id`, so
   it entered the retired set as an id; `{:retired true}` entered it as nil; and
   `{:name \"Ghost\"}` would have entered `names` under a nil key. None of those
   shapes can come from `remember!` or `retire!`, which are the only writers and
   write whole maps — the reason to fix it is not reachability, it is that one
   rule in one place is the whole of what this function is for. The `:name` test
   stays up in `names`; see there for why it cannot come down too.

   **Read line by line with `edn/read-string`, and NOT with the whole-reader
   `edn/read` `loci.chunks` uses.** The difference is what one torn line costs,
   and it is worse than it looks. `edn/read` over a reader does not know where a
   line ends: given `{:id \"b\" :name` it keeps going, takes the NEXT record as
   `:name`'s value, and then runs out of input still looking for the brace that
   would close the outer map. Measured 2026-08-21 against exactly that file: the
   torn record AND the good record after it are both lost, and so is everything
   appended later. Reading one line at a time and dropping the ones that do not
   parse loses exactly the line that was torn.

   **`line-seq` over a reader rather than `str/split-lines` over a `slurp`.**
   Measured 2026-08-22, the two agree on every shape this file actually takes —
   the torn fixture, a last line with no trailing newline, CRLF, a blank line in
   the middle — and part on two. An empty file is `[]` against `[\"\"]`, which
   is invisible downstream because `edn/read-string` answers nil for both. A
   lone `\\r` is not. `line-seq` splits on it, `split-lines` does not, and this
   reader's whole disposition is to lose as little as possible of a manifest
   that is PRIMARY and cannot be rebuilt. `line-seq` also streams a file that
   only ever grows. Neither reason is large; together they are enough to leave
   it alone.

   `a-lone-carriage-return-still-parts-two-records` is the fixture that holds
   this, and it exists because the paragraph above could not. Measured
   2026-08-22, swapping this back to `(str/split-lines (slurp f))` ran the rest
   of the namespace at 0 failures — every other fixture parts on `\\n`, where
   the two agree — and the loss is SILENT: `edn/read-string` given two records
   glued by a lone `\\r` answers the first and drops the second without
   throwing, so nothing downstream can tell the manifest came back short."
  []
  (let [f (manifest-file)]
    (if-not (.exists f)
      []
      (with-open [r (io/reader f)]
        (into [] (comp (keep (fn [line] (try (edn/read-string line) (catch Exception _ nil))))
                       (filter (fn [rec] (and (map? rec) (:id rec)))))
              (line-seq r))))))

(defn- latest
  "`{id record}` — the last record `files.edn` carries for each `:id`, from ONE
   read of the file.

   **Last-wins per id, which is not the same as the last record in the file.**
   That distinction is the whole of `retired`'s correctness and it now has one
   implementation; `retirement-is-last-wins-per-id-and-not-per-file` is the
   fixture that can see the difference.

   It exists because a reader that wants more than one thing about an id should
   not pay for the file twice. `records` costs ~43 ms over 10,000 lines
   (measured 2026-08-22), and `classify` needs three facts per id — is it in the
   manifest, is it retired, and when was it written. Asking `names`, `retired`
   and a third reader for those is three parses of the same file for one panel
   that draws on open.

   **`names` deliberately does not read through this**, and that is not an
   oversight to tidy up later. `names`' rule is *the last record that HAS a
   name*, not *the name on the last record* — see its docstring — so folding it
   in here would change what it answers for a record with no `:name` following
   one that had one. Nothing in `src/` writes that shape, which is exactly why
   the difference would go unnoticed."
  []
  (reduce (fn [m r] (assoc m (:id r) r)) {} (records)))

(defn names
  "`{id name}` for every file the manifest remembers. Last-wins by `:id`.

   No manifest at all is `{}` and not an error. A file placed by hand, by
   `rsync`, or by a mounted directory is a legitimate way for `dir` to hold
   content loci never named — `listing` offers such a file under its hash — so
   an absent or partial manifest is an ordinary state, not a broken one.

   **A record with no `:name` contributes nothing, and that is this function's
   own rule rather than the reader's.** `records` decides what is a record;
   this decides what is a name, and since `retire!` the test drops the
   retirement of an id loci never named, whose `:name` is a present-and-nil key.

   **Moving it down into `records` would put that id into the map under a nil
   name — and the caller that would notice is not `listing`.** `listing` builds
   its row with `(named n)`, which is nil either way, so the row comes out with
   no `:name` KEY AT ALL rather than a blank one; measured 2026-08-22,
   `(contains? row :name)` is false. What the nil entry would break is every
   caller that counts, enumerates or `contains?`-tests this map, starting with
   the claim that a loci which has never named a file answers `{}` —
   `loci.server-test`'s `(is (empty? (files/names)))` for an untitled paste
   rests on exactly that, and a nil-named entry would make it a map of one."
  []
  (reduce (fn [acc rec]
            (if (:name rec)
              (assoc acc (:id rec) (:name rec))
              acc))
          {}
          (records)))

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

(defn retire!
  "Mark `id`'s file as reclaimed, keeping its name. Returns the record written.

   An appended `{:id :name :at :retired true}` line — `remember!`'s idiom with
   one more key, and appended for the reason the whole manifest is append-only.
   **`files.edn` is PRIMARY.** A reclaim that rewrote the file to drop the id's
   line would be the one operation in loci that can lose a name for good: the
   bytes are gone by then, the hash never carried the name, and no other record
   in loci keeps it. So the line stays and gains a tombstone, and `names` reads
   it exactly as before.

   **Pass `original` when you already have it — the one-argument arity is O(N)
   in the manifest.** It resolves the name with `(get (names) id)`, which
   re-reads and re-parses the whole file; called once that is nothing, called in
   a loop it is quadratic. Measured 2026-08-22 over 83-byte lines, a fresh
   manifest per size: retiring 250 files costs 0.42 s, 500 costs 1.66 s and
   1,000 costs 7.68 s — a little over ×4 per doubling, which puts 2,000 near
   30 s (measured separately at 27.9 s) and 10,000 at roughly twelve minutes.
   Handed the name, the same three runs cost 0.005 s, 0.009 s and 0.018 s —
   linear, about 18 µs a file, so 10,000 is a quarter of a second. The files GC
   retires in a loop, inside an HTTP route the storage panel drives, and it has
   already computed the name in the row it is reclaiming from. The one-argument
   arity stays for a caller that has only an id.

   `:name` is carried forward rather than left to be looked up later, so the
   record is self-contained: a `files.edn` hand-edited, or truncated by
   something outside loci, still answers what the file was called from its tail
   alone. **Nothing in `src/` does that** — the only writes to this file are two
   `spit … :append true`, and the one loss it can suffer on its own, a torn
   line, happens before the retire rather than after it, so the tombstone would
   have been written `:name nil` anyway. This is a hedge on a primary file, not
   a mechanism anything here depends on. It is one map key, and one map key is
   worth it.

   Retiring an id loci never named is not an error and writes `:name nil`. A
   hand-placed file has no name to carry, inventing one would lie, and the GC
   never reclaims one anyway; this only has to not throw."
  ([id] (retire! id (get (names) id)))
  ([id original]
   (let [rec {:id id :name original :at (System/currentTimeMillis) :retired true}
         f   (manifest-file)]
     (io/make-parents f)
     (spit f (str (pr-str rec) "\n") :append true)
     rec)))

(defn retired
  "The set of ids whose most recent manifest record is a retirement.

   **Last-wins per id, which is not the same as the last record in the file.**
   Reducing into a map keyed by `:id` is what makes re-uploading the same bytes
   un-retire them while every other id keeps whatever its own last record said.
   Reading only the file's final line would pass any fixture that touches one id
   — measured 2026-08-22 — and answer `#{}` for a store where one file was
   reclaimed and a different one was then imported.

   No guard on the record here, and that is `records` doing it rather than this
   forgetting: everything it hands back is a map with an `:id`."
  []
  (->> (latest) vals (filter :retired) (map :id) set))

;; ---------------------------------------------------------------------------
;; the listing the picker draws
;; ---------------------------------------------------------------------------

(defn- connections
  "`{uri -> table-id}` over every object in `st` that names a source uri, the id
   being the first in sort order among the tables sharing one uri.

   **One pass, because `listing` needs an answer per file.** `connected-as`
   below is the single-uri question and is what `loci.server/import-csv!` asks;
   asking it once per file would make `listing` O(files × objects), and the
   files GC calls `listing` for a panel that draws on open. A directory of
   10,000 files against a store of 10,000 objects is a hundred million
   comparisons. The store is scanned once and the listing does lookups.

   (This paragraph said the GC's `report` \"walks the listing twice\" until
   2026-08-22, when `report` was written to walk it once and hand the rows to
   `classify` — see `classify`'s last section. The multiplier was never the
   point; one pass would be the shape at one walk too.)

   The `compare` is `sort`'s own comparator, kept as a running minimum rather
   than sorting each group: same answer, no intermediate seq per uri. An object
   with no `:id` sorts before every string exactly as `sort` puts nil first, so
   it answers nil and `listing` adds no key — which is what the filter-and-sort
   this replaces already did.

   **The accumulator is tested with `contains?` and not with `get`, and that is
   load-bearing.** A winner already found may itself be nil — it is the
   no-`:id` object above — and `get` cannot tell that from a uri seen for the
   first time. The two tidier spellings both break exactly that case:
   `(if-let [prev (get m u)] …)` and `(update m u (fnil …))` treat the nil
   winner as no winner, so whichever object was committed second overwrites it.
   Measured 2026-08-22 over a nil-`:id` object and `tbl:a` sharing one uri: the
   `get` spelling answers `\"tbl:a\"` with the nil committed first and nil with it
   committed second, while this one answers nil in both orders, which is what
   `(map :id) sort first` did.

   **\"Same answer\" has one measured exception.** Because the whole map is
   built, a single incomparable pair of ids ANYWHERE in the store makes this
   throw `ClassCastException`, where the per-uri scan it replaced threw only
   when asked about that uri. Measured 2026-08-22 with a numeric `:id` beside a
   string one on `x.csv`: asked about `y.csv`, the old scan answers `tbl:ok`
   and this throws. Reaching it needs a hand-crafted `commit!` — `loci.source`'s
   `mint-id` only ever makes `tbl:<slug>` strings — so it is not worth
   defending against, only worth not claiming immunity from."
  [st]
  (reduce (fn [m o]
            (if-let [u (:uri (:source o))]
              (let [id (:id o)]
                (if (and (contains? m u) (not (neg? (compare id (get m u)))))
                  m
                  (assoc m u id)))
              m))
          {}
          (vals (sub/objects st))))

(defn connected-as
  "The id of the table already bound to `uri`, or nil.

   **First in sort order, not oldest.** That is what `loci.server/import-csv!`'s
   `:duplicateOf` has always answered, and this exists so both call sites give
   one answer. Sort order over `tbl:` strings is not age, not relevance and not
   anything a person would have picked — it is arbitrary, and it stays that way
   because **one call site changing the meaning is worse than the answer being
   arbitrary**: the panel would name one duplicate before the click and the
   paste box a different one after it, for the same file, and a person would
   read that as loci having lost track of which table it meant. So making this
   one 'better' on its own is the failure and not the improvement. A better rule
   is welcome — here, once, for both.

   Reported so the panel can warn, never so it can refuse: two tables from one
   file is a thing someone may well want, and loci's answer to a mistake is
   `undo!`, not a veto.

   A lookup into `connections` rather than its own scan, so that the rule for
   which duplicate is meant has exactly one implementation. Building the whole
   map to answer one uri costs one pass either way — the filter this replaced
   was also a pass over every object — and the alternative is the panel and the
   paste box each carrying their own copy of the rule."
  [st uri]
  (get (connections st) uri))

(defn listing
  "What is in `dir`, as `[{:file :uri :bytes :name :connectedAs} …]` **sorted by
   `:file`**.

   Two mechanisms write this directory, both content-addressed:
   `loci.server/import-csv!` for a pasted CSV, and `POST /api/source/upload` for
   an uploaded one — which finishes through `finish-upload!` above. A person may
   also drop a file in by hand or mount one. So the listing is of the DIRECTORY rather than of any
   record loci keeps: a file loci did not put there still connects, and
   pretending otherwise would make the picker lie about what is available.

   `:name` is the manifest's memory of what an upload was called, and is absent
   for anything loci never named — absent rather than defaulted to `:file`, so
   the shell can tell a remembered name from a hash and draw them differently.

   `:connectedAs` is the id of a table this file is ALREADY the source of, and
   is absent when there is none — absent rather than nil or false, for the same
   reason `:name` is. **It is why this takes a store**, and why the answer lands
   here rather than on `describe`: the panel fetches the listing before you pick,
   so a duplicate can be named before the click instead of after it, and
   `describe` stays one thing — a file read that answers with columns. A warning,
   never a veto: two tables from one file is a thing someone may well want.

   **Sorted, and that is not cosmetic.** `File.listFiles` promises no order at
   all; measured 2026-08-21 it answers creation order here and the docs allow
   hash order elsewhere. Unsorted, the picker reshuffles its rows between two
   opens of the same panel with nothing having changed, which is the kind of
   motion a person reads as *something happened*.

   `.partial` never appears. It is where a chunked upload accumulates —
   `append-chunk!` writes it, `finish-upload!` empties it and `sweep-partials!`
   clears what neither did — and a half-arrived file is not something the picker
   may offer."
  [st]
  (let [named (names)
        conn  (connections st)]
    (->> (.listFiles (dir))
         (filter content?)
         (sort-by (fn [^java.io.File f] (.getName f)))
         (mapv (fn [^java.io.File f]
                 (let [n (.getName f)
                       u (uri n)]
                   (cond-> {:file n :uri u :bytes (.length f)}
                     (named n) (assoc :name (named n))
                     (conn u)  (assoc :connectedAs (conn u)))))))))

;; ---------------------------------------------------------------------------
;; the GC — which files could go, mirroring `loci.lakegc`
;; ---------------------------------------------------------------------------

(def default-window
  "How much history a reclaim keeps: whichever of these keeps more.

   `:days` transfers from `loci.lakegc/default-window` unchanged — a file named
   by any handle in the last 30 days stays. **`:versions` needed reinterpreting,
   because a file has no versions.** Here it is the three most recent FILES per
   object: an object re-uploaded or reconnected names a different content hash
   each time, and those are its versions.

   Whichever keeps more wins, for `lakegc`'s reason: a count alone drops a busy
   afternoon's work, an age alone puts no ceiling on a table reconnected forty
   times in that afternoon."
  {:versions 3 :days 30})

(defn- uri-of
  "The file a handle-walk entry names, or nil.

   `:source` as well as the handle, because a materialised handle is
   `{:store :lake :table …}` and names no location at all — only the `:put` of
   the whole object carries the uri, and a refresh's `:assoc [:value]` carries
   none. `tbl/event-handles` explains at length why it hands back both.

   **The nil answer is not an edge case, it is most of the walk.** Every
   materialised refresh contributes one, and `classify` filters on this
   *before* ranking for exactly that reason — see there."
  [e]
  (or (:uri (:source e)) (:uri (:handle e))))

(defn- keepier
  "Of two classifications for one uri, the one that retains more.

   The window's two halves are independent reasons to keep a file, so they
   merge independently: the lowest rank any object gives it, and the newest
   moment any object named it. Taking the pair from one entry would let a stale
   rank cancel a fresh timestamp.

   `:object` follows the RANK rather than the clock, so that the object named
   in the row is the one whose window is holding the file — which is what a
   panel offering a reclaim has to say. When the two halves disagree there is no
   single object the row is about, and this picks the one a reader would go
   looking for.

   Nil for `a` is a uri seen for the first time, so this doubles as the seed and
   `update` needs no `fnil`."
  [a b]
  (if (nil? a)
    b
    {:object   (if (< (:age-rank b) (:age-rank a)) (:object b) (:object a))
     :ts       (max (or (:ts a) 0) (or (:ts b) 0))
     :age-rank (min (:age-rank a) (:age-rank b))}))

(defn classify
  "`{filename {:class … :object … :ts …}}` for every file in `dir`.

   Five classes. **`:foreign`** — no manifest entry, so a person put it there
   and loci never touches it. **`:current`** — named by a live object, including
   every live passthrough table, whose file is re-read on every query.
   **`:in-window`** — inside `window`, so undo and `as-of` stay exact.
   **`:reclaimable`** — named only by history, outside it. **`:orphaned`** —
   remembered by the manifest, named by nothing at all, and old enough that
   nothing is about to.

   Walks `sub/history` through `tbl/handles-in-history` rather than current
   state, and takes the uri from `:source` as well as from the handle: a
   materialised source's handle is `{:store :lake :table …}` and names no
   location, so a refresh drops the uri and only the original `:put` carries it.

   The `cond` order is load-bearing twice. **`:foreign` first**, so a file with
   no manifest entry can never reach another class by any route — that is the
   one rule this GC cannot get wrong, because deleting a hand-placed file
   destroys data loci never created and no storage panel can undo it. (A
   hand-placed file that IS connected therefore reports `:foreign` rather than
   `:current`. Both answers are safe — neither is ever reclaimed — and the order
   is chosen so `:foreign` means exactly *no manifest entry*, with no
   exceptions to carry in your head.) **`:current` before the window**,
   mirroring `lakegc` and `source-block`'s precedence lesson from phase 2: a
   live passthrough table's file is not a retention question at all.

   ## `:current` before `:orphaned`, which is the other way round in `lakegc`

   The pair above is *not* mirrored, and it needs saying because the sentence
   before it says the order mirrors `lakegc`. `lakegc/classify` tests
   `(nil? m)` **before** `(live table_name)`, and `reclaim!`'s docstring spends
   a paragraph on why: a gap in `tbl/event-handles`' coverage — a new event
   shape that can carry a handle — then surfaces as a *live* table classified
   `:orphaned`, and `reclaim!`'s live-set guard refuses the **whole call** on
   it rather than filtering it out. That inverted order is a tripwire, and the
   refusal is what makes someone read the gap.

   Here the order is `(live uri)` first, and for files that is the better
   answer: a connected file is not an orphan by any reading, and `:orphaned`
   here is an ordinary state — an upload waiting to be picked — rather than
   evidence of anything. But it does convert `lakegc`'s tripwire into silent
   absorption: the same walk gap, on a file a live object still names, comes
   back `:current` and nothing anywhere says the walk missed a handle.

   What partly covers it is that `live` is built from `connections`, which
   reads the objects' own `:source` and never the walk — so the two disagree
   independently, and a gap on a LIVE file is caught by `live` answering
   correctly on its own. The hole that stays open is a gap on a file whose
   table is deleted but still undo-reachable: no walk entry, not live, so it
   falls to `:orphaned` and is aged by the manifest's `:at` — the moment the
   file ARRIVED, not the moment it was last named. A file uploaded forty days
   ago and disconnected yesterday would be reclaimed. That is the cost of the
   order, written down rather than argued away.

   ## `:orphaned` respects the window here, and in `lakegc` it does not

   A deliberate divergence, stated because being quietly different from the
   namespace this one mirrors is how the difference gets refactored away.
   `lakegc/classify` says orphans *\"ignore the window because nothing can ever
   reference them again\"*, and for a lake table that is true: the only way one
   comes into being is loci minting it and committing a handle, so no handle
   means the commit never landed or was undone.

   **For a file it is false, and not by a narrow margin.** `finish-upload!`
   calls `remember!` and does not connect — the shell says so itself: *\"an
   upload is a file ARRIVING in loci's directory and not a second way to connect
   one\"* — so the panel connects it in a LATER request, which may come minutes
   afterwards or never. Between the two, the file is in the manifest and named
   by nothing in history, which is precisely `:orphaned`. That is not a race; it
   is the ordinary state of an uploaded file waiting to be picked, and a reclaim
   that ignored the window would delete the 200 MiB somebody just uploaded.
   `import-csv!` has the same shape one request wide: it `remember!`s before
   `source-connect!` commits, and http-kit answers from a worker pool, so two
   handlers do interleave.

   So an orphan younger than the cutoff is `:in-window`. One clause and no new
   mechanism: it is aged by its own manifest record's `:at`, which `remember!`
   already writes and which is the right clock for *loci wrote this file at T*.
   Those rows carry `:at` and a nil `:ts` rather than putting the manifest's
   clock in `:ts` — `:ts` is a log timestamp everywhere else in this map, and a
   file nothing names has no log entry to take one from.

   ## One read of the manifest

   `classify` is what `report` calls, and the storage panel draws `report` on
   open. An earlier draft called `(retired)` inside a `keep` over
   `(keys (names))` — one full parse of `files.edn` per manifest entry, which at
   ~43 ms per parse is about **seven minutes** over 10,000 files.

   Everything this needs from the manifest comes from one `latest`: whether loci
   wrote the file, whether it has since given it up, and when. One `classify`
   costs **2 manifest parses and 3 store walks** — its own `latest`,
   `connections` and `tbl/handles-in-history`, plus the `names` and
   `connections` inside `listing`. **If you add a manifest consumer here, take
   it off `recs` rather than calling `names` or `retired` again.**

   The three walks are not the same size, and an earlier draft of this
   paragraph said 2 and called the store axis ~1 ms. Both were wrong the same
   way: the instrumentation sat on `connections`, and `handles-in-history` is
   neither counted by that nor priced like it. Measured 2026-08-22 against a
   store whose `history` and `objects` are plain derefs — the shape `loci.dlv`
   has in production, not `EventStore`'s materialise-per-call — over 40,000
   events and 20,000 objects, half of them connected tables:

       connections           6 ms   (per call, and it is called twice)
       handles-in-history   26 ms
       one `names` parse    56 ms   over 10,000 lines of ~78 bytes — the
                                    ~43 ms quoted above for `records`, plus
                                    `names`' own fold over what it returns

   So the third walk is the **same order as a manifest parse**, not the
   rounding error the old figure implied. And it is the one term here that
   grows with the **log**, which only ever grows: a file deleted from `dir`
   stops costing anything on the two axes above it, and the events that named
   it stay in `sub/history` forever.

   The duplicated `names` parse stays anyway. Duplicating `content?` and the
   sort to avoid it would put the directory's rules in two places, which is the
   thing this namespace exists to prevent — and a review measured that parse at
   ~18% of one `classify` at the stated scale, so the argument for keeping it
   was never that it is free.

   (`sub/objects` is a protocol method, so `with-redefs` cannot count it. The
   store walks are counted at `connections` and `tbl/handles-in-history`, both
   plain vars — and counting only the first is how one of the three went
   unnoticed.)

   ## The rank is over FILES, and two objects on one file merge

   `seen` was copied from `lakegc`'s and is the one thing in this namespace that
   could not be transferred. A lake table name is minted fresh per write, so
   there every entry is a distinct table, ranking entries ranks versions, and no
   key ever repeats. **A file uri repeats, and entries that name no uri exist.**
   Two independent reviews measured the copied expression classifying live,
   undo-reachable files `:reclaimable`; the fixtures under \"the shapes every
   fixture above this line is missing\" in `loci.files-test` are the arrangements
   that show it, and each carries the shipped answer beside the corrected one.

   So `filter uri-of` runs **before** `map-indexed`, because a rank is a
   position among an object's FILES — `default-window` says \"the three most
   recent FILES per object\" on its face, and ranking events instead let a
   materialised `refresh!`, whose `:assoc [:value]` names no location, age out
   its own source. And the fold is `keepier` rather than `into {}`, because
   last-wins over a newest-first walk takes the OLDEST mention of a repeated
   uri, and because two objects on one file — the ordinary state `:connectedAs`
   and `:duplicateOf` exist for — otherwise get whichever one the fold happened
   to finish on.

   ## The three-argument arity takes a listing you already have

   `report` needs the same rows this does — `:bytes` per file is on them — and
   calling `listing` at both levels would walk the directory twice for one
   answer. The walk is the largest single term here. Measured 2026-08-22 over
   10,000 files with a 10,000-line manifest and an EMPTY store, so that the
   figures are the directory-and-manifest axis alone and the store walks above
   sit on top of them unchanged: `listing` is 68 ms of one `classify`'s 104 ms,
   and a `report` that walks twice costs 174 ms against 109 ms. That establishes
   the mechanism's shape rather than that anyone's panel is slow — the second
   walk is a little over a third of a report the storage panel draws on open,
   whatever the absolute number turns out to be on a real directory.

   Speed is the smaller half of the reason. Two walks are also **two different
   directories**: a file written between them is reported with no `:class` at
   all, and one deleted between them is classified and never reported. One walk
   makes the rows and the classes answers about the same instant by
   construction."
  ([st window] (classify st window (listing st)))
  ([st {:keys [versions days] :as _window} rows]
   (let [recs   (latest)
         ;; `mine` is `(keys (names))` minus `retired`, read off the one map
         ;; rather than from two more parses.
         ;;
         ;; **The `:name` test is this function's rule and NOT `names`'.** An
         ;; earlier comment here claimed it was `names`' own rule kept so the two
         ;; sets could not drift; that is not what either one does. `names` is
         ;; *the last record that HAS a name* — `latest`'s docstring is explicit
         ;; that folding it in would change its answer — while this is *the last
         ;; record, and does it have a name*. They part on a nameless record
         ;; following a named one. Measured 2026-08-22 over a hand-written
         ;; `{:id "a.csv" :name "Alpha"}` followed by `{:id "a.csv" :at 1}`:
         ;; `names` answers `{"a.csv" "Alpha"}` and this file comes back
         ;; `:foreign`.
         ;;
         ;; That shape is unreachable from `src/` — both `remember!` callers
         ;; guard with `not-empty` (`server.clj`'s import route and
         ;; `finish-upload!` below), and `retire!`'s nameless record carries
         ;; `:retired`, which the clause above drops first. A hand-edited or
         ;; truncated manifest reaches it, which is why the divergence is written
         ;; down rather than removed: `:foreign` is the never-reclaimed side, so
         ;; where the two rules part this one takes the direction a GC over a
         ;; primary file must take. `a-nameless-record-after-a-named-one-parts-
         ;; mine-from-names` is the fixture.
         ;;
         ;; **`remove :retired` is what makes a retirement mean loci gave the
         ;; file up.** Re-uploading the same bytes calls `remember!`, which
         ;; un-retires the id and puts it straight back in `mine`. Bytes that
         ;; reappear WITHOUT loci — a restore, an rsync, a re-mounted volume —
         ;; are a file loci did not write, and this is what returns them to
         ;; `:foreign` so a second reclaim cannot delete them.
         mine   (into #{} (comp (remove #(:retired (val %)))
                                (filter #(:name (val %)))
                                (map key))
                      recs)
         ;; **`connections` and not a third spelling of the same store walk.**
         ;; Measured 2026-08-22, `(set (keys (connections st)))` and the
         ;; `(keep #(:uri (:source %)) (vals (sub/objects st)))` this replaces are
         ;; identical by construction — `keep` drops exactly the nils
         ;; `connections`' `if-let` skips — and this namespace already had that
         ;; walk written once for `:connectedAs`. Not for speed: a scan is ~6 ms
         ;; over a 20,000-object store (measured 2026-08-22, half of them
         ;; connected tables) and `objects` is an atom deref in production. For
         ;; the reason `connected-as` gives about itself — which uri counts as
         ;; connected is one rule, and two spellings of it are two things to keep
         ;; in step.
         ;;
         ;; **The keys, not the rows `listing` already carries.** A file's
         ;; `:connectedAs` is absent when the object holding the uri has no
         ;; `:id`, and reading liveness off that key would call such a file
         ;; unreferenced while an object is still reading it. Under-coverage is
         ;; the unsafe direction for both GCs; the key set has neither hole.
         live   (set (keys (connections st)))
         cutoff (- (System/currentTimeMillis) (* days 24 60 60 1000))
         ;; **Rank among the object's URI-BEARING entries only, and merge per uri
         ;; toward whichever answer retains more.** Both halves of that sentence
         ;; are corrections to `lakegc`'s `seen`, which this was copied from and
         ;; which is right there and wrong here — see the note in the docstring.
         ;;
         ;; `filter` before `map-indexed`, so a rank is a position among FILES.
         ;; `keepier` rather than `into {}`, so a uri named twice does not take
         ;; whichever mention the fold happened to finish on.
         seen   (reduce (fn [acc [o hs]]
                          (reduce (fn [a [i e]]
                                    (update a (uri-of e) keepier
                                            {:object o :ts (:ts e) :age-rank i}))
                                  acc
                                  (map-indexed vector (filter uri-of hs))))
                        {}
                        (tbl/handles-in-history st))]
     (into {}
           (for [{:keys [file uri]} rows
                 :let [m (get seen uri)]]
             [file
              (cond
                (not (mine file)) {:class :foreign :object nil :ts nil}
                (live uri)        (assoc (or m {}) :class :current)
                (nil? m)          (let [at (:at (get recs file))]
                                    ;; **A missing `:at` means KEEP.** `(or at 0)`
                                    ;; would read an unreadable clock as the epoch —
                                    ;; maximally old — and send the record straight to
                                    ;; `:orphaned`, the one class a reclaim deletes with
                                    ;; no object to warn about. `files.edn` is PRIMARY
                                    ;; and cannot be recomputed, so a GC over it fails
                                    ;; toward keeping. Measured 2026-08-22: under
                                    ;; `default-window`, `{:id "x.csv" :name "X"}` came
                                    ;; back `:orphaned`.
                                    {:class  (if (or (nil? at) (>= at cutoff))
                                               :in-window :orphaned)
                                     :object nil :ts nil :at at})
                (or (< (:age-rank m) versions)
                    (>= (or (:ts m) 0) cutoff)) (assoc m :class :in-window)
                :else             (assoc m :class :reclaimable))])))))

(defn report
  "What every file in `dir` costs, and what a reclaim would free. **Deletes
   nothing.**

   `report` IS the dry run, and that is why `reclaim!` has no dry-run flag — the
   shape `loci.lakegc` keeps, for the reason its own docstring gives: the report
   names exactly the files the reclaim will delete and the bytes it will free,
   so a flag would only add a second way to ask the same question, with the
   destructive answer as the default one.

   `:files` carries EVERY file and not only the doomed ones. A storage panel has
   to say what the directory COSTS as well as what it can free, and `lakegc`
   learned that the hard way — it reports `:catalog` beside `:files` because a
   reclaim that freed every reclaimable byte in the lake looked, against a
   catalog that never shrinks, like it had done nothing. Each row is its
   `listing` row — `:file`, `:uri`, `:bytes`, and `:name`/`:connectedAs` where
   there are any — with `classify`'s `:class`, `:object` and `:ts` merged onto
   it. `:name` is on the row for `reclaim!`'s sake; see there.

   **One `listing`, handed to `classify`.** 174 ms → 109 ms over 10,000 files,
   and rows and classes that describe one instant rather than two; `classify`'s
   last section has the measurement and the sharper half of the reason.

   No `:window` key, unlike `lakegc/report`. Every caller here passes the window
   in and holds it; echoing it back would be a second place for it to be wrong."
  [st window]
  (let [rows (listing st)
        cls  (classify st window rows)
        all  (mapv #(merge % (get cls (:file %))) rows)
        recl (filterv #(= :reclaimable (:class %)) all)]
    {:files             all
     :reclaimable       recl
     :reclaimable-bytes (reduce + 0 (map :bytes recl))
     :total-bytes       (reduce + 0 (map :bytes all))}))

(defn reclaim!
  "Delete every `:reclaimable` file and retire its manifest line. Irreversible.

   The bytes go and the NAME STAYS. `retire!` appends a tombstone rather than
   rewriting `files.edn`, because that file is PRIMARY and cannot be rebuilt: a
   reclaim that dropped the line would be the one operation in loci that can
   lose a name for good, since by then the bytes are gone and the hash never
   carried it.

   **`retire!` is handed the name off the row, not left to look it up.** The
   one-argument arity resolves it with `(get (names) id)`, a full parse of the
   manifest per file, which in a loop is quadratic — measured 2026-08-22,
   retiring 2,000 files costs 27.9 s that way against 0.040 s with the name in
   hand. `report` has already read the manifest and put the name on the row.

   **Every candidate gets a row, and a failure carries `:error` rather than
   vanishing.** The spec asks for it by name — *a reclaim that cannot delete a
   file reports which and why* — and the alternative is worse than it looks: a
   `:when` filter answers a SHORTER vector, and a caller comparing its count
   against `report`'s reads a total failure as a completed sweep. The name is
   **not** retired on a failed delete, because the bytes are still there and a
   tombstone would say they were not.

   A file that vanished between `report` and here is not an error; it is the
   outcome the caller asked for. `.delete` answers false for a file that no
   longer exists, so the check is `deleted OR already gone`.

   ## The gap between classifying and deleting, written down rather than closed

   Nothing holds a lock across the two, and none is wanted: loci is
   single-writer for the STORE, but http-kit answers from a worker pool and
   handlers do interleave — `server.clj:1037` records 48 of 64 tag colours lost
   to exactly that, which is measurement and not theory. So a
   `POST /api/source/connect` naming a `:reclaimable` file's uri, landing after
   `classify` read `connections` and before the `.delete` below, connects a
   table whose bytes this then removes.

   Reachable, and left open. **Every available check narrows the window without
   closing it**, and shipping a mechanism that cannot deliver the property it is
   named for is worse than the gap being written here. Re-reading `connections`
   immediately before each delete moves the exposure from the report's duration
   to a few instructions and still loses the same race, at the ~6 ms per call
   `classify` records over a 20,000-object store — 60 s across 10,000 files,
   against a `report` that is meant to be cheap enough to draw a panel with.
   Re-running `classify` per file is the whole classification again, which is
   the 104 ms measured in its own docstring before a single object is in the
   store.

   What it costs when it does happen is bounded and already drawn: a passthrough
   table becomes `:table/unavailable`, the state phase 2 draws and offers a
   reconnect for, and a materialised table loses nothing it is currently
   reading — its rows are in the lake — only the ability to `refresh!`. Closing
   this properly means one writer for the directory as well as the store, which
   is a decision about the server and not about this function."
  [st window]
  (vec (for [{:keys [file bytes] :as row} (:reclaimable (report st window))
             :let [f    (io/file (dir) file)
                   gone (or (.delete f) (not (.exists f)))]]
         (if gone
           (do (retire! file (:name row))
               {:file file :bytes bytes})
           {:file  file :bytes bytes
            :error (str "loci could not delete " file " — it is still in "
                        (.getPath (dir)))}))))

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
