package com.hermesagent.hermes_android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that injects recognized speech text into the currently
 * focused text field.
 *
 * It receives the text via a local broadcast from [HermesVoiceInteractionSession]
 * and uses [AccessibilityNodeInfo.ACTION_SET_TEXT] when the focused node is
 * editable.
 */
class HermesAccessibilityService : AccessibilityService() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_INJECT_TEXT) return
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            injectText(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        registerReceiver(receiver, IntentFilter(ACTION_INJECT_TEXT), RECEIVER_NOT_EXPORTED)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only need the focused node at injection time; events are logged for
        // debugging if needed.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiverSafe(receiver)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterReceiverSafe(receiver)
        super.onDestroy()
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun injectText(text: String) {
        val node = findFocusedEditableNode(rootInActiveWindow)
        if (node == null) {
            Log.w(TAG, "No focused editable node found")
            return
        }

        val existing = node.text?.toString() ?: ""
        val separator = if (existing.isNotBlank() && !existing.endsWith(" ")) " " else ""
        val newText = "$existing$separator$text"

        val args = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            } else {
                @Suppress("DEPRECATION")
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Injected text success=$success text='$text'")
        node.recycle()
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isFocused && root.isEditable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFocusedEditableNode(child)
            if (found != null) {
                root.recycle()
                return found
            }
            child.recycle()
        }
        root.recycle()
        return null
    }

    companion object {
        const val ACTION_INJECT_TEXT = "com.hermesagent.hermes_android.action.INJECT_TEXT"
        const val EXTRA_TEXT = "extra_text"
        private const val TAG = "HermesA11y"
    }
}
