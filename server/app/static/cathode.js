'use strict';
/* Cathode web: the app's phosphor tube in a browser. Pages: On air (tube
   carousel + guide), Lineup, Setup (with Stats), and the watch page. One
   <video> for the whole page: big on the watch page, a mini strip while you
   browse. Progress and watch time are reported like the app does. */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));

const S = {
  videos: [], loaded: false, loading: false, error: null,
  filter: 'all', current: null, settings: null, stats: null, health: null,
  subs: null, subMsg: '', showHelp: false, statDay: null,
};

async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) {
    let detail = '';
    try { detail = (await r.json()).detail || ''; } catch (e) {}
    throw new Error(detail || ('HTTP ' + r.status));
  }
  return r.json();
}

// ------------------------------------------------------------ formatting

function fmtDur(s) {
  s = Math.floor(s || 0);
  if (s <= 0) return '';
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
  return h ? h + ':' + String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
           : m + ':' + String(sec).padStart(2, '0');
}
function fmtWatch(s) {
  const m = Math.floor((s || 0) / 60);
  return m >= 60 ? Math.floor(m / 60) + 'H ' + (m % 60) + 'M' : m + 'M';
}
function localDay(d = new Date()) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
// When it went up, like the app: "5 HOURS AGO", "YESTERDAY", then the date.
function fmtAgo(v) {
  if (v.published > 0) {
    const m = Math.floor((Date.now() / 1000 - v.published) / 60);
    if (m < 1) return 'JUST NOW';
    if (m < 60) return m + ' MIN AGO';
    if (m < 1440) { const h = Math.floor(m / 60); return h + (h === 1 ? ' HOUR AGO' : ' HOURS AGO'); }
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
  if (days <= 0) return 'TODAY';
  if (days === 1) return 'YESTERDAY';
  if (days < 7) return days + ' DAYS AGO';
  const opts = {month: 'short', day: 'numeric'};
  if (d.getFullYear() !== today.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(undefined, opts).toUpperCase();
}
const meta = v => [fmtDur(v.duration), fmtAgo(v)].filter(Boolean).join(' · ');

// ------------------------------------------------------------ library logic (same rules as the app)

function sortKey(v) {
  if (v.published > 0) return v.published;
  const u = v.upload_date || '';
  if (/^[0-9]{8}$/.test(u)) return Date.UTC(+u.slice(0, 4), +u.slice(4, 6) - 1, +u.slice(6, 8)) / 1000;
  return v.downloaded_at || 0;
}
const inProgress = v => !v.watched && v.position_seconds > 0;
const fraction = v => v.watched ? 1 : v.duration > 0 ? clamp(v.position_seconds / v.duration, 0, 1) : 0;
const isFresh = v => { const k = sortKey(v); return k > 0 && Date.now() / 1000 - k < 2 * 86400; };

/* The lineup: channels numbered alphabetically, so CH 04 here is CH 04 on
   the phone too. */
function lineup() {
  if (S._lineupFor === S.videos) return S._lineup;
  const names = [...new Set(S.videos.map(v => v.channel_title).filter(n => n && n.trim()))]
    .sort((a, b) => { const x = a.toLowerCase(), y = b.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0; });
  S._lineup = new Map(names.map((n, i) => [n, i + 1]));
  S._lineupFor = S.videos;
  return S._lineup;
}
const chNum = name => lineup().get(name) || null;
const chLabel = name => { const n = chNum(name); return n ? String(n).padStart(2, '0') : '--'; };

/* One pick per channel: the video you were partway through, else its newest
   unwatched upload. Half-watched first, then newest. */
function nowShowing(videos) {
  const by = groupBy(videos);
  const per = [];
  for (const vs of by.values()) {
    const p = vs.find(inProgress) || vs.filter(v => !v.watched).sort((a, b) => sortKey(b) - sortKey(a))[0];
    if (p) per.push(p);
  }
  const started = per.filter(inProgress), fresh = per.filter(v => !inProgress(v)).sort((a, b) => sortKey(b) - sortKey(a));
  const picks = [...started, ...fresh];
  return (picks.length ? picks : [...videos].sort((a, b) => sortKey(b) - sortKey(a)).slice(0, 5)).slice(0, 16);
}
function groupBy(videos) {
  const m = new Map();
  for (const v of videos) {
    const k = v.channel_title || '';
    if (!m.has(k)) m.set(k, []);
    m.get(k).push(v);
  }
  return m;
}
const thumb = v => '/api/thumbs/' + encodeURIComponent(v.id);
const find = id => S.videos.find(v => v.id === id);

// ------------------------------------------------------------ data

async function loadLibrary() {
  S.loading = true; status();
  try {
    const feed = await api('/api/feed?limit=5000');
    const before = JSON.stringify(S.videos);
    S.videos = feed.videos || [];
    S.loaded = true; S.error = null;
    // Keep the playing video's object in step with the new list.
    if (S.current) S.current = find(S.current.id) || S.current;
    if (before !== JSON.stringify(S.videos)) rerender('data');
  } catch (e) {
    S.error = e.message;
    rerender('data');
  } finally {
    S.loading = false; status();
  }
}
async function loadStats() {
  const since = new Date(); since.setDate(since.getDate() - 29);
  try {
    S.stats = await api('/api/stats?since=' + localDay(since));
    rerender('stats');
  } catch (e) {}
}
function summary() {
  const st = S.stats || {rows: [], total_seconds: 0, first_day: null};
  const byDay = new Map();
  for (const r of st.rows) byDay.set(r.day, (byDay.get(r.day) || 0) + r.seconds);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const dayOf = n => { const d = new Date(today); d.setDate(d.getDate() - n); return d; };
  const sum = n => { let t = 0; for (let i = 0; i < n; i++) t += byDay.get(localDay(dayOf(i))) || 0; return t; };
  const firstStr = [...st.rows.map(r => r.day), st.first_day].filter(Boolean).sort()[0];
  let tracked = 1;
  if (firstStr) {
    const [y, m, d] = firstStr.split('-').map(Number);
    tracked = clamp(Math.round((today - new Date(y, m - 1, d)) / 86400000) + 1, 1, 30);
  }
  const start = localDay(dayOf(29)), end = localDay(today);
  const ch = new Map();
  for (const r of st.rows) if (r.day >= start && r.day <= end) ch.set(r.channel, (ch.get(r.channel) || 0) + r.seconds);
  return {
    today: byDay.get(localDay(today)) || 0,
    week: sum(7),
    avg: sum(30) / tracked,
    all: st.total_seconds || 0,
    days: Array.from({length: 14}, (_, i) => { const d = dayOf(13 - i); return [d, byDay.get(localDay(d)) || 0]; }),
    channels: [...ch.entries()].filter(([, s]) => s >= 60).sort((a, b) => b[1] - a[1]).slice(0, 5),
  };
}

// ------------------------------------------------------------ header

function status() {
  const el = $('#status');
  let t, blink = false;
  if (S.error && !S.loading) { t = 'NO SIGNAL'; blink = true; }
  else if (!S.loaded) { t = 'TUNING'; blink = true; }
  else t = '● ON AIR';
  el.textContent = t;
  el.classList.toggle('blink', blink);
}
function clock() {
  const d = new Date();
  $('#clock').textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  setTimeout(clock, (60 - d.getSeconds()) * 1000 + 50);
}

// ------------------------------------------------------------ router

function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const [page, arg] = h.split('/');
  return {page: page || 'air', arg: arg ? decodeURIComponent(arg) : null};
}
function rerender(reason) {
  const r = route();
  if (reason === 'data' && !(r.page === 'air' || r.page === 'watch' || r.page === 'lineup')) return;
  // A watch page that's already showing its video isn't redrawn under you.
  if (reason === 'data' && r.page === 'watch' && $('#slot')) return;
  if (reason === 'stats' && !(r.page === 'stats' || r.page === 'setup')) return;
  if (reason === 'settings' && r.page !== 'setup') return;
  if (reason === 'subs' && r.page !== 'lineup') return;
  render();
}
function render() {
  const r = route();
  closeSheet();
  const view = $('#view');
  // Park the set before the page under it is replaced.
  dock(r.page === 'watch');
  let keep = null;
  if (r.page === 'air') { const t = $('.tubes'); if (t) keep = t.scrollLeft; }
  for (const a of $$('#dial a')) {
    const tab = a.dataset.tab;
    a.classList.toggle('on', tab === r.page || (tab === 'setup' && r.page === 'stats'));
  }
  switch (r.page) {
    case 'lineup': view.innerHTML = lineupPage(); bindLineup(); if (!S.subs) loadSubs(); break;
    case 'setup': view.innerHTML = setupPage(); bindSetup(); break;
    case 'stats': view.innerHTML = statsPage(); bindStats(); break;
    case 'watch': renderWatch(r.arg); break;
    default: view.innerHTML = airPage(); bindAir(keep);
  }
}
window.addEventListener('hashchange', () => { render(); window.scrollTo(0, 0); });

// ------------------------------------------------------------ shared bits

function titleBlock(text, kicker) {
  return (kicker ? '<div class="kicker">' + esc(kicker) + '</div>' : '') +
    '<div class="title lit">' + esc(text) + '<span class="cursor"></span></div>';
}
function badge(v, small) {
  if (!small && !v.watched && !v.position_seconds && isFresh(v)) return '<span class="rev">NEW</span>';
  if (small && !v.watched && !v.position_seconds && isFresh(v)) return '<span class="rev">NEW</span>';
  return '';
}
function monoscope(label) {
  let grid = '';
  for (let i = 1; i < 12; i++) grid += '<line x1="' + i * 100 / 12 + '%" y1="0" x2="' + i * 100 / 12 + '%" y2="100%"/>';
  for (let i = 1; i < 9; i++) grid += '<line x1="0" y1="' + i * 100 / 9 + '%" x2="100%" y2="' + i * 100 / 9 + '%"/>';
  let wedges = '';
  for (let i = 0; i < 9; i++) wedges += '<line x1="' + (32 + i * 4.5) + '%" y1="80%" x2="' + (32 + i * 4.5) + '%" y2="86%" stroke-width="' + (1 + i * .8) + '"/>';
  return '<div class="mono"><svg viewBox="0 0 400 300" preserveAspectRatio="none">' +
    '<g stroke="#184226" stroke-width="1.2">' + grid + '</g>' +
    '<g fill="none" stroke="#2c7340"><circle cx="200" cy="150" r="126" stroke-width="2.4"/><circle cx="200" cy="150" r="78" stroke-width="1.6"/>' +
    '<line x1="74" y1="150" x2="326" y2="150" stroke-width="1.6"/><line x1="200" y1="24" x2="200" y2="276" stroke-width="1.6"/></g>' +
    '<g stroke="#45ad60">' + wedges + '</g></svg><span class="rev">' + esc(label) + '</span></div>';
}

// ------------------------------------------------------------ on air

function airPage() {
  if (!S.loaded && !S.error) return '<div style="margin-top:10px">' + monoscope('TUNING') + '</div>';
  if (!S.videos.length) {
    if (S.error) return '<div style="margin-top:10px">' + monoscope('NO SIGNAL') +
      '<p class="notice" style="margin-top:18px">Can\'t reach the server: ' + esc(S.error) + '</p>' +
      '<button class="btn on" data-act="retry">▶ Try again</button></div>';
    return '<div style="margin-top:10px">' + monoscope('OFF AIR') +
      '<p style="color:var(--p80);margin:18px 0">Nothing recorded yet. Add some channels to the lineup.</p>' +
      '<a class="btn on" href="#/lineup">▶ Add channels</a></div>';
  }
  const picks = nowShowing(S.videos);
  let html = S.error ? '<p class="notice">! Offline: showing the last library loaded</p>' : '';
  html += '<div class="tubes" tabindex="0" aria-label="Now showing, one channel at a time">';
  for (const v of picks) {
    const n = chNum(v.channel_title);
    const f = fraction(v);
    html += '<div class="tube" data-id="' + esc(v.id) + '" role="button" tabindex="-1">' +
      '<img src="' + thumb(v) + '" alt="" loading="lazy" onerror="this.remove()"><div class="shade"></div>' +
      (n ? '<div class="ch lit"><small>CH</small><b>' + chLabel(v.channel_title) + '</b></div>' : '') +
      '<div class="badge">' + badge(v) + '</div>' +
      '<div class="card"><div class="chan dim">' + esc(v.channel_title) + '</div>' +
      '<h2 class="lit">' + esc(v.title || v.id) + '</h2>' +
      '<div class="row"><span class="rev">' + (inProgress(v) ? '▶ RESUME ' + fmtDur(v.position_seconds) : '▶ WATCH') + '</span>' +
      '<span class="dim">' + esc(meta(v)) + '</span></div></div>' +
      (f > 0 && !v.watched ? '<div class="prog"><i style="width:' + (f * 100).toFixed(1) + '%"></i></div>' : '') +
      '<canvas class="snow" width="140" height="120"></canvas></div>';
  }
  html += '</div>';
  html += '<div class="guide-head"><div class="section lit" style="flex:1">Guide</div></div>' +
    '<div class="tabs" style="margin:-2px 0 16px">' +
    [['all', 'All'], ['new', 'Unwatched']].map(([k, l]) =>
      '<button class="tab' + (S.filter === k ? ' on' : '') + '" data-filter="' + k + '">' + l + '</button>').join('') + '</div>';
  const shown = S.videos.filter(v => S.filter === 'all' || !v.watched);
  const rows = [...groupBy(shown).entries()]
    .map(([ch, vs]) => [ch, vs.sort((a, b) => sortKey(b) - sortKey(a))])
    .sort((a, b) => sortKey(b[1][0]) - sortKey(a[1][0]));
  if (!rows.length) html += '<p class="hint">All caught up.</p>';
  for (const [ch, vs] of rows) {
    const unseen = vs.filter(v => !v.watched).length;
    html += '<div class="chrow"><div class="hd"><span class="num lit">' + chLabel(ch) + '</span>' +
      '<span class="name">' + esc(ch || 'Unknown channel') + '</span>' +
      (unseen ? '<span class="new dim">' + unseen + ' NEW</span>' : '') + '</div><div class="blocks scroller">';
    for (const v of vs) {
      // Block width follows running time, like a TV guide.
      const mins = Math.max(1, (v.duration || 0) / 60);
      const w = clamp(130 + mins * 4, 170, 340);
      const f = fraction(v);
      html += '<button class="block' + (v.watched ? ' seen' : '') + '" style="width:' + w + 'px" data-id="' + esc(v.id) + '">' +
        '<img src="' + thumb(v) + '" alt="" loading="lazy" onerror="this.remove()"><div class="shade"></div>' +
        '<div class="in"><div class="top"><span class="dim">' + (v.watched ? 'SEEN' : esc(fmtAgo(v))) + '</span>' + badge(v, true) + '</div>' +
        '<div class="t' + (v.watched ? '' : ' lit') + '">' + esc(v.title || v.id) + '</div>' +
        '<div class="dur dim">' + fmtDur(v.duration) + '</div></div>' +
        (f > 0 && !v.watched ? '<div class="prog"><i style="width:' + (f * 100).toFixed(1) + '%"></i></div>' : '') +
        '</button>';
    }
    html += '</div></div>';
  }
  return html;
}

function bindAir(keepScroll) {
  const view = $('#view');
  for (const b of $$('[data-filter]', view)) b.onclick = () => { S.filter = b.dataset.filter; render(); };
  const retry = $('[data-act=retry]', view);
  if (retry) retry.onclick = loadLibrary;
  for (const el of $$('.tube, .block', view)) bindVideoEl(el);
  const tubes = $('.tubes', view);
  if (tubes) {
    if (keepScroll != null) tubes.scrollLeft = keepScroll;
    tuner(tubes);
  }
}

function bindVideoEl(el) {
  const v = () => find(el.dataset.id);
  el.addEventListener('click', e => {
    if (el._longPressed) { el._longPressed = false; e.preventDefault(); return; }
    const tubes = el.closest('.tubes');
    if (tubes && tubes._tuneIfPeek && tubes._tuneIfPeek(el)) return;
    const x = v(); if (x) watch(x);
  });
  el.addEventListener('contextmenu', e => { e.preventDefault(); const x = v(); if (x) openSheet(x, e.clientX, e.clientY); });
  // Long-press on touch, like the app.
  let timer = null, sx = 0, sy = 0;
  el.addEventListener('pointerdown', e => {
    if (e.pointerType !== 'touch') return;
    sx = e.clientX; sy = e.clientY;
    timer = setTimeout(() => { el._longPressed = true; const x = v(); if (x) openSheet(x, sx, sy); }, 520);
  });
  const cancel = () => { clearTimeout(timer); timer = null; };
  el.addEventListener('pointerup', cancel);
  el.addEventListener('pointercancel', cancel);
  el.addEventListener('pointermove', e => { if (timer && Math.hypot(e.clientX - sx, e.clientY - sy) > 10) cancel(); });
}

/* Static that follows the scroll: off-centre channels are untuned, and the
   incoming one clears as it slides in. Four pre-rendered noise frames; it
   animates only while the carousel moves. */
let NOISE = null;
function noiseFrames() {
  if (NOISE) return NOISE;
  NOISE = Array.from({length: 4}, () => {
    const c = document.createElement('canvas'); c.width = 140; c.height = 120;
    const g = c.getContext('2d'), img = g.createImageData(140, 120);
    for (let i = 0; i < img.data.length; i += 4) {
      const r = Math.random();
      const a = r < .45 ? 0 : (r - .45) / .55;
      img.data[i] = 3 + 154 * a; img.data[i + 1] = 6 + 249 * a; img.data[i + 2] = 4 + 176 * a; img.data[i + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    return c;
  });
  return NOISE;
}
function tuner(tubes) {
  const frames = noiseFrames();
  const cards = $$('.tube', tubes);
  const gap = 16;
  // Tuned = snapped to the start edge. A spacer after the last channel lets
  // it reach that edge too; on wide screens the next channel peeks in as a
  // narrow strip of snow.
  const spacer = document.createElement('div');
  spacer.className = 'spacer';
  tubes.appendChild(spacer);
  const pad = () => parseFloat(getComputedStyle(tubes).paddingLeft) || 0;
  function size() {
    const w = tubes.clientWidth;
    if (innerWidth > 720) tubes.style.setProperty('--tw', Math.max(320, w - 2 * pad() - 110) + 'px');
    else tubes.style.removeProperty('--tw');
    const cw = cards[0] ? cards[0].getBoundingClientRect().width : 0;
    spacer.style.width = Math.max(0, w - cw - 2 * pad() - gap) + 'px';
  }
  let frame = 0, raf = 0, last = 0;
  function detuneOf(t) {
    const box = tubes.getBoundingClientRect(), r = t.getBoundingClientRect();
    return Math.abs(r.left - (box.left + pad())) / (r.width + gap);
  }
  function paint(animate) {
    for (const t of cards) {
      const d = detuneOf(t);
      const a = clamp((d - .04) * 2.4, 0, 1);
      t.classList.toggle('peek', d > .5);
      const c = t.lastElementChild;
      c.style.opacity = a.toFixed(3);
      if (a > 0 && (animate || !c._drawn)) {
        c.getContext('2d').drawImage(frames[(frame + cards.indexOf(t)) % frames.length], 0, 0);
        c._drawn = true;
      }
    }
  }
  function loop() {
    frame++;
    paint(true);
    raf = performance.now() - last < 140 ? requestAnimationFrame(loop) : 0;
  }
  tubes.addEventListener('scroll', () => {
    last = performance.now();
    if (!raf) raf = requestAnimationFrame(loop);
  }, {passive: true});
  size(); paint(false);
  new ResizeObserver(() => { size(); paint(false); }).observe(tubes);
  const goTo = t => tubes.scrollTo({left: t.offsetLeft - pad() - tubes.offsetLeft, behavior: 'smooth'});
  tubes._step = dir => {
    const i = cards.indexOf(tubes._centred());
    const t = cards[clamp(i + dir, 0, cards.length - 1)];
    if (t) goTo(t);
  };
  tubes._centred = () => cards.reduce((b, t) => (!b || detuneOf(t) < detuneOf(b) ? t : b), null);
  // Clicking a channel that's only peeking in tunes to it rather than playing it.
  tubes._tuneIfPeek = t => { if (detuneOf(t) > .5) { goTo(t); return true; } return false; };
}

// ------------------------------------------------------------ the sheet (long-press / right-click menu)

function openSheet(v, x, y) {
  const sh = $('#sheet');
  const acts = [[inProgress(v) ? 'Resume' : 'Watch', () => watch(v)]];
  acts.push(v.watched ? ['Mark unwatched', () => setWatched(v, false)] : ['Mark watched', () => setWatched(v, true)]);
  if (!v.watched && v.position_seconds > 0) acts.push(['Start over', () => setWatched(v, false)]);
  sh.innerHTML = '<div class="chan">CH ' + chLabel(v.channel_title) + '  ' + esc(v.channel_title) + '</div>' +
    '<h3>' + esc(v.title || v.id) + '</h3>' + acts.map((a, i) => '<button data-i="' + i + '">' + esc(a[0]) + '</button>').join('');
  for (const b of $$('button', sh)) b.onclick = () => { closeSheet(); acts[+b.dataset.i][1](); };
  sh.hidden = false;
  if (innerWidth <= 720) {
    sh.style.left = '10px'; sh.style.right = '10px'; sh.style.top = 'auto';
    sh.style.bottom = 'calc(70px + env(safe-area-inset-bottom))'; sh.style.maxWidth = 'none';
  } else {
    sh.style.right = 'auto'; sh.style.bottom = 'auto'; sh.style.maxWidth = '';
    const r = sh.getBoundingClientRect();
    sh.style.left = clamp(x, 10, innerWidth - r.width - 10) + 'px';
    sh.style.top = clamp(y, 10, innerHeight - r.height - 10) + 'px';
  }
}
function closeSheet() { $('#sheet').hidden = true; }
document.addEventListener('pointerdown', e => { if (!$('#sheet').hidden && !e.target.closest('#sheet')) closeSheet(); });

async function setWatched(v, w) {
  try {
    await fetch('/api/videos/' + encodeURIComponent(v.id) + '/watched?watched=' + w, {method: 'POST'});
    v.watched = w ? 1 : 0;
    if (!w) v.position_seconds = 0;
    render();
  } catch (e) {}
}

// ------------------------------------------------------------ the set (player)

const vid = $('#vid'), set = $('#set');

function dock(onWatchPage) {
  // Back to the page root first: replacing #view must never remove it.
  if (set.parentElement !== $('#crt')) $('#crt').appendChild(set);
  set.className = onWatchPage ? 'full' : 'mini';
  set.hidden = !S.current;
  // The mini strip is a picture, not a control surface.
  vid.controls = onWatchPage;
}
function watch(v) {
  location.hash = '#/watch/' + encodeURIComponent(v.id);
}
function start(v) {
  if (S.current && S.current.id === v.id && vid.src) return;
  if (S.current) { saveProgress(); wtStop(); }
  S.current = v;
  vid.src = '/api/videos/' + encodeURIComponent(v.id) + '/stream';
  // Resume where you stopped, like the phone; finished videos start over.
  const nearEnd = v.duration > 0 && v.position_seconds >= v.duration * .95;
  if (v.position_seconds > 0 && !v.watched && !nearEnd) {
    vid.addEventListener('loadedmetadata', () => { vid.currentTime = v.position_seconds; }, {once: true});
  }
  vid.play().catch(() => {});
  flash(v);
  mini();
}
function closeVideo() {
  if (S.current) saveProgress();
  wtStop();
  vid.pause(); vid.removeAttribute('src'); vid.load();
  S.current = null;
  set.hidden = true;
  if (route().page === 'watch') location.hash = '#/air';
}
let flashTimer = 0;
function flash(v) {
  const f = $('#flash');
  $('b', f).textContent = 'CH ' + chLabel(v.channel_title);
  $('span', f).textContent = v.channel_title || '';
  f.hidden = false; f.classList.remove('out');
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => f.classList.add('out'), 2800);
}
function mini() {
  const v = S.current; if (!v) return;
  $('#miniCh').textContent = 'CH ' + chLabel(v.channel_title) + ' · NOW';
  $('#miniTitle').textContent = v.title || v.id;
  $('#miniPlay').textContent = vid.paused ? '▶' : '❚❚';
}
$('#miniPlay').onclick = () => { vid.paused ? vid.play() : vid.pause(); };
$('#miniClose').onclick = closeVideo;
for (const el of [$('#set .bezel'), $('.mini-text')]) {
  el.addEventListener('click', () => { if (set.classList.contains('mini') && S.current) watch(S.current); });
}
vid.addEventListener('play', mini);
vid.addEventListener('pause', mini);
vid.addEventListener('timeupdate', () => {
  $('#miniBar').style.width = vid.duration ? (vid.currentTime / vid.duration * 100).toFixed(2) + '%' : '0';
});

function renderWatch(id) {
  const view = $('#view');
  const v = find(id);
  if (!v) {
    view.innerHTML = S.loaded ? '<div style="margin-top:10px">' + monoscope('NO SIGNAL') +
      '<p class="notice" style="margin-top:18px">That video isn\'t in the library any more.</p><a class="btn on" href="#/air">▶ On air</a></div>'
      : '<div style="margin-top:10px">' + monoscope('TUNING') + '</div>';
    return;
  }
  const later = S.videos.filter(x => x.channel_title === v.channel_title && x.id !== v.id && !x.watched)
    .sort((a, b) => sortKey(b) - sortKey(a)).slice(0, 8);
  const also = nowShowing(S.videos).filter(x => x.channel_title !== v.channel_title).slice(0, 6);
  const nextRow = (x, withCh) => {
    const f = fraction(x);
    return '<div class="next" data-id="' + esc(x.id) + '"><div class="th"><img src="' + thumb(x) + '" alt="" loading="lazy" onerror="this.remove()">' +
      (f > 0 && !x.watched ? '<i style="width:' + (f * 100).toFixed(1) + '%"></i>' : '') + '</div><div>' +
      (withCh ? '<div class="nm dim">CH ' + chLabel(x.channel_title) + '  ' + esc((x.channel_title || '').toUpperCase()) + '</div>' : '') +
      '<div class="nt">' + esc(x.title || x.id) + '</div><div class="nm dim">' + esc(meta(x)) + '</div></div></div>';
  };
  view.innerHTML = '<div class="watch"><div><button class="close lit" data-act="close">✕ CLOSE</button><div id="slot"></div></div>' +
    '<aside class="prog-card"><div class="chan dim">CH ' + chLabel(v.channel_title) + '  ' + esc(v.channel_title) + '</div>' +
    '<h1 class="lit">' + esc(v.title || v.id) + '</h1><div class="chips">' +
    (v.duration ? '<span class="chip">' + fmtDur(v.duration) + '</span>' : '') +
    (fmtAgo(v) ? '<span class="chip">' + esc(fmtAgo(v)) + '</span>' : '') +
    (v.watched ? '<span class="rev">SEEN</span>' : '') + '</div>' +
    '<div class="acts"><button class="btn" data-act="seen">' + (v.watched ? 'Mark unwatched' : 'Mark watched') + '</button></div>' +
    (later.length ? '<div class="section lit">Later on CH ' + chLabel(v.channel_title) + '</div>' + later.map(x => nextRow(x, false)).join('') : '') +
    (also.length ? '<div class="section lit">Also on air</div>' + also.map(x => nextRow(x, true)).join('') : '') +
    '</aside></div>';
  $('#slot').appendChild(set);
  set.className = 'full'; set.hidden = false; vid.controls = true;
  start(v);
  $('[data-act=close]', view).onclick = closeVideo;
  $('[data-act=seen]', view).onclick = () => setWatched(v, !v.watched);
  for (const el of $$('.next', view)) el.onclick = () => { const x = find(el.dataset.id); if (x) watch(x); };
}

// Progress: every 10 s while playing, on pause, and on leaving.
let lastSave = 0;
function saveProgress() {
  const v = S.current;
  if (!v || !vid.currentSrc) return;
  const p = Math.floor(vid.currentTime || 0);
  if (p <= 0) return;
  fetch('/api/videos/' + encodeURIComponent(v.id) + '/progress', {method: 'POST', keepalive: true,
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify({position_seconds: p})})
    .then(r => r.ok ? r.json() : null).then(j => { if (j && j.watched) v.watched = 1; }).catch(() => {});
  v.position_seconds = p;
}
setInterval(() => {
  if (S.current && !vid.paused && !vid.ended && Date.now() - lastSave > 10000) { lastSave = Date.now(); saveProgress(); }
}, 2000);

// Watch time (Stats): wall-clock time the video is actually playing. One
// session per video per day; the server keeps the larger running total.
let wt = null, wtSent = 0;
function wtAccrue() {
  if (!wt || wt.mark == null) return;
  const now = performance.now();
  wt.seconds += Math.min(60, Math.max(0, (now - wt.mark) / 1000));
  wt.mark = now;
}
function wtSend() {
  if (!wt || wt.seconds <= 0) return;
  wtSent = Date.now();
  const s = {id: wt.id, video_id: wt.video_id, day: wt.day, seconds: wt.seconds, channel: wt.channel};
  fetch('/api/watchtime', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({sessions: [s]}), keepalive: true}).catch(() => {});
}
function wtStart() {
  const v = S.current; if (!v) return;
  const day = localDay();
  if (!wt || wt.video_id !== v.id || wt.day !== day) {
    wtStop();
    wt = {id: Date.now().toString(36) + Math.random().toString(36).slice(2), video_id: v.id, day,
          seconds: 0, mark: null, channel: v.channel_title || null};
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
vid.addEventListener('pause', saveProgress);
vid.addEventListener('playing', wtStart);
for (const ev of ['pause', 'waiting', 'ended']) vid.addEventListener(ev, wtStop);
window.addEventListener('pagehide', () => { if (S.current) saveProgress(); wtStop(); });

// ------------------------------------------------------------ lineup

async function loadSubs() {
  try { S.subs = await api('/api/subscriptions'); } catch (e) { S.subs = {inputs: [], indexed: [], error: e.message}; }
  rerender('subs');
}
const ORIGIN = {opml: 'OPML import', takeout: 'Takeout import', manual: 'Added'};
function lineupPage() {
  const subs = S.subs;
  const titles = new Map((subs?.indexed || []).filter(c => c.title).map(c => [c.url, c.title]));
  const all = (subs?.inputs || []).map(s => [s, s.title || titles.get(s.source_url) || s.channel_id || s.source_url]);
  const onAir = all.filter(([, n]) => chNum(n)).sort((a, b) => chNum(a[1]) - chNum(b[1]));
  const off = all.filter(([, n]) => !chNum(n)).sort((a, b) => a[1].toLowerCase() < b[1].toLowerCase() ? -1 : 1);
  const item = ([s, n], num) => '<div class="chan-item"><span class="num ' + (num ? 'lit' : 'faint') + '">' + (num || '--') + '</span>' +
    '<div class="nm"><div class="' + (num ? 'lit' : 'dim') + '">' + esc(n) + '</div><small>' + esc(ORIGIN[s.origin] || '') + '</small></div>' +
    (s.origin === 'manual' ? '<button class="icon" data-rm="' + esc(s.source_url) + '" aria-label="Remove">✕</button>' : '') + '</div>';
  let html = titleBlock('Lineup', subs ? all.length + ' channels' : 'Channels') +
    '<div class="kicker" style="margin-bottom:4px">Tune in</div>' +
    '<form class="prompt" id="tune"><b>&gt;</b><input id="tuneIn" placeholder="Channel link or @handle" autocomplete="off" spellcheck="false">' +
    '<button class="btn" id="tuneAdd" type="submit" disabled>Add</button></form>' +
    '<div class="tools"><button class="btn" id="importBtn">Import file</button><input type="file" id="importFile" accept=".csv,.opml,.xml,.txt" hidden>' +
    '<button class="icon" id="helpBtn" aria-label="How to import">(i)</button><span class="lit" id="subMsg">' + esc(S.subMsg) + '</span></div>' +
    (S.showHelp ? '<div class="help panel">Import your subscriptions:<ol>' +
      '<li>Open takeout.google.com.</li><li>Deselect all, then select YouTube and YouTube Music.</li>' +
      '<li>Under All YouTube data included, keep only subscriptions.</li>' +
      '<li>Create the export, download it and unzip. You want subscriptions.csv.</li><li>Import file, here.</li></ol>' +
      'An OPML export from another app works too. A new CSV or OPML replaces the last one; a .txt list of channel links adds to it.</div>' : '');
  if (!subs) return html + '<div class="section lit">On the dial</div><p class="hint">TUNING…</p>';
  if (subs.error) html += '<p class="notice">! ' + esc(subs.error) + '</p>';
  html += '<div class="chan-list"><div class="section lit">On the dial</div>' +
    (onAir.length ? onAir.map(x => item(x, chLabel(x[1]))).join('') : '<p class="hint">No channels yet.</p>');
  if (off.length) html += '<div class="section dim">Off air</div>' + off.map(x => item(x, null)).join('');
  return html + '</div>';
}
function bindLineup() {
  const input = $('#tuneIn'), add = $('#tuneAdd');
  input.addEventListener('input', () => {
    // ADD lights once there's something to add.
    add.disabled = !input.value.trim();
    add.classList.toggle('on', !!input.value.trim());
  });
  $('#tune').onsubmit = async e => {
    e.preventDefault();
    const u = input.value.trim();
    if (!u) { input.focus(); return; }
    subOp(async () => {
      const r = await api('/api/subscriptions', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({url: u})});
      return r.added ? 'ADDED' : 'ALREADY ADDED';
    });
  };
  $('#importBtn').onclick = () => $('#importFile').click();
  $('#importFile').onchange = () => {
    const f = $('#importFile').files[0];
    if (!f) return;
    const ext = (f.name.split('.').pop() || '').toLowerCase();
    if (!['csv', 'opml', 'xml', 'txt'].includes(ext)) { S.subMsg = 'USE A .CSV, .OPML OR .TXT FILE'; render(); return; }
    subOp(async () => {
      const fd = new FormData(); fd.append('file', f, 'upload.' + ext);
      const r = await api('/api/subscriptions/upload', {method: 'POST', body: fd});
      return (ext === 'txt' ? 'ADDED ' : 'IMPORTED ') + (r.subscriptions ?? 0) + ' CHANNELS';
    });
  };
  $('#helpBtn').onclick = () => { S.showHelp = !S.showHelp; render(); };
  for (const b of $$('[data-rm]')) b.onclick = () => subOp(async () => {
    await api('/api/subscriptions?url=' + encodeURIComponent(b.dataset.rm), {method: 'DELETE'});
    return 'REMOVED';
  });
}
async function subOp(fn) {
  S.subMsg = '…'; $('#subMsg').textContent = '…';
  try { S.subMsg = await fn(); } catch (e) { S.subMsg = (e.message || 'FAILED').toUpperCase(); }
  await loadSubs();
  render();
}

// ------------------------------------------------------------ setup

const RES = [360, 480, 720, 1080, 1440, 2160];
const KEEP = [0, ...Array.from({length: 30}, (_, i) => i + 1), 60, 90, 180, 360];
const BACKFILL = [[0, 'Off'], [5, '5 days'], [7, '7 days'], [30, '30 days'], [90, '90 days']];
const SPEED = [[0, 'No limit'], [5, '5 Mbps'], [10, '10 Mbps'], [25, '25 Mbps'], [50, '50 Mbps'], [100, '100 Mbps']];
const SB = [['sponsor', 'Sponsors'], ['intro', 'Intros'], ['outro', 'End cards'], ['selfpromo', 'Self-promo'],
  ['preview', 'Recaps'], ['interaction', 'Reminders'], ['filler', 'Filler'], ['music_offtopic', 'Off-topic music'],
  ['poi_highlight', 'Highlights']];
const SB_ALL = SB.map(x => x[0]);
const resLabel = h => h === 1440 ? '1440P (2K)' : h === 2160 ? '2160P (4K)' : h + 'P';
const keepLabel = d => d <= 0 ? 'Forever' : d === 1 ? '1 day' : d <= 30 ? d + ' days' : d < 90 ? '2 months' : d < 180 ? '3 months' : d < 360 ? '6 months' : '1 year';
const intervalLabel = m => m < 60 ? m + ' min' : m % 60 === 0 ? (m === 60 ? '1 hour' : m / 60 + ' hours') : Math.floor(m / 60) + ' h ' + m % 60 + ' min';
const nearest = (opts, v) => opts.reduce((bi, x, i) => Math.abs(x - v) < Math.abs(opts[bi] - v) ? i : bi, 0);
const splitSb = s => new Set(String(s || '').split(',').map(x => x.trim()).filter(Boolean));

async function loadSettings() {
  try { S.settings = await api('/api/settings'); } catch (e) { S.settings = null; S.settingsErr = e.message; }
  rerender('settings');
}
async function loadHealth() {
  try { S.health = await api('/api/health'); } catch (e) {}
  rerender('settings');
}
let saveTimer = 0, saveMsg = '';
function saveSettingsSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      await api('/api/settings', {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(S.settings)});
      saveMsg = 'SAVED';
    } catch (e) { saveMsg = 'COULDN\'T SAVE'; }
    const el = $('#saveMsg'); if (el) el.textContent = saveMsg;
  }, 600);
}
function lastScan() {
  const h = S.health;
  if (!h || !h.last_run) return 'NO SCAN YET';
  const m = Math.floor((Date.now() / 1000 - h.last_run) / 60);
  const ago = m < 1 ? 'JUST NOW' : m < 60 ? m + ' MIN AGO' : Math.floor(m / 60) + ' H AGO';
  const r = h.last_result || {};
  return 'LAST SCAN ' + ago + (r.downloaded != null ? ' · ' + r.downloaded + ' NEW' : '') + (r.failed ? ' · ' + r.failed + ' FAILED' : '');
}
function setupPage() {
  const sm = summary();
  let html = titleBlock('Setup', 'Service menu') +
    '<div class="setup"><a class="panel menu-line" href="#/stats"><span class="go dim">&gt;</span><span class="lit" style="font-size:30px">STATS</span>' +
    '<span style="flex:1"></span><span class="dim">' + fmtWatch(sm.today) + ' TODAY</span></a>' +
    '<div class="section lit">Station<span id="saveMsg" class="dim" style="font-size:20px;order:3">' + esc(saveMsg) + '</span></div>' +
    '<div class="panel"><div class="scanline"><span class="hint">' + esc(lastScan()) + '</span><button class="btn" id="scanNow">Scan now</button></div></div>';
  const s = S.settings;
  if (!s) return html + '<p class="hint" style="margin-top:14px">' + (S.settingsErr ? '! ' + esc(S.settingsErr) : 'TUNING…') + '</p></div>';
  const ri = nearest(RES, s.max_resolution), ki = nearest(KEEP, s.keep_days);
  html += '<div class="panel" style="margin-top:12px">' +
    '<div class="field"><div class="lbl"><span>Check for new videos</span></div>' +
    [['on_open', 'When the app opens'], ['periodic', 'On a schedule'], ['instant', 'Every 5 minutes']].map(([k, l]) =>
      '<div class="radio' + (s.poll_mode === k ? ' on' : '') + '" data-poll="' + k + '"><b>' + (s.poll_mode === k ? '(*)' : '( )') + '</b>' + l + '</div>').join('') +
    (s.poll_mode === 'periodic' ? '<div class="lbl" style="margin-top:6px"><span>Every</span><span id="ivLbl">' + intervalLabel(s.poll_interval_minutes) + '</span></div>' +
      '<input type="range" id="iv" min="5" max="720" step="5" value="' + s.poll_interval_minutes + '">' : '') + '</div>' +
    '<div class="field"><div class="lbl"><span>Max quality</span><span id="resLbl">' + resLabel(RES[ri]) + '</span></div>' +
    '<input type="range" id="res" min="0" max="' + (RES.length - 1) + '" step="1" value="' + ri + '"></div>' +
    '<div class="field"><div class="lbl"><span>Keep downloads</span><span id="keepLbl">' + keepLabel(KEEP[ki]) + '</span></div>' +
    '<input type="range" id="keep" min="0" max="' + (KEEP.length - 1) + '" step="1" value="' + ki + '"></div>' +
    '<div class="field"><div class="lbl"><span>Backfill</span></div><div class="sub">Also fetch older uploads from each channel</div>' +
    '<div class="chipgrid">' + BACKFILL.map(([d, l]) => '<button data-bf="' + d + '" class="' + (s.backfill_days === d ? 'on' : '') + '">' + l + '</button>').join('') + '</div></div>' +
    '<div class="field"><div class="lbl"><span>Download speed</span></div><div class="sub">Limit on pulls from YouTube</div>' +
    '<div class="chipgrid">' + SPEED.map(([m, l]) => '<button data-speed="' + m + '" class="' + ((s.download_limit_mbps || 0) === m ? 'on' : '') + '">' + l + '</button>').join('') + '</div></div>' +
    '<div class="field"><div class="toggle-row" id="daw"><div class="txt"><div class="lbl"><span>Delete from server after watching</span></div>' +
    '<div class="sub">A day after you finish. Phones have their own setting</div></div><span class="toggle' + (s.delete_after_watched ? ' on' : '') + '">' +
    (s.delete_after_watched ? 'ON' : 'OFF') + '</span></div></div></div>';
  const rm = splitSb(s.sponsorblock_remove);
  const mk = s.sponsorblock_mark === 'all' ? new Set(SB_ALL) : splitSb(s.sponsorblock_mark);
  html += '<div class="section lit">SponsorBlock</div><div class="panel">' +
    '<div class="field"><div class="lbl"><span>Remove</span></div><div class="chipgrid">' +
    SB.filter(([k]) => k !== 'poi_highlight').map(([k, l]) => '<button data-rm-cat="' + k + '" class="' + (rm.has(k) ? 'on' : '') + '">' + l + '</button>').join('') + '</div></div>' +
    '<div class="field"><div class="lbl"><span>Mark as chapters</span></div><div class="chipgrid">' +
    SB.map(([k, l]) => '<button data-mk-cat="' + k + '" class="' + (mk.has(k) ? 'on' : '') + '">' + l + '</button>').join('') + '</div></div></div></div>';
  return html;
}
function bindSetup() {
  const s = S.settings;
  $('#scanNow').onclick = async () => {
    const b = $('#scanNow'); b.disabled = true; b.textContent = 'SCANNING';
    try {
      const r = await api('/api/subscriptions/refresh', {method: 'POST'});
      b.textContent = r.started ? 'STARTED' : 'ALREADY RUNNING';
      setTimeout(() => { loadHealth(); loadLibrary(); }, 15000);
    } catch (e) { b.textContent = 'FAILED'; }
  };
  if (!s) return;
  const change = (patch, redraw = true) => { Object.assign(s, patch); saveSettingsSoon(); if (redraw) render(); };
  for (const el of $$('[data-poll]')) el.onclick = () => change({poll_mode: el.dataset.poll});
  const iv = $('#iv');
  if (iv) {
    iv.oninput = () => { $('#ivLbl').textContent = intervalLabel(+iv.value); };
    iv.onchange = () => change({poll_interval_minutes: +iv.value}, false);
  }
  $('#res').oninput = e => { $('#resLbl').textContent = resLabel(RES[+e.target.value]); };
  $('#res').onchange = e => change({max_resolution: RES[+e.target.value]}, false);
  $('#keep').oninput = e => { $('#keepLbl').textContent = keepLabel(KEEP[+e.target.value]); };
  $('#keep').onchange = e => change({keep_days: KEEP[+e.target.value]}, false);
  for (const b of $$('[data-bf]')) b.onclick = () => change({backfill_days: +b.dataset.bf});
  for (const b of $$('[data-speed]')) b.onclick = () => change({download_limit_mbps: +b.dataset.speed});
  $('#daw').onclick = () => change({delete_after_watched: !s.delete_after_watched});
  for (const b of $$('[data-rm-cat]')) b.onclick = () => {
    // Same rules as the app: cutting a category also drops it from an explicit mark list.
    const k = b.dataset.rmCat, set_ = splitSb(s.sponsorblock_remove);
    set_.has(k) ? set_.delete(k) : set_.add(k);
    const mark = s.sponsorblock_mark === 'all' ? 'all' : [...splitSb(s.sponsorblock_mark)].filter(x => x !== k).join(',');
    change({sponsorblock_remove: [...set_].join(','), sponsorblock_mark: mark});
  };
  for (const b of $$('[data-mk-cat]')) b.onclick = () => {
    const k = b.dataset.mkCat;
    const set_ = s.sponsorblock_mark === 'all' ? new Set(SB_ALL) : splitSb(s.sponsorblock_mark);
    set_.has(k) ? set_.delete(k) : set_.add(k);
    const all = SB_ALL.every(x => set_.has(x)) && set_.size === SB_ALL.length;
    change({sponsorblock_mark: all ? 'all' : [...set_].join(',')});
  };
}

// ------------------------------------------------------------ stats

function statsPage() {
  const sm = summary();
  const today = sm.days[13][0];
  const sel = sm.days.find(([d]) => S.statDay && localDay(d) === S.statDay) || sm.days[13];
  const max = Math.max(60, ...sm.days.map(([, s]) => s));
  const t = Math.floor(sm.today), hh = Math.floor(t / 3600), mm = Math.floor(t % 3600 / 60);
  const dayName = d => localDay(d) === localDay(today) ? 'TODAY'
    : localDay(d) === localDay(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)) ? 'YESTERDAY'
    : d.toLocaleDateString(undefined, {weekday: 'short', day: 'numeric', month: 'short'}).toUpperCase();
  let html = '<div class="stats"><a class="back dim" href="#/setup">&lt; SETUP</a>' + titleBlock('Stats') +
    '<div class="panel counter"><div class="hd">' + (sm.today > 0 ? '<span class="rev">● TODAY</span>' : '<span class="dim">TODAY</span>') +
    '<span class="faint">H  MM</span></div><div class="digits"><span class="ghost">88:88</span>' +
    '<span class="val lit">' + (hh + ':' + String(mm).padStart(2, '0')).padStart(5, ' ') + '</span></div></div>' +
    '<div class="panel cells" style="margin-top:14px">' +
    [['7 days', sm.week], ['Daily avg', sm.avg], ['All time', sm.all]].map(([l, s]) =>
      '<div><b class="lit">' + fmtWatch(s) + '</b><span>' + l + '</span></div>').join('') + '</div>' +
    '<div class="section lit">Last 14 days</div><div class="chart-hd"><span class="dim">' + dayName(sel[0]) + '</span><b class="lit">' + fmtWatch(sel[1]) + '</b></div>' +
    '<div class="leds">' + sm.days.map(([d, s]) => {
      const lit = s <= 0 ? 0 : clamp(Math.floor(s / max * 16), 1, 16);
      let cells = '';
      for (let i = 15; i >= 0; i--) cells += '<i class="' + (i < lit ? 'l' : '') + '"></i>';
      return '<div class="col' + (d === sel[0] ? ' on' : '') + '" data-day="' + localDay(d) + '" title="' + esc(fmtWatch(s)) + '">' + cells + '</div>';
    }).join('') + '</div><div class="days">' +
    sm.days.map(([d]) => '<span class="' + (d === sel[0] ? 'on' : '') + '">' + d.toLocaleDateString(undefined, {weekday: 'narrow'}) + '</span>').join('') + '</div>';
  if (sm.channels.length) {
    const total = sm.channels.reduce((a, [, s]) => a + s, 0) || 1;
    html += '<div class="section lit">Share · 30 days</div>' + sm.channels.map(([n, s]) => {
      const lit = Math.max(1, Math.floor(s / total * 30));
      let segs = '';
      for (let i = 0; i < 30; i++) segs += '<i class="' + (i < lit ? 'l' : '') + '"></i>';
      return '<div class="share-row"><div class="hd"><span class="lit">' + chLabel(n) + '</span><span class="nm">' + esc(n || 'Unknown') + '</span>' +
        '<span class="dim">' + fmtWatch(s) + '</span><span class="lit">' + String(Math.floor(s / total * 100)).padStart(3, ' ') + '%</span></div>' +
        '<div class="segs">' + segs + '</div></div>';
    }).join('');
  }
  return html + '</div>';
}
function bindStats() {
  for (const c of $$('.leds .col')) c.onclick = () => { S.statDay = c.dataset.day; render(); };
}

// ------------------------------------------------------------ keys

document.addEventListener('keydown', e => {
  if (e.target.closest('input, textarea, select')) return;
  const r = route();
  if (e.key === 'Escape') {
    if (!$('#sheet').hidden) { closeSheet(); return; }
    if (r.page === 'watch') { location.hash = '#/air'; return; }
  }
  if (r.page === 'air') {
    const t = $('.tubes');
    if (!t) return;
    if (e.key === 'ArrowRight') { e.preventDefault(); t._step(1); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); t._step(-1); }
    else if (e.key === 'Enter' && !e.target.closest('button, a')) {
      const c = t._centred(); const v = c && find(c.dataset.id); if (v) watch(v);
    }
  }
  if (r.page === 'watch' && e.key === ' ' && e.target === document.body) {
    e.preventDefault(); vid.paused ? vid.play() : vid.pause();
  }
});

// ------------------------------------------------------------ boot

status(); clock(); render();
loadLibrary(); loadStats(); loadSettings(); loadHealth();
setInterval(() => { if (!document.hidden) loadLibrary(); }, 5 * 60 * 1000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) { loadLibrary(); loadStats(); } });
