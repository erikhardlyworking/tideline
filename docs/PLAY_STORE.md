# Google Play publication files

Prepared for TideLine 0.2.3 (`app.tideline`, version code 7). This package prepares an English listing, native graphics, real app screenshots, a release bundle and declaration drafts. Nothing has been submitted to Play Console.

## Files to use

| Play Console field | File |
| --- | --- |
| App name | `fastlane/metadata/android/en-US/title.txt` |
| Short description | `fastlane/metadata/android/en-US/short_description.txt` |
| Full description | `fastlane/metadata/android/en-US/full_description.txt` |
| Release notes | `fastlane/metadata/android/en-US/changelogs/7.txt` |
| Store icon, 512 × 512 RGBA PNG | `fastlane/metadata/android/en-US/images/icon.png` |
| Feature graphic, 1024 × 500 RGB PNG | `fastlane/metadata/android/en-US/images/featureGraphic.png` |
| Four phone screenshots, RGB PNG, 9:16 | `fastlane/metadata/android/en-US/images/phoneScreenshots/` |
| Data safety / app-content answers | `publishing/DATA_SAFETY.md` |
| Background-location answers and video script | `publishing/BACKGROUND_LOCATION.md` |
| Contact details and remaining publisher choices | `publishing/console-details.json` |
| Privacy policy | `PRIVACY.md`, also rendered as `docs/site/privacy.html` |
| Data and software notices | `docs/DATA_NOTICES.txt` |

The metadata directory follows [fastlane supply's format](https://docs.fastlane.tools/actions/supply/); it also works as a simple set of files for manual Console upload. No service-account credentials or upload automation are required. The title, descriptions, release notes, PNG colour modes, dimensions and checksums are checked by:

```sh
python3 tools/prepare_play_release.py --check-only
```

Graphics follow the [Play preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en). Screenshots come from MainActivity on the connected phone at a fixed Tregde demonstration location. They capture app content only and clip the excess tall-phone viewport to 9:16 without stretching or UI overlays. The feature graphic uses the actual tide renderer and real EOT20 predictions; the icon renders the existing Android vector. See `publishing/asset-provenance.txt`. No tablet/TV/Wear screenshots are claimed.

To regenerate on an authorized, unlocked USB phone with an existing TideLine debug install (JDK 17 / Android SDK 36):

```sh
python3 tools/capture_play_assets.py
```

This updates the debug/test APKs and opens a temporary fixed-location demo widget; it does not grant permissions or replace existing widget settings. Keep the phone unlocked. The test APK contains the capture code; it is not part of the release bundle.

## Build and sign

```sh
./gradlew :tide-core:test :app:lintRelease :app:bundleRelease
python3 tools/prepare_play_release.py
```

The AAB is `app/build/outputs/bundle/release/app-release.aab`. Without upload credentials it is **unsigned and cannot be uploaded**. The packager labels it `UNSIGNED`, copies it with the listing and declarations into `dist/play/`, and creates a ZIP and checksum manifest. That unsigned package is useful for local review. File paths inside the ZIP mirror the source checkout; run build/capture commands in the source checkout, not in the extracted review package. Existing output snapshots are preserved; move an old output directory before rerunning the same version/status.

To prepare the uploadable bundle:

1. Use your protected upload keystore, or create one in Android Studio's **Generate Signed Bundle / APK → Android App Bundle** flow. Choose a location outside this repository and back it up. This work has not created a production key or selected your Play App Signing identity.
2. Copy `publishing/key.properties.example` to the project root as `key.properties`, and enter the four real values locally. The actual file and keystores are ignored by Git. Java properties escape backslashes, so use forward slashes in Windows paths.
3. Rebuild and require signature verification:

```sh
./gradlew :tide-core:test :app:lintRelease :app:bundleRelease
python3 tools/prepare_play_release.py --require-signed
```

Alternatively set all four environment variables: `TIDELINE_STORE_FILE`, `TIDELINE_STORE_PASSWORD`, `TIDELINE_KEY_ALIAS`, `TIDELINE_KEY_PASSWORD`. Partial signing configuration fails; release never falls back to a debug key. Do not put passwords on a command line or in Git. The packager verifies a present JAR signature, rejects the usual Android debug certificate, records the public upload-certificate fingerprint and copies no signing secrets. Google Play still checks the certificate against the identity registered in your account. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

The validator checks the release package/version, non-debug manifest, target API 36, absence of Internet/fine-location permissions, exact bundled model/policy/notices and ZIP integrity. There are no native `.so` libraries in the current bundle. Review target API and any new Console requirements when submitting: [target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).

## Finish in Play Console

- Create/select `app.tideline`, English (United States), app name TideLine. Suggested category: Weather. Confirm the package ID before the first upload.
- Use workinghardlyforyou@gmail.com as the public support email. Choose pricing, distribution countries and intended age groups in `publishing/console-details.json`. Complete the IARC questionnaire in Console; this package does not assign a content rating. There are no ads, accounts, purchases or login restrictions in the current app.
- Upload the signed AAB to an internal/closed test track with Play App Signing. Complete any account-specific testing requirements before production.
- Upload the listing text, icon, feature graphic and four phone screenshots above.
- Make the privacy policy publicly readable and test its URL while signed out. The repository remains deliberately **private during testing**. Intended repository URL: https://github.com/erikhardlyworking/tideline/blob/main/PRIVACY.md . A private link is insufficient for submission. The optional manual Pages workflow can instead host `docs/site/privacy.html` once configured.
- Complete Data safety and app-content declarations using the worksheets after reviewing the final app/dependencies. Current local processing does not send user data off-device. See the [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- Finish the real approximate/background-location journey tests (allow/deny/revoke, following off, travel, disabled provider, reboot and battery saving). Record the disclosure, permission flow and working background widget for the [background-location review](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en), and add its accessible video URL. The draft script is provided; no review video or Play approval is claimed.

The generated `release-report.json` states signing status and outstanding publisher steps. Creating a local package never publishes the app or changes repository visibility.
