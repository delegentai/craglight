"""Local, auditable moderation queue. Standard library only; never makes network requests."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import sqlite3
import sys
from urllib.parse import urlsplit

PROJECT_ROOT = Path(__file__).resolve().parents[1]
LICENSES = {
    "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
    "CC-BY-4.0": "https://creativecommons.org/licenses/by/4.0/",
    "CC-BY-SA-4.0": "https://creativecommons.org/licenses/by-sa/4.0/",
    "ODbL-1.0": "https://opendatacommons.org/licenses/odbl/1-0/",
    "OGL-UK-3.0": "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/",
}
ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
MAX_IMPORT_BYTES = 5_000_000
MAX_IMPORT_RECORDS = 1_000


class ValidationError(ValueError):
    pass


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def identifier(value: object, name: str) -> str:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise ValidationError(f"{name} must be a nonempty safe identifier (maximum 128 characters)")
    return value


def text(value: object, name: str, maximum: int, required: bool = True) -> str:
    if not isinstance(value, str) or len(value) > maximum or (required and not value.strip()):
        raise ValidationError(f"{name} must be text, {'nonempty, ' if required else ''}maximum {maximum} characters")
    if any(ord(char) < 32 and char not in "\n\r\t" for char in value):
        raise ValidationError(f"{name} contains control characters")
    return value


def public_url(value: object, name: str, required: bool = True) -> str:
    value = text(value, name, 2048, required)
    if not value and not required:
        return ""
    if any(char.isspace() for char in value):
        raise ValidationError(f"{name} contains whitespace")
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        port = parsed.port
    except ValueError as error:
        raise ValidationError(f"{name} is not a valid URL") from error
    if parsed.scheme not in {"https", "http"} or not host or parsed.username or parsed.password:
        raise ValidationError(f"{name} must be an HTTP(S) URL without credentials")
    if port not in {None, 80, 443}:
        raise ValidationError(f"{name} must use the default HTTP(S) port")
    if host.lower() == "localhost" or host.lower().endswith((".localhost", ".local", ".internal", ".invalid")):
        raise ValidationError(f"{name} must refer to a public evidence location")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if "." not in host or not re.fullmatch(r"[A-Za-z0-9.-]+", host):
            raise ValidationError(f"{name} has an invalid public hostname")
    else:
        if not address.is_global:
            raise ValidationError(f"{name} must not refer to a private or local address")
    return value


def timestamp(value: object, name: str) -> str:
    value = text(value, name, 64)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValidationError(f"{name} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValidationError(f"{name} must include its timezone")
    return value


def safe_output(path: Path) -> Path:
    result = path.resolve()
    if not result.is_relative_to(PROJECT_ROOT):
        raise ValidationError("Queue and export outputs must remain inside the independent CragLight project")
    return result


def load_json(path: Path) -> dict:
    if path.stat().st_size > MAX_IMPORT_BYTES:
        raise ValidationError("Import exceeds the 5 MB limit")

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValidationError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValidationError(f"Invalid JSON number: {value}")

    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValidationError("Input must be valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValidationError("Import must be a JSON object")
    return value


def validate_submission(value: object) -> dict:
    if not isinstance(value, dict):
        raise ValidationError("Each contribution must be an object")
    result = {
        "id": identifier(value.get("id"), "id"),
        "cragId": identifier(value.get("cragId"), "cragId"),
        "cragName": text(value.get("cragName"), "cragName", 300),
        "category": text(value.get("category"), "category", 100),
        "proposal": text(value.get("proposal"), "proposal", 20_000),
        "license": text(value.get("license"), "license", 40),
        "createdAt": timestamp(value.get("createdAt"), "createdAt"),
    }
    if result["license"] not in LICENSES:
        raise ValidationError(f"Unrecognized open licence: {result['license']!r}; do not infer permission")
    result["sourceUrl"] = public_url(value.get("sourceUrl", ""), "sourceUrl", required=result["license"] != "CC0-1.0")
    if value.get("status", "queued_on_device") != "queued_on_device":
        raise ValidationError("Imports must be queued_on_device proposals; sender-supplied review decisions are never trusted")
    result["status"] = "queued_on_device"
    return result


class Queue:
    def __init__(self, path: Path):
        self.path = safe_output(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS proposals (
                id TEXT PRIMARY KEY, payload TEXT NOT NULL, digest TEXT NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('pending','approved','rejected')),
                imported_at TEXT NOT NULL, imported_from TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS decisions (
                proposal_id TEXT PRIMARY KEY REFERENCES proposals(id), state TEXT NOT NULL,
                reviewer TEXT NOT NULL, reason TEXT NOT NULL, decided_at TEXT NOT NULL,
                evidence_url TEXT NOT NULL, attribution TEXT NOT NULL, rights_verified INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS audit (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT, proposal_id TEXT NOT NULL,
                action TEXT NOT NULL, at TEXT NOT NULL, details TEXT NOT NULL
            );
        """)

    def close(self):
        self.db.close()

    def import_export(self, path: Path, received_via: str) -> dict:
        received_via = text(received_via, "received-via", 2048)
        export = load_json(path)
        if type(export.get("schemaVersion")) is not int or export["schemaVersion"] != 1:
            raise ValidationError("Only schemaVersion 1 exports are supported")
        timestamp(export.get("exportedAt"), "exportedAt")
        submitted = export.get("contributions")
        if not isinstance(submitted, list) or not 1 <= len(submitted) <= MAX_IMPORT_RECORDS:
            raise ValidationError("Import must contain between 1 and 1000 contributions")
        proposals = [validate_submission(value) for value in submitted]
        if len({value["id"] for value in proposals}) != len(proposals):
            raise ValidationError("Duplicate contribution IDs inside one export")
        imported = duplicate = 0
        # Validate the entire batch before making it visible; conflicting IDs roll back all new rows.
        with self.db:
            for proposal in proposals:
                payload = canonical(proposal)
                digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
                previous = self.db.execute("SELECT digest FROM proposals WHERE id=?", (proposal["id"],)).fetchone()
                if previous:
                    if previous["digest"] != digest:
                        raise ValidationError(f"Conflicting content for existing proposal {proposal['id']}; require a new ID")
                    duplicate += 1
                    continue
                at = now()
                self.db.execute("INSERT INTO proposals VALUES (?,?,?,?,?,?)", (proposal["id"], payload, digest, "pending", at, received_via))
                self.db.execute("INSERT INTO audit(proposal_id,action,at,details) VALUES (?,?,?,?)", (proposal["id"], "imported_pending", at, canonical({"sha256": digest, "receivedVia": received_via})))
                imported += 1
        return {"importedPending": imported, "identicalDuplicatesSkipped": duplicate, "published": False}

    def listing(self, state: str = "pending") -> list[dict]:
        if state not in {"pending", "approved", "rejected", "all"}:
            raise ValidationError("Unknown queue state")
        query = "SELECT id,payload,state,imported_at FROM proposals"
        rows = self.db.execute(query + (" ORDER BY imported_at,id" if state == "all" else " WHERE state=? ORDER BY imported_at,id"), () if state == "all" else (state,))
        return [{"id": row["id"], "state": row["state"], "cragName": json.loads(row["payload"])["cragName"], "category": json.loads(row["payload"])["category"], "importedAt": row["imported_at"]} for row in rows]

    def show(self, proposal_id: str) -> dict:
        proposal_id = identifier(proposal_id, "id")
        row = self.db.execute("SELECT * FROM proposals WHERE id=?", (proposal_id,)).fetchone()
        if row is None:
            raise ValidationError(f"Unknown proposal: {proposal_id}")
        decision = self.db.execute("SELECT * FROM decisions WHERE proposal_id=?", (proposal_id,)).fetchone()
        return {"proposal": json.loads(row["payload"]), "state": row["state"], "sha256": row["digest"], "importedAt": row["imported_at"], "receivedVia": row["imported_from"], "decision": dict(decision) if decision else None}

    def decide(self, proposal_id: str, state: str, reviewer: str, reason: str, evidence_url: str = "", attribution: str = "", rights_verified: bool = False) -> dict:
        if state not in {"approved", "rejected"}:
            raise ValidationError("A decision must explicitly approve or reject")
        reviewer = text(reviewer, "reviewer", 128)
        reason = text(reason, "reason", 4000)
        if len(reason.strip()) < 10:
            raise ValidationError("A review reason must explain the decision (at least 10 characters)")
        attribution = text(attribution, "attribution", 4000, False)
        with self.db:
            # Lock before checking state so concurrent reviewers cannot overwrite each other.
            self.db.execute("BEGIN IMMEDIATE")
            existing = self.show(proposal_id)
            if existing["state"] != "pending":
                raise ValidationError("This proposal already has a final decision; create a new proposal to revise it")
            if state == "approved":
                if not rights_verified:
                    raise ValidationError("Approval requires the explicit --rights-verified assertion after manual source review")
                evidence_url = public_url(evidence_url, "evidence-url")
                if existing["proposal"]["license"] != "CC0-1.0" and not attribution.strip():
                    raise ValidationError("This licence requires an attribution record before approval")
            elif evidence_url:
                evidence_url = public_url(evidence_url, "evidence-url")
            at = now()
            self.db.execute("INSERT INTO decisions VALUES (?,?,?,?,?,?,?,?)", (proposal_id, state, reviewer, reason, at, evidence_url, attribution, int(rights_verified)))
            self.db.execute("UPDATE proposals SET state=? WHERE id=?", (state, proposal_id))
            self.db.execute("INSERT INTO audit(proposal_id,action,at,details) VALUES (?,?,?,?)", (proposal_id, state, at, canonical({"reviewer": reviewer, "reason": reason, "evidenceUrl": evidence_url, "rightsVerified": rights_verified})))
        return self.show(proposal_id)

    def export_approved(self, output: Path) -> dict:
        output = safe_output(output)
        if output == self.path:
            raise ValidationError("Export cannot overwrite the queue database")
        if output.exists():
            raise ValidationError("Output already exists; choose a new versioned filename")
        records = []
        for row in self.listing("approved"):
            value = self.show(row["id"])
            value["licenseUrl"] = LICENSES[value["proposal"]["license"]]
            records.append(value)
        exported = {"schemaVersion": 1, "kind": "reviewed-proposals-not-a-data-pack", "generatedAt": now(), "automaticPackMerge": False, "proposals": records}
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8", newline="\n") as destination:
            json.dump(exported, destination, ensure_ascii=False, indent=2, allow_nan=False)
            destination.write("\n")
        return {"approvedProposals": len(records), "output": str(output), "published": False, "publicPacksModified": False}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=PROJECT_ROOT / "moderation/private/queue.sqlite")
    commands = parser.add_subparsers(dest="command", required=True)
    importer = commands.add_parser("import", help="Import an app JSON export as pending proposals")
    importer.add_argument("input", type=Path)
    importer.add_argument("--received-via", required=True, help="Issue URL or local receipt description; no automatic submission")
    listing = commands.add_parser("list")
    listing.add_argument("--state", choices=["pending", "approved", "rejected", "all"], default="pending")
    commands.add_parser("show").add_argument("id")
    for name in ("approve", "reject"):
        decision = commands.add_parser(name)
        decision.add_argument("id")
        decision.add_argument("--reviewer", required=True)
        decision.add_argument("--reason", required=True)
        decision.add_argument("--evidence-url", default="")
        decision.add_argument("--attribution", default="")
        if name == "approve":
            decision.add_argument("--rights-verified", action="store_true")
    commands.add_parser("export").add_argument("output", type=Path)
    args = parser.parse_args(argv)
    queue = None
    try:
        queue = Queue(args.db)
        if args.command == "import":
            result = queue.import_export(args.input, args.received_via)
        elif args.command == "list":
            result = queue.listing(args.state)
        elif args.command == "show":
            result = queue.show(args.id)
        elif args.command in {"approve", "reject"}:
            result = queue.decide(args.id, "approved" if args.command == "approve" else "rejected", args.reviewer, args.reason, args.evidence_url, args.attribution, getattr(args, "rights_verified", False))
        else:
            result = queue.export_approved(args.output)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
        return 0
    except (ValidationError, OSError, sqlite3.Error) as error:
        print(f"Moderation error: {error}", file=sys.stderr)
        return 2
    finally:
        if queue:
            queue.close()


if __name__ == "__main__":
    raise SystemExit(main())
