(ns loci.dlv
  "Layer 1, durable: the same append-only event log, on Datalevin (LMDB).

   The log vector and the materialized state stay in RAM, in ONE atom — that is
   what keeps `history`, `as-of` and `frozen-at` behaving exactly as they did
   over the EDN log, and what takes `state` off the per-request re-fold. One
   atom rather than two because over the EDN log `state` was *derived* from the
   log and so could never disagree with it; caching the fold is only free if
   the pair is still published, and read, as a single value (`snapshot`).
   Datalevin is the durable, indexed backing: events serialize natively
   (nippy), so there is no pr-str/read-string anywhere in the write path and a
   half-written or unreadable line cannot exist.

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

(defn- read-log
  "The durable events, oldest first.

   An event's key IS its position: that is what lets `commit!` derive the next
   index from the count and `undo!` delete by it. So the keys must be exactly
   1..n, and a gap is not a thing to route around — the next `commit!` would
   reuse a key that is still live and overwrite a durable event while telling
   the caller it succeeded. `commit!`/`undo!` cannot produce a gap; a bulk
   writer can, which is exactly the caller `reload!` is for. Refuse, and say
   where, rather than repair silently."
  [kv]
  (reduce (fn [log [k ev]]
            (if (= k (inc (count log)))
              (conj log ev)
              (throw (ex-info (str "substrate event keys are not contiguous — expected "
                                   (inc (count log)) ", found " k
                                   ". The events are intact; they must be renumbered 1..n "
                                   "before this log can be replayed.")
                              {:expected (inc (count log)) :found k}))))
          []
          (d/get-range kv "events" [:all] :long)))

(defn- db-of
  "The RAM half of the store: a log and the state it folds to, in one value so
   they cannot be published — or read — apart."
  [log]
  {:log log :state (sub/materialize log)})

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

(defrecord DatalevinStore [kv dir !db lock]
  sub/Store
  ;; `lock` makes commit! and undo! mutually exclusive. Reading the index and
  ;; the base state, then writing both back, is only correct if nothing
  ;; interleaves: the server commits from `future`s (start-job!) while the
  ;; request thread commits too, and unserialized that loses both durable
  ;; events and in-RAM objects. `locking` rather than a `swap!` because the
  ;; critical section writes to LMDB, and swap! may retry — a side effect
  ;; inside a retried swap happens twice.
  ;;
  ;; Readers take no lock; they do not need one, because log and state are one
  ;; value published in one write. A reader can be a commit behind, never
  ;; half-past it.
  (commit! [_ event]
    (let [ev (sub/normalize-keys (assoc event :ts (System/currentTimeMillis)))]
      (locking lock
        (let [{:keys [log state]} @!db
              i  (inc (count log))
              st (sub/apply-event state ev)]
          ;; durable first, RAM after: a refused write leaves the store exactly
          ;; where it was rather than one event ahead of its own log
          (d/transact-kv kv (commit-tx i ev st))
          (count (:log (reset! !db {:log (conj log ev) :state st})))))))
  (state   [_] (:state @!db))
  (objects [_] (get-in @!db [:state :objects]))
  (object  [_ id] (get-in @!db [:state :objects id]))
  (history [_] (:log @!db))
  (undo!   [_]
    (locking lock
      (let [log (:log @!db)
            i   (count log)]
        (if (pos? i)
          (let [ev (peek log)]
            (d/transact-kv kv (undo-tx i ev))
            (count (:log (reset! !db (db-of (pop log))))))
          i))))
  (as-of   [_ n] (sub/materialize (take n (:log @!db)))))

(defn datalevin-store
  "Open (or create) a durable store in `dir`. Defaults to <data-dir>/substrate."
  ([] (datalevin-store (str (sub/data-dir) "/substrate")))
  ([dir]
   (let [kv (open-env! dir)]
     (try
       (->DatalevinStore kv dir (atom (db-of (read-log kv))) (Object.))
       ;; an env we are not returning must not stay open — it would hold the
       ;; directory for the life of the JVM and no one would have the handle
       (catch Throwable t (d/close-kv kv) (throw t))))))

(defn view
  "The log and the state as ONE consistent value, `{:log [...] :state {...}}`.
   Anything that needs both must take them from a single view — two calls
   (`history` then `state`) are two reads, and a commit can land between them.

   Serves any Store, not just this one: `?at=N` hands a FrozenStore to every
   reader, and a FrozenStore's pair is two immutable fields, so reading them
   separately is already consistent. For a live Store that is neither — an
   EventStore, a PersistentStore — the fallback is two reads and can only be as
   atomic as that store allows; state is read first so that if the pair does
   skew, it skews the way those stores already skew (log ahead of state, never
   an object with no event)."
  [st]
  (if-let [!db (:!db st)]
    @!db
    (let [state (sub/state st)]
      {:log (sub/history st) :state state})))

(defn reload!
  "Re-derive the in-RAM pair from what is actually durable. For a caller that
   has written events to `events` by some other route than `commit!` — a bulk
   migration, an index rebuild — this is how it republishes RAM without having
   to be trusted to hand over the same log it wrote.

   Unlike `view`, this is not something every Store can do, and quietly doing
   nothing for one that cannot would hide the caller's mistake."
  [st]
  (if-let [lock (:lock st)]
    (do (locking lock
          (reset! (:!db st) (db-of (read-log (:kv st)))))
        st)
    (throw (ex-info (str "reload! needs a DatalevinStore — it re-reads the durable log, "
                         "and this store has none")
                    {:store (class st)}))))

(defn close!
  "Close the LMDB env behind `st`. A no-op for a Store that has none — a
   FrozenStore is a legitimate Store for a caller to be holding."
  [st]
  (when-let [kv (:kv st)] (d/close-kv kv)))
