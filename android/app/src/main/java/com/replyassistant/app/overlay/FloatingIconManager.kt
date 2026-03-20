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
import com.replyassistant.app.ClientLogReporter
import com.replyassistant.app.R

/**
 * Small draggable-style FAB at bottom-end; does not cover the WhatsApp input bar.
 * Shown only while WhatsApp is in the foreground.
 */
object FloatingIconManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fabView: android.view.View? = null
    private var windowManager: WindowManager? = null

    fun showIfNeeded(context: Context, onTap: () -> Unit) {
        val appCtx = context.applicationContext
        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                    return@post
                }
                if (fabView != null) return@post
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val themed = ContextThemeWrapper(appCtx, R.style.Theme_ReplyAssistant)
                val inflater = LayoutInflater.from(themed)
                val view = inflater.inflate(R.layout.overlay_floating_icon, null)
                view.findViewById<android.view.View>(R.id.fab_root).setOnClickListener { onTap() }

                val size = appCtx.resources.getDimensionPixelSize(R.dimen.reply_fab_size)
                val params = WindowManager.LayoutParams(
                    size,
                    size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.x = -(appCtx.resources.displayMetrics.density * 12).toInt()
                params.y = appCtx.resources.getDimensionPixelSize(R.dimen.reply_fab_bottom_offset)

                wm.addView(view, params)
                fabView = view
                windowManager = wm
            } catch (e: Exception) {
                ClientLogReporter.report(appCtx, "error", "fab_show_failed: ${e.message}", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                fabView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (_: Exception) {
            }
            fabView = null
            windowManager = null
        }
    }
}
