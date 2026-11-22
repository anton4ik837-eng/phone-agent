First public release. The app itself is an MCP server on the device; OpenCode runs in Termux and connects to `http://127.0.0.1:8765/mcp` — no PC required.

Features:

- `ui_dump` / `ui_tap` / `ui_swipe` / `ui_type` (Cyrillic supported) / `ui_key`
- `open_app`, `open_uri`, `sms_compose`, `notifications`, `clipboard_set`
- `battery`, `device_info`, `wait_ms`, `screenshot` (MediaProjection)
- auto-setup wizard on first launch, auto-start after reboot

Install the APK on POCO X7 Pro or Lenovo Xiaoxin Pad Pro GT, enable Accessibility + Notification access + screen capture when prompted, then in Termux install OpenCode and copy `phone-opencode.json` to `~/.config/opencode/opencode.json`. See README for full steps.

Note: debug-signed APK. Android does not allow granting Accessibility / Notification / MediaProjection permissions programmatically without root — the app opens the settings screens automatically, you only flip the toggles.
