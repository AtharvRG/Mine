// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anchor.core.ui.UiAction
import com.mine.companion.MineApp as MineUi
import com.mine.companion.store.MineAction
import com.mine.companion.store.MineStore
import com.mine.companion.ui.theme.DarkPalette
import com.mine.companion.ui.theme.LightPalette
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hide the status bar as well. Set false to keep the clock and battery visible. */
private const val HIDE_STATUS_BAR = true

/**
 * The single Activity. It hosts the companion UI and does the few things only an Activity can:
 * permission prompts, the biometric unlock, FLAG_SECURE, and routing launcher/share/deep-link
 * intents into the store.
 */
class MainActivity : ComponentActivity() {

    private val store: MineStore by viewModels()
    private var lastPauseAt = 0L
    private var pendingPermission: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.let { store.onPermissionResult(it, granted) }
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Mine.palette = if (dark) DarkPalette else LightPalette
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        keepSafeArea(findViewById(android.R.id.content))

        setContent {
            MineTheme(dark) {
                MineUi(store, onUnlockRequested = ::showBiometricPrompt)
            }
        }
        hideSystemBars()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    store.permissionRequests.collect { p ->
                        if (pendingPermission == null) {
                            pendingPermission = p
                            permissionLauncher.launch(p)
                        }
                    }
                }
                launch {
                    store.state.map { it.engine.blockScreenshots }.distinctUntilChanged().collect { block ->
                        if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        lastPauseAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        store.onResume()
        if (lastPauseAt > 0L) {
            val awaySec = (System.currentTimeMillis() - lastPauseAt) / 1000L
            lastPauseAt = 0L
            val e = store.state.value.engine
            if (e.appLockEnabled && !e.isAppLocked && awaySec >= e.relockTimeoutSec) store.dispatch(MineAction.Engine(UiAction.LockApp))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and permission prompts can bring the bars back.
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------------------------------
    // Intents
    // ---------------------------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        when {
            // mine://provider?... — provider setup from a QR code or link.
            data != null && data.scheme.equals("mine", true) &&
                (data.host.equals("provider", true) || data.authority.equals("provider", true)) ->
                store.dispatch(MineAction.Engine(UiAction.ImportProviderConfig(data.toString())))

            // "Share to Mine".
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) store.onExternalText(text)
            }

            // Notification "Resume listening".
            intent.action == com.anchor.mine.ambient.AmbientForegroundService.ACTION_START ->
                store.dispatch(MineAction.SetAmbientListening(true))

            // Post-call "Leave a voice note".
            intent.action == com.anchor.mine.nudge.CallNudgeNotifications.ACTION_VOICE_NOTE -> {
                val callEventId = intent.getStringExtra(com.anchor.mine.nudge.CallNudgeNotifications.EXTRA_CALL_EVENT_ID)
                if (callEventId != null) {
                    com.anchor.mine.nudge.CallNudgeNotifications.dismiss(this, callEventId.hashCode())
                    val app = application as? MineApp
                    if (app != null) lifecycleScope.launch {
                        runCatching { app.sharedStorage.await().updateCallEventState(callEventId, com.anchor.core.nudge.CallNudgeState.ANSWERED) }
                    }
                }
                store.onFocusComposer()
            }

            // Launcher shortcut ("Write a note to Mine"): open the chat with the keyboard up.
            intent.action == Intent.ACTION_VIEW && data?.scheme != "mine" -> store.onFocusComposer()
        }
    }

    // ---------------------------------------------------------------------------------------
    // App lock
    // ---------------------------------------------------------------------------------------

    private fun showBiometricPrompt() {
        try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock Mine")
                .setDescription("Your chats and memories are locked")
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(
                android.os.CancellationSignal(),
                mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                        store.dispatch(MineAction.Engine(UiAction.UnlockApp))
                    }
                },
            )
        } catch (_: Exception) {
            // No biometrics and no screen lock: the lock cannot be enforced, so don't trap the user.
            store.dispatch(MineAction.Engine(UiAction.UnlockApp))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Immersive layout
    // ---------------------------------------------------------------------------------------

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(
            if (HIDE_STATUS_BAR) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.navigationBars(),
        )
    }
}

/**
 * Hidden bars report zero insets, which would push every screen up under the camera and down into
 * the gesture area. Keep reporting their real size, like iOS's safe area, so no layout moves.
 */
private fun keepSafeArea(content: ViewGroup) {
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val fixed = withBarSpace(insets)
        val group = view as ViewGroup
        for (i in 0 until group.childCount) ViewCompat.dispatchApplyWindowInsets(group.getChildAt(i), fixed)
        WindowInsetsCompat.CONSUMED
    }
    if (Build.VERSION.SDK_INT >= 30) keepSafeAreaWhileAnimating(content)
}

private fun withBarSpace(insets: WindowInsetsCompat): WindowInsetsCompat {
    val status = WindowInsetsCompat.Type.statusBars()
    val nav = WindowInsetsCompat.Type.navigationBars()
    return WindowInsetsCompat.Builder(insets)
        .setInsets(status, insets.getInsetsIgnoringVisibility(status))
        .setInsets(nav, insets.getInsetsIgnoringVisibility(nav))
        .build()
}

/** The same correction during keyboard animations, so screens don't jump while the keyboard moves. */
@RequiresApi(30)
private fun keepSafeAreaWhileAnimating(content: ViewGroup) {
    content.setWindowInsetsAnimationCallback(
        object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(
                insets: android.view.WindowInsets,
                runningAnimations: MutableList<WindowInsetsAnimation>,
            ): android.view.WindowInsets =
                withBarSpace(WindowInsetsCompat.toWindowInsetsCompat(insets, content)).toWindowInsets() ?: insets
        },
    )
}
