"""Assemble the curated data release without credentials or local test state."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / 'data/packs'
target = root / 'release/data-v2'
target.mkdir(parents=True, exist_ok=True)
for path in source.iterdir():
    if path.is_file() and path.suffix in {'.json', '.sqlite', '.geojson', '.mbtiles'}:
        shutil.copyfile(path, target / path.name)
for src, name in [('data/LICENSE.txt', 'DATA_LICENSE.txt'), ('docs/DATA_RELEASE.md', 'DATA_RELEASE.md'),
                  ('SOURCES.md', 'SOURCES.md'), ('app/src/main/assets/licenses/os-open-names-licence.txt', 'os-open-names-licence.txt')]:
    path = root / src
    if not path.is_file():
        raise FileNotFoundError(path)
    shutil.copyfile(path, target / name)
with zipfile.ZipFile(target / 'photos.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((source / 'photos').glob('*.jpg')):
        archive.write(path, 'photos/' + path.name)
    for name in ['photo-licences.json', 'DATA_LICENSE.txt', 'SOURCES.md']:
        archive.write(target / name, name)
    archive.write(root / 'app/src/main/assets/licenses/photo-credits.txt', 'photo-credits.txt')
rows = [f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}'
        for path in sorted(target.iterdir()) if path.is_file() and path.name != 'SHA256SUMS']
(target / 'SHA256SUMS').write_text('\n'.join(rows) + '\n', encoding='ascii')
print(f'Packaged {len(rows)} release files in {target}')
