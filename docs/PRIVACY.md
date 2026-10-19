# CragLight privacy policy

Draft updated: 19 October 2026. App: CragLight (`org.craglight.app`). The optional community service described below is implemented but not publicly deployed; its final controller/contact and hosting details remain launch prerequisites.

CragLight is a free UK climbing guide with no advertising, analytics, payment processing or background location tracking. Browsing, offline guides and your private logbook do not require an account. The optional community contribution workflow is designed to use a separate Google sign-in account only when a real community service is configured. Until then, drafts remain local and the app must not describe them as uploaded.

## Data on your device

Your logbook, ascent dates, notes, saved forecasts, installed regional packs and unsent contributions are stored in the app's private Android storage. CragLight does not upload your logbook. Android cloud backup is disabled for this app. Deleting a tick or queued contribution removes it from the app's local database. Uninstalling the app removes this local data. Export a CSV or review-queue backup first if you want to keep it. Exported files remain wherever you chose to save them and must be deleted there separately.

## Optional location

The app asks for location permission only when you choose **Use my location**. Location is used on your device to sort climbing places or estimate an approach. It is not added to your logbook or sent to a CragLight server. You can decline permission, revoke it in Android settings, choose mapped parking, or enter a starting coordinate instead.

## Network services

- **Weather:** choosing **Refresh weather** sends the selected public crag's coordinates, the requested weather fields and your network IP address to Open-Meteo. It does not send your current location, ticks or notes. Forecasts are cached on-device, and repeat refreshes within 30 minutes reuse the cache. Open-Meteo processes requests under its [terms and privacy policy](https://open-meteo.com/en/terms), which describes technical logs and retention. Forecast requests are not made in the background.
- **Regional packs:** choosing **Save offline** contacts GitHub's release-download service, which can receive your IP address and technical request information. A bundled copy is available if the network download is unavailable. No logbook or location is included. GitHub's [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement) applies to its service.
- **Maps:** map geometry and label assets are rendered locally. CragLight does not request public raster tiles, third-party map glyphs or a tracking-enabled map service.
- **External links:** opening an OpenStreetMap, UKC, licence or other external page uses your browser. The destination's privacy practices apply. External climbing pages are not scraped or copied into the app.

## Optional community account and submissions

Local community drafts may contain your chosen alias, crag/parking coordinates, photographs, drawn route lines, descriptions and access observations. These are not private logbook uploads. Sending them requires a configured real server, sign-in, an explicit action and the contribution-licence confirmation. A confirmed server response distinguishes a remote pending submission from a local-only draft.

The implemented server verifies a Google ID token and one-time nonce. It stores a random account ID and a keyed identifier derived from Google's stable account subject; it discards Google email and real name. Its own login sessions expire after seven days and are stored as hashes on the server. Google processes the sign-in under its own terms. A custom public alias is optional; the default is a random pseudonym. Copying your Google profile photo is a separate optional choice. Custom aliases/profile photos require review before public display. Sign-in minimisation does not make these records anonymous or remove data-protection duties.

Server uploads start private. JPEG/PNG photos are decoded, oriented and re-encoded, removing embedded EXIF/GPS/text metadata. Visible faces, number plates and other details in the pixels remain; choose photos you have rights to share and avoid unnecessary personal information. Moderators can inspect private submissions to assess rights, content, access information and completeness. Approved original climbing/crag photos and captions may appear in the separate gallery; a complete public guide needs additional route, approach and descent information. Reusing an approved photo retains its original attribution. Public contributions use CC BY-SA 4.0; that licence does not authorise uploading someone else's protected work.

Public profiles show the pseudonym, approved optional photo and limited accepted-contribution credits. No credit is awarded for merely uploading or submitting. Reports are received privately by moderators and their submitter is not published. The service uses short-lived keyed abuse-limit identifiers and may receive technical network information through its eventual HTTPS host. The actual provider, proxy logs, lawful bases, international transfers and private contact route must be documented before launch; none is silently treated as configured today.

The older **Open review form** option opens a GitHub issue page in your browser. It is a separate service: review before submitting, and remember that your GitHub profile and issue may be public. Deleting a local draft or community account does not delete a GitHub issue you separately posted.

## Community access, deletion and retention

The implemented account export returns profile information, private drafts, accepted records, upload metadata and submitted reports; normalized photos can be downloaded through authenticated image endpoints. Account deletion revokes all sessions, removes active private drafts/uploads, withdraws dependent public content and removes/anonymises public credit. Routes and guide text already incorporated into another contributor's reviewed guide may remain under the contribution licence, with your attribution anonymised; your uploaded photos and publications depending on them are withdrawn. Limited non-public moderation/withdrawal records may remain with account links removed; retained reports may require individual privacy review under the retention policy. It cannot recall previously downloaded public content or exported copies. Report a photo/privacy concern for review even if you are not its uploader.

The service's proposed operational policy is seven-day sessions, five-minute login challenges, expired abuse-limit buckets, 180-day inactive/rejected drafts, 30-day orphan unpublished photos, 90-day resolved reports, one-year moderation audit and backups no older than 30 days. Pending cases require active operator review rather than indefinite unattended queues. The cleanup command is implemented, but its deployment schedule and real backup/logging processes are not installed yet. Account deletion applies immediately to the active database; backups must be protected, age out and have deletion records replayed before restoration. See the [community operation and retention guide](../community/README.md).

## Contact and requests

Use the [CragLight project contact and issue page](https://github.com/delegentai/craglight/issues) for app questions, source corrections and privacy requests that contain no private information. Do not post personal or sensitive data in a public issue. A dedicated private publisher contact address must be added to this policy and the Play listing before Play Store submission; this document is a release-candidate policy draft until that address is supplied.

The currently delivered service has no live public community accounts because it is not deployed. If enabled later, it will maintain the optional community data described above, separately from your private logbook. For device-local data, use the app's delete/export controls or Android's clear-storage/uninstall controls. The [community launch policy](../community/UGC_POLICY.md) records further privacy and online-safety work required before public launch; this draft is not a completed legal-compliance assessment.

Changes to this policy will be recorded in the public project history with a new effective date.
