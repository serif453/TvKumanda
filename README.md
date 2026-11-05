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
- Yön tuşları, OK, Geri, Ana ekran, Güç, ses açma/kısma ve sessiz; büyük ses çubuğu (dokun ya da sürükle)
- Bilgi ve Rehber tuşları; "fn" ile sistem kısayolları, 🎵 ile medya tuşları penceresi
- Üç kontrol modu: yön tuşları, kaydırma ve fare (fare yalnızca ADB); yön tuşları ile kaydırma TV adının yanındaki ⌄ menüsünden seçilir ve hatırlanır
- Fare: ayarlanabilir imleç hızı, sola ya da sağa alınabilen kaydırma şeridi, basılı tutup sürükleme / seçme, parmak altında halka
- TV'ye yazma: telefonun klavyesiyle canlı yazma; Standart bağlantıda Türkçe harfler (ş, ğ, ı…) olduğu gibi gider
- Sesli komut: telefonun mikrofonuyla TV'de arama
- Telefonun ses tuşları TV'nin sesini açar/kısar (Ayarlar'dan kapatılabilir)
- Uygulama satırı: TV'deki uygulamalar gerçek adları ve simgeleriyle, basılı tutup sürükleyerek istediğin sırayla (ADB)
- Şu an çalan: TV'de bir şey oynarken kumandada alt sağda uygulamanın küçük simgesi çıkar; dokununca tam ekran "Şimdi oynatılıyor" sayfası (sarma çubuğu, önceki/geri/oynat/ileri/sonraki, TV sesi) açılır (ADB)
- Birden fazla TV kaydetme, ağda otomatik TV bulma, Wake-on-LAN ile uyandırma
- TV adına dokununca açılan küçük panel: kayıtlı TV'ler ve IR kumandalar arasında tek dokunuşla geçiş, ADB / Standart seçimi

### Ayarlar
Sol üstteki ⚙️ düğmesiyle açılan tam sayfa:
- Kumanda tuşlarında titreşim (Hafif, Orta, Güçlü), telefonun ses tuşlarının TV'yi kontrol edip etmeyeceği, ekranın kararmaması (kapalı, yalnızca Kumanda'da ya da tüm sekmelerde)
- Fare imleç hızı ve kaydırma şeridinin tarafı
- Açılışta son TV'ye kendiliğinden bağlanma; kayıtlı TV'ler ve bağlantı türü
- Açılış sekmesi (Kumanda, Cast, Araçlar ya da son kalınan)
- Arka plan teması: Mor, Lacivert, Gece (tam siyah), Orman, Bordo
- Alt menü: istemediğin sekmeleri gizleme (en az biri açık kalır; tek sekme kalınca alt menü gizlenir)
- Hakkında: uygulamayı anlatan tanıtım sayfası, sürüm, güncelleme denetimi (yalnızca dokununca GitHub'a bakar), sıkça sorulan sorular, çökme kaydı (son 5 çökme, telefonda kalır, kopyalanabilir), lisans sayfası ve GitHub sayfası

### Cast
- Telefondaki fotoğraf, video ve müzikleri TV'ye gönderme (Chromecast veya DLNA)
- Tek cast düğmesi: basınca o an ağdaki Chromecast ve DLNA cihazları tek listede aranır, dokununca bağlanılır (kumanda bağlantısı gerekmez)
- Fotoğraflar telefonda galeri gibi açılır (kaydırma, yakınlaştırma, altta küçük resim şeridi). Bir cihaza bağlıyken baktığın foto TV'de de açılır ve telefonda kaydırdıkça TV takip eder; üstteki "Takip" ile bu kapatılabilir. Aşağı çekince kapanır
- Müzikler telefonda da çalınabilir: bağlı değilken şarkıya dokununca telefonda çalar ve altta küçük kart çıkar; karta dokununca ya da yukarı sürükleyince tam ekran oynatıcı açılır (10 sn ileri/geri, telefonun ses düzeyi), aşağı çekince yine karta küçülür. Çalarken üstten bir cihaza bağlanınca şarkı kaldığı saniyeden TV'de devam eder. Yalnızca uygulama açıkken çalar
- Bağlanmadan albümlerde gezinme; bağlı değilken bir öğeye dokununca cihaz listesi açılır, seçince o öğe oynar
- URL'den oynatma: videonun doğrudan adresi (.mp4, .webm, .m3u8…) yazılır ya da panodan gelir, TV kendisi çeker; son adresler listelenir. Paylaşılan doğrudan video linkleri de böyle oynar
- Son cihazı hatırlama Ayarlar'dan: hiç, listede başta ya da otomatik bağlan
- TS / M2TS videoları (deneysel, telefondaki dosyalar, Chromecast): H.264 görüntü + AAC ya da MP3 ses içerenler yeniden işlenmeden, parçalanarak oynar; kalite düşmez. Oynatılamayanlarda (MPEG-2, HEVC, şifreli kayıt ya da AC-3, MP2 gibi ses) nedeni söylenir ve DLNA önerilir
- Klasör sırasıyla oynatma, müzikte tekrar modu, videoda kaldığın yerden devam
- SRT altyazı (Türkçe karakter kodlaması otomatik düzeltilir)
- Telefon ekranı kapalıyken de devam eder; bildirimde ve kilit ekranında oynatıcı kontrolleri
- Paylaş menüsünden "TV'de aç": medya dosyaları ve linkler. Cihaza bağlı değilse cihaz listesi açılır, seçince hemen oynar
- Paylaş menüsünün üst satırında "TV'de oynat" ve "TV'ye gönder" kısayolları (telefonun paylaşım menüsü destekliyorsa)
- **DLNA Sunucu:** telefon ağda medya sunucusu olur; TV'nin kendi oynatıcısından telefondaki klasörlere göz atılır

### Araçlar
Çoğu araç ADB bağlantısı gerektirir. ⚠ işaretli olanlar TV ayarlarını değiştirir; dikkatli kullan.

- **Sistem:** sistem bilgisi (cihaz, işlemci çekirdekleri ve 32/64 bit uygulama türü, sıcaklık, bellek, depolama ve USB,
  ekran modu, yenileme hızları ve HDR, Wi-Fi sinyali ve bandı, uygulama sayısı; ham döküm ve IP/MAC/seri numarası gizlenmiş
  dosya olarak paylaşma), performans (genel bakış: TV açık mı uykuda mı, ekrandaki uygulama, ADB gecikmesi, açık kalma süresi, depolama; sistem yükü ve RAM boşaltma; en çok işlemci kullanan 5 uygulama; ağ: anlık indirme/yükleme, Wi-Fi adı, sinyal, bağlantı kapasitesi, bant, IP ve kısa öneri; donanım: model, Android ve güvenlik yaması, yonga, mimari, çekirdekler, RAM, ekran ve dpi; canlı çekirdekler ve son 1 dakika grafiği), çalışan uygulamalar (önce kendi uygulamaların, sistem uygulamaları ayrı düğmeyle; bellek ve işlemci kullanımı, ekrandaki uygulama, durdurma, toplu durdurma, TV'de açma ve uygulama bilgisi), RAM boşaltma, önbellek temizleme, yeniden başlatma; uygulama yöneticisi, APK yükleme, uygulama yedekleme, gereksiz uygulamaları kapatma ⚠
- **Dosyalar:** TV'ye dosya gönderme (paylaş menüsünden "TV'ye gönder" ile her türlü dosya, her uygulamadan), TV dosya gezgini, TV'nin kendi oynatıcısında açma (deneysel)
- **Ekran:** ekran görüntüsü, ekran kaydı (deneysel; TV ekranı videoya çekilir, en fazla 3 dk, ses yok, ADB), animasyon hızı ⚠
- **Gelişmiş:** özel DNS ⚠, ana ekran değiştirme ⚠, ADB komut satırı, sistem kayıtları
- **İçerik açma:** linki TV'de açma, panodan yazma (medya tuşları ve sistem kısayolları Kumanda'daki 🎵 ve fn tuşlarında)
- **Zamanlama ve güç:** uyku zamanlayıcısı (ADB'de son dakikada ses yavaşça kısılır), zamanlı açma (Wi-Fi ile uyandırma,
  tek sefer ya da her gün), ekran süresi sınırı (günlük süre ve gece saatleri, ADB). ADB'li araçları TV'de
  arka planda çalışan küçük bir "nöbetçi" yürütür; telefon kapalıyken de geçerlidir
- **Ağ ve test** (ADB gerekmez, TV'ye bağlı olmadan da açılır): ağ tarayıcı (ağdaki TV'ler üstte, diğer cihazlar
  altta; bulunan TV'ye oradan bağlanılır), tüm TV'lere komut (kayıtlı TV'leri aynı anda kapat, aç, sessize al,
  ses aç/kıs, ana ekran), bağlantı testi (Wi-Fi, VPN, TV'nin kapıları ve gecikme;
  sorun varsa tek cümlelik çözüm), teşhis kaydı (bağlanma, Cast ve araç hatalarının günlüğü; IP'ler gizlenerek paylaşılabilir)

- **IR kumanda** (telefonda kızılötesi verici varsa): TV seçimi penceresinden "IR kumanda ekle" → marka seç → kodu dene.
  100'den fazla marka; Wi-Fi ve bağlantı gerekmez, eski TV'lerde de çalışır. Yalnızca klasik tuşlar (güç, ses, kanal, yön,
  rakamlar, oynatma); yazı yazma ve araçlar IR'da yok. Kodlar [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)'den (CC0)
  ve [SmartIR](https://github.com/smartHomeHub/SmartIR)'dan (MIT; Xiaomi, Kivi, Noblex, OKI, TurboX, Andersson ve büyük markalara ek kodlar)
  - Aynı fabrikadan çıkan markalar birbirinin kodlarını da dener: Vestel ailesi (Vestel, Regal, Telefunken, Finlux,
    Toshiba, Hitachi, JVC…) ve Arçelik ailesi (Arçelik, Beko, Grundig, Altus)
  - "Markamı bilmiyorum": veritabanındaki tüm farklı güç kodlarını sırayla dener, TV kapanınca doğru kodu bulur
  - "Dosyadan ekle": Flipper Zero kumanda dosyası (.ir); uygulamanın tanımadığı tuşlar "Diğer tuşlar"da adıyla görünür
  - "Çevrimiçi kod ara": [irdb](https://github.com/probonopd/irdb)'den 600'den fazla marka; kodlar yalnızca istenince
    internetten indirilir, uygulamanın içinde gelmez. Kaydedilen kumanda internetsiz çalışır.
    *Contains/accesses irdb by Simon Peter and contributors, used under permission. For licensing details and for
    information on how to contribute to the database, see https://github.com/probonopd/irdb*

- **Deneysel (telefondan TV'ye canlı yayın):** ekran yansıtma (telefonun ekranı ve sesi TV'de görünür) ve telefonu TV
  hoparlörüne bağlama (telefonda çalan ses TV'den gelir). İkisi de ADB gerektirmez; ADB ile bağlıyken yayın TV'deki VLC'de doğrudan açılır (en iyi bu çalışıyor). TV'de 2-3 saniye gecikme olur;
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
| TS videosu Cast'te açılmıyor | Uygulama nedenini yazar; görüntü ya da ses biçimi uygun değilse DLNA Sunucu ile TV'nin kendi oynatıcısını dene |

## Sorun bildirme

Bir şey çalışmadıysa [Issues](../../issues) sayfasından bildir ve şunları yaz:

- TV marka ve modeli
- TV'nin Android sürümü (Ayarlar → Hakkında)
- Bağlantı türü: Standart mı, ADB mi
- Ne yaptın, ne bekledin, ne oldu
- Mümkünse teşhis kaydı: **Araçlar → Teşhis kaydını paylaş → Paylaş** (IP adresleri gizlenir)
- Uygulama kapandıysa çökme kaydı: **Ayarlar → Hakkında → Çökme kaydı → Kopyala**

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
| [Inter](https://github.com/rsms/inter) | Kumanda sekmesinin yazı tipi (yalnızca Latin + Türkçe harfler; lisans: `app/src/main/assets/fonts/INTER-LICENSE.txt`) | SIL OFL 1.1 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) ve Material ikonları | Arayüz | Apache-2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Okio](https://github.com/square/okio) | Arka plan işleri, veri akışı | Apache-2.0 |
