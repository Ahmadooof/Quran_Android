# Google Play publishing notes

Everything needed to fill in the Play Console for **The Great Quran / القرآن العظيم** (`com.readqurantoday.quran`).

## Store art

Made by `python tools/render_store_art.py` into `store/`:

- `icon-512.png` — app icon (512×512)
- `feature-graphic-1024x500.png` — feature graphic

Screenshots: take at least 2 phone screenshots on the device (menu, reader, player, dark mode, downloads).

## Store listing

**App name:** The Great Quran — القرآن العظيم

**Short description (≤ 80 chars)**

- en: Read the Madinah Mushaf page by page and listen to ayah-by-ayah recitation.
- ar: اقرأ مصحف المدينة صفحةً صفحة، واستمع إلى التلاوة آيةً آية.

**Full description**

en:

> The Great Quran shows the Madinah Mushaf exactly as printed, page by page, in clear sharp type on any screen.
>
> - Word-by-word highlighting while the recitation plays
> - Several reciters; download for offline listening, or save the audio to your phone
> - Search the Quran by word, surah, or page
> - Save pages and continue where you left off
> - Light and dark modes, adjustable colours and text weight
> - Slide or page-turn motion, one or two pages side by side
> - Arabic and English interface
>
> No account, no ads, no tracking.

ar:

> يعرض «القرآن العظيم» مصحف المدينة كما هو مطبوع، صفحةً صفحة، بخط واضح على كل الشاشات.
>
> - تمييز الكلمة أثناء التلاوة
> - عدة قرّاء، مع التنزيل للاستماع دون إنترنت أو حفظ التلاوات في الهاتف
> - البحث في القرآن بالكلمة أو السورة أو الصفحة
> - حفظ الصفحات والمتابعة من حيث توقفت
> - مظهر فاتح وداكن، مع تخصيص الألوان وسماكة الخط
> - تقليب الصفحات بالسحب أو بحركة تقليب الورق، وصفحة أو صفحتان
> - واجهة عربية وإنجليزية
>
> بلا حساب، وبلا إعلانات، وبلا تتبّع.

**Category:** Books & Reference  **Contact email:** readqurantoday@outlook.com  **Website:** https://readqurantoday.com

**Privacy policy URL:** https://readqurantoday.com/privacy/

## App content answers

**Ads:** No ads.

**App access:** All features available without login.

**Target audience:** 13+ (not designed for children, so the Families policy does not apply). Religious text, suitable for everyone.

**Content rating questionnaire:** Category *Reference, News, or Educational*. Answer **No** to violence, sexual content, language, drugs, gambling, user-to-user interaction, location sharing, and purchases. Expected result: Everyone / PEGI 3.

**Government app / financial / health features:** No.

### Data safety

Data collected: **Yes**. Shared with third parties: **No**. Encrypted in transit: **Yes** (HTTPS). Users can request deletion: **Yes** (by email).

Only collected when the user sends a report from *Settings → Report an issue*. Sending one is optional; purpose is **App functionality** only:

| Data type (Play name) | Collected | Required? | Purpose |
|---|---|---|---|
| Personal info → Email address | Yes | Optional | Reply to the report |
| App activity → Other user-generated content (the message) | Yes | Optional (only if user sends) | App functionality |
| App info and performance → Diagnostics (app version, device model, Android version, theme, reciter, screen size, last page) | Yes | Optional (only if user sends) | App functionality |
| Device or other IDs | No | | |
| Location, contacts, files, photos, financial, health | No | | |

The IP address is kept with a report only to block spam and is not used to find location, so declare **No** for location.

Reading position, bookmarks, settings and downloads stay on the device and are not "collected".

### Foreground service declaration

Type **Media playback** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`).

> The app plays Quran recitation audio chosen by the user. Playback continues when the screen is off or the user leaves the app, with a media notification to pause, skip, or stop. Without the foreground service, audio would stop when the app goes to the background.

Video of the feature (if asked): screen recording showing tapping play in the reader, going to the home screen, and the notification controls.

### Permissions

- `INTERNET` — stream and download recitations, send reports
- `POST_NOTIFICATIONS` — player and download notifications (asked once on first open)
- `WRITE_EXTERNAL_STORAGE` (maxSdk 28) — save audio to Download on Android 9 and older

## Content rights checklist

Before publishing confirm, and be ready to show if Play asks:

- [ ] **KFGQPC Madinah Mushaf fonts** (King Fahd Glorious Quran Printing Complex) — free for distribution unmodified; keep their credit in the app.
- [ ] **Reciter audio** — permission or licence from each audio source used on the CDN.
- [ ] **Tanzil Quran text** — CC BY 3.0, used unmodified, credited in *Settings → Credits*.
- [ ] Cairo font (OFL 1.1), AndroidX / Media3 / ColorPicker (Apache 2.0) — credited in the app.

## Releasing

1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Build: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. Upload the `.aab` in Play Console (start with *Internal testing*, then *Production*).

First release: enrol in **Play App Signing** (default). Google keeps the app signing key; our key is only the upload key.

### Upload key

- Keystore: `C:\Users\ahmad\AndroidKeys\readqurantoday-upload.jks` (backup in OneDrive `AndroidKeys\`)
- Alias: `upload`
- SHA-256: `74:16:17:19:3C:03:E8:86:19:E4:CD:7A:13:AC:0C:A3:D4:9E:77:2C:69:13:5F:93:45:7F:91:B5:93:AA:E5:55`
- Password: in the password manager, and in the gitignored `keystore.properties`. Never commit either.

If the upload key is lost, Play support can reset it (the app itself stays safe under Play App Signing).
