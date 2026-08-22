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

(def ^:private default-versions-only
  ;; `default-window`'s OWN version half with the age half off — `{:versions 3
  ;; :days -1}`, written as an `assoc` so it cannot drift from the shipped
  ;; default. `classify-ranks-the-files-an-object-named-and-not-the-events`
  ;; needs the real 3: the defect it pins is a table refreshed three times
  ;; falling out of the very window loci ships, and quoting 3 by hand would let
  ;; the default change underneath the claim.
  (assoc files/default-window :days -1))

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

(defn- refresh!
  "Re-run `id`'s recipe, the shape `loci.source/refresh!` actually commits.

   A `:tx` of two `:assoc`s and never a `:put` — see that function's own note on
   why. Only the first of the two yields a handle-walk entry: the `[:source]`
   one carries a source map, which names no `:store`, so `tbl/event-handles`
   does not recognise it as a handle at all.

   **The mode is the whole point of this helper.** A passthrough refresh's new
   handle is `{:store :source :uri …}` and NAMES the file; a materialised
   refresh's is `{:store :lake :table …}` and names no location whatsoever. So
   one mode gives the walk a second uri-bearing entry for a file it already had,
   and the other gives it an entry with no uri at all — the two shapes every
   fixture above this line is missing, and the two the ranking gets wrong."
  [st id uri mode n]
  (sub/commit! st {:op :tx
                   :events [{:op :assoc :id id :path [:value]
                             :value (if (= mode :passthrough)
                                      {:store :source :uri uri :format :csv}
                                      {:store :lake :table (str "t_" id "_" n) :snapshot n})}
                            {:op :assoc :id id :path [:source]
                             :value {:uri uri :mode mode :at (str n)}}]}))

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

;; ---------------------------------------------------------------------------
;; the shapes every fixture above this line is missing
;;
;; Two independent reviews of `classify` measured it calling live,
;; undo-reachable files `:reclaimable`, and the assertions above were not the
;; hole — the FIXTURES were. Every one of them names one file per object and
;; each file exactly once, which is the one arrangement in which ranking events
;; and ranking files agree, in which a last-wins fold has nothing to overwrite,
;; and in which no two objects can disagree about a uri. The four below are the
;; arrangements that fall outside it, and each is ordinary: a refresh, a
;; materialised refresh, two tables from one file, and a manifest line loci did
;; not write.
;; ---------------------------------------------------------------------------

(deftest classify-ranks-the-files-an-object-named-and-not-the-events
  ;; `:versions` is "the three most recent FILES per object" — `default-window`
  ;; says so on its face. The shipped fold spent a rank on every walk entry and
  ;; dropped the uri-less ones afterwards, so it counted EVENTS.
  ;;
  ;; A materialised `refresh!` is the event that has no uri: its `:assoc
  ;; [:value]` carries `{:store :lake :table …}`, which names no location. So an
  ;; object with exactly ONE file aged itself out by being refreshed. Measured
  ;; 2026-08-22 against this fixture under `default-versions-only`:
  ;;
  ;;     refreshes = 0   ->  {:age-rank 0,  :class :in-window}
  ;;     refreshes = 3   ->  {:age-rank 3,  :class :reclaimable}
  ;;     refreshes = 10  ->  {:age-rank 10, :class :reclaimable}
  ;;
  ;; Three is not an arbitrary count: it is `default-window`'s own `:versions`,
  ;; so the third refresh of a table is what pushed its source out of the window
  ;; loci ships. Refreshing a table was the thing that made its source deletable.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "one.csv") "x\n")
        (files/remember! "one.csv" "one")
        (put-source! st "tbl:a" "loci://files/one.csv" :materialise)
        (refresh! st "tbl:a" "loci://files/one.csv" :materialise 1)
        (refresh! st "tbl:a" "loci://files/one.csv" :materialise 2)
        (refresh! st "tbl:a" "loci://files/one.csv" :materialise 3)
        (is (= "t_tbl:a_3" (:table (:value (sub/object st "tbl:a"))))
            "the fixture's premise, asserted rather than assumed: three refreshes
             landed and each replaced the handle, so the walk really does carry
             four entries for one file")
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [row (get (files/classify st default-versions-only) "one.csv")]
          (is (= 0 (:age-rank row))
              "one file means rank 0 however many times the table was refreshed —
               the rank is a position among the object's FILES")
          (is (= :in-window (:class row))
              "so `default-window`'s three versions still cover it, which is the
               whole claim the count half makes"))
        (is (= :reclaimable (:class (get (files/classify st nothing-retained) "one.csv")))
            "and the pair, so the line above is an assertion about the count rather
             than about `:in-window` being unreachable"))))) 

(deftest classify-dates-a-file-by-the-newest-mention-and-not-the-first
  ;; `handles-in-history` is newest-first per object and the shipped fold was
  ;; `(into {} …)`, which is last-wins — so for a uri named more than once the
  ;; OLDEST mention won, on both halves at once.
  ;;
  ;; A passthrough refresh is the shape that exposes it: its new handle is
  ;; `{:store :source :uri …}` and DOES name the file, so the walk gets a second
  ;; uri-bearing entry for a uri it already had. A passthrough table connected
  ;; five weeks ago and refreshed this morning therefore read as five weeks
  ;; stale, and `:age-rank` reported its distance from the object's newest event
  ;; rather than 0.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "p.csv") "x\n")
        (files/remember! "p.csv" "p")
        (put-source! st "tbl:p" "loci://files/p.csv" :passthrough)
        ;; A real clock and not a backdated event: `commit!` overwrites `:ts`,
        ;; so the only way to put two mentions on either side of a line is to
        ;; let time pass between them and take the line from the same clock.
        (Thread/sleep 25)
        (let [mid (System/currentTimeMillis)]
          (refresh! st "tbl:p" "loci://files/p.csv" :passthrough 1)
          (refresh! st "tbl:p" "loci://files/p.csv" :passthrough 2)
          (sub/commit! st {:op :delete :id "tbl:p"})
          (let [row (get (files/classify st count-only) "p.csv")]
            (is (>= (:ts row) mid)
                "the connect is before `mid` and both refreshes are after it, so this
                 holds only if the NEWEST mention supplied `:ts` — a file is as
                 recent as the last thing that named it")
            (is (= 0 (:age-rank row))
                "and rank 0, because it is still the only file this object has ever
                 had — a repeat mention is not an older version")
            (is (= :in-window (:class row))
                "which is what it costs to get wrong: with the age half off, the
                 shipped rank of 2 put a table refreshed moments ago outside a
                 one-version window")))))))

(deftest classify-merges-two-tables-on-one-file-toward-keeping-it
  ;; Two tables from one file is the ordinary state this whole phase exists for
  ;; — `:connectedAs` warns about it and `:duplicateOf` names it — and the
  ;; shipped fold picked ONE of them arbitrarily. Measured 2026-08-22 over a
  ;; fixture varying only in the number of UNRELATED objects, because `group-by`
  ;; iterates in insertion order below 8 keys and hash order above:
  ;;
  ;;      4 objects in the walk  ->  attributed to tbl:new  ->  :in-window
  ;;     14 objects in the walk  ->  attributed to tbl:old  ->  :reclaimable
  ;;
  ;; This fixture does not rely on that flip, because relying on it would make
  ;; the test a hostage to Clojure's array-map threshold. It is arranged so the
  ;; CORRECT answer is one no single entry carries: `tbl:new` has the better
  ;; rank and the older clock, `tbl:old` the newer clock and the worse rank. A
  ;; fold that takes the pair from whichever entry it happens to land on is
  ;; wrong on one half or the other whichever one it lands on, which is the
  ;; reason `keepier` merges the two halves independently.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (doseq [f ["shared.csv" "v1.csv" "v2.csv" "v3.csv"]]
          (spit (io/file (files/dir) f) "x\n")
          (files/remember! f f))
        ;; `tbl:new` names shared.csv and nothing else: rank 0, and the OLDER clock.
        (put-source! st "tbl:new" "loci://files/shared.csv" :materialise)
        (Thread/sleep 25)
        (let [mid (System/currentTimeMillis)]
          ;; `tbl:old` names shared.csv and then three other files: rank 3, and
          ;; the NEWER clock.
          (put-source! st "tbl:old" "loci://files/shared.csv" :materialise)
          (doseq [f ["v1.csv" "v2.csv" "v3.csv"]]
            (put-source! st "tbl:old" (str "loci://files/" f) :materialise))
          ;; Unrelated tables, deleted again, purely to push the walk's map over
          ;; the array-map threshold — the axis the shipped answer moved along.
          (dotimes [i 12]
            (put-source! st (str "tbl:z" i) (str "loci://files/z" i ".csv") :materialise)
            (sub/commit! st {:op :delete :id (str "tbl:z" i)}))
          (sub/commit! st {:op :delete :id "tbl:new"})
          (sub/commit! st {:op :delete :id "tbl:old"})
          (let [row (get (files/classify st default-versions-only) "shared.csv")]
            (is (= 0 (:age-rank row))
                "the lowest rank any object gives the file, not the rank of whichever
                 object the fold finished on — a file one table still counts among its
                 three newest is inside the window whatever a second table thinks")
            (is (= "tbl:new" (:object row))
                "and it is attributed to that object, so a panel offering a reclaim
                 names the table whose window is keeping the file")
            (is (>= (:ts row) mid)
                "while `:ts` comes from the OTHER object, whose mention is newer —
                 the two halves are two independent reasons to keep a file, and
                 taking the pair from one entry lets a stale rank cancel a fresh
                 clock or the reverse")
            (is (= :in-window (:class row))
                "which is the outcome: `:reclaimable` here deletes the source of a
                 table `undo!` can still reach, and the undo comes back
                 `:table/unavailable`")))))))

(deftest classify-keeps-a-manifest-record-whose-clock-it-cannot-read
  ;; `files.edn` is PRIMARY, so a GC over it has a direction it must fail in.
  ;; The shipped clause was `(>= (or at 0) cutoff)`, which reads a missing `:at`
  ;; as the epoch — maximally old — and sends the record straight to
  ;; `:orphaned`, the one class a reclaim deletes without an object to warn
  ;; about. Measured 2026-08-22: `:orphaned` under `default-window`.
  ;;
  ;; `remember!` has written `:at` since `8f96895`, so reaching this needs a
  ;; manifest loci did not write — which this namespace already fixtures
  ;; elsewhere (`a-lone-carriage-return-still-parts-two-records`), `retire!`'s
  ;; docstring reasons about at length, and a hand-edit or an older store
  ;; produces for free.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)
            f  (io/file (sub/data-dir) "files.edn")]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "noclock.csv") "x\n")
        (spit (io/file (files/dir) "stamped.csv") "x\n")
        (io/make-parents f)
        (spit f "{:id \"noclock.csv\" :name \"No clock\"}\n" :append true)
        (spit f "{:id \"stamped.csv\" :name \"Stamped\" :at 1}\n" :append true)
        (let [cls (files/classify st files/default-window)]
          (is (= :in-window (:class (get cls "noclock.csv")))
              "an unreadable clock means KEEP — a GC over the one file in loci that
               cannot be recomputed does not get to guess a file is old")
          (is (= :orphaned (:class (get cls "stamped.csv")))
              "and a record whose clock IS readable and says 1970 is an orphan, which
               is the pair that makes the line above about the missing key rather
               than about `:orphaned` being unreachable from a hand-edited line"))))))

(deftest a-nameless-record-after-a-named-one-parts-mine-from-names
  ;; `classify`'s `mine` filters `latest` on `:name`, and a comment here used to
  ;; call that "`names`' own rule kept deliberately, so the two sets cannot
  ;; drift". They are not the same rule and they do drift. `names` is *the last
  ;; record that HAS a name* — `latest`'s docstring says folding it in would
  ;; change its answer — while `mine` is *the last record, and does it have a
  ;; name*. A nameless record following a named one is where they part, and
  ;; before this fixture existed the whole clause could be deleted with the
  ;; namespace still at 0 failures.
  ;;
  ;; Nothing in `src/` writes the shape: both `remember!` callers guard with
  ;; `not-empty`, and `retire!`'s nameless record carries `:retired`, which
  ;; `mine` drops before it ever reaches the `:name` test. A hand-edited or
  ;; truncated `files.edn` writes it, which is the same reachability every other
  ;; manifest fixture in this namespace has.
  ;;
  ;; The point is the DIRECTION. `:foreign` is the class no reclaim ever
  ;; touches, so where the two rules disagree `classify` takes the never-delete
  ;; side — which is the only defensible way for a GC over a primary file to be
  ;; uncertain.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)
            f  (io/file (sub/data-dir) "files.edn")]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "a.csv") "x\n")
        (io/make-parents f)
        (spit f "{:id \"a.csv\" :name \"Alpha\" :at 1}\n{:id \"a.csv\" :at 1}\n" :append true)
        (is (= {"a.csv" "Alpha"} (files/names))
            "the fixture's premise: `names` still names the file, because its rule
             is the last record that HAS a name")
        (is (= :foreign (:class (get (files/classify st files/default-window) "a.csv")))
            "and `classify` does not claim it — the two rules part here, and this
             one takes the side a reclaim can never act on. Drop the `:name` test
             from `mine` and this file becomes `:orphaned`, which a reclaim
             deletes")))))

;; ---------------------------------------------------------------------------
;; `report` and `reclaim!` — the dry run, and the deletion
;;
;; `report` is the dry run, which is why `reclaim!` has no dry-run flag — the
;; shape `loci.lakegc` keeps. Every window below is `nothing-retained`, so an
;; `:in-window` answer cannot be what saves a file here: the only thing that can
;; is its CLASS, which is what these fixtures are about.
;; ---------------------------------------------------------------------------

(deftest report-says-what-a-reclaim-would-free-without-freeing-it
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        ;; THREE files of DIFFERENT sizes and three different classes, because
        ;; the obvious one-file fixture cannot see the mistake it is meant to
        ;; catch: with a single file in the directory `:total-bytes` and
        ;; `:reclaimable-bytes` are the same number, and a `report` that summed
        ;; every row into both passes `(= 10 (:reclaimable-bytes r))` green.
        ;;
        ;; The orphan is the third, and it is what makes `:doomed` a two-member
        ;; set rather than a rename: a `:doomed` that is still only the
        ;; `:reclaimable` rows answers 10 bytes and one row here.
        (spit (io/file (files/dir) "old.csv") "0123456789")
        (spit (io/file (files/dir) "keep.csv") "abc")
        (spit (io/file (files/dir) "orphan.csv") "xy")
        (files/remember! "old.csv" "gone")
        (files/remember! "keep.csv" "kept")
        (files/remember! "orphan.csv" "never picked")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (put-source! st "tbl:k" "loci://files/keep.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [r (files/report st nothing-retained)]
          (is (= ["old.csv" "orphan.csv"] (map :file (:doomed r)))
              "both classes a reclaim deletes — the file nothing names any more AND
               the one nothing ever named")
          (is (= 12 (:reclaimable-bytes r))
              "their actual lengths summed, not a count, and over BOTH classes: 10
               alone is the answer a `:reclaimable`-only sum gives")
          (is (= 15 (:total-bytes r))
              "and the two are different on this fixture, which is the whole reason
               there is a file in it that neither class covers")
          (is (= {"old.csv" :reclaimable "keep.csv" :current "orphan.csv" :orphaned}
                 (into {} (map (juxt :file :class)) (:files r)))
              "every file gets a row and a class, not only the doomed ones — a
               storage panel says what the directory COSTS as well as what it frees")
          (is (= "gone" (:name (first (:doomed r))))
              "and the row carries the manifest name, which is what `reclaim!` hands
               to `retire!` instead of re-parsing files.edn once per file")
          (is (.exists (io/file (files/dir) "old.csv"))
              "report is the dry run — there is no dry-run flag because of it"))))))

(deftest reclaim-deletes-a-stale-orphan-and-spares-the-upload-nobody-has-picked-yet
  ;; The pair that has to hold together, under the window loci SHIPS rather than
  ;; a test constant. `reclaim!` took `(:reclaimable (report …))` alone for one
  ;; commit, so a file loci wrote that nothing ever named — the ordinary state of
  ;; an upload nobody connected, and of a connect that was undone — was never
  ;; deleted and never even offered: measured, `[]` back and the file on disk. At
  ;; the spec's scale, ten stale 200 MiB uploads is 2 GB the panel does not
  ;; mention.
  ;;
  ;; And widening it is safe only because `classify` gives an orphan the window.
  ;; `fresh.csv` is the other half of the same assertion: the two corrections are
  ;; load-bearing for each other, and a `reclaim!` that took every orphan would
  ;; delete the file the panel is about to connect.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "stale.csv") (apply str (repeat 200 "x")))
        (spit (io/file (files/dir) "fresh.csv") "y\n")
        ;; `remember!` stamps `:at` from the clock and a manifest record is not
        ;; an event, so the forty days is WRITTEN rather than waited for. The
        ;; record's shape is `remember!`'s own, key for key.
        (spit (io/file (sub/data-dir) "files.edn")
              (str (pr-str {:id "stale.csv" :name "sales.csv"
                            :at (- (System/currentTimeMillis) (* 40 24 60 60 1000))})
                   "\n")
              :append true)
        (files/remember! "fresh.csv" "just uploaded")
        (is (= {"stale.csv" :orphaned "fresh.csv" :in-window}
               (into {} (map (juxt :file :class))
                     (:files (files/report st files/default-window))))
            "the premise, under `default-window`: one orphan past the cutoff and one
             inside it — so what follows is about which of them a reclaim takes")
        (let [r (files/reclaim! st files/default-window)]
          (is (= ["stale.csv"] (map :file (:files r)))
              "the orphan is a candidate — `:reclaimable` alone answers [] here")
          (is (= 200 (:freed r)))
          (is (not (.exists (io/file (files/dir) "stale.csv"))))
          (is (= "sales.csv" (get (files/names) "stale.csv"))
              "and its name outlives it, exactly as a `:reclaimable` file's does")
          (is (.exists (io/file (files/dir) "fresh.csv"))
              "while the upload waiting in the panel to be picked is untouched — the
               orphan grace is what makes deleting the other one safe"))))))

(deftest reclaim-deletes-the-file-retires-the-name-and-leaves-the-rest
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (spit (io/file (files/dir) "keep.csv") "y\n")
        (files/remember! "old.csv" "gone")
        (files/remember! "keep.csv" "kept")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (put-source! st "tbl:k" "loci://files/keep.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        (let [r (files/reclaim! st nothing-retained)]
          (is (= ["old.csv"] (map :file (:files r))))
          (is (nil? (:error (first (:files r)))) "a delete that worked carries no :error")
          (is (= 2 (:freed r))
              "and the bytes that actually went are summed here rather than at the
               caller — `x\\n` is two of them")
          (is (not (.exists (io/file (files/dir) "old.csv"))))
          (is (.exists (io/file (files/dir) "keep.csv")) "a live table's file is untouched")
          (is (= #{"old.csv"} (files/retired)))
          (is (= "gone" (get (files/names) "old.csv"))
              "the name outlives the bytes — files.edn is primary")
          (is (= "kept" (get (files/names) "keep.csv"))
              "and so does every OTHER name in the manifest, which is the property
               `retire!` appends for and which nothing here asserted before: with
               `retire!` rewriting files.edn to hold only the tombstone, the two
               assertions above still pass and this one comes back nil — a name
               destroyed for good, the one outcome a reclaim may never produce")
          ;; The name `reclaim!` HANDS to `retire!` is invisible while the
          ;; `remember!` line is still in the file — it answers "gone" too, so
          ;; `(retire! file nil)` fails nothing above. The technique
          ;; `a-tombstone-carries-the-name-and-so-stands-alone` uses is what can
          ;; see it: drop everything but the last line and read `names` again.
          (let [f (io/file (sub/data-dir) "files.edn")]
            (spit f (str (last (str/split-lines (slurp f))) "\n"))
            (is (= {"old.csv" "gone"} (files/names))
                "the tombstone reclaim! wrote carries the name it was handed — the
                 two-argument arity is what this is about, and passing nil for the
                 name passes every other assertion in this test")))))))

(deftest reclaim-reports-a-file-it-could-not-delete-rather-than-dropping-it
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "old.csv") "x\n")
        (files/remember! "old.csv" "gone")
        (put-source! st "tbl:a" "loci://files/old.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:a"})
        ;; The file must stay a FILE — `listing` filters directories out via
        ;; `content?`, so a directory named `old.csv` never becomes a candidate
        ;; and this would pass over an empty result. Deletion on POSIX is a
        ;; property of the CONTAINING directory, so make that unwritable.
        (.setWritable (files/dir) false)
        (try
          (let [r (files/reclaim! st nothing-retained)]
            ;; Running as root ignores the permission bit. Say so loudly rather
            ;; than passing green on a mechanism that never ran — a silent skip
            ;; here is a test that certifies.
            (is (= ["old.csv"] (map :file (:files r)))
                "the candidate is still reported, not silently dropped: a `:when`
                 filter would answer a shorter vector and a caller comparing counts
                 would read that as success")
            (if (.exists (io/file (files/dir) "old.csv"))
              (do (is (re-find #"could not delete" (str (:error (first (:files r)))))
                      "and the row says which file")
                  (is (re-find #"AccessDeniedException"
                               (str (:error (first (:files r)))))
                      "and WHY, in the platform's own words. The sentence this
                       replaces — `it is still in <dir>` — restates the failure and
                       is the same for a permission bit, a `chattr +i` and a
                       read-only mount, which would say ReadOnlyFileSystemException
                       here")
                  (is (= 2 (:bytes (first (:files r))))
                      "the row still carries the file's bytes, because it describes
                       the FILE and not the outcome — which is exactly why the sum
                       below cannot be left to a caller")
                  (is (= 0 (:freed r))
                      "and NOTHING was freed: the obvious `(reduce + (map :bytes …))`
                       over these rows answers 2, and a sweep against a directory it
                       cannot write to would report the total it hoped for while the
                       disk is unchanged")
                  (is (= #{} (files/retired))
                      "and the name is NOT retired, because the bytes are still there"))
              (is false (str "the delete succeeded despite an unwritable directory"
                             " — running as root? this test proved nothing"))))
          (finally (.setWritable (files/dir) true)))))))

(deftest reclaim-with-nothing-reclaimable-deletes-nothing
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "x\n")
        ;; **The positive control, and it is not decoration.** `(= [] …)` is
        ;; also what a `reclaim!` that threw its candidates away answers, and
        ;; what one answers over a directory it never managed to read at all.
        ;; So establish first that there WAS a file here for a class to protect,
        ;; and that the class is the one doing the protecting.
        (is (= [{:file "placed.csv" :class :foreign}]
               (mapv #(select-keys % [:file :class])
                     (:files (files/report st nothing-retained))))
            "report saw exactly one file and called it foreign")
        (is (= {:files [] :freed 0} (files/reclaim! st nothing-retained)))
        (is (.exists (io/file (files/dir) "placed.csv"))
            "a hand-placed file survives a reclaim that had no other candidates —
             the class that protects it is not a special case of having something
             else to do")))))

(deftest reclaim-parses-the-manifest-a-fixed-number-of-times-and-not-once-per-file
  ;; `retire!`'s ONE-argument arity resolves the name with `(get (names) id)` —
  ;; a full parse of `files.edn`, per file, which in a loop is quadratic: 27.9 s
  ;; over 2,000 files against 0.040 s handed the name (measured 2026-08-22 and
  ;; recorded on `retire!` itself). `report` has already read the manifest and
  ;; put the name on the row, so `reclaim!` hands it over.
  ;;
  ;; **Nothing about the RESULT can see which arity was used.** The two write
  ;; the same record — `retiring-with-the-name-in-hand-writes-the-same-record`
  ;; is what pins that — so a fixture reading `names` or `retired` afterwards
  ;; passes either way, and the only thing that can hold this is the COUNT of
  ;; parses.
  ;;
  ;; **Counted at `records` and not at `names`, which is the correction.** One
  ;; `report` parses `files.edn` TWICE — `listing → names → records` and
  ;; `classify → latest → records` — so counting `names` sees only one of the
  ;; two paths, and a per-file parse introduced through `latest` would be
  ;; invisible to it. `records` is the one reader both go through, which is what
  ;; it was split out to be; pinning it costs nothing and pins both.
  (with-dir
    (fn [_]
      (let [st      (sub/fresh-store)
            parses  (atom 0)
            records #'files/records
            real    @records]
        (.mkdirs (files/dir))
        (doseq [n ["a.csv" "b.csv" "c.csv"]]
          (spit (io/file (files/dir) n) "x\n")
          (files/remember! n (str "name of " n))
          (put-source! st (str "tbl:" n) (files/uri n) :materialise)
          (sub/commit! st {:op :delete :id (str "tbl:" n)}))
        ;; `with-redefs-fn` and not `with-redefs`, because `records` is private
        ;; and the macro's binding form has to resolve the symbol here.
        (let [r (with-redefs-fn {records (fn [] (swap! parses inc) (real))}
                  #(files/reclaim! st nothing-retained))]
          (is (= ["a.csv" "b.csv" "c.csv"] (map :file (:files r)))
              "the premise: three files really were reclaimed, so a per-file parse
               would have somewhere to happen")
          (is (= 2 @parses)
              (str "the manifest is parsed twice for the whole reclaim — `listing`'s "
                   "and `classify`'s, the two `classify` documents — and not once "
                   "per file. Three files parsed it " @parses " times")))))))

(deftest classify-derives-a-rows-uri-and-does-not-read-one-off-it
  ;; The three-argument arity took `:uri` off the row, which made *this must be
  ;; a `listing` row* an unstated precondition — and its wrong direction is a
  ;; deletion, not a wrong answer. `report` is the only caller and its rows are
  ;; consistent, so nothing reachable produced it; `(uri file)` is what makes
  ;; that true by construction rather than by convention.
  (with-dir
    (fn [_]
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "live.csv") "x\n")
        (spit (io/file (files/dir) "dead.csv") "y\n")
        (files/remember! "live.csv" "Live")
        (files/remember! "dead.csv" "Dead")
        (put-source! st "tbl:l" "loci://files/live.csv" :passthrough)
        (put-source! st "tbl:d" "loci://files/dead.csv" :materialise)
        (sub/commit! st {:op :delete :id "tbl:d"})
        (is (= {"live.csv" :current "dead.csv" :reclaimable}
               (into {} (map (fn [[f m]] [f (:class m)]))
                     (files/classify st nothing-retained)))
            "the premise: the two files classify differently, so a row that names
             one file and the other's uri has two answers to choose between")
        (is (= :current
               (:class (get (files/classify st nothing-retained
                                            [{:file "live.csv"
                                              :uri  (files/uri "dead.csv")
                                              :bytes 2}])
                            "live.csv")))
            "the row's `:file` decides, and the uri is derived from it — reading the
             row's own `:uri` answers `:reclaimable` for a file a live passthrough
             table is reading on every query, which is a `reclaim!` deleting it")))))
