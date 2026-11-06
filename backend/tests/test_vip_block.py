from datetime import datetime, timedelta


def vip(client, headers, code, **body):
    return client.post(f"/admin/devices/{code}/vip", json=body, headers=headers)


def test_vip_has_no_time_limit_and_does_not_use_quota(api, client, clock, admin_headers):
    dev = api.register()
    r = vip(client, admin_headers, dev.code, action="activate", days=30)
    assert r.status_code == 200 and r.json()["is_vip"] is True

    me = api.me(dev).json()
    assert me["is_vip"] is True and me["vip_lifetime"] is False
    started = api.connect(dev)
    assert started["is_vip"] is True and started["remaining_seconds"] is None

    sid = started["session_id"]
    for _ in range(300):  # 2.5 hours, longer than the free quota
        clock.advance(30)
        hb = api.heartbeat(dev, sid).json()
        assert hb["status"] == "active" and hb["remaining_seconds"] is None
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7200  # free quota untouched


def test_vip_extend_and_cancel(api, client, clock, admin_headers):
    dev = api.register()
    first = vip(client, admin_headers, dev.code, action="activate", days=10).json()
    end1 = datetime.fromisoformat(first["vip_expires_at"])
    assert end1 == clock.now + timedelta(days=10)

    second = vip(client, admin_headers, dev.code, action="extend", days=5).json()
    assert datetime.fromisoformat(second["vip_expires_at"]) == end1 + timedelta(days=5)

    cancelled = vip(client, admin_headers, dev.code, action="cancel").json()
    assert cancelled["is_vip"] is False and cancelled["vip_expires_at"] is None


def test_vip_expires_by_itself(api, client, clock, admin_headers):
    dev = api.register()
    vip(client, admin_headers, dev.code, action="activate", days=1)
    assert api.me(dev).json()["is_vip"] is True
    clock.advance(86400 + 1)
    me = api.me(dev).json()
    assert me["is_vip"] is False
    assert api.start(dev).json()["remaining_seconds"] == 7200  # back to the free plan


def test_vip_lifetime_and_validation(api, client, admin_headers):
    dev = api.register()
    r = vip(client, admin_headers, dev.code, action="activate", lifetime=True)
    assert r.json()["is_vip"] is True and r.json()["vip_lifetime"] is True
    assert vip(client, admin_headers, dev.code, action="extend", days=3).status_code == 409

    other = api.register("hardware-id-0002")
    assert vip(client, admin_headers, other.code, action="activate").status_code == 422
    assert vip(client, admin_headers, other.code, action="extend", lifetime=True, days=1).status_code == 422


def test_vip_changes_are_audited(api, client, admin_headers):
    dev = api.register()
    vip(client, admin_headers, dev.code, action="activate", days=7)
    vip(client, admin_headers, dev.code, action="cancel")
    detail = client.get(f"/admin/devices/{dev.code}", headers=admin_headers).json()
    assert [e["action"] for e in detail["vip_events"]] == ["cancel", "activate"]
    assert detail["vip_events"][0]["admin_username"] == "admin"


def test_blocked_device_cannot_connect_even_after_reinstall(api, client, admin_headers):
    dev = api.register("hardware-id-0001")
    r = client.post(
        f"/admin/devices/{dev.code}/block", json={"blocked": True, "reason": "abuse"}, headers=admin_headers
    )
    assert r.status_code == 200 and r.json()["is_blocked"] is True

    denied = api.start(dev)
    assert denied.status_code == 403 and denied.json()["error"]["code"] == "device_blocked"
    assert api.me(dev).json()["is_blocked"] is True

    reinstalled = api.register("hardware-id-0001")  # same device, same block
    assert reinstalled.code == dev.code
    assert api.start(reinstalled).status_code == 403


def test_blocking_disconnects_active_session_and_unblock_restores(api, client, clock, admin_headers):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(30)
    client.post(f"/admin/devices/{dev.code}/block", json={"blocked": True}, headers=admin_headers)

    r = api.heartbeat(dev, sid)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "blocked"

    client.post(f"/admin/devices/{dev.code}/block", json={"blocked": False}, headers=admin_headers)
    assert api.start(dev).status_code == 201
