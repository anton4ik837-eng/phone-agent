package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("phone_agent", MODE_PRIVATE)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 16f; setPadding(24, 24, 24, 12) }
        log = TextView(this).apply { textSize = 12f; setPadding(24, 12, 24, 24) }

        root.addView(status)
        root.addView(button("Включить доступность (обязательно)") { markAndOpen("asked_acc") { openAccessibilitySettings() } })
        root.addView(button("Разрешить чтение уведомлений") { markAndOpen("asked_notif") { openNotificationSettings() } })
        root.addView(button("Разрешить скриншоты") { markAndOpen("asked_capture") { requestScreenCapture() } })
        root.addView(button("Запустить MCP-сервер") {
            McpServer.start(this)
            refresh()
            toast("MCP-сервер: http://127.0.0.1:8765/mcp")
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
        root.addView(button("Проверить инструменты") {
            val count = McpServer.toolNames().size
            appendLog("Инструментов: $count")
            toast("Инструментов: $count")
        })
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

    private fun button(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { click() }
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

    /** Opens the required system settings one by one. Android does not allow granting
     *  accessibility / notification / MediaProjection access programmatically without root,
     *  so the user only has to flip the toggles — the app advances by itself. */
    private fun autoAdvance() {
        if (!prefs.getBoolean("setup_active", true)) return
        when {
            !accessibilityEnabled() && !prefs.getBoolean("asked_acc", false) -> {
                prefs.edit().putBoolean("asked_acc", true).apply()
                appendLog("Автонастройка: шаг 1/3 — доступность")
                openAccessibilitySettings()
            }
            !accessibilityEnabled() -> Unit // user has seen the screen, waiting for the toggle
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
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        if (manager == null) {
            toast("MediaProjection недоступен")
            return
        }
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
            appendLine("Termux → opencode → mcp.servers.phone → http://127.0.0.1:8765/mcp")
        }
    }

    private fun appendLog(line: String) {
        log.append("[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] $line\n")
        val sc = log.parent?.parent as? ScrollView
        sc?.post { sc.fullScroll(Gravity.BOTTOM) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
