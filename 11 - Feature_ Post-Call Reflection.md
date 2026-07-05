# Feature: Post-Call Reflection

## User Story
I take a call. Mine was listening but politely paused the hardware mic to save battery. The call ends. Mine asks: "Capture what mattered?" I tap Record and dictate a summary.

## Detection
Handled directly inside the `AmbientListeningService` via `TelephonyManager`.

```kotlin
if (lastState == CALL_STATE_OFFHOOK && currentState == CALL_STATE_IDLE) {
notificationHelper.showCallReflectionNotification()
}
```

## Flow Diagram
1. Call ends.
2. High-priority notification triggers.
3. User taps "Record Summary".
4. App opens a Dialog. User records audio.
5. Audio is sent to NVIDIA Whisper.
6. Transcript is prepended with `[POST-CALL REFLECTION]` and sent to the Curator.
7. Graph is updated.

*(Note: The system explicitly guards against 0-byte audio arrays to prevent gRPC crashes if the user cancels the recording).*
