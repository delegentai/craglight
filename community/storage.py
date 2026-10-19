"""One isolated persistent SQLite database; media and metadata commit atomically."""
import contextlib
import os
import pathlib
import secrets
import sqlite3

SCHEMA='''
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,google_key TEXT UNIQUE NOT NULL,alias TEXT NOT NULL,pending_alias TEXT,avatar_id TEXT,moderator INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);
CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY,nonce_hash TEXT NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS rates(key TEXT PRIMARY KEY,count INTEGER NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS photos(id TEXT PRIMARY KEY,owner_id TEXT REFERENCES users(id) ON DELETE SET NULL,sha256 TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,data BLOB NOT NULL,created_at INTEGER NOT NULL,hidden INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS photos_owner ON photos(owner_id);
CREATE TABLE IF NOT EXISTS profile_requests(user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,photo_id TEXT NOT NULL REFERENCES photos(id) ON DELETE CASCADE);
CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,status TEXT NOT NULL,document TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,reason TEXT);
CREATE INDEX IF NOT EXISTS drafts_owner ON drafts(owner_id);
CREATE TABLE IF NOT EXISTS publications(id TEXT PRIMARY KEY,owner_id TEXT REFERENCES users(id) ON DELETE SET NULL,kind TEXT NOT NULL,crag_key TEXT NOT NULL,document TEXT NOT NULL,published_at INTEGER NOT NULL,hidden INTEGER NOT NULL DEFAULT 0,credit_alias TEXT NOT NULL,superseded INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS publication_photos(publication_id TEXT NOT NULL REFERENCES publications(id) ON DELETE CASCADE,photo_id TEXT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,PRIMARY KEY(publication_id,photo_id));
CREATE TABLE IF NOT EXISTS credits(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,target_key TEXT NOT NULL,publication_id TEXT NOT NULL REFERENCES publications(id) ON DELETE CASCADE,PRIMARY KEY(user_id,target_key));
CREATE TABLE IF NOT EXISTS reviews(id TEXT PRIMARY KEY,draft_id TEXT,reviewer_id TEXT REFERENCES users(id) ON DELETE SET NULL,decision TEXT NOT NULL,reason TEXT NOT NULL,evidence_url TEXT,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,reporter_id TEXT REFERENCES users(id) ON DELETE SET NULL,target_type TEXT NOT NULL,target_id TEXT NOT NULL,reason TEXT NOT NULL,details TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',resolution TEXT,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS reports_status ON reports(status);
'''

class Store:
    def __init__(self,path):
        self.path=pathlib.Path(path);self.path.parent.mkdir(parents=True,exist_ok=True)
        with self.connection() as db: db.executescript(SCHEMA)

    @contextlib.contextmanager
    def connection(self):
        db=sqlite3.connect(self.path,timeout=20)
        db.row_factory=sqlite3.Row;db.execute('PRAGMA foreign_keys=ON');db.execute('PRAGMA busy_timeout=20000')
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback();raise
        finally: db.close()

def load_secret(state):
    path=pathlib.Path(state)/'identity.secret';path.parent.mkdir(parents=True,exist_ok=True)
    try:
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as f:f.write(secrets.token_bytes(32))
    except FileExistsError: pass
    value=path.read_bytes()
    if len(value)!=32:raise RuntimeError('Invalid private service identity secret')
    return value
