"""Single-host account quota shared by all Agent processes on a local volume.

Reserve before network I/O, retain reservations on ambiguous failure, and never
hold a database connection while waiting for the model. This is an estimated
token safety budget, not supplier billing. Use one file/scope per cloud account.
"""
import asyncio
import hashlib
import json
import sqlite3
import time
from contextlib import contextmanager

from fastapi import HTTPException


class AccountQuota:
    def __init__(self, settings):
        self.settings = settings
        self.inflight = 0

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.settings.llm_quota_db_path, timeout=.5, isolation_level=None)
        try:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('PRAGMA synchronous=FULL')
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.execute('COMMIT')
        except BaseException:
            if db.in_transaction:
                db.execute('ROLLBACK')
            raise
        finally:
            db.close()

    def initialize(self):
        if not self.settings.llm_quota_db_path:
            return
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS budgets (scope TEXT PRIMARY KEY, config TEXT NOT NULL, blocked_until REAL NOT NULL DEFAULT 0)')
            db.execute('CREATE TABLE IF NOT EXISTS usage (scope TEXT NOT NULL, second INTEGER NOT NULL, requests INTEGER NOT NULL, tokens INTEGER NOT NULL, PRIMARY KEY(scope,second))')
            cfg = hashlib.sha256(json.dumps([self.settings.llm_quota_rpm, self.settings.llm_quota_tpm,
                                            self.settings.llm_quota_rps]).encode()).hexdigest()
            scope = self.settings.llm_quota_scope
            db.execute('INSERT OR IGNORE INTO budgets(scope,config) VALUES (?,?)', (scope, cfg))
            if db.execute('SELECT config FROM budgets WHERE scope=?', (scope,)).fetchone()[0] != cfg:
                raise RuntimeError('Agent processes must share identical account quotas; drain before changing quota configuration')

    def reserve(self, tokens, *, now=None, write=True):
        now = time.time() if now is None else now
        scope = self.settings.llm_quota_scope
        with self.db() as db:
            row = db.execute('SELECT blocked_until FROM budgets WHERE scope=?', (scope,)).fetchone()
            if row is None:
                raise RuntimeError('account quota not initialized')
            if row[0] > now:
                return max(1, int(row[0] - now) + 1)
            # Keep the boundary bucket: up to 61 seconds, never <60 seconds.
            db.execute('DELETE FROM usage WHERE scope=? AND second < ?', (scope, int(now) - 60))
            requests, used = db.execute('SELECT COALESCE(SUM(requests),0),COALESCE(SUM(tokens),0) FROM usage WHERE scope=?', (scope,)).fetchone()
            recent = db.execute('SELECT COALESCE(SUM(requests),0) FROM usage WHERE scope=? AND second>=?',
                                (scope, int(now) - 1)).fetchone()[0]
            if (requests + 1 > self.settings.llm_quota_rpm or used + tokens > self.settings.llm_quota_tpm
                    or recent + 1 > self.settings.llm_quota_rps):
                return 1
            if write:
                db.execute('INSERT INTO usage VALUES (?,?,1,?) ON CONFLICT(scope,second) DO UPDATE SET requests=requests+1,tokens=tokens+excluded.tokens',
                           (scope, int(now), tokens))
        return 0

    def block(self, seconds):
        with self.db() as db:
            db.execute('UPDATE budgets SET blocked_until=MAX(blocked_until,?) WHERE scope=?',
                       (time.time() + seconds, self.settings.llm_quota_scope))

    async def ready(self):
        if not self.settings.llm_quota_db_path:
            return True
        try:
            return await asyncio.to_thread(self.reserve, 1, write=False) == 0
        except (sqlite3.Error, RuntimeError):
            return False

    async def acquire(self, tokens):
        if self.inflight >= self.settings.llm_max_connections:
            raise HTTPException(429, 'model concurrency budget exhausted', headers={'Retry-After': '1'})
        self.inflight += 1
        try:
            if self.settings.llm_quota_db_path:
                try:
                    delay = await asyncio.to_thread(self.reserve, tokens)
                except sqlite3.Error:
                    raise HTTPException(503, 'account quota unavailable') from None
                if delay:
                    raise HTTPException(429, 'account model budget exhausted', headers={'Retry-After': str(delay)})
        except BaseException:
            self.inflight -= 1
            raise

    def release(self):
        self.inflight -= 1


def estimated_tokens(messages, output_tokens):
    # Conservative UTF-8 byte allowance plus framing. Validate against the
    # selected supplier tokenizer and measured usage before production.
    return sum(len(m['content'].encode('utf-8')) + 32 for m in messages) + output_tokens + 32
