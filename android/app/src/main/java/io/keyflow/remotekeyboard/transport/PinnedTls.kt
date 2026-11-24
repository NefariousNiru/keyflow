package io.keyflow.remotekeyboard.transport

import io.keyflow.remotekeyboard.pairing.PairingIdentity
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Authenticates the paired desktop over a socket already bound to the selected Android network. */
internal object PinnedTls {
    private val tls12 = setOf(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    )
    private val tls13 = setOf(
        "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"
    )

    /**
     * Performs a blocking handshake on an I/O thread. The caller must close the socket on
     * cancellation or an overall deadline and supply a key manager for the paired Android identity.
     */
    fun authenticate(socket: Socket, host: String, keyManager: KeyManager, peerPin: ByteArray): SSLSocket {
        require(peerPin.size == 32)
        val pin = peerPin.copyOf()
        return authenticateCore(socket, host, keyManager) { MessageDigest.isEqual(pin, it) }
    }

    internal fun confirmPairingIdentity(socket: Socket, host: String, keyManager: KeyManager, peer: PairingIdentity): SSLSocket =
        authenticateCore(socket, host, keyManager, peer::matches)

    private fun authenticateCore(socket: Socket, host: String, keyManager: KeyManager, verifyPin: (ByteArray) -> Boolean): SSLSocket {
        require(host.isNotBlank())
        var connection: SSLSocket? = null
        var authenticated = false
        try {
            val trust = PeerTrust(verifyPin)
            val context = SSLContext.getInstance("TLS")
            context.init(arrayOf(keyManager), arrayOf(trust), null)
            val tls = context.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
            connection = tls
            tls.useClientMode = true
            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            tls.enabledCipherSuites = tls.supportedCipherSuites.filter { it in tls12 || it in tls13 }.toTypedArray()
            val previousTimeout = tls.soTimeout
            tls.soTimeout = 10_000
            tls.startHandshake()

            val session = tls.session
            val allowed = when (session.protocol) {
                "TLSv1.2" -> session.cipherSuite in tls12
                "TLSv1.3" -> session.cipherSuite in tls13
                else -> false
            }
            val chain = session.peerCertificates
            val peer = chain.firstOrNull() as? X509Certificate
            if (!allowed || session.localCertificates.isNullOrEmpty() || peer == null) {
                throw SSLHandshakeException("Keyflow TLS policy rejected the connection.")
            }
            trust.verify(peer)
            tls.soTimeout = previousTimeout
            authenticated = true
            return tls
        } finally {
            if (!authenticated) (connection ?: socket).close()
        }
    }

    private class PeerTrust(private val verifyPin: (ByteArray) -> Boolean) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Keyflow Android accepts a server identity only.")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verify(chain?.firstOrNull() ?: throw CertificateException("Missing Keyflow peer certificate."))
        }

        fun verify(peer: X509Certificate) {
            peer.checkValidity()
            val actual = MessageDigest.getInstance("SHA-256").digest(peer.publicKey.encoded)
            if (!verifyPin(actual)) {
                throw CertificateException("Keyflow peer identity does not match the pairing pin.")
            }
        }
    }
}
