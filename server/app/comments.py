"""YouTube comments: read with yt-dlp, post through a signed-in account.

Reading needs no account. yt-dlp fetches the top comments on demand and the
result is cached in the database, so reopening a video is instant.

Posting needs the account's YouTube cookies (from the app's sign-in page, or
a cookies.txt export). They are kept in the data folder, never sent back to
a client, and used for nothing but comments: downloads stay anonymous.
"""

import base64
import hashlib
import json
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

from . import db as dbmod
from .config import settings

VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")
# A cached list older than this is fetched again when someone looks.
FRESH_SECONDS = 15 * 60
# Total, top-level, replies in total, replies per thread.
LIMITS = "160,60,100,4"
MAX_TEXT = 10000
ORIGIN = "https://www.youtube.com"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
FALLBACK_CLIENT_VERSION = "2.20261101.00.00"


class CommentError(Exception):
    """Something the viewer should be told, with an HTTP status to send."""

    def __init__(self, message: str, status: int = 502):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------- reading

def slim(raw: dict) -> dict:
    """One yt-dlp comment, cut down to what the clients draw."""
    parent = raw.get("parent") or "root"
    return {
        "id": str(raw.get("id") or ""),
        "parent": "" if parent == "root" else str(parent),
        "author": str(raw.get("author") or ""),
        "text": str(raw.get("text") or ""),
        "likes": int(raw.get("like_count") or 0),
        "time_text": str(raw.get("_time_text") or ""),
        "pinned": bool(raw.get("is_pinned")),
        "uploader": bool(raw.get("author_is_uploader")),
        "hearted": bool(raw.get("is_favorited")),
        "mine": False,
    }


def fetch(video_id: str) -> list[dict]:
    """The video's top comments, straight from YouTube (takes a few seconds)."""
    cmd = [
        "yt-dlp", "--skip-download", "--no-playlist", "--no-warnings",
        "--ignore-no-formats-error", "--write-comments",
        "--extractor-args", f"youtube:max_comments={LIMITS};comment_sort=top",
        "-O", "%(comments)j",
        f"https://www.youtube.com/watch?v={video_id}",
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise CommentError("YouTube took too long")
    except Exception as e:  # yt-dlp missing etc.
        raise CommentError(str(e))
    if proc.returncode != 0:
        tail = (proc.stderr or "").strip().splitlines()[-1:] or ["yt-dlp failed"]
        raise CommentError(tail[0][-300:])
    try:
        raw = json.loads((proc.stdout or "").strip().splitlines()[-1])
    except Exception:
        raw = None
    # Comments turned off comes back as null: an empty list, not an error.
    return [slim(c) for c in (raw or []) if isinstance(c, dict)]


_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()


def _lock_for(video_id: str) -> threading.Lock:
    with _locks_guard:
        return _locks.setdefault(video_id, threading.Lock())


def _read_cache(video_id: str) -> tuple[list[dict], int] | None:
    conn = dbmod.connect(settings.db_path)
    row = conn.execute("SELECT fetched_at, body FROM comments WHERE video_id=?",
                       (video_id,)).fetchone()
    conn.close()
    if not row:
        return None
    try:
        return json.loads(row["body"]), int(row["fetched_at"])
    except Exception:
        return None


def _write_cache(video_id: str, comments: list[dict], fetched_at: int) -> None:
    conn = dbmod.connect(settings.db_path)
    conn.execute(
        "INSERT INTO comments(video_id, fetched_at, body) VALUES(?,?,?) "
        "ON CONFLICT(video_id) DO UPDATE SET fetched_at=excluded.fetched_at, body=excluded.body",
        (video_id, fetched_at, json.dumps(comments)))
    # Lists nobody has opened in a month are for videos long gone.
    conn.execute("DELETE FROM comments WHERE fetched_at < ?", (fetched_at - 30 * 86400,))
    conn.commit()
    conn.close()


def _keep_mine(fresh: list[dict], old: list[dict]) -> list[dict]:
    """A comment you just posted may not be in YouTube's top list yet: keep
    showing it until the fetched list has it."""
    have = {c["id"] for c in fresh}
    mine = [c for c in old if c.get("mine") and c["id"] not in have]
    out = [c for c in mine if not c["parent"]] + fresh
    for c in mine:
        if c["parent"]:
            _insert_reply(out, c)
    return out


def _insert_reply(comments: list[dict], reply: dict) -> None:
    """After the last comment of its thread, or at the end if the thread's gone."""
    at = len(comments)
    for i, c in enumerate(comments):
        if c["id"] == reply["parent"] or c["parent"] == reply["parent"]:
            at = i + 1
    comments.insert(at, reply)


def get(video_id: str, refresh: bool = False) -> tuple[list[dict], int]:
    """Comments and when they were fetched. Cached ones are served while
    fresh; a failed fetch falls back to whatever is cached."""
    if not VIDEO_ID.fullmatch(video_id):
        raise CommentError("unknown video", 404)
    with _lock_for(video_id):
        cached = _read_cache(video_id)
        if cached and not refresh and time.time() - cached[1] < FRESH_SECONDS:
            return cached
        try:
            fresh = fetch(video_id)
        except CommentError:
            if cached:
                return cached
            raise
        if cached:
            fresh = _keep_mine(fresh, cached[0])
        now = int(time.time())
        _write_cache(video_id, fresh, now)
        return fresh, now


# ---------------------------------------------------------------- account

def _account_path() -> Path:
    return Path(settings.data_dir) / "youtube_account.json"


def parse_cookies(text: str) -> dict[str, str]:
    """YouTube cookies from a Netscape cookies.txt or a "name=value; ..." header."""
    text = (text or "").strip()
    cookies: dict[str, str] = {}
    if "\t" in text:
        for line in text.splitlines():
            # "#HttpOnly_" lines are real cookies; other # lines are comments.
            if line.startswith("#HttpOnly_"):
                line = line[len("#HttpOnly_"):]
            elif line.startswith("#"):
                continue
            parts = line.rstrip("\r\n").split("\t")
            if len(parts) < 7:
                continue
            if parts[0].lstrip(".").endswith("youtube.com"):
                cookies[parts[5]] = parts[6]
    else:
        for part in text.replace("\n", ";").split(";"):
            name, sep, value = part.strip().partition("=")
            if sep and name:
                cookies[name] = value
    return cookies


def _sapisid(cookies: dict[str, str]) -> str:
    return cookies.get("SAPISID") or cookies.get("__Secure-3PAPISID") or ""


def _auth_header(cookies: dict[str, str]) -> str:
    """The signature YouTube's own pages send with signed-in requests."""
    ts = str(int(time.time()))
    parts = []
    for scheme, sid in (("SAPISIDHASH", _sapisid(cookies)),
                        ("SAPISID1PHASH", cookies.get("__Secure-1PAPISID", "")),
                        ("SAPISID3PHASH", cookies.get("__Secure-3PAPISID", ""))):
        if sid:
            digest = hashlib.sha1(f"{ts} {sid} {ORIGIN}".encode()).hexdigest()
            parts.append(f"{scheme} {ts}_{digest}")
    return " ".join(parts)


def _client_version() -> str:
    try:
        from yt_dlp.extractor.youtube._base import INNERTUBE_CLIENTS
        return INNERTUBE_CLIENTS["web"]["INNERTUBE_CONTEXT"]["client"]["clientVersion"]
    except Exception:
        return FALLBACK_CLIENT_VERSION


def _innertube(endpoint: str, body: dict, cookies: dict[str, str]) -> dict:
    """One signed-in call to YouTube's own web API."""
    version = _client_version()
    payload = {"context": {"client": {"clientName": "WEB", "clientVersion": version,
                                      "hl": "en"}}, **body}
    req = urllib.request.Request(
        f"{ORIGIN}/youtubei/v1/{endpoint}?prettyPrint=false",
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Cookie": "; ".join(f"{k}={v}" for k, v in cookies.items()),
            "Authorization": _auth_header(cookies),
            "Origin": ORIGIN,
            "X-Origin": ORIGIN,
            "X-Goog-AuthUser": "0",
            "X-Youtube-Client-Name": "1",
            "X-Youtube-Client-Version": version,
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise CommentError("YouTube sign-in expired. Sign in again", 401)
        raise CommentError(f"YouTube said HTTP {e.code}")
    except CommentError:
        raise
    except Exception as e:
        raise CommentError(f"Can't reach YouTube: {e}")


def _find(node, key: str):
    """First value stored under `key`, anywhere in a JSON tree."""
    if isinstance(node, dict):
        if key in node:
            return node[key]
        node = list(node.values())
    if isinstance(node, list):
        for child in node:
            found = _find(child, key)
            if found is not None:
                return found
    return None


def _text_of(node) -> str:
    if isinstance(node, str):
        return node
    if isinstance(node, dict):
        if isinstance(node.get("simpleText"), str):
            return node["simpleText"]
        if isinstance(node.get("runs"), list):
            return "".join(str(r.get("text", "")) for r in node["runs"] if isinstance(r, dict))
    return ""


def _logged_in(resp: dict) -> bool:
    for group in (resp.get("responseContext") or {}).get("serviceTrackingParams") or []:
        for p in group.get("params") or []:
            if p.get("key") == "logged_in":
                return p.get("value") == "1"
    return False


def account() -> dict:
    """Who comments are posted as. Never includes the cookies."""
    try:
        saved = json.loads(_account_path().read_text(encoding="utf-8"))
        if saved.get("cookies"):
            return {"signed_in": True, "name": saved.get("name") or ""}
    except Exception:
        pass
    return {"signed_in": False, "name": ""}


def _saved_cookies() -> dict[str, str]:
    try:
        return dict(json.loads(_account_path().read_text(encoding="utf-8")).get("cookies") or {})
    except Exception:
        return {}


def sign_in(cookie_text: str) -> dict:
    """Check the cookies really are a signed-in YouTube session, then keep them."""
    cookies = parse_cookies(cookie_text)
    if not _sapisid(cookies):
        raise CommentError("No YouTube sign-in in those cookies", 400)
    resp = _innertube("account/account_menu", {}, cookies)
    if not _logged_in(resp):
        raise CommentError("Those cookies aren't signed in to YouTube", 400)
    name = _text_of(_find(resp, "channelHandle")) or _text_of(_find(resp, "accountName"))
    path = _account_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"name": name, "cookies": cookies, "saved_at": int(time.time())}, f)
    tmp.replace(path)
    return account()


def sign_out() -> dict:
    _account_path().unlink(missing_ok=True)
    return account()


# ---------------------------------------------------------------- posting

def _pb_string(field: int, value: str) -> bytes:
    data = value.encode()
    if len(data) > 127:
        raise CommentError("bad comment id", 400)
    return bytes([field << 3 | 2, len(data)]) + data


def post_params(video_id: str, parent: str = "") -> str:
    """The small protobuf YouTube wants with a new comment: which video,
    and for a reply, which thread."""
    buf = _pb_string(2, video_id)
    if parent:
        buf += _pb_string(4, parent)
    buf += bytes([0x2A, 0x02, 0x08, 0x00, 0x50, 0x07])
    return quote(base64.b64encode(buf).decode(), safe="")


def post(video_id: str, text: str, parent: str = "") -> dict:
    """Post a comment (or a reply to `parent`) as the signed-in account."""
    if not VIDEO_ID.fullmatch(video_id):
        raise CommentError("unknown video", 404)
    text = (text or "").strip()
    if not text:
        raise CommentError("Write something first", 400)
    if len(text) > MAX_TEXT:
        raise CommentError("Too long", 400)
    # Replies hang off the thread, even when answering another reply.
    thread = (parent or "").split(".")[0]
    if thread and not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", thread):
        raise CommentError("bad comment id", 400)
    cookies = _saved_cookies()
    if not _sapisid(cookies):
        raise CommentError("Sign in to YouTube first", 401)
    if thread:
        resp = _innertube("comment/create_comment_reply",
                          {"commentText": text, "createReplyParams": post_params(video_id, thread)},
                          cookies)
    else:
        resp = _innertube("comment/create_comment",
                          {"commentText": text, "createCommentParams": post_params(video_id)},
                          cookies)
    # A refused comment still comes back as HTTP 200, with a showErrorAction
    # and no result: only an explicit success counts.
    refused = _find(resp, "showErrorAction")
    status = (resp.get("actionResult") or {}).get("status")
    posted = status == "STATUS_SUCCEEDED" or (
        status is None and (_find(resp, "createCommentAction") is not None
                            or _find(resp, "createCommentReplyAction") is not None))
    if refused is not None or not posted:
        detail = _text_of(_find(refused, "text")) if refused is not None else ""
        raise CommentError((detail or "YouTube didn't take the comment").rstrip("."))
    new_id = _find(resp, "commentId")
    comment = {
        "id": new_id if isinstance(new_id, str) and new_id else f"local-{int(time.time() * 1000)}",
        "parent": thread,
        "author": account()["name"] or "You",
        "text": text,
        "likes": 0,
        "time_text": "just now",
        "pinned": False,
        "uploader": False,
        "hearted": False,
        "mine": True,
    }
    # Show it right away: YouTube's top list won't have it for a while.
    with _lock_for(video_id):
        cached = _read_cache(video_id)
        comments, fetched_at = cached if cached else ([], int(time.time()))
        if thread:
            _insert_reply(comments, comment)
        else:
            comments.insert(0, comment)
        _write_cache(video_id, comments, fetched_at)
    return comment
