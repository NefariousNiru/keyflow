package io.keyflow.remotekeyboard.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object LanPorts {
    const val SESSION = 48732
    const val PAIRING = 48733
}

/** Bounded NSD lookup. The address is only a hint and still requires link and TLS checks. */
@Suppress("DEPRECATION")
internal class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun find(link: WifiLink): List<InetSocketAddress> {
        // Avoid explicitly choosing the underlying Wi-Fi route while the VPN is active.
        if (link.vpn) return emptyList()
        val done = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val result = AtomicReference<List<InetSocketAddress>>(emptyList())
        val multicast = wifi.createMulticastLock("Keyflow discovery").apply { setReferenceCounted(false) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) = done.countDown()
            override fun onStopDiscoveryFailed(type: String, code: Int) = done.countDown()
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = done.countDown()
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (stopped.get() || !info.serviceName.startsWith("Keyflow-") || !resolving.compareAndSet(false, true)) return
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, code: Int) { resolving.set(false) }
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses
                                else listOfNotNull(service.host)
                            if (service.port == LanPorts.SESSION &&
                                (Build.VERSION.SDK_INT < 33 || service.network == null || service.network == link.network)) {
                                val choices = addresses.filter(link::allows).map { InetSocketAddress(it, service.port) }
                                if (choices.isNotEmpty()) {
                                    result.set(choices)
                                    done.countDown()
                                }
                            }
                            resolving.set(false)
                        }
                    })
                } catch (_: IllegalArgumentException) {
                    resolving.set(false)
                } catch (_: SecurityException) {
                    done.countDown()
                }
            }
        }
        multicast.acquire()
        var started = false
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                nsd.discoverServices("_keyflow._tcp", NsdManager.PROTOCOL_DNS_SD, link.network,
                    Executor { it.run() }, listener)
            } else nsd.discoverServices("_keyflow._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            started = true
            done.await(5, TimeUnit.SECONDS)
            return result.get()
        } finally {
            stopped.set(true)
            try {
                if (started) nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // The service may already have stopped after a system callback.
            } finally {
                multicast.release()
            }
        }
    }
}
