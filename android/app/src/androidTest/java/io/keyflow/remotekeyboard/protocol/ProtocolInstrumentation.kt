package io.keyflow.remotekeyboard.protocol

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.keyflow.remotekeyboard.session.SessionAuthorization
import java.time.Duration
import java.time.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

class ProtocolInstrumentation : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        try {
            if (options.containsKey("trustMode")) {
                TrustScenario.run(targetContext, options) { port ->
                    sendStatus(1, Bundle().apply { putInt("trustPort", port) })
                }
            } else {
                checkProtocol()
                checkAuthorization()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Keyflow protocol checks passed\n") })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "Keyflow protocol checks failed: " + error.javaClass.simpleName + " " + error.message + " " + error.cause?.message + "\n")
            })
        }
    }

    private fun checkProtocol() {
        val id = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val messages = listOf(
            WireMessage.Hello(3), WireMessage.Focus(id, 1, 5, true),
            WireMessage.Key(id, 2, 5, 29, true), WireMessage.Text(id, 3, 5, "Keyflow é 🙂"),
            WireMessage.Release(id, 4), WireMessage.Heartbeat(id, 5),
            WireMessage.Pause(id, 6, true), WireMessage.Error(id, 7, 2)
        )
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        messages.forEach { FrameCodec.write(output, it) }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        messages.forEach { check(FrameCodec.read(input) == it) }
        check(FrameCodec.read(input) == null)

        val vector = """{"v":1,"type":"heartbeat","session":"12345678-1234-1234-1234-123456789abc","seq":1}"""
        check(FrameCodec.read(framed(vector.encodeToByteArray())) == WireMessage.Heartbeat(id, 1))
        reject(framed("""{"v":2,"type":"hello","capabilities":1}""".encodeToByteArray()))
        reject(framed("""{"v":1,"type":"hello","capabilities":4}""".encodeToByteArray()))
        reject(framed("""{"v":1,"type":"hello","capabilities":1,"v":1}""".encodeToByteArray()))
        reject(framed("""{"v":1,"type":"hello","capabilities":1,"extra":0}""".encodeToByteArray()))
        reject(framed("""{"v":1,"type":"hello","capabilities":1.0}""".encodeToByteArray()))
        reject(framed("""{"v":1,"type":"text","session":"12345678-1234-1234-1234-123456789abc","seq":1,"generation":1,"text":"\ud800"}""".encodeToByteArray()))
        reject(framed(byteArrayOf(-1)))
        reject(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))))
        reject(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 64, 1))))
        reject(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 123))))

        val rejectedOutput = ByteArrayOutputStream()
        try {
            FrameCodec.write(DataOutputStream(rejectedOutput), WireMessage.Text(id, 1, 1, "x".repeat(1025)))
            error("Oversized frame was accepted")
        } catch (_: IOException) {
            check(rejectedOutput.size() == 0)
        }
        check(!WireMessage.Text(id, 1, 1, "synthetic-payload").toString().contains("synthetic-payload"))
    }

    private fun checkAuthorization() {
        val id = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val paired = Instant.parse("2025-01-01T00:00:00Z")
        val lifetime = Duration.ofDays(30)
        var now = paired
        var timestamp = 0L
        fun session(last: Instant = paired) = SessionAuthorization(id, paired, last, { now }, { timestamp })

        var auth = session()
        check(auth.tryAccept(WireMessage.Heartbeat(id, 1)))
        check(!auth.tryAccept(WireMessage.Heartbeat(id, 1)))
        check(!auth.isAuthorized())
        auth = session()
        check(!auth.tryAccept(WireMessage.Heartbeat(id, 2)))
        auth = session()
        check(!auth.tryAccept(WireMessage.Heartbeat(UUID.randomUUID(), 1)))
        auth = session()
        auth.revoke()
        check(!auth.isAuthorized())
        auth = session()
        now = paired.plus(lifetime).minusNanos(1)
        check(auth.isAuthorized())
        now = now.plusNanos(1)
        check(!auth.isAuthorized())
        check(!session().isAuthorized())
        now = paired
        check(!session(now.plusSeconds(1)).isAuthorized())
        auth = session()
        now = paired.plusSeconds(30)
        check(auth.isAuthorized())
        now = paired.plusSeconds(10)
        check(!auth.isAuthorized())
        now = paired
        auth = session()
        timestamp = lifetime.toMillis()
        check(!auth.isAuthorized())
    }

    private fun framed(body: ByteArray): DataInputStream {
        val bytes = ByteArrayOutputStream()
        val writer = DataOutputStream(bytes)
        writer.writeInt(body.size)
        writer.write(body)
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun reject(input: DataInputStream) {
        try {
            FrameCodec.read(input)
        } catch (_: IOException) {
            return
        }
        error("Malformed frame was accepted")
    }
}
