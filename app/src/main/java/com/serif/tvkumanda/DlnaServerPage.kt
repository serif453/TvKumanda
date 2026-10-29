package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cast ana sayfasındaki "DLNA Sunucu" kartı: durum + açma/kapama; dokununca tam ekran sayfa açılır. */
@Composable
fun DlnaServerCard(library: MediaLibrary) {
    val context = LocalContext.current
    val ask = rememberServerPermissionAsk(library) { DlnaServerUi.start(context) }
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(20.dp)).background(Pal.Btn)
            .clickable { DlnaServerUi.pageOpen = true }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Dns, null, tint = if (DlnaServerUi.running) Pal.Green else Pal.Text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Telefonu medya sunucusu yap", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(serverStatusLine(context), color = if (DlnaServerUi.running) Pal.Green else Pal.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        ServerSwitch(DlnaServerUi.running || DlnaServerUi.starting) { on ->
            if (on) ask() else DlnaServerUi.stop(context)
        }
    }
}

private fun serverStatusLine(c: android.content.Context): String = when {
    DlnaServerUi.starting -> "Başlatılıyor…"
    DlnaServerUi.running -> "Açık · TV'de \"${DlnaServerPrefs.name(c)}\" olarak görünüyor"
    DlnaServerUi.error.isNotBlank() -> DlnaServerUi.error
    else -> "Kapalı · TV'nin oynatıcısından telefondaki medyayı aç"
}

@Composable
private fun ServerSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Pal.Green,
            uncheckedThumbColor = Pal.Sub, uncheckedTrackColor = Pal.Bg, uncheckedBorderColor = Pal.Sub.copy(alpha = 0.5f)
        )
    )
}

/** Sunucu başlamadan önce paylaşılan türlerin galeri izinleri istenir (verilmese de başlar; izinsiz tür boş görünür). */
@Composable
private fun rememberServerPermissionAsk(library: MediaLibrary, onReady: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onReady)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { latest() }
    return remember(library) {
        {
            val missing = MediaKind.entries.filter { !library.hasPermission(it) }
            if (missing.isEmpty()) latest()
            else {
                missing.forEach { library.markAsked(it) }
                launcher.launch(missing.flatMap { library.permissionsFor(it).toList() }.distinct().toTypedArray())
            }
        }
    }
}

/** Tam ekran DLNA Sunucu sayfası. */
@Composable
fun DlnaServerPage(library: MediaLibrary, onClose: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberServerPermissionAsk(library) { DlnaServerUi.start(context) }
    var picker by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    val tick = DlnaServerUi.tick
    BackHandler { if (picker) picker = false else onClose() }

    Box(
        Modifier.fillMaxSize().background(Pal.Bg)
            // Arkadaki ekrana dokunulmasın
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            // ---- Üst şerit ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("DLNA Sunucu", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Chip("deneysel", Pal.Amber)
            }
            Spacer(Modifier.height(14.dp))

            // ---- Durum kartı ----
            StatusCard(context) { if (DlnaServerUi.running || DlnaServerUi.starting) DlnaServerUi.stop(context) else ask() }
            Spacer(Modifier.height(24.dp))

            // ---- Paylaşılanlar ----
            Header("Paylaşılanlar")
            val shares = remember(tick) { DlnaServerPrefs.albums(context) }
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Pal.Btn)) {
                for ((kind, label) in listOf(MediaKind.PHOTO to "Tüm fotoğraflar", MediaKind.VIDEO to "Tüm videolar", MediaKind.MUSIC to "Tüm müzikler")) {
                    val on = remember(tick) { DlnaServerPrefs.allEnabled(context, kind) }
                    val count by produceState<Int?>(null, kind) {
                        value = withContext(Dispatchers.IO) {
                            if (!library.hasPermission(kind)) null
                            else runCatching { library.albums(kind).filter { it.id != Long.MIN_VALUE }.sumOf { it.count } }.getOrNull()
                        }
                    }
                    ShareRow(
                        icon = placeholderFor(kind), title = label,
                        subtitle = count?.let { "$it dosya" } ?: "Galeri izni gerekli",
                        trailing = { ServerSwitch(on) { DlnaServerPrefs.setAllEnabled(context, kind, it); DlnaServerUi.tick++ } }
                    )
                }
                for (a in shares) {
                    ShareRow(
                        icon = placeholderFor(a.kind), title = a.name, subtitle = a.kind.label,
                        trailing = {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable {
                                    DlnaServerPrefs.setAlbums(context, shares.filterNot { it == a }); DlnaServerUi.tick++
                                },
                                contentAlignment = Alignment.Center
                            ) { Icon(AppIcons.Close, "Kaldır", tint = Pal.Sub, modifier = Modifier.size(20.dp)) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(Pal.Btn).clickable { picker = true },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, null, tint = Pal.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Klasör ekle", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))

            // ---- Ayarlar ----
            Header("Ayarlar")
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Pal.Btn)) {
                val name = remember(tick) { DlnaServerPrefs.name(context) }
                ShareRow(Icons.Outlined.Edit, "Sunucu adı", name, onClick = { editName = true }, trailing = {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub, modifier = Modifier.padding(end = 8.dp))
                })
                val thumbs = remember(tick) { DlnaServerPrefs.thumbnails(context) }
                ShareRow(Icons.Outlined.Image, "Küçük resimleri göster", "Fotoğraf, video ve albüm kapakları", trailing = {
                    ServerSwitch(thumbs) { DlnaServerPrefs.setThumbnails(context, it); DlnaServerUi.tick++ }
                })
                val groups = remember(tick) { DlnaServerPrefs.musicGroups(context) }
                ShareRow(Icons.Outlined.LibraryMusic, "Müzikleri grupla", "Sanatçıya ve albüme göre ayrı klasörler", trailing = {
                    ServerSwitch(groups) { DlnaServerPrefs.setMusicGroups(context, it); DlnaServerUi.tick++ }
                })
                // Kullanılmazsa kapan
                val idle = remember(tick) { DlnaServerPrefs.idleMinutes(context) }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Kullanılmazsa kapan", color = Pal.Text, fontSize = 15.sp)
                            Text("TV bu süre boyunca hiçbir şey açmazsa sunucu kapanır", color = Pal.Sub, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for ((v, label) in listOf(0 to "Asla", 30 to "30 dk", 60 to "1 saat", 120 to "2 saat")) {
                            val sel = idle == v
                            Box(
                                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Pal.Blue else Color.Transparent)
                                    .clickable { DlnaServerPrefs.setIdleMinutes(context, v); DlnaServerUi.tick++ },
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (picker) AlbumPicker(library) { picker = false }
    }

    if (editName) NameDialog(DlnaServerPrefs.name(context), DlnaServerPrefs.defaultName(context)) { new ->
        editName = false
        if (new != null) { DlnaServerPrefs.setName(context, new); DlnaServerUi.tick++; DlnaServerUi.nameChanged() }
    }
}

@Composable
private fun StatusCard(context: android.content.Context, onToggle: () -> Unit) {
    val running = DlnaServerUi.running
    val starting = DlnaServerUi.starting
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Btn).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(if (running) Pal.Green.copy(alpha = 0.15f) else Pal.Bg),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Dns, null, tint = if (running) Pal.Green else Pal.Text, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) { Box(Modifier.size(8.dp).clip(CircleShape).background(Pal.Green)); Spacer(Modifier.width(8.dp)) }
            Text(
                when { starting -> "Başlatılıyor…"; running -> "Çalışıyor"; else -> "Kapalı" },
                color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("TV'de \"${DlnaServerPrefs.name(context)}\" olarak görünür", color = Pal.Sub, fontSize = 13.sp)
        if (running && DlnaServerUi.address.isNotBlank()) Text(DlnaServerUi.address, color = Pal.Sub, fontSize = 12.sp)
        if (DlnaServerUi.error.isNotBlank() && !running) {
            Spacer(Modifier.height(6.dp))
            Text(DlnaServerUi.error, color = Pal.Red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (running || starting) Pal.Red else Pal.Green)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Text(if (running || starting) "Durdur" else "Başlat", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (running) {
            Spacer(Modifier.height(12.dp))
            Text(
                "TV'de Medya / Kaynaklar uygulamasını aç, listede \"${DlnaServerPrefs.name(context)}\" adını seç.",
                color = Pal.Sub, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun ShareRow(
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
            Text(subtitle, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Galeri albümlerinden klasör seçme: dokununca eklenir ya da çıkarılır. */
@Composable
private fun AlbumPicker(library: MediaLibrary, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(DlnaServerPrefs.albums(context)) }
    val albums by produceState<List<Pair<MediaKind, MediaAlbum>>?>(null) {
        value = withContext(Dispatchers.IO) {
            MediaKind.entries.filter { library.hasPermission(it) }.flatMap { k ->
                runCatching { library.albums(k) }.getOrDefault(emptyList()).filter { it.id != Long.MIN_VALUE && it.count > 0 }.map { k to it }
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Pal.Bg).pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Klasör ekle", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Pal.Blue).clickable {
                        DlnaServerPrefs.setAlbums(context, selected); DlnaServerUi.tick++; onClose()
                    }.padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text("Tamam", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(10.dp))
            val list = albums
            when {
                list == null -> Text("Yükleniyor…", color = Pal.Sub, fontSize = 14.sp)
                list.isEmpty() -> Text("Galeri izni verilmemiş ya da hiç klasör yok", color = Pal.Sub, fontSize = 14.sp)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var lastKind: MediaKind? = null
                    for ((k, a) in list) {
                        if (k != lastKind) {
                            lastKind = k
                            item(key = "h_$k") {
                                Text(
                                    when (k) { MediaKind.PHOTO -> "Fotoğraf klasörleri"; MediaKind.VIDEO -> "Video klasörleri"; MediaKind.MUSIC -> "Müzik albümleri" },
                                    color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }
                        }
                        item(key = "${k}_${a.id}") {
                            val isSel = selected.any { it.kind == k && it.bucketId == a.id }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (isSel) Pal.Blue.copy(alpha = 0.18f) else Pal.Btn)
                                    .clickable {
                                        selected = if (isSel) selected.filterNot { it.kind == k && it.bucketId == a.id }
                                        else selected + SharedAlbum(k, a.id, a.name)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Thumb(a.cover, library, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), placeholderFor(k), px = 160)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${a.count} dosya", color = Pal.Sub, fontSize = 12.sp)
                                }
                                Icon(
                                    if (isSel) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                                    tint = if (isSel) Pal.Blue else Pal.Sub, modifier = Modifier.size(24.dp).padding(end = 2.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(current: String, fallback: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Color(0xFF242426),
        title = { Text("Sunucu adı", color = Pal.Text) },
        text = {
            Column {
                Text("TV'nin listesinde bu adla görünür.", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    placeholder = { Text(fallback) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                        focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub, cursorColor = Pal.Blue
                    )
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text.ifBlank { fallback }) }) { Text("Kaydet", color = Pal.Blue) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Vazgeç", color = Pal.Sub) } }
    )
}
