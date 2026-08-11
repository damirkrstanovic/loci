#!/usr/bin/env python3
"""MetObjects.csv (~303 MB, ~490k rows, CC0) -> a ~300-row entangled extract.

Fetch first (Git LFS media, not the raw pointer file):

    curl -L -o MetObjects.csv \\
      https://media.githubusercontent.com/media/metmuseum/openaccess/master/MetObjects.csv

then run this from the same directory. Writes art-objects.csv.

Selection is deterministic: the file is read once in its own row order, each row
is offered to the themes in order, and the first `cap` rows that satisfy a
theme's predicate are kept. `era` is bucketed here so the committed file is
already in the shape `cat-col` needs.
"""
import csv, re, sys
from collections import Counter, OrderedDict

csv.field_size_limit(10_000_000)
SRC = "MetObjects.csv"
OUT = "art-objects.csv"

DUTCH_MASTERS = ["rembrandt", "vermeer", "frans hals", "ruisdael", "jan steen",
                 "cuyp", "claesz", "hobbema", "ter borch", "de hooch",
                 "van goyen", "gerard dou", "gabriel metsu", "avercamp",
                 "van heemskerck", "maerten", "jan van huysum", "willem kalf",
                 "ambrosius bosschaert", "adriaen van ostade", "van de velde"]


def era_of(y):
    if y < 500:    return "Before 500 CE"
    if y < 1400:   return "500-1399"
    if y < 1600:   return "1400-1599"
    if y < 1700:   return "1600-1699"
    if y < 1800:   return "1700-1799"
    if y < 1900:   return "1800-1899"
    if y < 1946:   return "1900-1945"
    return "After 1945"


def clean(s):
    return re.sub(r"\s+", " ", (s or "")).strip()


def main():
    src_n = 0
    themes = OrderedDict()

    def theme(name, cap, pred):
        themes[name] = {"cap": cap, "pred": pred, "rows": []}

    low = lambda r, k: (r.get(k) or "").lower()

    theme("cobalt", 45,
          lambda r: "cobalt" in low(r, "Medium") or "cobalt" in low(r, "Title"))
    theme("dutch masters", 55,
          lambda r: any(d in low(r, "Artist Display Name") for d in DUTCH_MASTERS))
    theme("dutch and netherlandish", 30,
          lambda r: ("dutch" in low(r, "Culture") or "netherlandish" in low(r, "Culture"))
                    and clean(r.get("Artist Display Name")))
    theme("delft", 25,
          lambda r: "delft" in low(r, "Culture") or "delft" in low(r, "Title")
                    or "delft" in low(r, "Medium"))
    theme("chinese porcelain", 45,
          lambda r: "china" in low(r, "Culture") and "porcelain" in low(r, "Medium"))
    theme("lead-bearing media", 40,
          lambda r: re.search(r"\blead\b|lead-glaz|white lead|lead white", low(r, "Medium")))
    # breadth: every era bucket gets a stride-sampled fill so the corpus is not
    # all seventeenth-century Holland
    for e in ["Before 500 CE", "500-1399", "1400-1599", "1600-1699",
              "1700-1799", "1800-1899", "1900-1945", "After 1945"]:
        theme(f"breadth {e}", 14,
              (lambda ee: lambda r: r["_era"] == ee and r["_stride"])(e))

    seen = set()
    with open(SRC, encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for i, r in enumerate(rd):
            src_n += 1
            try:
                y = int(r["Object Begin Date"])
            except (TypeError, ValueError):
                continue
            if y == 0 or y < -4000 or y > 2030:
                continue
            title = clean(r["Title"])
            medium = clean(r["Medium"])
            if not title or not medium or title.lower() in ("untitled", "unknown"):
                continue
            r["_era"] = era_of(y)
            r["_stride"] = (i % 977 == 0)
            oid = r["Object ID"]
            for name, t in themes.items():
                if len(t["rows"]) >= t["cap"] or oid in seen:
                    continue
                if t["pred"](r):
                    seen.add(oid)
                    t["rows"].append(OrderedDict([
                        ("Title", title),
                        ("Artist Display Name", clean(r["Artist Display Name"])),
                        ("Culture", clean(r["Culture"])),
                        ("Country", clean(r["Country"])),
                        ("Medium", medium),
                        ("Department", clean(r["Department"])),
                        ("era", r["_era"]),
                        ("year", y),
                    ]))
                    break
            if all(len(t["rows"]) >= t["cap"] for t in themes.values()):
                break

    out = [row for t in themes.values() for row in t["rows"]]
    out.sort(key=lambda r: (r["year"], r["Title"]))

    cols = ["Title", "Artist Display Name", "Culture", "Country", "Medium",
            "Department", "era", "year"]
    # ------------------------------------------------------------------------
    # THE CAT-COL GUARD. This is the finding, not a workaround.
    #
    # `cat-col` (src/loci/content.clj) sorts candidate string columns by their
    # DISTINCT COUNT ASCENDING and takes the first — "the coarsest category
    # wins". So among columns with 2..12 distinct values, FEWER ALWAYS WINS,
    # and a two-valued boolean flag therefore beats a nine-value taxonomy every
    # time. USGS's `Is critical mineral 2025` (Yes/No) would have out-ranked
    # `Commodity`; MET's `Department` (10 values here) out-ranks `era` (8).
    #
    # Nothing warns you. The table still molds as Pivot and Bar — it just
    # pivots on the wrong axis, and the coarse column you designed is inert.
    # So every passthrough string column is measured here, and any that lands
    # in 2..12 is DROPPED rather than allowed to take the Pivot silently.
    #
    # The real fix belongs in loci, not here: "coarsest" ought to mean
    # something other than "fewest", or cat-col ought to be declarable. Until
    # then this guard is the only thing standing between a designed corpus and
    # a Pivot grouped by Yes/No.
    # ------------------------------------------------------------------------
    for c in ["Department", "Country", "Culture"]:
        d = len(set(r[c] for r in out))
        if 2 <= d <= 12:
            print(f"!! {c} has {d} distinct values — it would steal cat-col; dropping",
                  file=sys.stderr)
            cols.remove(c)
            for r in out:
                r.pop(c)
        else:
            print(f"   {c}: {d} distinct — safe", file=sys.stderr)

    with open(OUT, "w", newline="") as f:
        w = csv.DictWriter(f, cols)
        w.writeheader(); w.writerows(out)

    print(f"source rows {src_n} -> committed {len(out)}")
    for name, t in themes.items():
        print(f"  {name}: {len(t['rows'])}/{t['cap']}")
    print("eras:", Counter(r["era"] for r in out))
    print("cols:", cols)
    print("cobalt rows:", sum(1 for r in out if "cobalt" in (r["Medium"] + r["Title"]).lower()))
    print("dutch rows:", sum(1 for r in out
                             if "dutch" in (r["Culture"] + r["Artist Display Name"]).lower()
                             or any(d in r["Artist Display Name"].lower() for d in DUTCH_MASTERS)
                             or "delft" in (r["Culture"] + r["Title"] + r["Medium"]).lower()))
    print("lead rows:", sum(1 for r in out if "lead" in r["Medium"].lower()))
    print("china rows:", sum(1 for r in out if "china" in r["Culture"].lower()))


main()
