package com.example.phoneagent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ToolExecutor(private val context: Context) {

    data class Result(val text: String, val isError: Boolean = false)

    private fun ok(text: String) = Result(text)
    private fun fail(text: String) = Result(text, true)

    fun execute(name: String, args: JSONObject): Result {
        return try {
        when (name) {
            "device_info" -> deviceInfo()
            "battery" -> battery()
            "ui_dump" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён. Включите его в приложении Phone Agent.")
                ok(service.dump(args.optInt("maxNodes", 350)))
            }
            "ui_tap" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)
                if (x < 0 || y < 0) return fail("Укажите x и y")
                ok("tap($x,$y) → ${service.tap(x, y)}")
            }
            "ui_swipe" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val x1 = args.optInt("x1", -1); val y1 = args.optInt("y1", -1)
                val x2 = args.optInt("x2", -1); val y2 = args.optInt("y2", -1)
                if (minOf(x1, y1, x2, y2) < 0) return fail("Укажите x1, y1, x2, y2")
                val duration = args.optLong("durationMs", 300)
                ok("swipe($x1,$y1 → $x2,$y2) → ${service.swipe(x1, y1, x2, y2, duration)}")
            }
            "ui_type" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val text = args.optString("text", "")
                if (text.isEmpty()) return fail("Укажите text")
                ok(service.typeText(text))
            }
            "ui_key" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val key = args.optString("key", "")
                if (key.isEmpty()) return fail("Укажите key")
                ok(service.global(key))
            }
            "open_app" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val app = args.optString("app", "")
                if (app.isEmpty()) return fail("Укажите app")
                ok(service.openApp(resolveApp(app)))
            }
            "open_uri" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val uri = args.optString("uri", "")
                if (uri.isEmpty()) return fail("Укажите uri")
                ok(service.openUri(uri))
            }
            "sms_compose" -> {
                val service = PhoneAccessibilityService.instance
                    ?: return fail("Accessibility Service не включён")
                val number = args.optString("number", "")
                val text = args.optString("text", "")
                val uri = Uri.parse("sms:${number}")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                ok("Открыт редактор SMS для $number (сообщение НЕ отправлено)")
            }
            "notifications" -> {
                val bridge = NotificationBridge.instance
                    ?: return fail("Доступ к уведомлениям не разрешён. Включите его в приложении Phone Agent.")
                ok(bridge.active(args.optInt("limit", 50)))
            }
            "clipboard_set" -> {
                val text = args.optString("text", "")
                val manager = android.content.ClipboardManager::class.java.let {
                    context.getSystemService(it)
                }
                manager?.setPrimaryClip(android.content.ClipData.newPlainText("phone-agent", text))
                ok("Буфер обмена установлен")
            }
            "wait_ms" -> {
                val ms = args.optLong("ms", 1000).coerceIn(0, 10000)
                Thread.sleep(ms)
                ok("Ожидание ${ms} мс завершено")
            }
            "screenshot" -> screenshot(args.optString("path", ""))
            else -> fail("Неизвестный инструмент: $name")
        }
        } catch (t: Throwable) {
            fail("Ошибка ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun deviceInfo(): Result {
        val metrics = context.resources.displayMetrics
        return ok(buildString {
            appendLine("Производитель: ${Build.MANUFACTURER}")
            appendLine("Модель: ${Build.MODEL}")
            appendLine("Устройство: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Экран: ${metrics.widthPixels}x${metrics.heightPixels}, плотность ${metrics.density}DPI ${metrics.densityDpi}")
        }.trimEnd())
    }

    private fun battery(): Result {
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)
            ?: return fail("Не удалось получить состояние батареи")
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", 100)
        val plugged = intent.getIntExtra("plugged", 0)
        val status = intent.getIntExtra("status", 0)
        val temp = intent.getIntExtra("temperature", 0) / 10f
        return ok(buildString {
            appendLine("Заряд: ${if (level >= 0 && scale > 0) (level * 100 / scale) else "?"}%")
            appendLine("Статус: $status (2=заряжается)")
            appendLine("Подключено: $plugged (1=USB, 2=AC)")
            appendLine("Температура: ${temp}°C")
        }.trimEnd())
    }

    private fun screenshot(requestedPath: String): Result {
        val permission = ScreenCaptureHolder.permissionIntent
            ?: return fail("Скриншот не разрешён. Нажмите «3. Разрешить скриншоты» в приложении.")
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(android.app.Activity.RESULT_OK, permission)
                ?: return fail("Не удалось создать MediaProjection")
        } catch (t: Throwable) {
            return fail("Не удалось создать MediaProjection: ${t.message}")
        }

        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return fail("Не удалось определить размер экрана")
        val density = context.resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        val bitmapBox = arrayOfNulls<Bitmap>(1)

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val buffer: ByteBuffer = plane.buffer
                val bytes = ByteArray(rowStride * height)
                buffer.get(bytes)
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = y * rowStride + x * pixelStride
                        val a = bytes[i + 3].toInt() and 0xff
                        val rr = bytes[i + 2].toInt() and 0xff
                        val g = bytes[i + 1].toInt() and 0xff
                        val b = bytes[i].toInt() and 0xff
                        pixels[y * width + x] = (a shl 24) or (rr shl 16) or (g shl 8) or b
                    }
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                bitmapBox[0] = bitmap
            } catch (t: Throwable) {
                android.util.Log.e("PhoneAgent", "Screenshot decode error", t)
            } finally {
                image.close()
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        val display = projection.createVirtualDisplay(
            "phone-agent-screenshot", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )

        val captured = latch.await(6, TimeUnit.SECONDS)
        display?.release()
        reader.close()
        try { projection.stop() } catch (_: Throwable) {}

        val bitmap = bitmapBox[0] ?: return fail("Не удалось получить изображение с экрана")
        val file = if (requestedPath.isNotBlank()) File(requestedPath)
                   else File(context.cacheDir, "screen-${System.currentTimeMillis()}.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ok("Скриншот: ${file.absolutePath} (${file.length()} байт)")
    }

    private fun resolveApp(name: String): String {
        val map = mapOf(
            "telegram" to "org.telegram.messenger",
            "telegram x" to "org.telegram.messenger.x",
            "whatsapp" to "com.whatsapp",
            "vk" to "com.vkontakte.android",
            "discord" to "com.discord",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "почта" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "settings" to "com.android.settings",
            "настройки" to "com.android.settings",
            "camera" to "com.android.camera2",
            "files" to "com.google.android.documentsui",
            "play" to "com.android.vending"
        )
        return if (name.contains('.')) name else map[name.trim().lowercase()] ?: name
    }
}
