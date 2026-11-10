"""3x-ui panel provisioning of short-lived Xray clients (one per VPN session).

Only one inbound (the one configured for the server) is ever touched, and only clients that this
module created: their id is derived from the session id and their e-mail starts with ``fg-``.
Customer inbounds and users of the panel are never read, changed or deleted.
"""

import json
import logging
import time
import uuid

import httpx

from app.core import security
from app.models.server import VpnServer

log = logging.getLogger(__name__)

PREFIX = "fg-"
TIMEOUT = httpx.Timeout(10.0, connect=5.0)
_NAMESPACE = uuid.UUID("6f0f6b9e-3c55-4a3f-9d62-1f6c1d2a7e10")
EXPIRY_SLACK_SECONDS = 120


class XuiError(Exception):
    pass


def client_id(session_id) -> str:
    return str(uuid.uuid5(_NAMESPACE, str(session_id)))


def client_email(session_id) -> str:
    return PREFIX + uuid.UUID(str(session_id)).hex[:12]


def build_link(server: VpnServer, session_id) -> str:
    """The server's link template with this session's client id filled in."""
    return server.ovpn_config.strip().replace("{uuid}", client_id(session_id))


def client_for(server: VpnServer) -> httpx.Client:
    """HTTP client for the panel (patched in tests)."""
    if not (server.xui_url and server.xui_user and server.xui_password_enc and server.xui_inbound_id):
        raise XuiError("3x-ui access is not configured for this server")
    return httpx.Client(base_url=server.xui_url.rstrip("/") + "/", timeout=TIMEOUT)


def _check(r: httpx.Response, what: str, *, tolerate_missing: bool = False) -> None:
    if r.status_code >= 400:
        raise XuiError(f"{what} failed: HTTP {r.status_code}")
    try:
        data = r.json()
    except ValueError:
        raise XuiError(f"{what} failed: unexpected reply") from None
    if data.get("success"):
        return
    msg = str(data.get("msg") or "")
    if tolerate_missing and any(w in msg.lower() for w in ("not found", "no such", "not exist")):
        return
    raise XuiError(f"{what} failed: {msg[:120]}")


def _login(c: httpx.Client, server: VpnServer) -> None:
    r = c.post("login", data={"username": server.xui_user, "password": security.decrypt_secret(server.xui_password_enc)})
    _check(r, "panel login")


def add_client(server: VpnServer, session_id, *, valid_seconds: int | None) -> None:
    """Create the session's client. ``valid_seconds`` None = no expiry (VIP)."""
    expiry_ms = 0 if valid_seconds is None else int((time.time() + valid_seconds + EXPIRY_SLACK_SECONDS) * 1000)
    client = {
        "id": client_id(session_id),
        "flow": "",
        "email": client_email(session_id),
        "limitIp": 0,
        "totalGB": 0,
        "expiryTime": expiry_ms,
        "enable": True,
        "tgId": "",
        "subId": "",
        "comment": "fastgame session",
        "reset": 0,
    }
    try:
        with client_for(server) as c:
            _login(c, server)
            r = c.post(
                "panel/api/inbounds/addClient",
                data={"id": server.xui_inbound_id, "settings": json.dumps({"clients": [client]})},
            )
            _check(r, "add client")
    except httpx.HTTPError as e:
        raise XuiError(f"panel unreachable: {e.__class__.__name__}") from e


def remove_client(server: VpnServer, session_id) -> None:
    """Delete the session's client. Safe to call twice."""
    try:
        with client_for(server) as c:
            _login(c, server)
            r = c.post(f"panel/api/inbounds/{server.xui_inbound_id}/delClient/{client_id(session_id)}")
            _check(r, "delete client", tolerate_missing=True)
    except httpx.HTTPError as e:
        raise XuiError(f"panel unreachable: {e.__class__.__name__}") from e
