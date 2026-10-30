# TideLine

An offline Android home-screen tide widget. A white tide curve runs around a central mean-sea-level time axis, with high/low marks and a timestamped height in centimetres. Tap for the next 24 hours, high/low times and settings.

- Four-hour default, with 2/3/4/6/12/24-hour forecasts for each widget.
- Whole-hour labels and 20-minute ticks in shorter views; the bottom-left timestamp identifies the last calculation.
- Fixed coastal coordinates or approximate current location, with optional background following.
- Adjustable colours, transparency, label size, line thickness and padding. Square corners and zero vertical padding by default.
- All 17 EOT20 constituents calculated locally. No Internet permission, accounts, advertising or analytics.

## Accuracy and coverage

TideLine is an approximation for casual interest. It excludes wind, storm surge and other weather-driven changes. Do not use it for navigation, safety decisions or critical activities. Heights are relative to model mean sea level, rather than chart-datum depths.

At 64 tested coastal sites with accepted continuous reference series, median RMS error was 5.3% of tidal range (6.5 cm), with 44 sites within 10%. This sample is not globally representative, and local errors can be much larger. The calculation implementation was separately checked against pyTMD at 179,360 instants, with maximum difference below 1 mm; that is implementation agreement, not model accuracy.

Coverage depends on EOT20 and the mapped coastline. Small harbours and fjords can be unsupported. EOT20 has no Caspian coefficients. The app distinguishes being too far inland from missing model coverage.

## Use

Open TideLine and tap **Set location**. Choose **Current location** or **Specific location**; a fixed location accepts latitude, longitude and an optional name. Add the widget through the app or the launcher's widget picker. Each widget retains its own location, forecast duration and appearance.

The widget refreshes approximately every 15 minutes; Android can delay updates during sleep or battery saving. It is not a live clock. The bottom-left time and height identify its last calculation. Open the app or tap **Refresh** for an immediate calculation.

Background following is off by default. Enabling it shows a disclosure before Android permission setup. Only approximate location is requested; Android 10+ needs separate background access for following. Updates use occasional network/passive system fixes, and only the latest fix is retained locally. Fixed locations and foreground updates work without background permission.

A fixed location must be within 1 km of the mapped sea. Approximate fixes allow a bounded 1–5 km shore search based on their accuracy. A supported model cell also needs a valid marine path within the 20 km model-support limit.

## Build and test

Use JDK 17, Android SDK platform 36/build-tools 35.0.0 and the included Gradle 8.13 wrapper. Minimum Android is 8/API 26; target/compile API is 36. Set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.

```sh
python3 tools/verify_model_pack.py
./gradlew :tide-core:test :app:lintDebug :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:lintRelease :app:bundleRelease
```

Windows can use `gradlew.bat` or `python tools/task.py build`. Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release AAB: `app/build/outputs/bundle/release/app-release.aab`

The release bundle is unsigned unless you configure your own protected upload key. Copy [key.properties.example](key.properties.example) to `key.properties` and enter the keystore path, passwords and alias locally. Alternatively supply all four environment variables: `TIDELINE_STORE_FILE`, `TIDELINE_STORE_PASSWORD`, `TIDELINE_KEY_ALIAS`, `TIDELINE_KEY_PASSWORD`. Keystores and the actual properties file are ignored by Git. Incomplete configuration fails; release signing never falls back to the debug key.

`tide-core` contains the shared Kotlin calculation, data and timeline code; `tide-cli` exercises it off-device; `app` contains the Android widget, settings and location handling. Builds and checks run locally through Codex or the commands above. Native phone checks use a fixed demonstration location:

```sh
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
adb install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w -r app.tideline.test/app.tideline.WidgetInstrumentation
```

## Bundled data

The build includes the real **30,945,129-byte** `data/packs/tideline-global.tpk`. Its coastal EOT20 harmonic values are encoded losslessly. The pack also contains coastline geometry and astronomy tables for 2020–2040. Its compressed tiles are read directly from the uncompressed Android asset; normal builds require no additional tide-data download.

To rebuild the pack, install the pinned Python packages from `requirements.lock.txt` in a virtual environment and run `python tools/task.py data`. This explicitly downloads the approximately 2.33 GB upstream archive. [Data and software notices](docs/DATA_NOTICES.txt) describe sources, licences and conversions.

## Privacy and licences

[Privacy policy](PRIVACY.md) · [Data and software notices](docs/DATA_NOTICES.txt) · [Apache-2.0 licence](LICENSE)

The privacy policy and licence notices are bundled for offline reading. The app's **View on GitHub** buttons use these stable repository URLs:

- [Privacy policy](https://github.com/erikhardlyworking/tideline/blob/main/PRIVACY.md)
- [Data and software notices](https://github.com/erikhardlyworking/tideline/blob/main/docs/DATA_NOTICES.txt)

A standalone HTML copy of the policy is maintained in `docs/site/`. Regenerate it locally with `python3 tools/render_privacy.py`; the repository links above provide the policy and notices on GitHub.

For support or privacy inquiries, email workinghardlyforyou@gmail.com. Bugs can also be reported through the repository's Issues page. Avoid putting private location details in public issues.

[More apps by this developer on Google Play](https://play.google.com/store/apps/dev?id=6256987868491351341).

Original application code: Apache-2.0. EOT20 data: CC BY 4.0. Natural Earth shoreline: public domain. Astronomical reference: pyTMD, MIT. Full notices and licence texts are retained in the source and app.
