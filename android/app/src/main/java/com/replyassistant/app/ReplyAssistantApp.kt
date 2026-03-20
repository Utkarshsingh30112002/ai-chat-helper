package com.replyassistant.app

import android.app.Application
import com.replyassistant.app.accessibility.WhatsAppAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReplyAssistantApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var accessibilityService: WhatsAppAccessibilityService? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ReplyAssistantApp
            private set
    }
}
