"""Factual/provenance regression tests and real pack validation."""
import gzip, hashlib, json, pathlib, sqlite3, sys, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'pipeline'))
import build
from extract import outdoor
from download import public_resolved_url
from shapely.geometry import Point,shape
import mapbox_vector_tile

def node(ident,lon=-2.0,lat=54.0,**tags):
    return {'type':'node','id':ident,'version':3,'timestamp':'2026-10-17T00:00:00Z','lon':lon,'lat':lat,'tags':tags,'country':'england'}

class TransformationTests(unittest.TestCase):
    def test_published_redirect_urls_exclude_signatures(self):
        self.assertEqual('https://example.org/data.zip', public_resolved_url('https://example.org/data.zip?sig=temporary&sv=version#fragment'))

    def test_unknowns_stay_unknown_and_grades_not_converted(self):
        c=node(1,name='Fixture crag',climbing='crag')
        crags,_=build.make_catalogue({'elements':[c],'context':[]})
        item=crags[0]
        self.assertIsNone(item['familyFriendly']);self.assertEqual([],item['styles'])
        self.assertIsNone(item['access']);self.assertIsNone(item['walkInTimeMin'])
        self.assertTrue(all(x is None for x in item['grades'].values()))
        self.assertEqual({'ukTrad':None,'ukTech':None,'french':'6a+','font':None,'v':None},build.grades({'climbing:grade:french':'6a+'}))

    def test_nearest_route_is_not_assigned_to_crag(self):
        c=node(1,name='Fixture crag',climbing='crag')
        r=node(2,lon=-2.00001,name='Fixture route',climbing='route_bottom')
        crags,_=build.make_catalogue({'elements':[c,r],'context':[]})
        self.assertEqual(2,len(crags))
        self.assertEqual([],next(x for x in crags if x['id']=='osm:node:1')['routes'])
        self.assertEqual('standalone-route',next(x for x in crags if x['id']=='osm:node:2')['kind'])

    def test_explicit_membership_assigns_route_and_unverified_parking_stays_unverified(self):
        r=node(2,name='Fixture route',climbing='route_bottom')
        c={'type':'relation','id':3,'version':1,'timestamp':'2026-10-17T00:00:00Z','country':'england','tags':{'name':'Fixture crag','climbing':'crag'},'members':[{'type':'node','ref':2,'role':''}]}
        parking=node(4,lon=-2.0002,amenity='parking',access='private')
        crags,_=build.make_catalogue({'elements':[c,r],'context':[parking]})
        self.assertEqual(1,len(crags));self.assertEqual('osm:node:2',crags[0]['routes'][0]['id'])
        p=crags[0]['parking'][0];self.assertEqual('nearby-unverified',p['association']);self.assertEqual('private',p['access'])
        self.assertIsNone(crags[0]['walkInDistanceM'])

    def test_endpoint_not_duplicate_route(self):
        n=node(8,name='Fixture start',climbing='route_bottom')
        w={'type':'way','id':9,'version':1,'timestamp':'2026-10-17T00:00:00Z','country':'england','tags':{'name':'Fixture line','climbing':'route'},'nodes':[8,10],'geometry':[{'lon':-2,'lat':54},{'lon':-2.0001,'lat':54.0001}]}
        crags,_=build.make_catalogue({'elements':[n,w],'context':[]})
        self.assertEqual(1,len(crags));self.assertEqual('osm:way:9',crags[0]['routes'][0]['id'])

    def test_indoor_and_playground_rejected(self):
        self.assertFalse(outdoor({'sport':'climbing','indoor':'yes'}))
        self.assertFalse(outdoor({'sport':'climbing','leisure':'sports_centre'}))
        self.assertFalse(outdoor({'sport':'climbing','leisure':'climbing_hall'}))
        self.assertFalse(outdoor({'sport':'climbing','leisure':'high_ropes_course'}))
        self.assertFalse(outdoor({'climbing':'boulder','playground':'climbingwall'}))
        self.assertTrue(outdoor({'sport':'climbing','natural':'cliff'}))
        self.assertFalse(outdoor({'climbing':'no','natural':'cliff'}))

    def test_plain_climbing_marker_does_not_invent_outdoor_status(self):
        crags,_=build.make_catalogue({'elements':[node(17,sport='climbing',name='Unclassified fixture')],'context':[]})
        self.assertEqual('climbing-location',crags[0]['kind'])
        self.assertEqual('unspecified',crags[0]['outdoorStatus'])

    def test_aspect_ambiguity_and_topo_validation(self):
        self.assertEqual(225,build.aspect({'climbing:orientation':'SW'}))
        self.assertIsNone(build.aspect({'climbing:orientation':'N;S'}))
        self.assertIsNone(build.aspect({'direction':'NaN'}))
        self.assertIsNone(build.parse_topopath('1.5,0.3|0.2,0.1'))
        self.assertEqual('A',build.parse_topopath('0.1,0.8|0.3,0.1A')[1]['type'])

    def test_ukc_link_whitelist(self):
        self.assertIsNone(build.safe_ukc({'website':'https://ukclimbing.com.evil.test/logbook/crags/foo'}))
        self.assertEqual('https://www.ukclimbing.com/logbook/crags/test-123/',build.safe_ukc({'climbing:url:ukclimbing':'https://www.ukclimbing.com/logbook/crags/test-123/'}))

    def test_path_tags_and_node_ids_survive(self):
        w={'type':'way','id':10,'version':1,'timestamp':'2026-10-17T00:00:00Z','country':'england','tags':{'highway':'path','foot':'no','access:conditional':'no @ (Mar-Jul)'},'nodes':[20,21],'geometry':[{'lon':-2,'lat':54},{'lon':-2.01,'lat':54}]}
        _,context=build.make_catalogue({'elements':[],'context':[w]})
        p=context['england-north'][0]['properties'];self.assertEqual([20,21],p['nodeIds']);self.assertEqual('no',p['tags']['foot']);self.assertEqual('no @ (Mar-Jul)',p['tags']['access:conditional'])

    def test_duplicate_relation_member_yields_one_route(self):
        r=node(2,name='Repeated route',climbing='route_bottom')
        member={'type':'node','ref':2,'role':''}
        c={'type':'relation','id':3,'version':1,'timestamp':'2026-10-17T00:00:00Z','country':'england','tags':{'name':'Repeated member crag','climbing':'crag'},'members':[member,member]}
        crags,_=build.make_catalogue({'elements':[c,r],'context':[]})
        self.assertEqual(['osm:node:2'],[r['id'] for r in crags[0]['routes']])

class RealDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshot=json.loads(gzip.decompress((ROOT/'data/snapshot.json.gz').read_bytes()))
        cls.manifest=json.loads((ROOT/'data/packs/manifest.json').read_text(encoding='utf8'))
        cls.catalogue=json.loads((ROOT/'data/packs/all-crags.json').read_text(encoding='utf8'))

    def test_exact_country_selection_excludes_neighbours(self):
        union_shapes=[shape(g) for g in self.snapshot['countries'].values()]
        def inside(lon,lat):return any(g.covers(Point(lon,lat)) for g in union_shapes)
        for p in [(-5.93,54.60),(-3.19,55.95),(-3.18,51.48),(-0.12,51.5)]:self.assertTrue(inside(*p),p)
        for p in [(-6.26,53.35),(-4.48,54.15),(-2.12,49.19),(2.35,48.86)]:self.assertFalse(inside(*p),p)

    def test_nontrivial_all_four_country_coverage_and_no_invented_walkins(self):
        crags=self.catalogue['crags'];self.assertGreater(len(crags),500)
        self.assertEqual({'england','scotland','wales','northern-ireland'},{c['country'] for c in crags})
        for c in crags:
            self.assertIsNone(c['walkInDistanceM']);self.assertIsNone(c['walkInTimeMin'])
            self.assertEqual('ODbL-1.0',c['provenance']['license']);self.assertGreater(c['provenance']['version'],0)
            self.assertTrue(c['provenance']['timestamp']);self.assertEqual(c['id'],c['provenance']['sourceId'])

    def test_real_route_ids_unique_per_location_and_explicit_noncrags_excluded(self):
        for c in self.catalogue['crags']:
            self.assertEqual(len(c['routes']),len({r['id'] for r in c['routes']}),c['id'])
            self.assertNotIn(c['rawTags'].get('leisure'),{'climbing_hall','high_ropes_course'})
        down_tor=next(c for c in self.catalogue['crags'] if c['id']=='osm:relation:21457705')
        self.assertEqual(230,len(down_tor['routes']))

    def test_publishable_metadata_has_no_signed_redirects_and_pack_newlines_are_lf(self):
        for p in (ROOT/'data').rglob('*'):
            if not p.is_file() or 'raw' in p.parts or p.suffix not in {'.json','.geojson'}:continue
            data=p.read_bytes()
            self.assertNotIn(b'?sv=',data,str(p));self.assertNotIn(b'&sig=',data,str(p))
            self.assertNotIn(b'\r\n',data,str(p))

    def test_manifest_hashes_and_database_integrity(self):
        for r in self.manifest['regions']:
            for kind,name in r['files'].items():
                p=ROOT/'data/packs'/name;self.assertEqual(r['sha256'][kind],hashlib.sha256(p.read_bytes()).hexdigest());self.assertEqual(r['bytes'][kind],p.stat().st_size)
            with sqlite3.connect(ROOT/'data/packs'/r['files']['sqlite']) as db:
                self.assertEqual('ok',db.execute('PRAGMA integrity_check').fetchone()[0])
                self.assertEqual(r['cragCount'],db.execute('SELECT count(*) FROM crags').fetchone()[0])
                self.assertEqual(r['routeCount'],db.execute('SELECT count(*) FROM routes').fetchone()[0])
                self.assertEqual(0,db.execute('SELECT count(*) FROM crags LEFT JOIN provenance ON crags.id=provenance.record_id WHERE provenance.record_id IS NULL').fetchone()[0])

    def test_real_vector_tiles_decode_and_have_osm_features(self):
        for r in self.manifest['regions']:
            self.assertGreater(r['tileCount'],0)
            with sqlite3.connect(ROOT/'data/packs'/r['files']['mbtiles']) as db:
                self.assertEqual('pbf',db.execute("SELECT value FROM metadata WHERE name='format'").fetchone()[0])
                self.assertEqual(r['tileCount'],db.execute('SELECT count(*) FROM tiles').fetchone()[0])
                z,x,y,blob=db.execute('SELECT * FROM tiles ORDER BY zoom_level DESC LIMIT 1').fetchone()
                self.assertTrue(0<=x<2**z and 0<=y<2**z)
                decoded=mapbox_vector_tile.decode(gzip.decompress(blob));self.assertIn('craglight',decoded);self.assertGreater(len(decoded['craglight']['features']),0)

    def test_licensed_images_and_topo_paths(self):
        photos=json.loads((ROOT/'data/photos.json').read_text(encoding='utf8'))['photos']
        self.assertGreaterEqual(len(photos),10)
        for p in photos.values():
            self.assertTrue(p['author']);self.assertNotIn('<',p['author']);self.assertTrue(p['license']);self.assertIn('creativecommons.org',p['licenseUrl'])
            self.assertTrue(p['sourceUrl'].startswith('https://commons.wikimedia.org/wiki/File:'))
            asset=ROOT/'data/packs'/p['assetPath'];self.assertEqual(p['sha256'],hashlib.sha256(asset.read_bytes()).hexdigest());self.assertEqual(p['bytes'],asset.stat().st_size)
        paths=0
        for c in self.catalogue['crags']:
            for r in c['routes']:
                if r['topoPath']:
                    paths+=1
                    for pt in r['topoPath']:self.assertTrue(0<=pt['x']<=1 and 0<=pt['y']<=1)
        self.assertGreaterEqual(paths,60)

if __name__=='__main__':unittest.main()
