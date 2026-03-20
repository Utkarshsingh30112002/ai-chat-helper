package com.replyassistant.app.api

/**
 * Shared across notification + accessibility paths so we do not double-hit the API
 * for the same content within a short window.
 */
object SuggestDedupe {

    @Volatile
    private var lastFingerprint: String? = null

    @Volatile
    private var lastAtMs: Long = 0L

    fun shouldSkip(message: String, sender: String?): Boolean {
        val fp = "${sender.orEmpty()}::${message.take(2000)}"
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (fp == lastFingerprint && now - lastAtMs < 45_000L) {
                return true
            }
            lastFingerprint = fp
            lastAtMs = now
        }
        return false
    }
}
