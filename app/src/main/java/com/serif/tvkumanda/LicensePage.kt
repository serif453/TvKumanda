package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kullanılan açık kaynak projeler; README'deki Lisans bölümü ve IR kod kaynaklarıyla uyumlu tutulur. */
private val thirdParty = listOf(
    Triple("dadb", "ADB bağlantısı (değiştirilmiş kopya)", "Apache-2.0"),
    Triple("androidtvremote2", "Standart bağlantının protokol tanımları", "Apache-2.0"),
    Triple("Haze", "Alt menünün cam görünümü", "Apache-2.0"),
    Triple("Inter", "Kumanda sekmesinin yazı tipi", "SIL OFL 1.1"),
    Triple("Jetpack Compose ve Material ikonları", "Arayüz", "Apache-2.0"),
    Triple("Kotlin Coroutines, Okio", "Arka plan işleri, veri akışı", "Apache-2.0"),
    Triple("Flipper-IRDB", "IR kumanda kodları", "CC0"),
    Triple("SmartIR", "Ek IR kumanda kodları", "MIT"),
    Triple("irdb", "Çevrimiçi IR kodları (yalnızca istenince indirilir)", "izinle kullanılıyor")
)

/** Lisans dosyasındaki sabit satır sonlarını kaldırır ki metin telefon ekranına düzgün yayılsın. */
private fun paragraphs(text: String): List<String> =
    text.split(Regex("\\n\\s*\\n")).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }.filter { it.isNotEmpty() }

/** Tam ekran Lisans sayfası (Ayarlar → Hakkında'dan açılır). */
@Composable
fun LicensePage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var fullOpen by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(fullOpen) {
        if (fullOpen && full.isEmpty()) {
            full = withContext(Dispatchers.IO) {
                runCatching { paragraphs(context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }) }
                    .getOrDefault(listOf("Lisans metni açılamadı. github.com/serif453/TvKumanda sayfasındaki LICENSE dosyasına bakabilirsin."))
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Pal.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Lisans", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            LicenseHeader("TV Kumanda")
            LicenseCard {
                Text("GPL-3.0 · Copyright (C) 2026 serif453", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bu program özgür yazılımdır: GNU Genel Kamu Lisansı sürüm 3 koşulları altında dağıtabilir ve değiştirebilirsin. " +
                        "Değiştirilmiş bir sürümü dağıtırsan, kaynak kodunu da aynı lisansla paylaşman gerekir. " +
                        "Program yararlı olacağı umuduyla, ancak hiçbir garanti olmaksızın dağıtılmaktadır.",
                    color = Pal.Sub, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(24.dp))

            LicenseHeader("Kullanılan açık kaynak yazılımlar")
            LicenseCard {
                thirdParty.forEachIndexed { i, (name, use, license) ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(license, color = Pal.Blue, fontSize = 12.sp)
                    }
                    Text(use, color = Pal.Sub, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))

            LicenseHeader("Lisansın tam metni")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn)) {
                Row(
                    Modifier.fillMaxWidth().clickable { fullOpen = !fullOpen }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GNU General Public License v3 (İngilizce)", color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Icon(if (fullOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Pal.Sub, modifier = Modifier.size(22.dp))
                }
                if (fullOpen) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                        for (p in full) {
                            Text(p, color = Pal.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LicenseHeader(text: String) {
    Text(text, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun LicenseCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn).padding(14.dp), content = content)
}
