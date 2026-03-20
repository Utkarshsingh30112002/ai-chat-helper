# Reply Assistant (Android)

## Requirements

- JDK 17+
- **Android SDK** — install via [Android Studio](https://developer.android.com/studio) (SDK Manager). Command-line builds need the SDK path:
  - Create `local.properties` in this folder (see `local.properties.example`) with  
    `sdk.dir=/Users/YOUR_USER/Library/Android/sdk` on macOS (default Studio location),  
    or set `ANDROID_HOME` and use that path for `sdk.dir`.
- Android Studio (recommended) or Gradle wrapper in this folder

## Build

```bash
./gradlew :app:assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/`.

## Permissions (grant in system settings)

1. **Display over other apps** — overlay with three suggestions.
2. **Notification access** — capture incoming WhatsApp notification text when not in chat.
3. **Accessibility** — read on-screen chat when WhatsApp is open and inject text into the compose field.

Also allow **notifications** for the foreground service (Android 13+).

## Configuration

Open the app and set:

- **HTTPS base URL** — e.g. `https://your-api.example.com` (no trailing slash).
- **Bearer token** — must match `APP_SECRET` on the Node server.

Toggles control whether notification capture and accessibility capture are active.

## Troubleshooting

- **No suggestions:** Confirm URL/token, backend `/health`, and that WhatsApp package is `com.whatsapp` or Business `com.whatsapp.w4b`.
- **Inject fails:** WhatsApp UI varies; the app falls back to **clipboard** and shows a toast — paste manually into the message field.
- **SDK location not found:** Add `local.properties` with `sdk.dir=...` (see above) or open the project once in Android Studio so it generates the file.
- **Diagnostics:** The app writes lines to **internal storage** `files/app_events.log` and POSTs errors to **`POST /v1/client-log`** on your backend (same bearer token). Check `logs/client.log` on the server.
