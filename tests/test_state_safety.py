"""Deterministic regression tests for application-owned asynchronous states.

Run with: PYTHONPATH=src python -m unittest discover -s tests -v
No network access, TV hardware, or real credentials are required.
"""
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from iSponsorBlockTV import api_helpers, helpers, main, setup_wizard
from iSponsorBlockTV.conditional_ttl_cache import AsyncConditionalTTL


class DeviceLifecycleTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.controller = SimpleNamespace(
            disconnect=AsyncMock(),
            is_available=AsyncMock(return_value=True),
            refresh_auth=AsyncMock(),
            connect=AsyncMock(return_value=True),
            linked=MagicMock(return_value=False),
            connected=MagicMock(return_value=True),
            screen_name="Test TV",
            subscribe_task_watchdog=None,
            subscribe_task=None,
            playback_speed=1.0,
        )
        device = SimpleNamespace(offset=0.0, screen_id="test-screen", name="Test TV")
        with patch.object(main.ytlounge, "YtLoungeApi", return_value=self.controller):
            self.listener = main.DeviceListener(
                api_helper=None,
                config=SimpleNamespace(join_name="test"),
                device=device,
                debug=False,
                web_session=None,
            )

    async def test_cancel_before_subscription_is_safe_and_idempotent(self):
        await self.listener.cancel()
        await self.listener.cancel()
        self.assertTrue(self.listener.cancelled)
        self.assertEqual(self.controller.disconnect.await_count, 2)

    async def test_cancel_joins_active_internal_tasks(self):
        async def pending():
            await asyncio.Event().wait()

        pending_tasks = [asyncio.create_task(pending()) for _ in range(3)]
        await asyncio.sleep(0)
        (
            self.listener.task,
            self.controller.subscribe_task,
            self.controller.subscribe_task_watchdog,
        ) = pending_tasks
        await self.listener.cancel()
        self.assertTrue(all(task.done() for task in pending_tasks))
        self.assertTrue(all(task.cancelled() for task in pending_tasks))

    async def test_availability_does_not_swallow_cancellation(self):
        self.controller.is_available = AsyncMock(side_effect=asyncio.CancelledError())
        with self.assertRaises(asyncio.CancelledError):
            await self.listener.is_available()

    async def test_auth_refresh_does_not_swallow_cancellation(self):
        self.controller.refresh_auth = AsyncMock(side_effect=asyncio.CancelledError())
        sleep_mock = AsyncMock(side_effect=[None, asyncio.CancelledError()])
        with patch.object(main.asyncio, "sleep", sleep_mock):
            with self.assertRaises(asyncio.CancelledError):
                await self.listener.refresh_auth_loop()
        self.assertEqual(sleep_mock.await_count, 1)

    async def test_connection_loop_does_not_swallow_cancellation(self):
        async def cancel_refresh():
            self.listener.cancelled = True
            raise asyncio.CancelledError()

        self.controller.refresh_auth = cancel_refresh
        self.controller.linked = MagicMock(side_effect=[False, True])
        async def subscribe(_callback):
            return asyncio.sleep(0)
        self.controller.subscribe_monitored = subscribe
        with patch.object(main.asyncio, "sleep", new=AsyncMock()):
            with self.assertRaises(asyncio.CancelledError):
                await self.listener.loop()


class _NetworkResponse:
    def __init__(self, status, payload=None, text=""):
        self.status = status
        self.payload = payload
        self.body = text
        self.json_calls = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    async def json(self):
        self.json_calls += 1
        if self.status != 200:
            raise ValueError("Error pages must not be parsed as SponsorBlock JSON")
        return self.payload

    async def text(self):
        return self.body


class _NetworkSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = 0

    def get(self, *_args, **_kwargs):
        self.calls += 1
        return self.responses.pop(0)


class SponsorBlockCacheTests(unittest.IsolatedAsyncioTestCase):
    async def test_explicit_no_cache_outcome_is_retried(self):
        executions = 0

        @AsyncConditionalTTL(time_to_live=300, maxsize=2)
        async def fetch():
            nonlocal executions
            executions += 1
            if executions == 1:
                return [], None  # transient result: do not cache
            return ["recovered"], True

        self.assertEqual(await fetch(), [])
        self.assertEqual(await fetch(), ["recovered"])
        self.assertEqual(await fetch(), ["recovered"])
        self.assertEqual(executions, 2)

    async def test_temporary_http_error_does_not_hide_later_success(self):
        error = _NetworkResponse(503, text="<html>temporary unavailable</html>")
        success = _NetworkResponse(
            200,
            payload=[{
                "videoID": "video-1",
                "segments": [{"segment": [2.0, 8.0], "UUID": "uuid-1", "locked": 1}],
            }],
        )
        session = _NetworkSession([error, success])
        config = SimpleNamespace(
            apikey=None,
            skip_categories=["sponsor"],
            channel_whitelist=[],
            skip_count_tracking=False,
            devices=[],
            minimum_skip_length=0,
            sponsorblock_api_url="https://example.invalid/api/",
        )
        helper = api_helpers.ApiHelper(config, session)
        self.assertEqual(await helper.get_segments("video-1"), [])
        self.assertEqual(error.json_calls, 0)
        recovered = await helper.get_segments("video-1")
        self.assertEqual(len(recovered), 1)
        self.assertEqual(recovered[0]["start"], 2.0)
        self.assertEqual(recovered[0]["end"], 8.0)
        self.assertEqual(session.calls, 2)


class ConfigSaveTests(unittest.TestCase):
    def test_save_failure_preserves_in_memory_and_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            config = helpers.Config(directory)
            config.devices = [{"screen_id": "screen-1", "name": "Living room"}]
            config.save()
            previous_file = Path(config.config_file).read_bytes()
            with patch.object(helpers.json, "dump", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    config.save()
            self.assertEqual(Path(config.config_file).read_bytes(), previous_file)
            self.assertEqual(config.config_file, str(Path(directory) / "config.json"))
            self.assertEqual(config.data_dir, directory)
            self.assertEqual(config.devices[0]["screen_id"], "screen-1")

    def test_save_keeps_original_config_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            config = helpers.Config(directory)
            config.devices = [{"screen_id": "screen-1", "name": "Test", "offset": 0}]
            config.save()
            result = json.loads(Path(config.config_file).read_text(encoding="utf-8"))
            self.assertEqual(result["devices"], config.devices)
            self.assertNotIn("config_file", result)
            self.assertNotIn("data_dir", result)
            self.assertEqual(result["skip_count_tracking"], True)


class SetupStateTests(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_edit_invalidates_previous_pairing_validation(self):
        screen = setup_wizard.AddDevice(
            config=SimpleNamespace(), web_session=None, api_helper=SimpleNamespace()
        )
        screen.pairing_validation_generation = 4
        event = SimpleNamespace(
            validation_result=SimpleNamespace(is_valid=False),
            input=SimpleNamespace(value="not-a-valid-code"),
        )
        screen.changed_pairing_code(event)
        self.assertEqual(screen.pairing_validation_generation, 5)
        self.assertFalse(screen.pairing_can_add)

    async def test_empty_channel_search_reenables_button(self):
        api = SimpleNamespace(search_channels=AsyncMock(return_value=[]))
        screen = setup_wizard.AddChannel(config=SimpleNamespace(), web_session=None, api_helper=api)
        widgets = {
            "#channel-name-input-search": SimpleNamespace(value="unknown-channel"),
            "#search-channel-button": SimpleNamespace(disabled=False),
            "#add-channel-info": SimpleNamespace(update=MagicMock()),
            "#add-channel-button-search": SimpleNamespace(disabled=False),
            "#channel-search-results": SimpleNamespace(remove_children=MagicMock()),
        }
        with patch.object(screen, "query_one", side_effect=lambda selector: widgets[selector]):
            await screen.handle_search_channel()
        self.assertFalse(widgets["#search-channel-button"].disabled)
        api.search_channels.assert_awaited_once_with("unknown-channel")


if __name__ == "__main__":
    unittest.main()
