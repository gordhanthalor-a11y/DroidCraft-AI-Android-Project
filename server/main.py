"""
Jarvis Social Relay — Buffer-जैसा background publishing server.
Android app queue को sync करता है; app बंद होने पर भी due posts publish करता है.

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
Deploy: Render / Railway / अपना VPS (SOCIAL_SETUP.md §F)
"""
import json
import sqlite3
import time
import threading
import logging
import tempfile
import base64
import os
import uuid
from uuid import uuid4
from datetime import datetime, timezone
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException, Request, UploadFile, File
from fastapi.responses import JSONResponse, Response, FileResponse
from pydantic import BaseModel

from tts import synthesize, voices, qc_video, TtsError
from dubbing import prepare as dub_prepare, render as dub_render, load_job as dub_job
from clipper import cut as clip_cut, load_job as clip_job

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("jarvis-social")

DB_PATH = Path(__file__).parent / "jarvis_social.db"
app = FastAPI(title="Jarvis Social Relay")

# Optional YouTube cookies for yt-dlp deployments that need authenticated access.
_youtube_cookies_b64 = os.environ.get("YT_COOKIES_B64", "").strip()
if _youtube_cookies_b64:
    try:
        cookies_path = Path("/tmp/yt_cookies.txt")
        cookies_path.write_bytes(base64.b64decode(_youtube_cookies_b64))
        cookies_path.chmod(0o600)
        log.info("YouTube cookies loaded from YT_COOKIES_B64")
    except Exception as error:
        log.warning("Could not decode YT_COOKIES_B64: %s", error.__class__.__name__)

# ------------------------------------------------------------------ DB

def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = db()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS posts (
            id TEXT PRIMARY KEY,
            video_path TEXT NOT NULL,
            video_url TEXT,
            title TEXT NOT NULL,
            description TEXT,
            hashtags TEXT,
            platform TEXT NOT NULL,
            account_id TEXT,
            scheduled_at INTEGER NOT NULL,
            status TEXT DEFAULT 'QUEUED',
            external_id TEXT,
            published_at INTEGER,
            retry_count INTEGER DEFAULT 0,
            last_error TEXT,
            created_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS accounts (
            platform TEXT PRIMARY KEY,
            access_token TEXT NOT NULL,
            refresh_token TEXT,
            expires_at INTEGER,
            extra TEXT
        );
        CREATE TABLE IF NOT EXISTS history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id TEXT, platform TEXT, attempt_at INTEGER,
            success INTEGER, external_id TEXT, error_code TEXT, error_message TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_posts_due ON posts(status, scheduled_at);
        """
    )
    conn.commit()
    conn.close()

init_db()

# ------------------------------------------------------------------ API models

class PostIn(BaseModel):
    id: str
    video_path: str | None = None
    video_url: str | None = None
    title: str
    description: str = ""
    hashtags: str = ""
    platform: str
    account_id: str | None = None
    scheduled_at: int
    status: str = "QUEUED"
    external_id: str | None = None
    retry_count: int = 0
    last_error: str | None = None

class AccountIn(BaseModel):
    platform: str
    access_token: str
    refresh_token: str | None = None
    expires_at: int | None = None
    extra: dict | None = None

@app.get("/api/v1/health")
def health():
    return {"ok": True, "time": int(time.time())}


# ------------------------------------------------------------------ Creator Studio (Phase 1)

class TtsRequest(BaseModel):
    text: str
    provider: str = "smallest"
    voice: str = ""
    speed: float = 1.0
    sentence_mode: bool = True
    style: float = 0.35
    stability: float = 0.5
    style_request: str = ""


@app.post("/api/v1/tts")
def tts(req: TtsRequest, request: Request):
    """Hindi script → MP3 bytes (Smallest / HeyGen / ElevenLabs / VoxCPM2)."""
    try:
        provider_key = request.headers.get("X-Provider-Key") or ""
        eleven_key = request.headers.get("X-ElevenLabs-Key") or ""
        data = synthesize(
            req.text,
            req.provider,
            req.voice,
            req.speed,
            req.sentence_mode,
            req.style,
            req.stability,
            eleven_key=eleven_key,
            provider_key=provider_key,
            style_request=req.style_request,
        )
        return Response(content=data, media_type="audio/mpeg")
    except TtsError as e:
        raise HTTPException(status_code=e.status_code, detail=str(e))
    except Exception as e:
        log.error("tts error: %s", e)
        raise HTTPException(status_code=502, detail=f"TTS fail: {e}")


@app.get("/api/v1/tts/voices")
def tts_voices(provider: str = "smallest"):
    return {"provider": provider, "voices": voices(provider)}


@app.post("/api/v1/qc")
async def qc(file: UploadFile = File(...)):
    """Video file का पूरा QC — ffprobe: resolution/SAR/DAR/duration/audio/loudness."""
    suffix = Path(file.filename or "video.mp4").suffix or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tf:
        tf.write(await file.read())
        path = tf.name
    try:
        return qc_video(path)
    finally:
        Path(path).unlink(missing_ok=True)


# ------------------------------------------------------------------ B3 Dubbing

class DubPrepareIn(BaseModel):
    url: str
    lang: str = "hi"


@app.post("/api/v1/dub/prepare")
def dub_prepare_ep(req: DubPrepareIn):
    job_id = uuid4().hex[:12]
    return dub_prepare(job_id, req.url, req.lang)


@app.get("/api/v1/dub/{job_id}")
def dub_status(job_id: str):
    return dub_job(job_id)


class DubRenderIn(BaseModel):
    job_id: str
    script: str
    voice: str = ""
    provider: str = "smallest"
    speed: float = 1.0


@app.post("/api/v1/dub/render")
def dub_render_ep(req: DubRenderIn, request: Request):
    return dub_render(
        req.job_id,
        req.script,
        req.voice,
        req.provider,
        req.speed,
        provider_key=request.headers.get("X-Provider-Key", ""),
    )


@app.get("/api/v1/dub/{job_id}/file")
def dub_file(job_id: str):
    job = dub_job(job_id)
    if not job.get("output"):
        raise HTTPException(status_code=404, detail="output नहीं मिला")
    return FileResponse(job["output"], media_type="video/mp4")


# ------------------------------------------------------------------ B4 Clipper

class ClipIn(BaseModel):
    url: str
    clip_sec: int = 60
    layout: str = "facecam_top_gameplay_bottom"
    credit: str = "@GTMarwadiVlogs"
    parts: int = 1


@app.post("/api/v1/clip")
def clip_ep(req: ClipIn):
    job_id = uuid4().hex[:12]
    return clip_cut(job_id, req.url, req.clip_sec, req.layout, req.credit, req.parts)


@app.get("/api/v1/clip/{job_id}")
def clip_status(job_id: str):
    return clip_job(job_id)


@app.get("/api/v1/clip/{job_id}/file/{index}")
def clip_file(job_id: str, index: int):
    job = clip_job(job_id)
    for c in job.get("clips", []):
        if c["index"] == index:
            return FileResponse(c["file"], media_type="video/mp4")
    raise HTTPException(status_code=404, detail="clip नहीं मिला")

@app.post("/api/v1/posts/sync")
def sync_posts(posts: list[PostIn]):
    """App queue को server पर upsert करता है."""
    conn = db()
    n = 0
    for p in posts:
        conn.execute(
            """
            INSERT OR REPLACE INTO posts
            (id, video_path, video_url, title, description, hashtags, platform,
             account_id, scheduled_at, status, external_id, published_at, retry_count,
             last_error, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (p.id, p.video_path or "", p.video_url, p.title, p.description, p.hashtags,
             p.platform, p.account_id, p.scheduled_at, p.status, p.external_id,
             None, p.retry_count, p.last_error, int(time.time())),
        )
        n += 1
    conn.commit()
    conn.close()
    return {"synced": n}

@app.post("/api/v1/accounts")
def save_account(a: AccountIn):
    conn = db()
    conn.execute(
        "INSERT OR REPLACE INTO accounts(platform, access_token, refresh_token, expires_at, extra) VALUES (?,?,?,?,?)",
        (a.platform, a.access_token, a.refresh_token, a.expires_at,
         json.dumps(a.extra or {})),
    )
    conn.commit()
    conn.close()
    return {"ok": True}

@app.get("/api/v1/dashboard")
def dashboard():
    conn = db()

    def cnt(where):
        r = conn.execute(f"SELECT COUNT(*) c FROM posts WHERE {where}").fetchone()
        return r["c"]

    scheduled = cnt("status='QUEUED'")
    published = cnt("status='PUBLISHED'")
    failed = cnt("status='FAILED'")
    total = cnt("1=1")
    hist = conn.execute(
        "SELECT * FROM history ORDER BY attempt_at DESC LIMIT 50"
    ).fetchall()
    conn.close()
    return {
        "scheduled": scheduled,
        "published": published,
        "failed": failed,
        "total": total,
        "history": [dict(h) for h in hist],
    }

# ------------------------------------------------------------------ Publishers

YOUTUBE_UPLOAD = "https://www.googleapis.com/upload/youtube/v3/videos"
YOUTUBE_API = "https://www.googleapis.com/youtube/v3"
META_GRAPH = "https://graph.facebook.com/v21.0"
http = httpx.Client(timeout=180.0)

def publish_youtube(post: dict) -> dict:
    """Resumable YouTube upload — असली Data API v3."""
    conn = db()
    acc = conn.execute("SELECT * FROM accounts WHERE platform='youtube'").fetchone()
    conn.close()
    if not acc:
        return {"success": False, "error": "youtube account नहीं है"}
    token = acc["access_token"]
    video_url = post.get("video_url") or post.get("video_path")
    if not (video_url and str(video_url).startswith("http")):
        return {"success": False, "error": "video_url publicly reachable नहीं है"}

    meta = {
        "snippet": {
            "title": post["title"][:100],
            "description": (post.get("description") or "")[:4000],
            "tags": [t for t in (post.get("hashtags") or "").replace("#", "").split(",") if t.strip()][:12],
        },
        "status": {"privacyStatus": "public", "selfDeclaredMadeForKids": False},
    }
    r = http.post(
        f"{YOUTUBE_UPLOAD}?uploadType=resumable&part=snippet,status",
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": "application/json; charset=UTF-8"},
        json=meta,
    )
    if r.status_code >= 300:
        return {"success": False, "error": f"upload init {r.status_code}: {r.text[:200]}"}
    session = r.headers.get("Location")
    r = http.put(
        session,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "video/*"},
        content=http.get(video_url).content,
    )
    if r.status_code not in (200, 201):
        return {"success": False, "error": f"upload {r.status_code}: {r.text[:200]}"}
    vid = r.json().get("id")
    return {"success": True, "external_id": vid, "error": None}

def publish_instagram(post: dict) -> dict:
    conn = db()
    acc = conn.execute("SELECT * FROM accounts WHERE platform='instagram'").fetchone()
    conn.close()
    if not acc:
        return {"success": False, "error": "instagram account नहीं है"}
    token = acc["access_token"]
    ig_id = (json.loads(acc["extra"]) if acc["extra"] else {}).get("ig_user_id", "")
    video_url = post.get("video_url") or post.get("video_path")
    if not (video_url and str(video_url).startswith("http")):
        return {"success": False, "error": "video_url publicly reachable नहीं है"}

    r = http.post(
        f"{META_GRAPH}/{ig_id}/media",
        data={"media_type": "REELS", "video_url": video_url,
              "caption": post.get("description") or post["title"], "access_token": token},
    )
    if r.status_code >= 300:
        return {"success": False, "error": f"container {r.status_code}: {r.text[:200]}"}
    creation = r.json().get("id")
    r = http.post(
        f"{META_GRAPH}/{ig_id}/media_publish",
        data={"creation_id": creation, "access_token": token},
    )
    if r.status_code >= 300:
        return {"success": False, "error": f"publish {r.status_code}: {r.text[:200]}"}
    return {"success": True, "external_id": r.json().get("id"), "error": None}

def publish_facebook(post: dict) -> dict:
    conn = db()
    acc = conn.execute("SELECT * FROM accounts WHERE platform='facebook'").fetchone()
    conn.close()
    if not acc:
        return {"success": False, "error": "facebook account नहीं है"}
    token = acc["access_token"]
    page_id = (json.loads(acc["extra"]) if acc["extra"] else {}).get("page_id", "")
    video_url = post.get("video_url") or post.get("video_path")
    if not (video_url and str(video_url).startswith("http")):
        return {"success": False, "error": "video_url publicly reachable नहीं है"}
    r = http.post(
        f"{META_GRAPH}/{page_id}/videos",
        params={"access_token": token},
        data={"description": post.get("description") or post["title"],
              "title": post["title"]},
        files={"source": ("video.mp4", http.get(video_url).content, "video/mp4")},
    )
    if r.status_code >= 300:
        return {"success": False, "error": f"{r.status_code}: {r.text[:200]}"}
    return {"success": True, "external_id": r.json().get("id"), "error": None}

PUBLISHERS = {"youtube": publish_youtube, "instagram": publish_instagram,
              "facebook": publish_facebook}
MAX_RETRY = 5

def run_due():
    """हर मिनट due posts publish करता है (exponential backoff के साथ)."""
    while True:
        try:
            conn = db()
            now = int(time.time() * 1000)
            rows = conn.execute(
                "SELECT * FROM posts WHERE status='QUEUED' AND scheduled_at <= ? ORDER BY scheduled_at",
                (now,),
            ).fetchall()
            for row in rows:
                p = dict(row)
                fn = PUBLISHERS.get(p["platform"])
                if not fn:
                    conn.execute("UPDATE posts SET status='FAILED', last_error=? WHERE id=?",
                                 ("unknown platform", p["id"]))
                    conn.commit()
                    continue
                result = fn(p)
                if result["success"]:
                    conn.execute(
                        "UPDATE posts SET status='PUBLISHED', external_id=?, published_at=? WHERE id=?",
                        (result.get("external_id"), int(time.time() * 1000), p["id"]),
                    )
                    conn.execute(
                        "INSERT INTO history(post_id, platform, attempt_at, success, external_id, error_code, error_message) VALUES (?,?,?,?,?,?,?)",
                        (p["id"], p["platform"], int(time.time() * 1000), 1,
                         result.get("external_id"), None, None),
                    )
                    log.info("published ✅ %s/%s", p["platform"], result.get("external_id"))
                else:
                    retry = p["retry_count"] + 1
                    backoff = (1 << min(retry, 6)) * 60_000
                    if retry <= MAX_RETRY:
                        conn.execute(
                            "UPDATE posts SET status='QUEUED', retry_count=?, last_error=?, scheduled_at=? WHERE id=?",
                            (retry, result["error"], int(time.time() * 1000) + backoff, p["id"]),
                        )
                    else:
                        conn.execute(
                            "UPDATE posts SET status='FAILED', retry_count=?, last_error=? WHERE id=?",
                            (retry, result["error"], p["id"]),
                        )
                    conn.execute(
                        "INSERT INTO history(post_id, platform, attempt_at, success, external_id, error_code, error_message) VALUES (?,?,?,?,?,?,?)",
                        (p["id"], p["platform"], int(time.time() * 1000), 0,
                         None, "PUBLISH_FAIL", result["error"]),
                    )
                    log.warning("failed ❌ %s: %s", p["id"], result["error"])
                conn.commit()
            conn.close()
        except Exception as e:
            log.error("scheduler loop error: %s", e)
        time.sleep(30)

@app.on_event("startup")
def _startup():
    threading.Thread(target=run_due, daemon=True).start()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
