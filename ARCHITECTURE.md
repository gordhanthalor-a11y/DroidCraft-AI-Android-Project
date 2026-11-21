# Jarvis Social — Buffer-जैसा Social Media Automation & Auto Publishing System

> **Version 1.0** • Android (Kotlin) + optional Relay Server (Python) • Official APIs only
> यह document पूरे system की blueprint है — APIs, Database, Workflow, Security, Rate Limits.

---

## 1. System Overview

दो हिस्से हैं:

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│   Android App (Control Plane)│        │   Relay Server (Data Plane)   │
│  ─ Jarvis Social Dashboard   │◄──────►│  ─ Queue sync                │
│  ─ Video queue (50-100)      │  HTTPS │  ─ Publish jab app band ho   │
│  ─ AI captions (Gemini)      │        │  ─ Retry / Rate limit        │
│  ─ Scheduler (WorkManager)   │        │  ─ History / Dashboard       │
│  ─ OAuth (Google / Meta)     │        └──────────────────────────────┘
└──────────────┬──────────────┘
               │ Official APIs
   ┌───────────┼────────────┬──────────────┐
   ▼           ▼            ▼              ▼
 YouTube    Instagram     Facebook      Gemini
 Data API   Graph API     Graph API     (captions)
```

- **Mode A (Phone-only):** सब कुछ phone पर — WorkManager exact alarms से publish। App बंद हो तब भी Android WorkManager चलता रहता है।
- **Mode B (Relay Server):** `server/` फ़ोल्डर का Python server कहीं host करो (Render/Railway/अपना VPS)। App queue → server sync करता है; schedule time पर **server** publish करता है, भले ही phone बंद हो।

---

## 2. Required APIs & Credentials (Official — कोई bypass नहीं)

| Platform | API | क्या चाहिए | कहाँ बनाएँ |
|---|---|---|---|
| **YouTube** | YouTube Data API v3 — `videos.insert` (resumable), `thumbnails.set` | Google Cloud project + OAuth 2.0 Client (Android/Desktop) + API enable | https://console.cloud.google.com → "YouTube Data API v3" enable |
| **Instagram** | Instagram Graph API — `ig_media` + `ig_publish` (Reels/Feed) | Meta App + Instagram **Business/Creator** account + `instagram_content_publish`, `pages_read_engagement` | https://developers.facebook.com → App → Instagram Graph API |
| **Facebook** | Facebook Graph API — `/page/videos`, `/page/feed` | उसी Meta App में `pages_manage_posts` + Page access token | Meta App Dashboard |
| **AI** | Gemini 3.8 Flash (text/captions) + Gemini 3.8 Live (realtime audio) | API key from Settings, encrypted with Android Keystore | Google AI Studio |

### ⚠️ ज़रूरी सच (Meta policy)
- Instagram Graph API सिर्फ़ **Business/Creator** accounts को publish करने देता है। Personal account से auto-publish **officially impossible** है।
- `instagram_content_publish` permission के लिए Meta **App Review** पास करना होता है (demo mode में सिर्फ़ app admins/test users)। इसलिए system में **"Meta App Review" checklist** दी गई है।
- Facebook Page token: एक दिन का short-lived token → `fb_exchange_token` से 60-day long-lived token। हम auto-refresh करते हैं।

### OAuth flow (सुरक्षित)
1. App में **Settings → Social Accounts → Connect** करके अपना Client ID / Secret डालो (या pre-configured).
2. WebView/Custom Tabs में official consent screen → authorization code.
3. Code → access token + refresh token exchange (OkHttp, server-side जैसा)।
4. Tokens **EncryptedSharedPreferences** में store (Android Keystore + AES-256)।
5. हर call से पहले refresh check; 401 पर auto-refresh + retry।

---

## 3. Database (Room v4)

### Social and Studio tables (existing databases migrate sequentially through `MIGRATION_1_2`, `MIGRATION_2_3`, and `MIGRATION_3_4`)

```kotlin
// 1) Connected social accounts + tokens
@Entity(tableName = "social_accounts", indices = [Index(value=["platform","account_id"], unique = true)])
data class SocialAccountEntity(
  @PrimaryKey(autoGenerate=true) val id: Long = 0,
  val platform: String,      // "youtube" | "instagram" | "facebook"
  val accountId: String,     // channel id / ig user id / page id
  val accountName: String,
  val accessToken: String,   // encrypted at rest
  val refreshToken: String?, // encrypted at rest
  val expiresAt: Long,       // epoch millis
  val scopes: String,
  val connectedAt: Long,
  val isActive: Boolean = true
)

// 2) Post queue — ek video, 1..n platforms
@Entity(tableName = "post_queue",
  indices = [Index(value=["external_id"], unique = true),
             Index(value=["status","scheduled_at")],
             Index(value=["platform","status"])])
data class PostQueueEntity(
  @PrimaryKey val id: String,          // UUID
  val videoPath: String,               // local file path (content uri संग्रहीत करके)
  val videoSha256: String,             // duplicate रोकने के लिए
  val title: String,                   // master title
  val description: String,             // master description
  val hashtags: String,                // master hashtags
  val thumbnailPath: String?,
  val durationSec: Int,
  val isShort: Boolean,                // <60s या #shorts
  val platform: String,                // "youtube" | "instagram" | "facebook"
  val accountId: String,               // किस account पर
  val scheduledAt: Long,               // epoch millis
  val status: String,                  // QUEUED | UPLOADING | PUBLISHED | FAILED | CANCELLED
  val externalId: String?,             // youtube video id / ig media id
  val publishedAt: Long?,
  val retryCount: Int = 0,
  val lastError: String?,
  val createdAt: Long = System.currentTimeMillis()
)

// 3) Per-platform AI captions (duplicate से बचाव)
@Entity(tableName = "post_captions", indices = [Index(value=["post_id","platform"], unique = true)])
data class PostCaptionEntity(
  @PrimaryKey(autoGenerate=true) val id: Long = 0,
  val postId: String,
  val platform: String,
  val caption: String,
  val title: String?,
  val hashtags: String?,
  val contentHash: String,   // caption का hash — दोहराव रोकने के लिए
  val generatedAt: Long
)

// 4) Publish history (audit + retry)
@Entity(tableName = "publish_history")
data class PublishHistoryEntity(
  @PrimaryKey(autoGenerate=true) val id: Long = 0,
  val postId: String,
  val platform: String,
  val attemptAt: Long,
  val success: Boolean,
  val externalId: String?,
  val errorCode: String?,
  val errorMessage: String?
)
```

### Duplicate-posting की 3 परत सुरक्षा
1. **DB unique index** `post_queue.external_id` — एक ही video एक ही platform पर दो बार नहीं।
2. **SHA-256** — फ़ाइल का hash; पहले से upload हुई file दोबारा नहीं।
3. **publish_history** — सफल publish के बाद `externalId` lock; retry से पहले जाँच।

---

## 4. Workflow (पूरा पाइपलाइन)

```
[1] UPLOAD          [2] AI ANALYZE        [3] CAPTION           [4] SCHEDULE
 videos pick  ───►   Gemini video    ───►  per-platform   ───►  date/time set
 (gallery/record)   understand + topic     captions           bulk 50-100
                                            │                  │
                                            ▼                  ▼
                                      [5] QUEUE        [6] PUBLISH (Worker/Server)
                                      status=QUEUED    exact time → upload API
                                                          │
                                                          ▼
                                                    [7] VERIFY + HISTORY
                                                     YouTube: videos.list status
                                                     IG: media status
                                                          │
                                                          ▼
                                                    PUBLISHED / FAILED → retry
```

### विस्तार से

**1. Video upload (एक बार 100 वीडियो)**
- Gallery picker (multiple) या record।
- `MediaExtractor` से duration; `<60s` या title में `#shorts` → `isShort=true`।
- Local file को app-internal storage में copy (content URI persist permission) + SHA-256 निकालो।
- Duplicate check: अगर same SHA पहले से queue में है → skip या merge platforms।
- **Bulk schedule UI:** grid में सब videos, एक date/time सेट करो → हर video के लिए platform-wise queue entry बनता है।

**2. AI analyze + captions (Gemini 3.8 Flash)**
- Video के frames/audio transcript (पहले से बने captions से) → Gemini को भेजो।
- Output (JSON): `{ topic, youtube: {title, description, hashtags}, instagram: {caption, hashtags}, facebook: {caption} }`।
- `contentHash` से दोहराव रोको; हर platform का अलग caption।
- Caption में video और title का **मिलान** (topic similarity) verify।

**3. Schedule**
- `WorkManager` `OneTimeWorkRequest` with `initialDelay = scheduledAt - now` + `setExpedited(OUT_OF_QUOTA)`।
- Exact timing के लिए `AlarmManager.setExactAndAllowWhileIdle` → BroadcastReceiver → Worker enqueue (Doze में भी)।
- 50-100 videos: हर एक के लिए अलग WorkRequest (unique work name `post_<id>`), साथ में **BatchScheduler** सब enqueue करता है।
- Server mode: queue → `POST /api/v1/posts/sync` → server अपने cron/APScheduler से publish।

**4. Publish (असली API)**
- **YouTube:** resumable `videos.insert` (chunks, progress), फिर `thumbnails.set`, `isShort` → description में `#shorts`।
- **Instagram:** `POST /ig_user_id/media` (`media_type=REELS`, video_url/caption) → `POST /ig_user_id/media_publish`।
- **Facebook:** `POST /page_id/videos` (file) या `feed`।

**5. Verify + retry**
- Publish के बाद `videos.list?part=status` / `ig_media?fields=status` से confirm।
- Success → `status=PUBLISHED`, `externalId` lock, history में ✅।
- Fail → `retryCount++`, `lastError` लिखो, **exponential backoff** (2^retry × 1 min, max 5) → फिर queue।
- Rate limit (429 / quotaExceeded) → `Retry-After` मानकर schedule आगे धकेलो।

---

## 5. API Rate Limits (गणना)

| API | Limit | ट्रिक |
|---|---|---|
| YouTube Data API | **10,000 units/day**; `videos.insert` = **1600 units** | दिन में ~6 full videos + reads; queue में quota tracker, सारे 100 videos एक दिन में नहीं — planer समय स्प्रेड करता है |
| Instagram Graph | ~200 calls/hour/user (publish) | batch publish में gap; 429 पर backoff |
| Facebook Graph | tier-based, ~200/hr | page token refresh हर 60 दिन |

**Quota guard:** Har publish से पहले `quota_used` counter check; 90% पर schedule को अगले दिन shift।

---

## 6. Background & "App बंद होने पर भी"

- **Phone mode:** `WorkManager` (दिए गए कारण से अनुमति) + `AlarmManager` exact। Android WorkManager app process मर जाने पर भी pending work चलाता है।
- **Server mode:** queue sync → server पर SQLite/Postgres; APScheduler cron हर मिनट due posts publish करता है। यही "server से scheduling" है, बिना कोई bypass।
- Foreground service **ज़रूरी नहीं** publish के लिए; WorkManager पर्याप्त। (लेकिन बड़े upload के लिए foreground service option रखा जाएगा।)

---

## 7. Security

- Tokens: `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore key)।
- Client Secret: user के पास रहता है; app में सिर्फ़ user खुद डालता है (कोई hard-coded secret नहीं) — या optional pre-configured build config।
- नेटवर्क: केवल HTTPS, certificate pinning नहीं (लेकिन TLS 1.2+)।
- OAuth state parameter से CSRF रोक।
- Refresh token rotation: नया मिले तो पुराना replace।

---

## 8. Module Map (कोड कहाँ-कहाँ)

```
app/src/main/java/com/droidcraft/app/
├─ social/
│  ├─ data/local/AppDatabase.kt        (Room v4 + social / Studio DAOs and migrations)
│  ├─ model/PostModels.kt             (entities + enums)
│  ├─ api/YouTubePublisher.kt         (Data API v3 resumable + thumbnail)
│  ├─ api/InstagramPublisher.kt       (Graph API media + publish)
│  ├─ api/FacebookPublisher.kt        (page videos/feed)
│  ├─ auth/OAuthManager.kt            (token store, refresh, WebView flow)
│  ├─ ai/CaptionGenerator.kt          (Gemini per-platform captions + dedupe)
│  ├─ schedule/PublishScheduler.kt    (WorkManager exact + batch + quota)
│  ├─ work/PublishWorker.kt           (upload→publish→verify→retry)
│  ├─ sync/RelaySync.kt               (optional server sync)
│  └─ ui/SocialHubScreen.kt           (dashboard + queue + schedule + history)
└─ jarvis/ (existing)  ← social intents add होंगे: POST_SCHEDULE, SOCIAL_DASHBOARD
```

```
server/                                  (optional, Python)
├─ main.py            FastAPI app
├─ db.py              SQLite/Postgres queue
├─ youtube.py         Data API v3 publish
├─ instagram.py       Graph API publish
├─ facebook.py        page publish
├─ scheduler.py       APScheduler due-post loop
├─ security.py        token encryption at rest
└─ requirements.txt
```

---

## 9. Deliverables (क्रम से)

1. **Android** — Room v4 schema, OAuth manager, social publishers, caption generator, scheduler, worker, dashboard UI, and Jarvis voice commands (`100 videos schedule karo`, `social dashboard kholo`)।
2. **Server** — Python relay (अगर चाहो) — queue sync + background publish।
3. **Docs** — `SOCIAL_SETUP.md` (Google Cloud / Meta App कैसे बनाएँ, App Review checklist)।

> ⚠️ **ईमानदार नोट:** वास्तविक publishing के लिए आपको अपने Google Cloud + Meta Developer accounts पर OAuth client बनाना होगा और Meta App Review पास करना होगा। Code पूरा असली होगा — कोई fake demo नहीं — लेकिन official APIs बिना credentials के काम नहीं करतीं। यही industry standard है (Buffer भी यही करता है)।
