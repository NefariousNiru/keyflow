package io.keyflow.remotekeyboard

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Keeps Wi-Fi metadata access in the foreground during the physical device tests. */
class LanTestSurface : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.lan_test_surface) })
    }
}
