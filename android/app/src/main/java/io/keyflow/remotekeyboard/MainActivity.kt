package io.keyflow.remotekeyboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.keyflow.remotekeyboard.network.LanClient
import io.keyflow.remotekeyboard.network.LanStatus
import io.keyflow.remotekeyboard.network.WifiIdentity
import io.keyflow.remotekeyboard.network.WifiLink
import io.keyflow.remotekeyboard.network.WifiMonitor
import io.keyflow.remotekeyboard.network.WifiPreferences
import io.keyflow.remotekeyboard.network.parseManualAddress
import java.net.Inet4Address

class MainActivity : ComponentActivity() {
    private var monitor: WifiMonitor? = null
    @Volatile private var monitorGeneration = 0L
    private var client: LanClient? = null
    private lateinit var preferences: WifiPreferences
    private var selected by mutableStateOf<WifiIdentity?>(null)
    private var current by mutableStateOf<WifiLink?>(null)
    private var status by mutableStateOf(LanStatus.SELECT_WIFI)
    private var permission by mutableStateOf(false)
    private var enteredAddress by mutableStateOf("")
    private var manual: Inet4Address? = null
    private var code by mutableStateOf("")

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        restartMonitor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = WifiPreferences(this)
        selected = preferences.load()
        manual = preferences.loadManual()
        enteredAddress = manual?.hostAddress ?: ""
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        if (!permission) {
                            Text(stringResource(R.string.wifi_permissions_message))
                            Button(onClick = ::requestWifiPermissions) { Text(stringResource(R.string.wifi_permissions)) }
                        } else {
                            Text(stringResource(status.label()))
                            val connected = current?.identity
                            if (connected != null) Text(stringResource(R.string.wifi_current, connected.ssid.trim('"')))
                            if (connected != null && connected != selected) {
                                Button(onClick = { select(connected) }) { Text(stringResource(R.string.wifi_trust)) }
                            }
                            if (selected != null) {
                                TextButton(onClick = { select(null) }) { Text(stringResource(R.string.wifi_forget)) }
                            }

                            OutlinedTextField(enteredAddress, { enteredAddress = it.take(15) },
                                label = { Text(stringResource(R.string.lan_ip)) }, singleLine = true,
                                isError = enteredAddress.isNotBlank() && parseManualAddress(enteredAddress) == null)
                            TextButton(
                                enabled = enteredAddress.isBlank() || parseManualAddress(enteredAddress) != null,
                                onClick = {
                                    manual = parseManualAddress(enteredAddress)
                                    preferences.saveManual(manual)
                                    refresh()
                                }
                            ) { Text(stringResource(R.string.lan_apply)) }

                            OutlinedTextField(code, { code = it.filter { character -> character in '0'..'9' }.take(6) },
                                label = { Text(stringResource(R.string.pairing_code)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                visualTransformation = PasswordVisualTransformation())
                            Button(enabled = connected != null && connected == selected && code.length == 6,
                                onClick = {
                                    val password = code.toCharArray()
                                    code = ""
                                    try { client?.pair(password) } finally { password.fill(0.toChar()) }
                                }) { Text(stringResource(R.string.lan_pair)) }
                            TextButton(onClick = { client?.revoke() }) { Text(stringResource(R.string.lan_revoke)) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client = LanClient(this, changed = { value -> runOnUiThread { status = value } })
        restartMonitor()
    }

    override fun onStop() {
        monitorGeneration++
        monitor?.close()
        monitor = null
        current = null
        client?.close()
        client = null
        code = ""
        super.onStop()
    }

    private fun restartMonitor() {
        val stamp = ++monitorGeneration
        monitor?.close()
        permission = WifiMonitor.hasPermissions(this)
        current = null
        val controller = client
        controller?.configure(selected, null, manual)
        monitor = if (permission && controller != null) WifiMonitor(this) { link ->
            if (monitorGeneration == stamp) {
                controller.updateLink(link)
                runOnUiThread { if (monitorGeneration == stamp) current = link }
            }
        } else null
    }

    private fun requestWifiPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 37) needed.add("android.permission.ACCESS_LOCAL_NETWORK")
        permissions.launch(needed.toTypedArray())
    }

    private fun select(wifi: WifiIdentity?) {
        preferences.save(wifi)
        selected = wifi
        refresh()
    }

    private fun refresh() {
        client?.configure(selected, monitor?.current(), manual)
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
