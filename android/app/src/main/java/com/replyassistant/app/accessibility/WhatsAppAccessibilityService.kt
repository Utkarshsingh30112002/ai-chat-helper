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
        if (event?.packageName?.toString() == BuildConfig.APPLICATION_ID) return

        // Use the active window's package, not event.packageName — IME/WebView/sub-windows often
        // report a different package while WhatsApp is still open, which was hiding the FAB constantly.
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        val eventPkg = event?.packageName?.toString()

        when {
            isWhatsApp(rootPkg) || (rootPkg == null && isWhatsApp(eventPkg)) -> {
                FloatingIconManager.showIfNeeded(this) { requestSuggestionsFromCurrentChat() }
            }
            rootPkg != null && !isWhatsApp(rootPkg) -> {
                FloatingIconManager.hide()
            }
            // rootPkg == null and event not WA: leave FAB as-is (transient null → no flicker)
        }
    }

    private fun isWhatsApp(pkg: String?): Boolean =
        pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS

    override fun onInterrupt() {}

    /**
     * Called when the user taps the floating icon. Reads **only WhatsApp** nodes, filters junk,
     * then requests suggestions and shows the panel at the **top** of the screen.
     */
    fun requestSuggestionsFromCurrentChat() {
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            try {
                val contactName = extractChatTitle(root)

                val compose = findComposeField(root)
                if (compose == null) {
                    Toast.makeText(this, R.string.toast_open_chat_first, Toast.LENGTH_SHORT).show()
                    return@post
                }
                val composeRect = Rect()
                compose.getBoundsInScreen(composeRect)
                val composeTopY = composeRect.top
                compose.recycle()

                val sb = StringBuilder()
                collectChatVisibleText(root, composeTopY, sb)

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

                val payload = wrapTranscriptForModel(clipped, contactName)

                OverlayPresenter.removeSuggestionPanel()
                ReplyAssistantApp.instance.applicationScope.launch(Dispatchers.IO) {
                    val result = suggestRepo.suggest(payload, sender = contactName)
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

    /**
     * Best-effort chat title from the top toolbar (above the message list). Used as `sender` and
     * to label who "the other person" is in the wrapped transcript.
     */
    private fun extractChatTitle(root: AccessibilityNodeInfo): String? {
        val maxY = (resources.displayMetrics.heightPixels * 0.16f).toInt().coerceAtLeast(40)
        val candidates = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()?.trim() ?: return
            if (t.isEmpty() || t.length > 52) return
            if (TIME_LINE_PATTERN.matches(t)) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.centerY() >= maxY) return
            val tl = t.lowercase()
            if (tl in TITLE_BLOCKLIST) return
            candidates.add(t)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try {
                    walk(c)
                } finally {
                    c.recycle()
                }
            }
        }
        walk(root)
        return candidates
            .distinct()
            .filter { it.length >= 2 }
            .maxByOrNull { it.length }
    }

    /**
     * Explains two-sided transcript + passes contact name so the model suggests **user** replies only.
     */
    private fun wrapTranscriptForModel(transcript: String, contactName: String?): String {
        val other = contactName?.trim()?.takeIf { it.isNotEmpty() } ?: "the other person"
        val header = buildString {
            appendLine("You are helping the user write their next WhatsApp reply.")
            appendLine("Chat with: $other")
            appendLine(
                "The transcript may include both sides. The user's own messages may appear as a line \"You\" " +
                    "before their bubbles (or similar). Other lines are from $other."
            )
            appendLine()
            appendLine("--- Transcript ---")
        }
        val budget = (MAX_CONTEXT_CHARS - header.length).coerceAtLeast(0)
        return header + transcript.take(budget)
    }

    private fun filterChatContext(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.contains("Suggested replies", ignoreCase = true) &&
                    line != "Close" &&
                    !isLikelyUiChromeLine(line)
            }
            .joinToString("\n")
            .trim()
    }

    private fun isLikelyUiChromeLine(line: String): Boolean {
        val t = line.trim()
        if (t.length <= 2) return false
        val lower = t.lowercase()
        if (lower in UI_CHROME_EXACT_EN) return true
        if (t in UI_CHROME_EXACT_EN) return true
        if (t in UI_CHROME_EXACT_HI) return true
        if (lower.contains("double tap") && (lower.contains("record") || lower.contains("hold"))) return true
        if (lower.contains("slide left to cancel")) return true
        if (lower.contains("voice message") && lower.contains("button") && t.length > 35) return true
        return false
    }

    private fun looksLikeChatList(text: String): Boolean {
        if (text.length < 500) return false
        val l = text.lowercase()
        return l.contains("chats") &&
            l.contains("updates") &&
            (l.contains("communities") || l.contains("\nstatus\n") || l.contains(" recent updates"))
    }

    /**
     * Prefer the conversation RecyclerView / list region above the composer; if too little text,
     * fall back to a vertical band (excludes top toolbar + bottom composer chrome).
     */
    private fun collectChatVisibleText(root: AccessibilityNodeInfo, composeTopY: Int, sb: StringBuilder) {
        val scoped = findMessageListRoot(root, composeTopY)
        if (scoped != null) {
            try {
                collectVisibleText(scoped, sb, 0)
            } finally {
                scoped.recycle()
            }
        }
        if (sb.length < MIN_SCOPED_CHARS) {
            sb.clear()
            val topMinCenterY = dp(140f).coerceAtLeast(0).coerceAtMost(composeTopY - 1)
            if (composeTopY > topMinCenterY) {
                collectVisibleTextInVerticalBand(root, sb, 0, composeTopY, topMinCenterY)
            } else {
                collectVisibleText(root, sb, 0)
            }
        }
    }

    private fun dp(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /** Largest RecyclerView sitting above the compose bar (typical message list on WA 2.26+). */
    private fun findRecyclerAboveCompose(root: AccessibilityNodeInfo, composeTopY: Int): AccessibilityNodeInfo? {
        val minHeight = dp(120f)
        val slop = dp(8f)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("RecyclerView", ignoreCase = true)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.height() >= minHeight && r.bottom <= composeTopY + slop) {
                    candidates.add(AccessibilityNodeInfo.obtain(n))
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                try {
                    walk(child)
                } finally {
                    child.recycle()
                }
            }
        }
        walk(root)
        val best = candidates.maxByOrNull {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.width().toLong() * r.height()
        }
        candidates.filter { it != best }.forEach { it.recycle() }
        return best
    }

    /** Fallback: id hints seen on some WhatsApp builds (package-specific ids). */
    private fun findConversationLikeContainer(root: AccessibilityNodeInfo, composeTopY: Int): AccessibilityNodeInfo? {
        val minHeight = dp(100f)
        val slop = dp(8f)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            val vid = n.viewIdResourceName ?: ""
            val idMatch =
                (vid.contains("conversation") && (vid.contains("list") || vid.contains("messages"))) ||
                    vid.contains("messages_list") ||
                    vid.contains("message_list")
            if (idMatch) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.height() >= minHeight && r.bottom <= composeTopY + slop) {
                    candidates.add(AccessibilityNodeInfo.obtain(n))
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                try {
                    walk(child)
                } finally {
                    child.recycle()
                }
            }
        }
        walk(root)
        val best = candidates.maxByOrNull {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.height()
        }
        candidates.filter { it != best }.forEach { it.recycle() }
        return best
    }

    private fun findMessageListRoot(root: AccessibilityNodeInfo, composeTopY: Int): AccessibilityNodeInfo? {
        findRecyclerAboveCompose(root, composeTopY)?.let { return it }
        return findConversationLikeContainer(root, composeTopY)
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

    private fun collectVisibleTextInVerticalBand(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        composeTopY: Int,
        topMinCenterY: Int
    ) {
        if (depth > 120) return
        if (node.packageName?.toString() == BuildConfig.APPLICATION_ID) return
        val r = Rect()
        node.getBoundsInScreen(r)
        val cy = r.centerY()
        val inBand = cy >= topMinCenterY && cy < composeTopY
        if (inBand) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.appendLine(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.appendLine(it) }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectVisibleTextInVerticalBand(child, sb, depth + 1, composeTopY, topMinCenterY)
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
        /** If scoped RecyclerView capture yields less than this, use vertical-band fallback. */
        private const val MIN_SCOPED_CHARS = 35

        private val TIME_LINE_PATTERN = Regex("^\\d{1,2}:\\d{2}$")

        /** Toolbar / tab labels — not chat titles (lowercase match). */
        private val TITLE_BLOCKLIST = setOf(
            "back",
            "search",
            "chats",
            "calls",
            "status",
            "camera",
            "settings",
            "more",
            "archived",
            "starred",
            "select",
            "voice call",
            "video call",
            "whatsapp",
            "view contact",
            "mute",
            "block",
            "online",
            "typing",
            "typing…",
            "typing...",
            "forward to…",
            "forward to...",
        )

        private val UI_CHROME_EXACT_EN = setOf(
            "back",
            "close",
            "read",
            "today",
            "forward to…",
            "forward to...",
            "more options",
            "message",
            "attach",
            "camera",
            "message yourself",
            "emoji, gifs and stickers",
        )

        /** Short Devanagari labels that are usually toolbar / composer (not normal chat). */
        private val UI_CHROME_EXACT_HI = setOf(
            "संदेश",
            "अटैच",
            "कैमरा",
            "आगे भेजें…",
            "आगे भेजें...",
        )

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
