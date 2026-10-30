# TideLine implementation and test handover

Implemented 30 October 2026 from the widget description and visual preferences. The main configuration covers location, forecast duration and appearance.

## Behaviour

Version 0.2.3 adds a general **More apps on Google Play** button in About, linking to the developer page, and removes the specific reference to another widget from these notes and the bundled notices. Data/software licence attribution remains intact.

Version 0.2.2 adds **View on GitHub** to the privacy policy and data/software notice dialogs, retaining the bundled documents for offline reading. The targets are the repository's `main/PRIVACY.md` and `main/docs/DATA_NOTICES.txt`. README/Play notes include the same URLs. The repository stays private during testing, so anonymous access is intentionally pending its later visibility change.

Version 0.2.1 uses a four-hour default for new widgets, including a one-time migration of the old new-widget template. Existing widget durations remain unchanged. Adding from an existing widget does not copy its old two-hour duration into the new widget. The selectable durations are 2/3/4/6/12/24 hours.

The widget has a central mean-sea-level axis with whole-hour labels above/below it. Captions shorten to `19` on small widgets; wider widgets can use `19:00` with a smaller grey `:00`. Short tick marks remain at 20-minute intervals in the 2–4 hour views. There are no intermediate minute captions. Labels are fitted around other annotations and the actual curve. Near-start hour captions remain whenever they fit; the other row and shorter hour text are tried before omitting a caption.

The lower-left inset shows the last-update time and height, such as `19:53 · −21 cm`. Both the curve and the axis have matching larger round start dots; no extra bold start bar is drawn. The tide curve is slightly thicker (2.0 dp for dark/light, 1.35 dp for transparent). Thin grey high/low guides span the time-axis width with explicit gaps around numeric/text annotations. Current model mean sea level remains zero; the high/low labels describe the next 24 hours.

Square corners and zero added vertical padding are the defaults. Old standard corner/line defaults are migrated, with a format version preserving subsequent deliberate custom choices. Light, dark and transparent palettes, colours and label/tick size remain configurable. Very small widgets may omit hour captions or reduce footer text size to keep annotations separate.

An accuracy note near the top reports the native selector's validated coastal cohort: median RMS 5.3% of tidal range (6.5 cm), with larger local errors possible and weather effects excluded. These are the app cohort's results (64 accepted continuous sites), distinct from the earlier Python report's 48-site cohort (5.2% / 7.2 cm); neither is a worldwide guarantee.

The **Set location** shortcut jumps to explicit current/specific-location choices. A specific location has labelled latitude/longitude inputs and an optional name; saving it disables movement of that widget's location. Each widget retains its own settings. Buttons stay on one line and resize their text to fit.

Background following remains optional and off by default, with disclosure and separate Android permission setup. It uses approximate network/passive fixes, and stops when no active widget opts in. Fixed locations need no permission. The settings explain the approximate 15-minute widget refresh, possible Android delays, absolute clock labels and timestamped value. Real movement, background permission approval and reboot/Doze journeys remain manual checks.

## Data and calculation

`tide-core` is Android-independent Kotlin/JVM and is shared with `tide-cli`. `app` contains native Android views, location/lifecycle code and the Canvas/RemoteViews renderer. The app requests no Internet permission.

The shipping pack contains original float64 amplitude/phase values from all 17 EOT20 ocean-height constituents, with the one-extra-cell global coastal band. Float bits are preserved through reversible XOR/byte shuffle and zlib. All blocks have length bounds, version checks and SHA-256 checks; decoded geographic tiles use bounded caches. The uncompressed APK asset is read by file offset without copying the entire data package on first launch.

The astronomical calculation uses exact carrier frequencies at each UTC instant, modulated by daily slowly varying complex envelopes generated from pinned pyTMD 2.2.9 FES astronomical/nodal calculations. A fixed admittance matrix adds only the inferred short-period constituents absent from EOT20. Long-period inference is disabled as in the original benchmark. UTC approximates UT1, and pinned timescale TT−UT1 values are included in the basis. The installed tables support 2020–2040 and reject out-of-range prediction dates. These are astronomical factors, not stored local forecast curves or a repeating single year.

The coastline is Natural Earth ocean/Caspian geometry, clipped into geographic tiles with original double-precision vertices. The selector uses complex bilinear interpolation where marine support is available, otherwise a bounded nearest reachable wet cell. The weighted experiment did not demonstrate a general accuracy gain, so IDW was not adopted as a universal fallback. Actual selected sea coordinates, snapping distance and model distance are retained. Missing data is shown as unavailable. The Caspian remains unsupported because EOT20 has no coefficients there.

## Verification actually performed

Version 0.2.3: APK/test APK/unsigned AAB builds and lint passed. The phone checks passed, including all 50 audited renders and single-line controls. The developer-profile URL in resources matches the supplied Google Play address. Current source and bundled notices contain no reference to the earlier widget; all data/software licence notices remain.

Version 0.2.2: APK, native-test APK and unsigned release bundle build successfully; lint has zero errors. The installed-phone checks, including all 50 render audits, passed after waking the dozing device. Both URL paths were verified against `origin/main` through authenticated Git access. Anonymous requests returned 404 as expected for the intentionally private testing repository; public-access verification is deferred until its visibility changes.

- Version 0.2.1: debug APK, native-test APK and unsigned release AAB build successfully; eight JVM tests pass and lint reports zero errors. Installed on the Samsung SM_S938B/API 37. Fifty actual-data renders across sizes, label scales and refresh minutes passed pairwise text-bound checks and guide/value intersection checks. Native checks also verified the four-hour new-template migration without changing an existing two-hour widget, persistence of a later deliberate default choice, specific-location saving, the accuracy note, single-line buttons, detail launch and widget taps. Screenshots and `results/device_tests_v0.2.1.log` describe this build. A selector fix keeps a newly allocated/configuring widget selected even before it appears in the provider's active ID list. Background permission approval and actual movement/reboot/Doze journeys remain manual checks.
- Five deterministic calculation/data JVM tests passed, including genuine pyTMD fixtures, an analytic solar constituent, numerical extrema, date bounds, real lossless-decoding fixtures, corruption/truncation, phase wrapping and the longitude seam.
- The production JVM engine was compared with pyTMD at 100 real coastal cells over dates from 2020 to 2040, plus all 159,360 cached continuous official-source instants: **179,360 height checks**, maximum difference **8.80 × 10⁻⁸ m**, below the 1 mm engineering target. This is implementation agreement, not an independent coastal-model accuracy claim.
- The actual Kotlin location selector was exercised at the 108 original official-reference locations. It accepted **69**, including **64** continuous-series locations; those had **6.46 cm median RMS** and **44/64 within 10% of local range**. This differs from the original Python selector cohort because the native selector uses local geodesic shore snapping and independently checks tiled polygon paths. Every accepted path was audited against the original untiled shoreline; no mapped-land crossings over 5 m were found. No rejected/unverified diagnostic was promoted merely because its tide curve looked good.
- Historical version 0.1.2 on the connected Samsung SM_S938B, Android API 37: real installed EOT20 prediction and next-extrema checks, independent saved configurations, four-hour defaults, persisted label size and vertical padding, fourteen widget renders with height marks and current-height labels (three themes/four sizes plus two at 250% label size), single-line push buttons without truncation at the device's current settings, missing-data rendering, RemoteViews inflation, native detail-screen launch and widget tap checks. Its historical log is `results/device_tests_v0.1.2.log`; current screenshots have been refreshed to 0.2.1. The UI check distinguishes push buttons from Android switches, which inherit the Button class.
- Screenshot review led to a fix for clock-label readability when a weak tide lies close to the middle axis. The render uses actual forecasts; illustrative vector art is limited to the launcher/widget-picker preview.
- Reviewed the installed 0.1.2 dark, transparent, compact light and large-label renders: white/grey hierarchy, actual-height marks and the bottom-left current value are visible. At small widths some clock labels are omitted to avoid the height annotations; at larger widths right-hand clock labels may move above the axis.

The phone's global airplane mode was not changed. Offline operation is supported by the absence of Internet permission and successful installed-data prediction tests. No physical reboot/Doze journey simulation or older API-26 device run was performed. A launcher home-screen pin/resize exercise can be completed using the installed app; the native tests inflate the same RemoteViews and exercise their tap action.

## Reproducible evidence

The 0.2.1 host build/check log is `results/layout_refinement_checks.log`; current native phone evidence is in `results/device_tests_v0.2.1.log`, `results/device/layout-audit.txt` and `results/device/`. Versioned older logs remain historical checks. The publication includes the real model pack, pinned source/build wrapper, public-facing README, privacy policy and generated HTML, CI and manual Pages workflow, listing draft and submission notes. Publishing requires access to the target repository.

- `results/app_verification.json`: final APK identity, exact size, build/check outcomes and device-test scope.
- `results/kotlin_validation.json`: production calculation/reference agreement and per-site official height comparisons using matched support cells.
- `results/kotlin_location_validation.json`: actual Kotlin selector, acceptance/rejection, selected sea/model cells, errors and independent water-path audit.
- `results/android_data_manifest.json`: shipping pack identity/hash, content size and astronomy coverage.
- `results/gradle_checks.log`: build, JVM tests and lint.
- `tide-core/build/test-results/test/`: JUnit XML results.
- `results/device_tests.log`, `results/device/`: native phone tests and owned-app screenshots.

The original scientific report and interpolation experiments remain unchanged in `results/REPORT.md` and their separate exports. Their percentages describe their stated cohorts and should not be mixed silently with the native selector's cohort.

## Short remaining device checklist

Add TideLine through the launcher or the app's pin button; resize a small/large widget and tap each independently. Try fixed location without permission, approximate current location, denied/revoked background permission, following on/off and movement while the app is closed. Record the disclosure and permission flow for Play review. Check the retained fix age, delayed periodic updates, reboot restore and the device's clock/time-zone changes. Compare a known local beach with an official source before relying on a location's approximation.
