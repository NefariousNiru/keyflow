package io.keyflow.remotekeyboard.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Per-install identity key and peer-store key stay in Android Keystore. */
internal class AndroidTrustStore(context: Context, namespace: String = "keyflow") {
    private val identityAlias = "$namespace.identity.v1"
    private val dataAlias = "$namespace.trust.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val peerFile = AtomicFile(File(context.noBackupFilesDir, "$namespace.peer"))

    @Synchronized
    fun identity(): LocalIdentity {
        if (!store.containsAlias(identityAlias)) {
            val now = Instant.now()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(identityAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt supplies a digest to the Android Keystore ECDSA signing provider.
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=Keyflow Android"))
                    .setCertificateNotBefore(Date.from(now.minus(10, ChronoUnit.MINUTES)))
                    .setCertificateNotAfter(Date.from(now.plus(1825, ChronoUnit.DAYS)))
                    .build())
            }.generateKeyPair()
        }
        return LocalIdentity(store.getCertificate(identityAlias) as X509Certificate,
            store.getKey(identityAlias, null) as PrivateKey)
    }

    @Synchronized
    fun load(): TrustedPeer? {
        val encrypted = try {
            peerFile.openRead().use { input ->
                val bytes = ByteArray(84)
                java.io.DataInputStream(input).readFully(bytes)
                require(input.read() == -1)
                bytes
            }
        } catch (_: FileNotFoundException) {
            return null
        }
        val key = store.getKey(dataAlias, null) as? SecretKey ?: error("Missing protected trust key.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val body = cipher.doFinal(encrypted, 12, encrypted.size - 12)
        try {
            val values = ByteBuffer.wrap(body)
            val pin = ByteArray(32)
            values.get(pin)
            return TrustedPeer(pin, Instant.ofEpochMilli(values.long), Instant.ofEpochMilli(values.long),
                Instant.ofEpochMilli(values.long))
        } finally {
            body.fill(0)
        }
    }

    @Synchronized
    fun save(peer: TrustedPeer) {
        require(peer.pin.size == 32 && peer.highWater >= peer.localPairedAt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val body = ByteBuffer.allocate(56).put(peer.pin).putLong(peer.pairedAt.toEpochMilli())
            .putLong(peer.localPairedAt.toEpochMilli()).putLong(peer.highWater.toEpochMilli()).array()
        val encrypted = try {
            cipher.iv + cipher.doFinal(body)
        } finally {
            body.fill(0)
        }
        val output = peerFile.startWrite()
        try {
            output.write(encrypted)
            peerFile.finishWrite(output)
        } catch (error: Exception) {
            peerFile.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        // Deleting the key also invalidates an encrypted record left behind by a storage failure.
        store.deleteEntry(dataAlias)
        peerFile.delete()
        check(!peerFile.baseFile.exists()) { "Could not remove the protected trust record." }
    }

    private fun encryptionKey(): SecretKey {
        (store.getKey(dataAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").let { generator ->
            generator.init(KeyGenParameterSpec.Builder(dataAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generator.generateKey()
        }
    }
}

internal class LocalIdentity(val certificate: X509Certificate, private val privateKey: PrivateKey) :
    X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> =
        if (keyType == "EC") arrayOf("identity") else emptyArray()
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? =
        if (keyType?.contains("EC") == true) "identity" else null
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> = emptyArray()
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == "identity") arrayOf(certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "identity") privateKey else null
}
