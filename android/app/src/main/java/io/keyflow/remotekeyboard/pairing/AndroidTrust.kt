package io.keyflow.remotekeyboard.pairing

import android.os.SystemClock
import io.keyflow.remotekeyboard.transport.PinnedTls
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

internal data class TrustedPeer(
    val pin: ByteArray,
    val pairedAt: Instant,
    val localPairedAt: Instant,
    val highWater: Instant
) {
    fun valid(now: Instant): Boolean = highWater >= localPairedAt && now >= highWater &&
        Duration.between(localPairedAt, now) < TRUST_LIFETIME

    fun sameIdentity(other: TrustedPeer): Boolean = pairedAt == other.pairedAt &&
        localPairedAt == other.localPairedAt && MessageDigest.isEqual(pin, other.pin)
}

private val TRUST_LIFETIME = Duration.ofDays(30)

/** Pairing and reconnect run on an I/O thread. The caller supplies a selected-interface socket. */
internal class AndroidTrust(
    private val store: AndroidTrustStore,
    private val now: () -> Instant = Instant::now,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime
) {
    private val monitor = Any()
    private var revision = 0L
    private var live: TrustedConnection? = null

    fun revoke() {
        synchronized(monitor) {
            revision++
            live?.close()
            live = null
            store.clear()
        }
    }

    fun pair(socket: Socket, host: String, otp: CharArray): TrustedConnection =
        withDeadline(socket) {
            val (stamp, identity) = synchronized(monitor) { revision to store.identity() }
            val local = PairingIdentity.android(pin(identity.certificate.publicKey.encoded))
            val peer = PairingExchange.confirm(
                DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()), local, otp
            )
            val tls = PinnedTls.confirmPairingIdentity(socket, host, identity, peer)
            val issuedAt = grant(tls)
            val observedAt = Instant.ofEpochMilli(now().toEpochMilli())
            if (Duration.between(issuedAt, observedAt).abs() > Duration.ofMinutes(2)) invalid()
            val peerPin = pin(tls.session.peerCertificates.first().publicKey.encoded)
            val record = TrustedPeer(peerPin, issuedAt, observedAt, observedAt)
            val connection = synchronized(monitor) {
                val previous = store.load()
                if (stamp != revision || (previous != null && observedAt < previous.highWater)) invalid()
                install(tls, record)
            }
            try {
                tls.outputStream.write(1)
                tls.outputStream.flush()
                connection
            } catch (error: IOException) {
                synchronized(monitor) { if (live === connection) revoke() }
                throw error
            }
        }

    fun reconnect(socket: Socket, host: String): TrustedConnection =
        withDeadline(socket) {
            val (stamp, credentials, identity) = synchronized(monitor) {
                Triple(revision, requirePeer(), store.identity())
            }
            val tls = PinnedTls.authenticate(socket, host, identity, credentials.pin)
            if (grant(tls) != credentials.pairedAt) invalid()
            synchronized(monitor) {
                val current = requirePeer()
                val checkedAt = now()
                if (stamp != revision || !current.sameIdentity(credentials) || !current.valid(checkedAt)) invalid()
                install(tls, current.copy(highWater = checkedAt))
            }
        }

    fun checkAuthorization(connection: TrustedConnection): Boolean {
        synchronized(monitor) {
            if (connection !== live) return false
            val current = store.load()
            val checkedAt = now()
            val duration = elapsed() - connection.startedAt
            if (connection.socket.isClosed || current == null || !current.sameIdentity(connection.peer) ||
                !current.valid(checkedAt) || duration < 0 || Duration.ofMillis(duration) >= connection.remaining) {
                connection.close()
                live = null
                return false
            }
            if (checkedAt != current.highWater) store.save(current.copy(highWater = checkedAt))
            return true
        }
    }

    private fun install(tls: SSLSocket, peer: TrustedPeer): TrustedConnection {
        live?.close()
        live = null
        store.save(peer)
        revision++
        val remaining = Duration.between(now(), peer.localPairedAt.plus(TRUST_LIFETIME))
        val expiry = deadlines.schedule({ tls.close() }, maxOf(0, remaining.toNanos()), TimeUnit.NANOSECONDS)
        return TrustedConnection(tls, peer, elapsed(), remaining, expiry)
            .also { live = it }
    }

    private fun requirePeer(): TrustedPeer = store.load()?.takeIf { it.valid(now()) } ?: invalid()

    private fun grant(tls: SSLSocket): Instant = Instant.ofEpochMilli(DataInputStream(tls.inputStream).readLong())

    private fun pin(spki: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(spki)

    private fun <T> withDeadline(socket: Socket, action: () -> T): T {
        val timer = deadlines.schedule({ socket.close() }, 30, TimeUnit.SECONDS)
        var completed = false
        try {
            val result = action()
            if (!timer.cancel(false)) invalid()
            completed = true
            return result
        } finally {
            timer.cancel(false)
            if (!completed) socket.close()
        }
    }

    private fun invalid(): Nothing = throw IOException("Keyflow trust verification failed.")

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { worker ->
            Thread(worker, "keyflow-handshake-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

internal class TrustedConnection(
    val socket: SSLSocket,
    val peer: TrustedPeer,
    val startedAt: Long,
    val remaining: Duration,
    private val expiry: ScheduledFuture<*>
) : Closeable {
    override fun close() {
        expiry.cancel(false)
        socket.close()
    }
}
