package io.keyflow.remotekeyboard.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Layer(val label: String) { LETTERS("ABC"), SYMBOLS("123"), FUNCTIONS("Fn"), UNICODE("Unicode") }

private data class Face(val key: RemoteKey, val label: String = key.name, val weight: Float = 1f)
private fun letters(value: String) = value.map { Face(RemoteKey.valueOf(it.toString())) }
private val digits = (1..9).map { Face(RemoteKey.valueOf("Digit$it"), "$it") } + Face(RemoteKey.Digit0, "0")
private val functions = (1..12).chunked(6).map { row -> row.map { Face(RemoteKey.valueOf("F$it")) } }
private val navigation = listOf(
    listOf(Face(RemoteKey.Home), Face(RemoteKey.Up, "↑"), Face(RemoteKey.End), Face(RemoteKey.PageUp, "PgUp")),
    listOf(Face(RemoteKey.Left, "←"), Face(RemoteKey.Down, "↓"), Face(RemoteKey.Right, "→"), Face(RemoteKey.PageDown, "PgDn")),
    listOf(Face(RemoteKey.Escape, "Esc"), Face(RemoteKey.Tab), Face(RemoteKey.Delete, "Del"), Face(RemoteKey.Enter, "↵"))
)
private val footer = listOf(Face(RemoteKey.Comma, ","), Face(RemoteKey.Space, "Space", 4f),
    Face(RemoteKey.Period, "."), Face(RemoteKey.Enter, "↵", 1.5f))
private val repeatable = setOf(RemoteKey.Backspace, RemoteKey.Delete, RemoteKey.Left,
    RemoteKey.Right, RemoteKey.Up, RemoteKey.Down, RemoteKey.PageUp, RemoteKey.PageDown, RemoteKey.Space)

@Composable
internal fun KeyboardSurface(
    target: FocusToken, enabled: Boolean, haptics: Boolean,
    send: (List<KeyboardInput>) -> Boolean, release: () -> Unit, dismiss: () -> Unit
) {
    val state = remember(target) { KeyboardState() }
    var layer by remember(target) { mutableStateOf(Layer.LETTERS) }
    var revision by remember(target) { mutableIntStateOf(0) }
    var unicode by remember(target) { mutableStateOf("") }
    val view = LocalView.current
    DisposableEffect(target, enabled) {
        onDispose {
            unicode = ""
            state.clear()
            if (enabled) release()
        }
    }

    fun submit(batch: List<KeyboardInput>, feedback: Boolean = true): Boolean {
        val accepted = enabled && batch.isNotEmpty() && send(batch)
        if (accepted && haptics && feedback) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return accepted
    }

    @Composable
    fun RowKeys(keys: List<Face>) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (face in keys) {
                var heldBatch by remember(target, face.key) { mutableStateOf<List<KeyboardInput>>(emptyList()) }
                KeyTile(face.label, face.key.name, enabled, false, Modifier.weight(face.weight),
                    press = {
                        val batch = state.press(face.key)
                        heldBatch = if (submit(batch)) batch else emptyList()
                        revision++
                    },
                    repeat = if (face.key in repeatable) ({ if (!submit(heldBatch, false)) heldBatch = emptyList() }) else null,
                    end = { heldBatch = emptyList() })
            }
        }
    }

    @Composable
    fun Modifiers() {
        val observed = revision
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (key in KeyboardState.MODIFIERS) {
                val mode = remember(observed, state, key) { state.mode(key) }
                val label = if (key == RemoteKey.Control) "Ctrl" else key.name
                KeyTile(if (mode == ModifierMode.LOCKED) "$label •" else label,
                    "$label " + mode.name.lowercase(), enabled, mode != ModifierMode.OFF, Modifier.weight(1f),
                    press = {
                        state.beginModifier(key)
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    },
                    end = { completed ->
                        state.endModifier(key, completed)
                        revision++
                    })
            }
        }
    }

    @Composable
    fun Shortcuts() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val options = listOf(
                Triple("Copy", RemoteKey.C, RemoteKey.Control), Triple("Paste", RemoteKey.V, RemoteKey.Control),
                Triple("Undo", RemoteKey.Z, RemoteKey.Control), Triple("Switch", RemoteKey.Tab, RemoteKey.Alt),
                Triple("Explorer", RemoteKey.E, RemoteKey.Win)
            )
            for ((label, key, modifier) in options) {
                KeyTile(label, label, enabled, false, Modifier.weight(1f),
                    press = { submit(state.shortcut(key, modifier)); revision++; Unit })
            }
        }
    }

    @Composable
    fun MainKeys() {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (layer) {
                Layer.LETTERS -> {
                    RowKeys(digits)
                    RowKeys(letters("QWERTYUIOP"))
                    RowKeys(letters("ASDFGHJKL"))
                    RowKeys(letters("ZXCVBNM") + Face(RemoteKey.Backspace, "⌫", 1.4f))
                    RowKeys(footer)
                }
                Layer.SYMBOLS -> {
                    RowKeys(digits)
                    RowKeys(listOf(Face(RemoteKey.Grave, "\u0060"), Face(RemoteKey.Minus, "-"), Face(RemoteKey.Equal, "="),
                        Face(RemoteKey.LeftBracket, "["), Face(RemoteKey.RightBracket, "]"), Face(RemoteKey.Backslash, "\\")))
                    RowKeys(listOf(Face(RemoteKey.Semicolon, ";"), Face(RemoteKey.Apostrophe, "'"),
                        Face(RemoteKey.Slash, "/"), Face(RemoteKey.Backspace, "⌫")))
                    RowKeys(footer)
                }
                Layer.FUNCTIONS -> functions.forEach { RowKeys(it) }
                Layer.UNICODE -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (sample in listOf("é", "ñ", "Ω", "—", "🙂")) {
                            KeyTile(sample, sample, enabled, false, Modifier.weight(1f),
                                press = { submit(listOf(KeyboardInput.Text(sample))); Unit })
                        }
                    }
                    val scalar = unicodeScalar(unicode)
                    Text(if (unicode.isEmpty()) "Unicode code point" else "U+$unicode  ${scalar.orEmpty()}",
                        style = MaterialTheme.typography.labelLarge)
                    for (digits in listOf("01234567", "89ABCDEF")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (digit in digits) KeyTile(digit.toString(), "Hex $digit", enabled && unicode.length < 6,
                                false, Modifier.weight(1f), press = { unicode += digit })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = enabled && unicode.isNotEmpty(), onClick = { unicode = unicode.dropLast(1) }) {
                            Text("Erase digit")
                        }
                        Button(enabled = enabled && scalar != null, onClick = {
                            if (scalar != null && submit(listOf(KeyboardInput.Text(scalar)))) unicode = ""
                        }) { Text("Send character") }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 620.dp
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Layer.entries.forEach { option ->
                    FilterChip(selected = layer == option, onClick = { layer = option }, label = { Text(option.label) })
                }
                TextButton(onClick = dismiss) { Text("Dismiss") }
            }
            if (!enabled) Text("Unlock the phone to type", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Modifiers()
                        MainKeys()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        navigation.forEach { RowKeys(it) }
                        Shortcuts()
                    }
                }
            } else {
                Modifiers()
                MainKeys()
                if (layer == Layer.FUNCTIONS) navigation.forEach { RowKeys(it) }
                Shortcuts()
            }
        }
    }
}

@Composable
private fun KeyTile(
    label: String, description: String, enabled: Boolean, selected: Boolean, modifier: Modifier,
    press: () -> Unit, repeat: (() -> Unit)? = null, end: (Boolean) -> Unit = {}
) {
    val onPress by rememberUpdatedState(press)
    val onRepeat by rememberUpdatedState(repeat)
    val onEnd by rememberUpdatedState(end)
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.45f)
        pressed || selected -> colors.primaryContainer
        else -> colors.surfaceVariant.copy(alpha = 0.84f)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.heightIn(min = 42.dp).clip(shape).background(background)
        .border(1.dp, colors.outlineVariant.copy(alpha = 0.7f), shape)
        .semantics {
            role = Role.Button
            contentDescription = description
            if (!enabled) disabled() else onClick { onPress(); onEnd(true); true }
        }.pointerInput(enabled, label) {
            if (!enabled) return@pointerInput
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    onPress()
                    val repeating = if (onRepeat != null) launch {
                        delay(420)
                        while (true) {
                            onRepeat?.invoke()
                            delay(90)
                        }
                    } else null
                    var completed = false
                    try {
                        waitForUpOrCancellation()?.let { up ->
                            up.consume()
                            completed = true
                        }
                    } finally {
                        repeating?.cancel()
                        pressed = false
                        onEnd(completed)
                    }
                }
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
            color = if (selected || pressed) colors.onPrimaryContainer else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
