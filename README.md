# Reply Assistant (Android)

## Requirements

- JDK 17+
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
# ai-chat-helper
