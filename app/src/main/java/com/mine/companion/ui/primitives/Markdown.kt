package com.mine.companion.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.Txt

/*
 * Just enough Markdown for chat replies: paragraphs, headings, bullet and numbered lists,
 * block quotes, fenced code, **bold**, *italic*, `code`, ~~strike~~ and [links](…).
 * Parsing is cached per text, so a streaming reply re-parses only when it actually changes.
 */

@Immutable
private sealed interface Block {
    data class Para(val text: AnnotatedString) : Block
    data class Heading(val text: AnnotatedString, val level: Int) : Block
    data class Bullet(val text: AnnotatedString, val marker: String, val depth: Int) : Block
    data class Quote(val text: AnnotatedString) : Block
    data class Code(val text: String) : Block
}

@Composable
fun MarkdownText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, selectable: Boolean = true) {
    val codeBg = Mine.Surface2
    val accent = Mine.Accent
    val blocks = remember(text, codeBg, accent) { parse(text, codeBg, accent) }
    val content: @Composable () -> Unit = {
        Column(modifier, verticalArrangement = Arrangement.spacedBy((style.fontSize.value * 0.6f).dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Block.Para -> Txt(b.text, style, color)
                    is Block.Heading -> Txt(
                        b.text,
                        style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * (if (b.level <= 1) 1.18f else if (b.level == 2) 1.1f else 1.02f)),
                        color,
                    )
                    is Block.Bullet -> Row(Modifier.fillMaxWidth().padding(start = (b.depth * 14).dp)) {
                        Box(Modifier.width(if (b.marker.length > 2) 26.dp else 18.dp)) { Txt(b.marker, style, color.copy(alpha = 0.6f)) }
                        Txt(b.text, style, color, Modifier.weight(1f))
                    }
                    is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = 0.25f), RoundedCornerShape(2.dp)))
                        Txt(b.text, style.copy(fontStyle = FontStyle.Italic), color.copy(alpha = 0.8f), Modifier.padding(start = 10.dp))
                    }
                    is Block.Code -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(codeBg, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Txt(b.text, style.copy(fontSize = style.fontSize * 0.88f, lineHeight = 1.45.em, fontFeatureSettings = "\"tnum\" 1"), color)
                    }
                }
            }
        }
    }
    if (selectable) SelectionContainer { content() } else content()
}

private fun parse(src: String, codeBg: Color, accent: Color): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out += Block.Para(inline(para.toString().trim(), codeBg, accent))
        para.clear()
    }
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += Block.Code(code.toString())
            }
            trimmed.isEmpty() -> flushPara()
            HEADING.matches(trimmed) -> {
                flushPara()
                val m = HEADING.find(trimmed)!!
                out += Block.Heading(inline(m.groupValues[2], codeBg, accent), m.groupValues[1].length)
            }
            BULLET.matches(line) -> {
                flushPara()
                val m = BULLET.find(line)!!
                out += Block.Bullet(inline(m.groupValues[2], codeBg, accent), "•", (m.groupValues[1].length / 2).coerceAtMost(3))
            }
            NUMBERED.matches(line) -> {
                flushPara()
                val m = NUMBERED.find(line)!!
                out += Block.Bullet(inline(m.groupValues[3], codeBg, accent), "${m.groupValues[2]}.", (m.groupValues[1].length / 2).coerceAtMost(3))
            }
            trimmed.startsWith(">") -> {
                flushPara()
                out += Block.Quote(inline(trimmed.removePrefix(">").trim(), codeBg, accent))
            }
            trimmed == "---" || trimmed == "***" -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flushPara()
    return out
}

private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.+)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.+)$")
private val INLINE = Regex("(\\*\\*|__)(.+?)\\1|(?<![*\\w])([*_])(?!\\s)(.+?)(?<!\\s)\\3(?![*\\w])|`([^`]+)`|~~(.+?)~~|\\[([^\\]]+)]\\(([^)\\s]+)\\)")

private fun inline(s: String, codeBg: Color, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[2], codeBg, accent)) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[4], codeBg, accent)) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(background = codeBg, fontFeatureSettings = "\"tnum\" 1")) { append(" ${g[5]} ") }
            g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
            g[7].isNotEmpty() -> {
                val url = g[8]
                pushLink(androidx.compose.ui.text.LinkAnnotation.Url(url, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))))
                append(g[7])
                pop()
            }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
