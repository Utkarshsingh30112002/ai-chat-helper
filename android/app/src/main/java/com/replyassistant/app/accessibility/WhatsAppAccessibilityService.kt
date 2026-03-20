package com.replyassistant.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.replyassistant.app.BuildConfig
import com.replyassistant.app.ClientLogReporter
import com.replyassistant.app.R
import com.replyassistant.app.ReplyAssistantApp
import com.replyassistant.app.api.SuggestRepository
import com.replyassistant.app.overlay.FloatingIconManager
import com.replyassistant.app.overlay.OverlayPresenter
import com.replyassistant.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.Rect

class WhatsAppAccessibilityService : AccessibilityService() {

    private val settings by lazy { SettingsRepository(this) }
    private val suggestRepo by lazy { SuggestRepository(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ReplyAssistantApp.instance.accessibilityService = this
    }

    override fun onDestroy() {
        FloatingIconManager.hide()
        OverlayPresenter.removeSuggestionPanel()
        if (ReplyAssistantApp.instance.accessibilityService === this) {
            ReplyAssistantApp.instance.accessibilityService = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!settings.useAccessibility) {
            FloatingIconManager.hide()
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == BuildConfig.APPLICATION_ID) return

        when (pkg) {
            WHATSAPP, WHATSAPP_BUSINESS -> {
                FloatingIconManager.showIfNeeded(this) { requestSuggestionsFromCurrentChat() }
            }
            else -> FloatingIconManager.hide()
        }
    }

    override fun onInterrupt() {}

    /**
     * Called when the user taps the floating icon. Reads **only WhatsApp** nodes, filters junk,
     * then requests suggestions and shows the panel at the **top** of the screen.
     */
    fun requestSuggestionsFromCurrentChat() {
        mainHandler.post {
            val rootCheck = rootInActiveWindow ?: return@post
            try {
                if (findComposeField(rootCheck) == null) {
                    Toast.makeText(this, R.string.toast_open_chat_first, Toast.LENGTH_SHORT).show()
                    return@post
                }
            } finally {
                rootCheck.recycle()
            }

            val root = rootInActiveWindow ?: return@post
            try {
                val sb = StringBuilder()
                collectVisibleText(root, sb, 0)
                val raw = sb.toString().trim()
                val filtered = filterChatContext(raw)
                if (looksLikeChatList(filtered)) {
                    Toast.makeText(this, R.string.toast_chat_list_open, Toast.LENGTH_SHORT).show()
                    return@post
                }
                val clipped = filtered.take(MAX_CONTEXT_CHARS)
                if (clipped.length < 5) {
                    Toast.makeText(this, R.string.toast_no_text_captured, Toast.LENGTH_SHORT).show()
                    return@post
                }

                OverlayPresenter.removeSuggestionPanel()
                ReplyAssistantApp.instance.applicationScope.launch(Dispatchers.IO) {
                    val result = suggestRepo.suggest(clipped, sender = null)
                    result.onSuccess { list ->
                        if (list.size == 3) {
                            mainHandler.post {
                                OverlayPresenter.showSuggestionsAtTop(this@WhatsAppAccessibilityService, list)
                            }
                        }
                    }
                    result.onFailure { e ->
                        ClientLogReporter.report(
                            this@WhatsAppAccessibilityService,
                            "error",
                            "suggest failed (fab): ${e.message}",
                            e
                        )
                    }
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun filterChatContext(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.contains("Suggested replies", ignoreCase = true) &&
                    line != "Close"
            }
            .joinToString("\n")
            .trim()
    }

    private fun looksLikeChatList(text: String): Boolean {
        if (text.length < 500) return false
        val l = text.lowercase()
        return l.contains("chats") &&
            l.contains("updates") &&
            (l.contains("communities") || l.contains("\nstatus\n") || l.contains(" recent updates"))
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 120) return
        val pn = node.packageName?.toString()
        if (pn == BuildConfig.APPLICATION_ID) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.appendLine(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.appendLine(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectVisibleText(child, sb, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    fun injectIntoCompose(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val input = findComposeField(root) ?: return false
            try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ok
            } finally {
                input.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findComposeField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.className == "android.widget.EditText" && node.isEditable) {
                candidates.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child)
                } finally {
                    child.recycle()
                }
            }
        }
        walk(root)
        val best = candidates.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.bottom
        }
        candidates.filter { it != best }.forEach { it.recycle() }
        return best
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val MAX_CONTEXT_CHARS = 8000

        fun copyToClipboard(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("reply", text))
        }

        fun tryInjectOrCopy(context: Context, text: String) {
            val svc = ReplyAssistantApp.instance.accessibilityService
            val injected = svc?.injectIntoCompose(text) == true
            if (!injected) {
                copyToClipboard(context, text)
                Toast.makeText(
                    context.applicationContext,
                    R.string.inject_failed_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
