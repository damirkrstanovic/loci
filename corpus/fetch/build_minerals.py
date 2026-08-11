#!/usr/bin/env python3
"""USGS Mineral Commodity Summaries 2026 -> a reduced world-production extract.

Fetch first (the ScienceBase item also carries the CC0 rights statement):

    curl -L -o MCS2026_Commodities_Data.csv \\
      'https://www.sciencebase.gov/catalog/file/get/696a75d5d4be0228872d3bf8?f=__disk__eb%2F4d%2Fd1%2Feb4dd129d4f08cfba010a8b11cbbd2d88bbefdcb'

then run this from the same directory. Writes mineral-production.csv.

TWO TRAPS IN THIS FILE, both of which silently produce a table that molds as
Rows and nothing else. See `decode` and `num` below.
"""
import csv, io, re, sys

SRC = "MCS2026_Commodities_Data.csv"
OUT = "mineral-production.csv"

# Nine commodities, hand-picked so the corpus crosses into art (cobalt blue,
# lead white), semiconductors (gallium, silicon, rare earths) and batteries.
KEEP = ["Cobalt", "Gallium", "Rare Earths", "Lithium", "Copper", "Nickel",
        "Silicon", "Lead", "Graphite (Natural)"]

# Country names as USGS writes them -> the plain name a reader would search for.
RENAME = {"Congo (Kinshasa)": "Democratic Republic of Congo",
          "Korea, Republic of": "South Korea",
          "Korea, North": "North Korea",
          "Burma": "Myanmar"}

TO_TONNES = {"metric tons": 1.0,
             "kilograms": 0.001,
             "thousand metric tons": 1000.0,
             "million metric tons": 1_000_000.0,
             "metric tons of contained lithium": 1.0}


def num(s):
    """TRAP 2 — the thousands separator.

    USGS writes production as `"226,000"`, with a comma, inside a quoted CSV
    field. loci's own `->val` (src/loci/tools.clj) only parses a cell as a
    number when it matches `-?\\d+` or `-?\\d+\\.\\d+`, so `"226,000"` arrives as
    a STRING. A column of strings is not in `numeric-cols`, so:

      · `numericish?` is false   -> no Summary, no Histogram
      · `categorical?` is false  -> no Pivot, no Bar
      · the table molds as Rows and Cards and nothing else

    and nothing anywhere reports that the numbers were not numbers. Strip the
    separator on the way out, so the committed file holds bare numerals.
    """
    s = (s or "").strip().replace(",", "")
    if not s or s in ("--", "NA", "W", "XX", "(3)"):
        return None
    m = re.match(r"^-?\d+(\.\d+)?$", s)
    return float(m.group(0)) if m else None


def decode(path):
    """TRAP 1 — the encoding.

    MCS2026_Commodities_Data.csv is cp1252, not UTF-8, despite the .csv. Its
    `Section` values contain an em dash ("Salient Statistics—United States")
    that becomes U+FFFD under a UTF-8 read, or raises on a strict one. Nothing
    here depends on that column, but a filter written against the mojibake
    silently matches nothing.
    """
    return open(path, "rb").read().decode("cp1252")


def main():
    rows = list(csv.DictReader(io.StringIO(decode(SRC))))
    src_n = len(rows)

    units = {}
    out = []
    for r in rows:
        if r["Commodity"] not in KEEP:
            continue
        if "World" not in r["Section"]:
            continue
        if r["Statistics"] != "Production":
            continue
        if r["Country"] in ("World total", "Other countries", ""):
            continue
        y = num(r["Year"])
        v = num(r["Value"])
        if y is None or v is None:
            continue
        unit = (r["Unit"] or "").strip().lower()
        units.setdefault(unit, 0)
        units[unit] += 1
        f = TO_TONNES.get(unit)
        if f is None:
            print("UNKNOWN UNIT", repr(unit), r["Commodity"], file=sys.stderr)
            continue
        # THE CAT-COL GUARD, in its negative form — see build_met.py for the
        # finding written out in full. `Is critical mineral 2025` is a real and
        # interesting USGS column, and it is deliberately NOT carried here: it
        # holds two values (Yes/No), `cat-col` takes the FEWEST distinct values
        # in 2..12, so it would have beaten `Commodity` (9) and the Pivot would
        # have grouped nine commodities into two buckets. A boolean flag always
        # beats a taxonomy. Carrying it would need the guard in build_met.py.
        out.append({"Commodity": r["Commodity"],
                    "Country": RENAME.get(r["Country"], r["Country"]),
                    "Unit": "metric tons",       # constant: 1 distinct, so cat-col ignores it
                    "year": int(y),
                    "production_tonnes": round(v * f, 3)})

    # one row per commodity/country/year, largest value wins when USGS lists a
    # mine and a refinery figure under the same key
    best = {}
    for r in out:
        k = (r["Commodity"], r["Country"], r["year"])
        if k not in best or r["production_tonnes"] > best[k]["production_tonnes"]:
            best[k] = r
    out = sorted(best.values(), key=lambda r: (r["Commodity"], r["Country"], r["year"]))

    with open(OUT, "w", newline="") as f:
        w = csv.DictWriter(f, ["Commodity", "Country", "Unit", "year", "production_tonnes"])
        w.writeheader()
        w.writerows(out)
    print(f"source rows {src_n} -> committed {len(out)}")
    print("units seen:", units)
    print("commodities:", sorted(set(r["Commodity"] for r in out)))
    print("countries:", len(set(r["Country"] for r in out)))
    print("years:", sorted(set(r["year"] for r in out)))
    print("cobalt/DRC:", [r for r in out if r["Commodity"] == "Cobalt"
                          and "Congo" in r["Country"]])
    print("gallium/China:", [r for r in out if r["Commodity"] == "Gallium"
                             and r["Country"] == "China"])


main()
