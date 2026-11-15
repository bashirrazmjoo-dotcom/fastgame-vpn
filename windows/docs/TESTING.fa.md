# تست فاز ۱ روی ویندوز

همه‌ی دستورها را در PowerShell (در صورت نیاز «Run as administrator») اجرا کنید.

1. **نصب:** `FastGame-Setup-0.1.0.exe` را اجرا کنید (یک بار UAC). بعد از نصب، سرویس باید در حال اجرا باشد:
   `Get-Service FastGameVPN`
2. **اتصال:** برنامه را باز کنید، سرور (نوع xray) را انتخاب و وصل شوید. زمان‌شمار باید شروع به کم شدن کند.
3. **IP:** `curl.exe https://api.ipify.org` قبل و بعد از اتصال؛ بعد از اتصال باید IP سرور را نشان دهد.
4. **مسیر پیش‌فرض و کارت شبکه:**
   `Get-NetAdapter | ? Name -eq FastGame` و `Get-NetRoute -DestinationPrefix 0.0.0.0/1,128.0.0.0/1`
5. **نشت DNS:** `nslookup example.com` (سرور DNS نباید DNS اینترنت/مودم شما باشد) و سپس https://www.dnsleaktest.com (Extended test).
6. **IPv6:** `curl.exe -6 https://api64.ipify.org` ؛ باید خطا بدهد یا IP سرور را نشان دهد، نه IP واقعی شما.
7. **غیرتونلی نماندن ترافیک:** تلگرام/یک بازی را باز کنید؛ بدون تنظیم جدا باید از VPN رد شوند. تأخیر UDP: `ping 1.1.1.1 -n 20`.
8. **قطع و وصل:** Wi-Fi/کابل را چند ثانیه قطع و وصل کنید؛ برنامه باید خودکار دوباره وصل شود.
9. **بستن برنامه:** در Task Manager فرایند FastGame را End Task کنید؛ تا حدود ۲۵ ثانیه تونل باید بسته شود و اینترنت عادی برگردد.
10. **قطع با دکمه:** بعد از قطع، کارت FastGame نباید در `Get-NetAdapter` بماند و `curl.exe https://api.ipify.org` باید IP خودتان را بدهد.

اگر اتصال نشد، لاگ سرویس (فقط ادمین): `C:\Windows\System32\config\systemprofile\AppData\Local\FastGame\service.log`
و متن خطای نمایش‌داده‌شده در برنامه را برای من بفرستید (بدون لینک/توکن).
