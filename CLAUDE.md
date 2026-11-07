# TV Kumanda — çalışma notları

Bu dosya, projede çalışan her yeni sohbetin ilk okuduğu yerdir. Kod neyi yaptığını söyler; burası **neden** öyle
yapıldığını ve **nasıl** çalışılacağını anlatır. Önemli bir karar alındığında buraya ya da ilgili ayrıntı dosyasına bir satır eklenir.

**Ayrıntılar `docs/notlar/` altında, konuya göre ayrı dosyalarda.** Her sohbette yalnızca bu dosya okunur; bir alanda
çalışmaya başlarken önce o alanın dosyasını oku, yeni kararı da oraya yaz (bu dosya kısa kalsın, her adımda okunuyor).

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
  kontrol kurulur (send_later, gönderimden **4 dakika** sonra; Serif'in isteği); sürüm çıkınca Serif'e "çıktı" denip sürüm sayfasının linki paylaşılır.
- Yeni bir özellik eklerken README'deki özellik listesi ve "Henüz hazır olmayanlar" da güncellenir.

### Commit kuralları

- Commit mesajı **Türkçe** ve kullanıcının anlayacağı dilde: ilk satır doğrudan sürüm notuna yazılır.
  Örnek: `Araçlar: Özel DNS eklendi (otomatik geri dönüş)`.
- Mesajlara **Co-Authored-By / Claude-Session satırı eklenmez.**
- Yazar: `serif453 <202360588+serif453@users.noreply.github.com>`. Gerçek e-posta hiçbir commit'e girmez.
- İmza anahtarı, şifre, token gibi gizli bilgiler asla depoya girmez; yalnızca GitHub Secrets'ta durur.
- Repo herkese açık olacak: geçmiş yeniden yazılmaz, `main`'e zorla gönderim yapılmaz.

## Derleme ve sürüm

- Oturumda Android SDK yok; derleme yalnızca **GitHub Actions**'ta (`.github/workflows/build.yml`). Saf (Android'siz) kod ve
  testleri oturumda Kotlin derleyicisiyle çalıştırılabilir.
- `main`'e her gönderim: birim testleri + imzalı release APK → yeni sürüm `v0.N` (son etiket + 1). Hata veren derleme numara
  tüketmez. Sürüm notları = önceki sürümden bu yana commit'lerin ilk satırları.
- Derleme başlatmayanlar: `README.md`, `LICENSE`, `CLAUDE.md`, `docs/**`. Küçük değişiklikleri tek gönderimde topla.
- Sürüm kontrolü: `gh api repos/serif453/TvKumanda/releases/latest` (`gh release list` çalışmaz, GraphQL kapalı).
- İmza, hızlandırma, süre raporu, kilitli eylemler: `docs/notlar/derleme.md`.

## Mimari (harita)

Tek modül (`app`), Jetpack Compose, paket `com.serif.tvkumanda`. Ayrıntılar parantezdeki dosyada.

- **Bağlantı** (`RemoteLink`, `ConnMethod`): Standart (varsayılan) = `AtvRemote.kt`/`AtvProtocol.kt`, Google Android TV Remote
  v2 (TLS 6467 eşleştirme, 6466 kumanda), Türkçe harf destekler. ADB (gelişmiş) = `TvRemote.kt`, 5555, `app/src/main/java/dadb`
  (akış denetimli kopya); hızlı mod `KeyServer.java` TV'de `app_process` ile (ProGuard'da korunur); ADB'de Türkçe harf sadeleşir.
  `TvDiscovery.kt` mDNS, `TvPing`, Wake-on-LAN `MainActivity.WakeOnLan`.
- **Ekranlar:** `MainActivity.kt` (durum, bağlantı, Cast akışı), alt menü `BottomNav.kt`: Kumanda / Cast / Araçlar / Kısayollar.
  - Kumanda: `RemoteUi.kt` (iskelet, tema), `RemoteControls.kt` (yön tuşları, dokunmatik yüzeyler, yazma/klavye pencereleri), `RemoteLayout.kt`, cam tasarım `GlassUi.kt` + `GlassRemote.kt`; TV seçimi `TvPicker.kt` (pilin altından açılan, büyüyebilen cam panel) + `TvSheet.kt`
    (`docs/notlar/kumanda.md`).
  - Cast: `CastScreen.kt`, `MediaLibrary.kt`, `FullPlayer.kt`, Chromecast/DLNA (`docs/notlar/cast.md`).
  - Araçlar: `ToolsScreen.kt` + pencereler; ağ ve test, zamanlama ve nöbetçi (`Watcher.java`), teşhis kaydı, sistem bilgisi,
    performans, ekran kaydı (`docs/notlar/araclar.md`). **`id`'si olmayan kutucuk henüz hazır değildir.**
  - Canlı yayın: ekran yansıtma + telefonu TV hoparlörü yapma (`docs/notlar/canli-yayin.md`).
  - IR kumanda: telefonun kızılötesi vericisi, Flipper-IRDB + SmartIR + çevrimiçi irdb (`docs/notlar/ir.md`).
  - Kısayollar: `ShortcutsScreen.kt` (ekran, düzenleyiciler) + `Shortcuts.kt` (adım modeli, JSON kaydı, şablonlar, adım motoru; `ShortcutsTest`); TV ile konuşan kısım `MainActivity.runShortcut` (`docs/notlar/kumanda.md` → Kısayollar).
  - Ayarlar: sol üstteki ⚙️ → tam sayfa `SettingsPage`, değerler `AppSettings` (`AppSettings.kt`; `docs/notlar/kumanda.md`).
- **Testler** `app/src/test`: saf kod test edilir (protokoller, DLNA, altyazı, ağ, canlı yayın, ekran kaydı, sistem bilgisi,
  performans, IR).
- Serif'in TV'si: Philips (TPV) 2021/22 UHD, MediaTek MT5895, 4 × Cortex-A73, Android 11, yalnızca 32 bit, 2.7 GB RAM.
  Telefon: Honor (IR vericisi var). Ağında başka Chromecast yok, tek TV var.

## Bilinen kısıtlar

- Yalnızca kodlu "Kablosuz hata ayıklama" sunan TV'lerde ADB eşleştirmesi yok (yalnızca 5555).
- Korumalı içerik (Netflix vb.) ekran görüntüsünde siyah çıkar.
- Nöbetçi TV tamamen yeniden başlayınca durur (ADB bağlantısında geri gelir). Philips uyku modundayken çalışmaya devam
  edip etmediği denenmedi; zamanlı açma için telefon o saatte ev Wi-Fi'ında olmalı.

## Yapılacaklar

### Planlanan (Serif'in sırası)
- **Yol haritası (2 Kasım 2026, Serif):** özelliklerin ~%90'ı tamam; yeni özellik artık nadiren. Önümüzdeki ~1 ay:
  mevcut özellikleri olgunlaştırma ve hata azaltma, arada arayüze çeki düzen ve optimizasyon düzeltmeleri. Yeni özellik
  isteği gelirse bu önceliği hatırlat.
- (Önceki) **Yol haritası (30 Ekim 2026):** ~2 gün daha özellik → optimizasyon ve genel kontrol (Serif yaptıracak; sağlamlık:
  çökme kalkanı, anlaşılır hata mesajları, tüm araçların tek tek denenmesi) → cila. MKV/TS dönüştürme 1.0 sonrasına.
- Gerçek TV'de denenecekler: ekran kaydı, ekran yansıtma, hoparlör (hangi gönderme yolu Philips'te işliyor?).
- Kısayollar: ilk sürüm hazır (7 Kasım 2026); sırada ana ekrana kısayol, widget / hızlı ayar kutusu, zamanlı çalıştırma.
- Cast: TS temel yolu eklendi (`docs/notlar/cast.md`); sırada ses çevirme, sonra MKV (Windows'taki Airflow benzeri).
- İleride: uygulamanın adı "TV Everything" gibi bir şey olabilir.

### Sonra bakılacak fikirler (HoangTran0410/tv-remote, MIT)
Kendi planlanan özellikler bittikten sonra:
1. ADB'de Türkçe harf: metni TV panosuna koyup yapıştırmak.
2. Akıllı bağlantı hatası: tek sebep + çözüm (TV kapalı, IP değişmiş, ADB kapalı, VPN…).
3. ~~VPN / mobil veri tuzağı: TV bağlantısını Wi-Fi'a zorlamak.~~ Yapıldı (5 Kasım 2026, `docs/notlar/kumanda.md` → Bağlantı kararlılığı).
4. Kodlu kablosuz hata ayıklama ile bilgisayarsız ADB eşleştirmesi.
5. Geri alınabilir gereksiz uygulama listeleri (marka marka).
6. "TV dondu" tuşu; ekran kapalı ses açık; şu an çalan.
7. Widget ve hızlı ayar kutucukları.
8. Çökme kalkanı (bir hata tüm uygulamayı kapatmasın).
