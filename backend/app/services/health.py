from __future__ import annotations

from sqlmodel import Session, select

from ..db import engine


def db_health_check() -> str:
    try:
        with Session(engine) as session:
            session.exec(select(1)).first()
        return "ok"
    except Exception:
        return "unavailable"
