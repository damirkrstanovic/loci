#!/usr/bin/env python3
"""Eurostat irt_st_m -> the one genuinely monthly table in the corpus.

`month` is the only column here renamed for loci's sake: `mold/viewers-for`
keys its Over-time and Headline viewers on a column literally named :month.
"""
import csv

NAMES = {"CZ": "Czechia", "DK": "Denmark", "EA": "Euro area", "HU": "Hungary",
         "JP": "Japan", "PL": "Poland", "RO": "Romania", "SE": "Sweden",
         "UK": "United Kingdom", "US": "United States"}

rows = list(csv.DictReader(open("euro-st.csv")))
src_n = len(rows)
out = []
for r in rows:
    if r["int_rt"] != "IRT_M3":
        continue
    if r["geo"] not in NAMES:
        continue
    p = r["TIME_PERIOD"]
    if not ("2021-01" <= p <= "2024-12"):
        continue
    v = r["OBS_VALUE"].strip()
    if not v:
        continue
    out.append({"month": p, "country": NAMES[r["geo"]], "rate_pct": round(float(v), 3)})

out.sort(key=lambda r: (r["month"], r["country"]))
with open("interest-rates-monthly.csv", "w", newline="") as f:
    w = csv.DictWriter(f, ["month", "country", "rate_pct"])
    w.writeheader(); w.writerows(out)

print(f"source rows {src_n} -> committed {len(out)}")
print("countries:", sorted(set(r["country"] for r in out)))
print("months:", min(r['month'] for r in out), "->", max(r['month'] for r in out),
      len(set(r["month"] for r in out)))
print("us sample:", [r for r in out if r["country"] == "United States"][:2],
      [r for r in out if r["country"] == "United States"][-2:])
