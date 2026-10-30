"""Create the short report, a self-contained HTML view, plots, and CSV exports."""
import base64
import csv
import html
import io
import json
import platform
import resource
from pathlib import Path

from common import ROOT, RESULTS, sha256, write_json
from interpolation_report import render_interpolation_follow_up

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


def median(values):
    return float(np.median(values)) if values else None


def summary(group):
    dense = [s for s in group if "raw" in s]
    scored = [s for s in dense if s.get("raw_rmse_percent_of_range") is not None]
    return {"locations": len(group), "continuous_locations": len(dense), "scored_continuous_locations": len(scored),
            "continuous_samples": sum(s.get("samples", 0) for s in group),
            "raw_within_10_percent": sum(s["raw_rmse_percent_of_range"] <= 10 for s in scored),
            "centered_within_10_percent": sum(s["centered_rmse_percent_of_range"] <= 10 for s in scored),
            "median_raw_rmse_cm": median([100 * s["raw"]["rmse_m"] for s in dense]),
            "median_raw_rmse_percent_of_range": median([s["raw_rmse_percent_of_range"] for s in scored]),
            "median_centered_rmse_percent_of_range": median([s["centered_rmse_percent_of_range"] for s in scored]),
            "median_high_timing_absolute_minutes": median([s["events"]["H"]["median_absolute_minutes"] for s in group if s.get("events", {}).get("H", {}).get("count")]),
            "median_low_timing_absolute_minutes": median([s["events"]["L"]["median_absolute_minutes"] for s in group if s.get("events", {}).get("L", {}).get("count")])}


def plots(stations):
    sample_by_id = {}
    wanted = ["9414290", "1612340", "SVG", "KAZ", "8443970", "9447130"]
    with (RESULTS / "samples.csv").open() as file:
        for row in csv.DictReader(file):
            if row["id"] in wanted and row["time_utc"].startswith(("2026-01-01", "2026-01-02")):
                sample_by_id.setdefault(row["id"], []).append(row)
    figures = []
    for sid in wanted:
        rows = sample_by_id.get(sid)
        if not rows:
            continue
        dates = np.array([row["time_utc"] for row in rows], dtype="datetime64[s]")
        ref, model = np.array([float(r["official_m"]) for r in rows]), np.array([float(r["model_m"]) for r in rows])
        fig, axes = plt.subplots(2, 1, figsize=(8.2, 3.6), sharex=True, gridspec_kw={"height_ratios": [3, 1]}, layout="constrained")
        axes[0].plot(dates, ref, label="Official prediction", lw=1.4)
        axes[0].plot(dates, model, label="EOT20 coastal package", lw=1.1)
        role = " — DIAGNOSTIC: location match unverified" if rows[0]["comparison_role"] == "diagnostic_only" else ""
        axes[0].set_title(rows[0]["name"] + role, fontsize=10)
        axes[0].set_ylabel("m, respective MSL")
        axes[0].legend(loc="upper right", fontsize=8)
        axes[1].plot(dates, model - ref, color="#b34830", lw=1)
        axes[1].axhline(0, color="#777", lw=.5)
        axes[1].set_ylabel("Error, m")
        axes[1].set_xlabel("UTC, 1–2 January 2026")
        for ax in axes:
            ax.grid(alpha=.2)
        destination = RESULTS / f"comparison_{sid}.png"
        fig.savefig(destination, dpi=130)
        plt.close(fig)
        figures.append((rows[0]["name"], destination))
    # Event-only references: plot times by type; no fitted datum or fake curve.
    linz = [s for s in stations if s["provider"] == "LINZ"]
    if linz:
        fig, axes = plt.subplots(len(linz), 1, figsize=(8.2, 1.4 * len(linz)), layout="constrained")
        for ax, station in zip(np.atleast_1d(axes), linz):
            result = station if "events" in station else station.get("diagnostic_only", {})
            matches = [m for m in result.get("event_details", []) if m["reference"]["time"].startswith(("2026-01-01", "2026-01-02"))]
            for key, offset, label in [("reference", .14, "LINZ"), ("model", -.14, "EOT20")]:
                for kind, value in [("H", 1), ("L", 0)]:
                    events = [m[key] for m in matches if m[key]["kind"] == kind]
                    ax.scatter(np.array([e["time"] for e in events], dtype="datetime64[s]"), [value + offset] * len(events), s=18,
                               label=label if kind == "H" else None, marker="o" if key == "reference" else "x")
            ax.set_yticks([0, 1], ["Low", "High"])
            ax.set_title(station["name"] + (" (diagnostic only)" if station["support"]["status"] != "supported" else ""), fontsize=9)
            ax.grid(alpha=.2)
        np.atleast_1d(axes)[0].legend(fontsize=8, loc="upper right")
        np.atleast_1d(axes)[-1].set_xlabel("UTC, 1–2 January 2026; event times only")
        destination = RESULTS / "linz_event_times.png"
        fig.savefig(destination, dpi=130)
        plt.close(fig)
        figures.append(("New Zealand event timing", destination))
    return figures


def main():
    benchmark = json.loads((RESULTS / "benchmark.json").read_text())
    stations = benchmark["locations"]
    primary = [s for s in stations if s["support"]["status"] == "supported"]
    diagnostic = [s["diagnostic_only"] for s in stations if "diagnostic_only" in s and ("raw" in s["diagnostic_only"] or "events" in s["diagnostic_only"])]
    p, d, combined = summary(primary), summary(diagnostic), summary(primary + diagnostic)
    compression = json.loads((RESULTS / "compression.json").read_text())
    packs = [r for r in compression if r["representation"] == "native_ap64" and r["encoding"] == "xor_shuffle"]
    calculation = json.loads((RESULTS / "calculation_validation.json").read_text())
    caspian = json.loads((RESULTS / "caspian_coverage.json").read_text())
    aggregate = {"attempted_locations": len(stations), "supported": p, "diagnostic_only": d, "all_quantitative_comparisons": combined,
                 "providers": {provider: summary([s for s in primary if s["provider"] == provider]) for provider in ["NOAA", "Kartverket", "LINZ"]},
                 "caspian_common_valid_cells": caspian["common_valid_wet_cells_in_36_48N_46_55E"], "packages": packs}
    interpolation_text, interpolation_result = render_interpolation_follow_up()
    if interpolation_result is not None:
        aggregate["weighted_interpolation_follow_up"] = interpolation_result
    write_json(RESULTS / "summary.json", aggregate)
    worst = sorted([s for s in primary if s.get("raw_rmse_percent_of_range") is not None], key=lambda s: -s["raw_rmse_percent_of_range"])[:5]
    worst_text = "; ".join(f"{s['name']} {s['raw_rmse_percent_of_range']:.0f}%" for s in worst)
    linz = [s for s in primary if s["provider"] == "LINZ"]
    linz_ranges = [s["events"].get("median_absolute_range_error_percent") for s in linz if s["events"].get("median_absolute_range_error_percent") is not None]
    pack_table = "\n".join(f"| {r['band_extra_cells']} | {r['retained_cells']:,} | {r['compressed_package_bytes'] / 1e6:.2f} MB |" for r in packs)
    text = f"""# EOT20 tide-widget feasibility test — 30 October 2026

**Verdict:** the dataset is practical to package for offline use, and many tested sea locations meet the proposed 10% tolerance. It does **not** establish reliable 10% accuracy or automatic coverage at every harbour worldwide. Local coverage and location selection need more work before building the widget.

## Prediction quality

Attempted **{len(stations)} official reference locations**: 67 NOAA, 33 Kartverket and eight LINZ ports. We obtained quantitative comparisons at **{combined['locations']}** locations: {combined['continuous_locations']} continuous-series sites plus eight event-table sites. The remaining {len(stations) - combined['locations']} had no usable nearby model match under the 20 km limit. The sample includes the Americas, Norway and Pacific/Indian Ocean islands, but is not a representative survey of every coastline.

We tested 1–2 January, April, July and October 2026, matching {combined['continuous_samples']:,} official astronomical heights at their original six- or ten-minute timestamps. These are predictions, not weather-affected observations. LINZ high/low tables were compared as events, never turned into a synthetic reference curve.

“10%” means **RMS height error divided by the mean official 48-hour high-to-low range**. This avoids division by tide heights near zero; it is an average-error criterion, not a guarantee that every point is within 10%.

| Comparison | Continuous sites | Within 10% | Median RMS error / range |
|---|---:|---:|---:|
| Accepted coastal matches | {p['scored_continuous_locations']} | {p['raw_within_10_percent']} | {p['median_raw_rmse_percent_of_range']:.1f}% |
| Nearest-cell diagnostics; location match unverified | {d['scored_continuous_locations']} | {d['raw_within_10_percent']} | {d['median_raw_rmse_percent_of_range']:.1f}% |

For accepted matches, median RMS height error was **{p['median_raw_rmse_cm']:.1f} cm**. Poor locations remain visible: {worst_text}. Median absolute high/low timing errors across accepted sites were approximately **{p['median_high_timing_absolute_minutes']:.0f}/{p['median_low_timing_absolute_minutes']:.0f} minutes**, with larger errors and unmatched events at some sites.

Only **{len(primary)}/{len(stations)} locations passed the conservative automatic location check**: nearby wet support with a clear mapped-water path, a maximum 20 km cell distance and at most 1 km shoreline snapping. {len(diagnostic)} others have explicitly separate nearest-cell diagnostics. This check is stricter than ordinary nearest-cell lookup and is partly limited by the coarse shoreline; rejected matches do not necessarily mean EOT20 has no nearby tide data. Diagnostics are excluded from the accepted-match score.

Both continuous providers were requested relative to local MSL; a shared physical zero with EOT20 is not established. No offset, scale or timing adjustment was applied. The separately labelled mean-centred check gives {p['median_centered_rmse_percent_of_range']:.1f}% median error and {p['centered_within_10_percent']}/{p['scored_continuous_locations']} within 10%. LINZ's four accepted ports had {median(linz_ranges):.1f}% median site-level range error; heights are rounded to 0.1 m. Good range agreement can coexist with poor timing: Bluff's median high/low timing errors were 27/38 minutes.

One NOAA coordinate discrepancy was corrected from its own [published tide table, page 403](https://tidesandcurrents.noaa.gov/tidetables/2016/pttt2016book.pdf#page=413): Jakarta's API coordinate was about 433 km from the named port. The original result is preserved; the corrected location remains a diagnostic, not accepted coverage.

## Storage and lossless compression

Downloaded the official **2.33 GB EOT20 final archive** and used all 17 ocean-height constituents. Retained original 64-bit amplitude/phase values, coastal islands and supported bays, excluding inland lakes; the Caspian was explicitly allowed. Coastal cells plus extra wet-cell bands produced:

| Extra cells beyond coastal seed | Retained wet cells | Lossless package |
|---|---:|---:|
{pack_table}

Sizes are decimal MB. Independent 5° tiles use spatial bitwise XOR, byte shuffling and Zstandard. All retained coefficient bits round-trip exactly; **compression introduces zero prediction error**. Most size reduction comes from coastal subsetting, not compression alone. The four-cell package contains 85.66 MB of uncompressed coefficients. Bands retain the original 0.125° model resolution; they cannot add missing fjords or resolve small harbours.

All three packages retained the same coefficients at all 103 compared model matches; the 24 MB option was sufficient for this sample. That does not establish its completeness for every coastline.

## Caspian and limits

**EOT20 supplies no valid tide coefficients in the Caspian Sea** in this extract, including the Baku/Aktau/Anzali checks. Including it in the selection cannot create a forecast there; a different data source would be needed. Natal was attempted, but the Brazilian Navy download-discovery pages returned HTTP 403, so no Natal accuracy claim is made.

This is a Python/pyTMD feasibility experiment, with short-period minor inference and FES astronomical corrections. It has **not** tested a Kotlin engine or Android widget. Our conversion agrees with pyTMD’s own reader/predictor to {calculation['maximum_height_difference_m']:.1e} m over {calculation['height_comparisons']:,} checks; that validates integration, not coastal model accuracy. Six offline checks passed. Raw responses, hashes, packages, per-site metrics, event matches and every compared height are saved for follow-up. Four short seasonal windows are preliminary; neither worldwide coastline completeness nor year-round accuracy has been demonstrated.

Sources: [EOT20 dataset](https://doi.org/10.17882/79489), [NOAA API](https://api.tidesandcurrents.noaa.gov/api/prod/), [Kartverket API](https://vannstand.kartverket.no/tideapi_en.html), [LINZ predictions](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions), [Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/).
"""
    if interpolation_text:
        text = text.replace("## Storage and lossless compression", interpolation_text + "\n## Storage and lossless compression")
    (RESULTS / "REPORT.md").write_text(text)
    # Keep diagnostic scores convenient to inspect without mixing them into the
    # primary coverage/accuracy CSV or requiring a nested JSON parser.
    with (RESULTS / "diagnostic_locations.csv").open("w", newline="") as file:
        fields = ["provider", "id", "name", "reason_primary_rejected", "distance_km", "samples", "rmse_m", "rmse_percent_of_range", "high_median_absolute_minutes", "low_median_absolute_minutes"]
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for original in stations:
            s = original.get("diagnostic_only")
            if s and ("raw" in s or "events" in s):
                writer.writerow({"provider": s["provider"], "id": s["id"], "name": s["name"], "reason_primary_rejected": original["support"]["reason"],
                                 "distance_km": s["support"]["maximum_model_distance_km"], "samples": s.get("samples", ""),
                                 "rmse_m": s.get("raw", {}).get("rmse_m", ""), "rmse_percent_of_range": s.get("raw_rmse_percent_of_range", ""),
                                 "high_median_absolute_minutes": s.get("events", {}).get("H", {}).get("median_absolute_minutes", ""),
                                 "low_median_absolute_minutes": s.get("events", {}).get("L", {}).get("median_absolute_minutes", "")})
    figures = plots(stations)
    # A tiny Markdown renderer keeps the HTML self-contained without another
    # dependency. Tables and links are rendered explicitly for this report.
    import re
    def inline(value):
        value = html.escape(value)
        value = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", value)
        return re.sub(r"\[([^]]+)\]\((https?://[^)]+)\)", r'<a href="\2">\1</a>', value)
    sections, table_open = [], False
    for line in text.splitlines():
        if line.startswith("|"):
            if not table_open:
                sections.append("<table>")
                table_open = True
            if re.match(r"^\|[-:| ]+\|$", line):
                continue
            sections.append("<tr>" + "".join("<td>" + inline(c.strip()) + "</td>" for c in line.strip("|").split("|")) + "</tr>")
            continue
        if table_open:
            sections.append("</table>")
            table_open = False
        if line.startswith("# "):
            sections.append("<h1>" + inline(line[2:]) + "</h1>")
        elif line.startswith("## "):
            sections.append("<h2>" + inline(line[3:]) + "</h2>")
        elif line.startswith("### "):
            sections.append("<h3>" + inline(line[4:]) + "</h3>")
        elif line:
            sections.append("<p>" + inline(line) + "</p>")
    sections.append("<details><summary>Plots: official predictions, model and residuals</summary>")
    for name, path in figures:
        encoded = base64.b64encode(path.read_bytes()).decode()
        sections.append(f'<figure><img alt="{html.escape(name)}" src="data:image/png;base64,{encoded}"></figure>')
    sections.append("</details><details><summary>All attempted locations</summary><table><tr><th>Provider</th><th>Location</th><th>Automatic match</th><th>Raw RMS / range</th><th>Note</th></tr>")
    for station in stations:
        result = station if station["support"]["status"] == "supported" else station.get("diagnostic_only", {})
        value = result.get("raw_rmse_percent_of_range")
        metric = f"{value:.1f}%" if value is not None else "Events only" if result.get("events") else "Unavailable"
        note = station["support"].get("reason", station["support"].get("method", ""))
        if "diagnostic_only" in station:
            note += "; score shown is diagnostic only"
        sections.append("<tr>" + "".join("<td>" + html.escape(str(v)) + "</td>" for v in [station["provider"], station["name"], station["support"]["status"], metric, note]) + "</tr>")
    sections.append("</table></details>")
    document = "<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>EOT20 feasibility report</title><style>body{max-width:1000px;margin:3em auto;padding:0 1em;font:16px/1.6 system-ui;color:#183144}h1{font-size:1.8em}h2{font-size:1.3em;margin-top:2em}table{border-collapse:collapse;width:100%;font-size:.9em}td,th{border-bottom:1px solid #cad6df;padding:.5em;text-align:left}tr:first-child{font-weight:bold}a{color:#006a9c}details{margin:2em 0}summary{cursor:pointer;font-weight:bold}img{width:100%;height:auto}figure{margin:2em 0}</style><body>" + "\n".join(sections) + "</body></html>"
    (RESULTS / "REPORT.html").write_text(document)
    provenance = {"python": platform.python_version(), "platform": platform.platform(), "dependencies": (ROOT / "requirements.lock.txt").read_text(),
                  "code_sha256": {str(p.relative_to(ROOT)): sha256(p.read_bytes()) for p in sorted((ROOT / "tools").iterdir()) if p.suffix in (".py", ".json")},
                  "package_sha256": {str(p.relative_to(ROOT)): sha256(p.read_bytes()) for p in sorted((ROOT / "data/packs").glob("*.eotz"))},
                  "reference_cache_metadata_files": len(list((ROOT / "data/raw/http").glob("*.meta.json")))}
    write_json(RESULTS / "provenance.json", provenance)
    print(json.dumps(aggregate, indent=2))
    print(f"Short report: {len(text.split())} words; HTML includes {len(figures)} figures")


if __name__ == "__main__":
    main()
