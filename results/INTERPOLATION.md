# Does weighted interpolation improve the tide comparison?

**It helps some good matches as well as some poor ones, but usually by very little, and it can make a good match substantially worse.** This run does not show that weighting should replace nearest-cell fallback everywhere.

We reused all 108 attempted locations, the same 103 numerically comparable locations, the same 159,360 official heights and the same eight days. There are 48 accepted continuous-series sites, 47 sites with only unverified location matches, and eight event-table ports. Good means the original raw RMS error was within 10% of the local range; these groups were fixed using the original result, before scoring the new method. This is an exploratory paired comparison, not new independent validation data.

| Baseline group | Sites | Median RMS / range, before → weighted | Improved >1 mm RMS | Worsened >1 mm RMS |
|---|---:|---:|---:|---:|
| Accepted match, already within 10% | 34 | 4.36% → 4.29% | 10 | 3 |
| Accepted match, over 10% | 14 | 19.35% → 19.22% | 2 | 0 |
| Unverified location, diagnostic only | 47 | 7.18% → 7.52% | 6 | 7 |

For the 34 already-good accepted sites, 17 improved, 10 worsened and 7 were unchanged. Only 10 improved RMS by more than 1 mm; 3 worsened by more than 1 mm. 33/34 remained within 10%.

For the 14 poorer accepted sites, 6 improved, 4 worsened and 4 were unchanged. None reached the 10% target. The 47 diagnostic sites still have 27/47 within 10%, unchanged from the baseline; none gains accepted geographical coverage through averaging.

Across the 48 accepted continuous sites, median RMS/range changed from 5.23% to 5.15%, but median RMS in centimetres changed from 7.17 to 7.24 cm and the number within 10% fell from 34 to 33. These are different site medians; the tiny percentage improvement is not a universal improvement in centimetres.

## Concrete examples

- Key West: RMS worsened from 6.01 to 10.52 cm (8.94% → 15.64% of range). Two model cells about 9.2 and 10.3 km away receive weights 0.555 and 0.445. Both paths are mapped water, but the location is not enclosed by those cells.
- Udamat Bay: RMS improved from 18.30 to 15.84 cm (9.68% → 8.38%), using two cells about 5.6 and 7.9 km away.
- For event-only LINZ comparisons, Bluff's median high-to-low range error improved from 4.11% to 3.31%. Wellington's unverified diagnostic worsened from 4.73% to 27.12%; it remains excluded from accepted coverage. Ranges are not continuous-series RMS scores.

## What was interpolated

For each of the 17 constituents we average its real and imaginary coefficients with positive weights proportional to `1/distance²`, normalised to sum to one. We use at most four wet cells within 20 km, filtering land-crossing paths before selecting/weighting them. The existing four-cell bilinear interpolation is retained where valid. One available cell remains a one-cell estimate. No missing coefficient is replaced by zero and no wrapped phase angle is averaged directly. Settings were fixed before scoring; the power, radius and cell count were not tuned to these references.

Geographical acceptance is separate from numerical agreement. The original 52 accepted sites and 51 diagnostic sites remain in their original groups; five sites without any match remain unavailable. Only 6 accepted estimates have cells that enclose the requested location, including the original bilinear site. The others use a coastal average with cells on one side of the location; this is not interpolation between fully surrounding cells. There were 0 exact grid-node matches in this real-location sample. An exact node is preserved without averaging, as verified by the offline tests.

All chosen weighted coefficients were checked against the full-grid baseline. Lossless decoding still gives identical coefficients and zero compression error. The 23.87 MB package covers all 52 accepted weighted supports; Vardø's diagnostic needs an extra neighbour absent from that band, so the 35.38 MB package covers all 103 weighted supports. No data package was enlarged or recompressed for this experiment.

`interpolation_comparison.json` saves all cells, distances, weights, geometry flags, per-window metrics and event matches. `interpolation_comparison.csv` gives a flat paired comparison. `interpolation_samples.csv` contains all weighted heights and residuals. The original benchmark and samples are preserved. Five additional offline tests cover weights, exact nodes, phase wrapping, one-sided geometry and a nearer cell across a land barrier; the real-data audit independently recomputes saved metrics and checks every band's coefficients.


## Only locations outside retained coastal cells

Using the closest regular-grid cell's footprint to define coverage, 57 requested locations are outside: 49 have continuous comparisons, three have event-only comparisons and five remain unavailable. All 57 are model-mask gaps, rather than valid water cells discarded by coastal subsetting.

| Outside-cell subset | Sites | Median RMS, nearest → weighted | Within 10%, nearest → weighted |
|---|---:|---:|---:|
| Accepted sea-cell matches | 20 | 8.67 → 8.68 cm | 16 → 16 |
| Including unverified diagnostics | 49 | 8.82 → 9.42 cm | 32 → 32 |

There is no clear interpolation benefit in this subset either. 12 of the 49 continuous sites still have only one eligible cell. This filter does not test locations farther offshore beyond the extracted band; none of those occurs in this reference sample. The filtered details are saved in `coverage_interpolation.csv` and `coverage_interpolation.json`.

For the three event-only ports outside the cell footprints, these are median high-to-low range errors, not RMS height errors. All three are unverified location diagnostics:

| Port | Original range error | Weighted range error |
|---|---:|---:|
| Auckland | 12.19% | 18.34% |
| Wellington | 4.73% | 27.12% |
| Napier | 4.54% | 4.65% |

The footprint convention assigns a grid node half a 0.125° step in each direction. It describes proximity to a retained grid value, not a guarantee of valid interpolation throughout that area. Results were identical for the one-, two- and four-extra-cell masks: 51 requested coordinates lie in retained footprints, 57 in full-model masked footprints, and zero on valid wet cells omitted solely by coastal subsetting. All requested coordinates, grid indices, mask classes and paired errors are in the coverage exports. The original official reference values and location cohorts have not changed.
