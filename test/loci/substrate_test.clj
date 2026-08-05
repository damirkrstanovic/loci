(ns loci.substrate-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/substrate.edn"))

(deftest persistent-store-replays
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
    (is (= "world" (:value (sub/object s "a"))))
    ;; a brand-new store over the same file replays to identical state
    (let [s2 (sub/persistent-store path)]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a"))))
      (is (= (sub/state s) (sub/state s2))))))

(deftest persistent-undo-truncates-file
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (sub/undo! s)
    (is (nil? (sub/object s "b")))
    (let [s2 (sub/persistent-store path)]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b"))))))

(deftest append-after-reload-preserves-order
  (let [path (tmpfile)]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    ;; a reloaded store appends after what it replayed
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (let [s2 (sub/persistent-store path)]
        (is (= [:put :put] (mapv :op (sub/history s2))))
        (is (= 1 (:value (sub/object s2 "a"))))
        (is (= 2 (:value (sub/object s2 "b"))))))))

(deftest frozen-store-is-a-read-only-window
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value "1'"})
    (let [fz (sub/frozen-at st 2)]
      ;; sees exactly the first 2 events
      (is (= "1" (:value (sub/object fz "a"))))          ; edit (event 3) not applied
      (is (= "B" (:title (sub/object fz "b"))))
      (is (= 2 (count (sub/history fz))))
      ;; the present is untouched
      (is (= "1'" (:value (sub/object st "a"))))
      ;; the past cannot be edited
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}})))
      (is (thrown? UnsupportedOperationException (sub/undo! fz)))
      ;; as-of within the window still works (nested time travel)
      (is (nil? (sub/object (sub/frozen-at fz 1) "b"))))))

(deftest corrupt-trailing-line-salvages-prefix
  (let [path (tmpfile)]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    (spit path "{:op :put :id \"b\" :va" :append true)   ; simulate crash mid-append
    (let [s (sub/persistent-store path)]
      (is (= 1 (count (sub/history s))))
      (is (= 1 (:value (sub/object s "a")))))))

;; ---- log readability: an event that cannot be read back is a lost log ----
;; Found live: a research table arrived with a column named "Key Proponent(s)".
;; As a keyword it PRINTS fine and reads back as garbage, so one line poisoned
;; the file and every later event silently vanished on the next boot.

(deftest col-kw-makes-any-column-name-readable
  (is (= :key_proponent_s (sub/col-kw "Key Proponent(s)")))
  (is (= :maturity_status (sub/col-kw "Maturity / Status")))
  (is (= :how_it_works    (sub/col-kw (keyword "How It Works"))))
  (is (= :year            (sub/col-kw "year")))            ; already clean → untouched
  (is (= :table/rows      (sub/col-kw :table/rows)))       ; readable namespaced kw survives
  (is (= :col             (sub/col-kw "  ")))              ; nothing left → stable fallback
  (doseq [s ["Key Proponent(s)" "Maturity / Status" "%" "a b" "  "]]
    (let [k (sub/col-kw s)]
      (is (= k (edn/read-string (pr-str k))) (str "must round-trip: " s)))))

(deftest unreadable-keys-never-poison-the-log
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "t"
                    :value {:id "t" :kind :table
                            :value [{(keyword "Key Proponent(s)") "Canon"
                                     (keyword "Maturity / Status") "R&D"
                                     :year 2026}]}})
    ;; normalized on the way in, so memory and disk cannot disagree
    (is (= [{:key_proponent_s "Canon" :maturity_status "R&D" :year 2026}]
           (:value (sub/object s "t"))))
    (let [s2 (sub/persistent-store path)]
      (is (= 1 (count (sub/history s2))))
      (is (= (sub/state s) (sub/state s2))))))

(deftest wide-rows-commit-fine
  ;; a map with more than 8 keys re-prints in a different order (array-map →
  ;; hash-map); the readability guard must judge the VALUE, not the string,
  ;; or every table with 9+ columns becomes uncommittable.
  (let [path (tmpfile)
        s    (sub/persistent-store path)
        row  (into {} (map (fn [i] [(keyword (str "c" i)) i]) (range 12)))]
    (sub/commit! s {:op :put :id "t" :value {:id "t" :kind :table :value [row]}})
    (is (= row (first (:value (sub/object s "t")))))
    (is (= 1 (count (sub/history (sub/persistent-store path)))))))

(deftest bad-middle-line-is-skipped-not-swallowed
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    ;; the real shape: a keyword with a space in it leaves the map an odd number
    ;; of forms — "Map literal must contain an even number of forms"
    (spit path "{:op :put :id \"bad\" :value {:Key Proponent(s)}}\n" :append true)
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (let [out (java.io.StringWriter.)
          s2  (binding [*err* out] (sub/persistent-store path))]
      (is (= 2 (count (sub/history s2))))                  ; the tail still replays
      (is (= 1 (:value (sub/object s2 "a"))))
      (is (= 2 (:value (sub/object s2 "b"))))
      (is (re-find #"line 2" (str out))))))                ; and it says so out loud
