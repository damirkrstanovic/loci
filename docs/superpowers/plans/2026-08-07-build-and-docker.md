# Build & Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `clojure -T:build uber` produces a jar that runs loci from any directory, and
`docker run` produces a container whose substrate survives a restart.

**Architecture:** `tools.build` uberjar with `loci.server` AOT-compiled as `:main`, then a
two-stage Dockerfile. No application logic changes except making the port configurable —
everything else the packaging needs is already in place.

**Tech Stack:** `tools.build`, Docker 29.7, JDK 26, Datalevin 1.0.0 (native LMDB via JavaCPP).

---

## What is already true (verified 2026-08-07, do not re-litigate)

- **The shell is served from the classpath**, not from disk: `server.clj:1212` does
  `(slurp (io/resource "public/index.html"))`, and `:paths` includes `resources`. It will
  work from a jar.
- **Both API keys prefer environment variables** and only fall back to files in the working
  directory: `DEEPSEEK_API_KEY` else `.deepseek-key` (`agent.clj:25`),
  `SEARCH_API_KEY`/`TAVILY_API_KEY` else `.tavily-key` (`tools.clj:123`). Docker is
  configured with env vars; no code change needed.
- **The data directory is already configurable**: `(or (System/getProperty "loci.data-dir")
  (System/getenv "LOCI_DATA") "data")` at `substrate.clj:133`. Docker mounts a volume there.
- **AOT is safe**: `content.clj:568` defines `store` as a `delay`, so requiring the namespace
  does not open LMDB. Compilation will not touch `data/`.
- **JDK 26 supports the `Enable-Native-Access` manifest attribute** (added in JDK 24), which
  is how a bare `java -jar` gets what `deps.edn` currently supplies per-alias as
  `--enable-native-access=ALL-UNNAMED`.

## What is missing

- No `build.clj`, no `:build` alias, no `pom.xml`, no Docker anything.
- `-main` (`server.clj:1264`) hardcodes `port 7777` and prints no data directory, so a
  misconfigured container is silent about where it put your substrate.

---

## CRITICAL: never write to `data/`

`data/` is the user's real substrate, `md5 e7a6eecdff7bb44ad6b959bb1a7fb7c6`. **Check it
before and after every build and every container run**, and report the value. Every
verification in this plan uses a throwaway directory. A packaging bug that points the
default `"data"` at the repo root while you test is exactly the accident this rule exists
for.

---

### Task 1: The uberjar

**Files:**
- Create: `build.clj`
- Modify: `deps.edn` (add `:build`), `src/loci/server.clj:1264-1270` (`-main`), `.gitignore` (`target/`)

- [ ] **Step 1: Make the port configurable and the data directory visible**

Replace `-main` in `src/loci/server.clj`:

```clojure
(defn -main [& _]
  ;; PORT because that is what every container runtime sets. The data directory is
  ;; printed because a packaged loci defaults to a RELATIVE "data" — launched from
  ;; the wrong place it silently starts an empty substrate instead of yours, and a
  ;; line of output is the difference between noticing and not.
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 7777)
        dir  (sub/data-dir)]
    (reset! server (http/run-server #'handler {:port port}))
    (println (str "loci shell on http://localhost:" port
                  "  (substrate: " dir ", " (count (sub/history (store))) " events)"))
    @(promise)))
```

Check `substrate.clj:133` for the real name of the data-dir function and use it; if it is
private, make it public rather than duplicating the `or` chain.

- [ ] **Step 2: Add the build alias**

In `deps.edn`, add to `:aliases`:

```clojure
  ;; the uberjar:  clojure -T:build uber   ->  target/loci-standalone.jar
  :build {:deps       {io.github.clojure/tools.build {:mvn/version "0.10.5"}}
          :ns-default build}
```

- [ ] **Step 3: Write `build.clj`**

```clojure
(ns build
  "Uberjar for loci.  clojure -T:build uber

   Two things here are load-bearing and easy to lose:
   the Enable-Native-Access manifest attribute, without which a bare `java -jar`
   cannot open Datalevin's LMDB (deps.edn supplies the equivalent flag per-alias,
   and a jar has no alias); and copying `resources`, which is where the entire
   shell lives — index.html is served with io/resource, not from disk."
  (:require [clojure.tools.build.api :as b]))

(def lib 'loci/loci)
(def version "0.1.0")
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
```

**Two things to verify rather than assume:**

- `-T:build` runs with `:paths ["."]` so `build.clj` at the repo root is found. Confirm; if
  not, add `:paths` to the alias.
- **Four platform-native Datalevin jars are on the classpath** (`dtlvnative-linux-arm64`,
  `-linux-x86_64`, `-macosx-arm64`, `-windows-x86_64`). Uberjar merging may report conflicts
  between them. If it does, do **not** silently drop files — read what actually collides and
  either keep all platform paths or exclude only the non-Linux ones with a comment saying the
  jar is then Linux-only. Report which you did.

- [ ] **Step 4: Build it and check `data/` was untouched**

```bash
md5sum data/substrate.edn
clojure -T:build uber
md5sum data/substrate.edn      # must be identical
ls -la target/loci-standalone.jar
```

- [ ] **Step 5: Verify the manifest**

```bash
unzip -p target/loci-standalone.jar META-INF/MANIFEST.MF
unzip -l target/loci-standalone.jar | grep -c "public/index.html"
```

Expected: `Main-Class: loci.server` and `Enable-Native-Access: ALL-UNNAMED` present, and the
shell present exactly once.

- [ ] **Step 6: Run it from a DIFFERENT directory — this is the whole point**

Packaging bugs of this class only appear once you leave the repo root.

```bash
RUN=$(mktemp -d) && cd "$RUN"
PORT=7901 LOCI_DATA="$RUN/data" java -jar /home/damirk/src/loci/target/loci-standalone.jar &
# wait for it, then:
curl -s localhost:7901/api/state | head -c 200
curl -s localhost:7901/ | grep -c "<title>loci</title>"
```

Assert: the process starts with **no** native-access warning or error, `/api/state` returns
JSON, and `/` returns the shell.

- [ ] **Step 7: Prove a write survives a restart**

Still in `$RUN`: create a notebook over HTTP with a route that does **not** call a model —
`/api/connect` is the model-free path (`/api/new-space` goes through the agent and would
make a live API call, since a DeepSeek key is present in both the repo and the environment).
Record `/api/state`'s `events` count, kill the JVM, start it again with the same `LOCI_DATA`,
and assert the count is unchanged and the notebook is still there. Report both counts.

Kill the server by PID looked up from the listening port. **Do not use `pkill -f`** — a
pattern that matches your own shell command kills the shell (exit 144, seen twice on this
repo).

- [ ] **Step 8: `.gitignore`**

Add `target/` if it is not already ignored.

- [ ] **Step 9: Commit**

```bash
git add build.clj deps.edn src/loci/server.clj .gitignore
git commit -m "build: tools.build uberjar — one file that runs loci from anywhere"
```

---

### Task 2: Docker

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Modify: `README.md`

- [ ] **Step 1: `.dockerignore`**

The build context must not carry the user's substrate or their secrets into an image:

```
data/
target/
node_modules/
.git/
.cpcache/
.clerk/
.deepseek-key
.deepseek-model
.tavily-key
test/browser/failures/
```

**This is a security boundary, not an optimisation.** `.deepseek-key` and `.tavily-key` are
gitignored secrets; a `COPY . .` without this file bakes them into a layer that survives
`docker push`.

- [ ] **Step 2: `Dockerfile`**

Two stages so the runtime image carries no build tooling:

```dockerfile
# Build the uberjar…
FROM clojure:temurin-26-tools-deps AS build
WORKDIR /src
# deps first, so a source edit does not re-resolve the whole dependency tree
COPY deps.edn build.clj ./
RUN clojure -P -T:build uber || true
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uber

# …and run it on a JRE alone.
FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /src/target/loci-standalone.jar /app/loci.jar
# the substrate lives on a volume: without one, every restart starts empty
ENV LOCI_DATA=/data
ENV PORT=7777
VOLUME /data
EXPOSE 7777
# Enable-Native-Access is in the jar manifest, so LMDB opens without a flag here
CMD ["java", "-jar", "/app/loci.jar"]
```

**Verify the base image tags actually exist** before committing — `clojure:temurin-26-tools-deps`
and `eclipse-temurin:26-jre` are plausible but unverified. If a tag is wrong, pick the closest
real one and say which in your report. Do not invent a tag that pulls nothing.

- [ ] **Step 3: Build the image, then check `data/` was untouched**

```bash
md5sum data/substrate.edn
docker build -t loci:dev .
md5sum data/substrate.edn      # identical
```

- [ ] **Step 4: Confirm no secret is in the image**

```bash
docker run --rm loci:dev sh -c 'ls -a /app; find / -name ".deepseek-key" -o -name ".tavily-key" 2>/dev/null | head'
```

Expected: nothing found. If anything is found, the `.dockerignore` is wrong — fix it and
rebuild. Report the actual output.

- [ ] **Step 5: Run it, and prove the volume persists**

```bash
docker volume create loci-test
docker run -d --name loci-t -p 7902:7777 -v loci-test:/data loci:dev
# wait, then:
curl -s localhost:7902/api/state | python3 -c 'import json,sys;print(json.load(sys.stdin)["events"])'
```

Then write something (the model-free `/api/connect` route), record the event count,
`docker restart loci-t`, wait, and assert the count is unchanged. Report both numbers.
Clean up: `docker rm -f loci-t && docker volume rm loci-test && docker rmi loci:dev`.

- [ ] **Step 6: README**

Add to the `## Run` section, in the README's existing voice:

````markdown
```bash
# a single jar that runs from anywhere
clojure -T:build uber
java -jar target/loci-standalone.jar          # PORT and LOCI_DATA are honoured

# or in a container, with the substrate on a volume
docker build -t loci .
docker run -p 7777:7777 -v loci-data:/data loci
```

The jar carries `Enable-Native-Access: ALL-UNNAMED` in its manifest, which is what lets
Datalevin open LMDB without the flag `deps.edn` supplies per-alias. `LOCI_DATA` defaults to
a **relative** `data/`, so a jar launched from elsewhere starts an empty substrate rather
than finding yours — the startup line prints which directory it opened, for exactly that
reason. In Docker it must be a mounted volume or every restart begins again from the seed.
Both API keys are read from `DEEPSEEK_API_KEY` and `SEARCH_API_KEY`/`TAVILY_API_KEY` before
falling back to files in the working directory; pass them with `-e` rather than baking them
into an image.
````

- [ ] **Step 7: Both suites once, and the substrate check**

```bash
clojure -M:test 2>&1 | tail -3
npm run test:browser 2>&1 | tail -8
md5sum data/substrate.edn
git status --short
```

- [ ] **Step 8: Commit**

```bash
git add Dockerfile .dockerignore README.md
git commit -m "build: a container whose substrate lives on a volume, and no key in the image"
```

## Out of scope

- Publishing the image anywhere, or a registry/CI pipeline
- A native-image / GraalVM build
- Multi-architecture images
- Health checks, non-root user hardening, resource limits — worth doing, separately
