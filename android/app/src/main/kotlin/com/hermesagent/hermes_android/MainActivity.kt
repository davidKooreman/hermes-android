package com.hermesagent.hermes_android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import androidx.core.app.RemoteInput
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : FlutterActivity() {
    private val shareChannelName = "com.hermesagent.hermes_android/share"
    private val launchChannelName = "com.hermesagent.hermes_android/launch"
    private val autoChannelName = "com.hermesagent.hermes_android/auto"
    private val keepAliveChannelName = "com.hermesagent.hermes_android/keepalive"
    private val recordingScreenOwners = mutableSetOf<String>()
    private val quickChatAction = "com.hermesagent.hermes_android.action.QUICK_CHAT"
    private val newChatExtra = "new_chat"
    private val voiceReplyExtra = "voice_reply"
    private val sessionIdExtra = "session_id"
    private val connectionJsonExtra = "connection_json"
    private val maxSharedItems = 10
    private val maxSharedBytes = 64L * 1024L * 1024L
    private var shareChannel: MethodChannel? = null
    private var launchChannel: MethodChannel? = null
    private var autoChannel: MethodChannel? = null
    private var initialShareIntent: Intent? = null
    private var initialLaunchAction: String? = null
    private var attachmentPdfPreview: AttachmentPdfPreview? = null
    private var apkUpdateInstaller: ApkUpdateInstaller? = null
    private var attachmentFileSaver: AttachmentFileSaver? = null
    private var handsFreeVoice: HandsFreeVoicePlugin? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        extractVoiceReplyFromRemoteInput(intent)
        initialShareIntent = intent.takeIf(::isShareIntent)
        initialLaunchAction = launchActionFor(intent)
        if (initialLaunchAction != null) clearPendingAutoReply()
        super.onCreate(savedInstanceState)
        runCatching { MobilePushBridge.captureTap(this, intent) }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Keep the engine reachable for Android Auto receivers (voice reply,
        // new chat) that may fire while the UI process is not in the foreground.
        HermesEngineHolder.attach(flutterEngine)

        autoChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, autoChannelName).apply {
            setMethodCallHandler { call, result ->
                val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                val connectionJson = (args["connectionJson"] as? String).orEmpty()
                when (call.method) {
                    "showNewChatEntry" -> {
                        HermesAutoNotifications.showNewChatEntry(this@MainActivity, connectionJson)
                        result.success(null)
                    }
                    "showAssistantMessage" -> {
                        HermesAutoNotifications.showAssistantMessage(
                            this@MainActivity,
                            (args["sessionId"] as? String).orEmpty(),
                            (args["title"] as? String).orEmpty(),
                            (args["body"] as? String).orEmpty(),
                            connectionJson,
                        )
                        result.success(null)
                    }
                    "startForegroundService" -> {
                        HermesForegroundService.start(this@MainActivity)
                        result.success(null)
                    }
                    "stopForegroundService" -> {
                        HermesForegroundService.stop(this@MainActivity)
                        result.success(null)
                    }
                    "syncCarAuthAndState" -> {
                        val url = (args["url"] as? String).orEmpty()
                        val apiKey = (args["apiKey"] as? String).orEmpty()
                        val cookie = (args["cookie"] as? String).orEmpty()
                        val lastMessage = (args["lastMessage"] as? String).orEmpty()
                        val sessionId = (args["sessionId"] as? String).orEmpty()
                        val sessionTitle = (args["sessionTitle"] as? String).orEmpty()
                        com.hermesagent.hermes_android.car.HermesBridge.updateFromFlutter(
                            url, apiKey, cookie, lastMessage
                        )
                        val prefs = getSharedPreferences("HermesCarAuthCache", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("gateway_url", url)
                            .putString("api_key", apiKey)
                            .putString("session_cookie", cookie)
                            .putString("last_message", lastMessage)
                            .putString("session_id", sessionId)
                            .putString("session_title", sessionTitle)
                            .apply()
                        com.hermesagent.hermes_android.car.HermesBridge.saveToPreferences(this@MainActivity)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        VoiceDraftMedia.install(this, flutterEngine.dartExecutor.binaryMessenger)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger,
            "com.hermesagent.hermes_android/recording_screen").setMethodCallHandler { call, result ->
            if (call.method != "setEnabled") {
                result.notImplemented()
            } else {
                val owner = call.argument<String>("owner")
                val enabled = call.argument<Boolean>("enabled")
                if (owner.isNullOrBlank() || enabled == null) {
                    result.error("invalid_arguments", "Recording owner and enabled are required", null)
                } else {
                    if (enabled) recordingScreenOwners.add(owner) else recordingScreenOwners.remove(owner)
                    if (recordingScreenOwners.isEmpty()) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    result.success(null)
                }
            }
        }

        attachmentPdfPreview?.close()
        attachmentPdfPreview = AttachmentPdfPreview(this, flutterEngine.dartExecutor.binaryMessenger)
        if (!BuildConfig.PLAY_BUILD) {
            apkUpdateInstaller?.close()
            apkUpdateInstaller = ApkUpdateInstaller(this, flutterEngine.dartExecutor.binaryMessenger)
        }
        attachmentFileSaver?.close()
        attachmentFileSaver = AttachmentFileSaver(this, flutterEngine.dartExecutor.binaryMessenger)
        BackgroundWorkPlatform.install(this, flutterEngine.dartExecutor.binaryMessenger)
        MobilePushPlugin.install(flutterEngine)

        handsFreeVoice = HandsFreeVoicePlugin.install(flutterEngine)

        shareChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, shareChannelName).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getInitialShare" -> {
                        val pending = initialShareIntent
                        initialShareIntent = null
                        processShareIntent(pending) { payload -> result.success(payload) }
                    }
                    else -> result.notImplemented()
                }
            }
        }
        launchChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, launchChannelName).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getInitialLaunchAction" -> {
                        val pending = initialLaunchAction
                        initialLaunchAction = null
                        result.success(pending)
                    }
                    else -> result.notImplemented()
                }
            }
        }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, keepAliveChannelName).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> result.success(startKeepAliveService())
                    "stop" -> {
                        stopService(Intent(this@MainActivity, ConnectionKeepAliveService::class.java))
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowViewport.refresh(window.decorView)
        handsFreeVoice?.setForeground(true)
    }

    override fun onPause() {
        handsFreeVoice?.setForeground(false)
        super.onPause()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        handsFreeVoice?.setForeground(false)
        handsFreeVoice = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        recordingScreenOwners.clear()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handsFreeVoice?.setForeground(false)
        attachmentPdfPreview?.close()
        attachmentPdfPreview = null
        apkUpdateInstaller?.close()
        apkUpdateInstaller = null
        attachmentFileSaver?.close()
        attachmentFileSaver = null
        // Finishing with Back removes the Activity without calling the
        // service's onTaskRemoved. Stop natively before the Flutter engine
        // tears down: Dart's async "stop" message can otherwise be lost,
        // leaving a foreground notification with no live turn to settle.
        // A configuration-change recreation is not a finish.
        if (isFinishing) {
            stopService(Intent(this, ConnectionKeepAliveService::class.java))
        }
        super.onDestroy()
    }

    /// Returns false when the OS refuses the foreground-service start (Android
    /// 12+ background-start limits on some OEMs). The transport's
    /// reconnect-on-resume loop remains the fallback in that case.
    private fun startKeepAliveService(): Boolean = try {
        val intent = Intent(this, ConnectionKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        true
    } catch (_: Exception) {
        false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        extractVoiceReplyFromRemoteInput(intent)
        WindowViewport.refresh(window.decorView)

        runCatching { MobilePushBridge.captureTap(this, intent) }
        launchActionFor(intent)?.let { action ->
            clearPendingAutoReply()
            launchChannel?.invokeMethod("launchAction", action)
            return
        }
        if (!isShareIntent(intent)) return
        processShareIntent(intent) { payload ->
            if (payload != null) shareChannel?.invokeMethod("sharePayload", payload)
        }
    }


    private fun launchActionFor(intent: Intent?): String? {
        if (intent == null) return null
        val extras = intent.extras
        if (intent.action == quickChatAction) {
            return buildLaunchActionJson("quickChat")
        }
        if (extras?.getBoolean(newChatExtra, false) == true) {
            val connectionJson = extras.getString(connectionJsonExtra).orEmpty()
            return buildLaunchActionJson("newChat", connectionJson = connectionJson)
        }
        val voiceReply = extras?.getString(voiceReplyExtra)
        val sessionId = extras?.getString(sessionIdExtra)
        if (!voiceReply.isNullOrBlank() && !sessionId.isNullOrBlank()) {
            val connectionJson = extras.getString(connectionJsonExtra).orEmpty()
            return buildLaunchActionJson(
                "voiceReply",
                sessionId = sessionId,
                message = voiceReply,
                connectionJson = connectionJson,
            )
        }
        // If the receiver stored a pending reply but the activity was not started
        // with the extras (process death, OEM launcher quirks), recover it here.
        val prefs = getSharedPreferences("hermes_auto", Context.MODE_PRIVATE)
        val pendingSession = prefs.getString("pending_voice_reply_session", null)
        val pendingMessage = prefs.getString("pending_voice_reply_message", null)
        val pendingConnection = prefs.getString("pending_voice_reply_connection", null)
        if (!pendingSession.isNullOrBlank() && !pendingMessage.isNullOrBlank()) {
            return buildLaunchActionJson(
                "voiceReply",
                sessionId = pendingSession,
                message = pendingMessage,
                connectionJson = pendingConnection,
            )
        }
        return null
    }

    private fun buildLaunchActionJson(
        kind: String,
        sessionId: String? = null,
        message: String? = null,
        connectionJson: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"").append(kind).append("\"")
        if (!sessionId.isNullOrBlank()) {
            sb.append(",\"sessionId\":\"").append(escapeJson(sessionId)).append("\"")
        }
        if (!message.isNullOrBlank()) {
            sb.append(",\"message\":\"").append(escapeJson(message)).append("\"")
        }
        if (!connectionJson.isNullOrBlank()) {
            sb.append(",\"connectionJson\":\"").append(escapeJson(connectionJson)).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun clearPendingAutoReply() {
        getSharedPreferences("hermes_auto", Context.MODE_PRIVATE)
            .edit()
            .remove("pending_voice_reply_session")
            .remove("pending_voice_reply_message")
            .remove("pending_voice_reply_connection")
            .apply()
    }

    private fun extractVoiceReplyFromRemoteInput(intent: Intent?) {
        if (intent == null) return
        if (intent.action == HermesAutoNotifications.ACTION_VOICE_REPLY) {
            val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
            val message = bundle.getCharSequence(HermesAutoNotifications.KEY_VOICE_REPLY)?.toString()
            if (!message.isNullOrBlank()) {
                intent.putExtra(voiceReplyExtra, message)
            }
        }
    }

    private fun isShareIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    private fun processShareIntent(intent: Intent?, callback: (Map<String, Any?>?) -> Unit) {
        if (!isShareIntent(intent)) {
            callback(null)
            return
        }
        Thread {
            val payload = try { extractSharePayload(intent!!) } catch (_: Exception) { null }
            runOnUiThread { callback(payload) }
        }.start()
    }

    private fun extractSharePayload(intent: Intent): Map<String, Any?>? {
        val text = extractSharedText(intent)
        val files = mutableListOf<Map<String, Any>>()
        var copiedBytes = 0L
        sharedUris(intent).take(maxSharedItems).forEachIndexed { index, uri ->
            val remaining = maxSharedBytes - copiedBytes
            if (remaining <= 0L) return@forEachIndexed
            copySharedUri(uri, index, intent.type, remaining)?.let { file ->
                files += file
                copiedBytes += file["byteLength"] as Long
            }
        }
        if (text == null && files.isEmpty()) return null
        return mapOf("text" to text, "files" to files)
    }

    private fun extractSharedText(intent: Intent): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        return when {
            text.isEmpty() -> subject.ifEmpty { null }
            subject.isEmpty() || text.startsWith(subject) -> text
            else -> "$subject\n\n$text"
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val streams = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
            else -> emptyList()
        }.toMutableList()
        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(streams::add)
            }
        }
        return streams.distinct()
    }

    private fun copySharedUri(
        uri: Uri,
        index: Int,
        fallbackType: String?,
        remainingBytes: Long,
    ): Map<String, Any>? {
        val mediaType = contentResolver.getType(uri)?.trim().orEmpty()
            .ifEmpty { fallbackType?.trim().orEmpty() }
            .ifEmpty { "application/octet-stream" }
        val displayName = queryDisplayName(uri)
            ?.let(::safeDisplayName)
            ?.takeIf(String::isNotEmpty)
            ?: "shared-${index + 1}"
        val directory = File(cacheDir, "shared_intake").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}-$displayName")
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            var total = 0L
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > remainingBytes) {
                            throw IllegalArgumentException("Shared payload exceeds 64 MiB")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (total <= 0L) {
                destination.delete()
                null
            } else {
                mapOf(
                    "path" to destination.absolutePath,
                    "name" to displayName,
                    "mediaType" to mediaType,
                    "byteLength" to total,
                )
            }
        } catch (_: Exception) {
            destination.delete()
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column < 0) null else cursor.getString(column)
        }
    } catch (_: Exception) {
        null
    }

    // The attachment saver's document picker result; everything else goes on
    // to Flutter's plugins as before.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (attachmentFileSaver?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun safeDisplayName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .take(160)
}
