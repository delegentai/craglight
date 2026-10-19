# Community API v1

The implementation is in `community/`, independent of the offline catalogue. A deployed HTTPS base URL and a newly configured CragLight Google web client ID are required. Blank configuration disables sign-in; a local test service is not a published community.

JSON uses camelCase. Dates are UTC ISO-8601. Authentication is `Authorization: Bearer <opaque token>`, never a Google access token. No cookies are used. Error responses contain `detail`; validation errors may be structured. JSON request bodies are limited to 512 KiB, image uploads to 8 MiB, and unknown fields are rejected. Merged published documents are limited to 750000 bytes. Crag/gallery/draft list responses fit approximately 2.5 MB and include `nextOffset` (null when finished); pass that value with `offset` for the next page. Account export is a separate potentially larger download.

1. `GET /v1/auth/challenge` → `{challengeId,nonce,expiresAt}`. Set the Google Credential Manager request nonce to exactly this nonce.
2. `POST /v1/auth/google` with `{idToken,challengeId,publicAlias?,useGooglePhoto:false,termsAccepted:true,licenceAccepted:true}` → `{token,expiresAt,profile}`. Google ID token audience must equal the configured web client ID. Nonce is single-use. No Google email or real name is stored. A custom alias and optional Google photo await moderation before becoming public.
3. `GET /v1/me`, `PATCH /v1/me` with `{publicAlias?,removePhoto?:true}`, and `POST /v1/auth/logout`. Profile has `{id,alias,pendingAlias,photoUrl,acceptedContributions}`. Default alias is a random pseudonym. The account/session endpoint never returns Google subject or email.
4. `POST /v1/photos` takes a raw JPEG/PNG body and `X-Photo-Id: <UUID>`, max 8 MiB and 12 million decoded pixels. Server validates/decodes, applies EXIF orientation, resizes to max 2400 pixels per side and writes a fresh JPEG without metadata. Return `{id,width,height,sha256,status:'private'}`. Keep returned hash/dimensions for the draft. `GET /v1/photos/{id}` requires owner/moderator auth unless the image has an approved public use. Upload alone never publishes it.
5. `PUT /v1/drafts/{UUID}` stores the document below; `GET /v1/drafts` and `GET /v1/drafts/{UUID}` recover actual status after timeouts. An accepted/rejected/pending submission cannot be overwritten; copy to a fresh draft for a revision. `DELETE /v1/drafts/{UUID}` removes an unaccepted private draft.
6. `POST /v1/drafts/{UUID}/submit` → `{id,status:'pending',missing:[...]}`. Incomplete proposals are accepted into review. Crag identity, ownership/rights consent and some authored content are required. Submission never creates public content or credits. Duplicate retries return current status.
7. `GET /v1/crags` / `GET /v1/crags/{id}` return only approved complete guides. `GET /v1/gallery?cragId=...` returns approved original photos/captions; a gallery photo does not establish a complete guide.
8. `GET /v1/profiles/{id}` returns public pseudonym/approved photo and accepted contribution count. One credit per member, crag and contribution kind; photo count and rejected proposals do not add credits.
9. `POST /v1/reports` with `{targetType:'crag'|'photo'|'profile'|'gallery',targetId,reason,details?}`. An account is optional; rate limiting applies. No reporter identity is exposed publicly.
10. `GET /v1/me/export` returns account, drafts, accepted records, upload metadata and submitted reports. `DELETE /v1/me` with `{confirmation:'DELETE'}` removes private data, revokes all sessions, deletes uploads, withdraws dependent publications and anonymises credit on others' surviving contributions. Only a non-public anonymised withdrawal record is retained; prior public copies cannot be recalled. Locally exported copies are outside server control.

## Draft document

```json
{
  "id": "UUID",
  "kind": "guide",
  "basePublicationId": null,
  "crag": {"name": "", "existingId": null, "lat": null, "lon": null},
  "parking": null,
  "parkingUnknownReason": "",
  "approach": "",
  "descent": "",
  "access": "",
  "accessCheckedAt": null,
  "photos": [],
  "routes": [],
  "contributorAlias": "",
  "licence": "CC-BY-SA-4.0",
  "consent": false,
  "caption": ""
}
```

`kind` defaults to `guide`; `gallery` is for a moderated original crag/climbing photo and caption. Optional `parking` is `{lat,lon,description}`. Photos are `{id,width,height,sha256,originalWork:true}`. Routes are `{id,name,gradeSystem,grade,style,description,photoId,lines:[[{x,y}]]}`. Grade systems are `ukTrad`, `ukTech`, `french`, `font`, `v`; style is `trad`, `sport`, `boulder` or `mixed`. Points are normalized top-left coordinates on the server-normalized photo. Multiple separate polylines are allowed. Coordinates and empty fields are allowed in a private draft. The authenticated account determines ownership/credit; `contributorAlias` cannot impersonate another account.

A public guide requires a named UK crag and coordinates; at least one owned original or approved CC photo; at least one named, graded route with descriptive prose traced on an attached photo; authored approach and descent text; parking coordinates with start guidance or an explicit unknown/unavailable explanation; access notes and observation date; and rights consent. A human moderator must also attest rights, completeness, access/currency checks and content safety. Passing structural checks does not establish climbing safety or legal access. Gallery approval has its own smaller gate and never publishes a crag guide. `ukTrad` accepts combined native grades such as `HS 4b` and `E1 5a` without cross-system conversion.

## Referencing a shared photo and amending a guide

Reference an already approved public CC BY-SA 4.0 photo using its existing `id`, exact server `width`, `height` and `sha256`, with `originalWork:false`. Do not re-upload it or claim its authorship. The server checks its active approved use and resolves its original contributor/source itself. Private, hidden or mismatched images are rejected. New uploads require `originalWork:true`.

For a route addition to an existing approved guide, set `basePublicationId` to its current UUID and send new route IDs with normalized lines. Blank approach/descent/access/parking fields inherit the approved base; existing routes and photos are retained. The server gates the merged guide, preserves photo, route and text authorship, and rejects stale bases or attempts to overwrite an existing route ID. Approval creates a new revision and supersedes the former revision atomically. A shared gallery photo without a complete guide may be used in a partial proposal, but publication still needs a complete guide.

Public list envelopes are `{crags:[...],nextOffset}` and `{gallery:[...],nextOffset}`. Each record is flat (draft fields plus `publishedAt`, `superseded`, `contributor:{id,alias}`, `photoAttributions`, `routeAttributions` and `sectionAttributions`). Photo attribution is `{photoId,contributorId,alias,licence,sourcePublicationId,sourceUrl}`. Route attribution uses `routeId`. `sectionAttributions` is an object keyed by nonempty `crag`, `approach`, `descent`, `access`, `accessCheckedAt`, `parking`, `parkingUnknownReason` and `caption`; each value is a list of `{contributorId,alias,licence,sourcePublicationId,sourceUrl}`. Unchanged inherited sections retain their original credit; a changed section retains prior authors and adds the amendment author once. All attribution is server-generated, never accepted from draft input. Report-driven alias removal and account deletion also sanitize these section credits. `/v1/publications/{id}` is the stable public provenance endpoint for either kind and for superseded revisions. Hidden/withdrawn content returns 404.

Accepted contribution counts reflect distinct member/crag/kind credits. Moderators must reconcile aliases and duplicate crag identities; this is not an automatic reputation or safety certification system.

Moderator endpoints and CLI are documented in `README.md`; they cannot be used by ordinary members. No self-approval is permitted.
