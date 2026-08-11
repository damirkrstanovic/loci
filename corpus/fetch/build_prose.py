#!/usr/bin/env python3
"""Build corpus/prose/*.md — short QUOTED extracts, each with its citation.

Nothing here is written by the importer. Every body is a span lifted verbatim
from a source that clears the licence bar (CC0, US public domain, or CC BY), and
the span is located by anchor strings rather than retyped, so a transcription
error is not possible.

Whitespace is the only thing normalised: runs of spaces collapse to one,
end-of-line hyphenation is joined, and hard-wrapped lines become paragraphs.
OCR errors in the 1890 scan are LEFT AS SCANNED and flagged in the citation —
silently correcting a source is how a quotation stops being one.
"""
import json, os, re, sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..", "..")
OUT = os.environ.get("CORPUS_DIR", "/home/damirk/src/loci/corpus")
PROSE = os.path.join(OUT, "prose")


def prep(s):
    """Hard-wrapped, hyphen-split source text -> one flat string of verbatim words.

    Anchors below are plain sentences, so the whole document is flattened FIRST
    and spans are cut from the flat text. Nothing but whitespace changes."""
    s = re.sub(r"-\n\s*", "", s)        # join end-of-line hyphenation
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def flatten(s):
    return re.sub(r"\s+", " ", s).strip()


def span(text, start_anchor, end_anchor, include_end=True, after=""):
    """Cut [start_anchor .. end_anchor] out of text.

    `after` exists because MCS repeats boilerplate — the critical-minerals-list
    sentence appears in EVERY commodity chapter, so an unqualified search finds
    the first chapter's copy and the span runs to the end of the report. Anchor
    on something unique to the chapter first."""
    base = text.find(after) if after else 0
    if base < 0:
        sys.exit(f"chapter anchor not found: {after[:60]!r}")
    text_ = text
    i = text_.find(start_anchor, base)
    if i < 0:
        sys.exit(f"anchor not found: {start_anchor[:60]!r}")
    j = text_.find(end_anchor, i)
    if j < 0:
        sys.exit(f"end anchor not found: {end_anchor[:60]!r}")
    return text_[i: j + (len(end_anchor) if include_end else 0)]


def write(name, title, lead, body, citation, licence, established, entangles):
    os.makedirs(PROSE, exist_ok=True)
    quoted = "\n>\n".join("> " + l for l in body.split("\n\n"))
    md = f"""# {title}

## {lead}

{quoted}

## Source and licence

{citation}

**Licence: {licence}.** {established}

*Entities this touches: {", ".join(entangles)}.*
"""
    p = os.path.join(PROSE, name)
    open(p, "w").write(md)
    print(f"  {name}  {len(md)} bytes")


# ---------------------------------------------------------------- Church 1890
church = open("church.txt", encoding="utf-8", errors="replace").read()
church = prep(church)

CHURCH_CITE = ("A. H. Church, *The Chemistry of Paints and Painting* (London: Seeley and Co., "
               "1890), chapter on the blue and white pigments. Scanned by the Getty Research "
               "Institute; text taken from the Internet Archive OCR, item `gri_33125003356652`, "
               "https://archive.org/details/gri_33125003356652 — retrieved 2026-08-11.")
CHURCH_LIC = ("Published 1890; the author died in 1915. A faithful reproduction of a "
              "public-domain two-dimensional work creates no new copyright in the United States, "
              "so the Getty scan does not restrict it. OCR errors in proper names are left as "
              "scanned rather than silently corrected.")

write("cobalt-blue-the-pigment.md",
      "Cobalt blue, as a chemist to the Royal Academy described it in 1890",
      "Quoted from A. H. Church, The Chemistry of Paints and Painting",
      flatten(span(church,
                   "Excluding smalt, which owes its colour to a cobalt silicate",
                   "cobalt arseniate may replace the phosphate.")),
      CHURCH_CITE, "US public domain", CHURCH_LIC,
      ["cobalt", "cobalt blue", "pigment"])

write("white-lead-the-painters-white.md",
      "White lead: the painter's white, and its poisonous character",
      "Quoted from A. H. Church, The Chemistry of Paints and Painting",
      flatten(span(church,
                   "The drawbacks attendant upon the use of white lead as a paint",
                   "its own strongly siccative character."))
      + "\n\n"
      + flatten(span(church,
                     "For when an old oil-picture is carefully examined",
                     "but not the high lights")) + ".",
      CHURCH_CITE, "US public domain", CHURCH_LIC,
      ["lead", "lead white", "white lead", "pigment"])

# ------------------------------------------------------------- USGS MCS 2026
mcs = prep(open("mcs2026.txt", encoding="utf-8", errors="replace").read())

USGS_CITE_BASE = ("U.S. Geological Survey, *Mineral Commodity Summaries 2026*, "
                  "https://doi.org/10.3133/mcs2026 — retrieved 2026-08-11, chapter ")
USGS_LIC = ("The Mineral Commodity Summaries 2026 data release carries a machine-readable rights "
            "field on ScienceBase — \"This work is marked with the Creative Commons Zero v1.0 "
            "Universal (CC0-1.0) public domain dedication\" — and the report itself is a work of "
            "the United States Government, which is in the public domain under 17 U.S.C. 105.")

write("cobalt-the-congo-export-ban.md",
      "Cobalt in 2025: Congo bans exports, then meters them by quota",
      "Quoted from the USGS Mineral Commodity Summaries 2026, COBALT",
      flatten(span(mcs,
                   "Congo (Kinshasa) was the world’s leading source of mined cobalt",
                   "The solicitation was cancelled before the end of the year."))
      + "\n\n"
      + flatten(span(mcs,
                     "Depending on the application, substitution for cobalt could result",
                     "cerium, iron, lead, manganese, or vanadium in paints; "
                     "cobalt-iron-copper or iron-copper in diamond tools")) + " …",
      USGS_CITE_BASE + "COBALT.", "US public domain (CC0-1.0)", USGS_LIC,
      ["cobalt", "democratic republic of congo", "china", "united states", "lead"])

write("gallium-and-the-export-controls.md",
      "Gallium: no US primary production since 1987, and China holds 99%",
      "Quoted from the USGS Mineral Commodity Summaries 2026, GALLIUM",
      flatten(span(mcs,
                   "No domestic primary (low-purity, unrefined) gallium has been recovered since 1987.",
                   "Uses of ICs included defense applications, high-performance computers, and telecommunications equipment."))
      + "\n\n"
      + flatten(span(mcs,
                     "In November 2025, China lifted its ban on gallium exports",
                     "China’s ban of all gallium exports to the United States in December 2024."))
      + "\n\n"
      + flatten(span(mcs,
                     "China accounted for 99% of worldwide primary low-purity gallium production.",
                     "Ukraine most likely ceased primary production in 2022.")),
      USGS_CITE_BASE + "GALLIUM.", "US public domain (CC0-1.0)", USGS_LIC,
      ["gallium", "china", "united states", "semiconductors", "japan"])

write("rare-earths-export-controls.md",
      "Rare earths in 2025: two rounds of Chinese export controls",
      "Quoted from the USGS Mineral Commodity Summaries 2026, RARE EARTHS",
      flatten(span(mcs,
                   "In April 2025, China tightened its export controls on rare-earth elements",
                   "although China began to issue general export licenses to selected exporters.")),
      USGS_CITE_BASE + "RARE EARTHS.", "US public domain (CC0-1.0)", USGS_LIC,
      ["rare earths", "china", "united states"])

write("lead-becomes-a-critical-mineral.md",
      "Lead joins the United States critical minerals list, 2025",
      "Quoted from the USGS Mineral Commodity Summaries 2026, LEAD",
      flatten(span(mcs,
                   "On November 7, 2025, the U.S. Final 2025 List of Critical Minerals was published",
                   "phosphate rock, and uranium were also added.",
                   after="During the first 10 months of 2025, the average North American price for lead")),
      USGS_CITE_BASE + "LEAD.", "US public domain (CC0-1.0)", USGS_LIC,
      ["lead", "united states", "copper", "silicon"])

# ------------------------------------------- Cleveland Museum of Art (CC0)
cma = json.load(open("cma_picked.json"))
CMA_LIC = ("The Cleveland Museum of Art's open-access statement, read 2026-08-11 at "
           "https://www.clevelandart.org/open-access-api: \"To the extent possible under law, the "
           "Cleveland Museum of Art has waived all copyright and related or neighboring rights to "
           "this dataset using Creative Commons Zero.\" Each record additionally carries "
           "`share_license_status: CC0` in the API response.")


def cma_write(key, name, title, lead, entangles):
    hits = [v for v in cma.values() if key.lower() in (v.get("title") or "").lower()]
    if len(hits) != 1:
        sys.exit(f"cma lookup {key!r} matched {len(hits)} records")
    a = hits[0]
    body = flatten(re.sub(r"<[^>]+>", " ", a["description"]))
    makers = "; ".join(c["description"] for c in (a.get("creators") or [])) or "maker unrecorded"
    cite = (f"Cleveland Museum of Art, *{a['title']}*, {a.get('creation_date')}. "
            f"{makers}. {'; '.join(a.get('culture') or [])}. "
            f"Accession {a['accession_number']}, {a['url']} — retrieved 2026-08-11. "
            f"The text quoted is the museum's own catalogue description.")
    write(name, title, lead, body, cite, "CC0 1.0", CMA_LIC, entangles)


cma_write("Lobed vase with underglaze cobalt blue decoration",
          "cobalt-blue-kangxi-porcelain.md",
          "Cobalt blue at its height: Jingdezhen under the Kangxi emperor",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["cobalt", "cobalt blue", "china", "porcelain"])

cma_write("Jar with Bird and Flower Decoration",
          "cobalt-blue-banned-in-joseon-korea.md",
          "When cobalt was a luxury import a court could ban",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["cobalt", "cobalt blue", "korea", "porcelain"])

cma_write("Wineglass and a Bowl of Fruit",
          "dutch-trade-in-a-still-life.md",
          "A Chinese porcelain bowl as evidence of the Dutch trading empire",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["netherlands", "china", "porcelain", "willem kalf"])

cma_write("Portrait of a Family Playing Music",
          "chinese-vases-in-a-dutch-parlour.md",
          "Chinese vases and Japanese boxes in a Dutch room, 1663",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["netherlands", "china", "japan", "pieter de hooch"])

cma_write("Base for a Flower Pyramid",
          "delft-flower-pyramid.md",
          "Delft at its most ambitious, and a queen who spread the fashion",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["netherlands", "delft", "ceramics"])

cma_write("Nautilus Cup",
          "delft-nautilus-cup.md",
          "A shell from Indonesia, mounted in silver in Delft",
          "Quoted from the Cleveland Museum of Art catalogue",
          ["netherlands", "delft", "indonesia"])

# ---------------------------------------------------------------- OWID (CC BY)
lead_ind = json.load(open("ind1208075.json"))
write("the-leaded-petrol-phase-out.md",
      "The leaded petrol phase-out, and how the dates were assembled",
      "Quoted from Our World in Data's own dataset description",
      flatten(lead_ind["description"]) + "\n\n" + flatten(lead_ind["origins"][0]["description"]),
      ("Our World in Data, *Leaded petrol bans in road vehicles*, indicator `lead_petrol_banned`, "
       "https://ourworldindata.org/grapher/lead-petrol-ban — metadata read at "
       "https://api.ourworldindata.org/v1/indicators/1208075.metadata.json on 2026-08-11. "
       "Cite as: Our World in Data (2021), Leaded petrol bans in road vehicles, based on multiple "
       "sources."),
      "CC BY 4.0",
      ("OWID's FAQ: \"Data produced by us falls under our permissive CC BY license; you have "
       "permission to use, reproduce, and distribute it, provided that you cite us.\" This dataset "
       "is OWID's own — its origin block records producer \"Various sources\" and citationFull "
       "\"Our World in Data (2021)\", and its description says it \"was built by Our World in Data "
       "based on published dates from multiple sources\"."),
      ["lead", "leaded petrol", "algeria", "united states"])

turnout = json.load(open("voter-turnout-of-registered-voters.meta.json"))
col = turnout["columns"]["Voter turnout of registered voters"]
write("voter-turnout-what-it-measures.md",
      "What \"voter turnout of registered voters\" actually measures",
      "Quoted from V-Dem via Our World in Data",
      flatten(col["descriptionShort"]) + "\n\n"
      + flatten(col["descriptionKey"].replace("- ", "").replace("**", "")),
      ("V-Dem, *Democracy report v16* (2026), indicator `v2eltrnout`, processed by Our World in "
       "Data, https://ourworldindata.org/grapher/voter-turnout-of-registered-voters — metadata read "
       "at https://api.ourworldindata.org/v1/indicators/1210039.metadata.json on 2026-08-11. "
       "Cite as: Coppedge, Michael, et al. 2026. \"V-Dem [Country-Year/Country-Date] Dataset v16\", "
       "Varieties of Democracy (V-Dem) Project, https://doi.org/10.23696/vdemds26"),
      "CC BY 4.0",
      ("Machine-readable: the indicator's origins block gives "
       "`license = {\"name\": \"CC BY 4.0\", \"url\": \"https://creativecommons.org/licenses/by/4.0/\"}` "
       "for producer V-Dem, version v16. OWID's processing on top of it is CC BY by its own FAQ."),
      ["voter turnout", "democracy", "united states", "netherlands"])

print("\nwrote", len(os.listdir(PROSE)), "prose files to", PROSE)
