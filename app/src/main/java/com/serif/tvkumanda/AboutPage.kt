package com.serif.tvkumanda

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Uygulamayı tanıtan tam ekran sayfa (Ayarlar → Hakkında → TV Kumanda). Özellikler değişirse metin de güncellenir. */
@Composable
fun AboutPage(version: String, onOpenLicense: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    // Uygulamanın gerçek simgesi; okunamazsa TV simgesi gösterilir
    val appIcon: ImageBitmap? = remember {
        runCatching {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            val b = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 216, 216)
            d.draw(Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }
    val openIssues = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/serif453/TvKumanda/issues")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
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
                Text("Hakkında", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))

            // ---- Giriş ----
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (appIcon != null) {
                    Image(appIcon, null, Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)))
                } else {
                    Icon(Icons.Outlined.Tv, null, tint = Pal.Text, modifier = Modifier.size(64.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("TV Kumanda", color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Sürüm $version", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Android TV ve Google TV'ni telefondan yöneten hepsi bir arada bir uygulama. Kumanda, Cast ve TV araçları " +
                        "tek yerde. Sunucu ya da hesap gerekmez; komutların ev ağından doğrudan TV'ye gider.",
                    color = Pal.Text, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(26.dp))

            AboutHeader("Neden var?")
            AboutCard {
                AboutBody(
                    "Bu uygulamayı, beni tam tatmin eden bir TV kumandası bulamadığım için yaptım. Her uygulamada bir şey eksikti; " +
                        "kimi yalnızca tuşları sunuyor, kimi Cast yapıyor ama TV'yi yönetemiyordu. Amacım her işi tek başına görebilen " +
                        "bir İsviçre çakısıydı. Milyonlara değil, TV'sini kurcalamayı seven meraklılara göre tasarlandı."
                )
            }
            Spacer(Modifier.height(24.dp))

            AboutHeader("Neler yapabilirsin?")
            AboutCard {
                AboutFeature(
                    Icons.Outlined.Tv, "Kumanda",
                    "Yön tuşları, büyük ses çubuğu, medya tuşları, kaydırma yüzeyi, telefon klavyesiyle yazma ve sesli komut. " +
                        "TV'yi ağdan uyandırabilir, birden fazla TV kaydedip aralarında geçebilirsin. ADB ile fare ve TV'deki uygulamalar da gelir."
                )
                AboutFeature(
                    Icons.Outlined.Cast, "Cast",
                    "Telefondaki fotoğraf, video ve müzikleri Chromecast ya da DLNA ile TV'ye gönder. Altyazı ekle, " +
                        "internetteki bir videoyu adresiyle oynat, telefon ekranı kapalıyken de yayını sürdür."
                )
                AboutFeature(
                    Icons.Outlined.Build, "Araçlar",
                    "TV'nin sistem bilgisi ve performansı, uygulama yönetimi, dosya gönderme, ekran görüntüsü, uyku zamanlayıcısı, " +
                        "zamanlı açma ve ağ testleri. Çoğu ADB bağlantısı ister; ağ testleri istemez."
                )
                AboutFeature(
                    Icons.Outlined.SettingsRemote, "IR kumanda",
                    "Telefonunda kızılötesi verici varsa eski TV'leri de kullanabilirsin. 100'den fazla marka hazır gelir; " +
                        "Wi-Fi ya da bağlantı gerekmez."
                )
            }
            Spacer(Modifier.height(24.dp))

            AboutHeader("İki bağlantı türü")
            AboutCard {
                AboutFeature(
                    Icons.Outlined.Link, "Standart (varsayılan)",
                    "Google'ın kendi kumanda uygulamasının yöntemi. TV'de hazırlık gerekmez, ekrandaki 6 haneli kodla eşleşirsin. " +
                        "Kumanda, Türkçe yazma, sesli komut ve Cast için yeterlidir."
                )
                AboutFeature(
                    Icons.Outlined.Terminal, "ADB (gelişmiş)",
                    "Android'in geliştirici bağlantısı. TV'de geliştirici seçeneklerini açman gerekir; karşılığında fare, " +
                        "uygulama listesi, şu an çalan ve Araçlar sekmesi açılır. Nasıl açılacağı Sıkça sorulan sorular'da."
                )
            }
            Spacer(Modifier.height(24.dp))

            AboutHeader("Gizlilik")
            AboutCard {
                AboutBody(
                    "Hesap, reklam ve kullanım takibi yok. Ayarların ve kayıtların telefonunda kalır; TV ile konuşma ev ağında olur. " +
                        "İnternet yalnızca senin başlattığın işlerde kullanılır: güncelleme denetimi, çevrimiçi IR kodu arama, " +
                        "internet adresinden oynatma ya da indirme. Sesli komutta ses, telefonun konuşma tanıma servisine ya da TV'ye gider. " +
                        "Teşhis ve çökme kayıtları sen paylaşmadıkça telefondan çıkmaz."
                )
            }
            Spacer(Modifier.height(24.dp))

            AboutHeader("Hangi TV'lerde çalışır?")
            AboutCard {
                AboutBody(
                    "Philips Android TV'de geliştirildi ve test edildi. Diğer Android TV ve Google TV cihazlarında çoğu özelliğin " +
                        "çalışması beklenir ama denenmedi. Telefon için Android 8.0 ya da üstü gerekir; IR dışındaki bağlantılarda " +
                        "telefonla TV aynı Wi-Fi ağında olmalı."
                )
            }
            Spacer(Modifier.height(24.dp))

            AboutHeader("Açık kaynak")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn)) {
                Text(
                    "Kaynak kodu herkese açık, GPL-3.0 lisanslı. Bir şey çalışmadıysa ya da kendi TV'nde denediysen sonucunu bildirmen çok işe yarar.",
                    color = Pal.Sub, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)
                )
                AboutLink(Icons.Outlined.BugReport, "Sorun bildir", openIssues)
                AboutLink(Icons.Outlined.Gavel, "Lisans", onOpenLicense)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AboutHeader(text: String) {
    Text(text, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun AboutBody(text: String) {
    Text(text, color = Pal.Sub, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun AboutFeature(icon: ImageVector, title: String, text: String) {
    Row {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(text, color = Pal.Sub, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun AboutLink(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = Pal.Sub)
    }
}
