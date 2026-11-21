package com.mine.companion.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ReminderStatus
import com.mine.companion.store.ScreenId
import com.mine.companion.store.TriggerType
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.icons.LucideGlyph
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.theme.HapticKind
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.hairlineTop
import com.mine.companion.ui.theme.lerp
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.rememberHaptics
import com.mine.companion.ui.theme.w
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────────────────── model ─────────────────────────

enum class CalendarMode { Week, Month }

@Immutable
data class DayMarks(val activity: Int = 0, val reminder: Boolean = false, val history: Boolean = false)

private val NoMarks = DayMarks()

@Immutable
data class CalendarColors(
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val accent: Color,
    /** Edge light on the selection. */
    val rim: Color,
    /** Selection fill: a tint, never solid black. */
    val pill: Color,
    val hairline: Color,
    val chip: Color,
    val due: Color,
    val history: Color,
    val warm: Color,
) {
    companion object {
        /** Dark surfaces: the drawer and sheets. */
        fun surface(): CalendarColors {
            val dark = Mine.IsDark
            return CalendarColors(
                ink = Mine.Ink1, ink2 = Mine.Ink2, ink3 = Mine.Ink3, ink4 = Mine.Ink4,
                accent = Mine.Accent, rim = Mine.Accent,
                pill = if (dark) Color.White.copy(alpha = 0.085f) else Color.Black.copy(alpha = 0.06f),
                hairline = Mine.Hairline, chip = Mine.Surface3,
                due = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                history = if (dark) Color(0xFFB9AEFF) else Color(0xFF6D5BD0),
                warm = if (dark) Color(0xFFF2B36B) else Color(0xFFD9822B),
            )
        }

        /** Paper surfaces: cream in dark mode, charcoal in light mode. */
        fun paper(): CalendarColors {
            val cream = Mine.IsDark
            return CalendarColors(
                ink = Mine.PaperInk1, ink2 = Mine.PaperInk2, ink3 = Mine.PaperInk3, ink4 = Mine.PaperInk1.copy(alpha = 0.2f),
                accent = Mine.PaperAccent, rim = Mine.PaperAccent,
                pill = Mine.PaperInk1.copy(alpha = 0.07f),
                hairline = Mine.PaperHairline, chip = Mine.PaperInk1.copy(alpha = 0.07f),
                due = if (cream) Color(0xFF2563EB) else Color(0xFF7DB4FF),
                history = if (cream) Color(0xFF6D5BD0) else Color(0xFFC1B5FF),
                warm = if (cream) Color(0xFFD9822B) else Color(0xFFF2B36B),
            )
        }
    }
}

private val PastKeyFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val PastLabelFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val ToastFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

@Composable
fun rememberCalendarMarks(): Map<LocalDate, DayMarks> {
    val nodes = mineSelect { it.engine.activeNodes }
    val reminders = mineSelect { it.engine.activeReminders }
    val counts = mineSelect { it.engine.chatDayCounts }
    return remember(nodes, reminders, counts) {
        val zone = java.time.ZoneId.systemDefault()
        val today = LocalDate.now()
        val out = HashMap<LocalDate, DayMarks>()
        fun bump(d: LocalDate, activity: Int = 0, reminder: Boolean = false, history: Boolean = false) {
            val m = out[d] ?: NoMarks
            out[d] = DayMarks(m.activity + activity, m.reminder || reminder, m.history || history)
        }
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        nodes.forEach { n ->
            when {
                n.contextTrigger?.contains("LEAVE_HOME", true) == true -> bump(today, activity = 1)
                n.dueAt != null -> bump(day(n.dueAt!!), activity = 1)
            }
        }
        reminders.forEach { bump(day(it.fireAtMillis), reminder = true) }
        counts.forEach { (key, n) ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            if (date.isBefore(today)) bump(date, activity = ((n + 1) / 4).coerceIn(1, 3), history = n > 0)
        }
        out
    }
}

// ───────────────────────── tuning ─────────────────────────

private const val PAGE_COUNT = 1201
private const val CENTER = 600
private val MONTH_ROW_H = 48.dp
private val WEEK_ROW_H = 72.dp
private val MONTH_PILL_H = 44.dp
private val MONTH_PILL_W = 42.dp
private val WEEK_PILL_W = 46.dp
private val MONTH_PILL_R = 14.dp
private val WEEK_PILL_R = 16.dp
private val PageSpring = spring<Float>(dampingRatio = 0.88f, stiffness = 380f)

private fun leadingBlanks(month: YearMonth, firstDow: DayOfWeek) =
    (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7

private fun rowsIn(month: YearMonth, firstDow: DayOfWeek) =
    (leadingBlanks(month, firstDow) + month.lengthOfMonth() + 6) / 7

private fun weekStartOf(d: LocalDate, firstDow: DayOfWeek): LocalDate =
    d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong())

private fun monthCells(month: YearMonth, firstDow: DayOfWeek): List<LocalDate?> {
    val lead = leadingBlanks(month, firstDow)
    val length = month.lengthOfMonth()
    val rows = (lead + length + 6) / 7
    return List(rows * 7) { i ->
        val day = i - lead + 1
        if (day in 1..length) month.atDay(day) else null
    }
}

// ───────────────────────── day actions ─────────────────────────

private enum class DayAction(val label: String) { OpenToday("Open today"), OpenDay("Open day"), Plan("Add") }

private fun dayAction(date: LocalDate, mark: DayMarks, today: LocalDate): DayAction? = when {
    date == today -> DayAction.OpenToday
    date.isBefore(today) -> if (mark.history) DayAction.OpenDay else null
    else -> DayAction.Plan
}

fun performDayAction(date: LocalDate, mark: DayMarks, today: LocalDate, dispatch: (MineAction) -> Unit) {
    when (dayAction(date, mark, today)) {
        DayAction.OpenToday -> dispatch(MineAction.Navigate(ScreenId.S15))
        DayAction.OpenDay -> {
            dispatch(MineAction.SelectPastDate(date.toString()))
            dispatch(MineAction.Navigate(ScreenId.S16))
        }
        DayAction.Plan -> dispatch(MineAction.OpenModal(ModalId.M09))
        null -> dispatch(MineAction.ShowToast("Nothing was logged on ${date.format(ToastFormat)}"))
    }
}

private fun dayTitle(d: LocalDate, today: LocalDate, format: DateTimeFormatter): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(format)
}

private fun dayDetail(d: LocalDate, m: DayMarks, today: LocalDate): String {
    val parts = buildList {
        when {
            m.history -> add("Conversation saved")
            m.activity > 0 && !d.isBefore(today) -> add(if (m.activity == 1) "1 thing due" else "${m.activity} things due")
        }
        if (m.reminder) add("reminder set")
    }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
        d.isBefore(today) -> "Nothing was logged"
        d == today -> "Nothing on today"
        else -> "Nothing planned yet"
    }
}

// ───────────────────────── calendar ─────────────────────────

@Composable
fun MineCalendar(
    modifier: Modifier = Modifier,
    mode: CalendarMode = CalendarMode.Month,
    colors: CalendarColors = CalendarColors.surface(),
    marks: Map<LocalDate, DayMarks> = rememberCalendarMarks(),
    showHeader: Boolean = true,
    showFooter: Boolean = true,
    initialDate: LocalDate? = null,
    onSelect: (LocalDate) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val locale = remember { Locale.getDefault() }
    val today = remember { LocalDate.now() }
    val firstDow = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val baseMonth = remember(today) { YearMonth.from(today) }
    val baseWeek = remember(today, firstDow) { weekStartOf(today, firstDow) }
    val weekdays = remember(firstDow) { List(7) { firstDow.plus(it.toLong()) } }
    val a11yFormat = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val titleFormat = remember(locale) { DateTimeFormatter.ofPattern("EEEE d", locale) }
    var selected by remember { mutableStateOf(initialDate ?: today) }
    val initialPage = remember {
        val d = initialDate ?: today
        if (mode == CalendarMode.Month) {
            CENTER + ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(d)).toInt()
        } else {
            CENTER + ChronoUnit.WEEKS.between(baseWeek, weekStartOf(d, firstDow)).toInt()
        }
    }
    val pager = rememberPagerState(initialPage = initialPage) { PAGE_COUNT }
    val monthAt: (Int) -> YearMonth = remember(baseMonth) { { page -> baseMonth.plusMonths((page - CENTER).toLong()) } }
    val weekAt: (Int) -> LocalDate = remember(baseWeek) { { page -> baseWeek.plusWeeks((page - CENTER).toLong()) } }
    val latestOnSelect by rememberUpdatedState(onSelect)

    val select: (LocalDate) -> Unit = remember(haptics) {
        { date ->
            if (date != selected) {
                haptics.tick()
                selected = date
            }
            latestOnSelect(date)
        }
    }
    val jumpToday: () -> Unit = {
        scope.launch { pager.animateScrollToPage(CENTER, animationSpec = PageSpring) }
        select(today)
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { haptics.soft() }
    }

    Column(modifier.fillMaxWidth()) {
        if (showHeader) {
            val shownMonth = if (mode == CalendarMode.Month) {
                monthAt(pager.currentPage)
            } else {
                YearMonth.from(weekAt(pager.currentPage).plusDays(3))
            }
            CalendarHeader(
                month = shownMonth,
                today = today,
                locale = locale,
                colors = colors,
                showToday = pager.currentPage != CENTER || selected != today,
                onToday = jumpToday,
                onPrev = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1, animationSpec = PageSpring) } },
                onNext = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = PageSpring) } },
            )
        }

        if (mode == CalendarMode.Month) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                weekdays.forEach { dow ->
                    val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                    Txt(
                        dow.getDisplayName(JTextStyle.NARROW, locale),
                        MineType.Caption.w(600),
                        if (weekend) colors.ink4 else colors.ink3,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        val rowPx = with(density) { MONTH_ROW_H.toPx() }
        val pagerModifier = if (mode == CalendarMode.Month) {
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val cur = pager.currentPage
                    val f = pager.currentPageOffsetFraction
                    val other = (if (f >= 0f) cur + 1 else cur - 1).coerceIn(0, PAGE_COUNT - 1)
                    val rows = lerp(rowsIn(monthAt(cur), firstDow).toFloat(), rowsIn(monthAt(other), firstDow).toFloat(), abs(f))
                    val h = (rows * rowPx).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
        } else {
            Modifier.fillMaxWidth().height(WEEK_ROW_H).clipToBounds()
        }

        HorizontalPager(
            state = pager,
            modifier = pagerModifier,
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = PageSpring),
            key = { it },
        ) { page ->
            val cells: List<LocalDate?> = if (mode == CalendarMode.Month) {
                monthCells(monthAt(page), firstDow)
            } else {
                val start = weekAt(page)
                List(7) { start.plusDays(it.toLong()) }
            }
            PeriodGrid(
                cells = cells,
                mode = mode,
                today = today,
                selected = selected,
                marks = marks,
                colors = colors,
                locale = locale,
                a11yFormat = a11yFormat,
                onSelect = select,
                modifier = Modifier
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .graphicsLayer {
                        val off = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceAtMost(1f)
                        alpha = 1f - 0.55f * off
                        val s = 1f - 0.03f * off
                        scaleX = s
                        scaleY = s
                    },
            )
        }

        if (showFooter) DayFooter(selected, marks, today, colors, titleFormat)
    }
}

// ───────────────────────── header ─────────────────────────

@Composable
private fun CalendarHeader(
    month: YearMonth,
    today: LocalDate,
    locale: Locale,
    colors: CalendarColors,
    showToday: Boolean,
    onToday: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnimatedContent(
            targetState = month,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInVertically(spring(dampingRatio = 0.86f, stiffness = 500f)) { h -> h / 2 * dir } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(spring(dampingRatio = 0.86f, stiffness = 500f)) { h -> -h / 2 * dir } + fadeOut(tween(120))) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.CenterStart,
            label = "calendar-title",
        ) { ym ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(ym.month.getDisplayName(JTextStyle.FULL, locale), MineType.Title, colors.ink)
                if (ym.year != today.year) Txt("${ym.year}", MineType.Callout.num(), colors.ink3, Modifier.padding(bottom = 2.dp))
            }
        }
        Presence(visible = showToday, initial = Fx(alpha = 0f, scale = 0.85f), enter = MineMotion.snappy()) {
            Pressable(
                onClick = onToday,
                feel = MineMotion.Press.Chip,
                label = "Back to today",
                modifier = Modifier
                    .padding(end = 4.dp)
                    .height(30.dp)
                    .background(colors.chip, CircleShape)
                    .padding(horizontal = 12.dp),
            ) { Txt("Today", MineType.Footnote.w(600), colors.accent) }
        }
        StepButton(Lucide.ChevronLeft, "Previous", colors, onPrev)
        StepButton(Lucide.ChevronRight, "Next", colors, onNext)
    }
}

@Composable
private fun StepButton(glyph: LucideGlyph, label: String, colors: CalendarColors, onClick: () -> Unit) {
    Pressable(onClick = onClick, feel = MineMotion.Press.Icon, label = label, modifier = Modifier.size(36.dp)) {
        LIcon(glyph, 18.dp, colors.ink3, strokeWidth = 2f)
    }
}

// ───────────────────────── grid ─────────────────────────

@Composable
private fun PeriodGrid(
    cells: List<LocalDate?>,
    mode: CalendarMode,
    today: LocalDate,
    selected: LocalDate,
    marks: Map<LocalDate, DayMarks>,
    colors: CalendarColors,
    locale: Locale,
    a11yFormat: DateTimeFormatter,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val rows = cells.size / 7
    val week = mode == CalendarMode.Week
    val rowH = if (week) WEEK_ROW_H else MONTH_ROW_H
    val selIndex = cells.indexOf(selected)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val cellPx = constraints.maxWidth / 7f
        val rowPx = with(density) { rowH.toPx() }
        val gap = with(density) { 4.dp.toPx() }
        val pillW = with(density) { min(cellPx - gap, (if (week) WEEK_PILL_W else MONTH_PILL_W).toPx()) }
        val pillH = with(density) { if (week) rowPx - 6.dp.toPx() else MONTH_PILL_H.toPx() }
        val pillR = with(density) { (if (week) WEEK_PILL_R else MONTH_PILL_R).toPx() }
        // The pill and each cell's content block are both centred on the cell, so marks never clip.
        val center = if (selIndex >= 0) Offset((selIndex % 7) * cellPx + cellPx / 2f, (selIndex / 7) * rowPx + rowPx / 2f) else null

        SelectionPill(center = center, wPx = pillW, hPx = pillH, cornerPx = pillR, colors = colors)

        Column {
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth().height(rowH)) {
                    repeat(7) { c ->
                        val date = cells[r * 7 + c]
                        if (date == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val isToday = date == today
                            DayCell(
                                date = date,
                                week = week,
                                isToday = isToday,
                                isSelected = date == selected,
                                isPast = date.isBefore(today),
                                mark = marks[date] ?: NoMarks,
                                colors = colors,
                                locale = locale,
                                label = date.format(a11yFormat) + if (isToday) ", today" else "",
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tinted selection that glides between days. The only light is a soft glow hugging the
 * inside of its border and a thin rim whose bright end turns as it moves. Nothing spills sideways.
 */
@Composable
private fun BoxScope.SelectionPill(center: Offset?, wPx: Float, hPx: Float, cornerPx: Float, colors: CalendarColors) {
    val pos = remember { Animatable(center ?: Offset.Zero, Offset.VectorConverter) }
    val shown = remember { Animatable(if (center != null) 1f else 0f) }
    val density = LocalDensity.current
    val maxStretch = with(density) { 6.dp.toPx() }
    val rimPx = with(density) { 1.dp.toPx() }
    val glowOuter = with(density) { 5.dp.toPx() }
    val glowInner = with(density) { 2.5.dp.toPx() }

    LaunchedEffect(center) {
        if (center == null) {
            shown.animateTo(0f, tween(140))
            return@LaunchedEffect
        }
        if (shown.value < 0.05f) {
            pos.snapTo(center)
            shown.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 700f))
        } else {
            pos.animateTo(center, spring(dampingRatio = 0.8f, stiffness = 520f))
        }
    }

    Spacer(
        Modifier
            .matchParentSize()
            .drawBehind {
                val a = shown.value
                if (a <= 0.001f) return@drawBehind
                val alpha = a.coerceIn(0f, 1f)
                val v = pos.velocity
                val sx = (abs(v.x) * 0.004f).coerceAtMost(maxStretch)
                val sy = (abs(v.y) * 0.004f).coerceAtMost(maxStretch)
                val k = lerp(0.75f, 1f, a)
                val w = (wPx + sx - sy * 0.2f) * k
                val h = (hPx + sy - sx * 0.2f) * k
                val c = pos.value
                val tl = Offset(c.x - w / 2f, c.y - h / 2f)
                val r = min(cornerPx * k, min(w, h) / 2f)

                drawRoundRect(colors.pill, topLeft = tl, size = Size(w, h), cornerRadius = CornerRadius(r), alpha = alpha)

                // Soft glow that stays inside the border.
                val i1 = glowOuter / 2f
                drawRoundRect(
                    colors.rim,
                    topLeft = tl + Offset(i1, i1),
                    size = Size(w - glowOuter, h - glowOuter),
                    cornerRadius = CornerRadius(max(r - i1, 0f)),
                    alpha = alpha * 0.06f,
                    style = Stroke(glowOuter),
                )
                val i2 = glowInner / 2f
                drawRoundRect(
                    colors.rim,
                    topLeft = tl + Offset(i2, i2),
                    size = Size(w - glowInner, h - glowInner),
                    cornerRadius = CornerRadius(max(r - i2, 0f)),
                    alpha = alpha * 0.10f,
                    style = Stroke(glowInner),
                )

                // Thin rim; its bright end turns as the pill moves.
                val ang = c.x * 0.012f - 0.8f
                val dx = cos(ang) * w / 2f
                val dy = sin(ang) * h / 2f
                val i3 = rimPx / 2f
                drawRoundRect(
                    Brush.linearGradient(
                        0f to colors.rim.copy(alpha = 0.85f),
                        0.45f to colors.rim.copy(alpha = 0.15f),
                        1f to colors.rim.copy(alpha = 0.45f),
                        start = Offset(c.x - dx, c.y - dy),
                        end = Offset(c.x + dx, c.y + dy),
                    ),
                    topLeft = tl + Offset(i3, i3),
                    size = Size(w - rimPx, h - rimPx),
                    cornerRadius = CornerRadius(max(r - i3, 0f)),
                    alpha = alpha,
                    style = Stroke(rimPx),
                )
            },
    )
}

@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    week: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isPast: Boolean,
    mark: DayMarks,
    colors: CalendarColors,
    locale: Locale,
    label: String,
    onSelect: (LocalDate) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "day-press",
    )
    val target = when {
        isToday -> colors.accent
        isSelected -> colors.ink
        isPast -> colors.ink2
        else -> colors.ink
    }
    val fg by animateColorAsState(target, tween(160), label = "day-fg")
    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onSelect(date) }
            .semantics {
                contentDescription = label
                selected = isSelected
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (week) {
            val wdTarget = when {
                isSelected -> colors.ink2
                weekend -> colors.ink4
                else -> colors.ink3
            }
            val wd by animateColorAsState(wdTarget, tween(160), label = "day-wd")
            Txt(
                date.dayOfWeek.getDisplayName(JTextStyle.SHORT, locale).uppercase(locale),
                MineType.Micro,
                Color.Unspecified,
                Modifier.padding(bottom = 4.dp),
                colorProducer = { wd },
            )
            Txt("${date.dayOfMonth}", mineText(19f, lineHeight = 1.1f, display = true).num(), Color.Unspecified, colorProducer = { fg })
        } else {
            Txt(
                "${date.dayOfMonth}",
                MineType.Callout.num().w(if (isToday || isSelected) 600 else 400),
                Color.Unspecified,
                colorProducer = { fg },
            )
        }
        MarkBar(mark, colors)
    }
}

/** Always reserves its 3dp, so numbers sit at the same height in every cell. */
@Composable
private fun MarkBar(mark: DayMarks, colors: CalendarColors) {
    val barW = when {
        mark.activity >= 3 -> 14.dp
        mark.activity == 2 -> 10.dp
        mark.activity == 1 -> 6.dp
        else -> 0.dp
    }
    Row(
        Modifier.padding(top = 4.dp).height(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (barW > 0.dp) {
            Box(Modifier.width(barW).height(3.dp).background(if (mark.history) colors.history else colors.due, CircleShape))
        }
        if (mark.reminder) Box(Modifier.size(3.dp).background(colors.warm, CircleShape))
    }
}

// ───────────────────────── footer ─────────────────────────

@Composable
private fun DayFooter(
    date: LocalDate,
    marks: Map<LocalDate, DayMarks>,
    today: LocalDate,
    colors: CalendarColors,
    titleFormat: DateTimeFormatter,
) {
    val dispatch = mineDispatch()
    val action = dayAction(date, marks[date] ?: NoMarks, today)
    val last = remember { arrayOfNulls<DayAction>(1) }
    if (action != null) last[0] = action

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .hairlineTop(colors.hairline)
            .padding(start = 8.dp, end = 4.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FxSwap(
            target = date,
            modifier = Modifier.weight(1f),
            initial = Fx(alpha = 0f, y = 6.dp),
            exit = Fx(alpha = 0f, y = (-4).dp),
            enter = MineMotion.gentle(),
            exitSpec = tween(120),
            wait = false,
        ) { d ->
            Column {
                Txt(dayTitle(d, today, titleFormat), MineType.Headline, colors.ink)
                Txt(dayDetail(d, marks[d] ?: NoMarks, today), MineType.Footnote, colors.ink3, Modifier.padding(top = 2.dp))
            }
        }

        Presence(visible = action != null, initial = Fx(alpha = 0f, scale = 0.9f), enter = MineMotion.snappy()) {
            val shown = action ?: last[0]
            if (shown != null) {
                ActionPill(shown.label, colors) { performDayAction(date, marks[date] ?: NoMarks, today, dispatch) }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, colors: CalendarColors, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        feel = MineMotion.Press.Chip,
        haptics = HapticKind.Tick,
        modifier = Modifier
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 600f))
            .height(34.dp)
            .background(colors.pill, CircleShape)
            .border(1.dp, colors.rim.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 16.dp),
    ) {
        FxSwap(
            target = label,
            initial = Fx(alpha = 0f, y = 4.dp),
            exit = Fx(alpha = 0f, y = (-4).dp),
            enter = tween(160),
        ) { text -> Txt(text, MineType.Footnote.w(600), colors.accent, maxLines = 1) }
    }
}

// ───────────────────────── drawer card ─────────────────────────

/** Week strip that opens into the full month. Lives in the Places drawer. */
@Composable
fun CalendarCard(modifier: Modifier = Modifier, colors: CalendarColors = CalendarColors.surface()) {
    var month by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    val marks = rememberCalendarMarks()

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("Calendar", MineType.Footnote.w(500), colors.ink3, Modifier.weight(1f))
            Pressable(
                onClick = { month = !month },
                feel = MineMotion.Press.Chip,
                haptics = HapticKind.Tick,
                label = if (month) "Show week" else "Show month",
                modifier = Modifier
                    .height(28.dp)
                    .background(colors.chip, CircleShape)
                    .padding(start = 12.dp, end = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FxSwap(
                        target = month,
                        initial = Fx(alpha = 0f, y = 3.dp),
                        exit = Fx(alpha = 0f, y = (-3).dp),
                        enter = tween(150),
                    ) { m -> Txt(if (m) "Week" else "Month", MineType.Caption.w(600), colors.ink2) }
                    val rot by animateFloatAsState(
                        if (month) 180f else 0f,
                        spring(dampingRatio = 0.8f, stiffness = 500f),
                        label = "calendar-chevron",
                    )
                    Box(Modifier.graphicsLayer { rotationZ = rot }) { LIcon(Lucide.ChevronDown, 14.dp, colors.ink3) }
                }
            }
        }

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) using
                    SizeTransform(clip = true) { _, _ ->
                        spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)
                    }
            },
            label = "calendar-mode",
        ) { m ->
            MineCalendar(
                mode = if (m) CalendarMode.Month else CalendarMode.Week,
                colors = colors,
                marks = marks,
                initialDate = day,
                onSelect = { day = it },
            )
        }
    }
}
