"""Per-session router accounts, against an in-memory fake of the RouterOS REST API."""

import json

import httpx
import pytest

from app.core import security
from app.core.db import SessionLocal
from app.models import VpnServer
from app.services import credentials, mikrotik


class FakeRouter:
    def __init__(self) -> None:
        self.secrets: dict[str, dict] = {}
        self.active: set[str] = set()
        self.down = False
        self.next_id = 1
        self.requests: list[tuple[str, str]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("down")
        path = request.url.path.removeprefix("/rest")
        self.requests.append((request.method, path))
        assert request.headers["authorization"].startswith("Basic ")
        if request.method == "PUT" and path == "/ppp/secret":
            body = json.loads(request.content)
            if body["name"] in self.secrets:
                return httpx.Response(400, json={"error": 400, "message": "Bad Request", "detail": "already have such name"})
            row = {**body, ".id": f"*{self.next_id}"}
            self.next_id += 1
            self.secrets[body["name"]] = row
            self.active.add(body["name"])
            return httpx.Response(201, json=row)
        if request.method == "GET" and path == "/ppp/secret":
            name = request.url.params.get("name")
            return httpx.Response(200, json=[r for n, r in self.secrets.items() if name in (None, n)])
        if request.method == "DELETE" and path.startswith("/ppp/secret/"):
            rid = path.rsplit("/", 1)[1]
            for n, r in list(self.secrets.items()):
                if r[".id"] == rid:
                    del self.secrets[n]
            return httpx.Response(204)
        if request.method == "GET" and path == "/ppp/active":
            name = request.url.params.get("name")
            return httpx.Response(200, json=[{".id": f"*A{n}", "name": n} for n in self.active if n == name])
        if request.method == "DELETE" and path.startswith("/ppp/active/"):
            self.active.discard(path.rsplit("/*A", 1)[1])
            return httpx.Response(204)
        return httpx.Response(404, json={"error": 404})


@pytest.fixture
def router(monkeypatch, server_id) -> FakeRouter:
    fake = FakeRouter()

    def make(server: VpnServer) -> httpx.Client:
        return httpx.Client(
            base_url="https://router.test/rest", auth=("fg-backend", "x"), transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(mikrotik, "client_for", make)
    with SessionLocal() as db:
        server = db.get(VpnServer, server_id)
        server.mt_api_url = "https://router.test"
        server.mt_api_user = "fg-backend"
        server.mt_api_password_enc = security.encrypt_secret("x")
        server.mt_ppp_profile = "fg-ovpn"
        server.auth_username = None
        server.auth_password_enc = None
        db.commit()
    return fake


def test_each_session_gets_its_own_account(api, router):
    dev = api.register()
    body = api.connect(dev)
    assert body["auth_username"].startswith("fg-")
    assert body["auth_password"] and len(body["auth_password"]) == 32
    row = router.secrets[body["auth_username"]]
    assert row["password"] == body["auth_password"]
    assert row["service"] == "ovpn" and row["profile"] == "fg-ovpn"
    assert "limit-uptime" not in row  # RouterOS rejects it on /ppp/secret
    assert (body["auth_username"], body["auth_password"]) == security.session_credentials(body["session_id"])


def test_vip_account_has_no_uptime_limit(api, router, admin_headers, client):
    dev = api.register()
    r = client.post(f"/admin/devices/{dev.code}/vip", json={"action": "activate", "lifetime": True}, headers=admin_headers)
    assert r.status_code == 200, r.text
    body = api.start(dev).json()
    assert "limit-uptime" not in router.secrets[body["auth_username"]]


def test_stop_removes_account_and_kicks_tunnel(api, router):
    dev = api.register()
    body = api.connect(dev)
    assert api.stop(dev, body["session_id"]).status_code == 200
    assert router.secrets == {}
    assert router.active == set()


def test_quota_exhaustion_removes_account(api, router, clock):
    dev = api.register()
    body = api.connect(dev)
    for _ in range(300):
        clock.advance(30)
        r = api.heartbeat(dev, body["session_id"])
        if r.json()["status"] != "active":
            break
    assert r.json()["status"] == "quota_exhausted"
    assert router.secrets == {}


def test_router_down_blocks_connection_without_charge(api, router):
    dev = api.register()
    router.down = True
    r = api.start(dev)
    assert r.status_code == 503 and r.json()["error"]["code"] == "server_unavailable"
    assert router.secrets == {}
    assert api.me(dev).json()["quota"]["used_seconds"] == 0
    router.down = False
    assert api.start(dev).status_code == 201  # not stuck on a half-open session


def test_failed_revoke_is_retried_by_sweeper(api, router):
    dev = api.register()
    body = api.connect(dev)
    router.down = True
    assert api.stop(dev, body["session_id"]).status_code == 200  # user is not affected
    assert body["auth_username"] in router.secrets
    router.down = False
    with SessionLocal() as db:
        assert credentials.sweep(db) == 1
    assert router.secrets == {}
    with SessionLocal() as db:
        assert credentials.sweep(db) == 0


def test_timeout_reaper_then_sweeper_removes_account(api, router, clock):
    from app.services import sessions

    dev = api.register()
    body = api.connect(dev)
    clock.advance(300)
    with SessionLocal() as db:
        assert sessions.reap_stale_sessions(db) == 1
        assert credentials.sweep(db) == 1
    assert router.secrets == {}


def test_blocking_a_user_cuts_the_tunnel(api, router, admin_headers, client):
    dev = api.register()
    body = api.connect(dev)
    r = client.post(f"/admin/devices/{dev.code}/block", json={"blocked": True, "reason": "abuse"}, headers=admin_headers)
    assert r.status_code == 200
    with SessionLocal() as db:
        credentials.sweep(db)
    assert body["auth_username"] not in router.secrets


def test_only_fastgame_accounts_can_be_removed(router, server_id):
    with SessionLocal() as db:
        server = db.get(VpnServer, server_id)
        with pytest.raises(mikrotik.MikrotikError):
            mikrotik.remove_account(server, "admin")


def test_duration_format():
    assert mikrotik.routeros_duration(7260) == "2h1m0s"
    assert mikrotik.routeros_duration(59) == "0h0m59s"


def test_admin_configures_mikrotik_without_leaking_password(client, admin_headers, server_id):
    body = {
        "name": "Frankfurt 1", "country_code": "DE", "country_name_en": "Germany", "country_name_fa": "آلمان",
        "ovpn_config": "client\nremote de1.example.test 1194 udp\n",
        "mikrotik": {"api_url": "https://203.0.113.5:8443", "username": "fg-backend", "password": "s3cret-pw", "ppp_profile": "fg-ovpn"},
    }
    r = client.put(f"/admin/servers/{server_id}", json=body, headers=admin_headers)
    assert r.status_code == 200, r.text
    assert r.json()["mikrotik"] == {"api_url": "https://203.0.113.5:8443", "username": "fg-backend", "ppp_profile": "fg-ovpn", "has_ca": False}
    assert "s3cret-pw" not in r.text
    # Updating without a password keeps the stored one.
    body["mikrotik"].pop("password")
    assert client.put(f"/admin/servers/{server_id}", json=body, headers=admin_headers).status_code == 200
    with SessionLocal() as db:
        assert security.decrypt_secret(db.get(VpnServer, server_id).mt_api_password_enc) == "s3cret-pw"
    # Plain http is refused.
    body["mikrotik"]["api_url"] = "http://203.0.113.5"
    assert client.put(f"/admin/servers/{server_id}", json=body, headers=admin_headers).status_code == 422
