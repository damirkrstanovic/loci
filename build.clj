(ns build
  "Uberjar for loci.  clojure -T:build uber

   Three things here are load-bearing and easy to lose.

   `resources` must be copied: the entire shell lives there, and index.html is
   served with io/resource, not read from disk.

   The Enable-Native-Access manifest attribute is what deps.edn supplies
   per-alias as --enable-native-access=ALL-UNNAMED, and a bare `java -jar` has
   no alias. Measured on JDK 26: without it Datalevin still opens LMDB, but
   every start prints four WARNING lines about a restricted method — and the
   last of them says restricted methods will be *blocked* in a future release.
   So it buys a clean startup now and a jar that keeps working later. Note the
   JDK honours the attribute only for `java -jar`; `java -cp <jar> loci.server`
   ignores it and warns.

   loci.server needs (:gen-class) for :main to mean anything — see the comment
   there."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/loci-standalone.jar")

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis      (basis)
                  :ns-compile '[loci.server]
                  :class-dir  class-dir
                  :java-opts  ["--enable-native-access=ALL-UNNAMED"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (basis)
           :main      'loci.server
           :manifest  {"Enable-Native-Access" "ALL-UNNAMED"}})
  (println "built" uber-file))
