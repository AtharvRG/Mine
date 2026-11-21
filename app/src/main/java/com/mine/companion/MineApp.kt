package com.mine.companion

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mine.companion.store.LocalMine
import com.mine.companion.store.MineAction
import com.mine.companion.store.MineHandle
import com.mine.companion.store.MineStore
import com.mine.companion.store.ScreenId
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.components.StatusChip
import com.mine.companion.ui.components.Toast
import com.mine.companion.ui.components.WalkBanner
import com.mine.companion.ui.modals.MineModalHost
import com.mine.companion.ui.screens.ChatAndDayPage
import com.mine.companion.ui.screens.S01LaunchGate
import com.mine.companion.ui.screens.S02Welcome
import com.mine.companion.ui.screens.S03Consent
import com.mine.companion.ui.screens.S04SetupBasics
import com.mine.companion.ui.screens.S05YourDay
import com.mine.companion.ui.screens.S06Persona
import com.mine.companion.ui.screens.S07ConnectAI
import com.mine.companion.ui.screens.S08MemoryModel
import com.mine.companion.ui.screens.S09Permissions
import com.mine.companion.ui.screens.S10SoulSeed
import com.mine.companion.ui.screens.S11PrivacyOptions
import com.mine.companion.ui.screens.S12BackupNote
import com.mine.companion.ui.screens.S13Done
import com.mine.companion.ui.screens.S14Home
import com.mine.companion.ui.screens.S17DateDial
import com.mine.companion.ui.screens.S18TogglePanel
import com.mine.companion.ui.screens.S19SideNav
import com.mine.companion.ui.screens.S20AboutYouSoul
import com.mine.companion.ui.screens.S21Reminders
import com.mine.companion.ui.screens.S22MemoryHub
import com.mine.companion.ui.screens.S23ItemDetailScreen
import com.mine.companion.ui.screens.S24InboxTriage
import com.mine.companion.ui.screens.S25Search
import com.mine.companion.ui.screens.S26Settings
import com.mine.companion.ui.screens.S27Reliability
import com.mine.companion.ui.screens.S28Backup
import com.mine.companion.ui.screens.S29Graph
import com.mine.companion.ui.screens.S30Debug
import com.mine.companion.ui.screens.S31S40SystemScreen
import com.mine.companion.ui.screens.S41LivingNotes
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.screens.BootRecoveryScreen
import com.mine.companion.ui.screens.LockScreen
import com.mine.companion.ui.screens.ProviderImportPrompt
import kotlin.math.roundToInt

@Composable
fun MineApp(store: MineStore, onUnlockRequested: () -> Unit = {}) {
    val state = store.state.collectAsStateWithLifecycle()
    val handle = remember(store) { MineHandle(state, store::dispatch) }

    CompositionLocalProvider(LocalMine provides handle) {
        // The user's "Reduce motion" switch adds to (never overrides) the system setting.
        val systemReduced = LocalReducedMotion.current
        val userReduced = mineSelect { it.reducedMotion }
        CompositionLocalProvider(LocalReducedMotion provides (systemReduced || userReduced)) {
            val bootError = mineSelect { it.bootError }
            val locked = mineSelect { it.engine.isAppLocked }
            when {
                bootError != null -> BootRecoveryScreen(bootError)
                locked -> LockScreen(onUnlockRequested)
                else -> AppSurface(store)
            }
        }
    }
}

@Composable
private fun AppSurface(store: MineStore) {
    RootBackHandler()
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val dispatch = mineDispatch()

    Box(
        Modifier
            .fillMaxSize()
            .background(Mine.Canvas)
            .imePadding(),
    ) {
        ScreenRouter()
        MineModalHost()
        Box(Modifier.align(Alignment.TopCenter).padding(top = statusTop + 6.dp)) {
            StatusChip(onPress = {
                val chip = handleChipTarget(store.state.value.statusChip)
                dispatch(MineAction.Navigate(chip))
            })
        }
        WalkBanner(Modifier.align(Alignment.TopCenter))
        ProviderImportPrompt()
        Toast(Modifier.align(Alignment.BottomCenter))
    }
}

/** Where tapping the status chip goes: the screen that can fix the problem it names. */
private fun handleChipTarget(s: com.mine.companion.store.StatusChipState): ScreenId = when (s) {
    com.mine.companion.store.StatusChipState.no_ai, com.mine.companion.store.StatusChipState.rate_limited,
    com.mine.companion.store.StatusChipState.backup_model -> ScreenId.S07
    com.mine.companion.store.StatusChipState.offline -> ScreenId.S15
    else -> ScreenId.S27
}

/** Android back: closes the sheet first, then pops a screen; at the root it exits the app. */
@Composable
private fun RootBackHandler() {
    val dispatch = mineDispatch()
    val canGoBack = mineSelect { it.activeModal != null || it.currentScreen != ScreenId.S14 || it.screenHistory.size > 1 }
    BackHandler(enabled = canGoBack) { dispatch(MineAction.GoBack) }
}

@Composable
private fun ScreenRouter() {
    val screen = mineSelect { it.currentScreen }
    val direction = mineSelect { it.navDirection }
    val dir by rememberUpdatedState(direction)
    val density = LocalDensity.current
    val enterPx = with(density) { 28.dp.toPx() }
    val exitPx = with(density) { 16.dp.toPx() }

    AnimatedContent(
        targetState = screen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val d = dir
            val slide = MineMotion.sheet(IntOffset.VisibilityThreshold)
            (slideInHorizontally(slide) { (d * enterPx).roundToInt() } + fadeIn(MineMotion.fade())) togetherWith
                (slideOutHorizontally(slide) { (-d * exitPx).roundToInt() } + fadeOut(tween(120))) using
                SizeTransform(clip = false)
        },
        label = "screens",
    ) { s ->
        Box(Modifier.fillMaxSize().clipToBounds()) { ScreenContent(s) }
    }
}

@Composable
private fun ScreenContent(s: ScreenId) {
    when (s) {
        ScreenId.S01 -> S01LaunchGate()
        ScreenId.S02 -> S02Welcome()
        ScreenId.S03 -> S03Consent()
        ScreenId.S04 -> S04SetupBasics()
        ScreenId.S05 -> S05YourDay()
        ScreenId.S06 -> S06Persona()
        ScreenId.S07 -> S07ConnectAI()
        ScreenId.S08 -> S08MemoryModel()
        ScreenId.S09 -> S09Permissions()
        ScreenId.S10 -> S10SoulSeed()
        ScreenId.S11 -> S11PrivacyOptions()
        ScreenId.S12 -> S12BackupNote()
        ScreenId.S13 -> S13Done()
        ScreenId.S14 -> S14Home()
        ScreenId.S15 -> ChatAndDayPage(isPastDayPage = false)
        ScreenId.S16 -> ChatAndDayPage(isPastDayPage = true)
        ScreenId.S17 -> S17DateDial()
        ScreenId.S18 -> S18TogglePanel()
        ScreenId.S19 -> S19SideNav()
        ScreenId.S20 -> S20AboutYouSoul()
        ScreenId.S21 -> S21Reminders()
        ScreenId.S22 -> S22MemoryHub()
        ScreenId.S23 -> S23ItemDetailScreen()
        ScreenId.S24 -> S24InboxTriage()
        ScreenId.S25 -> S25Search()
        ScreenId.S26 -> S26Settings()
        ScreenId.S27 -> S27Reliability()
        ScreenId.S28 -> S28Backup()
        ScreenId.S29 -> S29Graph()
        ScreenId.S30 -> S30Debug()
        ScreenId.S41 -> S41LivingNotes()
        ScreenId.S42 -> com.mine.companion.ui.screens.S42VoiceEnrollment()
        ScreenId.S43 -> com.mine.companion.ui.screens.S43NotificationSources()
        ScreenId.S44 -> com.mine.companion.ui.screens.S44Models()
        ScreenId.S45 -> com.mine.companion.ui.screens.S45AboutYouDetails()
        else -> S31S40SystemScreen(s)
    }
}
