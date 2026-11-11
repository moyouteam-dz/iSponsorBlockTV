"""No-network tests for clean service shutdown and correct skip telemetry."""
import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from iSponsorBlockTV import main


class _Connection:
    def __init__(self):
        self.closed = False

    async def close(self):
        self.closed = True


class MainShutdownTests(unittest.IsolatedAsyncioTestCase):
    async def test_cancellation_invokes_device_cleanup_before_closing_session(self):
        session = _Connection()
        connector = _Connection()
        running = asyncio.Event()
        created = []
        events = []

        class FakeListener:
            def __init__(self, *args):
                created.append(self)

            async def initialize_web_session(self):
                pass

            async def loop(self):
                running.set()
                await asyncio.Event().wait()

            async def refresh_auth_loop(self):
                await asyncio.Event().wait()

            async def cancel(self):
                events.append("device_cancel")
                self.cancelled = True

        config = SimpleNamespace(devices=[object()], use_proxy=False)
        with (
            patch.object(main.aiohttp, "TCPConnector", return_value=connector),
            patch.object(main.aiohttp, "ClientSession", return_value=session),
            patch.object(main.api_helpers, "ApiHelper", return_value=object()),
            patch.object(main, "DeviceListener", FakeListener),
            patch.object(main, "signal"),
        ):
            running_task = asyncio.create_task(main.main_async(config, False, False))
            await asyncio.wait_for(running.wait(), timeout=1)
            running_task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(running_task, timeout=1)

        self.assertEqual(events, ["device_cancel"])
        self.assertTrue(created[0].cancelled)
        self.assertTrue(session.closed)
        self.assertTrue(connector.closed)

    async def test_startup_failure_still_closes_http_session(self):
        session = _Connection()
        connector = _Connection()

        class FailingListener:
            def __init__(self, *args):
                self.cancelled = False

            async def initialize_web_session(self):
                raise RuntimeError("session init failed")

            async def cancel(self):
                self.cancelled = True

        config = SimpleNamespace(devices=[object()], use_proxy=False)
        with (
            patch.object(main.aiohttp, "TCPConnector", return_value=connector),
            patch.object(main.aiohttp, "ClientSession", return_value=session),
            patch.object(main.api_helpers, "ApiHelper", return_value=object()),
            patch.object(main, "DeviceListener", FailingListener),
            patch.object(main, "signal"),
        ):
            with self.assertRaisesRegex(RuntimeError, "session init failed"):
                await main.main_async(config, False, False)

        self.assertTrue(session.closed)
        self.assertTrue(connector.closed)


class SkipReportingTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.seek = AsyncMock()
        self.report = AsyncMock()
        device = SimpleNamespace(offset=0, name="Test", screen_id="screen")
        controller = SimpleNamespace(
            seek_to=self.seek,
            subscribe_task=None,
            subscribe_task_watchdog=None,
            disconnect=AsyncMock(),
        )
        with patch.object(main.ytlounge, "YtLoungeApi", return_value=controller):
            self.listener = main.DeviceListener(
                SimpleNamespace(mark_viewed_segments=self.report),
                SimpleNamespace(), device, False, None,
            )

    async def test_no_view_report_when_seek_fails(self):
        self.seek.side_effect = RuntimeError("TV disconnected before seek")
        with self.assertRaisesRegex(RuntimeError, "TV disconnected"):
            await self.listener.skip(0, 10.0, ["segment-1"])
        self.report.assert_not_awaited()

    async def test_rejected_seek_does_not_report_view(self):
        # pyytlounge.seek_to() returns False when the command is not accepted.
        self.seek.return_value = False
        await self.listener.skip(0, 10.0, ["segment-1"])
        self.seek.assert_awaited_once_with(10.0)
        self.report.assert_not_awaited()

    async def test_successful_seek_reports_once(self):
        self.seek.return_value = True
        await self.listener.skip(0, 10.0, ["segment-1"])
        self.seek.assert_awaited_once_with(10.0)
        self.report.assert_awaited_once_with(["segment-1"])


if __name__ == "__main__":
    unittest.main()
