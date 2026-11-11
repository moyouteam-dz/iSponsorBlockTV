# Android Architecture and verification notes

## Data flow

1. User supplies a YouTube TV pairing code (not a Google account/password).
2. PairingRepository calls YouTube `api/lounge/pairing/get_screen`, saves only `screenId` and display name.
3. User presses Start. `MonitorService` starts immediately in foreground (`connectedDevice`) and delegates to the injected singleton `MonitorController`.
4. Controller refreshes `loungeToken`, checks availability, POSTs to `bc/bind` for SID+gsession, then subscribes to the long-poll byte stream.
5. `LoungeFrameParser` decodes length-prefixed JSON frames, `LoungeClient` dispatches `nowPlaying`, `onStateChange`, ads, volume and speed.
6. `SponsorBlockRepository` GETs SHA256-prefix lookup, merges intervals, caches for 5 minutes and optionally posts viewed UUID reports.
7. Scheduler uses `SystemClock.elapsedRealtime()` and seek commands, guards against completed segments, cancels when playback state/video changes.
8. Stop cancels scheduling, attempts to unmute before disconnect and closes the stream.

## Boundary considerations

- No local web server or untrusted LAN discovery: the first iteration deliberately uses only manual pairing.
- `HttpTransport` rejects non-HTTPS URLs and caps response/frame size at 4 MiB; a frame parser handles truncated/malformed stream data.
- Commands are serialized with a coroutine mutex because Lounge's command offset is sequence-sensitive.
- Each network request is bounded by a timeout; subscriptions reconnect after stalls.
- `LoungeClient` is owned by the app-scope `MonitorController`; the `ViewModel` owns only UI and pairing intents, not streaming jobs.
- Automatic restart at boot is not enabled. There are no sticky background starts.
- Native Android UI and lifecycle state are separated from core algorithm; no overlay or screen scraping of YouTube.

## Verification status

- [x] Manually inspected upstream iSponsorBlockTV and pyytlounge protocol and GPLv3.
- [x] Created Android Gradle / Compose / Hilt project.
- [x] `tools/test-core.sh`: local Kotlin compiler: PASS.
- [x] AndroidManifest and resource XML parse.
- [ ] `gradle testDebugUnitTest assembleDebug`: requires Android SDK and Gradle (not installed in authoring container).
- [ ] Android emulator launch, notification behavior, RTL, remote focus.
- [ ] Real YouTube TV pairing and SponsorBlock seek on Android TV / Fire TV / LG / Samsung.
