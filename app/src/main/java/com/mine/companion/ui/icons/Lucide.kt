package com.mine.companion.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * lucide-react glyphs used by the prototype, as their original 24×24 stroke paths.
 * Rendered as ImageVectors (round caps/joins, currentColor tint), built once per
 * (glyph, strokeWidth, filled) and cached for the life of the process.
 */
@Immutable
class LucideGlyph internal constructor(val name: String, val paths: List<String>)

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f): String =
    if (rx <= 0f) "M$x ${y}h${w}v${h}h${-w}z"
    else "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = "M$x1 ${y1}L$x2 $y2"

private fun g(name: String, vararg p: String) = LucideGlyph(name, p.toList())

object Lucide {
    val Check = g("check", "M20 6 9 17l-5-5")
    val X = g("x", "M18 6 6 18", "m6 6 12 12")
    val Plus = g("plus", "M5 12h14", "M12 5v14")
    val ChevronLeft = g("chevron-left", "m15 18-6-6 6-6")
    val ChevronRight = g("chevron-right", "m9 18 6-6-6-6")
    val ChevronDown = g("chevron-down", "m6 9 6 6 6-6")
    val ChevronUp = g("chevron-up", "m18 15-6-6-6 6")
    val ArrowRight = g("arrow-right", "M5 12h14", "m12 5 7 7-7 7")
    val ArrowUpRight = g("arrow-up-right", "M7 7h10v10", "M7 17 17 7")
    val Menu = g("menu", line(4f, 12f, 20f, 12f), line(4f, 6f, 20f, 6f), line(4f, 18f, 20f, 18f))
    val SlidersHorizontal = g(
        "sliders-horizontal",
        line(21f, 4f, 14f, 4f), line(10f, 4f, 3f, 4f), line(21f, 12f, 12f, 12f), line(8f, 12f, 3f, 12f),
        line(21f, 20f, 16f, 20f), line(12f, 20f, 3f, 20f), line(14f, 2f, 14f, 6f), line(8f, 10f, 8f, 14f),
        line(16f, 18f, 16f, 22f),
    )
    val LayoutGrid = g(
        "layout-grid",
        rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(14f, 14f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f),
    )
    val RotateCcw = g("rotate-ccw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Radio = g(
        "radio",
        "M4.9 19.1C1 15.2 1 8.8 4.9 4.9", "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5", circle(12f, 12f, 2f),
        "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5", "M19.1 4.9C23 8.8 23 15.1 19.1 19",
    )
    val Bell = g("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val BellRing = g(
        "bell-ring",
        "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0",
        "M4 2C2.8 3.7 2 5.7 2 8", "M22 8c0-2.3-.8-4.3-2-6",
    )
    val Inbox = g(
        "inbox",
        "M22 12h-6l-2 3h-4l-2-3H2",
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    )
    val NotebookText = g(
        "notebook-text",
        "M2 6h4", "M2 10h4", "M2 14h4", "M2 18h4", rect(4f, 2f, 16f, 20f, 2f),
        "M9.5 8h5", "M9.5 12H16", "M9.5 16H14",
    )
    val AlarmClock = g(
        "alarm-clock",
        circle(12f, 13f, 8f), "M12 9v4l2 2", "M5 3 2 6", "m22 6-3-3", "M6.38 18.7 4 21", "M17.64 18.67 20 21",
    )
    val CalendarClock = g(
        "calendar-clock",
        "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5", "M16 2v4", "M8 2v4", "M3 10h5",
        "M17.5 17.5 16 16.3V14", circle(16f, 16f, 6f),
    )
    val GitMerge = g("git-merge", circle(18f, 18f, 3f), circle(6f, 6f, 3f), "M6 21V9a9 9 0 0 0 9 9")
    val Brain = g(
        "brain",
        "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
        "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
        "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4",
        "M17.599 6.5a3 3 0 0 0 .399-1.375", "M6.003 5.125A3 3 0 0 0 6.401 6.5",
        "M3.477 10.896a4 4 0 0 1 .585-.396", "M19.938 10.5a4 4 0 0 1 .585.396",
        "M6 18a4 4 0 0 1-1.967-.516", "M19.967 17.484A4 4 0 0 1 18 18",
    )
    val User = g("user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f))
    val Settings2 = g("settings-2", "M20 7h-9", "M14 17H5", circle(17f, 17f, 3f), circle(7f, 7f, 3f))
    val Home = g(
        "house",
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )
    val Feather = g(
        "feather",
        "M12.67 19a2 2 0 0 0 1.416-.588l6.154-6.172a6 6 0 0 0-8.49-8.49L5.586 9.914A2 2 0 0 0 5 11.328V18a1 1 0 0 0 1 1z",
        "M16 8 2 22", "M17.5 15H9",
    )
    val AudioLines = g("audio-lines", "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")
    val MapPin = g(
        "map-pin",
        "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0",
        circle(12f, 10f, 3f),
    )
    val ChartNoAxesColumn = g("chart-no-axes-column", line(18f, 20f, 18f, 10f), line(12f, 20f, 12f, 4f), line(6f, 20f, 6f, 14f))
    val Sparkles = g(
        "sparkles",
        "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
        "M20 3v4", "M22 5h-4", "M4 17v2", "M5 18H3",
    )
    val Send = g(
        "send",
        "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z",
        "m21.854 2.147-10.94 10.939",
    )
    val Loader2 = g("loader-circle", "M21 12a9 9 0 1 1-6.219-8.56")
    val Copy = g("copy", rect(8f, 8f, 14f, 14f, 2f), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val Globe = g("globe", circle(12f, 12f, 10f), "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val Pause = g("pause", rect(14f, 4f, 4f, 16f, 1f), rect(6f, 4f, 4f, 16f, 1f))
    val Play = g("play", "M6 3 20 12 6 21 6 3z")
    val Eye = g(
        "eye",
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        circle(12f, 12f, 3f),
    )
    val Clock = g("clock", circle(12f, 12f, 10f), "M12 6v6l4 2")
    val Search = g("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val Pin = g(
        "pin",
        "M12 17v5",
        "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
    )
    val MoonStar = g("moon-star", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9", "M20 3v4", "M22 5h-4")
    val Wifi = g("wifi", "M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0")

    // Added with the engine integration: settings, voice and notification-reading surfaces.
    val Volume2 = g(
        "volume-2",
        "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728",
    )
    val Square = g("square", rect(5f, 5f, 14f, 14f, 2f))
    val Mic = g("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", line(12f, 19f, 12f, 22f))
    val Headphones = g("headphones", "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3")
    val Lock = g("lock", rect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4")
    val Shield = g("shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
    val Trash2 = g(
        "trash-2", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
        line(10f, 11f, 10f, 17f), line(14f, 11f, 14f, 17f),
    )
    val Download = g("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", line(12f, 15f, 12f, 3f))
    val Upload = g("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m17 8-5-5-5 5", line(12f, 3f, 12f, 15f))
    val MessageSquare = g("message-square", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val Cpu = g(
        "cpu", rect(4f, 4f, 16f, 16f, 2f), rect(9f, 9f, 6f, 6f, 1f),
        "M15 2v2", "M15 20v2", "M2 15h2", "M2 9h2", "M20 15h2", "M20 9h2", "M9 2v2", "M9 20v2",
    )
    val Zap = g("zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    val TriangleAlert = g("triangle-alert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    val Info = g("info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    val Smartphone = g("smartphone", rect(5f, 2f, 14f, 20f, 2f), "M12 18h.01")
    val Link = g(
        "link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    )
    val RefreshCw = g(
        "refresh-cw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
}

private data class VecKey(val name: String, val stroke: Float, val filled: Boolean)

private val vectorCache = HashMap<VecKey, ImageVector>()

private fun buildVector(glyph: LucideGlyph, stroke: Float, filled: Boolean): ImageVector =
    vectorCache.getOrPut(VecKey(glyph.name, stroke, filled)) {
        ImageVector.Builder(
            name = glyph.name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            glyph.paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

/** `<LucideIcon className="size-4" strokeWidth={…} />`, tinted with currentColor. */
@Composable
fun LIcon(
    glyph: LucideGlyph,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 2f,
    filled: Boolean = false,
) {
    val vector = remember(glyph, strokeWidth, filled) { buildVector(glyph, strokeWidth, filled) }
    val painter = rememberVectorPainter(vector)
    val filter = remember(tint) { ColorFilter.tint(tint) }
    Image(painter = painter, contentDescription = null, colorFilter = filter, modifier = modifier.size(size))
}
