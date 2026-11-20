"""
B3 — Dubbing Studio: link → download → transcript → Hindi script → voice render → video.
Server ही सारा काम करता है (yt-dlp + ffmpeg + TTS); app सिर्फ़ UI/remote है।
"""
import json
import logging
import os
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path

import httpx

log = logging.getLogger("jarvis-dubbing")
JOBS_DIR = Path(__file__).parent / "jobs"
JOBS_DIR.mkdir(exist_ok=True)


def _job_path(job_id: str) -> Path:
    return JOBS_DIR / f"{job_id}.json"


def load_job(job_id: str) -> dict:
    p = _job_path(job_id)
    if not p.exists():
        return {}
    return json.loads(p.read_text())


def save_job(job: dict):
    _job_path(job["id"]).write_text(json.dumps(job, ensure_ascii=False))


def _run(cmd: list[str], timeout: int = 900) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


# ------------------------------------------------------------------ download + transcript

def prepare(job_id: str, url: str, lang: str = "hi"):
    job = {"id": job_id, "url": url, "status": "downloading", "progress": 5,
           "video": None, "audio": None, "transcript": "", "error": None}
    save_job(job)

    def work():
        try:
            work_dir = Path(tempfile.mkdtemp(prefix="dub_"))
            video = work_dir / "source.mp4"
            # yt-dlp से download
            r = _run(["yt-dlp", "-f", "bv*[height<=1080]+ba/b", "--merge-output-format", "mp4",
                      "-o", str(video), url], timeout=1200)
            if r.returncode != 0 or not video.exists():
                job.update(status="failed", error=f"download fail: {r.stderr[:300]}")
                save_job(job)
                return
            job.update(status="extracting_audio", progress=40, video=str(video))
            save_job(job)

            audio = work_dir / "audio.wav"
            _run(["ffmpeg", "-y", "-i", str(video), "-vn", "-ac", "1", "-ar", "16000", str(audio)])
            job.update(audio=str(audio), progress=60, status="transcribing")
            save_job(job)

            # transcript — whisper उपलब्ध हो तो; वरना ffmpeg silence detect के साथ placeholder
            transcript = ""
            if subprocess.run(["which", "whisper"], capture_output=True).returncode == 0:
                tr = _run(["whisper", str(audio), "--language", lang, "--model", "small",
                           "--output_format", "txt", "--output_dir", str(work_dir)], timeout=1800)
                txt = work_dir / "audio.txt"
                if txt.exists():
                    transcript = txt.read_text(encoding="utf-8", errors="ignore").strip()
            if not transcript:
                transcript = "(transcript server पर नहीं मिला — whisper install करें: pip install openai-whisper)"
            job.update(status="ready", progress=100, transcript=transcript)
            save_job(job)
        except Exception as e:
            log.error("dub prepare error: %s", e)
            job.update(status="failed", error=str(e)[:300])
            save_job(job)

    threading.Thread(target=work, daemon=True).start()
    return job


# ------------------------------------------------------------------ render

def render(
    job_id: str, script: str, voice: str, provider: str = "smallest", speed: float = 1.0,
    provider_key: str = "",
):
    job = load_job(job_id)
    if not job or not job.get("video"):
        return {"ok": False, "error": "job नहीं मिला या video ready नहीं"}
    job.update(status="rendering", progress=10)
    save_job(job)

    def work():
        try:
            work_dir = Path(job["video"]).parent
            # 1) script → TTS (server/tts.py का use)
            from tts import synthesize
            mp3 = work_dir / "voice.mp3"
            mp3.write_bytes(
                synthesize(
                    script, provider, voice, speed,
                    sentence_mode=True, provider_key=provider_key,
                )
            )

            # 2) video के audio को voice से replace; duration voice के अनुसार
            out = work_dir / "dubbed.mp4"
            r = _run([
                "ffmpeg", "-y", "-i", job["video"], "-i", str(mp3),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                "-b:a", "128k", "-shortest", str(out),
            ], timeout=1800)
            if r.returncode != 0 or not out.exists():
                job.update(status="failed", error=f"render fail: {r.stderr[:300]}")
                save_job(job)
                return
            job.update(status="done", progress=100, output=str(out))
            save_job(job)
        except Exception as e:
            log.error("dub render error: %s", e)
            job.update(status="failed", error=str(e)[:300])
            save_job(job)

    threading.Thread(target=work, daemon=True).start()
    return {"ok": True, "job_id": job_id}
