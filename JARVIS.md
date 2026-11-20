# J.A.R.V.I.S. — Aapka Personal AI Assistant

DroidCraft app mein ab **J.A.R.V.I.S.** (Just A Rather Very Intelligent System) hai — Iron Man style
voice assistant jo Hindi, Hinglish aur English, teenon mein baat karta hai.

## Kya kya kar sakta hai

| Command (bolo ya likho) | Kaam |
|---|---|
| `Mausam kaisa hai?` / `weather in Jaipur` | Live mausam (open-meteo, bina API key) |
| `Alarm 7 baje` / `mujhe 6 baje uthao` | Alarm set |
| `Timer 5 minute` | Timer set |
| `Open whatsapp` / `kholo youtube` | Koi bhi installed app kholo |
| `Call 9876543210` | Dial pad |
| `SMS 9876543210: hello` | SMS app |
| `Note karo: dua hai` / `note dikhao` | Notes save/list |
| `Flashlight on/off` | Torch |
| `Battery kitni hai?` | Battery status |
| `Time kya hai` / `aaj ki date` | Samay/tareekh |
| `Joke sunao` | Hinglish jokes |
| `Video ideas` / `script likho` | Creator tasks (Gemini) |
| `mera naam Rahul hai` | Naam yaad rakhta hai |
| `call papa` / `sms mummy: aa jao` | Contacts se naam se call/SMS |
| `remember that meri bike ka number 1234 hai` | Baatein yaad (Room memory) |
| `what do you remember` | Yaadein dikhao |
| `screen dikhao` / `screen par kya hai` | Screen ka text padho (accessibility) |
| `tap on WhatsApp` / `subscribe par click karo` | Screen par click (accessibility) |
| **Live** (header ka 🎚️) | Gemini Live realtime voice — seedha bolo, barge-in ke saath |
| **🎧 Hey Jarvis** | Background wake-word — "Hey Jarvis" bolo, Jarvis sunna shuru |

## Voice + awaaz
- Mic dabao → bolien (Hindi/English) → Jarvis samjhta hai.
- Jarvis khud **bol kar** jawab deta hai (Text-to-Speech). Upar 🔊/🔇 se awaaz on/off.
- Arc-reactor ka rang batata hai: **neela** = online, **cyan** = sun raha hai,
  **baingni** = soch raha hai, **hara** = bol raha hai.

## Permissions ek baar mein dena hota hai
- **Microphone** — voice ke liye (pehli baar).
- **Contacts** — `call papa` jaise commands ke liye (command bolo toh dialog aa jayega).
- **Accessibility** — header ka 🧩 icon → "Jarvis Automation" ON → `screen dikhao` / `tap on X`.
- **Notifications** — 🎧 "Hey Jarvis" background listening ke liye.

## Jarvis Social (Buffer-जैसा auto publishing)
Bottom bar का **Social** tab खोलो।
- `100 videos schedule karo` / `social dashboard kholo` — Jarvis ही Social Hub खोलेगा।
- **Connect Account** → YouTube/Instagram का official OAuth (Client ID/Secret SOCIAL_SETUP.md से)।
- **Schedule** → videos चुनो (50-100 एक साथ) → time सेट करो → AI captions अपने आप।
- Queue / History / Accounts tabs; Scheduled / Published / Failed counts।
- पूरा architecture: `ARCHITECTURE.md` • setup: `SOCIAL_SETUP.md` • relay server: `server/`

## API key (optional)
Device ke kaam **bina key ke** chalte hain. Baat-cheet aur creator tasks ke liye Gemini API key
daaliye (⚙️ icon) — [aistudio.google.com/apikey](https://aistudio.google.com/apikey) se free milti hai.

## APK install
1. Release se `app-debug.apk` download karo:
   <https://github.com/gordhanthalor-a11y/DroidCraft-AI-Android-Project/releases>
2. Phone par install karo (unknown sources allow karna ho to kar lena).
3. App kholo → **Jarvis** tab → mic se bolo.

## Web demo (browser)
`jarvis-web/index.html` — bina install ke browser mein hi try karo (Chrome best).
Web Speech API se voice input/output + same commands.

## Architecture
- `jarvis/JarvisCommandParser.kt` — offline Hinglish NLU (fast path, no network)
- `jarvis/JarvisDevice.kt` — device actions (apps, intents, weather, notes, battery, torch)
- `jarvis/JarvisBrain.kt` — Gemini 2.5 Flash persona + JSON tool output
- `jarvis/contacts/ContactResolver.kt` — naam se contact (exact/nickname/fuzzy)
- `jarvis/memory/MemoryManager.kt` + `data/local/AppDatabase.kt` — Room-backed yaadein
- `jarvis/a11y/JarvisAccessibilityService.kt` — screen padho + tap by text
- `jarvis/wake/WakeWordService.kt` — "Hey Jarvis" foreground wake-word
- `jarvis/live/GeminiLiveClient.kt` + `LiveAudio.kt` — Gemini Live WebSocket + 16k/24k PCM
- `jarvis/JarvisViewModel.kt` — state, TTS, chat, live session
- `ui/JarvisScreen.kt` — arc-reactor UI, waveform, chat

## Creator Studio (Studio tab — Gowardhan के रोज़ के काम के हिसाब से)
Bottom bar का **Studio** tab खोलो — 8 हिस्से:
- **Awaaz Banao** — Hindi script → server TTS (Smallest AI / HeyGen) → MP3 play/save/share; speed + sentence-gaps।
- **QC** — वीडियो चुनो → 1080×1920 / SAR 1:1 + DAR 9:16 / duration / audio / −14 LUFS की pass/fail list; credit line + upload-flow reminder।
- **Dubbing** — link → transcript → Hindi script → server render → share।
- **Clipper** — stream link → 1080×1920 clips (facecam top + gameplay below, credit chip), parts में।
- **Script** — topic → Gemini Hindi script (hook 2s, source facts)।
- **Thumbnail** — photo पर पीला bold text, 9:16, नीचे-बाएँ खाली।
- **Channels** — 7 channels के Studio links + Content Rewards campaign tracker।
- **Daily** — job applications + Hindi notes।

Jarvis voice: `awaaz banao` / `voice studio` → Voice Studio; `upload check` / `qc karo` → Upload QC।
Server deploy: `server/SERVER_DEPLOY.md` (Render / Railway)।

## 🎭 Jarvis का Character, Emotion और Voice (नया)
Jarvis अब robot नहीं — एक **लाइविंग AI दोस्त** है जो महसूस करता है।
- **Character sheet**: `jarvis/character/JarvisCharacter.kt` — उसका नाम, लहजा (Hinglish), नियम, और हर बात में आपका नाम/काम/यादें शामिल।
- **Emotion engine**: `jarvis/character/Emotion.kt` — बात से भावना निकालता है (खुशी/दुख/गुस्सा/हिम्मत/शांति) और तीन जगह दिखाता है:
  1. **शब्द** — जवाब का लहजा भावना के अनुसार।
  2. **आवाज़** — ElevenLabs style/stability/speed भावना से बदलता है।
  3. **HUD avatar** — arc reactor के बीच 😄😔😌💪 और रंग बदलता है, status "खुश / दुखी / शांत / हिम्मत दे रहा"।
- **Memory**: आपका नाम, काम, चैनल और यादें Gemini को हर बार ताज़ा मिलती हैं।

### Voice Settings (कहाँ हैं)
Jarvis स्क्रीन के ऊपर **🎙️ (RecordVoiceOver) icon** दबाओ → "Jarvis Voice Settings"।
- **Voice Mode**: Free – फ़ोन की आवाज़ / Free – Gemini Voices / ElevenLabs / Smallest / HeyGen / **Smart**।
- **ElevenLabs API key** — आप खुद डालो (encrypted TokenVault में, कहीं hardcode नहीं)।
- **Voice ID** — default **Bunty - Funny Best Friend** (`7b9mYhmnp0y2qSH1FnBL`)।
- **मेरी clone voice ID** (Smallest) — permanent choice।
- **Gemini voice** — Free-Gemini mode के लिए।

**Smart mode**: रोज़ की बात = free phone voice; मज़बूत भावना (खुशी/दुख/हिम्मत) = ElevenLabs।
**क्रेडिट खत्म हों तो** ElevenLabs अपने आप block होकर फ़ोन वॉइस चली जाती है; क्रेडिट वापस आने पर खुद ElevenLabs। Jarvis कभी चुप नहीं होगा।
