# EOT20 tide-widget feasibility test — 30 October 2026

**Verdict:** the dataset is practical to package for offline use, and many tested sea locations meet the proposed 10% tolerance. It does **not** establish reliable 10% accuracy or automatic coverage at every harbour worldwide. Local coverage and location selection need more work before building the widget.

## Prediction quality

Attempted **108 official reference locations**: 67 NOAA, 33 Kartverket and eight LINZ ports. We obtained quantitative comparisons at **103** locations: 95 continuous-series sites plus eight event-table sites. The remaining 5 had no usable nearby model match under the 20 km limit. The sample includes the Americas, Norway and Pacific/Indian Ocean islands, but is not a representative survey of every coastline.

We tested 1–2 January, April, July and October 2026, matching 159,360 official astronomical heights at their original six- or ten-minute timestamps. These are predictions, not weather-affected observations. LINZ high/low tables were compared as events, never turned into a synthetic reference curve.

“10%” means **RMS height error divided by the mean official 48-hour high-to-low range**. This avoids division by tide heights near zero; it is an average-error criterion, not a guarantee that every point is within 10%.

| Comparison | Continuous sites | Within 10% | Median RMS error / range |
|---|---:|---:|---:|
| Accepted coastal matches | 48 | 34 | 5.2% |
| Nearest-cell diagnostics; location match unverified | 47 | 27 | 7.2% |

For accepted matches, median RMS height error was **7.2 cm**. Poor locations remain visible: Point Hope 57%; Kaupanger 42%; Bruravik 40%; Barrow Offshore 39%; Helmick Point, southeast of 32%. Median absolute high/low timing errors across accepted sites were approximately **7/7 minutes**, with larger errors and unmatched events at some sites.

Only **52/108 locations passed the conservative automatic location check**: nearby wet support with a clear mapped-water path, a maximum 20 km cell distance and at most 1 km shoreline snapping. 51 others have explicitly separate nearest-cell diagnostics. This check is stricter than ordinary nearest-cell lookup and is partly limited by the coarse shoreline; rejected matches do not necessarily mean EOT20 has no nearby tide data. Diagnostics are excluded from the accepted-match score.

Both continuous providers were requested relative to local MSL; a shared physical zero with EOT20 is not established. No offset, scale or timing adjustment was applied. The separately labelled mean-centred check gives 5.2% median error and 34/48 within 10%. LINZ's four accepted ports had 1.9% median site-level range error; heights are rounded to 0.1 m. Good range agreement can coexist with poor timing: Bluff's median high/low timing errors were 27/38 minutes.

One NOAA coordinate discrepancy was corrected from its own [published tide table, page 403](https://tidesandcurrents.noaa.gov/tidetables/2016/pttt2016book.pdf#page=413): Jakarta's API coordinate was about 433 km from the named port. The original result is preserved; the corrected location remains a diagnostic, not accepted coverage.

## Weighted interpolation follow-up

Retested the same locations using up to four wet cells, weighted by inverse distance squared, averaging complex harmonic components. The existing bilinear site was retained. Land barriers, distance limits, dates and reference predictions were unchanged.

| Baseline group | Sites | Median RMS / range, before → weighted | Improved >1 mm RMS | Worsened >1 mm RMS |
|---|---:|---:|---:|---:|
| Accepted match, already within 10% | 34 | 4.36% → 4.29% | 10 | 3 |
| Accepted match, over 10% | 14 | 19.35% → 19.22% | 2 | 0 |
| Unverified location, diagnostic only | 47 | 7.18% → 7.52% | 6 | 7 |

Weighting helps some already-good sites, mostly slightly; it is not a general accuracy improvement. One good site, Key West, worsened from 8.9% to 15.6%. None of the 14 poorer accepted sites entered the 10% target. Across all 48 accepted continuous sites, the pass count fell from 34 to 33. Only six of 52 accepted model matches have enclosing cells; most coastal averages are one-sided. Exact grid nodes would be preserved, but none of these gauges is an exact node match.

The 23.9 MB package still covers all accepted weighted matches. Covering every diagnostic's extra neighbours needs the 35.4 MB band. Full paired results are in `INTERPOLATION.md`, `interpolation_comparison.csv` and `interpolation_comparison.json`.

### Only locations outside retained coastal cells

Using the closest regular-grid cell's footprint to define coverage, 57 requested locations are outside: 49 have continuous comparisons, three have event-only comparisons and five remain unavailable. All 57 are model-mask gaps, rather than valid water cells discarded by coastal subsetting.

| Outside-cell subset | Sites | Median RMS, nearest → weighted | Within 10%, nearest → weighted |
|---|---:|---:|---:|
| Accepted sea-cell matches | 20 | 8.67 → 8.68 cm | 16 → 16 |
| Including unverified diagnostics | 49 | 8.82 → 9.42 cm | 32 → 32 |

There is no clear interpolation benefit in this subset either. 12 of the 49 continuous sites still have only one eligible cell. This filter does not test locations farther offshore beyond the extracted band; none of those occurs in this reference sample. The filtered details are saved in `coverage_interpolation.csv` and `coverage_interpolation.json`.

## Storage and lossless compression

Downloaded the official **2.33 GB EOT20 final archive** and used all 17 ocean-height constituents. Retained original 64-bit amplitude/phase values, coastal islands and supported bays, excluding inland lakes; the Caspian was explicitly allowed. Coastal cells plus extra wet-cell bands produced:

| Extra cells beyond coastal seed | Retained wet cells | Lossless package |
|---|---:|---:|
| 1 | 131,725 | 23.87 MB |
| 2 | 196,440 | 35.38 MB |
| 4 | 314,913 | 56.41 MB |

Sizes are decimal MB. Independent 5° tiles use spatial bitwise XOR, byte shuffling and Zstandard. All retained coefficient bits round-trip exactly; **compression introduces zero prediction error**. Most size reduction comes from coastal subsetting, not compression alone. The four-cell package contains 85.66 MB of uncompressed coefficients. Bands retain the original 0.125° model resolution; they cannot add missing fjords or resolve small harbours.

All three packages retained the same coefficients at all 103 compared model matches; the 24 MB option was sufficient for this sample. That does not establish its completeness for every coastline.

## Caspian and limits

**EOT20 supplies no valid tide coefficients in the Caspian Sea** in this extract, including the Baku/Aktau/Anzali checks. Including it in the selection cannot create a forecast there; a different data source would be needed. Natal was attempted, but the Brazilian Navy download-discovery pages returned HTTP 403, so no Natal accuracy claim is made.

This is a Python/pyTMD feasibility experiment, with short-period minor inference and FES astronomical corrections. It has **not** tested a Kotlin engine or Android widget. Our conversion agrees with pyTMD’s own reader/predictor to 8.9e-16 m over 19,200 checks; that validates integration, not coastal model accuracy. Six offline checks passed. Raw responses, hashes, packages, per-site metrics, event matches and every compared height are saved for follow-up. Four short seasonal windows are preliminary; neither worldwide coastline completeness nor year-round accuracy has been demonstrated.

Sources: [EOT20 dataset](https://doi.org/10.17882/79489), [NOAA API](https://api.tidesandcurrents.noaa.gov/api/prod/), [Kartverket API](https://vannstand.kartverket.no/tideapi_en.html), [LINZ predictions](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions), [Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/).
