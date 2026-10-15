#!/usr/bin/env bash
# NAS setup: Docker + yt-dlp server. Idempotent. No personal data inside.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p data media
[ -f .env ] || cp .env.example .env
[ -f data/channels.txt ] || cp channels.example.txt data/channels.txt
[ -f data/subscriptions.opml ] || cp subscriptions.example.opml data/subscriptions.opml

docker compose up -d --build
echo "Ruminate server on http://<your-nas>:${RUMINATE_PORT:-8000}"
echo "Point the Android app at that URL (over Wi-Fi / Tailscale)."
echo "Trigger a first scan: curl -X POST http://<your-nas>:${RUMINATE_PORT:-8000}/api/subscriptions/refresh"
