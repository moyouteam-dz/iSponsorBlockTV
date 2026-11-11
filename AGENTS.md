# Agent guide: iSponsorBlockTV

This is a **Python 3.11+ service** with a Textual setup UI. As of the state-safety
audit, the repository tree contains no Android native application module. Do not
infer an Android APK build from the Docker / Python package workflows.

## Sources of truth

- \`src/iSponsorBlockTV/main.py\`: per-device lifecycle, playback processing,
  delayed segment skipping, cancellation and shutdown.
- \`src/iSponsorBlockTV/ytlounge.py\`: YouTube Lounge protocol adapter,
  monitored subscription, ad events and volume commands.
- \`src/iSponsorBlockTV/api_helpers.py\`: SponsorBlock lookups and device discovery.
- \`src/iSponsorBlockTV/conditional_ttl_cache.py\`: cached outcomes and TTL.
- \`src/iSponsorBlockTV/setup_wizard.py\`: setup UI and async pairing/discovery.
- \`src/iSponsorBlockTV/helpers.py\`: config load/validate/save and CLI.
- \`docs/implicit-state-machines.md\` in audit PR #1: state inventory and plan.

## Invariants

1. Cancellation always propagates; never catch \`asyncio.CancelledError\` using
   \`BaseException\` in normal control flow. Cancel, join and release owned tasks.
2. Stale pairing, search and discovery requests must never update current UI.
3. A transient SponsorBlock API failure must not become a permanent cache hit.
   \`AsyncConditionalTTL\` uses \`cache_policy=None\` for an uncached result;
   \`False\` means finite TTL; \`True\` means no expiry.
4. Only process skip commands for current playback; guard start=0, nonpositive
   playback speed, video changes and cancellation.
5. Keep disk config intact on write failure. Preserve existing config JSON shape.
6. Preserve multi-platform YouTube protocol behavior and default settings.
   Do not rewrite third-party \`pyytlounge\` internals without evidence.

## Validation

Use the CI Python 3.11 environment with \`requirements.txt\` pinned dependencies:

\`\`\`sh
python -m compileall -q src tests
PYTHONPATH=src python -m unittest discover -s tests -v
\`\`\`

The unit tests use fake HTTP responses, fake tasks and no live YouTube devices.
A green unit test result is **not** proof of a working Android APK, Docker
multi-architecture image or real TV playback. Check the existing Docker
workflow and manually test a real TV before release.

For behavior changes: add a failing regression test, make the smallest fix,
re-run the focused and full tests, review for compatibility, then open a draft
PR. Do not merge, push deployment tags, or deploy without explicit authorization
and verification.
