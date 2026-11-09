"""Free quota: 120 minutes per 24h cycle, counted by the server clock only."""

from app.core.db import SessionLocal
from app.services import sessions as session_service


def test_start_releases_profile_and_credentials(api):
    dev = api.register()
    r = api.start(dev)
    assert r.status_code == 201
    body = r.json()
    assert body["remaining_seconds"] == 7200 and body["is_vip"] is False
    assert "remote de1.example.test" in body["ovpn_config"]
    assert body["auth_username"] == "vpnuser" and body["auth_password"] == "vpnpass"
    assert body["heartbeat_interval_seconds"] == 30


def test_time_counts_only_while_connected(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]

    clock.advance(30)
    hb = api.heartbeat(dev, sid).json()
    assert hb["status"] == "active" and hb["remaining_seconds"] == 7170

    api.stop(dev, sid)
    clock.advance(3 * 3600)  # three hours disconnected: nothing is charged
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7170

    # Reconnecting resumes from the saved remaining time.
    assert api.start(dev).json()["remaining_seconds"] == 7170


def test_gap_between_heartbeats_is_capped(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(100)  # app was unreachable for 100s: only the 45s grace is credited
    assert api.heartbeat(dev, sid).json()["remaining_seconds"] == 7155


def test_quota_exhaustion_blocks_until_cycle_ends(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]

    for _ in range(239):
        clock.advance(30)
        assert api.heartbeat(dev, sid).json()["status"] == "active"
    clock.advance(30)
    last = api.heartbeat(dev, sid).json()
    assert last["status"] == "quota_exhausted" and last["remaining_seconds"] == 0

    # The server closed the session, and a new one is refused with the reset time.
    assert api.heartbeat(dev, sid).status_code == 409
    refused = api.start(dev)
    assert refused.status_code == 403
    err = refused.json()["error"]
    assert err["code"] == "quota_exhausted" and err["details"]["resets_at"]

    me = api.me(dev).json()
    assert me["quota"]["remaining_seconds"] == 0 and me["quota"]["resets_at"]

    # Still inside the 24h cycle one second before it ends...
    clock.advance(86400 - 7200 - 1)
    assert api.start(dev).status_code == 403
    # ...and fully recharged once it is over.
    clock.advance(2)
    again = api.start(dev)
    assert again.status_code == 201 and again.json()["remaining_seconds"] == 7200


def test_idle_cycle_does_not_leak_into_next_one(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(30)
    api.heartbeat(dev, sid)
    api.stop(dev, sid)
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7170

    clock.advance(2 * 86400)  # two days later the quota is full again
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7200
    assert api.start(dev).json()["remaining_seconds"] == 7200


def test_only_one_active_session_per_device(api, clock):
    dev = api.register()
    first = api.connect(dev)["session_id"]
    clock.advance(20)
    second = api.connect(dev)["session_id"]
    assert first != second

    r = api.heartbeat(dev, first)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "replaced"
    # The 20s spent on the first session was credited before it was replaced.
    assert api.heartbeat(dev, second).json()["remaining_seconds"] == 7180


def test_stop_is_idempotent_and_session_is_private(api, clock):
    a = api.register("hardware-id-0001")
    b = api.register("hardware-id-0002")
    sid = api.connect(a)["session_id"]
    clock.advance(10)
    assert api.stop(a, sid).json()["status"] == "closed"
    assert api.stop(a, sid).status_code == 200
    assert api.stop(b, sid).status_code == 404  # another device cannot touch it


def test_reaper_closes_sessions_without_heartbeats(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(30)
    api.heartbeat(dev, sid)
    clock.advance(301)  # the app died

    with SessionLocal() as db:
        assert session_service.reap_stale_sessions(db) == 1
    assert api.heartbeat(dev, sid).status_code == 409
    # Nothing beyond the last heartbeat was charged.
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7170


def test_client_cannot_supply_time(api, clock):
    """Extra fields (e.g. a fake elapsed time) are ignored: only the server clock counts."""
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    r = api.c.post(
        f"/v1/sessions/{sid}/heartbeat",
        json={"elapsed_seconds": -99999, "now": "2000-01-01T00:00:00Z"},
        headers=dev.headers,
    )
    assert r.status_code == 200 and r.json()["remaining_seconds"] == 7200
