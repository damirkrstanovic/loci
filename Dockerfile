# Build the uberjar, and the runtime that will run it…
FROM clojure:temurin-26-tools-deps AS build
WORKDIR /src
# objcopy, for jlink --strip-debug. On JDK 26 that plugin shells out to objcopy
# to strip the *native* libraries as well as the class files, and fails the whole
# link with `Cannot run program "objcopy"` when it is missing — the base image has
# no binutils. It stays in this stage, which is discarded.
RUN apt-get update \
 && apt-get install -y --no-install-recommends binutils \
 && rm -rf /var/lib/apt/lists/*
# The runtime is linked before the sources are copied, so a source edit does not
# re-link it. It depends on the JDK and on the module list below, and on nothing
# in src/.
#
# The module list was found empirically, not guessed, in two passes:
#
#   1. `jdeps --multi-release 26 --ignore-missing-deps --print-module-deps` over
#      the *exploded* slim jar. Over the jar itself jdeps answers with three
#      modules and is wrong: bcprov ships META-INF/versions/9/module-info.class,
#      which tools.build carries into the uberjar, so jdeps reads the whole thing
#      as the named module `org.bouncycastle.provider` and reports that module's
#      declared requires instead of analysing the classes. Unzipped, it finds
#      java.base, java.compiler, java.desktop, java.net.http, java.rmi, java.sql,
#      jdk.incubator.vector, jdk.management, jdk.sctp, jdk.security.auth,
#      jdk.unsupported.
#   2. `-Xlog:class+load=info` on a full JDK, while /api/state, /api/events,
#      /api/leap, /api/memory, /api/mold, /api/object, /api/import-csv, /api/undo
#      and a /api/connect write were exercised. That is the pass that matters,
#      because Clojure loads reflectively and jdeps cannot see it. It confirmed
#      java.management (39 classes), jdk.management (15), jdk.net (7),
#      java.net.http (7), jdk.unsupported, java.sql, jdk.security.auth — and it
#      showed that most of what jdeps found is never loaded.
#
# What each root is here for, since neither pass explains itself:
#
#   java.management     39 classes on every start; jdeps points at ManagementFactory
#                       in sofa-jraft's `Utils`, inside Datalevin
#   jdk.management      com.sun.management GC notifications, in `datalevin.spill`
#   jdk.net             ExtendedSocketOptions/LinuxSocketOptions, which the NIO
#                       channel code reaches for when the listener's socket opens
#   java.net.http       HttpClient/HttpRequest/HttpResponse, loaded when
#                       Datalevin's namespaces do (`datalevin.embedding`). loci's
#                       own model and embedder calls go through http-kit instead
#   java.sql            java.sql.Timestamp/Date — clojure.instant, data.json
#   jdk.unsupported     sun.misc.Unsafe
#   jdk.security.auth   com.sun.security.auth.module.UnixSystem, which
#                       `loci.substrate/effective-user` uses to name the uid in the
#                       "chown your volume" message. It degrades to a nil uid
#                       rather than throwing, so this one is a better message, not
#                       a working app.
#   jdk.zipfs           what a `jar:` FileSystem needs, for 92 KB — 51196 KB of
#                       runtime without it against 51288 KB with.
#
# **TLS is the one both passes are blind to**, and it is worth saying how it was
# checked rather than assumed. Neither pass can see it: a JCA provider is loaded
# by name, so jdeps cannot find it, and nothing in the offline exercise above
# speaks https, so the class+load trace never reaches it. Every https:// call loci
# makes — the model, the embedder, the reranker, web search — would fail at the
# handshake while /api/state stayed green. The obvious fix is to add
# `jdk.crypto.ec` for SunEC. **On JDK 26 that is wrong and does nothing**: SunEC
# now lives in java.base (`Class.forName("sun.security.ec.SunEC").getModule()`
# answers java.base), and `java --describe-module jdk.crypto.ec` shows a module
# that requires java.base and declares nothing at all — 28 KB of descriptor. So
# there is no root to add, and the check is empirical instead: a runtime of
# java.base alone already offers ECDH, X25519, X448 and XDH for KeyAgreement, and
# the image below completes a real TLS 1.3 handshake through loci's own http-kit
# client. Re-run that on the next JDK before trusting this paragraph.
#
# java.logging, java.naming, java.xml, java.transaction.xa, java.security.jgss and
# java.security.sasl arrive transitively and are not worth naming as roots.
#
# Deliberately left out, each measured:
#   java.desktop        +15 MB. jdeps wants it for clojure.core/bean and
#                       clojure.inspector, neither of which loci calls, and the two
#                       classes a full JRE does load from it are the annotations
#                       java.beans.ConstructorProperties and .Transient — an
#                       annotation whose class is absent is silently skipped.
#   jdk.localedata      +10 MB. Without it only the root and en locales resolve.
#                       Nothing in src/ formats against a locale — no
#                       SimpleDateFormat and no DateTimeFormatter anywhere — so
#                       what is left is what loci already used.
#   jdk.charsets        +1 MB. UTF-8, ISO-8859-1, US-ASCII and UTF-16 are in
#                       java.base, and JDK 18+ defaults file.encoding to UTF-8.
#   jdk.incubator.vector  JavaFastPFOR's SIMD codecs, which Datalevin does not
#                       select — never loaded in the trace. An incubator module is
#                       not resolved without --add-modules anyway, so shipping it
#                       would achieve nothing.
#   jdk.sctp, java.rmi, java.compiler  netty's SCTP channels, a Hessian RMI stub
#                       and errorprone's annotations: dead code jdeps can see and
#                       the JVM never reaches.
#
# Measured: 258 MB of JDK → 51 MB of runtime.
RUN jlink \
      --add-modules java.base,java.management,java.net.http,java.sql,jdk.management,jdk.net,jdk.security.auth,jdk.unsupported,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-9 \
      --output /javaruntime
# deps next, so a source edit does not re-resolve the whole dependency tree.
# Both lines are needed: -P with -T only resolves the *tool's* deps, because
# build.clj builds the project basis at runtime with b/create-basis, which -P
# never runs. Measured: -P -T:build alone leaves ~/.m2 at 15 MB with no
# datalevin; adding -P -M:serve takes it to 80 MB.
COPY deps.edn build.clj ./
RUN clojure -P -M:serve && clojure -P -T:build uber
COPY src ./src
COPY resources ./resources
# :slim true drops the native libraries for every platform but linux-x86_64 —
# Datalevin also ships linux-arm64/macosx-arm64/windows-x86_64, zstd-jni ships
# 18 architectures and JNA 24, and this image can load none of them. Measured
# 2026-08-07: 51 files, 80.7 MB → 63.9 MB of jar, 445 → 428 MB of image. The jar it
# produces runs on linux-x86_64 only; `clojure -T:build uber` without the flag
# still builds the portable one. See `foreign-natives` in build.clj.
RUN clojure -T:build uber :slim true

# …and run it on that runtime, on the same distribution the runtime was linked on.
# Debian 12 is not incidental: clojure:temurin-26-tools-deps *is* bookworm, and a
# jlinked runtime carries the JDK's own .so files, which are linked against the
# glibc of the image that produced them (2.36 here). A newer or older slim base
# would be betting on glibc compatibility for nothing — this one is exact.
# Nothing is installed on top: the runtime brings its own cacerts, so TLS needs no
# ca-certificates package, and the healthcheck below needs only bash, grep and
# head, which a slim Debian already has.
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=build /javaruntime /opt/java
COPY --from=build /src/target/loci-standalone.jar /app/loci.jar
ENV PATH="/opt/java/bin:${PATH}"
# The substrate lives on a volume. Declaring it means an unmounted `docker run`
# still gets an anonymous volume — measured: that survives `docker restart`, but
# it is tied to this container, so replacing the container (or --rm) starts from
# the seed again. Mount a named volume to outlive the container.
ENV LOCI_DATA=/data
ENV PORT=7777
# Unprivileged. The chown must precede VOLUME: once /data is a declared volume,
# later RUN changes to it are discarded, and Docker seeds a fresh volume from
# the image's directory — ownership included. Without this the container starts
# but cannot commit an event, which is worse than running as root.
#
# The uid is pinned rather than left to useradd. A volume's files carry the
# numeric owner, so if a future base image shifted the auto-assigned id, an
# existing loci-data would become unwritable by the very user meant to own it.
# The home directory exists because javacpp (Datalevin's native loader) and JNA
# unpack into ~/.javacpp before falling back to /tmp.
RUN groupadd --gid 10001 loci \
 && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/loci \
      --shell /usr/sbin/nologin loci \
 && mkdir -p /data && chown loci:loci /data
VOLUME /data
EXPOSE 7777
# /api/state is a plain read of the substrate, so a 200 means the JVM is up
# *and* LMDB opened. Done with bash's /dev/tcp because this image has neither
# curl nor wget, and installing one to check a port would undo the slimming
# above; the cost is that it tests the status line only, not the body.
# Cold start measured between ~3 s (warm host page cache) and ~13.6 s; 45 s of
# start-period is deliberate margin. start-interval keeps the container from
# sitting "starting" for the whole period once it is actually up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --start-interval=2s --retries=3 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /api/state HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q '^HTTP/1\\.[01] 200'"]
USER loci
# No --enable-native-access flag here: the jar's manifest carries
# Enable-Native-Access: ALL-UNNAMED, which keeps startup free of the restricted-
# method warnings and keeps the jar working when a JDK blocks rather than warns.
CMD ["java", "-jar", "/app/loci.jar"]
