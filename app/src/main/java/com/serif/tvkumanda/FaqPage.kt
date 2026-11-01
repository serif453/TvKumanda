package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class Faq(val q: String, val a: String)

/** Serif'in onayladığı içerik (1 Kasım 2026). Değişiklik yaparken README'deki Kurulum / Sorun giderme ile uyumlu tut. */
private val faqGroups: List<Pair<String, List<Faq>>> = listOf(
    "Başlarken" to listOf(
        Faq(
            "Standart ile ADB arasındaki fark ne?",
            "Standart, Google'ın kendi kumanda uygulamasının yöntemi; TV'de hazırlık gerekmez, 6 haneli kodla eşleşirsin. " +
                "ADB, Android'in geliştirici bağlantısı; fare, uygulama listesi, şu an çalan ve Araçlar sekmesi için gerekir."
        ),
        Faq(
            "ADB'yi nasıl açarım?",
            "TV'de Ayarlar → Sistem → Hakkında → \"Android TV OS derlemesi\"ne 7 kez bas. Açılan Geliştirici seçenekleri'nden " +
                "\"USB hata ayıklama\"yı aç. Bağlanınca TV'de çıkan soruya \"Her zaman izin ver\" de. Menü adları markaya göre biraz farklı olabilir."
        ),
        Faq(
            "Telefonla TV aynı ağda olmak zorunda mı?",
            "Evet, ikisi aynı Wi-Fi ağında olmalı. IR kumanda bunun dışında; o Wi-Fi'sız çalışır."
        )
    ),
    "Bağlantı sorunları" to listOf(
        Faq(
            "TV listede çıkmıyor.",
            "Aynı Wi-Fi'da olduğundan emin ol, telefonda VPN açıksa kapat. Olmazsa TV'nin IP adresini elle gir " +
                "(TV'de Ayarlar → Ağ → Wi-Fi ağın)."
        ),
        Faq(
            "ADB \"bağlanamadı\" diyor.",
            "TV 5555 kapısını açmamış olabilir. Bazı modellerde bir kez bilgisayardan \"adb tcpip 5555\" çalıştırmak gerekir."
        ),
        Faq(
            "TV yeniden başlayınca ADB koptu.",
            "Bazı TV'ler açılışta bu kapıyı kapatır; yukarıdaki adım tekrarlanır."
        ),
        Faq(
            "Sorunun nerede olduğunu nasıl anlarım?",
            "Araçlar → Bağlantı testi adım adım gösterir."
        )
    ),
    "Kumanda" to listOf(
        Faq(
            "Türkçe harfler neden bazen düzgün gitmiyor?",
            "Standart bağlantıda ş, ğ, ı olduğu gibi gider. ADB'de sadeleşir (ş → s)."
        ),
        Faq(
            "Telefonun ses tuşları TV'nin sesini değiştiriyor, kapatabilir miyim?",
            "Evet: Ayarlar → Kumanda → \"Ses tuşları TV'yi kontrol etsin\"."
        ),
        Faq(
            "Fare modu neden görünmüyor?",
            "Fare yalnızca ADB bağlantısında çalışır."
        )
    ),
    "Cast ve medya" to listOf(
        Faq(
            "MKV ya da AVI videolar açılmıyor.",
            "TV'nin Chromecast oynatıcısı bu biçimleri genelde desteklemez. Cast'teki DLNA Sunucu ile TV'nin kendi oynatıcısını dene."
        ),
        Faq(
            "Netflix ekran görüntüsü siyah çıkıyor.",
            "Korumalı videolar görüntülenemez; bu bir hata değil."
        )
    ),
    "Uygulama hakkında" to listOf(
        Faq(
            "Uygulama verilerimi bir yere gönderiyor mu?",
            "Hayır. Her şey telefonla TV arasında, ev ağında kalır. Tek istisna: IR kumanda ararken çevrimiçi kod veritabanına bağlanır."
        ),
        Faq(
            "Hangi TV'lerde çalışır?",
            "Android TV ve Google TV. Philips Android TV'de geliştirilip test ediliyor; diğer markalarda bazı araçlar farklı davranabilir."
        ),
        Faq(
            "Neden Play Store'da yok?",
            "Uygulama henüz gelişiyor; olgunlaşınca gelecek. Şimdilik GitHub'dan kuruluyor, kurarken Android'in \"bilinmeyen kaynak\" uyarısı normal."
        ),
        Faq(
            "Bir sorunu nasıl bildiririm?",
            "Araçlar → \"Teşhis kaydını paylaş\" ile kaydı al, GitHub'daki Issues sayfasına TV modelin ve bağlantı türünle birlikte yaz."
        )
    )
)

/** Tam ekran Sıkça sorulan sorular sayfası (Ayarlar → Hakkında'dan açılır). Soruya dokununca cevap açılır. */
@Composable
fun FaqPage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var open by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Pal.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Sıkça sorulan sorular", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            for ((group, items) in faqGroups) {
                Text(group, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn)) {
                    for (item in items) {
                        val expanded = open == item.q
                        val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "faqArrow")
                        Column(
                            Modifier.fillMaxWidth().clickable { open = if (expanded) null else item.q }
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.q, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.ExpandMore, null, tint = Pal.Sub, modifier = Modifier.size(22.dp).rotate(turn))
                            }
                            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Text(item.a, color = Pal.Sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
