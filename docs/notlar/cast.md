# Cast sekmesi — ayrıntılar

Chromecast, DLNA ve telefonu DLNA sunucusu yapma üzerinde çalışırken okunur.

- Chromecast: `CastClient.kt` (Cast v2, Default Media Receiver `CC1AD845`), dosyalar `MediaServer.kt` (rastgele
  adresli küçük HTTP sunucu, Range destekli) üzerinden sunulur.
- DLNA oynatıcıya gönderme: `DlnaClient.kt`. Telefonu DLNA sunucusu yapma: `DlnaMediaServer.kt`, `DlnaServerService.kt`.
- `CastService.kt`: ekran kapalıyken devam, bildirim ve kilit ekranı kontrolleri.
- `Subtitles.kt`: SRT → WebVTT; geçersiz UTF-8 ise Windows-1254 okunur.
- Chromecast MKV/AVI'yi genelde oynatamaz; kullanıcı uyarılır.
- **Cihaz seçimi (1 Kasım 2026, Serif'in isteği):** Cast sekmesi açılınca **kendiliğinden arama ya da bağlantı yok**
  (`castWarmup` boş). Chromecast ve DLNA seçicileri başta "Cihaz seç"; seçicide "Ara" → bulunanlar listelenir → dokununca
  seçilir (Chromecast'te hemen bağlanır). Seçim hatırlanır, bağlanmadan geri yüklenir; ilk gönderimde bağlanılır.
  Kayıt: `cc_secim` = "tv" (bağlı TV'yi izler) ya da `ad\u0001ip`; `dlna_secim` = ad, ip, location, avControl, rcControl
  (`\u0001` ile). Eski `cc_cihaz` / `dlna_cihaz` anahtarları artık okunmuyor. Hatırlanan cihaz aramada bulunmasa da listede ✓ ile
  görünür; adı aynı cihaz yeni adresle bulunursa kayıt güncellenir. Chromecast: `CcUi.selected` başka cihaz, `useTv` bağlı TV
  (TV bağlantısı gerekir). Aramada TV'nin kendisi "bağlı TV" diye işaretlenir. Arama 20 sn sürer, sonra durur (pil). Serif'in
  ağında başka Chromecast yok; başka cihazda denenmedi.
- **Galeri hızı (1 Kasım 2026, Serif'in onayıyla):** `MediaLibrary` tek ortak nesne (`MediaLibrary.get`): Cast sekmesi,
  bildirim kapağı ve DLNA sunucusu aynı önbelleği kullanır. Son okunan klasör listesi bellekte (`cachedAlbums`): bir türe
  yeniden girince anında görünür, telefondan arkadan yenilenir; değişiklik yoksa aynı liste döner. Küçük resim önbelleği
  belleğin 1/6'sı (24–96 MB); önbellekteki resim anında gösterilir; yükleme en fazla 4 eş zamanlı (`ThumbLoader`);
  müzikte kapak albüm başına bir kez. Okuma tabloları (`addedAt`, albüm adları) her okumaya yerel (eş zamanlı okumada çakışmasın).

## Paylaşımdan oynatma (2 Kasım 2026, Serif'in isteği)
- "TV'de aç" ile gelen medya: seçili yolda (Chromecast/DLNA) cihaz hazırsa hemen oynar. "Bağlı TV" seçili ve TV'ye
  bağlanılıyorsa bağlanınca oynar. Aksi hâlde `SharedWait` (CastScreen.kt): Cast ekranı başa döner, ilgili seçici kendiliğinden
  açılır ve aranır, üstte "Paylaşılan dosya bekliyor" satırı (✕ vazgeç). `pickCc`/`pickDlna` seçince `playPendingIfReady()`.
  Eskiden cihaz seçili değilse dosya sessizce düşüyordu. Bekleyen paylaşım varken bağlantı hatası TV panelini açmaz.
- Paylaşım menüsü kısayolları (`ShareShortcuts.kt`, `res/xml/share_shortcuts.xml`, Android 11+): "TV'de oynat" (görsel/video/ses)
  ve "TV'ye gönder" (her dosya), ikisi de MainActivity'ye, `EXTRA_SHORTCUT_ID` ile ayrılır. Honor'un paylaşım menüsü aynı
  uygulamanın seçeneklerini tek simgede topluyor; bu üst satırı gösterip göstermediği denenecek.

## Tek cast düğmesi (2 Kasım 2026 gece, Serif'in isteği; önceki "Paylaşımdan oynatma" notunun yerine)
- Eski düzen (Chromecast ve DLNA için ayrı seçici + ayrı Fotoğraf/Video/Müzik sırası, "bağlı TV" seçeneği, seçimin
  hatırlanıp açılışta geri yüklenmesi) kaldırıldı. `SharedWait` yok.
- `CastDevices.kt`: `CastButton` (Cast ekranının ve albüm başlığının sağında), `CastDevicePanel` (alttan panel, açılınca
  Chromecast mDNS + DLNA SSDP aramaları birlikte; tek liste, etiketli; bağlıyken "Bağlantıyı kes"). `CastPanel` nesnesi
  MainActivity'nin `openCastPanel`/`closeCastPanel`/`disconnectCast` eylemlerini taşır.
- Seçim tek: `pickCc` DLNA seçimini, `pickDlna` Chromecast seçimini temizler; `selectedRoute` seçilen cihazdan gelir. Chromecast
  hedefi yalnızca `ccUi.selected` (kumanda bağlantısından bağımsız); cihaz kumandanın TV'siyse oynatmadan önce uyandırılır.
- Bağlı değilken öğeye dokunma / paylaşım / dosya seçici: `openCastPanel(ad) { iş }` → seçince `afterCastPick()` işi çalıştırır.
  Panel seçim yapmadan kapanırsa bekleyen iş iptal.
- Hatırlama: `AppSettings.castRemember` ("cast_hatirla": NONE / TOP varsayılan / AUTO). Son cihaz "cast_son" (tür + ad).
  TOP: panelde başta "Son kullanılan". AUTO: Cast sekmesine girince (`castWarmup`) ya da her aramada (`maybeAutoPick`) son cihaz
  bulunursa bağlanılır; oturumda "Bağlantıyı kes" dendiyse denenmez. Uygulama açılışında arka planda bağlanılmaz.
- DLNA durdurma, seçim hemen değişebileceği için cihaz önceden alınarak yapılır (`stopRouteBlocking(route, dev)`).

## Fotoğraf görüntüleyici (2 Kasım 2026 gece, Serif'in isteği)
- Fotoğraf albümünde öğeye dokunmak artık doğrudan cast etmez: `PhotoViewer.kt` tam ekran (Dialog) açılır; `HorizontalPager` ile
  gezinme, iki parmak / çift dokunma yakınlaştırma (yakınken sayfa kaydırma kapalı), tek dokunuş üst/alt çubukları gizler.
- Tam boy foto `MediaLibrary.fullImage` (uzun kenar ~2048, 2'nin kuvvetiyle küçültme, EXIF yönü); önce küçük resim görünür.
- Takip: açarken cihaz bağlıysa (`castDeviceName != null`) foto hemen TV'ye gider ve takip açık başlar. Değilse "TV'de göster"
  takibi açar (bağlı değilse cihaz paneli). Kaydırma durunca 350 ms sonra gönderilir. TV'deki foto başka yoldan değişirse
  (slayt, kart) görüntüleyici oraya kayar (`lastSent` ile kendi gönderdiğimizden ayrılır). Videolar eskisi gibi dokununca TV'ye.
- Görüntüleyici açıkken cihaz paneli onun penceresinin içinde çizilir; Cast ekranı kendi panelini çizmez.

## Telefonda müzik (2 Kasım 2026 gece, Serif'in isteği; sade)
- `PhoneMusic.kt`: `PhonePlayer` (MediaPlayer, ses odağı; kayıpta duraklar) + `PhoneMiniCard` (Cast ekranının altında) +
  `PhoneMusicPlayer` (tam ekran Dialog). Bağlı değilken şarkıya dokunmak telefonda çalar; bağlıyken eskisi gibi TV'de.
- Serif'in kararı: yalnızca uygulama açıkken çalar (MainActivity ON_STOP → duraklat; arka plan servisi/bildirim yok),
  karıştır/tekrarla yok; albüm sonunda durur.
- "TV'de çal": konum saniye olarak `onCastItem(..., startAt)` → `playFromList(startAt)` → `castMedia(startAt)`. TV'de müzik
  başlayınca `castMedia` `PhonePlayer.stop()` çağırır. Panel iptal edilirse telefon duraklatılmış kalır.
- (2 Kasım gece, sonra) Alttaki "TV'de göster" düğmesi kaldırıldı. Yerine `Filmstrip` (küçük resim şeridi; seçili ortada ve
  beyaz çerçeveli, TV'deki fotoda mavi cast işareti; dokununca atlar, 3'ten uzaksa animasyonsuz). Takip başta hep açık; bağlı
  değilken üstteki "Bağlan"dan cihaz seçilince bakılan foto gider. Bağlıyken üst çubukta "Takip" aç/kapa.
