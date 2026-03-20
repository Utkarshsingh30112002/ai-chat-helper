package com.replyassistant.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import com.replyassistant.app.ClientLogReporter
import com.replyassistant.app.R
import com.replyassistant.app.accessibility.WhatsAppAccessibilityService

/**
 * Suggestion panel at the **top** of the screen so it does not cover the WhatsApp type field.
 */
object OverlayPresenter {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelView: android.view.View? = null
    private var windowManager: WindowManager? = null

    private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun removeSuggestionPanel() {
        mainHandler.post {
            try {
                panelView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (_: Exception) {
            }
            panelView = null
            windowManager = null
        }
    }

    fun showSuggestionsAtTop(context: Context, suggestions: List<String>) {
        if (suggestions.size != 3) return
        val appCtx = context.applicationContext
        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                    ClientLogReporter.report(
                        appCtx,
                        "warn",
                        "overlay_skipped: display over other apps not allowed"
                    )
                    return@post
                }
                removeSuggestionPanelSync()
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val themed = ContextThemeWrapper(appCtx, R.style.Theme_ReplyAssistant)
                val inflater = LayoutInflater.from(themed)
                val view = inflater.inflate(R.layout.overlay_suggestions, null)

                view.findViewById<MaterialButton>(R.id.buttonSuggestion1).apply {
                    text = suggestions[0]
                    setOnClickListener {
                        WhatsAppAccessibilityService.tryInjectOrCopy(appCtx, suggestions[0])
                        removeSuggestionPanelSync()
                    }
                }
                view.findViewById<MaterialButton>(R.id.buttonSuggestion2).apply {
                    text = suggestions[1]
                    setOnClickListener {
                        WhatsAppAccessibilityService.tryInjectOrCopy(appCtx, suggestions[1])
                        removeSuggestionPanelSync()
                    }
                }
                view.findViewById<MaterialButton>(R.id.buttonSuggestion3).apply {
                    text = suggestions[2]
                    setOnClickListener {
                        WhatsAppAccessibilityService.tryInjectOrCopy(appCtx, suggestions[2])
                        removeSuggestionPanelSync()
                    }
                }
                view.findViewById<android.widget.ImageButton>(R.id.buttonCloseOverlay).setOnClickListener {
                    removeSuggestionPanelSync()
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = appCtx.resources.getDimensionPixelSize(R.dimen.reply_panel_top_margin)

                wm.addView(view, params)
                panelView = view
                windowManager = wm
            } catch (e: Exception) {
                ClientLogReporter.report(appCtx, "error", "overlay_show_failed: ${e.message}", e)
            }
        }
    }

    private fun removeSuggestionPanelSync() {
        try {
            panelView?.let { v ->
                windowManager?.removeView(v)
            }
        } catch (_: Exception) {
        }
        panelView = null
        windowManager = null
    }

    /** @deprecated use removeSuggestionPanel */
    fun removeInternal() {
        removeSuggestionPanel()
    }
}
