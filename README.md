# Phone Agent — управление телефоном без ПК

Android-приложение, которое само является MCP-сервером. OpenCode запускается прямо на устройстве (Termux) и подключается к приложению по `http://127.0.0.1:8765/mcp`. ПК в контуре отсутствует.

Готовый APK: `C:\Users\Anton\Dev\PhoneAgent\phone-agent.apk` (копия `app\build\outputs\apk\debug\app-debug.apk`). Один и тот же APK ставится на POCO X7 Pro и на Lenovo Xiaoxin Pad Pro GT.

## Что умеет

| Инструмент | Назначение |
|---|---|
| `device_info` | Модель, Android, разрешение экрана |
| `battery` | Заряд, состояние, температура |
| `ui_dump` | Дерево элементов экрана: текст, описания, координаты, clickable/editable |
| `ui_tap` | Тап по координатам |
| `ui_swipe` | Свайп |
| `ui_type` | Ввод текста (кириллица работает) в сфокусированное поле |
| `ui_key` | BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER, LOCKSCREEN |
| `open_app` | Запуск приложения по пакету или имени (`telegram`, `gmail`, `chrome`, …) |
| `open_uri` | `tg://`, `mailto:`, `https://`, `tel:`, `sms:` |
| `sms_compose` | Открыть редактор SMS с текстом (не отправляет) |
| `notifications` | Прочитать активные уведомления |
| `clipboard_set` | Положить текст в буфер обмена |
| `wait_ms` | Пауза после открытия приложения |
| `screenshot` | Скриншот через MediaProjection |

Агент работает по циклу: `ui_dump` → понять координаты → `ui_tap`/`ui_type` → `ui_dump` для проверки.

## Установка на устройство

1. Скопируй `phone-agent.apk` на телефон/планшет и установи (разреши установку неизвестных приложений).
2. Открой **Phone Agent** — автонастройка запустится сама: приложение по очереди откроет настройки доступности, доступа к уведомлениям и диалог захвата экрана. Тебе остаётся только переключить тумблеры и подтвердить диалоги; после каждого шага приложение само переходит к следующему. Кнопка «Повторить автонастройку» запускает сценарий заново.
   - Без root Android **не разрешает** программно включить доступность, доступ к уведомлениям и MediaProjection — это системная защита. Поэтому «само всё» ограничено автоматическим переходом по экранам: по одному тапу на каждом экране всё равно нужно.
3. После перезагрузки сервер поднимается сам (`BootReceiver`), разрешение на скриншоты восстанавливается из сохранённого Intent.
4. На HyperOS и Lenovo дополнительно разреши приложению **автозапуск** и отключи оптимизацию батареи, иначе систему может убить фоновый процесс.

Проверка: в приложении должно быть `MCP: http://127.0.0.1:8765/mcp (работает)`.

## Установка OpenCode на устройстве

В Termux (Install from F-Droid or GitHub releases):

```sh
curl -fsSL https://raw.githubusercontent.com/bd-loser/opencode-bionic/main/repo-setup.sh | bash
pkg install opencode
```

Это нативная сборка OpenCode 2.x для Android (aarch64, без root/proot). Работает на Android 10+.

## Настройка OpenCode

Создай `$HOME/.config/opencode/opencode.json` в Termux. Готовый файл лежит в `phone-opencode.json` этого проекта — можно просто скопировать:

```sh
mkdir -p ~/.config/opencode
cp /sdcard/Download/phone-opencode.json ~/.config/opencode/opencode.json
opencode mcp list
```

Должно показать `phone connected`. Проверить вживую:

```sh
opencode serve
```

Открой напечатанный адрес `http://127.0.0.1:<port>` в браузере устройства — встроенный Web UI OpenCode. Попроси, например: «Покажи, что на экране, и открой Telegram». Агент вызовет `ui_dump`, затем `open_app`.

Можно также поставить приложение OpenCode Mobile и подключить его к `http://127.0.0.1:<port>`.

## Важно про модель

Конфиг использует существующий Cloudflare Worker как прокси к Gemini (`google/gemini-3.5-flash-lite`). На телефоне WARP не нужен — он нужен только для исходящего трафика Worker'а на ПК. Если хочешь другую модель, меняй `model` в конфиге.

## Пересборка

```powershell
& C:\Users\Anton\Dev\gradle\bin\gradle.bat -p C:\Users\Anton\Dev\PhoneAgent assembleDebug
Copy-Item C:\Users\Anton\Dev\PhoneAgent\app\build\outputs\apk\debug\app-debug.apk C:\Users\Anton\Dev\PhoneAgent\phone-agent.apk -Force
```

Стек: AGP 9.4 (встроенная Kotlin-поддержка), Gradle 9.9.1, JDK 21, compileSdk 36, minSdk 26.

## Ограничения

- Без root нельзя менять системные настройки напрямую и читать чужие приватные данные — только то, что видно через Accessibility и уведомления.
- HyperOS может требовать повторного включения доступности после перезагрузки/обновления приложения.
- `sms_compose` и `open_uri` открывают приложение с заполненными данными; финальное нажатие «Отправить» делает пользователь или агент через `ui_tap`.
- Прямая отправка через Telegram Bot API / Gmail API — следующий этап (нужны токены; хранить их предлагается на Cloudflare Worker, а не в приложении).
