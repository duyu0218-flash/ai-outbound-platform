from __future__ import annotations

import re
import heapq
import threading
from collections import OrderedDict
from types import SimpleNamespace
from sqlalchemy import func
from sqlmodel import Session, select

from ..models import KnowledgeItem


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


def retrieve_knowledge(session: Session, tenant_id: int, query: str, limit: int = 3) -> list[dict[str, str]]:
    items = _knowledge_snapshot(session, tenant_id)
    tokens = {token.lower() for token in re.findall(r"[\w\u4e00-\u9fff]+", query) if len(token) >= 2}

    def score(item: KnowledgeItem) -> tuple[int, int]:
        searchable = item.searchable
        item_keywords = item.terms
        query_lower = query.lower()
        token_hits = sum(1 for token in tokens if token in searchable)
        keyword_hits = sum(2 for keyword in item_keywords if keyword in query_lower)
        return token_hits + keyword_hits, item.version

    ranked = heapq.nlargest(max(1, limit), items, key=score)
    matched = [item for item in ranked if score(item)[0] > 0]
    return [
        {"id": str(item.id), "title": item.title, "content": item.content, "category": item.category}
        for item in matched[: max(1, limit)]
    ]
