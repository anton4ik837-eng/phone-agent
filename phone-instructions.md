На этом Android-устройстве для просмотра и управления экраном ВСЕГДА используй инструменты MCP-сервера `phone` (в Code Mode это `tools.phone.ui_dump`, `ui_tap`, `ui_swipe`, `ui_type`, `ui_key`, `screenshot`, `notifications`, `open_app`, `open_uri`).

Никогда не пытайся сделать это через shell: команды `screencap`, `input tap`, `termux-screenshot` здесь не работают без root и всегда возвращают ошибку.

Правило: после открытия приложения или любого действия на экране сначала вызови `ui_dump`, чтобы увидеть актуальные координаты и текст, затем `ui_tap` / `ui_type`, и снова `ui_dump` для проверки результата.

Если инструменты `tools.phone.*` недоступны, скажи пользователю: «Запусти в Termux `opencode mcp list` — сервер phone должен быть connected».
