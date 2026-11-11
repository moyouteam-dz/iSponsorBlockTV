# Real TV release smoke test — iSponsorBlockTV

Purpose: verify the **Python service** against a real YouTube-for-TV session. CI/fake-network tests do not constitute a hardware pass. This checklist does not claim that Android APK exists in this repository.

## Preconditions

- TV with official YouTube application, a second device/host running the Python service, both able to reach required YouTube / SponsorBlock services
- Use a separate test configuration/data directory; preserve production config (including `config.json`) and pairing codes
- Record device model, OS/firmware, YouTube app version, Python service commit SHA, date/time and network environment
- Enable debug logging only during testing; never paste pairing tokens or private credentials into a public issue

## Test matrix

| ID | Scenario | Expected result | Evidence |
| --- | --- | --- | --- |
| TV-01 | Open setup and pair using the YouTube TV code | Exactly one device is added, name is correct | Screen capture/log with code redacted |
| TV-02 | Enter malformed code, then a valid code, then rapidly replace it | Old response cannot validate or add a different code | UI status and sanitized log |
| TV-03 | Local discovery with no devices, then retry | Search finishes or can be restarted; no stuck button | UI status |
| TV-04 | Start service with paired TV powered off, then turn it on and open YouTube | Recovers automatically and connects without restarting the service | Timestamped connection log |
| TV-05 | Normal video with unlocked SponsorBlock segments | Seeks only within the current video and reports viewed segments according to configured policy | Sanitized segment/seek log |
| TV-06 | Switch video A → B during a pending segment fetch | No delayed seek or segment report for A after B begins | Event and seek timestamps |
| TV-07 | Pause/resume, seek to start, playback speed change | No divide-by-zero, unexpected seek, or repeated loops | Event log |
| TV-08 | Native YouTube ad with configured mute/skip | Mutes only during ad when enabled, skips only skippable ads when enabled, unmute follows current ad state | Video/audio observation |
| TV-09 | Change volume manually while ads are playing | Correct final volume/mute state with no unintended persistent mute | Video/audio observation |
| TV-10 | Disable SponsorBlock network temporarily, then restore | Service remains alive; later lookups succeed; no permanent negative cache | Sanitized network/service log |
| TV-11 | Exit during discovery, pairing, playback, and reconnect | No orphan process/task; clean shutdown; existing config intact | Process/log inspection |
| TV-12 | Restart, review existing configuration | Devices, whitelist and settings survive unchanged | Redacted config comparison |

## Acceptance and rollback

- Mark each scenario **PASS / FAIL / NOT TESTED**. Any untested scenario remains unverified.
- If the TV has firmware-specific differences, record separately. Do not generalize from one device.
- Compare new branch against an unchanged known-working release before switching production traffic.
- On regression, stop the test service, restore the previous known-good deployment and retain sanitized logs.
- Merge only after CI, review, and TV evidence are recorded. A Docker image build does not imply successful playback on TV.

## Automated tests

```sh
python -m compileall -q src tests
PYTHONPATH=src python -m unittest discover -s tests -v
```

Run after installing the pinned dependencies in `requirements.txt`. Both commands are exercised in the PR's Python workflow.
