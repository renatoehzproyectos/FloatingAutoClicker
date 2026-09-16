# Floating Auto Clicker

Native Android floating auto-clicker that works over other apps using AccessibilityService + Overlay.

Polished dark utility UI with clear status, permission indicators, and high-frequency click engine.

## Installation

1. Go to the repository **Actions** tab.
2. Open the latest **Build APK** workflow run.
3. Download the artifact **FloatingAutoClicker-debug**.
4. Install the APK on your device (enable "Install from unknown sources" if needed).

5. Open the app and grant:
   - **Display over other apps** (Overlay permission)
   - **Accessibility** service (Floating Auto Clicker)

6. Tap **Configure position** → drag the red marker to the desired location → tap **Save position**.
7. Adjust **Hold duration** and **Clicks per second**.
8. Press **Start auto clicker**.

## Controls

- **Hold duration**: How long each tap is held down (0–1000 ms). Lower values enable extremely fast taps.
- **CPS**: Target clicks per second (1–1000+). The engine tries to achieve the highest practical rate the device allows.
- Floating panel can be dragged and used to start/stop without returning to the app.

## GitHub Actions

- Workflow file: `.github/workflows/build.yml`
- Triggers on push and manual `workflow_dispatch`.
- Produces a debug APK as artifact.

Path: **Actions → Build APK → Artifacts → FloatingAutoClicker-debug**

## Technical notes

- Uses `AccessibilityService` + `GestureDescription` for real taps.
- Overlay via `WindowManager` + `TYPE_APPLICATION_OVERLAY`.
- Foreground service keeps the overlay alive.
- Settings (Hold, CPS, last click coordinates) are persisted automatically.
- High CPS values are limited only by device/Android gesture dispatch capacity; no artificial low caps.

## Requirements

- Android 8.0+ (API 26)
- Overlay + Accessibility permissions

## Build locally

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
