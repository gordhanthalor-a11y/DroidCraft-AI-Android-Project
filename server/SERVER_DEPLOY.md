# Server Deploy — Render / Railway (exact steps)

Jarvis का server (FastAPI) वहीं चलता है जहाँ heavy work होता है: TTS, QC (ffprobe), auto-publish।
दोनों free tier पर चल जाता है।

---

## ✅ पहले local test (यहीं चलाओ, तभी deploy करो)

```bash
cd server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# ffmpeg server पर चाहिए (QC के लिए)
sudo apt install -y ffmpeg      # Render/Railway पर Nixpacks खुद संभालता है

export SMALLEST_API_KEY=...     # Smallest AI से
export SMALLEST_TENANT=...
export HEYGEN_API_KEY=...       # HeyGen से (वैकल्पिक)

uvicorn main:app --host 0.0.0.0 --port 8001
```

जाँच:
```bash
curl http://127.0.0.1:8001/api/v1/health          # {"ok":true}
curl "http://127.0.0.1:8001/api/v1/tts/voices?provider=smallest"
```

---

## 🚀 Render पर deploy (सबसे आसान)

1. https://render.com → **New → Blueprint** → repo चुनो (`DroidCraft-AI-Android-Project`) और Blueprint file `server/render.yaml` चुनो।
2. Blueprint service का root directory `server/` है; `server/apt.txt` से FFmpeg और `requirements.txt` से Python dependencies install होंगी।
3. Render `render.yaml` देखकर service बना देगा: `jarvis-social` (web, python, free)।
4. Service के **Environment** में 3 keys डालो:
   - `SMALLEST_API_KEY`, `SMALLEST_TENANT`, `HEYGEN_API_KEY`
5. Deploy होने पर URL मिलेगा: `https://jarvis-social.onrender.com`
6. **App के Studio Settings में Server URL यही डालो**।

> Render free plan में app 15 मिनट idle रहने पर sleep हो जाता है — publish loop उस दौरान रुक जाता है।
> बेहतर: **Railway** (monthly free credit) या Render का paid starter।

---

## 🚆 Railway पर deploy

1. https://railway.com → **New Project → Deploy from GitHub repo** → repo चुनो।
2. Service Settings में Root Directory `/server` सेट करो, ताकि Python service, `server/railway.json`, और `nixpacks.toml` (FFmpeg) इस्तेमाल हों।
3. Variables में वही 3 keys डालो।
4. Deploy होने पर domain मिलेगा (जैसे `jarvis-social.up.railway.app`)।
5. App Settings में वही HTTPS URL डालो।

---

## 🧪 Test mode — असली publish अपने account से

> ⚠️ **ईमानदारी से:** मैं (agent) आपके Google/Meta credentials **नहीं माँग सकता** और न ही
> official API बिना OAuth token के काम करती है। इसलिए "मैं आपके account पर publish करके दिखाऊँ"
> तभी संभव है जब **आप** अपने credentials से server पर account connect करो। नीचे exact तरीका:

### Step 1 — Google Cloud OAuth client (10 मिनट)
1. https://console.cloud.google.com → अपना project → **APIs & Services → Library** → `YouTube Data API v3` **Enable**।
2. **OAuth consent screen** → External → app नाम `Jarvis Social` → scope जोड़ो:
   `.../auth/youtube.upload`, `.../auth/youtube.readonly`
3. **Credentials → Create Credentials → OAuth client ID → Web application**:
   - Authorized redirect URI: `https://<अपनी-server-URL>/api/v1/auth/google/callback`
     (Render: `https://jarvis-social.onrender.com/api/v1/auth/google/callback`)
4. Client ID + Secret नीचे copy करो।

### Step 2 — Server पर account connect
```bash
# server पर env के रूप में डालो (Render/Railway dashboard → Variables)
export GOOGLE_CLIENT_ID=...
export GOOGLE_CLIENT_SECRET=...
export REDIRECT_BASE=https://jarvis-social.onrender.com
```

### Step 3 — App से connect करो
- App → **Social tab → Connect Account → YouTube** → Client ID/Secret डालो → OAuth।
- App अपने server को बताएगा कि account connect हो गया।

### Step 4 — test publish (असली API)
- App → **Social tab → Schedule** → एक छोटी वीडियो चुनो → time अभी-अभी रखो → **Schedule**।
- Server के dashboard endpoint पर देखो:
```bash
curl https://jarvis-social.onrender.com/api/v1/dashboard
```
- publish होने पर history में ✅ आएगा; YouTube Studio में वीडियो दिखेगी।

### Test mode की सीमा (ज़रूरी)
- नया Google Cloud project **अभीतक verified नहीं** है तो OAuth सिर्फ़ **test users** के लिए खुलेगा।
- OAuth consent screen पर अपना email **test user** के रूप में जोड़ो (Cloud Console → Audience → Test users)।
- तब तक publish "access_token से काम करेगा, पर OAuth login केवल test users को"।
- जब तक App Review पास न हो, यही industry standard "test mode" है।

---

## 🔒 Private वीडियो का जवाब (आपका सवाल)

**YouTube Data API से upload की गई वीडियो बिना audit के Private क्यों रहती है:**

1. **हमारे code में privacy "public" लिखा है, फिर भी** — नया/अन-verified channel होने पर YouTube कुछ uploads को private रख देता है; `status.privacyStatus` आमतौर पर मान्य होता है, पर कुछ channels पर नहीं।
2. **Channel verification न होना** (phone verify) — तब तक API uploads पर कुछ सीमाएँ।
3. **Copyrighted audio** — Content ID claim आने पर YouTube उसे private/restricted कर देता है।
4. **Scheduled (`publishAt`)** — तब तक private रहती है।

**हमारा हल (Upload Helper में built-in):**
- QC report में हार्डकोडेड **privacy note** दिखता है (UploadHelperScreen)।
- हमेशा पहले **Unlisted** publish करो → checks का इंतज़ार → Short बना verify करो → फिर Studio से Public।
- `post_queue` में `status` और `externalId` सहेजा जाता है — दोबारा publish नहीं होगी।

**अगला कदम:** App Review की script बाद में बनाएँगे (जैसा आपने कहा)। तब तक test mode ही चलेगा — यही सही तरीका है।

---

## 📱 App में Server URL कहाँ डालें

App खोलो → **Studio tab (Awaaz Banao) → ⚙️ Settings** →
- Server URL: `https://jarvis-social.onrender.com`
- Smallest AI key, HeyGen key

और **Social tab → Connect Account** में Google/Meta Client ID/Secret।

> Note: अभी app अपने phone से सीधे `localhost` (10.0.2.2) की बजाय **server URL** use करता है —
> Studio Settings में डाला URL ही TTS/QC calls का मार्ग है।
