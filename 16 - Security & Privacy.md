# Security & Privacy

## Threat Model

| Threat | Mitigation |
|--------|------------|
| Physical device theft | Database encrypted at rest |
| Cloud API key exposure | Keys not in code; injected via BuildConfig |
| Backup interception | Backups encrypted with user-provided password |
| Other apps reading data | App-private storage only |

## Encryption Layers

### 1. SQLite Database (SQLCipher)
- Algorithm: AES-256-CBC
- Key: Derived from Android Keystore (hardware-backed). The `MasterKey` never leaves the device.
- Implementation: `net.sqlcipher:android-database-sqlcipher`

### 2. Configuration Storage
- `EncryptedSharedPreferences` (AndroidX Security)
- Stores: Timestamps, flags, persona selection
- Master key in Android Keystore

## User Consent & Disclosure
- Onboarding explicitly states: "Mine records audio only when you toggle Ambient Listening ON."
- Persistent notification always visible when listening.
- No data leaves device except to user-configured APIs (NVIDIA).
- No telemetry. No crash reporting.

