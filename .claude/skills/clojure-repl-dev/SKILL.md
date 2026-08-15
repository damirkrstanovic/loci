---
name: clojure-repl-dev
description: Use for ANY Clojure work in this repo — reading, writing, testing, measuring, debugging. Establishes the REPL as the execution surface and forbids spawning a JVM per operation.
---

# REPL-driven development in loci

Adapted from [iwillig/clojure-system-prompt](https://github.com/iwillig/clojure-system-prompt)
and [bhauman/clojure-mcp](https://github.com/bhauman/clojure-mcp), rewritten for this
project's actual tools and conventions.

## The rule

**A live nREPL is the execution surface. Do not start a JVM to run Clojure.**

`clojure -M -e "…"` and `clojure -M:test` each cost **~25 seconds** before a single form
evaluates — measured 2026-08-15: 0.78 s of JVM, 14.1 s loading Datalevin and friends,
12 s loading loci's own source. The same work in a warm REPL costs **milliseconds**. A
200-`remember` concurrency exercise measured 0.07 s in the REPL against ~24 s as a
one-namespace test invocation.

On 2026-08-15 this rule did not exist and the cost was concrete: dozens of cold JVMs to
call functions that were already loaded in an nREPL that had been idle for 209 minutes,
a load average of 11 on 6 cores, and one contaminated measurement.

## The loop

1. **Check the REPL is alive** — `mcp__clojure-mcp__clojure_eval` with `(+ 1 1)`.
   If it is not, `mcp__clojure-mcp__list_nrepl_ports`, then ask before starting one.
2. **Load what you need** — `(require '[loci.x :as x] :reload)`. Always `:reload` after
   editing a file, or you are testing the version you already had.
3. **Explore before writing** — `(clojure.repl/doc f)`, `(clojure.repl/source f)`,
   `(clojure.repl/apropos "frag")`. Do not guess at a signature that is one eval away.
4. **Try the change as an expression** before it becomes an edit.
5. **Edit the file** with Edit/Write, or `mcp__clojure-mcp__clojure_edit` when the
   built-in editor fights the parens.
6. **`:reload` and re-run.** An edit you have not reloaded and re-run is unverified.
7. **Run the relevant test namespace in the REPL**, not the whole suite in a new JVM.

## Two reload traps that will bite you

**`:reload` does not re-evaluate `defonce`.** `loci.dlv/store`, `loci.content/store` and
`loci.memory/memory` are all `defonce` delays. Editing their definition and reloading
leaves the *old* value in place, and you will debug a change that is not running. Clear
them by hand:

```clojure
(alter-var-root #'loci.memory/memory
                (constantly (delay (loci.memory/datalevin-memory @loci.dlv/store))))
```

**Reload in dependency order when protocols are involved.** `loci.factlog` defines
`FactLog`; `loci.dlv` and `loci.memory` implement it. Reloading `factlog` *after* them
mints a new protocol object and orphans every existing implementation — the symptom is
`No implementation of method: :append! of protocol: #'loci.factlog/FactLog found for
class: loci.dlv.DbiLog`, which looks like a code bug and is not. Always:

```clojure
(require 'loci.factlog :reload)   ; protocol first
(require 'loci.dlv :reload)       ; then its implementors
(require 'loci.memory :reload)
```

`(require 'loci.memory :reload-all)` also works and is safer when unsure, at the cost of
reloading the world.

## Running tests without leaving the REPL

```clojure
(require '[clojure.test :as t] '[loci.factlog-test] :reload)
(t/run-tests 'loci.factlog-test)          ; one namespace
(t/test-vars [#'loci.factlog-test/some-test])  ; one test
```

`clojure -M:test` is for **one** thing: a final full-suite run before a commit. Not for
iteration, and never for checking whether a single sabotage bit.

## Sabotage (proving a test bites) belongs in the REPL

Reintroducing a bug to confirm a test fails is worth doing for load-bearing tests. Doing
it by **editing source files and restarting a JVM** is what contaminated a measurement on
2026-08-15, when a reviewer's `[:put "events" 999 …]` leaked into a concurrent agent's run
in the same working tree.

In the REPL it is a redefinition, and there is nothing to restore:

```clojure
(with-redefs [loci.memory/append-line! (fn [_ _] :ok)]   ; drop writes
  (t/run-tests 'loci.memory-test))
```

**Protocol methods cannot be `with-redefs`'d** — that is a real limitation of this
codebase's shape (`FactLog`, `Recall`, `Store` are all protocols). To sabotage one,
re-`extend-type` the record at the REPL and re-extend it back afterwards, or reach for
the plain var underneath.

Do it **once per property**, not once per test, and only where the test is carrying real
weight.

## Two JVMs

Exactly one question needs two OS processes: whether two processes writing to one LMDB
environment corrupt each other. `locking` is a JVM monitor and means nothing across
processes, so threads cannot test it. That is one measurement, not a way of working.

Everything else — imports, migrations, dry runs, timings, verification — goes through the
REPL, calling the namespace's own public facade (`loci.conversation/import-conversation!`,
`loci.corpus/import-corpus!`, `loci.factmigrate/migrate-facts!`). If a task feels like it
needs a throwaway script and a fresh JVM, it almost certainly wants three evals instead.

## This project's conventions, which override the upstream skill

- **`!` on side-effecting names is correct here and is standard Clojure.** `commit!`,
  `undo!`, `remember`, `import-corpus!`, `append-line!`. The upstream prompt forbids it;
  it is wrong.
- Docstrings explain **why**, not what — see `loci/corpus.clj` for the register. A
  docstring that restates the code is noise; one that names the failure it prevents is
  the point.
- Threading macros over nesting, `cond->` for conditional assembly: already the house
  style, keep it.
- `(set! *warn-on-reflection* true)` is not used project-wide; do not add it namespace by
  namespace as a drive-by.

## Do not report success before verification

Reload the namespace, run the changed function, run the relevant tests, and read the
output. "Should work" is not a result. If the REPL says otherwise, the REPL is right.
