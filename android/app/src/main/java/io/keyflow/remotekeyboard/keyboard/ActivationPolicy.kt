package io.keyflow.remotekeyboard.keyboard

/** A visibility request is tied to one authenticated focus event, including across dismissal. */
internal class ActivationPolicy {
    var target: FocusToken? = null
        private set
    private var dismissed: FocusToken? = null

    fun update(next: FocusToken?): Boolean {
        if (target == next) return false
        target = next
        return next != null && dismissed != next
    }

    fun dismiss(token: FocusToken): Boolean {
        if (target != token) return false
        dismissed = token
        return true
    }

    fun permits(token: FocusToken, manual: Boolean): Boolean = target == token && (manual || dismissed != token)
}
