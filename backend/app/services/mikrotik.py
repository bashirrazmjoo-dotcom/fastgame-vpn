"""MikroTik RouterOS 7 (REST API) provisioning of short-lived OpenVPN accounts.

Every VPN session gets its own PPP secret that
  * is created only after the backend checked block state and quota,
  * is deleted (and the active tunnel is kicked) as soon as the session ends. RouterOS has no
    per-secret time limit for PPP, so the backend is what ends a session: when the quota runs out,
    the device is blocked, or heartbeats stop (the reaper), the account is revoked within seconds.

Only the ``fg-`` prefixed secrets are ever touched; the router's own users are never read or
changed.
"""

import logging
import ssl

import httpx

from app.core import security
from app.models.server import VpnServer

log = logging.getLogger(__name__)

PREFIX = "fg-"
TIMEOUT = httpx.Timeout(8.0, connect=5.0)


class MikrotikError(Exception):
    pass


def client_for(server: VpnServer) -> httpx.Client:
    """HTTP client for the router's REST API (patched in tests)."""
    if not (server.mt_api_url and server.mt_api_user and server.mt_api_password_enc):
        raise MikrotikError("MikroTik access is not configured for this server")
    verify: ssl.SSLContext | bool = True
    if server.mt_ca_pem:
        verify = ssl.create_default_context(cadata=server.mt_ca_pem)
    return httpx.Client(
        base_url=server.mt_api_url.rstrip("/") + "/rest",
        auth=(server.mt_api_user, security.decrypt_secret(server.mt_api_password_enc)),
        verify=verify,
        timeout=TIMEOUT,
    )


def _check(r: httpx.Response, what: str) -> None:
    if r.status_code >= 400:
        detail = ""
        try:
            detail = r.json().get("detail") or r.json().get("message") or ""
        except Exception:
            pass
        raise MikrotikError(f"{what} failed: HTTP {r.status_code} {detail}".strip())


def routeros_duration(seconds: int) -> str:
    seconds = max(1, int(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}h{m}m{s}s"


def create_account(
    server: VpnServer, username: str, password: str, *, limit_uptime_seconds: int | None
) -> None:
    body = {
        "name": username,
        "password": password,
        "service": "ovpn",
        "profile": server.mt_ppp_profile or "default",
        "comment": "fastgame session",
    }
    # limit_uptime_seconds is accepted for callers but not sent: RouterOS rejects
    # "limit-uptime" on /ppp/secret (HTTP 400 unknown parameter).
    try:
        with client_for(server) as c:
            _check(c.put("/ppp/secret", json=body), "create account")
    except httpx.HTTPError as e:
        raise MikrotikError(f"router unreachable: {e.__class__.__name__}") from e


def remove_account(server: VpnServer, username: str) -> None:
    """Delete the account and disconnect its tunnel. Safe to call twice."""
    if not username.startswith(PREFIX):
        raise MikrotikError("refusing to touch a non-FastGame account")
    try:
        with client_for(server) as c:
            # Delete the secret first so the user cannot reconnect, then kick the live tunnel.
            r = c.get("/ppp/secret", params={"name": username})
            _check(r, "look up account")
            for row in r.json():
                _check(c.delete(f"/ppp/secret/{row['.id']}"), "delete account")
            r = c.get("/ppp/active", params={"name": username})
            _check(r, "look up tunnel")
            for row in r.json():
                _check(c.delete(f"/ppp/active/{row['.id']}"), "disconnect tunnel")
    except httpx.HTTPError as e:
        raise MikrotikError(f"router unreachable: {e.__class__.__name__}") from e


def list_accounts(server: VpnServer) -> list[str]:
    """Names of all FastGame accounts currently on the router (for cleaning up leftovers)."""
    try:
        with client_for(server) as c:
            r = c.get("/ppp/secret")
            _check(r, "list accounts")
            return [row["name"] for row in r.json() if str(row.get("name", "")).startswith(PREFIX)]
    except httpx.HTTPError as e:
        raise MikrotikError(f"router unreachable: {e.__class__.__name__}") from e
