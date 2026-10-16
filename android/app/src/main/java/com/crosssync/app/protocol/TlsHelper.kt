package com.crosssync.app.protocol

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for the connection to the Windows PC.
 *
 * The PC presents a self-signed certificate, so it can't be validated against a CA. Instead the
 * PC is identified by the SHA-256 fingerprint of its certificate: a pairing token is stored per
 * fingerprint and only ever sent to that same fingerprint, and an unknown fingerprint requires
 * the user to pair (entering the code shown on the PC, after comparing fingerprints).
 */
object TlsHelper {

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val fingerprintTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not used")
        }

        // Identity is checked by fingerprint after the handshake (see class docs)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("No server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(fingerprintTrustManager), SecureRandom())
        }.socketFactory
    }

    /** Layers TLS over an already-connected [socket] and completes the handshake. */
    fun wrap(socket: Socket, host: String, port: Int, handshakeTimeoutMs: Int): SSLSocket {
        val ssl = socketFactory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = handshakeTimeoutMs
        ssl.startHandshake()
        return ssl
    }

    /** Uppercase hex SHA-256 of the server's certificate (DER), matching Windows' GetCertHash. */
    fun fingerprint(ssl: SSLSocket): String {
        val cert = ssl.session.peerCertificates.first()
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
    }

    /** First 128 bits in groups of four, the same format the PC shows in Settings → Pairing. */
    fun formatFingerprint(fingerprint: String): String =
        fingerprint.take(32).chunked(4).joinToString(" ")
}
