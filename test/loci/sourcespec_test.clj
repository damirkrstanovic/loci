(ns loci.sourcespec-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loci.sourcespec :as ssp]
            [loci.substrate :as sub]))

(deftest locate-resolves-loci-relative-to-the-data-dir
  (is (= (str (sub/data-dir) "/files/sales.csv")
         (ssp/locate "loci://files/sales.csv"))))

(deftest locate-passes-a-file-uri-through-as-a-path
  (is (= "/data/sales.csv" (ssp/locate "file:///data/sales.csv")))
  (is (= "/data/with space.csv" (ssp/locate "file:///data/with%20space.csv"))))

(deftest locate-refuses-a-file-uri-whose-first-segment-reads-as-a-host
  ;; `file://data/sales.csv` is a plausible thing to type: loci:// is relative
  ;; to the data directory, and file:// is documented one line away as "a host
  ;; path". Java reads it as the path /sales.csv on a host called "data", so
  ;; passing `.getPath` straight through did not refuse it — it RESOLVED it to
  ;; a path at the filesystem root the caller never typed, and the failure then
  ;; arrived from DuckDB naming that path. Same shape as `file://.tmp/…`, which
  ;; 5a11360 fixed at one call site.
  ;;
  ;; So this asserts the SENTENCE, not the failure. "Connecting file://data/x
  ;; fails" passed before the fix too, because /x does not exist either.
  (doseq [bad ["file://data/sales.csv" "file://.tmp/src-1.parquet" "file://./x.csv"]]
    (let [e   (try (ssp/locate bad) nil (catch clojure.lang.ExceptionInfo e e))
          ;; `str` so that a nil message FAILS rather than NPEs — the sabotage
          ;; run is read, and a stack trace hides which assertion bit.
          msg (str (some-> e .getMessage))
          tail (subs bad (count "file://"))]
      (is (some? e) (str "must refuse " bad))
      (is (= bad (:uri (ex-data e))) "carrying :uri, like every other branch here")
      (is (re-find #"followed by a HOST" msg)
          "and saying WHY, since the caller sees only this line")
      (is (str/includes? msg (str "file:///" tail))
          "and naming the three-slash form they probably meant")
      (is (str/includes? msg (str "loci://" tail))
          "and the loci:// spelling, for a path under the data directory")))
  ;; `.tmp` is the case that proves `.getAuthority` rather than `.getHost` is
  ;; the test: a leading dot is not a legal hostname, so `.getHost` is nil for
  ;; exactly the URI that started this.
  (is (nil? (.getHost (java.net.URI. "file://.tmp/src-1.parquet")))))

(deftest locate-accepts-the-one-authority-that-means-this-machine
  ;; RFC 8089 defines file://localhost/x as file:///x, and loci takes it, which
  ;; is what keeps the refusal above honest: every authority it can fire on is
  ;; then really a first path segment, so naming file:///<it> as the intent is
  ;; never a guess. This pins a decision rather than guarding a bug — break it
  ;; by refusing every authority, not by restoring the old `.getPath`.
  (is (= "/data/sales.csv" (ssp/locate "file://localhost/data/sales.csv"))))

(deftest locate-refuses-a-loci-path-that-escapes-the-data-dir
  ;; loci:// MEANS "under the data dir" — that is what makes a substrate
  ;; portable between machines. A path that leaves it is a file:// URI, which
  ;; is unrestricted and needs no disguise.
  (doseq [bad ["loci://../etc/passwd" "loci://files/../../etc/passwd" "loci:///abs" "loci://"]]
    (is (thrown? clojure.lang.ExceptionInfo (ssp/locate bad)) (str "must refuse " bad))))

(deftest locate-lists-every-scheme-it-knows-when-it-does-not-know-one
  ;; Was `locate-names-the-phase-…`, probing `s3://bucket/x.csv` until phase 3
  ;; and passing only while `locate` threw on s3://. `gs://` is now the nearest
  ;; scheme loci does not know, and it stays that way on purpose: it rides
  ;; httpfs and would cost one entry in `remote-schemes`.
  ;;
  ;; **The old assertion was `(re-find #"loci://" msg)`, and it CERTIFIED the
  ;; sentence it was watching.** `loci://` appears in every version of that
  ;; message, so the test stayed green while the rest of it went stale — phase
  ;; 1 wrote "Remote schemes (s3://, https://) arrive with httpfs" as a promise,
  ;; Task 1 made the promise true and left the future tense standing, and this
  ;; test had nothing to say about either. It would have stayed green through
  ;; any rewrite at all. So it now asserts the whole claim: every scheme
  ;; `locate` accepts is named, and the two it defers are named as deferred.
  ;;
  ;; `ftp://` is in the loop and is the reason the `gs://`/`az://` assertions
  ;; are not vacuous: the message quotes the uri back, so over `gs://bucket/…`
  ;; alone the string `gs://` would be present whether or not the sentence
  ;; mentions it. Over `ftp://h/x.csv` it can only have come from the sentence.
  (doseq [u ["gs://bucket/x.csv" "az://c/x.csv" "ftp://h/x.csv"]]
    (let [e (try (ssp/locate u) nil (catch clojure.lang.ExceptionInfo e e))
          ;; `str`/`some->` rather than a bare `.getMessage`, for the reason the
          ;; two tests above already give: when this test fails it is because
          ;; `e` is nil, and `(.getMessage nil)` turns every assertion below
          ;; into an NPE stack trace instead of a sentence. Seen doing exactly
          ;; that on 2026-08-23.
          msg (str (some-> e .getMessage))]
      (is (some? e) (str u " must be refused"))
      ;; Derived, not written out: with the five spelled as literals here this
      ;; test scores the same 21/21 against a message that spells them as
      ;; literals too, so reverting the composition is a silent no-op. It is
      ;; `remote-schemes` that must be named, whatever is in it.
      (doseq [known (concat ["loci://" "file://"] @#'ssp/remote-schemes)]
        (is (str/includes? msg known)
            (str "the message must name every scheme locate accepts, and "
                 known " is missing from: " msg)))
      (doseq [deferred ["gs://" "az://"]]
        (is (str/includes? msg deferred)
            (str "and name the pair that is deferred, because the reader who
                  sees this sentence is usually the one who typed one of them: "
                 msg)))
      (is (not (re-find #"arrive with httpfs" msg))
          (str "and it must not promise a future that has already shipped: " msg)))))

(deftest locate-composes-both-halves-of-its-scheme-list-from-one-set
  ;; The test above cannot see the drift it guards against, because both halves
  ;; of the sentence are right while `remote-schemes` is what it is today. This
  ;; is the day it changes, which is the day the deferred half was wrong:
  ;; measured 2026-08-23 with the deferred pair written as a literal —
  ;;
  ;;   … it knows loci://, file://, gs://, http://, https://, s3://.
  ;;     gs:// and az:// are not among them yet.
  ;;
  ;; — naming `gs://` twice, once as known and once as deferred, at the one
  ;; reader who typed it. `remote-schemes` is `def`'d, so `with-redefs` reaches
  ;; it and `locate` derefs it per call; the probe stays `ftp://` so that every
  ;; `gs://` in the message came from the sentence and not from the echoed uri.
  (with-redefs [ssp/remote-schemes (conj @#'ssp/remote-schemes "gs://")]
    (let [e   (try (ssp/locate "ftp://h/x.csv") nil (catch clojure.lang.ExceptionInfo e e))
          msg (str (some-> e .getMessage))]
      (is (some? e))
      ;; The known half, as one substring: `(includes? msg "gs://")` alone is
      ;; satisfied by the deferred clause of a fully hardcoded message, which is
      ;; the sabotage this has to fail against.
      (is (str/includes? msg (str/join ", " (concat ["loci://" "file://"]
                                                    (sort @#'ssp/remote-schemes))))
          (str "the schemes it knows are the set it reads, in order: " msg))
      (is (= 1 (count (re-seq #"gs://" msg)))
          (str "and a scheme is named ONCE — a literal deferred half says gs://"
               " twice the day it arrives: " msg))
      (is (str/includes? msg "az:// is not among them yet")
          (str "the half still deferred is what is left after subtraction,"
               " and it reads as one scheme rather than two: " msg)))))

(deftest locate-refuses-a-scheme-whose-case-is-wrong-by-naming-the-spelling
  ;; `remote?` is case-sensitive by decision, so this is where a person who
  ;; typed `S3://` arrives. The old message recited five schemes at them — with
  ;; `s3://` among the ones it says loci knows, one clause after "loci does not
  ;; know this location: S3://bucket/x.csv". The reader's eye finds `s3://` in
  ;; the list and concludes loci is broken; nothing in the sentence says
  ;; SCHEMES ARE CASE-SENSITIVE, which is the one fact that resolves it.
  ;;
  ;; The assertion that bites is the SPELLING, not the word "case": the old
  ;; message contains `s3://` too, and only a message that echoes the whole
  ;; corrected location contains `s3://bucket/Q3-Sales.csv`.
  (doseq [[typed spelled] {"S3://bucket/Q3-Sales.csv" "s3://bucket/Q3-Sales.csv"
                           "HTTPS://h/x.csv"          "https://h/x.csv"
                           "FILE:///data/x.csv"       "file:///data/x.csv"
                           "LOCI://files/x.csv"       "loci://files/x.csv"}]
    (let [e   (try (ssp/locate typed) nil (catch clojure.lang.ExceptionInfo e e))
          msg (str (some-> e .getMessage))]
      (is (some? e) (str typed " is still refused — the rule has not changed"))
      (is (str/includes? msg "case-sensitive")
          (str "and says WHY, which is the fact the reader is missing: " msg))
      (is (str/includes? msg spelled)
          (str "and names the location as loci reads it, with the path's own case
                intact — an S3 key is case-sensitive, so a lower-cased
                Q3-Sales.csv is a different object. Got: " msg))
      (is (not (str/includes? msg "not among them yet"))
          (str "and does not recite the deferred pair at someone whose scheme
                loci already reads: " msg)))))

(deftest locate-tells-a-path-from-a-location
  ;; A bare filename is the other accident loci can see, and `loci://` is the
  ;; repair for it: an uploaded file is the common case and that is where it
  ;; lives. The `loci://` branch already knows how to say this facing the other
  ;; way — "Use file:// for a path outside it."
  ;;
  ;; **This assertion used to end at `file://`, and required the message NOT to
  ;; name a remote scheme** — "they forgot the scheme, they are not choosing
  ;; one." That was right while the only way to reach `locate` was a REPL or an
  ;; upload. Phase 3 put the sentence under a ＋ Data field whose placeholder
  ;; reads *…or a URL loci fetches*, and the reader who types
  ;; `example.com/sales.csv` or `s3:/bucket/x.csv` into it lands in exactly this
  ;; branch — with `https://` and `s3://` being the single edit each of them
  ;; needs, and neither named. So the branch has two audiences and answers both,
  ;; and this test now pins that rather than the older half of it.
  (let [e   (try (ssp/locate "sales.csv") nil (catch clojure.lang.ExceptionInfo e e))
        msg (str (some-> e .getMessage))]
    (is (some? e))
    (is (str/includes? msg "loci://sales.csv")
        (str "an uploaded file is the common case, and loci:// is where it lives: " msg))
    (is (str/includes? msg "file://") (str "with the other spelling named too: " msg))
    (is (str/includes? msg "https://")
        (str "and the scheme a URL needs, which is the one edit the ＋ Data field's own
              reader is missing: " msg))
    (is (not (str/includes? msg "gs://"))
        (str "but not the deferred pair — someone who forgot a scheme is not shopping
              for one loci cannot read: " msg)))
  ;; The same sentence, reached the way the panel reaches it. `example.com/x.csv`
  ;; is a host and a path with the scheme left off, not a filename, and every
  ;; repair the older message composed for it — `loci://example.com/x.csv` — made
  ;; it worse. The scheme it needs is named; it is not glued onto what was typed,
  ;; because `https://sales.csv` would be the same mistake facing the other way.
  (let [e   (try (ssp/locate "example.com/sales.csv") nil
                 (catch clojure.lang.ExceptionInfo e e))
        msg (str (some-> e .getMessage))]
    (is (some? e))
    (is (str/includes? msg "https://")
        (str "a URL with the scheme left off is told nothing about the scheme: " msg))
    (is (not (str/includes? msg "https://example.com/sales.csv"))
        (str "the repair is composed onto a string loci cannot tell a host from a
              filename in — `https://sales.csv` is the same edit for the other
              audience of this sentence: " msg)))
  ;; And the one-slash typo, which has no `://` in it and so lands here too.
  ;; Nothing composes a repair for it — `loci://s3:/bucket/x.csv` is nonsense —
  ;; so what has to be true is that the scheme it meant is on screen.
  (let [e   (try (ssp/locate "s3:/bucket/x.csv") nil
                 (catch clojure.lang.ExceptionInfo e e))
        msg (str (some-> e .getMessage))]
    (is (some? e))
    (is (str/includes? msg "s3://")
        (str "one slash short of a location loci reads, and s3:// is not named: " msg)))
  ;; An absolute path can be spelled `file://` + itself, and CANNOT be spelled
  ;; `loci://` + itself: `loci:///data/x.csv` is refused two branches up for the
  ;; leading slash. So the repair offered is the one that works.
  (let [e   (try (ssp/locate "/data/x.csv") nil (catch clojure.lang.ExceptionInfo e e))
        msg (str (some-> e .getMessage))]
    (is (some? e))
    (is (str/includes? msg "file:///data/x.csv")
        (str "the three-slash form, built from the path they typed: " msg))
    (is (= "/data/x.csv" (ssp/locate (str "file://" "/data/x.csv")))
        "and it is a location locate then accepts — the repair must actually work")))

(deftest locate-answers-a-remote-uri-verbatim
  (is (= "s3://bucket/sales.csv" (ssp/locate "s3://bucket/sales.csv")))
  (is (= "https://example.com/a/b.csv" (ssp/locate "https://example.com/a/b.csv")))
  (is (= "http://127.0.0.1:8080/x.csv" (ssp/locate "http://127.0.0.1:8080/x.csv"))
      "plaintext too — httpfs reads it, and loci does not police the transport of a
       location a person typed, exactly as file:// is unrestricted by decision")
  (is (= "s3://bucket/a b.csv" (ssp/locate "s3://bucket/a b.csv"))
      "verbatim means verbatim: no re-encoding, no URI round trip. DuckDB is handed
       the string the user gave, quoted by sql-str, and it is DuckDB's job to parse it")
  ;; Mixed case in the PATH, and it is the only assertion here that a
  ;; NORMALISING locate fails. The four above are all lower-case with no edge
  ;; whitespace, so `(str/lower-case uri)` and `(str/trim uri)` ship green
  ;; through every one of them — measured 2026-08-23 against eight candidate
  ;; wrong `locate`s, and those two were the pair nothing caught. Task 1's own
  ;; sabotage lower-cased `remote?`'s ARGUMENT, which the `HTTPS://` assertions
  ;; do catch; lower-casing the ANSWER was the gap.
  ;;
  ;; It matters at fetch time and not before: S3 object keys are case-sensitive
  ;; and so is most of an HTTP path, so a normalised `Q3-Sales.csv` becomes a
  ;; NoSuchKey against a bucket that has the object — from an edit that looks
  ;; like tidying.
  (is (= "s3://bucket/Q3-Sales.csv" (ssp/locate "s3://bucket/Q3-Sales.csv"))
      "the path's case survives, because the object's name is case-sensitive"))

(deftest locate-still-refuses-a-scheme-it-does-not-know
  ;; A THROW and not a nil: a locate that fell off the end of the cond and
  ;; answered nil would satisfy an `(is (nil? …))` assertion while handing every
  ;; caller a location it never checked. Only refusing distinguishes them.
  ;;
  ;; `(is (thrown? …))` says that too, and the plan's sketch used it — but it
  ;; binds `e` to whatever `do-report` RETURNS when nothing was thrown, which is
  ;; nil only because clojure.test's default reporter happens to return nil.
  ;; Measured 2026-08-23 under a reporter that returns its accumulator instead:
  ;; the next line became `No matching field found: getMessage for class
  ;; PersistentVector` — a stack trace where a sentence should be. That is the
  ;; failure `locate-refuses-a-file-uri-whose-first-segment-reads-as-a-host`
  ;; already has a comment about, so this uses the same try/catch shape as the
  ;; rest of the file rather than depending on a reporter's return value.
  ;;
  ;; `HTTPS://` is in the list because `remote?` is case-sensitive by decision,
  ;; and this is where locate has to agree with it — a case-insensitive remote?
  ;; passes this uri through verbatim and this line is what notices.
  (doseq [u ["gs://bucket/x.csv" "az://c/x.csv" "ftp://h/x.csv" "redis://h/1"
             "HTTPS://h/x.csv"]]
    (let [e (try (ssp/locate u) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str u " must be refused, not resolved"))
      (is (re-find #"loci does not know this location" (str (some-> e .getMessage)))
          (str u " must be refused by name")))))

(deftest remote?-is-about-the-scheme-and-nothing-else
  (is (true?  (ssp/remote? "s3://b/x.csv")))
  (is (true?  (ssp/remote? "https://h/x.csv")))
  (is (true?  (ssp/remote? "http://h/x.csv")))
  (is (false? (ssp/remote? "loci://files/x.csv")))
  (is (false? (ssp/remote? "file:///data/x.csv")))
  (is (false? (ssp/remote? "gs://b/x.csv"))
      "a scheme loci does not know is not remote — it is unknown, and locate says so")
  ;; `false?` and not `nil?`: the callers branch on this, and a nil that reads as
  ;; falsey in an `if` is not the same var as one that answers a boolean. This is
  ;; the assertion that fails if the `boolean` wrapper is dropped.
  (is (false? (ssp/remote? nil)) "nil is not a location")
  (is (false? (ssp/remote? "HTTPS://h/x.csv"))
      ;; The RULE is documented on `remote?` itself, which is where a refactor
      ;; will read it — a rule that lives only in an assertion message gets
      ;; refactored away. It is consistency with the `loci://` and `file://`
      ;; branches, and NOT "every uri loci mints is lower-case": loci mints no
      ;; remote uri at all, they are typed.
      "case-sensitive by decision — see remote?'s docstring for the reason"))

(deftest unreadable-because-names-a-remote-uri-and-claims-no-filesystem
  ;; Task 1 made `locate` answer a remote uri verbatim; this function then built
  ;; a `java.io.File` out of that answer, whose `.exists` is false, and said
  ;; "there is no file at s3:/bucket/sales.csv" — a filesystem it never
  ;; consulted, with the scheme's slashes collapsed by `File`, on the path whose
  ;; own docstring says a second error must never be raised over the first.
  ;;
  ;; **The assertions are POSITIVE on purpose.** The plan sketched
  ;; `(is (not (re-find #"there is no file at" msg)))`, and that passes for three
  ;; different absences of a remote branch: a function that returns nil, one that
  ;; returns "", and one that throws past a caller who `str`s the result.
  ;; Measured 2026-08-23 with `unreadable-because` stubbed to `(constantly nil)`
  ;; — the negative assertion passed for all four uris below. So `string?` and
  ;; `includes?` carry this test; the negative one is kept because it names the
  ;; regression, and it is never the only one looking.
  (doseq [u ["s3://bucket/sales.csv" "https://example.com/x.csv"
             "http://127.0.0.1:1/x.csv" "s3://bucket/a b.csv"]]
    (let [msg (ssp/unreadable-because u)]
      (is (string? msg)
          (str "a remote uri gets a PHRASE, not nil. nil is this function's word for
                \"readable\", and answering it for " u " asserts a filesystem loci
                never looked at — tablestore/hydrate reads nil as DRIFT. Got: "
               (pr-str msg)))
      (is (str/includes? (str msg) u)
          (str "and the phrase carries the uri VERBATIM — java.io.File collapses
                s3:// to s3:/, which is exactly how the old sentence went wrong. Got: "
               (pr-str msg)))
      (is (re-find #"remote" (str msg))
          (str "and it says WHY loci has nothing of its own to add, rather than
                borrowing the sentence of a branch that did look at something.
                Got: " (pr-str msg)))
      (is (re-find #"could not read" (str msg))
          (str "and it STATES THE FAILURE. `tbl/unusable` composes this under
                \"its source is unreachable — \", and the first phrasing —
                \"loci cannot tell whether it can be read without fetching it\" —
                retracted that heading at the one instant loci had fetched it and
                knew: this function is reached from hydrate's catch, after a read
                that already failed. Got: " (pr-str msg)))
      (is (not (re-find #"there is no file at" (str msg)))
          (str "it claimed a missing local file for " u ": " (pr-str msg))))))

(deftest unreadable-because-still-tells-the-local-states-apart
  ;; The control for the test above, and what fails if the remote branch is
  ;; written too wide: an unconditional phrase, or one keyed on anything but the
  ;; scheme, satisfies every assertion up there while destroying the distinction
  ;; the whole function exists to draw. `loci.source-test` asserts these two
  ;; through `hydrate`; here they are asserted on the function itself, because
  ;; that is the seam the new branch was cut into.
  (let [f (java.io.File/createTempFile "ubc" ".csv")]
    (try
      (spit f "a\n1\n")
      (is (nil? (ssp/unreadable-because (str "file://" (.getAbsolutePath f))))
          "nil still means readable, and a readable local file still gets it")
      (.delete f)
      (is (re-find #"there is no file at"
                   (str (ssp/unreadable-because (str "file://" (.getAbsolutePath f)))))
          "and a missing one still says so — the local branches are untouched")
      (finally (.delete f))))
  ;; `loci://` and not only `file://`, because it is the scheme most sources
  ;; actually have — `index.html` strips `loci://files/` for display precisely
  ;; because that is the common shape — and because every other assertion here
  ;; and in the test above is written over `file://`. A remote branch spelled
  ;; `(not (str/starts-with? uri "file://"))` is a plausible inversion and it
  ;; passes all eighteen of them, while reporting a missing uploaded file as a
  ;; remote location. Measured 2026-08-23. This is the assertion that fails.
  (let [msg (str (ssp/unreadable-because (str "loci://files/ubc-" (System/nanoTime) ".csv")))]
    (is (re-find #"there is no file at" msg)
        (str "a loci:// upload that is not there is a MISSING FILE, and loci
              resolved it under the data directory to find that out: " msg))
    (is (not (re-find #"remote" msg))
        (str "and it is not remote — nothing fetches it: " msg))))

(deftest relation-builds-one-reader-call-per-format
  (is (= "read_csv('/tmp/a.csv')"
         (ssp/relation {:format :csv} "/tmp/a.csv")))
  (is (= "read_parquet('/tmp/a.parquet')"
         (ssp/relation {:format :parquet} "/tmp/a.parquet")))
  (is (= "read_json_auto('/tmp/a.json')"
         (ssp/relation {:format :json} "/tmp/a.json"))))

(deftest relation-orders-options-so-the-text-is-deterministic
  (is (= "read_csv('/tmp/a.csv', delim=';', header=true, sample_size=1024)"
         (ssp/relation {:format :csv :options {:sample_size 1024 :header true :delim ";"}}
                       "/tmp/a.csv"))))

(deftest relation-escapes-a-quote-in-the-location
  (is (= "read_csv('/tmp/o''brien.csv')"
         (ssp/relation {:format :csv} "/tmp/o'brien.csv"))))

(deftest relation-refuses-an-option-name-that-is-not-an-identifier
  ;; :options reach here from a JSON body, and an option NAME cannot be quoted
  ;; the way a value can — DuckDB wants a bare identifier. So it is checked.
  (doseq [bad ["header, x" "1=1" "" "a b" "he'ader"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ssp/relation {:format :csv :options {bad true}} "/tmp/a.csv"))
        (str "must refuse option name " (pr-str bad)))))

(deftest relation-refuses-an-option-value-it-cannot-render
  (is (thrown? clojure.lang.ExceptionInfo
               (ssp/relation {:format :csv :options {:columns {"a" "INT"}}} "/tmp/a.csv"))
      "a map is not a reader option value, and must not be interpolated raw"))

(deftest relation-refuses-an-unknown-format
  (let [e (try (ssp/relation {:format :xlsx} "/tmp/a.xlsx") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    ;; `str`/`some->`, like every other refusal test in this file: when this one
    ;; fails it fails because `e` is nil, and a bare `.getMessage` turns the
    ;; line below into an NPE stack trace where a sentence should be.
    (is (re-find #"csv" (str (some-> e .getMessage)))
        "the error must list the formats it knows")))

(deftest relation-refuses-a-bad-option-name-even-beside-a-good-one
  ;; `sort-by key` is a partial comparator: a string key and a keyword key in
  ;; one map throw a ClassCastException out of the SORT, before `opt-sql` can
  ;; refuse the bad name with a sentence. Ordering by the key's name is total,
  ;; so the guard is what the caller hears.
  (let [e (try (ssp/relation {:format :csv :options {"1=1" true :header true}} "/tmp/a.csv")
               nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "the option-name guard must win, not the comparator")
    (is (= "1=1" (:option (ex-data e))))))

(deftest locate-answers-a-malformed-file-uri-in-locis-own-voice
  ;; `java.net.URI.` throws first, and its own message — "Expected authority at
  ;; index 7: file://" — names neither loci nor what the caller typed. Every
  ;; other refusal here is an ex-info carrying :uri, and this one must be too,
  ;; because `describe`'s catch turns the message into what the shell shows.
  (doseq [bad ["file://" "file://a b/c"]]
    (let [e (try (ssp/locate bad) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str "must be an ex-info, not a raw URISyntaxException: " bad))
      (is (= bad (:uri (ex-data e)))))))
