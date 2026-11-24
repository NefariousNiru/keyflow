package io.keyflow.remotekeyboard.keyboard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.keyflow.remotekeyboard.KeyFlowTheme

class KeyboardActivity : ComponentActivity() {
    private var requested by mutableStateOf<FocusToken?>(null)
    private var attached: FocusToken? = null
    private var locked by mutableStateOf(false)
    private var resumed by mutableStateOf(false)
    private var registered = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateLockState()
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                attached?.let { KeyboardRuntime.service?.hidden(it) }
                attached = null
                locked = true
            } else if (resumed && !locked) {
                KeyboardRuntime.focus?.let(::present)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!acceptIntent(intent, savedInstanceState == null)) {
            finish()
            return
        }
        val settings = KeyboardSettings(this)
        setContent {
            KeyFlowTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                    val target = KeyboardRuntime.focus
                    BackHandler { dismiss() }
                    LaunchedEffect(target, resumed) {
                        if (target == null) finish()
                        else if (resumed) present(target)
                    }
                    if (target != null) {
                        KeyboardSurface(target, resumed && !locked, settings.haptics,
                            send = { KeyboardRuntime.service?.send(target, it) == true },
                            release = { KeyboardRuntime.service?.release(target) }, dismiss = ::dismiss)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (acceptIntent(intent, true)) setIntent(intent)
        else if (KeyboardRuntime.focus == null) finish()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateLockState()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screen, filter)
        registered = true
        KeyboardRuntime.focus?.let(::present)
    }

    override fun onPause() {
        resumed = false
        if (registered) {
            unregisterReceiver(screen)
            registered = false
        }
        attached?.let { KeyboardRuntime.service?.hidden(it) }
        attached = null
        super.onPause()
    }

    private fun acceptIntent(intent: Intent, allowWake: Boolean): Boolean {
        val service = KeyboardRuntime.service ?: return false
        val manual = intent.getBooleanExtra(KeyboardService.EXTRA_MANUAL, false)
        val explicit = KeyboardService.requestedToken(intent)
        val token = explicit ?: if (manual) KeyboardRuntime.focus else null
        if (token == null || !service.canPresent(token, manual)) return false

        requested = token
        val wake = !manual && intent.getBooleanExtra(KeyboardService.EXTRA_WAKE, false) && KeyboardSettings(this).wake
        setShowWhenLocked(wake)
        setTurnScreenOn(wake && allowWake)
        return true
    }

    private fun present(target: FocusToken) {
        val previous = attached
        if (previous != null && previous != target) KeyboardRuntime.service?.hidden(previous)
        if (requested != null) {
            KeyboardRuntime.service?.presented(target)
            attached = target
        }
    }

    private fun dismiss() {
        val token = attached ?: KeyboardRuntime.focus
        if (token != null) KeyboardRuntime.service?.dismiss(token)
        finish()
    }

    private fun updateLockState() {
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive
    }
}
