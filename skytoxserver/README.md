# skyTox Push Server

Minimal portable wake-up gateway for skyTox.

The server does not store chats, files, contacts, or message text. It only accepts a request from skyTox and asks Firebase Cloud Messaging to wake the target Android device.

## Folder Portability

You can copy the whole `skytoxserver` folder to another PC/VPS.

Required on the target machine:

- Node.js 20 or newer
- Internet access
- `serviceAccount.json` from Firebase
- `.env` copied from `.env.example`

If `node_modules` is copied together with the folder, run `start-server.bat`. If it is missing or the OS changed, run `install-libraries.bat` once.

## Setup

1. Create a Firebase project.
2. Add Android app package `markanddiego.skytox`.
3. Create a Firebase service account key JSON.
4. Put it here as `serviceAccount.json`.
5. Copy `.env.example` to `.env`.
6. Change `SKYTOX_PUSH_API_KEY` to a long random value.
7. Keep `SKYTOX_DRY_RUN=true` for local testing without Firebase.
8. Set `SKYTOX_DRY_RUN=false` when `serviceAccount.json` is ready.
9. Run `start-server.bat`.

## Test

Health check:

```powershell
curl http://127.0.0.1:8787/health
```

Push request example:

```powershell
curl -X POST http://127.0.0.1:8787/push `
  -H "Content-Type: application/json" `
  -H "X-SkyTox-Key: your-secret" `
  -d "{\"token\":\"FCM_DEVICE_TOKEN\"}"
```

## Notes

- Never put Firebase service account keys into the Android APK.
- Never publish `.env` or `serviceAccount.json`.
- Put the server behind HTTPS before public use.
- For public deployment, use a VPS/domain and reverse proxy such as Caddy or nginx.
