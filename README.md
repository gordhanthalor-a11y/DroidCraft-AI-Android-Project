# DroidCraft Jarvis for Android

DroidCraft is an Android project for a single Jarvis assistant experience built with Kotlin, Jetpack Compose, and Material 3. The current interface is being refactored around the Jarvis orb, conversation, and Settings; it is not an app for managing creator tools.

## Current project areas

- `app/src/main/java/com/droidcraft/app/ui/` — Compose interface and navigation.
- `app/src/main/java/com/droidcraft/app/jarvis/` — local command parsing, safety checks, device actions, memory, voice, and accessibility.
- `app/src/main/java/com/droidcraft/app/ai/` — Gemini clients, Live session, PCM audio, and provider-neutral Gemini/ElevenLabs speech output.
- `app/src/main/java/com/droidcraft/app/data/` — local settings and encrypted provider-key storage.

The old publishing/creator backend has been removed. An evidence-based screen inventory and removal checklist is maintained in `ACCEPTANCE_AUDIT.md`.

## Verification status

**Do not install or distribute build #67 as an update.** GitHub Actions run [44903689834](https://github.com/gordhanthalor-a11y/DroidCraft-AI-Android-Project/actions/runs/44903689834) produced an APK signed with Android's generic debug certificate, which is incompatible with existing DroidCraft-signed installs. The workflow is being changed to require the original DroidCraft key via Actions secrets and to verify its certificate subject before upload; no corrected-signature APK is available yet. Gemini or ElevenLabs speech, sustained Live, wake detection, and phone-control behavior also remain unverified on-device. Text-provider timing summaries measure complete reply attempts (including tool work); speech-provider timings measure synthesis requests and exclude audio playback. Neither is a model-only speed benchmark. The optional Vosk wake-phrase feature is disabled by default.

Build with the Gradle wrapper in an Android/Java development environment:

```sh
./gradlew :app:assembleDebug
```

API keys are user-supplied settings and must never be committed to source or build configuration. Grant Android permissions only when you intend to use the related capability.
