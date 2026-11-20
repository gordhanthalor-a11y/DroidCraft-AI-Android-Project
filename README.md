# DroidCraft: AI YouTube Automation Assistant (Android + Backend)

A professional Android app (Kotlin, Jetpack Compose, Material 3, MVVM) that acts as a creator command center for automated YouTube workflows.

## Features
- **J.A.R.V.I.S. Personal Assistant**: Apna Iron Man-style voice assistant — arc-reactor UI, Hindi/Hinglish/English voice input (mic), khud bolta hai (Text-to-Speech), aur device par asli kaam karta hai: app kholo, call/SMS, alarm, timer, mausam (live open-meteo), notes, flashlight, battery, device info. Bina API key ke bhi chalta hai; Gemini API key lagao to poora dimag (baat-cheet + creator tasks) active ho jata hai.
- **Jarvis Live**: Gemini Live (BidiGenerateContent WebSocket) se realtime voice — 16kHz PCM mic, 24kHz streaming playback, barge-in, session transcripts.
- **Hey Jarvis**: Background wake-word foreground service — bolte hi sunna shuru.
- **Contacts & Memory**: Naam se call/SMS (papa, mummy...), Room-backed yaadein (`remember that ...`).
- **Screen Automation**: Accessibility service se screen padho (`screen dikhao`) aur text par click (`tap on WhatsApp`).
- **Bilingual AI Assistant**: Hindi & English voice/text natural-language command understanding with modular action execution.
- **8-Stage Pipeline**: Ideas -> Scripts -> Generation -> Quality Check -> Ready to Upload -> Scheduled -> Published -> Failed.
- **Creator Approval Gate**: Holds videos in *Ready to Upload* by default; requires explicit creator approval before publishing.
- **Resumable Uploads**: WorkManager background uploads with SHA-256 duplicate detection.
- **Multi-Channel Management**: Manage multiple YouTube channels connected via OAuth 2.0.
- **Secure Architecture**: Client secrets kept on backend orchestrator; Android app uses OAuth tokens and authenticated endpoints.