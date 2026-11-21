import sys
import types
import unittest
from concurrent.futures import TimeoutError as FutureTimeoutError
from unittest.mock import patch

import numpy as np

from server import tts
from server import voxcpm_provider as provider


class VoxCPMProviderTests(unittest.TestCase):
    def test_uses_official_space_endpoint_and_input_order(self):
        observed = {}

        class FakeJob:
            def result(self, timeout=None):
                self.assert_timeout = timeout
                # The Space wrapper returns (Audio payload, last_successful_seed).
                return ((24_000, np.array([0.0, 0.5, -0.5], dtype=np.float32)), 12345)

        class FakeClient:
            def __init__(self, **kwargs):
                observed["client_kwargs"] = kwargs

            def submit(self, *args, **kwargs):
                observed["inputs"] = args
                observed["submit_kwargs"] = kwargs
                observed["job"] = FakeJob()
                return observed["job"]

            def close(self):
                observed["closed"] = True

        fake_gradio_client = types.ModuleType("gradio_client")
        fake_gradio_client.Client = FakeClient
        with patch.dict(sys.modules, {"gradio_client": fake_gradio_client}), patch.object(
            provider, "_to_mp3", side_effect=lambda audio: audio
        ), patch.object(provider, "_change_speed", side_effect=lambda audio, speed: audio):
            audio = provider.synthesize_voxcpm("Namaste Jarvis", "Speak warmly", speed=1.2)

        self.assertTrue(audio.startswith(b"RIFF"))
        self.assertTrue(observed["closed"])
        self.assertEqual(observed["client_kwargs"]["src"], "OpenBMB/VoxCPM-Demo")
        self.assertEqual(observed["submit_kwargs"]["api_name"], "/generate")
        self.assertEqual(observed["inputs"][:6], (
            "Namaste Jarvis", "Speak warmly", None, False, "", 2.0
        ))
        self.assertEqual(observed["inputs"][6:9], (False, False, 10))
        self.assertIsInstance(observed["inputs"][9], int)
        self.assertGreaterEqual(observed["inputs"][9], 0)
        self.assertLess(observed["inputs"][9], 2**32)
        self.assertEqual(observed["job"].assert_timeout, provider.SPACE_TIMEOUT_SECONDS)

    def test_timeout_cancels_job_and_closes_client(self):
        observed = {}

        class FakeJob:
            def result(self, timeout=None):
                raise FutureTimeoutError()

            def cancel(self):
                observed["cancelled"] = True

        class FakeClient:
            def __init__(self, **kwargs):
                pass

            def submit(self, *args, **kwargs):
                return FakeJob()

            def close(self):
                observed["closed"] = True

        fake_gradio_client = types.ModuleType("gradio_client")
        fake_gradio_client.Client = FakeClient
        with patch.dict(sys.modules, {"gradio_client": fake_gradio_client}):
            with self.assertRaisesRegex(provider.VoxCPMError, "timed out"):
                provider.synthesize_voxcpm("hello")

        self.assertTrue(observed["cancelled"])
        self.assertTrue(observed["closed"])

    def test_dispatch_translates_provider_errors_to_gateway_errors(self):
        with patch.object(tts, "synthesize_voxcpm", side_effect=provider.VoxCPMError("Space timed out")):
            with self.assertRaises(tts.TtsError) as raised:
                tts.synthesize("hello", "voxcpm")
        self.assertEqual(raised.exception.status_code, 504)

    def test_empty_text_is_rejected_without_creating_a_client(self):
        with self.assertRaisesRegex(provider.VoxCPMError, "no text"):
            provider.synthesize_voxcpm("  ")


if __name__ == "__main__":
    unittest.main()
