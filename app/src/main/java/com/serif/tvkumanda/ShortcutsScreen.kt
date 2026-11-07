package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun iconFor(name: String): ImageVector = when (name) {
    "power" -> Icons.Outlined.PowerSettingsNew
    "movie" -> Icons.Outlined.Movie
    "volume" -> Icons.Outlined.VolumeUp
    "bed" -> Icons.Outlined.Bedtime
    "search" -> Icons.Outlined.Search
    "home" -> Icons.Outlined.Home
    "play" -> Icons.Outlined.PlayArrow
    "tv" -> Icons.Outlined.Tv
    "music" -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.Star
}

/**
 * Kısayollar sekmesi: favori kanallar ve kullanıcının kendi tek dokunuşluk kısayolları.
 * Dokununca çalışır, basılı tutunca düzenlenir. [runSteps] adımları TV'de çalıştırır ve bitince hata metniyle (ya da null)
 * geri döner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutsScreen(
    apps: List<TvApp>,
    connected: Boolean,
    runSteps: (List<Step>, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val toast = { m: String -> Toast.makeText(context, m, Toast.LENGTH_SHORT).show() }

    val shortcuts = remember {
        mutableStateListOf<Shortcut>().apply {
            addAll(ShortcutStore.load(context))
            // İlk açılışta hazır şablonlar bir kez eklenir
            if (isEmpty() && !ShortcutStore.seededAlready(context)) {
                addAll(ShortcutTemplates.all())
                ShortcutStore.save(context, toList())
            }
            ShortcutStore.markSeeded(context)
        }
    }
    val channels = remember { mutableStateListOf<FavChannel>().apply { addAll(ShortcutStore.loadChannels(context)) } }
    var running by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Shortcut?>(null) }       // düzenlenen (yeniyse listede yok)
    var editingChannel by remember { mutableStateOf<FavChannel?>(null) }
    var newChannel by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }

    fun saveShortcuts() = ShortcutStore.save(context, shortcuts.toList())
    fun saveChannels() = ShortcutStore.saveChannels(context, channels.toList())

    fun run(steps: List<Step>, title: String) {
        if (running) return
        if (steps.isEmpty()) { toast("Bu kısayolda adım yok"); return }
        // Uyandırma adımı bağlantı olmadan da çalışır; geri kalanı için TV'ye bağlı olmak gerekir
        if (!connected && steps.firstOrNull() !is Step.Wake) { toast("Önce TV'ye bağlan"); return }
        running = true
        toast("$title çalışıyor…")
        runSteps(steps) { error ->
            running = false
            toast(if (error == null) "$title bitti" else "$title durdu: $error")
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Text("Kısayollar", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Dokununca çalışır, basılı tutunca düzenlenir", color = Pal.Sub, fontSize = 13.sp)

        // Favori kanallar: satırda 3 küçük kutu, sonda ekleme kutusu
        Spacer(Modifier.height(20.dp))
        Text("Favori kanallar", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val channelCells: List<FavChannel?> = channels.toList() + listOf(null)   // null = "Kanal ekle" kutusu
        for (row in channelCells.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (ch in row) {
                    if (ch != null) ChannelTile(
                        ch, Modifier.weight(1f),
                        onClick = { run(listOf(Step.Channel(ch.number)), "Kanal ${ch.number}") },
                        onLong = { editingChannel = ch }
                    ) else AddTile("Kanal ekle", Modifier.weight(1f).height(76.dp)) { newChannel = true }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Kendi kısayolların: satırda 2 büyük kutu, sonda "Yeni kısayol"
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Kısayollarım", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box {
                Text("Şablonlar", color = Pal.Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { templateMenu = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                    for (t in ShortcutTemplates.all()) {
                        DropdownMenuItem(text = { Text(t.name) }, onClick = {
                            shortcuts.add(t); saveShortcuts(); templateMenu = false
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val cells: List<Shortcut?> = shortcuts.toList() + listOf(null)   // null = "Yeni kısayol" kutusu
        for (row in cells.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sc in row) {
                    if (sc != null) ShortcutTile(sc, Modifier.weight(1f), onClick = { run(sc.steps, sc.name) }, onLong = { editing = sc })
                    else AddTile("Yeni kısayol", Modifier.weight(1f).height(96.dp)) {
                        editing = Shortcut(ShortcutStore.newId(), "", "star", emptyList())
                    }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        // Son satır alt menünün arkasında kalmasın
        Spacer(Modifier.height(BarSpace))
    }

    editing?.let { sc ->
        val exists = shortcuts.any { it.id == sc.id }
        ShortcutEditor(
            initial = sc, isNew = !exists, apps = apps,
            onSave = { saved ->
                val i = shortcuts.indexOfFirst { it.id == saved.id }
                if (i >= 0) shortcuts[i] = saved else shortcuts.add(saved)
                saveShortcuts(); editing = null
            },
            onDelete = { shortcuts.removeAll { it.id == sc.id }; saveShortcuts(); editing = null },
            onTest = { steps -> run(steps, "Deneme") },
            onDismiss = { editing = null }
        )
    }

    if (newChannel || editingChannel != null) {
        val old = editingChannel
        ChannelEditor(
            initial = old,
            onSave = { ch ->
                if (old != null) { val i = channels.indexOf(old); if (i >= 0) channels[i] = ch } else channels.add(ch)
                saveChannels(); newChannel = false; editingChannel = null
            },
            onDelete = if (old != null) ({ channels.remove(old); saveChannels(); editingChannel = null }) else null,
            onDismiss = { newChannel = false; editingChannel = null }
        )
    }
}

// ---------------------------------------------------------------- Kutucuklar

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(sc: Shortcut, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        modifier
            .height(96.dp)
            .glass(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(iconFor(sc.icon), sc.name, tint = Pal.Text, modifier = Modifier.size(26.dp))
        Column {
            Text(sc.name, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sc.detail, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(ch: FavChannel, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        modifier
            .height(76.dp)
            .glass(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(ch.number, color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (ch.name.isNotBlank())
            Text(ch.name, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Kesik çizgili "ekle" kutusu. */
@Composable
private fun AddTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .drawBehind {
                drawRoundRect(
                    color = Pal.Sub.copy(alpha = 0.6f),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                )
            }
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Add, label, tint = Pal.Sub, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Pal.Sub, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- Düzenleyiciler

@Composable
private fun ChannelEditor(initial: FavChannel?, onSave: (FavChannel) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(initial?.number ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSettings.theme.panel,
        title = { Text(if (initial == null) "Kanal ekle" else "Kanalı düzenle", color = Pal.Text) },
        text = {
            Column {
                OutlinedTextField(number, { number = it.filter(Char::isDigit).take(5) }, label = { Text("Kanal numarası") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(14) }, label = { Text("Ad (isteğe bağlı)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = number.isNotEmpty(), onClick = { onSave(FavChannel(number, name.trim())) }) { Text("Kaydet") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Sil", color = Pal.Red) }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        }
    )
}

private enum class AddKind(val label: String) {
    KEY("Tuş bas"), APP("Uygulama aç"), TEXT("Yazı yaz"), WAIT("Bekle"), WAKE("TV'yi uyandır"),
    VOLUME("Ses seviyesi"), SLEEP("Uyku zamanlayıcısı"), CHANNEL("Kanala geç")
}

@Composable
private fun ShortcutEditor(
    initial: Shortcut, isNew: Boolean, apps: List<TvApp>,
    onSave: (Shortcut) -> Unit, onDelete: () -> Unit, onTest: (List<Step>) -> Unit, onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var icon by remember { mutableStateOf(initial.icon) }
    val steps = remember { mutableStateListOf<Step>().apply { addAll(initial.steps) } }
    var addMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<AddKind?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSettings.theme.panel,
        title = { Text(if (isNew) "Yeni kısayol" else "Kısayolu düzenle", color = Pal.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Ad") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                // Simge seçimi
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in ShortcutIcons.names) {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (n == icon) Pal.Purple else Pal.Btn)
                                .clickable { icon = n },
                            contentAlignment = Alignment.Center
                        ) { Icon(iconFor(n), n, tint = Pal.Text, modifier = Modifier.size(20.dp)) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Adımlar (sırayla çalışır)", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (steps.isEmpty()) Text("Henüz adım yok", color = Pal.Sub, fontSize = 13.sp)
                steps.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${s.label}", color = Pal.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        IconButton(enabled = i > 0, onClick = { val t = steps.removeAt(i); steps.add(i - 1, t) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "Yukarı", tint = Pal.Sub)
                        }
                        IconButton(enabled = i < steps.lastIndex, onClick = { val t = steps.removeAt(i); steps.add(i + 1, t) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "Aşağı", tint = Pal.Sub)
                        }
                        IconButton(onClick = { steps.removeAt(i) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Close, "Sil", tint = Pal.Red)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box {
                    TextButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Adım ekle") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        for (k in AddKind.values()) DropdownMenuItem(text = { Text(k.label) }, onClick = {
                            addMenu = false
                            if (k == AddKind.WAKE) steps.add(Step.Wake) else adding = k
                        })
                    }
                }
                if (steps.isNotEmpty()) TextButton(onClick = { onTest(steps.toList()) }) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Şimdi dene")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && steps.isNotEmpty(),
                onClick = { onSave(Shortcut(initial.id, name.trim(), icon, steps.toList())) }) { Text("Kaydet") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Sil", color = Pal.Red) }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        }
    )

    adding?.let { kind ->
        AddStepDialog(kind, apps, onAdd = { steps.add(it); adding = null }, onDismiss = { adding = null })
    }
}

@Composable
private fun AddStepDialog(kind: AddKind, apps: List<TvApp>, onAdd: (Step) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var times by remember { mutableStateOf(1) }
    var key by remember { mutableStateOf(ShortcutKeys.all.first().first) }
    var app by remember { mutableStateOf<TvApp?>(null) }

    fun number(): Int? = text.toIntOrNull()
    val ready = when (kind) {
        AddKind.KEY -> true
        AddKind.APP -> app != null
        AddKind.TEXT -> text.isNotEmpty()
        AddKind.WAIT -> number() in 1..60
        AddKind.VOLUME -> number() in 0..100
        AddKind.SLEEP -> number() in 1..600
        AddKind.CHANNEL -> text.isNotEmpty()
        AddKind.WAKE -> true
    }
    fun build(): Step = when (kind) {
        AddKind.KEY -> Step.Key(key, times)
        AddKind.APP -> app!!.let { Step.Launch(it.pkg, it.activity, it.name) }
        AddKind.TEXT -> Step.Text(text)
        AddKind.WAIT -> Step.Wait((number() ?: 1) * 1000L)
        AddKind.VOLUME -> Step.Volume(number() ?: 0)
        AddKind.SLEEP -> Step.Sleep(number() ?: 30)
        AddKind.CHANNEL -> Step.Channel(text)
        AddKind.WAKE -> Step.Wake
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSettings.theme.panel,
        title = { Text(kind.label, color = Pal.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (kind) {
                    AddKind.KEY -> {
                        for ((code, label) in ShortcutKeys.all) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { key = code }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = key == code, onClick = { key = code })
                                Text(label, color = Pal.Text, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kaç kez: $times", color = Pal.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { times = (times - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, "Azalt", tint = Pal.Text) }
                            IconButton(onClick = { times = (times + 1).coerceAtMost(30) }) { Icon(Icons.Outlined.Add, "Artır", tint = Pal.Text) }
                        }
                    }
                    AddKind.APP -> {
                        if (apps.isEmpty()) Text("Uygulama listesi yok. Uygulama listesi ADB bağlantısında ve TV'ye bağlıyken gelir.",
                            color = Pal.Sub, fontSize = 13.sp)
                        for (a in apps) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app = a }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = app == a, onClick = { app = a })
                                Text(a.name, color = Pal.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    AddKind.TEXT -> OutlinedTextField(text, { text = it.take(80) }, label = { Text("Yazılacak metin") }, singleLine = true)
                    AddKind.WAIT -> OutlinedTextField(text, { text = it.filter(Char::isDigit).take(2) }, label = { Text("Saniye (1–60)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    AddKind.VOLUME -> OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = { Text("Ses seviyesi (0–100)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    AddKind.SLEEP -> OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = { Text("Kaç dakika sonra (1–600)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    AddKind.CHANNEL -> OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, label = { Text("Kanal numarası") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    AddKind.WAKE -> {}
                }
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onAdd(build()) }) { Text("Ekle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
