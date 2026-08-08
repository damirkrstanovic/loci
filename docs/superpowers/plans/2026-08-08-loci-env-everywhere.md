# One config file, Docker or not

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `loci.env` configures `clojure -M:serve` exactly as it already configures
`docker compose up`.

## Today

`loci.env` is a Docker env-file: compose turns each line into a real environment variable, and
loci reads it through `System/getenv`. Run `clojure -M:serve` and none of it applies — you
must export the variables by hand. The user has just switched `LOCI_EMBED_MODEL` to
`embed-qwen3-4b` in that file and it takes effect in the container only.

## The rule

Resolution becomes, for every configurable value:

**real environment → `loci.env` → the single-value dotfile → default**

The real environment stays first so a one-off `LOCI_EMBED_MODEL=… clojure -M:serve` still
overrides the file. In Docker nothing changes: compose already turns `loci.env` into real
environment variables, so the same values arrive by the first route, and `.dockerignore` keeps
the file out of the image.

## The trap, which is the whole reason this needs care

**`loci.env` currently contains `LOCI_DATA=/data`.** That is a *container* path. The moment
`clojure -M:serve` starts reading this file, a local run would try to open `/data` — and with
yesterday's writability check it would refuse to start with a message about Docker volumes,
which is a baffling thing to hit on your own laptop.

It is also **redundant**: `Dockerfile:29` already does `ENV LOCI_DATA=/data`, so the container
has it regardless.

So: **`LOCI_DATA` comes out of `loci.env.example`**, and the README says why — it is a
deployment fact, not configuration, and the image supplies it. `PORT` is already excluded for
a similar reason and the example says so; follow that wording.

The user's own `loci.env` still has the line. **Do not edit it** — it is gitignored and
theirs. Report that they should remove it, and make sure the failure if they don't is the
clear data-directory message rather than something obscure.

---

### Task 1: Read `loci.env`

**Files:** create `src/loci/config.clj`, `test/loci/config_test.clj`; modify
`src/loci/agent.clj`, `src/loci/embed.clj`, `src/loci/substrate.clj`, `loci.env.example`,
`README.md`

- [ ] **Step 1: Read the three existing seams first.** `loci.agent/env`, `loci.embed/env` and
  `loci.substrate/data-dir` each reach `System/getenv` directly. `agent` and `embed` each have
  a private `env` indirection that exists **because `System/getenv` is a Java static that
  `with-redefs` cannot reach** — every config test on this repo redefines those seams. Whatever
  you build must keep them redefinable, because the existing tests do exactly that and must
  keep passing untouched.

- [ ] **Step 2: Write the failing tests** in `test/loci/config_test.clj`, against temp paths:

  - a key present in the real environment wins over the same key in `loci.env`
  - a key only in `loci.env` resolves
  - a key in neither resolves to nil, so the existing dotfile/default chain still runs
  - **blank is unset**, consistently with everything else: `FOO=` in `loci.env` falls through
    rather than resolving to `""`
  - parsing: `#` comments, blank lines, `export FOO=bar`, surrounding single and double quotes
    stripped, `=` inside a value preserved, whitespace around the key trimmed
  - a missing `loci.env` is not an error
  - **a malformed line is skipped, not fatal** — and loci still starts. A config file that
    stops the app because of one bad line is worse than one that ignores it; say which lines
    were skipped
  - the file is re-read when it changes on disk (or is not cached at all) — a config value must
    not be frozen at first touch, the way `endpoint` once was as a `def`

- [ ] **Step 3: Run, watch fail. Step 4: Implement.**

  `loci.config/env` — `(or (System/getenv k) (get (dotenv) k))`, where `dotenv` reads
  `loci.env` from the working directory. **Reading and parsing per call is acceptable** —
  `from-file` already does `.exists` + `slurp` per call in the same chain — but if you cache,
  key the cache on the file's `lastModified` and say why in a comment.

  Then make `loci.agent/env` and `loci.embed/env` delegate to it, keeping both as the
  redefinable seams they are. `loci.substrate/data-dir` should use it too, so `LOCI_DATA` in
  `loci.env` works for anyone who genuinely wants it — the reason it leaves the example is that
  the *default value there* is container-specific, not that the key is unsupported.

- [ ] **Step 5: Green — including every pre-existing config test, unchanged.** If you had to
  edit one, say which and why; that is a signal you moved a seam you should not have.

- [ ] **Step 6: Prove it bites.** One batch, distinct tests: make `loci.env` win over the real
  environment; treat blank as set; make a malformed line throw.

- [ ] **Step 7: `loci.env.example` and README.** Remove `LOCI_DATA` from the example with a
  comment in the style of the existing `PORT` note. Add a short README paragraph: one file,
  both ways to run, precedence, and that `LOCI_DATA`/`PORT` are deployment facts the image
  supplies.

- [ ] **Step 8: Verify both paths for real.**
  - `clojure -M:serve` in a scratch directory with a `loci.env` you write there, a throwaway
    `LOCI_DATA` and a free port — confirm the startup line shows the model from that file.
  - The container path is unchanged, so **do not rebuild the image or touch the user's running
    stack.** Say in your report that you did not.

- [ ] **Step 9: Commit** — `feat: loci.env configures a local run too, not only a container`

## CRITICAL

- **NEVER write to, point anything at, or modify `data/`.** `data/substrate.edn` md5
  `e7a6eecdff7bb44ad6b959bb1a7fb7c6`, `data/memory.edn` md5
  `1999a9d108df48b17ccbb51291a0a955`. Check and report both.
- **Do not edit the user's `loci.env`** — it is gitignored, holds real keys, and is theirs.
  **Never print its values.**
- **The user has a live `docker compose` stack on port 7777.** Do not stop, restart, rebuild or
  take that port.
- Do not call a real model or embedder. No `pkill -f`. **Never overstate what the code does.**
- `clojure -M:test` once. **Do not run the browser suite.**

## Out of scope

- A config-file format other than the dotenv one compose already requires
- Making the path configurable beyond `loci.env` in the working directory
- Changing any resolution order other than inserting `loci.env` in the stated position
