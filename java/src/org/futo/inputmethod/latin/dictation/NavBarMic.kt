package org.futo.inputmethod.latin.dictation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.getSetting

/**
 * Mic button in the One UI navigation bar, next to back/home/recents, while this keyboard is showing.
 *
 * One UI lets an app place a small RemoteViews "shortcut" in the navigation bar through
 * SemStatusBarManager.setNavigationBarShortcut (how Samsung Keyboard gets its mic there). The system
 * service forwards the call to SystemUI without a permission check; only the client-side wrapper
 * checks the signature permission STATUS_BAR_SERVICE, against the Context it was created with. That
 * Context is the service that asked for the manager, so LatinIME answers the check itself while
 * [calling] is set. Samsung-only and undocumented: every step is best-effort and failure just means
 * no button (the bottom-row mic key remains).
 *
 * Observed on SM-F966U / One UI 8.5 (2026-10-02): SystemUI accepts the request but only draws it when
 * the IME-switcher button is not occupying that slot, i.e. when the One UI setting "Show input method
 * button on navigation bar" (secure show_keyboard_button) is off. The request name does not matter.
 */
object NavBarMic {
    private const val TAG = "NavBarMic"
    private const val SERVICE = "sem_statusbar"
    const val PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    /** Debug-settable (DictationDebugReceiver) while the placement rules of One UI are being worked out. */
    @Volatile var requestClass = "org.futo.inputmethod.latin.dictation.NavBarMic"
    @Volatile var position = 0          // 0 = left of the buttons, 1 = right
    @Volatile var priority = 5

    /** True only during our own call into SemStatusBarManager (main thread). */
    @Volatile var calling = false
        private set

    private var shown = false

    fun show(service: Context) {
        if (!service.getSetting(DICTATION_NAVBAR_MIC)) { hide(service); return }
        val tap = PendingIntent.getBroadcast(service, 0,
            Intent(service, NavBarMicReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(service.packageName, R.layout.dictation_navbar_mic)
        views.setOnClickPendingIntent(R.id.dictation_navbar_mic, tap)
        if (set(service, views)) shown = true
    }

    /** Debug: remove the current button, change how it is requested, and ask again. */
    fun debugReconfigure(service: Context, newClass: String?, newPosition: Int, newPriority: Int) {
        hide(service)
        if (newClass != null) requestClass = newClass
        position = newPosition; priority = newPriority
        show(service)
    }

    fun hide(service: Context) {
        if (!shown) return
        set(service, null)
        shown = false
    }

    private fun set(service: Context, views: RemoteViews?): Boolean {
        return try {
            val manager = service.getSystemService(SERVICE) ?: return false   // not a Samsung device
            val method = manager.javaClass.getMethod("setNavigationBarShortcut",
                String::class.java, RemoteViews::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            calling = true
            method.invoke(manager, requestClass, views, position, priority)
            Log.i(TAG, "setNavigationBarShortcut ${if (views != null) "shown" else "removed"} class=$requestClass position=$position priority=$priority")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "setNavigationBarShortcut failed: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        } finally {
            calling = false
        }
    }
}

/** The navigation-bar mic was tapped. Not exported: only our own PendingIntent reaches it. */
class NavBarMicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DictationController.instance?.onNavBarMicTapped()
    }
}
