package com.hermesagent.hermes_android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver for Android Auto “mark as read” actions on assistant replies.
 *
 * Android Auto requires a mark-as-read action on messaging notifications. This
 * receiver simply cancels the session notification; the actual read state lives
 * in the chat history.
 */
class MessageReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(HermesAutoNotifications.EXTRA_SESSION_ID) ?: return
        Log.d("HermesAuto", "MessageReadReceiver sessionId=$sessionId")
        HermesAutoNotifications.cancelSessionNotification(context, sessionId)
    }
}
