package com.anchor.mine.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.mine.ui.components.HairlineDivider
import com.anchor.mine.ui.components.VoiceOrb
import com.anchor.mine.ui.theme.DeepTeal
import com.anchor.mine.ui.theme.HairlineLight
import com.anchor.mine.ui.theme.WarmCream

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentStep by remember { mutableIntStateOf(0) }
    val nextButtonInteractionSource = remember { MutableInteractionSource() }

    var name by remember { mutableStateOf("") }
    var callName by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var nimKey by remember { mutableStateOf("") }

    var enrollmentStep by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    val enrollmentPhrases = listOf(
        "I am setting up my digital co-founder to help me remember the important things.",
        "The quick brown fox jumps over the lazy dog to test the microphone quality.",
        "I need the AI to understand my specific tone, pacing, and vocabulary.",
        "Voice enrollment complete. My profile is now securely stored on this device."
    )

    val context = LocalContext.current
    val keyManager = remember { com.anchor.mine.security.KeyManager(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepTeal)
            .systemBarsPadding()
            .imePadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Onboarding",
                style = MaterialTheme.typography.displayMedium,
                color = WarmCream
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Step ${currentStep + 1} of 8",
                style = MaterialTheme.typography.labelSmall,
                color = WarmCream.copy(alpha = 0.5f)
            )
        }

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
            },
            modifier = Modifier.weight(1f)
        ) { step ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (step) {
                    0 -> SingleInputQuestion("Your Full Name?", name) { name = it }
                    1 -> SingleInputQuestion("What would you like me to call you?", callName) { callName = it }
                    2 -> DateInputQuestion("When were you born?", dob) { dob = it }
                    3 -> GenderSelectionQuestion(gender) { gender = it }
                    4 -> MultiLineQuestion("In your words, explain how you want your AI to sound like? (Personality)", persona) { persona = it }
                    5 -> MultiLineQuestion("Tell me about yourself! What basic things should I know about you?", memories) { memories = it }
                    6 -> APIKeysQuestion(groqKey, nimKey, { groqKey = it }, { nimKey = it })
                    7 -> VoiceEnrollmentQuestion(
                        phrase = enrollmentPhrases.getOrNull(enrollmentStep) ?: "",
                        step = enrollmentStep,
                        isRecording = isRecording,
                        onRecordToggle = {
                            isRecording = !isRecording
                            if (!isRecording && enrollmentStep < 3) enrollmentStep++
                        },
                        onSkip = { currentStep++ }
                    )
                    8 -> CompletionScreen()
                }
            }
        }

        val canProceed = when (currentStep) {
            0 -> name.isNotBlank()
            1 -> callName.isNotBlank()
            2 -> dob.length == 8
            3 -> gender.isNotBlank()
            4 -> persona.isNotBlank()
            5 -> memories.isNotBlank()
            6 -> groqKey.isNotBlank() && nimKey.isNotBlank()
            7 -> enrollmentStep >= 4
            else -> true
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            if (canProceed) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(WarmCream)
                        .clickable(
                            interactionSource = nextButtonInteractionSource,
                            indication = LocalIndication.current,
                            onClick = {
                                if (currentStep < 8) {
                                    if (currentStep == 6) {
                                        keyManager.saveGroqKey(groqKey)
                                        keyManager.saveNimKey(nimKey)
                                    }
                                    currentStep++
                                } else {
                                    onComplete()
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next",
                        tint = DeepTeal
                    )
                }
            }
        }
    }
}

// --- COMPONENTS WITH ANIMATED LINES ---


@Composable
fun SingleInputQuestion(question: String, value: String, onValueChange: (String) -> Unit) {
    val isFocused = value.isNotEmpty()
    val lineColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isFocused) com.anchor.mine.ui.theme.WarmCream else com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.2f),
        animationSpec = androidx.compose.animation.core.tween(300), label = "lineColor"
    )

    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text(text = question, style = MaterialTheme.typography.displayMedium, color = com.anchor.mine.ui.theme.WarmCream)
        Spacer(modifier = Modifier.height(32.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.displayLarge.copy(color = com.anchor.mine.ui.theme.WarmCream),
            cursorBrush = SolidColor(com.anchor.mine.ui.theme.WarmCream),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // FIXED: Using the official Material 3 Divider!
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            thickness = 2.dp,
            color = lineColor
        )
    }
}

@Composable
fun DateInputQuestion(question: String, value: String, onValueChange: (String) -> Unit) {
    val isFocused = value.isNotEmpty()
    val lineColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isFocused) com.anchor.mine.ui.theme.WarmCream else com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.2f),
        animationSpec = androidx.compose.animation.core.tween(300), label = "lineColor"
    )

    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text(text = question, style = MaterialTheme.typography.displayMedium, color = com.anchor.mine.ui.theme.WarmCream)
        Text("DD / MM / YYYY", style = MaterialTheme.typography.labelSmall, color = com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))

        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 8 && it.all { char -> char.isDigit() }) onValueChange(it) },
            textStyle = MaterialTheme.typography.displayLarge.copy(color = com.anchor.mine.ui.theme.WarmCream),
            cursorBrush = SolidColor(com.anchor.mine.ui.theme.WarmCream),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            visualTransformation = DateTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            thickness = 2.dp,
            color = lineColor
        )
    }
}

@Composable
fun MultiLineQuestion(question: String, value: String, onValueChange: (String) -> Unit) {
    val isFocused = value.isNotEmpty()
    val lineColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isFocused) com.anchor.mine.ui.theme.WarmCream else com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.2f),
        animationSpec = androidx.compose.animation.core.tween(300), label = "lineColor"
    )

    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text(text = question, style = MaterialTheme.typography.displayMedium, color = com.anchor.mine.ui.theme.WarmCream)
        Text("Enter and the prompt will be enhanced by AI (NLP).", style = MaterialTheme.typography.labelSmall, color = com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = com.anchor.mine.ui.theme.WarmCream, fontSize = 24.sp),
            cursorBrush = SolidColor(com.anchor.mine.ui.theme.WarmCream),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            thickness = 2.dp,
            color = lineColor
        )
    }
}

@Composable
fun GenderSelectionQuestion(selectedGender: String, onSelect: (String) -> Unit) {
    val options = listOf("Male", "Female", "Human", "Doesn't Matter")
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = "Gender?", style = MaterialTheme.typography.displayMedium, color = WarmCream)
        Spacer(modifier = Modifier.height(32.dp))
        options.forEach { option ->
            val interactionSource = remember { MutableInteractionSource() }
            Text(
                text = option,
                style = MaterialTheme.typography.displayLarge.copy(
                    color = if (selectedGender == option) WarmCream else WarmCream.copy(alpha = 0.2f),
                    fontStyle = if (selectedGender == option) FontStyle.Italic else FontStyle.Normal
                ),
                modifier = Modifier.padding(vertical = 8.dp).clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onSelect(option) }
            )
        }
    }
}

@Composable
fun APIKeysQuestion(groq: String, nim: String, onGroqChange: (String) -> Unit, onNimChange: (String) -> Unit) {
    val groqFocused = groq.isNotEmpty()
    val nimFocused = nim.isNotEmpty()

    val groqLine by androidx.compose.animation.animateColorAsState(if (groqFocused) com.anchor.mine.ui.theme.WarmCream else com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.2f), label = "groqLine")
    val nimLine by androidx.compose.animation.animateColorAsState(if (nimFocused) com.anchor.mine.ui.theme.WarmCream else com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.2f), label = "nimLine")

    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text(text = "The Engine.", style = MaterialTheme.typography.displayMedium, color = com.anchor.mine.ui.theme.WarmCream)
        Text("Provide your keys to generate your AI's SOUL.", style = MaterialTheme.typography.labelSmall, color = com.anchor.mine.ui.theme.WarmCream.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))

        Text(text = "Groq API Key", style = MaterialTheme.typography.bodyLarge, color = com.anchor.mine.ui.theme.WarmCream)
        Spacer(modifier = Modifier.height(8.dp))
        BasicTextField(
            value = groq, onValueChange = onGroqChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = com.anchor.mine.ui.theme.WarmCream),
            cursorBrush = SolidColor(com.anchor.mine.ui.theme.WarmCream), modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp), thickness = 2.dp, color = groqLine)

        Text(text = "Nvidia NIM API Key", style = MaterialTheme.typography.bodyLarge, color = com.anchor.mine.ui.theme.WarmCream)
        Spacer(modifier = Modifier.height(8.dp))
        BasicTextField(
            value = nim, onValueChange = onNimChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = com.anchor.mine.ui.theme.WarmCream),
            cursorBrush = SolidColor(com.anchor.mine.ui.theme.WarmCream), modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 2.dp, color = nimLine)
    }
}

@Composable
fun VoiceEnrollmentQuestion(phrase: String, step: Int, isRecording: Boolean, onRecordToggle: () -> Unit, onSkip: () -> Unit) {
    val recordInteraction = remember { MutableInteractionSource() }
    val skipInteraction = remember { MutableInteractionSource() }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Voice Print", style = MaterialTheme.typography.displayMedium, color = WarmCream)
        Text("Read the following phrases to generate your local X-Vector. This helps the AI identify you in a noisy room.", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.5f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))

        VoiceOrb(isListening = isRecording)

        Spacer(modifier = Modifier.height(32.dp))

        if (step < 4) {
            Text("\"$phrase\"", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp, fontStyle = FontStyle.Italic), color = WarmCream, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Phrase ${step + 1} of 4", style = MaterialTheme.typography.labelSmall, color = WarmCream.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier.fillMaxWidth().clip(com.anchor.mine.ui.components.EditorialShape).background(if (isRecording) DeepTeal else WarmCream).clickable(interactionSource = recordInteraction, indication = LocalIndication.current, onClick = onRecordToggle).border(1.dp, HairlineLight, com.anchor.mine.ui.components.EditorialShape).padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isRecording) "Tap to Stop" else "Tap to Record", color = if (isRecording) WarmCream else DeepTeal, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        } else {
            Text("Voice Print Generated.", style = MaterialTheme.typography.bodyLarge, color = WarmCream)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Skip for now", style = MaterialTheme.typography.bodyLarge, color = WarmCream.copy(alpha = 0.5f), modifier = Modifier.clickable(interactionSource = skipInteraction, indication = LocalIndication.current, onClick = onSkip))
    }
}

@Composable
fun CompletionScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Onboarding\nCompleted.", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold), color = WarmCream, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Your SOUL is being generated.", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = WarmCream.copy(alpha = 0.6f))
    }
}

class DateTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        var out = ""
        for (i in text.text.indices) {
            out += text.text[i]
            if (i == 1 || i == 3) out += "/"
        }
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 1) return offset
                if (offset <= 3) return offset + 1
                if (offset <= 8) return offset + 2
                return 10
            }
            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 2) return offset
                if (offset <= 5) return offset - 1
                if (offset <= 10) return offset - 2
                return 8
            }
        }
        return TransformedText(AnnotatedString(out), offsetMapping)
    }
}