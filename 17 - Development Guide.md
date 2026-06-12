# Development Guide

## Kotlin Project Structure

```
app/
├── src/main/java/com/mine/app/
│   ├── MineApplication.kt
│   ├── di/
│   │   └── AppModule.kt (Hilt)
│   ├── data/
│   │   ├── local/
│   │   │   ├── db/
│   │   │   │   ├── MineDatabase.kt
│   │   │   │   ├── dao/
│   │   │   │   │   ├── NodeDao.kt
│   │   │   │   │   ├── EdgeDao.kt
│   │   │   │   │   ├── ChatDao.kt
│   │   │   │   │   ├── MemoryQueueDao.kt
│   │   │   │   │   └── ApiQueueDao.kt
│   │   │   │   └── entity/
│   │   │   │       ├── NodeEntity.kt
│   │   │   │       ├── EdgeEntity.kt
│   │   │   │       └── ...
│   │   │   ├── crypto/
│   │   │   │   └── KeystoreManager.kt
│   │   │   └── prefs/
│   │   │       └── EncryptedPrefs.kt
│   │   ├── remote/
│   │   │   ├── groq/
│   │   │   │   ├── GroqApi.kt
│   │   │   │   └── GroqWhisperRouter.kt
│   │   │   └── nim/
│   │   │       ├── NimApi.kt
│   │   │       ├── NimAcc1Repository.kt
│   │   │       └── NimAcc2Repository.kt
│   │   └── repository/
│   │       ├── GraphRepository.kt
│   │       ├── ChatRepository.kt
│   │       └── AmbientRepository.kt
│   ├── service/
│   │   ├── AmbientListeningService.kt
│   │   ├── AudioRecorderManager.kt
│   │   └── SherpaManager.kt
│   ├── ai/
│   │   ├── ToolExecutor.kt
│   │   ├── CuratorPromptBuilder.kt
│   │   ├── ChatPromptBuilder.kt
│   │   └── RateLimiter.kt
│   ├── worker/
│   │   ├── BatchProcessingWorker.kt
│   │   ├── PruningWorker.kt
│   │   └── BackupWorker.kt
│   ├── ui/
│   │   ├── theme/
│   │   ├── components/
│   │   │   ├── OrbInput.kt
│   │   │   ├── DayHeader.kt
│   │   │   ├── NudgeBubble.kt
│   │   │   └── MemoryCard.kt
│   │   ├── screens/
│   │   │   ├── DashboardScreen.kt
│   │   │   ├── ChatScreen.kt
│   │   │   ├── SettingsScreen.kt
│   │   │   └── ...
│   │   └── viewmodel/
│   │       ├── DashboardViewModel.kt
│   │       ├── ChatViewModel.kt
│   │       └── SettingsViewModel.kt
│   └── util/
│       ├── AudioUtils.kt
│       └── DateUtils.kt
├── src/main/jni/
└── build.gradle.kts
```


## Open Source Release Checklist

- [ ] Remove all API keys from codebase (use local.properties template)
- [ ] Add CONTRIBUTING.md
- [ ] Add LICENSE (MIT recommended)
- [ ] Scrub default souls.md and core.md of personal data
- [ ] Write README.md with screenshots, build instructions, architecture overview, privacy statement
- [ ] Create GitHub repository with issues and labels:
  - good first issue
  - help wanted
  - bug
  - feature
- [ ] Tag v1.0.0 release with APK
- [ ] Enable GitHub Discussions