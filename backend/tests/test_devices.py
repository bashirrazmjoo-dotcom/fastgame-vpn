from app.core import security


def test_register_creates_unique_codes(api):
    a = api.register("hardware-id-0001")
    b = api.register("hardware-id-0002")
    assert a.code != b.code
    assert len(a.code) == 9 and a.code[4] == "-"


def test_reinstall_keeps_identity_and_quota(api, clock):
    first = api.register("hardware-id-0001")
    sid = api.connect(first)["session_id"]
    clock.advance(30)
    assert api.heartbeat(first, sid).json()["remaining_seconds"] == 7170
    api.stop(first, sid)

    # "Reinstall": same hardware id registers again.
    again = api.register("hardware-id-0001")
    assert again.code == first.code
    assert api.me(again).json()["quota"]["remaining_seconds"] == 7170  # no fresh quota

    # The previous install's token no longer works.
    assert api.me(first).status_code == 401


def test_raw_hardware_id_and_token_are_not_stored(api):
    from sqlalchemy import select

    from app.core.db import SessionLocal
    from app.models import Device

    dev = api.register("hardware-id-secret-xyz")
    with SessionLocal() as db:
        row = db.execute(select(Device)).scalar_one()
    stored = f"{row.fingerprint_hash} {row.token_hash}"
    assert "hardware-id-secret-xyz" not in stored
    assert dev.token not in stored
    assert row.token_hash == security.hash_token(dev.token)


def test_me_requires_valid_token(api, client):
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Bearer nope"}).status_code == 401
    r = client.get("/v1/me", headers={"Authorization": "Basic abc"})
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "invalid_token"


def test_me_initial_state_and_vip_purchase_link(api):
    dev = api.register()
    me = api.me(dev).json()
    assert me["is_vip"] is False and me["is_blocked"] is False
    assert me["quota"]["total_seconds"] == 7200
    assert me["quota"]["remaining_seconds"] == 7200
    assert me["quota"]["resets_at"] is None  # the cycle starts at the first connection
    code = dev.code.replace("-", "")
    assert me["vip_purchase_url"] == f"https://app.razmjou.com/?page=vip&code={code}"


def test_public_config(client):
    cfg = client.get("/v1/config").json()
    assert cfg["free_quota_seconds"] == 7200
    assert cfg["cycle_seconds"] == 86400
    assert cfg["support_telegram_username"] == "fastgamevpn_bot"
    assert cfg["latest_version_code"] == 0
    assert cfg["update_url"].endswith("/FastGameVPN.apk")


def test_servers_list_hides_profile(api):
    dev = api.register()
    servers = api.c.get("/v1/servers", headers=dev.headers).json()
    assert len(servers) == 1
    assert servers[0]["country_code"] == "DE"
    assert "ovpn_config" not in servers[0] and "host" not in servers[0]


def test_register_rate_limit(client, monkeypatch):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "register_limit_per_hour", 3)
    for i in range(3):
        r = client.post(
            "/v1/devices/register",
            json={"hardware_id": f"hardware-id-{i:04d}", "app_version_code": 1},
        )
        assert r.status_code == 200
    r = client.post("/v1/devices/register", json={"hardware_id": "hardware-id-9999", "app_version_code": 1})
    assert r.status_code == 429
    assert r.json()["error"]["code"] == "rate_limited"


def test_register_validation_error_shape(client):
    r = client.post("/v1/devices/register", json={"hardware_id": "x", "app_version_code": 1})
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"
