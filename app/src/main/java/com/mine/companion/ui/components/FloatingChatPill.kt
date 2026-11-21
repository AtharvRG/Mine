package com.mine.companion.ui.components

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mine.companion.store.MineAction
import com.mine.companion.store.SettingKey
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.fx.PRISMATIC_PORTAL_AGSL
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.screens.ChatAndDayPage
import com.mine.companion.ui.theme.Haptics
import com.mine.companion.ui.theme.LocalFxActive
import com.mine.companion.ui.theme.LocalLowPower
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.blurEffect
import com.mine.companion.ui.theme.lerp
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.smoothstep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────── tuning ─────────────────────────

private const val PILL_W = 136f
private const val PILL_H = 42f
private const val PILL_BOTTOM = 14f
private const val OPEN_GAP = 8f
private const val SHEET_R = 34f
private const val BULGE = 12f
private const val RUBBER = 56f
private const val OPEN_LINE = 0.32f
private const val DISMISS_LINE = 0.80f
private const val TAP_KICK = 1.6f
/** On tap, the light starts first and the shape follows a beat later. */
private const val TAP_ANTICIPATION_MS = 80L
private const val HOLD_MS = 420L
private const val TICK_EVERY_DP = 18f
private const val SPREAD_K = 420f
private val SPREAD_C = 2f * 0.9f * sqrt(SPREAD_K)

/** Seconds for the full light show from rest, paced on the reference board. */
private const val LIGHT_DURATION = 1.45f
/** How far the light clock gets on touch alone: the navy charge with a cyan floor (ref. steps 10–18). */
private const val CHARGE_TAU = 0.075f
/** How far a full pull takes the light under your finger: into the heat stage. */
private const val DRAG_TAU_MAX = 0.42f
/** The small glow that lands with the capsule on close. */
private const val AFTERGLOW_TAU = 0.06f
private const val REVEAL_FROM = 0.76f
private const val PORTAL_RES = 0.5f
private const val CHAT_BLUR_DP = 18f

private val OpenSpring = spring(dampingRatio = 0.8f, stiffness = 250f, visibilityThreshold = 0.0002f)
private val CloseSpring = spring(dampingRatio = 0.92f, stiffness = 360f, visibilityThreshold = 0.0002f)

private val PillBlack = Color(0xFF070709)
private val PillLabel = Color(0xFFF4F2ED)
private val Lavender = Color(0xFFD9D2FF)

private var hoppedThisProcess = false

/** Two-stage spread: a near-square card first (ref. steps 19–36), then the full sheet. */
private fun spreadTarget(p: Float): Float {
    val q = p.coerceIn(0f, 1f)
    return 0.16f * smoothstep(0f, 0.22f, q) + 0.84f * smoothstep(0.28f, 0.72f, q)
}

/**
 * Light clock τ → shader phase, fitted to the reference board's stage boundaries
 * (charge, growth, heat peak, cooling, horizon, reveal, settle). Monotone cubic, so it
 * never overshoots or runs backwards, and the slow final slope gives the soft ending.
 */
private object LightCurve {
    private val xs = floatArrayOf(0f, 0.10f, 0.22f, 0.33f, 0.45f, 0.55f, 0.63f, 0.75f, 0.88f, 1f)
    private val ys = floatArrayOf(0f, 0.04f, 0.16f, 0.30f, 0.48f, 0.62f, 0.76f, 0.88f, 0.96f, 1f)
    private val m = FloatArray(xs.size)

    init {
        val n = xs.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    fun map(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var i = 0
        while (i < xs.size - 2 && t > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val u = (t - xs[i]) / h
        val u2 = u * u
        val u3 = u2 * u
        return (2f * u3 - 3f * u2 + 1f) * ys[i] + (u3 - 2f * u2 + u) * h * m[i] +
            (-2f * u3 + 3f * u2) * ys[i + 1] + (u3 - u2) * h * m[i + 1]
    }
}

// Halo colour per stage: navy charge, azure growth, warm-white heat, rose lobes, blue cooling, lavender reveal.
private val SpillStops = floatArrayOf(0f, 0.12f, 0.26f, 0.42f, 0.58f, 0.80f, 1f)
private val SpillColors = arrayOf(
    Color(0xFF1F33A0),
    Color(0xFF338CFA),
    Color(0xFFFFE6C8),
    Color(0xFFFF9E8C),
    Color(0xFF385CF2),
    Color(0xFFC7BDFF),
    Color(0xFFC7BDFF),
)

private fun spillColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    var i = 0
    while (i < SpillStops.size - 2 && x > SpillStops[i + 1]) i++
    val f = ((x - SpillStops[i]) / (SpillStops[i + 1] - SpillStops[i])).coerceIn(0f, 1f)
    return lerpColor(SpillColors[i], SpillColors[i + 1], f * f * (3f - 2f * f))
}

private fun spillStrength(t: Float) = smoothstep(0.004f, 0.06f, t) * (1f - smoothstep(0.70f, 0.95f, t))
private fun washStrength(t: Float) = smoothstep(0.78f, 0.88f, t) * (1f - smoothstep(0.90f, 1.0f, t))

// ───────────────────────── haptics ─────────────────────────

internal class PillHaptics(context: Context, private val view: Haptics) {
    private val rich: RichHaptics? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RichHaptics.create(context) else null
    private var lastTensionAt = 0L

    fun press() {
        rich?.lowTick(0.2f)
    }

    fun tension(level: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTensionAt < 32L) return
        lastTensionAt = now
        rich?.lowTick(0.08f + 0.3f * level)
    }

    fun reveal() {
        rich?.lowTick(0.28f)
    }

    fun detent() = rich?.click(0.75f) ?: view.detent()
    fun land() = rich?.land(0.45f) ?: view.land()
    fun soft() = rich?.tick(0.32f) ?: view.soft()
    fun confirm() = view.confirm()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class RichHaptics private constructor(private val vibrator: Vibrator, private val thud: Boolean) {
    private val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    private fun play(primitive: Int, scale: Float) {
        runCatching {
            vibrator.vibrate(
                VibrationEffect.startComposition().addPrimitive(primitive, scale.coerceIn(0f, 1f)).compose(),
                attrs,
            )
        }
    }

    fun lowTick(scale: Float) = play(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
    fun tick(scale: Float) = play(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
    fun click(scale: Float) = play(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
    fun land(scale: Float) = play(
        if (thud) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK,
        scale,
    )

    companion object {
        fun create(context: Context): RichHaptics? {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return null
            if (!vibrator.hasVibrator()) return null
            val basics = vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
            if (!basics) return null
            return RichHaptics(vibrator, vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD))
        }
    }
}

// ───────────────────────── state ─────────────────────────

/**
 * [value]: geometry, tracks the finger and lands on a spring.
 * [lightTau]: the light-show clock. It runs evenly; [lightPhase] maps it through [LightCurve].
 */
@Stable
class PillState internal constructor(
    private val scope: CoroutineScope,
    internal val haptics: PillHaptics,
) {
    private val anim = Animatable(0f, visibilityThreshold = 0.0002f)
    private val tau = Animatable(0f, visibilityThreshold = 0.0001f)
    internal val veil = Animatable(1f)
    private var dragValue by mutableFloatStateOf(0f)
    private var dragTau by mutableFloatStateOf(0f)
    private var raw = 0f
    private var fromOpen = false
    private var armed = false
    private var tickStep = 0
    private var job: Job? = null
    private var chargeJob: Job? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var spreadVelocity = 0f

    internal var travel = 1f
    internal var rubberPx = 1f
    internal var dpPx = 1f
    internal var shaderOn by mutableStateOf(false)

    var isDragging by mutableStateOf(false)
        private set
    var isOpen by mutableStateOf(false)
        private set
    var isPressed by mutableStateOf(false)
        private set
    var isMounted by mutableStateOf(false)
        private set
    var isClosing by mutableStateOf(false)
        private set
    var spread by mutableFloatStateOf(0f)
        private set

    val value: Float get() = if (isDragging) dragValue else anim.value
    val lightTau: Float get() = if (isDragging) dragTau else tau.value
    val lightPhase: Float get() = LightCurve.map(lightTau)

    internal fun contentAlpha(): Float = when {
        isClosing -> veil.value
        shaderOn -> smoothstep(REVEAL_FROM - 0.06f, REVEAL_FROM + 0.05f, lightPhase)
        else -> smoothstep(0.36f, 0.90f, value)
    }

    internal val chatPlaced: Boolean by derivedStateOf { isOpen || isPressed || isDragging || value > 0.0005f }
    internal val chatLive: Boolean by derivedStateOf { contentAlpha() > 0.02f }

    fun press(down: Boolean) {
        isPressed = down
        if (down) {
            isMounted = true
            haptics.press()
            if (!isOpen && shaderOn && tau.value < CHARGE_TAU) {
                chargeJob?.cancel()
                chargeJob = scope.launch { tau.animateTo(CHARGE_TAU, tween(240, easing = FastOutSlowInEasing)) }
            }
        } else if (!isOpen && !isDragging && job?.isActive != true) {
            // Let go without opening: the charge drains back out.
            chargeJob?.cancel()
            chargeJob = scope.launch { tau.animateTo(0f, tween(360, easing = FastOutSlowInEasing)) }
        }
        wakeups.trySend(Unit)
    }

    fun prewarm() {
        isMounted = true
    }

    fun tap() {
        if (!isOpen) settle(open = true, velocity = TAP_KICK, delayMs = TAP_ANTICIPATION_MS)
    }

    fun open(velocity: Float = 0f) = settle(open = true, velocity = velocity)

    fun close() {
        if (isOpen || value != 0f) settle(open = false, velocity = 0f)
    }

    internal fun beginDrag() {
        job?.cancel()
        job = null
        chargeJob?.cancel()
        val start = anim.value
        fromOpen = isOpen
        raw = start
        dragValue = start
        dragTau = tau.value
        armed = if (fromOpen) start < DISMISS_LINE else start > OPEN_LINE
        tickStep = 0
        isClosing = false
        isMounted = true
        isPressed = false
        isDragging = true
        wakeups.trySend(Unit)
    }

    internal fun dragBy(deltaPx: Float) {
        if (!isDragging) return
        raw -= deltaPx / travel
        val v = when {
            raw < 0f -> {
                val d = -raw * travel
                -(rubberPx * d / (d + rubberPx)) / travel
            }
            raw > 1f -> 1f + (raw - 1f) * 0.12f
            else -> raw
        }
        dragValue = v

        val nowArmed = if (fromOpen) v < DISMISS_LINE else v > OPEN_LINE
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) haptics.detent() else haptics.soft()
        }

        if (!fromOpen) {
            // The light grows under your finger: charge, growth, then into the heat.
            dragTau = max(CHARGE_TAU, DRAG_TAU_MAX * v.coerceIn(0f, 1f).pow(0.85f))
            if (v > 0f && v < OPEN_LINE) {
                val step = (v * travel / (TICK_EVERY_DP * dpPx)).toInt()
                if (step != tickStep) {
                    tickStep = step
                    haptics.tension(v / OPEN_LINE)
                }
            }
        }
    }

    internal fun roomAbovePx(): Float = if (isDragging) (1f - dragValue).coerceAtLeast(0f) * travel else 0f

    internal fun release(velocityPxDown: Float) {
        if (!isDragging) return
        val v = (-velocityPxDown / travel).coerceIn(-4f, 4f)
        val projected = dragValue + v * 0.16f
        val open = projected > if (fromOpen) DISMISS_LINE else OPEN_LINE
        settle(open, v, snapBack = fromOpen && open)
    }

    private fun settle(open: Boolean, velocity: Float, snapBack: Boolean = false, delayMs: Long = 0L) {
        val from = if (isDragging) dragValue else null
        val fromTau = dragTau
        val startAlpha = contentAlpha()
        isOpen = open
        chargeJob?.cancel()
        job?.cancel()
        if (open) {
            isMounted = true
            isClosing = false
        }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (from != null) {
                anim.snapTo(from)
                tau.snapTo(fromTau)
                isDragging = false
            }
            wakeups.trySend(Unit)
            if (open) {
                veil.snapTo(1f)
                launch { runLightOpen() }
                if (delayMs > 0L) {
                    delay(delayMs)
                    // The width spring went to sleep during the pause; wake it or the sheet rises as a stick.
                    wakeups.trySend(Unit)
                }
                var landed = anim.value >= 1f
                anim.animateTo(1f, OpenSpring, velocity) {
                    if (!landed && this.value >= 1f) {
                        landed = true
                        if (snapBack) haptics.soft() else haptics.land()
                    }
                }
            } else {
                isClosing = true
                launch { runClose(startAlpha) }
                var landed = anim.value <= 0f
                anim.animateTo(0f, CloseSpring, velocity) {
                    if (!landed && this.value <= 0f) {
                        landed = true
                        haptics.soft()
                    }
                }
                isClosing = false
            }
        }
    }

    private suspend fun runLightOpen() {
        val from = tau.value
        if (from >= 1f) return
        if (!shaderOn) {
            tau.animateTo(1f, tween(450))
            return
        }
        val ms = (LIGHT_DURATION * 1000f * (1f - from)).roundToInt().coerceAtLeast(300)
        var broke = LightCurve.map(from) >= REVEAL_FROM
        // Linear clock: all the easing lives in LightCurve, so a resumed show never kinks.
        tau.animateTo(1f, tween(ms, easing = LinearEasing)) {
            if (!broke && LightCurve.map(this.value) >= REVEAL_FROM) {
                broke = true
                haptics.reveal()
            }
        }
    }

    /** Dynamic Island collapse: content fades and blurs into black; no rewinding of the light show. */
    private suspend fun runClose(startAlpha: Float) {
        coroutineScope {
            veil.snapTo(startAlpha)
            launch { veil.animateTo(0f, tween(200, easing = FastOutLinearInEasing)) }
            if (lightPhase > 0.3f) {
                tau.snapTo(0f)
                if (shaderOn) {
                    snapshotFlow { anim.value }.first { it < 0.14f }
                    tau.animateTo(AFTERGLOW_TAU, tween(120, easing = FastOutSlowInEasing))
                    tau.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
                }
            } else {
                tau.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            }
        }
    }

    internal suspend fun runFollower() {
        while (true) {
            wakeups.receive()
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                var dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val target = spreadTarget(value)
                var s = spread
                var v = spreadVelocity
                while (dt > 0f) {
                    val h = min(dt, 1f / 240f)
                    v += (-SPREAD_K * (s - target) - SPREAD_C * v) * h
                    s += v * h
                    dt -= h
                }
                if (!isDragging && !anim.isRunning && job?.isActive != true && abs(s - target) < 0.0005f && abs(v) < 0.005f) {
                    spread = target
                    spreadVelocity = 0f
                    break
                }
                spread = s
                spreadVelocity = v
            }
        }
    }
}

@Composable
fun rememberPillState(): PillState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = rememberHaptics()
    val state = remember { PillState(scope, PillHaptics(context, view)) }
    LaunchedEffect(state) { state.runFollower() }
    return state
}

// ───────────────────────── geometry ─────────────────────────

private class PillMetrics(
    val width: Float,
    val height: Float,
    val pillW: Float,
    val pillH: Float,
    val closedTop: Float,
    val openTop: Float,
    val sheetR: Float,
    val bulge: Float,
    val squash: Float,
    val maxLift: Float,
    val dp: Float,
) {
    val travel = (closedTop - openTop).coerceAtLeast(1f)
    val closedBottom = closedTop + pillH
}

/** Outer black shape plus the inset "screen" the light lives in. Recomputed in draw lambdas only. */
private class PillGeometry(
    private val state: PillState,
    private val m: PillMetrics,
    private val hop: Animatable<Float, AnimationVector1D>,
    private val press: Animatable<Float, AnimationVector1D>,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    var radius = 0f
    var p = 0f
    var rest = 1f

    var inL = 0f
    var inT = 0f
    var inR = 0f
    var inB = 0f
    var inRadius = 0f

    fun update() {
        val v = state.value
        p = v
        rest = 1f - smoothstep(0f, 0.05f, abs(v))
        val pr = lerp(1f, press.value, rest)

        if (v < 0f) {
            val down = -v * m.travel
            val k = 1f + 0.22f * down / (down + m.squash)
            val w = m.pillW * k * pr
            val h = m.pillH / k * pr
            val cy = m.closedTop + m.pillH / 2f + down * 0.5f
            left = (m.width - w) / 2f
            right = left + w
            top = cy - h / 2f
            bottom = cy + h / 2f
            radius = h / 2f
        } else {
            val s = state.spread.coerceIn(0f, 1.03f)
            val lift = hop.value * rest
            val pc = min(v, 1f + m.maxLift / m.travel)
            val t = lerp(m.closedTop, m.openTop, pc) + lift
            val b = lerp(m.closedBottom, m.height + m.sheetR, smoothstep(0.10f, 0.70f, v)) + lift
            val cy = (t + b) / 2f
            val hw = lerp(m.pillW, m.width, s) * pr / 2f
            val hh = (b - t) * pr / 2f
            left = m.width / 2f - hw
            right = m.width / 2f + hw
            top = cy - hh
            bottom = cy + hh
            val shaped = lerp(m.pillH / 2f, m.sheetR, s.coerceAtMost(1f)) + m.bulge * sin(PI.toFloat() * s.coerceIn(0f, 1f))
            radius = min(shaped, min(hw, hh))
        }

        // The frame thins as the shape grows: 5dp on the capsule, ~1.25dp on the full sheet.
        val t = state.lightPhase
        val minSide = min(right - left, bottom - top)
        val sizeK = smoothstep(m.pillH, m.width * 0.85f, minSide)
        val padMax = lerp(5f, 1.25f, sizeK) * m.dp
        val pad = padMax * smoothstep(0f, 0.06f, t) * (1f - smoothstep(0.80f, 0.97f, t))
        inL = left + pad
        inR = right - pad
        inT = top + pad
        inB = bottom - pad
        inRadius = max(radius - pad, 3f * m.dp)
    }
}

private data class PillOutline(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(l, t, r, b, CornerRadius(radius)))
}

// ───────────────────────── composable ─────────────────────────

@Composable
fun FloatingChatPill(state: PillState, modifier: Modifier = Modifier) {
    val dispatch = mineDispatch()
    val handsFree = mineSelect { it.handsFreeActive }
    val latestHandsFree by rememberUpdatedState(handsFree)
    val lowPower = LocalLowPower.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val useShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !reduced

    val onHold by rememberUpdatedState {
        state.haptics.confirm()
        if (!latestHandsFree) dispatch(MineAction.ToggleSetting(SettingKey.handsFreeActive))
        state.open(1.2f)
    }

    LaunchedEffect(state) {
        if (latestHandsFree && !state.isOpen) dispatch(MineAction.ToggleSetting(SettingKey.handsFreeActive))
        delay(2400)
        state.prewarm()
    }
    LaunchedEffect(state) {
        snapshotFlow { !state.isOpen && state.value < 0.01f }
            .drop(1)
            .filter { it }
            .collect { if (latestHandsFree) dispatch(MineAction.ToggleSetting(SettingKey.handsFreeActive)) }
    }
    BackHandler(enabled = state.isOpen) { state.close() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val d = density.density
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val m = remember(w, h, navBottom, statusTop, d) {
            PillMetrics(
                width = w,
                height = h,
                pillW = PILL_W * d,
                pillH = PILL_H * d,
                closedTop = h - navBottom - (PILL_BOTTOM + PILL_H) * d,
                openTop = statusTop + OPEN_GAP * d,
                sheetR = SHEET_R * d,
                bulge = BULGE * d,
                squash = 24f * d,
                maxLift = 14f * d,
                dp = d,
            )
        }
        SideEffect {
            state.travel = m.travel
            state.rubberPx = RUBBER * d
            state.dpPx = d
            state.shaderOn = useShader
        }

        val hop = remember { Animatable(0f) }
        val press = remember { Animatable(1f) }
        LaunchedEffect(state) {
            snapshotFlow { state.isPressed }.collectLatest { down ->
                press.animateTo(
                    if (down) 0.95f else 1f,
                    spring(dampingRatio = if (down) 1f else 0.55f, stiffness = if (down) 1400f else 700f),
                )
            }
        }
        LaunchedEffect(Unit) {
            if (hoppedThisProcess || lowPower || reduced) return@LaunchedEffect
            hoppedThisProcess = true
            delay(1400)
            if (state.value != 0f || state.isPressed) return@LaunchedEffect
            hop.animateTo(-7f * d, tween(180, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 380f))
        }

        val geo = remember(m) { PillGeometry(state, m, hop, press) }
        val sheetFill = Mine.Canvas
        val grabberInk = Mine.Ink4
        val hintInk = Mine.PaperInk1
        val shadowTint = Color.Black.copy(alpha = 0.35f)
        val spillMax = if (Mine.IsDark) 0.28f else 0.16f

        val idle by remember(state) { derivedStateOf { !state.isOpen && !state.isDragging && abs(state.value) < 0.02f } }
        if (idle) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { (navBottom + (PILL_BOTTOM + PILL_H + 6f) * d).toDp() })
                    .graphicsLayer {
                        alpha = (1f - smoothstep(0f, 0.04f, abs(state.value))) * (1f - smoothstep(0.01f, 0.05f, state.lightPhase))
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PulsingChevron(hintInk)
                ShimmerText("Pull up to talk to Mine", hintInk)
            }
        }

        // Light spill: the glow lights up what's around it, coloured by the current stage.
        if (useShader) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { }
                    .drawBehind {
                        val t = state.lightPhase
                        val k = spillStrength(t) * spillMax
                        if (k < 0.002f) return@drawBehind
                        geo.update()
                        val c = Offset((geo.left + geo.right) / 2f, (geo.top + geo.bottom) / 2f)
                        val r = max(geo.right - geo.left, geo.bottom - geo.top) * 0.75f + 48f * d
                        val col = spillColor(t)
                        drawCircle(
                            Brush.radialGradient(
                                0f to col.copy(alpha = k),
                                0.5f to col.copy(alpha = k * 0.35f),
                                1f to Color.Transparent,
                                center = c,
                                radius = r,
                            ),
                            radius = r,
                            center = c,
                        )
                    },
            )
        }

        // While open, nothing outside the sheet should reach Home, even mid-animation.
        if (state.isOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    geo.update()
                    shape = PillOutline(geo.left, geo.top, geo.right, geo.bottom, geo.radius)
                    clip = true
                    shadowElevation = 14f * d * geo.rest
                    ambientShadowColor = shadowTint
                    spotShadowColor = shadowTint
                }
                .then(
                    if (state.isOpen) {
                        Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { }
                    .drawBehind {
                        val e = if (useShader) {
                            smoothstep(REVEAL_FROM, 0.95f, state.lightPhase)
                        } else {
                            smoothstep(0.05f, 0.40f, state.value.coerceIn(0f, 1f))
                        }
                        drawRect(lerpColor(PillBlack, sheetFill, e))
                    },
            )

            if (state.isMounted) PillChat(state, geo, m, useShader, onClose = { state.close() })

            // Exposure settle: the revealed content starts washed out with a lavender rim (ref. steps 118–135).
            if (useShader) {
                Spacer(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (state.isClosing) 0f else washStrength(state.lightPhase) * 0.42f }
                        .drawWithCache {
                            val brush = Brush.radialGradient(
                                0f to Lavender.copy(alpha = 0.2f),
                                0.65f to Lavender.copy(alpha = 0.45f),
                                1f to Lavender.copy(alpha = 0.9f),
                                center = Offset(size.width / 2f, size.height * 0.55f),
                                radius = max(size.width, size.height) * 0.7f,
                            )
                            onDrawBehind { drawRect(brush) }
                        },
                )
            }

            if (useShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PortalLayer(state, geo, d)

            Row(
                Modifier
                    .size(with(density) { m.pillW.toDp() }, with(density) { m.pillH.toDp() })
                    .graphicsLayer {
                        geo.update()
                        translationX = (geo.left + geo.right) / 2f - m.pillW / 2f
                        translationY = (geo.top + geo.bottom) / 2f - m.pillH / 2f
                        // The label gives way to the charge glow, the way Dynamic Island swaps content.
                        val a = (1f - smoothstep(0.02f, 0.12f, abs(geo.p))) *
                            (1f - smoothstep(0.012f, 0.045f, state.lightPhase))
                        alpha = a
                        renderEffect = blurEffect((1f - a) * 5f * d)
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Mine.Accent, CircleShape))
                Txt("Mine", mineText(12.5f, 500, tracking = -0.025f), PillLabel)
            }

            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .graphicsLayer {
                        geo.update()
                        translationY = geo.top
                        val shown = if (useShader) smoothstep(0.82f, 0.96f, state.lightPhase) else smoothstep(0.14f, 0.42f, geo.p)
                        alpha = if (state.isClosing) shown * state.veil.value else shown
                    }
                    .drawBehind {
                        val gw = 36.dp.toPx()
                        val gh = 5.dp.toPx()
                        drawRoundRect(
                            grabberInk,
                            topLeft = Offset((size.width - gw) / 2f, 8.dp.toPx()),
                            size = Size(gw, gh),
                            cornerRadius = CornerRadius(gh / 2f),
                        )
                    },
            )
        }

        if (!state.isOpen) {
            val slop = 8f * d
            Box(
                Modifier
                    .offset { IntOffset(((m.width - m.pillW) / 2f - slop).roundToInt(), (m.closedTop - slop).roundToInt()) }
                    .size(with(density) { (m.pillW + 2f * slop).toDp() }, with(density) { (m.pillH + 2f * slop).toDp() })
                    .semantics {
                        role = Role.Button
                        contentDescription = "Talk to Mine. Tap or pull up to open, hold for voice."
                    }
                    .pointerInput(state) { pillGestures(state, scope) { onHold() } },
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { m.openTop.toDp() })
                    .semantics { contentDescription = "Close" }
                    .tap { state.close() },
            )
            val dragState = rememberDraggableState { delta -> state.dragBy(delta) }
            Box(
                Modifier
                    .offset {
                        geo.update()
                        IntOffset(0, geo.top.roundToInt())
                    }
                    .fillMaxWidth()
                    .height(24.dp)
                    .semantics { contentDescription = "Drag down to close" }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = { state.beginDrag() },
                        onDragStopped = { velocity -> state.release(velocity) },
                    ),
            )
        }
    }
}

/** Your portal shader, drawn into the inset screen at half resolution. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun PortalLayer(state: PillState, geo: PillGeometry, d: Float) {
    val shader = remember { RuntimeShader(PRISMATIC_PORTAL_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.lightPhase.let { it > 0.0005f && it < 0.9995f } }.collectLatest { on ->
            if (on) {
                val start = time.floatValue
                val t0 = withFrameNanos { it }
                while (true) withFrameNanos { time.floatValue = start + (it - t0) / 1_000_000_000f }
            }
        }
    }
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val pw = (constraints.maxWidth * PORTAL_RES).roundToInt().coerceAtLeast(1)
                val ph = (constraints.maxHeight * PORTAL_RES).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(pw, ph))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = 1f / PORTAL_RES
                        scaleY = 1f / PORTAL_RES
                        transformOrigin = TransformOrigin(0f, 0f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
            }
            .drawBehind {
                val t = state.lightPhase
                if (t <= 0.0005f || t >= 0.9995f) return@drawBehind
                geo.update()
                val w = geo.inR - geo.inL
                val h = geo.inB - geo.inT
                if (w <= 1f || h <= 1f) return@drawBehind
                shader.setFloatUniform("uDpr", d * PORTAL_RES)
                shader.setFloatUniform("uRect", geo.inL / d, geo.inT / d, w / d, h / d)
                shader.setFloatUniform("uRadius", geo.inRadius / d)
                shader.setFloatUniform("uPhase", t)
                shader.setFloatUniform("uTime", time.floatValue)
                drawRect(
                    brush,
                    topLeft = Offset(geo.inL * PORTAL_RES, geo.inT * PORTAL_RES),
                    size = Size(w * PORTAL_RES, h * PORTAL_RES),
                )
            },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillChat(state: PillState, geo: PillGeometry, m: PillMetrics, shader: Boolean, onClose: () -> Unit) {
    val connection = remember(state) { SheetDismissConnection(state) }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val height = (m.height - m.openTop).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (state.chatPlaced) {
                        placeable.placeWithLayer(0, m.openTop.roundToInt()) {
                            geo.update()
                            translationY = geo.top - m.openTop
                            when {
                                state.isClosing -> {
                                    val v = state.veil.value
                                    alpha = v
                                    val s = lerp(0.97f, 1f, v)
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0.5f, 0.3f)
                                    renderEffect = blurEffect((1f - v) * 10f * m.dp)
                                }
                                shader -> {
                                    val t = state.lightPhase
                                    alpha = state.contentAlpha()
                                    // Arrives slightly magnified and soft from the lower-middle, then settles (ref. 100–135).
                                    val s = lerp(1.06f, 1f, smoothstep(REVEAL_FROM, 1f, t))
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0.5f, 0.62f)
                                    renderEffect = blurEffect((1f - smoothstep(REVEAL_FROM, 0.97f, t)) * CHAT_BLUR_DP * m.dp)
                                }
                                else -> {
                                    val e = smoothstep(0.36f, 0.90f, geo.p)
                                    alpha = e
                                    val s = lerp(0.95f, 1f, e)
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0.5f, 0f)
                                    translationY += (1f - e) * 32f * m.dp
                                }
                            }
                        }
                    }
                }
            }
            .consumeWindowInsets(WindowInsets.statusBars)
            .nestedScroll(connection)
            .padding(top = 12.dp),
    ) {
        CompositionLocalProvider(LocalFxActive provides state.chatLive) {
            ChatAndDayPage(isPastDayPage = false, onClosePeephole = onClose)
        }
    }
}

private class SheetDismissConnection(private val state: PillState) : NestedScrollConnection {
    private var active = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!active || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        val used = maxOf(available.y, -state.roomAbovePx())
        if (used < 0f) state.dragBy(used)
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f || !state.isOpen) return Offset.Zero
        if (!active) {
            active = true
            state.beginDrag()
        }
        state.dragBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!active) return Velocity.Zero
        active = false
        state.release(available.y)
        return available
    }
}

private suspend fun PointerInputScope.pillGestures(state: PillState, scope: CoroutineScope, onHold: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.press(true)
        var held = false
        var dragging = false
        var released = false
        val hold = scope.launch {
            delay(HOLD_MS)
            held = true
            onHold()
            state.press(false)
        }
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var pending = 0f
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    if (dragging) {
                        released = true
                        state.release(tracker.calculateVelocity().y)
                    } else if (!held) {
                        state.tap()
                    }
                    break
                }
                if (held) continue
                val dy = change.positionChange().y
                if (dragging) {
                    state.dragBy(dy)
                    change.consume()
                } else {
                    pending += dy
                    if (abs(pending) > slop) {
                        dragging = true
                        hold.cancel()
                        state.beginDrag()
                        state.dragBy(pending - sign(pending) * slop)
                        change.consume()
                    }
                }
            }
        } finally {
            hold.cancel()
            state.press(false)
            if (dragging && !released) state.release(0f)
        }
    }
}

@Composable
private fun PulsingChevron(ink: Color) {
    val t = rememberInfiniteTransition(label = "chev")
    val a = t.animateFloat(
        1f, 1f,
        InfiniteRepeatableSpec(
            keyframes {
                durationMillis = 2000
                1f at 0 using MineMotion.Pulse
                0.5f at 1000 using MineMotion.Pulse
                1f at 2000
            },
            RepeatMode.Restart,
        ),
        label = "chev-a",
    )
    Box(Modifier.padding(bottom = 2.dp).graphicsLayer { alpha = a.value }) {
        LIcon(Lucide.ChevronUp, 16.dp, ink.copy(alpha = 0.35f))
    }
}

@Composable
private fun ShimmerText(text: String, ink: Color) {
    val t = rememberInfiniteTransition(label = "shimmer-text")
    val pos = t.animateFloat(
        2f, -2f,
        InfiniteRepeatableSpec(tween(3400, easing = MineMotion.Linear), RepeatMode.Restart),
        label = "shimmer-pos",
    )
    val dim = ink.copy(alpha = 0.42f)
    val bright = ink.copy(alpha = 0.95f)
    Txt(
        text,
        mineText(12.5f, 500, tracking = -0.025f),
        Color.Black,
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val tile = size.width * 2f
                onDrawWithContent {
                    drawContent()
                    val off = -size.width * pos.value
                    drawRect(
                        Brush.linearGradient(
                            0f to dim, 0.5f to bright, 1f to dim,
                            start = Offset(off, 0f), end = Offset(off + tile, 0f),
                            tileMode = TileMode.Repeated,
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                }
            },
    )
}
