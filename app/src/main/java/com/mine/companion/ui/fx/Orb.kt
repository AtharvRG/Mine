package com.mine.companion.ui.fx

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.mine.companion.ui.theme.LocalFxActive
import com.mine.companion.ui.theme.MineMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

enum class AgentState { Thinking, Listening, Talking }

private object PerlinTexture {
    const val SIZE = 256

    @Volatile
    private var cached: Bitmap? = null

    fun get(): Bitmap = cached ?: synchronized(this) { cached ?: generate().also { cached = it } }

    private fun generate(): Bitmap {
        val rnd = java.util.Random(0x6d696e65)
        val perm = IntArray(512)
        val base = (0 until 256).shuffled(kotlin.random.Random(rnd.nextLong()))
        for (i in 0 until 512) perm[i] = base[i and 255]

        fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
        fun grad(h: Int, x: Float, y: Float): Float = when (h and 7) {
            0 -> x + y; 1 -> -x + y; 2 -> x - y; 3 -> -x - y
            4 -> x; 5 -> -x; 6 -> y; else -> -y
        }
        fun noise(x: Float, y: Float, period: Int): Float {
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()
            val xf = x - xi
            val yf = y - yi
            val x0 = ((xi % period) + period) % period
            val y0 = ((yi % period) + period) % period
            val x1 = (x0 + 1) % period
            val y1 = (y0 + 1) % period
            val aa = perm[perm[x0] + y0]
            val ab = perm[perm[x0] + y1]
            val ba = perm[perm[x1] + y0]
            val bb = perm[perm[x1] + y1]
            val u = fade(xf)
            val v = fade(yf)
            val l1 = grad(aa, xf, yf) + u * (grad(ba, xf - 1f, yf) - grad(aa, xf, yf))
            val l2 = grad(ab, xf, yf - 1f) + u * (grad(bb, xf - 1f, yf - 1f) - grad(ab, xf, yf - 1f))
            return l1 + v * (l2 - l1)
        }

        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                var sum = 0f
                var amp = 0.5f
                var freq = 4
                var norm = 0f
                repeat(4) {
                    sum += amp * noise(x * freq / SIZE.toFloat(), y * freq / SIZE.toFloat(), freq)
                    norm += amp
                    amp *= 0.5f
                    freq *= 2
                }
                val v = ((sum / norm) * 0.75f + 0.5f).coerceIn(0f, 1f)
                val g = (v * 255f).toInt()
                px[y * SIZE + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

private class OrbState(seed: Int?) {
    var time = 0f
    var animation = 0.1f
    var opacity = 0f
    var curIn = 0f
    var curOut = 0f
    var animSpeed = 0.1f
    val c1 = FloatArray(3)
    val c2 = FloatArray(3)
    var colorsInit = false
    val offsets: FloatArray = run {
        val r = if (seed != null) Random(seed) else Random.Default
        FloatArray(7) { r.nextFloat() * PI.toFloat() * 2f }
    }
}

@Composable
fun Orb(
    colors: Pair<Color, Color>,
    agentState: AgentState?,
    modifier: Modifier = Modifier,
    seed: Int? = null,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Orb33(colors, agentState, modifier, seed)
    else OrbFallback(colors, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Orb33(colors: Pair<Color, Color>, agentState: AgentState?, modifier: Modifier, seed: Int?) {
    val texture by produceState<Bitmap?>(null) { value = withContext(Dispatchers.Default) { runCatching { PerlinTexture.get() }.getOrNull() } }
    val tex = texture
    if (tex == null) {
        OrbFallback(colors, modifier)
        return
    }
    val latestColors by rememberUpdatedState(colors)
    val latestAgent by rememberUpdatedState(agentState)
    val s = remember(seed) { OrbState(seed) }
    val frame = remember { mutableLongStateOf(0L) }
    val shader = remember(tex) {
        runCatching {
            RuntimeShader(ORB_AGSL).apply {
                val bs = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
                setInputShader("uPerlin", bs)
                setFloatUniform("uTexSize", PerlinTexture.SIZE.toFloat())
                setFloatUniform("uInverted", 1f)
            }
        }.getOrNull()
    }
    if (shader == null) {
        OrbFallback(colors, modifier)
        return
    }

    val fxActive = LocalFxActive.current
    LaunchedEffect(fxActive) {
        if (!fxActive) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val delta = min(0.1f, (now - last) / 1_000_000_000f)
                last = now
                s.time += delta * 0.5f
                if (s.opacity < 1f) s.opacity = min(1f, s.opacity + delta * 2f)

                val t = s.time * 2f
                val (targetIn, targetOut) = when (latestAgent) {
                    null -> 0f to 0.3f
                    AgentState.Listening -> (0.55f + sin(t * 3.2f) * 0.35f).coerceIn(0f, 1f) to 0.45f
                    AgentState.Talking -> (0.65f + sin(t * 4.8f) * 0.22f).coerceIn(0f, 1f) to
                        (0.75f + sin(t * 3.6f) * 0.22f).coerceIn(0f, 1f)
                    AgentState.Thinking -> {
                        val baseV = 0.38f + 0.07f * sin(t * 0.7f)
                        val wander = 0.05f * sin(t * 2.1f) * sin(t * 0.37f + 1.2f)
                        (baseV + wander).coerceIn(0f, 1f) to (0.48f + 0.12f * sin(t * 1.05f + 0.6f)).coerceIn(0f, 1f)
                    }
                }
                s.curIn += (targetIn - s.curIn) * 0.2f
                s.curOut += (targetOut - s.curOut) * 0.2f
                val targetSpeed = 0.1f + (1f - (s.curOut - 1f).pow(2)) * 0.9f
                s.animSpeed += (targetSpeed - s.animSpeed) * 0.12f
                s.animation += delta * s.animSpeed

                val (a, b) = latestColors
                val t1 = floatArrayOf(srgbToLinear(a.red), srgbToLinear(a.green), srgbToLinear(a.blue))
                val t2 = floatArrayOf(srgbToLinear(b.red), srgbToLinear(b.green), srgbToLinear(b.blue))
                if (!s.colorsInit) {
                    t1.copyInto(s.c1); t2.copyInto(s.c2); s.colorsInit = true
                } else {
                    for (i in 0..2) {
                        s.c1[i] += (t1[i] - s.c1[i]) * 0.08f
                        s.c2[i] += (t2[i] - s.c2[i]) * 0.08f
                    }
                }
                frame.longValue = now
            }
        }
    }

    Spacer(
        modifier.drawWithCache {
            val brush = runCatching { ShaderBrush(shader) }.getOrNull()
            val half = min(size.width, size.height) / 2f
            val radiusPx = half * (3.5f / (5f * tan(37.5f * PI.toFloat() / 180f)))
            if (brush != null) {
                runCatching {
                    shader.setFloatUniform("uCenter", size.width / 2f, size.height / 2f)
                    shader.setFloatUniform("uRadiusPx", radiusPx)
                    shader.setFloatUniform("uOffA", s.offsets[0], s.offsets[1], s.offsets[2], s.offsets[3])
                    shader.setFloatUniform("uOffB", s.offsets[4], s.offsets[5], s.offsets[6], 0f)
                }
            }
            onDrawBehind {
                frame.longValue
                var drawn = false
                if (brush != null) {
                    drawn = runCatching {
                        shader.setFloatUniform("uTime", s.time)
                        shader.setFloatUniform("uAnimation", s.animation)
                        shader.setFloatUniform("uColor1", s.c1[0], s.c1[1], s.c1[2])
                        shader.setFloatUniform("uColor2", s.c2[0], s.c2[1], s.c2[2])
                        shader.setFloatUniform("uInputVolume", s.curIn)
                        shader.setFloatUniform("uOutputVolume", s.curOut)
                        shader.setFloatUniform("uOpacity", s.opacity)
                        drawRect(brush)
                        true
                    }.getOrDefault(false)
                }
                if (!drawn) {
                    val r = half * 0.912f
                    drawCircle(colors.first, radius = r)
                }
            }
        },
    )
}

@Composable
private fun OrbFallback(colors: Pair<Color, Color>, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "orb-fallback")
    val phase by t.animateFloat(
        0f, 1f,
        InfiniteRepeatableSpec(tween(6000, easing = MineMotion.Linear), RepeatMode.Restart),
        label = "orb-phase",
    )
    Spacer(
        modifier.drawWithCache {
            val r = min(size.width, size.height) / 2f * 0.912f
            onDrawBehind {
                val a = phase * 2f * PI.toFloat()
                val c = Offset(size.width / 2f + kotlin.math.cos(a) * r * 0.25f, size.height / 2f + sin(a) * r * 0.25f)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, colors.first, colors.second, Color.Black), center = c, radius = r * 1.3f),
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        },
    )
}
