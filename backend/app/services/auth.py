from __future__ import annotations

import hashlib
import hmac
import secrets
from base64 import urlsafe_b64decode, urlsafe_b64encode
from binascii import Error as BinasciiError
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import HTTPException, status
from jose import JWTError, jwt
from sqlmodel import Session, select

from ..config import get_settings
from ..clock import utc_now
from ..models import User

settings = get_settings()
PASSWORD_SCHEME = "pbkdf2_sha256"
PASSWORD_ITERATIONS = 600_000


def _legacy_hash_password(raw_password: str) -> str:
    salt = settings.jwt_secret or settings.secret_key
    return hashlib.sha256(f"{salt}:{raw_password}".encode("utf-8")).hexdigest()


def _hash_password(raw_password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        raw_password.encode("utf-8"),
        salt,
        PASSWORD_ITERATIONS,
    )
    return "$".join(
        [
            PASSWORD_SCHEME,
            str(PASSWORD_ITERATIONS),
            urlsafe_b64encode(salt).decode("ascii"),
            urlsafe_b64encode(digest).decode("ascii"),
        ]
    )


def _verify_password(stored_hash: str, raw_password: str) -> bool:
    if stored_hash.startswith(f"{PASSWORD_SCHEME}$"):
        try:
            _, iterations_raw, salt_raw, digest_raw = stored_hash.split("$", 3)
            iterations = int(iterations_raw)
            if iterations < 100_000 or iterations > 2_000_000:
                return False
            actual = hashlib.pbkdf2_hmac(
                "sha256",
                raw_password.encode("utf-8"),
                urlsafe_b64decode(salt_raw.encode("ascii")),
                iterations,
            )
            expected = urlsafe_b64decode(digest_raw.encode("ascii"))
            return hmac.compare_digest(actual, expected)
        except (BinasciiError, TypeError, ValueError):
            return False
    return hmac.compare_digest(stored_hash, _legacy_hash_password(raw_password))


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
        if user is None:
            session.add(
                User(
                    tenant_id=tenant_id,
                    username=info["username"],
                    password_hash=_hash_password(info["password"]),
                    full_name=info["full_name"],
                    role=info["role"],
                    is_supervisor=info["is_supervisor"],
                    enabled=True,
                )
            )
        else:
            # keep id stable, sync demo credential for local test
            if not _verify_password(user.password_hash, info["password"]):
                user.password_hash = _hash_password(info["password"])
            user.role = info["role"]
            user.full_name = info["full_name"]
            user.enabled = True
            user.is_supervisor = info["is_supervisor"]
            user.updated_at = utc_now()
            session.add(user)

    session.commit()


def authenticate_user(session: Session, username: str, raw_password: str) -> Optional[User]:
    user = session.exec(select(User).where(User.username == username)).first()
    if not user or not user.enabled:
        return None
    if not _verify_password(user.password_hash, raw_password):
        return None
    if not user.password_hash.startswith(f"{PASSWORD_SCHEME}$"):
        user.password_hash = _hash_password(raw_password)
        user.updated_at = utc_now()
        session.add(user)
        session.commit()
        session.refresh(user)
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
