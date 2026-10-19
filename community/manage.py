"""Explicit local operator commands; requires access to the private service database."""
import argparse
import json
import os
import pathlib
import sqlite3
import time
from uuid import UUID
from storage import Store

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--state',default=os.environ.get('CRAGLIGHT_COMMUNITY_STATE',str(pathlib.Path(__file__).parent/'.state')))
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('status')
    grant=sub.add_parser('moderator');grant.add_argument('user_id',type=UUID);grant.add_argument('--revoke',action='store_true')
    sub.add_parser('prune')
    backup=sub.add_parser('backup');backup.add_argument('destination',type=pathlib.Path)
    args=parser.parse_args();store=Store(pathlib.Path(args.state)/'community.sqlite');now=int(time.time())
    with store.connection() as db:
        if args.command=='status':
            print(json.dumps({table:db.execute(f'SELECT count(*) FROM {table}').fetchone()[0] for table in ['users','drafts','photos','publications','reports']},indent=2))
        elif args.command=='moderator':
            if db.execute('UPDATE users SET moderator=? WHERE id=?',(0 if args.revoke else 1,str(args.user_id))).rowcount!=1:raise SystemExit('User must sign in first; no account created by this command.')
            print('Moderator access revoked.' if args.revoke else 'Moderator access granted to existing CragLight account.')
        elif args.command=='prune':
            db.execute('DELETE FROM sessions WHERE expires<?',(now,));db.execute('DELETE FROM challenges WHERE expires<?',(now,));db.execute('DELETE FROM rates WHERE expires<?',(now,))
            db.execute("DELETE FROM drafts WHERE status IN ('draft','rejected','changes_requested') AND updated_at<?",(now-180*86400,))
            # Pending review stays until reviewed; aging queue must be actively handled by operator.
            db.execute("DELETE FROM photos WHERE created_at<? AND id NOT IN (SELECT photo_id FROM publication_photos) AND id NOT IN (SELECT avatar_id FROM users WHERE avatar_id IS NOT NULL) AND id NOT IN (SELECT photo_id FROM profile_requests) AND id NOT IN (SELECT json_extract(p.value,'$.id') FROM drafts d,json_each(d.document,'$.photos') p)",(now-30*86400,))
            db.execute("DELETE FROM reports WHERE status!='pending' AND created_at<?",(now-90*86400,))
            db.execute('DELETE FROM reviews WHERE created_at<?',(now-365*86400,))
            print('Expired sessions/challenges/rate buckets, old inactive drafts, orphan photos and resolved audit records pruned.')
        elif args.command=='backup':
            destination=args.destination.resolve()
            if destination.exists():raise SystemExit('Backup destination already exists; refusing overwrite.')
            if destination==store.path.resolve():raise SystemExit('Backup destination must differ from live database.')
            destination.parent.mkdir(parents=True,exist_ok=True)
            with sqlite3.connect(destination) as target:db.backup(target)
            print('Consistent SQLite backup created. Protect it and the separate identity.secret; remove backups after 30 days.')

if __name__=='__main__':main()
