"""Package public release documentation and store artwork, never signing material."""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[1]
release = root / "release"
release.mkdir(exist_ok=True)
documents = [
    "README.md", "LICENSE", "SOURCES.md", "DECISIONS.md", "DATA_AUDIT.md",
    "docs/ARCHITECTURE.md", "docs/DATA_RELEASE.md", "docs/GRADING.md",
    "docs/MODERATION.md", "docs/PRIVACY.md", "docs/QA.md", "docs/RELEASE_NOTES.md",
    "docs/SIGNING.md", "docs/STORE_LISTING.md", "docs/THIRD_PARTY_NOTICES.md",
    "docs/RELEASE_BUNDLE_VALIDATION.md",
    "data/photos.json", "data/LICENSE.txt", "data/os-open-names-licence.txt",
]
files = [root / name for name in documents]
files += sorted(p for p in (root / "store-assets").rglob("*") if p.is_file())
assert all(p.is_file() for p in files), "Finish all release documents before packaging"
assert len(list((root / "store-assets" / "screenshots").glob("*.png"))) >= 4, "Real app screenshots required"
archive = release / "CragLight-1.0.0-publishing-kit.zip"
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in files:
        output.write(path, path.relative_to(root).as_posix())
binaries = [release / "CragLight-1.0.0.apk", release / "CragLight-1.0.0.aab", archive]
assert all(p.is_file() for p in binaries), "Build and verify the signed APK and AAB first"
(release / "SHA256SUMS.txt").write_text(
    "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in binaries),
    encoding="ascii", newline="\n",
)
print(f"Packaged {len(files)} public files; SHA256SUMS.txt covers APK, AAB and publishing kit.")
