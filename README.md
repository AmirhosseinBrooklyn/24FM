<div align="center">
  
  # 📻 24FM | اف‌ام ۲۴
  **موسیقی بی‌وقفه، به سبک خودت — رادیو آنلاین ۲۴ ساعته**

  [![Status](https://img.shields.io/badge/Status-Active-success.svg?style=for-the-badge)](#)
  [![Version](https://img.shields.io/badge/Version-1.0.0-blue.svg?style=for-the-badge)](#)
  [![Frontend](https://img.shields.io/badge/Frontend-HTML%20%7C%20CSS%20%7C%20JS-orange.svg?style=for-the-badge)](#)
  [![Backend](https://img.shields.io/badge/Backend-PHP%208.x-777BB4.svg?style=for-the-badge&logo=php&logoColor=white)](#)

  <p align="center">
    پروژه 24FM یک پلتفرم استریم و پخش زنده موسیقی است که با یک رابط کاربری لوکس، تاریک (Dark Mode) و مینیمال بر پایه طراحی گرامافون توسعه یافته است.
  </p>

</div>

---

## ✨ ویژگی‌های کلیدی (Features)

### 🎧 پخش‌کننده پیشرفته (Player)
- **طراحی گرامافون تعاملی:** انیمیشن‌های روان و مینیمال برای چرخش دیسک، جابه‌جایی بازوی گرامافون و افکت‌های Glassmorphism.
- **پشتیبانی از HLS:** یکپارچگی کامل با `hls.js` برای استریم بدون قطعی و پایدار.
- **مقاومت در برابر قطعی (Resiliency):** دارای سیستم Watchdog و مکانیزم Retry هوشمند برای بازیابی خودکار استریم در صورت افت سرعت اینترنت.
- **حالت‌های پخش هوشمند:** مدیریت دقیق Stateهای `Play`، `Pause`، `Buffering` و نمایش خطاهای شبکه.

### 📊 پنل مدیریت اختصاصی (Admin Dashboard)
- **آمار در لحظه (Real-time):** نمایش تعداد کاربران زنده (Active Sessions).
- **لاگ و تاریخچه:** ثبت لاگ بازدیدها، سبک‌های انتخابی، مرورگر کاربر و خطاهای فرانت‌اند در یک دیتابیس فایل‌محور (`JSONL`).
- **امنیت بالا:** احراز هویت مبتنی بر Session، محافظت در برابر حملات Brute-force با استفاده از Rate Limiting و توکن‌های CSRF.

---

## 🛠 تکنولوژی‌های استفاده شده (Tech Stack)

| بخش | تکنولوژی | توضیحات |
| :--- | :--- | :--- |
| **رابط کاربری (UI)** | `HTML5`, `CSS3` | استایل‌دهی مینیمال، متغیرهای CSS، افکت‌های بلور و انیمیشن‌های Keyframe |
| **منطق کلاینت (Logic)** | `Vanilla JavaScript` | استفاده از ماژول‌های جداگانه (`player.js`, `app.js`, `popup.js`) |
| **استریمینگ (Streaming)** | `hls.js` | مدیریت قطعات استریم صوتی و بافرینگ شبکه |
| **بک‌اند و API** | `PHP (Strict Types)` | توسعه APIهای لاگین، دریافت وضعیت کاربران زنده و ثبت رویدادها |
| **ذخیره‌سازی (Storage)** | `File-based` | استفاده از فایل‌های `.jsonl` و `.sess` برای مدیریت داده‌ها بدون نیاز به SQL |

---

## 📁 ساختار پوشه‌ها (Directory Structure)

```text
24FM/
├── index.html               # صفحه اصلی و رابط کاربری گرامافون
├── css/
│   └── style.css            # استایل‌های اصلی، انیمیشن‌ها و تم تاریک
├── js/
│   ├── app.js               # نقطه شروع برنامه و تنظیمات اولیه
│   ├── player.js            # هسته اصلی استریم، HLS و کنترلرهای صوتی
│   ├── popup.js             # مدیریت پاپ‌آپ انتخاب سبک و زبان
│   └── analytics.js         # ارسال رویدادها (پخش، توقف، خطاها) به بک‌اند
├── api/
│   ├── track.php            # اندپوینت ثبت رویدادهای کاربر
│   └── live-users.php       # محاسبه کاربران فعال در لحظه
├── admin-brkloginyn/        # پنل مدیریت سیستم (محافظت‌شده)
│   ├── index.php            # داشبورد ادمین
│   └── auth.php             # منطق احراز هویت و Rate Limiting
└── data/                    # پوشه ذخیره‌سازی فایل‌های کانفیگ و لاگ‌ها (نیازمند سطح دسترسی 755)