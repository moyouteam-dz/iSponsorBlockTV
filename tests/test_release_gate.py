"""Release-gate tests for async cleanup, pairing and segment payload matching.

No requests are sent to the Internet or any real television.
"""
import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from iSponsorBlockTV import api_helpers


class FakeHttpResponse:
    def __init__(self, status, payload=None):
        self.status = status
        self.payload = payload

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def json(self):
        return self.payload


class FakeHttpSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = 0

    def get(self, *_args, **_kwargs):
        self.calls += 1
        return self.responses.pop(0)


class SegmentMatchingTests(unittest.IsolatedAsyncioTestCase):
    async def test_unrelated_hash_bucket_is_not_cached_forever(self):
        session = FakeHttpSession([
            FakeHttpResponse(200, [{
                "videoID": "another-video",
                "segments": [{"segment": [1, 4], "UUID": "unrelated", "locked": 1}],
            }]),
            FakeHttpResponse(200, [{
                "videoID": "requested-video",
                "segments": [{"segment": [5, 12], "UUID": "target", "locked": 1}],
            }]),
        ])
        config = SimpleNamespace(
            apikey="", skip_categories=["sponsor"], channel_whitelist=[],
            skip_count_tracking=False, devices=[], minimum_skip_length=0,
            sponsorblock_api_url="https://example.invalid/api/",
        )
        helper = api_helpers.ApiHelper(config, session)
        self.assertEqual(await helper.get_segments("requested-video"), [])
        segments = await helper.get_segments("requested-video")
        self.assertEqual(len(segments), 1)
        self.assertEqual(segments[0]["start"], 5)
        self.assertEqual(session.calls, 2)


class PairingCancellationTests(unittest.IsolatedAsyncioTestCase):
    async def test_pairing_while_closing_does_not_suppress_cancellation(self):
        class CancelledPairResponse(FakeHttpResponse):
            async def json(self):
                raise asyncio.CancelledError()

        class Session:
            def post(self, *_args, **_kwargs):
                return CancelledPairResponse(200)

        config = SimpleNamespace(
            apikey="", skip_categories=[], channel_whitelist=[],
            skip_count_tracking=False, devices=[], minimum_skip_length=0,
            sponsorblock_api_url="https://example.invalid/api/",
        )
        helper = api_helpers.ApiHelper(config, Session())
        with self.assertRaises(asyncio.CancelledError):
            await helper.pair_with_code("123456789012")


class DiscoveryCleanupTests(unittest.IsolatedAsyncioTestCase):
    async def test_closing_merged_results_joins_and_closes_sources(self):
        waiting = asyncio.Event()
        slow_started = asyncio.Event()
        cleaned = set()

        async def quick_source():
            try:
                # Wait until slow_source is awaiting, to make cleanup deterministic.
                await slow_started.wait()
                yield {"name": "Quick"}
                await waiting.wait()
            finally:
                cleaned.add("quick")

        async def slow_source():
            try:
                slow_started.set()
                await waiting.wait()
                yield {"name": "Slow"}
            finally:
                cleaned.add("slow")

        merged = api_helpers.merge_iterators([quick_source(), slow_source()])
        result = await asyncio.wait_for(anext(merged), timeout=1)
        self.assertEqual(result["name"], "Quick")
        await merged.aclose()
        self.assertEqual(cleaned, {"quick", "slow"})


if __name__ == "__main__":
    unittest.main()
