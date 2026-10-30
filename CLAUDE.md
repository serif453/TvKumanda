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
- Derleme hızlandırma: Gradle 3 GB, Kotlin 2 GB bellek, derleme önbelleği açık (`gradle.properties`).

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
- Kısayollar: `ShortcutsScreen.kt` — şimdilik yalnızca görünüm.

### Cast
- Chromecast: `CastClient.kt` (Cast v2, Default Media Receiver `CC1AD845`), dosyalar `MediaServer.kt` (rastgele
  adresli küçük HTTP sunucu, Range destekli) üzerinden sunulur.
- DLNA oynatıcıya gönderme: `DlnaClient.kt`. Telefonu DLNA sunucusu yapma: `DlnaMediaServer.kt`, `DlnaServerService.kt`.
- `CastService.kt`: ekran kapalıyken devam, bildirim ve kilit ekranı kontrolleri.
- `Subtitles.kt`: SRT → WebVTT; geçersiz UTF-8 ise Windows-1254 okunur.
- Chromecast MKV/AVI'yi genelde oynatamaz; kullanıcı uyarılır.

### Testler
`app/src/test`: protokol (`AtvProtocolTest`, `CastProtocolTest`), DLNA, altyazı, ağ ve test araçları (`NetToolsTest`). Saf (Android'siz) kod test edilir.

## Bilinen kısıtlar

- Yalnızca kodlu "Kablosuz hata ayıklama" sunan TV'lerde ADB eşleştirmesi yok (yalnızca 5555).
- Korumalı içerik (Netflix vb.) ekran görüntüsünde siyah çıkar.
- Nöbetçi TV tamamen yeniden başlayınca durur (ADB bağlantısında geri gelir). Philips uyku modundayken çalışmaya devam
  edip etmediği denenmedi; zamanlı açma için telefon o saatte ev Wi-Fi'ında olmalı.

## Yapılacaklar

### Planlanan (Serif'in sırası)
- Araçlar'da hazır olmayanlar: ekran kaydı; ekran yansıtma; telefonu TV hoparlörüne bağlama.
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
