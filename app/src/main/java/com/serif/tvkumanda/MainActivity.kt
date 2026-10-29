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
    const val INPUT = 178 // KEYCODE_TV_INPUT: TV'nin giriş kaynağı menüsü
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

    // Cast (TV'nin yerleşik Chromecast'i)
    private val castClient = CastClient()
    private lateinit var mediaServer: MediaServer
    private var cast by mutableStateOf(CastUi())
    private var castPollJob: Job? = null
    private val castIo = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var pendingKind = MediaKind.VIDEO
    /** Oynatmanın başlatıldığı klasör/albüm (önceki/sonraki ve otomatik geçiş için). */
    private var playlist: List<MediaItem> = emptyList()
    private var playIndex = -1
    private var playAlbumId: Long? = null
    private var castStepTick = 0
    private var castVolumeKnown = -1f
    // Müzikte tekrar/karıştır (seçim hatırlanır)
    private var repeatMode by mutableStateOf(MusicRepeat.ALL)
    private var shuffle by mutableStateOf(false)
    // Bildirim/kilit ekranı kontrolleri
    private var mediaSession: android.media.session.MediaSession? = null
    private var volumeProvider: android.media.VolumeProvider? = null
    private lateinit var thumbLib: MediaLibrary
    private var notifKey = ""
    private var artFor: Long? = null
    private var artBitmap: android.graphics.Bitmap? = null
    // "Paylaş" ile gelen dosyalar (bağlantı kurulunca oynatılır)
    private var pendingShare: List<MediaItem>? = null
    private var castTabTick by mutableStateOf(0)
    // DLNA (deneysel)
    private lateinit var dlnaClient: DlnaClient
    private var dlnaUi by mutableStateOf(DlnaUi())
    private var selectedRoute = CastRoute.CHROMECAST
    private var dlnaSawPlaying = false
    private var dlnaSearchedOnce = false
    // Fotoğraf slayt gösterisi
    private var slideshow = false
    private var slideSec = 5
    private var slideJob: Job? = null
    // Kaldığın yerden devam (video)
    private var currentUri: android.net.Uri? = null
    private var autoAdvancing = false
    // Altyazı (videoya göre hatırlanır)
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSubtitlePicked(uri)
    }
    private var saveCounter = 0
    @Volatile private var pendingVolume: Float? = null
    private var volumeSendJob: Job? = null
    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { playlist = emptyList(); playIndex = -1; playAlbumId = null; castMedia(uri, pendingKind) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = TvRemote(applicationContext)
        atv = AtvRemote(applicationContext)
        TvTools.adb = adb
        TvTools.isAdb = { method == ConnMethod.ADB }
        TvTools.sendKey = { code -> runOnUiThread { key(code) } }
        TvTools.sendText = { t -> runOnUiThread { sendText(t) } }
        TvTools.atvLink = { url -> atv.openLink(url) }
        TvTools.onRebooting = { runOnUiThread { reconnectAfterReboot() } }
        atv.onVolume = { level, max -> runOnUiThread { volume = level; volumeMax = max } }
        atv.onDisconnected = { runOnUiThread { onStandardDisconnected() } }
        atv.onImeShow = { value -> runOnUiThread { onTvKeyboard(value) } }
        atv.onImeHide = { runOnUiThread { if (typing) closeTyping() } }
        atv.onFieldState = { present -> runOnUiThread { imeReady = present } }
        mediaServer = MediaServer(applicationContext)
        thumbLib = MediaLibrary(applicationContext)
        dlnaClient = DlnaClient(applicationContext)
        repeatMode = runCatching { MusicRepeat.valueOf(getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getString("tekrar", "ALL")!!) }.getOrDefault(MusicRepeat.ALL)
        shuffle = getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getBoolean("karistir", false)
        slideSec = getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getInt("slayt_sn", 5)
        setupMediaSession()
        CastService.controller = { a -> runOnUiThread { onNotificationAction(a) } }
        castClient.onStatus = { st -> runOnUiThread { onCastStatus(st) } }
        castClient.onClosed = { reason -> runOnUiThread { onCastClosed(reason) } }
        castClient.onVolume = { v -> runOnUiThread { castVolumeKnown = v; if (cast.phase != CastPhase.NONE) cast = cast.copy(volume = v) } }
        prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        savedTvs = SavedTv.load(prefs.getString("tvs", null))
        val savedIp = prefs.getString("ip", "") ?: ""
        method = runCatching { ConnMethod.valueOf(prefs.getString("method", null) ?: "ADB") }.getOrDefault(ConnMethod.ADB)

        setContent {
            RemoteTheme {
                RemoteScreen(
                    ui = UiState(state, message, tvName, fastMode, apps, volume, volumeMax, volumeTick, method, pairing, pairingError, savedTvs, micActive, micLevel,
                        typing, typingSeed, typingSeedTick, imeReady, typingHint, castTabTick),
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
                    onTvSearch = { run { this.key(RemoteMsg.KEYCODE_SEARCH) } },
                    cast = cast,
                    onCastPick = ::pickForCast,
                    onCastItem = { item, list, albumId -> playAlbumId = albumId; playFromList(list, list.indexOf(item)) },
                    onCastPrev = { castStep(-1) },
                    onCastNext = { castStep(1) },
                    onCastSkip = ::castSkip,
                    onCastWarmup = ::castWarmup,
                    onCastVolume = ::castVolume,
                    onCastPlayAt = { i -> castStepTick++; playFromList(playlist, i) },
                    onCastRepeat = ::cycleRepeat,
                    onCastShuffle = ::toggleShuffle,
                    onCastSlideshow = ::toggleSlideshow,
                    onCastSlideSec = ::cycleSlideSec,
                    onCastSubtitle = ::subtitleAction,
                    dlna = dlnaUi,
                    onCastRoute = { r -> selectedRoute = r },
                    onDlnaPick = ::pickDlna,
                    onDlnaSearch = { searchDlna(force = true) },
                    onCastToggle = ::castToggle,
                    onCastSeek = ::castSeek,
                    onCastStop = ::castStop
                )
            }
        }

        if (savedIp.isNotBlank()) connect(savedIp)
        handleShare(intent)
        // Oynatıcı durumu değiştikçe bildirim ve kilit ekranı güncellenir
        scope.launch { androidx.compose.runtime.snapshotFlow { cast }.collect { syncMediaSession(it) } }
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
        pendingShare?.let { list -> pendingShare = null; playShared(list) }
        pendingLink?.let { url -> pendingLink = null; openSharedLink(url) }
    }

    /** Yeniden başlatılan TV açılana kadar bekler, sonra kendiliğinden yeniden bağlanır (en fazla ~3 dk). */
    private fun reconnectAfterReboot() {
        val ip = currentIp
        val link = remote
        state = ConnState.CONNECTING
        message = "TV yeniden başlıyor…"
        scope.launch {
            delay(25_000)
            repeat(32) {
                if (state != ConnState.CONNECTING || currentIp != ip) return@launch
                try {
                    val model = withContext(io) { link.connectTo(ip) }
                    onConnected(model, ip)
                    return@launch
                } catch (_: Exception) {
                    delay(5_000)
                }
            }
            state = ConnState.ERROR
            message = "TV yeniden başladıktan sonra bağlanamadı"
        }
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

    // ---------- Cast ----------

    private fun pickForCast(kind: MediaKind) {
        val ok = if (selectedRoute == CastRoute.DLNA) dlnaUi.selected != null else state == ConnState.CONNECTED && currentIp.isNotBlank()
        if (!ok) { toast(if (selectedRoute == CastRoute.DLNA) "Önce bir DLNA cihazı seç" else "Önce TV'ye bağlan"); return }
        pendingKind = kind
        runCatching { pickMedia.launch(kind.mimeFilter) }.onFailure { toast("Dosya seçici açılamadı") }
    }

    /** Seçilen dosyayı telefondaki sunucudan yayınlar ve TV'nin Chromecast'ine oynatır. */
    private fun castMedia(
        uri: android.net.Uri, kind: MediaKind, knownTitle: String? = null, knownMime: String? = null,
        startAt: Double = 0.0, askResume: Boolean = true, route: CastRoute = nextRoute()
    ) {
        val dev = dlnaUi.selected
        if (route == CastRoute.CHROMECAST && (state != ConnState.CONNECTED || currentIp.isBlank())) { toast("Önce TV'ye bağlan"); return }
        if (route == CastRoute.DLNA && dev == null) { toast("Önce bir DLNA cihazı seç"); return }
        // Başka yoldan yayın sürüyorsa o durdurulur (aynı anda iki cihaza yayın yapılmaz)
        if (cast.phase != CastPhase.NONE && cast.route != route) stopRouteQuietly(cast.route)
        // Önceki videonun konumu kaydedilir
        saveResumePosition()
        val auto = autoAdvancing
        autoAdvancing = false
        // TV'nin büyük ihtimalle açamayacağı biçim: önce uyar (otomatik geçişte sormadan dener)
        val skipCheck = skipFormatCheck
        skipFormatCheck = false
        // DLNA cihazları genelde bu biçimleri de oynatır; uyarı yalnızca Chromecast'te
        if (route == CastRoute.CHROMECAST && askResume && !auto && !skipCheck && likelyUnsupported(knownMime, knownTitle ?: displayName(uri))) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Bu dosya TV'de açılmayabilir")
                .setMessage("TV'nin Chromecast oynatıcısı bu biçimi (MKV, AVI, WMV gibi) genellikle desteklemez.")
                .setPositiveButton("Yine de dene") { _, _ -> continueCast(uri, kind, knownTitle, knownMime, route) }
                .setNegativeButton("Vazgeç", null)
                .show()
            return
        }
        // Daha önce yarıda bırakılmış video: kaldığı yerden devam edilsin mi?
        if (kind == MediaKind.VIDEO && askResume && !auto) {
            val saved = prefs.getFloat(resumeKey(uri), 0f).toDouble()
            if (saved > 0) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(knownTitle ?: kind.label)
                    .setMessage("Bu videoyu daha önce yarıda bırakmıştın.")
                    .setPositiveButton("Kaldığın yerden devam et (${formatTime(saved)})") { _, _ ->
                        castMedia(uri, kind, knownTitle, knownMime, startAt = saved, askResume = false, route = route)
                    }
                    .setNegativeButton("Baştan başla") { _, _ ->
                        prefs.edit().remove(resumeKey(uri)).apply()
                        castMedia(uri, kind, knownTitle, knownMime, startAt = 0.0, askResume = false, route = route)
                    }
                    .show()
                return
            }
        }
        currentUri = uri
        askNotificationPermissionOnce()
        val ip = if (route == CastRoute.DLNA) dev!!.ip else currentIp
        val title = knownTitle?.ifBlank { null } ?: displayName(uri) ?: kind.label
        val mime = knownMime?.ifBlank { null } ?: contentResolver.getType(uri) ?: kind.fallbackMime
        val subUri = if (kind == MediaKind.VIDEO && route == CastRoute.CHROMECAST) prefs.getString(subtitleKey(uri), null)?.let { android.net.Uri.parse(it) } else null
        cast = CastUi(
            CastPhase.PREPARING, title, kind, hasList = playlist.size > 1,
            currentId = playlist.getOrNull(playIndex)?.takeIf { it.uri == uri }?.id,
            albumId = if (playlist.isNotEmpty()) playAlbumId else null,
            stepTick = castStepTick,
            item = playlist.getOrNull(playIndex)?.takeIf { it.uri == uri },
            volume = castVolumeKnown,
            list = playlist,
            repeat = repeatMode,
            shuffle = shuffle,
            slideshow = slideshow && kind == MediaKind.PHOTO,
            slideSec = slideSec,
            hasSubtitle = subUri != null,
            route = route,
            routeName = if (route == CastRoute.DLNA) dev?.name.orEmpty() else tvName
        )
        castPollJob?.cancel()
        if (kind != MediaKind.PHOTO) { slideshow = false; slideJob?.cancel() } else if (slideshow) restartSlideTimer()
        scope.launch {
            try {
                if (route == CastRoute.DLNA) {
                    withContext(castIo) {
                        val url = mediaServer.publish(uri, mime, ip)
                        // Bağlı olduğumuz TV ise ve uykudaysa önce uyandırılır
                        if (dev!!.ip == currentIp && state == ConnState.CONNECTED) wakeTvIfAsleep(ip, waitForCast = false)
                        castStage("DLNA cihazına gönderiliyor…")
                        dlnaSawPlaying = false
                        dlnaClient.load(dev, url, mime, title, kind)
                        if (startAt > 0) runCatching { Thread.sleep(800); dlnaClient.seek(dev, startAt) }
                        dlnaClient.volume(dev)?.let { v -> runOnUiThread { castVolumeKnown = v; cast = cast.copy(volume = v) } }
                    }
                    if (kind == MediaKind.PHOTO) cast = cast.copy(phase = CastPhase.PLAYING)
                    startCastPolling()
                    return@launch
                }
                withContext(castIo) {
                    val url = mediaServer.publish(uri, mime, ip)
                    // Altyazı: SRT → WebVTT, telefondaki sunucudan
                    val subUrl = subUri?.let { su ->
                        runCatching {
                            val raw = contentResolver.openInputStream(su)!!.use { it.readBytes() }
                            mediaServer.publishBytes(Subtitles.convert(raw), "text/vtt; charset=utf-8", ip)
                        }.getOrElse { runOnUiThread { toast("Altyazı dosyası okunamadı") }; null }
                    }
                    // TV uykudaysa önce uyandırılır
                    wakeTvIfAsleep(ip)
                    if (!castClient.isConnected) castStage("TV'ye bağlanılıyor…")
                    castClient.connect(ip)
                    if (!castClient.hasReceiver) {
                        castStage("TV'de oynatıcı açılıyor…")
                        castClient.prepareReceiver()
                    }
                    castStage("Dosya yükleniyor…")
                    castClient.load(url, mime, title, startAt, subUrl)
                }
                startCastPolling()
            } catch (e: Exception) {
                cast = cast.copy(phase = CastPhase.ERROR, error = castError(e))
            }
        }
    }

    /** Tam ekran oynatıcıdaki ses çubuğu: sürüklerken en fazla saniyede ~6 kez gönderilir. */
    private fun castVolume(level: Float) {
        pendingVolume = level
        castVolumeKnown = level
        cast = cast.copy(volume = level)
        if (volumeSendJob?.isActive == true) return
        volumeSendJob = scope.launch {
            while (true) {
                val v = pendingVolume ?: break
                pendingVolume = null
                withContext(castIo) {
                    runCatching {
                        if (cast.route == CastRoute.DLNA) dlnaUi.selected?.let { dlnaClient.setVolume(it, v) }
                        else castClient.setVolume(v)
                    }
                }
                delay(150)
            }
        }
    }

    /** Hazırlık aşamasını karta yazar (yalnızca hâlâ hazırlanıyorsa). */
    private fun castStage(text: String) = runOnUiThread {
        if (cast.phase == CastPhase.PREPARING) cast = cast.copy(stage = text)
    }

    // ---------- Bildirim ve kilit ekranı (MediaSession) ----------

    private fun setupMediaSession() {
        val ms = android.media.session.MediaSession(this, "TvKumanda")
        ms.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { if (cast.phase == CastPhase.PAUSED || cast.phase == CastPhase.ENDED) castToggle() }
            override fun onPause() { if (cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING) castToggle() }
            override fun onSkipToNext() { castStep(1) }
            override fun onSkipToPrevious() { castStep(-1) }
            override fun onStop() { castStop() }
            override fun onSeekTo(pos: Long) { castSeek(pos / 1000.0) }
        })
        // Yayın sürerken telefonun ses tuşları (ekran kilitliyken de) TV'nin sesini değiştirir
        val vp = object : android.media.VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, 50) {
            override fun onSetVolumeTo(volume: Int) { runOnUiThread { castVolume(volume / 100f); currentVolume = volume } }
            override fun onAdjustVolume(direction: Int) {
                runOnUiThread {
                    val v = ((castVolumeKnown.coerceAtLeast(0f) * 100).toInt() + direction * 2).coerceIn(0, 100)
                    castVolume(v / 100f); currentVolume = v
                }
            }
        }
        ms.setPlaybackToRemote(vp)
        volumeProvider = vp
        mediaSession = ms
    }

    private fun onNotificationAction(a: String) {
        when (a) {
            CastService.ACTION_PLAY, CastService.ACTION_PAUSE -> castToggle()
            CastService.ACTION_NEXT -> castStep(1)
            CastService.ACTION_PREV -> castStep(-1)
            CastService.ACTION_STOP -> castStop()
        }
    }

    /** Oynatıcı durumunu bildirime ve kilit ekranına yansıtır. */
    private fun syncMediaSession(c: CastUi) {
        val ms = mediaSession ?: return
        if (c.phase == CastPhase.NONE) {
            ms.isActive = false
            notifKey = ""
            return
        }
        // Bitti/hata: bildirim kaldırılır (yayın sürmüyor)
        if (c.phase == CastPhase.ENDED || c.phase == CastPhase.ERROR) {
            notifKey = ""
            CastService.stop(this)
            return
        }
        val playing = c.phase == CastPhase.PLAYING || c.phase == CastPhase.BUFFERING || c.phase == CastPhase.PREPARING
        val state = when (c.phase) {
            CastPhase.PLAYING -> android.media.session.PlaybackState.STATE_PLAYING
            CastPhase.PAUSED -> android.media.session.PlaybackState.STATE_PAUSED
            CastPhase.ENDED, CastPhase.ERROR -> android.media.session.PlaybackState.STATE_STOPPED
            else -> android.media.session.PlaybackState.STATE_BUFFERING
        }
        var actions = android.media.session.PlaybackState.ACTION_STOP
        if (c.kind != MediaKind.PHOTO) actions = actions or android.media.session.PlaybackState.ACTION_PLAY or
            android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
            android.media.session.PlaybackState.ACTION_SEEK_TO
        if (c.hasList) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
            android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        ms.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(actions)
                .setState(state, (c.position * 1000).toLong(), if (c.phase == CastPhase.PLAYING) 1f else 0f)
                .build()
        )
        if (c.volume >= 0f) volumeProvider?.currentVolume = (c.volume * 100).toInt()
        ms.isActive = true

        // Kapak/küçük resim (dosya değişince bir kez yüklenir)
        val item = c.item
        if (item != null && artFor != item.id) {
            artFor = item.id
            artBitmap = null
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { runCatching { thumbLib.thumbnail(item, 512) }.getOrNull() }
                if (artFor == item.id) { artBitmap = bmp; notifKey = ""; syncMediaSession(cast) }
            }
        }
        // Bildirim yalnızca ad/durum değişince güncellenir (konum her saniye değil)
        val key = "${c.title}|$playing|${c.hasList}|${c.kind}|${artBitmap != null}"
        if (key == notifKey) return
        notifKey = key
        ms.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, c.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, c.item?.subtitle.orEmpty())
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, (c.duration * 1000).toLong())
                .apply { artBitmap?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        CastService.update(this, CastService.Info(
            title = c.title, subtitle = c.item?.subtitle.orEmpty(), playing = playing,
            canSkip = c.hasList, isPhoto = c.kind == MediaKind.PHOTO, art = artBitmap, token = ms.sessionToken
        ))
    }

    // ---------- "Paylaş" ile gelen dosyalar ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<android.net.Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> (if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> return
        }
        // Link paylaşımı: TV'de açılır
        if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            intent.action = null
            val url = TvTools.extractUrl(text)
            if (url == null) { if (text.isNotBlank()) toast("Paylaşılan metinde link bulunamadı"); return }
            if (state == ConnState.CONNECTED) openSharedLink(url)
            else {
                pendingLink = url
                if (state != ConnState.CONNECTING) toast("Önce TV'ye bağlan; bağlanınca link TV'de açılacak")
            }
            return
        }
        intent.action = null      // aynı paylaşım ikinci kez işlenmesin
        if (uris.isEmpty()) return
        val items = uris.mapIndexed { i, u ->
            val mime = contentResolver.getType(u) ?: intent.type ?: ""
            val kind = when {
                mime.startsWith("image") -> MediaKind.PHOTO
                mime.startsWith("audio") -> MediaKind.MUSIC
                else -> MediaKind.VIDEO
            }
            MediaItem(id = -1L - i, uri = u, title = displayName(u) ?: kind.label, subtitle = "",
                mime = mime.ifBlank { kind.fallbackMime }, kind = kind)
        }
        castTabTick++
        if (state == ConnState.CONNECTED) playShared(items)
        else {
            pendingShare = items
            if (state != ConnState.CONNECTING) toast("Önce TV'ye bağlan; bağlanınca dosya TV'de açılacak")
        }
    }

    private var pendingLink: String? = null

    private fun openSharedLink(url: String) {
        scope.launch {
            val r = withContext(io) { runCatching { TvTools.openLink(url) } }
            toast(r.exceptionOrNull()?.message ?: "Link TV'de açılıyor")
        }
    }

    private fun playShared(items: List<MediaItem>) {
        playAlbumId = null
        playFromList(items, 0)
    }

    /** Cast sekmesi açılınca: TV'nin Chromecast'ine görünmeden bağlanır (TV ekranı değişmez). */
    private fun castWarmup() {
        if (!dlnaSearchedOnce) searchDlna(force = false)
        val ip = currentIp
        if (state != ConnState.CONNECTED || ip.isBlank()) return
        scope.launch(castIo) { runCatching { castClient.connect(ip) } }
    }

    /** Klasör/albüm listesindeki [index]. öğeyi oynatır. */
    private fun playFromList(list: List<MediaItem>, index: Int, route: CastRoute = selectedRoute) {
        if (list.isEmpty() || index !in list.indices) return
        playlist = list
        playIndex = index
        val item = list[index]
        castMedia(item.uri, item.kind, item.title, item.mime, route = route)
    }

    /** Önceki (-1) / sonraki (+1); liste sonunda başa, başında sona döner. */
    private fun castStep(dir: Int) {
        if (playlist.isEmpty()) return
        val n = playlist.size
        castStepTick++                     // galeri listesi yeni öğeyi takip etsin
        val next = if (shuffle && cast.kind == MediaKind.MUSIC && n > 2 && dir > 0) {
            // Karıştır: aynı şarkı art arda gelmesin
            (0 until n).filter { it != playIndex }.random()
        } else ((playIndex + dir) % n + n) % n
        playFromList(playlist, next, cast.route)
    }

    // ---------- DLNA ----------

    /** Kullanıcının başlattığı yeni yayın için yol: en son dokunulan bölüm. */
    private fun nextRoute(): CastRoute = selectedRoute

    /** Ağdaki DLNA oynatıcılarını arar; seçili cihaz yoksa kendiliğinden seçer. */
    private fun searchDlna(force: Boolean) {
        if (dlnaUi.searching) return
        if (!force && dlnaSearchedOnce) return
        dlnaSearchedOnce = true
        dlnaUi = dlnaUi.copy(searching = true)
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { dlnaClient.discover() }.getOrDefault(emptyList()) }
            val saved = prefs.getString("dlna_cihaz", null)
            val keep = dlnaUi.selected?.let { s -> found.firstOrNull { it.key == s.key } }
            val pick = keep
                ?: found.firstOrNull { it.key == saved }
                ?: found.firstOrNull { it.ip == currentIp }
                ?: found.firstOrNull()
            dlnaUi = DlnaUi(found, pick, searching = false)
            if (force && found.isEmpty()) toast("Ağda DLNA cihazı bulunamadı")
        }
    }

    private fun pickDlna(d: DlnaDevice) {
        prefs.edit().putString("dlna_cihaz", d.key).apply()
        dlnaUi = dlnaUi.copy(selected = d)
    }

    /** DLNA cihazının durumu sorulur; oynattıktan sonra "durdu" bildirimi, dosyanın bittiği anlamına gelir. */
    private suspend fun pollDlna() {
        val dev = dlnaUi.selected ?: return
        val st = withContext(castIo) { runCatching { dlnaClient.status(dev) }.getOrNull() } ?: return
        if (st.playerState == "PLAYING") dlnaSawPlaying = true
        val mapped = if (st.playerState == "IDLE")
            st.copy(idleReason = if (dlnaSawPlaying) "FINISHED" else "") else st
        if (mapped.idleReason == "FINISHED") dlnaSawPlaying = false
        onCastStatus(mapped)
    }

    /** Başka yola geçerken önceki yayını sessizce durdurur. */
    private fun stopRouteQuietly(route: CastRoute) {
        castPollJob?.cancel()
        scope.launch(castIo) { stopRouteBlocking(route) }
    }

    private fun stopRouteBlocking(route: CastRoute) {
        if (route == CastRoute.DLNA) {
            dlnaUi.selected?.let { d -> runCatching { dlnaClient.stop(d) } }
        } else {
            runCatching { castClient.stopApp() }
            runCatching { Thread.sleep(300); castClient.close() }
        }
    }

    // ---------- Biçim uyarısı ----------

    private fun likelyUnsupported(mime: String?, name: String?): Boolean {
        val m = mime.orEmpty().lowercase()
        val ext = name.orEmpty().substringAfterLast('.', "").lowercase()
        return m in setOf("video/x-matroska", "video/x-msvideo", "video/avi", "video/x-ms-wmv", "video/x-flv", "audio/x-ms-wma") ||
            ext in setOf("mkv", "avi", "wmv", "flv", "wma", "rmvb", "ts", "m2ts", "vob")
    }

    /** Uyarıdan sonra "Yine de dene": soru sormadan devam eder (kaldığı yer sorusu yine sorulur). */
    private fun continueCast(uri: android.net.Uri, kind: MediaKind, title: String?, mime: String?, route: CastRoute) {
        skipFormatCheck = true
        castMedia(uri, kind, title, mime, route = route)
    }
    private var skipFormatCheck = false

    // ---------- Altyazı ----------

    private fun subtitleKey(uri: android.net.Uri) = "altyazi_" + uri.toString().hashCode()

    /** Altyazı tuşu: yoksa dosya seç, varsa kaldır; ikisinde de video kaldığı yerden yeniden açılır. */
    private fun subtitleAction() {
        val uri = currentUri ?: return
        if (cast.hasSubtitle) {
            prefs.edit().remove(subtitleKey(uri)).apply()
            reloadCurrent()
        } else runCatching { pickSubtitle.launch(arrayOf("*/*")) }.onFailure { toast("Dosya seçici açılamadı") }
    }

    private fun onSubtitlePicked(sub: android.net.Uri) {
        val uri = currentUri ?: return
        runCatching { contentResolver.takePersistableUriPermission(sub, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putString(subtitleKey(uri), sub.toString()).apply()
        reloadCurrent()
    }

    private fun reloadCurrent() {
        val uri = currentUri ?: return
        val item = cast.item
        castMedia(uri, cast.kind, item?.title ?: cast.title, item?.mime, startAt = cast.position, askResume = false, route = cast.route)
    }

    // ---------- TV'yi uyandırma ----------

    /** TV uykudaysa uyandırır ve Chromecast'i hazır olana kadar bekler (en fazla ~15 sn). */
    private fun wakeTvIfAsleep(ip: String, waitForCast: Boolean = true) {
        val asleep = when (method) {
            ConnMethod.STANDARD -> atv.isOn == false
            ConnMethod.ADB -> adb.isScreenOn() == false
        }
        if (!asleep) return
        castStage("TV açılıyor…")
        runCatching { remote.key(KEYCODE_WAKEUP) }
        if (!waitForCast) { Thread.sleep(4000); return }
        castClient.close()
        for (i in 0 until 15) {
            Thread.sleep(1000)
            if (runCatching { castClient.connect(ip) }.isSuccess) return
        }
    }

    // ---------- Slayt gösterisi ----------

    private fun toggleSlideshow() {
        slideshow = !slideshow
        cast = cast.copy(slideshow = slideshow)
        if (slideshow) restartSlideTimer() else slideJob?.cancel()
    }

    private fun cycleSlideSec() {
        slideSec = when (slideSec) { 3 -> 5; 5 -> 10; else -> 3 }
        prefs.edit().putInt("slayt_sn", slideSec).apply()
        cast = cast.copy(slideSec = slideSec)
        if (slideshow) restartSlideTimer()
    }

    /** Her fotoğrafta sayaç baştan başlar; önceki/sonraki tuşu slaytı bozmaz. */
    private fun restartSlideTimer() {
        slideJob?.cancel()
        slideJob = scope.launch {
            delay(slideSec * 1000L)
            if (slideshow && cast.kind == MediaKind.PHOTO && playlist.size > 1) castStep(1)
        }
    }

    // ---------- Kaldığın yerden devam ----------

    private fun resumeKey(uri: android.net.Uri) = "konum_" + uri.toString().hashCode()

    /** 1 dakikadan uzun, 30 sn'den fazla izlenmiş ve sonuna 30 sn'den fazla kalmış video hatırlanır. */
    private fun saveResumePosition() {
        val uri = currentUri ?: return
        if (cast.kind != MediaKind.VIDEO) return
        val pos = cast.position
        val dur = cast.duration
        val e = prefs.edit()
        if (dur > 60 && pos > 30 && pos < dur - 30) e.putFloat(resumeKey(uri), pos.toFloat())
        else if (dur > 0 && pos >= dur - 30) e.remove(resumeKey(uri))
        e.apply()
    }

    private fun formatTime(sec: Double): String {
        val t = sec.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val r = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    /** Şarkı/video bitince ne olacağı (müzikte tekrar moduna göre). */
    private fun onMediaFinished() {
        currentUri?.let { prefs.edit().remove(resumeKey(it)).apply() }   // sonuna kadar izlendi
        currentUri = null
        autoAdvancing = true
        if (cast.kind == MediaKind.MUSIC) {
            when {
                repeatMode == MusicRepeat.ONE -> { playFromList(playlist.ifEmpty { return }, playIndex.coerceAtLeast(0)); return }
                repeatMode == MusicRepeat.OFF && !shuffle && playIndex >= playlist.size - 1 -> {
                    cast = cast.copy(phase = CastPhase.ENDED); castPollJob?.cancel(); return
                }
            }
        }
        if (playlist.size > 1) castStep(1) else cast = cast.copy(phase = CastPhase.ENDED)
    }

    private fun cycleRepeat() {
        repeatMode = when (repeatMode) { MusicRepeat.OFF -> MusicRepeat.ALL; MusicRepeat.ALL -> MusicRepeat.ONE; MusicRepeat.ONE -> MusicRepeat.OFF }
        prefs.edit().putString("tekrar", repeatMode.name).apply()
        cast = cast.copy(repeat = repeatMode)
        toast(when (repeatMode) { MusicRepeat.OFF -> "Tekrar kapalı"; MusicRepeat.ALL -> "Listeyi tekrarla"; MusicRepeat.ONE -> "Bu şarkıyı tekrarla" })
    }

    private fun toggleShuffle() {
        shuffle = !shuffle
        prefs.edit().putBoolean("karistir", shuffle).apply()
        cast = cast.copy(shuffle = shuffle)
        toast(if (shuffle) "Karıştır açık" else "Karıştır kapalı")
    }

    /** 10 saniye ileri/geri; videonun başı ve sonunda durur. */
    private fun castSkip(delta: Double) {
        val max = if (cast.duration > 0) cast.duration else Double.MAX_VALUE
        castSeek((cast.position + delta).coerceIn(0.0, max))
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** İlk yayında bildirim izni istenir (ekran kapalıyken yayının sürdüğünü gösteren bildirim için). */
    private fun askNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun castError(e: Exception): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "TV'nin Chromecast'ine bağlanılamadı (TV'de Chromecast açık mı?)"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Oynatırken TV'den saniyede bir konum bilgisi istenir. */
    private fun startCastPolling() {
        castPollJob?.cancel()
        castPollJob = scope.launch {
            while (isActive) {
                delay(1000)
                // DLNA cihazları kendiliğinden durum bildirmez; "hazırlanıyor" aşamasında da sorulur
                val dlnaPreparing = cast.route == CastRoute.DLNA && cast.phase == CastPhase.PREPARING
                if (dlnaPreparing || cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING || cast.phase == CastPhase.PAUSED) {
                    if (cast.route == CastRoute.DLNA) pollDlna() else withContext(castIo) { castClient.refresh() }
                    if (++saveCounter % 10 == 0) saveResumePosition()   // 10 sn'de bir konum kaydı
                }
            }
        }
    }

    private fun onCastStatus(st: CastClient.Status) {
        if (cast.phase == CastPhase.NONE) return
        // Fotoğraf gösterilirken alıcı durumu "duraklatılmış" ya da "boşta" bildirebilir
        if (cast.kind == MediaKind.PHOTO && st.idleReason != "ERROR") {
            cast = cast.copy(phase = CastPhase.PLAYING)
            return
        }
        // Yeni dosya yüklenirken önceki dosyanın "bitti/kesildi" bildirimleri yok sayılır
        if (cast.phase == CastPhase.PREPARING && st.playerState == "IDLE") return
        // Bitince sıradaki kendiliğinden başlar (liste sonunda başa döner)
        if (st.playerState == "IDLE" && st.idleReason == "FINISHED" &&
            (playlist.size > 1 || (cast.kind == MediaKind.MUSIC && repeatMode == MusicRepeat.ONE && playlist.isNotEmpty()))) {
            onMediaFinished()
            return
        }
        val phase = when (st.playerState) {
            "PLAYING" -> CastPhase.PLAYING
            "PAUSED" -> CastPhase.PAUSED
            "BUFFERING", "LOADING" -> CastPhase.BUFFERING
            else -> when (st.idleReason) {
                "FINISHED" -> CastPhase.ENDED
                "ERROR" -> CastPhase.ERROR
                "CANCELLED", "INTERRUPTED" -> CastPhase.ENDED
                // Fotoğraflar gösterilirken alıcı oynatıcıyı boşta bildirebilir
                else -> if (cast.kind == MediaKind.PHOTO && cast.phase != CastPhase.PREPARING) CastPhase.PLAYING else cast.phase
            }
        }
        cast = cast.copy(
            phase = phase,
            position = st.currentTime,
            duration = if (st.duration > 0) st.duration else cast.duration,
            error = if (phase == CastPhase.ERROR) "TV dosyayı oynatamadı (biçim desteklenmiyor olabilir)" else ""
        )
        if (phase == CastPhase.ENDED || phase == CastPhase.ERROR) {
            castPollJob?.cancel()
            CastService.stop(this)
        }
    }

    private fun onCastClosed(reason: String) {
        castPollJob?.cancel()
        CastService.stop(this)
        if (cast.phase != CastPhase.NONE && cast.phase != CastPhase.ENDED && cast.phase != CastPhase.ERROR) {
            cast = cast.copy(phase = CastPhase.ERROR, error = "Chromecast bağlantısı koptu")
        }
    }

    private fun castToggle() {
        val playing = cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING
        if (cast.phase == CastPhase.ENDED) { castSeek(0.0); return }
        cast = cast.copy(phase = if (playing) CastPhase.PAUSED else CastPhase.PLAYING)
        castCommand({ if (playing) pause() else play() }, { d -> if (playing) pause(d) else play(d) })
    }

    private fun castSeek(seconds: Double) {
        cast = cast.copy(position = seconds)
        castCommand({ seek(seconds) }, { d -> seek(d, seconds) })
    }

    private fun castStop() {
        val stoppedRoute = cast.route
        saveResumePosition()
        currentUri = null
        slideshow = false
        slideJob?.cancel()
        castPollJob?.cancel()
        cast = CastUi()
        playlist = emptyList()
        playIndex = -1
        playAlbumId = null
        CastService.stop(this)
        scope.launch(castIo) {
            // TV'deki oynatıcı da kapatılır; bekleme ekranı kalmaz
            stopRouteBlocking(stoppedRoute)
            mediaServer.stop()
        }
    }

    private fun castCommand(action: CastClient.() -> Unit, dlnaAction: DlnaClient.(DlnaDevice) -> Unit = {}) {
        val route = cast.route
        scope.launch {
            try {
                withContext(castIo) {
                    if (route == CastRoute.DLNA) dlnaUi.selected?.let { dlnaClient.dlnaAction(it) }
                    else castClient.action()
                }
                if (castPollJob?.isActive != true) startCastPolling()
            } catch (e: Exception) {
                toast("Cast: ${e.message ?: e.javaClass.simpleName}")
            }
        }
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
    private val KEYCODE_WAKEUP = 224

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
        saveResumePosition()
        castPollJob?.cancel()
        slideJob?.cancel()
        CastService.stop(this)
        Thread { adb.close(); atv.release(); io.close(); castClient.close(); mediaServer.stop(); castIo.close() }.start()
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
    val typingHint: String = "",
    /** Artınca alt menü Cast sekmesine geçer (Paylaş ile dosya gelince). */
    val castTabTick: Int = 0
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
