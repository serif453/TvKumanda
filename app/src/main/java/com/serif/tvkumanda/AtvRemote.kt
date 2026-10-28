package com.serif.tvkumanda

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Standart bağlantı: Android TV Remote v2 (ADB gerektirmez).
 * Protokol ayrıntıları için [Polo] ve [RemoteMsg] (AtvProtocol.kt).
 *
 * Güvenlik:
 *  - İstemci anahtarı Android Keystore'da üretilir; özel anahtar cihazdan dışarı çıkamaz.
 *  - Eşleştirmede TV'nin sertifikası, ekranda gösterilen kodla birlikte hash'e girer;
 *    araya giren biri olursa kod tutmaz.
 *  - Kumanda kanalında TV sertifikası ilk başarılı bağlantıda sabitlenir (pin);
 *    sonradan değişirse yeniden eşleştirme istenir.
 */
class AtvRemote(context: Context) : RemoteLink {

    class PairingRequired(msg: String) : Exception(msg)
    class BadCode(msg: String) : Exception(msg)

    private val prefs = context.getSharedPreferences("atv", Context.MODE_PRIVATE)

    // Kumanda kanalı
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    private var host = ""
    @Volatile private var activeFeatures = RemoteMsg.FEATURES
    @Volatile private var imeCounter = 0
    @Volatile private var imeField = 0
    @Volatile private var volumeInfo: Pair<Int, Int>? = null
    @Volatile private var deviceName = ""

    /** TV ses seviyesini kendiliğinden bildirdiğinde çağrılır (değer, en yüksek). */
    @Volatile var onVolume: ((Int, Int) -> Unit)? = null
    /** Kumanda kanalı beklenmedik şekilde kapandığında çağrılır. */
    @Volatile var onDisconnected: (() -> Unit)? = null

    // Eşleştirme kanalı
    private var pairSocket: SSLSocket? = null
    private var pairIn: InputStream? = null
    private var pairOut: OutputStream? = null
    private var pairTrust: PinTrust? = null

    override val isFast: Boolean get() = false

    // ---------------- Kumanda kanalı ----------------

    @Synchronized
    override fun connectTo(ip: String): String {
        closeRemote()
        host = ip
        if (!prefs.getBoolean(pairedKey(ip), false)) throw PairingRequired("Eşleştirme gerekli")
        try {
            openRemote(ip)
        } catch (e: SSLException) {
            forget(ip)
            throw PairingRequired("TV bu cihazı tanımadı, yeniden eşleştirme gerekli")
        } catch (e: EOFException) {
            // Eşleşmemiş istemcide TV bağlantıyı el sıkışmadan hemen sonra kapatır
            forget(ip)
            throw PairingRequired("TV bağlantıyı reddetti, yeniden eşleştirme gerekli")
        }
        return deviceName.ifBlank { ip }
    }

    private fun openRemote(ip: String) {
        val pin = prefs.getString(pinKey(ip), null)
        val trust = PinTrust(pin)
        val s = tlsSocket(ip, PORT_REMOTE, trust)
        val inp = BufferedInputStream(s.inputStream)
        val o = BufferedOutputStream(s.outputStream)

        // El sıkışma: TV önce yapılandırma ister, sonra "başladı" bildirir
        var configured = false
        try {
            s.soTimeout = 10_000
            while (true) {
                val m = PbMsg.parse(readFrame(inp))
                handle(m, o)
                if (m.has(1)) configured = true
                if (m.has(40)) break
            }
        } catch (e: SocketTimeoutException) {
            if (!configured) {
                runCatching { s.close() }
                throw IOException("TV yanıt vermedi")
            }
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }

        if (pin == null) trust.seen?.let { prefs.edit().putString(pinKey(ip), sha256Hex(it.encoded)).apply() }

        // TV en geç 5 sn'de bir ping atar; 20 sn sessizlik bağlantının koptuğunu gösterir
        s.soTimeout = 20_000
        socket = s
        out = o
        Thread({ readLoop(s, inp, o) }, "atv-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(s: SSLSocket, inp: InputStream, o: OutputStream) {
        try {
            while (true) handle(PbMsg.parse(readFrame(inp)), o)
        } catch (_: Exception) {
        }
        runCatching { s.close() }
        if (socket === s) {
            socket = null
            out = null
            onDisconnected?.invoke()
        }
    }

    private fun handle(m: PbMsg, o: OutputStream) {
        when {
            m.has(1) -> { // remote_configure
                val cfg = m.msg(1)
                activeFeatures = RemoteMsg.FEATURES and (cfg?.int(1) ?: 0)
                val info = cfg?.msg(2)
                deviceName = listOf(info?.str(2), info?.str(1))
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                write(o, RemoteMsg.configureReply(activeFeatures))
            }
            m.has(2) -> write(o, RemoteMsg.setActive(activeFeatures))            // remote_set_active
            m.has(8) -> write(o, RemoteMsg.pong(m.msg(8)?.int(1) ?: 0))         // remote_ping_request
            m.has(21) -> {                                                       // remote_ime_batch_edit
                val b = m.msg(21)
                imeCounter = b?.int(1) ?: 0
                imeField = b?.int(2) ?: 0
            }
            m.has(50) -> {                                                       // remote_set_volume_level
                val v = m.msg(50) ?: return
                val max = v.int(6)
                val level = v.int(7)
                if (max > 0) {
                    volumeInfo = level to max
                    onVolume?.invoke(level, max)
                }
            }
        }
    }

    private fun write(o: OutputStream, payload: ByteArray) {
        synchronized(o) { writeFrame(o, payload) }
    }

    /** Gönderir; bağlantı kopmuşsa bir kez yeniden bağlanıp tekrar dener. */
    private fun send(payload: ByteArray) {
        val o = out
        if (o != null) {
            try {
                write(o, payload)
                return
            } catch (_: IOException) {
            }
        }
        synchronized(this) {
            if (host.isEmpty()) throw IOException("Önce TV'ye bağlan")
            closeRemote()
            openRemote(host)
        }
        write(out ?: throw IOException("Bağlantı kurulamadı"), payload)
    }

    override fun key(code: Int) = send(RemoteMsg.key(code))

    override fun text(raw: String) {
        val t = normalizeText(raw)
        if (t.isNotEmpty()) send(RemoteMsg.text(imeCounter, imeField, t))
    }

    /** Fare bu protokolde yok; yalnızca ADB bağlantısında çalışır. */
    override fun mouse(line: String) {}

    /** TV'deki uygulamaları listelemek bu protokolde yok; yalnızca ADB'de. */
    override fun listApps(): List<TvApp> = emptyList()

    override fun launch(app: TvApp) {}

    override fun volume(): Pair<Int, Int>? = volumeInfo

    @Synchronized
    override fun close() {
        cancelPairing()
        closeRemote()
    }

    private fun closeRemote() {
        val s = socket
        socket = null
        out = null
        runCatching { s?.close() }
    }

    // ---------------- Eşleştirme ----------------

    /** Eşleştirmeyi başlatır; döndüğünde TV ekranında 6 haneli kod görünür. */
    @Synchronized
    fun startPairing(ip: String) {
        cancelPairing()
        host = ip
        val trust = PinTrust(null)
        val s = tlsSocket(ip, PORT_PAIRING, trust)
        s.soTimeout = 15_000
        val inp = BufferedInputStream(s.inputStream)
        val o = BufferedOutputStream(s.outputStream)
        pairSocket = s; pairIn = inp; pairOut = o; pairTrust = trust
        try {
            writeFrame(o, Polo.pairingRequest(CLIENT_NAME))
            while (true) {
                val m = PbMsg.parse(readFrame(inp))
                checkStatus(m)
                when {
                    m.has(11) -> writeFrame(o, Polo.options())        // pairing_request_ack
                    m.has(20) -> writeFrame(o, Polo.configuration())  // options
                    m.has(31) -> return                               // configuration_ack: kod ekranda
                    else -> throw IOException("Beklenmeyen eşleştirme yanıtı")
                }
            }
        } catch (e: Exception) {
            cancelPairing()
            throw e
        }
    }

    /** TV'de görünen kodla eşleştirmeyi tamamlar. Yanlış kodda [BadCode] fırlatır ve kod yeniden denenebilir. */
    @Synchronized
    fun finishPairing(rawCode: String) {
        val code = rawCode.trim().uppercase()
        if (!Polo.isValidCode(code)) throw BadCode("Kod 6 haneli olmalı (0-9, A-F)")
        val s = pairSocket ?: throw IOException("Eşleştirme başlatılmadı")
        val o = pairOut!!
        val inp = pairIn!!
        val server = (pairTrust?.seen ?: s.session.peerCertificates.first() as X509Certificate)
            .publicKey as RSAPublicKey
        val client = clientCertificate().publicKey as RSAPublicKey
        val hash = Polo.pairingHash(client.modulus, client.publicExponent, server.modulus, server.publicExponent, code)
        if (!Polo.codeMatches(hash, code)) throw BadCode("Kod hatalı, TV'deki kodu kontrol et")

        try {
            writeFrame(o, Polo.secret(hash))
            val m = PbMsg.parse(readFrame(inp))
            if (m.int(2) == Polo.STATUS_BAD_SECRET) throw IOException("TV kodu kabul etmedi")
            checkStatus(m)
            if (!m.has(41)) throw IOException("Eşleştirme tamamlanamadı")
        } finally {
            cancelPairing()
        }
        // Yeni eşleşme: eski sabitlenmiş TV sertifikası (varsa) geçersiz
        prefs.edit().putBoolean(pairedKey(host), true).remove(pinKey(host)).apply()
    }

    @Synchronized
    fun cancelPairing() {
        runCatching { pairSocket?.close() }
        pairSocket = null; pairIn = null; pairOut = null; pairTrust = null
    }

    private fun checkStatus(m: PbMsg) {
        val st = m.int(2)
        if (st != Polo.STATUS_OK) throw IOException("TV eşleştirmeyi reddetti (durum $st)")
    }

    private fun forget(ip: String) {
        prefs.edit().remove(pairedKey(ip)).remove(pinKey(ip)).apply()
    }

    // ---------------- TLS ve anahtarlar ----------------

    private fun tlsSocket(ip: String, port: Int, trust: X509TrustManager): SSLSocket {
        ensureClientKey()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(ClientKeyManager()), arrayOf(trust), null)
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, port), 5_000)
        raw.tcpNoDelay = true
        val s = ctx.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
        s.soTimeout = 10_000
        s.startHandshake()
        return s
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureClientKey() {
        if (keyStore().containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=TV Kumanda"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L))
                .build()
        )
        kpg.generateKeyPair()
    }

    private fun clientCertificate(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate

    private inner class ClientKeyManager : X509ExtendedKeyManager() {
        private val ks = keyStore()
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = ks.getKey(ALIAS, null) as PrivateKey
    }

    /**
     * TV'ler kendinden imzalı sertifika kullanır, bu yüzden sertifika zinciri değil
     * sabitleme (pin) ile doğrulanır. pin == null ise sertifika kaydedilmek üzere kabul edilir:
     * eşleştirmede güvenlik ekrandaki kodla, kumanda kanalında ilk bağlantıdaki pin ile sağlanır.
     */
    private class PinTrust(private val pin: String?) : X509TrustManager {
        @Volatile var seen: X509Certificate? = null
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val c = chain?.firstOrNull() ?: throw CertificateException("TV sertifika göndermedi")
            seen = c
            if (pin != null && sha256Hex(c.encoded) != pin) throw CertificateException("TV sertifikası değişti")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        const val PORT_PAIRING = 6467
        const val PORT_REMOTE = 6466
        private const val ALIAS = "atv_remote_client"
        private const val CLIENT_NAME = "TV Kumanda"
        private fun pairedKey(ip: String) = "paired_$ip"
        private fun pinKey(ip: String) = "pin_$ip"
        private fun sha256Hex(b: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
