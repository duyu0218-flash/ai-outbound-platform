from fastapi import Header, HTTPException, Query, status
from sqlmodel import Session
from typing import Annotated

from ..config import get_settings
from ..db import get_session

settings = get_settings()

APIKey = Annotated[str, Header(alias="x-api-key")]


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
    if not token:
        return
    if not x_webhook_token or x_webhook_token != token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid webhook token")


def get_session_dep() -> Session:
    with get_session() as session:
        yield session


def get_pagination(
    page: Annotated[int, Query(ge=1)] = 1,
    size: Annotated[int, Query(ge=1, le=200)] = 50,
) -> tuple[int, int]:
    skip = (page - 1) * size
    return skip, size
