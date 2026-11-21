package com.mine.companion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
class MinePalette(
    val isDark: Boolean,
    val backdrop: Color,
    val canvas: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val well: Color,
    val ink: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val paper: Color,
    val paper2: Color,
    val paperInk: Color,
    val paperHairline: Color,
    val accent: Color,
    val onAccent: Color,
    val paperAccent: Color,
    val urgent: Color,
    val settled: Color,
    val steel: Color,
)

val DarkPalette = MinePalette(
    isDark = true,
    backdrop = Color(0xFF000000),
    canvas = Color(0xFF0B0B0C),
    surface1 = Color(0xFF141416),
    surface2 = Color(0xFF1B1B1E),
    surface3 = Color(0xFF242428),
    surface4 = Color(0xFF2E2E33),
    well = Color(0xFF070708),
    ink = Color(0xFFF2F1EE),
    hairline = Color(0x17FFFFFF),
    hairlineStrong = Color(0x24FFFFFF),
    paper = Color(0xFFF2F0EB),
    paper2 = Color(0xFFE8E5DE),
    paperInk = Color(0xFF141416),
    paperHairline = Color(0x17141416),
    accent = Color(0xFF93C5FD),
    onAccent = Color(0xFF0B1220),
    paperAccent = Color(0xFF2563EB),
    urgent = Color(0xFFFF5A52),
    settled = Color(0xFF4FB585),
    steel = Color(0xFF9AA7B6),
)

val LightPalette = MinePalette(
    isDark = false,
    backdrop = Color(0xFF000000),
    canvas = Color(0xFFF4F3F0),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECEBE7),
    surface3 = Color(0xFFE3E2DD),
    surface4 = Color(0xFFD9D8D3),
    well = Color(0xFFE9E8E4),
    ink = Color(0xFF121214),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x29000000),
    paper = Color(0xFF1B1B1E),
    paper2 = Color(0xFF242428),
    paperInk = Color(0xFFF2F1EE),
    paperHairline = Color(0x1AFFFFFF),
    accent = Color(0xFF2563EB),
    onAccent = Color(0xFFFFFFFF),
    paperAccent = Color(0xFF93C5FD),
    urgent = Color(0xFFD93A33),
    settled = Color(0xFF2F8A5F),
    steel = Color(0xFF8C9AAB),
)

/**
 * The palette is chosen once per Activity. A dark/light switch recreates the Activity,
 * so plain getters are safe — and they work inside draw lambdas and non-composable helpers.
 */
object Mine {
    @Volatile
    var palette: MinePalette = DarkPalette
        internal set

    val IsDark: Boolean get() = palette.isDark
    val Backdrop: Color get() = palette.backdrop
    val Canvas: Color get() = palette.canvas
    val Surface0: Color get() = palette.canvas
    val Surface1: Color get() = palette.surface1
    val Surface2: Color get() = palette.surface2
    val Surface3: Color get() = palette.surface3
    val Surface4: Color get() = palette.surface4
    val Well: Color get() = palette.well

    val Ink1: Color get() = palette.ink
    val Ink2: Color get() = palette.ink.copy(alpha = 0.64f)
    val Ink3: Color get() = palette.ink.copy(alpha = 0.44f)
    val Ink4: Color get() = palette.ink.copy(alpha = 0.22f)
    val Hairline: Color get() = palette.hairline
    val HairlineStrong: Color get() = palette.hairlineStrong

    val Paper: Color get() = palette.paper
    val Paper2: Color get() = palette.paper2
    val PaperInk1: Color get() = palette.paperInk
    val PaperInk2: Color get() = palette.paperInk.copy(alpha = 0.6f)
    val PaperInk3: Color get() = palette.paperInk.copy(alpha = 0.38f)
    val PaperHairline: Color get() = palette.paperHairline

    val Accent: Color get() = palette.accent
    val OnAccent: Color get() = palette.onAccent
    val PaperAccent: Color get() = palette.paperAccent
    val Attention: Color get() = palette.accent
    val Urgent: Color get() = palette.urgent
    val Settled: Color get() = palette.settled
    val Steel: Color get() = palette.steel
    val Thumb: Color get() = Color.White
}

/**
 * Legacy names, now mapped to semantic roles so every older screen themes itself.
 * Cream = inverse fill, Jet = ink on inverse surfaces. One accent replaces Gold/Sky/Indigo/Amber.
 */
object Hex {
    val Night: Color get() = Mine.Canvas
    val ChatBg: Color get() = Mine.Canvas
    val VoiceBg: Color get() = Mine.Canvas
    val Abyss: Color get() = Mine.Well
    val Track: Color get() = Mine.Well
    val Field: Color get() = Mine.Surface2
    val Dropdown: Color get() = Mine.Surface2
    val VoiceDock: Color get() = Mine.Surface2
    val Graphite: Color get() = Mine.Surface1
    val ReceiptBg: Color get() = Mine.Surface1
    val Chip: Color get() = Mine.Surface3

    val Bone: Color get() = Mine.Ink1
    val Mist: Color get() = Mine.Ink1
    val Parchment: Color get() = Mine.Ink1
    val Ice: Color get() = Mine.Ink1
    val Mute: Color get() = Mine.Ink3

    val Cream: Color get() = Mine.Ink1
    val Jet: Color get() = Mine.PaperInk1
    val Ink111215: Color get() = Mine.PaperInk1
    val PillBlack: Color get() = Color(0xFF070709)
    val Zinc500: Color get() = Mine.PaperInk2
    val Zinc600: Color get() = Mine.PaperInk1.copy(alpha = 0.72f)

    val Gold: Color get() = if (Mine.IsDark) Color(0xFFE5C185) else Color(0xFF9A6B1F)
    val Sky: Color get() = Mine.Accent
    val Amber: Color get() = if (Mine.IsDark) Color(0xFFE5A93C) else Color(0xFFB07A12)
    val Indigo: Color get() = Mine.Accent
    val BlueGlow: Color get() = Color(0xFF2563EB)
    val Moss: Color get() = Mine.Settled
    val Ember: Color get() = Mine.Urgent
    val Rose: Color get() = Mine.Urgent
    val RoseDeep: Color get() = Mine.Urgent
    val RoseLight: Color get() = Mine.Urgent
}

fun Color.a(alpha: Float): Color = copy(alpha = alpha)
