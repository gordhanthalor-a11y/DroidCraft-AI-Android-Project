"""
Jarvis Creator Studio — TTS (voice) provider adapters + QC helpers.

Providers (spec B1): Smallest AI (Waves), HeyGen (text-to-speech).
Keys server के env / DB से आती हैं — app से नहीं (public repo safe).
"""
import io
import json
import logging
import os
import subprocess
import tempfile
from pathlib import Path

import httpx

log = logging.getLogger("jarvis-tts")


# ------------------------------------------------------------------ TTS

class TtsError(Exception):
    pass


def _smallest(text: str, voice: str, speed: float, sentence_mode: bool) -> bytes:
    """Smallest AI Waves API — https://docs.smallest.ai"""
    api_key = os.getenv("SMALLEST_API_KEY", "")
    tenant = os.getenv("SMALLEST_TENANT", "")
    if not api_key or not tenant:
        raise TtsError("Smallest AI keys server पर set नहीं हैं (SMALLEST_API_KEY / SMALLEST_TENANT)")

    sentences = [s.strip() for s in text.replace("\n", " ").split("।") if s.strip()] if sentence_mode else [text]
    if not sentences:
        sentences = [text]

    client = httpx.Client(timeout=120.0)
    chunks = []
    for s in sentences:
        r = client.post(
            f"https://waves-api.smallest.ai/api/v1/{tenant}/synthesize",
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json={
                "text": s,
                "model": "lightning",
                "voice": voice or os.getenv("SMALLEST_VOICE", "mikey"),
                "speed": max(0.5, min(2.0, speed)),
                "sample_rate": 24000,
            },
        )
        if r.status_code >= 300:
            raise TtsError(f"Smallest {r.status_code}: {r.text[:200]}")
        chunks.append(r.content)
        if sentence_mode and len(sentences) > 1:
            chunks.append(b"\x00" * 2400)  # ~100ms silence @24k mono16
    return b"".join(chunks)


def _heygen(text: str, voice: str, speed: float, sentence_mode: bool) -> bytes:
    """HeyGen text-to-speech API."""
    api_key = os.getenv("HEYGEN_API_KEY", "")
    if not api_key:
        raise TtsError("HeyGen key server पर set नहीं है (HEYGEN_API_KEY)")
    client = httpx.Client(timeout=120.0)
    sentences = [s.strip() for s in text.replace("\n", " ").split("।") if s.strip()] if sentence_mode else [text]
    if not sentences:
        sentences = [text]
    chunks = []
    for s in sentences:
        r = client.post(
            "https://api.heygen.com/v1/text_to_speech",
            headers={"X-Api-Key": api_key, "Content-Type": "application/json"},
            json={"text": s, "voice_id": voice or os.getenv("HEYGEN_VOICE", ""), "speed": speed},
        )
        if r.status_code >= 300:
            raise TtsError(f"HeyGen {r.status_code}: {r.text[:200]}")
        audio_url = (r.json().get("data") or {}).get("audio_url") or r.json().get("audio_url")
        if not audio_url:
            raise TtsError(f"HeyGen से audio URL नहीं मिला: {r.text[:200]}")
        chunks.append(client.get(audio_url).content)
        if sentence_mode and len(sentences) > 1:
            chunks.append(b"\x00" * 2400)
    return b"".join(chunks)


PROVIDERS = {
    "smallest": _smallest,
    "heygen": _heygen,
    "elevenlabs": _elevenlabs,
}


def _elevenlabs(text: str, voice: str, speed: float, sentence_mode: bool,
                style: float = 0.35, stability: float = 0.5) -> bytes:
    """ElevenLabs TTS — emotion style के साथ (Bunty - Funny Best Friend default)।"""
    api_key = os.getenv("ELEVENLABS_API_KEY", "")
    if not api_key:
        raise TtsError("ElevenLabs key server पर set नहीं है (ELEVENLABS_API_KEY)")
    voice_id = voice or "7b9mYhmnp0y2qSH1FnBL"  # Bunty - Funny Best Friend
    sentences = [s.strip() for s in text.replace("\n", " ").split("।") if s.strip()] if sentence_mode else [text]
    if not sentences:
        sentences = [text]
    client = httpx.Client(timeout=120.0)
    chunks = []
    for s in sentences:
        r = client.post(
            f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}",
            headers={"xi-api-key": api_key, "Content-Type": "application/json",
                     "accept": "audio/mpeg"},
            json={
                "text": s,
                "model_id": os.getenv("ELEVENLABS_MODEL", "eleven_multilingual_v2"),
                "voice_settings": {
                    "stability": max(0.0, min(1.0, stability)),
                    "similarity_boost": 0.75,
                    "style": max(0.0, min(1.0, style)),
                    "use_speaker_boost": True,
                },
            },
        )
        if r.status_code >= 300:
            raise TtsError(f"ElevenLabs {r.status_code}: {r.text[:200]}")
        chunks.append(r.content)
        if sentence_mode and len(sentences) > 1:
            chunks.append(b"\x00" * 2400)

    out = b"".join(chunks)
    # speed adjust (भावना के अनुसार) — ffmpeg atempo
    if abs(speed - 1.0) > 0.02:
        out = _atempo(out, speed)
    return out


def _atempo(mp3: bytes, speed: float) -> bytes:
    import subprocess, tempfile
    from pathlib import Path
    with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
        f.write(mp3); src = f.name
    dst = src + ".out.mp3"
    try:
        r = subprocess.run(
            ["ffmpeg", "-y", "-i", src, "-filter:a", f"atempo={max(0.5, min(2.0, speed))}",
             "-b:a", "128k", dst],
            capture_output=True, timeout=120,
        )
        if r.returncode == 0 and Path(dst).exists():
            return Path(dst).read_bytes()
    except Exception:
        pass
    finally:
        Path(src).unlink(missing_ok=True)
        Path(dst).unlink(missing_ok=True)
    return mp3


def synthesize(text: str, provider: str, voice: str = "", speed: float = 1.0,
               sentence_mode: bool = True, style: float = 0.35, stability: float = 0.5) -> bytes:
    fn = PROVIDERS.get(provider.lower())
    if not fn:
        raise TtsError(f"अनजान provider: {provider}")
    if provider.lower() == "elevenlabs":
        return fn(text, voice, speed, sentence_mode, style, stability)
    return fn(text, voice, speed, sentence_mode)


def voices(provider: str) -> list[dict]:
    """Provider की voice list — env override या built-in defaults."""
    if provider == "elevenlabs":
        raw = os.getenv("ELEVENLABS_VOICES", "")
        if raw:
            return json.loads(raw)
        return [
            {"id": "7b9mYhmnp0y2qSH1FnBL", "name": "Bunty - Funny Best Friend"},
            {"id": "21m00Tcm4TlvDq8ikWAM", "name": "Rachel (English)"},
            {"id": "EXAVITQu4vr4xnSDxMaL", "name": "Bella (English)"},
            {"id": "AZnzlk1XvdvUeBnXmlld", "name": "Domi (English)"},
        ]
    if provider == "smallest":
        raw = os.getenv("SMALLEST_VOICES", "")
        if raw:
            return json.loads(raw)
        return [
            {"id": "mikey", "name": "Mikey (Hindi)"},
            {"id": "neerja", "name": "Neerja (Hindi)"},
            {"id": "prabhat", "name": "Prabhat (Hindi)"},
            {"id": "aditi", "name": "Aditi (Hindi)"},
        ]
    if provider == "heygen":
        raw = os.getenv("HEYGEN_VOICES", "")
        if raw:
            return json.loads(raw)
        return [
            {"id": "heyg_en_01", "name": "HeyGen English 01"},
            {"id": "heyg_hi_01", "name": "HeyGen Hindi 01"},
        ]
    return []


# ------------------------------------------------------------------ QC (ffprobe)

def qc_video(file_path: str) -> dict:
    """ffprobe से पूरा QC: resolution, SAR, DAR, duration, audio, loudness."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", file_path],
            capture_output=True, text=True, timeout=120,
        )
        if out.returncode != 0:
            return {"ok": False, "error": out.stderr.strip()[:300]}
        info = json.loads(out.stdout)
    except FileNotFoundError:
        return {"ok": False, "error": "ffprobe server पर installed नहीं है (apt install ffmpeg)"}
    except Exception as e:
        return {"ok": False, "error": str(e)[:300]}

    vstream = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)
    astream = next((s for s in info.get("streams", []) if s.get("codec_type") == "audio"), None)
    fmt = info.get("format", {})

    result = {
        "ok": True,
        "duration_sec": float(fmt.get("duration") or 0),
        "size_bytes": int(fmt.get("size") or 0),
        "format": fmt.get("format_name", ""),
        "video": None,
        "audio": None,
        "loudness": None,
        "warnings": [],
    }
    if vstream:
        w = int(vstream.get("width") or 0)
        h = int(vstream.get("height") or 0)
        sar = vstream.get("sample_aspect_ratio") or "0:1"
        dar = vstream.get("display_aspect_ratio") or "0:1"
        # rotation
        rot = 0
        side = vstream.get("side_data_list") or []
        for sd in side:
            if "rotation" in sd:
                rot = int(sd["rotation"])
        result["video"] = {
            "width": w, "height": h,
            "sample_aspect_ratio": sar,
            "display_aspect_ratio": dar,
            "rotation": rot,
            "codec": vstream.get("codec_name", ""),
            "fps": (vstream.get("avg_frame_rate") or "0/1"),
        }
    else:
        result["warnings"].append("video stream नहीं मिली")
    if astream:
        result["audio"] = {
            "codec": astream.get("codec_name", ""),
            "sample_rate": int(astream.get("sample_rate") or 0),
            "channels": int(astream.get("channels") or 0),
        }
    else:
        result["warnings"].append("audio stream नहीं मिली")

    # loudness — ffmpeg loudnorm में two-pass का पहला pass
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
            wav = tf.name
        subprocess.run(
            ["ffmpeg", "-y", "-i", file_path, "-vn", "-ac", "1", "-ar", "16000",
             "-t", "60", wav],
            capture_output=True, timeout=120,
        )
        lo = subprocess.run(
            ["ffmpeg", "-i", wav, "-af", "loudnorm=print_format=json", "-f", "null", "-"],
            capture_output=True, text=True, timeout=120,
        )
        Path(wav).unlink(missing_ok=True)
        import re
        m = re.search(r"\{[^}]+\}", lo.stderr, re.S)
        if m:
            lj = json.loads(m.group(0))
            result["loudness"] = {
                "integrated_lufs": float(lj.get("input_i") or 0),
                "true_peak_dbfs": float(lj.get("input_tp") or 0),
                "lra": float(lj.get("input_lra") or 0),
                "threshold": float(lj.get("input_thresh") or 0),
            }
    except Exception as e:
        result["warnings"].append(f"loudness measure fail: {str(e)[:120]}")
    return result
