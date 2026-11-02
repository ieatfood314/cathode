<img src="docs/logo.png" alt="" width="96" align="right">

# Cathode

Your subscriptions, auto-downloaded to your NAS, watchable offline on your phone. No Premium, no cellular data, no progress lost on bad Wi-Fi.

- **NAS downloader** (Docker, `server/`): polls your channels, grabs new uploads with **yt-dlp + SponsorBlock**, serves them over your LAN.
- **Android app** (`android/`, OLED black + glass): library of downloaded videos, in-app playback, auto-sync the moment you open it.
- **Generic by design**: point `data/subscriptions.opml` or `data/channels.txt` at your channels and it works. No personal data in this repo.

Inspired by [Pinchflat](https://github.com/kieraneglin/pinchflat) (auto-archive + SponsorBlock model) and [TubeArchivist](https://github.com/tubearchivist/tubearchivist) (self-hosted library model). Cathode is the lightweight alternative: single small server + a purpose-built offline-first phone app instead of Plex/Jellyfin + a heavy index.

## How it works

```
YouTube ──poll──▶ server (yt-dlp + SponsorBlock) ──Wi-Fi only──▶ Android app (offline copies on the phone)
  ▲                    │                                  │
  │ subscriptions      │ downloads on server link         │ streams/downloads
  │ (OPML/CSV/txt)     │ the moment videos drop          │ with Range resume
```

1. Server discovers uploads per channel (`yt-dlp --flat-playlist`, RSS-aware) on a schedule **or** when the app asks.
2. Server downloads with resume-safe flags (`--continuedl`, fragment retries, backoff) + SponsorBlock (removes `sponsor,selfpromo,interaction`, marks the rest as chapters so archive jobs stay fast).
3. App syncs **only on unmetered Wi-Fi** (WorkManager `UNMETERED` + runtime check), streams files with HTTP `Range`, and keeps **offline copies as plain .mp4 files** — shared `Movies/Cathode` by default, or any folder you pick (SD card, USB drive). A dropped connection resumes where it stopped — never from zero.

## Quick start (NAS)

```bash
cp .env.example .env
# Put YOUR subscriptions in (pick one; all free, no Premium):
#   data/subscriptions.opml  <- YouTube subscription-manager OPML export, or
#   data/subscriptions.csv   <- Google Takeout (see below), or
#   data/channels.txt        <- one channel URL per line (see channels.example.txt)
./scripts/install-nas.sh
curl -X POST http://<your-nas>:8000/api/subscriptions/refresh
```

Open `http://<your-nas>:8000/api/feed` — your library as JSON — or just open
`http://<your-nas>:8000/` in a desktop browser for the minimal web UI
(same library, player, and settings as the app; handy on a computer, no
offline sync there).

### Free subscription export (no Premium)

- **OPML (recommended)**: YouTube → Subscriptions → Manage → export, or `youtube.com/subscription_manager`. Save as `data/subscriptions.opml`.
- **Takeout CSV**: [Google Takeout](https://takeout.google.com) → YouTube → Subscriptions → `subscriptions.csv` → save as `data/subscriptions.csv`.
- **Manual**: copy channel URLs into `data/channels.txt`, one per line.

## Android app (Obtainium)

OLED black (`#000000`), flat panels, one violet accent. Bottom nav: Library / Channels / Playing / Stats / Settings.

**Saving to the phone** runs in the background: new uploads are saved after every sync (including the phone's own background sync), and a tapped **Save to phone** keeps going after you close the app. Each library row shows the channel and when the video was uploaded. For saves to run to the end with a progress notification, turn on Settings → **Background downloads** → Allow (this lifts Android's battery optimization for Cathode). Without that, Android stops a background save after about 10 minutes, and it resumes from the same spot next time.

**Stats** shows how long you've actually spent watching: today, the last 7 days, a daily average, all time, a two-week chart and your top channels. Only time spent playing counts (not paused or buffering), and time watched offline is added once the phone is back on Wi-Fi. The web UI's player counts too.

**Install via [Obtainium](https://apps.obtainium.imranr.dev/)** (update tracking, no store needed): add app → source GitHub → `https://github.com/ieatfood314/cathode` — it picks up each release APK automatically. Or download the APK from the [releases page](../../releases) and install directly.

```bash
cd android && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

In the app → Settings → enter your server URL (see Tailscale below), tap **Test connection**, then tune everything in place: polling mode + interval, download quality, SponsorBlock buttons, retention. Changes save to the server as you make them — no SSH or `.env` editing needed day to day. The Channels tab explains how to export your subscriptions and lets you add channels or upload the export file by hand.

## Remote access via Tailscale

The server binds your LAN by default. To reach it from any Wi-Fi (café, work — still never cellular, see below):

```bash
# One-time: share the NAS server on your tailnet over HTTPS
tailscale serve --bg --https=8443 http://127.0.0.1:8000
```

Then in the app → Settings → server URL `https://<your-nas>.<tailnet>.ts.net:8443` → **Test connection**. Wi-Fi-only still applies: Tailscale over Wi-Fi syncs fine; Tailscale over cellular stays paused until you're back on unmetered Wi-Fi.

### Polling controls (Settings)

| Mode | Behavior |
|---|---|
| On app open | NAS only scans when the app opens or you tap sync (`POST /api/subscriptions/refresh`) |
| Periodic | NAS scans every N minutes (you set N); the phone syncs in the background on the same schedule (min 15 on Android) |
| Instant | NAS scans every 5 minutes — new drops land within minutes |

The mode and interval live in Settings → Server config; the server also exposes `PUT /api/settings`.

### Zero cellular, bad-Wi-Fi proof

- **Wi-Fi only**: `NetworkType.UNMETERED` constraints and an `isUnmetered()` runtime gate (covers metered hotspots/VPNs). With the toggle ON, the app throws before any socket opens on cellular.
- **Resume**: server honors `Range` (`GET /api/videos/{id}/stream`), yt-dlp uses `--continue` + fragment retries, app downloads keep a `.part` file and resume with `Range: bytes=<done>-` + exponential backoff — also across failed attempts.
- **Offline library**: the app keeps the last synced library on the phone, so it opens and saved videos play with no network. Where you stopped (and what you finished) is queued and sent on the next sync.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | poll mode, last run |
| GET | `/api/feed?unwatched_only&channel&limit` | library (downloaded videos only) |
| GET | `/api/videos/{id}` | metadata |
| GET | `/api/videos/{id}/stream` | Range-capable file |
| GET | `/api/thumbs/{id}` | thumbnail |
| GET/PUT | `/api/settings` | poll_mode, poll_interval_minutes, resolution, SponsorBlock, keep_days |
| GET | `/api/subscriptions` | parsed inputs + indexed channels |
| POST | `/api/subscriptions/refresh` | starts a background scan, returns immediately (app calls on open) |
| POST | `/api/videos/{id}/progress` | `{"position_seconds": N}`; auto-marks watched near the end, never unmarks |
| POST | `/api/videos/{id}/watched?watched=true` | watch state (`false` also rewinds to the start) |
| POST | `/api/watchtime` | `{"sessions": [{id, video_id, day, seconds}]}` running totals per video per day; the larger total wins, so resending is safe |
| GET | `/api/stats?since=YYYY-MM-DD` | watch time per day and channel since that date, plus the all-time total |
| POST | `/api/subscriptions/upload` | `.opml` / `.csv` (replaces that file once it parses) or `.txt` (adds) |

## Privacy / self-host notes

- This repo contains **no personal data**: no IPs, hostnames, usernames, channel lists. `.env`, `data/`, `media/` are gitignored; only `.example` files are committed.
- Server binds LAN only by default; expose via Tailscale/WireGuard, not the public internet.
- YouTube breakage: downloads depend on yt-dlp; update with `docker compose build --pull` when YouTube changes.
- Only archive content you're entitled to keep.

## Upgrading from Ruminate

Cathode was called Ruminate. Existing servers keep working: `RUMINATE_*` env vars are still read (a `CATHODE_*` one wins if both are set), and a `ruminate.db` next to the configured database path is picked up automatically. The Android app has a new package id (`com.cathode.app`), so it installs alongside the old app; re-add it in Obtainium and uninstall Ruminate.
