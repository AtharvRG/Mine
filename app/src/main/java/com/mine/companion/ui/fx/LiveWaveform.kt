package com.mine.companion.ui.fx

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mine.companion.ui.theme.LocalFxActive
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WebAudio AnalyserNode equivalent: AudioRecord → Blackman window → FFT(256) →
 * smoothed magnitude (τ = 0.8) → dB → bytes over [-100, -30] dB, i.e. getByteFrequencyData.
 */
private class MicAnalyser(private val fftSize: Int = 256, private val smoothing: Float = 0.8f) {
    private val ring = FloatArray(fftSize)
    @Volatile private var ringPos = 0
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private val smoothed = FloatArray(fftSize / 2)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { i ->
        val a = 0.16f
        val n = i.toFloat() / fftSize
        ((1 - a) / 2f - 0.5f * cos(2 * PI.toFloat() * n) + (a / 2f) * cos(4 * PI.toFloat() * n))
    }
    val binCount get() = fftSize / 2

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = 44_100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, fftSize * 4))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread(name = "mine-mic", isDaemon = true) {
            val buf = ShortArray(fftSize)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(ring) {
                    for (i in 0 until n) {
                        ring[ringPos] = buf[i] / 32768f
                        ringPos = (ringPos + 1) % fftSize
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        running = false
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    fun byteFrequencyData(out: IntArray) {
        synchronized(ring) {
            for (i in 0 until fftSize) {
                re[i] = ring[(ringPos + i) % fftSize] * window[i]
                im[i] = 0f
            }
        }
        fft(re, im)
        for (k in 0 until binCount) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / fftSize
            smoothed[k] = smoothing * smoothed[k] + (1 - smoothing) * mag
            val db = if (smoothed[k] > 0f) 20f * log10(smoothed[k]) else -200f
            out[k] = (255f * (db + 100f) / 70f).toInt().coerceIn(0, 255)
        }
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tx = x[i]; x[i] = x[j]; x[j] = tx
                val ty = y[i]; y[i] = y[j]; y[j] = ty
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = x[i + k]; val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi
                    x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

private class WaveState {
    var staticBars = FloatArray(0)
    var lastActive = FloatArray(0)
    var transition = 0f
    var processingTime = 0f
    var fadeProgress = 0f
    var fading = false
    var lastUpdateMs = 0L
    var widthPx = 0f
    val freq = IntArray(128)
}

/**
 * components/ui/live-waveform.tsx in `mode="static"`: mirrored bars from the mic spectrum,
 * a synthetic voice cadence while the mic is silent or unavailable, the three-sine
 * "processing" swell, and the multiplicative fade to idle. Edges are faded with a
 * destination-out gradient in an offscreen layer; idle shows a 2dp dotted rule.
 */
@Composable
fun LiveWaveform(
    active: Boolean,
    processing: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    barGap: Dp = 2.dp,
    barRadius: Dp = 1.5.dp,
    barColor: Color = Color(0xFFF4F2ED),
    fadeEdges: Boolean = true,
    fadeWidth: Dp = 24.dp,
    baseBarHeight: Dp = 4.dp,
    height: Dp = 36.dp,
    sensitivity: Float = 1.2f,
    updateRateMs: Long = 30L,
    /**
     * External mic level (0..1). When set, the waveform follows it instead of opening its own
     * AudioRecord, so it never competes with the recorder that is actually listening.
     */
    level: (() -> Float)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val state = remember { WaveState() }
    val frame = remember { mutableLongStateOf(0L) }
    val latestActive by rememberUpdatedState(active)
    val latestProcessing by rememberUpdatedState(processing)
    val stepPx = with(density) { (barWidth + barGap).toPx() }

    // Microphone, only while active and only if the user granted it (the view requests it).
    val analyser = remember { MicAnalyser() }
    val micOn = remember { booleanArrayOf(false) }
    val latestLevel by rememberUpdatedState(level)
    DisposableEffect(active, level == null) {
        if (active && level == null && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micOn[0] = analyser.start()
        }
        onDispose {
            if (micOn[0]) analyser.stop()
            micOn[0] = false
        }
    }

    val fxActive = LocalFxActive.current
    LaunchedEffect(active, processing, fxActive) {
        if (!fxActive) return@LaunchedEffect
        if (processing && !active) {
            state.processingTime = 0f
            state.transition = 0f
        } else if (!active && !processing) {
            state.fading = state.staticBars.isNotEmpty()
            state.fadeProgress = 0f
        }
        while (latestActive || latestProcessing || state.fading) {
            withFrameNanos { nanos ->
                val nowMs = nanos / 1_000_000L
                val w = if (state.widthPx > 0f) state.widthPx else 180f
                val act = latestActive
                val proc = latestProcessing

                if (proc && !act) {
                    state.processingTime += 0.03f
                    state.transition = min(1f, state.transition + 0.02f)
                    val barCount = floor(w / stepPx).toInt()
                    val half = barCount / 2
                    val out = FloatArray(barCount)
                    for (i in 0 until barCount) {
                        val norm = (i - half).toFloat() / max(1, half)
                        val center = 1f - abs(norm) * 0.4f
                        val t = state.processingTime
                        val wave = sin(t * 1.5f + norm * 3f) * 0.25f + sin(t * 0.8f - norm * 2f) * 0.2f + cos(t * 2f + norm) * 0.15f
                        val pv = (0.2f + wave) * center
                        var v = pv
                        val last = state.lastActive
                        if (last.isNotEmpty() && state.transition < 1f) {
                            val lv = last[min(i, last.size - 1)]
                            v = lv * (1f - state.transition) + pv * state.transition
                        }
                        out[i] = v.coerceIn(0.05f, 1f)
                    }
                    state.staticBars = out
                } else if (!act && !proc && state.fading) {
                    state.fadeProgress += 0.03f
                    if (state.fadeProgress < 1f) {
                        val f = 1f - state.fadeProgress
                        state.staticBars = FloatArray(state.staticBars.size) { state.staticBars[it] * f }
                    } else {
                        state.staticBars = FloatArray(0)
                        state.fading = false
                    }
                }

                if (act && nowMs - state.lastUpdateMs > updateRateMs) {
                    state.lastUpdateMs = nowMs
                    val barCount = max(8, floor(w / stepPx).toInt())
                    val half = barCount / 2
                    val t = nowMs * 0.0045f
                    val bars = ArrayList<Float>(barCount)
                    val ext = latestLevel
                    if (ext != null) {
                        // Shape the single level into a centred voice envelope with natural motion.
                        val lv = ext().coerceIn(0f, 1f)
                        for (i in 0 until barCount) {
                            val norm = (i - half).toFloat() / max(1, half)
                            val env = max(0.12f, 1f - abs(norm) * 0.85f)
                            val wobble = 0.65f + 0.25f * sin(t * 2.6f + abs(norm) * 5f) + 0.1f * cos(t * 4.1f - i * 0.5f)
                            bars += max(0.08f, min(1f, (0.1f + lv * sensitivity * 1.4f) * env * wobble))
                        }
                    } else if (micOn[0]) {
                        analyser.byteFrequencyData(state.freq)
                        val start = floor(analyser.binCount * 0.05f).toInt()
                        val end = floor(analyser.binCount * 0.4f).toInt()
                        val relevant = state.freq.copyOfRange(start, end)
                        val maxCheck = relevant.maxOrNull() ?: 0
                        fun value(i: Int): Float {
                            val idx = floor(i.toFloat() / max(1, half) * relevant.size).toInt().coerceIn(0, relevant.size - 1)
                            val raw = if (maxCheck > 4) relevant[idx] / 255f * sensitivity
                            else (1f - i.toFloat() / max(1, half)) * (0.38f + 0.32f * sin(t * 2.1f + i * 0.55f) + 0.18f * cos(t * 3.4f - i * 0.3f))
                            return min(1f, max(0.08f, raw))
                        }
                        for (i in half - 1 downTo 0) bars += value(i)
                        for (i in 0 until half) bars += value(i)
                    } else {
                        // graceful synthetic cadence while awaiting / without mic permission
                        for (i in 0 until barCount) {
                            val norm = (i - half).toFloat() / max(1, half)
                            val env = max(0.15f, 1f - abs(norm) * 0.78f)
                            val v = env * (0.42f + 0.28f * sin(t * 2.3f + abs(norm) * 4.2f) + 0.2f * cos(t * 3.7f - i * 0.4f))
                            bars += max(0.08f, min(1f, v))
                        }
                    }
                    state.staticBars = bars.toFloatArray()
                    state.lastActive = state.staticBars
                }
                frame.longValue = nanos
            }
        }
        frame.longValue = frame.longValue + 1
    }

    val dot = remember(density) { PathEffect.dashPathEffect(floatArrayOf(0f, with(density) { 4.dp.toPx() }), 0f) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { state.widthPx = it.width.toFloat() }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        frame.longValue
        val bw = barWidth.toPx()
        val step = stepPx
        val barCount = floor(size.width / step).toInt()
        val centerY = size.height / 2f
        val data = state.staticBars

        val base = baseBarHeight.toPx()
        val r = barRadius.toPx()
        for (i in 0 until min(barCount, data.size)) {
            val value = if (data[i] == 0f) 0.1f else data[i]
            val x = i * step
            val bh = max(base, value * size.height * 0.8f)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - bh / 2f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(r),
                alpha = (0.4f + value * 0.6f).coerceIn(0f, 1f),
            )
        }

        if (fadeEdges && size.width > 0f) {
            val fp = min(0.3f, fadeWidth.toPx() / size.width)
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.White, fp to Color.Transparent, (1f - fp) to Color.Transparent, 1f to Color.White,
                ),
                blendMode = BlendMode.DstOut,
            )
        }

        // The idle rule is a sibling div in the original, so it is not edge-faded.
        if (!active && !processing) {
            drawLine(
                Color.White.copy(alpha = 0.2f),
                Offset(0f, centerY), Offset(size.width, centerY),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dot,
            )
        }
    }
}
