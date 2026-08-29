from fastapi import APIRouter, Depends, HTTPException, status
from sqlmodel import Session

from ...api.deps import current_user
from ...db import get_session
from ...clock import utc_now
from ...schemas import AgentPresenceUpdate, LoginRequest, LoginResponse, UserOut
from ...services.auth import authenticate_user, create_access_token

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _user_out(user) -> UserOut:
    return UserOut(
        id=user.id,
        tenant_id=user.tenant_id,
        username=user.username,
        full_name=user.full_name,
        role=user.role,
        is_supervisor=user.is_supervisor,
        agent_status=user.agent_status,
        last_seen_at=user.last_seen_at,
        enabled=user.enabled,
    )


@router.post("/login", response_model=LoginResponse)
def login(payload: LoginRequest, session: Session = Depends(get_session)):
    user = authenticate_user(session, payload.username, payload.password)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid username or password")
    if user.role == "agent":
        user.agent_status = "ready"
        user.last_seen_at = utc_now()
        user.updated_at = utc_now()
        session.add(user)
        session.commit()
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
    return _user_out(current)


@router.put("/presence", response_model=UserOut)
def update_presence(
    payload: AgentPresenceUpdate,
    current=Depends(current_user),
    session: Session = Depends(get_session),
):
    if current.role != "agent":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="presence is only available for agents")
    managed = session.get(type(current), current.id)
    if managed is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user not found")
    managed.agent_status = payload.status
    managed.last_seen_at = utc_now()
    managed.updated_at = utc_now()
    session.add(managed)
    session.commit()
    session.refresh(managed)
    return _user_out(managed)


@router.post("/logout")
def logout(current=Depends(current_user), session: Session = Depends(get_session)):
    if current.role == "agent":
        managed = session.get(type(current), current.id)
        if managed is not None:
            managed.agent_status = "offline"
            managed.last_seen_at = utc_now()
            managed.updated_at = utc_now()
            session.add(managed)
            session.commit()
    return {"result": "ok"}
