v0.2.0 — команды вводятся прямо в приложении, Termux больше не нужен для каждого запроса.

What's new:

- In-app chat: enter the `opencode serve` URL and password, press «Подключиться / новая сессия», then type commands and read the agent's reply in the app.
- Quick command buttons: screen, notifications, Telegram.
- «Все нужные команды и примеры» button prints the full Termux setup command list and example prompts inside the app.
- Explicit cleartext HTTP permission so the app can talk to the local OpenCode server.
- Auto-setup wizard and boot-time restart carried over from v0.1.0.

How to use:

1. Install APK, let the auto-setup open Accessibility / Notifications / Screenshot screens and flip the toggles.
2. In Termux run `opencode serve` once; enter its URL and password in the app.
3. Press connect, then type a command. The app talks to OpenCode over HTTP and OpenCode calls this app's MCP tools on `127.0.0.1:8765/mcp`.

Note: debug-signed APK. Android does not allow granting Accessibility / Notification / MediaProjection permissions programmatically without root — the app opens the settings screens automatically, you only flip the toggles.
