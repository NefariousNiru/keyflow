package io.keyflow.remotekeyboard

import android.content.Intent
import io.keyflow.remotekeyboard.keyboard.KeyboardService

/** Uses isolated debug-only trust and Wi-Fi preferences through the real service implementation. */
class KeyboardTestService : KeyboardService() {
    private lateinit var namespace: String

    override fun trustNamespace(intent: Intent?): String {
        namespace = requireNotNull(intent?.getStringExtra("trustNamespace"))
        require(Regex("keyflow-test-[0-9a-f]{16}").matches(namespace))
        return namespace
    }

    override fun preferenceName(): String = "$namespace.wifi"
}
