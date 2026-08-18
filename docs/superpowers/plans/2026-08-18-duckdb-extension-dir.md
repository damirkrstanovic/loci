# DuckDB extension directory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docker compose up --build` boots. Today it dies before the HTTP listener with
`IO Error: Failed to create directory "/.duckdb": Permission denied`.

**Architecture:** loci learns one config value naming DuckDB's extension directory, and the image
installs the extension at build time and points loci at it. On a dev machine the value is unset
and DuckDB's default (`~/.duckdb`) applies exactly as it does today.

**Tech Stack:** DuckDB 1.5.5 via `org.duckdb/duckdb_jdbc`; `loci.config`; the existing Dockerfile.

---

## The diagnosis, measured 2026-08-18

DuckDB resolves its extension directory from `$HOME`. The runtime image **never sets `HOME`** —
`USER` does not set it, and `debian:bookworm-slim` does not either — so `getenv("HOME")` is null,
DuckDB composes `/.duckdb`, and that is `EACCES` for any non-root uid.

**`ENV HOME=/home/loci` does not fix it**, which is why the obvious patch is not the one below.
`compose.yaml` sets `user: "${LOCI_UID:-10001}:${LOCI_GID:-10001}"`, so loci commonly runs as the
*host* user's uid — an id with no `/etc/passwd` entry in the container at all, and no ownership of
`/home/loci`. Any fix that depends on `HOME` being both set and writable is a fix for one of the
two supported ways to run the container.

**The extension is 36 MB.** So it must not be downloaded into `<data-dir>` on first boot: that is
the directory the user backs up, and it would land there once per data directory.

Measured facts the implementation rests on:

- Passing `extension_directory` in the JDBC `Properties` works: `current_setting` reports it,
  `INSTALL` writes there, and `ATTACH`+`USE` then succeed.
- The installed layout is version- and platform-specific:
  `<dir>/v1.5.5/linux_amd64/ducklake.duckdb_extension`. Installing at build time with the same
  jar is what guarantees the path matches at runtime.
- `LOAD` only reads, so a read-only extension directory is fine. Only `INSTALL` writes.

This is what `ensure-extension!`'s own docstring already asked for: *"a packaged build should
hoist it to image-build time so a user's first boot does not, and until it does, an air-gapped
first run cannot work no matter what this function says."*

---

## Task 1: loci honours an explicit extension directory

**Files:**
- Modify: `src/loci/lake.clj` (`open-lake!`, `ensure-extension!`)
- Test: `test/loci/lake_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest an-explicit-extension-directory-is-honoured
  ;; The container has no usable HOME — `USER` does not set it and compose runs
  ;; loci as the host uid, which has no passwd entry — so DuckDB composes
  ;; "/.duckdb" and dies with EACCES before the listener starts. loci must be
  ;; able to say where the extension lives without depending on HOME.
  (let [ext (str (System/getProperty "java.io.tmpdir") "/loci-ext-" (System/nanoTime))]
    (.mkdirs (clojure.java.io/file ext))
    (try
      (with-redefs [loci.config/env {"LOCI_DUCKDB_EXTENSIONS" ext}]
        (with-lake [lk]
          (is (= ext (:d (first (lake/q lk "SELECT current_setting('extension_directory') AS d"))))
              "DuckDB was told where to look")
          (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db"))
              "and the lake still opens")))
      (finally (rm-rf ext)))))

(deftest no-explicit-directory-changes-nothing
  (with-redefs [loci.config/env (constantly nil)]
    (with-lake [lk]
      (is (= [{:db "lake"}] (lake/q lk "SELECT current_database() AS db"))
          "an unset value must leave DuckDB's own default alone"))))
```

`loci.config/env`'s real arity is `[k]` — check it and redef accordingly rather than copying the
map literal above verbatim.

- [ ] **Step 2: Run to verify they fail**

Expected: `current_setting` reports `~/.duckdb`, not the temp dir.

- [ ] **Step 3: Implement**

In `open-lake!`, build the connection properties from the config value:

```clojure
(let [props (java.util.Properties.)
      _     (when-let [ext (config/env "LOCI_DUCKDB_EXTENSIONS")]
              (.setProperty props "extension_directory" ext))
      conn  (.connect (org.duckdb.DuckDBDriver.) "jdbc:duckdb:" props)]
  …)
```

Add `[loci.config :as config]` to the ns. Document on `open-lake!` why this exists and why
`HOME` is not the answer — the two-uid argument above is the part a reader cannot reconstruct.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Say something useful when the default is unwritable**

`ensure-extension!` currently lets DuckDB's raw error out. Catch a failure of the
`INSTALL`/`LOAD` sequence and rethrow with a message in `data-dir-problem`'s register: name the
path DuckDB tried, name `LOCI_DUCKDB_EXTENSIONS`, and say that a container should have it set at
build time. Keep the original exception as the cause.

Test it by pointing `LOCI_DUCKDB_EXTENSIONS` at an unwritable path — create a directory and
`(.setWritable f false)` — and asserting the message names the variable. **Skip the test if it
runs as root**, where the write would succeed; check `(= 0 (:uid (sub/effective-user)))` and say
so rather than letting it pass vacuously.

- [ ] **Step 6: Sabotage**

Drop the `when-let` so the property is never set. `an-explicit-extension-directory-is-honoured`
must FAIL, reporting DuckDB's default path. Restore.

- [ ] **Step 7: Commit**

```bash
git add src/loci/lake.clj test/loci/lake_test.clj
git commit -m "fix: loci can say where DuckDB's extensions live"
```

---

## Task 2: the image installs the extension at build time

**Files:**
- Modify: `Dockerfile`
- Modify: `README.md` (the Docker section)

- [ ] **Step 1: Install in the build stage**

After the uberjar step, while the full `clojure` toolchain and network are still available, run a
snippet that opens an in-memory DuckDB with `extension_directory` set to `/duckdb-ext` and issues
`INSTALL ducklake`. Use the same dependency set the jar was built from, so the extension version
matches the driver that will load it.

Verify in that same layer that the file landed —
`test -f /duckdb-ext/v*/linux_amd64/ducklake.duckdb_extension` — and fail the build if not. A
silently empty directory would move the failure to first boot, which is exactly where it is now.

- [ ] **Step 2: Carry it into the runtime image**

```dockerfile
COPY --from=build /duckdb-ext /opt/duckdb-extensions
ENV LOCI_DUCKDB_EXTENSIONS=/opt/duckdb-extensions
```

It stays root-owned and read-only to `loci`: `LOAD` only reads. Do **not** chown it — a writable
extension directory would let a version mismatch silently re-download at boot instead of failing
loudly.

Record the image-size delta in a comment. The Dockerfile accounts for every megabyte it spends
(jlink module roots, `:slim true`, `java.desktop` left out at +15 MB), and 36 MB unexplained
would be out of keeping.

- [ ] **Step 3: Build and boot it**

```bash
docker compose up --build
```

Expected: the container reaches `loci shell on http://…` and the healthcheck goes healthy.
Confirm `GET /api/state` answers 200, and that a table renders — `GET /api/mold?id=tbl:orders`
returns 1,000 rows — because the whole point is that the lake opens.

**Also boot it with the network off** (`docker compose run --rm --network none loci`) and confirm
it still starts. That is the property this task buys and the one thing the old code could not do.

- [ ] **Step 4: README**

The Docker section tells the reader to `chown -R` because `/data/substrate` would otherwise stay
root-owned, and that advice now covers `/data/lake` too. Add that the extension ships in the
image, so first boot needs no network — and that a non-Docker deployment with no `HOME`
(a systemd unit, say) should set `LOCI_DUCKDB_EXTENSIONS` for the same reason.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile README.md
git commit -m "fix: the image ships DuckDB's ducklake extension"
```

---

## What this plan does not answer

- **A non-Docker install with no `HOME`.** Task 1 makes it fixable by setting one variable and
  says so in the README, but nothing detects it for you beyond the improved error message.
- **A DuckDB upgrade.** The installed path is version-specific, so bumping `duckdb_jdbc` without
  rebuilding the image gives a `LOAD` failure at boot. That is loud rather than silent, and the
  build-time verification in Task 2 Step 1 is what keeps the two in step.
