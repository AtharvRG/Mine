package com.mine.companion.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

object MineMotion {

    fun <T> fm(stiffness: Float, damping: Float, mass: Float = 1f, threshold: T? = null): SpringSpec<T> =
        spring(
            dampingRatio = damping / (2f * sqrt(stiffness * mass)),
            stiffness = stiffness / mass,
            visibilityThreshold = threshold,
        )

    fun <T> dur(durationSec: Float, bounce: Float = 0f, threshold: T? = null): SpringSpec<T> {
        val omega = 2f * PI.toFloat() / durationSec
        return spring(dampingRatio = 1f - bounce, stiffness = omega * omega, visibilityThreshold = threshold)
    }

    fun <T> snappy(threshold: T? = null) = fm(520f, 38f, 0.7f, threshold)
    fun <T> sheet(threshold: T? = null) = fm(380f, 38f, 0.9f, threshold)
    fun <T> gentle(threshold: T? = null) = fm(260f, 30f, 1f, threshold)

    /** Pill → sheet. ζ 0.8 lifts ~1.5% past rest, then settles: the "relieved" landing. */
    fun <T> pillOpen(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 250f)

    /** Sheet → pill. Near-critical; the width follower provides the soft melt. */
    fun <T> pillClose(): SpringSpec<T> = spring(dampingRatio = 0.92f, stiffness = 360f)

    val EaseOutQuint: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EaseInOutSoft: Easing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
    val FmEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val FmEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val TwEase: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Pulse: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
    val Linear: Easing = LinearEasing

    fun <T> fade(): TweenSpec<T> = tween(180, easing = EaseOutQuint)

    fun <T> tw(ms: Int, easing: Easing = FmEaseInOut, delay: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis = ms, delayMillis = delay, easing = easing)

    enum class Press(val scale: Float) { Card(0.975f), Control(0.94f), Icon(0.88f), Chip(0.95f), Orb(0.9f) }

    fun projectToDetent(current: Float, velocity: Float, detents: List<Float>): Float {
        val projected = current + velocity * 0.18f
        return detents.minBy { abs(it - projected) }
    }

    fun rubberband(value: Float, min: Float, max: Float, k: Float = 0.55f): Float = when {
        value < min -> min - (min - value) * k * 0.4f
        value > max -> max + (value - max) * k * 0.4f
        else -> value
    }
}

fun interp(value: Float, input: FloatArray, output: FloatArray): Float {
    val n = input.size
    val asc = input[n - 1] >= input[0]
    if (asc) {
        if (value <= input[0]) return output[0]
        if (value >= input[n - 1]) return output[n - 1]
    } else {
        if (value >= input[0]) return output[0]
        if (value <= input[n - 1]) return output[n - 1]
    }
    for (i in 0 until n - 1) {
        val a = input[i]
        val b = input[i + 1]
        val inSeg = if (asc) value <= b else value >= b
        if (inSeg) {
            val t = if (b == a) 0f else (value - a) / (b - a)
            return output[i] + (output[i + 1] - output[i]) * t
        }
    }
    return output[n - 1]
}

fun interp(value: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
    if (i1 == i0) return if (value < i0) o0 else o1
    val t = ((value - i0) / (i1 - i0)).coerceIn(0f, 1f)
    return o0 + (o1 - o0) * t
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

fun cssBlurRadiusPx(cssSigmaPx: Float): Float =
    if (cssSigmaPx <= 0.5f) 0f else (cssSigmaPx - 0.5f) / 0.57735f

/** Global switch, set by MineTheme: blur transitions off in Battery Saver, on otherwise. */
object MineFx {
    @Volatile
    var blurEnabled: Boolean = true
}

fun blurEffect(cssSigmaPx: Float): RenderEffect? {
    if (!MineFx.blurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val r = cssBlurRadiusPx(cssSigmaPx)
    return if (r < 0.3f) null else BlurEffect(r, r, TileMode.Decal)
}

@Stable
class Haptics(private val view: View) {
    private fun fire(constant: Int) {
        view.performHapticFeedback(constant)
    }

    fun tick() = fire(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY)
    fun soft() = fire(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun warn() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** A drag crossed its commit point. */
    fun detent() = fire(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)

    /** A thrown surface reached rest. */
    fun land() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK)

    fun toggle(on: Boolean) = fire(
        when {
            Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CLOCK_TICK
            on -> HapticFeedbackConstants.TOGGLE_ON
            else -> HapticFeedbackConstants.TOGGLE_OFF
        },
    )

    fun play(kind: HapticKind) = when (kind) {
        HapticKind.Tick -> tick()
        HapticKind.Confirm -> confirm()
        HapticKind.Warn -> warn()
    }
}

enum class HapticKind { Tick, Confirm, Warn }

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
