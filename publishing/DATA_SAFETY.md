# Data safety and app content worksheet

Draft answers for the current `app.tideline` binary. These are copy/reference notes for Play Console, not an import file or a submitted declaration. Review again if code or SDKs change.

| Console topic | Answer supported by the current app |
| --- | --- |
| Data collection / sharing | No user data is transmitted off-device by TideLine. No data types collected or shared under the form's off-device definition. |
| Location | Approximate location is accessed on-device. A manually entered coordinate may be precise. Neither is transmitted by TideLine. Only the latest fix is retained, not a movement history. |
| Ads / advertising ID | No ads or advertising ID use. |
| Analytics, crash SDKs, accounts, payments | None. |
| Encryption in transit | No app data transfers to describe; follow the form's branching rather than claiming encryption for nonexistent transfers. |
| Account creation / deletion | No accounts. No account-deletion web endpoint. Clearing storage or uninstalling deletes local preferences and fixes. |
| App access | No login, subscription, access code or restricted membership. Use a fixed coastal coordinate without location permission, e.g. Tregde: 58.006377, 7.554759. |
| Background location | Declared in the manifest, used only for explicitly enabled following. Complete the separate location-permission review; “no collection” does not waive it. |
| External links | Privacy/notices and developer's other apps open in another app/browser. Those services operate under their own policies. |

Evidence: `app/src/main/AndroidManifest.xml`, the merged release manifest, `BackgroundLocation.kt`, `LocationAccess.kt`, `WidgetSettings.kt`, `PRIVACY.md` and the dependency list. The release validator rejects Internet/fine-location permissions and unexpected debug signing. Platform location services have their own device settings; they are not a TideLine backend.

The privacy policy is bundled in the app. Its web URL must be public before submitting. The repository is deliberately private during testing. The public support email is workinghardlyforyou@gmail.com. Review the target audience, pricing, distribution countries and content-rating questionnaire in your account; these publisher choices have not been invented or submitted.

References, checked 30 October 2026: [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
