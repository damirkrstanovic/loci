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
RUN clojure -T:build uber

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
VOLUME /data
EXPOSE 7777
# No --enable-native-access flag here: the jar's manifest carries
# Enable-Native-Access: ALL-UNNAMED, which keeps startup free of the restricted-
# method warnings and keeps the jar working when a JDK blocks rather than warns.
CMD ["java", "-jar", "/app/loci.jar"]
