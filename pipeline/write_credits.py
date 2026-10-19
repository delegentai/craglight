"""Refresh the two offline data-credit pages from the current public catalogue."""
import hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]

def run():
    target=ROOT/'app/src/main/assets/licenses'
    catalogue=json.loads((ROOT/'data/packs/all-crags.json').read_text(encoding='utf8'))
    crags=catalogue['crags']
    photo_lines=['CragLight curated photo and climbing-text credits','',
        f'{len(crags)} annotated photographs; one licensed source photo per curated venue.',
        'Photos and annotations are unchanged source JPEGs. Selected route, approach and descent prose has normalized whitespace. Native grade spelling aliases are normalized without converting grade systems.',
        'Dan and Michel / Multi-pitch.com authored the imported climbing prose and annotated topos. No attribution implies endorsement.',
        'Source content is partial and dated. It has not been field-checked for present access, equipment or conditions.','']
    for c in crags:
        p=c['photos'][0]
        photo_lines += [c['name']+' — '+c['routes'][0]['name'],
            'Author: '+p['author'],'Source: '+p['sourceUrl'],'Image: '+p['url'],
            'Text and annotated image licence: '+p['license'],'Licence: '+p['licenseUrl'],
            'Original photograph licence: '+p.get('originalLicense',p['license']),
            'Changes: '+p['changes'],'Bundled file: '+p['assetPath'],
            'SHA-256: '+p['sha256'],'Scope: '+c['coverageScope'],'']
    (target/'photo-credits.txt').write_bytes(('\n'.join(photo_lines).rstrip()+'\n').encode('utf8'))
    notice=(ROOT/'data/LICENSE.txt').read_text(encoding='utf8')
    extra='\nCurrent guide: 10 venues, 10 named route guides/link-ups, 10 annotated JPEGs and authored approaches with separate descent descriptions.\nSource and reproducible data: https://github.com/delegentai/craglight\nWeather data by Open-Meteo.com: https://open-meteo.com/\nForecast data CC BY 4.0: https://creativecommons.org/licenses/by/4.0/\nForecasts are cached with retrieval time; drying suggestions are CragLight estimates.\nNo attribution implies endorsement.\n'
    (target/'data-credits.txt').write_bytes((notice+extra).encode('utf8'))
    index=json.loads((target/'index.json').read_text(encoding='utf8'))
    for item in index:
        if item['file']=='photo-credits.txt':
            item['title']='Curated topo photographs and climbing-text credits'; item['sourceUrl']='https://www.multi-pitch.com/'
    (target/'index.json').write_bytes((json.dumps(index,ensure_ascii=False,indent=2)+'\n').encode('utf8'))
    sources=json.loads((target/'sources.json').read_text(encoding='utf8'))
    for item in sources:
        if item['file'] in {'photo-credits.txt','data-credits.txt'}:
            path=target/item['file'];item['bytes']=path.stat().st_size;item['sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
            if item['file']=='photo-credits.txt':item['sourceUrl']='https://www.multi-pitch.com/'
    (target/'sources.json').write_bytes((json.dumps(sources,ensure_ascii=False,indent=2)+'\n').encode('utf8'))
    print('Updated offline credits for',len(crags),'curated photographs and their climbing prose')

if __name__=='__main__':run()
