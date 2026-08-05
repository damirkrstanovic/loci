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
   Both are derived, so both are rebuildable from `events` alone —
   `rebuild-indices!` does exactly that, which is why a stale or missing index
   is a rebuild and never a data loss. A third dbi, `meta`, holds only the mark
   that says whether the other two can be believed: derived data that is
   half-built is worse than derived data that is missing, because it answers."
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
    (d/open-dbi kv "meta")
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

;; ----------------------------------------------------------------------------
;; the readers of the derived dbis — the reason they are maintained at all
;; ----------------------------------------------------------------------------

(def ^:private dirty-key
  "The mark in `meta` that says the derived dbis do not describe the log.
   ABSENT means trustworthy — a store written before this mark existed has an
   index `commit!` maintained event by event, and must keep answering."
  :indices-dirty)

(defn- kv-of
  "The LMDB env behind `st`, or a refusal that names what was wanted.

   The derived indices live in the env, and a Store without one is not an
   error the caller invented: `store-at` hands a FrozenStore to every `?at=`
   reader, which is the very path `object-at` exists for. It has to hear which
   store this needs, the way `reload!` says it, rather than an
   IllegalArgumentException about a nil that Datalevin cannot dispatch on."
  [st what]
  (or (:kv st)
      (throw (ex-info (str what " needs a DatalevinStore — it reads the durable index, "
                           "and this store has none")
                      {:store (class st) :fn what}))))

(defn- assert-usable!
  "Refuse to answer from an index that is mid-rebuild or was left half-built.

   Every transaction a rebuild writes is atomic, but the sequence of them is
   not, and an index missing its last events answers confidently and wrongly —
   `object-at` would hand back the object as of the last event that made it in.
   Wrong is worse than absent, so the mark goes down before the clear and comes
   off only after the last event, and readers stop rather than guess.

   Writers are not blocked: the log is the source of truth and a derived index
   being stale is no reason to refuse an append. Only readers of the derived
   data can be misled, so only they refuse."
  [kv what]
  (when-let [dirty (d/get-value kv "meta" dirty-key :keyword)]
    (throw (ex-info (str what " will not answer from a torn index — a rebuild was started "
                         "and has not finished, so parts of it describe no log at all. "
                         "The events are intact: run `rebuild-indices!`.")
                    (assoc dirty :fn what)))))

(defn object-at
  "One object as it was after event `n` — folding only the events that touched
   it. Cost is the object's own history, not the world's: measured 0.2 ms
   against a 10M-event log where a full-state fold took 642 ms.

   Folding a subset is sound because every `apply-event` method writes under
   `[:objects id]` and reads nothing outside it, so no event this skips could
   have changed `id` (see `apply-event`, which asks new methods to keep that
   true). A `:tx` in the list replays all of its sub-events, including ones for
   other objects — harmless, since only `id` is read back.

   nil when the object did not exist yet, never existed, or had been deleted by
   `n`: the same answer `(get-in (as-of st n) [:objects id])` gives, which is
   the property the tests hold it to."
  [st id n]
  (let [kv (kv-of st "object-at")]
    (assert-usable! kv "object-at")
    ;; `list-range` bounded at n, not `get-list` filtered afterwards:
    ;; `get-list` loops the iterator to exhaustion into a vector, so a
    ;; take-while over it still pays for the object's whole FUTURE — for a
    ;; notebook touched by every event in its own history, dragging the ⏱
    ;; scrubber to an early frame is the worst case, and it is the case this
    ;; index exists to make cheap. Measured through this fn, 16001 events with
    ;; an 8001-entry touch list: n=1 3.21 → 0.03 ms, n=101 2.97 → 0.23 ms.
    ;; The far end costs ~20% more (pairs, not bare values, and both read it
    ;; all) against a fold that by then dominates — the right trade for a
    ;; reader whose whole purpose is the past.
    (let [idxs (map second (d/list-range kv "touched" [:closed id id] :string [:at-most n] :long))]
      (when (seq idxs)
        (-> (reduce (fn [state i]
                      (sub/apply-event state (d/get-value kv "events" i :long)))
                    {:objects {}} idxs)
            (get-in [:objects id]))))))

(defn counts-at
  "`{:objects n :kinds {kind n}}` after event `n` — an O(1) read, never a scan.
   nil for an event that does not exist (never committed, or undone)."
  [st n]
  (let [kv (kv-of st "counts-at")]
    (assert-usable! kv "counts-at")
    (d/get-value kv "counts" n :long)))

(defn clear-indices!
  "Drop the derived dbis. They are rebuildable; the log is untouched.

   Marks them untrustworthy BEFORE dropping them. In the other order, a clear
   that half-succeeded would leave an index that reads as believable and is
   not; in this order the worst case is a mark that outlives its reason, and
   the cure for that is a rebuild — which is what the caller was doing anyway."
  [st]
  (let [kv (kv-of st "clear-indices!")]
    (d/transact-kv kv [[:put "meta" dirty-key {:since (System/currentTimeMillis)} :keyword]])
    (d/clear-dbi kv "touched")
    (d/clear-dbi kv "counts")))

(defn rebuild-indices!
  "Replay the log to rebuild `touched` and `counts`. Safe to run at any time —
   a stale or missing index is a rebuild, never a data loss.

   Replays the DURABLE log, not the in-RAM one. The caller this exists for is
   the bulk writer that put events into `events` by some other route than
   `commit!`; asking it to `reload!` first would be a precondition it could
   forget, and forgetting it builds an index that is silently short.

   Under the same lock `commit!` takes: between the clear and the last event
   rebuilt the index does not describe the log, and an `undo!` landing in that
   window would have its deleted event written back into the index by a replay
   that had already read it.

   Clears first, so a rebuild is a rebuild and not a merge with whatever a
   rewritten log left behind; and lifts the untrustworthy mark last, so a
   rebuild that dies partway leaves readers refusing rather than guessing."
  [st]
  (let [kv (kv-of st "rebuild-indices!")]
    (locking (:lock st)
      (clear-indices! st)
      (reduce (fn [state [i ev]]
                (let [state' (sub/apply-event state ev)]
                  ;; one transaction per event, same op shapes commit! uses
                  (d/transact-kv kv
                                 (into [[:put "counts" i (census state') :long]]
                                       (map (fn [id] [:put-list "touched" id [i] :string :long]))
                                       (touched-ids ev)))
                  state'))
              {:objects {}}
              (map-indexed (fn [k ev] [(inc k) ev]) (read-log kv)))
      (d/transact-kv kv [[:del "meta" dirty-key :keyword]])
      :ok)))
