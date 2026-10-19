"""Fail-closed publication of reviewed climbing guides from retained open sources."""
from __future__ import annotations
import collections, gzip, hashlib, json, pathlib, re, shutil
from urllib.parse import urlparse
from shapely.geometry import Point, box, shape
from shapely.ops import unary_union
import build

SOURCE = build.ROOT/'data/curated/source-records.json'
GENERATED = '2026-10-19T00:00:00Z'
CC = {'name':'Route descriptions, approaches and annotated topos: Dan and Michel / Multi-pitch.com',
      'url':'https://www.multi-pitch.com/','license':'CC-BY-SA-4.0','licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/'}

def provenance(record):
    return {'source':'multi-pitch.com','sourceId':record['sourceUrl'],'sourceUrl':record['sourceUrl'],
            'version':1,'timestamp':GENERATED,'license':record['license'],'licenseUrl':record['licenseUrl'],
            'author':record['author'],'sourcePageSha256':record['sourcePageSha256'],'checkedAt':record['checkedAt']}

def route_grades(raw):
    match=re.fullmatch(r'(D|Diff|VD|VDiff|S|HS|VS|HVS|E\d+)\s*(\d[a-c])?',raw.strip())
    if not match: raise ValueError('Unsupported source grade: '+raw)
    result={'ukTrad':{'Diff':'D','VDiff':'VD'}.get(match[1],match[1])}
    if match[2]: result['ukTech']=match[2]
    return result

def validate_source(record):
    review=record.get('review',{})
    if review.get('status')!='published' or not all(review.get(k) is True for k in ('imagery','approach','route','location','rights')):
        raise ValueError('Unreviewed record cannot enter public catalogue: '+record.get('name',''))
    for key in ('approachDescription','descentDescription','parkingDescription','coverageScope','routeDescription','routeName','sourceUrl','checkedAt'):
        if not isinstance(record.get(key),str) or not record[key].strip(): raise ValueError('Missing '+key)
    if len(record['routeDescription'])<150 or len(record['approachDescription'])<100: raise ValueError('Incomplete guide prose')
    if any(marker in record['routeDescription'].lower() for marker in ('[à compléter]','todo','placeholder')): raise ValueError('Draft prose')
    if urlparse(record['sourceUrl']).hostname!='www.multi-pitch.com' or record['license']!='CC-BY-SA-4.0': raise ValueError('Unreviewed source rights')
    route_grades(record['sourceStats']['grade'])
    photo=record['photo']
    for key in ('author','license','licenseUrl','sourceUrl','url','assetPath','sha256'):
        if not photo.get(key): raise ValueError('Missing photo attribution: '+key)
    if photo.get('kind')!='annotated-topo' or min(photo['width'],photo['height'])<500: raise ValueError('No useful topo')
    if photo['assetPath'].split('/')[0]!='photos' or '..' in photo['assetPath'].split('/'): raise ValueError('Unsafe photo path')
    asset=SOURCE.parent/photo['sourceFile']
    if hashlib.sha256(asset.read_bytes()).hexdigest()!=photo['sha256'] or asset.stat().st_size!=photo['bytes']: raise ValueError('Photo integrity failure')

def eligible(crag):
    """Public contract, independent of the richer source editor record."""
    return (crag.get('publicationStatus')=='published' and crag.get('qualityVersion')==1
        and all(isinstance(crag.get(k),str) and crag[k].strip() for k in ('coverageScope','approachDescription','descentDescription','parkingDescription','sourceUrl','contentCheckedAt'))
        and any(p.get('kind') in {'annotated-topo','crag-photo'} and p.get('author') and p.get('licenseUrl') and p.get('sha256') for p in crag.get('photos',[]))
        and bool(crag.get('routes')) and all(r.get('name') and r.get('grades') and len(r.get('description') or '')>=150 for r in crag['routes']))

def catalogue(snapshot,output):
    source=json.loads(SOURCE.read_text(encoding='utf8')); elements={(e['type'],e['id']):e for e in snapshot['elements']+snapshot['context']}
    countries={k:shape(v) for k,v in snapshot['countries'].items()}
    crags=[]
    for record in source['records']:
        validate_source(record); p=provenance(record); coords=record['coordinates']; lon,lat=coords['lon'],coords['lat']; location=p
        if record.get('coordinateOverrideOsmId'):
            _,kind,ident=record['coordinateOverrideOsmId'].split(':'); element=elements[(kind,int(ident))]
            lon,lat=build.center(element,elements); location=build.prov(element)
        if not countries[record['country']].covers(Point(lon,lat)): raise ValueError('Outside country boundary: '+record['name'])
        grades=route_grades(record['sourceStats']['grade']); styles=['trad','multipitch']
        if record['name']=='Dinorwig Quarry': styles=['trad','sport','multipitch']
        photo={k:v for k,v in record['photo'].items() if k!='sourceFile'}
        (output/'photos').mkdir(parents=True,exist_ok=True)
        shutil.copyfile(SOURCE.parent/record['photo']['sourceFile'],output/photo['assetPath'])
        raw_stats=record['sourceStats']; omit=record.get('omitNumericSummary',False)
        route={'id':record['id']+':route','name':record['routeName'],'grades':grades,'styles':styles,'description':record['routeDescription'],
            'lengthM':None if omit else build.parse_m(raw_stats.get('length')),'pitches':None if omit else int(raw_stats['pitches']),
            'sourceUrl':record['sourceUrl'],'provenance':p,'geometry':None,'topoImageRef':photo['ref'],'topoPath':None,'rawTags':{}}
        parking=[]
        if record.get('parkingCoordinates'):
            pc=record['parkingCoordinates']
            parking=[{'id':record['id']+':parking','name':record['parkingDescription'],'lat':pc['lat'],'lon':pc['lon'],
                'association':'authored-source','provenance':p,'access':None,'accessConditional':None,'rawTags':{}}]
        c={'id':record['id'],'name':record['name'],'country':record['country'],'lat':lat,'lon':lon,'region':build.region(record['country'],lat),
            'kind':'curated-crag','outdoorStatus':'source-described-outdoor','publicationStatus':'published','qualityVersion':1,
            'coverageScope':record['coverageScope'],'approachDescription':record['approachDescription'],'descentDescription':record['descentDescription'],
            'parkingDescription':record['parkingDescription'],'approachSourceUrl':record['sourceUrl'],'sourceUrl':record['sourceUrl'],
            'contentCheckedAt':record['checkedAt'],'accessNotes':record['accessNotes'],'styles':styles,'grades':grades,
            'gradeRanges':{k:{'min':v,'max':v} for k,v in grades.items()},'description':record['intro'] or record['coverageScope'],
            'rock':raw_stats.get('rock_and_flags'),'aspectRaw':raw_stats.get('aspect'),'aspectDegrees':build.COMPASS.get(raw_stats.get('aspect')),
            'access':None,'accessConditional':None,'restriction':None,'restrictionDescription':None,'openingHours':None,'familyFriendly':None,
            'walkInDistanceM':None,'walkInTimeMin':int(re.search(r'\d+',raw_stats['approach_time'])[0]),
            'osmUrl':location['sourceUrl'] if location['source']=='osm' else None,'ukcUrl':None,'provenance':p,'rawTags':{},
            'fieldProvenance':{'location':location,'route':p,'approach':p,'descent':p,'parking':p,'walkInTimeMin':dict(p,basis='source estimate, not measured')},
            'sourceStats':raw_stats,'routes':[route],'parking':parking,'approaches':[],'parentIds':[],'photos':[photo]}
        if not eligible(c): raise ValueError('Public quality gate failed: '+c['name'])
        crags.append(c)
    crags.sort(key=lambda c:(c['region'],c['name'].casefold(),c['id']))
    return crags,source

def run(skip_tiles=False,output=None):
    output=output or build.PACKS; output.mkdir(parents=True,exist_ok=True)
    snapshot=json.loads(gzip.decompress((build.ROOT/'data/snapshot.json.gz').read_bytes()))
    crags,source=catalogue(snapshot,output)
    # Generated photo directory belongs to this pipeline. Remove obsolete generated
    # media so the public bundle cannot silently retain the old sparse catalogue.
    expected={p['assetPath'] for c in crags for p in c['photos']}
    for old in (output/'photos').glob('*'):
        if old.is_file() and 'photos/'+old.name not in expected: old.unlink()
    _,contexts=build.make_catalogue({'elements':[],'context':snapshot['context']})
    places=build.os_places(); attrs=build.ATTRIBUTIONS+[CC]
    source_hash=hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    manifest={'schemaVersion':1,'catalogueMode':'curated','qualityVersion':1,'generatedAt':GENERATED,
        'sourceSnapshot':{'curatedSha256':source_hash,'osm':snapshot['source'],'osOpenNames':json.loads((build.ROOT/'data/os-places.source.json').read_text())},'attributions':attrs,'regions':[]}
    all_land=[]; context_count=0
    for rid,name in build.REGIONS.items():
        cs=[c for c in crags if c['region']==rid]; land=build.land_features(snapshot,rid); all_land+=land
        # Keep actual path topology around reviewed venues; never publish arbitrary OSM climbing pins.
        buffers=[box(c['lon']-.07,c['lat']-.04,c['lon']+.07,c['lat']+.04) for c in cs]
        zone=unary_union(buffers) if buffers else None
        fs=land+[f for f in contexts[rid] if zone is not None and f['geometry'] and zone.intersects(shape(f['geometry']))]
        context_count+=len(fs)-len(land)
        for c in cs:
            fs.append({'type':'Feature','id':c['id'],'geometry':{'type':'Point','coordinates':[c['lon'],c['lat']]},'properties':{'kind':'crag','id':c['id'],'name':c['name'],'region':rid,'license':c['provenance']['license'],'provenance':c['provenance']}})
            for p in c['parking']:
                fs.append({'type':'Feature','id':p['id'],'geometry':{'type':'Point','coordinates':[p['lon'],p['lat']]},'properties':{'kind':'parking','id':p['id'],'name':p['name'],'association':p['association'],'license':p['provenance']['license'],'provenance':p['provenance']}})
        for p in places:
            if build.region(p['country'],p['lat'])==rid and (p['type'] in {'City','Town'} or (zone is not None and zone.covers(Point(p['lon'],p['lat'])))):
                fs.append({'type':'Feature','id':p['id'],'geometry':{'type':'Point','coordinates':[p['lon'],p['lat']]},'properties':{'kind':'place','name':p['name'],'placeType':p['type'],'provenance':{'source':'os-open-names','sourceId':p['id'],'sourceUrl':p['sourceUrl'],'version':'2026-07','license':'OGL-UK-3.0'}}})
        fs=sorted({f['id']:f for f in fs}.values(),key=lambda f:f['id']); fc={'type':'FeatureCollection','features':fs}; bounds=list(shape(land[0]['geometry']).bounds)
        pack={'schemaVersion':1,'qualityVersion':1,'catalogueMode':'curated','generatedAt':GENERATED,'region':{'id':rid,'name':name},'attributions':attrs,'crags':cs,'map':fc}
        paths={ext:output/f'{rid}.{ext}' for ext in ('json','sqlite','geojson','mbtiles')}
        build.write_json(paths['json'],pack); build.write_json(paths['geojson'],fc)
        build.sqlite_pack(paths['sqlite'],cs,fs,{'schemaVersion':1,'qualityVersion':1,'region':rid,'generatedAt':GENERATED,'attributions':attrs,'sourceSnapshot':manifest['sourceSnapshot']})
        tiles=0 if skip_tiles else build.vector_tiles(paths['mbtiles'],fs,bounds,name)
        files={ext:p.name for ext,p in paths.items() if p.exists() and not (skip_tiles and ext=='mbtiles')}
        entry={'id':rid,'name':name,'bounds':bounds,'cragCount':len(cs),'routeCount':len(cs),'featureCount':len(fs),'tileCount':tiles,'files':files,
            'sha256':{ext:hashlib.sha256(paths[ext].read_bytes()).hexdigest() for ext in files},'bytes':{ext:paths[ext].stat().st_size for ext in files}}
        manifest['regions'].append(entry)
        print('Built curated',rid,len(cs),'venues',len(fs),'features',tiles,'tiles',flush=True)
    build.write_json(output/'manifest.json',manifest)
    build.write_json(output/'all-crags.json',{'schemaVersion':1,'qualityVersion':1,'catalogueMode':'curated','generatedAt':GENERATED,'attributions':attrs,'crags':crags,'map':{'type':'FeatureCollection','features':all_land}})
    build.write_json(output/'basemap.geojson',{'type':'FeatureCollection','features':all_land})
    build.write_json(output/'photo-licences.json',{'photos':[p for c in crags for p in c['photos']]})
    report={'schemaVersion':2,'catalogueMode':'curated','qualityVersion':1,'locations':len(crags),'individualRoutes':len(crags),'licensedPhotos':len(crags),
        'locationsWithPhotos':len(crags),'authoredApproachSites':len(crags),'locationsWithParkingCoordinates':sum(bool(c['parking']) for c in crags),
        'routeTopoPaths':0,'annotatedTopoPhotos':len(crags),'verifiedApproachPaths':0,'osmContextFeatures':context_count,
        'sourceSnapshotSha256':source_hash,'sourceCheckedAt':source['checkedAt'],'reviewScope':source['reviewScope'],'regions':manifest['regions'],
        'excludedCandidates':source['excludedCandidates'],'gaps':['Partial crag coverage: one named climb or explicitly labelled link-up per venue.','No field inspection or guarantee of current access, tides, equipment or conditions.','OSM paths are context, not source-authored approach geometry.','No Scotland seed currently meets the same strict complete-record gate.']}
    build.write_json(build.ROOT/'data/coverage.json',report)
    print('Curated catalogue:',len(crags),'venues; image, authored approach, route and licence gates passed',flush=True)
