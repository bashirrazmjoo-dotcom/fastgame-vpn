"""The Telegram bot's VIP endpoints."""

import pytest

from app.core.config import get_settings

TOKEN = "t" * 40
HEADERS = {"X-Bot-Token": TOKEN}


@pytest.fixture
def bot_enabled(monkeypatch):
    monkeypatch.setattr(get_settings(), "bot_api_token", TOKEN)


def test_disabled_without_token(client, api):
    dev = api.register()
    assert client.get(f"/bot/devices/{dev.code}", headers=HEADERS).status_code == 404


def test_rejects_wrong_or_missing_token(client, api, bot_enabled):
    dev = api.register()
    assert client.get(f"/bot/devices/{dev.code}").status_code == 401
    assert client.get(f"/bot/devices/{dev.code}", headers={"X-Bot-Token": "x" * 40}).status_code == 401


def test_short_token_counts_as_disabled(client, api, monkeypatch):
    monkeypatch.setattr(get_settings(), "bot_api_token", "short")
    dev = api.register()
    assert client.get(f"/bot/devices/{dev.code}", headers={"X-Bot-Token": "short"}).status_code == 404


def test_unknown_code(client, bot_enabled):
    assert client.get("/bot/devices/ZZZZ-ZZZZ", headers=HEADERS).status_code == 404


def test_activate_extend_cancel(client, api, bot_enabled):
    dev = api.register()
    body = client.get(f"/bot/devices/{dev.code}", headers=HEADERS).json()
    assert body["is_vip"] is False

    r = client.post(f"/bot/devices/{dev.code}/vip", json={"action": "activate", "days": 30}, headers=HEADERS)
    assert r.status_code == 200, r.text
    assert r.json()["is_vip"] is True and r.json()["vip_lifetime"] is False
    assert api.me(dev).json()["is_vip"] is True

    r = client.post(f"/bot/devices/{dev.code}/vip", json={"action": "activate", "lifetime": True}, headers=HEADERS)
    assert r.json()["vip_lifetime"] is True

    r = client.post(f"/bot/devices/{dev.code}/vip", json={"action": "cancel"}, headers=HEADERS)
    assert r.json()["is_vip"] is False
    assert api.me(dev).json()["is_vip"] is False


def test_code_may_be_sent_without_dash(client, api, bot_enabled):
    dev = api.register()
    raw = dev.code.replace("-", "").lower()
    assert client.get(f"/bot/devices/{raw}", headers=HEADERS).status_code == 200
