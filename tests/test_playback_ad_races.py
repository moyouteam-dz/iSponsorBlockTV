"""Regression tests for playback snapshots, stale skips and ad command ordering.

These tests do not connect to a real YouTube device.
"""
import asyncio
import logging
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pyytlounge

from iSponsorBlockTV import main, ytlounge


class PlaybackSnapshotTests(unittest.IsolatedAsyncioTestCase):
    async def test_each_playback_event_delivers_immutable_snapshot(self):
        snapshots = []

        async def record(state):
            snapshots.append(state)

        fake = SimpleNamespace(
            _playback_state=ytlounge.PlaybackState(),
            _state_callback=record,
        )
        playing = SimpleNamespace(value=1)
        await ytlounge.YtLoungeApi._handle_now_playing_event(
            fake,
            SimpleNamespace(video_id="first", current_time=4, duration=60, state=playing),
        )
        await ytlounge.YtLoungeApi._handle_now_playing_event(
            fake,
            SimpleNamespace(video_id="second", current_time=1, duration=90, state=playing),
        )
        self.assertIsNot(snapshots[0], snapshots[1])
        self.assertEqual(snapshots[0].videoId, "first")
        self.assertEqual(snapshots[0].currentTime, 4)
        self.assertEqual(snapshots[1].videoId, "second")
        with self.assertRaises(AttributeError):
            snapshots[0].videoId = "modified"


class StaleSkipTests(unittest.IsolatedAsyncioTestCase):
    async def test_superseded_video_cannot_seek_or_report_after_delayed_fetch(self):
        entered = asyncio.Event()
        release = asyncio.Event()

        async def fetch(video_id):
            if video_id == "first":
                entered.set()
                try:
                    await release.wait()
                except asyncio.CancelledError:
                    # Simulate an external library that finishes a request
                    # even when cancellation was requested.
                    await release.wait()
                return [{"start": 0, "end": 5, "UUID": ["uuid-first"]}]
            return []

        api = SimpleNamespace(
            get_segments=AsyncMock(side_effect=fetch),
            mark_viewed_segments=AsyncMock(),
        )
        controller = SimpleNamespace(
            playback_speed=1.0,
            seek_to=AsyncMock(),
            disconnect=AsyncMock(),
            subscribe_task=None,
            subscribe_task_watchdog=None,
        )
        device = SimpleNamespace(offset=0, name="TV", screen_id="test-tv")
        with patch.object(main.ytlounge, "YtLoungeApi", return_value=controller):
            listener = main.DeviceListener(api, SimpleNamespace(), device, False, None)

        state_first = SimpleNamespace(
            videoId="first", currentTime=0.0, state=SimpleNamespace(value=1)
        )
        state_second = SimpleNamespace(
            videoId="second", currentTime=0.0, state=SimpleNamespace(value=1)
        )
        await listener(state_first)
        await asyncio.wait_for(entered.wait(), timeout=1)
        await listener(state_second)
        release.set()
        await asyncio.sleep(0.05)
        controller.seek_to.assert_not_awaited()
        api.mark_viewed_segments.assert_not_awaited()
        await listener.cancel()

    async def test_stopping_cancels_a_waiting_skip(self):
        api = SimpleNamespace(
            get_segments=AsyncMock(return_value=[
                {"start": 8.0, "end": 12.0, "UUID": ["uuid"]}
            ]),
            mark_viewed_segments=AsyncMock(),
        )
        controller = SimpleNamespace(
            playback_speed=1.0,
            seek_to=AsyncMock(),
            disconnect=AsyncMock(),
            subscribe_task=None,
            subscribe_task_watchdog=None,
        )
        device = SimpleNamespace(offset=0, name="TV", screen_id="test-tv")
        with patch.object(main.ytlounge, "YtLoungeApi", return_value=controller):
            listener = main.DeviceListener(api, SimpleNamespace(), device, False, None)
        await listener(SimpleNamespace(
            videoId="first", currentTime=0, state=SimpleNamespace(value=1)
        ))
        await asyncio.sleep(0)
        await listener.cancel()
        controller.seek_to.assert_not_awaited()


class AdCommandOrderingTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.lounge = object.__new__(ytlounge.YtLoungeApi)
        self.lounge.logger = logging.getLogger("AdCommandOrderingTests")
        self.lounge.mute_ads = True
        self.lounge.skip_ads = False
        self.lounge.last_event_time = 0

    async def test_latest_ad_event_wins_when_previous_mute_is_slow(self):
        entered = asyncio.Event()
        release = asyncio.Event()
        applied = []

        async def mute(muted, override=False):
            if muted:
                entered.set()
                await release.wait()
            applied.append(muted)

        self.lounge.mute = mute
        with patch.object(pyytlounge.YtLoungeApi, "_process_event", new=AsyncMock()):
            await self.lounge._process_event(
                "onAdStateChange",
                [{"adState": "1", "currentTime": "3", "isSkipEnabled": "false"}],
            )
            await asyncio.wait_for(entered.wait(), timeout=1)
            await self.lounge._process_event(
                "onAdStateChange",
                [{"adState": "0", "currentTime": "3", "isSkipEnabled": "false"}],
            )
            release.set()
            await asyncio.sleep(0.05)
        self.assertEqual(applied, [True, False])

    async def test_repeated_ad_start_does_not_enqueue_duplicate_mutes(self):
        calls = []

        async def mute(muted, override=False):
            calls.append(muted)

        self.lounge.mute = mute
        with patch.object(pyytlounge.YtLoungeApi, "_process_event", new=AsyncMock()):
            for _ in range(3):
                await self.lounge._process_event(
                    "onAdStateChange",
                    [{"adState": "1", "currentTime": "3", "isSkipEnabled": "false"}],
                )
            await asyncio.sleep(0.05)
        self.assertEqual(calls, [True])


if __name__ == "__main__":
    unittest.main()
