# External sources, phase 3: a location can be remote

Date: 2026-08-23
Status: **design settled, nothing built.** Everything below is design except where it says measured.

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

and **10.5 s on a first call**, because `aws` autoinstalls over the network before it can refuse.

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

loci never sees a key, never writes one, and never has to rotate, back up or redact one. The storage
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
