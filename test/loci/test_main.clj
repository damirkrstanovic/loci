(ns loci.test-main
  "`clojure -M:test`, with the fixture directories reaped on the way out.

   **Why this exists.** 23 test namespaces build a scratch directory by hand —
   `(str (System/getProperty \"java.io.tmpdir\") \"/loci-…-\" (System/nanoTime))` —
   at 40 call sites, and most never remove it. Measured 2026-08-25: `.tmp` held
   **18,718 directories and 583 MB**, of which 17,444 were older than an hour.
   `deps.edn` already records the precedent this repeats: `/tmp` reached 100%
   mid-session once, holding 9,918 fixture directories, and stopped a build. It
   is why `java.io.tmpdir` was pointed at `.tmp` on disk rather than at RAM.

   **One shutdown hook rather than 40 edits.** Rewriting every call site to use a
   registering helper would touch 23 files to fix a hygiene problem, and each
   edit is a chance to break a test that currently passes. A hook needs no
   cooperation from any of them: whatever they created, this removes.

   **It reaps only what THIS JVM created.** The cutoff is the instant this
   namespace loads, so a concurrent `clojure -M:test` — or the browser harness's
   own server JVMs, which clean up after themselves and whose directories are
   named `loci-browser-*` anyway — keeps its directories. Without that cutoff
   two suites running at once would delete each other's fixtures mid-test, which
   is a far worse failure than the disk it saves.

   **What it cannot do.** A shutdown hook does not run on `SIGKILL`, so a `kill
   -9` still leaks. That is the residue to sweep by hand, and it is a much
   smaller number than what accumulated before this existed."
  (:require [clojure.java.io :as io])
  (:gen-class))

(def ^:private started
  "The instant this namespace loaded. Anything older belongs to somebody else."
  (System/currentTimeMillis))

(def ^:private prefixes
  "The scratch-name prefixes this suite creates, **measured rather than guessed**:
   run `clojure -M:test`, then list what appeared under `java.io.tmpdir` since.
   As of 2026-08-26 a full run leaves 60 entries across these six families.

   An explicit list and not *everything newer than* `started`, because
   `java.io.tmpdir` is shared: a concurrent browser suite writes Playwright
   profile and artifact directories there, and DuckDB extracts a 58 MB native
   library that a long-lived REPL stays mapped to. Deleting those mid-run would
   trade a disk problem for a much worse one.

   **A new test that invents a new prefix leaks until it is added here.** That is
   the cost of the list; the alternative costs more. `find .tmp -maxdepth 1
   -newermt @<start>` after a run is how you find one."
  ["loci-" "src-" "api-src-" "empty-" "nomode-" "chunks-src-"])

(defn- rm-rf [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf (.listFiles f)))
  (.delete f))

(defn- reap!
  "Remove every entry under `java.io.tmpdir` matching one of `prefixes` that
   appeared after this JVM started. Returns the count, and swallows everything:
   a scratch directory that will not delete must never be the reason a green
   suite reports failure."
  []
  (let [tmp (io/file (System/getProperty "java.io.tmpdir"))]
    (count
     (for [^java.io.File f (or (.listFiles tmp) [])
           :when (and (some #(.startsWith (.getName f) ^String %) prefixes)
                      (>= (.lastModified f) started))
           :when (try (rm-rf f) (catch Throwable _ false))]
       f))))

(defn -main [& args]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(reap!)))
  (apply (requiring-resolve 'cognitect.test-runner/-main) args))
