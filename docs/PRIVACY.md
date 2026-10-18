# CragLight privacy policy

Effective date: 18 October 2026. App: CragLight (`org.craglight.app`).

CragLight is a free UK climbing guide. It has no advertising, analytics, account system, payment processing or background location tracking.

## Data on your device

Your logbook, ascent dates, notes, saved forecasts, installed regional packs and unsent contributions are stored in the app's private Android storage. CragLight does not upload your logbook. Android cloud backup is disabled for this app. Deleting a tick or queued contribution removes it from the app's local database. Uninstalling the app removes this local data. Export a CSV or review-queue backup first if you want to keep it. Exported files remain wherever you chose to save them and must be deleted there separately.

## Optional location

The app asks for location permission only when you choose **Use my location**. Location is used on your device to sort climbing places or estimate an approach. It is not added to your logbook or sent to a CragLight server. You can decline permission, revoke it in Android settings, choose mapped parking, or enter a starting coordinate instead.

## Network services

- **Weather:** choosing **Refresh weather** sends the selected public crag's coordinates, the requested weather fields and your network IP address to Open-Meteo. It does not send your current location, ticks or notes. Forecasts are cached on-device, and repeat refreshes within 30 minutes reuse the cache. Open-Meteo processes requests under its [terms and privacy policy](https://open-meteo.com/en/terms), which describes technical logs and retention. Forecast requests are not made in the background.
- **Regional packs:** choosing **Save offline** contacts GitHub's release-download service, which can receive your IP address and technical request information. A bundled copy is available if the network download is unavailable. No logbook or location is included. GitHub's [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement) applies to its service.
- **Maps:** map geometry and label assets are rendered locally. CragLight does not request public raster tiles, third-party map glyphs or a tracking-enabled map service.
- **External links:** opening an OpenStreetMap, UKC, licence or other external page uses your browser. The destination's privacy practices apply. External climbing pages are not scraped or copied into the app.

## Contributions and moderation

An edit first enters a queue stored only on your device. **Queue for review** does not send it to a maintainer. You can export it to a file or choose **Open review form**, which opens an independent CragLight GitHub issue form in your browser. Review the text before submitting. GitHub may require you to log in; your GitHub profile and submitted text may be publicly visible. Do not include personal information or copied guidebook material. The app contains no GitHub access token.

A moderator checks proposed changes, source evidence and reuse rights before adding anything to a public data release. Deleting a local proposal does not delete an issue you separately submitted on GitHub. Use the issue's controls or contact the project maintainer to request changes to a submitted contribution. Contributions published under an open licence may already have been copied by others.

## Contact and requests

Use the [CragLight project contact and issue page](https://github.com/delegentai/craglight/issues) for app questions, source corrections and privacy requests that contain no private information. Do not post personal or sensitive data in a public issue. A dedicated private publisher contact address must be added to this policy and the Play listing before Play Store submission; this document is a release-candidate policy draft until that address is supplied.

CragLight does not maintain a server-side user account or a copy of your private logbook. For device-local data, the app's delete/export controls and Android's clear-storage/uninstall controls are the direct means of access and deletion.

Changes to this policy will be recorded in the public project history with a new effective date.
