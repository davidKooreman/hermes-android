package com.hermesagent.hermes_android.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Primary screen shown in the car. Displays the latest assistant message,
 * refreshes from the Hermes Gateway, lets the driver dictate a reply, and
 * reads replies aloud via TTS with car audio ducking.
 */
class HermesStatusScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "HermesCar"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var statusTitle: String = "Hermes Agent"

    @Volatile
    private var statusMessage: String = HermesBridge.lastMessage

    @Volatile
    private var isLoading: Boolean = false

    @Volatile
    private var isListening: Boolean = false

    @Volatile
    private var isAutoSpeakEnabled: Boolean = true

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        HermesBridge.loadFromPreferences(carContext)
        initTextToSpeech()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                abandonCarAudioFocus()
                tts?.stop()
                tts?.shutdown()
                speechRecognizer?.destroy()
            }
        })

        HermesBridge.onUpdateCallback = {
            mainHandler.post {
                statusTitle = "Hermes Agent"
                statusMessage = HermesBridge.lastMessage
                isLoading = false
                invalidate()
                if (isAutoSpeakEnabled) {
                    speakOutLoud(statusMessage)
                }
            }
        }
        fetchLatestHermesMessage()
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()
        val prefs = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
        val activeSessionTitle = prefs.getString("session_title", null)?.ifEmpty { null }

        if (isLoading) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(CarText.create("Laden..."))
                    .addText(CarText.create("Verbinden met Hermes Gateway..."))
                    .build()
            )
        }

        paneBuilder.addRow(
            Row.Builder()
                .setTitle(CarText.create(statusTitle))
                .addText(CarText.create(statusMessage))
                .build()
        )

        val pane = paneBuilder.build()

        val stripBuilder = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(CarText.create("Sessies"))
                    .setOnClickListener {
                        screenManager.pushForResult(HermesSessionListScreen(carContext)) { result ->
                            val chosen = result as? CarHermesSession
                            if (chosen != null) {
                                statusTitle = "Hermes: ${chosen.title}"
                                statusMessage = chosen.subtitle
                                invalidate()
                                fetchLatestMessageForSession(chosen.id)
                            }
                        }
                    }
                    .build()
            )

        if (!isLoading) {
            stripBuilder
                .addAction(
                    Action.Builder()
                        .setTitle(CarText.create(if (isListening) "Stop" else "Spreek"))
                        .setBackgroundColor(CarColor.PRIMARY)
                        .setOnClickListener {
                            if (isListening) stopVoiceInput() else startVoiceInput()
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle(CarText.create(if (tts?.isSpeaking == true) "Stil" else "Lees"))
                        .setOnClickListener {
                            if (tts?.isSpeaking == true) {
                                tts?.stop()
                                abandonCarAudioFocus()
                            } else {
                                speakOutLoud(statusMessage)
                            }
                            invalidate()
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle(CarText.create("Ververs"))
                        .setOnClickListener { fetchLatestHermesMessage() }
                        .build()
                )
        }

        return PaneTemplate.Builder(pane)
            .setTitle(
                activeSessionTitle?.let { "$statusTitle ($it)" } ?: statusTitle
            )
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(stripBuilder.build())
            .build()
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(carContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("nl", "NL"))
                isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED)

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attrs)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        abandonCarAudioFocus()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        abandonCarAudioFocus()
                    }
                })
            }
        }
    }

    private fun speakOutLoud(rawText: String) {
        if (!isTtsReady || rawText.isBlank()) return

        tts?.stop()

        val cleanText = rawText
            .replace(Regex("```[\\s\\S]*?```"), " codeblok weggelaten. ")
            .replace(Regex("[*_~`#\u003e]"), "")
            .replace(Regex("https?://\\S+"), "link")
            .trim()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    tts?.stop()
                }
            }
            .build()

        audioFocusRequest = focusRequest
        val res = audioManager.requestAudioFocus(focusRequest)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "HERMES_CAR_REPLY")
        }
    }

    private fun abandonCarAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun startVoiceInput() {
        tts?.stop()
        abandonCarAudioFocus()

        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (granted.contains(Manifest.permission.RECORD_AUDIO)) {
                    startVoiceInput()
                } else {
                    CarToast.makeText(
                        carContext,
                        "Geef microfoon-toestemming in de Hermes app.",
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }

        mainHandler.post {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(carContext)
            }

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    statusTitle = "Luisteren..."
                    statusMessage = "Spreek je bericht in"
                    invalidate()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    isLoading = true
                    statusTitle = "Verwerken..."
                    invalidate()
                }

                override fun onError(error: Int) {
                    isListening = false
                    isLoading = false
                    statusTitle = "Spraakfout ($error)"
                    statusMessage = "Tik op 'Spreek' om opnieuw te proberen."
                    invalidate()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        sendVoiceMessageToHermes(spokenText)
                    } else {
                        isLoading = false
                        statusTitle = "Niets gehoord"
                        invalidate()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        }
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isListening = false
        invalidate()
    }

    private fun sendVoiceMessageToHermes(promptText: String) {
        isLoading = true
        statusTitle = "Hermes denkt na..."
        statusMessage = "Jij: \"$promptText\""
        invalidate()

        val engine = com.hermesagent.hermes_android.HermesEngineHolder.engine
        if (engine != null) {
            sendViaFlutter(engine, promptText)
            return
        }

        sendDirectly(promptText)
    }

    private fun sendViaFlutter(engine: FlutterEngine, promptText: String) {
        val channel = MethodChannel(
            engine.dartExecutor.binaryMessenger,
            "com.hermesagent.hermes_android/car_voice"
        )
        channel.invokeMethod(
            "voiceMessage",
            mapOf("text" to promptText),
            object : MethodChannel.Result {
                override fun success(result: Any?) {
                    // The Flutter side will sync the reply back through HermesBridge.
                }
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    Log.w(TAG, "voiceMessage via Flutter failed: $errorCode $errorMessage")
                    sendDirectly(promptText)
                }
                override fun notImplemented() {
                    sendDirectly(promptText)
                }
            }
        )
    }

    private fun sendDirectly(promptText: String) {
        val host = HermesBridge.gatewayUrl.trimEnd('/')
        val apiKey = HermesBridge.apiKey
        val cookie = HermesBridge.sessionCookie
        val activeSessionId = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
            .getString("session_id", "default") ?: "default"

        if (host.isEmpty()) {
            statusTitle = "Geen Gateway URL"
            statusMessage = "Open eerst de Hermes app op je telefoon."
            isLoading = false
            invalidate()
            return
        }

        val json = JSONObject().apply {
            put("message", promptText)
            put("session_id", activeSessionId)
            put("stream", false)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url("$host/api/chat")
            .post(body)

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
                    statusTitle = "Verzenden mislukt"
                    statusMessage = "Fout bij verbinden: ${e.localizedMessage}"
                    isLoading = false
                    invalidate()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful) {
                        statusTitle = "Hermes Agent"
                        val parsed = try {
                            val j = JSONObject(raw)
                            j.optString("reply")
                                .ifEmpty { j.optString("response") }
                                .ifEmpty { j.optString("content") }
                                .ifEmpty { raw }
                        } catch (_: Exception) {
                            raw
                        }
                        statusMessage = parsed.take(240)
        HermesBridge.updateLastMessage(statusMessage)
                        if (isAutoSpeakEnabled) {
                            speakOutLoud(parsed)
                        }
                    } else {
                        statusTitle = "Fout (${response.code})"
                        statusMessage = "Gateway weigerde bericht: ${raw.take(150)}"
                    }
                    isLoading = false
                    invalidate()
                }
            }
        })
    }

    private fun fetchLatestHermesMessage() {
        val prefs = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
        val cachedMsg = prefs.getString("last_message", null)
        if (!cachedMsg.isNullOrEmpty()) {
            statusMessage = cachedMsg
            invalidate()
        }
    }

    private fun fetchLatestMessageForSession(sessionId: String) {
        val prefs = carContext.getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
        val host = prefs.getString("gateway_url", "")?.trimEnd('/') ?: return
        val apiKey = prefs.getString("api_key", "") ?: ""
        val cookie = prefs.getString("session_cookie", "") ?: ""

        isLoading = true
        invalidate()

        val requestBuilder = Request.Builder().url("$host/api/sessions/$sessionId")
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
                    isLoading = false
                    invalidate()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                mainHandler.post {
                    if (response.isSuccessful && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val messages = json.optJSONArray("messages")
                            if (messages != null && messages.length() > 0) {
                                val lastObj = messages.getJSONObject(messages.length() - 1)
                                statusMessage = lastObj.optString("content", statusMessage).take(240)
                                HermesBridge.updateLastMessage(statusMessage)
                            }
                        } catch (_: Exception) {}
                    }
                    isLoading = false
                    invalidate()
                }
            }
        })
    }
}
