# FastGame VPN

اپلیکیشن اندروید VPN مبتنی بر OpenVPN با سهمیه رایگان ۱۲۰ دقیقه در هر چرخه ۲۴ ساعته و اشتراک VIP.

## ساختار پروژه

| مسیر | توضیح |
|---|---|
| `backend/` | API با Python + FastAPI + PostgreSQL (محاسبه سهمیه فقط سمت سرور) |
| `admin-panel/` | پنل مدیریت با React + Tailwind CSS |
| `android/` | اپ اندروید با Kotlin + Jetpack Compose + موتور OpenVPN |
| `deploy/` | Docker Compose و تنظیمات Nginx |
| `docs/` | معماری و مستندات انتشار در Google Play |

## مراحل ساخت

1. [x] بررسی محیط و ایجاد ساختار
2. [x] Backend و دیتابیس (FastAPI + PostgreSQL + Alembic، ۶۳ تست خودکار)
3. [x] سهمیه رایگان و VIP (سمت سرور، heartbeat، یک سشن فعال برای هر دستگاه)
4. [x] اپ اندروید (کد نوشته شده؛ هنوز کامپایل نشده — نیاز به Android Studio یا CI)
5. [ ] اتصال OpenVPN
6. [x] پنل مدیریت (admin/، build موفق؛ استقرار: deploy/)
7. [ ] تست کامل
8. [ ] APK و AAB

## اصول طراحی

- **زمان فقط سمت سرور:** کلاینت هیچ‌وقت زمان مصرف را گزارش نمی‌دهد؛ سرور با رویدادهای اتصال/قطع و heartbeat زمان را حساب می‌کند.
- **شناسه نصب:** هر نصب یک UUID می‌سازد و با Play Integrity + کلید عمومی دستگاه ثبت می‌شود.
- **ضد تقلب:** تغییر ساعت گوشی بی‌اثر است (فقط ساعت سرور)، و نصب مجدد با Play Integrity و device-binding محدود می‌شود.
- **سرور OpenVPN فعلی بدون اجازه تغییر نمی‌کند.** فقط پروفایل اتصال (`.ovpn`) خوانده و استفاده می‌شود.

## License
FastGame VPN is free software released under the GNU General Public License v2 (see `LICENSE`).
The Android app is planned to include the ics-openvpn engine (GPLv2 with additional terms); its notices must be kept.
