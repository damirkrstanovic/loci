# Short, human-readable ids

Date: 2026-08-15
Status: **noted, not designed.** Raised by the user while reviewing `forget!`. No
brainstorming session has been held; nothing here is approved for planning.

## The idea

Ids in loci are counters: `mem-247`, `space:dd-37`, `doc:corpus-9`, `tbl:corpus-2`. The
proposal is to adopt ids of the shape YouTube uses — short, opaque, fixed-width, generated
rather than counted, and still short enough to read aloud and type: `mem-k3Qx7`, not
`mem-247` and not `mem-9f8b1c2a-...`.

## Why it is worth doing

**The counter is machinery that exists only to keep itself correct.** Measured 2026-08-15:
exactly one function uses an id's *ordinal* for anything semantic — `memory/older`, breaking
a `:ts` tie when choosing a merge survivor — and its own docstring already handles a
non-`mem-<n>` id by falling back to string order. Everything else that touches the ordinal
(`factlog/ordinal`, `DbiLog/append!`'s counter max, `fact-log`'s seeding) exists to maintain
the sequence.

That machinery has a history. Counting produced the 2026-07-23 data loss — `space:dd-` minted
from a count over a truncated log overwrote three live notebooks — and closing it took six
minters, a durable `:facts/next-id` in `meta`, and `with-transaction-kv`. Generated ids need
none of it: collision-free by construction rather than by argument.

**And `import-csv!` still mints `tbl:csv-<count>-<rand>`** (`server.clj:1779`), the last
count-plus-random minter. It survives precisely because closing it changes a user-visible id
shape — which is the same cost this change pays once, deliberately, for every prefix.

## Why plain UUIDs are not the answer

Measured the same day: swapping `mint-id!` for `(random-uuid)` passes the existing tests —
40 concurrent identical-text `remember`s across 4 topics gave 4 facts and 4 distinct ids —
and deletes the whole counter column. But `mem-42` is quotable and
`2099fcc3-5a26-4723-9a91-bc25f762c607` is not. The memory pane prints ids, entity pages cite
them, `unmerge` takes a pair of them, and people say them out loud. A readable short id keeps
what the counter was actually buying.

## What it would cost

- **Every user-visible id changes shape.** Existing ids must either be migrated or coexist.
  Coexistence is cheap here — `id-ordinal` already returns nil for anything that is not
  `mem-<n>`, and `older` already falls back to string — so old and new can sit side by side
  without a migration.
- **`older`'s tie-break stops meaning insertion order.** With equal `:ts` the survivor would
  be chosen by string comparison: deterministic, no longer meaningful. Since equal `:ts` means
  equally old, probably nothing real depends on it — but it is the one behaviour change to
  confirm rather than assume.
- Alphabet choice matters more than it looks: excluding look-alikes (`0`/`O`, `1`/`l`/`I`) is
  what makes an id sayable rather than merely short.

## Not urgent

Nothing is broken. The counter is correct, atomic and durable as of 2026-08-15. This is a
readability and simplicity change, filed so that the next person to touch id minting finds
the argument already written down instead of rebuilding the counter reasoning from scratch.
