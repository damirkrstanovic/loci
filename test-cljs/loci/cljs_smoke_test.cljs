(ns loci.cljs-smoke-test
  "Proves the ClojureScript test loop exists at all. If this file stops running,
   every other cljs test in this plan is silently not running either.

   That is not hypothetical. `:node-test` builds its runner out of whatever
   `:ns-regexp` matched, so a pattern that matches nothing compiles, runs, prints
   `Ran 0 tests containing 0 assertions` and **exits 0** — while a real failure
   exits 1. Nothing downstream can tell those two apart, and no other test can
   report its own absence.

   `cljs-smoke-test` and not `smoke-test`: `test/loci/smoke_test.clj` is the JVM
   suite's canary and has been since long before this, and two namespaces of the
   same name on two classpaths make `Testing loci.smoke-test` ambiguous in the
   one place it is read — a failure."
  (:require [cljs.test :refer [deftest is]]
            [loci.grid :as grid]))

(deftest the-cljs-test-runner-runs
  (is (fn? grid/mount) "the module under test loads and exports its seam"))
