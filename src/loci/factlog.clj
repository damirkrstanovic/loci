(ns loci.factlog
  "Where a fact is durably written, behind one protocol.

   `loci.memory` documents `append-line!` as the only way anything writes a
   fact. This makes that literal: every write path in that namespace goes
   through `append!`, so moving facts from a file into a database is a change
   of implementation and not a rewrite of the recall engine.

   **`mint-id!` is on the protocol and that is the point.** A file log can only
   answer 'what is the next id' by scanning what it has; a database can keep a
   counter and increment it inside the write transaction. Leaving minting in
   `loci.memory`, over an in-RAM map, is what lets two writers mint the same id
   and destroy one fact with no error anywhere.

   A scan alone still isn't enough for the file case: a caller may mint an id
   before deciding whether to append one, so two mints with no append between
   them must not repeat. `FileLog` keeps a small in-memory high-water mark for
   exactly that gap; see its `mint-id!` for why."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defprotocol FactLog
  (append!  [this rec] "Persist one record. Last-wins by :id. Returns :ok.")
  (load-all [this]     "id → record, last-wins, tombstones included.")
  (mint-id! [this]     "A fact id no live or tombstoned record holds."))

(def ^:private id-pattern #"mem-(\d+)")

(defn id-ordinal
  "The <n> of a mem-<n> id, or nil for an id in any other shape.

   Public because both implementations need it and `loci.memory` sorts by it."
  [id]
  (when-let [[_ n] (re-matches id-pattern (str id))] (parse-long n)))

(defn- ordinal
  "`id-ordinal`, folded to 0 for anything that isn't mem-<n> rather than
   thrown — a hand-edited log should not brick remembering. There are none in
   the corpus today; if one ever turns up it contributes nothing toward where
   minting resumes rather than stopping minting altogether."
  [id]
  (or (id-ordinal id) 0))

(defn- read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defn- highest-ordinal
  "One past this is where minting resumes — not (inc (count …)). Counting is
   collision-free only while nothing is ever deleted: delete mem-2 of three
   facts and the count is 2, so the next mint hands out mem-3 and a caller
   appending it overwrites a *different, still-live* fact — same id, last
   line wins, the original text gone with no error anywhere. The semantic
   merge deletes the absorbed fact, so this is no longer hypothetical:
   `loci.memory/merge-similar!` does exactly that, today, every time two
   facts turn out to be the same one. `ids` here should be every id the log
   has ever held, tombstones included, precisely so a tombstoned id still
   counts toward the maximum and is never reissued."
  [ids]
  (reduce (fn [hi id] (max hi (ordinal id))) 0 ids))

(defrecord FileLog [file lock minted]
  FactLog
  (append! [_ rec]
    (locking lock
      (io/make-parents (io/file file))
      (spit file (str (pr-str rec) "\n") :append true)
      ;; Keeps `minted` current without re-reading the file: the record just
      ;; written is the only new fact `minted` doesn't already reflect, so
      ;; folding just its ordinal in is equivalent to a full rescan — see
      ;; `mint-id!` for why a full rescan is no longer done at all.
      (swap! minted max (ordinal (:id rec)))
      :ok))
  (load-all [_] (read-file file))
  ;; O(1): bump `minted` and hand out the new value. `minted` is seeded by one
  ;; full scan at construction time (`file-log`, below) and kept current by
  ;; `append!` from then on, so `mint-id!` itself never touches the file.
  ;;
  ;; It used to re-scan the file on every call, on the theory that a second
  ;; `FileLog` on the same path might have appended something this instance
  ;; hadn't seen. That theory bought nothing real: `lock` and `minted` are
  ;; per-instance, so two `FileLog`s open on one path already race each other
  ;; regardless of how often either rescans — Task 1's own review measured 26
  ;; distinct ids from 50 mints across two instances on one path. Re-reading
  ;; paid for a cross-instance guarantee it could not actually deliver, and
  ;; the price was real: against a 591-record, 8.5 MB memory.edn, `remember`
  ;; went from 5.85 ms to 642 ms per call, almost all of it here. The gap this
  ;; method genuinely has to close — two mints from the SAME instance with no
  ;; `append!` between them (the caller mints, then decides, then writes)
  ;; must not repeat — is still closed, because `minted` is one atom under one
  ;; lock in one process, which is a guarantee `swap!` actually keeps.
  (mint-id! [_]
    (locking lock
      (str "mem-" (swap! minted inc)))))

(defn file-log [path] (->FileLog path (Object.) (atom (highest-ordinal (keys (read-file path))))))
