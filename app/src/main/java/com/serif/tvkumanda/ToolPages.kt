package com.serif.tvkumanda

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tam ekran açılan araç sayfaları (Uygulama yöneticisi, TV dosya gezgini). */
object ToolPages {
    var page by mutableStateOf<ToolId?>(null)
    /** Dosya gezgini bu klasörde açılsın (ör. "Dosya gönder" sonrası). */
    var startDir by mutableStateOf<String?>(null)
}

@Composable
fun ToolPageHost() {
    when (ToolPages.page) {
        ToolId.APP_MANAGER -> AppManagerPage { ToolPages.page = null }
        ToolId.FILE_BROWSER -> FileBrowserPage(ToolPages.startDir) { ToolPages.page = null; ToolPages.startDir = null }
        else -> {}
    }
}

// ---------------- Ortak parçalar ----------------

@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.Bg).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun FilterChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Text(
                o, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Blue else Pal.Btn)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Pal.Sub) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Pal.Sub) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
            focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub.copy(alpha = 0.4f), cursorColor = Pal.Blue
        )
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    if (icon != null) Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Pal.Btn), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Android, null, tint = Pal.Sub, modifier = Modifier.size(size * 0.55f))
    }
}

/** İlerleme çubuğu (değer < 0 ise belirsiz). */
@Composable
private fun Progress(stage: String, value: Float) {
    Text(stage, color = Pal.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    if (value < 0) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)), color = Pal.Blue, trackColor = Pal.Btn)
    else LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)), color = Pal.Blue, trackColor = Pal.Btn)
    if (value >= 0) {
        Spacer(Modifier.height(4.dp))
        Text("%${(value * 100).toInt()}", color = Pal.Sub, fontSize = 12.sp)
    }
}

private fun share(context: android.content.Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Paylaş"))
}

// ---------------- Uygulama yöneticisi ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppManagerPage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<Result<List<TvPackage>>?>(null) }
    LaunchedEffect(reload) { list = io { TvFiles.packages() } }
    val icons = remember { TvTools.appIcons() }
    var filter by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<TvPackage?>(null) }

    PageScaffold("Uygulama yöneticisi", onClose) {
        SearchBox(query, { query = it }, "Uygulama ara")
        Spacer(Modifier.height(10.dp))
        FilterChipRow(listOf("Yüklediklerim", "Sistem", "Devre dışı"), filter) { filter = it }
        Spacer(Modifier.height(10.dp))
        val r = list
        when {
            r == null -> Loading("Uygulamalar okunuyor…")
            r.isFailure -> ResultLine(ToolResult(false, errorText(r.exceptionOrNull()!!)))
            else -> {
                val shown = r.getOrThrow().filter {
                    when (filter) { 0 -> !it.system; 1 -> it.system; else -> it.disabled }
                }.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                    .sortedBy { it.label.lowercase() }
                Text("${shown.size} uygulama", color = Pal.Sub, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.pkg }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = p }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(icons[p.pkg])
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label, color = if (p.disabled) Pal.Sub else Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(p.version, p.pkg).filter { it.isNotBlank() }.joinToString(" · "), color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (p.disabled) Text("kapalı", color = Pal.Amber, fontSize = 11.sp)
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    open?.let { p ->
        ModalBottomSheet(
            onDismissRequest = { open = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF242426)
        ) {
            AppDetail(p, icons[p.pkg]) { changed -> open = null; if (changed) reload++ }
        }
    }
}

@Composable
private fun AppDetail(p: TvPackage, icon: ImageBitmap?, onDone: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val run = rememberRunner()
    val detail by produceState<TvFiles.PackageDetail?>(null, p) { value = io { TvFiles.detail(p) }.getOrNull() }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var backupUri by remember { mutableStateOf<Uri?>(null) }

    fun act(label: String, close: Boolean, block: () -> Unit) {
        busy = true; result = null
        run {
            io(block).onSuccess { result = ToolResult(true, label); if (close) onDone(true) }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(p.label, color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.pkg, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        val d = detail
        Text(
            listOfNotNull(
                d?.version?.ifBlank { null }?.let { "Sürüm $it" } ?: p.version.ifBlank { null }?.let { "Sürüm $it" },
                d?.sizeKb?.let { TvTools.mb(it) },
                d?.installed?.ifBlank { null }?.let { "Kurulum $it" },
                if (p.system) "Sistem uygulaması" else null,
                if (p.disabled) "Devre dışı" else null
            ).joinToString(" · "),
            color = Pal.Sub, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        progress?.let { Progress("Yedekleniyor…", it); Spacer(Modifier.height(12.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Aç", Modifier.weight(1f), enabled = !busy && !p.disabled) { act("Açıldı", false) { TvFiles.open(p) } }
            SecondaryButton("Zorla durdur", Modifier.weight(1f), enabled = !busy) { act("Durduruldu", false) { TvFiles.forceStop(p) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (backupUri != null) "Yedeği paylaş" else "Yedekle", Modifier.weight(1f), enabled = !busy) {
                backupUri?.let { share(context, it, "application/vnd.android.package-archive"); return@SecondaryButton }
                busy = true; result = null; progress = 0f
                run {
                    io { TvFiles.backup(context, p) { v -> progress = v } }
                        .onSuccess { (name, uri) -> backupUri = uri; result = ToolResult(true, "İndirilenler/TV Kumanda klasörüne kaydedildi: $name") }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                    progress = null; busy = false
                }
            }
            if (p.disabled) SecondaryButton("Etkinleştir", Modifier.weight(1f), enabled = !busy) { act("Etkinleştirildi", true) { TvFiles.enable(p) } }
            else Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        ConfirmButton("Verileri sil", enabled = !busy) { act("Veriler silindi", false) { TvFiles.clearData(p) } }
        if (!p.disabled) {
            Spacer(Modifier.height(10.dp))
            ConfirmButton("Devre dışı bırak", enabled = !busy) { act("Devre dışı bırakıldı", true) { TvFiles.disable(p) } }
        }
        if (!p.system) {
            Spacer(Modifier.height(10.dp))
            ConfirmButton("Kaldır", enabled = !busy) { act("Kaldırıldı", true) { TvFiles.uninstall(p) } }
        }
        ResultLine(result)
    }
}

// ---------------- APK yükle ----------------

@Composable
internal fun ApkInstallTool() {
    val context = LocalContext.current
    val run = rememberRunner()
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var installed by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        result = null; installed = null; progress = 0f; stage = "Hazırlanıyor…"
        run {
            io { TvFiles.installApk(context, uri, { s -> stage = s }, { v -> progress = v }) }
                .onSuccess { pkg -> installed = pkg; result = ToolResult(true, "Kuruldu: ${TvFiles.displayName(context, uri)}") }
                .onFailure { result = ToolResult(false, it.message ?: errorText(it)) }
            progress = null
        }
    }
    Text("Telefondaki .apk ya da parçalı .apks / .xapk paketini TV'ye kurar.", color = Pal.Sub, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    progress?.let { Progress(stage, it); Spacer(Modifier.height(14.dp)) }
    MainButton(if (progress != null) "Kuruluyor…" else "APK seç", enabled = progress == null) { picker.launch(arrayOf("*/*")) }
    installed?.let { pkg ->
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Aç", Modifier.fillMaxWidth()) {
            run { io { TvFiles.open(TvPackage(pkg, pkg, false, false, "", "", 0)) }.onFailure { result = ToolResult(false, errorText(it)) } }
        }
    }
    ResultLine(result)
}

// ---------------- Uygulama yedekle ----------------

@Composable
internal fun AppBackupTool() {
    val context = LocalContext.current
    val run = rememberRunner()
    val list by produceState<Result<List<TvPackage>>?>(null) { value = io { TvFiles.packages().filter { !it.system }.sortedBy { it.label.lowercase() } } }
    val icons = remember { TvTools.appIcons() }
    var busyPkg by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    Text("Uygulamanın kendisi yedeklenir; Android, uygulama verilerinin (girişler, ayarlar) yedeklenmesine izin vermez.", color = Pal.Sub, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    busyPkg?.let { Progress("Yedekleniyor…", progress); Spacer(Modifier.height(10.dp)) }
    ResultLine(result)
    saved?.let { u ->
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Paylaş", Modifier.fillMaxWidth()) { share(context, u, "application/vnd.android.package-archive") }
    }
    Spacer(Modifier.height(10.dp))
    val r = list
    when {
        r == null -> Loading("Uygulamalar okunuyor…")
        r.isFailure -> ResultLine(ToolResult(false, errorText(r.exceptionOrNull()!!)))
        else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(r.getOrThrow(), key = { it.pkg }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Btn)
                        .clickable(enabled = busyPkg == null) {
                            busyPkg = p.pkg; progress = 0f; result = null; saved = null
                            run {
                                io { TvFiles.backup(context, p) { v -> progress = v } }
                                    .onSuccess { (name, uri) -> saved = uri; result = ToolResult(true, "Kaydedildi: İndirilenler/TV Kumanda/$name") }
                                    .onFailure { result = ToolResult(false, errorText(it)) }
                                busyPkg = null
                            }
                        }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(icons[p.pkg], 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, color = Pal.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.version.ifBlank { p.pkg }, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.Download, null, tint = Pal.Sub, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------- Dosya gönder ----------------

@Composable
internal fun SendFilesTool(onShowInBrowser: () -> Unit) {
    val context = LocalContext.current
    val run = rememberRunner()
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var sent by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        result = null; sent = false; progress = 0f
        run {
            io { TvFiles.sendFiles(context, uris, { s -> stage = s }, { v -> progress = v }) }
                .onSuccess { sent = true; result = ToolResult(true, "${uris.size} dosya TV'nin Download klasörüne gönderildi") }
                .onFailure { result = ToolResult(false, errorText(it)) }
            progress = null
        }
    }
    Text("Telefondan seçtiğin dosyalar TV'nin Download klasörüne gider.", color = Pal.Sub, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    progress?.let { Progress(stage, it); Spacer(Modifier.height(14.dp)) }
    MainButton(if (progress != null) "Gönderiliyor…" else "Dosya seç", enabled = progress == null) { picker.launch(arrayOf("*/*")) }
    if (sent) {
        Spacer(Modifier.height(10.dp))
        SecondaryButton("TV dosya gezgininde göster", Modifier.fillMaxWidth(), onClick = onShowInBrowser)
    }
    ResultLine(result)
}

// ---------------- TV dosya gezgini ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserPage(startDir: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val run = rememberRunner()
    val roots by produceState<List<Pair<String, String>>>(listOf("/sdcard" to "Dahili depolama")) {
        io { TvFiles.roots() }.onSuccess { value = it }
    }
    var root by remember { mutableStateOf("/sdcard") }
    var dir by remember { mutableStateOf(startDir ?: "/sdcard") }
    var reload by remember { mutableIntStateOf(0) }
    var showHidden by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<Result<List<TvFiles.TvFile>>?>(null) }
    var selected by remember { mutableStateOf<TvFiles.TvFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    LaunchedEffect(dir, reload, showHidden) { files = null; files = io { TvFiles.list(dir, showHidden) } }

    BackHandler {
        if (dir != root) { val up = dir.substringBeforeLast('/'); dir = if (up.length < root.length) root else up } else onClose()
    }

    val rootName = roots.firstOrNull { it.first == root }?.second ?: "Depolama"
    val crumbs = dir.removePrefix(root).split('/').filter { it.isNotEmpty() }

    PageScaffold("TV dosya gezgini", onClose, actions = {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { showHidden = !showHidden }, contentAlignment = Alignment.Center) {
            Icon(if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, "Gizli dosyalar", tint = Pal.Sub, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { newFolder = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CreateNewFolder, "Yeni klasör", tint = Pal.Text, modifier = Modifier.size(24.dp))
        }
    }) {
        if (roots.size > 1) {
            FilterChipRow(roots.map { it.second }, roots.indexOfFirst { it.first == root }.coerceAtLeast(0)) { i ->
                root = roots[i].first; dir = root
            }
            Spacer(Modifier.height(8.dp))
        }
        // Konum: Dahili depolama › Download › …
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text(rootName, color = if (crumbs.isEmpty()) Pal.Text else Pal.Blue, fontSize = 13.sp, modifier = Modifier.clickable { dir = root })
            var acc = root
            for (c in crumbs) {
                acc = "$acc/$c"
                val target = acc
                Text("  ›  ", color = Pal.Sub, fontSize = 13.sp)
                Text(c, color = if (target == dir) Pal.Text else Pal.Blue, fontSize = 13.sp, modifier = Modifier.clickable { dir = target })
            }
        }
        Spacer(Modifier.height(8.dp))
        val r = files
        when {
            r == null -> Loading()
            r.isFailure -> ResultLine(ToolResult(false, errorText(r.exceptionOrNull()!!)))
            r.getOrThrow().isEmpty() -> Text("Bu klasör boş", color = Pal.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(r.getOrThrow(), key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { if (f.dir) dir = f.path else selected = f },
                                onLongClick = { selected = f }
                            ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(fileIcon(f), null, tint = if (f.dir) Pal.Amber else Pal.Text, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (f.dir) null else TvTools.bytes(f.size), date(f.modified)).joinToString(" · "),
                                color = Pal.Sub, fontSize = 12.sp
                            )
                        }
                        if (f.dir) Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    selected?.let { f ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF242426)
        ) {
            FileActions(f) { changed -> selected = null; if (changed) reload++ }
        }
    }
    if (newFolder) NameInputDialog("Yeni klasör", "", "Oluştur") { name ->
        newFolder = false
        if (!name.isNullOrBlank()) run { io { TvFiles.mkdir(dir, name.trim()) }; reload++ }
    }
}

private fun fileIcon(f: TvFiles.TvFile): ImageVector {
    if (f.dir) return Icons.Outlined.Folder
    val ext = f.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Outlined.Image
        "mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "3gp" -> Icons.Outlined.Movie
        "mp3", "flac", "aac", "m4a", "ogg", "wav", "opus" -> Icons.Outlined.MusicNote
        "apk", "apks", "xapk" -> Icons.Outlined.Android
        "zip", "rar", "7z", "tar", "gz" -> Icons.Outlined.FolderZip
        "txt", "log", "srt", "json", "xml" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun date(ms: Long): String? = if (ms <= 0) null else SimpleDateFormat("d MMM yyyy HH:mm", Locale("tr")).format(Date(ms))

@Composable
private fun FileActions(f: TvFiles.TvFile, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val run = rememberRunner()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var downloaded by remember { mutableStateOf<Uri?>(null) }
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon(f), null, tint = if (f.dir) Pal.Amber else Pal.Text, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(f.name, color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (f.dir) "Klasör" else TvTools.bytes(f.size), date(f.modified)).joinToString(" · "), color = Pal.Sub, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        progress?.let { Progress("Telefona indiriliyor…", it); Spacer(Modifier.height(12.dp)) }
        if (!f.dir) {
            if (downloaded == null) MainButton("Telefona indir", enabled = !busy) {
                busy = true; result = null; progress = 0f
                run {
                    io { TvFiles.download(context, f) { v -> progress = v } }
                        .onSuccess { downloaded = it; result = ToolResult(true, "İndirilenler/TV Kumanda klasörüne kaydedildi") }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                    progress = null; busy = false
                }
            } else {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
                MainButton("Paylaş") { share(context, downloaded!!, mime) }
            }
            Spacer(Modifier.height(10.dp))
        }
        SecondaryButton("Yeniden adlandır", Modifier.fillMaxWidth(), enabled = !busy) { renaming = true }
        Spacer(Modifier.height(10.dp))
        ConfirmButton(if (f.dir) "Klasörü sil" else "Sil", enabled = !busy) {
            busy = true
            run {
                io { TvFiles.delete(f) }.onSuccess { onDone(true) }.onFailure { result = ToolResult(false, errorText(it)) }
                busy = false
            }
        }
        ResultLine(result)
    }
    if (renaming) NameInputDialog("Yeniden adlandır", f.name, "Kaydet") { name ->
        renaming = false
        if (!name.isNullOrBlank() && name != f.name) run {
            io { TvFiles.rename(f, name.trim()) }.onSuccess { onDone(true) }.onFailure { result = ToolResult(false, errorText(it)) }
        }
    }
}

@Composable
private fun NameInputDialog(title: String, initial: String, confirm: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Color(0xFF242426),
        title = { Text(title, color = Pal.Text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.replace("/", "").take(120) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                    focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub, cursorColor = Pal.Blue
                )
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(confirm, color = Pal.Blue) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Vazgeç", color = Pal.Sub) } }
    )
}
