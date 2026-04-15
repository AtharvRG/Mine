package com.anchor.mine.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.WarmCream
import kotlin.math.cos
import kotlin.math.sin

// ULTRA-OPTIMIZED AGSL FRAGMENT SHADER (Ported from WebGL)
private const val ORB_SHADER = """
    uniform float2 u_resolution;
    uniform float u_time;
    
    // CPU-calculated points
    uniform float2 u_p1;
    uniform float2 u_p2;
    uniform float2 u_p3;
    uniform float2 u_p4;
    uniform float2 u_p5;

    // 3D Simplex Noise
    float4 permute(float4 x){return mod(((x*34.0)+1.0)*x, 289.0);}
    float4 taylorInvSqrt(float4 r){return 1.79284291400159 - 0.85373472095314 * r;}

    float snoise(float3 v){
        const float2 C = float2(1.0/6.0, 1.0/3.0);
        const float4 D = float4(0.0, 0.5, 1.0, 2.0);
        float3 i  = floor(v + dot(v, C.yyy));
        float3 x0 = v - i + dot(i, C.xxx);
        float3 g = step(x0.yzx, x0.xyz);
        float3 l = 1.0 - g;
        float3 i1 = min( g.xyz, l.zxy );
        float3 i2 = max( g.xyz, l.zxy );
        float3 x1 = x0 - i1 + 1.0 * C.xxx;
        float3 x2 = x0 - i2 + 2.0 * C.xxx;
        float3 x3 = x0 - 1.0 + 3.0 * C.xxx;
        i = mod(i, 289.0 );
        float4 p = permute( permute( permute(
                   i.z + float4(0.0, i1.z, i2.z, 1.0 ))
                 + i.y + float4(0.0, i1.y, i2.y, 1.0 ))
                 + i.x + float4(0.0, i1.x, i2.x, 1.0 ));
        float n_ = 1.0/7.0; 
        float3 ns = n_ * D.wyz - D.xzx;
        float4 j = p - 49.0 * floor(p * ns.z *ns.z);  
        float4 x_ = floor(j * ns.z);
        float4 y_ = floor(j - 7.0 * x_ );    
        float4 x = x_ *ns.x + ns.yyyy;
        float4 y = y_ *ns.x + ns.yyyy;
        float4 h = 1.0 - abs(x) - abs(y);
        float4 b0 = float4( x.xy, y.xy );
        float4 b1 = float4( x.zw, y.zw );
        float4 s0 = floor(b0)*2.0 + 1.0;
        float4 s1 = floor(b1)*2.0 + 1.0;
        float4 sh = -step(h, float4(0.0));
        float4 a0 = b0.xzyw + s0.xzyw*sh.xxyy ;
        float4 a1 = b1.xzyw + s1.xzyw*sh.zzww ;
        float3 p0 = float3(a0.xy,h.x);
        float3 p1 = float3(a0.zw,h.y);
        float3 p2 = float3(a1.xy,h.z);
        float3 p3 = float3(a1.zw,h.w);
        float4 norm = taylorInvSqrt(float4(dot(p0,p0), dot(p1,p1), dot(p2, p2), dot(p3,p3)));
        p0 *= norm.x;
        p1 *= norm.y;
        p2 *= norm.z;
        p3 *= norm.w;
        float4 m = max(0.48 - float4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
        m = m * m;
        return 42.0 * dot( m*m, float4( dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3) ) );
    }

    float random(float2 st) {
        return fract(sin(dot(st.xy, float2(12.9898,78.233))) * 43758.5453123);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord.xy / u_resolution.xy;
        float2 centeredUV = uv * 2.0 - 1.0;
        
        float aspect = u_resolution.x / u_resolution.y;
        if (aspect > 1.0) {
            centeredUV.x *= aspect; 
        } else {
            centeredUV.y /= aspect; 
        }

        float warpAmount = 0.50;
        float spread = 0.65;
        float radius = 0.65;
        
        // Hardcoded LAB Values from the React Component
        float3 col1 = float3(1.000, 0.957, 0.894); // #fff4e4
        float3 col2 = float3(1.000, 0.878, 0.749); // #ffe0bf
        float3 col3 = float3(0.949, 0.980, 1.000); // #f2faff
        float3 col4 = float3(1.000, 0.922, 0.855); // #ffebda
        float3 col5 = float3(1.000, 1.000, 1.000); // #ffffff
        float3 glassBase = float3(0.031, 0.200, 0.216); // #083337

        float2 warpedUV = centeredUV;
        warpedUV.x += snoise(float3(centeredUV * 2.0, u_time * 0.1)) * warpAmount;
        warpedUV.y += snoise(float3(centeredUV * 2.0 + 10.0, u_time * 0.1)) * warpAmount;

        float d1 = length(warpedUV - u_p1);
        float d2 = length(warpedUV - u_p2);
        float d3 = length(warpedUV - u_p3);
        float d4 = length(warpedUV - u_p4);
        float d5 = length(warpedUV - u_p5);

        float w1 = smoothstep(spread, 0.0, d1);
        float w2 = smoothstep(spread, 0.0, d2);
        float w3 = smoothstep(spread, 0.0, d3);
        float w4 = smoothstep(spread, 0.0, d4);
        float w5 = smoothstep(spread * 1.2, 0.0, d5);

        float W_sum = w1 + w2 + w3 + w4 + w5 + 0.0001;
        float3 fluidColor = (col1 * w1 + col2 * w2 + col3 * w3 + col4 * w4 + col5 * w5) / W_sum;
        
        float totalDensity = clamp(W_sum * 1.2, 0.0, 1.0);
        float3 orbInterior = mix(glassBase, fluidColor, totalDensity);

        float distFromCenter = length(centeredUV);
        float mask = smoothstep(radius, radius - 0.015, distFromCenter);
        
        // Liquid Retina Noise
        float3 finalColor = orbInterior + (random(fragCoord.xy + u_time) - 0.5) * 0.012;

        // Output with alpha mask for true transparent composition
        return half4(finalColor * mask, mask);
    }
"""

// CPU Point Generator (Translated from JS)
private fun getPoint(t: Float, index: Float): Pair<Float, Float> {
    val freqX = 0.31f + index * 0.17f
    val freqY = 0.43f + index * 0.23f
    val orbitSize = 0.50f

    val nX = sin(t * 0.8f + index * 12.1f) * cos(t * 0.5f + index * 5.3f) * 0.25f
    val nY = sin(t * 0.7f + index * 8.2f) * cos(t * 0.6f + index * 3.5f) * 0.25f

    val x = sin(t * freqX + index * 2.0f) * orbitSize + cos(t * (freqX * 0.5f)) * (orbitSize * 0.3f) + nX
    val y = cos(t * freqY + index * 3.0f) * orbitSize + sin(t * (freqY * 0.5f)) * (orbitSize * 0.3f) + nY

    return Pair(x, y)
}

@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    isListening: Boolean = true
) {
    // Target speeds: 0.5 for Idle, 2.5 for Active Listening
    val targetSpeed = if (isListening) 2.5f else 0.5f
    val currentSpeed by animateFloatAsState(
        targetValue = targetSpeed,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "speed"
    )

    // The Delta Time Accumulator (Ensures smooth speed changes without jumping)
    var shaderTime by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrameTime = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameTime ->
                val deltaSeconds = (frameTime - lastFrameTime) / 1_000_000_000f
                lastFrameTime = frameTime
                shaderTime += deltaSeconds * currentSpeed
            }
        }
    }

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = remember { RuntimeShader(ORB_SHADER) }
            val shaderBrush = remember { ShaderBrush(shader) }

            Canvas(modifier = Modifier.fillMaxSize()) {
                shader.setFloatUniform("u_resolution", size.width, size.height)
                shader.setFloatUniform("u_time", shaderTime)

                // Calculate the 5 points on the CPU and pass them to the GPU
                val p1 = getPoint(shaderTime, 1.0f)
                val p2 = getPoint(shaderTime, 2.0f)
                val p3 = getPoint(shaderTime, 3.0f)
                val p4 = getPoint(shaderTime, 4.0f)
                val p5 = getPoint(shaderTime, 5.0f)

                shader.setFloatUniform("u_p1", p1.first, p1.second)
                shader.setFloatUniform("u_p2", p2.first, p2.second)
                shader.setFloatUniform("u_p3", p3.first, p3.second)
                shader.setFloatUniform("u_p4", p4.first, p4.second)
                shader.setFloatUniform("u_p5", p5.first, p5.second)

                drawRect(brush = shaderBrush)
            }
        } else {
            // Fallback for older Androids
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = 1f + (sin(shaderTime * 2.0f) * 0.05f)
                drawCircle(color = WarmCream.copy(alpha = 0.5f), radius = size.width / 3f * scale)
            }
        }
    }
}