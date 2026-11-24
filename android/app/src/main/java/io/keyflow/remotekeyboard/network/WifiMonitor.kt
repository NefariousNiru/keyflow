package io.keyflow.remotekeyboard.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal data class WifiIdentity(val ssid: String, val bssid: String, val gateway: String)

internal data class WifiLink(
    val identity: WifiIdentity,
    val network: Network,
    val local: Inet4Address,
    val prefixLength: Int,
    val defaultNetwork: Network,
    val vpn: Boolean
) {
    fun allows(address: InetAddress): Boolean {
        if (address !is Inet4Address || !address.privateV4() || address == local || prefixLength !in 8..30) return false
        val peer = address.numeric()
        val hostMask = (1L shl (32 - prefixLength)) - 1
        val host = peer and hostMask
        return (peer and hostMask.inv()) == (local.numeric() and hostMask.inv()) && host != 0L && host != hostMask
    }

    fun connect(endpoint: InetSocketAddress, socket: Socket) {
        if (!allows(endpoint.address)) throw IOException("Endpoint is outside trusted Wi-Fi.")
        // With an active VPN, use its normal app route. Binding Wi-Fi here would bypass it.
        if (!vpn) network.bindSocket(socket)
        socket.connect(endpoint, 5_000)
        if (socket.localAddress != local) throw IOException("Local route is blocked or uses another interface.")
        socket.tcpNoDelay = true
    }
}

internal class WifiPreferences(context: Context, name: String = "trusted-wifi") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): WifiIdentity? {
        val ssid = preferences.getString("ssid", null) ?: return null
        val bssid = preferences.getString("bssid", null) ?: return null
        val gateway = preferences.getString("gateway", null) ?: return null
        return WifiIdentity(ssid, bssid, gateway)
    }

    fun loadManual(): Inet4Address? = preferences.getString("manual", null)?.let(::parseManualAddress)

    fun saveManual(address: Inet4Address?) {
        val edit = preferences.edit()
        if (address == null) edit.remove("manual") else edit.putString("manual", address.hostAddress)
        check(edit.commit()) { "Could not save the desktop address." }
    }

    fun save(identity: WifiIdentity?) {
        val edit = preferences.edit().remove("ssid").remove("bssid").remove("gateway")
        if (identity != null) edit.putString("ssid", identity.ssid).putString("bssid", identity.bssid)
            .putString("gateway", identity.gateway)
        check(edit.commit()) { "Could not save the trusted Wi-Fi selection." }
    }
}

@Suppress("DEPRECATION")
internal class WifiMonitor(context: Context, private val changed: (WifiLink?) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val routes = mutableMapOf<Network, LinkProperties>()
    private val identities = mutableMapOf<Network, WifiInfo>()
    private val blocked = mutableSetOf<Network>()
    private var defaultNetwork: Network? = null
    private var defaultCapabilities: NetworkCapabilities? = null
    private var last: WifiLink? = null
    private var closed = false

    private val wifiCallback = if (Build.VERSION.SDK_INT >= 31)
        wifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) else wifiCallback(0)

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update {
            defaultNetwork = network
            defaultCapabilities = null
        }
        override fun onLost(network: Network) = update {
            if (defaultNetwork == network) {
                defaultNetwork = null
                defaultCapabilities = null
            }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update {
            if (defaultNetwork == network) defaultCapabilities = capabilities
        }
        override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) = update {
            if (defaultNetwork == network) setBlocked(network, isBlocked)
        }
    }

    init {
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), wifiCallback
        )
        try {
            connectivity.registerDefaultNetworkCallback(defaultCallback)
        } catch (error: RuntimeException) {
            connectivity.unregisterNetworkCallback(wifiCallback)
            throw error
        }
    }

    @Synchronized
    fun current(): WifiLink? = last

    private fun wifiCallback(flags: Int): ConnectivityManager.NetworkCallback {
        if (Build.VERSION.SDK_INT >= 31) return object : ConnectivityManager.NetworkCallback(flags) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                wifiCapabilities(network, capabilities.transportInfo as? WifiInfo)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = wifiLink(network, properties)
            override fun onLost(network: Network) = wifiLost(network)
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) =
                update { setBlocked(network, isBlocked) }
        }
        return object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                wifiCapabilities(network, wifi.connectionInfo)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = wifiLink(network, properties)
            override fun onLost(network: Network) = wifiLost(network)
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) =
                update { setBlocked(network, isBlocked) }
        }
    }

    private fun wifiCapabilities(network: Network, info: WifiInfo?) = update {
        if (info != null) identities[network] = info else identities.remove(network)
    }

    private fun wifiLink(network: Network, properties: LinkProperties) = update { routes[network] = properties }
    private fun wifiLost(network: Network) = update {
        routes.remove(network)
        identities.remove(network)
        blocked.remove(network)
    }

    private fun setBlocked(network: Network, isBlocked: Boolean) {
        if (isBlocked) blocked.add(network) else blocked.remove(network)
    }

    @Synchronized
    private fun update(change: () -> Unit) {
        if (closed) return
        change()
        val selected = snapshot()
        if (selected != last) {
            last = selected
            changed(selected)
        }
    }

    private fun snapshot(): WifiLink? {
        val active = defaultNetwork ?: return null
        val capabilities = defaultCapabilities ?: return null
        if (active in blocked || !hasPermissions(app)) return null
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return routes.mapNotNull { (network, properties) ->
            if (network in blocked || properties.interfaceName == null) return@mapNotNull null
            val info = identities[network] ?: return@mapNotNull null
            val ssid = info.ssid ?: return@mapNotNull null
            val bssid = info.bssid ?: return@mapNotNull null
            if (ssid == WifiManager.UNKNOWN_SSID || bssid == "02:00:00:00:00:00") return@mapNotNull null
            val local = properties.linkAddresses.filter { it.address.privateV4() && it.prefixLength in 8..30 }
                .singleOrNull() ?: return@mapNotNull null
            val gateway = properties.routes.firstOrNull { it.isDefaultRoute && it.gateway?.privateV4() == true }
                ?.gateway?.hostAddress ?: return@mapNotNull null
            WifiLink(WifiIdentity(ssid, bssid, gateway), network, local.address as Inet4Address,
                local.prefixLength, active, vpn)
        }.singleOrNull()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            last = null
        }
        connectivity.unregisterNetworkCallback(defaultCallback)
        connectivity.unregisterNetworkCallback(wifiCallback)
    }

    companion object {
        fun hasPermissions(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 37 ||
                    context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED)
    }
}

private fun Inet4Address.numeric(): Long =
    address.fold(0L) { number, byte -> (number shl 8) or (byte.toLong() and 255) }

internal fun InetAddress.privateV4(): Boolean {
    if (this !is Inet4Address) return false
    val first = address[0].toInt() and 255
    val second = address[1].toInt() and 255
    return first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168
}

internal fun parseManualAddress(value: String): Inet4Address? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
            (part.length > 1 && part[0] == '0')) return null
        bytes[index] = (part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte()
    }
    return (InetAddress.getByAddress(bytes) as Inet4Address).takeIf { it.privateV4() }
}
