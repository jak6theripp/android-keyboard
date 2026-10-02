package org.futo.inputmethod.latin.dictation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether streaming dictation can reach the network right now. */
object NetworkStatus {
    /** Debug: pretend there is no network (to exercise the offline route without touching radios). */
    @Volatile var debugForceOffline = false

    fun isOnline(context: Context): Boolean {
        if (debugForceOffline) return false
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // if the state can't be read, try the network rather than silently downgrading
        }
    }
}
