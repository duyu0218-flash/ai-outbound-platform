import hashlib
import hmac
import json
import time
from typing import Annotated, Optional

from fastapi import Depends, Header, HTTPException, Query, Request, status
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
    request: Request,
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
    tenant_keys: dict[int, str] = {}
    if settings.tenant_api_keys_json.strip():
        try:
            raw_keys = json.loads(settings.tenant_api_keys_json)
            tenant_keys = {int(key): str(value) for key, value in raw_keys.items() if str(value)}
        except (TypeError, ValueError, json.JSONDecodeError):
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="tenant api key configuration is invalid",
            )
    matched_tenant = next(
        (tenant_id for tenant_id, key in tenant_keys.items() if _secure_equals(x_api_key, key)),
        None,
    )
    if matched_tenant is not None:
        request.state.api_tenant_id = matched_tenant
        return
    # Legacy keys remain supported, but are deliberately scoped to the default
    # tenant instead of trusting a caller-controlled x-tenant-id header.
    if _secure_equals(x_api_key, settings.api_key) or _secure_equals(x_api_key, settings.ui_api_key):
        request.state.api_tenant_id = settings.default_tenant_id
        return
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid api key")


def get_tenant_id(x_tenant_id: int | None = Header(default=None, alias="x-tenant-id")) -> int:
    if x_tenant_id is None:
        x_tenant_id = settings.default_tenant_id
    if x_tenant_id <= 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid tenant id")
    return x_tenant_id


def get_tenant_id_for_request(
    request: Request,
    x_tenant_id: int | None = Header(default=None, alias="x-tenant-id"),
    token: str = Depends(oauth2_scheme),
    session: Session = Depends(get_session),
) -> int:
    """Bind browser/API user requests to the tenant stored on the current user."""
    if not token:
        api_tenant_id = getattr(request.state, "api_tenant_id", None)
        if api_tenant_id is None:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="api tenant scope missing")
        if x_tenant_id is not None and x_tenant_id != api_tenant_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="tenant scope mismatch")
        return int(api_tenant_id)

    user = find_user_by_token(session, token)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    if x_tenant_id is None:
        return user.tenant_id
    if x_tenant_id != user.tenant_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="tenant scope mismatch")
    return x_tenant_id


async def _verify_webhook_request(
    request: Request,
    *,
    token: str,
    secret: str,
    label: str,
    x_webhook_token: str | None,
    x_webhook_timestamp: str | None,
    x_webhook_signature: str | None,
) -> None:
    is_prod = settings.env.lower() in {"prod", "production"}
    if is_prod and not token:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"{label} webhook token missing in production",
        )
    if token and not _secure_equals(x_webhook_token, token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=f"invalid {label} webhook token")
    if is_prod and not secret:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"{label} webhook signing secret missing in production",
        )
    if not secret:
        return
    if not x_webhook_timestamp or not x_webhook_signature:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=f"missing {label} webhook signature")
    try:
        timestamp = int(x_webhook_timestamp)
    except (TypeError, ValueError):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=f"invalid {label} webhook timestamp")
    max_age = max(30, min(3600, int(settings.webhook_signature_max_age_sec)))
    if abs(int(time.time()) - timestamp) > max_age:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=f"expired {label} webhook signature")
    body = await request.body()
    expected = hmac.new(
        secret.encode("utf-8"),
        x_webhook_timestamp.encode("ascii") + b"." + body,
        hashlib.sha256,
    ).hexdigest()
    supplied = x_webhook_signature.removeprefix("sha256=")
    if not _secure_equals(supplied, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=f"invalid {label} webhook signature")


async def check_webhook_token(
    request: Request,
    x_webhook_token: str | None = Header(default=None, alias="x-webhook-token"),
    x_webhook_timestamp: str | None = Header(default=None, alias="x-webhook-timestamp"),
    x_webhook_signature: str | None = Header(default=None, alias="x-webhook-signature"),
) -> None:
    await _verify_webhook_request(
        request,
        token=settings.telephony_webhook_token.strip(),
        secret=settings.telephony_webhook_secret.strip(),
        label="telephony",
        x_webhook_token=x_webhook_token,
        x_webhook_timestamp=x_webhook_timestamp,
        x_webhook_signature=x_webhook_signature,
    )


async def check_sms_webhook_token(
    request: Request,
    x_webhook_token: str | None = Header(default=None, alias="x-webhook-token"),
    x_webhook_timestamp: str | None = Header(default=None, alias="x-webhook-timestamp"),
    x_webhook_signature: str | None = Header(default=None, alias="x-webhook-signature"),
) -> None:
    await _verify_webhook_request(
        request,
        token=settings.sms_webhook_token.strip(),
        secret=settings.sms_webhook_secret.strip(),
        label="SMS",
        x_webhook_token=x_webhook_token,
        x_webhook_timestamp=x_webhook_timestamp,
        x_webhook_signature=x_webhook_signature,
    )


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
