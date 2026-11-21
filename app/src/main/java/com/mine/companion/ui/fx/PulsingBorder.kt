package com.mine.companion.ui.fx

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.mine.companion.ui.theme.LocalFxActive
import kotlin.math.min
import kotlin.math.roundToInt

data class PulsingBorderParams(
    val colorBack: Color = Color.Transparent,
    val colors: List<Color> = listOf(Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF818CF8), Color(0xFFE0F2FE)),
    val roundness: Float = 0.18f,
    val thickness: Float = 0.085f,
    val margin: Float = 0f,
    val aspectSquare: Boolean = false,
    val softness: Float = 0.85f,
    val intensity: Float = 0.75f,
    val bloom: Float = 0.6f,
    val spots: Int = 3,
    val spotSize: Float = 0.55f,
    val pulse: Float = 0.7f,
    val smoke: Float = 0.55f,
    val smokeSize: Float = 0.85f,
    val speed: Float = 1f,
    val scale: Float = 1f,
)

private object NoiseTexture {
    val bitmap: Bitmap by lazy {
        val size = 100
        val px = IntArray(size * size)
        var seed = 1337L
        fun next(): Int {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return (seed and 0xFFL).toInt()
        }
        for (i in px.indices) {
            val r = next()
            val g = next()
            val b = next()
            next()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

private class LerpState(p: PulsingBorderParams) {
    var thickness = p.thickness
    var intensity = p.intensity
    var bloom = p.bloom
    var pulse = p.pulse
    var smoke = p.smoke
    var time = 0f
}

@Composable
fun PulsingBorder(params: PulsingBorderParams, modifier: Modifier = Modifier, resolution: Float = 0.5f) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PulsingBorder33(params, modifier, resolution.coerceIn(0.25f, 1f))
    else PulsingBorderFallback(params, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun PulsingBorder33(params: PulsingBorderParams, modifier: Modifier, resolution: Float) {
    val latest by rememberUpdatedState(params)
    val lerp = remember { LerpState(params) }
    val frame = remember { mutableLongStateOf(0L) }
    val shader = remember {
        RuntimeShader(PULSING_BORDER_AGSL).apply {
            val noise = BitmapShader(NoiseTexture.bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            noise.filterMode = BitmapShader.FILTER_MODE_NEAREST
            setInputShader("u_noise", noise)
        }
    }

    val fxActive = LocalFxActive.current
    LaunchedEffect(fxActive) {
        if (!fxActive) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = min(0.05f, (now - last) / 1_000_000_000f)
                last = now
                val p = latest
                lerp.time += dt * p.speed
                lerp.thickness += (p.thickness - lerp.thickness) * 0.1f
                lerp.intensity += (p.intensity - lerp.intensity) * 0.1f
                lerp.bloom += (p.bloom - lerp.bloom) * 0.1f
                lerp.pulse += (p.pulse - lerp.pulse) * 0.1f
                lerp.smoke += (p.smoke - lerp.smoke) * 0.1f
                frame.longValue = now
            }
        }
    }

    Box(modifier) {
        Spacer(
            Modifier
                .layout { measurable, constraints ->
                    // Render into a smaller offscreen buffer, then scale it up. Soft glow hides it.
                    val w = (constraints.maxWidth * resolution).roundToInt().coerceAtLeast(1)
                    val h = (constraints.maxHeight * resolution).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = 1f / resolution
                            scaleY = 1f / resolution
                            transformOrigin = TransformOrigin(0f, 0f)
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    }
                }
                .drawWithCache {
                    val brush = ShaderBrush(shader)
                    shader.setFloatUniform("u_resolution", size.width, size.height)
                    shader.setFloatUniform("u_px", 1f / min(size.width, size.height).coerceAtLeast(1f))
                    onDrawBehind {
                        frame.longValue
                        val p = latest
                        shader.setFloatUniform("u_scale", p.scale)
                        shader.setFloatUniform("u_time", lerp.time)
                        setColor(shader, "u_colorBack", p.colorBack)
                        for (i in 0 until 5) setColor(shader, "u_c$i", p.colors.getOrElse(i) { Color.Transparent })
                        shader.setFloatUniform("u_colorsCount", min(p.colors.size, 5).toFloat())
                        shader.setFloatUniform("u_roundness", p.roundness)
                        shader.setFloatUniform("u_thickness", lerp.thickness)
                        shader.setFloatUniform("u_marginLeft", p.margin)
                        shader.setFloatUniform("u_marginRight", p.margin)
                        shader.setFloatUniform("u_marginTop", p.margin)
                        shader.setFloatUniform("u_marginBottom", p.margin)
                        shader.setFloatUniform("u_aspectRatio", if (p.aspectSquare) 1f else 0f)
                        shader.setFloatUniform("u_softness", p.softness)
                        shader.setFloatUniform("u_intensity", lerp.intensity)
                        shader.setFloatUniform("u_bloom", lerp.bloom)
                        shader.setFloatUniform("u_spots", p.spots.toFloat())
                        shader.setFloatUniform("u_spotSize", p.spotSize)
                        shader.setFloatUniform("u_pulse", lerp.pulse)
                        shader.setFloatUniform("u_smoke", lerp.smoke)
                        shader.setFloatUniform("u_smokeSize", p.smokeSize)
                        drawRect(brush)
                    }
                },
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun setColor(shader: RuntimeShader, name: String, c: Color) {
    shader.setFloatUniform(name, c.red, c.green, c.blue, c.alpha)
}

@Composable
private fun PulsingBorderFallback(params: PulsingBorderParams, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val stroke = min(size.width, size.height) * params.thickness * 0.5f
            val brush = Brush.sweepGradient(params.colors.map { it.copy(alpha = 0.55f * params.intensity) } + params.colors.first().copy(alpha = 0.55f * params.intensity))
            val radius = min(size.width, size.height) * 0.5f * params.roundness
            onDrawBehind {
                drawRoundRect(brush, cornerRadius = CornerRadius(radius), style = Stroke(stroke))
            }
        },
    )
}
