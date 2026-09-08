from __future__ import annotations

import re
import heapq
import threading
from collections import OrderedDict
from types import SimpleNamespace
from sqlalchemy import func
from sqlmodel import Session, select

from ..models import KnowledgeItem
from ..clock import utc_now


_cache = OrderedDict()
_cache_lock = threading.RLock()
_MAX_CACHE_BYTES = 32 * 1024 * 1024


def _knowledge_snapshot(session, tenant_id):
    # The revision is read on every request, so edits, activation and deletion
    # invalidate all API/worker processes without a TTL window or Redis failure.
    revision = tuple(session.exec(select(func.count(KnowledgeItem.id),
        func.max(KnowledgeItem.updated_at), func.sum(KnowledgeItem.version)).where(
        KnowledgeItem.tenant_id == tenant_id, KnowledgeItem.is_active.is_(True))).one())
    key = (session.get_bind(), tenant_id)
    with _cache_lock:
        cached = _cache.get(key)
        if cached and cached[0] == revision:
            _cache.move_to_end(key)
            return cached[1]
    rows = session.exec(select(KnowledgeItem).where(
        KnowledgeItem.tenant_id == tenant_id, KnowledgeItem.is_active.is_(True))).all()
    items = tuple(SimpleNamespace(id=row.id, title=row.title, keywords=row.keywords,
        content=row.content, category=row.category, version=row.version,
        source=row.source, valid_from=row.valid_from,valid_until=row.valid_until,campaign_id=row.campaign_id,
        searchable=f"{row.title} {row.keywords} {row.content}".lower(),
        terms={value.lower() for value in re.split(r"[\s,，;；]+", row.keywords)
               if len(value.strip()) >= 2}) for row in rows)
    size = sum(4 * (len(item.searchable) + len(item.content) + len(item.keywords)) + 512 for item in items)
    with _cache_lock:
        _cache.pop(key, None)
        if size <= _MAX_CACHE_BYTES:
            while _cache and (len(_cache) >= 128 or sum(value[2] for value in _cache.values()) + size > _MAX_CACHE_BYTES):
                _cache.popitem(last=False)
            _cache[key] = (revision, items, size)
    return items


def retrieve_knowledge(session: Session, tenant_id: int, query: str, limit: int = 3, *, campaign_id: int | None = None) -> list[dict[str, str]]:
    now=utc_now()
    items = [item for item in _knowledge_snapshot(session, tenant_id)
             if (item.valid_from is None or item.valid_from<=now) and (item.valid_until is None or item.valid_until>now)
             and (item.campaign_id is None or item.campaign_id==campaign_id)]
    return rank_knowledge(items,query,limit)


def rank_knowledge(items,query,limit=3):
    tokens = {token.lower() for token in re.findall(r"[\w\u4e00-\u9fff]+", query) if len(token) >= 2}
    # Character bigrams improve Chinese paraphrase recall without pretending to
    # be a semantic confidence score. Explicit configured keywords rank higher.
    chinese=''.join(re.findall(r'[\u4e00-\u9fff]',query))
    grams={chinese[i:i+2] for i in range(max(0,len(chinese)-1))}

    def score(item: KnowledgeItem) -> tuple[int, int]:
        searchable = item.searchable
        item_keywords = item.terms
        query_lower = query.lower()
        token_hits = sum(1 for token in tokens if token in searchable)
        keyword_hits = sum(2 for keyword in item_keywords if keyword in query_lower)
        gram_hits=sum(1 for gram in grams if gram in searchable)
        return token_hits*4 + keyword_hits*4 + (gram_hits if gram_hits>=2 else 0), item.version

    ranked = heapq.nlargest(max(1, limit), items, key=score)
    matched = [item for item in ranked if score(item)[0] > 0]
    return [
        {"id": str(item.id), "title": item.title, "content": item.content, "category": item.category,
         "source":getattr(item,'source',''), "version":str(item.version)}
        for item in matched[: max(1, limit)]
    ]


def retrieve_bound_knowledge(session, state, query, campaign_id=None):
    from ..models import ScenarioVersion
    import json
    from datetime import datetime
    version=session.get(ScenarioVersion,state.policy_version_id) if state.policy_version_id else None
    snapshot=json.loads(version.policy_json) if version else {}
    if '_knowledge' not in snapshot:
        return retrieve_knowledge(session,state.tenant_id,query,campaign_id=campaign_id)
    now=utc_now()
    items=[]
    for row in snapshot['_knowledge']:
        if row.get('valid_from') and datetime.fromisoformat(row['valid_from'])>now:continue
        if row.get('valid_until') and datetime.fromisoformat(row['valid_until'])<=now:continue
        items.append(SimpleNamespace(**row,searchable=f"{row['title']} {row['keywords']} {row['content']}".lower(),
            terms={v.lower() for v in re.split(r'[\s,，;；]+',row['keywords']) if len(v)>=2}))
    return rank_knowledge(items,query)
