"""Refresh software notices from resolved artifacts without changing curated data credits.

Run after lint has produced its dependency model. This performs no Gradle task or
network request. Google SDK AARs embed additional notices using byte offsets in
third_party_licenses.json; these are distinct from ordinary LICENSE/NOTICE files.
"""
from pathlib import Path
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/licenses"
CACHE = Path("C:/DevTmp/craglight-tools/gradle-home/caches/modules-2/files-2.1")
MODEL = ROOT / "app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-libraries.xml"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NOTICE = re.compile(r"(^|/)(LICENSE|NOTICE|COPYING|COPYRIGHT)(\.[a-z0-9_-]+)?$", re.I)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def add_notice(groups, body, origin, name=None):
    # Decode strictly, and preserve the vendor's full notice text unchanged.
    text = body.decode("utf-8")
    entry = groups.setdefault(digest(body), {"text": text, "origins": set(), "names": set()})
    entry["origins"].add(origin)
    if name:
        entry["names"].add(name)


def render_notices(header, groups):
    parts = [header]
    for _, entry in sorted(groups.items(), key=lambda pair: sorted(pair[1]["names"] or pair[1]["origins"])[0]):
        parts += ["\n" + "=" * 64]
        if entry["names"]:
            parts += ["Vendor component names: " + ", ".join(sorted(entry["names"]))]
        parts += ["\n".join(sorted(entry["origins"])), "", entry["text"]]
    return "\n".join(parts).encode("utf-8")


def main():
    protected = {name: digest((OUT / name).read_bytes()) for name in (
        "photo-credits.txt", "data-credits.txt", "os-open-names-licence.txt",
    )}
    libraries = sorted({item.get("resolved") for item in ET.parse(MODEL).getroot()})
    inventory = [
        "CragLight third-party software", "",
        "Resolved Android dependency snapshot, including optional debug tooling. Release builds omit debug-only tools. Versions below are taken from the current build model.", "",
        "AndroidX and the open-source components identify their licence below. Apache 2.0, MapLibre/native BSD notices, JetBrains notices and Public Suffix List MPL 2.0 remain available in the separate offline entries.", "",
        "Google Identity and Google Play services are licensed under the Android Software Development Kit License, not Apache 2.0. Their bundled third-party open-source notices and embedded SDK terms are reproduced in the Google SDK third-party notices entry.",
        "Google SDK terms: https://developer.android.com/studio/terms.html",
        "Google notice guidance: https://developers.google.com/android/guides/opensource", "",
    ]
    ordinary, google = {}, {}
    google_artifacts = []
    for coordinate in libraries:
        group, name, version = coordinate.split(":")
        directory = CACHE / group / name / version
        poms = list(directory.rglob("*.pom"))
        if not poms:
            raise ValueError(f"Missing resolved metadata: {coordinate}")
        pom = ET.parse(poms[0]).getroot()
        licences = pom.findall("m:licenses/m:license", NS)
        licence_names = [item.findtext("m:name", namespaces=NS) for item in licences]
        licence_urls = [item.findtext("m:url", namespaces=NS) for item in licences]
        source = pom.findtext("m:scm/m:url", namespaces=NS) or pom.findtext("m:url", namespaces=NS)
        inventory += [coordinate, "Licence: " + (", ".join(filter(None, licence_names)) or "See bundled original vendor notices")]
        inventory += ["Licence URL: " + url for url in dict.fromkeys(filter(None, licence_urls))]
        if source:
            inventory += ["Source: " + source]
        inventory += [""]

        archives = list(directory.rglob("*.aar")) + list(directory.rglob("*.jar"))
        for archive in archives:
            if "-sources" in archive.name:
                continue
            with zipfile.ZipFile(archive) as zipped:
                containers = [(archive.name, zipped)]
                inner = None
                if archive.suffix == ".aar" and "classes.jar" in zipped.namelist():
                    inner = zipfile.ZipFile(io.BytesIO(zipped.read("classes.jar")))
                    containers.append(("classes.jar", inner))
                for container_name, container in containers:
                    for member in container.namelist():
                        if NOTICE.search(member) and not member.lower().endswith(".class"):
                            add_notice(ordinary, container.read(member), f"{coordinate}: {container_name}!/{member}")
                if inner:
                    inner.close()

                if "third_party_licenses.json" in zipped.namelist():
                    raw = zipped.read("third_party_licenses.txt")
                    spans = json.loads(zipped.read("third_party_licenses.json"))
                    google_artifacts.append(coordinate)
                    for component, span in spans.items():
                        start, length = span["start"], span["length"]
                        if start < 0 or length <= 0 or start + length > len(raw):
                            raise ValueError(f"Invalid vendor notice span: {coordinate}/{component}")
                        add_notice(google, raw[start:start + length],
                            f"{coordinate}: {component}; {archive.name}!/third_party_licenses.txt bytes {start}–{start + length - 1}", component)

    if not google:
        raise ValueError("Expected Google SDK notice records were not found; dependency model may be stale.")
    updates = {
        "software-inventory.txt": ("Resolved software inventory", "\n".join(inventory).encode("utf-8"), "https://github.com/delegentai/craglight"),
        "artifact-notices.txt": ("Original AAR and JAR notices", render_notices(
            "Original licence and NOTICE text in resolved public AAR/JAR files. Identical bytes appear once; every artifact association is retained. Google SDK embedded third-party notices have a separate entry.\n", ordinary), ""),
        "google-sdk-third-party-notices.txt": ("Google SDK third-party notices and SDK terms", render_notices(
            "Notices shipped in the exact resolved Google Identity and Google Play services AARs. Text is extracted using each vendor's own byte-offset index. Identical text is reproduced once, retaining all component and artifact associations. Vendor component labels are preserved and are not claims that each component is separately used by CragLight.\n\nSDK licence: https://developer.android.com/studio/terms.html\nNotice guidance: https://developers.google.com/android/guides/opensource\n", google), "https://developers.google.com/android/guides/opensource"),
    }
    index = json.loads((OUT / "index.json").read_text(encoding="utf-8"))
    sources = json.loads((OUT / "sources.json").read_text(encoding="utf-8"))
    index = [entry for entry in index if entry["file"] not in updates]
    sources = [entry for entry in sources if entry["file"] not in updates]
    for filename, (title, body, source) in updates.items():
        (OUT / filename).write_bytes(body)
        index.append(dict(title=title, file=filename, sourceUrl=source))
        sources.append(dict(file=filename, sourceUrl=source, bytes=len(body), sha256=digest(body)))
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "sources.json").write_text(json.dumps(sources, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert all(digest((OUT / name).read_bytes()) == value for name, value in protected.items())
    print(json.dumps({"resolvedArtifacts": len(libraries), "ordinaryNoticeTexts": len(ordinary),
        "googleArtifacts": sorted(set(google_artifacts)), "googleDistinctNoticeTexts": len(google),
        "updatedFiles": {name: {"bytes": len(body), "sha256": digest(body)} for name, (_, body, _) in updates.items()},
        "curatedCreditsUnchanged": True}, indent=2))


if __name__ == "__main__":
    main()
