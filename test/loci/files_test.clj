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
          ;; The whole row, and NOT `(nil? (:name …))`. `:name`'s contract is
          ;; ABSENT, not nil — it is the precedent `:connectedAs` cites for its
          ;; own shape — and `(nil? (:name …))` answers the same for a missing key
          ;; as for a present-and-nil one, so a `listing` that always assoc'd
          ;; would pass it. Measured 2026-08-22 with the `(named n)` guard
          ;; dropped: the old spelling failed nothing, and both of these fail.
          (is (= {:file "hand-placed.csv" :uri "loci://files/hand-placed.csv" :bytes 4}
                 (by-file "hand-placed.csv"))
              "and a file loci never named has no name, rather than borrowing one")
          (is (not (contains? (by-file "hand-placed.csv") :name))
              "said directly as well, so the reason this is a whole-map comparison
               does not get lost in a later edit"))))))

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
        ;; THREE tables on one uri, in the order mike, alpha, zulu, and that is
        ;; the whole fixture. `fresh-store` answers `objects` as a small
        ;; array-map — measured 2026-08-22, and three entries is well under the
        ;; 8-entry boundary where it becomes a hash-map — so iteration is
        ;; insertion order and the three candidate rules give three different
        ;; strings: sorted-minimum `tbl:alpha`, first-committed `tbl:mike`,
        ;; last-committed `tbl:zulu`.
        ;;
        ;; TWO tables would not do it. With `tbl:sales` then `tbl:again` the
        ;; sorted minimum is also the LAST write, so `connections` simplified to
        ;; an unconditional `(assoc m u id)` — the obvious "this is just a
        ;; group-by" cleanup, which silently makes the rule last-connected-wins
        ;; — answers `tbl:again` and passes. A third id in the middle of sort
        ;; order is what makes both accidents visible at once, and this is the
        ;; only place the ordering rule is pinned:
        ;; `importing-one-file-twice-writes-it-once-and-says-so` has one prior
        ;; table and exercises no ordering at all.
        (doseq [id ["tbl:mike" "tbl:alpha" "tbl:zulu"]]
          (sub/commit! st {:op :put :id id
                           :value {:id id :source {:uri "loci://files/a.csv"}}}))
        (let [ids  (mapv :id (vals (sub/objects st)))
              best (first (sort ids))]
          ;; The fixture's premise as the PROPERTY, not as the ids. The earlier
          ;; spelling — `(is (= ["tbl:sales" "tbl:again"] ids))` — protects the
          ;; store's iteration order but not the fixture: rename the ids so that
          ;; insertion order happens to equal sort order and this line gets
          ;; updated in the same edit, both lines pass, and the assertion below
          ;; is vacuous again, which is the exact state it exists to prevent.
          ;; Said this way it survives a rename, or fails and says why.
          (is (not= best (first ids))
              "the sorted minimum is not the first thing iteration hands back, so
               first-committed-wins gives a different answer than the line below")
          (is (not= best (last ids))
              "nor the last, so last-committed-wins — an unconditional assoc — gives
               a different answer too"))
        (is (= "tbl:alpha" (files/connected-as st "loci://files/a.csv"))
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

;; ---------------------------------------------------------------------------
;; the GC classifier
;;
;; **Do not backdate an event to age it out.** `EventStore/commit!` does
;; `(assoc event :ts (System/currentTimeMillis))` (`substrate.clj:133`) — it
;; OVERWRITES any `:ts` a caller supplies, silently, so a test that commits a
;; 90-day-old event gets one stamped now and its window assertion becomes
;; vacuous. `loci.lakegc`'s own tests never backdate either; they shrink the
;; window (`lakegc_test.clj:38`). These do the same, and go one further: every
;; window assertion below is a PAIR over one fixture, differing only in the
;; window, because a single `:in-window` assertion against an event stamped now
;; is true whatever the arithmetic does.
;; ---------------------------------------------------------------------------

(def ^:private nothing-retained
  ;; Retain nothing: no versions, and a cutoff in the FUTURE so no event can be
  ;; inside the age half either. `:days 0` would put the cutoff at now, and an
  ;; event committed in the same millisecond satisfies `(>= ts cutoff)` and
  ;; comes back `:in-window` — a rare flake that reads as a real failure.
  {:versions 0 :days -1})

(def ^:private age-only
  ;; The age half alone: `default-window`'s 30 days with the version count off,
  ;; so an `:in-window` answer can only have come from the cutoff arithmetic.
  ;; `default-window` itself cannot say that — with `:versions 3`, an object's
  ;; newest file is age-rank 0 and `(< 0 3)` short-circuits the `or` before the
  ;; cutoff is ever compared, so the sign of `cutoff` is invisible to it.
  {:versions 0 :days 30})

(def ^:private count-only
  ;; And the version half alone, for the same reason in the other direction.
  {:versions 1 :days -1})

(defn- put-source!
  "Commit `id` as a connected table over `uri`, materialised or passthrough.

   A `:put` of the whole object, which is the shape `loci.source/connect!`
   commits and the shape `tbl/event-handles` reads a `:source` off — the uri
   lives on the object, and for a materialised table the handle beside it names
   a lake table and no location at all."
  [st id uri mode]
  (sub/commit! st {:op :put :id id
                   :value {:id id :source {:uri uri :mode mode}
                           :value (if (= mode :passthrough)
                                    {:store :source :uri uri :format :csv}
                                    {:store :lake :table (str "t_" id) :snapshot 1})}}))

(deftest classify-keeps-a-file-a-live-table-still-names
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "a.csv") "x\n")
        (files/remember! "a.csv" "kept")
        (put-source! st "tbl:a" "loci://files/a.csv" :materialise)
        (is (= :current (:class (get (files/classify st nothing-retained) "a.csv")))
            "a live object's file is never reclaimable however old — under a window
             that retains nothing, `:current` is the only thing that can save it")
        ;; **And under a window that WOULD keep it, which is the assertion that
        ;; sees the `cond` order.** Measured 2026-08-22: moving `(live uri)`
        ;; below the window check fails nothing under `nothing-retained`,
        ;; because there the window branch is false and `live` is still reached.
        ;; Precedence between two branches is only visible where BOTH would
        ;; fire, so it takes a window that retains — here `default-window`,
        ;; under which this file is also age-rank 0 and stamped now.
        (is (= :current (:class (get (files/classify st files/default-window) "a.csv")))
            "and `:current` rather than `:in-window` when the window would have kept
             it too — a live table's file is not a retention question, and the panel
             must not offer a countdown on a file that can never expire")))))

(deftest classify-reclaims-a-file-loci-wrote-that-nothing-names-any-more
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (files/remember! "old.csv" "gone")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [row (get (files/classify st nothing-retained) "old.csv")]
          (is (= :reclaimable (:class row))
              "no live object names it and its only mention is outside the window")
          ;; This is also the one test that can see the uri being taken from
          ;; `:source` rather than off the handle. `put-source!` commits a
          ;; MATERIALISED table, whose handle is `{:store :lake :table …}` and
          ;; names no location — so a classify reading `(:uri (:handle e))` only
          ;; finds no entry for this uri, and the file comes back `:orphaned`
          ;; with no `:object` instead. Measured 2026-08-22 by making exactly
          ;; that edit. The live case above CANNOT see it: `live` is built from
          ;; the objects' own `:source`, so it answers `:current` either way.
          (is (= "tbl:a" (:object row))
              "and it is attributed to the table that named it — which only the
               `:source` half of the walk can supply for a materialised table"))))))

(deftest classify-never-reclaims-a-file-loci-did-not-write
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "x\n")
        (spit (io/file (files/dir) "connected.csv") "x\n")
        (put-source! st "tbl:c" "loci://files/connected.csv" :passthrough)
        (let [cls (files/classify st nothing-retained)]
          (is (= :foreign (:class (get cls "placed.csv")))
              "no manifest entry means a person put it there — rsync, a mount, a
               volume — and deleting it would destroy data loci never created")
          ;; The precedence pin. `:foreign` is FIRST in the `cond`, so a
          ;; hand-placed file that is also connected stays `:foreign` rather
          ;; than becoming `:current`. Both answers are safe — neither class is
          ;; ever reclaimed — and the order is what makes `:foreign` mean
          ;; exactly "no manifest entry" with no exception to remember.
          (is (= :foreign (:class (get cls "connected.csv")))
              "and a hand-placed file that IS connected is still `:foreign`, because
               `:foreign` is tested before `:current` and means one thing only"))))))

(deftest classify-never-reclaims-a-live-passthrough-tables-file
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "live.csv") "x\n")
        (files/remember! "live.csv" "live")
        (put-source! st "tbl:p" "loci://files/live.csv" :passthrough)
        (is (= :current (:class (get (files/classify st nothing-retained) "live.csv")))
            "it is re-read on every query — reclaiming it turns a working table
             into :table/unavailable, and no window makes that acceptable")
        (is (= :current (:class (get (files/classify st files/default-window) "live.csv")))
            "and `:current` under a window that would also have kept it, which is
             what pins `live` ABOVE the window in the `cond` — see the note in
             `classify-keeps-a-file-a-live-table-still-names`")))))

(deftest classify-keeps-a-file-inside-the-window-and-reclaims-it-outside
  ;; ONE fixture, two windows, and that is the whole design of this test. The
  ;; event is stamped now — `commit!` sees to that — so a lone `:in-window`
  ;; assertion under `default-window` is true whatever `cutoff` computes, and
  ;; true even with the version half doing all the work. Measured 2026-08-22:
  ;; against `default-window` a flipped `cutoff` sign changes nothing here,
  ;; because age-rank 0 satisfies `(< 0 3)` and the `or` never reaches the age
  ;; comparison. `age-only` turns the version half off, so the two answers below
  ;; differ ONLY in the sign and size of the cutoff, and a sign flip fails both.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "recent.csv") "x\n")
        (files/remember! "recent.csv" "recent")
        (put-source! st "tbl:a" "loci://files/recent.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (is (= :in-window (:class (get (files/classify st age-only) "recent.csv")))
            "deleted a moment ago — undo and as-of can still reach it, which is what
             the 30 days are for")
        (is (= :reclaimable (:class (get (files/classify st nothing-retained) "recent.csv")))
            "and the same file with the cutoff moved past it is reclaimable — the
             pair is what makes the line above an assertion about the cutoff")))))

(deftest classify-keeps-the-newest-file-an-object-named-and-not-the-one-before-it
  ;; The version half, which nothing else here can see: every other fixture
  ;; names one file per object, so `age-rank` is always 0 and `(< 0 versions)`
  ;; is true for any positive count. Two files for one object — a reconnect,
  ;; which is how an object comes to have file versions at all — is what makes
  ;; the rank load-bearing, and the window keeps by count alone.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "v1.csv") "x\n")
        (spit (io/file (files/dir) "v2.csv") "x\n")
        (files/remember! "v1.csv" "first") (files/remember! "v2.csv" "second")
        (put-source! st "tbl:a" "loci://files/v1.csv" :materialise)
        (put-source! st "tbl:a" "loci://files/v2.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [cls (files/classify st count-only)]
          (is (= 0 (:age-rank (get cls "v2.csv")))
              "newest first — the fixture's premise, asserted rather than assumed,
               because a walk that answered oldest-first would keep the wrong file
               and every class below would still read correctly")
          (is (= :in-window (:class (get cls "v2.csv")))
              "the one most recent file is kept by count with the age half switched off")
          (is (= :reclaimable (:class (get cls "v1.csv")))
              "and the one before it is not, which is the only thing that says the
               count is being compared to anything"))))))

(deftest classify-finds-a-file-named-only-inside-a-tx
  ;; THE test the lifted walk exists for — and the version of it that can fail.
  ;;
  ;; Asserting `:current` alone proves nothing. Measured 2026-08-22: with the
  ;; `:tx` branch of `tbl/event-handles` replaced by nil, this file still comes
  ;; back `:current`, because `live` is built from `(sub/objects st)` and the
  ;; store applies a `:tx`'s sub-events whatever the walk does. The walk's
  ;; contribution is the ATTRIBUTION — `:object` and `:age-rank` — which a
  ;; top-level-only walk cannot supply, so `(or m {})` leaves the row with the
  ;; `:class` key and nothing else.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "txd.csv") "x\n")
        (files/remember! "txd.csv" "in a tx")
        (sub/commit! st {:op :tx
                         :events [{:op :put :id "tbl:t"
                                   :value {:id "tbl:t" :source {:uri "loci://files/txd.csv"}
                                           :value {:store :lake :table "t_t" :snapshot 1}}}
                                  {:op :put :id "note:t" :value {:id "note:t" :value "x"}}]})
        (is (= {:class :current :object "tbl:t" :age-rank 0}
               (select-keys (get (files/classify st nothing-retained) "txd.csv")
                            [:class :object :age-rank]))
            "a live table committed inside a `:tx` keeps its file, and the walk is
             what says WHOSE file it is — `tools/save-table!` commits a `:tx`
             whenever it also appends a notebook cell")
        (sub/commit! st {:op :delete :id "tbl:t"})
        (is (= {:class :reclaimable :object "tbl:t" :age-rank 0}
               (select-keys (get (files/classify st nothing-retained) "txd.csv")
                            [:class :object :age-rank]))
            "and once the table is gone the file is reclaimable AS ITS FILE — a
             top-level-only walk calls it `:orphaned` with no object, which is the
             shape that would let a reclaim run against a live table's source")))))

(deftest classify-gives-a-just-uploaded-file-the-window-lakegc-denies-an-orphan
  ;; The deliberate divergence from `loci.lakegc`, which says orphans "ignore
  ;; the window because nothing can ever reference them again". True of a lake
  ;; table; false of a file, and not narrowly.
  ;;
  ;; `finish-upload!` calls `remember!` and does NOT connect — the shell says so
  ;; itself: "an upload is a file ARRIVING in loci's directory and not a second
  ;; way to connect one" — so between the last chunk landing and the Connect
  ;; click there is a file in the manifest that nothing in history names. That
  ;; is `:orphaned` by the letter of it, and it may last minutes or forever.
  ;; This fixture IS that state: `remember!` and nothing else.
  (with-dir
    (fn [_]
      (let [st  (sub/fresh-store)
            _   (.mkdirs (files/dir))
            _   (spit (io/file (files/dir) "fresh.csv") "x\n")
            rec (files/remember! "fresh.csv" "just uploaded")
            row (get (files/classify st files/default-window) "fresh.csv")]
        (is (= :in-window (:class row))
            "an upload nobody has connected yet is inside the window, not an orphan
             a reclaim may delete out from under the panel about to connect it")
        (is (= (:at rec) (:at row))
            "and it is aged by the manifest record's own `:at` — the clock
             `remember!` already writes, which is the only one a file nothing names
             has. Asserting the exact value rather than `some?` is what stops a
             hard-coded `:at` or a `(System/currentTimeMillis)` recomputed here
             from passing")
        (is (nil? (:ts row))
            "`:ts` stays nil, because `:ts` is a LOG timestamp everywhere else in
             this map and there is no log entry to take one from")
        (is (= :orphaned (:class (get (files/classify st nothing-retained) "fresh.csv")))
            "and with the cutoff moved past it, the same file IS an orphan — the
             pair is what makes the first assertion about the window rather than
             about `:orphaned` being unreachable")))))

(deftest a-retired-file-whose-bytes-come-back-is-foreign-again
  ;; What `mine`'s `remove :retired` actually buys, which is not what it looks
  ;; like. A reclaimed file that comes back THROUGH LOCI needs no code at all:
  ;; `remember!` appends, `retired` is last-wins per id, so the id un-retires
  ;; itself and `(keys (names))` alone would have answered correctly. Measured
  ;; 2026-08-22 — a `mine` that ignores `retired` passes that scenario.
  ;;
  ;; The case it is for is bytes returning from OUTSIDE loci: a restore from
  ;; backup, an rsync, a re-mounted volume. loci's own record says it gave that
  ;; file up, so whatever is at that name now, loci did not write it — and
  ;; `:foreign` is what stops a second reclaim deleting somebody's restore.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "back.csv") "x\n")
        (files/remember! "back.csv" "Backup")
        (files/retire! "back.csv")
        (is (= :foreign (:class (get (files/classify st nothing-retained) "back.csv")))
            "retired, and the bytes are there again by some route that was not loci —
             so it is a hand-placed file now, and never reclaimable")
        (is (= "Backup" (get (files/names) "back.csv"))
            "the name outlives the retirement either way — files.edn is primary")
        (files/remember! "back.csv" "Backup")
        (is (= :orphaned (:class (get (files/classify st nothing-retained) "back.csv")))
            "and re-uploading it through loci un-retires the id, so it is loci's
             file again — reclaimable in principle, which `:foreign` never is")))))
