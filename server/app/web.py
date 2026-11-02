"""Web UI (desktop and phone companion for the Android app).

Same API, same library, same look: the phosphor-tube design from the app,
as a dependency-free page in static/ (index.html, cathode.css, cathode.js,
and the VT323 font, OFL). No offline sync and no Wi-Fi gating: it assumes
you're already on a trusted network, so never expose this port publicly.
"""
from pathlib import Path

STATIC = Path(__file__).parent / "static"

# Served at /assets/<name>; anything else 404s.
ASSETS = {
    "cathode.css": "text/css; charset=utf-8",
    "cathode.js": "text/javascript; charset=utf-8",
    "vt323.ttf": "font/ttf",
}

PAGE = (STATIC / "index.html").read_text(encoding="utf-8")
