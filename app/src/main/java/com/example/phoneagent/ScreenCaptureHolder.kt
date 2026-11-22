package com.example.phoneagent

import android.content.Intent

object ScreenCaptureHolder {
    @Volatile
    var permissionIntent: Intent? = null
}
