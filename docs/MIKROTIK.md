# MikroTik (RouterOS 7) — دستورهای راه‌اندازی

این دستورها **هیچ تنظیمی از OpenVPN فعلی شما را تغییر نمی‌دهند**؛ فقط یک کاربر محدود برای بک‌اند
و دسترسی REST امن اضافه می‌کنند. قبل از اجرا مقدارهای `<...>` را عوض کنید و دستورها را یکی‌یکی اجرا کنید.

| مقدار | معنی |
|---|---|
| `<BACKEND_IP>` | آی‌پی ثابت سروری که بک‌اند FastGame روی آن اجرا می‌شود |
| `<ROUTER_IP>` | آی‌پی عمومی میکروتیک (همان که در آدرس REST استفاده می‌شود) |
| `<PROFILE>` | نام PPP profile مربوط به OpenVPN فعلی شما (`/ppp profile print`) |
| `<STRONG_PASSWORD>` | رمز طولانی و تصادفی برای کاربر API (جای دیگری استفاده نشود) |

## ۱) کاربر مخصوص بک‌اند (حداقل دسترسی، فقط از آی‌پی بک‌اند)

```routeros
/user group add name=fg-api policy=read,write,api,rest-api
/user add name=fg-backend group=fg-api password="<STRONG_PASSWORD>" address=<BACKEND_IP>/32 comment="FastGame backend"
```

این کاربر نمی‌تواند وارد Winbox/SSH/Telnet شود و فقط از `<BACKEND_IP>` کار می‌کند.

## ۲) REST روی HTTPS با گواهی خودامضا (و بستن HTTP ساده)

```routeros
/certificate add name=fg-rest common-name=<ROUTER_IP> subject-alt-name=IP:<ROUTER_IP> days-valid=3650 key-usage=digital-signature,key-encipherment,tls-server
/certificate sign fg-rest
/ip service set www-ssl certificate=fg-rest port=8443 address=<BACKEND_IP>/32 disabled=no
/ip service set www disabled=yes
```

گواهی را بیرون بیاورید و **همان** را در پنل ادمین (فیلد Router certificate) بچسبانید تا بک‌اند فقط به همین روتر اعتماد کند:

```routeros
/certificate export-certificate fg-rest
```

سپس فایل `cert_export_fg-rest.crt` را از Files دانلود کنید (فقط بخش عمومی است؛ کلید خصوصی را خارج نکنید).

## ۳) فایروال: فقط بک‌اند به پورت REST برسد

قانون را **بالاتر از** قانون drop پیش‌فرض input قرار دهید (`/ip firewall filter print` برای دیدن ترتیب):

```routeros
/ip firewall filter add chain=input protocol=tcp dst-port=8443 src-address=<BACKEND_IP> action=accept comment="FastGame REST" place-before=0
```

(اگر قانون drop شما جای دیگری است، `place-before=<شماره>` را مطابق آن بدهید.)

## ۴) PPP profile

اگر می‌خواهید همان profile فعلی را برای حساب‌های موقت استفاده کنید، نیازی به ساختن چیزی نیست و نام آن را
(`<PROFILE>`) در پنل می‌نویسید. اگر profile جدا می‌خواهید، آدرس‌ها را از profile فعلی کپی کنید:

```routeros
/ppp profile print
/ppp profile add name=fg-ovpn local-address=<همان local-address> remote-address=<همان pool> dns-server=<همان DNS> only-one=yes
```

## ۵) تنظیم در پنل ادمین

در Servers → سرور آلمان → گزینه «MikroTik» را روشن کنید:
- REST URL: `https://<ROUTER_IP>:8443`
- PPP profile: `<PROFILE>`
- API user: `fg-backend` و رمز بالا
- Router certificate: گواهی خروجی مرحله ۲

از این لحظه برای هر اتصال یک حساب `fg-…` ساخته می‌شود و با پایان اتصال حذف می‌شود.
بعد از انتشار اپ، حساب مشترک قدیمی را در `/ppp secret` غیرفعال کنید (`disabled=yes`) تا رمز استخراج‌شده از نسخه‌های قبلی بی‌اثر شود.

## چطور کار می‌کند
- حساب فقط بعد از چک شدن مسدودی و سهمیه ساخته می‌شود و `limit-uptime` = زمان باقی‌مانده + ۶۰ ثانیه دارد، پس خود روتر هم حتی اگر بک‌اند یا گوشی از دسترس خارج شود اتصال را می‌برد.
- هنگام توقف، پایان سهمیه، مسدود شدن یا قطع heartbeat حساب حذف و تونل فعال قطع می‌شود؛ اگر روتر لحظه‌ای در دسترس نباشد، جاروب‌کننده هر ۳۰ ثانیه دوباره تلاش می‌کند.
- فقط حساب‌هایی که نامشان با `fg-` شروع می‌شود ساخته/حذف می‌شوند. به کاربران دیگر روتر دست نمی‌زنیم.
- اگر روتر در دسترس نباشد، اتصال جدید با خطای «سرور در دسترس نیست» رد می‌شود و چیزی از سهمیه کم نمی‌شود.

## نکته‌ای که باید روی روتر واقعی تست شود
نحوه‌ی حساب‌کردن `limit-uptime` در RouterOS (برای هر اتصال یا تجمعی) را روی روتر خودتان یک بار آزمایش کنید؛
در هر دو حالت امن است چون حساب بعد از هر نشست حذف می‌شود.
