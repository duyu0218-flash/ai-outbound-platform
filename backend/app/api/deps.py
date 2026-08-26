from fastapi import Depends, Header, HTTPException, Query, status
from fastapi.security import OAuth2PasswordBearer
from sqlmodel import Session
from typing import Annotated
from typing import Optional

from ..models import User
from ..config import get_settings
from ..db import get_session as get_db_session
from ..services.auth import find_user_by_token

settings = get_settings()

APIKey = Annotated[str, Header(alias="x-api-key")]
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/v1/auth/login", auto_error=False)


def check_api_key(x_api_key: str | None = Header(default=None, alias="x-api-key")) -> None:
    if settings.api_key is None and settings.ui_api_key is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="api key not configured in server",
        )
    if not x_api_key or (
        x_api_key != settings.api_key
        and (settings.ui_api_key is None or x_api_key != settings.ui_api_key)
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid api key")


def get_tenant_id(x_tenant_id: int | None = Header(default=None, alias="x-tenant-id")) -> int:
    if x_tenant_id is None:
        x_tenant_id = settings.default_tenant_id
    if x_tenant_id <= 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid tenant id")
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
    if not x_webhook_token or x_webhook_token != token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid webhook token")


def get_session() -> Session:
    with get_db_session() as session:
        yield session


def get_session_dep() -> Session:
    with get_db_session() as session:
        yield session


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
