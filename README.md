# TV Kumanda

Android TV / Google TV'yi telefondan yöneten hepsi bir arada kumanda: kumanda, Cast ve TV araçları tek uygulamada.
Sunucu yok, hesap yok; her şey ev ağında doğrudan TV'ye gider.

> **Philips Android TV'de geliştirildi ve test edildi.** Diğer Android TV / Google TV cihazlarında çoğu özelliğin
> çalışması beklenir ama denenmedi. Kendi TV'nde denediysen sonucunu [bildirmen](#sorun-bildirme) çok işe yarar.

## İçindekiler

- [İki bağlantı türü](#iki-bağlantı-türü)
- [Özellikler](#özellikler)
- [Kurulum](#kurulum)
- [Sorun giderme](#sorun-giderme)
- [Sorun bildirme](#sorun-bildirme)
- [Kaynaktan derleme](#kaynaktan-derleme)
- [Lisans](#lisans)

## İki bağlantı türü

| | **Standart** (varsayılan) | **ADB** (gelişmiş) |
|---|---|---|
| TV'de hazırlık | Gerekmez | Geliştirici seçenekleri ve USB hata ayıklama açılır |
| İlk bağlantı | TV ekranındaki 6 haneli kodla eşleşme | TV'de "Her zaman izin ver" onayı |
| Ne yapabilir | Kumanda, Türkçe yazma, sesli komut, Cast | Standart'ın hepsi + fare, uygulama listesi, şu an çalan, Araçlar sekmesi |

Standart bağlantı, Google'ın kendi kumanda uygulamasının kullandığı yöntemdir. ADB, Android'in geliştirici bağlantısıdır;
daha fazla yetki verir, o yüzden ayrıca açılması gerekir.

## Özellikler

### Kumanda
- Yön tuşları, OK, Geri, Ana ekran, Güç, ses açma/kısma ve sessiz
- Üç kontrol modu: yön tuşları, kaydırma ve fare (fare yalnızca ADB)
- TV'ye yazma: telefonun klavyesiyle canlı yazma; Standart bağlantıda Türkçe harfler (ş, ğ, ı…) olduğu gibi gider
- Sesli komut: telefonun mikrofonuyla TV'de arama
- Telefonun ses tuşları TV'nin sesini açar/kısar
- Uygulama satırı: TV'deki uygulamalar gerçek adları ve simgeleriyle, istediğin sırayla (ADB)
- Şu an çalan: TV'de oynayan medya için oynatıcı çubuğu (ADB)
- Birden fazla TV kaydetme, ağda otomatik TV bulma, Wake-on-LAN ile uyandırma

### Cast
- Telefondaki fotoğraf, video ve müzikleri TV'ye gönderme (Chromecast veya DLNA)
- Klasör sırasıyla oynatma, müzikte tekrar modu, videoda kaldığın yerden devam
- SRT altyazı (Türkçe karakter kodlaması otomatik düzeltilir)
- Telefon ekranı kapalıyken de devam eder; bildirimde ve kilit ekranında oynatıcı kontrolleri
- Paylaş menüsünden "TV'de aç": medya dosyaları ve linkler
- **DLNA Sunucu:** telefon ağda medya sunucusu olur; TV'nin kendi oynatıcısından telefondaki klasörlere göz atılır

### Araçlar
Çoğu araç ADB bağlantısı gerektirir. ⚠ işaretli olanlar TV ayarlarını değiştirir; dikkatli kullan.

- **Sistem:** sistem bilgisi, RAM boşaltma, önbellek temizleme, yeniden başlatma
- **Uygulamalar:** uygulama yöneticisi, APK yükleme, uygulama yedekleme, gereksiz uygulamaları kapatma ⚠
- **Dosyalar:** TV'ye dosya gönderme, TV dosya gezgini, TV'nin kendi oynatıcısında açma (deneysel)
- **Ekran:** ekran görüntüsü, ekran kaydı (deneysel; TV ekranı videoya çekilir, en fazla 3 dk, ses yok, ADB), animasyon hızı ⚠
- **Gelişmiş:** özel DNS ⚠, ana ekran değiştirme ⚠, ADB komut satırı, sistem kayıtları
- **İçerik ve tuşlar:** linki TV'de açma, panodan yazma, medya tuşları, sistem kısayolları
- **Zamanlama ve güç:** uyku zamanlayıcısı (ADB'de son dakikada ses yavaşça kısılır), zamanlı açma (Wi-Fi ile uyandırma,
  tek sefer ya da her gün), ses sınırı (ADB), ekran süresi sınırı (günlük süre ve gece saatleri, ADB). ADB'li araçları TV'de
  arka planda çalışan küçük bir "nöbetçi" yürütür; telefon kapalıyken de geçerlidir
- **Ağ ve test** (ADB gerekmez, TV'ye bağlı olmadan da açılır): ağ tarayıcı (ağdaki TV'ler üstte, diğer cihazlar
  altta; bulunan TV'ye oradan bağlanılır), tüm TV'lere komut (kayıtlı TV'leri aynı anda kapat, aç, sessize al,
  ses aç/kıs, ana ekran), bağlantı testi (Wi-Fi, VPN, TV'nin kapıları ve gecikme;
  sorun varsa tek cümlelik çözüm), teşhis kaydı (bağlanma, Cast ve araç hatalarının günlüğü; IP'ler gizlenerek paylaşılabilir)

- **Deneysel (telefondan TV'ye canlı yayın):** ekran yansıtma (telefonun ekranı ve sesi TV'de görünür) ve telefonu TV
  hoparlörüne bağlama (telefonda çalan ses TV'den gelir). İkisi de ADB gerektirmez. TV'de 2-5 saniye gecikme olur;
  slayt, fotoğraf, müzik ve podcast için uygundur, oyun ya da video izlerken ses senkronu için değil. Telefon sesini
  yakalamak Android 10 ve üstünü ister; bazı uygulamalar (ör. Spotify) sesinin yakalanmasına izin vermez

### Henüz hazır olmayanlar
Uygulamada görünen ama henüz çalışmayan bölümler: Kısayollar sekmesi.

## Kurulum

**Gerekenler:** Android 8.0 veya üstü bir telefon, Android TV / Google TV, ikisi de aynı Wi-Fi ağında.

1. [Releases](../../releases/latest) sayfasından en son `TvKumanda-x.y.apk` dosyasını telefona indirip kur.
   Android "bilinmeyen kaynak" ya da Play Protect uyarısı verebilir; Play Store dışından kurulan her uygulamada görülür.
2. Uygulamayı aç ve TV'ler penceresinde ağda bulunan TV'ni seç. Bulunmazsa TV'nin IP adresini elle gir
   (TV'de: Ayarlar → Ağ ve İnternet → Wi-Fi ağın → IP adresi).
3. **Standart:** TV'de çıkan 6 haneli kodu telefona yaz. Bu kadar.
4. **ADB (isteğe bağlı):**
   1. TV'de Ayarlar → Sistem (veya Cihaz Tercihleri) → Hakkında → **Android TV OS derlemesi**ne 7 kez bas.
   2. Ayarlar'da açılan **Geliştirici seçenekleri** → **USB hata ayıklama**yı aç.
      Menüde varsa **Ağ üzerinden hata ayıklama**yı da aç.
   3. Uygulamada ADB ile bağlan; TV'de "USB hata ayıklamaya izin verilsin mi?" sorusuna
      **Her zaman izin ver** → Tamam.

Menü adları markaya göre biraz farklı olabilir.

## Sorun giderme

Önce **Araçlar → Bağlantı testi**ni dene: sorunun nerede olduğunu adım adım gösterir.

| Belirti | Ne yapmalı |
|---|---|
| TV bulunamıyor | Telefon ve TV aynı Wi-Fi ağında mı? Telefonda VPN açıksa kapatıp tekrar dene |
| ADB: bağlanamadı / connection refused | TV 5555 portunu dinlemiyor. Bilgisayardan bir kez `adb tcpip 5555` çalıştırmak gerekebilir |
| TV'de yalnızca kodlu "Kablosuz hata ayıklama" var | Bu eşleştirme yöntemi henüz desteklenmiyor. Bilgisayardan `adb pair` ve `adb tcpip 5555` ile 5555 bir kez açılabilir |
| TV yeniden başlayınca ADB koptu | Bazı modellerde 5555 kapanır; yukarıdaki adımı tekrarla |
| ADB onay sorusu kayboldu | Uygulamada yeniden bağlan; soru TV'de tekrar çıkar |
| Netflix vb. ekran görüntüsünde siyah | Korumalı videolar görüntülenemez; bu bir hata değil |
| MKV, AVI gibi videolar Cast'te açılmıyor | TV'nin Chromecast oynatıcısı bu biçimleri genelde desteklemez. DLNA Sunucu ile TV'nin kendi oynatıcısını dene |

## Sorun bildirme

Bir şey çalışmadıysa [Issues](../../issues) sayfasından bildir ve şunları yaz:

- TV marka ve modeli
- TV'nin Android sürümü (Ayarlar → Hakkında)
- Bağlantı türü: Standart mı, ADB mi
- Ne yaptın, ne bekledin, ne oldu
- Mümkünse teşhis kaydı: **Araçlar → Teşhis kaydını paylaş → Paylaş** (IP adresleri gizlenir)

Bir şey **çalıştıysa** da yaz: farklı TV'lerde çalıştığı bildirilen modeller burada listelenecek.

## Kaynaktan derleme

- **Android Studio:** projeyi aç, Gradle senkronizasyonunu bekle, ▶ Run. JDK 17 gerekir.
  Yerel derlemede yalnızca debug sürümü çalıştırılabilir; release sürümü imzasız kalır.
- **GitHub Actions:** her gönderimde imzalı APK derlenir ve Releases'a yüklenir. Fork'larda kendi imza anahtarını
  Settings → Secrets and variables → Actions altına eklemen gerekir: `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD` ve isteğe bağlı `ANDROID_KEY_ALIAS`.

## Lisans

Copyright (C) 2026 serif453

Bu program özgür yazılımdır: [GNU Genel Kamu Lisansı sürüm 3](LICENSE) (GPL-3.0) koşulları altında dağıtabilir
ve değiştirebilirsin. Değiştirilmiş bir sürümü dağıtırsan, kaynak kodunu da aynı lisansla paylaşman gerekir.
Program yararlı olacağı umuduyla, ancak hiçbir garanti olmaksızın dağıtılmaktadır.

### Kullanılan açık kaynak yazılımlar

| Proje | Kullanım | Lisans |
|---|---|---|
| [dadb](https://github.com/mobile-dev-inc/dadb) | ADB bağlantısı (değiştirilmiş kopya: `app/src/main/java/dadb`) | Apache-2.0 |
| [androidtvremote2](https://github.com/tronikos/androidtvremote2) | Standart bağlantının protokol tanımları için kaynak | Apache-2.0 |
| [Haze](https://github.com/chrisbanes/haze) | Alt menünün bulanık cam görünümü | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) ve Material ikonları | Arayüz | Apache-2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Okio](https://github.com/square/okio) | Arka plan işleri, veri akışı | Apache-2.0 |
