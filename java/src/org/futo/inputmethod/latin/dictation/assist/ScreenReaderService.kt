package org.futo.inputmethod.latin.dictation.assist

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

enum class Sender { Me, Them, Unknown }
data class ScreenMessage(val sender: Sender, val text: String)
data class ScreenSnapshot(val title: String?, val messages: List<ScreenMessage>)

/**
 * Opt-in screen reader for AI reply mode. It does nothing on its own: it subscribes to no
 * accessibility events and never looks at the screen until [readNow] is called, which happens
 * only when the user taps the AI button. Nothing it reads is stored or sent anywhere except as
 * context for that one reply draft.
 */
class ScreenReaderService : AccessibilityService() {
    companion object {
        private const val TAG = "ScreenReader"
        private const val MAX_MESSAGES = 10
        @Volatile private var instance: ScreenReaderService? = null
        val isEnabled: Boolean get() = instance != null
        /** One-shot read of the conversation currently on screen, or null if the service is off. */
        fun snapshot(): ScreenSnapshot? = try { instance?.readNow() } catch (e: Exception) { Log.w(TAG, "read failed", e); null }

        private val NOISE = Regex(
            "^(\\d{1,2}:\\d{2}( ?[AaPp][Mm])?|delivered|read|sent|sending…?|seen|now|today|yesterday|" +
                "mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
                "rcs message|text message|sms|mms|message|type a message|aa)$", RegexOption.IGNORE_CASE)
    }

    override fun onServiceConnected() {
        // No events at all: this service is only ever asked, never told.
        serviceInfo = serviceInfo?.apply { eventTypes = 0 }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally unused */ }
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    private data class Item(val text: String, val bounds: Rect)

    fun readNow(): ScreenSnapshot? {
        // The app window, not the keyboard or system bars.
        val appRoot = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.let { r -> Rect().also { w.getBoundsInScreen(it) } to r } }
            .maxByOrNull { (b, _) -> b.width() * b.height() }
            ?: rootInActiveWindow?.let { r -> Rect().also { r.getBoundsInScreen(it) } to r }
            ?: return null
        val (win, root) = appRoot
        if (win.width() <= 0 || win.height() <= 0) return null

        val items = ArrayList<Item>()
        collect(root, items, 0)
        if (items.isEmpty()) return ScreenSnapshot(null, emptyList())
        items.sortBy { it.bounds.top }

        // Header: the first text in the top strip of the window is usually the contact or thread name.
        val headerLimit = win.top + (win.height() * 0.14f).toInt()
        val title = items.firstOrNull { it.bounds.bottom <= headerLimit && it.text.length in 2..40 }?.text

        val body = items.filter { it.bounds.top > headerLimit && !NOISE.matches(it.text) && it.text.length >= 2 }
        val messages = body.map { item ->
            val left = (item.bounds.left - win.left).toFloat() / win.width()
            val right = (item.bounds.right - win.left).toFloat() / win.width()
            // Chat apps put the user's own bubbles against the right edge and the other side's on the left.
            val sender = when {
                right > 0.80f && left > 0.22f -> Sender.Me
                left < 0.22f && right < 0.92f -> Sender.Them
                else -> Sender.Unknown
            }
            ScreenMessage(sender, item.text.take(600))
        }
        return ScreenSnapshot(title, messages.takeLast(MAX_MESSAGES))
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (node == null || depth > 40 || out.size > 400) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && !node.isEditable && !node.isPassword) {
                val cls = node.className?.toString() ?: ""
                if (!cls.endsWith("Button") && !cls.endsWith("EditText")) {
                    val r = Rect(); node.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) out.add(Item(text, r))
                }
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }
}
