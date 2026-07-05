# Settings & Configuration

## API Keys (BuildConfig)
API keys are strictly stored in `local.properties` and injected via `BuildConfig` to prevent accidental Git commits.

## Persona Engine
Users select from 4 hardcoded presets, saved to `EncryptedSharedPreferences`:
1. **Visionary:** Inspiring, big-picture focused.
2. **Operator:** Strict, execution-focused, demands accountability.
3. **Strategist:** Analytical, logical, asks probing questions.
4. **Confidant:** Empathetic, supportive, warm.

## Contextual Location
An opt-in toggle that uses Android's `ConnectivityManager`.
- Wi-Fi = `[SPATIAL CONTEXT: Indoors/Home]`
- Cellular = `[SPATIAL CONTEXT: Outside/Transit]`
Injected into the AI prompt to provide spatial awareness.

## Voice Enrollment
A button that records 5 seconds of audio, processes it through Sherpa-ONNX's `SpeakerEmbeddingExtractor`, and saves the x-vector to the `SpeakerEmbeddingManager` so the app recognizes the user.
