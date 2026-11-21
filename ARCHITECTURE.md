# DroidCraft Jarvis architecture

This describes the Kotlin/Android source as checked out. It is not a build or device-test report; `ACCEPTANCE_AUDIT.md` records what is and is not independently verified.

## UI and navigation

`ui/DroidCraftNavHost.kt` has one home destination: `JarvisScreen`. Settings is opened from the Jarvis HUD. The memory-management screen is reachable from Settings and returns there. There is no bottom navigation or home tools grid. The Settings screen contains provider, voice, appearance, permissions, health, and memory entry points.

The Jarvis HUD is composed in `ui/JarvisScreen.kt`; the orb is `ui/JarvisOrb.kt`. `ui/theme/OrbThemeSettings.kt` persists the Cyan / Amber Gold selection. `ui/theme/Theme.kt`, `ui/JarvisMotion.kt`, and `ui/JarvisOrb.kt` consume the selected Material palette for active lighting.

## Assistant flow

`jarvis/JarvisCommandParser.kt` handles supported local commands. Unhandled conversation goes through `jarvis/JarvisBrain.kt`, which uses `AiProviderSettings` to build configured provider routes and retries a configured next provider after failure. Phone tool calls are converted by `jarvis/JarvisToolIntents.kt`, checked by `jarvis/a11y/ActionSafety.kt`, then passed to `JarvisDevice` and the relevant Android capability.

Provider keys use the local encrypted secure-storage implementation. `AiProviderTelemetry` stores measured end-to-end text-provider route attempt durations, including tool round-trips. `SpeechSynthesisProvider` abstracts Gemini and ElevenLabs audio behind one 24 kHz PCM contract; `SpeechSynthesisRouter` announces provider fallback, uses Android phone TTS only after synthesis failure, and `SpeechProviderTelemetry` records measured synthesis-request durations. Displayed averages are derived from device measurements and remain absent until a route attempt completes.

## Audio and Live session

`ai/GeminiLiveVoiceSession.kt` builds the Live WebSocket setup and handles streamed transcripts, text or binary JSON frames, audio, tool calls, cancellation, and session resumption. Its microphone path requests mono PCM16 capture, uses Android AcousticEchoCanceler where available, applies the local playback-leak gate, and converts device capture to the 16 kHz wire rate. The gate tracks PCM playback and Android TTS fallback playback. `ai/PcmAudioPlayer.kt` writes 24 kHz mono PCM16 playback in bounded chunks; `ai/Pcm16Resampler.kt` is the edge-rate conversion helper. Gemini TTS Charon is the default selection; optional ElevenLabs synthesis shares the provider-neutral PCM interface and can switch back to Gemini with a visible notice. Android system speech is retained as an explicitly identified emergency fallback.

`jarvis/wake/WakeWordService.kt` is an optional foreground service controlled by a capability switch that defaults off. It uses on-device Vosk wake detection; after the wake phrase, `MainActivity` asks Android's system speech recognizer for the spoken command.

These code paths still require compile, API, and device verification. Their presence in source does not establish provider availability, voice output, stable Live duration, or successful tool execution.

## Local storage and access control

`data/local/AppDatabase.kt` stores user memories. Database migration 6→7 keeps memory while dropping retired app tables. `jarvis/memory/MemoryManager.kt` and `ui/MemoriesScreen.kt` provide the current memory path.

Phone interaction is isolated behind Android permission/service checks. Accessibility, microphone, contacts, calendar, notifications, overlay, reminder, and optional offline wake paths must be enabled and tested individually. The wake phrase itself is processed on-device; Android speech recognition handles the command after wake. Sensitive screen actions are subject to the local action-safety gate; a model response is never authorization.

## Build and proof

Use `./gradlew :app:assembleDebug` with a configured Android SDK and Java toolchain. Debug builds use Android's standard machine-local debug key; no private signing material is checked into this repository. CI run [44903316005](https://github.com/gordhanthalor-a11y/DroidCraft-AI-Android-Project/actions/runs/44903316005) built a debug APK and verified its uploaded/downloaded signature and byte identity for commit `c83d13d`. No device install or runtime evidence for speech providers, sustained Live, wake detection, or phone actions is available. See `ACCEPTANCE_AUDIT.md` before making acceptance or release claims.
