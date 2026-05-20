# Build Gallery

This guide shows the basic commands to build and install the Android app.

## Prerequisites

- Android SDK installed
- JDK 11 or newer
- `adb` available on your path

## 1. Open the Android project

Open a terminal in:

```bash
Android/src
```

## 2. Build the debug APK

Run:

```bash
./gradlew :app:assembleDebug
```

If you only want a faster compile check, run:

```bash
./gradlew :app:compileDebugKotlin
```

## 3. Install the APK on the phone

Run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Launch the app

If the app is already installed, you can launch it with:

```bash
adb shell monkey -p com.google.aiedge.gallery -c android.intent.category.LAUNCHER 1
```

## 5. Confirm the version

The debug build embeds the git commit hash in the version name.

To inspect it from the APK, run:

```bash
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName
```

The version name format is:

```text
1.0.14-<git-hash>
```

If the worktree is dirty, the version name ends with `+`.

## Notes

- Build output goes to `app/build/outputs/apk/debug/app-debug.apk`
- The app package name is `com.google.aiedge.gallery`
