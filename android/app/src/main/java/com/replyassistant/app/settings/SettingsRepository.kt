package com.replyassistant.app.settings

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()
        }

    var bearerToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    /** When true, auto-fetch suggestions when a WhatsApp notification arrives. Default off: use the floating button in chat. */
    var useNotifications: Boolean
        get() = prefs.getBoolean(KEY_USE_NOTIFICATIONS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_NOTIFICATIONS, value).apply()
        }

    var useAccessibility: Boolean
        get() = prefs.getBoolean(KEY_USE_ACCESSIBILITY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_ACCESSIBILITY, value).apply()
        }

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && bearerToken.isNotBlank()

    companion object {
        private const val PREFS = "reply_assistant"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USE_NOTIFICATIONS = "use_notifications"
        private const val KEY_USE_ACCESSIBILITY = "use_accessibility"
    }
}
