// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.mine.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anchor.core.ambient.VoiceEnrollment
import com.anchor.core.notifications.AppScope
import com.anchor.core.ui.ProfileFields
import com.anchor.core.ui.UiAction
import com.anchor.core.voice.ModelAssetKind
import com.anchor.core.voice.ModelCatalog
import com.mine.companion.store.MineAction
import com.mine.companion.store.ScreenId
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.fx.AgentState
import com.mine.companion.ui.fx.Orb
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.GroupRow
import com.mine.companion.ui.primitives.GroupedSurface
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.w

/* ============================== S42 Teach Mine your voice ============================== */

/**
 * Voice enrolment: read five short sentences in a quiet place. Each clip is checked for level,
 * noise and clipping, its x-vector is compared with the others, and the verdict says whether
 * Mine can now tell you from other people. Part of full setup; also reachable from Listening.
 */
@Composable
fun S42VoiceEnrollment() {
    val dispatch = mineDispatch()
    val en = mineSelect { it.platform.enrollment }
    val p = mineSelect { it.platform }
    val onboarding = mineSelect { !it.onboardingDone }
    val count = VoiceEnrollment.RECOMMENDED_CLIPS
    val recording = en.phase == "recording"
    val analysing = en.phase == "analysing"
    val done = en.phase == "done"
    val prompt = VoiceEnrollment.PROMPTS[en.prompt.coerceIn(0, VoiceEnrollment.PROMPTS.lastIndex)]

    LaunchedEffect(Unit) { if (en.phase == "idle" && en.clips.isEmpty()) dispatch(MineAction.EnrollReset) }

    fun next() = dispatch(MineAction.Navigate(if (onboarding) ScreenId.S10 else ScreenId.S36))

    SettingsPage(
        "Teach Mine your voice",
        "So Mine can tell you apart from everyone else when it listens. Takes about a minute.",
        onBack = { dispatch(MineAction.GoBack) },
    ) {
        StatusCard(
            "Find a quiet place first",
            "No TV, music or people talking. Hold the phone about a hand's length away and read each sentence clearly, at your normal volume.",
            Tone.Warn, Lucide.Info,
        )
        if (!p.speakerModelInstalled || !p.vadInstalled) {
            Spacer(Modifier.height(10.dp))
            StatusCard(
                "Install the voice models for the best result",
                buildString {
                    if (!p.speakerModelInstalled) append("The speaker model makes real voiceprints (x-vectors). ")
                    if (!p.vadInstalled) append("Silero VAD trims silence. ")
                    append("Without them Mine uses a rough fallback.")
                },
                Tone.Neutral, Lucide.Download, "Models", { dispatch(MineAction.Navigate(ScreenId.S44)) },
            )
        }
        Spacer(Modifier.height(18.dp))

        // The orb breathes with your voice while recording.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val scale = 1f + (if (recording) en.level * 0.18f else 0f)
            Box(Modifier.size(150.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                Orb(
                    colors = Color(0xFF38BDF8) to Color(0xFF1D4ED8),
                    agentState = when { recording -> AgentState.Listening; analysing -> AgentState.Thinking; else -> null },
                    modifier = Modifier.size(150.dp).clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // Progress dots: one per clip, green when good, amber when it needs another take.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (i in 0 until count) {
                val c = en.clips.firstOrNull { it.index == i }
                val color = when {
                    c == null -> Mine.Surface3
                    c.ok -> Mine.Settled
                    else -> Mine.Accent
                }
                Box(Modifier.padding(horizontal = 4.dp).size(if (i == en.prompt && !done) 12.dp else 9.dp).background(color, CircleShape))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!done) {
            GroupedSurface {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Sentence ${en.prompt + 1} of $count", MineType.Caption.w(600), Mine.Ink3)
                    Txt("“$prompt”", serifHeading(22f, 1.35f), Mine.Ink1)
                    when {
                        recording -> Txt("Listening… it stops by itself when you finish.", MineType.Footnote, Mine.Accent)
                        analysing -> Txt("Checking that clip…", MineType.Footnote, Mine.Ink3)
                        else -> en.clips.firstOrNull { it.index == en.prompt && !it.ok }?.let { c ->
                            Txt(c.issues.joinToString(" "), MineType.Footnote, Mine.Accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            CapsuleButton(
                if (recording) "Stop" else if (en.clips.any { it.index == en.prompt }) "Read it again" else "Start reading",
                busy = analysing,
            ) { if (recording) dispatch(MineAction.EnrollStop) else dispatch(MineAction.EnrollRecord(en.prompt)) }
        } else {
            val good = en.verdict == "good"
            val ok = en.verdict == "okay"
            StatusCard(
                en.verdictTitle ?: "Done",
                buildString {
                    append("Your clips match each other ${(en.consistency * 100).toInt()}%.")
                    if (!en.neural) append(" (Rough fallback model.)")
                },
                if (good) Tone.Good else if (ok) Tone.Warn else Tone.Bad,
                if (good) Lucide.Check else Lucide.TriangleAlert,
            )
            if (en.advice.isNotEmpty()) {
                Section("What would help") {
                    en.advice.forEachIndexed { i, a -> GroupRow(a, divider = i < en.advice.lastIndex) }
                }
            }
            Section("Your clips") {
                en.clips.forEachIndexed { i, c ->
                    GroupRow(
                        title = "Sentence ${c.index + 1}",
                        detail = if (c.ok) c.similarity?.let { "Sounds like you · ${(it * 100).toInt()}%" } ?: "Good" else c.issues.joinToString(" "),
                        divider = i < en.clips.lastIndex,
                        trailing = {
                            Txt("Redo", MineType.Footnote.w(600), Mine.Accent, Modifier.tap { dispatch(MineAction.EnrollRedo(c.index)) }.padding(8.dp))
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (en.saved) {
                CapsuleButton(if (onboarding) "Continue" else "Done") { next() }
            } else {
                CapsuleButton("Save my voice", enabled = good || ok) { dispatch(MineAction.EnrollSave) }
                Spacer(Modifier.height(8.dp))
                CapsuleButton("Start over", primary = false) { dispatch(MineAction.EnrollReset) }
            }
        }
        if (onboarding && !en.saved) {
            Spacer(Modifier.height(8.dp))
            CapsuleButton("Skip for now", primary = false) { next() }
        }
        SectionFooter("Recordings stay in memory only and are discarded after each clip. Only your averaged voiceprint is saved, encrypted on this phone, and it is never exported or sent anywhere.")
    }
}

/* ============================ S43 What Mine can read ============================ */

/**
 * Every app on the phone and what Mine does with its notifications, before any arrive:
 * "Read" (always), "Default" (follows the scope above), "Muted" (never). Plus the rules that no
 * setting can change.
 */
@Composable
fun S43NotificationSources() {
    val dispatch = mineDispatch()
    val s = mineSelect { it.engine.notificationReading }
    val apps = mineSelect { it.platform.installedApps }
    val recent = mineSelect { it.platform.recentApps }
    var q by rememberSaveable { mutableStateOf("") }
    val all = remember(apps, recent) { (apps + recent).toList().sortedBy { it.second.lowercase() } }
    val shown = all.filter { q.isBlank() || it.second.contains(q.trim(), ignoreCase = true) }

    SettingsPage("What Mine can read", "Choose, app by app, which notifications Mine may read aloud while it listens.") {
        Section("Which apps", footer = when (s.scope) {
            AppScope.MESSAGES -> "Chats and missed calls from any app, plus apps you set to Always read."
            AppScope.ALL -> "Every app's normal and urgent notifications, except muted apps."
            AppScope.ALLOWLIST -> "Only apps you set to Always read."
        }) {
            ChoiceRow(null, listOf(AppScope.MESSAGES, AppScope.ALL, AppScope.ALLOWLIST), s.scope, {
                when (it) { AppScope.MESSAGES -> "Messages"; AppScope.ALL -> "All apps"; AppScope.ALLOWLIST -> "Only chosen" }
            }, divider = false) { dispatch(MineAction.SetNotificationReading(s.copy(scope = it))) }
        }
        Section("Never read, whatever you choose") {
            listOf(
                "Mine's own notifications",
                "Calls, alarms, timers and stopwatches",
                "Music and media controls, navigation, downloads and progress bars",
                "Ongoing and silent notifications, group summaries",
                "Verification codes, card numbers and other long numbers (removed before reading)",
                "Promotions — unless you set that app to Always read",
            ).forEachIndexed { i, t -> GroupRow(t, divider = i < 5) }
        }
        SectionHeader("Apps (${all.size})")
        GroupedSurface {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LIcon(Lucide.Search, 16.dp, Mine.Ink3)
                MineField(q, { q = it }, MineType.Callout, Mine.Ink1, Modifier.fillMaxWidth(), placeholder = "Find an app")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (all.isEmpty()) {
            GroupedSurface { GroupRow("Loading apps…", divider = false) }
        } else GroupedSurface {
            shown.forEachIndexed { i, (pkg, label) ->
                val state = when (pkg) {
                    in s.blockedPackages -> "Muted"
                    in s.allowedPackages -> "Always read"
                    else -> "Default"
                }
                val effective = when {
                    state == "Muted" -> "Never read"
                    state == "Always read" -> "Read"
                    s.scope == AppScope.ALLOWLIST -> "Not read"
                    s.scope == AppScope.MESSAGES -> "Chats only"
                    else -> "Read"
                }
                GroupRow(
                    title = label,
                    detail = effective,
                    divider = i < shown.lastIndex,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Default", "Always read", "Muted").forEach { opt ->
                                val on = opt == state
                                Txt(
                                    when (opt) { "Always read" -> "Read"; else -> opt }, MineType.Caption.w(600),
                                    if (on) Mine.Canvas else Mine.Ink2,
                                    Modifier
                                        .background(if (on) (if (opt == "Muted") Mine.Urgent else Mine.Ink1) else Mine.Surface2, CircleShape)
                                        .tap {
                                            val next = when (opt) {
                                                "Always read" -> s.copy(allowedPackages = s.allowedPackages + pkg, blockedPackages = s.blockedPackages - pkg)
                                                "Muted" -> s.copy(allowedPackages = s.allowedPackages - pkg, blockedPackages = s.blockedPackages + pkg)
                                                else -> s.copy(allowedPackages = s.allowedPackages - pkg, blockedPackages = s.blockedPackages - pkg)
                                            }
                                            dispatch(MineAction.SetNotificationReading(next))
                                        }
                                        .padding(horizontal = 9.dp, vertical = 5.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        SectionFooter("Mine reads only while ambient listening is on, and only with Android's notification access. Nothing it reads is stored: the “Heard” list lives in memory until you close the app.")
    }
}

/* ================================ S44 On-device models ================================ */

/** Download, choose and delete the models Mine runs on this phone. */
@Composable
fun S44Models() {
    val dispatch = mineDispatch()
    val models = mineSelect { it.platform.models }
    val prefs = mineSelect { it.engine.profile.prefs }
    val activeEmbedder = prefs[ModelCatalog.KEY_ACTIVE_EMBEDDER]
    val activeTts = prefs[ModelCatalog.KEY_ACTIVE_TTS]
    val mobile = prefs[ModelCatalog.KEY_ALLOW_MOBILE_DATA] == "1"

    SettingsPage("Models", "Everything here runs on your phone. Downloaded once, after you see the size and licence; never bundled.") {
        Section(footer = "Large downloads use Wi-Fi unless you allow mobile data. Each file is checked against its published SHA-256 before it's used.") {
            SwitchRow("Allow mobile data", null, mobile, divider = false) {
                dispatch(MineAction.Engine(UiAction.SetPref(ModelCatalog.KEY_ALLOW_MOBILE_DATA, if (it) "1" else "0")))
            }
        }
        ModelGroup("Search memory (embeddings)", "The model that finds things by meaning. Switching re-indexes your memory on the phone.",
            ModelCatalog.ALL.filter { it.kind == ModelAssetKind.EMBED }, models, activeEmbedder, dispatch)
        ModelGroup("Voice (text to speech)", "Mine's spoken voice. Android's voice is used when none is installed.",
            ModelCatalog.ALL.filter { it.kind == ModelAssetKind.TTS }, models, activeTts, dispatch)
        ModelGroup("Listening & speech", "Needed for private voice chat, ambient listening and knowing your voice.",
            ModelCatalog.ALL.filter { it.kind == ModelAssetKind.VAD || it.kind == ModelAssetKind.SPEAKER || it.kind == ModelAssetKind.STT }, models, null, dispatch)
    }
}

@Composable
private fun ModelGroup(
    title: String,
    footer: String,
    entries: List<ModelCatalog.Entry>,
    models: Map<String, com.mine.companion.store.ModelUi>,
    activeId: String?,
    dispatch: (MineAction) -> Unit,
) {
    Section(title, footer = footer) {
        entries.forEachIndexed { i, e ->
            val m = models[e.id] ?: com.mine.companion.store.ModelUi()
            val chooseable = e.kind == ModelAssetKind.EMBED || e.kind == ModelAssetKind.TTS
            val isActive = activeId == e.id
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(e.name, MineType.Callout.w(600), Mine.Ink1, Modifier.weight(1f))
                    if (e.recommended) Txt("Recommended", MineType.Caption.w(600), Mine.Settled)
                    if (isActive) Txt("In use", MineType.Caption.w(600), Mine.Accent)
                }
                Txt(e.summary, MineType.Footnote, Mine.Ink2)
                Txt("${mb(e.approxBytes)} · ${e.license}", MineType.Caption, Mine.Ink3)
                val note = e.note
                if (!e.runnable && note != null) Txt(note, MineType.Caption, Mine.Accent)
                e.attribution?.let { Txt(it, MineType.Caption.w(500), Mine.Ink3) }
                m.error?.takeIf { m.state == "failed" }?.let { Txt(it, MineType.Caption, Mine.Urgent) }
                if (m.state == "downloading") {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Mine.Surface3, CircleShape)) {
                        Box(Modifier.fillMaxWidth(m.progress.coerceIn(0.02f, 1f)).height(4.dp).background(Mine.Accent, CircleShape))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        !e.runnable -> Txt("Can't run on Android yet", MineType.Caption.w(600), Mine.Ink3)
                        m.state == "downloading" -> SmallPill("Cancel ${(m.progress * 100).toInt()}%") { dispatch(MineAction.CancelModel(e.id)) }
                        m.state == "ready" -> {
                            if (chooseable && !isActive) SmallPill("Use this", primary = true) { dispatch(MineAction.ActivateModel(e.id)) }
                            SmallPill("Delete · ${mb(m.bytesOnDisk)}") { dispatch(MineAction.DeleteModel(e.id)) }
                        }
                        else -> SmallPill(if (m.state == "failed") "Try again" else "Download", primary = true) { dispatch(MineAction.DownloadModel(e.id)) }
                    }
                }
            }
            if (i < entries.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Mine.Hairline))
        }
    }
}

@Composable
private fun SmallPill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        feel = MineMotion.Press.Chip,
        modifier = Modifier.height(32.dp).background(if (primary) Mine.Ink1 else Mine.Surface2, CircleShape).padding(horizontal = 14.dp),
    ) { Txt(label, MineType.Footnote.w(600), if (primary) Mine.Canvas else Mine.Ink1) }
}

private fun mb(bytes: Long): String = if (bytes >= 1_000_000) "${(bytes + 500_000) / 1_000_000} MB" else "${(bytes + 500) / 1000} KB"

/* ============================ S45 A bit about you (onboarding) ============================ */

/**
 * Personal details, in quick and full setup alike. Everything is optional; each answer is a
 * `profile.*` setting and becomes an identity fact when setup finishes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun S45AboutYouDetails() {
    val dispatch = mineDispatch()
    val prefs = mineSelect { it.engine.profile.prefs }
    val fast = mineSelect { it.onboardingFastPath }
    val done = mineSelect { it.onboardingDone }
    val localOnly = prefs[ProfileFields.KEY_LOCAL_ONLY] == "1"

    LegacyScreen {
        TopStepNav("A bit about you") { dispatch(MineAction.GoBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("Help Mine get the basics right", serifHeading(28f, 1.2f), Hex.Bone)
            Txt("All optional. You can change any of it later in About you.", mineText(13.5f), Hex.Mute, Modifier.padding(top = 6.dp, bottom = 14.dp))
            ProfileFields.ALL.forEach { f -> ProfileInput(f, prefs[f.key].orEmpty()) { v -> dispatch(MineAction.Engine(UiAction.SetPref(f.key, v.take(300)))) } }
            Row(
                Modifier.padding(top = 10.dp).tap { dispatch(MineAction.Engine(UiAction.SetPref(ProfileFields.KEY_LOCAL_ONLY, if (localOnly) "0" else "1"))) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                com.mine.companion.ui.primitives.NativeCheckbox(localOnly, { dispatch(MineAction.Engine(UiAction.SetPref(ProfileFields.KEY_LOCAL_ONLY, if (it) "1" else "0"))) }, accent = Hex.Gold)
                Txt("Keep these on this phone only (your AI won't see them)", mineText(13f), Hex.Mute)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp)) {
            PrimaryButton("Continue") { dispatch(MineAction.Navigate(if (done) ScreenId.S20 else ScreenId.S05)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ProfileInput(f: ProfileFields.Field, stored: String, onSave: (String) -> Unit) {
    Txt(f.label, mineText(13f, 500), Hex.Bone, Modifier.padding(top = 10.dp, bottom = 6.dp))
    when (f.kind) {
        ProfileFields.Kind.CHOICE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            f.choices.forEach { c ->
                val on = stored == c
                Pressable(
                    onClick = { onSave(if (on) "" else c) },
                    feel = MineMotion.Press.Chip,
                    modifier = Modifier.height(34.dp).background(if (on) Hex.Cream else Hex.Field, CircleShape).padding(horizontal = 14.dp),
                ) { Txt(c, mineText(13f, 500), if (on) Hex.Jet else Hex.Bone) }
            }
        }
        else -> {
            var text by rememberSaveable(f.key) { mutableStateOf(stored) }
            val today = remember { java.time.LocalDate.now() }
            val dobBad = f.kind == ProfileFields.Kind.DATE && text.isNotBlank() && ProfileFields.parseDob(text, today) == null
            MineField(
                value = text,
                onValueChange = { text = it.take(300); if (f.kind != ProfileFields.Kind.DATE || ProfileFields.parseDob(it, today) != null || it.isBlank()) onSave(text) },
                style = mineText(15f),
                color = Hex.Bone,
                placeholder = f.placeholder,
                modifier = Modifier.fillMaxWidth().background(Hex.Field, R16).padding(horizontal = 16.dp, vertical = 13.dp),
            )
            if (dobBad) Txt("Try “14 March 1998” or “14/03/1998”.", mineText(12f), Hex.Gold, Modifier.padding(top = 4.dp))
            else if (f.kind == ProfileFields.Kind.DATE && text.isNotBlank()) {
                ProfileFields.parseDob(text, today)?.let { d -> Txt("Turns ${ProfileFields.ageOn(d, today) + 1} on ${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }}", mineText(12f), Hex.Mute, Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

/* ==================================== About-you chat ==================================== */

/**
 * The small, isolated chat on the About you page: Mine interviews you to learn more. No voice,
 * no web, its own history. Facts it picks up arrive as proposals in your inbox (or straight into
 * Soul if you allowed auto-accept). "Cook a question" asks something new when you're stuck.
 */
@Composable
fun AboutYouChatCard() {
    val dispatch = mineDispatch()
    val ui by com.anchor.mine.profile.AboutYouSession.ui.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(Color.White.copy(alpha = 0.04f), R16)
            .border(1.dp, Color.White.copy(alpha = 0.08f), R16),
    ) {
        Row(
            Modifier.fillMaxWidth().tap { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LIcon(Lucide.Sparkles, 16.dp, Hex.Sky)
            Column(Modifier.weight(1f)) {
                Txt("Tell Mine about you", mineText(14.5f, 600), Hex.Bone)
                Txt(if (ui.learned > 0) "${ui.learned} new thing${if (ui.learned == 1) "" else "s"} to review in your inbox" else "A short chat. Mine asks, you answer.", mineText(12f), Hex.Mute)
            }
            LIcon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, 16.dp, Hex.Mute)
        }
        if (open) {
            val scroll = rememberScrollState()
            LaunchedEffect(ui.turns.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(
                Modifier.fillMaxWidth().height(300.dp).verticalScroll(scroll).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ui.turns.forEach { t ->
                    val mine = t.role != "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
                        Txt(
                            t.text, mineText(13.5f, lineHeight = 1.5f), if (mine) Hex.Bone else Hex.Jet,
                            Modifier
                                .fillMaxWidth(0.85f)
                                .background(if (mine) Color.White.copy(alpha = 0.06f) else Hex.Cream, R12)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
                if (ui.busy) Txt("…", mineText(16f), Hex.Mute)
                ui.problem?.let { Txt(it, mineText(11.5f), Hex.Gold) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).background(Hex.Field, R12).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MineField(
                        value = draft, onValueChange = { draft = it.take(1000) },
                        style = mineText(14f), color = Hex.Bone, placeholder = "Say something about you…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Pressable(
                    onClick = { if (draft.isNotBlank()) { dispatch(MineAction.AboutYouSend(draft)); draft = "" } },
                    enabled = draft.isNotBlank() && !ui.busy,
                    feel = MineMotion.Press.Icon,
                    label = "Send",
                    modifier = Modifier.size(40.dp).background(Hex.Cream, CircleShape),
                ) { LIcon(Lucide.Send, 14.dp, Hex.Jet) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.background(Color.White.copy(alpha = 0.06f), CircleShape).tap(enabled = !ui.busy) { dispatch(MineAction.AboutYouSuggest) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LIcon(Lucide.Sparkles, 12.dp, Hex.Sky)
                    Txt("Cook a question", mineText(12.5f, 500), Hex.Bone)
                }
                Txt("Edit details", mineText(12.5f, 500), Hex.Sky, Modifier.tap { dispatch(MineAction.Navigate(ScreenId.S45)) }.padding(horizontal = 8.dp, vertical = 7.dp))
                Spacer(Modifier.weight(1f))
                Txt("Start over", mineText(12.5f), Hex.Mute, Modifier.tap { dispatch(MineAction.AboutYouReset) }.padding(horizontal = 8.dp, vertical = 7.dp))
            }
        }
    }
}
