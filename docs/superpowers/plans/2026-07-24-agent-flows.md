# Agent Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The full agentic flow as a substrate object: the agent plans a multi-step flow (`:flow`), an interpreter executes it over the existing verbs, every transition is a reversible event, a `gate` step parks it "needs-you" until the user approves — no framework, the substrate IS the framework.

**Architecture:** A `:flow` object (`{:goal :space :status :steps [{:verb :args :note :status :out}]}`) is committed as one `:tx` (flow + a notebook cell referencing it) inside a background job after the agent plans it. `run-flow!` walks pending steps: mark running (event) → execute via the EXISTING machinery (`research!`, `compute-clj!`, `ask!`+`keep-note!`, `delegate!`) → mark done with the produced object id (event). `$N` in args resolves to step N's output. A `gate` step sets flow+step `needs-you` and ends the job; `flow-gate!` (approve) resumes as a new job, (reject) stops. The flow renders as a moldable cell (`kind "flow"`): a live checklist with status icons, clickable output chips, and Approve/Reject when gated. Because every transition is an event, checkpointing/resume/replay come free — and a flow's execution is visible in the ⏱ scrubber.

**Tech Stack:** Clojure server + vanilla-JS shell, cognitect runner. Baseline: 56 tests / 190 assertions green on `d5d0487`.

**Decisions (from the accepted recommendation):** verbs v1 = `research | compute | ask | draft | gate`; plans capped at 6 steps, unknown verbs dropped at validation (never guessed); one flow object per launch (concurrent flows in one space not prevented — prototype limit); a step failure fails the flow honestly (no retries in v1); undo during a running flow is an accepted race (documented); flows are first-class objects — findable in LEAP, visible in `/api/state` objects, moldable. Deferred: retries, parallel steps, re-planning mid-flow, flow templates, per-verb model selection.

**Facts about the codebase the implementer needs:**
- `ask!` returns `{:answer text}` or `{:error}`; `keep-note! [st space title text]` commits a note cell and returns `{:state … :openId note-id}`; `research!`/`compute-clj!`/`delegate!` return `{:openId …}` or `{:error}`.
- `start-job!`/`job-status` exist (jobs layer); `next-id st "flow:"` mints ids; `nb/append-cell-event`, `space?`, `state-payload`, `mold-payload` exist.
- `state-payload`'s objects list excludes `#{:space :viewspec :applet :fn}` — `:flow` is deliberately NOT excluded (first-class, LEAP-findable). `leap-payload`'s objs listing has the same exclusion set — likewise leave `:flow` in.
- `agent/request` takes `:json?` for JSON-mode responses; `agent/chat` is plain text. Model is `deepseek-v4-flash`.
- The shell has `pollJob`, `TIME` guards convention (`if(TIME){toast;return;}` around writes and late-landing responses), `esc()` discipline, `.actions` is hidden by `.world.timemode` CSS.

---

### Task 1: planning + pure flow model (validate, resolve) — server

**Files:**
- Modify: `src/loci/agent.clj` (add `plan-flow`)
- Modify: `src/loci/server.clj` (pure helpers)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Failing tests** (pure parts only — `plan-flow` is LLM-bound, not unit-tested):

```clojure
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
    (is (= "" (:note (second v))))))                      ; missing note → ""

(deftest flow-step-refs-resolve-to-outputs
  (let [flow {:steps [{:out "tbl:derived-3"} {:out "note:n-1"}]}]
    (is (= "tbl:derived-3" (srv/resolve-ref flow "$0")))
    (is (= "note:n-1" (srv/resolve-ref flow "$1")))
    (is (= "$9" (srv/resolve-ref flow "$9")))             ; out-of-range → literal, honest
    (is (= "tbl:t" (srv/resolve-ref flow "tbl:t")))       ; plain ids pass through
    (is (= 5 (srv/resolve-ref flow 5)))))                 ; non-strings untouched
```

- [ ] **Step 2: red** — No such var: `srv/validate-plan`.

- [ ] **Step 3: Implementation.** In server.clj (new `;; ---- flows ----` section, before the routing):

```clojure
;; ---- flows: the full agentic loop as a substrate object. The agent plans,
;; the interpreter executes over the existing verbs, EVERY transition is a
;; reversible event — checkpoint/resume/replay come from the log, and a
;; gate step parks the flow needs-you until the human says go. ----
(def ^:private flow-verbs #{"research" "compute" "ask" "draft" "gate"})

(defn validate-plan
  "Agent-proposed steps → trusted steps: unknown verbs dropped, capped at 6,
   every step normalized to {:verb :args :note :status \"pending\"}."
  [steps]
  (->> steps
       (keep (fn [s] (let [v (str (:verb s))]
                       (when (flow-verbs v)
                         {:verb v :args (or (:args s) {}) :note (str (or (:note s) ""))
                          :status "pending"}))))
       (take 6) vec))

(defn resolve-ref
  "\"$N\" in step args means step N's output object id."
  [flow v]
  (if (and (string? v) (str/starts-with? v "$"))
    (or (get-in flow [:steps (or (parse-long (subs v 1)) -1) :out]) v)
    v))
```

In agent.clj (after `propose-subtopics`, matching its style):

```clojure
(defn plan-flow
  "Plan a multi-step flow toward `goal`. Returns raw steps (server validates)."
  [goal ctx]
  (let [sys (str "You plan a short agent flow for a research notebook. Return JSON "
                 "{\"steps\":[{\"verb\":…,\"args\":{…},\"note\":\"why this step\"}]} — at most 6 steps.\n"
                 "Verbs:\n"
                 "- research {\"prompt\"}: web+data research, lands findings (and often a table) in the notebook\n"
                 "- compute {\"id\",\"prompt\"}: derive a new table from table `id` (use \"$N\" for step N's output)\n"
                 "- ask {\"prompt\"}: answer a question from the notebook's data, kept as a note\n"
                 "- draft {}: write a brief from everything in the notebook\n"
                 "- gate {\"question\"}: STOP and ask the human before continuing — use before expensive or judgment-heavy steps\n"
                 "Prefer research → gate → compute/ask → draft shapes. Only JSON.")
        out (request [{:role "system" :content sys}
                      {:role "user" :content (str "Goal: " goal "\n\nNotebook context:\n" ctx)}]
                     :json? true)]
    (:steps (json/read-str (:content out) :key-fn keyword))))
```

- [ ] **Step 4: green** (58 tests). **Step 5: Commit** — `feat: flow model — agent plans validated into substrate-shaped steps`

---

### Task 2: the interpreter — run, gate, approve/reject, endpoints, flow mold

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Failing tests.** All interpreter tests stub the verbs with `with-redefs` — no LLM calls:

```clojure
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
                                           (= "pending" (get-in v [:steps 1 :status]))) k))
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
          m   (srv/mold-payload st fid nil)]
      (is (= "flow" (:kind m)))
      (is (= "g" (get-in m [:rendered :goal]))))))
```

- [ ] **Step 2: red** — No such var: `srv/flow-create!`.

- [ ] **Step 3: Implementation** (after the validate/resolve helpers). Note `flow-create!` (plan already validated → object + cell, ONE `:tx`) is separate from `flow-start!` (endpoint entry: validates space, then plans+creates+runs inside a job):

```clojure
(defn flow-create!
  "Commit the flow object + its notebook cell as ONE :tx. Steps must already
   be validated. Returns the flow id."
  [st space goal steps]
  (let [fid (next-id st "flow:")
        fobj {:id fid :kind :flow :title (str "Flow — " (if (> (count goal) 40) (str (subs goal 0 40) "…") goal))
              :value {:goal goal :space space :status "running" :steps (vec steps)}}]
    (sub/commit! st {:op :tx :events [{:op :put :id fid :value fobj}
                                      (nb/append-cell-event st space {:ref fid})]})
    fid))

(defn- flow-assoc! [st fid value]
  (sub/commit! st {:op :assoc :id fid :path [:value] :value value}))

(defn- flow-step!
  "One transition: update step i (and optionally the flow status) — one event."
  [st fid i patch & [flow-status]]
  (let [fl (:value (sub/object st fid))
        fl (cond-> (update-in fl [:steps i] merge patch)
             flow-status (assoc :status flow-status))]
    (flow-assoc! st fid fl)
    fl))

(defn- exec-step [st flow space {:keys [verb args]}]
  (case verb
    "research" (let [r (research! st space (str (:prompt args)))]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "compute"  (let [r (compute-clj! st (str (resolve-ref flow (:id args))) (str (:prompt args)) space)]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "draft"    (let [r (delegate! st space)]
                 (if (:error r) {:why (:error r)} {:out (:openId r)}))
    "ask"      (let [r (ask! st (str (:prompt args)) space)]
                 (if (:error r)
                   {:why (:error r)}
                   (let [k (keep-note! st space (str "Answer — " (:prompt args)) (:answer r))]
                     (if (:error k) {:why (:error k)} {:out (:openId k)}))))
    {:why (str "unknown verb " verb)}))

(defn run-flow!
  "Execute pending steps in order. Stops at a gate (needs-you), a failure
   (failed), or the end (done). Synchronous — callers wrap in start-job!."
  [st fid]
  (loop []
    (let [fl (:value (sub/object st fid))
          i  (first (keep-indexed (fn [k s] (when (= "pending" (:status s)) k)) (:steps fl)))]
      (cond
        (nil? i)
        (do (when (not= "done" (:status fl)) (flow-assoc! st fid (assoc fl :status "done")))
            {:state (state-payload st) :flowId fid :status "done"})

        (= "gate" (get-in fl [:steps i :verb]))
        (do (flow-step! st fid i {:status "needs-you"} "needs-you")
            {:state (state-payload st) :flowId fid :status "needs-you"})

        :else
        (let [_  (flow-step! st fid i {:status "running"})
              fl (:value (sub/object st fid))
              r  (exec-step st fl (:space fl) (get-in fl [:steps i]))]
          (if (:why r)
            (do (flow-step! st fid i {:status "failed" :why (:why r)} "failed")
                {:state (state-payload st) :flowId fid :status "failed"})
            (do (flow-step! st fid i {:status "done" :out (:out r)})
                (recur))))))))

(defn flow-start!
  "Endpoint entry: validate the notebook NOW, then plan + create + run in a job."
  [st space goal]
  (cond
    (not (space? st space)) {:error (str "not a notebook: " space)}
    (str/blank? goal)       {:error "what should the flow do?"}
    :else
    {:job (start-job!
           (fn []
             (let [sp    (sub/object st space)
                   ctx   (str/join "\n" (map #(str "- " (:id %) " — " (:title %))
                                             (keep #(sub/object st (:ref %)) (nb/cells-of sp))))
                   steps (validate-plan (agent/plan-flow goal ctx))]
               (if (empty? steps)
                 {:error "the agent could not plan that — try rephrasing the goal"}
                 (run-flow! st (flow-create! st space goal steps))))))}))

(defn flow-gate!
  "The human answers the gate. Approve → gate step done, flow resumes (as a
   job when async? else synchronously — tests call the sync path via run-flow!).
   Reject → flow rejected, nothing more runs."
  [st fid approve]
  (let [o (sub/object st fid)]
    (if-not (= :flow (:kind o))
      {:error (str "not a flow: " fid)}
      (let [fl (:value o)
            i  (first (keep-indexed (fn [k s] (when (= "needs-you" (:status s)) k)) (:steps fl)))]
        (cond
          (nil? i) {:error "this flow isn't waiting on you"}
          approve  (do (flow-step! st fid i {:status "done"} "running")
                       {:job (start-job! #(run-flow! st fid)) :flowId fid})
          :else    (do (flow-step! st fid i {:status "rejected"} "rejected")
                       {:state (state-payload st) :flowId fid :status "rejected"}))))))
```

`mold-payload` gains a `:flow` branch — insert as the FIRST cond clause (before the `app:` branch), since flows have no viewers:

```clojure
      (= :flow (:kind o))
      {:id id :title (:title o) :kind "flow" :view nil :label "flow"
       :rendered (:value o) :alternatives []}
```

Routes:

```clojure
(= uri "/api/flow")     (let [{:keys [space goal]} (body-json req)] (json-resp (flow-start! st space goal)))
(= uri "/api/flow-gate")(let [{:keys [id approve]} (body-json req)] (json-resp (flow-gate! st id approve)))
```

NOTES: (1) The gate test stubs `start-job!` synchronous so approve's resume can't race the assertions — do NOT add locking to run-flow!; it's naturally idempotent (only picks `pending` steps). (2) The ask branch assumes `keep-note!` returns `{:openId note-id}` — READ its actual source first; if it returns a different shape, adapt the extraction (never guess) and say so in your report. (3) `flow-step!` re-reads the flow before patching, and `exec-step` receives the freshly re-read flow — required so `$N` sees earlier steps' `:out`.

- [ ] **Step 4: green** (63 tests expected). **Step 5: Commit** — `feat: flow interpreter — every transition an event, gate parks needs-you`

---

### Task 3: frontend — ⚑ Flow verb, live flow cell, gate buttons

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: API + button.**

```js
  flow: (space,goal) => POST('/api/flow',{space,goal}),
  flowGate: (id,approve) => POST('/api/flow-gate',{id,approve}),
```

In the notebook actions row, after the Deep-dive button:

```js
    '<button class="btn ghost sm" id="flowBtn">⚑ Flow</button>'+
```

Wire: `b.querySelector('#flowBtn').addEventListener('click',()=>startFlow(i));`

- [ ] **Step 2: startFlow** (mirror `startResearch`'s shape — placeholder, guarded await, then watch):

```js
function startFlow(i){
  const sp=STATE.spaces[i], b=document.getElementById('body'+i);
  b.innerHTML='<div class="actions"><input id="fq" placeholder="what should the agent take on? e.g. “map the EV battery supply chain and brief me” " '+
    'style="flex:1;min-width:280px;border:1px solid var(--line);border-radius:8px;padding:8px 10px;font:inherit;font-size:14px">'+
    '<button class="btn sm" id="goFlow">Plan &amp; run</button><button class="btn ghost sm" id="cancelFlow">Cancel</button></div>'+
    '<div style="font-size:12px;color:var(--muted);margin-top:8px">the agent plans a short flow (research → gate → compute → draft), runs it step by step, and STOPS to ask you at gates — every step reversible</div>';
  const go=async()=>{
    const g=b.querySelector('#fq').value.trim(); if(!g) return;
    b.innerHTML='<div class="sec-h">planning</div><div style="color:var(--muted);font-size:13px">the agent is planning the flow…</div>';
    let res; try{ res=await API.flow(sp.id, g); }catch(e){ showToast('flow: network error'); renderBody(i); return; }
    if(res.error){ showToast('flow: '+res.error); renderBody(i); return; }
    watchFlow(i, res.job);
  };
  b.querySelector('#goFlow').addEventListener('click',go);
  b.querySelector('#fq').addEventListener('keydown',e=>{ if(e.key==='Enter') go(); });
  b.querySelector('#cancelFlow').addEventListener('click',()=>renderBody(i));
  b.querySelector('#fq').focus();
}
// watch a running flow: refresh the notebook every 2.5s so the checklist is
// live, and toast when the job ends (done / needs-you / failed).
function watchFlow(i, job){
  const tick=async()=>{
    let s; try{ s=await API.job(job); }catch(e){ setTimeout(tick,4000); return; }
    const ae=document.activeElement, typing=ae&&(ae.tagName==='INPUT'||ae.tagName==='TEXTAREA');
    if(!TIME && !typing && mode==='focus' && focusIdx===i && !openId) renderBody(i);
    if(!s.done){ setTimeout(tick,2500); return; }
    const r=s.result||{};
    if(r.error){ showToast('flow: '+r.error); return; }
    if(TIME){ showToast('flow '+(r.status==='needs-you'?'needs you':'finished')+' — ↩ now to see it'); return; }
    if(r.state){ applyState(r.state); safeRebuild(); }
    showToast(r.status==='needs-you' ? 'The flow needs you — open the notebook to approve'
             : r.status==='failed' ? 'Flow stopped — a step failed (see the flow cell)'
             : 'Flow complete — everything it made is in the notebook (reversible)');
  };
  setTimeout(tick,1500);
}
```

- [ ] **Step 3: the flow mold.** In `renderMold`, add `case 'flow': return renderFlow(m.rendered, m.id);` and:

```js
const FLOWICON={pending:'○',running:'◐',done:'●','needs-you':'⚑',failed:'✕',rejected:'⊘'};
function renderFlow(f, fid){
  let h='<div class="flowgoal">'+esc(f.goal)+'</div><div class="flowsteps">';
  (f.steps||[]).forEach((s,k)=>{
    h+='<div class="flowstep s-'+esc(s.status)+'"><span class="fic">'+(FLOWICON[s.status]||'○')+'</span>'+
      '<span class="fverb">'+esc(s.verb)+'</span>'+
      (s.verb==='gate'?'<span class="fnote">'+esc((s.args&&s.args.question)||'')+'</span>'
                      :'<span class="fnote">'+esc(s.note||((s.args&&s.args.prompt)||''))+'</span>')+
      (s.out?' <span class="ghostchip" data-open="'+esc(s.out)+'">'+esc(s.out)+'</span>':'')+
      (s.why?' <span class="fwhy">'+esc(s.why)+'</span>':'')+'</div>';
  });
  h+='</div>';
  if(f.status==='needs-you')
    h+='<div class="actions" style="margin-top:8px"><button class="btn sm" data-gate="yes" data-flow="'+esc(fid)+'">Approve &amp; continue</button>'+
       '<button class="btn ghost sm" data-gate="no" data-flow="'+esc(fid)+'">Reject</button></div>';
  else h+='<div class="fstat">'+esc(f.status)+'</div>';
  return h;
}
```

Gate wiring — flows render inside notebook cells AND the detail molder, so wire via delegation once in each render path. In `renderNotebook` (with the other `b.querySelectorAll` wirings) and in `renderDetail` (after its existing listeners):

```js
  b.querySelectorAll('[data-gate]').forEach(el=>el.addEventListener('click',async ev=>{
    ev.stopPropagation();
    if(TIME){ showToast('time mode — ↩ now to decide'); return; }
    const ok=el.dataset.gate==='yes';
    let r; try{ r=await API.flowGate(el.dataset.flow, ok); }catch(e){ showToast('gate: network error'); return; }
    if(r.error){ showToast(r.error); return; }
    if(TIME){ showToast('finished — ↩ now to see it'); return; }
    if(ok){ showToast('Approved — the flow continues'); watchFlow(i, r.job); renderBody(i); }
    else  { applyState(r.state); renderBody(i); showToast('Rejected — the flow stopped (reversible)', undo); }
  }));
```

(`renderDetail` has no `i` in the same way — it receives `i` as its first arg; use it. `renderMold` needs `m.id` — check the call sites pass the mold payload `m` which has `:id`; adapt `renderMold(m)`'s signature use accordingly: the existing switch receives `m`, so `case 'flow': return renderFlow(m.rendered, m.id);` works as-is.)

CSS (near the chips rules):

```css
  .flowgoal{font-weight:600;font-size:13.5px;margin-bottom:8px}
  .flowsteps{display:flex;flex-direction:column;gap:4px}
  .flowstep{display:flex;gap:8px;align-items:baseline;font-size:12.5px}
  .flowstep .fic{width:16px;flex:none;color:var(--accent)}
  .flowstep.s-failed .fic,.flowstep.s-rejected .fic{color:var(--attn)}
  .flowstep.s-needs-you .fic{color:var(--attn)}
  .flowstep .fverb{font-family:var(--mono);font-size:11px;color:var(--muted);width:64px;flex:none}
  .flowstep .fnote{color:var(--ink-2)}
  .flowstep .fwhy{color:var(--attn);font-size:11.5px}
  .fstat{margin-top:6px;font-family:var(--mono);font-size:10.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--faint)}
```

The gate buttons sit in a `.actions` div → hidden in time mode by the existing `.world.timemode .actions` rule, with the JS guard as backup.

- [ ] **Step 4: verify headless.** `node --check`. Scratch server :7779 (temp LOCI_DATA, kill after; NEVER :7777): flows need the LLM for planning, so verify the sync surfaces instead — `POST /api/flow {"space":"nope","goal":"x"}` → not-a-notebook error; `{"space":"space:world","goal":""}` → "what should the flow do?"; `POST /api/flow-gate {"id":"nope","approve":true}` → not-a-flow error; served `/` contains `flowBtn`, `data-gate`, `renderFlow`. `clojure -M:test` green (63).

- [ ] **Step 5: Commit** — `feat: ⚑ Flow — plan, watch it work, approve at the gate`

---

### Task 4: docs + final verify

**Files:** `README.md`, `docs/walkthrough.md`

- [ ] **Step 1: README** endpoints:

```markdown
| `POST /api/flow` | the agent plans + runs a multi-step flow (background job) |
| `POST /api/flow-gate` | answer a flow's gate: approve resumes, reject stops |
```

- [ ] **Step 2: walkthrough**, new flow at the end:

```markdown
## 7 · A full agent flow, gated (agent + web)
On any notebook hit **⚑ Flow** and give it a goal. The agent plans a short
flow — research → gate → compute → draft — as a real object IN the notebook:
a live checklist, every step a reversible event. Watch it work; at a **gate**
it stops and asks (⚑ needs-you) — approve to continue, reject to stop.
When it's done, scrub ⏱ time backwards: you can watch the flow execute
step by step through the substrate's own history. No framework — the
substrate is the framework.
```

- [ ] **Step 3: full verify** — suite green, node --check, Task 3's curl pass rerun. **Step 4: Commit** — `docs: agent flows — walkthrough flow 7, README endpoints`

---

## Out of scope (deferred)
- Retries / continue-past-failure; parallel steps; re-planning mid-flow
- Flow templates; per-verb model selection (flash vs pro)
- Enforcing one running flow per notebook; canceling a running (non-gated) flow
- Undo-during-running-flow races (accepted prototype limit, same class as jobs)
