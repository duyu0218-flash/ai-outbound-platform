from fastapi import APIRouter, Depends, HTTPException, status
from sqlmodel import Session

from ...api.deps import current_user
from ...db import get_session
from ...schemas import LoginRequest, LoginResponse, UserOut
from ...services.auth import authenticate_user, create_access_token

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


@router.post("/login", response_model=LoginResponse)
def login(payload: LoginRequest, session: Session = Depends(get_session)):
    user = authenticate_user(session, payload.username, payload.password)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid username or password")
    token = create_access_token(
        subject=user.username,
        tenant_id=user.tenant_id,
        role=user.role,
        user_id=user.id,
    )
    return LoginResponse(
        access_token=token,
        username=user.username,
        role=user.role,
        tenant_id=user.tenant_id,
    )


@router.get("/me", response_model=UserOut)
def me(current=Depends(current_user)):
    return UserOut(
        id=current.id,
        tenant_id=current.tenant_id,
        username=current.username,
        full_name=current.full_name,
        role=current.role,
        is_supervisor=current.is_supervisor,
        enabled=current.enabled,
    )
