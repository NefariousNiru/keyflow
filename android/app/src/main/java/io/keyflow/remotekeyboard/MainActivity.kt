package io.keyflow.remotekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.keyflow.remotekeyboard.keyboard.CompanionAssociation
import io.keyflow.remotekeyboard.keyboard.KeyboardRuntime
import io.keyflow.remotekeyboard.keyboard.KeyboardService
import io.keyflow.remotekeyboard.keyboard.KeyboardSettings
import io.keyflow.remotekeyboard.network.LanStatus
import io.keyflow.remotekeyboard.network.WifiIdentity
import io.keyflow.remotekeyboard.network.WifiLink
import io.keyflow.remotekeyboard.network.WifiMonitor
import io.keyflow.remotekeyboard.network.WifiPreferences
import io.keyflow.remotekeyboard.network.parseManualAddress

class MainActivity : ComponentActivity() {
    private var monitor: WifiMonitor? = null
    @Volatile private var monitorGeneration = 0L
    private lateinit var preferences: WifiPreferences
    private lateinit var settings: KeyboardSettings
    private lateinit var companion: CompanionAssociation
    private var selected by mutableStateOf<WifiIdentity?>(null)
    private var current by mutableStateOf<WifiLink?>(null)
    private var permission by mutableStateOf(false)
    private var notificationPermission by mutableStateOf(false)
    private var enteredAddress by mutableStateOf("")
    private var code by mutableStateOf("")
    private var wake by mutableStateOf(false)
    private var haptics by mutableStateOf(true)
    private var associationRevision by mutableStateOf(0)
    private var bluetoothName by mutableStateOf("")
    private var associationFailed by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        restartMonitor()
    }
    private val approval = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode != RESULT_OK) associationFailed = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = WifiPreferences(this)
        settings = KeyboardSettings(this)
        companion = CompanionAssociation(this)
        selected = preferences.load()
        enteredAddress = preferences.loadManual()?.hostAddress ?: ""
        wake = settings.wake
        haptics = settings.haptics
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        if (!permission) {
                            Text(stringResource(R.string.wifi_permissions_message))
                            Button(onClick = ::requestWifiPermissions) { Text(stringResource(R.string.wifi_permissions)) }
                        } else {
                            val service = KeyboardRuntime.service
                            Text(stringResource(if (KeyboardRuntime.launchFailed) R.string.service_failed
                                else if (service == null) R.string.service_stopped else KeyboardRuntime.status.label()))
                            val connected = (KeyboardRuntime.link ?: current)?.identity
                            if (connected != null) Text(stringResource(R.string.wifi_current, connected.ssid.trim('"')))
                            if (connected != null && connected != selected)
                                TextButton(onClick = { select(connected) }) { Text(stringResource(R.string.wifi_trust)) }
                            if (selected != null)
                                TextButton(onClick = { select(null) }) { Text(stringResource(R.string.wifi_forget)) }

                            OutlinedTextField(enteredAddress, {
                                enteredAddress = it.take(15)
                            }, label = { Text(stringResource(R.string.lan_ip)) }, singleLine = true,
                                isError = enteredAddress.isNotBlank() && parseManualAddress(enteredAddress) == null)
                            TextButton(enabled = enteredAddress.isBlank() || parseManualAddress(enteredAddress) != null,
                                onClick = {
                                    preferences.saveManual(parseManualAddress(enteredAddress))
                                    service?.reloadNetwork()
                                }) { Text(stringResource(R.string.lan_apply)) }

                            Button(enabled = connected != null && connected == selected || service != null,
                                onClick = {
                                    if (service == null) startConnection() else stopService(Intent(this@MainActivity, KeyboardService::class.java))
                                }) { Text(stringResource(if (service == null) R.string.service_start else R.string.service_stop)) }
                            if (service != null) {
                                OutlinedTextField(code, { code = it.filter { character -> character in '0'..'9' }.take(6) },
                                    label = { Text(stringResource(R.string.pairing_code)) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    visualTransformation = PasswordVisualTransformation())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = KeyboardRuntime.link?.identity != null && KeyboardRuntime.link?.identity == selected && code.length == 6,
                                        onClick = {
                                            val password = code.toCharArray()
                                            code = ""
                                            try { service.pair(password) } finally { password.fill(0.toChar()) }
                                        }) { Text(stringResource(R.string.lan_pair)) }
                                    TextButton(onClick = { service.revoke(); associationRevision++ }) {
                                        Text(stringResource(R.string.lan_revoke))
                                    }
                                }
                            }

                            if (KeyboardRuntime.focus != null && service != null)
                                Button(onClick = service::openManual) { Text(stringResource(R.string.keyboard_open)) }
                            if (Build.VERSION.SDK_INT >= 33 && !notificationPermission)
                                TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) {
                                    Text(stringResource(R.string.keyboard_notifications))
                                }
                            val pin = KeyboardRuntime.peerPin
                            val associated = androidx.compose.runtime.remember(pin, associationRevision) { companion.matches(pin) }
                            if (pin != null && companion.supported()) {
                                if (associated) Text(stringResource(R.string.keyboard_associated))
                                else {
                                    OutlinedTextField(bluetoothName, { bluetoothName = it.take(128) },
                                        label = { Text(stringResource(R.string.keyboard_bluetooth_name)) }, singleLine = true)
                                    TextButton(enabled = bluetoothName.isNotBlank(), onClick = { associate(pin) }) {
                                        Text(stringResource(R.string.keyboard_associate))
                                    }
                                }
                            }
                            if (associationFailed) Text(stringResource(R.string.keyboard_association_failed))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(wake, { wake = it; settings.wake = it })
                                Text(stringResource(R.string.keyboard_wake), Modifier.padding(top = 12.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(haptics, { haptics = it; settings.haptics = it })
                                Text(stringResource(R.string.keyboard_haptics), Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        restartMonitor()
    }

    override fun onResume() {
        super.onResume()
        KeyboardRuntime.setupVisible = true
    }

    override fun onPause() {
        KeyboardRuntime.setupVisible = false
        super.onPause()
    }

    override fun onStop() {
        monitorGeneration++
        monitor?.close()
        monitor = null
        current = null
        code = ""
        super.onStop()
    }

    private fun restartMonitor() {
        val stamp = ++monitorGeneration
        monitor?.close()
        permission = WifiMonitor.hasPermissions(this)
        notificationPermission = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        current = null
        monitor = if (permission) WifiMonitor(this) { link ->
            runOnUiThread { if (monitorGeneration == stamp) current = link }
        } else null
    }

    private fun startConnection() {
        try {
            startForegroundService(Intent(this, KeyboardService::class.java))
        } catch (_: IllegalStateException) {
            KeyboardRuntime.launchFailed = true
        } catch (_: SecurityException) {
            KeyboardRuntime.launchFailed = true
        }
    }

    private fun requestWifiPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 37) needed.add("android.permission.ACCESS_LOCAL_NETWORK")
        permissions.launch(needed.toTypedArray())
    }

    private fun select(wifi: WifiIdentity?) {
        preferences.save(wifi)
        selected = wifi
        KeyboardRuntime.service?.reloadNetwork()
    }

    private fun associate(pin: ByteArray) {
        associationFailed = false
        companion.request(pin, bluetoothName.trim(), { KeyboardRuntime.peerPin },
            askUser = { sender -> approval.launch(IntentSenderRequest.Builder(sender).build()) },
            completed = { ok -> associationFailed = !ok; associationRevision++ })
    }

    private fun LanStatus.label(): Int = when (this) {
        LanStatus.SELECT_WIFI -> R.string.lan_select
        LanStatus.UNTRUSTED_WIFI -> R.string.lan_untrusted
        LanStatus.PAIR_REQUIRED -> R.string.lan_pair_required
        LanStatus.CONNECTING -> R.string.lan_connecting
        LanStatus.CONNECTED -> R.string.lan_connected
        LanStatus.NO_LOCAL_ADDRESS -> R.string.lan_no_address
        LanStatus.LOCAL_ACCESS_BLOCKED -> R.string.lan_blocked
    }
}
