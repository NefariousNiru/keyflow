package io.keyflow.remotekeyboard.keyboard

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.keyflow.remotekeyboard.MainActivity
import io.keyflow.remotekeyboard.R
import io.keyflow.remotekeyboard.network.LanClient
import io.keyflow.remotekeyboard.network.LanStatus
import io.keyflow.remotekeyboard.network.WifiLink
import io.keyflow.remotekeyboard.network.WifiMonitor
import io.keyflow.remotekeyboard.network.WifiPreferences
import io.keyflow.remotekeyboard.pairing.AndroidTrustStore
import java.security.GeneralSecurityException
import java.util.UUID

/** Process-local UI state. Input payloads never enter this object or a notification. */
internal object KeyboardRuntime {
    var service by mutableStateOf<KeyboardService?>(null)
    var status by mutableStateOf(LanStatus.SELECT_WIFI)
    var link by mutableStateOf<WifiLink?>(null)
    var focus by mutableStateOf<FocusToken?>(null)
    var peerPin by mutableStateOf<ByteArray?>(null)
    var launchFailed by mutableStateOf(false)
    var setupVisible = false
}

internal class KeyboardSettings(context: Context) {
    private val prefs = context.getSharedPreferences("keyboard-settings", Context.MODE_PRIVATE)
    var wake: Boolean
        get() = prefs.getBoolean("wake", false)
        set(value) { prefs.edit().putBoolean("wake", value).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }
}

open class KeyboardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val activation = ActivationPolicy()
    private lateinit var notifications: NotificationManager
    private lateinit var settings: KeyboardSettings
    private lateinit var companion: CompanionAssociation
    private var shown: FocusToken? = null
    private var client: LanClient? = null
    private var monitor: WifiMonitor? = null
    private var fallback: Runnable? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL,
            getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(FALLBACK_CHANNEL,
            getString(R.string.keyboard_channel), NotificationManager.IMPORTANCE_HIGH))
        settings = KeyboardSettings(this)
        companion = CompanionAssociation(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (client != null) return START_NOT_STICKY
        try {
            startForeground(ONGOING_ID, ongoingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            KeyboardRuntime.launchFailed = true
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            KeyboardRuntime.launchFailed = true
            stopSelf()
            return START_NOT_STICKY
        }

        val store = AndroidTrustStore(this, trustNamespace(intent))
        val controller = LanClient(this, store, changed = { status ->
            val pin = if (status == LanStatus.CONNECTED) readPin(store) else null
            handler.post {
                if (!stopping) {
                    KeyboardRuntime.status = status
                    KeyboardRuntime.peerPin = pin
                }
            }
        }, focusChanged = { token, pin ->
            handler.post {
                if (!stopping) showFocus(token, pin)
            }
        })
        client = controller
        KeyboardRuntime.service = this
        KeyboardRuntime.launchFailed = false
        reloadNetwork()
        monitor = WifiMonitor(this) { link ->
            controller.updateLink(link)
            handler.post { if (!stopping) KeyboardRuntime.link = link }
        }
        return START_NOT_STICKY
    }

    protected open fun trustNamespace(intent: Intent?): String = "keyflow"
    protected open fun preferenceName(): String = "trusted-wifi"

    internal fun reloadNetwork() {
        val preferences = WifiPreferences(this, preferenceName())
        client?.select(preferences.load(), preferences.loadManual())
    }

    internal fun pair(code: CharArray) = client?.pair(code)

    internal fun revoke() {
        client?.revoke()
        companion.remove()
    }

    internal fun send(token: FocusToken, batch: List<KeyboardInput>): Boolean =
        shown == token && activation.permits(token, manual = true) &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked && client?.send(token, batch) == true

    internal fun release(token: FocusToken) {
        client?.release(token)
    }

    internal fun dismiss(token: FocusToken) {
        if (!activation.dismiss(token)) return
        if (shown == token) shown = null
        release(token)
        hideNotification()
    }

    internal fun canPresent(token: FocusToken, manual: Boolean): Boolean = activation.permits(token, manual)

    internal fun presented(token: FocusToken) {
        if (activation.target != token) return
        shown = token
        hideNotification()
    }

    internal fun hidden(token: FocusToken) {
        if (shown == token) shown = null
        release(token)
    }

    internal fun openManual() {
        val token = activation.target ?: return
        startActivity(keyboardIntent(token, manual = true))
    }

    private fun showFocus(next: FocusToken?, pin: ByteArray?) {
        if (activation.target == next) return
        hideNotification()
        val visible = shown != null
        val auto = activation.update(next)
        KeyboardRuntime.focus = next
        if (!auto || next == null || visible) return

        val power = getSystemService(PowerManager::class.java)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val displayRestricted = !power.isInteractive || locked
        val eligible = KeyboardRuntime.setupVisible || companion.matches(pin)
        if (eligible && (!displayRestricted || settings.wake)) {
            try {
                startActivity(keyboardIntent(next, manual = false).putExtra(EXTRA_WAKE, settings.wake && displayRestricted))
            } catch (_: SecurityException) {
                postFallback(next)
                return
            } catch (_: IllegalStateException) {
                postFallback(next)
                return
            }
            // Android can silently reject a background launch. The activity acknowledges visibility.
            fallback = Runnable { postFallback(next) }.also { handler.postDelayed(it, 850) }
        } else {
            postFallback(next)
        }
    }

    private fun postFallback(token: FocusToken) {
        if (!activation.permits(token, manual = false) || shown == token || !notifications.areNotificationsEnabled()) return
        val tap = PendingIntent.getActivity(this, FALLBACK_ID, keyboardIntent(token, manual = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        notifications.notify(FALLBACK_ID, Notification.Builder(this, FALLBACK_CHANNEL)
            .setSmallIcon(R.drawable.keyflow_glyph)
            .setContentTitle(getString(R.string.keyboard_ready))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build())
    }

    private fun ongoingNotification(): Notification {
        val setup = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val manual = PendingIntent.getActivity(this, ONGOING_ID,
            Intent(this, KeyboardActivity::class.java).putExtra(EXTRA_MANUAL, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.keyflow_glyph)
            .setContentTitle(getString(R.string.service_running))
            .setContentIntent(setup)
            .addAction(Notification.Action.Builder(null, getString(R.string.keyboard_open), manual).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.service_stop), stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .build()
    }

    private fun keyboardIntent(token: FocusToken, manual: Boolean) =
        Intent(this, KeyboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SESSION, token.session.toString()).putExtra(EXTRA_GENERATION, token.generation)
            .putExtra(EXTRA_MANUAL, manual)

    private fun hideNotification() {
        fallback?.let(handler::removeCallbacks)
        fallback = null
        notifications.cancel(FALLBACK_ID)
    }

    private fun readPin(store: AndroidTrustStore): ByteArray? = try {
        store.load()?.pin?.copyOf()
    } catch (_: java.io.IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    }

    override fun onDestroy() {
        stopping = true
        hideNotification()
        handler.removeCallbacksAndMessages(null)
        monitor?.close()
        client?.close()
        KeyboardRuntime.link = null
        KeyboardRuntime.focus = null
        KeyboardRuntime.peerPin = null
        KeyboardRuntime.status = LanStatus.SELECT_WIFI
        if (KeyboardRuntime.service === this) KeyboardRuntime.service = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "io.keyflow.remotekeyboard.STOP"
        private const val ONGOING_CHANNEL = "keyflow-connected"
        private const val FALLBACK_CHANNEL = "keyflow-keyboard"
        private const val ONGOING_ID = 10
        private const val FALLBACK_ID = 11
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_GENERATION = "generation"
        internal const val EXTRA_MANUAL = "manual"
        internal const val EXTRA_WAKE = "wake"

        internal fun requestedToken(intent: Intent): FocusToken? {
            val id = intent.getStringExtra(EXTRA_SESSION) ?: return null
            return try {
                FocusToken(UUID.fromString(id), intent.getLongExtra(EXTRA_GENERATION, 0))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
