package com.hermesagent.hermes_android.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * List screen for switching between pinned/recent Hermes sessions from Android
 * Auto. Results are returned via [screenManager.pushForResult].
 */
class HermesSessionListScreen(carContext: CarContext) : Screen(carContext) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isLoading: Boolean = true

    @Volatile
    private var errorMessage: String? = null

    private val sessions = mutableListOf<CarHermesSession>()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        isLoading = true
        errorMessage = null
        invalidate()

        val prefs = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
        val host = prefs.getString("gateway_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val cookie = prefs.getString("session_cookie", "") ?: ""
        val cachedSessionsJson = prefs.getString("sessions_json", null)

        if (host.isEmpty()) {
            isLoading = false
            errorMessage = "Geen Gateway geconfigureerd."
            invalidate()
            return
        }

        val requestBuilder = Request.Builder().url("$host/api/sessions")
        if (apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            requestBuilder.addHeader("X-API-Key", apiKey)
        }
        if (cookie.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookie)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (!cachedSessionsJson.isNullOrEmpty()) {
                        parseSessionsJson(cachedSessionsJson)
                    } else {
                        errorMessage = "Kan sessies niet ophalen: ${e.localizedMessage}"
                    }
                    isLoading = false
                    invalidate()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "[]"
                mainHandler.post {
                    if (response.isSuccessful) {
                        prefs.edit().putString("sessions_json", body).apply()
                        parseSessionsJson(body)
                    } else {
                        errorMessage = "Gateway fout (${response.code})"
                    }
                    isLoading = false
                    invalidate()
                }
            }
        })
    }

    private fun parseSessionsJson(rawJson: String) {
        sessions.clear()
        try {
            val array = if (rawJson.trim().startsWith("[")) {
                JSONArray(rawJson)
            } else {
                JSONObject(rawJson).optJSONArray("sessions") ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id").ifEmpty { obj.optString("session_id", "default") }
                val title = obj.optString("title")
                    .ifEmpty { obj.optString("name", "Sessie $id") }
                val lastMsg = obj.optString("last_message")
                    .ifEmpty { obj.optString("updated_at", "Tik om te openen") }
                val pinned = obj.optBoolean("pinned", false) || obj.optBoolean("is_pinned", false)

                sessions.add(CarHermesSession(id, title, lastMsg, pinned))
            }

            sessions.sortByDescending { it.isPinned }
        } catch (e: Exception) {
            errorMessage = "Fout bij inlezen sessies: ${e.localizedMessage}"
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val prefs = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
        val currentSessionId = prefs.getString("session_id", "default")

        if (!errorMessage.isNullOrEmpty() && sessions.isEmpty()) {
            listBuilder.setNoItemsMessage(errorMessage!!)
        } else if (sessions.isEmpty() && !isLoading) {
            listBuilder.setNoItemsMessage("Geen actieve Hermes-sessies gevonden.")
        } else {
            sessions.take(6).forEach { session ->
                val prefix = buildString {
                    if (session.isPinned) append("📌 ")
                    if (session.id == currentSessionId) append("✓ ")
                }

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$prefix${session.title}")
                        .addText(session.subtitle.take(80))
                        .setOnClickListener {
                            prefs.edit()
                                .putString("session_id", session.id)
                                .putString("session_title", session.title)
                                .apply()
                            setResult(session)
                            finish()
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Kies Hermes Sessie")
            .setHeaderAction(Action.BACK)
            .setLoading(isLoading)
            .apply {
                if (!isLoading) {
                    setSingleList(listBuilder.build())
                }
            }
            .build()
    }
}

data class CarHermesSession(
    val id: String,
    val title: String,
    val subtitle: String,
    val isPinned: Boolean,
)
