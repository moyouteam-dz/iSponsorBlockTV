# iSponsorBlockTV Android — Mobile YouTube beta 0.3.0

The application has two separate modes:

* **YouTube on this phone**: browse YouTube mobile website in embedded Android WebView and seek over SponsorBlock segments in the embedded HTML5 player; tries to press Skip Ad when the site makes it available.
* **YouTube TV**: preserves the existing device pairing and remote Lounge monitoring.

The app does **not** modify or control the installed official YouTube Android app. It does **not** guarantee removing native ads; WebView login, playback, fullscreen, and page structure can vary by device and YouTube updates.

### Mobile acceptance checks
1. Install the separately named Mobile Beta debug APK; open mobile mode.
2. Browse/search, start a normal video in the embedded page; verify normal playback and controls.
3. Play a video with known SponsorBlock segments and verify that the timeline advances without repeated skips.
4. Switch rapidly to another video; verify stale SponsorBlock lookups never cause a seek on the new video.
5. Pause, resume, use Back and switch to TV pairing mode. Existing TV mode remains available.
6. Try a skippable YouTube advertisement; verify app only attempts the available Skip Ad control.
7. On failure record phone model, OS, Android System WebView/Chrome version, YouTube behavior and sanitized logs.

No native playback/device test has yet been completed. GitHub Android CI tests and builds the code but cannot validate mobile YouTube site behavior.
