import asyncio
import logging
import time
from signal import SIGINT, SIGTERM, signal
from typing import Optional

import aiohttp

from . import api_helpers, ytlounge
from .debug_helpers import AiohttpTracer


class DeviceListener:
    def __init__(self, api_helper, config, device, debug: bool, web_session):
        self.task: Optional[asyncio.Task] = None
        self._playback_generation = 0
        self.api_helper = api_helper
        self.offset = device.offset
        self.name = device.name
        self.cancelled = False
        self.logger = logging.getLogger(f"iSponsorBlockTV-{device.screen_id}")
        self.web_session = web_session
        self.lounge_controller = ytlounge.YtLoungeApi(
            device.screen_id, config, api_helper, self.logger
        )

    # Ensures that we have a valid auth token
    async def refresh_auth_loop(self):
        while not self.cancelled:
            await asyncio.sleep(60 * 60 * 24)  # Refresh every 24 hours
            if self.cancelled:
                return
            try:
                await self.lounge_controller.refresh_auth()
            except Exception:
                self.logger.exception("Failed to refresh device authorization")

    async def is_available(self):
        try:
            return await self.lounge_controller.is_available()
        except Exception:
            return False

    # Main subscription loop
    async def loop(self):
        lounge_controller = self.lounge_controller
        while not self.cancelled:
            while not self.cancelled and not lounge_controller.linked():
                try:
                    self.logger.debug("Refreshing auth")
                    await lounge_controller.refresh_auth()
                except Exception:
                    if not self.cancelled:
                        await asyncio.sleep(10)
            if self.cancelled:
                return
            while not self.cancelled and not await self.is_available():
                self.logger.debug("Waiting for device to be available")
                await asyncio.sleep(10)
            if self.cancelled:
                return
            try:
                await lounge_controller.connect()
            except Exception:
                self.logger.exception("Could not connect to device")
            while not self.cancelled and not lounge_controller.connected():
                # Doesn't connect to the device if it's a kids profile (it's broken)
                self.logger.debug("Waiting for device to be connected")
                await asyncio.sleep(10)
                if self.cancelled:
                    return
                try:
                    await lounge_controller.connect()
                except Exception:
                    self.logger.exception("Could not reconnect to device")
            if self.cancelled:
                return
            self.logger.info(
                "Connected to device %s (%s)", lounge_controller.screen_name, self.name
            )
            try:
                self.logger.debug("Subscribing to lounge")
                sub = await lounge_controller.subscribe_monitored(self)
                await sub
            except Exception:
                self.logger.exception("Device subscription failed")

    # Method called on playback state change
    async def __call__(self, state):
        time_start = time.monotonic()
        if self.cancelled:
            return
        self._playback_generation += 1
        generation = self._playback_generation
        if self.task is not None:
            self.task.cancel()
        self.task = asyncio.create_task(self.process_playstatus(state, time_start, generation))

    # Processes the playback state change
    async def process_playstatus(self, state, time_start, generation=None):
        segments = []
        if state.videoId:
            segments = await self.api_helper.get_segments(state.videoId)
        if self.cancelled or (
            generation is not None and generation != self._playback_generation
        ):
            return
        if state.state.value == 1:  # Playing
            self.logger.info("Playing video %s with %d segments", state.videoId, len(segments))
            if segments:  # If there are segments
                await self.time_to_segment(segments, state.currentTime, time_start, generation)

    # Finds the next segment to skip to and skips to it
    async def time_to_segment(self, segments, position, time_start, generation=None):
        start_next_segment = None
        next_segment = None
        for segment in segments:
            segment_start = segment["start"]
            segment_end = segment["end"]
            is_within_start_range = (
                position < 1 < segment_end and segment_start <= position < segment_end
            )
            is_beyond_current_position = segment_start > position

            if is_within_start_range or is_beyond_current_position:
                next_segment = segment
                start_next_segment = position if is_within_start_range else segment_start
                break
        if start_next_segment is not None:
            playback_speed = self.lounge_controller.playback_speed
            if playback_speed <= 0:
                return  # Playback is not advancing; wait for a new playback event.
            time_to_next = (
                (start_next_segment - position - (time.monotonic() - time_start))
                / playback_speed
            ) - self.offset
            await self.skip(time_to_next, next_segment["end"], next_segment["UUID"], generation)

    # Skips to the next segment (waits for the time to pass)
    async def skip(self, time_to, position, uuids, generation=None):
        await asyncio.sleep(time_to)
        if self.cancelled or (
            generation is not None and generation != self._playback_generation
        ):
            return
        self.logger.info("Skipping segment: seeking to %s", position)
        await asyncio.gather(
            asyncio.create_task(self.lounge_controller.seek_to(position)),
            asyncio.create_task(self.api_helper.mark_viewed_segments(uuids)),
        )

    async def cancel(self):
        self.cancelled = True
        self._playback_generation += 1
        tasks = [
            task
            for task in (
                self.task,
                self.lounge_controller.subscribe_task_watchdog,
                self.lounge_controller.subscribe_task,
            )
            if task is not None
        ]
        for task in tasks:
            task.cancel()
        try:
            await self.lounge_controller.disconnect()
        finally:
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            stop_actions = getattr(self.lounge_controller, "stop_background_actions", None)
            if stop_actions is not None:
                await stop_actions()

    async def initialize_web_session(self):
        await self.lounge_controller.change_web_session(self.web_session)


async def finish(devices, web_session, tcp_connector):
    await asyncio.gather(*(device.cancel() for device in devices), return_exceptions=True)
    await web_session.close()
    await tcp_connector.close()


def handle_signal(signum, frame):
    raise KeyboardInterrupt()


async def main_async(config, debug, http_tracing):
    loop = asyncio.get_running_loop()
    tasks = []  # Save the tasks so the interpreter doesn't garbage collect them
    devices = []  # Save the devices to close them later
    if debug:
        loop.set_debug(True)

    tcp_connector = aiohttp.TCPConnector(ttl_dns_cache=300)

    # Configure session with tracing if enabled
    if http_tracing:
        root_logger = logging.getLogger("aiohttp_trace")
        tracer = AiohttpTracer(root_logger)
        trace_config = aiohttp.TraceConfig()
        trace_config.on_request_start.append(tracer.on_request_start)
        trace_config.on_response_chunk_received.append(tracer.on_response_chunk_received)
        trace_config.on_request_end.append(tracer.on_request_end)
        trace_config.on_request_exception.append(tracer.on_request_exception)
        web_session = aiohttp.ClientSession(
            trust_env=config.use_proxy, connector=tcp_connector, trace_configs=[trace_config]
        )
    else:
        web_session = aiohttp.ClientSession(trust_env=config.use_proxy, connector=tcp_connector)

    api_helper = api_helpers.ApiHelper(config, web_session)
    for i in config.devices:
        device = DeviceListener(api_helper, config, i, debug, web_session)
        devices.append(device)
        await device.initialize_web_session()
        tasks.append(loop.create_task(device.loop()))
        tasks.append(loop.create_task(device.refresh_auth_loop()))
    signal(SIGTERM, handle_signal)
    signal(SIGINT, handle_signal)
    try:
        await asyncio.gather(*tasks)
    except KeyboardInterrupt:
        print("Cancelling tasks and exiting...")
        await finish(devices, web_session, tcp_connector)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    finally:
        await web_session.close()
        await tcp_connector.close()
        print("Exited")


def main(config, debug, http_tracing):
    asyncio.run(main_async(config, debug, http_tracing))
