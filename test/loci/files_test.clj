(ns loci.files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.files :as files]
            [loci.lake-fixture :as lf]
            [loci.substrate :as sub]))

(defn- with-dir
  "Run `f` with `sub/data-dir` answering a fresh temp directory, removed after.

   The shape `loci.server-test/with-temp-data-dir` already uses, and for the
   same reason: `loci.files` resolves both the manifest and the `.partial`
   directory through that ONE var, so redefining it is what keeps a write and
   the read that follows it in the same place."
  [f]
  (let [d (str (System/getProperty "java.io.tmpdir") "/loci-files-" (System/nanoTime))]
    (try (with-redefs [sub/data-dir (constantly d)] (f d))
         (finally (lf/rm-rf d)))))

(deftest a-name-round-trips-and-last-write-wins
  (with-dir
    (fn [_]
      (is (= {} (files/names)) "no file yet is not an error")
      (files/remember! "abc123.csv" "sales-2026.csv")
      (is (= {"abc123.csv" "sales-2026.csv"} (files/names)))
      (files/remember! "abc123.csv" "sales-final.csv")
      (is (= {"abc123.csv" "sales-final.csv"} (files/names))
          "append-only, last-wins by id — the same rule chunks and memory keep"))))

(deftest a-corrupt-line-is-skipped-rather-than-fatal
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "one.csv")
      (spit (io/file (sub/data-dir) "files.edn") "{:id \"b.csv\" :name  \n" :append true)
      (files/remember! "c.csv" "three.csv")
      (is (= #{"a.csv" "c.csv"} (set (keys (files/names))))
          "a half-written line loses one name, never the file")
      (is (= "three.csv" (get (files/names) "c.csv"))
          "and the record AFTER the torn one is read whole, not eaten as its value"))))

(deftest a-lone-carriage-return-still-parts-two-records
  ;; `records` keeps `line-seq` where the plan said `(str/split-lines (slurp
  ;; f))`, and the ONLY shape the two disagree on is a lone `\r`: every other
  ;; fixture in this namespace parts on `\n`, where they agree. Measured
  ;; 2026-08-22, swapping `records` back to `split-lines` ran the whole
  ;; namespace at 0 failures without this fixture — which is how a future agent
  ;; reconciling the code against the plan would be told the swap is safe.
  ;;
  ;; And the loss it lets through is SILENT. `(edn/read-string "{:id \"a\"}\r{:id
  ;; \"b\"}")` answers `{:id "a"}` and drops the rest with no exception, so the
  ;; manifest comes back one name short and nothing says so.
  (with-dir
    (fn [_]
      (let [f (io/file (sub/data-dir) "files.edn")]
        (io/make-parents f)
        (spit f "{:id \"a.csv\" :name \"Alpha\"}\r{:id \"b.csv\" :name \"Bravo\"}\n"))
      (is (= {"a.csv" "Alpha" "b.csv" "Bravo"} (files/names))
          "a lone \\r ends a line for `line-seq` and not for `str/split-lines`,
           and this manifest is PRIMARY — it loses as little as it can"))))

(deftest a-line-that-parses-but-is-not-a-record-contributes-nothing
  ;; The guard `(map? rec)` and `(:id rec)` lives in `records` so `names` and
  ;; `retired` cannot answer from two rules — which is the whole reason
  ;; `records` was extracted, and which it failed on its first commit: `names`
  ;; kept the guard and `retired` was written without one.
  ;;
  ;; The fixture is what makes the guard visible, and it has to carry junk that
  ;; PARSES — a fixture of well-formed records passes with the guard deleted and
  ;; certifies nothing. Measured 2026-08-22 with the guard removed: `retired`
  ;; answers `#{"a.csv" :id nil}` — `#{:id :retired}` is a readable line that is
  ;; not a map and `(:id #{:id :retired})` is the keyword `:id`, while
  ;; `{:retired true}` has no id at all and lands under nil — and `names`
  ;; answers `{"a.csv" "Alpha", nil "Ghost"}`.
  ;;
  ;; None of these can come from `remember!` or `retire!`, the only two writers.
  ;; A hand-edited manifest can, and the rule being one rule is the point.
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "Alpha")
      (spit (io/file (sub/data-dir) "files.edn")
            "#{:id :retired}\n{:name \"Ghost\"}\n{:retired true}\n"
            :append true)
      (files/retire! "a.csv" "Alpha")
      (is (= #{"a.csv"} (files/retired))
          "a line that is not a record contributes no id to retire")
      (is (= {"a.csv" "Alpha"} (files/names))
          "and none to name either — one reading rule, in one place"))))

(deftest retiring-with-the-name-in-hand-writes-the-same-record
  ;; The two-argument arity exists because the one-argument one is O(N) in the
  ;; manifest — `(get (names) id)` re-reads and re-parses the whole file, so
  ;; retiring 1,000 files took 7.68 s against 0.018 s handed the name (measured
  ;; 2026-08-22, 83-byte lines). This is the correctness half: the two must
  ;; write the same record, or the fast path would quietly write a different
  ;; manifest from the slow one.
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "Alpha")
      (files/remember! "b.csv" "Bravo")
      (files/retire! "a.csv")
      (files/retire! "b.csv" "Bravo")
      (is (= #{"a.csv" "b.csv"} (files/retired)))
      (is (= {"a.csv" "Alpha" "b.csv" "Bravo"} (files/names))
          "the name handed in is carried forward exactly as the looked-up one is"))))

(deftest retiring-with-a-name-does-not-consult-the-manifest
  ;; The assertion above passes whether or not the second arity uses `original`
  ;; — `(get (names) id)` would answer "Bravo" too. This is the one that can
  ;; see it: a manifest that says something ELSE for the id, so a `retire!`
  ;; still resolving the name for itself writes the manifest's answer and not
  ;; the caller's. Measured 2026-08-22: with the body reverted to `(get (names)
  ;; id)`, this fails and the test above does not.
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "Stale")
      (files/retire! "a.csv" "Handed In")
      (is (= "Handed In" (get (files/names) "a.csv"))
          "the caller's name is what is written — the manifest is not consulted"))))

(deftest retiring-a-name-keeps-it-readable-and-marks-it
  (with-dir
    (fn [_]
      (files/remember! "abc.csv" "Q3 Takings")
      (is (= #{} (files/retired)) "nothing is retired before anything is")
      (files/retire! "abc.csv")
      (is (= "Q3 Takings" (get (files/names) "abc.csv"))
          "the name survives the file — files.edn is primary, and a reclaim that lost
           a name would be the one operation in loci that cannot be undone")
      (is (= #{"abc.csv"} (files/retired)))
      (files/remember! "abc.csv" "Q3 Takings")
      (is (= #{} (files/retired))
          "and re-uploading the same bytes un-retires it — last-wins, the whole point"))))

(deftest retiring-a-name-loci-never-knew-is-not-an-error
  (with-dir
    (fn [_]
      (files/retire! "never-seen.csv")
      (is (= #{"never-seen.csv"} (files/retired)))
      ;; `(nil? (get (files/names) "never-seen.csv"))` was the obvious way to
      ;; write this and it cannot fail: the thing it guards against is the id
      ;; entering the map under a NIL NAME, and `get` answers nil for that
      ;; exactly as it does for absent. `= {}` is the assertion that can see it.
      (is (= {} (files/names))
          "a hand-placed file has no name to carry forward, and inventing one would
           lie — so the tombstone's nil `:name` keeps it out of the map entirely,
           rather than putting it in under a blank one"))))

(deftest a-tombstone-carries-the-name-and-so-stands-alone
  ;; With an intact manifest the `:name` on a tombstone is invisible: the
  ;; `remember!` line before it says the same thing, so `names` answers
  ;; correctly whether or not the tombstone carries one. Measured 2026-08-22,
  ;; twice — a `retire!` writing no `:name` at all ran 0 failures against the
  ;; tests above, and so did a `names` that skipped every `:retired` record.
  ;; Both are caught here, by the one way this file already loses a line: with
  ;; the earlier line gone, the tombstone is the only record of the name, and
  ;; `names` must read it like any other.
  (with-dir
    (fn [_]
      (let [f (io/file (sub/data-dir) "files.edn")]
        (files/remember! "abc.csv" "Q3 Takings")
        (files/retire! "abc.csv")
        (spit f (str (last (str/split-lines (slurp f))) "\n"))
        (is (= {"abc.csv" "Q3 Takings"} (files/names))
            "the tombstone is self-contained — losing the line before it does not
             lose the name, and `names` needs no special case to read it")
        (is (= #{"abc.csv"} (files/retired)))))))

(deftest retirement-is-last-wins-per-id-and-not-per-file
  ;; The two tests above touch ONE id each, so in both of them the last record
  ;; in the file is also the last record for the id under test — a `retired`
  ;; that read only the file's final line would pass every assertion they make.
  ;; Measured 2026-08-22 by writing exactly that: 0 failures. Two ids, with the
  ;; file ending on the one that is NOT retired, is what makes the per-id
  ;; reduction load-bearing.
  (with-dir
    (fn [_]
      (files/remember! "a.csv" "Alpha")
      (files/retire! "a.csv")
      (files/remember! "b.csv" "Bravo")
      (is (= #{"a.csv"} (files/retired))
          "the file's last record belongs to b, and a is still retired")
      (is (= {"a.csv" "Alpha" "b.csv" "Bravo"} (files/names))
          "and both names are still readable — retiring a is not forgetting it"))))

(deftest sweeping-partials-removes-the-abandoned-and-keeps-the-fresh
  (with-dir
    (fn [_]
      (let [p (doto (io/file (sub/data-dir) "files" ".partial") .mkdirs)
            old (io/file p "old") new (io/file p "new")]
        (spit old "x") (spit new "y")
        (.setLastModified old (- (System/currentTimeMillis) (* 25 60 60 1000)))
        (is (= ["old"] (files/sweep-partials!)) "returns what it removed")
        (is (not (.exists old)))
        (is (.exists new) "an upload in flight is not garbage")))))

(deftest sweeping-with-no-partial-directory-at-all-is-not-an-error
  ;; The test above makes `.partial` itself, so it cannot see the case boot
  ;; hits on every loci that has never taken an upload: no directory, and
  ;; `.listFiles` answering nil rather than an empty array.
  (with-dir
    (fn [_]
      (is (= [] (files/sweep-partials!))))))

;; ---------------------------------------------------------------------------
;; the listing the picker draws. These five moved here from `loci.server-test`
;; with `loci.files/listing`: they are assertions about what the DIRECTORY
;; contains and how it is ordered, and leaving them beside the route while the
;; rules live here is the same split that let `.partial` be described in one
;; namespace and created in another.
;; ---------------------------------------------------------------------------

(deftest listing-is-what-is-in-the-directory
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        ;; Reverse lexical on purpose — see the ordering test below.
        (spit (io/file dir "hand-placed.csv") "b\n2\n")
        (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
        (.mkdirs (io/file dir ".partial"))
        (spit (io/file dir ".partial" "half") "not done")
        (let [r (files/listing (sub/fresh-store))]
          (is (= #{"72b441a9c3e05f18.csv" "hand-placed.csv"} (set (map :file r)))
              "both files, and never the .partial working directory")
          (is (= 4 (:bytes (first (filter #(= "72b441a9c3e05f18.csv" (:file %)) r))))
              "the real length of \"a\\n1\\n\", not merely some number — a hashCode would be one of those")
          (is (= "loci://files/hand-placed.csv"
                 (:uri (first (filter #(= "hand-placed.csv" (:file %)) r))))
              "and the loci:// spelling sourcespec/locate resolves"))))))

(deftest listing-is-in-name-order-and-not-in-disk-order
  ;; `File.listFiles` promises no order at all; measured 2026-08-21 it answers
  ;; CREATION order here, and the docs allow hash order elsewhere. So a fixture
  ;; written in sorted order comes back sorted whether or not anything sorted
  ;; it — which is exactly how the `sort-by` in the code this moved from went
  ;; five tests without a single assertion that could see it. Writing in
  ;; reverse is what makes the sort the only thing that can produce this vector.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (doseq [n ["zulu.csv" "mike.csv" "kilo.csv" "alpha.csv"]]
          (spit (io/file dir n) "x"))
        (is (= ["alpha.csv" "kilo.csv" "mike.csv" "zulu.csv"]
               (mapv :file (files/listing (sub/fresh-store))))
            "a picker that reshuffles between two opens reads as something having happened")))))

(deftest listing-is-empty-and-not-an-error-before-anything-is-imported
  (with-dir
    (fn [_] (is (= [] (files/listing (sub/fresh-store)))))))

(deftest listing-skips-directories-that-are-not-dotfiles
  ;; The tests above only ever put a DIRECTORY under a dot-prefixed name
  ;; (`.partial`), so they cannot tell the dotfile filter from the directory
  ;; filter — either alone would pass them. A subdirectory with an ordinary
  ;; name is what makes the `.isDirectory` half of `content?` load-bearing.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "real.csv") "a\n1\n")
        (.mkdirs (io/file dir "subdir"))
        (spit (io/file dir "subdir" "nested.csv") "a\n1\n")
        (is (= ["real.csv"] (mapv :file (files/listing (sub/fresh-store))))
            "a subdirectory is not content, dotfile-named or not")))))

(deftest listing-skips-dotfiles-that-are-not-directories
  ;; The other fixtures only ever dot-prefix a DIRECTORY (`.partial`), so the
  ;; `isDirectory` half already protects them — dropping the dotfile half by
  ;; itself changes nothing they can see. A dot-prefixed ORDINARY FILE is what
  ;; makes it load-bearing.
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "real.csv") "a\n1\n")
        (spit (io/file dir ".DS_Store") "junk")
        (is (= ["real.csv"] (mapv :file (files/listing (sub/fresh-store))))
            "a dotfile is not content, directory or not")))))

(deftest listing-reports-the-manifest-name-for-a-file-loci-named
  (with-dir
    (fn [_]
      (let [dir (doto (files/dir) .mkdirs)]
        (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
        (spit (io/file dir "hand-placed.csv") "b\n2\n")
        (files/remember! "72b441a9c3e05f18.csv" "sales-2026.csv")
        (let [by-file (into {} (map (juxt :file identity)) (files/listing (sub/fresh-store)))]
          (is (= "sales-2026.csv" (:name (by-file "72b441a9c3e05f18.csv")))
              "the listing reads the real manifest")
          (is (nil? (:name (by-file "hand-placed.csv")))
              "and a file loci never named has no name, rather than borrowing one"))))))

;; ---------------------------------------------------------------------------
;; `:connectedAs` — which file is already a table, said on the LISTING, which
;; is what the panel fetches before you pick. On `describe` it would arrive
;; after the click, and would make `describe` two things.
;; ---------------------------------------------------------------------------

(deftest connected-as-names-the-table-already-bound-to-a-uri
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (is (nil? (files/connected-as st "loci://files/a.csv"))
            "nothing connected is nil, not a blank string")
        ;; `tbl:sales` FIRST and `tbl:again` second, on purpose. `fresh-store`
        ;; answers `objects` as a small array-map, which iterates in insertion
        ;; order — measured 2026-08-22 — so committing them the other way round
        ;; would make the unsorted answer and the sorted answer the same string
        ;; and the assertion below could not see the `compare` at all. Phase 2
        ;; hit this on `listing`'s own sort and had to write its fixture in
        ;; reverse lexical order for the same reason.
        (sub/commit! st {:op :put :id "tbl:sales"
                         :value {:id "tbl:sales" :source {:uri "loci://files/a.csv"}}})
        (sub/commit! st {:op :put :id "tbl:again"
                         :value {:id "tbl:again" :source {:uri "loci://files/a.csv"}}})
        (is (= ["tbl:sales" "tbl:again"] (mapv :id (vals (sub/objects st))))
            "the fixture's premise, asserted rather than assumed: iteration order is
             NOT sort order here, which is the only thing that makes the next line
             an assertion about sorting")
        (is (= "tbl:again" (files/connected-as st "loci://files/a.csv"))
            "first in sort order — the same answer import-csv! already gives, and one
             call site changing the meaning is worse than the answer being arbitrary")
        (is (nil? (files/connected-as st "loci://files/b.csv"))
            "and a different uri is not connected just because something is")))))

(deftest listing-marks-the-file-that-is-already-a-table
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "a.csv") "x\n1\n")
        (spit (io/file (files/dir) "b.csv") "y\n2\n")
        (sub/commit! st {:op :put :id "tbl:sales"
                         :value {:id "tbl:sales" :source {:uri "loci://files/a.csv"}}})
        (let [by-file (into {} (map (juxt :file identity)) (files/listing st))]
          (is (= {:file "a.csv" :uri "loci://files/a.csv" :bytes 4 :connectedAs "tbl:sales"}
                 (get by-file "a.csv"))
              "the whole row, so the id is the table's own and not something derived
               from the file name")
          ;; The whole map again, and NOT `(nil? (:connectedAs …))`. That form
          ;; answers the same for a missing key and a present-and-nil one, so a
          ;; `cond->` that always assoc'd would pass it — the exact defect
          ;; found in Task 4 of this branch an hour before this was written.
          (is (= {:file "b.csv" :uri "loci://files/b.csv" :bytes 4}
                 (get by-file "b.csv"))
              "absent, not false and not nil — the shell tells a remembered value from
               a missing one")
          (is (not (contains? (get by-file "b.csv") :connectedAs))
              "said directly as well, so the reason this test is written with whole
               maps does not get lost in a later edit"))))))
