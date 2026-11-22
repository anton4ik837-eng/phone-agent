package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var chat: TextView
    private lateinit var commandInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var prefs: SharedPreferences
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("phone_agent", MODE_PRIVATE)
        sessionId = prefs.getString("oc_session", null)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 16f; setPadding(24, 24, 24, 12) }
        log = TextView(this).apply { textSize = 12f; setPadding(24, 12, 24, 12) }

        root.addView(status)
        root.addView(button("Включить доступность (обязательно)") { markAndOpen("asked_acc") { openAccessibilitySettings() } })
        root.addView(button("Разрешить чтение уведомлений") { markAndOpen("asked_notif") { openNotificationSettings() } })
        root.addView(button("Разрешить скриншоты") { markAndOpen("asked_capture") { requestScreenCapture() } })
        root.addView(button("Запустить MCP-сервер") {
            McpServer.start(this)
            refresh()
            toast("MCP: http://127.0.0.1:8765/mcp")
        })
        root.addView(button("Повторить автонастройку") {
            prefs.edit()
                .putBoolean("setup_active", true)
                .putBoolean("asked_acc", false)
                .putBoolean("asked_notif", false)
                .putBoolean("asked_capture", false)
                .apply()
            autoAdvance()
        })

        root.addView(section("ЧАТ С OPENCODE"))
        serverInput = EditText(this).apply {
            hint = "Адрес OpenCode (по умолчанию http://127.0.0.1:4096)"
            setText(prefs.getString("oc_url", "http://127.0.0.1:4096"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        passwordInput = EditText(this).apply {
            hint = "Пароль от opencode serve (если есть)"
            setText(prefs.getString("oc_password", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(serverInput)
        root.addView(passwordInput)
        root.addView(button("Подключиться / новая сессия") { connect() })
        root.addView(button("Все нужные команды ДЛЯ TERMUX") { appendChat(COMMANDS) })
        root.addView(button("Копировать команды для Termux") { copySetupCommands() })
        root.addView(button("Копировать: repo-setup") { copyText(CMD_REPO) })
        root.addView(button("Копировать: pkg install") { copyText(CMD_INSTALL) })
        root.addView(button("Копировать: конфиг") { copyText(CMD_MKDIR + "\n" + CMD_CONFIG) })
        root.addView(button("Копировать: serve") { copyText(CMD_SERVE) })
        root.addView(button("Открыть Termux") { openTermux() })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(button("Экран") { sendCommand("Посмотри, что сейчас на экране, и опиши") })
        quick.addView(button("Уведомления") { sendCommand("Прочитай активные уведомления") })
        quick.addView(button("Telegram") { sendCommand("Открой Telegram и скажи, что видишь") })
        root.addView(quick)

        commandInput = EditText(this).apply {
            hint = "Напиши команду для OpenCode..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(commandInput)
        root.addView(button("ОТПРАВИТЬ") { sendCommand(commandInput.text.toString()) })
        chat = TextView(this).apply { textSize = 13f; setPadding(24, 12, 24, 24) }
        root.addView(chat)
        root.addView(button("Очистить чат") { chat.text = "" })
        root.addView(log)
        root.addView(button("Очистить лог") { log.text = "" })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        restoreCapturePermission()
        McpServer.start(this)
        if (intent.getBooleanExtra("from_boot", false)) appendLog("Запуск после перезагрузки")
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = "—— $text ——"
        textSize = 15f
        setPadding(24, 20, 24, 4)
    }

    private fun button(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun client(): OpenCodeClient {
        val url = serverInput.text.toString().ifBlank { "http://127.0.0.1:4096" }
        val password = passwordInput.text.toString()
        prefs.edit().putString("oc_url", url).putString("oc_password", password).apply()
        return OpenCodeClient(url, password)
    }

    private fun connect() {
        appendChat("Подключение к OpenCode...")
        Thread {
            try {
                val result = client().check()
                val sid = client().createSession()
                sessionId = sid
                prefs.edit().putString("oc_session", sid).apply()
                runOnUiThread {
                    appendChat("$result. Новая сессия создана. Пиши команду ниже.")
                    toast("OpenCode подключён")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    appendChat(
                        "Не удалось подключиться: ${t.message}\n" +
                        "Открой Termux (кнопка выше) и выполни: opencode serve\n" +
                        "Затем впиши напечатанные адрес и пароль в поля и нажми «Подключиться» ещё раз."
                    )
                }
            }
        }.start()
    }

    private fun copyText(text: String) {
        val manager = getSystemService(android.content.ClipboardManager::class.java)
        manager?.setPrimaryClip(android.content.ClipData.newPlainText("Phone Agent", text))
        toast("Скопировано — вставь в Termux")
    }

    private fun copySetupCommands() {
        copyText(listOf(CMD_REPO, CMD_INSTALL, CMD_MKDIR, CMD_CONFIG, CMD_MCP, CMD_SERVE)
            .joinToString("\n"))
    }

    private fun openTermux() {
        val installed = packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .filter { it.contains("termux", ignoreCase = true) }
        val candidates = (listOf("com.termux", "com.termux.x11") + installed).distinct()
        for (pkg in candidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // try the next candidate
            }
        }
        toast("Не нашёл Termux. Установи его из F-Droid или GitHub releases")
    }

    private fun sendCommand(text: String) {
        if (text.isBlank()) return
        if (Regex("""^\s*(curl|pkg|mkdir|opencode|cd|ls|bash|apt)\b""").containsMatchIn(text)) {
            appendChat("Это команда для Termux, а не для агента. Нажми «Открыть Termux» и выполни её там (или «Копировать команды для Termux»).")
            return
        }
        appendChat("Ты: $text")
        commandInput.setText("")
        Thread {
            try {
                val currentClient = client()
                val sid = sessionId ?: currentClient.createSession().also { newId ->
                    sessionId = newId
                    prefs.edit().putString("oc_session", newId).apply()
                }
                val answer = currentClient.prompt(sid, text)
                runOnUiThread { appendChat("OpenCode: $answer") }
            } catch (t: Throwable) {
                runOnUiThread { appendChat("Ошибка: ${t.message}") }
            }
        }.start()
    }

    private fun appendChat(line: String) {
        chat.append(line + "\n\n")
        val scroll = chat.parent?.parent as? ScrollView
        scroll?.post { scroll.fullScroll(Gravity.BOTTOM) }
    }

    private fun markAndOpen(key: String, action: () -> Unit) {
        prefs.edit().putBoolean(key, true).apply()
        action()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        autoAdvance()
    }

    /** The app cannot grant system-protected permissions without root, but it opens
     *  the required screens one by one and advances as soon as each is granted. */
    private fun autoAdvance() {
        if (!prefs.getBoolean("setup_active", true)) return
        when {
            !accessibilityEnabled() && !prefs.getBoolean("asked_acc", false) -> {
                prefs.edit().putBoolean("asked_acc", true).apply()
                appendLog("Автонастройка: шаг 1/3 — доступность")
                openAccessibilitySettings()
            }
            !accessibilityEnabled() -> Unit
            !notificationsEnabled() && !prefs.getBoolean("asked_notif", false) -> {
                prefs.edit().putBoolean("asked_notif", true).apply()
                appendLog("Автонастройка: шаг 2/3 — уведомления")
                openNotificationSettings()
            }
            !notificationsEnabled() -> Unit
            ScreenCaptureHolder.permissionIntent == null && !prefs.getBoolean("asked_capture", false) -> {
                prefs.edit().putBoolean("asked_capture", true).apply()
                appendLog("Автонастройка: шаг 3/3 — скриншоты")
                requestScreenCapture()
            }
            accessibilityEnabled() && notificationsEnabled() && ScreenCaptureHolder.permissionIntent != null -> {
                prefs.edit().putBoolean("setup_active", false).apply()
                appendLog("Автонастройка завершена. Phone Agent готов к работе.")
                toast("Phone Agent готов")
            }
        }
        refresh()
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains("$packageName/${PhoneAccessibilityService::class.java.name}")
    }

    private fun notificationsEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains("$packageName/${NotificationBridge::class.java.name}")
    }

    private fun openAccessibilitySettings() {
        val key = "$packageName/${PhoneAccessibilityService::class.java.name}"
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", key)
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        }
        try { startActivity(intent) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openNotificationSettings() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        catch (_: Throwable) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        if (manager == null) { toast("MediaProjection недоступен"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), 42)
    }

    @Deprecated("Required for startActivityForResult compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        if (resultCode == RESULT_OK && data != null) {
            ScreenCaptureHolder.permissionIntent = data
            prefs.edit().putString("capture_permission", data.toUri(0)).apply()
            CaptureService.start(this)
            appendLog("Скриншоты разрешены")
        } else {
            appendLog("Скриншоты не разрешены — можно повторить кнопкой")
        }
        refresh()
    }

    private fun restoreCapturePermission() {
        val saved = prefs.getString("capture_permission", null) ?: return
        try {
            ScreenCaptureHolder.permissionIntent = Intent.parseUri(saved, 0)
            CaptureService.start(this)
        } catch (_: Throwable) {
            prefs.edit().remove("capture_permission").apply()
        }
    }

    private fun refresh() {
        status.text = buildString {
            appendLine("Phone Agent — MCP для телефона")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            appendLine()
            appendLine("Доступность: ${if (accessibilityEnabled()) "ВКЛЮЧЕНО" else "НЕТ"}")
            appendLine("Уведомления: ${if (notificationsEnabled()) "ВКЛЮЧЕНО" else "НЕТ"}")
            appendLine("Скриншоты: ${if (ScreenCaptureHolder.permissionIntent != null) "РАЗРЕШЕНЫ" else "НЕТ"}")
            appendLine("MCP: http://127.0.0.1:8765/mcp (${if (McpServer.isRunning()) "работает" else "остановлен"})")
            appendLine()
            appendLine("Команды агента — в поле ниже. Termux нужен только для `opencode serve`.")
        }
    }

    private fun appendLog(line: String) {
        log.append("[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] $line\n")
        val scroll = log.parent?.parent as? ScrollView
        scroll?.post { scroll.fullScroll(Gravity.BOTTOM) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val CMD_REPO = "curl -fsSL https://raw.githubusercontent.com/bd-loser/opencode-bionic/main/repo-setup.sh | bash"
        private const val CMD_INSTALL = "pkg install opencode"
        private const val CMD_MKDIR = "mkdir -p ~/.config/opencode"
        private const val CMD_CONFIG = "curl -fsSL https://raw.githubusercontent.com/anton4ik837-eng/phone-agent/main/phone-opencode.json -o ~/.config/opencode/opencode.json"
        private const val CMD_MCP = "opencode mcp list"
        private const val CMD_SERVE = "opencode serve"

        private val SETUP_COMMANDS = """1. Установить Termux из F-Droid или GitHub releases (не из Play Store).
2. $CMD_REPO
3. $CMD_INSTALL
4. $CMD_MKDIR
5. $CMD_CONFIG
6. $CMD_MCP   -> должно быть: phone connected
7. $CMD_SERVE      -> напечатает адрес и пароль, впиши их в поля выше

Вставляй в Termux ПО ОДНОЙ СТРОКЕ. Кнопки выше копируют каждую команду отдельно — так ссылка не разорвётся."""

        private val COMMANDS = """ВНИМАНИЕ: ЭТИ КОМАНДЫ ВВОДЯТСЯ В TERMUX, НЕ В ЧАТ!

$SETUP_COMMANDS

————————————————————

Примеры команд ДЛЯ ЧАТА (после подключения):
- Посмотри, что сейчас на экране, и опиши.
- Прочитай активные уведомления.
- Открой Telegram и скажи, что видишь.
- Сделай скриншот и сохрани его.
- Открой Gmail и найди письмо от ...
- Набери номер ... в SMS и подготовь сообщение.

Инструменты агента: ui_dump, ui_tap, ui_swipe, ui_type, ui_key, open_app,
open_uri, sms_compose, notifications, battery, device_info, clipboard_set,
wait_ms, screenshot."""
    }
}
