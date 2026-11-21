# Jarvis on DroidCraft Android

This document describes the intended assistant behavior and the source layout. It is not a claim that the current checkout builds or that a capability has passed a phone test; see `ACCEPTANCE_AUDIT.md` for evidence status.

## Interaction model

Jarvis is intended to be one voice-first assistant. Simple supported commands should be handled locally; conversation may use the configured Gemini provider. Phone interaction is a separate capability and must be explicitly enabled by the user. Model output, visible screen text, and notifications are untrusted input, not authorization.

A phone action must be directly requested in the current user message. Sensitive or irreversible screen controls must be blocked or paused for a user confirmation. Jarvis must not silently send messages, place calls, submit forms, pay, purchase, or social-react. It must report success only when the local operation returns a successful result.

## Voice and Live session

The intended default speech voice is Gemini TTS Charon. Speech output is kept separate from conversation logic so optional providers and Android system speech can be routed without changing the assistant's intent/safety layer. Android system TTS is an emergency fallback and should be identified to the user when used.

Live audio is intended to use mono PCM16 at 16 kHz for microphone input and 24 kHz for playback, with device-boundary resampling if a device requires it. Microphone access, Android acoustic echo cancellation, software playback-leak gating, interruption flush, and session resumption must be verified on supported devices before being described as operational.

## Permissions and data

Microphone, accessibility, contacts, notifications, calendar, overlays, and reminders are separate Android capabilities. Request a permission only for a feature the user invokes. Memory is intended to remain user-reviewable and deletable on device. Provider keys must not be committed to the repository.

## Source map

- `jarvis/JarvisCommandParser.kt` — direct local command parsing.
- `jarvis/JarvisBrain.kt` and `jarvis/JarvisToolIntents.kt` — provider-driven replies and the local tool-intent mapping.
- `jarvis/a11y/ActionSafety.kt` and `jarvis/a11y/JarvisAccessibilityService.kt` — action checks and accessibility integration.
- `jarvis/JarvisDevice.kt` — Android-side device operations.
- `jarvis/memory/MemoryManager.kt` and `data/local/AppDatabase.kt` — local memory persistence.
- `ai/GeminiLiveVoiceSession.kt`, `ai/PcmAudioPlayer.kt`, and `ai/Pcm16Resampler.kt` — Live protocol and audio path.
- `ui/JarvisScreen.kt`, `ui/JarvisOrb.kt`, and `ui/DroidCraftNavHost.kt` — main screen, orb, and routes.

The inventory of actual destinations, deleted legacy endpoints, and remaining verification gaps is in `ACCEPTANCE_AUDIT.md`.
