# DroidCraft Android APK Generation Guide

These commands describe how to request a debug APK; they are not evidence that this checkout has built successfully.

### Option 1: Automated Cloud Build
1. Push this project code to the repository's `main` or `master` branch, or run the workflow manually from GitHub Actions.
2. The included `.github/workflows/build-apk.yml` builds and verifies a debug APK.
3. Open the **Actions** tab, select the completed run, and download **DroidCraft-app-debug.apk**.
4. Transfer it to your Android phone and install it for testing.

The workflow uses Android's standard debug signing key, not a repository-held key. A debug APK is for testing only and is not a production release. Debug keys may differ between machines and CI runs, so an APK may not update an installation signed elsewhere. Production distribution requires a separately managed private release key stored in secure signing infrastructure; never commit that key or its password.

### Option 2: Local Android Studio Build
1. Click **Export Project (.ZIP)** in DroidCraft and unzip the archive.
2. Open **Android Studio** (Hedgehog, Iguana, or Ladybug).
3. Select **File > Open** and choose the unzipped folder.
4. Allow Gradle sync to complete (the Gradle wrapper selects Gradle 8.10.2; use JDK 17).
5. In the top menu, select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. The compiled APK will be created at:
   `app/build/outputs/apk/debug/app-debug.apk`
7. Run directly on your connected physical Android phone via USB debugging or install with `adb install app-debug.apk`.