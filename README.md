# TideLine

An offline Android home-screen tide widget. A white tide curve runs around a central mean-sea-level time axis, with high/low marks and a timestamped height in centimetres. Tap for the next 24 hours, high/low times and settings.

- Four-hour default, with 2/3/4/6/12/24-hour forecasts for each widget.
- New widgets start at 2×2 cells and can be resized.
- Whole-hour labels, quarter-hour ticks and larger half-hour ticks in shorter views; the centred timestamp identifies the last calculation.
- Offline search of about 40,000 coastal towns, fixed coordinates or approximate current location, with optional background following.
- Name either location mode and optionally show its name beside the update time. A small offline globe shows the selected point in settings.
- Adjustable colours, transparency, label size, line thickness and padding. Launcher-controlled corners and zero vertical padding by default.
- All 17 EOT20 constituents calculated locally. No Internet permission, accounts, advertising or analytics.

## Accuracy and coverage

TideLine is an approximation for casual interest. It excludes wind, storm surge and other weather-driven changes. Do not use it for navigation, safety decisions or critical activities. Heights are relative to model mean sea level, rather than chart-datum depths.

At 64 tested coastal sites with accepted continuous reference series, median RMS error was 5.3% of tidal range (6.5 cm), with 44 sites within 10%. This sample is not globally representative, and local errors can be much larger. The calculation implementation was separately checked against pyTMD at 179,360 instants, with maximum difference below 1 mm; that is implementation agreement, not model accuracy.

Coverage depends on EOT20 and the mapped coastline. Small harbours and fjords can be unsupported. EOT20 has no Caspian coefficients. The app distinguishes being too far inland from missing model coverage.

Polar tide cells are omitted: latitude coverage is **60°S–72°N**, retaining mainland northern Norway and Cape Horn. The global shoreline is retained for orientation on the globe.

## Use

Open TideLine and find the **Location** section below Tide details. Choose **Current location** or **Specific location**. Search coastal towns by name and select a result showing its region and country, or open **Enter coordinates** for an exact point. The globe centres on the selected point. Both modes have editable names (default **Current** when following); **Show location name on widget** adds the name in bold beside the update time/height. Long names are shortened to fit. Search and the globe work offline without location permission. Add the widget through the app or the launcher's widget picker. Each widget retains its own location, forecast duration and appearance. Tapping a widget opens its own settings; **Save widget** applies the settings and closes the screen.

The widget refreshes approximately every 15 minutes; Android can delay updates during sleep or battery saving. It is not a live clock. The centred time and height identify its last calculation. Open the app or tap **Refresh** for an immediate calculation.

Background following is off by default. Enabling it shows a disclosure before Android permission setup. Only approximate location is requested; Android 10+ needs separate background access for following. Updates use occasional network/passive system fixes, and only the latest fix is retained locally. Fixed locations and foreground updates work without background permission.

Tide data is used within 30 km of a stored tide cell, with a marine-path check to avoid selecting data across islands. Beyond 30 km the widget shows **Too far from coast**. Tide details shows the calculated cell distance. Widget configuration opens directly to Tide details; the widget selector and preview appear when starting the app itself.

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

If the phone has a Play/release installation, build the debug and test APKs with `-PtidelineIsolatedTest=true`. They use `app.tideline.testing`, leaving the installed app's widgets and settings available. Run that copy with `adb shell am instrument --user 0 -w -r app.tideline.testing.test/app.tideline.WidgetInstrumentation`.

## Bundled data

The build includes the real **26,895,584-byte** `data/packs/tideline-global.tpk`. Its retained coastal EOT20 harmonic values are encoded losslessly. Polar exclusion removes 4,049,545 bytes from the previous pack without changing any retained coefficient bits. The pack also contains global coastline geometry and astronomy tables for 2020–2040. Its compressed tiles are read directly from the uncompressed Android asset; normal builds require no additional tide-data download.

The app also bundles a 2.39 MB gzip index of 39,803 GeoNames towns within 30 km of retained tide cells, with alternate names and country/region information. The index is not a complete list of beaches or settlements; town centres may not have reachable marine support. Tide coverage is checked after selection. The 17.9 kB globe mask uses the same public-domain Natural Earth ocean geometry as the tide pack. No map or search service is contacted.

To rebuild these optional display/search assets, download `cities1000.zip` and `admin1CodesASCII.txt` from `https://download.geonames.org/export/dump/` into `data/geonames/`, then run `python tools/build_place_index.py` and `python tools/build_globe_map.py` with the existing model geometry and Natural Earth inputs. Normal builds use the bundled assets.

To rebuild the pack, install the pinned Python packages from `requirements.lock.txt` in a virtual environment and run `python tools/task.py data`. This explicitly downloads the approximately 2.33 GB upstream archive. [Data and software notices](docs/DATA_NOTICES.txt) describe sources, licences and conversions.

## Privacy and licences

[Privacy policy](PRIVACY.md) · [Data and software notices](docs/DATA_NOTICES.txt) · [Apache-2.0 licence](LICENSE)

The privacy policy and licence notices are bundled for offline reading. The app's **View on GitHub** buttons use these stable repository URLs:

- [Privacy policy](https://github.com/erikhardlyworking/tideline/blob/main/PRIVACY.md)
- [Data and software notices](https://github.com/erikhardlyworking/tideline/blob/main/docs/DATA_NOTICES.txt)

A standalone HTML copy of the policy is maintained in `docs/site/`. Regenerate it locally with `python3 tools/render_privacy.py`; the repository links above provide the policy and notices on GitHub.

For support or privacy inquiries, email workinghardlyforyou@gmail.com. Bugs can also be reported through the repository's Issues page. Avoid putting private location details in public issues.

[More apps by this developer on Google Play](https://play.google.com/store/apps/dev?id=6256987868491351341).

Original application code: Apache-2.0. EOT20 and GeoNames data: CC BY 4.0. Natural Earth shoreline: public domain. Astronomical reference: pyTMD, MIT. Full notices and licence texts are retained in the source and app.
