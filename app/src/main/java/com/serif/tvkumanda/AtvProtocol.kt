package com.serif.tvkumanda

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest

/*
 * Android TV Remote v2 protokolü — saf (Android'den bağımsız) kısım.
 *
 * Kaynak: tronikos/androidtvremote2 (Apache-2.0; Home Assistant'ın kullandığı kütüphane),
 * dosyalar: polo.proto, remotemessage.proto, pairing.py, remote.py, base.py.
 * Alan numaraları ve akış bu dosyalardan birebir alınmıştır; tahmin içermez.
 *
 *  - Eşleştirme: TLS, port 6467, OuterMessage (polo.proto)
 *  - Kumanda:    TLS, port 6466, RemoteMessage (remotemessage.proto)
 *  - Çerçeve:    her mesajın önünde varint uzunluk
 */

// ---------------- Küçük protobuf kodlayıcı / çözücü ----------------

fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

class Pb {
    private val out = ByteArrayOutputStream()
    private fun tag(field: Int, wireType: Int) = writeVarint(out, ((field shl 3) or wireType).toLong())
    fun int(field: Int, v: Long): Pb { tag(field, 0); writeVarint(out, v); return this }
    fun int(field: Int, v: Int): Pb = int(field, v.toLong())
    fun bytes(field: Int, b: ByteArray): Pb { tag(field, 2); writeVarint(out, b.size.toLong()); out.write(b); return this }
    fun str(field: Int, s: String): Pb = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, m: Pb): Pb = bytes(field, m.build())
    fun build(): ByteArray = out.toByteArray()
}

class PbMsg private constructor(private val fields: Map<Int, List<Any>>) {
    fun has(n: Int) = fields.containsKey(n)
    val fieldNumbers: Set<Int> get() = fields.keys
    fun long(n: Int): Long = fields[n]?.firstOrNull() as? Long ?: 0L
    fun int(n: Int): Int = long(n).toInt()
    fun bytes(n: Int): ByteArray? = fields[n]?.firstOrNull() as? ByteArray
    fun str(n: Int): String = bytes(n)?.toString(Charsets.UTF_8) ?: ""
    fun msg(n: Int): PbMsg? = bytes(n)?.let { parse(it) }

    companion object {
        fun parse(b: ByteArray): PbMsg {
            val map = LinkedHashMap<Int, MutableList<Any>>()
            var i = 0
            fun varint(): Long {
                var r = 0L
                var shift = 0
                while (true) {
                    if (i >= b.size) throw IOException("Kesik varint")
                    val x = b[i++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                    if (shift > 63) throw IOException("Geçersiz varint")
                }
            }
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when (val wt = (key and 7).toInt()) {
                    0 -> map.getOrPut(field) { mutableListOf() }.add(varint())
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || i + len > b.size) throw IOException("Geçersiz uzunluk")
                        map.getOrPut(field) { mutableListOf() }.add(b.copyOfRange(i, i + len))
                        i += len
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> throw IOException("Bilinmeyen wire type $wt")
                }
            }
            return PbMsg(map)
        }
    }
}

/** Varint uzunluk ön ekli tek mesaj yazar. */
fun writeFrame(out: OutputStream, payload: ByteArray) {
    val buf = ByteArrayOutputStream(payload.size + 5)
    writeVarint(buf, payload.size.toLong())
    buf.write(payload)
    out.write(buf.toByteArray())
    out.flush()
}

/** Varint uzunluk ön ekli tek mesaj okur. */
fun readFrame(inp: InputStream): ByteArray {
    var len = 0L
    var shift = 0
    while (true) {
        val x = inp.read()
        if (x < 0) throw EOFException("Bağlantı kapandı")
        len = len or ((x and 0x7F).toLong() shl shift)
        if (x and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw IOException("Geçersiz çerçeve")
    }
    if (len > 1_000_000) throw IOException("Çerçeve çok büyük")
    val data = ByteArray(len.toInt())
    var off = 0
    while (off < data.size) {
        val r = inp.read(data, off, data.size - off)
        if (r < 0) throw EOFException("Bağlantı kapandı")
        off += r
    }
    return data
}

// ---------------- Eşleştirme (polo.proto / pairing.py) ----------------

object Polo {
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    // OuterMessage: protocol_version=1 (2), status=2 (200)
    private fun outer() = Pb().int(1, 2).int(2, STATUS_OK)
    // Options.Encoding: type=1 (HEXADECIMAL=3), symbol_length=2 (6)
    private fun hexEncoding() = Pb().int(1, 3).int(2, 6)

    /** pairing_request=10 { service_name=1 "atvremote", client_name=2 } */
    fun pairingRequest(clientName: String) =
        outer().msg(10, Pb().str(1, "atvremote").str(2, clientName)).build()

    /** options=20 { input_encodings=1 [HEX,6], preferred_role=3 INPUT(1) } */
    fun options() = outer().msg(20, Pb().msg(1, hexEncoding()).int(3, 1)).build()

    /** configuration=30 { encoding=1 [HEX,6], client_role=2 INPUT(1) } */
    fun configuration() = outer().msg(30, Pb().msg(1, hexEncoding()).int(2, 1)).build()

    /** secret=40 { secret=1 bytes } */
    fun secret(hash: ByteArray) = outer().msg(40, Pb().bytes(1, hash)).build()

    private fun unsigned(b: BigInteger): ByteArray {
        val a = b.toByteArray()
        return if (a.size > 1 && a[0] == 0.toByte()) a.copyOfRange(1, a.size) else a
    }

    fun isValidCode(code: String) = code.length == 6 && code.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

    /**
     * SHA-256(istemci modulus, istemci üs, sunucu modulus, sunucu üs, kodun son 4 hanesi).
     * Kodun ilk 2 hanesi, hash'in ilk baytına eşit olmalıdır (yanlış kod yerelde yakalanır).
     */
    fun pairingHash(
        clientModulus: BigInteger, clientExponent: BigInteger,
        serverModulus: BigInteger, serverExponent: BigInteger,
        code: String
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(clientModulus))
        md.update(unsigned(clientExponent))
        md.update(unsigned(serverModulus))
        md.update(unsigned(serverExponent))
        md.update(hexToBytes(code.substring(2)))
        return md.digest()
    }

    fun codeMatches(hash: ByteArray, code: String) =
        (hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    private fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

// ---------------- Kumanda kanalı (remotemessage.proto / remote.py) ----------------

object RemoteMsg {
    // Özellik bayrakları: PING=1, KEY=2, IME=4, VOICE=8, POWER=32, VOLUME=64, APP_LINK=512
    const val VOICE = 8
    const val FEATURES = 1 or 2 or 4 or VOICE or 32 or 64 or 512
    const val KEYCODE_SEARCH = 84
    // Ses: 16 bit PCM, 8 kHz, mono. Parça en fazla 20 KB, en az 8 KB (eksik kısım sessizlikle doldurulur)
    const val VOICE_SAMPLE_RATE = 8000
    const val VOICE_CHUNK_MAX = 20 * 1024
    const val VOICE_CHUNK_MIN = 8 * 1024
    const val SHORT = 3

    /** remote_configure=1 { code1=1, device_info=2 { unknown1=3:1, unknown2=4:"1", package_name=5, app_version=6 } } */
    fun configureReply(active: Int) = Pb().msg(
        1, Pb().int(1, active).msg(
            2, Pb().int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0")
        )
    ).build()

    /** remote_set_active=2 { active=1 } */
    fun setActive(active: Int) = Pb().msg(2, Pb().int(1, active)).build()

    /** remote_ping_response=9 { val1=1 } */
    fun pong(val1: Int) = Pb().msg(9, Pb().int(1, val1)).build()

    /** remote_key_inject=10 { key_code=1, direction=2 } — tuş kodları Android KeyEvent ile aynı */
    fun key(code: Int, direction: Int = SHORT) = Pb().msg(10, Pb().int(1, code).int(2, direction)).build()

    /** remote_ime_batch_edit=21 { ime_counter=1, field_counter=2, edit_info=3 { insert=1:1, text_field_status=2 { start=1, end=2, value=3 } } } */
    fun text(imeCounter: Int, fieldCounter: Int, text: String): ByteArray {
        val n = text.length - 1
        return Pb().msg(
            21, Pb().int(1, imeCounter).int(2, fieldCounter).msg(
                3, Pb().int(1, 1).msg(2, Pb().int(1, n).int(2, n).str(3, text))
            )
        ).build()
    }

    /** remote_voice_begin=30 { session_id=1 } — TV'nin açtığı oturumu onaylar */
    fun voiceBegin(sessionId: Int) = Pb().msg(30, Pb().int(1, sessionId)).build()

    /** remote_voice_payload=31 { session_id=1, samples=2 } */
    fun voicePayload(sessionId: Int, samples: ByteArray) = Pb().msg(31, Pb().int(1, sessionId).bytes(2, samples)).build()

    /** remote_voice_end=32 { session_id=1 } */
    fun voiceEnd(sessionId: Int) = Pb().msg(32, Pb().int(1, sessionId)).build()

    /** remote_app_link_launch_request=90 { app_link=1 } */
    fun appLink(link: String) = Pb().msg(90, Pb().str(1, link)).build()
}

// ---------------- Kendinden imzalı istemci sertifikası (saf JVM) ----------------

/**
 * Bağımlılıksız, küçük bir X.509 v3 sertifika üreticisi (DER).
 * Android TV Remote yalnızca sertifikanın açık anahtarını kullanır; içerik basit tutulur.
 */
object SelfSignedCert {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    private fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + len(body.size) + body
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())

    private val SHA256_WITH_RSA = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val NULL = byteArrayOf(0x05, 0x00)

    private fun name(cn: String) = seq(set(seq(OID_CN, tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))

    private fun time(ms: Long): ByteArray {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        val y = c.get(java.util.Calendar.YEAR)
        val rest = String.format(
            java.util.Locale.US, "%02d%02d%02d%02d%02dZ",
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)
        )
        return if (y < 2050) tlv(0x17, (String.format(java.util.Locale.US, "%02d", y % 100) + rest).toByteArray())
        else tlv(0x18, (y.toString() + rest).toByteArray())
    }

    /** DER kodlu sertifika döndürür. */
    fun create(keyPair: java.security.KeyPair, cn: String, notBefore: Long, notAfter: Long, serial: BigInteger): ByteArray {
        val algo = seq(SHA256_WITH_RSA, NULL)
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))),   // v3
            integer(serial),
            algo,
            name(cn),
            seq(time(notBefore), time(notAfter)),
            name(cn),
            keyPair.public.encoded                       // SubjectPublicKeyInfo (hazır DER)
        )
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        return seq(tbs, algo, tlv(0x03, byteArrayOf(0) + sig))
    }
}
