package com.mine.companion.store

/**
 * Pure UI reducer. It owns navigation, sheets, toasts and *optimistic* touches (a task ticks off
 * the instant it is tapped). It never invents content: every real change is carried out by the
 * engine through [MineStore]'s effects, and the engine's next state replaces these guesses.
 */
fun mineReducer(state: MineUiState, action: MineAction, now: Long = System.currentTimeMillis()): MineUiState =
    when (action) {
        is MineAction.Navigate -> {
            if (action.screen == state.currentScreen && state.activeModal == null) state
            else state.copy(
                currentScreen = action.screen,
                screenHistory = (state.screenHistory + action.screen).takeLast(12),
                activeModal = null,
                navDirection = 1,
            )
        }

        is MineAction.SelectPastDate -> state.copy(selectedPastDate = action.date)

        MineAction.GoBack -> when {
            state.activeModal != null -> state.copy(activeModal = null)
            state.screenHistory.size <= 1 -> state.copy(currentScreen = ScreenId.S14, screenHistory = listOf(ScreenId.S14), navDirection = -1)
            else -> {
                val nextHistory = state.screenHistory.dropLast(1)
                state.copy(currentScreen = nextHistory.last(), screenHistory = nextHistory, navDirection = -1)
            }
        }

        is MineAction.OpenModal -> state.copy(
            activeModal = action.modal,
            selectedItemId = action.itemId ?: state.selectedItemId,
            selectedNoteId = action.noteId ?: state.selectedNoteId,
            selectedHeatmapDate = action.dateLabel ?: state.selectedHeatmapDate,
        )

        MineAction.CloseModal -> state.copy(activeModal = null)

        is MineAction.SetOnboardingFastPath -> state.copy(onboardingFastPath = action.fast)
        is MineAction.SetConsent -> state.copy(consentAccepted = action.accepted)
        is MineAction.SetUserName -> state.copy(userName = action.name)

        is MineAction.ToggleLanguage -> {
            val exists = action.lang in state.selectedLanguages
            val next = if (exists) state.selectedLanguages - action.lang else state.selectedLanguages + action.lang
            state.copy(selectedLanguages = next.ifEmpty { listOf("English") })
        }

        is MineAction.SetDayBounds -> state.copy(dayStart = action.start, dayEnd = action.end)

        is MineAction.SetPersona -> state.copy(
            persona = action.persona,
            toastMessage = "Mine will sound like ${personaArticle(action.persona)} now",
            toastUndo = null,
        )

        is MineAction.SetStatusChip -> state.copy(statusChip = action.status)
        is MineAction.SetContextState -> state.copy(contextState = action.context)

        // Effects arm the real timer; this only shows it at once.
        is MineAction.StartBreak -> state.copy(breakMinutesLeft = action.minutes)
        MineAction.EndBreak -> state.copy(breakMinutesLeft = null)

        is MineAction.SetProcrastinationMode -> state.copy(
            procrastinationMode = action.mode,
            toastMessage = if (action.mode == ProcrastinationMode.off) "Focus nudges off" else "Focus nudges: ${action.mode.name}",
            toastUndo = null,
        )

        is MineAction.PauseNudges -> state.copy(nudgesPausedUntil = action.until)

        is MineAction.ToggleSetting -> state.withSetting(action.key, !state.setting(action.key))

        is MineAction.CompleteItem -> state.copy(
            items = state.items.map { if (it.id == action.itemId) it.copy(status = ItemStatus.done) else it },
            recentlyClosedIds = (listOf(action.itemId) + state.recentlyClosedIds.filter { it != action.itemId }).take(20),
        )

        is MineAction.UndoCompleteItem -> state.copy(
            items = state.items.map { if (it.id == action.itemId) it.copy(status = ItemStatus.todo) else it },
            recentlyClosedIds = state.recentlyClosedIds.filter { it != action.itemId },
        )

        is MineAction.UpdateItemStatus -> state.copy(
            items = if (action.status == ItemStatus.trashed || action.status == ItemStatus.archived)
                state.items.filter { it.id != action.itemId }
            else state.items.map { if (it.id == action.itemId) it.copy(status = action.status) else it },
        )

        is MineAction.ToggleItemLocalOnly -> state.copy(
            items = state.items.map { if (it.id == action.itemId) it.copy(localOnly = !(it.localOnly ?: false)) else it },
        )

        is MineAction.TogglePinNote -> {
            val target = state.livingNotes.find { it.id == action.noteId }
            when {
                target == null -> state
                !target.pinned && state.livingNotes.count { it.pinned } >= 3 ->
                    state.copy(toastMessage = "You can pin up to 3 notes. Unpin one first.", toastUndo = null)
                else -> state.copy(livingNotes = state.livingNotes.map { if (it.id == action.noteId) it.copy(pinned = !it.pinned) else it })
            }
        }

        is MineAction.ToggleNoteAccessMode -> {
            val nextNotes = state.livingNotes.map { n ->
                if (n.id != action.noteId) n
                else n.copy(accessMode = if (n.accessMode == NoteAccessMode.read_only) NoteAccessMode.read_write else NoteAccessMode.read_only)
            }
            state.copy(livingNotes = nextNotes)
        }

        is MineAction.ResolveAmbiguity -> state.copy(
            todayMessages = state.todayMessages.map { msg ->
                val prompt = msg.ambiguityPrompt
                if (msg.id != action.messageId || prompt == null) msg
                else msg.copy(ambiguityPrompt = prompt.copy(resolvedOptionId = action.optionId))
            },
        )

        is MineAction.UndoReceipt -> state.copy(
            todayMessages = state.todayMessages.map { msg ->
                if (msg.id != action.messageId) msg
                else msg.copy(receipts = (msg.receipts ?: emptyList()).map { r -> if (r.id == action.receiptId) r.copy(undone = true) else r })
            },
        )

        // Optimistic bubble: the engine stores the real one and replaces this within a frame or two.
        is MineAction.SendChatMessage -> {
            val text = action.text.trim()
            if (text.isEmpty()) state
            else state.copy(
                todayMessages = state.todayMessages + ChatMessage(
                    id = "local-$now", sender = Sender.user, text = text, timestamp = "", tsUtc = now,
                ),
                chatBusy = true,
                chatError = null,
            )
        }

        MineAction.TriggerProcrastinationGate -> state
        is MineAction.TriggerWalkNotification -> state.copy(walkNotificationVisible = action.visible)
        is MineAction.SetMemoryTab -> state.copy(memoryActiveTab = action.tab)

        is MineAction.AddReminder -> state.copy(activeModal = null)

        is MineAction.CancelReminder -> {
            val removed = state.reminders.find { it.id == action.reminderId }
            state.copy(
                reminders = state.reminders.filter { it.id != action.reminderId },
                recentlyCancelledReminders = if (removed != null) (listOf(removed) + state.recentlyCancelledReminders).take(5) else state.recentlyCancelledReminders,
            )
        }

        is MineAction.ResolveInboxItem -> state.copy(inboxItems = state.inboxItems.filter { it.id != action.inboxId })

        is MineAction.ToggleSoulLocalOnly -> state.copy(
            soulFacts = state.soulFacts.map { if (it.id == action.soulId) it.copy(localOnly = !it.localOnly) else it },
        )

        is MineAction.ShowToast -> state.copy(toastMessage = action.message, toastUndo = action.undo)
        MineAction.DismissToast -> state.copy(toastMessage = null, toastUndo = null)
        // The store runs the undo action itself (it may be an engine call); here the toast just closes.
        MineAction.RunToastUndo -> state.copy(toastMessage = null, toastUndo = null)

        MineAction.Hydrated -> state.copy(isHydrating = false)

        is MineAction.SetReliability -> state.copy(reliability = action.score)
        is MineAction.SetProviderCooldown -> state.copy(providerCooldownUntil = action.until)

        MineAction.DismissChatError -> state.copy(chatError = null)
        MineAction.RetryFailedMessage -> state.copy(chatError = null, chatBusy = true)

        // Pure effect actions: no immediate UI change beyond what the engine will report.
        is MineAction.QuickAddOfflineItem, is MineAction.RescheduleItem, is MineAction.UndoRescheduleItem,
        is MineAction.AddNoteBullet, is MineAction.CreateLivingNote, is MineAction.SnoozeReminder,
        is MineAction.RestoreReminder, is MineAction.Engine, MineAction.StopReply, is MineAction.SpeakMessage,
        MineAction.StartVoice, MineAction.StopVoice, MineAction.PauseVoice, MineAction.ResumeVoice,
        is MineAction.Undo, is MineAction.SetNotificationReading, MineAction.OpenNotificationAccess,
        MineAction.ClearHeardFeed, MineAction.TestNotificationVoice, is MineAction.SaveHeardSuggestion,
        is MineAction.RequestPermission, MineAction.RefreshPlatform, is MineAction.ExportBackup,
        is MineAction.ImportBackup, is MineAction.ExportMarkdown, is MineAction.SetAutoBackupFolder,
        is MineAction.SetAmbientListening, MineAction.FinishOnboarding, is MineAction.SetDayWindow -> state
    }

private fun personaArticle(p: PersonaType): String = when (p) {
    PersonaType.companion -> "a companion"
    PersonaType.cofounder -> "a cofounder"
    PersonaType.coach -> "a coach"
    PersonaType.chill -> "a chill friend"
    PersonaType.minimal -> "a minimalist"
}
