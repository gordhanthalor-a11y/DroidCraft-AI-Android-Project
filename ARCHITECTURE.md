# DroidCraft Jarvis architecture

This describes the Kotlin/Android source as checked out. It is not a build or device-test report; `ACCEPTANCE_AUDIT.md` records what is and is not independently verified.

## UI and navigation

`ui/DroidCraftNavHost.kt` has one home destination: `JarvisScreen`. Settings is opened from the Jarvis HUD. The memory-management screen is reachable from Settings and returns there. There is no bottom navigation or home tools grid. The Settings screen contains provider, voice, appearance, permissions, health, and memory entry points.

The Jarvis HUD is composed in `ui/JarvisScreen.kt`; the orb is `ui/JarvisOrb.kt`. `ui/theme/OrbThemeSettings.kt` persists the Cyan / Amber Gold selection. `ui/theme/Theme.kt`, `ui/JarvisMotion.kt`, and `ui/JarvisOrb.kt` consume the selected Material palette for active lighting.

## Assistant flow

`jarvis/JarvisCommandParser.kt` handles supported local commands. Unhandled conversation goes through `jarvis/JarvisBrain.kt`, which uses `AiProviderSettings` to build configured provider routes and retries a configured next provider after failure. Phone tool calls are converted by `jarvis/JarvisToolIntents.kt`, checked by `jarvis/a11y/ActionSafety.kt`, then passed to `JarvisDevice` and the relevant Android capability.

Provider keys use the existing local secure-storage implementation. `AiProviderTelemetry` stores measured end-to-end provider-route attempt durations, totals, and counts (including any tool round-trips); displayed averages are derived from those measurements and are absent until a route attempt has actually completed.

## Audio and Live session

`ai/GeminiLiveVoiceSession.kt` builds the Live WebSocket setup and handles streamed transcripts, audio, tool calls, cancellation, and session resumption. Its microphone path requests mono PCM16 capture, uses Android AcousticEchoCanceler where available, applies the local playback-leak gate, and converts device capture to the 16 kHz wire rate. The gate tracks both PCM playback and Android TTS fallback playback. `ai/PcmAudioPlayer.kt` writes 24 kHz mono PCM16 playback in bounded chunks. `ai/Pcm16Resampler.kt` is the edge-rate conversion helper. Gemini TTS Charon is the intended default voice; Android system speech is retained as an explicitly identified emergency fallback.

These code paths still require compile, API, and device verification. Their presence in source does not establish provider availability, voice output, stable Live duration, or successful tool execution.

## Local storage and access control

`data/local/AppDatabase.kt` stores user memories. Database migration 6→7 keeps memory while dropping retired app tables. `jarvis/memory/MemoryManager.kt` and `ui/MemoriesScreen.kt` provide the current memory path.

Phone interaction is isolated behind Android permission/service checks. Accessibility, microphone, contacts, calendar, notifications, overlay, and reminder paths must be enabled and tested individually. Sensitive screen actions are subject to the local action-safety gate; a model response is never authorization.

## Build and proof

Use `./gradlew :app:assembleDebug` with a configured Android SDK and Java toolchain. Debug builds use Android's standard machine-local debug key; no private signing material is checked into this repository. The CI workflow verifies the produced/uploaded artifact, but this checkout has not yet produced a successful build or APK, or runtime evidence for Charon, sustained Live connection, or a completed phone action. See `ACCEPTANCE_AUDIT.md` before making acceptance or release claims.
