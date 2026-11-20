# Jarvis Social — Setup Guide (Google Cloud + Meta)

इस गाइड को follow करके अपने official API credentials बनाओ। यही "real system" बनाता है।

---

## A. YouTube — Google Cloud Console

1. https://console.cloud.google.com → नया project बनाओ (जैसे `Jarvis Social`).
2. **APIs & Services → Library** → खोजो `YouTube Data API v3` → **Enable**.
3. **OAuth consent screen** → External → app नाम `Jarvis Social` → email भरो → scopes:
   - `.../auth/youtube.upload`
   - `.../auth/youtube.readonly`
4. **Credentials → Create Credentials → OAuth client ID** → **Android** (package `com.droidcraft.app`, SHA-1 debug cert) या **Web application** (अगर WebView से code exchange करोगे — Web आसान है)।
   - Web client बनाओ तो **Authorized redirect URI**: `https://localhost/oauth2callback` (app में custom tab के बाद code capture होगा)।
5. Client ID + Secret app के **Settings → Social Accounts** में डालो।

> YouTube quota: 10,000 units/day (एक video upload = 1600 units)। दिन में ~5-6 videos safe।

---

## B. Instagram — Meta for Developers

1. https://developers.facebook.com → **Create App** → type **Business** → नाम दो।
2. App Dashboard → **Add products → Instagram** → **Instagram Graph API** add करो।
3. **Instagram Graph API** settings → scopes:
   - `instagram_basic`
   - `instagram_content_publish`
   - `pages_read_engagement`
   - `pages_manage_posts`
4. **App Review** → request करो `instagram_content_publish` + `pages_manage_posts` (स्क्रीनकास्ट ज़रूरी)। जब तक review pass न हो, **Test Users** के लिए ही काम करेगा।
5. Instagram account को **Professional (Business/Creator)** बनाओ और Facebook Page से connect करो (Meta Business Suite)।
6. Facebook Login product add करो → OAuth के लिए। Page token 60-din का; app auto-refresh करेगा।

> ⚠️ Instagram Graph API personal accounts को auto-publish नहीं देता। Business/Creator + App Review ज़रूरी। यह Meta की policy है, bypass करना possible नहीं है (और करना चाहिए भी नहीं)।

---

## C. Facebook Page

उसी Meta app में **Facebook Login** → `pages_manage_posts` → Page access token। एक Page चुनो → उसी token से videos/feed publish होगा।

---

## D. Gemini (captions)

Jarvis की API key (पहले से Settings में) यही captions generate करेगी। कुछ अलग setup नहीं चाहिए।

---

## E. App में करना

1. App खोलो → **Jarvis → Social Hub** tab।
2. **Connect Account** → platform चुनो → Client ID/Secret डालो → official consent screen → allow।
3. Video queue करो → AI captions आएँगे → schedule सेट करो → **Publish**।
4. Dashboard में Scheduled / Published / Failed / History देखो।

---

## F. Server (वैकल्पिक — app बंद होने पर भी)

```bash
cd server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export ENCRYPTION_KEY=...   # 64 hex chars
uvicorn main:app --host 0.0.0.0 --port 8000
```
Render/Railway/Fly.io पर deploy करो; app के Settings में server URL डालो।
