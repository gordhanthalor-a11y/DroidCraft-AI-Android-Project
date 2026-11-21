# Jarvis acceptance audit — source inventory and proof status

**Status: NOT ACCEPTED.** This is a source-level audit, not proof that the app builds or works on a phone. A standalone syntax-only parser accepted the 55 Kotlin/Gradle-Kotlin source files checked; it does not type-check Android APIs or substitute for compilation. The Android/Java toolchain was unavailable in this workspace; no successful Gradle build, test run, APK, or device session is recorded.

## Actual Android destinations and navigation evidence

The active Compose destination state is in `app/src/main/java/com/droidcraft/app/ui/DroidCraftNavHost.kt`:

| Destination | Entry / exit evidence | Purpose |
|---|---|---|
| Jarvis home | Fallback branch renders `JarvisScreen` (around line 103); initial route is `"jarvis"` (line 44). | Orb, conversation, corner telemetry, activity strip, message input. |
| Settings | `"settings" -> SettingsScreen` (around line 94); reached from the Jarvis HUD gear. Back returns to Jarvis. | Appearance, provider configuration, voice, capabilities/permissions, health, memory entry. |
| Memories | `"memories" -> MemoriesScreen` (around line 102); entered from Settings using `onOpenMemories` (around line 100); back returns to Settings. | View/edit/delete local Jarvis memories. |

There is no bottom navigation, `MoreScreen`, home grid, or extra tools tab in `DroidCraftNavHost.kt`. `MainActivity.kt` is the single app activity entry. Accessibility and foreground services in the manifest are Android services, not additional Compose screens.

## Legacy creator/publishing feature removal checklist

| Feature / entry surface | Status | Source evidence |
|---|---:|---|
| Clipper | Removed | The Python backend and app client/screen sources are deleted; no clip route remains in the Android navigation or source tree. |
| Dubbing | Removed | The Python backend and app client/screen sources are deleted; no Android destination or menu entry exists. |
| Voice Studio / server TTS | Removed | The server TTS and app Studio TTS implementations/endpoints are deleted; normal Jarvis speech is separate in `ai/GeminiTtsClient.kt`. |
| Upload Helper / upload-QC screens | Removed | Studio UI, QC code, backend endpoints, and deployment files are deleted. |
| Script Writer | Removed | Studio implementation and screen are deleted; no route or menu entry remains. |
| Thumbnail tool | Removed | Studio thumbnail implementation and screen are deleted; no current entity or editing path remains. |
| Social publishing / OAuth / queues | Removed | The complete `app/social/` implementation, workers, OAuth flow, backend, and `SOCIAL_SETUP.md` are deleted. |
| Creator tools hub and stale setup docs | Removed/rewritten | `DroidCraftNavHost.kt` has only the three destinations above. `README.md`, `JARVIS.md`, and `ARCHITECTURE.md` describe the current Jarvis app rather than creator features. `app/studio/`, `app/social/`, `server/`, and `SOCIAL_SETUP.md` are absent. |

`AppDatabase.kt` is now memory-only at the Room entity/DAO level. Its one-time 6→7 migration contains `DROP TABLE IF EXISTS` statements for obsolete tables to preserve existing memory while cleaning older installations; it is migration cleanup, not retained feature logic. `APK_GENERATION_GUIDE.md` still names Android Studio as an IDE only; that is unrelated to an app destination or creator feature.

## Addendum #5 source status

- Persisted `Cyan` / `Amber Gold` selection: `ui/theme/OrbThemeSettings.kt`, exposed by the Appearance section in `SettingsScreen.kt`; the selected Material palette is consumed by `Theme.kt`, `JarvisMotion.kt`, and `JarvisOrb.kt`.
- Corner HUD reads current state, Android battery percentage, uptime, and a live clock/date in `JarvisScreen.kt`.
- The timestamped terminal strip opens the activity list and its persisted action-approval/rejection decisions from the same screen. General activity history remains in memory only.
- The dashed concentric plinth is source-gated to a running phone-action record with multiple recorded steps. It has not been visually/runtime tested.
- Settings exposes independent capability switches and notification access. `NotificationStore.openSettings(Context): Boolean` exists and launches Android's notification-listener settings; the callback and actual permission flow have not been device-tested.
- Screen composition has not been checked on different screen sizes, contrast settings, or font scales.

## Functionality evidence — not yet established

| Requirement | Source status | Independent proof |
|---|---|---|
| Charon speech | Gemini TTS Charon is configured as the default source voice and Android phone TTS is used in the emergency fallback path. | **None.** No successful Charon request or audible device playback is recorded. |
| Sustained Gemini Live | Source configures Live audio, transcription, VAD, resumption handles, context compression, and reconnect behavior. | **None.** No valid API-key session or sustained connection test is recorded. Model availability and exact wire-contract acceptance are unverified. |
| Completed phone action | Live tool calls are routed through the local intent mapper and action-safety gate; risky taps remain gated. | **None.** No phone, accessibility-service, permission, or action-result test is recorded. |
| Build / APK | The Gradle wrapper and CI workflow build a debug APK and verify its signature and SHA-256 after upload. CI uses Android's standard debug signing key; no private signing material is stored in this repository. | **None.** Gradle could not run because Java/Android SDK are unavailable here; no APK exists as verified output. Local and CI debug signatures may differ, so these artifacts are for testing rather than production updates. |

## Remaining acceptance blockers

- Compile and test all changed Kotlin/Compose/Room code with a supported Java and Android SDK toolchain; fix all errors before producing an APK.
- Device-test Charon output, sustained Live, 16 kHz input and 24 kHz output, interruptions, barge-in, session expiry/resume, and one allowed phone action with recorded evidence.
- Add and verify the optional ElevenLabs speech provider behind a provider-neutral speech interface; it is not wired in this checkout. Verify configured-provider switch notices and measure route durations on a device.
- Verify the new persistent tap-approval/rejection log, rule-based durable-fact extraction, memory reload before provider context, and per-capability switches; general activity history itself is not durable yet.
- Verify health reporting against runtime permission/service state, not just source declarations.
- Audit Live protocol/model compatibility against the active Google API contract; the current model IDs and JSON schema have not been accepted by a live service.

No APK delivery, speed claim, capability-complete claim, or acceptance claim is made by this audit.
