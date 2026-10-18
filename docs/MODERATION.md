# Contribution review

CragLight saves improvement proposals on the device. Saving a proposal does not send it to anyone. The app can export a JSON file or open a prefilled GitHub issue form. The contributor must sign in, review the public text and submit it. The app does not infer submission from opening a browser and does not embed a publisher credential.

The intended review queue is the independent repository's [GitHub issues](https://github.com/delegentai/craglight/issues). Use the actual created repository before distributing that link; this document is also usable with locally received exports. No automated issue or comment is sent by the moderation tool.

## Contributor rules

The app's original observations use `CC0-1.0` only after the contributor confirms that the text is their own and that they may release it. A source URL is optional for an original observation, but an empty source is never proof of ownership or accuracy. Do not copy route descriptions, images, access notices or guidebook text from UKC, Rockfax, BMC or any other source without explicit compatible permission. A link to a source is not a licence grant. Personal logbook notes are not contribution records and should not be attached.

The maintainer tool also accepts `CC-BY-4.0`, `CC-BY-SA-4.0`, `ODbL-1.0` and `OGL-UK-3.0` claims with a source URL. These are claims requiring human review, not automatic permission to combine data. A reviewer must check source ownership, licence scope, attribution, share-alike obligations, accuracy and whether redistributing a sensitive location or restriction would be appropriate. An approval records that review; it does not convert the underlying licence or certify climbing safety.

## Local workflow

Run commands from the independent CragLight project with Python 3.11 or newer. No third-party package is needed. The default queue is `moderation/private/queue.sqlite`; it and local exports are ignored by Git. Back up that directory privately. Keep raw receipts and reviewer details out of the public repository.

Import an actual exported file after receiving it from the contributor. Record a real issue URL or receipt description:

```powershell
python -m moderation.queue_cli import moderation/private/received.json --received-via "Local export received for manual review"
python -m moderation.queue_cli list
python -m moderation.queue_cli show PROPOSAL_ID
```

Imports always create pending rows. A sender cannot supply an approval. Duplicate exports with identical IDs and contents are safely skipped; changed content under an existing ID is rejected, and the whole import transaction is rolled back. Ask for a new proposal ID for a revision. Malformed JSON, duplicate keys, invalid IDs, unknown licence claims, unsafe source URLs and missing required fields are rejected with a nonzero exit code.

After independently checking the source and recording evidence, make an explicit decision. Replace the example parameters with the actual proposal ID, reviewer, explanation and public evidence URL. The tool never opens or fetches the URL:

```powershell
python -m moderation.queue_cli approve PROPOSAL_ID --reviewer REVIEWER --reason "Explain the factual and licence checks performed" --evidence-url https://github.com/delegentai/craglight/issues/ISSUE_NUMBER --rights-verified
python -m moderation.queue_cli reject PROPOSAL_ID --reviewer REVIEWER --reason "Explain why the proposal cannot be accepted"
```

For licences other than CC0, approval also requires `--attribution "Author, title, source, licence and modifications"`. The `--rights-verified` flag is a reviewer assertion made after examination, not a permission detector. Approval requires an evidence URL even for an original CC0 observation. Rejection requires a reviewer and explanation. Final decisions cannot be silently overwritten; corrections use a new proposal with a reference to the old one. The database keeps import hashes and decision audit events.

Export approved proposals to a new, versioned local file:

```powershell
python -m moderation.queue_cli export moderation/private/approved-2026-10-18.json
```

The export contains the original proposal, claimed licence, standard licence URL, evidence URL, reviewer, reason, timestamps and source hash. Pending and rejected proposals are excluded. Existing export files are never overwritten. It is explicitly marked `reviewed-proposals-not-a-data-pack`; it does not modify any public pack or publish anything.

## Publishing reviewed corrections

A maintainer must translate each accepted factual correction into an appropriate structured record, preserve attribution and provenance, and review the diff. Prefer correcting OSM through its normal contributor workflow when the observation belongs there and the contributor has the necessary rights. Do not automatically upload proposals to OSM. For a CragLight-specific overlay, check compatibility with the pack's ODbL obligations and the contribution's licence before combining it.

Rebuild a new data version, run ingestion and app checks, review changed records, then publish a new pack release and manifest. Never replace an existing signed or checksummed pack in place. Rejected or pending text must not reach released data merely because it was received or exported.

Run the queue's validation tests with:

```powershell
python -m unittest moderation.test_queue -v
```

This is an operating review workflow with a real local queue. It does not claim that a reviewer is continuously monitoring issues, that any submitted proposal has been accepted, or that opening a form completed a remote submission.
