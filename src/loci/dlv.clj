(ns loci.dlv
  "Layer 1, durable: the same append-only event log, on Datalevin (LMDB).

   The log vector and the materialized state stay in RAM — that is what keeps
   `history`, `as-of` and `frozen-at` behaving exactly as they did over the EDN
   log, and what takes `state` off the per-request re-fold. Datalevin is the
   durable, indexed backing: events serialize natively (nippy), so there is no
   pr-str/read-string anywhere in the write path and a half-written or
   unreadable line cannot exist.

   Two derived dbis are maintained for later phases:
     · `touched` — object id → the event indices that touched it (lazy as-of)
     · `counts`  — event index → {:objects n :spaces n} (the ⏱ header, O(1))
   Both are rebuildable from `events`; `rebuild-indices!` does it."
  (:require [datalevin.core :as d]
            [loci.substrate :as sub]))

(defn- touched-ids
  "Which object ids an event writes. A :tx touches everything its sub-events do."
  [ev]
  (case (:op ev)
    :tx (into #{} (mapcat touched-ids) (:events ev))
    (if-let [id (:id ev)] #{id} #{})))

(defn- count-pair [state]
  {:objects (count (:objects state))
   :spaces  (count (filter #(= :space (:kind %)) (vals (:objects state))))})

(defn- open-env! [dir]
  (let [kv (d/open-kv dir {:mapsize 4096})]     ; MB — see map-full! below
    (d/open-dbi kv "events")
    (d/open-dbi kv "counts")
    (d/open-list-dbi kv "touched")
    kv))

(defn- map-full!
  "LMDB preallocates a maximum size and refuses writes once it is reached. Say
   so in words the caller can act on, instead of leaking MDB_MAP_FULL upward."
  [^Exception e dir]
  (if (re-find #"(?i)map.?full" (str (.getMessage e)))
    (throw (ex-info (str "the substrate is full — LMDB's map size at " dir
                         " is exhausted. Raise :mapsize (MB) in open-env! and restart; "
                         "nothing was written and nothing was lost.")
                    {:dir dir} e))
    (throw e)))

(defn- read-log [kv]
  (into [] (map second) (d/get-range kv "events" [:all] :long)))

(defrecord DatalevinStore [kv dir !log !state]
  sub/Store
  (commit! [_ event]
    (let [ev (sub/normalize-keys (assoc event :ts (System/currentTimeMillis)))
          i  (inc (count @!log))
          st (sub/apply-event @!state ev)]
      ;; durable first, RAM after — a refused write must leave the store exactly
      ;; where it was, or `map-full!`'s "nothing was lost" would be a lie
      (try
        (d/transact-kv kv [[:put "events" i ev :long]
                           [:put "counts" i (count-pair st) :long]])
        (catch Exception e (map-full! e dir)))
      ;; `touched` is derived — a crash between these two costs a rebuild, not data
      (doseq [id (touched-ids ev)]
        (d/put-list-items kv "touched" id [i] :string :long))
      (reset! !state st)
      (count (swap! !log conj ev))))
  (state   [_] @!state)
  (objects [_] (:objects @!state))
  (object  [_ id] (get-in @!state [:objects id]))
  (history [_] @!log)
  (undo!   [_]
    (let [i (count @!log)]
      (when (pos? i)
        (let [ev  (peek @!log)
              log (swap! !log pop)]
          (d/transact-kv kv [[:del "events" i :long] [:del "counts" i :long]])
          (doseq [id (touched-ids ev)]
            (d/del-list-items kv "touched" id [i] :string :long))
          (reset! !state (sub/materialize log))))
      (count @!log)))
  (as-of   [_ n] (sub/materialize (take n @!log))))

(defn datalevin-store
  "Open (or create) a durable store in `dir`. Defaults to <data-dir>/substrate."
  ([] (datalevin-store (str (sub/data-dir) "/substrate")))
  ([dir]
   (let [kv  (open-env! dir)
         log (read-log kv)]
     (->DatalevinStore kv dir (atom log) (atom (sub/materialize log))))))

(defn close! [st] (d/close-kv (:kv st)))
