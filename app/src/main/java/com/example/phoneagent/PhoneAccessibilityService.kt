package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PhoneAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        McpServer.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Events are not used: tools read the UI tree on demand.
    }

    override fun onInterrupt() {
        // No long-running operations to interrupt.
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun <T> main(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val box = arrayOf<Any?>(null)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                box[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(6, TimeUnit.SECONDS)) throw RuntimeException("Таймаут главного потока")
        val value = box[0]
        if (value is Throwable) throw value
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun tap(x: Int, y: Int): Boolean = main {
        if (x < 0 || y < 0) throw IllegalArgumentException("Координаты должны быть неотрицательными")
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 0.1f, y + 0.1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = main {
        val duration = durationMs.coerceIn(50L, 5000L)
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun typeText(text: String): String = main {
        val root = rootInActiveWindow ?: return@main "Нет активного окна"
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) target = findEditable(root)
        if (target == null) return@main "Не найдено поле ввода. Сначала сделайте tap по полю через ui_tap."
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) "Текст введён ($text)" else "Не удалось ввести текст"
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable || node.className?.contains("EditText") == true) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun dump(maxNodes: Int = 350): String = main {
        val root = rootInActiveWindow ?: return@main "Нет активного окна"
        val sb = StringBuilder()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes || depth > 14) return
            count++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.replace('\n', ' ') ?: ""
            val desc = node.contentDescription?.toString()?.replace('\n', ' ') ?: ""
            val id = node.viewIdResourceName ?: ""
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isFocusable) {
                sb.append("  ".repeat(depth))
                sb.append(node.className ?: "View").append(' ')
                if (id.isNotBlank()) sb.append("id=$id ")
                if (text.isNotBlank()) sb.append("text=\"").append(text.take(140)).append("\" ")
                if (desc.isNotBlank()) sb.append("desc=\"").append(desc.take(140)).append("\" ")
                sb.append('[').append(bounds.left).append(',').append(bounds.top)
                    .append(',').append(bounds.right).append(',').append(bounds.bottom).append(']')
                if (node.isClickable) sb.append(" clickable")
                if (node.isEditable) sb.append(" editable")
                if (node.isFocused) sb.append(" focused")
                sb.append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        "Узлов: $count\n$sb"
    }

    fun global(name: String): String = main {
        val action = when (name.trim().uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS", "APP_SWITCH", "RECENT" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "SHADE" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "POWER", "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
            "LOCKSCREEN", "LOCK" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> -1
        }
        if (action < 0) "Неизвестное действие: $name. Доступно: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER, LOCKSCREEN"
        else "ok=${performGlobalAction(action)}"
    }

    fun openApp(packageName: String): String = main {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@main "Приложение не найдено: $packageName"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        "Запущено: $packageName"
    }

    fun openUri(uri: String): String = main {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        "Открыто: $uri"
    }

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
    }
}
