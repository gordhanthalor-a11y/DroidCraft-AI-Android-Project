# DroidCraft Android APK Generation Guide

Follow these steps to generate the real, installable Android APK on your phone:

### Option 1: Automated Cloud Build (100% Free, No Android Studio needed)
1. Push this project code to any GitHub repository.
2. The included `.github/workflows/build-apk.yml` will automatically run.
3. Open the **Actions** tab on GitHub -> click the latest run -> download **DroidCraft-app-debug.apk**.
4. Transfer to your Android phone and install!

### Option 2: Local Android Studio Build
1. Click **Export Project (.ZIP)** in DroidCraft and unzip the archive.
2. Open **Android Studio** (Hedgehog, Iguana, or Ladybug).
3. Select **File > Open** and choose the unzipped folder.
4. Allow Gradle sync to complete (uses Gradle 8.9 & JDK 17).
5. In the top menu, select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. The compiled APK will be created at:
   `app/build/outputs/apk/debug/app-debug.apk`
7. Run directly on your connected physical Android phone via USB debugging or install with `adb install app-debug.apk`.