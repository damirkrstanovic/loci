(ns loci.entities-test
  "The entity projection: a normalized name, the live facts that mention it, and
   nothing stored. Every fact here is a literal map — this namespace holds no
   store and no memory, so nothing in this file opens either."
  (:require [clojure.test :refer [deftest is testing]]
            [loci.entities :as ent]))

(defn- f [id ents] {:id id :fact (str "a fact " id) :entities ents :ts 0 :strength 1})

(deftest normalization-is-stable
  (testing "lowercase, trimmed, internal whitespace collapsed — and nothing cleverer"
    (is (= "wrath of the righteous" (ent/normalize "  Wrath   of the\tRighteous ")))
    (is (= "tsmc" (ent/normalize "TSMC")))
    ;; punctuation is NOT stripped: changing the rule later re-identifies every
    ;; entity, so the rule is exactly what the spec wrote down
    (is (= "u.s." (ent/normalize "U.S.")))
    (is (= "" (ent/normalize nil))))
  (testing "Unicode whitespace collapses and trims the same as ASCII whitespace"
    ;; U+00A0 (NBSP) mid-string collapses to a single ordinary space, same as
    ;; any other run of whitespace
    (is (= "u.s. navy" (ent/normalize "u.s. navy")))
    ;; U+202F (narrow no-break space) at the edges: collapse-before-trim means
    ;; it becomes an ordinary space and is then trimmed away, not left behind
    (is (= "tsmc" (ent/normalize " tsmc "))))
  (testing "lowercasing does not depend on the JVM's default locale"
    ;; the real hazard — clojure.string/lower-case's .toLowerCase() maps "I"
    ;; to dotless "ı" under a Turkish locale, so entity identity would depend
    ;; on $LANG — can't be demonstrated on this JVM's default locale without
    ;; a -Duser.language=tr flag this suite doesn't set; Locale/ROOT is the
    ;; fix and this assertion just pins the ordinary case still works
    (is (= "intel" (ent/normalize "INTEL")))))

(deftest index-groups-facts-by-normalized-name
  (let [idx (ent/index [(f "mem-1" ["TSMC" "Taiwan" "  "])
                        (f "mem-2" ["tsmc"])
                        (f "mem-3" ["jupiter"])])]
    (is (= #{"mem-1" "mem-2"} (get idx "tsmc")))
    (is (= #{"mem-1"} (get idx "taiwan")))
    (is (= 3 (count idx)))
    ;; a blank entity string is dropped, not turned into a "" key — nothing in
    ;; this suite passes one, so deleting the guard would break nothing here
    ;; and instead surface as a blank-labelled row that matches every query
    (is (not (contains? idx "")))))

(deftest a-name-repeated-on-one-fact-counts-once
  ;; absorb unions :entities, so a survivor can legitimately carry duplicates
  (is (= 1 (count (get (ent/index [(f "mem-1" ["tsmc" "TSMC" " tsmc "])]) "tsmc")))))

(deftest df-map-counts-live-facts-carrying-each-name
  (let [dfm (ent/df-map [(f "mem-1" ["tsmc" "taiwan"]) (f "mem-2" ["tsmc"])])]
    (is (= 2 (get dfm "tsmc")))
    (is (= 1 (get dfm "taiwan")))
    (is (nil? (get dfm "asml")))))

(deftest promotion-needs-two-live-facts
  (let [idx (ent/index [(f "mem-1" ["tsmc" "taiwan"]) (f "mem-2" ["tsmc"])])]
    (is (= #{"tsmc"} (ent/promoted idx #{})))
    (testing "a materialized name is promoted regardless of its evidence"
      (is (= #{"tsmc" "taiwan"} (ent/promoted idx #{"taiwan"}))))))

(deftest a-merge-can-push-a-name-back-below-the-threshold
  ;; `absorb` unions the absorbed fact's entities onto the survivor, so two facts
  ;; naming asml become ONE fact naming asml. The evidence really did collapse.
  (let [before (ent/index [(f "mem-1" ["asml"]) (f "mem-2" ["asml"])])
        after  (ent/index [(f "mem-1" ["asml"])])]
    (is (= #{"asml"} (ent/promoted before #{})))
    (is (= #{} (ent/promoted after #{})))))

(deftest tombstones-are-the-callers-problem-and-must-not-be-passed-in
  ;; index takes LIVE facts; this pins that a tombstone (no :entities, no :fact)
  ;; contributes nothing rather than throwing, so a caller that slips one in
  ;; degrades quietly instead of 500ing the pane
  (is (= {} (ent/index [{:id "mem-9" :fact "gone" :ts 0 :merged-into "mem-1"}]))))

(deftest detect-matches-longest-first
  (let [names #{"wrath" "wrath of the righteous" "tsmc"}]
    (is (= #{"wrath of the righteous"}
           (ent/detect names "What happens in Wrath of the Righteous?")))
    (is (= #{"wrath"} (ent/detect names "the wrath of a customer")))
    (is (= #{"tsmc"} (ent/detect names "How much does TSMC make?")))))

(deftest detect-respects-word-boundaries
  (is (= #{} (ent/detect #{"us"} "thus it follows")))
  (is (= #{"us"} (ent/detect #{"us"} "in the US, mostly"))))

(deftest detect-respects-word-boundaries-outside-ascii
  ;; Java's \p{Alnum} is ASCII-only without (?U); a non-ASCII letter would
  ;; otherwise read as "not alnum" and satisfy the boundary where there is
  ;; none, over-matching the same way "us" would match inside "thus" with no
  ;; boundary check at all — the corpus has a Serbian-language notebook, so
  ;; this is live text
  (is (= #{} (ent/detect #{"us"} "žus radi")))
  (is (= #{} (ent/detect #{"ni"} "u Nišu danas"))))

(deftest detect-is-empty-for-empty-input
  (is (= #{} (ent/detect #{"tsmc"} "")))
  (is (= #{} (ent/detect #{} "TSMC"))))

(deftest detect-normalizes-its-own-names
  ;; a caller (recall's subject list, a stored materialized-name) may hand
  ;; detect a name that was never run through normalize; detect must not fail
  ;; silently on it — an un-normalized name that simply never matches looks
  ;; exactly like a correct empty answer, not a bug
  (is (= #{"tsmc"} (ent/detect #{"TSMC"} "about TSMC")))
  (is (= #{"tsmc"} (ent/detect #{"  tsmc "} "about TSMC"))))

(deftest detect-quotes-regex-metacharacters-in-names
  ;; a name from a model may contain regex metacharacters. Pattern/quote is
  ;; invisible to this suite's all-alphabetic fixture names — deleting it
  ;; breaks nothing here and instead breaks in production, where "." starts
  ;; matching as a wildcard instead of a literal dot
  (testing "a dot in a name is a literal dot, not a wildcard"
    (is (= #{} (ent/detect #{"u.s."} "uxsy elsewhere")))
    (is (= #{"u.s."} (ent/detect #{"u.s."} "the u.s. said today"))))
  (testing "unbalanced regex metacharacters in a name do not throw"
    (is (= #{"(mit)"} (ent/detect #{"(mit)"} "founded at (mit) in 1861")))))

(deftest detect-blanks-every-occurrence-of-a-match
  ;; str/replace-first would leave a second mention of the long name intact,
  ;; and the short name inside it would then independently match on the next
  ;; pass — every occurrence must be blanked, not just the first
  (is (= #{"wrath of the righteous"}
         (ent/detect #{"wrath" "wrath of the righteous"}
                      "Wrath of the Righteous is long. Wrath of the Righteous again."))))

(deftest promoted-normalizes-materialized-names
  ;; the call this exists for compares a normalized `nm` against this set —
  ;; an un-normalized stored name would make that lookup miss and report "not
  ;; promoted" for an entity that plainly is one
  (let [idx (ent/index [(f "mem-1" ["tsmc"])])]
    (is (= #{"tsmc"} (ent/promoted idx #{"  TSMC "})))
    (testing "a blank materialized name is dropped, not kept as a fake entity"
      (is (not (contains? (ent/promoted idx #{""}) ""))))))
