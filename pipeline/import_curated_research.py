"""Import the individually reviewed CC source extracts; no network or proprietary data.

Run only when intentionally updating the curated source snapshot. Normal pack builds
consume data/curated/source-records.json and do not need the research workspace.
"""
from __future__ import annotations
import argparse, hashlib, html, json, pathlib, re, shutil, struct
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / 'data/curated'

# Editorial decisions are explicit, not a numeric completeness score.
DECISIONS = {
    'Bosigran': ('Doorpost', 'National Trust car park by Carn Galver ruined tin mine.', 'The source notes first-pitch seepage; current conditions and parking charges have not been field-checked.'),
    "Wintour's Leap": ('Right Hand Route', 'Grassy parking above the outcrop at the source coordinates.', 'North Wall top-outs are prohibited in this source. Abseil below the crag top; preserve the detailed descent restrictions below.'),
    'Great Tor': ('East Ridge', 'National Trust car park at Penmaen, SA3 2HH.', 'Source gives low-tide beach access or scrambling/abseiling alternatives; these are not ordinary walking paths.'),
    'Idwal Slabs': ('Ordinary Route', 'Named approach start: Ogwen Cottage. No parking location is asserted by this source extract.', 'Descent includes steep scrambling or an abseil/down-climb; read the full source descent.'),
    'Slieve Lamagan': ('FM', 'Carrick Little car park.', 'Long, steep descent with potentially overgrown path. Source describes alternative finishes; headline length and pitch count are omitted because they differ from the described finish.'),
    'Dinorwig Quarry': ('Australia West Face link-up', 'Roadside parking around the bus stop turning circle at the source coordinates.', 'Six named pitches form a link-up, not one continuous route. Source describes a locked gate and bold climbing; this is not confirmation of present access permission. Alternative published grades are preserved in the route text.'),
    'Cneifion Arete': ('Cneifion Arete', 'Lay-bys beside Llyn Ogwen, or limited paid parking at Ogwen Cottage, as described by the source.', 'Source describes a Diff climb / grade 3 scramble and variable belay spacing. Conflicting total length and variable pitch count are omitted.'),
    'Buzzards Roost': ('The Sheugh', 'Carrick Little top car park; the source also describes the lower alternative.', 'Boggy ground and steep descent. Source parking price is dated 2025, not a current price. Chimney climbing and head-torch advice are preserved in the route description.'),
    'Pigeon Rock': ('Virgo', 'Pigeon Rock car park behind the farm gate; close the gate.', 'Source descent crosses a wire fence and steep ground; current access is not confirmed. Existing abseil equipment may require replacement.'),
    'Eagle Mountain': ('Sammy Higgins', 'Parking by the fence before the ford at Sandy Brae farm, at the source coordinates.', 'Very steep grass at the start. Source reports a failed peg and equipment observations from 2026; no current equipment inspection is implied.'),
}
HOLDS = {
    'Chair Ladder': 'Useful topo and pitch text, but source approach describes descending to the start and does not establish a post-climb return route.',
    'Cornakey Cliff': 'Useful topo and pitch text, but source approach describes descending to the start and does not establish a post-climb return route; fixed-rope and tidal uncertainties remain.',
    'Avon Gorge': 'Morpheus image and page disagree on grade and length.',
    'Old Man of Stoer': 'Headline says five pitches but the retained pitch-by-pitch text stops after pitch four; complete route description is not established.',
    'Old Man of Hoy': 'Useful topo and approach, but no complete pitch-by-pitch route text in the retained source.',
    'Slieve Beg': 'Conflicting route length and pitch summaries need reconciliation before publication.',
    'Smugglers Terrace': 'Useful partial topo; route descriptions and dated fixed-rope approach require a complete integrated editorial record.',
    'Halkyn Mountain Crags': 'Licensed imagery and route overlays, but no reliable source-authored approach in current records.',
    'Elder Tree Boulder': 'Licensed imagery and route overlays, but no reliable source-authored approach in current records.',
    'Creag Meagaidh / Coire Ardair Post Face': 'Winter-only content; excluded from this rock-climbing seed.',
}

class Plain(HTMLParser):
    def __init__(self): super().__init__(); self.parts=[]
    def handle_starttag(self,tag,attrs):
        if tag in {'p','br','h3','h4','strong'}: self.parts.append('\n')
    def handle_endtag(self,tag):
        if tag in {'p','h3','h4'}: self.parts.append('\n')
    def handle_data(self,text): self.parts.append(text)

def plain(fragment):
    parser=Plain(); parser.feed(fragment)
    lines=[re.sub(r'[ \t\r\f\v]+',' ',s).strip() for s in ''.join(parser.parts).split('\n')]
    return '\n'.join(s for s in lines if s)

def element(text,ident):
    match=re.search(r'<([a-z0-9]+)\b[^>]*\bid="'+re.escape(ident)+r'"[^>]*>(.*?)</\1>',text,re.S|re.I)
    return plain(match[2]) if match else ''

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def jpeg_size(path):
    data=path.read_bytes(); pos=2
    while pos<len(data):
        if data[pos]!=255: pos+=1; continue
        while data[pos]==255: pos+=1
        marker=data[pos]; pos+=1
        if marker in (0xd8,0xd9): continue
        length=int.from_bytes(data[pos:pos+2],'big')
        if marker in (0xc0,0xc1,0xc2):
            return int.from_bytes(data[pos+5:pos+7],'big'),int.from_bytes(data[pos+3:pos+5],'big')
        pos+=length
    raise ValueError('JPEG dimensions missing')

def run(research):
    regional=json.loads((research/'regional_north_west_inventory.json').read_text(encoding='utf8'))['records']
    texts={r['crag_name']:r for r in json.loads((research/'open-assets/regional-multipitch-source-records.json').read_text(encoding='utf8'))}
    england=json.loads((research/'england_inventory.json').read_text(encoding='utf8'))
    approaches={r['crag_name']:r for r in json.loads((research/'england_open_approaches.json').read_text(encoding='utf8'))}
    (DEST/'photos').mkdir(parents=True,exist_ok=True)
    records=[]
    for record in england+regional:
        name=record['crag_name']
        if name not in DECISIONS or record['reuse_status']!='open': continue
        if record['source_site'].lower()!='multi-pitch.com': continue
        route_name,parking,access=DECISIONS[name]
        if name in approaches:
            # Re-root provenance paths; machine paths never enter committed data.
            page=research/'open-assets/england'/pathlib.Path(record['evidence_path']).name
            text=page.read_text(encoding='utf8')
            stats={key:element(text,ident) for key,ident in [('grade','grade'),('length','length'),('pitches','pitches'),('aspect','face'),('rock_and_flags','rockType')]}
            stats['approach_time']=str(approaches[name]['approach_minutes_source_claim'])+'min'
            coords=re.search(r'"latitude":\s*([-\d.]+),\s*"longitude":\s*([-\d.]+)',text)
            coordinates={'lat':float(coords[1]),'lon':float(coords[2]),'precision':'source route-location pin'}
            approach=approaches[name]['approach_text']; descent=approaches[name]['post_climb_descent_text']
            description=element(text,'pitchInfo'); intro=element(text,'intro')
            image=research/'open-assets/england'/pathlib.Path(record['asset_path']).name
            image_url=record['asset_url']; expected_hash=record['asset_sha256']
            parking_coords=approaches[name]['parking_coordinates_source_claim']
            parking_coords={'lat':parking_coords['latitude'],'lon':parking_coords['longitude']} if parking_coords else None
            original_license='CC BY-SA 4.0'
        else:
            src=texts[name]
            page=research/next(e['local_file'] for e in record['evidence'] if e['source_url']==record['source_url'])
            text=page.read_text(encoding='utf8')
            stats=src['source_stats']; coordinates=src['source_coordinates']; description=src['pitch_source_text']; intro=element(text,'intro')
            full=src['approach_and_descent_source_text']
            full=re.split(r'\n\s*See .* on the climb map',full)[0]
            approach,descent=re.split(r'\bDescent\s*:',full,maxsplit=1)
            approach=re.sub(r'^Approach\s*:\s*','',approach).strip(); descent=re.sub(r'^Descent\s*:\s*','',descent).strip()
            image=research/record['retained_open_asset']['path']; image_url=record['topo_url']; expected_hash=record['retained_open_asset']['sha256']
            # These coordinates occur explicitly in parking prose, never inferred by proximity.
            match=re.search(r'(5\d\.\d+)\s*,\s*(-\d\.\d+)',approach)
            parking_coords={'lat':float(match[1]),'lon':float(match[2])} if match else None
            original_license=record['original_image_licence']
        assert digest(image)==expected_hash,name
        assert description and len(description)>150 and approach and descent,name
        slug=re.sub(r'[^a-z0-9]+','-',name.lower()).strip('-')
        target=DEST/'photos'/f'{slug}.jpg'; shutil.copyfile(image,target)
        width,height=jpeg_size(target)
        records.append({'id':'curated:'+slug,'name':name,'country':record['country'].lower().replace(' ','-'),
            'routeName':route_name,'sourceUrl':record['source_url'],'sourcePageSha256':digest(page),
            'author':'Dan and Michel / Multi-pitch.com','license':'CC-BY-SA-4.0','licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/',
            'rightsEvidence':{'sourceUrl':record['source_url'],'originalImageCaption':'our own image',
                'originalImageLicenseUrl':'https://creativecommons.org/licenses/'+('by/4.0/' if original_license=='CC BY 4.0' else 'by-sa/4.0/'),
                'siteAuthoredTextAndAnnotationsLicenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/',
                'scope':'Original image explicitly credited as site-owned; site-authored prose and annotations only. Guidebook covers, external content and third-party photographs excluded.'},
            'checkedAt':'2026-10-19','review':{'status':'published','imagery':True,'approach':True,'route':True,'location':True,'rights':True},
            'sourceStats':stats,'coordinates':coordinates,'intro':intro,'routeDescription':description,
            'approachDescription':approach,'descentDescription':descent,'parkingDescription':parking,'parkingCoordinates':parking_coords,
            'accessNotes':access,'omitNumericSummary':name in {'Slieve Lamagan','Cneifion Arete'},
            'coordinateOverrideOsmId':'osm:way:237115051' if name=='Dinorwig Quarry' else None,
            'coverageScope':('Six-pitch link-up in the Garret/Australia area; other quarry routes are not covered.' if name=='Dinorwig Quarry' else f'One named route: {route_name}. Other routes at this crag are not covered.'),
            'photo':{'ref':'multipitch:'+slug,'assetPath':'photos/curated-'+slug+'.jpg','sourceFile':'photos/'+target.name,
                'url':image_url,'sourceUrl':record['source_url'],'author':'Dan and Michel / Multi-pitch.com','license':'CC-BY-SA-4.0',
                'licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/','originalLicense':original_license,
                'width':width,'height':height,'sha256':expected_hash,'bytes':target.stat().st_size,'kind':'annotated-topo',
                'changes':'Unchanged source annotated JPEG. Route lines, labels and belays are part of the source image.'},
            'transformations':['Selected site-authored route, approach and descent sections; excluded guidebook and weather sections.','Whitespace normalized; source spelling and source equipment observations retained.','Diff and VDiff normalized to D and VD only; no grade-system conversion.']})
    assert len(records)==len(DECISIONS),(len(records),len(DECISIONS))
    records.sort(key=lambda r:r['id'])
    out={'schemaVersion':1,'qualityVersion':1,'checkedAt':'2026-10-19','reviewScope':'Desk review of primary source content, image usefulness, internal consistency and licensing; not field inspection or confirmation of current access.','records':records,'excludedCandidates':[{'name':k,'reason':v} for k,v in HOLDS.items()]}
    (DEST/'source-records.json').write_bytes((json.dumps(out,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode('utf8'))
    print('Retained',len(records),'reviewed venues and source annotated JPEGs')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--research-dir',type=pathlib.Path,required=True);run(parser.parse_args().research_dir)
