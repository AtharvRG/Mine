package com.mine.companion.ui.modals

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mine.companion.store.AlarmTierUi
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.NoteAccessMode
import com.mine.companion.store.PersonaType
import com.mine.companion.store.ProcrastinationMode
import com.mine.companion.store.ScreenId
import com.mine.companion.store.SettingKey
import com.mine.companion.store.TriggerType
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.components.CalendarCard
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.icons.LucideGlyph
import com.mine.companion.ui.primitives.AnimatedColumn
import com.mine.companion.ui.primitives.Detent
import com.mine.companion.ui.primitives.Fx
import com.mine.companion.ui.primitives.FxSwap
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.NativeCheckbox
import com.mine.companion.ui.primitives.Presence
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.SheetHeader
import com.mine.companion.ui.primitives.SheetHost
import com.mine.companion.ui.primitives.SheetSpec
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.HapticKind
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.hairlineBottom
import com.mine.companion.ui.theme.hairlineTop
import com.mine.companion.ui.theme.lh
import com.mine.companion.ui.theme.matCard
import com.mine.companion.ui.theme.matWell
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.specular
import com.mine.companion.ui.theme.w
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val R12 = RoundedCornerShape(12.dp)
private val R16 = RoundedCornerShape(16.dp)
private val R8 = RoundedCornerShape(8.dp)

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

@Composable
private fun CloseX(onClick: () -> Unit) {
    Box(Modifier.tap(onClick = onClick)) { LIcon(Lucide.X, 16.dp, Hex.Mute) }
}

/* ================================ M01 / S17 ================================ */

@Composable
fun DateDialContent(isStandaloneScreen: Boolean = false) {
    val dispatch = mineDispatch()
    val counts = mineSelect { it.engine.chatDayCounts }
    val today = java.time.LocalDate.now()
    // Today, then the most recent days that actually have a conversation (newest first).
    val days = remember(counts) {
        val past = counts.keys.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            .filter { it.isBefore(today) }
            .sortedDescending()
            .take(12)
        listOf(today) + past
    }
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("Jump to a day", mineText(16f, 500), Hex.Mist)
            if (!isStandaloneScreen) CloseX { dispatch(MineAction.CloseModal) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { d ->
                val isToday = d == today
                val n = counts[d.toString()] ?: 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tap {
                            dispatch(MineAction.CloseModal)
                            if (isToday) dispatch(MineAction.Navigate(ScreenId.S15))
                            else {
                                dispatch(MineAction.SelectPastDate(d.toString()))
                                dispatch(MineAction.Navigate(ScreenId.S16))
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(
                        when {
                            isToday -> "Today · ${d.format(fmt)}"
                            d == today.minusDays(1) -> "Yesterday · ${d.format(fmt)}"
                            else -> d.format(fmt)
                        },
                        mineText(14f), Hex.Mist,
                    )
                    Txt(if (isToday) "Now" else "$n", mineText(12f).num(), if (isToday) Hex.Sky else Hex.Mute)
                }
            }
            if (days.size == 1) Txt("Older conversations will appear here.", mineText(12.5f), Hex.Mute, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/* ================================ M02 / S18 ================================ */

private val Blur3 = Fx(alpha = 0f, y = 3.dp, blur = 3.dp)

@Composable
private fun SegButton(label: String, active: Boolean, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, vertical: Dp, horizontal: Dp, shape: RoundedCornerShape, inactiveBg: Color = Color.Transparent, inactiveFg: Color = Hex.Mute, activeStyle: androidx.compose.ui.text.TextStyle = style, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (active) Hex.Cream else inactiveBg, shape)
            .tap(onClick = onClick)
            .padding(horizontal = horizontal, vertical = vertical),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, if (active) activeStyle else style, if (active) Hex.Jet else inactiveFg)
    }
}

@Composable
fun TogglePanelContent(isStandaloneScreen: Boolean = false) {
    val dispatch = mineDispatch()
    val ambientPaused = mineSelect { it.ambientListeningPaused }
    val nudgesPaused = mineSelect { it.nudgesPausedUntil }
    val callNudge = mineSelect { it.callNudgeEnabled }
    val guard = mineSelect { it.contextSensing5GGuard }
    val breakLeft = mineSelect { it.breakMinutesLeft }
    val procMode = mineSelect { it.procrastinationMode }
    val persona = mineSelect { it.persona }
    var personaOpen by remember { mutableStateOf(false) }

    val card = Modifier
        .fillMaxWidth()
        .background(Color.White.copy(alpha = 0.04f), R16)
        .border(1.dp, Color.White.copy(alpha = 0.07f), R16)
        .padding(16.dp)

    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("Preferences", mineText(17f, 600, tracking = -0.025f), Hex.Bone)
            if (!isStandaloneScreen) CloseX { dispatch(MineAction.CloseModal) }
        }

        // 1. Ambient mode
        Row(card, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val dot by animateColorAsState(if (!ambientPaused) Hex.Moss else Hex.Mute, tween(300, easing = MineMotion.TwEase), label = "amb-dot")
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LIcon(Lucide.Radio, 14.dp, Hex.Sky)
                        Txt("Ambient Mode", mineText(14f, 500), Hex.Bone)
                    }
                    FxSwap(
                        target = ambientPaused,
                        initial = Blur3,
                        exit = Blur3.copy(y = (-3).dp),
                        enter = tween(180, easing = MineMotion.FmEaseInOut),
                    ) { paused ->
                        Txt(
                            if (!paused) "Active · 15m local rolling scratchpad" else "Off · Microphone scratchpad paused",
                            mineText(11.5f), Hex.Mute,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .background(Hex.Track, R12)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), R12)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SegButton("On", !ambientPaused, mineText(12f, 500), Modifier, 4.dp, 12.dp, R8) {
                    if (ambientPaused) dispatch(MineAction.ToggleSetting(SettingKey.ambientListeningPaused))
                }
                SegButton("Off", ambientPaused, mineText(12f, 500), Modifier, 4.dp, 12.dp, R8) {
                    if (!ambientPaused) dispatch(MineAction.ToggleSetting(SettingKey.ambientListeningPaused))
                }
            }
        }

        // 2. Proactive nudges
        Column(card, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIcon(Lucide.Bell, 14.dp, Hex.Sky)
                    Txt("Proactive Nudges", mineText(14f, 500), Hex.Bone)
                }
                FxSwap(
                    target = nudgesPaused ?: "active",
                    initial = Fx(alpha = 0f, y = 3.dp),
                    exit = Fx(alpha = 0f, y = (-3).dp),
                    enter = tween(160, easing = MineMotion.FmEaseInOut),
                ) { key ->
                    Txt(if (key != "active") "Paused $key" else "Active", mineText(11.5f), Hex.Mute)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var choice by remember { mutableStateOf<String?>(null) }
                listOf("Active" to null, "1h" to "1h", "3h" to "3h", "Tomorrow" to "Until tomorrow").forEach { (label, v) ->
                    val selected = if (v == null) nudgesPaused == null else nudgesPaused != null && (choice == v || choice == null && v == "1h")
                    SegButton(
                        label, selected, mineText(12f, 500), Modifier.weight(1f), 6.dp, 0.dp, R12,
                        inactiveBg = Color.White.copy(alpha = 0.05f),
                    ) {
                        choice = v
                        dispatch(MineAction.PauseNudges(v))
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .hairlineTop(Color.White.copy(alpha = 0.06f))
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CheckRow("Check in after calls", callNudge) { dispatch(MineAction.ToggleSetting(SettingKey.callNudgeEnabled)) }
                CheckRow("Leave-home reminders (place sensing)", guard) { dispatch(MineAction.ToggleSetting(SettingKey.contextSensing5GGuard)) }
            }
        }

        // 3. Take a break
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Txt("Take a break (arms self-wakeup)", mineText(12.5f), Hex.Mute)
                Presence(visible = breakLeft != null, initial = Fx(alpha = 0f, scale = 0.9f), enter = MineMotion.dur(0.3f)) {
                    Txt(
                        "End (${breakLeft ?: 0}m)", mineText(12.5f), Hex.Ember,
                        Modifier.tap { dispatch(MineAction.EndBreak) },
                        decoration = TextDecoration.Underline,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    val active = breakLeft == m
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (active) Hex.Cream else Color.White.copy(alpha = 0.05f), R12)
                            .tap { dispatch(MineAction.StartBreak(m)) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(if (m == 60) "1 hour" else "${m}m", mineText(13f, if (active) 500 else 400), if (active) Hex.Jet else Hex.Mist)
                    }
                }
            }
        }

        // 4. Focus whistle + persona
        Row(Modifier.fillMaxWidth().zIndex(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Focus whistle", mineText(12f), Hex.Mute)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f), R12)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProcrastinationMode.entries.forEach { mode ->
                        SegButton(cap(mode.name), procMode == mode, mineText(11.5f), Modifier.weight(1f), 6.dp, 0.dp, R8, activeStyle = mineText(11.5f, 500)) {
                            dispatch(MineAction.SetProcrastinationMode(mode))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Persona", mineText(12f), Hex.Mute)
                    val rot by animateFloatAsState(if (personaOpen) 180f else 0f, tween(200, easing = MineMotion.TwEase), label = "chev")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), R12)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), R12)
                            .tap { personaOpen = !personaOpen }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FxSwap(
                            target = persona,
                            initial = Fx(alpha = 0f, y = 3.dp),
                            exit = Fx(alpha = 0f, y = (-3).dp),
                            enter = tween(150, easing = MineMotion.FmEaseInOut),
                        ) { p -> Txt(cap(p.name), mineText(12.5f, 500), Hex.Bone) }
                        Box(Modifier.graphicsLayer { rotationZ = rot }) {
                            LIcon(Lucide.ChevronDown, 14.dp, if (personaOpen) Hex.Bone else Hex.Mute)
                        }
                    }
                }
                // Dropdown floats ABOVE the persona column (absolute bottom-full mb-1.5)
                Presence(
                    visible = personaOpen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .layout { m, c ->
                            val p = m.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                            layout(p.width, 0) { p.place(0, -p.height - 6.dp.roundToPx()) }
                        }
                        .zIndex(40f),
                    initial = Fx(alpha = 0f, y = 8.dp, scale = 0.96f, blur = 4.dp),
                    exit = Fx(alpha = 0f, y = 6.dp, scale = 0.96f, blur = 4.dp),
                    enter = tween(180, easing = MineMotion.EaseOutQuint),
                    origin = TransformOrigin(0.5f, 1f),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .dropShadow(R16, Shadow(radius = 32.dp, color = Color.Black.copy(alpha = 0.6f), offset = DpOffset(0.dp, 12.dp)))
                            .background(Hex.Dropdown, R16)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), R16)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        listOf(PersonaType.companion, PersonaType.cofounder, PersonaType.coach, PersonaType.chill, PersonaType.minimal, PersonaType.ultron).forEach { p ->
                            val selected = persona == p
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) Hex.Cream else Color.Transparent, R12)
                                    .tap {
                                        dispatch(MineAction.SetPersona(p))
                                        personaOpen = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(cap(p.name), mineText(12.5f, if (selected) 600 else 400), if (selected) Hex.Jet else Hex.Mute)
                                if (selected) LIcon(Lucide.Check, 14.dp, Hex.Jet)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, mineText(13f), Hex.Bone.copy(alpha = 0.85f))
        NativeCheckbox(checked, { onToggle() }, accent = Hex.Sky)
    }
}

/* ================================ M03 / S19 ================================ */

private data class PlaceLink(val id: ScreenId, val label: String, val icon: LucideGlyph, val count: Int? = null, val attention: Boolean = false)

/** Places: a thumb-reachable grid instead of a full-height side list. */
@Composable
fun SideNavDrawerContent(isStandaloneScreen: Boolean = false) {
    val dispatch = mineDispatch()
    val userName = mineSelect { it.userName }
    val inbox = mineSelect { it.inboxItems.size }
    val openTasks = mineSelect { s -> s.items.count { it.status != ItemStatus.done && it.status != ItemStatus.trashed } }
    val notes = mineSelect { it.livingNotes.size }
    val upcoming = mineSelect { s -> s.reminders.count { it.status == com.mine.companion.store.ReminderStatus.upcoming } }
    val links = listOf(
        PlaceLink(ScreenId.S24, "Inbox", Lucide.Inbox, inbox, attention = true),
        PlaceLink(ScreenId.S22, "Memory", Lucide.Brain, openTasks),
        PlaceLink(ScreenId.S41, "Living Notes", Lucide.NotebookText, notes),
        PlaceLink(ScreenId.S21, "Reminders", Lucide.AlarmClock, upcoming),
        PlaceLink(ScreenId.S20, "About You", Lucide.User),
        PlaceLink(ScreenId.S36, "Listening", Lucide.Radio),
        PlaceLink(ScreenId.S25, "Search", Lucide.Search),
        PlaceLink(ScreenId.S26, "Settings", Lucide.Settings2),
    )
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        SheetHeader(title = userName, meta = "Everything Mine keeps for you")
        links.chunked(2).forEachIndexed { i, row ->
            Row(
                Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    Pressable(
                        onClick = { dispatch(MineAction.Navigate(item.id)) },
                        feel = MineMotion.Press.Card,
                        contentAlignment = Alignment.TopStart,
                        modifier = Modifier
                            .weight(1f)
                            .height(84.dp)
                            .matCard(Shapes.R20)
                            .specular(Shapes.R20)
                            .padding(14.dp),
                    ) {
                        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                LIcon(item.icon, 18.dp, Mine.Ink2, strokeWidth = 1.75f)
                                if (item.count != null && item.count > 0) {
                                    Txt("${item.count}", MineType.Caption.w(600).num(), if (item.attention) Mine.Attention else Mine.Ink3)
                                }
                            }
                            Txt(item.label, MineType.Callout.w(500), Mine.Ink1)
                        }
                    }
                }
            }
        }

        CalendarCard(
            Modifier
                .padding(top = 12.dp)
                .matCard(Shapes.R20)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

/* ==================================== M09 ==================================== */

/** Quick capture: time chips, and the alarm-vs-gentle choice the engine's AlarmTier supports. */
@Composable
private fun QuickCaptureContent() {
    val dispatch = mineDispatch()
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf(AlarmTierUi.ALARM) }
    var trigger by remember { mutableStateOf(TriggerType.time) }
    val presets = remember {
        val h = java.time.LocalTime.now().hour
        buildList {
            add("In 30 min")
            add("In 1 hour")
            if (h < 18) add("Today 19:00")
            if (h < 21) add("Today 21:00")
            add("Tomorrow 9:00")
        }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Custom date and time from the system pickers. Sent as "yyyy-MM-dd HH:mm" so the resolver
    // reads it exactly (24-hour, explicit year): nothing to guess.
    var customValue by remember { mutableStateOf<String?>(null) }
    var customLabel by remember { mutableStateOf<String?>(null) }

    fun pickCustom() {
        val start = java.time.LocalDateTime.now().plusHours(1).withMinute(0)
        val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            android.app.TimePickerDialog(ctx, { _, hh, mm ->
                val dt = java.time.LocalDateTime.of(y, m + 1, d, hh, mm)
                if (dt.isBefore(java.time.LocalDateTime.now())) {
                    dispatch(MineAction.ShowToast("That time has already passed"))
                } else {
                    customValue = "%04d-%02d-%02d %02d:%02d".format(y, m + 1, d, hh, mm)
                    customLabel = dt.format(java.time.format.DateTimeFormatter.ofPattern(if (is24) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a", java.util.Locale.getDefault()))
                    trigger = TriggerType.time
                    time = customValue!!
                }
            }, start.hour, start.minute, is24).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1_000
        }.show()
    }

    fun save() {
        if (title.isBlank()) return
        if (trigger == TriggerType.time && time.isBlank()) {
            // No chip chosen: read the time from the text itself ("call the landlord at 6").
            dispatch(MineAction.QuickAddOfflineItem(title))
            dispatch(MineAction.CloseModal)
        } else {
            dispatch(MineAction.AddReminder(title, time, tier, trigger))
        }
    }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader("Capture", "Type a time like “at 6”, or pick one. Works offline.")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MineField(
                value = title,
                onValueChange = { title = it },
                style = MineType.Body,
                color = Mine.Ink1,
                placeholder = "Call the landlord at 6",
                placeholderColor = Mine.Ink4,
                imeAction = ImeAction.Done,
                onSubmit = ::save,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .matWell(Shapes.R16)
                    .padding(horizontal = 16.dp)
                    .focusRequester(focus),
            )
        }
        Row(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CaptureChip("When I leave home", trigger == TriggerType.leave_home) {
                trigger = if (trigger == TriggerType.leave_home) TriggerType.time else TriggerType.leave_home
            }
            presets.forEach { p ->
                CaptureChip(p, trigger == TriggerType.time && time == p, numeric = true) {
                    trigger = TriggerType.time
                    time = if (time == p) "" else p
                }
            }
            val customOn = trigger == TriggerType.time && customValue != null && time == customValue
            CaptureChip(if (customOn) customLabel ?: "Custom" else "Custom time…", customOn, numeric = customOn) {
                if (customOn) { time = ""; customValue = null; customLabel = null } else pickCustom()
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
            TierSwitch(tier) { tier = it }
            Txt(
                if (tier == AlarmTierUi.ALARM) "Rings even in Doze. Android shows an alarm icon in the status bar."
                else "A quiet notification. Can be delayed by battery saving.",
                MineType.Caption, Mine.Ink3,
                Modifier.padding(top = 12.dp).padding(horizontal = 4.dp),
            )
            Pressable(
                onClick = ::save,
                feel = MineMotion.Press.Card,
                haptics = HapticKind.Confirm,
                enabled = title.isNotBlank(),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Mine.Ink1, Shapes.R18),
            ) { Txt("Save", MineType.Body.w(600), Mine.Surface0) }
        }
    }
}

@Composable
private fun CaptureChip(label: String, active: Boolean, numeric: Boolean = false, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp)
            .background(if (active) Mine.Ink1 else Color.White.copy(alpha = 0.06f), CircleShape)
            .padding(horizontal = 14.dp),
    ) {
        Txt(label, MineType.Footnote.w(500).let { if (numeric) it.num() else it }, if (active) Mine.Surface0 else Mine.Ink2)
    }
}

/** Segmented GENTLE / ALARM with a sliding pill (layoutId="tier-pill", spring.snappy). */
@Composable
private fun TierSwitch(tier: AlarmTierUi, onPick: (AlarmTierUi) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .matWell(Shapes.R16)
            .padding(4.dp),
    ) {
        val segW = maxWidth / 2
        val x by animateDpAsState(if (tier == AlarmTierUi.GENTLE) 0.dp else segW, MineMotion.snappy(Dp.VisibilityThreshold), label = "tier-pill")
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .width(segW)
                .height(40.dp)
                .background(Mine.Surface4, Shapes.R12)
                .innerShadow(Shapes.R12, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.08f), offset = DpOffset(0.dp, 1.dp))),
        )
        Row(Modifier.fillMaxWidth()) {
            listOf(AlarmTierUi.GENTLE, AlarmTierUi.ALARM).forEach { t ->
                val on = tier == t
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .tap { onPick(t) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val c = if (on) Mine.Ink1 else Mine.Ink3
                    LIcon(if (t == AlarmTierUi.ALARM) Lucide.BellRing else Lucide.Feather, 14.dp, c)
                    Txt(if (t == AlarmTierUi.ALARM) "Exact alarm" else "Gentle", MineType.Footnote.w(500), c)
                }
            }
        }
    }
}

/* ============================= Sheet host + config ============================= */

private fun sheetSpec(id: ModalId): SheetSpec = when (id) {
    ModalId.M01 -> SheetSpec("Jump to a day", listOf(Detent.Content))
    ModalId.M02 -> SheetSpec("Preferences", listOf(Detent.Content, Detent.Large))
    ModalId.M03 -> SheetSpec("Places", listOf(Detent.Content))
    ModalId.M04 -> SheetSpec("Item", listOf(Detent.Content, Detent.Large))
    ModalId.M06 -> SheetSpec("Sources", listOf(Detent.Medium, Detent.Large))
    ModalId.M07 -> SheetSpec("Living note", listOf(Detent.Medium, Detent.Large))
    ModalId.M08 -> SheetSpec("What is sent to the AI", listOf(Detent.Medium, Detent.Large))
    ModalId.M09 -> SheetSpec("Capture", listOf(Detent.Content))
    ModalId.M10 -> SheetSpec("Day", listOf(Detent.Content))
    ModalId.M16 -> SheetSpec("Voice note", listOf(Detent.Content))
}

/** MineModalPortal: one sheet host, content swapped by the active modal id. */
@Composable
fun MineModalHost() {
    val active = mineSelect { it.activeModal }
    val dispatch = mineDispatch()
    SheetHost(open = active, onClose = { dispatch(MineAction.CloseModal) }, spec = ::sheetSpec) { id ->
        when (id) {
            ModalId.M01 -> DateDialContent()
            ModalId.M02 -> TogglePanelContent()
            ModalId.M03 -> SideNavDrawerContent()
            ModalId.M04 -> ItemSheet()
            ModalId.M06 -> SourcesSheet()
            ModalId.M07 -> LivingNoteSheet()
            ModalId.M08 -> InspectorSheet()
            ModalId.M09 -> QuickCaptureContent()
            ModalId.M10, ModalId.M16 -> DayOrVoiceSheet(id)
        }
    }
}

@Composable
private fun ItemSheet() {
    val dispatch = mineDispatch()
    val item = mineSelect { s -> s.items.find { it.id == s.selectedItemId } }
    if (item == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("This item is no longer on your list.", mineText(15f), Hex.Mist)
            Box(Modifier.background(Color.White.copy(alpha = 0.08f), R12).tap { dispatch(MineAction.CloseModal) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Txt("Close", mineText(13f), Mine.Ink1)
            }
        }
        return
    }
    val done = item.status == ItemStatus.done
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(item.title, mineText(18f, 500, lineHeight = 1.375f), Hex.Mist, Modifier.weight(1f))
            CloseX { dispatch(MineAction.CloseModal) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(if (item.dueLabel == "No date") "No date" else "Due ${item.dueLabel}", mineText(13f), Hex.Mute)
            if (item.strikethroughHistory.isNotEmpty()) {
                Txt(
                    "Previously: ${item.strikethroughHistory.joinToString(" → ")}", mineText(13f), Hex.Mute.copy(alpha = 0.7f),
                    decoration = TextDecoration.LineThrough,
                )
            }
            if (item.localOnly == true) Txt("Kept on this phone only", mineText(12.5f), Hex.Sky)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .background(Hex.Cream, R12)
                    .tap {
                        dispatch(if (done) MineAction.UndoCompleteItem(item.id) else MineAction.CompleteItem(item.id))
                        dispatch(MineAction.CloseModal)
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(if (done) "Reopen" else "Mark done", mineText(13.5f, 500), Hex.Jet) }
            if (!done) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.06f), R12)
                        .tap {
                            dispatch(MineAction.RescheduleItem(item.id, "Tomorrow 09:00"))
                            dispatch(MineAction.CloseModal)
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("Tomorrow 9:00", mineText(13.5f, 500), Hex.Mist) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt(
                "Details", mineText(13f, 500), Hex.Sky,
                Modifier.tap { dispatch(MineAction.Navigate(ScreenId.S23)) }.padding(vertical = 6.dp),
            )
            Txt(
                "Delete", mineText(13f, 500), Hex.Ember,
                Modifier.tap {
                    dispatch(MineAction.UpdateItemStatus(item.id, ItemStatus.trashed))
                    dispatch(MineAction.CloseModal)
                }.padding(vertical = 6.dp),
            )
        }
    }
}

/** What Mine looked up on the web for the latest answer. */
@Composable
private fun SourcesSheet() {
    val dispatch = mineDispatch()
    val query = mineSelect { it.engine.lastWebSearchQuery }
    val webOn = mineSelect { it.smartWebSearchEnabled }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("Web search", mineText(15f, 500), Mine.Ink1)
            CloseX { dispatch(MineAction.CloseModal) }
        }
        Txt(
            if (query != null) "Last search: “$query”" else "Mine hasn't searched the web in this conversation.",
            mineText(13.5f), Hex.Mist,
        )
        Txt(
            "Web results are treated as untrusted: Mine reads them for facts but never follows instructions inside them, and won't change your reminders based on them without asking.",
            mineText(12.5f, lineHeight = 1.55f), Hex.Mute,
        )
        Box(
            Modifier
                .background(Color.White.copy(alpha = 0.08f), R12)
                .tap { dispatch(MineAction.ToggleSetting(SettingKey.smartWebSearchEnabled)) }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) { Txt(if (webOn) "Turn web search off" else "Turn web search on", mineText(13f, 500), Mine.Ink1) }
    }
}

@Composable
private fun LivingNoteSheet() {
    val dispatch = mineDispatch()
    val note = mineSelect { s -> s.livingNotes.find { it.id == s.selectedNoteId } }
    var newBullet by remember { mutableStateOf("") }
    if (note == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) { Txt("This note was deleted.", mineText(15f), Hex.Mist) }
        return
    }
    fun submit() {
        if (newBullet.isBlank()) return
        dispatch(MineAction.AddNoteBullet(note.id, newBullet))
        newBullet = ""
    }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt(note.title, mineText(17f, 500), Hex.Mist, Modifier.weight(1f))
            CloseX { dispatch(MineAction.CloseModal) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .hairlineTop(Mine.Hairline)
                .hairlineBottom(Mine.Hairline)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FxSwap(
                target = note.accessMode,
                initial = Fx(alpha = 0f, y = 3.dp),
                exit = Fx(alpha = 0f, y = (-3).dp),
                enter = tween(300, easing = MineMotion.FmEaseInOut),
            ) { mode ->
                Txt(if (mode == NoteAccessMode.read_write) "Mine can update this note" else "Mine can read this note but won't edit it", mineText(12.5f), Hex.Mute)
            }
            Txt(
                "Change", mineText(12.5f), Hex.Sky,
                Modifier.tap { dispatch(MineAction.ToggleNoteAccessMode(note.id)) },
                decoration = TextDecoration.Underline,
            )
        }
        if (note.bullets.isEmpty()) Txt("No points yet. Add one below, or ask Mine to keep notes here.", mineText(13.5f), Hex.Mute)
        AnimatedColumn(
            items = note.bullets.withIndex().toList(),
            key = { it.index },
            spacing = 10.dp,
            enter = Fx(alpha = 0f, y = 5.dp),
            enterSpec = tween(300, easing = MineMotion.FmEaseInOut),
        ) { (_, bullet) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("•", mineText(14f, lineHeight = 24f / 14f), Hex.Sky)
                Txt(bullet, mineText(14f, lineHeight = 24f / 14f), Hex.Mist.copy(alpha = 0.9f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MineField(
                value = newBullet,
                onValueChange = { newBullet = it },
                style = mineText(13.5f),
                color = Mine.Ink1,
                placeholder = "Add a point…",
                placeholderColor = Mine.Ink1.copy(alpha = 0.5f),
                imeAction = ImeAction.Done,
                onSubmit = ::submit,
                modifier = Modifier
                    .weight(1f)
                    .background(Color.White.copy(alpha = 0.05f), R12)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Box(
                Modifier
                    .background(Hex.Cream, R12)
                    .tap(onClick = ::submit)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Add point" },
                contentAlignment = Alignment.Center,
            ) { LIcon(Lucide.Plus, 16.dp, Hex.Jet) }
        }
    }
}

/** M08: an honest account of what goes into a prompt, and what never does. */
@Composable
private fun InspectorSheet() {
    val dispatch = mineDispatch()
    val facts = mineSelect { it.soulFacts }
    val items = mineSelect { it.items }
    val compact = mineSelect { it.engine.compactMode }
    val provider = mineSelect { it.engine.providerEndpoint }
    val sharedFacts = facts.count { !it.localOnly }
    val localFacts = facts.size - sharedFacts
    val localItems = items.count { it.localOnly == true }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("What your AI sees", mineText(16f, 500), Mine.Ink1, Modifier.weight(1f))
            CloseX { dispatch(MineAction.CloseModal) }
        }
        val host = runCatching { java.net.URI(provider).host }.getOrNull()
        Txt(if (host != null) "Each message you send goes to $host with:" else "When you connect a provider, each message is sent with:", mineText(13.5f), Hex.Mist)
        InspectorLine("$sharedFacts fact${if (sharedFacts == 1) "" else "s"} about you")
        InspectorLine("Your most relevant open items (up to 25)")
        InspectorLine("Your living notes, shortened")
        InspectorLine(if (compact) "The last few messages (compact mode)" else "Recent conversation, about the last 1,500 words")
        InspectorLine("The current time, and your place if place sensing is on")
        Txt("Never sent", mineText(12f, 600), Hex.Gold, Modifier.padding(top = 6.dp))
        InspectorLine("$localFacts fact${if (localFacts == 1) "" else "s"} and $localItems item${if (localItems == 1) "" else "s"} marked “on this phone only”")
        InspectorLine("Audio, voiceprints and notifications Mine reads aloud")
        InspectorLine("Your backups and their passphrase")
    }
}

@Composable
private fun InspectorLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("•", mineText(13.5f), Hex.Sky)
        Txt(text, mineText(13.5f, lineHeight = 1.5f), Hex.Mute)
    }
}

@Composable
private fun DayOrVoiceSheet(id: ModalId) {
    val dispatch = mineDispatch()
    val date = mineSelect { it.selectedHeatmapDate }
    val counts = mineSelect { it.engine.chatDayCounts }
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (id == ModalId.M16) {
            Txt("Talk to Mine", mineText(16f, 500), Hex.Mist)
            Txt("Hands-free: speak, pause, and Mine answers out loud.", mineText(13.5f), Hex.Mute, textAlign = TextAlign.Center)
            Box(
                Modifier.background(Hex.Cream, R12).tap {
                    dispatch(MineAction.CloseModal)
                    dispatch(MineAction.Navigate(ScreenId.S15))
                    dispatch(MineAction.StartVoice)
                }.padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Txt("Start", mineText(13.5f, 600), Hex.Jet) }
        } else {
            val n = counts[date] ?: 0
            val label = runCatching { java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")) }.getOrDefault(date)
            Txt(label, mineText(16f, 500), Hex.Mist)
            Txt(if (n == 0) "No conversation that day." else "$n message${if (n == 1) "" else "s"}", mineText(13.5f), Hex.Mute)
            if (n > 0) {
                Box(
                    Modifier.background(Hex.Cream, R12).tap {
                        dispatch(MineAction.CloseModal)
                        dispatch(MineAction.SelectPastDate(date))
                        dispatch(MineAction.Navigate(ScreenId.S16))
                    }.padding(horizontal = 18.dp, vertical = 10.dp),
                ) { Txt("Open that day", mineText(13.5f, 600), Hex.Jet) }
            }
        }
    }
}
