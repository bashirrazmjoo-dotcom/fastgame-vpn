# فعال‌سازی VIP از ربات vpnbot (`@fastgamevpn_bot`)

فقط برای ربات **vpnbot** (`/opt/vpnbot`)، نه wizwiz. هیچ فایل موجود ربات بازنویسی نمی‌شود؛
یک ماژول جدید اضافه می‌شود.

جریان: کاربر در برنامه «خرید VIP» را می‌زند ← ربات `?start=fg_<کد>` را می‌گیرد ← به ادمین پیام با دکمه‌های
۳۰/۹۰/۳۶۵ روز، دائمی، لغو و رد می‌رود ← با زدن دکمه، VIP در بک‌اند عوض می‌شود و کاربر پیام می‌گیرد.

## ۱) بک‌اند (`/opt/fastgame/.env`)
```
FG_BOT_API_TOKEN=<یک رشته‌ی تصادفی ≥ ۳۲ کاراکتر>   # openssl rand -hex 32
```
بعد: `systemctl restart fastgame`. آدرس‌های `/bot/*` بدون این متغیر غیرفعال‌اند (404).

## ۲) ربات (`/opt/vpnbot`)
1. فایل `fastgame_vip.py` را کنار `bot.py` کپی کنید.
2. در `.env` ربات (یا هر جایی که متغیرهای ربات را می‌دهید) این سه خط را اضافه کنید:
```
FASTGAME_API_URL=http://127.0.0.1:18080
FASTGAME_BOT_TOKEN=<همان توکن مرحله ۱>
FASTGAME_ADMIN_IDS=<آیدی عددی تلگرام شما>[,<ادمین دیگر>]
```
3. در `bot.py` فقط دو خط اضافه کنید (قبل از روتری که `/start` را مدیریت می‌کند):
```python
import fastgame_vip
dp.include_router(fastgame_vip.router)
```
اگر `/start` فعلی مستقیم روی `dp` تعریف شده و همان اول می‌گیرد، به‌جای آن در ابتدای همان handler بگذارید:
```python
if await fastgame_vip.handle_start(message, command.args):
    return
```
4. `systemctl restart vpnbot-bot`

## تست
در برنامه «خرید VIP» را بزنید ← در ربات Start ← ادمین پیام را می‌بیند ← یکی از دکمه‌ها ← در برنامه VIP فعال می‌شود.

تست‌ها: `cd bot && pip install aiogram==3.13.1 pytest pytest-asyncio && pytest`
