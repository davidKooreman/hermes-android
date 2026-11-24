package com.hermesagent.hermes_android

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory service that creates a [HermesVoiceInteractionSession] for each
 * microphone-key invocation.
 */
class HermesVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return HermesVoiceInteractionSession(this)
    }
}
