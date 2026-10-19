# CragLight community service

A separate, deployable FastAPI service backed by persistent SQLite, including normalized image bytes. It implements optional Google sign-in, private drafts, photo uploads, moderated crag guides and galleries, attributable route additions on shared photos, reports, account export/deletion, and contribution credits. It has no feed ranking, direct messaging, ads, location tracking or logbook sync.

**Current state:** implemented and locally tested; no public service or real Google login has been deployed. Google project creation was rejected by automatic approval review before a resource was created. An official read-only Cloudflare CLI check found no active login. The Android build must keep community configuration empty until there is a real isolated HTTPS service and real OAuth client ID.

## Run privately on Windows

Python 3.12 is used. From this directory:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\start-private.ps1
```

The launcher protects only this directory's `.state` folder with a current-user/SYSTEM ACL, then binds to `127.0.0.1:8765`. It does not install a service, scheduled task, tunnel or firewall rule. `/health` reports `googleConfigured:false` and sign-in returns 503 until configured. Run with `-GoogleClientId 'ACTUAL_WEB_CLIENT_ID.apps.googleusercontent.com'` only after creating the separate clients described in [DEPLOYMENT.md](DEPLOYMENT.md). This ID is public configuration; there is no Google client secret in the app or this backend.

The private state contains `community.sqlite`, its WAL files and `identity.secret`. Keep the secret with the database across restarts; changing it breaks stable account lookup. Never put state, backups, session tokens or private uploads in Git. A persistent dedicated host and protected backups are required. SQLite/media storage has a 1 GiB application cap; no storage bill is automatically enabled.

## Verify

```powershell
New-Item -ItemType Directory -Path .state -Force | Out-Null
.\.venv\Scripts\python.exe -m pytest -q --basetemp=.state/test-run
```

Use a dedicated test directory: pytest owns and may clear its `--basetemp` target. On a restrictive Windows sandbox, Python's local event-loop socket pair may require a normal local process. Tests create temporary databases and use locally signed JWT fixtures, not a live Google account or a public server. See [VERIFICATION.md](VERIFICATION.md) for the verified results and [API.md](API.md) for the Android contract.

## Moderation operation

An operator grants moderator status only to an existing CragLight account using the local private database:

```powershell
.\.venv\Scripts\python.exe manage.py moderator ACTUAL_USER_UUID
.\.venv\Scripts\python.exe manage.py moderator ACTUAL_USER_UUID --revoke
.\.venv\Scripts\python.exe manage.py status
```

This is an explicit administrative action, not an HTTP registration option. Moderators authenticate through the same real Google flow. There is no shared moderator password or token in the repository. A moderator cannot review their own submission or profile.

- `GET /v1/moderation/drafts` lists pending submissions. `POST /v1/moderation/drafts/{id}` accepts `{decision:'approve'|'reject'|'changes_requested',reason,evidenceUrl?,rightsChecked,safetyChecked,accessChecked,completenessChecked}`. Approval requires every attestation and the complete publication gate. A failed check leaves the draft pending; incomplete proposals are never silently published.
- `GET /v1/moderation/profiles` lists requested aliases and optional Google profile photos. `POST /v1/moderation/profiles/{id}` takes `{decision:'approve'|'reject',approveAlias,approvePhoto,reason}`. Only a photo explicitly requested by that account may become its avatar.
- `GET /v1/moderation/reports` lists pending reports without public reporter disclosure. `POST /v1/moderation/reports/{id}` takes `{action:'dismiss'|'hide',reason}`. Hiding a photo also withdraws dependent guides/gallery entries. Use this while investigating privacy, copyright, illegal-content or access reports.

Review is substantive: inspect each photo, check image ownership/reuse credit and route plausibility, distinguish historical access from current access, reconcile duplicate crag identities, and preserve uncertainty. Structural completeness is not an assessment of climbing safety. No automated score, upload count or Google identity grants authority. Accepted credit is limited to one member/crag/kind combination; moderation still rejects duplicate/spam submissions.

Operate a staffed reporting and appeal process before launch; source safety notices do not replace one. [UGC_POLICY.md](UGC_POLICY.md) explains the publication rules and UK launch review.

## Retention and recovery

Run `manage.py prune` daily as an explicit scheduled operation when the service is deployed. It deletes expired sessions (seven days), challenges (five minutes), expired rate buckets, inactive draft/rejected/change-requested submissions after 180 days, orphan unpublished photos after 30 days, resolved reports after 90 days and review audit entries after one year. Pending review submissions/reports remain until an operator resolves them; monitor aging queues. Published contributions and active accounts remain until withdrawn/deleted. **No scheduler is installed yet**, so these maintenance windows are an operating requirement, not a claim that unattended deletion is already running.

`manage.py backup .state/backups/UNIQUE_DATE.sqlite` makes a consistent database snapshot, refusing overwrite. Protect backup ACLs and copy `identity.secret` separately through the operator's secure backup process. Do not retain backups beyond 30 days. On restore, replay deletion/withdrawal actions made since that backup before reopening the service. Account deletion immediately removes active private records and uploads, revokes sessions and withdraws public dependencies; old public exports/caches cannot be recalled. The release host must document its real logging, backup and incident procedures before collecting personal data.

## Design boundaries

Google's library validates cryptographic signature, issuer, audience and expiry; a single-use nonce binds the token to this service's challenge. Only an HMAC of the stable Google subject is kept. Email and real name are discarded. Sessions are random opaque secrets stored as hashes. Optional Google-photo fetches use only a signed-token photo URL on an exact Google image-host allowlist, reject redirects, and undergo the same bounded decode/re-encode process.

JPEG/PNG input is limited to 8 MiB and 12 megapixels; animated/invalid inputs are rejected, orientation is applied, maximum edge becomes 2400 pixels and a fresh RGB JPEG discards metadata. Original pixels may still contain identifiable people or visible sensitive information, so moderation/privacy reports remain necessary. JSON sizes, route points, object counts, per-account uploads and action rates are bounded. No content is executed as HTML. No CORS origin or cookie-based session is enabled. The default launcher suppresses access logging and does not trust forwarded headers; configure the final proxy deliberately rather than trusting arbitrary headers.

Dependencies are pinned in `requirements.txt`. Original implementation is under the repository Apache-2.0 licence; uploaded contributions have their own CC BY-SA 4.0 terms. Third-party packages retain their upstream licences; none grants rights to user-submitted images.
