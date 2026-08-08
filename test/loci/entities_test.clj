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
    (is (= "" (ent/normalize nil)))))

(deftest index-groups-facts-by-normalized-name
  (let [idx (ent/index [(f "mem-1" ["TSMC" "Taiwan"])
                        (f "mem-2" ["tsmc"])
                        (f "mem-3" ["jupiter"])])]
    (is (= #{"mem-1" "mem-2"} (get idx "tsmc")))
    (is (= #{"mem-1"} (get idx "taiwan")))
    (is (= 3 (count idx)))))

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

(deftest detect-is-empty-for-empty-input
  (is (= #{} (ent/detect #{"tsmc"} "")))
  (is (= #{} (ent/detect #{} "TSMC"))))
