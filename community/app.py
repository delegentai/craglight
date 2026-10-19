"""CragLight community service. No public data changes without human review."""
import hashlib
import hmac
import json
import os
import pathlib
import re
import secrets
import sqlite3
import time
from datetime import date,datetime,timezone
from urllib.parse import urlparse
from uuid import UUID,uuid4
from fastapi import FastAPI,HTTPException,Request,Response
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from models import Login,ProfileEdit,Draft,Report,Review,ReportReview,ProfileReview,DeleteAccount
from security import GoogleVerifier,normalize_image,google_photo,MAX_IMAGE_BYTES
from storage import Store,load_secret

def stamp(value):return datetime.fromtimestamp(value,timezone.utc).isoformat().replace('+00:00','Z')
def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def fail(status,message):raise HTTPException(status,message)

def publication_credits(document):
    for key in ('photoAttributions','routeAttributions'):
        yield from document.get(key,[])
    for credits in document.get('sectionAttributions',{}).values():
        yield from credits

def grade_valid(system,value):
    patterns={
        'ukTrad':r'(?:M|D|Diff|VD|VDiff|HVD|S|HS|VS|HVS|E(?:[1-9]|1[0-2]))(?:\s+[3-8][abc])?',
        'ukTech':r'(?:[1-3]|[3-8][abc])',
        'french':r'(?:[1-3][abc]?|[4-9][abc]\+?)',
        'font':r'(?:[1-5][ABCabc]?\+?|[6-9][ABCabc]\+?)',
        'v':r'V(?:B|[0-9]|1[0-9])[-+]?'
    }
    parts=value.strip().split('/')
    return len(parts)<=2 and all(re.fullmatch(patterns.get(system,'(?!)'),p.strip()) for p in parts)

def missing_fields(draft):
    missing=[]
    if len(draft.crag.name)<2:missing.append('crag.name')
    if draft.crag.lat is None or draft.crag.lon is None:missing.append('crag.coordinates')
    if not draft.photos:missing.append('photos')
    if not draft.consent:missing.append('contribution licence consent')
    if draft.kind=='gallery':
        if len(draft.caption)<5:missing.append('caption')
        return missing
    if len(draft.approach)<20:missing.append('approach')
    if len(draft.descent)<10:missing.append('descent')
    if len(draft.access)<10:missing.append('access')
    if draft.accessCheckedAt is None or draft.accessCheckedAt>date.today():missing.append('accessCheckedAt')
    if not (draft.parking and draft.parking.lat is not None and draft.parking.lon is not None and len(draft.parking.description)>=10) and len(draft.parkingUnknownReason)<10:
        missing.append('parking or parkingUnknownReason')
    if not draft.routes:missing.append('routes')
    photo_ids={p.id for p in draft.photos}
    for i,route in enumerate(draft.routes):
        if not route.name:missing.append(f'routes[{i}].name')
        if len(route.description)<10:missing.append(f'routes[{i}].description')
        if not grade_valid(route.gradeSystem,route.grade):missing.append(f'routes[{i}].grade')
        if route.photoId not in photo_ids or not route.lines:missing.append(f'routes[{i}].photo line')
    return missing

class BodyLimit:
    def __init__(self,app):self.app=app
    async def __call__(self,scope,receive,send):
        if scope['type']!='http':return await self.app(scope,receive,send)
        headers=dict(scope['headers']);is_image=headers.get(b'content-type',b'').split(b';')[0] in (b'image/jpeg',b'image/png')
        limit=MAX_IMAGE_BYTES if is_image else 512*1024
        data=bytearray()
        while True:
            event=await receive()
            if event['type']=='http.disconnect':return
            data.extend(event.get('body',b''))
            if len(data)>limit:
                return await JSONResponse({'detail':'Request body too large'},413)(scope,receive,send)
            if not event.get('more_body'):break
        delivered=False
        async def buffered():
            nonlocal delivered
            if not delivered:
                delivered=True;return {'type':'http.request','body':bytes(data),'more_body':False}
            return await receive()
        await self.app(scope,buffered,send)

def create_app(state_dir=None,client_id=None,verifier=None,photo_fetcher=None):
    state=pathlib.Path(state_dir or os.environ.get('CRAGLIGHT_COMMUNITY_STATE',pathlib.Path(__file__).parent/'.state'))
    store=Store(state/'community.sqlite');secret=load_secret(state)
    audience=client_id if client_id is not None else os.environ.get('CRAGLIGHT_GOOGLE_CLIENT_ID','')
    verify=verifier or GoogleVerifier(audience)
    fetch_picture=photo_fetcher or google_photo
    app=FastAPI(title='CragLight Community',version='1.0.0',docs_url=None,redoc_url=None,openapi_url=None)
    app.add_middleware(BodyLimit)
    app.state.store=store

    def keyed(value):return hmac.new(secret,value.encode(),hashlib.sha256).hexdigest()
    def rate(request,action,limit=30,period=3600,user=None):
        now=int(time.time());window=now//period
        identity=user or (request.client.host if request.client else 'unknown')
        key=keyed(f'{action}:{window}:{identity}')
        with store.connection() as db:
            db.execute('DELETE FROM rates WHERE expires<?',(now,))
            db.execute('INSERT INTO rates VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1',(key,(window+1)*period))
            count=db.execute('SELECT count FROM rates WHERE key=?',(key,)).fetchone()[0]
        if count>limit:fail(429,'Please try again later')

    def user(request,optional=False):
        authorization=request.headers.get('authorization','')
        if not authorization.startswith('Bearer '):
            if optional:return None
            fail(401,'Sign in required')
        token=authorization[7:]
        if not 32<=len(token)<=256:fail(401,'Invalid session')
        with store.connection() as db:
            row=db.execute('SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires>?',(digest(token),int(time.time()))).fetchone()
        if not row:fail(401,'Session expired or revoked')
        return dict(row)

    def moderator(request):
        account=user(request)
        if not account['moderator']:fail(403,'Moderator required')
        return account

    def profile(account):
        with store.connection() as db:
            count=db.execute('SELECT count(*) FROM credits c JOIN publications p ON p.id=c.publication_id WHERE user_id=? AND p.hidden=0',(account['id'],)).fetchone()[0]
        return dict(id=account['id'],alias=account['alias'],pendingAlias=account['pending_alias'],photoUrl=f"/v1/photos/{account['avatar_id']}" if account['avatar_id'] else None,acceptedContributions=count)

    def owned_draft(db,id,account):
        row=db.execute('SELECT * FROM drafts WHERE id=? AND owner_id=?',(str(id),account['id'])).fetchone()
        if not row:fail(404,'Draft not found')
        return dict(row)

    def verify_photos(db,draft,account):
        for photo in draft.photos:
            row=db.execute('SELECT width,height,sha256,owner_id FROM photos WHERE id=? AND hidden=0',(str(photo.id),)).fetchone()
            if not row or row['sha256']!=photo.sha256 or row['width']!=photo.width or row['height']!=photo.height:
                fail(422,'Photo missing, unavailable, not owned, or metadata does not match server image')
            if photo.originalWork and row['owner_id']!=account['id']:fail(422,'Cannot claim another contributor’s photograph as original work')
            if not photo.originalWork:
                public=db.execute('SELECT 1 FROM publication_photos pp JOIN publications p ON p.id=pp.publication_id WHERE pp.photo_id=? AND p.hidden=0 LIMIT 1',(str(photo.id),)).fetchone()
                if not public:fail(422,'Referenced photograph must have an approved public CC BY-SA record')

    def effective_draft(db,draft):
        if not draft.basePublicationId:return draft,None
        base=db.execute('SELECT * FROM publications WHERE id=? AND kind=\'guide\' AND hidden=0 AND superseded=0',(str(draft.basePublicationId),)).fetchone()
        if not base or draft.kind!='guide':fail(409,'Current approved base guide required; refresh before proposing a revision')
        old=json.loads(base['document']);new=draft.model_dump(mode='json')
        if draft.crag.existingId and old['crag'].get('existingId') and draft.crag.existingId!=old['crag']['existingId']:fail(422,'Amendment cannot move to a different crag')
        new['crag']={k:v for k,v in old['crag'].items()}
        for key in ('approach','descent','access','accessCheckedAt','parking','parkingUnknownReason'):
            if not new.get(key):new[key]=old.get(key)
        existing_routes={r['id'] for r in old['routes']}
        if any(str(r.id) in existing_routes for r in draft.routes):fail(422,'Route additions require fresh IDs; existing routes are immutable in this amendment flow')
        new['routes']=old['routes']+new['routes']
        photos={p['id']:{**p,'originalWork':False} for p in old['photos']}
        photos.update({p['id']:p for p in new['photos']});new['photos']=list(photos.values())
        try:merged=Draft.model_validate(new)
        except ValidationError:fail(422,'Merged guide exceeds route/photo/geometry limits; split or simplify the proposal')
        return merged,dict(base)

    def draft_response(row):
        doc=json.loads(row['document']);draft=Draft.model_validate(doc)
        return dict(id=row['id'],status=row['status'],document=doc,missing=missing_fields(draft),reason=row['reason'],updatedAt=stamp(row['updated_at']))

    def publication(row):
        result=json.loads(row['document'])
        result.update(id=row['id'],kind=row['kind'],cragId=row['crag_key'],publishedAt=stamp(row['published_at']),superseded=bool(row['superseded']),contributor=dict(id=row['owner_id'],alias=row['credit_alias']),licence='CC-BY-SA-4.0')
        return result

    def page(items,offset):
        result=[];size=0
        for item in items:
            cost=len(json.dumps(item,ensure_ascii=False).encode('utf-8'))+2
            if size+cost>2_500_000:break
            result.append(item);size+=cost
        return result,offset+len(result)

    @app.middleware('http')
    async def headers(request,call_next):
        response=await call_next(request)
        response.headers['X-Content-Type-Options']='nosniff'
        response.headers['Referrer-Policy']='no-referrer'
        response.headers['Content-Security-Policy']="default-src 'none'; frame-ancestors 'none'; sandbox"
        response.headers['Cache-Control']='no-store'
        return response

    @app.get('/health')
    def health():return {'status':'ok','googleConfigured':bool(audience),'service':'craglight-community','version':1}

    @app.get('/v1/auth/challenge')
    def challenge(request:Request):
        rate(request,'challenge',30,600)
        id=str(uuid4());nonce=secrets.token_urlsafe(32);expires=int(time.time())+300
        with store.connection() as db:
            db.execute('DELETE FROM challenges WHERE expires<?',(int(time.time()),))
            db.execute('DELETE FROM sessions WHERE expires<?',(int(time.time()),))
            db.execute('INSERT INTO challenges VALUES(?,?,?)',(id,digest(nonce),expires))
        return {'challengeId':id,'nonce':nonce,'expiresAt':stamp(expires)}

    @app.post('/v1/auth/google')
    def login(body:Login,request:Request):
        rate(request,'login',30,600)
        if not audience:fail(503,'Google sign-in is not configured for this deployment')
        if not body.termsAccepted or not body.licenceAccepted:fail(422,'Terms and contribution licence must be accepted')
        with store.connection() as db:challenge=db.execute('SELECT * FROM challenges WHERE id=? AND expires>?',(str(body.challengeId),int(time.time()))).fetchone()
        if not challenge:fail(401,'Challenge expired or already used')
        try:claims=verify(body.idToken)
        except Exception:fail(401,'Google token verification failed')
        nonce=claims.get('nonce')
        if not isinstance(nonce,str) or not hmac.compare_digest(digest(nonce),challenge['nonce_hash']):fail(401,'Google token nonce does not match challenge')
        now=int(time.time());expires=now+7*86400;token=secrets.token_urlsafe(48)
        google_key=keyed('google:'+claims['sub'])
        picture=None;picture_status='not_requested'
        if body.useGooglePhoto and claims.get('picture'):
            try:picture=fetch_picture(claims['picture']);picture_status='pending_review'
            except Exception:picture_status='unavailable'
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            consumed=db.execute('DELETE FROM challenges WHERE id=? AND expires>?',(str(body.challengeId),now)).rowcount
            if consumed!=1:fail(401,'Challenge expired or already used')
            row=db.execute('SELECT * FROM users WHERE google_key=?',(google_key,)).fetchone()
            if not row:
                id=str(uuid4());alias='Climber-'+secrets.token_hex(4)
                db.execute('INSERT INTO users(id,google_key,alias,pending_alias,created_at) VALUES(?,?,?,?,?)',(id,google_key,alias,body.publicAlias,now))
            else:
                id=row['id']
                if body.publicAlias:db.execute('UPDATE users SET pending_alias=? WHERE id=?',(body.publicAlias,id))
            if picture:
                data,width,height,sha=picture;photo_id=str(uuid4())
                used=db.execute('SELECT coalesce(sum(length(data)),0) FROM photos').fetchone()[0]
                count=db.execute('SELECT count(*) FROM photos WHERE owner_id=?',(id,)).fetchone()[0]
                if used+len(data)>1024**3 or count>=100:fail(507,'Photo storage allowance reached')
                db.execute('INSERT INTO photos(id,owner_id,sha256,width,height,data,created_at) VALUES(?,?,?,?,?,?,?)',(photo_id,id,sha,width,height,data,now))
                db.execute('INSERT INTO profile_requests VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET photo_id=excluded.photo_id',(id,photo_id))
                # This private image must be explicitly approved as an avatar by a moderator.
                picture_status={'status':'pending_review','photoId':photo_id}
            db.execute('INSERT INTO sessions VALUES(?,?,?)',(digest(token),id,expires))
            account=dict(db.execute('SELECT * FROM users WHERE id=?',(id,)).fetchone())
        return dict(token=token,expiresAt=stamp(expires),profile=profile(account),googlePhoto=picture_status)

    @app.get('/v1/me')
    def me(request:Request):return profile(user(request))

    @app.patch('/v1/me')
    def edit_profile(body:ProfileEdit,request:Request):
        account=user(request);rate(request,'profile',10,86400,account['id'])
        with store.connection() as db:
            if body.publicAlias:db.execute('UPDATE users SET pending_alias=? WHERE id=?',(body.publicAlias,account['id']))
            if body.removePhoto:
                if account['avatar_id']:db.execute('UPDATE photos SET hidden=1 WHERE id=?',(account['avatar_id'],))
                db.execute('UPDATE users SET avatar_id=NULL WHERE id=?',(account['id'],))
                db.execute('DELETE FROM profile_requests WHERE user_id=?',(account['id'],))
            account=dict(db.execute('SELECT * FROM users WHERE id=?',(account['id'],)).fetchone())
        return profile(account)

    @app.post('/v1/auth/logout')
    def logout(request:Request):
        user(request)
        with store.connection() as db:db.execute('DELETE FROM sessions WHERE token_hash=?',(digest(request.headers['authorization'][7:]),))
        return {'status':'signed_out'}

    @app.get('/v1/profiles/{id}')
    def public_profile(id:UUID):
        with store.connection() as db:row=db.execute('SELECT * FROM users WHERE id=?',(str(id),)).fetchone()
        if not row:fail(404,'Profile not found')
        result=profile(dict(row));result.pop('pendingAlias');return result

    @app.post('/v1/photos')
    async def upload(request:Request):
        account=user(request);rate(request,'upload',20,86400,account['id'])
        if request.headers.get('content-type','').split(';')[0] not in ('image/jpeg','image/png'):fail(415,'JPEG or PNG required')
        try:id=str(UUID(request.headers.get('x-photo-id','')))
        except ValueError:fail(422,'X-Photo-Id must be a UUID')
        data=await request.body()
        try:clean,width,height,sha=await run_in_threadpool(normalize_image,data)
        except ValueError as exc:fail(422,str(exc))
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            old=db.execute('SELECT * FROM photos WHERE id=?',(id,)).fetchone()
            if old:
                if old['owner_id']!=account['id'] or old['sha256']!=sha:fail(409,'Photo ID is already in use')
                return dict(id=id,width=old['width'],height=old['height'],sha256=sha,status='private')
            used=db.execute('SELECT coalesce(sum(length(data)),0) FROM photos').fetchone()[0]
            own=db.execute('SELECT count(*) FROM photos WHERE owner_id=?',(account['id'],)).fetchone()[0]
            if used+len(clean)>1024**3 or own>=100:fail(507,'Photo storage allowance reached; contact the maintainer')
            db.execute('INSERT INTO photos(id,owner_id,sha256,width,height,data,created_at) VALUES(?,?,?,?,?,?,?)',(id,account['id'],sha,width,height,clean,int(time.time())))
        return dict(id=id,width=width,height=height,sha256=sha,status='private')

    @app.get('/v1/photos/{id}')
    def photo(id:UUID,request:Request):
        account=user(request,optional=True)
        with store.connection() as db:
            row=db.execute('SELECT * FROM photos WHERE id=? AND hidden=0',(str(id),)).fetchone()
            public=db.execute('SELECT 1 FROM publication_photos pp JOIN publications p ON p.id=pp.publication_id WHERE pp.photo_id=? AND p.hidden=0 UNION SELECT 1 FROM users WHERE avatar_id=? LIMIT 1',(str(id),str(id))).fetchone()
        if not row or (not public and (not account or (row['owner_id']!=account['id'] and not account['moderator']))):fail(404,'Photo not found')
        return Response(row['data'],media_type='image/jpeg',headers={'Content-Disposition':'inline; filename="craglight-photo.jpg"'})

    @app.put('/v1/drafts/{id}')
    def save_draft(id:UUID,body:Draft,request:Request):
        account=user(request);rate(request,'draft-save',200,86400,account['id'])
        if id!=body.id:fail(422,'Path and document IDs differ')
        now=int(time.time());document=body.model_dump_json()
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE');verify_photos(db,body,account)
            old=db.execute('SELECT * FROM drafts WHERE id=?',(str(id),)).fetchone()
            if old and old['owner_id']!=account['id']:fail(404,'Draft not found')
            if old and old['status'] not in ('draft','changes_requested'):fail(409,'Submitted draft is immutable; create a new revision')
            if not old and db.execute('SELECT count(*) FROM drafts WHERE owner_id=?',(account['id'],)).fetchone()[0]>=100:fail(429,'Draft allowance reached')
            db.execute('INSERT INTO drafts VALUES(?,?,?, ?,?,?,NULL) ON CONFLICT(id) DO UPDATE SET document=excluded.document,status=\'draft\',updated_at=excluded.updated_at,reason=NULL',(str(id),account['id'],'draft',document,now,now))
            row=dict(db.execute('SELECT * FROM drafts WHERE id=?',(str(id),)).fetchone())
        return draft_response(row)

    @app.get('/v1/drafts')
    def drafts(request:Request,limit:int=50,offset:int=0):
        account=user(request)
        if not 1<=limit<=100 or offset<0:fail(422,'Invalid pagination')
        with store.connection() as db:rows=db.execute('SELECT * FROM drafts WHERE owner_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?',(account['id'],limit,offset)).fetchall()
        result,next_offset=page((draft_response(dict(row)) for row in rows),offset)
        return {'drafts':result,'nextOffset':next_offset if rows and (len(rows)==limit or len(result)<len(rows)) else None}

    @app.get('/v1/drafts/{id}')
    def draft(id:UUID,request:Request):
        account=user(request)
        with store.connection() as db:row=owned_draft(db,id,account)
        return draft_response(row)

    @app.delete('/v1/drafts/{id}')
    def remove_draft(id:UUID,request:Request):
        account=user(request)
        with store.connection() as db:
            row=owned_draft(db,id,account)
            if row['status']=='accepted':fail(409,'Accepted contribution requires a removal report or account deletion')
            db.execute('DELETE FROM drafts WHERE id=?',(str(id),))
        return {'status':'deleted'}

    @app.post('/v1/drafts/{id}/submit')
    def submit(id:UUID,request:Request):
        account=user(request);rate(request,'submit',10,86400,account['id'])
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE');row=owned_draft(db,id,account);body=Draft.model_validate_json(row['document'])
            if row['status'] in ('pending','accepted','rejected'):return draft_response(row)
            if not body.crag.name and not body.crag.existingId:fail(422,'Name or existing crag ID required for review')
            if not body.consent:fail(422,'Contribution licence consent is required')
            if not (body.photos or body.routes or body.approach or body.access or body.caption):fail(422,'Add authored content before submitting')
            verify_photos(db,body,account)
            effective,_=effective_draft(db,body)
            db.execute('UPDATE drafts SET status=\'pending\',updated_at=? WHERE id=?',(int(time.time()),str(id)))
        return {'id':str(id),'status':'pending','missing':missing_fields(effective)}

    @app.get('/v1/crags')
    def crags(limit:int=50,offset:int=0):
        if not 1<=limit<=100 or offset<0:fail(422,'Invalid pagination')
        with store.connection() as db:rows=db.execute('SELECT * FROM publications WHERE kind=\'guide\' AND hidden=0 AND superseded=0 ORDER BY published_at DESC,id LIMIT ? OFFSET ?',(limit,offset)).fetchall()
        result,next_offset=page((publication(row) for row in rows),offset)
        return {'crags':result,'nextOffset':next_offset if rows and (len(rows)==limit or len(result)<len(rows)) else None}

    @app.get('/v1/crags/{id}')
    def crag(id:UUID):
        with store.connection() as db:row=db.execute('SELECT * FROM publications WHERE id=? AND kind=\'guide\' AND hidden=0',(str(id),)).fetchone()
        if not row:fail(404,'Guide not found')
        return publication(row)

    @app.get('/v1/gallery')
    def gallery(cragId:str|None=None,limit:int=50,offset:int=0):
        if not 1<=limit<=100 or offset<0:fail(422,'Invalid pagination')
        with store.connection() as db:rows=db.execute('SELECT * FROM publications WHERE kind=\'gallery\' AND hidden=0 AND (? IS NULL OR crag_key=?) ORDER BY published_at DESC,id LIMIT ? OFFSET ?',(cragId,cragId,limit,offset)).fetchall()
        result,next_offset=page((publication(row) for row in rows),offset)
        return {'gallery':result,'nextOffset':next_offset if rows and (len(rows)==limit or len(result)<len(rows)) else None}

    @app.get('/v1/publications/{id}')
    def public_record(id:UUID):
        with store.connection() as db:row=db.execute('SELECT * FROM publications WHERE id=? AND hidden=0',(str(id),)).fetchone()
        if not row:fail(404,'Publication not found')
        return publication(row)

    @app.post('/v1/reports')
    def report(body:Report,request:Request):
        account=user(request,optional=True);rate(request,'report',10,86400,account['id'] if account else None)
        id=str(uuid4())
        with store.connection() as db:
            table={'crag':'publications','gallery':'publications','photo':'photos','profile':'users'}[body.targetType]
            if not db.execute(f'SELECT 1 FROM {table} WHERE id=?',(body.targetId,)).fetchone():fail(404,'Target not found')
            db.execute('INSERT INTO reports(id,reporter_id,target_type,target_id,reason,details,created_at) VALUES(?,?,?,?,?,?,?)',(id,account['id'] if account else None,body.targetType,body.targetId,body.reason,body.details,int(time.time())))
        return {'id':id,'status':'pending'}

    @app.get('/v1/me/export')
    def export(request:Request):
        account=user(request);rate(request,'export',5,3600,account['id'])
        with store.connection() as db:
            drafts=[draft_response(dict(row)) for row in db.execute('SELECT * FROM drafts WHERE owner_id=?',(account['id'],))]
            uploads=[dict(row) for row in db.execute('SELECT id,sha256,width,height,created_at,hidden FROM photos WHERE owner_id=?',(account['id'],))]
            reports=[dict(row) for row in db.execute('SELECT id,target_type,target_id,reason,details,status,resolution,created_at FROM reports WHERE reporter_id=?',(account['id'],))]
            accepted=[publication(row) for row in db.execute('SELECT * FROM publications WHERE owner_id=?',(account['id'],))]
        return {'schemaVersion':1,'exportedAt':stamp(time.time()),'profile':profile(account),'drafts':drafts,'photos':uploads,'reports':reports,'acceptedContributions':accepted,'photoDownload':'Use authenticated GET /v1/photos/{id} for each non-hidden original normalized photo.'}

    @app.delete('/v1/me')
    def delete_account(body:DeleteAccount,request:Request):
        account=user(request)
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            count=db.execute('SELECT count(*) FROM publications WHERE owner_id=?',(account['id'],)).fetchone()[0]
            # Withdraw public images and dependent guides; retain an anonymised, non-public review history.
            for row in db.execute('SELECT id,document FROM publications').fetchall():
                doc=json.loads(row['document'])
                for credit in publication_credits(doc):
                    if credit.get('contributorId')==account['id']:credit.update(contributorId=None,alias='Former contributor')
                db.execute('UPDATE publications SET document=? WHERE id=?',(json.dumps(doc,ensure_ascii=False),row['id']))
            db.execute('UPDATE publications SET hidden=1,credit_alias=\'Former contributor\',document=\'{}\' WHERE owner_id=?',(account['id'],))
            db.execute('UPDATE publications SET hidden=1 WHERE id IN (SELECT publication_id FROM publication_photos pp JOIN photos ph ON ph.id=pp.photo_id WHERE ph.owner_id=?)',(account['id'],))
            db.execute('DELETE FROM photos WHERE owner_id=?',(account['id'],))
            db.execute('UPDATE reviews SET reason=\'Contribution withdrawn on account deletion\',evidence_url=NULL WHERE draft_id IN (SELECT id FROM drafts WHERE owner_id=?)',(account['id'],))
            db.execute('UPDATE reports SET details=\'Removed on account deletion\' WHERE reporter_id=?',(account['id'],))
            db.execute('DELETE FROM users WHERE id=?',(account['id'],))
        return {'status':'deleted','withdrawnApprovedContributions':count,'retainedApprovedContributions':0,'note':'Prior public copies and exports cannot be recalled.'}

    @app.get('/v1/moderation/drafts')
    def queue(request:Request):
        moderator(request)
        with store.connection() as db:rows=db.execute('SELECT * FROM drafts WHERE status=\'pending\' ORDER BY created_at LIMIT 100').fetchall()
        return {'drafts':[dict(**draft_response(dict(row)),ownerId=row['owner_id']) for row in rows]}

    @app.post('/v1/moderation/drafts/{id}')
    def review(id:UUID,body:Review,request:Request):
        mod=moderator(request)
        if body.evidenceUrl and (urlparse(body.evidenceUrl).scheme!='https' or not urlparse(body.evidenceUrl).hostname):fail(422,'Evidence URL must be HTTPS')
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE');row=db.execute('SELECT * FROM drafts WHERE id=?',(str(id),)).fetchone()
            if not row or row['status']!='pending':fail(409,'Pending draft required')
            if row['owner_id']==mod['id']:fail(403,'Self-approval/review is not allowed')
            draft=Draft.model_validate_json(row['document'])
            owner=dict(db.execute('SELECT * FROM users WHERE id=?',(row['owner_id'],)).fetchone())
            if body.decision=='approve':
                draft,base=effective_draft(db,draft)
                missing=missing_fields(draft)
                if missing:fail(422,{'message':'Publication gate incomplete','missing':missing})
                if not(body.rightsChecked and body.safetyChecked and body.accessChecked and body.completenessChecked):fail(422,'All human review attestations required')
                verify_photos(db,draft,owner)
                target=draft.crag.existingId or f"{draft.crag.name.casefold()}:{draft.crag.lat:.4f},{draft.crag.lon:.4f}"
                if draft.kind=='guide' and not base and db.execute('SELECT 1 FROM publications WHERE kind=\'guide\' AND crag_key=? AND hidden=0 AND superseded=0',(target,)).fetchone():fail(409,'Use basePublicationId to amend the existing approved guide')
                document=draft.model_dump(mode='json');document['contributorAlias']=owner['alias']
                document['photoAttributions']=[]
                for photo in draft.photos:
                    photo_owner=db.execute('SELECT u.id,u.alias FROM photos ph LEFT JOIN users u ON u.id=ph.owner_id WHERE ph.id=?',(str(photo.id),)).fetchone()
                    origin=db.execute('SELECT p.id FROM publications p JOIN publication_photos pp ON pp.publication_id=p.id WHERE pp.photo_id=? AND p.hidden=0 ORDER BY p.published_at,p.id LIMIT 1',(str(photo.id),)).fetchone()
                    origin_id=origin['id'] if origin else str(id)
                    document['photoAttributions'].append(dict(photoId=str(photo.id),contributorId=photo_owner['id'],alias=photo_owner['alias'] or 'Former contributor',licence='CC-BY-SA-4.0',sourcePublicationId=origin_id,sourceUrl='/v1/publications/'+origin_id))
                prior=json.loads(base['document']).get('routeAttributions',[]) if base else []
                prior_ids={x['routeId'] for x in prior}
                document['routeAttributions']=prior+[dict(routeId=str(r.id),contributorId=owner['id'],alias=owner['alias'],licence='CC-BY-SA-4.0',sourcePublicationId=str(id)) for r in draft.routes if str(r.id) not in prior_ids]
                old=json.loads(base['document']) if base else {}
                document['sectionAttributions']={}
                def section_credit(contributor_id,alias,publication_id):
                    return dict(contributorId=contributor_id,alias=alias,licence='CC-BY-SA-4.0',sourcePublicationId=publication_id,sourceUrl='/v1/publications/'+publication_id)
                for field in ('crag','approach','descent','access','accessCheckedAt','parking','parkingUnknownReason','caption'):
                    if not document.get(field):continue
                    credits=[]
                    if base and old.get(field):
                        # Keep attribution on both inherited text and subsequent adaptations.
                        credits=old.get('sectionAttributions',{}).get(field) or [section_credit(base['owner_id'],base['credit_alias'],base['id'])]
                    if (not base or document.get(field)!=old.get(field)) and not any(c.get('contributorId')==owner['id'] for c in credits):
                        credits=credits+[section_credit(owner['id'],owner['alias'],str(id))]
                    document['sectionAttributions'][field]=credits
                if len(json.dumps(document,ensure_ascii=False).encode('utf-8'))>750_000:fail(422,'Merged publication exceeds 750000 bytes; simplify route geometry or split the proposal')
                # Canonical publication uses current account credit, never an impersonated client string.
                db.execute('INSERT INTO publications(id,owner_id,kind,crag_key,document,published_at,credit_alias) VALUES(?,?,?,?,?,?,?)',(str(id),owner['id'],draft.kind,target,json.dumps(document,ensure_ascii=False),int(time.time()),owner['alias']))
                if base:db.execute('UPDATE publications SET superseded=1 WHERE id=?',(base['id'],))
                for photo in draft.photos:db.execute('INSERT INTO publication_photos VALUES(?,?)',(str(id),str(photo.id)))
                # One credit per physical crag/kind and contributor, irrespective of photo/route counts.
                db.execute('INSERT OR IGNORE INTO credits VALUES(?,?,?)',(owner['id'],draft.kind+':'+target,str(id)))
                status='accepted'
            else:status='rejected' if body.decision=='reject' else 'changes_requested'
            db.execute('UPDATE drafts SET status=?,reason=?,updated_at=? WHERE id=?',(status,body.reason,int(time.time()),str(id)))
            db.execute('INSERT INTO reviews VALUES(?,?,?,?,?,?,?)',(str(uuid4()),str(id),mod['id'],body.decision,body.reason,body.evidenceUrl,int(time.time())))
        return {'id':str(id),'status':status}

    @app.get('/v1/moderation/reports')
    def report_queue(request:Request):
        moderator(request)
        with store.connection() as db:rows=db.execute('SELECT id,target_type,target_id,reason,details,status,created_at FROM reports WHERE status=\'pending\' ORDER BY created_at LIMIT 100').fetchall()
        return {'reports':[dict(row) for row in rows]}

    @app.get('/v1/moderation/profiles')
    def profile_queue(request:Request):
        moderator(request)
        with store.connection() as db:rows=db.execute('SELECT u.id,u.alias,u.pending_alias AS pendingAlias,p.photo_id AS pendingPhotoId FROM users u LEFT JOIN profile_requests p ON p.user_id=u.id WHERE u.pending_alias IS NOT NULL OR p.photo_id IS NOT NULL LIMIT 100').fetchall()
        return {'profiles':[dict(row) for row in rows]}

    @app.post('/v1/moderation/profiles/{id}')
    def review_profile(id:UUID,body:ProfileReview,request:Request):
        mod=moderator(request)
        if str(id)==mod['id']:fail(403,'Self review is not allowed')
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE');owner=db.execute('SELECT * FROM users WHERE id=?',(str(id),)).fetchone()
            if not owner:fail(404,'Profile not found')
            pending=db.execute('SELECT photo_id FROM profile_requests WHERE user_id=?',(str(id),)).fetchone()
            if body.decision=='approve':
                if body.approveAlias and owner['pending_alias']:
                    db.execute('UPDATE users SET alias=pending_alias,pending_alias=NULL WHERE id=?',(str(id),))
                if body.approvePhoto and pending:
                    db.execute('UPDATE users SET avatar_id=? WHERE id=?',(pending['photo_id'],str(id)))
                    db.execute('DELETE FROM profile_requests WHERE user_id=?',(str(id),))
            else:
                db.execute('UPDATE users SET pending_alias=NULL WHERE id=?',(str(id),))
                db.execute('DELETE FROM profile_requests WHERE user_id=?',(str(id),))
            db.execute('INSERT INTO reviews VALUES(?,?,?,?,?,?,?)',(str(uuid4()),None,mod['id'],'profile:'+body.decision,body.reason,None,int(time.time())))
        return {'status':body.decision}

    @app.post('/v1/moderation/reports/{id}')
    def review_report(id:UUID,body:ReportReview,request:Request):
        mod=moderator(request)
        with store.connection() as db:
            db.execute('BEGIN IMMEDIATE');row=db.execute('SELECT * FROM reports WHERE id=? AND status=\'pending\'',(str(id),)).fetchone()
            if not row:fail(404,'Pending report not found')
            if body.action=='hide':
                if row['target_type'] in ('crag','gallery'):db.execute('UPDATE publications SET hidden=1 WHERE id=?',(row['target_id'],))
                elif row['target_type']=='photo':
                    db.execute('UPDATE photos SET hidden=1 WHERE id=?',(row['target_id'],))
                    db.execute('UPDATE publications SET hidden=1 WHERE id IN (SELECT publication_id FROM publication_photos WHERE photo_id=?)',(row['target_id'],))
                    db.execute('UPDATE users SET avatar_id=NULL WHERE avatar_id=?',(row['target_id'],))
                else:
                    db.execute('UPDATE users SET alias=\'Contributor\',pending_alias=NULL,avatar_id=NULL WHERE id=?',(row['target_id'],))
                    db.execute('DELETE FROM profile_requests WHERE user_id=?',(row['target_id'],))
                    db.execute('UPDATE publications SET credit_alias=\'Contributor\' WHERE owner_id=?',(row['target_id'],))
                    for published in db.execute('SELECT id,owner_id,document FROM publications').fetchall():
                        doc=json.loads(published['document'])
                        if published['owner_id']==row['target_id']:doc['contributorAlias']='Contributor'
                        for credit in publication_credits(doc):
                            if credit.get('contributorId')==row['target_id']:credit['alias']='Contributor'
                        db.execute('UPDATE publications SET document=? WHERE id=?',(json.dumps(doc,ensure_ascii=False),published['id']))
            db.execute('UPDATE reports SET status=?,resolution=? WHERE id=?',(body.action,body.reason,str(id)))
            db.execute('INSERT INTO reviews VALUES(?,?,?,?,?,?,?)',(str(uuid4()),None,mod['id'],'report:'+body.action,body.reason,None,int(time.time())))
        return {'status':body.action}

    return app

if __name__=='__main__':
    import uvicorn
    # Binding and logging remain private by default. A reviewed HTTPS reverse proxy is separate.
    trusted_proxy=os.environ.get('CRAGLIGHT_TRUST_LOOPBACK_PROXY')=='1'
    uvicorn.run(create_app(),host='127.0.0.1',port=int(os.environ.get('CRAGLIGHT_PORT','8765')),access_log=False,proxy_headers=trusted_proxy,forwarded_allow_ips='127.0.0.1' if trusted_proxy else '')
