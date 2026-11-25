# Ares fork — technical notes

Notes on a local **Ares** fork, written so the upstream maintainer can see what changed and why.

## What this fork is

- Upstream base: **Ares** (`rusty4444/ares`), currently **4.0.16** (`4.0.16+3072`).
- Fork repo: `davidKooreman/hermes-android`.
- Package: `com.hermesagent.hermes_android`.
- Signing: our own release key, `apksigner` (APK Signature Scheme v2).
- Build: `flutter build apk --split-per-abi --release` plus a universal `flutter build apk --release`.

## Why we fork

The Android Auto experience we need is the one Teams/WhatsApp give: a car reply is sent **without opening the phone UI**, the answer comes back as a car notification (read aloud), and the notification offers another Reply action. Stock behaviour differed in two places:

1. A car reply for the **"new chat" entry** or a **missing session** was dropped; upstream only handled existing sessions.
2. We wanted the reply to land in the **NextGen** interface, not the Classic chat screen.

## What we changed on top of 4.0.16

Everything below is additive; upstream's Auto architecture stays intact.

| Area | Change | File |
|---|---|---|
| Car reply for a missing/new session | New optional `onNewChatReply` callback on `AutoMessagingService`; called when `_lookup` returns no session and the action is a `reply`. | `lib/core/services/auto_messaging_service.dart` |
| NextGen hand-off | `HermesAppState` wires `onNewChatReply` to `pendingNextGenVoiceReply`. | `lib/main.dart` |
| NextGen chat | `MainPage` listens for `pendingNextGenVoiceReply`, opens a fresh chat (`_newChat()`) and sends the text with `NgChatController.send(text)`. | `lib/nextgen/ui/main_page.dart` |
| Shared notifier | `pendingNextGenVoiceReply` (`ValueNotifier<String?>`). | `lib/nextgen/nextgen_preferences.dart` |
| Classic fallback | `ChatScreen` accepts `initialVoiceReply` and sends it with `speakResponse: true` once the chat is initialised. | `lib/core/screens/chat_screen.dart` |

Upstream's own pieces that we rely on unchanged: `AutoMessagingPlugin.kt`, `HermesAutoNotifications.kt`, `AutoMessageReceiver`, `MessageReadReceiver`, `automotive_app_desc.xml`, the `com.google.android.gms.car.application` metadata and the `MessagingStyle` / `CarExtender.UnreadConversation` notification shape.

## How the round trip works (4.0.16+3070028)

```
Assistant answer
  └─ HermesAutoNotifications.post(...)                 (Kotlin)
       MessagingStyle + CarExtender.UnreadConversation
       Reply action with RemoteInput  ──► AutoMessageReceiver (token)
                                              │
User speaks in the car ──────────────────────┘
                                              ▼
AutoMessageReceiver → MobilePushStore (sealed, expiring token)
                                              │
AutoMessagingPlugin.changed() ──► Dart AutoMessagingService.claim()
                                              │
                    ┌─────────────────────────┴─────────────────────────┐
                    │ existing session                                  │ missing / __new__
                    ▼                                                   ▼
        NormalGatewayApplicationSession.submitPrepared()      onNewChatReply(connection, text)
                                                                    │
                                                          pendingNextGenVoiceReply
                                                                    ▼
                                                    MainPage → _newChat() → NgChatController.send()
```

The native side is token-based on purpose: a reply token is sealed, expires and is single-use, so an uncertain delivery is never silently resent.

## Build and deploy

```bash
git clone https://github.com/davidKooreman/hermes-android
cd hermes-android
git checkout local/4.0.16-3070027   # fork branch
flutter pub get
flutter analyze lib/
flutter build apk --split-per-abi --release
flutter build apk --release
```

- Signing needs `android/release-key.jks` plus `key.properties`; without them the Gradle build emits an unsigned APK.
- The arm64 split targets a modern phone (`versionCode = base * 10 + 2`); the universal APK is for mixed fleets and manual installs.
- Verify with `aapt dump badging <apk> | grep ^package:` and `apksigner verify --print-certs <apk>`.

## Android Auto integration we had to restore on 4.0.16

Upstream 4.0.16 keeps only the token-based conversation path. To get a reply from a fresh session working in the car we re-added the notification/reply stack on top of it:

| Piece | What it does |
|---|---|
| `AutoMessagingPlugin` + `showAssistantMessage` | Extra channel method that posts an assistant answer without the ticket round-trip. |
| `HermesAutoNotifications.postAssistant()` | `MessagingStyle` + `CarExtender.UnreadConversation`, reply action opens `MainActivity` with `session_id` / `voice_reply` / `connection_json`. |
| `MainActivity.launchActionFor()` | Rebuilds a JSON launch action (`quickChat`, `newChat`, `voiceReply`). |
| `MainActivity.extractVoiceReplyFromRemoteInput()` | Reads the RemoteInput bundle — see gotcha 7. |
| `MessageReadReceiver` | Cancels the notification for the mark-as-read action. |
| `HermesAppState._sendVoiceReplyHeadless()` | Sends the reply over `DesktopGatewayClient`, posts the answer, speaks it with `flutter_tts`. |

## Gotchas we hit

1. **A listener in `HomeScreenState` is invisible in NextGen.** `HomeScreen` is never mounted when the NextGen home is active, so any launch/voice handling placed there silently does nothing. Route it at `HermesAppState` (app root) or in the service layer.
2. **`RECORD_AUDIO` is not granted automatically after a reinstall.** Without the runtime grant, dictation and TTS fail while the UI looks normal. Re-grant with `adb shell pm grant <package> android.permission.RECORD_AUDIO` (and `POST_NOTIFICATIONS`).
3. **A GitHub release APK cannot be installed over a locally signed build.** Different signing certificates force an uninstall, which wipes app data and saved connection keys. Always rebuild and sign locally.
4. **`versionCode` must stay above the installed one.** Upstream's `4.0.16+3072` is far below our fork code; a plain `adb install -r` of the upstream APK is rejected as a downgrade.
5. **`--split-per-abi` does not emit a universal APK.** Run `flutter build apk --release` separately when a fat APK is needed.
6. **`debugPrint` markers are dev-only.** Strip exploratory logging before shipping a build.
7. **RemoteInput reply text is not an intent extra.** Android delivers it only via `RemoteInput.getResultsFromIntent(intent)`; reading `getStringExtra("voice_reply")` silently yields nothing. The `PendingIntent` must also be `FLAG_MUTABLE`.
8. **Never stop a just-started foreground service.** A `startForegroundService()`/`stopService()` pair milliseconds apart makes Android tear the service down mid-promotion and throw `ForegroundServiceDidNotStartInTimeException`, killing the process. Stamp the start time *before* the native call and debounce the stop (~700 ms).

## Open questions for upstream

- Should the "new chat / missing session" reply path live upstream? Our `onNewChatReply` hook is small and might be a good fit for `AutoMessagingService` itself.
- Is there an upstream preference for how a car reply should open the NextGen surface (a dedicated callback vs. a route request)?
- We keep the Classic `ChatScreen` for the Classic interface and route only car replies into NextGen. A shared route-request abstraction would avoid the second notifier.

## Links

- Fork: `https://github.com/davidKooreman/hermes-android`
- Branch: `local/4.0.16-3070027`
- Pull requests opened against upstream: #355 (4.0.0 base), #356 (4.0.12 base), #378 (4.0.16 base)
