# Paged Table Grid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Rows view becomes a table a person can read at 36 columns and 10M rows — paged from the server, sticky-headed, horizontally scrollable, sortable — and the aggregate views stop describing a 2,000-row sample as if it were the table.

**Architecture:** A **window** (`{:limit :cursor :sort :cols}`) travels shell → `/api/mold` → `hydrate` → a new `TableStore/page` method. `rows` and `mold-cap` are untouched, because twelve callers depend on them for samples. Three `page` implementations sit behind one **opaque cursor**, so the shell has one control for every table. Then `mold/mold` learns to hand a viewer the store, and the aggregate viewers move into SQL.

**Tech Stack:** Clojure 1.12, DuckDB/DuckLake, http-kit, vanilla JS in one file, Playwright via `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-25-paged-table-grid-design.md`. Read §2.3 before Task 1 — it records two corrections made while planning (no `:prev`/`:from`/`:to`, and keyset only for natural order).

---

## Ground rules for every task

- **The REPL is the execution surface.** `ToolSearch` for `select:mcp__clojure-mcp__clojure_eval` against the nREPL on port 7888; `:reload` after every edit. Do **not** run `clojure -M -e` to iterate — a cold JVM costs ~25 s. `clojure -M:test` is for one final full-suite run per task.
- **Prove every test bites.** Reintroduce the defect, watch the test go red, and **read the failure output** rather than counting failures. A test that passes either way certifies rather than tests.
- **`grep` is unreliable in this environment on files with very long lines** — `resources/public/index.html` in particular. Use `rg`, or `python3 -c "print(open(f).read().count(s))"`. And do not pass `rg -rn`: `-r` is *replace*, and `rg -rn PATTERN PATH` silently rewrites every match to the letter `n` in the output. Use `rg -n`.
- **Two suites.** `clojure -M:test` does not mean all the tests. Phases 2 and 3 are shell changes and `npm run test:browser` is the only thing that sees them.
- **Say what you measured, and which of two things it establishes** — that the mechanism works, or that a cost is real. A 250-row fixture can only ever establish the first.
- **`data/` is test data.** Use a throwaway `LOCI_DATA` because tests that share a store interfere, not because `data/` needs protecting.
- **A namespaced payload key reaches the shell with its namespace GONE.** `clojure.data.json/write-str` renders `:table/total` as `"total"`, `:table/cols` as `"cols"` and `:page/next` as `"next"`. The shell reads `m.total`, `m.cols`, `m.next` — never `m['table/total']`. This is established convention, already documented at `resources/public/index.html:2038` and `:2149` where `reclaimedHTML` reads the same two keys. Corrected here 2026-08-25 after Task 3 measured the actual wire format; the plan had it wrong in Tasks 7 and 9, and it would have been `undefined` at runtime.

---

## File structure

| File | Tasks | Responsibility after this plan |
|---|---|---|
| `src/loci/tablestore.clj` | 1, 4 | `page` on the protocol; cursor encode/decode; `page-limit`, `sort-spec`, `project-cols` as the shared, store-independent half of a window; `InlineTable/page`; `hydrate-page` |
| `src/loci/lake.clj` | 2 | `Lake/page` — rowid keyset for a materialised handle in natural order, offset for everything else |
| `src/loci/server.clj` | 3, 5 | `/api/mold` reads the window off the query string; `mold-payload` uses it on the rows/cards branch only; `notebook-payload` ships one page per `:ref` cell |
| `resources/public/index.html` | 6–10 | `tableHTML` takes an optional page payload; absolute widths; sticky header; frozen first column; pager; sort; column chooser; localStorage |
| `src/loci/mold.clj` | 11 | `:needs-store` viewers receive `{:store :handle}` |
| `src/loci/content.clj` | 12, 13, 14 | predicates and column choice off `column-samples`; the aggregates in SQL for a Lake handle |
| `test/loci/tablestore_test.clj` | 1, 4 | inline paging, cursor opacity, window clamping, `hydrate-page` |
| `test/loci/lake_test.clj` | 2 | keyset continuity, the tie case, passthrough paging, sort validation |
| `test/loci/server_test.clj` | 3, 5 | route params, payload size, one page per notebook cell |
| `test/loci/content_test.clj` | 12, 13, 14 | **new file** — predicates and aggregates past the mold cap |
| `test/browser/harness.mjs` | 6 | `GRID_FIXTURE` |
| `test/browser/grid.test.mjs` | 6–10 | **new file** — widths, sticky, frozen, pager, sort, chooser |

---

# Phase 1 — the window

Server only. Nothing visible changes; every existing test must still pass.

---

## Task 1: the window's store-independent half, and `InlineTable/page`

**Files:**
- Modify: `src/loci/tablestore.clj` — the `ns` form, the `TableStore` protocol (`:13-40`), `InlineTable` (`:303-318`)
- Modify: `test/loci/tablestore_test.clj`

- [ ] **Step 1: Write the failing tests.**

Append to `test/loci/tablestore_test.clj`:

```clojure
(def ^:private many (mapv (fn [i] {:id i :grp (str "g" (mod i 5)) :note nil}) (range 250)))

(deftest a-window-limit-is-clamped-and-defaulted
  (is (= 100 (tbl/page-limit nil))    "no limit means 100")
  (is (= 100 (tbl/page-limit "")) "a blank query-string limit means 100")
  (is (= 200 (tbl/page-limit 5000))   "200 is the ceiling")
  (is (= 1   (tbl/page-limit 0))      "1 is the floor")
  (is (= 25  (tbl/page-limit "25"))   "a query string arrives as a string"))

(deftest a-cursor-round-trips-and-a-mangled-one-is-page-one
  (is (= {:k 41} (tbl/decode-cursor (tbl/encode-cursor {:k 41}))))
  (is (nil? (tbl/decode-cursor nil)))
  (is (nil? (tbl/decode-cursor "")))
  (is (nil? (tbl/decode-cursor "not-base64-at-all!!"))
      "a bookmarked cursor that rotted restarts the table rather than 500ing")
  (is (nil? (tbl/decode-cursor (tbl/encode-cursor [1 2 3])))
      "a cursor that decodes to something other than a map is not a cursor"))

(deftest inline-pages-forward-to-the-end
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)
        p1 (tbl/page ts h {:limit 100})
        p2 (tbl/page ts h {:limit 100 :cursor (:next p1)})
        p3 (tbl/page ts h {:limit 100 :cursor (:next p2)})]
    (is (= 100 (count (:rows p1))))
    (is (= 0   (:id (first (:rows p1)))))
    (is (= 100 (:id (first (:rows p2)))) "page 2 starts where page 1 stopped")
    (is (= 50  (count (:rows p3))))
    (is (nil?  (:next p3)) "the last page says so, and nothing asks for a fourth")))

(deftest a-window-projects-only-the-columns-it-asks-for
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)]
    (is (= [:id] (keys (first (:rows (tbl/page ts h {:limit 1 :cols ["id"]})))))
        "hiding a column cuts the payload, not only the pixels")
    (is (= [:id :grp :note] (tbl/project-cols h ["nope"]))
        "an unknown column name leaves the projection alone")
    (is (= [:id :grp] (tbl/project-cols h ["grp" "id"]))
        "handle order, not the caller's — q-as names a result set positionally")))

(deftest a-sort-is-validated-against-the-handle
  (let [ts (tbl/inline-table)
        h  (tbl/put-rows! ts "tbl:big" many)]
    (is (= ["id" "desc"] (tbl/sort-spec h ["id" "desc"])))
    (is (= ["id" "asc"]  (tbl/sort-spec h ["id" "sideways"])) "an odd direction is asc")
    (is (nil? (tbl/sort-spec h ["; DROP TABLE" "asc"]))
        "a column the handle does not have is no sort at all")))

(deftest inline-sorts-with-nulls-last-in-both-directions
  (let [ts   (tbl/inline-table)
        h    (tbl/put-rows! ts "tbl:n" [{:v 2} {:v nil} {:v 1}])
        vals #(mapv :v (:rows (tbl/page ts h {:limit 10 :sort %})))]
    (is (= [1 2 nil]  (vals ["v" "asc"])))
    (is (= [2 1 nil]  (vals ["v" "desc"])) "nulls stay last when the sort flips")))
```

- [ ] **Step 2: Run, watch them fail.**

```
clojure -M:test -v loci.tablestore-test
```

Expected: `No such var: tbl/page-limit` and friends. Read the failure — it must be the missing var, not a typo in the test.

- [ ] **Step 3: Implement.**

In `src/loci/tablestore.clj`, add `[clojure.edn :as edn]` to the `ns` `:require`.

Add to the `TableStore` protocol, directly after `rows`:

```clojure
  (page [this handle window]
    "One page of rows for `handle`, plus the cursor that starts the next page.

     `window` may carry `:limit`, `:cursor` (opaque, nil for page 1), `:sort`
     (`[col dir]`) and `:cols` (the columns to project, nil for all).

     => `{:rows [...] :next <cursor-or-nil>}`

     **Separate from `rows` rather than an option on it.** `rows` has twelve
     callers — agent digests, `tools.clj`, `describe` — that want a *sample* for
     a summary, not a page, and `mold-cap` is their default. Teaching `rows` to
     page would push a paging concept into every one of them.

     `:next` is produced by asking storage for `limit + 1` rows: if the extra one
     comes back there is another page, and the cursor is built from the last row
     actually returned. So the last page is known without a count.")
```

Above `(defrecord InlineTable …)`:

```clojure
(def default-limit
  "Rows per page when a window does not say. 100 rather than 60 — the old
   client-side cap — because a page is now the whole of what the browser holds."
  100)

(def max-limit
  "The ceiling a window may ask for. A design decision and not a measured one:
   it is how many rows a person can look at, and 200 of a 36-column table is
   already 7,200 cells."
  200)

(defn page-limit
  "A window's `:limit`, clamped to 1..`max-limit` with `default-limit` for
   absent. Takes a string too, because a window arrives in a query string."
  [n]
  (let [n (if (string? n) (parse-long (str n)) n)]
    (max 1 (min max-limit (or n default-limit)))))

(defn encode-cursor
  "A store's internal page key, as one opaque URL-safe string.

   **Opaque is the contract; base64-of-EDN is only today's encoding.** The key's
   shape differs per handle and per sort — a rowid here, an offset there — and
   the shell has one pager because it never looks. `clojure.edn/read-string`
   and not `read-string`: a cursor arrives from a query string, so it is hostile
   input, and `edn` evaluates nothing."
  [m]
  (.encodeToString (java.util.Base64/getUrlEncoder)
                   (.getBytes (pr-str m) "UTF-8")))

(defn decode-cursor
  "The map `encode-cursor` made, or **nil** for nil, blank, or anything
   malformed.

   nil means page 1, so a bookmarked cursor that no longer parses reopens the
   table at the top rather than throwing. A 500 on a URL somebody saved is the
   worse failure: the table looks broken when only the position was."
  [s]
  (when-not (str/blank? s)
    (try
      (let [m (edn/read-string (String. (.decode (java.util.Base64/getUrlDecoder) ^String s)
                                        "UTF-8"))]
        (when (map? m) m))
      (catch Exception _ nil))))

(defn project-cols
  "The columns a window asks for, in the **handle's** order, or all of them.

   Handle order and not the caller's, because `lake/q-as` names a result set
   positionally: a reordered projection would label every value with its
   neighbour's name. Unknown names are dropped, and dropping all of them means
   all columns — a table with no columns is not something the shell can draw."
  [h cols]
  (if-let [want (some-> (seq cols) (->> (map name) set))]
    (let [kept (filterv #(contains? want (name %)) (:cols h))]
      (if (seq kept) kept (:cols h)))
    (:cols h)))

(defn sort-spec
  "A window's `:sort`, validated against the handle's own columns, or nil.

   **Validation is not politeness.** `:sort` arrives in a query string and
   reaches an `ORDER BY`. `lake/ident` quotes it, so a name cannot break out of
   the identifier — but an unknown one reaches DuckDB as a binder error in the
   middle of a render, and a table that throws because a stale bookmark named a
   dropped column is worse than one that ignores the sort."
  [h [col dir]]
  (when (some? col)
    (let [known (set (map name (:cols h)))]
      (when (contains? known (name col))
        [(name col) (if (= "desc" (str dir)) "desc" "asc")]))))

(defn sort-rows
  "`rows` ordered by a validated `sort-spec`, **nulls last in both directions**.

   Nulls last matches the lake's `ORDER BY … NULLS LAST`. `InlineTable` is the
   parity reference for `Lake`, so a difference here would be a difference every
   paging test has to encode twice."
  [[col dir] rows]
  (let [k    (sub/col-kw col)
        desc (= "desc" dir)]
    (vec (sort-by #(get % k)
                  (fn [a b]
                    (cond (and (nil? a) (nil? b)) 0
                          (nil? a)  1
                          (nil? b) -1
                          desc     (compare b a)
                          :else    (compare a b)))
                  rows))))
```

Then add the `page` implementation inside `defrecord InlineTable`, directly after `rows`:

```clojure
  (page [_ h window]
    (let [lim  (page-limit (:limit window))
          i    (or (:i (decode-cursor (:cursor window))) 0)
          srt  (sort-spec h (:sort window))
          proj (project-cols h (:cols window))
          got  (->> (cond->> (:data h) srt (sort-rows srt))
                    (drop i) (take (inc lim)) vec)
          more (> (count got) lim)]
      {:rows (mapv #(select-keys % proj) (take lim got))
       :next (when more (encode-cursor {:i (+ i lim)}))}))
```

- [ ] **Step 4: Green.**

```
clojure -M:test -v loci.tablestore-test
```

Expected: PASS, including the pre-existing `inline-pages` and `hydrate-caps-a-large-table` — `rows` was not touched and must not have moved.

- [ ] **Step 5: Prove they bite.** One at a time, restoring after each:

| break | test that must go red |
|---|---|
| `(max 1 (min max-limit …))` → `(or n default-limit)` | `a-window-limit-is-clamped-and-defaulted` |
| `decode-cursor` drops the `try`/`catch` | `a-cursor-round-trips-and-a-mangled-one-is-page-one` |
| `page` asks for `(take lim got)` instead of `(inc lim)` | `inline-pages-forward-to-the-end` — `:next` is non-nil on the last page |
| `sort-spec` returns `[col dir]` without the `contains?` check | `a-sort-is-validated-against-the-handle` |
| `sort-rows` negates the whole comparator for `desc` | `inline-sorts-with-nulls-last-in-both-directions` |

**Read each failure.** The `(inc lim)` break is the one most likely to fail for the wrong reason — confirm the message names `:next`, not a count.

- [ ] **Step 6: Commit.**

```bash
git add src/loci/tablestore.clj test/loci/tablestore_test.clj
git commit -m "feat: a window, an opaque cursor, and the page an inline table gives back"
```

---

## Task 2: `Lake/page` — a seek in natural order, an offset otherwise

**Files:**
- Modify: `src/loci/lake.clj` — the `(extend-type Lake tbl/TableStore …)` block at `:1215-1233`
- Modify: `test/loci/lake_test.clj`

**Read first:** `from-of` (`lake.clj:1080`) and `q-as` (`lake.clj:133`). Two things they settle: for a `:source` handle `from-of` returns a *subquery* that has already renamed the file's headers to loci's, and `q-as` names result columns **positionally** from the vector you hand it — so prepending `rowid` to the `SELECT` means prepending `:rowid` to that vector.

- [ ] **Step 1: Write the failing tests.**

Append to `test/loci/lake_test.clj`:

```clojure
(deftest a-lake-table-pages-forward-by-seeking-on-rowid
  (with-lake [lk]
    (let [rs (vec (for [i (range 250)] {:i i :grp (str "g" (mod i 5))}))
          h  (tbl/put-rows! lk "tbl:p" rs)
          p1 (tbl/page lk h {:limit 100})
          p2 (tbl/page lk h {:limit 100 :cursor (:next p1)})
          p3 (tbl/page lk h {:limit 100 :cursor (:next p2)})]
      (is (= (range 0 100)   (map :i (:rows p1))))
      (is (= (range 100 200) (map :i (:rows p2))) "page 2 continues page 1 exactly")
      (is (= 50 (count (:rows p3))))
      (is (nil? (:next p3)))
      (is (not-any? :rowid (:rows p1)) "rowid is how the page is found, not a column"))))

(deftest a-sorted-page-continues-across-a-tie-in-the-sort-column
  ;; 250 rows over 5 groups: every page boundary lands inside a run of 50 equal
  ;; sort values. This is the case a naive keyset loses rows on and the reason
  ;; §2.3 pages a sort by offset.
  (with-lake [lk]
    (let [rs   (vec (for [i (range 250)] {:i i :grp (str "g" (mod i 5))}))
          h    (tbl/put-rows! lk "tbl:t" rs)
          walk (fn [] (loop [c nil acc []]
                        (let [p (tbl/page lk h {:limit 40 :cursor c :sort ["grp" "asc"]})
                              acc (into acc (:rows p))]
                          (if (:next p) (recur (:next p) acc) acc))))
          all  (walk)]
      (is (= 250 (count all)) "no row is lost at a boundary inside a tie")
      (is (= 250 (count (distinct (map :i all)))) "and none is served twice")
      (is (= (sort (map :grp all)) (map :grp all)) "the walk is in sort order"))))

(deftest a-descending-sort-pages-in-descending-order
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:d" (vec (for [i (range 30)] {:i i})))
          p (tbl/page lk h {:limit 5 :sort ["i" "desc"]})]
      (is (= [29 28 27 26 25] (map :i (:rows p))))
      (is (= [24 23 22 21 20]
             (map :i (:rows (tbl/page lk h {:limit 5 :cursor (:next p) :sort ["i" "desc"]}))))))))

(deftest a-passthrough-source-pages-with-no-rowid-in-sight
  (with-lake [lk]
    (let [dir  (str (System/getProperty "java.io.tmpdir") "/loci-page-src-" (System/nanoTime))
          _    (.mkdirs (java.io.File. dir))
          csv  (str dir "/rows.csv")
          _    (spit csv (apply str "region,amount\n"
                                (for [i (range 250)] (str "r" (mod i 5) "," i "\n"))))
          h    {:store :source :uri (str "file://" csv) :format :csv
                :cols [:region :amount] :raw ["region" "amount"] :rows 250}
          p1   (tbl/page lk h {:limit 100})
          p2   (tbl/page lk h {:limit 100 :cursor (:next p1)})]
      (try
        (is (= 100 (count (:rows p1))))
        (is (= 100 (:amount (first (:rows p2))))
            "the mode where keyset is impossible still pages, through the same method")
        (is (= [:region :amount] (vec (keys (first (:rows p1))))))
        (finally (lf/rm-rf dir))))))

(deftest a-window-that-hides-a-column-hides-it-in-the-sql
  (with-lake [lk]
    (let [h (tbl/put-rows! lk "tbl:c" [{:a 1 :b 2 :c 3}])]
      (is (= [:a :c] (vec (keys (first (:rows (tbl/page lk h {:limit 1 :cols ["a" "c"]}))))))))))
```

- [ ] **Step 2: Run, watch fail.**

```
clojure -M:test -v loci.lake-test
```

Expected: `No implementation of method: :page`.

- [ ] **Step 3: Implement.**

In the `(extend-type Lake tbl/TableStore …)` block in `src/loci/lake.clj`, add directly after `rows`:

```clojure
  (page [lk h window]
    ;; **Keyset in natural order; an offset for everything else.** A materialised
    ;; DuckLake table exposes a dense `rowid`, so an unsorted page is a range
    ;; predicate rather than a scan-and-discard — which is the whole reason
    ;; paging is keyset-shaped at all.
    ;;
    ;; A SORT pages by offset even here, and that is a decision rather than a
    ;; shortcut. The compound-key form works — the spec's §2.1 proves
    ;; `WHERE (n, rowid) < (48, 2) ORDER BY n DESC, rowid` returns the exact
    ;; continuation — but taking it costs two problems natural order does not
    ;; have: `ORDER BY col DESC, rowid ASC` is not what `(col, rowid) < (v, k)`
    ;; selects, and a row comparison against a NULL is NULL, so rows in a
    ;; nullable sort column vanish. Both would be paid to speed up a query that
    ;; is dominated by its ORDER BY however it is paginated, and nobody has
    ;; measured that. §2.3 keeps the compound key written down as the first thing
    ;; to reach for the day someone feels it.
    ;;
    ;; A `:source` handle has no key of any kind — `rowid` is a binder error over
    ;; `read_csv` and `file_row_number` is a Parquet option — so it is always the
    ;; offset branch. Its scan is not new: `read_csv` in a FROM clause re-parses
    ;; the file on every read, including page 1's.
    (let [lim   (tbl/page-limit (:limit window))
          proj  (tbl/project-cols h (:cols window))
          srt   (tbl/sort-spec h (:sort window))
          seek? (and (nil? srt) (not= :source (:store h)))
          cur   (tbl/decode-cursor (:cursor window))
          after (or (:k cur) -1)
          off   (or (:o cur) 0)
          ks    (cond->> proj seek? (cons :rowid))
          sql   (str "SELECT " (when seek? "rowid, ") (select-cols proj)
                     " FROM " (from-of h)
                     (cond seek? (str " WHERE rowid > " (long after) " ORDER BY rowid")
                           srt   (str " ORDER BY " (ident (first srt))
                                      (if (= "desc" (second srt)) " DESC" " ASC")
                                      " NULLS LAST"))
                     " LIMIT " (inc lim)
                     (when-not seek? (str " OFFSET " (long off))))
          got   (q-as lk sql (vec ks))
          more  (> (count got) lim)
          kept  (vec (take lim got))]
      {:rows (mapv #(dissoc % :rowid) kept)
       :next (when more
               (tbl/encode-cursor (if seek?
                                    {:k (:rowid (peek kept))}
                                    {:o (+ off lim)})))}))
```

- [ ] **Step 4: Green.**

```
clojure -M:test -v loci.lake-test
```

- [ ] **Step 5: Prove they bite.** One at a time, restoring after each:

| break | test that must go red |
|---|---|
| `seek?` drops `(nil? srt)` — keyset under a sort | `a-sorted-page-continues-across-a-tie-in-the-sort-column`, with rows lost at boundaries |
| `WHERE rowid > k` → `WHERE rowid >= k` | `a-lake-table-pages-forward-by-seeking-on-rowid` — page 2 repeats row 99 |
| drop `(dissoc % :rowid)` | the `not-any? :rowid` assertion |
| `seek?` drops `(not= :source (:store h))` | `a-passthrough-source-pages-with-no-rowid-in-sight`, with a binder error naming `rowid` |
| `" DESC"` → `" ASC"` | `a-descending-sort-pages-in-descending-order` |

The first is the important one. **Report the number of rows lost**, not just that it failed — that number is why the sort branch exists.

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "feat: a lake table seeks on rowid, and a sort pages the simple way"
```

---

## Task 3: `/api/mold` reads a window

**Files:**
- Modify: `src/loci/tablestore.clj` — `hydrate` (`:461-580`)
- Modify: `src/loci/server.clj` — `mold-payload` (`:415`), the `/api/mold` route (`:3388`)
- Modify: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests.**

Append to `test/loci/server_test.clj` (follow the file's existing store/lake fixture idiom — copy the `let` bindings from a neighbouring `mold-payload` test rather than inventing new ones):

```clojure
(deftest a-mold-of-a-table-carries-one-page-and-a-cursor
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "Windowed" rs {})})
      (let [p1 (srv/mold-payload st no-memory "tbl:w" nil {:limit 100})]
        (is (= 100 (count (:rendered p1))))
        (is (= 250 (:table/total p1)) "the true count is not the page's count")
        (is (some? (:page/next p1)))
        (let [p2 (srv/mold-payload st no-memory "tbl:w" nil
                                   {:limit 100 :cursor (:page/next p1)})]
          (is (= 100 (:i (first (:rendered p2))))))))))

(deftest a-mold-with-no-window-is-what-it-always-was
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "Windowed" rs {})})
      (let [m (srv/mold-payload st no-memory "tbl:w" nil)]
        (is (= 250 (count (:rendered m)))
            "every caller that does not ask for a page still gets mold-cap")
        (is (nil? (:page/next m)))))))

(deftest an-aggregate-view-is-not-paged
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i :grp (str "g" (mod i 5))}))]
      (sub/commit! st {:op :put :id "tbl:w"
                       :value (tbl/table-object lk "tbl:w" "Windowed" rs {})})
      (let [m (srv/mold-payload st no-memory "tbl:w" "table/summary" {:limit 10})]
        (is (nil? (:page/next m))
            "a summary has no next page — a window is for rows, not for a chart")))))
```

- [ ] **Step 2: Run, watch fail.**

```
clojure -M:test -v loci.server-test
```

Expected: an arity error on `mold-payload`.

- [ ] **Step 3: Implement — `hydrate-page` first.**

In `src/loci/tablestore.clj`, beside `hydrate`:

```clojure
(defn hydrate-page
  "`hydrate`, but for one page of a table rather than a `mold-cap` sample.

   Returns the hydrated object with `:value` set to the page's rows and
   `:page/next` set to the cursor for the next page, or nil on the last one.

   **Beside `hydrate` rather than a flag on it.** `hydrate` is reached from
   thirteen places and twelve of them want the sample — its failure handling,
   its `:table/unavailable` and `:table/source-drift` classification and its
   `:table/passthrough` marker are all shared, so this delegates for a non-handle
   value and for the failure paths and differs only in which read it issues."
  [ts o window]
  (if-not (and o (handle? (:value o)))
    (hydrate ts o)
    (let [ts (force ts)
          h  (:value o)]
      (try
        (let [{:keys [rows next]} (page ts h window)]
          (cond-> (assoc o :value rows :table/total (row-count ts h) :page/next next)
            (= :source (:store h)) (assoc :table/passthrough true)))
        (catch Exception _
          ;; one classifier, not two: a page that throws is the same broken
          ;; table a sample would have been, and `hydrate` already knows how to
          ;; say which way it is broken.
          (hydrate ts o))))))
```

In `src/loci/server.clj`, give `mold-payload` a 5-arity that takes the window, and use it on the rows/cards branch only:

```clojure
  ([st m id view] (mold-payload st m id view nil))
  ([st m id view window]
   (let [o (if window
             (tbl/hydrate-page lake/lake (sub/object st id) window)
             (tbl/hydrate lake/lake (sub/object st id)))]
     (cond
       ;; …every existing branch, unchanged and in the order it is in today.
       ;; `unusable` stays FIRST for the reason its comment gives.
       ))))
```

Move the existing body verbatim into the 5-arity. Do not reorder the `cond`: its first branch is `unusable`, and its comment records that a materialised source whose lake version aged past the GC window is reclaimed *and* sourced, so the order is load-bearing.

and in the built-in-viewer `:else` branch, carry the cursor out:

```clojure
      :else
      (let [mv (mold/mold (:value o) (when (seq view) (str->kw view)))]
        (cond-> {:id id :title (:title o) :kind (name (:kind mv))
                 :view (when (:view mv) (kw->str (:view mv)))
                 :label (:label mv) :rendered (:rendered mv)
                 :alternatives (alternatives st o) :source (source-block o)
                 :table/total (:table/total o)}
          ;; ONLY for a viewer that renders rows. `table/summary` over a window
          ;; would draw a pager under a chart computed from something else.
          (and (:page/next o) (#{:table/rows :table/cards} (:view mv)))
          (assoc :page/next (:page/next o))))
```

**Do not window the other branches.** `unusable`, `entity`, `flow`, `applet` and `view:` are untouched: none of them renders rows, and a `view:` spec is interpreted over `(:value o)` in whatever shape its author expected.

Then the route (`server.clj:3388`):

```clojure
      (= uri "/api/mold")    (json-resp (mold-payload (store-at st (params "at")) mem/memory
                                                     (params "id") (params "view")
                                                     (window-params params)))
```

with, beside `parse-query`:

```clojure
(defn- window-params
  "The window a query string asks for, or nil for none.

   nil and not an empty map: `mold-payload` branches on it to choose
   `hydrate-page` over `hydrate`, and every caller that is not the shell's Rows
   view must keep getting the `mold-cap` sample it gets today.

   `cols` is comma-separated. Every value here is validated downstream against
   the handle — `page-limit` clamps, `sort-spec` and `project-cols` drop what the
   table does not have — so nothing this function returns is trusted."
  [params]
  (let [limit  (params "limit")
        cursor (params "cursor")
        sort   (params "sort")
        cols   (params "cols")]
    (when (or limit cursor sort cols)
      {:limit  limit
       :cursor cursor
       :sort   (when (seq sort) (str/split sort #"," 2))
       :cols   (when (seq cols) (str/split cols #","))})))
```

- [ ] **Step 4: Green.**

```
clojure -M:test -v loci.server-test
```

- [ ] **Step 5: Prove it bites.**

| break | test that must go red |
|---|---|
| `mold-payload`'s 4-arity passes `{}` instead of `nil` | `a-mold-with-no-window-is-what-it-always-was` — 100 rows instead of 250 |
| drop the `#{:table/rows :table/cards}` guard | `an-aggregate-view-is-not-paged` |
| `window-params` returns `{}` when no window param is present | `a-mold-with-no-window-is-what-it-always-was` |

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/tablestore.clj src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: /api/mold takes a window, and everything that does not ask still gets the sample"
```

---

## Task 4: a notebook ships one page per cell

**Files:**
- Modify: `src/loci/server.clj` — `notebook-payload` (`:925-941`)
- Modify: `test/loci/server_test.clj`

The defect: `notebook-payload` merges a full `mold-payload` into every `:ref` cell, so a notebook with five table cells carries five `mold-cap` samples in one response. Measured on the real substrate before this plan: one 36-column × 2,000-row cell was **5,428,753 bytes**.

- [ ] **Step 1: Write the failing test.**

```clojure
(deftest a-notebook-cell-carries-one-page-and-not-the-whole-sample
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 250)] {:i i}))]
      (sub/commit! st {:op :tx :events
                       [{:op :put :id "tbl:n" :value (tbl/table-object lk "tbl:n" "T" rs {})}
                        {:op :put :id "space:n"
                         :value {:id "space:n" :kind :space :title "N"
                                 :value {:intent "one table cell"
                                         :cells [{:ref "tbl:n"}]}}}]})
      (let [cell (first (:cells (srv/notebook-payload st "space:n")))]
        (is (= 100 (count (:rendered cell))) "a cell is a page, not a sample")
        (is (= 250 (:table/total cell))      "and it still says how big the table is")
        (is (some? (:page/next cell)))))))
```

- [ ] **Step 2: Run, watch fail.** Expected: 250, not 100.

- [ ] **Step 3: Implement.** In `notebook-payload`, change the `:ref` branch:

```clojure
                        (sub/object st (:ref c))
                        (merge {:i i :type "ref"
                                :also-in (get also-in (:ref c))
                                :from (:from (sub/object st (:ref c)))
                                :via  (:via (sub/object st (:ref c)))}
                               ;; A window with only a limit: a cell opens on page
                               ;; one and pages from there like the molder does.
                               ;; Passing nil here is what shipped five copies of
                               ;; a mold-cap sample in one response.
                               (mold-payload st mem/memory (:ref c) (:view c)
                                             {:limit tbl/default-limit}))
```

- [ ] **Step 4: Green.** **Step 5: Prove it bites** — restore `nil` as the window; the test must report 250.

- [ ] **Step 6: Commit.**

```
clojure -M:test
```

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "fix: a notebook cell is a page, and five table cells stop being five samples"
```

---

# Phase 2 — the renderer

Everything from here is `resources/public/index.html` and the browser suite. **`npm run test:browser` is the only suite that sees these changes.** Run it foreground with a generous timeout; a backgrounded browser run is what two subagents once parked on.

---

## Task 5: absolute widths, and a table that actually scrolls

**Files:**
- Modify: `resources/public/index.html` — `tableHTML` (`:2248-2285`), `table.mold` CSS (`:168-185`)
- Modify: `test/browser/harness.mjs` — add `GRID_FIXTURE`
- Create: `test/browser/grid.test.mjs`

The defect, measured: `tableHTML` sizes each column in characters and then normalises to percentages of a `width:100%; table-layout:fixed` table, so the table is always exactly the panel and 36 columns get 0.7 characters each. The `.toFixed(2)` drift across 36 columns is the ~3 px horizontal scrollbar in the report — there is nothing behind it.

- [ ] **Step 1: Add the fixture.** In `test/browser/harness.mjs`, beside `STORAGE_FIXTURE`:

```javascript
// 36 columns, because that is the width at which tableHTML's percentage
// normalisation collapsed every column to under one character — the defect this
// file exists to pin. 250 rows, because at limit 100 that is three pages with a
// short last one, which is the only way to see `next` go nil.
//
// `grp` has five values and 50 rows each, so every page boundary under a sort by
// `grp` lands inside a run of equal values. `note` is one long value, for the
// tooltip clip. `maybe` is null on every third row, for NULLS LAST.
//
// Above DuckLake's ~10–20 row inlining threshold, so this is a real Parquet
// table with a real rowid and the keyset branch is the one under test.
//
// Its own notebook rather than space:cosmos: tags.test.mjs counts the corpus and
// outline.test.mjs counts cells.
export const GRID_FIXTURE = `
(let [st @c/store
      lk @lake/lake
      row (fn [i] (into {:id (str "row-" i)
                         :grp (str "g" (mod i 5))
                         :note (apply str (repeat 900 "x"))
                         :maybe (when (pos? (mod i 3)) i)}
                        (for [c (range 32)] [(keyword (str "c" c)) (* i (inc c))])))
      rs  (mapv row (range 250))
      h   (tbl/put-rows! lk "tbl:grid" rs)]
  (assert (= 36 (count (:cols h))) "the grid fixture stopped being 36 columns wide")
  (sub/commit! st {:op :tx :events
                   [{:op :put :id "tbl:grid"
                     :value {:id "tbl:grid" :kind :table :title "Grid fixture" :value h}}
                    {:op :put :id "space:grid"
                     :value {:id "space:grid" :kind :space :title "Grid fixture"
                             :value {:intent "a table 36 columns wide"
                                     :cells [{:ref "tbl:grid"}]}}}]}))
`;
```

- [ ] **Step 2: Write the failing test.** Create `test/browser/grid.test.mjs`:

```javascript
// The Rows view at 36 columns. Every assertion here is a measurement off the
// DOM and not a check that a rule is in the stylesheet: asserting
// `position:sticky` is present passes while the header scrolls away, and
// asserting a column "is readable" passes at 0.7 characters.
//
// Its own server for GRID_FIXTURE's reason — it adds a table and a notebook, and
// tags.test.mjs counts the corpus.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell, GRID_FIXTURE } from './harness.mjs';

let server, browser;
before(async () => {
  server = await startServer({ fresh: true, extra: GRID_FIXTURE });
  browser = await launchBrowser();
});
after(async () => { await browser?.close(); await server?.stop(); });

const openGrid = async (page) => {
  await bootedShell(page, server.url);
  await page.goto(server.url + '#space:grid');
  await page.waitForSelector('table.mold tbody tr');
};

test('a 36-column table is wider than its panel and scrolls', async () => {
  await withPage(browser, 'grid-scrolls', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(() => {
      const t = document.querySelector('table.mold');
      const box = t.closest('[data-grid-scroll]');
      return { table: t.getBoundingClientRect().width,
               box: box.clientWidth, scrollWidth: box.scrollWidth };
    });
    assert.ok(m.table > m.box * 1.5,
      `36 columns should overflow the panel; table ${m.table}px in a ${m.box}px box`);
    assert.ok(m.scrollWidth > m.box + 50,
      `there must be somewhere to scroll to, not ${m.scrollWidth - m.box}px of rounding`);
  });
});

test('a narrow column still shows its whole value', async () => {
  await withPage(browser, 'grid-widths', async (page) => {
    await openGrid(page);
    // `grp` holds "g0".."g4" — four characters with the ellipsis, two without.
    // Under the percentage normalisation this cell rendered as "g…".
    const text = await page.evaluate(() => {
      const heads = [...document.querySelectorAll('table.mold thead th')];
      const i = heads.findIndex(h => h.textContent.trim() === 'grp');
      const cell = document.querySelectorAll('table.mold tbody tr')[0].children[i];
      return { text: cell.textContent.trim(), w: cell.getBoundingClientRect().width };
    });
    assert.equal(text.text, 'g0', `grp rendered as ${JSON.stringify(text.text)} in ${text.w}px`);
  });
});

test('a long value is clipped in the tooltip, not shipped whole', async () => {
  await withPage(browser, 'grid-tooltip', async (page) => {
    await openGrid(page);
    const len = await page.evaluate(() => {
      const heads = [...document.querySelectorAll('table.mold thead th')];
      const i = heads.findIndex(h => h.textContent.trim() === 'note');
      return document.querySelectorAll('table.mold tbody tr')[0].children[i]
               .getAttribute('title').length;
    });
    assert.ok(len <= 520, `a 900-character value produced a ${len}-character tooltip`);
  });
});
```

- [ ] **Step 3: Run, watch fail.**

```
npm run test:browser -- --test-name-pattern=grid
```

Expected: the first test fails because the table width equals the box width. **Report the two numbers** — they are the measurement this task is judged on.

- [ ] **Step 4: Implement.**

Replace the width block in `tableHTML` (`index.html:2270-2280`). Delete `total`, the percentage map and the whole `<colgroup>`:

```js
  // Size each column by what it holds, in `ch`, and let the table be as wide as
  // that comes to. The previous version computed exactly these numbers and then
  // normalised them to percentages of a `width:100%` table — which discards the
  // scale, so at 36 columns the 7-character floor became 0.7 characters and
  // there was nothing to scroll to. Absolute widths are the whole fix; the
  // heuristic below was never the bug.
  const width=c=>{
    const seg=Math.max(...c.split('_').map(w=>w.length));
    const longest=shown.reduce((m,r)=>Math.max(m,String(r[c]??'').length),seg);
    return Math.min(Math.max(longest+2,7),28);
  };
  let h='<div data-grid-scroll style="max-height:420px;overflow:auto"><table class="mold"><thead><tr>'+
    cols.map(c=>'<th class="'+(numeric[c]?'num':'txt')+'" style="min-width:'+width(c)+'ch" title="'+esc(c)+'">'+
                esc(c).replace(/_/g,'_<wbr>')+'</th>').join('')+'</tr></thead><tbody>'+
    shown.map(r=>'<tr>'+cols.map(c=>cell(r,c)).join('')+'</tr>').join('')+
    '</tbody></table></div>';
```

and clip the tooltip in `cell`:

```js
  // The full value went into `title` and `description` in a real table reaches
  // 16,344 characters — which is not a tooltip, and is a second copy of the
  // page's rows in the DOM. 500 is enough to identify a truncated cell.
  const clip=s=>s.length>500?s.slice(0,500)+'…':s;
  const cell=(r,c)=>{
    const v=r[c], s=(v===null||v===undefined)?'':String(v);
    return '<td class="'+(numeric[c]?'num':'txt')+'" title="'+esc(clip(s))+'">'+esc(s)+'</td>';
  };
```

In the CSS at `index.html:168`, drop `width:100%` and `table-layout:fixed`:

```css
  /* No `width:100%` and no `table-layout:fixed`. Both existed to make truncation
     possible; `min-width` in ch on each th does that job without forcing the
     table to be exactly its panel. */
  table.mold{border-collapse:collapse;font-size:11.5px}
```

- [ ] **Step 5: Green.**

```
npm run test:browser -- --test-name-pattern=grid
```

- [ ] **Step 6: Prove they bite.** Restore `width:100%;table-layout:fixed` and the `<colgroup>`: `a 36-column table is wider than its panel` and `a narrow column still shows its whole value` must both go red. Remove `clip`: the tooltip test must report 900.

- [ ] **Step 7: Whole browser suite, then commit.**

```
npm run test:browser
```

`renderReport` and the ƒ-preview `before`/`after` also call `tableHTML`; the suite is what confirms they still draw.

```bash
git add resources/public/index.html test/browser/harness.mjs test/browser/grid.test.mjs
git commit -m "feat: a column is as wide as what it holds, and the table is as wide as its columns"
```

---

## Task 6: a header that stays, and a first column that stays

**Files:**
- Modify: `resources/public/index.html` — `table.mold` CSS (`:168-185`)
- Modify: `test/browser/grid.test.mjs`

- [ ] **Step 1: Write the failing tests.** Append to `grid.test.mjs`:

```javascript
test('the header stays put when the rows scroll', async () => {
  await withPage(browser, 'grid-sticky-head', async (page) => {
    await openGrid(page);
    const move = await page.evaluate(async () => {
      const box = document.querySelector('[data-grid-scroll]');
      const th = document.querySelector('table.mold thead th');
      const before = th.getBoundingClientRect().top;
      box.scrollTop = 300;
      await new Promise(r => requestAnimationFrame(r));
      return Math.abs(th.getBoundingClientRect().top - before);
    });
    assert.ok(move < 2, `the header moved ${move}px — it scrolled away with the rows`);
  });
});

test('the first column stays put when the columns scroll', async () => {
  await withPage(browser, 'grid-frozen-col', async (page) => {
    await openGrid(page);
    const m = await page.evaluate(async () => {
      const box = document.querySelector('[data-grid-scroll]');
      const td = document.querySelector('table.mold tbody tr td');
      const before = td.getBoundingClientRect().left;
      box.scrollLeft = 600;
      await new Promise(r => requestAnimationFrame(r));
      const after = td.getBoundingClientRect();
      const behind = document.elementFromPoint(after.left + 4, after.top + 4);
      return { move: Math.abs(after.left - before), onTop: td.contains(behind) };
    });
    assert.ok(m.move < 2, `the first column moved ${m.move}px`);
    assert.ok(m.onTop, 'the frozen column is transparent — scrolled rows show through it');
  });
});
```

- [ ] **Step 2: Run, watch fail.** Expected: a move of roughly 300 px and 600 px.

- [ ] **Step 3: Implement.** Add to the `table.mold` CSS block:

```css
  /* The header IS the column key, so it has to survive scrolling to one: at
     36 columns `fa` is only decodable as `archived` while the header is on
     screen. `background` is not decoration — a sticky element is painted over
     scrolling content and shows through without one. */
  table.mold thead th{position:sticky;top:0;z-index:2;background:var(--white)}
  /* The first column is the row's identity, and a table wider than its panel
     loses it on the first horizontal scroll. */
  table.mold th:first-child,table.mold td:first-child{
    position:sticky;left:0;background:var(--white);
    box-shadow:1px 0 0 var(--line)}
  /* the corner is sticky on both axes, so it must outrank both. */
  table.mold thead th:first-child{z-index:3}
```

- [ ] **Step 4: Green.** **Step 5: Prove they bite** — remove `position:sticky` from `thead th`, then from `td:first-child`, then remove the `background` from the frozen column (the `onTop` assertion is the one that catches a transparent sticky cell, and it is the failure a screenshot would show and a stylesheet assertion would not).

- [ ] **Step 6: Whole suite, then commit.**

```
npm run test:browser
```

```bash
git add resources/public/index.html test/browser/grid.test.mjs
git commit -m "feat: the header and the first column stay where you can read them"
```

---

## Task 7: the pager

**Files:**
- Modify: `resources/public/index.html` — `API.mold` (`:615`), `renderMold` (`:1960`), `tableHTML` (`:2248`), `remold` (`:1793`)
- Modify: `test/browser/grid.test.mjs`

`tableHTML` is called from three places: `renderMold` (a molded table, which pages), `renderReport` (a report block, which does not) and the ƒ preview (`:1889-1890`, which does not). So the page payload is a **second, optional argument** and the other two callers are untouched.

- [ ] **Step 1: Write the failing tests.**

```javascript
test('the pager walks to the last page and stops', async () => {
  await withPage(browser, 'grid-pager', async (page) => {
    await openGrid(page);
    const read = () => page.evaluate(() =>
      ({ caption: document.querySelector('[data-pager] .pgcap').textContent.trim(),
         first: document.querySelector('table.mold tbody tr td').textContent.trim(),
         rows: document.querySelectorAll('table.mold tbody tr').length,
         nextDisabled: document.querySelector('[data-a="page-next"]').disabled,
         prevDisabled: document.querySelector('[data-a="page-prev"]').disabled }));

    let s = await read();
    assert.match(s.caption, /1–100 of 250/);
    assert.equal(s.first, 'row-0');
    assert.ok(s.prevDisabled, 'there is no page before the first');

    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelector('table.mold tbody tr td').textContent.trim() === 'row-100');
    s = await read();
    assert.match(s.caption, /101–200 of 250/);
    assert.ok(!s.prevDisabled);

    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelectorAll('table.mold tbody tr').length === 50);
    s = await read();
    assert.match(s.caption, /201–250 of 250/);
    assert.ok(s.nextDisabled, 'the last page says it is the last');

    await page.click('[data-a="page-prev"]');
    await page.waitForFunction(() =>
      document.querySelector('table.mold tbody tr td').textContent.trim() === 'row-100');
    assert.match((await read()).caption, /101–200 of 250/, 'prev pops the cursor stack');
  });
});

test('the page size is adjustable and caps at 200', async () => {
  await withPage(browser, 'grid-pagesize', async (page) => {
    await openGrid(page);
    await page.selectOption('[data-a="page-size"]', '200');
    await page.waitForFunction(() =>
      document.querySelectorAll('table.mold tbody tr').length === 200);
    assert.match(
      await page.textContent('[data-pager] .pgcap'), /1–200 of 250/);
    const opts = await page.$$eval('[data-a="page-size"] option', os => os.map(o => o.value));
    assert.deepEqual(opts, ['25', '50', '100', '200']);
  });
});

test('the shell never reads a cursor', async () => {
  // The one invariant that lets three storage strategies share one control.
  await withPage(browser, 'grid-cursor-opaque', async (page) => {
    await openGrid(page);
    const src = await page.evaluate(() => document.documentElement.innerHTML.length && window.__src);
    assert.ok(true, 'structural check below');
  });
  const fs = await import('node:fs/promises');
  const src = await fs.readFile(new URL('../../resources/public/index.html', import.meta.url), 'utf8');
  for (const probe of ['cursor.k', 'cursor.o', 'cursor.i', 'atob(', 'JSON.parse(cursor']) {
    assert.ok(!src.includes(probe), `the shell inspects a cursor: found ${probe}`);
  }
});
```

- [ ] **Step 2: Run, watch fail.** Expected: no `[data-pager]` in the DOM.

- [ ] **Step 3: Implement.**

`API.mold` takes the window:

```js
  // `w` is the window: {limit, cursor, sort, cols}. Absent entirely for every
  // caller that wants what /api/mold always gave — server.clj's window-params
  // returns nil when no window parameter is present, and that is what keeps the
  // mold-cap sample the default for the other twelve readers.
  mold: (id,view,at,w) => fetch('/api/mold?id='+encodeURIComponent(id)+'&view='+encodeURIComponent(view)
        +(at!=null?'&at='+at:'')
        +(w&&w.limit?'&limit='+w.limit:'')
        +(w&&w.cursor?'&cursor='+encodeURIComponent(w.cursor):'')
        +(w&&w.sort?'&sort='+encodeURIComponent(w.sort.join(',')):'')
        +(w&&w.cols?'&cols='+encodeURIComponent(w.cols.join(',')):'')).then(r=>r.json()),
```

Add the per-object paging state and the window it produces, near `SRCNOW`:

```js
// Where each open table is in its walk. `stack` holds the cursor that STARTED
// each page visited, so Prev is a pop and is exact — a server-computed
// `prev` would be a second source of truth able to disagree with it.
// `page` is the 0-based index, which is the whole of what the caption needs:
// from = page*limit + 1, and `table/total` comes off the handle.
const PAGING={};
const pagingOf=id=>{
  if(!PAGING[id]){ PAGING[id]={stack:[null],page:0,limit:100,sort:null,hidden:[],visible:null};
                   loadPaging(id); }
  return PAGING[id];
};
// Every field is here from the start even though Tasks 8 and 9 are what set
// `sort` and `hidden`: a shape that grows between tasks is a shape two handlers
// disagree about for a commit.
const windowOf=id=>{const p=pagingOf(id);
  return {limit:p.limit,cursor:p.stack[p.page],sort:p.sort,
          cols:(p.hidden.length&&p.visible)?p.visible:null};};
// Defined in Task 9; a no-op until then, so this file always parses.
function loadPaging(id){}
function savePaging(id){}
```

`remold` carries it:

```js
async function remold(id,view){ const m=await API.mold(id,view,tat(),windowOf(id)); renderDetail(focusIdx,m); }
```

`renderMold`'s table case hands the payload through:

```js
    case 'table':  return liveBadge(m)+tableHTML(d,m);
```

`tableHTML` gains the optional second argument and the pager. Replace the trailing caption:

```js
function tableHTML(rows,m){
  if(!rows||!rows.length) return '<i>empty</i>';
  // **No cap for a windowed table.** The server already limited it to the page
  // size, and slicing a 100-row page to 60 would draw 60 rows under a caption
  // reading `1–100`, which is worse than the truncation it came from. `cap`
  // stays 60 for the two callers with no window: renderReport's table blocks and
  // the ƒ preview's before/after.
  const cols=Object.keys(rows[0]);
  const cap=m?rows.length:60, shown=rows.slice(0,cap);
  //  … numeric[], clip(), cell(), width() and the table markup exactly as
  //    Task 5 left them, building `h` …
  // `m` present means this table was molded through a window and can page.
  if(m) h+=pagerHTML(m);
  else if(rows.length>cap) h+='<div style="font-size:11px;color:var(--muted);margin-top:6px;font-family:var(--mono)">showing '+cap+' of '+rows.length+' rows</div>';
  return h;
}
function pagerHTML(m){
  const p=pagingOf(m.id), from=p.page*p.limit+1, to=from+((m.rendered||[]).length)-1;
  const total=m.total;
  return '<div data-pager style="display:flex;align-items:center;gap:12px;margin-top:8px;'+
    'font-size:11px;color:var(--muted);font-family:var(--mono)">'+
    '<span class="pgcap">showing '+from+'–'+to+(total!=null?' of '+fmtNum(total):'')+'</span>'+
    '<span style="flex:1"></span>'+
    '<select class="btn ghost sm" data-a="page-size">'+
      [25,50,100,200].map(n=>'<option value="'+n+'"'+(n===p.limit?' selected':'')+'>'+n+' rows</option>').join('')+
    '</select>'+
    '<button class="btn ghost sm" data-a="page-prev"'+(p.page===0?' disabled':'')+'>‹ Prev</button>'+
    '<button class="btn ghost sm" data-a="page-next"'+(m.next?'':' disabled')+'>Next ›</button>'+
    '</div>';
}
```

Wire the buttons in `wireSourceButtons` (rename nothing — it is already the function `renderDetail` and the notebook cell renderer both call to attach a molded table's controls):

```js
  // ONE DOM hook for every control a molded table grows — the pager here, the
  // sort headers in Task 8, the column chooser in Task 9. `data-mold-root` goes
  // on the element renderMold's output is inserted into, and carries the object
  // id and the current view, because a header cell is not the pager and neither
  // is the open object in a notebook.
  const rootOf=el=>el.closest('[data-mold-root]');
  root.querySelectorAll('[data-a="page-next"]').forEach(el=>el.addEventListener('click',ev=>{
    ev.stopPropagation();
    const w=rootOf(el), id=w.dataset.moldRoot, p=pagingOf(id);
    // The cursor that STARTS the page we are about to show, pushed so Prev can
    // pop back to the one that started this one.
    p.stack[p.page+1]=w.dataset.moldNext||null; p.page++;
    remold(id,w.dataset.moldView||'');
  }));
  root.querySelectorAll('[data-a="page-prev"]').forEach(el=>el.addEventListener('click',ev=>{
    ev.stopPropagation();
    const w=rootOf(el), id=w.dataset.moldRoot, p=pagingOf(id);
    if(p.page>0) p.page--;
    remold(id,w.dataset.moldView||'');
  }));
  root.querySelectorAll('[data-a="page-size"]').forEach(el=>el.addEventListener('change',ev=>{
    ev.stopPropagation();
    const w=rootOf(el), id=w.dataset.moldRoot, p=pagingOf(id);
    // A cursor names a position in a walk of a given size, so changing the size
    // invalidates every cursor in the stack. Back to page one, which is honest —
    // the alternative is a caption whose arithmetic no longer matches the rows.
    p.limit=+el.value; p.stack=[null]; p.page=0;
    savePaging(id); remold(id,w.dataset.moldView||'');
  }));
```

Add `data-mold-root` where `renderMold(m)`'s output is inserted — **both places**: `renderDetail` (the molder) and the notebook cell renderer. Drawing it on one and wiring the other is a control that does nothing, which is the failure `wireSourceButtons`' own comment already records for the Refresh button:

```js
  '<div data-mold-root="'+esc(m.id)+'" data-mold-view="'+esc(m.view||'')+'"'+
  ' data-mold-next="'+esc(m.next||'')+'">'+renderMold(m)+'</div>'
```

The next cursor rides on the wrapper as a string the shell only ever hands back, never opens.

- [ ] **Step 4: Green.**

```
npm run test:browser -- --test-name-pattern=grid
```

- [ ] **Step 5: Prove they bite.**

| break | test that must go red |
|---|---|
| Prev does `p.page--` without the stack (recomputes a cursor) | `the pager walks to the last page and stops` — the final Prev assertion |
| `page-size` keeps the stack instead of resetting | `the page size is adjustable and caps at 200` |
| add `JSON.parse(cursor)` anywhere in the shell | `the shell never reads a cursor` |
| `Next` is enabled regardless of `page/next` | the `nextDisabled` assertion |

- [ ] **Step 6: Whole suite, then commit.**

```
npm run test:browser
```

```bash
git add resources/public/index.html test/browser/grid.test.mjs
git commit -m "feat: a pager, a page size, and a cursor stack the shell never looks inside"
```

---

# Phase 3 — sorting and columns

---

## Task 8: click a header to sort

**Files:**
- Modify: `resources/public/index.html` — `tableHTML`, `wireSourceButtons`, `PAGING`
- Modify: `test/browser/grid.test.mjs`

- [ ] **Step 1: Write the failing tests.**

```javascript
test('clicking a header sorts, and clicking twice reverses', async () => {
  await withPage(browser, 'grid-sort', async (page) => {
    await openGrid(page);
    const firstId = () => page.textContent('table.mold tbody tr td');
    const head = 'table.mold thead th:nth-child(2)';   // grp

    await page.click(head);
    await page.waitForFunction(() =>
      document.querySelector('table.mold thead th:nth-child(2)').dataset.sort === 'asc');
    assert.equal((await page.$$eval('table.mold tbody tr td:nth-child(2)',
      c => c.map(x => x.textContent.trim())))[0], 'g0');

    await page.click(head);
    await page.waitForFunction(() =>
      document.querySelector('table.mold thead th:nth-child(2)').dataset.sort === 'desc');
    assert.equal((await page.$$eval('table.mold tbody tr td:nth-child(2)',
      c => c.map(x => x.textContent.trim())))[0], 'g4');

    await page.click(head);
    await page.waitForFunction(() =>
      !document.querySelector('table.mold thead th:nth-child(2)').dataset.sort);
    assert.equal((await firstId()).trim(), 'row-0', 'a third click is natural order again');
  });
});

test('a sort restarts the walk at page one', async () => {
  await withPage(browser, 'grid-sort-resets', async (page) => {
    await openGrid(page);
    await page.click('[data-a="page-next"]');
    await page.waitForFunction(() =>
      document.querySelector('table.mold tbody tr td').textContent.trim() === 'row-100');
    await page.click('table.mold thead th:nth-child(2)');
    await page.waitForFunction(() =>
      document.querySelector('[data-pager] .pgcap').textContent.includes('1–100'));
    assert.ok(await page.$eval('[data-a="page-prev"]', b => b.disabled),
      'a cursor from the unsorted walk means nothing in a sorted one');
  });
});
```

- [ ] **Step 2: Run, watch fail.**

- [ ] **Step 3: Implement.** Add `sort` to the paging state and the window:

`pagingOf` and `windowOf` already carry `sort` — Task 7 defined the whole shape. Nothing to add here; the headers just start setting it.

Headers carry the state and become buttons:

```js
  // The header is the sort control. Three states and not two: ascending,
  // descending, and back to the table's own order — which for a lake table is
  // the only order that pages by seeking rather than by counting, so it must be
  // reachable rather than something you lose on the first click.
  const srt=m?pagingOf(m.id).sort:null;
  const th=c=>{
    const dir=(srt&&srt[0]===c)?srt[1]:'';
    const mark=dir==='asc'?' ▲':dir==='desc'?' ▼':'';
    return '<th class="'+(numeric[c]?'num':'txt')+'" style="min-width:'+width(c)+'ch"'+
      (m?' data-a="sort" data-col="'+esc(c)+'"'+(dir?' data-sort="'+dir+'"':'')+
         ' style="cursor:pointer"':'')+
      ' title="'+esc(c)+'">'+esc(c).replace(/_/g,'_<wbr>')+mark+'</th>';
  };
```

(Fold `min-width` and `cursor` into one `style` attribute — two `style` attributes on one element silently drops the second.)

And the handler:

```js
  root.querySelectorAll('[data-a="sort"]').forEach(el=>el.addEventListener('click',ev=>{
    ev.stopPropagation();
    const wrap=el.closest('[data-mold-root]'), id=wrap.dataset.moldRoot, p=pagingOf(id);
    const col=el.dataset.col, cur=(p.sort&&p.sort[0]===col)?p.sort[1]:null;
    p.sort = cur==null ? [col,'asc'] : cur==='asc' ? [col,'desc'] : null;
    // A cursor names a position in one ordering. Reordering the table makes
    // every cursor in the stack meaningless, so the walk restarts — which is
    // also what a person expects from sorting.
    p.stack=[null]; p.page=0;
    savePaging(id); remold(id,wrap.dataset.moldView||'');
  }));
```

`data-mold-root` goes on the element `renderMold`'s output is inserted into, so a header click can find the object id the same way a pager button does. Add it in `renderDetail` and in the notebook cell renderer where `renderMold(m)` is wrapped.

- [ ] **Step 4: Green.** **Step 5: Prove they bite** — make the third click return to `asc` instead of null (the natural-order assertion fails); keep the stack across a sort (`a sort restarts the walk at page one` fails).

- [ ] **Step 6: Whole suite, then commit.**

```
npm run test:browser
```

```bash
git add resources/public/index.html test/browser/grid.test.mjs
git commit -m "feat: a header sorts three ways, and the third is the order that seeks"
```

---

## Task 9: the column chooser, and remembering all of it

**Files:**
- Modify: `resources/public/index.html`
- Modify: `test/browser/grid.test.mjs`

- [ ] **Step 1: Write the failing tests.**

```javascript
test('hiding a column removes it from the request, not only the page', async () => {
  await withPage(browser, 'grid-cols', async (page) => {
    await openGrid(page);
    const urls = [];
    page.on('request', r => { if (r.url().includes('/api/mold')) urls.push(r.url()); });
    await page.click('[data-a="cols"]');
    await page.click('[data-colpick="note"]');
    await page.waitForFunction(() =>
      ![...document.querySelectorAll('table.mold thead th')]
        .some(h => h.textContent.trim().startsWith('note')));
    assert.ok(urls.some(u => u.includes('cols=') && !u.includes('note')),
      'the hidden column must be absent from the window, or the payload never shrank');
  });
});

test('sort, hidden columns and page size survive a reload', async () => {
  await withPage(browser, 'grid-persist', async (page) => {
    await openGrid(page);
    await page.selectOption('[data-a="page-size"]', '25');
    await page.waitForFunction(() =>
      document.querySelectorAll('table.mold tbody tr').length === 25);
    await page.click('table.mold thead th:nth-child(2)');
    await page.waitForFunction(() =>
      document.querySelector('table.mold thead th:nth-child(2)').dataset.sort === 'asc');

    await page.reload();
    await page.waitForSelector('table.mold tbody tr');
    assert.equal(await page.$$eval('table.mold tbody tr', r => r.length), 25);
    assert.equal(
      await page.$eval('table.mold thead th:nth-child(2)', h => h.dataset.sort), 'asc');
  });
});

test('a browser that refuses storage still draws the table', async () => {
  await withPage(browser, 'grid-no-storage', async (page) => {
    await page.addInitScript(() => {
      Object.defineProperty(window, 'localStorage', {
        get() { throw new DOMException('denied', 'SecurityError'); } });
    });
    await openGrid(page);
    assert.ok(await page.$$eval('table.mold tbody tr', r => r.length) > 0,
      'a private window must get a table, not a blank panel');
  });
});
```

- [ ] **Step 2: Run, watch fail.**

- [ ] **Step 3: Implement.**

```js
// Per-object view state, in the browser and never in the substrate. The event
// log is what ?at= rewinds and undo! reverses; a column-header click is fiddling
// with a lens, not an act on content, and every one of them becoming a point in
// history you can travel to is noise in the one log that has to stay meaningful.
// (`view` itself stays a substrate event, as it is today — that is a choice made
// once, not a knob.)
//
// Every access is wrapped: a private window, cleared site data, or a browser set
// to block storage throws on the ACCESSOR, not on the call, so a bare
// `localStorage.getItem` in a try-less path takes the whole panel down.
const PAGEKEY=id=>'loci.grid.'+id;
function loadPaging(id){
  try{ const raw=localStorage.getItem(PAGEKEY(id)); if(raw){
    const s=JSON.parse(raw); const p=pagingOf(id);
    if(s.limit) p.limit=s.limit; if(s.sort) p.sort=s.sort; if(s.hidden) p.hidden=s.hidden;
  }}catch(e){}
}
function savePaging(id){
  try{ const p=pagingOf(id);
    localStorage.setItem(PAGEKEY(id),JSON.stringify({limit:p.limit,sort:p.sort,hidden:p.hidden}));
  }catch(e){}
}
```

`pagingOf` calls `loadPaging` on first construction; `savePaging` is called from the size, sort and column handlers. `windowOf` sends the visible columns:

```js
const windowOf=id=>{const p=pagingOf(id);
  return {limit:p.limit,cursor:p.stack[p.page],sort:p.sort,
          // The columns still shown, or nothing at all — an empty `cols` means
          // all of them server-side, and sending every column explicitly would
          // make a stale hidden list silently drop a column added later.
          cols:p.hidden.length?p.visible:null};};
```

`p.visible` is set by `tableHTML` from the payload's own column order minus `p.hidden`, so the list always reflects a real column set.

The chooser, in `pagerHTML` beside the page-size select:

```js
function colsHTML(m,cols){
  const p=pagingOf(m.id), hidden=new Set(p.hidden);
  // The FULL column list comes off the handle, not off the rendered page —
  // a page molded with `cols` set no longer contains the hidden ones, so
  // reading them back from the rows would make hiding a column one-way.
  const all=m.cols||cols;
  return '<details class="colpick"><summary class="btn ghost sm" data-a="cols">'+
    (hidden.size?('columns · '+(all.length-hidden.size)+'/'+all.length):'columns')+'</summary>'+
    '<div class="colpick-b">'+all.map(c=>
      '<label><input type="checkbox" data-colpick="'+esc(c)+'"'+
      (hidden.has(c)?'':' checked')+'> '+esc(c)+'</label>').join('')+'</div></details>';
}
```

`mold-payload` must therefore carry `:table/cols` on the rows branch — it already does for the `unusable` branch, and it comes off the handle rather than off the rows. Add `(:table/cols o)` beside `:table/total` in Task 3's `:else` branch if the implementer finds it absent.

The handler, beside the pager's:

```js
  root.querySelectorAll('[data-colpick]').forEach(el=>el.addEventListener('change',ev=>{
    ev.stopPropagation();
    const w=rootOf(el), id=w.dataset.moldRoot, p=pagingOf(id), c=el.dataset.colpick;
    p.hidden = el.checked ? p.hidden.filter(x=>x!==c) : p.hidden.concat([c]);
    // `visible` is what actually travels. Derived here from the handle's own
    // column list minus what is hidden, so a column added to the table later is
    // shown rather than silently dropped by a stale list.
    const all=JSON.parse(w.dataset.moldCols||'[]');
    p.visible=all.filter(x=>p.hidden.indexOf(x)<0);
    // A narrower projection is a different result set; the walk restarts.
    p.stack=[null]; p.page=0;
    savePaging(id); remold(id,w.dataset.moldView||'');
  }));
```

`data-mold-cols` joins `data-mold-root` and `data-mold-view` on the wrapper, as `JSON.stringify(m.cols||[])`.

- [ ] **Step 4: Green.** **Step 5: Prove they bite** — filter the columns in `tableHTML` instead of sending `cols` (the request assertion fails while the page still looks right, which is the whole point of asserting on the URL); remove the `try` from `loadPaging` (the storage test fails with a blank panel).

- [ ] **Step 6: Whole suite, then commit.**

```
npm run test:browser
```

```bash
git add resources/public/index.html test/browser/grid.test.mjs
git commit -m "feat: hide a column and it leaves the payload, and the browser remembers"
```

---

# Phase 4 — viewers that ask the database

---

## Task 10: the `:needs-store` seam

**Files:**
- Modify: `src/loci/mold.clj` — `mold` (`:47-67`), `register!` (`:24-31`)
- Modify: `src/loci/server.clj` — `mold-payload`'s `:else` branch
- Create: `test/loci/content_test.clj`

- [ ] **Step 1: Write the failing test.** Create `test/loci/content_test.clj`:

```clojure
(ns loci.content-test
  (:require [clojure.test :refer [deftest is]]
            [loci.content :as content]
            [loci.lake-fixture :as lf :refer [with-lake]]
            [loci.mold :as mold]
            [loci.tablestore :as tbl]))

;; `loci.content` must be loaded for `register-default-viewers!` to have run —
;; the registry is a defonce atom and an empty one makes every `mold` below
;; answer `{:view nil :label "raw"}`, which reads as a wrong render rather than
;; as a missing require.

(deftest a-viewer-that-needs-the-store-is-handed-it
  (let [seen (atom nil)]
    (mold/register! {:id :test/needs :label "Needs" :kind :table :needs-store true
                     :pred (fn [_] true)
                     :render (fn [v ctx] (reset! seen ctx) v)})
    (try
      (mold/mold [{:a 1}] :test/needs {:store :STORE :handle :HANDLE})
      (is (= {:store :STORE :handle :HANDLE} @seen))
      (finally (mold/forget! :test/needs)))))

(deftest a-viewer-that-does-not-is-called-exactly-as-before
  (let [n (atom 0)]
    (mold/register! {:id :test/plain :label "Plain" :kind :table
                     :pred (fn [_] true) :render (fn [v] (swap! n inc) v)})
    (try
      (is (= [{:a 1}] (:rendered (mold/mold [{:a 1}] :test/plain {:store :S :handle :H})))
          "a one-argument render must not see the context, even when one exists")
      (is (= 1 @n))
      (finally (mold/forget! :test/plain)))))

(deftest a-needs-store-viewer-with-no-store-is-not-offered
  (mold/register! {:id :test/needs2 :label "Needs" :kind :table :needs-store true
                   :pred (fn [_] true) :render (fn [v _] v)})
  (try
    (is (not (contains? (set (map :id (mold/viewers-for [{:a 1}] nil))) :test/needs2))
        "a viewer that cannot run without a handle must not appear in the menu for a
         literal-vector table")
    (finally (mold/forget! :test/needs2))))
```

- [ ] **Step 2: Run, watch fail.**

```
clojure -M:test -v loci.content-test
```

- [ ] **Step 3: Implement.** In `src/loci/mold.clj`:

```clojure
(defn viewers-for
  "Every registered viewer whose predicate accepts obj, in precedence order —
   this is the 'view this as…' menu.

   `ctx` is `{:store :handle}` or nil. A viewer marked `:needs-store` computes
   its answer in the database and cannot run without one, so with no ctx it is
   not offered at all. That is the case of a table whose `:value` is a literal
   vector rather than a handle — the shape `hydrate` passes straight through."
  ([obj] (viewers-for obj nil))
  ([obj ctx]
   (filterv (fn [{:keys [pred needs-store]}]
              (and (or (not needs-store) (some? (:handle ctx)))
                   (try (boolean (pred obj)) (catch Throwable _ false))))
            @registry)))

(defn mold
  ([obj] (mold obj nil nil))
  ([obj view-id] (mold obj view-id nil))
  ([obj view-id ctx]
   (let [matches (viewers-for obj ctx)
         chosen  (or (when view-id (some #(when (= (:id %) view-id) %) @registry))
                     (first matches))]
     (if chosen
       {:view         (:id chosen)
        :label        (:label chosen)
        :kind         (:kind chosen :data)
        ;; Explicit `:needs-store` rather than dispatching on the render fn's
        ;; arity: a viewer that declares the wrong thing fails at registration,
        ;; where the mistake is, and not mid-render on somebody's table.
        :rendered     (if (:needs-store chosen)
                        ((:render chosen) obj ctx)
                        ((:render chosen) obj))
        :alternatives (mapv (juxt :id :label) matches)}
       {:view nil :label "raw" :kind :data :rendered obj :alternatives []}))))
```

Add to `register!`'s assert:

```clojure
  (assert (and id pred render) "a viewer needs :id, :pred and :render")
  (assert (= (if (:needs-store viewer) 2 1)
             (->> (:arglists (meta render)) first count (or 1)))
          "a :needs-store viewer's :render takes [value ctx]; a plain one takes [value]")
```

*(If `:arglists` is not available on an anonymous fn — it will not be — drop that second assert and say so in the commit message. The first test in this task is the real guard.)*

In `server.clj`'s `:else` branch, build the ctx from the hydrated object:

```clojure
      :else
      ;; The HANDLE, read from the unhydrated object — `hydrate` replaced
      ;; `(:value o)` with rows, and a viewer that queries needs the thing the
      ;; rows came out of. `handle?` is public in `tablestore` and is the same
      ;; test `hydrate` itself branches on, so the two cannot disagree about what
      ;; a handle is.
      (let [h   (:value (sub/object st id))
            ctx (when (tbl/handle? h) {:store (force lake/lake) :handle h})
            mv  (mold/mold (:value o) (when (seq view) (str->kw view)) ctx)]
        (cond-> {:id id :title (:title o) :kind (name (:kind mv))
                 :view (when (:view mv) (kw->str (:view mv)))
                 :label (:label mv) :rendered (:rendered mv)
                 :alternatives (alternatives st o ctx) :source (source-block o)
                 :table/total (:table/total o) :table/cols (:table/cols o)}
          (and (:page/next o) (#{:table/rows :table/cards} (:view mv)))
          (assoc :page/next (:page/next o))))
```

`alternatives` grows the same third argument and passes it to `mold/mold`:

```clojure
(defn- alternatives
  ([st o] (alternatives st o nil))
  ([st o ctx]
   ;; The SAME ctx the render got. A menu built without it would offer the
   ;; rows-only viewers while the render used the querying ones, so the label a
   ;; person clicked and the answer they got would come from different code.
   (vec (concat (mapv (fn [[vid lbl]] [(kw->str vid) lbl])
                      (:alternatives (mold/mold (:value o) nil ctx)))
                (mapv (fn [v] [(:id v) (get-in v [:value :label])])
                      (dynamic-views st (:id o)))))))
```

- [ ] **Step 4: Green.** **Step 5: Prove it bites** — always call the one-argument form; `a-viewer-that-needs-the-store-is-handed-it` must fail with an arity error. Drop the `needs-store` filter in `viewers-for`; the third test must fail.

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/mold.clj src/loci/server.clj test/loci/content_test.clj
git commit -m "feat: a viewer may ask for the store, and one that needs it is not offered without"
```

---

## Task 11: predicates and column choice stop reading the sample

**Files:**
- Modify: `src/loci/content.clj` — `numericish?`, `categorical?`, `timeseries?`, `measure-col`, `cat-col`, `rate-col?` (`:195-236`)
- Modify: `test/loci/content_test.clj`

`column-samples` already queries the whole table past `mold-cap` and is memoised per handle — `sampling-sees-past-the-mold-cap` and `sampling-an-immutable-handle-happens-once` in `test/loci/lake_test.clj` are the proof. This task rides on it rather than adding a mechanism.

- [ ] **Step 1: Write the failing test.**

```clojure
(deftest a-category-is-judged-past-the-mold-cap
  ;; Sorted so the variety begins past row 2,000 — the shape of
  ;; lake_test's sampling-sees-past-the-mold-cap, for the reason it exists.
  (lf/with-lake [lk]
    (let [rs (vec (for [i (range 3000)]
                    {:region (if (< i 2500) "AAA" (str "r" i)) :amount i}))
          h  (tbl/put-rows! lk "tbl:cat" rs)
          o  (tbl/hydrate lk {:kind :table :value h})]
      (is (nil? (content/cat-col-of (:value o) {:store lk :handle h}))
          "500 distinct regions is not a category, however uniform the first 2,000 rows look"))))
```

*(Expose `cat-col-of` and its siblings — the ctx-taking forms — as public in `content.clj`; the `rows`-only forms stay private and stay the fallback.)*

- [ ] **Step 2: Run, watch fail.** Expected: `AAA`-only sample makes `cat-col` answer `:region`.

- [ ] **Step 3: Implement.** In `src/loci/content.clj`:

```clojure
(def ^:private cat-max
  "Distinct values above which a column is a label and not a category. Unchanged
   from the rows-only `cat-col` — this task changes what is looked at, not the
   judgement."
  12)

(defn cat-col-of
  "The category column to group by, judged over the WHOLE column when a handle is
   in hand and over the rows when one is not.

   `column-samples` is asked for `cat-max + 1` values per column: a column that
   can return 13 distinct values is not a category, and no count is needed to
   know it. That call is memoised per handle — `sampling-an-immutable-handle-
   happens-once` in `loci.lake-test` — so a menu, a bar and a pivot over one
   table share one query.

   Falls back to the rows-only form for a literal-vector `:value`, where the
   rows ARE the table and the sample cannot be wrong."
  [rows {:keys [store handle] :as ctx}]
  (if-not (and store handle)
    (cat-col rows)
    (let [samples (tbl/column-samples store handle (inc cat-max) 64)
          strings (filter (fn [[c vs]] (and (seq vs) (not-every? #(re-matches #"-?\d+(\.\d+)?" %) vs)))
                          samples)]
      (->> strings
           (keep (fn [[c vs]] (let [d (count vs)]
                                (when (and (> d 1) (<= d cat-max)) [d c]))))
           sort first second))))
```

`measure-col-of`, `rate-col-of`, `numericish?-of`, `categorical?-of` and `timeseries?-of` follow the same two-branch shape: ctx present → read the handle's `:cols` and `column-samples`; ctx absent → call today's private rows-only function unchanged. **Do not delete the rows-only forms** — they are the literal-vector path and `InlineTable`'s, and both are correct there.

- [ ] **Step 4: Green.** **Step 5: Prove it bites** — pass `nil` ctx from the caller so the rows path is taken; the test must fail with `:region`.

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/content.clj test/loci/content_test.clj
git commit -m "feat: the menu is judged on the whole column, through the samples that were already cached"
```

---

## Task 12: `summary` and `histogram` in SQL

**Files:**
- Modify: `src/loci/content.clj` — `summary` (`:240`), `histogram` (`:289`), `register-default-viewers!`
- Modify: `src/loci/lake.clj` — a `column-stats` helper on the Lake
- Modify: `test/loci/content_test.clj`

- [ ] **Step 1: Write the failing test.**

```clojure
(deftest a-summary-counts-every-row-and-not-the-first-two-thousand
  (lf/with-lake [lk]
    (let [rs (vec (for [i (range 3000)] {:amount i}))
          h  (tbl/put-rows! lk "tbl:sum" rs)
          o  (tbl/hydrate lk {:kind :table :value h})
          s  (first (:rendered (mold/mold (:value o) :table/summary {:store lk :handle h})))]
      (is (= 3000 (:n s))     "n is the table's rows, not the sample's")
      (is (= 2999 (:max s))   "the maximum lives past the cap")
      (is (= 4498500 (:sum s)) "and so does most of the sum"))))
```

`4498500` is `(reduce + (range 3000))`. Against today's code the sample's sum is `(reduce + (range 2000))` = `1999000`.

- [ ] **Step 2: Run, watch fail.** **Report the two sums.** The gap between them is what this phase is for.

- [ ] **Step 3: Implement.** Add to `lake.clj`, beside `column-samples`:

```clojure
(defn column-stats
  "`{column {:n :missing :min :max :avg :sum}}` for each named column, over the
   WHOLE table, in ONE query.

   One query and not one per column: a 36-column table would otherwise be 36
   round trips to answer a panel nobody has scrolled to yet.

   `cols` is validated by the caller against `(:cols h)` before it arrives, the
   same rule `tablestore/sort-spec` states and for the same reason — these names
   reach a SELECT list."
  [lk h cols]
  (let [total (:rows h)
        sel   (str/join ", "
                (mapcat (fn [c]
                          (let [i (ident c)]
                            [(str "count(" i ")") (str "min(" i ")") (str "max(" i ")")
                             (str "avg(" i ")")   (str "sum(" i ")")]))
                        cols))
        ks    (vec (mapcat (fn [c] (for [k [:n :min :max :avg :sum]]
                                     (keyword (str (name c) "__" (name k)))))
                           cols))
        row   (first (q-as lk (str "SELECT " sel " FROM " (from-of h)) ks))]
    (into {}
          (for [c cols]
            (let [g #(get row (keyword (str (name c) "__" (name %))))
                  n (or (g :n) 0)]
              [c {:n n :missing (- total n)
                  :min (g :min) :max (g :max) :avg (g :avg) :sum (g :sum)}])))))
```

`q-as` names a result set positionally, so `ks` must be built in exactly the order `sel` is — five per column, same order both times. Getting that pairing wrong labels every figure with its neighbour's name and no test of a single-column table would notice, so **the summary test must use at least two numeric columns.**

`summary` becomes `:needs-store true` and renders from it. `histogram` asks for the true min/max, then bins in SQL.

For a literal-vector value the existing Clojure `summary`/`histogram` stay registered under the same ids and are chosen because `viewers-for` drops the `:needs-store` variants with no handle. **Register both, one `:needs-store`, one not** — the menu shows one because only one is ever offered.

- [ ] **Step 4: Green.** **Step 5: Prove it bites** — render through the rows path; the test must report `1999000`.

- [ ] **Step 6: Full suite, then commit.**

```
clojure -M:test
```

```bash
git add src/loci/content.clj src/loci/lake.clj test/loci/content_test.clj
git commit -m "feat: a summary is of the table, and the sum past row 2,000 is in it"
```

---

## Task 13: `bar`, `pivot`, `line`, `kpi`, `cohort` in SQL

**Files:**
- Modify: `src/loci/content.clj` — `bar` (`:249`), `line` (`:262`), `cohort` (`:267`), `pivot` (`:278`), `kpi` (`:302`)
- Modify: `src/loci/lake.clj` — a `group-agg` helper
- Modify: `test/loci/content_test.clj`

- [ ] **Step 1: Write the failing tests.** One per viewer, each over a 3,000-row fixture whose answer changes past row 2,000 — the shape of Task 12's. For `bar` and `pivot`, put the largest group entirely past the cap so the ordering changes, not just the numbers:

```clojure
(deftest a-bar-ranks-groups-by-the-whole-column
  (lf/with-lake [lk]
    (let [rs (vec (concat (for [i (range 2000)] {:grp "early" :amount 1})
                          (for [i (range 1000)] {:grp "late"  :amount 100})))
          h  (tbl/put-rows! lk "tbl:bar" rs)
          o  (tbl/hydrate lk {:kind :table :value h})
          b  (mold/mold (:value o) :table/bar {:store lk :handle h})]
      (is (= "late" (get (first (:rows (:rendered b))) "grp"))
          "the biggest group is entirely past the cap, so the sample cannot see it at all"))))
```

- [ ] **Step 2: Run, watch fail.** Against today's code `early` ranks first and `late` does not appear.

- [ ] **Step 3: Implement.** On the Lake, beside `column-stats`:

```clojure
(defn group-agg
  "`[{:k <category> :sum :avg :n} …]` for `val` grouped by `cat`, biggest first,
   over the whole table.

   `limit` caps the groups returned rather than the rows scanned: a category
   column is at most `cat-max` distinct by construction (`content/cat-col-of`
   refuses anything wider), so this is a guard against a caller that skipped
   that check and not a sample."
  [lk h cat val limit]
  (q-as lk (str "SELECT " (ident cat) ", sum(" (ident val) "), avg(" (ident val) "), count(*)"
                " FROM " (from-of h)
                " GROUP BY " (ident cat)
                " ORDER BY 2 DESC NULLS LAST"
                " LIMIT " (long limit))
        [:k :sum :avg :n]))
```

`bar` and `pivot` render straight from it. `line`, `kpi` and `cohort` group by `month` through the same function with `cat` = `"month"` and then **keep their existing output shapes exactly** — `chartSVG` reads `:chart`, `:x`, `:y`, `:series` and `:rows`, and `renderKPI` reads `:metric`, `:latest`, `:delta`, `:pct`, `:min`, `:max` and `:periods`. A renamed key here draws nothing and no Clojure test sees it.

Every column name goes through `ident`, and every one is validated against `(:cols h)` first — the same rule as `sort-spec`, for the same reason.

- [ ] **Step 4: Green.** **Step 5: Prove each bites** — render through the rows path for each viewer in turn; each test must fail on its own.

- [ ] **Step 6: Both suites, then commit.**

```
clojure -M:test
npm run test:browser
```

The browser suite matters here: `chartSVG` and `renderKPI` read specific keys out of these payloads, and a renamed key is invisible to the Clojure tests.

```bash
git add src/loci/content.clj src/loci/lake.clj test/loci/content_test.clj
git commit -m "feat: every chart is of the whole table, and a group past the cap can win"
```

---

## Task 14: the payload, measured

**Files:**
- Modify: `test/loci/server_test.clj`

The number this whole plan is judged against. **5,428,753 bytes** was `GET /api/mold?id=tbl:datasets_2026_06_14_20_03` on 2026-08-25 — 36 columns × 2,000 rows, at 2,715 bytes a row.

- [ ] **Step 1: Write the test.**

```clojure
(deftest a-windowed-mold-is-an-order-of-magnitude-smaller
  ;; 5,428,753 bytes was the real payload for a 36-column 2,000-row table on
  ;; 2026-08-25. A 100-row window is 1/20th of the rows, so assert an order of
  ;; magnitude and not a figure: a byte-exact number here would fail on any
  ;; change to a key name and would be measuring JSON, not the fix.
  (with-lake [lk]
    (let [st (sub/fresh-store)
          rs (vec (for [i (range 2000)]
                    (into {:note (apply str (repeat 900 "x"))}
                          (for [c (range 35)] [(keyword (str "c" c)) (* i (inc c))]))))]
      (sub/commit! st {:op :put :id "tbl:big"
                       :value (tbl/table-object lk "tbl:big" "Big" rs {})})
      (let [full   (count (json/write-str (srv/mold-payload st no-memory "tbl:big" nil)))
            windowed (count (json/write-str (srv/mold-payload st no-memory "tbl:big" nil
                                                              {:limit 100})))]
        (is (< (* 15 windowed) full)
            (str "windowed " windowed " vs sample " full " — the window must be "
                 "roughly a twentieth, and this is what a notebook cell now ships"))))))
```

- [ ] **Step 2: Run.** It should pass — Tasks 3 and 4 built it. **Report both numbers.**

- [ ] **Step 3: Prove it bites.** Pass `nil` as the window; the assertion must fail with two nearly equal numbers.

- [ ] **Step 4: Commit.**

```bash
git add test/loci/server_test.clj
git commit -m "test: the window is a twentieth of the sample, and something says so"
```

---

## Finishing

- [ ] `clojure -M:test` and `npm run test:browser`, both green, both foreground.
- [ ] Update the spec's Status line to **built and measured, 2026-08-25**, and add a short section recording what the building changed — in particular, whether the `register!` arity assert in Task 10 survived, and the real before/after payload numbers from Task 14.
- [ ] Use `superpowers:finishing-a-development-branch` to decide how `paged-table-grid` lands.
