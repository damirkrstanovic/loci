# Build the uberjar…
FROM clojure:temurin-26-tools-deps AS build
WORKDIR /src
# deps first, so a source edit does not re-resolve the whole dependency tree.
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

# …and run it on a JRE alone.
FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /src/target/loci-standalone.jar /app/loci.jar
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
