# WhatsApp Reply Assistant

Personal Android assistant that:

- Reads **WhatsApp** context from **notifications** and **Accessibility** (on-screen chat).
- Calls a **Node.js** backend for **three** AI reply suggestions (**Gemini** if `GEMINI_API_KEY` is set, otherwise **OpenAI**-compatible API).
- Shows suggestions in an **overlay**; tapping tries to **fill the WhatsApp compose field** (Accessibility) or **copies to clipboard**.

## Layout

This project uses a **single Git repository** at the repo root (no nested `android/` Git repo).

| Folder | Description |
|--------|-------------|
| [`backend/`](backend/) | Fastify API: `POST /v1/suggest`, bearer auth, Gemini or OpenAI |
| [`android/`](android/) | Kotlin app: notification listener, accessibility service, overlay |
| [`releases/`](releases/) | Optional: copy your built `app-debug.apk` here to version it in Git (see [`releases/README.md`](releases/README.md)) |

## Quick start

### Backend

1. Copy `backend/.env.example` → `backend/.env` and set `APP_SECRET`, plus either **`GEMINI_API_KEY`** (preferred when set) or **`OPENAI_API_KEY`**, and optional `GEMINI_MODEL`, `OPENAI_BASE_URL` / `MODEL`. The Android app sends **`mode`** (`standard` \| `brief` \| `professional`) on `POST /v1/suggest` to pick the system-prompt variant.
2. Run: `cd backend && npm install && npm run dev`
3. Deploy behind HTTPS (Railway, Fly.io, Render, etc.) and use the public URL in the Android app.

### Android

1. Install **JDK 17** and **Android Studio**.
2. Open the `android/` folder in Android Studio and sync Gradle.
3. Run on a device: enable **Reply Assistant** in **Notification access**, **Accessibility**, and **Display over other apps** (from the in-app buttons).
4. Enter your **HTTPS base URL** (no trailing slash) and **Bearer token** (same value as `APP_SECRET` on the server). Save.

More detail: [`android/README.md`](android/README.md).

### APK in Git

After `./gradlew :app:assembleDebug`, copy `android/app/build/outputs/apk/debug/app-debug.apk` to `releases/app-debug.apk` and commit it. The Gradle `build/` folder stays ignored to avoid huge binary churn.

## Security

- The app sends message text to **your** backend; keep `APP_SECRET` private and use HTTPS in production.

## Legal

WhatsApp does not provide a supported API for this. Use at your own risk and comply with applicable terms and laws.
