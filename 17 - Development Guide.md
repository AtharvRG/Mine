# Development Guide

## Kotlin Project Structure

```
app/
├── src/main/java/com/mine/app/
│ ├── MainActivity.kt
│ ├── MineApplication.kt
│ ├── ai/
│ │ ├── ChatAgent.kt
│ │ ├── ChronosAgent.kt
│ │ ├── CuratorAgent.kt
│ │ ├── ToolExecutor.kt
│ │ └── ToolSchemas.kt
│ ├── data/
│ │ ├── local/
│ │ │ ├── db/
│ │ │ │ ├── MineDatabase.kt
│ │ │ │ ├── dao/
│ │ │ │ │ ├── ChatDao.kt
│ │ │ │ │ ├── GraphDao.kt
│ │ │ │ │ └── QueueDao.kt
│ │ │ │ └── entity/
│ │ │ │ ├── GraphEntities.kt
│ │ │ │ └── SystemEntities.kt
│ │ └── remote/
│ │ ├── NimWhisperClient.kt
│ │ ├── GroqApi.kt
│ │ ├── NimApi.kt
│ │ └── dto/
│ │ ├── GroqDto.kt
│ │ └── NimDto.kt
│ ├── di/
│ │ ├── AppModule.kt
│ │ ├── DatabaseModule.kt
│ │ └── NetworkModule.kt
│ ├── service/
│ │ ├── AmbientListeningService.kt
│ │ ├── AudioRecorderManager.kt
│ │ ├── ServiceStateHolder.kt
│ │ └── SherpaManager.kt
│ ├── ui/
│ │ ├── ChatScreen.kt
│ │ ├── ChatViewModel.kt
│ │ ├── DebugViewModel.kt
│ │ └── MainScreen.kt
│ ├── util/
│ │ ├── MineUtils.kt
│ │ ├── NetworkMonitor.kt
│ │ ├── NotificationHelper.kt
│ │ ├── NudgeReplyReceiver.kt
│ │ ├── PreferencesManager.kt
│ │ ├── RateLimiter.kt
│ │ └── VectorUtils.kt
│ └── worker/
│ ├── ChronosWorker.kt
│ ├── PruningWorker.kt
│ └── QueueWorker.kt
└── build.gradle.kts
```

## Open Source Release Checklist
- [ ] Add CONTRIBUTING.md
- [ ] Add LICENSE (MIT recommended)
- [ ] Write README.md with screenshots, build instructions, architecture overview, privacy statement
- [ ] Create GitHub repository with issues and labels
- [ ] Tag v1.0.0 release with APK
