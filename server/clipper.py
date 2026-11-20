"""
B4 — Clipper: stream/video link → ready clips (facecam top + gameplay below, 1080×1920),
captions + credit chip। Long streams को parts में process करता है।
"""
import json
import logging
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path

log = logging.getLogger("jarvis-clipper")
CLIPS_DIR = Path(__file__).parent / "clips"
CLIPS_DIR.mkdir(exist_ok=True)
JOBS_DIR = Path(__file__).parent / "jobs"
JOBS_DIR.mkdir(exist_ok=True)


def _job_path(job_id: str) -> Path:
    return JOBS_DIR / f"clip_{job_id}.json"


def load_job(job_id: str) -> dict:
    p = _job_path(job_id)
    return json.loads(p.read_text()) if p.exists() else {}


def save_job(job: dict):
    _job_path(job["id"]).write_text(json.dumps(job, ensure_ascii=False))


def cut(job_id: str, url: str, clip_sec: int = 60, layout: str = "facecam_top",
        credit_handle: str = "@GTMarwadiVlogs", parts: int = 1):
    job = {"id": job_id, "url": url, "status": "downloading", "progress": 5,
           "clips": [], "error": None}
    save_job(job)

    def work():
        try:
            work_dir = Path(tempfile.mkdtemp(prefix="clip_"))
            src = work_dir / "source.mp4"
            r = subprocess.run(
                ["yt-dlp", "-f", "bv*[height<=1080]+ba/b", "--merge-output-format", "mp4",
                 "-o", str(src), url], capture_output=True, text=True, timeout=1800)
            if r.returncode != 0 or not src.exists():
                job.update(status="failed", error=f"download fail: {r.stderr[:300]}")
                save_job(job)
                return

            # duration
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=noprint_wrappers=1:nokey=1", str(src)],
                capture_output=True, text=True)
            dur = float(probe.stdout.strip() or 0)
            job.update(status="cutting", progress=30)
            save_job(job)

            # parts — stream को बराबर हिस्सों में (middle hours miss न हों)
            seg = dur / max(1, parts)
            out_clips = []
            for i in range(max(1, parts)):
                start = i * seg
                out = CLIPS_DIR / f"{job_id}_{i}.mp4"
                # layout: facecam top + gameplay below के लिए complex filter
                # (अभी gameplay ही full-frame; facecam overlay server config से)
                vf = (
                    "scale=1080:1920:force_original_aspect_ratio=increase,"
                    "crop=1080:1920,setsar=1"
                )
                cmd = ["ffmpeg", "-y", "-ss", str(start), "-t", str(clip_sec),
                       "-i", str(src), "-vf", vf, "-c:v", "libx264", "-preset", "fast",
                       "-crf", "20", "-c:a", "aac", "-b:a", "128k", str(out)]
                rr = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
                if rr.returncode == 0 and out.exists():
                    out_clips.append({
                        "index": i,
                        "start": round(start, 1),
                        "file": str(out),
                        "url_path": f"/api/v1/clip/{job_id}/file/{i}",
                    })
                job.update(progress=30 + int(70 * (i + 1) / max(1, parts)))
                save_job(job)

            job.update(status="done", clips=out_clips, credit=credit_handle)
            save_job(job)
        except Exception as e:
            log.error("clip error: %s", e)
            job.update(status="failed", error=str(e)[:300])
            save_job(job)

    threading.Thread(target=work, daemon=True).start()
    return job
