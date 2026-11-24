package io.keyflow.remotekeyboard.protocol

import android.content.Context
import android.os.Bundle
import io.keyflow.remotekeyboard.pairing.AndroidTrust
import io.keyflow.remotekeyboard.pairing.AndroidTrustStore
import io.keyflow.remotekeyboard.pairing.PairingExchange
import io.keyflow.remotekeyboard.pairing.PairingIdentity
import io.keyflow.remotekeyboard.transport.PinnedTls
import java.security.KeyStore
import java.security.SecureRandom
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import javax.crypto.AEADBadTagException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant

/** Drives the product trust and framing code using a consented local ADB reverse connection. */
internal object TrustScenario {
    fun run(context: Context, args: Bundle, ready: (Int) -> Unit) {
        val mode = requireNotNull(args.getString("trustMode"))
        val namespace = requireNotNull(args.getString("trustNamespace"))
        require(Regex("keyflow-test-[0-9a-f]{16}").matches(namespace))
        val original = AndroidTrustStore(context, namespace)
        if (mode == "clear") {
            AndroidTrust(original).revoke()
            check(original.load() == null)
            AndroidTrustStore(context, "$namespace.other").clear()
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (scope in listOf(namespace, "$namespace.other")) {
                keystore.deleteEntry("$scope.identity.v1")
                keystore.deleteEntry("$scope.trust.v1")
            }
            return
        }
        if (mode == "corrupt-store") {
            val file = File(context.noBackupFilesDir, "$namespace.peer")
            val data = file.readBytes()
            try {
                val modified = data.copyOf()
                modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
                file.writeBytes(modified)
                try {
                    original.load()
                    error("Keystore AEAD accepted modified trust data.")
                } catch (_: AEADBadTagException) {
                    return
                }
            } finally {
                file.writeBytes(data)
            }
        }
        val store = if (mode == "wrong-identity") AndroidTrustStore(context, "$namespace.other").also {
            it.save(requireNotNull(original.load()))
        } else original
        val before = store.load()
        val clockOffset = when (mode) {
            "android-expired" -> Duration.ofDays(30)
            "android-rollback" -> Duration.ofMinutes(-1)
            else -> Duration.ZERO
        }
        val trust = AndroidTrust(store, { Instant.now().plus(clockOffset) })
        val expectedFailure = mode in setOf("wrong-code", "pair-wrong-key", "revoked", "expired", "rollback",
            "wrong-identity", "wrong-desktop", "android-expired", "android-rollback")
        val pairing = mode == "pair" || mode == "wrong-code"

        val connected = ServerSocket().use { listener ->
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            listener.soTimeout = 15_000
            ready(listener.localPort)
            listener.accept()
        }
        connected.use { socket ->
            socket.soTimeout = 10_000
            if (mode == "role-substitution" || mode == "malformed-pairing") {
                val input = DataInputStream(socket.getInputStream())
                val serverRound = input.readInt()
                check(serverRound in 1..8192)
                input.readFully(ByteArray(serverRound))
                val output = DataOutputStream(socket.getOutputStream())
                if (mode == "malformed-pairing") output.writeInt(8193)
                else {
                    val fields = ("keyflow-pair-v1.desktop." + "0".repeat(64) + "." + "0".repeat(64) +
                        ",1,1,1,1,1,1").toByteArray(Charsets.US_ASCII)
                    output.writeInt(fields.size)
                    output.write(fields)
                }
                output.flush()
                check(input.read() == -1)
                check(store.load()?.highWater == before?.highWater)
                return
            }
            val connection = try {
                if (mode == "pair-wrong-key" || mode == "no-ack") {
                    val identity = store.identity()
                    val replacement = if (mode == "no-ack")
                        java.security.MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
                    else ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val otp = requireNotNull(args.getString("trustOtp")).toCharArray()
                    val peer = try {
                        PairingExchange.confirm(DataInputStream(socket.getInputStream()),
                            DataOutputStream(socket.getOutputStream()), PairingIdentity.android(replacement), otp)
                    } finally {
                        otp.fill(0.toChar())
                    }
                    PinnedTls.confirmPairingIdentity(socket, "localhost", identity, peer).use {
                        DataInputStream(it.inputStream).readLong()
                    }
                    if (mode == "no-ack") {
                        check(store.load() == null)
                        return
                    }
                    error("TLS accepted a key that did not match the confirmed commitment.")
                }
                if (pairing) {
                    val otp = requireNotNull(args.getString("trustOtp")).toCharArray()
                    try {
                        trust.pair(socket, "localhost", otp)
                    } finally {
                        otp.fill('\u0000')
                    }
                } else trust.reconnect(socket, "localhost")
            } catch (error: IOException) {
                if (!expectedFailure) throw error
                val remaining = store.load()
                check(if (before == null) remaining == null else remaining != null &&
                    before.sameIdentity(remaining) && before.highWater == remaining.highWater)
                return
            }
            connection.use {
                check(!expectedFailure)
                val input = DataInputStream(it.socket.inputStream)
                val output = DataOutputStream(it.socket.outputStream)
                check(FrameCodec.read(input) == WireMessage.Hello(2))
                val focus = FrameCodec.read(input) as WireMessage.Focus
                check(focus.active && focus.generation == 1L && focus.seq == 1L)
                check(trust.checkAuthorization(it))
                FrameCodec.write(output, WireMessage.Hello(2))
                FrameCodec.write(output, WireMessage.Text(focus.id, 1, focus.generation, "synthetic"))
                output.flush()
                check(FrameCodec.read(input) == WireMessage.Heartbeat(focus.id, 2))
                if (mode == "replay") {
                    FrameCodec.write(output, WireMessage.Text(focus.id, 1, focus.generation, "synthetic"))
                    output.flush()
                }
                if (mode == "revoke-active" || mode == "expire-active" || mode == "replay") {
                    try {
                        check(FrameCodec.read(input) == null)
                    } catch (_: javax.net.ssl.SSLException) {
                        // Abrupt TLS shutdown also invalidates the authenticated transport.
                    }
                }
            }
        }
    }
}
