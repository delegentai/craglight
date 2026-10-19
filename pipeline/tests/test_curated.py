"""Publish only useful source-reviewed guides; regression tests use actual records."""
import copy, hashlib, json, pathlib, sqlite3, sys, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'pipeline'))
import curated

class CuratedQualityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=json.loads(curated.SOURCE.read_text(encoding='utf8'))
        cls.catalogue=json.loads((ROOT/'data/packs/all-crags.json').read_text(encoding='utf8'))['crags']

    def test_all_published_guides_pass_and_no_drafts_leak(self):
        for record in self.source['records']: curated.validate_source(record)
        self.assertEqual(10,len(self.catalogue))
        self.assertTrue(all(curated.eligible(c) for c in self.catalogue))
        self.assertTrue({r['name'] for r in self.source['excludedCandidates']}.isdisjoint(c['name'] for c in self.catalogue))

    def test_incomplete_or_unreviewed_edits_cannot_publish(self):
        for field,value in [('approachDescription',''),('descentDescription',''),('parkingDescription',''),('routeDescription','TBD'),('routeName','')]:
            record=copy.deepcopy(self.source['records'][0]);record[field]=value
            with self.assertRaises(ValueError,msg=field):curated.validate_source(record)
        record=copy.deepcopy(self.source['records'][0]);record['review']['status']='draft'
        with self.assertRaises(ValueError):curated.validate_source(record)
        record['review']['status']='published';record['review']['rights']=False
        with self.assertRaises(ValueError):curated.validate_source(record)

    def test_media_rights_and_hash_are_required(self):
        for key,value in [('author',''),('licenseUrl',''),('sha256','0'*64),('kind','landscape')]:
            record=copy.deepcopy(self.source['records'][0]);record['photo'][key]=value
            with self.assertRaises(ValueError,msg=key):curated.validate_source(record)

    def test_alias_normalization_never_converts_grade_systems(self):
        self.assertEqual({'ukTrad':'VD','ukTech':'3c'},curated.route_grades('VDiff 3c'))
        self.assertEqual({'ukTrad':'D','ukTech':'3a'},curated.route_grades('Diff 3a'))
        with self.assertRaises(ValueError):curated.route_grades('6a')

    def test_actual_parking_and_crag_coordinates_are_not_conflated(self):
        c=next(c for c in self.catalogue if c['name']=='Dinorwig Quarry')
        self.assertEqual('osm:way:237115051',c['fieldProvenance']['location']['sourceId'])
        p=c['parking'][0];self.assertEqual((53.12830201,-4.10817526),(p['lat'],p['lon']))
        self.assertNotEqual((c['lat'],c['lon']),(p['lat'],p['lon']))
        b=next(c for c in self.catalogue if c['name']=='Bosigran')
        self.assertEqual([],b['parking']);self.assertIn('Carn Galver',b['parkingDescription'])
        for c in self.catalogue:
            self.assertEqual([],c['approaches'])
            self.assertTrue(all(p['association']=='authored-source' for p in c['parking']))

    def test_ambiguous_totals_omitted_and_source_scope_retained(self):
        for name in ('Slieve Lamagan','Cneifion Arete'):
            c=next(c for c in self.catalogue if c['name']==name)
            self.assertIsNone(c['routes'][0]['lengthM']);self.assertIsNone(c['routes'][0]['pitches'])
        self.assertTrue(all(c['coverageScope'] for c in self.catalogue))
        wintour=next(c for c in self.catalogue if c['name']=="Wintour's Leap")
        self.assertIn('Do not top out',wintour['descentDescription'])

    def test_no_old_crag_markers_or_unattributed_generated_media(self):
        expected={c['id'] for c in self.catalogue}
        expected_photos={p['assetPath'] for c in self.catalogue for p in c['photos']}
        actual_photos={'photos/'+p.name for p in (ROOT/'data/packs/photos').iterdir() if p.is_file()}
        self.assertEqual(expected_photos,actual_photos)
        found=set()
        manifest=json.loads((ROOT/'data/packs/manifest.json').read_text(encoding='utf8'))
        for region in manifest['regions']:
            pack=json.loads((ROOT/'data/packs'/region['files']['json']).read_text(encoding='utf8'))
            found.update(f['id'] for f in pack['map']['features'] if f['properties']['kind']=='crag')
        self.assertEqual(expected,found)

    def test_committed_source_has_no_machine_private_paths_or_signed_urls(self):
        text=curated.SOURCE.read_text(encoding='utf8')
        for marker in ('C:\\','Abulhawa','?sv=','&sig=','DelegentAI','file://','\ufffd'):
            self.assertNotIn(marker,text)
        self.assertEqual(10,len(list((curated.SOURCE.parent/'photos').glob('*.jpg'))))

if __name__=='__main__':unittest.main()
