"""Download public, openly licensed snapshots; no authentication or proprietary content."""
from __future__ import annotations
import argparse, datetime, hashlib, json, pathlib, time, urllib.request, urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
RAW = ROOT / 'data' / 'raw'
URLS = {
    'uk.osm.pbf': 'https://download.geofabrik.de/europe/united-kingdom-latest.osm.pbf',
    'uk.poly': 'https://download.geofabrik.de/europe/united-kingdom.poly',
    'os-open-names.zip': 'https://api.os.uk/downloads/v1/products/OpenNames/downloads?area=GB&format=CSV&redirect',
    'natural-earth-countries.geojson': 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson',
    'openbeta-climbs.parquet': 'https://github.com/OpenBeta/parquet-exporter/releases/download/v2026-10-18/openbeta-climbs.parquet',
}

def public_resolved_url(url: str) -> str:
    """Redirect locations may contain temporary download signatures; publish only the stable path."""
    parts = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, '', ''))

def download(name: str, url: str) -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    dest = RAW / name
    if dest.exists():
        print('cached', name, dest.stat().st_size, flush=True)
        return
    req = urllib.request.Request(url, headers={'User-Agent':'CragLight/0.1 noncommercial open-data offline-pack builder'})
    part = dest.with_suffix(dest.suffix+'.part')
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=60) as response, part.open('wb') as output:
        sha = hashlib.sha256(); size = 0
        while block := response.read(1024*1024):
            output.write(block); sha.update(block); size += len(block)
        headers = dict(response.headers); final_url = response.url
    part.replace(dest)
    metadata = {'url':url,'resolvedUrl':public_resolved_url(final_url),'retrievedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sha256':sha.hexdigest(),'bytes':size,'lastModified':headers.get('Last-Modified'),'etag':headers.get('ETag')}
    dest.with_suffix(dest.suffix+'.source.json').write_bytes((json.dumps(metadata,indent=2)+'\n').encode('utf8'))
    print(name, size, 'bytes', round(time.monotonic()-start,1), 's', flush=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('names',nargs='*',choices=list(URLS));args=parser.parse_args()
    for name in args.names or URLS: download(name, URLS[name])
