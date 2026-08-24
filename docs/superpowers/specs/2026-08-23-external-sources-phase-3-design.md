# External sources, phase 3: a location can be remote

Date: 2026-08-23
Status: **built and measured, 2026-08-23.** Everything below was design; what the building changed
is struck through or corrected in place, and the numbers are under *Measured 2026-08-23*.

Phase 1 shipped the framework and the file connector; phase 2 gave the shell a way in; the
housekeeping branch merged at `98c3757` cleaned up after both. This phase adds `http://`, `https://`
and `s3://` to the set of locations a source can have.

It is the smaller half of what phase 1 deferred. The other half — SQL databases — is the next spec,
and phase 1 predicted the coupling correctly: *"which is also the spec that unblocks SQL databases,
since credentials are the thing they share."* This spec answers the credentials question, so that one
does not have to.

## The rule

**A remote location is a location, and nothing else changes.**

Phase 1 built `sourcespec/relation` to emit `read_csv('<location>')` from whatever `locate` returns.
For `loci://` that answer is a resolution under `<LOCI_DATA>`; for `file://` it is a host path;
**for `http://`, `https://` and `s3://` it is the URI itself.** `locate` becomes identity for remote
schemes and DuckDB does the reading.

**`http://` is in the set, corrected 2026-08-23 while planning.** An earlier draft listed only
`https://`, and its own testing section then asked for a local HTTPS server — which the harness
cannot provide, since `test/loci/chunks_test.clj`'s `with-stub` pattern runs http-kit on a loopback
port in plaintext. `httpfs` reads both schemes, and refusing plaintext while accepting TLS would be
loci policing the transport of a location a person typed, which is the opposite of the decision
`file://` already records: *"a host path is spelled `file://`, which is unrestricted by decision."*

So this phase adds no reader, no handle shape, no failure marker and no route. That is not a
coincidence — it is the design phase 1 chose, stated as *"a source is a location, a format and a
mode; DuckDB is the connector framework"*, finally being paid out.

## Credentials: loci stores none

**`CREATE SECRET (TYPE s3, PROVIDER credential_chain)`**, issued once against the lake's connection,
**on first `s3://` use rather than at lake open**.

**On first `s3://` use, not first remote use — corrected 2026-08-23 after measuring.** An earlier
draft said "first remote use", and that would have been a real defect. `CREATE SECRET … PROVIDER
credential_chain` is **validated at CREATE time**: the `aws` extension walks the entire chain right
there and throws if it finds nothing. Measured on a machine with no AWS credentials, twice on two
fresh connections: **6,069 ms and 6,053 ms**, ending in

```
Invalid Configuration Error: Secret Validation Failure: during `create` using the following:
Credential Chain: 'config'
```

and **10.5 s on a first call**, ~~because `aws` autoinstalls over the network before it can
refuse.~~ **The reason is wrong, corrected 2026-08-23 after re-measuring** — the first CREATE of a
process still costs ~10.0 s with `aws` already installed *and* explicitly loaded, and the `LOAD`
itself is 46 ms. The extra four seconds are a once-per-process cost inside the chain walk, which an
offline image pays too. See *Measured 2026-08-23*, section 4.

So issuing the secret on first *remote* use would have put six seconds and a hard failure in front of
every `https://` read on every machine without AWS credentials — most laptops, and this project's own
test suite. The secret is therefore conditional on the `s3://` scheme, and an `https://` read issues
none.

**A failed attempt is deliberately not remembered**, so a user who fixes their credentials and
retries succeeds without restarting loci — at the cost of paying the ~6 s again on each attempt that
still has none.

**`aws` is a second extension, and the image needs it.** `httpfs` alone reads `https://` and fails
`s3://` with `Extension Autoloading Error: … the required extension 'aws'`. Verified against an
extension directory holding only `httpfs` with autoinstall off — which is exactly the offline image
this spec targets. The AWS SDK chain finds environment variables,
`~/.aws/credentials`, an EC2 instance profile, or IRSA in Kubernetes — the same way every other tool
in that container already does.

**Lazily, and that is load-bearing.** `open-lake!` holds one long-lived JDBC connection behind a
`delay`, and it calls `ensure-extension!` inside a `try` that closes the connection and rethrows. That
is correct for `ducklake`, which is required — no extension, no lake, and loci should say so at boot.
It would be wrong for `httpfs`: a loci that never touches a remote source would fail to start because
an extension it does not need is missing. So loading `httpfs` and issuing the secret happen on the
first remote read, and their failure is a **connect-time** error against one source, not a boot-time
error against everything.

**But a URL can itself be a credential, and that one loci does commit.** Corrected 2026-08-23 after
Task 9 added the door. Two ordinary URLs carry secrets:

- a **presigned S3 URL**, carrying `X-Amz-Signature` — which is precisely the escape hatch this
  section points at for a bucket the chain cannot reach;
- `https://user:pass@host/data.csv`, which is a password in a URL.

Both are typed by a person into the ＋ Data panel, committed **verbatim** into the append-only log,
and drawn in the table's source badge. So the sentence below is true of the credentials loci
*manages* and false of the ones a user hands it by another name.

**Recorded, nothing built — decided 2026-08-23.** Stripping userinfo or the query from what is stored
would mean the stored uri is not the uri that works, and `refresh!` would break for exactly the
presigned case that most needs it: worse than the disease. Refusing userinfo touches the smaller half
and dresses it as a solution. A warning in the panel only reaches the user who reads it.

The mitigation that actually holds is the one presigning already provides: **a presigned URL
expires.** A password in a URL does not, and someone who types one has put it somewhere `undo!`
does not remove. That is the honest boundary, and it is the same one `file://` already draws.

loci never sees a key it manages, never writes one, and never has to rotate, back up or redact one. The storage
panel gains nothing to account for. `undo!` and `as-of` cannot resurrect a credential, because none
was ever committed — which matters more here than anywhere else in loci: **the substrate is an
append-only log, so a secret written into it is recoverable forever by design.** That is the log's
whole point and the reason it must not hold one.

The boundary this draws, stated plainly rather than hidden: **a bucket the chain cannot reach fails
with an auth error, and the user fixes it outside loci.** There is no paste-a-key box and no
`secrets.edn`. Both were considered; both make loci responsible for plaintext credentials, which is a
responsibility this project has no reason to take on and a new class of thing to get wrong.

This is the same rule as *"use what the tool already provides, before inventing a mechanism"*. The
credential chain is a solved problem on the classpath.

## Materialise only

`connect!` **refuses `:mode :passthrough` for a remote scheme**, with the shape of sentence phase 1
already composes for an unknown format.

The reason is a measured cost, not a preference. Phase 1 measured a passthrough connect at 10M rows
as **294 ms, of which 244 ms is the `count(*)`** — one full pass over the file, and `hydrate` does a
liveness check on every render besides. Locally those are disk reads. Over the network they are a
full object fetch per query and a round trip per render, and loci has no query timeout to catch it:
a single mis-chosen mode would make a notebook slow in a way nothing local can, with the cause
invisible.

**Refresh is the answer instead**, and phase 2 already built the button. A remote source is fetched
when you ask for it and is an ordinary lake table in between.

### The consequence that makes this cheap

**A remote materialised source writes no file into `<LOCI_DATA>/files/`.** The bytes go straight into
DuckLake. So the files GC merged at `98c3757` does not apply to a remote source at all — `loci.lakegc`
does, and its `{:versions 3 :days 30}` window already covers the versions a refresh leaves behind.

Nothing new is needed for storage accounting, and the storage panel's files section stays about
uploads and pastes, which is what its sentence already says.

## The extension is a deployment change

`httpfs` is the **second** extension with the problem `loci.lake/ensure-extension!` already documents
at length, and the Dockerfile already carries both the pattern and the scar tissue:

- INSTALL is the one step that reaches the network, so a container installs at build time into
  `/duckdb-ext`;
- the `test -f` guard on that line is not ceremony — its comment says *"a silently empty /duckdb-ext
  produces an image"* that fails on first ATTACH;
- the raw-JDBC-no-alias spelling exists so the extension version matches the driver that will load it.

One more `INSTALL httpfs` beside the `ducklake` one, with the same guard. Outside Docker the existing
LOAD-then-INSTALL fallback reaches the network once, on first use.

**But not through `ensure-extension!` as it stands.** That function is called from `open-lake!`'s
`try`, and its whole error path composes a message about the lake being unable to open. `httpfs`
needs the same LOAD-then-INSTALL logic with a different consequence — refuse this source, not the
store — so either it grows an argument naming the extension and the caller supplies the failure
sentence, or the retry logic is lifted and both callers wrap it. Whichever, **the ducklake message
must not change**: it is measured prose about a real deployment failure, and it earns its length.

`INSTALL` is also what makes the two suites' offline promise conditional: a machine that has never
seen `httpfs` will reach the network on the first remote test. The tests below avoid the *reads* going
out by serving loopback plaintext, but the extension itself still has to arrive once.

## Errors

One function needs a remote branch, and it is not obvious from its name.

**`sourcespec/unreadable-because` builds a `java.io.File` from `locate`'s answer.** Today `locate`
throws on a remote scheme and the function's own `catch` answers *"loci cannot resolve
s3://bucket/x.csv — …"*, which is correct. The moment `locate` returns a remote URI verbatim, that
line becomes `(java.io.File. "s3://bucket/x.csv")`, whose `.exists` is false, and the sentence
becomes **"there is no file at s3:/bucket/x.csv"** — wrong, with a mangled path, on the failure path
where its docstring says a second error must never be raised over the first.

A remote uri is not a file and loci cannot cheaply know why a remote read failed, so the honest
branch says so and lets DuckDB's own message through, which is what `duckdb-said` already exists for.

Everything else already has a sentence: an unknown format, a location `locate` refuses, two headers
that normalize to one column, a source that cannot be read. The new failures — a 404, a 403, a
missing credential chain, a bucket in the wrong region — arrive as DuckDB messages and are shown the
way every other DuckDB failure already is.

## What does not change

- ~~**The `＋ Data` panel.** Its file tab already accepts a typed URI; a remote one is a URI.~~
  **False, corrected 2026-08-23.** It does not. `dataFileTab` renders exactly two controls — an
  `<input type="file">` and a list of `.filerow` buttons built from `GET /api/source/files` — and
  both `dataPreview` call sites take their uri from the upload reply or that listing, so every uri
  the panel can produce is `loci://files/<hash>`. There is no text input in it at all.

  I asserted this without checking and it propagated into `describe`'s docstring. Left struck rather
  than deleted, because the phase was planned on it: **as built, phase 3 is reachable only by `curl`
  or a REPL** — which is the state phase 2 opened by naming as unfinished. **The door is a task of
  its own**, added to the plan on 2026-08-23.
- **The `:source` block, the live badge, drift, unavailable, `refresh!`, `:connectedAs`.** A remote
  materialised table is an ordinary materialised table.
- **`sourcespec/relation`, the `readers` map, the handle shapes.** Untouched.
- **The files GC.** See above — it never sees a remote source.

## Testing

- `locate` answers a remote uri verbatim, and still refuses a scheme it does not know.
- `unreadable-because` on a remote uri says something true and does not invent a file path.
- `connect!` refuses `:mode :passthrough` for a remote scheme, and the sentence names the mode.
- `connect!` with `:mode :materialise` over `http://` lands rows — against a loopback server built
  the way `chunks_test/with-stub` already builds one, so the suite stays offline and deterministic
  and can assert on what actually went over the wire, including that nothing did.
- `refresh!` re-fetches a changed remote object.
- The secret is issued once per connection and its SQL contains **no** credential material — the
  assertion is on the `PROVIDER credential_chain` form, since there is nothing else to assert.
- Every regression test proved to fail when its fix is reverted, and every sabotage's output read
  rather than counted.

## Measured 2026-08-23: a remote fetch costs wall clock and no heap, and a preview costs 32 MB

The question this phase closes on: **does a remote fetch change the heap story, or only the wall
clock?** It changes only the wall clock, by +19% at 200 MiB, and by nothing at all in heap.

Measured on the machine this was written on: 6 cores, 15.5 GiB RAM, JDK 26.0.2, JVM default max heap
**3,976 MB** — no `-Xmx`, because that is what `clojure -M:serve` runs with. DuckDB 1.5.5.1.
`<LOCI_DATA>` is a throwaway under `.tmp`; `data/` was not touched, and no test or measurement here
read from it.

Method, because the numbers are only as good as it. Phase 1's: memory sampled on a **20 ms daemon
thread** for the length of every call and the maximum reported — heap as `.totalMemory -
.freeMemory`, process RSS as field 2 of `/proc/self/statm` read with `Files/readString`, which is the
only spelling that works on procfs — with a triple `System.gc` bracketing each call for the live-heap
figures beside it. The fixture lives under `.tmp` on real disk, deliberately **not** on this machine's
tmpfs `/tmp`, so the file's own bytes never count against RAM. `/home` is btrfs mounted
`compress=zstd:3`, which matters for what the file occupies and not for anything measured here.

Two things this measurement does that the earlier ones did not, both because the subject is a fetch:

- **The HTTP server is a separate process.** A loopback http-kit stub inside the JVM under
  measurement would put its own request buffers into the very peak-heap number the question is
  about. It is a small Python server that speaks `Range`, answers `Accept-Ranges: bytes`, writes
  with `sendfile`, and logs one line per request — method, path, the `Range` header verbatim,
  status, and the bytes actually written to the socket. **The request log is the instrument**, and
  section 2 is unavailable without it.
- **The headline figures come from cold JVMs**, two of them, one connecting local-then-remote and
  one remote-then-local, three connects each — so an order effect would show as a difference between
  the two runs, and does not.

### The fixture

`id,region,amount,day`, from which DuckDB infers `BIGINT, VARCHAR, DOUBLE, DATE`; `region` carries
six distinct values. Phase 1's shape exactly, so the two are comparable.

| rows | bytes | generated in |
|---|---|---|
| 5,370,000 | 209,872,911 (200.2 MiB) | 2,047 ms |

### 1. Local against remote, same file, same JVM — the heap does not move

Every connect is `source/connect!` with `:mode :materialise`, and every one of the twelve returned
5,370,000 rows.

| | local `file://` | remote `http://` |
|---|---|---|
| run A wall (local first) | 543.0 / 515.7 / 510.4 ms | 612.8 / 605.5 / 629.1 ms |
| run B wall (remote first) | 516.3 / 534.0 / 521.0 ms | 674.6 / 623.5 / 614.3 ms |
| **median of six** | **518.7 ms** | **618.9 ms** |
| peak JVM heap | 13.6 – 14.5 MB | 13.6 – 15.1 MB |
| live heap, before → after | 12.6 → 12.6 MB, all twelve | 12.6 → 12.6 MB, all twelve |
| peak process RSS | 523.9 – 616.9 MB | 667.4 – 841.2 MB |

**+100.2 ms, +19.3%, and 0 MB.** The rows never enter this process either way, so the only thing a
socket adds over a file descriptor is time.

**The absolute heap figures are not phase 1's 57 MB, and the reason is the baseline rather than the
work.** These ran in a bare `clojure -M -e` whose live heap is 12.6 MB; phase 1's harness sat in a
JVM whose live heap was 55 MB. What is comparable is the **distance from the baseline**, and it is
the same number in both: phase 1 peaked 57 MB against a 55 MB baseline (+2 MB), these peak 13.6 MB
against 12.6 MB (+1 MB), and a warm nREPL with an 82.5 MB baseline peaked **83.4 MB (+0.9 MB)** for
the same remote connect. Three baselines spanning a factor of six, one file, and the file's
contribution to heap is one megabyte in all three. That is a stronger statement than phase 1's
flat 57 MB and it subsumes it.

Process **RSS is not flat**, exactly as phase 1 found for local files: DuckDB's native buffer
manager pays for the fetch, and the remote runs sit ~150 MB above the local ones at their peak. That
is where the bytes are, and it is bounded by DuckDB's own `memory_limit` rather than by the object.

The wall clock is also consistent with phase 1 across a different machine state: 398 MiB in 893 ms
there, 200.2 MiB in 518.7 ms here — 1.99× the bytes for 1.72× the time.

### 2. What actually goes over the wire — one prefix, not a second copy

The plan asked whether a `DESCRIBE` fetches the whole object or a prefix, because the browser test
saw `[HEAD, GET, HEAD, GET]` per connect and **two full fetches of a 200 MiB object is a different
story from two range reads.** It is neither. Per `connect!`, identical in both cold runs and in the
warm one:

| statement | requests | bytes served |
|---|---|---|
| `describe` — `DESCRIBE SELECT *` | `HEAD`, `GET bytes=0-31999999` | 32,000,000 |
| `land!` — `CREATE TABLE … AS SELECT` | `HEAD`, then 7 `GET`s walking 0 → 209,872,910 in 32 MB ranges | 209,872,911 |
| **per connect** | **10** | **241,872,911 — 1.15× the object** |

So the browser test's four requests are the same shape seen on a 3 KB fixture, where one range
covers the whole file. httpfs reads a remote CSV in **fixed 32,000,000-byte ranges** — an even
decimal constant, not a fraction of the object — and `describe`'s cost is one of them, re-fetched at
the head of the scan. The overhead is therefore **+15.3% at 200 MiB, +1.5% at 2 GB, and +100% —
a genuine second full fetch — on anything smaller than 32 MB.**

**A preview downloads 32 MB, and the external file cache does not save the second one.**
`enable_external_file_cache` reports `true` on a lake `open-lake!` opens, and two consecutive
`DESCRIBE`s over the same URL still issued `HEAD` + `GET bytes=0-31999999` each — 71.4 ms and
68.3 ms, two requests apiece. **This is the number the ＋ Data panel task did not have.** That task
worried about the six seconds an `s3://` describe costs on a machine *without* credentials; it did
not know that a describe *with* credentials moves 32 MB of the object before it can name four
columns. Over loopback that is ~470 MB/s and invisible. Over a link it is 32 MB per preview, per
preview, and it is egress somebody pays for.

It also puts a number on the passthrough refusal this spec argues for on phase 1's reasoning. A full
scan of this object is **209,872,911 bytes on the wire**, and `hydrate` does a liveness check on
every render besides. The design refused remote passthrough from a local `count(*)` measurement;
the wire figure says the same thing in the units that matter.

### 3. The `:once` per connection — what the second remote connect costs

| | |
|---|---|
| `ensure-remote!`, first call on a connection — `LOAD httpfs`, no secret for `http://` | 20.4 / 30.3 ms |
| `ensure-remote!`, second call on the same connection | **0.1 ms** |
| `LOAD httpfs` alone, cold JVM | 22.5 ms |
| `LOAD aws` alone, cold JVM | 46.0 ms |
| first remote `connect!` on a fresh connection | 674.6 ms cold, 831.0 ms warm |
| second remote `connect!`, same connection | 623.5 ms cold, 679.2 ms warm |

The once-per-connection preparation is **20–30 ms of a ~620 ms connect, about 4%**, and it is gone
entirely on the second. Most of the first-connect premium is not it: the first *local* connect shows
the same shape — 543 ms against 516 and 510 — with no `ensure-remote!` in it at all, so ~30 ms of
the 55 ms remote premium is JIT and DuckDB warming up and ~25 ms is the LOAD. The `:once` guard is
worth having and it is not what makes a first connect slower.

The extension is already installed on this machine, so the `LOAD` reads from
`~/.duckdb/extensions/v1.5.5/linux_amd64/` and reaches no network. On a machine that has never seen
httpfs the same call falls through to `ensure-extension!`'s `INSTALL`, which does — the conditional
this spec's deployment section already states.

### 4. Two numbers already written down that are wrong

**(a) The 10.5 s first `CREATE SECRET` is not the `aws` autoinstall.** This spec says *"and 10.5 s on
a first call, because `aws` autoinstalls over the network before it can refuse."* Re-measured with
`aws` already on disk (28,456,078 bytes, `installed true`) and explicitly `LOAD`ed first:

| | cold JVM | cold JVM, repeat | warm nREPL |
|---|---|---|---|
| `LOAD aws` | 46.0 ms | — | 31.0 ms |
| first `CREATE SECRET … credential_chain` | 10,022.2 ms | 10,067.7 / 10,083.8 ms | 6,028.3 ms |
| every one after | 6,017.7 ms | 6,017.2 / 6,017.4 ms | 6,017.4 ms |

The extension arriving costs **46 ms**, and the first CREATE still costs ~10.0 s with it already
loaded. The extra ~4 s is a **once-per-process** cost inside the `aws` extension's first chain walk,
not a download and not a per-connection one: the warm nREPL, which had paid it earlier in the phase,
opened a brand-new lake connection and got 6.0 s on its *first* CREATE. Repeated because it
contradicts what was written, and it reproduced to within 60 ms across two cold JVMs.

Nothing the design rests on moves. The 6.0 s steady-state figure is unchanged, and it is still the
reason the secret is conditional on `s3://`. What changes is what an operator should expect: **an
offline image pays the four seconds too**, because it was never the network.

**(b) `secret-covers?` costs about 2 ms, not 12.7 ms.** Its docstring said 12.7 ms; corrected there
on the day, and in `ensure-remote!` on 2026-08-24. Measured cold,
the first `which_secret` of a process is 5.8 / 5.3 ms and every one after is 2.1 / 1.9 ms; warm,
five calls gave 2.3 / 7.4 / 2.1 / 2.4 / 2.6 ms. The 12.7 ms was a first call in a busier process and
it was never wrong about the decision — the check is three orders of magnitude below the 6,000 ms
`CREATE SECRET` it can skip, which is more true at 2 ms than at 12.7.

### 5. The phase's other measurements, gathered

They were spread across four docstrings and three commit messages. Everything marked *re-verified*
was re-run today; the rest is quoted from where it was taken.

| what | number |
|---|---|
| `CREATE SECRET … PROVIDER credential_chain`, no AWS credentials, steady state | 6,069 / 6,053 ms; **re-verified** 6,017 ms, and `ensure-remote!` around it at 6,075 ms |
| the same in a container with `--network none` | **62 ms**; **re-verified** 68 ms — the chain gives up rather than waiting on IMDS |
| `CREATE SECRET … REFRESH auto` on DuckDB 1.5.5 | parses; fails on chain validation at 6,179 ms, **re-verified** 6,017.9 ms — not a parse or binder error |
| `CREATE SECRET IF NOT EXISTS` | still walks the chain (6,079 ms) before it looks at the name, so it is not the fix for the race |
| a duplicate temporary secret | `Invalid Input Error: Temporary secret with name 'loci_s3' already exists!` |
| the image | before 249,391,443 → after 299,418,382 bytes, **+50,026,939 (+20.1%)**; **re-verified** with `docker inspect` |
| the three extensions | ducklake 36,436,086 · aws 28,456,078 · httpfs 21,570,542 bytes — **re-verified**, and their sum plus two `.info` files is the image delta to the byte |
| the offline image, `file://` | 2 rows materialise; **re-verified** at 471 ms |
| the offline image *before* httpfs and aws, `s3://` | died on the extension, not on credentials; **re-verified** at 503 ms with `DuckDB's httpfs extension is not available` |
| httpfs autoloads with no `LOAD` from loci | fresh lake, httpfs `loaded false`; `read_csv('https://127.0.0.1:1/x.csv')` fails on the connection; httpfs `loaded true` after — **re-verified**. What autoload does *not* cover is the secret and where the failure lands |
| a space in a remote path | `http://…/a b.csv` reaches the server as `/a%20b.csv` — **re-verified** from the server's log. httpfs percent-encodes, so a bare space is not a discriminator over the wire, and `locate`'s "verbatim" is about what loci hands DuckDB, not about what leaves the machine |

### What this establishes, and what it does not

CLAUDE.md's rule, applied line by line, because half of these numbers are about a mechanism and half
are about a cost.

- **Mechanism, all of it.** A 200 MiB CSV at an `http://` URL becomes a 5,370,000-row lake table,
  through the same `connect!` a local file goes through, with a flat heap.
- **A real cost, and not a fixture artefact: the bytes on the wire.** 1.15× the object per connect
  and 32 MB per preview are properties of httpfs and of the object. They are the same from S3 as
  from loopback, and they get *better* proportionally as the object grows, which is the direction
  the design's target points.
- **A real cost: the ~6 s credential-chain validation.** A property of the AWS SDK, reproduced on
  two machines' worth of process state and inside a container. It does not depend on the fixture at
  all — there is no object in it.
- **Not a real cost: every wall clock with bytes in it.** 618.9 ms for 200 MiB is 391 MB/s over a
  socket that never leaves the machine, reading a file already in page cache. **This says nothing
  about what an S3 fetch costs.** At a plausible 100 Mbit/s the same object is ~16.8 s of transfer
  against 619 ms here — a factor of 27, all of it in the term loopback was chosen to set to zero.
  Loopback was the right choice, because the question was whether the *local side* changes; it is
  the wrong number to quote at anybody asking how long S3 takes.
- **The heap figure is the one that carries across unchanged**, and it is the one the question was
  about. Heap is flat because the rows never enter this process — as true of an S3 object as of a
  loopback socket. DuckDB fetches into its own buffers and hands the JVM a row count.

## Known gaps, recorded rather than fixed

Found in review during this phase and left deliberately, so they are not rediscovered as news. Each
is already noted where it lives; they are gathered here because a docstring is not where somebody
looks for what a phase decided not to do.

- **`lake/from-of` reads a `{:store :source}` handle without `ensure-remote!`.** It is the one read
  path that builds `read_csv('<location>')` without coming through `source/describe`, and
  `tbl/hydrate` reaches it on every render. Unreachable today because `connect!` refuses remote
  passthrough, so no remote `:store :source` handle can be minted — but it is the path that would
  degrade first if one ever were, with `s3://` going out anonymous and the failure landing inside a
  read instead of at connect. `source/describe`'s docstring names it; nothing guards it.
- **A typed URL gets no duplicate warning.** `connectedAs` is computable — `files/connected-as`
  answers it — but no route answers it for a single arbitrary uri, so the URL door draws no
  "already a table" note where the file door draws one. Connecting the same URL twice makes two
  tables silently, which `undo!` reverses.
- **A `credential_chain` secret resolves once per process.** `lake` is a `defonce` delay, so `:s3`
  is set at most once ever. On the deployments the design names — an EC2 instance profile, IRSA —
  the chain hands out a session token that typically expires in about an hour, after which every
  `s3://` read 403s and nothing re-issues. `REFRESH auto` parses on 1.5.5 (measured above), so the
  platform has the facility; nobody has hit this, so it stays one word in a docstring. A restart is
  the workaround.

`srcFormat` matching the whole uri — so `https://host/data.parquet?token=…` would fall back to CSV —
was **fixed** rather than recorded: `c3bf0f6` splits on `[?#]` before matching the extension.

## Deliberately not in this phase

- **SQL databases.** The next spec. It inherits this one's credential answer.
- **`gs://` and `az://`.** `gs://` rides httpfs; `az://` is a separate extension with its own
  install-at-build-time problem. Added the day someone has one — the same rule the `readers` map
  already applies to Excel.
- **A configurable S3 endpoint** for MinIO, R2 or a corporate object store. `s3://` reaches them via
  an endpoint setting, and that setting is a knob nobody has asked for yet. A self-hosted loci is
  where it would show up first; the day it does, it is a `CREATE SECRET` option and not a redesign.
- **Passthrough over a remote location.** Refused, with the measured reason above. Revisit it with a
  measurement of a real remote read, not with reasoning.
- **Storing credentials in any form.** Not a deferral — a decision.

## What connecting a remote source exposes, decided 2026-08-23

**Connecting a source is a server-side fetch of a caller-supplied URL — and so is previewing one.**
After this phase, a `POST /api/source/connect` **or `POST /api/source/describe`** makes loci issue an
outbound request from its own network position. `describe` matters more than it looks: it is the
cheap-by-construction route, it commits nothing, and `server.clj`'s own routing comment argues the
source routes sit on the safe side of the line partly for that reason. For `s3://` it is now also the
route that issues the credential secret, so an unauthenticated preview signs its requests with the
host's IAM identity — and
`server.clj`'s `(http/run-server #'handler {:port port})` passes no `:ip`, so http-kit binds
`0.0.0.0`, and the handler has no authentication.

Put beside this spec's own credential decision — the process holds ambient cloud credentials **by
design**, because `PROVIDER credential_chain` is the whole point —
`http://169.254.169.254/latest/meta-data/iam/security-credentials/` becomes a loci table on EC2, and
the same for the ECS and EKS metadata endpoints.

**No guard is built, and that is a decision rather than an oversight.** Reaching the port is already
total compromise: every fact in the substrate is readable, `file://` is unrestricted by decision so
any file the process can read is readable, and everything is deletable. An attacker who can POST to
`/api/source/connect` does not need the metadata endpoint to own the data. What this phase adds is
**reach** — the server's network position, including link-local addresses only it can see — and on a
cloud instance that widens the blast radius from loci's own data to the instance's role.

The alternatives were considered and rejected for stated reasons:

- **Blocking link-local ranges** is a partial guard that DNS rebinding, an HTTP redirect to the
  metadata IP, and IPv6 spellings all route around. Shipping protection that cannot deliver what its
  name promises is the failure mode `reclaim!`'s interleave note already argues against.
- **Binding to loopback by default** addresses the cause rather than a symptom and helps every route,
  not this one — but it changes how loci is reached and belongs to a deployment decision, not to a
  source-connector phase.
- **Designing authentication first** is the honest long answer and is out of scope here.

**The deployment answer is not to expose the port.** That was already true; this phase is the point at
which it stops being only about confidentiality of the substrate and starts being about the
credentials of the host.

## Cleanup 2026-08-24: what a review of the finished phase changed

A code review of the whole branch on 2026-08-24. The **code** came out lean — 148 of 829 added lines
in `src/` are code, the rest docstrings and comments — and nothing in it was over-built. What had
gone wrong was the prose around it, in exactly the way the volume predicts, plus one wrapper that had
produced a duplicated sentence a person reads on screen.

**One correction had landed in two of its four homes.** `11ad7e7` re-measured `secret-covers?` at
~2 ms and retracted the story that the first `CREATE SECRET`'s extra four seconds were the `aws`
extension autoinstalling. Both corrections reached `secret-covers?`'s docstring and this document;
neither reached `ensure-remote!`'s docstring, which still said *"the check costs 12.7 ms"*, or
`lake_test.clj`, which still carried the retracted autoinstall explanation as a comment. Fixed in
`34537d1`. The cause is structural rather than careless: every measured number in this phase is
written down in two to four places — `36,436,086` was in `lake.clj`, this spec, the `Dockerfile` and
a test — with nothing keeping them in agreement. `e9b82bf` gives each number one home, at the
decision it supports, and leaves a pointer everywhere else. The image figures now live only in the
`Dockerfile` beside the `COPY` that pays for them, and here.

**A remote extension failure drew two headlines for one failure.** Rendered on 2026-08-24, a missing
`httpfs` said `loci cannot read <uri> — DuckDB's httpfs extension is not available.` and then, one
blank line later, `loci cannot load DuckDB's httpfs extension.` — both drawn in the ＋ Data panel
under `.srcerr`. The cause was one concern implemented twice: `extension-problem` derived
`required? (= "ducklake" ext)` to gate two ducklake-only sentences, and `ensure-remote!` then wrapped
the whole message in a *second* composer to add its own, which also had to `str/trimr` a newline the
flag left behind and read the extension name back off the cause's `ex-data`. `dc0b88c` gives
`extension-problem` a `head` and a `tail` its caller supplies and deletes the wrapper, the flag, the
fix-up and the read-back. The ducklake boot message is byte-identical to a capture taken before the
change (543 bytes both ways); `the-ducklake-paragraph-did-not-move` pins it with the directory and
uid stubbed so it holds on any machine.

**Sixty assertions that read a sentence went, and one that measures a shape stayed.** 46 % of this
phase's Clojure assertions were `str/includes?` readings of an error message — 28 of 40 in
`sourcespec_test`. Four `locate` prose tests folded into one in `1cac901`; the sabotage that proves
what is left still bites is recorded in the test's own comment and was re-run. The same pass had also
deleted `a preview failure keeps the shape of the paragraph loci wrote`, the only coverage anywhere
for `.reclaimed p.srcerr{white-space:pre-wrap}`; that deletion was not taken, because a count of line
boxes passes under `pre-line` too and the glyph-position measurement is what separates them.

### Measured 2026-08-24: the 32 MB preview prefix, pinned

The phase's most surprising cost was prose only. It now has a test, and the figure reproduced exactly.
One `describe` against a 40 MiB loopback fixture (41,943,042 bytes):

```
  Range: bytes=0-1                    2 bytes   (httpfs learning the object's size)
  Range: bytes=0-31999999    32,000,000 bytes
                             ----------
                             32,000,002 bytes over the wire
```

It is **fixed, not proportional** — measured across four sizes plus one lie:

| object | ranges requested |
|---|---|
| 20 MiB | `bytes=0-1`, `bytes=0-20971538` — the whole thing |
| 40 MiB | `bytes=0-1`, `bytes=0-31999999` |
| 60 MiB | `bytes=0-1`, `bytes=0-31999999` |
| 100 MiB | `bytes=0-1`, `bytes=0-31999999` |
| 2 GiB, *claimed* by a stub holding 40 MiB | `bytes=0-1`, `bytes=0-31999999` |

**And the second preview pays it again.** Same URL, same connection, back to back:
`["bytes=0-1" "bytes=0-31999999" "bytes=0-1" "bytes=0-31999999"]` — with
`enable_external_file_cache` reading `true` on that connection, so this is not a disabled cache.
Preview, ‹ back, preview really is 64 MB.

**Two bytes of that total are the harness, and the number must not be quoted back without them.**
http-kit cannot answer a HEAD truthfully: `HttpUtils.HttpEncode` forces `content-length: 0` on any
nil-bodied response and there is no HEAD special case, so the obvious stub tells httpfs the object is
empty and httpfs answers with one plain un-ranged GET — the vacuous-rather-than-red trap. The stub
therefore answers **405** to HEAD, which is what httpfs's own fallback exists for (a 404 does not
fall back; it fails the read outright), and the fallback costs the extra `bytes=0-1` probe. That the
fallback is otherwise faithful was measured separately, by provoking a truthful HEAD through a
`Transfer-Encoding: chunked` bypass of the `putOrReplace` guard: `HEAD` + `GET bytes=0-31999999`,
nothing else, same prefix. **So production against a HEAD-answering host pays 32,000,000 and the test
asserts 32,000,002.**

What this establishes: that the cost is **real and bounded**, and that it does not scale with the
object. What it does not establish is anything about wall clock — loopback zeroes the term that
matters, exactly as the 2026-08-23 measurement above says.

The two tests add **1.03 s** to `clojure -M:test` (2113 ms for `loci.source-test` with them, 1087 ms
without), generate one shared 40 MiB fixture and push 128 MB over loopback. Written down because a
suite nobody will run is worse than a test nobody wrote.

### Both suites, on this tree

`clojure -M:test` — **874 tests, 4704 assertions, 0 failures, 0 errors**, 71 s.
`npm run test:browser` — **159/159**, no `outline` flake.
