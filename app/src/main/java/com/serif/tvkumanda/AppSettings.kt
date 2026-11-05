package com.serif.tvkumanda

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Arka plan temaları (Serif'in isteği, 2 Kasım 2026): yalnızca zemin renklerini değiştirir; tuşlar, mor vurgular ve yazılar
 * aynı kalır. Hepsi koyu, çünkü yazılar ve cam kartlar koyu zemine göre tasarlandı.
 * bgTop/bgMid/bgBottom/glow: ana sekmelerin arka planı; page/card/cardPressed: alt sayfalar; panel/panelTop: TV paneli, pencereler.
 */
enum class AppTheme(
    val label: String,
    val bgTop: Color, val bgMid: Color, val bgBottom: Color, val glow: Color,
    val page: Color, val card: Color, val cardPressed: Color,
    val panel: Color, val panelTop: Color
) {
    MOR("Mor",
        Color(0xFF1B1A3C), Color(0xFF15162F), Color(0xFF111226), Color(0xFF6A4FE0),
        Color(0xFF15162F), Color(0xFF23244A), Color(0xFF2E2F5C),
        Color(0xFF1C1B40), Color(0xFF2A2656)),
    LACIVERT("Lacivert",
        Color(0xFF0F2140), Color(0xFF0B1A33), Color(0xFF081326), Color(0xFF2F6BE0),
        Color(0xFF0B1A33), Color(0xFF172A4A), Color(0xFF20365C),
        Color(0xFF13274A), Color(0xFF1E3662)),
    GECE("Gece",
        Color.Black, Color.Black, Color.Black, Color.Transparent,
        Color.Black, Color(0xFF1A1A1C), Color(0xFF2A2A2D),
        Color(0xFF141416), Color(0xFF222226)),
    ORMAN("Orman",
        Color(0xFF12301F), Color(0xFF0E2619), Color(0xFF0A1C13), Color(0xFF2FA866),
        Color(0xFF0E2619), Color(0xFF1B3A29), Color(0xFF254A36),
        Color(0xFF143424), Color(0xFF20462F)),
    BORDO("Bordo",
        Color(0xFF3A1420), Color(0xFF2E1019), Color(0xFF220C13), Color(0xFFC93A5A),
        Color(0xFF2E1019), Color(0xFF46202C), Color(0xFF572A38),
        Color(0xFF3D1623), Color(0xFF542232))
}

/**
 * Uygulama ayarları (sol üstteki ⚙️ düğmesiyle açılan sayfa). Telefonda "ayarlar" tercihlerinde saklanır.
 * Değerler Compose durumu: değişince ilgili ekran kendiliğinden güncellenir.
 */
object AppSettings {
    /** Kumanda tuşlarına basınca kısa titreşim (varsayılan kapalı). */
    var haptic by mutableStateOf(false)
        private set
    /** Titreşim gücü: LOW, MID (varsayılan) ya da HIGH. */
    var hapticLevel by mutableStateOf("MID")
        private set
    /** Telefonun ses tuşları TV'nin sesini değiştirir (varsayılan açık, eski davranış). */
    var volumeKeys by mutableStateOf(true)
        private set
    /** Telefon ekranı kararmasın: OFF (varsayılan), REMOTE (yalnızca Kumanda sekmesinde) ya da ALL (tüm sekmelerde). */
    var keepScreen by mutableStateOf("OFF")
        private set
    /** Uygulama açılınca son TV'ye kendiliğinden bağlanır (varsayılan açık, eski davranış). */
    var autoConnect by mutableStateOf(true)
        private set
    /** Açılış sekmesi: REMOTE, CAST, TOOLS ya da LAST (son kalınan). */
    var startTab by mutableStateOf("REMOTE")
        private set
    private var lastTab = "REMOTE"
    /** Alt menüden kaldırılan sekmeler (Serif'in isteği, 2 Kasım 2026). En az bir sekme hep açık kalır. */
    var hiddenTabs by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Cast'te son cihaz: NONE hatırlama, TOP listede başta göster (varsayılan), AUTO Cast'e girince kendiliğinden bağlan. */
    var castRemember by mutableStateOf("TOP")
        private set
    /** Arka plan teması (varsayılan Mor, eski görünüm). */
    var theme by mutableStateOf(AppTheme.MOR)
        private set
    /** Cast'te son kullanılan internet adresleri. */
    var castUrls by mutableStateOf<List<String>>(emptyList())
        private set
    /** Kumandada yön tuşları yerine kaydırma yüzeyi (TV adının yanındaki ⌄ menüsünden seçilir, son seçim hatırlanır). */
    var swipeControl by mutableStateOf(false)
        private set
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(c)
        haptic = p.getBoolean("titresim", false)
        hapticLevel = p.getString("titresim_guc", "MID") ?: "MID"
        volumeKeys = p.getBoolean("ses_tuslari", true)
        // Eski açık/kapalı ayarı yeni seçeneğe taşınır: açıksa "yalnızca Kumanda" olarak devam eder
        keepScreen = p.getString("ekran_kararma", null) ?: if (p.getBoolean("ekran_acik", false)) "REMOTE" else "OFF"
        autoConnect = p.getBoolean("oto_baglan", true)
        startTab = p.getString("acilis_sekme", "REMOTE") ?: "REMOTE"
        lastTab = p.getString("son_sekme", "REMOTE") ?: "REMOTE"
        hiddenTabs = p.getStringSet("gizli_sekmeler", emptySet())?.toSet().orEmpty()
        castRemember = p.getString("cast_hatirla", "TOP") ?: "TOP"
        castUrls = p.getString("cast_url_gecmis", "").orEmpty().split('\n').filter { it.isNotBlank() }
        theme = runCatching { AppTheme.valueOf(p.getString("tema", null) ?: "MOR") }.getOrDefault(AppTheme.MOR)
        swipeControl = p.getBoolean("kaydirma_kontrol", false)
    }

    fun setSwipeControl(c: Context, v: Boolean) { swipeControl = v; prefs(c).edit().putBoolean("kaydirma_kontrol", v).apply() }

    fun setHaptic(c: Context, v: Boolean) { haptic = v; prefs(c).edit().putBoolean("titresim", v).apply(); if (v) tap(c) }
    fun setVolumeKeys(c: Context, v: Boolean) { volumeKeys = v; prefs(c).edit().putBoolean("ses_tuslari", v).apply() }
    fun setKeepScreen(c: Context, v: String) { keepScreen = v; prefs(c).edit().putString("ekran_kararma", v).apply() }
    fun setHapticLevel(c: Context, v: String) { hapticLevel = v; prefs(c).edit().putString("titresim_guc", v).apply(); tap(c) }
    fun setAutoConnect(c: Context, v: Boolean) { autoConnect = v; prefs(c).edit().putBoolean("oto_baglan", v).apply() }
    fun setCastRemember(c: Context, v: String) { castRemember = v; prefs(c).edit().putString("cast_hatirla", v).apply() }
    fun addCastUrl(c: Context, url: String) = saveCastUrls(c, CastUrl.remember(castUrls, url))
    fun removeCastUrl(c: Context, url: String) = saveCastUrls(c, castUrls - url)
    private fun saveCastUrls(c: Context, list: List<String>) {
        castUrls = list
        prefs(c).edit().putString("cast_url_gecmis", list.joinToString("\n")).apply()
    }
    fun setTheme(c: Context, v: AppTheme) { theme = v; prefs(c).edit().putString("tema", v.name).apply() }
    fun setStartTab(c: Context, v: String) { startTab = v; prefs(c).edit().putString("acilis_sekme", v).apply() }

    /** Alt menüde görünen sekmeler, sırasıyla (hiçbiri kalmazsa Kumanda). */
    fun visibleTabs(): List<Tab> = Tab.values().filter { it.name !in hiddenTabs }.ifEmpty { listOf(Tab.REMOTE) }

    fun isTabVisible(t: Tab): Boolean = t in visibleTabs()

    /** Sekmeyi alt menüde gösterir ya da gizler; son açık sekme kapatılamaz. */
    fun setTabVisible(c: Context, t: Tab, v: Boolean) {
        val next = if (v) hiddenTabs - t.name else hiddenTabs + t.name
        if (Tab.values().all { it.name in next }) return
        hiddenTabs = next
        prefs(c).edit().putStringSet("gizli_sekmeler", next).apply()
    }

    /** Uygulama açılınca gösterilecek sekme; gizlenmişse açık kalan ilk sekme. */
    fun initialTab(): Tab {
        val name = if (startTab == "LAST") lastTab else startTab
        val t = runCatching { Tab.valueOf(name) }.getOrDefault(Tab.REMOTE)
        return if (isTabVisible(t)) t else visibleTabs().first()
    }

    /** Sekme değişince "son kalınan" olarak saklanır. */
    fun rememberTab(c: Context, tab: Tab) {
        if (lastTab == tab.name) return
        lastTab = tab.name
        prefs(c).edit().putString("son_sekme", tab.name).apply()
    }

    private var vibrator: Vibrator? = null

    /** Ayar açıksa kısa bir tık titreşimi. Telefonun "dokunma titreşimi" ayarından bağımsız çalışır. */
    fun tapIfEnabled(c: Context) { if (haptic) tap(c) }

    private fun tap(c: Context) {
        runCatching {
            val v = vibrator ?: (if (Build.VERSION.SDK_INT >= 31)
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)).also { vibrator = it }
            if (!v.hasVibrator()) return
            // Hazır tık efektleri bazı telefonlarda birbirine çok benzer; süre ve güç elle verilince fark her telefonda hissedilir
            val (ms, strength) = when (hapticLevel) {
                "LOW" -> 15L to 70
                "HIGH" -> 45L to 255
                else -> 25L to 150
            }
            v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

/** Tam ekran Ayarlar sayfası. */
@Composable
fun SettingsPage(ui: UiState, onOpenTvs: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MouseSettings.load(context) }
    // Hakkında → Sıkça sorulan sorular (tam sayfa, Ayarlar'ın üstünde açılır)
    var faqOpen by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var licenseOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var crashOpen by remember { mutableStateOf(false) }
    var crashes by remember { mutableStateOf(CrashLog.entries()) }
    // Güncelleme denetimi: yalnızca dokununca çalışır
    val scope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheck.Result?>(null) }
    BackHandler(enabled = !faqOpen && !licenseOpen && !aboutOpen, onBack = onClose)

    Box(
        Modifier.fillMaxSize().background(Pal.Bg)
            // Arkadaki ekrana dokunulmasın
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // ---- Üst şerit ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Ayarlar", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            // ---- Kumanda ----
            SettingsHeader("Kumanda")
            SettingsGroup {
                SettingsRow(Icons.Outlined.Vibration, "Tuş titreşimi", "Kumanda tuşlarına basınca telefon hafifçe titrer") {
                    SettingsSwitch(AppSettings.haptic) { AppSettings.setHaptic(context, it) }
                }
                if (AppSettings.haptic) {
                    Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                        Segmented(
                            listOf("LOW" to "Hafif", "MID" to "Orta", "HIGH" to "Güçlü"), AppSettings.hapticLevel
                        ) { AppSettings.setHapticLevel(context, it) }
                    }
                }
                SettingsRow(Icons.Outlined.VolumeUp, "Ses tuşları TV'yi kontrol etsin", "Kapalıyken telefonun kendi sesi değişir") {
                    SettingsSwitch(AppSettings.volumeKeys) { AppSettings.setVolumeKeys(context, it) }
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LightMode, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Ekran kararmasın", color = Pal.Text, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        listOf("OFF" to "Kapalı", "REMOTE" to "Kumanda", "ALL" to "Tüm sekmeler"), AppSettings.keepScreen
                    ) { AppSettings.setKeepScreen(context, it) }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Fare ----
            SettingsHeader("Fare (ADB)")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mouse, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("İmleç hızı", color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(MouseSettings.speedNames[MouseSettings.speed - 1], color = Pal.Blue, fontSize = 13.sp)
                    }
                    Slider(
                        value = MouseSettings.speed.toFloat(),
                        onValueChange = { MouseSettings.updateSpeed(context, Math.round(it)) },
                        valueRange = 1f..5f, steps = 3,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.Bg)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Kaydırma şeridi", color = Pal.Text, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Segmented(
                        listOf(true to "Sol", false to "Sağ"), MouseSettings.stripLeft
                    ) { MouseSettings.updateStripLeft(context, it) }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Bağlantı ----
            SettingsHeader("Bağlantı")
            SettingsGroup {
                SettingsRow(Icons.Outlined.Link, "Açılışta son TV'ye bağlan", "Kapalıyken TV'yi sen seçince bağlanır") {
                    SettingsSwitch(AppSettings.autoConnect) { AppSettings.setAutoConnect(context, it) }
                }
                val method = if (ui.method == ConnMethod.ADB) "ADB" else "Standart"
                val tv = if (ui.state == ConnState.CONNECTED) ui.tvName.ifBlank { "TV" } else "Bağlı değil"
                SettingsRow(Icons.Outlined.Tv, "TV'ler ve bağlantı türü", "$tv · $method", onClick = onOpenTvs) {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Uygulama ----
            SettingsHeader("Uygulama")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Home, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Açılış sekmesi", color = Pal.Text, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    // Yalnızca alt menüde açık olan sekmeler seçilebilir
                    val startOptions = listOf("REMOTE" to "Kumanda", "CAST" to "Cast", "TOOLS" to "Araçlar")
                        .filter { (k, _) -> AppSettings.isTabVisible(Tab.valueOf(k)) } + ("LAST" to "Son kalınan")
                    val shownStart = AppSettings.startTab.takeIf { s -> startOptions.any { it.first == s } } ?: AppSettings.initialTab().name
                    Segmented(startOptions, shownStart) { AppSettings.setStartTab(context, it) }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Palette, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Arka plan teması", color = Pal.Text, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    ThemePicker(AppSettings.theme) { AppSettings.setTheme(context, it) }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Cast ----
            SettingsHeader("Cast")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Cast, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Son cihazı hatırla", color = Pal.Text, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        listOf("NONE" to "Hatırlama", "TOP" to "Başta göster", "AUTO" to "Otomatik"),
                        AppSettings.castRemember
                    ) { AppSettings.setCastRemember(context, it) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (AppSettings.castRemember) {
                            "NONE" -> "Her seferinde cast düğmesiyle arayıp seçersin."
                            "AUTO" -> "Cast ekranına girince ya da bir şey oynatınca son cihaz ağdaysa ona bağlanılır."
                            else -> "Son kullandığın cihaz taramada listenin en üstünde çıkar."
                        },
                        color = Pal.Sub, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---- Alt menü ----
            SettingsHeader("Alt menü")
            SettingsGroup {
                val onlyOne = AppSettings.visibleTabs().size == 1
                for ((t, label, icon) in listOf(
                    Triple(Tab.REMOTE, "Kumanda", Icons.Outlined.Tv),
                    Triple(Tab.CAST, "Cast", Icons.Outlined.Cast),
                    Triple(Tab.TOOLS, "Araçlar", Icons.Outlined.Build),
                    Triple(Tab.SHORTCUTS, "Kısayollar", Icons.Outlined.Bolt)
                )) {
                    val on = AppSettings.isTabVisible(t)
                    val locked = on && onlyOne
                    SettingsRow(
                        icon, label,
                        when {
                            locked -> "En az bir sekme açık kalmalı"
                            on -> "Alt menüde görünür"
                            else -> "Gizli"
                        }
                    ) {
                        SettingsSwitch(on, enabled = !locked) { AppSettings.setTabVisible(context, t, it) }
                    }
                }
            }
            Text(
                "Tek sekme açık kalınca alt menü gizlenir.",
                color = Pal.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
            Spacer(Modifier.height(24.dp))

            // ---- Hakkında ----
            SettingsHeader("Hakkında")
            SettingsGroup {
                SettingsRow(Icons.Outlined.Info, "TV Kumanda", "Sürüm $version", onClick = { aboutOpen = true }) {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                }
                val update = updateResult
                SettingsRow(
                    Icons.Outlined.SystemUpdate,
                    if (update is UpdateCheck.Result.Available) "Yeni sürüm var: ${update.version}" else "Güncellemeleri denetle",
                    when {
                        updateBusy -> "Denetleniyor…"
                        update is UpdateCheck.Result.Available -> "İndirme sayfasını açmak için dokun"
                        update is UpdateCheck.Result.UpToDate -> "En son sürümü kullanıyorsun"
                        update is UpdateCheck.Result.Failed -> update.message
                        else -> "GitHub'daki son sürüme bakar"
                    },
                    onClick = {
                        if (update is UpdateCheck.Result.Available) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        } else if (!updateBusy) {
                            updateBusy = true
                            scope.launch {
                                updateResult = withContext(Dispatchers.IO) { UpdateCheck.check(version) }
                                updateBusy = false
                            }
                        }
                    }
                ) {
                    if (update is UpdateCheck.Result.Available) {
                        Icon(Icons.Outlined.OpenInNew, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                    }
                }
                SettingsRow(Icons.Outlined.HelpOutline, "Sıkça sorulan sorular", "Bağlantı, kumanda, Cast ve daha fazlası", onClick = { faqOpen = true }) {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                }
                SettingsRow(Icons.Outlined.BugReport, "Çökme kaydı",
                    if (crashes.isEmpty()) "Kayıtlı çökme yok" else "${crashes.size} kayıt · son: ${CrashLog.timeOf(crashes.first())}",
                    onClick = if (crashes.isEmpty()) null else ({ crashOpen = true })
                ) {
                    if (crashes.isNotEmpty()) Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                }
                SettingsRow(Icons.Outlined.Gavel, "Lisans", "GPL-3.0 (özgür yazılım)", onClick = { licenseOpen = true }) {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                }
                SettingsRow(Icons.Outlined.Code, "GitHub sayfası", "github.com/serif453/TvKumanda", onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/serif453/TvKumanda")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Icon(Icons.Outlined.OpenInNew, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (faqOpen) FaqPage { faqOpen = false }
        // Lisans, Hakkında sayfasından da açılır: üstte çizilir ve geri tuşu önce onu kapatır
        if (aboutOpen) AboutPage(version, onOpenLicense = { licenseOpen = true }, onClose = { aboutOpen = false })
        if (licenseOpen) LicensePage { licenseOpen = false }
        if (crashOpen) CrashLogDialog(
            crashes,
            onClear = { CrashLog.clear(); crashes = emptyList(); crashOpen = false },
            onClose = { crashOpen = false }
        )
    }
}

/** Çökme kaydı penceresi: metni gösterir, tek dokunuşla kopyalar. */
@Composable
private fun CrashLogDialog(entries: List<String>, onClear: () -> Unit, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = remember(entries) { CrashLog.export(entries) }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = AppSettings.theme.panel,
        title = { Text("Çökme kaydı", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                text, color = Pal.Sub, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
                Text(if (copied) "Kopyalandı" else "Kopyala", color = Pal.Blue)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Sil", color = Pal.Sub) }
                TextButton(onClick = onClose) { Text("Kapat", color = Pal.Text) }
            }
        }
    )
}

@Composable
private fun SettingsHeader(text: String) {
    Text(text, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn), content = content)
}

@Composable
private fun SettingsRow(
    icon: ImageVector, title: String, subtitle: String,
    onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Pal.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun SettingsSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Pal.Green,
            uncheckedThumbColor = Pal.Sub, uncheckedTrackColor = Pal.Bg, uncheckedBorderColor = Pal.Sub.copy(alpha = 0.5f)
        )
    )
}

/** Temalar yan yana: renginden küçük bir daire ve adı; seçili olanın çevresi mavi, içinde tik. */
@Composable
private fun ThemePicker(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (t in AppTheme.values()) {
            val sel = t == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(t.panelTop, t.bgBottom)))
                        .border(if (sel) 2.dp else 1.dp, if (sel) Pal.Blue else Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (sel) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.label, color = if (sel) Pal.Text else Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Yan yana seçenekler; seçili olan mavi. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((v, label) in options) {
            val sel = v == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pal.Blue else Color.Transparent)
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
