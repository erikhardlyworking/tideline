# Background-location declaration draft

Copy the relevant answers into the current Play Console permission form. Review against the final binary. This has not been submitted or approved.

## App purpose

TideLine is an offline home-screen tide forecast widget. It calculates an approximate astronomical tide curve for a coastal location, with high/low predictions and a detailed full-day graph.

## Feature that needs background location

Optional automatic location following keeps the tide widget relevant to the user's coast as they move, without requiring them to open the app. Only widgets with following explicitly enabled use occasional approximate system location updates while the app is closed. Tide predictions are calculated locally. No location is uploaded or retained as a travel history.

## Why foreground access is insufficient for that feature

A home-screen widget is normally viewed without opening its settings app. Foreground-only location would leave an opted-in moving user's widget at an earlier coast until they open TideLine. Background access enables that specific automatic-following feature. Users can instead choose a fixed location or foreground refresh and can decline or disable background access.

## Exact prominent disclosure in the app

> TideLine accesses your approximate location in the background, including when the app is closed, to move your tide widget with you. Only the latest location is saved on this device. It is not sent to us or shared for advertising. Updates are occasional and can be delayed by Android. You can turn following off at any time; fixed locations and manual refresh still work.

This disclosure is shown by the **Follow movement in background** switch before setup, with **Not now** / **Continue** actions. Android 11+ then directs the user to the system's app location-permission screen. TideLine requests coarse and background location, with no fine-location permission.

## Reviewer route and video script

Use a clean test device/profile without personal notifications or private coordinates. Record the real app, not a mock-up. Suggested short recording:

1. Open TideLine, tap **Set location**, select **Current location**, and grant approximate foreground location if prompted.
2. Enable **Follow movement in background**. Hold on the full disclosure, show **Continue**, and show the actual Android permission route to allow background location. Do not cut out the disclosure or permission flow.
3. Add a home-screen widget, leave the app, and show its tide curve and timestamp. Demonstrate a genuine change of coastal location and the resulting widget update while the app remains closed. Android may delay updates; describe any time cut honestly. A test/mocked location, if used, must be identified, never presented as a real journey.
4. Return to TideLine, show the latest fix/time, turn following off, and show that a fixed-location widget still works.

Supply the review-accessible video URL requested by Play Console in `console-details.json`. No review video is claimed to exist in this package. Background access may still be rejected by Play review even with this declaration. If it is removed for a foreground-only release, remove the permission and feature and update the policy/listing/declarations together.

Before submission, also test denied/revoked background permission, location disabled, reboot, battery saving, travel and stopping following. Existing automated fix-filtering checks do not replace this journey.

Source: [Google Play background-location review](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en), checked 30 October 2026.
