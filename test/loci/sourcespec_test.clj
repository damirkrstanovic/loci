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

(deftest locate-names-the-phase-when-it-does-not-know-a-scheme
  (let [e (try (ssp/locate "s3://bucket/x.csv") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"loci://" (.getMessage e)) "the error must say what it DOES know")))

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
    (is (re-find #"csv" (.getMessage e)) "the error must list the formats it knows")))

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
