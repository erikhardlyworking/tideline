"""Render the paired interpolation follow-up without changing the original campaign."""
import json

from common import RESULTS, sha256


def render_interpolation_follow_up():
    path = RESULTS / "interpolation_comparison.json"
    if not path.exists():
        return "", None
    result = json.loads(path.read_text())
    accepted, diagnostic = result["paired_accepted"], result["paired_diagnostic"]
    good = result["baseline_quality_strata"]["accepted"]["within_10_percent"]
    poor = result["baseline_quality_strata"]["accepted"]["over_10_percent"]
    def row(label, group):
        return (f"| {label} | {group['paired_continuous_sites']} | "
                f"{group['baseline']['median_raw_rmse_percent_of_range']:.2f}% → {group['weighted']['median_raw_rmse_percent_of_range']:.2f}% | "
                f"{group['improved_rmse_over_1mm']} | {group['worsened_rmse_over_1mm']} |")
    table = "\n".join(["| Baseline group | Sites | Median RMS / range, before → weighted | Improved >1 mm RMS | Worsened >1 mm RMS |",
                       "|---|---:|---:|---:|---:|", row("Accepted match, already within 10%", good),
                       row("Accepted match, over 10%", poor), row("Unverified location, diagnostic only", diagnostic)])
    fragment = f"""## Weighted interpolation follow-up

Retested the same locations using up to four wet cells, weighted by inverse distance squared, averaging complex harmonic components. The existing bilinear site was retained. Land barriers, distance limits, dates and reference predictions were unchanged.

{table}

Weighting helps some already-good sites, mostly slightly; it is not a general accuracy improvement. One good site, Key West, worsened from 8.9% to 15.6%. None of the 14 poorer accepted sites entered the 10% target. Across all 48 accepted continuous sites, the pass count fell from 34 to 33. Only six of 52 accepted model matches have enclosing cells; most coastal averages are one-sided. Exact grid nodes would be preserved, but none of these gauges is an exact node match.

The 23.9 MB package still covers all accepted weighted matches. Covering every diagnostic's extra neighbours needs the 35.4 MB band. Full paired results are in `INTERPOLATION.md`, `interpolation_comparison.csv` and `interpolation_comparison.json`.
"""
    details = f"""# Does weighted interpolation improve the tide comparison?

**It helps some good matches as well as some poor ones, but usually by very little, and it can make a good match substantially worse.** This run does not show that weighting should replace nearest-cell fallback everywhere.

We reused all 108 attempted locations, the same 103 numerically comparable locations, the same 159,360 official heights and the same eight days. There are 48 accepted continuous-series sites, 47 sites with only unverified location matches, and eight event-table ports. Good means the original raw RMS error was within 10% of the local range; these groups were fixed using the original result, before scoring the new method. This is an exploratory paired comparison, not new independent validation data.

{table}

For the 34 already-good accepted sites, {good['improved_rmse']} improved, {good['worsened_rmse']} worsened and {good['unchanged_rmse']} were unchanged. Only {good['improved_rmse_over_1mm']} improved RMS by more than 1 mm; {good['worsened_rmse_over_1mm']} worsened by more than 1 mm. {good['weighted']['raw_within_10_percent']}/34 remained within 10%.

For the 14 poorer accepted sites, {poor['improved_rmse']} improved, {poor['worsened_rmse']} worsened and {poor['unchanged_rmse']} were unchanged. None reached the 10% target. The 47 diagnostic sites still have {diagnostic['weighted']['raw_within_10_percent']}/47 within 10%, unchanged from the baseline; none gains accepted geographical coverage through averaging.

Across the 48 accepted continuous sites, median RMS/range changed from {accepted['baseline']['median_raw_rmse_percent_of_range']:.2f}% to {accepted['weighted']['median_raw_rmse_percent_of_range']:.2f}%, but median RMS in centimetres changed from {accepted['baseline']['median_raw_rmse_cm']:.2f} to {accepted['weighted']['median_raw_rmse_cm']:.2f} cm and the number within 10% fell from 34 to 33. These are different site medians; the tiny percentage improvement is not a universal improvement in centimetres.

## Concrete examples

- Key West: RMS worsened from 6.01 to 10.52 cm (8.94% → 15.64% of range). Two model cells about 9.2 and 10.3 km away receive weights 0.555 and 0.445. Both paths are mapped water, but the location is not enclosed by those cells.
- Udamat Bay: RMS improved from 18.30 to 15.84 cm (9.68% → 8.38%), using two cells about 5.6 and 7.9 km away.
- For event-only LINZ comparisons, Bluff's median high-to-low range error improved from 4.11% to 3.31%. Wellington's unverified diagnostic worsened from 4.73% to 27.12%; it remains excluded from accepted coverage. Ranges are not continuous-series RMS scores.

## What was interpolated

For each of the 17 constituents we average its real and imaginary coefficients with positive weights proportional to `1/distance²`, normalised to sum to one. We use at most four wet cells within 20 km, filtering land-crossing paths before selecting/weighting them. The existing four-cell bilinear interpolation is retained where valid. One available cell remains a one-cell estimate. No missing coefficient is replaced by zero and no wrapped phase angle is averaged directly. Settings were fixed before scoring; the power, radius and cell count were not tuned to these references.

Geographical acceptance is separate from numerical agreement. The original 52 accepted sites and 51 diagnostic sites remain in their original groups; five sites without any match remain unavailable. Only {result['positions_enclosed']['accepted']} accepted estimates have cells that enclose the requested location, including the original bilinear site. The others use a coastal average with cells on one side of the location; this is not interpolation between fully surrounding cells. There were {result['exact_grid_node_matches']} exact grid-node matches in this real-location sample. An exact node is preserved without averaging, as verified by the offline tests.

All chosen weighted coefficients were checked against the full-grid baseline. Lossless decoding still gives identical coefficients and zero compression error. The 23.87 MB package covers all 52 accepted weighted supports; Vardø's diagnostic needs an extra neighbour absent from that band, so the 35.38 MB package covers all 103 weighted supports. No data package was enlarged or recompressed for this experiment.

`interpolation_comparison.json` saves all cells, distances, weights, geometry flags, per-window metrics and event matches. `interpolation_comparison.csv` gives a flat paired comparison. `interpolation_samples.csv` contains all weighted heights and residuals. The original benchmark and samples are preserved. Five additional offline tests cover weights, exact nodes, phase wrapping, one-sided geometry and a nearer cell across a land barrier; the real-data audit independently recomputes saved metrics and checks every band's coefficients.
"""
    coverage_path = RESULTS / "coverage_interpolation.json"
    coverage_summary = None
    if coverage_path.exists():
        coverage = json.loads(coverage_path.read_text())
        if coverage["input_sha256"]["interpolation_comparison.json"] != sha256(path.read_bytes()):
            raise ValueError("Coverage filter is stale; rerun tools/coverage_analysis.py")
        outside, accepted_outside = coverage["outside_all"], coverage["outside_accepted"]
        coverage_text = f"""
### Only locations outside retained coastal cells

Using the closest regular-grid cell's footprint to define coverage, 57 requested locations are outside: 49 have continuous comparisons, three have event-only comparisons and five remain unavailable. All 57 are model-mask gaps, rather than valid water cells discarded by coastal subsetting.

| Outside-cell subset | Sites | Median RMS, nearest → weighted | Within 10%, nearest → weighted |
|---|---:|---:|---:|
| Accepted sea-cell matches | {accepted_outside['comparable_continuous_sites']} | {accepted_outside['baseline']['median_raw_rmse_cm']:.2f} → {accepted_outside['weighted']['median_raw_rmse_cm']:.2f} cm | {accepted_outside['baseline']['raw_within_10_percent']} → {accepted_outside['weighted']['raw_within_10_percent']} |
| Including unverified diagnostics | {outside['comparable_continuous_sites']} | {outside['baseline']['median_raw_rmse_cm']:.2f} → {outside['weighted']['median_raw_rmse_cm']:.2f} cm | {outside['baseline']['raw_within_10_percent']} → {outside['weighted']['raw_within_10_percent']} |

There is no clear interpolation benefit in this subset either. {outside['one_cell_continuous_estimates']} of the 49 continuous sites still have only one eligible cell. This filter does not test locations farther offshore beyond the extracted band; none of those occurs in this reference sample. The filtered details are saved in `coverage_interpolation.csv` and `coverage_interpolation.json`.
"""
        fragment += coverage_text
        details += "\n" + coverage_text.replace("### Only", "## Only")
        events = [r for r in coverage["locations"] if r["coverage_class_by_band"]["1"] != "inside_retained_footprint" and "events" in r["weighted"] and "raw" not in r["weighted"]]
        event_rows = "\n".join(f"| {r['name']} | {r['baseline']['events']['median_absolute_range_error_percent']:.2f}% | {r['weighted']['events']['median_absolute_range_error_percent']:.2f}% |" for r in events)
        details += f"""
For the three event-only ports outside the cell footprints, these are median high-to-low range errors, not RMS height errors. All three are unverified location diagnostics:

| Port | Original range error | Weighted range error |
|---|---:|---:|
{event_rows}

The footprint convention assigns a grid node half a 0.125° step in each direction. It describes proximity to a retained grid value, not a guarantee of valid interpolation throughout that area. Results were identical for the one-, two- and four-extra-cell masks: 51 requested coordinates lie in retained footprints, 57 in full-model masked footprints, and zero on valid wet cells omitted solely by coastal subsetting. All requested coordinates, grid indices, mask classes and paired errors are in the coverage exports. The original official reference values and location cohorts have not changed.
"""
        coverage_summary = {key: value for key, value in coverage.items() if key != "locations"}
    (RESULTS / "INTERPOLATION.md").write_text(details)
    compact = {key: value for key, value in result.items() if key not in ("locations",)}
    if coverage_summary is not None:
        compact["outside_coverage_analysis"] = coverage_summary
    return fragment, compact
