(ns loci.server-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [loci.agent :as agent]
            [loci.content :as c]
            [loci.embed :as embed]
            [loci.entities :as ent]
            [loci.files :as files]
            [loci.lake :as lake]
            [loci.lake-fixture :as lf]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.server :as srv]
            [loci.source :as src]
            [loci.substrate :as sub]
            [loci.tablestore :as tbl]
            [loci.tools :as tools])
  (:import [java.util Locale]))

(use-fixtures :once loci.lake-fixture/with-temp-lake)

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

;; ---------------------------------------------------------------------------
;; the boot path: what exit does about the lake
;;
;; `-main` cannot be called from a test — it binds a port and blocks on a
;; promise — so the hook is registered by `close-lake-at-exit!`, which hands
;; back the thread. `.run` on it is the hook's body on this thread, which is
;; the only way to see a shutdown hook without shutting down. The hook is
;; always removed again: it is registered against the real JVM, and a suite
;; that left them behind would close a stranger's connection at exit.
;; ---------------------------------------------------------------------------

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defn- with-temp-data-dir
  "Run `f` with `sub/data-dir` answering a fresh temp directory, removed after.

   `import-csv!` writes the posted text under `<LOCI_DATA>/files/` before it
   connects, so without this the suite drops a CSV into the real `data/` on
   every run. Redefining the ONE function is what keeps the round trip whole:
   `sourcespec/locate` resolves `loci://` through the same var, so the write and
   the read cannot end up in different directories."
  [f]
  (let [d (str (System/getProperty "java.io.tmpdir") "/loci-server-data-" (System/nanoTime))]
    (try (with-redefs [sub/data-dir (constantly d)] (f))
         (finally (rm-rf d)))))

(defn- rows-of
  "A table object's rows, through the same seam the server reads them through.

   Since every writer lands a handle, `(:value (sub/object st id))` is no longer
   rows and asserting on it asserts on the handle's own keys. `hydrate` is what
   the fifteen production call sites use, and it passes a literal vector through
   untouched — so a test that writes rows into `:value` by hand still reads back
   what it wrote.

   **Which is exactly why a test that reads through here cannot tell a converted
   writer from an unconverted one.** That blind spot is not hypothetical: it is
   how `rerun!` — the seventh writer, which assocs over `:value` and so builds no
   `:kind :table` literal for a grep to find — went on putting rows in the log
   with every rerun test passing. Assertions that a writer *landed a handle* must
   read `(:value (sub/object …))` raw; see `every-server-writer-lands-a-handle`
   and `rerun-writes-a-handle-and-not-the-refreshed-rows`."
  [st id]
  (:value (tbl/hydrate lake/lake (sub/object st id))))

(deftest exit-closes-a-lake-that-was-opened
  (let [dir (str (System/getProperty "java.io.tmpdir") "/loci-hook-" (System/nanoTime))
        lk  (delay (lake/open-lake! dir))
        t   (#'srv/close-lake-at-exit! lk)]
    (try
      (is (= [{:answer 2}] (lake/q @lk "SELECT 1 + 1 AS answer")))
      (.run t)
      (is (thrown-with-msg? java.sql.SQLException #"Connection was closed"
                            (lake/q @lk "SELECT 1 AS answer"))
          "exit must close the connection, not just let the process drop it")
      (finally
        (.removeShutdownHook (Runtime/getRuntime) t)
        (rm-rf dir)))))

(deftest exit-opens-no-lake-that-was-never-opened
  ;; The guard `close-lake-at-exit!` exists for. A bare `@lk` in the hook opens
  ;; a lake on the way out of a process that never wanted one — and against a
  ;; delay whose body threw, rethrows on the shutdown thread. This is the test
  ;; that fails if the `realized?` check goes, and the reason the hook is
  ;; registered before `-main` forces anything.
  (let [forced (atom 0)
        lk     (delay (swap! forced inc))
        t      (#'srv/close-lake-at-exit! lk)]
    (try
      (.run t)
      (is (zero? @forced) "a hook that runs before the boot force must open nothing")
      (finally (.removeShutdownHook (Runtime/getRuntime) t)))))

(deftest exit-says-nothing-about-a-lake-that-failed-to-open
  ;; `realized?` is true for a delay whose body THREW, and every later deref
  ;; rethrows the cached exception — so a boot that could not open the lake
  ;; printed "closing the lake threw:" and the open failure's whole stack trace
  ;; on the way out, UNDER the real error `-main` had just printed. The guard
  ;; exists to keep exit quiet about a lake nobody opened; a lake that could
  ;; not be opened is the same case and was getting the loudest treatment.
  (let [lk  (delay (throw (ex-info "ATTACH failed" {})))
        err (java.io.StringWriter.)]
    (is (thrown? clojure.lang.ExceptionInfo @lk) "realize it by failing it")
    (is (realized? lk) "which is exactly the trap: a thrown body still realizes")
    (let [t (#'srv/close-lake-at-exit! lk)]
      (try
        (binding [*err* err] (.run t))
        (is (= "" (str err)) "a failed boot has nothing to close and nothing to say")
        (finally (.removeShutdownHook (Runtime/getRuntime) t))))))

(deftest exit-still-reports-a-close-that-fails
  ;; The other side of the same line, and the reason the `try` stays: a lake
  ;; that opened and then would not close is news, and silence there would lose
  ;; an uncheckpointed WAL without saying so.
  (let [lk  (delay {:conn (reify java.io.Closeable
                            (close [_] (throw (ex-info "connection is wedged" {}))))})
        err (java.io.StringWriter.)]
    @lk
    (let [t (#'srv/close-lake-at-exit! lk)]
      (try
        (binding [*err* err] (.run t))
        (is (str/includes? (str err) "closing the lake threw")
            "a genuine close failure must still be reported")
        (finally (.removeShutdownHook (Runtime/getRuntime) t))))))

(def ^:private no-memory
  "The memory `mold-payload` must not open — a delay that throws if it is forced.

   `mold-payload` takes a memory because a `:kind :entity` object's page IS
   `entity-payload`'s answer, and it forces that argument on the entity branch
   and on no other. Every object molded with this one is a doc, a table or a
   flow, so passing a poison delay costs nothing to write and turns \"it happens
   not to need the memory\" into an assertion: hoist the force out of the branch
   and these stop rendering and start throwing.

   It is also HALF the reason the in-process callers may pass `mem/memory`
   undereferenced, and only half — say which half. Hoisting the force catches
   nine tests through this def. It does not catch `(srv/edit! st \"doc:a\" \"y\")`
   below, which reaches `mold-payload` through `edit!` and so would force the
   real `mem/memory`, read the user's `data/memory.edn` and pass. That is the
   seam the laziness exists for and the seam no test can watch; this one watches
   the other."
  (delay (throw (ex-info "mold-payload forced the memory to render a non-entity" {}))))

(deftest leap-searches-everything
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "doc:x" :value {:id "doc:x" :kind :doc :title "Pricing notes"
                                                  :value "The plan rose 18% in March."}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "Notebook N"
                                                    :value {:intent "i"
                                                            :cells [{:text "churn concentrated in young accounts"}]}}})
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (let [groups (fn [q] (set (map :group (srv/leap-payload st m q))))]
      (is (contains? (groups "churn") "prose"))        ; cell prose
      (is (contains? (groups "march") "in text"))      ; doc body, title doesn't match
      (is (contains? (groups "downgrades") "memory"))  ; memory fact
      (is (contains? (groups "notebook") "space")))))  ; notebook by title

(deftest leap-finds-views-and-functions-with-target
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "tbl:p" :value {:id "tbl:p" :kind :table :title "Planets" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "app:p-1"
                     :value {:id "app:p-1" :kind :applet :title "app: orbits"
                             :value {:target "tbl:p" :code ";" :label "▶ orbit animation"}}})
    (sub/commit! st {:op :put :id "fn:p-1"
                     :value {:id "fn:p-1" :kind :fn :title "fn: densities"
                             :value {:source "tbl:p" :lang "clojure" :code "rows"}}})
    (let [hits  (srv/leap-payload st m "orbit")
          orbit (first (filter #(= "app:p-1" (:id %)) hits))]
      (is (some? orbit))
      (is (= "views & functions" (:group orbit)))
      (is (= "tbl:p" (:target orbit))))
    (is (some #(= "fn:p-1" (:id %)) (srv/leap-payload st m "densities")))
    ;; query-only, like prose: the empty listing stays uncluttered
    (is (not-any? #(= "app:p-1" (:id %)) (srv/leap-payload st m "")))))

(deftest notebook-op-rejects-non-notebooks
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    ;; non-space target: rejected, nothing committed, state still materializes
    (is (:error (srv/notebook-op! st {:space "tbl:t" :op "add-text" :text "x"})))
    (is (:error (srv/notebook-op! st {:space "nope" :op "add-text" :text "x"})))
    (is (= 2 (count (sub/history st))))
    ;; unknown op: no phantom event committed
    (srv/notebook-op! st {:space "space:n" :op "bogus"})
    (is (= 2 (count (sub/history st))))
    ;; a real op still works
    (srv/notebook-op! st {:space "space:n" :op "add-text" :text "hi"})
    (is (= 3 (count (sub/history st))))
    (is (= [{:text "hi"}] (get-in (sub/object st "space:n") [:value :cells])))))

;; ---------------------------------------------------------------------------
;; concurrent cell operations
;;
;; `notebook-op!` reads a notebook's whole cell vector, computes the new one and
;; writes the whole thing back — the read-modify-write `append-cell-event`
;; stopped doing after 24 concurrent appends left 6 cells. Every event lands;
;; what is lost is content, because each writer computed its vector from a read
;; taken before the others committed.
;; ---------------------------------------------------------------------------

(def ^:private cell-seeds
  (mapv (fn [i] {:text (str "seed-" i)}) (range 24)))

(def ^:private cell-op-mix
  "The op mix, chosen so that the cell COUNT and the fate of every appended cell
   follow from the ops alone, not from the order they happen to serialise in:

     add-text   +1, carrying a text nothing else writes
     move       a permutation inside [0,4) — count-neutral
     edit-text  idx in [4,8) — count-neutral, and every cell here is a text cell
                so `cell-op`'s type guard never turns it into a no-op
     remove     idx 0 — exactly -1

   24 seeds and 4 removes means the vector never falls below 20, so no index
   used here is ever out of range: every op really applies rather than falling
   through `cell-op`'s totality. And an appended cell lands at index >= 19 and
   drops at most 4 as removes shift it down, while moves reach only [0,4) — so
   nothing that was added can be moved, edited or removed away by this mix."
  (vec (concat (for [i (range 12)] {:op "add-text" :text (str "add-" i)})
               (for [i (range 4)]  {:op "move" :idx i :to (- 3 i)})
               (for [i (range 4)]  {:op "edit-text" :idx (+ 4 i) :text (str "edit-" i)})
               (repeat 4 {:op "remove" :idx 0}))))

(defn- cell-ops-together!
  "Fire every op at one notebook from its own thread, released together. The
   latch is what makes them collide: started threads alone would mostly run one
   after another."
  [st space ops]
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        ths   (mapv (fn [op] (Thread. (fn []
                                        (.await latch)
                                        (srv/notebook-op! st (assoc op :space space)))))
                    ops)]
    (doseq [t ths] (.start t))
    (.countDown latch)
    (doseq [t ths] (.join t))
    (nb/cells-of (sub/object st space))))

(deftest concurrent-cell-ops-lose-no-cell
  (let [st       (sub/fresh-store)
        ops      cell-op-mix
        adds     (set (keep :text (filter #(= "add-text" (:op %)) ops)))
        edits    (set (keep :text (filter #(= "edit-text" (:op %)) ops)))
        universe (into (set (map :text cell-seeds)) (concat adds edits))
        expected (- (+ (count cell-seeds) (count adds)) 4)]   ; seeds + adds - removes
    (sub/commit! st {:op :put :id "space:n"
                     :value {:id "space:n" :kind :space :title "N"
                             :value {:intent "i" :cells cell-seeds}}})
    (let [final (cell-ops-together! st "space:n" ops)
          texts (mapv :text final)]
      (is (= expected (count final))
          (str "no cell lost: " (count final) " cells survived of " expected))
      (is (every? (set texts) adds)
          "every concurrently appended cell is still there")
      (is (= (count texts) (count (set texts)))
          "and no op was applied twice")
      (is (every? universe texts)
          "no phantom cell: everything present was written by a seed or an op")
      ;; Deliberately NOT asserted, because it is genuinely not guaranteed:
      ;; the final ORDER (concurrent reorders conflict, and the winner is
      ;; whichever serialised last), and WHICH cell an index-addressed op landed
      ;; on — a `remove` at index 0 removes whatever the reorders left there.
      ;; That is last-writer-wins on intent, which is a design question. Losing a
      ;; cell is not; that is the defect, and it is what the assertions above
      ;; pin down. For the same reason the `edit-` texts are not all required to
      ;; survive: two edits at different indices can land on the same cell once
      ;; a remove has shifted it, and then the later one legitimately wins.
      )))

(deftest editing-two-notebooks-does-not-serialise
  ;; The lock is per notebook. If a later simplification collapses it to one
  ;; global monitor, this hangs: the fast notebook's edit would wait behind an
  ;; unrelated notebook's. Held open by a latch rather than a sleep, so the
  ;; happy path costs nothing and the failing path is a timeout, not a guess.
  (let [st      (sub/fresh-store)
        entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        done    (promise)
        secs    java.util.concurrent.TimeUnit/SECONDS
        real    nb/cell-op]
    (doseq [id ["space:slow" "space:fast"]]
      (sub/commit! st {:op :put :id id
                       :value {:id id :kind :space :title id :value {:cells []}}}))
    (with-redefs [nb/cell-op (fn [cells body]
                               ;; block INSIDE the critical section, only for one notebook
                               (when (= "space:slow" (:space body))
                                 (.countDown entered)
                                 (.await release 10 secs))
                               (real cells body))]
      (let [slow (Thread. #(srv/notebook-op! st {:space "space:slow" :op "add-text" :text "s"}))
            fast (Thread. #(do (srv/notebook-op! st {:space "space:fast" :op "add-text" :text "f"})
                               (deliver done true)))]
        (.start slow)
        (is (.await entered 10 secs) "the slow notebook's op reached the critical section")
        (.start fast)
        (is (true? (deref done 5000 false))
            "an edit to a different notebook must not wait on the first")
        (.countDown release)
        (.join slow)
        (.join fast)
        (is (= [{:text "s"}] (nb/cells-of (sub/object st "space:slow"))))
        (is (= [{:text "f"}] (nb/cells-of (sub/object st "space:fast"))))))))

(deftest edit-rejects-missing-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (is (:error (srv/edit! st nil "v")))
    (is (:error (srv/edit! st "nope" "v")))
    (is (= 1 (count (sub/history st))))          ; nothing committed
    (is (nil? (:error (srv/edit! st "doc:a" "y"))))
    (is (= "y" (:value (sub/object st "doc:a"))))))

(defn- await-job [id]
  (loop [n 0]
    (let [s (srv/job-status id)]
      (if (or (:done s) (> n 100))
        s
        (do (Thread/sleep 50) (recur (inc n)))))))

(deftest jobs-run-async-and-surface-results
  (let [id (srv/start-job! (fn [] {:x 1}))]
    (is (string? id))
    (let [s (await-job id)]
      (is (:done s))
      (is (= {:x 1} (:result s))))))

(deftest jobs-surface-thrown-errors
  (let [s (await-job (srv/start-job! (fn [] (throw (Exception. "boom")))))]
    (is (:done s))
    (is (= "boom" (get-in s [:result :error])))))

(deftest unknown-job-is-done-with-error
  (let [s (srv/job-status "job:nope")]
    (is (:done s))                 ; a poller must stop, not spin forever
    (is (:error s))))

(deftest suggest-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/suggest-start! st "tbl:t")))     ; not a notebook
    (is (:error (srv/suggest-start! st "nope")))      ; missing
    (with-redefs [srv/suggest! (fn [_ _] {:proposals [{:title "A"}]})]
      (let [{:keys [job error]} (srv/suggest-start! st "space:h")]
        (is (nil? error))
        (is (= [{:title "A"}] (get-in (await-job job) [:result :proposals])))))))

(deftest suggest-run-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/suggest-run-start! st "nope" [{:title "A"}] "new")))   ; missing notebook
    (with-redefs [srv/run-suggestions! (fn [_ _ _ _] {:ran ["space:dd-1"]})]
      (let [{:keys [job error]} (srv/suggest-run-start! st "space:h" [{:title "A"}] "new")]
        (is (nil? error))
        (is (= ["space:dd-1"] (get-in (await-job job) [:result :ran])))))))

(deftest research-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/research-start! st "nope" "q")))   ; missing notebook
    (is (:error (srv/research-start! st "space:h" " "))) ; blank prompt
    (with-redefs [srv/research! (fn [_ _ _] {:openId "find:h-1"})]
      (let [{:keys [job error]} (srv/research-start! st "space:h" "q")]
        (is (nil? error))
        (is (= "find:h-1" (get-in (await-job job) [:result :openId])))))))

(deftest state-payload-exposes-spawned-by
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "space:dd-1"
                     :value {:id "space:dd-1" :kind :space :title "Child"
                             :value {:intent "sub" :cells []
                                     :spawned-by {:space "space:h" :prompt "q"}}}})
    (let [by-id (into {} (map (juxt :id identity)) (:spaces (srv/state-payload st)))]
      (is (= "space:h" (get-in by-id ["space:dd-1" :spawned-by])))
      (is (nil? (get-in by-id ["space:h" :spawned-by]))))))

(deftest note-ids-survive-cell-removal
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    (srv/keep-note! st "space:n" "one" "1")
    (srv/keep-note! st "space:n" "two" "2")
    (srv/keep-note! st "space:n" "three" "3")
    ;; remove the MIDDLE note's cell (idx 1) — note:n-2's object lives on
    (srv/notebook-op! st {:space "space:n" :op "remove" :idx 1})
    (srv/keep-note! st "space:n" "four" "4")
    ;; the new note must NOT reuse an existing id
    (is (= "3" (:value (sub/object st "note:n-3"))))   ; old note untouched
    (is (= "4" (:value (sub/object st "note:n-4"))))))  ; new note got a fresh id

(def ^:private trows [{:region "EMEA" :revenue 100} {:region "APAC" :revenue 250}])

(defn- store-with-table []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value trows}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    st))

(deftest fns-payload-lists-builtins-and-agent-fns
  (let [st (store-with-table)]
    (is (:error (srv/fns-payload st "space:n")))          ; not a table
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [ids (set (map :id (:fns (srv/fns-payload st "tbl:t"))))]
      (is (contains? ids "lib:filter"))
      (is (contains? ids "fn:t-1")))))

(deftest fn-preview-commits-nothing
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-preview st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"})]
    (is (= [{:region "APAC" :revenue 250}] (:after r)))
    (is (= 2 (count (:before r))))
    (is (= 1 (:count r)))
    (is (= before (count (sub/history st))))))            ; preview is FREE

(deftest fn-apply-commits-one-tx-with-provenance
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")
        nid (:openId r)
        t (sub/object st nid)]
    (is (= (inc before) (count (sub/history st))))        ; ONE event (a :tx)
    (is (= "tbl:t" (:from t)))
    (is (= "lib:top" (:via t)))
    (is (some #(= nid (:ref %)) (get-in (sub/object st "space:n") [:value :cells])))
    (sub/undo! st)                                        ; one undo reverts table AND cell
    (is (nil? (sub/object st nid)))))

(deftest fn-apply-runs-agent-written-sci-fns
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [r (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil)]
      (is (= [200 500] (map :revenue (rows-of st (:openId r))))))))

(deftest fn-apply-rejects-non-space-target
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "tbl:t")]
    (is (:error r))                                       ; a table is not a notebook
    (is (= before (count (sub/history st))))              ; NOTHING committed
    (is (map? (srv/state-payload st)))))                  ; log not poisoned

(deftest fn-endpoints-reject-unknown-and-nil-fnid
  (let [st (store-with-table)
        before (count (sub/history st))]
    (is (:error (srv/fn-apply! st "tbl:t" nil {} nil)))
    (is (:error (srv/fn-apply! st "tbl:t" "lib:nope" {} nil)))
    (is (:error (srv/fn-preview st "tbl:t" nil {})))
    (is (= before (count (sub/history st))))))            ; nothing committed

(deftest fn-preview-surfaces-empty-result-as-error
  (let [st (store-with-table)
        r (srv/fn-preview st "tbl:t" "lib:filter" {:col "revenue" :op ">" :value "9999"})]
    (is (:error r))                                       ; honest "no rows came out"
    (is (nil? (:after r)))))                              ; not an empty table

(deftest store-at-clamps-and-freezes
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (sub/commit! st {:op :put :id "doc:b" :value {:id "doc:b" :kind :doc :title "B" :value "y"}})
    ;; nil/garbage → the live store itself
    (is (identical? st (srv/store-at st nil)))
    (is (identical? st (srv/store-at st "garbage")))
    ;; a valid n → frozen prefix
    (is (nil? (sub/object (srv/store-at st "1") "doc:b")))
    (is (= "A" (:title (sub/object (srv/store-at st "1") "doc:a"))))
    ;; clamped: negative → 0 events, huge → all events
    (is (empty? (sub/objects (srv/store-at st "-5"))))
    (is (= "B" (:title (sub/object (srv/store-at st "999") "doc:b"))))))

(deftest events-payload-has-humane-labels
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "Revenue" :value [{:a 1}]}})
    (sub/commit! st {:op :tx :events [{:op :put :id "doc:n" :value {:id "doc:n" :kind :doc :title "Note" :value "hi"}}
                                      {:op :assoc :id "tbl:t" :path [:title] :value "Revenue2"}]})
    (sub/commit! st {:op :delete :id "doc:n"})
    (let [{:keys [total events]} (srv/events-payload st)]
      (is (= 3 total))
      (is (= 3 (count events)))
      (is (= [1 2 3] (map :i events)))
      (is (str/includes? (:label (first events)) "Revenue"))   ; put labels with title
      (is (str/includes? (:label (second events)) "(+1)"))     ; tx aggregates
      (is (str/includes? (:label (nth events 2)) "doc:n")))))  ; delete labels the id

(deftest append-events-are-labelled-by-their-notebook
  ;; :append is not one of the shapes `case` used to know, and the default
  ;; label is the bare op name — the ⏱ scrubber would have read "append"
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n"
                     :value {:id "space:n" :kind :space :title "Cosmos" :value {:cells []}}})
    (sub/commit! st (nb/append-cell-event st "space:n" {:text "hello"}))
    (let [{:keys [events]} (srv/events-payload st)]
      (is (= "append" (:op (second events))))
      (is (str/includes? (:label (second events)) "Cosmos")))))

(deftest fn-apply-stamps-params
  (let [st (store-with-table)
        r  (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")]
    (is (= {:by "revenue" :n "1" :order "desc"} (:params (sub/object st (:openId r)))))))

(deftest rerun-recomputes-the-chain-in-one-tx
  (let [st (store-with-table)
        ;; b = top-1 of t (lib, params stamped) ; c = doubled b (agent fn, code stored)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil))   ; c derives from t too
        ;; now change the SOURCE: EMEA revenue jumps past APAC
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "EMEA" :revenue 900} {:region "APAC" :revenue 250}]})
        before (count (sub/history st))
        r  (srv/rerun! st "tbl:t")]
    (is (= (inc before) (count (sub/history st))))                 ; ONE :tx for the whole chain
    (is (= #{b c} (set (:refreshed r))))
    (is (= [{:region "EMEA" :revenue 900}] (rows-of st b)))                   ; top-1 follows the new data
    (is (= [1800 500] (map :revenue (rows-of st c))))                         ; doubled fresh rows
    (sub/undo! st)                                                 ; one undo restores BOTH
    ;; undo puts back the handle `fn-apply!` landed — `rerun!` assoc'd rows over
    ;; it, so this is the one assertion in the chain that reads through hydrate
    (is (= [{:region "APAC" :revenue 250}] (rows-of st b)))))

(deftest rerun-on-a-derived-table-refreshes-it-then-descendants
  (let [st (store-with-table)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "2" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st b "fn:t-1" {} nil))          ; c derives from B (a chain!)
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "X" :revenue 7} {:region "Y" :revenue 3}]})
        r  (srv/rerun! st b)]                                      ; hit ↻ on the DERIVED table
    (is (= [b c] (:refreshed r)))                                  ; parent before child
    ;; `recompute` threads ROWS between an ancestor and its descendants through
    ;; `working`; only what lands in the log is a handle. This is the assertion
    ;; that breaks first if those two ever get confused.
    (is (= [14 6] (map :revenue (rows-of st c))))))                ; child used b's NEW rows

(deftest rerun-skips-paramless-legacy-and-reports-why
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "tbl:old"
                     :value {:id "tbl:old" :kind :table :title "Old derived"
                             :value [{:a 1}] :from "tbl:t" :via "lib:top"}})   ; no :params
    (let [before (count (sub/history st))
          r (srv/rerun! st "tbl:t")]
      (is (= [] (:refreshed r)))
      (is (= "tbl:old" (:id (first (:skipped r)))))
      (is (string? (:why (first (:skipped r)))))
      (is (= before (count (sub/history st)))))))                  ; nothing to refresh → no event

(deftest rerun-refuses-a-reclaimed-source-rather-than-emptying-its-children
  ;; `recompute` is a SEVENTH reader of a hydrated table, and the one with
  ;; teeth. A reclaimed source hydrates to `:value []` — not nil — so the
  ;; "source is gone" branch cannot fire and the chain recomputes over zero
  ;; rows. Before `hydrate` learned to degrade, that case raised and the chain
  ;; stopped; the marker check is what replaces that.
  ;;
  ;; **The `via` here has to be an aggregate, and that is the whole test.**
  ;; Measured 2026-08-17: all six `lib:` builtins answer "no rows came out —
  ;; nothing to keep" for an empty input, so with a `lib:` via the marker
  ;; changes only the message a skip reports. An agent-written fn that counts
  ;; returns `[{:n 0}]` from no rows — non-empty, so nothing downstream refuses
  ;; it — and `rerun!` commits it over the good table and reports success.
  ;; Sabotaged, this test goes from `[{:n 100}]` to `[{:n 0}]` and `:refreshed`.
  (let [st  (sub/fresh-store)
        lk  @lake/lake
        ;; 100 rows so the write lands a Parquet there is something to delete —
        ;; DuckLake inlines a small insert and the source would keep reading.
        _   (sub/commit! st {:op :put :id "tbl:t"
                             :value (tbl/table-object
                                     lk "tbl:t" "T"
                                     (mapv (fn [i] {:region (str "r" i) :revenue i}) (range 100)))})
        _   (sub/commit! st {:op :put :id "fn:agg"
                             :value {:id "fn:agg" :kind :fn :title "fn: count"
                                     :value {:lang "clojure" :code "(fn [rows] [{:n (count rows)}])"}}})
        b   (:openId (srv/fn-apply! st "tbl:t" "fn:agg" {} nil))
        before (rows-of st b)
        h   (:value (sub/object st "tbl:t"))            ; raw: the handle, not rows
        files (mapv :data_file
                    (lake/q lk (str "SELECT data_file FROM ducklake_list_files('lake', '"
                                    (:table h) "')")))]
    (is (= [{:n 100}] before) "the derived table starts with a real count")
    (lake/x! lk (str "DROP TABLE " (:table h)))          ; reclaim the SOURCE for real
    (doseq [f files] (io/delete-file f true))
    (let [hist (count (sub/history st))
          r    (srv/rerun! st "tbl:t")]
      (is (= [] (:refreshed r)) "nothing could be refreshed")
      (is (= b (:id (first (:skipped r)))) "the child is skipped, by name")
      (is (re-find #"reclaimed" (str (:why (first (:skipped r))))) "and it says why")
      (is (= before (rows-of st b)) "the child's rows are UNCHANGED, not recounted to zero")
      (is (= hist (count (sub/history st))) "and nothing was committed"))))

(deftest rerun-validates
  (let [st (store-with-table)]
    (is (:error (srv/rerun! st "nope")))
    (is (:error (srv/rerun! st "space:n")))))

(deftest connect-unions-two-notebooks-non-destructively
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:a"
                     :value {:id "space:a" :kind :space :title "Alpha"
                             :value {:intent "ia" :cells [{:text "pa"} {:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:b"
                     :value {:id "space:b" :kind :space :title "Beta"
                             :value {:intent "ib" :cells [{:ref "tbl:x" :view "table/pivot"} {:text "pb"}]}}})
    (let [before (count (sub/history st))
          r   (srv/connect! st "space:a" "space:b")
          sid (:openId r)
          sp  (sub/object st sid)]
      (is (= (inc before) (count (sub/history st))))               ; ONE event
      (is (= "Alpha × Beta" (:title sp)))
      (is (= ["space:a" "space:b"] (get-in sp [:value :merged-from])))
      ;; union: prose from both kept, shared ref deduped (first occurrence wins)
      (is (= [{:text "pa"} {:ref "tbl:x"} {:text "pb"}] (get-in sp [:value :cells])))
      ;; originals untouched
      (is (= 2 (count (get-in (sub/object st "space:a") [:value :cells]))))
      ;; state payload exposes merged-from
      (let [by-id (into {} (map (juxt :id identity)) (:spaces (:state r)))]
        (is (= ["space:a" "space:b"] (get-in by-id [sid :merged-from]))))
      ;; undo removes the connected space, originals intact
      (sub/undo! st)
      (is (nil? (sub/object st sid)))
      (is (sub/object st "space:a")))))

(deftest connect-validates
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:a" :value {:id "space:a" :kind :space :title "A" :value {:cells []}}})
    (is (:error (srv/connect! st "space:a" "space:a")))            ; self
    (is (:error (srv/connect! st "space:a" "nope")))               ; missing
    (is (:error (srv/connect! st "nope" "space:a")))
    (is (= 1 (count (sub/history st))))))

(deftest flow-plan-validation-drops-unknown-verbs-and-caps
  (let [raw [{:verb "research" :args {:prompt "p"} :note "n"}
             {:verb "hack-the-gibson" :args {}}
             {:verb "gate" :args {:question "ok to proceed?"}}
             {:verb "compute" :args {:id "$0" :prompt "top 5"}}
             {:verb "ask"} {:verb "draft"} {:verb "research" :args {:prompt "x"}}
             {:verb "research" :args {:prompt "y"}}]
        v (srv/validate-plan raw)]
    (is (= ["research" "gate" "compute" "ask" "draft" "research"] (map :verb v)))  ; unknown dropped, capped at 6
    (is (every? #(= "pending" (:status %)) v))
    (is (= {:prompt "p"} (:args (first v))))
    (is (= "" (:note (second v)))))                       ; missing note → ""
  ;; non-map :args from a weird LLM must not survive as-is
  (is (= {} (:args (first (srv/validate-plan [{:verb "research" :args "foo"}]))))))

(deftest flow-step-refs-resolve-to-outputs
  (let [flow {:steps [{:out "tbl:derived-3"} {:out "note:n-1"}]}]
    (is (= "tbl:derived-3" (srv/resolve-ref flow "$0")))
    (is (= "note:n-1" (srv/resolve-ref flow "$1")))
    (is (= "note:n-1" (srv/resolve-ref flow "$9")))       ; past the end → the latest output
    (is (= "tbl:t" (srv/resolve-ref flow "tbl:t")))       ; plain ids pass through
    (is (= 5 (srv/resolve-ref flow 5)))))                 ; non-strings untouched

(deftest flow-compute-refs-prefer-tabular-outputs
  ;; live-fire finding #2: research sometimes lands prose only; a $-ref from
  ;; compute must find the nearest TABLE output, never hand compute a note.
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "prose"}})
    (let [flow {:steps [{:out "tbl:t"} {:out "doc:d"} {}]}]
      (is (= "tbl:t" (srv/resolve-table-ref st flow "$1")))      ; $1 is a doc → nearest table wins
      (is (= "tbl:t" (srv/resolve-table-ref st flow "$0")))
      ;; no table among outputs → pass the walk result through (honest failure downstream)
      (is (= "doc:d" (srv/resolve-table-ref st {:steps [{:out "doc:d"}]} "$0")))
      ;; literal ids are never hijacked
      (is (= "tbl:x" (srv/resolve-table-ref st flow "tbl:x"))))))

(deftest flow-compute-accepts-a-bare-step-index-not-only-a-dollar-ref
  ;; live-fire finding, 2026-08-15: this is what stopped agent flows working.
  ;; Both flows this project has ever run died here — the August one recorded
  ;; `:id "0"`, today's reproduction `:id 0` — while putting the real reference
  ;; in the PROSE ("From $0, create a new table…"). Neither starts with `$`, so
  ;; both were passed through as literal object ids, compute looked up an object
  ;; named 0, found none, and answered "compute works on tables" in under a
  ;; millisecond with a perfectly good table sitting one step earlier.
  ;;
  ;; Asking the planner to emit `$0` would be asking a model to hold a format it
  ;; has now failed to hold twice.
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "prose"}})
    (let [flow {:steps [{:out "tbl:t"} {:out "doc:d"} {}]}]
      (is (= "tbl:t" (srv/resolve-table-ref st flow 0))    "a bare integer index")
      (is (= "tbl:t" (srv/resolve-table-ref st flow "0"))  "a bare numeric string")
      (is (= "tbl:t" (srv/resolve-table-ref st flow 1))    "index onto a doc still finds the table")
      ;; and the narrowness of that rewrite is the point: an id is not an index.
      ;; Substituting a different table for one somebody named by hand is worse
      ;; than the honest failure, so a named-but-missing id still passes through.
      (is (= "tbl:x" (srv/resolve-table-ref st flow "tbl:x"))
          "an id-shaped literal is never rewritten, even when it names nothing")
      (is (= "space:s" (srv/resolve-table-ref st flow "space:s"))
          "nor is any other kind of id"))))

(deftest flow-compute-step-receives-a-table-not-a-note
  (let [seen (atom nil)]
    (with-redefs [srv/research!    (fn [st space prompt]
                                     (if (str/includes? prompt "first") {:openId "tbl:t"} {:openId "doc:d"}))
                  srv/compute-clj! (fn [st id prompt space] (reset! seen id) {:openId "tbl:d"})]
      (let [st (store-with-table)]
        (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "p"}})
        (let [fid (srv/flow-create! st "space:n" "g"
                                    [{:verb "research" :args {:prompt "first"} :note "" :status "pending"}
                                     {:verb "research" :args {:prompt "second"} :note "" :status "pending"}
                                     {:verb "compute" :args {:id "$1" :prompt "top"} :note "" :status "pending"}])]
          (srv/run-flow! st fid)
          (is (= "tbl:t" @seen)))))))                            ; skipped the note, found the table

(deftest research-retries-empty-responses-then-fails-honestly
  ;; live-fire finding #4: v4-flash occasionally returns an empty final
  ;; message from the tool loop. One retry, then an honest error.
  (let [n (atom 0)]
    (with-redefs [agent/chat-tools (fn [& _] (if (= 1 (swap! n inc)) "" "real findings"))
                  loci.server/distill! (fn [& _] nil)]
      (let [st (store-with-table)
            r  (srv/research! st "space:n" "q")]
        (is (= 2 @n))
        (is (nil? (:error r)))
        (is (= "real findings" (:value (sub/object st (:openId r))))))))
  (with-redefs [agent/chat-tools (fn [& _] "  ")
                loci.server/distill! (fn [& _] nil)]
    (let [st (store-with-table)
          before (count (sub/history st))
          r (srv/research! st "space:n" "q")]
      (is (:error r))
      (is (= before (count (sub/history st)))))))          ; nothing committed

(deftest research-salvages-prose-tables
  ;; The salvaged cells are NUMBERS: `md-cell-val` pulls a figure out of the
  ;; model's sentence, which nothing else will do, and `put-rows!` hands it to
  ;; `read_json_auto` — so DuckDB still decides the type. Handing it a string
  ;; instead makes the column VARCHAR and `fnlib/numeric-cols` answer [], which
  ;; costs Group, Top N, Change and Share at apply time. Measured 2026-08-19 —
  ;; `->val` was loci's second CSV inference and `read_csv` is the only one
  ;; left. **This path does not reach `read_csv`**: `research!` hands the rows
  ;; to `save-table!`, which types a lake column from the Clojure values it is
  ;; given, so `share_pct` is VARCHAR here where it was BIGINT.
  ;;
  ;; Measured 2026-08-19, and the reason this is written down rather than just
  ;; edited: `fnlib/catalog` over these rows offers ONE builtin where it offered
  ;; five — Group, Top N, Pivot, Change and Share all answer "needs a numeric
  ;; column". Routing the salvage through the file connector, as `import-csv!`
  ;; now goes, is what would make the intent true; it is a change to `research!`.
  (with-redefs [agent/chat-tools (fn [& _] "intro\n\nname | share_pct\n--- | ---\nA | 30%\nB | 20%\n\ndone")
                loci.server/distill! (fn [& _] nil)]
    (let [st (store-with-table)
          r  (srv/research! st "space:n" "extract a table of shares")]
      (is (clojure.string/starts-with? (str (:openId r)) "tbl:extract"))
      (is (= [{:name "A" :share_pct 30} {:name "B" :share_pct 20}]
             (rows-of st (:openId r)))))))

(deftest compute-retries-once-with-error-feedback
  ;; live-fire finding: one bad LLM sample ("Could not resolve symbol")
  ;; coin-flipped the whole flow. One retry, error fed back, then honest.
  (let [calls (atom [])]
    (with-redefs [agent/make-clj-transform
                  (fn [cols sample prompt & [prev-code err]]
                    (swap! calls conj err)
                    (if err "(mapv #(assoc % :x 1) rows)" "(bad"))]
      (let [st (store-with-table)
            r  (srv/compute-clj! st "tbl:t" "add x" nil)]
        (is (nil? (:error r)))
        (is (= 2 (count @calls)))
        (is (nil? (first @calls)))                        ; first attempt: no error context
        (is (string? (second @calls)))                    ; retry got the eval error
        (is (= 1 (:x (first (rows-of st (:openId r))))))))))

(deftest compute-fails-honestly-after-two-bad-attempts
  (with-redefs [agent/make-clj-transform (fn [& _] "(bad")]
    (let [st (store-with-table)
          before (count (sub/history st))
          r (srv/compute-clj! st "tbl:t" "x" nil)]
      (is (str/starts-with? (:error r) "compute failed:"))
      (is (= before (count (sub/history st)))))))         ; nothing committed

;; ---- the two ids compute mints, and why they are max+1 and not count+1 ----
;;
;; 2026-07-23: `space:dd-` was minted `(inc (count (nb/notebooks st)))` over a
;; store that had silently booted 49 of 80 events, so it re-minted 16, 17 and 18
;; onto three LIVE astronomy notebooks. `:put` is `assoc-in` with no occupancy
;; check, so it overwrote them with no error anywhere and three notebooks were
;; gone. `compute-clj!` minted BOTH its ids the same way, with neither the
;; `(inc (rand-int 9999))` suffix that saved `tbl:extract-14-677` from doing the
;; same to `tbl:extract-14-588` nor an existence check — and `server.clj` was
;; already minting `tbl:derived-` through `next-id` sixty lines further down, so
;; one prefix had two rules.
;;
;; The fixture below is the collision shape and nothing else: FEWER live objects
;; of each kind than the highest suffix already taken. Count and max must
;; genuinely disagree or the test passes under the bug.

(defn- store-with-a-gap-between-count-and-max
  "One live object of each minted kind, but `-5` is taken for every prefix: a
   count-based mint says `-2` (`-3` for tables, which have `tbl:x` too), a
   max-based one says `-6`. Every kind `compute-clj!`, `describe!` and
   `make-view!` mint over is in here, because all three took their number from
   a store-wide count of their own kind."
  []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value trows}})
    (sub/commit! st {:op :put :id "tbl:derived-5"
                     :value {:id "tbl:derived-5" :kind :table :title "the fifth derived table"
                             :value trows}})
    (sub/commit! st {:op :put :id "fn:x-5"
                     :value {:id "fn:x-5" :kind :fn :title "fn: the fifth function"
                             :value {:source "tbl:x" :prompt "five" :lang "clojure"
                                     :code "(fn [rows] rows)"}}})
    (sub/commit! st {:op :put :id "view:x-5"
                     :value {:id "view:x-5" :kind :viewspec :title "view: the fifth view"
                             :value {:target "tbl:x" :label "the fifth view"
                                     :spec {:kind "table"}}}})
    (sub/commit! st {:op :put :id "app:x-5"
                     :value {:id "app:x-5" :kind :applet :title "app: the fifth applet"
                             :value {:target "tbl:x" :code "el.textContent='5'" :label "▶ five"}}})
    st))

(deftest compute-mints-past-the-highest-id-not-past-the-count
  (with-redefs [agent/make-clj-transform (fn [& _] "(mapv #(assoc % :x 1) rows)")]
    (let [st     (store-with-a-gap-between-count-and-max)
          before (count (sub/history st))
          r      (srv/compute-clj! st "tbl:x" "add x" nil)
          fid    (:via (sub/object st (:openId r)))]
      (is (nil? (:error r)))
      (is (= "tbl:derived-6" (:openId r)))                 ; two tables live → count said -3
      (is (= "fn:x-6" fid))                                ; one :fn live → count said -2
      ;; the assertion the July notebooks would have wanted: the occupants live
      (is (= "the fifth derived table" (:title (sub/object st "tbl:derived-5"))))
      (is (= "fn: the fifth function" (:title (sub/object st "fn:x-5"))))
      ;; the :fn and its table are ONE :tx, so one undo reverses both
      (is (= (inc before) (count (sub/history st))))
      (sub/undo! st)
      (is (nil? (sub/object st "tbl:derived-6")))
      (is (nil? (sub/object st "fn:x-6"))))))

(deftest compute-numbers-functions-per-source-not-across-the-store
  ;; `fn:` carries the source's local name — `fn:<src>-N` — so the sequence is
  ;; per-source, and `next-id`'s prefix has to carry that name too. A store-wide
  ;; count numbered every source's functions off one running total, so the FIRST
  ;; function over a brand-new table came out `fn:y-2`. Two sources, because one
  ;; source cannot tell "counts the store" from "counts this source".
  (with-redefs [agent/make-clj-transform (fn [& _] "(mapv #(assoc % :x 1) rows)")]
    (let [st (store-with-a-gap-between-count-and-max)]
      (sub/commit! st {:op :put :id "tbl:y"
                       :value {:id "tbl:y" :kind :table :title "Y" :value trows}})
      (let [ry (srv/compute-clj! st "tbl:y" "add x" nil)]
        (is (= "fn:y-1" (:via (sub/object st (:openId ry)))))  ; store-wide count said -2
        (is (= "tbl:derived-6" (:openId ry))))
      (let [rx (srv/compute-clj! st "tbl:x" "add x again" nil)]
        (is (= "fn:x-6" (:via (sub/object st (:openId rx)))))  ; fn:x- keeps its own run
        (is (= "tbl:derived-7" (:openId rx)))))))              ; tbl:derived- keeps one run

;; ---- and the three minters the forensics table missed ----
;;
;; `new-space!`, `describe!` and `make-view!` each took `(inc (count <kind>))`
;; over the whole store, with no random suffix and no existence check, landing
;; via `:put`. `new-space!` is the one that matters: it counts `:space` and
;; mints a NOTEBOOK id, which is `(inc (count (nb/notebooks st)))` under a
;; different prefix — the identical form that destroyed `space:dd-16/17/18`,
;; not merely the same shape. `fresh-dd-id` does not reach it: it matches
;; `^space:dd-(\d+)$`, and this prefix is `space:new-`. A view or an applet can
;; be re-made from its target; a notebook carries cells, prose and lineage that
;; nothing else holds.

(defn- store-with-an-occupied-space-new
  "TWO spaces live and `space:new-3` among them, so `(inc (count :space))` is
   exactly 3 — the count form lands on the occupied id rather than merely near
   it. That is the July defect and not an analogy to it: the notebook the mint
   overwrites is here, with a title, cells and lineage to lose."
  []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:cosmos"
                     :value {:id "space:cosmos" :kind :space :title "Cosmos"
                             :value {:intent "the solar system, end to end" :cells []}}})
    (sub/commit! st {:op :put :id "space:new-3"
                     :value {:id "space:new-3" :kind :space :title "Inner vs. Outer Planets"
                             :value {:intent "Compare the terrestrial and giant planets."
                                     :cells [{:ref "tbl:planets"}]
                                     :spawned-by {:space "space:cosmos"}}}})
    st))

(deftest new-space-mints-past-the-highest-id-and-leaves-the-occupant-alone
  (with-redefs [agent/plan-space (fn [& _] {:title "World economies"
                                            :intent "output and prices" :members []})]
    (let [st (store-with-an-occupied-space-new)
          r  (srv/new-space! st "world economies")]
      (is (= "space:new-4" (:spaceId r)))                  ; two spaces live → count said -3
      (is (= "World economies" (:title (sub/object st "space:new-4"))))
      ;; the July defect, reproduced rather than argued: under the count form
      ;; every one of these four is the semiconductor notebook's data
      (let [occupant (sub/object st "space:new-3")]
        (is (= "Inner vs. Outer Planets" (:title occupant)))
        (is (= "Compare the terrestrial and giant planets." (get-in occupant [:value :intent])))
        (is (= [{:ref "tbl:planets"}] (get-in occupant [:value :cells])))
        (is (= "space:cosmos" (get-in occupant [:value :spawned-by :space])))))))

(deftest describe-and-make-view-mint-past-the-highest-id-not-past-the-count
  (with-redefs [agent/describe-view (fn [& _] {:label "by region" :kind "table"})
                agent/make-applet   (fn [& _] "el.textContent = data.length")]
    (let [st     (store-with-a-gap-between-count-and-max)
          before (count (sub/history st))]
      (srv/describe! st "tbl:x" "chart it")
      (srv/make-view! st "tbl:x" "draw it")
      (is (= (+ 2 before) (count (sub/history st))))       ; one commit each, and they happened
      (is (= "view: by region" (:title (sub/object st "view:x-6"))))  ; count said view:x-2
      (is (= "app: draw it" (:title (sub/object st "app:x-6"))))      ; count said app:x-2
      (is (nil? (sub/object st "view:x-2")))
      (is (nil? (sub/object st "app:x-2")))
      ;; the occupants of -5, which a count form walks toward with every mint
      (is (= "view: the fifth view" (:title (sub/object st "view:x-5"))))
      (is (= "app: the fifth applet" (:title (sub/object st "app:x-5")))))))

(deftest view-and-app-ids-number-per-source-not-across-the-store
  ;; same argument as `fn:` above: `view:<src>-N` and `app:<src>-N` carry the
  ;; source's local name, so each source owns its own run. A store-wide count
  ;; numbered them off one running total, so the FIRST view over a brand-new
  ;; table came out `view:y-2`. Two sources, because one cannot tell "counts
  ;; the store" from "counts this source".
  (with-redefs [agent/describe-view (fn [& _] {:label "by region" :kind "table"})
                agent/make-applet   (fn [& _] "el.textContent = data.length")]
    (let [st (store-with-a-gap-between-count-and-max)]
      (sub/commit! st {:op :put :id "tbl:y"
                       :value {:id "tbl:y" :kind :table :title "Y" :value trows}})
      (srv/describe! st "tbl:y" "chart it")
      (srv/make-view! st "tbl:y" "draw it")
      (is (= "view: by region" (:title (sub/object st "view:y-1"))))  ; count said view:y-2
      (is (= "app: draw it" (:title (sub/object st "app:y-1"))))      ; count said app:y-2
      (srv/describe! st "tbl:x" "chart it")
      (srv/make-view! st "tbl:x" "draw it")
      (is (= "view: by region" (:title (sub/object st "view:x-6"))))  ; view:x- keeps its own run
      (is (= "app: draw it" (:title (sub/object st "app:x-6")))))))

(deftest flow-step-refs-walk-back-past-outputless-steps
  ;; the live-fire bug: the planner counted 1-based, so "$1" hit the GATE
  ;; (no :out) and the literal "$1" reached compute. $N now means "the
  ;; output at or before step min(N, last)" — deterministic, absorbs both
  ;; numbering conventions for research → gate → compute shapes.
  (let [flow {:steps [{:verb "research" :out "tbl:extract-1"}
                      {:verb "gate"}
                      {:verb "compute"}]}]
    (is (= "tbl:extract-1" (srv/resolve-ref flow "$1")))  ; gate has no out → walk back
    (is (= "tbl:extract-1" (srv/resolve-ref flow "$0")))
    (is (= "$0" (srv/resolve-ref {:steps [{:verb "gate"}]} "$0")))  ; nothing to find → literal
    (is (= "$x" (srv/resolve-ref flow "$x")))))           ; malformed → literal

(defn- flow-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:f" :value {:id "space:f" :kind :space :title "F"
                                                    :value {:intent "i" :cells []}}})
    st))

(defn- flow-of [st fid] (:value (sub/object st fid)))

(deftest flow-executes-steps-in-order-and-lands-as-a-cell
  (with-redefs [srv/research!   (fn [st space prompt] {:openId (str "find:" prompt)})
                srv/compute-clj! (fn [st id prompt space] {:openId (str "tbl:d-" id)})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "compute" :args {:id "$0" :prompt "top"} :note "" :status "pending"}])
          _   (srv/run-flow! st fid)
          fl  (flow-of st fid)]
      (is (= "done" (:status fl)))
      (is (= ["done" "done"] (map :status (:steps fl))))
      (is (= "find:a" (:out (first (:steps fl)))))
      (is (= "tbl:d-find:a" (:out (second (:steps fl)))))          ; $0 resolved to step 0's out
      ;; the flow is a CELL in the notebook
      (is (some #(= fid (:ref %)) (get-in (sub/object st "space:f") [:value :cells])))
      ;; every transition was an event: as-of mid-history shows step 0 done, step 1 pending
      (let [n   (count (sub/history st))
            mid (some (fn [k] (let [v (get-in (sub/as-of st k) [:objects fid :value])]
                                (when (and (= "done" (get-in v [:steps 0 :status]))
                                           (= "pending" (get-in v [:steps 1 :status]))) k)))
                      (range n))]
        (is (some? mid))))))                                       ; the scrubber can watch it work

(deftest flow-gate-parks-then-approve-resumes-reject-stops
  ;; start-job! stubbed synchronous so approve's resume can't race the test
  (with-redefs [srv/research! (fn [st space prompt] {:openId "find:x"})
                srv/delegate! (fn [st space] {:openId "draft:x"})
                srv/start-job! (fn [f] (f) "job:test")]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "gate" :args {:question "go on?"} :note "" :status "pending"}
                                 {:verb "draft" :args {} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [fl (flow-of st fid)]
        (is (= "needs-you" (:status fl)))
        (is (= ["done" "needs-you" "pending"] (map :status (:steps fl)))))
      ;; reject on a fresh identical flow stops it
      (let [fid2 (srv/flow-create! st "space:f" "g2"
                                   [{:verb "gate" :args {:question "?"} :note "" :status "pending"}
                                    {:verb "draft" :args {} :note "" :status "pending"}])]
        (srv/run-flow! st fid2)
        (srv/flow-gate! st fid2 false)
        (let [fl2 (flow-of st fid2)]
          (is (= "rejected" (:status fl2)))
          (is (= "rejected" (get-in fl2 [:steps 0 :status])))
          (is (= "pending" (get-in fl2 [:steps 1 :status])))))     ; never ran
      ;; approve resumes the first flow to completion (start-job! stub is sync)
      (srv/flow-gate! st fid true)
      (let [fl (flow-of st fid)]
        (is (= "done" (:status fl)))
        (is (= "draft:x" (:out (nth (:steps fl) 2))))))))

(deftest flow-step-failure-fails-the-flow-honestly
  (with-redefs [srv/research! (fn [st space prompt] {:error "no key"})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "research" :args {:prompt "a"} :note "" :status "pending"}
                                 {:verb "draft" :args {} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [fl (flow-of st fid)]
        (is (= "failed" (:status fl)))
        (is (= "failed" (get-in fl [:steps 0 :status])))
        (is (= "no key" (get-in fl [:steps 0 :why])))
        (is (= "pending" (get-in fl [:steps 1 :status])))))))      ; honest stop, no cascade

(deftest flow-ask-step-lands-a-note
  (with-redefs [srv/ask! (fn [st prompt space] {:answer "42"})]
    (let [st  (flow-store)
          fid (srv/flow-create! st "space:f" "g"
                                [{:verb "ask" :args {:prompt "meaning?"} :note "" :status "pending"}])]
      (srv/run-flow! st fid)
      (let [out (:out (first (:steps (flow-of st fid))))]
        (is (str/starts-with? out "note:"))
        (is (= "42" (:value (sub/object st out))))))))

(deftest flow-create-validates-and-flow-mold-renders
  (let [st (flow-store)]
    (is (:error (srv/flow-start! st "nope" "g")))                  ; not a notebook → sync error
    (let [fid (srv/flow-create! st "space:f" "g" [{:verb "draft" :args {} :note "" :status "pending"}])
          m   (srv/mold-payload st no-memory fid nil)]
      (is (= "flow" (:kind m)))
      (is (= "g" (get-in m [:rendered :goal]))))))

(deftest state-payload-time-travels-via-frozen-store
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (let [past (srv/state-payload (srv/store-at st "1"))]
      (is (= 1 (:events past)))
      (is (= ["space:n"] (map :id (:spaces past))))
      (is (empty? (:objects past))))))

;; ---- real-world tables have gaps ----
;; Found by importing World Bank data: not every country reports unemployment,
;; so a numeric column arrives as a mix of numbers and "". Four of six viewers
;; threw ClassCastException on it — the seeded corpus has no missing values, so
;; nothing caught it. A substrate that cannot mold real data is not a substrate.

(defn- gappy-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:gappy"
                     :value {:id "tbl:gappy" :kind :table :title "With gaps"
                             :value [{:country "A" :region "North" :gdp 10.0 :jobless 5.0}
                                     {:country "B" :region "North" :gdp 20.0 :jobless ""}
                                     {:country "C" :region "South" :gdp 30.0 :jobless 7.0}
                                     {:country "D" :region "South" :gdp ""   :jobless ""}]}})
    st))

(deftest every-viewer-survives-missing-values
  (let [st (gappy-store)]
    (doseq [v ["table/rows" "table/summary" "table/cards" "table/pivot"
               "table/bar" "table/hist"]]
      (is (some? (:rendered (srv/mold-payload st no-memory "tbl:gappy" v)))
          (str v " threw or rendered nothing on a table with gaps")))))

(deftest aggregates-ignore-gaps-rather-than-counting-them
  (let [st (gappy-store)
        summ (:rendered (srv/mold-payload st no-memory "tbl:gappy" "table/summary"))
        gdp  (first (filter #(= "gdp" (:column %)) summ))]
    (is (= 3 (:n gdp)) "n must count the values that exist, not the rows")
    (is (= 60.0 (:sum gdp)))
    (is (= 20.0 (:avg gdp)) "the average must divide by what was measured")
    (is (= 10.0 (:min gdp)))
    (is (= 30.0 (:max gdp)))))

(deftest a-column-is-numeric-even-when-its-first-value-is-missing
  ;; numeric-cols used to sample only (first rows)
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:lead"
                     :value {:id "tbl:lead" :kind :table :title "Leading gap"
                             :value [{:name "A" :score ""} {:name "B" :score 5.0}
                                     {:name "C" :score 7.0}]}})
    (let [summ (:rendered (srv/mold-payload st no-memory "tbl:lead" "table/summary"))]
      (is (= ["score"] (mapv :column summ)))
      (is (= 12.0 (:sum (first summ)))))))

;; ---- the default chart must pick a column worth charting ----
;; Found with World Bank data: Bar summed inflation percentages across
;; countries and drew it confidently. It charted (first (numeric-cols rows)),
;; and above 8 keys Clojure hands back hash order, so "first" was arbitrary.
;; Column order cannot carry the answer — nippy thaws a 9-key map as a
;; hash-map, so the order is gone before a viewer ever sees it. The choice has
;; to come from what the column MEANS.

(defn- charted [st id]
  (:rendered (srv/mold-payload st no-memory id "table/bar")))

(deftest bar-prefers-a-quantity-over-a-rate-whatever-the-key-order
  (let [st (sub/fresh-store)
        rows (for [i (range 20)]
               {:country (str "C" i) :region (if (even? i) "North" "South")
                :iso3 (str "X" i) :gdp_usd_bn (* 10.0 (inc i)) :gdp_year 2024
                :gdp_per_capita 5000.0 :growth_pct 2.0
                :inflation_pct 3.0 :unemployment_pct 4.0})]
    (sub/commit! st {:op :put :id "tbl:wb"
                     :value {:id "tbl:wb" :kind :table :title "WB" :value (vec rows)}})
    (let [c (charted st "tbl:wb")]
      (is (= "gdp_usd_bn" (:y c))
          (str "charted " (:y c) " — a percentage or a year is not a quantity"))
      (is (= "sum" (:agg c))))))

(deftest bar-averages-when-every-numeric-column-is-a-rate
  ;; summing percentages is meaningless; averaging them is not
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:rates"
                     :value {:id "tbl:rates" :kind :table :title "Rates"
                             :value [{:country "A" :region "North" :inflation_pct 2.0}
                                     {:country "B" :region "North" :inflation_pct 4.0}
                                     {:country "C" :region "South" :inflation_pct 9.0}]}})
    (let [c (charted st "tbl:rates")]
      (is (= "inflation_pct" (:y c)))
      (is (= "avg" (:agg c)) "a rate must not be summed")
      (is (= 3.0 (get (first (filter #(= "North" (get % "region")) (:rows c)))
                      "inflation_pct"))
          "North should be the mean of 2 and 4"))))

(deftest a-year-column-is-never-the-measure
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:yr"
                     :value {:id "tbl:yr" :kind :table :title "Years"
                             :value [{:place "A" :region "N" :year 2024 :tonnes 5.0}
                                     {:place "B" :region "S" :year 2023 :tonnes 7.0}]}})
    (is (= "tonnes" (:y (charted st "tbl:yr"))))))

(deftest bar-picks-the-headline-quantity-not-a-small-per-person-measure
  ;; World Bank population table: summing life expectancy across countries is
  ;; meaningless, and "life_expectancy" is not a rate by name — so the choice
  ;; cannot come from names alone. Population is three orders of magnitude
  ;; larger, and that is the signal.
  (let [st (sub/fresh-store)
        rows (for [i (range 12)]
               {:country (str "C" i) :region (if (even? i) "North" "South")
                :iso3 (str "X" i)
                :population (* 1000000.0 (inc i)) :population_year 2024
                :urban_pct 55.0 :life_expectancy (+ 70.0 i)
                :density_per_km2 100.0 :fertility_rate 1.8
                :age_65_plus_pct 12.0 :note "x"})]
    (sub/commit! st {:op :put :id "tbl:pop"
                     :value {:id "tbl:pop" :kind :table :title "Pop" :value (vec rows)}})
    (let [c (:rendered (srv/mold-payload st no-memory "tbl:pop" "table/bar"))]
      (is (= "population" (:y c)) (str "charted " (:y c)))
      (is (= "sum" (:agg c))))))

;; ---- recency: what did you touch, and when ----
;; LEAP capped each group at "the first 8 encountered", which after the move to
;; Datalevin is hash order — so results were dropped by coin-flip. Ranking by
;; last-touched turns that cap into "the 8 most recent".

(deftest last-touched-picks-the-newest-event-that-names-an-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value 2})
    (let [t (srv/last-touched st)]
      (is (> (t "a") (t "b")) "a was edited after b was created")
      (is (= (t "a") (:ts (last (sub/history st))))))))

(deftest last-touched-sees-inside-a-tx
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "old" :value {:id "old" :kind :doc :value 1}})
    (Thread/sleep 2)
    (sub/commit! st {:op :tx :events [{:op :put :id "x" :value {:id "x" :kind :doc :value 1}}
                                      {:op :put :id "y" :value {:id "y" :kind :doc :value 1}}]})
    (let [t (srv/last-touched st)]
      (is (some? (t "x")) "a :tx has no :id of its own; its sub-events do")
      (is (= (t "x") (t "y")))
      (is (> (t "x") (t "old"))))))

(deftest a-notebook-is-touched-when-anything-in-it-is
  ;; otherwise a hub you work in constantly looks dormant, because you never
  ;; edit the space object itself
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S"
                             :value {:intent "i" :cells [{:ref "tbl:t"}]}}})
    (Thread/sleep 2)
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value []}})
    (let [t   (srv/last-touched st)
          nb  (srv/notebook-touched st t "space:s")]
      (is (> (t "tbl:t") (t "space:s")) "the table is newer than the notebook object")
      (is (= nb (t "tbl:t")) "the notebook inherits its newest member's recency"))))

(deftest state-payload-carries-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:s"
                     :value {:id "space:s" :kind :space :title "S" :value {:intent "i" :cells []}}})
    (is (number? (:touched (first (:spaces (srv/state-payload st))))))))

(defn- leap-of [st q] (srv/leap-payload st (mem/file-memory (tmpfile)) q))

(deftest leap-orders-each-group-most-recent-first
  (let [st (sub/fresh-store)]
    (doseq [n (range 3)]
      (sub/commit! st {:op :put :id (str "doc:d" n)
                       :value {:id (str "doc:d" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id))]
      (is (= ["doc:d2" "doc:d1" "doc:d0"] ids) "newest first"))))

(deftest leaps-cap-keeps-the-newest-eight-not-the-first-eight
  ;; the assertion that would have failed before this change: 12 matches, and
  ;; the 8 kept must be the 8 most recently touched
  (let [st (sub/fresh-store)]
    (doseq [n (range 12)]
      (sub/commit! st {:op :put :id (str "doc:w" n)
                       :value {:id (str "doc:w" n) :kind :doc :title (str "widget " n) :value "x"}})
      (Thread/sleep 2))
    (let [ids (->> (leap-of st "widget") (filter #(= "doc" (:group %))) (mapv :id) set)]
      (is (= 8 (count ids)))
      (is (= (set (map #(str "doc:w" %) (range 4 12))) ids)
          "the eight most recent, not doc:w0..w7"))))

(deftest leap-entries-carry-their-recency
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:one"
                     :value {:id "doc:one" :kind :doc :title "widget" :value "x"}})
    (is (number? (:touched (first (filter #(= "doc" (:group %)) (leap-of st "widget"))))))))

(deftest ask-and-verbs-are-not-reordered-by-recency
  ;; "the top result is already the action" — Ask stays first, and view verbs
  ;; (which have no timestamp) keep their existing order
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "rows here"
                                                  :value [{:a 1}]}})
    (let [groups (mapv :group (leap-of st "rows"))]
      (is (= "viewer" (last groups)) "verbs stay at the end, unsorted"))))

;; ---- tags: the one thing the substrate cannot derive ----
;; Structure is computed (spawned-by, shares, lineage); subject is not. Tags
;; are agent-proposed and human-approved, and carry :by so an inference is
;; never mistaken for an assertion.

(defn- tagged-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:t"
                     :value {:id "space:t" :kind :space :title "T" :value {:intent "i" :cells []}}})
    st))

(deftest set-tags-commits-one-reversible-event
  ;; a first-time tag also earns its colour, so it costs two events: the
  ;; palette first, the tags second. The ORDER is the point — undo! undoes
  ;; the last event, so undo must remove the tags, not the colour.
  (let [st (tagged-store)
        before (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
    (is (= (+ 2 before) (count (sub/history st))) "one colour event, one tag event")
    (is (= ["semiconductors"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags]))))
    (sub/undo! st)
    (is (nil? (get-in (sub/object st "space:t") [:value :tags])) "undo restores the previous tags")
    (is (= "semiconductors" (first (keys (srv/tag-colors st))))
        "and leaves the colour standing — a colour is a preference about a subject")))

(deftest set-tags-stamps-provenance-and-time
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "world data" :by "agent"}])
        t  (first (get-in (sub/object st "space:t") [:value :tags]))]
    (is (= "agent" (:by t)))
    (is (number? (:ts t)))))

(deftest set-tags-normalizes-and-refuses-nonsense
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "  Semiconductors  " :by "you"}
                                 {:tag "semiconductors" :by "you"}
                                 {:tag "" :by "you"}])
    (is (= ["semiconductors"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags])))
        "trimmed, lower-cased, de-duplicated; blanks dropped")
    (is (:error (srv/set-tags! st "tbl:nope" [{:tag "x" :by "you"}])))
    (is (re-find #"not a notebook" (:error (srv/set-tags! st "tbl:nope" [{:tag "x" :by "you"}]))))))

(deftest setting-the-same-tags-commits-nothing
  ;; the notebook-op! discipline: never commit a phantom event
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (is (= n (count (sub/history st))))))

(deftest state-payload-carries-tags
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "agent"}])
    (is (= ["a"] (mapv :tag (:tags (first (:spaces (srv/state-payload st)))))))))

(deftest your-assertion-outranks-the-agents-inference
  ;; the same name arriving twice: de-duplication must not silently demote what
  ;; you asserted into something the agent merely inferred
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "agent"}
                                 {:tag "Semiconductors" :by "you"}])
    (let [tags (get-in (sub/object st "space:t") [:value :tags])]
      (is (= ["semiconductors"] (mapv :tag tags)))
      (is (= ["you"] (mapv :by tags)) "you asserted it; the agent only guessed it"))))

(deftest re-asserting-an-agents-tag-as-your-own-records-it
  ;; :by is the whole point of the model, so a change of provenance IS a change
  (let [st  (tagged-store)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "agent"} {:tag "b" :by "you"}])
        was (get-in (sub/object st "space:t") [:value :tags])
        n   (count (sub/history st))
        _   (Thread/sleep 2)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "you"} {:tag "b" :by "you"}])
        now (get-in (sub/object st "space:t") [:value :tags])]
    (is (= (inc n) (count (sub/history st))) "approving an agent's tag is an event")
    (is (= ["you" "you"] (mapv :by now)))
    (is (= (:ts (second was)) (:ts (second now)))
        "a tag you did not touch keeps when it was asserted")))

(deftest suggesting-tags-writes-nothing
  ;; a proposal you ignore must leave no trace — not a reversible event, none
  (with-redefs [agent/propose-tags (fn [_ _ _] ["semiconductors" "chokepoints"])]
    (let [st (tagged-store)
          before (count (sub/history st))
          r (srv/suggest-tags! st "space:t")]
      (is (= ["semiconductors" "chokepoints"] (:tags r)))
      (is (= before (count (sub/history st))) "proposing must not commit"))))

(deftest suggesting-tags-refuses-a-non-notebook
  (with-redefs [agent/propose-tags (fn [_ _ _] ["x"])]
    (let [st (tagged-store)]
      (is (re-find #"not a notebook" (:error (srv/suggest-tags! st "tbl:nope")))))))

(deftest a-failed-suggestion-leaves-the-notebook-untouched
  (with-redefs [agent/propose-tags (fn [_ _ _] (throw (Exception. "no key")))]
    (let [st (tagged-store)
          before (count (sub/history st))
          r (srv/suggest-tags! st "space:t")]
      (is (:error r) "an agent failure is reported, not thrown")
      (is (= before (count (sub/history st)))))))

(deftest a-spawned-notebook-inherits-its-parents-tags
  ;; without this a grandchild drops out of its own family under a filter
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
    (let [kid (srv/inherit-tags st "space:t")]
      (is (= ["semiconductors"] (mapv :tag kid)))
      (is (= ["agent"] (mapv :by kid)) "an inherited tag is an inference, not your assertion"))))

;; ---- tag colours ----
;; Colour belongs to the NAME, globally: "world data" green here and plum there
;; would defeat the point of colouring at all.

;; The names below are deliberately real subjects, not "a".."h". Single letters
;; are consecutive char codes, so a bare hash mod 8 is a perfect permutation of
;; the eight inks and every test here passes with no least-used rule at all —
;; certifying the one thing it was written to catch. These eight hash onto five
;; slots, which is what real tags do.
(def ^:private eight-subjects
  ["semiconductors" "world data" "chokepoints" "supply chain"
   "policy" "energy" "logistics" "materials"])

(deftest eight-tags-receive-eight-distinct-inks
  (let [st (tagged-store)]
    (doseq [t eight-subjects]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)]
      (is (= 8 (count reg)) "every name is in the registry")
      (is (= 8 (count (set (vals reg)))) "and no two share an ink"))))

(deftest the-ninth-tag-reuses-a-least-used-ink
  (let [st (tagged-store)]
    (doseq [t (conj eight-subjects "lithography")]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)
          f   (frequencies (vals reg))]
      (is (= 9 (count reg)))
      (is (every? (set srv/tag-inks) (vals reg)) "only palette inks are ever assigned")
      (is (= 2 (apply max (vals f))) "exactly one ink is doubled")
      (is (= 7 (count (filter #(= 1 %) (vals f)))) "the other seven are untouched"))))

(deftest several-new-tags-in-one-call-get-different-inks
  ;; "world data" and "supply chain" hash to the SAME starting ink, so this is
  ;; the pair that catches assignment weighed against the registry as it was
  ;; read: measured against a map holding neither, both take that ink. Two
  ;; names that started apart would come out distinct even with the bug.
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "world data" :by "you"}
                                 {:tag "supply chain" :by "you"}])
    (let [reg (srv/tag-colors st)]
      (is (= 2 (count reg)))
      (is (= 2 (count (set (vals reg))))
          "the second name is weighed against a registry that already holds the first"))))

(deftest assignment-is-deterministic
  (let [ink (fn [] (let [st (tagged-store)]
                     (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
                     (get (srv/tag-colors st) "semiconductors")))]
    (is (= (ink) (ink)) "the same name always gets the same ink from an empty registry")))

(deftest concurrent-taggings-do-not-lose-colours
  ;; The registry is the first thing in server.clj that is GLOBAL — every other
  ;; write is scoped to one notebook, so nothing before it could contend. http-kit
  ;; serves from a worker pool, so two notebooks being tagged at once is ordinary.
  ;; A read-modify-write of the whole map loses whichever assignment lands second;
  ;; the tags survive and the colour does not, so the symptom is a tag with no ink.
  (let [st      (sub/fresh-store)
        threads 8
        per     8
        names   (fn [i] (mapv #(str "subject " i "-" %) (range per)))
        latch   (java.util.concurrent.CountDownLatch. 1)]
    (doseq [i (range threads)]
      (sub/commit! st {:op :put :id (str "space:" i)
                       :value {:id (str "space:" i) :kind :space :title "T"
                               :value {:intent "i" :cells []}}}))
    (let [ts (mapv (fn [i]
                     (doto (Thread.
                            #(do (.await latch)
                                 (srv/set-tags! st (str "space:" i)
                                                (mapv (fn [n] {:tag n :by "you"}) (names i)))))
                       (.start)))
                   (range threads))]
      (.countDown latch)
      (doseq [t ts] (.join t)))
    (let [reg  (srv/tag-colors st)
          want (mapcat names (range threads))]
      (is (= (* threads per) (count reg))
          "every name assigned by every thread is still in the registry")
      (is (every? #(contains? reg %) want) "no name lost its colour")
      (is (every? (set srv/tag-inks) (vals reg))))))

(deftest a-tag-whose-colour-exists-costs-one-event
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"} {:tag "a" :by "agent"}])
    (is (= n (count (sub/history st))) "nothing changed at all")
    (srv/set-tags! st "space:t" [])
    (is (= (inc n) (count (sub/history st))) "clearing is one event; no colour is involved")))

(deftest refusing-a-non-notebook-assigns-nothing
  (let [st (tagged-store)]
    (is (:error (srv/set-tags! st "tbl:nope" [{:tag "ghost" :by "you"}])))
    (is (empty? (srv/tag-colors st)) "a refused write must not leave a colour behind")))

(deftest state-payload-carries-tag-colors
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (let [p (srv/state-payload st)]
      (is (contains? (:tag-colors p) "a"))
      (is (not-any? #(= "palette" (:kind %)) (:objects p))
          "the registry is plumbing — it must not sit in the object list next to your notebooks"))))

(deftest the-palette-object-never-appears-in-leap
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
    (let [ids (map :id (leap-of st ""))]
      (is (seq ids) "the empty query still lists objects, so this is a real exclusion")
      (is (not-any? #(= "tag-palette" %) ids)))))

(deftest suggesting-tags-assigns-no-colours
  ;; proposing writes nothing at all — colours included
  (let [st (tagged-store)
        n  (count (sub/history st))]
    (with-redefs [agent/propose-tags (fn [_ _ _] ["ghost-tag"])]
      (srv/suggest-tags! st "space:t"))
    (is (= n (count (sub/history st))) "zero events")
    (is (empty? (srv/tag-colors st)) "and no ink claimed by a proposal you may discard")))

;; ---- choosing a colour yourself ----

(deftest set-tag-color-changes-one-tag-everywhere
  (let [st (tagged-store)
        _  (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        n  (count (sub/history st))
        ink (nth srv/tag-inks 4)]
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")))
    (is (= (inc n) (count (sub/history st))) "one event")
    (srv/set-tag-color! st "a" ink)
    (is (= (inc n) (count (sub/history st))) "setting the colour it already is commits nothing")))

(deftest set-tag-color-refuses-what-is-not-in-the-palette
  (let [st (tagged-store)]
    (is (re-find #"palette" (:error (srv/set-tag-color! st "a" "#ff00ff"))))
    (is (:error (srv/set-tag-color! st "   " (first srv/tag-inks))))
    (is (empty? (srv/tag-colors st)) "a refused write leaves nothing behind")))

(deftest set-tag-color-normalizes-the-name
  (let [st (tagged-store)]
    (srv/set-tag-color! st "  World Data  " (first srv/tag-inks))
    (is (contains? (srv/tag-colors st) "world data")
        "the same normalisation clean-tags applies, or the colour attaches to a name no tag has")))

(deftest choosing-a-colour-is-undoable
  ;; every other write in this file is reversible, and the shell's undo is one
  ;; button: a colour that could not be taken back would be the one write you
  ;; cannot walk out of.
  (let [st  (tagged-store)
        _   (srv/set-tags! st "space:t" [{:tag "a" :by "you"}])
        was (get (srv/tag-colors st) "a")
        ink (first (remove #{was} srv/tag-inks))]
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")) "the chosen ink is the assigned one changed")
    (sub/undo! st)
    (is (= was (get (srv/tag-colors st) "a")) "undo restores the previous ink")
    (is (= ["a"] (mapv :tag (get-in (sub/object st "space:t") [:value :tags])))
        "and the tag it belongs to is still there")))

(deftest set-tag-color-works-before-any-tag-exists
  ;; the picker can be opened on a store where nothing has ever been tagged, so
  ;; this is the path that has to CREATE the registry object rather than add a
  ;; key to it
  (let [st  (tagged-store)
        n   (count (sub/history st))
        ink (nth srv/tag-inks 2)]
    (is (nil? (sub/object st "tag-palette")) "no registry yet")
    (srv/set-tag-color! st "a" ink)
    (is (= ink (get (srv/tag-colors st) "a")))
    (is (= :palette (:kind (sub/object st "tag-palette")))
        "the registry it created is a real object, hideable by :kind")
    (is (= (inc n) (count (sub/history st))) "creating it is still one event")))

(deftest concurrent-colour-choices-do-not-lose-each-other
  ;; set-tag-color! writes the same global object assign-inks! does, so it
  ;; inherits the same hazard: a whole-map read-modify-write drops whichever
  ;; choice lands second. Written as a whole-map :put this function passes every
  ;; other test in this namespace — only contention tells the two apart.
  (let [st      (sub/fresh-store)
        threads 8
        per     8
        names   (fn [i] (mapv #(str "subject " i "-" %) (range per)))
        latch   (java.util.concurrent.CountDownLatch. 1)
        ts (mapv (fn [i]
                   (doto (Thread.
                          #(do (.await latch)
                               (doseq [n (names i)]
                                 (srv/set-tag-color! st n (nth srv/tag-inks (mod i 8))))))
                     (.start)))
                 (range threads))]
    (.countDown latch)
    (doseq [t ts] (.join t))
    (let [reg  (srv/tag-colors st)
          want (mapcat names (range threads))]
      (is (= (* threads per) (count reg)) "every name coloured by every thread is still there")
      (is (every? #(contains? reg %) want) "no choice was clobbered by a concurrent one"))))

;; ---- ✧ suggest ----
;; The agent proposes; you decide. suggest! reads and proposes and commits
;; NOTHING; run-suggestions! acts on exactly the list you approved and never
;; re-asks the agent.

(defn- hub-store []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:hub"
                     :value {:id "space:hub" :kind :space :title "Hub"
                             :value {:intent "the hub" :cells []}}})
    st))

(def ^:private three-proposals
  [{:title "A" :intent "ai" :query "qa"}
   {:title "B" :intent "bi" :query "qb"}
   {:title "C" :intent "ci" :query "qc"}])

(defn- spawned-kids [st]
  (->> (nb/notebooks st) (filter #(get-in % [:value :spawned-by :space]))))

(deftest suggest-commits-nothing-at-all
  ;; the whole point: a proposal you may discard must not touch the substrate
  (let [st (hub-store)
        n  (count (sub/history st))]
    (with-redefs [agent/propose-subtopics (fn [_ _ _] three-proposals)]
      (let [r (srv/suggest! st "space:hub")]
        (is (= 3 (count (:proposals r))))
        (is (= "qa" (:query (first (:proposals r)))))))
    (is (= n (count (sub/history st))) "zero events")))

(deftest suggest-refuses-a-non-notebook
  (let [st (hub-store)]
    (is (re-find #"not a notebook" (:error (srv/suggest! st "tbl:nope"))))))

(deftest a-failed-proposal-is-reported-not-thrown
  (with-redefs [agent/propose-subtopics (fn [_ _ _] (throw (Exception. "no key")))]
    (let [st (hub-store)
          n  (count (sub/history st))]
      (is (= "no key" (:error (srv/suggest! st "space:hub"))))
      (is (= n (count (sub/history st)))))))

(deftest run-suggestions-new-spawns-one-notebook-each
  (let [st (hub-store)]
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "new"))
    (let [kids (spawned-kids st)]
      (is (= 3 (count kids)))
      (is (every? #(= "space:hub" (get-in % [:value :spawned-by :space])) kids))
      (is (= #{"A" "B" "C"} (set (map :title kids)))))))

(deftest run-suggestions-here-spawns-nothing
  (let [st (hub-store)
        before (count (nb/notebooks st))
        seen (atom [])]
    (with-redefs [srv/research! (fn [_ sid q] (swap! seen conj [sid q]) {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "here"))
    (is (= before (count (nb/notebooks st))) "no notebook is created")
    (is (= [["space:hub" "qa"] ["space:hub" "qb"] ["space:hub" "qc"]] @seen)
        "each question is researched in the hub itself, in order")))

(deftest run-suggestions-runs-only-what-you-approved
  ;; the curated list is the input; the server must not re-ask the agent
  (let [st (hub-store)
        seen (atom [])]
    (with-redefs [agent/propose-subtopics (fn [_ _ _] (throw (Exception. "must not be called")))
                  srv/research! (fn [_ _ q] (swap! seen conj q) {:ok true})]
      (srv/run-suggestions! st "space:hub"
                            [{:title "A" :intent "ai" :query "edited by hand"}] "here"))
    (is (= ["edited by hand"] @seen))))

(deftest items-arrive-over-http-with-the-keys-the-code-destructures
  ;; run-suggestions! destructures :title/:intent/:query, and `items` comes off
  ;; the wire as JSON. Drive the REAL body reader so the assumption that
  ;; body-json keywordizes nested object keys is proven, not assumed.
  (let [st (hub-store)
        body (str "{\"space\":\"space:hub\",\"destination\":\"here\",\"items\":"
                  "[{\"title\":\"A\",\"intent\":\"ai\",\"query\":\"qa\"},"
                  " {\"title\":\"B\",\"intent\":\"bi\",\"query\":\"qb\"}]}")
        {:keys [space items destination]} (#'srv/body-json {:body (java.io.StringReader. body)})
        seen (atom [])]
    (with-redefs [srv/research! (fn [_ sid q] (swap! seen conj [sid q]) {:ok true})]
      (srv/run-suggestions! st space items destination))
    (is (= [["space:hub" "qa"] ["space:hub" "qb"]] @seen)
        "the questions survived the JSON round-trip into the researcher")))

(deftest run-suggestions-refuses-nonsense-honestly
  (let [st (hub-store)]
    (is (re-find #"not a notebook" (:error (srv/run-suggestions! st "tbl:x" three-proposals "new"))))
    (is (:error (srv/run-suggestions! st "space:hub" [] "new")))
    (is (re-find #"destination" (:error (srv/run-suggestions! st "space:hub" three-proposals "sideways"))))
    (is (= 1 (count (nb/notebooks st))) "a refused run creates nothing")))

(deftest a-failure-midway-keeps-what-already-landed
  ;; deliberate: a partial result is worth keeping and is individually undoable.
  ;; B's notebook is committed BEFORE its research runs, so a throw inside that
  ;; research leaves B's notebook standing and empty — honest, and undoable.
  ;; What must not happen is A's finished work being rolled back, or C running
  ;; on after a failure nobody has seen yet.
  (let [st (hub-store)
        n  (atom 0)]
    (with-redefs [srv/research! (fn [_ _ _] (if (= 2 (swap! n inc))
                                              (throw (Exception. "boom"))
                                              {:ok true}))]
      (is (= "boom" (:error (srv/run-suggestions! st "space:hub" three-proposals "new")))
          "the failure is reported, not thrown"))
    (is (= #{"A" "B"} (set (map :title (spawned-kids st))))
        "A's notebook survives B's failure; C never started")
    (is (= 2 @n) "the run stopped at the failure")))

(deftest a-new-notebook-never-overwrites-an-existing-one
  ;; the id came from the TOTAL notebook count, so it had nothing to do with the
  ;; dd sequence — and the write is a :put, which overwrites in silence. Two
  ;; notebooks in the store is exactly the state where `(inc (count …))` mints
  ;; "space:dd-3": the shape you get after two of an earlier batch are deleted.
  (let [st (hub-store)]
    (sub/commit! st {:op :put :id "space:dd-3"
                     :value {:id "space:dd-3" :kind :space :title "PRECIOUS"
                             :value {:intent "do not lose me" :cells []}}})
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" three-proposals "new"))
    (is (= "PRECIOUS" (:title (sub/object st "space:dd-3"))) "untouched")
    (is (= 5 (count (nb/notebooks st))) "hub + precious + three new")
    (is (= 3 (count (set (map :id (spawned-kids st))))) "and the three got distinct ids")))

(deftest spawned-children-inherit-the-hubs-tags
  (let [st (hub-store)]
    (srv/set-tags! st "space:hub" [{:tag "semiconductors" :by "you"}])
    (with-redefs [srv/research! (fn [_ _ _] {:ok true})]
      (srv/run-suggestions! st "space:hub" [(first three-proposals)] "new"))
    (let [kid (first (spawned-kids st))]
      (is (= ["semiconductors"] (mapv :tag (get-in kid [:value :tags])))))))

;; ---- where the model lives: endpoint, key and model are resolved, not frozen ----
;; The wire format is OpenAI's, so any server that speaks POST /v1/chat/completions
;; will do — including one on your own hardware. These tests exercise the
;; RESOLUTION ORDER only; nothing here touches the network.
;;
;; `agent/env` is the seam they bind. System/getenv is a Java static method and
;; with-redefs cannot reach it, so a test written directly against it would pass
;; or fail for reasons that have nothing to do with the resolver.

(defn- resolved
  "Call one of the agent's private resolvers under a fixed environment and a
   fixed set of working-directory files."
  [resolver env files]
  (with-redefs [agent/env #(get env %) agent/from-file #(get files %)]
    (resolver)))

(deftest llm-endpoint-defaults-to-deepseek
  ;; back-compat: an existing checkout sets none of this, and must not move.
  (is (= "https://api.deepseek.com/chat/completions"
         (resolved #'agent/endpoint {} {}))))

(deftest llm-endpoint-reads-env-then-file-then-default
  (is (= "http://from-env:8080/v1/chat/completions"
         (resolved #'agent/endpoint
                   {"LOCI_LLM_ENDPOINT" "http://from-env:8080/v1/chat/completions"}
                   {".llm-endpoint" "http://from-file:8080/v1/chat/completions"}))
      "the environment wins over the file")
  (is (= "http://from-file:8080/v1/chat/completions"
         (resolved #'agent/endpoint {} {".llm-endpoint" "http://from-file:8080/v1/chat/completions"}))
      "the file wins over the default"))

(deftest llm-key-is-not-named-after-a-vendor-you-may-not-use
  ;; someone pointing loci at their own llama server should not have to file
  ;; its token under DEEPSEEK_API_KEY or in a file named .deepseek-key — but
  ;; everyone who already did keeps working. The whole chain, in order:
  ;; LOCI_LLM_API_KEY → DEEPSEEK_API_KEY → .llm-key → .deepseek-key.
  (let [all-four {"LOCI_LLM_API_KEY" "neutral-env" "DEEPSEEK_API_KEY" "vendor-env"}
        both-files {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}]
    (is (= "neutral-env" (resolved #'agent/api-key all-four both-files))
        "LOCI_LLM_API_KEY wins over everything")
    (is (= "vendor-env" (resolved #'agent/api-key {"DEEPSEEK_API_KEY" "vendor-env"} both-files))
        "DEEPSEEK_API_KEY still works untouched, and still beats both files")
    (is (= "neutral-file" (resolved #'agent/api-key {} both-files))
        ".llm-key is the vendor-neutral file, and wins over .deepseek-key")
    (is (= "vendor-file" (resolved #'agent/api-key {} {".deepseek-key" "vendor-file"}))
        "and .deepseek-key alone still resolves, so no setup that predates .llm-key changes"))
  (is (nil? (resolved #'agent/api-key {} {}))
      "nothing configured resolves to nothing — request throws, it does not guess"))

(deftest a-blank-key-at-any-step-falls-through-rather-than-shadowing-the-next
  ;; "" is truthy in Clojure, so without `non-blank` at every step an empty
  ;; LOCI_LLM_API_KEY= in a loci.env — which is exactly what loci.env.example
  ;; ships — would shadow a perfectly good DEEPSEEK_API_KEY or key file below
  ;; it. An empty file is the same hazard: `touch .llm-key` must not hide
  ;; .deepseek-key. Each step is checked against the one immediately after it.
  (is (= "vendor-env" (resolved #'agent/api-key
                                {"LOCI_LLM_API_KEY" "" "DEEPSEEK_API_KEY" "vendor-env"}
                                {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}))
      "blank LOCI_LLM_API_KEY falls through to DEEPSEEK_API_KEY")
  (is (= "neutral-file" (resolved #'agent/api-key
                                  {"LOCI_LLM_API_KEY" "  " "DEEPSEEK_API_KEY" "\t"}
                                  {".llm-key" "neutral-file" ".deepseek-key" "vendor-file"}))
      "blank DEEPSEEK_API_KEY falls through to .llm-key")
  (is (= "vendor-file" (resolved #'agent/api-key {} {".llm-key" "" ".deepseek-key" "vendor-file"}))
      "a blank .llm-key falls through to .deepseek-key")
  (is (nil? (resolved #'agent/api-key
                      {"LOCI_LLM_API_KEY" "" "DEEPSEEK_API_KEY" " "}
                      {".llm-key" "  " ".deepseek-key" "\n"}))
      "all four blank is no key at all — the honest error fires, nothing is guessed"))

(deftest a-blank-llm-value-is-unset-not-a-configured-empty-string
  ;; loci.env.example ships `LOCI_LLM_API_KEY=` empty, and "" is truthy in
  ;; Clojure — so an empty key resolved to "" and the request went out as
  ;; `Authorization: Bearer `, which a llama.cpp started without --api-key
  ;; rejects. The honest "no LLM key" error never fired and the user saw a 401
  ;; that reads like a wrong key.
  (is (nil? (resolved #'agent/api-key {"LOCI_LLM_API_KEY" ""} {}))
      "an empty key is no key, so request throws the honest error")
  (is (= "vendor" (resolved #'agent/api-key {"LOCI_LLM_API_KEY" "   " "DEEPSEEK_API_KEY" "vendor"} {}))
      "a blank value falls through to the next source rather than shadowing it")
  (is (= "https://api.deepseek.com/chat/completions"
         (resolved #'agent/endpoint {"LOCI_LLM_ENDPOINT" ""} {}))
      "an empty endpoint is not a configured empty URL")
  (is (= "deepseek-v4-flash" (resolved #'agent/model {"DEEPSEEK_MODEL" "  "} {}))
      "and a blank model does not become the model name"))

(deftest llm-model-still-reads-env-then-file-then-default
  ;; DEEPSEEK_MODEL now names the model on WHATEVER server is configured; the
  ;; variable name is historical, and its resolution order must not have moved.
  (is (= "local-thing" (resolved #'agent/model {"DEEPSEEK_MODEL" "local-thing"}
                                 {".deepseek-model" "file-thing"})))
  (is (= "file-thing" (resolved #'agent/model {} {".deepseek-model" "file-thing"})))
  (is (= "deepseek-v4-flash" (resolved #'agent/model {} {}))))

;; ============================================================================
;; the memory pane, and which callers pay for meaning
;; ============================================================================

(defn- embed-env
  "loci.embed's resolvers seeing exactly `env` and no files. Nothing here opens
   a socket — every assertion below is about the *shape* of a payload."
  [env]
  {:env #(get env %) :file (constantly nil)})

(deftest the-memory-pane-reports-what-awaits-an-embedding-and-never-ships-a-vector
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (mold/remember m "Neptune takes 165 years to orbit the Sun." {})
    ;; one fact given a vector directly: the pane's job is to report the state of
    ;; the store, not to produce it, so this needs no embedder and no network
    (swap! (:!facts m) update "mem-1" assoc :vec (vec (repeat 1024 0.01)) :model "m" :dim 1024)

    (let [{:keys [env file]} (embed-env {})]
      (with-redefs [embed/env env embed/from-file file]
        (let [p (srv/memory-payload m nil)]
          (is (= 2 (count (:facts p))))
          (is (nil? (:embedding p)))
          (is (= 2 (:awaiting p))
              "with no embedder configured every fact awaits one forever — reported next to a nil model, so it reads as an unconfigured feature and not as a backlog"))))

    (let [{:keys [env file]} (embed-env {"LOCI_EMBED_ENDPOINT" "http://127.0.0.1:9/v1/embeddings"
                                         "LOCI_EMBED_MODEL"    "m"})]
      (with-redefs [embed/env env embed/from-file file]
        (let [p (srv/memory-payload m nil)]
          (is (= "m" (:embedding p)))
          (is (= 1 (:awaiting p)) "the embedded one awaits nothing")
          (is (every? #(nil? (:vec %)) (:facts p))
              "and no vector goes out — ~1024 floats is about 20 KB of JSON per fact, and the pane shows text"))))))

(defn- recall-spy
  "A Recall that answers nothing and writes down what it was asked.

   A `with-redefs` of `mold/recall` would not see these calls at all: the
   compiler turns a protocol call into a cached callsite that goes straight to
   the implementing class, so rebinding the var intercepts nothing. Both payload
   functions take the memory as an argument, so handing them one is both simpler
   and closer to what actually happens."
  [seen]
  (reify mold/Recall
    (remember  [_ _ _] :ok)
    (all-facts [_] [])
    (recall    [_ q opts] (swap! seen conj [q opts]) [])))

(deftest the-pane-searches-by-meaning-and-the-keystroke-path-does-not
  ;; The single most expensive decision in hybrid recall, asserted at the two
  ;; call sites that differ. /api/leap runs on every keystroke; embedding a query
  ;; is a 20–50 ms network round trip.
  (let [seen (atom [])
        m    (recall-spy seen)]
    (srv/memory-payload m "chips")
    (is (= [["chips" {:k 20 :semantic? true}]] @seen)
        "a search the user typed and is waiting for asks for meaning")
    (reset! seen [])
    (srv/leap-payload (sub/fresh-store) m "chips")
    (is (= [["chips" {:k 8}]] @seen))
    (is (not (:semantic? (second (first @seen))))
        "and the keystroke path does not — it must stay exactly as fast as it was")))

;; ============================================================================
;; what the pane learns about the memory layer itself
;; ============================================================================

(defn- a-closed-port
  "A port with nothing listening: bind one, ask which it was, close it. An
   unreachable embedder that needs no network to be unreachable."
  []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- one-embedded-fact
  "A throwaway memory holding a single fact that already carries a vector
   stamped `model`. Poked into the atom rather than embedded, because embedding
   it would need an embedder and what is under test is the payload."
  [model]
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Neptune takes 165 years to orbit the Sun." {})
    (swap! (:!facts m) update "mem-1" assoc :vec [0.1 0.2] :model model :dim 2)
    m))

(def ^:private neptune
  {:id "mem-1" :fact "Neptune takes 165 years to orbit the Sun." :entities []
   :ts 1000 :strength 1 :vec [0.1 0.2] :model "embed-qwen3-4b" :dim 2})

(defn- healthy-recall
  "A Recall whose semantic half ran and worked: hits, carrying the metadata a
   working embedder leaves behind. Opens no socket, so `embedding-configured?`
   can be true above it without anything being contacted."
  [facts]
  (reify mold/Recall
    (remember  [_ _ _] :ok)
    (all-facts [_] facts)
    (recall    [_ _ _] (with-meta (mapv #(assoc % :score 0.5 :via [:lexical :semantic]) facts)
                         {:semantic :ok}))))

(deftest an-unreachable-embedder-reaches-the-pane-as-a-plain-key
  ;; recall says it is degraded in the returned vector's METADATA, and metadata
  ;; does not survive json/write-str. Without lifting it out the pane shows
  ;; fewer results than it did yesterday with nothing anywhere saying why.
  (let [m (one-embedded-fact "m")
        {:keys [env file]}
        (embed-env {"LOCI_EMBED_ENDPOINT" (str "http://127.0.0.1:" (a-closed-port) "/v1/embeddings")
                    "LOCI_EMBED_MODEL"    "m"})]
    (with-redefs [embed/env env embed/from-file file]
      (let [p (srv/memory-payload m "Neptune")]
        (is (string? (:degraded p))
            "a plain key on the payload, not metadata on a vector")
        (is (str/includes? (:degraded p) "degraded"))
        (is (= 1 (count (:facts p)))
            "and the words still answer — degraded is not broken")))))

(deftest the-healthy-configured-payload-is-key-for-key-what-it-has-always-been
  ;; Four callers read this map. Adding :merge must move nothing else, and
  ;; :degraded must be ABSENT rather than nil or false — a key that is always
  ;; there and usually empty is what makes `if (p.degraded)` and
  ;; `if ('degraded' in p)` disagree, and only one of them is right.
  (let [m (healthy-recall [neptune])
        ;; nothing contacts this: healthy-recall answers without an embedder,
        ;; and the endpoint exists only to make `embedding-configured?` true
        {:keys [env file]} (embed-env {"LOCI_EMBED_ENDPOINT" "http://embedder.invalid/v1/embeddings"
                                       "LOCI_EMBED_MODEL"    "embed-qwen3-4b"})]
    (with-redefs [embed/env env embed/from-file file]
      (let [p (srv/memory-payload m "Neptune")]
        (is (= #{:facts :total :awaiting :embedding :merge} (set (keys p)))
            "the three keys it has always had, plus :merge and :total — and nothing else")
        (is (not (contains? p :degraded))
            "absent, not nil and not false")
        (is (= 0 (:awaiting p)))
        (is (= "embed-qwen3-4b" (:embedding p)))
        (is (= [(dissoc neptune :vec)] (mapv #(dissoc % :score :via) (:facts p)))
            "and the facts are untouched apart from the vector that never ships")
        (is (= 1 (:total p)))
        (is (= {:threshold 0.88} (:merge p)))))))

(deftest the-total-is-the-memorys-size-and-not-the-pages
  ;; The status line reads "12 of 80 awaiting". The 80 has to be the memory, so
  ;; it cannot be read off `:facts` — which under a query is the ranked top 20,
  ;; and under a scope is one notebook's slice.
  (let [m (mem/file-memory (tmpfile))]
    ;; 25 facts, so `:k 20` genuinely truncates and a page-derived total would
    ;; differ from the memory's by five. Every sentence shares exactly one token
    ;; with every other — "neptune", so all 25 rank for the query — and is
    ;; otherwise disjoint, because `remember` REINFORCES a near-duplicate at
    ;; jaccard 0.6 instead of adding it, and 25 variations on one sentence would
    ;; quietly be one fact.
    (doseq [i (range 25)]
      (mold/remember m (str "Neptune alpha" i " beta" i " gamma" i " delta" i ".") {}))
    (is (= 25 (count (mem/all-facts m)))
        "the fixture is 25 distinct facts, not one fact reinforced 25 times")
    (let [{:keys [env file]} (embed-env {})]
      (with-redefs [embed/env env embed/from-file file]
        (let [browse (srv/memory-payload m nil)
              found  (srv/memory-payload m "Neptune")]
          (is (= 25 (:total browse)) "a browse returns all 25 and says 25")
          (is (= 20 (count (:facts found)))
              "the query is answered with the top 20 — the page really is smaller")
          (is (= 25 (:total found))
              "…and the total is still the memory's 25, not the page's 20")
          ;; the pair of numbers the status line is made of, on the same response
          (is (= 25 (:awaiting found))
              "with no embedder every fact awaits one — 25 of 25, both from the memory"))))))

(deftest the-payload-says-whether-merging-runs
  (let [payload-under (fn [env-map]
                        (let [{:keys [env file]} (embed-env env-map)]
                          (with-redefs [embed/env env embed/from-file file]
                            (srv/memory-payload (one-embedded-fact "embed-qwen3-0.6b") nil))))
        at            (fn [model] {"LOCI_EMBED_ENDPOINT" "http://embedder.invalid/v1/embeddings"
                                   "LOCI_EMBED_MODEL"    model})]
    (is (= {:threshold 0.85} (:merge (payload-under (at "embed-qwen3-0.6b"))))
        "a calibrated model reports the number merging actually uses")
    (is (= {:threshold 0.88} (:merge (payload-under (at "embed-qwen3-4b"))))
        "each model its own — the table is per model, and so is this")
    (let [r (:merge (payload-under (at "embed-bge-m3")))]
      (is (string? (:refused r)) "uncalibrated: a refusal, not the nearest number")
      (is (str/includes? (:refused r) "embed-bge-m3") "naming the model it has no number for")
      (is (nil? (:threshold r)) "and no threshold beside it to be read instead"))

    ;; The question the plan left open, answered here. With no embedder the
    ;; DEFAULT model name still resolves and happens to be calibrated, so the
    ;; bare lookup would answer {:threshold 0.85} for a memory in which not one
    ;; fact has a vector and merge-similar! therefore considers nothing,
    ;; forever. Nor is it a refusal: a refusal names the model as the reason,
    ;; and here the model is fine and the embedder is absent. :off is the word
    ;; loci.embed already uses for not-configured as against not-working, and
    ;; it lets the pane report the missing embedder once instead of twice.
    (is (= {:off true} (:merge (payload-under {})))
        "no embedder configured is off — neither a threshold nor a refusal")))

(deftest computing-the-merge-status-runs-no-merge
  ;; This is a GET. merge-similar! is O(n²) cosines over the whole memory and
  ;; warns once per refusal; running one to find out whether one would run
  ;; would spend a page load on it and swallow the startup warning into a
  ;; request that nobody is reading stderr for.
  (let [seed  (fn [model]
                (let [path (tmpfile)
                      m    (mem/file-memory path)]
                  (mold/remember m "Kepler's Third Law relates period and axis." {})
                  (mold/remember m "The orbital period grows with distance." {})
                  ;; identical vectors: cosine 1.0, above every calibrated
                  ;; threshold, so a merge that ran would certainly merge
                  (swap! (:!facts m)
                         #(reduce-kv (fn [a k v] (assoc a k (assoc v :vec [1.0 0.0] :model model :dim 2)))
                                     {} %))
                  [path m]))
        lines (fn [path] (count (str/split-lines (str/trim (slurp path)))))
        under (fn [model f]
                (let [{:keys [env file]} (embed-env {"LOCI_EMBED_ENDPOINT" "http://embedder.invalid/v1/embeddings"
                                                     "LOCI_EMBED_MODEL"    model})]
                  (with-redefs [embed/env env embed/from-file file] (f))))]
    (let [[path m] (seed "embed-qwen3-0.6b")
          before   (lines path)
          p        (under "embed-qwen3-0.6b" #(srv/memory-payload m nil))]
      (is (= {:threshold 0.85} (:merge p)))
      (is (= before (lines path)) "nothing was appended — the pane did not merge")
      (is (= 2 (count (mem/all-facts m))) "both facts are still there")
      (is (every? #(nil? (:merged-from %)) (mem/all-facts m))))

    (let [[_ m]  (seed "embed-bge-m3")
          warned @@#'mem/!warned
          err    (java.io.StringWriter.)
          p      (binding [*err* err] (under "embed-bge-m3" #(srv/memory-payload m nil)))]
      (is (string? (:refused (:merge p))))
      (is (= warned @@#'mem/!warned)
          "the once-only refusal warning was not consumed by a page load")
      (is (= "" (str err)) "nor printed into a request"))))

;; ============================================================================
;; POST /api/memory-unmerge — the gesture that undoes one merge
;; ============================================================================

(defn- merged-memory
  "A throwaway memory holding exactly the state a merge leaves behind: a
   survivor carrying one `:merged-from` entry, and the absorbed id as a
   tombstone.

   Written as records rather than produced by a merge pass on purpose — this is
   about the endpoint's shape, and `loci.memory-merge-test` is where the pass
   itself is tested. Nothing here needs an embedder, so there is no vector on
   the absorbed side and the survivor's is a stand-in for one."
  []
  (let [path (tmpfile)]
    (io/make-parents (io/file path))
    (spit path (str/join "\n"
                         [(pr-str {:id "mem-1" :fact "TSMC makes most sub-7nm chips." :entities ["tsmc"]
                                   :source {:obj "obj-1"} :ts 2000 :strength 5
                                   :vec [1.0 0.0] :model "embed-qwen3-0.6b" :dim 2
                                   :merged-from [{:id "mem-2" :fact "Most advanced chips are TSMC's."
                                                  :entities ["taiwan"] :source {:obj "obj-9"}
                                                  :ts 2000 :strength 2}]})
                          (pr-str {:id "mem-2" :fact "Most advanced chips are TSMC's."
                                   :ts 2000 :merged-into "mem-1"})
                          ""]))
    (mem/file-memory path)))

(deftest the-unmerge-endpoint-answers-with-both-records-and-no-vector
  (let [m (merged-memory)
        r (srv/memory-unmerge! m "mem-1" "mem-2")]
    (is (= "mem-1" (:id (:survivor r))))
    (is (= "mem-2" (:id (:restored r))))
    (is (= "Most advanced chips are TSMC's." (:fact (:restored r))))
    (is (= 3 (:strength (:survivor r))) "5 - the 2 it absorbed")
    (is (nil? (:vec (:survivor r)))
        "no embedding goes out, for the reason memory-payload drops them: ~1024 floats per fact")
    (is (nil? (:vec (:restored r))))
    (is (= 2 (count (mem/all-facts m))) "and the memory itself now holds two facts")
    (is (string? (json/write-str r))
        "the response serialises — :no-merge is a set, and a set that data.json refused would 500")))

(deftest the-unmerge-endpoint-refuses-rather-than-throwing
  (let [m (merged-memory)]
    (is (string? (:error (srv/memory-unmerge! m "mem-1" "mem-404")))
        "an id the survivor never absorbed")
    (is (string? (:error (srv/memory-unmerge! m nil "mem-2"))) "a missing survivor")
    (is (string? (:error (srv/memory-unmerge! m "mem-1" nil))) "a missing absorbed id")
    (is (string? (:error (srv/memory-unmerge! m "" ""))))
    (is (= 1 (count (mem/all-facts m))) "and none of that changed the memory")))

(deftest the-panes-total-counts-live-facts-and-not-tombstones
  ;; A merge does not delete: it appends a record carrying :merged-into for the
  ;; absorbed id. Counting the raw map would say the memory grew by absorbing
  ;; something into itself. And the pane's own note: after an unmerge the count
  ;; goes UP, because a fact came back.
  (let [m (merged-memory)
        {:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file]
      (is (= 1 (:total (srv/memory-payload m nil)))
          "two records in the file, one of them a tombstone: one live fact")
      (srv/memory-unmerge! m "mem-1" "mem-2")
      (let [p (srv/memory-payload m nil)]
        (is (= 2 (:total p))
            "after the unmerge the restored fact is counted, because it is one again")
        (is (= 2 (count (:facts p))))))))

;; ============================================================================
;; POST /api/memory-durable — the judgement you make by hand
;; ============================================================================
;;
;; The third and last writer of `:durable?`. The distiller proposes on every new
;; fact, `loci.classify` proposed once over the corpus, and this is the one that
;; overrides both and is never overridden — which is what `:durable-by "you"`
;; buys and what the reinforcement test at the bottom of this section spends a
;; whole round trip through `remember` to prove.

(defn- unjudged-memory
  "A throwaway memory holding two live facts nobody has judged and one
   tombstone, as `[path m]`.

   Written as records rather than through `remember` so that `:ts` and
   `:strength` carry values the write under test could not have produced by
   accident: `:ts 1000` is 1970 and `:strength 3` is not the 1 a fresh
   `remember` writes. \"`:ts` and `:strength` are unchanged\" asserted against a
   fact seeded a millisecond ago would pass against a route that went through
   `remember` — which is the one break this endpoint most has to survive.

   `mem-1` carries a `:vec` so \"no embedding goes out\" is an assertion and not
   a tautology about a fact that never had one."
  []
  (let [path (tmpfile)]
    (io/make-parents (io/file path))
    (spit path (str/join "\n"
                         [(pr-str {:id "mem-1" :fact "Kepler's Third Law states that T² ∝ a³."
                                   :entities ["kepler"] :source {:obj "obj-1"}
                                   :ts 1000 :strength 3
                                   :vec [1.0 0.0] :model "embed-qwen3-0.6b" :dim 2})
                          (pr-str {:id "mem-2" :fact "Lithography capacity is booked through 2027."
                                   :entities ["asml"] :source {:obj "obj-2"}
                                   :ts 2000 :strength 1})
                          (pr-str {:id "mem-3" :fact "Most advanced chips are TSMC's."
                                   :ts 2000 :merged-into "mem-2"})
                          ""]))
    [path (mem/file-memory path)]))

(defn- fact-by-id [m id]
  (first (filter #(= id (:id %)) (mem/all-facts m))))

(deftest the-durable-endpoint-records-your-judgement-and-changes-nothing-else
  (let [[_ m] (unjudged-memory)
        r     (srv/memory-durable! m "mem-1" true)]
    (is (= "mem-1" (:id r)) "the record, not a pane payload — this request carries no q and no scope")
    (is (nil? (:facts r)) "and nothing that could be mistaken for one")
    (is (true? (:durable? r)))
    (is (= "you" (:durable-by r))
        "the whole point of the field: a distiller re-deriving this sentence next week may revise the agent's answer and may not revise yours")
    (is (nil? (:vec r))
        "no embedding goes out, for the reason memory-payload drops them: ~1024 floats per fact")
    ;; the `remember` trap, stated as an assertion. Going through `mold/remember`
    ;; would find this fact as its own jaccard ≥ 0.6 duplicate, bump :strength and
    ;; reset :ts to now — so marking a fact NOT durable would hand it a fresh 1.0
    ;; recency multiplier in the same breath as recording that it ages.
    (is (= 1000 (:ts r)) "the judgement is about the sentence, not a re-remembering of it")
    (is (= 3 (:strength r)) "nothing here says this fact came up again")
    (is (= "Kepler's Third Law states that T² ∝ a³." (:fact r)) "and the text is untouched")
    (let [f (fact-by-id m "mem-1")]
      (is (true? (:durable? f)) "and it is in the memory, not only in the answer")
      (is (= "you" (:durable-by f)))
      (is (= 1000 (:ts f)))
      (is (= 3 (:strength f)))
      (is (some? (:vec f))
          "the vector is dropped from the ANSWER and kept in the store — the text did not change, so the embedding still describes it"))
    (is (string? (json/write-str r))
        "the response serialises — a route that 500s on its own answer is a route nobody can call")))

(deftest the-durable-endpoint-toggles-back-and-the-judgement-stays-yours
  (let [[_ m] (unjudged-memory)]
    (srv/memory-durable! m "mem-1" true)
    (let [r (srv/memory-durable! m "mem-1" false)]
      (is (false? (:durable? r))
          "`false?` and not `(not (:durable? r))`: the second passes on a route that deleted the key, and an absent key is \"nothing has judged this\" rather than \"this one ages\"")
      (is (= "you" (:durable-by r))
          "changing your mind is still your judgement — a toggle that dropped the provenance would hand the next distiller pass permission to overwrite it")
      (is (= 1000 (:ts r)) "and the second write re-stamps nothing either")
      (is (= 3 (:strength r))))
    ;; and again, to the same value it already holds: a no-op toggle is an
    ;; ordinary success and not "the memory changed while this was being applied"
    (let [r (srv/memory-durable! m "mem-1" false)]
      (is (false? (:durable? r)))
      (is (nil? (:error r))
          "setting a flag to what it already says is idempotent, not a conflict"))))

(deftest the-durable-endpoint-refuses-rather-than-throwing
  (let [[_ m] (unjudged-memory)]
    (is (string? (:error (srv/memory-durable! m "mem-404" true)))
        "an id no fact has — a message, not a 500")
    ;; and the sentence says the ID is missing rather than that no fact has it.
    ;; `string?` alone could not fail here: drop the blank-id guard and a nil id
    ;; still refuses, one clause later, with "there is no fact  to judge" — a
    ;; message with a hole where the id should be, which is the one thing a
    ;; refusal written as a sentence exists to avoid.
    (is (re-find #"needs the id" (str (:error (srv/memory-durable! m nil true))))
        "a missing id")
    (is (re-find #"needs the id" (str (:error (srv/memory-durable! m "" true)))))
    (is (string? (:error (srv/memory-durable! m "mem-3" true)))
        "a tombstone: mem-3 was absorbed into mem-2, and stamping it would write a judgement onto a record only `live` saves every reader from")
    ;; `boolean?` and not `contains?`, ratified when the distiller and then
    ;; loci.classify met the same question. `weight` reads the VALUE, and the
    ;; string "false" is truthy in Clojure — so a hand-rolled POST carrying it
    ;; would mark a fact durable, stamp it `:durable-by "you"`, and thereby make
    ;; `loci.classify` skip it forever. The pane always sends a boolean; this is
    ;; about everything that is not the pane.
    (is (string? (:error (srv/memory-durable! m "mem-1" "false")))
        "a judgement that is neither true nor false is not a judgement")
    (is (string? (:error (srv/memory-durable! m "mem-1" nil))))
    (is (every? #(not (contains? % :durable?)) (mem/all-facts m))
        "and none of that judged anything")))

(deftest the-durable-endpoint-appends-and-rewrites-nothing
  ;; The log is append-only and last-wins by :id — `next-id`'s tombstone
  ;; reasoning depends on nothing ever being removed, so a route that rewrote
  ;; the file in place would be correct today and reissue an id later.
  (let [[path m] (unjudged-memory)
        before   (slurp path)]
    (srv/memory-durable! m "mem-1" true)
    (let [after (slurp path)]
      (is (str/starts-with? after before)
          "the prefix is byte-identical: this is one more line, not a rewritten file")
      (is (= 1 (- (count (str/split-lines (str/trim after)))
                  (count (str/split-lines (str/trim before)))))
          "exactly one line per judgement")
      (is (= 2 (count (mem/all-facts m)))
          "and the memory still holds the two live facts it held — an append under an existing id replaces it, it does not add a fact"))))

(deftest the-pane-payload-carries-the-judgement-it-is-asked-to-render
  ;; The plan says no payload change should be needed. That is a claim about
  ;; `memory-payload`, so it is checked here rather than assumed: it dissocs
  ;; `:vec` and ships every other key, and the pane cannot render a distinction
  ;; the payload drops.
  (let [[_ m] (unjudged-memory)]
    (srv/memory-durable! m "mem-1" true)
    (let [{:keys [env file]} (embed-env {})]
      (with-redefs [embed/env env embed/from-file file]
        (let [fs (into {} (map (juxt :id identity)) (:facts (srv/memory-payload m nil)))]
          (is (true? (:durable? (fs "mem-1"))))
          (is (= "you" (:durable-by (fs "mem-1")))
              "whose judgement it is travels with it — the pane says so, and only the payload can tell it")
          (is (nil? (:vec (fs "mem-1"))) "still no vectors, which is the one key that does not travel")
          (is (false? (contains? (fs "mem-2") :durable?))
              "and an unjudged fact arrives with no key at all — absent means it decays, which is today's behaviour and not a judgement that it ages"))))))

(deftest a-later-reinforcement-does-not-revert-what-you-said-through-the-route
  ;; Task 2 gave `remember` the guard and pinned it against a hand-stamped atom.
  ;; This is the same rule through the real endpoint, which is the only place
  ;; `"you"` is ever written: mark a fact by hand, then let a distiller pass
  ;; re-derive the same sentence and say the opposite.
  ;;
  ;; Drop `:durable-by "you"` from the route and this is the test that fails —
  ;; the flag survives on its own and the provenance is what protects it.
  (let [[_ m] (unjudged-memory)
        kepler "Kepler's Third Law states that T² ∝ a³."]
    (srv/memory-durable! m "mem-1" true)
    (mold/remember m kepler {:entities ["orbits"] :durable? false})
    (let [f (fact-by-id m "mem-1")]
      (is (true? (:durable? f))
          (str "the agent re-derived the sentence and judged it as-of-now; you had already said otherwise. Got "
               (pr-str (select-keys f [:durable? :durable-by]))))
      (is (= "you" (:durable-by f)) "and it is still yours, so the next pass is refused too")
      ;; the other half of Task 2's correction: only the JUDGEMENT stands. A
      ;; guard written as `(if (= "you" …) dup …)` would return the fact whole
      ;; and freeze it at strength 1 with text that never updates.
      (is (= 4 (:strength f))
          "reinforcement still happened — the fact came up again and says so")
      (is (contains? (set (:entities f)) "orbits")
          "and the entities still union"))
    ;; the mirror image: an agent judgement IS revisable, so the guard is about
    ;; provenance and not about the flag being written once
    (mold/remember m "Lithography capacity is booked through 2027." {:durable? true})
    (let [f (fact-by-id m "mem-2")]
      (is (true? (:durable? f)) "nobody had judged mem-2, so the agent's answer lands")
      (is (= "agent" (:durable-by f))))))

;; ============================================================================
;; memory scoped to one notebook's lineage (/api/memory?space=…)
;; ============================================================================

(defn- put-space!
  "A notebook holding one doc cell, plus whatever lineage keys the case needs."
  [st id title doc-id doc-text extra]
  (sub/commit! st {:op :put :id doc-id
                   :value {:id doc-id :kind :doc :title (str title " findings") :value doc-text}})
  (sub/commit! st {:op :put :id id
                   :value {:id id :kind :space :title title
                           :value (merge {:intent title :cells [{:ref doc-id}]} extra)}}))

(defn- lineage-fixture
  "A substrate whose shape covers every reason `nb/links` can report:

     space:semis   the root
       space:kid     spawned-by semis          — descent
         space:gk      spawned-by kid          — descent, one more hop
     space:other   unrelated
     space:mix     merged-from [semis other]   — absorbed both
     space:peer    shares find:semis-1 as a cell of its own

   Every notebook holds one doc cell, and every fact below is recorded the way
   `distill!` records one: `:source {:obj <doc> :space <notebook>}`."
  []
  (let [st (sub/fresh-store)]
    (put-space! st "space:semis" "Semis" "find:semis-1" "chips fabs" {})
    (put-space! st "space:kid"   "Kid"   "find:kid-1"   "chips euv"
                {:spawned-by {:space "space:semis" :prompt "go deeper"}})
    (put-space! st "space:gk"    "GKid"  "find:gk-1"    "chips litho"
                {:spawned-by {:space "space:kid" :prompt "deeper still"}})
    (put-space! st "space:other" "Other" "find:other-1" "chips unrelated" {})
    (put-space! st "space:mix"   "Mix"   "find:mix-1"   "chips merged"
                {:merged-from ["space:semis" "space:other"]})
    (sub/commit! st {:op :put :id "space:peer"
                     :value {:id "space:peer" :kind :space :title "Peer"
                             :value {:intent "peer" :cells [{:ref "find:semis-1"}]}}})
    st))

(defn- lineage-memory
  "One fact per notebook, all of them lexically matching \"chips\" so the
   *filter* is the only thing that can separate them."
  []
  (let [m (mem/file-memory (tmpfile))]
    (doseq [[nb obj txt] [["space:semis" "find:semis-1" "chips are fabricated in Taiwan."]
                          ["space:kid"   "find:kid-1"   "chips need EUV lithography."]
                          ["space:gk"    "find:gk-1"    "chips shrink with each node."]
                          ["space:other" "find:other-1" "chips are unrelated here."]
                          ["space:mix"   "find:mix-1"   "chips were merged together."]
                          ["space:peer"  "find:peer-1"  "chips seen by a peer notebook."]]]
      (mold/remember m txt {:source {:obj obj :space nb}}))
    m))

(defn- trunk-fixture
  "The shape the trunk rule is about — a research hub that branched twice, and
   one of those branches going deeper:

     space:root                          the trunk
       space:a     spawned-by root         a branch
         space:leaf  spawned-by a            deeper down that branch
       space:b     spawned-by root         THE SIBLING — must stay invisible from a and leaf

   Modelled on the live corpus: root = `Semiconductors — research hub`,
   a = `Taiwan's Foundry Concentration`, leaf = `Geopolitical Shock Scenarios`,
   b = `EUV Lithography Monopoly`."
  []
  (let [st (sub/fresh-store)]
    (put-space! st "space:root" "Root" "find:root-1" "chips root"  {})
    (put-space! st "space:a"    "A"    "find:a-1"    "chips a"     {:spawned-by {:space "space:root" :prompt "go deeper"}})
    (put-space! st "space:leaf" "Leaf" "find:leaf-1" "chips leaf"  {:spawned-by {:space "space:a" :prompt "deeper still"}})
    (put-space! st "space:b"    "B"    "find:b-1"    "chips b"     {:spawned-by {:space "space:root" :prompt "the other way"}})
    st))

(defn- trunk-memory
  "One fact per notebook of `trunk-fixture`, every one of them lexically
   matching \"chips\" so only the *filter* can separate them."
  []
  (let [m (mem/file-memory (tmpfile))]
    (doseq [[nb obj txt] [["space:root" "find:root-1" "chips are made in fabs."]
                          ["space:a"    "find:a-1"    "chips concentrate in Taiwan."]
                          ["space:leaf" "find:leaf-1" "chips face a blockade risk."]
                          ["space:b"    "find:b-1"    "chips need EUV from one vendor."]]]
      (mold/remember m txt {:source {:obj obj :space nb}}))
    m))

(defn- scoped-facts
  "The fact strings /api/memory returns for `q` under `space`, with no embedder
   configured anywhere — `:semantic? true` degrades to lexical, so this test
   opens no socket and passes offline."
  [st m q space]
  (let [{:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file]
      (let [p (srv/memory-request st m q space)]
        (assoc p :fact-set (set (map :fact (:facts p))))))))

(deftest memory-scoped-to-a-notebook-sees-its-lineage-and-nothing-else
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; descent, transitively: the notebook, its child, and its child's child
    (is (= #{"chips are fabricated in Taiwan." "chips need EUV lithography."
             "chips shrink with each node."}
           (:fact-set (scoped-facts st m "chips" "space:semis")))
        "spawned is lineage and it is transitive; nothing else is")
    ;; upward is the TRUNK, own ids only — from the child you see the parent
    ;; (changed 2026-08-08; it used to stop at the child)
    (is (= #{"chips need EUV lithography." "chips shrink with each node."
             "chips are fabricated in Taiwan."}
           (:fact-set (scoped-facts st m "chips" "space:kid")))
        "the parent's own facts, because they are the trunk this branch grew out of")
    ;; a merge absorbs both sides' facts
    (is (= #{"chips were merged together." "chips are fabricated in Taiwan."
             "chips need EUV lithography." "chips shrink with each node."
             "chips are unrelated here."}
           (:fact-set (scoped-facts st m "chips" "space:mix")))
        "merged-from brings in what was folded in — and its descent with it")
    ;; …but the merge is downstream of semis, so semis does not gain the mix
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:semis"))
                        "chips were merged together."))
        "merged is upward: a notebook this one was folded into is not its lineage")
    ;; A shared cell brings the facts distilled FROM THAT CELL and nothing else.
    ;; space:peer holds find:semis-1 as one of its own cells, so the fact whose
    ;; :source :obj is find:semis-1 is in peer's scope — it is a fact about a
    ;; document sitting in peer. What peer does NOT get is the rest of semis:
    ;; not its child notebooks, and not a fact recorded against semis with no
    ;; object (which is what `ask!` writes).
    (is (= #{"chips seen by a peer notebook." "chips are fabricated in Taiwan."}
           (:fact-set (scoped-facts st m "chips" "space:peer")))
        "a shared cell is in scope; the notebook that shares it is not")
    (mold/remember m "chips policy discussed with no document." {:source {:space "space:semis"}})
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:peer"))
                        "chips policy discussed with no document."))
        "sharing one cell does not make peer a descendant of semis")
    (is (not (contains? (:fact-set (scoped-facts st m "chips" "space:semis"))
                        "chips seen by a peer notebook.")))))

(deftest a-scope-that-holds-no-matching-fact-returns-empty-and-says-so
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; "unrelated" matches exactly one fact, and it lives in space:other
    (let [p (scoped-facts st m "unrelated" "space:other")]
      (is (= #{"chips are unrelated here."} (:fact-set p))))
    (let [p (scoped-facts st m "unrelated" "space:semis")]
      (is (= [] (:facts p))
          "a scoped recall that matches nothing in scope answers with nothing — it does NOT fall back to the whole memory")
      (is (= "space:semis" (get-in p [:scope :space])))
      (is (true? (get-in p [:scope :empty]))
          "and it says the emptiness is the scope's, not the memory's")
      (is (pos? (get-in p [:scope :sources]))
          "the eligible set was non-empty, so this is 'nothing here', not 'nothing to look at'"))))

(deftest a-scoped-browse-with-no-query-is-scoped-too
  ;; all-facts takes no filter, so this is the path most likely to leak
  (let [st (lineage-fixture)
        m  (lineage-memory)
        p  (scoped-facts st m nil "space:kid")]
    (is (= #{"chips need EUV lithography." "chips shrink with each node."
             "chips are fabricated in Taiwan."}
           (:fact-set p))
        "itself, its descendant, and its parent — the trunk, browsed the same way it is recalled")
    (is (= 6 (count (mem/all-facts m))) "…out of six remembered")))

(deftest a-scope-narrows-the-facts-and-never-the-total
  ;; `:total` is what the status line's denominator is made of, and that line is
  ;; a statement about the memory. A scoped pane showing three facts out of six
  ;; still awaits embeddings for six.
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    (doseq [[q label] [["chips" "under a query"] [nil "browsing"]]]
      (let [scoped (scoped-facts st m q "space:kid")
            whole  (scoped-facts st m q nil)]
        (is (= 3 (count (:facts scoped))) (str label ": space:kid's trunk is three of the six"))
        (is (= 6 (:total scoped))
            (str label ": but the memory holds six, and that is what the total says"))
        (is (= (:total whole) (:total scoped))
            (str label ": so scoping the pane cannot change the denominator"))
        (is (= (count (mem/all-facts m)) (:total scoped)))))))

(deftest an-unknown-or-non-notebook-scope-is-an-error-not-an-empty-answer
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    (is (= {:error "not a notebook: space:typo"} (srv/memory-request st m "chips" "space:typo")))
    (is (= {:error "not a notebook: find:semis-1"} (srv/memory-request st m "chips" "find:semis-1"))
        "a doc is not a notebook; answering [] would read as 'nothing remembered here'")))

(deftest without-a-space-the-response-is-exactly-what-it-was
  (let [st (lineage-fixture)
        m  (lineage-memory)
        {:keys [env file]} (embed-env {})
        ;; :score is dropped before comparing because it decays against
        ;; System/currentTimeMillis — two calls a millisecond apart differ in
        ;; the 8th decimal place and always did. Everything else, including the
        ;; ORDER of the facts, must match exactly.
        norm #(update % :facts (fn [fs] (mapv (fn [f] (dissoc f :score)) fs)))]
    (with-redefs [embed/env env embed/from-file file]
      (doseq [q [nil "chips"], space [nil ""]]
        (is (= (norm (srv/memory-payload m q)) (norm (srv/memory-request st m q space)))
            (str "q=" (pr-str q) " space=" (pr-str space) ": same map, key for key"))
        (is (= (keys (srv/memory-payload m q)) (keys (srv/memory-request st m q space))))
        (is (nil? (:scope (srv/memory-request st m q space)))
            "and no new key appears on the unscoped response"))))
  ;; and recall is still asked for exactly the opts it was asked for before
  (let [seen (atom [])]
    (srv/memory-request (sub/fresh-store) (recall-spy seen) "chips" nil)
    (is (= [["chips" {:k 20 :semantic? true}]] @seen)
        "no :filter key at all when nothing asked for a scope")))

(deftest lineage-sources-names-notebooks-and-their-cells
  (let [st (lineage-fixture)]
    (is (= #{"space:semis" "find:semis-1" "space:kid" "find:kid-1" "space:gk" "find:gk-1"}
           (srv/lineage-sources st "space:semis"))
        "the notebook and its cells, then the same for each descendant — a fact's :source names both")
    (is (= #{"space:peer" "find:semis-1"} (srv/lineage-sources st "space:peer")))))

(deftest a-merge-cycle-terminates
  ;; :merged-from is data, and nothing in the substrate forbids two notebooks
  ;; naming each other. The walk must stop rather than hang the request.
  (let [st (sub/fresh-store)]
    (put-space! st "space:a" "A" "doc:a" "a" {:merged-from ["space:b"]})
    (put-space! st "space:b" "B" "doc:b" "b" {:merged-from ["space:a"]})
    (is (= #{"space:a" "doc:a" "space:b" "doc:b"} (srv/lineage-sources st "space:a")))))

;; ---- the trunk: an ancestor's OWN ids, and never a re-descent from it ----

(deftest a-branch-sees-the-trunk-it-grew-out-of
  (let [st (trunk-fixture)]
    (is (= #{"space:a" "find:a-1"          ; itself
             "space:leaf" "find:leaf-1"    ; what it spawned
             "space:root" "find:root-1"}   ; and the trunk above it — NEW
           (srv/lineage-sources st "space:a"))
        "a branch sees its own work, the work below it, and its parent's own ids")))

(deftest the-upward-walk-never-turns-into-a-downward-one
  ;; THE leak this rule is easy to get wrong by. Reaching space:root and then
  ;; walking down from it lands on space:b, which is a different branch.
  ;; Asserted from the grandchild, the position a two-step mistake shows up in.
  (let [st (trunk-fixture)
        s  (srv/lineage-sources st "space:leaf")]
    (is (= #{"space:leaf" "find:leaf-1" "space:a" "find:a-1" "space:root" "find:root-1"} s)
        "the whole chain up to the root — parent AND grandparent — and nothing beside it")
    (is (not (contains? s "space:b")) "the sibling of its parent is a different branch")
    (is (not (contains? s "find:b-1")) "and so are that branch's documents")))

(deftest the-root-still-sees-everything-below-it
  ;; regression guard: the downward walk is untouched
  (let [st (trunk-fixture)]
    (is (= #{"space:root" "find:root-1" "space:a" "find:a-1"
             "space:leaf" "find:leaf-1" "space:b" "find:b-1"}
           (srv/lineage-sources st "space:root"))
        "from the trunk, every branch and every leaf, exactly as before")))

(deftest a-notebook-with-no-parent-is-scoped-exactly-as-it-was
  (let [st (lineage-fixture)]
    (is (= #{"space:semis" "find:semis-1" "space:kid" "find:kid-1" "space:gk" "find:gk-1"}
           (srv/lineage-sources st "space:semis"))
        "no :spawned-by → the upward walk contributes nothing at all")
    (is (= #{"space:other" "find:other-1"} (srv/lineage-sources st "space:other")))
    (is (= #{"space:peer" "find:semis-1"} (srv/lineage-sources st "space:peer")))))

(deftest a-spawned-by-cycle-terminates
  ;; :spawned-by is data like any other, and nothing in the substrate forbids a
  ;; cycle. The upward walk carries its own `seen` set for exactly this. The
  ;; TIMEOUT is the point of the test: without the bound this does not fail,
  ;; it hangs, and a hang in `deftest` wedges the whole suite.
  (let [st (sub/fresh-store)]
    (put-space! st "space:x" "X" "doc:x" "x" {:spawned-by {:space "space:y"}})
    (put-space! st "space:y" "Y" "doc:y" "y" {:spawned-by {:space "space:x"}})
    (let [f (future (srv/lineage-sources st "space:x"))
          r (deref f 5000 ::timed-out)]
      (future-cancel f)
      (is (= #{"space:x" "doc:x" "space:y" "doc:y"} r)
          "a cycle in :spawned-by terminates rather than hanging the request"))))

(deftest a-merge-is-not-an-ancestor
  ;; THE DECISION, 2026-08-08: the upward walk follows `:spawned-by` and NOT
  ;; `:merged-from`. See `lineage-sources`' docstring for the reasoning; this
  ;; test pins both halves of it.
  (let [st (sub/fresh-store)]
    (put-space! st "space:l" "L" "doc:l" "left" {})
    (put-space! st "space:r" "R" "doc:r" "right" {})
    ;; a real merge (`connect!`) copies BOTH sides' cells into the new notebook
    (sub/commit! st {:op :put :id "space:mix"
                     :value {:id "space:mix" :kind :space :title "Mix"
                             :value {:intent "both"
                                     :cells [{:ref "doc:l"} {:ref "doc:r"}]
                                     :merged-from ["space:l" "space:r"]}}})
    (put-space! st "space:child" "Child" "doc:child" "c" {:spawned-by {:space "space:mix"}})
    (is (= #{"space:l" "doc:l"} (srv/lineage-sources st "space:l"))
        "a merge is DOWNSTREAM of its inputs: from an input, the merge that absorbed it is not a trunk it grew out of, and its co-inputs are siblings by another name")
    (is (= #{"space:child" "doc:child" "space:mix" "doc:l" "doc:r"}
           (srv/lineage-sources st "space:child"))
        "a notebook spawned FROM a merge gets the merge's own ids — including the cells the merge copied in, so what was combined is not lost")
    (is (not (contains? (srv/lineage-sources st "space:child") "space:r"))
        "…but not the input NOTEBOOKS' ids: a fact recorded against space:r with no object (what `ask!` writes) is not on this branch's trunk")))

;; ============================================================================
;; what the AGENT is told it remembers — the same lineage scope, on ✦ Ask,
;; ✎ Draft, 🔍 Research and ✧ Suggest
;; ============================================================================

(defn- remembered
  "`remembered-context` with `m` standing in for the server's memory singleton.

   The stand-in goes in by rebinding the `mem/memory` DELAY — swapping the
   receiver — and never by redefining `mold/recall`. `recall` is a protocol
   method: the compiler turns the call into a cached callsite straight to the
   implementing class, so a `with-redefs` of it is silently ignored and the test
   passes green while observing nothing. `mem/memory` is an ordinary var deref,
   so rebinding it is seen, and the real `data/memory.edn` delay is never forced.

   No embedder is configured, so `:semantic? true` degrades to lexical and this
   opens no socket."
  [st m prompt space]
  (let [{:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file
                  mem/memory (delay m)]
      (#'srv/remembered-context st prompt space))))

(deftest the-agent-remembers-within-this-notebook-and-its-lineage
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    ;; a fact learned in an unrelated notebook is not offered here
    (let [ctx (remembered st m "chips" "space:other")]
      (is (str/includes? ctx "chips are unrelated here.")
          "what this notebook itself learned is still offered")
      (is (not (str/includes? ctx "chips are fabricated in Taiwan."))
          "a fact from an unrelated notebook must not reach the agent's instructions")
      (is (not (str/includes? ctx "chips need EUV lithography."))))
    ;; the hub is offered what its deep dives found, transitively
    (let [ctx (remembered st m "chips" "space:semis")]
      (is (str/includes? ctx "chips need EUV lithography.") "the child's finding")
      (is (str/includes? ctx "chips shrink with each node.") "and the child's child's")
      (is (not (str/includes? ctx "chips are unrelated here."))))))

(deftest the-agent-is-told-its-trunk-and-not-the-branches-beside-it
  ;; WHAT CHANGED, AND WHY. This test replaces
  ;; `the-scope-runs-DOWN-the-lineage-and-not-up`, which pinned the opposite:
  ;; from a child, the parent's facts were OUT of scope, and its comment
  ;; explained that one hop up the `spawned-by` edge reaches every sibling.
  ;;
  ;; That reasoning was right about the danger and wrong about the remedy. One
  ;; hop up is every sibling only if you then walk DOWN from the ancestor.
  ;; `lineage-sources` now walks up for each ancestor's OWN ids and never
  ;; re-descends, so the trunk is visible and the branches beside you are not.
  ;;
  ;; The user's decision, 2026-08-08: "I go on a research and then create a
  ;; branch — when I ask that branch it should have data to the root but not
  ;; siblings, since that's a different branch."
  ;;
  ;; The sibling assertion the old test made is still made, below, and is still
  ;; the one that matters — please do not "fix" this back to downward-only.
  (let [st  (trunk-fixture)
        m   (trunk-memory)
        ctx (remembered st m "chips" "space:a")]
    (is (str/includes? ctx "chips concentrate in Taiwan.") "its own")
    (is (str/includes? ctx "chips face a blockade risk.") "and its descendants'")
    (is (str/includes? ctx "chips are made in fabs.")
        "and the trunk it grew out of — NEW: this is what changed")
    (is (not (str/includes? ctx "chips need EUV from one vendor."))
        "but never the branch beside it, which is what re-descending from the trunk would deliver"))
  ;; and from the grandchild, where a two-step mistake would show
  (let [st  (trunk-fixture)
        m   (trunk-memory)
        ctx (remembered st m "chips" "space:leaf")]
    (is (str/includes? ctx "chips are made in fabs.") "the root, two hops up")
    (is (str/includes? ctx "chips concentrate in Taiwan.") "and its own parent")
    (is (not (str/includes? ctx "chips need EUV from one vendor."))
        "and still not its parent's sibling")))

(deftest a-brand-new-notebook-brings-no-remembered-context
  ;; a normal state, not an error: nothing has been learned here yet, so the
  ;; system prompt simply has no REMEMBERED block
  (let [st (lineage-fixture)
        m  (lineage-memory)]
    (sub/commit! st {:op :put :id "space:new"
                     :value {:id "space:new" :kind :space :title "New"
                             :value {:intent "just made" :cells []}}})
    (is (nil? (remembered st m "chips" "space:new"))
        "no facts in scope → nil, and the caller's `str` adds nothing")
    (is (seq (mem/all-facts m)) "…while memory itself is full of matching facts")))

(deftest with-no-notebook-at-all-memory-stays-global
  ;; `ask!` falls back to the whole workspace for its DOCUMENT context when
  ;; `space` is nil; memory matches that rule rather than inventing a second one
  (let [st (lineage-fixture)
        m  (lineage-memory)
        ctx (remembered st m "chips" nil)]
    (is (str/includes? ctx "chips are fabricated in Taiwan."))
    (is (str/includes? ctx "chips are unrelated here.")
        "with no notebook to scope to, an unrelated notebook's fact IS eligible")
    (is (str/includes? ctx "chips seen by a peer notebook."))))

(deftest the-remembered-block-still-carries-its-citations
  (let [st (lineage-fixture)
        m  (lineage-memory)
        ctx (remembered st m "chips" "space:kid")]
    (is (str/starts-with? ctx "\n\nREMEMBERED (distilled from earlier work"))
    (is (str/includes? ctx "chips need EUV lithography. (⌾ find:kid-1)")
        "the ⌾ citation is the object the fact came from, unchanged")
    (is (str/includes? ctx "chips shrink with each node. (⌾ find:gk-1)"))))

(deftest all-four-agent-flows-recall-inside-the-lineage
  ;; ✦ Ask, ✎ Draft, 🔍 Research, ✧ Suggest — every call site, one spy.
  ;; The model is stubbed everywhere; nothing here opens a socket.
  ;;
  ;; A FRESH store per flow: ✎ Draft and 🔍 Research each append a cell to the
  ;; notebook, which legitimately widens the next call's scope. Reusing one
  ;; store would compare each flow against a stale expectation.
  ;; The two empty sets are NOT noise. `recall-spy` answers `all-facts` with [],
  ;; so `subject-set` has no names to detect and no lineage facts to read, and
  ;; the opts show that honestly: this caller asked about subjects and found
  ;; none. An absent key would mean something else — "this caller does not do
  ;; subjects at all", which is what /api/leap and the memory pane still say.
  (let [want {:k 6 :semantic? true :subjects #{}
              :filter {:sources (srv/lineage-sources (lineage-fixture) "space:kid")
                       :entities #{}}}
        opts (fn [f]
               (let [st   (lineage-fixture)
                     seen (atom [])]
                 (with-redefs [mem/memory (delay (recall-spy seen))
                               loci.server/distill! (fn [& _] nil)
                               agent/chat-tools (fn [& _] "drafted")
                               agent/propose-subtopics (fn [& _] [])]
                   (f st))
                 (mapv second @seen)))]
    (is (= [want] (opts #(srv/ask! % "chips" "space:kid"))) "✦ Ask")
    (is (= [want] (opts #(srv/delegate! % "space:kid"))) "✎ Draft")
    (is (= [want] (opts #(srv/research! % "space:kid" "chips"))) "🔍 Research")
    (is (= [want] (opts #(srv/suggest! % "space:kid"))) "✧ Suggest")
    ;; and with no notebook, ✦ Ask asks for no filter at all
    (is (= [{:k 6 :semantic? true :subjects #{}}] (opts #(srv/ask! % "chips" nil)))
        "space nil → no :filter key, exactly as before this change")))

;; ============================================================================
;; the subject set — what a recall in this notebook is ABOUT, and not merely
;; where it stands (Task 3 of the entities plan)
;; ============================================================================

(defn- subject-fixture
  "Two unrelated roots and a brand-new notebook — the shape the subject union
   exists for:

     space:semis   a root that has been working on chips
       space:kid     spawned-by semis
     space:econ    a DIFFERENT root, sharing no ancestor with semis
     space:new     just created, holding nothing

   `space:econ` is the notebook `remembered-context`'s 2026-08-08 note called
   blind: no lineage reaches it from `space:semis` in either direction, so
   before this change nothing either one learned could ever reach the other.
   Modelled on the live corpus's `Serbia — open economic data` sitting beside
   the `Semiconductors` hub."
  []
  (let [st (sub/fresh-store)]
    (put-space! st "space:semis" "Semis" "find:semis-1" "chips fabs" {})
    (put-space! st "space:kid"   "Kid"   "find:kid-1"   "chips euv"
                {:spawned-by {:space "space:semis" :prompt "go deeper"}})
    (put-space! st "space:econ"  "Econ"  "find:econ-1"  "other work" {})
    (sub/commit! st {:op :put :id "space:new"
                     :value {:id "space:new" :kind :space :title "New"
                             :value {:intent "just made" :cells []}}})
    st))

(defn- subject-memory
  "Facts carrying real `:entities`, worded so each assertion below can pass for
   exactly one reason.

   Neither fact recalled BY SUBJECT here shares one token with the prompt that
   reaches it. That is the point of the wording: `relevance` is
   `0.6·keywords + 0.4·entities`, so a fact with a word in common would score on
   the keyword half alone and the assertion would stay green with every line of
   the subject machinery deleted. `lineage-memory` above cannot be reused for
   the same reason — its six facts all say \"chips\", which makes it a good
   fixture for a *filter* and a useless one for a *subject*.

   **The six findings in `space:semis` are the reason this fixture is twelve
   facts and not five.** They are perfectly ordinary notebook contents, they
   carry names nothing outside the lineage has ever used, and with fewer than
   `k`=6 candidates a test cannot see what they do: under a subject set that
   scores lineage-only names, all six take an identical 0.4000 and fill the
   whole block, pushing out both the fact the union exists to deliver and the
   notebook's own child's. A test with three candidates and six slots passes
   either way, which is certifying rather than testing.

   Their token counts are deliberately all different (8, 7, 5, 6, 9, 10) and
   none of them contains `of`, `is`, `long`, `how`, `wrath` or `righteous`, so
   each scores a distinct keyword jaccard against the game prompt and no
   assertion below depends on how a tie is broken.

   The Serbia fact is the negative control: it shares three of the game
   prompt's tokens (`the`, `long`, `is`) and would rank THIRD if it were ever a
   candidate, so its absence is a competitive exclusion and not an artefact of
   `k`. It is in neither the lineage nor the subject set.

   Written lineage-last so the six findings are the NEWEST facts: recency is the
   tie-break, and a fixture where the flattening's victims happen to be newer
   would hide it."
  []
  (let [m (mem/file-memory (tmpfile))]
    (doseq [[nb obj txt ents]
            [["space:econ"  "find:econ-1"  "A full playthrough takes roughly 120 hours." ["Wrath of the Righteous"]]
             ["space:econ"  "find:econ-1"  "Mythic paths branch at the third act."       ["Wrath of the Righteous" "Pathfinder"]]
             ["space:econ"  "find:econ-1"  "The long series is published quarterly."     ["Serbia"]]
             ["space:econ"  "find:econ-1"  "Fewer than sixty units ship a year."         ["ASML"]]
             ["space:kid"   "find:kid-1"   "One vendor ships every scanner."             ["EUV" "ASML"]]
             ["space:semis" "find:semis-1" "Fabrication concentrates on one island."     ["TSMC" "Taiwan"]]
             ;; the six ordinary findings — lineage-only names, all of them
             ["space:semis" "find:semis-1" "Output fell by two percent over the quarter."         ["output"]]
             ["space:semis" "find:semis-1" "Rainfall was unusually heavy through the summer."     ["rainfall"]]
             ["space:semis" "find:semis-1" "Yields improved after the retool."                    ["yield"]]
             ["space:semis" "find:semis-1" "Prices doubled through the spring quarter."           ["price"]]
             ["space:semis" "find:semis-1" "Export permits were renewed in the autumn without incident." ["export permits"]]
             ["space:semis" "find:semis-1" "Board members approved the new campus on its northern edge." ["campus"]]]]
      (mold/remember m txt {:entities ents :source {:obj obj :space nb}}))
    m))

(def ^:private game-prompt "how long is Wrath of the Righteous?")

(defn- lineage-only-findings
  "The six ordinary `space:semis` findings, by text. Named once so a test can
   say \"none of these\" without listing them and quietly listing five."
  []
  ["Output fell by two percent over the quarter."
   "Rainfall was unusually heavy through the summer."
   "Yields improved after the retool."
   "Prices doubled through the spring quarter."
   "Export permits were renewed in the autumn without incident."
   "Board members approved the new campus on its northern edge."])

(defn- remembered-lines
  "The fact lines of a REMEMBERED block, in order, bullet stripped. nil for no
   block at all, so \"nothing was recalled\" and \"an empty block was built\"
   stay different answers."
  [ctx]
  (when ctx
    (into [] (keep #(when (str/starts-with? % "- ") (subs % 2)))
          (str/split-lines ctx))))

(def ^:private semis-names
  "Every name the `space:semis` lineage carries — its own findings and its
   child's. All but `asml` are used nowhere else in the corpus.

   Kept as a NEGATIVE: after 2026-08-09 none of these may reach the subject set
   unless the prompt names it, so this is the set the assertions below check is
   absent, not present."
  #{"tsmc" "taiwan" "euv" "asml"
    "output" "rainfall" "yield" "price" "export permits" "campus"})

(deftest the-subject-set-is-what-the-prompt-NAMES-and-never-what-the-notebook-holds
  ;; WHAT CHANGED, AND WHY. This test replaces two —
  ;; `the-scope-set-takes-every-name-this-lineage-carries` and
  ;; `the-score-set-drops-the-names-only-this-lineage-has-ever-used` — which
  ;; between them pinned design §5's subject set: the prompt's names UNION the
  ;; names this lineage already carries, with the second half restricted to
  ;; names something outside the lineage also carries.
  ;;
  ;; That was built, shipped, and measured on the real corpus on 2026-08-09
  ;; (`docs/superpowers/specs/2026-08-08-entities-recall-measured.md`). It
  ;; degraded 7 of 12 real prompts — every single prompt typed into a notebook
  ;; that has a lineage. The restriction deletes exactly the names a notebook is
  ;; ABOUT, because those live in its family and nowhere else, so a
  ;; semiconductor question got a subject set made of planets. The user's
  ;; decision: drive the union from the prompt alone.
  ;;
  ;; So `subject-set` no longer takes `sources` at all — there is nothing left
  ;; for it to do with them — and the two sets that used to differ are one.
  ;; Please do not "restore" §5 here; it is the thing that was measured wrong.
  (let [m (subject-memory)]
    (is (= #{"wrath of the righteous"} (srv/subject-set m game-prompt))
        "a multi-word name the corpus knows, detected in the prompt and normalized")
    (is (= #{} (srv/subject-set m "how long is this going to take"))
        "a prompt naming nothing is no subject at all — and the whole feature is then inert")
    (is (= #{} (srv/subject-set m "chips"))
        "`chips` is a word this corpus never made a NAME of, so it names nothing — which is the case the old lineage half existed to rescue and the measurement said not to")))

(deftest no-name-the-notebook-merely-holds-reaches-the-subject-set
  ;; The pin that goes red if the lineage half is ever put back. `space:semis`
  ;; carries ten names across its own findings and its child's; a prompt that
  ;; names none of them must produce none of them, however much the notebook has
  ;; been working on them.
  (let [m (subject-memory)]
    (is (not-any? (srv/subject-set m "chips") semis-names)
        "not one of the notebook's ten names, because the question named none of them")
    (is (not-any? (srv/subject-set m game-prompt) semis-names)
        "and naming a DIFFERENT subject does not drag them in either")
    (is (= #{"asml"} (srv/subject-set m "how many ASML units ship in a year?"))
        "…while a prompt that DOES name one gets exactly that one — the union is driven by the question")))

(deftest the-subject-set-does-not-depend-on-where-you-are-standing
  ;; It reads `all-facts` for the corpus's known names and the prompt for which
  ;; of them was asked about. No notebook, no lineage, no scope enters it — which
  ;; is why it takes a memory and a prompt and nothing else, and why the same
  ;; question asked anywhere gets the same subjects.
  (let [m (subject-memory)]
    (is (= (srv/subject-set m game-prompt) (srv/subject-set m (str "  " game-prompt "  ")))
        "normalization is `ent/detect`'s and the answer does not drift with whitespace")
    (is (= #{"wrath of the righteous"} (srv/subject-set m "WRATH OF THE RIGHTEOUS, how long?"))
        "case and punctuation are not part of a name")))

(defn- recall-spy-over
  "A `recall-spy` that still answers `all-facts` from a real memory `m`.

   `subject-set` reads `all-facts` off the very memory `remembered-context` then
   recalls from, so the plain `recall-spy` — which answers `[]` — would build an
   empty subject set no matter what the code under test does, and an assertion
   on those opts would certify rather than test.

   And the spy has to be a `reify`, not a `with-redefs` of `mold/recall` as the
   plan's Step 1 suggested: `recall` is a protocol method, the compiler turns
   the call into a cached callsite straight to the implementing class, and the
   rebinding is silently ignored. See `recall-spy` above, which says the same
   thing and has said it since the memory pane was written."
  [m seen]
  (reify mold/Recall
    (remember  [_ f o] (mold/remember m f o))
    (all-facts [_] (mold/all-facts m))
    (recall    [_ q opts] (swap! seen conj [q opts]) [])))

(defn- recall-opts
  "The opts `remembered-context` asks recall for, with `m` standing in for the
   server's memory singleton."
  [st m prompt space]
  (let [seen (atom [])]
    (with-redefs [mem/memory (delay (recall-spy-over m seen))]
      (#'srv/remembered-context st prompt space))
    (second (first @seen))))

(deftest a-scoped-recall-asks-for-the-lineage-AND-the-subject
  (let [st (subject-fixture)
        m  (subject-memory)]
    ;; from a brand-new notebook: nothing in the lineage, one name in the prompt
    (is (= {:k 6 :semantic? true
            :subjects #{"wrath of the righteous"}
            :filter {:sources #{"space:new"} :entities #{"wrath of the righteous"}}}
           (recall-opts st m game-prompt "space:new"))
        "BOTH keys: :filter to make the other root's fact eligible, :subjects to make it rank — naming it in only one of the two is the quiet failure `recall`'s own comment warns about")))

(deftest one-subject-set-does-both-jobs-and-carries-no-lineage-name
  ;; WHAT CHANGED. This replaces `the-two-subject-sets-reach-recall-as-two-
  ;; different-values`, which asserted that `:filter {:entities}` and
  ;; `:subjects` receive DIFFERENT sets — the scope being §5's prompt-∪-lineage
  ;; union and the score being that union restricted. The 2026-08-09 measurement
  ;; removed the lineage half from both, so there is nothing left for them to
  ;; differ about and a distinction that no longer distinguishes is worse than
  ;; none. What still matters, and is asserted here, is that BOTH keys are sent:
  ;; `:filter` to make another root's fact eligible, `:subjects` to make it rank.
  (let [st   (subject-fixture)
        m    (subject-memory)
        opts (recall-opts st m game-prompt "space:semis")]
    (is (= {:k 6 :semantic? true
            :subjects #{"wrath of the righteous"}
            :filter {:sources (srv/lineage-sources st "space:semis")
                     :entities #{"wrath of the righteous"}}}
           opts)
        "the prompt's one name, in both places, beside the unchanged lineage filter")
    (is (= (:subjects opts) (:entities (:filter opts)))
        "one value, two jobs — naming a subject in only one of them is the quiet failure `recall`'s opts comment describes")
    (is (not-any? (:subjects opts) semis-names)
        "and not one of the ten names this notebook has been working on, because the question named none of them")))

(deftest with-no-notebook-recall-still-asks-for-no-filter-at-all
  (let [st (subject-fixture)
        m  (subject-memory)]
    (is (= {:k 6 :semantic? true :subjects #{"wrath of the righteous"}}
           (recall-opts st m game-prompt nil))
        "no :filter key — the whole memory is the candidate set, exactly as before this change")))

(deftest asking-about-a-subject-anywhere-brings-the-subjects-facts-back
  ;; THE REPORTED CASE, and the paragraph `remembered-context` used to accept as
  ;; a cost: `space:new` shares no lineage with `space:econ` and never will.
  ;;
  ;; Asserted as the WHOLE block, in order, rather than as three `includes?`
  ;; calls: what matters here is not only that the game's facts arrive but that
  ;; nothing else does, and a membership test cannot say that.
  (let [st (subject-fixture)
        m  (subject-memory)]
    (is (= ["A full playthrough takes roughly 120 hours. (⌾ find:econ-1)"
            "Mythic paths branch at the third act. (⌾ find:econ-1)"]
           (remembered-lines (remembered st m game-prompt "space:new")))
        "both of the other root's facts about the game, best first, cited as always — and NOTHING else out of twelve, in particular not `The long series is published quarterly.`, which shares three of the prompt's words and would rank second if the subject union had opened the door instead of widening it")))

(deftest a-notebook-no-longer-inherits-its-lineages-SUBJECTS
  ;; WHAT CHANGED, AND WHY — read this one before changing anything here.
  ;;
  ;; This test used to be called `a-notebook-that-has-been-working-on-something-
  ;; keeps-reaching-it`, and it asserted the opposite of what it asserts now:
  ;; asking `chips` in `space:semis` — a prompt naming no entity at all — pulled
  ;; `space:econ`'s ASML fact across, because `asml` was among the names semis'
  ;; own facts already carried. That was design §5's second half working as
  ;; specified.
  ;;
  ;; Measured on the real corpus on 2026-08-09, that half degraded 7 of 12 real
  ;; prompts — every prompt typed into a notebook with a lineage — and the user
  ;; decided to drop it. `space:semis` asked about chokepoints was returning six
  ;; facts about Jupiter and Saturn.
  ;;
  ;; So the rule now: **the question widens the scope, the notebook's habits
  ;; never do.** A prompt that names nothing gets nothing from outside its
  ;; lineage, and gets from inside it only what the words actually match — which
  ;; here is nothing, so there is no block at all.
  ;;
  ;; The six ordinary findings still earn their place in the fixture: they are in
  ;; the lineage and therefore candidates, and if a lineage name ever leaks back
  ;; into the subject set they will score 0.4000 apiece and this returns a block
  ;; of six instead of nil.
  (let [st (subject-fixture)
        m  (subject-memory)]
    (is (nil? (remembered st m "chips" "space:semis"))
        "no name in the prompt, no word in common with anything in scope → no REMEMBERED block, exactly as before entities existed")
    (is (= ["Fewer than sixty units ship a year. (⌾ find:econ-1)"        ; 0.6182 — named subject AND four words
            "One vendor ships every scanner. (⌾ find:kid-1)"             ; 0.1333 — its own child, on `asml` alone
            "Export permits were renewed in the autumn without incident. (⌾ find:semis-1)"] ; 0.0375 — on the word `in`
           (remembered-lines (remembered st m "how many ASML units ship in a year?" "space:semis")))
        "…and the moment the QUESTION names the subject, the other root's fact about it comes across and leads — which is the feature, driven by the prompt rather than by the notebook")))

(deftest a-prompt-naming-nothing-recalls-exactly-what-it-did-before-entities
  ;; `subject-set` claims in its docstring that when the prompt names no name the
  ;; corpus knows, the whole feature is INERT — `:subjects` empty leaves `qs` as
  ;; the query tokens, `:filter {:entities}` empty admits nothing, and the block
  ;; is character for character what df26e2b produced. That is a claim about
  ;; every prompt that does not happen to name a subject, which after
  ;; 2026-08-09 is most of them, so it is pinned rather than asserted in prose.
  ;;
  ;; The comparison is against the OLD opts, built here exactly as df26e2b's
  ;; `remembered-context` built them: `{:k 6 :semantic? true :filter {:sources …}}`
  ;; and no subject keys at all.
  (let [st (subject-fixture)
        m  (subject-memory)
        q  "yields improved through the quarter"
        {:keys [env file]} (embed-env {})]
    (with-redefs [embed/env env embed/from-file file mem/memory (delay m)]
      (is (= #{} (srv/subject-set m q))
          "the premise: `yield` is a name this corpus knows and `yields` is not one — `ent/detect` will not match inside a longer word")
      (is (= (mapv :fact (mold/recall m q {:k 6 :semantic? true
                                           :filter {:sources (srv/lineage-sources st "space:semis")}}))
             (mapv #(str/replace % #" \(⌾ [^)]*\)$" "")
                   (remembered-lines (#'srv/remembered-context st q "space:semis"))))
          "six facts, same order, same texts — the entity work costs nothing on a prompt it has nothing to say about"))))

(deftest a-fact-wholly-about-the-subject-outranks-one-only-partly-about-it
  ;; RECORDED, NOT FIXED. `relevance`'s entity half is a within-fact ratio, so it
  ;; asks "how much of this fact is about what you asked": the fact whose only
  ;; name is `wrath of the righteous` scores 0.4000, and the one that also names
  ;; `pathfinder` scores 0.1795 — for the same matched name.
  ;;
  ;; Task 12 measured this saturating on the real corpus: 38 of 39 top-6 slots
  ;; scored ent = 1.000 against a keyword half worth 0.004–0.114, up to 100:1, so
  ;; the entity term decides membership rather than order. The weighting is
  ;; deliberately unchanged — this test is the pin to argue with if that is ever
  ;; revisited, not a thing to quietly update.
  ;;
  ;; This used to be `a-remote-fact-wholly-on-subject-outranks-a-local-one-partly-
  ;; on-it`, phrased as local-versus-remote. That framing died with the lineage
  ;; half — both facts here are remote — but the ratio it was really about did
  ;; not, so the test is rewritten rather than dropped.
  (let [st (subject-fixture)
        m  (subject-memory)]
    (is (= ["A full playthrough takes roughly 120 hours. (⌾ find:econ-1)"
            "Mythic paths branch at the third act. (⌾ find:econ-1)"]
           (remembered-lines (remembered st m game-prompt "space:new")))
        "the fact that is wholly about the named subject leads the one that is half about it")))

(deftest a-fact-overlapping-on-neither-half-stays-out-of-a-FULL-block
  ;; §8 asked for this and nothing pinned it: a fact in neither the lineage nor
  ;; the subject set must stay out. Asserted with the block SATURATED — eight
  ;; facts score above zero and `k` is 6 — so the exclusion is competitive and
  ;; not an artefact of there being room for everything.
  ;;
  ;; `The long series is published quarterly.` shares `the`, `long` and `is`
  ;; with the prompt and would score 0.1800, third of the block, displacing
  ;; `Output fell by two percent over the quarter.` at 0.0429. It is excluded
  ;; because its only name is `serbia`, which the prompt does not name.
  (let [st    (subject-fixture)
        m     (subject-memory)
        lines (remembered-lines (remembered st m game-prompt "space:semis"))]
    (is (= 6 (count lines)) "the block is full — `k` is not hiding anything")
    (is (= ["A full playthrough takes roughly 120 hours. (⌾ find:econ-1)"     ; 0.4000  named subject
            "Mythic paths branch at the third act. (⌾ find:econ-1)"           ; 0.1795  named subject
            "Yields improved after the retool. (⌾ find:semis-1)"              ; 0.0545  lineage, one word
            "Prices doubled through the spring quarter. (⌾ find:semis-1)"     ; 0.0500  lineage, one word
            "Rainfall was unusually heavy through the summer. (⌾ find:semis-1)" ; 0.0462
            "Output fell by two percent over the quarter. (⌾ find:semis-1)"]  ; 0.0429
           lines)
        "the two facts the prompt's subject reached, then this notebook's own findings on the words alone")
    (is (not-any? #(str/includes? % "The long series is published quarterly.") lines)
        "the fact with no overlap on EITHER half is out, though it would have ranked third of six")
    (is (not-any? #(str/includes? % "Fewer than sixty units ship a year.") lines)
        "and so is the ASML fact this lineage used to inherit — `asml` is a name the notebook holds, not one the question asked")))

(deftest an-empty-notebook-with-a-subjectless-prompt-still-brings-nothing
  ;; unchanged, and the state a brand-new notebook is normally in
  (let [st (subject-fixture)
        m  (subject-memory)]
    (is (nil? (remembered st m "how long is this going to take" "space:new"))
        "no lineage facts and no name in the prompt → no candidates, no REMEMBERED block")
    (is (= 12 (count (mem/all-facts m))) "…while memory itself is full")))

;; ============================================================================
;; the entity page — ONE payload, served to the modal and to the object page
;; ============================================================================

(def ^:private entity-rows
  "The corpus behind the entity-page tests: `[text entities source]`, in the
   order it is written.

   **The write order is neither the ranked order nor its reverse**, and that is
   the point of it. Ranked by `recall`, the four `TSMC` facts come back
   F1 F2 F3 F4; written, they land F3 F1 F4 F2, so `:ts` ascending gives
   F3 F1 F4 F2 and `:ts` descending gives F2 F4 F1 F3. A fixture written in
   ranked order would pass with the ranking replaced by the log.

   The scores are arranged, not observed. `relevance` is
   `0.6·jaccard(query tokens, fact tokens) + 0.4·(inverse-frequency entity
   overlap)`, the query here is the name itself, and every name's document
   frequency is fixed by this table — tsmc 4, taiwan 3, asml 2, arizona 1 — so
   for `?name=tsmc`:

     F1  0.5200   says `tsmc` in five words, and carries only `tsmc`
     F2  0.4000   carries only `tsmc`, and shares no word with the query
     F3  0.1714   carries `tsmc` and `taiwan`, so the name is diluted
     F4  0.0480   carries four names, three of them rarer than `tsmc`

   Four scores, each separated from the next in the first or second decimal
   place, against a `decay` spread of about two parts in a million — see
   `stamp-ts!`. No assertion on this name depends on how a tie breaks, because
   there are no ties.

   `doc:gone` is a source with **no object behind it**: a fact outlives the
   document it was distilled from, and the page must drop the id rather than
   render a chip reading `null`.

   The last two facts carry a MULTI-WORD name and share no word with it. Query
   tokens are single words, so `taiwan semiconductor manufacturing` is three
   tokens to the scorer and one name to the index: without `:subjects` both
   score exactly 0 on both halves and `recall` drops them for not being
   positive. That is the case `:subjects` exists for, and this is where it is
   pinned."
  [;; F3 — mem-1
   ["a second advanced packaging site opens next spring"
    ["TSMC" "Taiwan"] {:obj "doc:gone" :space "space:semis"}]
   ;; F1 — mem-2
   ["tsmc leads at three nanometres"
    ["TSMC"] {:obj "doc:fab" :space "space:semis"}]
   ;; F4 — mem-3
   ["the arizona plant hires from four local colleges"
    ["TSMC" "Taiwan" "ASML" "Arizona"] {:obj "doc:az" :space "space:az"}]
   ;; F2 — mem-4
   ["the foundry raised capital spending for the coming year"
    ["TSMC"] {:obj "doc:fab" :space "space:semis"}]
   ;; the rest of the corpus, which exists to fix the document frequencies
   ["the island exports more than it imports each quarter"
    ["Taiwan"] {:obj "doc:fab" :space "space:semis"}]
   ["one vendor ships every extreme ultraviolet scanner"
    ["ASML"] {:obj "doc:fab" :space "space:semis"}]
   ;; the multi-word pair — G3 then G4, so G4 is the newer of the two
   ["the board approved a new packaging campus on its northern edge"
    ["Taiwan Semiconductor Manufacturing"] {:obj "doc:az" :space "space:az"}]
   ["wafer starts held steady through the winter months"
    ["Taiwan Semiconductor Manufacturing"] {:obj "doc:az" :space "space:az"}]])

(defn- stamp-ts!
  "Give every fact a distinct timestamp, one second apart, in the order it was
   written.

   `remember` stamps `System/currentTimeMillis`, and a fixture written in a
   `doseq` lands several facts in the SAME millisecond. `sort-by` is stable, so
   sorting a same-`:ts` corpus by `:ts` is a **no-op** — and the sabotage this
   fixture exists to fail, ranking the page by recency instead of by score,
   would leave the order untouched and the test would pass with the bug in
   place. A test that cannot fail is worse than no test.

   One second apart and a minute ago, so `decay` moves the scores by about two
   parts in a million: enough to break a tie deterministically (the multi-word
   pair below ties exactly on relevance, and the newer must lead), far too
   little to reorder facts whose relevances differ in the second decimal place."
  [m]
  (let [base (- (System/currentTimeMillis) 60000)]
    (swap! (:!facts m)
           (fn [fs]
             (reduce-kv (fn [acc id f]
                          (assoc acc id (assoc f :ts (+ base (* 1000 (parse-long (subs id 4)))))))
                        {} fs))))
  m)

(defn- entity-memory []
  (let [m (mem/file-memory (tmpfile))]
    (doseq [[txt ents src] entity-rows]
      (mold/remember m txt {:entities ents :source src}))
    (stamp-ts! m)))

(defn- entity-fixture
  "The objects the facts above were recorded against — minus `doc:gone`, which
   is the point of it."
  []
  (let [st (sub/fresh-store)]
    (doseq [o [{:id "doc:fab" :kind :doc :title "Fab notes" :value "…"}
               {:id "doc:az" :kind :doc :title "Arizona plans" :value "…"}
               {:id "space:semis" :kind :space :title "Semiconductors"
                :value {:intent "the supply chain" :cells []}}
               {:id "space:az" :kind :space :title "Arizona build"
                :value {:intent "the fab" :cells []}}]]
      (sub/commit! st {:op :put :id (:id o) :value o}))
    st))

(defn- materialize!
  "Hand-commit the object Task 5 will commit: `:kind :entity`, the NORMALIZED
   name under `[:value :name]`, and a title that is deliberately not the name —
   matching on the title would make every one of these lookups miss."
  [st id nm title]
  (sub/commit! st {:op :put :id id
                   :value {:id id :kind :entity :title title :value {:name nm}}})
  st)

(def ^:private tsmc-ranked
  "The four `tsmc` facts in the order `entity-payload` must return them, best
   first. See `entity-rows` for the arithmetic."
  ["tsmc leads at three nanometres"                        ; 0.5200
   "the foundry raised capital spending for the coming year" ; 0.4000
   "a second advanced packaging site opens next spring"     ; 0.1714
   "the arizona plant hires from four local colleges"])     ; 0.0480

(deftest an-entity-page-is-its-facts-ranked-and-not-its-facts-logged
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (srv/entity-payload st m "tsmc")]
    (is (= "tsmc" (:name p)))
    (is (= 4 (:count p)) "every live fact carrying the name, from the index")
    (is (true? (:promoted p)) "four facts is well past the threshold")
    (is (nil? (:object p)) "and nothing has been materialized")
    (is (= tsmc-ranked (mapv :fact (:facts p)))
        "RANKED, not logged — written F3 F1 F4 F2, so neither :ts order is this")
    ;; the claim above is only worth something if the two orders really differ
    (is (not= tsmc-ranked (mapv :fact (sort-by :ts > (:facts p))))
        "newest-first is a different order…")
    (is (not= tsmc-ranked (mapv :fact (sort-by :ts (:facts p))))
        "…and so is oldest-first")))

(deftest an-entity-page-names-where-its-facts-came-from
  ;; Documents AND notebooks in one list: a fact's `:source` names both (see
  ;; `mem/source-ids`), and the page is asking "where did this come from",
  ;; which either answers. Design §3: "the notebooks and documents those facts
  ;; came from."
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (srv/entity-payload st m "tsmc")]
    (is (= [{:id "doc:fab" :title "Fab notes" :kind "doc"}
            {:id "space:semis" :title "Semiconductors" :kind "space"}
            {:id "doc:az" :title "Arizona plans" :kind "doc"}
            {:id "space:az" :title "Arizona build" :kind "space"}]
           (:sources p))
        "in the FACTS' rank order, deduped, and with `doc:gone` — a source whose object is gone — dropped rather than rendered as a chip with no title")
    (is (not-any? #(nil? (:title %)) (:sources p)))))

(deftest a-source-that-is-a-HUSK-and-not-an-object-is-dropped-not-drawn
  ;; `apply-event` :assoc/:append against an id with no prior :put writes under
  ;; `[:objects id]` anyway, so the store answers with `{:note "x"}` — an entry
  ;; that is present but is not an object. It fails both of `:sources`' rules at
  ;; once: `(name (:kind o))` on it is an NPE, which is a 500 on a page load,
  ;; and a husk that survived would be drawn as a chip with no name on it.
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "doc:real"
                     :value {:id "doc:real" :kind :doc :title "A real document" :value "…"}})
    (sub/commit! st {:op :assoc :id "doc:husk" :path [:note] :value "x"})
    (is (= {:note "x"} (sub/object st "doc:husk"))
        "the husk is real — this is what the substrate actually holds, not a hypothetical")
    ;; word-disjoint, or `remember` reinforces the second into the first at a
    ;; 0.83 jaccard and there is only one fact to find sources for
    (mold/remember m "zebra lantern pivot marsh" {:entities ["husk"] :source {:obj "doc:husk"}})
    (mold/remember m "walnut cradle sundial thicket" {:entities ["husk"] :source {:obj "doc:real"}})
    (let [p (srv/entity-payload st m "husk")]
      (is (= 2 (:count p)) "both facts are on the page")
      (is (= [{:id "doc:real" :title "A real document" :kind "doc"}] (:sources p))
          "…and only the source that is an object is named"))))

(deftest one-fact-is-not-a-place-yet-but-the-page-still-renders
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (srv/entity-payload st m "arizona")]
    (is (= 1 (:count p)))
    (is (false? (:promoted p)) "one live fact is below the threshold")
    (is (= ["the arizona plant hires from four local colleges"] (mapv :fact (:facts p)))
        "…and the page renders anyway: `:promoted` false with facts present is a real state, not a reason to vanish")))

(deftest a-name-nothing-was-ever-recorded-about-is-empty-and-not-an-error
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (srv/entity-payload st m "graphene")]
    (is (nil? (:error p)) "asking about a subject with no facts is a normal question")
    (is (= "graphene" (:name p)))
    (is (= 0 (:count p)))
    (is (= [] (:facts p)))
    (is (= [] (:sources p)))
    (is (false? (:promoted p)))))

(deftest no-name-at-all-is-an-error-and-never-a-500
  (let [st (entity-fixture)
        m  (entity-memory)]
    ;; the last is a non-breaking space, spelled as an escape: `ent/normalize`
    ;; collapses `[\\s\\p{Z}]+` and not bare `\\s`, so this trims to "" too — and
    ;; a name that is only a U+00A0 is exactly as absent as one that is nothing
    (doseq [absent [nil "" "   " "\u00a0"]]
      (is (= {:error "no entity name given"} (srv/entity-payload st m absent))
          (str "…for " (pr-str absent))))))

(deftest a-multi-word-name-finds-its-own-facts
  ;; The case `:subjects` exists for. Query tokens are single words, so
  ;; `taiwan semiconductor manufacturing` can never match one; without the name
  ;; in `:subjects` both facts score 0 on the entity half, 0 on the keyword half
  ;; (neither text uses any of the three words), and `recall` drops them for not
  ;; being positive — an entity page with facts behind it that shows none.
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (srv/entity-payload st m "Taiwan Semiconductor Manufacturing")]
    (is (= "taiwan semiconductor manufacturing" (:name p)))
    (is (= 2 (:count p)))
    (is (true? (:promoted p)))
    (is (= ["wafer starts held steady through the winter months"
            "the board approved a new packaging campus on its northern edge"]
           (mapv :fact (:facts p)))
        "both of them, newer first — they tie exactly on relevance and `decay` breaks it")))

(defn- but-scores
  "A payload with the recall scores dropped.

   Two calls a millisecond apart cannot produce equal payloads and never could:
   `weight` multiplies relevance by `decay(ts, now)` with `now` read afresh
   inside each `recall`, so the scores differ in the eleventh decimal place —
   measured, `0.5199883642572858` against `0.5199883640566775`. What is under
   test is that two spellings of one name answer with the same PAGE: the same
   facts in the same order, the same counts, the same sources. Not that a float
   computed twice off two readings of the clock agrees with itself."
  [p]
  (update p :facts #(mapv (fn [f] (dissoc f :score)) %)))

(deftest a-name-is-normalized-at-the-door
  (let [st (entity-fixture)
        m  (entity-memory)
        p  (but-scores (srv/entity-payload st m "tsmc"))
        pay (fn [n] (but-scores (srv/entity-payload st m n)))]
    (is (= p (pay "TSMC")))
    (is (= p (pay "  tsmc  ")))
    ;; spelled as an escape rather than pasted, so it is readable as one: a
    ;; literal U+00A0 in the source looks exactly like a space and the next edit
    ;; through this file would "tidy" it into one without noticing
    (is (= p (pay "\u00a0TSMC\u00a0"))
        "…including a non-breaking space, which is what a pasted PDF hands you and what a bare \\s would never have stripped")))

(deftest the-entity-route-answers-the-same-payload-through-the-URL
  ;; The route is otherwise untested, and Task 8 finds a missing one only at
  ;; runtime. `c/store` and `mem/memory` are both delays behind ordinary vars,
  ;; so rebinding them means neither the user's Datalevin store nor
  ;; `data/memory.edn` is ever opened.
  (let [st  (entity-fixture)
        m   (entity-memory)
        get! (fn [qs]
               (with-redefs [c/store (delay st) mem/memory (delay m)]
                 (json/read-str (:body (srv/handler {:uri "/api/entity" :query-string qs}))
                                :key-fn keyword)))]
    (is (= 4 (:count (get! "name=tsmc"))))
    (is (= tsmc-ranked (mapv :fact (:facts (get! "name=tsmc"))))
        "the route serves the same ranked page the modal and the object page get")
    (is (= (but-scores (get! "name=tsmc"))
           (but-scores (get! "name=TSMC"))
           (but-scores (get! "name=%20tsmc%20")))
        "?name=TSMC and ?name=%20tsmc%20 answer identically — %20 is `parse-query`'s to decode and the surrounding space is `ent/normalize`'s to trim")
    (is (= "no entity name given" (:error (get! nil))))
    (is (= "no entity name given" (:error (get! "name="))))))

(deftest the-entity-route-freezes-at-the-point-it-was-asked-for
  ;; `/api/mold` and `/api/object/…` have always gone through `store-at`; this one
  ;; did not, and the shell's short-circuit made that reachable. Measured in a
  ;; browser: materialize `tsmc`, scrub to one event before the materialization,
  ;; click the subject chip. The route read the LIVE store, answered
  ;; `:object "ent:1"`, and the shell — which trusts that key completely — asked
  ;; `/api/object/ent:1?at=35` for an object that does not exist there. On screen: a
  ;; molder with an empty header and the single word `null`; scrubbed further back,
  ;; an uncaught TypeError and a blank world.
  ;;
  ;; The facts are today's on both pages, and that is not an oversight: the memory
  ;; has no time dimension and `undo!` never touches a fact, so there is no past set
  ;; to read. What `?at=` governs is everything the page reads out of the SUBSTRATE.
  (let [st   (entity-fixture)
        m    (entity-memory)
        n    (count (sub/history st))
        _    (srv/materialize-entity! st "tsmc")
        get! (fn [qs]
               (with-redefs [c/store (delay st) mem/memory (delay m)]
                 (json/read-str (:body (srv/handler {:uri "/api/entity" :query-string qs}))
                                :key-fn keyword)))
        now  (get! "name=tsmc")
        past (get! (str "name=tsmc&at=" n))]
    (is (some? (:object now))
        "live, the subject is a citizen — without this the assertion below is vacuous")
    (is (nil? (:object past))
        "frozen before the materialization it is a projection again, so the shell opens the modal instead of an object that is not there yet")
    (is (= (:count now) (:count past) 4)
        "the facts are today's on both pages: the memory has no time dimension")
    (is (true? (:promoted past))
        "…so the past page is a real page with real evidence on it, and not an empty one")))

(deftest a-materialized-entity-carries-its-object-and-never-demotes
  (let [st (-> (entity-fixture)
               (materialize! "ent:tsmc" "tsmc" "TSMC — the foundry")
               (materialize! "ent:az" "arizona" "The Arizona build"))
        m  (entity-memory)]
    (is (= "ent:tsmc" (:object (srv/entity-payload st m "tsmc")))
        "the page is now that object's page")
    (is (= 4 (:count (srv/entity-payload st m "tsmc"))) "and says exactly what it said before")
    (let [az (srv/entity-payload st m "arizona")]
      (is (= "ent:az" (:object az)))
      (is (true? (:promoted az))
          "ONE fact and promoted: a materialized entity never demotes — it is yours, not the evidence's"))
    (is (nil? (:object (srv/entity-payload st m "taiwan")))
        "…and an entity nobody materialized still has none")))

(deftest an-object-storing-a-RAW-name-is-still-that-entity
  ;; The one thing standing between an entity object that did NOT come from
  ;; Task 5 — hand-committed, migrated, imported, restored from an older log —
  ;; and a payload that contradicts itself. `ent/promoted` normalizes the names
  ;; it is handed; if `entity-objects` matched raw, the two halves of one answer
  ;; would disagree about the same entity: `:promoted` true, through `promoted`,
  ;; and `:object` absent, through the raw match. Nothing in the page would say
  ;; which half was lying.
  ;;
  ;; Pinned precisely BECAUSE Task 5 will always write a normalized name: "Task
  ;; 5 always writes normalized names" is the argument for deleting the
  ;; normalization, and the objects Task 5 did not write are the ones it does
  ;; not cover.
  (let [st (materialize! (entity-fixture) "ent:tsmc" "  TSMC  " "TSMC")
        m  (entity-memory)
        p  (srv/entity-payload st m "tsmc")]
    (is (= "ent:tsmc" (:object p))
        "a stored name that was never normalized still identifies the entity")
    (is (true? (:promoted p)) "…and both halves of the payload agree that it is materialized")
    (is (= "ent:tsmc" (:id (srv/entity-object st "tsmc"))))
    (is (= #{"tsmc"} (srv/materialized-names st))
        "reported normalized, which is the only form `ent/promoted` can compare")))

(deftest an-entity-is-identified-by-its-name-and-never-by-its-title
  ;; The title is a display string. An edit to it must not re-identify the
  ;; entity, or every fact that mentions the name is orphaned by a rename.
  (let [st (materialize! (entity-fixture) "ent:tsmc" "tsmc" "TSMC — the foundry")]
    (is (= "ent:tsmc" (:id (srv/entity-object st "tsmc"))))
    (is (nil? (srv/entity-object st "tsmc — the foundry")) "the title identifies nothing")
    (is (nil? (srv/entity-object st "graphene")))
    (is (= #{"tsmc"} (srv/materialized-names st)))
    (is (= #{} (srv/materialized-names (entity-fixture)))
        "a store with no entity objects in it names none")))

(defn- bulk-entity-memory
  "`n` facts all carrying one name and sharing no word with each other.

   Word-disjoint on purpose: `remember` reinforces an existing fact rather than
   adding one when their token sets overlap by 0.6 or more, so the obvious
   fixture — \"note one\", \"note two\" — would collapse into a handful of facts
   with high `:strength` and the cap would never be reached."
  [n]
  (let [m (mem/file-memory (tmpfile))]
    (doseq [i (range n)]
      (mold/remember m (str "alpha" i " beta" i " gamma" i " delta" i)
                     {:entities ["Bulk"] :source {:obj "doc:bulk" :space "space:bulk"}}))
    m))

(deftest the-page-is-capped-and-says-so-out-loud
  ;; `:count` and `(count :facts)` are two numbers on purpose, and this is the
  ;; only way they can differ: `:count` comes from the index and is every live
  ;; fact carrying the name, `:facts` is what `recall` returned at `:k 50`. With
  ;; one number the page would truncate in silence; with two it can say
  ;; "50 of 55" — which is what makes this a cap and not a lie.
  (let [st (sub/fresh-store)
        m  (bulk-entity-memory 55)
        p  (srv/entity-payload st m "bulk")]
    (is (= 55 (:count p)) "every live fact carrying the name")
    (is (= 50 (count (:facts p))) "…of which the page ships fifty")
    (is (< (count (:facts p)) (:count p))
        "the two numbers differ, and a renderer has everything it needs to say so")))

;; ============================================================================
;; materialization — one object, one notebook, one undo
;; ============================================================================

(defn- entity-objects-in
  "Every `:kind :entity` object in the store, by id. The store is the only place
   that can answer \"how many objects claim this name\", and that count is what
   separates \"materializing twice mints nothing\" from \"materializing twice
   happens to answer the same id\"."
  [st]
  (into {} (filter #(= :entity (:kind (val %)))) (sub/objects st)))

(deftest materializing-is-one-commit-and-one-undo
  (let [st  (entity-fixture)
        n0  (count (sub/history st))
        r   (srv/materialize-entity! st "tsmc")
        eid (:entity r)
        sid (:spaceId r)]
    (is (= (inc n0) (count (sub/history st)))
        "ONE commit, not two — `undo!` pops one, so two would leave a notebook about an entity that no longer exists")
    (is (= (inc n0) (:events (:state r)))
        "…and the payload's state is the state after the write, not before it")
    (is (= :entity (:kind (sub/object st eid))))
    (is (= :space (:kind (sub/object st sid))))
    (is (= #{eid} (nb/refs-of (sub/object st sid)))
        "the cell that puts one in the other")
    (sub/undo! st)
    (is (= n0 (count (sub/history st))) "one undo, and the log is where it started")
    (is (nil? (sub/object st eid)) "the entity object is gone…")
    (is (nil? (sub/object st sid)) "…and so is the notebook — one gesture, one undo")
    (is (empty? (entity-objects-in st)))))

(deftest materialization-touches-no-fact-and-no-fact-learns-the-object-id
  ;; THE INVARIANT. A fact references an entity by its normalized name and never
  ;; by an object id, which is the whole reason the model is derived: `undo!` of
  ;; a materialization returns the entity to being a projection with nothing left
  ;; dangling. The moment a fact stores `ent:1`, undo dangles it.
  ;;
  ;; `materialize-entity!` is not even handed the memory, so today the invariant
  ;; is enforced by the argument list. This asserts it anyway, because the
  ;; argument list is one refactor from changing and this is the property that
  ;; would silently stop holding.
  (let [st     (entity-fixture)
        m      (entity-memory)
        before (vec (mem/all-facts m))]
    (is (= 8 (count before)) "the fixture really has facts that could be rewritten")
    (is (= 4 (count (filter #(contains? (set (map ent/normalize (:entities %))) "tsmc") before)))
        "…four of them carrying the very name about to be materialized")
    (let [r      (srv/materialize-entity! st "tsmc")
          during (vec (mem/all-facts m))
          _      (sub/undo! st)
          after  (vec (mem/all-facts m))]
      (is (= before during) "materializing rewrote no fact")
      (is (= before after) "…and undoing it rewrote none back")
      (is (= (pr-str before) (pr-str during) (pr-str after))
          "byte-identical and not merely `=`: a fact that gained a key, or lost one, or had a value replaced by an equal-printing-differently one, compares unequal here and only here")
      (is (not (str/includes? (pr-str after) (:entity r)))
          (str "no fact carries " (:entity r) " — the id undo is about to make dangle"))
      (is (not (str/includes? (pr-str after) "ent:"))
          "…nor any other entity object id, under any key, at any depth"))))

(deftest materializing-twice-mints-nothing
  (let [st (entity-fixture)
        a  (srv/materialize-entity! st "TSMC")
        n1 (count (sub/history st))
        b  (srv/materialize-entity! st "  tsmc  ")]
    (is (= (:entity a) (:entity b)) "the object that is already there, not a second one")
    (is (= (:spaceId a) (:spaceId b)) "…and the notebook that is already about it")
    (is (= n1 (count (sub/history st))) "and not one event was written")
    (is (= 1 (count (entity-objects-in st)))
        "ONE object for one name — the page is reached BY NAME, and two objects would make `entity-object` a coin toss")))

(deftest the-object-carries-the-normalized-name-whatever-was-typed
  ;; Spelled with a non-breaking space, which is what a pasted PDF and a model's
  ;; JSON both hand you: `ent/normalize` collapses `[\s\p{Z}]+`, so this is one
  ;; name, and an object storing the raw string would be an entity no fact and no
  ;; chip could ever reach.
  (let [st (entity-fixture)
        r  (srv/materialize-entity! st "  TSMC\u00a0Arizona ")
        o  (sub/object st (:entity r))
        sp (sub/object st (:spaceId r))]
    (is (= "tsmc arizona" (get-in o [:value :name])) "the identity is the normalized name")
    (is (= "tsmc arizona" (:title o)))
    (is (= (:entity r) (:id o)) "and the object knows its own id")
    (is (= (:entity r) (:id (srv/entity-object st "tsmc arizona")))
        "…so the page reached by name finds it")
    (is (= "tsmc arizona" (:title sp)))
    (is (= "everything about tsmc arizona" (get-in sp [:value :intent])))
    (is (= #{"tsmc arizona"} (srv/materialized-names st)))))

(deftest a-blank-name-materializes-nothing
  (let [st (entity-fixture)
        n0 (count (sub/history st))]
    (doseq [absent [nil "" "   " "\u00a0"]]
      (is (= {:error "no entity name given"} (srv/materialize-entity! st absent))
          (str "…for " (pr-str absent)))
      (is (= n0 (count (sub/history st))) (str "and nothing was written for " (pr-str absent))))
    (is (empty? (entity-objects-in st)))))

(deftest a-materialized-entity-never-demotes-and-undo-returns-it-to-a-projection
  (let [st  (entity-fixture)
        m   (entity-memory)
        idx (ent/index (mem/all-facts m))
        up? (fn [n] (contains? (ent/promoted idx (srv/materialized-names st)) n))]
    (is (= 1 (count (get idx "arizona"))) "one fact — below the threshold on the evidence alone")
    (is (false? (up? "arizona")) "so on the evidence alone it is not a place")
    (srv/materialize-entity! st "Arizona")
    (is (= #{"arizona"} (srv/materialized-names st)) "reported normalized, the only form `promoted` can compare")
    (is (true? (up? "arizona")) "a materialized entity never demotes — it is yours, not the evidence's")
    (sub/undo! st)
    (is (= #{} (srv/materialized-names st)))
    (is (false? (up? "arizona")) "and undo returns it to being a projection: one fact, not a place")
    ;; the other half, and the one that says what "returns to a projection"
    ;; means: undo takes away the object, never the evidence. A name that was a
    ;; place before anyone materialized it is still one afterwards.
    (is (= 4 (count (get idx "tsmc"))))
    (is (true? (up? "tsmc")) "four facts — a place on its own evidence, before any object exists")
    (srv/materialize-entity! st "TSMC")
    (is (true? (up? "tsmc")))
    (sub/undo! st)
    (is (= #{} (srv/materialized-names st)) "the object is gone…")
    (is (true? (up? "tsmc")) "…and the projection still says yes, because the facts never moved")))

(deftest the-already-materialized-answer-is-honest-about-its-notebook
  (let [st  (entity-fixture)
        a   (srv/materialize-entity! st "tsmc")
        sid (:spaceId a)]
    (is (= sid (:spaceId (srv/materialize-entity! st "tsmc")))
        "the notebook that is about it, found by its cells and not by its id's shape")
    ;; a notebook is an ordinary object and its cells are ordinary edits — the
    ;; user can take the entity back out of the notebook they were just given
    (sub/commit! st (nb/set-cells-event sid []))
    (let [b (srv/materialize-entity! st "tsmc")]
      (is (= (:entity a) (:entity b)) "still that entity, and still nothing minted")
      (is (contains? b :spaceId) "the key is THERE…")
      (is (nil? (:spaceId b))
          "…and null: there is no notebook about this any more, and a shell told `null` opens the entity object instead of entering nothing"))))

(deftest the-notebook-answered-is-the-one-that-is-about-this-entity
  ;; Several notebooks holding one entity is not a race — it is what happens when
  ;; you drop a subject into somebody else's notebook — so one candidate is
  ;; plainly the better answer and determinism alone is not enough. Every id here
  ;; is one this codebase really mints: least id ALONE answers `space:about-1`
  ;; for an entity that notebook merely holds, and sends the user into asml's
  ;; notebook when they asked about tsmc.
  (let [st (entity-fixture)
        a  (srv/materialize-entity! st "asml")
        b  (srv/materialize-entity! st "tsmc")]
    (is (= ["space:about-1" "space:about-2"] [(:spaceId a) (:spaceId b)])
        "minted in that order, so the lower id is NOT tsmc's own — without which this test could not fail")
    (sub/commit! st (nb/append-cell-event st (:spaceId a) {:ref (:entity b)}))
    (is (= #{(:entity a) (:entity b)} (nb/refs-of (sub/object st (:spaceId a))))
        "asml's notebook now holds tsmc too — an ordinary cell, added by an ordinary gesture")
    (is (= (:spaceId b) (:spaceId (srv/materialize-entity! st "tsmc")))
        "the notebook that is ABOUT tsmc, not the lower-id one that merely holds it")
    (is (= (:spaceId a) (:spaceId (srv/materialize-entity! st "asml")))
        "…and asml's own is still asml's own")
    ;; The title is a display string. An edit to it must not re-identify the
    ;; entity — and it does not: it costs the preference and nothing else.
    (sub/commit! st {:op :assoc :id (:spaceId b) :path [:title] :value "TSMC — the foundry"})
    (is (= (:entity b) (:id (srv/entity-object st "tsmc")))
        "the entity is still reached by name, because identity is `[:value :name]` and never the title")
    (is (= (:spaceId a) (:spaceId (srv/materialize-entity! st "tsmc")))
        "…while the notebook answer falls back to least id: deterministic, and no longer the one you wanted")))

(deftest the-notebook-answered-does-not-depend-on-the-objects-maps-order
  ;; `vals` of an objects map is iteration order, not a rule, so `first` over it
  ;; is not an answer. Asking the same store twice cannot show this — it is the
  ;; same map in the same JVM and it iterates the same way every time — so the
  ;; two candidates are handed over in both orders and the answer must not move.
  (let [about {:id "space:about-2" :kind :space :title "tsmc"
               :value {:intent "everything about tsmc" :cells [{:ref "ent:2"}]}}
        holds {:id "space:about-1" :kind :space :title "asml"
               :value {:intent "everything about asml" :cells [{:ref "ent:1"} {:ref "ent:2"}]}}
        objm  (fn [os] (into {} (map (juxt :id identity)) os))
        pick  #'srv/about-notebook]
    (is (not= (mapv :id (vals (objm [about holds])))
              (mapv :id (vals (objm [holds about]))))
        "the two maps really do iterate differently — without this the two assertions below could not disagree, whatever the rule")
    (is (= "space:about-2" (pick (objm [about holds]) "ent:2" "tsmc")))
    (is (= "space:about-2" (pick (objm [holds about]) "ent:2" "tsmc"))
        "…and the same answer from the other order")
    ;; …and when the preference TIES — two notebooks titled for one entity, which
    ;; is what renaming a second notebook into the name produces — least id has
    ;; to decide. `sort-by` is stable, so with no tie-break the winner would be
    ;; whichever the map happened to iterate first, which is the whole defect.
    (let [twin {:id "space:new-1" :kind :space :title "tsmc"
                :value {:intent "also about tsmc" :cells [{:ref "ent:2"}]}}]
      (is (= "space:about-2" (pick (objm [about twin]) "ent:2" "tsmc")))
      (is (= "space:about-2" (pick (objm [twin about]) "ent:2" "tsmc"))
          "…decided by the tie-break and not by the iteration order"))))

(deftest concurrent-materializations-mint-distinct-ids
  ;; `next-id` reads the whole store's keys and mints from the highest suffix, so
  ;; two materializations that both read before either commits both mint `ent:1`
  ;; — and the second `:put` REPLACES the first. One object for two names, a user
  ;; taken to a notebook about somebody else's subject, and an entity that was
  ;; materialized and then silently was not. This is `nb/append-cell-event`'s
  ;; defect in a new place: 24 concurrent appends once left 6 cells.
  ;;
  ;; It is also what makes "materializing twice mints nothing" true rather than
  ;; nearly true: the check and the commit are a read-modify-write, and without
  ;; the lock two clicks on one name are a plain TOCTOU race.
  (let [st      (entity-fixture)
        n0      (count (sub/history st))
        names   (mapv #(str "subject " %) (range 8))
        latch   (java.util.concurrent.CountDownLatch. 1)
        results (atom [])
        ts      (mapv (fn [n]
                        (doto (Thread. #(do (.await latch)
                                            (swap! results conj (srv/materialize-entity! st n))))
                          (.start)))
                      names)]
    (.countDown latch)
    (doseq [t ts] (.join t))
    (let [ents (vals (entity-objects-in st))]
      (is (= 8 (count ents)) "eight names, eight objects — none replaced by another")
      (is (= (set names) (set (map #(get-in % [:value :name]) ents)))
          "…and each object carries its own name"))
    (is (= 8 (count (set (map :entity @results)))) "eight distinct object ids answered")
    (is (= 8 (count (set (map :spaceId @results)))) "eight distinct notebooks answered")
    (is (every? #(= :space (:kind (sub/object st (:spaceId %)))) @results)
        "and every notebook a caller was sent to is really there")
    (is (= (+ n0 8) (count (sub/history st)))
        "eight `:tx`s on top of the fixture — one commit each, and no thread wrote twice")))

(deftest the-materialize-route-answers-through-the-URL
  ;; The route is otherwise untested, and Task 8 finds a missing one only at
  ;; runtime. `c/store` is a delay behind an ordinary var, so rebinding it means
  ;; the user's Datalevin store is never opened.
  (let [st    (entity-fixture)
        n0    (count (sub/history st))
        post! (fn [body]
                (with-redefs [c/store (delay st)]
                  (json/read-str (:body (srv/handler
                                         {:uri "/api/entity-notebook" :request-method :post
                                          :body (java.io.StringReader. (json/write-str body))}))
                                 :key-fn keyword)))
        r     (post! {:name "  TSMC "})]
    (is (= "tsmc" (get-in (sub/object st (:entity r)) [:value :name])))
    (is (= :space (:kind (sub/object st (:spaceId r)))))
    (is (= (inc n0) (:events (:state r))) "the state comes back with the write in it")
    (let [again (post! {:name "tsmc"})]
      (is (= (:entity r) (:entity again)) "a second POST mints nothing")
      (is (= (:spaceId r) (:spaceId again))))
    (is (= "no entity name given" (:error (post! {}))) "a body with no name is a message, never a 500")
    (is (= "no entity name given" (:error (post! {:name "   "}))))))

;; ============================================================================
;; the entity object's page — one payload, two surfaces
;; ============================================================================

(deftest a-materialized-entity-molds-to-its-own-page
  ;; §4 of the design: once materialized, "the page is that object's page —
  ;; moldable, undoable, a citizen like any other". A `mold/mold` viewer cannot
  ;; be that page — a viewer's `:render` only ever receives `(:value o)`, which
  ;; here is `{:name "tsmc"}`, and has no way to reach the memory the facts live
  ;; in. So `mold-payload` branches on the kind and says "no viewers, the shell
  ;; renders this itself", exactly as it already does for `:flow`.
  (let [st  (entity-fixture)
        m   (entity-memory)
        eid (:entity (srv/materialize-entity! st "tsmc"))
        ;; a viewspec aimed at the entity, so `(alternatives st o)` would return
        ;; something. Without it the `:alternatives` assertion below cannot fail
        ;; — no built-in viewer's `pred` accepts `{:name "tsmc"}` and there are
        ;; no dynamic views, so `alternatives` and the literal `[]` agree even
        ;; when the branch hands back the wrong one.
        _   (sub/commit! st {:op :put :id "view:ent-1"
                             :value {:id "view:ent-1" :kind :viewspec :title "view: as a card"
                                     :value {:target eid :label "as a card" :spec {}}}})
        p   (srv/mold-payload st m eid nil)]
    (is (= eid (:id p)))
    (is (= "entity" (:kind p)) "its own branch, and not whatever `mold/mold` makes of `{:name \"tsmc\"}`")
    (is (nil? (:view p)))
    (is (= [] (:alternatives p))
        "and nothing to view it as, even with a view-spec aimed at it: a subject has one page")
    (let [r (:rendered p)]
      (is (= "tsmc" (:name r)))
      (is (= 4 (:count r)) "every live fact carrying the name")
      (is (= tsmc-ranked (mapv :fact (:facts r))) "ranked, and ranked exactly as the modal ranks them")
      (is (= #{"doc:fab" "doc:az" "space:semis" "space:az"} (set (map :id (:sources r))))
          "the objects and notebooks the facts came from — `doc:gone` dropped, as it has no object")
      (is (= eid (:object r))
          "…and the page carries the id of the object whose page it is, which is what tells the shell not to offer materialization again"))))

(deftest the-molder-header-is-the-objects-title-and-never-the-entitys-name
  ;; `:title` is what `renderDetail` draws above the molded body, and Task 8 is
  ;; about to depend on it. Nothing pinned it: `materialize-entity!` titles the
  ;; object WITH the name, so on everything it mints `(:title o)` and `(:name p)`
  ;; are the same string and the assertion could not tell a correct branch from
  ;; one reading the payload instead of the object.
  ;;
  ;; So this uses the hand-committed shape `materialize!` exists for — an object
  ;; whose title diverges from its name, which is what a rename produces and what
  ;; a migrated or restored object may always have carried. A rename must be able
  ;; to change the header without re-identifying the entity; that is the whole
  ;; reason `entity-objects` matches on the name and never on the title.
  (let [st (materialize! (entity-fixture) "ent:az" "arizona" "The Arizona build")
        m  (entity-memory)
        p  (srv/mold-payload st m "ent:az" nil)]
    (is (= "The Arizona build" (:title p))
        "the header is the OBJECT's title")
    (is (= "arizona" (:name (:rendered p)))
        "…while the page underneath is still keyed on the name, and the two are different strings here on purpose")
    (is (= "ent:az" (:object (:rendered p)))
        "and the payload still identifies the object whose page this is")))

(deftest the-object-page-and-the-modal-are-one-payload
  ;; The claim the whole task exists to make. Two renderers would drift, and the
  ;; drift would be invisible until somebody materialized something and the page
  ;; they had been reading changed under them.
  ;;
  ;; `but-scores` on both sides, because an exact `=` here would be flaky by
  ;; construction and not by luck: `weight` multiplies relevance by
  ;; `decay(ts, now)` with `now` read afresh inside each `recall`, so two calls a
  ;; millisecond apart differ in the eleventh decimal place. What is under test
  ;; is that the two surfaces answer the same PAGE — same keys, same facts, same
  ;; order, same counts, same sources — not that a float computed twice off two
  ;; readings of the clock agrees with itself.
  (let [st   (entity-fixture)
        m    (entity-memory)
        eid  (:entity (srv/materialize-entity! st "tsmc"))
        get! (fn [uri qs]
               (with-redefs [c/store (delay st) mem/memory (delay m)]
                 (json/read-str (:body (srv/handler {:uri uri :query-string qs}))
                                :key-fn keyword)))
        modal (but-scores (get! "/api/entity" "name=tsmc"))
        page  (but-scores (:rendered (get! "/api/mold" (str "id=" eid))))]
    (is (= modal page)
        "key for key, through the two URLs the shell actually calls")
    ;; the two comparisons above and below are not the same test. JSON flattens
    ;; a set and a vector to the same array and a keyword to its name, so an
    ;; in-process `=` is the one that would catch `:rendered` being assembled
    ;; rather than passed through.
    (is (= (but-scores (srv/entity-payload st m "tsmc"))
           (but-scores (:rendered (srv/mold-payload st m eid nil))))
        "and in process, where a set and a vector are still different things")
    ;; without these, two empty maps would agree and the test would certify it
    (is (= 4 (:count page)))
    (is (= 4 (count (:facts page))))
    (is (seq (:sources page)))))

(deftest a-past-entity-page-reads-todays-facts-and-that-days-objects
  ;; `?at=` hands `mold-payload` a frozen store. Memory has no time dimension
  ;; and `undo!` never touches a fact, so there is no past set of facts to read;
  ;; what the frozen store must still govern is everything the page reads out of
  ;; the SUBSTRATE — the source chips especially. Reading the live store for the
  ;; object while reading the frozen one for everything else is the specific
  ;; mistake this pins.
  (let [st  (entity-fixture)
        m   (entity-memory)
        eid (:entity (srv/materialize-entity! st "tsmc"))
        n   (count (sub/history st))]
    ;; a source arrives AFTER the point we freeze at — and so does a fact
    (sub/commit! st {:op :put :id "doc:gone"
                     :value {:id "doc:gone" :kind :doc :title "The missing memo" :value "…"}})
    ;; word-disjoint from every fact in `entity-rows`, or `remember` reinforces
    ;; an existing one at a 0.6 jaccard instead of adding this
    (mold/remember m "quarry lantern zither mulberry drawbridge"
                   {:entities ["TSMC"] :source {:obj "doc:fab"}})
    (let [now  (:rendered (srv/mold-payload st m eid nil))
          past (:rendered (srv/mold-payload (srv/store-at st (str n)) m eid nil))]
      (is (some #(= "doc:gone" (:id %)) (:sources now))
          "live, the memo is an object and its chip has a name on it")
      (is (not-any? #(= "doc:gone" (:id %)) (:sources past))
          "frozen a commit earlier it is not an object yet, so the chip is dropped — the source list came from the store it was handed")
      ;; the fact half, and phrased so it can actually fail: a fact remembered
      ;; after the freeze must still be on the past page. `(= facts facts)`
      ;; would have passed under every sabotage of this file, including the two
      ;; that delete the branch outright, because nil equals nil.
      (is (= 5 (:count now) (:count past))
          "the fact recorded after the freeze is on BOTH pages: the memory has no time dimension and `undo!` never touches a fact, so there is no past set to read")
      (is (contains? (set (mapv :fact (:facts past))) "quarry lantern zither mulberry drawbridge")
          "…named, and not merely counted"))))

(deftest an-entity-object-with-no-usable-name-molds-to-the-error-the-modal-gets
  ;; A hand-committed, migrated or half-restored object. The branch is on the
  ;; KIND, as `:flow`'s is, and never on whether the name looks usable — a guard
  ;; here would be a second normalization living outside `ent/normalize`, and it
  ;; would disagree with it: `str/blank?` is false for a lone U+00A0, which
  ;; `ent/normalize` trims to nothing.
  ;;
  ;; So the page is `entity-payload`'s own answer for a nameless request, which
  ;; is the shape `GET /api/entity?name=` already hands the modal. Task 8 writes
  ;; ONE renderer that handles it, rather than a case that exists only here.
  (let [st (entity-fixture)
        m  (entity-memory)]
    ;; the middle one is a lone non-breaking space, spelled as an escape so it
    ;; reads as one \u2014 pasted, it looks exactly like a space. It is also the case
    ;; a `str/blank?` guard on this branch would get wrong: `str/blank?` is
    ;; FALSE for U+00A0, which `ent/normalize` trims to nothing.
    (doseq [[id nm] [["ent:blank" "   "] ["ent:nbsp" "\u00a0"] ["ent:missing" nil]]]
      (sub/commit! st {:op :put :id id
                       :value {:id id :kind :entity :title "?" :value (when nm {:name nm})}})
      (let [p (srv/mold-payload st m id nil)]
        (is (= "entity" (:kind p)) (str id " must still mold as an entity"))
        (is (= {:error "no entity name given"} (:rendered p))
            (str id " — the same error shape /api/entity answers a nameless request with"))))))

(deftest molding-a-doc-a-table-and-a-flow-is-what-it-always-was
  ;; The arity changed on every caller, so one of each surviving shape is pinned
  ;; here. `no-memory` is the second half: it throws if forced, so this also
  ;; asserts that the memory argument is consulted on the entity branch and
  ;; nowhere else.
  (let [st (entity-fixture)]
    (sub/commit! st {:op :put :id "tbl:t"
                     :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1} {:a 2}]}})
    (sub/commit! st {:op :put :id "doc:d"
                     :value {:id "doc:d" :kind :doc :title "D" :value "# Heading\n\nbody text"}})
    (sub/commit! st {:op :put :id "flow:f"
                     :value {:id "flow:f" :kind :flow :title "F" :value {:goal "g" :steps []}}})
    (let [mold (fn [id] (srv/mold-payload st no-memory id nil))
          d (mold "doc:d") t (mold "tbl:t") f (mold "flow:f")]
      (is (= ["text" "doc/raw" "# Heading\n\nbody text"] [(:kind d) (:view d) (:rendered d)]))
      (is (= ["table" "table/rows" [{:a 1} {:a 2}]] [(:kind t) (:view t) (:rendered t)]))
      (is (seq (:alternatives t)) "a table still offers its other viewers")
      (is (= ["flow" nil {:goal "g" :steps []} []]
             [(:kind f) (:view f) (:rendered f) (:alternatives f)])))))

(deftest leap-does-not-offer-a-materialized-entity-under-its-own-kind
  ;; The object scan drops `:entity`, because Task 7's subject group is already
  ;; the destination for a subject and the same name under two headings is the
  ;; collision the design split the groups to avoid. The positive half — that it
  ;; gets exactly one row, under "subject" — belongs to Task 7, which is what
  ;; creates that row.
  ;;
  ;; NOT the same decision as `state-payload`, which keeps entity objects: that
  ;; payload is *what exists* and feeds `STATE.objects`, out of which the shell
  ;; draws each notebook member — and a materialized entity is a member of the
  ;; very notebook materialization mints for it.
  (let [st (entity-fixture)]
    (sub/commit! st {:op :put :id "doc:teardown"
                     :value {:id "doc:teardown" :kind :doc :title "TSMC teardown" :value "…"}})
    (let [m    (entity-memory)
          r    (srv/materialize-entity! st "tsmc")
          eid  (:entity r)
          rows (srv/leap-payload st m "tsmc")]
      ;; the controls come first: an empty result would satisfy every assertion
      ;; below it, and this fixture is built so that it cannot be empty
      (is (some #(= "doc:teardown" (:id %)) rows)
          "the object scan is alive — a doc whose title carries the name is still offered")
      (is (some #(and (= (:spaceId r) (:id %)) (= "space" (:group %))) rows)
          "…and so is the notebook the materialization minted, which IS where a subject sends you")
      (is (empty? (filter #(= "entity" (:group %)) rows))
          "no row is grouped `entity`")
      (is (empty? (filter #(= eid (:id %)) rows))
          "…and the object itself is offered by no group at all"))))

;; ============================================================================
;; LEAP's subject group — a subject is a place, and its own kind of place
;; ============================================================================

(defn- subject-rows
  "The `subject` group out of a LEAP payload, in payload order."
  [rows]
  (filterv #(= "subject" (:group %)) rows))

(deftest leap-takes-you-to-a-subject-and-not-only-to-a-document
  ;; Design §3: "Typing a subject takes you to its place, alongside notebooks
  ;; and objects." The document below exists so the assertions have a control —
  ;; an object row that matches the same query proves the subject row is an
  ;; ADDITION to the lexical pass and not a replacement for it.
  (let [st (entity-fixture)
        m  (entity-memory)]
    (sub/commit! st {:op :put :id "doc:teardown"
                     :value {:id "doc:teardown" :kind :doc :title "TSMC teardown" :value "…"}})
    (let [rows (srv/leap-payload st m "tsmc")
          subs (subject-rows rows)]
      (is (some #(= "doc:teardown" (:id %)) rows)
          "control: the object scan still answers, so an empty payload cannot pass this test")
      (is (= [{:id "subject:tsmc" :label "tsmc" :group "subject" :name "tsmc"
               :count 4 :touched 0}]
             subs)
          "one row: the name is the label, the live facts behind it are the count, and `subject:` is a pseudo-id no object ever wore")
      ;; `[false]` and not `(not (contains? …))`: `(contains? nil :object)` is
      ;; false, so the negative spelling would pass over an EMPTY group and
      ;; certify a subject row that was never built
      (is (= [false] (mapv #(contains? % :object) subs))
          "no `:object` KEY at all — not a nil one. The shell decides on presence, and `{:object nil}` is a different answer to \"has this been materialized\" than an absent key")
      ;; where it sits, which is a decision and not an accident: a subject is a
      ;; place you go to, a memory hit is text you read. Both indices are
      ;; asserted non-negative, or an absent subject group would score -1 and
      ;; sort before everything — passing loudest exactly when it is missing.
      (let [gs (mapv :group rows)
            i  (.indexOf ^java.util.List gs "subject")
            j  (.indexOf ^java.util.List gs "memory")]
        (is (and (nat-int? i) (nat-int? j) (< i j))
            (str "the subject group is offered before the memory group — at " i " and " j))))))

(deftest a-name-with-one-fact-behind-it-is-not-yet-a-place
  ;; The whole reason `promoted` exists: 68 of 104 names on the measured corpus
  ;; carry exactly one fact, and a LEAP list made of every extracted string is
  ;; two-thirds noise. `arizona` is still a name the index knows and `ent/detect`
  ;; still matches it for recall — it is just not somewhere to go yet.
  (let [st (entity-fixture)
        m  (entity-memory)
        rows (srv/leap-payload st m "arizona")]
    (is (some #(= "doc:az" (:id %)) rows)
        "control: `Arizona plans` is a document and the query plainly finds things")
    (is (= 1 (count (get (ent/index (mem/all-facts m)) "arizona")))
        "…and `arizona` is a name the index knows, with exactly one fact behind it")
    (is (empty? (subject-rows rows))
        "one live fact is a name, not a subject")))

(deftest a-materialized-subject-carries-its-object-and-never-demotes
  ;; One fact, and a place regardless: materializing is yours, not the evidence's,
  ;; so nothing that happens to the corpus takes the destination away again.
  (let [st (materialize! (entity-fixture) "ent:az" "arizona" "The Arizona build")
        m  (entity-memory)
        subs (subject-rows (srv/leap-payload st m "arizona"))
        row  (first subs)]
    (is (= 1 (count subs)))
    (is (= "ent:az" (:object row))
        "`:object` on the row, so the shell has ONE thing to decide on rather than two")
    (is (= 1 (:count row))
        "one fact — the count is what the evidence says, and the row is here anyway")
    ;; The one fixture in the suite that can tell a name from a title: every
    ;; entity `materialize-entity!` mints is TITLED with its own normalized name,
    ;; so `:label (or (:title o) n)` is indistinguishable from `:label n` on all
    ;; of them. This object is titled `The Arizona build`.
    (is (= "arizona" (:label row))
        "the label is the NAME and never the object's title — a rename must not change what you type to find the subject, which is the whole reason `entity-objects` matches on `[:value :name]`")
    (is (= "subject:arizona" (:id row))
        "the row is still the SUBJECT's, not the object's: the object is where it takes you, not what it is")
    (is (= 0 (:touched row))
        "`cap` looks up `subject:arizona`, which no event ever touched — not `ent:az`, which this fixture's `:put` did")))

(deftest a-materialized-subject-is-one-subject-plus-the-notebook-about-it
  ;; The other half of `leap-does-not-offer-a-materialized-entity-under-its-own-kind`.
  ;;
  ;; TWO rows read `tsmc` after materialization, and both are correct: the
  ;; subject, which is its facts, and the notebook `materialize-entity!` minted,
  ;; which is its cells. Design §3 splits the groups precisely because "entity
  ;; names can collide with object titles" — a subject and a document with the
  ;; same name are two different places to go, and the group heading is what says
  ;; which is which. What must NOT appear is a third row for the entity object
  ;; itself, because the subject row is already that destination.
  (let [st  (entity-fixture)
        m   (entity-memory)
        r   (srv/materialize-entity! st "tsmc")
        eid (:entity r)
        sid (:spaceId r)
        rows (srv/leap-payload st m "tsmc")
        subs (subject-rows rows)]
    (is (= 1 (count subs)) "exactly one subject row")
    (is (= "tsmc" (:label (first subs))))
    (is (= eid (:object (first subs))) "…pointing at the object that was minted")
    (is (empty? (filter #(= eid (:id %)) rows))
        "and NO row for the entity object itself, in any group — the subject row is where it takes you")
    (is (= 1 (count (filter #(and (= sid (:id %)) (= "space" (:group %))) rows)))
        "the notebook about it is offered, once, under `space` — a different destination, deliberately not suppressed")))

(deftest an-empty-query-offers-no-subjects
  ;; Like every other content group. Without the `(seq q)` guard `str/includes?`
  ;; against "" is true of every name, so an opened palette would list every
  ;; promoted subject in the corpus before a key is pressed — and would walk the
  ;; whole memory to build the list.
  (let [st (materialize! (entity-fixture) "ent:tsmc" "tsmc" "TSMC")
        m  (entity-memory)
        rows (srv/leap-payload st m "")]
    (is (some #(= "doc:fab" (:id %)) rows)
        "control: an empty query still lists the objects, so this payload is not empty")
    (is (empty? (subject-rows rows))
        "…and offers no subjects, materialized or not")))

(defn- many-subjects-memory
  "`n` promoted names sharing one substring — two word-disjoint facts each.

   Two facts because one is below the promotion threshold and would leave the
   name out of the group entirely. Word-disjoint for `bulk-entity-memory`'s
   reason: `remember` reinforces an existing fact rather than adding one when
   their token sets overlap by 0.6 or more, and two facts that collapsed into one
   would silently unpromote the name and leave the cap with nothing to cut."
  [n]
  (let [m (mem/file-memory (tmpfile))]
    (doseq [i (range n), j [0 1]]
      (mold/remember m (str "alpha" i "x" j " beta" i "x" j " gamma" i "x" j " delta" i "x" j)
                     {:entities [(format "zorp%02d" i)] :source {:obj "doc:bulk"}}))
    m))

(deftest the-subject-group-is-capped-and-alphabetical-under-the-cap
  ;; Twelve against a `group-cap` of eight, so the cap has four names to cut and
  ;; the assertion can actually fail. Zero-padded so alphabetical and numerical
  ;; order are the same and the expectation reads as one.
  (let [st   (sub/fresh-store)
        m    (many-subjects-memory 12)
        idx  (ent/index (mem/all-facts m))
        subs (subject-rows (srv/leap-payload st m "zorp"))]
    (is (= 12 (count (ent/promoted idx #{})))
        "control: twelve names are promoted — two facts each, none of them reinforced away")
    (is (= 8 (count subs))
        "capped at `group-cap`, like every other group")
    (is (= ["zorp00" "zorp01" "zorp02" "zorp03" "zorp04" "zorp05" "zorp06" "zorp07"]
           (mapv :label subs))
        "alphabetical, and the cap takes the first eight of THAT order: `promoted` answers a set in hash order, the group sorts it, and `cap`'s `sort-by :touched` is stable over rows whose `:touched` is 0 to a name")))

(defrecord CountingStore [inner !n]
  ;; Every read delegates; `objects` also counts. A spy and not a stub — the
  ;; payload under it is the real one, so a miscount cannot hide behind an
  ;; answer nothing checked.
  sub/Store
  (commit! [_ event] (sub/commit! inner event))
  (state   [_]       (sub/state inner))
  (objects [_]       (swap! !n inc) (sub/objects inner))
  (object  [_ id]    (sub/object inner id))
  (history [_]       (sub/history inner))
  (undo!   [_]       (sub/undo! inner))
  (as-of   [_ n]     (sub/as-of inner n)))

(defrecord MutatingStore [inner !n after]
  ;; Commits `after` the moment the FIRST `objects` read has been answered, so
  ;; the second read of one request sees a world the first did not. That is the
  ;; race, made deterministic — a real one needs another thread committing
  ;; between two reads of the same payload, which is a normal Tuesday on a store
  ;; an agent flow is also writing to.
  sub/Store
  (commit! [_ event] (sub/commit! inner event))
  (state   [_]       (sub/state inner))
  (objects [_]       (let [m (sub/objects inner)]
                       (when (= 1 (swap! !n inc)) (sub/commit! inner after))
                       m))
  (object  [_ id]    (sub/object inner id))
  (history [_]       (sub/history inner))
  (undo!   [_]       (sub/undo! inner))
  (as-of   [_ n]     (sub/as-of inner n)))

(deftest one-payload-reads-the-objects-once-and-so-answers-about-one-world
  ;; `objs`, `intext` and `made` each used to call `sub/objects` for themselves,
  ;; which is three reads of a store anything else may be writing to — and the
  ;; halves disagree in a way that loses rows rather than duplicating them. The
  ;; document below appears between the first read and the second: the old shape
  ;; showed it under `in text` while the object scan, which read the older world,
  ;; had never heard of it.
  ;;
  ;; The property is not "the new document is offered" — a request may honestly
  ;; predate it. It is that ONE request answers about ONE world: a document is
  ;; either in this payload or not in it, never present to one group and absent
  ;; from another.
  (let [inner (sub/fresh-store)
        m     (mem/file-memory (tmpfile))
        late  {:op :put :id "doc:late"
               ;; a title that does NOT match, so `intext` would not exclude it,
               ;; and a body that does — the shape that made the old skew visible
               :value {:id "doc:late" :kind :doc :title "Memo" :value "the zzz body"}}
        st    (->MutatingStore inner (atom 0) late)
        rows  (srv/leap-payload st m "zzz")]
    (is (some? (sub/object inner "doc:late"))
        "control: the store really did grow mid-request — the spy committed it")
    (is (empty? (filter #(= "doc:late" (:id %)) rows))
        "the document that arrived after this request read the world is in NO group of it, rather than in `in text` and nowhere else")))

(deftest the-subject-group-builds-one-objects-map-and-not-one-per-name
  ;; `entity-object` per promoted name and `materialized-names` beside it is N+1
  ;; whole objects maps **on a keystroke** — and N is every name the query
  ;; matched, not the eight `cap` keeps, because the row is built before the cap
  ;; sees it. Measured on an EDN store, the group alone: 36 names over 900
  ;; objects, 16.2 ms naive against 0.80 ms shared; 360 names over 900 objects,
  ;; 122 ms against 1.7 ms.
  ;;
  ;; Nothing about the payload's CONTENT notices, which is why this is asserted
  ;; on the store instead: one store, two memories, and the number of objects
  ;; maps built must not move when the number of subjects does. A count pinned
  ;; to a literal would instead break every time an unrelated line in
  ;; `leap-payload` read the store, and would say nothing about this.
  (let [st  (sub/fresh-store)
        n3  (atom 0)
        n12 (atom 0)
        m3  (many-subjects-memory 3)
        m12 (many-subjects-memory 12)]
    (is (= 3 (count (subject-rows (srv/leap-payload st m3 "zorp"))))
        "control: three subjects match…")
    (is (= 8 (count (subject-rows (srv/leap-payload st m12 "zorp"))))
        "…and twelve do, capped to eight — four times the rows built")
    (srv/leap-payload (->CountingStore st n3) m3 "zorp")
    (srv/leap-payload (->CountingStore st n12) m12 "zorp")
    (is (pos? @n3) "control: the spy is wired in and the payload does read the store")
    (is (= @n3 @n12)
        (str "the same number of objects maps either way — " @n3 " and " @n12))))

(deftest a-subject-is-matched-the-way-every-other-group-is-matched
  (let [st (entity-fixture)
        m  (entity-memory)
        labels (fn [q] (mapv :label (subject-rows (srv/leap-payload st m q))))]
    (is (= ["tsmc"] (labels "TSMC"))
        "`leap-payload` lower-cases the query and `ent/normalize` lower-cased the names, so the two sides meet")
    ;; INFIX, and that word is load-bearing: both names below start with
    ;; `taiwan`, so the assertion under them holds verbatim under a
    ;; `str/starts-with?` filter and says nothing about substrings. These two do
    ;; not — `semiconductor` is in the middle of one name and `sml` is inside
    ;; `asml` — and a prefix filter answers `[]` to each.
    (is (= ["taiwan semiconductor manufacturing"] (labels "semiconductor"))
        "a match INSIDE a name, not at its front: LEAP is a substring search and a subject is found the way a document is")
    (is (= ["asml"] (labels "sml"))
        "…including inside a single word")
    (is (= ["taiwan" "taiwan semiconductor manufacturing"] (labels "taiwan"))
        "two matches for one query, alphabetical between them")
    (is (= [] (labels " tsmc "))
        "a query with surrounding space finds nothing here — exactly as it finds nothing in any other group, because `leap-payload` lower-cases `q` and deliberately does not trim it, and the shell trims before it asks")))

(deftest leap-folds-case-with-the-locale-entity-identity-uses-and-not-the-JVMs
  ;; Measured under `-Duser.language=tr`, one doc titled `IBM memo` and two facts
  ;; naming `IBM`. Before the fix NEITHER spelling of the query returned the
  ;; page, and they failed in opposite directions:
  ;;
  ;;   q="IBM"  →  the doc alone. `q` folded to `ıbm` through `str/lower-case`,
  ;;               so it matched neither the subject `ibm` — folded by
  ;;               `ent/normalize` under `Locale/ROOT` — nor the facts, since
  ;;               `mold/recall` was handed the mangled query too.
  ;;   q="ibm"  →  the subject and the facts, and NOT the doc: `hit?` folds the
  ;;               HAYSTACK, so the title became `ıbm memo` and an
  ;;               already-lower-case query lost it.
  ;;
  ;; This test changes the default locale instead of constructing the mismatch
  ;; out of strings, and that is the point rather than a shortcut: under an
  ;; English locale `str/lower-case` and `Locale/ROOT` agree on every input, so
  ;; NO payload this function can produce tells the broken code from the fixed
  ;; one. That is why 453 tests and 1810 assertions passed over the bug, and why
  ;; a string-level assertion here would join them. The only test that can fail
  ;; is one that runs where the two disagree.
  ;;
  ;; `setDefault` is JVM-global, so it is restored in a `finally` — which runs
  ;; on a failing `is` too, because `is` records rather than throws — and the
  ;; runner is sequential.
  (let [was (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag "tr"))
      (is (not= "ibm" (str/lower-case "IBM"))
          "control: this JVM is folding `I` to a dotless `ı` right now — without it the rest of this test proves nothing")
      (let [st (sub/fresh-store)
            m  (mem/file-memory (tmpfile))]
        (sub/commit! st {:op :put :id "doc:memo"
                         :value {:id "doc:memo" :kind :doc :title "IBM memo" :value "…"}})
        ;; two facts, so `ibm` is promoted; word-disjoint, so `remember`
        ;; reinforces neither into the other and both survive as facts
        (mold/remember m "the mainframe division posted another quarter"
                       {:entities ["IBM"] :source {:obj "doc:memo"}})
        (mold/remember m "quantum roadmap slips by eighteen months"
                       {:entities ["IBM"] :source {:obj "doc:memo"}})
        (let [rows  (fn [q] (set (map (juxt :group :id :label) (srv/leap-payload st m q))))
              upper (rows "IBM")
              lower (rows "ibm")]
          (is (contains? upper ["subject" "subject:ibm" "ibm"])
              "typing the name as it is written finds the subject — folding `q` under the default locale lost it")
          (is (contains? lower ["doc" "doc:memo" "IBM memo"])
              "…and typing it lower-case still finds the document — folding the TITLE under the default locale lost that")
          (is (= upper lower)
              "the two spellings answer the same page, which is the whole property: one locale-independent fold on both sides of every comparison")
          (is (some (fn [[g]] (= "memory" g)) upper)
              "including the facts, which `mold/recall` never saw for a mangled query")))
      (finally (Locale/setDefault was)))))

(deftest the-mold-route-reads-the-past-when-asked-and-the-present-when-not
  ;; `?at=` on /api/mold is otherwise untested: deleting `store-at` from that one
  ;; route leaves every mold assertion in this file green, because they all call
  ;; `mold-payload` directly. Task 6 reasons at length about WHICH store the
  ;; entity branch is handed under `?at=`; nothing pinned that the route hands it
  ;; one at all.
  (let [st   (sub/fresh-store)
        m    (entity-memory)
        get! (fn [qs] (with-redefs [c/store (delay st) mem/memory (delay m)]
                        (json/read-str (:body (srv/handler {:uri "/api/mold" :query-string qs}))
                                       :key-fn keyword)))]
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "first"}})
    (sub/commit! st {:op :put :id "doc:d" :value {:id "doc:d" :kind :doc :title "D" :value "second"}})
    (is (= "second" (:rendered (get! "id=doc:d")))
        "no ?at= reads the live store")
    (is (= "first" (:rendered (get! "id=doc:d&at=1")))
        "?at=1 is the world after one event — the route passes the store through `store-at`, and nothing else can produce this")
    (is (= "second" (:rendered (get! "id=doc:d&at=99")))
        "…clamped to the log's length rather than answering an empty world")))

(deftest flows-payload-puts-what-needs-you-first-and-says-what-it-asks
  ;; 2026-08-16: a flow lived only as a cell in the notebook it was started in,
  ;; so one waiting at a gate somewhere else was invisible until you wandered
  ;; into it. This payload is the flows pane's whole content.
  (let [st (store-with-table)]                     ; gives us space:n
    (let [older (srv/flow-create! st "space:n" "an older one"
                                  [{:verb "draft" :args {} :status "done"}])
          _     (sub/commit! st {:op :assoc :id older :path [:value :status] :value "done"})
          newer (srv/flow-create! st "space:n" "the one waiting"
                                  [{:verb "gate" :args {:question "Proceed?"} :status "pending"}
                                   {:verb "draft" :args {} :status "pending"}])
          _     (srv/run-flow! st newer)
          p     (srv/flows-payload st)
          [a b] (:flows p)]
      (is (= 1 (:needs-you p)))
      (is (= newer (:id a)) "the flow needing you comes first, wherever it lives")
      (is (= "needs-you" (:status a)))
      (is (= "Proceed?" (:question a)) "the pane says what it is asking without a second fetch")
      (is (= "gate" (:step a)))
      (is (= [0 2] [(:done a) (:steps a)]))
      (is (= older (:id b)))
      (is (nil? (:question b)) "a flow not at a gate carries no question")
      ;; and the one-integer badge count on the ordinary state payload
      (is (= 1 (:flows-need-you (srv/state-payload st))))
      (srv/flow-gate! st newer false)
      (is (= 0 (:flows-need-you (srv/state-payload st)))
          "rejecting clears the badge — nothing is waiting any more"))))

;; ---------------------------------------------------------------------------
;; every reader hydrates, not just mold-payload
;;
;; A table's rows live in the lake; the log holds a handle. Twelve places pull
;; an object out of the substrate and read `:value` as rows. Three of them are
;; pinned here: the ƒ palette (which answered "functions work on tables" for a
;; real table), the agent's own workspace digest (where a table vanished
;; silently, because `obj-digest` returns "" for anything that is not rows),
;; and `table-shaped?`, the scan a compute step uses to find the table an
;; earlier step produced.
;; ---------------------------------------------------------------------------

(deftest the-fn-palette-sees-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (let [r (srv/fns-payload st "tbl:x")]
      (is (nil? (:error r)) "a handle-backed table must not read as 'not a table'")
      (is (seq (:fns r))))))

(deftest a-compute-step-resolves-past-a-doc-to-the-handle-backed-table
  ;; `table-shaped?` is the scan `resolve-table-ref` runs over earlier outputs.
  ;; A handle is a MAP, so an unhydrated scan reads a lake-backed table as "not
  ;; a table" and stops at whatever `resolve-ref` already returned — here the
  ;; doc — and the compute step runs on prose. A single-step flow cannot show
  ;; this: `resolve-ref` has already produced the step's own `:out` and the `or`
  ;; falls back to it either way. It needs a non-table step in front.
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "doc:n" :value {:id "doc:n" :kind :doc :title "N" :value "text"}})
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value h}})
    (is (= "tbl:x"
           (#'srv/resolve-table-ref st {:steps [{:out "doc:n"} {:out "tbl:x"}]} 0))
        "the scan must skip the doc and find the table, wherever its rows live")))

(deftest the-workspace-digest-still-describes-a-handle-backed-table
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1} {:a 2}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "Sales" :value h}})
    (let [c (:context (#'srv/agent-ctx st nil))]
      (is (re-find #"tbl:x" c) "the table must appear in the agent's context at all")
      (is (re-find #"2 rows" c) "and with its true row count"))))

(deftest the-workspace-digest-reads-no-rows
  ;; …and it must name the table WITHOUT fetching a row. `agent-ctx` runs on
  ;; every chat turn and scales with the total rows in the workspace, not with
  ;; the table being asked about: measured 282 ms over the migrated store's
  ;; 3,957 rows, against 14 ms once it reads the handle instead.
  (let [st (sub/fresh-store)
        h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1 :b "p"} {:a 2 :b "q"}])]
    (sub/commit! st {:op :put :id "tbl:x"
                     :value {:id "tbl:x" :kind :table :title "Sales" :value h}})
    (with-redefs [tbl/rows (fn [& _] (throw (ex-info "agent-ctx fetched rows" {})))]
      (let [c (:context (#'srv/agent-ctx st nil))]
        (is (re-find #"tbl:x — Sales" c) "the table is still named")
        (is (re-find #"cols: a, b" c) "with its columns")
        (is (re-find #"2 rows" c) "and its true row count")))))

;; ---------------------------------------------------------------------------
;; the three server writers land handles — and a filter that matched nothing
;; still lands a table
;; ---------------------------------------------------------------------------

(deftest every-server-writer-lands-a-handle
  ;; `rows-of` hydrates, so it reads an unconverted writer's literal rows just
  ;; as happily as a handle — which means no assertion above can tell whether
  ;; these three were converted at all. This is the one that can.
  (with-temp-data-dir
    (fn []
      (let [st (store-with-table)
            landed? (fn [id] (tbl/handle? (:value (sub/object st id))))]
        (is (landed? (:openId (srv/import-csv! st "C" "region,revenue\nEMEA,1" nil)))
            "import-csv!")
        (is (landed? (:openId (srv/fn-apply! st "tbl:t" "lib:top"
                                             {:by "revenue" :n "1" :order "desc"} nil)))
            "the ƒ apply")
        (with-redefs [agent/make-clj-transform (fn [& _] "(mapv #(assoc % :x 1) rows)")]
          (is (landed? (:openId (srv/compute-clj! st "tbl:t" "add x" nil)))
              "compute-clj!"))))))

(deftest a-csv-with-only-a-header-is-an-empty-table-not-an-error
  ;; `Lake/put-rows!` refuses a table with no rows. Of the six writers this is
  ;; one of only two that can hand it one — `compute-clj!` and the ƒ apply
  ;; cannot, because `run-clj-rows` and `fnlib/run-fn` both already answer
  ;; "the function did not return rows" for an empty result, measured
  ;; 2026-08-16. Before tables moved this committed `:value []` and the shell
  ;; drew an empty table; it still must.
  (with-temp-data-dir
    (fn []
      (let [st (store-with-table)
            r  (srv/import-csv! st "C" "region,revenue" nil)]
        (is (nil? (:error r)) "a header-only CSV must not become an error")
        (is (= [] (rows-of st (:openId r))))))))

(deftest a-header-only-csv-lands-an-empty-lake-table-not-a-literal
  ;; The shape above changed underneath that test, and this is where it is
  ;; pinned rather than left to be discovered. `tbl/table-value` refused an
  ;; empty table and committed `:value []`; `create-from-relation!` makes a
  ;; real, empty lake table and commits a handle with `:rows 0`. The test above
  ;; still passes because it reads through `rows-of`, which hydrates — and
  ;; hydrating an empty lake table gives `[]` exactly as a literal `[]` does.
  ;;
  ;; Landing the table is the simpler rule and the one that keeps `refresh!`
  ;; working: `[]` is not a handle, so a `:value []` table carries no recipe to
  ;; re-run.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            r  (srv/import-csv! st "C" "region,revenue" nil)
            h  (:value (sub/object st (:openId r)))]
        (is (nil? (:error r)))
        (is (tbl/handle? h) "a header-only CSV lands a handle, where table-value gave []")
        (is (= 0 (:rows h)))
        (is (= [] (:value (tbl/hydrate @lake/lake (sub/object st (:openId r))))))))))

(deftest import-csv-answers-the-shell-the-three-keys-startImport-reads
  ;; `resources/public/index.html`'s `startImport` reads `res.error`, then
  ;; `applyState(res.state)` and `openObject(res.openId)`. `import-csv!` is a
  ;; wrapper over `source-connect!` now, and this is the assertion that the
  ;; wrapper actually connects rather than only writing the file: without the
  ;; connect there is no object to open and `:openId` is nil.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            r  (srv/import-csv! st "Sales" "region,revenue\nEMEA,1\n" nil)]
        (is (nil? (:error r)))
        (is (some? (:openId r)) "the id the shell opens")
        (is (some? (:state r)) "and the state payload it applies")
        (is (= :table (:kind (sub/object st (:openId r))))
            "openObject must find a table there, not a missing id")
        (is (= [{:region "EMEA" :revenue 1}] (rows-of st (:openId r))))
        (is (re-matches #"loci://files/[0-9a-f]{16}\.csv"
                      (:uri (:source (sub/object st (:openId r)))))
            "and the CSV is on disk as a source, so refresh! has something to re-run")))))

(deftest import-csv-remembers-the-title-it-was-given
  ;; A pasted CSV and an uploaded one land in the same content-addressed
  ;; directory, but until now only the upload wrote `files.edn` — so a paste was
  ;; indistinguishable from a file someone `rsync`'d in, and the picker drew it
  ;; under its hash. `nm` is read from the LISTING rather than from the manifest
  ;; deliberately: read from `names` it would be nil while the bug is present,
  ;; and `re-matches` on nil throws — aborting the test instead of failing the
  ;; assertion that is actually about the name.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            r  (srv/import-csv! st "Q3 Takings" "region,revenue\nEMEA,10\n" nil)
            nm (first (map :file (files/listing st)))]
        (is (nil? (:error r)))
        (is (re-matches #"[0-9a-f]{16}\.csv" nm)
            "the file is still content-addressed — the title is metadata, not the name")
        (is (= "Q3 Takings" (get (files/names) nm))
            "and the manifest remembers what it was called, so the picker can show it")
        (is (= "Q3 Takings" (:name (first (filter #(= nm (:file %)) (files/listing st)))))
            "which is what makes a pasted CSV list under its title instead of its hash")))))

(deftest import-csv-with-no-title-remembers-nothing-rather-than-a-blank
  ;; Two pastes, because `nil` and `""` are guarded by different halves of the
  ;; expression and only one of them can catch a regression. `when-let` alone
  ;; already drops a nil title — a test that passed only nil would keep passing
  ;; with the `not-empty` deleted. `""` is what `not-empty` is for, and it is
  ;; what the shell posts when the title field is left blank.
  ;;
  ;; The manifest FILE is asserted on, not only `names`: `names` keeps a record
  ;; whose `:name` is `""` (an empty string is truthy), so it does move — but
  ;; the file is the direct statement that nothing was appended at all.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)
            a  (srv/import-csv! st "" "a,b\n1,2\n" nil)
            b  (srv/import-csv! st nil "c,d\n3,4\n" nil)]
        (is (every? nil? (map :error [a b])))
        (is (not (.exists (io/file (sub/data-dir) "files.edn")))
            "an untitled paste has no name to remember, and an empty string is not a name")
        (is (empty? (files/names))
            "so the manifest holds nothing for either of them")
        ;; `contains?`, and NOT `(= [nil nil] (mapv :name …))`. `:name`'s
        ;; contract is ABSENT rather than nil, and the old spelling answers the
        ;; same for a missing key as for a present-and-nil one — a `listing`
        ;; with its `(named n)` guard dropped assocs `:name nil` on every row
        ;; and passes it. Measured 2026-08-22 with the guard actually dropped.
        ;; The vector keeps its length, so this still says TWO rows.
        (is (= [false false] (mapv #(contains? % :name) (files/listing st)))
            "and the picker draws them under their hashes, which is the honest answer")))))

(deftest a-transform-that-keeps-nothing-never-reaches-the-writer
  ;; the other half of the same measurement: this path refuses upstream, so the
  ;; empty branch of `tbl/table-object` is unreachable from here — do not "fix"
  ;; compute by removing a guard it does not use.
  (with-redefs [agent/make-clj-transform (fn [& _] "(filterv (fn [_] false) rows)")]
    (let [st (store-with-table)
          r  (srv/compute-clj! st "tbl:t" "keep nothing" nil)]
      (is (= "compute failed: the function did not return rows" (:error r))))))

(deftest rerun-writes-a-handle-and-not-the-refreshed-rows
  ;; The seventh writer, and the one no grep for `:kind :table` finds: `rerun!`
  ;; replaces an existing object's `:value` in place. It went unnoticed for the
  ;; same reason `every-server-writer-lands-a-handle` exists — `rows-of` reads a
  ;; literal vector exactly as happily as a handle, so nothing that hydrates can
  ;; tell. Measured before the fix: one refresh of a 50-row derived table wrote a
  ;; 1,599-character event with `:region "r99"` in it.
  (let [trows (mapv (fn [i] {:region (str "r" i) :revenue (* 10 i)}) (range 100))
        st    (sub/fresh-store)
        _     (sub/commit! st {:op :put :id "tbl:t"
                               :value (tbl/table-object @lake/lake "tbl:t" "T" trows)})
        b     (:openId (srv/fn-apply! st "tbl:t" "lib:top"
                                      {:by "revenue" :n "50" :order "desc"} nil))
        _     (srv/rerun! st "tbl:t")
        ev    (last (sub/history st))]
    (is (tbl/handle? (:value (sub/object st b)))
        "a refresh must leave a handle where fn-apply! left one")
    (is (not (re-find #":region \"r99\"" (pr-str ev)))
        "and no row may appear anywhere in the :assoc event")
    (is (= 50 (count (rows-of st b))) "the refreshed rows are still readable")))

(deftest rerun-answers-a-refusal-instead-of-a-500
  ;; `rerun!` was the only writer with no `try`, and `handler` has no top-level
  ;; catch, so /api/rerun answered a throw with a stack trace. Reachable since
  ;; `normalize-rows` began refusing a collision: `lib:pivot` names its columns
  ;; from data VALUES, so a source that grows "a b" beside "a_b" collides on
  ;; recompute where the original apply did not.
  (let [st (sub/fresh-store)
        _  (sub/commit! st {:op :put :id "tbl:t"
                            :value (tbl/table-object @lake/lake "tbl:t" "T"
                                                     [{:r "r1" :c "x" :m 1} {:r "r2" :c "y" :m 2}])})
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:pivot"
                                   {:rows_col "r" :cols_col "c" :measure "m" :agg "sum"} nil))
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:r "r1" :c "a b" :m 1} {:r "r2" :c "a_b" :m 2}]})
        before (count (sub/history st))
        r  (srv/rerun! st "tbl:t")]
    (is (some? b))
    (is (re-find #"two column names would become one column" (str (:error r))))
    (is (= before (count (sub/history st))) "and nothing was committed")))

(deftest the-seed-lands-handles-for-every-row-bearing-kind
  ;; The EIGHTH writer, missed for the same reason as the seventh: `content/obj`
  ;; takes `kind` as a parameter, so it spells no `:kind :table` literal for a
  ;; grep to find. This is production code — `content/store` seeds whenever the
  ;; log is empty, so a fresh boot after a migration re-created them inline.
  ;;
  ;; **Kind-agnostic on purpose.** The first version of this test filtered the
  ;; seeded objects by `:kind :table` and asserted `(= 10 …)` — the same filter
  ;; the fix used — so it was structurally blind to the two `:metric` objects
  ;; the fix missed. Filtering by the property being fixed is the blind spot
  ;; this whole move keeps rediscovering.
  ;;
  ;; Measured before: 10 tables at 230,300 characters (`tbl:orders` alone
  ;; 134,630 in one event) and 2 metrics at 1,882, with `777000` in the log.
  (let [st (sub/fresh-store)
        _  (c/seed! st @lake/lake)
        rb (->> (sub/objects st) vals (filter #(#{:table :metric} (:kind %))))]
    (is (= 12 (count rb)) "10 tables and 2 metrics")
    (is (every? #(tbl/handle? (:value %)) rb))
    ;; Scoped to the row-bearing events, and NOT to the whole log, because
    ;; `report:qbr` carries its own inline copy of the mrr rows inside a chart
    ;; spec. A report's `:value` is a document, not a table — a different kind
    ;; of duplication, and not one this constructor reaches.
    (is (not (re-find #"777000"
                      (pr-str (filter #(#{:table :metric} (:kind (:value %))) (sub/history st)))))
        "a metric's rows must not be in its own event either")
    ;; 3,032 with all twelve converted; 4,454 with the two metrics left inline,
    ;; 232,144 with none of them. The bound has to sit below the middle number
    ;; or it does not notice the case this test was rewritten for.
    (is (< (reduce + (map (comp count pr-str)
                          (filter #(#{:table :metric} (:kind (:value %))) (sub/history st))))
           3500)
        "the twelve :put events together")
    (is (= 1000 (count (rows-of st "tbl:orders"))) "and the rows are still all there")
    (is (= 24 (count (rows-of st "metric:mrr"))))
    (is (= :metric (:kind (sub/object st "metric:mrr"))) "landing rows must not restamp the kind")))

(deftest as-of-reads-the-table-that-existed-then
  ;; Design decision 1 — every read names its version — is supposed to make
  ;; time travel work over lake-backed tables without anything else being built.
  ;; No implementation backs this test; it exists because the claim needs one.
  ;;
  ;; **What it does not prove.** Measured by sabotage 2026-08-17: dropping
  ;; ` AT (VERSION => …)` from `lake/rows` leaves this test green, because each
  ;; table here has exactly one snapshot and the current version IS the recorded
  ;; one. `a-read-names-its-version` in `loci.lake-test` is the test that fails
  ;; there. This one proves the substrate's `frozen-at` window and the lake agree
  ;; on which tables existed — not that a handle reads its own version.
  (let [st (sub/fresh-store)
        a  (:saved_as (tools/save-table! st "A" [{:v 1}]))
        n1 (count (sub/history st))
        b  (:saved_as (tools/save-table! st "B" [{:v 2}]))
        frozen (sub/frozen-at st n1)]
    (is (some? (sub/object frozen a)) "the first table existed at n1")
    (is (nil? (sub/object frozen b))  "the second did not")
    (is (= [{:v 1}] (:value (tbl/hydrate lake/lake (sub/object frozen a))))
        "and its rows still resolve, from a store that cannot commit")))

;; ---------------------------------------------------------------------------
;; a reclaimed table, on the two reader paths server.clj owns
;;
;; `hydrate` degrades rather than throwing, so both of these already answered
;; *something* before the fix — an empty table, and a catalog line advertising
;; 100 rows the store cannot serve. Saying "reclaimed" is the whole change.
;; ---------------------------------------------------------------------------

(deftest mold-payload-says-a-table-was-reclaimed
  (lf/with-reclaimed-table
    (fn [st]
      (let [p (srv/mold-payload st no-memory "tbl:gone" nil)]
        (is (:reclaimed p) "the payload says so rather than showing an empty table")
        (is (= 100 (:table/total p)) "and still reports what was there")
        (is (string? (json/write-str p)) "and the shell can render it")))))

(deftest the-agent-catalog-skips-a-reclaimed-table
  (lf/with-reclaimed-table
    (fn [st]
      (let [c (:context (#'srv/agent-ctx st nil))]
        (is (re-find #"tbl:good" c) "the healthy table is still catalogued")
        (is (not (re-find #"tbl:gone" c))
            "and the reclaimed one is omitted rather than advertised with rows it cannot serve")))))

(deftest as-of-answers-for-every-object-when-one-was-reclaimed
  ;; Phase 1's governing requirement, and the only test that states it whole:
  ;; failure is per object, never per operation. Each of the seven reader sites
  ;; has its own test; this one is the claim they were all changed to support —
  ;; that landing on a state containing one reclaimed table still lets every
  ;; other object answer normally.
  (lf/with-reclaimed-table
    (fn [st]
      (sub/commit! st {:op :put :id "doc:d"
                       :value {:id "doc:d" :kind :doc :title "D" :value "prose"}})
      (let [frozen (sub/frozen-at st (count (sub/history st)))]
        (is (= "prose" (:value (sub/object frozen "doc:d"))) "the doc answers")
        (is (= [{:b 1}] (:value (tbl/hydrate lake/lake (sub/object frozen "tbl:good"))))
            "the healthy table answers")
        (is (:table/reclaimed (tbl/hydrate lake/lake (sub/object frozen "tbl:gone")))
            "and the reclaimed one says so, without stopping the other two")))))

;; ---------------------------------------------------------------------------
;; the flow engine — the eighth reader, and the one where "per operation"
;; failure was still reachable
;; ---------------------------------------------------------------------------

(deftest a-flow-steps-over-a-reclaimed-table-to-the-live-one
  ;; `table-shaped?` answers from the handle, and a handle survives its rows:
  ;; same cols, same count, forever. So `resolve-table-ref`'s reverse scan —
  ;; whose entire job is to find a table the compute step can USE — stopped at
  ;; the reclaimed one, `compute-clj!` answered "compute works on tables", and
  ;; `run-flow!` marked that step AND the whole flow failed. One reclaimed table
  ;; stopping every remaining step is failure per operation, where the
  ;; requirement is per object — with a live table sitting two outputs earlier.
  (lf/with-reclaimed-table
    (fn [st]
      (sub/commit! st {:op :put :id "doc:prose"
                       :value {:id "doc:prose" :kind :doc :title "P" :value "prose"}})
      (sub/commit! st {:op :put :id "space:s"
                       :value {:id "space:s" :kind :space :title "S" :value {:intent "i" :cells []}}})
      (let [flow {:id "flow:f" :kind :flow :title "F"
                  :value {:goal "g" :space "space:s" :status "running"
                          :steps [{:verb "research" :status "done" :out "tbl:good"}
                                  {:verb "research" :status "done" :out "doc:prose"}
                                  {:verb "research" :status "done" :out "tbl:gone"}
                                  {:verb "compute" :args {:id "$2" :prompt "keep it"}
                                   :status "pending"}]}}]
        (sub/commit! st {:op :put :id "flow:f" :value flow})
        (is (false? (#'srv/table-shaped? st "tbl:gone"))
            "a table whose rows are gone is not one this scan can use")
        (is (true? (#'srv/table-shaped? st "tbl:good")))
        (is (= "tbl:good" (srv/resolve-table-ref st (:value flow) "$2"))
            "the scan walks PAST the reclaimed output to the live table behind it")
        ;; and the flow runs to the end. The model is stubbed — what is being
        ;; measured is which table the step was handed, not what it computed.
        (with-redefs [agent/make-clj-transform (fn [& _] "(fn [rows] rows)")]
          (let [r (srv/run-flow! st "flow:f")]
            (is (= "done" (:status r))
                "the flow completes rather than failing at a table it never needed")))))))

;; ---------------------------------------------------------------------------
;; /api/storage and /api/storage/reclaim
;;
;; A nested `with-temp-lake` inside the namespace's `:once` one: these ask what
;; a WHOLE lake costs and how many versions a reclaim took, and both numbers are
;; wrong the moment another test's tables share the directory.
;; ---------------------------------------------------------------------------

(deftest storage-endpoint-reports-and-serializes
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            h  (tbl/put-rows! @lake/lake "tbl:x" [{:a 1}])]
        (sub/commit! st {:op :put :id "tbl:x"
                         :value {:id "tbl:x" :kind :table :title "X" :value h}})
        (let [r (#'srv/storage-payload st)]
          (is (= 1 (count (:tables r))))
          (is (get-in r [:lake :catalog]))
          (is (string? (json/write-str r))
              "no DuckDBArray may reach the payload — ducklake_snapshots' :changes holds one"))))))

(deftest storage-reclaim-endpoint-frees-and-reports
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)]
        (dotimes [_ 5]
          (let [h (tbl/put-rows! @lake/lake "tbl:x" (vec (for [n (range 300)] {:a n})))]
            (sub/commit! st {:op :put :id "tbl:x"
                             :value {:id "tbl:x" :kind :table :title "X" :value h}})))
        (let [r (#'srv/storage-reclaim! st "tbl:x" {:versions 3 :days 0})]
          (is (pos? (:freed r)))
          (is (= 2 (:versions r))))))))

(deftest storage-reclaim-refuses-a-request-that-named-no-object
  ;; The route reads `:id` out of a JSON body, and `json/read-str` cannot tell a
  ;; missing key from an explicit null. `gc/reclaim!` gives `nil` a meaning —
  ;; **the orphans** — so without this guard `POST /api/storage/reclaim {}`, or a
  ;; stray GET with no body at all, irreversibly deletes every orphaned table in
  ;; the lake and answers as if that is what was asked for. Measured before the
  ;; guard: `{:object nil :tables ["t_tbl_orphan_…"] :versions 1 :freed 2176}`.
  ;;
  ;; This is the one place the surrounding routes' habit — pass the body value
  ;; through raw and let the callee refuse it by name — does not hold, because
  ;; the callee does not refuse it.
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)]
        (tbl/put-rows! @lake/lake "tbl:orphan" (vec (for [n (range 300)] {:a n})))
        (let [r (#'srv/storage-reclaim! st nil nil)]
          (is (= :no-object (:error r)) "a refusal, not a sweep of the orphans")
          (is (= 0 (:freed r)))
          (is (= 1 (count (lake/live-tables @lake/lake))) "and the orphan is still there"))))))

;; ---------------------------------------------------------------------------
;; the files half of the storage panel
;;
;; `loci.files/report` and `reclaim!` have their own suite in `loci.files-test`.
;; What is left here is the two things only this namespace can see: that the
;; payload the panel draws carries them, and that the destructive half is
;; reachable over HTTP under the window the SERVER ships rather than one a test
;; invented.
;; ---------------------------------------------------------------------------

(deftest storage-payload-carries-what-the-files-directory-costs
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "0123456789")
        (let [p (#'srv/storage-payload st)]
          (is (= 10 (get-in p [:files :total-bytes]))
              "the directory's real length in bytes, not a count of files")
          (is (= 1 (get-in p [:files :count]))
              "and how many files that is — a panel drawing no button still has to say
               what the directory holds, and `report`'s own rows are not on the wire
               to be counted")
          (is (= 0 (get-in p [:files :reclaimable-bytes]))
              "a hand-placed file costs bytes and frees none — the panel must not
               offer to delete something loci did not write")
          (is (= 0 (get-in p [:files :reclaimable-count]))
              "and none of them by count, which is what the BUTTON gates on — the
               bytes cannot, because nothing refuses an empty upload and a
               zero-length reclaimable file makes them 0 while there is plainly
               something to delete")
          (is (= {:foreign 1} (get-in p [:files :classes]))
              "and the classifier's own counts travel, which is the only thing that
               can make the zero-state phrase name a REASON rather than assert
               `nothing loci wrote is unreferenced` over an upload nobody has
               connected yet"))))))

(deftest storage-payload-carries-the-files-figures-through-rather-than-hard-coding-them
  ;; The fixture above is one hand-placed `:foreign` file, so every figure it
  ;; asserts is 0 or empty — and 0 and empty are also exactly what a payload
  ;; that hard-codes them answers. Measured 2026-08-22: `(assoc … :reclaimable
  ;; [])` passed both suites, and the `:doomed` rename emptied the panel's rows
  ;; underneath a green test for the same reason.
  ;;
  ;; So: a `report` that answers non-zero on every key, and a payload that has
  ;; to have read it. Stubbed rather than staged because what is under test is
  ;; the transfer, and a fixture that produces four distinct non-zero classes
  ;; needs a store, a lake and a backdated manifest to say what one map says.
  (with-temp-data-dir
    (fn []
      (let [st (sub/fresh-store)]
        (with-redefs [files/report
                      (constantly
                       {:files [{:file "a.csv" :bytes 700 :class :reclaimable}
                                {:file "b.csv" :bytes 200 :class :orphaned}
                                {:file "c.csv" :bytes 100 :class :current}
                                {:file "d.csv" :bytes 1   :class :in-window}]
                        :doomed [{:file "a.csv" :bytes 700 :class :reclaimable}
                                 {:file "b.csv" :bytes 200 :class :orphaned}]
                        :reclaimable-bytes 900
                        :total-bytes 1001})]
          (let [f (:files (#'srv/storage-payload st))]
            (is (= 4 (:count f)) "every row is counted, not only the doomed ones")
            (is (= 1001 (:total-bytes f)))
            (is (= 900 (:reclaimable-bytes f)) "over BOTH doomed classes")
            (is (= 2 (:reclaimable-count f))
                "the count of `:doomed` rows — and an integer, not the rows. Each row
                 carries file/uri/bytes/name/connectedAs/class/object/ts/at, and
                 `.length` is all the shell ever read off them")
            (is (= {:reclaimable 1 :orphaned 1 :current 1 :in-window 1} (:classes f))
                "and one count per class, which is what lets the panel say WHY nothing
                 can be freed instead of guessing")
            (is (nil? (:doomed f))
                "the rows themselves do not travel — ~2 MB of JSON on every panel open
                 at the scale these docstrings reason about, for one number")))))))

(deftest the-files-reclaim-route-answers-through-the-URL
  ;; `c/store` is rebound for its four siblings' reason: `handler` binds
  ;; `(store)` before the `cond` for every route, so without it asking for a
  ;; reclaim opens the user's real Datalevin store and seeds it.
  (with-temp-data-dir
    (fn []
      (with-redefs [c/store (delay (sub/fresh-store))]
        (.mkdirs (files/dir))
        (spit (io/file (files/dir) "placed.csv") "x\n")
        (let [w    (atom ::never-called)
              real files/reclaim!]
          ;; Wrapped rather than stubbed — the route still runs the real reclaim
          ;; against the real directory, and this additionally catches the window
          ;; it was handed. Not a wiring detail: the plan for this task wrote the
          ;; test constant `nothing-retained` into the route, and that window
          ;; retains nothing, so the first POST after a table was deleted would
          ;; take a file undo can still reach.
          (with-redefs [files/reclaim! (fn [st win] (reset! w win) (real st win))]
            (let [r (srv/handler {:request-method :post :uri "/api/storage/reclaim-files" :body nil})
                  b (when (= 200 (:status r)) (json/read-str (:body r) :key-fn keyword))]
              (is (= 200 (:status r)))
              (is (= [] (:reclaimed b))
                  "nothing was reclaimable, and the answer says so with an empty list")
              (is (= 0 (:freed b))
                  "and 0 bytes went, as a number the panel can draw rather than a key it
                   has to sum for itself")
              (is (= files/default-window @w)
                  "under the window loci SHIPS, not one a caller or a test chose")))
          (is (.exists (io/file (files/dir) "placed.csv"))
              "and the hand-placed file survived, which is the one thing this route
               may never get wrong")
          (is (= 404 (:status (srv/handler {:request-method :get :uri "/api/storage/reclaim-files"})))
              "a GET falls through to the 404 — method-gated because it deletes, exactly
               as /api/storage/reclaim is and for its reason"))))))

(deftest the-files-reclaim-route-answers-what-actually-went-and-not-what-it-tried
  ;; `files/reclaim!` answers one row per CANDIDATE and a failed delete is
  ;; shape-identical to a done one apart from `:error` — `:bytes` is on both,
  ;; because the row describes the file and not the outcome. So the obvious sum
  ;; at any caller reports files still on disk as freed.
  ;;
  ;; **That sum is no longer this namespace's to do.** It was, for one commit;
  ;; `files/reclaim!` now answers `{:files … :freed …}` computed beside the
  ;; deletes, as `lakegc/reclaim!` always has, and this route hands it through.
  ;; So what is left to hold here is the ENVELOPE: `:freed` travels rather than
  ;; being recomputed from `:reclaimed`, and every candidate row travels with it.
  ;; The property itself — 140 summed against 100 actually freed — is pinned at
  ;; the mechanism, in `loci.files-test`, against a real unwritable directory.
  ;;
  ;; Stubbed and not staged, because the failure this pins needs a delete that
  ;; FAILS, and the only way to arrange one for real is an unwritable directory,
  ;; which passes green under root.
  (with-temp-data-dir
    (fn []
      (with-redefs [c/store (delay (sub/fresh-store))
                    files/reclaim!
                    (fn [_ _] {:freed 100
                               :files [{:file "went.csv"  :bytes 100}
                                       {:file "stuck.csv" :bytes 40
                                        :error "loci could not delete stuck.csv from /files — AccessDeniedException"}]})]
        (let [b (json/read-str (:body (srv/handler {:request-method :post
                                                    :uri "/api/storage/reclaim-files"
                                                    :body nil}))
                               :key-fn keyword)]
          (is (= 100 (:freed b))
              "the number the mechanism computed travels — 140 is what the rows sum
               to, and a route re-deriving it from `:reclaimed` puts 40 bytes that
               are still on disk into a figure a person reads as freed")
          (is (= 2 (count (:reclaimed b)))
              "and the file it could not delete is still reported, not filtered out of
               a shorter vector a caller would read as a completed sweep")
          (is (= ["loci could not delete stuck.csv from /files — AccessDeniedException"]
                 (mapv :error (filter :error (:reclaimed b))))
              "carrying its own sentence, which is where *which and why* is answered"))))))

(deftest mold-payload-says-why-a-table-cannot-be-read
  ;; `:unusable` is the sentence every reader shares; `:reclaimed` stays beside
  ;; it because the shell formats that instant itself (`toLocaleString`) and a
  ;; baked ISO string in prose cannot be localised. Phase 2 decides which wins.
  (lf/with-reclaimed-table
    (fn [st]
      (let [p (srv/mold-payload st no-memory "tbl:gone" nil)]
        (is (:reclaimed p) "the raw instant survives, for the shell to format")
        (is (re-find #"reclaimed on " (:unusable p)) "and the sentence is there too")
        (is (string? (json/write-str p)) "and the whole payload still serialises")))))

;; ---------------------------------------------------------------------------
;; /api/source/describe, /api/source/connect and /api/source/refresh
;;
;; A nested `with-temp-lake` inside the namespace's `:once` one, for the reason
;; the storage tests above give: these materialise real lake tables under ids
;; another test in this file could also mint, and `lake/create-from-relation!`
;; names a directory after the id — a shared lake makes two suites' `tbl:a`
;; the same table.
;; ---------------------------------------------------------------------------

(defn- source-csv
  "A `file://` URI naming a fresh temp CSV holding `text`.

   The path is `(subs uri 7)`, which is how `loci.source-test` rewrites a file
   under a connected source to make a `refresh!` see something new."
  [text]
  (let [f (java.io.File/createTempFile "api-src-" ".csv")]
    (spit f text)
    (str "file://" (.getAbsolutePath f))))

(deftest source-describe-previews-without-committing
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            d  (srv/source-describe st {:uri (source-csv "Total Revenue ($)\n5\n") :format :csv})]
        (is (= [:total_revenue] (mapv :col (:cols d))))
        (is (empty? (sub/objects st)) "describe commits nothing — it IS the dry run")))))

(deftest source-connect-then-refresh-over-the-api
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            u  (source-csv "a\n1\n")
            c  (srv/source-connect! st {:uri u :format :csv :mode :materialise :title "A"})]
        (is (nil? (:error c)))
        (is (= "tbl:a" (:openId c)))
        (is (:state c) "connect returns the state payload, like every other mutating route")
        (spit (subs u 7) "a\n1\n2\n")
        (let [r (srv/source-refresh! st "tbl:a")]
          (is (= 2 (:rows r)))
          (is (:state r)))))))

(deftest source-connect-returns-an-error-map-rather-than-throwing
  ;; `loci.source`'s three entry points all throw — a bad location, an unknown
  ;; format, two headers that collapse to one column — and `handler` has no
  ;; top-level catch, so without the `try` here /api/source/connect answers a
  ;; missing file with a stack trace. Every other mutating route in this file
  ;; answers `{:error …}`; all three of these must too.
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            c  (srv/source-connect! st {:uri "file:///no/such.csv" :format :csv})]
        (is (:error c) "a bad location is an error map, not a 500")
        (is (empty? (sub/objects st)) "and nothing was committed on the way to it")
        (is (:error (srv/source-describe st {:uri "file:///no/such.csv" :format :csv}))
            "describe answers the same way")
        (is (:error (srv/source-refresh! st "tbl:never-connected"))
            "and so does a refresh of an id no source ever landed")))))

(deftest source-connect-accepts-a-format-as-a-json-string
  ;; `body-json` keywordises a map's KEYS and leaves its VALUES alone, so a
  ;; shell POST carries `{:format "csv"}`. `sourcespec/relation` looks `format`
  ;; up in `readers`, which is keyed by keyword, and a string misses every key —
  ;; the refusal is *loci does not know the format "csv"*, about the one format
  ;; it certainly does know.
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            c  (srv/source-connect! st {:uri (source-csv "a\n1\n")
                                        :format "csv" :mode "materialise" :title "S"})]
        (is (nil? (:error c)))
        (is (= "tbl:s" (:openId c)))))))

(deftest the-mode-is-the-facades-decision-and-the-route-does-not-repeat-it
  ;; `:mode` arrives from JSON as a string exactly as `:format` does, and is
  ;; deliberately NOT coerced here: `loci.source/connect!` normalizes it and
  ;; refuses an unknown one by name, precisely because a silently-defaulted mode
  ;; materialises a file the user asked not to copy. Both halves are asserted,
  ;; because only the second can tell the difference — a route-level
  ;; `(update :mode keyword)` still lands the passthrough below, and only the
  ;; refusal shows the decision having moved: it would name `:nonsense`, a
  ;; keyword the caller never typed and cannot find in their own request.
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            c  (srv/source-connect! st {:uri (source-csv "a\n1\n2\n")
                                        :format "csv" :mode "passthrough" :title "P"})]
        (is (nil? (:error c)))
        (is (= :passthrough (:mode c)) "the facade normalized the string it was handed")
        (is (= :source (:store (:value (sub/object st "tbl:p"))))
            "and a passthrough handle landed — no bytes were copied")
        (let [bad (srv/source-connect! st {:uri (source-csv "a\n1\n") :format "csv"
                                           :mode "nonsense" :title "N"})]
          (is (str/includes? (str (:error bad)) "\"nonsense\"")
              "the refusal names what the caller typed, not a keyword the route made")
          (is (nil? (sub/object st "tbl:n")) "and refused before committing anything"))))))

(deftest the-source-routes-answer-through-the-URL
  ;; Handler tests cannot see a route typo, and these three are otherwise only
  ;; ever called as functions. `c/store` is a delay behind an ordinary var, so
  ;; rebinding it means the user's Datalevin store is never opened — the trick
  ;; `the-materialize-route-answers-through-the-URL` uses.
  ;;
  ;; It also asserts the JSON round-trip, which the function tests cannot: a
  ;; `:col` is a keyword in Clojure and `"total_revenue"` by the time the shell
  ;; reads it.
  (lf/with-temp-lake
    (fn []
      (let [st    (sub/fresh-store)
            post! (fn [uri body]
                    (with-redefs [c/store (delay st)]
                      (json/read-str (:body (srv/handler
                                             {:uri uri :request-method :post
                                              :body (java.io.StringReader. (json/write-str body))}))
                                     :key-fn keyword)))
            _     (sub/commit! st {:op :put :id "space:n"
                                   :value {:id "space:n" :kind :space :title "N"
                                           :value {:intent "i" :cells []}}})
            u     (source-csv "Total Revenue ($)\n1\n")
            d     (post! "/api/source/describe" {:uri u :format "csv"})]
        (is (= ["total_revenue"] (mapv :col (:cols d))) "describe round-trips as JSON")
        (is (nil? (sub/object st "tbl:sales")) "and committed nothing")
        (let [c (post! "/api/source/connect" {:uri u :format "csv" :mode "materialise"
                                              :title "Sales" :space "space:n"})]
          (is (nil? (:error c)))
          (is (= "tbl:sales" (:openId c)) "the shell's openObject id")
          (is (:state c) "and the state payload it applies")
          (is (= [{:ref "tbl:sales"}] (nb/cells-of (sub/object st "space:n")))
              "`:space` still appends a cell — `import-csv!`'s shape, kept"))
        (spit (subs u 7) "Total Revenue ($)\n1\n2\n")
        (let [r (post! "/api/source/refresh" {:id "tbl:sales"})]
          (is (= 2 (:rows r)))
          (is (:state r)))
        (is (:error (post! "/api/source/connect" {:uri "file:///no/such.csv" :format "csv"}))
            "and a bad location comes back as a message, never a 500")))))

(deftest source-connect-with-no-mode-defaults-in-one-place-only
  ;; The default lives in `loci.source/connect!`. This pins that the route does
  ;; not quietly grow a second one — which would interact badly with the route
  ;; deliberately NOT coercing `:mode`, since a route-side default would be a
  ;; keyword the caller never sent sitting beside a validator built to name what
  ;; they did send.
  (lf/with-temp-lake
    (fn []
      (let [st (sub/fresh-store)
            f  (doto (java.io.File/createTempFile "nomode-" ".csv") (spit "a\n1\n"))
            c  (srv/source-connect! st {:uri (str "file://" (.getAbsolutePath f))
                                        :format "csv" :title "NoMode"})]
        (is (nil? (:error c)))
        (is (= :materialise (:mode c)) "the facade's default, reported as a keyword")
        (is (= :lake (:store (:value (sub/object st (:openId c)))))
            "and it really materialised")))))

(deftest concurrent-imports-of-one-title-keep-their-own-rows
  ;; Measured 2026-08-19 with a clock-derived filename: 20 concurrent posts of
  ;; one title minted 20 distinct table objects — `mint-id` and `connect-lock`
  ;; did their job — but the 20 writes landed on ONE file at millisecond
  ;; resolution, and every table afterwards held whichever body was written
  ;; last. Distinct ids pointing at one file is worse than a collision that
  ;; errors, because it looks like success.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
       (fn []
        (let [st  (sub/fresh-store)
            n   12
            rs  (mapv deref
                      (mapv (fn [i]
                              (future (srv/import-csv! st "Race"
                                                       (str "a\n" i "\n") nil)))
                            (range n)))
            ids (mapv :openId rs)]
        (is (every? nil? (map :error rs)) "every import succeeds")
        (is (= n (count (distinct ids))) "and each gets its own id")
        (let [vals (->> ids
                        (map #(sub/object st %))
                        (map #(tbl/hydrate @lake/lake %))
                        (mapcat :value)
                        (map :a)
                        set)]
          (is (= n (count vals))
              (str "each table must hold the rows its OWN request carried; got "
                   (count vals) " distinct values across " n " tables")))))))))

(deftest importing-one-file-twice-writes-it-once-and-says-so
  ;; Content addressing, arriving early from the big-content design's rule for
  ;; blobs. Nothing deletes <LOCI_DATA>/files, so a unique-per-call name would
  ;; keep a copy per import forever; a name that IS the content keeps one. And
  ;; because the name cannot be rewritten with different bytes, a loci://
  ;; passthrough source re-reading it on every query is safe.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                csv "region,revenue\nEMEA,1\n"
                a   (srv/import-csv! st "Sales" csv nil)
                b   (srv/import-csv! st "Sales again" csv nil)
                c   (srv/import-csv! st "Different" "region,revenue\nAPAC,2\n" nil)
                dir (io/file (sub/data-dir) "files")]
            (is (every? nil? (map :error [a b c])))
            (is (nil? (:duplicateOf a)) "the first import is not a duplicate")
            (is (= (:openId a) (:duplicateOf b))
                "the second names the table the first made, rather than refusing")
            (is (nil? (:duplicateOf c)) "different bytes are not a duplicate")
            (is (not= (:openId a) (:openId b)) "but it still makes its own table")
            (is (= 2 (count (.listFiles dir)))
                "two distinct bodies, two files — the repeat wrote nothing")))))))

(deftest a-hashed-name-survives-a-one-letter-title
  ;; The bug the previous naming scheme had: File/createTempFile refuses a
  ;; prefix under three characters, and "C" is a title this file already uses.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [r (srv/import-csv! (sub/fresh-store) "C" "region,revenue\nEMEA,1\n" nil)]
            (is (nil? (:error r)))
            (is (some? (:openId r)))))))))

(deftest mold-payload-says-which-source-state-a-table-is-in
  ;; The shell branches on this rather than on `unusable`'s wording. Reading a
  ;; state out of an English sentence is what `hydrate` deliberately does not do.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                f   (io/file dir "live.csv")
                _   (spit f "a,b\n1,x\n")
                uri (str "loci://files/" (.getName f))
                r   (src/connect! st {:uri uri :format :csv
                                      :mode :passthrough :title "Live"})
                p   (srv/mold-payload st no-memory (:id r) nil)]
            (is (= "live" (get-in p [:source :state])) "a healthy passthrough reads live")
            (is (= uri    (get-in p [:source :uri])))
            (is (= "passthrough" (get-in p [:source :mode])))
            (.delete f)
            (let [p2 (srv/mold-payload st no-memory (:id r) nil)]
              (is (= "unavailable" (get-in p2 [:source :state])))
              (is (string? (:unusable p2)) "the sentence still ships beside it"))
            (spit f "c,d\n1,x\n")
            (let [p3 (srv/mold-payload st no-memory (:id r) nil)]
              (is (= "drift" (get-in p3 [:source :state]))))))))))

(deftest a-materialised-source-is-stored-not-live
  ;; Refresh applies to any table with a recipe; the LIVE badge only to
  ;; passthrough. A materialised source must be distinguishable from both a
  ;; passthrough one and a table nobody connected.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                _   (spit (io/file dir "m.csv") "a\n1\n")
                r   (src/connect! st {:uri "loci://files/m.csv" :format :csv
                                      :mode :materialise :title "Stored"})
                p   (srv/mold-payload st no-memory (:id r) nil)]
            (is (= "stored" (get-in p [:source :state])))
            (is (= "materialise" (get-in p [:source :mode])))
            (is (seq (:rendered p)) "and it still renders its rows")))))))

(deftest a-table-nobody-connected-has-no-source-block
  (let [st (store-with-table)
        p  (srv/mold-payload st no-memory "tbl:t" nil)]
    (is (nil? (:source p)) "absent, not a block saying nothing")))

(deftest a-code-view-of-a-source-still-says-it-is-a-source
  ;; Four branches of `mold-payload` can render a table. A broken passthrough
  ;; source must not render silently empty just because it was reached through
  ;; a custom `app:` or `view:` id instead of the table's default view —
  ;; `make-view!` and `describe!` both refuse anything whose rows are not
  ;; tabular, so both are real doors onto a connected source, not a hypothetical
  ;; one. `unavailable`/`drift` genuinely cannot reach these branches — the
  ;; `(tbl/unusable o)` clause is checked first in `mold-payload` — but `stored`
  ;; can, and nothing proved `source-block` behaved the same there as from the
  ;; default `:else` branch.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                f   (io/file dir "cv.csv")
                _   (spit f "a,b\n1,x\n")
                r   (src/connect! st {:uri "loci://files/cv.csv" :format :csv
                                      :mode :passthrough :title "Viewed"})
                m   (spit (io/file dir "cv-stored.csv") "a,b\n1,x\n")
                rm  (src/connect! st {:uri "loci://files/cv-stored.csv" :format :csv
                                      :mode :materialise :title "Stored, viewed"})]
            (with-redefs [agent/make-applet   (fn [& _] "el.textContent = data.length")
                          agent/describe-view (fn [& _] {:label "as table" :kind "table"})]
              (let [av  (srv/make-view! st (:id r) "show it")
                    dv  (srv/describe! st (:id r) "chart it")
                    avm (srv/make-view! st (:id rm) "show it")
                    dvm (srv/describe! st (:id rm) "chart it")]
                (is (nil? (:error av)))
                (is (nil? (:error dv)))
                (is (nil? (:error avm)))
                (is (nil? (:error dvm)))
                (let [ap  (srv/mold-payload st no-memory (:id r) (:view av))
                      vp  (srv/mold-payload st no-memory (:id r) (:view dv))
                      apm (srv/mold-payload st no-memory (:id rm) (:view avm))
                      vpm (srv/mold-payload st no-memory (:id rm) (:view dvm))]
                  (is (= "live" (get-in ap [:source :state]))
                      "the app: branch carries the block too")
                  (is (= "live" (get-in vp [:source :state]))
                      "the view: branch carries the block too")
                  (is (= "stored" (get-in apm [:source :state]))
                      "stored reaches the app: branch too, not just :else")
                  (is (= "stored" (get-in vpm [:source :state]))
                      "stored reaches the view: branch too, not just :else"))))))))))

(deftest a-hand-written-source-with-a-bad-mode-costs-one-key-not-the-page
  ;; `connect!`'s validator refuses a mode that is not :materialise or
  ;; :passthrough, so this shape cannot arrive through the API. It can arrive
  ;; through a REPL commit, which CLAUDE.md names as a way to reach storage —
  ;; and `(name nil)` would take out every read of the object rather than the
  ;; one key that cannot be answered.
  (let [st (sub/fresh-store)]
    (doseq [bad [nil 5 {:a 1}]]
      (sub/commit! st {:op :put :id "tbl:handmade"
                       :value {:id "tbl:handmade" :kind :table :title "Hand"
                               :value [{:a 1}]
                               :source {:uri "loci://files/x.csv" :format :csv :mode bad}}})
      (let [p (srv/mold-payload st no-memory "tbl:handmade" nil)]
        (is (= "stored" (get-in p [:source :state])) (str "still answers for mode " (pr-str bad)))
        (is (nil? (get-in p [:source :mode])) "and says nothing rather than throwing")
        (is (seq (:rendered p)) "and the rows still render")))))

(deftest a-reclaimed-materialised-source-agrees-with-itself
  ;; source-block must mirror tbl/unusable's precedence: both are readings of
  ;; one hydrate and must never be able to disagree. Reproduced by the
  ;; reviewer against ordinary use — connect materialise, refresh, the old
  ;; lake version ages past the GC window, browse it with ?at= — and before
  ;; the reclaimed clause this cond answered "stored" while :unusable and
  ;; :reclaimed both said the rows were gone.
  ;;
  ;; 100 rows, not fewer: below ~10-20 rows DuckLake inlines the insert into
  ;; catalog.duckdb, so the table owns no Parquet, reclaim! has nothing to
  ;; delete, and the read afterwards succeeds — the same trap
  ;; `lake-fixture/with-reclaimed-table` documents for tbl:gone. The assert on
  ;; reclaim!'s return is what makes a false green impossible here too.
  (lf/with-temp-lake
    (fn []
      (with-temp-data-dir
        (fn []
          (let [st  (sub/fresh-store)
                dir (doto (io/file (sub/data-dir) "files") .mkdirs)
                f   (io/file dir "gc.csv")
                _   (spit f (str "a\n" (str/join "\n" (range 100)) "\n"))
                r   (src/connect! st {:uri "loci://files/gc.csv" :format :csv
                                      :mode :materialise :title "GC me"})
                h   (:value (sub/object st (:id r)))]
            (is (= 1 (count (lf/reclaim! @lake/lake (:table h))))
                "no Parquet was deleted — the test would prove nothing")
            (let [p (srv/mold-payload st no-memory (:id r) nil)]
              (is (= "reclaimed" (get-in p [:source :state]))
                  "source.state must agree with :unusable and :reclaimed, not report stored")
              (is (:reclaimed p) "the payload still says so rather than showing an empty table")
              (is (str/includes? (:unusable p) "reclaimed")
                  "the sentence and the structural state name the same failure"))))))))

;; ---------------------------------------------------------------------------
;; GET /api/source/files — the route and its envelope. What is IN the directory
;; is `loci.files`'s rule and is tested in `loci.files-test` beside it; what is
;; here is that the URL reaches it and that the payload keeps its shape.
;; ---------------------------------------------------------------------------

(deftest source-files-answers-through-the-URL
  ;; A handler test cannot catch a typo'd URI, per `the-source-routes-answer-
  ;; through-the-URL` above. `c/store` is a delay behind an ordinary var and
  ;; `handler` binds `(store)` before the `cond` for every route, so without
  ;; this rebind the mere act of asking for a file listing opens the user's
  ;; Datalevin store and runs `c/seed!` — inside a `with-temp-data-dir` that
  ;; then deletes it, leaving a process-wide realized delay over an unlinked
  ;; env. Its four siblings all rebind for exactly this reason.
  (with-temp-data-dir
    (fn []
      (with-redefs [c/store (delay (sub/fresh-store))]
        (let [dir (doto (io/file (sub/data-dir) "files") .mkdirs)]
          ;; Written in REVERSE lexical order on purpose. `File.listFiles`
          ;; promises no order and answers creation order here (measured
          ;; 2026-08-21), so a fixture written a-then-b comes back sorted
          ;; whether or not anything sorted it — which is how the sort in
          ;; `files/listing` went five tests without an assertion that could
          ;; see it.
          (spit (io/file dir "hand-placed.csv") "b\n2\n")
          (spit (io/file dir "72b441a9c3e05f18.csv") "a\n1\n")
          (let [r (json/read-str (:body (srv/handler {:uri "/api/source/files"}))
                                  :key-fn keyword)]
            (is (= ["72b441a9c3e05f18.csv" "hand-placed.csv"] (mapv :file (:files r)))
                "the route answers, under :files, in name order and not in disk order")))))))

;; ---------------------------------------------------------------------------
;; the boot-time .partial sweep (Task 6)
;; ---------------------------------------------------------------------------

(deftest boot-sweeps-an-abandoned-partial-and-leaves-one-in-flight
  ;; `-main` ends in `@(promise)` and can never be called by a test, so what
  ;; boot does is testable only through the functions boot calls — the same
  ;; reason `start-chunk-sweep!` and `close-lake-at-exit!` are their own vars.
  (with-temp-data-dir
    (fn []
      (let [p    (doto (io/file (sub/data-dir) "files" ".partial") .mkdirs)
            old  (io/file p "abandoned")
            live (io/file p "in-flight")]
        (spit old "region,rev") (spit live "region,rev")
        (.setLastModified old (- (System/currentTimeMillis) (* 25 60 60 1000)))
        (is (= ["abandoned"] (srv/sweep-partials-at-boot!))
            "the server's own boot step sweeps, and says what it swept")
        (is (not (.exists old)))
        (is (.exists live) "an upload that started an hour ago is not garbage")))))

;; ---------------------------------------------------------------------------
;; POST /api/source/upload — chunked (Task 7)
;;
;; The rules live in `loci.files`, which owns the directory they are about, and
;; are exercised here through `srv/source-upload!` because the seam a person
;; reaches is the route: the sequence, the header, the raw body and the
;; envelope only exist together.
;; ---------------------------------------------------------------------------

(deftest an-upload-arrives-in-chunks-and-lands-as-one-content-addressed-file
  (with-temp-data-dir
    (fn []
      (let [a (srv/source-upload! (sub/fresh-store) {:id "up-1" :seq 0 :name "sales.csv"} "region,revenue\n")
            b (srv/source-upload! (sub/fresh-store) {:id "up-1" :seq 1 :name "sales.csv" :final true} "EMEA,10\n")]
        (is (nil? (:error a)))
        (is (nil? (:uri a)) "no uri until the last chunk")
        (is (= 15 (:bytes a)) "and the caller is told how much of the file has landed")
        (is (nil? (:error b)))
        (is (re-matches #"loci://files/[0-9a-f]{16}\.csv" (:uri b)))
        (is (= (files/uri (:file b)) (:uri b))
            "one spelling of the location, minted by loci.files and not by the route")
        (is (= "region,revenue\nEMEA,10\n" (slurp (io/file (files/dir) (:file b))))
            "the pieces are in order and nothing is lost")
        ;; Not merely SOME 16 hex characters: the hash of the WHOLE file. A
        ;; finalize that hashed only the last chunk — or the first — would still
        ;; match the regex above and still produce one plausible file.
        (is (= (files/content-hash "region,revenue\nEMEA,10\n") (subs (:file b) 0 16))
            "the name is the hash of everything that arrived, not of a chunk of it")
        (is (= "sales.csv" (get (files/names) (:file b)))
            "and the name it arrived with is remembered")
        (is (empty? (.list (io/file (files/dir) ".partial")))
            "the partial is gone once it lands")))))

(deftest an-upload-whose-bytes-are-already-a-table-says-which-one
  ;; The upload door drew no duplicate note until 2026-08-22, and the picked-row
  ;; door has drawn one since phase 2. Same panel, same preview, same Connect:
  ;; upload `sales.csv`, connect it, drag the same file in again, and the hash
  ;; matches — so `finish-upload!` keeps the file it has and `:existed` says so
  ;; — while the preview said nothing and Connect made a second table from the
  ;; same rows in silence. An upload IS an import.
  (with-temp-data-dir
    (fn []
      (let [st   (sub/fresh-store)
            rows "region,revenue\nEMEA,10\n"
            a    (srv/source-upload! st {:id "dup-a" :seq 0 :name "sales.csv" :final true} rows)]
        (is (not (contains? a :connectedAs))
            "absent and not nil while nothing is connected to it — `listing`'s rule for
             this key, so the shell has one shape to mean nothing")
        (sub/commit! st {:op :put :id "tbl:sales"
                         :value {:id "tbl:sales"
                                 :source {:uri (:uri a) :mode :passthrough}
                                 :value  {:store :source :uri (:uri a) :format :csv}}})
        (let [b (srv/source-upload! st {:id "dup-b" :seq 0 :name "sales again.csv" :final true} rows)]
          (is (:existed b)
              "the premise: identical bytes, so nothing was written and this is the
               moment a person is most likely to make a second table from one file")
          (is (= "tbl:sales" (:connectedAs b))
              "and the answer names the table those bytes already are")
          (is (= (files/connected-as st (:uri b)) (:connectedAs b))
              "through `files/connected-as`, which is the same rule the listing's rows
               carry — a second scan here would be the duplicate question answered in
               two places, which is what that function exists to prevent"))))))

(deftest a-chunk-out-of-sequence-is-refused-rather-than-silently-misordered
  (with-temp-data-dir
    (fn []
      (srv/source-upload! (sub/fresh-store) {:id "oos" :seq 0 :name "x.csv"} "a\n")
      (let [part (io/file (files/dir) ".partial" "oos")
            r    (srv/source-upload! (sub/fresh-store) {:id "oos" :seq 5 :name "x.csv"} "b\n")]
        ;; **First, and not last.** This is the assertion that separates
        ;; "refused" from "accepted in the wrong order" — both of which could
        ;; answer an `:error` — and putting it after the message checks would
        ;; mean a sabotage that returns no error at all never reaches it.
        (is (= "a\n" (slurp part))
            "the refused bytes are not appended")
        (is (:error r))
        (is (nil? (:uri r)))
        ;; The phrase alone is a weak assertion — plenty of failures could put
        ;; those three words in a sentence. The NUMBERS are the part only the
        ;; sequence check knows: which chunk came, and which one was due.
        ;; `str` rather than the raw value so that a missing error FAILS here
        ;; instead of throwing out of the deftest.
        (is (re-find #"chunk 5 arrived out of sequence" (str (:error r))))
        (is (re-find #"expected 1" (str (:error r)))
            "and it says what would have been accepted, so a client can resume")
        (let [ok (srv/source-upload! (sub/fresh-store) {:id "oos" :seq 1 :name "x.csv" :final true} "b\n")]
          (is (= "a\nb\n" (slurp (io/file (files/dir) (:file ok))))
              "and a refusal does not poison the upload: the chunk that was due still lands"))))))

(deftest an-upload-that-restarts-under-the-same-id-starts-over-rather-than-appending
  ;; An abandoned upload leaves its entry in the expected-sequence map, and that
  ;; entry outlives the browser that made it — there is no eviction and the
  ;; design says there should not be one. Two things follow, and neither has
  ;; another test: chunk 0 must not be checked against the leftover number, or
  ;; a reused id is refused forever by a state nothing on disk backs; and chunk
  ;; 0 must truncate rather than append, or the dead attempt lands glued to the
  ;; front of the good file, under a hash OF the corrupt bytes.
  (with-temp-data-dir
    (fn []
      (srv/source-upload! (sub/fresh-store) {:id "retry" :seq 0 :name "x.csv"} "DEAD")
      (srv/source-upload! (sub/fresh-store) {:id "retry" :seq 1 :name "x.csv"} "ATTEMPT")
      (let [a (srv/source-upload! (sub/fresh-store) {:id "retry" :seq 0 :name "x.csv"} "a,b\n")
            r (srv/source-upload! (sub/fresh-store) {:id "retry" :seq 1 :name "x.csv" :final true} "1,2\n")]
        (is (nil? (:error a)) "beginning again is never out of sequence")
        (is (= 4 (:bytes a)) "and the working file holds only what the retry has sent")
        (is (= "a,b\n1,2\n" (slurp (io/file (files/dir) (:file r))))
            "the abandoned attempt is gone, not glued to the front")
        (is (= (files/content-hash "a,b\n1,2\n") (subs (:file r) 0 16))
            "so the retry is named by its own bytes and dedups against the same file uploaded any other way")))))

(deftest re-uploading-identical-bytes-is-discarded-rather-than-rewritten
  (with-temp-data-dir
    (fn []
      (let [one (srv/source-upload! (sub/fresh-store) {:id "dup1" :seq 0 :name "a.csv" :final true} "a\n1\n")
            f   (io/file (files/dir) (:file one))
            ;; **The file count cannot tell "discarded" from "overwritten".**
            ;; One file remains either way and the uri is equal either way — and
            ;; overwriting is the thing the spec forbids outright, because a
            ;; `loci://` passthrough source re-reads its file on every query. A
            ;; sentinel is what makes the two outcomes different: if the second
            ;; upload rewrites the destination, this is gone.
            _   (spit f "SENTINEL\n")
            two (srv/source-upload! (sub/fresh-store) {:id "dup2" :seq 0 :name "b.csv" :final true} "a\n1\n")]
        (is (= (:uri one) (:uri two)) "the same bytes are the same file")
        (is (false? (:existed one)))
        (is (true? (:existed two)) "and the second arrival knows it is not new")
        (is (= "SENTINEL\n" (slurp f))
            "a file whose name is its content is never rewritten")
        (is (= 1 (count (remove #(.isDirectory %) (.listFiles (files/dir))))))
        (is (empty? (.list (io/file (files/dir) ".partial")))
            "and the discarded upload leaves no partial behind")
        (is (= "b.csv" (get (files/names) (:file two)))
            "the manifest is last-wins, so the name it most recently arrived under is the one it answers to")))))

(deftest a-chunk-boundary-inside-a-multi-byte-character-survives-byte-exact
  ;; The plan's route sketch was `(slurp (:body req))`, and this is the test
  ;; that says why it could not stay. `slurp` decodes bytes to a String, a chunk
  ;; boundary falls at an arbitrary byte, and a 4 MB slice of a real CSV lands
  ;; mid-character sooner or later. Measured 2026-08-21 on exactly the bytes
  ;; below: slurping the two halves and re-encoding turns 24 bytes into 28 —
  ;; `c3 a9` comes back as `ef bf bd ef bf bd`, one replacement character per
  ;; half. The file is content-addressed, so the corruption lands under a hash
  ;; OF the corrupt bytes and is indistinguishable from a good file ever after.
  ;;
  ;; Written as \u00e9 and \u20ac escapes rather than as the characters
  ;; themselves, so that the test cannot be broken by how this source file is
  ;; decoded — which is the one thing it is about.
  (with-temp-data-dir
    (fn []
      (let [text  "region,caf\u00e9\nEMEA,\u20ac10\n"
            bs    (.getBytes text "UTF-8")
            cut   (inc (count (.getBytes (subs text 0 (str/index-of text "\u00e9")) "UTF-8")))
            head  (java.util.Arrays/copyOfRange bs 0 cut)
            tail  (java.util.Arrays/copyOfRange bs cut (alength bs))
            hex   (fn [^bytes a] (apply str (map #(format "%02x" %) a)))]
        (srv/source-upload! (sub/fresh-store) {:id "utf8" :seq 0 :name "c.csv"} head)
        (let [r      (srv/source-upload! (sub/fresh-store) {:id "utf8" :seq 1 :name "c.csv" :final true} tail)
              landed (.readAllBytes (io/input-stream (io/file (files/dir) (:file r))))]
          (is (java.util.Arrays/equals bs landed)
              (str "byte for byte what was sent: expected " (hex bs) " got " (hex landed)))
          (is (= (files/content-hash text) (subs (:file r) 0 16))
              "and so it is named by the hash of the text itself — which is what makes a pasted CSV and an uploaded one the same file rather than two"))))))

(deftest content-hash-agrees-between-a-string-and-the-file-holding-those-bytes
  ;; Nothing else checks this and everything rests on it. A CSV pasted into
  ;; `/api/import-csv` is hashed as a String; the same CSV uploaded is hashed by
  ;; streaming the completed file, because reading a 200 MB upload into a String
  ;; would undo the chunking the route exists for. If the two shapes disagreed,
  ;; the same bytes would land twice under two names and the dedup this
  ;; directory is built on would fail for exactly the pair a person is most
  ;; likely to produce.
  (let [text (apply str (repeat 9137 "r\u00e9gion,\u20ac,revenue\n"))
        f    (doto (java.io.File/createTempFile "loci-hash" ".csv") .deleteOnExit)]
    (io/copy (.getBytes text "UTF-8") f)
    (is (< 65536 (.length f))
        "longer than the 64 KB read buffer, so the streaming loop runs more than once")
    (is (pos? (mod (.length f) 65536))
        "and not a whole number of buffers, so a loop that digested the WHOLE buffer on its short last read would disagree")
    (is (= (files/content-hash text) (files/content-hash f))
        "a pasted CSV and the same CSV uploaded are one file")
    (is (re-matches #"[0-9a-f]{16}" (files/content-hash f))
        "sixteen hex characters, because this IS the filename")))

(deftest an-upload-id-that-would-escape-the-files-directory-is-refused
  ;; The id arrives in a header from the browser and names a file under
  ;; `.partial`. It is the one caller-supplied string on this route that becomes
  ;; a path, so it is the one that can be a directory traversal.
  (with-temp-data-dir
    (fn []
      ;; **One legitimate upload first, so that `.partial` EXISTS before the bad
      ;; ids are tried.** Without it, `.partial/../escaped.csv` fails on the
      ;; missing directory rather than being refused, and the `escaped.csv`
      ;; assertion below passes for a reason that has nothing to do with the
      ;; guard. Measured 2026-08-21 by dropping the guard: four of the five
      ;; assertions here failed and that one passed anyway.
      (srv/source-upload! (sub/fresh-store) {:id "good" :seq 0 :name "ok.csv"} "a\n")
      (doseq [bad ["../escaped.csv" "a/b" "" nil]]
        (is (re-find #"unusable upload id" (str (:error (srv/source-upload! (sub/fresh-store) {:id bad :seq 0 :name "x.csv"} "a\n"))))
            (str "refused: " (pr-str bad))))
      (is (not (.exists (io/file (files/dir) "escaped.csv")))
          "`.partial/../escaped.csv` resolves to `files/escaped.csv`, and the OS resolves it at write time whether or not Java normalised the path")
      (is (= ["good"] (seq (.list (io/file (files/dir) ".partial"))))
          "and no working file was made under any of the four bad names"))))

(deftest the-upload-route-answers-through-the-URL
  ;; A handler test rather than four more calls to `source-upload!`, for the
  ;; reason its siblings above give: nothing else can catch a typo'd URI. It is
  ;; also the only thing that exercises the header, which is where the metadata
  ;; rides precisely so the body can stay raw bytes. `c/store` is rebound
  ;; because `handler` binds `(store)` before the `cond` for every route.
  (with-temp-data-dir
    (fn []
      (with-redefs [c/store (delay (sub/fresh-store))]
        (let [post (fn [m body]
                     (json/read-str
                      (:body (srv/handler
                              {:uri "/api/source/upload"
                               :request-method :post
                               :headers {"x-loci-upload" (json/write-str m)}
                               :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))}))
                      :key-fn keyword))]
          (post {:id "url1" :seq 0 :name "via-url.csv"} "a,b\n")
          (let [r (post {:id "url1" :seq 1 :name "via-url.csv" :final true} "1,2\n")]
            (is (nil? (:error r)))
            (is (= "a,b\n1,2\n" (slurp (io/file (files/dir) (:file r))))
                "the raw body is the chunk and the header is the metadata")
            (is (= "via-url.csv" (get (files/names) (:file r)))
                "the name off the header reaches the manifest"))
          (let [r (json/read-str (:body (srv/handler {:uri "/api/source/upload"
                                                      :request-method :post
                                                      :headers {}}))
                                 :key-fn keyword)]
            (is (re-find #"X-Loci-Upload" (:error r))
                "a request with no header is told what it is missing, not handed a stack trace")))))))
