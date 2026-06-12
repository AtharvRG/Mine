# User Flow: Onboarding

## Complete Flow Diagram

```mermaid
flowchart TD
  A[App Launch] --> B[Splash Screen<br/>1.5 seconds]
  B --> C[Legal & Terms<br/>Acceptance]
  C --> D{API Keys Present?}
  D -->|No| E[API Key Setup Screen]
  E --> F[Validate Keys]
  F -->|Invalid| E
  F -->|Valid| G{Voice Enrolled?}
  D -->|Yes| G

  G -->|No| H[Voice Enrollment Screen]
  H --> I[Sherpa-ONNX<br/>Read text aloud]
  I --> J[Capture x-vector]
  J --> K[Store in SQLite]
  K --> L{core.md & souls.md<br/>Present?}
  G -->|Yes| L

  L -->|No| M[Persona Setup Screen]
  M --> N[core.md input]
  M --> O[souls.md input]
  O --> P{Battery Optimization<br/>Disabled?}
  L -->|Yes| P

  P -->|No| Q[Battery Opt Screen<br/>CRITICAL STEP]
  Q --> R[System Intent to Settings]
  R --> S[User Returns]
  S --> P
  P -->|Yes| T[Dashboard Screen]
  T --> U[Welcome to Mine]
```

## Entry Points

- Fresh install: Full flow
- Update or reinstall: Skip if keys, voice, and persona exist in storage
- User can skip persona setup (uses defaults), but cannot skip Legal, API keys, Voice enrollment, or Battery Optimization.

## State Persistence

Each onboarding step writes to EncryptedSharedPreferences upon completion:
- `terms_accepted: boolean`
- `api_keys_configured: boolean`
- `voice_enrolled: boolean`
- `persona_configured: boolean`
- `battery_opt_disabled: boolean`

Dashboard checks all flags before showing main UI.