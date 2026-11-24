package com.hermesagent.hermes_android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

/**
 * Notification service helper for Android Auto.
 * Creates a persistent "start new conversation" entry and per-session
 * conversation notifications with voice reply actions.
 *
 * Keeps the most recent connection JSON in SharedPreferences so receivers
 * can start a foreground service and forward replies even when the app
 * process is not running.
 */
object HermesAutoNotifications {

    private const val CHANNEL_ID = "hermes_auto_messaging"
    private const val NEW_CHAT_NOTIFICATION_ID = 1001
    private const val PREFS_NAME = "hermes_auto"
    private const val PREF_LAST_CONNECTION_JSON = "last_connection_json"

    const val ACTION_VOICE_REPLY = "com.hermesagent.hermes_android.VOICE_REPLY"
    const val ACTION_NEW_CHAT = "com.hermesagent.hermes_android.NEW_CHAT"
    const val ACTION_MARK_AS_READ = "com.hermesagent.hermes_android.MARK_AS_READ"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_CONNECTION_JSON = "connection_json"
    const val KEY_VOICE_REPLY = "voice_reply"

    const val NEW_SESSION_SENTINEL = "__new__"

    private val sessionNotificationIds = mutableMapOf<String, Int>()
    private var nextSessionNotificationId = 2000

    private fun getSessionNotificationId(sessionId: String): Int {
        return sessionNotificationIds.getOrPut(sessionId) {
            nextSessionNotificationId++
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermes Agent",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Android Auto conversations and new chat entry"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun rememberConnectionJson(context: Context, connectionJson: String) {
        if (connectionJson.isEmpty()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_CONNECTION_JSON, connectionJson)
            .apply()
    }

    internal fun getConnectionJson(context: Context, connectionJson: String?): String {
        if (!connectionJson.isNullOrEmpty()) return connectionJson
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_CONNECTION_JSON, "") ?: ""
    }

    private fun showNotification(context: Context, connectionJson: String, force: Boolean) {
        createChannel(context)

        val tapIntent = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_NEW_CHAT
                putExtra("new_chat", true)
                putExtra(EXTRA_CONNECTION_JSON, connectionJson)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VOICE_REPLY
            putExtra(EXTRA_SESSION_ID, NEW_SESSION_SENTINEL)
            putExtra(EXTRA_CONNECTION_JSON, connectionJson)
        }
        val replyPendingIntent = PendingIntent.getActivity(
            context,
            4,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_VOICE_REPLY)
            .setLabel(context.getString(R.string.voice_reply_label))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            context.getString(R.string.reply_action_label),
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val user = Person.Builder()
            .setName(context.getString(R.string.user_name))
            .setKey("user")
            .build()

        val hermes = Person.Builder()
            .setName(context.getString(R.string.new_chat_title))
            .setKey("hermes")
            .setImportant(true)
            .build()

        val style = NotificationCompat.MessagingStyle(user)
            .setConversationTitle(context.getString(R.string.new_chat_title))
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    context.getString(R.string.new_chat_body_short),
                    System.currentTimeMillis(),
                    hermes,
                ),
            )
            .setGroupConversation(false)

        // Android Auto specific unread conversation so the car shows it as a message thread.
        val unreadConversation = NotificationCompat.CarExtender.UnreadConversation.Builder(
            context.getString(R.string.new_chat_title),
        )
            .addMessage(context.getString(R.string.new_chat_body_short))
            .setReadPendingIntent(tapIntent)
            .setReplyAction(replyPendingIntent, remoteInput)
            .setLatestTimestamp(System.currentTimeMillis())
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(context.getString(R.string.new_chat_title))
            .setContentText(context.getString(R.string.new_chat_body_short))
            .setStyle(style)
            .setContentIntent(tapIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSilent(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .extend(NotificationCompat.CarExtender().setUnreadConversation(unreadConversation))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NEW_CHAT_NOTIFICATION_ID, notification)
    }

    /**
     * Public entry used by Dart. Re-posts the entry so Android Auto surfaces it
     * and remembers the connection so the service can recover after app death.
     */
    fun showNewChatEntry(context: Context, connectionJson: String) {
        rememberConnectionJson(context, connectionJson)
        showNotification(context, connectionJson, force = true)
    }

    /**
     * Internal re-post when the receiver detects the app becoming foreground.
     */
    fun refreshNewChatEntry(context: Context, connectionJson: String) {
        rememberConnectionJson(context, connectionJson)
        showNotification(context, connectionJson, force = true)
    }

    fun showAssistantMessage(
        context: Context,
        sessionId: String,
        title: String,
        body: String,
        connectionJson: String,
    ) {
        rememberConnectionJson(context, connectionJson)
        createChannel(context)

        val replyIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VOICE_REPLY
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_CONNECTION_JSON, connectionJson)
        }
        val replyPendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val remoteInput = RemoteInput.Builder(KEY_VOICE_REPLY)
            .setLabel(context.getString(R.string.voice_reply_label))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            context.getString(R.string.reply_action_label),
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val markAsReadIntent = Intent(context, MessageReadReceiver::class.java).apply {
            action = ACTION_MARK_AS_READ
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val markAsReadPendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId.hashCode(),
            markAsReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markAsReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.mark_as_read_label),
            markAsReadPendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val user = Person.Builder()
            .setName(context.getString(R.string.user_name))
            .setKey("user")
            .build()

        val assistant = Person.Builder()
            .setName(title)
            .setKey("assistant_$sessionId")
            .build()

        val message = NotificationCompat.MessagingStyle.Message(
            body,
            System.currentTimeMillis(),
            assistant,
        )

        val messagingStyle = NotificationCompat.MessagingStyle(user)
            .setConversationTitle(title)
            .addMessage(message)
            .setGroupConversation(false)

        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_VOICE_REPLY
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CONNECTION_JSON, connectionJson)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val assistantLargeIcon = context.packageManager
            .getApplicationIcon(context.packageName)
            .let { drawable ->
                if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap
                else null
            }

        val unreadConversation = NotificationCompat.CarExtender.UnreadConversation.Builder(
            title,
        )
            .addMessage(body)
            .setReadPendingIntent(markAsReadPendingIntent)
            .setReplyAction(replyPendingIntent, remoteInput)
            .setLatestTimestamp(System.currentTimeMillis())
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setLargeIcon(assistantLargeIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(messagingStyle)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setSilent(false)
            .setWhen(System.currentTimeMillis())
            .addAction(replyAction)
            .addAction(markAsReadAction)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .extend(NotificationCompat.CarExtender().setUnreadConversation(unreadConversation))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NEW_CHAT_NOTIFICATION_ID)
        val notificationId = getSessionNotificationId(sessionId)
        sessionNotificationIds[sessionId] = notificationId
        manager.notify(notificationId, notification)
    }

    fun cancelSessionNotification(context: Context, sessionId: String) {
        val id = sessionNotificationIds[sessionId] ?: return
        val manager = androidx.core.app.NotificationManagerCompat.from(context)
        manager.cancel(id)
    }
 }
