package com.serif.tvkumanda

import androidx.compose.material.icons.outlined.Input

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// ---------- Renkler ----------
object Pal {
    // Alt sayfaların zemini ve kartları seçili temadan gelir
    val Bg: Color get() = AppSettings.theme.page
    val Btn: Color get() = AppSettings.theme.card
    val BtnPressed: Color get() = AppSettings.theme.cardPressed
    val Pad: Color get() = AppSettings.theme.card
    val Text = Color(0xFFF5F5F7)
    val Sub = Color(0xFF9A9AA2)
    val Purple = Color(0xFF8E5CF7)
    val Blue = Color(0xFF4A7CF5)
    val Green = Color(0xFF4CD964)
    val Amber = Color(0xFFFFC542)
    val Red = Color(0xFFFF5A5F)
    val Level = Color(0xFF4A7FB5)
}

val RingBrush = Brush.linearGradient(listOf(Pal.Purple, Pal.Blue))

/** Kumanda sekmesinin lacivert teması (şimdilik yalnızca Kumanda'da). */
object Navy {
    val Bg: Color get() = AppSettings.theme.page
    val Card: Color get() = AppSettings.theme.panel
    val Btn: Color get() = AppSettings.theme.card
    val BtnPressed: Color get() = AppSettings.theme.cardPressed
    val Border = Color.White.copy(alpha = 0.10f)
    val Accent = Color(0xFF5B7CFF)
    val AccentSoft = Color(0xFF1C2A5C)
    val Sub = Color(0xFF9AA3BF)
    val PowerBg = Color(0xFF2A1418)
}

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Blue,
            background = Pal.Bg,
            surface = Pal.Bg,
            surfaceContainerLow = AppSettings.theme.panel,
            onSurface = Pal.Text,
            onBackground = Pal.Text
        ),
        content = content
    )
}

private enum class Sheet { NONE, KEYBOARD, NUMPAD, EDIT_APPS }

// ---------- Ana ekran ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    ui: UiState,
    initialIp: String,
    onConnect: (String) -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onMouse: (String) -> Unit,
    onLaunch: (TvApp) -> Unit,
    onMethod: (ConnMethod) -> Unit,
    onPairCode: (String) -> Unit,
    onPairCancel: () -> Unit,
    onPickSaved: (SavedTv) -> Unit,
    onForgetSaved: (SavedTv) -> Unit,
    onRenameSaved: (SavedTv, String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onSeekTv: (Long) -> Unit = {},
    onWake: (SavedTv) -> Unit = {},
    onMic: () -> Unit,
    onOpenTyping: () -> Unit,
    onCloseTyping: () -> Unit,
    onLiveText: (String) -> Unit,
    onTypingSubmit: () -> Unit,
    onTvSearch: () -> Unit,
    cast: CastUi = CastUi(),
    onCastPick: (MediaKind) -> Unit = {},
    onCastItem: (MediaItem, List<MediaItem>, Long, Double) -> Unit = { _, _, _, _ -> },
    onCastToggle: () -> Unit = {},
    onCastSeek: (Double) -> Unit = {},
    onCastStop: () -> Unit = {},
    onCastPrev: () -> Unit = {},
    onCastNext: () -> Unit = {},
    onCastSkip: (Double) -> Unit = {},
    onCastWarmup: () -> Unit = {},
    onCastUrl: (String) -> Unit = {},
    onCastVolume: (Float) -> Unit = {},
    onCastPlayAt: (Int) -> Unit = {},
    onCastRepeat: () -> Unit = {},
    onCastShuffle: () -> Unit = {},
    onCastSlideshow: () -> Unit = {},
    onCastSlideSec: () -> Unit = {},
    onCastSubtitle: () -> Unit = {},
    dlna: DlnaUi = DlnaUi(),
    onCastRoute: (CastRoute) -> Unit = {},
    onDlnaPick: (DlnaDevice) -> Unit = {},
    onDlnaSearch: () -> Unit = {},
    cc: CcUi = CcUi(),
    onCcPick: (FoundTv?) -> Unit = {},
    onCcSearch: () -> Unit = {}
) {
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var tab by remember { mutableStateOf(AppSettings.initialTab()) }
    // Sol üstteki ⚙️ ile açılan tam ekran Ayarlar sayfası
    var settingsOpen by remember { mutableStateOf(false) }
    // TV adı kutusunun altından açılan küçük TV paneli ve onun ekrandaki yeri (üst şeridin alt kenarı)
    // İlk açılışta TV yoksa panel doğrudan büyük açılır (eski alttan pencerenin yerine)
    var pickerOpen by remember { mutableStateOf(initialIp.isBlank()) }
    var pickerExpanded by remember { mutableStateOf(initialIp.isBlank()) }
    var navTop by remember { mutableFloatStateOf(0f) }
    var rootH by remember { mutableFloatStateOf(0f) }
    val openTvs = { pickerExpanded = true; pickerOpen = true }
    var rootTop by remember { mutableFloatStateOf(0f) }
    var barBottom by remember { mutableFloatStateOf(0f) }
    // Kumanda: yön tuşu (0), kaydırma (1), fare (2)
    var padMode by remember { mutableIntStateOf(0) }
    var appRowTick by remember { mutableIntStateOf(0) }
    // Kumandadaki fn ve medya tuşlarının açtığı Araçlar pencereleri
    var remoteTool by remember { mutableStateOf<ToolId?>(null) }
    LaunchedEffect(ui.method) { if (ui.method != ConnMethod.ADB && padMode == 2) padMode = 0 }
    // "Paylaş" ile dosya gelince Cast sekmesine geçilir
    LaunchedEffect(ui.castTabTick) { if (ui.castTabTick > 0) tab = Tab.CAST }
    // Alt menüdeki sekmeler (Ayarlar → Alt menü). Gizli bir sekmedeyken (ör. paylaşımla açılan Cast) o sekme
    // o seferlik menüde görünür; başka sekmeye geçince yine kaybolur.
    val visibleTabs = AppSettings.visibleTabs()
    val navTabs = if (tab in visibleTabs) visibleTabs else (visibleTabs + tab).sortedBy { it.ordinal }
    val showNav = navTabs.size > 1
    SideEffect { NavBar.visible = showNav }
    // Ayarlardan şu anki sekme gizlenirse açık kalan ilk sekmeye geçilir
    // (yalnızca ayar değişince; açılışta paylaşımla gelen geçici Cast'i bozmasın)
    var seenHidden by remember { mutableStateOf(AppSettings.hiddenTabs) }
    LaunchedEffect(AppSettings.hiddenTabs) {
        if (AppSettings.hiddenTabs == seenHidden) return@LaunchedEffect
        seenHidden = AppSettings.hiddenTabs
        if (tab !in AppSettings.visibleTabs()) tab = AppSettings.visibleTabs().first()
    }
    LaunchedEffect(tab) { LiveUi.remoteTabOpen = tab == Tab.REMOTE }
    val tabContext = LocalContext.current
    LaunchedEffect(tab) { AppSettings.rememberTab(tabContext, tab) }
    // Ayara göre telefon ekranı kararmaz: yalnızca Kumanda sekmesinde ya da tüm sekmelerde
    val keepOn = AppSettings.keepScreen == "ALL" || (AppSettings.keepScreen == "REMOTE" && tab == Tab.REMOTE)
    DisposableEffect(keepOn) {
        val window = (tabContext as? android.app.Activity)?.window
        if (keepOn) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Oynatıcı yalnızca görünüp kaybolunca bütün ekran yeniden düzenlenir; içerik değişince sadece çubuk çizilir
    val npVisible by remember { derivedStateOf { LiveUi.nowPlaying != null } }
    // Cast: tam ekran oynatıcı ve "klasörde göster" isteği
    val context = LocalContext.current
    val mediaLibrary = remember { MediaLibrary.get(context) }
    val playerSheet = rememberPlayerSheet()
    var showInFolderTick by remember { mutableStateOf(0) }
    // Yayın bitince ya da Cast sekmesinden çıkınca tam ekran oynatıcı gizlenir
    LaunchedEffect(cast.phase == CastPhase.NONE, tab) {
        if (cast.phase == CastPhase.NONE || tab != Tab.CAST) playerSheet.hideNow()
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.state) {
        // Cast kendi cihazını seçer, kumanda bağlantısına bağlı değil: orada TV paneli kendiliğinden açılmaz
        if (ui.state == ConnState.ERROR && tab != Tab.CAST) openTvs()
        if (ui.state == ConnState.CONNECTED && pickerOpen && pickerExpanded) pickerOpen = false
    }

    val hazeState = remember { HazeState() }
    val typingOpen = ui.typing && ui.method == ConnMethod.STANDARD && ui.state == ConnState.CONNECTED
    // Ortak tasarım dili (Serif, 2 Kasım 2026): tüm sekmelerde cam tasarımın mor ışıklı arka planı
    val bgMod = Modifier.glassBackdrop()
    // Arka plan içerikte (Column) bir kez çizilir; dış katman yalnızca yazma ekranında bulanıklaşan kenarların arkasını doldurur
    Box(
        Modifier.fillMaxSize().then(if (typingOpen) bgMod else Modifier).onSizeChanged { playerSheet.height = it.height.toFloat(); rootH = it.height.toFloat() }
            .onGloballyPositioned { rootTop = it.positionInRoot().y }
    ) {
    Column(
        Modifier
            .fillMaxSize()
            // Yazma ekranı açıkken kumanda arkada bulanık görünür (Android 12+; eskilerde perde yeterli)
            .then(if (typingOpen) Modifier.blur(28.dp) else Modifier)
            .then(bgMod)
            // Alt menünün arkasında bulanık görünecek içerik
            .haze(hazeState)
            // Arka plan kenardan kenara; içerik saat şeridinin ve alttaki çizginin dışında kalır
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.onGloballyPositioned { barBottom = it.positionInRoot().y + it.size.height }) {
            // Cast'te üst çubuk yok: ⚙️ kendi başlığında. Kutu boş kalsa da TV panelinin yeri için ölçülür.
            if (tab != Tab.CAST) TopBarV2(
                ui, navy = true,
                onSettings = { settingsOpen = true },
                // Kayıtlı TV yoksa seçilecek bir şey yok: doğrudan büyük "TV'ler" penceresi
                onPicker = {
                    if (pickerOpen) pickerOpen = false
                    else { pickerExpanded = ui.savedTvs.isEmpty(); pickerOpen = true }
                },
                onPower = { onKey(Keys.POWER) },
                showPower = tab == Tab.REMOTE
            )
        }

        if (tab == Tab.CAST) {
            CastScreen(
                cast, ui.state == ConnState.CONNECTED,
                onCastPick, onCastItem, onCastToggle, onCastSeek, onCastStop,
                onCastPrev, onCastNext, onCastSkip, onCastWarmup,
                mediaLibrary, showInFolderTick, playerSheet, onCastSlideshow, onCastSlideSec,
                dlna, onCastRoute, onDlnaPick, onDlnaSearch,
                cc, onCcPick, onCcSearch,
                onSettings = { settingsOpen = true },
                onCastUrl = onCastUrl,
                modifier = Modifier.weight(1f)
            )
        } else if (tab == Tab.TOOLS) {
            ToolsScreen(ui.method, ui.state == ConnState.CONNECTED, Modifier.weight(1f))
        } else if (tab == Tab.SHORTCUTS) {
            ShortcutsScreen(Modifier.weight(1f))
        } else {
        Spacer(Modifier.height(14.dp))
        RemoteAppsRow(ui.apps, ui.state, ui.method, appRowTick, onLaunch) { sheet = Sheet.EDIT_APPS }
        Spacer(Modifier.height(14.dp))
        // Yön tuşu kartı kalan boşluğa göre boyutlanır; ekran ne olursa olsun kaydırma gerekmez
        GlassPadCard(
            ui, padMode, { padMode = it }, onKey, onMouse, onMic,
            onNumpad = { sheet = Sheet.NUMPAD },
            // Standart modda tam ekran canlı yazma, ADB'de küçük yazma penceresi
            onKeyboard = { if (ui.method == ConnMethod.STANDARD) onOpenTyping() else sheet = Sheet.KEYBOARD },
            onTvSettings = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { TvTools.openSettings() } } },
            onSystemKeys = { remoteTool = ToolId.SYSTEM_KEYS },
            onMediaKeys = { remoteTool = ToolId.MEDIA_KEYS },
            modifier = Modifier.weight(1f, fill = false)
        )
        // TV'de bir şey çalınca oynatıcı medya çubuğuyla tek kart olur; kumanda kartı aşağı doğru uzar (boşluk 10 dp)
        Spacer(Modifier.height(if (npVisible) 10.dp else 12.dp))
        GlassMediaBar(
            onKey,
            header = if (npVisible) { { LiveUi.nowPlaying?.let { NowPlayingContent(it, ui.apps, onSeekTv) } } } else null,
            leading = if (npVisible) { { LiveUi.nowPlaying?.let { NowPlayingIcon(it, ui.apps) } } } else null
        )
        // Medya çubuğu ile alt menü arasında bir oynatıcı çubuğu sığacak kadar boşluk (Serif'in tasarımı).
        // Oynatıcı çubuğu görünürken ya da ekran kısaysa boşluk küçülür, tuşlar küçülmesin diye.
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        val gap = when {
            npVisible -> 0.dp
            screenH >= 780 -> 80.dp
            screenH >= 720 -> 40.dp
            else -> 0.dp
        }
        Spacer(Modifier.fillMaxWidth().height(gap).glassSwoosh())
        // Kumanda sabit ekran: tuşlar alt menünün üstünde biter
        Spacer(Modifier.height(BarSpace))
        }
    }
    // Alt menü: Kumanda / Cast / Araçlar / Kısayollar — içeriğin üstünde yüzen cam kapsül
    // Tek sekme açıksa gizli (Serif'in isteği)
    if (showNav) BottomNav(
        tab, navTabs, hazeState,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 10.dp)
            .onGloballyPositioned { navTop = it.positionInRoot().y }
    ) { tab = it }

    // Tam ekran DLNA Sunucu sayfası (Cast sekmesindeki karttan açılır)
    if (DlnaServerUi.pageOpen) DlnaServerPage(mediaLibrary) { DlnaServerUi.pageOpen = false }
    // Tam ekran IR kumanda sayfaları (TV seçimi penceresinden açılır)
    IrPageHost()
    // Tam ekran araç sayfaları (Uygulama yöneticisi, TV dosya gezgini)
    if (tab == Tab.TOOLS) ToolPageHost()
    // Paylaş menüsünden "TV'ye gönder" ile gelen dosyalar
    ShareSend.job?.let { job ->
        ShareSendSheet(job, ui.state,
            onShowInBrowser = {
                ShareSend.job = null
                tab = Tab.TOOLS
                ToolPages.startDir = TvFiles.TV_DOWNLOAD
                ToolPages.page = ToolId.FILE_BROWSER
            })
    }

    // Tam ekran oynatıcı: alttan kayarak açılır, aşağı kaydırınca kapanır
    // Tam ekran oynatıcı: küçük karttan açılır, parmağı takip ederek açılıp kapanır
    if (playerSheet.visible && tab == Tab.CAST && cast.phase != CastPhase.NONE) {
        FullPlayer(
            playerSheet, cast, ui.tvName, mediaLibrary,
            onToggle = onCastToggle, onSeek = onCastSeek,
            onStop = { playerSheet.hideNow(); onCastStop() },
            onPrev = onCastPrev, onNext = onCastNext, onSkip = onCastSkip,
            onVolume = onCastVolume,
            onPlayAt = onCastPlayAt,
            onShowInFolder = { playerSheet.close(); showInFolderTick++ },
            onRepeat = onCastRepeat, onShuffle = onCastShuffle,
            onSlideshow = onCastSlideshow, onSlideSec = onCastSlideSec,
            onSubtitle = onCastSubtitle
        )
    }

    val density = LocalDensity.current
    TvPickerPanel(
        // Üst şeridin yeri ölçülmeden açılmaz (ilk karede yanlış yerde görünmesin)
        open = pickerOpen && barBottom > 0f,
        expanded = pickerExpanded,
        topOffset = with(density) { (barBottom - rootTop).coerceAtLeast(0f).toDp() } + 8.dp,
        // Büyükken alt menünün 12 dp üstüne kadar
        maxExpandedHeight = with(density) {
            ((if (NavBar.visible) navTop else rootTop + rootH) - barBottom).coerceAtLeast(0f).toDp()
        } - 20.dp,
        initialIp = initialIp,
        ui = ui,
        onPickSaved = onPickSaved, onWake = onWake,
        onConnect = onConnect, onMethod = onMethod,
        onRename = onRenameSaved, onForget = onForgetSaved, onDisconnect = onDisconnect,
        onExpand = { pickerExpanded = it },
        onClose = { pickerOpen = false }
    )

    if (settingsOpen) SettingsPage(ui, onOpenTvs = { settingsOpen = false; openTvs() }) { settingsOpen = false }

    if (typingOpen) {
        TypingScreen(ui.typingSeed, ui.typingSeedTick, ui.imeReady, ui.typingHint, onLiveText, onTypingSubmit, onCloseTyping, onTvSearch)
    }
    }

    if (sheet != Sheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheet = Sheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF242426)
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                when (sheet) {
                    Sheet.KEYBOARD -> KeyboardSheet(onText, onKey)
                    Sheet.NUMPAD -> NumpadSheet(onKey)
                    Sheet.EDIT_APPS -> EditAppsSheet(ui.apps) { appRowTick++ }
                    Sheet.NONE -> {}
                }
            }
        }
    }

    remoteTool?.let { id ->
        val adbReady = ui.method == ConnMethod.ADB && ui.state == ConnState.CONNECTED
        if (id == ToolId.SYSTEM_KEYS) ToolSheet(id, "Sistem kısayolları", Icons.Outlined.Keyboard, adbReady) { remoteTool = null }
        else ToolSheet(id, "Medya tuşları", Icons.Outlined.QueueMusic, adbReady) { remoteTool = null }
    }

    if (ui.pairing) PairingDialog(ui.pairingError, onPairCode, onPairCancel)
}

/** Standart bağlantıda TV'nin gösterdiği 6 haneli kodu isteyen küçük pencere. */
@Composable
private fun PairingDialog(error: String, onCode: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(0xFF242426),
        title = { Text("TV ile eşleştir", color = Pal.Text) },
        text = {
            Column {
                Text("TV ekranında görünen 6 haneli kodu gir.", color = Pal.Sub, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6) },
                    placeholder = { Text("ör. 1A2B3C") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onCode(code) })
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Pal.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCode(code) }, enabled = code.length == 6) { Text("Eşleştir") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("İptal", color = Pal.Sub) }
        }
    )
}

@Composable
fun StatusDot(state: ConnState) {
    val color = when (state) {
        ConnState.CONNECTED -> Pal.Green
        ConnState.CONNECTING -> Pal.Amber
        ConnState.ERROR -> Pal.Red
        ConnState.IDLE -> Pal.Sub
    }
    // Yanıp sönme yalnızca bağlanırken çalışır; diğer durumlarda sürekli kare çizilmez
    if (state == ConnState.CONNECTING) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val a by pulse.animateFloat(
            0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
        )
        Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
    } else {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

// ---------- Ortak yardımcılar ----------
internal fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
fun pressScale(inter: MutableInteractionSource): Float {
    val pressed by inter.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "scale"
    )
    return s
}

/**
 * Basma davranışı: dokunulduğu anda gönderir (gecikmesiz),
 * basılı tutunca (repeat=true) tekrar tekrar gönderir.
 * deferMs > 0 ise gönderim o kadar ertelenir; bu sürede parmak kaydırılırsa
 * (ör. mod değiştirmek için) tuş gönderilmez. Hızlı dokunuşta bırakınca hemen gönderilir.
 */
@Composable
fun Modifier.pressable(
    inter: MutableInteractionSource,
    repeat: Boolean = false,
    deferMs: Long = 0,
    onClick: () -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onClick)
    return this.pointerInput(repeat, deferMs) {
        coroutineScope {
            detectTapGestures(onPress = { offset ->
                val press = PressInteraction.Press(offset)
                inter.emit(press)
                var fired = false
                val fire = {
                    fired = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latest()
                }
                if (deferMs == 0L) fire()
                val job = launch {
                    if (deferMs > 0) { delay(deferMs); fire() }
                    if (repeat) {
                        delay(380 - deferMs)
                        while (isActive) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latest()
                            delay(90)
                        }
                    }
                }
                val released = tryAwaitRelease()
                job.cancel()
                if (released && !fired) fire()
                inter.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            })
        }
    }
}
