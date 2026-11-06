# استقرار روی سرور (fg.razmjou.com → 185.33.25.240)

روی سرور (Ubuntu/Debian) با کاربر root یا sudo:

```bash
# 1) Docker و certbot
curl -fsSL https://get.docker.com | sh
apt-get install -y certbot git

# 2) گواهی HTTPS (پورت 80 باید آزاد باشد؛ اگر وب‌سرور دیگری دارید اول متوقفش کنید)
certbot certonly --standalone -d fg.razmjou.com

# 3) کد پروژه
git clone https://github.com/bashirrazmjoo-dotcom/fastgame-vpn.git
cd fastgame-vpn/deploy

# 4) تنظیمات (رمزها را تصادفی می‌سازد؛ در جای امن نگه دارید)
cp .env.example .env
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" .env
sed -i "s|^FG_SECRET_KEY=.*|FG_SECRET_KEY=$(openssl rand -base64 48 | tr -d '\n=/+')|" .env
sed -i "s|^FG_ADMIN_BOOTSTRAP_USERNAME=.*|FG_ADMIN_BOOTSTRAP_USERNAME=admin|" .env
nano .env   # رمز FG_ADMIN_BOOTSTRAP_PASSWORD را خودتان بنویسید (طولانی)

# 5) اجرا
docker compose up -d --build
curl https://fg.razmjou.com/healthz
```

پنل مدیریت: `https://fg.razmjou.com/panel/`

بعد از اولین ورود موفق، خطوط `FG_ADMIN_BOOTSTRAP_*` را از `.env` پاک کنید و `docker compose up -d` بزنید.
**FG_SECRET_KEY را هرگز عوض نکنید و حتماً پشتیبان بگیرید** (رمزهای سرورها با آن رمزگذاری می‌شوند).

اضافه کردن سرور: در پنل، Servers → Add server، محتوای فایل `.ovpn` را بچسبانید.
