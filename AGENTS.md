# Notes for coding agents

The README's "How it works" and "API" sections give the big picture. This file covers where the code lives and what tends to go wrong.

## Where things live
- **`server/app/`** is a FastAPI server. It runs in Docker (`docker-compose.yml`, `server/Dockerfile`).
  - `main.py`: the API routes, resumable file serving and poll triggers.
  - `poller.py`: finds new uploads and downloads them right away.
  - `downloader.py`: yt-dlp and SponsorBlock.
  - `subscriptions.py`: reads OPML, CSV and txt subscription lists.
  - `db.py`: the SQLite schema and migrations.
  - `config.py`: the `CATHODE_*` environment settings.
- **`server/app/static/`** is the web UI. `cathode.js` (about 46 KB) is divided by `// ----- name` banners. Grep for the section you need instead of reading the whole file.
- **`server/tests/test_server.py`** is the server test suite. CI runs `PYTHONPATH=server pytest server/tests -q`, which expects `/data` and `/media` to exist and be writable.
- **`android/app/src/main/java/com/cathode/app/`** is the Jetpack Compose app.
  - `data/`: API client, offline library, downloads store and watch time.
  - `player/`: playback and its service.
  - `ui/`: one file per screen, plus `Components.kt` and `Theme.kt`.
  - `worker/`: background sync and downloads.

## Things that bite
- **The library rules are written twice.** They live in the app (`data/Library.kt`) and in the web UI (the "library logic" section of `cathode.js`). Change both, or the phone and the browser show different libraries.
- **Every push to `main` publishes a signed release APK.** The `build` workflow names it `<versionName>.<1000 + run number>`, and Obtainium installs it.
  - To start a new version line, edit the `versionName` fallback in `android/app/build.gradle.kts`. Don't tag by hand.
  - Push when a change is ready for the phone, not halfway through.
- **The live server's database can be lost.** The server builds from this checkout, and its database holds watch history.
  - Back up `cathode.db` before a migration that could lose data.
  - Check `/api/health` after a rebuild.
- **The UI copy is deliberately short.** Labels are short, explanations sit behind an (i) button, and the look is flat with one accent color. Match it.
