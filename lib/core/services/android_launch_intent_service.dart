import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Kind of launcher action received from the Android side.
enum LaunchActionKind { quickChat, voiceReply, newChat }

/// Parsed launcher action from an Android intent.
class LaunchAction {
  const LaunchAction({
    required this.kind,
    this.sessionId,
    this.message,
    this.connectionJson,
  });

  final LaunchActionKind kind;
  final String? sessionId;
  final String? message;
  final String? connectionJson;

  factory LaunchAction.fromJson(Map<dynamic, dynamic>? json) {
    final kindName = (json?['kind'] as String?) ?? 'quickChat';
    final kind = LaunchActionKind.values.firstWhere(
      (e) => e.name == kindName,
      orElse: () => LaunchActionKind.quickChat,
    );
    return LaunchAction(
      kind: kind,
      sessionId: json?['sessionId'] as String?,
      message: json?['message'] as String?,
      connectionJson: json?['connectionJson'] as String?,
    );
  }
}

/// Bridges Android launcher actions into the Flutter navigation lifecycle.
class AndroidLaunchIntentService {
  static const channelName = 'com.hermesagent.hermes_android/launch';

  final MethodChannel _channel;
  final ValueNotifier<LaunchAction?> pendingAction = ValueNotifier(null);
  final ValueNotifier<bool> pendingQuickChat = ValueNotifier(false);
  bool _initialized = false;

  AndroidLaunchIntentService({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(channelName) {
    pendingAction.addListener(() {
      final action = pendingAction.value;
      pendingQuickChat.value = action?.kind == LaunchActionKind.quickChat;
    });
  }

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    _channel.setMethodCallHandler(_handleMethodCall);
    try {
      final actionJson = await _channel.invokeMethod<String>('getInitialLaunchAction');
      _publish(actionJson);
    } on MissingPluginException {
      // Non-Android builds do not provide launcher shortcuts.
    } on PlatformException {
      // A launcher failure must never prevent the app from opening normally.
    }
  }

  Future<void> _handleMethodCall(MethodCall call) async {
    if (call.method != 'launchAction') return;
    _publish(call.arguments);
  }

  void _publish(dynamic actionJson) {
    final map = _asMap(actionJson);
    final kindName = (map?['kind'] as String?) ?? 'null';
    debugPrint('[HermesAuto] AndroidLaunchIntentService._publish raw=$actionJson kind=$kindName');
    pendingAction.value = map == null ? null : LaunchAction.fromJson(map);
  }

  Map<dynamic, dynamic>? _asMap(dynamic value) {
    if (value == null) return null;
    if (value is Map<dynamic, dynamic>) return value;
    if (value is String) {
      try {
        final decoded = jsonDecode(value);
        if (decoded is Map<dynamic, dynamic>) return decoded;
      } catch (_) {
        // Ignore malformed JSON and fall through to null.
      }
    }
    return null;
  }

  LaunchAction? takePendingAction() {
    final pending = pendingAction.value;
    if (pending != null) pendingAction.value = null;
    pendingQuickChat.value = false;
    return pending;
  }

  bool takePendingQuickChat() {
    final pending = pendingAction.value;
    if (pending != null && pending.kind == LaunchActionKind.quickChat) {
      pendingAction.value = null;
      pendingQuickChat.value = false;
      return true;
    }
    return false;
  }

  void dispose() {
    _channel.setMethodCallHandler(null);
    pendingAction.dispose();
    pendingQuickChat.dispose();
  }
}
