package com.example.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        McpServer.start(context)
        val saved = context.getSharedPreferences("phone_agent", Context.MODE_PRIVATE)
            .getString("capture_permission", null) ?: return
        try {
            ScreenCaptureHolder.permissionIntent = Intent.parseUri(saved, 0)
            CaptureService.start(context)
        } catch (_: Throwable) {
            // Permission is no longer valid — the user can re-grant it in the app.
        }
    }
}
