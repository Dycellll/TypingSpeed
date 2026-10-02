package com.dycel.typingspeed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private val Background = Color(0xFF18181B)
private val Panel = Color(0xFF212125)
private val Border = Color(0xFF2E2E33)
private val TextPrimary = Color(0xFFE4E4E7)
private val TextDim = Color(0xFF71717A)
private val Accent = Color(0xFFA78BFA)
private val Wrong = Color(0xFFF87171)
private val EasyColor = Color(0xFF4ADE80)
private val MediumColor = Color(0xFFFBBF24)
private val HardColor = Color(0xFFFB7185)

enum class Difficulties {
    Easy,
    Medium,
    Hard
}

sealed interface Screen {
    data object Menu : Screen
    data class Test(val difficulty: Difficulties) : Screen
}

private enum class MenuMode { Home, PickDifficulty, History }

private enum class CharState { Pending, Correct, Wrong, Cursor }

private fun Difficulties.multiplier(): Float = when (this) {
    Difficulties.Easy -> 1.0f
    Difficulties.Medium -> 1.35f
    Difficulties.Hard -> 1.8f
}

private fun Difficulties.color(): Color = when (this) {
    Difficulties.Easy -> EasyColor
    Difficulties.Medium -> MediumColor
    Difficulties.Hard -> HardColor
}

private fun calcScore(wpm: Int, accuracy: Int, difficulty: Difficulties): Int {
    val accuracyRatio = (accuracy / 100f).coerceIn(0f, 1f)
    return (wpm * difficulty.multiplier() * accuracyRatio.pow(2)).roundToInt()
}

data class TestRecord(
    val wpm: Int,
    val accuracy: Int,
    val timestamp: String,
    val difficulty: Difficulties = Difficulties.Easy,
    val id: Long = nowMillis()
) {
    val score: Int get() = calcScore(wpm, accuracy, difficulty)
}

private class TestResult(
    val wpm: Int,
    val accuracy: Int,
    val millis: Long,
    val record: TestRecord,
    val newBest: Boolean
)

private val settings by lazy { Settings() }

private fun loadRecords(): List<TestRecord> =
    settings.getString("records", "").lines().withIndex().mapNotNull { (index, line) ->
        val parts = line.split("|")
        if (parts.size < 3) return@mapNotNull null

        val wpm = parts[0].toIntOrNull() ?: return@mapNotNull null
        val accuracy = parts[1].toIntOrNull() ?: return@mapNotNull null
        val difficulty = parts.getOrNull(3)
            ?.let { name -> Difficulties.entries.firstOrNull { it.name == name } }
            ?: Difficulties.Easy
        val id = parts.getOrNull(4)?.toLongOrNull() ?: -(index + 1L)

        TestRecord(wpm, accuracy, parts[2], difficulty, id)
    }

private fun saveRecords(records: List<TestRecord>) {
    val serialized = records.joinToString("\n") {
        "${it.wpm}|${it.accuracy}|${it.timestamp}|${it.difficulty.name}|${it.id}"
    }
    settings.putString("records", serialized)
}

private val timestampFormat = LocalDateTime.Format {
    hour(); char(':'); minute()
    char(' ')
    dayOfMonth(); char('/'); monthNumber(); char('/'); yearTwoDigits(2000)
}

fun currentTimestamp(): String =
    Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .format(timestampFormat)

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun formatSeconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}s"

private fun countCorrectChars(typed: String, target: String): Int =
    typed.indices.count { it < target.length && typed[it] == target[it] }

private fun calcWpm(correctChars: Int, ms: Long): Int =
    if (ms <= 0) 0 else ((correctChars / 5f) / (ms / 60000f)).roundToInt()

private fun splitWords(text: String): List<Pair<Int, String>> {
    val parts = text.split(" ")
    var start = 0
    return parts.mapIndexed { index, word ->
        val chunk = if (index < parts.lastIndex) "$word " else word
        (start to chunk).also { start += chunk.length }
    }
}

private val easyWords = listOf(
    "the", "and", "you", "that", "with", "have", "this", "from", "they", "will",
    "would", "there", "their", "what", "about", "which", "when", "make", "like", "time",
    "just", "know", "take", "people", "into", "year", "your", "good", "some", "could",
    "them", "other", "than", "then", "look", "only", "come", "over", "think", "also",
    "back", "after", "work", "first", "well", "want", "because", "give", "most", "day"
)

private val mediumWords = listOf(
    "thought", "between", "another", "without", "through", "country", "reason", "company",
    "problem", "morning", "picture", "machine", "question", "kitchen", "explain", "several",
    "journey", "balance", "network", "teacher", "weather", "bicycle", "library", "mountain",
    "pattern", "quickly", "history", "chicken", "freedom", "holiday", "imagine", "monitor",
    "painting", "station", "surface", "trouble", "venture", "whisper", "yellow", "harvest"
)

private val hardWords = listOf(
    "extraordinary", "archaeology", "bureaucracy", "conscientious", "phenomenon",
    "questionnaire", "rhythm", "entrepreneur", "acknowledge", "miscellaneous",
    "pronunciation", "surveillance", "vocabulary", "unnecessary", "perseverance",
    "sophisticated", "synchronize", "temperature", "environment", "accommodate",
    "exaggerate", "mediterranean", "imagination", "responsibility", "opportunity",
    "jewelry", "quarantine", "labyrinth", "maintenance", "thoroughly"
)

private fun decorateHardWord(word: String): String {
    var result = word
    if (Random.nextFloat() < 0.25f) result = result.replaceFirstChar { it.uppercase() }
    if (Random.nextFloat() < 0.2f) result += listOf(",", ".", ";").random()
    return result
}

private fun generateText(difficulty: Difficulties): String = when (difficulty) {
    Difficulties.Easy -> easyWords.shuffled().take(20).joinToString(" ")
    Difficulties.Medium -> mediumWords.shuffled().take(25).joinToString(" ")
    Difficulties.Hard -> hardWords.shuffled().take(25).joinToString(" ") { decorateHardWord(it) }
}

@Composable
private fun Modifier.screenInsets(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))

private fun Modifier.zeroLayoutHeight() = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
    )
    layout(placeable.width, 0) { placeable.place(0, 0) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CookieBackground(
    color: Color,
    boosted: Boolean,
    onBoostFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialShapes.Cookie12Sided.toShape()

    val speed by animateFloatAsState(
        targetValue = if (boosted) 16f else 1f,
        animationSpec = tween(200),
        label = "cookieSpeed"
    )

    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val deltaSeconds = (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            angle = (angle + deltaSeconds * 3f * speed) % 360f
        }
    }

    LaunchedEffect(boosted) {
        if (boosted) {
            delay(200.milliseconds)
            onBoostFinished()
        }
    }

    Box(
        modifier.fillMaxSize().drawWithCache {
            val diameter = size.minDimension * 1.3f
            val inset = diameter * 0.15f
            val half = diameter / 2f
            val outline = shape.createOutline(Size(diameter, diameter), layoutDirection, this)

            onDrawBehind {
                fun drawCookie(center: Offset, rotation: Float) {
                    withTransform({
                        translate(center.x - half, center.y - half)
                        rotate(rotation, Offset(half, half))
                    }) { drawOutline(outline, color) }
                }
                drawCookie(Offset(inset, inset), angle)
                drawCookie(Offset(size.width - inset, size.height - inset), -angle)
            }
        }
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 56.sp,
            lineHeight = 56.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(subtitle, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MenuHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TextPrimary,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1).sp,
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingPreview(running: Boolean, modifier: Modifier = Modifier) {
    val text = "The quick brown fox jumps over the lazy dog."
    val words = remember { splitWords(text) }
    var typedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) {
            typedCount = 0
            return@LaunchedEffect
        }
        while (true) {
            for (count in 0..text.length) {
                typedCount = count
                delay(75)
            }
            delay(1600)
            typedCount = 0
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border)
    ) {
        FlowRow(Modifier.padding(20.dp)) {
            words.forEach { (start, chunk) ->
                Row {
                    chunk.forEachIndexed { i, char ->
                        PreviewChar(char = char, typed = start + i < typedCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewChar(char: Char, typed: Boolean) {
    val fade = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }

    LaunchedEffect(typed) {
        if (typed) {
            launch { fade.animateTo(1f, tween(300)) }
            lift.animateTo(-2f, tween(300, easing = FastOutSlowInEasing))
            lift.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        } else {
            fade.snapTo(0f)
            lift.snapTo(0f)
        }
    }

    Text(
        text = char.toString(),
        color = TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 17.sp,
        lineHeight = 28.sp,
        modifier = Modifier.graphicsLayer {
            translationY = lift.value.dp.toPx()
            alpha = 0.4f + 0.6f * fade.value
        }
    )
}

@Composable
private fun TestChar(char: Char, state: CharState) {
    val lift = remember { Animatable(0f) }
    val isTyped = state == CharState.Correct || state == CharState.Wrong

    LaunchedEffect(isTyped) {
        if (isTyped) {
            lift.animateTo(-3f, tween(200, easing = FastOutSlowInEasing))
            lift.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        } else {
            lift.snapTo(0f)
        }
    }

    val textColor = when (state) {
        CharState.Correct, CharState.Cursor -> TextPrimary
        CharState.Wrong -> Wrong
        CharState.Pending -> TextDim
    }
    val backgroundColor = when (state) {
        CharState.Wrong -> Wrong.copy(alpha = 0.18f)
        CharState.Cursor -> Accent.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Text(
        text = char.toString(),
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        lineHeight = 34.sp,
        modifier = Modifier
            .graphicsLayer { translationY = lift.value.dp.toPx() }
            .background(backgroundColor)
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DifficultyChip(difficulty: Difficulties) {
    val color = difficulty.color()
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(difficulty.name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SortPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) Accent else Border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Accent else TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HistoryEntry(
    record: TestRecord,
    number: Int,
    maxScore: Int,
    interactive: Boolean,
    visible: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBest = record.score == maxScore && record.score > 0
    val targetFraction = if (maxScore > 0) (record.score / maxScore.toFloat()).coerceIn(0f, 1f) else 0f

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val barFraction by animateFloatAsState(
        targetValue = if (visible && appeared) targetFraction else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "historyBar"
    )

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "#" + number.toString().padStart(2, '0'),
                color = TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "${record.wpm}",
                color = if (isBest) Accent else TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 30.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "wpm",
                color = TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    record.timestamp,
                    color = TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(
                    "${record.accuracy}% acc",
                    color = if (isBest) Accent else TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            DifficultyChip(record.difficulty)
            Spacer(Modifier.width(10.dp))
            Text(
                "score ${record.score}",
                color = if (isBest) Accent else TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable(enabled = interactive, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = TextDim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Border)
                .drawBehind {
                    drawRect(
                        color = if (isBest) Accent else TextDim,
                        size = Size(size.width * barFraction, size.height)
                    )
                }
        )
    }
}

@Composable
private fun HistoryList(
    records: List<TestRecord>,
    open: Boolean,
    fadeAlpha: Float,
    height: androidx.compose.ui.unit.Dp,
    onDelete: (TestRecord) -> Unit
) {
    var sortByBest by remember { mutableStateOf(false) }

    val maxScore = records.maxOfOrNull { it.score } ?: 0
    val numbered = records.mapIndexed { index, record -> (index + 1) to record }
    val shown = if (sortByBest) numbered.sortedByDescending { it.second.score } else numbered.asReversed()

    Box(
        Modifier
            .zeroLayoutHeight()
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = fadeAlpha }
            .border(1.dp, Border, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = open
        ) {
            if (records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing here yet. Finish a test and it will show up.",
                        color = TextDim,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 16.dp, end = 16.dp)
                    )
                }
            } else {
                item(key = "header") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SortPill("Recent", selected = !sortByBest, enabled = open) { sortByBest = false }
                        SortPill("Best score", selected = sortByBest, enabled = open) { sortByBest = true }
                    }
                }
            }
            items(shown, key = { it.second.id }) { (number, record) ->
                HistoryEntry(
                    record = record,
                    number = number,
                    maxScore = maxScore,
                    interactive = open,
                    visible = open,
                    onDelete = { onDelete(record) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun DeleteDialog(record: TestRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextDim,
        title = { Text("Delete this score?", fontWeight = FontWeight.Bold) },
        text = { Text("${record.wpm} wpm · ${record.accuracy}% acc · ${record.difficulty.name}\n${record.timestamp}") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Wrong, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextPrimary)
            }
        }
    )
}

@Composable
private fun MenuScreen(
    mode: MenuMode,
    records: List<TestRecord>,
    onStartTest: () -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
    onDifficultySelect: (Difficulties) -> Unit,
    onDelete: (TestRecord) -> Unit,
    modifier: Modifier = Modifier,
    drawAlpha: Float = 1f,
    bestScore: String = "0",
    accuracy: String = "0%",
    testsTaken: String = "0"
) {
    var pendingDelete by remember { mutableStateOf<TestRecord?>(null) }

    val picking = mode == MenuMode.PickDifficulty
    val viewingHistory = mode == MenuMode.History

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .screenInsets()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .graphicsLayer { alpha = drawAlpha },
        contentAlignment = Alignment.TopCenter
    ) {
        val screenHeight = maxHeight

        val startFade by animateFloatAsState(
            targetValue = if (picking) 0f else 1f,
            animationSpec = tween(durationMillis = 400),
            label = "startFade"
        )
        val historyFade by animateFloatAsState(
            targetValue = if (viewingHistory) 0f else 1f,
            animationSpec = tween(durationMillis = 400),
            label = "historyFade"
        )
        val liftMain by animateFloatAsState(
            targetValue = if (picking) -0.14f else 0f,
            animationSpec = tween(durationMillis = 400),
            label = "liftMain"
        )
        val liftBack by animateFloatAsState(
            targetValue = when (mode) {
                MenuMode.PickDifficulty -> -0.14f
                MenuMode.History -> -0.07f
                MenuMode.Home -> 0f
            },
            animationSpec = tween(durationMillis = 400),
            label = "liftBack"
        )

        val homeAlpha = minOf(historyFade, startFade)
        val pickerAlpha = minOf(historyFade, 1f - startFade)

        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.padding(top = 32.dp)) {
                ScreenTitle("Typing Speed", "Put your typing skills to the test.")
            }

            Box {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.graphicsLayer {
                        translationY = 32f.dp.toPx()
                        alpha = homeAlpha
                    }
                ) {
                    TypingPreview(running = homeAlpha > 0f, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Best Score", bestScore, Modifier.weight(1f))
                        StatCard("Accuracy", accuracy, Modifier.weight(1f))
                        StatCard("Tests", testsTaken, Modifier.weight(1f))
                    }
                }

                MenuHeading(
                    "Choose the difficulty.",
                    Modifier
                        .padding(top = screenHeight * 0.12f)
                        .graphicsLayer {
                            translationY = (screenHeight * 0.04f).toPx()
                            alpha = 1f - startFade
                        }
                )

                MenuHeading(
                    "View your past records.",
                    Modifier
                        .padding(top = 140.dp)
                        .graphicsLayer {
                            translationY = -190f.dp.toPx()
                            alpha = 1f - historyFade
                        }
                )

                HistoryList(
                    records = records,
                    open = viewingHistory,
                    fadeAlpha = 1f - historyFade,
                    height = screenHeight * 0.5f,
                    onDelete = { pendingDelete = it }
                )
            }

            Column(
                Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val liftedMain = Modifier.graphicsLayer {
                    translationY = liftMain * screenHeight.toPx()
                    alpha = pickerAlpha
                }

                PrimaryButton(
                    text = "Easy",
                    onClick = { if (picking) onDifficultySelect(Difficulties.Easy) },
                    modifier = liftedMain
                )
                PrimaryButton(
                    text = "Medium",
                    onClick = { if (picking) onDifficultySelect(Difficulties.Medium) },
                    modifier = liftedMain
                )
                PrimaryButton(
                    text = if (picking) "Hard" else "Start Test",
                    onClick = {
                        when (mode) {
                            MenuMode.History -> Unit
                            MenuMode.PickDifficulty -> onDifficultySelect(Difficulties.Hard)
                            MenuMode.Home -> onStartTest()
                        }
                    },
                    modifier = Modifier.graphicsLayer {
                        translationY = liftMain * screenHeight.toPx()
                        alpha = historyFade
                    }
                )
                SecondaryButton(
                    text = if (mode == MenuMode.Home) "History" else "Back",
                    onClick = if (mode == MenuMode.Home) onHistory else onBack,
                    modifier = Modifier.graphicsLayer { translationY = liftBack * screenHeight.toPx() }
                )
            }
        }
    }

    pendingDelete?.let { record ->
        DeleteDialog(
            record = record,
            onConfirm = {
                onDelete(record)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingCard(
    words: List<Pair<Int, String>>,
    typed: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border)
    ) {
        FlowRow(Modifier.padding(20.dp)) {
            words.forEach { (start, chunk) ->
                Row {
                    chunk.forEachIndexed { i, char ->
                        val index = start + i
                        val state = when {
                            index < typed.length ->
                                if (typed[index] == char) CharState.Correct else CharState.Wrong
                            index == typed.length -> CharState.Cursor
                            else -> CharState.Pending
                        }
                        TestChar(char, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: TestResult, difficulty: Difficulties) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${result.wpm}",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 72.sp,
                lineHeight = 72.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(6.dp))
            Text("words per minute", color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "score ${result.record.score}",
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (result.newBest) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "New personal best",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("Accuracy", "${result.accuracy}%", Modifier.weight(1f))
        StatCard("Time", formatSeconds(result.millis), Modifier.weight(1f))
        StatCard("Mode", difficulty.name, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TestScreen(
    difficulty: Difficulties,
    bestScore: Int,
    active: Boolean,
    onSave: (TestRecord) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    drawAlpha: Float = 1f
) {
    var attempt by remember { mutableIntStateOf(0) }
    val target = remember(difficulty, attempt) { generateText(difficulty) }
    val words = remember(target) { splitWords(target) }

    var typed by remember(difficulty, attempt) { mutableStateOf("") }
    var keystrokes by remember(difficulty, attempt) { mutableIntStateOf(0) }
    var correctKeys by remember(difficulty, attempt) { mutableIntStateOf(0) }
    var startedAt by remember(difficulty, attempt) { mutableLongStateOf(0L) }
    var elapsed by remember(difficulty, attempt) { mutableLongStateOf(0L) }
    var result by remember(difficulty, attempt) { mutableStateOf<TestResult?>(null) }
    var saved by remember(difficulty, attempt) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun openKeyboard() {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    LaunchedEffect(active) {
        if (active) attempt++
    }

    LaunchedEffect(startedAt, result) {
        if (startedAt == 0L || result != null) return@LaunchedEffect
        while (true) {
            elapsed = nowMillis() - startedAt
            delay(100)
        }
    }

    LaunchedEffect(active, attempt, result) {
        if (active && result == null) openKeyboard()
    }

    fun handleInput(raw: String) {
        if (result != null) return

        val next = raw.take(target.length)
        if (next.length > typed.length && next.startsWith(typed)) {
            if (startedAt == 0L) startedAt = nowMillis()
            for (i in typed.length until next.length) {
                keystrokes++
                if (next[i] == target[i]) correctKeys++
            }
        }
        typed = next

        if (next.length == target.length && startedAt != 0L) {
            val ms = (nowMillis() - startedAt).coerceAtLeast(1)
            val wpm = calcWpm(countCorrectChars(next, target), ms)
            val accuracy = if (keystrokes == 0) 0 else (correctKeys * 100f / keystrokes).roundToInt()
            val record = TestRecord(wpm, accuracy, currentTimestamp(), difficulty)

            elapsed = ms
            result = TestResult(
                wpm = wpm,
                accuracy = accuracy,
                millis = ms,
                record = record,
                newBest = record.score > bestScore
            )
        }
    }

    fun saveOnce(finished: TestResult) {
        if (saved) return
        saved = true
        onSave(finished.record)
    }

    Box(
        modifier = modifier
            .then(if (active) Modifier.pointerInput(Unit) {} else Modifier)
            .fillMaxSize()
            .screenInsets()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .graphicsLayer { alpha = drawAlpha },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.padding(top = 32.dp)) {
                ScreenTitle("Speed Test", "Difficulty: $difficulty")
            }

            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val finished = result

                    if (finished == null) {
                        TypingCard(words = words, typed = typed, onClick = ::openKeyboard)

                        Text(
                            "Start typing whenever you're ready.",
                            color = TextDim,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = if (startedAt == 0L) 1f else 0f }
                        )

                        val liveWpm = if (elapsed > 0) calcWpm(countCorrectChars(typed, target), elapsed) else 0
                        val liveAccuracy = if (keystrokes == 0) 100 else (correctKeys * 100f / keystrokes).roundToInt()

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("WPM", "$liveWpm", Modifier.weight(1f))
                            StatCard("Accuracy", "$liveAccuracy%", Modifier.weight(1f))
                            StatCard("Time", formatSeconds(elapsed), Modifier.weight(1f))
                        }

                        if (active) {
                            BasicTextField(
                                value = typed,
                                onValueChange = ::handleInput,
                                modifier = Modifier
                                    .size(1.dp)
                                    .graphicsLayer { alpha = 0f }
                                    .focusRequester(focusRequester),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false
                                )
                            )
                        }
                    } else {
                        ResultCard(finished, difficulty)
                    }
                }
            }

            val finished = result
            if (finished == null) {
                SecondaryButton("Exit", onClick = onExit)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        text = "Try again",
                        onClick = {
                            saveOnce(finished)
                            attempt++
                        }
                    )
                    SecondaryButton(
                        text = "Main menu",
                        onClick = {
                            saveOnce(finished)
                            onExit()
                        }
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
    var menuMode by remember { mutableStateOf(MenuMode.Home) }
    var lastDifficulty by remember { mutableStateOf(Difficulties.Easy) }
    var spinBoost by remember { mutableStateOf(false) }

    (screen as? Screen.Test)?.let { lastDifficulty = it.difficulty }

    val records = remember { mutableStateListOf<TestRecord>().apply { addAll(loadRecords()) } }
    val bestScore = records.maxOfOrNull { it.score } ?: 0
    val averageAccuracy = if (records.isEmpty()) 0 else records.sumOf { it.accuracy } / records.size

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Box(Modifier.fillMaxSize().background(Background)) {
            CookieBackground(
                color = Accent.copy(alpha = 0.07f),
                boosted = spinBoost,
                onBoostFinished = { spinBoost = false }
            )

            val menuVisible = screen == Screen.Menu
            val menuOpacity by animateFloatAsState(
                targetValue = if (menuVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 400),
                label = "menuOpacity"
            )

            MenuScreen(
                mode = menuMode,
                records = records,
                onStartTest = {
                    menuMode = MenuMode.PickDifficulty
                    spinBoost = true
                },
                onHistory = {
                    menuMode = MenuMode.History
                    spinBoost = true
                },
                onBack = {
                    menuMode = MenuMode.Home
                    spinBoost = true
                },
                onDifficultySelect = { difficulty ->
                    spinBoost = true
                    menuMode = MenuMode.Home
                    screen = Screen.Test(difficulty)
                },
                onDelete = { record ->
                    records.removeAll { it.id == record.id }
                    saveRecords(records)
                },
                modifier = Modifier.zIndex(if (menuVisible) 1f else 0f),
                drawAlpha = menuOpacity,
                bestScore = bestScore.toString(),
                accuracy = "$averageAccuracy%",
                testsTaken = records.size.toString()
            )

            TestScreen(
                difficulty = lastDifficulty,
                bestScore = bestScore,
                active = screen is Screen.Test,
                onSave = { record ->
                    var unique = record
                    while (records.any { it.id == unique.id }) unique = unique.copy(id = unique.id + 1)
                    records.add(unique)
                    saveRecords(records)
                },
                onExit = {
                    menuMode = MenuMode.Home
                    screen = Screen.Menu
                },
                modifier = Modifier.zIndex(if (screen is Screen.Test) 1f else 0f),
                drawAlpha = 1f - menuOpacity
            )
        }
    }
}