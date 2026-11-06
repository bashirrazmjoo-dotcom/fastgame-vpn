import pytest

GOOD_OVPN = "client\ndev tun\nproto udp\nremote de2.example.test 1194\n"


def server_body(**overrides):
    body = {
        "name": "Frankfurt 2",
        "country_code": "de",
        "country_name_en": "Germany",
        "country_name_fa": "آلمان",
        "host": "de2.example.test",
        "port": 1194,
        "proto": "udp",
        "ovpn_config": GOOD_OVPN,
    }
    body.update(overrides)
    return body


@pytest.mark.parametrize(
    "method,path",
    [
        ("get", "/admin/devices"),
        ("get", "/admin/servers"),
        ("get", "/admin/stats"),
        ("get", "/admin/sessions/active"),
        ("get", "/admin/auth/me"),
    ],
)
def test_admin_endpoints_require_a_token(client, method, path):
    r = getattr(client, method)(path)
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


def test_device_token_is_not_an_admin_token(api, client):
    dev = api.register()
    assert client.get("/admin/devices", headers=dev.headers).status_code == 401


def test_login(client, admin_headers):
    assert client.get("/admin/auth/me", headers=admin_headers).json() == {"username": "admin"}
    bad = client.post("/admin/auth/login", json={"username": "admin", "password": "wrong"})
    assert bad.status_code == 401 and bad.json()["error"]["code"] == "invalid_credentials"
    ghost = client.post("/admin/auth/login", json={"username": "nobody", "password": "wrong"})
    assert ghost.status_code == 401


def test_login_rate_limit(client):
    for _ in range(10):
        assert client.post("/admin/auth/login", json={"username": "a", "password": "b"}).status_code == 401
    assert client.post("/admin/auth/login", json={"username": "a", "password": "b"}).status_code == 429


def test_search_filter_and_paginate_users(api, client, admin_headers):
    devs = [api.register(f"hardware-id-{i:04d}") for i in range(5)]
    api_vip = devs[0]
    client.post(f"/admin/devices/{api_vip.code}/vip", json={"action": "activate", "days": 5}, headers=admin_headers)
    client.post(f"/admin/devices/{devs[1].code}/block", json={"blocked": True}, headers=admin_headers)

    everyone = client.get("/admin/devices", headers=admin_headers).json()
    assert everyone["total"] == 5 and len(everyone["items"]) == 5

    one = client.get("/admin/devices", params={"q": devs[2].code}, headers=admin_headers).json()
    assert [d["user_code"] for d in one["items"]] == [devs[2].code]
    # Search works without the dash and in lower case.
    loose = client.get("/admin/devices", params={"q": devs[2].code.replace("-", "").lower()}, headers=admin_headers)
    assert loose.json()["total"] == 1

    assert client.get("/admin/devices", params={"status": "vip"}, headers=admin_headers).json()["total"] == 1
    assert client.get("/admin/devices", params={"status": "blocked"}, headers=admin_headers).json()["total"] == 1
    assert client.get("/admin/devices", params={"status": "free"}, headers=admin_headers).json()["total"] == 3

    page = client.get("/admin/devices", params={"limit": 2, "offset": 4}, headers=admin_headers).json()
    assert page["total"] == 5 and len(page["items"]) == 1


def test_usage_and_session_history_visible(api, client, clock, admin_headers):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(30)
    api.heartbeat(dev, sid)

    live = client.get("/admin/sessions/active", headers=admin_headers).json()
    assert len(live) == 1 and live[0]["user_code"] == dev.code and live[0]["server_name"] == "Frankfurt 1"
    assert live[0]["counted_seconds"] == 30

    api.stop(dev, sid)
    detail = client.get(f"/admin/devices/{dev.code}", headers=admin_headers).json()
    assert detail["total_used_seconds"] == 30 and detail["has_active_session"] is False
    assert detail["sessions"][0]["counted_seconds"] == 30 and detail["sessions"][0]["end_reason"] == "user"
    assert client.get("/admin/devices/ZZZZ-ZZZZ", headers=admin_headers).status_code == 404


def test_reset_quota(api, client, clock, admin_headers):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(40)
    api.heartbeat(dev, sid)
    api.stop(dev, sid)
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7160
    r = client.post(f"/admin/devices/{dev.code}/reset-quota", headers=admin_headers)
    assert r.json()["quota"]["remaining_seconds"] == 7200


def test_stats(api, client, clock, admin_headers):
    a, b = api.register("hardware-id-0001"), api.register("hardware-id-0002")
    client.post(f"/admin/devices/{b.code}/vip", json={"action": "activate", "days": 3}, headers=admin_headers)
    sid = api.connect(a)["session_id"]
    clock.advance(30)
    api.heartbeat(a, sid)
    stats = client.get("/admin/stats", headers=admin_headers).json()
    assert stats["total_devices"] == 2 and stats["vip_devices"] == 1 and stats["blocked_devices"] == 0
    assert stats["active_sessions"] == 1 and stats["sessions_started_24h"] == 1
    assert stats["usage_seconds_24h"] == 30 and stats["new_devices_24h"] == 2


def test_server_crud_and_secret_handling(client, admin_headers):
    created = client.post(
        "/admin/servers",
        json=server_body(auth_username="u1", auth_password="s3cret"),
        headers=admin_headers,
    )
    assert created.status_code == 201
    s = created.json()
    assert s["country_code"] == "DE" and s["has_credentials"] is True
    assert "s3cret" not in created.text and "auth_password" not in s

    # Updating without a password keeps the stored one.
    upd = client.put(f"/admin/servers/{s['id']}", json=server_body(name="Frankfurt 2b", auth_username="u1"), headers=admin_headers)
    assert upd.status_code == 200 and upd.json()["name"] == "Frankfurt 2b" and upd.json()["has_credentials"] is True

    cleared = client.put(
        f"/admin/servers/{s['id']}", json=server_body(clear_credentials=True), headers=admin_headers
    )
    assert cleared.json()["has_credentials"] is False

    listing = client.get("/admin/servers", headers=admin_headers).json()
    assert len(listing) == 1 and listing[0]["active_connections"] == 0

    assert client.delete(f"/admin/servers/{s['id']}", headers=admin_headers).status_code == 204
    assert client.delete(f"/admin/servers/{s['id']}", headers=admin_headers).status_code == 404


@pytest.mark.parametrize(
    "bad_config",
    [
        "client\nremote a.example.test 1194\nup /tmp/evil.sh\n",
        "client\nremote a.example.test 1194\nscript-security 2\n",
        "client\nremote a.example.test 1194\nplugin /lib/x.so\n",
        "client\ndev tun\nproto udp\n",  # no remote line
    ],
)
def test_dangerous_or_incomplete_profiles_are_rejected(client, admin_headers, bad_config):
    r = client.post("/admin/servers", json=server_body(ovpn_config=bad_config), headers=admin_headers)
    assert r.status_code == 422


def test_inactive_server_is_not_offered_or_connectable(api, client, admin_headers, server_id):
    dev = api.register()
    body = server_body(is_active=False)
    client.put(f"/admin/servers/{server_id}", json=body, headers=admin_headers)
    assert api.c.get("/v1/servers", headers=dev.headers).json() == []
    r = api.start(dev)
    assert r.status_code == 404 and r.json()["error"]["code"] == "server_unavailable"


def test_server_with_history_cannot_be_deleted(api, client, clock, admin_headers, server_id):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    listing = client.get("/admin/servers", headers=admin_headers).json()
    assert listing[0]["active_connections"] == 1
    api.stop(dev, sid)
    r = client.delete(f"/admin/servers/{server_id}", headers=admin_headers)
    assert r.status_code == 409 and r.json()["error"]["code"] == "server_has_sessions"
