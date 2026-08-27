from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import HTTPException, status
from jose import JWTError, jwt
from sqlmodel import Session, select

from ..config import get_settings
from ..models import User

settings = get_settings()


def _hash_password(raw_password: str) -> str:
    salt = settings.jwt_secret or settings.secret_key
    return hashlib.sha256(f"{salt}:{raw_password}".encode("utf-8")).hexdigest()


def ensure_demo_users(session: Session) -> None:
    tenant_id = settings.demo_tenant_id
    admins = [
        {
            "username": settings.demo_admin_username,
            "password": settings.demo_admin_password,
            "full_name": "演示管理员",
            "role": "admin",
            "is_supervisor": True,
        },
        {
            "username": settings.demo_agent_username,
            "password": settings.demo_agent_password,
            "full_name": "演示座席 1001",
            "role": "agent",
            "is_supervisor": False,
        },
    ]

    for info in admins:
        user = session.exec(
            select(User)
            .where(User.username == info["username"])
            .where(User.tenant_id == tenant_id)
        ).first()
        password_hash = _hash_password(info["password"])
        if user is None:
            session.add(
                User(
                    tenant_id=tenant_id,
                    username=info["username"],
                    password_hash=password_hash,
                    full_name=info["full_name"],
                    role=info["role"],
                    is_supervisor=info["is_supervisor"],
                    enabled=True,
                )
            )
        else:
            # keep id stable, sync demo credential for local test
            user.password_hash = password_hash
            user.role = info["role"]
            user.full_name = info["full_name"]
            user.enabled = True
            user.is_supervisor = info["is_supervisor"]
            user.updated_at = datetime.utcnow()
            session.add(user)

    session.commit()


def authenticate_user(session: Session, username: str, raw_password: str) -> Optional[User]:
    user = session.exec(select(User).where(User.username == username)).first()
    if not user or not user.enabled:
        return None
    if not hmac.compare_digest(user.password_hash, _hash_password(raw_password)):
        return None
    return user


def create_access_token(subject: str, tenant_id: int, role: str, user_id: int) -> str:
    expire = datetime.now(timezone.utc) + timedelta(seconds=settings.jwt_ttl_seconds)
    payload = {
        "sub": subject,
        "tenant_id": tenant_id,
        "role": role,
        "uid": user_id,
        "exp": expire,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def parse_token(token: str) -> dict:
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except JWTError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")


def find_user_by_token(session: Session, token: str) -> Optional[User]:
    payload = parse_token(token)
    username = payload.get("sub")
    user_id = payload.get("uid")
    if not username:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token payload")

    if not isinstance(user_id, int):
        if isinstance(user_id, str) and user_id.isdigit():
            try:
                user_id = int(user_id)
            except ValueError:
                user_id = None
        else:
            user_id = None

    user: Optional[User] | None = None
    if isinstance(user_id, int):
        user = session.exec(select(User).where(User.id == user_id)).first()
    if user is None:
        user = session.exec(select(User).where(User.username == username)).first()
    if user is None or not user.enabled:
        return None
    # Prevent token/user mismatch (e.g., old token with replaced id/username data drift)
    if str(user.username) != str(username) or (user_id is not None and user.id != user_id):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token payload")
    return user
