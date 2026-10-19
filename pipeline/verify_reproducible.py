"""Rebuild packs in an isolated temporary directory and compare every hash."""
import datetime, hashlib, json, pathlib, tempfile
import build

root=pathlib.Path(__file__).resolve().parents[1]
original=json.loads((root/'data/packs/manifest.json').read_text(encoding='utf8'))
with tempfile.TemporaryDirectory(prefix='craglight-repro-',dir=root/'data') as temporary:
    target=pathlib.Path(temporary).resolve()
    assert target.parent == (root/'data').resolve() and target.name.startswith('craglight-repro-')
    build.PACKS=target
    build.run()
    rebuilt=json.loads((target/'manifest.json').read_text(encoding='utf8'))
    checks=[]
    for before,after in zip(original['regions'],rebuilt['regions'],strict=True):
        assert before['id']==after['id']
        for kind,expected in before['sha256'].items():
            actual=after['sha256'][kind]
            checks.append({'region':before['id'],'format':kind,'sha256':actual,'identical':expected==actual})
    extra=['all-crags.json','basemap.geojson','manifest.json','photo-licences.json']
    extra += sorted('photos/'+p.name for p in (root/'data/packs/photos').glob('*') if p.is_file())
    for name in extra:
        expected=hashlib.sha256((root/'data/packs'/name).read_bytes()).hexdigest()
        actual=hashlib.sha256((target/name).read_bytes()).hexdigest()
        checks.append({'file':name,'sha256':actual,'identical':actual==expected})
    report={'verifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'method':'Network-free rebuild from committed curated source records, licensed JPEGs and compact geographic snapshots in isolated temporary output directory. JSON, SQLite, GeoJSON, MBTiles, media ledger and images compared byte-for-byte using SHA-256.','snapshotSha256':hashlib.sha256((root/'data/snapshot.json.gz').read_bytes()).hexdigest(),'curatedSourceSha256':hashlib.sha256((root/'data/curated/source-records.json').read_bytes()).hexdigest(),'checks':checks,'allIdentical':all(c['identical'] for c in checks)}
    (root/'data/reproducibility.json').write_bytes((json.dumps(report,indent=2)+'\n').encode('utf8'))
    if not report['allIdentical']:raise AssertionError('Non-deterministic pack output; see data/reproducibility.json')
    print('Reproducible:',len(checks),'outputs identical',flush=True)
