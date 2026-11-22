package com.example.phoneagent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationBridge : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    fun active(limit: Int): String {
        val notifications = activeNotifications ?: return "Нет доступа к уведомлениям"
        if (notifications.isEmpty()) return "Уведомлений нет"
        val sb = StringBuilder()
        for (sbn in notifications.take(limit)) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            sb.append(sbn.packageName).append(" | ").append(title).append(" | ").append(text)
            if (!big.isNullOrBlank() && big != text) sb.append(" | ").append(big)
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    companion object {
        @Volatile
        var instance: NotificationBridge? = null
    }
}
