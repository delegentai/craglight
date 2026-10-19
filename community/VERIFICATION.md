# Community service verification

Verified locally on Windows with Python 3.12 on 19 October 2026. This verifies the implementation and private launcher; it does not claim a deployed service, live Google account login or completed public-launch review.

## API and security regressions

31 tests passed in 5.49 seconds, with zero failures. The local JUnit report is `test-results.xml` (ignored by Git). From `community/`:

```powershell
.\.venv\Scripts\python.exe -m pytest -q --tb=short --basetemp=.state/tests-final-sections --junitxml=test-results.xml
```

Use a fresh dedicated test directory. In the restricted Windows execution sandbox the Python event loop could not establish its local socket pair; the same tests pass in a normal local process. This is a test-process permission requirement, not a public network dependency. One upstream Starlette warning recommends replacing its deprecated `httpx` test adapter; it did not affect the results.

The tests cover actual Google-library RSA signature validation with local certificate fixtures; issuer, audience and expiry checks; single-use nonces; opaque sessions; omission of Google email/name from stored/exported profile data; pseudonyms and opt-in avatar review; image bounds, orientation and metadata removal; upload and draft ownership; malformed geometry; partial submissions and the complete publication gate; route prose, parking and descent; moderator-only actions and refusal of self-approval; idempotent submission; distinct gallery approval and credit limits; reuse of another contributor's approved photograph; additive route revisions; original and adapted text-section attribution; publication-size rejection without replacing the existing guide; reports; account export/deletion; and alias removal across embedded public credits.

## Private Windows launcher smoke

The supplied launcher successfully served only `127.0.0.1:8766` for the smoke test. `/health` returned `status:ok`, `googleConfigured:false`; the authentication challenge returned a nonce and expiry. The operator `status`, `prune` and SQLite `backup` commands succeeded against an empty isolated state directory. Source compilation succeeded. The foreground process was stopped afterward and the port had no listener. No service, scheduled task, firewall rule, public tunnel or cloud resource was installed.

## External boundaries

Live Google Credential Manager sign-in remains untested because the separate Google project/OAuth clients do not exist. Automatic approval review rejected project creation before a resource was created. The read-only official Cloudflare CLI check found no active login. There is no verified independent public hostname or durable tunnel configuration. Android keeps service configuration empty until these are real. Follow [DEPLOYMENT.md](DEPLOYMENT.md) for the remaining isolated setup and [UGC_POLICY.md](UGC_POLICY.md) for the operational/privacy review before collecting public contributions.
