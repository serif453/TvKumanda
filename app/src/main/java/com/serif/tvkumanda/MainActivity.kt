package com.serif.tvkumanda

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// Android tuş kodları
object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3; const val MENU = 82
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164; const val POWER = 26
    const val CH_UP = 166; const val CH_DOWN = 167; const val ASSIST = 231
    const val PREV = 88; const val PLAY_PAUSE = 85; const val NEXT = 87
    const val DEL = 67; const val ENTER = 66
    fun digit(n: Int) = 7 + n // KEYCODE_0 = 7
}

enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR }

class MainActivity : ComponentActivity() {

    // İki bağımsız bağlantı yöntemi; hangisinin kullanılacağını kullanıcı seçer
    private lateinit var adb: TvRemote
    private lateinit var atv: AtvRemote
    private var method by mutableStateOf(ConnMethod.ADB)
    private val remote: RemoteLink get() = if (method == ConnMethod.STANDARD) atv else adb
    private var pairing by mutableStateOf(false)
    private var pairingError by mutableStateOf("")
    private var currentIp = ""
    private var savedTvs by mutableStateOf<List<SavedTv>>(emptyList())
    private lateinit var prefs: android.content.SharedPreferences

    // Sesli komut
    private var micActive by mutableStateOf(false)
    private var micLevel by mutableStateOf(0f)
    @Volatile private var micStop = false
    private var recognizer: SpeechRecognizer? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic() else toast("Sesli komut için mikrofon izni gerekli")
    }
    // Tuşlar sırayla gitsin diye tek iş parçacığı
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = MainScope()

    private var state by mutableStateOf(ConnState.IDLE)
    private var message by mutableStateOf("Bağlı değil")
    private var tvName by mutableStateOf("")
    private var fastMode by mutableStateOf(false)
    private var apps by mutableStateOf<List<TvApp>>(emptyList())
    private var volume by mutableStateOf<Int?>(null)
    private var volumeMax by mutableStateOf(100)
    private var volumeJob: Job? = null
    private var volumeTick by mutableStateOf(0)

    // Standart moddaki tam ekran yazma ekranı
    private var typing by mutableStateOf(false)
    private var typingSeed by mutableStateOf("")
    private var typingSeedTick by mutableStateOf(0)
    private var typingHint by mutableStateOf("")
    private var imeReady by mutableStateOf(false)
    @Volatile private var liveText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = TvRemote(applicationContext)
        atv = AtvRemote(applicationContext)
        atv.onVolume = { level, max -> runOnUiThread { volume = level; volumeMax = max } }
        atv.onDisconnected = { runOnUiThread { onStandardDisconnected() } }
        atv.onImeShow = { value -> runOnUiThread { onTvKeyboard(value) } }
        atv.onImeHide = { runOnUiThread { if (typing) closeTyping() } }
        atv.onFieldState = { present -> runOnUiThread { imeReady = present } }
        prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        savedTvs = SavedTv.load(prefs.getString("tvs", null))
        val savedIp = prefs.getString("ip", "") ?: ""
        method = runCatching { ConnMethod.valueOf(prefs.getString("method", null) ?: "ADB") }.getOrDefault(ConnMethod.ADB)

        setContent {
            RemoteTheme {
                RemoteScreen(
                    ui = UiState(state, message, tvName, fastMode, apps, volume, volumeMax, volumeTick, method, pairing, pairingError, savedTvs, micActive, micLevel,
                        typing, typingSeed, typingSeedTick, imeReady, typingHint),
                    initialIp = savedIp,
                    onConnect = { ip ->
                        prefs.edit().putString("ip", ip).apply()
                        connect(ip)
                    },
                    onKey = ::key,
                    onText = ::sendText,
                    onMouse = ::mouse,
                    onLaunch = { app -> run { this.launch(app) } },
                    onMethod = { m ->
                        if (m != method) {
                            prefs.edit().putString("method", m.name).apply()
                            switchMethod(m)
                        }
                    },
                    onPairCode = ::submitPairingCode,
                    onPickSaved = ::connectSaved,
                    onMic = ::onMic,
                    onForgetSaved = { tv ->
                        savedTvs = savedTvs.filterNot { it.ip == tv.ip && it.method == tv.method }
                        prefs.edit().putString("tvs", SavedTv.save(savedTvs)).apply()
                    },
                    onPairCancel = ::cancelPairing,
                    onOpenTyping = { openTyping(atv.imeText) },
                    onCloseTyping = ::closeTyping,
                    onLiveText = ::onLiveText,
                    onTypingSubmit = ::submitTyping,
                    onTvSearch = { run { this.key(RemoteMsg.KEYCODE_SEARCH) } }
                )
            }
        }

        if (savedIp.isNotBlank()) connect(savedIp)
    }

    private fun connect(ip: String) {
        if (ip.isBlank()) { state = ConnState.ERROR; message = "IP adresi gir"; return }
        currentIp = ip
        state = ConnState.CONNECTING
        message = if (method == ConnMethod.ADB) "Bağlanıyor… TV'de izin isterse onayla" else "Bağlanıyor…"
        val link = remote
        scope.launch {
            try {
                val model = withContext(io) { link.connectTo(ip) }
                onConnected(model, ip)
            } catch (e: AtvRemote.PairingRequired) {
                startPairing(ip)
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Bağlanamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun onConnected(model: String, ip: String) {
        // Başarılı her bağlantı kayıtlı TV listesinin en başına yazılır
        savedTvs = listOf(SavedTv(ip, model.ifBlank { ip }, method)) +
            savedTvs.filterNot { it.ip == ip && it.method == method }
        prefs.edit().putString("tvs", SavedTv.save(savedTvs)).apply()
        tvName = model
        fastMode = remote.isFast
        state = ConnState.CONNECTED
        message = ip
        apps = emptyList()
        loadApps(ip)
        refreshVolume(0)
    }

    /** Kayıtlı bir TV'ye, en son çalışan yöntemiyle bağlanır. */
    private fun connectSaved(tv: SavedTv) {
        if (tv.method != method) {
            prefs.edit().putString("method", tv.method.name).apply()
            switchMethod(tv.method)   // diğer yöntemin bağlantısını kapatır
        }
        // Aynı yöntemde eski bağlantıyı connectTo() kendisi, aynı iş parçacığında kapatır
        prefs.edit().putString("ip", tv.ip).apply()
        connect(tv.ip)
    }

    /** Yöntem değişince açık bağlantı kapatılır; diğer yönteme otomatik geçiş yapılmaz. */
    private fun switchMethod(m: ConnMethod) {
        val old = remote
        method = m
        pairing = false
        closeTyping()
        Thread { old.close() }.start()
        state = ConnState.IDLE
        fastMode = false
        apps = emptyList()
        volume = null
        message = "Bağlı değil"
    }

    // ---------- Standart bağlantı: eşleştirme ----------

    private fun startPairing(ip: String) {
        state = ConnState.CONNECTING
        message = "Eşleştirme başlatılıyor… TV'de kod görünecek"
        scope.launch {
            try {
                withContext(io) { atv.startPairing(ip) }
                pairingError = ""
                pairing = true
                message = "TV ekranındaki kodu gir"
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Eşleştirme başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun submitPairingCode(code: String) {
        scope.launch {
            try {
                withContext(io) { atv.finishPairing(code) }
                pairing = false
                message = "Eşleşti, bağlanıyor…"
                val model = withContext(io) { atv.connectTo(currentIp) }
                onConnected(model, currentIp)
            } catch (e: AtvRemote.BadCode) {
                pairingError = e.message ?: "Kod hatalı"   // pencere açık kalır, yeniden denenebilir
            } catch (e: Exception) {
                pairing = false
                state = ConnState.ERROR
                message = "Eşleştirilemedi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun cancelPairing() {
        pairing = false
        scope.launch(io) { atv.cancelPairing() }
        state = ConnState.IDLE
        message = "Eşleştirme iptal edildi"
    }

    /** TV bağlantıyı kapattıysa (ör. uyku sonrası) bir kez sessizce yeniden bağlanmayı dener. */
    private fun onStandardDisconnected() {
        if (method != ConnMethod.STANDARD || state != ConnState.CONNECTED) return
        val ip = currentIp
        scope.launch {
            delay(1000)
            try {
                val model = withContext(io) { atv.connectTo(ip) }
                if (method == ConnMethod.STANDARD) tvName = model
            } catch (e: Exception) {
                if (method == ConnMethod.STANDARD) {
                    state = ConnState.ERROR
                    message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun loadApps(ip: String) {
        val link = remote
        scope.launch {
            // Bu TV'nin son listesi önbellekten anında gösterilir, güncel liste arkadan gelir
            if (link === adb) {
                val cached = withContext(Dispatchers.IO) { adb.cachedApps(ip) }
                if (cached.isNotEmpty() && apps.isEmpty() && remote === link) apps = cached
            }
            val fresh = withContext(Dispatchers.IO) { runCatching { link.listApps() }.getOrDefault(emptyList()) }
            // Simgesiz yedek liste (hızlı mod açılamadıysa) simgeli önbelleğin yerine geçmez
            val downgrade = fresh.none { it.icon != null } && apps.any { it.icon != null }
            if (remote === link && !downgrade && (fresh.isNotEmpty() || apps.isEmpty())) apps = fresh
        }
    }

    /** Ses değişince kısa bir bekleme sonrası TV'den gerçek ses seviyesini okur. */
    private fun refreshVolume(delayMs: Long = 350) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(delayMs)
            val link = remote
            val v = withContext(Dispatchers.IO) { runCatching { link.volume() }.getOrNull() }
            if (v != null) { volume = v.first; volumeMax = v.second }
        }
    }

    /** Bağlantı üzerinde arka planda iş çalıştırır. */
    private fun run(action: RemoteLink.() -> Unit) {
        if (state != ConnState.CONNECTED) { message = "Önce TV'ye bağlan"; return }
        val link = remote
        scope.launch {
            try {
                withContext(io) { link.action() }
                fastMode = remote.isFast
            } catch (e: Exception) {
                state = ConnState.ERROR
                fastMode = false
                message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun key(code: Int) {
        run { this.key(code) }
        if (code == Keys.VOL_UP || code == Keys.VOL_DOWN || code == Keys.MUTE) {
            // Anında göster (tahmini), ardından TV'den gerçek değeri oku
            volume?.let { v ->
                volume = when (code) {
                    Keys.VOL_UP -> (v + 1).coerceAtMost(volumeMax)
                    Keys.VOL_DOWN -> (v - 1).coerceAtLeast(0)
                    else -> v
                }
            }
            volumeTick++
            refreshVolume()
        }
    }

    private fun mouse(line: String) {
        if (state != ConnState.CONNECTED) return
        val link = remote
        scope.launch(io) { runCatching { link.mouse(line) } }
    }

    private fun sendText(value: String) {
        if (value.isEmpty()) return
        run { this.text(value) }
    }

    // ---------- Yazma ekranı (Standart) ----------

    /** TV'de yazı kutusu açıldı: ekran kapalıysa kutudaki metinle açılır. */
    private fun onTvKeyboard(value: String) {
        if (method != ConnMethod.STANDARD || state != ConnState.CONNECTED) return
        typingHint = atv.imeHint
        // Yalnızca gerçek kutu seçiminde çağrılır (kutu bilgisi içeren mesaj)
        if (!typing && System.currentTimeMillis() - typingClosedAt > 2000) openTyping(value)
    }

    private fun openTyping(seed: String) {
        if (state != ConnState.CONNECTED) { message = "Önce TV'ye bağlan"; return }
        typingSeed = seed
        typingHint = atv.imeHint
        typingSeedTick++
        liveText = seed
        atv.startTyping(seed)
        // TV'de yazı kutusu seçili mi: ekrandaki bilgi satırı buna göre gösterilir
        // (sonraki değişiklikler onFieldState ile gelir)
        imeReady = atv.hasTextField
        typing = true
    }

    private var typingClosedAt = 0L

    private fun closeTyping() {
        if (typing) typingClosedAt = System.currentTimeMillis()
        typing = false
    }

    /** Canlı yazma: her değişiklik TV'ye gider (TV tarafında sırayla, cevap beklenerek uygulanır). */
    private fun onLiveText(value: String) {
        liveText = value
        if (state == ConnState.CONNECTED) atv.type(value)
    }

    /** Telefon klavyesindeki Ara/Git: son metni uygular, TV'ye Enter basar, ekranı kapatır. */
    private fun submitTyping() {
        atv.typeAndEnter(liveText)
        closeTyping()
    }

    // ---------- Sesli komut ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun onMic() {
        if (micActive) { stopMic(); return }
        if (state != ConnState.CONNECTED) { toast("Önce TV'ye bağlan"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startMic()
    }

    private fun startMic() {
        // Standart bağlantıda ses doğrudan TV'nin sesli aramasına akar; desteklenmiyorsa
        // ya da ADB'de, ses telefonda yazıya çevrilip TV'ye arama olarak gönderilir.
        if (method == ConnMethod.STANDARD && atv.supportsVoice) {
            startVoiceStream()
        } else {
            startPhoneRecognition()
        }
    }

    private fun stopMic() {
        micStop = true
        recognizer?.stopListening()
    }

    /**
     * Standart: mikrofon → 8 kHz / 16 bit / mono PCM → TV (referans: androidtvremote2 voice).
     * Kayıt, telefonun desteklediği bir hızda açılır ve 8 kHz'e dönüştürülür; kısık ses yükseltilir.
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        val rates = listOf(16000, 48000, 44100, 8000)
        for (src in sources) for (rate in rates) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = runCatching {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2 / 5))
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to rate
            r.release()
        }
        return null
    }

    private val VOICE_BLOCK = 16 * 1024

    private fun startVoiceStream() {
        micStop = false
        micActive = true
        Thread({
            var rec: AudioRecord? = null
            var sentBytes = 0
            var peak = 0f
            var stage = "mikrofon açılıyor"
            try {
                val opened = openRecorder() ?: throw IllegalStateException("Mikrofon açılamadı")
                rec = opened.first
                val rate = opened.second
                val resampler = VoiceDsp.Resampler(rate, RemoteMsg.VOICE_SAMPLE_RATE)
                val agc = VoiceDsp.AutoGain()
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("Mikrofon kullanımda")

                // Mikrofon hazır olduktan sonra TV'de oturumu aç; ses kaybolmasın
                stage = "TV oturumu bekleniyor"
                val sid = atv.startVoice()
                stage = "ses gönderiliyor"
                val buf = ByteArray(rate / 10 * 2)          // 100 ms
                val pending = ByteArrayOutputStream()
                val start = System.currentTimeMillis()
                var heard = false
                var lastLoud = start
                while (!micStop) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val pcm8k = agc.process(resampler.process(VoiceDsp.toShorts(buf, n)))
                    pending.write(VoiceDsp.toBytes(pcm8k))
                    val level = VoiceDsp.level(pcm8k)
                    peak = maxOf(peak, level)
                    runOnUiThread { micLevel = level }
                    val now = System.currentTimeMillis()
                    if (level > 0.08f) { heard = true; lastLoud = now }
                    // Referans örnekle aynı: tam 16 KB (8 kHz'de 1 sn) parçalar
                    while (pending.size() >= VOICE_BLOCK) {
                        val all = pending.toByteArray()
                        atv.sendVoice(sid, all.copyOfRange(0, VOICE_BLOCK))
                        sentBytes += VOICE_BLOCK
                        pending.reset()
                        pending.write(all, VOICE_BLOCK, all.size - VOICE_BLOCK)
                    }
                    // Konuşma bitince (1,5 sn sessizlik) ya da hiç konuşulmazsa kendiliğinden dur
                    if (heard && now - lastLoud > 1500) break
                    if (!heard && now - start > 6000) break
                    if (now - start > 15000) break
                }
                if (pending.size() > 0) {
                    atv.sendVoice(sid, pending.toByteArray())
                    sentBytes += pending.size()
                }
                atv.endVoice(sid)
                stage = "bitti"
            } catch (e: Exception) {
                stage = "hata: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // Yalnızca gerçek sorunlarda kullanıcıya bilgi ver
                val problem = when {
                    stage.startsWith("hata") -> "Sesli komut: ${stage.removePrefix("hata: ")}"
                    sentBytes == 0 -> "Mikrofondan ses alınamadı"
                    peak < 0.05f -> "Ses çok kısık algılandı, telefona daha yakın konuş"
                    else -> null
                }
                if (problem != null) runOnUiThread { toast(problem) }
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
                runOnUiThread { micActive = false; micLevel = 0f }
            }
        }, "voice").start()
    }

    /** ADB (ve sesi desteklemeyen TV'ler): telefonda yazıya çevir, TV'de ara. */
    private fun startPhoneRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Bu telefonda konuşma tanıma bulunamadı")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        micStop = false
        micActive = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { micLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    toast("Ses anlaşılamadı, tekrar dene")
                } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                    toast("Konuşma tanıma hatası ($error)")
                }
                finishRecognition()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    toast("“$text”")
                    sendRecognized(text)
                }
                finishRecognition()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        )
    }

    private fun finishRecognition() {
        recognizer?.destroy()
        recognizer = null
        micActive = false
        micLevel = 0f
    }

    private fun sendRecognized(text: String) {
        if (method == ConnMethod.ADB) {
            run { (this as? TvRemote)?.voiceSearch(text) }
        } else {
            // Ses aktarımı olmayan Standart TV: arama ekranını aç, metni yaz
            scope.launch {
                try {
                    withContext(io) { atv.key(RemoteMsg.KEYCODE_SEARCH) }
                    delay(1500)
                    withContext(io) { atv.text(text) }
                } catch (e: Exception) {
                    toast("Gönderilemedi: ${e.message}")
                }
            }
        }
    }

    // Telefonun ses tuşları TV'nin sesini kontrol eder
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (state == ConnState.CONNECTED &&
            (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                key(if (code == KeyEvent.KEYCODE_VOLUME_UP) Keys.VOL_UP else Keys.VOL_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        micStop = true
        recognizer?.destroy()
        scope.cancel()
        Thread { adb.close(); atv.release(); io.close() }.start()
    }
}

data class UiState(
    val state: ConnState,
    val message: String,
    val tvName: String,
    val fastMode: Boolean,
    val apps: List<TvApp>,
    val volume: Int?,
    val volumeMax: Int,
    val volumeTick: Int,
    val method: ConnMethod,
    val pairing: Boolean,
    val pairingError: String,
    val savedTvs: List<SavedTv>,
    val micActive: Boolean,
    val micLevel: Float,
    val typing: Boolean,
    val typingSeed: String,
    val typingSeedTick: Int,
    val imeReady: Boolean,
    val typingHint: String = ""
)

/** Kayıtlı TV: IP + bağlantı yöntemi birlikte anahtardır; aynı TV her yöntem için ayrı kaydedilir. */
data class SavedTv(val ip: String, val name: String, val method: ConnMethod) {
    companion object {
        fun load(json: String?): List<SavedTv> = runCatching {
            val arr = org.json.JSONArray(json ?: "[]")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedTv(
                    o.getString("ip"),
                    o.optString("name", o.getString("ip")),
                    runCatching { ConnMethod.valueOf(o.optString("method", "ADB")) }.getOrDefault(ConnMethod.ADB)
                )
            }
        }.getOrDefault(emptyList())

        fun save(list: List<SavedTv>): String = org.json.JSONArray().apply {
            list.forEach { put(org.json.JSONObject().put("ip", it.ip).put("name", it.name).put("method", it.method.name)) }
        }.toString()
    }
}
