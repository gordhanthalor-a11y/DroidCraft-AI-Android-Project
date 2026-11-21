# DroidCraft Jarvis for Android

DroidCraft is an Android project for a single Jarvis assistant experience built with Kotlin, Jetpack Compose, and Material 3. The current interface is being refactored around the Jarvis orb, conversation, and Settings; it is not an app for managing creator tools.

## Current project areas

- `app/src/main/java/com/droidcraft/app/ui/` — Compose interface and navigation.
- `app/src/main/java/com/droidcraft/app/jarvis/` — local command parsing, safety checks, device actions, memory, voice, and accessibility.
- `app/src/main/java/com/droidcraft/app/ai/` — Gemini clients, Live session, PCM audio, and provider-neutral Gemini/ElevenLabs speech output.
- `app/src/main/java/com/droidcraft/app/data/` — local settings and encrypted provider-key storage.

The old publishing/creator backend has been removed. An evidence-based screen inventory and removal checklist is maintained in `ACCEPTANCE_AUDIT.md`.

## Verification status

Source edits in this checkout have **not** been independently verified as an installable app. A successful Android build, APK, Gemini or ElevenLabs speech test, sustained Live session, and completed phone-control test are not available as evidence. Do not interpret source configuration as proof that a provider or device action works on a phone. Text-provider timing summaries measure complete reply attempts (including tool work); speech-provider timings measure synthesis requests and exclude audio playback. Neither is a model-only speed benchmark. The optional Vosk wake-phrase feature is disabled by default and also requires device verification.

Build with the Gradle wrapper in an Android/Java development environment:

```sh
./gradlew :app:assembleDebug
```

API keys are user-supplied settings and must never be committed to source or build configuration. Grant Android permissions only when you intend to use the related capability.
