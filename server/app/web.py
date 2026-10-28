"""Minimal web UI (desktop companion for the Android app).

Same API, same library — a single dependency-free page for occasionally
watching from a computer on the LAN/tailnet. Not a replacement for the app:
no offline sync, no Wi-Fi gating (it assumes you're already on a trusted
network — never expose this port publicly).
"""

PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Cathode</title>
<style>
:root { color-scheme: dark; }
body { background: #000; color: #eee; font-family: system-ui, sans-serif; margin: 0; padding: 16px; }
h1 { font-size: 1.4rem; }
.bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
button, select, input { background: #1a1a22; color: #eee; border: 1px solid #33333f; border-radius: 10px; padding: 8px 12px; }
button { cursor: pointer; }
button:hover { border-color: #8b7cff; }
#status { color: #5eead4; font-size: .85rem; }
#watch { color: #999; font-size: .85rem; margin: -4px 0 12px; }
#grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; margin-top: 12px; }
.card { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 16px; padding: 10px; cursor: pointer; }
.card img { width: 100%; border-radius: 10px; aspect-ratio: 16/9; object-fit: cover; background: #111; }
.card .t { font-size: .9rem; margin: 6px 0 2px; }
.card .m { font-size: .75rem; color: #999; }
.card.watched { opacity: .55; }
#player { position: sticky; top: 0; background: #000; padding-bottom: 12px; display: none; }
#player video { width: 100%; max-height: 60vh; background: #000; border-radius: 12px; }
#settings { margin-top: 20px; border-top: 1px solid #222; padding-top: 12px; display: grid; gap: 8px; max-width: 480px; }
#settings label { font-size: .8rem; color: #aaa; }
#settings input, #settings select { width: 100%; box-sizing: border-box; }
#s_status { color: #5eead4; font-size: .85rem; }
</style>
</head>
<body>
<h1>Cathode</h1>
<div id="watch"></div>
<div id="player">
  <video id="vid" controls preload="none"></video>
  <div class="bar" style="margin-top:8px">
    <span id="now"></span>
    <button onclick="markWatched(true)">Mark watched</button>
    <button onclick="closePlayer()">Close</button>
  </div>
</div>
<div class="bar">
  <button onclick="syncNow()">Sync now</button>
  <label><input type="checkbox" id="unwatched"> Unwatched only</label>
  <span id="status"></span>
</div>
<div id="grid"></div>
<div id="settings">
  <h3>Server settings</h3>
  <label>Poll mode <select id="s_poll_mode">
    <option value="on_open">On app open</option>
    <option value="periodic">Periodic</option>
    <option value="instant">Instant</option>
  </select></label>
  <label>Poll interval (minutes) <input id="s_poll_interval_minutes" type="number" min="1" max="1440"></label>
  <label>Max resolution (p) <input id="s_max_resolution" type="number" min="360" max="2160" step="1"></label>
  <label>SponsorBlock: cut out <input id="s_sponsorblock_remove"></label>
  <label>SponsorBlock: chapter-mark <input id="s_sponsorblock_mark"></label>
  <label>Keep days (0 = forever) <input id="s_keep_days" type="number" min="0" max="3650"></label>
  <label>Backfill days (0 = off) <input id="s_backfill_days" type="number" min="0" max="3650"></label>
  <label><input type="checkbox" id="s_delete_after_watched" style="width:auto"> Delete server file after watching</label>
  <div class="bar"><button onclick="saveSettings()">Save settings</button><span id="s_status"></span></div>
</div>
<script>
let current = null;
const $ = id => document.getElementById(id);
async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) throw new Error(path + ' -> ' + r.status);
  return r.json();
}
function fmtDur(s) {
  if (!s) return '';
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
  return h ? h + ':' + String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
           : m + ':' + String(sec).padStart(2, '0');
}
// When it went up, like the app: "5 hours ago", "Yesterday", then the date.
function fmtAgo(v) {
  if (v.published > 0) {
    const m = Math.floor((Date.now() / 1000 - v.published) / 60);
    if (m < 1) return 'Just now';
    if (m < 60) return m + ' min ago';
    if (m < 1440) { const h = Math.floor(m / 60); return h + (h === 1 ? ' hour ago' : ' hours ago'); }
  }
  let d;
  if (v.published > 0) d = new Date(v.published * 1000);
  else if (/^[0-9]{8}$/.test(v.upload_date || '')) {
    const u = v.upload_date;
    d = new Date(+u.slice(0, 4), +u.slice(4, 6) - 1, +u.slice(6, 8));
  } else return '';
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const day = new Date(d); day.setHours(0, 0, 0, 0);
  const days = Math.round((today - day) / 86400000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 7) return days + ' days ago';
  const opts = {month: 'short', day: 'numeric'};
  if (d.getFullYear() !== today.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(undefined, opts);
}
async function load() {
  $('status').textContent = 'loading…';
  try {
    const q = $('unwatched').checked ? '?unwatched_only=true&limit=200' : '?limit=200';
    const feed = await api('/api/feed' + q);
    const g = $('grid'); g.innerHTML = '';
    for (const v of feed.videos) {
      const d = document.createElement('div');
      d.className = 'card' + (v.watched ? ' watched' : '');
      d.innerHTML = '<img loading="lazy" src="/api/thumbs/' + v.id + '" onerror="this.remove()">' +
        '<div class="t"></div><div class="m"></div>';
      d.querySelector('.t').textContent = v.title || v.id;
      d.querySelector('.m').textContent = [v.channel_title, fmtAgo(v), fmtDur(v.duration)].filter(Boolean).join('  •  ');
      d.onclick = () => play(v);
      g.appendChild(d);
    }
    $('status').textContent = feed.videos.length + ' videos';
    loadWatch();
  } catch (e) { $('status').textContent = 'failed: ' + e.message; }
}
function play(v) {
  current = v;
  $('now').textContent = v.title || v.id;
  $('vid').src = '/api/videos/' + v.id + '/stream';
  $('player').style.display = 'block';
  // Resume where you stopped, like the phone app.
  if (v.position_seconds > 0 && v.position_seconds < v.duration - 5) {
    $('vid').addEventListener('loadedmetadata', () => { $('vid').currentTime = v.position_seconds; }, {once: true});
  }
  $('vid').play().catch(() => {});
  window.scrollTo(0, 0);
}
function closePlayer() { if (current) saveProgress(); wtStop(); $('vid').pause(); $('vid').removeAttribute('src'); $('player').style.display = 'none'; }
let lastSave = 0;
function saveProgress() {
  if (!current || !$('vid').src) return;
  const p = Math.floor($('vid').currentTime || 0);
  fetch('/api/videos/' + current.id + '/progress', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({position_seconds: p})});
  current.position_seconds = p;
}
setInterval(() => { const v = $('vid'); if (current && !v.paused && !v.ended && Date.now() - lastSave > 10000) { lastSave = Date.now(); saveProgress(); } }, 2000);
// Watch time (Stats): wall-clock time the video is actually playing. One
// session per video per day; the server keeps the larger running total.
let wt = null, wtSent = 0;
function localDay(d = new Date()) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function wtAccrue() {
  if (!wt || wt.mark == null) return;
  const now = performance.now();
  wt.seconds += Math.min(60, Math.max(0, (now - wt.mark) / 1000));
  wt.mark = now;
}
function wtSend() {
  if (!wt || wt.seconds <= 0) return;
  wtSent = Date.now();
  const s = {id: wt.id, video_id: wt.video_id, day: wt.day, seconds: wt.seconds};
  fetch('/api/watchtime', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({sessions: [s]}), keepalive: true}).catch(() => {});
}
function wtStart() {
  if (!current) return;
  const day = localDay();
  if (!wt || wt.video_id !== current.id || wt.day !== day) {
    wtStop();
    wt = {id: Date.now().toString(36) + Math.random().toString(36).slice(2), video_id: current.id, day, seconds: 0, mark: null};
  }
  wt.mark = performance.now();
}
function wtStop() { wtAccrue(); if (wt) wt.mark = null; wtSend(); }
setInterval(() => {
  if (!wt || wt.mark == null) return;
  if (wt.day !== localDay()) { wtStop(); wtStart(); return; }  // past midnight
  wtAccrue();
  if (Date.now() - wtSent > 30000) wtSend();
}, 5000);
function fmtWatch(s) {
  const m = Math.floor(s / 60);
  return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + 'm';
}
async function loadWatch() {
  try {
    const now = new Date(), since = new Date(now);
    since.setDate(now.getDate() - 6);
    const st = await api('/api/stats?since=' + localDay(since));
    const today = localDay(now);
    let t = 0, w = 0;
    for (const r of st.rows) { w += r.seconds; if (r.day === today) t += r.seconds; }
    $('watch').textContent = 'Watched today ' + fmtWatch(t) + '  ·  7 days ' + fmtWatch(w);
  } catch (e) {}
}
async function markWatched(w) {
  if (!current) return;
  await fetch('/api/videos/' + current.id + '/watched?watched=' + w, {method: 'POST'});
  load();
}
async function syncNow() {
  $('status').textContent = 'scan started…';
  try {
    const r = await api('/api/subscriptions/refresh', {method: 'POST'});
    $('status').textContent = r.started ? 'scan running in background — reload in a bit'
      : 'scan already running';
    setTimeout(load, 15000);
  } catch (e) { $('status').textContent = 'failed: ' + e.message; }
}
async function loadSettings() {
  try {
    const s = await api('/api/settings');
    for (const k of ['poll_mode', 'poll_interval_minutes', 'max_resolution',
                     'sponsorblock_remove', 'sponsorblock_mark', 'keep_days', 'backfill_days'])
      $('s_' + k).value = s[k];
    $('s_delete_after_watched').checked = !!s.delete_after_watched;
  } catch (e) {}
}
async function saveSettings() {
  const body = {
    poll_mode: $('s_poll_mode').value,
    poll_interval_minutes: +$('s_poll_interval_minutes').value,
    max_resolution: +$('s_max_resolution').value,
    sponsorblock_remove: $('s_sponsorblock_remove').value,
    sponsorblock_mark: $('s_sponsorblock_mark').value,
    keep_days: +$('s_keep_days').value,
    backfill_days: +$('s_backfill_days').value,
    delete_after_watched: $('s_delete_after_watched').checked,
  };
  try {
    await api('/api/settings', {method: 'PUT',
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    $('s_status').textContent = 'settings saved';
  } catch (e) { $('s_status').textContent = 'save failed: ' + e.message; }
}
$('vid').addEventListener('pause', saveProgress);
$('vid').addEventListener('playing', wtStart);
for (const ev of ['pause', 'waiting', 'ended']) $('vid').addEventListener(ev, wtStop);
window.addEventListener('pagehide', () => { if (current) saveProgress(); wtStop(); });
$('unwatched').onchange = load;
load(); loadSettings(); loadWatch();
</script>
</body>
</html>
"""
