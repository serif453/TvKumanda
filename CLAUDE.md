# TV Kumanda — çalışma notları

Bu dosya, projede çalışan her yeni sohbetin ilk okuduğu yerdir. Kod neyi yaptığını söyler; burası **neden** öyle
yapıldığını ve **nasıl** çalışılacağını anlatır. Önemli bir karar alındığında buraya bir satır eklenir.

## Proje

- Android TV / Google TV için telefondan "İsviçre çakısı" kumanda: kumanda, Cast ve TV araçları tek uygulamada.
- Sahibi: serif453 (Serif). Yazılım bilgisi yok; açıklamalar sade Türkçe, teknik terim gerekiyorsa kısa açıklamasıyla.
- Hedef kitle: meraklılar (Android TV forumları, XDA, Reddit). Play Store sonra, uygulama tamamen olgunlaşınca.
- Philips Android TV'de geliştirilir ve test edilir. Önce Philips'te kusursuz çalışsın; başka TV'lere uyum en sona,
  katman katman eklenir.
- Lisans: GPL-3.0. Uygulama arayüzü Türkçe.

## Çalışma kuralları

- **Derlemeden ya da arayüz değişikliğinden önce** ne yapılacağını anlat ve Serif'in "okey"ini bekle.
  `main`'e her gönderim bir derleme ve yeni sürüm başlatır (bkz. Derleme).
- Önce işlevler, sona görünüş: özellikler basitten karmaşığa adım adım eklenir; arayüz cilası en son.
- Serif en titiz kullanıcıdır: yarım ya da kalitesiz bir şey göstermektense eksik olduğunu söyle.
- `main`'e gönderdikten sonra **derlemenin bitmesi beklenmez**: "gönderildi, birkaç dakikaya sürüm çıkar" deyip iş biter.
  Derleme hata verirse Serif söyler (Serif'in kararı; beklemek gereksiz uzatıyordu). Bunun yerine birkaç dakika sonrasına
  kontrol kurulur (send_later); sürüm çıkınca Serif'e "çıktı" denip sürüm sayfasının linki paylaşılır.
- Yeni bir özellik eklerken README'deki özellik listesi ve "Henüz hazır olmayanlar" da güncellenir.

### Commit kuralları

- Commit mesajı **Türkçe** ve kullanıcının anlayacağı dilde: ilk satır doğrudan sürüm notuna yazılır.
  Örnek: `Araçlar: Özel DNS eklendi (otomatik geri dönüş)`.
- Mesajlara **Co-Authored-By / Claude-Session satırı eklenmez.**
- Yazar: `serif453 <202360588+serif453@users.noreply.github.com>`. Gerçek e-posta hiçbir commit'e girmez.
- İmza anahtarı, şifre, token gibi gizli bilgiler asla depoya girmez; yalnızca GitHub Secrets'ta durur.
- Repo herkese açık olacak: geçmiş yeniden yazılmaz, `main`'e zorla gönderim yapılmaz.

## Derleme ve sürüm

- Claude oturumlarında Android SDK yok; derleme yalnızca **GitHub Actions**'ta olur (`.github/workflows/build.yml`).
  Saf (Android'siz) kod ve testleri oturumda Kotlin derleyicisiyle (GitHub Releases'tan, JetBrains/kotlin) ve
  `/opt/gradle-*/lib/junit-4.13.2.jar` ile yerelde çalıştırılabilir; `main`'e göndermeden hataları yakalar.
- `main`'e her gönderimde: birim testleri (`testReleaseUnitTest`) + imzalı, küçültülmüş (R8) release APK →
  yeni sürüm `v0.N` olarak Releases'a yüklenir. Hata veren derleme numara tüketmez.
- Sürüm numarası etiketlerden hesaplanır (son etiket + 1). Bu repo 0.96'dan başladı; 0.1–0.95 eski, gizli
  `TvKumanda-eski` reposunda.
- Sürüm notları: önceki sürümden bu yana commit'lerin ilk satırları.
- Derleme başlatmayan dosyalar: `README.md`, `LICENSE`, `CLAUDE.md`. Küçük değişiklikleri tek gönderimde toplamak
  derleme sayısını azaltır.
- İmza sırları: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` (anahtar adı `tvkumanda`).
- Dış eylemler (gradle/actions, softprops/action-gh-release) commit kimliğiyle kilitli; sürüm yükseltirken
  yeni kimlik `git ls-remote` ile alınır.
- Derleme hızlandırma: Gradle 6 GB, Kotlin 3 GB bellek (herkese açık repoda makine 16 GB), derleme önbelleği açık
  (`gradle.properties`). Lint kapatılmaz; simge kütüphanesi (material-icons-extended) yerinde kalır (Serif'in kararları).
- Süre raporu: `.github/gradle/sure-raporu.gradle` en uzun 8 Gradle adımını derleme sayfasına not olarak yazar;
  `gh api repos/serif453/TvKumanda/check-runs/<job id>/annotations` ile okunur (derleme kayıtlarına oturumdan erişilemiyor).
  Uygulama kodu değişmeyen derlemede her şey önbellekten gelir (~45 sn); gerçek ölçüm kod değişen derlemede alınır.

## Mimari

Tek modül (`app`), Jetpack Compose. Paket: `com.serif.tvkumanda`.

### Bağlantı (`RemoteLink`, `ConnMethod`)
- **Standart (varsayılan)** — `AtvRemote.kt`, `AtvProtocol.kt`: Google'ın Android TV Remote v2 protokolü
  (tronikos/androidtvremote2'den birebir). Eşleştirme TLS 6467, kumanda TLS 6466. İstemci anahtarı Android
  Keystore'da değil uygulama içinde tutulur (Keystore'da TLS imza hatası çıkıyordu). Türkçe harfleri destekler.
- **ADB (gelişmiş)** — `TvRemote.kt`: port 5555, dadb ile (`app/src/main/java/dadb`, akış denetimi eklenmiş kopya).
  - **Hızlı mod (⚡):** `KeyServer.java` uygulamanın APK'sından TV'de `app_process` ile çalışır; tuş, yazı, fare
    ve uygulama listesini tek açık bağlantıdan alır, her tuş için yeni süreç açılmaz. Adı ve `main()` ProGuard'da
    korunur (`proguard-rules.pro`).
  - ADB'de yazı `input text` ile gider; Türkçe harfler sadeleşir (ş→s).
- `TvDiscovery.kt`: mDNS (`_androidtvremote2._tcp`) ile ağdaki TV'ler; `TvPing` kayıtlı TV'ler için hafif kontrol.
- Wake-on-LAN: `MainActivity.kt` içindeki `WakeOnLan`.

### Ekranlar
- `MainActivity.kt`: durum, bağlantı, Cast akışı, izinler. Alt menü `BottomNav.kt`: Kumanda / Cast / Araçlar / Kısayollar.
- Kumanda: `RemoteUi.kt`, `RemoteLayout.kt` (üst çubuk, uygulama satırı). Modlar: yön tuşları, kaydırma, fare (ADB).
- TV seçimi: `TvSheet.kt`.
- Cast: `CastScreen.kt`, `MediaLibrary.kt`, `FullPlayer.kt`.
- Araçlar: `ToolsScreen.kt` (kutucuklar), `ToolSheets.kt` / `ToolPages.kt` / `AdvancedTools.kt` (pencereler),
  `TvTools.kt` / `TvFiles.kt` / `TvAdvanced.kt` (ADB komutları). **`id`'si olmayan kutucuk henüz hazır değildir.**
  - Ağ ve test: `NetSheets.kt` (pencereler, `PhoneNet`: Wi-Fi/VPN/modem adresi), `ConnCheck.kt` (bağlantı testinin saf
    mantığı: kapı yoklama 6466/6467/5555 + sonuç satırları), `NetScan.kt` (ağ tarayıcı: telefonun /24'ünde 8 kapı,
    128 paralel deneme; adlar mDNS Android TV + `_googlecast` "fn", DLNA ve ters DNS'ten), `AllTvs.kt` + `AllTvSender`
    (tüm TV'lere komut: her kayıtlı TV'ye en son yöntemiyle geçici bağlantı; ADB'de `TvRemote.oneShot`, hızlı mod
    kurulmaz; bağlı TV'de mevcut bağlantı kullanılır. "Kapat" = uyku tuşu 223, "Aç" = WoL + 224).
    Serif'in tek TV'si var; birden fazla TV'de denenmedi. Bu grubun kutucukları `offline = true`: TV'ye bağlı
    olmadan da açılır, çünkü en çok bağlantı çalışmadığında lazım.
- Zamanlama ve güç: `TimerSheets.kt` (pencereler). Serif bu araçları kendisi kullanmayacak; seçimler Claude'a bırakıldı.
  - **Nöbetçi** `Watcher.java`: saf Java, TV'de `app_process` ile bağlantıdan bağımsız çalışır (`TvRemote.startTvProgram`,
    `setsid nohup`). Ayarlar `/data/local/tmp/tvkumanda-nobetci.conf` (uygulama yazar, `WatcherConfig.kt`), durum
    `.state` (nöbetçi yazar: pid, apk, bugünkü ekran süresi). 3 sn'de bir tur; ekran durumu 30 sn'de bir
    `dumpsys power`. İş kalmayınca kendiliğinden kapanır. `TvWatcher.kt` yönetir; her ADB bağlantısında
    `ensureRunning()` (TV yeniden başladıysa ya da uygulama güncellendiyse yeniden başlatır). ProGuard'da adı korunur.
  - Telefon alarmları `PhoneTimers.kt`: zamanlı açma (WoL + uyandırma tuşu, `AllTvSender` ile) ve Standart'ta uyku
    zamanlayıcısı. `setAlarmClock` (Android 12+ için alarm izni; yoksa birkaç dakika gecikebilen alarm),
    telefon yeniden başlayınca `TimerBootReceiver` geri kurar.
- Teşhis kaydı: `DiagLog.kt` — son 500 olay `filesDir/teshis.log`'da, çökme yakalayıcısı dahil. Bağlanma, eşleştirme,
  kopma, Cast hataları ve her araç hatası (`io()` içinden) yazılır. Paylaşırken IP'lerin son hanesi varsayılan olarak
  gizlenir. Dosya adı, video adı gibi kişisel bilgiler kayda yazılmaz.
- Sistem bilgisi: `SysInfo.kt` (saf): tek kabuk komutu (`SysInfo.COMMAND`, bölümler `##X` ile ayrılır: getprop, cpuinfo,
  cpufreq, thermal, meminfo, df, wm, `dumpsys display` mBaseDisplayInfo, ip, wifi, uname, pm) → bölümlü Türkçe satırlar.
  ARM çekirdek kodları ada çevrilir (0xd09 = Cortex-A73). Ham dökümde seri no/MAC gibi anahtarlar "gizlendi"; paylaşılan
  dosyada ayrıca IP son hane ve MAC son yarısı gizlenir. Pil bilgisi bilerek yok (TV'de pil yok, Android sahte değer verir).
  Serif'in TV'si: Philips (TPV) 2021/22 UHD, MediaTek MT5895, 4 × Cortex-A73, Android 11, yalnızca 32 bit uygulama, 2.7 GB RAM.
- Performans: `PerfStats.kt` (saf: tek komutla `/proc/stat` cpu satırları, MemTotal/MemAvailable, `cpu/present`; iki ölçüm
  farkından yüzde, iowait boşta sayılır; listede olmayan çekirdek "Kapalı") + `PerfSheet.kt` (bellek kartı, 2'li ızgarada
  halkalar). Pencere açıkken 1 sn'de bir ADB ile okur, kapanınca durur. Görünüm Serif'in gönderdiği ekran görüntüsünden.
  **Sıcaklık yok:** v0.106'da saniyede bir tüm thermal_zone'ları okumak Serif'in TV'sini yeniden başlattı (Philips logosu);
  v0.107'de kaldırıldı. Sistem bilgisi'ndeki tek seferlik okuma kaldı. "Son 1 dakika" grafiği
  (işlemci + bellek, 60 ölçüm). Kartlara dokununca "Çalışan uygulamalar" (Bellek/İşlemci sıralı); Sistem'de ayrı kutucuk da var.
  - Ölçüm: tek `ps -A -o PID,RSS,TIME+,NAME` (TIME+ = jiffy; ps bilmiyorsa `/proc/[pid]/stat`) 2 sn'de bir; işlemci tüm
    işlemcinin yüzdesi; süreçler paket adına toplanır. Ağır olanlar **sürekli çalışmaz** (Serif'in kararı, TV'yi yoruyor):
    ekrandaki uygulama (`dumpsys activity activities`) açılışta, durdurma/açmadan sonra ve "Yenile"de; PSS (`dumpsys
    meminfo`; gelene kadar RSS) açılışta ve "Yenile"de.
  - Hız: `PerfCache` (bağlantı başına) uygulama adlarını, son listeyi ve ölçümü tutar; Performans açılınca liste önceden
    hazırlanır. Açılış süresi teşhis kaydına yazılır ("Çalışan uygulamalar: liste N ms").
  - Liste ikiye ayrılır (`PerfStats.isUserApp`): üstte kullanıcının uygulamaları = ana ekranda simgesi olan (LEANBACK_LAUNCHER,
    bağlantı başına bir kez) ya da sonradan kurulan; altta "Sistem uygulamalarını göster" ile açılan sistem bölümü. Philips'te
    YouTube/Netflix fabrikadan sistem uygulaması geldiği için yalnızca "sistem" etiketine bakılmaz.
  - Durdur = `am force-stop`; `TvAdvanced.isProtected` olanlarda düğme yok. Toplu durdurma iki dokunuşla, yalnızca üstteki
    kullanıcı uygulamaları (ekrandaki hariç). Satıra dokununca: TV'de aç, TV'nin "Uygulama bilgisi" ekranı. Uygulama başına önbellek temizleme
    Android 11 kabuğunda yok; o ekrandan yapılır.
- Ekran kaydı: `ScreenRec.kt` — TV'nin `screenrecord` komutu ADB ile arka planda (`setsid nohup`) başlar, uygulamadan
  bağımsız sürer; uygulama açılınca `status()` süren ya da alınmamış kaydı bulur. Dosya TV'de `/data/local/tmp/tvk_rec.mp4`,
  bitince telefona çekilip silinir. En fazla 180 sn (Android sınırı), ses yok. Durdurma `kill -2` (dosya düzgün kapansın).
  16:9 ekranda `--size 1280x720` (4K TV'de dev dosya/kodlayıcı hatası olmasın), başka oranda boyut komuta bırakılır.
  UI: `LiveSheets.kt` içinde `ScreenRecTool`.
- Canlı yayın (ekran yansıtma + telefonu TV hoparlörü yap): ikisi aynı motoru kullanır, ADB gerekmez.
  - `LiveService.kt`: `mediaProjection` türünde ön plan hizmeti. Ekran → `MediaCodec` H.264 (VirtualDisplay yüzeyi, uzun kenar
    en çok 1280, 16'nın katı, 4 Mbps, 1 sn anahtar kare, durağan ekranda `repeat-previous-frame-after`); ses →
    `AudioPlaybackCaptureConfiguration` (Android 10+, RECORD_AUDIO ister) → AAC-LC 128 kbps. Durum `LiveStream` nesnesinde.
  - `LiveTs.kt`: saf MPEG-TS paketleyici (`TsMuxer`; PAT/PMT her anahtar karede, PCR görüntü PID'inde, PTS = zaman + 1 sn pay),
    `Adts` başlığı, `LiveSize`. ffmpeg ile doğrulandı (hatasız çözülür, yayının ortasından katılan oynatıcı da çalışır).
  - `LiveServer.kt`: bitmeyen HTTP yanıtı (`/live.ts` video/mp2t, `/live.aac` audio/aac). Görüntüde yeni izleyici ilk
    anahtar kareden başlar; yetişemeyen izleyicinin kuyruğu atılır, sonraki anahtar kareden devam eder.
  - TV'ye gönderme (`LiveSend`): önce DLNA (bağlı TV'nin IP'sine uyan oynatıcı), olmazsa ADB'de `am start VIEW -t <tür>`,
    Standart'ta kumandanın "bağlantı aç" isteği. Hiçbiri açmazsa kullanıcı adresi kopyalayıp TV'deki oynatıcıya (VLC) yapıştırır.
  - **Gerçek TV'de henüz denenmedi** (Philips'in canlı TS/ADTS'yi hangi yoldan açacağı bilinmiyor). Gecikme 2-5 sn beklenir.
    Telefon sesini kısma seçeneği yok (yakalamanın ses seviyesinden etkilenip etkilenmediği belirsiz).
- Kısayollar: `ShortcutsScreen.kt` — şimdilik yalnızca görünüm.
- **IR kumanda** (telefonun kızılötesi vericisi, `ConsumerIrManager`; IR yoksa bölüm hiç görünmez):
  - `IrCodec.kt` (saf): kod → süre dizisi. NEC, NECext, Samsung32, RC5, RC5X, RC6, SIRC(12/15/20), Kaseikyo, RCA, Pioneer
    ve ham (W). Adres/komut anlamı Flipper Zero kodlayıcılarıyla birebir (kodlar o cihazla kaydedildi). RC5/RC6 değişim biti
    her yeni basışta değişir, basılı tutunca değişmez. `IrCodecTest` bilinen kodlarla karşılaştırır (LG 20DF10EF, Samsung
    E0E040BF, Sony A90, Panasonic) ve gerçek veritabanının her kodunu kodlar.
  - Veritabanı `app/src/main/assets/ir/tv.txt`: `tools/ir_db_build.py` ile Flipper-IRDB `TVs/` klasöründen (CC0) üretilir.
    Tuş adları ortak ada çevrilir (vol_up, ch_up, ok…); aynı markanın kumandaları güç+ses koduna göre kod setlerine
    birleşir, çok kumandadan gelen set önce denenir. Vestel markası veritabanında yok (Telefunken/Toshiba/Finlux önerilir).
  - `IrRemote.kt`: TV seçimi penceresinde "IR KUMANDALAR" bölümü, ekleme (marka → kod seti deneme: sesi aç, gerekirse güç →
    ad), tam ekran IR kumanda. Kayıtlar `ayarlar` → `ir_kumandalar` (marka adı + kod seti sırası). Gönderim tek iş
    parçacığında; desteklenmeyen taşıyıcı frekansı en yakın desteklenene çekilir. Serif'in Honor telefonunda IR var;
    gerçek TV'de henüz denenmedi.

### Cast
- Chromecast: `CastClient.kt` (Cast v2, Default Media Receiver `CC1AD845`), dosyalar `MediaServer.kt` (rastgele
  adresli küçük HTTP sunucu, Range destekli) üzerinden sunulur.
- DLNA oynatıcıya gönderme: `DlnaClient.kt`. Telefonu DLNA sunucusu yapma: `DlnaMediaServer.kt`, `DlnaServerService.kt`.
- `CastService.kt`: ekran kapalıyken devam, bildirim ve kilit ekranı kontrolleri.
- `Subtitles.kt`: SRT → WebVTT; geçersiz UTF-8 ise Windows-1254 okunur.
- Chromecast MKV/AVI'yi genelde oynatamaz; kullanıcı uyarılır.

### Testler
`app/src/test`: protokol (`AtvProtocolTest`, `CastProtocolTest`), DLNA, altyazı, ağ ve test araçları (`NetToolsTest`). Saf (Android'siz) kod test edilir. Canlı yayın paketleyicisi, sunucu ve ekran kaydı mantığı: `LiveTsTest`, `ScreenRecTest`. Sistem bilgisi: `SysInfoTest`. Performans: `PerfStatsTest`. IR: `IrCodecTest`.

## Bilinen kısıtlar

- Yalnızca kodlu "Kablosuz hata ayıklama" sunan TV'lerde ADB eşleştirmesi yok (yalnızca 5555).
- Korumalı içerik (Netflix vb.) ekran görüntüsünde siyah çıkar.
- Nöbetçi TV tamamen yeniden başlayınca durur (ADB bağlantısında geri gelir). Philips uyku modundayken çalışmaya devam
  edip etmediği denenmedi; zamanlı açma için telefon o saatte ev Wi-Fi'ında olmalı.

## Yapılacaklar

### Planlanan (Serif'in sırası)
- **Yol haritası (30 Ekim 2026):** ~2 gün daha özellik → optimizasyon ve genel kontrol (Serif yaptıracak; sağlamlık:
  çökme kalkanı, anlaşılır hata mesajları, tüm araçların tek tek denenmesi) → cila. MKV/TS dönüştürme 1.0 sonrasına.
- **Sıradaki iş (Serif onayladı, 30 Ekim):** Performans penceresinin en üstüne "Genel bakış" kartı. İçerik: güç (açık/uyku),
  ekrandaki uygulama, ADB gecikmesi (ms; mevcut ölçüm komutunun süresi), açık kalma süresi (`/proc/uptime`), depolama
  çubuğu (`df /data`). "Sistem yükü": mevcut işlemci/bellek kartları + "RAM boşalt" düğmesi (`TvTools.freeRam`) + "En çok
  işlemci kullananlar" ilk 5 (ad, işlemci %, RAM) + "Tümünü gör" → Çalışan uygulamalar. Hafif olanlar (uptime, df, gecikme)
  saniyelik `PerfStats.COMMAND`'a eklenir; güç ve ekrandaki uygulama yalnızca açılışta ve "Yenile"de (5 sn'de bir değil).
  "Picture stream" ve gece otomatik temizlik yok (temizlik istenirse ayrı iş, nöbetçiye). Örnek: Serif'in gönderdiği
  "Manage the TV → Health → Now" ekran görüntüsü.
- Gerçek TV'de denenecekler: ekran kaydı, ekran yansıtma, hoparlör (hangi gönderme yolu Philips'te işliyor?).
- Kısayollar sekmesinin çalışır hâli.
- Cast: MKV/TS gibi biçimleri anında dönüştürerek oynatma (Windows'taki Airflow benzeri).
- Ayarlar: sol üstte küçük düğme; ortada TV adı (kayıtlı TV'ler ve bağlantı türü).
- İleride: uygulamanın adı "TV Everything" gibi bir şey olabilir.

### Sonra bakılacak fikirler (HoangTran0410/tv-remote, MIT)
Kendi planlanan özellikler bittikten sonra:
1. ADB'de Türkçe harf: metni TV panosuna koyup yapıştırmak.
2. Akıllı bağlantı hatası: tek sebep + çözüm (TV kapalı, IP değişmiş, ADB kapalı, VPN…).
3. VPN / mobil veri tuzağı: TV bağlantısını Wi-Fi'a zorlamak.
4. Kodlu kablosuz hata ayıklama ile bilgisayarsız ADB eşleştirmesi.
5. Geri alınabilir gereksiz uygulama listeleri (marka marka).
6. "TV dondu" tuşu; ekran kapalı ses açık; şu an çalan.
7. Widget ve hızlı ayar kutucukları.
8. Çökme kalkanı (bir hata tüm uygulamayı kapatmasın).

**Zamanlama kararı:** TV'ye ayrı uygulama kurulmadı. Uyku zamanlayıcısı (ADB), ses sınırı ve ekran süresi sınırı TV'deki
nöbetçide çalışır. Philips uyku modunda arka plan işlemlerini durduruyorsa ya da nöbetçi güvenilmez çıkarsa, TV'ye küçük
bir yardımcı uygulama (açılışta kendiliğinden başlayan) gerekecek; bu, Serif'e ayrıca sorulacak.
