package com.mine.companion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.mine.companion.store.ReplyDraft
import com.mine.companion.store.ReplyQuote
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mine.companion.store.ChatErrorUi
import com.mine.companion.store.ChatMessage
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ScreenId
import com.mine.companion.store.Sender
import com.mine.companion.store.SettingKey
import com.mine.companion.store.VoicePhase
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.components.ProposalRow
import com.mine.companion.ui.components.ReceiptChip
import com.mine.companion.ui.fx.AgentState
import com.mine.companion.ui.fx.LiveWaveform
import com.mine.companion.ui.fx.Orb
import com.mine.companion.ui.fx.PulsingBorder
import com.mine.companion.ui.fx.PulsingBorderParams
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.icons.LucideGlyph
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.MarkdownText
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.LocalLowPower
import com.mine.companion.ui.theme.LocalReducedMotion
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.blurEffect
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.rememberHaptics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/* ======================================================================== */
/* Voice view                                                               */
/* ======================================================================== */

private val OutQuint = MineMotion.EaseOutQuint
private val VoiceEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
private fun AnimatedVoiceStatusLabel(phaseWord: String, paused: Boolean, onClick: () -> Unit) {
    var dotCount by remember { mutableIntStateOf(1) }
    LaunchedEffect(paused, phaseWord) {
        if (paused) return@LaunchedEffect
        while (true) {
            delay(420)
            dotCount = (dotCount % 3) + 1
        }
    }
    val labelKey = if (paused) "Paused" else phaseWord
    Box(Modifier.height(28.dp).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        FxSwap(
            target = labelKey,
            initial = Fx(alpha = 0f, y = 5.dp, blur = 4.dp),
            exit = Fx(alpha = 0f, y = (-5).dp, blur = 4.dp),
            enter = tween(240, easing = OutQuint),
            wait = true,
            animateOnFirst = true,
        ) { word ->
            Row(verticalAlignment = Alignment.Bottom) {
                val style = mineText(13.5f, 500, tracking = 0.04f)
                Txt(word, style, Hex.Sky.copy(alpha = 0.9f))
                if (word != "Paused") {
                    Row(Modifier.width(18.dp)) {
                        (1..3).forEach { n ->
                            val a by animateFloatAsState(if (dotCount >= n) 1f else 0f, tween(150, easing = MineMotion.TwEase), label = "dot")
                            Txt(".", mineText(13.5f, 500, mono = true), Hex.Sky.copy(alpha = 0.9f), Modifier.graphicsLayer { alpha = a })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWord(word: String, shown: Boolean, bright: Boolean) {
    val alpha = remember { Animatable(0f) }
    val blur = remember { Animatable(6f) }
    val y = remember { Animatable(3f) }
    val ms = if (bright) 260 else 240
    LaunchedEffect(shown) {
        val spec = tween<Float>(ms, easing = MineMotion.FmEaseOut)
        launch { alpha.animateTo(if (shown) 1f else if (bright) 0f else 0.15f, spec) }
        launch { blur.animateTo(if (shown) 0f else if (bright) 6f else 4f, spec) }
        launch { y.animateTo(if (shown) 0f else 3f, spec) }
    }
    Txt(
        word,
        mineText(20f, 500, lineHeight = 1.4f, tracking = -0.015f),
        if (bright) Hex.Cream else Color.White.copy(alpha = 0.4f),
        Modifier
            .padding(end = 5.dp)
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = y.value * density
                renderEffect = blurEffect(blur.value * density)
            },
    )
}

private val OrbGlowStops = arrayOf(
    0f to Hex.BlueGlow.copy(alpha = 0.15f * 0.964f),
    (40f / 192f) to Hex.BlueGlow.copy(alpha = 0.15f * 0.788f),
    (72f / 192f) to Hex.BlueGlow.copy(alpha = 0.15f * 0.5f),
    (100f / 192f) to Hex.BlueGlow.copy(alpha = 0.15f * 0.242f),
    (130f / 192f) to Hex.BlueGlow.copy(alpha = 0.15f * 0.073f),
    (160f / 192f) to Hex.BlueGlow.copy(alpha = 0.15f * 0.013f),
    1f to Color.Transparent,
)

/** Keeps only the last [max] words, so a long reply scrolls like a caption instead of overflowing. */
private fun tailWords(text: String, max: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.size <= max) words else listOf("…") + words.takeLast(max)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandsFreeVoiceView(onExit: () -> Unit) {
    val dispatch = mineDispatch()
    val voice = mineSelect { it.platform.voice }
    val lowPower = LocalLowPower.current
    BackHandler { onExit() }

    val paused = voice.phase == VoicePhase.paused
    val orbState: AgentState? = when (voice.phase) {
        VoicePhase.listening -> AgentState.Listening
        VoicePhase.thinking -> AgentState.Thinking
        VoicePhase.speaking -> AgentState.Talking
        else -> null
    }
    val phaseWord = when (voice.phase) {
        VoicePhase.listening -> "Listening"
        VoicePhase.thinking -> "Thinking"
        VoicePhase.speaking -> "Speaking"
        VoicePhase.paused -> "Paused"
        VoicePhase.idle -> "Starting"
    }
    // Muted line: what you said. Bright line: what Mine says (or a hint while listening).
    val muted = when {
        voice.problem != null -> ""
        voice.heard.isNotBlank() -> voice.heard
        voice.phase == VoicePhase.listening -> "I'm listening."
        else -> ""
    }
    val bright: String = when {
        voice.problem != null -> voice.problem.orEmpty()
        voice.phase == VoicePhase.speaking || voice.reply.isNotBlank() && voice.phase != VoicePhase.listening -> voice.reply
        voice.phase == VoicePhase.listening && voice.heard.isBlank() -> "Go ahead — say “stop” when you're done."
        voice.phase == VoicePhase.thinking -> "One moment…"
        else -> ""
    }
    val prefixWords = remember(muted) { tailWords(muted, 18) }
    val brightWords = remember(bright) { tailWords(bright, 34) }
    val turnKey = "${voice.phase}|${muted.hashCode()}|${bright.hashCode()}"
    var visibleWords by remember { mutableIntStateOf(0) }
    val total = prefixWords.size + brightWords.size
    LaunchedEffect(turnKey) {
        visibleWords = 0
        while (visibleWords < total) {
            delay(70)
            visibleWords += 1
        }
    }

    val border = PulsingBorderParams(
        colorBack = Color.Transparent,
        colors = listOf(Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFF93C5FD)),
        roundness = 0.18f,
        thickness = if (paused) 0.042f else 0.12f,
        softness = 0.94f,
        intensity = if (paused) 0.28f else 0.87f,
        bloom = 0.01f,
        spots = 4,
        spotSize = 0.42f,
        pulse = 0f,
        smoke = if (paused) 0.04f else 0.28f,
        smokeSize = 0.45f,
        speed = if (paused) 0.04f else 0.5f,
    )

    Box(Modifier.fillMaxSize().background(Hex.VoiceBg)) {
        val columnPadding = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 36.dp)

        Column(columnPadding.zIndex(10f), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(184.dp)
                        .drawWithCache {
                            val r = 192.dp.toPx()
                            val glow = Brush.radialGradient(*OrbGlowStops, center = Offset(size.width / 2f, size.height / 2f), radius = r)
                            onDrawBehind { drawCircle(glow, radius = r) }
                        }
                        .tap { dispatch(if (paused) MineAction.ResumeVoice else MineAction.PauseVoice) }
                        .semantics { contentDescription = if (paused) "Resume" else "Pause" },
                    contentAlignment = Alignment.Center,
                ) {
                    Orb(colors = Color(0xFF38BDF8) to Color(0xFF1D4ED8), agentState = orbState, modifier = Modifier.size(184.dp).clip(CircleShape))
                }
                AnimatedVoiceStatusLabel(phaseWord, paused) { dispatch(if (paused) MineAction.ResumeVoice else MineAction.PauseVoice) }
            }
        }

        // Lower resolution in Battery Saver: the glow is soft enough that nobody can tell.
        PulsingBorder(border, Modifier.fillMaxSize().zIndex(20f), resolution = if (lowPower) 0.33f else 0.5f)

        Column(
            columnPadding.zIndex(30f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(8.dp + 184.dp + 12.dp + 28.dp))
            Box(Modifier.widthIn(max = 318.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FxSwap(
                    target = voice.phase,
                    initial = Fx(alpha = 0f, y = 6.dp, blur = 6.dp),
                    exit = Fx(alpha = 0f, y = (-6).dp, blur = 6.dp),
                    enter = tween(260, easing = OutQuint),
                    wait = true,
                    animateOnFirst = true,
                    contentAlignment = Alignment.Center,
                ) { _ ->
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        prefixWords.forEachIndexed { i, w -> key("pre-$i-$w") { VoiceWord(w, i < visibleWords, bright = false) } }
                        brightWords.forEachIndexed { i, w -> key("br-$i-$w") { VoiceWord(w, prefixWords.size + i < visibleWords, bright = true) } }
                    }
                }
            }
            VoiceDock(
                paused = paused,
                listening = voice.phase == VoicePhase.listening,
                thinking = voice.phase == VoicePhase.thinking,
                onExit = onExit,
                onPause = { dispatch(if (it) MineAction.PauseVoice else MineAction.ResumeVoice) },
            )
        }
    }
}

@Composable
private fun VoiceDock(paused: Boolean, listening: Boolean, thinking: Boolean, onExit: () -> Unit, onPause: (Boolean) -> Unit) {
    val pill = CircleShape
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        val full = min(334f, maxWidth.value).dp
        val width by animateDpAsState(if (paused) 64.dp else full, MineMotion.fm(180f, 19f), label = "dock-w")

        val playA = remember { Animatable(if (paused) 1f else 0f) }
        val barA = remember { Animatable(if (paused) 0f else 1f) }
        val btnS = remember { Animatable(if (paused) 0.4f else 1f) }
        val wave = remember { Animatable(if (paused) 0f else 1f) }
        LaunchedEffect(paused) {
            val ease = tween<Float>(200, easing = VoiceEase)
            launch { playA.animateTo(if (paused) 1f else 0f, ease) }
            launch { barA.animateTo(if (paused) 0f else 1f, ease) }
            launch {
                if (!paused) delay(60)
                btnS.animateTo(if (paused) 0.4f else 1f, MineMotion.fm(180f, 19f))
            }
            launch {
                if (!paused) delay(80)
                wave.animateTo(if (paused) 0f else 1f, tween(280, easing = VoiceEase))
            }
        }

        Box(
            Modifier
                .width(width)
                .height(54.dp)
                .dropShadow(pill, Shadow(radius = 32.dp, color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 12.dp)))
                .background(Hex.VoiceDock, pill)
                .innerShadow(pill, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.12f), offset = DpOffset(0.dp, 1.dp)))
                .border(1.dp, Color.White.copy(alpha = 0.09f), pill)
                .clip(pill),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = playA.value
                        val s = 0.65f + 0.35f * playA.value
                        scaleX = s
                        scaleY = s
                        renderEffect = blurEffect((1f - playA.value) * 4f * density)
                    }
                    .then(if (paused) Modifier.tap { onPause(false) }.semantics { contentDescription = "Resume" } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LIcon(Lucide.Play, 18.dp, Hex.Cream, Modifier.offset(x = 2.dp), filled = true)
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = barA.value }
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val btn = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = btnS.value
                        scaleY = btnS.value
                        alpha = ((btnS.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    }
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                Box(btn.then(if (!paused) Modifier.tap(onClick = onExit).semantics { contentDescription = "End voice" } else Modifier), contentAlignment = Alignment.Center) {
                    LIcon(Lucide.X, 16.dp, Hex.Cream.copy(alpha = 0.85f))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = wave.value
                            translationY = (1f - wave.value) * 7f * density
                            renderEffect = blurEffect((1f - wave.value) * 4f * density)
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveWaveform(
                        active = !paused && listening,
                        processing = !paused && thinking,
                        barWidth = 2.5.dp,
                        barGap = 2.dp,
                        barRadius = 1.5.dp,
                        barColor = Hex.Cream,
                        height = 30.dp,
                        fadeEdges = true,
                        fadeWidth = 20.dp,
                        level = { com.anchor.mine.voice.VoiceSession.currentLevel() },
                    )
                }
                Box(btn.then(if (!paused) Modifier.tap { onPause(true) }.semantics { contentDescription = "Pause" } else Modifier), contentAlignment = Alignment.Center) {
                    LIcon(Lucide.Pause, 15.dp, Hex.Cream.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/* ======================================================================== */
/* Expanding prompt input                                                   */
/* ======================================================================== */

/** layoutId="mine-kinetic-prompt": FLIP from wherever the previous prompt sat. */
private class PromptFlip {
    var last: Offset? = null
    var lastKey: Any? = null
}

private fun Modifier.sharedFlip(holder: PromptFlip, key: Any): Modifier = composed {
    val anim = remember(key) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val first = remember(key) { booleanArrayOf(true) }
    this
        .onGloballyPositioned { c ->
            val pos = c.positionInRoot()
            if (first[0]) {
                first[0] = false
                val prev = holder.last
                if (prev != null && holder.lastKey != key) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        anim.snapTo(prev - pos)
                        anim.animateTo(Offset.Zero, MineMotion.gentle(Offset(0.5f, 0.5f)))
                    }
                }
            }
            holder.last = pos
            holder.lastKey = key
        }
        .graphicsLayer {
            translationX = anim.value.x
            translationY = anim.value.y
        }
}

@Composable
private fun ExpandingPromptInput(
    draft: String,
    onDraft: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onEnterHandsFree: () -> Unit,
    loading: Boolean,
    webOn: Boolean,
    onToggleWeb: () -> Unit,
    focusTick: Int,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val active = focused || draft.isNotBlank()

    LaunchedEffect(focusTick) { if (focusTick > 0) runCatching { focus.requestFocus() } }

    fun submit() {
        if (draft.isBlank() || loading) return
        haptics.confirm()
        onSubmit(draft)
        onDraft("")
        focused = false
        focusManager.clearFocus()
    }

    val borderColor by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.09f),
        tween(150, easing = MineMotion.TwEase),
        label = "prompt-border",
    )
    val shape = RoundedCornerShape(24.dp)
    val textStyle = mineText(14.5f, lineHeight = 1.5f)
    val lineH = 14.5f * 1.5f
    val rows = if (active) draft.split('\n').size.coerceIn(3, 6) else 1

    Box(
        Modifier
            .fillMaxWidth()
            .animateContentSize(MineMotion.dur(0.32f))
            .background(Hex.Field, shape)
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .clickable(interactionSource = null, indication = null) { focus.requestFocus() },
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = textStyle.copy(color = Hex.Bone),
            cursorBrush = SolidColor(Hex.Bone),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Message Mine" }
                .then(
                    if (active) {
                        Modifier
                            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 54.dp)
                            .height(with(density) { (lineH * rows).sp.toDp() })
                    } else {
                        Modifier
                            .padding(start = 20.dp, end = 124.dp)
                            .height(48.dp)
                    },
                ),
            decorationBox = { inner ->
                Box(
                    if (active) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
                    contentAlignment = if (active) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Txt("Ask Mine anything…", textStyle, Hex.Mute, maxLines = 1)
                    inner()
                }
            },
        )

        Presence(
            visible = active,
            modifier = Modifier.align(Alignment.BottomCenter),
            initial = Fx.Hidden,
            enter = tween(300, easing = MineMotion.FmEaseInOut),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Hex.Field.copy(alpha = 0.9f), 1f to Hex.Field)),
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp)
                .zIndex(20f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PromptTool(
                expanded = active,
                glyph = Lucide.AudioLines,
                label = "Voice",
                tint = Hex.Sky,
                background = Color.White.copy(alpha = 0.06f),
                onClick = onEnterHandsFree,
            )
            val webBg by animateColorAsState(
                if (webOn) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f),
                tween(150),
                label = "web-bg",
            )
            PromptTool(
                expanded = active,
                glyph = Lucide.Globe,
                label = if (webOn) "Web on" else "Web",
                tint = if (webOn) Hex.Cream else Hex.Mute,
                background = webBg,
                outlined = webOn,
                onClick = onToggleWeb,
            )

            val canSend = draft.isNotBlank() && !loading
            val sendAlpha by animateFloatAsState(
                if (canSend || loading) 1f else 0.35f,
                tween(150, easing = MineMotion.TwEase),
                label = "send-a",
            )
            Box(
                Modifier
                    .size(32.dp)
                    .graphicsLayer { alpha = sendAlpha }
                    .background(Hex.Cream, CircleShape)
                    .tap(enabled = canSend || loading) { if (loading) onStop() else submit() }
                    .semantics { contentDescription = if (loading) "Stop" else "Send" },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    // A stop square inside a slowly turning ring: busy, and interruptible.
                    val spin = rememberInfiniteTransition(label = "spin")
                    val deg by spin.animateFloat(
                        0f, 360f,
                        InfiniteRepeatableSpec(tween(1100, easing = MineMotion.Linear), RepeatMode.Restart),
                        label = "deg",
                    )
                    Box(Modifier.graphicsLayer { rotationZ = deg }) { LIcon(Lucide.Loader2, 20.dp, Hex.Jet.copy(alpha = 0.35f)) }
                    LIcon(Lucide.Square, 10.dp, Hex.Jet, filled = true)
                } else {
                    // The paper plane's mass sits up-right; this small offset centres it optically.
                    LIcon(Lucide.Send, 14.dp, Hex.Jet, Modifier.offset(x = (-0.75).dp, y = 0.75.dp))
                }
            }
        }
    }
}

/** 32dp circle when collapsed, labelled pill when active. The icon stays dead centre either way. */
@Composable
private fun PromptTool(
    expanded: Boolean,
    glyph: LucideGlyph,
    label: String,
    tint: Color,
    background: Color,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .animateContentSize(MineMotion.dur(0.3f))
            .height(32.dp)
            .then(if (expanded) Modifier else Modifier.width(32.dp))
            .clip(CircleShape)
            .background(background)
            .then(if (outlined) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape) else Modifier)
            .tap(onClick = onClick)
            .semantics { contentDescription = label }
            .then(if (expanded) Modifier.padding(horizontal = 12.dp) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LIcon(glyph, 14.dp, tint)
        AnimatedVisibility(
            expanded,
            enter = fadeIn(tween(200)) + expandHorizontally(tween(200)),
            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150)),
        ) { Txt(label, mineText(12f, 500), tint, Modifier.padding(start = 6.dp), maxLines = 1) }
    }
}

/* ======================================================================== */
/* Screen                                                                   */
/* ======================================================================== */

private val UserBubble = RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp)

private fun greeting(h: Int) = when {
    h < 5 -> "Still up"
    h < 12 -> "Morning"
    h < 17 -> "Afternoon"
    else -> "Evening"
}

/** S15 Chat (today) and S16 Day Page (past). Also rendered inside the pull-up pill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatAndDayPage(isPastDayPage: Boolean = false, onClosePeephole: (() -> Unit)? = null) {
    val dispatch = mineDispatch()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voicePhase = mineSelect { it.platform.voice.phase }
    val handsFree = voicePhase != VoicePhase.idle
    val userName = mineSelect { it.userName }
    val selectedPastDate = mineSelect { it.selectedPastDate }
    val todayMessages = mineSelect { it.todayMessages }
    val pastMessages = mineSelect { s -> s.pastDayMessages[s.selectedPastDate].orEmpty() }
    val busy = mineSelect { it.chatBusy }
    val error = mineSelect { it.chatError }
    val webOn = mineSelect { it.smartWebSearchEnabled }
    val speakingId = mineSelect { it.platform.speakingId }
    val prefill = mineSelect { it.platform.composerPrefill }
    val focusTick = mineSelect { it.platform.composerFocusTick }
    val voiceReplies = mineSelect { it.voiceRepliesEnabled }
    val lastWebQuery = mineSelect { it.engine.lastWebSearchQuery }
    val nextUp = mineSelect { s ->
        s.items.firstOrNull { it.status != ItemStatus.done && it.dueLabel.startsWith("Today") }?.let { it.title to it.dueLabel.removePrefix("Today ") }
    }
    val connected = mineSelect { it.engine.providerKey.isNotBlank() || it.engine.onDeviceFallbackEnabled }
    val replyTo = mineSelect { it.platform.replyTo }

    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            draft = prefill
            dispatch(MineAction.Engine(com.anchor.core.ui.UiAction.UpdateDraft(prefill)))
        }
    }

    var copiedId by remember { mutableStateOf<String?>(null) }
    val flip = remember { PromptFlip() }
    val messages = if (isPastDayPage) pastMessages else todayMessages
    val isCenteredMode = messages.isEmpty() && !isPastDayPage

    // Read finished replies aloud when the user asked for spoken replies (outside voice mode).
    val lastAssistant = todayMessages.lastOrNull { it.sender == Sender.assistant && it.text.isNotBlank() && !it.isSystem }
    var spokenFor by rememberSaveable { mutableStateOf(lastAssistant?.id) }
    LaunchedEffect(lastAssistant?.id, busy) {
        val la = lastAssistant ?: return@LaunchedEffect
        if (!busy && voiceReplies && !handsFree && !isPastDayPage && la.id != spokenFor) {
            spokenFor = la.id
            dispatch(MineAction.SpeakMessage(la.id, la.text))
        } else if (!voiceReplies) spokenFor = la.id
    }

    fun send(text: String) {
        dispatch(MineAction.SendChatMessage(text))
    }

    fun handleCopy(id: String, text: String) {
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mine", text))
        }
        copiedId = id
        scope.launch {
            delay(1800)
            if (copiedId == id) copiedId = null
        }
    }

    val now = rememberNow()
    val title = if (isPastDayPage) selectedPastDate.ifBlank { "Earlier" }
    else "Today · ${now.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"

    Box(Modifier.fillMaxSize().background(Hex.ChatBg)) {
        FxSwap(
            target = handsFree,
            modifier = Modifier.fillMaxSize(),
            wait = true,
            initialFor = { hf -> if (hf) Fx(alpha = 0f, scale = 0.98f, blur = 8.dp) else Fx.Hidden },
            exitFor = { hf -> if (hf) Fx(alpha = 0f, scale = 0.98f, blur = 8.dp) else Fx.Hidden },
            enterFor = { hf -> if (hf) tween(320, easing = OutQuint) else tween(220, easing = MineMotion.FmEaseInOut) },
            exitSpecFor = { hf -> if (hf) tween(320, easing = OutQuint) else tween(220, easing = MineMotion.FmEaseInOut) },
        ) { hf ->
            if (hf) {
                HandsFreeVoiceView(onExit = { dispatch(MineAction.StopVoice) })
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .tap { dispatch(MineAction.OpenModal(ModalId.M01)) }
                                .semantics { contentDescription = "Choose a day" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Txt(title, mineText(14f, 600), Hex.Bone)
                            LIcon(Lucide.ChevronDown, 14.dp, Hex.Mute)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isPastDayPage) {
                                Txt(
                                    "Back to today", mineText(12f), Hex.Mute,
                                    Modifier.tap { dispatch(MineAction.Navigate(ScreenId.S15)) }.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.06f), CircleShape)
                                    .tap { onClosePeephole?.invoke() ?: dispatch(MineAction.Navigate(ScreenId.S14)) }
                                    .semantics { contentDescription = "Close" },
                                contentAlignment = Alignment.Center,
                            ) { LIcon(Lucide.X, 16.dp, Hex.Bone.copy(alpha = 0.75f)) }
                        }
                    }

                    if (isCenteredMode) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Presence(visible = true, initial = Fx(alpha = 0f, y = 8.dp, blur = 6.dp), enter = tween(280, easing = MineMotion.FmEaseInOut)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(bottom = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Txt(
                                        if (userName.isNotBlank()) "${greeting(now.hour)}, ${userName.trim()}." else "${greeting(now.hour)}.",
                                        MineType.Title, Mine.Ink1, textAlign = TextAlign.Center,
                                    )
                                    Txt(
                                        when {
                                            !connected -> "Quick tasks work now — try “call mom at 6”. Connect an AI in Settings to chat."
                                            nextUp != null -> "${nextUp.first} is at ${nextUp.second}. What's on your mind?"
                                            else -> "What's on your mind?"
                                        },
                                        mineText(14f, lineHeight = 1.625f), Hex.Mute,
                                        Modifier.widthIn(max = 280.dp), textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            Box(Modifier.sharedFlip(flip, "centered")) {
                                ExpandingPromptInput(
                                    draft = draft, onDraft = { draft = it },
                                    onSubmit = ::send,
                                    onStop = { dispatch(MineAction.StopReply) },
                                    onEnterHandsFree = { dispatch(MineAction.StartVoice) },
                                    loading = busy,
                                    webOn = webOn,
                                    onToggleWeb = { dispatch(MineAction.ToggleSetting(SettingKey.smartWebSearchEnabled)) },
                                    focusTick = focusTick,
                                )
                            }
                            Presence(
                                visible = true,
                                initial = Fx(alpha = 0f, y = 6.dp),
                                enter = tween(250, delayMillis = 50, easing = MineMotion.FmEaseInOut),
                            ) {
                                val chips = buildList {
                                    if (!connected) {
                                        add("Gym at 7 tomorrow")
                                        add("Call mom at 6")
                                    } else {
                                        add(if (now.hour < 12) "Plan my day" else "Plan my evening")
                                        if (nextUp != null) add("What should I focus on?") else add("Remind me to drink water in 1 hour")
                                        add("Take a 20 minute break")
                                    }
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    chips.forEach { chip ->
                                        Txt(
                                            chip, mineText(12.5f), Hex.Mute,
                                            Modifier
                                                .background(Color.White.copy(alpha = 0.03f), CircleShape)
                                                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                                                .tap { send(chip) }
                                                .padding(horizontal = 14.dp, vertical = 7.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        MessageList(
                            modifier = Modifier.weight(1f),
                            messages = messages,
                            busy = busy && !isPastDayPage,
                            webQuery = if (webOn) lastWebQuery else null,
                            error = if (isPastDayPage) null else error,
                            copiedId = copiedId,
                            speakingId = speakingId,
                            onCopy = ::handleCopy,
                            isPastDay = isPastDayPage,
                            onReply = { m -> dispatch(MineAction.SetReplyTo(ReplyDraft(m.id, m.sender, m.text.take(160)))) },
                            onTalkAbout = { m -> dispatch(MineAction.TalkAbout(m, selectedPastDate.ifBlank { null })) },
                        )
                        if (!isPastDayPage) {
                            replyTo?.let { r -> ReplyBar(r) { dispatch(MineAction.SetReplyTo(null)) } }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Hex.ChatBg)
                                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
                                    .sharedFlip(flip, "docked"),
                            ) {
                                ExpandingPromptInput(
                                    draft = draft, onDraft = { draft = it },
                                    onSubmit = ::send,
                                    onStop = { dispatch(MineAction.StopReply) },
                                    onEnterHandsFree = { dispatch(MineAction.StartVoice) },
                                    loading = busy,
                                    webOn = webOn,
                                    onToggleWeb = { dispatch(MineAction.ToggleSetting(SettingKey.smartWebSearchEnabled)) },
                                    focusTick = focusTick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the list: a message, or the "working" placeholder while Mine thinks. */
private sealed interface ChatRow {
    val key: String
    data class Msg(val m: ChatMessage, val streaming: Boolean) : ChatRow { override val key = m.id }
    data object Working : ChatRow { override val key = "working" }
    data class Error(val e: ChatErrorUi) : ChatRow { override val key = "error" }
}

@Composable
private fun MessageList(
    modifier: Modifier,
    messages: List<ChatMessage>,
    busy: Boolean,
    webQuery: String?,
    error: ChatErrorUi?,
    copiedId: String?,
    speakingId: String?,
    onCopy: (String, String) -> Unit,
    isPastDay: Boolean = false,
    onReply: (ChatMessage) -> Unit = {},
    onTalkAbout: (ChatMessage) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Long-press opens a small action row under that one message.
    var menuFor by remember { mutableStateOf<String?>(null) }
    val initialIds = remember { messages.map { it.id }.toHashSet() }
    val rows = remember(messages, busy, error) {
        val lastAssistantIdx = messages.indexOfLast { it.sender == Sender.assistant && !it.isSystem }
        val tailIsUser = messages.lastOrNull()?.sender == Sender.user
        buildList<ChatRow> {
            messages.forEachIndexed { i, m -> add(ChatRow.Msg(m, streaming = busy && i == lastAssistantIdx && i == messages.lastIndex)) }
            if (busy && (tailIsUser || messages.lastOrNull()?.text.isNullOrEmpty())) add(ChatRow.Working)
            if (error != null) add(ChatRow.Error(error))
        }
    }

    // Stick to the bottom while new content arrives, unless the user scrolled up to read.
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, canGoFurther) ->
                if (scrolling) stick = !canGoFurther else if (!canGoFurther) stick = true
            }
    }
    val tailSignature = rows.lastOrNull()?.let { r -> r.key + ":" + ((r as? ChatRow.Msg)?.m?.text?.length ?: 0) }
    LaunchedEffect(tailSignature) {
        if (stick && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE / 4)
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bubbleMax = (maxWidth - 48.dp) * 0.82f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Msg -> {
                        val m = row.m
                        val animate = m.id !in initialIds
                        when {
                            m.isSystem -> Txt(
                                m.text, MineType.Footnote, Mine.Ink3,
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp), textAlign = TextAlign.Center,
                            )
                            m.isReminder -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LIcon(Lucide.Bell, 12.dp, Hex.Mute)
                                Txt(" ${m.text}", MineType.Footnote, Mine.Ink3)
                            }
                            m.sender == Sender.user -> Presence(
                                visible = true,
                                animateOnFirst = animate,
                                initial = Fx(alpha = 0f, y = 10.dp, blur = 4.dp),
                                enter = tween(240, easing = MineMotion.FmEaseInOut),
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SwipeToReply(enabled = !isPastDay, onReply = { onReply(m) }) {
                                        Column(
                                            Modifier
                                                .widthIn(max = bubbleMax)
                                                .background(Hex.Cream, UserBubble)
                                                .messageActions(onLongPress = { menuFor = m.id })
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            m.replyTo?.let { QuoteBlock(it, onLight = true) }
                                            Txt(m.text, mineText(14.5f, 500, lineHeight = 1.625f), Hex.Jet)
                                        }
                                    }
                                    if (menuFor == m.id) MessageMenu(isPastDay, onReply = { menuFor = null; onReply(m) }, onTalkAbout = { menuFor = null; onTalkAbout(m) }, onCopy = { menuFor = null; onCopy(m.id, m.text) })
                                    if (m.queued) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            LIcon(Lucide.Clock, 11.dp, Hex.Mute)
                                            Txt("Will send when you're back online", MineType.Caption, Hex.Mute)
                                        }
                                    }
                                }
                            }
                            else -> Presence(
                                visible = true,
                                animateOnFirst = animate,
                                initial = Fx(alpha = 0f, y = 10.dp),
                                enter = tween(250, easing = MineMotion.FmEaseInOut),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SwipeToReply(enabled = !isPastDay && !row.streaming && m.text.isNotBlank(), onReply = { onReply(m) }) {
                                        Box(Modifier.messageActions(enabled = m.text.isNotBlank() && !row.streaming, onLongPress = { menuFor = m.id })) {
                                            AssistantTurn(m, row.streaming, webQuery.takeIf { row.streaming }, copiedId == m.id, speakingId == m.id, onCopy)
                                        }
                                    }
                                    if (menuFor == m.id) MessageMenu(isPastDay, onReply = { menuFor = null; onReply(m) }, onTalkAbout = { menuFor = null; onTalkAbout(m) }, onCopy = { menuFor = null; onCopy(m.id, m.text) })
                                }
                            }
                        }
                    }
                    ChatRow.Working -> WorkingIndicator(webQuery)
                    is ChatRow.Error -> ChatErrorRow(row.e)
                }
            }
        }
    }
}

/** Three soft dots while Mine thinks; names the web search when one is running. */
@Composable
private fun WorkingIndicator(webQuery: String?) {
    val reduce = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "think")
    val phase by t.animateFloat(0f, 1f, InfiniteRepeatableSpec(tween(1200, easing = MineMotion.Linear), RepeatMode.Restart), label = "p")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = "Mine is thinking" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (0..2).forEach { i ->
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            val local = ((phase + i * 0.18f) % 1f)
                            alpha = if (reduce) 0.6f else 0.25f + 0.75f * kotlin.math.sin(local * Math.PI).toFloat().coerceAtLeast(0f)
                        }
                        .background(Hex.Bone, CircleShape),
                )
            }
        }
        if (webQuery != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LIcon(Lucide.Globe, 12.dp, Hex.Sky)
                Txt("Searching “${webQuery.take(40)}”", mineText(12f), Hex.Sky, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChatErrorRow(e: ChatErrorUi) {
    val dispatch = mineDispatch()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Mine.Urgent.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, Mine.Urgent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            LIcon(Lucide.TriangleAlert, 16.dp, Mine.Urgent, Modifier.padding(top = 1.dp))
            Txt(e.message, MineType.Footnote, Mine.Ink1, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 26.dp)) {
            if (e.canRetry) ErrorButton("Retry", primary = true) { dispatch(MineAction.RetryFailedMessage) }
            if (e.needsSettings) ErrorButton("Settings", primary = !e.canRetry) { dispatch(MineAction.Navigate(ScreenId.S07)) }
            ErrorButton("Dismiss", primary = false) { dispatch(MineAction.DismissChatError) }
        }
    }
}

@Composable
private fun ErrorButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        feel = MineMotion.Press.Chip,
        modifier = Modifier
            .height(32.dp)
            .background(if (primary) Mine.Ink1 else Color.White.copy(alpha = 0.06f), CircleShape)
            .padding(horizontal = 14.dp),
    ) { Txt(label, MineType.Caption.copy(fontSize = 12.5.sp), if (primary) Mine.Canvas else Mine.Ink1) }
}

@Composable
private fun AssistantTurn(
    m: ChatMessage,
    streaming: Boolean,
    webQuery: String?,
    isCopied: Boolean,
    isSpeaking: Boolean,
    onCopy: (String, String) -> Unit,
) {
    val dispatch = mineDispatch()
    val body = mineText(15f, lineHeight = 28f / 15f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.proactive) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LIcon(Lucide.Sparkles, 11.dp, Hex.Sky)
                Txt("Mine reached out", mineText(11.5f, 500), Hex.Sky)
            }
        }
        m.replyTo?.let { QuoteBlock(it, onLight = false) }
        if (streaming && webQuery != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LIcon(Lucide.Globe, 12.dp, Hex.Sky)
                Txt("Searched “${webQuery.take(40)}”", mineText(12f), Hex.Sky, maxLines = 1)
            }
        }

        if (m.text.isNotEmpty()) {
            // Streaming text renders plainly; markdown, selection and links once it settles.
            if (streaming) Txt(m.text, body, Hex.Bone)
            else MarkdownText(m.text, body, Hex.Bone)
        }

        m.ambiguityPrompt?.let { ProposalRow(m.id, it) }
        val receipts = m.receipts.orEmpty()
        if (receipts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                receipts.forEach { r -> key(r.id) { ReceiptChip(m.id, r) } }
            }
        }

        if (!streaming && m.text.isNotBlank()) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.tap { onCopy(m.id, m.text) }.semantics { contentDescription = "Copy" }.padding(2.dp)) {
                    FxSwap(
                        target = isCopied,
                        initial = Fx(alpha = 0f, scale = 0.9f),
                        exit = Fx(alpha = 0f, scale = 0.9f),
                        enter = tween(300, easing = MineMotion.FmEaseInOut),
                        wait = true,
                    ) { copied ->
                        if (copied) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                LIcon(Lucide.Check, 13.dp, Hex.Moss)
                                Txt("Copied", mineText(12f), Hex.Bone)
                            }
                        } else {
                            LIcon(Lucide.Copy, 13.dp, Hex.Mute)
                        }
                    }
                }
                Box(
                    Modifier
                        .tap { dispatch(MineAction.SpeakMessage(m.id, m.text)) }
                        .semantics { contentDescription = if (isSpeaking) "Stop reading" else "Read aloud" }
                        .padding(2.dp),
                ) {
                    LIcon(if (isSpeaking) Lucide.Square else Lucide.Volume2, 13.dp, if (isSpeaking) Hex.Sky else Hex.Mute, filled = isSpeaking)
                }
                if (m.timestamp.isNotBlank()) Txt(m.timestamp, mineText(11.5f).num(), Hex.Mute.copy(alpha = 0.7f))
            }
        }
    }
}

/* ======================================================================== */
/* Replies                                                                  */
/* ======================================================================== */

/** Long-press on a bubble (tap does nothing, so text selection and links keep working). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.messageActions(enabled: Boolean = true, onLongPress: () -> Unit): Modifier =
    if (!enabled) this else this.combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = onLongPress)

/** Drag a message right to reply, like WhatsApp: it follows the finger, a reply arrow appears, and it springs back. */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    var fired by remember { mutableStateOf(false) }
    Box(
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { fired = false },
                onDragEnd = { scope.launch { offset.animateTo(0f, tween(180)) } },
                onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                onHorizontalDrag = { change, dx ->
                    val next = (offset.value + dx).coerceIn(0f, trigger * 1.3f)
                    if (next > 0f) change.consume()
                    scope.launch { offset.snapTo(next) }
                    if (!fired && next >= trigger) { fired = true; onReply() }
                },
            )
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val p = (offset.value / trigger).coerceIn(0f, 1f)
        Box(Modifier.graphicsLayer { alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p }.padding(start = 4.dp)) {
            LIcon(Lucide.Reply, 16.dp, Hex.Mute)
        }
        Box(Modifier.offset { IntOffset(offset.value.toInt(), 0) }) { content() }
    }
}

/** The quoted message inside a bubble: who said it (and when, if another day), two lines of it. */
@Composable
private fun QuoteBlock(q: ReplyQuote, onLight: Boolean) {
    val ink = if (onLight) Hex.Jet else Hex.Bone
    val bg = if (onLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.05f)
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (q.fromAssistant) Hex.Sky else Hex.Gold))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(
                (if (q.fromAssistant) "Mine" else "You") + (q.dayKey?.let { " · $it" } ?: ""),
                mineText(11.5f, 600), if (q.fromAssistant) Hex.Sky else Hex.Gold,
            )
            Txt(q.preview.ifBlank { "Message" }, mineText(12.5f, lineHeight = 1.4f), ink.copy(alpha = 0.75f), maxLines = 2)
        }
    }
}

/** Shown above the composer while replying. */
@Composable
private fun ReplyBar(r: ReplyDraft, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Hex.ChatBg)
            .padding(start = 20.dp, end = 20.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(46.dp).background(if (r.sender == Sender.assistant) Hex.Sky else Hex.Gold))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Txt(
                    "Replying to ${if (r.sender == Sender.assistant) "Mine" else "yourself"}" + (r.dayLabel?.let { " · $it" } ?: ""),
                    mineText(11.5f, 600), if (r.sender == Sender.assistant) Hex.Sky else Hex.Gold,
                )
                Txt(r.preview, mineText(12.5f), Hex.Bone.copy(alpha = 0.75f), maxLines = 1)
            }
            Box(Modifier.size(36.dp).tap(onClick = onClose).semantics { contentDescription = "Cancel reply" }, contentAlignment = Alignment.Center) {
                LIcon(Lucide.X, 14.dp, Hex.Mute)
            }
        }
    }
}

/** The small row a long-press opens: Reply (today) or Talk about this (past days), and Copy. */
@Composable
private fun MessageMenu(isPastDay: Boolean, onReply: () -> Unit, onTalkAbout: () -> Unit, onCopy: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val pill = Modifier.height(32.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 12.dp)
        if (isPastDay) {
            Row(pill.tap(onClick = onTalkAbout), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LIcon(Lucide.Reply, 13.dp, Hex.Sky); Txt("Talk about this", mineText(12.5f, 500), Hex.Bone)
            }
        } else {
            Row(pill.tap(onClick = onReply), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LIcon(Lucide.Reply, 13.dp, Hex.Sky); Txt("Reply", mineText(12.5f, 500), Hex.Bone)
            }
        }
        Row(pill.tap(onClick = onCopy), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LIcon(Lucide.Copy, 13.dp, Hex.Mute); Txt("Copy", mineText(12.5f, 500), Hex.Bone)
        }
    }
}
