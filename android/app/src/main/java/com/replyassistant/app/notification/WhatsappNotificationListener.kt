package com.replyassistant.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.replyassistant.app.ReplyAssistantApp
import com.replyassistant.app.api.SuggestRepository
import com.replyassistant.app.ClientLogReporter
import com.replyassistant.app.overlay.OverlayPresenter
import com.replyassistant.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhatsappNotificationListener : NotificationListenerService() {

    private val settings by lazy { SettingsRepository(this) }
    private val suggestRepo by lazy { SuggestRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.useNotifications) return
        val pkg = sbn.packageName
        if (pkg != WHATSAPP && pkg != WHATSAPP_BUSINESS) return

        val (sender, body) = extractNotificationText(sbn) ?: return
        if (body.isBlank()) return
        if (suggestRepo.shouldSkipDuplicate(body, sender)) return

        ReplyAssistantApp.instance.applicationScope.launch(Dispatchers.IO) {
            val result = suggestRepo.suggest(body, sender)
            result.onSuccess { list ->
                if (list.size == 3) {
                    OverlayPresenter.showSuggestionsAtTop(this@WhatsappNotificationListener, list)
                }
            }
            result.onFailure { e ->
                ClientLogReporter.report(
                    this@WhatsappNotificationListener,
                    "error",
                    "suggest failed (notification): ${e.message}",
                    e
                )
            }
        }
    }

    private fun extractNotificationText(sbn: StatusBarNotification): Pair<String?, String>? {
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)

        val body = when {
            !big.isNullOrBlank() -> big
            !lines.isNullOrEmpty() -> lines.joinToString("\n") { it.toString() }
            !text.isNullOrBlank() -> text
            else -> return null
        }

        val sender = title?.takeIf { it.isNotBlank() }
        return Pair(sender, body.trim())
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
