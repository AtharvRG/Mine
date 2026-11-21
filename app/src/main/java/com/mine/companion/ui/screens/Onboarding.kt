package com.mine.companion.ui.screens

import android.Manifest
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.core.ui.UiAction
import com.mine.companion.store.MineAction
import com.mine.companion.store.PersonaType
import com.mine.companion.store.ScreenId
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.NativeCheckbox
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.primitives.tap
import com.mine.companion.ui.theme.Hex
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.mineText
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.w

/*
 * First run. Quick setup: consent → name → details → day → AI → permissions → done.
 * Full setup adds persona, how memory works, voice enrolment, Soul seed, privacy and backups.
 * Every step writes real settings; nothing here is a demo.
 */

/* ============================ S01 – S06 ============================ */

@Composable
fun S01LaunchGate() {
    val dispatch = mineDispatch()
    val done = mineSelect { it.onboardingDone }
    LegacyScreen(verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Txt("Mine", mineText(12f), Hex.Mute)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Mine.", serifHeading(48f, 1f), Hex.Bone)
                Txt("Your private, encrypted personal companion.", mineText(15f, lineHeight = 1.625f), Hex.Mute, Modifier.widthIn(max = 240.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (done) PrimaryButton("Open Home") { dispatch(MineAction.Navigate(ScreenId.S14)) }
                else PrimaryButton("Get started") { dispatch(MineAction.Navigate(ScreenId.S02)) }
            }
        }
    }
}

@Composable
fun S02Welcome() {
    val dispatch = mineDispatch()
    LegacyScreen(verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Private by design", mineText(12f), Hex.Gold)
            Txt("A second brain that belongs only to you.", serifHeading(38f, 1.12f), Hex.Bone)
            Txt(
                "Reminders that actually ring, notes that keep themselves tidy, and an assistant you can just talk to. Stored encrypted on this phone, connected to the AI you choose.",
                mineText(14.5f, lineHeight = 1.625f), Hex.Mute, Modifier.padding(top = 4.dp),
            )
        }
        PaperSheet(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Quick setup", dark = true, content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Quick setup · 1 minute", mineText(14f, 500), Hex.Cream)
                    LIcon(Lucide.ArrowRight, 16.dp, Hex.Cream)
                }
            }) {
                dispatch(MineAction.SetOnboardingFastPath(true))
                dispatch(MineAction.Navigate(ScreenId.S03))
            }
            SecondaryButton("Set everything up", onPaper = true) {
                dispatch(MineAction.SetOnboardingFastPath(false))
                dispatch(MineAction.Navigate(ScreenId.S03))
            }
        }
    }
}

/** Dark-screen footer holding the main action, clear of the nav bar. */
@Composable
private fun ColumnScope.Footer(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun S03Consent() {
    val dispatch = mineDispatch()
    val accepted = mineSelect { it.consentAccepted }
    LegacyScreen {
        TopStepNav("Your privacy") { dispatch(MineAction.GoBack) }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt("Your words stay yours.", serifHeading(32f, 1.25f), Hex.Bone)
            Txt(
                "Tasks, notes and what Mine learns about you live in an encrypted database on this phone. When you chat, your message and the context it needs go to the AI provider you choose. Anything you mark “on this phone only” is never sent.",
                mineText(14.5f, lineHeight = 28f / 14.5f), Hex.Mute,
            )
            Row(
                Modifier.padding(top = 12.dp).tap { dispatch(MineAction.SetConsent(!accepted)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NativeCheckbox(accepted, { dispatch(MineAction.SetConsent(it)) }, accent = Hex.Gold)
                Txt("I understand", mineText(14f), Hex.Bone)
            }
        }
        Spacer(Modifier.weight(1f))
        Footer {
            PrimaryButton("Continue", enabled = accepted) { dispatch(MineAction.Navigate(ScreenId.S04)) }
        }
    }
}

private val Languages = listOf("English", "Hindi", "Hinglish", "Spanish", "French", "German", "Arabic", "Bengali", "Tamil", "Portuguese")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun S04SetupBasics() {
    val dispatch = mineDispatch()
    val name = mineSelect { it.userName }
    val langs = mineSelect { it.selectedLanguages }
    LegacyScreen {
        TopStepNav("About you") { dispatch(MineAction.GoBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("What should I call you?", serifHeading(30f, 1.2f), Hex.Bone)
            MineField(
                value = name,
                onValueChange = { dispatch(MineAction.SetUserName(it.take(40))) },
                style = mineText(17f),
                color = Hex.Bone,
                placeholder = "Your name",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Hex.Field, R16)
                    .border(1.dp, Mine.Hairline, R16)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(18.dp))
            Txt("Languages you chat in", mineText(13f), Hex.Mute)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Languages.forEach { l ->
                    val on = l in langs
                    Pressable(
                        onClick = { dispatch(MineAction.ToggleLanguage(l)) },
                        feel = MineMotion.Press.Chip,
                        modifier = Modifier.height(36.dp).background(if (on) Hex.Cream else Hex.Field, CircleShape).padding(horizontal = 14.dp),
                    ) { Txt(l, mineText(13.5f, 500), if (on) Hex.Jet else Hex.Bone) }
                }
            }
        }
        Footer { PrimaryButton("Continue", enabled = name.isNotBlank()) { dispatch(MineAction.Navigate(ScreenId.S45)) } }
    }
}

/** S05: the day dial — 48 half-hour ticks, the waking window bright. */
@Composable
fun S05YourDay() {
    val dispatch = mineDispatch()
    val fast = mineSelect { it.onboardingFastPath }
    val startLabel = mineSelect { it.dayStart }
    val endLabel = mineSelect { it.dayEnd }
    var start by rememberSaveable { mutableIntStateOf(parse(startLabel) ?: 7 * 60) }
    var end by rememberSaveable { mutableIntStateOf(parse(endLabel) ?: 22 * 60) }
    val hours = ((end - start + 1440) % 1440) / 60f

    LegacyScreen {
        TopStepNav("Your day") { dispatch(MineAction.GoBack) }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                val bright = Hex.Cream
                val dim = Color.White.copy(alpha = 0.16f)
                Canvas(Modifier.size(200.dp)) {
                    scale(size.width / 120f, pivot = Offset.Zero) {
                        for (idx in 0 until 48) {
                            val minute = idx * 30
                            val active = if (start < end) minute in start until end else minute >= start || minute < end
                            rotate(idx * 7.5f - 90f + 180f, pivot = Offset(60f, 60f)) {
                                drawLine(
                                    color = if (active) bright else dim,
                                    start = Offset(60f, 6f),
                                    end = Offset(60f, if (active) 15f else 11f),
                                    strokeWidth = if (active) 2f else 1.2f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("${if (hours % 1f == 0f) hours.toInt().toString() else "%.1f".format(hours)}h", mineText(38f, 300, lineHeight = 1f).num(), Hex.Bone)
                    Txt("awake", mineText(11f), Hex.Mute, Modifier.padding(top = 4.dp))
                }
            }
            Txt("Mine stays quiet outside these hours.", mineText(13f), Hex.Mute, Modifier.padding(top = 16.dp))
        }
        Spacer(Modifier.weight(1f))
        PaperSheet(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TimeStepper("Wake", start) { start = it }
                TimeStepper("Wind down", end, alignEnd = true) { end = it }
            }
            PrimaryButton("Continue", dark = true, enabled = start != end) {
                dispatch(MineAction.SetDayWindow(start, end))
                dispatch(MineAction.SetDayBounds(fmt(start), fmt(end)))
                dispatch(MineAction.Navigate(if (fast) ScreenId.S07 else ScreenId.S06))
            }
        }
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, alignEnd: Boolean = false, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, mineText(12f), Hex.Zinc500)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pressable(onClick = { onChange((minute - 30 + 1440) % 1440) }, feel = MineMotion.Press.Icon, label = "Earlier",
                modifier = Modifier.size(34.dp).background(Color.Black.copy(alpha = 0.06f), CircleShape)) { LIcon(Lucide.ChevronLeft, 16.dp, Hex.Jet) }
            Txt(fmt(minute), mineText(18f, 700).num(), Hex.Jet)
            Pressable(onClick = { onChange((minute + 30) % 1440) }, feel = MineMotion.Press.Icon, label = "Later",
                modifier = Modifier.size(34.dp).background(Color.Black.copy(alpha = 0.06f), CircleShape)) { LIcon(Lucide.ChevronRight, 16.dp, Hex.Jet) }
        }
    }
}

private fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun parse(s: String): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(s)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

private val PersonaCopy = mapOf(
    PersonaType.companion to "Warm and attentive. Checks in, remembers what matters.",
    PersonaType.cofounder to "Direct and ambitious. Pushes you to ship.",
    PersonaType.coach to "Encouraging, with structure. Keeps you accountable.",
    PersonaType.chill to "Easygoing. Light reminders, no pressure.",
    PersonaType.minimal to "Few words. Just the facts.",
    PersonaType.ultron to "Immaculate English, theatrical wit, mocks your excuses — then helps, precisely.",
)

@Composable
fun S06Persona() {
    val dispatch = mineDispatch()
    val persona = mineSelect { it.persona }
    LegacyScreen {
        TopStepNav("How Mine talks") { dispatch(MineAction.GoBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("Pick a voice. You can change it any time.", mineText(14f), Hex.Mute, Modifier.padding(bottom = 8.dp))
            PersonaCopy.forEach { (p, copy) ->
                val active = persona == p
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) Hex.Cream else Hex.Field, R16)
                        .tap { dispatch(MineAction.SetPersona(p)) }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Txt(if (p == PersonaType.chill) "Chill friend" else p.name.replaceFirstChar { it.uppercase() }, mineText(16f, 600), if (active) Hex.Jet else Hex.Bone)
                    Txt(copy, mineText(13f), if (active) Hex.Zinc600 else Hex.Mute)
                }
            }
        }
        Footer { PrimaryButton("Continue") { dispatch(MineAction.Navigate(ScreenId.S07)) } }
    }
}

/* ============================ S07 – S13 ============================ */

/** S07 doubles as the AI provider settings page once onboarding is done. */
@Composable
fun S07ConnectAI() {
    val done = mineSelect { it.onboardingDone }
    if (done) {
        ProviderSettingsScreen()
        return
    }
    val dispatch = mineDispatch()
    val fast = mineSelect { it.onboardingFastPath }
    val next = if (fast) ScreenId.S09 else ScreenId.S08
    SettingsPage("Connect your AI", "Mine works with any OpenAI-compatible provider. You pay them directly; Mine never sees your bill.") {
        ProviderForm(onSaved = { dispatch(MineAction.Navigate(next)) })
        Spacer(Modifier.height(10.dp))
        CapsuleButton("Skip for now", primary = false) { dispatch(MineAction.Navigate(next)) }
        SectionFooter("Without a provider, reminders and quick tasks still work. You can connect one later in Settings.")
    }
}

/** S08–S12 share one shape: back chevron, a headline block, then the step's content and Continue. */
@Composable
private fun StepScreen(next: ScreenId, cta: String = "Continue", content: @Composable ColumnScope.() -> Unit) {
    val dispatch = mineDispatch()
    LegacyScreen {
        TopStepNav("") { dispatch(MineAction.GoBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), content = content)
        Footer { PrimaryButton(cta) { dispatch(MineAction.Navigate(next)) } }
    }
}

@Composable
fun S08MemoryModel() = StepScreen(ScreenId.S09) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("On this phone", mineText(12f), Hex.Moss)
        Txt("How Mine remembers", serifHeading(32f), Mine.Ink1)
        Txt("Everything you tell Mine becomes tasks, notes and facts about you, stored encrypted on this phone. When you ask about something, Mine searches that memory first, so answers fit your life.", mineText(14f, lineHeight = 1.625f), Hex.Mute)
        Txt("If the on-device search model is installed, finding things works even offline. You can review, correct or delete anything Mine remembers.", mineText(14f, lineHeight = 1.625f), Hex.Mute)
    }
}

@Composable
fun S09Permissions() {
    val dispatch = mineDispatch()
    val p = mineSelect { it.platform }
    val fast = mineSelect { it.onboardingFastPath }
    // Full setup continues to voice enrolment (needs the mic), then the Soul seed.
    StepScreen(if (fast) ScreenId.S13 else ScreenId.S42) {
        Txt("A few permissions", serifHeading(32f), Mine.Ink1)
        Txt("Only notifications matter for reminders. The rest are optional and can change any time.", mineText(14f, lineHeight = 1.625f), Hex.Mute, Modifier.padding(top = 8.dp, bottom = 20.dp))
        PermissionCard(
            "Notifications", "So reminders and alarms can ring.", p.notificationsGranted, required = true,
        ) { dispatch(MineAction.RequestPermission(Manifest.permission.POST_NOTIFICATIONS)) }
        PermissionCard("Microphone", "For voice chat and ambient listening.", p.micGranted) {
            dispatch(MineAction.RequestPermission(Manifest.permission.RECORD_AUDIO))
        }
        PermissionCard("Notification access", "Lets Mine read your notifications aloud while it listens. Off until you turn it on.", p.notificationAccess) {
            dispatch(MineAction.OpenNotificationAccess)
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, granted: Boolean, required: Boolean = false, onAllow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(Hex.Field, R16)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, mineText(15f, 600), Hex.Bone)
                if (required && !granted) Txt("Needed", mineText(11f, 600), Hex.Gold)
            }
            Txt(body, mineText(13f), Hex.Mute)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LIcon(Lucide.Check, 15.dp, Hex.Moss)
                Txt("On", mineText(13f, 500), Hex.Moss)
            }
        } else {
            Pressable(onClick = onAllow, feel = MineMotion.Press.Control, modifier = Modifier.height(34.dp).background(Hex.Cream, CircleShape).padding(horizontal = 14.dp)) {
                Txt("Allow", mineText(13f, 600), Hex.Jet)
            }
        }
    }
}

private data class SeedQ(val category: String, val question: String, val placeholder: String)

// "What do you do?" is asked on the details screen (S45) in both setup paths.
private val SeedQuestions = listOf(
    SeedQ("routine", "What does a normal day look like?", "Gym at 7, work 9–6, gaming at night…"),
    SeedQ("reply_style", "How should I reply?", "Short and direct, or warm and detailed…"),
    SeedQ("health", "Anything health-related I should keep in mind?", "Optional"),
)

@Composable
fun S10SoulSeed() {
    val dispatch = mineDispatch()
    val answers = mineSelect { it.engine.onboardingAnswers }
    val healthLocal = mineSelect { it.engine.onboardingHealthLocalOnly }
    StepScreen(ScreenId.S11) {
        Txt("Help Mine know you", serifHeading(32f), Mine.Ink1)
        Txt("Skip anything. You can edit all of it later in About you.", mineText(14f), Hex.Mute, Modifier.padding(top = 8.dp, bottom = 18.dp))
        SeedQuestions.forEach { q ->
            var text by rememberSaveable(q.category) { mutableStateOf(answers[q.category].orEmpty()) }
            Txt(q.question, mineText(13.5f, 500), Hex.Bone, Modifier.padding(bottom = 6.dp))
            MineField(
                value = text,
                onValueChange = {
                    text = it.take(240)
                    dispatch(MineAction.Engine(UiAction.OnboardingAnswer(q.category, text)))
                },
                style = mineText(15f),
                color = Hex.Bone,
                placeholder = q.placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (q.category == "health") 8.dp else 16.dp)
                    .background(Hex.Field, R16)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            )
            if (q.category == "health") {
                Row(
                    Modifier.padding(bottom = 16.dp).tap { dispatch(MineAction.Engine(UiAction.OnboardingHealthLocalOnly(!healthLocal))) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NativeCheckbox(healthLocal, { dispatch(MineAction.Engine(UiAction.OnboardingHealthLocalOnly(it))) }, accent = Hex.Gold)
                    Txt("Keep this on this phone only", mineText(13f), Hex.Mute)
                }
            }
        }
    }
}

@Composable
fun S11PrivacyOptions() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    StepScreen(ScreenId.S12) {
        Txt("Lock it down", serifHeading(32f), Mine.Ink1)
        Txt("Both are optional.", mineText(14f), Hex.Mute, Modifier.padding(top = 8.dp, bottom = 20.dp))
        ToggleCard("App lock", "Require your fingerprint, face or screen lock to open Mine.", e.appLockEnabled) {
            dispatch(MineAction.Engine(UiAction.SetAppLock(it)))
        }
        ToggleCard("Private notifications", "Show “Reminder” on the lock screen instead of the text.", e.hideNotificationContent) {
            dispatch(MineAction.Engine(UiAction.SetHideNotificationContent(it)))
        }
    }
}

@Composable
private fun ToggleCard(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Hex.Field, R16).tap { onChange(!on) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(title, mineText(15f, 600), Hex.Bone)
            Txt(body, mineText(13f), Hex.Mute)
        }
        com.mine.companion.ui.primitives.MineSwitch(on, onChange, title)
    }
}

@Composable
fun S12BackupNote() = StepScreen(ScreenId.S13) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Your data, your keys", mineText(12f), Hex.Gold)
        Txt("Back up now and then", serifHeading(32f), Mine.Ink1)
        Txt("Mine lives only on this phone. If you lose it, an encrypted backup is the only way back. You choose the passphrase, and nobody else can open it.", mineText(14f, lineHeight = 1.625f), Hex.Mute)
        Txt("Turn on weekly backups to a folder (like Google Drive) in Settings → Backup & restore.", mineText(14f, lineHeight = 1.625f), Hex.Mute)
    }
}

/** S13: centre-horizon greeting over the paper "Start" bar. */
@Composable
fun S13Done() {
    val dispatch = mineDispatch()
    val name = mineSelect { it.userName }
    val connected = mineSelect { it.engine.providerKey.isNotBlank() || it.engine.onDeviceFallbackEnabled }
    LegacyScreen {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt(
                    if (name.isNotBlank()) "You're all set, ${name.trim()}." else "You're all set.",
                    serifHeading(30f, 1.25f), Hex.Bone, textAlign = TextAlign.Center,
                )
                Txt(
                    if (connected) "Tell Mine anything — “gym at 7 tomorrow”, “I'm stressed about Friday”, or just “hey”."
                    else "Try “gym at 7 tomorrow” — it works right away. Connect an AI in Settings to chat.",
                    mineText(14.5f, lineHeight = 1.6f), Hex.Mute, textAlign = TextAlign.Center,
                )
            }
        }
        PaperSheet {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Txt("Ready", mineText(12f), Hex.Zinc500)
                    Txt("Open Mine", mineText(15f, 700), Hex.Jet)
                }
                Box(
                    Modifier
                        .background(Hex.Jet, CircleShape)
                        .tap { dispatch(MineAction.FinishOnboarding) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Txt("Start", mineText(13.5f, 500), Hex.Cream) }
            }
        }
    }
}
