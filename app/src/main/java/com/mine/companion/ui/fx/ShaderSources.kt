package com.mine.companion.ui.fx

const val PRISMATIC_PORTAL_AGSL = """
uniform float uDpr;     // device px per dp
uniform float4 uRect;   // portal rect (x, y, w, h) in dp, y down
uniform float uRadius;  // corner radius dp
uniform float uPhase;   // 0 -> 1
uniform float uTime;    // seconds

float sat(float x) { return clamp(x, 0.0, 1.0); }

float bell(float a, float b, float c, float d, float x) {
  return smoothstep(a, b, x) * (1.0 - smoothstep(c, d, x));
}

float sdRoundBox(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float hash12(float2 p) {
  float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  float a = hash12(i);
  float b = hash12(i + float2(1.0, 0.0));
  float c = hash12(i + float2(0.0, 1.0));
  float d = hash12(i + float2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float seg(float s, float k) {
  float t = sat(s - k);
  return t * t * (3.0 - 2.0 * t);
}

float3 blueResponse(float x) {
  float s = sat(x) * 8.0;
  float3 c = float3(0.027, 0.027, 0.035);
  c = mix(c, float3(0.000, 0.043, 0.184), seg(s, 0.0));
  c = mix(c, float3(0.039, 0.094, 0.459), seg(s, 1.0));
  c = mix(c, float3(0.095, 0.235, 0.700), seg(s, 2.0));
  c = mix(c, float3(0.255, 0.440, 0.830), seg(s, 3.0));
  c = mix(c, float3(0.540, 0.620, 0.930), seg(s, 4.0));
  c = mix(c, float3(0.745, 0.790, 0.950), seg(s, 5.0));
  c = mix(c, float3(0.855, 0.890, 0.960), seg(s, 6.0));
  c = mix(c, float3(0.950, 0.965, 1.000), seg(s, 7.0));
  return c;
}

half4 main(float2 fragCoord) {
  float T = uPhase;
  float2 p = fragCoord / uDpr;

  float2 hs = 0.5 * uRect.zw;
  float2 ctr = uRect.xy + hs;
  float rad = min(uRadius, min(hs.x, hs.y));
  float sd = sdRoundBox(p - ctr, hs, rad);

  float cover = sat(0.5 - sd * uDpr);
  if (cover <= 0.0 || T <= 0.0005) {
    return half4(0.0);
  }

  float inset = max(-sd, 0.0);
  float2 uv = (p - uRect.xy) / uRect.zw;

  float he = min(uRect.w, 1.15 * uRect.z);
  float boxBottom = uRect.y + uRect.w - (uRect.w - he) * 0.35;
  float boxTop = boxBottom - he;
  float yEff = (p.y - boxTop) / he;
  float yMin = (uRect.y - boxTop) / he;
  float yMax = (uRect.y + uRect.w - boxTop) / he;

  float t = uTime;
  float2 drift = float2(
    vnoise(uv * float2(2.2, 1.6) + float2(0.19 * t, -0.13 * t)),
    vnoise(uv * float2(1.7, 2.4) + float2(5.3 - 0.17 * t, 1.9 + 0.21 * t))
  ) - 0.5;
  float2 q = float2(uv.x, yEff) + drift * 0.035;

  float X = (q.x - 0.5) * 2.0;
  float Y = q.y;

  // 1. fill level
  float fill = smoothstep(0.0, 0.08, T);
  float level = 1.04 - fill * 1.25;
  float fillMask = smoothstep(level - 0.08, level + 0.08, uv.y + drift.x * 0.05);

  // 2. source anchor
  float flatK = smoothstep(0.50, 0.72, T);
  float srcY = mix(1.00, 0.80, smoothstep(0.01, 0.18, T));
  srcY = mix(srcY, 0.68, flatK);

  // 3. cooling dome
  float2 dC = float2(X * 0.86, (Y - srcY) / 0.86);
  float dc = length(dC);
  float cavFar = length(float2(0.86, (srcY - yMin) / 0.86));
  float cavR = 0.46 + max(1.64, cavFar + 0.04) * smoothstep(0.24, 0.86, T);
  float cavOn = bell(0.22, 0.38, 0.90, 1.00, T);
  float cav = (1.0 - smoothstep(cavR - 0.42, cavR, dc)) * cavOn;
  float depth = max(cavR - dc, 0.0);
  float crest = 0.5 + 0.5 * cos(depth / 0.25 * 3.14159);
  float ripple = (crest * crest * crest * 2.0 - 0.45) * exp(-depth / 0.80);
  float frontCTerm = (dc - cavR + 0.10) / 0.16;
  float frontC = exp(-(frontCTerm * frontCTerm)) * cavOn;

  // 4. warm source light
  float sRx = mix(0.85, 1.10, smoothstep(0.04, 0.30, T));
  float sRy = mix(0.38, 0.36, smoothstep(0.04, 0.34, T));
  float2 dS = float2(X / sRx, (Y - srcY) / sRy);
  float S1 = exp(-dot(dS, dS)) * 1.40 * bell(0.0, 0.16, 0.46, 0.64, T);
  S1 *= mix(1.0, 0.45, smoothstep(0.0, 0.22, Y - srcY));
  float2 dK = float2(X / 0.62, (Y - srcY) / 0.15);
  float coreKeep = exp(-dot(dK, dK));
  S1 *= 1.0 - 0.96 * cav * (1.0 - 0.85 * coreKeep);

  float2 dH = float2(X / 1.45, (Y - srcY) / 0.052);
  float hD2 = dot(dH, dH);
  float S2 = exp(-hD2) * 3.2 * bell(0.54, 0.68, 0.86, 0.97, T);

  // 5. spectral absorption
  float dRx = mix(0.34, 0.56, smoothstep(0.06, 0.40, T));
  float dRy = mix(0.10, 0.125, smoothstep(0.06, 0.40, T));
  float lobe = mix(0.14, 0.22, smoothstep(0.06, 0.40, T));
  float dyD = (Y - (srcY - 0.02)) / dRy;
  float2 dL = float2((X - lobe) / dRx, dyD);
  float2 dRt = float2((X + lobe) / dRx, dyD);
  float D1 = 0.62 * (exp(-pow(max(dot(dL, dL), 0.0), 1.25)) + exp(-pow(max(dot(dRt, dRt), 0.0), 1.25)));
  D1 *= (1.25 * smoothstep(0.10, 0.30, T) + 0.95 * smoothstep(0.28, 0.46, T)) * (1.0 - smoothstep(0.56, 0.70, T));
  float2 dSh = dS * float2(1.25, 1.6);
  D1 += 0.25 * exp(-pow(max(dot(dSh, dSh), 0.0), 1.1)) * bell(0.04, 0.18, 0.44, 0.62, T);

  float D2 = exp(-pow(max(hD2 / 0.70, 0.0), 1.1)) * 0.55 * bell(0.56, 0.68, 0.86, 0.97, T);

  // 6. blue exposure field
  float ambOut = 0.32 * smoothstep(0.0, 0.07, T)
               + 0.19 * bell(0.03, 0.20, 0.46, 0.64, T) * mix(1.0, 0.85, smoothstep(0.4, 1.0, Y));
  float ambIn = mix(0.42, 0.33, smoothstep(0.55, 0.75, T)) + 0.10 * ripple;
  float dcNorm = dc / 0.62;
  ambIn -= 0.20 * exp(-(dcNorm * dcNorm)) * bell(0.26, 0.40, 0.62, 0.74, T);
  float aboveH = (Y - (srcY - 0.13)) / 0.11;
  ambIn -= 0.20 * exp(-aboveH * aboveH) * bell(0.52, 0.64, 0.84, 0.95, T);
  float belowH = (Y - (srcY + 0.10)) / 0.10;
  ambIn += 0.12 * exp(-belowH * belowH) * bell(0.52, 0.64, 0.84, 0.95, T);
  float amb = mix(ambOut, ambIn, cav) + 0.10 * frontC;

  float rimW = mix(0.11, 0.07, smoothstep(0.5, 0.75, T)) * min(uRect.z, uRect.w) + 3.0;
  float2 eA = max(p - uRect.xy, 0.0) / rimW;
  float2 eB = max(uRect.xy + uRect.zw - p, 0.0) / rimW;
  float rimSum = exp(-eA.x) + exp(-eB.x) + exp(-eA.y) + exp(-eB.y);
  float rim = min((1.0 - exp(-rimSum)) / 0.632, 1.25);
  float rimAmp = 0.34 * smoothstep(0.06, 0.34, T) * (1.0 - smoothstep(0.92, 1.0, T)) * mix(0.75, 1.3, uv.y);
  float spec = exp(-inset / 1.6) * 0.30 * smoothstep(0.10, 0.30, T) * (1.0 - smoothstep(0.90, 1.0, T));

  float I = (amb + rimAmp * rim + spec) * fillMask;

  // 7. reveal
  float2 dV = float2(X / 0.92, (Y - srcY) / 0.34);
  float dv = length(dV);
  float rv = smoothstep(0.76, 1.0, T);
  float dvFar = length(float2(1.0 / 0.92, max(srcY - yMin, yMax - srcY) / 0.34));
  float revR = (dvFar + 0.85) * pow(max(rv, 0.0), 1.45);
  float feather = 1.0;
  float hole = 1.0 - smoothstep(revR - feather, revR, dv);
  hole *= smoothstep(0.76, 0.80, T);
  hole *= 1.0 - rim * 0.85 * (1.0 - smoothstep(0.95, 1.0, T));

  float frontBand = (dv - revR + feather * 0.45) / 0.22;
  float front = exp(-frontBand * frontBand) * bell(0.76, 0.81, 0.90, 0.99, T);

  float Ssum = S1 + S2 + front * 0.9;
  float Dsum = D1 + D2 + front * 0.5;

  // compose
  float3 col = blueResponse(I);
  float Sx = Ssum * fillMask;
  col = 1.0 - (1.0 - col) * exp(-Sx * float3(0.70, 1.00, 1.12));
  col *= 1.0 + 0.85 * smoothstep(0.0, 1.4, D1 + D2) * fillMask;
  float Dx = Dsum * fillMask;
  col *= exp(-float3(0.08 * Dx + 0.045 * Dx * Dx, 0.45 * Dx + 0.12 * Dx * Dx, 1.00 * Dx));
  col = clamp(col, 0.0, 1.0);

  // ordered dither
  col += (hash12(fragCoord + fract(t) * 61.0) - 0.5) / 255.0;

  float alpha = cover * (1.0 - hole) * (1.0 - smoothstep(0.97, 1.0, T));
  return half4(half3(col * alpha), half(alpha));
}
"""

const val PULSING_BORDER_AGSL = """ uniform float2 u_resolution; uniform float
u_px; uniform float u_scale; uniform float u_time; uniform float4 u_colorBack;
uniform float4 u_c0; uniform float4 u_c1; uniform float4 u_c2; uniform float4
u_c3; uniform float4 u_c4; uniform float u_colorsCount; uniform float
u_roundness; uniform float u_thickness; uniform float u_marginLeft; uniform
float u_marginRight; uniform float u_marginTop; uniform float u_marginBottom;
uniform float u_aspectRatio; uniform float u_softness; uniform float
u_intensity; uniform float u_bloom; uniform float u_spotSize; uniform float
u_spots; uniform float u_pulse; uniform float u_smoke; uniform float
u_smokeSize; uniform shader u_noise;

const float PI = 3.14159265359; const float TWO_PI = 6.28318530718;

float4 colorAt(int i) { if (i == 0) return u_c0; if (i == 1) return u_c1; if (i
== 2) return u_c2; if (i == 3) return u_c3; return u_c4; }

float4 tex(float2 uv) { float2 texel = floor(fract(uv) * 100.0) + 0.5; return
float4(u_noise.eval(texel)); }

float2 randomGB(float2 p) { float2 uv = floor(p) / 100.0 + 0.5; return
tex(uv).gb; }

float randomG(float2 p) { float2 uv = floor(p) / 100.0 + 0.5; return tex(uv).g;
}

float valueNoise(float2 st) { float2 i = floor(st); float2 f = fract(st); float
a = randomG(i); float b = randomG(i + float2(1.0, 0.0)); float c = randomG(i +
float2(0.0, 1.0)); float d = randomG(i + float2(1.0, 1.0)); float2 u = f * f *
(3.0 - 2.0 * f); float x1 = mix(a, b, u.x); float x2 = mix(c, d, u.x); return
mix(x1, x2, u.y); }

float beat(float time) { float first = pow(abs(sin(time * TWO_PI)), 10.0); float
second = pow(abs(sin((time - 0.15) * TWO_PI)), 10.0); return clamp(first + 0.6 *
second, 0.0, 1.0); }

float roundedBox(float2 uv, float2 halfSize, float dist, float cornerDistance,
float thickness, float softness) { float borderDistance = abs(dist); float aa
= 2.0 * u_px; float border = 1.0 - smoothstep(min(mix(thickness, -thickness,
softness), thickness + aa), max(mix(thickness, -thickness, softness), thickness
+ aa), borderDistance); float cornerFadeCircles = 0.0; cornerFadeCircles =
mix(1.0, cornerFadeCircles, smoothstep(0.0, 1.0, length((uv + halfSize) /
thickness))); cornerFadeCircles = mix(1.0, cornerFadeCircles,
smoothstep(0.0, 1.0, length((uv - float2(-halfSize.x, halfSize.y)) /
thickness))); cornerFadeCircles = mix(1.0, cornerFadeCircles,
smoothstep(0.0, 1.0, length((uv - float2(halfSize.x, -halfSize.y)) /
thickness))); cornerFadeCircles = mix(1.0, cornerFadeCircles,
smoothstep(0.0, 1.0, length((uv - halfSize) / thickness))); aa = u_px; float
cornerFade = smoothstep(0.0, mix(aa, thickness, softness), cornerDistance);
cornerFade *= cornerFadeCircles; border += cornerFade; return border; }

half4 main(float2 fragCoord) { float2 uvBase = fragCoord / u_resolution - 0.5;
float canvasRatio = u_resolution.x / max(u_resolution.y, 1.0); float2 aspectUV =
uvBase; aspectUV.x *= max(canvasRatio, 1.0); aspectUV.y /=
min(canvasRatio, 1.0); float2 patternUV = aspectUV * max(u_scale, 0.01);

float firstFrameOffset = 109.0; float t = 1.2 * (u_time + firstFrameOffset);

float2 borderUV = uvBase; float pulse = u_pulse * beat(0.18 * u_time);

float2 halfSize = float2(0.5); borderUV.x *= max(canvasRatio, 1.0); borderUV.y
/= min(canvasRatio, 1.0); halfSize.x *= max(canvasRatio, 1.0); halfSize.y /=
min(canvasRatio, 1.0);

float mL = u_marginLeft; float mR = u_marginRight; float mT = u_marginTop; float
mB = u_marginBottom; float mX = mL + mR; float mY = mT + mB;

if (u_aspectRatio > 0.0) { float shapeRatio = canvasRatio * (1.0 - mX) / max(1.0
- mY, 1e-6); float freeX = shapeRatio > 1.0 ? (1.0 - mX) * (1.0 - 1.0 /
max(abs(shapeRatio), 1e-6)) : 0.0; float freeY = shapeRatio < 1.0 ? (1.0 - mY) *
(1.0 - shapeRatio) : 0.0; mL += freeX * 0.5; mR += freeX * 0.5; mT += freeY
* 0.5; mB += freeY * 0.5; mX = mL + mR; mY = mY + mT + mB - mY; }

float thickness = 0.5 * u_thickness * min(halfSize.x, halfSize.y);

halfSize.x *= (1.0 - mX); halfSize.y *= (1.0 - mY);

float2 centerShift = float2( (mL - mR) * max(canvasRatio, 1.0) * 0.5, (mB - mT)
/ min(canvasRatio, 1.0) * 0.5 );

borderUV -= centerShift; halfSize -= mix(thickness, 0.0, u_softness);

float radius = mix(0.0, min(halfSize.x, halfSize.y), u_roundness); float2 d =
abs(borderUV) - halfSize + radius; float outsideDistance =
length(max(d, 0.0001)) - radius; float insideDistance = min(max(d.x,
d.y), 0.0001); float cornerDistance = abs(min(max(d.x, d.y) - 0.45 *
radius, 0.0)); float dist = outsideDistance + insideDistance;

float borderThickness = mix(thickness, 3.0 * thickness, u_softness); float
border = roundedBox(borderUV, halfSize, dist, cornerDistance, borderThickness,
u_softness); border = pow(border, 1.0 + u_softness);

float2 smokeUV = 0.3 * u_smokeSize * patternUV; float smoke = clamp(3.0 *
valueNoise(2.7 * smokeUV + 0.5 * t), 0.0, 1.0); smoke -= valueNoise(3.4 *
smokeUV - 0.5 * t); float smokeThickness = thickness + 0.2; smokeThickness =
min(0.4, max(smokeThickness, 0.1)); smoke *= roundedBox(borderUV, halfSize,
dist, cornerDistance, smokeThickness, 1.0); smoke = 30.0 * smoke * smoke; smoke
*= mix(0.0, 0.5, pow(u_smoke, 2.0)); smoke *= mix(1.0, pulse, u_pulse); smoke =
clamp(smoke, 0.0, 1.0); border += smoke;

border = clamp(border, 0.0, 1.0);

float3 blendColor = float3(0.0); float blendAlpha = 0.0; float3 addColor =
float3(0.0); float addAlpha = 0.0;

float bloom = 4.0 * u_bloom; float intensity = 1.0 + (1.0 + 4.0 * u_softness) *
u_intensity;

float angle = atan(borderUV.y, borderUV.x) / TWO_PI;

for (int colorIdx = 0; colorIdx < 5; colorIdx++) { float colorIdxF =
float(colorIdx); if (colorIdxF < u_colorsCount) { float4 cc = colorAt(colorIdx);
float3 c = cc.rgb * cc.a; float a = cc.a;

  for (int spotIdx = 0; spotIdx < 4; spotIdx++) {
    float spotIdxF = float(spotIdx);
    if (spotIdxF < u_spots) {
      float2 randVal = randomGB(float2(spotIdxF * 10.0 + 2.0, 40.0 + colorIdxF));

      float time = (0.1 + 0.15 * abs(sin(spotIdxF * (2.0 + colorIdxF)) * cos(spotIdxF * (2.0 + 2.5 * colorIdxF)))) * t + randVal.x * 3.0;
      time *= mix(1.0, -1.0, step(0.5, randVal.y));

      float mask = 0.5 + 0.5 * mix(
        sin(t + spotIdxF * (5.0 - 1.5 * colorIdxF)),
        cos(t + spotIdxF * (3.0 + 1.3 * colorIdxF)),
        step(mod(colorIdxF, 2.0), 0.5)
      );

      float p = clamp(2.0 * u_pulse - randVal.x, 0.0, 1.0);
      mask = mix(mask, pulse, p);

      float atg1 = fract(angle + time);
      float spotSize = 0.05 + 0.6 * pow(u_spotSize, 2.0) + 0.05 * randVal.x;
      spotSize = mix(spotSize, 0.1, p);
      float sector = smoothstep(0.5 - spotSize, 0.5, atg1) * (1.0 - smoothstep(0.5, 0.5 + spotSize, atg1));

      sector *= mask;
      sector *= border;
      sector *= intensity;
      sector = clamp(sector, 0.0, 1.0);

      float3 srcColor = c * sector;
      float srcAlpha = a * sector;

      blendColor += ((1.0 - blendAlpha) * srcColor);
      blendAlpha = blendAlpha + (1.0 - blendAlpha) * srcAlpha;
      addColor += srcColor;
      addAlpha += srcAlpha;
    }
  }
}

}

float3 accumColor = mix(blendColor, addColor, bloom); float accumAlpha =
mix(blendAlpha, addAlpha, bloom); accumAlpha = clamp(accumAlpha, 0.0, 1.0);

float3 bgColor = u_colorBack.rgb * u_colorBack.a; float3 color = accumColor +
(1.0 - accumAlpha) * bgColor; float opacity = accumAlpha + (1.0 - accumAlpha) *
u_colorBack.a;

color += (fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453)
- 0.5) / 255.0;

return half4(half3(color), half(opacity)); } """

const val ORB_AGSL = """ uniform float2 uCenter; uniform float uRadiusPx;
uniform float uTime; uniform float uAnimation; uniform float uInverted; uniform
float4 uOffA; uniform float4 uOffB; uniform float3 uColor1; uniform float3
uColor2; uniform float uInputVolume; uniform float uOutputVolume; uniform float
uOpacity; uniform float uTexSize; uniform shader uPerlin;

const float PI = 3.14159265358979323846;

float perlin(float2 uv) { return float(uPerlin.eval(uv * uTexSize).r); }

float offsetAt(int i) { if (i == 0) return uOffA.x; if (i == 1) return uOffA.y;
if (i == 2) return uOffA.z; if (i == 3) return uOffA.w; if (i == 4) return
uOffB.x; if (i == 5) return uOffB.y; return uOffB.z; }

// drawOval with the out colour folded into the return: a < 0 means "not drawn"
float4 drawOval(float2 polarUv, float2 polarCenter, float a, float b, bool
reverseGradient, float softness) { float2 p = polarUv - polarCenter; float oval
= (p.x * p.x) / (a * a) + (p.y * p.y) / (b * b); float edge =
smoothstep(1.0, 1.0 - softness, oval); if (edge > 0.0) { float gradient =
reverseGradient ? (1.0 - (p.x / a + 1.0) / 2.0) : ((p.x / a + 1.0) / 2.0);
gradient = mix(0.5, gradient, 0.1); return float4(float3(gradient), 0.85 *
edge); } return float4(0.0, 0.0, 0.0, -1.0); }

float3 colorRamp(float grayscale, float3 color1, float3 color2, float3 color3,
float3 color4) { if (grayscale < 0.33) { return mix(color1, color2, grayscale
* 3.0); } else if (grayscale < 0.66) { return mix(color2, color3, (grayscale
- 0.33) * 3.0); } return mix(color3, color4, (grayscale - 0.66) * 3.0); }

float2 hash2(float2 p) { return fract(sin(float2(dot(p, float2(127.1, 311.7)),
dot(p, float2(269.5, 183.3)))) * 43758.5453); }

float noise2D(float2 p) { float2 i = floor(p); float2 f = fract(p); float2 u = f
* f * (3.0 - 2.0 * f); float n = mix( mix(dot(hash2(i + float2(0.0, 0.0)), f -
float2(0.0, 0.0)), dot(hash2(i + float2(1.0, 0.0)), f - float2(1.0, 0.0)), u.x),
mix(dot(hash2(i + float2(0.0, 1.0)), f - float2(0.0, 1.0)), dot(hash2(i +
float2(1.0, 1.0)), f - float2(1.0, 1.0)), u.x), u.y ); return 0.5 + 0.5 * n; }

float sharpRing(float3 decomposed, float time) { float ringStart = 1.0; float
ringWidth = 0.3; float noiseScale = 5.0; float n = mix(
noise2D(float2(decomposed.x, time) * noiseScale), noise2D(float2(decomposed.y,
time) * noiseScale), decomposed.z ); n = (n - 0.5) * 2.5; return ringStart + n *
ringWidth * 1.5; }

float smoothRing(float3 decomposed, float time) { float ringStart = 0.9; float
ringWidth = 0.2; float noiseScale = 6.0; float n = mix(
noise2D(float2(decomposed.x, time) * noiseScale), noise2D(float2(decomposed.y,
time) * noiseScale), decomposed.z ); n = (n - 0.5) * 5.0; return ringStart + n *
ringWidth; }

float flow(float3 decomposed, float time) { return mix( perlin(float2(time,
decomposed.x / 2.0)), perlin(float2(time, decomposed.y / 2.0)), decomposed.z );
}

half4 main(float2 fragCoord) { // three.js circle: uv*2-1 == position / radius,
y up float2 uv = float2(fragCoord.x - uCenter.x, uCenter.y - fragCoord.y) /
uRadiusPx; float radius = length(uv); float cover = clamp((1.0 - radius) *
uRadiusPx + 0.5, 0.0, 1.0); if (cover <= 0.0) { return half4(0.0); }

float theta = atan(uv.y, uv.x); if (theta < 0.0) theta += 2.0 * PI;

float3 decomposed = float3( theta / (2.0 * PI), mod(theta / (2.0 * PI)
+ 0.5, 1.0) + 1.0, abs(theta / PI - 1.0) );

float fnoise = flow(decomposed, radius * 0.03 - uAnimation * 0.2) - 0.5; theta
+= fnoise * mix(0.08, 0.25, uOutputVolume);

float4 color = float4(1.0, 1.0, 1.0, 1.0);

for (int i = 0; i < 7; i++) { float fi = float(i); float center = fi * 0.5 * PI
+ 0.5 * sin(uTime / 20.0 + offsetAt(i)); float n = perlin(float2(mod(center +
uTime * 0.05, 1.0), 0.5)); float a = 0.5 + n * 0.3; float b = n * mix(3.5, 2.5,
uInputVolume); bool reverseGradient = mod(fi, 2.0) > 0.5;

float distTheta = min(
  abs(theta - center),
  min(abs(theta + 2.0 * PI - center), abs(theta - 2.0 * PI - center))
);
float4 oval = drawOval(float2(distTheta, radius), float2(0.0, 0.0), a, b, reverseGradient, 0.6);
if (oval.a >= 0.0) {
  color.rgb = mix(color.rgb, oval.rgb, oval.a);
  color.a = max(color.a, oval.a);
}

}

float ringRadius1 = sharpRing(decomposed, uTime * 0.1); float ringRadius2 =
smoothRing(decomposed, uTime * 0.1);

float inputRadius1 = radius + uInputVolume * 0.2; float inputRadius2 = radius +
uInputVolume * 0.15; float opacity1 = mix(0.2, 0.6, uInputVolume); float
opacity2 = mix(0.15, 0.45, uInputVolume);

float ringAlpha1 = (inputRadius2 >= ringRadius1) ? opacity1 : 0.0; float
ringAlpha2 = smoothstep(ringRadius2 - 0.05, ringRadius2 + 0.05, inputRadius1) *
opacity2; float totalRingAlpha = max(ringAlpha1, ringAlpha2);

float3 ringColor = float3(1.0); color.rgb = 1.0 - (1.0 - color.rgb) * (1.0 -
ringColor * totalRingAlpha);

float luminance = mix(color.r, 1.0 - color.r, uInverted); color.rgb =
colorRamp(luminance, float3(0.0), uColor1, uColor2, float3(1.0));

float alpha = color.a * uOpacity * cover; return half4(half3(color.rgb * alpha),
half(alpha)); } """
