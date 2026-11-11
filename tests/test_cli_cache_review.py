"""Review regressions for CLI search and negative SponsorBlock cache states."""
import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from iSponsorBlockTV import api_helpers, config_setup


class SegmentCachePolicyTests(unittest.TestCase):
    def test_empty_success_result_must_have_finite_ttl(self):
        # New SponsorBlock entries can be submitted after a video is first played.
        segments, ignore_ttl = api_helpers.ApiHelper.process_segments(
            {"segments": []}, 0
        )
        self.assertEqual(segments, [])
        self.assertFalse(ignore_ttl)

    def test_locked_segments_still_preserve_nonexpiring_cache(self):
        segments, ignore_ttl = api_helpers.ApiHelper.process_segments(
            {"segments": [{"segment": [5, 12], "UUID": "locked-1", "locked": 1}]},
            0,
        )
        self.assertEqual(segments[0]["end"], 12)
        self.assertTrue(ignore_ttl)

    def test_unlocked_segments_remain_expiring(self):
        segments, ignore_ttl = api_helpers.ApiHelper.process_segments(
            {"segments": [{"segment": [5, 12], "UUID": "unlocked-1", "locked": 0}]},
            0,
        )
        self.assertEqual(segments[0]["start"], 5)
        self.assertFalse(ignore_ttl)


class CliChannelSearchTests(unittest.TestCase):
    def test_channel_search_wizard_uses_api_helper_contract(self):
        channels_queried = []
        sessions = []

        class FakeSession:
            def __init__(self):
                self.close = AsyncMock()

        class FakeApiHelper:
            def __init__(self, *_args, **_kwargs):
                pass

            async def search_channels(self, channel_name):
                channels_queried.append(channel_name)
                return []  # No matches must not crash the CLI wizard.

        async def fake_web_session(_use_proxy):
            session = FakeSession()
            sessions.append(session)
            return session

        config = SimpleNamespace(
            devices=[],
            apikey="some-api-key",
            skip_categories=["sponsor"],
            channel_whitelist=[],
            minimum_skip_length=1,
            skip_count_tracking=True,
            mute_ads=False,
            skip_ads=False,
            auto_play=True,
            sponsorblock_api_url="https://example.invalid/",
            use_proxy=False,
            save=MagicMock(),
        )
        # Proxy, devices, API key, categories, channel whitelist, minimum
        # length, reports, mute, skip and autoplay.
        responses = ["n", "n", "n", "n", "y", "n", "y", "n", "n", "y"]

        try:
            with (
                patch.object(config_setup, "get_yn_input", side_effect=responses),
                patch.object(config_setup, "create_web_session", fake_web_session),
                patch.object(config_setup.api_helpers, "ApiHelper", FakeApiHelper),
                patch("builtins.input", side_effect=["missing-channel", "/exit", ""]),
            ):
                config_setup.main(config, debug=False)
        finally:
            # The CLI wizard creates an event loop; don't leak it to other tests.
            loop = asyncio.get_event_loop()
            if not loop.is_closed():
                loop.close()
            asyncio.set_event_loop(None)

        self.assertEqual(channels_queried, ["missing-channel"])
        config.save.assert_called_once()
        sessions[0].close.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
