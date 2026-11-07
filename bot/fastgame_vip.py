"""FastGame VPN: VIP activation from the Telegram bot (aiogram 3).

Flow
  1. The app's "Buy VIP" button opens  t.me/<bot>?start=fg_<USERCODE>.
  2. The user sees a confirmation; every admin gets a message with buttons
     (30 / 90 / 365 days, lifetime, cancel VIP, dismiss).
  3. An admin taps a button -> the FastGame backend changes VIP -> the user is notified.

It talks to the backend on the same server (default http://127.0.0.1:18080) with a shared token.

Environment (read when this module is imported)
  FASTGAME_API_URL     backend base URL            (default http://127.0.0.1:18080)
  FASTGAME_BOT_TOKEN   must equal FG_BOT_API_TOKEN in the backend's .env (>= 32 characters)
  FASTGAME_ADMIN_IDS   comma separated Telegram user ids allowed to press the buttons

Wiring (two lines in the existing bot, nothing else is replaced):
    import fastgame_vip
    dp.include_router(fastgame_vip.router)      # include it BEFORE the router that handles /start
If the existing /start handler lives directly on `dp` and wins, call this at the top of it instead:
    if await fastgame_vip.handle_start(message, command.args): return
"""

import logging
import os
import re
from datetime import datetime

import aiohttp
from aiogram import F, Router
from aiogram.filters import CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

log = logging.getLogger("fastgame_vip")
router = Router(name="fastgame_vip")

API_URL = os.getenv("FASTGAME_API_URL", "http://127.0.0.1:18080").rstrip("/")
API_TOKEN = os.getenv("FASTGAME_BOT_TOKEN", "")
ADMIN_IDS = {int(x) for x in os.getenv("FASTGAME_ADMIN_IDS", "").replace(" ", "").split(",") if x.isdigit()}

CODE_RE = re.compile(r"^fg_([A-Za-z0-9]{8})$")
CALLBACK_RE = re.compile(r"^fgv:(d30|d90|d365|life|cancel|skip):([A-Za-z0-9]{8}):(\d{1,15})$")
ACTIONS = {
    "d30": ({"action": "activate", "days": 30}, "VIP ۳۰ روزه"),
    "d90": ({"action": "activate", "days": 90}, "VIP ۹۰ روزه"),
    "d365": ({"action": "activate", "days": 365}, "VIP ۳۶۵ روزه"),
    "life": ({"action": "activate", "lifetime": True}, "VIP دائمی"),
    "cancel": ({"action": "cancel"}, "لغو VIP"),
}


class BackendError(Exception):
    pass


async def _call(method: str, path: str, json: dict | None = None) -> dict:
    if not API_TOKEN:
        raise BackendError("FASTGAME_BOT_TOKEN تنظیم نشده است")
    timeout = aiohttp.ClientTimeout(total=10)
    try:
        async with aiohttp.ClientSession(timeout=timeout) as http:
            async with http.request(method, API_URL + path, json=json, headers={"X-Bot-Token": API_TOKEN}) as r:
                data = await r.json(content_type=None)
                if r.status >= 400:
                    message = (data or {}).get("error", {}).get("message") if isinstance(data, dict) else None
                    raise BackendError(message or f"HTTP {r.status}")
                return data
    except aiohttp.ClientError as e:
        raise BackendError(f"سرور FastGame در دسترس نیست ({e.__class__.__name__})") from e


def _status_text(info: dict) -> str:
    if info.get("is_blocked"):
        return "مسدود"
    if info.get("vip_lifetime"):
        return "VIP دائمی"
    if info.get("is_vip"):
        until = info.get("vip_expires_at") or ""
        try:
            until = datetime.fromisoformat(until.replace("Z", "+00:00")).strftime("%Y-%m-%d")
        except ValueError:
            pass
        return f"VIP تا {until}"
    return "رایگان"


def _keyboard(code: str, uid: int) -> InlineKeyboardMarkup:
    def b(label: str, key: str) -> InlineKeyboardButton:
        return InlineKeyboardButton(text=label, callback_data=f"fgv:{key}:{code}:{uid}")

    return InlineKeyboardMarkup(
        inline_keyboard=[
            [b("۳۰ روز", "d30"), b("۹۰ روز", "d90")],
            [b("۳۶۵ روز", "d365"), b("دائمی", "life")],
            [b("لغو VIP", "cancel"), b("رد", "skip")],
        ]
    )


async def handle_start(message: Message, args: str | None) -> bool:
    """Handles ``/start fg_<CODE>``. Returns True when the message was ours."""
    match = CODE_RE.match(args or "")
    if not match:
        return False
    code = match.group(1).upper()
    user = message.from_user
    try:
        info = await _call("GET", f"/bot/devices/{code}")
    except BackendError as e:
        log.warning("fastgame lookup failed for %s: %s", code, e)
        await message.answer(
            "کد کاربری پیدا نشد یا سرور در دسترس نیست. لطفاً از داخل برنامه دوباره امتحان کنید.", parse_mode=None
        )
        return True

    shown = info["user_code"]
    await message.answer(
        f"درخواست VIP برای کد {shown} ثبت شد ✅\nبعد از تأیید ادمین، همین‌جا خبر می‌دهیم.",
        parse_mode=None,
    )
    if not ADMIN_IDS:
        log.warning("FASTGAME_ADMIN_IDS is empty: nobody can approve VIP requests")
        return True
    who = f"{user.full_name}" + (f" (@{user.username})" if user.username else "") + f" — id {user.id}"
    text = f"درخواست VIP\nکد: {shown}\nوضعیت فعلی: {_status_text(info)}\nاز: {who}"
    for admin_id in ADMIN_IDS:
        try:
            await message.bot.send_message(admin_id, text, reply_markup=_keyboard(code, user.id), parse_mode=None)
        except Exception:  # an admin who never started the bot must not block the others
            log.exception("could not notify admin %s", admin_id)
    return True


@router.message(CommandStart(deep_link=True))
async def on_start(message: Message, command) -> None:
    if not await handle_start(message, command.args):
        # Not one of ours: let the bot's own /start handlers run.
        from aiogram.dispatcher.event.bases import SkipHandler

        raise SkipHandler()


@router.callback_query(F.data.startswith("fgv:"))
async def on_button(query: CallbackQuery) -> None:
    if query.from_user.id not in ADMIN_IDS:
        await query.answer("دسترسی ندارید", show_alert=True)
        return
    match = CALLBACK_RE.match(query.data or "")
    if not match:
        await query.answer("دکمه نامعتبر است", show_alert=True)
        return
    key, code, uid = match.group(1), match.group(2), int(match.group(3))

    if key == "skip":
        await query.message.edit_text((query.message.text or "") + "\n\nرد شد ❌", parse_mode=None)
        await query.answer()
        return

    body, label = ACTIONS[key]
    try:
        info = await _call("POST", f"/bot/devices/{code}/vip", json=body)
    except BackendError as e:
        await query.answer(f"انجام نشد: {e}", show_alert=True)
        return

    status = _status_text(info)
    await query.message.edit_text(
        f"{(query.message.text or '').split(chr(10) + chr(10))[0]}\n\n{label} ✅\nوضعیت جدید: {status}",
        parse_mode=None,
    )
    await query.answer("انجام شد")
    note = "VIP شما لغو شد." if key == "cancel" else f"VIP شما فعال شد ✅\nوضعیت: {status}\nبرنامه را باز کنید؛ زمان نامحدود است."
    try:
        await query.bot.send_message(uid, note, parse_mode=None)
    except Exception:
        log.exception("could not notify user %s", uid)
