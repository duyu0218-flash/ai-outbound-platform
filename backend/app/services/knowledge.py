from __future__ import annotations

import re
from sqlmodel import Session, select

from ..models import KnowledgeItem


def retrieve_knowledge(session: Session, tenant_id: int, query: str, limit: int = 3) -> list[dict[str, str]]:
    items = session.exec(
        select(KnowledgeItem).where(
            KnowledgeItem.tenant_id == tenant_id,
            KnowledgeItem.is_active.is_(True),
        )
    ).all()
    tokens = {token.lower() for token in re.findall(r"[\w\u4e00-\u9fff]+", query) if len(token) >= 2}

    def score(item: KnowledgeItem) -> tuple[int, int]:
        searchable = f"{item.title} {item.keywords} {item.content}".lower()
        item_keywords = {
            value.lower()
            for value in re.split(r"[\s,，;；]+", item.keywords)
            if len(value.strip()) >= 2
        }
        query_lower = query.lower()
        token_hits = sum(1 for token in tokens if token in searchable)
        keyword_hits = sum(2 for keyword in item_keywords if keyword in query_lower)
        return token_hits + keyword_hits, item.version

    ranked = sorted(items, key=score, reverse=True)
    matched = [item for item in ranked if score(item)[0] > 0]
    return [
        {"id": str(item.id), "title": item.title, "content": item.content, "category": item.category}
        for item in matched[: max(1, limit)]
    ]
