package com.mine.companion.store

/**
 * The state before the first storage read lands. Everything is empty and `isHydrating` is true,
 * so screens show their geometry-matched skeletons instead of invented content.
 */
object MineSeed {

    val initial: MineUiState = MineUiState(
        currentScreen = ScreenId.S14,
        screenHistory = listOf(ScreenId.S14),
        activeModal = null,
        onboardingFastPath = true,
        consentAccepted = false,
        userName = "",
        selectedLanguages = listOf("English"),
        dayStart = "07:00",
        dayEnd = "22:00",
        persona = PersonaType.companion,
        statusChip = StatusChipState.normal,
        contextState = ContextState.AtHomeStill,
        breakMinutesLeft = null,
        procrastinationMode = ProcrastinationMode.gentle,
        nudgesPausedUntil = null,
        reducedMotion = false,
        devMode = false,

        reasoningEnabled = true,
        contextSensing5GGuard = false,
        callNudgeEnabled = false,
        voiceRepliesEnabled = false,
        ambientListeningPaused = true,
        smartWebSearchEnabled = false,
        handsFreeActive = false,
        handsFreePhase = HandsFreePhase.listening,
        soulAutoMode = false,

        selectedItemId = "",
        selectedNoteId = "",
        selectedPastDate = "",
        selectedHeatmapDate = "",
        memoryActiveTab = MemoryTab.Items,

        items = emptyList(),
        recentlyClosedIds = emptyList(),
        livingNotes = emptyList(),
        soulFacts = emptyList(),
        reminders = emptyList(),
        todayMessages = emptyList(),
        pastDayMessages = emptyMap(),
        agentWakeups = emptyList(),
        inboxItems = emptyList(),

        toastMessage = null,
        toastUndo = null,
        walkNotificationVisible = false,
        navDirection = 1,
        isHydrating = true,
        reliability = ReliabilityScore.healthy,
        handsFreeState = HandsFreeState.IDLE,
        ambientState = AmbientState.Off,
        queuedChatCount = 0,
        providerCooldownUntil = null,
        recentlyCancelledReminders = emptyList(),
    )
}
