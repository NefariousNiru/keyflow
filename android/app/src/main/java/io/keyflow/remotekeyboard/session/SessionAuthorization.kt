package io.keyflow.remotekeyboard.session

import android.os.SystemClock
import io.keyflow.remotekeyboard.protocol.WireMessage
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Authorization for one authenticated connection, confined to the connection's serial loop. */
internal class SessionAuthorization(
    private val id: UUID,
    pairedAt: Instant,
    persistedHighWater: Instant,
    private val utcNow: () -> Instant = Instant::now,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private val expiresAt = pairedAt.plus(Duration.ofDays(30))
    private val startedAt = monotonicMillis()
    private val observedAtStart = utcNow()
    private val remainingAtStart = Duration.between(observedAtStart, expiresAt)
    private var lastSequence = 0L
    private var revoked = false

    /** Persist this value with the paired credentials, outside the session loop. */
    var lastObservedUtc: Instant = maxOf(pairedAt, persistedHighWater)
        private set

    init {
        require(id != UUID(0, 0))
        revoked = observedAtStart < lastObservedUtc || observedAtStart >= expiresAt
        if (!revoked) lastObservedUtc = observedAtStart
    }

    fun revoke() {
        revoked = true
    }

    fun isAuthorized(): Boolean {
        if (revoked) return false
        val now = utcNow()
        val elapsed = monotonicMillis() - startedAt
        if (now < lastObservedUtc || now >= expiresAt || elapsed < 0 ||
            Duration.ofMillis(elapsed) >= remainingAtStart
        ) {
            revoke()
            return false
        }
        lastObservedUtc = now
        return true
    }

    /** A false result requires the caller to disconnect and clear all session input. */
    fun tryAccept(message: WireMessage.Session): Boolean {
        if (!isAuthorized()) return false
        if (message.id != id || lastSequence == Long.MAX_VALUE || message.seq != lastSequence + 1) {
            revoke()
            return false
        }
        lastSequence = message.seq
        return true
    }
}
