#!/usr/bin/env python3
"""Two OWID extracts: the leaded-petrol phase-out, and voter turnout.

Both keep OWID's own header `World region according to OWID` verbatim — it is a
real header nobody sanitised, and normalising it is `col-kw`'s job, not ours.
"""
import csv
from collections import defaultdict

REGION_HDR = "World region according to OWID"


def regions():
    m = {}
    for r in csv.DictReader(open("continents-according-to-our-world-in-data.csv")):
        m[r["Entity"]] = r[REGION_HDR]
    return m


def lead_phaseout():
    reg = regions()
    first_yes, code = {}, {}
    src_n = 0
    for r in csv.DictReader(open("lead-petrol-ban.csv")):
        src_n += 1
        ent, yr = r["Entity"], int(r["Year"])
        code[ent] = r["Code"]
        val = r["Which countries have banned the use of leaded petrol in road vehicles?"]
        if val.strip().lower() == "yes":
            if ent not in first_yes or yr < first_yes[ent]:
                first_yes[ent] = yr
    out = []
    for ent in sorted(first_yes):
        if ent not in reg:            # aggregates and territories with no OWID region
            continue
        out.append({"Entity": ent, "Code": code[ent], REGION_HDR: reg[ent],
                    "ban_year": first_yes[ent]})
    with open("lead-phaseout.csv", "w", newline="") as f:
        w = csv.DictWriter(f, ["Entity", "Code", REGION_HDR, "ban_year"])
        w.writeheader(); w.writerows(out)
    print(f"lead-phaseout: source rows {src_n} -> committed {len(out)}")
    print("  regions:", sorted(set(r[REGION_HDR] for r in out)))
    print("  netherlands:", [r for r in out if r["Entity"] == "Netherlands"])
    print("  united states:", [r for r in out if r["Entity"] == "United States"])
    print("  ban_year range:", min(r["ban_year"] for r in out), max(r["ban_year"] for r in out))


def turnout():
    reg = regions()
    rows = list(csv.DictReader(open("voter-turnout-of-registered-voters.csv")))
    src_n = len(rows)
    COL = "Voter turnout of registered voters"
    by_ent = defaultdict(list)
    for r in rows:
        if not r[COL].strip():
            continue
        ent = r["Entity"]
        if ent not in reg:            # drops World/region aggregates and imputed groups
            continue
        by_ent[ent].append((int(r["Year"]), float(r[COL]), r["Code"]))
    out = []
    for ent in sorted(by_ent):
        obs = sorted(by_ent[ent])
        recent = [o for o in obs if o[0] >= 2000]
        if not recent:
            continue
        yr, pct, code = recent[-1]
        out.append({"Entity": ent, "Code": code, REGION_HDR: reg[ent],
                    "year": yr, "turnout_pct": round(pct, 2),
                    "elections_since_2000": len(recent)})
    with open("elections-turnout.csv", "w", newline="") as f:
        w = csv.DictWriter(f, ["Entity", "Code", REGION_HDR, "year",
                               "turnout_pct", "elections_since_2000"])
        w.writeheader(); w.writerows(out)
    print(f"elections-turnout: source rows {src_n} -> committed {len(out)}")
    print("  regions:", sorted(set(r[REGION_HDR] for r in out)))
    print("  netherlands:", [r for r in out if r["Entity"] == "Netherlands"])
    print("  united states:", [r for r in out if r["Entity"] == "United States"])
    print("  years:", min(r["year"] for r in out), max(r["year"] for r in out))
    print("  elections_since_2000 range:", min(r["elections_since_2000"] for r in out),
          max(r["elections_since_2000"] for r in out))


lead_phaseout()
turnout()
