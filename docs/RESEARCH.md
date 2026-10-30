## Data feasibility experiment

Raw downloads, full official-response caches and per-sample campaigns are local research artifacts, excluded from this source publication. The repository includes the code, summary reports, calculation/selector results and shipping model pack. Run the pipeline below to regenerate the detailed campaign.

The earlier data-only experiment and all its raw evidence remain in this workspace. Subsequent Kotlin validation is saved separately so the original scientific comparison is not silently replaced.

Read [the short report](../results/REPORT.md) or open [the self-contained HTML report](../results/REPORT.html), which adds expandable plots and all attempted locations.

## Reproduce

The run used Python 3.14.4 on Linux. Exact Python package versions are in `requirements.lock.txt`; pyTMD is pinned to 2.2.9. Scientific packages are installed only inside `.venv`.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock.txt
.venv/bin/python tools/download_inputs.py
.venv/bin/python tools/coastal.py
.venv/bin/python tools/fetch_references.py
.venv/bin/python tools/validate_reference.py
.venv/bin/python tools/test_feasibility.py
.venv/bin/python tools/benchmark.py
.venv/bin/python tools/report.py
```

To reproduce the weighted-interpolation follow-up using the existing cached campaign:

```sh
.venv/bin/python tools/compare_interpolation.py
.venv/bin/python tools/test_interpolation.py
.venv/bin/python tools/audit_interpolation.py
.venv/bin/python tools/coverage_analysis.py
.venv/bin/python tools/report.py
```

The follow-up compares fixed inverse-distance-squared weighting of up to four wet cells against the original result, and stratifies locations by their original accuracy and geographical acceptance. [The interpolation report](../results/INTERPOLATION.md) explains the gains and regressions. The baseline `benchmark.json` and `samples.csv` remain the original campaign. Weighted outputs use `interpolation_` filenames; the main report includes both sets of findings. The 23.9 MB package covers every accepted weighted support, while one unverified diagnostic needs a wider band.

`coverage_analysis.py` further filters by the original requested coordinate's nearest regular-grid footprint, rather than the chosen fallback cell. `coverage_interpolation.csv` and `.json` distinguish an original model-mask gap from a valid wet value omitted by coastal subsetting. This station sample has 57 model-mask gaps and zero omission-only cases; it does not establish accuracy at offshore positions beyond the stored band.

On Windows use `.venv\Scripts\python.exe` in place of `.venv/bin/python`. The pipeline itself uses Python rather than shell-specific orchestration. If the OS Python lacks pip/ensurepip, create the environment with `python3 -m venv --without-pip .venv` and bootstrap pip in that environment using the official `https://bootstrap.pypa.io/get-pip.py` script. This was necessary on the experiment host.

The download step intentionally transfers the large upstream archive. Subsequent runs reuse it and cached source responses. Ordinary offline tests do not download anything. A fresh run needs several GB of local disk and enough RAM to process the full global grids; peak conversion memory was not instrumented. `pdftotext`/`pdftoppm` were used to inspect the one NOAA coordinate discrepancy, but are not required for the normal frozen campaign.

## Saved evidence

| Path | Contents |
|---|---|
| `results/REPORT.md`, `REPORT.html` | Short conclusions; HTML includes inline plots |
| `results/locations.csv` | All primary locations, including unsupported ones |
| `results/diagnostic_locations.csv` | Explicitly separate nearest-cell diagnostics |
| `results/samples.csv` | Every matched official/model height, with comparison role |
| `results/benchmark.json` | Per-window errors, cell coordinates/weights/distances, event matches, timing and range errors |
| `results/summary.json` | Aggregates without sample-count weighting of site medians |
| `results/references.json` | Normalised official predictions, original cache paths and datum/time metadata |
| `results/selected_stations.json` | A-priori core sample, selection algorithm and seasonal windows |
| `results/compression.json` | All 18 representation/transform/band measurements |
| `results/model_inspection.json` | Actual axes, source variables, masks, units, constituent names and file hashes |
| `results/ocean_selection.json`, `coastal_selection.json` | Component selection, excluded inland components and coastal-band method |
| `results/caspian_coverage.json` | Explicit Caspian coverage checks requested by the user |
| `results/calculation_validation.json`, `packaging_validation.json` | Reference integration and exact coefficient checks |
| `results/provenance.json`, `download_manifest.json` | Versions, code/package/archive SHA-256 hashes |
| `results/offline_tests.log` | Six offline check results |
| `data/raw/http/*.body`, `*.meta.json` | Untouched HTTP responses, exact request URLs, retrieval times, hashes |
| `data/raw/85762.zip` | Official final EOT20 archive, including the unused load-tide archive |
| `data/EOT20/ocean_tides/` | Original 17 ocean-height NetCDF files |
| `data/packs/*.eotz` | Three actual lossless global coastal packages |
| `data/source_ap.npy`, `model_geometry.npz` | Full-grid baseline and selected geometry for rerunning comparisons |

Large data and environments are excluded by `.gitignore`. They have not been uploaded anywhere.

## Method and limits

The 100 core sites are 67 NOAA harmonic/reference stations and all 33 permanent gauges in Kartverket's catalog. Seven named NOAA station IDs were verified against the official catalog; remaining sites were chosen by spherical farthest-point sampling before viewing model coverage or errors. Eight LINZ ports provide additional event-only checks. NOAA's catalog includes international stations, so provider count is not country count. The sample includes protected harbours, narrow fjords, high latitudes and weak tides. Its geographic distribution is not a representative worldwide validation.

The user requested a quick feasibility campaign, so the specification's proposed 30-day windows were shortened to two days starting 1 January, April, July and October 2026. We fetched the complete native six-minute NOAA and ten-minute Norwegian series for those eight days. The dates test several seasons but not the full spring/neap or 18.6-year nodal cycle. This is explicitly not the full application acceptance campaign in the attachment.

The predictor uses UTC instants, double-precision harmonic values, FES astronomical arguments/nodal factors and pyTMD's short-period minor inference exactly once. Supplied constituents are excluded from inference. Long-period inference is disabled because EOT20 lacks the `node` pivot required by pyTMD's algorithm; its supplied SA, SSA, MM and MF are retained. Per-window errors without minor inference are also saved. `timescale` supplies TT−UT1; UTC approximates UT1, without a separate subsecond DUT1 correction. Only the 2026 test windows are evaluated; no timeless package validity is claimed.

Interpolation is complex bilinear only when the complete wet quadrilateral and paths are marine in the independent shoreline. Otherwise a nearest-cell fallback needs a clear straight water path, at most 20 km distance and at most 1 km shoreline snapping. This is deliberately conservative and cannot follow a curved channel around a peninsula. Natural Earth 1:10m shorelines omit some harbour detail. Thus automatic-location rejection is a limit of this prototype, not proof that the entire locality has no EOT20 data. Rejected locations also receive separate nearest-wet-cell diagnostics within 20 km, without an unearned claim of water connectivity. Their scores are not included in the accepted-match headline.

The percentage metric is RMS error divided by the average peak-to-trough reference range of the four 48-hour windows. It is not maximum error or a percentage of instantaneous height. Ranges under 0.10 m have no percentage score. Raw errors use the requested local MSL reference; the model/local MSL zeros have not been physically surveyed into a common datum. An additional mean-centred score subtracts one constant over all windows and is labelled separately. No fitted amplitude or phase adjustments were made.

Extrema use a 5 mm prominence and quadratic refinement of dense samples, with chronological matching by type within two hours. Six/ten-minute reference sampling limits timing precision; refinement does not establish sub-minute observational accuracy. LINZ's actual CSV events use IANA `Pacific/Auckland` for standard/daylight time; heights are rounded to 0.1 m. Only event timing and high-to-low ranges are scored for LINZ, with no invented continuous reference curve or fitted vertical offset. Ambiguous, extra and unmatched events remain in JSON. Percentage range errors are not interchangeable with continuous-series RMS errors.

The original pyTMD check compares our source conversion with its own EOT20 reader at 100 genuine coastal cells and 192 instants per cell. It tests integration and conventions, not an independent model. The later production Kotlin/JVM comparison is in `results/kotlin_validation.json`. External local predictions are a stronger check, but statistical independence of their underlying gauge records is not established: EOT20 builds on FES2014 and gauge/altimetry data can overlap.

One source error was investigated: NOAA's Djakarta API latitude is about 433 km north of the named Java port. The run uses 6°06′S, 106°53′E from NOAA's published 2016 tide-table page 403 (PDF index 412). `tools/source_corrections.json` documents the exact override; the original comparison and samples are retained under `results/jakarta_original_api_coordinate_*`, along with an image of the source page. The 2026 official prediction values are unchanged. This fixes station identity rather than calibrating tide predictions.

Natal was attempted via the Brazilian Navy's publication page, map page and current 2026 table catalog. Direct discovery requests returned HTTP 403; the indexed official catalog confirmed Natal's presence but did not expose a usable table attachment. No third-party table was substituted. This provider remains untested.

## Lossless format

EOT20 actually contains float64 amplitude/phase and redundant real/imaginary fields. The primary packages preserve original amplitude/phase bits; they do not convert them to float32 or quantised integers. Complex128 derived coefficients were benchmarked separately. The baseline uses the same original amplitude/phase pairs. Reconstructed complex coefficients are identical after decoding, so deterministic predictions are identical at all instants, including extrema. This exact equality is stronger than a sampled millimetre error bound for compression alone.

Source longitude includes both 0° and 360°. Shared valid values are exactly equal, but 232 rows are masked at 0° and valid at 360°. These duplicates are merged into one meridian, taking the original valid coefficient bits, before periodic connectivity and coastal selection. This avoids throwing away valid seam cells or inventing interpolated values.

Ocean wet components are selected with periodic longitude connectivity and Natural Earth's ocean polygon. At the user's request the Caspian polygon is also permitted, but EOT20 has no valid coefficients there. Inland lakes are excluded. Coastal seeds combine model wet/land adjacency with the independent shoreline so sub-grid islands are not lost merely because the model has no dry island cell. Extra bands expand through valid wet cells only. Additional cells preserve interpolation support; they do not improve resolution. Model spacing is about 13.9 km north/south, with east/west spacing decreasing toward the poles.

The experimental `.eotz` format is `EOTLSS01`, a little-endian uint64 compressed-index length, a checksummed Zstandard JSON index, then independently compressed 5° tiles. Entries contain origin, tile dimensions, bit-packed wet mask length, compressed coefficient length and SHA-256. Native arrays are little-endian float64 `[constituent, amplitude/phase, retained-cell]`. Bitwise spatial XOR and byte shuffling are reversible before Zstandard level 9; there is no numerical delta rounding or quantisation. The reader checks versions, hashes and truncation, and has an eight-tile coefficient cache. This is a research container, not a finished Android import/update format.

Decode and prediction timings in the original research results are Linux host measurements. Peak conversion memory and atomic app updates were not tested. The later installed-app checks are documented in [APP_STATUS.md](APP_STATUS.md); they verify functionality on the connected phone without claiming a phone performance benchmark.

## Attribution

EOT20: Hart-Davis, Piccioni, Dettmering, Schwatke, Passaro and Seitz (2021), DOI [10.17882/79489](https://doi.org/10.17882/79489), model paper [10.5194/essd-13-3869-2021](https://doi.org/10.5194/essd-13-3869-2021), CC BY 4.0. Changes: ocean-height selection, ocean/Caspian coastal subsetting and lossless format conversion. The loading-tide model was not used.

Shoreline: Natural Earth `ne_10m_ocean` 5.1.1, public domain. Predictor reference: pyTMD 2.2.9, MIT. Kartverket API documentation identifies CC BY 4.0. Official raw responses remain local. The original data-only study made no application licensing decision; the new application code is Apache-2.0, with dataset and third-party licences kept separate.
