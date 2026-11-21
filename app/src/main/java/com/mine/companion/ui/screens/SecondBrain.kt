package com.mine.companion.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mine.companion.store.AlarmTierUi
import com.mine.companion.store.InboxKind
import com.mine.companion.store.InboxTriageItem
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.LivingNote
import com.mine.companion.store.MemoryTab
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.NoteAccessMode
import com.mine.companion.store.ReminderStatus
import com.mine.companion.store.ScreenId
import com.mine.companion.store.TriggerType
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.components.CalendarColors
import com.mine.companion.ui.components.MineCalendar
import java.time.LocalDate
import com.mine.companion.ui.icons.IconLocalVault
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.AnimatedColumn
import com.mine.companion.ui.primitives.ExitMode
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.blurEffect
import com.mine.companion.ui.theme.hairlineBottom
import com.mine.companion.ui.theme.hairlineTop
import com.mine.companion.ui.theme.lh
import com.mine.companion.ui.theme.mineSurface
import com.mine.companion.ui.theme.mineSurfaceSubtle
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.serif

private val RowRule: Color get() = Mine.PaperHairline
private val R8 = RoundedCornerShape(8.dp)

/** Paper list sheet that fills the rest of the screen and scrolls. */
@Composable
private fun ScrollPaper(
    modifier: Modifier = Modifier,
    top: Dp = 20.dp,
    spacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Mine.Paper, PaperTop)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = top, bottom = 24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
    }
}

private val PopEnter = Fx(alpha = 0f, y = 6.dp)

/* ================================ S20 About You ================================ */

@Composable
fun S20AboutYouSoul() {
    val dispatch = mineDispatch()
    val name = mineSelect { it.userName }
    val facts = mineSelect { it.soulFacts }
    LegacyScreen {
        BackHeader("About You", onBack = { dispatch(MineAction.GoBack) }, trailing = {
            Row(
                Modifier.tap { dispatch(MineAction.OpenModal(ModalId.M08)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LIcon(Lucide.Eye, 14.dp, Hex.Sky)
                Txt("What Mine sees", mineText(12f), Hex.Sky)
            }
        })
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(if (name.isBlank()) "About you" else "${name.trim()}’s Soul", serifHeading(34f, 1.25f), Hex.Bone)
            Txt(
                buildAnnotatedString {
                    append("Facts locked as ")
                    withStyle(mineText(14f).copy(color = Hex.Sky).toSpanStyle()) { append("Local only") }
                    append(" stay on this device and are stripped before any prompt is sent.")
                },
                mineText(14f, lineHeight = 1.625f), Hex.Mute,
            )
        }
        AboutYouChatCard()
        Spacer(Modifier.height(12.dp))
        ScrollPaper(Modifier.weight(1f), top = 24.dp, spacing = 8.dp) {
            if (facts.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Nothing yet", mineText(15f, 500), Hex.Jet)
                    Txt("As you chat, Mine suggests things worth remembering. You approve each one.", mineText(13f), Hex.Zinc500, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            AnimatedColumn(items = facts, key = { it.id }, spacing = 8.dp, enter = Fx.Visible) { fact ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hairlineBottom(Mine.PaperInk1.copy(alpha = 0.07f))
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dot by animateColorAsState(if (fact.localOnly) Hex.Ember else Hex.Moss, tween(200, easing = MineMotion.TwEase), label = "soul-dot")
                            Box(Modifier.size(8.dp).background(dot, CircleShape))
                            Txt(fact.category.label, mineText(11.5f, 500), Hex.Zinc500)
                        }
                        Txt(fact.text, mineText(14.5f, 500, lineHeight = 1.375f), Hex.Jet)
                    }
                    Row(
                        Modifier
                            .background(if (fact.localOnly) Hex.Jet else Mine.PaperInk1.copy(alpha = 0.06f), CircleShape)
                            .tap { dispatch(MineAction.ToggleSoulLocalOnly(fact.id)) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconLocalVault(12.dp, if (fact.localOnly) Hex.Sky else Hex.Zinc600)
                        FxSwap(
                            target = fact.localOnly,
                            initial = Fx(alpha = 0f, y = 2.dp),
                            exit = Fx(alpha = 0f, y = (-2).dp),
                            enter = tween(140, easing = MineMotion.FmEaseInOut),
                        ) { local ->
                            Txt(if (local) "Local only" else "Shared", mineText(11.5f, 500), if (local) Hex.Cream else Hex.Zinc600)
                        }
                    }
                }
            }
        }
    }
}

/* ================================ S21 Reminders ================================ */

/** Equal-width segmented track with a sliding pill (layoutId), springing on [stiffness]/[damping]. */
@Composable
private fun <T> SegmentedEqual(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    trackColor: Color,
    pillColor: Color,
    activeText: Color,
    idleText: Color,
    trackShape: RoundedCornerShape,
    pillShape: RoundedCornerShape,
    textSize: Float,
    activeWeight: Int = 500,
    stiffness: Float = 400f,
    damping: Float = 32f,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(trackColor, trackShape)
            .padding(4.dp),
    ) {
        val gap = 4.dp
        val segW = (maxWidth - gap * (options.size - 1)) / options.size
        val idx = options.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState((segW + gap) * idx, MineMotion.fm(stiffness, damping), label = "seg-pill")
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .width(segW)
                .height(with(LocalDensity.current) { (textSize * 1.5f).dp } + 12.dp)
                .background(pillColor, pillShape),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { o ->
                val on = o == selected
                Box(
                    Modifier
                        .width(segW)
                        .tap { onSelect(o) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(label(o), mineText(textSize, if (on) activeWeight else 500), if (on) activeText else idleText) }
            }
        }
    }
}

@Composable
fun S21Reminders() {
    val dispatch = mineDispatch()
    val reminders = mineSelect { it.reminders }
    var tab by remember { mutableStateOf(ReminderStatus.upcoming) }
    val filtered = reminders.filter { it.status == tab }
    LegacyScreen {
        BackHeader("Reminders", onBack = { dispatch(MineAction.GoBack) }, trailing = {
            Box(Modifier.offset(x = 8.dp).tap { dispatch(MineAction.OpenModal(ModalId.M09)) }.padding(8.dp)) {
                LIcon(Lucide.Plus, 20.dp, Hex.Sky)
            }
        })
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt("Reminders", serifHeading(32f, 1.25f), Mine.Ink1)
            SegmentedEqual(
                options = ReminderStatus.entries.toList(),
                selected = tab,
                label = { it.name.replaceFirstChar { c -> c.uppercase() } },
                onSelect = { tab = it },
                trackColor = Hex.Field,
                pillColor = Hex.Cream,
                activeText = Hex.Jet,
                idleText = Hex.Mute,
                trackShape = R16,
                pillShape = R12,
                textSize = 12.5f,
            )
        }
        ScrollPaper(Modifier.weight(1f), spacing = 4.dp) {
            if (filtered.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(
                        when (tab) { ReminderStatus.upcoming -> "Nothing coming up"; ReminderStatus.missed -> "Nothing missed"; ReminderStatus.done -> "Nothing finished today" },
                        mineText(15f, 500), Hex.Jet,
                    )
                    if (tab == ReminderStatus.upcoming) Txt("Tap + or just tell Mine: “remind me to stretch at 4”.", mineText(13f), Hex.Zinc500)
                }
            }
            AnimatedColumn(
                items = filtered,
                key = { it.id },
                spacing = 4.dp,
                enter = PopEnter,
                exit = Fx(alpha = 0f, scale = 0.96f, blur = 4.dp),
                enterSpec = tween(200, easing = MineMotion.FmEaseInOut),
                exitSpec = tween(200, easing = MineMotion.FmEaseInOut),
                mode = ExitMode.PopLayout,
                animateInitial = true,
            ) { rem ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hairlineBottom(RowRule)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(3.dp).height(20.dp).background(if (rem.tier == AlarmTierUi.ALARM) Hex.Ember else Hex.Indigo, CircleShape))
                        Column {
                            Txt(rem.title, mineText(14.5f, 500), Hex.Jet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Txt("${if (rem.tier == AlarmTierUi.ALARM) "Exact Alarm" else "Gentle"} · ${rem.recurrence?.label ?: "Once"}", mineText(11.5f), Hex.Zinc500)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FxSwap(
                            target = rem.time,
                            initial = Fx(alpha = 0f, y = 3.dp),
                            exit = Fx(alpha = 0f, y = (-3).dp),
                            enter = tween(300, easing = MineMotion.FmEaseInOut),
                        ) { t -> Txt(t, mineText(13f, 600, mono = true), Hex.Jet) }
                        if (rem.status != ReminderStatus.done) {
                            Txt(
                                "+15m", mineText(11f, 500), Hex.Jet,
                                Modifier
                                    .background(Mine.PaperInk1.copy(alpha = 0.06f), R8)
                                    .tap { dispatch(MineAction.SnoozeReminder(rem.id, 15)) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                            Box(Modifier.tap { dispatch(MineAction.CancelReminder(rem.id)) }.padding(4.dp)) {
                                LIcon(Lucide.X, 16.dp, Hex.Zinc500)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================ S22 Memory hub ================================ */

private val MemorySections = listOf(
    MemoryTab.Items to "Tasks",
    MemoryTab.Agenda to "Calendar",
    MemoryTab.Learned to "Wakeups",
    MemoryTab.PeoplePlaces to "Places",
    MemoryTab.Trash to "Trash",
)

/**
 * `flex gap-1 p-1 overflow-x-auto` with `flex-1 whitespace-nowrap` items: equal shares unless a
 * label's own width is larger, then horizontal scroll. The active pill slides between items.
 */
@Composable
private fun MemoryTabsTrack(active: MemoryTab, onSelect: (MemoryTab) -> Unit) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateOf(List(MemorySections.size) { 0 to 0 }) }
    val shape = R16
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Hex.Field, shape)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape),
    ) {
        val viewport = constraints.maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
            val idx = MemorySections.indexOfFirst { it.first == active }.coerceAtLeast(0)
            val (bx, bw) = bounds.value[idx]
            val x by animateFloatAsState(bx.toFloat(), MineMotion.fm(380f, 30f), label = "mem-x")
            val w by animateFloatAsState(bw.toFloat(), MineMotion.fm(380f, 30f), label = "mem-w")
            Box(
                Modifier
                    .offset { IntOffset(x.toInt(), 0) }
                    .width(with(density) { w.toDp() })
                    .height(30.dp)
                    .background(Hex.Cream, R12),
            )
            val gap = with(density) { 4.dp.roundToPx() }
            val inner = viewport - with(density) { 8.dp.roundToPx() }
            Layout(
                content = {
                    MemorySections.forEach { (tab, label) ->
                        val on = tab == active
                        Box(
                            Modifier
                                .height(30.dp)
                                .tap { onSelect(tab) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt(label, mineText(12.5f, if (on) 600 else 500), if (on) Hex.Jet else Hex.Mute, maxLines = 1) }
                    }
                },
            ) { measurables, _ ->
                // flex: 1 1 0 with min-content floors
                val mins = measurables.map { it.maxIntrinsicWidth(Int.MAX_VALUE) }
                val n = mins.size
                val free = inner - gap * (n - 1)
                val widths = IntArray(n)
                val frozen = BooleanArray(n)
                var remaining = free
                var open = n
                var changed = true
                while (changed && open > 0) {
                    changed = false
                    val share = remaining / open
                    for (i in 0 until n) if (!frozen[i] && mins[i] > share) {
                        frozen[i] = true; widths[i] = mins[i]; remaining -= mins[i]; open--; changed = true
                    }
                }
                if (open > 0) {
                    val share = (remaining / open).coerceAtLeast(0)
                    for (i in 0 until n) if (!frozen[i]) widths[i] = maxOf(share, mins[i])
                }
                val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], m.minIntrinsicHeight(widths[i]).coerceAtLeast(1))) }
                val total = widths.sum() + gap * (n - 1)
                val h = placeables.maxOf { it.height }
                layout(total, h) {
                    var x0 = 0
                    val next = ArrayList<Pair<Int, Int>>(n)
                    placeables.forEachIndexed { i, p ->
                        p.place(x0, 0)
                        next += x0 to widths[i]
                        x0 += widths[i] + gap
                    }
                    if (next != bounds.value) bounds.value = next
                }
            }
        }
    }
}

@Composable
fun S22MemoryHub() {
    val dispatch = mineDispatch()
    val items = mineSelect { it.items }
    val tab = mineSelect { it.memoryActiveTab }
    val wakeups = mineSelect { it.agentWakeups }
    val trash = mineSelect { it.engine.trashNodes }
    val peakHour = mineSelect { r -> r.engine.latestInsightsReport?.activityHeatStrip?.takeIf { it.totalActions > 0 }?.peakHour }
    val sensing = mineSelect { it.contextSensing5GGuard }
    val placeLabel = mineSelect { it.platform.contextLabel }
    var stage by remember { mutableStateOf(ItemStatus.todo) }

    LegacyScreen {
        BackHeader("Memory", onBack = { dispatch(MineAction.GoBack) }, trailing = {
            Txt("Connections →", mineText(12.5f), Hex.Sky, Modifier.tap { dispatch(MineAction.Navigate(ScreenId.S29)) })
        })
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Txt("Second Brain", serifHeading(34f, 1.25f), Hex.Bone, Modifier.alignByBaseline())
                Txt("${items.count { it.status != ItemStatus.done }} active", mineText(12f), Hex.Mute, Modifier.alignByBaseline())
            }
            MemoryTabsTrack(tab) { dispatch(MineAction.SetMemoryTab(it)) }
        }

        ScrollPaper(Modifier.weight(1f)) {
            FxSwap(
                target = tab,
                initial = Fx(alpha = 0f, y = 8.dp, blur = 4.dp),
                exit = Fx(alpha = 0f, y = (-6).dp, blur = 4.dp),
                enter = tween(220, easing = MineMotion.FmEaseInOut),
                wait = true,
                animateOnFirst = true,
            ) { t ->
                when (t) {
                    MemoryTab.Items -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val stages = listOf(ItemStatus.in_progress to "In Progress", ItemStatus.todo to "To Do", ItemStatus.done to "Completed")
                        SegmentedEqual(
                            options = stages.map { it.first },
                            selected = stage,
                            label = { s -> "${stages.first { it.first == s }.second} (${items.count { it.status == s }})" },
                            onSelect = { stage = it },
                            trackColor = Mine.PaperInk1.copy(alpha = 0.06f),
                            pillColor = Hex.Jet,
                            activeText = Hex.Cream,
                            idleText = Hex.Zinc500,
                            trackShape = R12,
                            pillShape = R8,
                            textSize = 12f,
                        )
                        AnimatedColumn(
                            items = items.filter { it.status == stage },
                            key = { it.id },
                            spacing = 4.dp,
                            enter = PopEnter,
                            exit = Fx(alpha = 0f, scale = 0.96f),
                            enterSpec = tween(200, easing = MineMotion.FmEaseInOut),
                            exitSpec = tween(200, easing = MineMotion.FmEaseInOut),
                            animateInitial = true,
                        ) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .hairlineBottom(RowRule)
                                    .tap { dispatch(MineAction.OpenModal(ModalId.M04, itemId = item.id)) }
                                    .padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val bar = when {
                                        item.slipCount > 0 -> Hex.Ember
                                        item.type == com.mine.companion.store.ItemType.habit -> Hex.Indigo
                                        item.type == com.mine.companion.store.ItemType.errand -> Hex.Amber
                                        else -> Hex.Moss
                                    }
                                    Box(Modifier.width(3.dp).height(20.dp).background(bar, CircleShape))
                                    Column {
                                        Txt(item.title, mineText(14.5f, 500), Hex.Jet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Txt(item.dueLabel + if (item.slipCount > 0) " · Slipped ${item.slipCount}x" else "", mineText(12f), Hex.Zinc500)
                                    }
                                }
                                Txt(
                                    when (item.status) {
                                        ItemStatus.todo -> "Start"
                                        ItemStatus.in_progress -> "Done"
                                        else -> "Reopen"
                                    },
                                    mineText(11.5f, 500), Hex.Jet,
                                    Modifier
                                        .background(Mine.PaperInk1.copy(alpha = 0.06f), CircleShape)
                                        .tap {
                                            val next = when (item.status) {
                                                ItemStatus.todo -> ItemStatus.in_progress
                                                ItemStatus.in_progress -> ItemStatus.done
                                                else -> ItemStatus.todo
                                            }
                                            if (next == ItemStatus.done) dispatch(MineAction.CompleteItem(item.id))
                                            else dispatch(MineAction.UpdateItemStatus(item.id, next))
                                        }
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    MemoryTab.Agenda -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var day by remember { mutableStateOf(LocalDate.now()) }
                        MineCalendar(colors = CalendarColors.paper(), initialDate = day, onSelect = { day = it })

                        val today = LocalDate.now()
                        val due = items.filter { item ->
                            when {
                                item.status == ItemStatus.trashed || item.status == ItemStatus.archived -> false
                                item.dueLabel.startsWith("Today") || item.triggerType == TriggerType.leave_home -> day == today
                                item.dueLabel.startsWith("Tomorrow") -> day == today.plusDays(1)
                                else -> false
                            }
                        }
                        if (due.isNotEmpty()) {
                            Txt(
                                if (day == today) "Due today" else "Due tomorrow",
                                mineText(12f, 500), Hex.Zinc500,
                                Modifier.padding(start = 8.dp, top = 8.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                due.forEach { item ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .hairlineBottom(RowRule)
                                            .tap { dispatch(MineAction.OpenModal(ModalId.M04, itemId = item.id)) }
                                            .padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Txt(
                                            item.title,
                                            mineText(14f, 500),
                                            if (item.status == ItemStatus.done) Hex.Zinc500 else Hex.Jet,
                                            Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            decoration = if (item.status == ItemStatus.done) TextDecoration.LineThrough else null,
                                        )
                                        Txt(
                                            item.dueLabel.removePrefix("Today ").removePrefix("Tomorrow "),
                                            mineText(12f, mono = true), Hex.Zinc500,
                                            Modifier.padding(start = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MemoryTab.Learned -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (peakHour != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Mine.PaperInk1.copy(alpha = 0.05f), R16)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Txt("BUSIEST HOUR THIS WEEK", mineText(11f, 500, tracking = 0.05f), Hex.Zinc500)
                                    Txt("%02d:00 – %02d:00".format(peakHour, (peakHour + 1) % 24), mineText(16f, 700), Hex.Jet)
                                }
                                LIcon(Lucide.Clock, 20.dp, Hex.Jet.copy(alpha = 0.5f))
                            }
                        }
                        if (wakeups.isEmpty()) {
                            Txt("No check-ins scheduled. Tell Mine “check on me in an hour” or start a break.", mineText(13.5f, lineHeight = 1.5f), Hex.Zinc500, Modifier.padding(vertical = 12.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            wakeups.forEach { w ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .hairlineBottom(RowRule)
                                        .padding(vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Txt(w.scheduledFor, mineText(14f, 600), Hex.Jet)
                                        Txt(
                                            w.sourceContext, mineText(10.5f, 500), Hex.Cream,
                                            Modifier.background(Hex.Jet, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
                                    Txt(w.reasonMotto, mineText(13.5f, lineHeight = 1.375f), Hex.Zinc600)
                                }
                            }
                        }
                    }

                    MemoryTab.PeoplePlaces -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hairlineBottom(RowRule)
                                .tap { dispatch(MineAction.Navigate(ScreenId.S34)) }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LIcon(Lucide.MapPin, 16.dp, Hex.Jet)
                            Column(Modifier.weight(1f)) {
                                Txt(if (sensing) placeLabel ?: "Learning where home is" else "Place sensing is off", mineText(14.5f, 500), Hex.Jet)
                                Txt(if (sensing) "Leave-home reminders are armed" else "Turn on to get reminders as you leave", mineText(12.5f), Hex.Zinc500)
                            }
                            LIcon(Lucide.ChevronRight, 14.dp, Hex.Zinc500)
                        }
                        items.filter { it.triggerType == TriggerType.leave_home && it.status != ItemStatus.done }.forEach { e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .hairlineBottom(RowRule)
                                    .tap { dispatch(MineAction.OpenModal(ModalId.M04, itemId = e.id)) }
                                    .padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(e.title, mineText(14.5f, 500), Hex.Jet, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Txt(e.fallbackTime?.let { "or $it" } ?: "on leaving", mineText(12f), Hex.Moss)
                            }
                        }
                    }

                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (trash.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Txt("Trash is empty", mineText(14f, 500), Hex.Jet)
                                Txt("Deleted items wait here for 30 days.", mineText(12.5f), Hex.Zinc500)
                            }
                        }
                        trash.forEach { n ->
                            Row(
                                Modifier.fillMaxWidth().hairlineBottom(RowRule).padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(n.title, mineText(14f, 500), Hex.Zinc600, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Txt(
                                    "Restore", mineText(11.5f, 500), Hex.Jet,
                                    Modifier.background(Mine.PaperInk1.copy(alpha = 0.06f), CircleShape)
                                        .tap { dispatch(MineAction.Engine(com.anchor.core.ui.UiAction.RestoreNode(n.id))) }
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                                Txt(
                                    "Erase", mineText(11.5f, 500), Hex.Ember,
                                    Modifier.tap { dispatch(MineAction.Engine(com.anchor.core.ui.UiAction.EraseNode(n.id))) }.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================ S23 Item detail ================================ */

@Composable
fun S23ItemDetailScreen() {
    val dispatch = mineDispatch()
    val item = mineSelect { s -> s.items.find { it.id == s.selectedItemId } }
    if (item == null) {
        LegacyScreen {
            BackHeader("Item", onBack = { dispatch(MineAction.GoBack) }, titleStyle = mineText(13.5f), titleColor = Hex.Mute)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Txt("This item was completed or removed.", mineText(14f), Hex.Mute)
            }
        }
        return
    }
    val done = item.status == ItemStatus.done
    LegacyScreen {
        BackHeader("Item", onBack = { dispatch(MineAction.GoBack) }, titleStyle = mineText(13.5f), titleColor = Hex.Mute)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(item.title, serifHeading(30f, 1.25f), Mine.Ink1)
            Txt(if (item.dueLabel == "No date") "No date" else "Due ${item.dueLabel}", mineText(13.5f), Hex.Sky)
        }
        PaperSheet(Modifier.weight(1f), padding = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailChip(if (done) "Reopen" else "Mark done", primary = true) {
                    dispatch(if (done) MineAction.UndoCompleteItem(item.id) else MineAction.CompleteItem(item.id))
                }
                if (!done) {
                    DetailChip("Tomorrow 9:00") { dispatch(MineAction.RescheduleItem(item.id, "Tomorrow 09:00")) }
                    DetailChip("+1 hour") { dispatch(MineAction.RescheduleItem(item.id, "in 1 hour")) }
                }
            }
            if (item.strikethroughHistory.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Moved ${item.slipCount} time${if (item.slipCount == 1) "" else "s"}", mineText(12f), Hex.Zinc500)
                    Txt(
                        buildAnnotatedString {
                            withStyle(mineText(14f).copy(color = Hex.Zinc500, textDecoration = TextDecoration.LineThrough).toSpanStyle()) {
                                append(item.strikethroughHistory.joinToString(" → "))
                            }
                            append("  → ${item.dueLabel}")
                        },
                        mineText(14f), Hex.Jet,
                    )
                }
            }
            if (item.streakDays != null) {
                Txt("${item.streakDays}-day streak · best ${item.bestStreakDays ?: item.streakDays}", mineText(14f, 500), Hex.Jet)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .hairlineTop(Mine.PaperHairline)
                    .tap { dispatch(MineAction.ToggleItemLocalOnly(item.id)) }
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconLocalVault(14.dp, if (item.localOnly == true) Hex.Sky else Hex.Zinc600)
                Column(Modifier.weight(1f)) {
                    Txt(if (item.localOnly == true) "On this phone only" else "Shared with your AI", mineText(14f, 500), Hex.Jet)
                    Txt(if (item.localOnly == true) "Never sent in prompts" else "Tap to keep it on this phone only", mineText(12f), Hex.Zinc500)
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .hairlineTop(Mine.PaperHairline)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt("Where it came from", mineText(12f), Hex.Zinc500)
                Txt(item.provenance, mineText(14f), Hex.Jet)
            }
            Spacer(Modifier.weight(1f))
            Txt(
                "Delete item", mineText(13.5f, 500), Hex.Ember,
                Modifier.tap {
                    dispatch(MineAction.UpdateItemStatus(item.id, ItemStatus.trashed))
                    dispatch(MineAction.GoBack)
                }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DetailChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Txt(
        label, mineText(12.5f, 500), if (primary) Hex.Cream else Hex.Jet,
        Modifier
            .background(if (primary) Hex.Jet else Mine.PaperInk1.copy(alpha = 0.06f), R12)
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/* ================================ S24 Inbox triage ================================ */

private sealed interface InboxEntry {
    val key: String
    data object Empty : InboxEntry { override val key = "empty-inbox" }
    data class Card(val item: InboxTriageItem) : InboxEntry { override val key = item.id }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun S24InboxTriage(embedded: Boolean = false) {
    val dispatch = mineDispatch()
    val inbox = mineSelect { it.inboxItems }
    val entries = if (inbox.isEmpty()) listOf(InboxEntry.Empty) else inbox.map { InboxEntry.Card(it) }

    val list: @Composable () -> Unit = {
        AnimatedColumn(
            items = entries,
            key = { it.key },
            spacing = 12.dp,
            enter = Fx(alpha = 0f, y = 8.dp),
            exit = Fx(alpha = 0f, scale = 0.95f, blur = 4.dp),
            enterSpec = tween(220, easing = MineMotion.FmEaseInOut),
            exitSpec = tween(220, easing = MineMotion.FmEaseInOut),
            animateInitial = true,
        ) { e ->
            when (e) {
                InboxEntry.Empty -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LIcon(Lucide.Check, 24.dp, Hex.Moss)
                    Txt("All caught up", mineText(15f, 500), Hex.Jet)
                    Txt("Nothing waiting for a decision.", mineText(13f), Hex.Zinc500)
                }
                is InboxEntry.Card -> {
                    val inb = e.item
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Mine.PaperInk1.copy(alpha = 0.04f), R16)
                            .border(1.dp, Mine.PaperInk1.copy(alpha = 0.07f), R16)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Txt(
                                when (inb.kind) {
                                    InboxKind.ambiguity -> "NEEDS A CHOICE"
                                    InboxKind.duplicate -> "POSSIBLE DUPLICATE"
                                    InboxKind.soul_proposal -> "ABOUT YOU"
                                    InboxKind.stale_30d -> "UNTOUCHED FOR 30 DAYS"
                                    InboxKind.ambient -> "HEARD WHILE LISTENING"
                                },
                                mineText(11f, 500, tracking = 0.05f), Hex.Zinc500,
                            )
                            LIcon(Lucide.ArrowUpRight, 14.dp, Hex.Zinc500)
                        }
                        Txt(inb.title, mineText(14.5f, 600, lineHeight = 1.375f), Hex.Jet)
                        Txt(inb.subtitle, mineText(13f, lineHeight = 1.625f), Hex.Zinc600)
                        if (inb.choices.size > 1) {
                            androidx.compose.foundation.layout.FlowRow(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                inb.choices.forEach { c ->
                                    Txt(
                                        c.label, mineText(12.5f, 500), Hex.Cream,
                                        Modifier
                                            .background(Hex.Jet, R12)
                                            .tap { dispatch(MineAction.ResolveAmbiguity(inb.id, c.id)) }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                                Txt(
                                    "Never mind", mineText(12.5f, 500), Hex.Zinc600,
                                    Modifier
                                        .background(Mine.PaperInk1.copy(alpha = 0.06f), R12)
                                        .tap { dispatch(MineAction.ResolveInboxItem(inb.id, false)) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        } else Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(
                                inb.primaryActionLabel, mineText(12.5f, 500), Hex.Cream,
                                Modifier
                                    .background(Hex.Jet, R12)
                                    .tap { dispatch(MineAction.ResolveInboxItem(inb.id, true)) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            Txt(
                                inb.secondaryActionLabel, mineText(12.5f, 500), Hex.Zinc600,
                                Modifier
                                    .background(Mine.PaperInk1.copy(alpha = 0.06f), R12)
                                    .tap { dispatch(MineAction.ResolveInboxItem(inb.id, false)) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (embedded) {
        list()
        return
    }
    LegacyScreen {
        BackHeader("Inbox", onBack = { dispatch(MineAction.GoBack) })
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("To review", serifHeading(34f, 1.25f), Mine.Ink1)
            Txt("Nothing here changes until you choose.", mineText(14f), Hex.Mute)
        }
        ScrollPaper(Modifier.weight(1f), top = 24.dp) { list() }
    }
}

/* ================================ S41 Living notes ================================ */

private sealed interface NotesSection {
    val key: String
    data object Pinned : NotesSection { override val key = "pinned" }
    data object All : NotesSection { override val key = "all" }
}

@Composable
private fun NoteCard(note: LivingNote, pinned: Boolean) {
    val dispatch = mineDispatch()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (pinned) Modifier.mineSurface(R16) else Modifier.mineSurfaceSubtle(R16))
            .tap { dispatch(MineAction.OpenModal(ModalId.M07, noteId = note.id)) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (pinned) 10.dp else 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt(note.title, mineText(15f, 500), Hex.Mist, Modifier.weight(1f))
            Box(Modifier.tap { dispatch(MineAction.TogglePinNote(note.id)) }) {
                LIcon(Lucide.Pin, 14.dp, if (pinned) Hex.Sky else Hex.Mute, filled = pinned)
            }
        }
        Txt(note.bullets.firstOrNull() ?: "", mineText(13.5f, lineHeight = 24f / 13.5f), Hex.Mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (pinned) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val meta = mineText(11.5f)
                Txt(note.updatedAt, meta, Hex.Mute.copy(alpha = 0.7f))
                Txt(if (note.accessMode == NoteAccessMode.read_write) "Read & Write" else "Read only", meta, Hex.Mute.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun S41LivingNotes() {
    val dispatch = mineDispatch()
    val notes = mineSelect { it.livingNotes }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var bullet by remember { mutableStateOf("") }
    val pinned = notes.filter { it.pinned }
    val unpinned = notes.filter { !it.pinned }

    fun create() {
        if (title.isBlank()) return
        dispatch(MineAction.CreateLivingNote(title, bullet.ifEmpty { "Note initialized." }))
        title = ""
        bullet = ""
        creating = false
    }

    val noteEnter = Fx(alpha = 0f, scale = 0.96f)
    val noteSpec = tween<Float>(220, easing = MineMotion.FmEaseInOut)

    LegacyScreen {
        BackHeader("Living Notes", onBack = { dispatch(MineAction.Navigate(ScreenId.S14)) }, titleColor = Hex.Mist, trailing = {
            val rot by animateFloatAsState(if (creating) 45f else 0f, tween(200, easing = MineMotion.TwEase), label = "plus-rot")
            Box(Modifier.offset(x = 8.dp).tap { creating = !creating }.padding(8.dp)) {
                Box(Modifier.graphicsLayer { rotationZ = rot }) { LIcon(Lucide.Plus, 20.dp, if (creating) Hex.Cream else Hex.Mute) }
            }
        })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val formBlur by animateFloatAsState(if (creating) 0f else 4f, tween(240, easing = MineMotion.EaseOutQuint), label = "form-blur")
            AnimatedVisibility(
                visible = creating,
                enter = expandVertically(tween(240, easing = MineMotion.EaseOutQuint)) + fadeIn(tween(240, easing = MineMotion.EaseOutQuint)),
                exit = shrinkVertically(tween(240, easing = MineMotion.EaseOutQuint)) + fadeOut(tween(240, easing = MineMotion.EaseOutQuint)),
            ) {
                Column(
                    Modifier
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                        .graphicsLayer { renderEffect = blurEffect(formBlur * density) }
                        .mineSurface(R16)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MineField(title, { title = it }, mineText(15f, 500), Hex.Mist, Modifier.fillMaxWidth(), placeholder = "Note title...")
                    MineField(bullet, { bullet = it }, mineText(13.5f), Hex.Mute, Modifier.fillMaxWidth(), placeholder = "First point...", onSubmit = ::create)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Txt("Cancel", mineText(12f), Hex.Mute, Modifier.tap { creating = false }.padding(horizontal = 12.dp, vertical = 6.dp))
                        Txt(
                            "Save", mineText(12f, 500), Hex.Night,
                            Modifier
                                .background(Hex.Mist, R12)
                                .tap(onClick = ::create)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            val sections = buildList {
                add(NotesSection.Pinned)
                if (unpinned.isNotEmpty()) add(NotesSection.All)
            }
            AnimatedColumn(items = sections, key = { it.key }, spacing = 24.dp, enter = Fx.Visible, exit = Fx.Hidden, exitSpec = tween(1)) { section ->
                when (section) {
                    NotesSection.Pinned -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt("Pinned (${pinned.size}/3)", mineText(12f), Hex.Mute)
                        AnimatedColumn(
                            items = pinned, key = { it.id }, spacing = 12.dp,
                            enter = noteEnter, exit = noteEnter, enterSpec = noteSpec, exitSpec = noteSpec,
                        ) { NoteCard(it, pinned = true) }
                    }
                    NotesSection.All -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt("All Notes", mineText(12f), Hex.Mute)
                        AnimatedColumn(
                            items = unpinned, key = { it.id }, spacing = 12.dp,
                            enter = noteEnter, exit = noteEnter, enterSpec = noteSpec, exitSpec = noteSpec,
                        ) { NoteCard(it, pinned = false) }
                    }
                }
            }
        }
    }
}
