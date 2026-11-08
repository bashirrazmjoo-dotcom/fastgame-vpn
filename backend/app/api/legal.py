"""Public legal pages (privacy policy) required by Google Play for VPN apps."""

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["legal"], include_in_schema=False)

CONTACT = "https://t.me/fastgamevpn"
UPDATED = "2026-11-09"

_EN = f"""
<section lang="en" dir="ltr">
<h1>FastGame VPN – Privacy Policy</h1>
<p class="meta">Last updated: {UPDATED}</p>
<p>FastGame VPN ("we", "the app") provides a VPN service through Android's VPN API. This page explains
what the app and our servers process. You do not create an account or give us your name, e-mail or phone number.</p>

<h2>What the VPN does</h2>
<p>While connected, the app uses Android's <code>VpnService</code> to send <b>all</b> of your device's internet
traffic through our VPN server, which is its only purpose. We use this to give you a secure, encrypted tunnel.
We do not use the VPN to show ads, sell data, or inject content.</p>

<h2>Data we process</h2>
<ul>
<li><b>Anonymous device identifier</b> – a value derived from your device (hashed on the server) and a short
public user code (for example <code>u123-abcd</code>). It lets us remember your plan and remaining free time after
you reinstall the app. It is not linked to your name or any account.</li>
<li><b>Usage time</b> – when a connection starts, when it ends and how many seconds you were connected, to enforce
the free plan (120 minutes per 24 hours) and VIP expiry.</li>
<li><b>Technical data</b> – app version, language, selected server, and the time of your last activity.</li>
<li><b>Temporary connection credentials</b> – a one-time username and password created for each connection and
removed when it ends.</li>
<li><b>Support</b> – if you contact us on Telegram or buy VIP, we see your Telegram account and the user code you send.</li>
</ul>

<h2>What we do not do</h2>
<ul>
<li>We do not collect your name, e-mail, phone number, contacts, photos, location or advertising ID.</li>
<li>We do not monitor, record or keep the websites you visit or the content of your traffic.</li>
<li>We do not sell your data or share it with advertisers. The app contains no ads and no third-party analytics or trackers.</li>
</ul>

<h2>Sharing</h2>
<p>Data is stored on our own server and is not shared with third parties, except where the law requires it.
Traffic to the internet leaves from our VPN server, so the websites you visit see the server's IP address, not yours.</p>

<h2>Security and retention</h2>
<p>The app talks to our API over HTTPS and the VPN tunnel is encrypted. Usage records are kept while your
identifier is active and for as long as needed to run the service.</p>

<h2>Your choices and deletion</h2>
<p>You can stop at any time by disconnecting and uninstalling the app. To have the data tied to your user code
deleted, message us on Telegram with that code: <a href="{CONTACT}">{CONTACT}</a>.</p>

<h2>Children</h2>
<p>The service is not directed to children under 13.</p>

<h2>Contact and changes</h2>
<p>Questions: <a href="{CONTACT}">{CONTACT}</a>. If this policy changes, the new version and date will appear on this page.</p>
</section>
"""

_FA = f"""
<section lang="fa" dir="rtl">
<h1>فست‌گیم وی‌پی‌ان – حریم خصوصی</h1>
<p class="meta">آخرین به‌روزرسانی: {UPDATED}</p>
<p>فست‌گیم (FastGame VPN) با سرویس VPN اندروید یک تونل امن بین دستگاه شما و سرور ما می‌سازد.
این صفحه توضیح می‌دهد برنامه و سرورها چه داده‌ای پردازش می‌کنند. ثبت‌نام لازم نیست و نام، ایمیل یا شماره‌ی شما را نمی‌گیریم.</p>

<h2>VPN چه می‌کند</h2>
<p>هنگام اتصال، <b>تمام</b> ترافیک اینترنت دستگاه شما از سرور VPN ما عبور می‌کند. این تنها هدف آن است.
از VPN برای تبلیغ، فروش داده یا تغییر محتوا استفاده نمی‌کنیم.</p>

<h2>داده‌هایی که پردازش می‌کنیم</h2>
<ul>
<li><b>شناسه‌ی ناشناس دستگاه</b> – مقداری که از دستگاه ساخته و در سرور هش می‌شود، به‌همراه یک کد کاربری کوتاه (مثل <code>u123-abcd</code>).
برای به‌خاطر سپردن پلن و زمان باقی‌مانده بعد از نصب دوباره است و به نام یا حساب شما وصل نیست.</li>
<li><b>زمان مصرف</b> – شروع و پایان اتصال و مدت اتصال، برای اعمال پلن رایگان (۱۲۰ دقیقه در ۲۴ ساعت) و انقضای VIP.</li>
<li><b>داده‌ی فنی</b> – نسخه‌ی برنامه، زبان، سرور انتخابی و زمان آخرین فعالیت.</li>
<li><b>اعتبار موقت اتصال</b> – نام‌کاربری و رمز یک‌بارمصرف برای هر اتصال که با پایان آن حذف می‌شود.</li>
<li><b>پشتیبانی</b> – اگر در تلگرام پیام بدهید یا VIP بخرید، حساب تلگرام و کد کاربری‌ای که می‌فرستید را می‌بینیم.</li>
</ul>

<h2>کارهایی که نمی‌کنیم</h2>
<ul>
<li>نام، ایمیل، شماره‌ی تلفن، مخاطبین، عکس‌ها، موقعیت مکانی یا شناسه‌ی تبلیغاتی شما را نمی‌گیریم.</li>
<li>سایت‌هایی که می‌بینید و محتوای ترافیک شما را پایش، ضبط یا نگهداری نمی‌کنیم.</li>
<li>داده‌ی شما را نمی‌فروشیم و با تبلیغ‌دهندگان به اشتراک نمی‌گذاریم. برنامه تبلیغ و ردیاب یا تحلیل‌گر شخص ثالث ندارد.</li>
</ul>

<h2>اشتراک‌گذاری</h2>
<p>داده‌ها روی سرور خودمان نگهداری می‌شوند و جز در موارد الزام قانونی با شخص ثالثی به اشتراک گذاشته نمی‌شوند.
ترافیک شما از سرور VPN به اینترنت می‌رسد؛ بنابراین سایت‌ها نشانی IP سرور را می‌بینند، نه نشانی شما را.</p>

<h2>امنیت و نگهداری</h2>
<p>ارتباط برنامه با API از طریق HTTPS است و تونل VPN رمزنگاری‌شده است. سوابق مصرف تا زمانی که شناسه‌ی شما فعال است و به‌اندازه‌ی نیاز سرویس نگه‌داری می‌شود.</p>

<h2>انتخاب‌ها و حذف داده</h2>
<p>هر زمان می‌توانید اتصال را قطع و برنامه را حذف کنید. برای حذف داده‌های مربوط به کد کاربری‌تان، آن کد را در تلگرام بفرستید: <a href="{CONTACT}">{CONTACT}</a>.</p>

<h2>کودکان</h2>
<p>این سرویس برای کودکان زیر ۱۳ سال نیست.</p>

<h2>تماس و تغییرات</h2>
<p>پرسش‌ها: <a href="{CONTACT}">{CONTACT}</a>. اگر این سیاست تغییر کند، نسخه و تاریخ جدید همین‌جا نشان داده می‌شود.</p>
</section>
"""

_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FastGame VPN – Privacy Policy / حریم خصوصی</title>
<style>
:root{{color-scheme:dark light;--bg:#0b0e1a;--fg:#e8ecf8;--mut:#9aa3c0;--acc:#38e8c6}}
@media (prefers-color-scheme:light){{:root{{--bg:#fff;--fg:#14172a;--mut:#555d7a;--acc:#0a8f78}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:16px/1.7 system-ui,"Vazirmatn",sans-serif}}
main{{max-width:760px;margin:0 auto;padding:24px 16px 64px}}
h1{{font-size:24px;margin:.2em 0}}h2{{font-size:18px;margin-top:1.6em;color:var(--acc)}}
.meta{{color:var(--mut);font-size:14px}}a{{color:var(--acc)}}code{{background:#8883;padding:1px 6px;border-radius:6px}}
section+section{{margin-top:56px;padding-top:32px;border-top:1px solid #8884}}
</style></head><body><main>{en}{fa}</main></body></html>"""


@router.get("/privacy", response_class=HTMLResponse)
def privacy() -> HTMLResponse:
    return HTMLResponse(_PAGE.format(en=_EN, fa=_FA))
