# TapMemory for Android
Android auto-clicker using AccessibilityService. Records accessible UI clicks and scroll events, replays gestures, inserts clipboard text saved by the user, persists macro locally, and optionally attempts a mobile-network reconnect using the airplane mode quick setting before **each loop**. Can also send an HTTPS request to an IP-rotation API.
## Install
Download TapMemory.apk from this build branch and allow installation. Open app, enable the TapMemory service in Settings > Accessibility. On Android 13+ you may need to allow restricted settings under App info.
## Usage
1. Press Record. Perform actions in other apps. Stop with the floating panel.
2. To add text: copy it, return to TapMemory, tap Add paste step (captures current clipboard). Tap the input field before that step.
3. Set repetitions (0 unlimited) and optional IP change mode; tap Play. Floating Stop ends recording/playback.
## Limitations and privacy
No telemetry. All macro data stored locally, except optional HTTPS rotation command to user-configured URL. Accessibility clicks may miss web, game, canvas and non-accessible widgets; click recording uses UI element centers rather than physical touch positions. Pasted text may contain secrets; do not save passwords. Quick-settings automation may be unavailable on some OEMs or languages; airplane mode can disrupt connectivity and **does not guarantee a new public IP**. Works only on cellular when Wi-Fi is off. An HTTPS rotation API changes the device's public IP only if the configured VPN/proxy actually routes device traffic through it.