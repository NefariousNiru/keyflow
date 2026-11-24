package io.keyflow.remotekeyboard.keyboard

import java.util.UUID

/** The ordinal wire layout mirrors desktop CanonicalKey version 1. */
internal enum class RemoteKey {
    Escape, Tab, Enter, Backspace, Delete, Control, Alt, Shift, Win,
    Home, End, PageUp, PageDown, Left, Right, Up, Down,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    Space, Minus, Equal, LeftBracket, RightBracket, Backslash, Semicolon,
    Apostrophe, Grave, Comma, Period, Slash;

    val wire: Int get() = ordinal + 1
}

internal data class FocusToken(val session: UUID, val generation: Long)

internal sealed interface KeyboardInput {
    data class Key(val key: RemoteKey, val down: Boolean) : KeyboardInput
    data class Text(val value: String) : KeyboardInput {
        override fun toString(): String = "Text"
    }
    data object Release : KeyboardInput
}

internal enum class ModifierMode { OFF, NEXT, LOCKED }

internal class KeyboardState {
    private val modifiers = linkedMapOf<RemoteKey, ModifierMode>()
    private val pressed = linkedMapOf<RemoteKey, Boolean>()

    fun mode(key: RemoteKey): ModifierMode = modifiers[key] ?: ModifierMode.OFF

    fun toggle(key: RemoteKey) {
        require(key in MODIFIERS)
        modifiers[key] = when (mode(key)) {
            ModifierMode.OFF -> ModifierMode.NEXT
            ModifierMode.NEXT -> ModifierMode.LOCKED
            ModifierMode.LOCKED -> ModifierMode.OFF
        }
    }

    fun beginModifier(key: RemoteKey) {
        require(key in MODIFIERS)
        pressed[key] = false
    }

    fun endModifier(key: RemoteKey, completed: Boolean) {
        if (pressed.remove(key) == false && completed) toggle(key)
    }

    fun press(key: RemoteKey): List<KeyboardInput> {
        require(key !in MODIFIERS)
        val selected = MODIFIERS.filter { mode(it) != ModifierMode.OFF || it in pressed }
        pressed.replaceAll { _, _ -> true }
        val batch = chord(key, selected)
        modifiers.entries.removeAll { it.value == ModifierMode.NEXT }
        return batch
    }

    fun shortcut(key: RemoteKey, vararg held: RemoteKey): List<KeyboardInput> {
        require(key !in MODIFIERS && held.all { it in MODIFIERS })
        pressed.replaceAll { _, _ -> true }
        modifiers.entries.removeAll { it.value == ModifierMode.NEXT }
        return chord(key, held.distinct())
    }

    fun clear() {
        modifiers.clear()
        pressed.clear()
    }

    private fun chord(key: RemoteKey, held: List<RemoteKey>): List<KeyboardInput> =
        held.map { KeyboardInput.Key(it, true) } +
            listOf(KeyboardInput.Key(key, true), KeyboardInput.Key(key, false)) +
            held.asReversed().map { KeyboardInput.Key(it, false) }

    companion object {
        val MODIFIERS = listOf(RemoteKey.Control, RemoteKey.Alt, RemoteKey.Shift, RemoteKey.Win)
    }
}

internal fun unicodeScalar(hex: String): String? {
    val codePoint = hex.toIntOrNull(16) ?: return null
    if (!Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF ||
        Character.isISOControl(codePoint)) return null
    return String(Character.toChars(codePoint))
}
