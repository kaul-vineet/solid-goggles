package com.contoso.mdmtest

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JavaScript → Native bridge.
 *
 * Registered as window.NativeBridge in the WebView.
 * The JS chat calls these when its token expires or auth fails,
 * triggering a silent token refresh on the native side.
 */
class TokenBridge(private val onTokenExpired: () -> Unit) {

    @JavascriptInterface
    fun onTokenExpired() {
        Log.i(App.TAG, "TokenBridge: JS reported token expired — triggering refresh")
        onTokenExpired.invoke()
    }

    @JavascriptInterface
    fun onAuthFailed(reason: String) {
        Log.e(App.TAG, "TokenBridge: JS reported auth failed — reason=$reason")
        // For this test scaffold: treat auth failure same as expiry and retry.
        // Production code should distinguish permanent failures from transient ones.
        onTokenExpired.invoke()
    }

    @JavascriptInterface
    fun log(message: String) {
        // Convenience: lets JS write to Android logcat for debugging
        Log.d(App.TAG, "[JS] $message")
    }
}
