"""Admin: manage the OpenVPN servers the app can connect to."""

from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import get_current_admin, get_db
from app.core import security
from app.core.errors import ApiError
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.schemas.admin import MikrotikOut, ServerAdminOut, ServerIn
from app.services import ovpn

router = APIRouter(prefix="/admin/servers", tags=["admin"], dependencies=[Depends(get_current_admin)])


def _to_out(server: VpnServer, active_connections: int) -> ServerAdminOut:
    return ServerAdminOut(
        id=server.id,
        name=server.name,
        country_code=server.country_code,
        country_name_en=server.country_name_en,
        country_name_fa=server.country_name_fa,
        host=server.host,
        port=server.port,
        proto=server.proto,
        ovpn_config=server.ovpn_config,
        auth_username=server.auth_username,
        has_credentials=bool(server.auth_username and server.auth_password_enc),
        mikrotik=MikrotikOut(
            api_url=server.mt_api_url,
            username=server.mt_api_user or "",
            ppp_profile=server.mt_ppp_profile,
            has_ca=bool(server.mt_ca_pem),
        )
        if server.mt_api_url
        else None,
        is_active=server.is_active,
        sort_order=server.sort_order,
        created_at=server.created_at,
        active_connections=active_connections,
    )


def _active_count(db: Session, server_id: int) -> int:
    return (
        db.scalar(
            select(func.count()).where(VpnSession.server_id == server_id, VpnSession.ended_at.is_(None))
        )
        or 0
    )


def _apply(server: VpnServer, body: ServerIn) -> None:
    server.name = body.name
    server.country_code = body.country_code
    server.country_name_en = body.country_name_en
    server.country_name_fa = body.country_name_fa
    parsed = ovpn.parse_profile(body.ovpn_config)
    host = body.host or parsed.host
    port = body.port or parsed.port
    proto = body.proto or parsed.proto
    if not (host and port and proto):
        raise ApiError(
            422, "profile_incomplete", "Could not read host, port and protocol from the profile; provide them."
        )
    server.host = host
    server.port = port
    server.proto = proto
    server.ovpn_config = parsed.config  # inline credentials are stored encrypted, never in the profile
    server.is_active = body.is_active
    server.sort_order = body.sort_order
    if body.clear_mikrotik:
        server.mt_api_url = server.mt_api_user = server.mt_api_password_enc = None
        server.mt_ppp_profile = server.mt_ca_pem = None
    elif body.mikrotik is not None:
        mt = body.mikrotik
        if mt.password is None and not server.mt_api_password_enc:
            raise ApiError(422, "mikrotik_password_required", "A password is required for the MikroTik API user.")
        server.mt_api_url = mt.api_url
        server.mt_api_user = mt.username
        if mt.password:
            server.mt_api_password_enc = security.encrypt_secret(mt.password)
        server.mt_ppp_profile = mt.ppp_profile
        if mt.ca_pem:  # empty keeps the pinned certificate
            server.mt_ca_pem = mt.ca_pem
    if body.clear_credentials:
        server.auth_username = None
        server.auth_password_enc = None
    else:
        username = body.auth_username if body.auth_username is not None else parsed.username
        password = body.auth_password if body.auth_password is not None else parsed.password
        if username is not None:
            server.auth_username = username or None
        if password is not None:
            server.auth_password_enc = security.encrypt_secret(password) if password else None


@router.get("", response_model=list[ServerAdminOut])
def list_servers(db: Session = Depends(get_db)) -> list[ServerAdminOut]:
    counts = (
        select(VpnSession.server_id, func.count().label("n"))
        .where(VpnSession.ended_at.is_(None))
        .group_by(VpnSession.server_id)
        .subquery()
    )
    rows = db.execute(
        select(VpnServer, func.coalesce(counts.c.n, 0))
        .outerjoin(counts, counts.c.server_id == VpnServer.id)
        .order_by(VpnServer.sort_order, VpnServer.id)
    ).all()
    return [_to_out(server, int(n)) for server, n in rows]


@router.post("", response_model=ServerAdminOut, status_code=201)
def create_server(body: ServerIn, db: Session = Depends(get_db)) -> ServerAdminOut:
    server = VpnServer(ovpn_config=body.ovpn_config)
    _apply(server, body)
    db.add(server)
    db.commit()
    return _to_out(server, 0)


@router.put("/{server_id}", response_model=ServerAdminOut)
def update_server(server_id: int, body: ServerIn, db: Session = Depends(get_db)) -> ServerAdminOut:
    server = db.get(VpnServer, server_id)
    if server is None:
        raise ApiError(404, "server_not_found", "Server not found.")
    _apply(server, body)
    db.commit()
    return _to_out(server, _active_count(db, server.id))


@router.delete("/{server_id}", status_code=204)
def delete_server(server_id: int, db: Session = Depends(get_db)) -> None:
    server = db.get(VpnServer, server_id)
    if server is None:
        raise ApiError(404, "server_not_found", "Server not found.")
    try:
        db.delete(server)
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(
            409,
            "server_has_sessions",
            "This server has usage history. Deactivate it instead of deleting it.",
        ) from None
