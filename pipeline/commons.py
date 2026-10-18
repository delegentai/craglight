"""Fetch OSM-referenced topo photos only after per-file licence verification.

Commons' robots excludes Action API URLs; this low-volume implementation reads
permitted File pages and their structured licence templates instead. No search,
guidebook scraping, or blanket assumption about Commons copyright is used.
"""
import argparse, gzip, hashlib, html, json, pathlib, re, time, urllib.parse, urllib.request, urllib.robotparser

ROOT=pathlib.Path(__file__).resolve().parents[1]
UA='CragLight/0.1 (+https://github.com/delegentai/craglight; open climbing topo licence verification)'

def text(markup):return ' '.join(html.unescape(re.sub(r'<[^>]*>',' ',markup)).split())
def field(page,cls):
    m=re.search(r'<([a-z]+)\b[^>]*class="[^"]*\b'+re.escape(cls)+r'\b[^"]*"[^>]*>(.*?)</\1>',page,re.S)
    return text(m[2]) if m else None
def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':UA}),timeout=35) as response:return response.read(),response.headers.get('Content-Type','')
def licence_record(page,title,url):
    page=html.unescape(page)
    licence=field(page,'licensetpl_short');link=field(page,'licensetpl_link');author=field(page,'licensetpl_attr')
    if not author:
        m=re.search(r'<td[^>]*id="fileinfotpl_aut"[^>]*>.*?</td>\s*<td[^>]*>(.*?)</td>',page,re.S)
        author=text(m[1]) if m else None
    if not licence or not link or not author:return None
    if licence not in {'CC BY-SA 4.0','CC BY-SA 3.0','CC BY-SA 2.0','CC BY 4.0','CC BY 3.0','CC BY 2.0','CC0'}:return None
    if not re.match(r'^https?://creativecommons\.org/(licenses/(by|by-sa)/(2\.0|3\.0|4\.0)/?|publicdomain/zero/1\.0/?)(deed\.[a-z-]+)?$',link):return None
    m=re.search(r'<div class="fullImageLink".*?<img[^>]*src="([^"]+)"[^>]*width="(\d+)"[^>]*height="(\d+)"',page,re.S)
    if not m:return None
    image_url=m[1]
    if urllib.parse.urlsplit(image_url).hostname not in {'upload.wikimedia.org','thumb.wikimedia.org'}:return None
    return {'ref':title,'title':title,'url':image_url,'sourceUrl':url,'author':author,'license':licence,'licenseUrl':link,'width':int(m[2]),'height':int(m[3]),'changes':'Commons preview image, with separately rendered OSM route-line overlay when available. Photo and rendered photo-overlay adaptations retain the stated image licence.'}

def run():
    out=ROOT/'data/packs/photos';out.mkdir(parents=True,exist_ok=True)
    raw=ROOT/'data/raw/commons';raw.mkdir(parents=True,exist_ok=True)
    snapshot_path=ROOT/'data/snapshot.json.gz'
    if snapshot_path.exists():
        elements=json.loads(gzip.decompress(snapshot_path.read_bytes()))['elements']
        titles=sorted({e['tags']['wikimedia_commons'] for e in elements if e['tags'].get('wikimedia_commons','').startswith('File:') and ('wikimedia_commons:path' in e['tags'] or e['tags'].get('climbing') in {'crag','area','boulder'})})
    else:
        audit=json.loads((ROOT/'data/source-tag-audit.json').read_text(encoding='utf8'))
        titles=sorted({e['photo'] for e in audit['photoRefs'] if e['path'] and e['photo'].startswith('File:')})
    rules={};records={};failures=[]
    def allowed(url):
        host=urllib.parse.urlsplit(url).netloc
        if host not in rules:
            rp=urllib.robotparser.RobotFileParser()
            try:b,_=fetch('https://'+host+'/robots.txt');rp.parse(b.decode('utf8').splitlines())
            except urllib.error.HTTPError as exc:
                if exc.code==404:rp.parse([])
                else:return False
            rules[host]=rp
        return rules[host].can_fetch('CragLight',url)
    for title in titles:
        key=hashlib.sha256(title.replace('_',' ').encode()).hexdigest()[:20]
        url='https://commons.wikimedia.org/wiki/'+urllib.parse.quote(title.replace(' ','_'),safe=':')
        try:
            if not allowed(url):raise RuntimeError('robots excludes File page')
            cached=raw/(key+'.html')
            if not cached.exists():b,_=fetch(url);cached.write_bytes(b);time.sleep(1)
            record=licence_record(cached.read_text(encoding='utf8'),title,url)
            if not record:raise RuntimeError('no supported verified licence/author/image')
            if not allowed(record['url']):raise RuntimeError('robots excludes media URL')
            dest=out/(key+'.jpg')
            if not dest.exists():
                b,mime=fetch(record['url'])
                if mime.split(';')[0] not in {'image/jpeg','image/png'}:raise RuntimeError('unsupported image MIME '+mime)
                dest.write_bytes(b);time.sleep(1)
            record.update(assetPath='photos/'+dest.name,sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),bytes=dest.stat().st_size,metadataSha256=hashlib.sha256(cached.read_bytes()).hexdigest())
            records[title.replace('_',' ')]=record
            print('Licensed image',title,record['license'],record['bytes'],flush=True)
        except Exception as exc:failures.append({'title':title,'reason':str(exc)});print('Photo unavailable',title,str(exc),flush=True)
    (ROOT/'data/photos.json').write_bytes((json.dumps({'photos':records,'unavailable':failures},ensure_ascii=False,indent=2)+'\n').encode('utf8'))
    print('Photos',len(records),'unavailable',len(failures),flush=True)

if __name__=='__main__':run()
