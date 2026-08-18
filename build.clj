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
   there.

   `:slim true` drops the native libraries for every platform but linux-x86_64.
   It is off by default because the resulting jar runs on linux-x86_64 and
   nowhere else — see `foreign-natives` below."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/loci-standalone.jar")

(def ^:private foreign-natives
  "Paths to drop when building for linux-x86_64 alone.

   tools.build matches these with `re-matches`, so each must describe a whole
   path. Four dependencies ship one native payload per platform, and each
   spells linux-x86_64 differently — copying a pattern between them is how you
   get a slim jar that quietly kept all four platforms, or dropped all four:

   - Datalevin's LMDB binding, under `datalevin/dtlvnative/<platform>/` — four
     platforms. The sibling `DTLV*.class` files are not under a platform
     directory and are kept; the `[^/]+/` segment is what distinguishes them.
   - zstd-jni (reached through nippy), as `<os>/<arch>/libzstd-jni-*` — 18.
   - JNA's `jnidispatch`, under `com/sun/jna/<platform>/` — 24. Restricted to
     the native file extensions on purpose: `com/sun/jna/win32/`, `internal/`
     and `ptr/` hold ordinary classes that must survive.
   - DuckDB's JNI binding, as `libduckdb_java.so_<os>_<arch>` at the jar root —
     four platforms, one file each, and no directory to key on. `linux_amd64`
     is the one to keep; the sibling `org/duckdb/DuckDBNative.class` loads it
     and must survive, which the `libduckdb_java` prefix already ensures.

   Measured on the 2026-08-07 dependency set: 51 files, 17.0 MB *compressed*,
   taking the jar from 80.7 MB to 63.9 MB. Compressed is the number that
   matters — it is what the jar and the image layer carry. Those same files are
   44.9 MB uncompressed, which is the larger figure `unzip -l` reports and it
   is not the saving.

   DuckDB arrived 2026-08-16 and dwarfs all of that: its four natives are
   81.3 MB compressed on their own, of which the three foreign ones are
   61.4 MB. Slim is no longer a nicety on this dependency set.

   The trade is that the jar stops being portable. Nothing checks the platform
   at runtime: on arm64 or macOS a slim jar fails when Datalevin first tries to
   load libdtlv, not at startup. The manifest records which kind it is."
  ["datalevin/dtlvnative/(?!linux-x86_64/)[^/]+/.*"
   "(?!linux/amd64/)[^/]+/[^/]+/libzstd-jni-.*"
   "com/sun/jna/(?!linux-x86-64/)[^/]+/.*\\.(so|dll|dylib|jnilib)"
   "libduckdb_java\\.so_(?!linux_amd64).*"])

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_] (b/delete {:path "target"}))

(defn uber
  "clojure -T:build uber              — portable jar, every platform (default)
   clojure -T:build uber :slim true   — linux-x86_64 only, ~17 MB smaller

   The Dockerfile passes :slim true, because that image is linux-x86_64."
  [{:keys [slim]}]
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
           :exclude   (when slim foreign-natives)
           ;; So `unzip -p <jar> META-INF/MANIFEST.MF` answers "will this run on
           ;; my machine?" — the two jars are otherwise indistinguishable, and
           ;; they share a filename.
           :manifest  {"Enable-Native-Access" "ALL-UNNAMED"
                       "Loci-Platform"        (if slim "linux-x86_64" "any")}})
  (println "built" uber-file (if slim "(slim: linux-x86_64 only)" "(portable)")))
