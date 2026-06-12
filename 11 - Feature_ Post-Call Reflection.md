# Feature: Post-Call Reflection

## User Story

I take a call. Mine was listening but politely paused. The call ends. Mine asks: "How was the call? Capture it?" I tap Record and dictate a 30-second summary. Mine extracts the commitments and files them.

## Detection

```kotlin
class CallStateListener : PhoneStateListener() {
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        if (lastState == CALL_STATE_OFFHOOK && state == CALL_STATE_IDLE) {
            triggerPostCallNotification(phoneNumber)
        }
        lastState = state
    }
}
```

## Flow Diagram

```mermaid
flowchart TD
    A[Call ends<br/>OFFHOOK to IDLE] --> B[Trigger Notification]
    B --> C{User action}
    C -->|Ignore| D[Dismiss<br/>No data stored]
    C -->|Record| E[Open Quick Capture<br/>Bottom Sheet]
    E --> F[60s Voice Note<br/>Mic hot immediately]
    F --> G[User taps Done<br/>or auto-stop at 60s]
    G --> H[Sherpa VAD + SpeakerID]
    H --> I[Groq Whisper]
    I --> J[Curator with enhanced prompt<br/>source: call_reflection]
    J --> K[Tool Executor writes<br/>to Graph]
    K --> L[Summary notification<br/>Saved N memories]
```

## Quick Capture Sheet

- Bottom sheet UI
- 60-second countdown timer
- Done button and Cancel (swipe down)
- Audio processed identically to ambient but tagged differently

## Graph Tagging

- `source`: `call_reflection`
- `people`: Extracted caller name if known from contacts or prior nodes
- `branch`: Inferred from content (example: `work/meetings`, `personal/family`)

## Notification UX

- Non-sticky heads-up notification
- Title: "Call ended"
- Text: "Capture what mattered?"
- Actions: Record, Ignore
- Auto-dismiss after 5 minutes if untouched