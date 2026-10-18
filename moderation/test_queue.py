from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from moderation.queue_cli import PROJECT_ROOT, Queue, ValidationError, public_url


class ModerationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".test-", dir=PROJECT_ROOT / "moderation")
        self.root = Path(self.temp.name)
        self.queue = Queue(self.root / "queue.sqlite")
        self.proposal = {
            "id": "00ab8ae2-1111-4444-aaaa-01aa00000001", "cragId": "osm:node:123",
            "cragName": "Test fixture crag", "category": "Access", "proposal": "Original observation requiring human review.",
            "sourceUrl": "", "license": "CC0-1.0", "createdAt": "2026-10-18T10:00:00Z", "status": "queued_on_device",
        }

    def tearDown(self):
        self.queue.close()
        self.temp.cleanup()

    def export_file(self, proposals=None):
        path = self.root / "input.json"
        path.write_text(json.dumps({"schemaVersion": 1, "exportedAt": "2026-10-18T11:00:00Z", "contributions": proposals if proposals is not None else [self.proposal]}), encoding="utf-8")
        return path

    def import_one(self):
        return self.queue.import_export(self.export_file(), "local test export")

    def approve(self, **overrides):
        arguments = dict(reviewer="fixture-reviewer", reason="Original source and licence checked manually.", evidence_url="https://github.com/example/fixture/issues/1", rights_verified=True)
        arguments.update(overrides)
        return self.queue.decide(self.proposal["id"], "approved", **arguments)

    def test_import_stays_pending_without_publication(self):
        result = self.import_one()
        self.assertEqual(1, result["importedPending"])
        self.assertFalse(result["published"])
        self.assertEqual("pending", self.queue.show(self.proposal["id"])["state"])
        self.assertEqual([], self.queue.listing("approved"))

    def test_import_cannot_supply_its_own_approval(self):
        self.proposal["status"] = "approved"
        with self.assertRaises(ValidationError):
            self.import_one()
        self.assertEqual([], self.queue.listing("all"))

    def test_reimport_is_idempotent_and_never_resets_decisions(self):
        self.import_one()
        self.approve()
        result = self.import_one()
        self.assertEqual(1, result["identicalDuplicatesSkipped"])
        self.assertEqual("approved", self.queue.show(self.proposal["id"])["state"])

    def test_conflicting_id_rolls_back_the_whole_batch(self):
        self.import_one()
        changed = deepcopy(self.proposal)
        changed["proposal"] = "Changed content must use a new ID."
        new = deepcopy(self.proposal)
        new["id"] = "new-proposal"
        with self.assertRaises(ValidationError):
            self.queue.import_export(self.export_file([new, changed]), "test batch")
        self.assertEqual([self.proposal["id"]], [p["id"] for p in self.queue.listing()])

    def test_approval_requires_source_review_attestation_and_evidence(self):
        self.import_one()
        for kwargs in ({"rights_verified": False}, {"evidence_url": ""}, {"reason": "ok"}, {"reviewer": ""}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValidationError):
                self.approve(**kwargs)
        self.assertEqual("pending", self.queue.show(self.proposal["id"])["state"])

    def test_copyright_attribution_is_required_for_non_cc0(self):
        self.proposal.update(license="CC-BY-4.0", sourceUrl="https://commons.wikimedia.org/wiki/File:Fixture.jpg")
        self.import_one()
        with self.assertRaises(ValidationError):
            self.approve()
        result = self.approve(attribution="Fixture Author, source URL, CC BY 4.0, edited description")
        self.assertEqual("approved", result["state"])

    def test_export_contains_only_reviewed_evidence_and_no_pack_merge(self):
        self.import_one()
        self.approve()
        pending = deepcopy(self.proposal)
        pending["id"] = "pending-second"
        self.queue.import_export(self.export_file([pending]), "second fixture")
        output = self.root / "approved-1.json"
        result = self.queue.export_approved(output)
        exported = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(1, result["approvedProposals"])
        self.assertFalse(result["publicPacksModified"])
        self.assertFalse(exported["automaticPackMerge"])
        self.assertEqual("reviewed-proposals-not-a-data-pack", exported["kind"])
        self.assertEqual(self.proposal["proposal"], exported["proposals"][0]["proposal"]["proposal"])
        self.assertEqual("fixture-reviewer", exported["proposals"][0]["decision"]["reviewer"])
        self.assertIn("creativecommons.org", exported["proposals"][0]["licenseUrl"])
        with self.assertRaises(ValidationError):
            self.queue.export_approved(output)

    def test_rejected_proposal_is_excluded_and_cannot_be_overwritten(self):
        self.import_one()
        self.queue.decide(self.proposal["id"], "rejected", "reviewer", "No evidence supporting the access claim.")
        with self.assertRaises(ValidationError):
            self.approve()
        result = self.queue.export_approved(self.root / "none.json")
        self.assertEqual(0, result["approvedProposals"])

    def test_unrecognized_licences_and_missing_attributed_sources_fail(self):
        for license_value, source in [("all rights reserved", "https://example.org/reference"), ("CC-BY-4.0", ""), ("", "")]:
            with self.subTest(license=license_value):
                self.proposal.update(license=license_value, sourceUrl=source)
                with self.assertRaises(ValidationError):
                    self.import_one()

    def test_urls_cannot_be_local_credentials_or_executable_schemes(self):
        for url in ["javascript:alert(1)", "file:///private/key", "https://user:password@example.org/path", "http://127.0.0.1/x", "http://10.0.0.1/x", "http://[::1]/x", "https://localhost/x", "https://example.org:444/x", "https://example.org/a b"]:
            with self.subTest(url=url), self.assertRaises(ValidationError):
                public_url(url, "evidence")
        self.assertEqual("https://example.org/reference", public_url("https://example.org/reference", "evidence"))

    def test_duplicate_json_keys_nan_and_malformed_documents_fail(self):
        path = self.root / "bad.json"
        for raw in ['{"schemaVersion":1,"schemaVersion":1}', '{"schemaVersion":NaN}', 'not json', '[]']:
            path.write_text(raw, encoding="utf-8")
            with self.subTest(raw=raw), self.assertRaises(ValidationError):
                self.queue.import_export(path, "bad fixture")

    def test_bad_ids_duplicate_ids_and_unzoned_timestamps_fail(self):
        for value in ["../outside", "x'; DROP TABLE proposals; --", "", "x" * 129]:
            proposal = deepcopy(self.proposal)
            proposal["id"] = value
            with self.subTest(value=value), self.assertRaises(ValidationError):
                self.queue.import_export(self.export_file([proposal]), "bad fixture")
        with self.assertRaises(ValidationError):
            self.queue.import_export(self.export_file([self.proposal, self.proposal]), "duplicate fixture")
        self.proposal["createdAt"] = "2026-10-18T10:00:00"
        with self.assertRaises(ValidationError):
            self.import_one()

    def test_outputs_cannot_escape_the_independent_project_or_replace_database(self):
        with self.assertRaises(ValidationError):
            Queue(PROJECT_ROOT.parent / "outside-queue.sqlite")
        with self.assertRaises(ValidationError):
            self.queue.export_approved(PROJECT_ROOT.parent / "outside-export.json")
        with self.assertRaises(ValidationError):
            self.queue.export_approved(self.queue.path)

    def test_import_size_limit_and_unsupported_schema(self):
        path = self.export_file()
        path.write_text(' ' * 5_000_001, encoding="utf-8")
        with self.assertRaises(ValidationError):
            self.queue.import_export(path, "oversize fixture")
        path.write_text('{"schemaVersion":true}', encoding="utf-8")
        with self.assertRaises(ValidationError):
            self.queue.import_export(path, "wrong schema")


if __name__ == "__main__":
    unittest.main()
