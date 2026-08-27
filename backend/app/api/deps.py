import hmac
from typing import Annotated, Optional

from fastapi import Depends, Header, HTTPException, Query, status
from fastapi.security import OAuth2PasswordBearer
from sqlmodel import Session

from ..config import get_settings
from ..db import get_session as get_db_session
from ..models import User
from ..services.auth import find_user_by_token

settings = get_settings()

APIKey = Annotated[str, Header(alias="x-api-key")]
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/v1/auth/login", auto_error=False)


def get_session() -> Session:
    yield from get_db_session()


def get_session_dep() -> Session:
    yield from get_db_session()


def _secure_equals(left: str | None, right: str | None) -> bool:
    if left is None or right is None:
        return False
    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


def check_api_key(
    x_api_key: str | None = Header(default=None, alias="x-api-key"),
    token: str = Depends(oauth2_scheme),
) -> None:
    # Browser clients authenticate with Bearer tokens. API keys are reserved for
    # trusted server-to-server callers and must never be embedded into HTML.
    if token:
        return
    if settings.api_key is None and settings.ui_api_key is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="api key not configured in server",
        )
    if _secure_equals(x_api_key, settings.api_key):
        return
    if _secure_equals(x_api_key, settings.ui_api_key):
        return
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid api key")


def get_tenant_id(x_tenant_id: int | None = Header(default=None, alias="x-tenant-id")) -> int:
    if x_tenant_id is None:
        x_tenant_id = settings.default_tenant_id
    if x_tenant_id <= 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid tenant id")
    return x_tenant_id


def get_tenant_id_for_request(
    x_tenant_id: int | None = Header(default=None, alias="x-tenant-id"),
    token: str = Depends(oauth2_scheme),
    session: Session = Depends(get_session),
) -> int:
    """Bind browser/API user requests to the tenant stored on the current user."""
    if not token:
        return get_tenant_id(x_tenant_id)

    user = find_user_by_token(session, token)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    if x_tenant_id is None:
        return user.tenant_id
    if x_tenant_id != user.tenant_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="tenant scope mismatch")
    return x_tenant_id


def check_webhook_token(x_webhook_token: str | None = Header(default=None, alias="x-webhook-token")) -> None:
    token = settings.telephony_webhook_token.strip()
    is_prod = settings.env.lower() in {"prod", "production"}
    if is_prod and not token:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="telephony webhook token missing in production",
        )
    if not token:
        return
    if not _secure_equals(x_webhook_token, token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid webhook token")


def current_user(
    token: str = Depends(oauth2_scheme),
    session: Session = Depends(get_session),
) -> User:
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authorization header missing")
    user = find_user_by_token(session, token)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return user


def current_user_optional(
    token: str = Depends(oauth2_scheme),
    session: Session = Depends(get_session),
) -> Optional[User]:
    if not token:
        return None
    user = find_user_by_token(session, token)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return user


def require_role(required_role: str):
    def _resolver(current: User = Depends(current_user)) -> User:
        if current.role != required_role:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="insufficient role")
        return current

    return _resolver


def require_any_role(*roles: str):
    allowed = set(roles)

    def _resolver(current: User = Depends(current_user)) -> User:
        if current.role not in allowed:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="insufficient role")
        return current

    return _resolver


def require_roles(*roles: str):
    return require_any_role(*roles)


def require_roles_if_authenticated(*roles: str):
    allowed = set(roles)

    def _resolver(current: User | None = Depends(current_user_optional)) -> Optional[User]:
        if current is None:
            return None
        if current.role not in allowed:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="insufficient role")
        return current

    return _resolver


def get_pagination(
    page: Annotated[int, Query(ge=1)] = 1,
    size: Annotated[int, Query(ge=1, le=200)] = 50,
) -> tuple[int, int]:
    skip = (page - 1) * size
    return skip, size
