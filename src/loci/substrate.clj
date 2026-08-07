(ns loci.substrate
  "Layer 1: the deterministic substrate — an append-only event log.

   Every change (human or agent) is one event appended to the log; the current
   state is a left-fold of the log. That single shape buys the three Raskin
   non-negotiables for free:

     · universal undo   = drop the last event, re-materialize
     · audit / history  = the log *is* the audit trail
     · time-travel      = materialize a prefix (`as-of`)

   This is the in-process implementation: an EDN-lines log, verifiable with no
   external DB. It sits behind the `Store` protocol so a durable engine could
   replace it without any caller changing — the same seam we used for `Recall`,
   and as of 2026-08-05 the seam has been used. `loci.dlv/DatalevinStore` is
   what the server runs on; the candidates this docstring used to name were all
   rejected on measurement (Datomic Local: a 4096-char string limit against
   objects up to 134 KB; XTDB: server-shaped, its bitemporality idle under
   events-as-truth; see the design spec).

   `PersistentStore` below is not dead code. It stays as the parity reference —
   the suite runs the whole substrate behaviour against both stores, so
   equivalence is proven rather than asserted — and as the rollback. Note the
   asymmetry recorded in `loci.dlv`: EDN log → Datalevin is always safe;
   Datalevin → EDN log is not, because nippy keeps values that `pr-str` cannot
   round-trip."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; ----------------------------------------------------------------------------
;; readable keys — a log is only a log if it can be read back.
;;
;; Column names arrive from agents and CSV headers and become keywords. A
;; keyword like :Key Proponent(s) PRINTS without complaint and then cannot be
;; read back at all, so one such line truncated the file at boot and every
;; event after it vanished silently. Normalize on the way in, by rule (the same
;; deterministic move as the markdown-table salvage — never re-asked, never
;; guessed), and refuse anything that still cannot round-trip.
;; ----------------------------------------------------------------------------

(defn- readable? [x]
  (let [s (pr-str x)]
    (= s (try (pr-str (edn/read-string s)) (catch Exception _ ::unreadable)))))

(defn col-kw
  "Any column name → a keyword that survives a pr-str/read round-trip.
   Keywords that already read back cleanly (`:year`, `:table/rows`) pass through."
  [x]
  (if (and (keyword? x) (readable? x))
    x
    (let [s (-> (if (keyword? x) (subs (str x) 1) (str x))
                str/lower-case
                (str/replace #"[^a-z0-9]+" "_")
                (str/replace #"^_+|_+$" ""))]
      (keyword (if (str/blank? s) "col" s)))))

(defn normalize-keys
  "Only keys can poison a line — a string value with spaces is fine, a keyword
   key with spaces is not. Walk the event and fix the keys."
  [event]
  (walk/postwalk
   (fn [n] (if (map? n)
             (reduce-kv (fn [m k v] (assoc m (cond-> k (keyword? k) col-kw) v)) {} n)
             n))
   event))

(defn safe-event
  "The event as it will be written: keys normalized, round-trip proven. Throws
   rather than append a line that would truncate the log on the next boot."
  [event]
  (let [ev (normalize-keys event)]
    (if (readable? ev)
      ev
      (throw (ex-info "event cannot be read back from the log — refusing to write it"
                      {:id (:id event) :op (:op event)})))))

(defprotocol Store
  (commit! [this event] "append an event map; returns the new tx count")
  (state   [this]       "the materialized current state {:objects {id -> obj}}")
  (objects [this]       "map of id -> object")
  (object  [this id]    "one object by id")
  (history [this]       "the full event log (vector, oldest first)")
  (undo!   [this]       "revert the last commit; returns tx count")
  (as-of   [this n]     "materialized state after the first n events"))

(defmulti apply-event
  "How one event transforms state. Open for extension (an agent's new op is a
   new method, not a fork of the reducer).

   A new method must write only under `[:objects (:id event)]` and read nothing
   outside it (a `:tx` delegates, so its sub-events carry the same obligation).
   `loci.dlv/object-at` rebuilds one object by folding only the events that
   touched that id — an op that derived one object's value from another's, say
   a `:copy`, would make that fold silently disagree with a full one, and the
   disagreement would show up as a wrong answer in the past, not as an error."
  (fn [_state event] (:op event)))

(defmethod apply-event :put     [st {:keys [id value]}] (assoc-in st [:objects id] value))
(defmethod apply-event :assoc   [st {:keys [id path value]}] (assoc-in st (into [:objects id] path) value))
(defmethod apply-event :delete  [st {:keys [id]}] (update st :objects dissoc id))
;; Appending is computed HERE, at apply time, not when the event was built. The
;; old form read the whole cell vector and emitted a whole new one, so two
;; concurrent appends each wrote their own idea of the whole and one was lost —
;; measured, 24 concurrent appends left 6 cells. Deciding the effect at apply
;; time means it is decided wherever the event is already ordered: inside
;; DatalevinStore's commit lock, or in the fold over an EDN log the append to
;; the log vector has itself ordered. Never in a read taken before the race.
;;
;; The effect depends only on the event and the prior state, which is what this
;; multimethod's docstring demands: `object-at` folds only the events that
;; touched one id, so a method that read anything else would answer a wrong
;; past rather than an error.
;;
;; :seed is used only when the path is ABSENT, which is how a legacy notebook
;; (:members, no :cells) gets normalized without a separate racing write. Two
;; writers that both saw it absent carry the same seed, so whichever lands first
;; seeds it and the second simply appends.
(defmethod apply-event :append  [st {:keys [id path value seed]}]
  (update-in st (into [:objects id] path)
             (fn [cur] (conj (vec (or cur seed)) value))))
;; a transaction — several sub-events committed atomically as ONE undoable step
(defmethod apply-event :tx      [st {:keys [events]}] (reduce apply-event st events))
(defmethod apply-event :default [st _] st)

(defn materialize [events]
  (reduce apply-event {:objects {}} events))

(defrecord EventStore [!log]
  Store
  (commit! [_ event]
    (swap! !log conj (safe-event (assoc event :ts (System/currentTimeMillis))))
    (count @!log))
  (state   [_] (materialize @!log))
  (objects [this] (:objects (state this)))
  (object  [this id] (get (objects this) id))
  (history [_] @!log)
  (undo!   [_] (swap! !log (fn [l] (cond-> l (seq l) pop))) (count @!log))
  (as-of   [_ n] (materialize (take n @!log))))

(defn fresh-store [] (->EventStore (atom [])))

;; ----------------------------------------------------------------------------
;; durable flavour — same Store protocol, events land on disk as EDN lines.
;; Boot replays the file; undo! rewrites it (logs are small; correctness over
;; cleverness). Reset = delete the data dir.
;; ----------------------------------------------------------------------------

(defn data-dir
  "Where the logs live. Overridable so demos/tests never clobber real data."
  []
  (or (System/getProperty "loci.data-dir") (System/getenv "LOCI_DATA") "data"))

(defn- load-events
  "Replay the log line by line. A crash mid-append can truncate the LAST line —
   salvage the prefix quietly. An unreadable line anywhere else is a bug: skip
   it so the rest of the history still replays, and say so out loud (it used to
   be swallowed as EOF, silently discarding every event after it)."
  [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (io/reader f)]
        (let [lines (vec (line-seq r))
              last-i (dec (count lines))]
          (reduce-kv
           (fn [acc i line]
             (if (str/blank? line)
               acc
               (let [ev (try (edn/read-string line) (catch Exception _ ::bad))]
                 (cond
                   (not= ::bad ev) (conj acc ev)
                   (= i last-i)    acc                     ; truncated tail — expected
                   :else (do (binding [*out* *err*]
                               (println (str "loci: substrate log line " (inc i)
                                             " is unreadable — skipped: "
                                             (subs line 0 (min 90 (count line))) "…")))
                             acc)))))
           [] lines)))
      [])))

(defn- write-all! [file events]
  (io/make-parents (io/file file))
  (spit file (apply str (map #(str (pr-str %) "\n") events))))

(defrecord PersistentStore [!log file]
  Store
  (commit! [_ event]
    (let [ev (safe-event (assoc event :ts (System/currentTimeMillis)))]
      (io/make-parents (io/file file))
      (spit file (str (pr-str ev) "\n") :append true)
      (count (swap! !log conj ev))))
  (state   [_] (materialize @!log))
  (objects [this] (:objects (state this)))
  (object  [this id] (get (objects this) id))
  (history [_] @!log)
  (undo!   [_]
    (let [l (swap! !log (fn [l] (cond-> l (seq l) pop)))]
      (write-all! file l)
      (count l)))
  (as-of   [_ n] (materialize (take n @!log))))

(defn persistent-store
  ([] (persistent-store (str (data-dir) "/substrate.edn")))
  ([path] (->PersistentStore (atom (load-events path)) path)))

;; ----------------------------------------------------------------------------
;; a read-only window onto the past — the same Store protocol over an `as-of`
;; snapshot, so every reader (mold, notebook, links, payloads) time-travels
;; for free. The past cannot be edited; only viewed.
;; ----------------------------------------------------------------------------

(defrecord FrozenStore [snapshot log-prefix]
  Store
  (commit! [_ _] (throw (UnsupportedOperationException. "read-only: the past cannot be edited")))
  (state   [_] snapshot)
  (objects [_] (:objects snapshot))
  (object  [_ id] (get-in snapshot [:objects id]))
  (history [_] log-prefix)
  (undo!   [_] (throw (UnsupportedOperationException. "read-only: the past cannot be edited")))
  (as-of   [_ n] (materialize (take n log-prefix))))

(defn frozen-at
  "A read-only Store showing the world after the first n events of `st`."
  [st n]
  (let [prefix (vec (take n (history st)))]
    (->FrozenStore (materialize prefix) prefix)))
