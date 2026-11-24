package io.keyflow.remotekeyboard.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.keyflow.remotekeyboard.keyboard.FocusToken
import io.keyflow.remotekeyboard.keyboard.InputMailbox
import io.keyflow.remotekeyboard.keyboard.KeyboardInput
import io.keyflow.remotekeyboard.pairing.AndroidTrust
import io.keyflow.remotekeyboard.pairing.AndroidTrustStore
import io.keyflow.remotekeyboard.protocol.FrameCodec
import io.keyflow.remotekeyboard.protocol.WireMessage
import io.keyflow.remotekeyboard.session.SessionAuthorization
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal enum class LanStatus {
    SELECT_WIFI, UNTRUSTED_WIFI, PAIR_REQUIRED, CONNECTING, CONNECTED, NO_LOCAL_ADDRESS, LOCAL_ACCESS_BLOCKED
}

/** Foreground connection lifecycle. A new link always creates a new session. */
internal class LanClient(
    context: Context,
    private val store: AndroidTrustStore = AndroidTrustStore(context),
    private val changed: (LanStatus) -> Unit,
    private val onFocus: (WireMessage.Focus) -> String? = { null },
    private val focusChanged: (FocusToken?, ByteArray?) -> Unit = { _, _ -> }
) : Closeable {
    private val state = Any()
    private val worker = executor("keyflow-connect")
    private val inputWriter = executor("keyflow-input")
    @Volatile private var outbound: Output? = null
    private val timers = executor("keyflow-session-timers", threads = 2)
    private val discovery = LanDiscovery(context.applicationContext)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val trust = AndroidTrust(store)

    private var selection: WifiIdentity? = null
    private var link: WifiLink? = null
    private var manual: Inet4Address? = null
    private var task: ScheduledFuture<*>? = null
    private var socket: Socket? = null
    private var generation = 0L
    private var failures = 0
    private var closed = false
    private var pendingCode: CharArray? = null

    fun configure(trusted: WifiIdentity?, current: WifiLink?, address: Inet4Address?) = synchronized(state) {
        if (closed || (selection == trusted && link == current && manual == address)) return@synchronized
        stopLocked()
        selection = trusted
        link = current
        manual = address
        failures = 0
        startLocked()
    }

    fun send(target: FocusToken, batch: List<KeyboardInput>): Boolean =
        outbound?.offer(target, batch) ?: false

    fun release(target: FocusToken) {
        outbound?.release(target)
    }

    private class Output(
        private val mailbox: InputMailbox,
        private val notify: (InputMailbox.Result) -> Boolean
    ) {
        fun offer(target: FocusToken, batch: List<KeyboardInput>): Boolean = notify(mailbox.offer(target, batch))
        fun release(target: FocusToken) { notify(mailbox.release(target)) }
        fun close() = mailbox.close()
    }

    fun select(trusted: WifiIdentity?, address: Inet4Address?) = synchronized(state) { configure(trusted, link, address) }

    fun updateLink(current: WifiLink?) = synchronized(state) { configure(selection, current, manual) }

    fun pair(code: CharArray) = synchronized(state) {
        require(code.size == 6 && code.all { it in '0'..'9' })
        if (closed) return@synchronized
        stopLocked()
        pendingCode = code.copyOf()
        startLocked()
    }

    fun revoke() = synchronized(state) {
        if (closed) return@synchronized
        stopLocked()
        trust.revoke()
        startLocked()
    }

    private fun startLocked() {
        val selected = selection
        if (selected == null) {
            pendingCode?.fill(0.toChar())
            pendingCode = null
            changed(LanStatus.SELECT_WIFI)
            return
        }
        val current = link
        if (current == null || current.identity != selected) {
            pendingCode?.fill(0.toChar())
            pendingCode = null
            changed(LanStatus.UNTRUSTED_WIFI)
            return
        }
        scheduleLocked(0)
    }

    private fun scheduleLocked(delay: Long) {
        val stamp = generation
        task = worker.schedule({ attempt(stamp) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun attempt(stamp: Long) {
        val current: WifiLink
        val address: Inet4Address?
        val code: CharArray?
        synchronized(state) {
            if (generation != stamp || closed) return
            current = link ?: return
            address = manual
            code = pendingCode
            pendingCode = null
        }
        var retry = code == null
        var established = false
        try {
            if (code == null && store.load()?.valid(java.time.Instant.now()) != true) {
                retry = false
                report(stamp, LanStatus.PAIR_REQUIRED)
                return
            }

            report(stamp, LanStatus.CONNECTING)
            val fallback = address?.takeIf(current::allows)
            val discovered = if (current.vpn || (code != null && fallback != null)) emptyList() else try {
                discovery.find(current).mapNotNull { it.address as? Inet4Address }.take(3)
            } catch (_: SecurityException) {
                // Discovery is optional. The explicit address still goes through link and TLS policy.
                emptyList()
            }
            val choices = (discovered + listOfNotNull(fallback)).distinct()
            if (choices.isEmpty()) {
                report(stamp, if (address == null && !current.vpn) LanStatus.NO_LOCAL_ADDRESS
                    else LanStatus.LOCAL_ACCESS_BLOCKED)
                return
            }

            for (candidate in choices) {
                val localSocket = Socket()
                localSocket.use { connectionSocket ->
                    synchronized(state) {
                        if (closed || stamp != generation) return
                        socket = connectionSocket
                    }
                    try {
                        val port = if (code == null) LanPorts.SESSION else LanPorts.PAIRING
                        if (!routeCurrent(current)) throw IOException("The default app route changed.")
                        current.connect(InetSocketAddress(candidate, port), connectionSocket)
                        if (!routeCurrent(current)) throw IOException("The default app route changed.")
                        val authenticated = if (code == null) trust.reconnect(connectionSocket, candidate.hostAddress!!)
                            else trust.pair(connectionSocket, candidate.hostAddress!!, code)
                        retry = true
                        authenticated.use { connection ->
                            runSession(connection, stamp, current) {
                                established = true
                                report(stamp, LanStatus.CONNECTED)
                            }
                        }
                    } catch (_: IOException) {
                        // The current authenticated session has ended if it had reached ready.
                    } catch (_: GeneralSecurityException) {
                        // The current authenticated session has ended if it had reached ready.
                    }
                }
                if (code != null || established || !isCurrent(stamp)) break
            }
            report(stamp, LanStatus.LOCAL_ACCESS_BLOCKED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: SecurityException) {
            report(stamp, LanStatus.LOCAL_ACCESS_BLOCKED)
        } catch (_: IOException) {
            report(stamp, LanStatus.LOCAL_ACCESS_BLOCKED)
        } catch (_: GeneralSecurityException) {
            report(stamp, LanStatus.PAIR_REQUIRED)
            retry = false
        } finally {
            code?.fill(0.toChar())
            synchronized(state) {
                if (stamp == generation && !closed) {
                    socket = null
                    if (retry) {
                        if (established) failures = 0
                        scheduleLocked(retryDelay(failures))
                        failures = minOf(failures + 1, 6)
                    }
                }
            }
        }
    }

    private fun runSession(
        connection: io.keyflow.remotekeyboard.pairing.TrustedConnection, stamp: Long, link: WifiLink,
        ready: () -> Unit
    ) {
        val tls = connection.socket
        tls.soTimeout = 35_000
        val input = DataInputStream(tls.inputStream)
        val output = DataOutputStream(tls.outputStream)
        val writing = Any()
        var sequence = 1L
        var watchdog: ScheduledFuture<*>? = null
        val mailbox = InputMailbox()

        fun armWatchdog() {
            watchdog?.cancel(false)
            watchdog = timers.schedule({ tls.close() }, 35, TimeUnit.SECONDS)
        }

        fun emit(build: (Long) -> WireMessage.Session?) = synchronized(writing) {
            if (!isCurrent(stamp) || !routeCurrent(link) || !trust.checkAuthorization(connection)) {
                tls.close()
                return@synchronized
            }
            val message = build(sequence) ?: return@synchronized
            FrameCodec.write(output, message)
            sequence++
            output.flush()
        }

        fun queue(result: InputMailbox.Result): Boolean {
            if (result == InputMailbox.Result.STALE) return false
            try {
                if (result == InputMailbox.Result.OVERFLOW) {
                    timers.execute { tls.close() }
                    return false
                }
                if (result == InputMailbox.Result.SCHEDULE) inputWriter.execute {
                    try {
                        while (true) {
                            val (target, batch, revision) = mailbox.next() ?: break
                            for (command in batch) {
                                if (!mailbox.accepts(target, revision)) break
                                emit { seq ->
                                    if (!mailbox.accepts(target, revision)) return@emit null
                                    when (command) {
                                        is KeyboardInput.Key -> WireMessage.Key(target.session, seq,
                                            target.generation, command.key.wire, command.down)
                                        is KeyboardInput.Text -> WireMessage.Text(target.session, seq,
                                            target.generation, command.value)
                                        KeyboardInput.Release -> WireMessage.Release(target.session, seq)
                                    }
                                }
                            }
                        }
                    } catch (_: IOException) {
                        tls.close()
                    } catch (_: GeneralSecurityException) {
                        tls.close()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return false // The service is closing.
            }
            return true
        }

        fun publishFocus(target: FocusToken?) {
            synchronized(state) {
                if (stamp != generation || closed) return
                mailbox.setFocus(target)
                focusChanged(target, if (target != null) connection.peer.pin.copyOf() else null)
            }
        }

        armWatchdog()
        var sendHeartbeat: ScheduledFuture<*>? = null
        try {
            if (FrameCodec.read(input) !is WireMessage.Hello) throw IOException("Missing desktop hello.")
            val focus = FrameCodec.read(input) as? WireMessage.Focus ?: throw IOException("Missing desktop session.")
            val session = SessionAuthorization(focus.id, connection.peer.localPairedAt, connection.peer.highWater)
            if (!session.tryAccept(focus) || !trust.checkAuthorization(connection) || !isCurrent(stamp) || !routeCurrent(link))
                throw IOException("Desktop authorization failed.")

            FrameCodec.write(output, WireMessage.Hello(3))
            output.flush()
            armWatchdog()
            ready()
            sendHeartbeat = timers.scheduleWithFixedDelay({
                try {
                    emit { WireMessage.Heartbeat(focus.id, it) }
                } catch (_: IOException) {
                    tls.close()
                } catch (_: GeneralSecurityException) {
                    tls.close()
                }
            }, 10, 10, TimeUnit.SECONDS)
            synchronized(state) {
                if (stamp != generation || closed) return
                outbound = Output(mailbox, ::queue)
            }
            var remoteFocus = focus
            var paused = false
            fun refreshFocus() {
                publishFocus(if (remoteFocus.active && !paused) FocusToken(remoteFocus.id, remoteFocus.generation) else null)
            }
            refreshFocus()
            deliverFocus(focus, ::emit)
            var focusGeneration = focus.generation
            while (isCurrent(stamp)) {
                val message = FrameCodec.read(input) as? WireMessage.Session ?: return
                if (!routeCurrent(link) || !session.tryAccept(message) || !trust.checkAuthorization(connection)) return
                when (message) {
                    is WireMessage.Focus -> {
                        if (message.generation <= focusGeneration) return
                        focusGeneration = message.generation
                        remoteFocus = message
                        refreshFocus()
                        if (!paused) deliverFocus(message, ::emit)
                    }
                    is WireMessage.Pause -> {
                        paused = message.paused
                        // A resume still requires a fresh focus generation.
                        remoteFocus = remoteFocus.copy(active = false)
                        refreshFocus()
                    }
                    is WireMessage.Error -> if (message.code != 1) {
                        remoteFocus = remoteFocus.copy(active = false)
                        refreshFocus()
                    }
                    is WireMessage.Heartbeat -> Unit
                    else -> return
                }
                armWatchdog()
            }
        } finally {
            mailbox.close()
            synchronized(state) {
                if (stamp == generation) {
                    outbound = null
                    focusChanged(null, null)
                }
            }
            sendHeartbeat?.cancel(false)
            watchdog?.cancel(false)
        }
    }

    private fun deliverFocus(
        focus: WireMessage.Focus, emit: ((Long) -> WireMessage.Session?) -> Unit
    ) {
        if (!focus.active) return
        val text = onFocus(focus) ?: return
        emit { WireMessage.Text(focus.id, it, focus.generation, text) }
    }

    private fun routeCurrent(link: WifiLink): Boolean {
        val active = connectivity.activeNetwork ?: return false
        if (active != link.defaultNetwork) return false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == link.vpn
    }

    private fun isCurrent(stamp: Long): Boolean = synchronized(state) { stamp == generation && !closed }

    private fun report(stamp: Long, status: LanStatus) {
        synchronized(state) { if (stamp == generation && !closed) changed(status) }
    }

    private fun stopLocked() {
        generation++
        outbound?.close()
        outbound = null
        focusChanged(null, null)
        socket?.close()
        socket = null
        task?.cancel(true)
        task = null
        pendingCode?.fill(0.toChar())
        pendingCode = null
    }

    override fun close() {
        synchronized(state) {
            if (closed) return
            stopLocked()
            closed = true
        }
        worker.shutdownNow()
        timers.shutdownNow()
        inputWriter.shutdownNow()
    }

    companion object {
        internal fun retryDelay(failures: Int, random: Random = Random.Default): Long {
            val ceiling = minOf(30_000L, 1000L shl failures.coerceIn(0, 5))
            return random.nextLong(ceiling / 2, ceiling + 1)
        }

        private fun executor(name: String, threads: Int = 1) = ScheduledThreadPoolExecutor(threads) { work ->
            Thread(work, name).apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
