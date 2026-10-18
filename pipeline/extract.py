"""Extract a compact, reproducible UK-only OSM snapshot from Geofabrik PBF.

All source object metadata except user identifiers is preserved. The national
extract includes a small boundary buffer; exact OSM country polygons remove it.
"""
from __future__ import annotations
import gzip, json, math, pathlib, sys, time
import osmium
from shapely.geometry import Point, LineString, mapping
from shapely.ops import polygonize, unary_union

ROOT=pathlib.Path(__file__).resolve().parents[1]
PBF=ROOT/'data/raw/uk.osm.pbf'
COUNTRIES={'GB-ENG':'england','GB-SCT':'scotland','GB-WLS':'wales','GB-NIR':'northern-ireland'}
CLIMBING={'area','crag','boulder','route','route_bottom','route_top','bolt','yes','multipitch'}
PATHS={'path','footway','bridleway','track','steps','pedestrian','living_street','service','residential','unclassified'}

def record(o, locations=True):
    typ={'n':'node','w':'way','r':'relation'}[o.type_str()]
    d={'type':typ,'id':o.id,'version':o.version,'timestamp':str(o.timestamp),'tags':dict(o.tags)}
    if typ=='node':d.update(lat=o.location.lat,lon=o.location.lon)
    elif typ=='way':
        d['nodes']=[n.ref for n in o.nodes]
        d['geometry']=[{'lon':n.lon,'lat':n.lat} for n in o.nodes if n.location.valid()] if locations else []
    else:d['members']=[{'type':{'n':'node','w':'way','r':'relation'}[m.type],'ref':m.ref,'role':m.role} for m in o.members]
    return d

def positive(v):
    return v is not None and v.lower() not in {'no','0','false','none'}

def outdoor(t):
    if t.get('climbing')=='no' or t.get('indoor')=='yes' or t.get('outdoor')=='no' or t.get('building') not in (None,'no') or t.get('leisure') in {'sports_centre','sports_hall','pitch','playground','climbing_hall','high_ropes_course'} or 'playground' in t:return False
    # Plain sport=climbing markers are retained with unspecified outdoor status.
    # Rejecting them would discard real mapped places such as Kilnsey Crag.
    return t.get('climbing') in CLIMBING or t.get('natural') in {'cliff','rock','stone','bare_rock'} or t.get('outdoor')=='yes' or 'climbing' in t.get('sport','').split(';') or any(positive(t.get('climbing:'+s)) for s in ('trad','sport','boulder','deepwater'))

def center(e, index=None, seen=None):
    if 'lat' in e:return e['lon'],e['lat']
    coords=[(p['lon'],p['lat']) for p in e.get('geometry',[])]
    if not coords and index:
        seen=set() if seen is None else seen
        key=(e['type'],e['id'])
        if key in seen:return None
        seen.add(key)
        for m in e.get('members',[]):
            ref=index.get((m['type'],m['ref']))
            if ref:
                c=center(ref,index,seen)
                if c:coords.append(c)
    if not coords:return None
    return (min(c[0] for c in coords)+max(c[0] for c in coords))/2,(min(c[1] for c in coords)+max(c[1] for c in coords))/2

def run():
    start=time.monotonic();boundary_relations={};boundary_ids=set();initial={};member_ids=set()
    # Country membership is based on authoritative OSM administrative relations.
    for o in osmium.FileProcessor(PBF).with_filter(osmium.filter.KeyFilter('ISO3166-2','climbing','sport')):
        tags=dict(o.tags);code=tags.get('ISO3166-2')
        if o.is_relation() and code in COUNTRIES and tags.get('admin_level')=='4':
            e=record(o);boundary_relations[code]=e
            boundary_ids.update(m['ref'] for m in e['members'] if m['type']=='way')
        if 'climbing' in tags or 'climbing' in tags.get('sport','').split(';'):
            e=record(o,locations=False);initial[(e['type'],e['id'])]=e
            member_ids.update(m['ref'] for m in e.get('members',[]))
    if set(boundary_relations)!=set(COUNTRIES):raise RuntimeError('Missing UK country boundaries: '+str(boundary_relations.keys()))
    print('Country boundaries',len(boundary_relations),'ways',len(boundary_ids),flush=True)
    elements=[];boundaries={};dependencies=[]
    # Filter is deliberately broad enough to include unnamed/partially tagged climbs.
    needed=boundary_ids|member_ids|{i for _,i in initial}
    fp=osmium.FileProcessor(PBF).with_locations('flex_mem').with_filter(osmium.filter.IdFilter(needed))
    for o in fp:
        t=dict(o.tags)
        if o.is_way() and o.id in boundary_ids:boundaries[o.id]=record(o)
        typ={'n':'node','w':'way','r':'relation'}[o.type_str()]
        if (typ,o.id) in initial:elements.append(record(o))
        elif o.id in member_ids:dependencies.append(record(o))
    del fp
    shapes={}
    for code,r in boundary_relations.items():
        lines=[LineString([(p['lon'],p['lat']) for p in boundaries[m['ref']]['geometry']]) for m in r['members'] if m['type']=='way' and m['ref'] in boundaries and m['role']!='inner']
        shape=unary_union(list(polygonize(lines)))
        if shape.is_empty:raise RuntimeError('Cannot assemble '+code)
        shapes[COUNTRIES[code]]=shape
    index={(e['type'],e['id']):e for e in elements+dependencies}
    centers=[];kept=[];excluded={}
    for e in elements:
        c=center(e,index)
        country=next((name for name,shape in shapes.items() if c and shape.covers(Point(c))),None)
        if country:
            e['country']=country;kept.append(e)
            if outdoor(e['tags']) and e['tags'].get('climbing') not in {'bolt','route_top'}:centers.append(c)
        else:excluded[str((e['type'],e['id']))]='outside UK country polygon or no coordinate'
    print('Climbing objects',len(elements),'UK',len(kept),'outdoor reference points',len(centers),flush=True)
    # A cheap spatial grid avoids returning a full national path network.
    grid={}
    for lon,lat in centers:grid.setdefault((math.floor(lon/.025),math.floor(lat/.025)),[]).append((lon,lat))
    covered={(x+dx,y+dy) for x,y in grid for dx in range(-2,3) for dy in range(-1,2)}
    def near(lon,lat):
        x,y=math.floor(lon/.025),math.floor(lat/.025)
        if (x,y) not in covered:return False
        for dx in range(-2,3):
            for dy in range(-1,2):
                for cl,ct in grid.get((x+dx,y+dy),[]):
                    if ((lon-cl)*math.cos(math.radians(lat))*111320)**2+((lat-ct)*111320)**2<=1500**2:return True
        return False
    context=[]
    for e in dependencies:
        c=center(e,index);country=next((name for name,shape in shapes.items() if c and shape.covers(Point(c))),None)
        if country:e['country']=country;context.append(e)
    tagpairs=[('amenity','parking')]+[('highway',s) for s in sorted(PATHS)]+[('path','climbing_access'),('natural','cliff')]
    fp=osmium.FileProcessor(PBF).with_locations('flex_mem').with_filter(osmium.filter.TagFilter(*tagpairs))
    for o in fp:
        if o.is_relation():continue
        if o.is_node():relevant=near(o.location.lon,o.location.lat)
        else:relevant=any(n.location.valid() and near(n.lon,n.lat) for n in o.nodes)
        if relevant:
            e=record(o);c=center(e)
            country=next((name for name,shape in shapes.items() if c and shape.covers(Point(c))),None)
            if country:e['country']=country;context.append(e)
    del fp
    source=json.loads((PBF.with_suffix(PBF.suffix+'.source.json')).read_text())
    snapshot={'schemaVersion':1,'source':source,'sourceDescription':'Geofabrik UK OSM PBF','elements':kept,'context':context,'countries':{name:mapping(shape.simplify(.0005,preserve_topology=True)) for name,shape in shapes.items()},'countryProvenance':{COUNTRIES[code]:r for code,r in boundary_relations.items()},'excluded':excluded}
    out=ROOT/'data/snapshot.json.gz'
    with out.open('wb') as f:
        with gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as gz:gz.write(json.dumps(snapshot,separators=(',',':'),ensure_ascii=False).encode())
    print('Snapshot',len(kept),'climbing objects',len(context),'context objects',out.stat().st_size,'bytes',round(time.monotonic()-start,1),'seconds',flush=True)

if __name__=='__main__':run()
