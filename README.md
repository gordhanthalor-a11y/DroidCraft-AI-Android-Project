# DroidCraft: AI YouTube Automation Assistant (Android + Backend)

A professional Android app (Kotlin, Jetpack Compose, Material 3, MVVM) that acts as a creator command center for automated YouTube workflows.

## Features

- **J.A.R.V.I.S. HUD**: Uses the supplied Mark 85 arc-core/orb interface as the main screen, with native Android actions for Live Talk, history, Settings, and Creator Studio.
- **Jarvis Live Talk**: Continuous Gemini Live voice conversation with Hindi, Hinglish, and English audio transcription; typed input is available in the conversation history.
- **Live voice choice**: Select **Gemini Live (free)** or **ElevenLabs (my voice)** in Settings. ElevenLabs speaks with Bunty voice ID `7b9mYhmnp0y2qSH1FnBL`; if it runs out of credits or becomes unavailable, the same conversation falls back to Gemini's free preset voice.
- **Gemini AI**: Chat and creator text generation use `gemini-3.8-flash`; Live audio uses Google's Live-specific `gemini-3.8-live` model.
- **Settings-only AI keys**: Gemini and ElevenLabs keys are entered in Settings and encrypted with Android Keystore; they are not embedded in app code. ElevenLabs keys are sent over HTTPS directly to ElevenLabs.
- **Jarvis device assistant**: Hindi/Hinglish/English commands for apps, alarms, timers, weather, notes, flashlight, battery, contacts/calls/SMS, memory, and accessibility automation.
- **Hey Jarvis**: Background wake-word foreground service.
- **Jarvis Social**: Social-account connections, captions, scheduling, publish queue, and activity history.
- **Creator Studio** — eight sections in the Studio hub:
  - **Voice Studio**: Hindi script to generated speech, playback, save, and share.
  - **Upload QC**: Local video checks plus server-backed SAR/DAR and loudness QC.
  - **Dubbing**: Source link, transcript, script editing, voice selection, and render.
  - **Clipper**: Create and download vertical clips from a source link.
  - **Script Writer**: Gemini-assisted Hindi scripts with an opening hook.
  - **Thumbnail Text**: Add large high-contrast text to a thumbnail.
  - **Channel Manager**: Channel shortcuts and campaign tracking.
  - **Daily**: Job applications and Hindi notes/reminders.
- **8-stage video pipeline**, creator approval gate, resumable uploads, and multi-channel YouTube management.

## Creator Studio backend

Voice Studio, server QC, Dubbing, and Clipper require the Python service in `server/`. Deploy it using `server/SERVER_DEPLOY.md`, then enter its HTTPS base URL under **Settings → Creator Studio server**. The Upload QC screen retains local checks when the server is not configured.

## Build

The GitHub Actions workflow builds the Android APK. API keys must only be entered by the user in the app's Settings; never add them to source code or build configuration.
