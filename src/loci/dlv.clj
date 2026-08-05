(ns loci.dlv
  "Layer 1, durable: the same append-only event log, on Datalevin (LMDB).

   The log vector and the materialized state stay in RAM — that is what keeps
   `history`, `as-of` and `frozen-at` behaving exactly as they did over the EDN
   log, and what takes `state` off the per-request re-fold. Datalevin is the
   durable, indexed backing: events serialize natively (nippy), so there is no
   pr-str/read-string anywhere in the write path and a half-written or
   unreadable line cannot exist.

   Because there is no pr-str, there is no `sub/safe-event` either — events go
   through `sub/normalize-keys` only. Keys are still rewritten to survive a
   round-trip (the datalog and full-text phases want clean column names), but
   VALUES are no longer checked, so a log written here is not guaranteed to be
   EDN-expressible. Nippy keeps what Clojure holds. Worth naming because it
   makes the direction asymmetric: EDN log → here is always safe, here → EDN
   log is not.

   Two derived dbis are maintained for later phases:
     · `touched` — object id → the event indices that touched it (lazy as-of)
     · `counts`  — event index → {:objects n :kinds {kind n}} (the ⏱ header)
   Both are derived, so both are rebuildable from `events` alone; the next task
   adds `rebuild-indices!` to do it."
  (:require [datalevin.core :as d]
            [loci.substrate :as sub]))

(defn- touched-ids
  "Which object ids an event writes. A :tx touches everything its sub-events do."
  [ev]
  (case (:op ev)
    :tx (into #{} (mapcat touched-ids) (:events ev))
    (if-let [id (:id ev)] #{id} #{})))

(defn- census
  "The per-event count record: how many objects, and how many of each kind.
   Layer 1 stores the honest histogram and lets each view derive its own total
   — the shell's object count hides spaces, viewspecs, applets and fns, but
   that is the shell's rule, not the log's, and the ⏱ scrubber must not show a
   past that counts differently from the present."
  [state]
  (let [objs (vals (:objects state))]
    {:objects (count objs)
     :kinds   (frequencies (keep :kind objs))}))

(defn- open-env! [dir]
  ;; deliberately no :mapsize — Datalevin honours it only when the directory
  ;; does not yet exist (binding/cpp.clj), and grows the map itself when a
  ;; write hits MDB_MAP_FULL. Pinning it here would be a knob that silently
  ;; stopped applying after the first boot.
  (let [kv (d/open-kv dir)]
    (d/open-dbi kv "events")
    (d/open-dbi kv "counts")
    (d/open-list-dbi kv "touched")
    kv))

(defn- read-log [kv]
  (into [] (map second) (d/get-range kv "events" [:all] :long)))

(defn- commit-tx
  "Event, counts and touch index as ONE transaction. Splitting them would let a
   failure land the event durably while the caller is told the commit failed —
   the object then appears out of nowhere after the next restart."
  [i ev st]
  (into [[:put "events" i ev :long]
         [:put "counts" i (census st) :long]]
        (map (fn [id] [:put-list "touched" id [i] :string :long]))
        (touched-ids ev)))

(defn- undo-tx [i ev]
  (into [[:del "events" i :long] [:del "counts" i :long]]
        (map (fn [id] [:del-list "touched" id [i] :string :long]))
        (touched-ids ev)))

(defrecord DatalevinStore [kv dir lock !log !state]
  sub/Store
  ;; `lock` makes commit! and undo! mutually exclusive. Reading the index off
  ;; !log and the base state off !state, then writing both back, is only
  ;; correct if nothing interleaves: the server commits from `future`s
  ;; (start-job!) while the request thread commits too, and unserialized that
  ;; loses both durable events and in-RAM objects. `locking` rather than a
  ;; `swap!` because the critical section writes to LMDB, and swap! may retry —
  ;; a side effect inside a retried swap happens twice.
  (commit! [_ event]
    (let [ev (sub/normalize-keys (assoc event :ts (System/currentTimeMillis)))]
      (locking lock
        (let [i  (inc (count @!log))
              st (sub/apply-event @!state ev)]
          ;; durable first, RAM after: a refused write leaves the store exactly
          ;; where it was rather than one event ahead of its own log. State
          ;; before log, so a reader (which takes no lock) can never see a log
          ;; entry whose effect is missing from `state`.
          (d/transact-kv kv (commit-tx i ev st))
          (reset! !state st)
          (count (swap! !log conj ev))))))
  (state   [_] @!state)
  (objects [_] (:objects @!state))
  (object  [_ id] (get-in @!state [:objects id]))
  (history [_] @!log)
  (undo!   [_]
    (locking lock
      (let [i (count @!log)]
        (when (pos? i)
          (let [ev  (peek @!log)
                log (pop @!log)]
            (d/transact-kv kv (undo-tx i ev))
            (reset! !log log)
            (reset! !state (sub/materialize log))))
        (count @!log))))
  (as-of   [_ n] (sub/materialize (take n @!log))))

(defn datalevin-store
  "Open (or create) a durable store in `dir`. Defaults to <data-dir>/substrate."
  ([] (datalevin-store (str (sub/data-dir) "/substrate")))
  ([dir]
   (let [kv  (open-env! dir)
         log (read-log kv)]
     (->DatalevinStore kv dir (Object.) (atom log) (atom (sub/materialize log))))))

(defn close!
  "Close the LMDB env behind `st`. A no-op for a Store that has none — a
   FrozenStore is a legitimate Store for a caller to be holding."
  [st]
  (when-let [kv (:kv st)] (d/close-kv kv)))
