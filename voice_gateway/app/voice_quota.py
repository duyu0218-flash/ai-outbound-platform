"""Durable owner-node voice permits. Unknown releases never expire by TTL."""
import json
import time
from fastapi import HTTPException


def initialize(db):
    db.execute('''CREATE TABLE IF NOT EXISTS voice_permits (
        id TEXT PRIMARY KEY, scope TEXT NOT NULL, kind TEXT NOT NULL,
        call_id TEXT NOT NULL, attempt INTEGER NOT NULL, owner TEXT NOT NULL,
        state TEXT NOT NULL, created REAL NOT NULL)''')
    db.execute('CREATE INDEX IF NOT EXISTS voice_permits_scope ON voice_permits(scope,state)')
    db.execute('CREATE INDEX IF NOT EXISTS voice_permits_retention ON voice_permits(state,created)')


def budget(settings, kind):
    try:
        data = json.loads(settings.voice_quota_budgets_json)[kind]
        scope = '/'.join(str(data[k]).strip() for k in ('provider', 'account', 'region', 'product'))
        if any(not str(data[k]).strip() or '/' in str(data[k]) for k in ('provider', 'account', 'region', 'product')):
            raise ValueError()
        limit, rpm = data['concurrency'], data.get('requests_per_minute', 0)
        if type(limit) is not int or limit < 1 or type(rpm) is not int or (kind == 'tts' and rpm < 1):
            raise ValueError()
        return scope, limit, rpm
    except (KeyError, ValueError, TypeError):
        raise HTTPException(503, 'approved provider/account/region/product voice budget missing') from None


def usage(db, scope, kind):
    if kind == 'asr':
        # First physical stream consumes its call's logical ringing reservation;
        # every overlapping reconnect consumes another permit.
        return db.execute('''SELECT COALESCE(SUM(MAX(reserved,streams)),0) FROM (
            SELECT call_id,attempt,MAX(CASE WHEN kind='reserve' THEN 1 ELSE 0 END) reserved,
                SUM(CASE WHEN kind='asr' THEN 1 ELSE 0 END) streams
            FROM voice_permits WHERE scope=? AND state!='closed' AND kind IN ('reserve','asr')
            GROUP BY call_id,attempt)''', (scope,)).fetchone()[0]
    return db.execute("SELECT count(*) FROM voice_permits WHERE scope=? AND kind='tts' AND state!='closed'", (scope,)).fetchone()[0]


def reserve(db, settings, call_id, attempt):
    initialize(db)
    scope, limit, _ = budget(settings, 'asr')
    require_scope(db, scope, 'asr')
    budget(settings, 'tts')  # No AI originate with an unapproved synthesis account.
    if usage(db, scope, 'asr') >= limit:
        raise HTTPException(429, 'ASR reservation capacity exhausted',
            headers={'X-Voice-Dial-Admitted': 'false', 'Retry-After': '1'})
    db.execute('INSERT INTO voice_permits VALUES (?,?,?,?,?,?,?,?)',
        (f'reserve:{call_id}:{attempt}', scope, 'reserve', call_id, attempt, 'controller', 'active', time.time()))


def acquire(db, settings, permit_id, kind, call_id, attempt, owner):
    initialize(db)
    scope, limit, rpm = budget(settings, kind)
    require_scope(db, scope, kind)
    prior = db.execute('SELECT * FROM voice_permits WHERE id=?', (permit_id,)).fetchone()
    if prior:
        if tuple(prior[k] for k in ('scope','kind','call_id','attempt','owner')) != (scope,kind,call_id,attempt,owner) or prior['state'] != 'active':
            raise HTTPException(409, 'voice permit identity or state conflict')
        return
    reserved = db.execute("SELECT scope FROM voice_permits WHERE call_id=? AND attempt=? AND kind='reserve' AND state='active'", (call_id,attempt)).fetchone()
    if not reserved or kind == 'asr' and reserved['scope'] != scope:
        raise HTTPException(409, 'call has no active voice reservation')
    extra = 1
    if kind == 'asr':
        first = not db.execute("SELECT 1 FROM voice_permits WHERE scope=? AND call_id=? AND attempt=? AND kind='asr' AND state!='closed'", (scope,call_id,attempt)).fetchone()
        extra = 0 if first else 1
    if usage(db, scope, kind) + extra > limit:
        raise HTTPException(429, 'voice connection/request concurrency exhausted')
    now = time.time()
    # Closed permits retain the full rolling RPM window plus an hour of replay
    # identity. Active/unknown usage is never reclaimed by age.
    db.execute("DELETE FROM voice_permits WHERE id IN (SELECT id FROM voice_permits WHERE state='closed' AND created<? LIMIT 100)", (now-3600,))
    if kind == 'tts' and db.execute("SELECT count(*) FROM voice_permits WHERE scope=? AND kind='tts' AND created>?", (scope,now-60)).fetchone()[0] >= rpm:
        raise HTTPException(429, 'TTS rolling request budget exhausted')
    db.execute('INSERT INTO voice_permits VALUES (?,?,?,?,?,?,?,?)',
        (permit_id,scope,kind,call_id,attempt,owner,'active',now))


def release(db, permit_id, owner, confirmed):
    row = db.execute('SELECT owner,state FROM voice_permits WHERE id=?', (permit_id,)).fetchone()
    if row is None or row['owner'] != owner:
        raise HTTPException(409, 'voice permit owner mismatch')
    if row['state'] != 'closed':
        db.execute('UPDATE voice_permits SET state=? WHERE id=?', ('closed' if confirmed else 'unknown',permit_id))


def call_ended(db, call_id, attempt):
    initialize(db)
    db.execute("UPDATE voice_permits SET state='closed' WHERE call_id=? AND attempt=? AND kind='reserve'", (call_id,attempt))
    # Physical streams and requests need their own provider close evidence.


def require_scope(db, scope, kind):
    kinds = ('asr', 'reserve') if kind == 'asr' else ('tts', 'tts')
    if db.execute("SELECT 1 FROM voice_permits WHERE kind IN (?,?) AND state!='closed' AND scope!=? LIMIT 1", (*kinds,scope)).fetchone():
        raise HTTPException(503, 'drain or reconcile outstanding permits before changing voice account scope')


def snapshot(db, settings):
    result = {}
    for kind in ('asr', 'tts'):
        scope, limit, rpm = budget(settings, kind)
        result[kind] = dict(scope=scope, concurrency=limit, requests_per_minute=rpm,
            used=usage(db,scope,kind), unknown=db.execute(
                "SELECT count(*) FROM voice_permits WHERE scope=? AND kind=? AND state='unknown'",(scope,kind)).fetchone()[0])
    return result


def validate_start(ledger, settings):
    with ledger.read() as db:
        if not settings.voice_quota_enabled:
            if db.execute("SELECT 1 FROM voice_permits WHERE state!='closed' LIMIT 1").fetchone():
                raise RuntimeError('drain or reconcile voice permits before disabling shared quota')
            return
        for kind in ('asr','tts'):
            scope,_,_ = budget(settings,kind)
            require_scope(db,scope,kind)
