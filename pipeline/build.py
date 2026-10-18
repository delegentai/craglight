"""Deterministically normalise the licensed snapshot into app JSON/SQLite/MVT packs."""
from __future__ import annotations
import argparse, collections, csv, gzip, hashlib, io, json, math, pathlib, re, sqlite3, zipfile
from pyproj import Transformer
from shapely.geometry import Point, LineString, Polygon, box, mapping, shape
from shapely.ops import transform
import mapbox_vector_tile
from shapely import make_valid
from extract import center, outdoor
from download import public_resolved_url

ROOT=pathlib.Path(__file__).resolve().parents[1]
PACKS=ROOT/'data/packs'
REGIONS={'scotland':'Scotland','wales':'Wales','northern-ireland':'Northern Ireland','england-north':'Northern England','england-midlands':'Midlands and East England','england-south':'Southern England'}
ATTRIBUTIONS=[{'name':'© OpenStreetMap contributors','url':'https://www.openstreetmap.org/copyright','license':'ODbL-1.0','licenseUrl':'https://opendatacommons.org/licenses/odbl/1-0/'},{'name':'Contains OS data © Crown copyright and database right 2026','url':'https://www.ordnancesurvey.co.uk/products/os-open-names','license':'OGL-UK-3.0','licenseUrl':'https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/'}]
ATTRIBUTIONS.append({'name':'Contains Royal Mail data © Royal Mail copyright and database right 2026; Contains National Statistics data © Crown copyright and database right 2026','url':'https://www.ordnancesurvey.co.uk/products/os-open-names','license':'OGL-UK-3.0','licenseUrl':'https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/'})
ATTRIBUTIONS.append({'name':'Made with Natural Earth','url':'https://www.naturalearthdata.com/','license':'Public Domain','licenseUrl':'https://www.naturalearthdata.com/about/terms-of-use/'})
GRADE_KEYS={'ukTrad':['british_adjectival','british','uk_adjectival','uktrad','uk_trad'],'ukTech':['british_technical','uk_technical','uktech','uk_tech'],'french':['french'],'font':['font','fontainebleau'],'v':['hueco','vscale','v']}
COMPASS={'N':0,'NNE':22.5,'NE':45,'ENE':67.5,'E':90,'ESE':112.5,'SE':135,'SSE':157.5,'S':180,'SSW':202.5,'SW':225,'WSW':247.5,'W':270,'WNW':292.5,'NW':315,'NNW':337.5}
PHOTO_PATH=ROOT/'data/photos.json'
PHOTOS=json.loads(PHOTO_PATH.read_text(encoding='utf8'))['photos'] if PHOTO_PATH.exists() else {}
def photo(ref):return PHOTOS.get((ref or '').replace('_',' '))

def compact(v):return json.dumps(v,ensure_ascii=False,separators=(',',':'),sort_keys=True)
def write_json(path,data):path.write_bytes((compact(data)+'\n').encode('utf8'))
def source_id(e):return 'osm:'+e['type']+':'+str(e['id'])
def prov(e):return {'source':'osm','sourceId':source_id(e),'version':e.get('version'),'timestamp':e.get('timestamp'),'license':'ODbL-1.0','sourceUrl':f"https://www.openstreetmap.org/{e['type']}/{e['id']}"}
def region(country,lat):return country if country!='england' else ('england-north' if lat>=53 else 'england-midlands' if lat>=52 else 'england-south')
def truth(v):return True if v=='yes' else False if v=='no' else None
def styles(t):
    return [s for s in ('trad','sport','boulder','toprope','multipitch','deepwater','ice','mixed','aid','dry') if t.get('climbing:'+s) in {'yes','true'} or re.fullmatch(r'[1-9][0-9]*',t.get('climbing:'+s,''))]+(['boulder'] if t.get('climbing')=='boulder' and not t.get('climbing:boulder') else [])
def aspect(t):
    raw=t.get('climbing:orientation') or t.get('direction')
    if raw is None:return None
    raw=raw.strip().upper()
    if raw in COMPASS:return COMPASS[raw]
    try:
        value=float(raw.removesuffix('Â°'))
        return value%360 if math.isfinite(value) and 0<=value<=360 else None
    except ValueError:return None
def grades(t,suffix=''):
    return {system:next((t['climbing:grade:'+key+suffix] for key in keys if 'climbing:grade:'+key+suffix in t),None) for system,keys in GRADE_KEYS.items()}
def haversine(lon1,lat1,lon2,lat2):
    p1,p2=map(math.radians,(lat1,lat2));dp=p2-p1;dl=math.radians(lon2-lon1)
    return 6371008.8*2*math.asin(min(1,math.sqrt(math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2)))
def geometry(e,index=None):
    if 'lat' in e:return {'type':'Point','coordinates':[e['lon'],e['lat']]}
    pts=[[p['lon'],p['lat']] for p in e.get('geometry',[])]
    if len(pts)>=2:
        if len(pts)>=4 and pts[0]==pts[-1] and e.get('tags',{}).get('amenity')=='parking':return {'type':'Polygon','coordinates':[pts]}
        return {'type':'LineString','coordinates':pts}
    c=center(e,index)
    return {'type':'Point','coordinates':list(c)} if c else None
def safe_ukc(t):
    for k in ('climbing:url:ukclimbing','website:ukclimbing','website','url'):
        value=t.get(k,'')
        if re.match(r'^https?://(?:www\.)?ukclimbing\.com/logbook/crags/[A-Za-z0-9_?=&./%+~-]+$',value):return value
    return None
def route(e,index):
    t=e['tags'];c=center(e,index)
    return {'id':source_id(e),'name':t.get('name') or f"Unnamed mapped route ({e['type']} {e['id']})",'lat':c[1] if c else None,'lon':c[0] if c else None,'grades':grades(t),'styles':styles(t),'description':t.get('description:en') or t.get('description'),'lengthM':parse_m(t.get('climbing:length')),'geometry':geometry(e,index),'topoImageRef':t.get('wikimedia_commons'),'topoPath':parse_topopath(t.get('wikimedia_commons:path')),'photo':photo(t.get('wikimedia_commons')),'rawTags':t,'provenance':prov(e)}
def parse_m(value):
    if value is None:return None
    m=re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(m)?\s*',value)
    return float(m[1]) if m else None
def parse_topopath(value):
    if not value:return None
    pts=[]
    for raw in value.split('|'):
        m=re.fullmatch(r'(0(?:\.\d+)?|1(?:\.0+)?),(0(?:\.\d+)?|1(?:\.0+)?)([BAPSU])?(:)?',raw)
        if not m:return None
        pts.append({'x':float(m[1]),'y':float(m[2]),'type':m[3],'dottedAfter':bool(m[4])})
    return pts if len(pts)>=2 else None

def make_catalogue(snapshot):
    index={(e['type'],e['id']):e for e in snapshot['elements']+snapshot['context']}
    retained=[e for e in snapshot['elements'] if outdoor(e['tags']) and e['tags'].get('climbing') not in {'bolt','route_top','abseil_route'}]
    route_elements={source_id(e):e for e in retained if e['tags'].get('climbing') in {'route','route_bottom'}}
    # A route_bottom belonging to a mapped route way is an endpoint, not a second route.
    way_nodes={n for e in route_elements.values() if e['type']=='way' for n in e.get('nodes',[])}
    route_elements={k:e for k,e in route_elements.items() if not(e['type']=='node' and e['id'] in way_nodes and e['tags'].get('climbing')=='route_bottom')}
    parents={};relation_members={}
    for e in retained:
        if e['type']=='relation' and e['tags'].get('climbing') in {'crag','area'}:
            ids=[f"osm:{m['type']}:{m['ref']}" for m in e.get('members',[])]
            relation_members[source_id(e)]=ids
            for member in ids:parents.setdefault(member,[]).append(source_id(e))
    context_by_region=collections.defaultdict(list);parking=[]
    for e in snapshot['context']:
        c=center(e,index)
        if not c:continue
        t=e['tags']
        if t.get('amenity')!='parking' and 'highway' not in t and t.get('natural')!='cliff':continue
        kind='parking' if t.get('amenity')=='parking' else 'path' if 'highway' in t else 'cliff'
        properties={'id':source_id(e),'kind':kind,'name':t.get('name'),'access':t.get('access'),'foot':t.get('foot'),'accessConditional':t.get('access:conditional'),'footConditional':t.get('foot:conditional'),'highway':t.get('highway'),'path':t.get('path'),'surface':t.get('surface'),'sacScale':t.get('sac_scale'),'nodeIds':e.get('nodes',[]),'tags':t,'rawTags':t,'provenance':prov(e)}
        feat={'type':'Feature','id':source_id(e),'properties':properties,'geometry':geometry(e,index)}
        context_by_region[region(e['country'],c[1])].append(feat)
        if kind=='parking':parking.append({'id':source_id(e),'name':t.get('name') or 'Unnamed mapped parking','lat':c[1],'lon':c[0],'access':t.get('access'),'accessConditional':t.get('access:conditional'),'fee':t.get('fee'),'capacity':t.get('capacity'),'provenance':prov(e),'rawTags':t,'geometry':geometry(e,index)})
    crags=[]
    for e in retained:
        ident=source_id(e);t=e['tags'];kind=t.get('climbing') or ('boulder' if t.get('natural') in {'rock','stone'} else 'crag' if t.get('natural')=='cliff' else 'climbing-location')
        if kind in {'route','route_bottom'} and (ident not in route_elements or any(p in relation_members for p in parents.get(ident,[]))):continue
        c=center(e,index)
        if not c:continue
        # OSM relations can repeat a member; one route ID must appear only once per location.
        rids=list(dict.fromkeys(x for x in relation_members.get(ident,[]) if x in route_elements))
        routes=[route(route_elements[x],index) for x in rids]
        if ident in route_elements:routes=[route(e,index)];kind='standalone-route'
        images={p['assetPath']:p for p in [photo(t.get('wikimedia_commons'))]+[r.get('photo') for r in routes] if p}
        near=[]
        members=set(relation_members.get(ident,[]))
        for p in parking:
            if abs(p['lat']-c[1])>.02 or abs(p['lon']-c[0])>.05:continue
            distance=haversine(*c,p['lon'],p['lat'])
            if distance<=1500 or p['id'] in members:near.append(dict(p,association='osm-relation-member' if p['id'] in members else 'nearby-unverified',straightLineDistanceM=round(distance)))
        near.sort(key=lambda x:(x['straightLineDistanceM'],x['id']))
        approaches=[]
        for member in e.get('members',[]):
            x=index.get((member['type'],member['ref']))
            if x and x['tags'].get('path')=='climbing_access':
                approaches.append({'id':source_id(x),'association':'osm-relation-member','geometry':geometry(x,index),'nodeIds':x.get('nodes',[]),'rawTags':x['tags'],'provenance':prov(x)})
        crags.append({'id':ident,'name':t.get('name') or f"Unnamed mapped {kind} ({e['type']} {e['id']})",'lat':c[1],'lon':c[0],'region':region(e['country'],c[1]),'country':e['country'],'kind':kind,'outdoorStatus':'unspecified' if kind=='climbing-location' and t.get('outdoor')!='yes' else 'mapped-outdoor','styles':styles(t),'rock':t.get('climbing:rock'),'aspectDegrees':aspect(t),'aspectRaw':t.get('climbing:orientation') or t.get('direction'),'description':t.get('description:en') or t.get('description'),'access':t.get('access'),'accessConditional':t.get('access:conditional'),'restriction':t.get('climbing:restriction'),'restrictionDescription':t.get('climbing:restriction:time_description'),'openingHours':t.get('opening_hours'),'familyFriendly':truth(t.get('climbing:family_friendly')),'walkInDistanceM':None,'walkInTimeMin':None,'osmUrl':prov(e)['sourceUrl'],'ukcUrl':safe_ukc(t),'provenance':prov(e),'rawTags':t,'grades':grades(t),'gradeRanges':{system:{'min':grades(t,':min')[system],'max':grades(t,':max')[system]} for system in GRADE_KEYS},'routes':routes,'parking':near[:8],'approaches':approaches,'parentIds':parents.get(ident,[]),'photo':next(iter(images.values()),None),'photos':list(images.values()),'photoRef':t.get('wikimedia_commons')})
    crags.sort(key=lambda c:(c['region'],c['name'].casefold(),c['id']))
    return crags,context_by_region

def os_places():
    out=ROOT/'data/os-places.json.gz'
    if out.exists():return json.loads(gzip.decompress(out.read_bytes()))
    z=zipfile.ZipFile(ROOT/'data/raw/os-open-names.zip');fields=z.read('Doc/OS_Open_Names_Header.csv').decode('utf-8-sig').strip().split(',')
    trans=Transformer.from_crs(27700,4326,always_xy=True);records=[]
    for name in sorted(z.namelist()):
        if not name.startswith('Data/') or not name.endswith('.csv'):continue
        with z.open(name) as f:
            for row in csv.DictReader(io.TextIOWrapper(f,encoding='utf-8-sig'),fieldnames=fields):
                if row['LOCAL_TYPE'] not in {'City','Town','Village'}:continue
                lon,lat=trans.transform(float(row['GEOMETRY_X']),float(row['GEOMETRY_Y']))
                country={'England':'england','Scotland':'scotland','Wales':'wales'}.get(row['COUNTRY'])
                if country:records.append({'id':'os:'+row['ID'],'name':row['NAME1'],'lon':lon,'lat':lat,'country':country,'type':row['LOCAL_TYPE'],'sourceUrl':row['NAMES_URI']})
    records.sort(key=lambda p:p['id'])
    out.write_bytes(gzip.compress(compact(records).encode(),mtime=0))
    (ROOT/'data/os-open-names-licence.txt').write_bytes(z.read('Doc/licence.txt').decode('cp1252').replace('\r\n','\n').encode('utf8'))
    metadata=json.loads((ROOT/'data/raw/os-open-names.zip.source.json').read_text())
    metadata['resolvedUrl']=public_resolved_url(metadata['resolvedUrl'])
    write_json(ROOT/'data/os-places.source.json',metadata)
    return records

def land_features(snapshot,region_id):
    country=region_id if not region_id.startswith('england-') else 'england'
    # Administrative boundaries include territorial water; intersect a real land
    # outline instead of rendering the sea as land. Natural Earth is public domain.
    land=shape(json.loads((ROOT/'data/uk-land.geojson').read_text(encoding='utf8'))['features'][0]['geometry'])
    geom=shape(snapshot['countries'][country]).intersection(land)
    if region_id=='england-north':geom=geom.intersection(box(-9,53,3,62))
    elif region_id=='england-midlands':geom=geom.intersection(box(-9,52,3,53))
    elif region_id=='england-south':geom=geom.intersection(box(-9,49,3,52))
    provenance=prov(snapshot['countryProvenance'][country]);provenance['landSource']={'source':'Natural Earth','license':'Public Domain','url':'https://www.naturalearthdata.com/about/terms-of-use/'}
    return [{'type':'Feature','id':'boundary:'+region_id,'properties':{'kind':'land','name':REGIONS[region_id],'provenance':provenance},'geometry':mapping(geom)}]

def sqlite_pack(path,crags,features,meta):
    if path.exists():path.unlink()
    db=sqlite3.connect(path)
    db.executescript('''PRAGMA user_version=1;
    CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE crags(id TEXT PRIMARY KEY,name TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,region TEXT NOT NULL,data_json TEXT NOT NULL);
    CREATE TABLE routes(id TEXT PRIMARY KEY,crag_id TEXT NOT NULL,name TEXT NOT NULL,data_json TEXT NOT NULL);
    CREATE TABLE features(id TEXT PRIMARY KEY,kind TEXT NOT NULL,geojson TEXT NOT NULL);
    CREATE TABLE provenance(record_id TEXT PRIMARY KEY,source TEXT NOT NULL,source_id TEXT NOT NULL,version INTEGER,timestamp TEXT,license TEXT NOT NULL);
    CREATE INDEX crag_region ON crags(region);CREATE INDEX route_crag ON routes(crag_id);
    CREATE VIRTUAL TABLE crag_search USING fts4(id,name,description);
    ''')
    for k,v in sorted(meta.items()):db.execute('INSERT INTO metadata VALUES(?,?)',(k,compact(v)))
    for c in crags:
        db.execute('INSERT INTO crags VALUES(?,?,?,?,?,?)',(c['id'],c['name'],c['lat'],c['lon'],c['region'],compact(c)))
        db.execute('INSERT INTO crag_search VALUES(?,?,?)',(c['id'],c['name'],c['description'] or ''))
        for item in [c]+c['routes']:
            p=item['provenance'];db.execute('INSERT OR IGNORE INTO provenance VALUES(?,?,?,?,?,?)',(item['id'],p['source'],p['sourceId'],p['version'],p['timestamp'],p['license']))
        for r in c['routes']:db.execute('INSERT OR IGNORE INTO routes VALUES(?,?,?,?)',(r['id'],c['id'],r['name'],compact(r)))
    for f in features:db.execute('INSERT OR IGNORE INTO features VALUES(?,?,?)',(f['id'],f['properties']['kind'],compact(f)))
    db.commit();db.execute('VACUUM');db.close()

def tile_xy(lon,lat,z):
    n=2**z;lat=max(-85.05112878,min(85.05112878,lat));return int((lon+180)/360*n),int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)
def tile_bounds(x,y,z):
    n=2**z
    def lat(v):return math.degrees(math.atan(math.sinh(math.pi*(1-2*v/n))))
    return x/n*360-180,lat(y+1),(x+1)/n*360-180,lat(y)
def vector_tiles(path,features,bounds,name):
    if path.exists():path.unlink()
    db=sqlite3.connect(path);db.executescript('CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT);CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row));')
    projection=Transformer.from_crs(4326,3857,always_xy=True).transform
    buckets=collections.defaultdict(list)
    for f in features:
        kind=f['properties']['kind'];g=shape(f['geometry'])
        if not g.is_valid:g=make_valid(g)
        if g.is_empty:continue
        low,high=(4,10) if kind=='land' else (7,12) if kind=='place' else (5,15) if kind=='crag' else (12,15)
        props={k:v for k,v in f['properties'].items() if isinstance(v,(str,int,float,bool))}
        props['id']=f['id'];props['license']='OGL-UK-3.0' if kind=='place' else 'ODbL-1.0'
        west,south,east,north=g.bounds
        for z in range(low,high+1):
            minx,maxy=tile_xy(west,south,z);maxx,miny=tile_xy(east,north,z)
            for x in range(minx,maxx+1):
                for y in range(miny,maxy+1):
                    bb=tile_bounds(x,y,z);clip=g.intersection(box(*bb))
                    if clip.is_empty:continue
                    if kind=='land':clip=clip.simplify(360/(2**z*4096)*2,preserve_topology=True)
                    if clip.geom_type=='GeometryCollection':continue
                    buckets[(z,x,y)].append({'geometry':transform(projection,clip),'properties':props})
    for (z,x,y),items in sorted(buckets.items()):
        west,south,east,north=tile_bounds(x,y,z);a,b=projection(west,south);c,d=projection(east,north)
        raw=mapbox_vector_tile.encode({'name':'craglight','features':items},default_options={'quantize_bounds':(a,b,c,d),'extents':4096})
        db.execute('INSERT INTO tiles VALUES(?,?,?,?)',(z,x,(2**z-1)-y,gzip.compress(raw,mtime=0)))
    metadata={'name':'CragLight '+name,'format':'pbf','type':'overlay','version':'1','description':'Licensed OSM climbing/context and OS Open Names labels; partial detailed path coverage around climbing features.','attribution':'© OpenStreetMap contributors; Contains OS data © Crown copyright and database right 2026','bounds':','.join(map(str,bounds)),'minzoom':'4','maxzoom':'15','json':compact({'vector_layers':[{'id':'craglight','description':'Climbing, land, paths, parking, cliffs, and places','minzoom':4,'maxzoom':15,'fields':{'kind':'String','name':'String','id':'String','license':'String'}}]})}
    db.executemany('INSERT INTO metadata VALUES(?,?)',sorted(metadata.items()));db.commit();count=len(buckets);db.close();return count

def run(skip_tiles=False):
    PACKS.mkdir(parents=True,exist_ok=True)
    snapshot=json.loads(gzip.decompress((ROOT/'data/snapshot.json.gz').read_bytes()))
    crags,context=make_catalogue(snapshot);places=os_places()
    generated=snapshot['source']['retrievedAt'];manifest={'schemaVersion':1,'generatedAt':generated,'sourceSnapshot':{'osm':snapshot['source'],'osOpenNames':json.loads((ROOT/'data/os-places.source.json').read_text())},'attributions':ATTRIBUTIONS,'regions':[]}
    report={'schemaVersion':1,'sourceSnapshot':snapshot['source'],'totalClimbingSourceObjects':len(snapshot['elements']),'locations':len(crags),'individualRoutes':len({r['id'] for c in crags for r in c['routes']}),'locationsWithRoutes':sum(bool(c['routes']) for c in crags),'locationsWithAspect':sum(c['aspectDegrees'] is not None for c in crags),'locationsWithRockType':sum(c['rock'] is not None for c in crags),'locationsWithFamilyTag':sum(c['familyFriendly'] is not None for c in crags),'locationsWithParkingCandidates':sum(bool(c['parking']) for c in crags),'verifiedApproachPaths':sum(len(c['approaches']) for c in crags),'routeLineGeometries':sum(r['geometry'] and r['geometry']['type']=='LineString' for c in crags for r in c['routes']),'routeTopoPaths':sum(r['topoPath'] is not None for c in crags for r in c['routes']),'osmContextFeatures':sum(map(len,context.values())),'osPlaceLabels':len(places),'openBetaUkRows':0,'licensedPhotos':len(PHOTOS),'locationsWithPhotos':sum(bool(c['photos']) for c in crags),'regions':[],'gaps':['No complete guidebook route inventory; unknown fields are null.','Nearby parking is unverified unless explicitly in an OSM climbing relation.','Paths are mapped context, not automatically authorised approaches.','No current nesting-ban feed; mapped restrictions can be incomplete.','Images require per-file Commons licence verification before distribution.']}
    all_land=[]
    for rid,name in REGIONS.items():
        cs=[c for c in crags if c['region']==rid];land=land_features(snapshot,rid);all_land+=land
        fs=land+context[rid]
        for c in cs:
            fs.append({'type':'Feature','id':c['id'],'geometry':{'type':'Point','coordinates':[c['lon'],c['lat']]},'properties':{'kind':'crag','id':c['id'],'name':c['name'],'region':rid,'provenance':c['provenance']}})
            for r in c['routes']:
                if r['geometry']:fs.append({'type':'Feature','id':r['id']+':route','geometry':r['geometry'],'properties':{'kind':'route','id':r['id'],'name':r['name'],'provenance':r['provenance']}})
        for p in places:
            if region(p['country'],p['lat'])==rid:fs.append({'type':'Feature','id':p['id'],'geometry':{'type':'Point','coordinates':[p['lon'],p['lat']]},'properties':{'kind':'place','name':p['name'],'placeType':p['type'],'provenance':{'source':'os-open-names','sourceId':p['id'],'sourceUrl':p['sourceUrl'],'version':'2026-07','license':'OGL-UK-3.0'}}})
        # Defensive de-duplication by source feature ID, without merging distinct OSM objects.
        fs=list({f['id']:f for f in fs}.values());fs.sort(key=lambda f:f['id'])
        fc={'type':'FeatureCollection','features':fs};bounds=list(shape(land[0]['geometry']).bounds)
        pack={'schemaVersion':1,'generatedAt':generated,'region':{'id':rid,'name':name},'attributions':ATTRIBUTIONS,'crags':cs,'map':fc}
        paths={ext:PACKS/f'{rid}.{ext}' for ext in ('json','sqlite','geojson','mbtiles')}
        write_json(paths['json'],pack);write_json(paths['geojson'],fc)
        sqlite_pack(paths['sqlite'],cs,fs,{'schemaVersion':1,'region':rid,'generatedAt':generated,'attributions':ATTRIBUTIONS,'sourceSnapshot':snapshot['source']})
        tiles=0 if skip_tiles else vector_tiles(paths['mbtiles'],fs,bounds,name)
        files={ext:p.name for ext,p in paths.items() if p.exists()};entry={'id':rid,'name':name,'bounds':bounds,'cragCount':len(cs),'routeCount':len({r['id'] for c in cs for r in c['routes']}),'featureCount':len(fs),'tileCount':tiles,'files':files,'sha256':{ext:hashlib.sha256(paths[ext].read_bytes()).hexdigest() for ext in files},'bytes':{ext:paths[ext].stat().st_size for ext in files}}
        manifest['regions'].append(entry);report['regions'].append(entry)
        print('Built',rid,len(cs),'locations',entry['routeCount'],'routes',len(fs),'map features',tiles,'tiles',flush=True)
    write_json(PACKS/'manifest.json',manifest)
    write_json(PACKS/'all-crags.json',{'schemaVersion':1,'generatedAt':generated,'attributions':ATTRIBUTIONS,'crags':crags,'map':{'type':'FeatureCollection','features':all_land}})
    write_json(PACKS/'basemap.geojson',{'type':'FeatureCollection','features':all_land})
    write_json(ROOT/'data/coverage.json',report)
    print('TOTAL',compact({k:v for k,v in report.items() if k not in {'regions','sourceSnapshot','gaps'}}),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--skip-tiles',action='store_true');run(p.parse_args().skip_tiles)
