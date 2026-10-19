import io
import json
import secrets
import time
from datetime import datetime,timedelta,timezone
from types import SimpleNamespace
from uuid import uuid4
import pytest
from fastapi.testclient import TestClient
from PIL import Image,PngImagePlugin
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from google.auth import crypt,jwt
from app import create_app,grade_valid
from security import GoogleVerifier,normalize_image,google_photo

@pytest.fixture
def service(tmp_path):
    claims={}
    app=create_app(tmp_path,'craglight-test.apps.googleusercontent.com',verifier=lambda token:claims[token],photo_fetcher=lambda url:normalize_image(picture()))
    client=TestClient(app)
    def login(sub=None,alias='Rock Friend',photo=False):
        challenge=client.get('/v1/auth/challenge').json();token=secrets.token_urlsafe(32)
        claims[token]={'sub':sub or str(uuid4()),'nonce':challenge['nonce'],'email':'never-store@example.test','name':'Private Real Name','picture':'https://lh3.googleusercontent.com/test'}
        body=dict(idToken=token,challengeId=challenge['challengeId'],publicAlias=alias,useGooglePhoto=photo,termsAccepted=True,licenceAccepted=True)
        response=client.post('/v1/auth/google',json=body);assert response.status_code==200,response.text
        return response.json(),{'Authorization':'Bearer '+response.json()['token']},body
    def moderator():
        result,headers,_=login()
        with app.state.store.connection() as db:db.execute('UPDATE users SET moderator=1 WHERE id=?',(result['profile']['id'],))
        return headers
    return client,app,login,moderator,claims

def picture(kind='JPEG',size=(120,80),exif=False):
    image=Image.new('RGB',size,(12,120,85));output=io.BytesIO()
    if kind=='PNG':
        info=PngImagePlugin.PngInfo();info.add_text('Location','private home');image.save(output,kind,pnginfo=info)
    elif exif:
        tags=Image.Exif();tags[274]=6;tags[315]='Private camera owner';image.save(output,kind,exif=tags)
    else:image.save(output,kind)
    return output.getvalue()

def upload(client,headers):
    response=client.post('/v1/photos',headers={**headers,'Content-Type':'image/jpeg','X-Photo-Id':str(uuid4())},content=picture())
    assert response.status_code==200,response.text
    photo=response.json();photo.pop('status');photo['originalWork']=True;return photo

def guide(photo,kind='guide'):
    return dict(id=str(uuid4()),kind=kind,crag=dict(name='Fixture Crag',existingId='fixture:crag',lat=53.1,lon=-4.1),
      parking=dict(lat=53.101,lon=-4.101,description='Authored parking observation'),approach='Walk along the authored path to the test cliff.',descent='Reverse the approach after walking down the marked side.',access='Current access must be checked with the land manager.',accessCheckedAt='2026-01-01',photos=[photo],routes=[dict(id=str(uuid4()),name='Fixture route',gradeSystem='ukTrad',grade='VS',style='trad',description='Test original description',photoId=photo['id'],lines=[[dict(x=.2,y=.9),dict(x=.3,y=.1)]])],contributorAlias='Impersonated name',licence='CC-BY-SA-4.0',consent=True,caption='An original crag photograph.')

def submit(client,headers,draft):
    response=client.put('/v1/drafts/'+draft['id'],headers=headers,json=draft);assert response.status_code==200,response.text
    return client.post('/v1/drafts/'+draft['id']+'/submit',headers=headers)

def approve(client,mod,id):
    return client.post('/v1/moderation/drafts/'+id,headers=mod,json=dict(decision='approve',reason='Checked original rights, access notes and all lines.',rightsChecked=True,safetyChecked=True,accessChecked=True,completenessChecked=True,evidenceUrl='https://example.org/review'))

def test_google_library_verifies_signature_issuer_audience_and_expiry():
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'CragLight offline verification fixture')])
    cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc)-timedelta(days=1)).not_valid_after(datetime.now(timezone.utc)+timedelta(days=1)).sign(key,hashes.SHA256())
    pem=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
    signer=crypt.RSASigner.from_string(pem,key_id='fixture')
    certificate=cert.public_bytes(serialization.Encoding.PEM).decode()
    verifier=GoogleVerifier('our-client',lambda *args,**kwargs:SimpleNamespace(status=200,data=json.dumps({'fixture':certificate}).encode()))
    base=dict(iss='https://accounts.google.com',sub='account-subject',aud='our-client',iat=int(time.time()),exp=int(time.time())+300)
    assert verifier(jwt.encode(signer,base).decode())['sub']=='account-subject'
    for changes in [dict(aud='other-client'),dict(iss='https://attacker.test'),dict(exp=int(time.time())-60),dict(iat=int(time.time())+300)]:
        with pytest.raises(Exception):verifier(jwt.encode(signer,{**base,**changes}).decode())
    token=jwt.encode(signer,base).decode();parts=token.split('.');parts[1]=parts[1][:-4]+'AAAA'
    with pytest.raises(Exception):verifier('.'.join(parts))

def test_nonce_is_required_and_single_use(service):
    c,app,login,mod,claims=service
    account,headers,body=login()
    assert c.post('/v1/auth/google',json=body).status_code==401
    challenge=c.get('/v1/auth/challenge').json();body['challengeId']=challenge['challengeId']
    assert c.post('/v1/auth/google',json=body).status_code==401
    claims[body['idToken']]['nonce']=challenge['nonce'];assert c.post('/v1/auth/google',json=body).status_code==200

def test_google_data_minimisation_alias_and_photo_moderation(service):
    c,app,login,moderator,_=service
    result,headers,_=login(photo=True);id=result['profile']['id'];photo=result['googlePhoto']['photoId']
    assert result['profile']['alias'].startswith('Climber-') and result['profile']['photoUrl'] is None
    assert c.get('/v1/photos/'+photo).status_code==404
    exported=c.get('/v1/me/export',headers=headers).text
    assert 'never-store' not in exported and 'Private Real Name' not in exported and 'google_key' not in exported
    with app.state.store.connection() as db:
        user=dict(db.execute('SELECT * FROM users WHERE id=?',(id,)).fetchone())
        assert len(user['google_key'])==64
    mod=moderator();response=c.post('/v1/moderation/profiles/'+id,headers=mod,json=dict(decision='approve',approveAlias=True,approvePhoto=True,reason='Alias and optional profile image checked.'))
    assert response.status_code==200
    assert c.get('/v1/profiles/'+id).json()['alias']=='Rock Friend'
    assert c.get('/v1/photos/'+photo).status_code==200

@pytest.mark.parametrize('kind',['JPEG','PNG'])
def test_images_are_reencoded_without_metadata(kind):
    clean,w,h,sha=normalize_image(picture(kind,exif=kind=='JPEG'))
    image=Image.open(io.BytesIO(clean));assert image.format=='JPEG' and not image.getexif() and 'Location' not in image.info
    assert (w,h)==((80,120) if kind=='JPEG' else (120,80))
    assert b'Private camera owner' not in clean and len(sha)==64

def test_invalid_large_animated_images_and_ssrf_are_rejected(service):
    with pytest.raises(ValueError):normalize_image(b'<svg><script>bad</script></svg>')
    with pytest.raises(ValueError):normalize_image(picture(size=(4000,3100)))
    with pytest.raises(ValueError):google_photo('http://127.0.0.1/secret')
    with pytest.raises(ValueError):google_photo('https://lh3.googleusercontent.com.attacker.test/image')
    c,_,login,_,_=service;_,headers,_=login()
    assert c.post('/v1/photos',headers={**headers,'Content-Type':'image/jpeg','X-Photo-Id':str(uuid4())},content=b'x'*(8*1024*1024+1)).status_code==413

def test_private_media_and_draft_ownership(service):
    c,_,login,_,_=service;_,a,_=login();_,b,_=login();photo=upload(c,a)
    assert c.get('/v1/photos/'+photo['id']).status_code==404
    assert c.get('/v1/photos/'+photo['id'],headers=b).status_code==404
    assert c.get('/v1/photos/'+photo['id'],headers=a).status_code==200
    d=guide(photo);assert c.put('/v1/drafts/'+d['id'],headers=b,json=d).status_code==422
    assert submit(c,a,d).status_code==200
    assert c.get('/v1/drafts/'+d['id'],headers=b).status_code==404

def test_partial_submission_cannot_publish_or_gain_credit(service):
    c,_,login,moderator,_=service;account,h,_=login();mod=moderator()
    d=dict(id=str(uuid4()),crag=dict(name='Partial Cliff'),approach='Some original approach notes.',consent=True)
    response=submit(c,h,d);assert response.status_code==200 and 'photos' in response.json()['missing']
    assert approve(c,mod,d['id']).status_code==422
    assert c.get('/v1/crags').json()['crags']==[]
    assert c.get('/v1/me',headers=h).json()['acceptedContributions']==0

def test_self_approval_and_non_moderator_rejected(service):
    c,app,login,_,_=service;account,h,_=login();d=guide(upload(c,h));submit(c,h,d)
    assert approve(c,h,d['id']).status_code==403
    with app.state.store.connection() as db:db.execute('UPDATE users SET moderator=1 WHERE id=?',(account['profile']['id'],))
    assert approve(c,h,d['id']).status_code==403

def test_complete_guide_publication_and_idempotent_submission(service):
    c,_,login,moderator,_=service;account,h,_=login();mod=moderator();p=upload(c,h);d=guide(p)
    assert submit(c,h,d).json()['missing']==[]
    assert c.post('/v1/drafts/'+d['id']+'/submit',headers=h).json()['status']=='pending'
    assert approve(c,mod,d['id']).status_code==200
    public=c.get('/v1/crags').json()['crags'][0]
    assert public['contributorAlias']==account['profile']['alias'] and public['contributorAlias']!='Impersonated name'
    assert public['photos'][0]['sha256']==p['sha256']
    assert public['photoAttributions'][0]['contributorId']==account['profile']['id']
    assert c.get('/v1/photos/'+p['id']).status_code==200
    assert c.get('/v1/me',headers=h).json()['acceptedContributions']==1
    assert approve(c,mod,d['id']).status_code==409
    assert c.put('/v1/drafts/'+d['id'],headers=h,json=d).status_code==409

def test_descent_is_required_for_complete_guide(service):
    c,_,login,moderator,_=service;_,h,_=login();d=guide(upload(c,h));d['descent']=''
    assert 'descent' in submit(c,h,d).json()['missing']
    assert approve(c,moderator(),d['id']).status_code==422

def test_route_prose_and_parking_guidance_required(service):
    c,_,login,moderator,_=service;_,h,_=login();d=guide(upload(c,h));d['routes'][0]['description']='';d['parking']['description']=''
    missing=submit(c,h,d).json()['missing']
    assert 'routes[0].description' in missing and 'parking or parkingUnknownReason' in missing
    assert approve(c,moderator(),d['id']).status_code==422

def test_gallery_separate_gate_and_credit_not_per_photo(service):
    c,_,login,moderator,_=service;_,h,_=login();mod=moderator();photo=upload(c,h)
    for _ in range(2):
        d=guide(photo,'gallery');d.update(routes=[],approach='',descent='',access='',accessCheckedAt=None,parking=None)
        assert submit(c,h,d).json()['missing']==[];assert approve(c,mod,d['id']).status_code==200
    assert c.get('/v1/crags').json()['crags']==[] and len(c.get('/v1/gallery').json()['gallery'])==2
    assert c.get('/v1/me',headers=h).json()['acceptedContributions']==1

def test_approved_photo_reuse_and_route_amendment_preserve_authors(service):
    c,_,login,moderator,_=service;original,a,_=login();other,b,_=login();mod=moderator()
    p=upload(c,a);base=guide(p);submit(c,a,base);assert approve(c,mod,base['id']).status_code==200
    reuse={**p,'originalWork':False};addition=guide(reuse);addition['basePublicationId']=base['id'];addition.update(approach='',descent='',access='',accessCheckedAt=None,parking=None)
    assert submit(c,b,addition).status_code==200
    assert approve(c,mod,addition['id']).status_code==200
    public=c.get('/v1/crags').json()['crags'];assert len(public)==1 and len(public[0]['routes'])==2
    assert public[0]['photoAttributions'][0]['contributorId']==original['profile']['id']
    assert {r['contributorId'] for r in public[0]['routeAttributions']}=={original['profile']['id'],other['profile']['id']}
    for field in ('crag','approach','descent','access','accessCheckedAt','parking'):
        assert public[0]['sectionAttributions'][field]==[dict(contributorId=original['profile']['id'],alias=original['profile']['alias'],licence='CC-BY-SA-4.0',sourcePublicationId=base['id'],sourceUrl='/v1/publications/'+base['id'])]
    assert c.get('/v1/publications/'+base['id']).json()['superseded'] is True

def test_amended_sections_keep_original_and_new_author_with_stable_sources(service):
    c,_,login,moderator,_=service;original,a,_=login();other,b,_=login();mod=moderator()
    p=upload(c,a);base=guide(p);submit(c,a,base);assert approve(c,mod,base['id']).status_code==200
    addition=guide({**p,'originalWork':False});addition['basePublicationId']=base['id']
    addition['approach']=base['approach']+' Continue beside the wall to the north entrance.'
    addition['descent']='';addition['routes']=[]
    assert submit(c,b,addition).status_code==200;assert approve(c,mod,addition['id']).status_code==200
    public=c.get('/v1/crags').json()['crags'][0]
    credits=public['sectionAttributions']['approach']
    assert [x['contributorId'] for x in credits]==[original['profile']['id'],other['profile']['id']]
    assert [x['sourcePublicationId'] for x in credits]==[base['id'],addition['id']]
    assert public['sectionAttributions']['descent'][0]['contributorId']==original['profile']['id']
    assert all(c.get(x['sourceUrl']).status_code==200 for x in credits)

def test_deleted_section_author_is_anonymised_in_surviving_revision(service):
    c,_,login,moderator,_=service;original,a,_=login();other,b,_=login();third,z,_=login();mod=moderator()
    p=upload(c,a);base=guide(p);submit(c,a,base);assert approve(c,mod,base['id']).status_code==200
    addition=guide({**p,'originalWork':False});addition['basePublicationId']=base['id'];addition['approach']+=' Extra original guidance from the second contributor.'
    submit(c,b,addition);assert approve(c,mod,addition['id']).status_code==200
    final=guide({**p,'originalWork':False});final['basePublicationId']=addition['id'];final['approach']=''
    submit(c,z,final);assert approve(c,mod,final['id']).status_code==200
    assert c.request('DELETE','/v1/me',headers=b,json={'confirmation':'DELETE'}).status_code==200
    public=c.get('/v1/crags').json()['crags'][0]
    assert public['id']==final['id']
    for key in ('photoAttributions','routeAttributions'):
        assert all(x.get('contributorId')!=other['profile']['id'] for x in public[key])
    credits=public['sectionAttributions']['approach']
    assert credits[1]['contributorId'] is None and credits[1]['alias']=='Former contributor'
    assert other['profile']['alias'] not in json.dumps(public)

def test_reuse_cannot_claim_authorship_or_reference_private_image(service):
    c,_,login,moderator,_=service;_,a,_=login();_,b,_=login();p=upload(c,a)
    d=guide({**p,'originalWork':False});assert c.put('/v1/drafts/'+d['id'],headers=b,json=d).status_code==422
    base=guide(p);submit(c,a,base);approve(c,moderator(),base['id'])
    d['photos'][0]['originalWork']=True;assert c.put('/v1/drafts/'+d['id'],headers=b,json=d).status_code==422

def test_reports_hide_photo_and_dependent_guide(service):
    c,_,login,moderator,_=service;_,h,_=login();mod=moderator();p=upload(c,h);d=guide(p);submit(c,h,d);approve(c,mod,d['id'])
    report=c.post('/v1/reports',json=dict(targetType='photo',targetId=p['id'],reason='privacy',details='Please review the person depicted.')).json()
    assert c.post('/v1/moderation/reports/'+report['id'],headers=mod,json=dict(action='hide',reason='Temporary removal while privacy report is checked.')).status_code==200
    assert c.get('/v1/photos/'+p['id']).status_code==404 and c.get('/v1/crags').json()['crags']==[]
    assert c.get('/v1/me',headers=h).json()['acceptedContributions']==0

def test_account_deletion_removes_media_private_data_and_sessions(service):
    c,app,login,moderator,_=service;account,h,_=login();p=upload(c,h);d=guide(p);submit(c,h,d);approve(c,moderator(),d['id'])
    assert c.get('/v1/me/export',headers=h).json()['drafts']
    result=c.request('DELETE','/v1/me',headers=h,json={'confirmation':'DELETE'});assert result.status_code==200
    assert c.get('/v1/me',headers=h).status_code==401 and c.get('/v1/photos/'+p['id']).status_code==404
    assert c.get('/v1/profiles/'+account['profile']['id']).status_code==404
    with app.state.store.connection() as db:
        assert db.execute('SELECT count(*) FROM drafts').fetchone()[0]==0
        assert db.execute('SELECT owner_id,credit_alias,document FROM publications').fetchone()['document']=='{}'

def test_profile_hide_removes_all_embedded_public_aliases_and_pending_avatar(service):
    c,app,login,moderator,_=service;account,h,_=login(alias='Secret Alias',photo=True);mod=moderator();uid=account['profile']['id']
    c.post('/v1/moderation/profiles/'+uid,headers=mod,json=dict(decision='approve',approveAlias=True,approvePhoto=False,reason='Alias accepted for this test.'))
    d=guide(upload(c,h));submit(c,h,d);assert approve(c,mod,d['id']).status_code==200
    assert 'Secret Alias' in c.get('/v1/crags').text
    report=c.post('/v1/reports',json=dict(targetType='profile',targetId=uid,reason='privacy')).json()
    assert c.post('/v1/moderation/reports/'+report['id'],headers=mod,json=dict(action='hide',reason='Remove the reported personal alias everywhere.')).status_code==200
    assert 'Secret Alias' not in c.get('/v1/crags').text
    with app.state.store.connection() as db:assert db.execute('SELECT count(*) FROM profile_requests WHERE user_id=?',(uid,)).fetchone()[0]==0

def test_oversize_merged_publication_rejected_without_mutating_base(service):
    c,app,login,moderator,_=service;_,h,_=login();mod=moderator();p=upload(c,h);d=guide(p);submit(c,h,d);assert approve(c,mod,d['id']).status_code==200
    # Model an already stored large historical guide; new individual requests remain bounded.
    with app.state.store.connection() as db:
        row=db.execute('SELECT document FROM publications WHERE id=?',(d['id'],)).fetchone();doc=json.loads(row[0]);doc['routes']=[]
        for i in range(80):
            route={**d['routes'][0],'id':str(uuid4()),'description':'Original prose '*240,'lines':[[{'x':j/199,'y':j/199} for j in range(200)] for _ in range(2)]};doc['routes'].append(route)
        db.execute('UPDATE publications SET document=? WHERE id=?',(json.dumps(doc),d['id']))
    addition=guide(p);addition['basePublicationId']=d['id'];submit(c,h,addition)
    response=approve(c,mod,addition['id']);assert response.status_code==422 and '750000' in response.text
    with app.state.store.connection() as db:assert db.execute('SELECT superseded FROM publications WHERE id=?',(d['id'],)).fetchone()[0]==0

def test_bad_geometry_nan_and_duplicate_routes_rejected(service):
    c,_,login,_,_=service;_,h,_=login();d=guide(upload(c,h));d['routes'][0]['lines'][0][0]['x']=1.5
    assert c.put('/v1/drafts/'+d['id'],headers=h,json=d).status_code==422
    d['routes'][0]['lines'][0][0]['x']=.2;d['routes'].append(d['routes'][0])
    assert c.put('/v1/drafts/'+d['id'],headers=h,json=d).status_code==422

def test_logout_revokes_session_and_unconfigured_signin_is_explicit(service,tmp_path):
    c,_,login,_,_=service;_,h,_=login();assert c.post('/v1/auth/logout',headers=h).status_code==200
    assert c.get('/v1/me',headers=h).status_code==401
    local=TestClient(create_app(tmp_path/'disabled',client_id=''))
    challenge=local.get('/v1/auth/challenge').json()
    assert local.post('/v1/auth/google',json=dict(idToken='x'*30,challengeId=challenge['challengeId'],termsAccepted=True,licenceAccepted=True)).status_code==503

@pytest.mark.parametrize('system,grade',[('v','V0+'),('v','V4/V5'),('french','3b'),('font','7A+'),('ukTrad','HVS'),('ukTrad','HS 4b'),('ukTrad','E1 5a'),('ukTech','3')])
def test_native_grade_variants(system,grade):assert grade_valid(system,grade)
