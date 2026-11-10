"""Xray servers: one 3x-ui client per session, against an in-memory fake of the panel API."""

import json

import httpx
import pytest

from app.core import security
from app.core.db import SessionLocal
from app.models import VpnServer
from app.services import credentials, xui

TEMPLATE = "vless://{uuid}@xray.test:23088?type=tcp&security=tls&fp=chrome&sni=xray.test#FastGame"


class FakePanel:
    def __init__(self) -> None:
        self.clients: dict[str, dict] = {}
        self.logged_in = False
        self.down = False
        self.paths: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("down")
        path = request.url.path.removeprefix("/rad/")
        self.paths.append(path)
        form = dict(x.split("=", 1) for x in request.content.decode().split("&") if "=" in x) if request.content else {}
        if path == "login":
            self.logged_in = True
            return httpx.Response(200, json={"success": True, "msg": "", "obj": None})
        if not self.logged_in:
            return httpx.Response(404)
        if path == "panel/api/inbounds/addClient":
            from urllib.parse import unquote_plus

            assert form["id"] == "12"
            c = json.loads(unquote_plus(form["settings"]))["clients"][0]
            self.clients[c["id"]] = c
            return httpx.Response(200, json={"success": True, "msg": "ok", "obj": None})
        if path.startswith("panel/api/inbounds/12/delClient/"):
            cid = path.rsplit("/", 1)[1]
            if cid not in self.clients:
                return httpx.Response(200, json={"success": False, "msg": "record not found"})
            del self.clients[cid]
            return httpx.Response(200, json={"success": True, "msg": "deleted"})
        return httpx.Response(404)


@pytest.fixture
def panel(monkeypatch, server_id) -> FakePanel:
    fake = FakePanel()
    monkeypatch.setattr(
        xui, "client_for", lambda server: httpx.Client(base_url="https://xray.test/rad/", transport=httpx.MockTransport(fake.handler))
    )
    with SessionLocal() as db:
        s = db.get(VpnServer, server_id)
        s.kind = "xray"
        s.ovpn_config = TEMPLATE
        s.xui_url = "https://xray.test/rad/"
        s.xui_user = "admin"
        s.xui_password_enc = security.encrypt_secret("x")
        s.xui_inbound_id = 12
        s.auth_username = s.auth_password_enc = None
        db.commit()
    return fake


def test_session_gets_its_own_client_and_link(api, panel):
    dev = api.register()
    body = api.connect(dev)
    assert body["kind"] == "xray" and body["ovpn_config"] == ""
    cid = xui.client_id(body["session_id"])
    assert body["xray_link"] == TEMPLATE.replace("{uuid}", cid)
    client = panel.clients[cid]
    assert client["email"].startswith("fg-") and client["enable"] is True
    assert client["expiryTime"] > 0  # free plan: the panel expires it by itself too


def test_vip_client_has_no_expiry(api, panel, admin_headers, client):
    dev = api.register()
    r = client.post(f"/admin/devices/{dev.code}/vip", json={"action": "activate", "lifetime": True}, headers=admin_headers)
    assert r.status_code == 200, r.text
    body = api.start(dev).json()
    assert panel.clients[xui.client_id(body["session_id"])]["expiryTime"] == 0


def test_stop_removes_client(api, panel):
    dev = api.register()
    body = api.connect(dev)
    assert api.stop(dev, body["session_id"]).status_code == 200
    assert panel.clients == {}


def test_panel_down_gives_503_and_no_charge(api, panel):
    panel.down = True
    dev = api.register()
    r = api.start(dev)
    assert r.status_code == 503
    panel.down = False
    assert api.connect(dev)["kind"] == "xray"  # the failed attempt did not block a retry


def test_remove_twice_is_fine(api, panel):
    dev = api.register()
    body = api.connect(dev)
    with SessionLocal() as db:
        server = db.get(VpnServer, body["server_id"])
        xui.remove_client(server, body["session_id"])
        xui.remove_client(server, body["session_id"])  # already gone: no error


def test_admin_can_create_xray_server(client, admin_headers, monkeypatch):
    body = {
        "kind": "xray", "name": "tr-1", "country_code": "TR", "country_name_en": "Turkey", "country_name_fa": "ترکیه",
        "ovpn_config": TEMPLATE,
        "xui": {"url": "https://xray.test:8443/rad/", "username": "admin", "password": "secret", "inbound_id": 12},
    }
    r = client.post("/admin/servers", json=body, headers=admin_headers)
    assert r.status_code == 201, r.text
    out = r.json()
    assert out["kind"] == "xray" and out["host"] == "xray.test" and out["port"] == 23088
    assert out["xui"] == {"url": "https://xray.test:8443/rad/", "username": "admin", "inbound_id": 12}
    assert "secret" not in r.text
    bad = client.post("/admin/servers", json={**body, "ovpn_config": "vless://abc@x:1"}, headers=admin_headers)
    assert bad.status_code == 422
