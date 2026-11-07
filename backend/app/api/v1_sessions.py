"""Connect / heartbeat / disconnect. The OpenVPN profile is only released here,
after the server has checked the block state and the remaining quota."""

import logging
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_device, get_db
from app.core import security
from app.core.config import get_settings
from app.models.device import Device
from app.schemas.session import SessionStateResponse, StartSessionRequest, StartSessionResponse
from app.core.errors import ApiError
from app.services import credentials, mikrotik, sessions

log = logging.getLogger("uvicorn.error")
router = APIRouter(prefix="/v1/sessions", tags=["app"])


def _state_out(result: sessions.StateResult) -> SessionStateResponse:
    return SessionStateResponse(
        status=result.status,  # type: ignore[arg-type]
        is_vip=result.vip,
        remaining_seconds=None if result.vip else result.snapshot.remaining_seconds,
        resets_at=None if result.vip else result.snapshot.resets_at,
    )


@router.post("", response_model=StartSessionResponse, status_code=201)
def start(
    body: StartSessionRequest,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> StartSessionResponse:
    result = sessions.start_session(db, device.id, body.server_id)
    server = result.server
    username, password = server.auth_username, None
    if credentials.uses_router_accounts(server):
        remaining = None if result.vip else int(result.snapshot.remaining_seconds)
        try:
            username, password = credentials.issue(db, server, result.session, remaining)
        except mikrotik.MikrotikError as e:
            log.warning("router account for server %s could not be created: %s", server.id, e)
            raise ApiError(503, "server_unavailable", "This server is not available. Try another one.") from None
    elif server.auth_password_enc:
        password = security.decrypt_secret(server.auth_password_enc)
    return StartSessionResponse(
        session_id=result.session.id,
        server_id=server.id,
        heartbeat_interval_seconds=get_settings().heartbeat_interval_seconds,
        is_vip=result.vip,
        remaining_seconds=None if result.vip else result.snapshot.remaining_seconds,
        resets_at=None if result.vip else result.snapshot.resets_at,
        ovpn_config=server.ovpn_config,
        auth_username=username,
        auth_password=password,
    )


@router.post("/{session_id}/heartbeat", response_model=SessionStateResponse)
def heartbeat(
    session_id: UUID,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    result = sessions.heartbeat(db, device.id, session_id)
    if result.status != "active":
        credentials.revoke_now(db, session_id)
    return _state_out(result)


@router.post("/{session_id}/connected", response_model=SessionStateResponse)
def connected(
    session_id: UUID,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    """The tunnel is up; charging of connected time starts now."""
    return _state_out(sessions.confirm_connected(db, device.id, session_id))


@router.post("/{session_id}/stop", response_model=SessionStateResponse)
def stop(
    session_id: UUID,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    result = sessions.stop_session(db, device.id, session_id)
    credentials.revoke_now(db, session_id)
    return _state_out(result)
