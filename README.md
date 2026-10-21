# DroidCraft: AI YouTube Automation Assistant (Android + Backend)

A professional Android app (Kotlin, Jetpack Compose, Material 3, MVVM) that acts as a creator command center for automated YouTube workflows.

## Features
- **Bilingual AI Assistant**: Hindi & English voice/text natural-language command understanding with modular action execution.
- **8-Stage Pipeline**: Ideas -> Scripts -> Generation -> Quality Check -> Ready to Upload -> Scheduled -> Published -> Failed.
- **Creator Approval Gate**: Holds videos in *Ready to Upload* by default; requires explicit creator approval before publishing.
- **Resumable Uploads**: WorkManager background uploads with SHA-256 duplicate detection.
- **Multi-Channel Management**: Manage multiple YouTube channels connected via OAuth 2.0.
- **Secure Architecture**: Client secrets kept on backend orchestrator; Android app uses OAuth tokens and authenticated endpoints.