# Refuse to start on an unwritable data directory

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** an unwritable data directory produces one clear sentence naming the cause and the
fix, not a Datalevin stack trace.

## The defect, observed live 2026-08-08

Making the container non-root (`d439bc6`) broke every **existing** deployment. Docker applies
the image's `/data` ownership only to a **fresh** volume; a volume created earlier keeps its
root-owned contents. Observed on the user's `loci_loci-data`: files owned by uid 0, and
`substrate/` at mode `750` — no access for anyone else — against a container now running as
uid 10001.

What the user saw:

```
clojure.lang.ExceptionInfo: Fail to open database: #error {
 :cause "Permission denied"
 …
```

Nothing in that names the volume, the uid, or what to do. The one-line fix
(`docker run --rm -v <volume>:/data alpine chown -R 10001:10001 /data`) is undiscoverable
from it.

The Task-5 agent verified non-root against a **fresh** volume, which inherits the image's
ownership. The upgrade path was never exercised. That is the gap this closes.

---

### Task 1: Check, and say something useful

**Files:** modify `src/loci/substrate.clj` (beside `data-dir`) and `src/loci/server.clj`
(`-main`); tests; `README.md`

- [ ] **Step 1: Write the failing tests.**

Put the check beside `data-dir` in `loci.substrate` as a plain, testable predicate — not
inline in `-main`, which cannot be tested.

- an existing, writable directory passes
- a directory that exists but **cannot be written** fails, and the message names **the path**
  and **the effective uid**
- a directory that does **not** exist but whose parent is writable **passes** — loci creates
  its data dir on first run and must keep doing so
- a path that does not exist and whose parent is not writable fails
- a path that exists but is a **file** fails with a message saying so

**A trap you must handle rather than discover:** if the test suite runs as **root**, a
`chmod 000` directory is still writable and the negative tests silently pass while asserting
nothing. Detect that (`(System/getProperty "user.name")`, or probe by actually attempting a
write) and **skip with an explicit message** rather than passing vacuously. Report in your
summary which branch ran on this machine.

Do the writability check by **attempting a write and cleaning up**, not by `.canWrite` —
`java.io.File.canWrite` consults permission bits and is wrong under ACLs, and on some
filesystems it disagrees with reality. Say why in a comment.

- [ ] **Step 2: Run, watch fail. Step 3: Implement.**

In `-main`, before the store is touched (so the check runs before Datalevin opens anything),
fail with a message shaped like:

```
loci cannot write its data directory: /data
  running as uid 10001 (loci)

  If this is a Docker volume created before loci ran as a non-root user, its
  files are still owned by root. Fix it once:

    docker run --rm -v <your-volume>:/data alpine chown -R 10001:10001 /data

Refusing to start rather than failing later with a database error.
```

Exit non-zero. **Do not attempt to fix the ownership itself** — the same path may be a
bind-mounted host directory, and silently chowning a user's own files is a worse surprise
than refusing to start. Say that in a comment.

- [ ] **Step 4: Green. Step 5: Prove it bites** — remove the check from `-main`; the test that
  asserts an unwritable dir is refused must fail. Report the real output.

- [ ] **Step 6: Verify it in a real container.** Build the image, create a volume, `chown` it
  to root, run — and confirm the new message appears instead of the Datalevin trace. Then
  chown it back to 10001 and confirm it starts. **Use a throwaway volume**, never the user's
  `loci_loci-data` or `loci-data`, and never `data/`. Report the actual output of both runs.

- [ ] **Step 7: README.** Under the Docker section, a short "upgrading an existing volume"
  note: the container runs as uid 10001, a volume created before that is root-owned, and here
  is the one-line chown. Keep it to a few sentences in the existing voice.

- [ ] **Step 8: Commit** — `fix: refuse to start on an unwritable data directory, and say why`

## CRITICAL

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. Check and report both.
- **The user has a live `docker compose` stack running on port 7777 against volume
  `loci_loci-data`. Do not stop it, do not touch that volume, do not take port 7777.**
- Do not call a real model or embedder. Never print or commit a token. No `pkill -f`.
- Clean up any container, volume or image you create.
- `clojure -M:test` once. **Do not run the browser suite.**

## Out of scope

- Auto-chowning the data directory
- Dropping privileges from an entrypoint
- Anything else about the image
