# راه‌اندازی سرور روی hatamidev.com بدون آسیب به سایت فعلی

این راهنما برای حالتی است که روی همان سرور **از قبل یک سایت بالا است**.
همه‌چیزِ رله روی یک زیردامنهٔ جدا (`relay.hatamidev.com`) و یک نمونهٔ Redis
جدا (پورت ۶۳۸۰) نصب می‌شود، تا هیچ فایل یا سرویسی که سایت فعلی به آن وابسته
است دست نخورد.

> **هشدار:** اسکریپت `deploy/install.sh` را روی این سرور اجرا نکنید.
> آن نسخه برای سروری نوشته شده که فقط همین اپ رویش است و
> `/etc/redis/redis.conf` و `sites-enabled/default` را بازنویسی می‌کند.
> روی این سرور فقط `deploy/install-coexist.sh` را اجرا کنید.

---

## قدم ۰ — پیش‌نیازها

روی سرور باید موجود باشد: Ubuntu 22.04 یا 24.04، دسترسی root یا sudo، و nginx
که همین حالا سایت شما را سرو می‌کند.

**رکوردهای DNS** را قبل از قدم ۲ بسازید (certbot تا وقتی این‌ها resolve نشوند
گواهی صادر نمی‌کند):

| Type | Name | Value |
|---|---|---|
| A | `relay.hatamidev.com` | همان IP سرور |
| A | `turn.hatamidev.com` | همان IP سرور |

اگر سرور IPv6 دارد، رکوردهای AAAA را هم اضافه کنید.

اگر از Cloudflare استفاده می‌کنید: هر دو رکورد باید **DNS only** (ابر خاکستری)
باشند. پروکسی Cloudflare نه TURN را عبور می‌دهد و نه WebSocket بلندمدت را
پایدار نگه می‌دارد.

---

## قدم ۱ — فایروال

پورت‌هایی که باید باز شوند. ۸۰ و ۴۴۳ احتمالاً از قبل برای سایت شما بازند.

```bash
ufw allow 3478                 # TURN (UDP + TCP)
ufw allow 5349/tcp             # TURN over TLS
ufw allow 49152:65535/udp      # کاندیدهای رله TURN
ufw status
```

پورت ۶۳۸۰ (Redis رله) عمداً در این فهرست نیست — فقط روی loopback گوش می‌دهد.

**اگر پنل ابری دارید** (Hetzner، DigitalOcean، آروان، ابرآروان…) همین پورت‌ها
را در فایروال پنل هم باز کنید؛ `ufw` جلوی فایروال بالادستی را نمی‌گیرد.

---

## قدم ۲ — نصب

```bash
git clone https://github.com/HatamiDev/relay-bridge.git
cd relay-bridge
sudo bash deploy/install-coexist.sh
```

اسکریپت اول از `/etc/nginx` و `/etc/redis` بکاپ می‌گیرد و مسیر فایل بکاپ را
چاپ می‌کند. اگر `nginx -t` بعد از افزودن سایت جدید خطا داد، خودش سایت رله را
برمی‌دارد و سایت فعلی شما دست‌نخورده می‌ماند.

سرویس‌ها فعال می‌شوند ولی **استارت نمی‌شوند** — چون هنوز رمزها placeholder
هستند و استارت‌کردن فقط باعث crash-loop می‌شود.

---

## قدم ۳ — رمزها

```bash
sudo bash deploy/set-secrets.sh
```

این اسکریپت چهار رمز را می‌سازد و در هر سه فایلی که باید با هم یکی باشند
می‌نویسد، بعد سرویس‌ها را بالا می‌آورد و `/health` را چک می‌کند.

در انتها `BOOTSTRAP_SECRET` را چاپ می‌کند — **آن را نگه دارید**، برای بیلد
اندروید لازم است.

اگر ترجیح می‌دهید دستی انجام دهید، چهار مقدار و محل‌شان:

| مقدار | تولید با | کجا |
|---|---|---|
| `JWT_SECRET` | `openssl rand -hex 48` | `/opt/relay/server/.env` |
| `BOOTSTRAP_SECRET` | `openssl rand -hex 32` | `/opt/relay/server/.env` |
| `TURN_STATIC_AUTH_SECRET` | `openssl rand -hex 32` | `.env` **و** `static-auth-secret` در `/etc/turnserver.conf` |
| رمز Redis | `openssl rand -hex 32` | `requirepass` در `/etc/redis/relay-redis.conf` **و** داخل `REDIS_URL` در `.env` |

دو مورد آخر باید کاراکتربه‌کاراکتر یکی باشند. مغایرت‌شان موقع نصب هیچ خطایی
نمی‌دهد و فقط سر برقراری تماس خودش را نشان می‌دهد.

---

## قدم ۴ — تست

```bash
curl -s https://relay.hatamidev.com/health | python3 -m json.tool
```

باید `"ok": true` و `"turn": true` بدهد.

تست دست‌دادن WebSocket:

```bash
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  "https://relay.hatamidev.com/socket.io/?EIO=4&transport=websocket"
```

تست اینکه TURN واقعاً رله می‌کند:
<https://icetest.info> را باز کنید، سرور را دستی وارد کنید
(`turn:turn.hatamidev.com:3478`) با یوزر/پس موقتی که `/ice` می‌دهد. باید
کاندید `relay` ببینید. اگر فقط `host` و `srflx` دیدید، UDP رنج ۴۹۱۵۲–۶۵۵۳۵
بسته است.

**تست اینکه سایت فعلی سالم است** — این را فراموش نکنید:

```bash
curl -I https://hatamidev.com
sudo nginx -t
```

---

## قدم ۵ — اتصال اپ

الان `RELAY_SERVER_URL` داخل APK هنوز `https://hatamidev.com` است و
`BOOTSTRAP_SECRET` خالی. باید یک بیلد تازه بگیرید:

در GitHub → repo → Settings:

* **Secrets and variables → Actions → Variables** → New variable
  `RELAY_SERVER_URL` = `https://relay.hatamidev.com`
* **Secrets** → New secret
  `RELAY_BOOTSTRAP_SECRET` = همان مقداری که `set-secrets.sh` چاپ کرد

بعد Actions → Build APK → Run workflow. APK جدید را روی هر دو گوشی نصب کنید.

---

## سرعت و کیفیت تماس

**مهم‌ترین عامل، محل سرور است.** صدا وقتی TURN لازم شود از سرور رد می‌شود، پس
هر میلی‌ثانیه فاصلهٔ گوشی تا سرور دو بار حساب می‌شود. برای دو گوشی در ایران،
سرور ایران یا نزدیک‌ترین دیتاسنتر منطقه به‌مراتب بهتر از سرور اروپا یا آمریکا
است. زیر ۵۰ms عالی، بالای ۱۵۰ms در مکالمه محسوس می‌شود.

**اما بیشتر تماس‌ها اصلاً از سرور رد نمی‌شوند.** WebRTC اول مسیر مستقیم را
امتحان می‌کند و TURN فقط fallback است. اگر هر دو گوشی روی یک وای‌فای باشند،
صدا مستقیم بین‌شان می‌رود و سرور فقط برای برقراری اولیه استفاده می‌شود.

چند تنظیم که واقعاً اثر دارد:

* **UDP را باز نگه دارید.** اگر رنج ۴۹۱۵۲–۶۵۵۳۵ بسته باشد، TURN مجبور می‌شود
  از TCP روی ۵۳۴۹ استفاده کند — کار می‌کند ولی هر بستهٔ گم‌شده باعث ارسال مجدد
  و لرزش صدا می‌شود. UDP باز = تفاوت محسوس.
* **`max-bps=1000000`** در `turnserver.conf` برای صدا فراوان است. پایین‌ترش
  نیاورید؛ بالا بردنش هم فایده‌ای ندارد.
* **Cloudflare proxy را خاموش نگه دارید** روی این دو زیردامنه.
* **پهنای باند سرور:** هر تماسی که از TURN رد شود حدود ۶۰–۸۰ kbps در هر جهت
  مصرف می‌کند. برای دو گوشی این هیچ است؛ فقط اگر بعداً چند گیرنده اضافه کردید
  در نظر بگیرید.

منابع سرور: کل این مجموعه (Node + Redis + coturn) روی ۱ vCPU و ۱ گیگ RAM
راحت جا می‌شود و چیزی از سایت فعلی شما کم نمی‌کند.

---

## اگر چیزی خراب شد

بکاپ قبل از نصب اینجاست (مسیر دقیق را خود اسکریپت چاپ کرده):

```bash
sudo tar xzf /root/relay-preinstall-backup-*.tar.gz -C /
sudo nginx -t && sudo systemctl reload nginx
```

برداشتن کامل رله بدون دست‌زدن به سایت:

```bash
sudo rm -f /etc/nginx/sites-enabled/relay.hatamidev.com.conf
sudo nginx -t && sudo systemctl reload nginx
sudo systemctl disable --now relay-signaling redis-relay coturn
```

## لاگ‌ها

```bash
journalctl -u relay-signaling -f
journalctl -u redis-relay -f
journalctl -u coturn -f
tail -f /var/log/nginx/error.log
```

## به‌روزرسانی

```bash
cd relay-bridge && git pull
sudo bash deploy/install-coexist.sh    # فقط کد را sync می‌کند، رمزها دست‌نخورده
sudo systemctl restart relay-signaling
```
