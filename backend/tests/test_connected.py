"""Nothing is charged while the tunnel is being established; only confirmed time counts."""

from app.core.db import SessionLocal
from app.services import sessions as session_service


def test_connecting_time_is_not_charged(api, clock):
    dev = api.register()
    sid = api.start(dev).json()["session_id"]  # tunnel is being established...
    clock.advance(40)
    state = api.connected(dev, sid).json()  # ...and is up 40s later
    assert state["status"] == "active" and state["remaining_seconds"] == 7200

    clock.advance(30)
    assert api.heartbeat(dev, sid).json()["remaining_seconds"] == 7170


def test_heartbeat_before_connected_charges_nothing(api, clock):
    dev = api.register()
    sid = api.start(dev).json()["session_id"]
    clock.advance(30)
    assert api.heartbeat(dev, sid).json()["remaining_seconds"] == 7200
    api.connected(dev, sid)
    clock.advance(30)
    assert api.heartbeat(dev, sid).json()["remaining_seconds"] == 7170


def test_calling_connected_again_cannot_skip_charging(api, clock):
    dev = api.register()
    sid = api.connect(dev)["session_id"]
    clock.advance(30)
    again = api.connected(dev, sid).json()  # behaves like a heartbeat
    assert again["remaining_seconds"] == 7170
    clock.advance(30)
    assert api.connected(dev, sid).json()["remaining_seconds"] == 7140


def test_session_that_never_connects_is_reaped_and_costs_nothing(api, clock):
    dev = api.register()
    sid = api.start(dev).json()["session_id"]
    clock.advance(300)
    with SessionLocal() as db:
        assert session_service.reap_stale_sessions(db) == 1
    assert api.connected(dev, sid).status_code == 409
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7200


def test_stopping_a_pending_session_charges_nothing(api, clock):
    dev = api.register()
    sid = api.start(dev).json()["session_id"]
    clock.advance(20)
    assert api.stop(dev, sid).json()["status"] == "closed"
    assert api.me(dev).json()["quota"]["remaining_seconds"] == 7200


def test_connected_requires_own_session(api, clock):
    a, b = api.register("hardware-id-0001"), api.register("hardware-id-0002")
    sid = api.start(a).json()["session_id"]
    assert api.connected(b, sid).status_code == 404
