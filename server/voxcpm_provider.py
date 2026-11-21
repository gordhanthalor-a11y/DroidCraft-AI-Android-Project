"""Hugging Face Gradio adapter for the OpenBMB VoxCPM2 demo Space."""
from __future__ import annotations

import base64
import io
import logging
import os
import secrets
import subprocess
import tempfile
import wave
from concurrent.futures import TimeoutError as FutureTimeoutError
from pathlib import Path
from typing import Any

import httpx

log = logging.getLogger("jarvis-voxcpm")
SPACE_ID = os.getenv("VOXCPM_SPACE", "OpenBMB/VoxCPM-Demo").strip() or "OpenBMB/VoxCPM-Demo"
SPACE_TIMEOUT_SECONDS = max(30, int(os.getenv("VOXCPM_TIMEOUT_SECONDS", "110")))


class VoxCPMError(RuntimeError):
    """The VoxCPM2 Space failed or returned an unusable audio result."""


def synthesize_voxcpm(text: str, style_request: str = "", speed: float = 1.0) -> bytes:
    """Call the Space's /generate endpoint using its published Gradio input order.

    The current OpenBMB Space inputs are text, control_instruction, reference_wav,
    show_prompt_text, prompt_text, cfg_value, do_normalize, denoise, dit_steps, seed.
    The control instruction is the natural-language style request used by VoxCPM2.
    """
    clean_text = text.strip()
    if not clean_text:
        raise VoxCPMError("There is no text to synthesize.")

    try:
        from gradio_client import Client
    except ImportError as error:  # pragma: no cover - deployment configuration error
        raise VoxCPMError("The server is missing the gradio_client dependency for VoxCPM2.") from error

    instruction = style_request.strip() or "Use a natural, clear conversational delivery."
    client = None
    try:
        client = Client(
            src=SPACE_ID,
            verbose=False,
            httpx_kwargs={"timeout": float(SPACE_TIMEOUT_SECONDS)},
        )
        job = client.submit(
            clean_text,
            instruction[:500],  # control_instruction
            None,               # reference_wav
            False,              # show_prompt_text
            "",                 # prompt_text
            2.0,                # cfg_value
            False,              # do_normalize
            False,              # reference-audio denoise
            10,                 # dit_steps
            secrets.randbelow(2**32),
            api_name="/generate",
        )
        result = job.result(timeout=SPACE_TIMEOUT_SECONDS)
    except FutureTimeoutError as error:
        if "job" in locals():
            try:
                job.cancel()
            except Exception:
                pass
        raise VoxCPMError(f"VoxCPM2 Hugging Face Space timed out after {SPACE_TIMEOUT_SECONDS} seconds.") from error
    except VoxCPMError:
        raise
    except Exception as error:
        detail = str(error).strip() or error.__class__.__name__
        raise VoxCPMError(f"VoxCPM2 Hugging Face Space request failed: {detail[:400]}") from error
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass

    audio = _extract_audio_bytes(result)
    mp3 = _to_mp3(audio)
    if abs(float(speed) - 1.0) > 0.02:
        mp3 = _change_speed(mp3, speed)
    if not mp3:
        raise VoxCPMError("VoxCPM2 returned an empty audio file.")
    return mp3


def _extract_audio_bytes(result: Any) -> bytes:
    """Unwrap the Audio + seed Gradio result, supporting filepath and numpy audio forms."""
    if isinstance(result, (tuple, list)):
        if len(result) == 2 and isinstance(result[0], (int, float)):
            return _numpy_audio_to_wav(int(result[0]), result[1])
        for item in result:
            try:
                return _extract_audio_bytes(item)
            except VoxCPMError:
                continue
        raise VoxCPMError("VoxCPM2 returned no audio output in its Gradio result.")

    if isinstance(result, (bytes, bytearray)):
        data = bytes(result)
        if data:
            return data
        raise VoxCPMError("VoxCPM2 returned an empty audio payload.")

    if isinstance(result, dict):
        encoded = result.get("data")
        if isinstance(encoded, str) and encoded:
            try:
                return base64.b64decode(encoded)
            except Exception:
                pass
        candidate = result.get("path") or result.get("name") or result.get("url")
        if candidate:
            return _read_audio_path(str(candidate))

    for attribute in ("path", "name", "url"):
        candidate = getattr(result, attribute, None)
        if isinstance(candidate, str) and candidate:
            return _read_audio_path(candidate)

    if isinstance(result, str) and result.strip():
        return _read_audio_path(result.strip())

    raise VoxCPMError(f"VoxCPM2 returned an unsupported audio result ({type(result).__name__}).")


def _read_audio_path(value: str) -> bytes:
    if value.startswith(("https://", "http://")):
        try:
            response = httpx.get(value, timeout=30.0, follow_redirects=True)
            response.raise_for_status()
            if response.content:
                return response.content
        except Exception as error:
            raise VoxCPMError(f"Could not download VoxCPM2 audio: {str(error)[:250]}") from error
    else:
        path = Path(value)
        if path.is_file():
            data = path.read_bytes()
            if data:
                return data
    raise VoxCPMError("VoxCPM2 finished but its generated audio file was not available.")


def _numpy_audio_to_wav(sample_rate: int, samples: Any) -> bytes:
    try:
        import numpy as np

        array = np.asarray(samples)
        if array.size == 0:
            raise ValueError("empty sample array")
        if array.ndim > 1:
            array = array.mean(axis=1)
        if array.dtype.kind == "f":
            array = (np.clip(array, -1.0, 1.0) * 32767.0).astype(np.int16)
        else:
            array = np.clip(array, -32768, 32767).astype(np.int16)
        pcm = array.tobytes()
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(max(8000, sample_rate))
            wav.writeframes(pcm)
        return output.getvalue()
    except Exception as error:
        raise VoxCPMError(f"Could not decode VoxCPM2 audio samples: {str(error)[:250]}") from error


def _to_mp3(audio: bytes) -> bytes:
    """The existing app TTS contract is MP3, so normalize the Space's WAV result here."""
    if not audio:
        raise VoxCPMError("VoxCPM2 returned an empty audio file.")
    src_name = dst_name = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=".audio", delete=False) as source:
            source.write(audio)
            src_name = source.name
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as target:
            dst_name = target.name
        completed = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src_name,
             "-vn", "-codec:a", "libmp3lame", "-b:a", "160k", dst_name],
            capture_output=True,
            timeout=40,
            check=False,
        )
        output = Path(dst_name).read_bytes() if completed.returncode == 0 and Path(dst_name).is_file() else b""
        if not output:
            detail = completed.stderr.decode("utf-8", "replace").strip()[-300:]
            raise VoxCPMError("Could not convert VoxCPM2 audio to MP3" + (f": {detail}" if detail else "."))
        return output
    except FileNotFoundError as error:
        raise VoxCPMError("FFmpeg is required to convert VoxCPM2 audio on this server.") from error
    except subprocess.TimeoutExpired as error:
        raise VoxCPMError("VoxCPM2 audio conversion timed out.") from error
    finally:
        if src_name:
            Path(src_name).unlink(missing_ok=True)
        if dst_name:
            Path(dst_name).unlink(missing_ok=True)


def _change_speed(mp3: bytes, speed: float) -> bytes:
    src_name = dst_name = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as source:
            source.write(mp3)
            src_name = source.name
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as target:
            dst_name = target.name
        completed = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src_name,
             "-filter:a", f"atempo={max(0.5, min(2.0, float(speed)))}", "-codec:a", "libmp3lame", dst_name],
            capture_output=True,
            timeout=40,
            check=False,
        )
        result = Path(dst_name).read_bytes() if completed.returncode == 0 and Path(dst_name).is_file() else b""
        return result or mp3
    except Exception as error:
        log.warning("VoxCPM2 speed adjustment failed; using original audio: %s", error)
        return mp3
    finally:
        if src_name:
            Path(src_name).unlink(missing_ok=True)
        if dst_name:
            Path(dst_name).unlink(missing_ok=True)
