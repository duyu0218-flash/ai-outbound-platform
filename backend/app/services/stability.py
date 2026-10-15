"""Shared, fail-closed new-dial control. Existing calls never enter this gate.

The caller holds the existing platform admission transaction lock. Redis stores
observations and the controller state; durable call/voice quotas remain authoritative.
"""
from functools import lru_cache
import json
import logging
import math
import time
import redis
from ..config import get_settings

logger = logging.getLogger(__name__)


@lru_cache(maxsize=4)
def client(url):
    return redis.from_url(url, decode_responses=True, socket_timeout=.2,
                          socket_connect_timeout=.2, max_connections=4)


def advance(previous, *, now, queue_ms, age_sec, ready):
    state = dict(previous or dict(mode='PAUSED', fraction=0., changed_at=now, version=0))
    if (state.get('mode') not in {'NORMAL', 'THROTTLED', 'PAUSED', 'RECOVERING'}
            or not isinstance(state.get('fraction'), (int, float))
            or not math.isfinite(state['fraction']) or not 0 <= state['fraction'] <= 1
            or not isinstance(state.get('changed_at'), (int, float))
            or not math.isfinite(state['changed_at']) or state['changed_at'] > now):
        state = dict(mode='PAUSED', fraction=0., changed_at=now, version=0)
    state['updated_at'] = now
    hard = not ready or age_sec >= .6
    soft = age_sec >= .3 or queue_ms >= 100
    healthy = ready and age_sec < .15 and queue_ms < 50
    if hard:
        state.update(mode='PAUSED', fraction=0., healthy_since=None, soft_since=None,
                     reason='dependency_or_callback_pressure', changed_at=now)
    elif soft:
        state['healthy_since'] = None
        if state.get('soft_since') is None:
            state['soft_since'] = now
        hold = 2 if age_sec >= .3 else 10
        if now-state['soft_since'] >= hold and now-state['changed_at'] >= 10 and state['fraction'] > 0:
            fraction = state['fraction']*.8
            state.update(mode='THROTTLED' if fraction >= .1 else 'PAUSED',
                         fraction=fraction if fraction >= .1 else 0., changed_at=now,
                         reason='queue_pressure')
    elif healthy:
        state['soft_since'] = None
        if state.get('healthy_since') is None:
            state['healthy_since'] = now
        if state['mode'] != 'NORMAL' and now-state['healthy_since'] >= 60 and now-state['changed_at'] >= 60:
            fraction = min(1., state['fraction']+.1) if state['fraction'] else .5
            state.update(mode='NORMAL' if fraction >= 1 else 'RECOVERING', fraction=fraction,
                         reason='healthy', changed_at=now)
    else:
        state.update(healthy_since=None, soft_since=None)
    state['version'] = int(state.get('version', 0))+1
    return state


def publish(kind, identity, values):
    settings = get_settings()
    if not settings.stability_admission_enabled:
        return
    try:
        r = client(settings.redis_url)
        name = settings.stability_redis_key+':signals'
        # Timestamped members are pruned by the serialized controller, not immortal keys.
        r.hset(name, kind+':'+identity, json.dumps(dict(at=time.time(), **values)))
        r.expire(name, 60)
    except (redis.RedisError, OSError):
        logger.warning('stability telemetry unavailable; new dial admission will fail closed')


def check(session, inbox):
    settings = get_settings()
    if not settings.stability_admission_enabled:
        return True
    from .gateway_cluster import node_specs
    now = time.time()
    try:
        r = client(settings.redis_url)
        prefix = settings.stability_redis_key
        rows = r.hgetall(prefix+':signals')
        signals = {}
        stale = []
        for name, raw in rows.items():
            value = json.loads(raw)
            if not 0 <= now-float(value['at']) <= settings.stability_signal_ttl_sec:
                stale.append(name)
            else:
                signals[name] = value
        if stale:
            r.hdel(prefix+':signals', *stale)
        ai = [v for k,v in signals.items() if k.startswith('ai:')]
        specs = [n for n in node_specs() if n.enabled]
        gateways = [signals.get('gateway:'+n.id, {}) for n in specs]
        ready = bool(inbox['ready'] and len(ai) >= settings.stability_min_ai_workers and specs
                     and all(n.cps > 0 for n in specs) and all(v.get('ready') for v in gateways))
        age = max([float(inbox['oldest_age_sec'])]+[float(v.get('age_sec', 1)) for v in gateways])
        queue = max([0.]+[max(float(v['queue_p95_ms']), float(v['oldest_wait_ms'])) for v in ai])
        if not math.isfinite(age) or not math.isfinite(queue):
            raise ValueError('non-finite pressure observation')
        raw = r.get(prefix+':control')
        state = advance(json.loads(raw) if raw else None, now=now, queue_ms=queue, age_sec=age, ready=ready)
        r.set(prefix+':control', json.dumps(state), ex=120)
        session.info['stability_fraction'] = state['fraction']
        return state['fraction'] > 0
    except (redis.RedisError, OSError, ValueError, KeyError, TypeError):
        session.info['stability_fraction'] = 0.
        logger.warning('stability admission paused: unavailable or invalid shared state')
        return False


def status():
    settings = get_settings()
    if not settings.stability_admission_enabled:
        return dict(mode='DISABLED', fraction=1., updated_at=0)
    try:
        state = json.loads(client(settings.redis_url).get(settings.stability_redis_key+':control') or '{}')
        if 0 <= time.time()-state.get('updated_at', 0) <= settings.stability_signal_ttl_sec:
            return state
    except (redis.RedisError, OSError, ValueError, TypeError):
        pass
    return dict(mode='PAUSED', fraction=0., updated_at=0)
