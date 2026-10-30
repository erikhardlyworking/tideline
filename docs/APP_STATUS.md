# TideLine implementation and test handover

Implemented 30 October 2026 from the widget description and Rainline's visual reference. The current app keeps configuration to location, forecast duration and appearance. It does not include Rainline's MET client or rain-intensity highlighting.

## Behaviour

The resizable widget has a central time axis at model mean sea level, a real astronomical tide curve, 24-hour maximum/minimum heights in centimetres and absolute clock labels. The grey maximum/minimum labels have a short horizontal mark after each number at that height on the graph. Close height labels are separated with short connectors, retaining the true mark positions. Clock labels move above the axis or are omitted when necessary to avoid those annotations. The bottom-left grey `Now: … cm` gives the prediction at the rendered window's start. Location and direction/next-event text remain in the detail screen. New settings default to three hours; 2/4/6/12/24 hours are also available. Existing saved durations are retained. Time/height text and tick marks default to 150% of the original size, with a 100–250% appearance control. Whole-hour labels and 20-minute ticks anchor the three-hour view to actual clock times. Smaller :20/:40 labels appear where space permits, with whole hours prioritised. Tests cover quarter-hour zones and repeated/half-hour DST changes. Very large labels are bounded on the smallest widget sizes. Tapping opens that widget's detail/configuration screen.

Version 0.1.2 separates horizontal and vertical padding, with zero added top/bottom padding by default, including older settings without that field. The renderer still fits complete glyphs inside the bitmap. New settings use the dark palette with white curve/time lines and grey height values; the transparent palette uses the same white/grey hierarchy. The light palette uses dark lines for contrast. Original palette colours are upgraded on loading while custom backgrounds/colours are retained.

The curve uses densely evaluated model heights with smooth, shape-preserving cubic segments. Vertical scale uses a longer 48-hour interval, so weak or slowly changing tides can still appear nearly straight over a short window. The renderer does not substitute a decorative wave. Removing the corner text also gives the curve more vertical room.

The screen shows current predicted height, rising/falling/turning status, next high/low times and heights, a 24-hour curve, selected model location/distance, location-fix age/accuracy and attribution. It uses the device's IANA time zone, shown explicitly in the details. Appearance follows Rainline's Light/Dark/Transparent palettes, with colour/alpha controls and a local preview. Custom transparency is shown against a neutral dark backplate in the app preview; the home-screen widget remains transparent. Buttons use one line and automatically reduce text size to fit; extreme width/font combinations use an ellipsis rather than wrapping.

Each widget has its own persisted configuration. Foreground location requests are approximate and bounded to 25 seconds. Optional background following is off by default, requires a dedicated disclosure and the applicable Android permissions, and uses infrequent system network/passive fixes. Registration is removed when no active widget opts in, and restored after boot/provider changes when authorised. No fine-location permission, continuous GPS service or travel history is used. Atomic preference updates prevent background callbacks from overwriting fixed widgets or a settings edit from replacing a newer fix. Fixed mode needs no location permission.

For current locations, reported accuracy permits a bounded 1–5 km sea search; fixed coordinates retain the 1 km limit used in the existing scientific campaign. The app distinguishes inland locations from missing coastal model support. The expanded approximate-location search has not been independently scored against the official-reference cohort.

A single WorkManager task refreshes existing widgets approximately every 15 minutes, with explicit refresh and boot/package/time-zone/clock/configuration handling. Android may delay periodic work. Absolute time labels describe the rendered window even if an update is delayed. Full RemoteViews replacement avoids accumulating bitmap actions; bitmap size is bounded relative to launcher memory limits.

## Data and calculation

`tide-core` is Android-independent Kotlin/JVM and is shared with `tide-cli`. `app` contains native Android views, location/lifecycle code and the Canvas/RemoteViews renderer. The app requests no Internet permission.

The shipping pack contains original float64 amplitude/phase values from all 17 EOT20 ocean-height constituents, with the one-extra-cell global coastal band. Float bits are preserved through reversible XOR/byte shuffle and zlib. All blocks have length bounds, version checks and SHA-256 checks; decoded geographic tiles use bounded caches. The uncompressed APK asset is read by file offset without copying the entire data package on first launch.

The astronomical calculation uses exact carrier frequencies at each UTC instant, modulated by daily slowly varying complex envelopes generated from pinned pyTMD 2.2.9 FES astronomical/nodal calculations. A fixed admittance matrix adds only the inferred short-period constituents absent from EOT20. Long-period inference is disabled as in the original benchmark. UTC approximates UT1, and pinned timescale TT−UT1 values are included in the basis. The installed tables support 2020–2040 and reject out-of-range prediction dates. These are astronomical factors, not stored local forecast curves or a repeating single year.

The coastline is Natural Earth ocean/Caspian geometry, clipped into geographic tiles with original double-precision vertices. The selector uses complex bilinear interpolation where marine support is available, otherwise a bounded nearest reachable wet cell. The weighted experiment did not demonstrate a general accuracy gain, so IDW was not adopted as a universal fallback. Actual selected sea coordinates, snapping distance and model distance are retained. Missing data is shown as unavailable. The Caspian remains unsupported because EOT20 has no coefficients there.

## Verification actually performed

- Version 0.2.0: debug APK, native-test APK and unsigned release AAB build successfully; eight JVM tests pass and lint reports zero errors. The phone is disconnected while its owner is out. New on-device background permission, movement and rendering checks remain pending; the prepared native test includes inland detection and opt-in/stale-fix/edit-race filtering. Earlier screenshots and `results/device_tests.log` remain explicitly version 0.1.2 evidence.
- Five deterministic calculation/data JVM tests passed, including genuine pyTMD fixtures, an analytic solar constituent, numerical extrema, date bounds, real lossless-decoding fixtures, corruption/truncation, phase wrapping and the longitude seam.
- The production JVM engine was compared with pyTMD at 100 real coastal cells over dates from 2020 to 2040, plus all 159,360 cached continuous official-source instants: **179,360 height checks**, maximum difference **8.80 × 10⁻⁸ m**, below the 1 mm engineering target. This is implementation agreement, not an independent coastal-model accuracy claim.
- The actual Kotlin location selector was exercised at the 108 original official-reference locations. It accepted **69**, including **64** continuous-series locations; those had **6.46 cm median RMS** and **44/64 within 10% of local range**. This differs from the original Python selector cohort because the native selector uses local geodesic shore snapping and independently checks tiled polygon paths. Every accepted path was audited against the original untiled shoreline; no mapped-land crossings over 5 m were found. No rejected/unverified diagnostic was promoted merely because its tide curve looked good.
- On the connected Samsung SM_S938B, Android API 37, version 0.1.2: real installed EOT20 prediction and next-extrema checks, independent saved configurations, four-hour defaults, persisted label size and vertical padding, fourteen widget renders with height marks and current-height labels (three themes/four sizes plus two at 250% label size), single-line push buttons without truncation at the device's current settings, missing-data rendering, RemoteViews inflation, native detail-screen launch and widget tap checks. Test screenshots and logs are in `results/device/` and `results/device_tests.log`. The UI check distinguishes push buttons from Android switches, which inherit the Button class.
- Screenshot review led to a fix for clock-label readability when a weak tide lies close to the middle axis. The render uses actual forecasts; illustrative vector art is limited to the launcher/widget-picker preview.
- Reviewed the installed 0.1.2 dark, transparent, compact light and large-label renders: white/grey hierarchy, actual-height marks and the bottom-left current value are visible. At small widths some clock labels are omitted to avoid the height annotations; at larger widths right-hand clock labels may move above the axis.

The phone's global airplane mode was not changed. Offline operation is supported by the absence of Internet permission and successful installed-data prediction tests. No physical reboot/Doze journey simulation or older API-26 device run was performed. A launcher home-screen pin/resize exercise can be completed using the installed app; the native tests inflate the same RemoteViews and exercise their tap action.

## Reproducible evidence

The 0.2.0 host build/check log is `results/play_preparation_checks.log`; current native phone evidence is still from 0.1.2. The publication includes the real model pack, pinned source/build wrapper, public-facing README, privacy policy and generated HTML, CI and manual Pages workflow, listing draft and submission notes. Publishing requires access to the target repository.

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
