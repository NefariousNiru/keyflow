package io.keyflow.remotekeyboard

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import java.io.File
import java.math.BigInteger
import kotlin.concurrent.thread
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload

/** Debug-only interop probe. ADB delivers synthetic round payloads through app-private files. */
class PairingProbeActivity : Activity() {
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.pairing_probe) })
        val otp = if (intent.getBooleanExtra("mismatch", false)) "384018" else "384017"
        worker = thread(name = "pairing-probe") {
            try {
                runExchange(otp)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: CryptoException) {
                write("result", "ERROR")
            } catch (_: IllegalStateException) {
                write("result", "ERROR")
            }
        }
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    private fun runExchange(otp: String) {
        val participant = JPAKEParticipant("android", otp.toCharArray(), JPAKEPrimeOrderGroups.NIST_3072)
        val first = participant.createRound1PayloadToSend()
        send(1, first.participantId, first.gx1, first.gx2,
            *first.knowledgeProofForX1, *first.knowledgeProofForX2)
        val peerFirst = receive(1, 7)
        participant.validateRound1PayloadReceived(JPAKERound1Payload(
            peerFirst[0], BigInteger(peerFirst[1]), BigInteger(peerFirst[2]),
            proof(peerFirst, 3), proof(peerFirst, 5)
        ))

        val second = participant.createRound2PayloadToSend()
        send(2, second.participantId, second.a, *second.knowledgeProofForX2s)
        val peerSecond = receive(2, 4)
        participant.validateRound2PayloadReceived(JPAKERound2Payload(
            peerSecond[0], BigInteger(peerSecond[1]), proof(peerSecond, 2)
        ))

        val material = participant.calculateKeyingMaterial()
        val third = participant.createRound3PayloadToSend(material)
        send(3, third.participantId, third.macTag)
        val peerThird = receive(3, 2)
        val result = try {
            participant.validateRound3PayloadReceived(JPAKERound3Payload(peerThird[0], BigInteger(peerThird[1])), material)
            "CONFIRMED"
        } catch (_: CryptoException) {
            "REJECTED"
        }
        write("result", result)
    }

    private fun send(round: Int, vararg values: Any) = write("out$round", values.joinToString(","))

    private fun receive(round: Int, count: Int): List<String> {
        val input = File(filesDir, "pairing-in$round")
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!input.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(input.isFile && input.length() in 1..8192) { "Missing or oversized test payload" }
        val fields = input.readText().trim().split(',')
        check(fields.size == count && fields[0] == "windows") { "Invalid test payload" }
        return fields
    }

    private fun proof(fields: List<String>, offset: Int) =
        arrayOf(BigInteger(fields[offset]), BigInteger(fields[offset + 1]))

    private fun write(name: String, value: String) {
        val temp = File(filesDir, "pairing-$name.tmp")
        temp.writeText(value)
        check(temp.renameTo(File(filesDir, "pairing-$name"))) { "Could not publish test result" }
    }
}
