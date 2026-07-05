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
H --> I[Sherpa-ONNX<br/>Record 5s Audio]
I --> J[Capture x-vector]
J --> K[Store in SpeakerManager]
K --> L{Persona Selected?}
G -->|Yes| L

L -->|No| M[Persona Setup Screen]
M --> N[Select Preset]
N --> O{Battery Optimization<br/>Disabled?}
L -->|Yes| O

O -->|No| P[Battery Opt Screen<br/>CRITICAL STEP]
P --> Q[System Intent to Settings]
Q --> R[User Returns]
R --> O
O -->|Yes| S[Main Tabbed UI]
```

## State Persistence
Each onboarding step writes to `EncryptedSharedPreferences` upon completion. The app checks all flags before showing the main UI.