# TideLine

An offline Android tide widget with a white tide curve, a central mean-sea-level time axis, grey high/low level marks and a timestamped predicted height. Tap the widget for details and settings. Thin grey high/low guides span the graph with gaps around the value labels.

- Four-hour default forecast, with 2/3/4/6/12/24-hour options for each widget.
- Whole-hour labels above/below the axis, with 20-minute ticks in the four-hour view. The inset bottom-left timestamp and height identify the last update. Hours shorten to `19` on small widgets; wider widgets use `19:00` with a smaller grey minute suffix.
- Fixed coordinates or approximate current location. Optional background following keeps selected widgets near you without opening the app.
- Clear coastal-distance and missing-data messages instead of an invented forecast.
- Dark, transparent and light palettes, custom colours, adjustable label/tick size and independent horizontal/vertical padding. Vertical padding and rounded corners default to zero.
- All 17 EOT20 constituents, calculated locally. No Internet permission, accounts, advertising or analytics.

TideLine is an approximation for casual interest. It excludes weather-driven water-level changes and should not be used for navigation, safety decisions or critical activities. Heights are centimetres relative to the model’s mean sea level, rather than chart-datum depths. Coverage is limited by the global model and mapped coastline; some small harbours/fjords are unsupported, and EOT20 has no Caspian coefficients.

## Use

Install an APK you build and open TideLine. **Set location** near the top jumps to explicit **Current location** / **Specific location** choices. A specific location accepts latitude, longitude and an optional name. Then use **Add widget to home screen** or the launcher's widget picker. Each widget retains its own location, duration and appearance. Existing widget durations are preserved; the old new-widget template is migrated to four hours, and adding from an existing widget starts the new one at four hours.

A brief accuracy note near the top reports the native engine's validated coastal cohort: median RMS error 5.3% of tidal range (6.5 cm), with larger local errors possible.

The widget normally refreshes approximately every 15 minutes. Android can delay updates during sleep or battery saving, so its timeline uses absolute device clock times. The curve covers the configured duration from its last calculation; the inset bottom-left time and height identify that calculation. Larger dots mark the starts of the curve and time axis. It is not a live clock. Refreshing in the app calculates immediately.

Background following is **off by default**. Enabling it shows an in-app disclosure before permission setup. On Android 10+ it requires background location access; Android 11+ grants this through app settings. Only approximate location is requested. Updates use occasional system network/passive fixes, with no continuous GPS service. Android may deliver them only a few times an hour or delay them further. Only the latest fix is saved locally, with its age shown in the app. Fixed locations and foreground-only updates work without background permission.

A precise fixed location must be within 1 km of the mapped sea. Approximate current fixes allow a bounded 1–5 km shore search according to their reported accuracy. A model cell still needs a valid marine path within the existing 20 km support limit. The app distinguishes being too far from the coast from missing model support.

## Build

Use JDK 17, Android SDK platform 36/build-tools 35.0.0 and the included Gradle 8.13 wrapper. Minimum Android is 8/API 26; target/compile API is 36. Set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.

```sh
python3 tools/verify_model_pack.py
./gradlew :app:assembleDebug
./gradlew :tide-core:test :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:bundleRelease
```

Windows can use `gradlew.bat` or `python tools/task.py build`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Release bundle: `app/build/outputs/bundle/release/app-release.aab`. The release bundle is unsigned unless you configure your own upload signing; see [Google Play preparation](docs/PLAY_STORE.md). No signing keys are included.

The repository publication includes the **30,945,129-byte** real `data/packs/tideline-global.tpk`. It is losslessly encoded and licensed separately from the app. The normal build uses that pack and needs no research downloads. It stays uncompressed as an APK asset for direct seeking; its internal tiles are compressed. [Data and software notices](docs/DATA_NOTICES.txt) are bundled in the app. To rebuild the pack from the 2.33 GB official upstream archive, install the pinned Python requirements in a virtual environment and run `python tools/task.py data`; this is an explicit large-data step.

## Source and verification

`tide-core` is the shared pure Kotlin calculation/data/timeline module; `tide-cli` exercises it off-device; `app` contains Android location, settings and widget rendering. GitHub Actions is configured to run tests, lint and builds. Native phone checks can be run with:

```sh
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
adb install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w -r app.tideline.test/app.tideline.WidgetInstrumentation
```

The calculation engine was checked against pyTMD at 179,360 instants, with maximum implementation difference below 1 mm. This is calculation agreement, not independent model accuracy. The native selector's existing official-reference cohort had median RMS error 6.46 cm (5.27% of range) over 64 accepted continuous-series sites, with 44 within 10%; the sample is not globally representative. [The original report](results/REPORT.md), [interpolation follow-up](results/INTERPOLATION.md) and [research reproduction notes](docs/RESEARCH.md) describe the samples and limits. The original data-only report has a different selection cohort and remains unchanged.

## Privacy, support and publishing

[Privacy policy](PRIVACY.md) · [Google Play preparation](docs/PLAY_STORE.md) · [Store listing draft](docs/STORE_LISTING.md) · [Licence](LICENSE)

The privacy policy is available offline in the app. `docs/site/privacy.html` is generated from the same policy using `python3 tools/render_privacy.py`. After enabling GitHub Pages with GitHub Actions in repository settings, run the manual **Publish privacy page** workflow to host it publicly. The intended URL is `https://erikhardlyworking.github.io/tideline/privacy.html`; verify it before entering it in Play Console. An inaccessible/private repository alone is not a public privacy-policy URL.

For bugs or privacy inquiries, use the repository's Issues page. Avoid posting private location details in public issues.

Original application code: Apache-2.0. EOT20 data: CC BY 4.0. Natural Earth shoreline: public domain. Astronomical reference calculations: pyTMD, MIT. Full notices are retained separately.
