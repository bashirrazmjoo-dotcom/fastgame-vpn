import os
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from aiohttp import web

os.environ["FASTGAME_BOT_TOKEN"] = "t" * 40
os.environ["FASTGAME_ADMIN_IDS"] = "111, 222"
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import fastgame_vip as m  # noqa: E402

pytestmark = pytest.mark.asyncio(loop_scope="function")


@pytest.fixture
async def backend(monkeypatch):
    calls = []

    async def get_device(request):
        calls.append(("GET", request.match_info["code"], request.headers.get("X-Bot-Token")))
        if request.match_info["code"] == "NOPE0000":
            return web.json_response({"error": {"code": "device_not_found", "message": "No user with this code."}}, status=404)
        return web.json_response({"user_code": "ABCD-1234", "is_vip": False, "vip_lifetime": False, "vip_expires_at": None, "is_blocked": False})

    async def set_vip(request):
        body = await request.json()
        calls.append(("POST", request.match_info["code"], body))
        return web.json_response({"user_code": "ABCD-1234", "is_vip": True, "vip_lifetime": bool(body.get("lifetime")), "vip_expires_at": "2027-01-01T00:00:00Z", "is_blocked": False})

    app = web.Application()
    app.router.add_get("/bot/devices/{code}", get_device)
    app.router.add_post("/bot/devices/{code}/vip", set_vip)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    monkeypatch.setattr(m, "API_URL", f"http://127.0.0.1:{port}")
    yield calls
    await runner.cleanup()


def make_message(uid=5):
    bot = SimpleNamespace(send_message=AsyncMock())
    user = SimpleNamespace(id=uid, full_name="Ali", username="ali")
    return SimpleNamespace(from_user=user, answer=AsyncMock(), bot=bot)


async def test_start_notifies_admins_with_buttons(backend):
    msg = make_message()
    assert await m.handle_start(msg, "fg_abcd1234") is True
    assert backend[0] == ("GET", "ABCD1234", "t" * 40)
    msg.answer.assert_awaited_once()
    sent = msg.bot.send_message.await_args_list
    assert {c.args[0] for c in sent} == {111, 222}
    markup = sent[0].kwargs["reply_markup"]
    data = [b.callback_data for row in markup.inline_keyboard for b in row]
    assert "fgv:d30:ABCD1234:5" in data and all(len(d) <= 64 for d in data)


async def test_start_ignores_other_payloads(backend):
    msg = make_message()
    assert await m.handle_start(msg, "ref_whatever") is False
    assert await m.handle_start(msg, None) is False
    msg.answer.assert_not_awaited()


async def test_unknown_code_tells_user(backend):
    msg = make_message()
    assert await m.handle_start(msg, "fg_NOPE0000") is True
    msg.bot.send_message.assert_not_awaited()
    assert "پیدا نشد" in msg.answer.await_args.args[0]


def make_query(uid, data):
    message = SimpleNamespace(text="درخواست VIP\nکد: ABCD-1234\n\nپایین", edit_text=AsyncMock())
    bot = SimpleNamespace(send_message=AsyncMock())
    return SimpleNamespace(from_user=SimpleNamespace(id=uid), data=data, message=message, answer=AsyncMock(), bot=bot)


async def test_non_admin_cannot_press(backend):
    q = make_query(999, "fgv:life:ABCD1234:5")
    await m.on_button(q)
    q.answer.assert_awaited_once()
    assert q.answer.await_args.kwargs.get("show_alert") is True
    assert not [c for c in backend if c[0] == "POST"]


async def test_admin_activates_and_user_is_told(backend):
    q = make_query(111, "fgv:d90:ABCD1234:5")
    await m.on_button(q)
    assert ("POST", "ABCD1234", {"action": "activate", "days": 90}) in backend
    q.message.edit_text.assert_awaited_once()
    q.bot.send_message.assert_awaited_once()
    assert q.bot.send_message.await_args.args[0] == 5


async def test_lifetime_and_cancel_bodies(backend):
    await m.on_button(make_query(222, "fgv:life:ABCD1234:5"))
    await m.on_button(make_query(222, "fgv:cancel:ABCD1234:5"))
    posts = [c[2] for c in backend if c[0] == "POST"]
    assert posts == [{"action": "activate", "lifetime": True}, {"action": "cancel"}]


async def test_dismiss_does_nothing_to_backend(backend):
    q = make_query(111, "fgv:skip:ABCD1234:5")
    await m.on_button(q)
    assert not backend
    q.bot.send_message.assert_not_awaited()


async def test_tampered_callback_rejected(backend):
    q = make_query(111, "fgv:life:../../x:5")
    await m.on_button(q)
    assert q.answer.await_args.kwargs.get("show_alert") is True
    assert not backend
