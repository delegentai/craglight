# Play Store listing draft

## App name

CragLight

## Short description

Explore UK crags with open maps, offline packs, a logbook and sun forecasts.

## Full description

Find your next day outside with CragLight, a free climbing guide built from openly licensed UK data.

Browse mapped crags and routes, search by name, and filter by climbing style, grade, rock type, region, walk-in and recorded family suitability. Keep a personal logbook with ascent style, date and notes, then export your ticks to CSV.

Save regional packs for offline maps, paths and parking. Choose a starting location or a mapped car park to estimate a walk-in where a connected path is available. Plan for sunlight using crag aspect and solar position, and check Open-Meteo weather and a clearly labelled drying estimate. Saved forecasts retain their timestamp when you go offline.

Open data is incomplete. Some crags have no route list, grades, access information or photographic topo. Unknown details stay clearly marked. Contribute your own observations through a moderated review process to help improve coverage.

CragLight is UK-only, with no ads, subscriptions, paywalls or required account. Your logbook stays on your device. Location is optional.

Climbing and access conditions change. This guide is not a substitute for local access advice, route judgement or navigation skills. Mapped parking may be unverified; a missing restriction is not permission to climb. Sun and drying calculations are estimates.

Contains OpenStreetMap data under ODbL, OS Open Names under the Open Government Licence, and Open-Meteo weather under CC BY 4.0. Source attribution is included in the app.

## Console choices

- Package: `org.craglight.app`; category: Sports; price: free; ads: no.
- Distribution: United Kingdom for v1. Android 8.0+ (min API 26), target API 36.
- No login is required to browse, install packs, log ticks or queue a contribution. Sending a public GitHub issue may require a separate browser login and is optional.
- App access instructions: unrestricted, no credentials required.
- Publisher: use the existing developer account only by creating a new app entry. Existing legal publisher identity may be displayed by Play; do not add that identity to the app's branding unless the store legally requires it.
- Content rating and target audience: complete the Play questionnaire accurately for an outdoor sport reference tool; no user messaging or auto-published user content exists inside the app.

## Data safety review draft

Review these answers against the exact release and current Play form before submitting. The app has no analytics or advertising SDK. Logbook/notes/contributions and device location stay local unless the user explicitly exports or submits a proposal. Android backup is disabled. Network services receive IP/technical request data; weather requests contain the selected public crag coordinate. GitHub contributions are an optional, user-directed browser submission, not an in-app account.

Do not simply claim “no data collected” without evaluating Play's definitions and these third-party transfers. Optional foreground location is used only for app functionality on-device. The app requests no background location, contacts, broad storage or advertising ID permissions.

## Still required for Play publication

1. Create a separate new Play Console app for this package under the existing developer account.
2. Set a real publisher support email and private privacy-request contact. Add it to the privacy policy; publish that policy at a permanent public HTML URL.
3. Upload the signed AAB, choose Play App Signing and protect the separate CragLight upload key. Do not reuse another app's key.
4. Upload the generated icon, feature graphic and real app screenshots; complete content rating, target audience, data safety and app-access declarations.
5. Complete any testing or verification requirements shown by the account, review the pre-launch report, and submit the new app for review.

No existing Play app, project, listing or credential has been changed. A store review, developer identity verification and any account-specific testing gates cannot be represented as completed by a local build.

## Policy references checked

- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
