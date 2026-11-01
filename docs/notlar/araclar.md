# Araçlar sekmesi — ayrıntılar

Araçlar sekmesi (ağ ve test, zamanlama ve nöbetçi, teşhis kaydı, sistem bilgisi, performans, ekran kaydı) üzerinde çalışırken okunur.

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
  - **Genel bakış (1 Kasım 2026, Serif'in onayıyla):** pencerenin üstünde sırayla Genel bakış, Sistem yükü,
    En çok işlemci kullananlar, Ağ, Donanım kartları; altında çekirdek halkaları ve grafik. Eski büyük Bellek kartı ile
    "İşlemci kullanımı" halkası kaldırıldı (Sistem yükü'ndeki iki küçük halka aynı bilgiyi veriyor). Saniyelik
    `PerfStats.COMMAND`'a hafifler eklendi: `/proc/uptime`, `df -k /data`, `/proc/net/dev` (wlan+eth bayt sayaçları →
    indirme/yükleme hızı). ADB gecikmesi = bu komutun süresi. `STATUS_COMMAND` (güç `mWakefulness`, ekrandaki uygulama,
    `mWifiInfo`, IP) yalnızca açılışta ve "Yenile"de. `HARDWARE_COMMAND` (getprop, cpuinfo, wm, display) bağlantı başına bir
    kez (`PerfCache.hardware`); RAM satırı bellek kartıyla aynı sayı olsun diye MemTotal'dan. "En çok işlemci kullananlar"
    3 sn'de bir `ps`. Wi-Fi "Link speed" = **bağlantı kapasitesi (teorik üst sınır)**, gerçek internet hızı değil; hız testi
    bilerek yok (Serif'in kararı). Gerçek TV'de henüz denenmedi.
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

**Zamanlama kararı:** TV'ye ayrı uygulama kurulmadı. Uyku zamanlayıcısı (ADB), ses sınırı ve ekran süresi sınırı TV'deki
nöbetçide çalışır. Philips uyku modunda arka plan işlemlerini durduruyorsa ya da nöbetçi güvenilmez çıkarsa, TV'ye küçük
bir yardımcı uygulama (açılışta kendiliğinden başlayan) gerekecek; bu, Serif'e ayrıca sorulacak.
- **Paylaş → "TV'ye gönder"** (`ShareSend.kt`): manifestte `.SendToTv` activity-alias (`*/*`, SEND ve SEND_MULTIPLE), yani
  her uygulamadan her dosya türü. "TV'de aç" (medya/link → Cast) ayrı kalır. `MainActivity.handleShare` alias'ı
  `intent.component` ile ayırır; alttan pencere açılır, TV'ye bağlanınca `TvFiles.sendFiles` ile Download'a gönderir.
  Standart bağlantıda dosya taşınamaz: pencere "ADB ile bağlan" sunar (türü değiştirip aynı TV'ye yeniden bağlanır).
  Gönderim sürerken pencere kaydırılarak kapanmaz; "Durdur" yarım dosyayı siler.
- **Hız (1 Kasım 2026, Serif'in onayıyla):** Performans ve Çalışan uygulamalar'daki sürekli ölçümler uygulama arkadayken
  duraklar (`rememberForeground` + `awaitForeground`, ToolSheets.kt); dönünce önceki ölçüm bayat sayılır. Ekran kaydı yoklaması
  bilerek hariç. "ADB gecikmesi" = `TvRemote.shellTimed` (kilit alındıktan sonraki süre; süreç ölçümünü bekleme sayılmaz).
  Alarm izni saniyede bir değil, uygulamaya her dönüşte (ON_RESUME) kontrol edilir.
- Teşhis kaydı (`DiagLog`) dosyaya arka planda tek sırayla yazar; çökmede tamamı hemen yazılır ve sıradakiler bırakılır.
  Dosyayı hemen okuyacak yer önce `DiagLog.flush()` çağırır (test de öyle).
- Genel tarama (1 Kasım 2026): kullanılmayan 3 fonksiyon ve dadb'nin `adbserver`/`forwarding` parçaları (bilgisayardaki
  ADB sunucusu, port yönlendirme; uygulamada hiç kullanılmıyordu) silindi.
- **Gelişmiş tuşlar bölümü kaldırıldı (v0.148, Serif'in isteği):** Medya tuşları ve Sistem kısayolları yalnızca Kumanda'daki 🎵 ve fn tuşlarından açılır (aynı `ToolSheet`, `ToolId`'ler duruyor).
- **v0.149 (Serif'in isteği):** "Ses sınırı" aracı kaldırıldı (`VolumeCapTool`, `ToolId.VOLUME_CAP`); nöbetçide `vol_max` desteği duruyor ama her ADB bağlantısında eskiden açık kalmış sınır kapatılır. "Uygulamalar" bölümü "Sistem" başlığına katıldı.
