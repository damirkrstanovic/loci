;; Task 5 measurement harness: two OS processes writing facts to one
;; Datalevin/LMDB environment concurrently.
;;
;; Tests the spec's cross-process claim in
;; docs/superpowers/specs/2026-08-15-one-substrate-design.md:
;;   "a monotonic counter kept in `meta`, read and incremented inside the
;;    same write path ... is what makes 'don't run two writers' a guarantee."
;;
;; `DbiLog.mint-id!` (dlv.clj) does `d/get-value` then `d/transact-kv` under
;; `(locking lock)` — a JVM monitor, meaningless across processes. LMDB
;; serialises write TRANSACTIONS, but a read-then-write straddling two
;; transactions is not atomic, so two processes could both read counter N
;; and both write N+1, minting the same id for two different facts.
;;
;; RUN LIKE THIS — plain `clojure -M -e "(load-file ...)" a b c` does NOT
;; populate *command-line-args*, so arguments go in as JVM system properties:
;;
;;   clojure -J--enable-native-access=ALL-UNNAMED \
;;     -J-Dtw.dir=/tmp/xyz/substrate -J-Dtw.tag=a -J-Dtw.n=100 \
;;     -M -e "(load-file \"dev/two_writers.clj\")"
;;
;; Prints the arguments it read and the jaccard sanity check BEFORE writing
;; anything, so a caller can confirm the harness actually saw its arguments
;; rather than silently running against defaults.

(require '[clojure.set :as set]
         '[clojure.string :as str]
         '[loci.dlv :as dlv]
         '[loci.memory :as mem]
         '[loci.mold :as mold])

(defn- tokens [s]
  (set (re-seq #"[a-z0-9]+(?:[.%-][a-z0-9]+)*%?" (str/lower-case (str s)))))

(defn- jaccard [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (/ (count (set/intersection a b)) (double (count (set/union a b))))))

;; One token per slot carries BOTH the tag and the index, so an `a` sentence
;; and a `b` sentence share no content token at all — only the three filler
;; words "governs"/"within"/"entirely" are common, same shape as the already
;;-measured 20-topic corpus in memory_dlv_test.clj (pairwise jaccard 0.3333).
(defn- sentence [tag i]
  (str "Quiddity" tag i " governs phenomenon" tag i " within regime" tag i " entirely."))

(let [dir (System/getProperty "tw.dir")
      tag (System/getProperty "tw.tag")
      n-s (System/getProperty "tw.n")]
  (println "=== two_writers.clj: arguments read via System/getProperty ===")
  (println "tw.dir =" (pr-str dir))
  (println "tw.tag =" (pr-str tag))
  (println "tw.n   =" (pr-str n-s))
  (when (or (nil? dir) (nil? tag) (nil? n-s))
    (println "!!! missing an argument -- aborting WITHOUT writing anything")
    (System/exit 1))
  (let [n (Long/parseLong n-s)
        ;; jaccard sanity check, measured with the SAME tokens/jaccard this
        ;; harness uses to write — not assumed from another corpus's numbers.
        sa0 (sentence "a" 0) sb0 (sentence "b" 0) sa1 (sentence "a" 1)
        j-cross  (jaccard (tokens sa0) (tokens sb0))
        j-within (jaccard (tokens sa0) (tokens sa1))]
    (println "=== jaccard sanity check ===")
    (println "sentence a0:" sa0)
    (println "sentence b0:" sb0)
    (println "jaccard(a0,b0) =" j-cross)
    (println "jaccard(a0,a1) =" j-within)
    (when (or (>= j-cross 0.6) (>= j-within 0.6))
      (println "!!! jaccard >= 0.6 -- these sentences would reinforce instead of")
      (println "    inserting, which would not exercise the id-mint race. Aborting.")
      (System/exit 1))

    (let [st (dlv/datalevin-store dir)
          m  (mem/datalevin-memory st)]
      (println "=== writing" n "facts, tag" tag "===")
      (dotimes [i n]
        (mold/remember m (sentence tag i) {:entities [(str tag i)]}))
      (let [facts (mem/all-facts m)
            mine  (filter #(str/starts-with? (:fact %) (str "Quiddity" tag)) facts)]
        (println "=== tag" tag "done ===")
        (println "wrote (called remember)      :" n)
        (println "this process's own facts seen:" (count mine))
        (println "distinct ids among them      :" (count (set (map :id mine))))))
    (shutdown-agents)
    (System/exit 0)))
