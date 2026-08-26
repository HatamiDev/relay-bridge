# نصب از طریق cPanel

cPanel دو حالت کاملاً متفاوت دارد و قدم‌ها در هرکدام فرق می‌کند. اول تشخیص
بدهید کدام را دارید، بعد سراغ مسیر مربوطه بروید.

---

## قدم ۰ — کدام حالت را دارید؟ (۳۰ ثانیه)

وارد cPanel شوید و در کادر جستجوی بالای صفحه بنویسید **Terminal**.

* **اگر آیکون Terminal ظاهر شد** (یا به WHM دسترسی دارید، یعنی
  `hatamidev.com:2087`) → **مسیر A**. سرور شما VPS است و root دارید. همه‌چیز
  کامل نصب می‌شود.
* **اگر ظاهر نشد** → **مسیر B**. هاست اشتراکی است.

> اگر Terminal را نمی‌بینید ولی مطمئن نیستید، از پشتیبانی هاست بپرسید:
> «آیا SSH با دسترسی root دارم؟» جوابشان مسیر را تعیین می‌کند.

---

# مسیر A — cPanel روی VPS (root داری)

cPanel اینجا فقط یک پنل است؛ کار اصلی از Terminal انجام می‌شود.

1. **cPanel → Advanced → Terminal** (یا SSH با هر کلاینتی)
2. اگر کاربر cPanel هستید و نه root، اول `sudo -i` بزنید.
3. دستورات را عیناً اجرا کنید:

```bash
git clone https://github.com/HatamiDev/relay-bridge.git
cd relay-bridge
sudo bash deploy/install-coexist.sh
sudo bash deploy/set-secrets.sh
```

4. ادامه از `deploy/README-fa.md` — DNS، فایروال و تست همان‌جاست.

> **یک نکتهٔ مهم مخصوص WHM/cPanel:** cPanel فایل‌های nginx یا Apache را خودش
> مدیریت می‌کند و ممکن است تنظیمات دستی را بازنویسی کند. اگر سرورتان
> **Apache** دارد (پیش‌فرض cPanel، نه nginx)، اسکریپت nginx را پیدا نمی‌کند و
> متوقف می‌شود. در آن حالت به‌جای فایل nginx از این استفاده کنید:
> **WHM → Service Configuration → Apache Configuration → Include Editor →
> Pre VirtualHost Include** و یک ProxyPass به `127.0.0.1:8443` اضافه کنید.
> ساده‌تر از آن: در cPanel یک زیردامنه بسازید و از **Setup Node.js App**
> استفاده کنید (همان قدم‌های مسیر B، ولی TURN را هم می‌توانید نصب کنید چون
> root دارید).

---

# مسیر B — هاست اشتراکی cPanel

**قبل از شروع، این را بدانید:**

| قابلیت | روی هاست اشتراکی |
|---|---|
| رله‌ی پیامک (SMS) | ✅ کامل کار می‌کند |
| برقراری تماس (signaling) | ✅ کار می‌کند |
| **صدای تماس** | ⚠️ نیاز به TURN دارد که روی هاست اشتراکی **ممکن نیست** |

دلیلش این است که coturn به دسترسی root و یک بازهٔ پورت UDP
(۴۹۱۵۲–۶۵۵۳۵) نیاز دارد و هیچ هاست اشتراکی این را نمی‌دهد. راه‌حل در
انتهای همین مسیر آمده.

---

## قدم ۱ — ساخت زیردامنه

**cPanel → Domains → Create A New Domain**

* Domain: `relay.hatamidev.com`
* تیک **Share document root** را **بردارید**
* Document Root را روی پیش‌فرض بگذارید (`/home/USER/relay.hatamidev.com`)
* Submit

بعد **Zone Editor** را باز کنید و مطمئن شوید رکورد A برای
`relay.hatamidev.com` ساخته شده و به IP همین سرور اشاره می‌کند.

## قدم ۲ — گواهی SSL

**cPanel → Security → SSL/TLS Status**

زیردامنهٔ جدید را تیک بزنید → **Run AutoSSL**. چند دقیقه طول می‌کشد. تا وقتی
کنارش تیک سبز نیامده جلو نروید — اپ اندروید فقط با HTTPS کار می‌کند.

## قدم ۳ — آوردن کد روی هاست

**cPanel → Files → Git™ Version Control → Create**

* Clone URL: `https://github.com/HatamiDev/relay-bridge.git`
* Repository Path: `repositories/relay-bridge`
* Create

> ریپو private است. اگر Git از شما احراز هویت خواست و پنل جایی برای وارد کردن
> آن نداشت، ساده‌ترین راه این است: پوشهٔ `server/` را از کامپیوتر خودتان
> ZIP کنید و با **File Manager → Upload** بالا بفرستید و همان‌جا Extract کنید.
> فقط پوشهٔ `server/` لازم است، بقیهٔ ریپو روی هاست کاربردی ندارد.

مقصد نهایی: `/home/USER/relay-app/` که داخلش `server.js`، `package.json` و
پوشهٔ `src/` باشد.

## قدم ۴ — ساخت فایل .env

**File Manager** → وارد `relay-app` شوید → **+ File** → نامش را `.env`
بگذارید → روی آن راست‌کلیک → **Edit**.

> اگر فایل را نمی‌بینید: در File Manager → **Settings** (بالا راست) →
> تیک **Show Hidden Files (dotfiles)**.

این محتوا را بگذارید و سه مقدار `CHANGE_ME` را عوض کنید:

```
PORT=8443
HOST=127.0.0.1
NODE_ENV=production
PUBLIC_ORIGIN=https://relay.hatamidev.com

TLS_CERT_PATH=
TLS_KEY_PATH=

JWT_SECRET=CHANGE_ME_1
JWT_TTL_SECONDS=2592000
BOOTSTRAP_SECRET=CHANGE_ME_2
PAIR_CODE_TTL_SECONDS=300

STUN_URLS=stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302

# تا وقتی TURN ندارید، این دو خط خالی بمانند.
TURN_URLS=
TURN_STATIC_AUTH_SECRET=
TURN_CREDENTIAL_TTL_SECONDS=3600

# خالی = ذخیره‌سازی داخل حافظه با snapshot روی دیسک.
# هاست اشتراکی Redis ندارد و برای یک فرستنده و چند گیرنده هم لازم نیست.
REDIS_URL=
REDIS_KEY_PREFIX=relay:

OFFLINE_QUEUE_MAX=500
MAX_ENVELOPE_BYTES=131072
SNAPSHOT_PATH=./data/rooms.json
SNAPSHOT_INTERVAL_MS=15000
LOG_LEVEL=info
MAX_RECEIVERS_PER_ROOM=8
```

برای ساختن دو مقدار تصادفی، اگر Terminal ندارید از این سایت استفاده کنید یا
در همان کامپیوتر خودتان در PowerShell بزنید:

```powershell
# JWT_SECRET
-join ((1..96) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })
# BOOTSTRAP_SECRET
-join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })
```

`BOOTSTRAP_SECRET` را جایی نگه دارید — برای بیلد اپ لازم است.

**نکتهٔ مهم دربارهٔ REDIS_URL خالی:** حالت حافظه هر ۱۵ ثانیه یک snapshot در
`data/rooms.json` می‌نویسد و موقع بالا آمدن دوباره می‌خواندش. یعنی اگر
Passenger اپ را ری‌استارت کند، جفت‌شدن دستگاه‌ها از بین نمی‌رود.

## قدم ۵ — ساخت اپ Node

**cPanel → Software → Setup Node.js App → Create Application**

| فیلد | مقدار |
|---|---|
| Node.js version | بالاترین نسخهٔ ۲۰ یا بالاتر موجود |
| Application mode | Production |
| Application root | `relay-app` |
| Application URL | `relay.hatamidev.com` |
| Application startup file | `server.js` |

**Create** را بزنید.

> اگر در لیست نسخه‌ها Node ۲۰ یا بالاتر نبود، همین‌جا متوقف شوید. کد به
> Node 20 نیاز دارد و روی نسخهٔ پایین‌تر بالا نمی‌آید. از پشتیبانی هاست
> بخواهید نسخهٔ جدیدتر اضافه کنند.

## قدم ۶ — نصب پکیج‌ها

در همان صفحه، روی اپِ ساخته‌شده **Edit** بزنید و دکمهٔ
**Run NPM Install** را بزنید. یکی دو دقیقه طول می‌کشد.

اگر دکمه خطا داد، متن بالای صفحه یک دستور `source .../bin/activate` به شما
می‌دهد؛ آن را در Terminal اجرا کنید و بعد `npm install --omit=dev`.

بعد **Restart** را بزنید.

## قدم ۷ — تست

در مرورگر باز کنید:

```
https://relay.hatamidev.com/health
```

باید چیزی شبیه `{"ok":true,...}` ببینید.

* **اگر ۵۰۳ یا صفحهٔ خطای Passenger دیدید:** در Setup Node.js App → Edit،
  پایین صفحه لاگ را ببینید. رایج‌ترین علت‌ها: نسخهٔ Node پایین، `npm install`
  انجام‌نشده، یا یک `CHANGE_ME` جامانده در `.env` (کد عمداً موقع دیدن
  placeholder بالا نمی‌آید تا با رمز پیش‌فرض اجرا نشود).
* **اگر صفحهٔ سایت اصلی را دیدید:** Application URL اشتباه ست شده.

**تست WebSocket** — این حیاتی است. در همان مرورگر، Console را باز کنید
(F12) و بزنید:

```js
new WebSocket('wss://relay.hatamidev.com/socket.io/?EIO=4&transport=websocket')
  .onopen = () => console.log('WEBSOCKET OK')
```

* اگر `WEBSOCKET OK` چاپ شد، عالی است.
* اگر نشد، اپ باز هم کار می‌کند ولی Socket.IO به long-polling می‌افتد —
  کندتر و پرمصرف‌تر. از پشتیبانی هاست بپرسید «آیا WebSocket روی پلن من
  پشتیبانی می‌شود؟» خیلی از هاست‌های اشتراکی نمی‌کنند.

## قدم ۸ — TURN (برای اینکه صدای تماس کار کند)

بدون TURN، تماس فقط وقتی برقرار می‌شود که هر دو گوشی روی یک شبکه باشند یا
NAT اپراتور اجازه بدهد. روی اینترنت موبایل ایران که تقریباً همیشه CGNAT است،
معمولاً برقرار **نمی‌شود**. سه گزینه:

**گزینهٔ ۱ — یک VPS کوچک فقط برای coturn (پیشنهاد من).**
ارزان‌ترین پلن هر ارائه‌دهنده کافی است؛ coturn روی ۱ گیگ RAM راحت اجرا
می‌شود. یک VPS ایرانی هم بگیرید بهتر است، چون صدا از آن رد می‌شود و تأخیر
کمتر یعنی کیفیت بهتر. بعد روی آن:

```bash
sudo apt update && sudo apt install -y coturn
# فایل deploy/coturn/turnserver.conf این ریپو را در /etc/turnserver.conf بگذارید
# realm و server-name را به دامنهٔ همان VPS تغییر دهید
# static-auth-secret را با openssl rand -hex 32 پر کنید
sudo systemctl enable --now coturn
```

و همان مقدار را در `.env` هاست بگذارید:

```
TURN_URLS=turn:turn.hatamidev.com:3478?transport=udp,turn:turn.hatamidev.com:3478?transport=tcp
TURN_STATIC_AUTH_SECRET=<همان مقدار>
```

(و رکورد A برای `turn.hatamidev.com` به IP آن VPS.)

**گزینهٔ ۲ — سرویس TURN آماده.** هر سرویسی که استاندارد
«coturn REST / static-auth-secret» را پشتیبانی کند مستقیم کار می‌کند؛ فقط
`TURN_URLS` و `TURN_STATIC_AUTH_SECRET` را پر کنید. سرویس‌هایی که API
اختصاصی خودشان را دارند (مثل Cloudflare Realtime TURN) نیاز به تغییر کد
دارند — بگویید تا اضافه کنم. توجه: بعضی از این سرویس‌ها از ایران قابل
ثبت‌نام نیستند؛ قبل از حساب‌کردن روی‌شان تست کنید.

**گزینهٔ ۳ — فعلاً بی‌خیال تماس.** `TURN_URLS` را خالی بگذارید. رله‌ی
پیامک کامل کار می‌کند و تماس هم وقتی هر دو گوشی روی وای‌فای خانه باشند
برقرار می‌شود. هر وقت TURN اضافه کردید، فقط دو خط `.env` عوض می‌شود و
Restart — نیازی به بیلد جدید APK نیست.

## قدم ۹ — اتصال اپ

در GitHub → repo → **Settings → Secrets and variables → Actions**:

* تب **Variables** → New: `RELAY_SERVER_URL` = `https://relay.hatamidev.com`
* تب **Secrets** → New: `RELAY_BOOTSTRAP_SECRET` = مقدار `BOOTSTRAP_SECRET`

بعد **Actions → Build APK → Run workflow**، و APK جدید را روی هر دو گوشی
نصب کنید.

---

## محدودیت‌هایی که باید بدانید (هاست اشتراکی)

* **Passenger اپ را بعد از بی‌کاری می‌خواباند** و با اولین درخواست دوباره
  بالا می‌آورد. snapshot جلوی از دست رفتن جفت‌شدن را می‌گیرد، ولی سوکت‌های
  باز قطع می‌شوند و گوشی باید دوباره وصل شود. برای پیامک بی‌اهمیت است؛
  اگر وسط تماس بیفتد، تماس قطع می‌شود.
* **محدودیت پردازه (LVE)** روی پلن‌های ارزان ممکن است اپ را بکشد. اگر
  در لاگ `killed` دیدید، علتش این است.
* **پهنای باند**: signaling ناچیز است. اگر بعداً TURN را روی همان VPS
  گذاشتید، هر تماس حدود ۶۰–۸۰ kbps در هر جهت مصرف می‌کند.

اگر هر کدام از این‌ها آزارتان داد، یک VPS ارزان با مسیر A هم ساده‌تر است و
هم پایدارتر.
