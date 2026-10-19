# Optional community contributions

CragLight now includes an independent community-service implementation and Android contribution workflow. **The service is not publicly deployed and real Google sign-in is not configured yet.** Offline drafting remains available; the app must not describe a draft as uploaded until a real server confirms it.

The service supports private photos and route drawings, partial submissions for review, complete-guide publication, separate moderated climbing/crag photo galleries, named contributor credit, additions to an existing approved photo, reporting, export and account deletion. Its data and identity are separate from the offline logbook. Google email and real name are not retained; public pseudonyms are used, and a Google profile photo is optional and reviewed.

Implementation and operational details are in [community/README.md](../community/README.md), the [Android API contract](../community/API.md), [deployment prerequisites](../community/DEPLOYMENT.md), and [content/privacy launch policy](../community/UGC_POLICY.md). The API is implemented with server checks and local tests; that does not imply a hosted community or completed legal launch review.

New Google Cloud project creation was stopped by automatic approval review before creation. A separately controlled stable hostname, isolated host ingress and actual private publisher contact are also pending. No existing business service, credentials, users, domain or data were reused. No temporary tunnel is presented as production.

Account deletion withdraws uploaded photos and dependent publications. Routes/text reused in another reviewed guide may remain with anonymised credit under the contribution licence; see the [privacy policy](PRIVACY.md#community-access-deletion-and-retention).
