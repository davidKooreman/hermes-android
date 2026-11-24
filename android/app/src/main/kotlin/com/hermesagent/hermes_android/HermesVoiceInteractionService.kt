package com.hermesagent.hermes_android

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Entry point for the TV microphone / hotword button.
 *
 * When this service is set as the default voice interaction service,
 * the system routes the microphone key to it instead of Google Assistant.
 */
class HermesVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "HermesVoice"

        fun isSetAsDefault(context: Context): Boolean {
            return Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service"
            )?.startsWith(context.packageName) == true
        }
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "HermesVoiceInteractionService ready")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HermesVoiceInteractionService created")
    }
}
