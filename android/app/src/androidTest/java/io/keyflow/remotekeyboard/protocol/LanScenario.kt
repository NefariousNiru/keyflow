package io.keyflow.remotekeyboard.protocol

import android.content.Context
import android.os.Bundle
import io.keyflow.remotekeyboard.network.LanClient
import io.keyflow.remotekeyboard.network.LanDiscovery
import io.keyflow.remotekeyboard.network.LanStatus
import io.keyflow.remotekeyboard.network.WifiLink
import io.keyflow.remotekeyboard.network.WifiMonitor
import io.keyflow.remotekeyboard.network.parseManualAddress
import io.keyflow.remotekeyboard.pairing.AndroidTrustStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

internal object LanScenario {
    fun run(context: Context, options: Bundle, report: (String) -> Unit) {
        val mode = requireNotNull(options.getString("lanMode"))
        val namespace = requireNotNull(options.getString("trustNamespace"))
        require(Regex("keyflow-test-[0-9a-f]{16}").matches(namespace))
        val store = AndroidTrustStore(context, namespace)
        if (mode == "clear") {
            store.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$namespace.identity.v1")
            return
        }
        val available = CountDownLatch(1)
        val latest = AtomicReference<WifiLink?>()
        val listener = AtomicReference<((WifiLink?) -> Unit)?>(null)
        WifiMonitor(context) { link ->
            latest.set(link)
            if (link != null) available.countDown()
            listener.get()?.invoke(link)
        }.use {
            check(available.await(10, TimeUnit.SECONDS)) { "Trusted Wi-Fi metadata unavailable." }
            val current = requireNotNull(latest.get())
            val address = options.getString("lanAddress")?.let { requireNotNull(parseManualAddress(it)) }

            if (mode == "link-events") {
                val updates = LinkedBlockingQueue<Boolean>()
                listener.set { updates.add(it != null) }
                report("WIFI_READY")
                check(updates.poll(20, TimeUnit.SECONDS) == false) { "Wi-Fi loss callback was missing." }
                report("WIFI_LOST")
                check(updates.poll(30, TimeUnit.SECONDS) == true) { "Wi-Fi recovery callback was missing." }
                check(latest.get()?.identity == current.identity) { "The restored Wi-Fi has a different identity." }
                report("WIFI_RESTORED")
                return
            }

            if (mode == "public-blocked") {
                check(address != null && current.allows(address))
                Socket().use { connection ->
                    try {
                        current.connect(InetSocketAddress(address, 48732), connection)
                        error("The ineligible desktop unexpectedly accepted TCP.")
                    } catch (_: IOException) {
                        check(!connection.isConnected)
                    }
                }
                return
            }

            if (mode == "policy") {
                check(address != null && current.allows(address))
                check(parseManualAddress("8.8.8.8") == null)
                check(parseManualAddress("127.0.0.1") == null)
                val unrelated = requireNotNull(parseManualAddress("192.168.241.45"))
                check(!current.allows(unrelated))
                Socket().use { unopened ->
                    try {
                        current.connect(InetSocketAddress(unrelated, 48732), unopened)
                        error("Off-subnet endpoint was accepted")
                    } catch (_: IOException) {
                        check(!unopened.isConnected)
                    }
                }
                for (attempt in 0..10) {
                    val delay = LanClient.retryDelay(attempt, Random(attempt))
                    check(delay in 500..30_000)
                }
                val notices = LinkedBlockingQueue<LanStatus>()
                LanClient(context, store, changed = { notices.add(it) }).use { client ->
                    client.configure(current.identity.copy(bssid = "12:34:56:78:9a:bc"), current, address)
                    check(notices.poll(2, TimeUnit.SECONDS) == LanStatus.UNTRUSTED_WIFI)
                }
                return
            }

            if (mode == "discover") {
                check(LanDiscovery(context).find(current).any { it.address == address }) { "Desktop was not discovered." }
            }
            val notices = LinkedBlockingQueue<LanStatus>()
            LanClient(context, store, changed = { status ->
                notices.add(status)
                report(status.name)
            }, onFocus = { "m2-synthetic" }).use { client ->
                val manual = if (mode == "discover") null else address
                val identity = current.identity
                listener.set { refreshed -> client.configure(identity, refreshed, manual) }
                client.configure(identity, latest.get(), manual)
                if (mode == "pair") {
                    val code = requireNotNull(options.getString("lanOtp")).toCharArray()
                    try { client.pair(code) } finally { code.fill(0.toChar()) }
                }
                val original = if (mode == "pair") null else store.load()?.pairedAt
                awaitStatus(notices, if (mode == "blocked") LanStatus.LOCAL_ACCESS_BLOCKED else LanStatus.CONNECTED)
                if (mode == "recovery") {
                    awaitStatus(notices, LanStatus.UNTRUSTED_WIFI)
                    awaitStatus(notices, LanStatus.CONNECTED)
                }
                if (original != null) check(store.load()?.pairedAt == original) { "Reconnect renewed authorization." }
                if (mode != "blocked") Thread.sleep(500)
            }
        }
    }

    private fun awaitStatus(queue: LinkedBlockingQueue<LanStatus>, expected: LanStatus) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < end) {
            if (queue.poll(500, TimeUnit.MILLISECONDS) == expected) return
        }
        error("Missing LAN status $expected")
    }
}
