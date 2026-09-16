"""Persist intent before playback; unknown outcomes are queried, never replayed."""
import hashlib
import json
from fastapi import HTTPException


def initialize(db):
    db.execute('''CREATE TABLE IF NOT EXISTS voice_action_commands (
        id TEXT PRIMARY KEY, digest TEXT NOT NULL, result TEXT,
        call_id TEXT NOT NULL DEFAULT '', attempt INTEGER NOT NULL DEFAULT 0)''')
    columns = {row[1] for row in db.execute('PRAGMA table_info(voice_action_commands)')}
    if 'call_id' not in columns:
        db.execute("ALTER TABLE voice_action_commands ADD COLUMN call_id TEXT NOT NULL DEFAULT ''")
        db.execute("ALTER TABLE voice_action_commands ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
    db.execute('CREATE INDEX IF NOT EXISTS voice_commands_call ON voice_action_commands(call_id,attempt)')


def begin(ledger, identity, action, payload):
    encoded = json.dumps([action,payload], sort_keys=True, separators=(',',':'))
    digest = hashlib.sha256(encoded.encode()).hexdigest()
    with ledger.transaction() as db:
        prior = db.execute('SELECT digest,result FROM voice_action_commands WHERE id=?', (identity,)).fetchone()
        if prior:
            if prior['digest'] != digest:
                raise HTTPException(409, 'business command identity conflict')
            if prior['result'] is None:
                raise HTTPException(409, 'business command outcome unknown; reconcile before retry',
                    headers={'X-Voice-Outcome': 'unknown'})
            return json.loads(prior['result'])
        db.execute('INSERT INTO voice_action_commands(id,digest,call_id,attempt) VALUES (?,?,?,?)',
            (identity,digest,payload.get('call_id',''),payload.get('expected_attempt',0)))
        return None


def finish(ledger, identity, result):
    with ledger.transaction() as db:
        db.execute('UPDATE voice_action_commands SET result=? WHERE id=?',
            (json.dumps(result, sort_keys=True),identity))
