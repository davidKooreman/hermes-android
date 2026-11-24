package com.hermesagent.hermes_android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView

/**
 * Voice interaction session started by the microphone key.
 *
 * It shows a small listening overlay inside the session window, streams speech
 * recognition, and injects the recognized text into the currently focused text
 * field via the accessibility service.
 */
class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var statusText: TextView? = null
    private var listening = false
    private var started = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    @SuppressLint("InflateParams")
    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.voice_interaction_overlay, null)
        statusText = view.findViewById(R.id.voice_status_text)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow flags=$showFlags")
        if (!started) {
            started = true
            startListening()
        }
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "onHide")
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopListening()
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            finishWithResult(null, context.getString(R.string.voice_error_client))
            return
        }

        updateStatus(context.getString(R.string.voice_status_listening))

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech")
                    updateStatus(context.getString(R.string.voice_status_speak_now))
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech")
                    updateStatus(context.getString(R.string.voice_status_processing))
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "onError $error")
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.voice_error_audio)
                        SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.voice_error_client)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.voice_error_permission)
                        SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.voice_error_network)
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.voice_error_network)
                        SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.voice_error_no_match)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.voice_error_busy)
                        SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.voice_error_server)
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> context.getString(R.string.voice_error_server)
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.voice_error_no_speech)
                        else -> context.getString(R.string.voice_error_unknown)
                    }
                    finishWithResult(null, message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.d(TAG, "onResults text=$text")
                    if (!text.isNullOrBlank()) {
                        finishWithResult(text, null)
                    } else {
                        finishWithResult(null, context.getString(R.string.voice_error_no_match))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        updateStatus(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            listening = true
            startListening(intent)
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun updateStatus(text: String?) {
        handler.post {
            statusText?.text = text ?: ""
            statusText?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun finishWithResult(text: String?, error: String?) {
        if (text != null) {
            updateStatus(text)
            injectText(text)
            handler.postDelayed({ finish() }, 800)
        } else {
            updateStatus(error)
            handler.postDelayed({ finish() }, 1500)
        }
    }

    private fun injectText(text: String) {
        try {
            val intent = Intent(HermesAccessibilityService.ACTION_INJECT_TEXT).apply {
                setPackage(context.packageName)
                putExtra(HermesAccessibilityService.EXTRA_TEXT, text)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send inject broadcast", e)
        }
    }

    companion object {
        private const val TAG = "HermesVoiceSession"
    }
}
