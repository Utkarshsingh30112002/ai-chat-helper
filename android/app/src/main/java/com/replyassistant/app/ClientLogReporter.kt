package com.replyassistant.app

import android.content.Context
import android.os.Build
import com.replyassistant.app.api.SuggestRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sends diagnostics to your backend [POST /v1/client-log] and appends a copy under [filesDir]/app_events.log.
 */
object ClientLogReporter {

    fun report(context: Context, level: String, message: String, throwable: Throwable? = null) {
        val appCtx = context.applicationContext
        val stack = throwable?.stackTraceToString()
        val line =
            "${System.currentTimeMillis()}\t$level\t${message.replace("\n", " ")}\t${stack?.take(500)?.replace("\n", " ") ?: ""}"
        appendLocalFile(appCtx, line)

        try {
            ReplyAssistantApp.instance.applicationScope.launch(Dispatchers.IO) {
                try {
                    SuggestRepository(appCtx).sendClientLog(level, message, stack)
                } catch (_: Exception) {
                }
            }
        } catch (_: UninitializedPropertyAccessException) {
        }
    }

    private fun appendLocalFile(context: Context, line: String) {
        try {
            val f = File(context.filesDir, "app_events.log")
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun deviceSummary(): String =
        "sdk=${Build.VERSION.SDK_INT} model=${Build.MANUFACTURER} ${Build.MODEL}"
}
