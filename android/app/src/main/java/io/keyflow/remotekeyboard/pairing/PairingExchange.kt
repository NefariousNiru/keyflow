package io.keyflow.remotekeyboard.pairing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload

internal class PairingIdentity private constructor(
    val id: String,
    private val nonce: ByteArray,
    private val commitment: ByteArray
) {
    fun matches(pin: ByteArray): Boolean =
        pin.size == 32 && MessageDigest.isEqual(commitment, commit(nonce, pin))

    companion object {
        private val pattern = Regex("keyflow-pair-v1\\.desktop\\.([0-9a-f]{64})\\.([0-9a-f]{64})")

        fun android(pin: ByteArray): PairingIdentity {
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val commitment = commit(nonce, pin)
            return PairingIdentity("keyflow-pair-v1.android.${nonce.toHex()}.${commitment.toHex()}",
                nonce, commitment)
        }

        fun desktop(id: String): PairingIdentity {
            val result = pattern.matchEntire(id) ?: throw IOException("Invalid pairing identity.")
            return PairingIdentity(id, readHex(result.groupValues[1]), readHex(result.groupValues[2]))
        }

        private fun commit(nonce: ByteArray, pin: ByteArray): ByteArray {
            require(pin.size == 32)
            return MessageDigest.getInstance("SHA-256").digest(nonce + pin)
        }

        private fun ByteArray.toHex(): String = joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        private fun readHex(value: String): ByteArray =
            ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** The caller closes the socket on timeout or cancellation of the handshake. */
internal object PairingExchange {
    private val positive = Regex("0|[1-9][0-9]{0,924}")
    private val signed = Regex("0|-?[1-9][0-9]{0,924}")
    private val group = JPAKEPrimeOrderGroups.NIST_3072

    fun confirm(input: DataInputStream, output: DataOutputStream, own: PairingIdentity, otp: CharArray): PairingIdentity {
        require(otp.size == 6 && otp.all { it in '0'..'9' })
        val password = otp.copyOf()
        try {
            val participant = JPAKEParticipant(own.id, password, group)
            val one = participant.createRound1PayloadToSend()
            send(output, one.participantId, one.gx1, one.gx2, *one.knowledgeProofForX1, *one.knowledgeProofForX2)
            var fields = receive(input, 7)
            val peer = PairingIdentity.desktop(fields[0])
            participant.validateRound1PayloadReceived(JPAKERound1Payload(
                peer.id, number(fields[1]), number(fields[2]), proof(fields, 3), proof(fields, 5)
            ))

            val two = participant.createRound2PayloadToSend()
            send(output, two.participantId, two.a, *two.knowledgeProofForX2s)
            fields = receive(input, 4)
            if (fields[0] != peer.id) invalid()
            participant.validateRound2PayloadReceived(JPAKERound2Payload(peer.id, number(fields[1]), proof(fields, 2)))

            val material = participant.calculateKeyingMaterial()
            val three = participant.createRound3PayloadToSend(material)
            send(output, three.participantId, three.macTag)
            fields = receive(input, 2)
            if (fields[0] != peer.id) invalid()
            participant.validateRound3PayloadReceived(JPAKERound3Payload(peer.id, number(fields[1], true)), material)
            return peer
        } catch (_: CryptoException) {
            invalid()
        } catch (_: ArithmeticException) {
            invalid()
        } finally {
            password.fill('\u0000')
        }
    }

    private fun proof(fields: List<String>, offset: Int) =
        arrayOf(number(fields[offset]), number(fields[offset + 1]))

    private fun number(value: String, allowSign: Boolean = false): BigInteger {
        if (!(if (allowSign) signed else positive).matches(value)) invalid()
        val number = BigInteger(value)
        if (!allowSign && number >= group.p) invalid()
        return number
    }

    private fun send(output: DataOutputStream, vararg values: Any) {
        val body = values.joinToString(",").toByteArray(Charsets.US_ASCII)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun receive(input: DataInputStream, count: Int): List<String> {
        val length = input.readInt()
        if (length !in 1..8192) invalid()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        if (bytes.any { it !in 32..126 }) invalid()
        return bytes.toString(Charsets.US_ASCII).split(',').also { if (it.size != count) invalid() }
    }

    private fun invalid(): Nothing = throw IOException("Pairing exchange rejected.")
}
