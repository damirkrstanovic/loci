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

(defn- read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defrecord FileLog [file lock minted]
  FactLog
  (append! [_ rec]
    (locking lock
      (io/make-parents (io/file file))
      (spit file (str (pr-str rec) "\n") :append true)
      :ok))
  (load-all [_] (read-file file))
  ;; One past the largest id ever issued — not (inc (count …)). Counting is
  ;; collision-free only while nothing is ever deleted: delete mem-2 of three
  ;; facts and the count is 2, so the next mint hands out mem-3 and a caller
  ;; appending it overwrites a *different, still-live* fact — same id, last
  ;; line wins, the original text gone with no error anywhere. `read-file`
  ;; returns the whole map, tombstones included, precisely so a tombstoned id
  ;; still counts toward the maximum and is never reissued.
  ;;
  ;; Scans the file on every call, which is what a flat file can do — but a
  ;; scan alone is not enough. Two calls to `mint-id!` with no `append!` in
  ;; between (the caller mints, then decides, then writes) must not hand out
  ;; the same id twice: the file hasn't changed, so a scan-only answer would
  ;; repeat. `minted` remembers the highest id this log has ever handed out,
  ;; in addition to the highest it has ever seen written, so consecutive
  ;; mints still advance. `DbiLog` doesn't need this — a database counter
  ;; already advances on read.
  (mint-id! [_]
    (locking lock
      (let [seen (reduce (fn [hi id] (max hi (or (id-ordinal id) 0)))
                          0 (keys (read-file file)))
            n    (inc (max seen @minted))]
        (reset! minted n)
        (str "mem-" n)))))

(defn file-log [path] (->FileLog path (Object.) (atom 0)))
