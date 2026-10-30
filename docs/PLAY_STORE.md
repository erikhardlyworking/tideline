# Google Play preparation

Prepared 30 October 2026 for TideLine 0.2.3 (`app.tideline`, version code 7). This prepares source, a policy, listing text and build outputs; it does not submit the app to Google Play or assert store approval.

## Bundle and signing

Build with JDK 17 and Android SDK platform 36/build-tools 35.0.0:

```sh
./gradlew :tide-core:test :app:lintDebug :app:bundleRelease
```

The output is `app/build/outputs/bundle/release/app-release.aab`. Without an explicitly configured release signing key this bundle is unsigned. Use your own protected upload key and Play App Signing before uploading; do not use the debug key. Keep keystores and passwords out of Git. The package has no native `.so` libraries; its data reader uses the platform JVM/zlib. Confirm the application ID and final listing/developer identity in Play Console before the first upload.

## Privacy and Data safety

The canonical policy is `PRIVACY.md`, bundled in the app and rendered by `tools/render_privacy.py` into `docs/site/`. Both in-app document dialogs offer a **View on GitHub** button:

- Privacy policy: https://github.com/erikhardlyworking/tideline/blob/main/PRIVACY.md
- Data and software notices: https://github.com/erikhardlyworking/tideline/blob/main/docs/DATA_NOTICES.txt

The repository is intentionally private during testing. Before submitting to Play, make the policy publicly accessible and verify its URL while signed out. After making the repository public, its policy URL above can be used as the repository-hosted link. A standalone HTML policy is also prepared in `docs/site/`; the manual Pages workflow can publish it after Pages is configured. Intended HTML URL: https://erikhardlyworking.github.io/tideline/privacy.html .

For the current code, no user data is transmitted off-device by the app: there is no Internet permission, analytics, ad SDK, account or developer backend. Approximate location is accessed and the latest fix is saved locally; a fixed coordinate may be entered manually. The system location provider has its own device/OS settings. Describe this consistently in the policy and permission declarations. Data safety's collection questions distinguish off-device transmission from local processing; review the final binary and all dependencies before submitting the answers. No account-deletion endpoint is needed because TideLine has no accounts. Clearing app storage or uninstalling deletes local data.

Google requires an accessible privacy policy and accurate Data safety disclosures: [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).

## Background-location review

The optional following feature uses `ACCESS_COARSE_LOCATION` and `ACCESS_BACKGROUND_LOCATION`; it does not request fine location. Following is off by default. Before background permission setup, the primary location screen displays a dedicated disclosure explaining access while closed, the widget-following purpose, local storage and how to stop it. Approximate access suffices. Android 11+ uses the app's Permissions → Location settings to choose background access; refusing leaves other modes usable.

Complete the Play Console background-location declaration and provide the requested demonstration video showing the actual disclosure, permission flow and a widget following changed location. Record it on an Android device before submission. Explain the core benefit: the home-screen tide display remains relevant to the user's coast as they move without opening the app. Google reviews whether background access is justified; optional availability does not guarantee approval. [Google Play background-location requirements](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en).

## Release tasks owned by the publisher

- Verify the public policy URL and final contact/developer details.
- Test the installed app with approximate-only location, background access allowed/denied/revoked, following switched off, fixed locations, travel, provider disabled, reboot and battery saving. The new callback's filtering is testable independently, but a real movement/reboot journey is still required.
- Sign the AAB with the protected upload key, then use an internal/closed test track and any account-specific testing requirements shown by Play Console.
- Supply final icon, feature graphic and representative screenshots; use real predictions and disclose approximation/weather exclusions in listing text.
- Complete Data safety, app access, ads, content rating, target audience and background-location declarations according to the final app and intended audience.

The app targets API 36, meeting the published new-app target requirement as checked on 30 October 2026. Verify Play Console's requirements at submission: [target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).
