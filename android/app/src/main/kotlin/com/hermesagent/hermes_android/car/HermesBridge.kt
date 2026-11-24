package com.hermesagent.hermes_android.car

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared bridge between the Flutter engine (MainActivity) and the Android Auto
 * car app. Holds the live gateway credentials/state so that [HermesCarAppService]
 * and its screens can render the latest assistant message without duplicating
 * login logic or storing secrets unencrypted.
 */
object HermesBridge {
    private const val PREFS_NAME = "HermesCarAuthCache"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_SESSION_COOKIE = "session_cookie"
    private const val KEY_LAST_MESSAGE = "last_message"

    @Volatile
    var gatewayUrl: String = ""
        private set

    @Volatile
    var apiKey: String = ""
        private set

    @Volatile
    var sessionCookie: String = ""
        private set

    @Volatile
    var lastMessage: String = "Geen actieve sessie geladen."
        private set

    @Volatile
    var onUpdateCallback: (() -> Unit)? = null

    fun updateFromFlutter(url: String, key: String, cookie: String, message: String) {
        gatewayUrl = url
        apiKey = key
        sessionCookie = cookie
        lastMessage = message
        onUpdateCallback?.invoke()
    }

    fun updateLastMessage(message: String) {
        lastMessage = message
        onUpdateCallback?.invoke()
    }

    fun saveToPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_SESSION_COOKIE, sessionCookie)
            .putString(KEY_LAST_MESSAGE, lastMessage)
            .apply()
    }

    fun loadFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString(KEY_GATEWAY_URL, "") ?: ""
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        sessionCookie = prefs.getString(KEY_SESSION_COOKIE, "") ?: ""
        lastMessage = prefs.getString(KEY_LAST_MESSAGE, "Geen actieve sessie geladen.") ?: "Geen actieve sessie geladen."
    }
}
