"""Fail-closed admission and durable accounting for one FreeSWITCH gateway.

SQLite transactions coordinate requests; an exclusive process lock prevents
accidentally starting multiple media workers on this single-host deployment.
The ledger MUST live on a persistent local volume, never a tmpfs or NFS share.
"""
from __future__ import annotations

import asyncio
import fcntl
import hashlib
import hmac
import json
import logging
import math
import re
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

from fastapi import HTTPException
import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator

logger = logging.getLogger(__name__)


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


class RoutePolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    gateway: str = Field(pattern=r"^[A-Za-z0-9_.-]+$")
    caller_id: str = Field(pattern=r"^\+?[0-9]{6,15}$")
    allowed_prefixes: list[str] = Field(min_length=1)
    denied_prefixes: list[str] = Field(default_factory=list)
    max_concurrent: int = Field(ge=1, le=10000)
    cps: int = Field(ge=1, le=1000)
    calls_per_day: int = Field(ge=1, le=10000000)
    hour_budget_minor: int = Field(ge=1)
    day_budget_minor: int = Field(ge=1)
    rate_minor_per_minute: int = Field(ge=1)
    # Upper bound including BOTH legs, billing increments and surcharges.
    billing_multiplier: int = Field(default=2, ge=2, le=100)
    max_duration_sec: int = Field(ge=1, le=3600)

    @field_validator("allowed_prefixes", "denied_prefixes")
    @classmethod
    def prefixes(cls, values):
        if any(not re.fullmatch(r"[1-9][0-9]{0,14}", v) for v in values):
            raise ValueError("prefixes must be canonical country-code-prefixed digits")
        return values


def routes(settings) -> dict[str, RoutePolicy]:
    raw = Path(settings.voice_security_routes_file).read_text(encoding="utf-8") if settings.voice_security_routes_file else settings.voice_security_routes_json
    value = json.loads(raw)
    if not isinstance(value, dict) or any(not re.fullmatch(r"[1-9][0-9]*:[0-9]+", k) for k in value):
        raise ValueError("VOICE_SECURITY_ROUTES_JSON keys must be tenant_id:line_id (0 for default line)")
    return {k: RoutePolicy.model_validate(v) for k, v in value.items()}


CALLBACK_PATHS = {f"/api/v1/webhooks/telephony/{kind}" for kind in ("status", "speech", "media", "recording", "transcript")}


def validate_callback_url(settings, url: str) -> None:
    # Exact configured origin AND path. No caller-controlled DNS, credentials,
    # query, fragments or redirects. Internal HTTP is an explicit ops decision.
    base = settings.voice_callback_base_url.rstrip("/")
    allowed = {base + path for path in CALLBACK_PATHS}
    if not base or url not in allowed:
        raise HTTPException(403, "callback target is not registered")


def validate_security_settings(settings) -> None:
    if settings.voice_gateway_driver == "mock":
        return
    if settings.voice_gateway_driver != "freeswitch_esl":
        raise RuntimeError("real PBX HTTP forwarding is disabled until its hard-stop and reconciliation contract is verified")
    names = ("service_token", "voice_command_secret", "voice_security_admin_token", "webhook_token", "webhook_secret", "freeswitch_esl_password")
    values = [getattr(settings, name).strip() for name in names]
    if any(len(v) < 32 or any(p in v.lower() for p in ("change-me", "replace-me", "your-")) for v in values):
        raise RuntimeError("real dialing requires independent 32-character service, command, security-admin, callback and ESL credentials")
    if len(set(values)) != len(values):
        raise RuntimeError("voice security credentials must be distinct")
    if not settings.voice_security_db_path or settings.voice_security_db_path == ":memory:":
        raise RuntimeError("VOICE_SECURITY_DB_PATH must be a persistent local database")
    parsed = urlsplit(settings.voice_callback_base_url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.path not in {"", "/"}
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise RuntimeError("VOICE_CALLBACK_BASE_URL must be one trusted origin")
    if parsed.scheme != "https" and not settings.voice_callback_allow_private_http:
        raise RuntimeError("internal callback HTTP requires explicit VOICE_CALLBACK_ALLOW_PRIVATE_HTTP=true")
    configured_routes = routes(settings)  # Empty map deliberately permits NO calls.
    if configured_routes and settings.freeswitch_dialplan_context != "agent-restricted":
        raise RuntimeError("real dialing requires FREESWITCH_DIALPLAN_CONTEXT=agent-restricted")
    if not 1 <= settings.voice_callback_failure_stop_sec <= 60:
        raise RuntimeError("VOICE_CALLBACK_FAILURE_STOP_SEC must be between 1 and 60")
    for name in ("voice_max_concurrent", "voice_cps", "voice_daily_call_limit", "voice_hour_budget_minor", "voice_day_budget_minor", "voice_max_duration_sec"):
        if getattr(settings, name) < 1:
            raise RuntimeError(f"{name} must be positive")
    if settings.voice_max_duration_sec > 3600:
        raise RuntimeError("VOICE_MAX_DURATION_SEC cannot exceed 3600")


class Ledger:
    def __init__(self, path: str):
        self.path = path
        self.initialized = False

    @contextmanager
    def transaction(self):
        if not self.path:
            raise HTTPException(503, "voice security ledger is not configured")
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            if not self.initialized:
                db.executescript("""
                  CREATE TABLE IF NOT EXISTS attempts (
                    call_id TEXT NOT NULL, attempt INTEGER NOT NULL, tenant INTEGER NOT NULL,
                    line TEXT NOT NULL, uuid TEXT NOT NULL UNIQUE, digest TEXT NOT NULL,
                    payload TEXT NOT NULL, created REAL NOT NULL, deadline REAL NOT NULL,
                    cost INTEGER NOT NULL, rate INTEGER NOT NULL, multiplier INTEGER NOT NULL,
                    state TEXT NOT NULL DEFAULT 'pending', result TEXT, ended REAL,
                    observed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(call_id, attempt));
                  CREATE INDEX IF NOT EXISTS attempts_scope ON attempts(tenant,line,created);
                  CREATE TABLE IF NOT EXISTS flags (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                  CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, expires REAL NOT NULL);
                  CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT PRIMARY KEY, url TEXT NOT NULL, body BLOB NOT NULL,
                    created REAL NOT NULL, due REAL NOT NULL, failures INTEGER NOT NULL DEFAULT 0);
                  CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY, at REAL NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL);
                """)
                self.initialized = True
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.execute("COMMIT")
        except BaseException:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def verify_command(self, secret: str, path: str, body: bytes, headers) -> None:
        stamp, nonce, signature = (headers.get(k, "") for k in ("x-voice-timestamp", "x-voice-nonce", "x-voice-signature"))
        now = time.time()
        if not stamp.isdigit() or abs(now - int(stamp)) > 60 or not re.fullmatch(r"[a-zA-Z0-9_-]{20,100}", nonce):
            raise HTTPException(401, "missing or expired voice command permit")
        expected = hmac.new(secret.encode(), f"{stamp}.{nonce}.{path}.".encode() + body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            raise HTTPException(401, "invalid voice command permit")
        with self.transaction() as db:
            db.execute("DELETE FROM nonces WHERE expires < ?", (now,))
            try:
                db.execute("INSERT INTO nonces VALUES (?, ?)", (nonce, now + 125))
            except sqlite3.IntegrityError:
                raise HTTPException(409, "voice command permit was already used") from None

    def set_stopped(self, stopped: bool, reason: str) -> None:
        with self.transaction() as db:
            db.execute("INSERT OR REPLACE INTO flags VALUES ('stopped', ?)", ("1" if stopped else "0",))
            db.execute("INSERT INTO audit(at,action,detail) VALUES (?,?,?)", (time.time(), "stop" if stopped else "resume", reason[:300]))

    def summary(self) -> dict:
        with self.transaction() as db:
            flag = db.execute("SELECT value FROM flags WHERE key='stopped'").fetchone()
            pending = db.execute("SELECT COUNT(*), MIN(created) FROM outbox").fetchone()
            return {"stopped": bool(flag and flag[0] == "1"), "pending_callbacks": pending[0],
                    "oldest_callback_age_sec": max(0, time.time() - pending[1]) if pending[1] else 0,
                    "active_attempts": db.execute("SELECT COUNT(*) FROM attempts WHERE state != 'ended'").fetchone()[0],
                    "rejected_commands": db.execute("SELECT COUNT(*) FROM audit WHERE action='reject'").fetchone()[0]}

    def rejected(self, reason: str):
        with self.transaction() as db:
            db.execute("INSERT INTO audit(at,action,detail) VALUES (?, 'reject', ?)", (time.time(), reason[:200]))

    def admit(self, payload: dict, route: RoutePolicy, settings) -> tuple[dict, bool]:
        metadata = payload["metadata"]
        tenant, attempt = int(metadata["tenant_id"]), int(metadata["attempt"])
        line = str(metadata.get("telephony_line_id") or 0)
        digest = hashlib.sha256(canonical(payload)).hexdigest()
        now = time.time()
        duration = min(route.max_duration_sec, settings.voice_max_duration_sec)
        cost = math.ceil(duration / 60) * route.rate_minor_per_minute * route.billing_multiplier
        with self.transaction() as db:
            prior = db.execute("SELECT * FROM attempts WHERE call_id=? AND attempt=?", (payload["call_id"], attempt)).fetchone()
            if prior:
                if prior["tenant"] != tenant or prior["digest"] != digest:
                    raise HTTPException(409, "idempotency key has different payload")
                return dict(prior), False
            if db.execute("SELECT 1 FROM attempts WHERE call_id=? AND (state != 'ended' OR tenant != ?)", (payload["call_id"], tenant)).fetchone():
                raise HTTPException(409, "previous call attempt is still active or belongs to another tenant")
            flag = db.execute("SELECT value FROM flags WHERE key='stopped'").fetchone()
            if flag and flag[0] == "1":
                raise HTTPException(503, "emergency stop is active")
            if db.execute("SELECT 1 FROM outbox WHERE created < ? LIMIT 1", (now - settings.voice_callback_failure_stop_sec,)).fetchone():
                raise HTTPException(503, "callback delivery unhealthy; new calls stopped")
            day, hour = int(now // 86400) * 86400, int(now // 3600) * 3600
            scopes = [("1=1", (), settings.voice_max_concurrent, settings.voice_cps, settings.voice_daily_call_limit,
                       settings.voice_hour_budget_minor, settings.voice_day_budget_minor),
                      ("tenant=?", (tenant,), settings.voice_max_concurrent, settings.voice_cps, settings.voice_daily_call_limit,
                       settings.voice_hour_budget_minor, settings.voice_day_budget_minor),
                      ("tenant=? AND line=?", (tenant, line), route.max_concurrent, route.cps, route.calls_per_day,
                       route.hour_budget_minor, route.day_budget_minor)]
            for where, args, concurrency, cps, daily, hourly_budget, daily_budget in scopes:
                count = lambda condition, params=(): db.execute(f"SELECT COUNT(*) FROM attempts WHERE {where} AND ({condition})", (*args, *params)).fetchone()[0]
                if count("state != 'ended'") >= concurrency or count("created >= ?", (now - 1,)) >= cps or count("created >= ?", (day,)) >= daily:
                    raise HTTPException(429, "voice call/concurrency/CPS hard limit reached")
                for start, budget in ((hour, hourly_budget), (day, daily_budget)):
                    spent = db.execute(f"SELECT COALESCE(SUM(cost),0) FROM attempts WHERE {where} AND (created >= ? OR ended >= ? OR state != 'ended')", (*args, start, start)).fetchone()[0]
                    if spent + cost > budget:
                        raise HTTPException(429, "voice budget hard limit reached")
            provider_id = str(uuid4())
            # Persist the intent BEFORE writing anything to ESL. A crash may
            # conservatively lose availability, but never authorizes a redial.
            db.execute("INSERT INTO attempts(call_id,attempt,tenant,line,uuid,digest,payload,created,deadline,cost,rate,multiplier) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                       (payload["call_id"], attempt, tenant, line, provider_id, digest, canonical(payload).decode(), now,
                        now + max(5, settings.freeswitch_originate_timeout_sec) + duration + 10, cost,
                        route.rate_minor_per_minute, route.billing_multiplier))
            return dict(db.execute("SELECT * FROM attempts WHERE uuid=?", (provider_id,)).fetchone()), True

    def result(self, uuid: str, result: dict) -> None:
        with self.transaction() as db:
            db.execute("UPDATE attempts SET result=?, state=CASE WHEN state='ended' THEN state ELSE 'active' END WHERE uuid=?", (canonical(result).decode(), uuid))

    def lookup(self, call_id: str, tenant: int) -> dict | None:
        with self.transaction() as db:
            row = db.execute("SELECT * FROM attempts WHERE call_id=? AND tenant=? ORDER BY attempt DESC LIMIT 1", (call_id, tenant)).fetchone()
            return dict(row) if row else None

    def known_uuid(self, uuid: str) -> bool:
        with self.transaction() as db:
            return db.execute("SELECT 1 FROM attempts WHERE uuid=? AND state != 'ended'", (uuid,)).fetchone() is not None

    def mark_seen(self, uuid: str):
        with self.transaction() as db:
            db.execute("UPDATE attempts SET observed=1 WHERE uuid=?", (uuid,))

    def finish(self, uuid: str, billsec: int | None = None) -> None:
        with self.transaction() as db:
            row = db.execute("SELECT * FROM attempts WHERE uuid=?", (uuid,)).fetchone()
            if row and row["state"] != "ended":
                # Missing CDR: retain FULL reserved charge, never assume free.
                charge = row["cost"] if billsec is None else max(1, math.ceil(billsec / 60)) * row["rate"] * row["multiplier"]
                db.execute("UPDATE attempts SET state='ended',cost=?,ended=? WHERE uuid=?", (charge, time.time(), uuid))


class CallbackSender:
    def __init__(self, settings, ledger: Ledger | None = None):
        self.settings = settings
        self.ledger = ledger or (Ledger(settings.voice_security_db_path) if settings.voice_security_db_path else None)
        self.task = None

    async def start(self):
        if self.ledger and self.task is None:
            self.task = asyncio.create_task(self._run(), name="voice-callback-outbox")

    async def stop(self):
        if self.task:
            self.task.cancel()
            try:
                await self.task
            except asyncio.CancelledError:
                pass
            self.task = None

    async def post(self, url: str, payload: dict):
        validate_callback_url(self.settings, url)
        body = canonical(payload)
        if not self.ledger:
            await self._send(url, body)
            return
        # Content id makes repeated producer delivery idempotent. Body/ID stay
        # stable on retries; signature timestamp is freshly generated.
        key = hashlib.sha256(url.encode() + b"\0" + body).hexdigest()
        with self.ledger.transaction() as db:
            db.execute("INSERT OR IGNORE INTO outbox(id,url,body,created,due) VALUES (?,?,?,?,?)", (key, url, body, time.time(), time.time()))

    async def _send(self, url, body):
        validate_callback_url(self.settings, url)
        stamp = str(int(time.time()))
        headers = {"Content-Type": "application/json", "x-webhook-token": self.settings.webhook_token}
        if self.settings.webhook_secret:
            headers.update({"x-webhook-timestamp": stamp, "x-webhook-signature": hmac.new(self.settings.webhook_secret.encode(), stamp.encode() + b"." + body, hashlib.sha256).hexdigest()})
        async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec, follow_redirects=False, trust_env=False) as client:
            response = await client.post(url, content=body, headers=headers)
            response.raise_for_status()

    async def flush(self):
        with self.ledger.transaction() as db:
            rows = db.execute("SELECT * FROM outbox WHERE due <= ? ORDER BY created LIMIT 50", (time.time(),)).fetchall()
        for row in rows:
            try:
                await self._send(row["url"], row["body"])
            except (httpx.HTTPError, HTTPException):
                # Do not log URLs, secrets, transcripts or response bodies.
                logger.warning("voice callback delivery failed id=%s", row["id"])
                with self.ledger.transaction() as db:
                    db.execute("UPDATE outbox SET failures=failures+1,due=? WHERE id=?", (time.time() + min(60, 2 ** min(row["failures"], 6)), row["id"]))
            else:
                with self.ledger.transaction() as db:
                    db.execute("DELETE FROM outbox WHERE id=?", (row["id"],))

    async def _run(self):
        while True:
            try:
                await self.flush()
            except Exception:
                logger.error("voice callback ledger unavailable")
            await asyncio.sleep(0.5)


class SecureDriver:
    def __init__(self, settings, driver):
        self.settings, self.driver = settings, driver
        self.ledger = Ledger(settings.voice_security_db_path)
        self.sender = CallbackSender(settings, self.ledger)
        driver.security_ledger = self.ledger
        driver._post_json = self.sender.post
        if driver.pipecat_manager:
            driver.pipecat_manager._post_json = self.sender.post
        self.lock_file = None
        self.reconcile_task = None

    def __getattr__(self, name):
        return getattr(self.driver, name)

    async def start(self):
        validate_security_settings(self.settings)
        self.lock_file = open(self.settings.voice_security_db_path + ".lock", "a")
        try:
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.ledger.summary()
            await self.driver.start()
            await self.sender.start()
            self.reconcile_task = asyncio.create_task(self._reconcile(), name="voice-pbx-reconciliation")
        except BaseException:
            self.lock_file.close()
            self.lock_file = None
            raise

    async def stop(self):
        if self.reconcile_task:
            self.reconcile_task.cancel()
            try:
                await self.reconcile_task
            except asyncio.CancelledError:
                pass
        try:
            await self.driver.stop()
            await self.sender.stop()
        finally:
            if self.lock_file:
                self.lock_file.close()
                self.lock_file = None

    async def ready(self):
        try:
            state = self.ledger.summary()
            return bool(routes(self.settings)) and not state["stopped"] and state["oldest_callback_age_sec"] < self.settings.voice_callback_failure_stop_sec and await self.driver.ready()
        except Exception:
            return False

    def policy(self, payload):
        metadata = payload.get("metadata") or {}
        tenant, attempt = metadata.get("tenant_id"), metadata.get("attempt")
        if type(tenant) is not int or tenant < 1 or type(attempt) is not int or attempt < 1:
            raise HTTPException(403, "dial requires tenant and attempt identity")
        route = routes(self.settings).get(f"{tenant}:{metadata.get('telephony_line_id') or 0}")
        if not route:
            raise HTTPException(403, "tenant line is not authorized")
        phone = payload["phone"]
        if not re.fullmatch(r"\+?[1-9][0-9]{5,14}", phone):
            raise HTTPException(403, "destination must include country code and contain 6-15 ASCII digits")
        digits = phone.lstrip("+")
        if not any(digits.startswith(p) for p in route.allowed_prefixes) or any(digits.startswith(p) for p in route.denied_prefixes):
            raise HTTPException(403, "destination is not authorized")
        for key, expected in (("freeswitch_gateway", route.gateway), ("caller_id", route.caller_id)):
            if metadata.get(key) and metadata[key] != expected:
                raise HTTPException(403, "route/caller ID override is forbidden")
        for key in ("webhook_url",):
            validate_callback_url(self.settings, str(payload[key]))
        for key, value in metadata.items():
            if key.endswith("webhook_url") and value:
                validate_callback_url(self.settings, str(value))
        return route

    async def post(self, action, payload):
        try:
            return await self._post(action, payload)
        except HTTPException as exc:
            self.ledger.rejected(str(exc.detail))
            raise

    async def _post(self, action, payload):
        if action == "dial":
            route = self.policy(payload)
            row, fresh = self.ledger.admit(payload, route, self.settings)
            if not fresh:
                return json.loads(row["result"]) if row["result"] else {"result": "pending_reconciliation", "provider_call_id": row["uuid"]}
            outgoing = json.loads(row["payload"])
            outgoing["_provider_call_id"] = row["uuid"]
            outgoing["_max_duration_sec"] = min(route.max_duration_sec, self.settings.voice_max_duration_sec)
            outgoing["metadata"].update(freeswitch_gateway=route.gateway, caller_id=route.caller_id)
            result = await self.driver.post(action, outgoing)
            self.ledger.result(row["uuid"], result)
            return result
        tenant = payload.get("tenant_id")
        if type(tenant) is not int or tenant < 1:
            raise HTTPException(403, "call control requires tenant identity")
        row = self.ledger.lookup(payload["call_id"], tenant)
        if row is None:
            raise HTTPException(404, "call not found in tenant")
        if action in {"hangup", "status"}:
            if row["state"] == "ended":
                return {"result": "hungup", "ended": True, "provider_call_id": row["uuid"]}
            if action == "hangup":
                try:
                    await self.driver.client.api(f"uuid_kill {row['uuid']} NORMAL_CLEARING")
                except Exception:
                    # Query may still confirm that an earlier hangup succeeded.
                    pass
            exists = (await self.driver.client.api(f"uuid_exists {row['uuid']}")).strip().lower()
            if exists == "true":
                self.ledger.mark_seen(row["uuid"])
            # A negative lookup during asynchronous originate is NOT proof of
            # termination: keep the reservation until the full safety deadline.
            # An originate job may still be queued inside PBX. Absence alone
            # is NEVER proof that an unobserved job cannot start later.
            ended = exists == "false" and bool(row["observed"]) and time.time() >= row["deadline"]
            if ended:
                self.ledger.finish(row["uuid"])
                binding = self.driver.calls_by_uuid.get(row["uuid"])
                if binding is not None:
                    await self.driver._stop_ai_media(binding)
                    self.driver.calls_by_uuid.pop(row["uuid"], None)
                    if self.driver.calls_by_id.get(binding.call_id) is binding:
                        self.driver.calls_by_id.pop(binding.call_id, None)
            return {"result": "hungup" if ended else "pending", "ended": ended, "provider_call_id": row["uuid"]}
        if row["state"] == "ended":
            raise HTTPException(409, "call has ended")
        if action == "transfer" and not re.fullmatch(r"agent:[1-9][0-9]*", payload.get("target_group") or ""):
            raise HTTPException(403, "transfer requires an authorized agent ID")
        # After restart control is kept fail-closed unless a genuine PBX event
        # has reconstructed the binding. Hangup/status never need that binding.
        return await self.driver.post(action, payload)

    async def _reconcile(self):
        while True:
            try:
                with self.ledger.transaction() as db:
                    rows = db.execute("SELECT call_id,tenant FROM attempts WHERE state != 'ended' AND deadline <= ?", (time.time(),)).fetchall()
                for row in rows:
                    await self.post("hangup", {"call_id": row["call_id"], "tenant_id": row["tenant"]})
            except Exception:
                logger.error("voice PBX reconciliation unavailable; reservations retained")
            await asyncio.sleep(2)
