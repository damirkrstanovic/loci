# Tag Colours Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every tag carries a colour — suggested from an eight-ink palette the first time the
tag is set, changeable by you, stored in the substrate as ordinary reversible events.

**Architecture:** One registry object (`tag-palette`) maps tag name → hex. `set-tags!`
assigns inks to unseen names and commits that **before** the tag event. `/api/state` carries
the map; the shell colours the strip chips and the `⌗ tags` editor from it.

**Tech Stack:** Clojure (`src/loci/server.clj`), vanilla JS/CSS
(`resources/public/index.html`), `clojure -M:test`, `npm run test:browser`.

**Spec:** `docs/superpowers/specs/2026-08-06-tag-colours-design.md`

---

## Context for every task

loci is an event-log substrate (`src/loci/substrate.clj`) with a vanilla-JS shell. Tags
already exist: a notebook's `:value :tags` is a vector of `{:tag :by :ts}`, set by
`set-tags!` in `src/loci/server.clj`, filtered in the overview by a tri-state strip in
`resources/public/index.html`.

**Two rules this plan must not break:**

1. `suggest-tags!` commits **zero** events. The agent proposing a tag must never touch the
   substrate. No colour is assigned for a proposal — only for a tag actually set.
2. `sub/undo!` undoes the **last** event. The colour event is therefore committed **before**
   the tag event, so undoing a tagging removes the tags and leaves the colour standing.
   This is a deliberate decision from spec §3, not an accident of ordering.

**Never point a test or a scratch server at the repo's `data/`.** It is the user's real
substrate. Tests use `sub/fresh-store`; the browser harness uses its own fixture.

Run both suites: `clojure -M:test` and `npm run test:browser`.

---

### Task 1: The palette and the assignment rule

**Files:**
- Modify: `src/loci/server.clj` (add above `set-tags!`, after `keep-tag-times` ~line 255)
- Test: `test/loci/server_test.clj` (append near the existing tag tests ~line 850)

- [ ] **Step 1: Write the failing tests**

Append to `test/loci/server_test.clj`:

```clojure
(deftest eight-tags-receive-eight-distinct-inks
  ;; the test that fails the moment assignment is reduced to a bare hash:
  ;; with eight inks and eight names a hash collides more often than not
  (let [st (tagged-store)]
    (doseq [t ["a" "b" "c" "d" "e" "f" "g" "h"]]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)]
      (is (= 8 (count reg)) "every name is in the registry")
      (is (= 8 (count (set (vals reg)))) "and no two share an ink"))))

(deftest the-ninth-tag-reuses-a-least-used-ink
  (let [st (tagged-store)]
    (doseq [t ["a" "b" "c" "d" "e" "f" "g" "h" "i"]]
      (srv/set-tags! st "space:t" [{:tag t :by "you"}]))
    (let [reg (srv/tag-colors st)
          f   (frequencies (vals reg))]
      (is (= 9 (count reg)))
      (is (every? (set srv/tag-inks) (vals reg)) "only palette inks are ever assigned")
      (is (= 2 (apply max (vals f))) "exactly one ink is doubled")
      (is (= 7 (count (filter #(= 1 %) (vals f)))) "the other seven are untouched"))))

(deftest several-new-tags-in-one-call-get-different-inks
  ;; assignment must accumulate within the call, or both new names take the
  ;; same "least-used" ink because neither is in the registry yet
  (let [st (tagged-store)]
    (srv/set-tags! st "space:t" [{:tag "x" :by "you"} {:tag "y" :by "you"}])
    (let [reg (srv/tag-colors st)]
      (is (= 2 (count reg)))
      (is (= 2 (count (set (vals reg))))))))

(deftest assignment-is-deterministic
  (let [ink (fn [] (let [st (tagged-store)]
                     (srv/set-tags! st "space:t" [{:tag "semiconductors" :by "you"}])
                     (get (srv/tag-colors st) "semiconductors")))]
    (is (= (ink) (ink)) "the same name always gets the same ink from an empty registry")))
```

- [ ] **Step 2: Run them to verify they fail**

Run: `clojure -M:test 2>&1 | tail -20`
Expected: FAIL — `No such var: srv/tag-colors` (or `srv/tag-inks`).

- [ ] **Step 3: Write the implementation**

In `src/loci/server.clj`, insert after `keep-tag-times` and before `set-tags!`:

```clojure
;; ---- tag colours ----
;; Eight inks in the paper's lightness band, so a strip of them reads as a
;; family. Clay (--attn) is deliberately absent: clay means EXCLUDED in the
;; strip, and a clay tag that was also excluded would be a chip you can't read.
(def tag-inks
  ["#2f6f5b"   ; green — loci's own accent, so the first tag looks like it belongs
   "#2b6b74"   ; teal
   "#3f5a8a"   ; indigo
   "#6b4a8a"   ; violet
   "#8c3f5a"   ; garnet
   "#7a5a2f"   ; bronze
   "#5f6b33"   ; olive
   "#4a5560"]) ; slate

(def ^:private palette-id "tag-palette")

(defn tag-colors
  "The registry: tag name → hex. Empty until the first tag is set."
  [st]
  (or (:value (sub/object st palette-id)) {}))

(defn- name-hash [s]
  (reduce (fn [h c] (unchecked-int (+ (* 31 h) (int c)))) 0 s))

(defn- next-ink
  "The ink used by the fewest tags already in `reg`, ties broken by a hash of
   the name. Hashing alone would collide: with eight inks and six tags, two
   subjects sharing an ink is likelier than not, and that is the one failure
   colour exists to prevent. So the hash only picks WHERE to start looking."
  [reg tag]
  (let [used  (frequencies (vals reg))
        n     (count tag-inks)
        start (mod (Math/abs (long (name-hash tag))) n)]
    (->> (range n)
         (map #(nth tag-inks (mod (+ start %) n)))
         (apply min-key #(get used % 0)))))

(defn- assign-inks!
  "Give every unseen name an ink — one event, or none when nothing is new.
   Callers MUST commit this BEFORE the tag event: undo! undoes the last event,
   so undoing a tagging has to remove the tags and leave the colour standing."
  [st names]
  (let [reg   (tag-colors st)
        fresh (remove #(contains? reg %) (distinct names))]
    (when (seq fresh)
      ;; reduce over the accumulating map, not `reg` — two new names in one
      ;; call would otherwise both take the same "least-used" ink
      (let [reg' (reduce (fn [m t] (assoc m t (next-ink m t))) reg fresh)]
        (if (sub/object st palette-id)
          (sub/commit! st {:op :assoc :id palette-id :path [:value] :value reg'})
          (sub/commit! st {:op :put :id palette-id
                           :value {:id palette-id :kind :palette
                                   :title "tag colours" :value reg'}}))
        reg'))))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `clojure -M:test 2>&1 | tail -20`
Expected: the four new tests pass. Existing tag tests may now fail on event
counts — that is Task 2's job; note which fail and move on.

- [ ] **Step 5: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: an eight-ink palette, assigned to the least-used ink"
```

---

### Task 2: `set-tags!` assigns, and the registry stays out of sight

**Files:**
- Modify: `src/loci/server.clj:257-269` (`set-tags!`), `:123` (`state-payload`), `:178` (LEAP)
- Test: `test/loci/server_test.clj:754-761` (amend), plus new tests

- [ ] **Step 1: Write the failing tests**

First **amend** the existing test at `test/loci/server_test.clj:754`, which asserts one
event and will now see two. Replace it entirely with:

```clojure
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
    (let [ids (map :id (:results (srv/leap-payload st "")))]
      (is (not-any? #(= "tag-palette" %) ids)))))

(deftest suggesting-tags-assigns-no-colours
  ;; proposing writes nothing at all — colours included
  (let [st (tagged-store)
        n  (count (sub/history st))]
    (with-redefs [loci.agent/propose-tags (fn [& _] ["ghost-tag"])]
      (srv/suggest-tags! st "space:t"))
    (is (= n (count (sub/history st))) "zero events")
    (is (empty? (srv/tag-colors st)) "and no ink claimed by a proposal you may discard")))
```

**Note on `leap-payload`:** check its actual arity and result key before writing that test —
read `src/loci/server.clj` around line 170. If the shape differs, adjust the assertion to
match, keeping the intent: `tag-palette` must not be in LEAP's results.

**Note on `suggest-tags!`:** check `loci.agent/propose-tags`'s real name and arity at
`src/loci/agent.clj` and `src/loci/server.clj:646` before writing the redef.

- [ ] **Step 2: Run to verify they fail**

Run: `clojure -M:test 2>&1 | tail -30`
Expected: FAIL — no `:tag-colors` in the payload, palette object present in `:objects`.

- [ ] **Step 3: Write the implementation**

Change `set-tags!` (`src/loci/server.clj:257`) — assignment goes **inside** the `when`, so
re-saving an unchanged strip is still free, and **before** the commit:

```clojure
(defn set-tags!
  "Replace a notebook's tags — one reversible event, or none when nothing
   changed. Provenance is content: approving the agent's tag as your own
   changes what the tag claims, so it earns an event of its own."
  [st space tags]
  (let [o (sub/object st space)]
    (if-not (= :space (:kind o))
      {:error (str "not a notebook: " space)}
      (let [was (get-in o [:value :tags])
            now (keep-tag-times was (clean-tags tags))]
        (when (not= (mapv (juxt :tag :by) was) (mapv (juxt :tag :by) now))
          ;; BEFORE the tag event, so undo! takes the tags and leaves the colour
          (assign-inks! st (mapv :tag now))
          (sub/commit! st {:op :assoc :id space :path [:value :tags] :value now}))
        {:state (state-payload st) :tags now}))))
```

In `state-payload` (`src/loci/server.clj:123`), add `:palette` to the hidden kinds and add
the map:

```clojure
     :objects (->> objs (remove #(#{:space :viewspec :applet :fn :palette} (:kind %)))
                   (map (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))}))
                   vec)
     :tag-colors (tag-colors st)}))
```

In LEAP (`src/loci/server.clj:178`), add `:palette`:

```clojure
                   (remove #(#{:viewspec :applet :fn :palette} (:kind %)))
```

**`tag-colors` is defined in Task 1 above `set-tags!`, which is above `state-payload`? No —
`state-payload` is at line 107, ABOVE the tag section at 228.** Move the `tag-colors`
defn (and only that one) up so it is defined before `state-payload` uses it, or declare it.
Prefer moving `tag-colors` and `palette-id` to just above `state-payload`; leave
`name-hash`, `next-ink` and `assign-inks!` where Task 1 put them. Verify with a clean
`clojure -M:test` run that there is no forward reference.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `clojure -M:test 2>&1 | tail -20`
Expected: all pass, including every pre-existing tag test.

- [ ] **Step 5: Prove the ordering test bites**

Swap the two lines inside the `when` so the tag event commits first and
`assign-inks!` second. Run `clojure -M:test`. Expected: FAIL on
`set-tags-commits-one-reversible-event`'s last two assertions (undo now removes the colour,
leaving the tags). **Restore the correct order** and confirm green.

- [ ] **Step 6: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: setting a tag claims its colour, before the tag event"
```

---

### Task 3: `POST /api/tag-color`

**Files:**
- Modify: `src/loci/server.clj` (add after `set-tags!`; route after `:1062`)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
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
```

- [ ] **Step 2: Run to verify they fail**

Run: `clojure -M:test 2>&1 | tail -20`
Expected: FAIL — `No such var: srv/set-tag-color!`.

- [ ] **Step 3: Write the implementation**

After `set-tags!` in `src/loci/server.clj`:

```clojure
(defn set-tag-color!
  "Choose a tag's colour — one reversible event, or none when it is already
   that. The palette is a closed set: a free-form hex would let the shell write
   a colour that fails the very contrast the palette was chosen for."
  [st tag color]
  (let [t (str/lower-case (str/trim (str tag)))]
    (cond
      (str/blank? t)                {:error "no tag"}
      (not ((set tag-inks) color))  {:error (str "not a palette colour: " color)}
      :else
      (do
        (when (not= color (get (tag-colors st) t))
          (if (sub/object st palette-id)
            (sub/commit! st {:op :assoc :id palette-id :path [:value t] :value color})
            (sub/commit! st {:op :put :id palette-id
                             :value {:id palette-id :kind :palette :title "tag colours"
                                     :value {t color}}})))
        {:state (state-payload st) :tag-colors (tag-colors st)}))))
```

Add the route after the `/api/tags` route (`src/loci/server.clj:1062`):

```clojure
      (= uri "/api/tag-color")(let [{:keys [tag color]} (body-json req)]
                                (json-resp (set-tag-color! st tag color)))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `clojure -M:test 2>&1 | tail -20`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: POST /api/tag-color — a closed palette, one event"
```

---

### Task 4: The strip wears the ink

**Files:**
- Modify: `resources/public/index.html` — CSS ~`:46-52`, `applyState` ~`:327`,
  `API` ~`:308`, `renderTagStrip` ~`:1258`

- [ ] **Step 1: Add the client palette and accessor**

Near `allTags()` (~line 1233) in `resources/public/index.html`:

```javascript
// mirrors srv/tag-inks — the server assigns from this list and refuses anything else
const TAG_INKS=['#2f6f5b','#2b6b74','#3f5a8a','#6b4a8a','#8c3f5a','#7a5a2f','#5f6b33','#4a5560'];
// a tag set before colours existed has no registry entry; fall back to a
// deterministic pick so an untouched corpus is never a wall of grey
function tagInk(t){
  const c=(STATE.tagColors||{})[t]; if(c) return c;
  let h=0; for(let i=0;i<t.length;i++) h=(h*31+t.charCodeAt(i))>>>0;
  return TAG_INKS[h%TAG_INKS.length];
}
```

- [ ] **Step 2: Carry the map into STATE**

In `applyState` (~line 327), alongside the existing assignments, add:

```javascript
  STATE.tagColors = s['tag-colors'] || {};
```

Place it with the other `STATE.x = s.y` lines in that function — read the function first and
match where `STATE.spaces` and `STATE.objects` are set.

- [ ] **Step 3: Add the API method**

At `resources/public/index.html:308`, after `tags:` and `tagSuggest:`:

```javascript
  tagColor: (tag,color) => POST('/api/tag-color',{tag,color}),
```

- [ ] **Step 4: Style the swatch and the include state**

Replace the `.tagchip` rules at `resources/public/index.html:46-52` with:

```css
  .tagchip{font-family:var(--mono);font-size:10px;letter-spacing:.05em;padding:3px 9px;
           border-radius:2px;border:1px solid var(--line);background:var(--white);
           color:var(--muted);cursor:pointer;display:inline-flex;align-items:center;gap:6px}
  .tagchip i{font-style:normal;color:var(--faint);margin-left:4px}
  /* the swatch carries identity; the fill and the strike carry state */
  .tagchip .ink{width:6px;height:6px;border-radius:1px;flex:none;
                background:var(--tag-ink,var(--accent));text-decoration:none}
  .tagchip.in{border-color:var(--tag-ink,var(--accent-line));color:var(--tag-ink,var(--accent));
              background:color-mix(in srgb,var(--tag-ink,var(--accent)) 11%,transparent)}
  /* exclude overrides the ink: the strike and the clay are what say "subtracting",
     and the swatch still says which tag it is */
  .tagchip.out{border-color:var(--attn);color:var(--attn);background:var(--attn-soft);
               text-decoration:line-through}
```

- [ ] **Step 5: Render it**

Replace the `strip.innerHTML=` expression in `renderTagStrip` (`:1264-1266`):

```javascript
  strip.innerHTML=tags.map(([t,n])=>
    '<button class="tagchip '+(tagState[t]||'')+'" data-tag="'+esc(t)+'" '+
    'style="--tag-ink:'+tagInk(t)+'" '+
    'title="click: include · again: exclude · again: off">'+
    '<u class="ink"></u>'+esc(t)+' <i>'+n+'</i></button>').join('');
```

- [ ] **Step 6: Verify by hand against a throwaway server**

```bash
SP=$(mktemp -d) && cp -r data/substrate "$SP/" && mkdir -p "$SP/data" && mv "$SP/substrate" "$SP/data/"
LOCI_DATA="$SP/data" clojure -M -e '(require (quote loci.server) (quote org.httpkit.server)) (org.httpkit.server/run-server (var loci.server/handler) {:port 7799}) @(promise)'
```

Open `http://localhost:7799`, press Escape for the overview, confirm each chip shows a
coloured square and that clicking once fills the chip in **that tag's** colour rather than
green. Stop the server when done. **Never point this at `data/` directly.**

- [ ] **Step 7: Commit**

```bash
git add resources/public/index.html
git commit -m "feat: the tag strip wears its ink — identity in the swatch, state in the fill"
```

---

### Task 5: Choosing a colour in the editor

**Files:**
- Modify: `resources/public/index.html` — `startTags` ~`:601-640`, CSS near `:54-57`

- [ ] **Step 1: Style the picker**

After the `.tagedit` rules (`resources/public/index.html:53-57`), add:

```css
  .tagedit .tagchip{cursor:default}
  .tagedit .tagchip .ink{cursor:pointer;outline-offset:2px}
  .tagedit .tagchip .ink:hover{outline:1px solid var(--line)}
  .inkpick{display:flex;gap:5px;padding:6px 8px;margin-top:8px;width:max-content;
           border:1px solid var(--line);border-radius:4px;background:var(--white)}
  .inkpick b{width:14px;height:14px;border-radius:2px;cursor:pointer;border:1px solid transparent}
  .inkpick b.on{border-color:var(--ink)}
```

- [ ] **Step 2: Render the swatch in the editor and open the picker**

In `startTags` (`resources/public/index.html:601`), change the chip markup inside `draw()`
so each chip carries its ink and a clickable swatch:

```javascript
      '<div class="tagedit">'+(tags.length?tags.map((t,k)=>
        '<span class="tagchip '+(t.by==='agent'?'byagent':'')+'" data-k="'+k+'" '+
        'style="--tag-ink:'+tagInk(t.tag)+'">'+
        '<u class="ink" data-ink="'+esc(t.tag)+'" title="choose a colour"></u>'+esc(t.tag)+
        ' <span class="x">✕</span></span>').join(''):'<i style="color:var(--muted)">no tags yet</i>')+
      '</div><div id="inkpickhost"></div>'+
```

Then, alongside the existing `.tagedit .x` handler (`:617`), add:

```javascript
    b.querySelectorAll('.tagedit .ink').forEach(sw=>sw.addEventListener('click',e=>{
      const tag=e.target.dataset.ink, host=b.querySelector('#inkpickhost');
      if(host.dataset.open===tag){ host.innerHTML=''; host.dataset.open=''; return; }
      host.dataset.open=tag;
      host.innerHTML='<div class="inkpick">'+TAG_INKS.map(c=>
        '<b data-c="'+c+'" class="'+(tagInk(tag)===c?'on':'')+'" style="background:'+c+'"></b>'
      ).join('')+'</div>';
      host.querySelectorAll('.inkpick b').forEach(sq=>sq.addEventListener('click',async()=>{
        if(TIME){ showToast('time mode — ↩ now to edit'); return; }
        // a colour is global and standing: choosing one for a tag you have
        // typed but not saved still records it, because the colour is about
        // the name, not about this notebook
        let r; try{ r=await API.tagColor(tag,sq.dataset.c); }
        catch(e){ showToast('colour: network error'); return; }
        if(r.error){ showToast(r.error); return; }
        applyState(r.state); host.innerHTML=''; host.dataset.open='';
        draw(); renderTagStrip();
      }));
    }));
```

**Read `startTags` in full before editing** — `draw()` re-renders `b.innerHTML` on every
call, so every handler must be re-attached inside `draw()`, which is where the existing
`.x` and `#tagIn` handlers already live. Put the new handler there too.

- [ ] **Step 3: Verify by hand**

Start the throwaway server from Task 4 Step 6. Focus a notebook, press `⌗ tags`, click a
swatch, pick a different colour. Confirm: the editor chip changes, and pressing Escape to
the overview shows the strip chip in the new colour. Confirm picking the colour it already
has commits nothing (`/api/state`'s `events` count is unchanged).

- [ ] **Step 4: Commit**

```bash
git add resources/public/index.html
git commit -m "feat: pick a tag's colour from the palette — one event, everywhere at once"
```

---

### Task 6: Browser tests

**Files:**
- Modify: `test/browser/tags.test.mjs`
- Reference: `test/browser/harness.mjs:65-68` (the fixture already sets four tags, which now
  receive inks automatically)

- [ ] **Step 1: Read the existing file**

Read `test/browser/tags.test.mjs` and `test/browser/harness.mjs` in full. Match their
existing style for launching the page and reaching the overview.

- [ ] **Step 2: Write the failing tests**

Append tests asserting **computed colour**, never class names — a class-counting assertion
passed straight through a shipped no-op on 2026-08-06, because `layout()` writes inline
styles that beat stylesheet rules:

```javascript
test('every strip chip renders its tag ink, and no two tags share one', async () => {
  // …reach the overview as the existing tests do…
  const inks = await page.evaluate(() => [...document.querySelectorAll('#tagstrip .tagchip')]
    .map(c => ({tag: c.dataset.tag,
                sw: getComputedStyle(c.querySelector('.ink')).backgroundColor})));
  assert.ok(inks.length >= 4);
  for (const i of inks) assert.notStrictEqual(i.sw, 'rgba(0, 0, 0, 0)', `${i.tag} has no ink`);
  assert.strictEqual(new Set(inks.map(i => i.sw)).size, inks.length, 'distinct inks');
});

test('an included chip borders in its own ink, not the generic accent', async () => {
  // click one chip once, then compare its computed borderColor to its swatch colour
  // and assert it differs from the accent green used before colours existed
});

test('an excluded chip is clay and struck, and still shows its ink', async () => {
  // click the same chip twice; assert textDecorationLine contains 'line-through',
  // borderColor is the clay, and the swatch backgroundColor is unchanged
});

test('picking a colour in the editor changes the strip chip too', async () => {
  // focus the notebook, open ⌗ tags, click the swatch, pick TAG_INKS[4],
  // return to the overview, assert the strip chip's swatch is now that colour
});
```

Fill in each body completely — no placeholders in the committed file.

- [ ] **Step 3: Run to verify they fail**

Run: `npm run test:browser 2>&1 | tail -20`
Expected: FAIL on the new tests only.

- [ ] **Step 4: Make them pass**

If Tasks 4–5 are correct they should pass as written. If one fails, fix the shell, not the
test.

- [ ] **Step 5: Prove each test bites**

For each of the four: break the thing it tests (delete the `--tag-ink` style attribute from
`renderTagStrip`; make `.tagchip.in` use `var(--accent-line)` again; drop the swatch from
the `.out` rule; make the picker skip `renderTagStrip()`), confirm **that** test fails, then
restore. A test that passes either way certifies nothing.

- [ ] **Step 6: Commit**

```bash
git add test/browser/tags.test.mjs
git commit -m "test: tag inks — assigned, distinct, and survived by the exclude state"
```

---

### Task 7: Docs and final verification

**Files:**
- Modify: `README.md` (the tags paragraph and the endpoint table added in the tags plan)

- [ ] **Step 1: Extend the tags paragraph**

Find the existing tags paragraph in `README.md` and add, in the same register:

```markdown
Each tag carries a colour, so a tag is recognisable before it is read. The first time a tag
is set it takes the least-used ink from a palette of eight; the swatch on its chip opens the
palette, and changing it changes that tag everywhere at once. Undoing a tagging removes the
tags and leaves the colour — a colour is a standing preference about a subject, not a claim
about a notebook.
```

- [ ] **Step 2: Add the endpoint row**

Under the `POST /api/tag-suggest` row:

```markdown
| `POST /api/tag-color` | choose a tag's colour — one reversible event |
```

- [ ] **Step 3: Run both suites**

```bash
clojure -M:test 2>&1 | tail -5
npm run test:browser 2>&1 | tail -5
```

Expected: both green. Report the actual counts, not "tests pass".

- [ ] **Step 4: Confirm the user's substrate is untouched**

```bash
md5sum data/substrate.edn
git status --short
```

Expected: `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, and only `README.md` modified.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: tag colours — suggested on creation, chosen by you"
```
