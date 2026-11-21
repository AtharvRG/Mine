package com.mine.companion.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

val LocalReducedMotion = staticCompositionLocalOf { false }

/** True in Battery Saver. Only skips one-off flourishes (the pill hop); core motion stays. */
val LocalLowPower = staticCompositionLocalOf { false }

val LocalFxActive = compositionLocalOf { true }

@Composable
fun MineTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = remember(dark) { (if (dark) DarkPalette else LightPalette).also { Mine.palette = it } }
    val context = LocalContext.current
    val reduced = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val lowPower by produceState(initialValue = context.isPowerSave(), context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                value = c.isPowerSave()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitDispose { context.unregisterReceiver(receiver) }
    }
    val selection = remember(palette) {
        TextSelectionColors(handleColor = palette.accent, backgroundColor = palette.accent.copy(alpha = 0.3f))
    }
    SideEffect { MineFx.blurEnabled = !lowPower }
    CompositionLocalProvider(
        LocalReducedMotion provides reduced,
        LocalLowPower provides lowPower,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}

private fun Context.isPowerSave(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
