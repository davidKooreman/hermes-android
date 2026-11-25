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
        val sessionId = intent.getStringExtra("session_id") ?: return
        Log.d("HermesAuto", "MessageReadReceiver sessionId=$sessionId")
        val id = (sessionId.hashCode() and 0x0fffffff) or 0x10000000
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id)
    }
}
