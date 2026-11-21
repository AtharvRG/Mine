package com.mine.companion.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mine.companion.store.AmbientState
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.ItemType
import com.mine.companion.store.MineAction
import com.mine.companion.store.MineItem
import com.mine.companion.store.ModalId
import com.mine.companion.store.ScreenId
import com.mine.companion.store.SettingKey
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.components.DoneRow
import com.mine.companion.ui.components.FloatingChatPill
import com.mine.companion.ui.components.TaskRow
import com.mine.companion.ui.components.ThumbDock
import com.mine.companion.ui.components.rememberPillState
import com.mine.companion.ui.icons.IconStreakFlame
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.AnimatedColumn
import com.mine.companion.ui.primitives.ExitMode
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.GroupRow
import com.mine.companion.ui.primitives.GroupedSurface
import com.mine.companion.ui.primitives.InlineSkeleton
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.MineSwitch
import com.mine.companion.ui.primitives.Num
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Skeleton
import com.mine.companion.ui.primitives.TaskRowSkeleton
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.interp
import com.mine.companion.ui.theme.lerp
import com.mine.companion.ui.theme.matPaper
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.smoothstep
import com.mine.companion.ui.theme.w
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private fun partOfDay(h: Int) = when {
    h < 5 -> "Late night"
    h < 12 -> "Morning"
    h < 17 -> "Afternoon"
    else -> "Evening"
}

private val TodayTime = Regex("^Today (.+)$")

/** The latest time something is due today, in the label's own 12/24-hour format. */
private fun lastTimeToday(items: List<MineItem>): String? =
    items.mapNotNull { TodayTime.find(it.dueLabel)?.groupValues?.get(1) }
        .maxByOrNull { t -> clockMinutes(t) }

private fun clockMinutes(t: String): Int {
    val m = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").find(t) ?: return 0
    var h = m.groupValues[1].toInt() % 12
    val pm = m.groupValues[3].lowercase() == "pm"
    if (m.groupValues[3].isEmpty()) h = m.groupValues[1].toInt() else if (pm) h += 12
    return h * 60 + m.groupValues[2].toInt()
}

@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(15_000)
            value = LocalDateTime.now()
        }
    }
    return now
}

@Composable
private fun LedeLink(count: Int, noun: String, trailing: String?, onClick: () -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val strong = MineType.Lede.w(600)
    val rule = Mine.Ink4
    Row(verticalAlignment = Alignment.Bottom) {
        Row(
            Modifier
                .tap(onClick = onClick)
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val y = l.firstBaseline + 4.dp.toPx()
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Num(count, strong, Mine.Ink1)
            Txt(" $noun", strong, Mine.Ink1, onTextLayout = { layout = it })
        }
        if (trailing != null) Txt(trailing, MineType.Lede, Mine.Ink3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Briefing(
    now: LocalDateTime,
    userName: String,
    hydrating: Boolean,
    open: Int,
    pinned: Int,
    habits: Int,
    lastTime: String?,
) {
    val dispatch = mineDispatch()
    val lede = MineType.Lede
    val density = LocalDensity.current
    val space = with(density) { (lede.fontSize.toPx() * 0.26f).toDp() }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(space)) {
        if (userName.isBlank()) {
            Txt("${partOfDay(now.hour)}.", lede, Mine.Ink3)
        } else {
            Txt("${partOfDay(now.hour)},", lede, Mine.Ink3)
            Row {
                Txt(userName.trim(), lede.w(600), Mine.Ink1)
                Txt(".", lede, Mine.Ink3)
            }
        }
        when {
            hydrating -> Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { InlineSkeleton() }
            open == 0 -> listOf("Nothing", "left", "today.").forEach { Txt(it, lede, Mine.Ink3) }
            else -> {
                Txt("You", lede, Mine.Ink3)
                Txt("have", lede, Mine.Ink3)
                LedeLink(
                    open, if (open == 1) "thing" else "things",
                    trailing = when {
                        pinned > 0 -> ","
                        habits > 0 -> null
                        else -> "."
                    },
                ) { dispatch(MineAction.Navigate(ScreenId.S22)) }
                if (pinned > 0) {
                    LedeLink(pinned, if (pinned == 1) "pinned note" else "pinned notes", trailing = if (habits > 0) null else ".") {
                        dispatch(MineAction.Navigate(ScreenId.S41))
                    }
                }
                if (habits > 0) {
                    Txt("and", lede, Mine.Ink3)
                    LedeLink(habits, if (habits == 1) "habit" else "habits", trailing = ".") {
                        dispatch(MineAction.Navigate(ScreenId.S21))
                    }
                }
                if (lastTime != null) {
                    Txt("Last", lede, Mine.Ink3)
                    Txt("one", lede, Mine.Ink3)
                    Txt("at", lede, Mine.Ink3)
                    Row {
                        Txt(lastTime, lede.num(), Mine.Ink2)
                        Txt(".", lede, Mine.Ink3)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSkeleton() {
    GroupedSurface {
        repeat(3) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.42f).height(12.dp))
                    Skeleton(Modifier.fillMaxWidth(0.28f).height(9.dp))
                }
                Skeleton(Modifier.size(46.dp, 28.dp))
            }
        }
    }
}

private sealed interface HomeRow {
    val key: String
    data class Open(val item: MineItem) : HomeRow { override val key = "open-${item.id}" }
    data class Done(val item: MineItem) : HomeRow { override val key = "done-${item.id}" }
}

private data class Snaps(val expanded: Float, val peek: Float, val revealed: Float)

@Composable
fun S14Home() {
    val dispatch = mineDispatch()
    val haptics = rememberHaptics()
    val now = rememberNow()
    val pill = rememberPillState()

    val items = mineSelect { it.items }
    val userName = mineSelect { it.userName }
    val hydrating = mineSelect { it.isHydrating }
    val pinnedNotes = mineSelect { s -> s.livingNotes.count { it.pinned } }
    val contextLabel = mineSelect { it.platform.contextLabel }
    val readingNotifications = mineSelect { it.engine.notificationReading.enabled && it.platform.notificationAccess }
    val weekDone = mineSelect { it.engine.latestInsightsReport?.weeklyReview?.completedCount }
    val inbox = mineSelect { it.inboxItems.size }
    val ambientPaused = mineSelect { it.ambientListeningPaused }
    val ambientState = mineSelect { it.ambientState }
    val guard = mineSelect { it.contextSensing5GGuard }

    val openItems = remember(items) {
        items.filter { it.status != ItemStatus.done && it.status != ItemStatus.trashed && it.status != ItemStatus.archived }
    }
    val doneItems = remember(items) { items.filter { it.status == ItemStatus.done } }
    val habits = openItems.count { it.type == ItemType.habit }
    val streak = max(0, items.maxOfOrNull { it.streakDays ?: 0 } ?: 0)
    val lastTime = lastTimeToday(openItems)
    val hasUrgent = openItems.any { it.slipCount > 0 }
    val slips = items.sumOf { it.slipCount }

    val ambientDetail = when {
        ambientPaused -> "Off. Nothing is being heard."
        ambientState is AmbientState.Sleeping -> "Asleep · ${ambientState.reason.display}"
        readingNotifications -> "Listening · reading notifications aloud"
        else -> "Listening · audio stays on this phone"
    }
    val placeDetail = if (guard) contextLabel ?: "Learning where home is" else "Off. Mine won't use your location."

    BoxWithConstraints(Modifier.fillMaxSize().background(Mine.Backdrop)) {
        val density = LocalDensity.current
        val containerH = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val d = density.density
        val scope = rememberCoroutineScope()
        val dimMax = if (Mine.IsDark) 0.5f else 0.28f

        var snaps by remember { mutableStateOf(Snaps(statusTop + 52f * d, 300f * d, 560f * d)) }
        val y = remember { mutableFloatStateOf(300f * d) }
        val runway = remember { mutableFloatStateOf(300f * d) }
        val measured = remember { booleanArrayOf(false) }
        val headerBottom = remember { floatArrayOf(-1f) }
        val groupBottom = remember { floatArrayOf(-1f) }
        val job = remember { arrayOfNulls<Job>(1) }

        fun remeasure() {
            if (headerBottom[0] < 0f || groupBottom[0] < 0f) return
            val next = Snaps(statusTop + 52f * d, headerBottom[0] + 16f * d, groupBottom[0] + 20f * d)
            if (next != snaps) snaps = next
            if (!measured[0]) {
                measured[0] = true
                y.floatValue = next.peek
                runway.floatValue = next.peek
            }
        }

        fun springTo(target: Float, velocity: Float = 0f) {
            runway.floatValue = target
            job[0]?.cancel()
            job[0] = scope.launch {
                animate(y.floatValue, target, velocity, MineMotion.sheet()) { v, _ -> y.floatValue = v }
            }
        }

        // Everything Home draws lives in this layer, so it can recede behind the opening sheet.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = pill.value.coerceIn(0f, 1f)
                    val s = lerp(1f, 0.93f, e)
                    scaleX = s
                    scaleY = s
                    clip = e > 0.001f
                    shape = RoundedCornerShape((22f * e).dp)
                }
                .background(Mine.Canvas),
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .onPlaced { c ->
                            headerBottom[0] = c.positionInParent().y + c.size.height
                            remeasure()
                        }
                        .graphicsLayer {
                            val s = snaps
                            alpha = interp(y.floatValue, s.expanded, s.peek, 0f, 1f)
                            translationY = interp(y.floatValue, s.expanded, s.peek, -12f * d, 0f)
                        }
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("${now.dayOfMonth}", MineType.Display.num(), Mine.Ink1)
                            Presence(visible = hasUrgent, initial = Fx(scale = 0f), enter = MineMotion.snappy()) {
                                Box(
                                    Modifier
                                        .padding(top = 10.dp)
                                        .size(8.dp)
                                        .background(Mine.Urgent, CircleShape)
                                        .semantics { contentDescription = "Something has slipped" },
                                )
                            }
                        }
                        Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.End) {
                            Txt(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), MineType.Footnote.w(500), Mine.Ink2, textAlign = TextAlign.End)
                            Txt(now.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())), MineType.Footnote.num(), Mine.Ink3, textAlign = TextAlign.End)
                        }
                    }

                    Briefing(now, userName, hydrating, openItems.size, pinnedNotes, habits, lastTime)

                    if (streak > 0 || inbox > 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (streak > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IconStreakFlame(13.dp, Mine.Accent)
                                    Num(streak, MineType.Caption.w(500), Mine.Ink2, suffix = "-day streak")
                                }
                            }
                            if (inbox > 0) {
                                Row(
                                    Modifier
                                        .tap { dispatch(MineAction.Navigate(ScreenId.S24)) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(6.dp).background(Mine.Accent, CircleShape))
                                    Num(inbox, MineType.Caption.w(500), Mine.Ink2, suffix = " to review")
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier
                        .onPlaced { c ->
                            groupBottom[0] = c.positionInParent().y + c.size.height
                            remeasure()
                        }
                        .graphicsLayer {
                            val s = snaps
                            alpha = interp(y.floatValue, s.peek, s.revealed, 0.4f, 1f)
                            val sc = interp(y.floatValue, s.peek, s.revealed, 0.97f, 1f)
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp),
                ) {
                    Txt("Right now", MineType.Footnote.w(500), Mine.Ink3, Modifier.padding(start = 16.dp, bottom = 8.dp))
                    if (hydrating) {
                        GroupSkeleton()
                    } else {
                        GroupedSurface {
                            GroupRow(
                                title = "Ambient listening",
                                detail = ambientDetail,
                                divider = true,
                                onClick = { dispatch(MineAction.Navigate(ScreenId.S36)) },
                                trailing = {
                                    MineSwitch(
                                        checked = !ambientPaused,
                                        onCheckedChange = { dispatch(MineAction.ToggleSetting(SettingKey.ambientListeningPaused)) },
                                        label = "Ambient listening",
                                    )
                                },
                            )
                            GroupRow(
                                title = "Place sensing",
                                detail = placeDetail,
                                divider = true,
                                onClick = { dispatch(MineAction.Navigate(ScreenId.S34)) },
                                trailing = {
                                    MineSwitch(
                                        checked = guard,
                                        onCheckedChange = { dispatch(MineAction.ToggleSetting(SettingKey.contextSensing5GGuard)) },
                                        label = "Place sensing",
                                    )
                                },
                            )
                            GroupRow(
                                title = "This week",
                                detail = if (weekDone != null) "$weekDone done this week · $slips slipped" else "${doneItems.size} done today · $slips slipped",
                                onClick = { dispatch(MineAction.Navigate(ScreenId.S40)) },
                                trailing = { LIcon(Lucide.ChevronRight, 16.dp, Mine.Ink4) },
                            )
                        }
                    }
                }
            }

            // Today sheet
            val dragStart = remember { floatArrayOf(0f, 0f) }
            val dragState = rememberDraggableState { delta ->
                dragStart[1] += delta
                val s = snaps
                y.floatValue = MineMotion.rubberband(dragStart[0] + dragStart[1], s.expanded, s.revealed)
            }

            Column(
                Modifier
                    .offset { IntOffset(0, y.floatValue.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { containerH.toDp() })
                    .matPaper(Shapes.SheetTop)
                    .semantics { contentDescription = "Today" },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = {
                                job[0]?.cancel()
                                dragStart[0] = y.floatValue
                                dragStart[1] = 0f
                            },
                            onDragStopped = { v ->
                                val s = snaps
                                val target = MineMotion.projectToDetent(y.floatValue, v, listOf(s.expanded, s.peek, s.revealed))
                                if (abs(target - dragStart[0]) > 4f * d) haptics.tick()
                                springTo(target, v)
                            },
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                val s = snaps
                                springTo(if (abs(y.floatValue - s.peek) < 8f * d) s.expanded else s.peek)
                            })
                        }
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 8.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 5.dp)
                            .background(Mine.PaperInk1.copy(alpha = 0.16f), CircleShape),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Txt("Today", MineType.Headline, Mine.PaperInk1, Modifier.alignByBaseline())
                        Row(Modifier.alignByBaseline(), verticalAlignment = Alignment.Bottom) {
                            Num(doneItems.size, MineType.Footnote, Mine.PaperInk2)
                            Txt("/", MineType.Footnote, Mine.PaperInk2)
                            Num(doneItems.size + openItems.size, MineType.Footnote, Mine.PaperInk2)
                            Txt(" done", MineType.Footnote, Mine.PaperInk2)
                        }
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    if (hydrating) {
                        TaskRowSkeleton()
                        TaskRowSkeleton(0.54f to 0.22f)
                        TaskRowSkeleton(0.72f to 0.30f)
                    } else {
                        val rows = remember(openItems, doneItems) {
                            openItems.map { HomeRow.Open(it) } + doneItems.map { HomeRow.Done(it) }
                        }
                        AnimatedColumn(
                            items = rows,
                            key = { it.key },
                            enter = Fx(alpha = 0f, y = 6.dp),
                            exit = Fx.Hidden,
                            enterSpec = MineMotion.gentle(),
                            exitSpec = MineMotion.snappy(),
                            exitAlphaSpec = MineMotion.fade(),
                            mode = ExitMode.Collapse,
                            layoutSpec = MineMotion.gentle(),
                        ) { row ->
                            when (row) {
                                is HomeRow.Open -> TaskRow(
                                    item = row.item,
                                    onOpen = { dispatch(MineAction.OpenModal(ModalId.M04, itemId = row.item.id)) },
                                    onComplete = { dispatch(MineAction.CompleteItem(row.item.id)) },
                                    onSnooze = { dispatch(MineAction.RescheduleItem(row.item.id, "Tomorrow 09:00")) },
                                )
                                is HomeRow.Done -> DoneRow(row.item) { dispatch(MineAction.UndoCompleteItem(row.item.id)) }
                            }
                        }
                    }

                    if (!hydrating && openItems.isEmpty()) {
                        Presence(visible = true, initial = Fx(alpha = 0f, y = 6.dp), enter = MineMotion.gentle()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LIcon(Lucide.Sparkles, 20.dp, Mine.PaperInk3, strokeWidth = 1.75f)
                                Txt("Clear for today", MineType.Callout.w(500), Mine.PaperInk1, Modifier.padding(top = 12.dp))
                                Txt("Anything you tell Mine shows up here.", MineType.Footnote, Mine.PaperInk2, Modifier.padding(top = 4.dp))
                            }
                        }
                    }

                    QuickAddRow { text -> dispatch(MineAction.QuickAddOfflineItem(text)) }

                    // Runway so the last row clears the pill. Sized by the snap the sheet is
                    // heading to — relayout once per settle, never per drag frame.
                    Spacer(
                        Modifier.layout { m, c ->
                            val p = m.measure(c.copy(minHeight = 0, maxHeight = 0))
                            layout(p.width, (runway.floatValue + navBottom + 96f * d).roundToInt().coerceAtLeast(0)) {}
                        },
                    )
                }
            }

            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - smoothstep(0f, 0.18f, pill.value.coerceAtLeast(0f)) }) {
                ThumbDock()
            }

            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dimMax * pill.value.coerceIn(0f, 1f) }
                    .background(Color.Black),
            )
        }

        FloatingChatPill(pill)
    }
}

@Composable
private fun QuickAddRow(onSubmit: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LIcon(Lucide.Plus, 16.dp, Mine.PaperInk3)
        MineField(
            value = draft,
            onValueChange = { draft = it },
            style = MineType.Callout,
            color = Mine.PaperInk1,
            placeholder = "Add something — “gym at 7”",
            placeholderColor = Mine.PaperInk3,
            imeAction = ImeAction.Done,
            onSubmit = {
                if (draft.isNotBlank()) {
                    onSubmit(draft)
                    draft = ""
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
