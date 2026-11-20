# DroidCraft: AI YouTube Automation Assistant (Android + Backend)

A professional Android app (Kotlin, Jetpack Compose, Material 3, MVVM) that acts as a creator command center for automated YouTube workflows.

## Features
- **Jarvis Live Talk**: Gemini Live streams microphone audio and replies in real time, with Hindi, Hinglish, and English transcription.
- **Live Voice choice**: Select **Gemini Live (free)** or **ElevenLabs (my voice)** in Settings. ElevenLabs speaks replies with the configured Bunty voice and expressive style; if its service or credits run out, the same conversation switches to Gemini's preset voice without reconnecting.
- **Settings-only API keys**: Gemini and ElevenLabs API keys are entered in Settings, stored encrypted with Android Keystore, and never embedded in the source. The ElevenLabs key is sent only to ElevenLabs over HTTPS.
- **Bilingual AI Assistant**: Hindi & English text and voice command understanding alongside Live Talk.
- **8-Stage Pipeline**: Ideas -> Scripts -> Generation -> Quality Check -> Ready to Upload -> Scheduled -> Published -> Failed.
- **Creator Approval Gate**: Holds videos in *Ready to Upload* by default; requires explicit creator approval before publishing.
- **Resumable Uploads**: WorkManager background uploads with SHA-256 duplicate detection.
- **Multi-Channel Management**: Manage multiple YouTube channels connected via OAuth 2.0.
- **Secure Architecture**: Client secrets kept on backend orchestrator; Android app uses OAuth tokens and authenticated endpoints.